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
import app.aircontrol.gesture.Bind
import app.aircontrol.gesture.BindCodec
import app.aircontrol.gesture.Binds
import app.aircontrol.gesture.EngineConfig
import app.aircontrol.gesture.SystemAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class SourceType { FRONT, BACK, NETWORK }
enum class Accelerator { AUTO, GPU, CPU }

/** Size of the on-screen tracking preview (width in dp). */
enum class PreviewSize(val label: String, val widthDp: Float) {
    SMALL("Small", 130f), MEDIUM("Medium", 190f), LARGE("Large", 260f)
}

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
    /** Camera → screen gain: 1 = the camera edges are the screen edges. */
    val pointerGain: Float = 1f,
    val smoothing: Float = 0.5f,
    val clickSensitivity: Float = 1f,
    /** How far from upright (degrees) the hand may be to start a pinch. */
    val pinchTiltDeg: Float = 45f,
    val swipeSensitivity: Float = 0.7f,
    val swipeLength: Float = 0.4f,
    val autoDisarmSec: Int = 20,
    val swapHands: Boolean = false,
    val binds: List<Bind> = Binds.defaults(),
    val glow: GlowPreset = GlowPreset.CORAL,
    val showPreview: Boolean = false,
    val previewSize: PreviewSize = PreviewSize.MEDIUM,
    val previewImage: Boolean = false,
    val showLog: Boolean = true,
    val requireBody: Boolean = false,
) {
    fun applyTo(base: EngineConfig): EngineConfig = base.copy(
        binds = binds,
        pointerGain = pointerGain,
        smoothing = smoothing,
        swipeSensitivity = swipeSensitivity,
        clickSensitivity = clickSensitivity,
        pinchMaxTiltDeg = pinchTiltDeg,
        pinchHoldTiltDeg = pinchTiltDeg + 30f,
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
        val pointerGain = floatPreferencesKey("pointer_gain")
        val smoothing = floatPreferencesKey("smoothing")
        val swipeSensitivity = floatPreferencesKey("swipe_sensitivity")
        val clickSensitivity = floatPreferencesKey("click_sensitivity")
        val pinchTilt = floatPreferencesKey("pinch_tilt")
        val version = intPreferencesKey("settings_version")
        val swipeLength = floatPreferencesKey("swipe_length")
        val autoDisarmSec = intPreferencesKey("auto_disarm_sec")
        val swapHands = booleanPreferencesKey("swap_hands")
        val binds = stringPreferencesKey("binds")
        val glow = stringPreferencesKey("glow")
        val showPreview = booleanPreferencesKey("show_preview")
        val previewSize = stringPreferencesKey("preview_size")
        val previewImage = booleanPreferencesKey("preview_image")
        val showLog = booleanPreferencesKey("show_log")
        val requireBody = booleanPreferencesKey("require_body")

        // v2 keys, only read to migrate.
        val legacyThumbDown = stringPreferencesKey("sign_thumb_down")
        val legacyFist = stringPreferencesKey("sign_fist")
        val legacyVictory = stringPreferencesKey("sign_victory")
        val legacyThumbUp = stringPreferencesKey("sign_thumb_up")
        val legacyShowSkeleton = booleanPreferencesKey("show_skeleton")
        val legacyShowHud = booleanPreferencesKey("show_hud")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        val version = this[K.version] ?: 1
        // v2 persisted its old tuning defaults; v3 has new defaults and a new meaning for the swipe/click values.
        val migrated = version < SETTINGS_VERSION
        val binds = this[K.binds]?.let { BindCodec.decode(it) } ?: Binds.legacy(
            thumbDown = enumOr(this[K.legacyThumbDown], SystemAction.BACK),
            fist = enumOr(this[K.legacyFist], SystemAction.HOME),
            victory = enumOr(this[K.legacyVictory], SystemAction.RECENTS),
            // v2 used 👍 for recenter, which no longer exists (enumOr then falls back to nothing).
            thumbUp = enumOr(this[K.legacyThumbUp], SystemAction.NONE),
        )
        return AppSettings(
            source = enumOr(this[K.source], d.source),
            networkUrl = this[K.networkUrl] ?: d.networkUrl,
            mirror = this[K.mirror] ?: d.mirror,
            networkRotation = this[K.networkRotation] ?: d.networkRotation,
            accelerator = enumOr(this[K.accelerator], d.accelerator),
            usePose = this[K.usePose] ?: d.usePose,
            pointerGain = this[K.pointerGain] ?: d.pointerGain,
            smoothing = this[K.smoothing] ?: d.smoothing,
            clickSensitivity = this[K.clickSensitivity] ?: d.clickSensitivity,
            pinchTiltDeg = this[K.pinchTilt] ?: d.pinchTiltDeg,
            swipeSensitivity = if (migrated) d.swipeSensitivity else this[K.swipeSensitivity] ?: d.swipeSensitivity,
            swipeLength = this[K.swipeLength] ?: d.swipeLength,
            autoDisarmSec = this[K.autoDisarmSec] ?: d.autoDisarmSec,
            swapHands = this[K.swapHands] ?: d.swapHands,
            binds = binds,
            glow = enumOr(this[K.glow], d.glow),
            showPreview = this[K.showPreview] ?: ((this[K.legacyShowSkeleton] ?: false) || (this[K.legacyShowHud] ?: false)),
            previewSize = enumOr(this[K.previewSize], d.previewSize),
            previewImage = this[K.previewImage] ?: d.previewImage,
            showLog = this[K.showLog] ?: d.showLog,
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
        this[K.pointerGain] = s.pointerGain
        this[K.smoothing] = s.smoothing
        this[K.swipeSensitivity] = s.swipeSensitivity
        this[K.clickSensitivity] = s.clickSensitivity
        this[K.pinchTilt] = s.pinchTiltDeg
        this[K.version] = SETTINGS_VERSION
        this[K.swipeLength] = s.swipeLength
        this[K.autoDisarmSec] = s.autoDisarmSec
        this[K.swapHands] = s.swapHands
        this[K.binds] = BindCodec.encode(s.binds)
        this[K.glow] = s.glow.name
        this[K.showPreview] = s.showPreview
        this[K.previewSize] = s.previewSize.name
        this[K.previewImage] = s.previewImage
        this[K.showLog] = s.showLog
        this[K.requireBody] = s.requireBody
    }

    private companion object {
        const val SETTINGS_VERSION = 3
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
