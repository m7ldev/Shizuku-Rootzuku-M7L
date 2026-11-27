package moe.shizuku.manager.tools

import android.content.Intent
import android.os.Bundle
import moe.shizuku.manager.R
import moe.shizuku.manager.log.ActivityLogActivity

/**
 * The two logs in one place: the system log and the record of what apps did through Shizuku.
 */
class LogsActivity : ToolListActivity() {

    override val requiresService: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return

        showRows(
            listOf(
                Row(
                    title = getString(R.string.tool_logcat_title),
                    summary = getString(R.string.tool_logcat_summary),
                    icon = R.drawable.ic_bug_24,
                    iconBackground = R.drawable.tool_icon_red,
                    onClick = { startActivity(Intent(this, LogcatActivity::class.java)) }
                ),
                Row(
                    title = getString(R.string.activity_log_title),
                    summary = getString(R.string.home_activity_log_description),
                    icon = R.drawable.ic_history_24,
                    iconBackground = R.drawable.tool_icon_teal,
                    onClick = { startActivity(Intent(this, ActivityLogActivity::class.java)) }
                )
            )
        )
    }
}
