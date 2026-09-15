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

# SDLActivity owns JNI_OnLoad on Android. Aurora still needs SDL's private
# Android activity-mutex helpers while presenting, so expose only a tiny bridge
# from the shared SDL3 library. The game itself is linked against SDL3-shared
# below, which resolves both SDL's public API and these bridge entry points.
if(TARGET SDL3-shared)
    target_sources(SDL3-shared PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/sdl_activity_mutex_bridge.c"
    )

    # SDL 3.4.x links its shared library with src/dynapi/SDL_dynapi.sym, whose
    # `local: *;` rule hides every symbol not explicitly listed. Default C
    # visibility and --export-dynamic-symbol are therefore not sufficient for
    # our bridge. Add the two bridge entry points to SDL's export map before
    # Ninja links libSDL3.so.
    include(FetchContent)
    FetchContent_GetProperties(SDL SOURCE_DIR _strikers_sdl3_source_dir)
    set(_strikers_sdl3_export_map
        "${_strikers_sdl3_source_dir}/src/dynapi/SDL_dynapi.sym")
    if(NOT EXISTS "${_strikers_sdl3_export_map}")
        message(FATAL_ERROR
            "Unable to locate SDL3 export map: ${_strikers_sdl3_export_map}")
    endif()

    file(READ "${_strikers_sdl3_export_map}" _strikers_sdl3_exports)
    set(_strikers_sdl3_export_marker
        "    # extra symbols go here (don't modify this line)")
    foreach(_strikers_bridge_symbol IN ITEMS
            SDL_StrikersLockActivityMutex
            SDL_StrikersUnlockActivityMutex)
        string(FIND "${_strikers_sdl3_exports}"
            "    ${_strikers_bridge_symbol};" _strikers_bridge_pos)
        if(_strikers_bridge_pos EQUAL -1)
            string(REPLACE
                "${_strikers_sdl3_export_marker}"
                "    ${_strikers_bridge_symbol};\n${_strikers_sdl3_export_marker}"
                _strikers_sdl3_exports
                "${_strikers_sdl3_exports}")
        endif()
    endforeach()
    file(WRITE "${_strikers_sdl3_export_map}" "${_strikers_sdl3_exports}")

    target_link_options(SDL3-shared PRIVATE
        -Wl,-u,SDL_StrikersLockActivityMutex
        -Wl,-u,SDL_StrikersUnlockActivityMutex
        -Wl,--export-dynamic-symbol=SDL_StrikersLockActivityMutex
        -Wl,--export-dynamic-symbol=SDL_StrikersUnlockActivityMutex
    )
else()
    message(FATAL_ERROR "Android port requires the vendored SDL3-shared target")
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
    SDL3-shared
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
