package moe.shizuku.manager.tools

import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.UserHandleCompat
import java.util.Locale

/**
 * Sets the language of a single app, apart from the system language (Android 13+). Shown like the
 * app manager: a searchable list of apps, then a short list of languages for the one you pick.
 */
class AppLocaleActivity : ToolListActivity() {

    private class App(val info: ApplicationInfo, val label: String) {
        val user: Boolean get() = (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
    }

    override val searchable: Boolean = true

    private val userId = UserHandleCompat.myUserId()
    private var apps: List<App> = emptyList()

    // name shown in the dialog -> BCP-47 tag ("" means follow the system)
    private val languages = linkedMapOf(
        "اردو" to "ur",
        "العربية" to "ar",
        "English" to "en",
        "Français" to "fr",
        "Español" to "es",
        "Deutsch" to "de",
        "Türkçe" to "tr",
        "Русский" to "ru",
        "हिन्दी" to "hi",
        "中文" to "zh",
        "日本語" to "ja"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        showLoading()
        load()
    }

    override fun onQueryChanged(query: String) = showApps()

    // package -> current BCP-47 tag ("" / absent means system default)
    private var current: Map<String, String> = emptyMap()

    private fun load() {
        runAsync({
            val pm = packageManager
            val list = pm.getInstalledApplications(0).map { info ->
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (e: Throwable) {
                    info.packageName
                }
                App(info, label)
            }.sortedWith(
                compareByDescending<App> { it.user }.thenBy { it.label.lowercase(Locale.getDefault()) }
            )
            list to readLocales(list.map { it.info.packageName })
        }) {
            apps = it.first
            current = it.second
            showApps()
        }
    }

    /** Reads the set language of every app in one shell pass. Output line: "pkg|[tag]". */
    private fun readLocales(packages: List<String>): Map<String, String> {
        if (packages.isEmpty()) return emptyMap()
        val script = packages.joinToString("\n") { p ->
            "echo \"$p|\$(cmd locale get-app-locales $p --user $userId 2>/dev/null)\""
        }
        val out = ShizukuShell.exec(script).out
        val map = HashMap<String, String>()
        for (line in out.lineSequence()) {
            val sep = line.indexOf('|')
            if (sep <= 0) continue
            val pkg = line.substring(0, sep)
            // "... are [ar]" -> ar ; empty brackets -> default
            val tag = line.substringAfterLast('[', "").substringBefore(']', "").trim()
            if (tag.isNotEmpty()) map[pkg] = tag.substringBefore(',')
        }
        return map
    }

    private fun display(tag: String): String =
        languages.entries.firstOrNull { it.value.equals(tag, true) || tag.startsWith(it.value, true) }?.key ?: tag

    private fun showApps() {
        val q = query.trim().lowercase(Locale.getDefault())
        val rows = apps
            .filter {
                q.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(q) ||
                        it.info.packageName.lowercase(Locale.ROOT).contains(q)
            }
            .map { app ->
                val tag = current[app.info.packageName]
                val label = if (tag != null) "🌐 ${display(tag)}" else getString(R.string.locale_system)
                Row(
                    title = app.label,
                    summary = "$label · ${app.info.packageName}",
                    appInfo = app.info,
                    onClick = { pickLanguage(app) }
                )
            }
        showRows(rows)
    }

    private fun pickLanguage(app: App) {
        val names = ArrayList<CharSequence>()
        names.add(getString(R.string.locale_system))
        names.addAll(languages.keys)
        val tags = ArrayList<String>()
        tags.add("")
        tags.addAll(languages.values)
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setItems(names.toTypedArray()) { _, which ->
                apply(app.info.packageName, tags[which])
            }
            .show()
    }

    private fun apply(pkg: String, tag: String) {
        val locales = if (tag.isEmpty()) "" else tag
        toast(getString(R.string.tools_loading))
        runAsync({
            val r = ShizukuShell.exec("cmd locale set-app-locales $pkg --user $userId --locales ${ShizukuShell.quote(locales)}")
            // update our cached state so the list reflects the change at once
            if (tag.isEmpty()) (current as? HashMap)?.remove(pkg) else (current as? HashMap)?.put(pkg, tag)
            r
        }) { result ->
            toast(if (result.isSuccess) getString(R.string.tools_done) else result.message)
            showApps()
        }
    }
}
