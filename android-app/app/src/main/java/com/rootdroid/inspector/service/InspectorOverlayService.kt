package com.rootdroid.inspector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rootdroid.inspector.MainActivity
import com.rootdroid.inspector.model.LogEntry
import com.rootdroid.inspector.model.LogLevel
import com.rootdroid.inspector.ui.theme.*
import com.rootdroid.inspector.virtual.ContainerApp
import com.rootdroid.inspector.virtual.ContainerEngine
import com.rootdroid.inspector.virtual.ContainerManager
import com.rootdroid.inspector.virtual.ContainerSession
import com.rootdroid.inspector.virtual.MethodEnumerator
import com.rootdroid.inspector.virtual.MethodInfo
import kotlinx.coroutines.*

class InspectorOverlayService : Service() {

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_PID     = "extra_pid"
        const val CHANNEL_ID    = "vs_overlay"
        const val NOTIF_ID      = 1001
    }

    private lateinit var wm: WindowManager
    private var view: ComposeView? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val expanded       = mutableStateOf(false)
    private val selectedTab    = mutableStateOf(0)
    private val attachedPkg    = mutableStateOf("")
    private val attachedPid    = mutableStateOf(-1)
    private val attachedLoader = mutableStateOf<dalvik.system.DexClassLoader?>(null)
    private val attachStatus   = mutableStateOf("No container attached")

    private val containerItems = mutableStateListOf<ContainerUI>()
    private val logs           = mutableStateListOf<LogEntry>()
    private val methods        = mutableStateListOf<MethodInfo>()
    private val memLines       = mutableStateListOf<String>()
    private val fdLines        = mutableStateListOf<String>()
    private val loadingMethods = mutableStateOf(false)

    private var logJob:     Job? = null
    private var pollJob:    Job? = null
    private var methodJob:  Job? = null
    private var monitorJob: Job? = null

    private val collapsedParams get() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 140 }

    private val expandedParams get() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

    private var currentParams = collapsedParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        startContainerMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: ""
        val pid = intent?.getIntExtra(EXTRA_PID, -1) ?: -1

        if (view == null) attachView()

        if (pkg.isNotEmpty()) {
            attachedPkg.value = pkg
            ContainerManager.activeSessions[pkg]?.let { attachToSession(it) }
                ?: run {
                    if (pid > 0) attachedPid.value = pid
                    attachStatus.value = "Waiting for $pkg…"
                }
            setExpanded(true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun startContainerMonitor() {
        monitorJob = scope.launch {
            while (true) {
                val installed = ContainerManager.list(this@InspectorOverlayService)
                val sessions  = ContainerManager.activeSessions

                containerItems.clear()
                containerItems.addAll(installed.map { ContainerUI(it, sessions[it.packageName]) })

                val target = attachedPkg.value
                if (target.isNotEmpty() && attachedPid.value <= 0) {
                    sessions[target]?.let { attachToSession(it) }
                }
                delay(2000)
            }
        }
    }

    private fun attachToSession(session: ContainerSession) {
        if (attachedPid.value == session.pid && attachedPkg.value == session.pkg) return

        attachedPkg.value    = session.pkg
        attachedPid.value    = session.pid
        attachedLoader.value = session.classLoader
        attachStatus.value   = "${session.pkg.split(".").last()} · PID ${session.pid}"

        logs.clear(); memLines.clear(); fdLines.clear()

        logJob?.cancel()
        logJob = scope.launch {
            // App runs in our process — logcat filtered by our PID works without root
            ContainerEngine.streamLogcat(session.pid).collect { entry ->
                if (logs.size >= 800) logs.removeAt(0)
                logs.add(entry)
            }
        }

        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                val pid = session.pid.takeIf { it > 0 } ?: break
                runCatching {
                    ContainerEngine.getOpenFiles(this@InspectorOverlayService, pid)
                        .also { if (it.isNotEmpty()) { fdLines.clear(); fdLines.addAll(it) } }
                }
                runCatching {
                    Runtime.getRuntime()
                        .exec(arrayOf("sh", "-c", "cat /proc/$pid/maps 2>/dev/null"))
                        .inputStream.bufferedReader().readText().trim()
                        .lines().filter { it.isNotBlank() }
                        .also { if (it.isNotEmpty()) { memLines.clear(); memLines.addAll(it) } }
                }
                delay(4000)
            }
        }
    }

    private fun loadMethods() {
        if (loadingMethods.value || attachedPkg.value.isEmpty()) return
        methodJob?.cancel()
        methodJob = scope.launch {
            loadingMethods.value = true
            val loader = attachedLoader.value
            val pkg    = attachedPkg.value
            val apk    = ContainerManager.apkFile(this@InspectorOverlayService, pkg).absolutePath
            val result = if (loader != null) {
                MethodEnumerator.enumerateFromLoader(loader, apk)
            } else {
                MethodEnumerator.enumerate(this@InspectorOverlayService, pkg)
            }
            methods.clear(); methods.addAll(result)
            loadingMethods.value = false
        }
    }

    private fun attachView() {
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                RootDroidTheme {
                    OverlayRoot(
                        expanded       = expanded.value,
                        selectedTab    = selectedTab.value,
                        attachedPkg    = attachedPkg.value,
                        attachedPid    = attachedPid.value,
                        attachStatus   = attachStatus.value,
                        containerItems = containerItems,
                        logs           = logs,
                        methods        = methods,
                        memLines       = memLines,
                        fdLines        = fdLines,
                        loadingMethods = loadingMethods.value,
                        onToggle       = { setExpanded(it) },
                        onTabSelect    = { tab ->
                            selectedTab.value = tab
                            if (tab == 2 && methods.isEmpty()) loadMethods()
                        },
                        onAttach = { session ->
                            attachToSession(session)
                            selectedTab.value = 1
                            setExpanded(true)
                        },
                        onClose  = { stopSelf() },
                        onDrag   = { dx, dy ->
                            currentParams.x = (currentParams.x + dx.toInt()).coerceIn(0, 2000)
                            currentParams.y = (currentParams.y + dy.toInt()).coerceIn(0, 3000)
                            view?.let { runCatching { wm.updateViewLayout(it, currentParams) } }
                        },
                    )
                }
            }
        }
        view = cv
        currentParams = collapsedParams
        try {
            wm.addView(cv, currentParams)
        } catch (e: Exception) {
            view = null
            stopSelf()
        }
    }

    private fun setExpanded(expand: Boolean) {
        expanded.value = expand
        currentParams = if (expand) expandedParams else collapsedParams
        view?.let { runCatching { wm.updateViewLayout(it, currentParams) } }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Virtual Space Overlay", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotif() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Virtual Space — overlay active")
        .setContentText(attachedPkg.value.ifEmpty { "No container attached" })
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentIntent(
            PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .setOngoing(true)
        .build()
}

