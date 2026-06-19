package top.niunaijun.blackboxa.view.net

import android.util.Log
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Random
import java.util.concurrent.Executors

/**
 * Manages one TCP connection: client (TUN side) ↔ real server.
 *
 * Thread safety:
 *  - handlePacket() is called from the VPN read-thread.
 *  - startServerRelay() runs a separate daemon thread reading from the real server.
 *  - tunOutput writes are synchronized on [tunLock].
 */
class TcpSession(
    val srcIp: ByteArray, val srcPort: Int,
    val dstIp: ByteArray, val dstPort: Int,
    private val tunOutput: FileOutputStream,
    private val tunLock: Any,
    private val tracker: ConnectionTracker,
    private val sessionKey: String
) {

    companion object {
        private const val TAG = "TcpSession"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS    = 30_000
        private const val SERVER_BUF_SIZE    = 16_384

        private val relay = Executors.newCachedThreadPool { r ->
            Thread(r, "tcp-relay").also { it.isDaemon = true }
        }
    }

    enum class State { INIT, SYN_RECEIVED, ESTABLISHED, CLOSING, CLOSED }

    @Volatile var state = State.INIT

    // Our ISN (sequence numbers from server→client perspective)
    private var serverSeq = (Random().nextLong() and 0x7FFF_FFFFL) + 1L
    // Next expected sequence from client
    private var nextClientSeq = 0L

    // internal so NetworkAnalyzerVpnService can call protect() on it before connecting
    internal val socket = Socket()

    // ── Public API called from VPN thread ─────────────────────────────────────

    /**
     * Called when a packet arrives for this session from the TUN device.
     * @param data raw IP packet buffer
     * @param n    valid bytes in data
     */
    fun handlePacket(data: ByteArray, n: Int) {
        val ipHdr  = PacketParser.ipHeaderLen(data)
        val tcpOff = ipHdr
        if (tcpOff + 20 > n) return

        val flags  = PacketParser.tcpFlags(data, tcpOff)
        val seq    = PacketParser.tcpSeq(data, tcpOff)
        val tcpHdr = PacketParser.tcpHeaderLen(data, tcpOff)
        val payStart = tcpOff + tcpHdr
        val payLen = n - payStart
        val payload = if (payLen > 0) data.copyOfRange(payStart, n) else ByteArray(0)

        when {
            PacketParser.hasRst(flags) -> handleRst()
            PacketParser.hasSyn(flags) && !PacketParser.hasAck(flags) -> handleSyn(seq, payload)
            PacketParser.hasFin(flags) -> handleFin(seq)
            payload.isNotEmpty() && state == State.ESTABLISHED -> handleData(payload, seq)
            // Plain ACK with no data: update tracking but no action needed
        }
    }

    // ── Packet handling ───────────────────────────────────────────────────────

    private fun handleSyn(clientIsn: Long, payload: ByteArray) {
        nextClientSeq = clientIsn + 1
        state = State.SYN_RECEIVED

        // Early SYN-ACK: Send back to client immediately so it doesn't timeout
        sendToTun(PacketParser.TCP_SYN or PacketParser.TCP_ACK, serverSeq, nextClientSeq)
        serverSeq++

        // Connect to real server on a relay thread (blocking I/O)
        relay.submit {
            try {
                socket.soTimeout = READ_TIMEOUT_MS
                socket.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), CONNECT_TIMEOUT_MS)
                state = State.ESTABLISHED
                // Start reading from real server and relaying to TUN
                startServerRelay()
            } catch (e: Exception) {
                Log.w(TAG, "SYN connect failed [$sessionKey]: ${e.message}")
                sendRst()
                tracker.markClosed(sessionKey, ConnStatus.ERROR)
                state = State.CLOSED
            }
        }

        // Inspect payload snuck in with SYN (SYN+data is rare but valid in TFO)
        if (payload.isNotEmpty()) inspectPayload(payload, outbound = true)
    }

    private fun handleData(payload: ByteArray, clientSeq: Long) {
        nextClientSeq = clientSeq + payload.size
        // Forward to real server
        try {
            if (clientSeq != nextClientSeq - payload.size) {
                // Out of order or retransmission - ignore for now to keep it simple
                return
            }
            socket.getOutputStream().write(payload)
            tracker.get(sessionKey)?.let { rec ->
                rec.bytesSent += payload.size
                rec.lastSeen   = System.currentTimeMillis()
                rec.addEvent(PacketEvent(System.currentTimeMillis(), Direction.OUTBOUND, payload.size, "→ ${payload.size}B",
                    rawData = payload.copyOf(minOf(payload.size, PacketEvent.MAX_RAW_PER_EVENT))))
            }
            inspectPayload(payload, outbound = true)
        } catch (e: Exception) {
            Log.w(TAG, "Write to server failed [$sessionKey]: ${e.message}")
            closeSession(ConnStatus.ERROR)
            return
        }
        // Send ACK to client
        sendToTun(PacketParser.TCP_ACK, serverSeq, nextClientSeq)
    }

    private fun handleFin(clientSeq: Long) {
        nextClientSeq = clientSeq + 1
        state = State.CLOSING
        sendToTun(PacketParser.TCP_FIN or PacketParser.TCP_ACK, serverSeq, nextClientSeq)
        serverSeq++
        tracker.markClosed(sessionKey, ConnStatus.CLOSING)
        try { socket.close() } catch (_: Exception) { }
    }

    private fun handleRst() {
        state = State.CLOSED
        tracker.markClosed(sessionKey, ConnStatus.CLOSED)
        try { socket.close() } catch (_: Exception) { }
    }

    // ── Server → TUN relay thread ─────────────────────────────────────────────

    private fun startServerRelay() {
        relay.submit {
            val buf = ByteArray(SERVER_BUF_SIZE)
            try {
                val inp = socket.getInputStream()
                while (!socket.isClosed && state != State.CLOSED) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    val data = buf.copyOf(n)

                    // Inspect inbound data
                    inspectPayload(data, outbound = false)
                    tracker.get(sessionKey)?.let { rec ->
                        rec.bytesReceived += n
                        rec.lastSeen = System.currentTimeMillis()
                        rec.addEvent(PacketEvent(System.currentTimeMillis(), Direction.INBOUND, n, "← ${n}B",
                        rawData = data.copyOf(minOf(n, PacketEvent.MAX_RAW_PER_EVENT))))
                    }

                    // Build TCP packet: src=server, dst=client
                    val pkt = PacketFactory.buildTcp(
                        srcIp = dstIp, srcPort = dstPort,
                        dstIp = srcIp, dstPort = srcPort,
                        seq = serverSeq, ack = nextClientSeq,
                        flags = PacketParser.TCP_ACK or PacketParser.TCP_PSH,
                        payload = data
                    )
                    writeToTun(pkt)
                    serverSeq += n
                }
            } catch (e: Exception) {
                if (state != State.CLOSED) Log.w(TAG, "Server relay [$sessionKey]: ${e.message}")
            } finally {
                // Server closed: send FIN-ACK to client
                if (state == State.ESTABLISHED || state == State.CLOSING) {
                    runCatching {
                        val fin = PacketFactory.buildTcp(
                            srcIp = dstIp, srcPort = dstPort,
                            dstIp = srcIp, dstPort = srcPort,
                            seq = serverSeq, ack = nextClientSeq,
                            flags = PacketParser.TCP_FIN or PacketParser.TCP_ACK
                        )
                        writeToTun(fin)
                        serverSeq++
                    }
                    tracker.markClosed(sessionKey, ConnStatus.CLOSED)
                }
                state = State.CLOSED
                runCatching { socket.close() }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun sendToTun(flags: Int, seq: Long, ack: Long, payload: ByteArray = ByteArray(0)) {
        val pkt = PacketFactory.buildTcp(
            srcIp = dstIp, srcPort = dstPort,
            dstIp = srcIp, dstPort = srcPort,
            seq = seq, ack = ack, flags = flags, payload = payload
        )
        writeToTun(pkt)
    }

    private fun sendRst() = runCatching {
        sendToTun(PacketParser.TCP_RST or PacketParser.TCP_ACK, serverSeq, nextClientSeq)
    }

    private fun writeToTun(pkt: ByteArray) {
        try {
            synchronized(tunLock) { tunOutput.write(pkt) }
        } catch (e: Exception) {
            Log.w(TAG, "TUN write error [$sessionKey]: ${e.message}")
        }
    }

    private fun closeSession(status: ConnStatus) {
        state = State.CLOSED
        tracker.markClosed(sessionKey, status)
        runCatching { socket.close() }
    }

    /**
     * Inspect application-layer payload to enrich [ConnectionRecord].
     * Updates protocol, host, path, method, responseCode based on content.
     */
    private fun inspectPayload(payload: ByteArray, outbound: Boolean) {
        val rec = tracker.get(sessionKey) ?: return
        runCatching {
            if (outbound) {
                // Try HTTP request
                PacketParser.parseHttpRequest(payload)?.let { req ->
                    if (rec.method.isEmpty()) rec.method = req.method
                    if (rec.host.isBlank() || rec.host == PacketParser.formatIp(dstIp)) {
                        if (req.host.isNotBlank()) rec.host = req.host
                    }
                    if (rec.path.isEmpty()) rec.path = if (req.isWs) "WS:${req.path}" else req.path
                }
                // Try TLS SNI
                PacketParser.extractSni(payload)?.takeIf {
                    rec.protocol == Protocol.HTTPS || rec.protocol == Protocol.WSS
                }?.let { sni ->
                    if (rec.host.isBlank() || rec.host == PacketParser.formatIp(dstIp)) rec.host = sni
                }
            } else {
                // Try HTTP response
                PacketParser.parseHttpResponse(payload)?.let { resp ->
                    if (rec.responseCode == 0) rec.responseCode = resp.statusCode
                }
            }
        }
    }

    fun destroy() {
        state = State.CLOSED
        runCatching { socket.close() }
    }
}
