package top.niunaijun.blackboxa.view.net

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.debugger.ProcessInfo

class NetworkProcessAdapter(
    private val onSelect: (ProcessInfo) -> Unit
) : ListAdapter<ProcessInfo, NetworkProcessAdapter.ViewHolder>(DIFF) {

    private var selectedPid: Int = -1

    fun setSelectedPid(pid: Int) {
        selectedPid = pid
        notifyDataSetChanged()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ProcessInfo>() {
            override fun areItemsTheSame(a: ProcessInfo, b: ProcessInfo) = a.pid == b.pid
            override fun areContentsTheSame(a: ProcessInfo, b: ProcessInfo) =
                a.name == b.name && a.packageName == b.packageName
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_app_icon)
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

        val icon: Drawable? = if (item.appPackage.isNotEmpty()) {
            runCatching {
                holder.itemView.context.packageManager.getApplicationIcon(item.appPackage)
            }.getOrNull()
        } else null

        if (icon != null) {
            holder.ivIcon.setImageDrawable(icon)
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_network_white)
        }

        holder.tvName.text = item.name
        holder.tvPkg.text = item.packageName
        holder.tvPid.text = "PID: ${item.pid}"

        if (item.pid == selectedPid) {
            holder.itemView.setBackgroundColor(0x334CAF50.toInt())
            holder.tvName.setTextColor(0xFF4CAF50.toInt())
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.tvName.setTextColor(Color.WHITE)
        }

        holder.itemView.setOnClickListener { onSelect(item) }
    }
}
