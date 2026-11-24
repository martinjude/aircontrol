package app.aircontrol.gesture

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A recorded clip (scripts/extract-clips.py): the observations the app's VisionPipeline would have
 * produced for a gesture video, plus what the clip is expected to do (from its file name).
 */
class Clip(val device: String, val name: String, val frames: List<FrameObservation>) {
    val expect: Map<String, Int> = Expectation.parse(name)
    val group: String = name.substringBefore('_')
    val negative: Boolean get() = group == "neg"
    val durationMs: Long get() = if (frames.isEmpty()) 0 else frames.last().timestampMs - frames.first().timestampMs
    val label: String get() = "$device/$name"
}

/**
 * File-name expectations: `<group>_<desc>__<event><n>[_<event><n>...]`, e.g. `click_center__click3`,
 * `swipe_mixed__left1_right1_up1_down1`. Action events not listed are expected 0 times, so every
 * `neg_*` clip expects no clicks, taps, touches or swipes at all.
 */
object Expectation {
    /** Counted in every clip: anything that acts on the screen. */
    val ACTIONS = listOf("click", "tap", "drag", "long", "left", "right", "up", "down")
    /** Only checked when listed. */
    val OTHERS = listOf("bind", "arm", "disarm")
    private val token = Regex("([a-z]+)(\\d+)")

    fun parse(name: String): Map<String, Int> {
        val spec = name.substringAfter("__", "")
        val m = LinkedHashMap<String, Int>()
        for (a in ACTIONS) m[a] = 0
        if (spec.isEmpty()) return m
        for (part in spec.split('_')) {
            val g = token.matchEntire(part) ?: continue
            m[g.groupValues[1]] = g.groupValues[2].toInt()
        }
        return m
    }
}

object ClipLoader {
    /** Freshness of pose / face results carried to the frames between model runs (as in VisionPipeline). */
    private const val CARRY_MS = 500L

    /** All clips under src/test/resources/clips (empty when none were extracted yet). */
    fun all(): List<Clip> {
        val root = root() ?: return emptyList()
        return root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.sortedBy { it.name }.map { load(dir.name, it) }
        }
    }

    fun root(): File? {
        val candidates = listOf("src/test/resources/clips", "app/src/test/resources/clips")
        return candidates.map(::File).firstOrNull { it.isDirectory }
    }

    fun load(device: String, file: File): Clip {
        val lines = file.readLines().filter { it.isNotBlank() }
        val header = JSONObject(lines.first())
        val flipped = header.optBoolean("flipped", true)
        var pose: PoseObservation? = null
        var poseT = Long.MIN_VALUE / 2
        var face: FaceObservation? = null
        var faceT = Long.MIN_VALUE / 2
        val frames = lines.drop(1).map { line ->
            val o = JSONObject(line)
            val t = o.getLong("t")
            o.optJSONObject("p")?.let { p ->
                pose = PoseObservation(points(p.getJSONArray("l")), floats(p.getJSONArray("v")).toList())
                poseT = t
            }
            o.optJSONObject("f")?.let { f ->
                val b = f.getJSONObject("b")
                val blend = b.keys().asSequence().associateWith { b.getDouble(it).toFloat() }
                face = FaceObservation.from(floats(f.getJSONArray("m")), blend, mirror = flipped)
                faceT = t
            }
            val hands = o.getJSONArray("h").let { arr ->
                (0 until arr.length()).map { i ->
                    val h = arr.getJSONObject(i)
                    HandObservation(
                        landmarks = points(h.getJSONArray("l")),
                        gesture = h.optString("g", Signs.NONE),
                        gestureScore = h.optDouble("s", 0.0).toFloat(),
                        world = h.optJSONArray("w")?.let(::points),
                        side = when (h.optString("side")) {
                            "L" -> HandSide.LEFT
                            "R" -> HandSide.RIGHT
                            else -> HandSide.ANY
                        },
                    )
                }
            }
            FrameObservation(
                timestampMs = t,
                imageAspect = o.getDouble("a").toFloat(),
                hands = hands,
                pose = pose.takeIf { t - poseT < CARRY_MS },
                face = face.takeIf { t - faceT < CARRY_MS },
            )
        }
        return Clip(device, header.optString("clip", file.nameWithoutExtension), frames)
    }

    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }

    private fun points(a: JSONArray): List<P3> {
        val f = floats(a)
        return List(f.size / 3) { P3(f[3 * it], f[3 * it + 1], f[3 * it + 2]) }
    }
}

