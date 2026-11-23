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
 *   (The hand is still analysed so the live preview can show what *would* happen.)
 * - Armed: a pointer follows the centre of the palm; the camera view maps directly onto the screen.
 *   - Click = the index finger curls and straightens quickly ([ClickDetector]).
 *   - Pinch (hand upright, thumb–index plane facing the camera) = finger on the screen: quick pinch
 *     = tap, pinch + move = drag, pinch held = long press.
 *   - Holding a flat hand edge-on for [EngineConfig.swipeArmHoldMs] arms swipe mode: a vertical
 *     blade swipes left/right, a horizontal one up/down. It stays armed while the hand stays flat.
 *   - Held poses trigger the user's binds (one hand, or two hands together).
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
    @Volatile var debug: EngineDebug = EngineDebug()
        private set
    @Volatile var bodyVisible = false
        private set

    private class Sample(val t: Long, val x: Float, val y: Float)

    private enum class Touch { IDLE, PENDING, DRAGGING }

    private val body = BodyScale()
    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()
    private val palmHistory = ArrayDeque<Sample>()
    private val pointerHistory = ArrayDeque<Sample>()
    private val armHold = HoldTracker()
    private val bladeHold = HoldTracker()
    private val click = ClickDetector()
    private val matcher = BindMatcher()

    private var px = 0f
    private var py = 0f

    private var armProgressShown = 0f
    private var bindShown: String? = null
    private var bindProgressNow = 0f

    private var touch = Touch.IDLE
    private var downT = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastTouchEnd = NEVER
    private var lastSwipeT = NEVER
    private var lastSwipeDir: SwipeDir? = null
    private var lastClickT = NEVER
    private var lastPinchReady = false

    // Pointer frozen while a bind / blade hold builds up (the hand changes shape).
    private var holdFreezeStart = NEVER
    private var freezeX = 0f
    private var freezeY = 0f

    // Swipe mode.
    private var swipeAxis: SwipeAxis? = null
    private var bladeLastSeen = NEVER
    private var bladeHoldStart = NEVER
    private var bladeStartPalm: P3? = null
    private var swipeProgressShown = 0f

    private var lastT = 0L
    private var lastHandMs = 0L
    private var handPresent = false
    private var pointerVisible = false
    private var activeCenter: P3? = null
    private var activeSeenMs = NEVER
    private var cursorU = 0.5f
    private var cursorV = 0.5f

    init {
        applySmoothing()
    }

    fun process(f: FrameObservation) {
        val cfg = config
        val t = f.timestampMs
        lastT = t
        body.updateBody(f.pose, f.imageAspect, t)
        bodyVisible = body.bodyVisible
        val bodyOk = !cfg.requireBody || body.bodyVisible

        val armSeen = bodyOk && f.hands.any { it.gesture == cfg.armSign && it.gestureScore >= cfg.signScoreMin }
        val all = if (bodyOk) {
            f.hands.map { h ->
                val ft = HandFeatures.from(h, f.imageAspect)
                TrackedHand(h, ft, HandPose.classify(h, ft, cfg.signScoreMin))
            }
        } else {
            emptyList()
        }
        val hand = selectHand(all, t)

        if (!armed) {
            processDisarmed(t, armSeen)
            observeDisarmed(t, hand, f.imageAspect)
        } else {
            processArmed(t, hand, all, armSeen, f.imageAspect)
        }
        publishDebug(t, hand, all.size, f.imageAspect)
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

    /** While disarmed the hand is still followed (no events) so the preview can show cursor, pinch and click state. */
    private fun observeDisarmed(t: Long, hand: TrackedHand?, aspect: Float) {
        if (armed) return
        val cfg = config
        if (hand == null) {
            if (handPresent && t - lastHandMs > cfg.handLostGraceMs) {
                handPresent = false
                click.reset()
                activeCenter = null
                palmHistory.clear(); pointerHistory.clear()
            }
            return
        }
        lastHandMs = t
        track(t, hand, aspect)
        val f = hand.feat
        if (click.update(t, f.indexExt, px, py, f.palmCenter, f.palmSize, enabled = armHold.current == null, cfg)) {
            lastClickT = t
        }
        click.takeReject()?.let { emit(ControlEvent.ClickRejected(it)) }
        cursorU = px / cfg.screenW
        cursorV = py / cfg.screenH
        lastPinchReady = pinchReady(f, cfg)
    }

    private fun arm(t: Long) {
        armed = true
        armProgressShown = 0f
        matcher.reset()
        bladeHold.reset()
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
        bindShown = null
        matcher.reset()
        bladeHold.reset()
        resetHandState()
        emit(ControlEvent.Disarmed(reason))
    }

    // ---------------------------------------------------------------- armed

    private fun processArmed(t: Long, hand: TrackedHand?, all: List<TrackedHand>, armSeen: Boolean, aspect: Float) {
        val cfg = config
        if (hand == null) {
            if (handPresent && t - lastHandMs > cfg.handLostGraceMs) onHandLost()
            if (t - lastHandMs > cfg.autoDisarmMs) {
                disarm(DisarmReason.TIMEOUT)
                return
            }
            armHold.update(null, t, cfg.signGapMs)
            if (armHold.current == null) emitArmProgress(0f, disarming = true)
            matcher.update(t, emptyList(), cfg.binds, cfg.signGapMs, enabled = false)
            cancelBindProgress()
            return
        }

        lastHandMs = t
        track(t, hand, aspect)
        val obs = hand.obs
        val feat = hand.feat

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
            click.cancel()
            matcher.update(t, emptyList(), cfg.binds, cfg.signGapMs, enabled = false)
            cancelBindProgress()
            cursorU = px / cfg.screenW
            cursorV = py / cfg.screenH
            if (pointerVisible) {
                pointerVisible = false
                emit(ControlEvent.Pointer(cursorU, cursorV, visible = false, touching = false))
            }
            detectStroke(t)
            return
        }

        updateTouch(t, obs, feat)
        lastPinchReady = pinchReady(feat, cfg)

        // Binds (one hand or two hands held).
        runBinds(t, all, enabled = touch == Touch.IDLE && armHold.current == null)

        // Click: index curl and release.
        val clickEnabled = touch == Touch.IDLE && !matcher.holding && armHold.current == null && bladeHold.current == null
        if (click.update(t, feat.indexExt, px, py, feat.palmCenter, feat.palmSize, clickEnabled, cfg)) {
            lastTouchEnd = t
            lastClickT = t
            emit(ControlEvent.Tap(click.clickX / cfg.screenW, click.clickY / cfg.screenH, click = true))
        }
        click.takeReject()?.let { emit(ControlEvent.ClickRejected(it)) }

        // Pointer output.
        val holding = matcher.holding || bladeHold.current != null
        if (holding) {
            if (holdFreezeStart == NEVER) {
                holdFreezeStart = t
                // The hand started changing shape a little before the pose was recognised.
                val back = lookback(t - HOLD_FREEZE_LOOKBACK_MS)
                freezeX = back?.x ?: px
                freezeY = back?.y ?: py
            }
        } else {
            holdFreezeStart = NEVER
        }
        val (dx, dy) = when {
            touch == Touch.PENDING -> downX to downY
            holding -> freezeX to freezeY
            touch == Touch.DRAGGING -> dragX() to dragY()
            else -> px to py
        }
        pointerVisible = true
        cursorU = dx / cfg.screenW
        cursorV = dy / cfg.screenH
        emit(
            ControlEvent.Pointer(
                cursorU, cursorV, visible = true, touching = touch != Touch.IDLE,
                press = if (click.state == ClickDetector.State.CURLING) click.progress.coerceAtLeast(0.05f) else 0f,
            )
        )
    }

    /** Follows the palm centre: maps it to the screen, filters it and keeps the histories. */
    private fun track(t: Long, hand: TrackedHand, aspect: Float) {
        val cfg = config
        val feat = hand.feat
        if (!handPresent) {
            handPresent = true
            fx.reset(); fy.reset()
            palmHistory.clear(); pointerHistory.clear()
            click.reset()
        }
        body.updateHand(feat)
        val (u, v) = PointerMap.map(feat.palmCenter, aspect, cfg.pointerGain)
        px = fx.filter((u * cfg.screenW).toDouble(), t).toFloat()
        py = fy.filter((v * cfg.screenH).toDouble(), t).toFloat()
        pointerHistory.addLast(Sample(t, px, py))
        palmHistory.addLast(Sample(t, feat.palmCenter.x, feat.palmCenter.y))
        trim(pointerHistory, t)
        trim(palmHistory, t)
    }

    private fun runBinds(t: Long, all: List<TrackedHand>, enabled: Boolean) {
        val cfg = config
        val r = matcher.update(t, all, cfg.binds, cfg.signGapMs, enabled)
        val cand = r.candidate
        when {
            r.fired != null -> {
                val b = r.fired
                cancelBindProgress()
                palmHistory.clear()
                click.cancel()
                emit(ControlEvent.BindFired(b.action, b.appPackage, b.actionLabel))
            }
            cand != null -> {
                val label = cand.actionLabel
                val shown = bindShown
                if (shown != null && shown != label) emit(ControlEvent.BindProgress(shown, 0f))
                bindShown = label
                bindProgressNow = r.progress
                emit(ControlEvent.BindProgress(label, r.progress.coerceAtLeast(0.01f)))
            }
            else -> cancelBindProgress()
        }
    }

    private fun pinchReady(feat: HandFeatures, cfg: EngineConfig) =
        feat.upright(cfg.pinchMaxTiltDeg, cfg.pinchMinUpright) && feat.indexReach > cfg.pinchMinIndexReach

    private fun updateTouch(t: Long, obs: HandObservation, feat: HandFeatures) {
        val cfg = config
        val w = cfg.screenW.toFloat()
        val h = cfg.screenH.toFloat()
        val pinched = if (touch == Touch.IDLE) {
            feat.pinchRatio < cfg.pinchEnter &&
                feat.indexReach > cfg.pinchMinIndexReach &&
                feat.upright(cfg.pinchMaxTiltDeg, cfg.pinchMinUpright) &&
                !(obs.gesture == Signs.CLOSED_FIST && obs.gestureScore > 0.7f)
        } else {
            feat.pinchRatio < cfg.pinchExit && feat.tiltDeg <= cfg.pinchHoldTiltDeg
        }
        when (touch) {
            Touch.IDLE -> if (pinched) {
                // A recognised pinch wins over an index curl that may have started while the fingers closed.
                click.cancel("pinch")
                touch = Touch.PENDING
                downT = t
                val back = lookback(t - cfg.touchLookbackMs)
                downX = back?.x ?: px
                downY = back?.y ?: py
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

    private fun dragX() = px.coerceIn(0f, config.screenW.toFloat())
    private fun dragY() = py.coerceIn(0f, config.screenH.toFloat())

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
        val idle = touch == Touch.IDLE && !matcher.holding && armHold.current == null
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
            click.cancel()
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
        val threshold = cfg.flickMinShoulders * body.shoulderWidth.coerceAtLeast(0.05f) /
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

    // ---------------------------------------------------------------- helpers

    private fun selectHand(hands: List<TrackedHand>, t: Long): TrackedHand? {
        if (hands.isEmpty()) return null
        val prev = activeCenter
        val chosen = when {
            hands.size == 1 -> hands[0]
            prev != null && t - activeSeenMs < 500 -> hands.minBy { it.feat.palmCenter.dist2d(prev) }
            else -> hands.maxBy { it.feat.palmSize }
        }
        activeCenter = chosen.feat.palmCenter
        activeSeenMs = t
        return chosen
    }

    private fun onHandLost() {
        handPresent = false
        endTouch()
        exitSwipeMode()
        bladeHold.cancel()
        click.reset()
        activeCenter = null
        palmHistory.clear()
        if (pointerVisible) {
            pointerVisible = false
            val last = pointerHistory.lastOrNull()
            val cfg = config
            emit(ControlEvent.Pointer((last?.x ?: 0f) / cfg.screenW, (last?.y ?: 0f) / cfg.screenH, visible = false, touching = false))
        }
        pointerHistory.clear()
        matcher.cancel()
        cancelBindProgress()
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
        click.reset()
        fx.reset(); fy.reset()
        palmHistory.clear(); pointerHistory.clear()
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

    private fun cancelBindProgress() {
        bindShown?.let { emit(ControlEvent.BindProgress(it, 0f)) }
        bindShown = null
        bindProgressNow = 0f
    }

    private fun applySmoothing() {
        val s = config.smoothing.coerceIn(0f, 1f).toDouble()
        val minCutoff = exp(ln(4.0) + (ln(0.4) - ln(4.0)) * s)
        val beta = 0.02 + (0.004 - 0.02) * s
        for (f in arrayOf(fx, fy)) {
            f.minCutoff = minCutoff
            f.beta = beta
        }
    }

    private fun publishDebug(t: Long, hand: TrackedHand?, handCount: Int, aspect: Float) {
        val cfg = config
        val f = hand?.feat
        val prev = debug
        debug = EngineDebug(
            t = t,
            armed = armed,
            hands = handCount,
            side = hand?.obs?.side ?: HandSide.ANY,
            sign = hand?.obs?.takeIf { it.gestureScore >= cfg.signScoreMin }?.gesture ?: Signs.NONE,
            pose = hand?.pose,
            palmU = f?.let { it.palmCenter.x / aspect } ?: prev.palmU,
            palmV = f?.palmCenter?.y ?: prev.palmV,
            cursorU = cursorU,
            cursorV = cursorV,
            gain = cfg.pointerGain,
            pinch = f?.pinchRatio ?: 0f,
            pinchEnter = cfg.pinchEnter,
            pinchExit = cfg.pinchExit,
            pinchReady = hand != null && lastPinchReady,
            touch = touch.name,
            tiltDeg = f?.tiltDeg ?: 0f,
            upright = f?.upright(cfg.pinchMaxTiltDeg, cfg.pinchMinUpright) ?: false,
            reach = f?.indexExt ?: 0f,
            reachBaseline = if (hand != null) click.baseline else 0f,
            curlNeeded = cfg.clickNeeded,
            curlDepth = if (hand != null) click.depth else 0f,
            clickState = click.state.name,
            clickAgoMs = if (lastClickT == NEVER) Long.MAX_VALUE else t - lastClickT,
            blade = when (f?.blade(cfg.bladeMinAxis)) {
                BladeAxis.VERTICAL -> "V"
                BladeAxis.HORIZONTAL -> "H"
                null -> "-"
            },
            swipeAxis = swipeAxis,
            bindLabel = bindShown,
            bindProgress = bindProgressNow,
            bodyVisible = body.bodyVisible,
        )
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4
        const val HISTORY_MS = 600L
        const val HOLD_FREEZE_LOOKBACK_MS = 150L
        const val BLADE_GAP_MS = 120L
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
