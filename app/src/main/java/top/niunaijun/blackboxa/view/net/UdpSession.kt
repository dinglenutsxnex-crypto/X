package top.niunaijun.blackboxa.view.net

import android.util.Log
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Manages one UDP relay: tunnelled UDP datagram from TUN → real server,
 * and the response back to TUN.
 *
 * UDP sessions expire after IDLE_TIMEOUT_MS of inactivity.
 */
class UdpSession(
    val srcIp: ByteArray, val srcPort: Int,
    val dstIp: ByteArray, val dstPort: Int,
    private val tunOutput: FileOutputStream,
    private val tunLock: Any,
    private val tracker: ConnectionTracker,
    private val sessionKey: String
) {

    companion object {
        private const val TAG = "UdpSession"
        const val IDLE_TIMEOUT_MS = 30_000L
        private const val MAX_DATAGRAM = 65_507

        private val relay = Executors.newCachedThreadPool { r ->
            Thread(r, "udp-relay").also { it.isDaemon = true }
        }
    }

    @Volatile var lastActivity = System.currentTimeMillis()
    @Volatile var closed = false

    // internal so NetworkAnalyzerVpnService can call protect() on it before start()
    internal val socket = DatagramSocket()

    /** Must be called AFTER VpnService.protect(socket) to start the receive relay. */
    fun start() {
        relay.submit(::receiveLoop)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun send(payload: ByteArray) {
        if (closed) return
        lastActivity = System.currentTimeMillis()
        try {
            val pkt = DatagramPacket(payload, payload.size, InetAddress.getByAddress(dstIp), dstPort)
            socket.send(pkt)
            tracker.get(sessionKey)?.let { rec ->
                rec.bytesSent += payload.size
                rec.lastSeen   = System.currentTimeMillis()
                rec.addEvent(PacketEvent(System.currentTimeMillis(), Direction.OUTBOUND, payload.size,
                    if (dstPort == 53) "DNS query ${payload.size}B" else "UDP →${payload.size}B",
                    rawData = payload.copyOf(minOf(payload.size, PacketEvent.MAX_RAW_PER_EVENT))))
            }
        } catch (e: Exception) {
            Log.w(TAG, "UDP send [$sessionKey]: ${e.message}")
            close()
        }
    }

    fun isExpired() = System.currentTimeMillis() - lastActivity > IDLE_TIMEOUT_MS

    fun close() {
        if (closed) return
        closed = true
        tracker.markClosed(sessionKey, ConnStatus.CLOSED)
        runCatching { socket.close() }
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private fun receiveLoop() {
        val buf = ByteArray(MAX_DATAGRAM)
        socket.soTimeout = IDLE_TIMEOUT_MS.toInt()
        try {
            while (!socket.isClosed && !closed) {
                val incoming = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(incoming)
                } catch (_: java.net.SocketTimeoutException) {
                    if (isExpired()) break else continue
                }

                val data = incoming.data.copyOf(incoming.length)
                lastActivity = System.currentTimeMillis()
                tracker.get(sessionKey)?.let { rec ->
                    rec.bytesReceived += data.size
                    rec.lastSeen = System.currentTimeMillis()
                    rec.addEvent(PacketEvent(System.currentTimeMillis(), Direction.INBOUND, data.size,
                        if (dstPort == 53) "DNS resp ${data.size}B" else "UDP ←${data.size}B",
                        rawData = data.copyOf(minOf(data.size, PacketEvent.MAX_RAW_PER_EVENT))))
                }

                // Build UDP response packet: src=server, dst=client
                val response = PacketFactory.buildUdp(
                    srcIp = dstIp, srcPort = dstPort,
                    dstIp = srcIp, dstPort = srcPort,
                    payload = data
                )
                try {
                    synchronized(tunLock) { tunOutput.write(response) }
                } catch (e: Exception) {
                    Log.w(TAG, "TUN write [$sessionKey]: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "UDP receive [$sessionKey]: ${e.message}")
        } finally {
            close()
        }
    }
}
