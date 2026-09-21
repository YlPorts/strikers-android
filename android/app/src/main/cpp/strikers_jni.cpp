#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <strings.h>
#include <unistd.h>

#include <dolphin/pad.h>
#include <aurora/gfx.h>

#include "port/disc.h"
#include "touch_state.h"

namespace {
constexpr const char* kLogTag = "StrikersAndroid";
std::mutex g_mutex;
std::string g_filesDir;

// Android MotionEvents arrive on the UI thread while PADRead runs on the game
// thread. Keep the JNI side lock-free and copy a coherent snapshot into Aurora's
// virtual PAD immediately before the game's sampling callback each frame.
static_assert(std::atomic<std::uint64_t>::is_always_lock_free,
              "Android touch snapshots must be lock-free on the target ABI");
std::atomic<std::uint64_t> g_touchState{0};

JavaVM* g_saveVm = nullptr;
jobject g_saveFolder = nullptr;
jmethodID g_saveOpen = nullptr, g_saveList = nullptr, g_saveMkdir = nullptr, g_saveDelete = nullptr;
jmethodID g_saveIsDirectory = nullptr;

struct SaveEnv {
    JNIEnv* env = nullptr;
    bool attached = false;
    SaveEnv() {
        if (!g_saveVm) return;
        if (g_saveVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            attached = g_saveVm->AttachCurrentThread(&env, nullptr) == JNI_OK;
            if (!attached) env = nullptr;
        }
    }
    ~SaveEnv() { if (attached) g_saveVm->DetachCurrentThread(); }
    bool ok() {
        if (!env) return false;
        if (!env->ExceptionCheck()) return true;
        env->ExceptionDescribe();
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Save folder access failed");
        return false;
    }
    jstring path(const char* value) {
        constexpr const char* prefix = "/strikers-saf/";
        if (!env || !value || strncmp(value, prefix, strlen(prefix)) != 0) return nullptr;
        return env->NewStringUTF(value + strlen(prefix));
    }
};

std::string JStringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const char* raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) {
        return {};
    }

    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

int FindCommonIni(void* user, const char* path, unsigned, unsigned, int is_dir) {
    if (is_dir || path == nullptr) {
        return 0;
    }

    bool* found = static_cast<bool*>(user);
    const char* p = path;
    while (*p == '/') {
        ++p;
    }
    if (strcasecmp(p, "common.ini") == 0) {
        *found = true;
    }
    return 0;
}

jstring ErrorString(JNIEnv* env, const std::string& text) {
    return env->NewStringUTF(text.c_str());
}

s8 ClampAxis(jint value) {
    int v = static_cast<int>(value);
    if (v < -127) {
        v = -127;
    } else if (v > 127) {
        v = 127;
    }
    return static_cast<s8>(v);
}

u8 ClampTrigger(jint value) {
    int v = static_cast<int>(value);
    if (v < 0) {
        v = 0;
    } else if (v > 255) {
        v = 255;
    }
    return static_cast<u8>(v);
}
}  // namespace

