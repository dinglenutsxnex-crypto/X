package com.rootdroid.inspector.virtual

import android.content.Context
import android.content.Intent
import android.os.Process
import com.rootdroid.inspector.ContainerHostActivity
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ContainerApp(
    val packageName: String,
    val appName: String,
    val installedAt: Long = System.currentTimeMillis(),
    val apkSizeBytes: Long = 0L,
)

/**
 * Tracks a live container session — an app running inside the container user
 * (or loaded in-process via DexClassLoader for inspection).
 */
data class ContainerSession(
    val pkg: String,
    val pid: Int,
    val classLoader: DexClassLoader?,
    val apkPath: String,
    val startedAt: Long = System.currentTimeMillis(),
)

object ContainerManager {

    private const val PREFS = "vs_container_registry"
    private const val KEY   = "apps_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** In-process sessions / active container sessions. */
    val activeSessions: ConcurrentHashMap<String, ContainerSession> = ConcurrentHashMap()

    // ── Paths ─────────────────────────────────────────────────────────────────

    private fun root(ctx: Context)           = File(ctx.filesDir, "containers")
    fun apkFile(ctx: Context, pkg: String)   = File(root(ctx), "$pkg/base.apk")
    fun dataDir(ctx: Context, pkg: String)   = File(root(ctx), "$pkg/data").also { it.mkdirs() }
    fun optDir(ctx: Context, pkg: String)    = File(root(ctx), "$pkg/opt").also  { it.mkdirs() }
    fun isInstalled(ctx: Context, pkg: String) = apkFile(ctx, pkg).exists()

    // ── Session registry ──────────────────────────────────────────────────────

    fun registerSession(pkg: String, pid: Int, loader: DexClassLoader?, apkPath: String) {
        activeSessions[pkg] = ContainerSession(pkg, pid, loader, apkPath)
    }

    fun unregisterSession(pkg: String) {
        activeSessions.remove(pkg)
    }

    // ── Install ───────────────────────────────────────────────────────────────

    /**
     * Copies the host-installed APK into our private dir (read-only, required
     * by Android 8+ DexClassLoader security check).
     */
    suspend fun install(ctx: Context, pkg: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val pm    = ctx.packageManager
            val info  = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(info).toString()

            val dst = apkFile(ctx, pkg).also { it.parentFile!!.mkdirs() }
            // Make writable before copy in case of re-install over read-only file
            if (dst.exists()) dst.setWritable(true, false)
            File(info.sourceDir).copyTo(dst, overwrite = true)
            // Android 8+ (API 26+): DexClassLoader rejects writable dex paths
            dst.setWritable(false, false)
            dst.setReadable(true, false)

            // Copy splits if any
            info.splitSourceDirs?.forEachIndexed { i, split ->
                val splitDst = File(dst.parentFile!!, "split_$i.apk")
                if (splitDst.exists()) splitDst.setWritable(true, false)
                File(split).copyTo(splitDst, overwrite = true)
                splitDst.setWritable(false, false)
                splitDst.setReadable(true, false)
            }

            dataDir(ctx, pkg); optDir(ctx, pkg)

            val apps = list(ctx).toMutableList()
            if (apps.none { it.packageName == pkg }) {
                apps += ContainerApp(pkg, label, apkSizeBytes = dst.length())
                saveList(ctx, apps)
            }
            true
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    // ── Uninstall ─────────────────────────────────────────────────────────────

    fun uninstall(ctx: Context, pkg: String) {
        // Read-only APKs need to be made writable before deleteRecursively
        File(root(ctx), pkg).walkBottomUp().forEach { it.setWritable(true, false) }
        File(root(ctx), pkg).deleteRecursively()
        activeSessions.remove(pkg)
        saveList(ctx, list(ctx).filter { it.packageName != pkg })
    }

    suspend fun uninstallAsync(ctx: Context, pkg: String) = withContext(Dispatchers.IO) {
        uninstall(ctx, pkg)
    }

    // ── List ──────────────────────────────────────────────────────────────────

    fun list(ctx: Context): List<ContainerApp> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try { json.decodeFromString(raw) } catch (_: Exception) { emptyList() }
    }

    // ── Launch via ContainerHostActivity ──────────────────────────────────────

    fun launch(ctx: Context, pkg: String) {
        ctx.startActivity(
            Intent(ctx, ContainerHostActivity::class.java)
                .putExtra(ContainerHostActivity.EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun saveList(ctx: Context, apps: List<ContainerApp>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.encodeToString(apps)).apply()
    }
}
