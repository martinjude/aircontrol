package app.aircontrol.gesture

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Turns per-frame hand/body observations into control events.
 *
 * Not thread-safe: call every method from a single thread.
 *
 * - Disarmed: only the arm sign (🤟) is watched. Holding it for [EngineConfig.armHoldMs] arms.
 * - Armed: a pointer follows the index fingertip.
 *   - A quick push towards the camera = tap (the pointer freezes during the push, so it lands
 *     where aimed). Pinch = finger on the screen (quick pinch = tap, pinch + move = drag, pinch
 *     held = long press).
 *   - Holding a flat hand edge-on for [EngineConfig.swipeArmHoldMs] arms swipe mode: a vertical
 *     blade swipes left/right, a horizontal one up/down. It stays armed while the hand stays flat.
 *   - Held signs trigger system actions (or recenter the pointer).
 *   - Holding 🤟 again, or no hand for [EngineConfig.autoDisarmMs], disarms.
 */
class GestureEngine(config: EngineConfig, private val emit: (ControlEvent) -> Unit) {

    var config: EngineConfig = config
        set(value) {
            field = value
            applySmoothing()
        }

    @Volatile var armed = false
        private set
    @Volatile var debugSign: String = Signs.NONE
        private set
    @Volatile var debugPinch: Float = 0f
        private set
    @Volatile var debugPush: Float = 0f
        private set
    @Volatile var debugBlade: String = "-"
        private set
    @Volatile var bodyVisible = false
        private set

    private class Sample(val t: Long, val x: Float, val y: Float)

    private enum class Touch { IDLE, PENDING, DRAGGING }

    private val box = ControlBox()
    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()
    // Knuckle-driven point: rigid while the fingers close, so it drives drags (relative to touch-down).
    private val kfx = OneEuroFilter()
    private val kfy = OneEuroFilter()
    private var lastKx = 0f
    private var lastKy = 0f
    private val palmHistory = ArrayDeque<Sample>()
    private val pointerHistory = ArrayDeque<Sample>()
    private val armHold = HoldTracker()
    private val signHold = HoldTracker()
    private val bladeHold = HoldTracker()
    private val push = PushDetector()

    private var armProgressShown = 0f
    private var signProgressShown: SystemAction? = null

    private var touch = Touch.IDLE
    private var downT = 0L
    private var downX = 0f
    private var downY = 0f
    /** The fingertip moves while a pinch closes, so drags follow the knuckles: drag = knuckles + offset. */
    private var touchOffX = 0f
    private var touchOffY = 0f
    private var lastTouchEnd = NEVER
    private var lastSwipeT = NEVER
    private var lastSwipeDir: SwipeDir? = null

    // Pointer frozen while a sign / blade hold builds up (the hand changes shape, the tip moves).
    private var holdFreezeStart = NEVER
    private var freezeX = 0f
    private var freezeY = 0f

    // Swipe mode.
    private var swipeAxis: SwipeAxis? = null
    private var bladeLastSeen = NEVER
    private var bladeHoldStart = NEVER
    private var bladeStartPalm: P3? = null
    private var swipeProgressShown = 0f

    // Recenter.
    private var recenterPending = false
    private var recenterSince = NEVER
    private var steadyStart = NEVER
    private var steadyRef: P3? = null

    private var lastT = 0L
    private var lastHandMs = 0L
    private var handPresent = false
    private var pointerVisible = false
    private var activeCenter: P3? = null
    private var activeSeenMs = NEVER

    init {
        applySmoothing()
    }

    fun process(f: FrameObservation) {
        val cfg = config
        val t = f.timestampMs
        lastT = t
        box.updateBody(f.pose, f.imageAspect, t)
        bodyVisible = box.bodyVisible
        val bodyOk = !cfg.requireBody || box.bodyVisible

        val armSeen = bodyOk && f.hands.any { it.gesture == cfg.armSign && it.gestureScore >= cfg.signScoreMin }
        val hand = if (bodyOk) selectHand(f, t) else null
        debugSign = hand?.first?.takeIf { it.gestureScore >= cfg.signScoreMin }?.gesture ?: Signs.NONE
        debugPinch = hand?.second?.pinchRatio ?: 0f
        debugBlade = when (hand?.second?.blade(cfg.bladeMinAxis)) {
            BladeAxis.VERTICAL -> "V"
            BladeAxis.HORIZONTAL -> "H"
            null -> "-"
        }

        if (!armed) processDisarmed(t, armSeen) else processArmed(t, hand, armSeen)
        debugPush = push.debugGrowth
    }

