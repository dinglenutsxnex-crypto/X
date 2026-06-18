package com.ferfalk.simplesearchview

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
import android.widget.FrameLayout

class SimpleSearchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onQueryTextListener: OnQueryTextListener? = null

    fun setOnQueryTextListener(listener: OnQueryTextListener) {
        onQueryTextListener = listener
    }

    fun setText(text: String) {
        // Stub - search functionality disabled
    }

    fun clearText() {
        // Stub
    }

    fun getText(): String = ""

    abstract class OnQueryTextListener {
        open fun onQueryTextChange(newText: String): Boolean = false
        open fun onQueryTextCleared(): Boolean = false
        open fun onQueryTextSubmit(query: String): Boolean = false
    }
}
