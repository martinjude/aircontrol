package app.aircontrol.gesture

/** Maps a tracked hand to a bindable [Pose]. */
object HandPose {
    /**
     * Custom finger-pattern poses win (they are exact patterns of extended fingers and thumb), then
     * MediaPipe's canned signs above [minScore]. 🤟 (arm sign) and pointing are not bindable poses.
     */
    fun classify(obs: HandObservation, f: HandFeatures, minScore: Float): Pose? {
        val up = f.fingerUp // index, middle, ring, pinky
        val thumb = f.thumbOut
        when {
            thumb && !up[0] && !up[1] && !up[2] && up[3] -> return Pose.SHAKA
            !thumb && up[0] && up[1] && up[2] && !up[3] -> return Pose.THREE
            !thumb && up[0] && up[1] && up[2] && up[3] -> return Pose.FOUR
        }
        if (obs.gestureScore < minScore) return null
        return when (obs.gesture) {
            Signs.CLOSED_FIST -> Pose.FIST
            Signs.THUMB_UP -> Pose.THUMB_UP
            Signs.THUMB_DOWN -> Pose.THUMB_DOWN
            Signs.VICTORY -> Pose.VICTORY
            Signs.OPEN_PALM -> Pose.OPEN_PALM
            else -> null
        }
    }
}
