package app.aircontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import app.aircontrol.R
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Page title: medium weight, hierarchy from size. */
@Composable
fun PageTitle(text: String, subtitle: String? = null, logo: Boolean = false) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (logo) AirLogo(Modifier.padding(bottom = 8.dp))
        Text(text, style = AirType.Title, color = AirColors.Text)
        if (subtitle != null) Text(subtitle, style = AirType.Body, color = AirColors.Text2)
    }
}

/** The "air" mark in the muted text colour. */
@Composable
fun AirLogo(modifier: Modifier = Modifier, tint: Color = AirColors.Muted, height: Dp = 16.dp) {
    Icon(
        painterResource(R.drawable.ic_logo_mark),
        contentDescription = "AirControl",
        tint = tint,
        modifier = modifier.height(height).aspectRatio(476f / 231f),
    )
}

/** A card: one surface step above the page, a hairline, 20 padding, 12 between its rows. */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(AirColors.Surface, shape)
            .border(1.dp, AirColors.Hairline, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null) Text(title, style = AirType.Section, color = AirColors.Text)
        content()
    }
}

@Composable
fun StatusDot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** Secondary text. */
@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = AirType.Secondary, color = AirColors.Text2)
}

/** A gesture shown as an emoji, in a fixed-width box so labels line up. */
@Composable
fun Emoji(text: String, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = (size.value * 0.82f).sp, lineHeight = (size.value).sp)
    }
}

@Composable
fun AirIcon(icon: ImageVector, tint: Color = AirColors.Text2, size: Dp = 20.dp) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AirType.Body, color = if (enabled) AirColors.Text else AirColors.Muted)
            if (subtitle != null) Muted(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Slider that only persists on release. With a [default] it shows a small reset button whenever
 * the value differs from it.
 */
@Composable
fun SettingSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String,
    default: Float? = null,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = AirType.Body, color = AirColors.Text)
            if (default != null && abs(local - default) > 1e-3f) {
                TextButton(
                    onClick = { local = default; onChange(default) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    AirIcon(AirIcons.Reset, AirColors.Text2, 14.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(format(default), style = AirType.Caption, color = AirColors.Text2)
                }
            }
            Text(format(local), style = AirType.Figure, color = AirColors.Text)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onChange(local) },
            // Stepped sliders draw a dot per step by default: visual noise.
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
    }
}

@Composable
fun <T> DropdownRow(title: String, value: T, options: List<T>, label: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = AirType.Body, color = AirColors.Text)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(label(value), color = AirColors.Text) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(label(o)) }, onClick = { open = false; onChange(o) })
                }
            }
        }
    }
}

/** A full-width segmented choice. */
@Composable
fun <T> Segmented(value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, l) ->
            SegmentedButton(
                selected = value == v,
                onClick = { onChange(v) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = AirColors.Selected,
                    activeContentColor = AirColors.Text,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = AirColors.Text2,
                    activeBorderColor = AirColors.Outline,
                    inactiveBorderColor = AirColors.Outline,
                ),
            ) { Text(l, maxLines = 1) }
        }
    }
}

/** A labelled thin bar with optional threshold ticks (for the live tracking meters). */
@Composable
fun Meter(label: String, value: String, frac: Float, ticks: List<Float>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = AirType.Caption, color = AirColors.Text2, modifier = Modifier.weight(1f))
            Text(value, style = AirType.Figure, color = AirColors.Text)
        }
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
            drawRoundRect(AirColors.SurfaceHigh, size = size, cornerRadius = r)
            if (frac > 0.005f) drawRoundRect(color, size = androidx.compose.ui.geometry.Size(size.width * frac.coerceIn(0f, 1f), size.height), cornerRadius = r)
            ticks.forEach { t ->
                val x = size.width * t.coerceIn(0f, 1f)
                drawLine(AirColors.Text, androidx.compose.ui.geometry.Offset(x, -4f), androidx.compose.ui.geometry.Offset(x, size.height + 4f), strokeWidth = 3f)
            }
        }
    }
}
