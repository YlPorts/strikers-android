#include <jni.h>

#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <ucontext.h>
#include <unistd.h>

namespace {

static char g_log_path[1024];
static char g_altstack[64 * 1024];

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

static void native_crash_handler(int sig, siginfo_t* info, void* context)
{
    int fd = -1;
    if (g_log_path[0] != '\0')
        fd = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);

    if (fd >= 0)
    {
        write_literal(fd, "\n*** STRIKERS EARLY NATIVE CRASH ***\n");
        write_literal(fd, "signal=");
        write_literal(fd, signal_name(sig));
        write_literal(fd, "\n");

        if (info != nullptr)
            write_hex(fd, "fault=", reinterpret_cast<uintptr_t>(info->si_addr));

#if defined(__aarch64__)
        if (context != nullptr)
        {
            const auto* uc = reinterpret_cast<const ucontext_t*>(context);
            write_hex(fd, "pc=", static_cast<uintptr_t>(uc->uc_mcontext.pc));
            write_hex(fd, "lr=", static_cast<uintptr_t>(uc->uc_mcontext.regs[30]));
            write_hex(fd, "sp=", static_cast<uintptr_t>(uc->uc_mcontext.sp));
        }
#endif

        dump_proc_maps(fd);
        close(fd);
    }

    // Exit immediately instead of returning into a corrupted constructor. The main launcher
    // process remains alive and will show the durable run log on the next screen.
    _exit(128 + sig);
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
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;

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
}
