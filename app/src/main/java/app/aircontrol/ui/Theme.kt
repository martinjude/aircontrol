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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compose view of [Palette]. */
object AirColors {
    val Coral = Color(Palette.ACCENT)
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
    val Selected = Color(0xFF332D29)
    val Outline = Color(0xFF3A3430)
    val Ok = Color(Palette.OK)
    val Warn = Color(Palette.WARN)
    val Bad = Color(Palette.BAD)
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
    val Title = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
    val Section = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal)
    val BodyStrong = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
    val Secondary = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val Caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val Figure = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
    val Status = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
}

private val scheme = darkColorScheme(
    primary = AirColors.Coral,
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
    outlineVariant = Color(0xFF2C2724),
    error = AirColors.Bad,
)

private val typography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = AirType.Section,
        bodyLarge = AirType.Body,
        bodyMedium = AirType.Body,
        bodySmall = AirType.Caption,
        labelLarge = t.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium),
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
