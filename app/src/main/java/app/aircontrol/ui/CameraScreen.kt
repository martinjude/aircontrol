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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aircontrol.gesture.ControlEvent
import app.aircontrol.gesture.EngineDebug
import app.aircontrol.gesture.HandLm
import app.aircontrol.gesture.HandSide
import app.aircontrol.gesture.PointerMap
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


// ------------------------------------------------------------------ live tracking

private const val SPARK_N = 90
private const val SPARK_LO = 0.8f
private const val SPARK_HI = 2.4f
private const val PINCH_SCALE = 1.2f
private const val CLICK_FLASH_MS = 400L

private class ReachHistory {
    val v = FloatArray(SPARK_N) { Float.NaN }
    val click = BooleanArray(SPARK_N)
    var idx = 0
    var lastT = Long.MIN_VALUE
    var lastClick = Long.MIN_VALUE

    fun push(d: EngineDebug) {
        if (d.t == lastT) return
        lastT = d.t
        v[idx] = if (d.hands > 0) d.reach else Float.NaN
        val ct = if (d.clickAgoMs == Long.MAX_VALUE) Long.MIN_VALUE else d.t - d.clickAgoMs
        click[idx] = ct != Long.MIN_VALUE && ct != lastClick
        if (click[idx]) lastClick = ct
        idx = (idx + 1) % SPARK_N
    }
}

private fun describeEvent(e: ControlEvent): String? = when (e) {
    ControlEvent.Armed -> "armed"
    is ControlEvent.Disarmed -> "disarmed"
    is ControlEvent.Tap -> if (e.click) "click" else "pinch tap"
    is ControlEvent.TouchDown -> "touch down"
    is ControlEvent.TouchUp -> "touch up"
    is ControlEvent.Swipe -> "swipe ${e.dir.name.lowercase()}"
    is ControlEvent.BindFired -> "bind: ${e.label}"
    is ControlEvent.ClickRejected -> "click ✗ ${e.reason}"
    else -> null
}

