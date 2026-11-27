package moe.shizuku.manager.tools

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.monitor.MonitorService
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Saves the list of authorized apps with their limits and the settings to a file, and
 * restores them from such a file.
 */
class BackupActivity : ToolListActivity() {

    companion object {
        private const val REQUEST_CREATE = 1
        private const val REQUEST_OPEN = 2
        private const val FORMAT = "shizuku-m7l-backup"

        /** Settings that are safe to copy to another installation. */
        private val SETTINGS = arrayOf(
            ShizukuSettings.STATUS_NOTIFICATION,
            ShizukuSettings.WATCHDOG,
            ShizukuSettings.ALERT_ON_USE,
            ShizukuSettings.DISABLE_WADB_AFTER_START,
            ShizukuSettings.NIGHT_MODE,
            "black_night_theme",
            "use_system_color",
            "apps_sort_mode"
        )

        /** FLAG_ALLOWED, FLAG_DENIED and the three FLAG_DENY_* of the service. */
        private const val ALL_FLAGS = 0x3E
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return

        showRows(
            listOf(
                Row(
                    title = getString(R.string.tool_backup_create),
                    summary = getString(R.string.tool_backup_create_summary),
                    icon = R.drawable.ic_cloud_backup_24,
                    iconBackground = R.drawable.tool_icon_blue,
                    onClick = { create() }
                ),
                Row(
                    title = getString(R.string.tool_backup_restore),
                    summary = getString(R.string.tool_backup_restore_summary),
                    icon = R.drawable.ic_history_24,
                    iconBackground = R.drawable.tool_icon_violet,
                    onClick = { restore() }
                )
            )
        )
    }

    private fun create() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "shizuku-m7l-backup.json")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CREATE)
        } catch (e: Throwable) {
            toast(e.toString())
        }
    }

    private fun restore() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_OPEN)
        } catch (e: Throwable) {
            toast(e.toString())
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_CREATE -> write(uri)
            REQUEST_OPEN -> read(uri)
        }
    }

    private fun write(uri: Uri) {
        runAsync({
            val apps = JSONArray()
            for (pi in AuthorizationManager.getPackages()) {
                val ai = pi.applicationInfo ?: continue
                val flags = Shizuku.getFlagsForUid(ai.uid, ALL_FLAGS) and ALL_FLAGS
                if (flags == 0) continue
                apps.put(
                    JSONObject()
                        .put("package", pi.packageName)
                        .put("user", ai.uid / 100000)
                        .put("flags", flags)
                )
            }

            val settings = JSONObject()
            val all = ShizukuSettings.getPreferences().all
            for (key in SETTINGS) {
                val value = all[key] ?: continue
                if (value is Boolean || value is Int || value is String) settings.put(key, value)
            }

            val root = JSONObject()
                .put("format", FORMAT)
                .put("version", 1)
                .put("apps", apps)
                .put("settings", settings)

            contentResolver.openOutputStream(uri)?.use {
                it.write(root.toString(2).toByteArray(Charsets.UTF_8))
            } ?: error("cannot open $uri")
            apps.length()
        }) { count ->
            toast(getString(R.string.tool_backup_created, count))
        }
    }

    private fun read(uri: Uri) {
        runAsync({
            val text = contentResolver.openInputStream(uri)?.use {
                String(it.readBytes(), Charsets.UTF_8)
            } ?: error("cannot open $uri")
            val root = JSONObject(text)
            require(root.optString("format") == FORMAT) { getString(R.string.tool_backup_invalid) }

            // Authorizations: only for apps that are installed, matched by package and user
            val uids = HashMap<String, Int>()
            for (pi in AuthorizationManager.getPackages()) {
                val ai = pi.applicationInfo ?: continue
                uids[pi.packageName + ":" + ai.uid / 100000] = ai.uid
            }
            var restored = 0
            val apps = root.optJSONArray("apps") ?: JSONArray()
            for (i in 0 until apps.length()) {
                val o = apps.optJSONObject(i) ?: continue
                val uid = uids[o.optString("package") + ":" + o.optInt("user")] ?: continue
                Shizuku.updateFlagsForUid(uid, ALL_FLAGS, o.optInt("flags") and ALL_FLAGS)
                restored++
            }

            root.optJSONObject("settings")?.let { settings ->
                val editor = ShizukuSettings.getPreferences().edit()
                for (key in SETTINGS) {
                    when (val value = settings.opt(key)) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is String -> editor.putString(key, value)
                    }
                }
                editor.apply()
            }
            restored
        }) { count ->
            MonitorService.sync(applicationContext)
            toast(getString(R.string.tool_backup_restored, count))
        }
    }
}
