package app.aircontrol.gesture

/**
 * Tracks the user's shoulder width (in image heights) and whether a person is in view. The swipe
 * threshold is expressed in shoulder widths so strokes feel the same at any distance from the
 * camera; without a body the width is estimated from the palm size.
 */
class BodyScale {
    /** Smoothed shoulder width in image heights (estimated from palm size when no body is seen). */
    var shoulderWidth = 0f
        private set
    var bodyVisible = false
        private set

    private var lastBodyMs = Long.MIN_VALUE / 2

    fun updateBody(pose: PoseObservation?, aspect: Float, tMs: Long) {
        if (pose != null && pose.landmarks.size > PoseLm.RIGHT_SHOULDER) {
            val visL = pose.visibility.getOrElse(PoseLm.LEFT_SHOULDER) { 0f }
            val visR = pose.visibility.getOrElse(PoseLm.RIGHT_SHOULDER) { 0f }
            if (visL > 0.5f && visR > 0.5f) {
                val l = pose.landmarks[PoseLm.LEFT_SHOULDER].let { P3(it.x * aspect, it.y) }
                val r = pose.landmarks[PoseLm.RIGHT_SHOULDER].let { P3(it.x * aspect, it.y) }
                val w = l.dist2d(r)
                if (w > 0.03f) {
                    shoulderWidth = if (shoulderWidth == 0f || !bodyVisible) w else lerp(shoulderWidth, w, 0.1f)
                    lastBodyMs = tMs
                }
            }
        }
        bodyVisible = tMs - lastBodyMs < BODY_TIMEOUT_MS
    }

    /** Called for every frame with a tracked hand. */
    fun updateHand(features: HandFeatures) {
        if (!bodyVisible) {
            val est = features.palmSize * PALM_TO_SHOULDERS
            shoulderWidth = if (shoulderWidth == 0f) est else lerp(shoulderWidth, est, 0.05f)
        }
    }

    private companion object {
        const val BODY_TIMEOUT_MS = 1000L
        /** Adult shoulder width is roughly 3.5x the wrist-to-middle-knuckle length. */
        const val PALM_TO_SHOULDERS = 3.5f
    }
}
