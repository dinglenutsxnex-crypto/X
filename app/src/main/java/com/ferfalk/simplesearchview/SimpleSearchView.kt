package com.ferfalk.simplesearchview

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class SimpleSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onQueryTextListener: OnQueryTextListener? = null
    var type: String = "card"

    init {
        if (attrs != null) {
            try {
                val a = context.obtainStyledAttributes(attrs, 
                    intArrayOf(android.R.attr.background))
                try {
                    val bg = a.getDrawable(0)
                    if (bg != null) background = bg
                } finally {
                    a.recycle()
                }
                
                // Try custom attrs
                val styleable = Class.forName("${context.packageName}.R\$styleable")
                val styleableArr = styleable.getField("SimpleSearchView").get(null) as IntArray
                val typedArray = context.obtainStyledAttributes(attrs, styleableArr)
                try {
                    type = typedArray.getString(0) ?: "card"
                } catch (e: Exception) {
                    // Ignore
                } finally {
                    typedArray.recycle()
                }
            } catch (e: Exception) {
                // Ignore on early compilation
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
