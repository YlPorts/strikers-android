cmake_minimum_required(VERSION 3.25)
project(strikers_card_regression LANGUAGES C CXX)
include(FetchContent)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(SDL_SHARED OFF CACHE BOOL "" FORCE)
set(SDL_STATIC ON CACHE BOOL "" FORCE)
set(SDL_UNIX_CONSOLE_BUILD ON CACHE BOOL "" FORCE)
set(SDL_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
set(SDL_TESTS OFF CACHE BOOL "" FORCE)
set(SDL_EXAMPLES OFF CACHE BOOL "" FORCE)
foreach(subsystem AUDIO VIDEO GPU RENDER CAMERA JOYSTICK HAPTIC HIDAPI SENSOR POWER DIALOG TRAY)
  set(SDL_${subsystem} OFF CACHE BOOL "" FORCE)
endforeach()
FetchContent_Declare(SDL3 URL https://github.com/libsdl-org/SDL/archive/refs/tags/release-3.4.10.tar.gz)
FetchContent_Declare(fmt URL https://github.com/fmtlib/fmt/archive/refs/tags/12.1.0.tar.gz
  URL_HASH SHA256=ea7de4299689e12b6dddd392f9896f08fb0777ac7168897a244a6d6085043fea)
FetchContent_MakeAvailable(SDL3 fmt)
get_filename_component(AURORA "${CMAKE_CURRENT_SOURCE_DIR}/../../../smstrikers-port/extern/aurora" ABSOLUTE)
add_executable(card_regression card_regression.cpp
  ${AURORA}/lib/card/BlockAllocationTable.cpp
  ${AURORA}/lib/card/CardRawFile.cpp
  ${AURORA}/lib/card/CardGciFolder.cpp
  ${AURORA}/lib/card/Directory.cpp
  ${AURORA}/lib/card/DolphinCardPath.cpp
  ${AURORA}/lib/card/File.cpp
  ${AURORA}/lib/card/FileIO.cpp
  ${AURORA}/lib/card/SRAM.cpp
  ${AURORA}/lib/card/Util.cpp
  ${AURORA}/lib/dolphin/card.cpp
  ${AURORA}/lib/io.cpp)
# Compile the production Android card path against real host SDL I/O. Only the
# document-provider boundary and application globals are replaced by the fixture.
target_compile_definitions(card_regression PRIVATE __ANDROID__ TARGET_PC=1)
target_include_directories(card_regression PRIVATE ${AURORA}/include ${AURORA}/lib)
target_compile_options(card_regression PRIVATE -UNDEBUG -fsanitize=undefined)
target_link_options(card_regression PRIVATE -fsanitize=undefined)
target_link_libraries(card_regression PRIVATE SDL3::SDL3-static fmt::fmt)
enable_testing()
foreach(region E P J)
  add_test(NAME card_saf_${region} COMMAND card_regression ${region} saf)
  add_test(NAME card_internal_${region} COMMAND card_regression ${region} internal)
endforeach()
