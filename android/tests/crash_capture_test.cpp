#include "../app/src/main/cpp/crash_capture.hpp"
#include <assert.h>
#include <fstream>
#include <iostream>
#include <string>
#include <sys/resource.h>
#include <sys/mman.h>
#include <sys/wait.h>

using namespace strikers::diagnostics;

static void previous_handler(int sig, siginfo_t* info, void* context) {
    // Verify the original siginfo/ucontext survived, not a synthetic second fault.
    if (sig != SIGABRT || !info || info->si_signo != SIGABRT || !context) _exit(91);
    _exit(42);
}
static void returning_handler(int sig) {
    if (sig != SIGABRT) _exit(92);
}

static void run_case(int kind) {
    char path[] = "/tmp/strikers-fault-XXXXXX";
    const int fd = mkstemp(path);
    assert(fd >= 0);
    close(fd);
    const pid_t child = fork();
    assert(child >= 0);
    if (!child) {
        struct rlimit no_core{0, 0};
        setrlimit(RLIMIT_CORE, &no_core);
        alarm(3); // A diagnostic deadlock is a test failure, not a hung CI job.
        struct sigaction previous{};
        sigemptyset(&previous.sa_mask);
        if (kind == 0) {
            previous.sa_sigaction = previous_handler;
            previous.sa_flags = SA_SIGINFO;
        } else if (kind == 1) {
            previous.sa_handler = returning_handler;
        } else {
            previous.sa_handler = SIG_DFL;
        }
        sigaction(SIGABRT, &previous, nullptr);
        if (kind == 4) sigaction(SIGSEGV, &previous, nullptr);
        install_handlers(path);
        install_handlers(path); // Reinstallation must not chain the handler to itself.
        if (kind == 4) {
            void* page = mmap(nullptr, 4096, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (page == MAP_FAILED) _exit(93);
            const volatile char value = *static_cast<volatile char*>(page);
            (void)value;
        }
        if (kind == 3) abort();
        raise(SIGABRT);
        _exit(kind == 1 ? 43 : 90);
    }
    int status = 0;
    assert(waitpid(child, &status, 0) == child);
    if (kind < 2) assert(WIFEXITED(status) && WEXITSTATUS(status) == (kind == 0 ? 42 : 43));
    else assert(WIFSIGNALED(status) && WTERMSIG(status) == (kind == 4 ? SIGSEGV : SIGABRT));
    std::ifstream input(path);
    const std::string log((std::istreambuf_iterator<char>(input)), {});
    unlink(path);
    assert(log.find("*** STRIKERS NATIVE CRASH ***") != std::string::npos);
    assert(log.find(kind == 4 ? "signal=SIGSEGV" : "signal=SIGABRT") != std::string::npos);
    assert(log.find("tid=") != std::string::npos);
    assert(log.find("pc=0x") != std::string::npos);
    assert(log.find("*** END NATIVE CRASH ***") != std::string::npos);
    assert(log.size() < 16 * 1024);
}

int main() {
    assert(relevant_mapping("1000-2000 r-xp 00000000 00:00 0 /vendor/lib64/driver.so", 0x1500, 0, 0));
    assert(!relevant_mapping("1000-2000 rw-p 00000000 00:00 0 [anon:heap]", 0x2000, 0, 0));
    assert(relevant_mapping("1000-2000 r-xp 00000000 00:00 0 /data/libstrikers.so", 0, 0, 0));
    assert(!relevant_mapping("not a mapping", 0, 0, 0));
    for (int i = 0; i < 5; ++i) run_case(i);
    std::cout << "Signal forwarding, original context, repeat install, abort/raise termination and bounded capture passed\n";
}
