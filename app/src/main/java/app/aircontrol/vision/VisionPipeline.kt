package app.aircontrol.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import app.aircontrol.gesture.FaceObservation
import app.aircontrol.gesture.FrameObservation
import app.aircontrol.gesture.HandObservation
import app.aircontrol.gesture.HandSide
import app.aircontrol.gesture.P3
import app.aircontrol.gesture.PoseObservation
import app.aircontrol.gesture.Signs
import app.aircontrol.settings.Accelerator
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ConcurrentHashMap

/**
 * MediaPipe hand + gesture recognition (every frame), upper-body pose and face (each every Nth frame,
 * on different frames), in LIVE_STREAM mode. Results are converted to user-view
 * [FrameObservation]s (mirrored if needed).
 */
class VisionPipeline(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on a MediaPipe thread. [frame] is the analysed (un-mirrored) bitmap. */
        fun onObservation(obs: FrameObservation, frame: Bitmap, inferenceMs: Long)
        fun onPipelineError(message: String)
    }

    @Volatile var mirror = true
    @Volatile var poseEnabled = true
    @Volatile var poseEvery = 3
    /** Also run pose while disarmed. Needed when the engine requires a visible body before it accepts the arm sign. */
    @Volatile var poseWhileDisarmed = false
    /** Flips the detected left/right hand (safety switch in case the label comes out inverted). */
    @Volatile var swapHands = false
    /** Face (attention gate): runs while armed when enabled, or always while [faceAlways] (calibration). */
    @Volatile var faceEnabled = false
    @Volatile var faceAlways = false

    var delegateName = ""
        private set

    @Volatile private var recognizer: GestureRecognizer? = null
    @Volatile private var pose: PoseLandmarker? = null
    @Volatile private var face: FaceLandmarker? = null

    val ready: Boolean get() = recognizer != null
    private var lastTs = 0L
    private var frameCounter = 0
    private val submitTimes = ConcurrentHashMap<Long, Long>()
    private val aspects = ConcurrentHashMap<Long, Float>()

    @Volatile private var latestPose: PoseObservation? = null
    @Volatile private var latestPoseTs = 0L
    @Volatile private var latestFace: FaceObservation? = null
    @Volatile private var latestFaceTs = 0L

    // Flow control: one frame in flight per model, so latency can never build up when inference is
    // slower than the camera. 0 = idle; a stuck flag expires after IN_FLIGHT_TIMEOUT_MS.
    @Volatile private var handsInFlightSince = 0L
    @Volatile private var poseInFlightSince = 0L
    @Volatile private var faceInFlightSince = 0L

    /** True while the previous frame is still being analysed: the caller should drop this one. */
    fun busy(nowMs: Long): Boolean {
        val since = handsInFlightSince
        return since != 0L && nowMs - since < IN_FLIGHT_TIMEOUT_MS
    }

    /** Loads the models; tries the GPU first in [Accelerator.AUTO]. Blocking (~100–500 ms). */
    fun start(accelerator: Accelerator) {
        val order = when (accelerator) {
            Accelerator.AUTO -> listOf(Delegate.GPU, Delegate.CPU)
            Accelerator.GPU -> listOf(Delegate.GPU)
            Accelerator.CPU -> listOf(Delegate.CPU)
        }
        var lastError: Throwable? = null
        for (d in order) {
            try {
                recognizer = createRecognizer(d)
                delegateName = d.name
                break
            } catch (t: Throwable) {
                Log.w(TAG, "GestureRecognizer with $d failed", t)
                lastError = t
            }
        }
        if (recognizer == null) {
            listener.onPipelineError("Could not load hand model: ${lastError?.message}")
            return
        }
        // Pose is optional; CPU is plenty for the lite model at a reduced rate.
        pose = try {
            createPose(if (delegateName == "GPU") Delegate.GPU else Delegate.CPU)
        } catch (t: Throwable) {
            Log.w(TAG, "PoseLandmarker GPU failed, trying CPU", t)
            try { createPose(Delegate.CPU) } catch (t2: Throwable) { Log.w(TAG, "PoseLandmarker unavailable", t2); null }
        }
        // Face is optional too (attention gate); the CPU keeps the GPU for the hands.
        face = try { createFace(Delegate.CPU) } catch (t: Throwable) { Log.w(TAG, "FaceLandmarker unavailable", t); null }
    }

    /** Submits a frame; called from the frame source thread. */
    fun submit(bitmap: Bitmap, armed: Boolean) {
        val rec = recognizer ?: return
        val now = SystemClock.uptimeMillis()
        val ts = if (now > lastTs) now else lastTs + 1
        lastTs = ts
        submitTimes[ts] = now
        aspects[ts] = bitmap.width.toFloat() / bitmap.height.toFloat()
        handsInFlightSince = now
        try {
            rec.recognizeAsync(BitmapImageBuilder(bitmap).build(), ts)
        } catch (e: Exception) {
            handsInFlightSince = 0L
            submitTimes.remove(ts); aspects.remove(ts)
            Log.w(TAG, "recognizeAsync failed", e)
            return
        }
        val every = poseEvery.coerceAtLeast(2)
        val slot = frameCounter++ % every
        val p = pose
        val poseIdle = poseInFlightSince.let { it == 0L || now - it > IN_FLIGHT_TIMEOUT_MS }
        if (p != null && poseEnabled && (armed || poseWhileDisarmed) && poseIdle && slot == 0) {
            poseInFlightSince = now
            try {
                p.detectAsync(BitmapImageBuilder(bitmap).build(), ts)
            } catch (e: Exception) {
                poseInFlightSince = 0L
                Log.w(TAG, "pose detectAsync failed", e)
            }
        }
        val fl = face
        val faceIdle = faceInFlightSince.let { it == 0L || now - it > IN_FLIGHT_TIMEOUT_MS }
        if (fl != null && ((faceEnabled && armed) || faceAlways) && faceIdle && slot == 1) {
            faceInFlightSince = now
            try {
                fl.detectAsync(BitmapImageBuilder(bitmap).build(), ts)
            } catch (e: Exception) {
                faceInFlightSince = 0L
                Log.w(TAG, "face detectAsync failed", e)
            }
        }
        // Drop bookkeeping for frames MediaPipe skipped.
        if (submitTimes.size > 64) {
            val cutoff = ts - 2000
            submitTimes.keys.removeIf { it < cutoff }
            aspects.keys.removeIf { it < cutoff }
        }
    }

    fun close() {
        try { recognizer?.close() } catch (_: Exception) {}
        try { pose?.close() } catch (_: Exception) {}
        try { face?.close() } catch (_: Exception) {}
        recognizer = null
        pose = null
        face = null
    }

    private fun createRecognizer(delegate: Delegate): GestureRecognizer {
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(HAND_MODEL).setDelegate(delegate).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, image -> handsInFlightSince = 0L; onHands(result, image) }
            .setErrorListener { e -> handsInFlightSince = 0L; Log.w(TAG, "recognizer error", e) }
            .build()
        return GestureRecognizer.createFromOptions(context, options)
    }

    private fun createPose(delegate: Delegate): PoseLandmarker {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(POSE_MODEL).setDelegate(delegate).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> poseInFlightSince = 0L; onPose(result) }
            .setErrorListener { e -> poseInFlightSince = 0L; Log.w(TAG, "pose error", e) }
            .build()
        return PoseLandmarker.createFromOptions(context, options)
    }

    private fun createFace(delegate: Delegate): FaceLandmarker {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACE_MODEL).setDelegate(delegate).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result, _ -> faceInFlightSince = 0L; onFace(result) }
            .setErrorListener { e -> faceInFlightSince = 0L; Log.w(TAG, "face error", e) }
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    private fun onFace(result: FaceLandmarkerResult) {
        val matrix = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
        val blend = result.faceBlendshapes().orElse(null)?.firstOrNull()
        latestFace = if (matrix != null && matrix.size >= 16) {
            val scores = blend?.associate { it.categoryName() to it.score() }.orEmpty()
            FaceObservation.from(matrix, scores, mirror)
        } else {
            null
        }
        latestFaceTs = result.timestampMs()
    }

    private fun onPose(result: PoseLandmarkerResult) {
        val lms = result.landmarks().firstOrNull()
        latestPose = lms?.let { list ->
            PoseObservation(
                landmarks = list.map { it.toP3() },
                visibility = list.map { it.visibility().orElse(0f) },
            )
        }
        latestPoseTs = result.timestampMs()
    }

    private fun onHands(result: GestureRecognizerResult, image: MPImage) {
        val ts = result.timestampMs()
        val submitted = submitTimes.remove(ts)
        val aspect = aspects.remove(ts) ?: (image.width.toFloat() / image.height.toFloat())
        val inference = submitted?.let { SystemClock.uptimeMillis() - it } ?: 0L
        val hands = result.landmarks().mapIndexed { i, lms ->
            val g = result.gestures().getOrNull(i)?.firstOrNull()
            // MediaPipe assumes a mirrored (selfie) image for its Left/Right label. The frames analysed here
            // are never mirrored, so the label is inverted: flip it (unless the user swapped it back).
            val label = result.handedness().getOrNull(i)?.firstOrNull()?.categoryName()
            val flipped = when (label) {
                "Left" -> HandSide.RIGHT
                "Right" -> HandSide.LEFT
                else -> HandSide.ANY
            }
            val side = if (swapHands) when (flipped) {
                HandSide.LEFT -> HandSide.RIGHT
                HandSide.RIGHT -> HandSide.LEFT
                HandSide.ANY -> HandSide.ANY
            } else flipped
            HandObservation(
                landmarks = lms.map { it.toP3() },
                gesture = g?.categoryName()?.takeIf { it.isNotEmpty() } ?: Signs.NONE,
                gestureScore = g?.score() ?: 0f,
                world = result.worldLandmarks().getOrNull(i)?.map { w ->
                    if (mirror) P3(-w.x(), w.y(), w.z()) else P3(w.x(), w.y(), w.z())
                },
                side = side,
            )
        }
        val poseObs = latestPose?.takeIf { poseEnabled && ts - latestPoseTs < 500 }
        val faceObs = latestFace?.takeIf { (faceEnabled || faceAlways) && ts - latestFaceTs < 500 }
        val obs = FrameObservation(ts, aspect, hands, poseObs, faceObs)
        val frame = try { BitmapExtractor.extract(image) } catch (e: Exception) { null } ?: return
        listener.onObservation(obs, frame, inference)
    }

    private fun NormalizedLandmark.toP3(): P3 =
        if (mirror) P3(1f - x(), y(), z()) else P3(x(), y(), z())

    companion object {
        private const val TAG = "VisionPipeline"
        private const val IN_FLIGHT_TIMEOUT_MS = 1000L
        const val HAND_MODEL = "gesture_recognizer.task"
        const val POSE_MODEL = "pose_landmarker_lite.task"
        const val FACE_MODEL = "face_landmarker.task"
    }
}
