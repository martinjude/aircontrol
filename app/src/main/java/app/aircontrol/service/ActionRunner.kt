package app.aircontrol.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import app.aircontrol.gesture.SystemAction

/** Performs a bind's [SystemAction] through the accessibility service and system services. Main thread only. */
class ActionRunner(private val service: AccessibilityService) {

    private val audio = service.getSystemService(AudioManager::class.java)

    /** Returns false when the action could not be performed. */
    fun run(action: SystemAction, appPackage: String?): Boolean = try {
        when (action) {
            SystemAction.NONE -> true
            SystemAction.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            SystemAction.HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            SystemAction.RECENTS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            SystemAction.NOTIFICATIONS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            SystemAction.QUICK_SETTINGS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            SystemAction.LOCK_SCREEN -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            SystemAction.SCREENSHOT -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            SystemAction.POWER_MENU -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            SystemAction.MEDIA_PLAY_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            SystemAction.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            SystemAction.MEDIA_PREVIOUS -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            SystemAction.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
            SystemAction.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
            SystemAction.MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            SystemAction.LAUNCH_APP -> launch(appPackage)
        }
    } catch (e: Exception) {
        Log.w(TAG, "action $action failed", e)
        false
    }

    private fun mediaKey(code: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        return true
    }

    private fun volume(direction: Int): Boolean {
        audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
        return true
    }

    private fun launch(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val intent = service.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w(TAG, "no launch intent for $pkg")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        service.startActivity(intent)
        return true
    }

    private companion object {
        const val TAG = "ActionRunner"
    }
}
