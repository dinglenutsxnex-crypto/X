package top.niunaijun.blackboxa.view.debugger

import android.graphics.drawable.Drawable

data class ProcessInfo(
    val name: String,
    val packageName: String,
    val pid: Int,
    val processLine: String,
    val appPackage: String = "",
    val icon: Drawable? = null
)
