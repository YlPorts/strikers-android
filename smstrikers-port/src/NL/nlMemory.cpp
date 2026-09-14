#include "NL/nlMemory.h"
#include <stdlib.h>
#if defined(__ANDROID__)
#include <cstdlib>
#endif
#include "NL/MemAlloc.h"

#include <types.h>

#include "dolphin/os.h"
#include "dolphin/pad.h"
#include "dolphin/dvd.h"
#include "dolphin/vm/VM.h"
#include "dolphin/vi/vifuncs.h"

static u8 s_MemoryInitialized = 0;

MemoryAllocator StandardAllocator;
MemoryAllocator VirtualAllocator;

/**
 * Offset/Address/Size: 0x0 | 0x801D1EE4 | size: 0x40
 */
void nlFree(void* ptr)
{
    if (((uintptr_t)ptr & 0x80000000) == 0)
    {
        VirtualAllocator.Free(ptr);
    }
    else
    {
        StandardAllocator.Free(ptr);
    }
}

/**
 * Offset/Address/Size: 0x40 | 0x801D1F24 | size: 0x64
 */
void* nlMalloc(size_t size, unsigned int alignment, bool atEnd)
{
    if (s_MemoryInitialized == 0)
    {
        nlInitMemory();
    }
    return StandardAllocator.Allocate(size, alignment, atEnd);
}

/**
 * Offset/Address/Size: 0xA4 | 0x801D1F88 | size: 0x4C
 */
void* nlMalloc(size_t size)
{
    if (s_MemoryInitialized == 0)
    {
        nlInitMemory();
    }
    return StandardAllocator.Allocate(size, 8, false);
}

/**
 * Offset/Address/Size: 0xF0 | 0x801D1FD4 | size: 0x4C
 */
void* operator new(size_t size)
{
    // PORT: the host heap, not nlMalloc, see the note in tools/vendor.py.
#if defined(__ANDROID__)
    void* p = std::malloc(size ? size : 1);
#else
    void* p = malloc(size ? size : 1);
#endif
    if (p == NULL)
    {
        OSReport("nlMemory: out of memory allocating %lu bytes\n", size);
        abort();
    }
    return p;
}

/**
 * Offset/Address/Size: 0x13C | 0x801D2020 | size: 0x40
 */
void operator delete[](void* ptr)
{
    nlFree(ptr);
}

/**
 * Offset/Address/Size: 0x17C | 0x801D2060 | size: 0x40
 */
void operator delete(void* ptr)
{
    nlFree(ptr);
}

/**
 * Offset/Address/Size: 0x1BC | 0x801D20A0 | size: 0x24
 */
unsigned int nlVirtualTotalFree()
{
    return VirtualAllocator.TotalFreeMemory();
}

/**
 * Offset/Address/Size: 0x1E0 | 0x801D20C4 | size: 0x24
 */
unsigned int nlVirtualLargestBlock()
{
    return VirtualAllocator.LargestFreeBlock();
}

/**
 * Offset/Address/Size: 0x204 | 0x801D20E8 | size: 0x28
 */
void nlVirtualFree(void* ptr)
{
    VirtualAllocator.Free(ptr);
}

/**
 * Offset/Address/Size: 0x22C | 0x801D2110 | size: 0x30
 */
void* nlVirtualAlloc(size_t size, bool bZero)
{
    return VirtualAllocator.Allocate(size, 0x20, bZero);
}

/**
 * Offset/Address/Size: 0x25C | 0x801D2140 | size: 0x1B8
 */
extern "C" void* VMPortGetWindow(size_t*);  // src/platform/vm.c

void nlInitMemory()
{
    if (s_MemoryInitialized == 0)
    {
        s_MemoryInitialized = 1;
        VMInit(0x100000, 0x700000, 0x900000);
        VMAlloc(0x7E000000, 0x900000);
        DVDInit();
        VIInit();
        PADInit();

        void* arenaLo = OSGetArenaLo();
        void* arenaHi = OSGetArenaHi();
        arenaLo = OSInitAlloc(arenaLo, arenaHi, 1);
        OSSetArenaLo(arenaLo);

        OSHeapHandle handle = OSCreateHeap(arenaLo, arenaHi);
        OSSetCurrentHeap(handle);
        size_t windowSize = 0;
        void* window = VMPortGetWindow(&windowSize);
        VirtualAllocator.Initialize(window, windowSize);
        StandardAllocator.Initialize(arenaLo, (uintptr_t)arenaHi - (uintptr_t)arenaLo);
    }
}
