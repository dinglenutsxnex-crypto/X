package top.niunaijun.blackboxa.view.net

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConnectionDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CONN_ID = "conn_id"

        fun start(context: Context, id: Long) {
            context.startActivity(
                Intent(context, ConnectionDetailActivity::class.java)
                    .putExtra(EXTRA_CONN_ID, id)
            )
        }
    }

    private val adapter = PacketEventAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection_detail)

        val id  = intent.getLongExtra(EXTRA_CONN_ID, -1L)
        val rec = NetworkAnalyzerVpnService.tracker.getById(id)
        if (rec == null) {
            Toast.makeText(this, "Connection not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Custom back (no ActionBar in Theme.BlackBox)
        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        // Download this connection as ZIP
        findViewById<ImageView>(R.id.btn_detail_download).setOnClickListener {
            downloadConnection(rec)
        }

        bindHeader(rec)
        bindList(rec)
    }

    private fun bindHeader(rec: ConnectionRecord) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        // Title
        findViewById<TextView>(R.id.tv_detail_title).text =
            "${rec.displayProto}  ${rec.displayHost}:${rec.dstPort}"

        // Summary row
        val tvInfo = findViewById<TextView>(R.id.tv_detail_info)
        val sb = StringBuilder()
        sb.append("${rec.displayProto}  ${rec.displayHost}:${rec.dstPort}")
        if (rec.method.isNotBlank())    sb.append("   ${rec.method} ${rec.displayPath}")
        if (rec.responseCode > 0)       sb.append("   [${rec.responseCode}]")
        sb.append("\n")
        sb.append("UP ${fmtBytes(rec.bytesSent)}  DN ${fmtBytes(rec.bytesReceived)}")
        sb.append("   ${statusLabel(rec.status)}")
        sb.append("   ${fmt.format(Date(rec.startTime))}")
        tvInfo.text = sb.toString()
        tvInfo.setTextColor(statusColor(rec.status))

        // Copy-all button
        findViewById<LinearLayout>(R.id.btn_detail_copy_all).setOnClickListener {
            val events = rec.events
            val allText = events.joinToString("\n\n") { ev ->
                val dir = if (ev.direction == Direction.OUTBOUND) "SENT" else "RECV"
                val hex = ev.rawData?.joinToString(" ") { b -> "%02X".format(b) } ?: "(no data)"
                "[${fmt.format(Date(ev.timestamp))}] $dir ${ev.size}B\n$hex"
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("conn", allText))
            Toast.makeText(this, "All events copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindList(rec: ConnectionRecord) {
        val rv = findViewById<RecyclerView>(R.id.rv_events)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.itemAnimator = null
        adapter.setEvents(rec.events)
    }

    private fun downloadConnection(rec: ConnectionRecord) {
        Toast.makeText(this, "Exporting connection…", Toast.LENGTH_SHORT).show()
        Thread {
            runCatching {
                val file = ConnectionExporter.exportSingle(this, rec)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Saved to Downloads: ${file.name}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure { err ->
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun fmtBytes(b: Long) = when {
        b == 0L -> "0B"
        b < 1024 -> "${b}B"
        b < 1_048_576 -> "%.1fKB".format(b / 1024.0)
        else -> "%.1fMB".format(b / 1_048_576.0)
    }

    private fun statusLabel(s: ConnStatus) = when (s) {
        ConnStatus.ALIVE   -> "LIVE"
        ConnStatus.CLOSING -> "CLOSING"
        ConnStatus.CLOSED  -> "CLOSED"
        ConnStatus.ERROR   -> "ERROR"
    }

    private fun statusColor(s: ConnStatus): Int = when (s) {
        ConnStatus.ALIVE   -> 0xFF69F0AE.toInt()
        ConnStatus.CLOSING -> 0xFFFFD54F.toInt()
        ConnStatus.CLOSED  -> 0xFF546E7A.toInt()
        ConnStatus.ERROR   -> 0xFFEF9A9A.toInt()
    }
}