private data class ContainerUI(
    val app: ContainerApp,
    val session: ContainerSession?,
)

@Composable
private fun OverlayRoot(
    expanded: Boolean,
    selectedTab: Int,
    attachedPkg: String,
    attachedPid: Int,
    attachStatus: String,
    containerItems: List<ContainerUI>,
    logs: List<LogEntry>,
    methods: List<MethodInfo>,
    memLines: List<String>,
    fdLines: List<String>,
    loadingMethods: Boolean,
    onToggle: (Boolean) -> Unit,
    onTabSelect: (Int) -> Unit,
    onAttach: (ContainerSession) -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    if (!expanded) {
        FloatingPill(
            attached = attachedPid > 0,
            runningCount = containerItems.count { it.session != null },
            onExpand = { onToggle(true) },
            onDrag = onDrag,
        )
    } else {
        ExpandedPanel(
            selectedTab    = selectedTab,
            attachedPkg    = attachedPkg,
            attachedPid    = attachedPid,
            attachStatus   = attachStatus,
            containerItems = containerItems,
            logs           = logs,
            methods        = methods,
            memLines       = memLines,
            fdLines        = fdLines,
            loadingMethods = loadingMethods,
            onTabSelect    = onTabSelect,
            onAttach       = onAttach,
            onMinimise     = { onToggle(false) },
            onClose        = onClose,
            onDrag         = onDrag,
        )
    }
}

