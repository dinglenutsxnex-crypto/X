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

// High-speed TRACE macro
#define TRACE(type, cls, method) \
    __android_log_print(ANDROID_LOG_VERBOSE, TRACE_TAG, "%s|%s|%s|native||0|0", (type), (cls), (method))

static volatile bool gInitialised = false;
static thread_local bool tInTracer = false;

// ── IL2CPP Structures & Function Pointers ────────────────────────────────────

struct Il2CppClass;
struct MethodInfo;

typedef enum {
    IL2CPP_PROFILE_NONE = 0,
    IL2CPP_PROFILE_METHOD_ENTER = 1 << 3,
} Il2CppProfileFlags;

typedef void (*Il2CppProfilerMethodCallback)(const MethodInfo* method);
typedef void (*install_enter_t)(Il2CppProfilerMethodCallback callback);
typedef void (*set_events_t)(Il2CppProfileFlags events);
typedef const char* (*method_get_name_t)(const MethodInfo* method);
typedef const void* (*method_get_class_t)(const MethodInfo* method);
typedef const char* (*class_get_name_t)(const void* klass);
typedef const char* (*class_get_namespace_t)(const void* klass);

static method_get_name_t      fn_method_get_name      = nullptr;
static method_get_class_t     fn_method_get_class     = nullptr;
static class_get_name_t       fn_class_get_name       = nullptr;
static class_get_namespace_t  fn_class_get_namespace  = nullptr;

// ── Optimized Filtering ──────────────────────────────────────────────────────

static inline bool isEngineNoise(const char* ns) {
    if (!ns || !ns[0]) return false;
    switch (ns[0]) {
        case 'U': return (strncmp(ns, "UnityEngine", 11) == 0 || strncmp(ns, "UnityEditor", 11) == 0);
        case 'S': return (strncmp(ns, "System", 6) == 0);
        case 'm': return (strncmp(ns, "mscorlib", 8) == 0);
        case 'M': return (strncmp(ns, "Microsoft", 9) == 0 || strncmp(ns, "Mono", 4) == 0);
    }
    return false;
}

// ── IL2CPP Profiler Callback ─────────────────────────────────────────────────

static void on_method_enter(const MethodInfo* method) {
    if (!gInitialised || tInTracer || !method || !fn_method_get_name) return;
    
    tInTracer = true;

    const char* mName = fn_method_get_name(method);
    const void* klass = fn_method_get_class(method);
    if (mName && klass) {
        const char* nsName = fn_class_get_namespace(klass);
        if (!isEngineNoise(nsName)) {
            const char* cName = fn_class_get_name(klass);
            char fullClass[256];
            if (nsName && nsName[0])
                snprintf(fullClass, sizeof(fullClass), "%s.%s", nsName, cName);
            else
                snprintf(fullClass, sizeof(fullClass), "%s", cName ? cName : "Unknown");

            TRACE("IL2CPP_CALL", fullClass, mName);
        }
    }

    tInTracer = false;
}

// ── IL2CPP runtime_invoke hook ───────────────────────────────────────────────

typedef void* (*Il2CppInvoke_t)(const MethodInfo* method, void* obj, void** params, void** exc);
static Il2CppInvoke_t orig_il2cpp_invoke = nullptr;

static void* hook_il2cpp_invoke(const MethodInfo* method, void* obj, void** params, void** exc) {
    if (gInitialised && !tInTracer && method) {
        on_method_enter(method);
    }
    return orig_il2cpp_invoke(method, obj, params, exc);
}

static bool hookIl2Cpp() {
    void *handle = xdl_open("libil2cpp.so", XDL_DEFAULT);
    if (!handle) return false;

    fn_method_get_name = (method_get_name_t)xdl_sym(handle, "il2cpp_method_get_name", nullptr);
    fn_method_get_class = (method_get_class_t)xdl_sym(handle, "il2cpp_method_get_class", nullptr);
    fn_class_get_name = (class_get_name_t)xdl_sym(handle, "il2cpp_class_get_name", nullptr);
    fn_class_get_namespace = (class_get_namespace_t)xdl_sym(handle, "il2cpp_class_get_namespace", nullptr);

    install_enter_t install_enter = (install_enter_t)xdl_sym(handle, "il2cpp_profiler_install_method_enter", nullptr);
    set_events_t set_events = (set_events_t)xdl_sym(handle, "il2cpp_profiler_set_events", nullptr);
    
    if (install_enter && set_events) {
        install_enter(on_method_enter);
        set_events(IL2CPP_PROFILE_METHOD_ENTER);
    }

    void *sym_invoke = xdl_sym(handle, "il2cpp_runtime_invoke", nullptr);
    if (sym_invoke) {
        DobbyHook(sym_invoke, (void*)hook_il2cpp_invoke, (void**)&orig_il2cpp_invoke);
    }

    xdl_close(handle);
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_niunaijun_blackbox_fake_hook_MethodTracer_nativeInit(
        JNIEnv * , jclass , jint apiLevel) {
    if (gInitialised) return JNI_TRUE;
    hookIl2Cpp();
    gInitialised = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_top_niunaijun_blackbox_fake_hook_MethodTracer_nativeStop(
        JNIEnv * , jclass ) {
    gInitialised = false;
    LOGI("Native tracer stopped");
}