/** Replays a clip through a fresh engine and counts what it did. */
object ClipRunner {
    class Result(
        val clip: Clip,
        val counts: Map<String, Int>,
        val events: List<Pair<Long, ControlEvent>>,
        val calibration: FloatArray?,
    ) {
        /** Expected → actual for every mismatch. */
        val mismatches: Map<String, Pair<Int, Int>> = clip.expect.filter { (k, v) -> (counts[k] ?: 0) != v }
            .mapValues { (k, v) -> v to (counts[k] ?: 0) }
        val ok: Boolean get() = mismatches.isEmpty()
    }

    fun configFor(clip: Clip, attention: AttentionCalibration? = null, gate: Boolean = false): EngineConfig {
        val base = EngineConfig(screenW = 1080, screenH = 2400, density = 2.6f, attentionGate = gate, attentionCalibration = attention)
        return when {
            clip.name.startsWith("binds_two_hands") -> base.copy(
                binds = listOf(
                    Bind("2f", HandSpec(Pose.FIST), HandSpec(Pose.FIST), SystemAction.HOME),
                    Bind("2o", HandSpec(Pose.OPEN_PALM), HandSpec(Pose.OPEN_PALM), SystemAction.RECENTS),
                )
            )
            clip.name.startsWith("binds_") -> base.copy(
                binds = listOf(Pose.FIST, Pose.THUMB_UP, Pose.THUMB_DOWN, Pose.VICTORY, Pose.OPEN_PALM).map {
                    Bind("b-${it.name}", HandSpec(it), action = SystemAction.MEDIA_PLAY_PAUSE)
                }
            )
            else -> base
        }
    }

    fun run(clip: Clip, cfg: EngineConfig = configFor(clip), calibrate: CalibrationKind? = null): Result {
        val events = ArrayList<Pair<Long, ControlEvent>>()
        var now = 0L
        val engine = GestureEngine(cfg) { events += now to it }
        var started = false
        for (f in clip.frames) {
            now = f.timestampMs
            if (!started) {
                // Arm clips start disarmed; all others are replayed armed, as if 🤟 was held before.
                engine.process(f.copy(hands = emptyList()))
                if (!clip.name.startsWith("arm_")) engine.armNow()
                calibrate?.let { engine.startCalibration(it) }
                started = true
            }
            engine.process(f)
        }
        val cal = events.map { it.second }.filterIsInstance<ControlEvent.Calibration>().lastOrNull { it.done }?.result
        return Result(clip, count(events, cfg), events, cal)
    }

    private fun count(events: List<Pair<Long, ControlEvent>>, cfg: EngineConfig): Map<String, Int> {
        val c = LinkedHashMap<String, Int>()
        fun inc(k: String) { c[k] = (c[k] ?: 0) + 1 }
        var down: Pair<Long, ControlEvent.TouchDown>? = null
        val slop = cfg.dragSlopPx
        for ((t, e) in events) when (e) {
            is ControlEvent.Tap -> inc(if (e.click) "click" else "tap")
            is ControlEvent.TouchDown -> down = t to e
            is ControlEvent.TouchUp -> {
                val d = down
                if (d != null) {
                    val dist = kotlin.math.hypot((e.x - d.second.x) * cfg.screenW, (e.y - d.second.y) * cfg.screenH)
                    when {
                        dist > slop -> inc("drag")
                        // Android treats a short still press as a tap.
                        t - d.first < LONG_PRESS_MS -> inc("tap")
                        else -> inc("long")
                    }
                }
                down = null
            }
            is ControlEvent.Swipe -> inc(e.dir.name.lowercase())
            is ControlEvent.BindFired -> inc("bind")
            is ControlEvent.Armed -> inc("arm")
            is ControlEvent.Disarmed -> if (e.reason == DisarmReason.GESTURE) inc("disarm")
            else -> {}
        }
        return c
    }

    private const val LONG_PRESS_MS = 500L
}