    /** Arms immediately (notification action / test button). */
    fun armNow() {
        if (!armed) arm(lastT)
    }

    fun disarmNow(reason: DisarmReason = DisarmReason.MANUAL) = disarm(reason)

    // ---------------------------------------------------------------- disarmed

    private fun processDisarmed(t: Long, armSeen: Boolean) {
        val cfg = config
        armHold.update(if (armSeen) cfg.armSign else null, t, cfg.signGapMs)
        if (armHold.current != null) {
            val elapsed = armHold.elapsed()
            emitArmProgress((elapsed.toFloat() / cfg.armHoldMs).coerceIn(0f, 1f), disarming = false)
            if (elapsed >= cfg.armHoldMs) {
                armHold.fire()
                arm(t)
            }
        } else {
            emitArmProgress(0f, disarming = false)
        }
    }

    private fun arm(t: Long) {
        armed = true
        armProgressShown = 0f
        signHold.reset()
        bladeHold.reset()
        box.clearRecenter()
        resetHandState()
        lastHandMs = t
        emit(ControlEvent.Armed)
    }

    private fun disarm(reason: DisarmReason) {
        if (!armed) return
        endTouch()
        exitSwipeMode()
        armed = false
        armProgressShown = 0f
        signProgressShown = null
        signHold.reset()
        bladeHold.reset()
        recenterPending = false
        box.clearRecenter()
        resetHandState()
        emit(ControlEvent.Disarmed(reason))
    }

    // ---------------------------------------------------------------- armed

