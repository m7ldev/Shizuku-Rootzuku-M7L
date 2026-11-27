package moe.shizuku.manager.tools

import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import rikka.shizuku.Shizuku
import java.util.Locale

/**
 * Picks an app to block (or allow) on the background network, shown like the app manager:
 * a searchable list of apps with their icons, instead of a popup.
 */
class NetworkAppsActivity : ToolListActivity() {

    private class App(val info: ApplicationInfo, val label: String) {
        val user: Boolean get() = (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
    }

    override val searchable: Boolean = true

    private var apps: List<App> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        showLoading()
        load()
    }

    override fun onQueryChanged(query: String) = showApps()

    private fun load() {
        runAsync({
            val pm = packageManager
            pm.getInstalledApplications(0).map { info ->
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (e: Throwable) {
                    info.packageName
                }
                App(info, label)
            }.sortedWith(
                compareByDescending<App> { it.user }.thenBy { it.label.lowercase(Locale.getDefault()) }
            )
        }) {
            apps = it
            showApps()
        }
    }

    private fun showApps() {
        val q = query.trim().lowercase(Locale.getDefault())
        val rows = apps
            .filter {
                q.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(q) ||
                        it.info.packageName.lowercase(Locale.ROOT).contains(q)
            }
            .map { app ->
                Row(
                    title = app.label,
                    summary = app.info.packageName,
                    appInfo = app.info,
                    onClick = { choose(app) }
                )
            }
        showRows(rows)
    }

    private fun choose(app: App) {
        val labels = arrayOf<CharSequence>(
            getString(R.string.network_block),
            getString(R.string.network_allow)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setItems(labels) { _, which ->
                val action = if (which == 0) "add" else "remove"
                service("cmd netpolicy $action restrict-background-blacklist ${app.info.uid}")
            }
            .show()
    }

    private fun service(command: String) {
        if (!Shizuku.pingBinder()) {
            toast(getString(R.string.home_status_service_not_running, getString(R.string.app_name)))
            return
        }
        toast(getString(R.string.tools_loading))
        runAsync({ ShizukuShell.exec(command) }) { result ->
            toast(if (result.isSuccess) getString(R.string.tools_done) else result.message)
        }
    }
}
