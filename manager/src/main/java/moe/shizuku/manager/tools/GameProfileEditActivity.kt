package moe.shizuku.manager.tools

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import rikka.shizuku.Shizuku

/**
 * The settings of one game's profile. Every change is saved at once. The values here are the
 * genuine levers that adb can pull; there is no magic switch, so each one says what it does.
 */
class GameProfileEditActivity : ToolListActivity() {

    companion object {
        const val EXTRA_PACKAGE = "package"
        private const val MENU_REMOVE = 1
    }

    override val requiresService: Boolean = false

    private lateinit var profile: GameProfile
    private var label: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (pkg == null) {
            finish()
            return
        }
        profile = GameProfiles.get(pkg) ?: GameProfile(pkg)
        label = try {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        } catch (e: Throwable) {
            pkg
        }
        supportActionBar?.title = label
        show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_REMOVE, 0, R.string.games_remove).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_REMOVE) {
            GameProfiles.remove(profile.pkg)
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun persist() {
        GameProfiles.put(profile)
        show()
    }

    private fun toggleRow(title: Int, summary: Int, value: Boolean, set: (Boolean) -> Unit): Row {
        return Row(
            title = getString(title),
            summary = getString(summary),
            checked = value,
            icon = R.drawable.ic_bolt_24, iconBackground = R.drawable.tool_icon_green,
            onClick = { set(!value); persist() }
        )
    }

    private fun show() {
        val rows = ArrayList<Row>()
        rows.add(
            Row(
                title = getString(R.string.turbo_refresh),
                summary = if (profile.refresh > 0) "${profile.refresh} Hz" else getString(R.string.games_leave),
                icon = R.drawable.ic_history_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { chooseRefresh() }
            )
        )
        rows.add(
            Row(
                title = getString(R.string.games_resolution),
                summary = resName(),
                icon = R.drawable.ic_phone_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { chooseRes() }
            )
        )
        rows.add(toggleRow(R.string.games_perf, R.string.games_perf_summary, profile.perf) { profile.perf = it })
        rows.add(toggleRow(R.string.games_clean, R.string.games_clean_summary, profile.kill) { profile.kill = it })
        rows.add(toggleRow(R.string.games_hw, R.string.games_hw_summary, profile.hw) { profile.hw = it })
        rows.add(toggleRow(R.string.games_vivid, R.string.games_vivid_summary, profile.vivid) { profile.vivid = it })
        rows.add(toggleRow(R.string.games_touch, R.string.games_touch_summary, profile.touch) { profile.touch = it })
        rows.add(toggleRow(R.string.games_dnd, R.string.games_dnd_summary, profile.dnd) { profile.dnd = it })
        rows.add(toggleRow(R.string.games_net, R.string.games_net_summary, profile.net) { profile.net = it })
        rows.add(toggleRow(R.string.games_meter, R.string.games_meter_summary, profile.meter) { profile.meter = it })
        if (profile.meter) {
            rows.add(
                Row(
                    title = getString(R.string.games_meter_look),
                    icon = R.drawable.ic_history_24, iconBackground = R.drawable.tool_icon_green,
                    onClick = { startActivity(Intent(this, PerfActivity::class.java)) }
                )
            )
        }
        rows.add(
            Row(
                title = getString(R.string.games_play_now),
                summary = getString(R.string.games_play_now_summary),
                icon = R.drawable.ic_outline_play_arrow_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { playNow() }
            )
        )
        showRows(rows)
    }

    private fun chooseRefresh() {
        toast(getString(R.string.tools_loading))
        runAsync({
            val dump = if (Shizuku.pingBinder()) ShizukuShell.exec("dumpsys display 2>/dev/null").out else ""
            Regex("fps=([0-9]+)").findAll(dump).mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in 24..240 }.toSortedSet().toList()
        }) { detected ->
            val rates = if (detected.isEmpty()) listOf(60, 90, 120, 144, 165) else detected
            val labels = ArrayList<CharSequence>()
            labels.add(getString(R.string.games_leave))
            rates.forEach { labels.add("$it Hz") }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.turbo_refresh)
                .setItems(labels.toTypedArray()) { _, which ->
                    profile.refresh = if (which == 0) 0 else rates[which - 1]
                    persist()
                }
                .show()
        }
    }

    private fun resName(): String {
        return getString(
            when (profile.res) {
                100 -> R.string.games_quality_ultra
                85 -> R.string.games_quality_high
                70 -> R.string.games_quality_medium
                else -> R.string.games_leave
            }
        )
    }

    private fun chooseRes() {
        // Named quality levels: higher quality keeps more pixels, lower gives a higher frame rate
        val values = intArrayOf(0, 100, 85, 70)
        val labels = arrayOf<CharSequence>(
            getString(R.string.games_leave),
            getString(R.string.games_quality_ultra),
            getString(R.string.games_quality_high),
            getString(R.string.games_quality_medium)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.games_resolution)
            .setMessage(R.string.games_resolution_hint)
            .setItems(labels) { _, which ->
                profile.res = values[which]
                persist()
            }
            .show()
    }

    private fun playNow() {
        val intent = packageManager.getLaunchIntentForPackage(profile.pkg)
        if (intent == null) {
            toast(getString(R.string.tool_apps_open_failed))
            return
        }
        // The watcher applies the profile when the game comes to the foreground
        if (!ShizukuSettings.getPreferences().getBoolean(GameProfiles.KEY_WATCH, false)) {
            toast(getString(R.string.games_turn_on_first))
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
