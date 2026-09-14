#ifndef STRIKERS_PORT_EXECINFO_H
#define STRIKERS_PORT_EXECINFO_H

#if defined(__ANDROID__)

// Bionic does not provide the glibc/macOS execinfo backtrace API used by the desktop
// crash reporter. Keep the same crash-signal path on Android, but let Android's native
// tombstone/logcat machinery own stack unwinding instead of inventing an unsafe unwinder
// in the signal handler.
static inline int backtrace(void** buffer, int size)
{
    (void)buffer;
    (void)size;
    return 0;
}

static inline void backtrace_symbols_fd(void* const* buffer, int size, int fd)
{
    (void)buffer;
    (void)size;
    (void)fd;
}

#else

// This compatibility header is earlier on Strikers' include path than the host headers.
// Desktop builds still use the platform's real execinfo implementation.
#include_next <execinfo.h>

#endif

#endif // STRIKERS_PORT_EXECINFO_H
