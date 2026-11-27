package moe.shizuku.manager.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeToolItemBinding
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * A simple card on the home screen: icon, title, description and a click action.
 */
class HomeTool(
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val enabled: Boolean,
    val onClick: (Context) -> Unit
)

class ToolViewHolder(private val binding: HomeToolItemBinding, private val root: View) :
    BaseViewHolder<HomeTool>(root),
    View.OnClickListener {

    companion object {
        val CREATOR = Creator<HomeTool> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeToolItemBinding.inflate(inflater, outer.root, true)
            ToolViewHolder(inner, outer.root)
        }
    }

    init {
        root.setOnClickListener(this)
    }

    override fun onBind() {
        val context = itemView.context
        val tool = data
        binding.icon.setImageResource(tool.icon)
        binding.text1.setText(tool.title)
        if (tool.enabled) {
            root.isEnabled = true
            binding.text2.setText(tool.summary)
        } else {
            root.isEnabled = false
            binding.text2.text =
                context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        }
    }

    override fun onClick(v: View) {
        val tool = data ?: return
        if (tool.enabled) {
            tool.onClick(v.context)
        }
    }
}
