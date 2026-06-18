package top.niunaijun.blackboxa.view.net

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PacketEventAdapter : RecyclerView.Adapter<PacketEventAdapter.VH>() {

    private val items = mutableListOf<PacketEvent>()

    fun setEvents(list: List<PacketEvent>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvHeader:  TextView    = view.findViewById(R.id.tv_event_header)
        val tvData:    TextView    = view.findViewById(R.id.tv_event_data)
        val btnCopy:   android.widget.LinearLayout = view.findViewById(R.id.btn_event_copy)
        val btnParse:  android.widget.LinearLayout = view.findViewById(R.id.btn_event_parse)
        val btnToggle: android.widget.LinearLayout = view.findViewById(R.id.btn_event_toggle)
        val btnMore:   TextView    = view.findViewById(R.id.btn_event_more)
        // Inner TextViews for text mutation
        val tvParseLabel:  TextView = btnParse.findViewById(R.id.tv_parse_label)
        val tvToggleLabel: TextView = btnToggle.findViewById(R.id.tv_toggle_label)

        var rawText:    String = ""
        var fullHex:    String = ""    // lazy — populated when "LOAD MORE" tapped
        var parsedText: String = ""
        var showingRaw: Boolean = true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_packet_event, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val ev = items[pos]
        val isOut = ev.direction == Direction.OUTBOUND

        val dirLabel = if (isOut) "SENT" else "RECV"
        val dirColor = if (isOut) 0xFF4FC3F7.toInt() else 0xFF69F0AE.toInt()
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ev.timestamp))

        h.tvHeader.text = "$dirLabel   $ts   ${ev.size}B"
        h.tvHeader.setTextColor(dirColor)
        h.tvHeader.setBackgroundColor(if (isOut) 0x224FC3F7.toInt() else 0x2269F0AE.toInt())

        val raw = ev.rawData
        val hasData = raw != null && raw.isNotEmpty()
        val truncated = hasData && raw!!.size > PacketEvent.UI_DISPLAY_LIMIT

        h.rawText = if (hasData) formatHexDump(raw!!, PacketEvent.UI_DISPLAY_LIMIT) else "(no data captured)"
        h.fullHex = ""
        h.parsedText = ""
        h.showingRaw = true
        h.btnToggle.visibility = View.GONE
        h.btnParse.text = "PARSE"
        h.btnMore.visibility = if (truncated) View.VISIBLE else View.GONE

        h.tvData.text = h.rawText
        h.tvData.setTextColor(if (isOut) 0xBB4FC3F7.toInt() else 0xBB69F0AE.toInt())

        // LOAD MORE — shows full hex dump without the display cap
        h.btnMore.setOnClickListener {
            if (raw != null) {
                if (h.fullHex.isEmpty()) h.fullHex = formatHexDump(raw, raw.size)
                h.rawText = h.fullHex
                if (h.showingRaw) h.tvData.text = h.fullHex
            }
            h.btnMore.visibility = View.GONE
        }

        // COPY — copies whatever is currently displayed
        h.btnCopy.setOnClickListener {
            val txt = if (h.showingRaw) h.rawText else h.parsedText
            val cm = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("packet", txt))
            Toast.makeText(it.context, "Copied", Toast.LENGTH_SHORT).show()
        }

        // PARSE — attempts protobuf decode
        h.btnParse.setOnClickListener {
            if (!hasData) {
                Toast.makeText(it.context, "No data to parse", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runCatching { ProtobufParser.parse(raw!!) }.fold(
                onSuccess = { parsed ->
                    h.parsedText = parsed
                    h.showingRaw = false
                    h.tvData.text = parsed
                    h.btnToggle.visibility = View.VISIBLE
                    h.tvParseLabel.text = "PARSED"
                    h.btnMore.visibility = View.GONE
                    pulseGreen(h.tvData)
                },
                onFailure = { err ->
                    Toast.makeText(it.context, "Parse error: ${err.message}", Toast.LENGTH_LONG).show()
                }
            )
        }

        // TOGGLE — switch between raw hex and parsed protobuf
        h.btnToggle.setOnClickListener {
            h.showingRaw = !h.showingRaw
            if (h.showingRaw) {
                h.tvData.text = h.rawText
                h.tvData.setTextColor(if (isOut) 0xBB4FC3F7.toInt() else 0xBB69F0AE.toInt())
                h.tvToggleLabel.text = "PARSED"
            } else {
                h.tvData.text = h.parsedText
                h.tvData.setTextColor(0xFF69F0AE.toInt())
                h.tvToggleLabel.text = "RAW"
            }
        }
    }

    private fun pulseGreen(view: TextView) {
        val start = view.currentTextColor
        val green = 0xFF69F0AE.toInt()
        ValueAnimator.ofObject(ArgbEvaluator(), start, green, start).apply {
            duration = 600
            addUpdateListener { view.setTextColor(it.animatedValue as Int) }
            start()
        }
    }

    /** Renders a classic hex dump. [limit] controls how many bytes are shown. */
    private fun formatHexDump(data: ByteArray, limit: Int): String {
        val sb = StringBuilder()
        val cap = minOf(data.size, limit)
        var offset = 0
        while (offset < cap) {
            val rowEnd = minOf(offset + 16, cap)
            val row = data.slice(offset until rowEnd)
            sb.append("%04X  ".format(offset))
            for (i in 0 until 16) {
                if (i < row.size) sb.append("%02X ".format(row[i])) else sb.append("   ")
                if (i == 7) sb.append(" ")
            }
            sb.append(" |")
            for (b in row) {
                val c = b.toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append("|\n")
            offset += 16
        }
        return sb.toString().trimEnd()
    }
}
