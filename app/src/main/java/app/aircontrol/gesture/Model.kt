package app.aircontrol.gesture

import kotlin.math.sqrt

/** A 3D point. Image landmarks use normalized coordinates; engine-internal points are aspect-corrected. */
data class P3(val x: Float, val y: Float, val z: Float = 0f) {
    operator fun plus(o: P3) = P3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: P3) = P3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = P3(x * s, y * s, z * s)
    fun dist(o: P3): Float {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    fun dist2d(o: P3): Float {
        val dx = x - o.x; val dy = y - o.y
        return sqrt(dx * dx + dy * dy)
    }
}

fun mid(a: P3, b: P3) = P3((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.z + b.z) / 2f)
fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun lerp(a: P3, b: P3, t: Float) = P3(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t))

/** Which of the user's hands. [ANY] doubles as "unknown" on an observation and "either" in a bind. */
enum class HandSide { ANY, LEFT, RIGHT }

/**
 * One tracked hand. [landmarks] are the 21 MediaPipe hand landmarks in normalized image
 * coordinates (x, y in 0..1, z relative depth on roughly the same scale as x), expressed in the
 * *user view*: already mirrored when the camera faces the user, so +x is the user's right.
 */
data class HandObservation(
    val landmarks: List<P3>,
    val gesture: String,
    val gestureScore: Float,
    /** MediaPipe hand world landmarks (metres, hand-centred, x mirrored like [landmarks]), if available. */
    val world: List<P3>? = null,
    /** The user's left/right hand, [HandSide.ANY] when unknown. */
    val side: HandSide = HandSide.ANY,
)

/** The 33 MediaPipe pose landmarks (same coordinate convention as [HandObservation]). */
data class PoseObservation(
    val landmarks: List<P3>,
    val visibility: List<Float>,
)

data class FrameObservation(
    val timestampMs: Long,
    /** Width / height of the analysed image. */
    val imageAspect: Float,
    val hands: List<HandObservation>,
    val pose: PoseObservation?,
    /** Head and eye direction, when the face model runs (attention gate). */
    val face: FaceObservation? = null,
)

/** MediaPipe canned gesture names. */
object Signs {
    const val NONE = "None"
    const val I_LOVE_YOU = "ILoveYou"
    const val THUMB_DOWN = "Thumb_Down"
    const val THUMB_UP = "Thumb_Up"
    const val CLOSED_FIST = "Closed_Fist"
    const val VICTORY = "Victory"
    const val OPEN_PALM = "Open_Palm"
    const val POINTING_UP = "Pointing_Up"
}

/** What a bind can do. Executed by the accessibility service (`ActionRunner`). */
enum class SystemAction(val label: String) {
    NONE("Nothing"),
    BACK("Back"),
    HOME("Home"),
    RECENTS("Recents"),
    NOTIFICATIONS("Notifications"),
    QUICK_SETTINGS("Quick settings"),
    LOCK_SCREEN("Lock screen"),
    SCREENSHOT("Screenshot"),
    POWER_MENU("Power menu"),
    MEDIA_PLAY_PAUSE("Play / pause"),
    MEDIA_NEXT("Next track"),
    MEDIA_PREVIOUS("Previous track"),
    VOLUME_UP("Volume up"),
    VOLUME_DOWN("Volume down"),
    MUTE("Mute"),
    LAUNCH_APP("Open an app"),
}

enum class SwipeDir {
    UP, DOWN, LEFT, RIGHT;

    val opposite: SwipeDir
        get() = when (this) { UP -> DOWN; DOWN -> UP; LEFT -> RIGHT; RIGHT -> LEFT }
}

enum class DisarmReason { GESTURE, TIMEOUT, MANUAL }

/** Which click gestures are active. */
enum class ClickMethod(val label: String) { TAP("Finger tap"), PUSH("Air push"), BOTH("Both") }

/** How sure the engine must be before a tap or push counts. */
enum class ClickStrictness(val label: String, val threshold: Float) {
    RELAXED("Relaxed", 0.35f), BALANCED("Balanced", 0.5f), STRICT("Strict", 0.65f)
}

/** What is touching the screen: thumb and index together, or a push through the air plane. */
enum class TouchSource { TAP, PUSH }

/** HAND = the comfortable palm angle, PUSH = the air plane at 5 screen points, ATTENTION = gaze on the screen. */
enum class CalibrationKind { HAND, PUSH, ATTENTION }

