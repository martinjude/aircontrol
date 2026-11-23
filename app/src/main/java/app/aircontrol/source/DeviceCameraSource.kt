package app.aircontrol.source

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Display
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Front/back device camera through CameraX ImageAnalysis (works from a camera foreground service). */
class DeviceCameraSource(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val front: Boolean,
    private val sink: FrameSink,
) : FrameSource {

    override val label = if (front) "Front camera" else "Back camera"

    private val main = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var executor: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var started = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) analysis?.targetRotation = displayRotation()
        }
    }

    override fun start() {
        if (started) return
        started = true
        sink.onStatus("Opening $label…")
        executor = Executors.newSingleThreadExecutor { Thread(it, "camera-analysis") }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!started) return@addListener
            try {
                bind(future.get())
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                sink.onStatus("Camera error: ${e.message}", error = true)
            }
        }, ContextCompat.getMainExecutor(context))
        displayManager.registerDisplayListener(displayListener, main)
    }

    private fun bind(p: ProcessCameraProvider) {
        provider = p
        val wanted = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val selector = when {
            p.hasCamera(wanted) -> wanted
            p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        val ia = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .setTargetRotation(displayRotation())
            .build()
        ia.setAnalyzer(executor!!) { proxy ->
            try {
                val now = SystemClock.uptimeMillis()
                if (sink.wantsFrame(now)) sink.onFrame(proxy.toBitmap(), now)
            } catch (e: Exception) {
                Log.w(TAG, "frame conversion failed", e)
            } finally {
                proxy.close()
            }
        }
        analysis = ia
        p.unbindAll()
        val camera = p.bindToLifecycle(owner, selector, ia)
        camera.cameraInfo.cameraState.observe(owner) { state ->
            val err = state.error
            when {
                err != null -> sink.onStatus(
                    when (err.code) {
                        CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                            "Camera is used by another app — waiting…"
                        CameraState.ERROR_CAMERA_DISABLED -> "Camera disabled (privacy toggle or policy)"
                        else -> "Camera error (${err.code})"
                    },
                    error = true,
                )
                state.type == CameraState.Type.OPEN -> sink.onStatus("$label running")
                state.type == CameraState.Type.PENDING_OPEN -> sink.onStatus("Waiting for camera…")
                else -> {}
            }
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        displayManager.unregisterDisplayListener(displayListener)
        analysis?.clearAnalyzer()
        analysis = null
        provider?.unbindAll()
        provider = null
        executor?.shutdown()
        executor = null
    }

    private fun displayRotation(): Int = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0

    private companion object {
        const val TAG = "DeviceCameraSource"
    }
}
