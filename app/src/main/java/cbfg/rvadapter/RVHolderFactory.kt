package cbfg.rvadapter

import android.view.View
import android.view.ViewGroup

abstract class RVHolderFactory {
    abstract fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any>
}
