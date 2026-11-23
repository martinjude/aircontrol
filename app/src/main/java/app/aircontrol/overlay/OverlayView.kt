package app.aircontrol.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import app.aircontrol.gesture.ControlEvent
import app.aircontrol.gesture.DisarmReason
import app.aircontrol.gesture.HandLm
import app.aircontrol.gesture.PoseLm
import app.aircontrol.gesture.SwipeAxis
import app.aircontrol.gesture.SwipeDir
import app.aircontrol.gesture.SystemAction
import app.aircontrol.service.TrackingSnapshot
import app.aircontrol.service.TrackingState
import app.aircontrol.settings.GlowPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Full-screen, non-touchable overlay: animated glow border while armed, live pointer, tap ripples,
 * swipe arrows, hold-progress rings and status pill. Event coordinates are normalized (0..1).
 */
class OverlayView(context: Context) : View(context) {

    var onIdle: (() -> Unit)? = null

    var colors: IntArray = GlowPreset.CORAL.colors
        set(value) {
            field = value
            sweep = null
            invalidate()
        }

    var showHud = false
        set(value) { field = value; invalidate() }
    var showSkeleton = false
        set(value) { field = value; invalidate() }
    var hudState: TrackingState? = null
        set(value) { field = value; if (showHud) invalidate() }
    var snapshot: TrackingSnapshot? = null
        set(value) { field = value; if (showSkeleton) invalidate() }

    val isIdle: Boolean
        get() = armedTarget == 0f && armedLevel < 0.001f && pointerAlpha < 0.001f && ripples.isEmpty() &&
            swipeDir == null && swipeAxis == null && signAction == null && flashAction == null && pillAlpha < 0.001f && now() > pillUntil

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val accent get() = colors[0]
    private val startMs = SystemClock.uptimeMillis()
    private var lastDrawMs = 0L

    // Glow.
    private var armedTarget = 0f
    private var armedLevel = 0f
    private var armedAtMs = 0L
    private var sweep: SweepGradient? = null
    private val shaderMatrix = Matrix()
    // The glow shape (soft falloff from the screen edge) is rendered once into an alpha mask; each
    // frame only the edge bands are filled with a rotating colour gradient masked by it.
    private var glowMask: Bitmap? = null
    private var glowShader: ComposeShader? = null
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowBand get() = dp(56f)
    private var cornerRadius = dp(32f)
    private var topInset = dp(28f)
    private var bottomInset = dp(24f)

    // Pointer (normalized target, pixel rendered position).
    private var targetU = 0.5f
    private var targetV = 0.5f
    private var px = -1f
    private var py = -1f
    private var pointerWanted = false
    private var pointerAlpha = 0f
    private var touching = false
    private var press = 0f
    private val trail = ArrayDeque<Pair<Float, Float>>()

    private class Ripple(val u: Float, val v: Float, val start: Long, val big: Boolean)
    private val ripples = mutableListOf<Ripple>()

    private var swipeDir: SwipeDir? = null
    private var swipeStart = 0L
    private var swipeAxis: SwipeAxis? = null
    private var swipePill = false

    private var signAction: SystemAction? = null
    private var signProgress = 0f
    private var flashAction: SystemAction? = null
    private var flashStart = 0L

