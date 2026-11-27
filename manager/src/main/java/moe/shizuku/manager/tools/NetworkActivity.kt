package moe.shizuku.manager.tools

import android.content.Intent
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import rikka.shizuku.Shizuku
import java.util.Locale

/**
 * Network management: private DNS, tests of the connection and the settings that decide which
 * apps may use the network in the background.
 *
 * Nothing here can make the line itself faster, that would need root. What helps in practice
 * is a DNS server that answers quickly and keeping apps from using data in the background.
 */
class NetworkActivity : ToolListActivity() {

    companion object {
        private val DNS_SERVERS = listOf(
            "Cloudflare" to "one.one.one.one",
            "Google" to "dns.google",
            "Quad9" to "dns.quad9.net",
            "AdGuard" to "dns.adguard-dns.com"
        )
        private val TEST_TARGETS = listOf(
            "Cloudflare 1.1.1.1" to "1.1.1.1",
            "Google 8.8.8.8" to "8.8.8.8",
            "google.com" to "google.com"
        )
    }

    override val requiresService: Boolean = false

    private var dataSaver: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        show()
        load()
    }

    private fun load() {
        if (!Shizuku.pingBinder()) return
        runAsync({ ShizukuShell.exec("cmd netpolicy get restrict-background").out }) { out ->
            dataSaver = out.contains("enabled")
            show()
        }
    }

    private fun show() {
        val rows = ArrayList<Row>()
        rows.add(
            Row(
                title = getString(R.string.dns_title),
                summary = getString(R.string.dns_summary),
                icon = R.drawable.ic_dns_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { startActivity(Intent(this, DnsActivity::class.java)) }
            )
        )
        rows.add(
            Row(
                title = getString(R.string.network_fastest_dns),
                summary = getString(R.string.network_fastest_dns_summary),
                icon = R.drawable.ic_bolt_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { measure(R.string.network_fastest_dns, DNS_SERVERS) }
            )
        )
        rows.add(
            Row(
                title = getString(R.string.network_test),
                summary = getString(R.string.network_test_summary),
                icon = R.drawable.ic_history_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { measure(R.string.network_test, TEST_TARGETS) }
            )
        )
        rows.add(
            Row(
                title = getString(R.string.network_data_saver),
                summary = getString(R.string.network_data_saver_summary),
                checked = dataSaver ?: false,
                icon = R.drawable.ic_shield_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { service("cmd netpolicy set restrict-background ${if (dataSaver == true) "false" else "true"}") }
            )
        )
        rows.add(
            Row(
                title = getString(R.string.network_block_app),
                summary = getString(R.string.network_block_app_summary),
                icon = R.drawable.ic_stop_24, iconBackground = R.drawable.tool_icon_green,
                onClick = { startActivity(Intent(this, NetworkAppsActivity::class.java)) }
            )
        )
        rows.add(
            Row(
                title = getString(R.string.network_refresh),
                summary = getString(R.string.network_refresh_summary),
                icon = R.drawable.ic_swap_24, iconBackground = R.drawable.tool_icon_green,
                onClick = {
                    service("cmd connectivity airplane-mode enable; sleep 3; cmd connectivity airplane-mode disable")
                }
            )
        )
        rows.add(
            Row(
                title = getString(R.string.network_reset),
                summary = getString(R.string.network_reset_summary),
                icon = R.drawable.ic_history_24, iconBackground = R.drawable.tool_icon_red,
                onClick = { confirmReset() }
            )
        )
        showRows(rows)
    }

    /** Puts the network settings this app can touch back to their defaults and restarts the radios. */
    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.network_reset)
            .setMessage(R.string.network_reset_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.network_reset) { _, _ ->
                service(
                    "settings put global private_dns_mode off; " +
                        "settings delete global private_dns_specifier; " +
                        "settings put global wifi_scan_throttle_enabled 1; " +
                        "settings put global mobile_data_always_on 0; " +
                        "cmd netpolicy set restrict-background false; " +
                        "svc wifi enable; svc data enable; " +
                        "cmd connectivity airplane-mode enable; sleep 3; cmd connectivity airplane-mode disable"
                )
            }
            .show()
    }

    /** Runs a command that needs the service, and says so when it is not running. */
    private fun service(command: String) {
        if (!Shizuku.pingBinder()) {
            toast(getString(R.string.home_status_service_not_running, getString(R.string.app_name)))
            return
        }
        toast(getString(R.string.tools_loading))
        runAsync({ ShizukuShell.exec(command) }) { result ->
            toast(if (result.isSuccess) getString(R.string.tools_done) else result.message)
            load()
        }
    }

    /**
     * The average answer time of a host in milliseconds, or null when it did not answer.
     * ping is a normal command, this works without the service.
     */
    private fun ping(host: String): Double? {
        return try {
            val process = ProcessBuilder("ping", "-c", "3", "-W", "2", host).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            // rtt min/avg/max/mdev = 11.1/12.2/13.3/0.9 ms
            output.lines().firstOrNull { it.contains("min/avg") }
                ?.substringAfter('=')?.trim()?.split('/')?.getOrNull(1)?.toDoubleOrNull()
        } catch (e: Throwable) {
            null
        }
    }

    private fun measure(title: Int, targets: List<Pair<String, String>>) {
        toast(getString(R.string.network_measuring))
        runAsync({ targets.map { it to ping(it.second) } }) { results ->
            val lines = results.sortedBy { it.second ?: Double.MAX_VALUE }.joinToString("\n") { (target, time) ->
                val value = if (time == null) getString(R.string.network_no_answer)
                else String.format(Locale.ROOT, "%.0f ms", time)
                "${target.first}: $value"
            }
            val best = results.filter { it.second != null }.minByOrNull { it.second ?: 0.0 }
            val text = if (best == null) {
                lines + "\n\n" + getString(R.string.network_all_failed)
            } else {
                lines + "\n\n" + getString(R.string.network_fastest, best.first.first)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

}
