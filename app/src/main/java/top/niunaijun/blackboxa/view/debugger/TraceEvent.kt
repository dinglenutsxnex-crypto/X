package top.niunaijun.blackboxa.view.debugger

enum class TraceType {
    METHOD_ENTER,
    METHOD_EXIT,
    CONSTRUCTOR,
    CLASS_LOAD,
    DEX_LOAD,
    JNI_REGISTER,
    IL2CPP_CALL,
    BINDER_CALL,
    EXCEPTION,
}

data class TraceEvent(
    val type: TraceType,
    val className: String,
    val methodName: String,
    val thread: String,
    val args: String,
    val retVal: String,
    val depth: Int,
    val elapsedMs: Long,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    val icon: String get() = when (type) {
        TraceType.METHOD_ENTER  -> "→"
        TraceType.METHOD_EXIT   -> "←"
        TraceType.CONSTRUCTOR   -> "NEW"
        TraceType.CLASS_LOAD    -> "LOAD"
        TraceType.DEX_LOAD      -> "DEX"
        TraceType.JNI_REGISTER  -> "JNI"
        TraceType.IL2CPP_CALL   -> "IL→"
        TraceType.BINDER_CALL   -> "IPC"
        TraceType.EXCEPTION     -> "!"
    }

    val iconColor: Int get() = when (type) {
        TraceType.METHOD_ENTER  -> 0xFF4DD0E1.toInt()
        TraceType.METHOD_EXIT   -> 0xFF66BB6A.toInt()
        TraceType.CONSTRUCTOR   -> 0xFFFFD54F.toInt()
        TraceType.CLASS_LOAD    -> 0xFFCE93D8.toInt()
        TraceType.DEX_LOAD      -> 0xFFB39DDB.toInt()
        TraceType.JNI_REGISTER  -> 0xFFFFB74D.toInt()
        TraceType.IL2CPP_CALL   -> 0xFFFF80AB.toInt()
        TraceType.BINDER_CALL   -> 0xFF80CBC4.toInt()
        TraceType.EXCEPTION     -> 0xFFFF5252.toInt()
    }

    val displayText: String get() {
        val shortClass = className.substringAfterLast('.')
        return when (type) {
            TraceType.METHOD_ENTER  -> "$shortClass.$methodName()"
            TraceType.METHOD_EXIT   -> "$shortClass.$methodName"
            TraceType.CONSTRUCTOR   -> "$shortClass()"
            TraceType.CLASS_LOAD    -> className
            TraceType.DEX_LOAD      -> methodName
            TraceType.JNI_REGISTER  -> "$shortClass.$methodName"
            TraceType.IL2CPP_CALL   -> "$className::$methodName"
            TraceType.BINDER_CALL   -> methodName
            TraceType.EXCEPTION     -> "$className: $methodName"
        }
    }
}
