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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
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
import android.graphics.RectF
import app.aircontrol.gesture.TapGate
import app.aircontrol.gesture.ClickMethod
import app.aircontrol.gesture.ControlEvent
import app.aircontrol.gesture.TouchSource
import app.aircontrol.overlay.TrackingRenderer
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

    PageTitle("Camera")

    SectionCard("Source") {
        Segmented(settings.source, listOf(SourceType.FRONT to "Front", SourceType.BACK to "Back", SourceType.NETWORK to "Network")) { v ->
            update { it.copy(source = v) }
        }
        when (settings.source) {
            SourceType.FRONT -> Muted("Recommended: the selfie camera faces you while you look at the screen.")
            SourceType.BACK -> Muted("Use when the back of the device faces you, for example a tablet on a stand.")
            SourceType.NETWORK -> NetworkFields(settings) { t -> update(t) }
        }
        SwitchRow(
            "Mirror horizontally",
            "On for any camera that faces you, so your right is the cursor's right.",
            settings.mirror,
        ) { v -> update { it.copy(mirror = v) } }
    }

    LivePreview(settings)
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
        Muted("Same Wi‑Fi as this device", Modifier.weight(1f))
        TextButton(enabled = url.trim() != settings.networkUrl, onClick = { update { it.copy(networkUrl = url.trim()) } }) {
            Text("Save URL")
        }
    }
    Muted("MJPEG has the lowest latency. RTSP also works.")
    Text(
        "IP Webcam   http://IP:8080/video\nDroidCam    http://IP:4747/video\nESP32-CAM   http://IP:81/stream\nRTSP        rtsp://user:pass@IP:554/stream",
        style = AirType.Figure, color = AirColors.Text2,
    )
    Text("Rotation", style = AirType.Body, color = AirColors.Text)
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
private const val SPARK_LO = 0f
private const val SPARK_HI = 1.2f
private const val GAP_SCALE = 1.2f

private class ReachHistory {
    val v = FloatArray(SPARK_N) { Float.NaN }
    val click = BooleanArray(SPARK_N)
    var idx = 0
    var lastT = Long.MIN_VALUE
    var lastClick = Long.MIN_VALUE

    fun push(d: EngineDebug) {
        if (d.t == lastT) return
        lastT = d.t
        v[idx] = if (d.hands > 0) d.gap else Float.NaN
        val ct = if (d.clickAgoMs == Long.MAX_VALUE) Long.MIN_VALUE else d.t - d.clickAgoMs
        click[idx] = ct != Long.MIN_VALUE && ct != lastClick
        if (click[idx]) lastClick = ct
        idx = (idx + 1) % SPARK_N
    }
}

private fun describeEvent(e: ControlEvent): String? = when (e) {
    ControlEvent.Armed -> "armed"
    is ControlEvent.Disarmed -> "disarmed"
    is ControlEvent.Tap -> if (e.click) "click" else "push tap"
    is ControlEvent.TouchDown -> "touch down"
    is ControlEvent.TouchUp -> "touch up"
    is ControlEvent.Swipe -> "swipe ${e.dir.name.lowercase()}"
    is ControlEvent.BindFired -> "bind: ${e.label}"
    is ControlEvent.ClickRejected -> "✗ ${e.reason}"
    is ControlEvent.Calibration -> if (e.done) e.message.lowercase() else null
    else -> null
}

