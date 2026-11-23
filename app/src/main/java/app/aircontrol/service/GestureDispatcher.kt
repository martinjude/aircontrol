package app.aircontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.aircontrol.gesture.SwipeDir

/**
 * Injects touches through [AccessibilityService.dispatchGesture]. Drags are streamed as
 * continued strokes: each segment starts where the previous ended and is only dispatched after
 * the previous one completed; intermediate moves are coalesced to the latest target.
 * All methods must be called on the main thread.
 */
class GestureDispatcher(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())

    private var stroke: GestureDescription.StrokeDescription? = null
    private var last: PointF? = null
    private var target: PointF? = null
    private var upPending = false
    private var inFlight = false
    private var dragActive = false

    fun tap(x: Float, y: Float) {
        cancel()
        val path = Path().apply { moveTo(x.safe(), y.safe()) }
        send(GestureDescription.StrokeDescription(path, 0, TAP_MS), final = true)
    }

    fun swipe(dir: SwipeDir, w: Int, h: Int, lengthFraction: Float) {
        cancel()
        val cx = w / 2f
        val cy = h / 2f
        val half = lengthFraction.coerceIn(0.1f, 0.9f) / 2f
        val dx = w * half
        val dy = h * half
        val (x0, y0, x1, y1) = when (dir) {
            SwipeDir.UP -> floatArrayOf(cx, cy + dy, cx, cy - dy)
            SwipeDir.DOWN -> floatArrayOf(cx, cy - dy, cx, cy + dy)
            SwipeDir.LEFT -> floatArrayOf(cx + dx, cy, cx - dx, cy)
            SwipeDir.RIGHT -> floatArrayOf(cx - dx, cy, cx + dx, cy)
        }
        val path = Path().apply { moveTo(x0, y0); lineTo(x1, y1) }
        send(GestureDescription.StrokeDescription(path, 0, SWIPE_MS), final = true)
    }

    fun touchDown(x: Float, y: Float) {
        cancel()
        val p = PointF(x.safe(), y.safe())
        dragActive = true
        last = p
        val path = Path().apply { moveTo(p.x, p.y) }
        val s = GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true)
        stroke = s
        send(s, final = false)
    }

    fun touchMove(x: Float, y: Float) {
        if (!dragActive) return
        target = PointF(x.safe(), y.safe())
        if (!inFlight) pump()
    }

    fun touchUp(x: Float, y: Float) {
        if (!dragActive) return
        target = PointF(x.safe(), y.safe())
        upPending = true
        if (!inFlight) pump()
    }

    /** Lifts any held touch (right away, or as soon as the in-flight segment completes). */
    fun cancel() {
        if (!dragActive) return
        upPending = true
        if (!inFlight) pump()
    }

    private fun pump() {
        val prev = stroke ?: return
        val from = last ?: return
        val to = target ?: if (upPending) from else return
        target = null
        val up = upPending
        val path = Path().apply {
            moveTo(from.x, from.y)
            if (to.x != from.x || to.y != from.y) lineTo(to.x, to.y)
        }
        val next = try {
            prev.continueStroke(path, 0, SEGMENT_MS, !up)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "continueStroke failed", e)
            reset()
            return
        }
        stroke = next
        last = to
        if (up) {
            upPending = false
            dragActive = false
        }
        send(next, final = up)
    }

    private fun send(s: GestureDescription.StrokeDescription, final: Boolean) {
        val gesture = GestureDescription.Builder().addStroke(s).build()
        inFlight = true
        val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                inFlight = false
                if (final) {
                    if (!dragActive) { stroke = null; last = null }
                } else {
                    pump()
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                inFlight = false
                if (!final) reset()
            }
        }, handler)
        if (!ok) {
            Log.w(TAG, "dispatchGesture rejected")
            inFlight = false
            reset()
        }
    }

    private fun reset() {
        stroke = null
        last = null
        target = null
        upPending = false
        dragActive = false
    }

    private fun Float.safe(): Float = coerceAtLeast(0f)

    private companion object {
        const val TAG = "GestureDispatcher"
        const val TAP_MS = 50L
        const val SWIPE_MS = 180L
        const val SEGMENT_MS = 30L
    }
}