/** Events produced by [GestureEngine]. Coordinates are normalized to the screen (0..1). */
sealed interface ControlEvent {
    /** Progress of holding the arm/disarm sign; 0 means the hold was abandoned. */
    data class ArmProgress(val progress: Float, val disarming: Boolean) : ControlEvent
    data object Armed : ControlEvent
    data class Disarmed(val reason: DisarmReason) : ControlEvent
    /**
     * The cursor.
     * - [closing]: how far thumb and index have closed toward a tap (0..1).
     * - [push]: air-plane proximity (1 = the plane).
     * - [touchSource], [holdMs], [dragging]: a touch in progress (dragging once it moved or became a long press).
     * - [edge]: bitmask of the hand-safe region edges the palm presses on (1 left, 2 top, 4 right, 8 bottom).
     * - [confidence]: how much the pointer can be trusted (hand angle, steadiness, gaze), 0..1.
     * - [angleWarn]: 0 = the palm faces the camera as calibrated, 1 = far off (shown as a warm shade).
     * - [looking]: the eyes are on the screen (null = unknown: no gaze calibration or no face).
     * - [cooldown]: actions are paused right after a pose (bind / arm sign).
     */
    data class Pointer(
        val x: Float,
        val y: Float,
        val visible: Boolean,
        val touching: Boolean,
        val closing: Float = 0f,
        val push: Float = 0f,
        val touchSource: TouchSource? = null,
        val holdMs: Long = 0L,
        val dragging: Boolean = false,
        val edge: Int = 0,
        val confidence: Float = 1f,
        val angleWarn: Float = 0f,
        val looking: Boolean? = null,
        val cooldown: Boolean = false,
    ) : ControlEvent
    data class TouchDown(val x: Float, val y: Float) : ControlEvent
    data class TouchMove(val x: Float, val y: Float) : ControlEvent
    data class TouchUp(val x: Float, val y: Float) : ControlEvent
    /** A tap; [click] = a thumb–index tap (otherwise a quick air push). */
    data class Tap(val x: Float, val y: Float, val click: Boolean = false) : ControlEvent
    data class Swipe(val dir: SwipeDir) : ControlEvent
    /** Progress of holding a bind's gesture; 0 means abandoned. */
    data class BindProgress(val label: String, val progress: Float) : ControlEvent
    /** A bind fired: perform [action] ([appPackage] for [SystemAction.LAUNCH_APP]). */
    data class BindFired(val action: SystemAction, val appPackage: String?, val label: String) : ControlEvent
    /** A tap or push was not accepted; for the preview / tuning only. */
    data class ClickRejected(val reason: String) : ControlEvent
    /**
     * A calibration step: [message] tells the user what to do, [progress] 0..1 within the step,
     * ([targetX], [targetY]) where to point (normalized screen, -1 = none).
     * When [done], [result] holds the measured values or is null if it failed.
     */
    data class Calibration(
        val kind: CalibrationKind,
        val step: Int,
        val progress: Float,
        val message: String,
        val done: Boolean = false,
        val result: FloatArray? = null,
        val targetX: Float = -1f,
        val targetY: Float = -1f,
    ) : ControlEvent
}

