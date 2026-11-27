package moe.shizuku.manager.tools

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Observer
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Start Shizuku over the classic ADB port on localhost, which keeps working with no Wi-Fi and no
 * computer. Wireless debugging hands out a fresh TLS port every boot and needs a network to find
 * it; the classic port (5555) can be opened once and then reconnected to on 127.0.0.1 with no
 * network, until the next reboot.
 *
 * Opening the port is done the only way Android allows without root: over a live wireless
 * debugging connection, we ask adbd to also listen on the classic port (the `tcpip` request). So
 * opening it needs wireless debugging reachable that one time; starting over it afterwards does
 * not. adbd still checks the paired key, so only this device can connect.
 */
class UsbStartActivity : ToolListActivity() {

    override val requiresService: Boolean = false

    companion object {
        private const val PORT = 5555
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready) return
        showLoading()
        load()
    }

    private fun load() {
        runAsync({ isPortOpen() }) { show(it) }
    }

    private fun isPortOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 400); true }
    } catch (e: Throwable) {
        false
    }

    private fun show(open: Boolean) {
        val rows = ArrayList<Row>()
        rows.add(
            Row(
                title = getString(R.string.usb_start_now),
                summary = getString(R.string.usb_start_now_summary),
                icon = R.drawable.ic_play_24, iconBackground = R.drawable.tool_icon_green,
                onClick = {
                    startActivity(
                        Intent(this, StarterActivity::class.java)
                            .putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                            .putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                            .putExtra(StarterActivity.EXTRA_PORT, PORT)
                    )
                }
            )
        )
        rows.add(
            Row(
                title = getString(R.string.usb_open_port),
                summary = getString(
                    if (open) R.string.usb_open_port_on else R.string.usb_open_port_summary
                ),
                checked = open,
                icon = R.drawable.ic_bolt_24, iconBackground = R.drawable.tool_icon_teal,
                onClick = { if (open) closePort() else openPort() }
            )
        )
        showRows(rows)
    }

    /**
     * Finds the live wireless debugging port, connects to it and sends the tcpip request so adbd
     * also listens on the classic port. Needs wireless debugging on for this one step.
     */
    private fun openPort() {
        toast(getString(R.string.usb_opening))
        runAsync({
            val port = findWirelessPort()
                ?: return@runAsync getString(R.string.usb_no_wireless)
            try {
                val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                AdbClient("127.0.0.1", port, key).runCatching {
                    connect()
                    open("tcpip:$PORT")
                    close()
                }
                // adbd restarts after the request; give it a moment, then verify
                var up = false
                for (i in 0 until 20) {
                    if (isPortOpen()) { up = true; break }
                    Thread.sleep(300)
                }
                if (up) getString(R.string.tools_done) else getString(R.string.usb_open_failed)
            } catch (e: Throwable) {
                e.message ?: getString(R.string.usb_open_failed)
            }
        }) { message ->
            toast(message)
            load()
        }
    }

    /**
     * Closes the classic port again: connect to it and send the `usb:` request, which puts adbd
     * back to USB-only. adbd restarts, so the connection dying here is expected.
     */
    private fun closePort() {
        toast(getString(R.string.usb_closing))
        runAsync({
            try {
                val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                AdbClient("127.0.0.1", PORT, key).runCatching {
                    connect()
                    open("usb:")
                    close()
                }
                var down = false
                for (i in 0 until 20) {
                    if (!isPortOpen()) { down = true; break }
                    Thread.sleep(300)
                }
                if (down) getString(R.string.tools_done) else getString(R.string.usb_close_failed)
            } catch (e: Throwable) {
                e.message ?: getString(R.string.usb_close_failed)
            }
        }) { message ->
            toast(message)
            load()
        }
    }

    /** The wireless debug port, from the stable property if set, otherwise found over mDNS. */
    private fun findWirelessPort(): Int? {
        val known = EnvironmentUtils.getAdbTcpPort()
        if (known > 0) return known
        var found = -1
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(this, AdbMdns.TLS_CONNECT, Observer { p ->
            if (p != null && p > 0 && found < 0) { found = p; latch.countDown() }
        })
        mdns.start()
        try {
            latch.await(8, TimeUnit.SECONDS)
        } finally {
            mdns.stop()
        }
        return found.takeIf { it > 0 }
    }
}
