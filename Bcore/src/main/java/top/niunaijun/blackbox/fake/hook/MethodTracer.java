package top.niunaijun.blackbox.fake.hook;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;

public class MethodTracer implements IInjectHook {
    private static final String TAG = "MethodTracer";
    private static final MethodTracer sTracer = new MethodTracer();

    // Cached reflection refs to TraceEngine (in app module — resolved lazily at runtime).
    // Using reflection avoids a compile-time Bcore → app dependency.
    private volatile boolean traceEngineResolved = false;
    private Method teOnClassLoad    = null;
    private Method teOnDexLoad      = null;
    private Method teOnJniRegister  = null;
    private Method teOnBinderCall   = null;

    public static MethodTracer get() { return sTracer; }

    // ── IInjectHook ──────────────────────────────────────────────────────────

    @Override
    public void injectHook() {
        Log.i(TAG, "MethodTracer injected - initialising trace engine bridge");
        resolveTraceEngine();
        try {
            nativeInit(Build.VERSION.SDK_INT);
            Log.i(TAG, "Native tracer initialised (API " + Build.VERSION.SDK_INT + ")");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native tracer not available: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "nativeInit failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isBadEnv() { return false; }

    // ── Java-layer event routing ─────────────────────────────────────────────
    // Called by existing hook implementations (VMClassLoaderHook, JniHook, etc.)
    // to feed events into the Trace tab without a compile-time dep on the app module.

    public void notifyClassLoad(String className) {
        if (teOnClassLoad == null) return;
        try { teOnClassLoad.invoke(null, className); }
        catch (Exception ignored) {}
    }

    public void notifyDexLoad(String path) {
        if (teOnDexLoad == null) return;
        try { teOnDexLoad.invoke(null, path); }
        catch (Exception ignored) {}
    }

    public void notifyJniRegister(String className, String methodName) {
        if (teOnJniRegister == null) return;
        try { teOnJniRegister.invoke(null, className, methodName); }
        catch (Exception ignored) {}
    }

    public void notifyBinderCall(String descriptor, int code) {
        if (teOnBinderCall == null) return;
        try { teOnBinderCall.invoke(null, descriptor, code); }
        catch (Exception ignored) {}
    }

    // ── Native methods (implemented in Tracer/tracer.cpp) ────────────────────

    /**
     * Initialise the native tracer: cache TraceEngine JNI refs, attempt to hook
     * il2cpp_runtime_invoke (Unity/il2cpp games) and art::interpreter::Execute
     * (interpreted Java methods) via Dobby + xdl symbol lookup.
     */
    public static native void nativeInit(int apiLevel);

    /**
     * Remove all Dobby hooks installed by nativeInit. Called when the debugger
     * detaches so we don't leave stale hooks in the process.
     */
    public static native void nativeStop();

    // ── Private helpers ──────────────────────────────────────────────────────

    private void resolveTraceEngine() {
        if (traceEngineResolved) return;
        try {
            // TraceEngine is in the app module; its class loader is the app's.
            // We look it up by name at runtime — no compile-time dependency.
            Class<?> te = Class.forName("top.niunaijun.blackboxa.view.debugger.TraceEngine");
            teOnClassLoad   = te.getMethod("onClassLoad",   String.class);
            teOnDexLoad     = te.getMethod("onDexLoad",     String.class);
            teOnJniRegister = te.getMethod("onJniRegister", String.class, String.class);
            teOnBinderCall  = te.getMethod("onBinderCall",  String.class, int.class);
            traceEngineResolved = true;
            Log.i(TAG, "TraceEngine bridge resolved");
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "TraceEngine not on classpath yet — will retry on next attach");
        } catch (Exception e) {
            Log.w(TAG, "TraceEngine bridge failed: " + e.getMessage());
        }
    }
}
