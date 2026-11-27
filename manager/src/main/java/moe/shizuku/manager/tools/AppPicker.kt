package moe.shizuku.manager.tools

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.AppIconCache
import java.util.Locale
import kotlin.concurrent.thread

/**
 * A dialog for choosing one installed app, shown with its icon and name and a field to search.
 */
object AppPicker {

    class Item(val info: ApplicationInfo, val label: String) {
        val user: Boolean get() = (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(android.R.id.icon)
        val title: TextView = view.findViewById(android.R.id.title)
        val summary: TextView = view.findViewById(android.R.id.summary)
        var job: Job? = null
    }

    private class Adapter(
        private val all: List<Item>,
        private val onPick: (Item) -> Unit
    ) : RecyclerView.Adapter<Holder>() {

        var items: List<Item> = all

        fun filter(text: String) {
            val q = text.trim().lowercase(Locale.getDefault())
            items = if (q.isEmpty()) all else all.filter {
                it.label.lowercase(Locale.getDefault()).contains(q) ||
                        it.info.packageName.lowercase(Locale.ROOT).contains(q)
            }
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(LayoutInflater.from(parent.context).inflate(R.layout.app_pick_row, parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.label
            holder.summary.text = item.info.packageName
            holder.job?.cancel()
            holder.icon.setImageDrawable(null)
            holder.job = AppIconCache.loadIconBitmapAsync(
                holder.icon.context, item.info, item.info.uid / 100000, holder.icon
            )
            holder.itemView.setOnClickListener { onPick(item) }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.job?.cancel()
            holder.job = null
        }
    }

    fun show(activity: Activity, title: Int, onPicked: (Item) -> Unit) {
        thread(name = "app-picker") {
            val pm = activity.packageManager
            val items = try {
                pm.getInstalledApplications(0).map { info ->
                    val label = try {
                        info.loadLabel(pm).toString()
                    } catch (e: Throwable) {
                        info.packageName
                    }
                    Item(info, label)
                }
            } catch (e: Throwable) {
                emptyList()
            }.sortedWith(
                // Apps installed by the user come first, then by name
                compareByDescending<Item> { it.user }.thenBy { it.label.lowercase(Locale.getDefault()) }
            )
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) showDialog(activity, title, items, onPicked)
            }
        }
    }

    private fun showDialog(activity: Activity, title: Int, items: List<Item>, onPicked: (Item) -> Unit) {
        val density = activity.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            setPadding(0, (4 * density).toInt(), 0, 0)
            clipToPadding = false
        }
        val search = EditText(activity).apply {
            hint = activity.getString(R.string.apps_search)
            setSingleLine()
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, (8 * density).toInt(), padding, 0)
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
            ))
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val adapter = Adapter(items) { item ->
            dialog.dismiss()
            onPicked(item)
        }
        list.adapter = adapter
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
            }
        })
        dialog.show()
    }
}
