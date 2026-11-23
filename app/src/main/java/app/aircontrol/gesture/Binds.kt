package app.aircontrol.gesture

/**
 * Hand poses that can trigger a bind. 🤟 is reserved for arming/disarming and pointing is the
 * cursor pose, so neither is bindable. [custom] poses are classified from the landmarks
 * ([HandPose]); the others come from MediaPipe's gesture recognizer.
 */
enum class Pose(val emoji: String, val label: String, val defaultHoldMs: Int, val custom: Boolean = false) {
    FIST("✊", "Fist", 800),
    THUMB_UP("👍", "Thumb up", 600),
    THUMB_DOWN("👎", "Thumb down", 500),
    VICTORY("✌️", "Victory", 600),
    OPEN_PALM("🖐", "Open palm", 1000),
    SHAKA("🤙", "Shaka (thumb + pinky)", 800, custom = true),
    THREE("3️⃣", "Three fingers", 800, custom = true),
    FOUR("4️⃣", "Four fingers, thumb tucked", 800, custom = true),
}

/** One hand of a bind: a pose, optionally limited to the left or right hand. */
data class HandSpec(val pose: Pose, val side: HandSide = HandSide.ANY) {
    fun matches(pose: Pose?, side: HandSide): Boolean =
        pose == this.pose && (this.side == HandSide.ANY || this.side == side)

    val specificity: Int get() = if (side == HandSide.ANY) 0 else 1
}

/**
 * A hot bind: hold [first] (and [second] with the other hand, for a two-hand bind) for [holdMs]
 * to trigger [action].
 */
data class Bind(
    val id: String,
    val first: HandSpec,
    val second: HandSpec? = null,
    val action: SystemAction,
    /** Package to open for [SystemAction.LAUNCH_APP]. */
    val appPackage: String? = null,
    /** Display name of that app (stored so it can be shown without a package lookup). */
    val appLabel: String? = null,
    val holdMs: Int = if (second != null) maxOf(first.pose.defaultHoldMs, second.pose.defaultHoldMs) else first.pose.defaultHoldMs,
    val enabled: Boolean = true,
) {
    val twoHands: Boolean get() = second != null

    /** What the bind does, for the overlay and the lists. */
    val actionLabel: String
        get() = if (action == SystemAction.LAUNCH_APP) "Open ${appLabel ?: appPackage ?: "app"}" else action.label

    /** Same trigger = same key (to warn about duplicates). Two-hand triggers are unordered. */
    val triggerKey: String
        get() {
            val a = "${first.pose}:${first.side}"
            val b = second?.let { "${it.pose}:${it.side}" } ?: return a
            return listOf(a, b).sorted().joinToString("+")
        }

    /** Higher wins when several binds match at once: two hands first, then side-specific ones. */
    val priority: Int get() = (if (twoHands) 10 else 0) + first.specificity + (second?.specificity ?: 0)

    val triggerLabel: String
        get() {
            fun spec(s: HandSpec) = s.pose.emoji + when (s.side) {
                HandSide.ANY -> ""
                HandSide.LEFT -> "L"
                HandSide.RIGHT -> "R"
            }
            return second?.let { "${spec(first)} + ${spec(it)}" } ?: spec(first)
        }
}

object Binds {
    /** The v2 sign mappings: 👎 Back, ✊ Home, ✌️ Recents. */
    fun defaults(): List<Bind> = listOf(
        Bind("d-back", HandSpec(Pose.THUMB_DOWN), action = SystemAction.BACK),
        Bind("d-home", HandSpec(Pose.FIST), action = SystemAction.HOME),
        Bind("d-recents", HandSpec(Pose.VICTORY), action = SystemAction.RECENTS),
    )

    /** Builds binds from the four fixed sign settings of v2 (👎 ✊ ✌️ 👍). */
    fun legacy(thumbDown: SystemAction, fist: SystemAction, victory: SystemAction, thumbUp: SystemAction): List<Bind> =
        listOf(
            Triple("d-back", Pose.THUMB_DOWN, thumbDown),
            Triple("d-home", Pose.FIST, fist),
            Triple("d-recents", Pose.VICTORY, victory),
            Triple("d-thumbup", Pose.THUMB_UP, thumbUp),
        ).filter { it.third != SystemAction.NONE }
            .map { (id, pose, action) -> Bind(id, HandSpec(pose), action = action) }

    fun newId(): String = java.lang.Long.toHexString(System.nanoTime() xor (Math.random() * 1e9).toLong())
}

/**
 * Line-based text format for storing binds in preferences:
 * `id|enabled|pose:side|pose:side or -|ACTION|package or -|label or -|holdMs`.
 */
object BindCodec {
    fun encode(binds: List<Bind>): String = binds.joinToString("\n") { b ->
        listOf(
            esc(b.id),
            if (b.enabled) "1" else "0",
            "${b.first.pose.name}:${b.first.side.name}",
            b.second?.let { "${it.pose.name}:${it.side.name}" } ?: "-",
            b.action.name,
            esc(b.appPackage ?: "-"),
            esc(b.appLabel ?: "-"),
            b.holdMs.toString(),
        ).joinToString("|")
    }

    /** Malformed lines are skipped. */
    fun decode(text: String): List<Bind> = text.lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val p = line.split("|")
        if (p.size != 8) return@mapNotNull null
        try {
            val first = spec(p[2]) ?: return@mapNotNull null
            val second = if (p[3] == "-") null else spec(p[3]) ?: return@mapNotNull null
            Bind(
                id = unesc(p[0]),
                enabled = p[1] == "1",
                first = first,
                second = second,
                action = SystemAction.valueOf(p[4]),
                appPackage = unesc(p[5]).takeIf { it != "-" },
                appLabel = unesc(p[6]).takeIf { it != "-" },
                holdMs = p[7].toInt().coerceIn(200, 5000),
            )
        } catch (e: IllegalArgumentException) {
            null
        }
    }.toList()

    private fun spec(s: String): HandSpec? {
        val (pose, side) = s.split(":").takeIf { it.size == 2 } ?: return null
        return HandSpec(Pose.valueOf(pose), HandSide.valueOf(side))
    }

    private fun esc(s: String) = s.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A")
    private fun unesc(s: String) = s.replace("%0A", "\n").replace("%7C", "|").replace("%25", "%")
}
