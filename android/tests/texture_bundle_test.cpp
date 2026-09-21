// Exercises the production glxTexture.cpp and GCTextureSize, including the
// inventory and pixel/palette copies. Only filesystem, GPU and arena boundaries
// are fixtures. No ROM assets or Android device are required.
#include <assert.h>
#include <sys/mman.h>
#include <unistd.h>
#include "NL/glx/glxTexture.h"
#include "NL/gl/glMemory.h"
#include "NL/nlFile.h"
#include "Game/GL/GLInventory.h"
#include "Game/Sys/debug.h"

static void* resources[1024];
static size_t resourceCount;
static int invalidations;
static const u32 hashA = 0x00e8e5db;
static const u32 hashB = 0x1234abcd;

void* nlMalloc(size_t n, unsigned int alignment, bool) {
    void* p = NULL;
    assert(posix_memalign(&p, alignment < sizeof(void*) ? sizeof(void*) : alignment, n ? n : 1) == 0);
    return p;
}
void* nlMalloc(size_t n) { return nlMalloc(n, 8, false); }
extern "C" void nlFree(void* p) { free(p); }
void* operator new(size_t n) { void* p = malloc(n ? n : 1); assert(p); return p; }
void operator delete(void* p) { free(p); }
void* operator new[](size_t n) { return ::operator new(n); }
void operator delete[](void* p) { free(p); }
void* glResourceAlloc(unsigned long n, eGLMemory) {
    assert(resourceCount < 1024);
    return resources[resourceCount++] = nlMalloc(n, 32, false);
}
void glx_FreeMemory0() {}
void glx_FreeMemory1(const char*) {}
extern "C" int port_region_owns(const void*) { return 0; }
GLInventory::GLInventory() {}
GLInventory::~GLInventory() {}
GLInventory glInventory;
GLTextureAnim* GLInventory::GetTextureAnim(uintptr_t) { return NULL; }
GLAnimTex* GLTextureAnim::GetTexture(int) { assert(false); return NULL; }
bool glTextureLoad(uintptr_t handle) { return glplatTextureLoad(handle); }
int tDebugPrintManager::Print(eDEBUG_CHANNEL, const char*, ...) { return 0; }
extern "C" void OSReport(const char*, ...) {}
extern "C" void DCStoreRange(void*, u32) {}
extern "C" void GXInvalidateTexAll() { ++invalidations; }
extern "C" void GXInitTexObj(GXTexObj*, void*, u16, u16, GXTexFmt, GXTexWrapMode, GXTexWrapMode, u8) {}
extern "C" void GXInitTexObjCI(GXTexObj*, void*, u16, u16, GXCITexFmt, GXTexWrapMode, GXTexWrapMode, u8, u32) {}
extern "C" void GXInitTexObjLOD(GXTexObj*, GXTexFilter, GXTexFilter, f32, f32, f32, GXBool, GXBool, GXAnisotropy) {}
extern "C" void GXInitTlutObj(GXTlutObj*, void*, GXTlutFmt, u16) {}

static void be32(u8* p, u32 v) {
    p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = v;
}
static void be16(u8* p, u16 v) { p[0] = v >> 8; p[1] = v; }

struct Bundle {
    u8* bytes;
    size_t size;
    size_t entrySize;
    size_t pixelSize;
    size_t firstOffset;
    unsigned width;
    unsigned paletteEntries;
    explicit Bundle(unsigned side = 8, unsigned palette = 0)
        : pixelSize(side * side * (palette ? 1 : 4)), width(side), paletteEntries(palette) {
        entrySize = 0x20 + pixelSize + 2 * palette;
        firstOffset = entrySize + 0x20;
        size = 0x40 + firstOffset + entrySize;
        bytes = (u8*)calloc(1, size);
        assert(bytes);
        be32(bytes + 4, 2);
        // Deliberately not sorted by offset; both fields need BE conversion.
        be32(bytes + 0x20, hashA); be32(bytes + 0x24, firstOffset); be32(bytes + 0x28, entrySize);
        be32(bytes + 0x30, hashB); be32(bytes + 0x34, 0); be32(bytes + 0x38, entrySize);
        for (int i = 0; i < 2; ++i) {
            u8* p = texture(i);
            be32(p, 1); be32(p + 4, palette ? GXTex_CI8 : GXTex_RGBA8);
            memset(p + 8, 8, 4); be16(p + 14, side); be16(p + 16, side);
            be32(p + 20, palette);
            memset(p + 0x20, i ? 0x35 : 0xa7, pixelSize);
            for (unsigned j = 0; j < palette; ++j) be16(p + 0x20 + pixelSize + j * 2, 0x8000 + j);
        }
    }
    u8* texture(int index) { return bytes + 0x40 + (index ? 0 : firstOffset); }
    ~Bundle() { free(bytes); }
};

