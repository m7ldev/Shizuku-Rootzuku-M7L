package moe.shizuku.manager.log

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.databinding.ActivityLogActivityBinding
import moe.shizuku.manager.databinding.ActivityLogItemBinding
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Shows which apps used Shizuku and what they did.
 */
class ActivityLogActivity : AppBarActivity() {

    private class Row(val title: String, val entry: ActivityLogManager.Entry)

    private lateinit var binding: ActivityLogActivityBinding
    private val adapter = Adapter()
    private var entrancePlayed = false

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

        binding = ActivityLogActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(top = 8f, bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                load()
                true
            }
            R.id.action_share -> {
                val text = adapter.rows.joinToString("\n") { row ->
                    val e = row.entry
                    val time = DateUtils.formatDateTime(
                        this, e.time,
                        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE
                    )
                    "$time | ${row.title} | type ${e.type} | ${e.detail.orEmpty()} | x${e.count}" +
                            if (e.denied) " | blocked" else ""
                }
                try {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                            getString(R.string.tool_logcat_share)
                        )
                    )
                } catch (e: Throwable) {
                }
                true
            }
            R.id.action_clear -> {
                thread {
                    try {
                        ActivityLogManager.clear()
                    } catch (e: Throwable) {
                    }
                    runOnUiThread { load() }
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun load() {
        thread {
            val rows = try {
                val entries = ActivityLogManager.get()
                if (entries == null) {
                    null
                } else {
                    // Labels of the apps that use Shizuku, by uid
                    val names = HashMap<Int, String>()
                    try {
                        for (pi in AuthorizationManager.getPackages()) {
                            val ai = pi.applicationInfo ?: continue
                            names[ai.uid] = ai.loadLabel(packageManager).toString()
                        }
                    } catch (e: Throwable) {
                    }
                    entries.map { Row(names[it.uid] ?: it.packageName ?: "uid ${it.uid}", it) }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                null
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (rows == null) {
                    Toast.makeText(this, R.string.extras_need_restart, Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                adapter.rows = rows
                adapter.notifyDataSetChanged()
                if (!entrancePlayed && rows.isNotEmpty()) {
                    entrancePlayed = true
                    binding.list.scheduleLayoutAnimation()
                }
                binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private class ViewHolder(val binding: ActivityLogItemBinding) : RecyclerView.ViewHolder(binding.root)

    private class Adapter : RecyclerView.Adapter<ViewHolder>() {

        var rows: List<Row> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ActivityLogItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val context = holder.itemView.context
            val row = rows[position]
            val entry = row.entry

            val type = context.getString(
                when (entry.type) {
                    ActivityLogManager.TYPE_SHELL -> R.string.activity_log_type_shell
                    ActivityLogManager.TYPE_USER_SERVICE -> R.string.activity_log_type_user_service
                    else -> R.string.activity_log_type_binder
                }
            )

            var time: String = DateUtils.formatDateTime(
                context, entry.time,
                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
            )
            if (entry.count > 1) {
                time = context.getString(R.string.activity_log_count, time, entry.count)
            }
            if (entry.denied) {
                time = context.getString(R.string.activity_log_blocked) + " · " + time
            }

            holder.binding.title.text = row.title
            holder.binding.text1.text = if (entry.detail.isNullOrEmpty()) type else "$type: ${entry.detail}"
            holder.binding.text2.text = time
        }
    }
}
