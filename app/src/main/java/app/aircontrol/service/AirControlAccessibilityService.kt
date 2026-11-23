package app.aircontrol.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import app.aircontrol.gesture.ControlEvent
import app.aircontrol.gesture.SystemAction
import app.aircontrol.overlay.OverlayController
import app.aircontrol.settings.SettingsRepository
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
    private var debugJob: Job? = null
    @Volatile private var swipeLength = 0.4f

    override fun onServiceConnected() {
        super.onServiceConnected()
        val ov = OverlayController(this)
        val gd = GestureDispatcher(this)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        overlay = ov
        dispatcher = gd
        scope = sc
        sc.launch { ControlBus.events.collect { handle(it) } }
        sc.launch {
            SettingsRepository(this@AirControlAccessibilityService).flow.collect { s ->
                swipeLength = s.swipeLength
                ov.view.colors = s.glow.colors
                ov.view.showHud = s.showHud
                ov.view.showSkeleton = s.showSkeleton
                setDebugFeeds(s.showHud || s.showSkeleton)
            }
        }
        connectedFlow.value = true
    }

    private fun setDebugFeeds(enabled: Boolean) {
        val sc = scope ?: return
        val ov = overlay ?: return
        if (enabled == (debugJob != null)) return
        if (enabled) {
            debugJob = sc.launch {
                launch { ControlBus.snapshot.collect { ov.view.snapshot = it } }
                launch {
                    ControlBus.state.collect { s ->
                        ov.view.hudState = s
                        ov.keepAlive = s.running
                    }
                }
            }
        } else {
            debugJob?.cancel()
            debugJob = null
            ov.keepAlive = false
            ov.view.snapshot = null
        }
    }

    private fun handle(e: ControlEvent) {
        val ov = overlay ?: return
        val gd = dispatcher ?: return
        ov.onEvent(e)
        if (e !is ControlEvent.Pointer && e !is ControlEvent.TouchMove && e !is ControlEvent.ArmProgress &&
            e !is ControlEvent.SignProgress && !(e is ControlEvent.SwipeMode && e.axis == null)
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
            is ControlEvent.Global -> performGlobal(e.action)
            is ControlEvent.Disarmed -> gd.cancel()
            else -> {}
        }
    }

    private fun performGlobal(action: SystemAction) {
        val code = when (action) {
            SystemAction.BACK -> GLOBAL_ACTION_BACK
            SystemAction.HOME -> GLOBAL_ACTION_HOME
            SystemAction.RECENTS -> GLOBAL_ACTION_RECENTS
            SystemAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            SystemAction.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            SystemAction.NONE, SystemAction.RECENTER -> return
        }
        performGlobalAction(code)
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
        super.onDestroy()
    }
}
