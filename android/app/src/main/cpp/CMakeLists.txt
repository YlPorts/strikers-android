cmake_minimum_required(VERSION 3.24)
project(strikers_android_app LANGUAGES C CXX)

add_subdirectory(drivers)

# Small library loaded before libstrikers.so. It owns no game code; its only job is to
# capture a fatal signal that happens while the dynamic loader is running C/C++ constructors.
add_library(strikers_diag SHARED
    "${CMAKE_CURRENT_LIST_DIR}/strikers_diag.cpp"
)
target_compile_features(strikers_diag PRIVATE cxx_std_17)
target_compile_options(strikers_diag PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

# Build the exact full native library that is validated by android-full-link.
# Keeping this as an add_subdirectory means the APK and the standalone linker
# workflow cannot silently drift onto different source sets.
set(STRIKERS_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../..")
add_subdirectory(
    "${STRIKERS_REPO_ROOT}/cmake/android"
    "${CMAKE_CURRENT_BINARY_DIR}/strikers-full"
)

# The Android launcher/JNI bridge belongs to the same shared object as the game.
# cmake/android names the resulting library libstrikers.so.
target_sources(strikers_android PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/strikers_jni.cpp"
)

target_compile_definitions(strikers_android PRIVATE
    STRIKERS_ANDROID=1
)

target_compile_options(strikers_android PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

find_library(android_log log REQUIRED)
find_library(android_lib android REQUIRED)

target_link_libraries(strikers_android PRIVATE
    ${android_log}
    ${android_lib}
)
