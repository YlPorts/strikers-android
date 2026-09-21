cmake_minimum_required(VERSION 3.24)
project(strikers_texture_bundle_regression LANGUAGES CXX)
get_filename_component(PORT "${CMAKE_CURRENT_SOURCE_DIR}/../../../smstrikers-port" ABSOLUTE)
add_executable(texture_bundle_test ../texture_bundle_test.cpp
    ${PORT}/src/NL/glx/glxTexture.cpp ${PORT}/src/NL/gc/gcSwizzler.cpp
    ${PORT}/src/NL/nlAVLTree.cpp)
# The legacy non-Aurora OS headers define globals; one translation unit avoids
# duplicate definitions without weakening linker or sanitizer checks.
set_target_properties(texture_bundle_test PROPERTIES CXX_STANDARD 11 CXX_STANDARD_REQUIRED YES UNITY_BUILD ON)
target_include_directories(texture_bundle_test PRIVATE ${PORT}/include ${PORT}/include/msl ${PORT}/src)
target_compile_definitions(texture_bundle_test PRIVATE TARGET_PC=1)
target_compile_options(texture_bundle_test PRIVATE -fshort-wchar -fsigned-char -fno-strict-aliasing
    -include ${PORT}/include/port/prelude.h -ffunction-sections -fdata-sections -UNDEBUG
    -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer)
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(texture_bundle_test PRIVATE -fdeclspec)
else()
    # GCC is used only by this isolated host fixture; the game itself uses Clang.
    target_compile_options(texture_bundle_test PRIVATE "-D__declspec(x)=")
endif()
target_link_options(texture_bundle_test PRIVATE -Wl,--gc-sections -fsanitize=address,undefined)
enable_testing()
add_test(NAME texture_bundle_regression COMMAND texture_bundle_test)
