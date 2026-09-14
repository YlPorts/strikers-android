#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>

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
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_ylports_strikers_MainActivity_nativeBootstrapInit(
        JNIEnv* env, jclass, jstring files_dir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_filesDir = JStringToUtf8(env, files_dir);

    __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "Native bootstrap initialized; filesDir=%s",
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

    std::string info = "Native bootstrap OK · ";
    info += arch;
    info += " · API ";
    info += std::to_string(__ANDROID_API__);
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "libstrikers_android loaded");
    return JNI_VERSION_1_6;
}
