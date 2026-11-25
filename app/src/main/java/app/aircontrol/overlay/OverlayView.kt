package app.aircontrol.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import app.aircontrol.R
import android.os.Build
import android.os.SystemClock
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import app.aircontrol.gesture.CalibrationKind
import app.aircontrol.gesture.ControlEvent
import app.aircontrol.gesture.DisarmReason
import app.aircontrol.gesture.HandSide
import app.aircontrol.gesture.SwipeDir
import app.aircontrol.gesture.TapGate
import app.aircontrol.gesture.TouchSource
import app.aircontrol.service.PreviewFrame
import app.aircontrol.service.TrackingSnapshot
import app.aircontrol.service.TrackingState
import app.aircontrol.settings.GlowPreset
import app.aircontrol.settings.PreviewSize
import app.aircontrol.settings.TrackingView
import app.aircontrol.ui.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-screen, non-touchable overlay: glow border while armed, a warm shade when the palm turns
 * away, the live pointer (confidence, gaze, finger closing, air-push and touch states), tap ripples, swipe chevrons, hold rings, status pill, calibration
 * prompts and the tracking view. Event coordinates are normalized (0..1).
 */
class OverlayView(context: Context) : View(context) {

    var onIdle: (() -> Unit)? = null

    var colors: IntArray = GlowPreset.SILVER.colors
        set(value) {
            field = value
            sweep = null
            renderer.accent = value[0]
            invalidate()
        }

    var trackingView = TrackingView.COMPACT
        set(value) { field = value; invalidate() }
    var previewSize = PreviewSize.MEDIUM
        set(value) { field = value; invalidate() }
    var previewImage = false
        set(value) { field = value; invalidate() }
    var showLog = true
        set(value) { field = value; invalidate() }
    /** Whether air push is on (depth gauge, push glow). */
    var pushEnabled = true
    var trackingState: TrackingState? = null
        set(value) { field = value; if (trackingShown) invalidate() }
    var previewFrame: PreviewFrame? = null
    var snapshot: TrackingSnapshot? = null
        set(value) {
            field = value
            if (value != null) recordSnapshot(value)
            if (trackingShown) invalidate()
        }

    /** The tracking view is drawn: always in Diagnostics, only while armed in Compact. */
    private val trackingShown: Boolean
        get() = when (trackingView) {
            TrackingView.OFF -> false
            TrackingView.COMPACT -> armedTarget > 0f || calKind != null
            TrackingView.DIAGNOSTICS -> true
        }

    val isIdle: Boolean
        get() = armedTarget == 0f && armedLevel < 0.001f && pointerAlpha < 0.001f && ripples.isEmpty() &&
            swipeDir == null && bindLabel == null && flashLabel == null && pillAlpha < 0.001f && now() > pillUntil &&
            calKind == null && calDoneUntil < now()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val accent get() = colors[0]
    private val renderer = TrackingRenderer(density)
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
    private var closing = 0f
    private var closingShown = 0f
    private var confidence = 1f
    private var confidenceShown = 1f
    private var angleWarn = 0f
    private var angleShown = 0f
    private var looking: Boolean? = null
    private var cooldown = false
    private var push = 0f
    private var pushShown = 0f
    private var touchSource: TouchSource? = null
    private var holdMs = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var moved = false
    private val trail = ArrayDeque<Pair<Float, Float>>()

    private class Ripple(val u: Float, val v: Float, val start: Long, val big: Boolean)
    private val ripples = mutableListOf<Ripple>()

    private var swipeDir: SwipeDir? = null
    private var swipeStart = 0L

    private var bindLabel: String? = null
    private var bindProgress = 0f
    private var flashLabel: String? = null
    private var flashStart = 0L

    private var pillText: String? = null
    private var pillProgress = -1f
    private var pillUntil = 0L
    private var pillAlpha = 0f

