package top.niunaijun.blackboxa.view.net

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Core VPN service for the Network Analyzer.
 *
 * - Opens a TUN interface routed for all traffic (0.0.0.0/0).
 * - If [TARGET_PACKAGE] extra is provided the VPN is scoped to that package only.
 * - Reads raw IPv4 packets, relays TCP via [TcpSession] and UDP via [UdpSession].
 * - Updates [NetworkAnalyzerVpnService.tracker] with live connection data.
 *
 * FLAG note: if the device has no target package selected, we allow all traffic.
 * The VPN stops cleanly when [onRevoke] / [stopSelf] is called.
 */
class NetworkAnalyzerVpnService : VpnService() {

    companion object {
        private const val TAG = "NetAnalyzerVpn"
        const val ACTION_START  = "top.niunaijun.blackboxa.NET_START"
        const val ACTION_STOP   = "top.niunaijun.blackboxa.NET_STOP"
        const val EXTRA_PACKAGE = "target_package"

        private const val TUN_IP    = "10.99.0.1"
        private const val TUN_MASK  = 24
        private const val DNS_SERVER = "8.8.8.8"
        private const val TUN_MTU   = 16_384
        private const val READ_BUF  = TUN_MTU + 20

        // Singleton tracker — activity observes this directly
        val tracker = ConnectionTracker()
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var tunPfd: ParcelFileDescriptor? = null
    private var tunInput: FileInputStream? = null
    private var tunOutput: FileOutputStream? = null
    private val tunLock = Any()

    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    // Read loop runs on a dedicated thread
    private val readerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vpn-reader").also { it.isDaemon = true }
    }

