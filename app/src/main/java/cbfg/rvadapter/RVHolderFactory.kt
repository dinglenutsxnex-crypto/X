package cbfg.rvadapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

abstract class RVHolderFactory {
    abstract fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any>

    protected fun inflateLayout(layoutId: Int, parent: ViewGroup?): View {
        return LayoutInflater.from(parent?.context).inflate(layoutId, parent, false)
    }
}
