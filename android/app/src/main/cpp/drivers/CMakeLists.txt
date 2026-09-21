include(FetchContent)
# Scope these settings to the optional loader and its pinned BSD-licensed dependency.
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS OFF)
FetchContent_Declare(adrenotools
  GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
  GIT_TAG 8fae8ce254dfc1344527e05301e43f37dea2df80
  GIT_SUBMODULES lib/linkernsbypass
  GIT_SUBMODULES_RECURSE TRUE)
FetchContent_MakeAvailable(adrenotools)

# Deliberately not named libvulkan.so: normal devices must resolve the system loader.
add_library(strikers_vulkan SHARED driver_loader.cpp)
target_link_libraries(strikers_vulkan PRIVATE adrenotools log dl)
target_compile_options(strikers_vulkan PRIVATE -Wall -Wextra)
target_link_options(strikers_vulkan PRIVATE -Wl,--exclude-libs,ALL)
set_target_properties(strikers_vulkan PROPERTIES CXX_VISIBILITY_PRESET hidden)
add_dependencies(strikers_vulkan main_hook hook_impl file_redirect_hook gsl_alloc_hook)
