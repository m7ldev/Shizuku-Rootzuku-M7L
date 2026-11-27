package moe.shizuku.manager.stealth

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings

/**
 * Stealth mode: some apps refuse to work while developer options or USB debugging are on.
 * Before such an app is opened, these settings are turned off; they are turned on again and
 * Shizuku is restarted after the user has left the app.
 *
 * Shizuku started through adb cannot survive this on most devices, which is why it has to
 * be started again afterwards. That needs Wi-Fi.
 */
object StealthController {

    private const val TAG = AppConstants.TAG
    private const val KEY_APPS = "stealth_apps"
    private const val KEY_ACTIVE = "stealth_active"
    private const val KEY_TARGET = "stealth_target"

    fun getApps(): Set<String> {
        return ShizukuSettings.getPreferences().getStringSet(KEY_APPS, null)?.toSet() ?: emptySet()
    }

    fun setApps(apps: Set<String>) {
        ShizukuSettings.getPreferences().edit().putStringSet(KEY_APPS, HashSet(apps)).apply()
    }

    @JvmStatic
    fun isActive(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_ACTIVE, false)
    }

    /** The app stealth mode was entered for, or null. */
    fun getTarget(): String? {
        return ShizukuSettings.getPreferences().getString(KEY_TARGET, null)
    }

    private fun setActive(active: Boolean, target: String?) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(KEY_ACTIVE, active)
            .putString(KEY_TARGET, target)
            .apply()
    }

    /** Forget stealth mode without touching any setting, used after a reboot. */
    @JvmStatic
    fun reset() {
        if (isActive()) setActive(false, null)
    }

    /**
     * Changing the developer settings needs a permission that the Shizuku service gives to
     * this app the first time it runs.
     */
    fun canChangeSettings(context: Context): Boolean {
        return context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    /** Whether this app may ask which app is in front, needed to notice that the user left. */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Throwable) {
            false
        }
    }

    private fun putGlobal(context: Context, name: String, value: Int) {
        try {
            Settings.Global.putInt(context.contentResolver, name, value)
        } catch (e: Throwable) {
            Log.w(TAG, "set $name=$value", e)
        }
    }

    /**
     * Turns developer options, USB debugging and wireless debugging off.
     *
     * @return false if this app is not allowed to change these settings
     */
    fun enter(context: Context, target: String): Boolean {
        if (!canChangeSettings(context)) return false

        // Marked first, so that nothing restarts Shizuku while the settings are going off
        setActive(true, target)
        putGlobal(context, "adb_wifi_enabled", 0)
        putGlobal(context, Settings.Global.ADB_ENABLED, 0)
        putGlobal(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)

        val intent = Intent(context, StealthService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "start stealth service", e)
        }
        return true
    }

    /**
     * Turns the developer settings on again. Starting Shizuku afterwards is left to the
     * caller, because that takes a while.
     */
    fun leave(context: Context) {
        setActive(false, null)
        ShizukuSettings.setUserStopped(false)
        if (!canChangeSettings(context)) return
        putGlobal(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        putGlobal(context, Settings.Global.ADB_ENABLED, 1)
    }
}
