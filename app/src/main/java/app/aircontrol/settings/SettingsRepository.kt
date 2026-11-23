package app.aircontrol.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.aircontrol.gesture.AnchorMode
import app.aircontrol.gesture.EngineConfig
import app.aircontrol.gesture.Signs
import app.aircontrol.gesture.SystemAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class SourceType { FRONT, BACK, NETWORK }
enum class Accelerator { AUTO, GPU, CPU }

enum class GlowPreset(val label: String, val colors: IntArray) {
    CORAL("Coral", intArrayOf(0xFFD97757.toInt(), 0xFFF2A65A.toInt(), 0xFFFF8A65.toInt(), 0xFFE8607A.toInt(), 0xFFD97757.toInt())),
    BLUE("Electric", intArrayOf(0xFF4FC3F7.toInt(), 0xFF7C4DFF.toInt(), 0xFF40C4FF.toInt(), 0xFF536DFE.toInt(), 0xFF4FC3F7.toInt())),
    VIOLET("Violet", intArrayOf(0xFFB388FF.toInt(), 0xFFEA80FC.toInt(), 0xFF8C9EFF.toInt(), 0xFFFF80AB.toInt(), 0xFFB388FF.toInt())),
    GREEN("Aurora", intArrayOf(0xFF69F0AE.toInt(), 0xFF00E5FF.toInt(), 0xFFB2FF59.toInt(), 0xFF64FFDA.toInt(), 0xFF69F0AE.toInt()));

    val accent: Int get() = colors[0]
}

