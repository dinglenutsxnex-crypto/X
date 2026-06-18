package cbfg.rvadapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

abstract class RVAdapter<T : Any>(private val factory: RVHolderFactory) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val items = mutableListOf<T>()

    abstract fun onCreateViewHolderAdapter(parent: ViewGroup?, viewType: Int): RecyclerView.ViewHolder

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return onCreateViewHolderAdapter(parent, viewType)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.setOnClickListener {
            onItemClick?.invoke(it, item, position)
        }
        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(it, item, position)
            true
        }
    }

    var onItemClick: ((View, T, Int) -> Unit)? = null
    var onItemLongClick: ((View, T, Int) -> Unit)? = null

    fun addItem(item: T) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun removeItem(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun replaceAt(position: Int, item: T) {
        if (position in items.indices) {
            items[position] = item
            notifyItemChanged(position)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = position
}
