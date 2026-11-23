package app.aircontrol.gesture

import kotlin.math.abs
import kotlin.math.sqrt

/** MediaPipe hand landmark indices. */
object HandLm {
    const val WRIST = 0
    const val THUMB_IP = 3
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_TIP = 20

    /** Bone connections, for drawing skeletons. */
    val CONNECTIONS = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20,
    )
}

/** MediaPipe pose landmark indices used by the app. */
object PoseLm {
    const val NOSE = 0
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16

    val UPPER_BODY = listOf(
        LEFT_SHOULDER to RIGHT_SHOULDER,
        LEFT_SHOULDER to LEFT_ELBOW, LEFT_ELBOW to LEFT_WRIST,
        RIGHT_SHOULDER to RIGHT_ELBOW, RIGHT_ELBOW to RIGHT_WRIST,
    )
}

/**
 * Geometric features of one hand. Points are converted to an aspect-corrected space
 * (x and z scaled by image width/height) so distances are in "image heights" on both axes.
 */
class HandFeatures private constructor(val pts: List<P3>, world: List<P3>?) {
    val palmSize: Float = pts[HandLm.WRIST].dist(pts[HandLm.MIDDLE_MCP]).coerceAtLeast(1e-4f)

    val palmCenter: P3 = run {
        var x = 0f; var y = 0f; var z = 0f
        for (i in PALM) { x += pts[i].x; y += pts[i].y; z += pts[i].z }
        P3(x / PALM.size, y / PALM.size, z / PALM.size)
    }

    /** Midpoint of thumb and index tips (where a pinch lands). */
    val pinchPoint: P3 = mid(pts[HandLm.THUMB_TIP], pts[HandLm.INDEX_TIP])

    /**
     * Pointer anchor built from rigid palm joints (index + middle knuckles, wrist). Unlike the
     * fingertips it does not move when the fingers close into a pinch, so taps land where aimed.
     */
    val pointerAnchor: P3 = run {
        val a = pts[HandLm.INDEX_MCP]; val b = pts[HandLm.MIDDLE_MCP]; val w = pts[HandLm.WRIST]
        P3(0.4f * a.x + 0.4f * b.x + 0.2f * w.x, 0.4f * a.y + 0.4f * b.y + 0.2f * w.y, 0.4f * a.z + 0.4f * b.z + 0.2f * w.z)
    }

    /** Index fingertip: the pointer follows it. */
    val indexTip: P3 = pts[HandLm.INDEX_TIP]

    /**
     * Apparent hand size (mean of rigid palm bones, 3D so it tolerates rotation). Grows as the hand
     * gets closer to the camera; ln(scale) is used for push-to-click.
     */
    val scale: Float = run {
        val w = pts[HandLm.WRIST]; val i = pts[HandLm.INDEX_MCP]; val m = pts[HandLm.MIDDLE_MCP]; val p = pts[HandLm.PINKY_MCP]
        ((w.dist(i) + w.dist(m) + w.dist(p) + i.dist(p)) / 4f).coerceAtLeast(1e-4f)
    }

    /**
     * Unit palm normal (index-knuckle x pinky-knuckle from the wrist). Uses metric world landmarks
     * when available. z ≈ ±1 when the palm faces the camera, x for a vertical blade, y for a
     * horizontal one.
     */
    val palmNormal: P3 = run {
        val src = world?.takeIf { it.size >= 21 } ?: pts
        val w = src[HandLm.WRIST]
        val a = src[HandLm.INDEX_MCP] - w
        val b = src[HandLm.PINKY_MCP] - w
        val n = P3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
        val len = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
        if (len < 1e-9f) P3(0f, 0f, 1f) else n * (1f / len)
    }

    val pinchRatio: Float = pts[HandLm.THUMB_TIP].dist(pts[HandLm.INDEX_TIP]) / palmSize

    /** How far the index tip is from the wrist relative to the index knuckle (~2 extended, ~1 in a fist). */
    val indexReach: Float =
        pts[HandLm.WRIST].dist(pts[HandLm.INDEX_TIP]) /
            pts[HandLm.WRIST].dist(pts[HandLm.INDEX_MCP]).coerceAtLeast(1e-4f)

    /** Number of extended fingers among index, middle, ring, pinky. */
    val extendedFingers: Int = FINGERS.count { (pip, tip) ->
        pts[HandLm.WRIST].dist(pts[tip]) > pts[HandLm.WRIST].dist(pts[pip]) * 1.1f
    }

    val openPalm: Boolean get() = extendedFingers >= 4 && pinchRatio > 0.5f

    /** Index finger extended (pointing pose). */
    val pointing: Boolean get() = indexReach > 1.5f

    /** Flat hand seen edge-on, or null. */
    fun blade(minAxis: Float): BladeAxis? {
        if (extendedFingers < 4) return null
        val n = palmNormal
        return when {
            abs(n.x) >= minAxis -> BladeAxis.VERTICAL
            abs(n.y) >= minAxis -> BladeAxis.HORIZONTAL
            else -> null
        }
    }

    companion object {
        private val PALM = intArrayOf(HandLm.WRIST, HandLm.INDEX_MCP, HandLm.MIDDLE_MCP, HandLm.RING_MCP, HandLm.PINKY_MCP)
        private val FINGERS = listOf(
            HandLm.INDEX_PIP to HandLm.INDEX_TIP,
            HandLm.MIDDLE_PIP to HandLm.MIDDLE_TIP,
            HandLm.RING_PIP to HandLm.RING_TIP,
            HandLm.PINKY_PIP to HandLm.PINKY_TIP,
        )

        fun from(hand: HandObservation, aspect: Float): HandFeatures =
            HandFeatures(hand.landmarks.map { P3(it.x * aspect, it.y, it.z * aspect) }, hand.world)
    }
}
