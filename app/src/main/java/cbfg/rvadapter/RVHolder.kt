package cbfg.rvadapter

import android.view.View
import androidx.recyclerview.widget.RecyclerView

abstract class RVHolder<T>(itemView: View) : RecyclerView.ViewHolder(itemView) {
    abstract fun bind(item: T)
}
