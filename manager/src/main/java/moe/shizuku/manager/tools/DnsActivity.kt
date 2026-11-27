package moe.shizuku.manager.tools

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import java.net.InetAddress
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import rikka.shizuku.Shizuku

/**
 * Switches the Private DNS of the phone with one tap, for example to a server that blocks
 * ads. The setting is the same one as in the network settings of Android.
 */
class DnsActivity : ToolListActivity() {

    companion object {
        private const val MODE = "private_dns_mode"
        private const val HOST = "private_dns_specifier"

        private const val MODE_OFF = "off"
        private const val MODE_AUTOMATIC = "opportunistic"
        private const val MODE_HOSTNAME = "hostname"
    }

    private class Server(val name: Int, val host: String)

    private val servers = listOf(
        Server(R.string.dns_adguard, "dns.adguard-dns.com"),
        Server(R.string.dns_adguard_family, "family.adguard-dns.com"),
        Server(R.string.dns_cloudflare, "one.one.one.one"),
        Server(R.string.dns_google, "dns.google"),
        Server(R.string.dns_quad9, "dns.quad9.net")
    )

    override val requiresService: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        show()
    }

    private fun get(name: String): String? {
        return try {
            Settings.Global.getString(contentResolver, name)
        } catch (e: Throwable) {
            null
        }
    }

    private fun show() {
        val mode = get(MODE) ?: MODE_AUTOMATIC
        val host = get(HOST).orEmpty()
        val rows = ArrayList<Row>()

        rows.add(Row(title = getString(R.string.dns_explanation), dimmed = true))
        rows.add(
            Row(
                title = getString(R.string.dns_automatic),
                checked = mode == MODE_AUTOMATIC,
                icon = R.drawable.ic_dns_24, iconBackground = R.drawable.tool_icon_gray,
                onClick = { if (mode == MODE_AUTOMATIC) apply(MODE_OFF, null) else apply(MODE_AUTOMATIC, null) }
            )
        )
        for (server in servers) {
            rows.add(
                Row(
                    title = getString(server.name),
                    summary = server.host,
                    checked = mode == MODE_HOSTNAME && host == server.host,
                    icon = R.drawable.ic_dns_24, iconBackground = R.drawable.tool_icon_blue,
                    // Switching the active server off turns private DNS off
                    onClick = {
                        if (mode == MODE_HOSTNAME && host == server.host) apply(MODE_OFF, null)
                        else apply(MODE_HOSTNAME, server.host)
                    }
                )
            )
        }
        val custom = mode == MODE_HOSTNAME && servers.none { it.host == host }
        rows.add(
            Row(
                title = getString(R.string.dns_custom),
                summary = if (custom) host else null,
                checked = custom,
                icon = R.drawable.ic_dns_24, iconBackground = R.drawable.tool_icon_violet,
                onClick = { if (custom) apply(MODE_OFF, null) else askHost("") }
            )
        )
        showRows(rows)
    }

    private fun askHost(initial: String) {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "dns.example.com"
            setText(initial)
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dns_custom)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = input.text.toString().trim()
                // A host name only: no scheme, no path, no spaces
                if (host.isEmpty() || !host.contains('.') || host.any { it.isWhitespace() || it == '/' || it == ':' }) {
                    toast(getString(R.string.dns_invalid))
                } else {
                    apply(MODE_HOSTNAME, host)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Writes the setting, directly when this app may, otherwise through the service.
     *
     * @return an error message, or null when it worked
     */
    private fun write(mode: String, host: String?): String? {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (host != null) Settings.Global.putString(contentResolver, HOST, host)
                Settings.Global.putString(contentResolver, MODE, mode)
                return null
            } catch (e: Throwable) {
                // Fall through to the shell
            }
        }
        if (!Shizuku.pingBinder()) return getString(R.string.stealth_no_permission)
        val command = (if (host != null) "settings put global $HOST ${ShizukuShell.quote(host)}; " else "") +
                "settings put global $MODE $mode"
        val result = ShizukuShell.exec(command)
        return if (result.isSuccess) null else result.message
    }

    /** Whether names can be looked up right now. */
    private fun resolves(): Boolean {
        return listOf("example.org", "wikipedia.org", "cloudflare.com").any {
            try {
                InetAddress.getByName(it)
                true
            } catch (e: Throwable) {
                false
            }
        }
    }

    /**
     * Applies the setting. A chosen server is tested afterwards: some networks block private
     * DNS servers, and Android then has no internet at all. When the lookups worked before and
     * stop working with the new server, the previous setting is put back.
     */
    private fun apply(mode: String, host: String?) {
        val oldMode = get(MODE) ?: MODE_AUTOMATIC
        val oldHost = get(HOST)
        if (mode == MODE_HOSTNAME) toast(getString(R.string.dns_checking))
        runAsync({
            val workedBefore = mode == MODE_HOSTNAME && resolves()
            val error = write(mode, host)
            when {
                error != null -> error
                !workedBefore -> null
                else -> {
                    Thread.sleep(5000)
                    var ok = resolves()
                    if (!ok) {
                        Thread.sleep(5000)
                        ok = resolves()
                    }
                    if (ok) {
                        null
                    } else {
                        // Going back to the same broken server would not help
                        if (oldMode == MODE_HOSTNAME && (oldHost.isNullOrEmpty() || oldHost == host)) {
                            write(MODE_AUTOMATIC, null)
                        } else {
                            write(oldMode, oldHost)
                        }
                        getString(R.string.dns_reverted, host.orEmpty())
                    }
                }
            }
        }) { error ->
            if (error != null) {
                MaterialAlertDialogBuilder(this)
                    .setMessage(error)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            show()
        }
    }
}
