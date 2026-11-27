package moe.shizuku.manager.tools

import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import moe.shizuku.manager.R
import moe.shizuku.manager.terminal.TerminalActivity

/**
 * "Tools Shizuku": the tools as neon cards in two columns, each one opens its own screen.
 */
class ToolsActivity : ToolListActivity() {

    override val requiresService: Boolean = false

    private fun tool(title: Int, icon: Int, neon: Neon, activity: Class<*>): Card {
        return Card(
            title = getString(title),
            neon = neon,
            icon = icon,
            onClick = { startActivity(Intent(this, activity)) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return

        val grid = CardGridAdapter()
        grid.cards = listOf(
            tool(R.string.tool_files_title, R.drawable.ic_folder_24, Neon.AMBER, FileManagerActivity::class.java),
            tool(R.string.tool_apps_title, R.drawable.ic_android_24, Neon.GREEN, AppManagerActivity::class.java),
            tool(R.string.turbo_title, R.drawable.ic_bolt_24, Neon.GREEN, TurboActivity::class.java),
            tool(R.string.doctor_title, R.drawable.ic_doctor_24, Neon.RED, DoctorActivity::class.java),
            tool(R.string.network_title, R.drawable.ic_dns_24, Neon.TEAL, NetworkActivity::class.java),
            tool(R.string.adblock_title, R.drawable.ic_stop_24, Neon.RED, AdBlockActivity::class.java),
            tool(R.string.display_title, R.drawable.ic_phone_24, Neon.AMBER, DisplayActivity::class.java),
            tool(R.string.force_dark_title, R.drawable.ic_outline_dark_mode_24, Neon.VIOLET, ForceDarkActivity::class.java),
            tool(R.string.activities_title, R.drawable.ic_apps_24, Neon.GREEN, ActivitiesActivity::class.java),
            tool(R.string.locale_title, R.drawable.ic_article_24, Neon.AMBER, AppLocaleActivity::class.java),
            tool(R.string.dev_title, R.drawable.ic_code_24dp, Neon.PINK, DevToolsActivity::class.java),
            tool(R.string.tool_backup_title, R.drawable.ic_cloud_backup_24, Neon.BLUE, BackupActivity::class.java),
            tool(R.string.tool_logs_title, R.drawable.ic_article_24, Neon.BLUE, LogsActivity::class.java),
            tool(R.string.terminal_title, R.drawable.ic_terminal_24, Neon.VIOLET, TerminalActivity::class.java)
        )

        // The spacing of the plain list would push only the first card down
        while (binding.list.itemDecorationCount > 0) {
            binding.list.removeItemDecorationAt(0)
        }
        binding.list.layoutManager = CardGridAdapter.layoutManager(binding.list, grid)
        binding.list.layoutAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_grid)
        binding.list.adapter = grid
        binding.list.scheduleLayoutAnimation()
    }
}
