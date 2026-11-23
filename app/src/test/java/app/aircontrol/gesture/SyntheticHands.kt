package app.aircontrol.gesture

import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds plausible MediaPipe-style landmarks for tests. Geometry is authored in aspect-corrected
 * space (units = image heights) and converted back to normalized image coordinates.
 */
object SyntheticHands {
    const val ASPECT = 4f / 3f

    /**
     * BLADE_V: flat hand, fingers up, palm facing sideways. BLADE_H: flat hand, fingers pointing
     * sideways, palm facing the floor. Both are seen almost edge-on by the camera.
     * SHAKA: thumb and pinky out. THREE: index, middle, ring up, thumb tucked. FOUR: four fingers up,
     * thumb tucked.
     */
    enum class Shape { OPEN, PINCH, FIST, POINT, BLADE_V, BLADE_H, SHAKA, THREE, FOUR }

    /**
     * @param cx,cy palm-centre position (the cursor reference) in aspect-corrected space (cx in 0..ASPECT, cy in 0..1)
     * @param s palm size (wrist to middle knuckle)
     * @param pinchRatio thumb-index tip distance / s, used by [Shape.PINCH]
     * @param indexCurl 0 = index straight, 1 = fully curled; used by [Shape.POINT]
     * @param rollDeg in-plane rotation of the whole hand about the palm centre: 0 = fingers up, 90 = fingers sideways
     */
    fun hand(
        cx: Float,
        cy: Float,
        shape: Shape = Shape.OPEN,
        gesture: String = Signs.NONE,
        score: Float = if (gesture == Signs.NONE) 0.5f else 0.9f,
        s: Float = 0.1f,
        pinchRatio: Float = 0.1f,
        indexCurl: Float = 0f,
        rollDeg: Float = 0f,
        side: HandSide = HandSide.ANY,
    ): HandObservation {
        // The authored geometry has its palm centre (mean of wrist + 4 knuckles) at (ox + 0.1 s, oy - 0.25 s).
        val ox = cx - 0.1f * s
        val oy = cy + 0.25f * s
        val p = Array(21) { P3(0f, 0f) }
        val wrist = P3(ox, oy + 0.5f * s)
        p[0] = wrist
        val mcps = mapOf(
            5 to P3(ox - 0.35f * s, oy - 0.45f * s),
            9 to P3(ox, oy - 0.5f * s),
            13 to P3(ox + 0.3f * s, oy - 0.45f * s),
            17 to P3(ox + 0.55f * s, oy - 0.35f * s),
        )
        fun extended(m: Int) {
            val b = mcps.getValue(m)
            p[m] = b
            p[m + 1] = b + P3(0f, -0.4f * s)
            p[m + 2] = b + P3(0f, -0.65f * s)
            p[m + 3] = b + P3(0f, -0.85f * s)
        }
        fun curled(m: Int) {
            val b = mcps.getValue(m)
            p[m] = b
            p[m + 1] = b + P3(0f, -0.3f * s)
            p[m + 2] = b + P3(0f, -0.15f * s)
            p[m + 3] = b + P3(0f, 0.05f * s)
        }
        fun thumbOut() {
            p[3] = wrist + P3(-0.8f * s, -0.55f * s)
            p[4] = wrist + P3(-0.95f * s, -0.75f * s)
        }
        fun thumbTucked() {
            p[3] = wrist + P3(-0.45f * s, -0.6f * s)
            p[4] = wrist + P3(-0.2f * s, -0.55f * s)
        }
        // Thumb base.
        p[1] = wrist + P3(-0.35f * s, -0.15f * s)
        p[2] = wrist + P3(-0.6f * s, -0.35f * s)
        when (shape) {
            Shape.OPEN, Shape.BLADE_V, Shape.BLADE_H -> {
                listOf(5, 9, 13, 17).forEach(::extended)
                thumbOut()
            }
            Shape.POINT -> {
                extended(5); curled(9); curled(13); curled(17)
                // The index curls about its knuckle: blend the extended and curled joint positions.
                val b = mcps.getValue(5)
                val k = indexCurl.coerceIn(0f, 1f)
                p[6] = b + P3(0f, (-0.4f + 0.1f * k) * s)
                p[7] = b + P3(0f, (-0.65f + 0.5f * k) * s)
                p[8] = b + P3(0f, (-0.85f + 0.9f * k) * s)
                p[3] = wrist + P3(-0.5f * s, -0.6f * s)
                p[4] = wrist + P3(-0.3f * s, -0.75f * s)
            }
            Shape.PINCH -> {
                listOf(9, 13, 17).forEach(::extended)
                val m = mcps.getValue(5)
                p[5] = m
                p[6] = m + P3(0f, -0.35f * s)
                p[7] = m + P3(0.02f * s, -0.55f * s)
                val tip = m + P3(0.05f * s, -0.6f * s)
                p[8] = tip
                p[4] = tip + P3(-pinchRatio * s, 0f)
                p[3] = p[4] + P3(-0.15f * s, 0.2f * s)
            }
            Shape.FIST -> {
                listOf(5, 9, 13, 17).forEach(::curled)
                p[3] = wrist + P3(-0.3f * s, -0.7f * s)
                p[4] = wrist + P3(-0.05f * s, -0.85f * s)
            }
            Shape.SHAKA -> {
                curled(5); curled(9); curled(13); extended(17)
                thumbOut()
            }
            Shape.THREE -> {
                extended(5); extended(9); extended(13); curled(17)
                thumbTucked()
            }
            Shape.FOUR -> {
                listOf(5, 9, 13, 17).forEach(::extended)
                thumbTucked()
            }
        }
        val c = P3(cx, cy)
        val pts = when (shape) {
            Shape.BLADE_V -> p.map { c + rotY(it - c, BLADE_DEG) }
            // Fingers to the side first (in-plane), then tip the palm to face down.
            Shape.BLADE_H -> p.map { c + rotX(rotZ(it - c, 90f), BLADE_DEG) }
            else -> p.toList()
        }.let { list -> if (rollDeg == 0f) list else list.map { c + rotZ(it - c, rollDeg) } }
        return HandObservation(
            pts.map { P3(it.x / ASPECT, it.y, it.z / ASPECT) }, gesture, score,
            world = pts.map { (it - c) * 0.8f },
            side = side,
        )
    }

