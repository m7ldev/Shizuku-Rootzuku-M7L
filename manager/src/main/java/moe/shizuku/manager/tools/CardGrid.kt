package moe.shizuku.manager.tools

import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import moe.shizuku.manager.databinding.ToolGridItemBinding
import moe.shizuku.manager.utils.AppIconCache

/**
 * A neon card of a card grid: an icon (or the icon of an app) and a name.
 *
 * @param fullWidth the card spans the whole row and is lower, for notices and actions
 */
class Card(
    val title: CharSequence,
    val neon: Neon,
    val icon: Int = 0,
    val appInfo: ApplicationInfo? = null,
    val fullWidth: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: (() -> Unit)? = null
)

class CardGridAdapter : RecyclerView.Adapter<CardGridAdapter.Holder>() {

    class Holder(val binding: ToolGridItemBinding) : RecyclerView.ViewHolder(binding.root) {
        var iconJob: Job? = null
    }

    companion object {
        const val COLUMNS = 2

        /** Height of a card as a part of its width. */
        private const val SHAPE = 0.66f

        private const val TYPE_CARD = 0
        private const val TYPE_FULL_WIDTH = 1

        /**
         * A layout manager with two columns in which full-width cards take a whole row.
         */
        fun layoutManager(view: RecyclerView, adapter: CardGridAdapter): GridLayoutManager {
            val manager = GridLayoutManager(view.context, COLUMNS)
            manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.cards.getOrNull(position)?.fullWidth == true) COLUMNS else 1
                }
            }
            return manager
        }
    }

    var cards: List<Card> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemCount(): Int = cards.size

    override fun getItemViewType(position: Int): Int {
        return if (cards[position].fullWidth) TYPE_FULL_WIDTH else TYPE_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ToolGridItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (viewType == TYPE_CARD) {
            // The height follows the width of a column
            val density = parent.resources.displayMetrics.density
            val margins = (12 * density).toInt()
            val column = (parent.measuredWidth - parent.paddingLeft - parent.paddingRight) / COLUMNS
            if (column > margins) {
                binding.root.layoutParams.height = ((column - margins) * SHAPE).toInt()
            }
        }
        if (viewType == TYPE_FULL_WIDTH) {
            // A wide card is a button: a small icon next to its text
            val density = parent.resources.displayMetrics.density
            (binding.root as? LinearLayout)?.orientation = LinearLayout.HORIZONTAL
            binding.icon.layoutParams.width = (36 * density).toInt()
            binding.icon.layoutParams.height = (36 * density).toInt()
            (binding.title.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.width = ViewGroup.LayoutParams.WRAP_CONTENT
                it.topMargin = 0
                it.marginStart = (12 * density).toInt()
            }
        }
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = cards[position]
        val binding = holder.binding
        val context = binding.root.context
        val density = context.resources.displayMetrics.density
        val color = card.neon.resolve(context)

        binding.root.setBackgroundResource(card.neon.card)
        binding.title.text = card.title
        // A soft glow in the colour of the card
        binding.title.setShadowLayer(14f, 0f, 0f, color)

        holder.iconJob?.cancel()
        holder.iconJob = null
        val icon = binding.icon
        if (card.appInfo != null) {
            icon.setBackgroundResource(0)
            icon.imageTintList = null
            icon.setPadding(0, 0, 0, 0)
            icon.setImageDrawable(null)
            holder.iconJob = AppIconCache.loadIconBitmapAsync(context, card.appInfo, card.appInfo.uid / 100000, icon)
        } else {
            val padding = ((if (card.fullWidth) 8 else 13) * density).toInt()
            icon.setPadding(padding, padding, padding, padding)
            icon.setBackgroundResource(card.neon.iconBox)
            icon.imageTintList = ColorStateList.valueOf(color)
            icon.setImageResource(card.icon)
        }

        binding.root.setOnClickListener(card.onClick?.let { action -> View.OnClickListener { action() } })
        binding.root.setOnLongClickListener(card.onLongClick?.let { action ->
            View.OnLongClickListener {
                action()
                true
            }
        })
        binding.root.isLongClickable = card.onLongClick != null
    }

    override fun onViewRecycled(holder: Holder) {
        holder.iconJob?.cancel()
        holder.iconJob = null
    }
}
