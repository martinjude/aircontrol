package app.aircontrol.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aircontrol.R

/** Compose view of [Palette]. */
object AirColors {
    val Accent = Color(Palette.ACCENT)
    val OnAccent = Color(Palette.ON_ACCENT)
    val Bg = Color(Palette.BG)
    val Surface = Color(Palette.SURFACE)
    val SurfaceHigh = Color(Palette.RAISED)
    val Sunken = Color(Palette.SUNKEN)
    val Text = Color(Palette.TEXT)
    val Text2 = Color(Palette.TEXT_2)
    val Muted = Color(Palette.TEXT_3)
    val Hairline = Color(Palette.HAIRLINE)
    /** A selected option: one clear step above the raised surface. */
    val Selected = Color(0xFF2E2E31)
    val Outline = Color(0xFF39393C)
    val Ok = Color(Palette.OK)
    val Warn = Color(Palette.WARN)
    val Bad = Color(Palette.BAD)
}

/** Geist (sans, weights 400 and 500) and Geist Mono for figures. SIL Open Font License, see licenses/. */
object AirFonts {
    val Sans = FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
    )
    val Mono = FontFamily(Font(R.font.geist_mono, FontWeight.Normal))
}

/** The one easing curve and the few durations used for motion. */
object AirMotion {
    val Ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    const val FAST = 150
    const val MEDIUM = 200
    const val SLOW = 400
}

/** Text styles: one family, weights 400 and 500, a short scale; figures in mono with tabular digits. */
object AirType {
    val Title = TextStyle(fontFamily = AirFonts.Sans, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
    val Section = TextStyle(fontFamily = AirFonts.Sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontFamily = AirFonts.Sans, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)
    val BodyStrong = TextStyle(fontFamily = AirFonts.Sans, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
    val Secondary = TextStyle(fontFamily = AirFonts.Sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val Caption = TextStyle(fontFamily = AirFonts.Sans, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val Figure = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = AirFonts.Mono, fontFeatureSettings = "tnum")
    val Status = TextStyle(fontFamily = AirFonts.Sans, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
}

private val scheme = darkColorScheme(
    primary = AirColors.Accent,
    onPrimary = AirColors.OnAccent,
    secondary = AirColors.Text2,
    secondaryContainer = AirColors.SurfaceHigh,
    onSecondaryContainer = AirColors.Text,
    background = AirColors.Bg,
    onBackground = AirColors.Text,
    surface = AirColors.Surface,
    onSurface = AirColors.Text,
    surfaceVariant = AirColors.SurfaceHigh,
    onSurfaceVariant = AirColors.Text2,
    surfaceContainer = AirColors.Surface,
    surfaceContainerLow = AirColors.Surface,
    surfaceContainerHigh = AirColors.SurfaceHigh,
    surfaceContainerHighest = AirColors.SurfaceHigh,
    outline = AirColors.Outline,
    outlineVariant = Color(0xFF2A2A2D),
    error = AirColors.Bad,
)

private val typography = Typography().let { t ->
    fun TextStyle.sans() = copy(fontFamily = AirFonts.Sans)
    t.copy(
        displayLarge = t.displayLarge.sans(), displayMedium = t.displayMedium.sans(), displaySmall = t.displaySmall.sans(),
        headlineLarge = t.headlineLarge.sans(), headlineMedium = t.headlineMedium.sans(), headlineSmall = t.headlineSmall.sans(),
        titleLarge = t.titleLarge.sans().copy(fontWeight = FontWeight.Medium),
        titleMedium = AirType.Section,
        titleSmall = t.titleSmall.sans(),
        bodyLarge = AirType.Body,
        bodyMedium = AirType.Body,
        bodySmall = AirType.Caption,
        labelLarge = t.labelLarge.sans().copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.sans().copy(fontWeight = FontWeight.Medium),
        labelSmall = t.labelSmall.sans(),
    )
}

/** Controls 10, cards 16, sheets/dialogs 20. */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun AirTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