// nlRead is intentionally void in the game API. The fixture asserts every read
// stays inside the file, and ASan verifies the destination allocation too.
static Bundle* currentFile;
static unsigned openFiles;
class MemoryFile : public nlFile {
public:
    size_t position = 0;
    MemoryFile() { ++openFiles; }
    ~MemoryFile() { --openFiles; }
    u32 FileSize(unsigned int* size) override { *size = currentFile->size; return *size; }
    void Read(void* target, unsigned int size) override {
        assert(position <= currentFile->size && size <= currentFile->size - position);
        memcpy(target, currentFile->bytes + position, size);
        position += size;
    }
};
nlFile::nlFile() {}
nlFile::~nlFile() {}
nlFile* nlOpen(const char*) { return currentFile ? new MemoryFile() : NULL; }
void nlClose(nlFile* file) { delete file; }
unsigned int nlFileSize(nlFile* file, unsigned int* size) { return file->FileSize(size); }
void nlRead(nlFile* file, void* buffer, unsigned int size) { file->Read(buffer, size); }
void nlSeek(nlFile* file, unsigned int offset, unsigned long origin) {
    assert(origin == 0); static_cast<MemoryFile*>(file)->position = offset;
}

static void clearTextures() {
    glx_SetLoadCallback(NULL);
    glx_BackupTexMarkerLevel(0);
    while (resourceCount) free(resources[--resourceCount]);
    glx_AdvanceTexMarkerLevel();
}
static void checkTexture(u32 handle, Bundle& bundle, int entry) {
    PlatTexture* tex = glx_GetTex(handle, false, false);
    assert(tex && tex->m_Width == bundle.width && tex->m_Height == bundle.width && tex->m_Levels == 1);
    assert(tex->m_nPaletteEntries == bundle.paletteEntries);
    assert(memcmp(tex->m_SwizzledData, bundle.texture(entry) + 0x20, bundle.pixelSize) == 0);
    if (bundle.paletteEntries)
        assert(memcmp(tex->m_PaletteData, bundle.texture(entry) + 0x20 + bundle.pixelSize, bundle.paletteEntries * 2) == 0);
}
static void reject(Bundle& bundle, size_t size) {
    const int before = invalidations;
    assert(!glplatEndLoadTextureBundle(bundle.bytes, size));
    assert(resourceCount == 0 && invalidations == before);
    assert(!glx_GetTex(hashA, false, false));
    assert(!glx_GetTex(hashB, false, false));
}
static unsigned long callbackHashes[2];
static unsigned callbackCount;
static unsigned long remap(unsigned long hash) {
    assert(callbackCount < 2); callbackHashes[callbackCount++] = hash;
    return hash == hashA ? hashB : (unsigned long)-1;
}

