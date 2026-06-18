package com.ferfalk.simplesearchview

import android.content.Context
import android.utils.TypedValue
import android.view.View
import android.widget.FrameLayout

class SimpleSearchView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onQueryTextListener: OnQueryTextListener? = null
    var type: String = "card"

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, intArrayOf(
                android.R.attr.background
            ))
            try {
                val bg = a.getDrawable(0)
                if (bg != null) background = bg
            } finally {
                a.recycle()
            }
        }
    }

    fun setOnQueryTextListener(listener: OnQueryTextListener) {
        onQueryTextListener = listener
    }

    fun setText(text: String) {}
    fun clearText() {}
    fun getText(): String = ""
    fun showSearch(clear: Boolean = false) {}
    fun hideSearch() {}

    abstract class OnQueryTextListener {
        open fun onQueryTextChange(newText: String): Boolean = false
        open fun onQueryTextCleared(): Boolean = false
        open fun onQueryTextSubmit(query: String): Boolean = false
    }
}
