cmake_minimum_required(VERSION 3.25)
project(strikers_android LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Native game tree.
set(STRIKERS_GAME_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../smstrikers-port")

# Android policy: SDL must be a shared library because SDLActivity loads libSDL3.so before
# libmain.so. Dawn already ships an Android/aarch64 package in Aurora, so keep that path instead of
# compiling Dawn from source on every APK build.
set(AURORA_SDL3_PROVIDER "vendor" CACHE STRING "" FORCE)
set(AURORA_SDL3_LINKAGE "shared" CACHE STRING "" FORCE)
set(AURORA_DAWN_PROVIDER "package" CACHE STRING "" FORCE)
set(AURORA_DAWN_LINKAGE "static" CACHE STRING "" FORCE)
set(STRIKERS_AURORA ON CACHE BOOL "" FORCE)
set(STRIKERS_FFMPEG OFF CACHE BOOL "" FORCE)
set(STRIKERS_ASAN OFF CACHE BOOL "" FORCE)

# Configure the original project, but keep its desktop executable out of the default Android build.
# We reuse its object libraries and all of its existing Aurora dependency wiring below.
add_subdirectory("${STRIKERS_GAME_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/strikers-game" EXCLUDE_FROM_ALL)

# SDLActivity expects the application's final shared object to be libmain.so and invokes SDL_main
# from it. Aurora's lib/main.cpp supplies SDL_main and forwards to the game's aurora_main().
add_library(main SHARED
    "${STRIKERS_GAME_ROOT}/extern/aurora/lib/main.cpp"
    $<TARGET_OBJECTS:strikers_game>
    $<TARGET_OBJECTS:strikers_ode>
    $<TARGET_OBJECTS:strikers_sdk>
    $<TARGET_OBJECTS:strikers_platform>
    $<TARGET_OBJECTS:strikers_operators>
    $<TARGET_OBJECTS:strikers_musyx>
)

# Make the Android platform visible to the ported host code without changing desktop builds.
foreach(_target strikers_game strikers_ode strikers_sdk strikers_platform strikers_operators)
    target_compile_definitions(${_target} PRIVATE STRIKERS_ANDROID=1 TARGET_ANDROID=1)
endforeach()
target_compile_definitions(strikers_musyx PRIVATE STRIKERS_ANDROID=1 TARGET_ANDROID=1)

# The legacy ODE collision source uses the C malloc symbol without including stdlib.h. Desktop
# libc headers happened to expose it transitively; Android NDK/Clang correctly does not.
target_compile_options(strikers_ode PRIVATE "-include" "stdlib.h")

target_link_libraries(main PRIVATE
    port_flags
    aurora::core
    aurora::gx
    aurora::gd
    aurora::vi
    aurora::pad
    aurora::si
    aurora::card
    android
    log
)

# lib/main.cpp includes SDL_main.h directly, while aurora::core also provides the SDL include path.
target_link_libraries(main PRIVATE ${AURORA_SDL3_TARGET})

# Keep the game's custom allocation operators local to libmain.so, matching the desktop ELF build
# without using desktop-only -static-libstdc++/-static-libgcc flags.
set(_local_ops "${CMAKE_CURRENT_BINARY_DIR}/local-operators-android.map")
file(WRITE "${_local_ops}" "{ local: _Znw*; _Zna*; _ZdlPv*; _ZdaPv*; };\n")
target_link_options(main PRIVATE
    "-Wl,--version-script=${_local_ops}"
    "-Wl,-z,noexecstack"
)

message(STATUS "Strikers Android: game root = ${STRIKERS_GAME_ROOT}")
message(STATUS "Strikers Android: SDL target = ${AURORA_SDL3_TARGET}")
