package moe.shizuku.manager.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.ServiceController
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Quick settings tile that shows whether Shizuku is running and toggles it.
 */
class ShizukuTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())
    private var starting = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refresh() }

    override fun onStartListening() {
        super.onStartListening()
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        refresh()
    }

    override fun onStopListening() {
        super.onStopListening()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = Shizuku.pingBinder()
        tile.label = getString(R.string.app_name)
        tile.state = when {
            running -> Tile.STATE_ACTIVE
            starting -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when {
                    running -> R.string.tile_running
                    starting -> R.string.tile_starting
                    else -> R.string.tile_stopped
                }
            )
        }
        try {
            tile.updateTile()
        } catch (e: Throwable) {
        }
    }

    override fun onClick() {
        super.onClick()

        if (starting) return

        if (Shizuku.pingBinder()) {
            ServiceController.stop()
            refresh()
            return
        }

        if (!ServiceController.canAutoStart(this)) {
            openApp()
            return
        }

        starting = true
        refresh()
        val context = applicationContext
        thread(name = "shizuku-tile-start") {
            ShizukuSettings.setUserStopped(false)
            val ok = try {
                ServiceController.start(context, attempts = 2)
            } catch (e: Throwable) {
                false
            }
            handler.post {
                starting = false
                refresh()
                if (!ok) {
                    Toast.makeText(context, R.string.tile_start_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Throwable) {
        }
    }
}
