#pragma once

// No heap, stdio, symbol lookup, unwinding or locks in the signal handler.
// Keep this independent of JNI so the actual handler can be tested in a child process.
#include <atomic>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

namespace strikers { namespace diagnostics {

static char g_log_path[1024];
static char g_altstack[64 * 1024];
static struct sigaction g_previous_actions[NSIG];
static std::atomic_flag g_capturing = ATOMIC_FLAG_INIT;
static bool g_installed = false;

static void write_all(int fd, const char* data, size_t size)
{
    while (size > 0)
    {
        const ssize_t written = write(fd, data, size);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return;
        data += written;
        size -= static_cast<size_t>(written);
    }
}

static void write_literal(int fd, const char* text)
{
    write_all(fd, text, strlen(text));
}

static void write_number(int fd, const char* label, uint64_t value, unsigned base)
{
    static const char digits[] = "0123456789abcdef";
    char reversed[64];
    size_t count = 0;
    do {
        reversed[count++] = digits[value % base];
        value /= base;
    } while (value && count < sizeof(reversed));
    char line[160];
    size_t size = 0;
    while (*label && size < 80) line[size++] = *label++;
    if (base == 16) { line[size++] = '0'; line[size++] = 'x'; }
    while (count) line[size++] = reversed[--count];
    line[size++] = '\n';
    write_all(fd, line, size);
}

static uintptr_t read_hex(const char*& text)
{
    uintptr_t value = 0;
    for (;;) {
        const char c = *text;
        const int digit = c >= '0' && c <= '9' ? c - '0'
                : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
        if (digit < 0) break;
        value = (value << 4) | static_cast<unsigned>(digit);
        ++text;
    }
    return value;
}

static bool relevant_mapping(const char* line, uintptr_t pc, uintptr_t lr, uintptr_t fault)
{
    const char* cursor = line;
    const uintptr_t start = read_hex(cursor);
    if (cursor == line || *cursor++ != '-') return false;
    const uintptr_t end = read_hex(cursor);
    return (pc && start <= pc && pc < end) || (lr && start <= lr && lr < end)
            || (fault && start <= fault && fault < end)
            || strstr(line, "/libstrikers.so") || strstr(line, "/libSDL3.so")
            || strstr(line, "/libstrikers_diag.so");
}

static void dump_relevant_maps(int out, uintptr_t pc, uintptr_t lr, uintptr_t fault)
{
    const int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    write_literal(out, "--- relevant mappings (PC/LR/fault/game) ---\n");
    // Bound both the scan and output. A complete Android map can swamp the fault
    // header; anonymous heaps/JARs unrelated to these addresses are not useful here.
    char buffer[4096], line[2048];
    size_t scanned = 0, output = 0, length = 0;
    bool truncated = false;
    while (scanned < 2 * 1024 * 1024 && output < 12 * 1024) {
        const ssize_t count = read(fd, buffer, sizeof(buffer));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        scanned += static_cast<size_t>(count);
        for (ssize_t i = 0; i < count; ++i) {
            if (buffer[i] == '\n') {
                line[length] = '\0';
                if (!truncated && relevant_mapping(line, pc, lr, fault)
                        && output + length + 1 <= 12 * 1024) {
                    write_all(out, line, length);
                    write_literal(out, "\n");
                    output += length + 1;
                }
                length = 0;
                truncated = false;
            } else if (length + 1 < sizeof(line)) {
                line[length++] = buffer[i];
            } else {
                truncated = true;
            }
        }
    }
    if (scanned >= 2 * 1024 * 1024 || output >= 12 * 1024)
        write_literal(out, "maps_scan_limit_reached\n");
    close(fd);
}

static const char* signal_name(int sig)
{
    switch (sig) {
    case SIGSEGV: return "SIGSEGV";
    case SIGBUS: return "SIGBUS";
    case SIGABRT: return "SIGABRT";
    case SIGILL: return "SIGILL";
    case SIGFPE: return "SIGFPE";
    default: return "SIGNAL";
    }
}

static void native_crash_handler(int sig, siginfo_t* info, void* context)
{
    const int saved_errno = errno;
    // Restore the real previous disposition, not SIG_DFL. On Android the
    // previous handler is normally debuggerd behind ART's signal chain.
    const struct sigaction previous = g_previous_actions[sig];
    sigaction(sig, &previous, nullptr);
    if (!g_capturing.test_and_set(std::memory_order_relaxed)) {
        const int fd = g_log_path[0] ? open(g_log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600) : -1;
        if (fd >= 0) {
            write_literal(fd, "\n*** STRIKERS NATIVE CRASH ***\nsignal=");
            write_literal(fd, signal_name(sig));
            write_literal(fd, "\n");
            write_number(fd, "signal_number=", sig, 10);
            write_number(fd, "pid=", getpid(), 10);
            write_number(fd, "tid=", syscall(SYS_gettid), 10);
            struct timespec now{};
            if (clock_gettime(CLOCK_MONOTONIC, &now) == 0)
                write_number(fd, "monotonic_ms=", uint64_t(now.tv_sec) * 1000 + now.tv_nsec / 1000000, 10);
            uintptr_t pc = 0, lr = 0, fault = 0;
            if (info) {
                // si_code can be negative (e.g. SI_TKILL); preserve its sign.
                const int code = info->si_code;
                write_number(fd, code < 0 ? "si_code=-" : "si_code=",
                             code < 0 ? uint64_t(-int64_t(code)) : uint64_t(code), 10);
                if (sig != SIGABRT) fault = reinterpret_cast<uintptr_t>(info->si_addr);
            }
            write_number(fd, "fault=", fault, 16);
            const auto* uc = static_cast<const ucontext_t*>(context);
#if defined(__aarch64__)
            if (uc) {
                pc = uc->uc_mcontext.pc;
                lr = uc->uc_mcontext.regs[30];
                write_number(fd, "sp=", uc->uc_mcontext.sp, 16);
                write_number(fd, "fp/x29=", uc->uc_mcontext.regs[29], 16);
                static const char* const labels[] = {"x0=", "x1=", "x2=", "x3=", "x4=", "x5=", "x6=", "x7="};
                for (int i = 0; i < 8; ++i) write_number(fd, labels[i], uc->uc_mcontext.regs[i], 16);
            }
#elif defined(__x86_64__)
            if (uc) pc = uc->uc_mcontext.gregs[REG_RIP];
#else
            (void)uc;
#endif
            write_number(fd, "pc=", pc, 16);
            write_number(fd, "lr=", lr, 16);
            dump_relevant_maps(fd, pc, lr, fault);
            write_literal(fd, "*** END NATIVE CRASH ***\n");
            // Do not block the faulting thread on fsync or attempt an unsafe unwind.
            close(fd);
        }
    }
    errno = saved_errno;
    if (previous.sa_handler == SIG_IGN) return;
    if (previous.sa_handler != SIG_DFL && previous.sa_handler != nullptr) {
        if (previous.sa_flags & SA_SIGINFO) previous.sa_sigaction(sig, info, context);
        else previous.sa_handler(sig);
    } else {
        // A raised signal need not fault again. Queue it for this same thread;
        // delivery follows handler return and retains signal termination status.
        raise(sig);
    }
}

static void install_handlers(const char* path)
{
    if (g_installed) return;
    g_installed = true;
    if (path) {
        strncpy(g_log_path, path, sizeof(g_log_path) - 1);
        g_log_path[sizeof(g_log_path) - 1] = '\0';
    }
    stack_t stack{};
    // Do not replace an alternate stack already installed by Android on this thread.
    if (sigaltstack(nullptr, &stack) == 0 && (stack.ss_flags & SS_DISABLE)) {
        stack.ss_sp = g_altstack;
        stack.ss_size = sizeof(g_altstack);
        stack.ss_flags = 0;
        sigaltstack(&stack, nullptr);
    }
    struct sigaction action{};
    action.sa_sigaction = native_crash_handler;
    sigfillset(&action.sa_mask);
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    const int signals[] = {SIGSEGV, SIGBUS, SIGABRT, SIGILL, SIGFPE};
    for (int sig : signals) sigaction(sig, &action, &g_previous_actions[sig]);
}

}} // namespace strikers::diagnostics