    private fun processArmed(t: Long, hand: Pair<HandObservation, HandFeatures>?, armSeen: Boolean) {
        val cfg = config
        if (hand == null) {
            if (handPresent && t - lastHandMs > cfg.handLostGraceMs) onHandLost()
            if (t - lastHandMs > cfg.autoDisarmMs) {
                disarm(DisarmReason.TIMEOUT)
                return
            }
            armHold.update(null, t, cfg.signGapMs)
            if (armHold.current == null) emitArmProgress(0f, disarming = true)
            signHold.update(null, t, cfg.signGapMs)
            if (signHold.current == null) cancelSignProgress()
            return
        }

        val (obs, feat) = hand
        lastHandMs = t
        if (!handPresent) {
            handPresent = true
            fx.reset(); fy.reset(); kfx.reset(); kfy.reset()
            palmHistory.clear(); pointerHistory.clear()
            push.reset()
        }
        val tip = feat.indexTip
        box.updateHand(feat, tip)
        if (recenterPending) updateRecenter(t, obs, feat)

        val (u, v) = box.map(tip, cfg.pointerSensitivity, cfg.anchorMode)
        val px = fx.filter((u * cfg.screenW).toDouble(), t).toFloat()
        val py = fy.filter((v * cfg.screenH).toDouble(), t).toFloat()
        val (ku, kv) = box.map(feat.pointerAnchor, cfg.pointerSensitivity, cfg.anchorMode)
        lastKx = kfx.filter((ku * cfg.screenW).toDouble(), t).toFloat()
        lastKy = kfy.filter((kv * cfg.screenH).toDouble(), t).toFloat()
        pointerHistory.addLast(Sample(t, px, py))
        palmHistory.addLast(Sample(t, feat.palmCenter.x, feat.palmCenter.y))
        trim(pointerHistory, t)
        trim(palmHistory, t)

        // Disarm with the arm sign (not while touching).
        if (touch == Touch.IDLE) {
            armHold.update(if (armSeen) cfg.armSign else null, t, cfg.signGapMs)
            if (armHold.current != null) {
                val elapsed = armHold.elapsed()
                emitArmProgress((elapsed.toFloat() / cfg.disarmHoldMs).coerceIn(0f, 1f), disarming = true)
                if (elapsed >= cfg.disarmHoldMs) {
                    armHold.fire()
                    disarm(DisarmReason.GESTURE)
                    return
                }
            } else {
                emitArmProgress(0f, disarming = true)
            }
        }

        // Swipe mode: arming with a held flat hand, then strokes along the armed axis.
        updateSwipeMode(t, feat)
        if (swipeAxis != null) {
            push.cancel()
            signHold.cancel()
            cancelSignProgress()
            if (pointerVisible) {
                pointerVisible = false
                emit(ControlEvent.Pointer(px / cfg.screenW, py / cfg.screenH, visible = false, touching = false))
            }
            detectStroke(t)
            return
        }

        updateTouch(t, obs, feat, px, py)

        // Push-to-click.
        val pushEnabled = touch == Touch.IDLE && feat.pointing && !recenterPending &&
            signHold.current == null && armHold.current == null && bladeHold.current == null
        if (push.update(t, ln(feat.scale), px, py, feat.palmCenter, feat.palmSize, pushEnabled, cfg)) {
            lastTouchEnd = t
            emit(ControlEvent.Tap(push.clickX / cfg.screenW, push.clickY / cfg.screenH))
        }

        // System signs.
        if (touch == Touch.IDLE && armHold.current == null && !push.frozen) {
            val sign = obs.gesture.takeIf {
                obs.gestureScore >= cfg.signScoreMin && it != cfg.armSign && cfg.actionFor(it) != SystemAction.NONE
            }
            signHold.update(sign, t, cfg.signGapMs)
            val cur = signHold.current
            if (cur != null) {
                val action = cfg.actionFor(cur)
                val hold = cfg.holdFor(cur)
                val elapsed = signHold.elapsed()
                if (signProgressShown != null && signProgressShown != action) {
                    emit(ControlEvent.SignProgress(signProgressShown!!, 0f))
                }
                signProgressShown = action
                emit(ControlEvent.SignProgress(action, (elapsed.toFloat() / hold).coerceIn(0f, 1f)))
                if (elapsed >= hold) {
                    signHold.fire()
                    signProgressShown = null
                    palmHistory.clear()
                    if (action == SystemAction.RECENTER) startRecenter(t) else emit(ControlEvent.Global(action))
                }
            } else {
                cancelSignProgress()
            }
        } else {
            signHold.cancel()
            cancelSignProgress()
        }

        // Pointer output.
        val holding = signHold.current != null || bladeHold.current != null
        if (holding) {
            if (holdFreezeStart == NEVER) {
                holdFreezeStart = t
                // The hand started changing shape a little before the sign was recognised.
                val back = lookback(t - HOLD_FREEZE_LOOKBACK_MS)
                freezeX = back?.x ?: px
                freezeY = back?.y ?: py
            }
        } else {
            holdFreezeStart = NEVER
        }
        val (dx, dy) = when {
            touch == Touch.PENDING -> downX to downY
            push.frozen -> push.clickX to push.clickY
            holding -> freezeX to freezeY
            touch == Touch.DRAGGING -> dragX() to dragY()
            else -> px to py
        }
        pointerVisible = true
        emit(
            ControlEvent.Pointer(
                dx / cfg.screenW, dy / cfg.screenH, visible = true, touching = touch != Touch.IDLE,
                press = if (push.state == PushDetector.State.PRESSING) push.progress.coerceAtLeast(0.05f) else 0f,
            )
        )
    }

