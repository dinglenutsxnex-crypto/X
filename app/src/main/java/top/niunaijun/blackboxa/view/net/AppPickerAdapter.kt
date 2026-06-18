package top.niunaijun.blackboxa.view.net

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

class AppPickerAdapter(
    private val onPick: (AppEntry?) -> Unit  // null = "Monitor ALL"
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ALL = 0
        private const val TYPE_APP = 1
    }

    private val apps = mutableListOf<AppEntry>()

    fun setApps(list: List<AppEntry>) {
        apps.clear()
        apps.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount() = apps.size + 1   // +1 for "ALL" row
    override fun getItemViewType(pos: Int) = if (pos == 0) TYPE_ALL else TYPE_APP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ALL) {
            AllVH(inflater.inflate(R.layout.item_app_picker_all, parent, false))
        } else {
            AppVH(inflater.inflate(R.layout.item_app_picker, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        if (holder is AllVH) {
            holder.itemView.setOnClickListener { onPick(null) }
        } else if (holder is AppVH) {
            val app = apps[pos - 1]
            holder.tvLabel.text = app.label
            holder.tvPkg.text   = app.packageName
            holder.ivIcon.setImageDrawable(app.icon)
            holder.itemView.setOnClickListener { onPick(app) }
        }
    }

    inner class AllVH(view: View) : RecyclerView.ViewHolder(view)
    inner class AppVH(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon:  ImageView = view.findViewById(R.id.iv_app_icon)
        val tvLabel: TextView  = view.findViewById(R.id.tv_app_label)
        val tvPkg:   TextView  = view.findViewById(R.id.tv_app_pkg)
    }

    companion object {
        fun loadInstalledApps(pm: PackageManager): List<AppEntry> {
            return try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .mapNotNull { ai ->
                        runCatching {
                            AppEntry(
                                packageName = ai.packageName,
                                label       = pm.getApplicationLabel(ai).toString(),
                                icon        = pm.getApplicationIcon(ai.packageName)
                            )
                        }.getOrNull()
                    }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
