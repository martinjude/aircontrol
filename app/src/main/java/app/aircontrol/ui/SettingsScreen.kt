package app.aircontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aircontrol.settings.Accelerator
import app.aircontrol.settings.AppSettings
import app.aircontrol.settings.GlowPreset
import app.aircontrol.settings.PreviewSize
import app.aircontrol.settings.SettingsRepository
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The factory defaults, for the sliders' reset buttons. */
private val D = AppSettings()

private fun x(v: Float) = "%.2f×".format(v)

@Composable
fun SettingsScreen(settings: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    fun update(t: (AppSettings) -> AppSettings) {
        scope.launch { repo.update(t) }
    }

    Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Muted("Tip: turn on the live tracking preview under Look to watch the meters while you tune, on top of any app.")

    SectionCard("Cursor") {
        SettingSlider("Cursor speed", settings.pointerGain, 0.5f..2f, 29, ::x, D.pointerGain) { v ->
            update { it.copy(pointerGain = v) }
        }
        Muted(
            "The cursor sits on the centre of your palm. At 1.00× the middle of the camera view is the middle of the screen " +
                "and the corners of the view are the corners of the screen. Raise it if you can't reach the screen edges " +
                "without leaving the camera view."
        )
        SettingSlider("Smoothing", settings.smoothing, 0f..1f, 19, { "${(it * 100).roundToInt()} %" }, D.smoothing) { v ->
            update { it.copy(smoothing = v) }
        }
        Muted("Higher = steadier cursor, slightly more lag.")
    }

    SectionCard("Click") {
        Muted("Curl your index finger and straighten it again, quickly. The click happens on the release.")
        SettingSlider("Click sensitivity", settings.clickSensitivity, 0.5f..2f, 29, ::x, D.clickSensitivity) { v ->
            update { it.copy(clickSensitivity = v) }
        }
        Muted("Higher = a smaller flick counts. Lower it if clicks fire by accident. The \"click\" meter in the preview shows how deep each curl was.")
    }

    SectionCard("Pinch") {
        Muted("Pinch with your hand upright (fingers up, thumb and index facing the camera). Quick pinch = tap, pinch and move = drag, pinch and hold = long press. A horizontal hand does not pinch.")
        SettingSlider("Tilt tolerance", settings.pinchTiltDeg, 20f..70f, 9, { "${it.roundToInt()}°" }, D.pinchTiltDeg) { v ->
            update { it.copy(pinchTiltDeg = v) }
        }
        Muted("How far from upright the hand may be when a pinch starts.")
    }

    SectionCard("Swipes") {
        Muted("Hold a flat hand edge-on for a moment until \"Swipe armed\" shows, then stroke.")
        SettingSlider("Stroke sensitivity", settings.swipeSensitivity, 0.3f..2f, 33, ::x, D.swipeSensitivity) { v ->
            update { it.copy(swipeSensitivity = v) }
        }
        Muted("Higher = shorter, slower strokes are enough.")
        SettingSlider("Swipe length", settings.swipeLength, 0.25f..0.7f, 8, { "${(it * 100).roundToInt()} % of screen" }, D.swipeLength) { v ->
            update { it.copy(swipeLength = v) }
        }
    }

    SectionCard("Session") {
        SettingSlider("Auto-off without a hand", settings.autoDisarmSec.toFloat(), 5f..120f, 22, { "${it.roundToInt()} s" }, D.autoDisarmSec.toFloat()) { v ->
            update { it.copy(autoDisarmSec = v.roundToInt()) }
        }
        SwitchRow(
            "Require a person in view",
            "Ignore hands unless shoulders are visible (avoids triggers from people passing by).",
            settings.requireBody && settings.usePose,
        ) { v -> update { it.copy(requireBody = v, usePose = it.usePose || v) } }
    }

    SectionCard("Tracking") {
        SwitchRow(
            "Upper-body tracking",
            "Shoulder detection: scales swipe strokes to your distance and enables \"Require a person\". Turn off to save battery.",
            settings.usePose,
        ) { v -> update { it.copy(usePose = v) } }
        SwitchRow(
            "Swap left / right hand",
            "Use if binds limited to the left or right hand fire for the wrong hand. The preview header shows L / R.",
            settings.swapHands,
        ) { v -> update { it.copy(swapHands = v) } }
        DropdownRow("Processor", settings.accelerator, Accelerator.entries, {
            when (it) {
                Accelerator.AUTO -> "Auto"
                Accelerator.GPU -> "GPU"
                Accelerator.CPU -> "CPU"
            }
        }) { v -> update { it.copy(accelerator = v) } }
        Muted("Auto tries the GPU first. Pick CPU if tracking misbehaves on your device.")
    }

    SectionCard("Look") {
        Text("Glow colour", fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GlowPreset.entries.forEach { preset ->
                val selected = settings.glow == preset
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(Brush.sweepGradient(preset.colors.map { Color(it) }), CircleShape)
                            .border(if (selected) 3.dp else 0.dp, if (selected) Color.White else Color.Transparent, CircleShape)
                            .clickable { update { it.copy(glow = preset) } },
                    )
                    Text(preset.label, fontSize = 12.sp, color = AirColors.Muted)
                }
            }
        }
        HorizontalDivider(color = AirColors.SurfaceHigh)
        SwitchRow(
            "Live tracking preview",
            "A panel in a screen corner: camera view with your hand and the cursor point, meters for pinch / click / bind with their thresholds, and an event log.",
            settings.showPreview,
        ) { v -> update { it.copy(showPreview = v) } }
        if (settings.showPreview) {
            DropdownRow("Preview size", settings.previewSize, PreviewSize.entries, { it.label }) { v -> update { it.copy(previewSize = v) } }
            SwitchRow("Show the camera image", "Draws the camera picture behind the skeleton (uses a little more GPU).", settings.previewImage) { v ->
                update { it.copy(previewImage = v) }
            }
            SwitchRow("Event log", "Recent clicks, taps, swipes, binds and why a click was rejected.", settings.showLog) { v ->
                update { it.copy(showLog = v) }
            }
        }
    }

    OutlinedButton(onClick = {
        update {
            AppSettings(
                source = it.source, networkUrl = it.networkUrl, mirror = it.mirror, networkRotation = it.networkRotation,
                binds = it.binds, showPreview = it.showPreview, previewSize = it.previewSize,
            )
        }
    }) {
        Text("Reset gesture settings")
    }
}