    private fun updateTouch(t: Long, obs: HandObservation, feat: HandFeatures, px: Float, py: Float) {
        val cfg = config
        val w = cfg.screenW.toFloat()
        val h = cfg.screenH.toFloat()
        val pinched = if (touch == Touch.IDLE) {
            feat.pinchRatio < cfg.pinchEnter &&
                feat.indexReach > cfg.pinchMinIndexReach &&
                !(obs.gesture == Signs.CLOSED_FIST && obs.gestureScore > 0.7f)
        } else {
            feat.pinchRatio < cfg.pinchExit
        }
        when (touch) {
            Touch.IDLE -> if (pinched) {
                // A pinch wins over a push that may have started while the fingers closed.
                val pressed = push.frozen
                val px0 = push.clickX; val py0 = push.clickY
                push.cancel()
                touch = Touch.PENDING
                downT = t
                val back = lookback(t - cfg.touchLookbackMs)
                downX = if (pressed) px0 else back?.x ?: px
                downY = if (pressed) py0 else back?.y ?: py
                touchOffX = downX - lastKx
                touchOffY = downY - lastKy
            }
            Touch.PENDING -> when {
                !pinched -> {
                    touch = Touch.IDLE
                    lastTouchEnd = t
                    emit(ControlEvent.Tap(downX / w, downY / h))
                }
                hypot(dragX() - downX, dragY() - downY) > cfg.dragSlopPx || t - downT > cfg.tapMaxMs -> {
                    touch = Touch.DRAGGING
                    emit(ControlEvent.TouchDown(downX / w, downY / h))
                    emit(ControlEvent.TouchMove(dragX() / w, dragY() / h))
                }
            }
            Touch.DRAGGING -> if (!pinched) {
                touch = Touch.IDLE
                lastTouchEnd = t
                emit(ControlEvent.TouchUp(dragX() / w, dragY() / h))
            } else {
                emit(ControlEvent.TouchMove(dragX() / w, dragY() / h))
            }
        }
    }

    private fun dragX() = (lastKx + touchOffX).coerceIn(0f, config.screenW.toFloat())
    private fun dragY() = (lastKy + touchOffY).coerceIn(0f, config.screenH.toFloat())

    // ---------------------------------------------------------------- swipes

    private fun updateSwipeMode(t: Long, feat: HandFeatures) {
        val cfg = config
        val blade = feat.blade(cfg.bladeMinAxis)
        val axis = swipeAxis
        if (axis != null) {
            if (blade == bladeFor(axis)) bladeLastSeen = t
            else if (t - bladeLastSeen > cfg.swipeModeExitMs) exitSwipeMode()
            return
        }
        val idle = touch == Touch.IDLE && !push.frozen && signHold.current == null && armHold.current == null
        bladeHold.update(if (idle) blade?.name else null, t, BLADE_GAP_MS)
        val cur = bladeHold.current
        if (cur == null) {
            bladeHoldStart = NEVER
            emitSwipeProgress(0f)
            return
        }
        if (bladeHold.startedAt != bladeHoldStart) {
            bladeHoldStart = bladeHold.startedAt
            bladeStartPalm = feat.palmCenter
        }
        val start = bladeStartPalm ?: feat.palmCenter
        if (feat.palmCenter.dist2d(start) / feat.palmSize > cfg.swipeArmMaxDrift) {
            // Moving: restart the hold from here.
            bladeHold.cancel()
            bladeHold.update(blade?.name, t, BLADE_GAP_MS)
            bladeHoldStart = bladeHold.startedAt
            bladeStartPalm = feat.palmCenter
            emitSwipeProgress(0f)
            return
        }
        val elapsed = bladeHold.elapsed()
        if (elapsed >= cfg.swipeArmHoldMs) {
            bladeHold.fire()
            bladeHoldStart = NEVER
            swipeProgressShown = 0f
            val a = if (cur == BladeAxis.VERTICAL.name) SwipeAxis.HORIZONTAL else SwipeAxis.VERTICAL
            swipeAxis = a
            bladeLastSeen = t
            palmHistory.clear()
            push.cancel()
            emit(ControlEvent.SwipeMode(a))
        } else {
            emitSwipeProgress((elapsed.toFloat() / cfg.swipeArmHoldMs).coerceIn(0.01f, 0.99f))
        }
    }

