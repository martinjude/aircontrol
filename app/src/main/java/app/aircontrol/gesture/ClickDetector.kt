package app.aircontrol.gesture

import kotlin.math.hypot

/**
 * Click = the index finger curls and straightens again, quickly (like tapping the air).
 *
 * The input is the index-finger reach ([HandFeatures.indexExt], ≈2 straight, ≈1 curled). Because the
 * cursor sits on the palm, which barely moves while the finger flicks, no pointer freezing is needed:
 * the click simply lands where the pointer was just before the curl started.
 *
 * - IDLE: the recent maximum of the reach is the baseline (only trusted when ≥ [EngineConfig.clickMinBaseline],
 *   i.e. the finger really was extended). A fall of [EngineConfig.clickOnsetDrop] below it starts CURLING.
 * - CURLING: tracks the minimum. When the reach has regained [EngineConfig.clickRecovery] of the dip,
 *   the click fires if the dip was deep enough ([EngineConfig.clickNeeded]) and quick enough
 *   ([EngineConfig.clickMaxMs]). Slow curls (forming a fist), shallow ones and curls with a moving
 *   hand are rejected, and the reason is reported for the live preview.
 *
 * Not thread-safe; owned by [GestureEngine].
 */
internal class ClickDetector {

    enum class State { IDLE, CURLING }

    private class S(val t: Long, val v: Float, val px: Float, val py: Float, val cx: Float, val cy: Float)

    private val hist = ArrayDeque<S>()
    private val raw = FloatArray(3)
    private var rawCount = 0
    private var rawIdx = 0

    var state = State.IDLE
        private set
    /** 0..1 while curling: depth reached over the depth needed. */
    var progress = 0f
        private set
    var clickX = 0f
        private set
    var clickY = 0f
        private set

    // Debug / preview.
    var signal = 0f
        private set
    var baseline = 0f
        private set
    var depth = 0f
        private set
    var needed = 0f
        private set

    private var rejectReason: String? = null

    private var peakT = 0L
    private var base = 0f
    private var minV = 0f
    private var minT = 0L
    private var startCx = 0f
    private var startCy = 0f
    private var lastClickT = NEVER

    /**
     * Feeds one frame. [signal] = index reach, [px], [py] = current (filtered) pointer in pixels,
     * [palm] and [palmSize] in aspect-corrected image units. Returns true when a click fires.
     */
    fun update(
        t: Long, reach: Float, px: Float, py: Float, palm: P3, palmSize: Float,
        enabled: Boolean, cfg: EngineConfig,
    ): Boolean {
        // Median of three removes single-frame landmark glitches without smearing real flicks.
        raw[rawIdx] = reach
        rawIdx = (rawIdx + 1) % 3
        if (rawCount < 3) rawCount++
        val v = if (rawCount >= 3) median3(raw[0], raw[1], raw[2]) else reach
        signal = v
        needed = cfg.clickNeeded
        hist.addLast(S(t, v, px, py, palm.x, palm.y))
        while (hist.isNotEmpty() && t - hist.first().t > HISTORY_MS) hist.removeFirst()

        // The baseline only uses samples after the last click, so a curl cannot be judged against the
        // extension from before it (a stale peak would turn the tail of the release into a second click).
        var peak = hist.last()
        for (s in hist) if (s.t > lastClickT && t - s.t <= BASELINE_WINDOW_MS && s.v >= peak.v) peak = s
        if (state == State.IDLE) {
            baseline = peak.v
            depth = (baseline - v).coerceAtLeast(0f)
        }

        if (!enabled) {
            if (state == State.CURLING) abort(null)
            return false
        }

        when (state) {
            State.IDLE -> {
                if (t - lastClickT >= cfg.clickRefractoryMs && peak.v >= cfg.clickMinBaseline &&
                    peak.v - v >= cfg.clickOnsetDrop
                ) {
                    state = State.CURLING
                    peakT = peak.t
                    base = peak.v
                    minV = v
                    minT = t
                    startCx = peak.cx
                    startCy = peak.cy
                    val look = lookback(peak.t - cfg.clickLookbackMs)
                    clickX = look.px
                    clickY = look.py
                }
            }
            State.CURLING -> {
                if (v < minV) { minV = v; minT = t }
                baseline = base
                depth = base - minV
                progress = (depth / needed).coerceIn(0f, 1f)
                val lateral = hypot(palm.x - startCx, palm.y - startCy) / palmSize.coerceAtLeast(1e-4f)
                val elapsed = t - peakT
                val recovered = t > minT && v >= minV + cfg.clickRecovery * depth
                when {
                    lateral > cfg.clickMaxLateral -> abort(if (depth >= needed * 0.5f) "hand moving" else null)
                    recovered -> {
                        if (depth >= needed && elapsed in cfg.clickMinDipMs..cfg.clickMaxMs) {
                            state = State.IDLE
                            progress = 0f
                            lastClickT = t
                            return true
                        }
                        abort(
                            when {
                                depth < needed * 0.5f -> null
                                depth < needed -> "too shallow %.2f/%.2f".format(depth, needed)
                                elapsed < cfg.clickMinDipMs -> "too brief"
                                else -> "too slow ${elapsed} ms"
                            }
                        )
                    }
                    elapsed > cfg.clickMaxMs -> abort(if (depth >= needed * 0.5f) "too slow >${cfg.clickMaxMs} ms" else null)
                }
            }
        }
        return false
    }

    /** A reject reason produced since the last call, once. */
    fun takeReject(): String? = rejectReason.also { rejectReason = null }

    /** Drops any curl in progress (e.g. a pinch was recognised). */
    fun cancel(reason: String? = null) {
        if (state == State.CURLING) abort(reason)
    }

    fun reset() {
        hist.clear()
        rawCount = 0
        rawIdx = 0
        state = State.IDLE
        progress = 0f
        depth = 0f
        baseline = 0f
        rejectReason = null
        lastClickT = NEVER
    }

    private fun abort(reason: String?) {
        state = State.IDLE
        progress = 0f
        if (reason != null) rejectReason = reason
    }

    private fun lookback(target: Long): S {
        var best: S? = null
        for (s in hist) if (s.t <= target) best = s else break
        return best ?: hist.first()
    }

    private fun median3(a: Float, b: Float, c: Float): Float = maxOf(minOf(a, b), minOf(maxOf(a, b), c))

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4
        const val HISTORY_MS = 800L
        const val BASELINE_WINDOW_MS = 500L
    }
}
