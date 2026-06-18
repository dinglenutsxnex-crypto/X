package com.rootdroid.inspector.virtual

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.content.res.Resources
import java.io.File

/**
 * ContextWrapper injected into a guest Application so that:
 *   - all file / db / cache paths go to the container data dir
 *   - getPackageName() returns the guest package name
 *   - getResources() returns resources loaded from the guest APK
 *   - getClassLoader() returns the guest DexClassLoader
 *
 * Everything else (system services, display metrics, configuration) comes
 * from the real host context — the guest code still runs inside our process.
 */
class ContainerContext(
    base: Context,
    val pkg: String,
    val containerDataDir: File,
    private val guestLoader: ClassLoader? = null,
    guestApkPath: String? = null,
) : ContextWrapper(base) {

    private val guestRes: Resources? = guestApkPath?.let { loadGuestResources(base, it) }

    // ── Resource / ClassLoader overrides ──────────────────────────────────────

    override fun getResources(): Resources = guestRes ?: super.getResources()
    override fun getClassLoader(): ClassLoader = guestLoader ?: super.getClassLoader()

    // ── File path overrides → isolated container data dir ────────────────────

    private fun sub(name: String) = File(containerDataDir, name).also { it.mkdirs() }

    override fun getFilesDir()         = sub("files")
    override fun getCacheDir()         = sub("cache")
    override fun getCodeCacheDir()     = sub("code_cache")
    override fun getNoBackupFilesDir() = sub("no_backup")
    override fun getDir(name: String, mode: Int) = sub("app_$name")

    override fun getDatabasePath(name: String): File = File(sub("databases"), name)

    override fun getExternalFilesDir(type: String?): File =
        File(containerDataDir, if (type != null) "external/$type" else "external").also { it.mkdirs() }

    // ── Identity overrides ─────────────────────────────────────────────────────

    override fun getPackageName(): String = pkg

    override fun getPackageCodePath(): String =
        File(containerDataDir.parentFile!!, "base.apk").absolutePath

    override fun getPackageResourcePath(): String =
        File(containerDataDir.parentFile!!, "base.apk").absolutePath

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences("vs__${pkg}__$name", mode)

    companion object {
        /**
         * Creates an AssetManager that points at the guest APK's resources.
         * Uses the hidden AssetManager.addAssetPath() API — same technique used by
         * VirtualApp, DroidPlugin, and all real virtual container frameworks.
         */
        private fun loadGuestResources(ctx: Context, apkPath: String): Resources? {
            return try {
                val amc = AssetManager::class.java
                val am  = amc.getDeclaredConstructor().newInstance()
                amc.getDeclaredMethod("addAssetPath", String::class.java)
                    .apply { isAccessible = true }
                    .invoke(am, apkPath)
                @Suppress("DEPRECATION")
                Resources(am, ctx.resources.displayMetrics, ctx.resources.configuration)
            } catch (_: Throwable) { null }
        }
    }
}
