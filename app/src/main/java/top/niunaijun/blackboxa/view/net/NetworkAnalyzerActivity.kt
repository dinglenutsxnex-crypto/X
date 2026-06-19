package top.niunaijun.blackboxa.view.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackboxa.R

class NetworkAnalyzerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NetAnalyzerActivity"
        private const val REQ_OVERLAY = 1001

        fun start(context: Context) =
            context.startActivity(Intent(context, NetworkAnalyzerActivity::class.java))
    }

    private lateinit var btnStart: TextView
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_launcher)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Network Analyzer"
        }

        btnStart = findViewById(R.id.btn_start_network)
        tvHint = findViewById(R.id.tv_overlay_hint)

        btnStart.setOnClickListener {
            if (NetworkAnalyzerFloatingService.isRunning) {
                stopNetworkService()
            } else {
                if (hasOverlayPermission()) {
                    launchNetworkService()
                } else {
                    tvHint.visibility = android.view.View.VISIBLE
                    tvHint.text = "Overlay permission required. Tap to open settings."
                    tvHint.setOnClickListener { requestOverlayPermission() }
                    requestOverlayPermission()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        if (NetworkAnalyzerFloatingService.isRunning) {
            btnStart.text = "STOP NETWORK ANALYZER"
            btnStart.setTextColor(0xFF000000.toInt())
            btnStart.setBackgroundColor(0xFFFF5555.toInt())
        } else {
            btnStart.text = "START NETWORK ANALYZER"
            btnStart.setTextColor(0xFF000000.toInt())
            btnStart.setBackgroundColor(0xFFFFFFFF.toInt())
        }
    }

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQ_OVERLAY)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting overlay permission: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (hasOverlayPermission()) {
                launchNetworkService()
            } else {
                tvHint.visibility = android.view.View.VISIBLE
                tvHint.text = "Overlay permission is required to show the floating panel."
            }
        }
    }

    private fun launchNetworkService() {
        try {
            NetworkAnalyzerFloatingService.isRunning = true  // optimistic
            startService(Intent(this, NetworkAnalyzerFloatingService::class.java))
            refreshUi()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting network service: ${e.message}")
        }
    }

    private fun stopNetworkService() {
        try {
            NetworkAnalyzerFloatingService.isRunning = false  // optimistic
            stopService(Intent(this, NetworkAnalyzerFloatingService::class.java))
            // Also stop the underlying VPN if it's running
            startService(Intent(this, NetworkAnalyzerVpnService::class.java).apply {
                action = NetworkAnalyzerVpnService.ACTION_STOP
            })
            refreshUi()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping network service: ${e.message}")
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
