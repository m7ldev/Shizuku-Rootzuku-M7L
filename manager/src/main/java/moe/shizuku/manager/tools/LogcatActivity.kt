package moe.shizuku.manager.tools

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R

/**
 * Shows the latest lines of the system log. The Shizuku service may read the log of every
 * app, which a normal app may not.
 */
class LogcatActivity : ToolListActivity() {

    companion object {
        private const val LINES = 1500
        private val LEVELS = arrayOf("V", "D", "I", "W", "E")
        private val LEVEL_NAMES = arrayOf<CharSequence>("Verbose", "Debug", "Info", "Warning", "Error")
    }

    override val searchable: Boolean = true

    private var lines: List<String> = emptyList()
    private var level = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        showLoading()
        load()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menu.add(0, 1, 0, R.string.activity_log_refresh)
        menu.add(0, 2, 0, R.string.tool_logcat_level)
        menu.add(0, 3, 0, R.string.tool_logcat_share)
        menu.add(0, 4, 0, R.string.tool_logcat_clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> {
                load()
                true
            }
            2 -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.tool_logcat_level)
                    .setSingleChoiceItems(LEVEL_NAMES, level) { dialog, which ->
                        dialog.dismiss()
                        level = which
                        load()
                    }
                    .show()
                true
            }
            3 -> {
                share()
                true
            }
            4 -> {
                runAsync({ ShizukuShell.exec("logcat -c") }) { load() }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onQueryChanged(query: String) {
        show()
    }

    private fun load() {
        runAsync({
            ShizukuShell.exec("logcat -d -v time -t $LINES '*:${LEVELS[level]}'", 4 * 1024 * 1024)
        }) { result ->
            if (!result.isSuccess && result.out.isBlank()) {
                showMessage(result.message)
            } else {
                lines = result.out.lines().filter { it.isNotBlank() }
                show()
                binding.list.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0))
            }
        }
    }

    private fun filtered(): List<String> {
        val q = query.trim()
        return if (q.isEmpty()) lines else lines.filter { it.contains(q, ignoreCase = true) }
    }

    private fun show() {
        showRows(filtered().map { Row(title = it, mono = true) }, getString(R.string.tool_logcat_empty))
    }

    private fun share() {
        // Intents cannot carry much, keep the newest part
        val text = filtered().takeLast(400).joinToString("\n")
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                    getString(R.string.tool_logcat_share)
                )
            )
        } catch (e: Throwable) {
            toast(e.toString())
        }
    }
}
