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

typedef void (*Il2CppProfilerMethodCallback)(const MethodInfo* method);
typedef void (*install_enter_t)(Il2CppProfilerMethodCallback callback);
typedef const char* (*method_get_name_t)(const MethodInfo* method);
typedef const Il2CppClass* (*method_get_class_t)(const MethodInfo* method);
typedef const char* (*class_get_name_t)(const Il2CppClass* klass);
typedef const char* (*class_get_namespace_t)(const Il2CppClass* klass);

static method_get_name_t      fn_method_get_name      = nullptr;
static method_get_class_t     fn_method_get_class     = nullptr;
static class_get_name_t       fn_class_get_name       = nullptr;
static class_get_namespace_t  fn_class_get_namespace  = nullptr;

// ── Helpers ──────────────────────────────────────────────────────────────────

static inline bool isReadablePtr(const void *p) {
    uintptr_t v = reinterpret_cast<uintptr_t>(p);
    if (v == 0 || (v & 0x3) || v >= 0x0000800000000000ULL) return false;
    return true;
}

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

static bool hookIl2Cpp() {
    void *handle = xdl_open("libil2cpp.so", XDL_DEFAULT);
    if (!handle) return false;

    install_enter_t install_enter = (install_enter_t)xdl_sym(handle, "il2cpp_profiler_install_method_enter", nullptr);
    fn_method_get_name = (method_get_name_t)xdl_sym(handle, "il2cpp_method_get_name", nullptr);
    fn_method_get_class = (method_get_class_t)xdl_sym(handle, "il2cpp_method_get_class", nullptr);
    fn_class_get_name = (class_get_name_t)xdl_sym(handle, "il2cpp_class_get_name", nullptr);
    fn_class_get_namespace = (class_get_namespace_t)xdl_sym(handle, "il2cpp_class_get_namespace", nullptr);

    if (install_enter && fn_method_get_name) {
        install_enter(on_method_enter);
        LOGI("IL2CPP Profiler installed successfully");
        xdl_close(handle);
        return true;
    }

    LOGW("IL2CPP Profiler symbols not found, falling back to runtime_invoke hook");
    // Fallback logic could go here, but Profiler is better for IL2CPP
    xdl_close(handle);
    return false;
}

// ── ART Hook ─────────────────────────────────────────────────────────────────

typedef void (*ArtMethodInvoke_t)(void* method, void* thread, uint32_t* args, uint32_t args_size, void* result, const char* shorty);
static ArtMethodInvoke_t orig_art_method_invoke = nullptr;

static void hook_art_method_invoke(void* method, void* thread, uint32_t* args, uint32_t args_size, void* result, const char* shorty) {
    if (gInitialised && method) {
        // In a real ART hook, you'd resolve the method name here.
        // For simplicity and to avoid crashes with ART's internal structures,
        // we just log that a method was entered. 
        // Note: Getting names from ArtMethod* requires calling internal ART functions.
        TRACE("METHOD_ENTER", "ART", "ArtMethod::Invoke", "", 0, 0);
    }
    orig_art_method_invoke(method, thread, args, args_size, result, shorty);
}

static bool hookArt() {
    void *handle = xdl_open("libart.so", XDL_DEFAULT);
    if (!handle) return false;

    // ArtMethod::Invoke is the bottleneck for almost all Java calls (JNI, Reflection, and sometimes interpreted)
    // The mangled name varies, but this is a common one for Android 10+
    const char* sym_invoke = "_ZN3art9ArtMethod6InvokeEPNS_6ThreadEPjjPNS_6JValueEPKc";
    void* sym = xdl_sym(handle, sym_invoke, nullptr);
    
    if (sym) {
        DobbyHook(sym, (void*)hook_art_method_invoke, (void**)&orig_art_method_invoke);
        LOGI("art::ArtMethod::Invoke hooked");
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
