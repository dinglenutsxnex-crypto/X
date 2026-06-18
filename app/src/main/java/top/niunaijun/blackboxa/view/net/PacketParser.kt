package top.niunaijun.blackboxa.view.net

/**
 * Stateless utility object for parsing raw IPv4 packets from the TUN interface.
 *
 * Supported: IPv4 / TCP / UDP / TLS-ClientHello (SNI extraction) / HTTP 1.x / WebSocket upgrade
 * Skipped:   IPv6, ICMP, fragmented packets, TCP options beyond header length
 */
object PacketParser {

    // ── IP constants ──────────────────────────────────────────────────────────
    const val PROTO_ICMP = 1
    const val PROTO_TCP  = 6
    const val PROTO_UDP  = 17
    const val IP_HEADER_MIN = 20

    // ── TCP flag bits ─────────────────────────────────────────────────────────
    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    // ── TLS constants ─────────────────────────────────────────────────────────
    private const val TLS_CONTENT_HANDSHAKE: Byte = 0x16
    private const val TLS_HANDSHAKE_CLIENT_HELLO: Byte = 0x01

    // ── IP Header ─────────────────────────────────────────────────────────────

    fun ipVersion(buf: ByteArray) = (buf[0].toInt() ushr 4) and 0x0F
    fun ipHeaderLen(buf: ByteArray) = (buf[0].toInt() and 0x0F) * 4
    fun ipTotalLen(buf: ByteArray) = getUShort(buf, 2)
    fun ipProtocol(buf: ByteArray): Int = buf[9].toInt() and 0xFF
    fun ipSrc(buf: ByteArray): ByteArray = buf.copyOfRange(12, 16)
    fun ipDst(buf: ByteArray): ByteArray = buf.copyOfRange(16, 20)

    fun formatIp(b: ByteArray): String =
        "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"

    fun isIpv4(buf: ByteArray) = buf.size >= IP_HEADER_MIN && ipVersion(buf) == 4

    // ── TCP Header (offset = start of TCP header inside buf) ──────────────────

    fun tcpSrcPort(buf: ByteArray, off: Int) = getUShort(buf, off)
    fun tcpDstPort(buf: ByteArray, off: Int) = getUShort(buf, off + 2)
    fun tcpSeq(buf: ByteArray, off: Int)     = getUInt(buf, off + 4)
    fun tcpAck(buf: ByteArray, off: Int)     = getUInt(buf, off + 8)
    fun tcpHeaderLen(buf: ByteArray, off: Int) = ((buf[off + 12].toInt() and 0xFF) ushr 4) * 4
    fun tcpFlags(buf: ByteArray, off: Int)   = buf[off + 13].toInt() and 0xFF
    fun hasSyn(f: Int) = (f and TCP_SYN) != 0
    fun hasAck(f: Int) = (f and TCP_ACK) != 0
    fun hasFin(f: Int) = (f and TCP_FIN) != 0
    fun hasRst(f: Int) = (f and TCP_RST) != 0
    fun hasPsh(f: Int) = (f and TCP_PSH) != 0

    // ── UDP Header (offset = start of UDP header) ─────────────────────────────

    fun udpSrcPort(buf: ByteArray, off: Int) = getUShort(buf, off)
    fun udpDstPort(buf: ByteArray, off: Int) = getUShort(buf, off + 2)
    const val UDP_HEADER_LEN = 8

    // ── Protocol detection ────────────────────────────────────────────────────

    /**
     * Detect application-layer protocol from destination port and first few bytes of payload.
     * [isTls] is checked first because port 443 might carry non-TLS traffic theoretically.
     */
    fun detectProtocol(dstPort: Int, payload: ByteArray, isTcp: Boolean): Protocol {
        if (!isTcp) {
            return if (dstPort == 53) Protocol.DNS else Protocol.UDP
        }
        if (dstPort == 53) return Protocol.DNS
        if (isTlsClientHello(payload)) return Protocol.HTTPS
        if (dstPort == 443) return Protocol.HTTPS
        if (isHttpRequest(payload)) {
            // Check for WS upgrade in first 512 bytes
            val header = payload.take(512).toByteArray().decodeToStringSafe()
            return if (header.lowercase().contains("upgrade: websocket")) Protocol.WS else Protocol.HTTP
        }
        if (isHttpResponse(payload)) return Protocol.HTTP
        return Protocol.TCP
    }