data class EngineConfig(
    val screenW: Int = 1080,
    val screenH: Int = 2400,
    val density: Float = 2.6f,

    val armSign: String = Signs.I_LOVE_YOU,
    val armHoldMs: Long = 1000,
    val disarmHoldMs: Long = 1000,
    val signScoreMin: Float = 0.55f,
    /** Classification may flicker; gaps shorter than this don't break a hold. */
    val signGapMs: Long = 200,
    val binds: List<Bind> = Binds.defaults(),
    /** After a bound pose or the arm sign, taps, pushes and swipes wait this long (the hand is changing shape). */
    val poseCooldownMs: Long = 600,

    val clickMethod: ClickMethod = ClickMethod.BOTH,
    val clickStrictness: ClickStrictness = ClickStrictness.BALANCED,

    // Finger tap: thumb and index tips touch. Quick = click, held = touch down (drag / long press).
    /** Thumb-tip/index-tip distance over palm size: below enter = touching, above exit = apart. */
    val tapEnter: Float = 0.30f,
    val tapExit: Float = 0.45f,
    /** Rejects fists: index tip must reach this far from the wrist (relative to the index knuckle). */
    val tapMinIndexReach: Float = 1.15f,
    /** Touching longer than this (or moving) turns the tap into a held touch. */
    val tapMaxMs: Long = 300,
    val dragSlopDp: Float = 22f,
    /** The touch lands where the pointer was this long before the fingers met (closing drift). */
    val touchLookbackMs: Long = 80,
    /** Before the fingers meet the palm should be this still (palm sizes / s). */
    val tapStillSpeed: Float = 1.5f,

    // Hand angle: the palm should face the camera as in the hand calibration.
    /** Unit palm normal of the comfortable pose (canonical form, see [HandFeatures.canonicalNormal]); null = facing the camera. */
    val handReference: P3? = null,
    /** Within this angle from the reference the hand is fine; beyond it the warning and the confidence drop grow. */
    val angleToleranceDeg: Float = 25f,
    /** Beyond tolerance + this, taps and pushes are blocked. */
    val angleBlockExtraDeg: Float = 25f,

    // Air push: a virtual plane at a fixed distance from the camera (calibrated at 5 points);
    // without a calibration it floats a fixed push in front of the resting hand.
    val pushCalibration: PushCalibration? = null,
    /** Uncalibrated plane: ln(hand scale) growth from rest to the plane (0.12 ≈ ~10 cm at 1 m). */
    val pushDepth: Float = 0.12f,
    /** Uncalibrated plane: how fast the rest position follows the hand. */
    val pushRestTauMs: Long = 1500,
    /** Proximity (1 = plane) at which the touch starts / ends. */
    val pushEnter: Float = 1f,
    val pushExit: Float = 0.8f,
    /** From this proximity the cursor slows down so pushing does not drag the aim. */
    val pushMagnetFrom: Float = 0.4f,
    /** Cursor gain left at the plane. */
    val pushMagnetGain: Float = 0.3f,
    /** Cursor gain while pushed through (dragging precisely with an outstretched arm is hard). */
    val pushDragGain: Float = 0.35f,
    /** An air push is slower than a tap: in and out within this is still a tap. */
    val pushTapMaxMs: Long = 450,

    // Swipes: hold an open palm still for a moment, then flick it.
    val swipesEnabled: Boolean = true,
    /** Palm speed (palm sizes / s) below which the open hand counts as still. */
    val flickStillSpeed: Float = 0.8f,
    val flickStillMs: Long = 120,
    /** The flick must cover [flickMinPalms] palm sizes within this window after leaving still. */
    val flickWindowMs: Long = 350,
    val flickMinPalms: Float = 2.0f,
    /** Peak palm speed (palm sizes / s) a flick must reach. */
    val flickMinPeakSpeed: Float = 6f,
    val flickAxisRatio: Float = 1.6f,
    val swipeCooldownMs: Long = 450,
    /** The return stroke (opposite direction) is ignored for this long. */
    val swipeOppositeBlockMs: Long = 700,
    val swipeSensitivity: Float = 1f,

    // Attention: gaze on the screen (needs a calibration). Raises pointer confidence; can gate clicks.
    val attentionGate: Boolean = false,
    val attentionCalibration: AttentionCalibration? = null,
    /** Below this attention score a click is blocked (when the gate is on). */
    val attentionBlockBelow: Float = 0.3f,
    /** At or above this the eyes count as on the screen (pointer feedback). */
    val attentionLooking: Float = 0.6f,

    /** Hand-safe region margins (palm sizes): the cursor reaches the screen edges with the whole hand in view. */
    val safeTop: Float = 1.7f,
    val safeBottom: Float = 0.7f,
    val safeSide: Float = 0.65f,
    /** Gain around the centre of the hand-safe region; 1 = its edges are the screen edges. */
    val pointerGain: Float = 1f,
    /** 0 = raw/fast, 1 = very smooth. */
    val smoothing: Float = 0.5f,

    val autoDisarmMs: Long = 20_000,
    val handLostGraceMs: Long = 350,
    val requireBody: Boolean = false,
) {
    val dragSlopPx: Float get() = dragSlopDp * density
    val tapClicks: Boolean get() = clickMethod != ClickMethod.PUSH
    val pushClicks: Boolean get() = clickMethod != ClickMethod.TAP
}

/** Result of the attention calibration: where the user looks when looking at the screen (degrees). */
data class AttentionCalibration(val yaw: Float, val pitch: Float, val spread: Float)

/** One calibrated air-plane point: at screen position ([u], [v]) the hand hovers at depth [hover] and touches at [plane] (ln scale). */
data class PushPoint(val u: Float, val v: Float, val hover: Float, val plane: Float)

