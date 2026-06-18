package cbfg.rvadapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class RVAdapter<T : Any>(
    private val context: Context,
    private val factory: RVHolderFactory
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    private val items = mutableListOf<Any>()
    private var recyclerView: RecyclerView? = null
    private var onItemClick: ((View, Any, Int) -> Unit)? = null
    private var onItemLongClick: ((View, Any, Int) -> Unit)? = null
    private val viewHolderMap = mutableMapOf<Int, RVHolder<out Any>>()

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

    @Suppress("UNCHECKED_CAST")
    fun getItems(): List<T> = items.filterIsInstance<T>()

    fun setItems(newItems: List<*>) {
        items.clear()
        items.addAll(newItems)
        viewHolderMap.clear()
        notifyDataSetChanged()
    }

    fun addItem(item: Any) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun removeItem(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = position

    @Suppress("UNCHECKED_CAST")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val item = items.getOrNull(viewType)
        val holder = if (item != null) {
            factory.createViewHolder(parent, viewType, item)
        } else {
            object : RVHolder<Any>(View(parent.context)) {
                override fun bind(item: Any) {}
            }
        }
        viewHolderMap[viewType] = holder
        return holder
    }

    @Suppress("UNCHECKED_CAST")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        (holder as? RVHolder<Any>)?.let { rvHolder ->
            rvHolder.setContent(item, false, null)
            holder.itemView.setOnClickListener { onItemClick?.invoke(it, item, position) }
            holder.itemView.setOnLongClickListener { 
                onItemLongClick?.invoke(it, item, position)
                true
            }
        }
    }
}
