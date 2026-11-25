package app.aircontrol.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.aircontrol.ui.Palette
import kotlin.math.hypot

/**
 * The tracking panel's drag handle: a small touchable window on the panel's top edge. The panel
 * itself is drawn by the non-touchable [OverlayView], so taps still reach the app below it; only this
 * grip catches touches. Drag it (with a finger, or an air drag) to move the panel; double-tap it to
 * put the panel back in its corner. [onMoved] gets the new normalized centre (-1, -1 = default corner).
 */
@SuppressLint("ViewConstructor")
class PreviewHandle(
    context: Context,
    private val panel: OverlayView,
    private val onMoved: (Float, Float) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** Window size: larger than the grip drawn, so it is easy to catch. */
    val widthPx = dp(64f).toInt()
    val heightPx = dp(30f).toInt()

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pill = RectF()

    private var downX = 0f
    private var downY = 0f
    private var startCx = 0f
    private var startCy = 0f
    private var tracking = false
    private var moved = false
    private var lastTapAt = 0L

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val w = dp(if (moved) 48f else 40f)
        val h = dp(16f)
        pill.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        fill.color = if (moved) Palette.RAISED else Palette.SURFACE
        fill.alpha = 240
        canvas.drawRoundRect(pill, h / 2f, h / 2f, fill)
        line.strokeWidth = dp(1f)
        line.color = Palette.HAIRLINE
        canvas.drawRoundRect(pill, h / 2f, h / 2f, line)
        // Grip: two short lines.
        line.strokeWidth = dp(1.5f)
        line.color = if (moved) Palette.TEXT else Palette.TEXT_2
        canvas.drawLine(cx - dp(8f), cy - dp(2.5f), cx + dp(8f), cy - dp(2.5f), line)
        canvas.drawLine(cx - dp(8f), cy + dp(2.5f), cx + dp(8f), cy + dp(2.5f), line)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = panel.currentPanelRect() ?: return false
                downX = e.rawX
                downY = e.rawY
                startCx = r.centerX()
                startCy = r.centerY()
                tracking = true
                moved = false
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && hypot(dx, dy) > slop) {
                    moved = true
                    invalidate()
                }
                if (moved) panel.dragPanelTo(startCx + dx, startCy + dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (tracking) {
                tracking = false
                if (moved) {
                    moved = false
                    invalidate()
                    val (u, v) = panel.dropPanel()
                    onMoved(u, v)
                } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                    val t = SystemClock.uptimeMillis()
                    if (t - lastTapAt < DOUBLE_TAP_MS) {
                        lastTapAt = 0L
                        panel.resetPanel()
                        onMoved(-1f, -1f)
                    } else {
                        lastTapAt = t
                    }
                }
            }
        }
        return true
    }

    private companion object {
        const val DOUBLE_TAP_MS = 350L
    }
}
