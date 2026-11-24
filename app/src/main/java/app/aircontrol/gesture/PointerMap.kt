package app.aircontrol.gesture

/**
 * Camera → screen mapping through a *hand-safe region*: the part of the camera view in which the
 * palm centre can sit while the whole hand (raised fingers included) stays in view. Its margins are
 * proportional to the palm size, so they adapt to the distance from the camera. The region maps
 * onto the full screen, so the cursor reaches the corners before the index finger leaves the frame.
 * [gain] > 1 additionally shrinks the region around its centre.
 */
object PointerMap {
    const val MIN_GAIN = 0.25f
    const val MAX_GAIN = 4f
    /** The region never gets smaller than this fraction of the view (a hand very close to the camera). */
    private const val MIN_SPAN = 0.35f

    const val EDGE_LEFT = 1
    const val EDGE_TOP = 2
    const val EDGE_RIGHT = 4
    const val EDGE_BOTTOM = 8

    /** A rectangle in aspect-corrected image space (x in 0..aspect, y in 0..1). */
    data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    fun region(palmSize: Float, aspect: Float, top: Float, bottom: Float, side: Float, gain: Float): Region {
        var mt = top * palmSize
        var mb = bottom * palmSize
        var ms = side * palmSize
        val maxV = 1f - MIN_SPAN
        if (mt + mb > maxV) { val k = maxV / (mt + mb); mt *= k; mb *= k }
        val maxH = aspect * (1f - MIN_SPAN)
        if (2 * ms > maxH) ms = maxH / 2f
        val r = Region(ms, mt, aspect - ms, 1f - mb)
        val g = gain.coerceIn(MIN_GAIN, MAX_GAIN)
        if (g == 1f) return r
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val hw = r.width / 2f / g
        val hh = r.height / 2f / g
        return Region(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    class Mapped(val u: Float, val v: Float, val edge: Int)

    /** [palm] is in aspect-corrected image space; returns normalized screen (u, v), clamped, and the edges hit. */
    fun map(palm: P3, r: Region): Mapped {
        val u = (palm.x - r.left) / r.width
        val v = (palm.y - r.top) / r.height
        var edge = 0
        if (u <= 0f) edge = edge or EDGE_LEFT
        if (u >= 1f) edge = edge or EDGE_RIGHT
        if (v <= 0f) edge = edge or EDGE_TOP
        if (v >= 1f) edge = edge or EDGE_BOTTOM
        return Mapped(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f), edge)
    }
}
