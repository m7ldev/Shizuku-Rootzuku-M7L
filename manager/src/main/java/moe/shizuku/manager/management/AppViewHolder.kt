package moe.shizuku.manager.management

import android.content.pm.PackageInfo
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.databinding.AppListItemBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.utils.AppIconCache
import moe.shizuku.manager.utils.ShizukuSystemApis
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku

class AppViewHolder(private val binding: AppListItemBinding) : BaseViewHolder<PackageInfo>(binding.root), View.OnClickListener {

    companion object {
        @JvmField
        val CREATOR = Creator<PackageInfo> { inflater: LayoutInflater, parent: ViewGroup? -> AppViewHolder(AppListItemBinding.inflate(inflater, parent, false)) }
    }


    private val icon get() = binding.icon
    private val name get() = binding.title
    private val pkg get() = binding.summary
    private val switchWidget get() = binding.switchWidget
    private val root get() = binding.requiresRoot

    init {
        itemView.filterTouchesWhenObscured = true
        itemView.setOnClickListener(this)
        itemView.setOnLongClickListener { v ->
            showRestrictions(v)
            true
        }
    }

    private fun isRestricted(): Boolean {
        return try {
            AuthorizationManager.supportsExtras() && AuthorizationManager.getRestrictions(uid) != 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun showRestrictions(v: View) {
        val context = v.context
        if (data == null) return
        if (!AuthorizationManager.supportsExtras()) {
            Toast.makeText(context, R.string.extras_need_restart, Toast.LENGTH_LONG).show()
            return
        }

        // The view holder may show another app by the time the dialog is closed
        val targetUid = uid
        val flags = try {
            AuthorizationManager.getRestrictions(targetUid)
        } catch (e: Throwable) {
            return
        }
        val denyFlags = intArrayOf(
            AuthorizationManager.FLAG_DENY_SHELL,
            AuthorizationManager.FLAG_DENY_USER_SERVICE,
            AuthorizationManager.FLAG_DENY_BINDER
        )
        val labels = arrayOf<CharSequence>(
            context.getString(R.string.apps_restriction_shell),
            context.getString(R.string.apps_restriction_user_service),
            context.getString(R.string.apps_restriction_binder)
        )
        val checked = BooleanArray(denyFlags.size) { (flags and denyFlags[it]) == 0 }

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.apps_restrictions_title, name.text))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                var newFlags = 0
                for (i in denyFlags.indices) {
                    if (!checked[i]) newFlags = newFlags or denyFlags[i]
                }
                try {
                    AuthorizationManager.setRestrictions(targetUid, newFlags)
                } catch (e: Throwable) {
                    Toast.makeText(context, e.toString(), Toast.LENGTH_SHORT).show()
                }
                adapter?.notifyDataSetChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inline val packageName get() = data.packageName
    private inline val ai get() = data.applicationInfo!!
    private inline val uid get() = ai.uid

    private var loadIconJob: Job? = null

    override fun onClick(v: View) {
        val context = v.context
        try {
            if (AuthorizationManager.granted(packageName, uid)) {
                AuthorizationManager.revoke(packageName, uid)
            } else {
                AuthorizationManager.grant(packageName, uid)
            }
        } catch (e: SecurityException) {
            val uid = try {
                Shizuku.getUid()
            } catch (ex: Throwable) {
                return
            }
            if (uid != 0) {
                val dialog = MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.app_management_dialog_adb_is_limited_title)
                        .setMessage(context.getString(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB.get()).toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE))
                        .setPositiveButton(android.R.string.ok, null)
                        .create()
                dialog.setOnShowListener {
                    (it as AlertDialog).findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
                }
                try {
                    dialog.show()
                } catch (ignored: Throwable) {
                }
            }
        }
        catch (e: Throwable) {
            // Show what went wrong instead of closing the app
            try {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.tool_apps_error_title)
                    .setMessage(e.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (ignored: Throwable) {
            }
        }
        adapter.notifyItemChanged(adapterPosition, Any())
    }

    override fun onBind() {
        val pm = itemView.context.packageManager
        val userId = UserHandleCompat.getUserId(uid)
        icon.setImageDrawable(ai.loadIcon(pm))
        name.text = if (userId != UserHandleCompat.myUserId()) {
            val userInfo = ShizukuSystemApis.getUserInfo(userId)
            "${ai.loadLabel(pm)} - ${userInfo.name} ($userId)"
        } else {
            ai.loadLabel(pm)
        }
        pkg.text = ai.packageName
        switchWidget.isChecked = AuthorizationManager.granted(packageName, uid)
        root.visibility = if (ai.metaData != null && ai.metaData.getBoolean("moe.shizuku.client.V3_REQUIRES_ROOT")) View.VISIBLE else View.GONE
        binding.restricted.visibility = if (isRestricted()) View.VISIBLE else View.GONE

        loadIconJob = AppIconCache.loadIconBitmapAsync(context, ai, ai.uid / 100000, icon)
    }

    override fun onBind(payloads: List<Any>) {
        switchWidget.isChecked = AuthorizationManager.granted(packageName, uid)
    }

    override fun onRecycle() {
        if (loadIconJob?.isActive == true) {
            loadIconJob?.cancel()
        }
    }
}
