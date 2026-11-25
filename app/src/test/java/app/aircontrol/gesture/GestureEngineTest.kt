package app.aircontrol.gesture

import app.aircontrol.gesture.SyntheticHands.Shape
import app.aircontrol.gesture.SyntheticHands.hand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {

    // ------------------------------------------------------------ arming

    @Test
    fun holdingILoveYouForOneSecondArms() {
        val h = EngineHarness()
        h.hold(1100) { hand(0.66f, 0.5f, gesture = Signs.I_LOVE_YOU) }
        assertTrue(h.engine.armed)
        assertEquals(1, h.of<ControlEvent.Armed>().size)
        val progress = h.of<ControlEvent.ArmProgress>().map { it.progress }
        assertTrue("progress should grow", progress.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(progress.last() >= 0.99f)
    }

    @Test
    fun shortHoldDoesNotArmAndCancelsProgress() {
        val h = EngineHarness()
        h.hold(600) { hand(0.66f, 0.5f, gesture = Signs.I_LOVE_YOU) }
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        assertFalse(h.engine.armed)
        assertTrue(h.of<ControlEvent.Armed>().isEmpty())
        assertEquals(0f, h.of<ControlEvent.ArmProgress>().last().progress)
    }

    @Test
    fun requireBodyArmsWhenTheBodyIsSeenWhileDisarmed() {
        // The pipeline must keep running pose while disarmed in this mode (VisionPipeline.poseWhileDisarmed).
        val h = EngineHarness(EngineConfig(screenW = 1000, screenH = 2000, density = 2f, requireBody = true))
        h.hold(1100, pose = SyntheticHands.pose(0.5f, 0.75f, 0.35f)) { hand(0.66f, 0.5f, gesture = Signs.I_LOVE_YOU) }
        assertTrue(h.engine.armed)
    }

    @Test
    fun requireBodyBlocksArmingWithoutAVisibleBody() {
        val h = EngineHarness(EngineConfig(screenW = 1000, screenH = 2000, density = 2f, requireBody = true))
        h.hold(1100) { hand(0.66f, 0.5f, gesture = Signs.I_LOVE_YOU) }
        assertFalse(h.engine.armed)
    }

    @Test
    fun briefClassificationDropoutDoesNotBreakTheHold() {
        val h = EngineHarness()
        h.hold(1300) { ms ->
            val dropout = ms in 400..480
            hand(0.66f, 0.5f, gesture = if (dropout) Signs.NONE else Signs.I_LOVE_YOU)
        }
        assertTrue(h.engine.armed)
    }

    @Test
    fun otherSignsDoNothingWhileDisarmed() {
        val h = EngineHarness()
        h.hold(2000) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST) }
        h.hold(2000) { hand(0.66f, 0.5f, Shape.PINCH) }
        assertFalse(h.engine.armed)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun keepingTheSignAfterArmingDoesNotDisarm_releaseThenHoldDoes() {
        val h = EngineHarness()
        h.hold(2600) { hand(0.66f, 0.5f, gesture = Signs.I_LOVE_YOU) }
        assertTrue("continuous hold must not toggle back", h.engine.armed)
        h.hold(400) { hand(0.66f, 0.5f, Shape.POINT) }
        h.hold(1100) { hand(0.66f, 0.5f, gesture = Signs.I_LOVE_YOU) }
        assertFalse(h.engine.armed)
        assertEquals(DisarmReason.GESTURE, h.of<ControlEvent.Disarmed>().single().reason)
    }

    @Test
    fun noHandForTwentySecondsDisarms() {
        val h = EngineHarness()
        h.arm()
        h.hold(19_000) { null }
        assertTrue(h.engine.armed)
        h.hold(1_500) { null }
        assertFalse(h.engine.armed)
        assertEquals(DisarmReason.TIMEOUT, h.of<ControlEvent.Disarmed>().single().reason)
    }

    @Test
    fun handDisappearingHidesThePointer() {
        val h = EngineHarness()
        h.arm()
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT) }
        assertTrue(h.of<ControlEvent.Pointer>().last().visible)
        h.hold(600) { null }
        assertFalse(h.of<ControlEvent.Pointer>().last().visible)
    }

    // ------------------------------------------------------------ pinch / touch

    // ------------------------------------------------------------ finger tap: thumb meets index

    /** Thumb and index touch for [touchMs], from an open hand (or a pointing hand with [pointing]). */
    private fun EngineHarness.tap(touchMs: Long = 120, x: Float = 0.66f, y: Float = 0.5f, pointing: Boolean = false, yawDeg: Float = 0f) {
        val open = if (pointing) Shape.POINT else Shape.OPEN
        val closed = if (pointing) Shape.POINT_PINCH else Shape.PINCH
        hold(touchMs) { hand(x, y, closed, yawDeg = yawDeg) }
        hold(300) { hand(x, y, open, yawDeg = yawDeg) }
    }

    private fun EngineHarness.clicks() = of<ControlEvent.Tap>().filter { it.click }

    @Test
    fun aQuickTouchIsAClickWhereTheCursorWas() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.OPEN) }
        val before = h.of<ControlEvent.Pointer>().last()
        h.tap()
        val c = h.clicks().single()
        assertEquals(before.x, c.x, 0.01f)
        assertEquals(before.y, c.y, 0.01f)
        assertTrue(h.of<ControlEvent.TouchDown>().isEmpty())
    }

    @Test
    fun aPointingHandTapsToo() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.tap(pointing = true)
        assertEquals(1, h.clicks().size)
    }

    @Test
    fun separateTapsEachClick() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.tap()
        h.hold(300) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.tap()
        assertEquals(2, h.clicks().size)
    }

    @Test
    fun touchMoveReleaseIsADrag() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.60f, 0.5f, Shape.OPEN) }
        h.hold(100) { hand(0.60f, 0.5f, Shape.PINCH) }
        h.hold(600) { ms -> hand(0.60f + 0.15f * ms / 600f, 0.5f, Shape.PINCH) }
        h.hold(200) { hand(0.75f, 0.5f, Shape.OPEN) }
        assertEquals(1, h.of<ControlEvent.TouchDown>().size)
        assertTrue(h.of<ControlEvent.TouchMove>().size > 3)
        val down = h.of<ControlEvent.TouchDown>().single()
        val up = h.of<ControlEvent.TouchUp>().single()
        assertTrue("dragged right", up.x - down.x > 0.05f)
        assertTrue(h.clicks().isEmpty())
    }

    @Test
    fun touchHeldStillPressesAndHolds() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.hold(900) { hand(0.66f, 0.5f, Shape.PINCH) }
        assertEquals(1, h.of<ControlEvent.TouchDown>().size)
        assertTrue(h.of<ControlEvent.TouchUp>().isEmpty())
        assertTrue(h.of<ControlEvent.Pointer>().last().dragging)
        h.hold(200) { hand(0.66f, 0.5f, Shape.OPEN) }
        assertEquals(1, h.of<ControlEvent.TouchUp>().size)
    }

    @Test
    fun hysteresisIgnoresASmallOpeningWhileHolding() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.hold(500) { hand(0.66f, 0.5f, Shape.PINCH) }
        h.hold(200) { hand(0.66f, 0.5f, Shape.PINCH, pinchRatio = 0.38f) }
        assertTrue(h.of<ControlEvent.TouchUp>().isEmpty())
    }

    @Test
    fun losingTheHandMidDragLiftsTheTouch() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.hold(600) { hand(0.66f, 0.5f, Shape.PINCH) }
        h.hold(800) { null }
        assertEquals(1, h.of<ControlEvent.TouchUp>().size)
    }

    @Test
    fun aFistIsNotATap() {
        val h = EngineHarness(configWith())
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.hold(150) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST) }
        h.hold(800) { hand(0.66f, 0.5f, Shape.OPEN) }
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
    }

    @Test
    fun fingersDriftingTogetherSlowlyAreNotAClick() {
        val h = EngineHarness(EngineConfig(screenW = 1000, screenH = 2000, density = 2f, clickStrictness = ClickStrictness.STRICT))
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.PINCH, pinchRatio = 0.5f) }
        h.hold(800) { ms -> hand(0.66f, 0.5f, Shape.PINCH, pinchRatio = 0.5f - 0.35f * ms / 800f) }
        h.hold(300) { hand(0.66f, 0.5f, Shape.OPEN) }
        assertTrue(h.clicks().isEmpty())
        assertTrue(h.of<ControlEvent.ClickRejected>().any { it.reason.contains("approach") })
    }

    @Test
    fun aTapWhileTheHandSweepsIsRejectedWhenStrict() {
        val h = EngineHarness(EngineConfig(screenW = 1000, screenH = 2000, density = 2f, clickStrictness = ClickStrictness.STRICT))
        h.arm()
        h.hold(400) { hand(0.4f, 0.5f, Shape.OPEN) }
        h.hold(200) { ms -> hand(0.4f + 0.1f * ms / 200f, 0.5f, Shape.OPEN) }
        h.hold(120) { ms -> hand(0.5f + 0.06f * ms / 120f, 0.5f, Shape.PINCH) }
        h.hold(300) { hand(0.56f, 0.5f, Shape.OPEN) }
        assertTrue(h.clicks().isEmpty())
    }

    @Test
    fun aCleanTapHasHighConfidence() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.tap()
        assertEquals(1, h.clicks().size)
        assertTrue("confidence ${h.engine.debug.clickConfidence}", h.engine.debug.clickConfidence > 0.8f)
    }

    @Test
    fun tapsCanBeTurnedOff() {
        val h = EngineHarness(EngineConfig(screenW = 1000, screenH = 2000, density = 2f, clickMethod = ClickMethod.PUSH))
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.tap()
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
        assertTrue(h.of<ControlEvent.TouchDown>().isEmpty())
    }

    @Test
    fun aTapIsSeenButNotPerformedWhileDisarmed() {
        val h = EngineHarness()
        h.hold(600) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.tap()
        assertFalse(h.engine.armed)
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
        assertTrue("the preview still sees the click", h.engine.debug.clickAgoMs < 1000)
    }

    // ------------------------------------------------------------ pause after a pose

    @Test
    fun goingBackFromAPoseDoesNotClick() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.OPEN) }
        // A fist (bound to Home) shown briefly, then straight into a touch while the hand reopens.
        h.hold(400) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST) }
        h.hold(150) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.tap()
        assertTrue(h.clicks().isEmpty())
        assertTrue(h.of<ControlEvent.ClickRejected>().any { it.reason.contains("right after a pose") })
        assertTrue(h.of<ControlEvent.Pointer>().any { it.cooldown })
        // Once the pause has passed, taps work again.
        h.hold(600) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.tap()
        assertEquals(1, h.clicks().size)
    }

    @Test
    fun aBindFiringPausesTapsAndSwipes() {
        val h = EngineHarness()
        h.arm()
        h.hold(1000) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_DOWN) }
        assertEquals(listOf(SystemAction.BACK), h.fired())
        h.hold(100) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.tap()
        assertTrue(h.clicks().isEmpty())
    }

    @Test
    fun anUnboundPoseDoesNotPause() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.OPEN) }
        // Thumb up has no default bind.
        h.hold(300) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_UP) }
        h.hold(150) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.tap()
        assertEquals(1, h.clicks().size)
    }

    // ------------------------------------------------------------ hand angle and pointer confidence

    @Test
    fun aPalmTurnedFarAwayCannotClickAndWarns() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.OPEN, yawDeg = 65f) }
        h.tap(yawDeg = 65f)
        assertTrue(h.clicks().isEmpty())
        assertTrue(h.of<ControlEvent.ClickRejected>().any { it.reason.startsWith("tap: palm not facing the camera") })
        val p = h.of<ControlEvent.Pointer>().last()
        assertTrue("warning ${p.angleWarn}", p.angleWarn > 0.8f)
        assertTrue("confidence ${p.confidence}", p.confidence < 0.3f)
    }

    @Test
    fun aSlightlyTurnedPalmIsFine() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.OPEN, yawDeg = 15f) }
        h.tap(yawDeg = 15f)
        assertEquals(1, h.clicks().size)
        val p = h.of<ControlEvent.Pointer>().last()
        assertEquals(0f, p.angleWarn, 0.05f)
        assertTrue(p.confidence > 0.9f)
    }

    @Test
    fun theHandCalibrationSetsTheGoodAngle() {
        val h = EngineHarness()
        h.hold(300) { hand(0.66f, 0.5f, Shape.OPEN, yawDeg = 35f) }
        h.engine.startCalibration(CalibrationKind.HAND)
        h.hold(2300) { hand(0.66f, 0.5f, Shape.OPEN, yawDeg = 35f) }
        val r = h.of<ControlEvent.Calibration>().last { it.done }.result!!
        val ref = P3(r[0], r[1], r[2])
        val turned = HandFeatures.from(hand(0.66f, 0.5f, Shape.OPEN, yawDeg = 35f), SyntheticHands.ASPECT)
        val facing = HandFeatures.from(hand(0.66f, 0.5f, Shape.OPEN), SyntheticHands.ASPECT)
        assertEquals(0f, turned.angleTo(ref), 2f)
        assertEquals(35f, facing.angleTo(ref), 3f)
        // With that reference, the turned hand clicks and the facing one is still within tolerance.
        val cfg = EngineConfig(screenW = 1000, screenH = 2000, density = 2f, handReference = ref)
        val h2 = EngineHarness(cfg)
        h2.arm()
        h2.hold(500) { hand(0.66f, 0.5f, Shape.OPEN, yawDeg = 35f) }
        h2.tap(yawDeg = 35f)
        assertEquals(1, h2.clicks().size)
        assertTrue(h2.of<ControlEvent.Pointer>().last().confidence > 0.9f)
    }

    @Test
    fun theAngleIgnoresWhichHandItIs() {
        val right = HandFeatures.from(hand(0.66f, 0.5f, Shape.OPEN, yawDeg = 30f), SyntheticHands.ASPECT)
        val mirrored = HandFeatures.from(hand(0.66f, 0.5f, Shape.OPEN, yawDeg = -30f), SyntheticHands.ASPECT)
        assertEquals(right.angleTo(null), mirrored.angleTo(null), 0.5f)
        assertEquals(30f, right.angleTo(null), 2f)
    }

    // ------------------------------------------------------------ attention

    private val lookingCal = AttentionCalibration(0f, 0f, 10f)

    @Test
    fun withTheAttentionGateATapNeedsTheUserLooking() {
        val cfg = EngineConfig(screenW = 1000, screenH = 2000, density = 2f, attentionGate = true, attentionCalibration = lookingCal)
        val away = EngineHarness(cfg)
        away.face = FaceObservation(40f, 0f, 0f, 0f)
        away.arm()
        away.hold(500) { hand(0.66f, 0.5f, Shape.OPEN) }
        away.tap()
        assertTrue(away.clicks().isEmpty())
        assertTrue(away.of<ControlEvent.ClickRejected>().any { it.reason.contains("not looking at the screen") })

        val looking = EngineHarness(cfg)
        looking.face = FaceObservation(2f, -1f, 1f, 0f)
        looking.arm()
        looking.hold(500) { hand(0.66f, 0.5f, Shape.OPEN) }
        looking.tap()
        assertEquals(1, looking.clicks().size)
    }

    @Test
    fun withoutAFaceTheGateNeitherBlocksNorConfirms() {
        val cfg = EngineConfig(screenW = 1000, screenH = 2000, density = 2f, attentionGate = true, attentionCalibration = lookingCal)
        val h = EngineHarness(cfg)
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.tap()
        assertEquals(1, h.clicks().size)
        assertEquals(null, h.of<ControlEvent.Pointer>().last().looking)
    }

    @Test
    fun thePointerShowsWhenTheEyesAreOnTheScreen() {
        val cfg = EngineConfig(screenW = 1000, screenH = 2000, density = 2f, attentionCalibration = lookingCal)
        val h = EngineHarness(cfg)
        h.face = FaceObservation(1f, 0f, 0f, 0f)
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN) }
        assertEquals(true, h.of<ControlEvent.Pointer>().last().looking)
        val looking = h.of<ControlEvent.Pointer>().last().confidence
        h.face = FaceObservation(45f, 10f, 0f, 0f)
        h.hold(600) { hand(0.66f, 0.5f, Shape.OPEN) }
        val away = h.of<ControlEvent.Pointer>().last()
        assertEquals(false, away.looking)
        assertTrue("gaze lowers confidence", away.confidence < looking - 0.2f)
    }

    @Test
    fun attentionCalibrationMeasuresTheGaze() {
        val h = EngineHarness()
        h.face = FaceObservation(5f, -3f, 1f, 0f)
        h.hold(200) { hand(0.66f, 0.5f, Shape.POINT) }
        h.engine.startCalibration(CalibrationKind.ATTENTION)
        h.hold(3300) { hand(0.66f, 0.5f, Shape.POINT) }
        val done = h.of<ControlEvent.Calibration>().last()
        assertTrue(done.done)
        val r = done.result!!
        assertEquals(6f, r[0], 0.1f)
        assertEquals(-3f, r[1], 0.1f)
    }

    // ------------------------------------------------------------ air push

    /** Hand grows from palm size [s0] to [s1] in [inMs], holds, and shrinks back. */
    private fun EngineHarness.push(
        s0: Float = 0.1f, s1: Float = 0.118f, inMs: Long = 250, holdMs: Long = 100, outMs: Long = 250,
        x: (Long) -> Float = { 0.66f }, shape: Shape = Shape.POINT,
    ) {
        val base = t
        hold(inMs) { ms -> hand(x(t - base), 0.5f, shape, s = s0 + (s1 - s0) * (ms.toFloat() / inMs).coerceAtMost(1f)) }
        hold(holdMs) { hand(x(t - base), 0.5f, shape, s = s1) }
        hold(outMs) { ms -> hand(x(t - base), 0.5f, shape, s = s1 + (s0 - s1) * (ms.toFloat() / outMs).coerceAtMost(1f)) }
        hold(400) { hand(x(t - base), 0.5f, shape, s = s0) }
    }

    @Test
    fun pushingThroughTheFloatingPlaneTaps() {
        val h = EngineHarness()
        h.arm()
        h.hold(1500) { hand(0.66f, 0.5f, Shape.POINT) }
        val before = h.of<ControlEvent.Pointer>().last()
        h.push()
        val taps = h.of<ControlEvent.Tap>()
        assertEquals(1, taps.size)
        assertFalse(taps[0].click)
        assertEquals(before.x, taps[0].x, 0.02f)
        assertTrue("proximity reported", h.of<ControlEvent.Pointer>().any { it.push > 0.5f })
    }

    @Test
    fun aShallowPushOnlyGlows() {
        val h = EngineHarness()
        h.arm()
        h.hold(1500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.push(s1 = 0.108f)
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
        assertTrue(h.of<ControlEvent.Pointer>().any { it.push > 0.3f })
    }

    @Test
    fun slowlyComingCloserDoesNotPressTheFloatingPlane() {
        val h = EngineHarness()
        h.arm()
        h.hold(1500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.hold(4000) { ms -> hand(0.66f, 0.5f, Shape.POINT, s = 0.1f + 0.02f * ms / 4000f) }
        h.hold(1000) { hand(0.66f, 0.5f, Shape.POINT, s = 0.12f) }
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
        assertTrue(h.of<ControlEvent.TouchDown>().isEmpty())
    }

    @Test
    fun aCalibratedPlaneIsFixedToTheCamera() {
        // Hover at palm size 0.1, touch at 0.112 everywhere.
        val cal = PushCalibration(PushCalibration.TARGETS.map { (u, v) -> PushPoint(u, v, depthAt(0.1f), depthAt(0.112f)) })
        val cfg = EngineConfig(screenW = 1000, screenH = 2000, density = 2f, pushCalibration = cal)
        val h = EngineHarness(cfg)
        h.arm()
        // Coming slowly closer reaches the plane all the same: it does not move with the hand.
        h.hold(800) { hand(0.66f, 0.5f, Shape.POINT) }
        h.hold(3000) { ms -> hand(0.66f, 0.5f, Shape.POINT, s = 0.1f + 0.016f * ms / 3000f) }
        assertEquals(1, h.of<ControlEvent.TouchDown>().size + h.of<ControlEvent.Tap>().size)
        // Staying farther back than the hover never presses.
        val h2 = EngineHarness(cfg)
        h2.arm()
        h2.hold(800) { hand(0.66f, 0.5f, Shape.POINT, s = 0.09f) }
        h2.push(s0 = 0.09f, s1 = 0.1f)
        assertTrue(h2.of<ControlEvent.Tap>().isEmpty())
    }

    @Test
    fun pushHoldAndMoveDragsSlowly() {
        val h = EngineHarness()
        h.arm()
        h.hold(1500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.push(holdMs = 900, x = { ms -> if (ms < 300) 0.66f else 0.66f + 0.15f * ((ms - 300) / 600f).coerceAtMost(1f) })
        assertEquals(1, h.of<ControlEvent.TouchDown>().size)
        val down = h.of<ControlEvent.TouchDown>().single()
        val up = h.of<ControlEvent.TouchUp>().single()
        val moved = up.x - down.x
        // The palm moved 0.15 / 1.2 ≈ 12.5 % of the screen; at the default drag speed (0.35×) the touch moves far less.
        assertTrue("dragged right a little: $moved", moved > 0.01f && moved < 0.07f)
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
    }

    @Test
    fun airPushCanBeTurnedOff() {
        val h = EngineHarness(EngineConfig(screenW = 1000, screenH = 2000, density = 2f, clickMethod = ClickMethod.TAP))
        h.arm()
        h.hold(1500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.push()
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
    }

    @Test
    fun aPushRightAfterAPoseIsIgnored() {
        val h = EngineHarness()
        h.arm()
        h.hold(1500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.hold(300) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST) }
        h.push()
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
    }

    /** The air-push depth signal (ln of the rigid palm-bone scale) of a hand with palm size [s]. */
    private fun depthAt(s: Float) = kotlin.math.ln(HandFeatures.from(hand(0.66f, 0.5f, Shape.POINT, s = s), SyntheticHands.ASPECT).scale)

    /** Palm-centre position whose cursor lands on screen point ([u], [v]). */
    private fun palmFor(u: Float, v: Float): Pair<Float, Float> {
        val r = safeRegion()
        return (r.left + u * r.width) to (r.top + v * r.height)
    }

    @Test
    fun thePushCalibrationMeasuresFivePoints() {
        val h = EngineHarness()
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT) }
        h.engine.startCalibration(CalibrationKind.PUSH)
        for ((u, v) in PushCalibration.TARGETS) {
            val (x, y) = palmFor(u, v)
            h.hold(1400) { hand(x, y, Shape.POINT) }
            h.hold(250) { ms -> hand(x, y, Shape.POINT, s = 0.1f + 0.015f * (ms / 250f).coerceAtMost(1f)) }
            h.hold(1300) { hand(x, y, Shape.POINT, s = 0.115f) }
            h.hold(300) { hand(x, y, Shape.POINT) }
        }
        val done = h.of<ControlEvent.Calibration>().last { it.done }
        val cal = PushCalibration.fromResult(done.result!!)
        assertEquals(5, cal.points.size)
        val (hover, plane) = cal.at(0.5f, 0.5f)
        assertEquals(depthAt(0.1f), hover, 0.01f)
        assertEquals(kotlin.math.ln(1.15f) * 0.85f, plane - hover, 0.02f)
        assertTrue(h.of<ControlEvent.Calibration>().any { it.targetX == 0.85f && it.targetY == 0.85f })
    }

    @Test
    fun thePushCalibrationWaitsForTheHandOnTheDot() {
        val h = EngineHarness()
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT) }
        h.engine.startCalibration(CalibrationKind.PUSH)
        // The centre dot, but the hand stays in a corner: no progress.
        val (x, y) = palmFor(0.1f, 0.9f)
        h.hold(3000) { hand(x, y, Shape.POINT) }
        assertTrue(h.of<ControlEvent.Calibration>().none { it.done })
        assertTrue(h.of<ControlEvent.Calibration>().last().progress < 0.05f)
    }

    // ------------------------------------------------------------ swipes: open-palm flick

    /** Open palm held still, then moved by ([dx], [dy]) in [ms], then held still. */
    private fun EngineHarness.flick(fromX: Float, fromY: Float, dx: Float, dy: Float, ms: Long = 130, shape: Shape = Shape.OPEN, stillMs: Long = 300) {
        hold(stillMs) { hand(fromX, fromY, shape) }
        hold(ms) { t -> val k = (t.toFloat() / ms).coerceAtMost(1f); hand(fromX + dx * k, fromY + dy * k, shape) }
        hold(250) { hand(fromX + dx, fromY + dy, shape) }
    }

    private fun EngineHarness.swipes() = of<ControlEvent.Swipe>().map { it.dir }

    @Test
    fun stillOpenPalmThenFlickSwipesInEachDirection() {
        for ((d, dir) in listOf(
            (0.3f to 0f) to SwipeDir.RIGHT, (-0.3f to 0f) to SwipeDir.LEFT,
            (0f to -0.3f) to SwipeDir.UP, (0f to 0.3f) to SwipeDir.DOWN,
        )) {
            val h = EngineHarness()
            h.arm()
            h.flick(0.66f, 0.5f, d.first, d.second)
            assertEquals("flick $dir", listOf(dir), h.swipes())
        }
    }

    @Test
    fun aFlickDoesNotClickOrTouch() {
        val h = EngineHarness()
        h.arm()
        h.flick(0.66f, 0.5f, 0.3f, 0f)
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
        assertTrue(h.of<ControlEvent.TouchDown>().isEmpty())
    }

    @Test
    fun theCursorStaysPutDuringAFlick() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN) }
        val before = h.of<ControlEvent.Pointer>().last()
        h.events.clear()
        h.hold(130) { t -> hand(0.66f + 0.3f * (t / 130f).coerceAtMost(1f), 0.5f, Shape.OPEN) }
        h.hold(150) { hand(0.96f, 0.5f, Shape.OPEN) }
        assertEquals(listOf(SwipeDir.RIGHT), h.swipes())
        val after = h.of<ControlEvent.Pointer>().last()
        assertEquals(before.x, after.x, 0.03f)
    }

    @Test
    fun pointingHandMovingFastIsNotASwipe() {
        val h = EngineHarness()
        h.arm()
        h.flick(0.66f, 0.5f, 0.3f, 0f, shape = Shape.POINT)
        assertTrue(h.swipes().isEmpty())
    }

    @Test
    fun slowOpenPalmMoveIsNotASwipe() {
        val h = EngineHarness()
        h.arm()
        h.flick(0.66f, 0.5f, 0.3f, 0f, ms = 900)
        assertTrue(h.swipes().isEmpty())
    }

    @Test
    fun openPalmAlwaysMovingIsNotASwipe() {
        val h = EngineHarness()
        h.arm()
        // Never still: a steady sweep, then faster.
        h.hold(1200) { ms -> hand(0.3f + 0.4f * ms / 1200f, 0.5f, Shape.OPEN) }
        h.hold(130) { ms -> hand(0.7f + 0.3f * ms / 130f, 0.5f, Shape.OPEN) }
        assertTrue(h.swipes().isEmpty())
    }

    @Test
    fun smallFlickIsNotASwipe() {
        val h = EngineHarness()
        h.arm()
        h.flick(0.66f, 0.5f, 0.12f, 0f)
        assertTrue(h.swipes().isEmpty())
    }

    @Test
    fun diagonalFlickIsIgnored() {
        val h = EngineHarness()
        h.arm()
        h.flick(0.66f, 0.5f, 0.25f, 0.22f)
        assertTrue(h.swipes().isEmpty())
    }

    @Test
    fun theReturnStrokeIsIgnoredButTheNextFlickWorks() {
        val h = EngineHarness()
        h.arm()
        h.flick(0.5f, 0.5f, 0.3f, 0f)
        // Quick return.
        h.flick(0.8f, 0.5f, -0.3f, 0f, stillMs = 150)
        h.hold(700) { hand(0.5f, 0.5f, Shape.OPEN) }
        h.flick(0.5f, 0.5f, 0.3f, 0f)
        assertEquals(listOf(SwipeDir.RIGHT, SwipeDir.RIGHT), h.swipes())
    }

    @Test
    fun aFlickLeavingTheFrameStillCounts() {
        val h = EngineHarness()
        h.arm()
        val a = SyntheticHands.ASPECT
        h.hold(300) { hand(a - 0.35f, 0.5f, Shape.OPEN) }
        // Fast toward the right edge, then the hand is gone (motion blur / out of view).
        h.hold(66) { ms -> hand(a - 0.35f + 0.22f * ms / 66f, 0.5f, Shape.OPEN) }
        h.hold(500) { null }
        assertEquals(listOf(SwipeDir.RIGHT), h.swipes())
    }

    @Test
    fun swipesCanBeTurnedOff() {
        val h = EngineHarness(EngineConfig(screenW = 1000, screenH = 2000, density = 2f, swipesEnabled = false))
        h.arm()
        h.flick(0.66f, 0.5f, 0.3f, 0f)
        assertTrue(h.swipes().isEmpty())
    }

    // ------------------------------------------------------------ binds

    private fun configWith(vararg binds: Bind) =
        EngineConfig(screenW = 1000, screenH = 2000, density = 2f, binds = binds.toList())

    private fun EngineHarness.fired() = of<ControlEvent.BindFired>().map { it.action }

    @Test
    fun signsFireOncePerEntry() {
        val h = EngineHarness()
        h.arm()
        h.hold(1500) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_DOWN) }
        assertEquals(listOf(SystemAction.BACK), h.fired())
        h.hold(400) { hand(0.66f, 0.5f, Shape.POINT) }
        h.hold(700) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_DOWN) }
        assertEquals(2, h.fired().size)
    }

    @Test
    fun defaultBinds() {
        fun fire(sign: String): List<SystemAction> {
            val h = EngineHarness()
            h.arm()
            h.hold(1200) { hand(0.66f, 0.5f, Shape.FIST, gesture = sign) }
            return h.fired()
        }
        assertEquals(listOf(SystemAction.BACK), fire(Signs.THUMB_DOWN))
        assertEquals(listOf(SystemAction.HOME), fire(Signs.CLOSED_FIST))
        assertEquals(listOf(SystemAction.RECENTS), fire(Signs.VICTORY))
        assertTrue("thumb up has no default bind", fire(Signs.THUMB_UP).isEmpty())
    }

    @Test
    fun homeNeedsTheFullHold() {
        val h = EngineHarness()
        h.arm()
        h.hold(600) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST) }
        h.hold(400) { hand(0.66f, 0.5f, Shape.POINT) }
        assertTrue(h.fired().isEmpty())
        assertEquals(0f, h.of<ControlEvent.BindProgress>().last().progress)
    }

    @Test
    fun bindFiresItsActionLabelAndPackage() {
        val bind = Bind("b", HandSpec(Pose.THUMB_UP), action = SystemAction.LAUNCH_APP, appPackage = "com.example.music", appLabel = "Music")
        val h = EngineHarness(configWith(bind))
        h.arm()
        h.hold(900) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_UP) }
        val e = h.of<ControlEvent.BindFired>().single()
        assertEquals(SystemAction.LAUNCH_APP, e.action)
        assertEquals("com.example.music", e.appPackage)
        assertEquals("Open Music", e.label)
    }

    @Test
    fun holdTimeIsPerBind() {
        val quick = Bind("q", HandSpec(Pose.THUMB_UP), action = SystemAction.MEDIA_PLAY_PAUSE, holdMs = 300)
        val h = EngineHarness(configWith(quick))
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_UP) }
        assertEquals(listOf(SystemAction.MEDIA_PLAY_PAUSE), h.fired())
    }

    @Test
    fun disabledBindDoesNothing() {
        val off = Bind("x", HandSpec(Pose.THUMB_UP), action = SystemAction.MEDIA_PLAY_PAUSE, enabled = false)
        val h = EngineHarness(configWith(off))
        h.arm()
        h.hold(1500) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_UP) }
        assertTrue(h.fired().isEmpty())
    }

    @Test
    fun sideSpecificBindFiresOnlyForThatHand() {
        val right = Bind("r", HandSpec(Pose.THUMB_UP, HandSide.RIGHT), action = SystemAction.VOLUME_UP)
        val hL = EngineHarness(configWith(right))
        hL.arm()
        hL.hold(1200) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_UP, side = HandSide.LEFT) }
        assertTrue(hL.fired().isEmpty())
        val hR = EngineHarness(configWith(right))
        hR.arm()
        hR.hold(1200) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_UP, side = HandSide.RIGHT) }
        assertEquals(listOf(SystemAction.VOLUME_UP), hR.fired())
    }

    @Test
    fun leftAndRightBindsOnTheSamePoseDoDifferentThings() {
        val l = Bind("l", HandSpec(Pose.THUMB_UP, HandSide.LEFT), action = SystemAction.VOLUME_DOWN)
        val r = Bind("r", HandSpec(Pose.THUMB_UP, HandSide.RIGHT), action = SystemAction.VOLUME_UP)
        val h = EngineHarness(configWith(l, r))
        h.arm()
        h.hold(1000) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_UP, side = HandSide.LEFT) }
        h.hold(400) { hand(0.66f, 0.5f, Shape.POINT) }
        h.hold(1000) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_UP, side = HandSide.RIGHT) }
        assertEquals(listOf(SystemAction.VOLUME_DOWN, SystemAction.VOLUME_UP), h.fired())
    }

    private fun twoFists(): List<HandObservation> = listOf(
        hand(0.45f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST, side = HandSide.LEFT),
        hand(0.85f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST, side = HandSide.RIGHT),
    )

    @Test
    fun twoHandBindNeedsBothHands() {
        val both = Bind("two", HandSpec(Pose.FIST), HandSpec(Pose.FIST), SystemAction.LOCK_SCREEN)
        val one = EngineHarness(configWith(both))
        one.arm()
        one.hold(1500) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST) }
        assertTrue("one fist is not enough", one.fired().isEmpty())

        val two = EngineHarness(configWith(both))
        two.arm()
        two.holdHands(1200) { twoFists() }
        assertEquals(listOf(SystemAction.LOCK_SCREEN), two.fired())
    }

    @Test
    fun twoHandBindBeatsTheOneHandBindOfTheSamePose() {
        val single = Bind("one", HandSpec(Pose.FIST), action = SystemAction.HOME, holdMs = 500)
        val both = Bind("two", HandSpec(Pose.FIST), HandSpec(Pose.FIST), SystemAction.LOCK_SCREEN, holdMs = 500)
        val h = EngineHarness(configWith(single, both))
        h.arm()
        h.holdHands(1200) { twoFists() }
        assertEquals(listOf(SystemAction.LOCK_SCREEN), h.fired())
    }

    @Test
    fun twoHandBindWithSidesIsOrderSensitive() {
        val b = Bind("lr", HandSpec(Pose.THUMB_UP, HandSide.LEFT), HandSpec(Pose.FIST, HandSide.RIGHT), SystemAction.MEDIA_NEXT)
        val ok = EngineHarness(configWith(b))
        ok.arm()
        ok.holdHands(1200) {
            listOf(
                hand(0.45f, 0.5f, Shape.FIST, gesture = Signs.THUMB_UP, side = HandSide.LEFT),
                hand(0.85f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST, side = HandSide.RIGHT),
            )
        }
        assertEquals(listOf(SystemAction.MEDIA_NEXT), ok.fired())
        val swapped = EngineHarness(configWith(b))
        swapped.arm()
        swapped.holdHands(1200) {
            listOf(
                hand(0.45f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST, side = HandSide.LEFT),
                hand(0.85f, 0.5f, Shape.FIST, gesture = Signs.THUMB_UP, side = HandSide.RIGHT),
            )
        }
        assertTrue(swapped.fired().isEmpty())
    }

    @Test
    fun customPoseBindFires() {
        val b = Bind("s", HandSpec(Pose.SHAKA), action = SystemAction.MEDIA_PLAY_PAUSE)
        val h = EngineHarness(configWith(b))
        h.arm()
        h.hold(1100) { hand(0.66f, 0.5f, Shape.SHAKA) }
        assertEquals(listOf(SystemAction.MEDIA_PLAY_PAUSE), h.fired())
    }

    @Test
    fun theCursorStaysFrozenWhileABindHoldBuilds() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        val before = h.of<ControlEvent.Pointer>().last()
        h.events.clear()
        // The wrist drifts while the hand forms the fist (0.045 of the screen width in total).
        h.hold(600) { ms -> hand(0.66f + 0.0001f * ms, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST) }
        val ptr = h.of<ControlEvent.Pointer>()
        assertTrue(ptr.size > 10)
        assertTrue("pointer frozen at where it was before the pose formed", ptr.all { kotlin.math.abs(it.x - before.x) < 0.005f })
    }

    // ------------------------------------------------------------ pointer mapping

    private fun safeRegion(s: Float = 0.1f, gain: Float = 1f) =
        PointerMap.region(s, SyntheticHands.ASPECT, 1.7f, 0.7f, 0.65f, gain)

    @Test
    fun theHandSafeRegionMapsOntoTheWholeScreen() {
        val r = safeRegion()
        val a = SyntheticHands.ASPECT
        assertEquals(0.065f, r.left, 1e-5f)
        assertEquals(0.17f, r.top, 1e-5f)
        assertEquals(a - 0.065f, r.right, 1e-5f)
        assertEquals(0.93f, r.bottom, 1e-5f)
        val tl = PointerMap.map(P3(r.left, r.top), r)
        assertEquals(0f, tl.u, 1e-5f); assertEquals(0f, tl.v, 1e-5f)
        val br = PointerMap.map(P3(r.right, r.bottom), r)
        assertEquals(1f, br.u, 1e-5f); assertEquals(1f, br.v, 1e-5f)
        val beyond = PointerMap.map(P3(0f, 0f), r)
        assertEquals(PointerMap.EDGE_LEFT or PointerMap.EDGE_TOP, beyond.edge)
    }

    @Test
    fun theRegionAdaptsToTheHandSizeButNeverCollapses() {
        val near = safeRegion(s = 0.4f)
        assertTrue(near.height >= 0.35f - 1e-5f)
        assertTrue(near.width >= SyntheticHands.ASPECT * 0.35f - 1e-5f)
        val far = safeRegion(s = 0.05f)
        assertTrue(far.width > safeRegion().width)
    }

    @Test
    fun theTopCornerIsReachedWithTheFingersStillInView() {
        val h = EngineHarness()
        h.arm()
        // Palm centre right at the hand-safe corner: the index tip (1.05 palm sizes above it) is still in the frame.
        h.hold(900) { hand(0.065f, 0.17f, Shape.POINT) }
        val p = h.of<ControlEvent.Pointer>().last()
        assertEquals(0f, p.x, 0.01f)
        assertEquals(0f, p.y, 0.01f)
        assertTrue("index tip in view", hand(0.065f, 0.17f, Shape.POINT).landmarks[HandLm.INDEX_TIP].y > 0f)
    }

    @Test
    fun pointerFollowsThePalmThroughTheRegion() {
        val h = EngineHarness()
        h.arm()
        val r = safeRegion()
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        h.hold(600) { hand(cx, cy, Shape.POINT) }
        val a = h.of<ControlEvent.Pointer>().last()
        assertEquals(0.5f, a.x, 0.01f)
        assertEquals(0.5f, a.y, 0.01f)
        h.hold(900) { hand(r.left + 0.75f * r.width, r.top + 0.25f * r.height, Shape.POINT) }
        val b = h.of<ControlEvent.Pointer>().last()
        assertEquals(0.75f, b.x, 0.02f)
        assertEquals(0.25f, b.y, 0.02f)
    }

    @Test
    fun theCursorIsTheSameWhateverTheFingersDo() {
        fun cursorFor(shape: Shape): Pair<Float, Float> {
            val h = EngineHarness()
            h.arm()
            h.hold(900) { hand(0.7f, 0.4f, shape) }
            val p = h.of<ControlEvent.Pointer>().last { it.visible }
            return p.x to p.y
        }
        val point = cursorFor(Shape.POINT)
        for (shape in listOf(Shape.OPEN, Shape.FIST, Shape.THREE, Shape.SHAKA)) {
            val c = cursorFor(shape)
            assertEquals("x for $shape", point.first, c.first, 0.01f)
            assertEquals("y for $shape", point.second, c.second, 0.01f)
        }
    }

    @Test
    fun pointerGainScalesAroundTheCentre() {
        val cfg = EngineConfig(screenW = 1000, screenH = 2000, density = 2f, pointerGain = 2f)
        val h = EngineHarness(cfg)
        h.arm()
        val r = safeRegion()
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        h.hold(900) { hand(cx, cy + 0.05f * r.height, Shape.POINT) }
        val p = h.of<ControlEvent.Pointer>().last()
        assertEquals(0.5f, p.x, 0.01f)
        assertEquals(0.6f, p.y, 0.01f)
    }

    @Test
    fun debugSnapshotDescribesTheFrame() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.OPEN, side = HandSide.RIGHT) }
        val d = h.engine.debug
        assertTrue(d.armed)
        assertEquals(1, d.hands)
        assertEquals(HandSide.RIGHT, d.side)
        assertEquals(0f, d.angleDeg, 2f)
        assertTrue(d.gap > 0.6f)
        assertTrue(d.pointerConfidence > 0.9f)
        assertFalse(d.cooldown)
        assertEquals("IDLE", d.touch)
    }

    @Test
    fun requireBodyIgnoresHandsWithoutAPerson() {
        val h = EngineHarness(EngineConfig(screenW = 1000, screenH = 2000, requireBody = true))
        h.hold(1500) { hand(0.66f, 0.5f, gesture = Signs.I_LOVE_YOU) }
        assertFalse(h.engine.armed)
    }

    @Test
    fun manualArmAndDisarm() {
        val h = EngineHarness()
        h.engine.armNow()
        assertTrue(h.engine.armed)
        h.engine.disarmNow()
        assertFalse(h.engine.armed)
        assertEquals(DisarmReason.MANUAL, h.of<ControlEvent.Disarmed>().single().reason)
    }
}
