#include <jni.h>
#include "crash_capture.hpp"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>

namespace {
using namespace strikers::diagnostics;
static bool g_memory_thread_started = false;

static long parse_status_kb(const char* text, const char* key)
{
    const char* p = strstr(text, key);
    if (p == nullptr)
        return -1;
    p += strlen(key);
    while (*p == ' ' || *p == '\t')
        ++p;
    return strtol(p, nullptr, 10);
}

static long monotonic_ms()
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return static_cast<long>(ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL);
}

static void log_memory_snapshot()
{
    if (g_log_path[0] == '\0')
        return;

    const int status_fd = open("/proc/self/status", O_RDONLY | O_CLOEXEC);
    if (status_fd < 0)
        return;

    char status[16384];
    const ssize_t count = read(status_fd, status, sizeof(status) - 1);
    close(status_fd);
    if (count <= 0)
        return;
    status[count] = '\0';

    const long vm_size = parse_status_kb(status, "VmSize:");
    const long vm_rss = parse_status_kb(status, "VmRSS:");
    const long rss_anon = parse_status_kb(status, "RssAnon:");
    const long rss_file = parse_status_kb(status, "RssFile:");
    const long rss_shmem = parse_status_kb(status, "RssShmem:");
    const long vm_swap = parse_status_kb(status, "VmSwap:");

    char line[320];
    const int len = snprintf(line, sizeof(line),
                             "[memdiag] t=%ld rss=%ldKB anon=%ldKB file=%ldKB shmem=%ldKB vmsize=%ldKB swap=%ldKB\n",
                             monotonic_ms(), vm_rss, rss_anon, rss_file, rss_shmem, vm_size, vm_swap);
    if (len <= 0)
        return;

    const int log_fd = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (log_fd < 0)
        return;
    const size_t bytes = static_cast<size_t>(len) < sizeof(line) ? static_cast<size_t>(len) : sizeof(line) - 1;
    write_all(log_fd, line, bytes);
    close(log_fd);
}

static void* memory_thread_main(void*)
{
    // Session-level memory trend, not a profiler: one sample every five seconds
    // avoids four /proc reads and file opens/writes per second during gameplay.
    // The signal handler still captures faults immediately.
    for (;;)
    {
        log_memory_snapshot();
        usleep(5000000);
    }
    return nullptr;
}

static void start_memory_thread()
{
    if (g_memory_thread_started)
        return;
    g_memory_thread_started = true;

    pthread_t thread;
    if (pthread_create(&thread, nullptr, memory_thread_main, nullptr) == 0)
        pthread_detach(thread);
    else
        g_memory_thread_started = false;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_ylports_strikers_GameBootstrapActivity_nativeInstallCrashDiagnostics(
    JNIEnv* env, jclass, jstring log_path)
{
    if (log_path == nullptr) return;
    const char* path = env->GetStringUTFChars(log_path, nullptr);
    if (path == nullptr) return;
    strikers::diagnostics::install_handlers(path);
    env->ReleaseStringUTFChars(log_path, path);
    start_memory_thread();
}
