package top.niunaijun.blackboxa.view.debugger

import android.text.SpannableStringBuilder
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R

class TraceAdapter : ListAdapter<TraceEvent, TraceAdapter.VH>(DIFF) {

    var filterPattern: String = ""

    fun submitFiltered(all: List<TraceEvent>) {
        val pattern = filterPattern.trim()
        val filtered = if (pattern.isEmpty()) {
            all
        } else {
            val glob = pattern.replace(".", "\\.").replace("*", ".*")
            val regex = runCatching { Regex(glob, RegexOption.IGNORE_CASE) }.getOrNull()
            if (regex != null) all.filter { regex.containsMatchIn(it.className) }
            else all.filter { it.className.contains(pattern, ignoreCase = true) }
        }
        submitList(filtered)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val indent:   View     = view.findViewById(R.id.trace_indent)
        val icon:     TextView = view.findViewById(R.id.tv_trace_icon)
        val main:     TextView = view.findViewById(R.id.tv_trace_main)
        val elapsed:  TextView = view.findViewById(R.id.tv_trace_elapsed)
        val thread:   TextView = view.findViewById(R.id.tv_trace_thread)
        val args:     TextView = view.findViewById(R.id.tv_trace_args)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trace_event, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val ev = getItem(position)

        // Depth indent: 8dp per level, max 10 levels
        val density = h.itemView.context.resources.displayMetrics.density
        val indentPx = (ev.depth.coerceIn(0, 10) * 8 * density).toInt()
        h.indent.layoutParams = h.indent.layoutParams.also { it.width = indentPx }

        // Icon
        h.icon.text = ev.icon
        h.icon.setTextColor(ev.iconColor)

        // Main label — highlight class name differently from method name
        val displayText = ev.displayText
        val dotIdx = displayText.lastIndexOf('.')
        val ssb = SpannableStringBuilder(displayText)
        if (dotIdx > 0 && dotIdx < displayText.length - 1) {
            ssb.setSpan(ForegroundColorSpan(0xFF888888.toInt()), 0, dotIdx + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(ForegroundColorSpan(ev.iconColor), dotIdx + 1, displayText.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        h.main.text = ssb

        // Elapsed (only on exit / il2cpp events with a duration)
        if (ev.elapsedMs > 0) {
            h.elapsed.text = "+${ev.elapsedMs}ms"
            h.elapsed.visibility = View.VISIBLE
        } else {
            h.elapsed.visibility = View.GONE
        }

        // Thread
        h.thread.text = "[${ev.thread}]"

        // Ret val for exits
        val argsText = buildString {
            if (ev.args.isNotEmpty()) append(ev.args)
            if (ev.retVal.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("↳ ${ev.retVal}")
            }
        }
        if (argsText.isNotEmpty()) {
            h.args.text = argsText
            h.args.visibility = View.VISIBLE
        } else {
            h.args.visibility = View.GONE
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TraceEvent>() {
            override fun areItemsTheSame(a: TraceEvent, b: TraceEvent) =
                a.timestampMs == b.timestampMs && a.className == b.className && a.methodName == b.methodName
            override fun areContentsTheSame(a: TraceEvent, b: TraceEvent) = a == b
        }
    }
}
