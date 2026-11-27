package moe.shizuku.manager.tools

import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ToolListActivityBinding
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Base of the tool screens: a toolbar, a list of [Row]s and a message for the empty state.
 */
abstract class ToolListActivity : AppBarActivity() {

    protected lateinit var binding: ToolListActivityBinding
        private set
    protected val adapter = RowAdapter()

    /** Tools that talk to the Shizuku service close themselves when it is not running. */
    protected open val requiresService: Boolean = true

    /** Show a search field in the toolbar, changes arrive in [onQueryChanged]. */
    protected open val searchable: Boolean = false

    protected var query: String = ""
        private set

    private var entrancePlayed = false

    /** False when onCreate gave up, subclasses must not touch the views then. */
    protected var ready = false
        private set

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (requiresService && !isFinishing) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (requiresService && !Shizuku.pingBinder()) {
            Toast.makeText(
                this,
                getString(R.string.home_status_service_not_running, getString(R.string.app_name)),
                Toast.LENGTH_SHORT
            ).show()
            finish()
            return
        }

        binding = ToolListActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(top = 8f, bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        Shizuku.addBinderDeadListener(binderDeadListener)
        ready = true
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (searchable) {
            menuInflater.inflate(R.menu.tool_search, menu)
            val searchView = menu.findItem(R.id.action_search)?.actionView as? SearchView
            searchView?.queryHint = getString(R.string.apps_search)
            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    query = newText.orEmpty()
                    onQueryChanged(query)
                    return true
                }
            })
        }
        return true
    }

    protected open fun onQueryChanged(query: String) {
    }

    protected fun showRows(rows: List<Row>, emptyText: CharSequence? = null) {
        if (!ready) return
        adapter.rows = rows
        if (!entrancePlayed && rows.isNotEmpty()) {
            entrancePlayed = true
            binding.list.scheduleLayoutAnimation()
        }
        binding.empty.text = emptyText ?: getString(R.string.tools_empty)
        binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    protected fun showMessage(text: CharSequence) {
        showRows(emptyList(), text)
    }

    protected fun showLoading() {
        showMessage(getString(R.string.tools_loading))
    }

    protected fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /**
     * Runs [work] on a background thread and [done] on the main thread, unless the activity
     * was closed in the meantime.
     */
    protected fun <T> runAsync(work: () -> T, done: (T) -> Unit) {
        thread(name = "shizuku-tool") {
            val result = try {
                Result.success(work())
            } catch (e: Throwable) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(done) { toast(it.toString()) }
            }
        }
    }
}