    private const val BLADE_DEG = 80f

    private fun rad(d: Float) = Math.toRadians(d.toDouble())
    private fun rotY(p: P3, deg: Float): P3 {
        val c = cos(rad(deg)).toFloat(); val s = sin(rad(deg)).toFloat()
        return P3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c)
    }
    private fun rotX(p: P3, deg: Float): P3 {
        val c = cos(rad(deg)).toFloat(); val s = sin(rad(deg)).toFloat()
        return P3(p.x, p.y * c - p.z * s, p.y * s + p.z * c)
    }
    private fun rotZ(p: P3, deg: Float): P3 {
        val c = cos(rad(deg)).toFloat(); val s = sin(rad(deg)).toFloat()
        return P3(p.x * c - p.y * s, p.x * s + p.y * c, p.z)
    }

    fun pose(centerX: Float, shoulderY: Float, shoulderWidth: Float): PoseObservation {
        val pts = MutableList(33) { P3(0.5f, 0.5f) }
        val vis = MutableList(33) { 0.1f }
        pts[PoseLm.LEFT_SHOULDER] = P3((centerX - shoulderWidth / 2) / ASPECT, shoulderY)
        pts[PoseLm.RIGHT_SHOULDER] = P3((centerX + shoulderWidth / 2) / ASPECT, shoulderY)
        vis[PoseLm.LEFT_SHOULDER] = 0.99f
        vis[PoseLm.RIGHT_SHOULDER] = 0.99f
        return PoseObservation(pts, vis)
    }
}

/** Drives an engine with a fixed frame interval and records its events. */
class EngineHarness(config: EngineConfig = EngineConfig(screenW = 1000, screenH = 2000, density = 2f)) {
    val events = mutableListOf<ControlEvent>()
    val engine = GestureEngine(config) { events += it }
    var t = 0L
        private set
    private val frameMs = 33L

    fun frame(hands: List<HandObservation>, pose: PoseObservation? = null) {
        t += frameMs
        engine.process(FrameObservation(t, SyntheticHands.ASPECT, hands, pose))
    }

    /** Feeds frames for [durationMs] produced by [hand] (given elapsed ms within this segment). */
    fun hold(durationMs: Long, pose: PoseObservation? = null, hand: (Long) -> HandObservation?) {
        val start = t
        while (t - start < durationMs) {
            val h = hand(t - start + frameMs)
            frame(listOfNotNull(h), pose)
        }
    }

    /** Like [hold] but with several hands per frame. */
    fun holdHands(durationMs: Long, hands: (Long) -> List<HandObservation>) {
        val start = t
        while (t - start < durationMs) frame(hands(t - start + frameMs))
    }

    fun arm() {
        hold(1100) { SyntheticHands.hand(0.66f, 0.5f, gesture = Signs.I_LOVE_YOU) }
        // Release the sign so it can later be used to disarm.
        hold(300) { SyntheticHands.hand(0.66f, 0.5f, SyntheticHands.Shape.POINT) }
        check(engine.armed) { "engine failed to arm" }
        events.clear()
    }

    inline fun <reified T : ControlEvent> of(): List<T> = events.filterIsInstance<T>()
}
