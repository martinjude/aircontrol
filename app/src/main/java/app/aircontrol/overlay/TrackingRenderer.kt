package app.aircontrol.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.aircontrol.gesture.EngineDebug
import app.aircontrol.gesture.HandLm
import app.aircontrol.gesture.PointerMap
import app.aircontrol.gesture.PoseLm
import app.aircontrol.gesture.TouchSource
import app.aircontrol.service.TrackingSnapshot
import app.aircontrol.ui.Palette
import kotlin.math.abs

/**
 * Draws what the camera sees, the same way in the overlay and in the app's Camera tab: the hand
 * skeleton, the hand-safe region (its edge lights up where the palm presses on it), the palm point
 * that drives the cursor, the pinch gap and the air-push depth gauge.
 */
class TrackingRenderer(private val density: Float) {
    var accent: Int = Palette.ACCENT

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val r2 = RectF()

    private fun dp(v: Float) = v * density

    /**
     * @param frame optional camera image (drawn dimmed behind the skeleton); [mirrored] flips it to the user view
     * @param showPush draw the air-push depth gauge
     */
    fun drawCamera(
        c: Canvas, box: RectF, radius: Float, snap: TrackingSnapshot?,
        frame: Bitmap? = null, mirrored: Boolean = false, showPush: Boolean = false,
    ) {
        val d = snap?.debug
        fill.shader = null
        fill.color = Palette.SUNKEN
        c.drawRoundRect(box, radius, radius, fill)
        c.save()
        clip.reset()
        clip.addRoundRect(box, radius, radius, Path.Direction.CW)
        c.clipPath(clip)
        if (frame != null) {
            c.save()
            if (mirrored) c.scale(-1f, 1f, box.centerX(), box.centerY())
            bmp.alpha = 140
            c.drawBitmap(frame, null, box, bmp)
            c.restore()
        }
        fun mx(u: Float) = box.left + u * box.width()
        fun my(v: Float) = box.top + v * box.height()
        val stroke = dp(1.2f).coerceAtLeast(box.width() / 160f)

        // Hand-safe region: where the palm can go with the whole hand in view.
        if (d != null) {
            val r = d.region
            line.pathEffect = null
            line.strokeWidth = stroke
            line.color = Palette.withAlpha(Palette.TEXT, 0.22f)
            c.drawRect(mx(r[0]), my(r[1]), mx(r[2]), my(r[3]), line)
            if (d.edge != 0) {
                line.color = accent
                line.strokeWidth = stroke * 2.2f
                if (d.edge and PointerMap.EDGE_LEFT != 0) c.drawLine(mx(r[0]), my(r[1]), mx(r[0]), my(r[3]), line)
                if (d.edge and PointerMap.EDGE_RIGHT != 0) c.drawLine(mx(r[2]), my(r[1]), mx(r[2]), my(r[3]), line)
                if (d.edge and PointerMap.EDGE_TOP != 0) c.drawLine(mx(r[0]), my(r[1]), mx(r[2]), my(r[1]), line)
                if (d.edge and PointerMap.EDGE_BOTTOM != 0) c.drawLine(mx(r[0]), my(r[3]), mx(r[2]), my(r[3]), line)
            }
        }

        snap?.pose?.let { pose ->
            line.strokeWidth = stroke
            line.color = Palette.withAlpha(Palette.TEXT_3, 0.8f)
            for ((a, b) in PoseLm.UPPER_BODY) {
                if (pose.visibility[a] < 0.5f || pose.visibility[b] < 0.5f) continue
                c.drawLine(mx(pose.landmarks[a].x), my(pose.landmarks[a].y), mx(pose.landmarks[b].x), my(pose.landmarks[b].y), line)
            }
        }

        val hands = snap?.hands.orEmpty()
        val active = if (d != null && hands.isNotEmpty()) {
            hands.minBy { abs(it.landmarks[HandLm.MIDDLE_MCP].x - d.palmU) + abs(it.landmarks[HandLm.MIDDLE_MCP].y - d.palmV) }
        } else null
        line.strokeWidth = stroke * 1.3f
        for (hand in hands) {
            val main = hand === active
            for ((a, b) in HandLm.CONNECTIONS) {
                line.color = when {
                    b in 6..8 -> accent
                    b in 1..4 -> Palette.THUMB
                    main -> Palette.TEXT
                    else -> Palette.TEXT_3
                }
                if (!main) line.color = Palette.withAlpha(line.color, 0.55f)
                val pa = hand.landmarks[a]; val pb = hand.landmarks[b]
                c.drawLine(mx(pa.x), my(pa.y), mx(pb.x), my(pb.y), line)
            }
        }

        if (d != null && active != null) {
            // Thumb tip to index tip, once they get close.
            val far = d.tapEnter * 2.2f
            if (d.gap < far || d.touchSource == TouchSource.TAP) {
                val k = ((far - d.gap) / (far - d.tapEnter)).coerceIn(0f, 1f)
                val a = active.landmarks[HandLm.THUMB_TIP]; val b = active.landmarks[HandLm.INDEX_TIP]
                line.color = Palette.withAlpha(if (d.cooldown) Palette.TEXT_3 else accent, 0.35f + 0.65f * k)
                line.strokeWidth = stroke * 2f
                c.drawLine(mx(a.x), my(a.y), mx(b.x), my(b.y), line)
            }
            // The palm point that drives the cursor.
            val cx = mx(d.palmU)
            val cy = my(d.palmV)
            val rr = (box.width() / 28f).coerceIn(dp(3.5f), dp(7f))
            // White when trusted, warm when the palm is turned away from the calibrated angle.
            line.color = if (d.angleDeg > d.angleTolDeg) Palette.WARN else Palette.TEXT
            line.strokeWidth = stroke * 1.2f
            c.drawCircle(cx, cy, rr, line)
            fill.color = accent
            c.drawCircle(cx, cy, rr * 0.45f, fill)
            if (d.clickAgoMs < CLICK_FLASH_MS) {
                val k = d.clickAgoMs / CLICK_FLASH_MS.toFloat()
                line.color = Palette.withAlpha(Palette.OK, 1f - k)
                line.strokeWidth = stroke * 1.8f
                c.drawCircle(cx, cy, rr + rr * 2.2f * k, line)
            }
        }

        if (showPush && d != null) {
            // Air-push depth: fills toward the plane mark.
            val w = dp(3f)
            val x = box.right - dp(6f) - w
            val top = box.top + dp(8f)
            val bottom = box.bottom - dp(8f)
            val h = bottom - top
            r2.set(x, top, x + w, bottom)
            fill.color = Palette.withAlpha(Palette.TEXT, 0.15f)
            c.drawRoundRect(r2, w / 2, w / 2, fill)
            val k = (d.push / PUSH_GAUGE_MAX).coerceIn(0f, 1f)
            if (k > 0.01f) {
                r2.set(x, bottom - h * k, x + w, bottom)
                fill.color = if (d.touchSource == TouchSource.PUSH) Palette.OK else accent
                c.drawRoundRect(r2, w / 2, w / 2, fill)
            }
            val plane = bottom - h / PUSH_GAUGE_MAX
            fill.color = Palette.TEXT
            c.drawRect(x - dp(2f), plane - dp(0.75f), x + w + dp(2f), plane + dp(0.75f), fill)
        }
        c.restore()
        line.strokeWidth = dp(1f)
        line.color = Palette.HAIRLINE
        c.drawRoundRect(box, radius, radius, line)
    }