    private fun detectStroke(t: Long) {
        val cfg = config
        val axis = swipeAxis ?: return
        val start = t - cfg.flickWindowMs
        val samples = palmHistory.filter { it.t >= start }
        if (samples.size < 3) return
        val first = samples.first()
        val last = samples.last()
        val dx = last.x - first.x
        val dy = last.y - first.y
        val threshold = cfg.flickMinShoulders * box.shoulderWidth.coerceAtLeast(0.05f) /
            cfg.swipeSensitivity.coerceIn(0.25f, 4f)
        val along = if (axis == SwipeAxis.HORIZONTAL) dx else dy
        val across = if (axis == SwipeAxis.HORIZONTAL) dy else dx
        if (abs(along) < threshold || abs(along) < abs(across) * cfg.flickAxisRatio) return
        val dir = if (axis == SwipeAxis.HORIZONTAL) {
            if (along > 0) SwipeDir.RIGHT else SwipeDir.LEFT
        } else {
            if (along > 0) SwipeDir.DOWN else SwipeDir.UP
        }
        if (t - lastSwipeT < cfg.swipeCooldownMs) return
        if (lastSwipeDir == dir.opposite && t - lastSwipeT < cfg.swipeOppositeBlockMs) return
        lastSwipeT = t
        lastSwipeDir = dir
        palmHistory.clear()
        emit(ControlEvent.Swipe(dir))
    }

    private fun exitSwipeMode() {
        if (swipeAxis != null) {
            swipeAxis = null
            swipeProgressShown = 0f
            palmHistory.clear()
            emit(ControlEvent.SwipeMode(null))
        } else {
            emitSwipeProgress(0f)
        }
        bladeHoldStart = NEVER
    }

    private fun emitSwipeProgress(p: Float) {
        if (p == 0f && swipeProgressShown == 0f) return
        swipeProgressShown = p
        emit(ControlEvent.SwipeMode(null, p))
    }

    private fun bladeFor(axis: SwipeAxis) = if (axis == SwipeAxis.HORIZONTAL) BladeAxis.VERTICAL else BladeAxis.HORIZONTAL

    // ---------------------------------------------------------------- recenter

    private fun startRecenter(t: Long) {
        recenterPending = true
        recenterSince = t
        steadyStart = NEVER
        steadyRef = null
        emit(ControlEvent.RecenterPending)
    }

