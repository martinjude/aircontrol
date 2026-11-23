package app.aircontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aircontrol.gesture.HandLm
import app.aircontrol.gesture.PoseLm
import app.aircontrol.service.ControlBus
import app.aircontrol.service.TrackingService
import app.aircontrol.settings.AppSettings
import app.aircontrol.settings.SettingsRepository
import app.aircontrol.settings.SourceType
import kotlinx.coroutines.launch

@Composable
fun CameraScreen(settings: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { repo.update(t) }

    Text("Camera", fontSize = 26.sp, fontWeight = FontWeight.Bold)

    SectionCard("Source") {
        val sources = listOf(SourceType.FRONT to "Front", SourceType.BACK to "Back", SourceType.NETWORK to "Network")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            sources.forEachIndexed { i, (type, label) ->
                SegmentedButton(
                    selected = settings.source == type,
                    onClick = { update { it.copy(source = type) } },
                    shape = SegmentedButtonDefaults.itemShape(i, sources.size),
                ) { Text(label) }
            }
        }
        when (settings.source) {
            SourceType.FRONT -> Muted("Recommended: the selfie camera faces you while you look at the screen.")
            SourceType.BACK -> Muted("Use when the device's back faces you (e.g. a tablet mounted on a stand).")
            SourceType.NETWORK -> NetworkFields(settings) { t -> update(t) }
        }
        SwitchRow(
            "Mirror horizontally",
            "On for any camera that faces you, so your right is the pointer's right.",
            settings.mirror,
        ) { v -> update { it.copy(mirror = v) } }
    }

    LivePreview()
}

@Composable
private fun NetworkFields(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    var url by remember(settings.networkUrl) { mutableStateOf(settings.networkUrl) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Stream URL") },
        placeholder = { Text("http://192.168.1.20:8080/video") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            focus.clearFocus()
            update { it.copy(networkUrl = url.trim()) }
        }),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Muted("Same Wi‑Fi as this device.", Modifier.weight(1f))
        TextButton(enabled = url.trim() != settings.networkUrl, onClick = { update { it.copy(networkUrl = url.trim()) } }) {
            Text("Save URL")
        }
    }
    Muted(
        "MJPEG (lowest latency):\n" +
            "• IP Webcam app: http://IP:8080/video\n" +
            "• DroidCam: http://IP:4747/video\n" +
            "• ESP32-CAM: http://IP:81/stream\n" +
            "RTSP also works: rtsp://user:pass@IP:554/stream"
    )
    Text("Rotation", fontSize = 16.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 90, 180, 270).forEach { r ->
            FilterChip(
                selected = settings.networkRotation == r,
                onClick = { update { it.copy(networkRotation = r) } },
                label = { Text("$r°") },
            )
        }
    }
}

@Composable
private fun LivePreview() {
    val context = LocalContext.current
    val state by ControlBus.state.collectAsStateWithLifecycle()
    SectionCard("Live tracking") {
        if (!state.running) {
            Muted("Start AirControl to see what the camera sees and check that your hand is tracked.")
            Button(onClick = { TrackingService.start(context) }) { Text("Start") }
            return@SectionCard
        }
        val frame by ControlBus.preview.collectAsStateWithLifecycle()
        val f = frame
        if (f == null) {
            Muted(state.sourceStatus.ifEmpty { "Waiting for frames…" })
            return@SectionCard
        }
        val aspect = f.bitmap.width.toFloat() / f.bitmap.height.toFloat()
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
        ) {
            Image(
                bitmap = f.bitmap.asImageBitmap(),
                contentDescription = "Camera preview",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = if (f.mirrored) -1f else 1f },
            )
            Canvas(Modifier.fillMaxSize()) {
                val snap = f.snapshot
                fun o(x: Float, y: Float) = Offset(x * size.width, y * size.height)
                snap.pose?.let { pose ->
                    for ((a, b) in PoseLm.UPPER_BODY) {
                        if (pose.visibility[a] < 0.5f || pose.visibility[b] < 0.5f) continue
                        drawLine(
                            Color.White.copy(alpha = 0.7f),
                            o(pose.landmarks[a].x, pose.landmarks[a].y),
                            o(pose.landmarks[b].x, pose.landmarks[b].y),
                            strokeWidth = 5f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
                for (hand in snap.hands) {
                    for ((a, b) in HandLm.CONNECTIONS) {
                        drawLine(
                            AirColors.Coral,
                            o(hand.landmarks[a].x, hand.landmarks[a].y),
                            o(hand.landmarks[b].x, hand.landmarks[b].y),
                            strokeWidth = 4f,
                            cap = StrokeCap.Round,
                        )
                    }
                    hand.landmarks.forEach { drawCircle(Color.White, 5f, o(it.x, it.y)) }
                }
            }
            Text(
                if (state.armed) "ARMED" else "watching for 🤟",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Sign", color = AirColors.Muted, fontSize = 13.sp)
                Text(signLabel(state.sign), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Text("Hand / body", color = AirColors.Muted, fontSize = 13.sp)
                Text("${if (state.handVisible) "✓" else "–"} / ${if (state.bodyVisible) "✓" else "–"}", fontSize = 18.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("Speed", color = AirColors.Muted, fontSize = 13.sp)
                Text("%.0f fps".format(state.fps), fontSize = 18.sp)
            }
        }
        Text("Pinch — counts as touching past the line", color = AirColors.Muted, fontSize = 13.sp)
        Box {
            LinearProgressIndicator(
                progress = { (1f - (state.pinch / 1.2f)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                strokeCap = StrokeCap.Round,
            )
        }
        Row {
            Spacer(Modifier.weight(1f - 0.25f))
            Box(Modifier.width(2.dp).height(10.dp).background(AirColors.Text))
            Spacer(Modifier.weight(0.25f))
        }
    }
}

private fun signLabel(sign: String) = when (sign) {
    "ILoveYou" -> "🤟 arm"
    "Thumb_Down" -> "👎"
    "Thumb_Up" -> "👍"
    "Closed_Fist" -> "✊"
    "Victory" -> "✌️"
    "Open_Palm" -> "✋"
    "Pointing_Up" -> "☝️"
    else -> "–"
}
