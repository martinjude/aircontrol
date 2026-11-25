package app.aircontrol.ui

/**
 * Colour tokens shared by the app (Compose) and the overlay (Android Canvas), as ARGB ints.
 *
 * Direction: an instrument panel in a dim room. One neutral graphite ramp for every surface and text,
 * hairlines instead of shadows, and a single light-grey signal colour for what is live or primary.
 */
object Palette {
    // Surfaces, darkest first.
    const val BG = 0xFF0F0F10.toInt()
    const val SURFACE = 0xFF171718.toInt()
    const val RAISED = 0xFF202022.toInt()
    const val SUNKEN = 0xFF0A0A0B.toInt()

    // Text roles.
    const val TEXT = 0xFFEDEDEE.toInt()
    const val TEXT_2 = 0xFFA3A3A8.toInt()
    const val TEXT_3 = 0xFF7E7E84.toInt()

    /** Hairline = primary text at 8 %. */
    const val HAIRLINE = 0x14EDEDEE

    // The signal colour and the text that sits on it.
    const val ACCENT = 0xFFE9E9EB.toInt()
    const val ON_ACCENT = 0xFF121213.toInt()

    // Semantic colours, toned down to sit in the palette.
    const val OK = 0xFF6FBF8E.toInt()
    const val WARN = 0xFFE0A955.toInt()
    const val BAD = 0xFFD9645C.toInt()

    /** Thumb in skeleton drawings (to tell it from the index). */
    const val THUMB = 0xFFE0C27A.toInt()

    fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt() shl 24)
}
