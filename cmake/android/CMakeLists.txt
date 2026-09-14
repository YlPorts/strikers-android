cmake_minimum_required(VERSION 3.24)
project(strikers_android C CXX)

set(STRIKERS_AURORA ON CACHE BOOL "" FORCE)
set(STRIKERS_FFMPEG OFF CACHE BOOL "" FORCE)
set(STRIKERS_ASAN OFF CACHE BOOL "" FORCE)

# SDLActivity owns the Android JNI runtime. Keep it in libSDL3.so and load it
# before the game's shared object.
set(AURORA_CACHE_USE_ZSTD OFF CACHE BOOL "" FORCE)
set(AURORA_SDL3_PROVIDER vendor CACHE STRING "" FORCE)
set(AURORA_SDL3_LINKAGE shared CACHE STRING "" FORCE)
set(AURORA_DAWN_PROVIDER package CACHE STRING "" FORCE)
set(AURORA_DAWN_LINKAGE static CACHE STRING "" FORCE)

set(STRIKERS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../smstrikers-port")
add_subdirectory("${STRIKERS_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/strikers-core" EXCLUDE_FROM_ALL)

# Aurora needs SDL's real Android activity mutex while presenting. Those helpers
# are private/hidden in a shared SDL build, so compile a tiny forwarding API into
# SDL3 and explicitly keep/export only those two bridge symbols. The -u flags make
# them GC roots and --export-dynamic-symbol places them in libSDL3.so's dynsym.
if(TARGET SDL3-shared)
    target_sources(SDL3-shared PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/sdl_activity_mutex_bridge.c"
    )
    target_link_options(SDL3-shared PRIVATE
        -Wl,-u,SDL_StrikersLockActivityMutex
        -Wl,-u,SDL_StrikersUnlockActivityMutex
        -Wl,--export-dynamic-symbol=SDL_StrikersLockActivityMutex
        -Wl,--export-dynamic-symbol=SDL_StrikersUnlockActivityMutex
    )
endif()

target_compile_definitions(strikers_game PRIVATE NDEBUG=1)

target_sources(strikers_ode PRIVATE
    "${STRIKERS_ROOT}/src/ode/lcp.cpp"
    "${STRIKERS_ROOT}/src/ode/timer.cpp"
)

add_library(strikers_android SHARED
    android_link_anchor.c
    android_sdl_mutex_shim.c
    animation_missing_shim.c
    gx_efb_shim.cpp
    stats_gatherer_shim.cpp
)
set_target_properties(strikers_android PROPERTIES OUTPUT_NAME "strikers")

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

target_link_options(strikers_android PRIVATE
    -Wl,-u,SDL_main
    -Wl,--gc-sections
    -Wl,-z,noexecstack
)

set_target_properties(strikers_android PROPERTIES
    LIBRARY_OUTPUT_NAME "strikers"
)
