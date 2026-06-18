package com.rootdroid.inspector.virtual

import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Modifier

data class MethodInfo(
    val shortClass: String,
    val fullClass: String,
    val methodName: String,
    val params: String,
    val returnType: String,
    val isNative: Boolean,
    val isPublic: Boolean,
)

object MethodEnumerator {

    private val SKIP_PREFIXES = listOf(
        "android.", "kotlin.", "java.", "javax.", "androidx.",
        "com.google.", "kotlinx.", "dalvik.", "libcore.",
    )

    /**
     * Fast path: reuse an existing [DexClassLoader] already holding the loaded APK.
     * Only DexFile.entries() is needed to walk the class list — no new classloader created.
     */
    @Suppress("DEPRECATION")
    suspend fun enumerateFromLoader(loader: DexClassLoader, apkPath: String): List<MethodInfo> =
        withContext(Dispatchers.IO) {
            try {
                val dex     = DexFile(apkPath)
                val entries = dex.entries()
                val results = mutableListOf<MethodInfo>()

                while (entries.hasMoreElements()) {
                    val cn = entries.nextElement()
                    if (SKIP_PREFIXES.any { cn.startsWith(it) }) continue
                    try {
                        val clazz = loader.loadClass(cn)
                        for (m in clazz.declaredMethods) {
                            val mods = m.modifiers
                            results += MethodInfo(
                                shortClass = cn.split("$").first().split(".").last(),
                                fullClass  = cn,
                                methodName = m.name,
                                params     = m.parameterTypes.joinToString(", ") { it.simpleName },
                                returnType = m.returnType.simpleName,
                                isNative   = Modifier.isNative(mods),
                                isPublic   = Modifier.isPublic(mods),
                            )
                        }
                    } catch (_: Throwable) {}
                }

                dex.close()
                results.sortedWith(compareBy({ it.shortClass }, { it.methodName }))
            } catch (_: Exception) { emptyList() }
        }

    /**
     * Slow path: create a new DexClassLoader from scratch (used when no live session exists).
     */
    @Suppress("DEPRECATION")
    suspend fun enumerate(context: Context, packageName: String): List<MethodInfo> =
        withContext(Dispatchers.IO) {
            try {
                val apkPath = context.packageManager
                    .getApplicationInfo(packageName, PackageManager.GET_META_DATA).sourceDir
                val optDir  = context.getDir("dex_me", Context.MODE_PRIVATE).absolutePath
                val loader  = DexClassLoader(apkPath, optDir, null, context.classLoader)
                enumerateFromLoader(loader, apkPath)
            } catch (_: Exception) { emptyList() }
        }
}
