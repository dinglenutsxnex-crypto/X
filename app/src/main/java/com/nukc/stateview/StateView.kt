package com.github.nukc.stateview

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

class StateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    
    private var loadingView: View? = null
    private var emptyView: View? = null
    private var contentView: View? = null

    fun showLoading() {
        visibility = View.VISIBLE
        loadingView?.visibility = View.VISIBLE
        emptyView?.visibility = View.GONE
        contentView?.visibility = View.GONE
    }

    fun showEmpty() {
        visibility = View.VISIBLE
        loadingView?.visibility = View.GONE
        emptyView?.visibility = View.VISIBLE
        contentView?.visibility = View.GONE
    }

    fun showContent() {
        visibility = View.VISIBLE
        loadingView?.visibility = View.GONE
        emptyView?.visibility = View.GONE
        contentView?.visibility = View.VISIBLE
    }

    fun setLoadingView(view: View) {
        loadingView = view
    }

    fun setEmptyView(view: View) {
        emptyView = view
    }

    fun setContentView(view: View) {
        contentView = view
    }
}
