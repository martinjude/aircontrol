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

/** Axis of an armed swipe: HORIZONTAL = left/right (vertical blade hand), VERTICAL = up/down. */
enum class SwipeAxis { HORIZONTAL, VERTICAL }

/** Orientation of a flat hand seen edge-on: VERTICAL = palm faces sideways, HORIZONTAL = palm faces floor/ceiling. */
enum class BladeAxis { VERTICAL, HORIZONTAL }

enum class DisarmReason { GESTURE, TIMEOUT, MANUAL }

/** Events produced by [GestureEngine]. Coordinates are normalized to the screen (0..1). */
sealed interface ControlEvent {
    /** Progress of holding the arm/disarm sign; 0 means the hold was abandoned. */
    data class ArmProgress(val progress: Float, val disarming: Boolean) : ControlEvent
    data object Armed : ControlEvent
    data class Disarmed(val reason: DisarmReason) : ControlEvent
    /** [press] is the click progress (0..1) while the index finger is curling. */
    data class Pointer(
        val x: Float,
        val y: Float,
        val visible: Boolean,
        val touching: Boolean,
        val press: Float = 0f,
    ) : ControlEvent
    data class TouchDown(val x: Float, val y: Float) : ControlEvent
    data class TouchMove(val x: Float, val y: Float) : ControlEvent
    data class TouchUp(val x: Float, val y: Float) : ControlEvent
    /** A tap; [click] = it came from the index-curl click (otherwise from a quick pinch). */
    data class Tap(val x: Float, val y: Float, val click: Boolean = false) : ControlEvent
    data class Swipe(val dir: SwipeDir) : ControlEvent
    /**
     * Swipe mode: [axis] non-null = armed for strokes along it. With a null axis, [progress] > 0 is
     * the flat-hand hold building up and 0 means swipe mode is off.
     */
    data class SwipeMode(val axis: SwipeAxis?, val progress: Float = if (axis != null) 1f else 0f) : ControlEvent
    /** Progress of holding a bind's gesture; 0 means abandoned. */
    data class BindProgress(val label: String, val progress: Float) : ControlEvent
    /** A bind fired: perform [action] ([appPackage] for [SystemAction.LAUNCH_APP]). */
    data class BindFired(val action: SystemAction, val appPackage: String?, val label: String) : ControlEvent
    /** The click detector rejected a curl; for the preview / tuning only. */
    data class ClickRejected(val reason: String) : ControlEvent
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

    /** Thumb-tip/index-tip distance over palm size: below enter = pinched, above exit = released. */
    val pinchEnter: Float = 0.30f,
    val pinchExit: Float = 0.45f,
    /** Rejects fists: index tip must reach this far from the wrist (relative to the index knuckle). */
    val pinchMinIndexReach: Float = 1.15f,
    /** A pinch only starts with the hand upright: wrist→knuckle within this angle of straight up. */
    val pinchMaxTiltDeg: Float = 45f,
    /** Once pinched, the hand may tilt this far before the touch is released. */
    val pinchHoldTiltDeg: Float = 75f,
    /** 2D/3D length of the palm axis; low when the hand points at the camera. */
    val pinchMinUpright: Float = 0.5f,
    val tapMaxMs: Long = 280,
    val dragSlopDp: Float = 22f,
    /** Touch-down uses the pointer position from this long before the pinch closed (closing drift). */
    val touchLookbackMs: Long = 80,

    // Click: the index finger curls and straightens again (fires on the release).
    /** Reach (≈2 extended, ≈1 curled) must fall at least this far below its recent maximum. */
    val clickMinDrop: Float = 0.30f,
    /** The dip starts being tracked after this fall. */
    val clickOnsetDrop: Float = 0.12f,
    /** Fraction of the dip that must be regained for the release. */
    val clickRecovery: Float = 0.6f,
    val clickMinDipMs: Long = 80,
    /** Time from the start of the curl to the release; slower = a pose change, not a click. */
    val clickMaxMs: Long = 450,
    /** The finger must have been at least this extended just before (a real pointing finger). */
    val clickMinBaseline: Float = 1.5f,
    /** Palm travel during the curl, in palm sizes, above which it is not a click. */
    val clickMaxLateral: Float = 0.6f,
    /** The click lands where the pointer was this long before the curl began. */
    val clickLookbackMs: Long = 60,
    val clickRefractoryMs: Long = 300,
    val clickSensitivity: Float = 1f,

    // Armed swipes: hold a flat hand edge-on, then stroke.
    /** |palm normal component| needed to count as a vertical/horizontal blade. */
    val bladeMinAxis: Float = 0.75f,
    val swipeArmHoldMs: Long = 300,
    /** Palm travel allowed while holding the blade to arm (palm sizes). */
    val swipeArmMaxDrift: Float = 0.5f,
    /** Swipe mode ends after the blade has been gone this long. */
    val swipeModeExitMs: Long = 300,
    val flickWindowMs: Long = 220,
    /** Minimum palm travel within [flickWindowMs], in shoulder widths. */
    val flickMinShoulders: Float = 0.45f,
    val flickAxisRatio: Float = 1.5f,
    val swipeCooldownMs: Long = 450,
    val swipeOppositeBlockMs: Long = 900,
    val swipeSensitivity: Float = 0.7f,

    /** Camera frame → screen gain around the centre; 1 = camera edges are the screen edges. */
    val pointerGain: Float = 1f,
    /** 0 = raw/fast, 1 = very smooth. */
    val smoothing: Float = 0.5f,

    val autoDisarmMs: Long = 20_000,
    val handLostGraceMs: Long = 350,
    val requireBody: Boolean = false,
) {
    val dragSlopPx: Float get() = dragSlopDp * density
    val clickNeeded: Float get() = clickMinDrop / clickSensitivity.coerceIn(0.25f, 4f)
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
    val pinch: Float = 0f,
    val pinchEnter: Float = 0f,
    val pinchExit: Float = 0f,
    /** The hand is in a pose from which a pinch would start (upright, index extended). */
    val pinchReady: Boolean = false,
    /** IDLE / PENDING / DRAGGING. */
    val touch: String = "IDLE",
    val tiltDeg: Float = 0f,
    val upright: Boolean = false,
    /** Index-finger reach (≈2 extended, ≈1 curled) and the recent maximum it is compared with. */
    val reach: Float = 0f,
    val reachBaseline: Float = 0f,
    val curlNeeded: Float = 0f,
    val curlDepth: Float = 0f,
    val clickState: String = "IDLE",
    /** Time since the last click was detected (also while disarmed), [Long.MAX_VALUE] = never. */
    val clickAgoMs: Long = Long.MAX_VALUE,
    val blade: String = "-",
    val swipeAxis: SwipeAxis? = null,
    val bindLabel: String? = null,
    val bindProgress: Float = 0f,
    val bodyVisible: Boolean = false,
)
