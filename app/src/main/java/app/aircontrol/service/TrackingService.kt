package app.aircontrol.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.aircontrol.R
import app.aircontrol.gesture.AttentionCalibration
import app.aircontrol.gesture.CalibrationKind
import app.aircontrol.gesture.ControlEvent
import app.aircontrol.gesture.DisarmReason
import app.aircontrol.gesture.EngineConfig
import app.aircontrol.gesture.FrameObservation
import app.aircontrol.gesture.GestureEngine
import app.aircontrol.gesture.P3
import app.aircontrol.gesture.PushCalibration
import app.aircontrol.settings.AppSettings
import app.aircontrol.settings.SettingsRepository
import app.aircontrol.settings.SourceType
import app.aircontrol.source.DeviceCameraSource
import app.aircontrol.source.FrameSink
import app.aircontrol.source.FrameSource
import app.aircontrol.source.MjpegSource
import app.aircontrol.source.RtspSource
import app.aircontrol.ui.MainActivity
import app.aircontrol.vision.VisionPipeline
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Foreground service: frame source → MediaPipe → [GestureEngine] → [ControlBus].
 * Must be started from the foreground (the app's Start button) so the camera keeps working
 * while other apps are in front.
 */
class TrackingService : LifecycleService(), FrameSink, VisionPipeline.Listener {

    companion object {
        private const val TAG = "TrackingService"
        const val ACTION_START = "app.aircontrol.action.START"
        const val ACTION_STOP = "app.aircontrol.action.STOP"
        const val ACTION_TOGGLE_ARM = "app.aircontrol.action.TOGGLE_ARM"
        const val ACTION_CALIBRATE = "app.aircontrol.action.CALIBRATE"
        const val EXTRA_KIND = "kind"
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val IDLE_FPS = 12
        private const val ARMED_FPS = 30

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            if (ControlBus.state.value.running) {
                context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
            }
        }

        /** Runs calibrations one after the other (tracking must be running); the overlay guides the user. */
        fun calibrate(context: Context, vararg kinds: CalibrationKind) {
            if (ControlBus.state.value.running && kinds.isNotEmpty()) {
                context.startService(
                    Intent(context, TrackingService::class.java).setAction(ACTION_CALIBRATE)
                        .putExtra(EXTRA_KIND, kinds.joinToString(",") { it.name })
                )
            }
        }

        fun toggleArm(context: Context) {
            if (ControlBus.state.value.running) {
                context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_TOGGLE_ARM))
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val engineThread = Executors.newSingleThreadExecutor { Thread(it, "gesture-engine") }
    private lateinit var repo: SettingsRepository
    private lateinit var engine: GestureEngine
    private lateinit var displayManager: DisplayManager

    @Volatile private var settings = AppSettings()
    @Volatile private var running = false
    @Volatile private var armed = false
    @Volatile private var screenOn = true
    @Volatile private var lastAcceptedMs = 0L

    private var pipeline: VisionPipeline? = null
    private var source: FrameSource? = null
    private var sourceKey = ""
    private var receiversRegistered = false

    // Stats (MediaPipe result thread).
    private var statFrames = 0
    private var statStart = 0L
    private var lastStateMs = 0L
    private var lastPreviewMs = 0L

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository(this)
        displayManager = getSystemService(DisplayManager::class.java)
        engine = GestureEngine(EngineConfig(), ::onEngineEvent)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_TOGGLE_ARM -> if (running) {
                engineThread.execute { if (engine.armed) engine.disarmNow() else engine.armNow() }
            } else {
                stopSelf()
            }
            ACTION_CALIBRATE -> if (running) {
                val kinds = intent.getStringExtra(EXTRA_KIND).orEmpty().split(",")
                    .mapNotNull { k -> CalibrationKind.entries.firstOrNull { it.name == k } }
                calQueue.clear()
                calQueue.addAll(kinds)
                startNextCalibration()
            }
            else -> if (!running) begin()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- lifecycle

    private fun begin() {
        settings = runBlocking { repo.flow.first() }
        val needsCamera = settings.source != SourceType.NETWORK
        if (needsCamera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // A started foreground service must call startForeground; do it with a type that needs no permission.
            if (!goForeground(network = true)) return
            Toast.makeText(this, "AirControl needs the camera permission", Toast.LENGTH_LONG).show()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (!goForeground(network = !needsCamera)) {
            stopSelf()
            return
        }
        running = true
        applyEngineConfig()
        registerReceivers()
        ControlBus.state.value = TrackingState(running = true, sourceStatus = "Loading models…")

        val p = VisionPipeline(this, this).apply {
            mirror = settings.mirror
            poseEnabled = settings.usePose
            swapHands = settings.swapHands
            faceEnabled = settings.attentionGate && settings.attention != null
        }
        pipeline = p
        val accel = settings.accelerator
        Thread({
            p.start(accel)
            ControlBus.state.update { it.copy(delegate = p.delegateName) }
            main.post { if (running && pipeline === p) restartSource() }
        }, "model-loader").start()

        lifecycleScope.launch {
            repo.flow.collect { onSettingsChanged(it) }
        }
    }

    private fun shutdown() {
        if (running) {
            running = false
            unregisterReceivers()
            source?.stop()
            source = null
            sourceKey = ""
            engineThread.execute { engine.disarmNow(DisarmReason.MANUAL) }
            val p = pipeline
            pipeline = null
            // Give in-flight MediaPipe callbacks a moment before closing the graphs.
            main.postDelayed({ Thread({ p?.close() }, "model-close").start() }, 300)
            ControlBus.state.value = TrackingState(running = false)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) shutdown()
        engineThread.shutdown()
        super.onDestroy()
    }

    private fun onSettingsChanged(s: AppSettings) {
        if (!running) return
        val old = settings
        settings = s
        pipeline?.apply {
            mirror = s.mirror
            poseEnabled = s.usePose
            swapHands = s.swapHands
            faceEnabled = s.attentionGate && s.attention != null
        }
        applyEngineConfig()
        if (s.sourceKey() != old.sourceKey() || s.source != old.source) {
            val network = s.source == SourceType.NETWORK
            if (!network && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                onStatus("Camera permission missing", error = true)
                source?.stop(); source = null; sourceKey = ""
                return
            }
            goForeground(network)
            if (pipeline?.ready == true) restartSource()
        }
        if (s.accelerator != old.accelerator) restartPipeline()
        updateNotification()
    }

    private fun restartSource() {
        source?.stop()
        source = null
        val s = settings
        sourceKey = s.sourceKey()
        val src: FrameSource? = when (s.source) {
            SourceType.FRONT -> DeviceCameraSource(this, this, front = true, sink = this)
            SourceType.BACK -> DeviceCameraSource(this, this, front = false, sink = this)
            SourceType.NETWORK -> {
                val url = s.networkUrl.trim()
                when {
                    url.isEmpty() -> null.also { onStatus("Set the network camera URL on the Camera tab", error = true) }
                    url.startsWith("rtsp", ignoreCase = true) -> RtspSource(this, url, s.networkRotation, this)
                    else -> MjpegSource(url, s.networkRotation, this)
                }
            }
        }
        source = src
        ControlBus.state.update { it.copy(sourceLabel = src?.label ?: "No source") }
        src?.takeIf { screenOn }?.start()
        updateNotification()
    }

    private fun restartPipeline() {
        source?.stop()
        source = null
        val old = pipeline
        main.postDelayed({ Thread({ old?.close() }, "model-close").start() }, 300)
        val p = VisionPipeline(this, this).apply {
            mirror = settings.mirror
            poseEnabled = settings.usePose
            swapHands = settings.swapHands
            faceEnabled = settings.attentionGate && settings.attention != null
        }
        pipeline = p
        val accel = settings.accelerator
        Thread({
            p.start(accel)
            ControlBus.state.update { it.copy(delegate = p.delegateName) }
            main.post { if (running && pipeline === p) restartSource() }
        }, "model-loader").start()
    }

    private fun applyEngineConfig() {
        val size = screenSize()
        val density = resources.displayMetrics.density
        val s = settings
        engineThread.execute {
            engine.config = s.applyTo(engine.config.copy(screenW = size.x, screenH = size.y, density = density))
        }
    }

    // ---------------------------------------------------------------- frames

    override fun wantsFrame(nowMs: Long): Boolean {
        val p = pipeline
        if (!running || !screenOn || p == null || !p.ready || p.busy(nowMs)) return false
        val fast = armed || ControlBus.preview.subscriptionCount.value > 0 || ControlBus.snapshot.subscriptionCount.value > 0
        val interval = 1000L / (if (fast) ARMED_FPS else IDLE_FPS)
        if (nowMs - lastAcceptedMs < interval - 4) return false
        lastAcceptedMs = nowMs
        return true
    }

    override fun onFrame(bitmap: Bitmap, nowMs: Long) {
        pipeline?.submit(bitmap, armed)
    }

    override fun onStatus(status: String, error: Boolean) {
        ControlBus.state.update { it.copy(sourceStatus = status, sourceError = error) }
    }

    override fun onObservation(obs: FrameObservation, frame: Bitmap, inferenceMs: Long) {
        if (!running) return
        val snapshotWanted = ControlBus.snapshot.subscriptionCount.value > 0
        val previewWanted = ControlBus.preview.subscriptionCount.value > 0
        engineThread.execute {
            engine.process(obs)
            val dbg = engine.debug
            if (dbg.sign != loggedSign) {
                loggedSign = dbg.sign
                Log.i(TAG, "sign $loggedSign (hands=${obs.hands.size}, body=${engine.bodyVisible})")
            }
            // Published from the engine thread so landmarks and the engine's verdict are of the same frame.
            if (snapshotWanted || previewWanted) {
                val snap = TrackingSnapshot(obs.timestampMs, obs.imageAspect, obs.hands, obs.pose, dbg)
                ControlBus.snapshot.value = snap
                val now = SystemClock.uptimeMillis()
                if (previewWanted && now - lastPreviewMs > 60) {
                    lastPreviewMs = now
                    ControlBus.preview.value = PreviewFrame(frame, settings.mirror, snap)
                }
            }
        }

        val now = SystemClock.uptimeMillis()
        if (statStart == 0L) statStart = now
        statFrames++
        if (now - lastStateMs > 250) {
            lastStateMs = now
            val elapsed = (now - statStart).coerceAtLeast(1)
            val fps = statFrames * 1000f / elapsed
            if (elapsed > 2000) { statStart = now; statFrames = 0 }
            val dbg = engine.debug
            ControlBus.state.update {
                it.copy(
                    fps = fps,
                    inferenceMs = inferenceMs,
                    sign = dbg.sign,
                    pinch = dbg.gap,
                    handVisible = obs.hands.isNotEmpty(),
                    bodyVisible = engine.bodyVisible,
                    sourceError = false,
                )
            }
        }
    }

    override fun onPipelineError(message: String) {
        Log.e(TAG, message)
        onStatus(message, error = true)
    }

    private var loggedSign = ""

    private fun onEngineEvent(e: ControlEvent) {
        ControlBus.events.tryEmit(e)
        val changed = when (e) {
            ControlEvent.Armed -> { armed = true; true }
            is ControlEvent.Disarmed -> { armed = false; true }
            is ControlEvent.Calibration -> {
                if (e.done) onCalibrated(e)
                false
            }
            else -> false
        }
        if (changed) {
            ControlBus.state.update { it.copy(armed = armed) }
            main.post { updateNotification() }
        }
    }

    /** Calibrations still to run (a "calibrate everything" sequence). Touched on the main thread only. */
    private val calQueue = ArrayDeque<CalibrationKind>()

    private fun startNextCalibration() {
        val kind = calQueue.removeFirstOrNull() ?: return
        pipeline?.faceAlways = kind == CalibrationKind.ATTENTION
        engineThread.execute { engine.startCalibration(kind) }
    }

    private fun onCalibrated(e: ControlEvent.Calibration) {
        main.post {
            pipeline?.faceAlways = false
            val r = e.result
            if (r != null) {
                lifecycleScope.launch {
                    repo.update {
                        when (e.kind) {
                            CalibrationKind.HAND -> it.copy(handReference = P3(r[0], r[1], r[2]))
                            CalibrationKind.PUSH -> it.copy(pushCalibration = PushCalibration.fromResult(r))
                            CalibrationKind.ATTENTION -> it.copy(attention = AttentionCalibration(r[0], r[1], r[2]))
                        }
                    }
                }
                // Next step after a short pause, so the "calibrated" message can be read.
                main.postDelayed({ startNextCalibration() }, 1200)
            } else {
                calQueue.clear()
            }
        }
    }

    // ---------------------------------------------------------------- system

    private fun goForeground(network: Boolean): Boolean {
        val type = when {
            Build.VERSION.SDK_INT >= 34 ->
                if (network) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            Build.VERSION.SDK_INT >= 30 ->
                if (network) ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST else ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            else -> 0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            Toast.makeText(this, "AirControl could not start: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_TOGGLE_ARM), flags,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), flags,
        )
        val label = source?.label ?: when (settings.source) {
            SourceType.FRONT -> "Front camera"
            SourceType.BACK -> "Back camera"
            SourceType.NETWORK -> "Network camera"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_aircontrol)
            .setContentTitle(if (armed) "Gesture control ON" else "AirControl is watching for 🤟")
            .setContentText(if (armed) "Hold 🤟 for 1 s to turn it off" else "$label · hold 🤟 for 1 s to take control")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, if (armed) "Disarm" else "Arm now", toggle)
            .addAction(0, "Stop", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Gesture tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while AirControl is watching the camera"
                setShowBadge(false)
            }
        )
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    engineThread.execute { engine.disarmNow(DisarmReason.MANUAL) }
                    source?.stop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    if (running) source?.start()
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) applyEngineConfig()
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        receiversRegistered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        displayManager.registerDisplayListener(displayListener, main)
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        receiversRegistered = false
        unregisterReceiver(screenReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Point {
        val p = Point()
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
        if (p.x == 0) {
            val m = resources.displayMetrics
            p.set(m.widthPixels, m.heightPixels)
        }
        return p
    }
}
