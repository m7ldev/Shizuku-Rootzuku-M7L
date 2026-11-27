package moe.shizuku.manager.home

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import com.google.android.material.card.MaterialCardView
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeToolsGridBinding
import moe.shizuku.manager.management.ApplicationManagementActivity
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.stealth.StealthController
import moe.shizuku.manager.tools.StealthActivity
import moe.shizuku.manager.tools.ToolsActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * Three shortcut tiles on the home screen: Tools Shizuku, authorized apps and the apps
 * hidden by stealth mode. The data is the service status and the number of authorized apps.
 */
class ToolsGridViewHolder(private val binding: HomeToolsGridBinding, root: View) :
    BaseViewHolder<Pair<ServiceStatus, Int>>(root) {

    companion object {
        val CREATOR = Creator<Pair<ServiceStatus, Int>> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeToolsGridBinding.inflate(inflater, outer.root, true)
            ToolsGridViewHolder(inner, outer.root)
        }
    }

    init {
        // The tiles are the cards here, the container must not draw or react
        (root as? MaterialCardView)?.apply {
            setCardBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            isCheckable = false
            stateListAnimator = null
            clipChildren = false
        }

        binding.tileTools.setOnClickListener { open(it, ToolsActivity::class.java) }
        binding.tileApps.setOnClickListener { open(it, ApplicationManagementActivity::class.java) }
        binding.tileStealth.setOnClickListener { open(it, StealthActivity::class.java) }
    }

    private var entrancePlayed = false

    private fun open(v: View, activity: Class<*>) {
        v.context.startActivity(Intent(v.context, activity))
    }

    private fun setEnabled(tile: View, enabled: Boolean) {
        tile.isEnabled = enabled
        tile.alpha = if (enabled) 1f else 0.45f
    }

    override fun onBind() {
        val context = itemView.context
        val status = data.first
        val count = data.second
        // The list of authorized apps comes from the service
        val usable = status.isRunning && status.permission

        // Always open: the service doctor and stealth mode are needed exactly when Shizuku is down
        setEnabled(binding.tileTools, true)
        setEnabled(binding.tileApps, usable)
        setEnabled(binding.tileStealth, true)

        val hidden = StealthController.getApps().size
        binding.tileStealthSummary.text = if (hidden > 0) {
            context.getString(R.string.tile_hidden_apps_count, hidden)
        } else {
            context.getString(R.string.stealth_choose_apps)
        }

        if (!entrancePlayed) {
            // The tiles pop in one after another the first time they are shown
            entrancePlayed = true
            val tiles = listOf(binding.tileTools, binding.tileApps, binding.tileStealth)
            tiles.forEachIndexed { index, tile ->
                val targetAlpha = tile.alpha
                tile.alpha = 0f
                tile.scaleX = 0.8f
                tile.scaleY = 0.8f
                tile.animate()
                    .alpha(targetAlpha).scaleX(1f).scaleY(1f)
                    .setStartDelay(120L + 70L * index)
                    .setDuration(380)
                    .setInterpolator(OvershootInterpolator(1.6f))
                    .start()
            }
        }

        binding.tileAppsSummary.text = if (usable) {
            context.getString(R.string.tile_apps_summary_count, count)
        } else {
            context.getString(R.string.tile_apps_summary_default)
        }
    }
}
