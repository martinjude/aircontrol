package app.aircontrol.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Line icons drawn on a 24-unit grid with a 1.5 stroke, after Lucide (https://lucide.dev,
 * ISC licence, © Lucide contributors). Tinted by the caller.
 */
object AirIcons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    val Power = icon("power", "M12 2v10", "M18.4 6.6a9 9 0 1 1-12.77.04")
    val Camera = icon(
        "camera",
        "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z",
        circle(12f, 13f, 3f),
    )
    val Zap = icon("zap", "M13 2 3 14h9l-1 8 10-12h-9l1-8z")
    val Sliders = icon(
        "sliders",
        "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3", "M14 2v4", "M8 10v4", "M16 18v4",
    )
    val Help = icon("help", circle(12f, 12f, 10f), "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01")
    val Check = icon("check", "M20 6 9 17l-5-5")
    val Alert = icon("alert", circle(12f, 12f, 10f), "M12 8v4", "M12 16h.01")
    val Plus = icon("plus", "M5 12h14", "M12 5v14")
    val Reset = icon("reset", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    val ChevronRight = icon("chevron", "m9 18 6-6-6-6")
}
