#include <jni.h>
#include <android/log.h>

#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <strings.h>

#include "port/disc.h"

namespace {
constexpr const char* kLogTag = "StrikersAndroid";
std::mutex g_mutex;
std::string g_filesDir;

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
}  // namespace

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

    FILE* redirected = freopen(path.c_str(), "w", stderr);
    if (redirected != nullptr) {
        setvbuf(stderr, nullptr, _IOLBF, 0);
        fprintf(stderr, "[android] Strikers native run log started\n");
        fflush(stderr);
    } else {
        __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "Could not redirect stderr to %s",
                path.c_str());
    }
}

// Do not define JNI_OnLoad here. SDL3's Android backend owns JNI_OnLoad and uses
// it to register SDLActivity, audio, controller and HID native methods. Defining a
// second copy in this bridge makes the full libstrikers.so link fail and would
// bypass SDL's required Java/native registration even if it were allowed.
