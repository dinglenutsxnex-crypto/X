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

    private val items = mutableListOf<T>()
    private var recyclerView: RecyclerView? = null
    private var onItemClick: ((View, T, Int) -> Unit)? = null
    private var onItemLongClick: ((View, T, Int) -> Unit)? = null

    fun bind(recyclerView: RecyclerView): RVAdapter<T> {
        this.recyclerView = recyclerView
        recyclerView.adapter = this
        return this
    }

    fun setItemClickListener(listener: (View, T, Int) -> Unit): RVAdapter<T> {
        onItemClick = listener
        return this
    }

    fun setItemLongClickListener(listener: (View, T, Int) -> Unit): RVAdapter<T> {
        onItemLongClick = listener
        return this
    }

    fun getItems(): List<T> = items

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

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val item = items.getOrNull(viewType)
        return if (item != null) {
            factory.createViewHolder(parent, viewType, item)
        } else {
            object : RecyclerView.ViewHolder(View(parent.context)) {}
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        (holder as? RVHolder<T>)?.setContent(item, false, null)
        holder.itemView.setOnClickListener { onItemClick?.invoke(it, item, position) }
        holder.itemView.setOnLongClickListener { 
            onItemLongClick?.invoke(it, item, position)
            true
        }
    }
}

abstract class RVHolderFactory {
    abstract fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RecyclerView.ViewHolder

    protected fun inflateLayout(layoutId: Int, parent: ViewGroup?): View {
        return LayoutInflater.from(parent?.context).inflate(layoutId, parent, false)
    }
}
