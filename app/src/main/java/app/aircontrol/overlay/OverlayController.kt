package app.aircontrol.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import app.aircontrol.gesture.ControlEvent

/**
 * Owns the full-screen accessibility overlay window. The window is added only while something is
 * shown (arming, armed, fading out, debug HUD) and removed shortly after the view goes idle.
 * TYPE_ACCESSIBILITY_OVERLAY is a trusted overlay: injected touches pass through it and apps do
 * not consider themselves obscured.
 */
class OverlayController(private val service: AccessibilityService) {

    val view = OverlayView(service)

    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private val detachRunnable = Runnable { if (view.isIdle && !keepAlive) detach() }

    /** Keeps the window attached (e.g. debug HUD / skeleton preview while tracking runs). */
    var keepAlive = false
        set(value) {
            if (field == value) return
            field = value
            if (value) attach() else view.postInvalidateOnAnimation()
        }

    init {
        view.onIdle = {
            handler.removeCallbacks(detachRunnable)
            handler.postDelayed(detachRunnable, 1500)
        }
    }

    fun onEvent(e: ControlEvent) {
        val needsWindow = when (e) {
            is ControlEvent.Pointer -> e.visible
            is ControlEvent.ArmProgress -> e.progress > 0f
            is ControlEvent.BindProgress -> e.progress > 0f
            is ControlEvent.Calibration -> true
            is ControlEvent.TouchMove, is ControlEvent.ClickRejected -> false
            else -> true
        }
        if (needsWindow) attach()
        if (attached) view.onEvent(e)
    }

    /** Current screen size in pixels (rotation-aware), for converting normalized coordinates. */
    fun screenSize(): Point {
        if (attached && view.width > 0 && view.height > 0) return Point(view.width, view.height)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    fun attach() {
        handler.removeCallbacks(detachRunnable)
        if (attached) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "AirControl overlay"
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        try {
            wm.addView(view, lp)
            attached = true
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
        }
    }

    fun detach() {
        handler.removeCallbacks(detachRunnable)
        if (!attached) return
        attached = false
        try {
            wm.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
    }

    private companion object {
        const val TAG = "OverlayController"
    }
}
