package top.niunaijun.blackboxa.view.debugger

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R

class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private var items: List<CharSequence> = emptyList()

    fun updateLogs(newLogs: List<CharSequence>) {
        items = newLogs
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLog: TextView = view.findViewById(R.id.tv_log_line)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.tvLog.text = items[position]
    }

    override fun getItemCount() = items.size
}
