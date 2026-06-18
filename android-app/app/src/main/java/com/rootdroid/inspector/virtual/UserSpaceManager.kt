package com.rootdroid.inspector.virtual

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages an isolated Android user profile ("container user") via root.
 *
 * This is what HackApi.installPackageFromHost / HackApi.startActivity do
 * under the hood — Android's multi-user system gives true data isolation
 * per user ID, no native VA framework required on a rooted device.
 *
 * User profile is created once and reused across launches.
 */
object UserSpaceManager {

    private const val TAG = "UserSpaceManager"
    private const val PREFS = "vs_user_space"
    private const val KEY_USER_ID = "container_user_id"
    private const val USER_NAME = "RootDroidContainer"

    // ── Root exec ─────────────────────────────────────────────────────────────

    private fun suExec(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            p.waitFor()
            Log.d(TAG, "su[$cmd] => out=$out err=$err")
            out.ifEmpty { err }
        } catch (e: Exception) {
            Log.e(TAG, "suExec failed: $cmd", e)
            ""
        }
    }

    // ── User lifecycle ────────────────────────────────────────────────────────

    /** Returns true if su grants uid=0. */
    suspend fun isRooted(): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readLine() ?: return@withContext false
            p.waitFor()
            out.contains("uid=0")
        } catch (_: Exception) { false }
    }

    /**
     * Returns the container user ID, creating the user profile if it doesn't exist yet.
     * Returns -1 on failure.
     */
    suspend fun ensureContainerUser(ctx: Context): Int = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getInt(KEY_USER_ID, -1)
        if (stored > 0 && userExists(stored)) {
            Log.d(TAG, "Reusing container user $stored")
            return@withContext stored
        }

        // Try to create a new restricted/managed profile
        val output = suExec("pm create-user --profileOf 0 --managed \"$USER_NAME\"")
            .ifEmpty { suExec("pm create-user \"$USER_NAME\"") }

        Log.d(TAG, "create-user output: $output")

        // Parse "Success: created user id N"
        val id = Regex("id (\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: return@withContext -1

        prefs.edit().putInt(KEY_USER_ID, id).apply()
        Log.d(TAG, "Created container user $id")
        id
    }

    private fun userExists(userId: Int): Boolean {
        val out = suExec("pm list users")
        return out.contains("UserInfo{$userId:")
    }

    /** Remove the container user and its data. */
    suspend fun destroyContainerUser(ctx: Context) = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getInt(KEY_USER_ID, -1)
        if (id > 0) {
            suExec("pm remove-user $id")
            prefs.edit().remove(KEY_USER_ID).apply()
            Log.d(TAG, "Destroyed container user $id")
        }
    }

    // ── App management inside container user ──────────────────────────────────

    /**
     * Makes an already-system-installed package available inside the container user.
     * Equivalent to HackApi.installPackageFromHost().
     */
    suspend fun installIntoContainer(ctx: Context, pkg: String): Boolean = withContext(Dispatchers.IO) {
        val userId = ensureContainerUser(ctx)
        if (userId < 0) return@withContext false

        val out = suExec("pm install-existing --user $userId $pkg")
        val ok = out.contains("installed", ignoreCase = true) || out.contains("Success", ignoreCase = true)
        Log.d(TAG, "install-existing $pkg in user $userId => $out | ok=$ok")
        ok
    }

    suspend fun isInstalledInContainer(ctx: Context, pkg: String): Boolean = withContext(Dispatchers.IO) {
        val userId = ensureContainerUser(ctx)
        if (userId < 0) return@withContext false
        val out = suExec("pm list packages --user $userId")
        out.contains("package:$pkg")
    }

    suspend fun uninstallFromContainer(ctx: Context, pkg: String) = withContext(Dispatchers.IO) {
        val userId = ensureContainerUser(ctx)
        if (userId < 0) return@withContext
        suExec("pm uninstall --user $userId $pkg")
        Log.d(TAG, "Uninstalled $pkg from container user $userId")
    }

    // ── Launch ────────────────────────────────────────────────────────────────

    /**
     * Launches [pkg] inside the container user via `am start --user`.
     * Equivalent to HackApi.startActivity(intent, userId).
     * Returns the launched PID, or -1 on failure.
     */
    suspend fun launchInContainer(ctx: Context, pkg: String): Int = withContext(Dispatchers.IO) {
        val userId = ensureContainerUser(ctx)
        if (userId < 0) return@withContext -1

        // Prefer launching by action+category (works without knowing the exact component)
        var out = suExec(
            "am start --user $userId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg"
        )
        Log.d(TAG, "am start (action) => $out")

        // Fallback: resolve component via pm dump, then am start -n
        if (out.contains("Error") || out.contains("error") || out.isBlank()) {
            val component = resolveMainActivity(pkg)
            if (!component.isNullOrBlank()) {
                out = suExec("am start --user $userId -n $component")
                Log.d(TAG, "am start (component=$component) => $out")
            }
        }

        if (out.contains("Error") || (out.isBlank())) {
            Log.e(TAG, "Failed to launch $pkg in user $userId")
            return@withContext -1
        }

        // Give the process a moment to spawn, then find its PID
        Thread.sleep(1500)
        findPidInUser(userId, pkg)
    }

    /** Kill the app inside the container user. */
    suspend fun killInContainer(ctx: Context, pkg: String) = withContext(Dispatchers.IO) {
        val userId = ensureContainerUser(ctx)
        if (userId < 0) return@withContext
        suExec("am force-stop --user $userId $pkg")
        Log.d(TAG, "Stopped $pkg in user $userId")
    }

    // ── PID lookup ───────────────────────────────────────────────────────────

    /**
     * Find the PID of a package running inside the container user.
     * Checks /proc for a process whose cmdline matches [pkg].
     */
    fun findPidInUser(userId: Int, pkg: String): Int {
        // Try pidof first
        val pidofOut = suExec("pidof $pkg").trim()
        pidofOut.split("\\s+".toRegex()).forEach {
            val p = it.toIntOrNull() ?: return@forEach
            val cmdline = suExec("cat /proc/$p/cmdline 2>/dev/null")
                .replace('\u0000', ' ').trim()
            if (cmdline.contains(pkg)) return p
        }

        // Walk /proc as fallback
        val procOut = suExec("ls /proc")
        for (entry in procOut.lines()) {
            val pid = entry.trim().toIntOrNull() ?: continue
            val cmdline = suExec("cat /proc/$pid/cmdline 2>/dev/null")
                .replace('\u0000', ' ').trim()
            if (cmdline.startsWith(pkg)) return pid
        }
        return -1
    }

    // ── Su spoofing inside the container ─────────────────────────────────────

    /**
     * Installs a fake su shim into the container user's writable area and
     * sets up a wrapper script that intercepts root checks.
     *
     * This leverages real root to write into /data/user/<userId>/<pkg>/files/vsbin/
     * so that child processes spawned by the app find our fake su first.
     */
    suspend fun installFakeSuForContainer(ctx: Context, pkg: String) = withContext(Dispatchers.IO) {
        val userId = ensureContainerUser(ctx)
        if (userId < 0) return@withContext

        val fakeBinDir = "/data/user/$userId/$pkg/files/vsbin"
        suExec("mkdir -p $fakeBinDir")

        val suScript = """#!/system/bin/sh
if [ "$1" = "-c" ]; then shift; exec sh -c "$@"; elif [ $# -eq 0 ]; then exec sh; else exec sh -c "$@"; fi
""".trimIndent()

        val idScript = """#!/system/bin/sh
echo "uid=0(root) gid=0(root) groups=0(root)"
""".trimIndent()

        val whichScript = """#!/system/bin/sh
echo "/system/xbin/su"
""".trimIndent()

        // Write via su to the container user's data dir
        suExec("echo '${suScript.replace("'", "'\\''")}' > $fakeBinDir/su && chmod 755 $fakeBinDir/su")
        suExec("echo '${idScript.replace("'", "'\\''")}' > $fakeBinDir/id && chmod 755 $fakeBinDir/id")
        suExec("echo '${whichScript.replace("'", "'\\''")}' > $fakeBinDir/which && chmod 755 $fakeBinDir/which")
        suExec("echo '26.1:MAGISK' > $fakeBinDir/.su_version")

        Log.d(TAG, "Installed fake su in $fakeBinDir")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveMainActivity(pkg: String): String? {
        // Primary: cmd package resolve-activity (API 26+)
        val resolve = suExec(
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg 2>/dev/null"
        )
        if (resolve.isNotBlank() && resolve.contains("/")) {
            return resolve.trim().lines().lastOrNull { it.contains("/") }?.trim()
        }

        // Fallback: parse pm dump for activity with MAIN/LAUNCHER intent filter
        val pmDump = suExec("pm dump $pkg 2>/dev/null")
        var inActionMain = false
        var lastActivity: String? = null
        for (line in pmDump.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Activity ") && trimmed.contains("/") ->
                    lastActivity = trimmed.removePrefix("Activity ").substringBefore(" ").trim()
                trimmed.contains("android.intent.action.MAIN") -> inActionMain = true
                trimmed.contains("android.intent.category.LAUNCHER") && inActionMain ->
                    return lastActivity
                trimmed.isEmpty() -> inActionMain = false
            }
        }

        return null
    }
}
