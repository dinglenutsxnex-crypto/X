package top.niunaijun.blackbox.fake.hook;

import android.os.Build;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Enumeration;

/**
 * MethodTracer — injected into the container (guest) process by HookManager.
 *
 * Events are emitted as logcat lines tagged "__TRACE__" so the host-process
 * DebuggerFloatingService can parse them from the existing logcat reader.
 * No custom IPC or reflection into the host app is needed.
 *
 * Format: TYPE|className|methodName|thread|args|depth|elapsedMs
 */
public class MethodTracer implements IInjectHook {
    private static final String TAG        = "MethodTracer";
    static final String         TRACE_TAG  = "__TRACE__";

    private static final MethodTracer sTracer = new MethodTracer();
    public  static MethodTracer get() { return sTracer; }

    // ── IInjectHook ──────────────────────────────────────────────────────────

    @Override
    public void injectHook() {
        Log.i(TAG, "MethodTracer injected (API " + Build.VERSION.SDK_INT + ")");

        // Let the host know the tracer is live in this process
        logTrace("CLASS_LOAD", "MethodTracer", "INJECTED",
                Thread.currentThread().getName(), "", 0, 0);

        // Emit CLASS_LOAD events for every class already in the app's DEX files
        enumerateDexClasses();

        // Initialise native hooks (il2cpp + ART) — failures are non-fatal
        try {
            nativeInit(Build.VERSION.SDK_INT);
        } catch (UnsatisfiedLinkError | Exception e) {
            Log.w(TAG, "nativeInit skipped: " + e.getMessage());
        }
    }

    @Override
    public boolean isBadEnv() { return false; }

    // ── Public notify API (called from C++ hooks via JNI or from Java hooks) ─
    // Each method emits a __TRACE__ logcat line that the host parses.

    public static void logTraceStatic(String type, String className, String methodName) {
        Log.v(TRACE_TAG, type + "|" + className + "|" + methodName + "|||0|0");
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    static void logTrace(String type, String cls, String method,
                         String thread, String args, int depth, long elapsedMs) {
        Log.v(TRACE_TAG,
              type + "|" + cls + "|" + method + "|" + thread + "|" + args
              + "|" + depth + "|" + elapsedMs);
    }

    /**
     * Enumerate every class defined in the guest app's DEX files and emit
     * CLASS_LOAD events. This gives the TRACE tab immediate data the moment
     * the debugger attaches, before any new class loads happen.
     */
    private void enumerateDexClasses() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = MethodTracer.class.getClassLoader();
            if (cl == null) return;

            // Walk up the ClassLoader chain — the app classloader is usually the first
            // non-bootstrap one (PathClassLoader or InMemoryDexClassLoader).
            ClassLoader cur = cl;
            int dexCount = 0;
            while (cur != null && dexCount < 5) {
                String clName = cur.getClass().getName();
                if (clName.contains("DexClassLoader")
                        || clName.contains("PathClassLoader")
                        || clName.contains("BaseDexClassLoader")
                        || clName.contains("InMemoryDexClassLoader")) {
                    dexCount += scanClassLoader(cur);
                }
                cur = cur.getParent();
            }
        } catch (Exception e) {
            Log.w(TAG, "enumerateDexClasses: " + e.getMessage());
        }
    }

    /** Returns the number of dex files scanned. */
    private int scanClassLoader(ClassLoader cl) {
        int dexScanned = 0;
        try {
            Field pathListField = findField(cl.getClass(), "pathList");
            if (pathListField == null) return 0;
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(cl);
            if (pathList == null) return 0;

            Field dexElementsField = findField(pathList.getClass(), "dexElements");
            if (dexElementsField == null) return 0;
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(pathList);
            if (dexElements == null) return 0;

            for (Object elem : dexElements) {
                try {
                    Field dexFileField = findField(elem.getClass(), "dexFile");
                    if (dexFileField == null) continue;
                    dexFileField.setAccessible(true);
                    Object dexFile = dexFileField.get(elem);
                    if (dexFile == null) continue;

                    Method entries = dexFile.getClass().getMethod("entries");
                    @SuppressWarnings("unchecked")
                    Enumeration<String> classNames =
                            (Enumeration<String>) entries.invoke(dexFile);

                    int emitted = 0;
                    while (classNames.hasMoreElements() && emitted < 2000) {
                        String cn = classNames.nextElement();
                        // Skip system frameworks — only app & third-party classes
                        if (isFrameworkClass(cn)) continue;
                        logTrace("CLASS_LOAD", cn, "", "dex-enum", "", 0, 0);
                        emitted++;
                    }
                    dexScanned++;
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "scanClassLoader: " + e.getMessage());
        }
        return dexScanned;
    }

    private static boolean isFrameworkClass(String cn) {
        return cn.startsWith("android.")
            || cn.startsWith("java.")
            || cn.startsWith("javax.")
            || cn.startsWith("dalvik.")
            || cn.startsWith("libcore.")
            || cn.startsWith("sun.")
            || cn.startsWith("org.apache.")
            || cn.startsWith("com.android.")
            || cn.startsWith("com.google.android.")
            || cn.startsWith("androidx.")
            || cn.startsWith("kotlin.");
    }

    /** Walk up the class hierarchy to find a declared field. */
    private static Field findField(Class<?> cls, String name) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            try { return cur.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            cur = cur.getSuperclass();
        }
        return null;
    }

    // ── Native methods (in Tracer/tracer.cpp) ────────────────────────────────

    /** Set up Dobby hooks for il2cpp and ART interpreter. Non-fatal if unsupported. */
    public static native void nativeInit(int apiLevel);

    /** Remove Dobby hooks when the debugger detaches. */
    public static native void nativeStop();
}
