package app.aircontrol.gesture

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Air push: a virtual plane parallel to the camera. Pushing the hand through it presses; pulling
 * back releases.
 *
 * Depth = ln(apparent hand size) ([HandFeatures.scale], rigid palm bones): it grows as the hand
 * gets closer to the camera.
 * - Calibrated ([PushCalibration]): the plane is fixed relative to the camera. At each screen
 *   position the hover and touch depths are interpolated from the calibration points, and
 *   proximity = (depth − hover) / (plane − hover).
 * - Not calibrated: the plane floats [EngineConfig.pushDepth] in front of wherever the hand rests
 *   (the rest follows the hand slowly, at once when it moves back).
 *
 * Pressing needs a settled hand: for [SETTLE_MS] after a hand appears nothing presses (the floating
 * rest catches up quickly meanwhile), because a hand being raised into view grows like a push.
 * The caller also passes `canPress = false` for an open palm (the swipe pose, never a push). A press
 * that was held back this way has to pull back first: reaching the plane must be a crossing.
 *
 * Position-based on purpose: the v2.0 velocity-based "jab" did not work on a real phone.
 * Not thread-safe; owned by [GestureEngine].
 */
internal class PushPlane {
    /** 0 at rest (hover), 1 at the plane (can exceed 1 when pushed through). */
    var proximity = 0f
        private set
    var pressed = false
        private set
    /** Current filtered depth signal (for calibration). */
    var depth = 0f
        private set
    val hasSignal: Boolean get() = !filtered.isNaN()

    private var rest = Float.NaN
    private var filtered = Float.NaN
    private var prevRaw = 0f
    private var lastT = 0L
    private var glitches = 0
    private var appearedT = 0L
    /** The plane was reached while pressing was not allowed: wait for the hand to pull back. */
    private var mustRetreat = false

    fun reset() {
        mustRetreat = false
        rest = Float.NaN
        filtered = Float.NaN
        proximity = 0f
        pressed = false
        glitches = 0
    }

    /** Releases without waiting for the hand to pull back (hand lost, touch cancelled...). */
    fun release() {
        pressed = false
    }

    /**
     * Feeds one frame; ([u], [v]) = cursor on the screen (0..1) for the calibrated plane.
     * [frozen] keeps the floating rest where it is (e.g. during a touch). Returns the proximity.
     */
    fun update(
        t: Long, f: HandFeatures, u: Float, v: Float, enabled: Boolean, frozen: Boolean, cfg: EngineConfig,
        canPress: Boolean = true,
    ): Float {
        val d = ln(f.scale)
        if (filtered.isNaN()) {
            filtered = d
            rest = d
            prevRaw = d
            lastT = t
            glitches = 0
            appearedT = t
        }
        val settling = t - appearedT < SETTLE_MS
        val dt = (t - lastT).coerceIn(1L, 200L).toFloat()
        lastT = t
        // A real push takes ≥ 150 ms: a jump of most of the push depth within one frame is a glitch.
        val glitch = maxOf(MIN_GLITCH, cfg.pushDepth * GLITCH_OF_DEPTH)
        val jump = abs(d - prevRaw)
        prevRaw = d
        if (jump > glitch && glitches < 2) {
            // Landmark glitch (a finger merged with the palm, a mis-detection): hold for up to two frames.
            glitches++
        } else {
            if (glitches > 0 && abs(d - filtered) > glitch) {
                // The new level persisted: it is real (hand re-detected), start over from it.
                filtered = d; rest = d
            }
            glitches = 0
            filtered += (d - filtered) * (1f - exp(-dt / FILTER_TAU_MS))
        }
        depth = filtered

        val cal = cfg.pushCalibration
        proximity = if (cal != null) {
            val (hover, plane) = cal.at(u, v)
            ((filtered - hover) / (plane - hover).coerceAtLeast(0.02f)).coerceAtLeast(0f)
        } else {
            val depthNeeded = cfg.pushDepth.coerceIn(0.03f, 0.5f)
            if (!frozen && !pressed) {
                // A real push takes ~0.3 s, so a 1.5 s follow barely affects it, while a slow approach never
                // catches up with the plane. Moving back: the plane recedes with the hand right away.
                val tau = when {
                    settling -> SETTLE_TAU_MS
                    filtered < rest -> cfg.pushRestTauMs / 6f
                    else -> cfg.pushRestTauMs.toFloat()
                }
                rest += (filtered - rest) * (1f - exp(-dt / tau))
            }
            ((filtered - rest) / depthNeeded).coerceAtLeast(0f)
        }
        if (proximity <= cfg.pushExit) mustRetreat = false
        if (!enabled) {
            pressed = false
        } else if (!pressed && proximity >= cfg.pushEnter && (!canPress || settling)) {
            mustRetreat = true
        } else if (!pressed && proximity >= cfg.pushEnter && !mustRetreat) {
            pressed = true
        } else if (pressed && proximity <= cfg.pushExit) {
            pressed = false
        }
        return proximity
    }

    private companion object {
        /** A larger single-frame jump of ln(scale) is a tracking glitch, not a push. */
        const val MIN_GLITCH = 0.05f
        const val GLITCH_OF_DEPTH = 0.6f
        const val FILTER_TAU_MS = 45f
        /** After a hand appears: no press, and the floating rest follows it closely. */
        const val SETTLE_MS = 700L
        const val SETTLE_TAU_MS = 80f
    }
}
