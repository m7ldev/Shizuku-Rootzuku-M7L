package moe.shizuku.manager.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.log.ActivityLogActivity
import moe.shizuku.manager.log.ActivityLogManager
import moe.shizuku.manager.starter.ServiceController
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that shows the state of the Shizuku service in a notification and,
 * when the watchdog is enabled, starts the service again after it stopped unexpectedly.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = AppConstants.TAG

        const val ACTION_START = "moe.shizuku.manager.monitor.action.START"
        const val ACTION_STOP = "moe.shizuku.manager.monitor.action.STOP"
        const val ACTION_BLOCK = "moe.shizuku.manager.monitor.action.BLOCK"
        private const val EXTRA_UID = "uid"
        private const val EXTRA_PACKAGE = "package"

        private const val ALERT_CHANNEL_ID = "alerts"
        private const val ALERT_NOTIFICATION_BASE = 1000
        private const val ALERT_POLL_INTERVAL = 20_000L

        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 10

        /**
         * Starts or stops this service so that it matches the settings.
         */
        @JvmStatic
        fun sync(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            try {
                if (ShizukuSettings.isStatusNotificationEnabled()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.stopService(intent)
                }
            } catch (e: Throwable) {
                // Starting a foreground service from the background is not always allowed
                Log.w(TAG, "sync monitor service", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var executor: ExecutorService? = null
    private val restarting = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Apps that were already reported since the Shizuku service started. */
    private val alertedUids = HashSet<Int>()
    private var alertsSince = System.currentTimeMillis()

    private val alertPoll = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if (!ShizukuSettings.isAlertOnUseEnabled()) return
            if (Shizuku.pingBinder()) {
                try {
                    executor?.execute { checkActivity() }
                } catch (e: Throwable) {
                }
            }
            handler.postDelayed(this, ALERT_POLL_INTERVAL)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateNotification()
        synchronized(alertedUids) { alertedUids.clear() }
        alertPoll.run()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        updateNotification()
        if (ShizukuSettings.isWatchdogEnabled() && !ShizukuSettings.isUserStopped()) {
            // Leave some time for a manual restart from the app to finish first
            scheduleRestart(5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        executor = Executors.newSingleThreadExecutor()
        createChannel()
        startForegroundCompat()

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService requires startForeground to be called every time
        startForegroundCompat()

        if (!ShizukuSettings.isStatusNotificationEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                executor?.execute {
                    ServiceController.stop()
                    handler.post { updateNotification() }
                }
            }
            ACTION_START -> {
                ShizukuSettings.setUserStopped(false)
                scheduleRestart(0)
            }
            ACTION_BLOCK -> {
                val uid = intent.getIntExtra(EXTRA_UID, -1)
                val packageName = intent.getStringExtra(EXTRA_PACKAGE)
                if (uid >= 0 && packageName != null) {
                    executor?.execute {
                        try {
                            AuthorizationManager.revoke(packageName, uid)
                        } catch (e: Throwable) {
                            Log.w(TAG, "block $packageName", e)
                        }
                    }
                    getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIFICATION_BASE + uid % 100000)
                }
            }
            else -> {
                // Started on boot or by the system: the service may have died while this
                // process was not alive
                if (!Shizuku.pingBinder()
                    && ShizukuSettings.isWatchdogEnabled()
                    && !ShizukuSettings.isUserStopped()
                    && ShizukuSettings.getLastLaunchMode() != LaunchMethod.UNKNOWN
                ) {
                    scheduleRestart(5000)
                }
            }
        }
        alertPoll.run()
        return START_STICKY
    }

    /**
     * Looks at the activity log of the service and tells the user about every app that used
     * Shizuku for the first time since the service started.
     */
    private fun checkActivity() {
        val entries = try {
            ActivityLogManager.get()
        } catch (e: Throwable) {
            null
        } ?: return

        // Newest first: walk backwards so that notifications come in the order of use
        for (entry in entries.asReversed()) {
            if (entry.time < alertsSince || entry.denied) continue
            val packageName = entry.packageName ?: continue
            val isNew = synchronized(alertedUids) { alertedUids.add(entry.uid) }
            if (!isNew) continue
            handler.post { showAlert(entry.uid, packageName, entry) }
        }
    }

    private fun showAlert(uid: Int, packageName: String, entry: ActivityLogManager.Entry) {
        val type = getString(
            when (entry.type) {
                ActivityLogManager.TYPE_SHELL -> R.string.activity_log_type_shell
                ActivityLogManager.TYPE_USER_SERVICE -> R.string.activity_log_type_user_service
                else -> R.string.activity_log_type_binder
            }
        )
        val text = if (entry.detail.isNullOrEmpty()) type else "$type: ${entry.detail}"

        val blockIntent = Intent(this, MonitorService::class.java)
            .setAction(ACTION_BLOCK)
            .putExtra(EXTRA_UID, uid)
            .putExtra(EXTRA_PACKAGE, packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val requestCode = 100 + uid % 100000
        val blockPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, blockIntent, flags)
        } else {
            PendingIntent.getService(this, requestCode, blockIntent, flags)
        }
        val openPending = PendingIntent.getActivity(
            this, requestCode,
            Intent(this, ActivityLogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.alert_on_use_title, packageName))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.alert_on_use_block), blockPending)
            .build()
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(ALERT_NOTIFICATION_BASE + uid % 100000, notification)
        } catch (e: Throwable) {
            Log.w(TAG, "alert", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        unregisterNetworkCallback()
        handler.removeCallbacks(alertPoll)
        executor?.shutdownNow()
        executor = null
    }

    private fun scheduleRestart(delay: Long) {
        val executor = executor ?: return
        if (!restarting.compareAndSet(false, true)) {
            return
        }
        updateNotification()
        try {
            executor.execute {
                try {
                    if (delay > 0) Thread.sleep(delay)
                    if (!Shizuku.pingBinder()
                        && !ShizukuSettings.isUserStopped()
                        && ServiceController.canAutoStart(this)
                    ) {
                        ServiceController.start(this)
                    }
                } catch (e: InterruptedException) {
                } catch (e: Throwable) {
                    Log.w(TAG, "restart", e)
                } finally {
                    restarting.set(false)
                    handler.post { updateNotification() }
                }
            }
        } catch (e: Throwable) {
            restarting.set(false)
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Wireless debugging only works on Wi-Fi, so this is the moment the
                    // service can be started again
                    if (!Shizuku.pingBinder()
                        && ShizukuSettings.isWatchdogEnabled()
                        && !ShizukuSettings.isUserStopped()
                        && ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB
                    ) {
                        scheduleRestart(5000)
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Throwable) {
            Log.w(TAG, "registerNetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: Throwable) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            manager?.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    getString(R.string.alert_on_use_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    private fun startForegroundCompat() {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground", e)
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Throwable) {
            Log.w(TAG, "updateNotification", e)
        }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MonitorService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun buildNotification(): Notification {
        val running = Shizuku.pingBinder()
        val appName = getString(R.string.app_name)

        val title = if (running) {
            getString(R.string.home_status_service_is_running, appName)
        } else {
            getString(R.string.home_status_service_not_running, appName)
        }

        val text = when {
            running -> {
                val uid = try {
                    Shizuku.getUid()
                } catch (e: Throwable) {
                    -1
                }
                getString(R.string.monitor_notification_mode, if (uid == 0) "root" else "adb")
            }
            restarting.get() -> getString(R.string.monitor_notification_restarting)
            else -> getString(R.string.monitor_notification_stopped)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setColor(getColor(R.color.notification))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (running) {
            builder.addAction(0, getString(R.string.monitor_action_stop), servicePendingIntent(ACTION_STOP, 1))
        } else if (!restarting.get() && ServiceController.canAutoStart(this)) {
            builder.addAction(0, getString(R.string.monitor_action_start), servicePendingIntent(ACTION_START, 2))
        }
        return builder.build()
    }
}
