package app.aircontrol.gesture

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Decides whether thumb and index touching (or an air push reaching the plane) is a deliberate
 * click. It keeps a short history of the hand and, at the moment of contact, checks hard gates and
 * scores soft factors into a confidence that must reach the [EngineConfig.clickStrictness] threshold.
 *
 * Factors ([FACTOR_NAMES]), each from a floor (0.1 or 0.25) to 1:
 * - angle: the palm faces the camera as calibrated ([HandFeatures.angleTo]);
 * - steadiness: the palm was still just before (a tap while sweeping the hand is usually accidental);
 * - isolation: middle, ring and little finger did not move (not a pose change);
 * - approach: the fingertips came together quickly (not drifting together);
 * - attention: the eyes are on the screen (only with the attention gate).
 *
 * Not thread-safe; owned by [GestureEngine].
 */
internal class TapGate {

    class Verdict(val ok: Boolean, val reason: String?)

    private class S(val t: Long, val gap: Float, val speed: Float, val others: FloatArray)

    private val hist = ArrayDeque<S>()

    var confidence = 0f
        private set
    val factors = FloatArray(FACTOR_NAMES.size)

    fun observe(t: Long, f: HandFeatures, palmSpeed: Float) {
        hist.addLast(S(t, f.pinchRatio, palmSpeed, floatArrayOf(f.fingerExt[1], f.fingerExt[2], f.fingerExt[3])))
        while (hist.isNotEmpty() && t - hist.first().t > HISTORY_MS) hist.removeFirst()
    }

    fun reset() = hist.clear()

    /**
     * Judges a contact happening now. [angleDeg] = palm angle from the calibrated pose, [attention]
     * 0..1 or -1, [cooldown] = a pose was just shown, [push] = an air push (no finger-approach factor).
     */
    fun judge(t: Long, angleDeg: Float, attention: Float, cooldown: Boolean, push: Boolean, cfg: EngineConfig): Verdict {
        val before = hist.filter { t - it.t in 0..STILL_WINDOW_MS }
        val recent = hist.filter { t - it.t <= ISOLATION_WINDOW_MS }
        val maxSpeed = before.maxOfOrNull { it.speed } ?: 0f
        var maxOther = 0f
        if (recent.size >= 2) {
            val first = recent.first().others
            for (s in recent) for (i in 0 until 3) maxOther = maxOf(maxOther, abs(s.others[i] - first[i]))
        }
        // Closing speed (palm sizes / s): the fastest gap decrease toward the current gap.
        val now = hist.lastOrNull()
        var closing = 0f
        if (now != null) for (s in recent) {
            val dt = (now.t - s.t) / 1000f
            if (dt > 0f) closing = maxOf(closing, (s.gap - now.gap) / dt)
        }

        val g = factors
        g[F_ANGLE] = factor(angleScore(angleDeg, cfg), 0.25f)
        g[F_STEADY] = factor(1f - smoothstep(maxSpeed, cfg.tapStillSpeed, cfg.tapStillSpeed * 3f), 0.1f)
        g[F_ISOLATION] = if (push) -1f else factor(1f - smoothstep(maxOther, 0.1f, 0.45f), 0.25f)
        g[F_APPROACH] = if (push) -1f else factor(smoothstep(closing, MIN_CLOSING_SPEED, GOOD_CLOSING_SPEED), 0.1f)
        g[F_ATTENTION] = if (cfg.attentionGate && attention >= 0f) factor(attention, 0.25f) else -1f
        var sum = 0.0
        var wsum = 0.0
        for (i in g.indices) {
            if (g[i] < 0f) continue
            sum += WEIGHTS[i] * ln(g[i].coerceAtLeast(0.01f).toDouble())
            wsum += WEIGHTS[i]
        }
        confidence = if (wsum > 0) exp(sum / wsum).toFloat() else 0f

        val reason = when {
            cooldown -> "right after a pose"
            angleDeg > cfg.angleToleranceDeg + cfg.angleBlockExtraDeg -> "palm not facing the camera (%.0f°)".format(angleDeg)
            cfg.attentionGate && attention in 0f..cfg.attentionBlockBelow -> "not looking at the screen"
            confidence < cfg.clickStrictness.threshold ->
                "low confidence %.2f (%s)".format(confidence, FACTOR_NAMES[weakest()])
            else -> null
        }
        return Verdict(reason == null, reason)
    }

    private fun weakest(): Int {
        var w = -1
        for (i in factors.indices) if (factors[i] >= 0f && (w < 0 || factors[i] < factors[w])) w = i
        return w.coerceAtLeast(0)
    }

    /** A factor never drops below its [floor]; the hard gates handle the extremes. */
    private fun factor(goodness: Float, floor: Float) = floor + (1f - floor) * goodness.coerceIn(0f, 1f)

    companion object {
        val FACTOR_NAMES = listOf("angle", "steadiness", "isolation", "approach", "attention")
        private const val F_ANGLE = 0
        private const val F_STEADY = 1
        private const val F_ISOLATION = 2
        private const val F_APPROACH = 3
        private const val F_ATTENTION = 4
        private val WEIGHTS = doubleArrayOf(2.0, 2.0, 1.5, 2.0, 1.5)
        /** Thumb–index closing speeds (palm sizes / s): a deliberate tap closes at several per second. */
        private const val MIN_CLOSING_SPEED = 0.6f
        private const val GOOD_CLOSING_SPEED = 2.0f
        private const val HISTORY_MS = 600L
        private const val STILL_WINDOW_MS = 150L
        private const val ISOLATION_WINDOW_MS = 350L

        /** 1 within the tolerance, falling to 0 at the blocking angle. */
        fun angleScore(angleDeg: Float, cfg: EngineConfig): Float =
            1f - smoothstep(angleDeg, cfg.angleToleranceDeg, cfg.angleToleranceDeg + cfg.angleBlockExtraDeg)

        fun smoothstep(x: Float, a: Float, b: Float): Float {
            if (b <= a) return if (x >= b) 1f else 0f
            val k = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return k * k * (3f - 2f * k)
        }
    }
}
