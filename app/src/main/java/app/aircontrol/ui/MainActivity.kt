package app.aircontrol.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aircontrol.gesture.ControlEvent
import app.aircontrol.gesture.DisarmReason
import app.aircontrol.gesture.SwipeDir
import app.aircontrol.gesture.SystemAction
import app.aircontrol.service.AirControlAccessibilityService
import app.aircontrol.service.ControlBus
import app.aircontrol.service.TrackingService
import app.aircontrol.settings.AppSettings
import app.aircontrol.settings.SettingsRepository
import app.aircontrol.settings.SourceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { AirTheme { AirApp() } }
    }
}

private enum class Tab(val icon: String, val label: String) {
    HOME("🤟", "Control"), CAMERA("📷", "Camera"), BINDS("🖐", "Binds"), SETTINGS("⚙️", "Settings"), HELP("❔", "Help")
}

@Composable
private fun AirApp() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val settings by repo.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    Scaffold(
        containerColor = AirColors.Bg,
        bottomBar = {
            NavigationBar(containerColor = AirColors.Surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Text(t.icon, fontSize = 20.sp) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (tab) {
                Tab.HOME -> HomeScreen(settings)
                Tab.CAMERA -> CameraScreen(settings, repo)
                Tab.BINDS -> BindsScreen(settings, repo)
                Tab.SETTINGS -> SettingsScreen(settings, repo)
                Tab.HELP -> HelpScreen(settings)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ------------------------------------------------------------------ setup state

data class SetupStatus(
    val camera: Boolean,
    val notifications: Boolean,
    val accessibility: Boolean,
    val battery: Boolean,
)

fun readSetup(context: Context, a11yConnected: Boolean): SetupStatus {
    val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val notifications = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val am = context.getSystemService(AccessibilityManager::class.java)
    val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
        it.resolveInfo.serviceInfo.packageName == context.packageName
    }
    val pm = context.getSystemService(PowerManager::class.java)
    return SetupStatus(camera, notifications, enabled || a11yConnected, pm.isIgnoringBatteryOptimizations(context.packageName))
}

fun openAccessibilitySettings(context: Context) {
    val component = ComponentName(context, AirControlAccessibilityService::class.java).flattenToString()
    val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
    try {
        context.startActivity(detail)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    } catch (e: SecurityException) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

fun openAppInfo(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

// ------------------------------------------------------------------ home

@Composable
private fun HomeScreen(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ControlBus.state.collectAsStateWithLifecycle()
    val a11y by AirControlAccessibilityService.connected.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val setup = remember(refresh, a11y, state.running) { readSetup(context, a11y) }

    val needsCamera = settings.source != SourceType.NETWORK
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        if (granted) TrackingService.start(context)
    }

    fun start() {
        if (!setup.notifications && Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (needsCamera && !setup.camera) cameraLauncher.launch(Manifest.permission.CAMERA) else TrackingService.start(context)
    }

    Text("AirControl", fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Muted("Hands-free control of this device with your camera. Hold 🤟 for 1 second to take control.")

    SectionCard {
        val (color, title) = when {
            !state.running -> AirColors.Muted to "Not running"
            state.armed -> AirColors.Coral to "Gesture control ON"
            else -> AirColors.Ok to "Watching for 🤟"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, 14)
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        if (state.running) {
            Muted("${state.sourceLabel} · ${state.sourceStatus}")
            Muted("%.0f fps · %d ms inference · %s".format(state.fps, state.inferenceMs, state.delegate.ifEmpty { "…" }))
        } else {
            Muted("Starts the camera in the background. A notification stays visible while AirControl is watching.")
        }
        if (!setup.accessibility) {
            Text("⚠️ Enable the accessibility service below, otherwise gestures can't act on the screen.", color = AirColors.Warn, fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!state.running) {
                Button(onClick = ::start, modifier = Modifier.height(52.dp)) { Text("Start", fontSize = 17.sp) }
            } else {
                Button(
                    onClick = { TrackingService.stop(context) },
                    modifier = Modifier.height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AirColors.SurfaceHigh, contentColor = AirColors.Text),
                ) { Text("Stop", fontSize = 17.sp) }
                FilledTonalButton(onClick = { TrackingService.toggleArm(context) }, modifier = Modifier.height(52.dp)) {
                    Text(if (state.armed) "Disarm" else "Arm now")
                }
            }
        }
    }

    SectionCard("Setup") {
        SetupRow(
            "Camera",
            if (needsCamera) "Needed for the front/back camera" else "Not needed for a network camera",
            ok = setup.camera || !needsCamera,
            action = "Allow",
        ) { cameraLauncher.launch(Manifest.permission.CAMERA) }
        if (Build.VERSION.SDK_INT >= 33) {
            SetupRow("Notifications", "Shows the Arm/Stop controls while running", setup.notifications, "Allow") {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        SetupRow("Accessibility service", "Lets AirControl tap, swipe and draw the glow", setup.accessibility, "Open") {
            openAccessibilitySettings(context)
        }
        if (!setup.accessibility) {
            Muted("If the switch is greyed out (\"Restricted setting\"): open App info → ⋮ menu → Allow restricted settings, then come back and enable it.")
            TextButton(onClick = { openAppInfo(context) }) { Text("Open App info") }
        }
        SetupRow("Battery: unrestricted", "Stops Android from killing tracking in the background", setup.battery, "Allow") {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            } catch (e: ActivityNotFoundException) {
                openAppInfo(context)
            }
        }
    }

    TestCard(enabled = setup.accessibility, running = state.running, armed = state.armed) { block ->
        scope.launch { block() }
    }
}

@Composable
private fun SetupRow(title: String, subtitle: String, ok: Boolean, action: String, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (ok) AirColors.Ok else AirColors.Warn)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Muted(subtitle)
        }
        if (!ok) OutlinedButton(onClick = onFix) { Text(action) } else Text("✓", color = AirColors.Ok, fontSize = 18.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestCard(enabled: Boolean, running: Boolean, armed: Boolean, launch: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var testGlow by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<String?>(null) }

    fun delayed(name: String, action: suspend () -> Unit) = launch {
        for (i in 3 downTo 1) {
            countdown = "$name in $i…"
            delay(1000)
        }
        countdown = null
        action()
    }

    SectionCard("Test without the camera") {
        Muted("Checks that injection works on this device. Delayed actions give you 3 s to switch to another app.")
        if (!enabled) Text("Enable the accessibility service first.", color = AirColors.Warn, fontSize = 14.sp)
        countdown?.let { Text(it, color = AirColors.Coral, fontWeight = FontWeight.SemiBold) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = enabled, onClick = {
                if (running) {
                    TrackingService.toggleArm(context)
                } else {
                    testGlow = !testGlow
                    ControlBus.events.tryEmit(if (testGlow) ControlEvent.Armed else ControlEvent.Disarmed(DisarmReason.MANUAL))
                }
            }) { Text(if ((running && armed) || (!running && testGlow)) "Hide glow" else "Show glow") }
            FilledTonalButton(enabled = enabled, onClick = {
                delayed("Tap") { ControlBus.events.tryEmit(ControlEvent.Tap(0.5f, 0.5f)) }
            }) { Text("Tap center") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(SwipeDir.UP to "↑", SwipeDir.DOWN to "↓", SwipeDir.LEFT to "←", SwipeDir.RIGHT to "→").forEach { (d, l) ->
                OutlinedButton(enabled = enabled, onClick = {
                    delayed("Swipe $l") { ControlBus.events.tryEmit(ControlEvent.Swipe(d)) }
                }) { Text("Swipe $l") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, onClick = {
                delayed("Drag") {
                    val e = ControlBus.events
                    e.tryEmit(ControlEvent.TouchDown(0.5f, 0.75f))
                    for (i in 1..20) {
                        val y = 0.75f - 0.45f * i / 20f
                        e.tryEmit(ControlEvent.Pointer(0.5f, y, visible = true, touching = true))
                        e.tryEmit(ControlEvent.TouchMove(0.5f, y))
                        delay(33)
                    }
                    e.tryEmit(ControlEvent.TouchUp(0.5f, 0.3f))
                    e.tryEmit(ControlEvent.Pointer(0.5f, 0.3f, visible = true, touching = false))
                }
            }) { Text("Drag ↑") }
            listOf(SystemAction.BACK to "Back", SystemAction.HOME to "Home", SystemAction.RECENTS to "Recents").forEach { (a, l) ->
                OutlinedButton(enabled = enabled, onClick = {
                    delayed(l) { ControlBus.events.tryEmit(ControlEvent.BindFired(a, null, a.label)) }
                }) { Text(l) }
            }
        }
    }
}
