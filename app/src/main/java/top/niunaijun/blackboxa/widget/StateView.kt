package top.niunaijun.blackboxa.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView

class StateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val progressBar: ProgressBar
    private val emptyText: TextView

    init {
        progressBar = ProgressBar(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.GONE
        }

        emptyText = TextView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            text = "Empty"
            textSize = 16f
            setTextColor(0xFF888888.toInt())
            visibility = View.GONE
        }

        addView(progressBar)
        addView(emptyText)
        visibility = View.GONE
    }

    fun showLoading() {
        visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
    }

    fun showEmpty() {
        visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        emptyText.visibility = View.VISIBLE
    }

    fun showContent() {
        visibility = View.GONE
        progressBar.visibility = View.GONE
        emptyText.visibility = View.GONE
    }
}
