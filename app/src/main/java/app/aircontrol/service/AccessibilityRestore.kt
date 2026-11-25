package app.aircontrol.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Keeps the accessibility service switched on across app updates.
 *
 * Some ways of installing an update (and some devices) leave the service switched off, so it had
 * to be re-enabled by hand after every update. With WRITE_SECURE_SETTINGS, granted once over adb
 * (`adb shell pm grant app.aircontrol android.permission.WRITE_SECURE_SETTINGS`, kept across
 * updates, lost on uninstall), the app adds its own service back to the enabled list: right after
 * an update ([UpdateReceiver]) and whenever the app or tracking starts.
 */
object AccessibilityRestore {
    private const val TAG = "AccessibilityRestore"

    /** The one-time command that lets AirControl switch its service back on by itself. */
    const val GRANT_COMMAND = "adb shell pm grant app.aircontrol android.permission.WRITE_SECURE_SETTINGS"

    fun component(context: Context) = ComponentName(context, AirControlAccessibilityService::class.java)

    fun canRestore(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** The service is in the enabled list (it may still be binding). */
    fun isEnabled(context: Context): Boolean {
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = component(context)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Switches the service on if it is off and the permission was granted. Returns whether it is (now) on. */
    fun ensureEnabled(context: Context): Boolean {
        if (isEnabled(context)) return true
        if (!canRestore(context)) return false
        return try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val mine = component(context).flattenToString()
            val updated = current.split(':').filter { it.isNotBlank() }.plus(mine).joinToString(":")
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "accessibility service switched back on")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "could not switch the accessibility service on", e)
            false
        }
    }

    /** Runs after every update of the app (MY_PACKAGE_REPLACED), before it is opened. */
    class UpdateReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) ensureEnabled(context)
        }
    }
}
