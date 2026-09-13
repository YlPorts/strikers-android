#include <jni.h>
#include <android/log.h>
#include <sys/utsname.h>
#include <string>

namespace {
constexpr const char* kTag = "StrikersAndroid";
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_ylports_strikers_MainActivity_nativeStatus(JNIEnv* env, jobject) {
    utsname info{};
    const bool haveUname = uname(&info) == 0;

    std::string message = "Strikers Android bootstrap\n\n";
    message += "NDK native library loaded successfully.\n";
#if defined(__aarch64__)
    message += "ABI: arm64-v8a\n";
#else
    message += "ABI: unsupported test ABI\n";
#endif
    message += "Next milestone: SDL/Aurora + game main.\n";
    if (haveUname) {
        message += "Kernel: ";
        message += info.release;
        message += "\n";
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
    return env->NewStringUTF(message.c_str());
}
