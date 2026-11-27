package moe.shizuku.manager.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.ServiceController
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Home screen widget showing whether Shizuku is running. Tapping it starts the service when
 * that is possible without the app, otherwise it opens the app.
 */
class StatusWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_START = "moe.shizuku.manager.widget.action.START"

        @JvmStatic
        fun updateAll(context: Context, starting: Boolean = false) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, StatusWidgetProvider::class.java))
                if (ids == null || ids.isEmpty()) return
                manager.updateAppWidget(ids, buildViews(context, starting))
            } catch (e: Throwable) {
                Log.w(AppConstants.TAG, "update widget", e)
            }
        }

        private fun buildViews(context: Context, starting: Boolean): RemoteViews {
            val running = Shizuku.pingBinder()
            val views = RemoteViews(context.packageName, R.layout.widget_status)
            views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
            views.setTextViewText(
                R.id.widget_text, context.getString(
                    when {
                        running -> R.string.tile_running
                        starting -> R.string.tile_starting
                        else -> R.string.tile_stopped
                    }
                )
            )
            views.setImageViewResource(
                R.id.widget_icon,
                if (running) R.drawable.ic_widget_ok_24 else R.drawable.ic_widget_error_24
            )

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = if (!running && !starting && ServiceController.canAutoStart(context)) {
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, StatusWidgetProvider::class.java).setAction(ACTION_START),
                    flags
                )
            } else {
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    flags
                )
            }
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            return views
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try {
            appWidgetManager.updateAppWidget(appWidgetIds, buildViews(context, false))
        } catch (e: Throwable) {
            Log.w(AppConstants.TAG, "update widget", e)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (ACTION_START != intent.action || Shizuku.pingBinder()) {
            return
        }

        val app = context.applicationContext
        val pending = goAsync()
        updateAll(app, starting = true)
        thread(name = "shizuku-widget-start") {
            try {
                ShizukuSettings.setUserStopped(false)
                // A broadcast receiver must finish within about ten seconds
                ServiceController.start(app, attempts = 1, discoverTimeout = 4000, binderTimeout = 4000)
            } catch (e: Throwable) {
                Log.w(AppConstants.TAG, "start from widget", e)
            } finally {
                updateAll(app)
                pending.finish()
            }
        }
    }
}
