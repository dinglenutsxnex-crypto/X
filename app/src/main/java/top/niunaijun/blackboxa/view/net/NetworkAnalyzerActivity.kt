package top.niunaijun.blackboxa.view.net

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackboxa.R

class NetworkAnalyzerActivity : AppCompatActivity() {

    companion object {
        private const val TAG     = "NetAnalyzer"
        private const val REQ_VPN = 9001

        fun start(context: Context) =
            context.startActivity(Intent(context, NetworkAnalyzerActivity::class.java))
    }

    private lateinit var tvVpnStatus:   TextView
    private lateinit var tvTargetApp:   TextView
    private lateinit var btnStart:      TextView
    private lateinit var rvConnections: RecyclerView
    private lateinit var tvEmpty:       View
    private lateinit var tvConnCount:   TextView
    private lateinit var btnClearAll:   TextView

    private lateinit var filterButtons: List<Pair<TextView, Protocol?>>
    private lateinit var btnAlive:  TextView
    private lateinit var btnClosed: TextView
    private lateinit var btnOut:    TextView
    private lateinit var btnIn:     TextView

    private var vpnRunning = false
    private var targetApp: AppEntry? = null

    // Open connection detail activity — not an alert dialog
    private val adapter = ConnectionAdapter { rec ->
        ConnectionDetailActivity.start(this, rec.id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_analyzer)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Network Analyzer"
        }
        bindViews()
        setupFilterBar()
        setupRecyclerView()
        setupButtons()
        observeTracker()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnRunning) stopVpn()
    }

    private fun bindViews() {
        tvVpnStatus   = findViewById(R.id.tv_vpn_status)
        tvTargetApp   = findViewById(R.id.tv_target_app)
        btnStart      = findViewById(R.id.btn_vpn_start)
        rvConnections = findViewById(R.id.rv_connections)
        tvEmpty       = findViewById(R.id.tv_empty_hint)
        tvConnCount   = findViewById(R.id.tv_conn_count)
        btnClearAll   = findViewById(R.id.btn_clear_all)
        btnAlive      = findViewById(R.id.btn_filter_alive)
        btnClosed     = findViewById(R.id.btn_filter_closed)
        btnOut        = findViewById(R.id.btn_filter_out)
        btnIn         = findViewById(R.id.btn_filter_in)

    }

    private fun setupFilterBar() {
        val btnAll  = findViewById<TextView>(R.id.filter_all)
        val btnHttp = findViewById<TextView>(R.id.filter_http)
        val btnTls  = findViewById<TextView>(R.id.filter_tls)
        val btnWs   = findViewById<TextView>(R.id.filter_ws)
        val btnTcp  = findViewById<TextView>(R.id.filter_tcp)
        val btnUdp  = findViewById<TextView>(R.id.filter_udp)
        val btnDns  = findViewById<TextView>(R.id.filter_dns)

        filterButtons = listOf(
            btnAll  to null,
            btnHttp to Protocol.HTTP,
            btnTls  to Protocol.HTTPS,
            btnWs   to Protocol.WS,
            btnTcp  to Protocol.TCP,
            btnUdp  to Protocol.UDP,
            btnDns  to Protocol.DNS
        )
        filterButtons.forEach { (btn, proto) ->
            btn.setOnClickListener {
                adapter.filterProto = proto
                highlightProtoFilter(btn)
                adapter.applyFilters()
            }
        }
        highlightProtoFilter(btnAll)

        setupToggle(btnAlive, btnClosed) { active ->
            adapter.filterStatus = if (active === btnAlive) ConnStatus.ALIVE
                                   else if (active === btnClosed) ConnStatus.CLOSED
                                   else null
            adapter.applyFilters()
        }
        setupToggle(btnOut, btnIn) { active ->
            adapter.filterDirection = if (active === btnOut) Direction.OUTBOUND
                                      else if (active === btnIn) Direction.INBOUND
                                      else null
            adapter.applyFilters()
        }
    }

    private fun highlightProtoFilter(selected: TextView) {
        filterButtons.forEach { (btn, _) ->
            btn.setTextColor(if (btn === selected) 0xFF80DEEA.toInt() else 0x80546E7A.toInt())
            btn.setBackgroundResource(
                if (btn === selected) R.drawable.bg_filter_active else android.R.color.transparent
            )
        }
    }

    private fun setupToggle(btnA: TextView, btnB: TextView, onChanged: (TextView?) -> Unit) {
        var active: TextView? = null
        val toggle = { tapped: TextView ->
            active = if (active === tapped) null else tapped
            btnA.setTextColor(if (active === btnA) 0xFF80DEEA.toInt() else 0x80546E7A.toInt())
            btnB.setTextColor(if (active === btnB) 0xFF80DEEA.toInt() else 0x80546E7A.toInt())
            onChanged(active)
        }
        btnA.setOnClickListener { toggle(btnA) }
        btnB.setOnClickListener { toggle(btnB) }
    }

    private fun setupRecyclerView() {
        rvConnections.layoutManager = LinearLayoutManager(this)
        rvConnections.adapter = adapter
        rvConnections.setHasFixedSize(false)
        rvConnections.itemAnimator = null
    }

    private fun setupButtons() {
        btnStart.setOnClickListener {
            if (vpnRunning) stopVpn() else requestVpnPermission()
        }
        tvTargetApp.setOnClickListener { showAppPicker() }
        btnClearAll.setOnClickListener {
            NetworkAnalyzerVpnService.tracker.clear()
            adapter.submitFiltered(emptyList())
            updateEmptyState(true)
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent == null) startVpn() else startActivityForResult(intent, REQ_VPN)
    }

    @Deprecated("standard VPN permission pattern")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) startVpn()
        else if (requestCode == REQ_VPN) {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_LONG).show()
        }
    }

    private fun startVpn() {
        vpnRunning = true
        try {
            val svc = Intent(this, NetworkAnalyzerVpnService::class.java).apply {
                action = NetworkAnalyzerVpnService.ACTION_START
            }
            startService(svc)
            updateVpnUi(running = true)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start VPN: ${e.message}", Toast.LENGTH_LONG).show()
            vpnRunning = false
        }
    }

    private fun stopVpn() {
        vpnRunning = false
        try {
            startService(Intent(this, NetworkAnalyzerVpnService::class.java).apply {
                action = NetworkAnalyzerVpnService.ACTION_STOP
            })
        } catch (_: Exception) {}
        updateVpnUi(running = false)
    }

    private fun updateVpnUi(running: Boolean) {
        if (running) {
            tvVpnStatus.text = "VPN ACTIVE"
            tvVpnStatus.setTextColor(0xFF69F0AE.toInt())
            btnStart.text = "STOP"
            btnStart.setTextColor(0xFFEF9A9A.toInt())
            tvTargetApp.isEnabled = false
        } else {
            tvVpnStatus.text = "VPN INACTIVE"
            tvVpnStatus.setTextColor(0xFF546E7A.toInt())
            btnStart.text = "START"
            btnStart.setTextColor(0xFF80DEEA.toInt())
            tvTargetApp.isEnabled = true
        }
    }

    private fun showAppPicker() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_BlackBox)
            .setTitle("Filter: container app")
            .setView(R.layout.dialog_app_picker)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        val rv = dialog.findViewById<RecyclerView>(R.id.rv_app_list) ?: return
        val pickerAdapter = AppPickerAdapter { chosen ->
            targetApp = chosen
            tvTargetApp.text = chosen?.label ?: "All Container Traffic"
            adapter.filterProto = null
            adapter.applyFilters()
            dialog.dismiss()
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = pickerAdapter
        Thread {
            val apps = AppPickerAdapter.loadInstalledApps(packageManager)
            runOnUiThread { pickerAdapter.setApps(apps) }
        }.also { it.isDaemon = true }.start()
    }

    private fun observeTracker() {
        NetworkAnalyzerVpnService.tracker.liveData.observe(this) { list ->
            adapter.submitFiltered(list)
            tvConnCount.text = "${list.size} connections"
            updateEmptyState(adapter.itemCount == 0)
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        tvEmpty.visibility       = if (isEmpty) View.VISIBLE else View.GONE
        rvConnections.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

}
