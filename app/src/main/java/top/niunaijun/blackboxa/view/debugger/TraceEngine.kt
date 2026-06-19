package top.niunaijun.blackboxa.view.debugger

import android.os.Handler
import android.os.Looper
import android.util.Log

object TraceEngine {
    private const val TAG = "TraceEngine"
    private const val MAX_EVENTS = 10_000

    private val events = ArrayDeque<TraceEvent>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var observer: ((List<TraceEvent>) -> Unit)? = null
    @Volatile var isRunning = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                observer?.invoke(snapshot())
                mainHandler.postDelayed(this, 250)
            }
        }
    }

    fun start(pid: Int) {
        isRunning = true
        events.clear()
        mainHandler.post(updateRunnable)
        Log.i(TAG, "TraceEngine started for PID $pid")
    }

    fun stop() {
        isRunning = false
        mainHandler.removeCallbacks(updateRunnable)
        observer = null
        Log.i(TAG, "TraceEngine stopped")
    }

    fun clear() {
        synchronized(events) { events.clear() }
    }

    fun snapshot(): List<TraceEvent> = synchronized(events) { events.toList() }

    fun addEvent(event: TraceEvent) {
        synchronized(events) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(event)
        }
    }

    // ─── Called from JNI (MethodTracer native layer) ────────────────────────
    // Keep signature in sync with tracer.cpp callback registration.
    @JvmStatic
    fun onNativeEvent(
        typeOrdinal: Int,
        className: String,
        methodName: String,
        thread: String,
        args: String,
        retVal: String,
        depth: Int,
        elapsedMs: Long,
    ) {
        val type = TraceType.values().getOrNull(typeOrdinal) ?: return
        addEvent(TraceEvent(type, className, methodName, thread, args, retVal, depth, elapsedMs))
    }

    // ─── Called from Java layer (MethodTracer hooks) ─────────────────────────
    @JvmStatic
    fun onClassLoad(className: String) {
        if (!isRunning) return
        addEvent(TraceEvent(
            type = TraceType.CLASS_LOAD,
            className = className,
            methodName = "",
            thread = Thread.currentThread().name,
            args = "", retVal = "", depth = 0, elapsedMs = 0
        ))
    }

    @JvmStatic
    fun onDexLoad(path: String) {
        if (!isRunning) return
        addEvent(TraceEvent(
            type = TraceType.DEX_LOAD,
            className = "DexClassLoader",
            methodName = path.substringAfterLast('/'),
            thread = Thread.currentThread().name,
            args = path, retVal = "", depth = 0, elapsedMs = 0
        ))
    }

    @JvmStatic
    fun onJniRegister(className: String, methodName: String) {
        if (!isRunning) return
        addEvent(TraceEvent(
            type = TraceType.JNI_REGISTER,
            className = className,
            methodName = methodName,
            thread = Thread.currentThread().name,
            args = "", retVal = "", depth = 0, elapsedMs = 0
        ))
    }

    @JvmStatic
    fun onBinderCall(descriptor: String, code: Int) {
        if (!isRunning) return
        addEvent(TraceEvent(
            type = TraceType.BINDER_CALL,
            className = descriptor,
            methodName = "transact(code=$code)",
            thread = Thread.currentThread().name,
            args = "", retVal = "", depth = 0, elapsedMs = 0
        ))
    }
}
