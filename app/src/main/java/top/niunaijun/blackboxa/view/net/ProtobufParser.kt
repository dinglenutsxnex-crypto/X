package top.niunaijun.blackboxa.view.net

object ProtobufParser {

    private const val MAX_DEPTH = 5
    private const val MAX_FIELDS = 200

    fun parse(data: ByteArray): String {
        val sb = StringBuilder()
        try {
            parseMessage(data, 0, data.size, sb, depth = 0, fieldCount = IntArray(1))
        } catch (e: Exception) {
            throw IllegalArgumentException("Protobuf parse error: ${e.message}", e)
        }
        return if (sb.isEmpty()) "(empty message)" else sb.toString()
    }

    private fun parseMessage(
        data: ByteArray,
        start: Int,
        end: Int,
        sb: StringBuilder,
        depth: Int,
        fieldCount: IntArray
    ) {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("max nesting depth exceeded")
        val indent = "  ".repeat(depth)
        var pos = start

        while (pos < end) {
            if (fieldCount[0]++ > MAX_FIELDS) throw IllegalArgumentException("too many fields")

            val (tag, tagLen) = readVarint(data, pos)
            pos += tagLen
            val fieldNumber = (tag ushr 3).toInt()
            val wireType    = (tag and 0x7L).toInt()

            when (wireType) {
                0 -> {
                    val (value, vLen) = readVarint(data, pos)
                    pos += vLen
                    sb.append("$indent[$fieldNumber] varint: $value\n")
                }
                1 -> {
                    if (pos + 8 > end) throw IllegalArgumentException("truncated 64-bit field")
                    val value = readFixed64(data, pos)
                    pos += 8
                    sb.append("$indent[$fieldNumber] fixed64: 0x${value.toString(16)} ($value)\n")
                }
                2 -> {
                    val (lenLong, lLen) = readVarint(data, pos)
                    pos += lLen
                    val len = lenLong.toInt()
                    if (len < 0 || pos + len > end) throw IllegalArgumentException("bad length-delimited field len=$len")
                    val chunk = data.copyOfRange(pos, pos + len)
                    pos += len

                    if (looksLikeString(chunk)) {
                        sb.append("$indent[$fieldNumber] string: \"${chunk.toString(Charsets.UTF_8)}\"\n")
                    } else if (depth < MAX_DEPTH && looksLikeMessage(chunk)) {
                        sb.append("$indent[$fieldNumber] message {\n")
                        runCatching {
                            parseMessage(chunk, 0, chunk.size, sb, depth + 1, fieldCount)
                        }.onFailure {
                            sb.append("$indent  <unparseable bytes len=${chunk.size}>\n")
                        }
                        sb.append("$indent}\n")
                    } else {
                        sb.append("$indent[$fieldNumber] bytes(${chunk.size}): ${chunk.toHexSnippet()}\n")
                    }
                }
                5 -> {
                    if (pos + 4 > end) throw IllegalArgumentException("truncated 32-bit field")
                    val value = readFixed32(data, pos)
                    pos += 4
                    sb.append("$indent[$fieldNumber] fixed32: 0x${value.toString(16)} ($value)\n")
                }
                else -> throw IllegalArgumentException("unknown wire type $wireType")
            }
        }
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < data.size) {
            val b = data[i++].toLong() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0L) return result to (i - start)
            shift += 7
            if (shift >= 64) throw IllegalArgumentException("varint overflow")
        }
        throw IllegalArgumentException("truncated varint at $start")
    }

    private fun readFixed64(data: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (i * 8))
        return v
    }

    private fun readFixed32(data: ByteArray, pos: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((data[pos + i].toInt() and 0xFF) shl (i * 8))
        return v
    }

    private fun looksLikeString(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > 4096) return false
        var printable = 0
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E || c == 0x09 || c == 0x0A || c == 0x0D) printable++
        }
        return printable.toFloat() / bytes.size > 0.85f
    }

    private fun looksLikeMessage(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return runCatching {
            val sb = StringBuilder()
            parseMessage(bytes, 0, bytes.size, sb, depth = 0, fieldCount = IntArray(1))
            true
        }.getOrDefault(false)
    }

    private fun ByteArray.toHexSnippet(): String {
        val limit = minOf(size, 32)
        val hex = take(limit).joinToString(" ") { "%02X".format(it) }
        return if (size > limit) "$hex …(+${size - limit}B)" else hex
    }
}
