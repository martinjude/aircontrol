package app.aircontrol.source

import android.graphics.Bitmap

/** Receives frames from a [FrameSource]; all calls come from the source's own worker thread. */
interface FrameSink {
    /** Asked before a frame is decoded/converted, so throttled frames cost almost nothing. */
    fun wantsFrame(nowMs: Long): Boolean

    /** An upright (display-oriented), un-mirrored frame. The sink owns the bitmap. */
    fun onFrame(bitmap: Bitmap, nowMs: Long)

    fun onStatus(status: String, error: Boolean = false)
}

interface FrameSource {
    val label: String
    fun start()
    fun stop()
}
