package app.aircontrol.gesture

/**
 * Maps a hand point (aspect-corrected camera space) to normalized screen coordinates.
 *
 * With upper-body tracking the box is anchored next to the shoulder on the side of the active
 * hand and scaled by shoulder width, so the same arm movement covers the screen at any distance
 * from the camera. Without a body (or in [AnchorMode.HAND]) the box is centred on where the hand
 * first appeared and scaled from palm size.
 *
 * [recenter] lets the user pick the hand position that maps to the screen centre. With a body it is
 * stored relative to the shoulders (in shoulder widths) so it keeps working at any distance.
 */
class ControlBox {
    /** Smoothed shoulder width in image heights (estimated from palm size when no body is seen). */
    var shoulderWidth = 0f
        private set
    var bodyVisible = false
        private set

    private var bodyCenter: P3? = null
    private var lastBodyMs = Long.MIN_VALUE / 2
    private var anchor: P3? = null
    private var side = 1
    /** User-chosen centre offset from the body box centre, in shoulder widths. */
    private var offX = 0f
    private var offY = 0f
    /** HAND mode: anchor chosen by [recenter]; survives losing the hand. */
    private var pinnedAnchor: P3? = null

    fun updateBody(pose: PoseObservation?, aspect: Float, tMs: Long) {
        if (pose != null && pose.landmarks.size > PoseLm.RIGHT_SHOULDER) {
            val visL = pose.visibility.getOrElse(PoseLm.LEFT_SHOULDER) { 0f }
            val visR = pose.visibility.getOrElse(PoseLm.RIGHT_SHOULDER) { 0f }
            if (visL > 0.5f && visR > 0.5f) {
                val l = pose.landmarks[PoseLm.LEFT_SHOULDER].let { P3(it.x * aspect, it.y) }
                val r = pose.landmarks[PoseLm.RIGHT_SHOULDER].let { P3(it.x * aspect, it.y) }
                val w = l.dist2d(r)
                if (w > 0.03f) {
                    val c = mid(l, r)
                    shoulderWidth = if (shoulderWidth == 0f || !bodyVisible) w else lerp(shoulderWidth, w, 0.1f)
                    bodyCenter = bodyCenter?.takeIf { bodyVisible }?.let { lerp(it, c, 0.1f) } ?: c
                    lastBodyMs = tMs
                }
            }
        }
        bodyVisible = tMs - lastBodyMs < BODY_TIMEOUT_MS
    }

    /** Called for every frame with a tracked hand; [pointer] is the point that drives the pointer. */
    fun updateHand(features: HandFeatures, pointer: P3) {
        if (!bodyVisible) {
            val est = features.palmSize * PALM_TO_SHOULDERS
            shoulderWidth = if (shoulderWidth == 0f) est else lerp(shoulderWidth, est, 0.05f)
        }
        if (anchor == null) {
            anchor = pinnedAnchor ?: pointer
            side = bodyCenter?.takeIf { bodyVisible }?.let { if (features.palmCenter.x < it.x) -1 else 1 } ?: 1
        }
    }

    /** Hand lost: the next hand re-anchors. */
    fun release() {
        anchor = null
    }

    /** Makes [p] (current pointer point) map to the screen centre. */
    fun recenter(p: P3, mode: AnchorMode) {
        val body = bodyCenter
        if (mode == AnchorMode.BODY && bodyVisible && body != null) {
            val sw = shoulderWidth.coerceAtLeast(0.05f)
            val base = bodyBoxCenter(body, sw)
            offX = (p.x - base.x) / sw
            offY = (p.y - base.y) / sw
        } else {
            pinnedAnchor = p
            anchor = p
        }
    }

    /** Forgets [recenter] (on disarm). */
    fun clearRecenter() {
        offX = 0f; offY = 0f
        pinnedAnchor = null
    }

    private fun bodyBoxCenter(body: P3, sw: Float) = P3(body.x + side * SIDE_OFFSET * sw, body.y - UP_OFFSET * sw)

    fun map(p: P3, sensitivity: Float, mode: AnchorMode): Pair<Float, Float> {
        val sw = shoulderWidth.coerceAtLeast(0.05f)
        val s = sensitivity.coerceIn(0.25f, 4f)
        val w = BOX_W * sw / s
        val h = BOX_H * sw / s
        val body = bodyCenter
        val c = if (mode == AnchorMode.BODY && bodyVisible && body != null) {
            bodyBoxCenter(body, sw).let { P3(it.x + offX * sw, it.y + offY * sw) }
        } else {
            anchor ?: p
        }
        val u = ((p.x - c.x) / w + 0.5f).coerceIn(0f, 1f)
        val v = ((p.y - c.y) / h + 0.5f).coerceIn(0f, 1f)
        return u to v
    }

    private companion object {
        const val BODY_TIMEOUT_MS = 1000L
        /** Adult shoulder width is roughly 3.5x the wrist-to-middle-knuckle length. */
        const val PALM_TO_SHOULDERS = 3.5f
        const val BOX_W = 0.85f
        const val BOX_H = 0.70f
        const val SIDE_OFFSET = 0.45f
        /** The index fingertip rests ~0.3 shoulder widths above the knuckles. */
        const val UP_OFFSET = 0.35f
    }
}
