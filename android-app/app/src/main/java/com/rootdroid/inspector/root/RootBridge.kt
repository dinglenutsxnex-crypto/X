package com.rootdroid.inspector.root

import android.util.Log
import com.rootdroid.inspector.model.FunctionCall
import com.rootdroid.inspector.model.LogEntry
import com.rootdroid.inspector.model.LogLevel
import com.rootdroid.inspector.model.MemoryRegion
import com.rootdroid.inspector.model.ProcessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RootBridge {
    private val TAG = "RootBridge"
    private val TIME_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Returns true if su is available and grants root. */
    suspend fun isRooted(): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readLine() ?: return@withContext false
            p.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        }
    }

    /** Execute a root command and return stdout as a string. */
    suspend fun exec(cmd: String): String = withContext(Dispatchers.IO) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $cmd", e)
            ""
        }
    }

    /** Stream logcat output for a specific PID as LogEntry Flow. */
    fun streamLogcat(pid: Int): Flow<LogEntry> = callbackFlow {
        val process = try {
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "logcat -v time --pid=$pid -b all")
            )
        } catch (e: Exception) {
            Log.e(TAG, "logcat launch failed", e)
            close(e)
            return@callbackFlow
        }

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val entry = parseLogcatLine(line!!) ?: continue
                trySend(entry)
            }
        } catch (e: Exception) {
            Log.e(TAG, "logcat read error", e)
        } finally {
            reader.close()
            process.destroy()
        }

        awaitClose { process.destroy() }
    }.flowOn(Dispatchers.IO)

    /** Stream all logcat output (no PID filter) as LogEntry Flow. */
    fun streamAllLogcat(): Flow<LogEntry> = callbackFlow {
        val process = try {
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "logcat -v time -b all")
            )
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val entry = parseLogcatLine(line!!) ?: continue
                trySend(entry)
            }
        } finally {
            reader.close()
            process.destroy()
        }

        awaitClose { process.destroy() }
    }.flowOn(Dispatchers.IO)

    /** Parse /proc/<pid>/maps into MemoryRegion list. */
    suspend fun getMemoryMaps(pid: Int): List<MemoryRegion> = withContext(Dispatchers.IO) {
        val raw = exec("cat /proc/$pid/maps")
        raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            // format: address perms offset dev inode pathname
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 2) return@mapNotNull null
            val address = parts[0]
            val perms = parts[1]
            val name = parts.getOrElse(5) { "[anon]" }
            val (start, end) = address.split("-").map { it.toLong(16) }
            val sizeKb = (end - start) / 1024
            MemoryRegion(address, perms, name, sizeKb)
        }
    }

    /** Read /proc/<pid>/status for process stats. */
    suspend fun getProcessInfo(pid: Int): ProcessInfo? = withContext(Dispatchers.IO) {
        val status = exec("cat /proc/$pid/status")
        if (status.isBlank()) return@withContext null
        val map = status.lines().associate { line ->
            val (k, v) = line.split(":").let { Pair(it[0].trim(), it.getOrElse(1) { "" }.trim()) }
            k to v
        }
        ProcessInfo(
            pid = pid,
            name = map["Name"] ?: "unknown",
            state = map["State"] ?: "?",
            threads = map["Threads"]?.toIntOrNull() ?: 0,
            vmRssKb = map["VmRSS"]?.replace("[^0-9]".toRegex(), "")?.toLongOrNull() ?: 0L,
            vmSizeKb = map["VmSize"]?.replace("[^0-9]".toRegex(), "")?.toLongOrNull() ?: 0L,
        )
    }

    /** Get open file descriptors for a PID. */
    suspend fun getOpenFiles(pid: Int): List<String> = withContext(Dispatchers.IO) {
        exec("ls -la /proc/$pid/fd 2>/dev/null").lines().filter { it.isNotBlank() }
    }

    /** Find PID for a package name. Returns -1 if not found. */
    suspend fun findPid(packageName: String): Int = withContext(Dispatchers.IO) {
        val out = exec("pidof $packageName").trim()
        out.split(" ").firstOrNull()?.toIntOrNull() ?: -1
    }

    /** Launch an app via am start. */
    suspend fun launchApp(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val result = exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        !result.contains("error", ignoreCase = true)
    }

    /** Kill a process by PID. */
    suspend fun killProcess(pid: Int): Boolean = withContext(Dispatchers.IO) {
        exec("kill -9 $pid").isNotBlank().let { true }
    }

    /** Monitor function calls via strace (requires strace binary on device). */
    fun streamSyscalls(pid: Int): Flow<FunctionCall> = flow {
        // strace intercepts syscalls; parse output into FunctionCall entries
        val process = try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "strace -p $pid -f -e trace=all 2>&1"))
        } catch (e: Exception) {
            return@flow
        }
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val fc = parseSyscallLine(line!!) ?: continue
                emit(fc)
            }
        } finally {
            reader.close()
            process.destroy()
        }
    }.flowOn(Dispatchers.IO)

    // --- Parsers ---

    private val LOGCAT_REGEX = Regex(
        """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*:\s*(.*)$"""
    )

    private fun parseLogcatLine(line: String): LogEntry? {
        val m = LOGCAT_REGEX.matchEntire(line.trim()) ?: return null
        val (ts, lvl, tag, msg) = m.destructured
        return LogEntry(
            timestamp = ts,
            level = when (lvl) {
                "V" -> LogLevel.V
                "D" -> LogLevel.D
                "I" -> LogLevel.I
                "W" -> LogLevel.W
                "E" -> LogLevel.E
                "F" -> LogLevel.F
                else -> LogLevel.D
            },
            tag = tag.trim(),
            message = msg.trim(),
        )
    }

    private val STRACE_REGEX = Regex("""^(?:\[pid\s+\d+\]\s+)?(\w+)\((.*?)\)\s+=\s+(.+)$""")

    private fun parseSyscallLine(line: String): FunctionCall? {
        val m = STRACE_REGEX.matchEntire(line.trim()) ?: return null
        val (syscall, args, ret) = m.destructured
        return FunctionCall(
            timestamp = TIME_FMT.format(Date()),
            className = "syscall",
            methodName = syscall,
            args = args.split(",").map { it.trim() },
            returnValue = ret.trim(),
            durationMs = 0,
        )
    }
}
