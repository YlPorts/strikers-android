#pragma once

// Android only gained backtrace/backtrace_symbols/backtrace_symbols_fd in API 33.
// The port targets API 26, and morphwatch already performs its own frame-pointer
// walk; symbol emission is diagnostic-only. Forward to the real platform header,
// then provide a harmless fd sink where Bionic does not expose the API yet.
#include_next <execinfo.h>

#if defined(__ANDROID__) && defined(__ANDROID_API__) && __ANDROID_API__ < 33
static inline void backtrace_symbols_fd(void* const* buffer, int size, int fd)
{
    (void)buffer;
    (void)size;
    (void)fd;
}
#endif
