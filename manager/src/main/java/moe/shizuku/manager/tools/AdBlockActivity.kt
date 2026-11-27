package moe.shizuku.manager.tools

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import moe.shizuku.manager.R

/**
 * A local, no-root ad blocker. Turning it on starts a private VPN that stays on the device and
 * filters out ad and tracker domains for every app and the browser. It does not send traffic
 * anywhere and does not need Shizuku. It cannot block YouTube's own ads (same servers as the
 * videos), and only one VPN can run at a time, so it stops any VPN app you use.
 */
class AdBlockActivity : ToolListActivity() {

    companion object {
        private const val REQ_VPN = 81
    }

    override val requiresService: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        show()
    }

    override fun onResume() {
        super.onResume()
        if (ready) show()
    }

    private fun show() {
        val on = AdVpnService.running
        showRows(
            listOf(
                Row(
                    title = getString(R.string.adblock_vpn),
                    summary = getString(if (on) R.string.adblock_vpn_on else R.string.adblock_vpn_summary),
                    checked = on,
                    icon = R.drawable.ic_stop_24, iconBackground = R.drawable.tool_icon_green,
                    onClick = { if (on) AdVpnService.stop(this).also { postRefresh() } else enable() }
                ),
                Row(
                    title = getString(R.string.adblock_note_title),
                    summary = getString(R.string.adblock_note_youtube),
                    icon = R.drawable.ic_outline_info_24, iconBackground = R.drawable.tool_icon_gray,
                    onClick = {}
                )
            )
        )
    }

    private fun enable() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            startActivityForResult(consent, REQ_VPN)
        } else {
            AdVpnService.start(this)
            postRefresh()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == Activity.RESULT_OK) {
            AdVpnService.start(this)
        }
        postRefresh()
    }

    private fun postRefresh() {
        binding.list.postDelayed({ if (!isFinishing) show() }, 600)
    }
}
