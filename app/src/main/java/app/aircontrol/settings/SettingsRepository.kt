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
import app.aircontrol.gesture.AttentionCalibration
import app.aircontrol.gesture.Bind
import app.aircontrol.gesture.BindCodec
import app.aircontrol.gesture.Binds
import app.aircontrol.gesture.ClickMethod
import app.aircontrol.gesture.ClickStrictness
import app.aircontrol.gesture.EngineConfig
import app.aircontrol.gesture.P3
import app.aircontrol.gesture.PushCalibration
import app.aircontrol.gesture.SystemAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class SourceType { FRONT, BACK, NETWORK }
enum class Accelerator { AUTO, GPU, CPU }

/** The on-screen tracking view: off, a compact view of the hand, or the full diagnostics panel. */
enum class TrackingView(val label: String) { OFF("Off"), COMPACT("Compact"), DIAGNOSTICS("Diagnostics") }

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
    /** Gain around the centre of the hand-safe region: 1 = its edges are the screen edges. */
    val pointerGain: Float = 1f,
    val smoothing: Float = 0.5f,
    val clickMethod: ClickMethod = ClickMethod.BOTH,
    val clickStrictness: ClickStrictness = ClickStrictness.BALANCED,
    /** Longest thumb–index touch that is a click; longer becomes a hold (ms). */
    val tapMaxMs: Int = 300,
    /** Pause after a pose before taps, pushes and swipes count again (ms). */
    val poseCooldownMs: Int = 600,
    /** Calibrated comfortable palm direction (canonical normal), null = facing the camera. */
    val handReference: P3? = null,
    val angleTolerance: Float = 25f,
    /** Air plane fixed to the camera (5-point calibration); null = floating plane of [pushDepth]. */
    val pushCalibration: PushCalibration? = null,
    val pushDepth: Float = 0.12f,
    /** Cursor speed while pushed through the air plane (dragging). */
    val pushDragGain: Float = 0.35f,
    val swipesEnabled: Boolean = true,
    val swipeSensitivity: Float = 1f,
    val swipeLength: Float = 0.4f,
    val attentionGate: Boolean = false,
    val attention: AttentionCalibration? = null,
    val autoDisarmSec: Int = 20,
    val swapHands: Boolean = false,
    val binds: List<Bind> = Binds.defaults(),
    val glow: GlowPreset = GlowPreset.CORAL,
    val trackingView: TrackingView = TrackingView.COMPACT,
    val previewSize: PreviewSize = PreviewSize.MEDIUM,
    val previewImage: Boolean = false,
    val showLog: Boolean = true,
    val requireBody: Boolean = false,
) {
    fun applyTo(base: EngineConfig): EngineConfig = base.copy(
        binds = binds,
        pointerGain = pointerGain,
        smoothing = smoothing,
        clickMethod = clickMethod,
        clickStrictness = clickStrictness,
        tapMaxMs = tapMaxMs.toLong(),
        poseCooldownMs = poseCooldownMs.toLong(),
        handReference = handReference,
        angleToleranceDeg = angleTolerance,
        pushCalibration = pushCalibration,
        pushDepth = pushDepth,
        pushDragGain = pushDragGain,
        swipesEnabled = swipesEnabled,
        swipeSensitivity = swipeSensitivity,
        attentionGate = attentionGate && attention != null,
        attentionCalibration = attention,
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
        val version = intPreferencesKey("settings_version")
        val swipeLength = floatPreferencesKey("swipe_length")
        val autoDisarmSec = intPreferencesKey("auto_disarm_sec")
        val swapHands = booleanPreferencesKey("swap_hands")
        val binds = stringPreferencesKey("binds")
        val glow = stringPreferencesKey("glow")
        val showPreview = booleanPreferencesKey("show_preview")
        val trackingView = stringPreferencesKey("tracking_view")
        val clickMethod = stringPreferencesKey("click_method")
        val clickStrictness = stringPreferencesKey("click_strictness")
        val tapMaxMs = intPreferencesKey("tap_max_ms")
        val poseCooldownMs = intPreferencesKey("pose_cooldown_ms")
        val handReference = stringPreferencesKey("hand_reference")
        val angleTolerance = floatPreferencesKey("angle_tolerance")
        val pushCalibration = stringPreferencesKey("push_calibration")
        val pushDepth = floatPreferencesKey("push_depth")
        val pushDragGain = floatPreferencesKey("push_drag_gain")
        val swipesEnabled = booleanPreferencesKey("swipes_enabled")
        val attentionGate = booleanPreferencesKey("attention_gate")
        val attention = stringPreferencesKey("attention_cal")
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
            // v4 maps the hand-safe region (not the whole view) onto the screen: old gains would overshoot.
            pointerGain = if (version < 4) d.pointerGain else this[K.pointerGain] ?: d.pointerGain,
            smoothing = this[K.smoothing] ?: d.smoothing,
            // v5: the curl click was replaced by the finger tap; "CURL" falls back to the default.
            clickMethod = enumOr(this[K.clickMethod], d.clickMethod),
            clickStrictness = enumOr(this[K.clickStrictness], d.clickStrictness),
            tapMaxMs = this[K.tapMaxMs] ?: d.tapMaxMs,
            poseCooldownMs = this[K.poseCooldownMs] ?: d.poseCooldownMs,
            handReference = this[K.handReference]?.let(::decodeP3),
            angleTolerance = this[K.angleTolerance] ?: d.angleTolerance,
            pushCalibration = this[K.pushCalibration]?.let { PushCalibration.decode(it) },
            pushDepth = this[K.pushDepth] ?: d.pushDepth,
            pushDragGain = this[K.pushDragGain] ?: d.pushDragGain,
            swipesEnabled = this[K.swipesEnabled] ?: d.swipesEnabled,
            // v4 swipes are open-palm flicks measured in palm sizes: the old value means something else.
            swipeSensitivity = if (version < 4) d.swipeSensitivity else this[K.swipeSensitivity] ?: d.swipeSensitivity,
            swipeLength = this[K.swipeLength] ?: d.swipeLength,
            attentionGate = this[K.attentionGate] ?: d.attentionGate,
            attention = this[K.attention]?.let(::decodeAttention),
            autoDisarmSec = this[K.autoDisarmSec] ?: d.autoDisarmSec,
            swapHands = this[K.swapHands] ?: d.swapHands,
            binds = binds,
            glow = enumOr(this[K.glow], d.glow),
            trackingView = this[K.trackingView]?.let { enumOr(it, d.trackingView) }
                ?: if (this[K.showPreview] == true || this[K.legacyShowSkeleton] == true || this[K.legacyShowHud] == true) {
                    TrackingView.DIAGNOSTICS
                } else {
                    d.trackingView
                },
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
        this[K.version] = SETTINGS_VERSION
        this[K.swipeLength] = s.swipeLength
        this[K.autoDisarmSec] = s.autoDisarmSec
        this[K.swapHands] = s.swapHands
        this[K.binds] = BindCodec.encode(s.binds)
        this[K.glow] = s.glow.name
        this[K.trackingView] = s.trackingView.name
        this[K.clickMethod] = s.clickMethod.name
        this[K.clickStrictness] = s.clickStrictness.name
        this[K.tapMaxMs] = s.tapMaxMs
        this[K.poseCooldownMs] = s.poseCooldownMs
        val hr = s.handReference
        if (hr != null) this[K.handReference] = "${hr.x};${hr.y};${hr.z}" else remove(K.handReference)
        this[K.angleTolerance] = s.angleTolerance
        val pc = s.pushCalibration
        if (pc != null) this[K.pushCalibration] = pc.encode() else remove(K.pushCalibration)
        this[K.pushDepth] = s.pushDepth
        this[K.pushDragGain] = s.pushDragGain
        this[K.swipesEnabled] = s.swipesEnabled
        this[K.attentionGate] = s.attentionGate
        val att = s.attention
        if (att != null) this[K.attention] = "${att.yaw};${att.pitch};${att.spread}" else remove(K.attention)
        this[K.previewSize] = s.previewSize.name
        this[K.previewImage] = s.previewImage
        this[K.showLog] = s.showLog
        this[K.requireBody] = s.requireBody
    }

    private companion object {
        const val SETTINGS_VERSION = 5
    }

    private fun decodeP3(s: String): P3? {
        val p = s.split(";").mapNotNull { it.toFloatOrNull() }
        return if (p.size == 3) P3(p[0], p[1], p[2]) else null
    }

    private fun decodeAttention(s: String): AttentionCalibration? {
        val p = s.split(";").mapNotNull { it.toFloatOrNull() }
        return if (p.size == 3) AttentionCalibration(p[0], p[1], p[2]) else null
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
