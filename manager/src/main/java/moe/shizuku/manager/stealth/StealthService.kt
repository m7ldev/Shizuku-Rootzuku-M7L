package moe.shizuku.manager.stealth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.R
import moe.shizuku.manager.starter.ServiceController
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Runs while stealth mode is on. It shows a notification with a button to end stealth mode,
 * and ends it by itself once the protected app is no longer in front.
 */
class StealthService : Service() {

    companion object {
        private const val TAG = AppConstants.TAG

        const val ACTION_RESTORE = "moe.shizuku.manager.stealth.action.RESTORE"

        private const val CHANNEL_ID = "stealth"
        private const val NOTIFICATION_ID = 20
        private const val RESULT_NOTIFICATION_ID = 21

        private const val POLL_INTERVAL = 2000L

        /** Time for the protected app to come to the front before anything is concluded. */
        private const val GRACE_PERIOD = 10_000L

        /** The app has to be away for this long, a short look at another app does not count. */
        private const val AWAY_BEFORE_RESTORE = 12_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var lastForeground: String? = null
    private var lastQueryTime = 0L
    private var awaySince = 0L
    private var restoring = false

    private val poll = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            if (restoring || !StealthController.isActive()) return
            try {
                check()
            } catch (e: Throwable) {
                Log.w(TAG, "stealth poll", e)
            }
            handler.postDelayed(this, POLL_INTERVAL)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.stealth_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification())

        if (intent?.action == ACTION_RESTORE) {
            restore()
            return START_NOT_STICKY
        }
        if (!StealthController.isActive()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startedAt = SystemClock.elapsedRealtime()
        awaySince = 0L
        lastForeground = null
        lastQueryTime = System.currentTimeMillis() - 5000
        handler.postDelayed(poll, POLL_INTERVAL)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(poll)
    }

    /**
     * Finds out which app is in front and ends stealth mode when it is no longer the
     * protected one.
     */
    private fun check() {
        val target = StealthController.getTarget() ?: return
        if (!StealthController.hasUsageAccess(this)) return

        val usage = getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(lastQueryTime, now)
        lastQueryTime = now
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // 1 is ACTIVITY_RESUMED (MOVE_TO_FOREGROUND before Android 10)
            if (event.eventType == 1) {
                lastForeground = event.packageName
            }
        }

        val elapsed = SystemClock.elapsedRealtime()
        val foreground = lastForeground
        if (elapsed - startedAt < GRACE_PERIOD || foreground == null || foreground == target) {
            awaySince = 0L
            return
        }
        if (awaySince == 0L) {
            awaySince = elapsed
        } else if (elapsed - awaySince >= AWAY_BEFORE_RESTORE) {
            restore()
        }
    }

    private fun restore() {
        if (restoring) return
        restoring = true
        handler.removeCallbacks(poll)

        StealthController.leave(this)
        startForegroundCompat(buildNotification())

        thread(name = "shizuku-stealth-restore") {
            val ok = try {
                Shizuku.pingBinder() || ServiceController.start(applicationContext)
            } catch (e: Throwable) {
                Log.w(TAG, "restart after stealth", e)
                false
            }
            handler.post {
                if (!ok) {
                    try {
                        getSystemService(NotificationManager::class.java)?.notify(
                            RESULT_NOTIFICATION_ID,
                            NotificationCompat.Builder(this, CHANNEL_ID)
                                .setSmallIcon(R.drawable.ic_system_icon)
                                .setColor(getColor(R.color.notification))
                                .setContentTitle(getString(R.string.stealth_title))
                                .setContentText(getString(R.string.tile_start_failed))
                                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.tile_start_failed)))
                                .setAutoCancel(true)
                                .build()
                        )
                    } catch (e: Throwable) {
                    }
                }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground", e)
        }
    }

    private fun buildNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.stealth_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (restoring) {
            builder.setContentText(getString(R.string.stealth_notification_restoring))
        } else {
            val text = getString(
                if (StealthController.hasUsageAccess(this)) R.string.stealth_notification_auto
                else R.string.stealth_notification_manual
            )
            val restoreIntent = Intent(this, StealthService::class.java).setAction(ACTION_RESTORE)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, 0, restoreIntent, flags)
            } else {
                PendingIntent.getService(this, 0, restoreIntent, flags)
            }
            builder.setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .addAction(0, getString(R.string.stealth_restore_now), pending)
        }
        return builder.build()
    }
}
