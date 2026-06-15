package top.niunaijun.blackboxa.view.debugger

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class DebuggerFloatingService : Service() {

    companion object {
        private const val TAG = "DebuggerFloat"
    }

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var isPanelExpanded = false

    private var selectedProcessPkg: String? = null
    private var selectedProcessPid: Int = -1
    private var selectedProcessName: String = ""
    private var filterMode = "ALL"

    private val logBuffer = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var logcatFuture: Future<*>? = null
    private var logcatProcess: Process? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var processAdapter: ProcessListAdapter? = null

    private val params: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 200
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showFloatingView()
    }

    private fun showFloatingView() {
        try {
            val inflater = LayoutInflater.from(this)
            floatView = inflater.inflate(R.layout.view_debugger_float, null)

            setupBubble()
            setupPanel()

            windowManager.addView(floatView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing floating view: ${e.message}")
        }
    }

    private fun setupBubble() {
        val bubble = floatView?.findViewById<ImageView>(R.id.debugger_bubble) ?: return
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try { windowManager.updateViewLayout(floatView, params) } catch (e: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        togglePanel()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupPanel() {
        val panel = floatView?.findViewById<LinearLayout>(R.id.debugger_panel) ?: return
        val btnCollapse = floatView?.findViewById<ImageView>(R.id.btn_collapse) ?: return
        val btnRefresh = floatView?.findViewById<Button>(R.id.btn_refresh_processes) ?: return
        val btnStop = floatView?.findViewById<Button>(R.id.btn_stop_logging) ?: return
        val btnFilterAll = floatView?.findViewById<Button>(R.id.btn_filter_all) ?: return
        val btnFilterError = floatView?.findViewById<Button>(R.id.btn_filter_error) ?: return
        val btnClear = floatView?.findViewById<Button>(R.id.btn_clear_logs) ?: return
        val rvProcesses = floatView?.findViewById<RecyclerView>(R.id.rv_processes) ?: return

        btnCollapse.setOnClickListener { collapsePanel() }

        processAdapter = ProcessListAdapter { processInfo ->
            selectProcess(processInfo)
        }
        rvProcesses.layoutManager = LinearLayoutManager(this)
        rvProcesses.adapter = processAdapter

        btnRefresh.setOnClickListener { loadProcesses() }

        btnStop.setOnClickListener { stopLogging() }

        btnFilterAll.setOnClickListener {
            filterMode = "ALL"
            appendLog("[Debugger] Filter: ALL logs")
        }
        btnFilterError.setOnClickListener {
            filterMode = "ERROR"
            appendLog("[Debugger] Filter: ERROR only")
        }
        btnClear.setOnClickListener {
            logBuffer.clear()
            mainHandler.post {
                floatView?.findViewById<TextView>(R.id.tv_logs)?.text = "Log cleared."
            }
        }

        panel.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    try { windowManager.updateViewLayout(floatView, params) } catch (e: Exception) { }
                    true
                }
                else -> false
            }
        }
    }

    private fun togglePanel() {
        if (isPanelExpanded) {
            collapsePanel()
        } else {
            expandPanel()
        }
    }

    private fun expandPanel() {
        isPanelExpanded = true
        floatView?.findViewById<ImageView>(R.id.debugger_bubble)?.visibility = View.GONE
        floatView?.findViewById<LinearLayout>(R.id.debugger_panel)?.visibility = View.VISIBLE
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        try { windowManager.updateViewLayout(floatView, params) } catch (e: Exception) { }
        loadProcesses()
    }

    private fun collapsePanel() {
        isPanelExpanded = false
        floatView?.findViewById<ImageView>(R.id.debugger_bubble)?.visibility = View.VISIBLE
        floatView?.findViewById<LinearLayout>(R.id.debugger_panel)?.visibility = View.GONE
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { windowManager.updateViewLayout(floatView, params) } catch (e: Exception) { }
    }

    private fun loadProcesses() {
        executor.submit {
            try {
                val processes = getRunningVirtualProcesses()
                mainHandler.post {
                    processAdapter?.submitList(processes)
                    if (processes.isEmpty()) {
                        appendLog("[Debugger] No running processes found in container.\nInstall and launch an app first.")
                    } else {
                        appendLog("[Debugger] Found ${processes.size} running process(es). Tap to attach.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading processes: ${e.message}")
            }
        }
    }

    private fun getRunningVirtualProcesses(): List<ProcessInfo> {
        val result = mutableListOf<ProcessInfo>()
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = am.runningAppProcesses ?: return result
            val hostPkg = packageName

            for (proc in runningProcesses) {
                val procName = proc.processName ?: continue
                if (procName == hostPkg) continue
                if (procName.startsWith("$hostPkg:")) continue
                if (proc.pkgList.isNullOrEmpty()) continue

                for (pkg in proc.pkgList) {
                    if (pkg == hostPkg) continue
                    result.add(
                        ProcessInfo(
                            name = proc.processName.substringAfterLast(":").let {
                                if (it == proc.processName) pkg.substringAfterLast(".") else it
                            },
                            packageName = pkg,
                            pid = proc.pid,
                            processLine = proc.processName
                        )
                    )
                    break
                }
            }

            if (result.isEmpty()) {
                for (proc in runningProcesses) {
                    val procName = proc.processName ?: continue
                    if (!procName.startsWith("$hostPkg:")) continue
                    val slot = procName.substringAfterLast(":")
                    result.add(
                        ProcessInfo(
                            name = "Container Process",
                            packageName = procName,
                            pid = proc.pid,
                            processLine = procName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting running processes: ${e.message}")
        }
        return result
    }

    private fun selectProcess(processInfo: ProcessInfo) {
        selectedProcessPkg = processInfo.packageName
        selectedProcessPid = processInfo.pid
        selectedProcessName = processInfo.name

        mainHandler.post {
            floatView?.findViewById<LinearLayout>(R.id.section_process_select)?.visibility = View.GONE
            floatView?.findViewById<LinearLayout>(R.id.section_logging)?.visibility = View.VISIBLE
            floatView?.findViewById<TextView>(R.id.tv_selected_process)?.text =
                "📦 ${processInfo.packageName} (PID: ${processInfo.pid})"

            logBuffer.clear()
            logBuffer.append("[Debugger] Attached to: ${processInfo.packageName}\n")
            logBuffer.append("[Debugger] PID: ${processInfo.pid}\n")
            logBuffer.append("[Debugger] Process: ${processInfo.processLine}\n")
            logBuffer.append("─".repeat(40) + "\n")
            updateLogView()
        }

        startLogcatCapture(processInfo.pid, processInfo.packageName)
    }

    private fun stopLogging() {
        stopLogcatCapture()
        selectedProcessPkg = null
        selectedProcessPid = -1

        mainHandler.post {
            floatView?.findViewById<LinearLayout>(R.id.section_process_select)?.visibility = View.VISIBLE
            floatView?.findViewById<LinearLayout>(R.id.section_logging)?.visibility = View.GONE
            appendLog("[Debugger] Detached. Select a new process.")
        }
        loadProcesses()
    }

    private fun startLogcatCapture(pid: Int, pkg: String) {
        stopLogcatCapture()
        logcatFuture = executor.submit {
            try {
                val cmd = if (pid > 0) {
                    arrayOf("logcat", "-v", "time", "--pid=$pid")
                } else {
                    arrayOf("logcat", "-v", "time")
                }

                logcatProcess = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(logcatProcess!!.inputStream))
                var line: String?

                appendLog("[Debugger] Logcat stream started for PID $pid\n")

                while (reader.readLine().also { line = it } != null) {
                    if (Thread.currentThread().isInterrupted) break
                    val logLine = line ?: continue
                    if (shouldShowLog(logLine)) {
                        appendLog(formatLogLine(logLine))
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.e(TAG, "Logcat error: ${e.message}")
                    appendLog("[Debugger] Log stream ended: ${e.message}")
                }
            }
        }
    }

    private fun stopLogcatCapture() {
        try {
            logcatFuture?.cancel(true)
            logcatProcess?.destroy()
            logcatProcess = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping logcat: ${e.message}")
        }
    }

    private fun shouldShowLog(line: String): Boolean {
        return when (filterMode) {
            "ERROR" -> line.contains(" E ") || line.contains(" E/") || line.contains("Exception") || line.contains("Error")
            else -> true
        }
    }

    private fun formatLogLine(raw: String): String {
        val colored = when {
            raw.contains(" E ") || raw.contains(" E/") -> "❌ $raw"
            raw.contains(" W ") || raw.contains(" W/") -> "⚠️ $raw"
            raw.contains(" D ") || raw.contains(" D/") -> "🔵 $raw"
            raw.contains(" I ") || raw.contains(" I/") -> "ℹ️ $raw"
            raw.contains("Exception") || raw.contains("at ") -> "💥 $raw"
            else -> raw
        }
        return colored + "\n"
    }

    private fun appendLog(text: String) {
        val timestamp = timeFormat.format(Date())
        val entry = if (!text.startsWith("[Debugger]")) "[$timestamp] $text" else "$text\n"
        logBuffer.append(entry)

        if (logBuffer.length > 80000) {
            val trimmed = logBuffer.toString().takeLast(60000)
            logBuffer.clear()
            logBuffer.append(trimmed)
        }

        mainHandler.post { updateLogView() }
    }

    private fun updateLogView() {
        val tvLogs = floatView?.findViewById<TextView>(R.id.tv_logs) ?: return
        val scrollView = floatView?.findViewById<ScrollView>(R.id.scroll_logs) ?: return
        tvLogs.text = logBuffer.toString()
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogcatCapture()
        executor.shutdownNow()
        try {
            floatView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing view: ${e.message}")
        }
    }
}
