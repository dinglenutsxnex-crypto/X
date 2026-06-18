package com.rootdroid.inspector.virtual

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dalvik.system.DexClassLoader
import java.io.File
import java.util.zip.ZipInputStream

object AppLoader {

    data class LoadResult(
        val classLoader: DexClassLoader?,
        val apkPath: String,
        val nativeLibDir: String? = null,
        val error: String? = null,
    )

    /**
     * Load an APK from a concrete path into a new DexClassLoader.
     * Extracts native libs from the APK's lib/ directory first so the classloader
     * can dlopen them.
     */
    fun loadFromPath(
        apkPath: String,
        optDir: String,
        nativeLibDir: String?,
        parentLoader: ClassLoader,
    ): LoadResult {
        return try {
            val libDir = File(optDir).parentFile?.let { File(it, "lib") } ?: File(optDir, "lib")
            val extractedLibDir = extractNativeLibs(apkPath, libDir)

            val effectiveLibDir = when {
                extractedLibDir.isNotEmpty() -> extractedLibDir
                nativeLibDir != null         -> nativeLibDir
                else                         -> null
            }

            val loader = DexClassLoader(apkPath, optDir, effectiveLibDir, parentLoader)
            LoadResult(classLoader = loader, apkPath = apkPath, nativeLibDir = effectiveLibDir)
        } catch (t: Throwable) {
            LoadResult(classLoader = null, apkPath = apkPath, error = "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun load(context: Context, packageName: String): LoadResult {
        return try {
            val info   = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val optDir = context.getDir("opt_${packageName.replace('.', '_')}", Context.MODE_PRIVATE)
            FakeSuProvider.install(context)
            loadFromPath(
                apkPath      = info.sourceDir,
                optDir       = optDir.absolutePath,
                nativeLibDir = info.nativeLibraryDir,
                parentLoader = context.classLoader,
            )
        } catch (t: Throwable) {
            LoadResult(classLoader = null, apkPath = "", error = "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Invoke the guest app's Application class in-process using a ContainerContext.
     *
     * Uses AssetManager.addAssetPath() so guest resources load correctly.
     * Catches Throwable (not just Exception) so Error subclasses like
     * NoClassDefFoundError, UnsatisfiedLinkError, StackOverflowError are all
     * captured and returned as an error string instead of crashing the host.
     *
     * Never throws — always returns a status string.
     */
    fun invokeApplication(
        context: Context,
        packageName: String,
        loader: DexClassLoader,
        dataDir: File? = null,
        apkPath: String? = null,
    ): String {
        return try {
            val info = try {
                context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            } catch (_: Throwable) { null }

            val appClassName = info?.className?.takeIf { it.isNotBlank() }
                ?: return "No Application class — skipped (normal for many apps)"

            val clazz = try {
                loader.loadClass(appClassName)
            } catch (t: Throwable) {
                return "ClassLoad failed: ${t.javaClass.simpleName}: ${t.message?.take(80)}"
            }

            val appCtx: Context = if (dataDir != null) {
                ContainerContext(
                    base          = context,
                    pkg           = packageName,
                    containerDataDir = dataDir,
                    guestLoader   = loader,
                    guestApkPath  = apkPath,
                )
            } else {
                context
            }

            val instance = try {
                clazz.getDeclaredConstructor().newInstance()
            } catch (t: Throwable) {
                return "Instantiate failed: ${t.javaClass.simpleName}: ${t.message?.take(80)}"
            }

            try {
                clazz.superclass
                    ?.getDeclaredMethod("attach", Context::class.java)
                    ?.apply { isAccessible = true; invoke(instance, appCtx) }
            } catch (_: Throwable) {}

            try {
                clazz.getDeclaredMethod("onCreate")
                    .apply { isAccessible = true; invoke(instance) }
            } catch (t: Throwable) {
                return "onCreate threw: ${t.javaClass.simpleName}: ${t.message?.take(80)}"
            }

            "Loaded OK"
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message?.take(80)}"
        }
    }

    /**
     * Extracts native .so files from the APK's lib/ directory for the best
     * supported ABI. Returns the path of the lib dir on success, empty string
     * if the APK has no native libs.
     */
    fun extractNativeLibs(apkPath: String, libDir: File): String {
        return try {
            val preferredAbis = Build.SUPPORTED_ABIS.toList()

            var bestAbi: String? = null
            ZipInputStream(File(apkPath).inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                        val abi = entry.name.split("/").getOrNull(1) ?: ""
                        val rank = preferredAbis.indexOf(abi)
                        if (rank >= 0 && (bestAbi == null || preferredAbis.indexOf(bestAbi!!) > rank)) {
                            bestAbi = abi
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (bestAbi == null) return ""

            libDir.mkdirs()
            ZipInputStream(File(apkPath).inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory
                        && entry.name.startsWith("lib/$bestAbi/")
                        && entry.name.endsWith(".so")
                    ) {
                        val soName  = entry.name.substringAfterLast('/')
                        val outFile = File(libDir, soName)
                        if (!outFile.exists()) {
                            outFile.outputStream().buffered().use { zis.copyTo(it) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            libDir.absolutePath
        } catch (_: Throwable) { "" }
    }

    fun probeRootChecks(context: Context, loader: DexClassLoader): Map<String, String> {
        val fakeBin = FakeSuProvider.fakeBinPath(context)
        val path    = "$fakeBin:${System.getenv("PATH") ?: "/system/bin:/system/xbin"}"
        val env     = arrayOf("PATH=$path")
        val results = mutableMapOf<String, String>()

        FakeSuProvider.fakeResponses.forEach { (cmd, expected) ->
            return@forEach try {
                val p   = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd), env)
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                results[cmd] = if (out == expected) "✓ spoofed" else "✗ got: $out"
            } catch (t: Throwable) {
                results[cmd] = "error: ${t.message}"
            }
        }

        val suFile = File(fakeBin, "su")
        results["su binary exists"] = if (suFile.canExecute()) "✓" else "✗"
        return results
    }
}