    private var pillText: String? = null
    private var pillProgress = -1f
    private var pillUntil = 0L
    private var pillAlpha = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
        typeface = Typeface.MONOSPACE
    }
    private val rect = RectF()
    private val path = Path()

    private fun now() = SystemClock.uptimeMillis()

    // ---------------------------------------------------------------- events

    fun onEvent(e: ControlEvent) {
        val t = now()
        when (e) {
            is ControlEvent.ArmProgress -> if (e.progress > 0f) {
                showPill(if (e.disarming) "🤟  Keep holding to turn off" else "🤟  Keep holding to take control", e.progress, 400)
            } else {
                pillUntil = t
            }
            ControlEvent.Armed -> {
                armedTarget = 1f
                armedAtMs = t
                showPill("Gesture control ON  ·  🤟 to stop", -1f, 2200)
            }
            is ControlEvent.Disarmed -> {
                armedTarget = 0f
                pointerWanted = false
                touching = false
                press = 0f
                swipeAxis = null
                swipePill = false
                trail.clear()
                signAction = null
                showPill(
                    if (e.reason == DisarmReason.TIMEOUT) "No hand seen  ·  gesture control OFF" else "Gesture control OFF",
                    -1f, 1600,
                )
            }
            is ControlEvent.Pointer -> {
                if (e.visible && (!pointerWanted || px < 0f)) {
                    px = e.x * width; py = e.y * height
                }
                targetU = e.x; targetV = e.y
                pointerWanted = e.visible
                touching = e.touching
                press = e.press
                if (touching) {
                    trail.addLast(e.x to e.y)
                    while (trail.size > 16) trail.removeFirst()
                } else {
                    trail.clear()
                }
            }
            is ControlEvent.Tap -> ripples += Ripple(e.x, e.y, t, big = true)
            is ControlEvent.TouchDown -> ripples += Ripple(e.x, e.y, t, big = false)
            is ControlEvent.TouchMove -> {}
            is ControlEvent.TouchUp -> trail.clear()
            is ControlEvent.Swipe -> { swipeDir = e.dir; swipeStart = t }
            is ControlEvent.SwipeMode -> when {
                e.axis != null -> {
                    swipeAxis = e.axis
                    showPill(if (e.axis == SwipeAxis.HORIZONTAL) "Swipe armed  ⟷" else "Swipe armed  ↕", -1f, Long.MAX_VALUE / 4)
                    swipePill = true
                }
                e.progress > 0f -> {
                    swipeAxis = null
                    showPill("✋  Hold flat to swipe", e.progress, 300)
                    swipePill = true
                }
                else -> {
                    swipeAxis = null
                    if (swipePill) pillUntil = t
                    swipePill = false
                }
            }
            ControlEvent.RecenterPending -> {
                showPill("👍  Point where the centre should be", -1f, 3000)
            }
            ControlEvent.Recentered -> {
                ripples += Ripple(0.5f, 0.5f, t, big = true)
                showPill("Pointer centred", -1f, 1200)
            }
            is ControlEvent.SignProgress -> if (e.progress > 0f) {
                signAction = e.action; signProgress = e.progress
            } else if (signAction == e.action) {
                signAction = null
            }
            is ControlEvent.Global -> {
                signAction = null
                flashAction = e.action
                flashStart = t
            }
        }
        invalidate()
    }

    private fun showPill(msg: String, progress: Float, durationMs: Long) {
        pillText = msg
        swipePill = false
        pillProgress = progress
        pillUntil = now() + durationMs
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 30) {
            topInset = insets.getInsets(WindowInsets.Type.statusBars()).top.toFloat().coerceAtLeast(dp(24f))
            bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom.toFloat().coerceAtLeast(dp(16f))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.takeIf { it > 0 }?.let {
                if (it.toFloat() != cornerRadius) glowMask = null
                cornerRadius = it.toFloat()
            }
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sweep = null
        glowMask = null
        if (px >= 0f) { px = targetU * w; py = targetV * h }
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val t = now()
        val dt = if (lastDrawMs == 0L) 16f else (t - lastDrawMs).coerceIn(1L, 100L).toFloat()
        lastDrawMs = t
        var animating = false
        var glowOnly = false

        armedLevel = approach(armedLevel, armedTarget, dt / 320f)
        if (armedLevel > 0.001f) {
            drawGlow(canvas, armedLevel, t)
            glowOnly = armedLevel == armedTarget && t - armedAtMs > 600
        }

        val pointerTarget = if (pointerWanted && armedTarget > 0f) 1f else 0f
        pointerAlpha = approach(pointerAlpha, pointerTarget, dt / 140f)
        if (pointerAlpha != pointerTarget) animating = true
        if (pointerAlpha > 0.001f) {
            val tx = targetU * width
            val ty = targetV * height
            val k = 1f - exp(-dt / 32f)
            px += (tx - px) * k
            py += (ty - py) * k
            if (abs(tx - px) + abs(ty - py) > 0.5f) animating = true
            drawTrail(canvas)
            drawPointer(canvas, px, py, pointerAlpha)
        }

        if (drawRipples(canvas, t)) animating = true
        drawSwipeAxis(canvas)
        if (drawSwipe(canvas, t)) animating = true
        if (drawSign(canvas, t)) animating = true
        if (drawPill(canvas, t, dt)) animating = true
        if (showSkeleton) drawSkeleton(canvas)
        if (showHud) drawHud(canvas)

        when {
            animating -> postInvalidateOnAnimation()
            glowOnly -> postInvalidateDelayed(GLOW_FRAME_MS)
            isIdle -> onIdle?.invoke()
        }
    }

    private fun drawGlow(c: Canvas, level: Float, t: Long) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val sh = sweep ?: SweepGradient(w / 2f, h / 2f, colors, null).also { sweep = it; glowShader = null }
        val mask = glowMask ?: buildGlowMask(w, h).also { glowMask = it; glowShader = null }
        val shader = glowShader ?: ComposeShader(
            BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), sh, PorterDuff.Mode.SRC_IN,
        ).also { glowShader = it }
        val sec = (t - startMs) / 1000f
        shaderMatrix.setRotate((sec * 50f) % 360f, w / 2f, h / 2f)
        sh.setLocalMatrix(shaderMatrix)
        glowPaint.shader = shader
        val breathe = 0.82f + 0.18f * sin(sec * 2f * PI.toFloat() / 2.6f)
        val flash = 0.8f * exp(-(t - armedAtMs) / 260f)
        val band = glowBand
        val fw = w.toFloat()
        val fh = h.toFloat()
        fun bands(alpha: Float) {
            glowPaint.alpha = (255f * alpha).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, fw, band, glowPaint)
            c.drawRect(0f, fh - band, fw, fh, glowPaint)
            c.drawRect(0f, band, band, fh - band, glowPaint)
            c.drawRect(fw - band, band, fw, fh - band, glowPaint)
        }
        bands(level * breathe)
        if (flash > 0.02f) bands(level * flash)
    }

    private fun buildGlowMask(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val mc = Canvas(bmp)
        val fw = w.toFloat()
        val fh = h.toFloat()
        // Software canvas: a real Gaussian blur is affordable here because the mask is built once.
        val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = dp(18f)
            maskFilter = BlurMaskFilter(dp(24f), BlurMaskFilter.Blur.NORMAL)
        }
        mc.drawRoundRect(0f, 0f, fw, fh, cornerRadius, cornerRadius, soft)
        soft.strokeWidth = dp(5f)
        soft.maskFilter = BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL)
        soft.alpha = 200
        mc.drawRoundRect(0f, 0f, fw, fh, cornerRadius, cornerRadius, soft)
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = dp(2.5f)
            alpha = 235
        }
        mc.drawRoundRect(0f, 0f, fw, fh, cornerRadius, cornerRadius, core)
        return bmp
    }

    private fun drawPointer(c: Canvas, x: Float, y: Float, a: Float) {
        stroke.shader = null
        if (!touching && press > 0f) {
            // Push in progress: the ring closes in on the dot and fills with the accent colour.
            val r = dp(15f) - dp(8f) * press
            fill.color = accent
            fill.alpha = (90 * a * press).toInt()
            c.drawCircle(x, y, r, fill)
            stroke.strokeWidth = dp(6f)
            stroke.color = Color.BLACK
            stroke.alpha = (70 * a).toInt()
            c.drawCircle(x, y, r, stroke)
            stroke.strokeWidth = dp(3f)
            stroke.color = accent
            stroke.alpha = (255 * a).toInt()
            c.drawCircle(x, y, r, stroke)
            fill.color = Color.WHITE
            fill.alpha = (255 * a).toInt()
            c.drawCircle(x, y, dp(3.5f), fill)
        } else if (!touching) {
            val r = dp(15f)
            stroke.strokeWidth = dp(6f)
            stroke.color = Color.BLACK
            stroke.alpha = (70 * a).toInt()
            c.drawCircle(x, y, r, stroke)
            stroke.strokeWidth = dp(3f)
            stroke.color = Color.WHITE
            stroke.alpha = (255 * a).toInt()
            c.drawCircle(x, y, r, stroke)
            fill.color = accent
            fill.alpha = (255 * a).toInt()
            c.drawCircle(x, y, dp(3.5f), fill)
        } else {
            val r = dp(11f)
            fill.color = Color.BLACK
            fill.alpha = (60 * a).toInt()
            c.drawCircle(x, y, r + dp(4f), fill)
            fill.color = accent
            fill.alpha = (235 * a).toInt()
            c.drawCircle(x, y, r, fill)
            stroke.strokeWidth = dp(2f)
            stroke.color = Color.WHITE
            stroke.alpha = (255 * a).toInt()
            c.drawCircle(x, y, r, stroke)
        }
    }

    private fun drawTrail(c: Canvas) {
        if (trail.size < 2) return
        path.reset()
        trail.forEachIndexed { i, (u, v) ->
            val x = u * width; val y = v * height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        stroke.strokeWidth = dp(5f)
        stroke.color = accent
        stroke.alpha = (115 * pointerAlpha).toInt()
        c.drawPath(path, stroke)
    }

    private fun drawRipples(c: Canvas, t: Long): Boolean {
        if (ripples.isEmpty()) return false
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val e = (t - r.start) / 380f
            if (e >= 1f) { it.remove(); continue }
            val ease = 1f - (1f - e) * (1f - e)
            val radius = dp(10f) + dp(if (r.big) 40f else 24f) * ease
            stroke.strokeWidth = dp(3f)
            stroke.color = accent
            stroke.alpha = (220 * (1f - e)).toInt()
            c.drawCircle(r.u * width, r.v * height, radius, stroke)
        }
        return true
    }

    private fun drawSwipe(c: Canvas, t: Long): Boolean {
        val dir = swipeDir ?: return false
        val e = (t - swipeStart) / 520f
        if (e >= 1f) { swipeDir = null; return false }
        val cx = width / 2f
        val cy = height / 2f
        val (vx, vy) = when (dir) {
            SwipeDir.UP -> 0f to -1f
            SwipeDir.DOWN -> 0f to 1f
            SwipeDir.LEFT -> -1f to 0f
            SwipeDir.RIGHT -> 1f to 0f
        }
        val fade = 1f - e
        fill.color = Color.BLACK
        fill.alpha = (70 * fade).toInt()
        c.drawCircle(cx, cy, dp(70f), fill)
        val size = dp(22f)
        for (i in 0 until 3) {
            val off = (i - 1) * dp(24f) + e * dp(50f)
            val ax = cx + vx * off
            val ay = cy + vy * off
            // Chevron pointing along (vx, vy).
            val px1 = ax - vx * size - vy * size
            val py1 = ay - vy * size + vx * size
            val px2 = ax - vx * size + vy * size
            val py2 = ay - vy * size - vx * size
            path.reset()
            path.moveTo(px1, py1); path.lineTo(ax, ay); path.lineTo(px2, py2)
            stroke.strokeWidth = dp(6f)
            stroke.color = if (i == 2) accent else Color.WHITE
            stroke.alpha = (255 * fade * (0.45f + 0.275f * i)).toInt().coerceIn(0, 255)
            c.drawPath(path, stroke)
        }
        return true
    }

    /** Faint two-headed arrow at the centre while swipe mode is armed. */
    private fun drawSwipeAxis(c: Canvas) {
        val axis = swipeAxis ?: return
        val cx = width / 2f
        val cy = height / 2f
        val (vx, vy) = if (axis == SwipeAxis.HORIZONTAL) 1f to 0f else 0f to 1f
        val len = dp(56f)
        val head = dp(16f)
        fill.color = Color.BLACK
        fill.alpha = 50
        c.drawCircle(cx, cy, dp(76f), fill)
        stroke.strokeWidth = dp(5f)
        stroke.color = Color.WHITE
        stroke.alpha = 150
        c.drawLine(cx - vx * len, cy - vy * len, cx + vx * len, cy + vy * len, stroke)
        stroke.color = accent
        stroke.alpha = 200
        for (sgn in floatArrayOf(-1f, 1f)) {
            val ax = cx + sgn * vx * len
            val ay = cy + sgn * vy * len
            path.reset()
            path.moveTo(ax - sgn * vx * head - vy * head, ay - sgn * vy * head + vx * head)
            path.lineTo(ax, ay)
            path.lineTo(ax - sgn * vx * head + vy * head, ay - sgn * vy * head - vx * head)
            c.drawPath(path, stroke)
        }
    }

    private fun drawSign(c: Canvas, t: Long): Boolean {
        val cx = if (pointerAlpha > 0.1f) px else width / 2f
        val cy = if (pointerAlpha > 0.1f) py else height / 2f
        var animating = false
        signAction?.let { action ->
            rect.set(cx - dp(30f), cy - dp(30f), cx + dp(30f), cy + dp(30f))
            stroke.strokeWidth = dp(5f)
            stroke.color = Color.BLACK
            stroke.alpha = 70
            c.drawArc(rect, 0f, 360f, false, stroke)
            stroke.color = accent
            stroke.alpha = 255
            c.drawArc(rect, -90f, 360f * signProgress, false, stroke)
            drawLabel(c, label(action), cx, cy + dp(56f), 1f)
        }
        flashAction?.let { action ->
            val e = (t - flashStart) / 700f
            if (e >= 1f) {
                flashAction = null
            } else {
                fill.color = accent
                fill.alpha = (150 * (1f - e)).toInt()
                c.drawCircle(cx, cy, dp(30f) + dp(26f) * e, fill)
                drawLabel(c, label(action), cx, cy + dp(56f), 1f - e)
                animating = true
            }
        }
        return animating
    }

    private fun drawPill(c: Canvas, t: Long, dt: Float): Boolean {
        val msg = pillText ?: return false
        val visible = t < pillUntil
        pillAlpha = approach(pillAlpha, if (visible) 1f else 0f, dt / 180f)
        if (pillAlpha < 0.001f && !visible) return false
        val tw = text.measureText(msg)
        val w = tw + dp(40f)
        val h = dp(42f)
        val cx = width / 2f
        val top = topInset + dp(12f)
        rect.set(cx - w / 2f, top, cx + w / 2f, top + h)
        fill.color = 0xFF1E1916.toInt()
        fill.alpha = (235 * pillAlpha).toInt()
        c.drawRoundRect(rect, h / 2f, h / 2f, fill)
        if (pillProgress >= 0f) {
            c.save()
            c.clipRect(rect.left, rect.top, rect.left + w * pillProgress, rect.bottom)
            fill.color = accent
            fill.alpha = (110 * pillAlpha).toInt()
            c.drawRoundRect(rect, h / 2f, h / 2f, fill)
            c.restore()
        }
        stroke.strokeWidth = dp(1.2f)
        stroke.color = accent
        stroke.alpha = (200 * pillAlpha).toInt()
        c.drawRoundRect(rect, h / 2f, h / 2f, stroke)
        text.alpha = (255 * pillAlpha).toInt()
        c.drawText(msg, cx - tw / 2f, top + h / 2f - (text.descent() + text.ascent()) / 2f, text)
        return true
    }

    private fun drawLabel(c: Canvas, msg: String, cx: Float, cy: Float, a: Float) {
        val tw = text.measureText(msg)
        val h = dp(32f)
        rect.set(cx - tw / 2f - dp(14f), cy - h / 2f, cx + tw / 2f + dp(14f), cy + h / 2f)
        fill.color = 0xFF1E1916.toInt()
        fill.alpha = (225 * a).toInt()
        c.drawRoundRect(rect, h / 2f, h / 2f, fill)
        text.alpha = (255 * a).toInt()
        c.drawText(msg, cx - tw / 2f, cy - (text.descent() + text.ascent()) / 2f, text)
    }

    private fun drawSkeleton(c: Canvas) {
        val snap = snapshot ?: return
        val cw = dp(140f)
        val ch = cw / snap.aspect.coerceIn(0.4f, 2.5f)
        val right = width - dp(16f)
        val bottom = height - bottomInset - dp(16f)
        rect.set(right - cw, bottom - ch, right, bottom)
        fill.color = 0xFF14110F.toInt()
        fill.alpha = 190
        c.drawRoundRect(rect, dp(14f), dp(14f), fill)
        stroke.shader = null
        stroke.strokeWidth = dp(1.5f)
        fun x(u: Float) = rect.left + u.coerceIn(0f, 1f) * cw
        fun y(v: Float) = rect.top + v.coerceIn(0f, 1f) * ch
        snap.pose?.let { pose ->
            stroke.color = Color.WHITE
            stroke.alpha = 120
            for ((a, b) in PoseLm.UPPER_BODY) {
                if (pose.visibility[a] < 0.5f || pose.visibility[b] < 0.5f) continue
                c.drawLine(x(pose.landmarks[a].x), y(pose.landmarks[a].y), x(pose.landmarks[b].x), y(pose.landmarks[b].y), stroke)
            }
        }
        for (hand in snap.hands) {
            stroke.color = accent
            stroke.alpha = 230
            for ((a, b) in HandLm.CONNECTIONS) {
                val pa = hand.landmarks[a]; val pb = hand.landmarks[b]
                c.drawLine(x(pa.x), y(pa.y), x(pb.x), y(pb.y), stroke)
            }
        }
        if (snap.hands.isEmpty()) {
            mono.alpha = 160
            c.drawText("no hand", rect.left + dp(10f), rect.bottom - dp(10f), mono)
        }
    }

    private fun drawHud(c: Canvas) {
        val s = hudState ?: return
        val lines = listOf(
            "AirControl ${if (s.armed) "ARMED" else "idle"}",
            "%.0f fps  %d ms  %s".format(s.fps, s.inferenceMs, s.delegate),
            "sign ${s.sign}  pinch %.2f".format(s.pinch),
            "push %+.3f  blade ${s.blade}".format(s.push),
            "hand ${if (s.handVisible) "yes" else "no"}  body ${if (s.bodyVisible) "yes" else "no"}",
            s.sourceStatus.take(38),
        )
        val lh = dp(15f)
        val left = dp(14f)
        val top = topInset + dp(64f)
        rect.set(left - dp(8f), top - dp(6f), left + dp(250f), top + lines.size * lh + dp(4f))
        fill.color = Color.BLACK
        fill.alpha = 150
        c.drawRoundRect(rect, dp(10f), dp(10f), fill)
        mono.alpha = 255
        lines.forEachIndexed { i, l -> c.drawText(l, left, top + (i + 1) * lh - dp(3f), mono) }
    }

    private fun label(a: SystemAction) = when (a) {
        SystemAction.BACK -> "←  Back"
        SystemAction.HOME -> "⌂  Home"
        SystemAction.RECENTS -> "▢  Recents"
        SystemAction.NOTIFICATIONS -> "Notifications"
        SystemAction.QUICK_SETTINGS -> "Quick settings"
        SystemAction.RECENTER -> "◎  Recenter"
        SystemAction.NONE -> ""
    }

    private companion object {
        /** The glow drifts slowly; 30 fps is plenty and halves the GPU cost on 60/120 Hz screens. */
        const val GLOW_FRAME_MS = 33L
    }

    private fun approach(cur: Float, target: Float, step: Float): Float = when {
        cur < target -> (cur + step).coerceAtMost(target)
        cur > target -> (cur - step).coerceAtLeast(target)
        else -> cur
    }
}
