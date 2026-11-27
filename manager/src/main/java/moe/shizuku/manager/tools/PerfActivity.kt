package moe.shizuku.manager.tools

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings

/**
 * How the floating performance meter looks and what it shows. The meter itself is turned on
 * per game, so this screen has no on/off switch; it only sets the look and the numbers. A
 * change takes effect at once if a meter is on screen.
 */
class PerfActivity : ToolListActivity() {

    override val requiresService: Boolean = false

    private val prefs by lazy { ShizukuSettings.getPreferences() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        show()
    }

    private fun bool(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    private fun toggle(key: String, default: Boolean) {
        prefs.edit().putBoolean(key, !bool(key, default)).apply()
        show()
        PerfOverlayService.update(this)
    }

    private fun show() {
        val rows = ArrayList<Row>()
        rows.add(metric(PerfOverlayService.KEY_FPS, R.string.perf_fps, true))
        rows.add(metric(PerfOverlayService.KEY_CPU, R.string.perf_cpu, true))
        rows.add(metric(PerfOverlayService.KEY_RAM, R.string.perf_ram, true))
        rows.add(metric(PerfOverlayService.KEY_GPU, R.string.perf_gpu, false))
        rows.add(metric(PerfOverlayService.KEY_PING, R.string.perf_ping, false))
        rows.add(
            Row(
                title = getString(R.string.perf_position),
                summary = positionName(),
                icon = R.drawable.ic_apps_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { choosePosition() }
            )
        )
        rows.add(
            Row(
                title = getString(R.string.perf_size),
                summary = sizeName(),
                icon = R.drawable.ic_phone_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { chooseSize() }
            )
        )
        showRows(rows)
    }

    private fun metric(key: String, name: Int, default: Boolean): Row {
        return Row(
            title = getString(name),
            checked = bool(key, default),
            icon = R.drawable.ic_history_24, iconBackground = R.drawable.tool_icon_green,
            onClick = { toggle(key, default) }
        )
    }

    private fun positionName(): String {
        return getString(
            when (prefs.getInt(PerfOverlayService.KEY_POSITION, PerfOverlayService.POS_LEFT)) {
                PerfOverlayService.POS_CENTER -> R.string.perf_position_center
                PerfOverlayService.POS_RIGHT -> R.string.perf_position_right
                else -> R.string.perf_position_left
            }
        )
    }

    private fun sizeName(): String {
        return getString(
            if (prefs.getInt(PerfOverlayService.KEY_SIZE, PerfOverlayService.SIZE_SMALL) == PerfOverlayService.SIZE_MEDIUM)
                R.string.perf_size_medium else R.string.perf_size_small
        )
    }

    private fun choosePosition() {
        val labels = arrayOf<CharSequence>(
            getString(R.string.perf_position_left),
            getString(R.string.perf_position_center),
            getString(R.string.perf_position_right)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perf_position)
            .setItems(labels) { _, which ->
                prefs.edit().putInt(PerfOverlayService.KEY_POSITION, which).apply()
                show()
                PerfOverlayService.update(this)
            }
            .show()
    }

    private fun chooseSize() {
        val labels = arrayOf<CharSequence>(
            getString(R.string.perf_size_small),
            getString(R.string.perf_size_medium)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perf_size)
            .setItems(labels) { _, which ->
                prefs.edit().putInt(PerfOverlayService.KEY_SIZE, which).apply()
                show()
                PerfOverlayService.update(this)
            }
            .show()
    }
}
