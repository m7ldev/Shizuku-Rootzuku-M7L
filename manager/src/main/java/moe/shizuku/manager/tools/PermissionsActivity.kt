package moe.shizuku.manager.tools

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat

/**
 * Runtime permissions and app operations (AppOps) of one app.
 */
class PermissionsActivity : ToolListActivity() {

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"

        private val OP_LINE = Regex("^\\s*([A-Za-z_0-9]+): ([a-z]+)")
        private val MODES = arrayOf("allow", "ignore", "deny", "default", "foreground")
    }

    private class Permission(val name: String, val label: String, val granted: Boolean)
    private class Op(val name: String, val mode: String)
    private class Data(val permissions: List<Permission>, val ops: List<Op>)

    private lateinit var pkg: String
    private val userId = UserHandleCompat.myUserId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return

        pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }
        supportActionBar?.subtitle = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        showLoading()
        load()
    }

    private fun load() {
        runAsync({
            val permissions = ArrayList<Permission>()
            val info: PackageInfo? = ShizukuSystemApis
                .getInstalledPackages(PackageManager.GET_PERMISSIONS.toLong(), userId)
                .firstOrNull { it.packageName == pkg }
            val requested = info?.requestedPermissions
            val flags = info?.requestedPermissionsFlags
            if (requested != null) {
                for (i in requested.indices) {
                    val name = requested[i]
                    // Only permissions the user can decide about, the others cannot be changed
                    val permissionInfo = try {
                        packageManager.getPermissionInfo(name, 0)
                    } catch (e: Throwable) {
                        null
                    } ?: continue
                    @Suppress("DEPRECATION")
                    val base = permissionInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
                    if (base != PermissionInfo.PROTECTION_DANGEROUS) continue

                    val granted = flags != null && i < flags.size
                            && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    val label = try {
                        permissionInfo.loadLabel(packageManager).toString()
                    } catch (e: Throwable) {
                        name
                    }
                    permissions.add(Permission(name, label, granted))
                }
            }

            val ops = ArrayList<Op>()
            val seen = HashSet<String>()
            val result = ShizukuShell.exec("appops get --user $userId ${ShizukuShell.quote(pkg)}")
            for (line in result.out.lines()) {
                val match = OP_LINE.find(line) ?: continue
                val name = match.groupValues[1]
                val mode = match.groupValues[2]
                // The first lines are headers such as "Uid mode: ..."
                if (name != name.uppercase() || mode !in MODES) continue
                if (seen.add(name)) ops.add(Op(name, mode))
            }
            Data(permissions.sortedBy { it.label }, ops.sortedBy { it.name })
        }) { show(it) }
    }

    private fun show(data: Data) {
        val rows = ArrayList<Row>()
        if (data.permissions.isNotEmpty()) {
            rows.add(Row(title = getString(R.string.tool_permissions_runtime), dimmed = true))
            for (p in data.permissions) {
                rows.add(
                    Row(
                        title = p.label,
                        summary = p.name.removePrefix("android.permission."),
                        checked = p.granted,
                        onClick = {
                            val action = if (p.granted) "revoke" else "grant"
                            run("pm $action --user $userId ${ShizukuShell.quote(pkg)} ${ShizukuShell.quote(p.name)}")
                        }
                    )
                )
            }
        }
        if (data.ops.isNotEmpty()) {
            rows.add(Row(title = getString(R.string.tool_permissions_appops), dimmed = true))
            for (op in data.ops) {
                rows.add(
                    Row(
                        title = op.name,
                        summary = op.mode,
                        onClick = { chooseMode(op) }
                    )
                )
            }
        }
        showRows(rows, getString(R.string.tool_permissions_empty))
    }

    private fun chooseMode(op: Op) {
        val labels = arrayOf<CharSequence>(*MODES)
        MaterialAlertDialogBuilder(this)
            .setTitle(op.name)
            .setSingleChoiceItems(labels, MODES.indexOf(op.mode)) { dialog, which ->
                dialog.dismiss()
                run("appops set --user $userId ${ShizukuShell.quote(pkg)} ${op.name} ${MODES[which]}")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun run(command: String) {
        runAsync({ ShizukuShell.exec(command) }) { result ->
            if (!result.isSuccess) toast(result.message)
            load()
        }
    }
}