@Composable
private fun LivePreview(settings: AppSettings) {
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
            Muted(state.sourceStatus.ifEmpty { "Waiting for frames" })
            return@SectionCard
        }
        val d = f.snapshot.debug
        val hist = remember { ReachHistory() }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(d.t) {
            hist.push(d)
            tick++
        }
        val density = LocalDensity.current.density
        val renderer = remember(density) { TrackingRenderer(density) }
        val showPush = settings.clickMethod != ClickMethod.TAP
        val aspect = f.bitmap.width.toFloat() / f.bitmap.height.toFloat()
        val box = remember { RectF() }
        Canvas(Modifier.fillMaxWidth().aspectRatio(aspect)) {
            tick
            box.set(0f, 0f, size.width, size.height)
            drawIntoCanvas { renderer.drawCamera(it.nativeCanvas, box, 12.dp.toPx(), f.snapshot, f.bitmap, f.mirrored, showPush) }
        }
        val (status, color) = TrackingRenderer.status(d, showPush)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (state.armed) AirColors.Coral else AirColors.Muted)
            Spacer(Modifier.width(8.dp))
            Text(status, style = AirType.BodyStrong, color = Color(color), modifier = Modifier.weight(1f))
            Text(
                (if (d.hands == 0) "" else f.snapshot.hands.joinToString("+") {
                    when (it.side) { HandSide.LEFT -> "L"; HandSide.RIGHT -> "R"; HandSide.ANY -> "?" }
                } + "  ") + "%.0f fps".format(state.fps),
                style = AirType.Figure, color = AirColors.Text2,
            )
        }
        Muted("The frame is where your palm can go with the whole hand in view; it spans the screen. An edge lights up when your palm reaches it.")
        val have = d.hands > 0
        Meter(
            "Pointer confidence",
            if (have) "%.0f %%".format(d.pointerConfidence * 100) + (if (d.attention >= 0f) " · eyes %.0f %%".format(d.attention * 100) else "") else "–",
            if (have) d.pointerConfidence else 0f,
            emptyList(),
            if (have && d.pointerConfidence < 0.5f) AirColors.Warn else AirColors.Text2,
        )
        Meter(
            "Palm angle (ticks: tolerance, blocked)",
            if (have) "%.0f°".format(d.angleDeg) else "–",
            if (have) d.angleDeg / (d.angleTolDeg * 2.5f) else 0f,
            listOf(1f / 2.5f, (d.angleTolDeg + 25f) / (d.angleTolDeg * 2.5f)),
            if (have && d.angleDeg > d.angleTolDeg) AirColors.Warn else AirColors.Text2,
        )
        Meter(
            "Thumb–index gap (ticks: touch, release)",
            if (have) "%.2f".format(d.gap) + (if (d.cooldown) " · pause" else "") else "–",
            if (have) 1f - d.gap / GAP_SCALE else 0f,
            listOf(1f - d.tapEnter / GAP_SCALE, 1f - d.tapExit / GAP_SCALE),
            when {
                !have -> AirColors.Muted
                d.touchSource == TouchSource.TAP -> AirColors.Ok
                d.cooldown -> AirColors.Muted
                else -> AirColors.Coral
            },
        )
        Meter(
            "Last tap confidence",
            "%.2f".format(d.clickConfidence) + "  " + TapGate.FACTOR_NAMES.indices.filter { d.clickFactors[it] in 0f..0.5f }
                .joinToString(", ") { TapGate.FACTOR_NAMES[it] },
            d.clickConfidence,
            listOf(settings.clickStrictness.threshold),
            AirColors.Text2,
        )
        if (showPush) {
            Meter(
                "Air push (tick: the plane)",
                if (have) "%.0f %%".format(d.push * 100) + (if (d.pushCalibrated) "" else " · floating") else "–",
                if (have) d.push / TrackingRenderer.PUSH_GAUGE_MAX else 0f,
                listOf(1f / TrackingRenderer.PUSH_GAUGE_MAX),
                if (d.touchSource == TouchSource.PUSH) AirColors.Ok else AirColors.Coral,
            )
        }
        Text("Thumb–index gap over time: a tap dips below the accent line", style = AirType.Caption, color = AirColors.Text2)
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
            drawLine(AirColors.Text.copy(alpha = 0.35f), Offset(0f, sy(d.tapExit)), Offset(size.width, sy(d.tapExit)), strokeWidth = 2f, pathEffect = dashes)
            drawLine(AirColors.Coral, Offset(0f, sy(d.tapEnter)), Offset(size.width, sy(d.tapEnter)), strokeWidth = 3f, pathEffect = dashes)
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
            drawPath(path, AirColors.Text, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
        if (d.bindLabel != null) Meter("Bind", d.bindLabel, d.bindProgress, emptyList(), AirColors.Coral)
        if (log.isNotEmpty()) {
            Text("Events", style = AirType.Caption, color = AirColors.Text2)
            Text(log.takeLast(6).joinToString("\n"), style = AirType.Figure, color = AirColors.Text)
        }
    }
}