    /** While pending: the first steady pointing position becomes the screen centre. */
    private fun updateRecenter(t: Long, obs: HandObservation, feat: HandFeatures) {
        val cfg = config
        if (t - recenterSince > cfg.recenterTimeoutMs) {
            recenterPending = false
            return
        }
        val tip = feat.indexTip
        val ref = steadyRef
        val stillSign = obs.gesture == Signs.THUMB_UP && obs.gestureScore >= cfg.signScoreMin
        if (!feat.pointing || stillSign || touch != Touch.IDLE) {
            steadyStart = NEVER; steadyRef = null
            return
        }
        if (ref == null || tip.dist2d(ref) / feat.palmSize > RECENTER_STEADY_TOL) {
            steadyStart = t; steadyRef = tip
            return
        }
        if (t - steadyStart >= cfg.recenterSteadyMs) {
            recenterPending = false
            box.recenter(tip, cfg.anchorMode)
            fx.reset(); fy.reset(); kfx.reset(); kfy.reset()
            pointerHistory.clear()
            emit(ControlEvent.Recentered)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun selectHand(f: FrameObservation, t: Long): Pair<HandObservation, HandFeatures>? {
        if (f.hands.isEmpty()) return null
        val candidates = f.hands.map { it to HandFeatures.from(it, f.imageAspect) }
        val prev = activeCenter
        val chosen = when {
            candidates.size == 1 -> candidates[0]
            prev != null && t - activeSeenMs < 500 -> candidates.minBy { it.second.palmCenter.dist2d(prev) }
            else -> candidates.maxBy { it.second.palmSize }
        }
        activeCenter = chosen.second.palmCenter
        activeSeenMs = t
        return chosen
    }

    private fun onHandLost() {
        handPresent = false
        endTouch()
        exitSwipeMode()
        bladeHold.cancel()
        push.reset()
        box.release()
        activeCenter = null
        palmHistory.clear()
        if (pointerVisible) {
            pointerVisible = false
            val last = pointerHistory.lastOrNull()
            val cfg = config
            emit(ControlEvent.Pointer((last?.x ?: 0f) / cfg.screenW, (last?.y ?: 0f) / cfg.screenH, visible = false, touching = false))
        }
        pointerHistory.clear()
        signHold.cancel()
        cancelSignProgress()
    }

    /** Lifts an active drag (a pending tap is dropped). */
    private fun endTouch() {
        if (touch == Touch.DRAGGING) {
            val cfg = config
            emit(ControlEvent.TouchUp(dragX() / cfg.screenW, dragY() / cfg.screenH))
        }
        if (touch != Touch.IDLE) lastTouchEnd = lastT
        touch = Touch.IDLE
    }

    private fun resetHandState() {
        handPresent = false
        pointerVisible = false
        touch = Touch.IDLE
        swipeAxis = null
        swipeProgressShown = 0f
        bladeHoldStart = NEVER
        holdFreezeStart = NEVER
        push.reset()
        fx.reset(); fy.reset(); kfx.reset(); kfy.reset()
        palmHistory.clear(); pointerHistory.clear()
        box.release()
        activeCenter = null
    }

    private fun lookback(target: Long): Sample? {
        var best: Sample? = null
        for (s in pointerHistory) {
            if (s.t <= target) best = s else break
        }
        return best ?: pointerHistory.firstOrNull()
    }

    private fun trim(q: ArrayDeque<Sample>, t: Long) {
        while (q.isNotEmpty() && t - q.first().t > HISTORY_MS) q.removeFirst()
    }

    private fun emitArmProgress(p: Float, disarming: Boolean) {
        if (p == 0f && armProgressShown == 0f) return
        armProgressShown = p
        emit(ControlEvent.ArmProgress(p, disarming))
    }

    private fun cancelSignProgress() {
        signProgressShown?.let { emit(ControlEvent.SignProgress(it, 0f)) }
        signProgressShown = null
    }

    private fun applySmoothing() {
        val s = config.smoothing.coerceIn(0f, 1f).toDouble()
        val minCutoff = exp(ln(4.0) + (ln(0.4) - ln(4.0)) * s)
        val beta = 0.02 + (0.004 - 0.02) * s
        for (f in arrayOf(fx, fy, kfx, kfy)) {
            f.minCutoff = minCutoff
            f.beta = beta
        }
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4
        const val HISTORY_MS = 600L
        const val HOLD_FREEZE_LOOKBACK_MS = 150L
        const val BLADE_GAP_MS = 120L
        /** Fingertip movement (palm sizes) still counted as steady while recentering. */
        const val RECENTER_STEADY_TOL = 0.3f
    }
}

/**
 * Tracks how long a sign has been held, tolerating short classification gaps. A sign that fired
 * is latched and must disappear before it can fire again.
 */
internal class HoldTracker {
    var current: String? = null
        private set
    private var start = 0L
    private var lastSeen = 0L

    /** When the current hold began. */
    val startedAt: Long get() = start
    private var latched: String? = null
    private var latchAbsentSince = -1L

    fun update(seen: String?, t: Long, gapMs: Long) {
        latched?.let { l ->
            if (seen == l) {
                latchAbsentSince = -1L
            } else {
                if (latchAbsentSince < 0) latchAbsentSince = t
                if (t - latchAbsentSince >= gapMs) {
                    latched = null
                    latchAbsentSince = -1L
                }
            }
        }
        if (seen != null && seen != latched) {
            if (seen == current) {
                lastSeen = t
            } else {
                current = seen
                start = t
                lastSeen = t
            }
        } else if (current != null && t - lastSeen > gapMs) {
            current = null
        }
    }

    /** Time the sign has actually been seen (does not grow during tolerated gaps). */
    fun elapsed(): Long = if (current == null) 0L else lastSeen - start

    fun fire() {
        latched = current
        latchAbsentSince = -1L
        current = null
    }

    fun cancel() {
        current = null
    }

    fun reset() {
        current = null
        latched = null
        latchAbsentSince = -1L
    }
}
