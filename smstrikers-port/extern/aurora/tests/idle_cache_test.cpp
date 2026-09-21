#include "../lib/gfx/idle_cache.hpp"

#include <gtest/gtest.h>
#include <memory>
#include <unordered_map>

namespace {
struct Entry { std::shared_ptr<int> resource; uint32_t lastUsedFrame; };
using Cache = std::unordered_map<uint64_t, Entry>;
using aurora::gfx::detail::trim_idle_cache;

TEST(IdleCache, EvictsOldestColdEntriesWithoutDestroyingSubmittedResources) {
  auto submitted = std::make_shared<int>(42);
  Cache cache{{1, {submitted, 0}}, {2, {{}, 100}}, {3, {{}, 900}}, {4, {{}, 901}}};
  trim_idle_cache(cache, 3, 1000, 600);
  EXPECT_FALSE(cache.contains(1));
  EXPECT_TRUE(cache.contains(2));
  EXPECT_EQ(*submitted, 42);
  EXPECT_EQ(submitted.use_count(), 1);
  trim_idle_cache(cache, 1, 1000, 600);
  EXPECT_EQ(cache.size(), 2); // Recent entries may exceed the soft limit.
  EXPECT_TRUE(cache.contains(3));
  EXPECT_TRUE(cache.contains(4));
}

TEST(IdleCache, KeepsQueuedFramesAndHandlesCounterWrap) {
  Cache cache{{1, {{}, UINT32_MAX - 1000}}, {2, {{}, UINT32_MAX - 1}}, {3, {{}, 2}}, {4, {{}, 5}}};
  trim_idle_cache(cache, 1, 3, 600);
  EXPECT_FALSE(cache.contains(1));
  EXPECT_TRUE(cache.contains(2));
  EXPECT_TRUE(cache.contains(3));
  EXPECT_TRUE(cache.contains(4)); // Newer concurrent timestamp is not an ancient entry.
}

TEST(IdleCache, RepeatedSessionsDoNotRetainAllColdVariants) {
  Cache cache;
  uint64_t id = 0;
  for (uint32_t session = 0; session < 100; ++session) {
    const uint32_t frame = session * 1800;
    for (int i = 0; i < 64; ++i) cache.emplace(++id, Entry{{}, frame});
    trim_idle_cache(cache, 128, frame, 600);
    EXPECT_LE(cache.size(), 128);
    EXPECT_TRUE(cache.contains(id));
  }
  EXPECT_EQ(cache.size(), 128);
}
} // namespace
