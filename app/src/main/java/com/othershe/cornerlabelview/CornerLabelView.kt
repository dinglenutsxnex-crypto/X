package com.othershe.cornerlabelview

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

class CornerLabelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    
    private val textView: TextView
    private var bgColor: Int = Color.RED
    private var sideLength: Float = 44f
    
    var text: String
        get() = textView.text.toString()
        set(value) { 
            textView.text = value
            updateBg()
        }
    
    var textColor: Int
        get() = textView.currentTextColor
        set(value) { 
            textView.setTextColor(value)
        }
    
    var textSize: Float
        get() = textView.textSize / resources.displayMetrics.scaledDensity
        set(value) { 
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
        }
    
    var bgColorAttr: Int
        get() = bgColor
        set(value) {
            bgColor = value
            updateBg()
        }
    
    var position: String = "left_top"
    
    var sideLengthAttr: Float
        get() = sideLength
        set(value) {
            sideLength = value
            updateBg()
        }
    
    init {
        textView = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(textView)
        
        updateBg()
        
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, intArrayOf(
                android.R.attr.text,
                android.R.attr.textColor,
                android.R.attr.textSize
            ))
            try {
                text = ta.getString(0) ?: ""
                textColor = ta.getColor(1, Color.WHITE)
                textSize = ta.getDimension(2, 10f)
            } finally {
                ta.recycle()
            }
            
            val a = context.obtainStyledAttributes(attrs, R.styleable.CornerLabelView)
            try {
                bgColor = a.getColor(R.styleable.CornerLabelView_bg_color, Color.RED)
                position = a.getString(R.styleable.CornerLabelView_position) ?: "left_top"
                sideLength = a.getDimension(R.styleable.CornerLabelView_side_length, 44f)
                updateBg()
            } finally {
                a.recycle()
            }
        }
    }
    
    private fun updateBg() {
        val size = sideLength.toInt()
        layoutParams = LayoutParams(size, size)
        
        val drawable = GradientDrawable().apply {
            setColor(bgColor)
            shape = GradientDrawable.RECTANGLE
        }
        background = drawable
        
        rotation = when(position) {
            "left_top" -> -45f
            "right_top" -> 45f
            "left_bottom" -> 45f
            "right_bottom" -> -45f
            else -> -45f
        }
        
        val offset = sideLength / 2
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = when(position) {
            "left_top" -> Gravity.TOP or Gravity.START
            "right_top" -> Gravity.TOP or Gravity.END
            "left_bottom" -> Gravity.BOTTOM or Gravity.START
            "right_bottom" -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.TOP or Gravity.START
        }
        if (position.contains("left")) {
            lp.setMargins(-offset.toInt(), offset.toInt(), 0, 0)
        } else {
            lp.setMargins(0, offset.toInt(), -offset.toInt(), 0)
        }
        textView.layoutParams = lp
    }
}
