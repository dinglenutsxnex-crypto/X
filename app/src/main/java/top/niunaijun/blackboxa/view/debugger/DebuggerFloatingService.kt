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
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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

        private val LIFECYCLE_KEYWORDS = listOf(
            "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
            "onBind", "onUnbind", "onReceive", "onCreateView", "onViewCreated",
            "dispatchTouchEvent", "onClick", "onLongClick",
            "onRequestPermissionsResult", "onActivityResult"
        )
    }

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var isPanelExpanded = false

    private var selectedPid: Int = -1
    private var selectedPkg: String = ""
    private var filterMode = "ALL"

    private val logBuffer = StringBuilder()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Two separate executors: one for process scanning, one for logcat streaming.
    // This prevents logcat from blocking process refresh operations.
    private val scanExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var logcatFuture: Future<*>? = null
    private var logcatProcess: Process? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var processAdapter: ProcessListAdapter? = null

    // FLAG_NOT_FOCUSABLE must NEVER be removed — doing so causes the overlay
    // window to steal system focus and freeze all input on the device.
    // FLAG_NOT_TOUCH_MODAL lets touches outside our window reach the app below.
    private val windowFlags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private val params: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            windowFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 200
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            setupFloatView()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed: ${e.message}")
            showToast("Debugger could not start: ${e.message}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        killLogcat()
        scanExecutor.shutdownNow()
        logExecutor.shutdownNow()
        try { floatView?.let { windowManager.removeView(it) } } catch (_: Exception) { }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // View setup
    // ──────────────────────────────────────────────────────────────────────────

    private fun setupFloatView() {
        // Services run with Theme.DeviceDefault — ?attr/ references from the app theme
        // crash with UnsupportedOperationException. Wrap with the real app theme first.
        val themedCtx = ContextThemeWrapper(this, R.style.Theme_BlackBox)
        floatView = LayoutInflater.from(themedCtx).inflate(R.layout.view_debugger_float, null)
        setupBubble()
        setupPanel()
        windowManager.addView(floatView, params)
    }

    private fun setupBubble() {
        // Bubble is now a FrameLayout (not ImageView)
        val bubble = floatView?.findViewById<View>(R.id.debugger_bubble) ?: return
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var dragging = false

        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        dragging = true
                        params.x = startX + dx; params.y = startY + dy
                        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) togglePanel(); true }
                else -> false
            }
        }
    }

    private fun setupPanel() {
        // All controls are now TextView — no Button or ImageView in the new layout.
        floatView?.findViewById<TextView>(R.id.btn_collapse)
            ?.setOnClickListener { collapsePanel() }

        floatView?.findViewById<TextView>(R.id.btn_refresh_processes)
            ?.setOnClickListener { loadProcesses() }

        floatView?.findViewById<TextView>(R.id.btn_stop_logging)
            ?.setOnClickListener { stopLogging() }

        floatView?.findViewById<TextView>(R.id.btn_filter_all)?.setOnClickListener {
            filterMode = "ALL"
            updateFilterLabel("▶  ALL LOGS")
            highlightFilter("ALL")
        }
        floatView?.findViewById<TextView>(R.id.btn_filter_error)?.setOnClickListener {
            filterMode = "ERROR"
            updateFilterLabel("▶  ERRORS ONLY")
            highlightFilter("ERROR")
        }
        floatView?.findViewById<TextView>(R.id.btn_filter_trace)?.setOnClickListener {
            filterMode = "CALLS"
            updateFilterLabel("▶  FUNCTION CALLS")
            highlightFilter("CALLS")
        }
        floatView?.findViewById<TextView>(R.id.btn_clear_logs)?.setOnClickListener {
            logBuffer.clear()
            floatView?.findViewById<TextView>(R.id.tv_logs)?.text = "Log cleared."
        }

        val rv = floatView?.findViewById<RecyclerView>(R.id.rv_processes) ?: return
        processAdapter = ProcessListAdapter { info -> selectProcess(info) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = processAdapter
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Panel show / hide
    // ──────────────────────────────────────────────────────────────────────────

    private fun togglePanel() { if (isPanelExpanded) collapsePanel() else expandPanel() }

    private fun expandPanel() {
        isPanelExpanded = true
        floatView?.findViewById<View>(R.id.debugger_bubble)?.visibility = View.GONE
        floatView?.findViewById<LinearLayout>(R.id.debugger_panel)?.visibility = View.VISIBLE
        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
        loadProcesses()
    }

    private fun collapsePanel() {
        isPanelExpanded = false
        floatView?.findViewById<View>(R.id.debugger_bubble)?.visibility = View.VISIBLE
        floatView?.findViewById<LinearLayout>(R.id.debugger_panel)?.visibility = View.GONE
        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
    }

    /** Update the active filter button highlight — active gets bg_filter_active, others clear. */
    private fun highlightFilter(active: String) {
        mainHandler.post {
            val activeDrawable = resources.getDrawable(R.drawable.bg_filter_active, null)
            mapOf(
                "ALL"   to R.id.btn_filter_all,
                "ERROR" to R.id.btn_filter_error,
                "CALLS" to R.id.btn_filter_trace
            ).forEach { (key, id) ->
                floatView?.findViewById<TextView>(id)?.background =
                    if (key == active) activeDrawable else null
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Process scanning  — NO BlackBoxCore IPC, NO /proc reads
    // ──────────────────────────────────────────────────────────────────────────

    private fun loadProcesses() {
        scanExecutor.submit {
            val processes = try {
                doScanProcesses()
            } catch (e: Exception) {
                Log.e(TAG, "scanProcesses error: ${e.message}")
                showToast("Process scan failed: ${e.message}")
                emptyList()
            }

            mainHandler.post {
                processAdapter?.submitList(processes)
                if (processes.isEmpty()) {
                    appendLog("[Debugger] No virtual app processes found.")
                    appendLog("[Debugger] Launch an app inside the container, then tap REFRESH.")
                } else {
                    appendLog("[Debugger] ${processes.size} process(es) found — tap one to attach.")
                }
            }
        }
    }

    /**
     * Scan only via ActivityManager.getRunningAppProcesses() — no IPC to any
     * other service, no /proc reads.  Safe to call from the background executor.
     */
    private fun doScanProcesses(): List<ProcessInfo> {
        val hostPkg = packageName
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.runningAppProcesses ?: return emptyList()

        val result = mutableListOf<ProcessInfo>()

        for (proc in running) {
            val name = proc.processName ?: continue
            // Skip the main host process — we only want virtual app slots
            if (name == hostPkg) continue

            // BlackBox slot processes are named  hostPkg:p0, hostPkg:p1, …
            val slotSuffix = if (name.startsWith("$hostPkg:"))
                name.substringAfterLast(':') else null

            result.add(
                ProcessInfo(
                    name        = slotSuffix ?: name.substringAfterLast('.'),
                    packageName = name,
                    pid         = proc.pid,
                    processLine = name
                )
            )
        }

        return result.sortedByDescending { it.pid }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Attach / detach
    // ──────────────────────────────────────────────────────────────────────────

    private fun selectProcess(info: ProcessInfo) {
        selectedPid = info.pid
        selectedPkg = info.packageName

        mainHandler.post {
            floatView?.findViewById<LinearLayout>(R.id.section_process_select)?.visibility = View.GONE
            floatView?.findViewById<LinearLayout>(R.id.section_logging)?.visibility = View.VISIBLE
            floatView?.findViewById<TextView>(R.id.tv_selected_process)?.text =
                "PKG: ${info.name}   PID: ${info.pid}"

            logBuffer.clear()
            logBuffer.append("[Debugger] ══════════════════════════\n")
            logBuffer.append("[Debugger] Attached: ${info.packageName}\n")
            logBuffer.append("[Debugger] PID: ${info.pid}\n")
            logBuffer.append("[Debugger] ══════════════════════════\n")
            updateLogView()
        }

        startLogcat(info.pid)
    }

    private fun stopLogging() {
        killLogcat()
        selectedPid = -1
        selectedPkg = ""
        mainHandler.post {
            floatView?.findViewById<LinearLayout>(R.id.section_process_select)?.visibility = View.VISIBLE
            floatView?.findViewById<LinearLayout>(R.id.section_logging)?.visibility = View.GONE
        }
        appendLog("[Debugger] Detached. Select a process to re-attach.")
        loadProcesses()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Logcat capture
    // ──────────────────────────────────────────────────────────────────────────

    private fun startLogcat(pid: Int) {
        killLogcat()
        logcatFuture = logExecutor.submit {
            try {
                val cmd = arrayOf("logcat", "-v", "threadtime", "--pid=$pid")
                logcatProcess = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(logcatProcess!!.inputStream))
                appendLog("[Debugger] Logcat stream started (PID $pid)\n")

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (Thread.currentThread().isInterrupted) break
                    val l = line ?: continue
                    if (shouldShow(l)) appendLog(format(l))
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    appendLog("[Debugger] Stream error: ${e.message}")
                    showToast("Logcat error: ${e.message}")
                }
            }
        }
    }

    private fun killLogcat() {
        try { logcatFuture?.cancel(true) } catch (_: Exception) { }
        try { logcatProcess?.destroy(); logcatProcess = null } catch (_: Exception) { }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Filtering & formatting
    // ──────────────────────────────────────────────────────────────────────────

    private fun shouldShow(line: String) = when (filterMode) {
        "ERROR" -> line.contains(" E ") || line.contains(" E/") ||
                   line.contains("Exception") || line.contains("FATAL")
        "CALLS" -> line.trimStart().startsWith("at ") ||
                   LIFECYCLE_KEYWORDS.any { line.contains(it) }
        else    -> true
    }

    private fun format(raw: String): String {
        if (filterMode == "CALLS") {
            val t = raw.trim()
            return when {
                t.startsWith("at ") -> {
                    val method = t.removePrefix("at ").substringBefore("(")
                    "  > ${method.substringAfterLast('.')}  [${method.substringBeforeLast('.')}]\n"
                }
                else -> {
                    val kw = LIFECYCLE_KEYWORDS.firstOrNull { raw.contains(it) } ?: ""
                    "$kw  |  $raw\n"
                }
            }
        }
        return when {
            raw.contains(" E ") || raw.contains(" E/") -> "E: $raw\n"
            raw.contains(" W ") || raw.contains(" W/") -> "W: $raw\n"
            raw.contains(" D ") || raw.contains(" D/") -> "D: $raw\n"
            raw.contains(" I ") || raw.contains(" I/") -> "I: $raw\n"
            raw.trimStart().startsWith("at ")          -> "!: $raw\n"
            else                                        -> "$raw\n"
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Log view helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun appendLog(text: String) {
        val ts = timeFormat.format(Date())
        logBuffer.append(if (text.startsWith("[Debugger]")) "$text\n" else "[$ts] $text")
        if (logBuffer.length > 100_000) {
            val trimmed = logBuffer.toString().takeLast(70_000)
            logBuffer.clear()
            logBuffer.append("...[trimmed]...\n$trimmed")
        }
        mainHandler.post { updateLogView() }
    }

    private fun updateLogView() {
        val tv = floatView?.findViewById<TextView>(R.id.tv_logs) ?: return
        val sv = floatView?.findViewById<ScrollView>(R.id.scroll_logs) ?: return
        tv.text = logBuffer.toString()
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateFilterLabel(label: String) {
        mainHandler.post {
            floatView?.findViewById<TextView>(R.id.tv_filter_label)?.text = label
        }
    }

    private fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
