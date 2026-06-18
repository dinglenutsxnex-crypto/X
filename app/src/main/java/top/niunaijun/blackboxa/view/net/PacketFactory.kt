package top.niunaijun.blackboxa.view.net

import java.nio.ByteBuffer

/**
 * Builds raw IPv4 packets (IP + TCP or IP + UDP headers + payload) ready to be
 * written to the TUN file descriptor.
 *
 * All checksum fields are computed per RFC 791 (IP) and RFC 793 (TCP) / RFC 768 (UDP).
 */
object PacketFactory {

    private val idCounter = java.util.concurrent.atomic.AtomicInteger(1)

    // ── Public builders ───────────────────────────────────────────────────────

    fun buildTcp(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        windowSize: Int = 65535
    ): ByteArray {
        require(srcIp.size == 4 && dstIp.size == 4)
        val tcpLen   = 20 + payload.size
        val totalLen = 20 + tcpLen
        val buf      = ByteBuffer.allocate(totalLen)

        // ── IPv4 Header (20 bytes, no options) ────────────────────────────────
        buf.put(0x45.toByte())                                 // version=4, IHL=5
        buf.put(0x00)                                          // DSCP/ECN
        buf.putShort(totalLen.toShort())                       // total length
        buf.putShort((idCounter.getAndIncrement() and 0xFFFF).toShort()) // ID
        buf.putShort(0x40_00.toShort())                        // flags=DF, frag=0
        buf.put(64)                                            // TTL
        buf.put(PacketParser.PROTO_TCP.toByte())               // protocol
        buf.putShort(0)                                        // checksum placeholder
        buf.put(srcIp)
        buf.put(dstIp)
        val ipStart = 0
        putChecksum(buf, ipStart + 10, checksum(buf.array(), ipStart, 20))

        // ── TCP Header (20 bytes) ─────────────────────────────────────────────
        val tcpStart = 20
        buf.putShort(srcPort.toShort())                        // src port
        buf.putShort(dstPort.toShort())                        // dst port
        buf.putInt((seq and 0xFFFFFFFFL).toInt())              // seq number
        buf.putInt((ack and 0xFFFFFFFFL).toInt())              // ack number
        buf.put(0x50.toByte())                                  // data offset=5, reserved=0
        buf.put(flags.toByte())                                 // flags
        buf.putShort(windowSize.toShort())                     // window size
        buf.putShort(0)                                        // checksum placeholder
        buf.putShort(0)                                        // urgent pointer

        // ── Payload ───────────────────────────────────────────────────────────
        buf.put(payload)

        // TCP checksum over pseudo-header + TCP segment
        val tcpCsum = tcpChecksum(srcIp, dstIp, buf.array(), tcpStart, tcpLen)
        putChecksum(buf, tcpStart + 16, tcpCsum)

        return buf.array()
    }

    fun buildUdp(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        require(srcIp.size == 4 && dstIp.size == 4)
        val udpLen   = 8 + payload.size
        val totalLen = 20 + udpLen
        val buf      = ByteBuffer.allocate(totalLen)

        // IPv4
        buf.put(0x45.toByte()); buf.put(0)
        buf.putShort(totalLen.toShort())
        buf.putShort((idCounter.getAndIncrement() and 0xFFFF).toShort())
        buf.putShort(0x40_00.toShort())
        buf.put(64); buf.put(PacketParser.PROTO_UDP.toByte())
        buf.putShort(0)
        buf.put(srcIp); buf.put(dstIp)
        putChecksum(buf, 10, checksum(buf.array(), 0, 20))

        // UDP
        val udpStart = 20
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)   // checksum (optional for UDP; we set 0 = disabled)
        buf.put(payload)

        return buf.array()
    }

    // ── Checksum helpers ──────────────────────────────────────────────────────

    /** One's-complement internet checksum (RFC 1071). */
    private fun checksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    /** TCP checksum over the pseudo-header (srcIp, dstIp, 0, proto=6, tcpLen) + TCP segment. */
    private fun tcpChecksum(srcIp: ByteArray, dstIp: ByteArray,
                            buf: ByteArray, tcpStart: Int, tcpLen: Int): Int {
        val pseudo = ByteArray(12 + tcpLen)
        srcIp.copyInto(pseudo, 0)
        dstIp.copyInto(pseudo, 4)
        pseudo[8] = 0
        pseudo[9] = PacketParser.PROTO_TCP.toByte()
        pseudo[10] = (tcpLen ushr 8).toByte()
        pseudo[11] = tcpLen.toByte()
        buf.copyInto(pseudo, 12, tcpStart, tcpStart + tcpLen)
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun putChecksum(buf: ByteBuffer, offset: Int, value: Int) {
        buf.put(offset,     (value ushr 8).toByte())
        buf.put(offset + 1, (value and 0xFF).toByte())
    }
}
