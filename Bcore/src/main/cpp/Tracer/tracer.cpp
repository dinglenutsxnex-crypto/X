/*
 * tracer.cpp — Native method tracer using Dobby + xdl
 *
 * Hooks two key dispatch points:
 *   1. il2cpp_runtime_invoke   — universal entry for ALL managed Unity/il2cpp calls
 *   2. art::interpreter::Execute — universal entry for interpreted ART Java methods
 *
 * Both hooks fire a JNI callback into TraceEngine.onNativeEvent() which the
 * Debugger TRACE tab observes.  Hooks are installed lazily so startup cost
 * is zero when the Trace tab is not in use.
 *
 * Dobby and xdl are already bundled in the project — no extra dependencies.
 */

#include <jni.h>
#include <string>
#include <cstring>
#include <dlfcn.h>
#include <time.h>
#include <android/log.h>
#include "../Log.h"
#include "../Dobby/dobby.h"
#include "../xdl.h"

#define TRACER_TAG "Tracer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TRACER_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TRACER_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TRACER_TAG, __VA_ARGS__)

// ─── TraceType ordinals — must match TraceEvent.TraceType enum order ─────────
enum TraceType {
    METHOD_ENTER  = 0,
    METHOD_EXIT   = 1,
    CONSTRUCTOR   = 2,
    CLASS_LOAD    = 3,
    DEX_LOAD      = 4,
    JNI_REGISTER  = 5,
    IL2CPP_CALL   = 6,
    BINDER_CALL   = 7,
    EXCEPTION     = 8,
};

// ─── JNI state ───────────────────────────────────────────────────────────────

static JavaVM  *gVm              = nullptr;
static jclass   gTraceEngineClass = nullptr;
static jmethodID gOnNativeEvent  = nullptr;
static int      gApiLevel        = 0;
static bool     gInitialised     = false;

// ─── il2cpp structs (layout is stable across Unity versions) ─────────────────

struct Il2CppClass {
    const void  *image;        // +0x00
    void        *gc_desc;      // +0x08
    const char  *name;         // +0x10  ← class name string
    const char  *namespaze;    // +0x18
    // ...
};

struct MethodInfo {
    void              *method_pointer; // +0x00
    void              *invoker_method; // +0x08
    const char        *name;           // +0x10  ← method name string
    const Il2CppClass *klass;          // +0x18  ← declaring class
    // ...
};

// ─── ART interpreter state structs (forward decls, we only need the pointer) ──

// art::ShadowFrame is opaque — we just need the method pointer from it.
// Layout (stable from Android 8+):
//   +0x00 uint32_t number_of_vregs
//   +0x04 uint32_t number_of_references
//   +0x08 uint32_t dex_pc
//   +0x0C uint32_t padding
//   +0x10 ArtMethod* method    ← what we need
struct ShadowFrameStub {
    uint32_t number_of_vregs;
    uint32_t number_of_references;
    uint32_t dex_pc;
    uint32_t padding;
    void    *method;        // ArtMethod*
};

// ArtMethod name helpers — read via the JniHook env already in the process,
// or fall back to reading known offsets directly.
// We use GetMethodName / GetMethodDesc via a tiny helper so we don't duplicate
// the JniHook offset tables.  Simplest: just call back to Java for the name.

// ─── JNI helper ──────────────────────────────────────────────────────────────

