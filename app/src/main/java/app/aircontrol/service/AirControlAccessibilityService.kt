package app.aircontrol.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import app.aircontrol.gesture.ClickMethod
import app.aircontrol.gesture.ControlEvent
import app.aircontrol.overlay.OverlayController
import app.aircontrol.settings.SettingsRepository
import app.aircontrol.settings.TrackingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Performs what the gesture engine decides: injects taps/swipes/drags, triggers Back/Home/Recents
 * and draws the overlay. It never reads window content.
 */
class AirControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AirControl"
        private val connectedFlow = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = connectedFlow
    }

    private var scope: CoroutineScope? = null
    private var overlay: OverlayController? = null
    private var dispatcher: GestureDispatcher? = null
    private var actions: ActionRunner? = null
    private var debugJob: Job? = null
    @Volatile private var swipeLength = 0.4f

    override fun onServiceConnected() {
        super.onServiceConnected()
        val ov = OverlayController(this)
        val gd = GestureDispatcher(this)
        actions = ActionRunner(this)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        overlay = ov
        dispatcher = gd
        scope = sc
        sc.launch { ControlBus.events.collect { handle(it) } }
        sc.launch {
            SettingsRepository(this@AirControlAccessibilityService).flow.collect { s ->
                swipeLength = s.swipeLength
                ov.view.colors = s.glow.colors
                ov.view.previewSize = s.previewSize
                ov.view.previewImage = s.previewImage
                ov.view.showLog = s.showLog
                ov.view.trackingView = s.trackingView
                ov.view.pushEnabled = s.clickMethod != ClickMethod.TAP
                setDebugFeeds(s.trackingView != TrackingView.OFF, s.previewImage, keepAlive = s.trackingView == TrackingView.DIAGNOSTICS)
            }
        }
        connectedFlow.value = true
    }

    private var debugImage = false
    private var debugKeepAlive = false

    /**
     * Feeds the tracking view. [keepAlive] keeps the overlay window up while tracking runs (the
     * diagnostics panel is shown even when disarmed; the compact view only while armed).
     */
    private fun setDebugFeeds(enabled: Boolean, image: Boolean, keepAlive: Boolean) {
        val sc = scope ?: return
        val ov = overlay ?: return
        if (enabled == (debugJob != null) && (!enabled || (image == debugImage && keepAlive == debugKeepAlive))) return
        debugJob?.cancel()
        debugJob = null
        debugImage = image
        debugKeepAlive = keepAlive
        if (enabled) {
            debugJob = sc.launch {
                launch { ControlBus.snapshot.collect { ov.view.snapshot = it } }
                launch {
                    ControlBus.state.collect { s ->
                        ov.view.trackingState = s
                        ov.keepAlive = s.running && keepAlive
                    }
                }
                if (image) launch { ControlBus.preview.collect { ov.view.previewFrame = it } }
            }
        } else {
            ov.keepAlive = false
            ov.view.snapshot = null
            ov.view.previewFrame = null
        }
    }

    private fun handle(e: ControlEvent) {
        val ov = overlay ?: return
        val gd = dispatcher ?: return
        ov.onEvent(e)
        if (e !is ControlEvent.Pointer && e !is ControlEvent.TouchMove && e !is ControlEvent.ArmProgress &&
            e !is ControlEvent.BindProgress && e !is ControlEvent.Calibration
        ) {
            Log.i(TAG, "action $e")
        }
        val size = ov.screenSize()
        val w = size.x.toFloat()
        val h = size.y.toFloat()
        when (e) {
            is ControlEvent.Tap -> gd.tap(e.x * w, e.y * h)
            is ControlEvent.TouchDown -> gd.touchDown(e.x * w, e.y * h)
            is ControlEvent.TouchMove -> gd.touchMove(e.x * w, e.y * h)
            is ControlEvent.TouchUp -> gd.touchUp(e.x * w, e.y * h)
            is ControlEvent.Swipe -> gd.swipe(e.dir, size.x, size.y, swipeLength)
            is ControlEvent.BindFired -> if (actions?.run(e.action, e.appPackage) == false) {
                Log.w(TAG, "bind action ${e.action} (${e.appPackage}) failed")
            }
            is ControlEvent.Disarmed -> gd.cancel()
            else -> {}
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        connectedFlow.value = false
        debugJob = null
        scope?.cancel()
        scope = null
        overlay?.detach()
        overlay = null
        dispatcher = null
        actions = null
        super.onDestroy()
    }
}
