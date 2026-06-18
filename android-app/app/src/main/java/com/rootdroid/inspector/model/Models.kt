package com.rootdroid.inspector.model

import kotlinx.serialization.Serializable

@Serializable
data class ManagedApp(
    val packageName: String,
    val appName: String,
    val addedAt: Long = System.currentTimeMillis(),
)

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable?,
)

enum class LogLevel { V, D, I, W, E, F, FRIDA }

data class LogEntry(
    val id: String = System.nanoTime().toString(),
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

data class FunctionCall(
    val id: String = System.nanoTime().toString(),
    val timestamp: String,
    val className: String,
    val methodName: String,
    val args: List<String>,
    val returnValue: String,
    val durationMs: Long,
)

data class MemoryRegion(
    val address: String,
    val perms: String,
    val name: String,
    val sizeKb: Long,
)

data class ProcessInfo(
    val pid: Int,
    val name: String,
    val state: String,
    val threads: Int,
    val vmRssKb: Long,
    val vmSizeKb: Long,
)

sealed class InspectorTab(val label: String, val index: Int) {
    object Logs : InspectorTab("LOGS", 0)
    object Calls : InspectorTab("CALLS", 1)
    object Memory : InspectorTab("MEMORY", 2)
    object Files : InspectorTab("FILES", 3)
}