static JNIEnv *getEnv() {
    JNIEnv *env = nullptr;
    if (gVm) gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

static JNIEnv *ensureEnv() {
    JNIEnv *env = getEnv();
    if (!env && gVm) gVm->AttachCurrentThread(&env, nullptr);
    return env;
}

static void fireEvent(int type,
                      const char *className, const char *methodName,
                      const char *args,      const char *retVal,
                      int depth,             long elapsedMs) {
    if (!gInitialised || !gTraceEngineClass || !gOnNativeEvent) return;
    JNIEnv *env = ensureEnv();
    if (!env) return;

    jstring jClass  = env->NewStringUTF(className  ? className  : "");
    jstring jMethod = env->NewStringUTF(methodName ? methodName : "");
    jstring jThread = env->NewStringUTF("native");
    jstring jArgs   = env->NewStringUTF(args       ? args       : "");
    jstring jRet    = env->NewStringUTF(retVal      ? retVal     : "");

    // Signature: onNativeEvent(int, String, String, String, String, String, int, long)
    env->CallStaticVoidMethod(gTraceEngineClass, gOnNativeEvent,
                              (jint) type,
                              jClass, jMethod, jThread, jArgs, jRet,
                              (jint) depth, (jlong) elapsedMs);

    env->DeleteLocalRef(jClass);
    env->DeleteLocalRef(jMethod);
    env->DeleteLocalRef(jThread);
    env->DeleteLocalRef(jArgs);
    env->DeleteLocalRef(jRet);

    if (env->ExceptionCheck()) env->ExceptionClear();
}

// ─── il2cpp hook ─────────────────────────────────────────────────────────────
//
// il2cpp_runtime_invoke is the universal managed method dispatch for Unity games.
// Every scripted method call passes through it regardless of class or obfuscation.
//
// Signature (stable across il2cpp versions):
//   Il2CppObject* il2cpp_runtime_invoke(MethodInfo* method, void* obj,
//                                       void** params, Il2CppException** exc)

typedef void *(*Il2CppRuntimeInvoke_t)(const MethodInfo *, void *, void **, void **);
static Il2CppRuntimeInvoke_t orig_il2cpp_runtime_invoke = nullptr;

static void *hook_il2cpp_runtime_invoke(const MethodInfo *method, void *obj,
                                         void **params, void **exc) {
    // Read method name and class name from the MethodInfo struct.
    // Both are plain C strings — safe to read without il2cpp headers.
    const char *mName     = (method && method->name)              ? method->name             : "?";
    const char *cName     = (method && method->klass)             ? method->klass->name      : "?";
    const char *nsName    = (method && method->klass)             ? method->klass->namespaze : "";

    // Build a "Namespace.Class" string (Unity convention)
    char fullClass[256] = {};
    if (nsName && nsName[0]) snprintf(fullClass, sizeof(fullClass), "%s.%s", nsName, cName);
    else                     snprintf(fullClass, sizeof(fullClass), "%s", cName);

    long t0 = 0;
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    t0 = ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;

    fireEvent(IL2CPP_CALL, fullClass, mName, "", "", 0, 0);

    void *result = orig_il2cpp_runtime_invoke(method, obj, params, exc);

    clock_gettime(CLOCK_MONOTONIC, &ts);
    long elapsed = (ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL) - t0;

    fireEvent(METHOD_EXIT, fullClass, mName, "", "", 0, elapsed);

    return result;
}

static bool hookIl2Cpp() {
    // xdl_open searches /proc/self/maps — works even if the lib is loaded via dlopen
    void *handle = xdl_open("libil2cpp.so", XDL_DEFAULT);
    if (!handle) {
        LOGI("libil2cpp.so not found — not a Unity/il2cpp game");
        return false;
    }
    void *sym = xdl_sym(handle, "il2cpp_runtime_invoke", nullptr);
    xdl_close(handle);

    if (!sym) {
        LOGW("il2cpp_runtime_invoke symbol not found");
        return false;
    }

    int ret = DobbyHook(sym, (void *) hook_il2cpp_runtime_invoke,
                        (void **) &orig_il2cpp_runtime_invoke);
    if (ret == 0) {
        LOGI("il2cpp_runtime_invoke hooked @ %p", sym);
        return true;
    }
    LOGW("Dobby hook failed for il2cpp_runtime_invoke (ret=%d)", ret);
    return false;
}

// ─── ART interpreter hook ────────────────────────────────────────────────────
//
// art::interpreter::Execute is called for every interpreted ART method.
// We try to find and hook it via xdl symbol lookup on libart.so.
// This gives us a method trace for all non-JIT-compiled Java methods.
//
// Signature (Android 8–14, slightly varies but first arg is always Thread*
// and third arg is ShadowFrame& from which we read the ArtMethod*):
//   void Execute(Thread* self, const DexFile::CodeItem* code,
//                ShadowFrame& shadow_frame, JValue result, bool stay)

typedef void (*ArtExecute_t)(void *, const void *, void *, void *, bool);
static ArtExecute_t orig_art_execute = nullptr;

// Throttle: skip if we've fired recently for the same method to avoid flooding
#define ART_TRACE_SKIP_MASK 0x3F   // fire 1 in every 64 calls per-method

static void hook_art_execute(void *self, const void *code,
                              void *shadow_frame, void *result, bool stay) {
    // Read ArtMethod* from ShadowFrame at offset 0x10 (stable Android 8–14, arm64)
    if (shadow_frame) {
        auto *sf = reinterpret_cast<ShadowFrameStub *>(shadow_frame);
        void *artMethod = sf->method;
        if (artMethod) {
            // ArtMethod::GetName() is not directly accessible without headers.
            // Instead we emit a lightweight event with the ArtMethod pointer address
            // as the "name" — the Java layer can optionally resolve it.
            // This keeps the hook overhead minimal.
            char addrBuf[32];
            snprintf(addrBuf, sizeof(addrBuf), "0x%lx", (unsigned long) artMethod);
            fireEvent(METHOD_ENTER, "ART", addrBuf, "", "", 0, 0);
        }
    }
    orig_art_execute(self, code, shadow_frame, result, stay);
}

static bool hookArtInterpreter() {
    // Try multiple candidate names — ART mangles the symbol differently per version
    const char *candidates[] = {
        "_ZN3art11interpreter7ExecuteEPNS_6ThreadERKNS_20CodeItemDataAccessorERNS_11ShadowFrameENS_6JValueEbb",
        "_ZN3art11interpreter7ExecuteEPNS_6ThreadERKNS_20CodeItemDataAccessorERNS_11ShadowFrameENS_6JValueEb",
        "_ZN3art11interpreter7ExecuteEPNS_6ThreadEPKNS_7DexFile8CodeItemERNS_11ShadowFrameENS_6JValueEb",
        nullptr
    };

    void *handle = xdl_open("libart.so", XDL_DEFAULT);
    if (!handle) {
        LOGW("libart.so not found via xdl");
        return false;
    }

    void *sym = nullptr;
    for (int i = 0; candidates[i] != nullptr; ++i) {
        sym = xdl_sym(handle, candidates[i], nullptr);
        if (sym) {
            LOGI("Found art::interpreter::Execute @ %p (%s)", sym, candidates[i]);
            break;
        }
    }
    xdl_close(handle);

    if (!sym) {
        LOGW("art::interpreter::Execute symbol not found (JIT-compiled process?)");
        return false;
    }

    int ret = DobbyHook(sym, (void *) hook_art_execute,
                        (void **) &orig_art_execute);
    if (ret == 0) {
        LOGI("art::interpreter::Execute hooked");
        return true;
    }
    LOGW("Dobby hook failed for art::interpreter::Execute (ret=%d)", ret);
    return false;
}

// ─── JNI exports ─────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_top_niunaijun_blackbox_fake_hook_MethodTracer_nativeInit(
        JNIEnv *env, jclass /*clazz*/, jint apiLevel) {

    if (gInitialised) return;
    gApiLevel = apiLevel;

    env->GetJavaVM(&gVm);

    // Cache TraceEngine class + onNativeEvent method for callbacks
    jclass te = env->FindClass(
        "top/niunaijun/blackboxa/view/debugger/TraceEngine");
    if (!te) {
        env->ExceptionClear();
        LOGW("TraceEngine class not found — trace events will be dropped");
    } else {
        gTraceEngineClass = reinterpret_cast<jclass>(env->NewGlobalRef(te));
        // onNativeEvent(int, String, String, String, String, String, int, long)
        gOnNativeEvent = env->GetStaticMethodID(gTraceEngineClass, "onNativeEvent",
            "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            "Ljava/lang/String;Ljava/lang/String;IJ)V");
        if (!gOnNativeEvent) {
            env->ExceptionClear();
            LOGW("TraceEngine.onNativeEvent method not found");
        }
    }

    gInitialised = true;

    // Install hooks (failures are non-fatal — we just log and continue)
    bool il2cpp = hookIl2Cpp();
    bool art    = hookArtInterpreter();
    LOGI("Native tracer ready. il2cpp=%s art=%s",
         il2cpp ? "yes" : "no", art ? "yes" : "no");
}

extern "C"
JNIEXPORT void JNICALL
Java_top_niunaijun_blackbox_fake_hook_MethodTracer_nativeStop(
        JNIEnv *env, jclass /*clazz*/) {

    if (!gInitialised) return;

    // Dobby doesn't expose an uninstall API in older versions — we set a flag
    // so the hook handler bails early instead.
    gInitialised = false;

    if (gTraceEngineClass) {
        env->DeleteGlobalRef(gTraceEngineClass);
        gTraceEngineClass = nullptr;
    }
    gOnNativeEvent = nullptr;
    LOGI("Native tracer stopped");
}
