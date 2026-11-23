package app.aircontrol.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OneEuroFilterTest {
    @Test
    fun firstSampleIsPassedThrough() {
        val f = OneEuroFilter()
        assertEquals(42.0, f.filter(42.0, 0), 1e-9)
    }

    @Test
    fun jitterIsDamped() {
        val f = OneEuroFilter(minCutoff = 1.0, beta = 0.0)
        var maxDev = 0.0
        for (i in 0 until 300) {
            val raw = 500.0 + if (i % 2 == 0) 10.0 else -10.0
            val out = f.filter(raw, i * 33L)
            if (i > 30) maxDev = maxOf(maxDev, abs(out - 500.0))
        }
        assertTrue("deviation $maxDev should be well under the raw 10", maxDev < 4.0)
    }

    @Test
    fun higherBetaTracksFastMotionWithLessLag() {
        fun lagAfterRamp(beta: Double): Double {
            val f = OneEuroFilter(minCutoff = 1.0, beta = beta)
            var out = 0.0
            for (i in 0..15) out = f.filter(i * 100.0, i * 33L)
            return 1500.0 - out
        }
        assertTrue(lagAfterRamp(0.02) < lagAfterRamp(0.0))
    }

    @Test
    fun convergesToAConstantInput() {
        val f = OneEuroFilter()
        f.filter(0.0, 0)
        var out = 0.0
        for (i in 1..200) out = f.filter(100.0, i * 33L)
        assertEquals(100.0, out, 0.5)
    }
}
