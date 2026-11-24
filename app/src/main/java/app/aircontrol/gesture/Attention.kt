package app.aircontrol.gesture

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Where the user's head and eyes point, in degrees (user view). Only meaningful relative to an
 * [AttentionCalibration]: the camera is rarely exactly where the screen is.
 */
data class FaceObservation(
    val headYaw: Float,
    val headPitch: Float,
    val eyeYaw: Float,
    val eyePitch: Float,
) {
    /** Combined gaze direction (head + eyes). */
    val gazeYaw: Float get() = headYaw + eyeYaw
    val gazePitch: Float get() = headPitch + eyePitch

    companion object {
        /** Eye-look blendshapes the attention estimate uses (also stored in recorded clips). */
        val BLENDSHAPES = listOf(
            "eyeLookInLeft", "eyeLookOutLeft", "eyeLookUpLeft", "eyeLookDownLeft",
            "eyeLookInRight", "eyeLookOutRight", "eyeLookUpRight", "eyeLookDownRight",
        )

        /** Degrees of eye rotation for a blendshape value of 1. */
        private const val EYE_YAW_DEG = 30f
        private const val EYE_PITCH_DEG = 25f

        /**
         * From MediaPipe's facial transformation matrix (4x4, 16 values) and blendshape scores.
         * Only differences from the calibration matter, so the matrix layout (row/column-major)
         * merely flips signs consistently.
         */
        fun from(matrix: FloatArray, blend: Map<String, Float>, mirror: Boolean): FaceObservation {
            // Forward axis of the head = third column of the rotation.
            val fx = matrix[8]; val fy = matrix[9]; val fz = matrix[10]
            val n = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f)
            var yaw = Math.toDegrees(atan2((fx / n).toDouble(), (fz / n).toDouble())).toFloat()
            val pitch = Math.toDegrees(asin((-fy / n).coerceIn(-1f, 1f).toDouble())).toFloat()
            fun b(k: String) = blend[k] ?: 0f
            // Looking to the user's right: right eye looks out, left eye looks in.
            val right = (b("eyeLookInLeft") + b("eyeLookOutRight")) / 2f
            val left = (b("eyeLookOutLeft") + b("eyeLookInRight")) / 2f
            var eyeYaw = (right - left) * EYE_YAW_DEG
            val eyePitch = ((b("eyeLookUpLeft") + b("eyeLookUpRight")) - (b("eyeLookDownLeft") + b("eyeLookDownRight"))) / 2f * EYE_PITCH_DEG
            if (mirror) { yaw = -yaw; eyeYaw = -eyeYaw }
            return FaceObservation(yaw, pitch, eyeYaw, eyePitch)
        }
    }
}

/**
 * Is the user looking at the screen? Scores the gaze direction against the calibrated one:
 * 1 = right on it, falling off with a Gaussian of width [AttentionCalibration.spread].
 * Not thread-safe; owned by [GestureEngine].
 */
internal class Attention {
    /** Latest score 0..1, or -1 when unknown (no calibration or no face for a while). */
    var score = -1f
        private set
    private var lastFaceT = Long.MIN_VALUE / 4
    private var smoothed = -1f

    fun update(t: Long, face: FaceObservation?, cal: AttentionCalibration?) {
        if (cal == null) {
            score = -1f
            smoothed = -1f
            return
        }
        if (face != null) {
            lastFaceT = t
            val dy = face.gazeYaw - cal.yaw
            val dp = face.gazePitch - cal.pitch
            val tol = cal.spread.coerceAtLeast(MIN_TOLERANCE_DEG)
            val s = exp(-0.5f * (dy * dy + dp * dp) / (tol * tol))
            smoothed = if (smoothed < 0f) s else smoothed + (s - smoothed) * 0.5f
            score = smoothed
        } else if (t - lastFaceT > FACE_TIMEOUT_MS) {
            // No face: neither confirms nor blocks.
            smoothed = -1f
            score = NEUTRAL
        }
    }

    fun reset() {
        score = -1f
        smoothed = -1f
    }

    companion object {
        const val NEUTRAL = 0.5f
        const val MIN_TOLERANCE_DEG = 8f
        private const val FACE_TIMEOUT_MS = 700L

        /** Calibration from gaze samples taken while looking at the screen centre. */
        fun calibrate(samples: List<FaceObservation>): AttentionCalibration? {
            if (samples.size < 8) return null
            val yaw = samples.map { it.gazeYaw }
            val pitch = samples.map { it.gazePitch }
            val my = yaw.average().toFloat()
            val mp = pitch.average().toFloat()
            val sd = sqrt(samples.indices.sumOf { i ->
                val a = yaw[i] - my; val b = pitch[i] - mp
                (a * a + b * b).toDouble()
            } / samples.size).toFloat()
            // A tolerance a few times the natural jitter, but never so tight that blinking fails.
            return AttentionCalibration(my, mp, (sd * 3f).coerceIn(MIN_TOLERANCE_DEG, 25f))
        }
    }
}
