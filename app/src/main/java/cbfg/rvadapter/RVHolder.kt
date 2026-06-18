package cbfg.rvadapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

abstract class RVHolder<T>(itemView: View) : RecyclerView.ViewHolder(itemView) {
    abstract fun bind(item: T)
    
    open fun setContent(item: T, isSelected: Boolean, payload: Any?) {
        // Default implementation for compatibility
    }

    companion object {
        fun inflate(layoutId: Int, parent: ViewGroup?): View {
            return LayoutInflater.from(parent?.context).inflate(layoutId, parent, false)
        }
    }
}

abstract class RVHolderFactory {
    abstract fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any>

    protected fun inflateLayout(layoutId: Int, parent: ViewGroup?): View {
        return LayoutInflater.from(parent?.context).inflate(layoutId, parent, false)
    }
}
