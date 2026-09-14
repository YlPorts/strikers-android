// Android/ELF compatibility definitions for the three world-animation symbols
// that the upstream decomp deliberately leaves in tools/genstubs.allow.
//
// The checked-in stubs_generated.c was last generated on Mach-O, where external
// C symbols carry an extra leading underscore. Android/ELF does not, so those
// definitions do not satisfy the Itanium C++ names referenced by world.cpp.
// Keep these Android-only and fail loudly if normal gameplay reaches code that
// still needs reconstruction rather than silently corrupting an animation object.

#include <stdio.h>
#include <stdlib.h>

static void android_missing_animation(const char* name)
{
    fprintf(stderr, "\n[android-port] missing world animation implementation: %s\n", name);
    abort();
}

void android_stub_tm_anim_ctor(void)
    __asm__("_ZN16TMAnimControllerC1EPKcP5World");
void android_stub_tm_anim_ctor(void)
{
    android_missing_animation("TMAnimController::TMAnimController(const char*, World*)");
}

void android_stub_skinned_create_mesh(void)
    __asm__("_ZN21SkinnedAnimController16CreateGLSkinMeshEP7glModel");
void android_stub_skinned_create_mesh(void)
{
    android_missing_animation("SkinnedAnimController::CreateGLSkinMesh(glModel*)");
}

void android_stub_skinned_anim_ctor(void)
    __asm__("_ZN21SkinnedAnimControllerC1EPKcP5World");
void android_stub_skinned_anim_ctor(void)
{
    android_missing_animation("SkinnedAnimController::SkinnedAnimController(const char*, World*)");
}
