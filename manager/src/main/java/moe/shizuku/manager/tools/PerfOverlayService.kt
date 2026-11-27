package moe.shizuku.manager.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import kotlin.concurrent.thread

/**
 * A small floating overlay that shows the frame rate and the use of the processor, memory and
 * graphics chip over whatever is on the screen, such as a game.
 *
 * The frame rate is measured by counting the display's vsync beats, so it follows the screen's
 * refresh while something moves. The three loads are read from the kernel through the Shizuku
 * service once a second. The graphics load is not exposed by every phone; it then shows "--".
 */
class PerfOverlayService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_UPDATE = "update"

        const val KEY_ENABLED = "perf_overlay"
        const val KEY_FPS = "perf_fps"
        const val KEY_CPU = "perf_cpu"
        const val KEY_RAM = "perf_ram"
        const val KEY_GPU = "perf_gpu"
        const val KEY_PING = "perf_ping"
        const val KEY_POSITION = "perf_position"
        const val KEY_SIZE = "perf_size"

        const val POS_LEFT = 0
        const val POS_CENTER = 1
        const val POS_RIGHT = 2
        const val SIZE_SMALL = 0
        const val SIZE_MEDIUM = 1

        private const val CHANNEL = "perf_overlay"
        private const val NOTIFICATION_ID = 61

        fun start(context: Context) {
            val intent = Intent(context, PerfOverlayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun update(context: Context) {
            try {
                context.startService(Intent(context, PerfOverlayService::class.java).setAction(ACTION_UPDATE))
            } catch (e: Throwable) {
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, PerfOverlayService::class.java).setAction(ACTION_STOP))
            } catch (e: Throwable) {
            }
        }
    }

    private val prefs by lazy { ShizukuSettings.getPreferences() }
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    private var frames = 0
    private var fps = 0
    private var lastFpsTime = 0L

    // The last reading of the kernel counters, to measure the change over one second
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var cpu = -1
    private var ram = -1
    private var gpu = -1
    private var ping = -1

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frames++
            val now = frameTimeNanos / 1_000_000L
            if (lastFpsTime == 0L) lastFpsTime = now
            if (now - lastFpsTime >= 1000) {
                fps = (frames * 1000 / (now - lastFpsTime)).toInt()
                frames = 0
                lastFpsTime = now
            }
            if (view != null) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val sampler = object : Runnable {
        override fun run() {
            readLoads()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                applyLayout()
                render()
                return START_STICKY
            }
            else -> ensureRunning()
        }
        return START_STICKY
    }

    private fun notificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.perf_title), NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
    }

    private fun ensureRunning() {
        notificationChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt_24)
            .setContentTitle(getString(R.string.perf_title))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (view == null) {
            val text = TextView(this).apply {
                setTextColor(Color.WHITE)
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                typeface = Typeface.MONOSPACE
                setBackgroundColor(0x66000000)
                val pad = (4 * resources.displayMetrics.density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
            }
            view = text
            try {
                windowManager().addView(text, params())
            } catch (e: Throwable) {
                view = null
                stopSelf()
                return
            }
            Choreographer.getInstance().postFrameCallback(frameCallback)
            handler.post(sampler)
        }
        applyLayout()
        render()
    }

    private fun windowManager(): WindowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun params(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.y = (8 * resources.displayMetrics.density).toInt()
        return params
    }

    private fun applyLayout() {
        val text = view ?: return
        text.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (prefs.getInt(KEY_SIZE, SIZE_SMALL) == SIZE_MEDIUM) 13f else 10f
        )
        val params = text.layoutParams as? WindowManager.LayoutParams ?: params()
        params.gravity = Gravity.TOP or when (prefs.getInt(KEY_POSITION, POS_LEFT)) {
            POS_CENTER -> Gravity.CENTER_HORIZONTAL
            POS_RIGHT -> Gravity.END
            else -> Gravity.START
        }
        try {
            windowManager().updateViewLayout(text, params)
        } catch (e: Throwable) {
        }
    }

    private fun render() {
        val text = view ?: return
        val parts = ArrayList<String>()
        if (prefs.getBoolean(KEY_FPS, true)) parts.add("${fps} FPS")
        if (prefs.getBoolean(KEY_CPU, true)) parts.add("CPU ${if (cpu < 0) "--" else "$cpu%"}")
        if (prefs.getBoolean(KEY_RAM, true)) parts.add("RAM ${if (ram < 0) "--" else "$ram%"}")
        if (prefs.getBoolean(KEY_GPU, false)) parts.add("GPU ${if (gpu < 0) "--" else "$gpu%"}")
        if (prefs.getBoolean(KEY_PING, false)) parts.add("PING ${if (ping < 0) "--" else "${ping}ms"}")
        text.text = parts.joinToString("  ")
        handler.postDelayed({ render() }, 500)
    }

    /**
     * Reads the processor, memory and graphics load from the kernel through the service.
     */
    private fun readLoads() {
        thread(name = "perf-read") {
            val out = try {
                ShizukuShell.exec(
                    "head -n1 /proc/stat; echo ---; " +
                            "grep -E 'MemTotal|MemAvailable' /proc/meminfo; echo ---; " +
                            "for p in /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage " +
                            "/sys/devices/platform/*.mali/utilization " +
                            "/sys/class/misc/mali0/device/utilization " +
                            "/sys/kernel/gpu/gpu_busy /sys/class/devfreq/*.gpu/load; do " +
                            "[ -f \$p ] && { cat \"\$p\" 2>/dev/null; break; }; done; " +
                            "echo ---; ping -c 1 -W 1 1.1.1.1 2>/dev/null | grep -oE 'time=[0-9.]+'"
                ).out
            } catch (e: Throwable) {
                ""
            }
            parse(out)
        }
    }

    private fun parse(out: String) {
        try {
            val sections = out.split("---")
            // Processor: busy part of the change since the last reading
            val fields = sections.getOrNull(0)?.trim()?.split(Regex("\\s+")).orEmpty()
            if (fields.size >= 5 && fields[0] == "cpu") {
                val numbers = fields.drop(1).mapNotNull { it.toLongOrNull() }
                val total = numbers.sum()
                val idle = (numbers.getOrNull(3) ?: 0) + (numbers.getOrNull(4) ?: 0)
                if (lastCpuTotal != 0L && total > lastCpuTotal) {
                    val dTotal = total - lastCpuTotal
                    val dIdle = idle - lastCpuIdle
                    cpu = (((dTotal - dIdle) * 100) / dTotal).toInt().coerceIn(0, 100)
                }
                lastCpuTotal = total
                lastCpuIdle = idle
            }
            // Memory
            var memTotal = 0L
            var memAvailable = 0L
            sections.getOrNull(1)?.lines()?.forEach { line ->
                val value = Regex("(\\d+)").find(line)?.value?.toLongOrNull() ?: return@forEach
                if (line.contains("MemTotal")) memTotal = value
                if (line.contains("MemAvailable")) memAvailable = value
            }
            if (memTotal > 0) ram = (((memTotal - memAvailable) * 100) / memTotal).toInt().coerceIn(0, 100)
            // Graphics
            val gpuLine = sections.getOrNull(2)?.trim().orEmpty()
            val gpuValue = Regex("(\\d+)").find(gpuLine)?.value?.toIntOrNull()
            gpu = gpuValue?.coerceIn(0, 100) ?: -1
            // Network latency to a public server (not the game's own server)
            val pingLine = sections.getOrNull(3)?.trim().orEmpty()
            ping = Regex("time=([0-9.]+)").find(pingLine)?.groupValues?.get(1)?.toFloatOrNull()?.toInt() ?: -1
        } catch (e: Throwable) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        val text = view
        view = null
        if (text != null) {
            try {
                windowManager().removeView(text)
            } catch (e: Throwable) {
            }
        }
    }
}
