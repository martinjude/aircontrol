package app.aircontrol.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aircontrol.gesture.Bind
import app.aircontrol.gesture.Binds
import app.aircontrol.gesture.ControlEvent
import app.aircontrol.gesture.HandSide
import app.aircontrol.gesture.HandSpec
import app.aircontrol.gesture.Pose
import app.aircontrol.gesture.SystemAction
import app.aircontrol.service.ControlBus
import app.aircontrol.settings.AppSettings
import app.aircontrol.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun BindsScreen(settings: AppSettings, repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    fun update(t: (AppSettings) -> AppSettings) {
        scope.launch { repo.update(t) }
    }
    var editing by remember { mutableStateOf<Bind?>(null) }
    var creating by remember { mutableStateOf(false) }

    Text("Binds", fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Muted(
        "Hold a hand pose for a moment to trigger an action while gesture control is on. " +
            "One hand, or two hands together; optionally only the left or the right hand. " +
            "🤟 is reserved for turning control on and off, and ☝️ moves the cursor."
    )

    val duplicates = settings.binds.filter { it.enabled }.groupBy { it.triggerKey }.filterValues { it.size > 1 }.keys
    if (settings.binds.isEmpty()) Muted("No binds yet.")
    settings.binds.forEach { b ->
        BindCard(
            b,
            duplicate = b.enabled && b.triggerKey in duplicates,
            onToggle = { on -> update { s -> s.copy(binds = s.binds.map { if (it.id == b.id) it.copy(enabled = on) else it }) } },
            onEdit = { editing = b },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { creating = true }) { Text("Add bind") }
        OutlinedButton(onClick = { update { it.copy(binds = Binds.defaults()) } }) { Text("Reset to defaults") }
    }
    SectionCard("Tips") {
        Muted("• Two-hand binds need both poses held at the same time. A one-hand bind with the same pose as one of them may fire first if you raise your hands one after the other.")
        Muted("• 🤙, 3️⃣ and 4️⃣ are recognised from finger positions, not by MediaPipe: they are newer, so hold them steady; they may need tuning on your hand. 4️⃣ means four fingers up with the thumb tucked; 🖐 is the open palm with the thumb out.")
        Muted("• Left / right is the hand as you see it from your side. If it comes out swapped, flip it in Settings → Tracking.")
        Muted("• \"Open an app\" needs the accessibility service to be on; the target app opens over whatever is showing.")
    }

    if (creating || editing != null) {
        val close = { creating = false; editing = null }
        BindEditor(
            initial = editing,
            onDismiss = close,
            onSave = { nb ->
                update { s ->
                    s.copy(binds = if (s.binds.any { it.id == nb.id }) s.binds.map { if (it.id == nb.id) nb else it } else s.binds + nb)
                }
                close()
            },
            onDelete = { id ->
                update { s -> s.copy(binds = s.binds.filterNot { it.id == id }) }
                close()
            },
        )
    }
}

@Composable
private fun BindCard(b: Bind, duplicate: Boolean, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    SectionCard(modifier = Modifier.clickable(onClick = onEdit)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${b.triggerLabel}  →  ${b.actionLabel}", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Muted(describeBind(b))
                if (duplicate) Text("Same gesture as another bind: only one of them will fire.", color = AirColors.Warn, fontSize = 13.sp)
            }
            Switch(checked = b.enabled, onCheckedChange = onToggle)
        }
    }
}

internal fun describeBind(b: Bind): String {
    fun spec(s: HandSpec) = s.pose.label.substringBefore(" (").substringBefore(",") + when (s.side) {
        HandSide.ANY -> ""
        HandSide.LEFT -> " (left hand)"
        HandSide.RIGHT -> " (right hand)"
    }
    val trigger = b.second?.let { "${spec(b.first)} + ${spec(it)}" } ?: spec(b.first)
    return "$trigger · hold %.1f s".format(b.holdMs / 1000f)
}

private fun sideLabel(s: HandSide) = when (s) {
    HandSide.ANY -> "Either hand"
    HandSide.LEFT -> "Left hand"
    HandSide.RIGHT -> "Right hand"
}

