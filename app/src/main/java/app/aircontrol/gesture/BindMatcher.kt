package app.aircontrol.gesture

/** A tracked hand with its classified pose, as seen by the engine in one frame. */
internal class TrackedHand(val obs: HandObservation, val feat: HandFeatures, val pose: Pose?)

/**
 * Matches the poses of the visible hands against the user's binds and times the hold.
 * One bind is the candidate at a time: two-hand binds beat side-specific ones, which beat plain
 * ones; ties go to the earlier bind in the list. A bind fires once per entry (the pose must be
 * dropped for a moment before it can fire again).
 */
internal class BindMatcher {

    class Result(val candidate: Bind?, val progress: Float, val fired: Bind?)

    private val hold = HoldTracker()

    /** True while a bind's hold is building (the pointer is frozen and clicks suppressed). */
    val holding: Boolean get() = hold.current != null

    fun update(t: Long, hands: List<TrackedHand>, binds: List<Bind>, gapMs: Long, enabled: Boolean): Result {
        val best = if (enabled) best(hands, binds) else null
        hold.update(best?.id, t, gapMs)
        val cur = hold.current ?: return Result(null, 0f, null)
        val bind = binds.firstOrNull { it.id == cur } ?: run { hold.cancel(); return Result(null, 0f, null) }
        val elapsed = hold.elapsed()
        if (elapsed >= bind.holdMs) {
            hold.fire()
            return Result(bind, 1f, bind)
        }
        return Result(bind, (elapsed.toFloat() / bind.holdMs).coerceIn(0f, 1f), null)
    }

    fun cancel() = hold.cancel()

    fun reset() = hold.reset()

    private fun best(hands: List<TrackedHand>, binds: List<Bind>): Bind? {
        if (hands.isEmpty()) return null
        var best: Bind? = null
        for (b in binds) {
            if (!b.enabled || b.action == SystemAction.NONE || !satisfied(b, hands)) continue
            if (best == null || b.priority > best.priority) best = b
        }
        return best
    }

    private fun satisfied(b: Bind, hands: List<TrackedHand>): Boolean {
        val second = b.second
        if (second == null) return hands.any { b.first.matches(it.pose, it.obs.side) }
        if (hands.size < 2) return false
        for (i in hands.indices) for (j in hands.indices) {
            if (i == j) continue
            if (b.first.matches(hands[i].pose, hands[i].obs.side) && second.matches(hands[j].pose, hands[j].obs.side)) return true
        }
        return false
    }
}