@Composable
private fun LivePreview() {
    val context = LocalContext.current
    val state by ControlBus.state.collectAsStateWithLifecycle()
    val log = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        ControlBus.events.collect { e ->
            describeEvent(e)?.let {
                log.add(it)
                while (log.size > 8) log.removeAt(0)
            }
        }
    }
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
        val d = f.snapshot.debug
        val hist = remember { ReachHistory() }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(d.t) {
            hist.push(d)
            tick++
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
                // The camera region that spans the whole screen.
                val hs = PointerMap.halfSpan(d.gain)
                drawRect(
                    AirColors.Coral.copy(alpha = 0.7f),
                    topLeft = o(0.5f - hs, 0.5f - hs),
                    size = Size(size.width * 2 * hs, size.height * 2 * hs),
                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
                snap.pose?.let { pose ->
                    for ((a, b) in PoseLm.UPPER_BODY) {
                        if (pose.visibility[a] < 0.5f || pose.visibility[b] < 0.5f) continue
                        drawLine(
                            Color.White.copy(alpha = 0.6f),
                            o(pose.landmarks[a].x, pose.landmarks[a].y),
                            o(pose.landmarks[b].x, pose.landmarks[b].y),
                            strokeWidth = 5f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
                for (hand in snap.hands) {
                    for ((a, b) in HandLm.CONNECTIONS) {
                        val color = when (b) {
                            in 1..4 -> AirColors.Amber
                            in 6..8 -> AirColors.Coral
                            else -> Color.White
                        }
                        drawLine(
                            color,
                            o(hand.landmarks[a].x, hand.landmarks[a].y),
                            o(hand.landmarks[b].x, hand.landmarks[b].y),
                            strokeWidth = 5f,
                            cap = StrokeCap.Round,
                        )
                    }
                    hand.landmarks.forEach { drawCircle(Color.White, 4f, o(it.x, it.y)) }
                }
                if (d.hands > 0) {
                    if (d.pinch < d.pinchEnter) {
                        val hand = snap.hands.minBy {
                            kotlin.math.abs(it.landmarks[HandLm.MIDDLE_MCP].x - d.palmU) + kotlin.math.abs(it.landmarks[HandLm.MIDDLE_MCP].y - d.palmV)
                        }
                        drawLine(
                            if (d.pinchReady) AirColors.Ok else AirColors.Bad,
                            o(hand.landmarks[HandLm.THUMB_TIP].x, hand.landmarks[HandLm.THUMB_TIP].y),
                            o(hand.landmarks[HandLm.INDEX_TIP].x, hand.landmarks[HandLm.INDEX_TIP].y),
                            strokeWidth = 8f,
                            cap = StrokeCap.Round,
                        )
                    }
                    // The cursor reference: the centre of the palm.
                    val c = o(d.palmU, d.palmV)
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(c.x - 28f, c.y), Offset(c.x + 28f, c.y), strokeWidth = 3f)
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(c.x, c.y - 28f), Offset(c.x, c.y + 28f), strokeWidth = 3f)
                    drawCircle(Color.White, 15f, c, style = Stroke(width = 4f))
                    drawCircle(AirColors.Coral, 7f, c)
                    if (d.clickAgoMs < CLICK_FLASH_MS) {
                        val k = d.clickAgoMs / CLICK_FLASH_MS.toFloat()
                        drawCircle(AirColors.Ok.copy(alpha = 1f - k), 16f + 44f * k, c, style = Stroke(width = 6f))
                    }
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
                Text("Pose", color = AirColors.Muted, fontSize = 13.sp)
                Text(poseLabel(d), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Text("Hands", color = AirColors.Muted, fontSize = 13.sp)
                Text(
                    if (d.hands == 0) "–" else f.snapshot.hands.joinToString(" + ") {
                        when (it.side) { HandSide.LEFT -> "L"; HandSide.RIGHT -> "R"; HandSide.ANY -> "?" }
                    },
                    fontSize = 18.sp,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Speed", color = AirColors.Muted, fontSize = 13.sp)
                Text("%.0f fps".format(state.fps), fontSize = 18.sp)
            }
        }
        val have = d.hands > 0
        Meter(
            "Pinch (ticks: closes / opens)",
            if (have) "%.2f · %s".format(d.pinch, if (d.upright) "upright" else "tilted ${d.tiltDeg.toInt()}°") else "–",
            if (have) 1f - d.pinch / PINCH_SCALE else 0f,
            listOf(1f - d.pinchEnter / PINCH_SCALE, 1f - d.pinchExit / PINCH_SCALE),
            when {
                !have -> AirColors.Muted
                d.touch != "IDLE" -> AirColors.Ok
                d.pinch < d.pinchEnter && !d.pinchReady -> AirColors.Bad
                d.pinchReady -> AirColors.Coral
                else -> AirColors.Muted
            },
        )
        Meter(
            "Click: curl depth (tick = needed)",
            if (have) "%.2f · dip %.2f/%.2f".format(d.reach, d.curlDepth, d.curlNeeded) else "–",
            if (have) d.curlDepth / (d.curlNeeded * 1.5f) else 0f,
            listOf(1f / 1.5f),
            when {
                d.clickAgoMs < CLICK_FLASH_MS -> AirColors.Ok
                d.clickState == "CURLING" -> AirColors.Coral
                else -> AirColors.Muted
            },
        )
        Text("Index reach: the dashed line is how low it must dip", color = AirColors.Muted, fontSize = 13.sp)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AirColors.SurfaceHigh),
        ) {
            tick // redraw on every new frame
            val step = size.width / (SPARK_N - 1)
            fun sy(v: Float) = size.height - ((v - SPARK_LO) / (SPARK_HI - SPARK_LO)).coerceIn(0f, 1f) * size.height
            val dashes = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            if (have && d.reachBaseline > 0f) {
                drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, sy(d.reachBaseline)), Offset(size.width, sy(d.reachBaseline)), strokeWidth = 2f, pathEffect = dashes)
                val need = sy(d.reachBaseline - d.curlNeeded)
                drawLine(AirColors.Coral, Offset(0f, need), Offset(size.width, need), strokeWidth = 3f, pathEffect = dashes)
            }
            for (i in 0 until SPARK_N) {
                if (hist.click[(hist.idx + i) % SPARK_N]) drawLine(AirColors.Ok, Offset(i * step, 0f), Offset(i * step, size.height), strokeWidth = 3f)
            }
            val path = androidx.compose.ui.graphics.Path()
            var pen = false
            for (i in 0 until SPARK_N) {
                val v = hist.v[(hist.idx + i) % SPARK_N]
                if (v.isNaN()) { pen = false; continue }
                if (pen) path.lineTo(i * step, sy(v)) else { path.moveTo(i * step, sy(v)); pen = true }
            }
            drawPath(path, Color.White, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
        if (d.bindLabel != null) Meter("Bind", d.bindLabel, d.bindProgress, emptyList(), AirColors.Coral)
        if (log.isNotEmpty()) {
            Text("Events", color = AirColors.Muted, fontSize = 13.sp)
            Text(log.takeLast(6).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Meter(label: String, value: String, frac: Float, ticks: List<Float>, color: Color) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = AirColors.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, fontSize = 13.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(AirColors.SurfaceHigh, size = size, cornerRadius = r)
            if (frac > 0.005f) drawRoundRect(color, size = Size(size.width * frac.coerceIn(0f, 1f), size.height), cornerRadius = r)
            ticks.forEach { t ->
                val x = size.width * t.coerceIn(0f, 1f)
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
            }
        }
    }
}

private fun poseLabel(d: EngineDebug): String = when {
    d.hands == 0 -> "–"
    d.pose != null -> "${d.pose.emoji} ${d.pose.label.substringBefore(" (").substringBefore(",")}"
    d.sign == "ILoveYou" -> "🤟 arm"
    d.sign == "Pointing_Up" -> "☝️ point"
    d.sign != "None" -> d.sign
    else -> "·"
}
