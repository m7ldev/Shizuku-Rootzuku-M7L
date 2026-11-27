package moe.shizuku.manager.tools

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat
import java.util.Locale

/**
 * Lists the installed apps and offers the actions that normally need a computer:
 * freeze, force stop, clear data and uninstall, also for system apps.
 */
class AppManagerActivity : ToolListActivity() {

    companion object {
        private const val MENU_SELECT = 2
        private const val MENU_FREEZE_SELECTED = 3
        private const val MENU_UNFREEZE_SELECTED = 4
        private const val MENU_STOP_SELECTED = 5
        private const val MENU_KILL_ALL = 6
        private const val MENU_DEBLOAT = 20
        private const val MENU_RESTORE = 21
    }

    private class App(val info: ApplicationInfo, val label: String) {
        val isSystem: Boolean get() = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    override val searchable: Boolean = true

    private var apps: List<App> = emptyList()
    private var showSystem = false

    /** Multi-select: tapping an app marks it instead of opening its actions. */
    private var selecting = false
    private val selected = HashSet<String>()
    private val userId = UserHandleCompat.myUserId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        load()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menu.add(0, 1, 0, R.string.tool_apps_show_system).apply {
            isCheckable = true
            isChecked = showSystem
        }
        menu.add(0, MENU_SELECT, 0, R.string.tool_apps_select).isCheckable = true
        menu.add(0, MENU_FREEZE_SELECTED, 0, R.string.tool_apps_freeze_selected)
        menu.add(0, MENU_UNFREEZE_SELECTED, 0, R.string.tool_apps_unfreeze_selected)
        menu.add(0, MENU_STOP_SELECTED, 0, R.string.tool_apps_stop_selected)
        menu.add(0, MENU_KILL_ALL, 0, R.string.tool_apps_kill_all)
        menu.add(0, MENU_DEBLOAT, 0, R.string.debloat_title)
        menu.add(0, MENU_RESTORE, 0, R.string.debloat_restore)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_SELECT)?.isChecked = selecting
        for (id in intArrayOf(MENU_FREEZE_SELECTED, MENU_UNFREEZE_SELECTED, MENU_STOP_SELECTED)) {
            menu.findItem(id)?.isVisible = selecting
        }
        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * Runs one command for every selected app. [command] gets the quoted package name.
     */
    private fun runForSelected(command: (String) -> String) {
        if (selected.isEmpty()) {
            toast(getString(R.string.tool_apps_nothing_selected))
            return
        }
        val script = selected.joinToString("; ") { command(ShizukuShell.quote(it)) }
        selecting = false
        selected.clear()
        invalidateOptionsMenu()
        // Failures of single apps are expected here, the refreshed list shows the result
        runAsync({ ShizukuShell.exec(script) }) {
            toast(getString(R.string.tools_done))
            load()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                showSystem = !showSystem
                item.isChecked = showSystem
                show()
            }
            MENU_SELECT -> {
                selecting = !selecting
                selected.clear()
                invalidateOptionsMenu()
                show()
            }
            MENU_FREEZE_SELECTED -> runForSelected { "pm disable-user --user $userId $it" }
            MENU_UNFREEZE_SELECTED -> runForSelected { "pm enable --user $userId $it" }
            MENU_STOP_SELECTED -> runForSelected { "am force-stop --user $userId $it" }
            MENU_KILL_ALL -> run("am kill-all")
            MENU_DEBLOAT -> debloat()
            MENU_RESTORE -> restoreRemoved()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onQueryChanged(query: String) {
        show()
    }

    private fun load() {
        if (apps.isEmpty()) showLoading()
        runAsync({
            val pm = packageManager
            val result = ArrayList<App>()
            for (pi: PackageInfo in ShizukuSystemApis.getInstalledPackages(0, userId)) {
                val ai = pi.applicationInfo ?: continue
                if (ai.packageName == BuildConfig.APPLICATION_ID) continue
                val label = try {
                    ai.loadLabel(pm).toString()
                } catch (e: Throwable) {
                    ai.packageName
                }
                result.add(App(ai, label))
            }
            result.sortBy { it.label.lowercase(Locale.getDefault()) }
            result
        }) {
            apps = it
            show()
        }
    }

    private fun show() {
        val q = query.trim().lowercase(Locale.getDefault())
        val rows = apps.asSequence()
            .filter { showSystem || !it.isSystem }
            .filter {
                q.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(q)
                        || it.info.packageName.lowercase(Locale.ROOT).contains(q)
            }
            .map { app ->
                val frozen = !app.info.enabled
                Row(
                    title = app.label,
                    summary = if (frozen) {
                        app.info.packageName + "\n" + getString(R.string.tool_apps_frozen)
                    } else {
                        app.info.packageName
                    },
                    appInfo = app.info,
                    dimmed = frozen,
                    checked = if (selecting) app.info.packageName in selected else null,
                    onClick = {
                        if (selecting) {
                            val pkg = app.info.packageName
                            if (!selected.remove(pkg)) selected.add(pkg)
                            show()
                        } else {
                            showActions(app)
                        }
                    }
                )
            }
            .toList()
        showRows(rows)
    }

    private fun showActions(app: App) {
        val pkg = app.info.packageName
        val frozen = !app.info.enabled
        val quoted = ShizukuShell.quote(pkg)

        val labels = arrayOf<CharSequence>(
            getString(R.string.tool_apps_open),
            getString(if (frozen) R.string.tool_apps_unfreeze else R.string.tool_apps_freeze),
            getString(R.string.tool_apps_force_stop),
            getString(R.string.tool_apps_kill_background),
            getString(R.string.tool_apps_background),
            getString(R.string.tool_apps_bucket),
            getString(R.string.tool_apps_permissions),
            getString(R.string.tool_apps_clear_data),
            getString(R.string.tool_apps_uninstall),
            getString(R.string.tool_apps_info),
            getString(R.string.tool_apps_battery)
        )
        val icons = intArrayOf(
            R.drawable.ic_outline_open_in_new_24,
            if (frozen) R.drawable.ic_play_24 else R.drawable.ic_stop_24,
            R.drawable.ic_close_24,
            R.drawable.ic_power_24,
            R.drawable.ic_history_24,
            R.drawable.ic_bolt_24,
            R.drawable.ic_key_24,
            R.drawable.ic_file_24,
            R.drawable.ic_delete_24,
            R.drawable.ic_outline_info_24,
            R.drawable.ic_shield_24
        )
        ActionGrid.show(this, app.label, labels.indices.map { which ->
            ActionGrid.Item(labels[which], icons[which]) {
                when (which) {
                    0 -> try {
                        val intent = packageManager.getLaunchIntentForPackage(pkg)
                        if (intent == null) {
                            toast(getString(R.string.tool_apps_open_failed))
                        } else {
                            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    } catch (e: Throwable) {
                        toast(e.toString())
                    }
                    1 -> if (frozen) unfreeze(quoted) else run("pm disable-user --user $userId $quoted")
                    2 -> run("am force-stop --user $userId $quoted")
                    3 -> run("am kill --user $userId $quoted")
                    4 -> chooseBackground(quoted)
                    5 -> chooseBucket(quoted)
                    6 -> startActivity(
                        Intent(this, PermissionsActivity::class.java)
                            .putExtra(PermissionsActivity.EXTRA_PACKAGE, pkg)
                            .putExtra(PermissionsActivity.EXTRA_LABEL, app.label)
                    )
                    7 -> confirm(getString(R.string.tool_apps_clear_data_confirm, app.label)) {
                        run("pm clear --user $userId $quoted")
                    }
                    8 -> confirm(
                        getString(
                            if (app.isSystem) R.string.tool_apps_uninstall_system_confirm
                            else R.string.tool_apps_uninstall_confirm,
                            app.label
                        )
                    ) {
                        // A system app can only be removed for the current user, its files stay
                        run(
                            if (app.isSystem) "pm uninstall --user $userId $quoted"
                            else "pm uninstall $quoted"
                        )
                    }
                    9 -> try {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                        )
                    } catch (e: Throwable) {
                        toast(e.toString())
                    }
                    10 -> chooseBattery(quoted)
                }
            }
        })
    }

    /**
     * Lets or forbids the app to run in the background at all.
     */
    private fun chooseBackground(quoted: String) {
        val labels = arrayOf<CharSequence>(
            getString(R.string.tool_apps_background_allow),
            getString(R.string.tool_apps_background_deny)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tool_apps_background)
            .setItems(labels) { _, which ->
                val mode = if (which == 0) "allow" else "ignore"
                run("appops set --user $userId $quoted RUN_ANY_IN_BACKGROUND $mode")
            }
            .show()
    }

    /**
     * Battery optimisation. Allowing an app onto the idle whitelist lets it keep working with the
     * screen off (handy for messengers); taking it off restores normal Doze limits.
     */
    private fun chooseBattery(quoted: String) {
        val labels = arrayOf<CharSequence>(
            getString(R.string.tool_apps_battery_unrestricted),
            getString(R.string.tool_apps_battery_optimized)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tool_apps_battery)
            .setItems(labels) { _, which ->
                val sign = if (which == 0) "+" else "-"
                run("dumpsys deviceidle whitelist $sign$quoted")
            }
            .show()
    }

    /**
     * The standby bucket decides how often the system lets the app run jobs and alarms.
     */
    private fun chooseBucket(quoted: String) {
        val buckets = arrayOf("active", "working_set", "frequent", "rare", "restricted")
        val labels = arrayOf<CharSequence>(
            getString(R.string.tool_apps_bucket_active),
            getString(R.string.tool_apps_bucket_working_set),
            getString(R.string.tool_apps_bucket_frequent),
            getString(R.string.tool_apps_bucket_rare),
            getString(R.string.tool_apps_bucket_restricted)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tool_apps_bucket)
            .setItems(labels) { _, which ->
                run("am set-standby-bucket --user $userId $quoted ${buckets[which]}")
            }
            .show()
    }

    /**
     * Shows what the system answered, without the Java stack trace that follows it.
     */
    private fun showError(output: String) {
        val text = output.lines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("at ") }
            .take(8)
            .joinToString("\n")
        val message = if (output.contains("Shell cannot change component state")) {
            getString(R.string.tool_apps_error_no_permission) + "\n\n" + text
        } else {
            text
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tool_apps_error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirm(message: CharSequence, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Turns a frozen app on again. An app can be off in several ways, each way of turning it
     * on is tried. If the system refuses all of them, a repair by reinstalling is offered.
     */
    private fun unfreeze(quoted: String) {
        val stillOff = "pm list packages -d --user $userId | grep -qx package:$quoted && exit 1; exit 0"
        val command = "pm enable --user $userId $quoted 2>&1 || " +
                "pm default-state --user $userId $quoted 2>&1; " +
                "pm unsuspend --user $userId $quoted >/dev/null 2>&1; " + stillOff
        runAsync({ ShizukuShell.exec(command) }) { result ->
            if (result.isSuccess) {
                toast(getString(R.string.tools_done))
                load()
                return@runAsync
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tool_apps_repair_title)
                .setMessage(getString(R.string.tool_apps_repair_message) + "\n\n" + result.message)
                .setPositiveButton(R.string.tool_apps_repair) { _, _ -> run(repairCommand(quoted, stillOff)) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * Installing an app over itself resets its "disabled" state, whoever disabled it. The
     * files of the installed app are streamed into a new install session.
     */
    private fun repairCommand(quoted: String, stillOff: String): String {
        return "P=$(pm path --user $userId $quoted | sed 's/^package://'); " +
                "[ -z \"\$P\" ] && { echo 'apk not found'; exit 1; }; " +
                "S=$(pm install-create -r --user $userId | grep -o '[0-9][0-9]*' | head -n 1); " +
                "[ -z \"\$S\" ] && { echo 'cannot create install session'; exit 1; }; " +
                "i=0; for f in \$P; do " +
                "n=$(stat -c %s \"\$f\") && cat \"\$f\" | pm install-write -S \$n \$S \$i.apk - >/dev/null 2>&1 " +
                "|| { pm install-abandon \$S >/dev/null 2>&1; echo \"cannot read \$f\"; exit 1; }; " +
                "i=$((i+1)); done; " +
                "pm install-commit \$S 2>&1 || exit 1; " + stillOff
    }

    /**
     * Lets the user remove preinstalled apps that are known to be optional. They are removed
     * for the current user only: the system keeps their files, so they can be restored.
     */
    private fun debloat() {
        val found = apps.filter { it.info.packageName in Bloat.PACKAGES }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
        if (found.isEmpty()) {
            toast(getString(R.string.debloat_none))
            return
        }
        chooseMany(R.string.debloat_title, found.map { it.label + "\n" + it.info.packageName }) { chosen ->
            val packages = chosen.map { found[it].info.packageName }
            confirm(getString(R.string.debloat_confirm, packages.size)) {
                runScript(packages.joinToString("; ") { "pm uninstall --user $userId ${ShizukuShell.quote(it)}" })
            }
        }
    }

    /**
     * Brings back apps that were removed for this user while their files are still there.
     */
    private fun restoreRemoved() {
        runAsync({
            val all = ShizukuShell.exec("pm list packages -u --user $userId").out.lines()
            val present = ShizukuShell.exec("pm list packages --user $userId").out.lines().toHashSet()
            all.filter { it.startsWith("package:") && it !in present }.map { it.removePrefix("package:").trim() }.sorted()
        }) { removed ->
            if (removed.isEmpty()) {
                toast(getString(R.string.debloat_restore_none))
                return@runAsync
            }
            chooseMany(R.string.debloat_restore, removed) { chosen ->
                runScript(chosen.joinToString("; ") {
                    "pm install-existing --user $userId ${ShizukuShell.quote(removed[it])}"
                })
            }
        }
    }

    private fun chooseMany(title: Int, labels: List<String>, action: (List<Int>) -> Unit) {
        val checked = BooleanArray(labels.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray<CharSequence>(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = checked.indices.filter { checked[it] }
                if (chosen.isEmpty()) toast(getString(R.string.tool_apps_nothing_selected)) else action(chosen)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Runs commands for several apps. Single failures are expected, the new list shows the result. */
    private fun runScript(script: String) {
        runAsync({ ShizukuShell.exec(script) }) {
            toast(getString(R.string.tools_done))
            load()
        }
    }

    private fun run(command: String) {
        runAsync({ ShizukuShell.exec(command) }) { result ->
            if (result.isSuccess) {
                toast(getString(R.string.tools_done))
            } else {
                showError(result.message)
            }
            load()
        }
    }
}
