package cbfg.rvadapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class RVAdapter<T>(
    private val context: Context,
    private val baseAdapter: RecyclerView.Adapter<*>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    private val items = mutableListOf<Any>()
    private var recyclerView: RecyclerView? = null
    private var onItemClick: ((View, Any, Int) -> Unit)? = null
    private var onItemLongClick: ((View, Any, Int) -> Unit)? = null

    fun bind(recyclerView: RecyclerView): RVAdapter<T> {
        this.recyclerView = recyclerView
        recyclerView.adapter = this
        return this
    }

    fun setItemClickListener(listener: (View, Any, Int) -> Unit): RVAdapter<T> {
        onItemClick = listener
        return this
    }

    fun setItemLongClickListener(listener: (View, Any, Int) -> Unit): RVAdapter<T> {
        onItemLongClick = listener
        return this
    }

    fun getItems(): List<T> = items.filterIsInstance<T>()

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

    fun notifyItemMoved(fromPosition: Int, toPosition: Int) {
        if (fromPosition in items.indices && toPosition in items.indices) {
            val item = items.removeAt(fromPosition)
            items.add(toPosition, item)
            notifyItemMoved(fromPosition, toPosition)
        }
    }

    override fun getItemCount(): Int = items.size

    @Suppress("UNCHECKED_CAST")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(context).inflate(0, parent, false)
        return object : RecyclerView.ViewHolder(view) {}
    }

    @Suppress("UNCHECKED_CAST")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.setOnClickListener { onItemClick?.invoke(it, item, position) }
        holder.itemView.setOnLongClickListener { 
            onItemLongClick?.invoke(it, item, position)
            true
        }
    }
}
