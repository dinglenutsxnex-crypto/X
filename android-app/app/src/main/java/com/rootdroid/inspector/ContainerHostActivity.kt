package com.rootdroid.inspector

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rootdroid.inspector.service.InspectorOverlayService
import com.rootdroid.inspector.ui.theme.*
import com.rootdroid.inspector.virtual.AppLoader
import com.rootdroid.inspector.virtual.ContainerManager
import com.rootdroid.inspector.virtual.FakeSuProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContainerHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    private lateinit var pkg: String
    private val statusState = mutableStateOf("Starting container…")

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> loadContainer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }

        installCrashNet()

        setContent {
            RootDroidTheme {
                val status by statusState
                LaunchScreen(pkg = pkg, status = status)
            }
        }

        FakeSuProvider.install(this)
        System.setProperty("vs.fake_bin_path", FakeSuProvider.fakeBinPath(this))

        val apkFile = ContainerManager.apkFile(this, pkg)
        if (!apkFile.exists()) {
            showToast("Container APK missing — remove and re-add the app")
            finish()
            return
        }

        val missingPerms = parseApkPermissions(apkFile.absolutePath)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (missingPerms.isNotEmpty()) {
            statusState.value = "Requesting ${missingPerms.size} permission(s)…"
            permLauncher.launch(missingPerms.toTypedArray())
        } else {
            loadContainer()
        }
    }

    private fun loadContainer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val apkFile = ContainerManager.apkFile(this@ContainerHostActivity, pkg)
                val optDir  = ContainerManager.optDir(this@ContainerHostActivity, pkg)
                val dataDir = ContainerManager.dataDir(this@ContainerHostActivity, pkg)

                // ── Step 1: Make APK read-only (Android 8+ DexClassLoader requirement) ──
                withContext(Dispatchers.Main) { statusState.value = "Loading dex…" }
                if (apkFile.canWrite()) {
                    apkFile.setWritable(false, false)
                    apkFile.setReadable(true, false)
                }

                // ── Step 2: Load dex in-process for inspection + Application init ──
                val result = AppLoader.loadFromPath(
                    apkPath      = apkFile.absolutePath,
                    optDir       = optDir.absolutePath,
                    nativeLibDir = null,
                    parentLoader = classLoader,
                )

                if (result.classLoader == null) {
                    withContext(Dispatchers.Main) {
                        showToast("Dex load failed: ${result.error}")
                        finish()
                    }
                    return@launch
                }

                // ── Step 3: Register session (our PID — app runs in our process) ──
                val pid = Process.myPid()
                ContainerManager.registerSession(
                    pkg     = pkg,
                    pid     = pid,
                    loader  = result.classLoader,
                    apkPath = apkFile.absolutePath,
                )

                // ── Step 4: Invoke Application.onCreate() with ContainerContext ──
                withContext(Dispatchers.Main) { statusState.value = "Initialising app…" }
                val appMsg = AppLoader.invokeApplication(
                    context     = this@ContainerHostActivity,
                    packageName = pkg,
                    loader      = result.classLoader,
                    dataDir     = dataDir,
                    apkPath     = apkFile.absolutePath,
                )
                android.util.Log.d("ContainerHost", "invokeApplication: $appMsg")

                // ── Step 5: Start overlay ──
                withContext(Dispatchers.Main) {
                    statusState.value = "Container running · PID $pid"

                    if (Settings.canDrawOverlays(this@ContainerHostActivity)) {
                        startForegroundService(
                            Intent(this@ContainerHostActivity, InspectorOverlayService::class.java)
                                .putExtra(InspectorOverlayService.EXTRA_PACKAGE, pkg)
                                .putExtra(InspectorOverlayService.EXTRA_PID, pid)
                        )
                    }

                    Handler(Looper.getMainLooper()).postDelayed({ finish() }, 800)
                }

            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    showToast("Container error: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
                    finish()
                }
            }
        }
    }

    private fun installCrashNet() {
        val main = Handler(Looper.getMainLooper())
        Thread.setDefaultUncaughtExceptionHandler { _, t ->
            main.post {
                if (!isFinishing) showToast("Crash: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
            }
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun parseApkPermissions(apkPath: String): List<String> {
        return try {
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_PERMISSIONS)
            info?.requestedPermissions
                ?.filter { it.startsWith("android.permission.") && isDangerous(it) }
                ?: emptyList()
        } catch (_: Throwable) { emptyList() }
    }

    private fun isDangerous(perm: String): Boolean = try {
        (packageManager.getPermissionInfo(perm, 0).protectionLevel
                and PermissionInfo.PROTECTION_DANGEROUS) != 0
    } catch (_: Throwable) { false }

    override fun onDestroy() {
        super.onDestroy()
        // Don't unregister — the container user keeps running in the background
        // and the overlay service stays attached to it
    }
}

@Composable
private fun LaunchScreen(pkg: String, status: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    pkg.split(".").last(),
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                )
                Text(pkg, fontSize = 10.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
            }
            Box(
                modifier = Modifier
                    .background(SurfaceHigh, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    status,
                    fontSize = 11.sp, color = TextSecond, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
