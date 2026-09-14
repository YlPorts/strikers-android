cmake_minimum_required(VERSION 3.24)
project(strikers_android_bootstrap LANGUAGES C CXX)

add_library(strikers_android SHARED
    strikers_jni.cpp
)

set_target_properties(strikers_android PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
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
