/*
 * Export the two Android activity-mutex operations Aurora needs while keeping
 * SDL's actual mutex implementation inside libSDL3.so.
 *
 * SDL builds its shared library with hidden visibility.  These Android helpers
 * are intentionally internal, so Aurora cannot link to them directly when SDL3
 * is shared.  This tiny bridge lives *inside* SDL3 and exposes only our two
 * forwarding entry points with default visibility.
 */

#if defined(__ANDROID__)

extern void Android_LockActivityMutex(void);
extern void Android_UnlockActivityMutex(void);

#if defined(__GNUC__) || defined(__clang__)
#define STRIKERS_SDL_EXPORT __attribute__((visibility("default"), used))
#else
#define STRIKERS_SDL_EXPORT
#endif

STRIKERS_SDL_EXPORT void SDL_StrikersLockActivityMutex(void)
{
    Android_LockActivityMutex();
}

STRIKERS_SDL_EXPORT void SDL_StrikersUnlockActivityMutex(void)
{
    Android_UnlockActivityMutex();
}

#endif
