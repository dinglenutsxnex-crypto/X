package cbfg.rvadapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class RVAdapter<T>(
    private val context: Context,
    private val factory: RVHolderFactory
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<T>()
    private var onItemClick: ((View, T, Int) -> Unit)? = null
    private var onItemLongClick: ((View, T, Int) -> Unit)? = null

    fun setItemClickListener(listener: (View, T, Int) -> Unit) {
        onItemClick = listener
    }

    fun setItemLongClickListener(listener: (View, T, Int) -> Unit) {
        onItemLongClick = listener
    }

    @Suppress("UNCHECKED_CAST")
    fun getItems(): List<T> = items.toList()

    fun setItems(newItems: List<T>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

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

    @Suppress("UNCHECKED_CAST")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val item = if (viewType < items.size) items[viewType] else Any()
        return factory.createViewHolder(parent, viewType, item)
    }

    @Suppress("UNCHECKED_CAST")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.setOnClickListener { onItemClick?.invoke(it, item, position) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(it, item, position)
            true
        }
        // Call bind on the holder if it extends RVHolder
        (holder as? RVHolder<T>)?.setContent(item, false, null)
    }
}
