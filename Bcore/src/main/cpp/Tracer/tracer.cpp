/*
 * tracer.cpp — Native method tracer using Dobby + xdl
 *
 * Runs inside the CONTAINER (guest) process injected by BlackBox.
 * Events are written to logcat with tag "__TRACE__" so the host-process
 * DebuggerFloatingService can parse them from its existing logcat reader.
 * No JNI callback into the host app is needed.
 *
 * Hooks installed:
 *   il2cpp_runtime_invoke  — every managed Unity/il2cpp method call
 *   art::interpreter::Execute — every interpreted ART Java method call
 */

#include <jni.h>
#include <string>
#include <cstring>
#include <dlfcn.h>
#include <time.h>
#include <android/log.h>
#include "../Dobby/dobby.h"
#include "../xdl.h"

#define TRACER_TAG  "Tracer"
#define TRACE_TAG   "__TRACE__"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,    TRACER_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,    TRACER_TAG, __VA_ARGS__)
#define TRACE(type, cls, method, args, depth, ms) \
    __android_log_print(ANDROID_LOG_VERBOSE, TRACE_TAG, \
        "%s|%s|%s|native|%s|%d|%ld", (type), (cls), (method), (args), (depth), (long)(ms))

// ─── State ───────────────────────────────────────────────────────────────────

static volatile bool gInitialised = false;

// ─── il2cpp structs (layout stable across Unity versions, arm64) ─────────────

struct Il2CppClass {
    const void  *image;        // +0x00
    void        *gc_desc;      // +0x08
    const char  *name;         // +0x10
    const char  *namespaze;    // +0x18
};

struct MethodInfo {
    void              *method_pointer; // +0x00
    void              *invoker_method; // +0x08
    const char        *name;           // +0x10
    const Il2CppClass *klass;          // +0x18
};

// ─── ART ShadowFrame stub (stable Android 8–14, arm64) ───────────────────────

struct ShadowFrameStub {
    uint32_t number_of_vregs;
    uint32_t number_of_references;
    uint32_t dex_pc;
    uint32_t padding;
    void    *method;   // ArtMethod* at +0x10
};

// ─── il2cpp hook ─────────────────────────────────────────────────────────────

typedef void *(*Il2CppInvoke_t)(const MethodInfo *, void *, void **, void **);
static Il2CppInvoke_t orig_il2cpp_invoke = nullptr;

static void *hook_il2cpp_invoke(const MethodInfo *method, void *obj,
                                 void **params, void **exc) {
    if (!gInitialised) return orig_il2cpp_invoke(method, obj, params, exc);

    const char *mName  = (method && method->name)         ? method->name            : "?";
    const char *cName  = (method && method->klass)        ? method->klass->name     : "?";
    const char *nsName = (method && method->klass)        ? method->klass->namespaze: "";

    char fullClass[256];
    if (nsName && nsName[0])
        snprintf(fullClass, sizeof(fullClass), "%s.%s", nsName, cName);
    else
        snprintf(fullClass, sizeof(fullClass), "%s", cName);

    struct timespec ts0, ts1;
    clock_gettime(CLOCK_MONOTONIC, &ts0);

    void *result = orig_il2cpp_invoke(method, obj, params, exc);

    clock_gettime(CLOCK_MONOTONIC, &ts1);
    long elapsedMs = (ts1.tv_sec  - ts0.tv_sec)  * 1000L
                   + (ts1.tv_nsec - ts0.tv_nsec) / 1000000L;

    // Emit a single IL2CPP_CALL with elapsed time on the exit side
    TRACE("IL2CPP_CALL", fullClass, mName, "", 0, elapsedMs);

    return result;
}

static bool hookIl2Cpp() {
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
    int ret = DobbyHook(sym, (void *) hook_il2cpp_invoke, (void **) &orig_il2cpp_invoke);
    if (ret == 0) {
        LOGI("il2cpp_runtime_invoke hooked @ %p", sym);
        return true;
    }
    LOGW("Dobby hook failed for il2cpp_runtime_invoke (ret=%d)", ret);
    return false;
}

// ─── ART interpreter hook ────────────────────────────────────────────────────
//
// We throttle to every 64th call to avoid flooding logcat — the ART interpreter
// is invoked for EVERY Java bytecode dispatch.

typedef void (*ArtExecute_t)(void *, const void *, void *, void *, bool);
static ArtExecute_t orig_art_execute = nullptr;

static thread_local uint64_t tArtCallCount = 0;

static void hook_art_execute(void *self, const void *code,
                              void *shadow_frame, void *result, bool stay) {
    if (gInitialised && shadow_frame) {
        tArtCallCount++;
        // 1-in-64 sampling to reduce noise
        if ((tArtCallCount & 0x3F) == 0) {
            auto *sf = reinterpret_cast<ShadowFrameStub *>(shadow_frame);
            if (sf->method) {
                char addrBuf[32];
                snprintf(addrBuf, sizeof(addrBuf), "0x%lx", (unsigned long) sf->method);
                TRACE("METHOD_ENTER", "ART", addrBuf, "", 0, 0);
            }
        }
    }
    orig_art_execute(self, code, shadow_frame, result, stay);
}

static bool hookArtInterpreter() {
    // Mangled symbol candidates across Android 8–14
    static const char *candidates[] = {
        "_ZN3art11interpreter7ExecuteEPNS_6ThreadERKNS_20CodeItemDataAccessorERNS_11ShadowFrameENS_6JValueEbb",
        "_ZN3art11interpreter7ExecuteEPNS_6ThreadERKNS_20CodeItemDataAccessorERNS_11ShadowFrameENS_6JValueEb",
        "_ZN3art11interpreter7ExecuteEPNS_6ThreadEPKNS_7DexFile8CodeItemERNS_11ShadowFrameENS_6JValueEb",
        nullptr
    };
    void *handle = xdl_open("libart.so", XDL_DEFAULT);
    if (!handle) { LOGW("libart.so not found"); return false; }
    void *sym = nullptr;
    for (int i = 0; candidates[i]; i++) {
        sym = xdl_sym(handle, candidates[i], nullptr);
        if (sym) { LOGI("art::interpreter::Execute found (%s)", candidates[i]); break; }
    }
    xdl_close(handle);
    if (!sym) { LOGW("art::interpreter::Execute not found"); return false; }
    int ret = DobbyHook(sym, (void *) hook_art_execute, (void **) &orig_art_execute);
    if (ret == 0) { LOGI("art::interpreter::Execute hooked"); return true; }
    LOGW("Dobby hook failed for art::interpreter::Execute (ret=%d)", ret);
    return false;
}

// ─── JNI exports ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_top_niunaijun_blackbox_fake_hook_MethodTracer_nativeInit(
        JNIEnv * /*env*/, jclass /*clazz*/, jint apiLevel) {
    if (gInitialised) return;
    gInitialised = true;
    bool il2cpp = hookIl2Cpp();
    bool art    = hookArtInterpreter();
    LOGI("Native tracer ready (API %d). il2cpp=%s art=%s",
         apiLevel, il2cpp ? "yes" : "no", art ? "yes" : "no");
}

extern "C" JNIEXPORT void JNICALL
Java_top_niunaijun_blackbox_fake_hook_MethodTracer_nativeStop(
        JNIEnv * /*env*/, jclass /*clazz*/) {
    gInitialised = false;
    LOGI("Native tracer stopped");
}
