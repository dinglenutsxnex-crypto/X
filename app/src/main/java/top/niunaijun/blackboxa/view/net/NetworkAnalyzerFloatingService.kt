package top.niunaijun.blackboxa.view.net

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

class NetworkAnalyzerFloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var isPanelExpanded = false
    private var vpnRunning = false
    private var targetApp: AppEntry? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var adapter: ConnectionAdapter

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
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupFloatView()
        observeTracker()
    }

    override fun onDestroy() {
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
            if (vpnRunning) stopVpn() else startVpn()
        }
        floatView?.findViewById<TextView>(R.id.tv_target_app)?.setOnClickListener {
            Toast.makeText(this, "Target filtering coming soon in overlay", Toast.LENGTH_SHORT).show()
        }
        floatView?.findViewById<TextView>(R.id.btn_clear_all)?.setOnClickListener {
            NetworkAnalyzerVpnService.tracker.clear()
            adapter.submitFiltered(emptyList())
        }

        val rv = floatView?.findViewById<RecyclerView>(R.id.rv_connections) ?: return
        adapter = ConnectionAdapter { /* detail view in overlay? */ }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

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
                adapter.filterProto = proto
                adapter.applyFilters()
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
        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
    }

    private fun collapsePanel() {
        isPanelExpanded = false
        floatView?.findViewById<View>(R.id.analyzer_bubble)?.visibility = View.VISIBLE
        floatView?.findViewById<LinearLayout>(R.id.analyzer_panel)?.visibility = View.GONE
        try { windowManager.updateViewLayout(floatView, params) } catch (_: Exception) { }
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            Toast.makeText(this, "Please grant VPN permission in the app first", Toast.LENGTH_LONG).show()
            return
        }
        vpnRunning = true
        startService(Intent(this, NetworkAnalyzerVpnService::class.java).apply {
            action = NetworkAnalyzerVpnService.ACTION_START
        })
        updateVpnUi(true)
    }

    private fun stopVpn() {
        vpnRunning = false
        startService(Intent(this, NetworkAnalyzerVpnService::class.java).apply {
            action = NetworkAnalyzerVpnService.ACTION_STOP
        })
        updateVpnUi(false)
    }

    private fun updateVpnUi(running: Boolean) {
        val status = floatView?.findViewById<TextView>(R.id.tv_vpn_status)
        val btn = floatView?.findViewById<TextView>(R.id.btn_vpn_start)
        if (running) {
            status?.text = "VPN ACTIVE"
            status?.setTextColor(0xFFFFFFFF.toInt())
            btn?.text = "STOP"
            btn?.setTextColor(0xFFFF5555.toInt())
        } else {
            status?.text = "VPN INACTIVE"
            status?.setTextColor(0xFF888888.toInt())
            btn?.text = "START"
            btn?.setTextColor(0xFFFFFFFF.toInt())
        }
    }

    private fun observeTracker() {
        NetworkAnalyzerVpnService.tracker.liveData.observeForever { list ->
            mainHandler.post {
                adapter.submitFiltered(list)
                floatView?.findViewById<TextView>(R.id.tv_conn_count)?.text = "${list.size} connections"
            }
        }
    }
}
