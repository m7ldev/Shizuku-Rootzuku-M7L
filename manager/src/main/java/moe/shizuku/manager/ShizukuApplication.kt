package moe.shizuku.manager

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.monitor.MonitorService
import moe.shizuku.manager.security.AppLock
import moe.shizuku.manager.tile.ShizukuTileService
import moe.shizuku.manager.widget.StatusWidgetProvider
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate

lateinit var application: ShizukuApplication

class ShizukuApplication : Application() {

    companion object {

        init {
            logd("ShizukuApplication", "init")

            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            if (atLeast30) {
                System.loadLibrary("adb")
            }
        }
    }

    private fun init(context: Context?) {
        ShizukuSettings.initialize(context)
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()
        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode())
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        init(this)

        // Keep the widget, the tile and the monitor in sync with the service
        Shizuku.addBinderReceivedListenerSticky {
            ShizukuSettings.setUserStopped(false)
            onServiceStateChanged()
            onServiceStarted()
        }
        Shizuku.addBinderDeadListener {
            onServiceStateChanged()
            // If the service died right after wireless debugging was turned off, this device
            // cannot keep it running that way: give the option up instead of looping
            if (wadbDisabledAt != 0L && SystemClock.elapsedRealtime() - wadbDisabledAt < 60_000) {
                ShizukuSettings.setDisableWadbAfterStart(false)
            }
            wadbDisabledAt = 0L
        }
        MonitorService.sync(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = AppLock.onActivityStarted()
            override fun onActivityStopped(activity: Activity) = AppLock.onActivityStopped()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private var wadbDisabledAt = 0L

    private fun onServiceStarted() {
        thread(name = "shizuku-on-start") {
            try {
                // Optional: close wireless debugging again once the service runs on its own
                if (ShizukuSettings.isDisableWadbAfterStart()
                    && Shizuku.getUid() != 0
                    && checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                    && Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
                ) {
                    Thread.sleep(3000)
                    Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 0)
                    wadbDisabledAt = SystemClock.elapsedRealtime()
                }
            } catch (e: Throwable) {
                Log.w(AppConstants.TAG, "disable wireless debugging", e)
            }
        }
    }

    private fun onServiceStateChanged() {
        StatusWidgetProvider.updateAll(this)
        try {
            TileService.requestListeningState(this, ComponentName(this, ShizukuTileService::class.java))
        } catch (e: Throwable) {
        }
    }

}
