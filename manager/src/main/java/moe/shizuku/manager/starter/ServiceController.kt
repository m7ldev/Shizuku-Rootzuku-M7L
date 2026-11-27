package moe.shizuku.manager.starter

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.stealth.StealthController
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Starts and stops the Shizuku service without any user interface. Used by the boot job, the
 * quick settings tile, the widget and the watchdog.
 */
object ServiceController {

    private const val TAG = AppConstants.TAG

    private val lock = Any()

    private fun canEnableWirelessAdb(context: Context): Boolean {
        // https://r.android.com/2128832
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    private fun isWirelessAdbEnabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    }

    /**
     * Whether [start] has a chance to work without the user doing anything.
     */
    fun canAutoStart(context: Context): Boolean {
        // In stealth mode nothing may turn the developer settings back on
        if (StealthController.isActive()) return false
        return when (ShizukuSettings.getLastLaunchMode()) {
            LaunchMethod.ROOT -> true
            LaunchMethod.ADB -> EnvironmentUtils.getAdbTcpPort() > 0
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && (canEnableWirelessAdb(context) || isWirelessAdbEnabled(context)))
            else -> EnvironmentUtils.isRooted()
        }
    }

    /**
     * Tries to start the service with the method that was used the last time. Blocks until the
     * service is running or all attempts failed.
     *
     * @return true if the service is running
     */
    @WorkerThread
    fun start(
        context: Context,
        attempts: Int = 3,
        discoverTimeout: Long = 15_000,
        binderTimeout: Long = 10_000
    ): Boolean {
        // Starting would turn the developer settings back on behind the protected app
        if (StealthController.isActive()) return Shizuku.pingBinder()

        synchronized(lock) {
            val app = context.applicationContext
            for (i in 0 until attempts) {
                if (Shizuku.pingBinder()) return true

                val launched = try {
                    when (ShizukuSettings.getLastLaunchMode()) {
                        LaunchMethod.ROOT -> startRoot()
                        LaunchMethod.ADB -> startAdb(app, discoverTimeout)
                        else -> EnvironmentUtils.isRooted() && startRoot()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "start attempt ${i + 1} failed", e)
                    false
                }

                if (launched && waitForBinder(binderTimeout)) return true

                if (i < attempts - 1) {
                    try {
                        Thread.sleep(3000L * (i + 1))
                    } catch (e: InterruptedException) {
                        return Shizuku.pingBinder()
                    }
                }
            }
            return Shizuku.pingBinder()
        }
    }

    /**
     * Stops the service and remembers that this was requested by the user.
     */
    fun stop() {
        ShizukuSettings.setUserStopped(true)
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.exit()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "stop", e)
        }
    }

    private fun waitForBinder(timeout: Long): Boolean {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            if (Shizuku.pingBinder()) return true
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                break
            }
        }
        return Shizuku.pingBinder()
    }

    private fun startRoot(): Boolean {
        if (!Shell.getShell().isRoot) {
            Shell.getCachedShell()?.close()
            return false
        }
        Shell.cmd(Starter.internalCommand).exec()
        return true
    }

    private fun startAdb(context: Context, discoverTimeout: Long): Boolean {
        // adb listening on a fixed TCP port ("adb tcpip 5555")
        val tcpPort = EnvironmentUtils.getAdbTcpPort()
        if (tcpPort > 0) {
            try {
                runStarter(tcpPort)
                return true
            } catch (e: Throwable) {
                Log.w(TAG, "adb on tcp port $tcpPort", e)
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        return startWirelessAdb(context, discoverTimeout)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startWirelessAdb(context: Context, discoverTimeout: Long): Boolean {
        val cr = context.contentResolver
        if (canEnableWirelessAdb(context)) {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        }
        if (!isWirelessAdbEnabled(context)) {
            Log.w(TAG, "wireless debugging is not enabled")
            return false
        }

        val port = discoverPort(context, discoverTimeout)
        if (port <= 0) {
            Log.w(TAG, "wireless debugging port not found")
            return false
        }
        runStarter(port)
        return true
    }

    private fun runStarter(port: Int) {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        val client = AdbClient("127.0.0.1", port, key)
        try {
            client.connect()
            client.shellCommand(Starter.internalCommand, null)
        } finally {
            try {
                client.close()
            } catch (e: Throwable) {
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun discoverPort(context: Context, timeout: Long): Int {
        val latch = CountDownLatch(1)
        val result = AtomicInteger(-1)
        val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
            if (port > 0) {
                result.set(port)
                latch.countDown()
            }
        }
        adbMdns.start()
        try {
            latch.await(timeout, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
        } finally {
            try {
                adbMdns.stop()
            } catch (e: Throwable) {
            }
        }
        return result.get()
    }
}
