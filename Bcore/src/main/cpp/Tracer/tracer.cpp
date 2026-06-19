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

static volatile bool gInitialised = false;

struct Il2CppClass {
    const void  *image;
    void        *gc_desc;
    const char  *name;
    const char  *namespaze;
};

struct MethodInfo {
    void              *method_pointer;
    void              *invoker_method;
    const char        *name;
    const Il2CppClass *klass;
};

struct ShadowFrameStub {
    uint32_t number_of_vregs;
    uint32_t number_of_references;
    uint32_t dex_pc;
    uint32_t padding;
    void    *method;
};

// Readable user-space pointer guard.
// Rejects null, unaligned, and addresses that fall in obvious non-heap ranges
// (kernel space on arm64 starts at 0xffff000000000000).
static inline bool isReadablePtr(const void *p) {
    uintptr_t v = reinterpret_cast<uintptr_t>(p);
    if (v == 0)                       return false;
    if (v & 0x3)                      return false; // must be 4-byte aligned
    if (v >= 0x0000800000000000ULL)   return false; // kernel/invalid range
    return true;
}

// Safe string accessor: returns ptr only if the pointer itself is readable.
// We do NOT inspect the first byte — il2cpp metadata strings live in a
// read-only mapped segment and can start with bytes outside printable ASCII,
// which caused the old safeStr to silently drop every method name.
// If the struct pointer passed isReadablePtr() the string is safe to read;
// a garbled log line is harmless, a silent drop is not.
static inline const char *safeStr(const char *s) {
    if (!isReadablePtr(s)) return nullptr;
    return s;
}

typedef void *(*Il2CppInvoke_t)(const MethodInfo *, void *, void **, void **);
static Il2CppInvoke_t orig_il2cpp_invoke = nullptr;

static void *hook_il2cpp_invoke(const MethodInfo *method, void *obj,
                                 void **params, void **exc) {
    if (!gInitialised || !isReadablePtr(method)) {
        return orig_il2cpp_invoke(method, obj, params, exc);
    }

    const char *mName  = safeStr(method->name);
    const char *cName  = nullptr;
    const char *nsName = nullptr;

    if (isReadablePtr(method->klass)) {
        cName  = safeStr(method->klass->name);
        nsName = safeStr(method->klass->namespaze);
    }

    if (!mName || !cName) {
        return orig_il2cpp_invoke(method, obj, params, exc);
    }

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

typedef void (*ArtExecute_t)(void *, const void *, void *, void *, bool);
static ArtExecute_t orig_art_execute = nullptr;

static thread_local uint64_t tArtCallCount = 0;

static void hook_art_execute(void *self, const void *code,
                              void *shadow_frame, void *result, bool stay) {
    if (gInitialised && isReadablePtr(shadow_frame)) {
        tArtCallCount++;
        if ((tArtCallCount & 0x3F) == 0) {
            auto *sf = reinterpret_cast<ShadowFrameStub *>(shadow_frame);
            if (isReadablePtr(sf->method)) {
                char addrBuf[32];
                snprintf(addrBuf, sizeof(addrBuf), "0x%lx", (unsigned long) sf->method);
                TRACE("METHOD_ENTER", "ART", addrBuf, "", 0, 0);
            }
        }
    }
    orig_art_execute(self, code, shadow_frame, result, stay);
}

static bool hookArtInterpreter() {
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

extern "C" JNIEXPORT jboolean JNICALL
Java_top_niunaijun_blackbox_fake_hook_MethodTracer_nativeInit(
        JNIEnv * , jclass , jint apiLevel) {
    if (gInitialised) return JNI_TRUE;
    bool il2cpp = hookIl2Cpp();
    bool art    = hookArtInterpreter();
    LOGI("Native tracer probe (API %d). il2cpp=%s art=%s",
         apiLevel, il2cpp ? "yes" : "no", art ? "yes" : "no");
    if (il2cpp || art) {
        gInitialised = true;
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_top_niunaijun_blackbox_fake_hook_MethodTracer_nativeStop(
        JNIEnv * , jclass ) {
    gInitialised = false;
    LOGI("Native tracer stopped");
}
