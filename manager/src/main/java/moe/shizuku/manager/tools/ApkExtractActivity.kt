package moe.shizuku.manager.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat
import java.io.File
import java.util.Locale

/**
 * The installed apps with their version and size. Tapping one saves its apk into a folder.
 * An app that consists of several apk files is saved as one .apks bundle.
 */
class ApkExtractActivity : ToolListActivity() {

    companion object {
        private const val EXTRA_FOLDER = "folder"
        private const val TEMP = "/data/local/tmp/rootzuko_files/apk"
        private const val MENU_TAB = 1

        fun intent(context: Context, folder: String): Intent {
            return Intent(context, ApkExtractActivity::class.java).putExtra(EXTRA_FOLDER, folder)
        }
    }

    private class App(
        val info: ApplicationInfo,
        val label: String,
        val version: String,
        val size: Long,
        val splitSize: Long
    ) {
        val isSystem: Boolean get() = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    override val searchable: Boolean = true

    private var apps: List<App> = emptyList()
    private var showSystem = false
    private var folder = "/sdcard/Download"
    private val userId = UserHandleCompat.myUserId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        folder = intent.getStringExtra(EXTRA_FOLDER) ?: folder
        showLoading()
        load()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menu.add(0, MENU_TAB, 0, R.string.apk_system_apps)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // The button names the other list, the subtitle names the one that is shown
        menu.findItem(MENU_TAB)?.setTitle(if (showSystem) R.string.apk_user_apps else R.string.apk_system_apps)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_TAB) {
            showSystem = !showSystem
            invalidateOptionsMenu()
            show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onQueryChanged(query: String) {
        show()
    }

    private fun sizeOf(path: String?): Long {
        return try {
            if (path == null) 0 else File(path).length()
        } catch (e: Throwable) {
            0
        }
    }

    private fun load() {
        runAsync({
            val pm = packageManager
            val result = ArrayList<App>()
            for (pi: PackageInfo in ShizukuSystemApis.getInstalledPackages(0, userId)) {
                val ai = pi.applicationInfo ?: continue
                val label = try {
                    ai.loadLabel(pm).toString()
                } catch (e: Throwable) {
                    ai.packageName
                }
                result.add(
                    App(
                        ai, label, pi.versionName.orEmpty(),
                        sizeOf(ai.sourceDir), ai.splitSourceDirs?.sumOf { sizeOf(it) } ?: 0
                    )
                )
            }
            result.sortedBy { it.label.lowercase(Locale.getDefault()) }
        }) {
            apps = it
            show()
        }
    }

    private fun show() {
        if (!ready) return
        supportActionBar?.subtitle = getString(if (showSystem) R.string.apk_system_apps else R.string.apk_user_apps)
        val q = query.trim()
        val rows = apps.asSequence()
            .filter { it.isSystem == showSystem }
            .filter {
                q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.info.packageName.contains(q, ignoreCase = true)
            }
            .map { app ->
                val details = ArrayList<String>()
                if (app.version.isNotEmpty()) details.add(app.version)
                if (app.size > 0) details.add(Formatter.formatShortFileSize(this, app.size))
                if (app.splitSize > 0) details.add("SPLIT+" + Formatter.formatShortFileSize(this, app.splitSize))
                Row(
                    title = app.label,
                    summary = details.joinToString("   ") + "\n" + app.info.packageName,
                    appInfo = app.info,
                    onClick = { extract(app) }
                )
            }
            .toList()
        showRows(rows)
    }

    private fun extract(app: App) {
        val q = ShizukuShell::quote
        val pkg = app.info.packageName
        val base = (app.label + if (app.version.isEmpty()) "" else "_${app.version}")
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').ifEmpty { pkg }
        val target = (if (folder == "/") "" else folder) + "/" + base
        val tool = "CLASSPATH=${q(applicationInfo.sourceDir)} app_process /system/bin " +
                FileTool::class.java.name + " zip ${q("$target.apks")} $TEMP"
        val command = "P=\$(pm path --user $userId ${q(pkg)} | sed 's/^package://'); " +
                "[ -z \"\$P\" ] && { echo 'apk not found'; exit 1; }; " +
                "if [ \$(echo \"\$P\" | wc -l) -le 1 ]; then cp -- \"\$P\" ${q("$target.apk")} && echo ${q("$target.apk")}; exit \$?; fi; " +
                "rm -f -- ${q("$target.apks")}; rm -rf $TEMP; mkdir -p $TEMP || exit 1; " +
                "for f in \$P; do cp -- \"\$f\" $TEMP/ || { rm -rf $TEMP; exit 1; }; done; " +
                "$tool && echo ${q("$target.apks")}; R=\$?; rm -rf $TEMP; exit \$R"

        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.files_working)
            .setCancelable(false)
            .show()
        runAsync({ ShizukuShell.exec(command) }) { result ->
            dialog.dismiss()
            if (result.isSuccess) {
                toast(getString(R.string.apk_saved, result.out.trim().lines().lastOrNull().orEmpty()))
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.tool_apps_error_title)
                    .setMessage(result.message.lines().take(12).joinToString("\n"))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }
}