    // Calibration.
    private var calKind: CalibrationKind? = null
    private var calProgress = 0f
    private var calX = -1f
    private var calY = -1f
    private var calDoneUntil = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val geistMedium: Typeface =
        ResourcesCompat.getFont(context, R.font.geist_medium) ?: Typeface.create(Typeface.DEFAULT, 500, false)
    private val geistMono: Typeface = ResourcesCompat.getFont(context, R.font.geist_mono) ?: Typeface.MONOSPACE

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = dp(14f)
        typeface = geistMedium
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT
        textSize = dp(12f)
        typeface = geistMedium
    }
    private val rect = RectF()
    private val path = Path()

    // Air-push glow: a white radial falloff, tinted with the accent when drawn.
    private val pushGlow: Bitmap = run {
        val n = 128
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(Color.WHITE, 0x80FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        Canvas(b).drawCircle(n / 2f, n / 2f, n / 2f, p)
        b
    }
    private val pushPaint = Paint(Paint.FILTER_BITMAP_FLAG)

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
                showPill("🤟  Gesture control on", -1f, 2200)
                logLine("armed", Palette.TEXT)
            }
            is ControlEvent.Disarmed -> {
                armedTarget = 0f
                pointerWanted = false
                touching = false
                closing = 0f
                trail.clear()
                bindLabel = null
                logLine("disarmed (${e.reason.name.lowercase()})", Palette.TEXT)
                showPill(if (e.reason == DisarmReason.TIMEOUT) "No hand seen, gesture control off" else "Gesture control off", -1f, 1600)
            }
            is ControlEvent.Pointer -> {
                if (e.visible && (!pointerWanted || px < 0f)) {
                    px = e.x * width; py = e.y * height
                }
                targetU = e.x; targetV = e.y
                pointerWanted = e.visible
                if (e.touching && !touching) {
                    touchStartX = e.x; touchStartY = e.y; moved = false
                }
                touching = e.touching
                if (touching && hypot((e.x - touchStartX) * width, (e.y - touchStartY) * height) > dp(14f)) moved = true
                closing = e.closing
                confidence = e.confidence
                angleWarn = e.angleWarn
                looking = e.looking
                cooldown = e.cooldown
                push = e.push
                touchSource = e.touchSource
                holdMs = e.holdMs
                if (touching) {
                    trail.addLast(e.x to e.y)
                    while (trail.size > 16) trail.removeFirst()
                } else {
                    trail.clear()
                }
            }
            is ControlEvent.Tap -> {
                ripples += Ripple(e.x, e.y, t, big = true)
                logLine(if (e.click) "click  (%.0f%%, %.0f%%)".format(e.x * 100, e.y * 100) else "push tap", Palette.OK)
            }
            is ControlEvent.TouchDown -> {
                ripples += Ripple(e.x, e.y, t, big = false)
                logLine("touch down", Palette.OK)
            }
            is ControlEvent.TouchMove -> {}
            is ControlEvent.TouchUp -> {
                trail.clear()
                ripples += Ripple(e.x, e.y, t, big = false)
                logLine("touch up", Palette.OK)
            }
            is ControlEvent.Swipe -> {
                swipeDir = e.dir; swipeStart = t
                logLine("swipe ${e.dir.name.lowercase()}", Palette.OK)
            }
            is ControlEvent.ClickRejected -> logLine("✗ ${e.reason}", Palette.WARN)
            is ControlEvent.BindProgress -> if (e.progress > 0f) {
                bindLabel = e.label; bindProgress = e.progress
            } else if (bindLabel == e.label) {
                bindLabel = null
            }
            is ControlEvent.BindFired -> {
                bindLabel = null
                flashLabel = e.label
                flashStart = t
                logLine("bind: ${e.label}", Palette.OK)
            }
            is ControlEvent.Calibration -> {
                if (e.done) {
                    calKind = null
                    calDoneUntil = t + 1800
                    showPill((if (e.result != null) "✅  " else "⚠️  ") + e.message, -1f, 1800)
                    logLine(e.message.lowercase(), if (e.result != null) Palette.OK else Palette.WARN)
                } else {
                    calKind = e.kind
                    calProgress = e.progress
                    calX = e.targetX
                    calY = e.targetY
                    val icon = when (e.kind) {
                        CalibrationKind.HAND -> "✋  "
                        CalibrationKind.PUSH -> "👉  "
                        CalibrationKind.ATTENTION -> "👀  "
                    }
                    showPill(icon + e.message, e.progress, 600)
                }
            }
        }
        invalidate()
    }

    private fun showPill(msg: String, progress: Float, durationMs: Long) {
        pillText = msg
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
        warnShader = null
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
            // While the palm angle warning shows, the normal glow steps back so the warning reads clearly.
            drawGlow(canvas, armedLevel * (1f - 0.75f * angleShown), t)
            glowOnly = armedLevel == armedTarget && t - armedAtMs > 600
        }
        // Palm turned away from the calibrated angle: a warm shade on the screen edges.
        angleShown += ((if (pointerWanted && armedTarget > 0f) angleWarn else 0f) - angleShown) * (1f - exp(-dt / 120f))
        if (angleShown > 0.03f) {
            drawWarnShade(canvas, angleShown)
            animating = true
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
            // Feedback levels ease so they do not flicker with the landmarks.
            val ek = 1f - exp(-dt / 60f)
            closingShown += (closing - closingShown) * ek
            pushShown += ((if (pushEnabled) push else 0f) - pushShown) * ek
            confidenceShown += (confidence - confidenceShown) * (1f - exp(-dt / 150f))
            if (abs(closing - closingShown) > 0.01f || abs(push - pushShown) > 0.01f || abs(confidence - confidenceShown) > 0.01f) animating = true
            drawPushGlow(canvas, px, py, pointerAlpha)
            drawTrail(canvas)
            drawPointer(canvas, px, py, pointerAlpha)
            if (touching) animating = true
        }

        if (drawRipples(canvas, t)) animating = true
        if (drawSwipe(canvas, t)) animating = true
        if (drawSign(canvas, t)) animating = true
        when {
            calKind == CalibrationKind.ATTENTION -> drawCalTarget(canvas, width / 2f, height / 2f)
            calKind == CalibrationKind.PUSH && calX >= 0f -> drawCalTarget(canvas, calX * width, calY * height)
        }
        if (drawPill(canvas, t, dt)) animating = true
        if (trackingShown) drawTracking(canvas, t)

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
        shaderMatrix.setRotate((sec * 40f) % 360f, w / 2f, h / 2f)
        sh.setLocalMatrix(shaderMatrix)
        glowPaint.shader = shader
        val breathe = 0.88f + 0.12f * sin(sec * 2f * PI.toFloat() / 3.2f)
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

    /** The glow mask filled with a warm warning colour, orange shifting to red as [level] grows. */
    private fun drawWarnShade(c: Canvas, level: Float) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val mask = glowMask ?: buildGlowMask(w, h).also { glowMask = it; glowShader = null }
        warnPaint.shader = warnShader ?: BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { warnShader = it }
        warnPaint.colorFilter = PorterDuffColorFilter(blend(Palette.WARN, Palette.BAD, level), PorterDuff.Mode.SRC_IN)
        warnPaint.alpha = (255 * level.coerceIn(0f, 1f) * 0.85f).toInt()
        val band = glowBand
        val fw = w.toFloat()
        val fh = h.toFloat()
        c.drawRect(0f, 0f, fw, band, warnPaint)
        c.drawRect(0f, fh - band, fw, fh, warnPaint)
        c.drawRect(0f, band, band, fh - band, warnPaint)
        c.drawRect(fw - band, band, fw, fh - band, warnPaint)
    }

    private val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var warnShader: BitmapShader? = null

    private fun blend(a: Int, b: Int, k: Float): Int {
        val t = k.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() shl shift
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
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

    /** Soft light under the cursor that grows as the hand nears the air plane. */
    private fun drawPushGlow(c: Canvas, x: Float, y: Float, a: Float) {
        val p = pushShown
        if (p < PUSH_FEEDBACK_FROM && touchSource != TouchSource.PUSH) return
        val k = if (touchSource == TouchSource.PUSH) 1f else TapGate.smoothstep(p, PUSH_FEEDBACK_FROM, 1f)
        val r = dp(28f) + dp(28f) * k
        pushPaint.colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
        pushPaint.alpha = (255 * a * (0.12f + 0.5f * k)).toInt()
        rect.set(x - r, y - r, x + r, y + r)
        c.drawBitmap(pushGlow, null, rect, pushPaint)
    }

    private fun drawPointer(c: Canvas, x: Float, y: Float, a: Float) {
        stroke.shader = null
        stroke.pathEffect = null
        val ai = (255 * a).toInt()
        if (touching) {
            // Finger on the screen: a solid dot; a ring fills toward a long press while it stays still.
            val r = dp(10f)
            fill.color = Color.BLACK
            fill.alpha = (60 * a).toInt()
            c.drawCircle(x, y, r + dp(4f), fill)
            fill.color = accent
            fill.alpha = ai
            c.drawCircle(x, y, r, fill)
            stroke.strokeWidth = dp(2f)
            stroke.color = Color.WHITE
            stroke.alpha = ai
            c.drawCircle(x, y, r, stroke)
            val tapMax = if (touchSource == TouchSource.PUSH) PUSH_TAP_MS else TAP_HOLD_MS
            if (!moved) {
                val k = (holdMs.toFloat() / (tapMax + LONG_PRESS_MS)).coerceIn(0f, 1f)
                if (k > 0.05f) {
                    rect.set(x - dp(18f), y - dp(18f), x + dp(18f), y + dp(18f))
                    stroke.strokeWidth = dp(3f)
                    stroke.color = Color.BLACK
                    stroke.alpha = (60 * a).toInt()
                    c.drawArc(rect, 0f, 360f, false, stroke)
                    stroke.color = Color.WHITE
                    stroke.alpha = ai
                    c.drawArc(rect, -90f, 360f * k, false, stroke)
                }
            }
            val chip = when {
                moved -> "Drag"
                holdMs > tapMax + LONG_PRESS_MS -> "Long press"
                holdMs > tapMax -> "Hold"
                else -> null
            }
            chip?.let { drawChip(c, it, x, y + dp(34f), a) }
            return
        }

        // Ring: white when the pointer is trusted, shifting to orange/red as the palm turns away;
        // it fades with low confidence and while actions pause after a pose.
        val r = dp(14f)
        val ringColor = if (angleShown > 0.05f) blend(Color.WHITE, blend(Palette.WARN, Palette.BAD, angleShown), (angleShown * 1.5f).coerceAtMost(1f)) else Color.WHITE
        val ringAlpha = a * (0.35f + 0.65f * confidenceShown) * (if (cooldown) 0.5f else 1f)
        stroke.strokeWidth = dp(5.5f)
        stroke.color = Color.BLACK
        stroke.alpha = (60 * a).toInt()
        c.drawCircle(x, y, r, stroke)
        stroke.strokeWidth = dp(2.5f)
        stroke.color = ringColor
        stroke.alpha = (255 * ringAlpha).toInt()
        c.drawCircle(x, y, r, stroke)
        fill.color = accent
        fill.alpha = ai
        c.drawCircle(x, y, dp(3.5f), fill)
        // Confidence below ~90 %: a thin arc outside the ring shows how much is left.
        if (confidenceShown < 0.9f) {
            val cr = r + dp(6f)
            rect.set(x - cr, y - cr, x + cr, y + cr)
            stroke.strokeWidth = dp(2f)
            stroke.color = Color.BLACK
            stroke.alpha = (50 * a).toInt()
            c.drawArc(rect, 0f, 360f, false, stroke)
            stroke.color = ringColor
            stroke.alpha = (200 * a).toInt()
            c.drawArc(rect, -90f, 360f * confidenceShown, false, stroke)
        }
        // Eyes on the screen: a small dot above the ring.
        if (looking == true) {
            fill.color = Palette.OK
            fill.alpha = ai
            c.drawCircle(x, y - r - dp(11f), dp(3.5f), fill)
        }

        // Thumb and index closing: two arcs close in on the ring.
        val k = closingShown
        if (k > 0.05f) {
            val pr = r + dp(4f) + dp(12f) * (1f - k)
            rect.set(x - pr, y - pr, x + pr, y + pr)
            stroke.strokeWidth = dp(3f)
            stroke.color = if (cooldown) Palette.TEXT_3 else accent
            stroke.alpha = (ai * (0.35f + 0.65f * k)).toInt()
            c.drawArc(rect, 180f - 35f, 70f, false, stroke)
            c.drawArc(rect, -35f, 70f, false, stroke)
        }
        when {
            angleShown > 0.6f -> drawChip(c, "Turn your palm to the camera", x, y + dp(38f), a)
            cooldown && k > 0.5f -> drawChip(c, "Wait a moment", x, y + dp(38f), a)
        }
    }

    private fun drawChip(c: Canvas, msg: String, cx: Float, cy: Float, a: Float) {
        val tw = label.measureText(msg)
        val h = dp(24f)
        rect.set(cx - tw / 2f - dp(10f), cy - h / 2f, cx + tw / 2f + dp(10f), cy + h / 2f)
        fill.color = Palette.SURFACE
        fill.alpha = (235 * a).toInt()
        c.drawRoundRect(rect, dp(6f), dp(6f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Palette.HAIRLINE
        c.drawRoundRect(rect, dp(6f), dp(6f), stroke)
        label.color = Palette.TEXT
        label.alpha = (255 * a).toInt()
        c.drawText(msg, cx - tw / 2f, cy - (label.descent() + label.ascent()) / 2f, label)
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
        fill.color = Palette.SURFACE
        fill.alpha = (200 * fade).toInt()
        c.drawCircle(cx, cy, dp(64f), fill)
        val size = dp(18f)
        for (i in 0 until 3) {
            val off = (i - 1) * dp(20f) + e * dp(40f)
            val ax = cx + vx * off
            val ay = cy + vy * off
            val px1 = ax - vx * size - vy * size
            val py1 = ay - vy * size + vx * size
            val px2 = ax - vx * size + vy * size
            val py2 = ay - vy * size - vx * size
            path.reset()
            path.moveTo(px1, py1); path.lineTo(ax, ay); path.lineTo(px2, py2)
            stroke.strokeWidth = dp(4f)
            stroke.color = if (i == 2) accent else Palette.TEXT
            stroke.alpha = (255 * fade * (0.45f + 0.275f * i)).toInt().coerceIn(0, 255)
            c.drawPath(path, stroke)
        }
        return true
    }

    private fun drawSign(c: Canvas, t: Long): Boolean {
        val cx = if (pointerAlpha > 0.1f) px else width / 2f
        val cy = if (pointerAlpha > 0.1f) py else height / 2f
        var animating = false
        bindLabel?.let { lbl ->
            rect.set(cx - dp(28f), cy - dp(28f), cx + dp(28f), cy + dp(28f))
            stroke.strokeWidth = dp(4f)
            stroke.color = Color.BLACK
            stroke.alpha = 70
            c.drawArc(rect, 0f, 360f, false, stroke)
            stroke.color = accent
            stroke.alpha = 255
            c.drawArc(rect, -90f, 360f * bindProgress, false, stroke)
            drawChip(c, lbl, cx, cy + dp(50f), 1f)
        }
        flashLabel?.let { lbl ->
            val e = (t - flashStart) / 700f
            if (e >= 1f) {
                flashLabel = null
            } else {
                fill.color = accent
                fill.alpha = (120 * (1f - e)).toInt()
                c.drawCircle(cx, cy, dp(28f) + dp(22f) * e, fill)
                drawChip(c, lbl, cx, cy + dp(50f), 1f - e)
                animating = true
            }
        }
        return animating
    }

    /** Where to look (attention) or point (air push) during a calibration: a ring filling with progress. */
    private fun drawCalTarget(c: Canvas, cx: Float, cy: Float) {
        fill.color = Palette.SURFACE
        fill.alpha = 230
        c.drawCircle(cx, cy, dp(30f), fill)
        rect.set(cx - dp(22f), cy - dp(22f), cx + dp(22f), cy + dp(22f))
        stroke.strokeWidth = dp(3f)
        stroke.color = Palette.HAIRLINE
        stroke.alpha = 255
        c.drawArc(rect, 0f, 360f, false, stroke)
        stroke.color = accent
        c.drawArc(rect, -90f, 360f * calProgress, false, stroke)
        fill.color = accent
        fill.alpha = 255
        c.drawCircle(cx, cy, dp(5f), fill)
    }

    private fun drawPill(c: Canvas, t: Long, dt: Float): Boolean {
        val msg = pillText ?: return false
        val visible = t < pillUntil
        pillAlpha = approach(pillAlpha, if (visible) 1f else 0f, dt / 180f)
        if (pillAlpha < 0.001f && !visible) return false
        val tw = text.measureText(msg)
        val w = tw + dp(36f)
        val h = dp(44f)
        val cx = width / 2f
        val top = topInset + dp(12f)
        rect.set(cx - w / 2f, top, cx + w / 2f, top + h)
        val rad = dp(14f)
        fill.color = Palette.SURFACE
        fill.alpha = (245 * pillAlpha).toInt()
        c.drawRoundRect(rect, rad, rad, fill)
        if (pillProgress >= 0f) {
            c.save()
            c.clipRect(rect.left, rect.top, rect.left + w * pillProgress, rect.bottom)
            fill.color = Palette.RAISED
            fill.alpha = (255 * pillAlpha).toInt()
            c.drawRoundRect(rect, rad, rad, fill)
            c.restore()
            // A thin accent line under the filled part.
            stroke.strokeWidth = dp(2f)
            stroke.color = accent
            stroke.alpha = (255 * pillAlpha).toInt()
            c.drawLine(rect.left + rad, rect.bottom - dp(1f), rect.left + rad + (w - 2 * rad) * pillProgress, rect.bottom - dp(1f), stroke)
        }
        stroke.strokeWidth = dp(1f)
        stroke.color = Palette.HAIRLINE
        stroke.alpha = (20 * pillAlpha).toInt()
        c.drawRoundRect(rect, rad, rad, stroke)
        val x = rect.left + dp(18f)
        text.alpha = (255 * pillAlpha).toInt()
        c.drawText(msg, x, top + h / 2f - (text.descent() + text.ascent()) / 2f, text)
        return true
    }

    // ---------------------------------------------------------------- tracking view

    private class LogLine(val text: String, val t: Long, val color: Int)
    private val log = ArrayDeque<LogLine>()

    private fun logLine(text: String, color: Int) {
        log.addLast(LogLine(text, now(), color))
        while (log.size > LOG_KEEP) log.removeFirst()
    }

    // Thumb–index gap history for the sparkline (ring buffer, oldest at [sparkIdx]).
    private val reachHist = FloatArray(SPARK_N) { Float.NaN }
    private val clickMark = BooleanArray(SPARK_N)
    private var sparkIdx = 0
    private var lastSnapT = Long.MIN_VALUE
    private var lastClickTime = Long.MIN_VALUE
    private var panelLeft = false
    private var panelFlipT = 0L

    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        typeface = geistMono
    }
    private val dash = DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f)
    private val rect2 = RectF()
    private val box = RectF()

    private fun recordSnapshot(s: TrackingSnapshot) {
        val d = s.debug
        if (d.t == lastSnapT) return
        lastSnapT = d.t
        reachHist[sparkIdx] = if (d.hands > 0) d.gap else Float.NaN
        val clickTime = if (d.clickAgoMs == Long.MAX_VALUE) Long.MIN_VALUE else d.t - d.clickAgoMs
        val isNew = clickTime != Long.MIN_VALUE && clickTime != lastClickTime
        clickMark[sparkIdx] = isNew
        if (isNew) {
            lastClickTime = clickTime
            if (!d.armed) logLine("click detected (not armed)", Palette.OK)
        }
        sparkIdx = (sparkIdx + 1) % SPARK_N
    }

    private fun sideLabel(s: HandSide) = when (s) {
        HandSide.LEFT -> "L"
        HandSide.RIGHT -> "R"
        HandSide.ANY -> "?"
    }

    /** Panel position: a bottom corner, hopping to the other one when the pointer gets under it. */
    private fun panelLeftFor(w: Float, top: Float, bottom: Float, t: Long): Float {
        var left = if (panelLeft) dp(12f) else width - w - dp(12f)
        if (pointerAlpha > 0.1f && px in (left - dp(28f))..(left + w + dp(28f)) &&
            py in (top - dp(28f))..(bottom + dp(28f)) && t - panelFlipT > 900
        ) {
            panelLeft = !panelLeft
            panelFlipT = t
            left = if (panelLeft) dp(12f) else width - w - dp(12f)
        }
        return left
    }

    private fun drawTracking(c: Canvas, t: Long) {
        if (trackingView == TrackingView.DIAGNOSTICS) drawDiagnostics(c, t) else drawCompact(c, t)
    }

    /** The everyday tracking view: the hand in the camera, the hand-safe region and one status line. */
    private fun drawCompact(c: Canvas, t: Long) {
        val snap = snapshot
        val pad = dp(6f)
        val w = dp(COMPACT_WIDTH_DP)
        val aspect = (snap?.aspect ?: 0.75f).coerceIn(0.4f, 2.5f)
        val boxW = w - 2 * pad
        val boxH = boxW / aspect
        val lineH = dp(24f)
        val h = pad + boxH + lineH
        val bottom = height - bottomInset - dp(12f)
        val top = bottom - h
        val left = panelLeftFor(w, top, bottom, t)
        rect.set(left, top, left + w, bottom)
        fill.shader = null
        fill.color = Palette.SURFACE
        fill.alpha = 240
        c.drawRoundRect(rect, dp(14f), dp(14f), fill)
        stroke.pathEffect = null
        stroke.strokeWidth = dp(1f)
        stroke.color = Palette.HAIRLINE
        c.drawRoundRect(rect, dp(14f), dp(14f), stroke)
        box.set(left + pad, top + pad, left + pad + boxW, top + pad + boxH)
        val frame = previewFrame?.takeIf { previewImage }
        renderer.drawCamera(c, box, dp(8f), snap, frame?.bitmap, frame?.mirrored ?: false, showPush = pushEnabled)
        val (status, color) = TrackingRenderer.status(snap?.debug, pushEnabled)
        label.color = color
        label.alpha = 255
        val ty = box.bottom + lineH / 2f - (label.descent() + label.ascent()) / 2f
        val maxW = boxW - dp(4f)
        var s = status
        while (label.measureText(s) > maxW && s.length > 4) s = s.dropLast(2) + "…"
        c.drawText(s, box.left + dp(2f), ty, label)
    }

    /**
     * Everything for tuning: the camera view, the meters with their thresholds (pointer confidence, hand
     * angle, thumb–index gap, tap confidence factors, air-push depth, flick speed), an index-reach sparkline and an event log.
     */
    private fun drawDiagnostics(c: Canvas, t: Long) {
        val snap = snapshot
        val d = snap?.debug
        val st = trackingState
        val hands = snap?.hands.orEmpty()
        val pad = dp(8f)
        val w = dp(previewSize.widthDp)
        val innerW = w - 2 * pad
        val aspect = (snap?.aspect ?: 0.75f).coerceIn(0.4f, 2.5f)
        val boxW = min(innerW, innerW * 1.1f * aspect)
        val boxH = boxW / aspect
        val rowH = dp(16f)
        val meterH = dp(26f)
        val factorsH = dp(30f)
        val sparkH = dp(36f)
        val h = pad + rowH + boxH + dp(4f) + rowH + dp(4f) + meterH * 5 + factorsH + dp(4f) + sparkH + pad
        val bottom = height - bottomInset - dp(12f)
        val top = bottom - h
        val left = panelLeftFor(w, top, bottom, t)
        val ix = left + pad

        stroke.shader = null
        stroke.pathEffect = null
        rect.set(left, top, left + w, bottom)
        fill.color = Palette.SURFACE
        fill.alpha = 250
        c.drawRoundRect(rect, dp(14f), dp(14f), fill)
        stroke.strokeWidth = dp(1f)
        stroke.color = Palette.HAIRLINE
        c.drawRoundRect(rect, dp(14f), dp(14f), stroke)

        // Header: armed state, hands, speed.
        var y = top + pad
        val armedNow = d?.armed == true
        fill.color = if (armedNow) accent else Palette.TEXT_3
        fill.alpha = 255
        c.drawCircle(ix + dp(4f), y + rowH / 2f, dp(3.5f), fill)
        small.color = Palette.TEXT
        small.alpha = 255
        c.drawText(if (armedNow) "armed" else "idle", ix + dp(12f), y + rowH * 0.7f, small)
        val badge = if (hands.isEmpty()) "no hand" else hands.joinToString("+") { sideLabel(it.side) }
        small.color = Palette.TEXT_2
        c.drawText(badge, ix + dp(12f) + small.measureText("armed") + dp(8f), y + rowH * 0.7f, small)
        if (st != null) {
            val speed = if (previewSize == PreviewSize.SMALL) "%.0f fps".format(st.fps) else "%.0f fps  %d ms".format(st.fps, st.inferenceMs)
            small.color = Palette.TEXT_3
            c.drawText(speed, left + w - pad - small.measureText(speed), y + rowH * 0.7f, small)
        }
        y += rowH

        box.set(left + (w - boxW) / 2f, y, left + (w - boxW) / 2f + boxW, y + boxH)
        val frame = previewFrame?.takeIf { previewImage }
        renderer.drawCamera(c, box, dp(8f), snap, frame?.bitmap, frame?.mirrored ?: false, showPush = pushEnabled)
        y += boxH + dp(4f)

        val (status, color) = TrackingRenderer.status(d, pushEnabled)
        small.color = color
        c.drawText(status.take(30), ix, y + rowH * 0.7f, small)
        y += rowH + dp(4f)

        val have = d != null && hands.isNotEmpty()
        drawMeter(
            c, ix, y, innerW, "pointer",
            if (have) "%.0f %%".format(d!!.pointerConfidence * 100) + (if (d.attention >= 0f) "  eyes %.0f %%".format(d.attention * 100) else "") else "–",
            if (have) d!!.pointerConfidence else 0f, null,
            if (have && d!!.pointerConfidence < 0.5f) Palette.WARN else Palette.TEXT_2,
        )
        y += meterH
        drawMeter(
            c, ix, y, innerW, "angle",
            if (have) "%.0f° (ok ≤ %.0f°)".format(d!!.angleDeg, d.angleTolDeg) else "–",
            if (have) d!!.angleDeg / (d.angleTolDeg * 2.5f) else 0f,
            if (d != null) floatArrayOf(1f / 2.5f, (d.angleTolDeg + 25f) / (d.angleTolDeg * 2.5f)) else null,
            if (have && d!!.angleDeg > d.angleTolDeg) Palette.WARN else Palette.TEXT_2,
        )
        y += meterH
        drawMeter(
            c, ix, y, innerW, "tap",
            if (have) "gap %.2f%s".format(d!!.gap, if (d.cooldown) "  cooldown" else "") else "–",
            if (have) 1f - d!!.gap / GAP_SCALE else 0f,
            if (d != null) floatArrayOf(1f - d.tapEnter / GAP_SCALE, 1f - d.tapExit / GAP_SCALE) else null,
            when {
                !have -> Palette.TEXT_3
                d!!.touchSource == TouchSource.TAP -> Palette.OK
                d.cooldown -> Palette.TEXT_3
                else -> accent
            },
        )
        y += meterH
        // Confidence factors of the last tap / push.
        drawFactors(c, ix, y, innerW, d?.clickFactors, d?.clickConfidence ?: 0f)
        y += factorsH
        drawMeter(
            c, ix, y, innerW, "push",
            if (have && pushEnabled) "%.0f %%%s".format(d!!.push * 100, if (d.pushCalibrated) "" else "  floating") else if (pushEnabled) "–" else "off",
            if (have) d!!.push / TrackingRenderer.PUSH_GAUGE_MAX else 0f,
            floatArrayOf(1f / TrackingRenderer.PUSH_GAUGE_MAX),
            if (d?.touchSource == TouchSource.PUSH) Palette.OK else accent,
        )
        y += meterH
        when {
            d?.bindLabel != null -> drawMeter(c, ix, y, innerW, "bind", d.bindLabel.take(18), d.bindProgress, null, accent)
            else -> drawMeter(
                c, ix, y, innerW, "flick", if (have) "%.1f palm/s%s".format(d!!.palmSpeed, if (d.flickReady) " ready" else "") else "–",
                if (have) d!!.palmSpeed / FLICK_SCALE else 0f, floatArrayOf(6f / FLICK_SCALE),
                if (have && d!!.flickReady) accent else Palette.TEXT_2,
            )
        }
        y += meterH + dp(4f)

        // Thumb–index gap over time: the dashed lines are where a tap starts (accent) and ends.
        rect.set(ix, y, ix + innerW, y + sparkH)
        fill.color = Palette.RAISED
        fill.alpha = 255
        c.drawRoundRect(rect, dp(6f), dp(6f), fill)
        fun sy(v: Float) = y + sparkH - ((v - SPARK_LO) / (SPARK_HI - SPARK_LO)).coerceIn(0f, 1f) * sparkH
        if (d != null) {
            stroke.pathEffect = dash
            stroke.strokeWidth = dp(1f)
            stroke.color = Palette.TEXT
            stroke.alpha = 80
            c.drawLine(ix, sy(d.tapExit), ix + innerW, sy(d.tapExit), stroke)
            stroke.color = accent
            stroke.alpha = 200
            c.drawLine(ix, sy(d.tapEnter), ix + innerW, sy(d.tapEnter), stroke)
            stroke.pathEffect = null
        }
        val step = innerW / (SPARK_N - 1)
        stroke.strokeWidth = dp(1f)
        stroke.color = Palette.OK
        stroke.alpha = 200
        for (i in 0 until SPARK_N) {
            if (clickMark[(sparkIdx + i) % SPARK_N]) c.drawLine(ix + i * step, y, ix + i * step, y + sparkH, stroke)
        }
        path.reset()
        var pen = false
        for (i in 0 until SPARK_N) {
            val v = reachHist[(sparkIdx + i) % SPARK_N]
            if (v.isNaN()) { pen = false; continue }
            if (pen) path.lineTo(ix + i * step, sy(v)) else { path.moveTo(ix + i * step, sy(v)); pen = true }
        }
        stroke.strokeWidth = dp(1.5f)
        stroke.color = Palette.TEXT
        stroke.alpha = 235
        c.drawPath(path, stroke)
        if (showLog) drawLog(c, left, top, w, t)
    }

    /** Small bars: the tap / push confidence factors (1 = ideal, empty = not used), with the confidence itself. */
    private fun drawFactors(c: Canvas, left: Float, top: Float, width: Float, f: FloatArray?, conf: Float) {
        small.color = Palette.TEXT_2
        small.alpha = 255
        c.drawText("confidence", left, top + dp(9f), small)
        small.color = Palette.TEXT
        val v = if (f == null) "–" else "%.2f".format(conf)
        c.drawText(v, left + width - small.measureText(v), top + dp(9f), small)
        val n = TapGate.FACTOR_NAMES.size
        val gap = dp(3f)
        val bw = (width - gap * (n - 1)) / n
        val by = top + dp(13f)
        val bh = dp(12f)
        for (i in 0 until n) {
            val x = left + i * (bw + gap)
            rect2.set(x, by, x + bw, by + bh)
            fill.color = Palette.RAISED
            fill.alpha = 255
            c.drawRoundRect(rect2, dp(2f), dp(2f), fill)
            val k = f?.getOrNull(i) ?: -1f
            if (k >= 0f) {
                rect2.top = by + bh * (1f - k)
                fill.color = if (k < 0.5f) Palette.WARN else Palette.TEXT_2
                c.drawRoundRect(rect2, dp(2f), dp(2f), fill)
            }
        }
    }

    /** Recent events as a block right above the panel (it only exists while there are fresh entries), newest at the bottom. */
    private fun drawLog(c: Canvas, left: Float, panelTop: Float, w: Float, t: Long) {
        val live = log.takeLast(LOG_LINES).filter { t - it.t < LOG_FADE_MS }
        if (live.isEmpty()) return
        val lh = dp(12f)
        val pad = dp(8f)
        val h = live.size * lh + 2 * pad - dp(2f)
        rect.set(left, panelTop - dp(6f) - h, left + w, panelTop - dp(6f))
        fill.color = Palette.SURFACE
        fill.alpha = 240
        c.drawRoundRect(rect, dp(10f), dp(10f), fill)
        var ly = rect.top + pad
        val maxChars = if (previewSize == PreviewSize.SMALL) 20 else 30
        for (line in live) {
            small.color = line.color
            small.alpha = (255 * (1f - (t - line.t) / LOG_FADE_MS.toFloat())).toInt().coerceIn(40, 255)
            c.drawText(line.text.take(maxChars), left + dp(8f), ly + dp(9f), small)
            ly += lh
        }
        small.alpha = 255
    }

    /** Label + value over a thin bar; [ticks] are 0..1 positions of thresholds. */
    private fun drawMeter(c: Canvas, left: Float, top: Float, width: Float, name: String, value: String, frac: Float, ticks: FloatArray?, color: Int) {
        small.alpha = 255
        small.color = Palette.TEXT_2
        c.drawText(name, left, top + dp(9f), small)
        small.color = Palette.TEXT
        c.drawText(value, left + width - small.measureText(value), top + dp(9f), small)
        val by = top + dp(13f)
        val bh = dp(6f)
        rect2.set(left, by, left + width, by + bh)
        fill.color = Palette.RAISED
        fill.alpha = 255
        c.drawRoundRect(rect2, bh / 2f, bh / 2f, fill)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.005f) {
            rect2.right = left + width * f
            fill.color = color
            c.drawRoundRect(rect2, bh / 2f, bh / 2f, fill)
        }
        if (ticks != null) {
            fill.color = Palette.TEXT
            for (tk in ticks) {
                val x = left + width * tk.coerceIn(0f, 1f)
                c.drawRect(x - dp(0.8f), by - dp(2f), x + dp(0.8f), by + bh + dp(2f), fill)
            }
        }
    }

    private companion object {
        /** The glow drifts slowly; 30 fps is plenty and halves the GPU cost on 60/120 Hz screens. */
        const val GLOW_FRAME_MS = 33L
        const val COMPACT_WIDTH_DP = 132f
        const val PUSH_FEEDBACK_FROM = 0.35f
        /** Tap windows of the engine and Android's long-press delay, for the hold ring. */
        const val TAP_HOLD_MS = 300L
        const val PUSH_TAP_MS = 450L
        const val LONG_PRESS_MS = 420L
        const val LOG_KEEP = 24
        const val LOG_LINES = 6
        const val LOG_FADE_MS = 6000L
        /** Tap meter spans thumb–index gaps 0 .. GAP_SCALE palm sizes. */
        const val GAP_SCALE = 1.2f
        const val FLICK_SCALE = 12f
        const val SPARK_N = 60
        const val SPARK_LO = 0f
        const val SPARK_HI = 1.2f
    }

    private fun approach(cur: Float, target: Float, step: Float): Float = when {
        cur < target -> (cur + step).coerceAtMost(target)
        cur > target -> (cur - step).coerceAtLeast(target)
        else -> cur
    }
}
