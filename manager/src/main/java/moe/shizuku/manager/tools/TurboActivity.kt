package moe.shizuku.manager.tools

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.stealth.StealthController
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

/**
 * Performance mode: the home of per-game profiles. Here the user adds games, each with its own
 * strong settings, and turns on the watcher that applies them while a game is open. The two
 * shared tools (the floating meter and its one switch) sit at the top.
 */
class TurboActivity : ToolListActivity() {

    override val requiresService: Boolean = false

    private val pickGame = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val pkg = result.data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE)
            if (!pkg.isNullOrEmpty()) {
                if (GameProfiles.get(pkg) == null) {
                    GameProfiles.put(GameProfile(pkg))
                }
                edit(pkg)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        // When the watcher is off, no overlay should linger from an earlier run
        if (!watching()) {
            PerfOverlayService.stop(this)
        }
        show()
    }

    private fun watching(): Boolean = ShizukuSettings.getPreferences().getBoolean(GameProfiles.KEY_WATCH, false)

    private fun show() {
        val rows = ArrayList<Row>()
        rows.add(
            Row(
                title = getString(R.string.games_auto),
                summary = getString(R.string.games_auto_summary),
                checked = watching(),
                icon = R.drawable.ic_bolt_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { setWatching(!watching()) }
            )
        )
        rows.add(
            Row(
                title = getString(R.string.games_add),
                icon = R.drawable.ic_add_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { addGame() }
            )
        )
        val pm = packageManager
        for (profile in GameProfiles.all().sortedBy { it.pkg }) {
            val info = try {
                pm.getApplicationInfo(profile.pkg, 0)
            } catch (e: Throwable) {
                null
            }
            val label = info?.let {
                try {
                    it.loadLabel(pm).toString()
                } catch (e: Throwable) {
                    profile.pkg
                }
            } ?: profile.pkg
            rows.add(
                Row(
                    title = label,
                    summary = profile.summary().ifEmpty { profile.pkg },
                    appInfo = info,
                    icon = if (info == null) R.drawable.ic_android_24 else 0,
                    iconBackground = if (info == null) R.drawable.tool_icon_green else 0,
                    onClick = { edit(profile.pkg) },
                    onLongClick = { confirmRemove(profile.pkg, label) }
                )
            )
        }
        showRows(rows)
    }

    private fun edit(pkg: String) {
        startActivity(Intent(this, GameProfileEditActivity::class.java).putExtra(GameProfileEditActivity.EXTRA_PACKAGE, pkg))
    }

    private fun addGame() {
        pickGame.launch(
            Intent(this, AppPickerActivity::class.java)
                .putExtra(AppPickerActivity.EXTRA_TITLE, R.string.games_add)
        )
    }

    private fun confirmRemove(pkg: String, label: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setMessage(R.string.games_remove_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                GameProfiles.remove(pkg)
                show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Turns the watcher on or off. Seeing the foreground app needs usage access; while Shizuku
     * runs, this app can give it to itself.
     */
    private fun setWatching(on: Boolean) {
        val prefs = ShizukuSettings.getPreferences()
        if (!on) {
            prefs.edit().putBoolean(GameProfiles.KEY_WATCH, false).apply()
            GameWatchService.stop(this)
            // Stop everything the watcher may have started
            PerfOverlayService.stop(this)
            show()
            return
        }
        if (!StealthController.hasUsageAccess(this)) {
            if (Shizuku.pingBinder()) {
                toast(getString(R.string.tools_loading))
                runAsync({
                    ShizukuShell.exec(
                        "appops set --user ${UserHandleCompat.myUserId()} ${BuildConfig.APPLICATION_ID} GET_USAGE_STATS allow"
                    )
                }) {
                    if (StealthController.hasUsageAccess(this)) startWatch() else askUsage()
                }
            } else {
                askUsage()
            }
            return
        }
        startWatch()
    }

    private fun startWatch() {
        ShizukuSettings.getPreferences().edit().putBoolean(GameProfiles.KEY_WATCH, true).apply()
        GameWatchService.start(this)
        show()
    }

    private fun askUsage() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.stealth_usage_access)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (e: Throwable) {
                    toast(e.toString())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
