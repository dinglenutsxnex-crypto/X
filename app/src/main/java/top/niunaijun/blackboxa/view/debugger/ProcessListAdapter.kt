package top.niunaijun.blackboxa.view.debugger

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R

class ProcessListAdapter(
    private val onSelect: (ProcessInfo) -> Unit
) : ListAdapter<ProcessInfo, ProcessListAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ProcessInfo>() {
            override fun areItemsTheSame(a: ProcessInfo, b: ProcessInfo) = a.pid == b.pid
            override fun areContentsTheSame(a: ProcessInfo, b: ProcessInfo) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_process_name)
        val tvPkg: TextView = view.findViewById(R.id.tv_process_pkg)
        val tvPid: TextView = view.findViewById(R.id.tv_process_pid)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_process, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvName.text = item.name
        holder.tvPkg.text = item.packageName
        holder.tvPid.text = "PID: ${item.pid}"
        holder.itemView.setOnClickListener { onSelect(item) }
    }
}
