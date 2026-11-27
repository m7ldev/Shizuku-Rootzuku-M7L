package moe.shizuku.manager.tools

import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.ToolRowBinding
import moe.shizuku.manager.utils.AppIconCache

/**
 * One line of a tool screen.
 *
 * @param icon a drawable to show, or 0
 * @param iconBackground a drawable drawn behind [icon] (the icon is then white), or 0
 * @param appInfo show the icon of this app instead of [icon]
 * @param checked show a switch in this state, or null for no switch
 * @param mono small monospace text, for log lines
 * @param dimmed draw the row half transparent
 */
class Row(
    val title: CharSequence,
    val summary: CharSequence? = null,
    @DrawableRes val icon: Int = 0,
    @DrawableRes val iconBackground: Int = 0,
    val appInfo: ApplicationInfo? = null,
    val checked: Boolean? = null,
    val mono: Boolean = false,
    val dimmed: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: (() -> Unit)? = null
)

class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {

    class Holder(val binding: ToolRowBinding) : RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null
        val normalPaddingTop = binding.root.paddingTop
        val normalPaddingBottom = binding.root.paddingBottom
        val normalMinHeight = binding.root.minimumHeight
        val normalTextSize = binding.title.textSize
        val marginHorizontal = (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
        val marginVertical = (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
    }

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(ToolRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val binding = holder.binding
        val context = binding.root.context
        val density = context.resources.displayMetrics.density

        binding.title.text = row.title
        binding.summary.text = row.summary
        binding.summary.visibility = if (row.summary.isNullOrEmpty()) View.GONE else View.VISIBLE

        // Log lines are small and tight, everything else is a normal list row
        // Log lines are plain text, everything else is a card
        val params = binding.root.layoutParams as? ViewGroup.MarginLayoutParams
        if (row.mono) {
            binding.root.background = null
            params?.apply {
                marginStart = 0
                marginEnd = 0
                topMargin = 0
                bottomMargin = 0
            }
        } else {
            binding.root.setBackgroundResource(R.drawable.neon_row_background)
            params?.apply {
                marginStart = holder.marginHorizontal
                marginEnd = holder.marginHorizontal
                topMargin = holder.marginVertical
                bottomMargin = holder.marginVertical
            }
        }

        if (row.mono) {
            binding.title.typeface = Typeface.MONOSPACE
            binding.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            binding.root.minimumHeight = 0
            binding.root.setPadding(
                binding.root.paddingLeft, (2 * density).toInt(),
                binding.root.paddingRight, (2 * density).toInt()
            )
        } else {
            binding.title.typeface = Typeface.DEFAULT
            binding.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, holder.normalTextSize)
            binding.root.minimumHeight = holder.normalMinHeight
            binding.root.setPadding(
                binding.root.paddingLeft, holder.normalPaddingTop,
                binding.root.paddingRight, holder.normalPaddingBottom
            )
        }

        holder.iconJob?.cancel()
        holder.iconJob = null
        val icon = binding.icon
        when {
            row.appInfo != null -> {
                icon.visibility = View.VISIBLE
                icon.setBackgroundResource(0)
                icon.imageTintList = null
                icon.setPadding(0, 0, 0, 0)
                icon.setImageDrawable(null)
                holder.iconJob = AppIconCache.loadIconBitmapAsync(context, row.appInfo, row.appInfo.uid / 100000, icon)
            }
            row.icon != 0 -> {
                icon.visibility = View.VISIBLE
                val padding = (9 * density).toInt()
                icon.setPadding(padding, padding, padding, padding)
                icon.setImageResource(row.icon)
                if (row.iconBackground != 0) {
                    icon.setBackgroundResource(row.iconBackground)
                    // In a neon box the icon glows in the colour of the box
                    val tint = Neon.ofIconBox(row.iconBackground)?.resolve(context) ?: Color.WHITE
                    icon.imageTintList = ColorStateList.valueOf(tint)
                } else {
                    icon.setBackgroundResource(0)
                    icon.imageTintList = null
                }
            }
            else -> icon.visibility = View.GONE
        }

        if (row.checked != null) {
            binding.switchWidget.visibility = View.VISIBLE
            binding.switchWidget.isChecked = row.checked
        } else {
            binding.switchWidget.visibility = View.GONE
        }

        binding.root.alpha = if (row.dimmed) 0.5f else 1f
        binding.root.isClickable = row.onClick != null || row.onLongClick != null
        binding.root.setOnClickListener(row.onClick?.let { action -> View.OnClickListener { action() } })
        binding.root.setOnLongClickListener(row.onLongClick?.let { action ->
            View.OnLongClickListener {
                action()
                true
            }
        })
        binding.root.isLongClickable = row.onLongClick != null
    }

    override fun onViewRecycled(holder: Holder) {
        holder.iconJob?.cancel()
        holder.iconJob = null
    }
}
