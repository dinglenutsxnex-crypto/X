package com.rootdroid.inspector.virtual

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.rootdroid.inspector.model.LogEntry
import com.rootdroid.inspector.model.LogLevel
import com.rootdroid.inspector.model.ProcessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Core engine for the virtual space.
 *
 * Non-root host device is fully supported:
 *  - FakeSuProvider installs fake binaries + env injection at startup
 *  - Apps launched inside the space inherit the modified PATH, making
 *    existence-style root checks pass transparently
 *  - For deeper in-process hooking (apps loaded via DexClassLoader), the
 *    fakeResponses map is consulted before any real exec
 */
object ContainerEngine {

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun init(context: Context) {
        FakeSuProvider.install(context)
    }

    // ── App launch ────────────────────────────────────────────────────────────

    fun launchInSpace(context: Context, packageName: String) {
        val intent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) context.startActivity(intent)
    }

    // ── Shell exec with fake-root intercept ───────────────────────────────────

    /**
     * Runs [cmd] in a child shell with PATH prepended to our fake binaries.
     * Fake-root responses are checked first before touching the OS.
     */
    suspend fun exec(context: Context, cmd: String): String = withContext(Dispatchers.IO) {
        // 1. Check fake-root shortcut first
        FakeSuProvider.fakeResponse(cmd)?.let { return@withContext it }

        // 2. Real exec with injected PATH
        val fakeBin = FakeSuProvider.fakeBinPath(context)
        val currentPath = System.getenv("PATH") ?: "/system/bin:/system/xbin"
        val env = arrayOf("PATH=$fakeBin:$currentPath")

        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd), env)
            val out = proc.inputStream.bufferedReader().readText().trim()
            val err = proc.errorStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (out.isNotEmpty()) out else err
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    // ── Process tracking (best-effort, no root needed) ────────────────────────

    suspend fun findPid(context: Context, packageName: String): Int =
        withContext(Dispatchers.IO) {
            exec(context, "pidof $packageName").trim()
                .split(" ").lastOrNull()?.toIntOrNull() ?: -1
        }

    suspend fun killProcess(context: Context, pid: Int) = withContext(Dispatchers.IO) {
        if (pid > 0) exec(context, "kill -9 $pid")
    }

    suspend fun getProcessInfo(context: Context, pid: Int): ProcessInfo? =
        withContext(Dispatchers.IO) {
            if (pid <= 0) return@withContext null
            val status = exec(context, "cat /proc/$pid/status 2>/dev/null")
            if (status.isBlank() || status.startsWith("error")) return@withContext null
            fun field(name: String) = status.lines()
                .find { it.startsWith("$name:") }?.substringAfter(":")?.trim() ?: "?"
            ProcessInfo(
                pid = pid,
                name = field("Name"),
                state = field("State").take(1),
                threads = field("Threads").toIntOrNull() ?: 0,
                vmRssKb = field("VmRSS").replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L,
                vmSizeKb = field("VmSize").replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L,
            )
        }

    suspend fun getOpenFiles(context: Context, pid: Int): List<String> =
        withContext(Dispatchers.IO) {
            if (pid <= 0) return@withContext emptyList()
            exec(context, "ls -la /proc/$pid/fd 2>/dev/null")
                .lines().drop(1).filter { it.isNotBlank() }
        }

    // ── Logcat stream (own process logs — no root needed) ────────────────────

    fun streamLogcat(pid: Int): Flow<LogEntry> = flow {
        val fakeBin = System.getProperty("vs.fake_bin_path", "")
        val currentPath = System.getenv("PATH") ?: "/system/bin:/system/xbin"
        val env = if (fakeBin.isNotEmpty())
            arrayOf("PATH=$fakeBin:$currentPath") else null

        val proc = try {
            Runtime.getRuntime().exec(
                arrayOf("logcat", if (pid > 0) "--pid=$pid" else "-d", "-v", "time"),
                env
            )
        } catch (_: Exception) { return@flow }

        proc.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                parseLogLine(line)?.let { emit(it) }
                line = reader.readLine()
            }
        }
        proc.destroy()
    }.flowOn(Dispatchers.IO)

    private fun parseLogLine(line: String): LogEntry? {
        if (line.isBlank() || line.startsWith("-----")) return null
        val level = when {
            " V " in line -> LogLevel.V
            " D " in line -> LogLevel.D
            " I " in line -> LogLevel.I
            " W " in line -> LogLevel.W
            " E " in line -> LogLevel.E
            " F " in line -> LogLevel.F
            else -> LogLevel.D
        }
        val colon = line.indexOf(':', 20)
        val tag = if (colon > 0) line.substring(0, colon).trim().takeLast(20) else "sys"
        val msg  = if (colon > 0 && colon + 1 < line.length) line.substring(colon + 1).trim() else line
        return LogEntry(timestamp = line.take(18).trim(), level = level, tag = tag, message = msg)
    }

    // ── In-process app loading (fake-root intercept) ──────────────────────────

    /**
     * Loads the target APK into our process via DexClassLoader so that
     * any Runtime.exec("su") call from that code hits our fake su binary.
     * Returns a status string displayed in the space UI.
     */
    fun loadInProcess(context: Context, packageName: String): String {
        val result = AppLoader.load(context, packageName)
        if (result.classLoader == null) return "Load failed: ${result.error}"
        val appMsg = AppLoader.invokeApplication(context, packageName, result.classLoader)
        return appMsg
    }

    fun probeRootChecks(context: Context, packageName: String): Map<String, String> {
        val result = AppLoader.load(context, packageName)
        if (result.classLoader == null) return mapOf("error" to (result.error ?: "unknown"))
        return AppLoader.probeRootChecks(context, result.classLoader)
    }

    // ── Root simulation status ────────────────────────────────────────────────

    fun rootSimulationActive(context: Context): Boolean =
        FakeSuProvider.fakeBinPath(context).let { java.io.File(it, "su").exists() }
}