@Composable
private fun BindEditor(initial: Bind?, onDismiss: () -> Unit, onSave: (Bind) -> Unit, onDelete: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var two by remember { mutableStateOf(initial?.twoHands ?: false) }
    var p1 by remember { mutableStateOf(initial?.first?.pose ?: Pose.THUMB_UP) }
    var s1 by remember { mutableStateOf(initial?.first?.side ?: HandSide.ANY) }
    var p2 by remember { mutableStateOf(initial?.second?.pose ?: Pose.THUMB_UP) }
    var s2 by remember { mutableStateOf(initial?.second?.side ?: HandSide.ANY) }
    var action by remember { mutableStateOf(initial?.action ?: SystemAction.MEDIA_PLAY_PAUSE) }
    var pkg by remember { mutableStateOf(initial?.appPackage) }
    var appLabel by remember { mutableStateOf(initial?.appLabel) }
    var holdOverride by remember { mutableStateOf(initial?.holdMs?.div(1000f)) }
    var picking by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<String?>(null) }

    val suggestedHold = (if (two) maxOf(p1.defaultHoldMs, p2.defaultHoldMs) else p1.defaultHoldMs) / 1000f
    val holdSec = holdOverride ?: suggestedHold
    val valid = action != SystemAction.LAUNCH_APP || pkg != null

    fun build() = Bind(
        id = initial?.id ?: Binds.newId(),
        first = HandSpec(p1, s1),
        second = if (two) HandSpec(p2, s2) else null,
        action = action,
        appPackage = pkg.takeIf { action == SystemAction.LAUNCH_APP },
        appLabel = appLabel.takeIf { action == SystemAction.LAUNCH_APP },
        holdMs = (holdSec * 1000).roundToInt(),
        enabled = initial?.enabled ?: true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New bind" else "Edit bind") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to "One hand", true to "Two hands").forEachIndexed { i, (v, label) ->
                        SegmentedButton(selected = two == v, onClick = { two = v }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
                Text(if (two) "First hand" else "Hand", fontWeight = FontWeight.SemiBold)
                DropdownRow("Pose", p1, Pose.entries, { "${it.emoji} ${it.label}" }) { p1 = it }
                DropdownRow("Which", s1, HandSide.entries, ::sideLabel) { s1 = it }
                if (two) {
                    Text("Second hand", fontWeight = FontWeight.SemiBold)
                    DropdownRow("Pose", p2, Pose.entries, { "${it.emoji} ${it.label}" }) { p2 = it }
                    DropdownRow("Which", s2, HandSide.entries, ::sideLabel) { s2 = it }
                }
                DropdownRow("Action", action, SystemAction.entries.filter { it != SystemAction.NONE }, { it.label }) { action = it }
                if (action == SystemAction.LAUNCH_APP) {
                    OutlinedButton(onClick = { picking = true }, Modifier.fillMaxWidth()) { Text(appLabel ?: "Choose an app…") }
                }
                SettingSlider("Hold time", holdSec, 0.3f..2f, 16, { "%.1f s".format(it) }) { holdOverride = it }
                countdown?.let { Text(it, color = AirColors.Coral, fontWeight = FontWeight.SemiBold) }
                OutlinedButton(
                    enabled = valid && countdown == null,
                    onClick = {
                        val b = build()
                        scope.launch {
                            for (i in 3 downTo 1) {
                                countdown = "Testing in $i…"
                                delay(1000)
                            }
                            countdown = null
                            ControlBus.events.tryEmit(ControlEvent.BindFired(b.action, b.appPackage, b.actionLabel))
                        }
                    },
                ) { Text("Test the action in 3 s") }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(build()) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onDelete(initial.id) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (picking) {
        AppPicker(onDismiss = { picking = false }) { a ->
            pkg = a.pkg
            appLabel = a.label
            picking = false
        }
    }
}

private data class AppEntry(val label: String, val pkg: String)

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun AppPicker(onDismiss: () -> Unit, onPick: (AppEntry) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps(context) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val list = apps
                if (list == null) {
                    Muted("Loading…")
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list.filter { it.label.contains(query, ignoreCase = true) }, key = { it.pkg }) { a ->
                            Text(
                                a.label,
                                Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 12.dp, horizontal = 4.dp),
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
