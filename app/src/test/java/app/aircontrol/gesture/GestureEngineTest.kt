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
        h.hold(400) { ms -> hand(0.60f + 0.1f * ms / 400f, 0.5f, Shape.PINCH) }
        h.hold(200) { hand(0.70f, 0.5f, Shape.OPEN) }

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

    // ------------------------------------------------------------ push to click

    /** Pointing hand that grows from s0 to s1 over [ms] (a push towards the camera). */
    private fun EngineHarness.push(
        x: Float = 0.66f, y: Float = 0.5f, s0: Float = 0.1f, s1: Float = 0.12f, ms: Long = 150, driftX: Float = 0f,
    ) {
        hold(ms) { t ->
            val k = (t.toFloat() / ms).coerceAtMost(1f)
            hand(x + driftX * k, y, Shape.POINT, s = s0 + (s1 - s0) * k)
        }
    }

    @Test
    fun quickPushIsATapAtThePreOnsetPosition() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        val before = h.of<ControlEvent.Pointer>().last()
        // The hand drifts sideways as it pushes; the click must still land where it was aimed.
        h.push(driftX = 0.012f)
        h.hold(300) { hand(0.672f, 0.5f, Shape.POINT, s = 0.12f) }
        val tap = h.of<ControlEvent.Tap>().single()
        assertEquals(before.x, tap.x, 0.01f)
        assertEquals(before.y, tap.y, 0.01f)
        assertTrue(h.of<ControlEvent.TouchDown>().isEmpty())
    }

    @Test
    fun pointerFrozenWhilePressing() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.events.clear()
        h.push(driftX = 0.012f)
        val pressing = h.of<ControlEvent.Pointer>().filter { it.press > 0f }
        assertTrue("press progress reported", pressing.isNotEmpty())
        assertTrue(pressing.all { it.x == pressing.first().x && it.y == pressing.first().y })
    }

    @Test
    fun slowLeanInIsNotATap() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.push(s1 = 0.13f, ms = 1500)
        h.hold(300) { hand(0.66f, 0.5f, Shape.POINT, s = 0.13f) }
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
    }

    @Test
    fun pushWithLargeSidewaysMotionIsNotATap() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.push(driftX = 0.2f)
        h.hold(300) { hand(0.86f, 0.5f, Shape.POINT, s = 0.12f) }
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
    }

    @Test
    fun pullBackAfterClickDoesNotMovePointerOrClickAgain() {
        val h = EngineHarness()
        h.arm()
        h.hold(500) { hand(0.66f, 0.5f, Shape.POINT) }
        h.push()
        val tap = h.of<ControlEvent.Tap>().single()
        h.events.clear()
        // Pull back with some drift.
        h.hold(150) { t -> val k = (t / 150f).coerceAtMost(1f); hand(0.66f - 0.01f * k, 0.5f, Shape.POINT, s = 0.12f - 0.02f * k) }
        val during = h.of<ControlEvent.Pointer>()
        assertTrue(during.all { it.x == tap.x && it.y == tap.y })
        h.hold(600) { hand(0.65f, 0.5f, Shape.POINT) }
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
        // Two pushes in a row both click.
        h.push(x = 0.65f)
        h.hold(300) { hand(0.65f, 0.5f, Shape.POINT, s = 0.12f) }
        assertEquals(1, h.of<ControlEvent.Tap>().size)
    }

    @Test
    fun handMovingAroundWithoutPushingDoesNotClick() {
        val h = EngineHarness()
        h.arm()
        h.hold(2000) { ms ->
            val a = ms / 300f
            hand(0.66f + 0.05f * kotlin.math.sin(a), 0.5f + 0.05f * kotlin.math.cos(a), Shape.POINT)
        }
        assertTrue(h.of<ControlEvent.Tap>().isEmpty())
    }

    // ------------------------------------------------------------ system signs

    @Test
    fun signsFireOncePerEntry() {
        val h = EngineHarness()
        h.arm()
        h.hold(1500) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_DOWN) }
        assertEquals(listOf(SystemAction.BACK), h.of<ControlEvent.Global>().map { it.action })
        h.hold(400) { hand(0.66f, 0.5f, Shape.POINT) }
        h.hold(700) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.THUMB_DOWN) }
        assertEquals(2, h.of<ControlEvent.Global>().size)
    }

    @Test
    fun defaultSignMapping() {
        fun fire(sign: String): List<SystemAction> {
            val h = EngineHarness()
            h.arm()
            h.hold(1200) { hand(0.66f, 0.5f, Shape.FIST, gesture = sign) }
            return h.of<ControlEvent.Global>().map { it.action }
        }
        assertEquals(listOf(SystemAction.BACK), fire(Signs.THUMB_DOWN))
        assertEquals(listOf(SystemAction.HOME), fire(Signs.CLOSED_FIST))
        assertEquals(listOf(SystemAction.RECENTS), fire(Signs.VICTORY))
        assertTrue("thumb up recenters, not a global action", fire(Signs.THUMB_UP).isEmpty())
    }

    @Test
    fun homeNeedsTheFullHold() {
        val h = EngineHarness()
        h.arm()
        h.hold(600) { hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST) }
        h.hold(400) { hand(0.66f, 0.5f, Shape.POINT) }
        assertTrue(h.of<ControlEvent.Global>().isEmpty())
        assertEquals(0f, h.of<ControlEvent.SignProgress>().last().progress)
    }

    // ------------------------------------------------------------ pointer mapping

    @Test
    fun pointerFollowsTheHand() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.POINT) }
        val a = h.of<ControlEvent.Pointer>().last()
        assertEquals(0.5f, a.x, 0.02f)
        assertEquals(0.5f, a.y, 0.02f)
        h.hold(800) { hand(0.76f, 0.45f, Shape.POINT) }
        val b = h.of<ControlEvent.Pointer>().last()
        assertTrue("moving right moves the pointer right", b.x > a.x + 0.2f)
        assertTrue("moving up moves the pointer up", b.y < a.y - 0.1f)
    }

    @Test
    fun bodyAnchoredBoxIsIndependentOfWhereTheHandAppears() {
        val pose = SyntheticHands.pose(centerX = 0.66f, shoulderY = 0.6f, shoulderWidth = 0.3f)
        val h = EngineHarness()
        h.hold(1100, pose) { hand(0.8f, 0.5f, gesture = Signs.I_LOVE_YOU) }
        h.hold(300, pose) { hand(0.8f, 0.5f, Shape.POINT) }
        assertTrue(h.engine.armed)
        assertTrue(h.engine.bodyVisible)
        // Box centre for a right-side hand: shoulder centre + 0.45 * shoulder width, raised 0.35 sw.
        val cx = 0.66f + 0.45f * 0.3f
        val cy = 0.6f - 0.35f * 0.3f
        // The index tip sits at (-0.35 s, -1.3 s) from the synthetic palm centre (s = 0.1).
        h.hold(800, pose) { hand(cx + 0.035f, cy + 0.13f, Shape.POINT) }
        val p = h.of<ControlEvent.Pointer>().last()
        assertEquals(0.5f, p.x, 0.05f)
        assertEquals(0.5f, p.y, 0.05f)
    }

    @Test
    fun thumbUpThenPointRecentersPointer() {
        val h = EngineHarness()
        h.arm()
        h.hold(400) { hand(0.66f, 0.5f, Shape.POINT) }
        h.hold(600) { hand(0.76f, 0.56f, Shape.POINT) }
        val off = h.of<ControlEvent.Pointer>().last()
        assertTrue(off.x > 0.7f && off.y > 0.55f)
        h.hold(800) { hand(0.76f, 0.56f, Shape.FIST, gesture = Signs.THUMB_UP) }
        assertTrue(h.events.contains(ControlEvent.RecenterPending))
        assertTrue("recenter is not a system action", h.of<ControlEvent.Global>().isEmpty())
        h.hold(800) { hand(0.76f, 0.56f, Shape.POINT) }
        assertTrue(h.events.contains(ControlEvent.Recentered))
        val p = h.of<ControlEvent.Pointer>().last()
        assertEquals(0.5f, p.x, 0.02f)
        assertEquals(0.5f, p.y, 0.02f)
        // Survives losing the hand.
        h.hold(600) { null }
        h.hold(600) { hand(0.76f, 0.56f, Shape.POINT) }
        assertEquals(0.5f, h.of<ControlEvent.Pointer>().last().x, 0.02f)
    }

    @Test
    fun recenterWithBodyIsRelativeToShoulders() {
        val pose = SyntheticHands.pose(centerX = 0.66f, shoulderY = 0.6f, shoulderWidth = 0.3f)
        val h = EngineHarness()
        h.hold(1100, pose) { hand(0.8f, 0.5f, gesture = Signs.I_LOVE_YOU) }
        h.hold(300, pose) { hand(0.8f, 0.5f, Shape.POINT) }
        h.hold(800, pose) { hand(0.85f, 0.62f, Shape.FIST, gesture = Signs.THUMB_UP) }
        h.hold(800, pose) { hand(0.85f, 0.62f, Shape.POINT) }
        assertTrue(h.events.contains(ControlEvent.Recentered))
        // The user steps sideways: body and hand move together, the pointer stays centred.
        val moved = SyntheticHands.pose(centerX = 0.56f, shoulderY = 0.6f, shoulderWidth = 0.3f)
        h.hold(2500, moved) { hand(0.75f, 0.62f, Shape.POINT) }
        val p = h.of<ControlEvent.Pointer>().last()
        assertEquals(0.5f, p.x, 0.05f)
        assertEquals(0.5f, p.y, 0.05f)
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
