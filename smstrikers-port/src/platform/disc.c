// Reading a GameCube disc image, so the game data can be a file somebody already has. dvd.c reads
// an extracted directory and that stays the default: it is what a release archive ships.

#include "port/disc.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(__ANDROID__)
#include <limits.h>
#include <unistd.h>
#endif

#ifdef STRIKERS_ZLIB
#include <zlib.h>
#endif

#if defined(STRIKERS_ZLIB) || defined(PORT_DISC_INFLATE_HOOK)
#define DISC_CAN_INFLATE 1
#else
#define DISC_CAN_INFLATE 0
#endif

// 64-bit seek. A GameCube image is 1.36 GB so a 32-bit `long` would in fact reach every byte of
// one, but only just, and being one bad image away from a silent wrap is not worth the four lines
// saved.
#ifdef _WIN32
#define disc_seek_raw(f, off) _fseeki64((f), (__int64)(off), SEEK_SET)
#else
#define disc_seek_raw(f, off) fseeko((f), (off_t)(off), SEEK_SET)
#endif

// Every seek clears the stream's flags first, and that is not tidiness.
static int disc_seek(FILE* f, unsigned long long off)
{
    clearerr(f);
    return disc_seek_raw(f, off);
}

#define DISC_RAW 0
#define DISC_CISO 1
#define DISC_GCZ 2

// The disc header's own magic, big-endian at 0x1C, on every GameCube disc.
#define GC_MAGIC 0xC2339F3Du
#define WII_MAGIC 0x5D1C9EA3u
#define GCZ_MAGIC 0xB10BC001u

#define CISO_HEADER 0x8000
#define CISO_MAP (CISO_HEADER - 8)

struct PortDisc
{
    FILE* f;
    int kind;
    const char* name;

    unsigned blockSize;

    // CISO: logical block -> stored block, or -1 for a hole.
    int32_t* map;
    unsigned mapCount;

    // GCZ: one offset per block, top bit meaning "stored uncompressed".
    uint64_t* off;
    unsigned blockCount;
    uint64_t dataStart;
    uint64_t compressedSize;
    unsigned char* scratch;   // one compressed block, read before inflating

    // Decoded-block cache; see the note above cache_get.
    unsigned char** cache;
    unsigned* cacheBlock;     // which block each slot holds, ~0u for empty
    unsigned* cacheLen;       // and how many bytes of it are real
    unsigned* cacheAge;
    unsigned cacheSlots;
    unsigned cacheClock;
};

