package top.niunaijun.blackboxa.view.net

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ConnectionExporter {

    private val TS_FMT   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val ENTRY_TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Export a single connection as a ZIP into the public Downloads folder.
     * All raw bytes from every packet event are written with no cap.
     */
    fun exportSingle(context: Context, rec: ConnectionRecord): File {
        val ts  = TS_FMT.format(Date())
        val dir = downloadsDir(context)
        dir.mkdirs()
        val name = "${rec.displayProto.lowercase()}_${rec.dstPort}_${sanitize(rec.displayHost)}_$ts.zip"
        val zipFile = File(dir, name)

        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
            writeConnection(zos, rec)
        }
        return zipFile
    }

    private fun downloadsDir(context: Context): File {
        // Prefer public Downloads so the user can find the file without root
        val pub = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        return if (pub != null && (pub.exists() || pub.mkdirs())) pub
               else context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                   ?: context.cacheDir
    }

    private fun sanitize(s: String) =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)

    private fun writeConnection(zos: ZipOutputStream, rec: ConnectionRecord) {
        val proto  = rec.displayProto.lowercase()
        val host   = sanitize(rec.displayHost)
        val folder = "${proto}_${rec.dstPort}_${host}"

        val events = rec.events

        // File counter per direction sequence
        var appCounter = 0       // how many outbound sequences we've seen
        var srvCounter = 0       // how many inbound sequences we've seen
        var srvSubIdx  = 0       // continuation counter within current inbound sequence
        var lastDir: Direction? = null

        for (event in events) {
            val raw = event.rawData ?: continue
            if (raw.isEmpty()) continue

            val entryName: String
            when (event.direction) {
                Direction.OUTBOUND -> {
                    appCounter++
                    srvSubIdx = 0
                    entryName = "app_$appCounter"
                }
                Direction.INBOUND -> {
                    if (lastDir == Direction.INBOUND) {
                        srvSubIdx++
                        entryName = "server_${srvCounter}.${srvSubIdx}"
                    } else {
                        srvCounter++
                        srvSubIdx = 0
                        entryName = "server_$srvCounter"
                    }
                }
                else -> continue
            }
            lastDir = event.direction

            // Write the full raw bytes — no cap
            zos.putNextEntry(ZipEntry("$folder/$entryName.bin"))
            zos.write(raw)
            zos.closeEntry()
        }

        // Metadata summary
        zos.putNextEntry(ZipEntry("$folder/meta.txt"))
        val meta = buildString {
            appendLine("Proto:     ${rec.displayProto}")
            appendLine("Host:      ${rec.displayHost}:${rec.dstPort}")
            if (rec.method.isNotBlank()) appendLine("Method:    ${rec.method}")
            if (rec.path.isNotBlank())   appendLine("Path:      ${rec.displayPath}")
            if (rec.responseCode > 0)    appendLine("Response:  ${rec.responseCode}")
            appendLine("Src:       ${rec.srcIp}:${rec.srcPort}")
            appendLine("Dst:       ${rec.dstIp}:${rec.dstPort}")
            appendLine("Sent:      ${rec.bytesSent} B")
            appendLine("Received:  ${rec.bytesReceived} B")
            appendLine("Status:    ${rec.status}")
            appendLine("Started:   ${ENTRY_TS.format(Date(rec.startTime))}")
            appendLine("LastSeen:  ${ENTRY_TS.format(Date(rec.lastSeen))}")
            appendLine()
            appendLine("Events: ${events.size}  (${events.count { it.rawData != null }} with raw data)")
        }
        zos.write(meta.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
}
