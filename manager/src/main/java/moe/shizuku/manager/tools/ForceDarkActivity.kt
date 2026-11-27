package moe.shizuku.manager.tools

import android.os.Bundle
import moe.shizuku.manager.R

/**
 * Forces a dark look across the whole phone: the system night mode for apps that have a dark
 * theme, and a force-dark for apps that do not. Force-dark is a developer feature and takes
 * effect as apps are redrawn, so an app may need to be reopened.
 */
class ForceDarkActivity : ToolListActivity() {

    private var night = false
    private var forceDark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        showLoading()
        load()
    }

    private fun load() {
        runAsync({
            night = ShizukuShell.exec("cmd uimode night").out.contains("yes", ignoreCase = true)
            forceDark = ShizukuShell.exec("getprop debug.hwui.force_dark").out.trim() == "true"
            Unit
        }) { show() }
    }

    private fun show() {
        showRows(
            listOf(
                Row(
                    title = getString(R.string.force_dark_system),
                    summary = getString(R.string.force_dark_system_summary),
                    checked = night,
                    icon = R.drawable.ic_outline_dark_mode_24, iconBackground = R.drawable.tool_icon_violet,
                    onClick = { run("cmd uimode night ${if (night) "no" else "yes"}") }
                ),
                Row(
                    title = getString(R.string.force_dark_all),
                    summary = getString(R.string.force_dark_all_summary),
                    checked = forceDark,
                    icon = R.drawable.ic_outline_dark_mode_24, iconBackground = R.drawable.tool_icon_blue,
                    onClick = {
                        run("setprop debug.hwui.force_dark ${if (forceDark) "false" else "true"}", R.string.force_dark_restart_hint)
                    }
                )
            )
        )
    }

    private fun run(command: String, hint: Int = 0) {
        runAsync({ ShizukuShell.exec(command) }) { result ->
            if (!result.isSuccess) toast(result.message)
            else if (hint != 0) toast(getString(hint))
            load()
        }
    }
}
