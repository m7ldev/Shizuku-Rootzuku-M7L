package moe.shizuku.manager.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import moe.shizuku.manager.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * A small floating button that stays over other apps. Open the app you want, then tap it to take a
 * screenshot through the service, which captures even apps that block screenshots. The button
 * hides itself for the instant of the shot so it does not appear in the picture. Drag to move it.
 */
class CaptureButtonService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val KEY_ENABLED = "capture_button"

        private const val CHANNEL = "capture_button"
        private const val NOTIFICATION_ID = 62

        fun start(context: Context) {
            val intent = Intent(context, CaptureButtonService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, CaptureButtonService::class.java).setAction(ACTION_STOP))
            } catch (e: Throwable) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: ImageView? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.display_capture_button), NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_camera_24)
            .setContentTitle(getString(R.string.display_capture_button))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (view == null) addButton()
        return START_STICKY
    }

    private fun windowManager(): WindowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun addButton() {
        val density = resources.displayMetrics.density
        val size = (48 * density).toInt()
        val button = ImageView(this).apply {
            setImageResource(R.drawable.ic_camera_24)
            val pad = (11 * density).toInt()
            setPadding(pad, pad, pad, pad)
            imageTintList = ColorStateList.valueOf(Color.parseColor("#06160F"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22E6A0"))
            }
            alpha = 0.92f
        }
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (16 * density).toInt()
        params.y = (120 * density).toInt()

        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0; var moved = false
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; sx = params.x; sy = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = sx + dx; params.y = sy + dy
                    runCatching { windowManager().updateViewLayout(button, params) }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) capture(); true }
                else -> false
            }
        }

        view = button
        try {
            windowManager().addView(button, params)
        } catch (e: Throwable) {
            view = null
            stopSelf()
        }
    }

    private fun capture() {
        if (busy) return
        busy = true
        view?.visibility = View.INVISIBLE
        handler.postDelayed({
            thread {
                val ok = try {
                    val name = "Rootzuku_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
                    val path = "/sdcard/Pictures/$name"
                    val result = ShizukuShell.exec("screencap -p ${ShizukuShell.quote(path)}")
                    if (result.isSuccess) path else null
                } catch (e: Throwable) {
                    null
                }
                handler.post {
                    view?.visibility = View.VISIBLE
                    busy = false
                    if (ok != null) {
                        MediaScannerConnection.scanFile(this, arrayOf(ok), null, null)
                        Toast.makeText(this, getString(R.string.display_capture_done, ok), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.display_capture_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, 150)
    }

    override fun onDestroy() {
        super.onDestroy()
        view?.let { runCatching { windowManager().removeView(it) } }
        view = null
    }
}
