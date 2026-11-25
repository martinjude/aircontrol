package app.aircontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aircontrol.gesture.CalibrationKind
import app.aircontrol.gesture.ClickMethod
import app.aircontrol.gesture.ClickStrictness
import app.aircontrol.service.ControlBus
import app.aircontrol.service.TrackingService
import app.aircontrol.settings.Accelerator
import app.aircontrol.settings.AppSettings
import app.aircontrol.settings.GlowPreset
import app.aircontrol.settings.PreviewSize
import app.aircontrol.settings.SettingsRepository
import app.aircontrol.settings.TrackingView
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.roundToInt

/** The factory defaults, for the sliders' reset buttons. */
private val D = AppSettings()

private fun x(v: Float) = "%.2f×".format(v)

@Composable
fun SettingsScreen(settings: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by ControlBus.state.collectAsStateWithLifecycle()
    fun update(t: (AppSettings) -> AppSettings) {
        scope.launch { repo.update(t) }
    }
    val running = state.running

    PageTitle("Settings", "Set the tracking view to Diagnostics under Look to see the meters while you tune, on top of any app.", logo = true)

    SectionCard("Calibration") {
        Muted("Do this once where you usually sit or stand. The screen guides you through each step.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = running, onClick = {
                TrackingService.calibrate(context, CalibrationKind.HAND, CalibrationKind.PUSH, CalibrationKind.ATTENTION)
            }) { Text("Calibrate everything") }
            if (!running) Muted("Start AirControl first")
        }
        HorizontalDivider(color = AirColors.Hairline)
        CalibrationRow(
            "✋", "Hand angle", if (settings.handReference != null) "Calibrated: your comfortable palm angle" else "Default: palm facing the camera",
            running,
        ) { TrackingService.calibrate(context, CalibrationKind.HAND) }
        CalibrationRow(
            "👉", "Air plane", if (settings.pushCalibration != null) "Calibrated at 5 points, fixed to the camera" else "Not calibrated: the plane floats in front of your hand",
            running,
        ) { TrackingService.calibrate(context, CalibrationKind.PUSH) }
        CalibrationRow(
            "👀", "Gaze", if (settings.attention != null) "Calibrated: the cursor shows when your eyes are on the screen" else "Not calibrated",
            running,
        ) { TrackingService.calibrate(context, CalibrationKind.ATTENTION) }
        if (settings.handReference != null || settings.pushCalibration != null || settings.attention != null) {
            TextButton(onClick = { update { it.copy(handReference = null, pushCalibration = null, attention = null, attentionGate = false) } }) {
                Text("Clear calibrations", color = AirColors.Text2)
            }
        }
    }

    SectionCard("Pointer") {
        SettingSlider("Cursor speed", settings.pointerGain, 0.5f..2f, 29, ::x, D.pointerGain) { v ->
            update { it.copy(pointerGain = v) }
        }
        Muted("The cursor follows the centre of your palm inside the frame shown in the tracking view. At 1.00× that frame spans the whole screen.")
        SettingSlider("Smoothing", settings.smoothing, 0f..1f, 19, { "${(it * 100).roundToInt()} %" }, D.smoothing) { v ->
            update { it.copy(smoothing = v) }
        }
        Muted("Higher is steadier, with slightly more lag.")
        SettingSlider("Hand angle tolerance", settings.angleTolerance, 10f..45f, 6, { "${it.roundToInt()}°" }, D.angleTolerance) { v ->
            update { it.copy(angleTolerance = v) }
        }
        Muted("How far the palm may turn from its calibrated angle before the screen edges warn and confidence drops.")
    }

    SectionCard("Click and hold") {
        Segmented(settings.clickMethod, ClickMethod.entries.map { it to it.label }) { v -> update { it.copy(clickMethod = v) } }
        Muted(
            when (settings.clickMethod) {
                ClickMethod.TAP -> "Touch thumb and index quickly to click. Keep them together to hold, then move to drag."
                ClickMethod.PUSH -> "Push your hand toward the screen through an invisible plane to touch; pull back to release."
                ClickMethod.BOTH -> "Thumb–index touch and air push both work. A quick touch clicks; keeping it held drags or long-presses."
            }
        )
        Text("Strictness", style = AirType.Body, color = AirColors.Text)
        Segmented(settings.clickStrictness, ClickStrictness.entries.map { it to it.label }) { v -> update { it.copy(clickStrictness = v) } }
        Muted("How sure AirControl must be (palm angle, steady hand, a clean deliberate touch). Strict rejects more.")
        if (settings.clickMethod != ClickMethod.PUSH) {
            SettingSlider("Click length", settings.tapMaxMs.toFloat(), 150f..600f, 17, { "${it.roundToInt()} ms" }, D.tapMaxMs.toFloat()) { v ->
                update { it.copy(tapMaxMs = v.roundToInt()) }
            }
            Muted("A touch shorter than this clicks; longer becomes a hold.")
        }
        SettingSlider("Pause after a pose", settings.poseCooldownMs.toFloat(), 0f..1500f, 14, { "${it.roundToInt()} ms" }, D.poseCooldownMs.toFloat()) { v ->
            update { it.copy(poseCooldownMs = v.roundToInt()) }
        }
        Muted("After a bind pose or 🤟, nothing clicks for this long, so changing back to pointing is safe.")
        SwitchRow(
            "Only click when I look at the screen",
            if (settings.attention == null) "Needs the gaze calibration." else "Blocks clicks while your eyes are elsewhere.",
            settings.attentionGate && settings.attention != null,
            enabled = settings.attention != null,
        ) { v -> update { it.copy(attentionGate = v) } }
    }

    if (settings.clickMethod != ClickMethod.TAP) {
        SectionCard("Air push") {
            SettingSlider("Drag speed", settings.pushDragGain, 0.1f..1f, 17, ::x, D.pushDragGain) { v ->
                update { it.copy(pushDragGain = v) }
            }
            Muted("How fast the cursor moves while pushed through the plane. Low makes drags precise.")
            if (settings.pushCalibration == null) {
                SettingSlider("Push depth", settings.pushDepth, 0.05f..0.3f, 24, { pushCm(it) }, D.pushDepth) { v ->
                    update { it.copy(pushDepth = v) }
                }
                Muted("Until the air plane is calibrated, it floats this far in front of your resting hand.")
            }
        }
    }

    SectionCard("Swipes") {
        SwitchRow(
            "Open-palm flick",
            "Hold an open palm still for a moment, then flick it left, right, up or down.",
            settings.swipesEnabled,
        ) { v -> update { it.copy(swipesEnabled = v) } }
        if (settings.swipesEnabled) {
            SettingSlider("Flick sensitivity", settings.swipeSensitivity, 0.5f..2f, 29, ::x, D.swipeSensitivity) { v ->
                update { it.copy(swipeSensitivity = v) }
            }
            Muted("Higher means a shorter flick is enough.")
            SettingSlider("Swipe length", settings.swipeLength, 0.25f..0.7f, 8, { "${(it * 100).roundToInt()} % of screen" }, D.swipeLength) { v ->
                update { it.copy(swipeLength = v) }
            }
        }
    }

    SectionCard("Detection") {
        SettingSlider("Turn off without a hand after", settings.autoDisarmSec.toFloat(), 5f..120f, 22, { "${it.roundToInt()} s" }, D.autoDisarmSec.toFloat()) { v ->
            update { it.copy(autoDisarmSec = v.roundToInt()) }
        }
        SwitchRow(
            "Require a person in view",
            "Ignore hands unless shoulders are visible, so people passing by don't trigger anything.",
            settings.requireBody && settings.usePose,
        ) { v -> update { it.copy(requireBody = v, usePose = it.usePose || v) } }
        SwitchRow(
            "Upper-body tracking",
            "Shoulder detection, needed for \"Require a person\". Off saves battery.",
            settings.usePose,
        ) { v -> update { it.copy(usePose = v) } }
        SwitchRow(
            "Swap left and right hand",
            "Use if binds for one hand fire for the other. The diagnostics view shows L or R.",
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
        Text("Tracking view", style = AirType.Body, color = AirColors.Text)
        Segmented(settings.trackingView, TrackingView.entries.map { it to it.label }) { v -> update { it.copy(trackingView = v) } }
        Muted(
            when (settings.trackingView) {
                TrackingView.OFF -> "Nothing in the corner of the screen."
                TrackingView.COMPACT -> "While control is on, a small view in a screen corner shows your hand, the frame the cursor moves in, and what is happening."
                TrackingView.DIAGNOSTICS -> "A larger panel with the camera view, confidence, hand angle, tap, push and flick meters, and an event log. Shown while tracking runs."
            }
        )
        if (settings.trackingView != TrackingView.OFF) {
            SwitchRow("Show the camera image", "Draws the picture behind the hand (uses a little more GPU).", settings.previewImage) { v ->
                update { it.copy(previewImage = v) }
            }
            SettingSlider("Panel opacity", settings.previewOpacity, 0.3f..1f, 13, { "${(it * 100).roundToInt()} %" }, D.previewOpacity) { v ->
                update { it.copy(previewOpacity = v) }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Panel position", style = AirType.Body, color = AirColors.Text)
                    Muted("Drag the handle on top of the panel to move it. Double-tap the handle to put it back.")
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    enabled = settings.previewX >= 0f,
                    onClick = { update { it.copy(previewX = -1f, previewY = -1f) } },
                ) { Text("Reset", color = AirColors.Text) }
            }
        }
        if (settings.trackingView == TrackingView.DIAGNOSTICS) {
            DropdownRow("Panel size", settings.previewSize, PreviewSize.entries, { it.label }) { v -> update { it.copy(previewSize = v) } }
            SwitchRow("Event log", "Recent clicks, swipes, binds, and why a tap or push was rejected.", settings.showLog) { v ->
                update { it.copy(showLog = v) }
            }
        }
        HorizontalDivider(color = AirColors.Hairline)
        Text("Glow colour", style = AirType.Body, color = AirColors.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GlowPreset.entries.forEach { preset ->
                val selected = settings.glow == preset
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .border(if (selected) 2.dp else 1.dp, if (selected) AirColors.Text else AirColors.Hairline, CircleShape)
                            .clickable { update { it.copy(glow = preset) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(30.dp).background(Brush.sweepGradient(preset.colors.map { Color(it) }), CircleShape))
                    }
                    Text(preset.label, style = AirType.Caption, color = if (selected) AirColors.Text else AirColors.Text2)
                }
            }
        }
    }

    OutlinedButton(onClick = {
        update {
            AppSettings(
                source = it.source, networkUrl = it.networkUrl, mirror = it.mirror, networkRotation = it.networkRotation,
                binds = it.binds, trackingView = it.trackingView, previewSize = it.previewSize,
                previewX = it.previewX, previewY = it.previewY, previewOpacity = it.previewOpacity,
                attention = it.attention, handReference = it.handReference, pushCalibration = it.pushCalibration,
            )
        }
    }) {
        Text("Reset gesture settings", color = AirColors.Text)
    }
}

/** Approximate push distance at 1 m from the camera for a ln(scale) depth. */
private fun pushCm(depth: Float): String = "≈%.0f cm".format(100f * (1f - exp(-depth)))

@Composable
private fun CalibrationRow(emoji: String, title: String, status: String, running: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Emoji(emoji, 22.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AirType.Body, color = AirColors.Text)
            Muted(status)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(enabled = running, onClick = onClick) { Text("Run", color = if (running) AirColors.Text else AirColors.Muted) }
    }
}