static uint32_t rd_be32(const unsigned char* p)
{
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

static uint32_t rd_le32(const unsigned char* p)
{
    return ((uint32_t)p[3] << 24) | ((uint32_t)p[2] << 16) | ((uint32_t)p[1] << 8) | p[0];
}

static uint64_t rd_le64(const unsigned char* p)
{
    return (uint64_t)rd_le32(p) | ((uint64_t)rd_le32(p + 4) << 32);
}

int port_disc_looks_like_image(const char* path)
{
    static const char* const kExt[] = { ".iso", ".gcm", ".ciso", ".gcz",
                                        ".rvz", ".wia", ".nkit" };
    size_t n = strlen(path);
    size_t i;
    for (i = 0; i < sizeof kExt / sizeof kExt[0]; i++)
    {
        size_t e = strlen(kExt[i]);
        if (n > e && strcmpi(path + n - e, kExt[i]) == 0)
            return 1;
    }
    return 0;
}

const char* port_disc_format(const PortDisc* disc)
{
    return disc != NULL && disc->name != NULL ? disc->name : "?";
}

void port_disc_close(PortDisc* disc)
{
    unsigned i;
    if (disc == NULL)
        return;
    if (disc->f)
        fclose(disc->f);
    free(disc->map);
    free(disc->off);
    free(disc->scratch);
    for (i = 0; i < disc->cacheSlots; i++)
        free(disc->cache[i]);
    free(disc->cache);
    free(disc->cacheBlock);
    free(disc->cacheLen);
    free(disc->cacheAge);
    free(disc);
}

static PortDisc* fail(PortDisc* d, char* err, size_t errsize, const char* text)
{
    snprintf(err, errsize, "%s", text);
    port_disc_close(d);
    return NULL;
}

static int gcz_load(PortDisc* d, unsigned block, unsigned char* out, unsigned* outLen);

static const unsigned char* cache_get(PortDisc* d, unsigned block, unsigned* len)
{
    unsigned i, victim = 0;
    unsigned oldest = ~0u;

    for (i = 0; i < d->cacheSlots; i++)
        if (d->cacheBlock[i] == block)
        {
            d->cacheAge[i] = ++d->cacheClock;
            *len = d->cacheLen[i];
            return d->cache[i];
        }

    for (i = 0; i < d->cacheSlots; i++)
        if (d->cacheAge[i] < oldest)
        {
            oldest = d->cacheAge[i];
            victim = i;
        }

    if (d->cache[victim] == NULL)
    {
        d->cache[victim] = (unsigned char*)malloc(d->blockSize);
        if (d->cache[victim] == NULL)
            return NULL;
    }
    d->cacheBlock[victim] = ~0u;
    if (gcz_load(d, block, d->cache[victim], len) != 0)
        return NULL;
    d->cacheBlock[victim] = block;
    d->cacheLen[victim] = *len;
    d->cacheAge[victim] = ++d->cacheClock;
    return d->cache[victim];
}

static int cache_init(PortDisc* d)
{
    // Sixteen slots, not a setting: the 1011 reads between launch and kickoff cost 29.8 ms at one
    // slot and at sixty-four alike, because the reads run forward.
    d->cacheSlots = 16u;
    d->cache = (unsigned char**)calloc(d->cacheSlots, sizeof *d->cache);
    d->cacheBlock = (unsigned*)malloc(d->cacheSlots * sizeof *d->cacheBlock);
    d->cacheLen = (unsigned*)calloc(d->cacheSlots, sizeof *d->cacheLen);
    d->cacheAge = (unsigned*)calloc(d->cacheSlots, sizeof *d->cacheAge);
    if (d->cache == NULL || d->cacheBlock == NULL || d->cacheLen == NULL
        || d->cacheAge == NULL)
        return -1;
    memset(d->cacheBlock, 0xFF, d->cacheSlots * sizeof *d->cacheBlock);
    return 0;
}

static int gcz_load(PortDisc* d, unsigned block, unsigned char* out, unsigned* outLen)
{
    const uint64_t kStored = 1ull << 63;
    uint64_t raw, next, clen;
    int stored;

    *outLen = 0;
    if (block >= d->blockCount)
        return -1;

    stored = (d->off[block] & kStored) != 0;
    raw = d->off[block] & ~kStored;
    next = block + 1 < d->blockCount ? (d->off[block + 1] & ~kStored)
                                     : d->compressedSize;
    if (next < raw)
        return -1;
    clen = next - raw;

    if (disc_seek(d->f, d->dataStart + raw) != 0)
        return -1;

    if (stored)
    {
        // Bigger compressed than raw, so the writer kept it as it was.
        size_t want = clen < d->blockSize ? (size_t)clen : d->blockSize;
        size_t got = fread(out, 1, want, d->f);
        *outLen = (unsigned)got;
        return got == want ? 0 : -1;
    }

#if DISC_CAN_INFLATE
    if (clen > d->blockSize + (d->blockSize >> 3) + 256)
        return -1;   // a compressed block larger than zlib's worst case
    if (fread(d->scratch, 1, (size_t)clen, d->f) != (size_t)clen)
        return -1;
    {
#ifdef STRIKERS_ZLIB
        uLongf dst = d->blockSize;
        if (uncompress(out, &dst, d->scratch, (uLong)clen) != Z_OK)
            return -1;
#else
        unsigned long dst = d->blockSize;
        if (port_disc_inflate(out, &dst, d->scratch, (unsigned long)clen) != 0)
            return -1;
#endif
        *outLen = (unsigned)dst;
    }
    return 0;
#else
    (void)clen;
    return -1;
#endif
}

static PortDisc* gcz_open(PortDisc* d, const unsigned char* head, char* err,
                          size_t errsize)
{
    unsigned char* table;
    size_t tableBytes;

#if !DISC_CAN_INFLATE
    return fail(d, err, errsize,
                "That is a .gcz disc image, and this build of the game was "
                "compiled without zlib,\nso it cannot decompress one.\n\n"
                "Use a plain .iso, a .gcm or a .ciso instead.");
#else
    d->kind = DISC_GCZ;
    d->name = "GCZ";
    d->compressedSize = rd_le64(head + 8);
    d->blockSize = rd_le32(head + 24);
    d->blockCount = rd_le32(head + 28);

    if (d->blockSize < 0x400 || d->blockSize > (16u << 20) || d->blockCount == 0
        || d->blockCount > (64u << 20))
        return fail(d, err, errsize,
                    "That .gcz file's header does not make sense (block size or "
                    "block count out of\nrange). It is probably damaged; "
                    "re-create it, or use a plain .iso.");

    tableBytes = (size_t)d->blockCount * 8;
    table = (unsigned char*)malloc(tableBytes);
    d->off = (uint64_t*)malloc((size_t)d->blockCount * sizeof *d->off);
    d->scratch = (unsigned char*)malloc(d->blockSize + (d->blockSize >> 3) + 256);
    if (table == NULL || d->off == NULL || d->scratch == NULL)
    {
        free(table);
        return fail(d, err, errsize, "Out of memory reading the .gcz block table.");
    }
    if (disc_seek(d->f, 32) != 0 || fread(table, 1, tableBytes, d->f) != tableBytes)
    {
        free(table);
        return fail(d, err, errsize,
                    "That .gcz file ends inside its block table, it is "
                    "truncated.");
    }
    {
        unsigned i;
        for (i = 0; i < d->blockCount; i++)
            d->off[i] = rd_le64(table + (size_t)i * 8);
    }
    free(table);

    // Header, then the offsets, then one adler32 per block.
    d->dataStart = 32 + (uint64_t)d->blockCount * 12;

    if (cache_init(d) != 0)
        return fail(d, err, errsize, "Out of memory sizing the .gcz block cache.");
    return d;
#endif
}

static PortDisc* ciso_open(PortDisc* d, const unsigned char* head, char* err,
                           size_t errsize)
{
    unsigned char map[CISO_MAP];
    unsigned i;
    int32_t stored = 0;

    d->kind = DISC_CISO;
    d->name = "CISO";
    d->blockSize = rd_le32(head + 4);
    if (d->blockSize < 0x800 || d->blockSize > (64u << 20))
        return fail(d, err, errsize,
                    "That .ciso file's block size is not a sane one, so this is "
                    "not a GameCube CISO.\n\nThe PSP uses a different format "
                    "with the same four-letter magic. Convert the image\nto a "
                    "plain .iso.");

    if (disc_seek(d->f, 8) != 0 || fread(map, 1, sizeof map, d->f) != sizeof map)
        return fail(d, err, errsize, "That .ciso file ends inside its header.");

    d->map = (int32_t*)malloc(sizeof(int32_t) * CISO_MAP);
    if (d->map == NULL)
        return fail(d, err, errsize, "Out of memory reading the .ciso map.");
    d->mapCount = CISO_MAP;
    for (i = 0; i < CISO_MAP; i++)
    {
        // One byte per block, 1 for stored and 0 for a hole, and nothing else.
        if (map[i] > 1)
            return fail(d, err, errsize,
                        "That .ciso file's block map is not one byte per block, "
                        "so it is not a GameCube\nCISO, the PSP's format uses "
                        "the same magic. Convert the image to a plain .iso.");
        d->map[i] = map[i] ? stored++ : -1;
    }
    if (d->map[0] < 0)
        return fail(d, err, errsize,
                    "That .ciso file does not store its first block, which "
                    "holds the disc header.\nIt is not a disc image this can "
                    "read.");
    return d;
}


long port_disc_read(PortDisc* d, void* dst, size_t len, unsigned long long offset)
{
    unsigned char* out = (unsigned char*)dst;
    size_t done = 0;

    if (d == NULL || dst == NULL)
        return -1;

    if (d->kind == DISC_RAW)
    {
        if (disc_seek(d->f, offset) != 0)
            return -1;
        return (long)fread(out, 1, len, d->f);
    }

    while (done < len)
    {
        unsigned long long abs = offset + done;
        unsigned block = (unsigned)(abs / d->blockSize);
        unsigned within = (unsigned)(abs % d->blockSize);
        size_t want = d->blockSize - within;
        if (want > len - done)
            want = len - done;

        if (d->kind == DISC_CISO)
        {
            if (block >= d->mapCount)
                break;
            if (d->map[block] < 0)
            {
                // A hole: padding the writer dropped, which was zeroes on the disc.
                memset(out + done, 0, want);
            }
            else
            {
                unsigned long long at = (unsigned long long)CISO_HEADER
                                        + (unsigned long long)d->map[block] * d->blockSize
                                        + within;
                size_t got;
                if (disc_seek(d->f, at) != 0)
                    return done > 0 ? (long)done : -1;
                got = fread(out + done, 1, want, d->f);
                done += got;
                if (got != want)
                    break;
                continue;
            }
        }
        else
        {
            unsigned have = 0;
            const unsigned char* p = cache_get(d, block, &have);
            if (p == NULL)
                return done > 0 ? (long)done : -1;
            if (within >= have)
                break;
            if (want > have - within)
                want = have - within;
            memcpy(out + done, p + within, want);
        }
        done += want;
    }
    return (long)done;
}

// NKit is *not* a refusal, and working out why cost a wrong one first.
static void note_nkit(const unsigned char* head, size_t got)
{
    if (got >= 0x204 && memcmp(head + 0x200, "NKIT", 4) == 0)
        fprintf(stderr,
                "[port] disc: NKit-processed image, the disc's padding was "
                "removed and rebuilt.\n"
                "[port] disc: the filesystem is complete, so it is read as an "
                "ordinary image.\n");
}

#if defined(__ANDROID__)
// Android's Storage Access Framework gives the launcher a real, seekable file descriptor, but
// opening /proc/self/fd/N again with fopen() is not reliable under Android's app sandbox. The Java
// bootstrap deliberately keeps the original ParcelFileDescriptor alive for the lifetime of the
// :game process, so duplicate that descriptor and wrap the duplicate in stdio instead of reopening
// procfs. fclose() then owns only the duplicate; Java still owns the original descriptor.
static FILE* open_android_proc_fd(const char* path)
{
    static const char prefix[] = "/proc/self/fd/";
    char* end = NULL;
    long value;
    int duplicated;
    FILE* f;

    if (path == NULL || strncmp(path, prefix, sizeof prefix - 1) != 0)
        return NULL;

    value = strtol(path + sizeof prefix - 1, &end, 10);
    if (end == path + sizeof prefix - 1 || *end != '\0' || value < 0 || value > INT_MAX)
        return NULL;

    duplicated = dup((int)value);
    if (duplicated < 0)
        return NULL;

    f = fdopen(duplicated, "rb");
    if (f == NULL)
    {
        close(duplicated);
        return NULL;
    }

    // SAF providers may leave the descriptor positioned somewhere other than zero. Every later
    // read seeks explicitly too, but normalising here makes the header probe deterministic.
    if (disc_seek(f, 0) != 0)
    {
        fclose(f);
        return NULL;
    }
    return f;
}
#endif

static FILE* open_disc_stream(const char* path)
{
#if defined(__ANDROID__)
    FILE* android_fd = NULL;
    if (path != NULL && strncmp(path, "/proc/self/fd/", 14) == 0)
    {
        android_fd = open_android_proc_fd(path);
        if (android_fd != NULL)
        {
            fprintf(stderr, "[android] disc: using duplicated SAF fd for %s\n", path);
            return android_fd;
        }

        // Do not fall through to fopen("/proc/self/fd/N") on Android. The whole point of this
        // branch is that SAF descriptors are valid while procfs re-open is denied by the sandbox.
        return NULL;
    }
#endif
    return fopen(path, "rb");
}

PortDisc* port_disc_open(const char* path, char* err, size_t errsize)
{
    unsigned char head[0x220];
    size_t got;
    PortDisc* d;

    err[0] = '\0';
    d = (PortDisc*)calloc(1, sizeof *d);
    if (d == NULL)
    {
        snprintf(err, errsize, "Out of memory.");
        return NULL;
    }

    d->f = open_disc_stream(path);
    if (d->f == NULL)
    {
        snprintf(err, errsize,
                 "That file could not be opened:\n\n  %s\n\nCheck the path and "
                 "that the file is readable.", path);
        free(d);
        return NULL;
    }

    got = fread(head, 1, sizeof head, d->f);
    if (got < 0x20)
        return fail(d, err, errsize,
                    "That file is too small to be a disc image.");

    // The formats that are recognisable and cannot be read.
    if (memcmp(head, "RVZ\x01", 4) == 0 || memcmp(head, "WIA\x01", 4) == 0)
    {
        char msg[1024];
        snprintf(msg, sizeof msg,
                 "That is a .%s disc image, which this port cannot read.\n\n"
                 "RVZ and WIA store the disc re-encoded, zstd, LZMA and bzip2 "
                 "blocks, with the\nlayout rebuilt around the partitions; "
                 "which is a much larger reader than the\nthree formats here "
                 "put together.\n\nConvert it to a plain .iso first. In "
                 "Dolphin: right-click the game, Convert File\nFormat..., and "
                 "set Format to ISO.",
                 head[0] == 'R' ? "rvz" : "wia");
        return fail(d, err, errsize, msg);
    }

    if (rd_be32(head + 0x1C) == GC_MAGIC)
    {
        d->kind = DISC_RAW;
        d->name = "raw";
        note_nkit(head, got);
        return d;
    }

    if (rd_be32(head + 0x18) == WII_MAGIC)
        return fail(d, err, errsize,
                    "That is a Wii disc image. Super Mario Strikers is a "
                    "GameCube game, the disc\nthis needs has a code beginning "
                    "G4Q.");

    if (memcmp(head, "CISO", 4) == 0)
        d = ciso_open(d, head, err, errsize);
    else if (rd_le32(head) == GCZ_MAGIC)
        d = gcz_open(d, head, err, errsize);
    else
        return fail(d, err, errsize,
                    "That file is not a disc image this port recognises.\n\n"
                    "It reads .iso and .gcm (a plain disc image, NKit-processed "
                    "or not), .ciso and\n.gcz. It names .rvz and .wia and says "
                    "to convert them; anything else has no\nheader it knows.");

    if (d == NULL)
        return NULL;

    // A container this build can read, holding something that is not this console's disc.
    if (port_disc_read(d, head, sizeof head, 0) < 0x204)
        return fail(d, err, errsize,
                    "That image could not be read past its own header, it is "
                    "truncated or damaged.");
    if (rd_be32(head + 0x1C) != GC_MAGIC)
        return fail(d, err, errsize,
                    "That image decompresses to something that is not a "
                    "GameCube disc: the disc\nmagic is missing from its header. "
                    "It is either damaged or an image of\nsomething else.");
    note_nkit(head, sizeof head);
    return d;
}

static unsigned be32u(const unsigned char* p)
{
    return ((unsigned)p[0] << 24) | ((unsigned)p[1] << 16) | ((unsigned)p[2] << 8) | p[3];
}

int port_disc_walk(PortDisc* disc, PortDiscVisit visit, void* user, char* err,
                   size_t errsize)
{
    unsigned char hdr[0x430];
    unsigned fstOff, fstSize, count, i;
    unsigned char* fst;
    const char* names;

    err[0] = '\0';
    if (disc == NULL || visit == NULL)
    {
        snprintf(err, errsize, "Internal disc reader error.");
        return -1;
    }

    if (port_disc_read(disc, hdr, sizeof hdr, 0) != (long)sizeof hdr)
    {
        snprintf(err, errsize, "That image ends before the GameCube disc header does.");
        return -1;
    }

    fstOff = be32u(hdr + 0x424);
    fstSize = be32u(hdr + 0x428);
    if (fstSize < 12 || fstSize > (64u << 20))
    {
        snprintf(err, errsize, "The image's filesystem table size is invalid.");
        return -1;
    }

    fst = (unsigned char*)malloc(fstSize);
    if (fst == NULL)
    {
        snprintf(err, errsize, "Out of memory reading the disc filesystem table.");
        return -1;
    }
    if (port_disc_read(disc, fst, fstSize, fstOff) != (long)fstSize)
    {
        free(fst);
        snprintf(err, errsize, "The image ends inside its filesystem table.");
        return -1;
    }

    if ((be32u(fst) & 0xFF000000u) == 0)
    {
        free(fst);
        snprintf(err, errsize, "The root filesystem entry is not a directory.");
        return -1;
    }
    count = be32u(fst + 8);
    if (count == 0 || count > fstSize / 12)
    {
        free(fst);
        snprintf(err, errsize, "The image's filesystem entry count is invalid.");
        return -1;
    }
    names = (const char*)(fst + (size_t)count * 12);

    // Reconstruct paths from directory end indices. The FST stores no parent index for files.
    {
        struct DirLevel
        {
            unsigned end;
            size_t pathLen;
        } stack[128];
        unsigned depth = 0;
        char path[2048];
        size_t pathLen = 0;
        path[0] = '\0';
        stack[depth].end = count;
        stack[depth].pathLen = 0;
        depth++;

        for (i = 1; i < count; i++)
        {
            const unsigned char* e = fst + (size_t)i * 12;
            unsigned word = be32u(e);
            unsigned nameOff = word & 0x00FFFFFFu;
            int isDir = (word >> 24) != 0;
            unsigned a = be32u(e + 4);
            unsigned b = be32u(e + 8);
            const char* name;
            size_t remain, nameLen;

            while (depth > 0 && i >= stack[depth - 1].end)
            {
                pathLen = stack[depth - 1].pathLen;
                path[pathLen] = '\0';
                depth--;
            }

            if ((size_t)count * 12 + nameOff >= fstSize)
            {
                free(fst);
                snprintf(err, errsize, "A filesystem name points outside the name table.");
                return -1;
            }
            name = names + nameOff;
            remain = fstSize - ((size_t)count * 12 + nameOff);
            nameLen = strnlen(name, remain);
            if (nameLen == remain)
            {
                free(fst);
                snprintf(err, errsize, "A filesystem name is not terminated.");
                return -1;
            }

            if (pathLen && pathLen + 1 < sizeof path)
                path[pathLen++] = '/';
            if (pathLen + nameLen >= sizeof path)
            {
                free(fst);
                snprintf(err, errsize, "A filesystem path is too long.");
                return -1;
            }
            memcpy(path + pathLen, name, nameLen);
            pathLen += nameLen;
            path[pathLen] = '\0';

            if (visit(user, path, isDir ? 0u : a, isDir ? 0u : b, isDir) != 0)
            {
                free(fst);
                return 0;
            }

            if (isDir)
            {
                if (b <= i || b > count || depth >= sizeof stack / sizeof stack[0])
                {
                    free(fst);
                    snprintf(err, errsize, "A filesystem directory range is invalid.");
                    return -1;
                }
                stack[depth].end = b;
                stack[depth].pathLen = pathLen - nameLen - (pathLen > nameLen ? 1u : 0u);
                depth++;
            }
            else
            {
                pathLen -= nameLen;
                if (pathLen && path[pathLen - 1] == '/')
                    pathLen--;
                path[pathLen] = '\0';
            }
        }
    }

    free(fst);
    return 0;
}
