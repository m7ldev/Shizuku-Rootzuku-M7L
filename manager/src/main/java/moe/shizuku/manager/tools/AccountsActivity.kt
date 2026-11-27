package moe.shizuku.manager.tools

import android.os.Bundle
import moe.shizuku.manager.R
import moe.shizuku.manager.utils.CustomTabsHelper

/**
 * Links to the accounts of the developer of this edition.
 */
class AccountsActivity : ToolListActivity() {

    override val requiresService: Boolean = false

    private fun link(title: String, url: String, icon: Int, background: Int): Row {
        return Row(
            title = title,
            summary = url.removePrefix("https://").removePrefix("www."),
            icon = icon,
            iconBackground = background,
            onClick = { CustomTabsHelper.launchUrlOrCopy(this, url) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return

        showRows(
            listOf(
                link("YouTube", "https://youtube.com/@androidplus4494",
                    R.drawable.ic_play_24, R.drawable.account_box_red),
                link("Instagram", "https://www.instagram.com/m7l.misoz",
                    R.drawable.ic_camera_24, R.drawable.account_box_insta),
                link("X", "https://x.com/m7lmisoz",
                    R.drawable.ic_at_24, R.drawable.account_box_black),
                link("Telegram", "https://t.me/m7lmisoz",
                    R.drawable.ic_send_24, R.drawable.account_box_sky),
                link(getString(R.string.accounts_support), "https://www.paypal.com/paypalme/hakrm7l",
                    R.drawable.ic_heart_24, R.drawable.account_box_navy)
            )
        )
    }
}
