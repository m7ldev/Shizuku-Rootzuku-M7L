package moe.shizuku.manager.tools

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.stealth.StealthController
import java.util.Locale

/**
 * Chooses the apps that are protected by stealth mode.
 */
class StealthPickActivity : ToolListActivity() {

    private class App(val info: ApplicationInfo, val label: String)

    override val requiresService: Boolean = false
    override val searchable: Boolean = true

    private var apps: List<App> = emptyList()
    private var selected: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return

        selected = StealthController.getApps()
        showLoading()
        runAsync({
            val pm = packageManager
            // Every app with an icon in the launcher, the manifest asks for exactly these
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val seen = HashSet<String>()
            val result = ArrayList<App>()
            for (resolved in pm.queryIntentActivities(launcher, 0)) {
                val info = resolved.activityInfo?.applicationInfo ?: continue
                if (info.packageName == BuildConfig.APPLICATION_ID || !seen.add(info.packageName)) continue
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (e: Throwable) {
                    info.packageName
                }
                result.add(App(info, label))
            }
            result.sortBy { it.label.lowercase(Locale.getDefault()) }
            result
        }) {
            apps = it
            show()
        }
    }

    override fun onQueryChanged(query: String) {
        show()
    }

    private fun show() {
        val q = query.trim().lowercase(Locale.getDefault())
        val rows = apps.asSequence()
            .filter {
                q.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(q)
                        || it.info.packageName.lowercase(Locale.ROOT).contains(q)
            }
            // Chosen apps first, so that they are easy to find again
            .sortedByDescending { it.info.packageName in selected }
            .map { app ->
                val pkg = app.info.packageName
                Row(
                    title = app.label,
                    summary = pkg,
                    appInfo = app.info,
                    checked = pkg in selected,
                    onClick = {
                        selected = if (pkg in selected) selected - pkg else selected + pkg
                        StealthController.setApps(selected)
                        show()
                    }
                )
            }
            .toList()
        showRows(rows, getString(R.string.tools_empty))
    }
}