    /** True if payload looks like a TLS ClientHello. */
    fun isTlsClientHello(payload: ByteArray): Boolean {
        if (payload.size < 6) return false
        return payload[0] == TLS_CONTENT_HANDSHAKE &&
               (payload[1].toInt() and 0xFF) == 0x03 &&   // major version
               payload[5] == TLS_HANDSHAKE_CLIENT_HELLO
    }

    /** True if payload starts with an HTTP method verb. */
    fun isHttpRequest(payload: ByteArray): Boolean {
        if (payload.size < 4) return false
        val s = payload.take(8).toByteArray().decodeToStringSafe()
        return s.startsWith("GET ") || s.startsWith("POST ") || s.startsWith("PUT ") ||
               s.startsWith("DELETE ") || s.startsWith("PATCH ") || s.startsWith("HEAD ") ||
               s.startsWith("OPTIONS ") || s.startsWith("CONNECT ")
    }

    fun isHttpResponse(payload: ByteArray): Boolean {
        if (payload.size < 5) return false
        return payload.take(5).toByteArray().decodeToStringSafe().startsWith("HTTP/")
    }

    // ── TLS SNI extraction ────────────────────────────────────────────────────

    /**
     * Parse a TLS ClientHello to extract the SNI hostname.
     * Returns null if not present or payload is malformed.
     */
    fun extractSni(payload: ByteArray): String? = runCatching {
        if (!isTlsClientHello(payload)) return null
        // TLS record header: 5 bytes
        // Handshake header:  4 bytes (type 1 + length 3)
        // ClientHello body starts at offset 9
        var p = 9
        if (p + 2 > payload.size) return null
        p += 2                                           // skip client_version (2)
        if (p + 32 > payload.size) return null
        p += 32                                          // skip random (32)
        if (p >= payload.size) return null
        p += 1 + (payload[p].toInt() and 0xFF)          // skip session_id
        if (p + 2 > payload.size) return null
        p += 2 + getUShort(payload, p)                  // skip cipher_suites
        if (p >= payload.size) return null
        p += 1 + (payload[p].toInt() and 0xFF)          // skip compression_methods
        if (p + 2 > payload.size) return null
        val extsEnd = p + 2 + getUShort(payload, p)
        p += 2
        while (p + 4 <= extsEnd && p + 4 <= payload.size) {
            val extType = getUShort(payload, p)
            val extLen  = getUShort(payload, p + 2)
            p += 4
            if (extType == 0x0000) {                    // SNI extension (type 0)
                if (p + 5 > payload.size) return null
                // server_name_list_len(2) + name_type(1) + name_len(2) + name
                val nameLen = getUShort(payload, p + 3)
                if (p + 5 + nameLen > payload.size) return null
                return String(payload, p + 5, nameLen, Charsets.UTF_8)
            }
            p += extLen
        }
        null
    }.getOrNull()

    // ── HTTP request/response parsing ─────────────────────────────────────────

    data class HttpRequest(val method: String, val path: String, val host: String, val isWs: Boolean)
    data class HttpResponse(val statusCode: Int, val statusMsg: String)

    fun parseHttpRequest(payload: ByteArray): HttpRequest? = runCatching {
        val text = payload.take(4096).toByteArray().decodeToStringSafe()
        val lines = text.split("\r\n")
        if (lines.isEmpty()) return null
        val parts = lines[0].trim().split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val path = parts.getOrElse(1) { "/" }
        var host = ""
        var isWs = false
        for (line in lines.drop(1)) {
            if (line.isBlank()) break
            val lower = line.lowercase()
            when {
                lower.startsWith("host:") -> host = line.substringAfter(':').trim()
                lower.contains("upgrade: websocket") -> isWs = true
            }
        }
        HttpRequest(method, path, host, isWs)
    }.getOrNull()

    fun parseHttpResponse(payload: ByteArray): HttpResponse? = runCatching {
        val text = payload.take(512).toByteArray().decodeToStringSafe()
        if (!text.startsWith("HTTP/")) return null
        val parts = text.split(" ")
        val code = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val msg = parts.getOrNull(2)?.takeWhile { it != '\r' && it != '\n' } ?: ""
        HttpResponse(code, msg)
    }.getOrNull()

    // ── Byte utilities ────────────────────────────────────────────────────────

    fun getUShort(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    fun getUInt(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong()     and 0xFF) shl 24) or
        ((buf[off + 1].toLong() and 0xFF) shl 16) or
        ((buf[off + 2].toLong() and 0xFF) shl 8)  or
         (buf[off + 3].toLong() and 0xFF)

    private fun ByteArray.decodeToStringSafe(): String =
        runCatching { toString(Charsets.UTF_8) }.getOrElse { toString(Charsets.ISO_8859_1) }
}
