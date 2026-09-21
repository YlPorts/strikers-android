#include "../../smstrikers-port/extern/aurora/lib/card/AndroidDocuments.hpp"

#include <array>
#include <cassert>
#include <cstdio>
#include <fcntl.h>
#include <string>

static std::string backing;
static bool denied = false;
extern "C" int PortAndroidSaveOpen(const char*, int mode) {
  if (denied) return -1;
  return open(backing.c_str(), mode == 0 ? O_RDONLY : mode == 2 ? O_CREAT | O_RDWR | O_TRUNC : O_RDWR, 0600);
}

int main() {
  using namespace aurora::card::documents;
  char temporary[] = "/tmp/strikers-save-test-XXXXXX";
  int initial = mkstemp(temporary);
  assert(initial >= 0);
  close(initial);
  backing = temporary;
  const char* path = "/strikers-saf/USA/Card A/progress.gci";
  assert(handles(path));
  assert(!handles("/data/user/0/com.ylports.strikers/files/USA/Card A/progress.gci"));
  std::array<unsigned char, 16448> data{};
  data.fill(17);
  assert(write(path, data.data(), data.size(), 0));
  const std::array<unsigned char, 4> updated{1, 2, 3, 4};
  assert(write(path, updated.data(), updated.size(), 8192 + 64));
  std::array<unsigned char, 16448> result{};
  assert(read(path, result.data(), result.size(), 0));
  for (size_t i = 0; i < result.size(); ++i) {
    assert(result[i] == (i >= 8256 && i < 8260 ? updated[i - 8256] : 17));
  }
  // A new descriptor sees persisted data; no shutdown callback or export is needed.
  assert(!read(path, result.data(), 1, data.size()));
  assert(!write(path, updated.data(), updated.size(), -1));
  denied = true;
  assert(!write(path, updated.data(), updated.size(), 0));
  assert(!read(path, result.data(), result.size(), 0));
  denied = false;
  assert(read(path, result.data(), result.size(), 0));
  assert(result[0] == 17); // Failed access never creates/truncates the saved progress.
  unlink(temporary);
  puts("Direct save descriptor tests passed");
}
