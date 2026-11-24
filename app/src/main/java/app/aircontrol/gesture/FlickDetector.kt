package app.aircontrol.gesture

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Swipe = hold an open palm still for a moment, then flick it. No arming, no special hand angle.
 *
 * - READY: open palm (≥ 4 extended fingers) slower than [EngineConfig.flickStillSpeed] for
 *   [EngineConfig.flickStillMs]. The start position and palm size are remembered.
 * - Within [EngineConfig.flickWindowMs] of leaving still, travel along one axis must reach
 *   [EngineConfig.flickMinPalms] palm sizes with a peak speed of [EngineConfig.flickMinPeakSpeed];
 *   all distances are in palm sizes so it feels the same at any distance from the camera.
 * - A hand lost mid-flick (motion blur) still counts if it was travelling fast toward the frame edge.
 * - After a swipe the opposite direction is ignored for a while (the return stroke) and the hand must
 *   be still again before the next one.
 *
 * Not thread-safe; owned by [GestureEngine].
 */
internal class FlickDetector {
    var ready = false
        private set
    /** Latest palm speed (palm sizes / s), for the preview. */
    var speed = 0f
        private set
    /** True while a flick is under way (the pointer is frozen). */
    val moving: Boolean get() = ready && onsetT != NEVER

    private var stillSince = NEVER
    private var startX = 0f
    private var startY = 0f
    private var startSize = 0f
    private var startT = 0L
    private var onsetT = NEVER
    private var peak = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = NEVER
    private var lastSwipeT = NEVER
    private var lastDir: SwipeDir? = null

    fun reset() {
        ready = false
        stillSince = NEVER
        onsetT = NEVER
        lastT = NEVER
        speed = 0f
    }

    /** Feeds one frame with the palm centre (aspect-corrected) and its speed. Returns a direction when a flick fires. */
    fun update(t: Long, f: HandFeatures, palmSpeed: Float, enabled: Boolean, cfg: EngineConfig): SwipeDir? {
        speed = palmSpeed
        val x = f.palmCenter.x
        val y = f.palmCenter.y
        lastX = x; lastY = y; lastT = t
        if (!enabled) {
            reset()
            lastT = t
            return null
        }
        val open = f.extendedFingers >= 4
        if (!ready || onsetT == NEVER) {
            // Waiting for (or holding) the still open palm.
            if (open && palmSpeed <= cfg.flickStillSpeed) {
                if (stillSince == NEVER) stillSince = t
                if (t - stillSince >= cfg.flickStillMs) {
                    ready = true
                    startX = x; startY = y; startSize = f.palmSize; startT = t
                }
                return null
            }
            if (!ready) {
                stillSince = NEVER
                return null
            }
            if (!open && f.extendedFingers < 3) {
                ready = false
                stillSince = NEVER
                return null
            }
            // Ready and moving: the flick starts.
            onsetT = t
            peak = 0f
        }
        if (palmSpeed > peak) peak = palmSpeed
        val dir = judge(x, y, cfg)
        if (dir != null) return fire(t, dir, cfg)
        if (t - onsetT > cfg.flickWindowMs) {
            // Too slow or too short: a cursor move, not a flick. Needs a new still moment.
            ready = false
            onsetT = NEVER
            stillSince = NEVER
        }
        return null
    }

    /** The hand vanished. A flick in progress toward the frame edge still counts (motion blur). */
    fun onHandLost(t: Long, aspect: Float, cfg: EngineConfig): SwipeDir? {
        // A dropout while holding still keeps the hand ready; the caller resets once the hand is really gone.
        if (!moving) return null
        if (t - onsetT > LOST_GRACE_MS + cfg.flickWindowMs) { reset(); return null }
        val s = startSize.coerceAtLeast(1e-4f)
        val dx = lastX - startX
        val dy = lastY - startY
        if (hypot(dx, dy) / s < 1f || peak < cfg.flickMinPeakSpeed) { reset(); return null }
        val dir = direction(dx, dy, cfg) ?: run { reset(); return null }
        val margin = EDGE_PALMS * s
        val nearEdge = when (dir) {
            SwipeDir.LEFT -> lastX < margin
            SwipeDir.RIGHT -> lastX > aspect - margin
            SwipeDir.UP -> lastY < margin
            SwipeDir.DOWN -> lastY > 1f - margin
        }
        return if (nearEdge) fire(t, dir, cfg) else { reset(); null }
    }

    private fun judge(x: Float, y: Float, cfg: EngineConfig): SwipeDir? {
        val s = startSize.coerceAtLeast(1e-4f)
        val dx = (x - startX) / s
        val dy = (y - startY) / s
        val need = cfg.flickMinPalms / cfg.swipeSensitivity.coerceIn(0.25f, 4f)
        if (maxOf(abs(dx), abs(dy)) < need || peak < cfg.flickMinPeakSpeed) return null
        return direction(dx, dy, cfg)
    }

    private fun direction(dx: Float, dy: Float, cfg: EngineConfig): SwipeDir? = when {
        abs(dx) >= abs(dy) * cfg.flickAxisRatio -> if (dx > 0) SwipeDir.RIGHT else SwipeDir.LEFT
        abs(dy) >= abs(dx) * cfg.flickAxisRatio -> if (dy > 0) SwipeDir.DOWN else SwipeDir.UP
        else -> null
    }

    private fun fire(t: Long, dir: SwipeDir, cfg: EngineConfig): SwipeDir? {
        ready = false
        onsetT = NEVER
        stillSince = NEVER
        val blocked = t - lastSwipeT < cfg.swipeCooldownMs ||
            (lastDir == dir.opposite && t - lastSwipeT < cfg.swipeOppositeBlockMs)
        if (blocked) return null
        lastSwipeT = t
        lastDir = dir
        return dir
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4
        const val LOST_GRACE_MS = 200L
        /** "Near the frame edge" = within this many palm sizes of it. */
        const val EDGE_PALMS = 1.5f
    }
}
