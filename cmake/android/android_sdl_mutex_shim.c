/*
 * Compatibility names used by Aurora's Android SurfaceLock.
 *
 * In a static SDL build Aurora could call SDL's internal Android mutex helpers
 * directly.  With libSDL3.so those symbols are hidden, so forward the old names
 * to the explicit bridge exported by our SDL3 shared target.
 */

#if defined(__ANDROID__)

extern void SDL_StrikersLockActivityMutex(void);
extern void SDL_StrikersUnlockActivityMutex(void);

void Android_LockActivityMutex(void)
{
    SDL_StrikersLockActivityMutex();
}

void Android_UnlockActivityMutex(void)
{
    SDL_StrikersUnlockActivityMutex();
}

#endif
