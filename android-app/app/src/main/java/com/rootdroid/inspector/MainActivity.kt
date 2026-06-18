package com.rootdroid.inspector

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.rootdroid.inspector.model.InstalledApp
import com.rootdroid.inspector.repository.InstalledAppsRepository
import com.rootdroid.inspector.ui.AppPickerSheet
import com.rootdroid.inspector.ui.HomeScreen
import com.rootdroid.inspector.ui.theme.RootDroidTheme
import com.rootdroid.inspector.virtual.ContainerApp
import com.rootdroid.inspector.virtual.ContainerManager
import com.rootdroid.inspector.virtual.FakeSuProvider
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private lateinit var repo: InstalledAppsRepository

    // Fires Android's system overlay-permission settings page — no custom screen
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* permission change picked up in onResume via recomposition */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = InstalledAppsRepository(this)
        FakeSuProvider.install(this)

        // Ask for overlay permission immediately using the Android system UI
        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        setContent {
            RootDroidTheme {
                VirtualSpaceApp(activity = this, repo = repo)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun VirtualSpaceApp(activity: MainActivity, repo: InstalledAppsRepository) {
    val scope = rememberCoroutineScope()

    var containerApps by remember { mutableStateOf<List<ContainerApp>>(emptyList()) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loadingApps   by remember { mutableStateOf(false) }
    var showPicker    by remember { mutableStateOf(false) }
    var installingPkg by remember { mutableStateOf<String?>(null) }

    // Load container list on enter and whenever we come back from ContainerHostActivity
    LaunchedEffect(Unit) { containerApps = ContainerManager.list(activity) }

    fun refresh() { containerApps = ContainerManager.list(activity) }

    fun installApp(app: InstalledApp) {
        if (containerApps.any { it.packageName == app.packageName }) { showPicker = false; return }
        showPicker    = false
        installingPkg = app.packageName
        scope.launch {
            ContainerManager.install(activity, app.packageName)
            installingPkg = null
            refresh()
        }
    }

    HomeScreen(
        containerApps = containerApps,
        installingPkg = installingPkg,
        onAddApp = {
            showPicker  = true
            loadingApps = true
            scope.launch { installedApps = repo.getAllInstalledApps(); loadingApps = false }
        },
        onLaunch = { app -> ContainerManager.launch(activity, app.packageName) },
        onRemove = { app ->
            scope.launch(Dispatchers.IO) { ContainerManager.uninstallAsync(activity, app.packageName) }
            refresh()
        },
        getIcon = { pkg -> repo.getIcon(pkg) },
    )

    if (showPicker) {
        AppPickerSheet(
            apps      = installedApps,
            isLoading = loadingApps,
            onSelect  = { installApp(it) },
            onDismiss = { showPicker = false },
        )
    }
}
