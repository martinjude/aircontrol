package app.aircontrol.gesture

import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays the recorded gesture clips (testdata/README.md) through the engine with default settings
 * and checks every clip against the expectation in its name. Prints a per-clip table, the false
 * actions per minute on negative clips and the recall on positive ones, and writes the same report
 * to build/reports/clips.txt. Skipped while no clips have been extracted.
 *
 * `./gradlew testDebugUnitTest --tests '*ClipReplayTest*' -Pclips=` prints the report; a value
 * (`-Pclips=click_`) only replays clips whose name contains it. Each click candidate's reject reason
 * and confidence factors are listed for the failing clips.
 */
class ClipReplayTest {

    @Test
    fun recordedClipsDoWhatTheirNamesSay() {
        java.util.Locale.setDefault(java.util.Locale.ROOT)
        val filter = System.getProperty("clips.filter").orEmpty()
        val clips = ClipLoader.all().filter { filter.isEmpty() || it.label.contains(filter) }
        assumeTrue("no recorded clips", clips.isNotEmpty())

        val out = StringBuilder()
        fun line(s: String = "") { out.appendLine(s) }
        val attentionByDevice = clips.filter { it.name.startsWith("gaze_calibration") }.associate { c ->
            c.device to ClipRunner.run(c, calibrate = CalibrationKind.ATTENTION).calibration?.let { AttentionCalibration(it[0], it[1], it[2]) }
        }

        val results = ArrayList<ClipRunner.Result>()
        val gateFailures = ArrayList<String>()
        line("%-46s %6s  %-40s %s".format("clip", "sec", "expected → got", ""))
        for (clip in clips) {
            when {
                clip.name.startsWith("gaze_") -> {
                    reportGaze(clip, attentionByDevice[clip.device], ::line)
                    continue
                }
            }
            val r = ClipRunner.run(clip)
            results += r
            line(row(r))
            if (!r.ok) details(r, ::line)
            // With the attention gate on, clicks while looking away must be blocked.
            if (clip.name.contains("lookaway")) {
                val cal = attentionByDevice[clip.device]
                if (cal == null) {
                    line("    (no gaze_calibration clip for ${clip.device}: gated replay skipped)")
                } else {
                    val gated = ClipRunner.run(clip, ClipRunner.configFor(clip, cal, gate = true))
                    val clicks = gated.counts["click"] ?: 0
                    line("    attention gate on: $clicks clicks (expected 0)${if (clicks == 0) "" else "  ✗"}")
                    if (clicks != 0) gateFailures += "  ${clip.label}: $clicks click(s) with the attention gate on"
                }
            }
        }

        line()
        val neg = results.filter { it.clip.negative }
        val negMinutes = neg.sumOf { it.clip.durationMs } / 60_000.0
        val falseActions = neg.sumOf { r -> Expectation.ACTIONS.sumOf { r.counts[it] ?: 0 } }
        if (neg.isNotEmpty()) line("negatives: %d clips, %.1f min, %d false actions (%.1f / min)".format(neg.size, negMinutes, falseActions, falseActions / negMinutes.coerceAtLeast(1e-6)))
        val pos = results.filter { !it.clip.negative }
        val wanted = pos.sumOf { r -> r.clip.expect.values.sum() }
        val hit = pos.sumOf { r -> r.clip.expect.entries.sumOf { (k, v) -> minOf(v, r.counts[k] ?: 0) } }
        if (pos.isNotEmpty()) line("positives: %d clips, recall %d/%d (%.0f %%)".format(pos.size, hit, wanted, 100.0 * hit / wanted.coerceAtLeast(1)))
        val failed = results.filter { !it.ok }
        line("${results.size - failed.size}/${results.size} clips as expected")

        val report = out.toString()
        println(report)
        File("build/reports").apply { mkdirs() }.resolve("clips.txt").writeText(report)
        if (failed.isNotEmpty() || gateFailures.isNotEmpty()) {
            fail("${failed.size + gateFailures.size} clip check(s) failed:\n" + (failed.map { "  " + row(it) } + gateFailures).joinToString("\n"))
        }
    }

    private fun row(r: ClipRunner.Result): String {
        val shown = r.clip.expect.filter { (k, v) -> v != 0 || (r.counts[k] ?: 0) != 0 }.keys +
            Expectation.OTHERS.filter { (r.counts[it] ?: 0) != 0 && it !in r.clip.expect }
        val text = shown.joinToString(" ") { k ->
            val e = r.clip.expect[k]
            val got = r.counts[k] ?: 0
            if (e == null) "$k:$got" else "$k $e→$got"
        }.ifEmpty { "nothing → nothing" }
        return "%-46s %6.1f  %-40s %s".format(r.clip.label, r.clip.durationMs / 1000f, text, if (r.ok) "" else "✗")
    }

    /** Click reject reasons and the actions with their times, to see what went wrong. */
    private fun details(r: ClipRunner.Result, line: (String) -> Unit) {
        val t0 = r.clip.frames.firstOrNull()?.timestampMs ?: 0L
        for ((t, e) in r.events) {
            val s = when (e) {
                is ControlEvent.ClickRejected -> "✗ ${e.reason}"
                is ControlEvent.Tap -> if (e.click) "CLICK" else "tap"
                is ControlEvent.TouchDown -> "touch down"
                is ControlEvent.TouchUp -> "touch up"
                is ControlEvent.Swipe -> "SWIPE ${e.dir.name.lowercase()}"
                is ControlEvent.BindFired -> "bind ${e.label}"
                else -> null
            } ?: continue
            line("    %6.2fs  %s".format((t - t0) / 1000f, s))
        }
    }

    /** Attention scores over a gaze clip: should be high while looking at the screen, low when away. */
    private fun reportGaze(clip: Clip, cal: AttentionCalibration?, line: (String) -> Unit) {
        if (clip.name.startsWith("gaze_calibration")) {
            line("%-46s %6.1f  attention calibration %s".format(clip.label, clip.durationMs / 1000f,
                cal?.let { "yaw %.1f° pitch %.1f° tolerance %.1f°".format(it.yaw, it.pitch, it.spread) } ?: "failed"))
            return
        }
        if (cal == null) { line("%-46s  (no calibration)".format(clip.label)); return }
        val a = Attention()
        val t0 = clip.frames.firstOrNull()?.timestampMs ?: 0L
        val perSecond = clip.frames.groupBy { (it.timestampMs - t0) / 1000 }.map { (_, fs) ->
            fs.map { f -> a.update(f.timestampMs, f.face, cal); a.score }.average()
        }
        line("%-46s %6.1f  attention per second: %s".format(clip.label, clip.durationMs / 1000f,
            perSecond.joinToString(" ") { if (it < 0) "–" else "%.1f".format(it) }))
    }
}