@Composable
private fun FloatingPill(
    attached: Boolean,
    runningCount: Int,
    onExpand: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .pointerInput(Unit) { detectDragGestures { _, d -> onDrag(d.x, d.y) } }
            .clickable { onExpand() }
            .background(Surface, RoundedCornerShape(24.dp))
            .border(1.dp, if (attached) Accent.copy(alpha = 0.5f) else Border, RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(6.dp).background(if (attached) StatusGreen else TextMuted, CircleShape))
        Text("VS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        if (runningCount > 0) {
            Box(
                Modifier.background(Accent, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text("$runningCount", fontSize = 8.sp, color = Background, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private val TABS = listOf("APPS", "LOGS", "METHODS", "MEMORY", "FILES")

@Composable
private fun ExpandedPanel(
    selectedTab: Int,
    attachedPkg: String,
    attachedPid: Int,
    attachStatus: String,
    containerItems: List<ContainerUI>,
    logs: List<LogEntry>,
    methods: List<MethodInfo>,
    memLines: List<String>,
    fdLines: List<String>,
    loadingMethods: Boolean,
    onTabSelect: (Int) -> Unit,
    onAttach: (ContainerSession) -> Unit,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .background(Background.copy(alpha = 0.97f))
            .border(0.5.dp, Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .pointerInput(Unit) { detectDragGestures { _, d -> onDrag(d.x, d.y) } }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(6.dp).background(if (attachedPid > 0) StatusGreen else TextMuted, CircleShape))
                Column {
                    Text(
                        if (attachedPkg.isNotEmpty()) attachedPkg.split(".").last() else "Virtual Space",
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                    )
                    if (attachStatus.isNotEmpty()) {
                        Text(attachStatus, fontSize = 8.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Row {
                IconButton(onClick = onMinimise, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.UnfoldLess, null, tint = TextSecond, modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, null, tint = TextSecond, modifier = Modifier.size(14.dp))
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().background(SurfaceMid)) {
            TABS.forEachIndexed { i, label ->
                val active = selectedTab == i
                Column(
                    modifier = Modifier.weight(1f).clickable { onTabSelect(i) }.padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        fontSize = 8.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Accent else TextMuted,
                        letterSpacing = 0.3.sp,
                    )
                    if (active) {
                        Box(Modifier.padding(top = 2.dp).width(14.dp).height(1.5.dp)
                            .background(Accent, RoundedCornerShape(1.dp)))
                    }
                }
            }
        }
        HorizontalDivider(color = Border, thickness = 0.5.dp)

        when (selectedTab) {
            0 -> AppsTab(containerItems, attachedPkg, onAttach)
            1 -> LogsTab(logs, attachedPid)
            2 -> MethodsTab(methods, loadingMethods, attachedPkg)
            3 -> LinesTab(memLines, "Attach to a running container to see memory map")
            4 -> LinesTab(fdLines, "Attach to a running container to see open file descriptors")
        }
    }
}

@Composable
private fun AppsTab(
    items: List<ContainerUI>,
    attachedPkg: String,
    onAttach: (ContainerSession) -> Unit,
) {
    if (items.isEmpty()) {
        Hint("No apps in container space.\nAdd one from the main app.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.app.packageName }) { ui ->
            val running    = ui.session != null
            val isAttached = ui.app.packageName == attachedPkg

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(when {
                        isAttached -> Accent.copy(alpha = 0.08f)
                        running    -> StatusGreen.copy(alpha = 0.04f)
                        else       -> Color.Transparent
                    })
                    .clickable(enabled = running && !isAttached) { ui.session?.let { onAttach(it) } }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(6.dp).background(when {
                    isAttached -> Accent
                    running    -> StatusGreen
                    else       -> TextMuted
                }, CircleShape))
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ui.app.appName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = if (isAttached) Accent else TextPrimary)
                        if (running) Badge("running", StatusGreen)
                        if (isAttached) Badge("attached", Accent)
                    }
                    Text(ui.app.packageName, fontSize = 8.sp, color = TextMuted,
                        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ui.session?.let { Text("PID ${it.pid}", fontSize = 8.sp, color = TextSecond, fontFamily = FontFamily.Monospace) }
                }
                if (running && !isAttached) {
                    Text("ATTACH", fontSize = 8.sp, color = Accent, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Accent.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
            HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun LogsTab(logs: List<LogEntry>, pid: Int) {
    if (pid <= 0) { Hint("Tap ATTACH on a running container to stream its logs"); return }
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) state.animateScrollToItem(logs.size - 1) }
    if (logs.isEmpty()) { Hint("Streaming logcat for PID $pid…"); return }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
        items(logs.takeLast(500), key = { it.id }) { e ->
            val col = when (e.level) {
                LogLevel.V -> LogVerbose; LogLevel.D -> LogDebug; LogLevel.I -> LogInfo
                LogLevel.W -> LogWarn;    LogLevel.E -> LogError; LogLevel.F -> LogFatal
                LogLevel.FRIDA -> LogFrida
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(e.level.name, fontSize = 7.sp, color = col, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, modifier = Modifier.width(9.dp))
                Text("${e.tag}: ${e.message}", fontSize = 9.sp, color = TextPrimary.copy(alpha = 0.88f),
                    fontFamily = FontFamily.Monospace, lineHeight = 12.sp)
            }
        }
    }
}

@Composable
private fun MethodsTab(methods: List<MethodInfo>, loading: Boolean, pkg: String) {
    when {
        pkg.isEmpty() -> Hint("Attach to a container, then tap METHODS to enumerate its classes")
        loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("Enumerating…", fontSize = 9.sp, color = TextMuted)
            }
        }
        methods.isEmpty() -> Hint("No methods found")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(methods, key = { "${it.fullClass}.${it.methodName}.${it.params}" }) { m ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (m.isNative) Badge("NAT", StatusYellow)
                            Text(m.shortClass, fontSize = 8.sp, color = Accent, fontFamily = FontFamily.Monospace)
                            Text(".${m.methodName}", fontSize = 8.sp, color = TextPrimary,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        }
                        Text("(${m.params}) → ${m.returnType}", fontSize = 7.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun LinesTab(lines: List<String>, emptyHint: String) {
    if (lines.isEmpty()) { Hint(emptyHint); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(lines) { line ->
            Text(line, fontSize = 8.sp, color = TextPrimary, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp), lineHeight = 12.sp)
            HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun Hint(msg: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(msg, fontSize = 10.sp, color = TextMuted, textAlign = TextAlign.Center,
            lineHeight = 15.sp, modifier = Modifier.padding(20.dp))
    }
}

@Composable
private fun Badge(label: String, color: Color) {
    Text(label, fontSize = 7.sp, color = color, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp))
}
