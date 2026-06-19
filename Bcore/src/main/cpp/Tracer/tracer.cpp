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
typedef const Il2CppClass* (*method_get_class_t)(const MethodInfo* method);
typedef const char* (*class_get_name_t)(const Il2CppClass* klass);
typedef const char* (*class_get_namespace_t)(const Il2CppClass* klass);

static method_get_name_t      fn_method_get_name      = nullptr;
static method_get_class_t     fn_method_get_class     = nullptr;
static class_get_name_t       fn_class_get_name       = nullptr;
static class_get_namespace_t  fn_class_get_namespace  = nullptr;

// ── ART PrettyMethod Resolution ──────────────────────────────────────────────

typedef std::string (*PrettyMethod_t)(void* method, bool with_signature);
static PrettyMethod_t fn_pretty_method = nullptr;

// ── IL2CPP Profiler Callback ─────────────────────────────────────────────────

static void on_method_enter(const MethodInfo* method) {
    if (!gInitialised || !method || !fn_method_get_name) return;

    const char* mName = fn_method_get_name(method);
    const Il2CppClass* klass = fn_method_get_class(method);
    
    if (!mName || !klass) return;

    const char* cName = fn_class_get_name(klass);
    const char* nsName = fn_class_get_namespace(klass);

    char fullClass[256];
    if (nsName && nsName[0])
        snprintf(fullClass, sizeof(fullClass), "%s.%s", nsName, cName);
    else
        snprintf(fullClass, sizeof(fullClass), "%s", cName ? cName : "Unknown");

    TRACE("IL2CPP_CALL", fullClass, mName, "", 0, 0);
}

// ── IL2CPP runtime_invoke hook (Option B - more aggressive) ──────────────────

typedef void* (*Il2CppInvoke_t)(const MethodInfo* method, void* obj, void** params, void** exc);
static Il2CppInvoke_t orig_il2cpp_invoke = nullptr;

static void* hook_il2cpp_invoke(const MethodInfo* method, void* obj, void** params, void** exc) {
    if (gInitialised && method) {
        on_method_enter(method);
    }
    return orig_il2cpp_invoke(method, obj, params, exc);
}

static bool hookIl2Cpp() {
    void *handle = xdl_open("libil2cpp.so", XDL_DEFAULT);
    if (!handle) return false;

    // 1. Try Profiler API first
    install_enter_t install_enter = (install_enter_t)xdl_sym(handle, "il2cpp_profiler_install_method_enter", nullptr);
    set_events_t set_events = (set_events_t)xdl_sym(handle, "il2cpp_profiler_set_events", nullptr);
    
    fn_method_get_name = (method_get_name_t)xdl_sym(handle, "il2cpp_method_get_name", nullptr);
    fn_method_get_class = (method_get_class_t)xdl_sym(handle, "il2cpp_method_get_class", nullptr);
    fn_class_get_name = (class_get_name_t)xdl_sym(handle, "il2cpp_class_get_name", nullptr);
    fn_class_get_namespace = (class_get_namespace_t)xdl_sym(handle, "il2cpp_class_get_namespace", nullptr);

    bool profiler_ok = false;
    if (install_enter && set_events && fn_method_get_name) {
        install_enter(on_method_enter);
        set_events(IL2CPP_PROFILE_METHOD_ENTER);
        LOGI("IL2CPP Profiler installed and events set");
        profiler_ok = true;
    }

    // 2. Aggressively hook runtime_invoke as well
    void *sym_invoke = xdl_sym(handle, "il2cpp_runtime_invoke", nullptr);
    if (sym_invoke) {
        DobbyHook(sym_invoke, (void*)hook_il2cpp_invoke, (void**)&orig_il2cpp_invoke);
        LOGI("il2cpp_runtime_invoke hooked as fallback/addition");
    }

    xdl_close(handle);
    return profiler_ok || (sym_invoke != nullptr);
}

// ── ART Hook with PrettyMethod ───────────────────────────────────────────────

typedef void (*ArtMethodInvoke_t)(void* method, void* thread, uint32_t* args, uint32_t args_size, void* result, const char* shorty);
static ArtMethodInvoke_t orig_art_method_invoke = nullptr;

static void hook_art_method_invoke(void* method, void* thread, uint32_t* args, uint32_t args_size, void* result, const char* shorty) {
    if (gInitialised && method && fn_pretty_method) {
        std::string pretty = fn_pretty_method(method, true);
        // PrettyMethod usually returns "className.methodName(args)"
        size_t lastDot = pretty.find_last_of('.');
        if (lastDot != std::string::npos) {
            std::string cls = pretty.substr(0, lastDot);
            std::string mth = pretty.substr(lastDot + 1);
            TRACE("METHOD_ENTER", cls.c_str(), mth.c_str(), "", 0, 0);
        } else {
            TRACE("METHOD_ENTER", "ART", pretty.c_str(), "", 0, 0);
        }
    }
    orig_art_method_invoke(method, thread, args, args_size, result, shorty);
}

static bool hookArt() {
    void *handle = xdl_open("libart.so", XDL_DEFAULT);
    if (!handle) return false;

    // Resolve PrettyMethod to get actual names
    // Mangled: art::ArtMethod::PrettyMethod(bool)
    fn_pretty_method = (PrettyMethod_t)xdl_sym(handle, "_ZN3art9ArtMethod12PrettyMethodEb", nullptr);

    const char* sym_invoke = "_ZN3art9ArtMethod6InvokeEPNS_6ThreadEPjjPNS_6JValueEPKc";
    void* sym = xdl_sym(handle, sym_invoke, nullptr);
    
    if (sym) {
        DobbyHook(sym, (void*)hook_art_method_invoke, (void**)&orig_art_method_invoke);
        LOGI("art::ArtMethod::Invoke hooked with PrettyMethod support");
        xdl_close(handle);
        return true;
    }

    LOGW("art::ArtMethod::Invoke not found");
    xdl_close(handle);
    return false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_niunaijun_blackbox_fake_hook_MethodTracer_nativeInit(
        JNIEnv * , jclass , jint apiLevel) {
    if (gInitialised) return JNI_TRUE;
    
    bool il2cpp = hookIl2Cpp();
    bool art    = hookArt();
    
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
