package app.aircontrol.ui

/**
 * Colour tokens shared by the app (Compose) and the overlay (Android Canvas), as ARGB ints.
 *
 * Direction: an instrument panel in a dim room. One warm graphite ramp for every surface and text,
 * hairlines instead of shadows, and a single coral signal colour for what is live or primary.
 */
object Palette {
    // Surfaces, darkest first.
    const val BG = 0xFF141210.toInt()
    const val SURFACE = 0xFF1B1816.toInt()
    const val RAISED = 0xFF24201D.toInt()
    const val SUNKEN = 0xFF0F0D0C.toInt()

    // Text roles.
    const val TEXT = 0xFFEDE8E3.toInt()
    const val TEXT_2 = 0xFFA8A09A.toInt()
    const val TEXT_3 = 0xFF857D77.toInt()

    /** Hairline = primary text at 8 %. */
    const val HAIRLINE = 0x14EDE8E3

    // The signal colour and the text that sits on it.
    const val ACCENT = 0xFFD97757.toInt()
    const val ON_ACCENT = 0xFF1A0F0A.toInt()

    // Semantic colours, toned down to sit in the palette.
    const val OK = 0xFF6FBF8E.toInt()
    const val WARN = 0xFFE0A955.toInt()
    const val BAD = 0xFFD9645C.toInt()

    /** Thumb in skeleton drawings (to tell it from the index). */
    const val THUMB = 0xFFE0C27A.toInt()

    fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt() shl 24)
}
