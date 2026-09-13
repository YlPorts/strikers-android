cmake_minimum_required(VERSION 3.24)
project(strikers_android_bootstrap LANGUAGES C CXX)

add_library(strikers_android SHARED
    native_bootstrap.cpp
)

target_compile_features(strikers_android PRIVATE cxx_std_20)

target_compile_definitions(strikers_android PRIVATE
    STRIKERS_ANDROID=1
    TARGET_ANDROID=1
)

target_link_libraries(strikers_android PRIVATE
    android
    log
)

# The desktop/native game tree lives at ../../../.. /smstrikers-port relative to this file.
# It is intentionally not linked into the APK yet. The first milestone is proving the Android
# Gradle + NDK + arm64 packaging path independently; then Aurora/SDL can be introduced without
# mixing build-system failures with platform-port failures.
set(STRIKERS_GAME_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../smstrikers-port")
message(STATUS "Strikers game root: ${STRIKERS_GAME_ROOT}")
