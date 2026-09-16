#define NLFILEGC_IMPLEMENTATION 1
#include "NL/nlFileGC.h"
#include "NL/nlFile.h"

// The GameCube NIS loader queues asynchronous reads into a scratch area that is
// reused across cinematics. On Android a transition can reset/reuse that area
// before a late callback finishes, which shows up as cut cinematics or a crash
// at character intro/post-game animations. Keep the game's map residency model
// unchanged; only make NIS asset reads deterministic on Android.
void nlAsyncLoadFileToVirtualMemoryAndroidSafe(
    nlFile* file,
    int size,
    void* buffer,
    ReadAsyncCallback callback,
    uintptr_t param)
{
    if (file == nullptr || buffer == nullptr || size <= 0)
    {
        return;
    }

    // Read directly into the final aligned NIS destination and complete the
    // callback before the cinematic state can reset/recycle the buffer.
    nlRead(file, buffer, (unsigned int)size);

    if (callback != nullptr)
    {
        // Match nlFileGC.cpp's async callback convention: the callback receives
        // an end pointer and subtracts size to recover the beginning.
        callback(file, (char*)buffer + size, (unsigned int)size, param);
    }
}
