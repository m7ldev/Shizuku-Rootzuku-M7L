package moe.shizuku.manager.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import moe.shizuku.manager.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * A local, no-root ad blocker built on VpnService. It captures only DNS: apps send their name
 * lookups to a fake DNS address routed into the tunnel, and for each lookup we either answer
 * 0.0.0.0 (when the domain belongs to an ad or tracker) or forward it to a real resolver and pass
 * the answer back. All other traffic goes straight out. It cannot block YouTube ads, which come
 * from the same servers as the videos.
 */
class AdVpnService : VpnService() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "ad_vpn"
        private const val NOTIFICATION_ID = 64

        // fake DNS seen by apps, and the real resolver we forward clean lookups to
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val TUN_DNS = "10.111.222.2"
        private const val UPSTREAM = "1.1.1.1"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, AdVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, AdVpnService::class.java).setAction(ACTION_STOP))
            } catch (e: Throwable) {
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        foreground()
        connect()
        return START_STICKY
    }

    private fun foreground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val m = getSystemService(NotificationManager::class.java)
            if (m?.getNotificationChannel(CHANNEL) == null) {
                m?.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.adblock_title), NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, AdBlockActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stop_24)
            .setContentTitle(getString(R.string.adblock_title))
            .setContentText(getString(R.string.adblock_vpn_on))
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun connect() {
        val builder = Builder()
            .setSession(getString(R.string.adblock_title))
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(TUN_DNS)
            .addRoute(TUN_DNS, 32)
            .setMtu(1500)
        // Do not route our own app through the tunnel
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Throwable) {
        }
        val fd = try {
            builder.establish()
        } catch (e: Throwable) {
            null
        }
        if (fd == null) {
            stopSelf()
            return
        }
        tun = fd
        running = true
        worker = Thread { loop(fd) }.apply { isDaemon = true; start() }
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val packet = ByteArray(32767)
        while (running) {
            val n = try {
                input.read(packet)
            } catch (e: Throwable) {
                break
            }
            if (n <= 0) continue
            try {
                handle(packet, n, output)
            } catch (e: Throwable) {
                // drop malformed packets
            }
        }
        try { input.close() } catch (e: Throwable) {}
        try { output.close() } catch (e: Throwable) {}
    }

    /** Handles one captured packet: only IPv4 UDP to port 53 (DNS) is of interest. */
    private fun handle(data: ByteArray, len: Int, output: FileOutputStream) {
        if (len < 28) return
        val version = (data[0].toInt() ushr 4) and 0xF
        if (version != 4) return
        val ihl = (data[0].toInt() and 0xF) * 4
        val protocol = data[9].toInt() and 0xFF
        if (protocol != 17) return // UDP
        val udp = ihl
        val dstPort = ((data[udp + 2].toInt() and 0xFF) shl 8) or (data[udp + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val udpLen = ((data[udp + 4].toInt() and 0xFF) shl 8) or (data[udp + 5].toInt() and 0xFF)
        val dnsStart = udp + 8
        val dnsLen = udpLen - 8
        if (dnsLen <= 12 || dnsStart + dnsLen > len) return

        val domain = parseDomain(data, dnsStart, dnsStart + dnsLen) ?: return
        val reply: ByteArray = if (Blocklist.isBlocked(domain)) {
            buildBlockedAnswer(data, dnsStart, dnsLen)
        } else {
            forward(data, dnsStart, dnsLen) ?: return
        }
        writeResponse(data, ihl, udp, reply, output)
    }

    /** Reads the queried host name from a DNS question. */
    private fun parseDomain(data: ByteArray, start: Int, end: Int): String? {
        var i = start + 12 // skip DNS header
        val sb = StringBuilder()
        while (i < end) {
            val l = data[i].toInt() and 0xFF
            if (l == 0) break
            if (l > 63 || i + 1 + l > end) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (j in 0 until l) sb.append((data[i + 1 + j].toInt() and 0xFF).toChar())
            i += 1 + l
        }
        return if (sb.isEmpty()) null else sb.toString().lowercase()
    }

    /** Builds a DNS answer that points the blocked name at 0.0.0.0. */
    private fun buildBlockedAnswer(data: ByteArray, dnsStart: Int, dnsLen: Int): ByteArray {
        val query = data.copyOfRange(dnsStart, dnsStart + dnsLen)
        val out = ByteBuffer.allocate(dnsLen + 16)
        out.put(query)
        // flags: response, recursion available, no error
        out.put(2, 0x81.toByte())
        out.put(3, 0x80.toByte())
        // answer count = 1
        out.put(6, 0)
        out.put(7, 1)
        out.position(dnsLen)
        out.put(0xC0.toByte()); out.put(0x0C.toByte())        // name pointer to question
        out.put(0); out.put(1)                                 // type A
        out.put(0); out.put(1)                                 // class IN
        out.put(0); out.put(0); out.put(0x0E.toByte()); out.put(0x10.toByte()) // ttl 3600
        out.put(0); out.put(4)                                 // rdlength
        out.put(0); out.put(0); out.put(0); out.put(0)         // 0.0.0.0
        return out.array().copyOf(out.position())
    }

    /** Sends a clean lookup to the real resolver and returns its answer. */
    private fun forward(data: ByteArray, dnsStart: Int, dnsLen: Int): ByteArray? {
        val socket = DatagramSocket()
        return try {
            protect(socket)
            socket.soTimeout = 4000
            val q = data.copyOfRange(dnsStart, dnsStart + dnsLen)
            socket.send(DatagramPacket(q, q.size, InetAddress.getByName(UPSTREAM), 53))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            buf.copyOf(resp.length)
        } catch (e: Throwable) {
            null
        } finally {
            socket.close()
        }
    }

    /** Wraps a DNS reply back into an IPv4/UDP packet addressed to the app and writes it. */
    private fun writeResponse(req: ByteArray, ihl: Int, udp: Int, dns: ByteArray, output: FileOutputStream) {
        val total = ihl + 8 + dns.size
        val p = ByteArray(total)
        // IPv4 header: copy and swap addresses
        System.arraycopy(req, 0, p, 0, ihl)
        p[0] = (0x40 or (ihl / 4)).toByte()
        p[2] = ((total ushr 8) and 0xFF).toByte(); p[3] = (total and 0xFF).toByte()
        p[8] = 64 // ttl
        // swap src/dst IP (bytes 12..15 <-> 16..19)
        for (k in 0 until 4) {
            val s = req[12 + k]; p[12 + k] = req[16 + k]; p[16 + k] = s
        }
        // zero then compute IP checksum
        p[10] = 0; p[11] = 0
        val ipc = checksum(p, 0, ihl)
        p[10] = ((ipc ushr 8) and 0xFF).toByte(); p[11] = (ipc and 0xFF).toByte()
        // UDP header: swap ports, set length, clear checksum (0 = not checked)
        val uu = ihl
        p[uu] = req[udp + 2]; p[uu + 1] = req[udp + 3]       // src port = old dst (53)
        p[uu + 2] = req[udp]; p[uu + 3] = req[udp + 1]       // dst port = old src
        val ulen = 8 + dns.size
        p[uu + 4] = ((ulen ushr 8) and 0xFF).toByte(); p[uu + 5] = (ulen and 0xFF).toByte()
        p[uu + 6] = 0; p[uu + 7] = 0
        System.arraycopy(dns, 0, p, uu + 8, dns.size)
        output.write(p, 0, total)
        output.flush()
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun teardown() {
        running = false
        try { worker?.interrupt() } catch (e: Throwable) {}
        try { tun?.close() } catch (e: Throwable) {}
        tun = null
    }

    override fun onDestroy() {
        super.onDestroy()
        teardown()
    }

    override fun onRevoke() {
        teardown()
        stopSelf()
    }
}
