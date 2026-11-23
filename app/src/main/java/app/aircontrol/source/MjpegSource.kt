package app.aircontrol.source

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network camera over HTTP: an MJPEG stream (multipart/x-mixed-replace, e.g. IP Webcam
 * `http://IP:8080/video`, DroidCam `http://IP:4747/video`, ESP32-CAM `http://IP:81/stream`) or a
 * single-JPEG snapshot URL that is polled. Credentials may be given as `http://user:pass@host/...`.
 */
class MjpegSource(
    private val url: String,
    private val rotation: Int,
    private val sink: FrameSink,
) : FrameSource {

    override val label = "Network camera (MJPEG)"

    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    private var thread: Thread? = null
    private var sampleSize = 0
    private var reportedSize = false

    override fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "mjpeg").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        connection?.disconnect()
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var backoff = 1000L
        while (running) {
            try {
                sink.onStatus("Connecting to ${URL(url).host}…")
                val conn = open()
                val type = conn.contentType.orEmpty().lowercase()
                if (type.startsWith("image/")) {
                    conn.disconnect()
                    pollSnapshots()
                } else {
                    readStream(conn.inputStream)
                }
                backoff = 1000L
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "stream failed", e)
                sink.onStatus("Stream error: ${e.message ?: e.javaClass.simpleName} — retrying", error = true)
                reportedSize = false
                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    break
                }
                backoff = (backoff * 2).coerceAtMost(8000L)
            } finally {
                connection?.disconnect()
                connection = null
            }
        }
    }

    private fun open(): HttpURLConnection {
        val u = URL(url)
        val clean = if (u.userInfo != null) URL(u.protocol, u.host, u.port, u.file) else u
        val conn = clean.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.useCaches = false
        u.userInfo?.let {
            val token = Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        connection = conn
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
        return conn
    }

    /** Scans the byte stream for JPEG start/end markers; robust to any multipart framing. */
    private fun readStream(input: InputStream) {
        val chunk = ByteArray(32 * 1024)
        var frame = ByteArray(256 * 1024)
        var len = 0
        var inFrame = false
        var prev = -1
        while (running) {
            val n = input.read(chunk)
            if (n < 0) throw EOFException("stream ended")
            for (i in 0 until n) {
                val b = chunk[i].toInt() and 0xFF
                if (!inFrame) {
                    if (prev == 0xFF && b == 0xD8) {
                        inFrame = true
                        frame[0] = 0xFF.toByte(); frame[1] = 0xD8.toByte()
                        len = 2
                    }
                } else {
                    if (len == frame.size) {
                        if (frame.size >= MAX_FRAME) { inFrame = false; prev = b; continue }
                        frame = frame.copyOf(frame.size * 2)
                    }
                    frame[len++] = b.toByte()
                    if (prev == 0xFF && b == 0xD9) {
                        inFrame = false
                        onJpeg(frame, len)
                    }
                }
                prev = b
            }
        }
    }

    private fun pollSnapshots() {
        while (running) {
            val now = SystemClock.uptimeMillis()
            if (!sink.wantsFrame(now)) {
                Thread.sleep(15)
                continue
            }
            val conn = open()
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            onJpeg(bytes, bytes.size)
        }
    }

    private fun onJpeg(data: ByteArray, len: Int) {
        val now = SystemClock.uptimeMillis()
        if (!sink.wantsFrame(now)) return
        if (sampleSize == 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, len, bounds)
            if (bounds.outWidth <= 0) return
            var s = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= TARGET_LONG_SIDE) s *= 2
            sampleSize = s
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(data, 0, len, opts) ?: return
        val bmp = rotate(decoded, rotation)
        if (!reportedSize) {
            reportedSize = true
            sink.onStatus("Streaming ${bmp.width}×${bmp.height}")
        }
        sink.onFrame(bmp, now)
    }

    companion object {
        private const val TAG = "MjpegSource"
        private const val MAX_FRAME = 8 * 1024 * 1024
        private const val TARGET_LONG_SIDE = 640

        fun rotate(src: Bitmap, degrees: Int): Bitmap {
            if (degrees % 360 == 0) return src
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        }
    }
}
