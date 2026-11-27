package moe.shizuku.manager.tools

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.UserHandleCompat
import java.util.Locale

/**
 * Opens any screen (activity) inside another app, even the hidden ones such as test or debug
 * menus. It opens like the app manager: a list of apps to choose from, then the chosen app's
 * screens, each launched with the service's permission so closed screens open too.
 */
class ActivitiesActivity : ToolListActivity() {

    private class App(val info: ApplicationInfo, val label: String) {
        val user: Boolean get() = (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
    }

    override val searchable: Boolean = true

    private val userId = UserHandleCompat.myUserId()
    private var apps: List<App> = emptyList()

    // Packages the user pinned, kept at the top of the list, stored between runs
    private val favorites: MutableSet<String> by lazy {
        ShizukuSettings.getPreferences().getString(FAV_KEY, "")
            .orEmpty().split('\n').filter { it.isNotEmpty() }.toMutableSet()
    }

    private fun saveFavorites() {
        ShizukuSettings.getPreferences().edit().putString(FAV_KEY, favorites.joinToString("\n")).apply()
    }

    private fun toggleFavorite(pkg: String) {
        if (!favorites.remove(pkg)) favorites.add(pkg)
        saveFavorites()
        showApps()
    }

    companion object {
        private const val FAV_KEY = "activity_favorites"
    }

    // When an app is chosen, its screens are shown; back returns to the app list
    private var current: App? = null
    private var activities: List<String> = emptyList()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            current = null
            isEnabled = false
            supportActionBar?.subtitle = null
            showApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        onBackPressedDispatcher.addCallback(this, backCallback)
        showLoading()
        loadApps()
    }

    override fun onQueryChanged(query: String) {
        if (current == null) showApps() else showActivities()
    }

    private fun loadApps() {
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
            // Pinned apps first, then user apps, then the rest, each group by name
            .sortedWith(
                compareByDescending<App> { it.info.packageName in favorites }
                    .thenByDescending { it.user }
                    .thenBy { it.label.lowercase(Locale.getDefault()) }
            )
            .map { app ->
                val pinned = app.info.packageName in favorites
                Row(
                    title = if (pinned) "★ ${app.label}" else app.label,
                    summary = app.info.packageName,
                    appInfo = app.info,
                    onClick = { open(app) },
                    onLongClick = { toggleFavorite(app.info.packageName) }
                )
            }
        showRows(rows)
    }

    private fun open(app: App) {
        current = app
        backCallback.isEnabled = true
        supportActionBar?.subtitle = app.label
        showLoading()
        runAsync({
            packageManager.getPackageInfo(app.info.packageName, PackageManager.GET_ACTIVITIES)
                .activities?.map { it.name }?.sorted().orEmpty()
        }) {
            activities = it
            showActivities()
        }
    }

    private fun showActivities() {
        val app = current ?: return
        val q = query.trim().lowercase(Locale.ROOT)
        val rows = activities
            .filter { q.isEmpty() || it.lowercase(Locale.ROOT).contains(q) }
            .map { name ->
                Row(
                    title = name.substringAfterLast('.'),
                    summary = name,
                    icon = R.drawable.ic_apps_24, iconBackground = R.drawable.tool_icon_green,
                    onClick = { launch(app.info.packageName, name) }
                )
            }
        showRows(rows, getString(R.string.activities_empty))
    }

    private fun launch(pkg: String, activity: String) {
        runAsync({
            ShizukuShell.exec("am start --user $userId -n ${ShizukuShell.quote("$pkg/$activity")} 2>&1")
        }) { result ->
            if (result.out.contains("Error", ignoreCase = true) || !result.isSuccess) {
                toast(result.message.ifBlank { getString(R.string.activities_failed) })
            }
        }
    }
}
