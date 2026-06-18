package com.rootdroid.inspector.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.rootdroid.inspector.model.*
import com.rootdroid.inspector.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorScreen(
    app: ManagedApp,
    icon: Drawable?,
    pid: Int,
    isRunning: Boolean,
    logs: List<LogEntry>,
    calls: List<FunctionCall>,
    memoryRegions: List<MemoryRegion>,
    openFiles: List<String>,
    processInfo: ProcessInfo?,
    onBack: () -> Unit,
    onLaunch: () -> Unit,
    onKill: () -> Unit,
    onToggleOverlay: () -> Unit,
    overlayActive: Boolean,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Logs", "Memory", "Files")

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecond)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (icon != null) {
                            Image(
                                bitmap = icon.toBitmap(28, 28).asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
                            )
                        }
                        Column {
                            Text(
                                app.appName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                app.packageName,
                                fontSize = 10.sp,
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (pid > 0) {
                        Text(
                            "PID $pid",
                            fontSize = 11.sp,
                            color = Accent,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .background(Accent.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(if (isRunning) StatusGreen else TextMuted, CircleShape)
                    )
                    Spacer(Modifier.width(16.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = Border, thickness = 0.5.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isRunning) {
                        OutlinedButton(
                            onClick = onKill,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Kill", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Button(
                            onClick = onLaunch,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                            shape = RoundedCornerShape(8.dp),
                            elevation = ButtonDefaults.buttonElevation(0.dp),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Launch", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    OutlinedButton(
                        onClick = onToggleOverlay,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (overlayActive) Accent else TextSecond,
                        ),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Overlay", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        containerColor = Background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            if (processInfo != null) {
                ProcessInfoBar(processInfo)
            }

            HorizontalDivider(color = Border, thickness = 0.5.dp)

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Surface,
                contentColor = Accent,
                divider = {},
            ) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = {
                            Text(
                                label,
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == i) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        selectedContentColor = Accent,
                        unselectedContentColor = TextSecond,
                    )
                }
            }

            when (selectedTab) {
                0 -> LogsTab(logs)
                1 -> MemoryTab(memoryRegions)
                2 -> FilesTab(openFiles)
            }
        }
    }
}

@Composable
private fun ProcessInfoBar(info: ProcessInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceMid)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StatItem("RSS", "${info.vmRssKb / 1024} MB")
        StatItem("VSZ", "${info.vmSizeKb / 1024} MB")
        StatItem("Threads", info.threads.toString())
        StatItem("State", info.state)
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(label, fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LogsTab(logs: List<LogEntry>) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    if (logs.isEmpty()) {
        Placeholder("Waiting for logcat…")
    } else {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
            items(logs, key = { it.id }) { LogRow(it) }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.V -> LogVerbose
        LogLevel.D -> LogDebug
        LogLevel.I -> LogInfo
        LogLevel.W -> LogWarn
        LogLevel.E -> LogError
        LogLevel.F -> LogFatal
        LogLevel.FRIDA -> LogFrida
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            entry.timestamp.takeLast(12),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = TextMuted,
            modifier = Modifier.width(72.dp),
        )
        Text(
            entry.level.name,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = levelColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(12.dp),
        )
        Text(
            "${entry.tag}: ${entry.message}",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = TextPrimary.copy(alpha = if (entry.level == LogLevel.V) 0.45f else 0.9f),
        )
    }
}

@Composable
private fun MemoryTab(regions: List<MemoryRegion>) {
    if (regions.isEmpty()) {
        Placeholder("Memory map not available\nLaunch app and wait for process scan")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(regions, key = { it.address }) { region ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(region.address, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Accent, modifier = Modifier.width(118.dp))
                    Text(region.perms, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = when {
                        "x" in region.perms -> LogWarn
                        "w" in region.perms -> LogInfo
                        else -> TextMuted
                    }, modifier = Modifier.width(30.dp))
                    Text("${region.sizeKb}K", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted, modifier = Modifier.width(44.dp))
                    Text(region.name, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun FilesTab(files: List<String>) {
    if (files.isEmpty()) {
        Placeholder("Open file descriptors not available\nLaunch app and wait for process scan")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(files) { file ->
                Text(
                    file,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                HorizontalDivider(color = BorderSub, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun Placeholder(msg: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            msg,
            fontSize = 13.sp,
            color = TextMuted,
            lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

