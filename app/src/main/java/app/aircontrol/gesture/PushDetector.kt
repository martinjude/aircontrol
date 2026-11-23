package app.aircontrol.gesture

import kotlin.math.hypot

/**
 * Push-to-click: detects a quick jab of the hand towards the camera from the growth of the hand's
 * apparent size (ln scale), and compensates the pointer drift that comes with it.
 *
 * - IDLE → PRESSING when the ln-scale slope exceeds [EngineConfig.pushOnsetRate]. The click point
 *   is the pointer from [EngineConfig.pushLookbackMs] before that moment, and the pointer stays
 *   frozen there while pressing.
 * - PRESSING → click when the hand grew by [EngineConfig.pushGrowth] fast enough
 *   ([EngineConfig.pushMinPeakRate]) without moving sideways too much; aborts otherwise.
 * - After a click (RECOVER) the pointer stays frozen while the hand is pulled back.
 *
 * Not thread-safe; owned by [GestureEngine].
 */
internal class PushDetector {

    enum class State { IDLE, PRESSING, RECOVER }

    private class S(val t: Long, val ls: Float, val px: Float, val py: Float, val cx: Float, val cy: Float)

    private val hist = ArrayDeque<S>()

    var state = State.IDLE
        private set
    /** 0..1 while pressing. */
    var progress = 0f
        private set
    var clickX = 0f
        private set
    var clickY = 0f
        private set
    /** Recent ln-scale growth (for the debug HUD). */
    var debugGrowth = 0f
        private set

    /** True while the pointer must stay at [clickX], [clickY]. */
    val frozen: Boolean get() = state != State.IDLE

    private var onsetT = NEVER
    private var base = 0f
    private var peak = 0f
    private var startCx = 0f
    private var startCy = 0f
    private var recoverT = NEVER
    private var retracted = false
    private var lastClickT = NEVER

    /**
     * Feeds one frame. [px], [py] = current (filtered) pointer in pixels; [palm] = palm centre and
     * [palmSize] in aspect-corrected image units. Returns true when a click fires on this frame.
     */
    fun update(
        t: Long, lnScale: Float, px: Float, py: Float, palm: P3, palmSize: Float,
        enabled: Boolean, cfg: EngineConfig,
    ): Boolean {
        hist.addLast(S(t, lnScale, px, py, palm.x, palm.y))
        while (hist.isNotEmpty() && t - hist.first().t > HISTORY_MS) hist.removeFirst()
        val slope = slope(t)
        debugGrowth = if (state == State.PRESSING) lnScale - base else lnScale - (hist.minOfOrNull { it.ls } ?: lnScale)

        if (!enabled) {
            if (state == State.PRESSING) abort()
            if (state == State.RECOVER) state = State.IDLE
            return false
        }

        when (state) {
            State.IDLE -> if (slope > cfg.pushOnsetRate && t - lastClickT >= cfg.pushRefractoryMs) {
                state = State.PRESSING
                onsetT = t
                peak = slope
                base = hist.filter { it.t >= t - BASE_WINDOW_MS }.minOf { it.ls }
                val look = lookback(t - cfg.pushLookbackMs)
                clickX = look.px; clickY = look.py
                startCx = look.cx; startCy = look.cy
                progress = 0f
            }
            State.PRESSING -> {
                if (slope > peak) peak = slope
                val needed = cfg.pushGrowth
                val growth = lnScale - base
                progress = (growth / needed).coerceIn(0f, 1f)
                val lateral = hypot(palm.x - startCx, palm.y - startCy) / palmSize.coerceAtLeast(1e-4f)
                when {
                    lateral > cfg.pushMaxLateral -> abort()
                    growth >= needed && peak >= cfg.pushMinPeakRate -> {
                        state = State.RECOVER
                        recoverT = t
                        retracted = false
                        lastClickT = t
                        progress = 0f
                        return true
                    }
                    t - onsetT > cfg.pushMaxMs -> abort()
                    t - onsetT > STALL_MS && slope < STALL_RATE -> abort()
                }
            }
            State.RECOVER -> {
                if (slope < -RETRACT_RATE) retracted = true
                if ((retracted && slope > -RETRACT_RATE) || t - recoverT > cfg.pushRecoverMaxMs) state = State.IDLE
            }
        }
        return false
    }

    /** Cancels any press (e.g. a pinch started). */
    fun cancel() {
        if (state == State.PRESSING) abort() else state = State.IDLE
    }

    fun reset() {
        hist.clear()
        state = State.IDLE
        progress = 0f
        debugGrowth = 0f
        lastClickT = NEVER
    }

    private fun abort() {
        state = State.IDLE
        progress = 0f
    }

    private fun lookback(target: Long): S {
        var best: S? = null
        for (s in hist) if (s.t <= target) best = s else break
        return best ?: hist.first()
    }

    /** Least-squares slope of ln scale over the last [SLOPE_WINDOW_MS], per second. */
    private fun slope(t: Long): Float {
        var n = 0; var st = 0.0; var sy = 0.0; var stt = 0.0; var sty = 0.0
        for (s in hist) {
            if (t - s.t > SLOPE_WINDOW_MS) continue
            val x = (s.t - t) / 1000.0
            n++; st += x; sy += s.ls; stt += x * x; sty += x * s.ls
        }
        if (n < 2) return 0f
        val den = n * stt - st * st
        if (den <= 1e-12) return 0f
        return ((n * sty - st * sy) / den).toFloat()
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4
        const val HISTORY_MS = 500L
        const val SLOPE_WINDOW_MS = 110L
        const val BASE_WINDOW_MS = 120L
        const val STALL_MS = 130L
        const val STALL_RATE = 0.15f
        const val RETRACT_RATE = 0.3f
    }
}