data class AppSettings(
    val source: SourceType = SourceType.FRONT,
    val networkUrl: String = "",
    val mirror: Boolean = true,
    val networkRotation: Int = 0,
    val accelerator: Accelerator = Accelerator.AUTO,
    val usePose: Boolean = true,
    val anchorMode: AnchorMode = AnchorMode.BODY,
    val pointerSensitivity: Float = 1f,
    val smoothing: Float = 0.5f,
    val swipeSensitivity: Float = 1f,
    val pushSensitivity: Float = 1f,
    val swipeLength: Float = 0.4f,
    val autoDisarmSec: Int = 20,
    val thumbDownAction: SystemAction = SystemAction.BACK,
    val fistAction: SystemAction = SystemAction.HOME,
    val victoryAction: SystemAction = SystemAction.RECENTS,
    val thumbUpAction: SystemAction = SystemAction.RECENTER,
    val glow: GlowPreset = GlowPreset.CORAL,
    val showSkeleton: Boolean = false,
    val showHud: Boolean = false,
    val requireBody: Boolean = false,
) {
    val signActions: Map<String, SystemAction>
        get() = mapOf(
            Signs.THUMB_DOWN to thumbDownAction,
            Signs.CLOSED_FIST to fistAction,
            Signs.VICTORY to victoryAction,
            Signs.THUMB_UP to thumbUpAction,
        )

    fun applyTo(base: EngineConfig): EngineConfig = base.copy(
        signActions = signActions,
        pointerSensitivity = pointerSensitivity,
        smoothing = smoothing,
        swipeSensitivity = swipeSensitivity,
        pushSensitivity = pushSensitivity,
        anchorMode = if (usePose) anchorMode else AnchorMode.HAND,
        autoDisarmMs = autoDisarmSec * 1000L,
        requireBody = requireBody && usePose,
    )

    /** Fields that require restarting the frame source when changed. */
    fun sourceKey(): String = "$source|$networkUrl|$networkRotation"
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val flow: Flow<AppSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs.write(next)
        }
    }

    private object K {
        val source = stringPreferencesKey("source")
        val networkUrl = stringPreferencesKey("network_url")
        val mirror = booleanPreferencesKey("mirror")
        val networkRotation = intPreferencesKey("network_rotation")
        val accelerator = stringPreferencesKey("accelerator")
        val usePose = booleanPreferencesKey("use_pose")
        val anchorMode = stringPreferencesKey("anchor_mode")
        val pointerSensitivity = floatPreferencesKey("pointer_sensitivity")
        val smoothing = floatPreferencesKey("smoothing")
        val swipeSensitivity = floatPreferencesKey("swipe_sensitivity")
        val pushSensitivity = floatPreferencesKey("push_sensitivity")
        val version = intPreferencesKey("settings_version")
        val swipeLength = floatPreferencesKey("swipe_length")
        val autoDisarmSec = intPreferencesKey("auto_disarm_sec")
        val thumbDown = stringPreferencesKey("sign_thumb_down")
        val fist = stringPreferencesKey("sign_fist")
        val victory = stringPreferencesKey("sign_victory")
        val thumbUp = stringPreferencesKey("sign_thumb_up")
        val glow = stringPreferencesKey("glow")
        val showSkeleton = booleanPreferencesKey("show_skeleton")
        val showHud = booleanPreferencesKey("show_hud")
        val requireBody = booleanPreferencesKey("require_body")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        val version = this[K.version] ?: 1
        return AppSettings(
            source = enumOr(this[K.source], d.source),
            networkUrl = this[K.networkUrl] ?: d.networkUrl,
            mirror = this[K.mirror] ?: d.mirror,
            networkRotation = this[K.networkRotation] ?: d.networkRotation,
            accelerator = enumOr(this[K.accelerator], d.accelerator),
            usePose = this[K.usePose] ?: d.usePose,
            anchorMode = enumOr(this[K.anchorMode], d.anchorMode),
            pointerSensitivity = this[K.pointerSensitivity] ?: d.pointerSensitivity,
            smoothing = this[K.smoothing] ?: d.smoothing,
            swipeSensitivity = this[K.swipeSensitivity] ?: d.swipeSensitivity,
            pushSensitivity = this[K.pushSensitivity] ?: d.pushSensitivity,
            swipeLength = this[K.swipeLength] ?: d.swipeLength,
            autoDisarmSec = this[K.autoDisarmSec] ?: d.autoDisarmSec,
            thumbDownAction = enumOr(this[K.thumbDown], d.thumbDownAction),
            fistAction = enumOr(this[K.fist], d.fistAction),
            victoryAction = enumOr(this[K.victory], d.victoryAction),
            // v1 stored "Nothing" for thumb up; v2 makes it the recenter sign.
            thumbUpAction = enumOr(this[K.thumbUp], d.thumbUpAction)
                .let { if (version < SETTINGS_VERSION && it == SystemAction.NONE) SystemAction.RECENTER else it },
            glow = enumOr(this[K.glow], d.glow),
            showSkeleton = this[K.showSkeleton] ?: d.showSkeleton,
            showHud = this[K.showHud] ?: d.showHud,
            requireBody = this[K.requireBody] ?: d.requireBody,
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[K.source] = s.source.name
        this[K.networkUrl] = s.networkUrl
        this[K.mirror] = s.mirror
        this[K.networkRotation] = s.networkRotation
        this[K.accelerator] = s.accelerator.name
        this[K.usePose] = s.usePose
        this[K.anchorMode] = s.anchorMode.name
        this[K.pointerSensitivity] = s.pointerSensitivity
        this[K.smoothing] = s.smoothing
        this[K.swipeSensitivity] = s.swipeSensitivity
        this[K.pushSensitivity] = s.pushSensitivity
        this[K.version] = SETTINGS_VERSION
        this[K.swipeLength] = s.swipeLength
        this[K.autoDisarmSec] = s.autoDisarmSec
        this[K.thumbDown] = s.thumbDownAction.name
        this[K.fist] = s.fistAction.name
        this[K.victory] = s.victoryAction.name
        this[K.thumbUp] = s.thumbUpAction.name
        this[K.glow] = s.glow.name
        this[K.showSkeleton] = s.showSkeleton
        this[K.showHud] = s.showHud
        this[K.requireBody] = s.requireBody
    }

    private companion object {
        const val SETTINGS_VERSION = 2
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