    // Session cleanup runs periodically
    private val cleanupExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "vpn-cleanup").also { it.isDaemon = true }
        }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "Starting VPN — container-only mode")
        tracker.clear()
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ── VPN setup ─────────────────────────────────────────────────────────────

    private fun startVpn() {
        try {
            val builder = Builder()
                .setSession("NetAnalyzer")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IP, TUN_MASK)
                .addDnsServer(DNS_SERVER)
                .addRoute("0.0.0.0", 0)

            // Always restrict to the BlackBox host package — this ensures ONLY traffic
            // from apps running inside the container is captured, never host-device traffic.
            runCatching { builder.addAllowedApplication(packageName) }
                .onFailure { Log.w(TAG, "addAllowedApplication(self) failed: ${it.message}") }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val pfd = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "VPN establish() returned null — permission not granted?")
                stopSelf()
                return
            }

            tunPfd = pfd
            tunInput  = FileInputStream(pfd.fileDescriptor)
            tunOutput = FileOutputStream(pfd.fileDescriptor)

            // Start packet read loop
            readerExecutor.submit(::readLoop)

            // Schedule UDP session cleanup every 15 seconds
            cleanupExecutor.scheduleAtFixedRate(::cleanupSessions, 15, 15, TimeUnit.SECONDS)

            Log.i(TAG, "VPN up. TUN=$TUN_IP/$TUN_MASK")
        } catch (e: Exception) {
            Log.e(TAG, "startVpn error: ${e.message}", e)
            stopSelf()
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN")
        readerExecutor.shutdownNow()
        cleanupExecutor.shutdownNow()

        tcpSessions.values.forEach { it.destroy() }
        tcpSessions.clear()
        udpSessions.values.forEach { it.close() }
        udpSessions.clear()

        runCatching { tunPfd?.close() }
        tunPfd = null
        tunInput = null
        tunOutput = null
    }

    // ── Read loop ─────────────────────────────────────────────────────────────

    private fun readLoop() {
        val buf = ByteArray(READ_BUF)
        val input = tunInput ?: return

        Log.i(TAG, "VPN read loop started")
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
        Log.i(TAG, "VPN read loop stopped")
    }

    // ── Packet dispatch ───────────────────────────────────────────────────────

    private fun processPacket(buf: ByteArray, n: Int) {
        // Only handle IPv4
        if (!PacketParser.isIpv4(buf)) return
        if (n < PacketParser.IP_HEADER_MIN) return

        // Skip fragmented packets (fragment offset ≠ 0 or MF bit set for non-zero fragments)
        val flagsFrag = PacketParser.getUShort(buf, 6)
        if ((flagsFrag and 0x3FFF) != 0) return   // has fragment offset or MF

        val ipHdrLen = PacketParser.ipHeaderLen(buf)
        val proto    = PacketParser.ipProtocol(buf)
        val srcIp    = PacketParser.ipSrc(buf)
        val dstIp    = PacketParser.ipDst(buf)

        when (proto) {
            PacketParser.PROTO_TCP -> handleTcp(buf, n, ipHdrLen, srcIp, dstIp)
            PacketParser.PROTO_UDP -> handleUdp(buf, n, ipHdrLen, srcIp, dstIp)
            // ICMP and others: ignore (will time out naturally)
        }
    }

    // ── TCP dispatch ──────────────────────────────────────────────────────────

    private fun handleTcp(buf: ByteArray, n: Int, ipHdrLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val tcpOff = ipHdrLen
        if (tcpOff + 20 > n) return

        val srcPort = PacketParser.tcpSrcPort(buf, tcpOff)
        val dstPort = PacketParser.tcpDstPort(buf, tcpOff)
        val flags   = PacketParser.tcpFlags(buf, tcpOff)
        val key     = sessionKey(PacketParser.PROTO_TCP, srcIp, srcPort, dstIp, dstPort)

        // On SYN (new connection), create session and record
        if (PacketParser.hasSyn(flags) && !PacketParser.hasAck(flags)) {
            if (tcpSessions.containsKey(key)) {
                // Retransmitted SYN — ignore duplicate
                return
            }
            val out = tunOutput ?: return

            // Detect protocol from first payload or port
            val tcpHdrLen = PacketParser.tcpHeaderLen(buf, tcpOff)
            val payStart  = tcpOff + tcpHdrLen
            val payLoad   = if (payStart < n) buf.copyOfRange(payStart, n) else ByteArray(0)
            val proto     = PacketParser.detectProtocol(dstPort, payLoad, isTcp = true)

            // Create ConnectionRecord
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
                    // Try early SNI / HTTP inspection even on SYN payload (rare)
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
            // Protect the socket from looping back through VPN
            protect(session.socket)
            tcpSessions[key] = session
            session.handlePacket(buf.copyOf(n), n)
            return
        }

        // Deliver to existing session
        val session = tcpSessions[key]
        if (session == null) {
            // RST unknown sessions to clean up client side
            if (!PacketParser.hasRst(flags)) {
                sendTcpRst(srcIp, srcPort, dstIp, dstPort,
                    ack = PacketParser.tcpSeq(buf, tcpOff) + 1)
            }
            return
        }

        session.handlePacket(buf.copyOf(n), n)

        // Clean up closed sessions
        if (session.state == TcpSession.State.CLOSED) {
            tcpSessions.remove(key)
        }
    }

    // ── UDP dispatch ──────────────────────────────────────────────────────────

    private fun handleUdp(buf: ByteArray, n: Int, ipHdrLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val udpOff  = ipHdrLen
        if (udpOff + PacketParser.UDP_HEADER_LEN > n) return

        val srcPort = PacketParser.udpSrcPort(buf, udpOff)
        val dstPort = PacketParser.udpDstPort(buf, udpOff)
        val payStart = udpOff + PacketParser.UDP_HEADER_LEN
        val payload  = if (payStart < n) buf.copyOfRange(payStart, n) else ByteArray(0)
        val key      = sessionKey(PacketParser.PROTO_UDP, srcIp, srcPort, dstIp, dstPort)

        // Create session if new
        if (!udpSessions.containsKey(key)) {
            val out = tunOutput ?: return
            val session = UdpSession(
                srcIp = srcIp, srcPort = srcPort,
                dstIp = dstIp, dstPort = dstPort,
                tunOutput = out, tunLock = tunLock,
                tracker = tracker, sessionKey = key
            )
            protect(session.socket)   // must call before session starts sending
            session.start()           // now safe to start the receive relay
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

    // ── Session cleanup ───────────────────────────────────────────────────────

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
            if (expiredUdp.isNotEmpty() || closedTcp.isNotEmpty()) {
                Log.d(TAG, "Cleanup: removed ${expiredUdp.size} UDP + ${closedTcp.size} TCP sessions")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error: ${e.message}")
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

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

    // Public so TcpSession/UdpSession can call protect() via the service reference —
    // but here we inline protect() directly in the dispatchers above.
}
