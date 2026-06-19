package top.niunaijun.blackboxa.view.debugger

import android.app.ActivityManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R
import java.io.BufferedReader
import java.io.File
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
        @Volatile var isRunning: Boolean = false

        private val LIFECYCLE_KEYWORDS = listOf(
            "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
            "onBind", "onUnbind", "onReceive", "onCreateView", "onViewCreated",
            "dispatchTouchEvent", "onClick", "onLongClick",
            "onRequestPermissionsResult", "onActivityResult"
        )
    }

    // ── Log state ────────────────────────────────────────────────────────────
    private sealed class LogEntry {
        class Sys(val text: String) : LogEntry()
        class Raw(val raw: String) : LogEntry()
    }
    private val logEntries = ArrayDeque<LogEntry>()
    private val MAX_LOG_ENTRIES = 5000
    private val displayedLogs = mutableListOf<CharSequence>()
    private var logAdapter: LogAdapter? = null

    private val COL_SYS  = 0xFF4DD0E1.toInt()
    private val COL_TIME = 0xFF66BB6A.toInt()
    private val COL_ERR  = 0xFFFF7043.toInt()
    private val COL_WARN = 0xFFFFD54F.toInt()
    private val COL_INFO = 0xFFFFFFFF.toInt()
    private val COL_DBG  = 0xFFB0BEC5.toInt()
    private val COL_CALL = 0xFF42A5F5.toInt()

    // ── Trace state ──────────────────────────────────────────────────────────
    private var traceAdapter: TraceAdapter? = null

    // ── Window state ─────────────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var isPanelExpanded = false
    private var activeTab = "LOGS"
    private var bubbleX = 16
    private var bubbleY = 200

    // ── Process state ────────────────────────────────────────────────────────
    private var selectedPid: Int = -1
    private var selectedPkg: String = ""
    private var filterMode = "ALL"
    private var processAdapter: ProcessListAdapter? = null

    // ── Threading ────────────────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var logUpdatePending = false
    private val LOG_UPDATE_MS = 200L
    private val scanExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var logcatFuture: Future<*>? = null
    private var logcatProcess: Process? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // ── Window LayoutParams (mutated on expand/collapse) ─────────────────────
    private val windowFlagsCollapsed =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private val windowFlagsExpanded =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private val params: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            windowFlagsCollapsed,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Service lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            setupFloatView()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed: ${e.message}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
        killLogcat()
        TraceEngine.stop()
        scanExecutor.shutdownNow()
        logExecutor.shutdownNow()
        try { floatView?.let { windowManager.removeView(it) } } catch (_: Exception) { }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // View setup
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupFloatView() {
        val ctx = ContextThemeWrapper(this, R.style.Theme_BlackBox)
        floatView = LayoutInflater.from(ctx).inflate(R.layout.view_debugger_float, null)
        setupBubble()
        setupPanel()
        windowManager.addView(floatView, params)
    }

    private fun setupBubble() {
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
                        bubbleX = params.x; bubbleY = params.y
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
        floatView?.findViewById<TextView>(R.id.btn_collapse)?.setOnClickListener { collapsePanel() }
        floatView?.findViewById<TextView>(R.id.btn_refresh_processes)?.setOnClickListener { loadProcesses() }
        floatView?.findViewById<TextView>(R.id.btn_stop_logging)?.setOnClickListener { stopLogging() }

        // Tabs
        floatView?.findViewById<TextView>(R.id.tab_logs)?.setOnClickListener { switchTab("LOGS") }
        floatView?.findViewById<TextView>(R.id.tab_trace)?.setOnClickListener { switchTab("TRACE") }

        // Overflow menu
        floatView?.findViewById<TextView>(R.id.btn_overflow)?.setOnClickListener { anchor ->
            val popup = PopupMenu(ContextThemeWrapper(this, R.style.Theme_BlackBox), anchor)
            popup.menuInflater.inflate(R.menu.debugger_overflow, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_dump_stack      -> { dumpStackNow(); true }
                    R.id.action_download_logs   -> { downloadLogs(); true }
                    R.id.action_download_trace  -> { downloadTrace(); true }
                    else -> false
                }
            }
            popup.show()
        }

        // Log filter buttons
        floatView?.findViewById<TextView>(R.id.btn_filter_all)?.setOnClickListener {
            filterMode = "ALL"; updateFilterLabel("▶  ALL LOGS"); highlightLogFilter("ALL"); rebuildDisplayedLogs()
        }
        floatView?.findViewById<TextView>(R.id.btn_filter_error)?.setOnClickListener {
            filterMode = "ERROR"; updateFilterLabel("▶  ERRORS ONLY"); highlightLogFilter("ERROR"); rebuildDisplayedLogs()
        }
        floatView?.findViewById<TextView>(R.id.btn_filter_calls)?.setOnClickListener {
            filterMode = "CALLS"; updateFilterLabel("▶  FUNCTION CALLS"); highlightLogFilter("CALLS"); rebuildDisplayedLogs()
        }
        floatView?.findViewById<TextView>(R.id.btn_clear_logs)?.setOnClickListener {
            logEntries.clear(); displayedLogs.clear(); logAdapter?.updateLogs(emptyList())
        }

        // Trace controls
        floatView?.findViewById<TextView>(R.id.btn_clear_trace)?.setOnClickListener {
            TraceEngine.clear()
            traceAdapter?.submitList(emptyList())
            updateTraceStatus(0)
        }

        // Trace filter — apply on text change
        floatView?.findViewById<EditText>(R.id.et_trace_filter)?.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    traceAdapter?.filterPattern = s?.toString() ?: ""
                    traceAdapter?.submitFiltered(TraceEngine.snapshot())
                }
            }
        )

        // Scroll to bottom listeners
        floatView?.findViewById<View>(R.id.btn_scroll_bottom_logs)?.setOnClickListener {
            val rv = floatView?.findViewById<RecyclerView>(R.id.rv_logs)
            rv?.scrollToPosition((logAdapter?.itemCount ?: 1) - 1)
            it.visibility = View.GONE
        }
        floatView?.findViewById<View>(R.id.btn_scroll_bottom_trace)?.setOnClickListener {
            val rv = floatView?.findViewById<RecyclerView>(R.id.rv_trace)
            rv?.scrollToPosition((traceAdapter?.itemCount ?: 1) - 1)
            it.visibility = View.GONE
        }

        // Scroll listeners to show/hide the arrow
        floatView?.findViewById<RecyclerView>(R.id.rv_logs)?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastCompletelyVisibleItemPosition()
                val atBottom = lastVisible >= (logAdapter?.itemCount ?: 0) - 5
                floatView?.findViewById<View>(R.id.btn_scroll_bottom_logs)?.visibility = if (atBottom) View.GONE else View.VISIBLE
            }
        })
        floatView?.findViewById<RecyclerView>(R.id.rv_trace)?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastCompletelyVisibleItemPosition()
                val atBottom = lastVisible >= (traceAdapter?.itemCount ?: 0) - 5
                floatView?.findViewById<View>(R.id.btn_scroll_bottom_trace)?.visibility = if (atBottom) View.GONE else View.VISIBLE
            }
        })

        // Processes RecyclerView
        val rv = floatView?.findViewById<RecyclerView>(R.id.rv_processes) ?: return
        processAdapter = ProcessListAdapter { info -> selectProcess(info) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = processAdapter

        // Logs RecyclerView
        val rvLogs = floatView?.findViewById<RecyclerView>(R.id.rv_logs) ?: return
        logAdapter = LogAdapter()
        rvLogs.layoutManager = LinearLayoutManager(this)
        rvLogs.adapter = logAdapter

        // Trace RecyclerView
        val rvTrace = floatView?.findViewById<RecyclerView>(R.id.rv_trace) ?: return
        traceAdapter = TraceAdapter()
        rvTrace.layoutManager = LinearLayoutManager(this)
        rvTrace.adapter = traceAdapter
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Panel expand / collapse (full-screen toggle)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun togglePanel() { if (isPanelExpanded) collapsePanel() else expandPanel() }

    private fun expandPanel() {
        isPanelExpanded = true
        floatView?.findViewById<View>(R.id.debugger_bubble)?.visibility = View.GONE
        floatView?.findViewById<View>(R.id.debugger_panel)?.visibility = View.VISIBLE
        params.width  = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.flags  = windowFlagsExpanded
        params.x = 0; params.y = 0
        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
        loadProcesses()
    }

    private fun collapsePanel() {
        isPanelExpanded = false
        floatView?.findViewById<View>(R.id.debugger_bubble)?.visibility = View.VISIBLE
        floatView?.findViewById<View>(R.id.debugger_panel)?.visibility = View.GONE
        params.width  = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags  = windowFlagsCollapsed
        params.x = bubbleX; params.y = bubbleY
        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tab switching
    // ═══════════════════════════════════════════════════════════════════════════

    private fun switchTab(tab: String) {
        activeTab = tab
        val isLogs = tab == "LOGS"
        floatView?.findViewById<View>(R.id.section_logs)?.visibility  = if (isLogs) View.VISIBLE else View.GONE
        floatView?.findViewById<View>(R.id.section_trace)?.visibility = if (isLogs) View.GONE else View.VISIBLE
        floatView?.findViewById<TextView>(R.id.tab_logs)?.apply {
            setTextColor(if (isLogs) 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
            setBackgroundResource(if (isLogs) R.drawable.bg_filter_active else 0)
        }
        floatView?.findViewById<TextView>(R.id.tab_trace)?.apply {
            setTextColor(if (!isLogs) 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
            setBackgroundResource(if (!isLogs) R.drawable.bg_filter_active else 0)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Process management
    // ═══════════════════════════════════════════════════════════════════════════

    private fun loadProcesses() {
        scanExecutor.submit {
            val list = getRunningProcesses()
            mainHandler.post { processAdapter?.submitList(list) }
        }
    }

    private fun getRunningProcesses(): List<ProcessInfo> {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.runningAppProcesses ?: return emptyList()
        return running.mapNotNull { proc ->
            if (proc.pkgList.isEmpty()) return@mapNotNull null
            val pkg = proc.pkgList[0]
            ProcessInfo(pkg.substringAfterLast('.'), pkg, proc.pid, pkg, pkg)
        }.sortedByDescending { it.pid }
    }

    private fun selectProcess(info: ProcessInfo) {
        selectedPid = info.pid
        selectedPkg = info.packageName
        processAdapter?.setSelectedPid(info.pid)

        floatView?.findViewById<View>(R.id.section_process_select)?.visibility = View.GONE
        floatView?.findViewById<View>(R.id.section_attached)?.visibility = View.VISIBLE
        floatView?.findViewById<TextView>(R.id.tv_selected_process)?.text =
            "${info.name}  PID ${info.pid}"

        logEntries.clear(); displayedLogs.clear(); logAdapter?.updateLogs(emptyList())
        appendSysMsg("[Debugger] Attached to ${info.name} (PID ${info.pid})")

        // Start logcat
        startLogcat(info.pid)

        // Start trace engine + observe events
        TraceEngine.start(info.pid)
        TraceEngine.observer = { events ->
            mainHandler.post {
                val filtered = events
                traceAdapter?.submitFiltered(filtered)
                updateTraceStatus(filtered.size)
                val rv = floatView?.findViewById<RecyclerView>(R.id.rv_trace) ?: return@post
                val lm = rv.layoutManager as? LinearLayoutManager ?: return@post
                if (lm.findLastCompletelyVisibleItemPosition() >= (traceAdapter?.itemCount ?: 0) - 2)
                    rv.scrollToPosition((traceAdapter?.itemCount ?: 1) - 1)
            }
        }
    }

    private fun stopLogging() {
        killLogcat()
        TraceEngine.stop()
        selectedPid = -1; selectedPkg = ""
        processAdapter?.setSelectedPid(-1)
        floatView?.findViewById<View>(R.id.section_process_select)?.visibility = View.VISIBLE
        floatView?.findViewById<View>(R.id.section_attached)?.visibility = View.GONE
        appendSysMsg("[Debugger] Detached.")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Logcat
    // ═══════════════════════════════════════════════════════════════════════════

    private fun startLogcat(pid: Int) {
        killLogcat()
        logcatFuture = logExecutor.submit {
            try {
                val cmd = arrayOf("logcat", "-b", "main", "-b", "system", "-b", "crash",
                    "--pid=$pid", "-v", "threadtime", "*:V")
                logcatProcess = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(logcatProcess!!.inputStream))
                appendSysMsg("[Debugger] Logcat stream started (PID $pid)")
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (Thread.currentThread().isInterrupted) break
                    appendRawLine(line ?: continue)
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted)
                    appendSysMsg("[Debugger] Stream error: ${e.message}")
            }
        }
    }

    private fun killLogcat() {
        try { logcatFuture?.cancel(true) } catch (_: Exception) { }
        try { logcatProcess?.destroy(); logcatProcess = null } catch (_: Exception) { }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Log formatting / filtering
    // ═══════════════════════════════════════════════════════════════════════════

    private fun appendSysMsg(text: String) {
        mainHandler.post {
            if (logEntries.size >= MAX_LOG_ENTRIES) logEntries.removeFirst()
            logEntries.addLast(LogEntry.Sys(text))
            val span = SpannableStringBuilder()
            appendColored(span, "[${timeFormat.format(Date())}] ", COL_TIME)
            appendColored(span, text, COL_SYS)
            displayedLogs.add(span)
            if (displayedLogs.size > MAX_LOG_ENTRIES) displayedLogs.removeAt(0)
            scheduleLogUpdate()
        }
    }

    private fun appendRawLine(raw: String) {
        // __TRACE__ tagged lines are the cross-process event bridge from the container.
        // Route them to TraceEngine and keep them OUT of the LOGS tab.
        if (raw.contains("__TRACE__")) {
            parseTraceEvent(raw)
            return
        }
        mainHandler.post {
            if (logEntries.size >= MAX_LOG_ENTRIES) logEntries.removeFirst()
            logEntries.addLast(LogEntry.Raw(raw))
            if (shouldShow(raw)) {
                displayedLogs.add(formatToSpan(raw))
                if (displayedLogs.size > MAX_LOG_ENTRIES) displayedLogs.removeAt(0)
                scheduleLogUpdate()
            }
        }
    }

    /**
     * Parse a logcat line containing the __TRACE__ tag and push the event into TraceEngine.
     *
     * Expected payload format (after "__TRACE__: "):
     *   TYPE|className|methodName|thread|args|depth|elapsedMs
     *
     * Example: "CLASS_LOAD|com.example.MyClass||dex-enum||0|0"
     */
    private fun parseTraceEvent(raw: String) {
        val payload = raw.substringAfter("__TRACE__: ", "")
                        .ifEmpty { raw.substringAfter("__TRACE__:", "").trim() }
        if (payload.isEmpty()) return

        val parts = payload.split("|")
        val typeName    = parts.getOrNull(0)?.trim() ?: return
        val className   = parts.getOrNull(1) ?: ""
        val methodName  = parts.getOrNull(2) ?: ""
        val thread      = parts.getOrNull(3).takeIf { !it.isNullOrBlank() } ?: "container"
        val args        = parts.getOrNull(4) ?: ""
        val depth       = parts.getOrNull(5)?.toIntOrNull() ?: 0
        val elapsedMs   = parts.getOrNull(6)?.toLongOrNull() ?: 0L

        val type = runCatching { TraceType.valueOf(typeName) }.getOrNull() ?: return

        TraceEngine.addEvent(
            TraceEvent(
                type      = type,
                className = className,
                methodName = methodName,
                thread    = thread,
                args      = args,
                retVal    = "",
                depth     = depth,
                elapsedMs = elapsedMs,
            )
        )
    }

    private fun formatToSpan(raw: String): CharSequence {
        val span = SpannableStringBuilder()
        val formatted = formatLine(raw)
        val tsEnd = formatted.indexOf(']').takeIf { it > 0 }?.plus(2) ?: 0
        if (tsEnd > 0) {
            appendColored(span, formatted.substring(0, tsEnd), COL_TIME)
            appendColored(span, formatted.substring(tsEnd), colorForLine(raw))
        } else {
            appendColored(span, formatted, colorForLine(raw))
        }
        return span
    }

    private fun appendColored(span: SpannableStringBuilder, text: String, color: Int) {
        val start = span.length
        span.append(text)
        span.setSpan(ForegroundColorSpan(color), start, span.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun colorForLine(raw: String): Int = when {
        raw.contains(" E ") || raw.contains(" E/") || raw.contains("Exception") || raw.contains("FATAL") -> COL_ERR
        raw.contains(" W ") || raw.contains(" W/") -> COL_WARN
        raw.contains(" I ") || raw.contains(" I/") -> COL_INFO
        raw.contains(" D ") || raw.contains(" D/") -> COL_DBG
        raw.trimStart().startsWith("at ") || LIFECYCLE_KEYWORDS.any { raw.contains(it) } -> COL_CALL
        else -> COL_INFO
    }

    private fun shouldShow(line: String) = when (filterMode) {
        "ERROR" -> line.contains(" E ") || line.contains(" E/") || line.contains("Exception") || line.contains("FATAL")
        "CALLS" -> line.trimStart().startsWith("at ") || line.contains("/art:") ||
            line.contains("I/art") || (line.contains("prio=") && line.contains("tid=")) ||
            LIFECYCLE_KEYWORDS.any { line.contains(it) }
        else    -> true
    }

    private fun formatLine(raw: String): String {
        if (raw.length < 31) return raw
        return "[${raw.substring(0, 18).trim()}] ${raw.substring(31)}"
    }

    private fun rebuildDisplayedLogs() {
        displayedLogs.clear()
        for (entry in logEntries) {
            when (entry) {
                is LogEntry.Sys -> {
                    val span = SpannableStringBuilder()
                    appendColored(span, entry.text, COL_SYS)
                    displayedLogs.add(span)
                }
                is LogEntry.Raw -> if (shouldShow(entry.raw)) displayedLogs.add(formatToSpan(entry.raw))
            }
        }
        while (displayedLogs.size > MAX_LOG_ENTRIES) displayedLogs.removeAt(0)
        logAdapter?.updateLogs(displayedLogs.toList())
    }

    private fun scheduleLogUpdate() {
        if (!logUpdatePending) {
            logUpdatePending = true
            mainHandler.postDelayed({
                logUpdatePending = false
                val rv = floatView?.findViewById<RecyclerView>(R.id.rv_logs) ?: return@postDelayed
                val adapter = logAdapter ?: return@postDelayed
                val lm = rv.layoutManager as? LinearLayoutManager ?: return@postDelayed
                val atBottom = lm.findLastCompletelyVisibleItemPosition() >= adapter.itemCount - 2
                adapter.updateLogs(displayedLogs.toList())
                if (atBottom) rv.scrollToPosition(adapter.itemCount - 1)
            }, LOG_UPDATE_MS)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Filter UI helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun highlightLogFilter(active: String) {
        mainHandler.post {
            mapOf("ALL" to R.id.btn_filter_all, "ERROR" to R.id.btn_filter_error,
                "CALLS" to R.id.btn_filter_calls).forEach { (key, id) ->
                floatView?.findViewById<TextView>(id)?.setBackgroundResource(
                    if (key == active) R.drawable.bg_filter_active else 0
                )
            }
        }
    }

    private fun updateFilterLabel(text: String) {
        floatView?.findViewById<TextView>(R.id.tv_filter_label)?.text = text
    }

    private fun updateTraceStatus(count: Int) {
        val status = if (selectedPid == -1) "▶  $count events — attach to a process first"
                     else "▶  $count events"
        floatView?.findViewById<TextView>(R.id.tv_trace_status)?.text = status
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Actions
    // ═══════════════════════════════════════════════════════════════════════════

    private fun dumpStackNow() {
        val pid = selectedPid
        if (pid == -1) { showToast("Attach to a process first"); return }
        appendSysMsg("[Debugger] ── Stack dump requested ──")
        android.os.Process.sendSignal(pid, 3)
    }

    private fun downloadLogs() {
        if (logEntries.isEmpty()) { showToast("No logs to save"); return }
        val content = StringBuilder()
        for (entry in logEntries) {
            when (entry) {
                is LogEntry.Sys -> content.append("${entry.text}\n")
                is LogEntry.Raw -> content.append("${entry.raw}\n")
            }
        }
        saveFile("debugger_logs_${timestamp()}.txt", content.toString(), "Logs saved")
    }

    private fun downloadTrace() {
        val events = TraceEngine.snapshot()
        if (events.isEmpty()) { showToast("No trace events to save"); return }
        val json = StringBuilder("[")
        events.forEachIndexed { i, ev ->
            if (i > 0) json.append(",")
            json.append("""{"t":${ev.timestampMs},"type":"${ev.type.name}","class":"${ev.className}","method":"${ev.methodName}","thread":"${ev.thread}","args":${ev.args.toJsonString()},"ret":${ev.retVal.toJsonString()},"depth":${ev.depth},"ms":${ev.elapsedMs}}""")
        }
        json.append("]")
        saveFile("trace_${timestamp()}.json", json.toString(), "Trace saved")
    }

    private fun String.toJsonString(): String {
        val escaped = replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun saveFile(name: String, content: String, successMsg: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) }
                    showToast("$successMsg: Downloads/$name")
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, name)
                file.writeText(content)
                showToast("$successMsg: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            showToast("Save failed: ${e.message}")
        }
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    private fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
