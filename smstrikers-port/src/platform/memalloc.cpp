// MemoryAllocator over an address range the port owns, so a pointer is identified by comparison.

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>

#include "NL/MemAlloc.h"

#if defined(_WIN32)
#include <windows.h>
#else
#include <sys/mman.h>
#endif

namespace
{

const std::size_t kRegionSize = 768u * 1024u * 1024u;
const std::size_t kHeaderSize = 32;
const std::size_t kAllocatedMagic = (std::size_t)0x5354524b414c4c4full; // STRKALLO
const std::size_t kFreedMagic = (std::size_t)0x5354524b46524545ull;     // STRKFREE

struct BlockHeader
{
    std::size_t size;
    std::size_t offset;
    void* owner;
    std::size_t magic;
};

char* s_region;
char* s_bump;
char* s_end;

bool region_init()
{
    if (s_region != nullptr)
        return true;
#if defined(_WIN32)
    void* p = VirtualAlloc(nullptr, kRegionSize, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
#else
    void* p = mmap(nullptr, kRegionSize, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED)
        p = nullptr;
#endif
    if (p == nullptr)
        return false;
    s_region = (char*)p;
    s_bump = s_region;
    s_end = s_region + kRegionSize;
    return true;
}

inline bool region_owns(const void* p)
{
    return s_region != nullptr && (const char*)p >= s_region && (const char*)p < s_end;
}

struct AllocState
{
    std::size_t live;
    std::size_t pool;
    void* freeList[32];
};

AllocState* state_for(FreeBlockList** slot)
{
    if (*slot == nullptr)
        *slot = (FreeBlockList*)std::calloc(1, sizeof(AllocState));
    return (AllocState*)*slot;
}

inline int size_class(std::size_t n)
{
    int c = 0;
    std::size_t s = 32;
    while (s < n && c < 31)
    {
        s <<= 1;
        c++;
    }
    return c;
}

inline std::size_t class_size(int c) { return (std::size_t)32 << c; }

} // namespace

void MemoryAllocator::Initialize(void* memory, unsigned int size)
{
    (void)memory;
    m_free_block_list = nullptr;
    AllocState* st = state_for(&m_free_block_list);
    st->pool = size;
    st->live = 0;
}

void* MemoryAllocator::Allocate(unsigned long size, unsigned int alignment, bool fromEnd)
{
    (void)fromEnd;
    if (!region_init())
        return nullptr;
    if (alignment < alignof(std::max_align_t))
        alignment = alignof(std::max_align_t);
    if (alignment > kHeaderSize)
        alignment = kHeaderSize;

    AllocState* st = state_for(&m_free_block_list);
    const std::size_t want = kHeaderSize + (size ? (std::size_t)size : 1);
    const int cls = size_class(want);

    char* block;
    if (st->freeList[cls] != nullptr)
    {
        block = (char*)st->freeList[cls];
        st->freeList[cls] = *(void**)block;
    }
    else
    {
        const std::size_t take = class_size(cls);
        if (s_bump + take > s_end)
            return nullptr;
        block = s_bump;
        s_bump += take;
    }

    std::uintptr_t raw = (std::uintptr_t)block + kHeaderSize;
    std::uintptr_t aligned = (raw + alignment - 1) & ~(std::uintptr_t)(alignment - 1);
    BlockHeader* h = (BlockHeader*)(aligned - sizeof(BlockHeader));
    h->size = (std::size_t)size;
    h->offset = (std::size_t)(aligned - (std::uintptr_t)block);
    h->owner = st;
    h->magic = kAllocatedMagic;
    st->live += (std::size_t)size;
    return (void*)aligned;
}

void MemoryAllocator::Free(void* p)
{
    if (p == nullptr)
        return;

    if (!region_owns(p))
    {
        std::free(p);
        return;
    }

    // Animation/effect objects can retain a stale reference after the port has already
    // recycled their backing block. With the full match data resident this is much easier
    // to hit than with the GameCube's paged VM. Never enqueue the same block twice: doing so
    // makes the size-class list point at itself and the next allocation corrupts live data.
    BlockHeader* h = (BlockHeader*)((char*)p - sizeof(BlockHeader));
    if (h->magic != kAllocatedMagic)
        return;

    const std::size_t payloadSize = h->size;
    const std::size_t payloadOffset = h->offset;
    AllocState* st = h->owner ? (AllocState*)h->owner : state_for(&m_free_block_list);
    char* block = (char*)p - payloadOffset;

    h->magic = kFreedMagic;
    st->live -= payloadSize < st->live ? payloadSize : st->live;

    const int cls = size_class(payloadOffset + payloadSize);
    *(void**)block = st->freeList[cls];
    st->freeList[cls] = block;
}

unsigned int MemoryAllocator::TotalFreeMemory()
{
    AllocState* st = state_for(&m_free_block_list);
    return (unsigned int)(st->pool > st->live ? st->pool - st->live : 0);
}

unsigned int MemoryAllocator::LargestFreeBlock()
{
    return TotalFreeMemory();
}

extern "C" void* port_region_reserve(std::size_t size, std::size_t align)
{
    if (!region_init())
        return nullptr;
    if (align < alignof(std::max_align_t))
        align = alignof(std::max_align_t);
    std::uintptr_t aligned = ((std::uintptr_t)s_bump + align - 1) & ~(std::uintptr_t)(align - 1);
    if ((char*)(aligned + size) > s_end)
        return nullptr;
    s_bump = (char*)(aligned + size);
    return (void*)aligned;
}

extern "C" int port_region_owns(const void* p) { return region_owns(p) ? 1 : 0; }

extern "C" void port_region_stats(std::size_t* used, std::size_t* total)
{
    if (used)
        *used = (s_region != nullptr) ? (std::size_t)(s_bump - s_region) : 0;
    if (total)
        *total = (s_region != nullptr) ? (std::size_t)(s_end - s_region) : 0;
}
