#include <jni.h>

#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

namespace {

static char g_log_path[1024];
static char g_altstack[64 * 1024];
static volatile sig_atomic_t g_in_crash_handler = 0;
static bool g_memory_thread_started = false;

static void write_all(int fd, const char* data, size_t size)
{
    while (size > 0)
    {
        const ssize_t written = write(fd, data, size);
        if (written <= 0)
            return;
        data += written;
        size -= static_cast<size_t>(written);
    }
}

static void write_literal(int fd, const char* text)
{
    write_all(fd, text, strlen(text));
}

static void write_hex(int fd, const char* label, uintptr_t value)
{
    static const char digits[] = "0123456789abcdef";
    char line[96];
    size_t pos = 0;

    while (*label != '\0' && pos + 1 < sizeof(line))
        line[pos++] = *label++;

    if (pos + 2 < sizeof(line))
    {
        line[pos++] = '0';
        line[pos++] = 'x';
    }

    bool started = false;
    for (int shift = static_cast<int>(sizeof(uintptr_t) * 8) - 4; shift >= 0; shift -= 4)
    {
        const unsigned nibble = static_cast<unsigned>((value >> shift) & 0xfu);
        if (!started && nibble == 0 && shift != 0)
            continue;
        started = true;
        if (pos + 1 < sizeof(line))
            line[pos++] = digits[nibble];
    }

    if (pos + 1 < sizeof(line))
        line[pos++] = '\n';
    write_all(fd, line, pos);
}

static void write_dec(int fd, const char* label, long value)
{
    char line[96];
    size_t pos = 0;
    while (*label != '\0' && pos + 1 < sizeof(line))
        line[pos++] = *label++;

    unsigned long magnitude;
    if (value < 0)
    {
        if (pos + 1 < sizeof(line))
            line[pos++] = '-';
        magnitude = static_cast<unsigned long>(-(value + 1)) + 1;
    }
    else
    {
        magnitude = static_cast<unsigned long>(value);
    }

    char digits[32];
    size_t count = 0;
    do
    {
        digits[count++] = static_cast<char>('0' + (magnitude % 10));
        magnitude /= 10;
    } while (magnitude != 0 && count < sizeof(digits));

    while (count > 0 && pos + 1 < sizeof(line))
        line[pos++] = digits[--count];
    if (pos + 1 < sizeof(line))
        line[pos++] = '\n';
    write_all(fd, line, pos);
}

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

static const char* signal_name(int sig)
{
    switch (sig)
    {
    case SIGSEGV: return "SIGSEGV";
    case SIGBUS: return "SIGBUS";
    case SIGABRT: return "SIGABRT";
    case SIGILL: return "SIGILL";
    case SIGFPE: return "SIGFPE";
    default: return "SIGNAL";
    }
}

static void dump_proc_maps(int out_fd)
{
    const int maps_fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (maps_fd < 0)
        return;

    write_literal(out_fd, "--- /proc/self/maps ---\n");
    char buffer[2048];
    for (;;)
    {
        const ssize_t count = read(maps_fd, buffer, sizeof(buffer));
        if (count <= 0)
            break;
        write_all(out_fd, buffer, static_cast<size_t>(count));
    }
    write_literal(out_fd, "--- end maps ---\n");
    close(maps_fd);
}

static void dump_registers(int fd, const ucontext_t* uc)
{
#if defined(__aarch64__)
    if (uc == nullptr)
        return;

    write_hex(fd, "pc=", static_cast<uintptr_t>(uc->uc_mcontext.pc));
    write_hex(fd, "lr=", static_cast<uintptr_t>(uc->uc_mcontext.regs[30]));
    write_hex(fd, "sp=", static_cast<uintptr_t>(uc->uc_mcontext.sp));
    write_hex(fd, "fp/x29=", static_cast<uintptr_t>(uc->uc_mcontext.regs[29]));
    for (int i = 0; i < 8; ++i)
    {
        static const char* labels[] = {
            "x0=", "x1=", "x2=", "x3=", "x4=", "x5=", "x6=", "x7="
        };
        write_hex(fd, labels[i], static_cast<uintptr_t>(uc->uc_mcontext.regs[i]));
    }
#else
    (void)fd;
    (void)uc;
#endif
}

static void native_crash_handler(int sig, siginfo_t* info, void* context)
{
    if (g_in_crash_handler)
        return;
    g_in_crash_handler = 1;

    int fd = -1;
    if (g_log_path[0] != '\0')
        fd = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);

    if (fd >= 0)
    {
        write_literal(fd, "\n*** STRIKERS NATIVE CRASH ***\n");
        write_literal(fd, "signal=");
        write_literal(fd, signal_name(sig));
        write_literal(fd, "\n");
        write_dec(fd, "signal_number=", sig);

        if (info != nullptr)
        {
            write_dec(fd, "si_code=", info->si_code);
            write_hex(fd, "fault=", reinterpret_cast<uintptr_t>(info->si_addr));
        }

        dump_registers(fd, reinterpret_cast<const ucontext_t*>(context));
        write_literal(fd, "note=Android debuggerd/ApplicationExitInfo keeps the system native tombstone; PC/LR can be resolved against the maps below.\n");
        dump_proc_maps(fd);
        fsync(fd);
        close(fd);
    }

    /*
     * SA_RESETHAND restores the default disposition as soon as this handler is
     * entered. Returning is deliberate: synchronous faults (SIGSEGV/SIGBUS/
     * SIGILL/SIGFPE) fault again at the original instruction and Android's
     * debuggerd receives the real crash context. abort() similarly re-raises
     * SIGABRT after a user handler returns. This preserves ApplicationExitInfo
     * REASON_CRASH_NATIVE instead of disguising the failure as _exit(128+sig).
     */
    g_in_crash_handler = 0;
}

static void install_handlers()
{
    stack_t stack;
    memset(&stack, 0, sizeof(stack));
    stack.ss_sp = g_altstack;
    stack.ss_size = sizeof(g_altstack);
    stack.ss_flags = 0;
    sigaltstack(&stack, nullptr);

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = native_crash_handler;
    sigemptyset(&action.sa_mask);
    action.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESETHAND;

    sigaction(SIGSEGV, &action, nullptr);
    sigaction(SIGBUS, &action, nullptr);
    sigaction(SIGABRT, &action, nullptr);
    sigaction(SIGILL, &action, nullptr);
    sigaction(SIGFPE, &action, nullptr);
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_ylports_strikers_GameBootstrapActivity_nativeInstallCrashDiagnostics(
    JNIEnv* env, jclass, jstring log_path)
{
    g_log_path[0] = '\0';
    if (log_path != nullptr)
    {
        const char* path = env->GetStringUTFChars(log_path, nullptr);
        if (path != nullptr)
        {
            strncpy(g_log_path, path, sizeof(g_log_path) - 1);
            g_log_path[sizeof(g_log_path) - 1] = '\0';
            env->ReleaseStringUTFChars(log_path, path);
        }
    }
    install_handlers();
    start_memory_thread();
}