int main(int argc, char**) {
    glxInitTex();
    glx_AdvanceTexMarkerLevel();
    // This same production call segfaults with the 1.5.8 glxTexture.cpp.
    {
        Bundle bundle;
        assert(glplatEndLoadTextureBundle(bundle.bytes, bundle.size));
        checkTexture(hashA, bundle, 0); checkTexture(hashB, bundle, 1);
        if (argc > 1) return 0;
        // Reusing the buffer must not double-swap or mutate the source.
        u8* original = (u8*)malloc(bundle.size); memcpy(original, bundle.bytes, bundle.size);
        for (int round = 0; round < 100; ++round) {
            clearTextures();
            assert(glplatEndLoadTextureBundle(bundle.bytes, bundle.size));
            checkTexture(hashA, bundle, 0); checkTexture(hashB, bundle, 1);
            assert(memcmp(original, bundle.bytes, bundle.size) == 0);
        }
        free(original);
    }
    clearTextures();
    {
        // CMPR 128x128 with five mip levels: 8192+2048+512+128+32 bytes.
        // Bundle entries may include padding after the actual texture payload.
        Bundle bundle(64);
        for (int i = 0; i < 2; ++i) {
            be32(bundle.texture(i), 5); be32(bundle.texture(i) + 4, GXTex_CMPR);
            be16(bundle.texture(i) + 14, 128); be16(bundle.texture(i) + 16, 128);
        }
        assert(glplatEndLoadTextureBundle(bundle.bytes, bundle.size));
        PlatTexture* tex = glx_GetTex(hashA, false, false);
        assert(tex && tex->m_Format == GXTex_CMPR && tex->m_Width == 128 && tex->m_Levels == 5);
        assert(memcmp(tex->m_SwizzledData, bundle.texture(0) + 0x20, 10912) == 0);
    }
    clearTextures();
    {
        Bundle bundle(8, 256);
        assert(glplatEndLoadTextureBundle(bundle.bytes, bundle.size));
        checkTexture(hashA, bundle, 0); checkTexture(hashB, bundle, 1);
        glx_SetLoadCallback(remap);
        assert(glplatEndLoadTextureBundle(bundle.bytes, bundle.size));
        assert(callbackCount == 2 && callbackHashes[0] == hashA && callbackHashes[1] == hashB);
        checkTexture(hashB, bundle, 0);
        // Truncated replacements leave the existing pixels/palette untouched.
        memset(bundle.texture(0) + 0x20, 0, bundle.pixelSize);
        glplatTextureReplace(hashB, bundle.texture(0), bundle.entrySize - 1);
        assert(((u8*)glx_GetTex(hashB, false, false)->m_SwizzledData)[0] == 0xa7);
    }
    clearTextures();
    {
        Bundle bundle;
        u8* unaligned = (u8*)malloc(bundle.size + 1);
        memcpy(unaligned + 1, bundle.bytes, bundle.size);
        assert(glplatEndLoadTextureBundle(unaligned + 1, bundle.size));
        checkTexture(hashA, bundle, 0); checkTexture(hashB, bundle, 1);
        free(unaligned);
    }
    clearTextures();
    for (int problem = 0; problem < 12; ++problem) {
        Bundle bundle;
        size_t size = bundle.size;
        switch (problem) {
        case 0: size = 0x1f; break;
        case 1: size = 0x3f; break;
        case 2: be32(bundle.bytes + 4, 0xffffffff); break;
        case 3: be32(bundle.bytes + 0x34, 0xffffffff); break;
        case 4: be32(bundle.bytes + 0x38, 0xffffffff); break;
        case 5: be32(bundle.bytes + 0x38, 0x1f); break;
        case 6: be32(bundle.texture(1), 0); break;
        case 7: be32(bundle.texture(1), 0xffffffff); break;
        case 8: be32(bundle.texture(1) + 4, GXTex_Num); break;
        case 9: be16(bundle.texture(1) + 14, 0); break;
        case 10: be32(bundle.texture(1) + 20, 256); break; // palette not in file
        case 11: --size; break;
        }
        reject(bundle, size);
    }
    assert(!glplatEndLoadTextureBundle(NULL, 0x100));
    {
        const long page = sysconf(_SC_PAGESIZE);
        u8* pages = (u8*)mmap(NULL, page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        assert(pages != MAP_FAILED && mprotect(pages + page, page, PROT_NONE) == 0);
        // Ends immediately at an inaccessible page; no speculative header read.
        assert(!glplatEndLoadTextureBundle(pages + page - 31, 31));
        glplatTextureAdd(hashA, pages + page - 31, 31);
        assert(resourceCount == 0);
        assert(munmap(pages, page * 2) == 0);
    }
    {
        Bundle large(512); // each RGBA8 entry exceeds the old 0x40800 scratch buffer
        currentFile = &large;
        assert(glplatLoadTextureBundle("fixture.glt"));
        assert(openFiles == 0);
        checkTexture(hashA, large, 0); checkTexture(hashB, large, 1);
    }
    clearTextures();
    {
        Bundle bundle;
        currentFile = &bundle;
        be32(bundle.bytes + 0x28, 0xffffffff);
        assert(!glplatLoadTextureBundle("bad.glt"));
        assert(openFiles == 0 && resourceCount == 0);
        be32(bundle.bytes + 4, 0xffffffff);
        assert(!glplatLoadTextureBundle("bad-count.glt"));
        assert(openFiles == 0 && resourceCount == 0);
        currentFile = NULL;
        assert(!glplatLoadTextureBundle("missing.glt"));
    }
    glx_BackupTexMarkerLevel(0);
    puts("texture bundle regressions passed (BE async load, 100 reloads, palette, callbacks, alignment, bounds, guarded page, large streaming entry)");
}
