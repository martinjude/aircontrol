package app.aircontrol.gesture

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Turns per-frame hand/body/face observations into control events.
 *
 * Not thread-safe: call every method from a single thread.
 *
 * - Disarmed: only the arm sign (🤟) is watched. Holding it for [EngineConfig.armHoldMs] arms.
 *   (The hand is still analysed so the live preview can show what *would* happen.)
 * - Armed: a pointer follows the centre of the palm through the hand-safe region ([PointerMap]).
 *   - Finger tap: thumb and index tips touch. Quick = click; held = finger on the screen (move to
 *     drag, keep still for a long press).
 *   - Air push: pushing the hand through a virtual plane ([PushPlane]) touches the same way.
 *   - Both are judged by [TapGate] (hand angle, steadiness, isolation, approach, attention) and
 *     paused for [EngineConfig.poseCooldownMs] after a bound pose or the arm sign.
 *   - Swipe = hold an open palm still for a moment, then flick it ([FlickDetector]).
 *   - Held poses trigger the user's binds (one hand, or two hands together).
 *   - Holding 🤟 again, or no hand for [EngineConfig.autoDisarmMs], disarms.
 * - The pointer carries a confidence (hand angle, steadiness, gaze, frame edge) and a warning when
 *   the palm is turned away from the calibrated angle.
 * - Calibrations ([startCalibration]): the comfortable hand angle, the air plane at 5 screen points,
 *   and where the eyes look when on the screen.
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
    /** The calibration in progress, if any. */
    @Volatile var calibrating: CalibrationKind? = null
        private set

    private class Sample(val t: Long, val x: Float, val y: Float)

    private enum class Touch { IDLE, PENDING, DRAGGING }

    private val body = BodyScale()
    private val fx = OneEuroFilter()
    private val fy = OneEuroFilter()
    private val palmHistory = ArrayDeque<Sample>()
    private val pointerHistory = ArrayDeque<Sample>()
    private val armHold = HoldTracker()
    private val gate = TapGate()
    private val push = PushPlane()
    private val flick = FlickDetector()
    private val attention = Attention()
    private val matcher = BindMatcher()

    // Filtered pointer from the palm, and the output ("aim") after the air-push slow-down.
    private var px = 0f
    private var py = 0f
    private var prevPx = 0f
    private var prevPy = 0f
    private var ox = 0f
    private var oy = 0f
    private var palmSpeed = 0f
    private var palmSizeSmooth = 0f
    private var lastTrackT = 0L
    private var region = PointerMap.Region(0f, 0f, 1f, 1f)
    private var regionAspect = 1f
    private var edge = 0

    // Confidence.
    private var angleDeg = 0f
    private var angleWarn = 0f
    private var jitter = 0f
    private var pointerConfidence = 1f
    private var lastPoseT = NEVER

    private var armProgressShown = 0f
    private var bindShown: String? = null
    private var bindProgressNow = 0f

    private var touch = Touch.IDLE
    private var touchSource: TouchSource? = null
    private var downT = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastClickT = NEVER
    /** A contact (or push) was rejected: ignore it until the fingers part (or the push releases). */
    private var tapRejected = false
    private var pushRejected = false

    // Pointer frozen while a bind hold builds up (the hand changes shape) or during a flick.
    private var holdFreezeStart = NEVER
    private var freezeX = 0f
    private var freezeY = 0f
    private var flickFreezeUntil = NEVER
    private var flickX = 0f
    private var flickY = 0f

    private var lastT = 0L
    private var lastHandMs = 0L
    private var handPresent = false
    private var pointerVisible = false
    private var activeCenter: P3? = null
    private var activeSeenMs = NEVER
    private var cursorU = 0.5f
    private var cursorV = 0.5f

    // Calibration.
    private var calStep = 0
    private var calPhase = 0
    private var calStartT = 0L
    private var calStepMs = 0L
    private var calPrevT = 0L
    private var calLastEmit = 0L
    private var calHover = 0f
    /** Time left before measuring starts (the instruction can be read), and in the pause after an air-plane point. */
    private var calReadyLeft = 0L
    private var calDoneLeft = 0L
    private var calSeqIndex = 0
    private var calSeqSize = 1
    private val calValues = ArrayList<Float>()
    private val calNormals = ArrayList<P3>()
    private val calFaces = ArrayList<FaceObservation>()
    private val calResult = ArrayList<Float>()

    init {
        applySmoothing()
    }

    fun process(f: FrameObservation) {
        val cfg = config
        val t = f.timestampMs
        lastT = t
        body.updateBody(f.pose, f.imageAspect, t)
        bodyVisible = body.bodyVisible
        attention.update(t, f.face, cfg.attentionCalibration)
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
        // Pose cooldown: a bound pose, a fist or the arm sign on any hand means the hand is changing shape.
        if (armSeen || all.any { it.pose != null && (it.pose == Pose.FIST || boundPose(it.pose, cfg)) }) lastPoseT = t
        val hand = selectHand(all, t)

        if (!armed) {
            processDisarmed(t, armSeen)
            observeDisarmed(t, hand, f.imageAspect)
        } else {
            processArmed(t, hand, all, armSeen, f.imageAspect)
        }
        updateCalibration(t, hand, f.face)
        publishDebug(t, hand, all.size, f.imageAspect)
    }

    /** Arms immediately (notification action / test button). */
    fun armNow() {
        if (!armed) arm(lastT)
    }

    fun disarmNow(reason: DisarmReason = DisarmReason.MANUAL) = disarm(reason)

    /**
     * Starts a calibration: the hand angle (needs a hand), the air plane (hand, 5 points) or attention (needs a face).
     * Nothing is measured for the first [readyMs] (the user reads what to do). [sequenceIndex] of
     * [sequenceSize] is shown when several calibrations run one after the other.
     */
    fun startCalibration(kind: CalibrationKind, sequenceIndex: Int = 0, sequenceSize: Int = 1, readyMs: Long = CAL_READY_MS) {
        calibrating = kind
        calSeqIndex = sequenceIndex
        calSeqSize = sequenceSize
        calReadyLeft = readyMs
        calDoneLeft = 0L
        calStep = 0
        calPhase = 0
        calStartT = lastT
        calStepMs = 0L
        calPrevT = lastT
        calLastEmit = NEVER
        calValues.clear()
        calNormals.clear()
        calFaces.clear()
        calResult.clear()
        endTouch()
        emitCalibration(0f)
    }

    fun cancelCalibration() {
        val kind = calibrating ?: return
        calibrating = null
        emit(ControlEvent.Calibration(kind, calStep, 0f, "Calibration cancelled", done = true, result = null,
            stepCount = stepCount(kind), sequenceIndex = calSeqIndex, sequenceSize = calSeqSize))
    }

    private fun boundPose(p: Pose, cfg: EngineConfig) =
        cfg.binds.any { it.enabled && it.action != SystemAction.NONE && (it.first.pose == p || it.second?.pose == p) }

    private fun cooling(t: Long) = t - lastPoseT < config.poseCooldownMs

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

    /** While disarmed the hand is still followed (no events) so the preview can show the cursor, taps and confidence. */
    private fun observeDisarmed(t: Long, hand: TrackedHand?, aspect: Float) {
        if (armed) return
        val cfg = config
        if (hand == null) {
            if (handPresent && t - lastHandMs > cfg.handLostGraceMs) {
                handPresent = false
                resetHandTracking()
            }
            return
        }
        lastHandMs = t
        track(t, hand, aspect)
        val f = hand.feat
        val p = push.update(t, f, ox / cfg.screenW, oy / cfg.screenH, enabled = false, frozen = false, cfg)
        updateAim(t, p)
        updateConfidence(t, f)
        flick.update(t, f, palmSpeed, enabled = false, cfg)
        gate.observe(t, f, palmSpeed)
        if (calibrating == null) updateTouch(t, hand.obs, f, live = false)
        cursorU = ox / cfg.screenW
        cursorV = oy / cfg.screenH
    }

    private fun arm(t: Long) {
        armed = true
        armProgressShown = 0f
        matcher.reset()
        resetHandState()
        lastHandMs = t
        // The arm sign was just held: the hand is changing shape.
        lastPoseT = t
        emit(ControlEvent.Armed)
    }

    private fun disarm(reason: DisarmReason) {
        if (!armed) return
        endTouch()
        armed = false
        armProgressShown = 0f
        bindShown = null
        matcher.reset()
        resetHandState()
        emit(ControlEvent.Disarmed(reason))
    }

    // ---------------------------------------------------------------- armed

    private fun processArmed(t: Long, hand: TrackedHand?, all: List<TrackedHand>, armSeen: Boolean, aspect: Float) {
        val cfg = config
        if (hand == null) {
            if (handPresent && t - lastHandMs > cfg.handLostGraceMs) onHandLost(t, aspect)
            else if (handPresent) flick.onHandLost(t, aspect, cfg)?.let { onFlick(t, it) }
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
        val calib = calibrating != null

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
        val signHeld = armHold.current != null
        val cooldown = cooling(t)

        // Swipes: still open palm, then a flick.
        val flickEnabled = cfg.swipesEnabled && touch == Touch.IDLE && !signHeld && !calib && !cooldown
        val dir = flick.update(t, feat, palmSpeed, flickEnabled, cfg)
        // Freeze the pointer once the open hand clearly flicks (not for ordinary open-palm moves).
        val fast = flick.moving && palmSpeed >= cfg.flickMinPeakSpeed * 0.5f
        if ((fast || dir != null) && t >= flickFreezeUntil) {
            val back = lookback(t - FLICK_LOOKBACK_MS)
            flickX = back?.x ?: ox
            flickY = back?.y ?: oy
        }
        if (fast) flickFreezeUntil = maxOf(flickFreezeUntil, t + 1)
        dir?.let { onFlick(t, it) }
        val flicking = fast || t < flickFreezeUntil

        // Air push.
        val pushEnabled = cfg.pushClicks && !signHeld && !calib && !flicking && touchSource != TouchSource.TAP
        val p = push.update(t, feat, ox / cfg.screenW, oy / cfg.screenH, pushEnabled, frozen = touchSource == TouchSource.PUSH, cfg)
        updateAim(t, if (pushEnabled) p else 0f)
        updateConfidence(t, feat)

        gate.observe(t, feat, palmSpeed)
        if (!calib && !signHeld) updateTouch(t, obs, feat, live = true)

        // Binds (one hand or two hands held).
        runBinds(t, all, enabled = touch == Touch.IDLE && !signHeld && !flicking && !calib)
        if (matcher.holding) lastPoseT = t

        // Pointer output.
        val holding = matcher.holding
        if (holding) {
            if (holdFreezeStart == NEVER) {
                holdFreezeStart = t
                // The hand started changing shape a little before the pose was recognised.
                val back = lookback(t - HOLD_FREEZE_LOOKBACK_MS)
                freezeX = back?.x ?: ox
                freezeY = back?.y ?: oy
            }
        } else {
            holdFreezeStart = NEVER
        }
        val (dx, dy) = when {
            touch == Touch.PENDING -> downX to downY
            holding -> freezeX to freezeY
            flicking -> flickX to flickY
            touch == Touch.DRAGGING -> dragX() to dragY()
            else -> ox to oy
        }
        pointerVisible = true
        cursorU = dx / cfg.screenW
        cursorV = dy / cfg.screenH
        val att = attention.score
        emit(
            ControlEvent.Pointer(
                cursorU, cursorV, visible = true, touching = touch != Touch.IDLE,
                closing = closing(feat, cfg),
                push = if (cfg.pushClicks) p else 0f,
                touchSource = touchSource,
                holdMs = if (touch != Touch.IDLE) t - downT else 0L,
                dragging = touch == Touch.DRAGGING,
                edge = edge,
                confidence = pointerConfidence,
                angleWarn = angleWarn,
                looking = if (cfg.attentionCalibration == null || att < 0f || att == Attention.NEUTRAL) null else att >= cfg.attentionLooking,
                cooldown = cooldown,
            )
        )
    }

    /** Follows the palm centre: maps it to the screen through the hand-safe region, filters it, keeps the histories. */
    private fun track(t: Long, hand: TrackedHand, aspect: Float) {
        val cfg = config
        val feat = hand.feat
        if (!handPresent) {
            handPresent = true
            resetHandTracking()
            lastTrackT = t
            // A hand coming into view is not a deliberate gesture yet.
            lastPoseT = maxOf(lastPoseT, t)
        }
        body.updateHand(feat)
        val dt = (t - lastTrackT).coerceIn(1L, 200L)
        lastTrackT = t
        // The region follows the hand's size slowly, and not at all while touching or pushing (pushing grows the hand).
        if (palmSizeSmooth == 0f) {
            palmSizeSmooth = feat.palmSize
        } else if (touch == Touch.IDLE && push.proximity < 0.2f) {
            palmSizeSmooth += (feat.palmSize - palmSizeSmooth) * (1f - exp(-dt / REGION_TAU_MS))
        }
        region = PointerMap.region(palmSizeSmooth, aspect, cfg.safeTop, cfg.safeBottom, cfg.safeSide, cfg.pointerGain)
        regionAspect = aspect
        val m = PointerMap.map(feat.palmCenter, region)
        edge = m.edge
        val first = pointerHistory.isEmpty()
        px = fx.filter((m.u * cfg.screenW).toDouble(), t).toFloat()
        py = fy.filter((m.v * cfg.screenH).toDouble(), t).toFloat()
        if (first) { prevPx = px; prevPy = py; ox = px; oy = py }
        // Jitter: second difference of the palm position (smooth motion ≈ 0, landmark noise > 0).
        if (palmHistory.size >= 2) {
            val a = palmHistory[palmHistory.size - 2]
            val b = palmHistory.last()
            val jx = feat.palmCenter.x - 2 * b.x + a.x
            val jy = feat.palmCenter.y - 2 * b.y + a.y
            val j = sqrt(jx * jx + jy * jy) / feat.palmSize.coerceAtLeast(1e-4f)
            jitter += (j - jitter) * 0.3f
        }
        palmHistory.addLast(Sample(t, feat.palmCenter.x, feat.palmCenter.y))
        trim(palmHistory, t)
        palmSpeed = speedOf(palmHistory, t) / feat.palmSize.coerceAtLeast(1e-4f)
    }

    /**
     * The output pointer: follows the palm pointer, except that approaching the air plane slows it
     * down (so pushing does not drag the aim) and a push touch moves it at [EngineConfig.pushDragGain].
     * The offset this creates melts away once the hand is back from the plane.
     */
    private fun updateAim(t: Long, proximity: Float) {
        val cfg = config
        val dx = px - prevPx
        val dy = py - prevPy
        prevPx = px; prevPy = py
        when {
            touchSource == TouchSource.PUSH -> { ox += dx * cfg.pushDragGain; oy += dy * cfg.pushDragGain }
            touchSource == TouchSource.TAP -> { ox += dx; oy += dy }
            proximity >= cfg.pushMagnetFrom -> {
                val k = ((proximity - cfg.pushMagnetFrom) / (1f - cfg.pushMagnetFrom).coerceAtLeast(0.05f)).coerceIn(0f, 1f)
                val g = 1f + (cfg.pushMagnetGain - 1f) * k
                ox += dx * g; oy += dy * g
            }
            else -> {
                ox += dx; oy += dy
                val k = 1f - exp(-(t - (pointerHistory.lastOrNull()?.t ?: t)).coerceIn(1L, 200L) / AIM_RETURN_TAU_MS)
                ox += (px - ox) * k; oy += (py - oy) * k
            }
        }
        ox = ox.coerceIn(0f, cfg.screenW.toFloat())
        oy = oy.coerceIn(0f, cfg.screenH.toFloat())
        pointerHistory.addLast(Sample(t, ox, oy))
        trim(pointerHistory, t)
    }

    /** Hand angle against the calibrated pose, and the pointer confidence (angle, steadiness, gaze, frame edge). */
    private fun updateConfidence(t: Long, f: HandFeatures) {
        val cfg = config
        val a = f.angleTo(cfg.handReference)
        angleDeg += (a - angleDeg) * 0.35f
        val warn = TapGate.smoothstep(angleDeg, cfg.angleToleranceDeg, cfg.angleToleranceDeg + cfg.angleBlockExtraDeg)
        angleWarn += (warn - angleWarn) * 0.35f
        var sum = 0.0
        var wsum = 0.0
        fun add(score: Float, w: Double) {
            sum += w * ln(score.coerceIn(0.02f, 1f).toDouble()); wsum += w
        }
        add(TapGate.angleScore(angleDeg, cfg), 2.0)
        add(1f - 0.8f * TapGate.smoothstep(jitter, 0.03f, 0.15f), 1.0)
        if (edge != 0) add(0.6f, 0.5)
        val att = attention.score
        if (cfg.attentionCalibration != null && att >= 0f && att != Attention.NEUTRAL) add(0.2f + 0.8f * att, 1.5)
        pointerConfidence = exp(sum / wsum).toFloat()
    }

    private fun onFlick(t: Long, dir: SwipeDir) {
        val cfg = config
        if (flickFreezeUntil == NEVER || t > flickFreezeUntil) {
            val back = lookback(t - FLICK_LOOKBACK_MS)
            flickX = back?.x ?: ox
            flickY = back?.y ?: oy
        }
        emit(ControlEvent.Swipe(dir))
        matcher.cancel()
        cancelBindProgress()
        push.reset()
        lastPoseT = t
        // Keep the pointer where it was before the flick until the hand settles.
        flickFreezeUntil = t + FLICK_FREEZE_MS
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
                lastPoseT = t
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

    /** 0 = thumb and index apart, 1 = touching. */
    private fun closing(feat: HandFeatures, cfg: EngineConfig): Float {
        if (touchSource == TouchSource.TAP) return 1f
        val far = CLOSING_FEEDBACK_FROM
        return ((far - feat.pinchRatio) / (far - cfg.tapEnter)).coerceIn(0f, 1f)
    }

    private fun contactStarts(obs: HandObservation, feat: HandFeatures, cfg: EngineConfig) =
        feat.pinchRatio < cfg.tapEnter &&
            feat.indexReach > cfg.tapMinIndexReach &&
            !(obs.gesture == Signs.CLOSED_FIST && obs.gestureScore > 0.7f)

    /**
     * The touch state machine shared by finger taps and air pushes. While disarmed ([live] false) it
     * only records taps for the preview.
     */
    private fun updateTouch(t: Long, obs: HandObservation, feat: HandFeatures, live: Boolean) {
        val cfg = config
        val w = cfg.screenW.toFloat()
        val h = cfg.screenH.toFloat()
        if (feat.pinchRatio > cfg.tapExit) tapRejected = false
        if (!push.pressed) pushRejected = false
        if (touch == Touch.IDLE) {
            val cooldown = cooling(t)
            when {
                cfg.tapClicks && !tapRejected && contactStarts(obs, feat, cfg) -> {
                    val v = gate.judge(t, angleDeg, attention.score, cooldown, push = false, cfg)
                    if (v.ok) {
                        push.release()
                        val back = lookback(t - cfg.touchLookbackMs)
                        begin(t, TouchSource.TAP, back?.x ?: ox, back?.y ?: oy)
                    } else {
                        tapRejected = true
                        if (live) emit(ControlEvent.ClickRejected("tap: ${v.reason}"))
                    }
                }
                live && push.pressed && cfg.pushClicks && !pushRejected -> {
                    val v = gate.judge(t, angleDeg, attention.score, cooldown, push = true, cfg)
                    if (v.ok) {
                        begin(t, TouchSource.PUSH, ox, oy)
                    } else {
                        pushRejected = true
                        emit(ControlEvent.ClickRejected("push: ${v.reason}"))
                    }
                }
            }
            return
        }
        val pressed = when (touchSource) {
            TouchSource.TAP -> feat.pinchRatio < cfg.tapExit
            else -> push.pressed
        }
        val tapMax = if (touchSource == TouchSource.PUSH) cfg.pushTapMaxMs else cfg.tapMaxMs
        val slop = if (touchSource == TouchSource.PUSH) cfg.dragSlopPx * cfg.pushDragGain else cfg.dragSlopPx
        when (touch) {
            Touch.IDLE -> {}
            Touch.PENDING -> when {
                !pressed -> {
                    val click = touchSource == TouchSource.TAP
                    finishTouch()
                    if (click) lastClickT = t
                    if (live) emit(ControlEvent.Tap(downX / w, downY / h, click = click))
                }
                hypot(dragX() - downX, dragY() - downY) > slop || t - downT > tapMax -> {
                    touch = Touch.DRAGGING
                    if (live) {
                        emit(ControlEvent.TouchDown(downX / w, downY / h))
                        emit(ControlEvent.TouchMove(dragX() / w, dragY() / h))
                    }
                }
            }
            Touch.DRAGGING -> if (!pressed) {
                if (live) emit(ControlEvent.TouchUp(dragX() / w, dragY() / h))
                finishTouch()
            } else if (live) {
                emit(ControlEvent.TouchMove(dragX() / w, dragY() / h))
            }
        }
    }

    private fun begin(t: Long, source: TouchSource, x: Float, y: Float) {
        touch = Touch.PENDING
        touchSource = source
        downT = t
        downX = x
        downY = y
        ox = x; oy = y
    }

    private fun finishTouch() {
        touch = Touch.IDLE
        touchSource = null
    }

    private fun dragX() = ox.coerceIn(0f, config.screenW.toFloat())
    private fun dragY() = oy.coerceIn(0f, config.screenH.toFloat())

    // ---------------------------------------------------------------- calibration

    private fun updateCalibration(t: Long, hand: TrackedHand?, face: FaceObservation?) {
        val kind = calibrating ?: return
        val dt = (t - calPrevT).coerceIn(0L, 200L)
        calPrevT = t
        if (calDoneLeft > 0L) {
            calDoneLeft -= dt
            if (calDoneLeft <= 0L) {
                calDoneLeft = 0L
                calReadyLeft = CAL_NEXT_POINT_MS
                emitCalibration(0f)
            }
            calStartT += dt
            return
        }
        if (calReadyLeft > 0L) {
            // The timeout only counts once measuring starts.
            calReadyLeft -= dt
            calStartT += dt
            if (calReadyLeft <= 0L) {
                calReadyLeft = 0L
                emitCalibration(0f)
            } else {
                maybeEmitCalibration(t, 0f)
            }
            return
        }
        if (t - calStartT > if (kind == CalibrationKind.PUSH) CAL_PUSH_TIMEOUT_MS else CAL_TIMEOUT_MS) {
            finishCalibration(kind, null, if (kind == CalibrationKind.ATTENTION) "No face seen" else "No steady hand seen")
            return
        }
        when (kind) {
            CalibrationKind.HAND -> {
                if (hand == null) return
                calStepMs += dt
                calNormals += hand.feat.canonicalNormal
                if (calStepMs >= CAL_HAND_MS) {
                    var x = 0f; var y = 0f; var z = 0f
                    for (n in calNormals) { x += n.x; y += n.y; z += n.z }
                    val len = sqrt(x * x + y * y + z * z)
                    if (len < 1e-3f) finishCalibration(kind, null, "Hand not seen clearly")
                    else finishCalibration(kind, floatArrayOf(x / len, y / len, z / len), "Hand angle calibrated")
                } else {
                    maybeEmitCalibration(t, calStepMs.toFloat() / CAL_HAND_MS)
                }
            }
            CalibrationKind.PUSH -> updatePushCalibration(t, dt, hand)
            CalibrationKind.ATTENTION -> {
                if (face == null) return
                calStepMs += dt
                calFaces += face
                if (calStepMs >= CAL_ATTENTION_MS) {
                    val c = Attention.calibrate(calFaces)
                    if (c == null) finishCalibration(kind, null, "Face not seen clearly")
                    else finishCalibration(kind, floatArrayOf(c.yaw, c.pitch, c.spread), "Gaze calibrated")
                } else {
                    maybeEmitCalibration(t, calStepMs.toFloat() / CAL_ATTENTION_MS)
                }
            }
        }
    }

    /**
     * Air plane at 5 points (centre, then the corners). At each: point at the dot and hold still
     * (hover depth), then push toward the screen and hold (plane depth).
     */
    private fun updatePushCalibration(t: Long, dt: Long, hand: TrackedHand?) {
        if (hand == null || !push.hasSignal) return
        val (tu, tv) = PushCalibration.TARGETS[calStep]
        val d = push.depth
        if (calPhase == 0) {
            val onTarget = hypot(cursorU - tu, (cursorV - tv) * 0.6f) < CAL_TARGET_RADIUS && palmSpeed < CAL_STILL_SPEED
            if (onTarget) {
                calStepMs += dt
                calValues += d
            } else {
                calStepMs = 0L
                calValues.clear()
            }
            if (calStepMs >= CAL_HOVER_MS) {
                calHover = calValues.sorted()[calValues.size / 2]
                calValues.clear()
                calPhase = 1
                calStepMs = 0L
                emitCalibration(0f)
            } else {
                maybeEmitCalibration(t, calStepMs.toFloat() / CAL_HOVER_MS)
            }
        } else {
            // Pushing: count only once the hand is clearly closer than the hover.
            if (d - calHover > CAL_PUSH_START) {
                calStepMs += dt
                calValues += d
            }
            if (calStepMs >= CAL_PRESS_MS) {
                val sorted = calValues.sorted()
                val plane = sorted[(sorted.size * 0.7f).toInt().coerceAtMost(sorted.size - 1)]
                if (plane - calHover < MIN_PUSH_DEPTH) {
                    calValues.clear()
                    calStepMs = 0L
                    emitCalibration(0f, "Push a little further toward the screen")
                    return
                }
                // The touch triggers a little before the full push, so it is reachable without straining.
                calResult += listOf(tu, tv, calHover, calHover + (plane - calHover) * 0.85f)
                calValues.clear()
                calStepMs = 0L
                calPhase = 0
                calStep++
                if (calStep >= PushCalibration.TARGETS.size) {
                    finishCalibration(CalibrationKind.PUSH, calResult.toFloatArray(), "Air push calibrated")
                } else {
                    // A short pause so the finished point can be seen before the next dot.
                    calDoneLeft = CAL_STEP_DONE_MS
                    emitCalibration(0f)
                }
            } else {
                maybeEmitCalibration(t, calStepMs.toFloat() / CAL_PRESS_MS)
            }
        }
    }

    private fun maybeEmitCalibration(t: Long, progress: Float) {
        if (t - calLastEmit > CAL_EMIT_MS) emitCalibration(progress)
    }

    private fun emitCalibration(progress: Float, override: String? = null) {
        val kind = calibrating ?: return
        calLastEmit = lastT
        val phase = when {
            calDoneLeft > 0L -> CalPhase.STEP_DONE
            calReadyLeft > 0L -> CalPhase.READY
            kind == CalibrationKind.PUSH && calPhase == 1 -> CalPhase.PUSH
            else -> CalPhase.HOLD
        }
        var tx = -1f
        var ty = -1f
        if (kind == CalibrationKind.PUSH) {
            // After a point, the finished one; otherwise the one to point at.
            val i = if (phase == CalPhase.STEP_DONE) calStep - 1 else calStep
            val (u, v) = PushCalibration.TARGETS[i.coerceIn(0, PushCalibration.TARGETS.size - 1)]
            tx = u; ty = v
        }
        val msg = override ?: when (kind) {
            CalibrationKind.HAND -> when (phase) {
                CalPhase.READY -> "Hold your hand up as you normally use it, palm toward the screen"
                else -> "Hold still"
            }
            CalibrationKind.ATTENTION -> when (phase) {
                CalPhase.READY -> "Look at the dot in the centre of the screen and keep looking"
                else -> "Keep looking at the dot"
            }
            CalibrationKind.PUSH -> when (phase) {
                CalPhase.READY -> if (calStep == 0) "At each dot: point at it and hold still, then push toward the screen as if touching it"
                    else "Move to the next dot"
                CalPhase.HOLD -> "Point at the dot and hold still"
                CalPhase.PUSH -> "Now push toward the screen and hold"
                CalPhase.STEP_DONE -> "Got it"
            }
        }
        val overall = if (kind == CalibrationKind.PUSH) {
            if (phase == CalPhase.HOLD || phase == CalPhase.PUSH) {
                (calStep + (calPhase + progress.coerceIn(0f, 1f)) / 2f) / PushCalibration.TARGETS.size
            } else calStep.toFloat() / PushCalibration.TARGETS.size
        } else if (phase == CalPhase.READY) 0f else progress
        emit(ControlEvent.Calibration(
            kind, calStep, overall.coerceIn(0f, 1f), msg, targetX = tx, targetY = ty,
            phase = phase, countdownMs = if (phase == CalPhase.READY) calReadyLeft else 0L,
            stepCount = stepCount(kind), sequenceIndex = calSeqIndex, sequenceSize = calSeqSize,
        ))
    }

    private fun stepCount(kind: CalibrationKind) = if (kind == CalibrationKind.PUSH) PushCalibration.TARGETS.size else 1

    private fun finishCalibration(kind: CalibrationKind, result: FloatArray?, msg: String) {
        calibrating = null
        emit(ControlEvent.Calibration(kind, calStep, 1f, msg, done = true, result = result,
            stepCount = stepCount(kind), sequenceIndex = calSeqIndex, sequenceSize = calSeqSize))
    }

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

    private fun onHandLost(t: Long, aspect: Float) {
        flick.onHandLost(t, aspect, config)?.let { onFlick(t, it) }
        handPresent = false
        endTouch()
        resetHandTracking()
        if (pointerVisible) {
            pointerVisible = false
            emit(ControlEvent.Pointer(cursorU, cursorV, visible = false, touching = false))
        }
        matcher.cancel()
        cancelBindProgress()
    }

    private fun resetHandTracking() {
        fx.reset(); fy.reset()
        palmHistory.clear(); pointerHistory.clear()
        gate.reset()
        push.reset()
        flick.reset()
        palmSizeSmooth = 0f
        jitter = 0f
        activeCenter = null
    }

    /** Lifts an active drag (a pending tap is dropped). */
    private fun endTouch() {
        if (touch == Touch.DRAGGING) {
            val cfg = config
            emit(ControlEvent.TouchUp(dragX() / cfg.screenW, dragY() / cfg.screenH))
        }
        touch = Touch.IDLE
        touchSource = null
        push.release()
    }

    private fun resetHandState() {
        handPresent = false
        pointerVisible = false
        touch = Touch.IDLE
        touchSource = null
        holdFreezeStart = NEVER
        flickFreezeUntil = NEVER
        tapRejected = false
        pushRejected = false
        resetHandTracking()
    }

    private fun lookback(target: Long): Sample? {
        var best: Sample? = null
        for (s in pointerHistory) {
            if (s.t <= target) best = s else break
        }
        return best ?: pointerHistory.firstOrNull()
    }

    /** Speed over the last ~[SPEED_WINDOW_MS] in history units per second. */
    private fun speedOf(q: ArrayDeque<Sample>, t: Long): Float {
        if (q.size < 2) return 0f
        val last = q.last()
        var ref = q.first()
        for (i in q.indices.reversed()) {
            val s = q[i]
            if (t - s.t >= SPEED_WINDOW_MS) { ref = s; break }
        }
        if (ref === last) return 0f
        val dt = (last.t - ref.t).coerceAtLeast(1L)
        return hypot(last.x - ref.x, last.y - ref.y) * 1000f / dt
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
        val r = region
        val a = regionAspect.coerceAtLeast(0.1f)
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
            gap = f?.pinchRatio ?: 0f,
            tapEnter = cfg.tapEnter,
            tapExit = cfg.tapExit,
            touch = touch.name,
            clickAgoMs = if (lastClickT == NEVER) Long.MAX_VALUE else t - lastClickT,
            clickConfidence = gate.confidence,
            clickFactors = gate.factors.copyOf(),
            pointerConfidence = if (hand != null) pointerConfidence else 0f,
            angleDeg = if (hand != null) angleDeg else 0f,
            angleTolDeg = cfg.angleToleranceDeg,
            push = if (hand != null) push.proximity else 0f,
            pushCalibrated = cfg.pushCalibration != null,
            flickReady = hand != null && flick.ready,
            palmSpeed = if (hand != null) palmSpeed else 0f,
            attention = attention.score,
            cooldown = cooling(t),
            region = floatArrayOf(r.left / a, r.top, r.right / a, r.bottom),
            edge = if (hand != null) edge else 0,
            touchSource = touchSource,
            bindLabel = bindShown,
            bindProgress = bindProgressNow,
            bodyVisible = body.bodyVisible,
        )
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4
        const val HISTORY_MS = 600L
        const val HOLD_FREEZE_LOOKBACK_MS = 150L
        const val SPEED_WINDOW_MS = 90L
        const val REGION_TAU_MS = 800f
        const val AIM_RETURN_TAU_MS = 150f
        const val FLICK_FREEZE_MS = 350L
        const val FLICK_LOOKBACK_MS = 150L
        /** Closing feedback starts when thumb and index are this close (palm sizes). */
        const val CLOSING_FEEDBACK_FROM = 0.9f
        const val CAL_TIMEOUT_MS = 15_000L
        const val CAL_PUSH_TIMEOUT_MS = 90_000L
        const val CAL_HAND_MS = 2000L
        const val CAL_ATTENTION_MS = 3000L
        const val CAL_HOVER_MS = 800L
        const val CAL_PRESS_MS = 900L
        /** Time to read the instruction before a calibration measures anything. */
        const val CAL_READY_MS = 3000L
        /** Air plane: the pause after a point (it shows as done), then the time to move to the next dot. */
        const val CAL_STEP_DONE_MS = 700L
        const val CAL_NEXT_POINT_MS = 1000L
        /** Cursor within this distance (normalized screen) of the dot counts as pointing at it. */
        const val CAL_TARGET_RADIUS = 0.14f
        const val CAL_STILL_SPEED = 1.2f
        /** ln(scale) growth above the hover that counts as pushing during the calibration. */
        const val CAL_PUSH_START = 0.035f
        const val CAL_EMIT_MS = 100L
        const val MIN_PUSH_DEPTH = 0.05f
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
