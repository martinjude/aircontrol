package app.aircontrol.gesture

/**
 * Direct camera → screen mapping: the centre of the camera view is the centre of the screen and the
 * corners of the view are the corners of the screen. [gain] > 1 shrinks the camera region that
 * spans the screen (the hand does not have to reach the very edge of the frame); < 1 widens it.
 */
object PointerMap {
    const val MIN_GAIN = 0.25f
    const val MAX_GAIN = 4f

    /** [palm] is in aspect-corrected image space (x scaled by [aspect]); returns normalized screen (u, v). */
    fun map(palm: P3, aspect: Float, gain: Float): Pair<Float, Float> {
        val g = gain.coerceIn(MIN_GAIN, MAX_GAIN)
        val u = 0.5f + (palm.x / aspect - 0.5f) * g
        val v = 0.5f + (palm.y - 0.5f) * g
        return u.coerceIn(0f, 1f) to v.coerceIn(0f, 1f)
    }

    /** Half-size, in camera-view fractions, of the region around the centre that spans the screen. */
    fun halfSpan(gain: Float): Float = 0.5f / gain.coerceIn(MIN_GAIN, MAX_GAIN)
}
