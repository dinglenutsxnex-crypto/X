package top.niunaijun.blackboxa.view.debugger

data class ProcessInfo(
    val name: String,
    val packageName: String,
    val pid: Int,
    val processLine: String
)
