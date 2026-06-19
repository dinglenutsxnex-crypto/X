package top.niunaijun.blackboxa.view.net

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.VpnService
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
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.view.debugger.ProcessInfo
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NetworkAnalyzerFloatingService : Service() {

    companion object {
        private const val TAG = "NetAnalyzerFloat"
        @Volatile var isRunning: Boolean = false
    }

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var isPanelExpanded = false
    private var vpnRunning = false

    private var selectedProcess: ProcessInfo? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var connectionAdapter: ConnectionAdapter
    private var processAdapter: NetworkProcessAdapter? = null

    // Poll every 2s to detect if the VPN was silently revoked
    private val vpnWatchdogRunnable = object : Runnable {
        override fun run() {
            if (vpnRunning && !NetworkAnalyzerVpnService.vpnEstablished) {
                vpnRunning = false
                updateVpnUi(false, "VPN REVOKED — tap START again")
            }
            if (vpnRunning) mainHandler.postDelayed(this, 2000)
        }
    }

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
            y = 400
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupFloatView()
        observeTracker()
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(vpnWatchdogRunnable)
        stopCapture()
        super.onDestroy()
        try { floatView?.let { windowManager.removeView(it) } } catch (_: Exception) { }
    }

    private fun setupFloatView() {
        val themedCtx = ContextThemeWrapper(this, R.style.Theme_BlackBox)
        floatView = LayoutInflater.from(themedCtx).inflate(R.layout.view_analyzer_float, null)
        setupBubble()
        setupPanel()
        windowManager.addView(floatView, params)
    }

    private fun setupBubble() {
        val bubble = floatView?.findViewById<View>(R.id.analyzer_bubble) ?: return
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
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
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

        floatView?.findViewById<TextView>(R.id.btn_vpn_start)?.setOnClickListener {
            if (vpnRunning) stopCapture() else startCapture()
        }

        floatView?.findViewById<TextView>(R.id.btn_refresh_net_procs)?.setOnClickListener {
            loadProcesses()
        }

        floatView?.findViewById<TextView>(R.id.btn_clear_all)?.setOnClickListener {
            NetworkAnalyzerVpnService.tracker.clear()
            connectionAdapter.submitFiltered(emptyList())
            floatView?.findViewById<TextView>(R.id.tv_conn_count)?.text = "0 connections"
        }

        floatView?.findViewById<TextView>(R.id.tv_net_target)?.setOnClickListener {
            val rvProc = floatView?.findViewById<RecyclerView>(R.id.rv_net_processes)
            if (rvProc?.visibility == View.VISIBLE) {
                rvProc.visibility = View.GONE
            } else {
                rvProc?.visibility = View.VISIBLE
                loadProcesses()
            }
        }

        val rv = floatView?.findViewById<RecyclerView>(R.id.rv_connections) ?: return
        connectionAdapter = ConnectionAdapter { }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = connectionAdapter

        val rvProc = floatView?.findViewById<RecyclerView>(R.id.rv_net_processes)
        if (rvProc != null) {
            processAdapter = NetworkProcessAdapter { proc ->
                selectedProcess = proc
                processAdapter?.setSelectedPid(proc.pid)
                NetworkAnalyzerVpnService.tracker.clear()
                connectionAdapter.submitFiltered(emptyList())
                floatView?.findViewById<TextView>(R.id.tv_conn_count)?.text = "0 connections"
                floatView?.findViewById<TextView>(R.id.tv_net_target)?.text = "▶ ${proc.name}"
                // Auto-collapse process list on selection for better UX
                rvProc.visibility = View.GONE
            }
            rvProc.layoutManager = LinearLayoutManager(this)
            rvProc.adapter = processAdapter
        }

        setupFilterBar()
    }

    private fun setupFilterBar() {
        val filterButtons = listOf(
            floatView?.findViewById<TextView>(R.id.filter_all) to null,
            floatView?.findViewById<TextView>(R.id.filter_http) to Protocol.HTTP,
            floatView?.findViewById<TextView>(R.id.filter_tls) to Protocol.HTTPS,
            floatView?.findViewById<TextView>(R.id.filter_ws) to Protocol.WS,
            floatView?.findViewById<TextView>(R.id.filter_tcp) to Protocol.TCP,
            floatView?.findViewById<TextView>(R.id.filter_udp) to Protocol.UDP,
            floatView?.findViewById<TextView>(R.id.filter_dns) to Protocol.DNS
        )
        filterButtons.forEach { (btn, proto) ->
            btn?.setOnClickListener {
                connectionAdapter.filterProto = proto
                connectionAdapter.applyFilters()
                filterButtons.forEach { (b, _) ->
                    b?.setTextColor(if (b === btn) 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
                    b?.setBackgroundResource(if (b === btn) R.drawable.bg_filter_active else 0)
                }
            }
        }
    }

    private fun togglePanel() { if (isPanelExpanded) collapsePanel() else expandPanel() }

    private fun expandPanel() {
        isPanelExpanded = true
        floatView?.findViewById<View>(R.id.analyzer_bubble)?.visibility = View.GONE
        floatView?.findViewById<LinearLayout>(R.id.analyzer_panel)?.visibility = View.VISIBLE
        // Keep process list hidden initially if a process is already selected
        if (selectedProcess != null) {
            floatView?.findViewById<View>(R.id.rv_net_processes)?.visibility = View.GONE
        } else {
            floatView?.findViewById<View>(R.id.rv_net_processes)?.visibility = View.VISIBLE
            loadProcesses()
        }
        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
    }

    private fun collapsePanel() {
        isPanelExpanded = false
        floatView?.findViewById<View>(R.id.analyzer_bubble)?.visibility = View.VISIBLE
        floatView?.findViewById<LinearLayout>(R.id.analyzer_panel)?.visibility = View.GONE
        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
    }

    private val scanExecutor = Executors.newSingleThreadExecutor()

    private fun loadProcesses() {
        scanExecutor.submit {
            val procs = try { doScanProcesses() } catch (e: Exception) {
                Log.w(TAG, "process scan error: ${e.message}")
                emptyList()
            }
            mainHandler.post { processAdapter?.submitList(procs) }
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
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(guestPkg, 0)
                    ).toString()
                }.getOrElse { slotSuffix ?: name.substringAfterLast('.') }
            } else {
                slotSuffix ?: name.substringAfterLast('.')
            }
            result.add(ProcessInfo(
                name = displayName,
                packageName = name,
                pid = proc.pid,
                processLine = name,
                appPackage = guestPkg
            ))
        }
        return result.sortedByDescending { it.pid }
    }

    private fun startCapture() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            Toast.makeText(this, "Please grant VPN permission in the main app first", Toast.LENGTH_LONG).show()
            return
        }
        
        vpnRunning = true
        updateVpnUi(true, "VPN STARTING...")
        
        val vpnIntent = Intent(this, NetworkAnalyzerVpnService::class.java).apply {
            action = NetworkAnalyzerVpnService.ACTION_START
            putExtra(NetworkAnalyzerVpnService.EXTRA_PACKAGE, selectedProcess?.appPackage)
        }
        startService(vpnIntent)
        
        mainHandler.postDelayed(vpnWatchdogRunnable, 2000)
    }

    private fun stopCapture() {
        vpnRunning = false
        mainHandler.removeCallbacks(vpnWatchdogRunnable)

        runCatching {
            startService(Intent(this, NetworkAnalyzerVpnService::class.java).apply {
                action = NetworkAnalyzerVpnService.ACTION_STOP
            })
        }
        updateVpnUi(false)
    }

    private fun updateVpnUi(active: Boolean, statusText: String? = null) {
        val btn = floatView?.findViewById<TextView>(R.id.btn_vpn_start) ?: return
        val status = floatView?.findViewById<TextView>(R.id.tv_vpn_status) ?: return
        
        if (active) {
            btn.text = "STOP"
            btn.setBackgroundResource(R.drawable.bg_btn_stop)
            status.text = statusText ?: "VPN ACTIVE"
            status.setTextColor(0xFF4CAF50.toInt())
        } else {
            btn.text = "START"
            btn.setBackgroundResource(R.drawable.bg_btn_start)
            status.text = statusText ?: "VPN IDLE"
            status.setTextColor(0xFF888888.toInt())
        }
    }

    private fun observeTracker() {
        NetworkAnalyzerVpnService.tracker.observer = { records ->
            mainHandler.post {
                val filtered = if (selectedProcess != null) {
                    records.filter { it.srcIp == "10.99.0.1" || it.dstIp == "10.99.0.1" }
                } else {
                    records
                }
                
                val rv = floatView?.findViewById<RecyclerView>(R.id.rv_connections)
                val lastVisible = (rv?.layoutManager as? LinearLayoutManager)?.findLastCompletelyVisibleItemPosition() ?: 0
                val count = connectionAdapter.itemCount

                connectionAdapter.submitFiltered(filtered)
                floatView?.findViewById<TextView>(R.id.tv_conn_count)?.text = "${filtered.size} connections"

                if (count == 0 || lastVisible >= count - 1) {
                    rv?.post { rv.scrollToPosition(connectionAdapter.itemCount - 1) }
                }
            }
        }
    }
}
