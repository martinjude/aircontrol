package app.aircontrol.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

    PageTitle(
        "Binds",
        "Hold a hand pose for a moment to trigger an action while gesture control is on: one hand or two together, " +
            "optionally only the left or the right. 🤟 turns control on and off, so it can't be bound.",
    )

    val duplicates = settings.binds.filter { it.enabled }.groupBy { it.triggerKey }.filterValues { it.size > 1 }.keys
    if (settings.binds.isEmpty()) Muted("No binds yet")
    settings.binds.forEach { b ->
        BindCard(
            b,
            duplicate = b.enabled && b.triggerKey in duplicates,
            onToggle = { on -> update { s -> s.copy(binds = s.binds.map { if (it.id == b.id) it.copy(enabled = on) else it }) } },
            onEdit = { editing = b },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { creating = true }) {
            AirIcon(AirIcons.Plus, AirColors.OnAccent, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text("Add bind")
        }
        OutlinedButton(onClick = { update { it.copy(binds = Binds.defaults()) } }) { Text("Reset to defaults", color = AirColors.Text) }
    }
    SectionCard("Tips") {
        Muted("Two-hand binds need both poses at the same time. A one-hand bind with one of the same poses may fire first if you raise your hands one after the other.")
        Muted("🤙, 3️⃣ and 4️⃣ are read from finger positions rather than by the gesture model: hold them steady. 4️⃣ means the thumb tucked; 🖐 has the thumb out.")
        Muted("After any bound pose, taps, pushes and swipes pause for a moment (Settings, Click and hold) so going back to pointing never clicks.")
        Muted("Left and right are as seen from your side. If they come out swapped, flip them in Settings, Tracking.")
        Muted("Open an app needs the accessibility service on; the app opens over whatever is showing.")
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
            TriggerGlyphs(b)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(b.actionLabel, style = AirType.BodyStrong, color = if (b.enabled) AirColors.Text else AirColors.Muted)
                Muted(describeBind(b))
                if (duplicate) Text("Same gesture as another bind: only one will fire", style = AirType.Caption, color = AirColors.Warn)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = b.enabled, onCheckedChange = onToggle)
        }
    }
}

/** The bind's pose(s) as emoji, with L / R under a side-specific hand. */
@Composable
internal fun TriggerGlyphs(b: Bind) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        listOfNotNull(b.first, b.second).forEach { spec ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Emoji(spec.pose.emoji, 28.dp)
                if (spec.side != HandSide.ANY) Text(if (spec.side == HandSide.LEFT) "L" else "R", style = AirType.Caption, color = AirColors.Text2)
            }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (two) "First hand" else "Hand", style = AirType.BodyStrong, modifier = Modifier.weight(1f))
                    Emoji(p1.emoji, 24.dp)
                }
                DropdownRow("Pose", p1, Pose.entries, { "${it.emoji}  ${it.label}" }) { p1 = it }
                DropdownRow("Which", s1, HandSide.entries, ::sideLabel) { s1 = it }
                if (two) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Second hand", style = AirType.BodyStrong, modifier = Modifier.weight(1f))
                        Emoji(p2.emoji, 24.dp)
                    }
                    DropdownRow("Pose", p2, Pose.entries, { "${it.emoji}  ${it.label}" }) { p2 = it }
                    DropdownRow("Which", s2, HandSide.entries, ::sideLabel) { s2 = it }
                }
                DropdownRow("Action", action, SystemAction.entries.filter { it != SystemAction.NONE }, { it.label }) { action = it }
                if (action == SystemAction.LAUNCH_APP) {
                    OutlinedButton(onClick = { picking = true }, Modifier.fillMaxWidth()) { Text(appLabel ?: "Choose an app", color = AirColors.Text) }
                }
                SettingSlider("Hold time", holdSec, 0.3f..2f, 16, { "%.1f s".format(it) }) { holdOverride = it }
                countdown?.let { Text(it, style = AirType.BodyStrong, color = AirColors.Coral) }
                OutlinedButton(
                    enabled = valid && countdown == null,
                    onClick = {
                        val b = build()
                        scope.launch {
                            for (i in 3 downTo 1) {
                                countdown = "Testing in $i s"
                                delay(1000)
                            }
                            countdown = null
                            ControlBus.events.tryEmit(ControlEvent.BindFired(b.action, b.appPackage, b.actionLabel))
                        }
                    },
                ) { Text("Test the action in 3 s", color = AirColors.Text) }
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
                    Muted("Loading apps")
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list.filter { it.label.contains(query, ignoreCase = true) }, key = { it.pkg }) { a ->
                            Text(
                                a.label,
                                Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 12.dp, horizontal = 4.dp),
                                style = AirType.Body,
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
