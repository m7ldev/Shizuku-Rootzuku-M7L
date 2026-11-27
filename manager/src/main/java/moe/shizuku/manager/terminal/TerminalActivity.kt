package moe.shizuku.manager.terminal

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.TerminalActivityBinding
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A very small terminal: one "sh" process started by the Shizuku service. Commands are written
 * to its standard input, so the working directory and variables are kept between commands.
 * There is no real tty, programs that need one will not work.
 */
class TerminalActivity : AppBarActivity() {

    companion object {
        private const val MAX_OUTPUT_LENGTH = 100_000
        private const val TRIMMED_OUTPUT_LENGTH = 80_000
    }

    private lateinit var binding: TerminalActivityBinding
    private val handler = Handler(Looper.getMainLooper())
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()

    /** Increased whenever the shell is replaced, so that output of an old shell is ignored. */
    @Volatile
    private var generation = 0
    private var process: IRemoteProcess? = null
    private var stdin: OutputStream? = null

    private val pending = StringBuilder()
    private var flushPosted = false

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (!isFinishing) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Shizuku.pingBinder()) {
            finish()
            return
        }

        binding = TerminalActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.send.setOnClickListener { submit() }
        binding.input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND
                || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submit()
                true
            } else {
                false
            }
        }

        addQuickCommands()

        // Keep the input line above the keyboard
        binding.root.viewTreeObserver.addOnGlobalLayoutListener { adjustForKeyboard() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.inputRow) { _, insets ->
            handler.post { adjustForKeyboard() }
            insets
        }

        Shizuku.addBinderDeadListener(binderDeadListener)
        startShell()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderDeadListener(binderDeadListener)
        generation++
        destroyShell()
        writer.shutdown()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.terminal, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear -> {
                binding.output.text = ""
                true
            }
            R.id.action_restart -> {
                destroyShell()
                startShell()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Moves the input line up by exactly the part of it that the keyboard covers. Measuring
     * instead of trusting the insets makes this work no matter who else already made room.
     */
    private fun adjustForKeyboard() {
        if (isDestroyed) return
        val row = binding.inputRow
        val lp = row.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val insets = ViewCompat.getRootWindowInsets(row) ?: return
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom

        val margin = if (ime <= 0) {
            0
        } else {
            val location = IntArray(2)
            row.getLocationInWindow(location)
            val rowBottom = location[1] + row.height
            val visibleBottom = window.decorView.height - ime
            (lp.bottomMargin + rowBottom - visibleBottom).coerceAtLeast(0)
        }
        if (margin != lp.bottomMargin) {
            lp.bottomMargin = margin
            row.layoutParams = lp
        }
    }

    /**
     * A ready-made command.
     *
     * @param runNow run it when tapped. False for commands that change something: those are
     * only written to the input line, so that nothing happens by accident.
     */
    private class QuickCommand(val label: Int, val command: String, val runNow: Boolean = true)

    private val quickCommands = listOf(
        QuickCommand(R.string.terminal_quick_device, "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; id"),
        QuickCommand(R.string.terminal_quick_packages, "pm list packages -3"),
        QuickCommand(R.string.terminal_quick_battery, "dumpsys battery"),
        QuickCommand(R.string.terminal_quick_storage, "df -h /data /sdcard"),
        QuickCommand(R.string.terminal_quick_memory, "cat /proc/meminfo | head -6"),
        QuickCommand(R.string.terminal_quick_processes, "top -b -n 1 | head -25"),
        QuickCommand(R.string.terminal_quick_current_app, "dumpsys activity activities | grep -E 'ResumedActivity' | head -3"),
        QuickCommand(R.string.terminal_quick_network, "ip -4 addr show | grep inet"),
        QuickCommand(R.string.terminal_quick_screen, "wm size; wm density"),
        QuickCommand(R.string.terminal_quick_clear_cache, "pm trim-caches 999G", false),
        QuickCommand(R.string.terminal_quick_reboot, "reboot", false)
    )

    private fun addQuickCommands() {
        val density = resources.displayMetrics.density
        for (quick in quickCommands) {
            val chip = TextView(this).apply {
                text = getString(quick.label)
                textSize = 13f
                setBackgroundResource(R.drawable.chip_background)
                setPadding((14 * density).toInt(), (7 * density).toInt(), (14 * density).toInt(), (7 * density).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    binding.input.setText(quick.command)
                    binding.input.setSelection(quick.command.length)
                    if (quick.runNow) submit()
                }
                setOnLongClickListener {
                    // Long-press: only fill the input line, to change the command before running
                    binding.input.setText(quick.command)
                    binding.input.setSelection(quick.command.length)
                    true
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = (8 * density).toInt()
            binding.quickCommands.addView(chip, params)
        }
    }

    private fun startShell() {
        val gen = ++generation
        thread(name = "shizuku-terminal") {
            try {
                val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                val p = service.newProcess(arrayOf("sh"), null, null)
                if (gen != generation) {
                    p.destroy()
                    return@thread
                }
                val out = ParcelFileDescriptor.AutoCloseOutputStream(p.outputStream)
                val user = if (Shizuku.getUid() == 0) "root" else "adb (shell)"

                handler.post {
                    if (gen == generation) {
                        process = p
                        stdin = out
                    }
                }
                appendLine(getString(R.string.terminal_welcome, user), gen)

                pump(ParcelFileDescriptor.AutoCloseInputStream(p.inputStream), gen)
                pump(ParcelFileDescriptor.AutoCloseInputStream(p.errorStream), gen)

                val code = p.waitFor()
                appendLine(getString(R.string.terminal_exited, code), gen)
                handler.post {
                    if (gen == generation) {
                        process = null
                        stdin = null
                    }
                }
            } catch (e: Throwable) {
                appendLine(getString(R.string.terminal_failed, e.toString()), gen)
            }
        }
    }

    private fun destroyShell() {
        val p = process
        val out = stdin
        process = null
        stdin = null
        if (p == null && out == null) return
        thread(name = "shizuku-terminal-destroy") {
            try {
                out?.close()
            } catch (e: Throwable) {
            }
            try {
                p?.destroy()
            } catch (e: Throwable) {
            }
        }
    }

    private fun pump(input: InputStream, gen: Int) {
        thread(name = "shizuku-terminal-reader", isDaemon = true) {
            try {
                val reader = InputStreamReader(input, Charsets.UTF_8)
                val buffer = CharArray(4096)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    if (n > 0) append(String(buffer, 0, n), gen)
                }
            } catch (e: Throwable) {
            } finally {
                try {
                    input.close()
                } catch (e: Throwable) {
                }
            }
        }
    }

    private fun appendLine(text: String, gen: Int) {
        append(text + "\n", gen)
    }

    /**
     * Collects output from any thread and shows it in batches on the main thread.
     */
    private fun append(text: String, gen: Int) {
        if (gen != generation) return
        synchronized(pending) {
            pending.append(text)
            if (!flushPosted) {
                flushPosted = true
                handler.postDelayed({ flush() }, 50)
            }
        }
    }

    private fun flush() {
        val text = synchronized(pending) {
            val t = pending.toString()
            pending.setLength(0)
            flushPosted = false
            t
        }
        if (isDestroyed || text.isEmpty()) return

        val output = binding.output
        output.append(text)
        val length = output.length()
        if (length > MAX_OUTPUT_LENGTH) {
            output.editableText?.delete(0, length - TRIMMED_OUTPUT_LENGTH)
        }
        binding.scroll.post { binding.scroll.scrollTo(0, output.bottom) }
    }

    private fun submit() {
        val command = binding.input.text?.toString().orEmpty()
        if (command.isBlank()) return

        val out = stdin
        val gen = generation
        if (out == null) {
            // The shell has exited, start a new one for the next command
            destroyShell()
            startShell()
            return
        }

        binding.input.setText("")
        append("$ $command\n", gen)
        writer.execute {
            try {
                out.write((command + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: Throwable) {
                appendLine(e.toString(), gen)
            }
        }
    }
}