/** The air plane measured at several screen points, interpolated in between (inverse-distance weighting). */
data class PushCalibration(val points: List<PushPoint>) {
    /** Hover and plane depth at screen position ([u], [v]). */
    fun at(u: Float, v: Float): Pair<Float, Float> {
        var wSum = 0f; var h = 0f; var p = 0f
        for (pt in points) {
            val d2 = (pt.u - u) * (pt.u - u) + (pt.v - v) * (pt.v - v)
            if (d2 < 1e-6f) return pt.hover to pt.plane
            val w = 1f / d2
            wSum += w; h += w * pt.hover; p += w * pt.plane
        }
        return if (wSum == 0f) 0f to 0f else (h / wSum) to (p / wSum)
    }

    fun encode(): String = points.joinToString(";") { "${it.u},${it.v},${it.hover},${it.plane}" }

    companion object {
        /** Where the 5-point calibration asks the user to point: centre, then the four corners. */
        val TARGETS = listOf(0.5f to 0.5f, 0.15f to 0.15f, 0.85f to 0.15f, 0.85f to 0.85f, 0.15f to 0.85f)

        fun decode(s: String): PushCalibration? {
            val pts = s.split(";").mapNotNull { part ->
                val f = part.split(",").mapNotNull { it.toFloatOrNull() }
                if (f.size == 4) PushPoint(f[0], f[1], f[2], f[3]) else null
            }
            return if (pts.size >= 1) PushCalibration(pts) else null
        }

        fun fromResult(r: FloatArray): PushCalibration =
            PushCalibration(List(r.size / 4) { PushPoint(r[4 * it], r[4 * it + 1], r[4 * it + 2], r[4 * it + 3]) })
    }
}

/** One immutable snapshot of what the engine currently sees and decides, for the live preview. */
data class EngineDebug(
    val t: Long = 0,
    val armed: Boolean = false,
    val hands: Int = 0,
    val side: HandSide = HandSide.ANY,
    /** Raw MediaPipe sign of the active hand. */
    val sign: String = Signs.NONE,
    val pose: Pose? = null,
    /** Cursor reference (palm centre) in user-view camera coordinates, 0..1. */
    val palmU: Float = 0.5f,
    val palmV: Float = 0.5f,
    /** Where the pointer is on the screen, 0..1. */
    val cursorU: Float = 0.5f,
    val cursorV: Float = 0.5f,
    val gain: Float = 1f,
    /** Thumb–index gap over palm size, and the thresholds. */
    val gap: Float = 0f,
    val tapEnter: Float = 0f,
    val tapExit: Float = 0f,
    /** IDLE / PENDING / DRAGGING. */
    val touch: String = "IDLE",
    /** Time since the last finger-tap click (also while disarmed), [Long.MAX_VALUE] = never. */
    val clickAgoMs: Long = Long.MAX_VALUE,
    /** Confidence of the last tap/push candidate and its factors ([TapGate.FACTOR_NAMES], -1 = not used). */
    val clickConfidence: Float = 0f,
    val clickFactors: FloatArray = FloatArray(TapGate.FACTOR_NAMES.size),
    /** Pointer confidence 0..1. */
    val pointerConfidence: Float = 1f,
    /** Palm angle away from the calibrated pose (degrees) and the tolerance. */
    val angleDeg: Float = 0f,
    val angleTolDeg: Float = 25f,
    /** Air-plane proximity (1 = plane); whether the plane is calibrated (fixed to the camera). */
    val push: Float = 0f,
    val pushCalibrated: Boolean = false,
    /** Open palm held still: a flick would be recognised. */
    val flickReady: Boolean = false,
    /** Palm speed in palm sizes per second. */
    val palmSpeed: Float = 0f,
    /** Attention score 0..1, or -1 without a calibration / face. */
    val attention: Float = -1f,
    /** Actions paused after a pose. */
    val cooldown: Boolean = false,
    /** Hand-safe region in user-view camera coordinates (0..1): left, top, right, bottom. */
    val region: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
    /** Region edges the palm is pressing on (bitmask, see [ControlEvent.Pointer.edge]). */
    val edge: Int = 0,
    val touchSource: TouchSource? = null,
    val bindLabel: String? = null,
    val bindProgress: Float = 0f,
    val bodyVisible: Boolean = false,
)
