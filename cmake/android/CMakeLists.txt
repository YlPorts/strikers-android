cmake_minimum_required(VERSION 3.24)
project(strikers_android C CXX)

# Keep the Android link experiment isolated from the desktop executable target. The
# Strikers subproject still owns all source lists/compile flags; this wrapper only
# repackages its already-portable object libraries as an Android .so.
set(STRIKERS_AURORA ON CACHE BOOL "" FORCE)
set(STRIKERS_FFMPEG OFF CACHE BOOL "" FORCE)
set(STRIKERS_ASAN OFF CACHE BOOL "" FORCE)

# These settings are the Android Aurora combination already proven by the Aurora
# probe workflow.
set(AURORA_CACHE_USE_ZSTD OFF CACHE BOOL "" FORCE)
set(AURORA_SDL3_PROVIDER vendor CACHE STRING "" FORCE)
set(AURORA_SDL3_LINKAGE static CACHE STRING "" FORCE)
set(AURORA_DAWN_PROVIDER package CACHE STRING "" FORCE)
set(AURORA_DAWN_LINKAGE static CACHE STRING "" FORCE)

set(STRIKERS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../smstrikers-port")
add_subdirectory("${STRIKERS_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/strikers-core" EXCLUDE_FROM_ALL)

# The retail build took the LCP solver from a prebuilt ODE library, so it is not in
# build-sources.txt. Android links the native game as one shared object and needs the
# real implementation here rather than the generated placeholder stub.
target_sources(strikers_ode PRIVATE "${STRIKERS_ROOT}/src/ode/lcp.cpp")

add_library(strikers_android SHARED android_link_anchor.c)
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

# aurora::main is a static archive. Android's SDLActivity resolves SDL_main with
# dlsym at runtime, so there is otherwise no link-time reference that would pull
# main.cpp out of that archive. Force that single public entry point into the .so.
target_link_options(strikers_android PRIVATE
    -Wl,-u,SDL_main
    -Wl,--gc-sections
    -Wl,-z,noexecstack
)

# JNI/SDL will load this exact soname from the APK.
set_target_properties(strikers_android PROPERTIES
    LIBRARY_OUTPUT_NAME "strikers"
)