    companion object {
        const val CLICK_FLASH_MS = 400L
        /** The gauge spans proximities 0..this; the plane (1) sits below its top. */
        const val PUSH_GAUGE_MAX = 1.25f

        /** One short word or two describing what the hand is doing, and its colour. */
        fun status(d: EngineDebug?, showPush: Boolean): Pair<String, Int> {
            if (d == null || d.hands == 0) return "No hand" to Palette.TEXT_3
            return when {
                d.touchSource == TouchSource.TAP -> (if (d.touch == "DRAGGING") "Holding · drag" else "Touch") to Palette.OK
                d.touchSource == TouchSource.PUSH -> (if (d.touch == "DRAGGING") "Pushed · drag" else "Pushed") to Palette.OK
                d.clickAgoMs < CLICK_FLASH_MS -> "Click" to Palette.OK
                d.bindLabel != null -> d.bindLabel to Palette.TEXT
                d.angleDeg > d.angleTolDeg + 10f -> "Palm to camera" to Palette.WARN
                d.cooldown -> "Wait a moment" to Palette.TEXT_3
                showPush && d.push >= 0.35f -> "Push %d %%".format((d.push * 100).toInt().coerceAtMost(100)) to Palette.TEXT
                d.flickReady -> "Ready to flick" to Palette.TEXT_2
                d.edge != 0 -> "At the edge" to Palette.WARN
                d.pose != null -> "${d.pose.emoji}  ${d.pose.label.substringBefore(" (").substringBefore(",")}" to Palette.TEXT_2
                else -> "Pointing" + (if (d.attention >= 0.6f) "  👀" else "") to Palette.TEXT_2
            }
        }
    }
}
