package com.rootdroid.inspector.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.rootdroid.inspector.model.InstalledApp
import com.rootdroid.inspector.model.ManagedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class InstalledAppsRepository(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager
    private val dataFile = File(context.filesDir, "managed_apps.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns all user-installed apps (excludes system apps). */
    suspend fun getInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        } else {
            null
        }

        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(flags!!)
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        apps
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    appName = pm.getApplicationLabel(info).toString(),
                    icon = try { pm.getApplicationIcon(info.packageName) } catch (e: Exception) { null },
                )
            }
            .sortedBy { it.appName.lowercase() }
    }

    /** Returns all apps (including system). Used with root for full visibility. */
    suspend fun getAllInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }
        apps.map { info ->
            InstalledApp(
                packageName = info.packageName,
                appName = pm.getApplicationLabel(info).toString(),
                icon = try { pm.getApplicationIcon(info.packageName) } catch (e: Exception) { null },
            )
        }.sortedBy { it.appName.lowercase() }
    }

    /** Persist managed apps list to disk. */
    suspend fun saveManagedApps(apps: List<ManagedApp>) = withContext(Dispatchers.IO) {
        dataFile.writeText(json.encodeToString(apps))
    }

    /** Load managed apps from disk. */
    suspend fun loadManagedApps(): List<ManagedApp> = withContext(Dispatchers.IO) {
        if (!dataFile.exists()) return@withContext emptyList()
        try {
            json.decodeFromString<List<ManagedApp>>(dataFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Get icon for a package. */
    fun getIcon(packageName: String) = try {
        pm.getApplicationIcon(packageName)
    } catch (e: Exception) {
        null
    }
}
