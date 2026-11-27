package moe.shizuku.manager.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Watches which app is in the foreground and applies the matching game profile while that game
 * is open, then puts everything back when it is left. It is the piece that makes the per-game
 * settings automatic, the way a phone's own game booster works.
 *
 * It needs usage access to see the foreground app, and the Shizuku service to change settings.
 */
class GameWatchService : Service() {

    companion object {
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "game_watch"
        private const val NOTIFICATION_ID = 62
        private const val POLL_MS = 1500L
        private val userId = UserHandleCompat.myUserId()

        fun start(context: Context) {
            val intent = Intent(context, GameWatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, GameWatchService::class.java).setAction(ACTION_STOP))
            } catch (e: Throwable) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var foreground: String? = null
    private var applied: String? = null

    // What to put back when the game is left
    private var savedRefresh: String? = null
    private var savedAnim: String? = null
    private var savedNet: String? = null
    private var savedTouch: String? = null
    private var savedVivid: String? = null
    private var startedMeter = false

    private val poll = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotice()
        handler.removeCallbacks(poll)
        handler.post(poll)
        return START_STICKY
    }

    private fun startForegroundNotice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.games_watch), NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt_24)
            .setContentTitle(getString(R.string.games_watch))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** The package that is in the foreground now, from the usage events of the last seconds. */
    private fun currentForeground(): String? {
        return try {
            val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usage.queryEvents(now - 10_000, now)
            val event = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    last = event.packageName
                }
            }
            last
        } catch (e: Throwable) {
            null
        }
    }

    private fun check() {
        val now = currentForeground() ?: return
        if (now == foreground) return
        foreground = now
        val target = GameProfiles.get(now)
        if (target?.pkg == applied) return
        thread(name = "game-watch") {
            if (applied != null) revert()
            if (target != null) apply(target)
        }
    }

    private fun shell(command: String): String {
        return try {
            if (Shizuku.pingBinder()) ShizukuShell.exec(command).out else ""
        } catch (e: Throwable) {
            ""
        }
    }

    private fun apply(profile: GameProfile) {
        applied = profile.pkg
        // Remember what to restore
        savedRefresh = shell("settings get system peak_refresh_rate").trim()
        savedAnim = shell("settings get global window_animation_scale").trim()
        savedNet = shell("cmd netpolicy get restrict-background").trim()
        savedTouch = shell("settings get system high_touch_sensitivity").trim()
        savedVivid = shell("settings get system screen_mode_setting").trim()

        val commands = ArrayList<String>()
        if (profile.refresh > 0) {
            commands.add("settings put system min_refresh_rate ${profile.refresh}")
            commands.add("settings put system peak_refresh_rate ${profile.refresh}")
        }
        when (profile.anim) {
            0 -> commands.add(scales("0"))
            1 -> commands.add(scales("0.5"))
            2 -> commands.add(scales("1"))
        }
        if (profile.perf) {
            commands.add("settings put global sem_enhanced_cpu_responsiveness 1")
            commands.add("cmd power set-fixed-performance-mode-enabled true >/dev/null 2>&1")
        }
        if (profile.kill) commands.add("am kill-all")
        if (profile.hw) commands.add("service call SurfaceFlinger 1008 i32 1 >/dev/null 2>&1")
        if (profile.dnd) commands.add("cmd notification set_dnd priority >/dev/null 2>&1 || settings put global zen_mode 1")
        if (profile.net) commands.add("cmd netpolicy set restrict-background true")
        if (profile.touch) commands.add("settings put system high_touch_sensitivity 1")
        if (profile.vivid) commands.add("settings put system screen_mode_setting 4")
        if (profile.res in 1..99) {
            // Scale the whole screen down, the game then renders fewer pixels
            commands.add(
                "SZ=\$(wm size | grep -oE '[0-9]+x[0-9]+' | head -n1); " +
                        "DN=\$(wm density | grep -oE '[0-9]+' | head -n1); " +
                        "W=\${SZ%x*}; H=\${SZ#*x}; " +
                        "[ -n \"\$W\" ] && wm size \$((W*${profile.res}/100))x\$((H*${profile.res}/100)); " +
                        "[ -n \"\$DN\" ] && wm density \$((DN*${profile.res}/100))"
            )
        }
        if (commands.isNotEmpty()) shell(commands.joinToString("; "))

        if (profile.meter) {
            if (Shizuku.pingBinder()) {
                shell("appops set --user $userId ${BuildConfig.APPLICATION_ID} SYSTEM_ALERT_WINDOW allow")
            }
            startedMeter = true
            handler.post { PerfOverlayService.start(this) }
        }
    }

    private fun revert() {
        applied = null
        val commands = ArrayList<String>()
        // Refresh rate back to what it was, or to the system default
        val refresh = savedRefresh
        if (refresh.isNullOrEmpty() || refresh == "null") {
            commands.add("settings delete system min_refresh_rate")
            commands.add("settings delete system peak_refresh_rate")
        } else {
            commands.add("settings put system peak_refresh_rate $refresh")
            commands.add("settings delete system min_refresh_rate")
        }
        val anim = savedAnim
        commands.add(scales(if (anim.isNullOrEmpty() || anim == "null") "1" else anim))
        commands.add("settings put global sem_enhanced_cpu_responsiveness 0")
        commands.add("cmd power set-fixed-performance-mode-enabled false >/dev/null 2>&1")
        commands.add("service call SurfaceFlinger 1008 i32 0 >/dev/null 2>&1")
        commands.add("wm size reset; wm density reset")
        commands.add("cmd notification set_dnd off >/dev/null 2>&1 || settings put global zen_mode 0")
        val net = savedNet
        commands.add("cmd netpolicy set restrict-background " + if (net != null && net.contains("enabled")) "true" else "false")
        val touch = savedTouch
        commands.add("settings put system high_touch_sensitivity " + if (touch == "1") "1" else "0")
        val vivid = savedVivid
        if (!vivid.isNullOrEmpty() && vivid != "null") commands.add("settings put system screen_mode_setting $vivid")
        shell(commands.joinToString("; "))
        if (startedMeter) {
            startedMeter = false
            handler.post { PerfOverlayService.stop(this) }
        }
    }

    private fun scales(value: String): String {
        return "for k in window_animation_scale transition_animation_scale animator_duration_scale; do " +
                "settings put global \$k $value; done"
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // Leave nothing changed behind
        if (applied != null) {
            val done = Object()
            thread(name = "game-watch-stop") {
                revert()
                synchronized(done) { (done as Object).notifyAll() }
            }
        }
    }
}
