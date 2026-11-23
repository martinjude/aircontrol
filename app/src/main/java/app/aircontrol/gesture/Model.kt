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

/** [RECENTER] is handled inside the engine (makes the next pointing position the screen centre). */
enum class SystemAction { NONE, BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, RECENTER }

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

enum class AnchorMode { BODY, HAND }

/** Events produced by [GestureEngine]. Coordinates are normalized to the screen (0..1). */
sealed interface ControlEvent {
    /** Progress of holding the arm/disarm sign; 0 means the hold was abandoned. */
    data class ArmProgress(val progress: Float, val disarming: Boolean) : ControlEvent
    data object Armed : ControlEvent
    data class Disarmed(val reason: DisarmReason) : ControlEvent
    /** [press] is the push-to-click progress (0..1) while a push is in progress; the pointer is frozen then. */
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
    data class Tap(val x: Float, val y: Float) : ControlEvent
    data class Swipe(val dir: SwipeDir) : ControlEvent
    /**
     * Swipe mode: [axis] non-null = armed for strokes along it. With a null axis, [progress] > 0 is
     * the flat-hand hold building up and 0 means swipe mode is off.
     */
    data class SwipeMode(val axis: SwipeAxis?, val progress: Float = if (axis != null) 1f else 0f) : ControlEvent
    /** Recenter sign fired; the next steady pointing position becomes the screen centre. */
    data object RecenterPending : ControlEvent
    data object Recentered : ControlEvent
    /** Progress of holding a system sign; 0 means abandoned. */
    data class SignProgress(val action: SystemAction, val progress: Float) : ControlEvent
    data class Global(val action: SystemAction) : ControlEvent
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
    val signActions: Map<String, SystemAction> = mapOf(
        Signs.THUMB_DOWN to SystemAction.BACK,
        Signs.CLOSED_FIST to SystemAction.HOME,
        Signs.VICTORY to SystemAction.RECENTS,
        Signs.THUMB_UP to SystemAction.RECENTER,
    ),
    val signHoldMs: Map<String, Long> = mapOf(
        Signs.THUMB_DOWN to 500,
        Signs.CLOSED_FIST to 800,
        Signs.VICTORY to 600,
        Signs.THUMB_UP to 600,
    ),

    /** Thumb-tip/index-tip distance over palm size: below enter = pinched, above exit = released. */
    val pinchEnter: Float = 0.30f,
    val pinchExit: Float = 0.45f,
    /** Rejects fists: index tip must reach this far from the wrist (relative to the index knuckle). */
    val pinchMinIndexReach: Float = 1.15f,
    val tapMaxMs: Long = 280,
    val dragSlopDp: Float = 22f,
    /** Touch-down uses the pointer position from this long before the pinch closed (closing drift). */
    val touchLookbackMs: Long = 140,

    // Push-to-click: a quick jab of the pointing hand towards the camera (hand grows in the image).
    /** ln(hand scale) slope (1/s) that starts a press and freezes the pointer. */
    val pushOnsetRate: Float = 0.5f,
    /** Peak slope the press must reach: a slow lean-in is not a click. */
    val pushMinPeakRate: Float = 0.8f,
    /** Growth of ln(hand scale) since the press began that clicks (0.10 ≈ hand 10 % closer). */
    val pushMinGrowth: Float = 0.10f,
    val pushMaxMs: Long = 320,
    /** Palm travel in the image plane during a press, in palm sizes, above which it is not a click. */
    val pushMaxLateral: Float = 0.8f,
    /** The click lands where the pointer was this long before the press began (drift compensation). */
    val pushLookbackMs: Long = 60,
    val pushRefractoryMs: Long = 350,
    /** After a click the pointer stays frozen while the hand is pulled back, at most this long. */
    val pushRecoverMaxMs: Long = 450,
    val pushSensitivity: Float = 1f,

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
    val swipeSensitivity: Float = 1f,

    val recenterTimeoutMs: Long = 3000,
    val recenterSteadyMs: Long = 150,

    val pointerSensitivity: Float = 1f,
    /** 0 = raw/fast, 1 = very smooth. */
    val smoothing: Float = 0.5f,
    val anchorMode: AnchorMode = AnchorMode.BODY,

    val autoDisarmMs: Long = 20_000,
    val handLostGraceMs: Long = 350,
    val requireBody: Boolean = false,
) {
    val dragSlopPx: Float get() = dragSlopDp * density
    val pushGrowth: Float get() = pushMinGrowth / pushSensitivity.coerceIn(0.25f, 4f)
    fun holdFor(sign: String): Long = signHoldMs[sign] ?: 600
    fun actionFor(sign: String): SystemAction = signActions[sign] ?: SystemAction.NONE
}
