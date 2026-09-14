// The Android game entry point is provided by aurora::main as SDL_main.
// This exported symbol only gives CMake a concrete source for the shared target;
// no emulation or game logic lives in this wrapper.
#if defined(__GNUC__)
__attribute__((visibility("default")))
#endif
void strikers_android_link_anchor(void)
{
}
