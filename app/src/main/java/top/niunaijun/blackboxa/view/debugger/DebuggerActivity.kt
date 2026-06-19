package top.niunaijun.blackboxa.view.debugger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackboxa.R

class DebuggerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DebuggerActivity"
        private const val REQUEST_OVERLAY_PERMISSION = 2001

        fun start(context: Context) {
            val intent = Intent(context, DebuggerActivity::class.java)
            context.startActivity(intent)
        }
    }

    private lateinit var btnStart: TextView
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debugger)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Debugger"
        }

        btnStart = findViewById(R.id.btn_start_debugger)
        tvHint = findViewById(R.id.tv_overlay_hint)

        btnStart.setOnClickListener {
            if (DebuggerFloatingService.isRunning) {
                stopDebuggerService()
            } else {
                if (Settings.canDrawOverlays(this)) {
                    launchDebuggerService()
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
        if (DebuggerFloatingService.isRunning) {
            btnStart.text = "STOP DEBUGGER"
            btnStart.setTextColor(0xFF000000.toInt())
            btnStart.setBackgroundColor(0xFFFF5555.toInt())
        } else {
            btnStart.text = "START DEBUGGER"
            btnStart.setTextColor(0xFF000000.toInt())
            btnStart.setBackgroundColor(0xFFFFFFFF.toInt())
        }
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting overlay permission: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                launchDebuggerService()
            }
        }
    }

    private fun launchDebuggerService() {
        try {
            DebuggerFloatingService.isRunning = true  // optimistic — service sets true in onCreate too
            startService(Intent(this, DebuggerFloatingService::class.java))
            refreshUi()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting debugger service: ${e.message}")
        }
    }

    private fun stopDebuggerService() {
        try {
            DebuggerFloatingService.isRunning = false  // optimistic — service sets false in onDestroy too
            stopService(Intent(this, DebuggerFloatingService::class.java))
            refreshUi()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping debugger service: ${e.message}")
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
