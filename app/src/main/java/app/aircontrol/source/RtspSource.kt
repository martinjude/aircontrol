package app.aircontrol.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/**
 * Network camera over RTSP (best effort). Media3 decodes the stream into an [ImageReader]; frames
 * are converted from YUV and downsampled to ~640 px. MJPEG usually has lower latency.
 */
@OptIn(UnstableApi::class)
class RtspSource(
    private val context: Context,
    private val url: String,
    private val rotation: Int,
    private val sink: FrameSink,
) : FrameSource {

    override val label = "Network camera (RTSP)"

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var player: ExoPlayer? = null
    private var reader: ImageReader? = null
    private var readerW = 0
    private var readerH = 0
    private var reportedSize = false
    @Volatile private var running = false

    override fun start() {
        if (running) return
        running = true
        val t = HandlerThread("rtsp").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { it.post { createPlayer() } }
    }

    override fun stop() {
        running = false
        val h = handler ?: return
        h.removeCallbacksAndMessages(null)
        h.post {
            releasePlayer()
            thread?.quitSafely()
        }
        handler = null
    }

    private fun createPlayer() {
        if (!running) return
        sink.onStatus("Connecting RTSP…")
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(300, 1000, 100, 100)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(context)
            .setLooper(thread!!.looper)
            .setLoadControl(loadControl)
            .build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        p.volume = 0f
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) ensureReader(videoSize.width, videoSize.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "rtsp error", error)
                sink.onStatus("RTSP error: ${error.errorCodeName} — retrying", error = true)
                handler?.postDelayed({ releasePlayer(); createPlayer() }, 3000)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) sink.onStatus("Buffering RTSP…")
            }
        })
        player = p
        ensureReader(1280, 720)
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()
    }

    private fun ensureReader(w: Int, h: Int) {
        if (reader != null && readerW == w && readerH == h) return
        val old = reader
        val r = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        reader = r
        readerW = w
        readerH = h
        player?.setVideoSurface(r.surface)
        old?.close()
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (!sink.wantsFrame(now)) return
            val bmp = MjpegSource.rotate(yuvToBitmap(img, 640), rotation)
            if (!reportedSize) {
                reportedSize = true
                sink.onStatus("Streaming ${img.width}×${img.height} (RTSP)")
            }
            sink.onFrame(bmp, now)
        } catch (e: Exception) {
            Log.w(TAG, "frame conversion failed", e)
        } finally {
            img.close()
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        reader?.close()
        reader = null
        readerW = 0; readerH = 0
        reportedSize = false
    }

    companion object {
        private const val TAG = "RtspSource"

        /** YUV_420_888 → ARGB with integer downsampling so the long side is about [maxSide]. */
        fun yuvToBitmap(img: Image, maxSide: Int): Bitmap {
            val w = img.width
            val h = img.height
            val step = ((maxOf(w, h) + maxSide - 1) / maxSide).coerceAtLeast(1)
            val ow = w / step
            val oh = h / step
            val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
            val yBuf = ByteArray(yP.buffer.remaining()).also { yP.buffer.get(it) }
            val uBuf = ByteArray(uP.buffer.remaining()).also { uP.buffer.get(it) }
            val vBuf = ByteArray(vP.buffer.remaining()).also { vP.buffer.get(it) }
            val yRow = yP.rowStride; val yPix = yP.pixelStride
            val uvRow = uP.rowStride; val uvPix = uP.pixelStride
            val out = IntArray(ow * oh)
            var o = 0
            for (oy in 0 until oh) {
                val sy = oy * step
                val yBase = sy * yRow
                val uvBase = (sy shr 1) * uvRow
                for (ox in 0 until ow) {
                    val sx = ox * step
                    val yy = ((yBuf[yBase + sx * yPix].toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192
                    val uvi = uvBase + (sx shr 1) * uvPix
                    val u = (uBuf.getOrElse(uvi) { 0 }.toInt() and 0xFF) - 128
                    val v = (vBuf.getOrElse(uvi) { 0 }.toInt() and 0xFF) - 128
                    val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
                    val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                    val b = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
                    out[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
        }
    }
}
