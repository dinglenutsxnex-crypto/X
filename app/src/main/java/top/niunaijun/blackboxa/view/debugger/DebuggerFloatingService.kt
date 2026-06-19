package top.niunaijun.blackboxa.view.debugger

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.Service
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

    private sealed class LogEntry {
        class Sys(val text: String) : LogEntry()
        class Raw(val raw: String) : LogEntry()
    }
    private val logEntries = ArrayDeque<LogEntry>()
    private val MAX_ENTRIES = 5000 // Increased since we use RecyclerView now

    // For RecyclerView display
    private val displayedLogs = mutableListOf<CharSequence>()
    private var logAdapter: LogAdapter? = null

    private val COL_SYS  = 0xFF4DD0E1.toInt()
    private val COL_TIME = 0xFF66BB6A.toInt()
    private val COL_ERR  = 0xFFFF7043.toInt()
    private val COL_WARN = 0xFFFFD54F.toInt()
    private val COL_INFO = 0xFFFFFFFF.toInt()
    private val COL_DBG  = 0xFFB0BEC5.toInt()
    private val COL_CALL = 0xFF42A5F5.toInt()

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var isPanelExpanded = false

    private var selectedPid: Int = -1
    private var selectedPkg: String = ""
    private var filterMode = "ALL"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var logUpdatePending = false
    private val LOG_UPDATE_INTERVAL_MS = 200L

    private val scanExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var logcatFuture: Future<*>? = null
    private var logcatProcess: Process? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var processAdapter: ProcessListAdapter? = null

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
        scanExecutor.shutdownNow()
        logExecutor.shutdownNow()
        try { floatView?.let { windowManager.removeView(it) } } catch (_: Exception) { }
    }

    private fun setupFloatView() {
        val themedCtx = ContextThemeWrapper(this, R.style.Theme_BlackBox)
        floatView = LayoutInflater.from(themedCtx).inflate(R.layout.view_debugger_float, null)
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

        floatView?.findViewById<TextView>(R.id.btn_overflow)?.setOnClickListener { anchor ->
            val themedCtx = ContextThemeWrapper(this, R.style.Theme_BlackBox)
            val popup = PopupMenu(themedCtx, anchor)
            popup.menuInflater.inflate(R.menu.debugger_overflow, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_dump_stack    -> { dumpStackNow(); true }
                    R.id.action_download_logs -> { downloadLogs(); true }
                    else -> false
                }
            }
            popup.show()
        }

        floatView?.findViewById<TextView>(R.id.btn_filter_all)?.setOnClickListener {
            filterMode = "ALL"
            updateFilterLabel("▶  ALL LOGS")
            highlightFilter("ALL")
            rebuildDisplayedLogs()
        }
        floatView?.findViewById<TextView>(R.id.btn_filter_error)?.setOnClickListener {
            filterMode = "ERROR"
            updateFilterLabel("▶  ERRORS ONLY")
            highlightFilter("ERROR")
            rebuildDisplayedLogs()
        }
        floatView?.findViewById<TextView>(R.id.btn_filter_trace)?.setOnClickListener {
            filterMode = "CALLS"
            updateFilterLabel("▶  FUNCTION CALLS")
            highlightFilter("CALLS")
            rebuildDisplayedLogs()
        }
        floatView?.findViewById<TextView>(R.id.btn_clear_logs)?.setOnClickListener {
            logEntries.clear()
            displayedLogs.clear()
            logAdapter?.updateLogs(emptyList())
        }

        val rv = floatView?.findViewById<RecyclerView>(R.id.rv_processes) ?: return
        processAdapter = ProcessListAdapter { info -> selectProcess(info) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = processAdapter

        // Setup Logs RecyclerView
        val rvLogs = floatView?.findViewById<RecyclerView>(R.id.rv_logs) ?: return
        logAdapter = LogAdapter()
        rvLogs.layoutManager = LinearLayoutManager(this)
        rvLogs.adapter = logAdapter
    }

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

    private fun highlightFilter(active: String) {
        mainHandler.post {
            val activeDrawable = resources.getDrawable(R.drawable.bg_filter_active, null)
            mapOf(
                "ALL"   to R.id.btn_filter_all,
                "ERROR" to R.id.btn_filter_error,
                "CALLS" to R.id.btn_filter_trace
            ).forEach { (key, id) ->
                floatView?.findViewById<TextView>(id)?.apply {
                    background = if (key == active) activeDrawable else null
                }
            }
        }
    }

    private fun updateFilterLabel(text: String) {
        floatView?.findViewById<TextView>(R.id.tv_filter_label)?.text = text
    }

    private fun loadProcesses() {
        scanExecutor.submit {
            val list = getRunningProcesses()
            mainHandler.post { processAdapter?.submitList(list) }
        }
    }

    private fun getRunningProcesses(): List<ProcessInfo> {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.runningAppProcesses ?: return emptyList()
        val result = mutableListOf<ProcessInfo>()
        for (proc in running) {
            if (proc.pkgList.isEmpty()) continue
            val pkg = proc.pkgList[0]
            val name = pkg.substringAfterLast('.')
            result.add(ProcessInfo(proc.pid, name, pkg, pkg))
        }
        return result.sortedByDescending { it.pid }
    }

    private fun selectProcess(info: ProcessInfo) {
        selectedPid = info.pid
        selectedPkg = info.packageName
        
        processAdapter?.setSelectedPid(info.pid)
        
        floatView?.findViewById<View>(R.id.section_process_select)?.visibility = View.GONE
        floatView?.findViewById<View>(R.id.section_logging)?.visibility = View.VISIBLE
        floatView?.findViewById<TextView>(R.id.tv_selected_process)?.text = "Attached: ${info.name}"
        
        logEntries.clear()
        displayedLogs.clear()
        logAdapter?.updateLogs(emptyList())
        appendSysMsg("[Debugger] Attached to ${info.name} (PID ${info.pid})")
        
        startLogcat(info.pid)
    }

    private fun stopLogging() {
        killLogcat()
        selectedPid = -1
        selectedPkg = ""
        processAdapter?.setSelectedPid(-1)
        
        floatView?.findViewById<View>(R.id.section_process_select)?.visibility = View.VISIBLE
        floatView?.findViewById<View>(R.id.section_logging)?.visibility = View.GONE
        
        appendSysMsg("[Debugger] Logging stopped.")
    }

    private fun startLogcat(pid: Int) {
        killLogcat()
        logcatFuture = logExecutor.submit {
            try {
                val cmd = arrayOf("logcat", "-b", "main", "-b", "system", "-b", "crash", "--pid=$pid", "-v", "threadtime")
                logcatProcess = Runtime.getRuntime().exec(cmd)
                val reader = BufferedReader(InputStreamReader(logcatProcess!!.inputStream))
                appendSysMsg("[Debugger] Logcat stream started (PID $pid)")

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (Thread.currentThread().isInterrupted) break
                    appendRawLine(line ?: continue)
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    appendSysMsg("[Debugger] Stream error: ${e.message}")
                }
            }
        }
    }

    private fun killLogcat() {
        try { logcatFuture?.cancel(true) } catch (_: Exception) { }
        try { logcatProcess?.destroy(); logcatProcess = null } catch (_: Exception) { }
    }

    private fun dumpStackNow() {
        val pid = selectedPid
        if (pid == -1) { showToast("Attach to a process first"); return }
        appendSysMsg("[Debugger] ── Stack dump requested ──")
        android.os.Process.sendSignal(pid, 3)
    }

    private fun appendSysMsg(text: String) {
        mainHandler.post {
            if (logEntries.size >= MAX_ENTRIES) logEntries.removeFirst()
            logEntries.addLast(LogEntry.Sys(text))
            
            val span = SpannableStringBuilder()
            val ts = timeFormat.format(Date())
            appendColored(span, "[$ts] ", COL_TIME)
            appendColored(span, "$text", COL_SYS)
            
            displayedLogs.add(span)
            if (displayedLogs.size > MAX_ENTRIES) displayedLogs.removeAt(0)
            scheduleLogUpdate()
        }
    }

    private fun appendRawLine(raw: String) {
        mainHandler.post {
            if (logEntries.size >= MAX_ENTRIES) logEntries.removeFirst()
            logEntries.addLast(LogEntry.Raw(raw))
            if (shouldShow(raw)) {
                val span = formatToSpan(raw)
                displayedLogs.add(span)
                if (displayedLogs.size > MAX_ENTRIES) displayedLogs.removeAt(0)
                scheduleLogUpdate()
            }
        }
    }

    private fun formatToSpan(raw: String): CharSequence {
        val span = SpannableStringBuilder()
        val formatted = format(raw)
        val color = colorForLine(raw)
        val tsEnd = formatted.indexOf(']').takeIf { it > 0 }?.plus(2) ?: 0
        if (tsEnd > 0) {
            appendColored(span, formatted.substring(0, tsEnd), COL_TIME)
            appendColored(span, formatted.substring(tsEnd), color)
        } else {
            appendColored(span, formatted, color)
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

    private fun rebuildDisplayedLogs() {
        displayedLogs.clear()
        for (entry in logEntries) {
            when (entry) {
                is LogEntry.Sys -> {
                    val span = SpannableStringBuilder()
                    appendColored(span, "${entry.text}", COL_SYS)
                    displayedLogs.add(span)
                }
                is LogEntry.Raw -> if (shouldShow(entry.raw)) {
                    displayedLogs.add(formatToSpan(entry.raw))
                }
            }
        }
        while (displayedLogs.size > MAX_ENTRIES) displayedLogs.removeAt(0)
        logAdapter?.updateLogs(displayedLogs.toList())
    }

    private fun scheduleLogUpdate() {
        if (!logUpdatePending) {
            logUpdatePending = true
            mainHandler.postDelayed({
                logUpdatePending = false
                updateLogView()
            }, LOG_UPDATE_INTERVAL_MS)
        }
    }

    private fun updateLogView() {
        val rv = floatView?.findViewById<RecyclerView>(R.id.rv_logs) ?: return
        val adapter = logAdapter ?: return
        val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return
        
        val atBottom = layoutManager.findLastCompletelyVisibleItemPosition() >= adapter.itemCount - 2
        adapter.updateLogs(displayedLogs.toList())
        if (atBottom) rv.scrollToPosition(adapter.itemCount - 1)
    }

    private fun shouldShow(line: String) = when (filterMode) {
        "ERROR" -> line.contains(" E ") || line.contains(" E/") || line.contains("Exception") || line.contains("FATAL")
        "CALLS" -> line.trimStart().startsWith("at ") || line.contains("/art:") || line.contains("I/art") || (line.contains("prio=") && line.contains("tid=")) || LIFECYCLE_KEYWORDS.any { line.contains(it) }
        else    -> true
    }

    private fun format(raw: String): String {
        if (raw.length < 31) return raw
        val time = raw.substring(0, 18).trim()
        val tagMsg = raw.substring(31)
        return "[$time] $tagMsg"
    }

    private fun downloadLogs() {
        if (logEntries.isEmpty()) { showToast("No logs to save"); return }
        val pid = selectedPid.takeIf { it != -1 } ?: 0
        val pkg = selectedPkg.takeIf { it.isNotEmpty() } ?: "system"
        val fileName = "debugger_logs_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        
        val content = StringBuilder()
        for (entry in logEntries) {
            when (entry) {
                is LogEntry.Sys -> content.append("${entry.text}\n")
                is LogEntry.Raw -> content.append("${entry.raw}\n")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { os -> os.write(content.toString().toByteArray()) }
                    showToast("Logs saved to Downloads")
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, fileName)
                file.writeText(content.toString())
                showToast("Logs saved to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
            showToast("Save failed: ${e.message}")
        }
    }

    private fun showToast(msg: String) = mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
