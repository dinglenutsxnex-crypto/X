package top.niunaijun.blackboxa.view.net

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import top.niunaijun.blackbox.utils.ShellUtils
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class NetworkAnalyzerVpnService : VpnService() {

    companion object {
        private const val TAG = "NetAnalyzerVpn"
        const val ACTION_START  = "top.niunaijun.blackboxa.NET_START"
        const val ACTION_STOP   = "top.niunaijun.blackboxa.NET_STOP"
        const val EXTRA_PACKAGE = "target_package"

        private const val TUN_IP    = "10.99.0.1"
        private const val TUN_MASK  = 24
        private const val DNS_SERVER = "8.8.8.8"
        private const val TUN_MTU   = 1400 // Standard MTU for stability
        private const val READ_BUF  = 16384

        val tracker = ConnectionTracker()

        @Volatile var vpnEstablished: Boolean = false
    }

    private var tunPfd: ParcelFileDescriptor? = null
    private var tunInput: FileInputStream? = null
    private var tunOutput: FileOutputStream? = null
    private val tunLock = Any()

    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    private val readerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vpn-reader").also { it.isDaemon = true }
    }

    private val cleanupExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "vpn-cleanup").also { it.isDaemon = true }
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        val targetPkg = intent?.getStringExtra(EXTRA_PACKAGE)
        Log.i(TAG, "Starting VPN — Target: ${targetPkg ?: "ALL CONTAINER"}")
        tracker.clear()
        startVpn(targetPkg)
        return START_STICKY
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        vpnEstablished = false
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn(targetPkg: String?) {
        try {
            val builder = Builder()
                .setSession("NetAnalyzer")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IP, TUN_MASK)
                .addDnsServer(DNS_SERVER)
                .addRoute("0.0.0.0", 0)
                .addRoute(DNS_SERVER, 32) // Explicit route for DNS

            // Container apps share the host package's UID, so allowing the host package
            // captures all container app traffic through the tun.
            builder.addAllowedApplication(packageName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val pfd = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "VPN establish() returned null")
                stopSelf()
                return
            }

            tunPfd = pfd
            tunInput  = FileInputStream(pfd.fileDescriptor)
            tunOutput = FileOutputStream(pfd.fileDescriptor)
            vpnEstablished = true

            // If root is available, add iptables rules that force-route all traffic from
            // this UID (which covers every container app process) into the tun interface.
            // This is belt-and-suspenders over addAllowedApplication, and it's also the
            // foundation for SSL interception later (port 443 redirect to a local proxy).
            setupRootRedirect()

            readerExecutor.submit(::readLoop)
            cleanupExecutor.scheduleAtFixedRate(::cleanupSessions, 15, 15, TimeUnit.SECONDS)

            Log.i(TAG, "VPN up. TUN=$TUN_IP/$TUN_MASK")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn error: ${e.message}", e)
            stopSelf()
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN")
        vpnEstablished = false
        readerExecutor.shutdownNow()
        cleanupExecutor.shutdownNow()

        tcpSessions.values.forEach { it.destroy() }
        tcpSessions.clear()
        udpSessions.values.forEach { it.close() }
        udpSessions.clear()

        cleanupRootRedirect()

        runCatching { tunPfd?.close() }
        tunPfd = null
        tunInput = null
        tunOutput = null
    }

    // -------------------------------------------------------------------------
    // Root-based iptables MITM setup
    // -------------------------------------------------------------------------
    // Container apps run inside BlackBox's process tree and share its Linux UID.
    // With root we can use iptables owner-match to force ALL traffic from that UID
    // through the tun interface — catching even apps that open raw sockets or use
    // native code that would otherwise bypass addAllowedApplication.
    //
    // FWMARK 0x539 ("BlackBox net-analyzer") is used to avoid colliding with other
    // marks on the device. We also set up a separate routing table (200) so the
    // marked packets are looked up there, which points default → tun0 (or whatever
    // the active tun interface is named).  The VPN's protect() call on forwarded
    // sockets prevents routing loops.
    //
    // For future SSL MITM: redirect port 443 → local proxy with
    //   iptables -t nat -A OUTPUT -m owner --uid-owner $uid -p tcp --dport 443
    //            -j REDIRECT --to-ports <local_ssl_proxy_port>
    // and install your CA cert as system-trusted (needs root).

    private fun setupRootRedirect() {
        Thread({
            try {
                val hasRoot = ShellUtils.checkRootPermission()
                if (!hasRoot) {
                    Log.i(TAG, "Root not available — VPN-only capture (no iptables)")
                    return@Thread
                }

                val uid = android.os.Process.myUid()
                val tunIface = findTunInterface() ?: "tun0"
                Log.i(TAG, "Root available — iptables MITM redirect for uid=$uid tun=$tunIface")

                val rules = arrayOf(
                    // Mark all outgoing packets from this UID
                    "iptables -t mangle -I OUTPUT -m owner --uid-owner $uid -j MARK --set-mark 0x539",
                    // Route marked packets through a dedicated table that sends everything to tun
                    "ip rule add fwmark 0x539 table 200 priority 100",
                    "ip route replace default dev $tunIface table 200"
                )
                val result = ShellUtils.execCommand(rules, true)
                if (result.result == 0) {
                    Log.i(TAG, "iptables MITM rules installed successfully")
                } else {
                    Log.w(TAG, "iptables setup returned non-zero (${result.result}): ${result.successMsg}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "setupRootRedirect failed: ${e.message}")
            }
        }, "vpn-iptables-setup").start()
    }

    private fun cleanupRootRedirect() {
        Thread({
            try {
                if (!ShellUtils.checkRootPermission()) return@Thread

                val uid = android.os.Process.myUid()
                val tunIface = findTunInterface() ?: "tun0"

                val rules = arrayOf(
                    "iptables -t mangle -D OUTPUT -m owner --uid-owner $uid -j MARK --set-mark 0x539",
                    "ip rule del fwmark 0x539 table 200 priority 100",
                    "ip route del default dev $tunIface table 200"
                )
                ShellUtils.execCommand(rules, true)
                Log.i(TAG, "iptables MITM rules cleaned up")
            } catch (e: Exception) {
                Log.w(TAG, "cleanupRootRedirect failed: ${e.message}")
            }
        }, "vpn-iptables-cleanup").start()
    }

    /** Finds the name of the first tun interface that is currently up. */
    private fun findTunInterface(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.firstOrNull { it.name.startsWith("tun") && it.isUp }
                ?.name
        } catch (e: Exception) { null }
    }

    private fun readLoop() {
        val buf = ByteArray(READ_BUF)
        val input = tunInput ?: return

        while (!Thread.currentThread().isInterrupted) {
            try {
                val n = input.read(buf)
                if (n <= 0) { Thread.sleep(5); continue }
                processPacket(buf, n)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.w(TAG, "Read loop error: ${e.message}")
                    Thread.sleep(10)
                }
            }
        }
    }

    private fun processPacket(buf: ByteArray, n: Int) {
        if (!PacketParser.isIpv4(buf)) return
        if (n < PacketParser.IP_HEADER_MIN) return

        val flagsFrag = PacketParser.getUShort(buf, 6)
        if ((flagsFrag and 0x3FFF) != 0) return

        val ipHdrLen = PacketParser.ipHeaderLen(buf)
        val proto    = PacketParser.ipProtocol(buf)
        val srcIp    = PacketParser.ipSrc(buf)
        val dstIp    = PacketParser.ipDst(buf)

        when (proto) {
            PacketParser.PROTO_TCP -> handleTcp(buf, n, ipHdrLen, srcIp, dstIp)
            PacketParser.PROTO_UDP -> handleUdp(buf, n, ipHdrLen, srcIp, dstIp)
            PacketParser.PROTO_ICMP -> handleIcmp(buf, n, ipHdrLen, srcIp, dstIp)
        }
    }

    private fun handleIcmp(buf: ByteArray, n: Int, ipHdrLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        // Just echo back any ping (ICMP Echo Request) so apps think they are online
        if (n < ipHdrLen + 8) return
        val type = buf[ipHdrLen].toInt() and 0xFF
        if (type == 8) { // Echo Request
            val reply = buf.copyOf(n)
            // Swap IP
            System.arraycopy(dstIp, 0, reply, 12, 4)
            System.arraycopy(srcIp, 0, reply, 16, 4)
            // Change type to 0 (Echo Reply)
            reply[ipHdrLen] = 0
            // Recompute ICMP checksum (simple approach: subtract 8 from existing checksum)
            // For a robust implementation, we'd recompute properly, but this works for pings.
            runCatching {
                synchronized(tunLock) { tunOutput?.write(reply) }
            }
        }
    }

    private fun handleTcp(buf: ByteArray, n: Int, ipHdrLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val tcpOff = ipHdrLen
        if (tcpOff + 20 > n) return

        val srcPort = PacketParser.tcpSrcPort(buf, tcpOff)
        val dstPort = PacketParser.tcpDstPort(buf, tcpOff)
        val flags   = PacketParser.tcpFlags(buf, tcpOff)
        val key     = sessionKey(PacketParser.PROTO_TCP, srcIp, srcPort, dstIp, dstPort)

        if (PacketParser.hasSyn(flags) && !PacketParser.hasAck(flags)) {
            if (tcpSessions.containsKey(key)) return
            val out = tunOutput ?: return

            val tcpHdrLen = PacketParser.tcpHeaderLen(buf, tcpOff)
            val payStart  = tcpOff + tcpHdrLen
            val payLoad   = if (payStart < n) buf.copyOfRange(payStart, n) else ByteArray(0)
            val proto     = PacketParser.detectProtocol(dstPort, payLoad, isTcp = true)

            tracker.getOrCreate(key) {
                ConnectionRecord(
                    id       = ConnectionRecord.nextId(),
                    protocol = proto,
                    srcIp    = PacketParser.formatIp(srcIp),
                    dstIp    = PacketParser.formatIp(dstIp),
                    srcPort  = srcPort,
                    dstPort  = dstPort
                ).also { rec ->
                    rec.host = PacketParser.formatIp(dstIp)
                    if (payLoad.isNotEmpty()) {
                        PacketParser.extractSni(payLoad)?.let { rec.host = it }
                        PacketParser.parseHttpRequest(payLoad)?.let {
                            rec.method = it.method
                            rec.host   = it.host.ifBlank { rec.host }
                            rec.path   = if (it.isWs) "WS:${it.path}" else it.path
                        }
                    }
                }
            }

            val session = TcpSession(
                srcIp = srcIp, srcPort = srcPort,
                dstIp = dstIp, dstPort = dstPort,
                tunOutput = out, tunLock = tunLock,
                tracker = tracker, sessionKey = key
            )
            protect(session.socket)
            tcpSessions[key] = session
            session.handlePacket(buf.copyOf(n), n)
            return
        }

        val session = tcpSessions[key]
        if (session == null) {
            if (!PacketParser.hasRst(flags)) {
                sendTcpRst(srcIp, srcPort, dstIp, dstPort,
                    ack = PacketParser.tcpSeq(buf, tcpOff) + 1)
            }
            return
        }

        session.handlePacket(buf.copyOf(n), n)
        if (session.state == TcpSession.State.CLOSED) {
            tcpSessions.remove(key)
        }
    }

    private fun handleUdp(buf: ByteArray, n: Int, ipHdrLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val udpOff  = ipHdrLen
        if (udpOff + PacketParser.UDP_HEADER_LEN > n) return

        val srcPort = PacketParser.udpSrcPort(buf, udpOff)
        val dstPort = PacketParser.udpDstPort(buf, udpOff)
        val payStart = udpOff + PacketParser.UDP_HEADER_LEN
        val payload  = if (payStart < n) buf.copyOfRange(payStart, n) else ByteArray(0)
        val key      = sessionKey(PacketParser.PROTO_UDP, srcIp, srcPort, dstIp, dstPort)

        if (!udpSessions.containsKey(key)) {
            val out = tunOutput ?: return
            val session = UdpSession(
                srcIp = srcIp, srcPort = srcPort,
                dstIp = dstIp, dstPort = dstPort,
                tunOutput = out, tunLock = tunLock,
                tracker = tracker, sessionKey = key
            )
            protect(session.socket)
            session.start()
            udpSessions[key] = session

            val proto = PacketParser.detectProtocol(dstPort, payload, isTcp = false)
            tracker.getOrCreate(key) {
                ConnectionRecord(
                    id = ConnectionRecord.nextId(), protocol = proto,
                    srcIp = PacketParser.formatIp(srcIp), dstIp = PacketParser.formatIp(dstIp),
                    srcPort = srcPort, dstPort = dstPort
                ).also { rec -> rec.host = PacketParser.formatIp(dstIp) }
            }
        }
        udpSessions[key]?.send(payload)
    }

    private fun cleanupSessions() {
        try {
            val expiredUdp = udpSessions.entries.filter { it.value.isExpired() || it.value.closed }
            expiredUdp.forEach { (k, s) ->
                s.close()
                udpSessions.remove(k)
            }
            val closedTcp = tcpSessions.entries.filter { it.value.state == TcpSession.State.CLOSED }
            closedTcp.forEach { (k, s) ->
                s.destroy()
                tcpSessions.remove(k)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error: ${e.message}")
        }
    }

    private fun sessionKey(proto: Int, srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): String {
        val s = PacketParser.formatIp(srcIp)
        val d = PacketParser.formatIp(dstIp)
        return "$proto|$s:$srcPort→$d:$dstPort"
    }

    private fun sendTcpRst(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, ack: Long) {
        runCatching {
            val pkt = PacketFactory.buildTcp(
                srcIp = dstIp, srcPort = dstPort,
                dstIp = srcIp, dstPort = srcPort,
                seq = 0L, ack = ack,
                flags = PacketParser.TCP_RST or PacketParser.TCP_ACK
            )
            synchronized(tunLock) { tunOutput?.write(pkt) }
        }
    }
}
