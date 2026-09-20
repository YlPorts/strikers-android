#pragma once

#include <string_view>

#if defined(__ANDROID__)
#include <cerrno>
#include <cstddef>
#include <sys/stat.h>
#include <unistd.h>

// Optional Android host bridge; portable Aurora builds do not link JNI.
extern "C" bool PortAndroidSaveEnabled() __attribute__((weak));
extern "C" int PortAndroidSaveOpen(const char*, int) __attribute__((weak));
extern "C" bool PortAndroidSaveMkdir(const char*) __attribute__((weak));
extern "C" bool PortAndroidSaveDelete(const char*) __attribute__((weak));
extern "C" bool PortAndroidSaveList(const char*, void (*)(const char*, void*), void*) __attribute__((weak));
#endif

namespace aurora::card::documents {
inline constexpr const char* Root = "/strikers-saf/";
inline bool handles(std::string_view path) {
#if defined(__ANDROID__)
  return path.starts_with(Root);
#else
  return false;
#endif
}

#if defined(__ANDROID__)
// Java hands over a seekable descriptor, not a filesystem path derived from a URI.
struct Descriptor {
  int value;
  Descriptor(const char* path, int mode) : value(PortAndroidSaveOpen ? PortAndroidSaveOpen(path, mode) : -1) {}
  ~Descriptor() { if (value >= 0) close(value); }
  Descriptor(const Descriptor&) = delete;
  Descriptor& operator=(const Descriptor&) = delete;
  explicit operator bool() const { return value >= 0; }
};

inline bool read(const char* path, void* buffer, size_t length, off_t offset) {
  Descriptor fd(path, 0);
  if (!fd || offset < 0) return false;
  auto* bytes = static_cast<unsigned char*>(buffer);
  while (length > 0) {
    const auto got = pread(fd.value, bytes, length, offset);
    if (got < 0 && errno == EINTR) continue;
    if (got <= 0) return false;
    length -= got;
    bytes += got;
    offset += got;
  }
  return true;
}

inline bool write(const char* path, const void* buffer, size_t length, off_t offset) {
  Descriptor fd(path, 1);
  if (!fd || offset < 0) return false;
  const auto* bytes = static_cast<const unsigned char*>(buffer);
  while (length > 0) {
    const auto put = pwrite(fd.value, bytes, length, offset);
    if (put < 0 && errno == EINTR) continue;
    if (put <= 0) return false;
    length -= put;
    bytes += put;
    offset += put;
  }
  int result;
  do { result = fsync(fd.value); } while (result < 0 && errno == EINTR);
  return result == 0;
}
#endif
} // namespace aurora::card::documents
