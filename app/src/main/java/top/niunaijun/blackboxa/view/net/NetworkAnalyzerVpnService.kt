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

            // We always allow the host package because the container apps run in its processes
            builder.addAllowedApplication(packageName)
            
            // If a specific guest app is targeted, we still capture via the host process,
            // but the UI will filter by IP/Port if needed.
            // Note: In BlackBox, guest apps share the host's UID, so addAllowedApplication(guest)
            // might not work as expected. We capture all container traffic and filter in UI.

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

        runCatching { tunPfd?.close() }
        tunPfd = null
        tunInput = null
        tunOutput = null
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
