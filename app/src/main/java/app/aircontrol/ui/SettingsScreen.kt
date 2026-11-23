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
import app.aircontrol.gesture.AnchorMode
import app.aircontrol.gesture.SystemAction
import app.aircontrol.settings.Accelerator
import app.aircontrol.settings.AppSettings
import app.aircontrol.settings.GlowPreset
import app.aircontrol.settings.SettingsRepository
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(settings: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    fun update(t: (AppSettings) -> AppSettings) {
        scope.launch { repo.update(t) }
    }

    Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold)

    SectionCard("Pointer") {
        SettingSlider("Sensitivity", settings.pointerSensitivity, 0.5f..2f, 14, { "%.1f×".format(it) }) { v ->
            update { it.copy(pointerSensitivity = v) }
        }
        Muted("Higher = smaller hand movements cover the whole screen.")
        SettingSlider("Smoothing", settings.smoothing, 0f..1f, 9, { "${(it * 100).roundToInt()} %" }) { v ->
            update { it.copy(smoothing = v) }
        }
        Muted("Higher = steadier pointer, slightly more lag.")
        SwitchRow(
            "Anchor to shoulders",
            "Uses upper-body tracking so the same arm movement works at any distance. Off: centred where your hand appears.",
            settings.anchorMode == AnchorMode.BODY && settings.usePose,
        ) { v -> update { it.copy(anchorMode = if (v) AnchorMode.BODY else AnchorMode.HAND, usePose = it.usePose || v) } }
    }

    SectionCard("Click") {
        SettingSlider("Push sensitivity", settings.pushSensitivity, 0.5f..2f, 14, { "%.1f×".format(it) }) { v ->
            update { it.copy(pushSensitivity = v) }
        }
        Muted("Higher = a shorter push towards the camera clicks. Lower it if clicks fire by accident.")
    }

    SectionCard("Swipes") {
        Muted("Hold a flat hand edge-on for a moment until \"Swipe armed\" shows, then stroke.")
        SettingSlider("Stroke sensitivity", settings.swipeSensitivity, 0.5f..2f, 14, { "%.1f×".format(it) }) { v ->
            update { it.copy(swipeSensitivity = v) }
        }
        Muted("Higher = shorter, slower strokes are enough.")
        SettingSlider("Swipe length", settings.swipeLength, 0.25f..0.7f, 8, { "${(it * 100).roundToInt()} % of screen" }) { v ->
            update { it.copy(swipeLength = v) }
        }
    }

    SectionCard("Hand signs") {
        Muted("Hold a sign while gesture control is on. 🤟 is reserved for on/off.")
        val actions = SystemAction.entries
        DropdownRow("👎  Thumb down", settings.thumbDownAction, actions, ::actionLabel) { v -> update { it.copy(thumbDownAction = v) } }
        DropdownRow("✊  Fist (hold)", settings.fistAction, actions, ::actionLabel) { v -> update { it.copy(fistAction = v) } }
        DropdownRow("✌️  Victory", settings.victoryAction, actions, ::actionLabel) { v -> update { it.copy(victoryAction = v) } }
        DropdownRow("👍  Thumb up", settings.thumbUpAction, actions, ::actionLabel) { v -> update { it.copy(thumbUpAction = v) } }
    }

    SectionCard("Session") {
        SettingSlider("Auto-off without a hand", settings.autoDisarmSec.toFloat(), 5f..120f, 22, { "${it.roundToInt()} s" }) { v ->
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
            "Shoulder detection for distance-independent control. Turn off to save battery.",
            settings.usePose,
        ) { v -> update { it.copy(usePose = v) } }
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
        SwitchRow("Tracking preview", "Small hand skeleton in a corner of the screen.", settings.showSkeleton) { v ->
            update { it.copy(showSkeleton = v) }
        }
        SwitchRow("Debug HUD", "fps, latency, detected sign and pinch value on screen.", settings.showHud) { v ->
            update { it.copy(showHud = v) }
        }
    }

    OutlinedButton(onClick = { update { AppSettings(source = it.source, networkUrl = it.networkUrl, mirror = it.mirror, networkRotation = it.networkRotation) } }) {
        Text("Reset gesture settings")
    }
}

fun actionLabel(a: SystemAction) = when (a) {
    SystemAction.NONE -> "Nothing"
    SystemAction.BACK -> "Back"
    SystemAction.HOME -> "Home"
    SystemAction.RECENTS -> "Recents"
    SystemAction.NOTIFICATIONS -> "Notifications"
    SystemAction.QUICK_SETTINGS -> "Quick settings"
    SystemAction.RECENTER -> "Recenter pointer"
}
