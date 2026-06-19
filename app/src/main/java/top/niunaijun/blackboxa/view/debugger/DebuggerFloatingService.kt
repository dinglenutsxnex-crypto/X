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
import android.widget.ScrollView
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
    private val MAX_ENTRIES = 3000

    private val logSpan = SpannableStringBuilder()

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
            rebuildLogSpan()
        }
        floatView?.findViewById<TextView>(R.id.btn_filter_error)?.setOnClickListener {
            filterMode = "ERROR"
            updateFilterLabel("▶  ERRORS ONLY")
            highlightFilter("ERROR")
            rebuildLogSpan()
        }
        floatView?.findViewById<TextView>(R.id.btn_filter_trace)?.setOnClickListener {
            filterMode = "CALLS"
            updateFilterLabel("▶  FUNCTION CALLS")
            highlightFilter("CALLS")
            rebuildLogSpan()
        }
        floatView?.findViewById<TextView>(R.id.btn_clear_logs)?.setOnClickListener {
            logEntries.clear()
            logSpan.clear()
            logSpan.clearSpans()
            floatView?.findViewById<TextView>(R.id.tv_logs)?.text = ""
        }

        val rv = floatView?.findViewById<RecyclerView>(R.id.rv_processes) ?: return
        processAdapter = ProcessListAdapter { info -> selectProcess(info) }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = processAdapter
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
                floatView?.findViewById<TextView>(id)?.background =
                    if (key == active) activeDrawable else null
            }
        }
    }

    private fun updateFilterLabel(label: String) {
        mainHandler.post {
            floatView?.findViewById<TextView>(R.id.tv_filter_label)?.text = label
        }
    }

    private fun loadProcesses() {
        scanExecutor.submit {
            val processes = try { doScanProcesses() } catch (e: Exception) { emptyList() }
            mainHandler.post { processAdapter?.submitList(processes) }
        }
    }

    private fun doScanProcesses(): List<ProcessInfo> {
        val hostPkg = packageName
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.runningAppProcesses ?: return emptyList()
        val result = mutableListOf<ProcessInfo>()
        for (proc in running) {
            val name = proc.processName ?: continue
            if (name == hostPkg) continue
            val slotSuffix = if (name.startsWith("$hostPkg:")) name.substringAfterLast(':') else null
            val guestPkg = proc.pkgList?.firstOrNull { it != hostPkg } ?: ""
            val displayName = if (guestPkg.isNotEmpty()) {
                runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(guestPkg, 0)).toString()
                }.getOrElse { slotSuffix ?: name.substringAfterLast('.') }
            } else {
                slotSuffix ?: name.substringAfterLast('.')
            }
            result.add(ProcessInfo(name = displayName, packageName = name, pid = proc.pid, processLine = name, appPackage = guestPkg))
        }
        return result.sortedByDescending { it.pid }
    }

    private fun selectProcess(info: ProcessInfo) {
        selectedPid = info.pid
        selectedPkg = info.packageName
        
        processAdapter?.setSelectedPid(info.pid)
        
        // Switch sections
        floatView?.findViewById<View>(R.id.section_process_select)?.visibility = View.GONE
        floatView?.findViewById<View>(R.id.section_logging)?.visibility = View.VISIBLE
        floatView?.findViewById<TextView>(R.id.tv_selected_process)?.text = "Attached: ${info.name}"
        
        logEntries.clear()
        logSpan.clear()
        logSpan.clearSpans()
        appendSysMsg("[Debugger] Attached to ${info.name} (PID ${info.pid})")
        
        startLogcat(info.pid)
    }

    private fun stopLogging() {
        killLogcat()
        selectedPid = -1
        selectedPkg = ""
        processAdapter?.setSelectedPid(-1)
        
        // Switch sections back
        floatView?.findViewById<View>(R.id.section_process_select)?.visibility = View.VISIBLE
        floatView?.findViewById<View>(R.id.section_logging)?.visibility = View.GONE
        
        appendSysMsg("[Debugger] Logging stopped.")
    }

    private fun startLogcat(pid: Int) {
        killLogcat()
        logcatFuture = logExecutor.submit {
            try {
                // In-container processes are same UID, so we can read their logs without root
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
        // Same-UID allows sending SIGQUIT for thread dump
        android.os.Process.sendSignal(pid, 3)
    }

    private fun appendSysMsg(text: String) {
        mainHandler.post {
            if (logEntries.size >= MAX_ENTRIES) logEntries.removeFirst()
            logEntries.addLast(LogEntry.Sys(text))
            val ts = timeFormat.format(Date())
            appendColoredToSpan("[$ts] ", COL_TIME)
            appendColoredToSpan("$text\n", COL_SYS)
            scheduleLogUpdate()
        }
    }

    private fun appendRawLine(raw: String) {
        mainHandler.post {
            if (logEntries.size >= MAX_ENTRIES) logEntries.removeFirst()
            logEntries.addLast(LogEntry.Raw(raw))
            if (shouldShow(raw)) {
                appendFormattedRaw(raw)
                scheduleLogUpdate()
            }
        }
    }

    private fun appendFormattedRaw(raw: String) {
        val formatted = format(raw)
        val color = colorForLine(raw)
        val tsEnd = formatted.indexOf(']').takeIf { it > 0 }?.plus(2) ?: 0
        if (tsEnd > 0) {
            appendColoredToSpan(formatted.substring(0, tsEnd), COL_TIME)
            appendColoredToSpan(formatted.substring(tsEnd), color)
        } else {
            appendColoredToSpan(formatted, color)
        }
    }

    private fun appendColoredToSpan(text: String, color: Int) {
        val start = logSpan.length
        logSpan.append(text)
        logSpan.setSpan(ForegroundColorSpan(color), start, logSpan.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun colorForLine(raw: String): Int = when {
        raw.contains(" E ") || raw.contains(" E/") || raw.contains("Exception") || raw.contains("FATAL") -> COL_ERR
        raw.contains(" W ") || raw.contains(" W/") -> COL_WARN
        raw.contains(" I ") || raw.contains(" I/") -> COL_INFO
        raw.contains(" D ") || raw.contains(" D/") -> COL_DBG
        raw.trimStart().startsWith("at ") || LIFECYCLE_KEYWORDS.any { raw.contains(it) } -> COL_CALL
        else -> COL_INFO
    }

    private fun rebuildLogSpan() {
        logSpan.clear()
        logSpan.clearSpans()
        for (entry in logEntries) {
            when (entry) {
                is LogEntry.Sys -> appendColoredToSpan("${entry.text}\n", COL_SYS)
                is LogEntry.Raw -> if (shouldShow(entry.raw)) appendFormattedRaw(entry.raw)
            }
        }
        mainHandler.post { updateLogView() }
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
        val sv = floatView?.findViewById<ScrollView>(R.id.scroll_logs) ?: return
        val tv = floatView?.findViewById<TextView>(R.id.tv_logs) ?: return
        val atBottom = isAtBottom(sv)
        tv.text = logSpan
        if (atBottom) sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun isAtBottom(sv: ScrollView): Boolean {
        val child = sv.getChildAt(0) ?: return true
        return sv.scrollY + sv.height >= child.height - 40
    }

    private fun shouldShow(line: String) = when (filterMode) {
        "ERROR" -> line.contains(" E ") || line.contains(" E/") || line.contains("Exception") || line.contains("FATAL")
        "CALLS" -> line.trimStart().startsWith("at ") || line.contains("/art:") || line.contains("I/art") || (line.contains("prio=") && line.contains("tid=")) || LIFECYCLE_KEYWORDS.any { line.contains(it) }
        else    -> true
    }

    private fun format(raw: String): String {
        if (filterMode == "CALLS") {
            val t = raw.trim()
            return when {
                t.startsWith("at ") -> {
                    val method = t.removePrefix("at ").substringBefore("(")
                    "  ↳ ${method.substringAfterLast('.')}()  ← ${method.substringBeforeLast('.')}  (${t.substringAfter("(").substringBefore(")")})\n"
                }
                t.contains("prio=") && t.contains("tid=") -> "\n── Thread: $t\n"
                else -> "$raw\n"
            }
        }
        return "$raw\n"
    }

    private fun downloadLogs() {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fname = "debugger_logs_$ts.txt"
            val content = logSpan.toString()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fname)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    cv.clear()
                    cv.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, cv, null, null)
                    showToast("Saved to Downloads/$fname")
                }
            }
        } catch (e: Exception) { }
    }

    private fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
