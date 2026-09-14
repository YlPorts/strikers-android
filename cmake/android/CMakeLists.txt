cmake_minimum_required(VERSION 3.24)
project(strikers_android C CXX)

# Keep the Android link experiment isolated from the desktop executable target. The
# Strikers subproject still owns all source lists/compile flags; this wrapper only
# repackages its already-portable object libraries as Android shared libraries.
set(STRIKERS_AURORA ON CACHE BOOL "" FORCE)
set(STRIKERS_FFMPEG OFF CACHE BOOL "" FORCE)
set(STRIKERS_ASAN OFF CACHE BOOL "" FORCE)

# Android SDLActivity expects SDL's JNI runtime to live in libSDL3.so and to be
# loaded before the game's shared object.  Do not fold SDL3's JNI_OnLoad into the
# very large libstrikers.so: keep SDL3 shared while Aurora/Dawn remain otherwise
# identical to the native port configuration.
set(AURORA_CACHE_USE_ZSTD OFF CACHE BOOL "" FORCE)
set(AURORA_SDL3_PROVIDER vendor CACHE STRING "" FORCE)
set(AURORA_SDL3_LINKAGE shared CACHE STRING "" FORCE)
set(AURORA_DAWN_PROVIDER package CACHE STRING "" FORCE)
set(AURORA_DAWN_LINKAGE static CACHE STRING "" FORCE)

set(STRIKERS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../smstrikers-port")
add_subdirectory("${STRIKERS_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/strikers-core" EXCLUDE_FROM_ALL)

# Android debug APKs are used for packaging/logcat, not the original game's fuzzy
# debugger. Several decompilation-only debugger methods are intentionally absent;
# compiling retail-style assertions out keeps the Android core on the same code path
# as the already-proven Release full-link build.
target_compile_definitions(strikers_game PRIVATE NDEBUG=1)

# The retail build took these ODE pieces from the prebuilt ODE library, so they are
# not in build-sources.txt. Android links the game as one shared object and needs the
# real solver plus its stopwatch implementation rather than the generated stubs.
target_sources(strikers_ode PRIVATE
    "${STRIKERS_ROOT}/src/ode/lcp.cpp"
    "${STRIKERS_ROOT}/src/ode/timer.cpp"
)

add_library(strikers_android SHARED
    android_link_anchor.c
    animation_missing_shim.c
    gx_efb_shim.cpp
    stats_gatherer_shim.cpp
)
set_target_properties(strikers_android PROPERTIES OUTPUT_NAME "strikers")

# Linking an OBJECT_LIBRARY target is supported by our CMake baseline and adds both
# its object files and usage requirements. This is the same native code used by the
# desktop target, not an emulator core.
target_link_libraries(strikers_android PRIVATE
    strikers_game
    strikers_ode
    strikers_sdk
    strikers_platform
    strikers_operators
    strikers_musyx
    port_flags
    aurora::core
    aurora::gx
    aurora::gd
    aurora::vi
    aurora::pad
    aurora::si
    aurora::card
    aurora::main
)

if(TARGET ZLIB::ZLIB)
    target_link_libraries(strikers_android PRIVATE ZLIB::ZLIB)
endif()

# aurora::main is a static archive. SDLActivity resolves SDL_main with dlsym from
# libstrikers.so at runtime, so there is otherwise no link-time reference that would
# pull main.cpp out of that archive. Force that single public entry point into the .so.
target_link_options(strikers_android PRIVATE
    -Wl,-u,SDL_main
    -Wl,--gc-sections
    -Wl,-z,noexecstack
)

# JNI/SDL will load this exact game soname after libSDL3.so.
set_target_properties(strikers_android PROPERTIES
    LIBRARY_OUTPUT_NAME "strikers"
)
