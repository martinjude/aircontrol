package app.aircontrol.gesture

import kotlin.math.PI
import kotlin.math.abs

/**
 * One-Euro filter (Casiez et al.): a low-pass filter whose cutoff rises with speed, giving
 * low jitter when the hand is still and low lag when it moves fast.
 */
class OneEuroFilter(
    var minCutoff: Double = 1.0,
    var beta: Double = 0.007,
    var dCutoff: Double = 1.0,
) {
    private var initialized = false
    private var xPrev = 0.0
    private var dxPrev = 0.0
    private var tPrev = 0L

    fun reset() {
        initialized = false
    }

    fun filter(x: Double, tMs: Long): Double {
        if (!initialized) {
            initialized = true
            xPrev = x; dxPrev = 0.0; tPrev = tMs
            return x
        }
        val dt = (tMs - tPrev).coerceAtLeast(1L) / 1000.0
        tPrev = tMs
        val dx = (x - xPrev) / dt
        val aD = alpha(dCutoff, dt)
        val dxHat = aD * dx + (1 - aD) * dxPrev
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1 - a) * xPrev
        xPrev = xHat
        dxPrev = dxHat
        return xHat
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
