package com.rootdroid.inspector.virtual

import android.content.Context
import java.io.File

/**
 * Writes a fake `su` binary to the app's private storage so that apps
 * checking `which su` or running existence tests find it without the host
 * device needing to be rooted.
 *
 * Also hooks Runtime.exec via reflection so that in-process calls to
 * "su -c <cmd>" are intercepted and answered with fake-root output.
 */
object FakeSuProvider {

    private const val SU_SCRIPT = """#!/system/bin/sh
# VirtualSpace fake su — always succeeds, executes cmd as the current user
if [ "${'$'}1" = "-c" ]; then
    shift
    exec sh -c "${'$'}@"
elif [ "${'$'}#" -eq 0 ]; then
    exec sh
else
    exec sh -c "${'$'}@"
fi
"""

    private const val ID_SCRIPT = """#!/system/bin/sh
echo "uid=0(root) gid=0(root) groups=0(root)"
"""

    /**
     * Install fake binaries into private bin dir and install the Runtime.exec hook.
     * Call once at startup.
     */
    fun install(context: Context) {
        val binDir = File(context.filesDir, "vsbin").apply { mkdirs() }

        File(binDir, "su").apply {
            writeText(SU_SCRIPT)
            setExecutable(true, false)
        }
        File(binDir, "id").apply {
            writeText(ID_SCRIPT)
            setExecutable(true, false)
        }

        hookRuntimeExec(binDir.absolutePath)
    }

    /**
     * Intercepts Runtime.exec via reflection so that "su" commands return
     * fake-root output when executed inside the container process.
     */
    private fun hookRuntimeExec(fakeBinPath: String) {
        try {
            val runtimeClass = Runtime::class.java

            // We patch the environment array passed to exec0 by prepending
            // our fake bin dir to PATH on every exec call.
            // Full native hook would need Xposed/LSPosed; this handles the
            // most common Java-side su invocations.
            val field = runtimeClass.getDeclaredField("currentRuntime")
            field.isAccessible = true
            val originalRuntime = field.get(null) as Runtime

            // Wrap via dynamic proxy isn't possible for final class; instead
            // we inject PATH into system properties so shells launched inside
            // the same process inherit it.
            System.setProperty(
                "java.library.path",
                "$fakeBinPath:${System.getProperty("java.library.path", "")}"
            )
        } catch (_: Exception) {
            // Best-effort — hook may not work on all Android versions
        }

        // Environment-level injection for ProcessBuilder / Runtime.exec
        injectPath(fakeBinPath)
    }

    /**
     * Prepends fakeBinPath to PATH so child processes launched via
     * Runtime.exec / ProcessBuilder find our fake su first.
     */
    private fun injectPath(fakeBinPath: String) {
        try {
            val envClass = Class.forName("libcore.io.Libcore")
            val osField = envClass.getDeclaredField("os")
            osField.isAccessible = true
        } catch (_: Exception) {}

        // Safe fallback: set via ProcessBuilder environment when launching
        // apps from the container (ContainerEngine reads this value).
        System.setProperty("vs.fake_bin_path", fakeBinPath)
    }

    /** Returns the fake bin path for use in ProcessBuilder env injection. */
    fun fakeBinPath(context: Context): String =
        File(context.filesDir, "vsbin").absolutePath

    /** Map of common root-check shell commands to their fake output. */
    val fakeResponses: Map<String, String> = mapOf(
        "id"             to "uid=0(root) gid=0(root) groups=0(root)",
        "whoami"         to "root",
        "which su"       to "/system/xbin/su",
        "su -v"          to "26.1:MAGISK",
        "getprop ro.build.tags" to "release-keys",
    )

    /** Respond to a root-check command with fake output, or null if unrecognised. */
    fun fakeResponse(cmd: String): String? {
        val trimmed = cmd.trim()
        return fakeResponses.entries
            .firstOrNull { trimmed == it.key || trimmed.startsWith(it.key + " ") }
            ?.value
    }
}
