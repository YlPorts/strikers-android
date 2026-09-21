#include <aurora/aurora.h>
#include <dolphin/card.h>
#include "card/AndroidDocuments.hpp"
#include "card/CardGciFolder.hpp"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fcntl.h>
#include <string>
#include <vector>

namespace aurora {
AuroraConfig g_config{};
char g_gameName[4] = {'G', '4', 'Q', 'E'};
void log_internal(AuroraLogLevel, const char*, const char* text, unsigned int length) noexcept {
  std::fprintf(stderr, "%.*s\n", static_cast<int>(length), text);
}
}

namespace fs = std::filesystem;
static fs::path disk;
static bool selected = true;
static bool revoked = false;
static bool readOnly = false;

static fs::path document(const char* path) {
  const std::string_view value(path);
  assert(value.starts_with(aurora::card::documents::Root));
  return disk / value.substr(std::strlen(aurora::card::documents::Root));
}
extern "C" bool PortAndroidSaveEnabled() { return selected; }
extern "C" bool PortAndroidSaveIsDirectory(const char* path) {
  return !revoked && fs::is_directory(document(path));
}
extern "C" bool PortAndroidSaveMkdir(const char* path) {
  if (revoked || readOnly) return false;
  fs::create_directories(document(path));
  return fs::is_directory(document(path));
}
extern "C" bool PortAndroidSaveDelete(const char* path) {
  return !revoked && !readOnly && fs::remove(document(path));
}
extern "C" int PortAndroidSaveOpen(const char* path, int mode) {
  if (revoked || (readOnly && mode != 0)) return -1;
  return open(document(path).c_str(), mode == 0 ? O_RDONLY : mode == 1 ? O_RDWR : O_RDWR | O_CREAT | O_TRUNC, 0600);
}
extern "C" bool PortAndroidSaveList(const char* path, void (*append)(const char*, void*), void* data) {
  if (revoked || !fs::is_directory(document(path))) return false;
  for (const auto& entry : fs::directory_iterator(document(path))) {
    if (entry.is_regular_file() && entry.path().extension() == ".gci")
      append(entry.path().filename().c_str(), data);
  }
  return true;
}

int main(int argc, char** argv) {
  assert(argc == 3);
  aurora::g_gameName[3] = argv[1][0];
  selected = std::strcmp(argv[2], "saf") == 0;
  char temporary[] = "/tmp/strikers-card-XXXXXX";
  assert(mkdtemp(temporary));
  disk = temporary;
  const std::string storage = disk.string();
  aurora::g_config.userPath = storage.c_str();
  aurora::g_config.logLevel = LOG_WARNING;
  const char* region = argv[1][0] == 'P' ? "EUR" : argv[1][0] == 'J' ? "JAP" : "USA";
  for (const auto* slot : {"Card A", "Card B"}) fs::create_directories(disk / region / slot);
  const std::string game(aurora::g_gameName, 4);
  CARDInit(game.c_str(), "01");

  // The game probes BEFORE mounting/opening. A direct FileIO-only test misses this.
  s32 capacity = 0, sector = 0;
  assert(CARDProbe(0));
  assert(CARDProbeEx(0, &capacity, &sector) == CARD_RESULT_READY);
  assert(capacity > 0 && sector == 8192);
  assert(CARDProbe(1));
  assert(CARDMount(0, nullptr, nullptr) == CARD_RESULT_READY);
  assert(CARDCheck(0) == CARD_RESULT_READY);
  CARDFileInfo handle{};
  assert(CARDOpen(0, "Strikers", &handle) == CARD_RESULT_NOFILE);
  std::vector<unsigned char> expected(5 * 8192), actual(expected.size());
  for (size_t i = 0; i < expected.size(); ++i) expected[i] = (i * 37) % 251;
  assert(CARDCreate(0, "Strikers", expected.size(), &handle) == CARD_RESULT_READY);
  assert(CARDWrite(&handle, expected.data(), expected.size(), 0) == CARD_RESULT_READY);
  CARDStat status{};
  assert(CARDGetStatus(0, handle.fileNo, &status) == CARD_RESULT_READY);
  assert(status.length == expected.size());
  assert(CARDSetStatus(0, handle.fileNo, &status) == CARD_RESULT_READY);
  assert(CARDClose(&handle) == CARD_RESULT_READY);
  assert(CARDOpen(0, "Strikers", &handle) == CARD_RESULT_READY);
  assert(CARDRead(&handle, actual.data(), actual.size(), 0) == CARD_RESULT_READY);
  assert(actual == expected);

  // A fresh card instance must reload the GCI header and persisted payload.
  aurora::card::CardGciFolder reopened;
  reopened.InitCard(game.c_str(), "01");
  fs::path path = (selected ? fs::path(aurora::card::documents::Root) : disk) / region / "Card A";
  assert(reopened.open(path));
  aurora::card::FileHandle fresh;
  assert(reopened.openFile("Strikers", fresh) == aurora::card::ECardResult::READY);
  std::fill(actual.begin(), actual.end(), 0);
  assert(reopened.fileRead(fresh, actual.data(), actual.size()) == aurora::card::ECardResult::READY);
  assert(actual == expected);

  if (selected) {
    readOnly = true;
    assert(CARDWrite(&handle, expected.data(), expected.size(), 0) == CARD_RESULT_IOERROR);
    readOnly = false;
    assert(CARDRead(&handle, actual.data(), actual.size(), 0) == CARD_RESULT_READY);
    assert(actual == expected);
    revoked = true;
    assert(!CARDProbe(0));
    assert(CARDProbeEx(0, &capacity, &sector) == CARD_RESULT_NOCARD);
    assert(!reopened.open(path));
    assert(fs::exists(disk / region / "Card A" / ("01-" + game + "-Strikers.gci")));
    revoked = false;
    assert(CARDProbe(0));
    fs::remove(disk / region / "Card B");
    assert(!CARDProbe(1));
  }
  fs::remove_all(disk);
  std::printf("Card probe/create/write/reopen/read: %s %s passed\n", region, argv[2]);
}
