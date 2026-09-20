#ifndef PORT_ANDROID_LOADING_H
#define PORT_ANDROID_LOADING_H

// Optional Android UI hooks. Other native builds do not link the Java bridge.
#if defined(__ANDROID__)
#ifdef __cplusplus
extern "C" {
#endif
void PortAndroidSetLoadActive(int active) __attribute__((weak));
void PortAndroidSetShaderWait(int active) __attribute__((weak));
#ifdef __cplusplus
}
#endif
#endif
#endif
