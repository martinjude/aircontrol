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

    @Test
    fun quickPinchIsATap() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.hold(150) { hand(0.66f, 0.5f, Shape.PINCH) }
        h.hold(300) { hand(0.66f, 0.5f, Shape.OPEN) }
        assertEquals(1, h.of<ControlEvent.Tap>().size)
        assertTrue(h.of<ControlEvent.TouchDown>().isEmpty())
        assertTrue(h.of<ControlEvent.Swipe>().isEmpty())
    }

    @Test
    fun tapLandsWhereThePointerWas() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN) }
        val before = h.of<ControlEvent.Pointer>().last()
        h.hold(150) { hand(0.66f, 0.5f, Shape.PINCH) }
        h.hold(200) { hand(0.66f, 0.5f, Shape.OPEN) }
        val tap = h.of<ControlEvent.Tap>().single()
        assertEquals(before.x, tap.x, 0.01f)
        assertEquals(before.y, tap.y, 0.01f)
    }

    @Test
    fun pinchMoveReleaseIsADrag() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.60f, 0.5f, Shape.OPEN) }
        h.hold(100) { hand(0.60f, 0.5f, Shape.PINCH) }
        // 0.2 image heights to the right = 15 % of the screen width (the camera view maps 1:1 onto the screen).
        h.hold(400) { ms -> hand(0.60f + 0.2f * ms / 400f, 0.5f, Shape.PINCH) }
        h.hold(200) { hand(0.80f, 0.5f, Shape.OPEN) }

        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
        assertEquals(1, h.of<ControlEvent.TouchDown>().size)
        assertEquals(1, h.of<ControlEvent.TouchUp>().size)
        val moves = h.of<ControlEvent.TouchMove>().map { it.x }
        assertTrue(moves.size >= 5)
        assertTrue("drag should move right", moves.last() > moves.first() + 0.1f)
        // Order: down, moves, up.
        val kinds = h.events.filter { it is ControlEvent.TouchDown || it is ControlEvent.TouchMove || it is ControlEvent.TouchUp }
        assertTrue(kinds.first() is ControlEvent.TouchDown)
        assertTrue(kinds.last() is ControlEvent.TouchUp)
    }

    @Test
    fun pinchHeldStillPressesAndHolds() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.hold(900) { hand(0.66f, 0.5f, Shape.PINCH) }
        assertEquals(1, h.of<ControlEvent.TouchDown>().size)
        assertTrue(h.of<ControlEvent.TouchUp>().isEmpty())
        h.hold(200) { hand(0.66f, 0.5f, Shape.OPEN) }
        assertEquals(1, h.of<ControlEvent.TouchUp>().size)
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
    }

    @Test
    fun pinchHysteresisIgnoresSmallOpening() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.hold(400) { ms -> hand(0.66f, 0.5f, Shape.PINCH, pinchRatio = if (ms > 200) 0.38f else 0.1f) }
        // 0.38 is above the enter threshold but below the exit one: still pinched.
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
        assertTrue(h.of<ControlEvent.TouchUp>().isEmpty())
        assertEquals(1, h.of<ControlEvent.TouchDown>().size)
    }

    @Test
    fun losingTheHandMidDragLiftsTheTouch() {
        val h = EngineHarness()
        h.arm()
        h.hold(300) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.hold(500) { hand(0.66f, 0.5f, Shape.PINCH) }
        h.hold(600) { null }
        assertEquals(1, h.of<ControlEvent.TouchDown>().size)
        assertEquals(1, h.of<ControlEvent.TouchUp>().size)
    }

    @Test
    fun fistIsNotAPinch() {
        val h = EngineHarness()
        h.arm()
        h.hold(600) { hand(0.66f, 0.5f, Shape.FIST) }
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
        assertTrue(h.of<ControlEvent.TouchDown>().isEmpty())
    }

    // ------------------------------------------------------------ armed swipes

    /** Holds a still blade to arm swipe mode; returns the harness with events cleared. */
    private fun armedSwipes(shape: Shape): EngineHarness {
        val h = EngineHarness()
        h.arm()
        h.hold(450) { hand(0.66f, 0.5f, shape) }
        assertTrue("swipe mode should arm", h.of<ControlEvent.SwipeMode>().any { it.axis != null })
        h.events.clear()
        return h
    }

    private fun EngineHarness.stroke(shape: Shape, fromX: Float, fromY: Float, dx: Float, dy: Float, ms: Long = 160) {
        hold(ms) { t -> val k = (t.toFloat() / ms).coerceAtMost(1f); hand(fromX + dx * k, fromY + dy * k, shape) }
        hold(250) { hand(fromX + dx, fromY + dy, shape) }
    }

    @Test
    fun verticalBladeHeldArmsHorizontalSwipes() {
        val h = armedSwipes(Shape.BLADE_V)
        h.stroke(Shape.BLADE_V, 0.66f, 0.5f, 0.25f, 0f)
        assertEquals(listOf(SwipeDir.RIGHT), h.of<ControlEvent.Swipe>().map { it.dir })
        val h2 = armedSwipes(Shape.BLADE_V)
        h2.stroke(Shape.BLADE_V, 0.66f, 0.5f, -0.25f, 0f)
        assertEquals(listOf(SwipeDir.LEFT), h2.of<ControlEvent.Swipe>().map { it.dir })
    }

    @Test
    fun horizontalBladeArmsVerticalSwipes() {
        val h = armedSwipes(Shape.BLADE_H)
        assertTrue(h.engine.armed)
        h.stroke(Shape.BLADE_H, 0.66f, 0.5f, 0f, -0.25f)
        assertEquals(listOf(SwipeDir.UP), h.of<ControlEvent.Swipe>().map { it.dir })
        val h2 = armedSwipes(Shape.BLADE_H)
        h2.stroke(Shape.BLADE_H, 0.66f, 0.5f, 0f, 0.25f)
        assertEquals(listOf(SwipeDir.DOWN), h2.of<ControlEvent.Swipe>().map { it.dir })
    }

    @Test
    fun swipeModeArmsWithTheRightAxisAndHidesThePointer() {
        val h = EngineHarness()
        h.arm()
        h.hold(450) { hand(0.66f, 0.5f, Shape.BLADE_V) }
        val modes = h.of<ControlEvent.SwipeMode>()
        assertTrue("hold progress shown first", modes.first().axis == null && modes.first().progress > 0f)
        assertEquals(SwipeAxis.HORIZONTAL, modes.last().axis)
        assertFalse(h.of<ControlEvent.Pointer>().last().visible)
    }

    @Test
    fun strokeWithoutArmingDoesNothing() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN) }
        h.stroke(Shape.OPEN, 0.66f, 0.5f, 0.25f, 0f)
        // A fast blade that was never held still doesn't arm either.
        h.stroke(Shape.BLADE_V, 0.66f, 0.5f, -0.25f, 0f)
        assertTrue(h.of<ControlEvent.Swipe>().isEmpty())
    }

    @Test
    fun movingBladeDoesNotArm() {
        val h = EngineHarness()
        h.arm()
        h.hold(1200) { ms -> hand(0.5f + 0.3f * ms / 1200f, 0.5f, Shape.BLADE_V) }
        assertTrue(h.of<ControlEvent.SwipeMode>().none { it.axis != null })
    }

    @Test
    fun offAxisStrokeIgnoredInSwipeMode() {
        val h = armedSwipes(Shape.BLADE_V)
        h.stroke(Shape.BLADE_V, 0.66f, 0.5f, 0f, 0.25f)
        assertTrue(h.of<ControlEvent.Swipe>().isEmpty())
    }

    @Test
    fun repeatedStrokesWhileFlat_returnIgnored() {
        val h = armedSwipes(Shape.BLADE_V)
        h.stroke(Shape.BLADE_V, 0.66f, 0.5f, 0.25f, 0f)
        // Quick return stroke: blocked.
        h.stroke(Shape.BLADE_V, 0.91f, 0.5f, -0.25f, 0f, ms = 160)
        h.hold(600) { hand(0.66f, 0.5f, Shape.BLADE_V) }
        // Second stroke in the same direction, still armed.
        h.stroke(Shape.BLADE_V, 0.66f, 0.5f, 0.25f, 0f)
        assertEquals(listOf(SwipeDir.RIGHT, SwipeDir.RIGHT), h.of<ControlEvent.Swipe>().map { it.dir })
    }

    @Test
    fun swipeModeExitsWhenHandNoLongerFlat() {
        val h = armedSwipes(Shape.BLADE_V)
        h.hold(600) { hand(0.66f, 0.5f, Shape.POINT) }
        assertEquals(null, h.of<ControlEvent.SwipeMode>().last().axis)
        assertTrue(h.of<ControlEvent.Pointer>().last().visible)
        h.stroke(Shape.POINT, 0.66f, 0.5f, 0.25f, 0f)
        assertTrue(h.of<ControlEvent.Swipe>().isEmpty())
    }

    // ------------------------------------------------------------ pinch orientation

    @Test
    fun pinchIsIgnoredWhenTheHandIsHorizontal() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN, rollDeg = 90f) }
        h.hold(700) { hand(0.66f, 0.5f, Shape.PINCH, rollDeg = 90f) }
        h.hold(300) { hand(0.66f, 0.5f, Shape.OPEN, rollDeg = 90f) }
        assertTrue(h.of<ControlEvent.TouchDown>().isEmpty())
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
    }

    @Test
    fun pinchWorksWithAMildTilt() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN, rollDeg = 30f) }
        h.hold(150) { hand(0.66f, 0.5f, Shape.PINCH, rollDeg = 30f) }
        h.hold(300) { hand(0.66f, 0.5f, Shape.OPEN, rollDeg = 30f) }
        assertEquals(1, h.of<ControlEvent.Tap>().count { !it.click })
    }

    @Test
    fun pinchBeyondTheToleranceIsIgnored() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.OPEN, rollDeg = 60f) }
        h.hold(150) { hand(0.66f, 0.5f, Shape.PINCH, rollDeg = 60f) }
        h.hold(300) { hand(0.66f, 0.5f, Shape.OPEN, rollDeg = 60f) }
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
    }

    @Test
    fun aDragSurvivesTheHandTiltingFurther() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.60f, 0.5f, Shape.OPEN) }
        h.hold(100) { hand(0.60f, 0.5f, Shape.PINCH) }
        // Started upright; the wrist then rolls to 60°, past the start tolerance but inside the hold tolerance.
        h.hold(600) { ms -> hand(0.60f + 0.1f * ms / 600f, 0.5f, Shape.PINCH, rollDeg = 60f * ms / 600f) }
        assertEquals(1, h.of<ControlEvent.TouchDown>().size)
        assertTrue("drag must not drop while tilting", h.of<ControlEvent.TouchUp>().isEmpty())
        h.hold(200) { hand(0.70f, 0.5f, Shape.OPEN, rollDeg = 60f) }
        assertEquals(1, h.of<ControlEvent.TouchUp>().size)
    }

    // ------------------------------------------------------------ click: index curl and release

    /** Index curls to [depth] and straightens again; the palm stays at ([x], [y]) (plus optional drift). */
    private fun EngineHarness.curl(
        x: Float = 0.66f, y: Float = 0.5f, depth: Float = 0.55f,
        downMs: Long = 100, holdMs: Long = 40, upMs: Long = 100, driftX: Float = 0f, rollDeg: Float = 0f,
    ) {
        hold(downMs) { t ->
            val k = (t.toFloat() / downMs).coerceAtMost(1f)
            hand(x + driftX * k, y, Shape.POINT, indexCurl = depth * k, rollDeg = rollDeg)
        }
        hold(holdMs) { hand(x + driftX, y, Shape.POINT, indexCurl = depth, rollDeg = rollDeg) }
        hold(upMs) { t ->
            val k = (t.toFloat() / upMs).coerceAtMost(1f)
            hand(x + driftX, y, Shape.POINT, indexCurl = depth * (1f - k), rollDeg = rollDeg)
        }
    }

    private fun EngineHarness.clicks() = of<ControlEvent.Tap>().filter { it.click }

    @Test
    fun curlAndReleaseIsAClickWhereTheCursorWas() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        val before = h.of<ControlEvent.Pointer>().last()
        h.curl()
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT) }
        val click = h.clicks().single()
        assertEquals(before.x, click.x, 0.01f)
        assertEquals(before.y, click.y, 0.01f)
        assertTrue(h.of<ControlEvent.TouchDown>().isEmpty())
    }

    @Test
    fun theClickFiresOnTheReleaseNotOnTheCurl() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.hold(120) { t -> hand(0.66f, 0.5f, Shape.POINT, indexCurl = 0.55f * (t / 120f).coerceAtMost(1f)) }
        h.hold(100) { hand(0.66f, 0.5f, Shape.POINT, indexCurl = 0.55f) }
        assertTrue("no click while the finger is still curled", h.clicks().isEmpty())
        h.hold(150) { t -> hand(0.66f, 0.5f, Shape.POINT, indexCurl = 0.55f * (1f - (t / 150f).coerceAtMost(1f))) }
        assertEquals(1, h.clicks().size)
    }

    @Test
    fun theCursorDoesNotMoveWhenTheIndexCurls() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.events.clear()
        h.curl()
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT) }
        val ptr = h.of<ControlEvent.Pointer>()
        assertTrue(ptr.isNotEmpty())
        assertTrue(ptr.all { kotlin.math.abs(it.x - ptr.first().x) < 0.005f && kotlin.math.abs(it.y - ptr.first().y) < 0.005f })
        assertTrue("curl progress is reported", ptr.any { it.press > 0f })
    }

    @Test
    fun slowCurlLikeMakingAFistIsNotAClick() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.curl(downMs = 700, holdMs = 300, upMs = 700)
        h.hold(400) { hand(0.66f, 0.5f, Shape.POINT) }
        assertTrue(h.clicks().isEmpty())
        assertTrue(h.of<ControlEvent.ClickRejected>().any { it.reason.startsWith("too slow") })
    }

    @Test
    fun pointingThenClosingAFistAndStayingIsNotAClick() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.hold(800) { hand(0.66f, 0.5f, Shape.FIST) }
        assertTrue(h.clicks().isEmpty())
    }

    @Test
    fun shallowCurlIsNotAClickAndSaysWhy() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.curl(depth = 0.25f)
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT) }
        assertTrue(h.clicks().isEmpty())
        assertTrue(h.of<ControlEvent.ClickRejected>().any { it.reason.startsWith("too shallow") })
    }

    @Test
    fun higherClickSensitivityAcceptsAShallowerCurl() {
        val cfg = EngineConfig(screenW = 1000, screenH = 2000, density = 2f, clickSensitivity = 2f)
        val h = EngineHarness(cfg)
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.curl(depth = 0.25f)
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT) }
        assertEquals(1, h.clicks().size)
    }

    @Test
    fun curlWhileSweepingTheHandIsNotAClick() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.curl(driftX = 0.2f)
        h.hold(300) { hand(0.86f, 0.5f, Shape.POINT) }
        assertTrue(h.clicks().isEmpty())
    }

    @Test
    fun clickWorksOnAHorizontalHandToo() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT, rollDeg = 90f) }
        h.curl(rollDeg = 90f)
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT, rollDeg = 90f) }
        assertEquals(1, h.clicks().size)
    }

    @Test
    fun aSingleCurlClicksExactlyOnce() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.curl()
        h.hold(1000) { hand(0.66f, 0.5f, Shape.POINT) }
        assertEquals(1, h.clicks().size)
    }

    @Test
    fun separateCurlsEachClick() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.curl()
        h.hold(600) { hand(0.66f, 0.5f, Shape.POINT) }
        h.curl()
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT) }
        assertEquals(2, h.clicks().size)
    }

    @Test
    fun clickIsNotFiredByAPinch() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.POINT) }
        h.hold(150) { hand(0.66f, 0.5f, Shape.PINCH) }
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT) }
        assertTrue(h.clicks().isEmpty())
        assertEquals(1, h.of<ControlEvent.Tap>().count { !it.click })
    }

    @Test
    fun handMovingAroundWithoutCurlingDoesNotClick() {
        val h = EngineHarness()
        h.arm()
        h.hold(2000) { ms ->
            val a = ms / 300f
            hand(0.66f + 0.05f * kotlin.math.sin(a), 0.5f + 0.05f * kotlin.math.cos(a), Shape.POINT)
        }
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
    }

    @Test
    fun clicksAreDetectedButNotPerformedWhileDisarmed() {
        val h = EngineHarness()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.curl()
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT) }
        assertFalse(h.engine.armed)
        assertTrue("no taps while disarmed", h.of<ControlEvent.Tap>().isEmpty())
        assertTrue("the preview still sees the click", h.engine.debug.clickAgoMs < 1000)
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

    @Test
    fun cameraCentreIsScreenCentreAndCornersAreScreenCorners() {
        val a = SyntheticHands.ASPECT
        fun map(x: Float, y: Float, g: Float = 1f) = PointerMap.map(P3(x * a, y), a, g)
        assertEquals(0.5f to 0.5f, map(0.5f, 0.5f))
        assertEquals(0f to 0f, map(0f, 0f))
        assertEquals(1f to 1f, map(1f, 1f))
        assertEquals(1f to 0f, map(1f, 0f))
        // Gain 2 around the centre: a quarter of the view reaches the edge, beyond it clamps.
        assertEquals(1f, map(0.75f, 0.5f, 2f).first, 1e-5f)
        assertEquals(0.75f, map(0.625f, 0.5f, 2f).first, 1e-5f)
        assertEquals(1f, map(0.9f, 0.5f, 2f).first, 1e-5f)
    }

    @Test
    fun pointerSitsOnThePalmCentre() {
        val h = EngineHarness()
        h.arm()
        h.hold(600) { hand(0.5f * SyntheticHands.ASPECT, 0.5f, Shape.POINT) }
        val a = h.of<ControlEvent.Pointer>().last()
        assertEquals(0.5f, a.x, 0.01f)
        assertEquals(0.5f, a.y, 0.01f)
        h.hold(900) { hand(0.75f * SyntheticHands.ASPECT, 0.25f, Shape.POINT) }
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
        h.hold(900) { hand(0.5f * SyntheticHands.ASPECT, 0.55f, Shape.POINT) }
        val p = h.of<ControlEvent.Pointer>().last()
        assertEquals(0.5f, p.x, 0.01f)
        assertEquals(0.6f, p.y, 0.01f)
    }

    @Test
    fun debugSnapshotDescribesTheFrame() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT, side = HandSide.RIGHT) }
        val d = h.engine.debug
        assertTrue(d.armed)
        assertEquals(1, d.hands)
        assertEquals(HandSide.RIGHT, d.side)
        assertTrue(d.upright)
        assertEquals(0f, d.tiltDeg, 1f)
        assertTrue(d.reach > 1.5f)
        assertEquals(0.30f, d.curlNeeded, 1e-4f)
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