extern "C" JNIEXPORT jfloat JNICALL
Java_com_ylports_strikers_StrikersActivity_nativePresentedFps(JNIEnv*, jclass) {
    // This API snapshots the presentation timestamps under the renderer's stats mutex.
    return aurora_get_fps();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ylports_strikers_MainActivity_nativeBootstrapInit(
        JNIEnv* env, jclass, jstring files_dir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_filesDir = JStringToUtf8(env, files_dir);

    __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "Native Android bridge initialized; filesDir=%s",
            g_filesDir.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ylports_strikers_MainActivity_nativeBuildInfo(JNIEnv* env, jclass) {
#if defined(__aarch64__)
    constexpr const char* arch = "arm64-v8a";
#elif defined(__arm__)
    constexpr const char* arch = "armeabi-v7a";
#elif defined(__x86_64__)
    constexpr const char* arch = "x86_64";
#else
    constexpr const char* arch = "unknown";
#endif

    std::string info = "Native Strikers core · ";
    info += arch;
    info += " · API ";
    info += std::to_string(__ANDROID_API__);
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ylports_strikers_GameBootstrapActivity_nativeValidateDiscPath(
        JNIEnv* env, jclass, jstring native_path) {
    const std::string path = JStringToUtf8(env, native_path);
    if (path.empty()) {
        return ErrorString(env, "La ruta nativa de la imagen está vacía.");
    }

    char err[2048] = {};
    PortDisc* disc = port_disc_open(path.c_str(), err, sizeof(err));
    if (disc == nullptr) {
        std::string message = "El lector de discos no pudo abrir la imagen.\n\n";
        message += err[0] != '\0' ? err : "Sin detalles del lector.";
        return ErrorString(env, message);
    }

    unsigned char header[32] = {};
    if (port_disc_read(disc, header, sizeof(header), 0) != static_cast<long>(sizeof(header))) {
        port_disc_close(disc);
        return ErrorString(env, "La imagen se abrió, pero no se pudo leer el encabezado GameCube.");
    }

    char disc_id[7] = {};
    std::memcpy(disc_id, header, 6);
    if (std::memcmp(header, "G4Q", 3) != 0) {
        std::string message = "La imagen es legible, pero no es Super Mario Strikers.\n\nID detectado: ";
        message += disc_id;
        message += "\nSe esperaba un disco cuyo ID empiece por G4Q.";
        port_disc_close(disc);
        return ErrorString(env, message);
    }

    bool found_common_ini = false;
    char walk_err[2048] = {};
    if (port_disc_walk(disc, FindCommonIni, &found_common_ini, walk_err, sizeof(walk_err)) != 0) {
        std::string message = "El disco tiene un encabezado de Strikers, pero su sistema de archivos no se pudo leer.\n\n";
        message += walk_err[0] != '\0' ? walk_err : "Sin detalles del FST.";
        port_disc_close(disc);
        return ErrorString(env, message);
    }

    if (!found_common_ini) {
        std::string message = "El disco tiene ID ";
        message += disc_id;
        message += ", pero no contiene common.ini. La imagen puede estar dañada o incompleta.";
        port_disc_close(disc);
        return ErrorString(env, message);
    }

    __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "Disc validation OK: id=%s format=%s path=%s",
            disc_id,
            port_disc_format(disc),
            path.c_str());
    port_disc_close(disc);
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ylports_strikers_GameBootstrapActivity_nativeBeginRunLog(
        JNIEnv* env, jclass, jstring log_path) {
    const std::string path = JStringToUtf8(env, log_path);
    if (path.empty()) {
        return;
    }

    // Append instead of truncating: Java has already written durable markers for
    // every step before SDL is entered. Keep stderr completely unbuffered so a
    // native abort cannot strand the last useful line in stdio buffers.
    FILE* redirected = freopen(path.c_str(), "a", stderr);
    if (redirected != nullptr) {
        setvbuf(stderr, nullptr, _IONBF, 0);
        fprintf(stderr, "[android] native stderr attached; entering SDL next\n");
        fflush(stderr);
        fsync(fileno(stderr));
    } else {
        __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "Could not redirect stderr to %s",
                path.c_str());
    }
}

// Called by src/platform/aurora_compat.c on the game thread immediately before
// VBlankPadUpdate/PADRead. This removes the UI-thread/PADRead data race that can
// otherwise make analog motion intermittent while still merging with real pads.
extern "C" void PortAndroidApplyTouchState(void) {
    const auto touch = strikers::touch::Unpack(g_touchState.load(std::memory_order_relaxed));
    PADStatus status{};
    status.button = static_cast<u16>(touch.buttons);
    status.stickX = ClampAxis(touch.stickX);
    status.stickY = ClampAxis(touch.stickY);
    status.substickX = ClampAxis(touch.substickX);
    status.substickY = ClampAxis(touch.substickY);
    status.triggerLeft = ClampTrigger(touch.triggerLeft);
    status.triggerRight = ClampTrigger(touch.triggerRight);
    status.analogA = (status.button & PAD_BUTTON_A) != 0 ? 255 : 0;
    status.analogB = (status.button & PAD_BUTTON_B) != 0 ? 255 : 0;
    status.err = PAD_ERR_NONE;
    PADSetVirtualStatus(PAD_CHAN0, &status);
}

// MotionEvents run on Android's UI thread. Store them atomically; the game
// thread consumes the latest snapshot once per frame in PortAndroidApplyTouchState.
extern "C" JNIEXPORT void JNICALL
Java_com_ylports_strikers_StrikersActivity_nativeSetTouchState(
        JNIEnv*, jclass, jint buttons, jint stick_x, jint stick_y,
        jint substick_x, jint substick_y, jint trigger_left, jint trigger_right) {
    g_touchState.store(strikers::touch::Pack({buttons, stick_x, stick_y,
            substick_x, substick_y, trigger_left, trigger_right}), std::memory_order_relaxed);
}

// Configured on the bootstrap thread before the native game starts. The global
// reference lives for the :game process; no Activity or UI thread is retained.
extern "C" JNIEXPORT void JNICALL
Java_com_ylports_strikers_GameBootstrapActivity_nativeSetSaveFolder(JNIEnv* env, jclass, jobject folder) {
    if (g_saveFolder) env->DeleteGlobalRef(g_saveFolder);
    g_saveFolder = folder ? env->NewGlobalRef(folder) : nullptr;
    if (!folder) return;
    env->GetJavaVM(&g_saveVm);
    jclass cls = env->GetObjectClass(folder);
    g_saveOpen = env->GetMethodID(cls, "open", "(Ljava/lang/String;I)I");
    g_saveList = env->GetMethodID(cls, "list", "(Ljava/lang/String;)[Ljava/lang/String;");
    g_saveMkdir = env->GetMethodID(cls, "mkdir", "(Ljava/lang/String;)Z");
    g_saveIsDirectory = env->GetMethodID(cls, "isDirectory", "(Ljava/lang/String;)Z");
    g_saveDelete = env->GetMethodID(cls, "delete", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(cls);
}

extern "C" bool PortAndroidSaveEnabled() { return g_saveFolder != nullptr; }

extern "C" int PortAndroidSaveOpen(const char* path, int mode) {
    SaveEnv scope;
    auto name = scope.path(path);
    if (!name || !g_saveFolder || !g_saveOpen) return -1;
    const int fd = scope.env->CallIntMethod(g_saveFolder, g_saveOpen, name, mode);
    scope.env->DeleteLocalRef(name);
    return scope.ok() ? fd : -1;
}

static bool SavePathOperation(const char* path, jmethodID method) {
    SaveEnv scope;
    auto name = scope.path(path);
    if (!name || !g_saveFolder || !method) return false;
    const bool success = scope.env->CallBooleanMethod(g_saveFolder, method, name);
    scope.env->DeleteLocalRef(name);
    return scope.ok() && success;
}
extern "C" bool PortAndroidSaveMkdir(const char* path) { return SavePathOperation(path, g_saveMkdir); }
extern "C" bool PortAndroidSaveIsDirectory(const char* path) { return SavePathOperation(path, g_saveIsDirectory); }
extern "C" bool PortAndroidSaveDelete(const char* path) { return SavePathOperation(path, g_saveDelete); }

extern "C" bool PortAndroidSaveList(const char* path, void (*append)(const char*, void*), void* data) {
    SaveEnv scope;
    auto name = scope.path(path);
    if (!name || !g_saveFolder || !g_saveList) return false;
    auto names = static_cast<jobjectArray>(scope.env->CallObjectMethod(g_saveFolder, g_saveList, name));
    scope.env->DeleteLocalRef(name);
    if (!scope.ok() || !names) return false;
    for (jsize i = 0, size = scope.env->GetArrayLength(names); i < size; ++i) {
        auto item = static_cast<jstring>(scope.env->GetObjectArrayElement(names, i));
        const std::string text = JStringToUtf8(scope.env, item);
        scope.env->DeleteLocalRef(item);
        if (!scope.ok()) { scope.env->DeleteLocalRef(names); return false; }
        append(text.c_str(), data);
    }
    scope.env->DeleteLocalRef(names);
    return true;
}

// Do not define JNI_OnLoad here. SDL3's Android backend owns JNI_OnLoad and uses
// it to register SDLActivity, audio, controller and HID native methods. Defining a
// second copy in this bridge makes the full libstrikers.so link fail and would
// bypass SDL's required Java/native registration even if it were allowed.
