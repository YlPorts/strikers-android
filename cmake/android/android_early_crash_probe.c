/*
 * Android-only early loader diagnostics.
 *
 * libstrikers.so has a large C/C++ static-initializer set. Java cannot catch a
 * SIGSEGV/SIGABRT raised while System.loadLibrary() is running, so this probe
 * redirects stderr early. Normal builds also install a small fallback signal
 * handler. Diagnostic builds deliberately keep the richer strikers_diag handler
 * that was armed by GameBootstrapActivity before libstrikers.so was loaded.
 */

#if defined(__ANDROID__)

#include <execinfo.h>
#include <fcntl.h>
#include <signal.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static char g_early_altstack[64 * 1024];

static const char* strikers_signal_name(int sig)
{
    switch (sig) {
    case SIGSEGV: return "SIGSEGV";
    case SIGBUS:  return "SIGBUS";
    case SIGABRT: return "SIGABRT";
    case SIGILL:  return "SIGILL";
    case SIGFPE:  return "SIGFPE";
    default:      return "SIGNAL";
    }
}

static void strikers_early_crash_handler(int sig)
{
    const char* prefix = "\n*** strikers android: early native load crash: ";
    const char* name = strikers_signal_name(sig);
    const char* suffix = " ***\n";

    write(STDERR_FILENO, prefix, strlen(prefix));
    write(STDERR_FILENO, name, strlen(name));
    write(STDERR_FILENO, suffix, strlen(suffix));

    void* frames[64];
    int count = backtrace(frames, 64);
    if (count > 0)
        backtrace_symbols_fd(frames, count, STDERR_FILENO);

    fsync(STDERR_FILENO);
    signal(sig, SIG_DFL);
    raise(sig);
}

__attribute__((constructor(101))) static void strikers_android_early_probe(void)
{
    const char* path = getenv("STRIKERS_CRASH_LOG");
    if (path != NULL && *path != '\0') {
        int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
        if (fd >= 0) {
            if (fd != STDERR_FILENO) {
                dup2(fd, STDERR_FILENO);
                close(fd);
            }
            static const char marker[] =
                "[android] early native crash probe active before static constructors\n";
            write(STDERR_FILENO, marker, sizeof(marker) - 1);
            fsync(STDERR_FILENO);
        }
    }

    /* GameBootstrapActivity has already loaded strikers_diag in the dedicated
       diagnostic APK. Do not replace its SA_SIGINFO handler: it records the
       original fault address/registers/backtrace and then lets Android create a
       genuine REASON_CRASH_NATIVE record for the :game process. */
    const char* diagnostic = getenv("STRIKERS_DIAGNOSTIC_BUILD");
    if (diagnostic != NULL && diagnostic[0] == '1') {
        static const char marker[] =
            "[android] diagnostic build: keeping strikers_diag crash handler armed\n";
        write(STDERR_FILENO, marker, sizeof(marker) - 1);
        fsync(STDERR_FILENO);
        return;
    }

    stack_t ss;
    memset(&ss, 0, sizeof(ss));
    ss.ss_sp = g_early_altstack;
    ss.ss_size = sizeof(g_early_altstack);
    sigaltstack(&ss, NULL);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = strikers_early_crash_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_ONSTACK;

    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
}

#endif
