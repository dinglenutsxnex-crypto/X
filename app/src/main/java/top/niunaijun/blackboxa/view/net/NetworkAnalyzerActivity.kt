package top.niunaijun.blackboxa.view.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackboxa.R

class NetworkAnalyzerActivity : AppCompatActivity() {

    companion object {
        private const val REQ_OVERLAY = 1001

        fun start(context: Context) =
            context.startActivity(Intent(context, NetworkAnalyzerActivity::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debugger) // Reuse simple launcher layout

        if (checkOverlayPermission()) {
            startFloatingService()
            finish()
        } else {
            requestOverlayPermission()
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQ_OVERLAY)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (checkOverlayPermission()) {
                startFloatingService()
                finish()
            } else {
                Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startFloatingService() {
        startService(Intent(this, NetworkAnalyzerFloatingService::class.java))
    }
}
