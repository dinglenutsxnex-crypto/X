package com.rootdroid.inspector.virtual

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class RunningProcess(
    val pid: Int,
    val processName: String,
    val appLabel: String,
    val isUserApp: Boolean,
    val isNew: Boolean = false,
)

object ProcessScanner {

    fun snapshot(context: Context): List<RunningProcess> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = context.packageManager
        return (am.runningAppProcesses ?: emptyList()).map { proc ->
            val pkg = proc.pkgList?.firstOrNull() ?: proc.processName
            val isUser = try {
                (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) == 0
            } catch (_: Exception) { false }
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { proc.processName.split(":").first().split(".").last() }
            RunningProcess(proc.pid, proc.processName, label, isUser)
        }.sortedWith(compareByDescending<RunningProcess> { it.isUserApp }.thenBy { it.appLabel })
    }

    /** Emits (full list, newly-appeared pids) every [intervalMs]. Never completes. */
    fun monitor(context: Context, intervalMs: Long = 2000L): Flow<Pair<List<RunningProcess>, Set<Int>>> = flow {
        var prevPids = emptySet<Int>()
        while (true) {
            val current = snapshot(context)
            val currentPids = current.map { it.pid }.toSet()
            val newPids = currentPids - prevPids
            emit(current.map { it.copy(isNew = it.pid in newPids) } to newPids)
            prevPids = currentPids
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)
}
