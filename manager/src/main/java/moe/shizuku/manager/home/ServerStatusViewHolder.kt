package moe.shizuku.manager.home

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.google.android.material.card.MaterialCardView
import androidx.core.content.ContextCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeServerStatusBinding
import moe.shizuku.manager.model.ServiceStatus
import rikka.html.text.HtmlCompat
import rikka.html.text.toHtml
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerConstants

class ServerStatusViewHolder(private val binding: HomeServerStatusBinding, private val root: View) :
    BaseViewHolder<ServiceStatus>(root) {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeServerStatusBinding.inflate(inflater, outer.root, true)
            ServerStatusViewHolder(inner, outer.root)
        }
    }

    private inline val textView get() = binding.text1
    private inline val summaryView get() = binding.text2
    private inline val iconView get() = binding.icon

    private var pulseAnimator: ObjectAnimator? = null
    private var lastState: Boolean? = null

    init {
        // The gradient is the card, the container only adds the ripple and the press effect
        (root as? MaterialCardView)?.setCardBackgroundColor(Color.TRANSPARENT)
        root.setOnClickListener { v ->
            v.context.startActivity(Intent(v.context, ServiceControlActivity::class.java))
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (lastState == true) startPulse()
            }

            override fun onViewDetachedFromWindow(v: View) {
                stopPulse()
            }
        })
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        val pulse = binding.pulse
        pulse.visibility = View.VISIBLE
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            pulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.55f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.55f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f)
        ).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.pulse.visibility = View.INVISIBLE
    }

    override fun onBind() {
        val context = itemView.context
        val status = data
        val ok = status.isRunning
        val isRoot = status.uid == 0
        val apiVersion = status.apiVersion
        val patchVersion = status.patchVersion
        if (ok) {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_ok_24dp))
        } else {
            iconView.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_server_error_24dp))
        }
        binding.heroRoot.setBackgroundResource(if (ok) R.drawable.hero_running else R.drawable.hero_stopped)
        binding.statusChip.setText(if (ok) R.string.hero_chip_running else R.string.hero_chip_stopped)
        if (ok) startPulse() else stopPulse()
        if (lastState != ok) {
            // A small pop when the state changes
            iconView.scaleX = 0.6f
            iconView.scaleY = 0.6f
            iconView.animate().scaleX(1f).scaleY(1f).setDuration(450)
                .setInterpolator(OvershootInterpolator(2.5f)).start()
            lastState = ok
        }
        val user = if (isRoot) "root" else "adb"
        val title = if (ok) {
            context.getString(R.string.home_status_service_is_running, context.getString(R.string.app_name))
        } else {
            context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        }
        val summary = if (ok) {
            if (apiVersion != Shizuku.getLatestServiceVersion() || status.patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION) {
                context.getString(
                    R.string.home_status_service_version_update, user,
                    "${apiVersion}.${patchVersion}",
                    "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}"
                )
            } else {
                context.getString(R.string.home_status_service_version, user, "${apiVersion}.${patchVersion}")
            }
        } else {
            context.getString(R.string.hero_tap_to_start)
        }
        textView.text = title.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        summaryView.text = summary.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        if (TextUtils.isEmpty(summaryView.text)) {
            summaryView.visibility = View.GONE
        } else {
            summaryView.visibility = View.VISIBLE
        }
    }

    override fun onRecycle() {
        super.onRecycle()
        stopPulse()
    }
}
