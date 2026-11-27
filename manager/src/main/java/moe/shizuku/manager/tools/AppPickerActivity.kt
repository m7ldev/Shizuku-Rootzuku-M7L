package moe.shizuku.manager.tools

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import moe.shizuku.manager.R
import java.util.Locale

/**
 * Full-page app chooser, styled exactly like the App manager (neon rows, icons, search in the
 * toolbar). Returns the chosen package name as [EXTRA_PACKAGE] in the activity result.
 */
class AppPickerActivity : ToolListActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_PACKAGE = "package"
    }

    // Choosing an app does not need the Shizuku service, so the page opens on its own.
    override val requiresService: Boolean = false
    override val searchable: Boolean = true

    private class App(val info: ApplicationInfo, val label: String) {
        val user: Boolean get() = (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
    }

    private var apps: List<App> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        val titleRes = intent.getIntExtra(EXTRA_TITLE, 0)
        if (titleRes != 0) setTitle(titleRes)
        load()
    }

    private fun load() {
        showLoading()
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
                q.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(q) ||
                        it.info.packageName.lowercase(Locale.ROOT).contains(q)
            }
            .map { app ->
                Row(
                    title = app.label,
                    summary = app.info.packageName,
                    appInfo = app.info,
                    onClick = {
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PACKAGE, app.info.packageName))
                        finish()
                    }
                )
            }
            .toList()
        showRows(rows)
    }
}
