#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

namespace aurora::gfx::detail {
// A soft limit: retain anything used recently, including all queued frame work.
// Drop only the cache's reference; submitted GPU commands still own their resources.
template <class Map>
void trim_idle_cache(Map& cache, size_t limit, uint32_t frame, uint32_t idleFrames) {
  if (cache.size() <= limit) return;
  std::vector<std::pair<uint32_t, typename Map::key_type>> candidates;
  for (const auto& [key, value] : cache) {
    const uint32_t age = frame - value.lastUsedFrame;
    // Handle frame-counter wrap while rejecting a concurrently newer timestamp.
    if (age > idleFrames && age < UINT32_MAX / 2) candidates.emplace_back(age, key);
  }
  std::sort(candidates.begin(), candidates.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
  for (const auto& [age, key] : candidates) {
    if (cache.size() <= limit) break;
    cache.erase(key);
  }
}
} // namespace aurora::gfx::detail
