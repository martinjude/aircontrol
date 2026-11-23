package app.aircontrol.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AirColors {
    val Coral = Color(0xFFD97757)
    val Amber = Color(0xFFF2A65A)
    val Bg = Color(0xFF15120F)
    val Surface = Color(0xFF1F1A17)
    val SurfaceHigh = Color(0xFF2A2420)
    val Text = Color(0xFFF3EDE8)
    val Muted = Color(0xFFB3A79F)
    val Ok = Color(0xFF7BD389)
    val Warn = Color(0xFFF2C14E)
    val Bad = Color(0xFFEF6F6C)
}

private val scheme = darkColorScheme(
    primary = AirColors.Coral,
    onPrimary = Color(0xFF1A0F0A),
    secondary = AirColors.Amber,
    secondaryContainer = Color(0xFF3B2A21),
    onSecondaryContainer = Color(0xFFF6D5C4),
    background = AirColors.Bg,
    onBackground = AirColors.Text,
    surface = AirColors.Surface,
    onSurface = AirColors.Text,
    surfaceVariant = AirColors.SurfaceHigh,
    onSurfaceVariant = AirColors.Muted,
    surfaceContainer = AirColors.Surface,
    surfaceContainerHigh = AirColors.SurfaceHigh,
    outline = Color(0xFF4A403A),
)

@Composable
fun AirTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
