package app.aircontrol.gesture

import app.aircontrol.gesture.SyntheticHands.Shape
import app.aircontrol.gesture.SyntheticHands.hand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BindsTest {

    // ------------------------------------------------------------ codec

    @Test
    fun codecRoundTripsEveryField() {
        val binds = listOf(
            Bind("a", HandSpec(Pose.THUMB_UP, HandSide.LEFT), HandSpec(Pose.FIST, HandSide.RIGHT), SystemAction.MEDIA_NEXT, holdMs = 700, enabled = false),
            Bind("b", HandSpec(Pose.SHAKA), action = SystemAction.LAUNCH_APP, appPackage = "com.example.app", appLabel = "My | App 100%\nline", holdMs = 1200),
            Bind("c|odd%id", HandSpec(Pose.OPEN_PALM), action = SystemAction.LOCK_SCREEN),
        )
        assertEquals(binds, BindCodec.decode(BindCodec.encode(binds)))
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals(emptyList<Bind>(), BindCodec.decode(BindCodec.encode(emptyList())))
    }

    @Test
    fun malformedAndUnknownLinesAreSkipped() {
        val good = BindCodec.encode(listOf(Bind("ok", HandSpec(Pose.FIST), action = SystemAction.HOME)))
        val text = listOf(
            "garbage",
            "id|1|FIST:ANY|-|RECENTER|-|-|800", // an action that no longer exists
            "id|1|NOPE:ANY|-|HOME|-|-|800",
            "id|1|FIST:ANY|-|HOME|-|-|abc",
            good,
        ).joinToString("\n")
        val decoded = BindCodec.decode(text)
        assertEquals(listOf("ok"), decoded.map { it.id })
    }

    @Test
    fun holdTimeIsClamped() {
        val b = BindCodec.decode("x|1|FIST:ANY|-|HOME|-|-|5").single()
        assertEquals(200, b.holdMs)
    }

    // ------------------------------------------------------------ model

    @Test
    fun legacySignSettingsBecomeBinds() {
        val binds = Binds.legacy(SystemAction.BACK, SystemAction.HOME, SystemAction.NONE, SystemAction.MEDIA_PLAY_PAUSE)
        assertEquals(
            listOf(Pose.THUMB_DOWN to SystemAction.BACK, Pose.FIST to SystemAction.HOME, Pose.THUMB_UP to SystemAction.MEDIA_PLAY_PAUSE),
            binds.map { it.first.pose to it.action },
        )
    }

    @Test
    fun defaultsAreTheV2Signs() {
        assertEquals(
            listOf(Pose.THUMB_DOWN to SystemAction.BACK, Pose.FIST to SystemAction.HOME, Pose.VICTORY to SystemAction.RECENTS),
            Binds.defaults().map { it.first.pose to it.action },
        )
    }

    @Test
    fun twoHandTriggersAreUnorderedForDuplicateDetection() {
        val a = Bind("a", HandSpec(Pose.FIST), HandSpec(Pose.THUMB_UP), SystemAction.HOME)
        val b = Bind("b", HandSpec(Pose.THUMB_UP), HandSpec(Pose.FIST), SystemAction.BACK)
        val c = Bind("c", HandSpec(Pose.FIST), action = SystemAction.BACK)
        assertEquals(a.triggerKey, b.triggerKey)
        assertNotEquals(a.triggerKey, c.triggerKey)
    }

    @Test
    fun priorityPrefersTwoHandsThenSides() {
        val any = Bind("1", HandSpec(Pose.FIST), action = SystemAction.HOME)
        val side = Bind("2", HandSpec(Pose.FIST, HandSide.LEFT), action = SystemAction.HOME)
        val two = Bind("3", HandSpec(Pose.FIST), HandSpec(Pose.FIST), SystemAction.HOME)
        assertTrue(two.priority > side.priority && side.priority > any.priority)
    }

    @Test
    fun labels() {
        val b = Bind("a", HandSpec(Pose.THUMB_UP, HandSide.LEFT), HandSpec(Pose.FIST), SystemAction.LAUNCH_APP, appPackage = "p", appLabel = "Maps")
        assertEquals("👍L + ✊", b.triggerLabel)
        assertEquals("Open Maps", b.actionLabel)
        assertEquals("Play / pause", Bind("x", HandSpec(Pose.FIST), action = SystemAction.MEDIA_PLAY_PAUSE).actionLabel)
    }

    // ------------------------------------------------------------ pose classification

    private fun pose(shape: Shape, gesture: String = Signs.NONE, rollDeg: Float = 0f): Pose? {
        val obs = hand(0.66f, 0.5f, shape, gesture = gesture, rollDeg = rollDeg)
        return HandPose.classify(obs, HandFeatures.from(obs, SyntheticHands.ASPECT), 0.55f)
    }

    @Test
    fun customPosesAreRecognisedFromTheFingers() {
        assertEquals(Pose.SHAKA, pose(Shape.SHAKA))
        assertEquals(Pose.THREE, pose(Shape.THREE))
        assertEquals(Pose.FOUR, pose(Shape.FOUR))
    }

    @Test
    fun customPosesDoNotDependOnHandRotation() {
        assertEquals(Pose.SHAKA, pose(Shape.SHAKA, rollDeg = 40f))
        assertEquals(Pose.THREE, pose(Shape.THREE, rollDeg = -40f))
    }

    @Test
    fun openPalmWithTheThumbOutIsNotFourFingers() {
        assertNull(pose(Shape.OPEN))
        assertEquals(Pose.OPEN_PALM, pose(Shape.OPEN, gesture = Signs.OPEN_PALM))
    }

    @Test
    fun mediaPipeSignsMapToPoses() {
        assertEquals(Pose.FIST, pose(Shape.FIST, Signs.CLOSED_FIST))
        assertEquals(Pose.THUMB_UP, pose(Shape.FIST, Signs.THUMB_UP))
        assertEquals(Pose.THUMB_DOWN, pose(Shape.FIST, Signs.THUMB_DOWN))
        assertEquals(Pose.VICTORY, pose(Shape.FIST, Signs.VICTORY))
    }

    @Test
    fun armSignAndPointingAreNotBindable() {
        assertNull(pose(Shape.OPEN, Signs.I_LOVE_YOU))
        assertNull(pose(Shape.POINT, Signs.POINTING_UP))
        assertNull(pose(Shape.POINT))
    }

    @Test
    fun lowConfidenceSignIsIgnored() {
        val obs = hand(0.66f, 0.5f, Shape.FIST, gesture = Signs.CLOSED_FIST, score = 0.3f)
        assertNull(HandPose.classify(obs, HandFeatures.from(obs, SyntheticHands.ASPECT), 0.55f))
    }

    // ------------------------------------------------------------ hand geometry

    @Test
    fun uprightHandHasNoTiltAndHorizontalHandIs90() {
        fun feat(roll: Float) = hand(0.66f, 0.5f, Shape.PINCH, rollDeg = roll).let { HandFeatures.from(it, SyntheticHands.ASPECT) }
        assertEquals(0f, feat(0f).tiltDeg, 1f)
        assertEquals(30f, feat(30f).tiltDeg, 1f)
        assertEquals(90f, feat(90f).tiltDeg, 1f)
        assertEquals(90f, feat(-90f).tiltDeg, 1f)
        assertTrue(feat(0f).upright(45f, 0.5f))
        assertFalse(feat(60f).upright(45f, 0.5f))
        assertFalse(feat(90f).upright(45f, 0.5f))
    }

    @Test
    fun indexExtensionDropsAsTheFingerCurls() {
        fun ext(curl: Float) = hand(0.66f, 0.5f, Shape.POINT, indexCurl = curl).let { HandFeatures.from(it, SyntheticHands.ASPECT).indexExt }
        assertTrue(ext(0f) > 1.7f)
        assertTrue(ext(1f) < 1.1f)
        assertTrue(ext(0f) > ext(0.5f) && ext(0.5f) > ext(1f))
        // Rotating the hand in the image does not change it.
        val rolled = hand(0.66f, 0.5f, Shape.POINT, indexCurl = 0.5f, rollDeg = 70f).let { HandFeatures.from(it, SyntheticHands.ASPECT).indexExt }
        assertEquals(ext(0.5f), rolled, 0.02f)
    }

    @Test
    fun palmCentreIsWhereTheHandIsPlaced() {
        val obs = hand(0.8f, 0.4f, Shape.FIST)
        val f = HandFeatures.from(obs, SyntheticHands.ASPECT)
        assertEquals(0.8f, f.palmCenter.x, 1e-4f)
        assertEquals(0.4f, f.palmCenter.y, 1e-4f)
    }
}
