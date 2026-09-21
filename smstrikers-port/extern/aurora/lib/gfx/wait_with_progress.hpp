#pragma once

#include <chrono>
#include <condition_variable>
#include <mutex>

namespace aurora::gfx::detail {
// A pending pipeline must still finish before its draw. Service GPU callbacks
// between sleeps, outside the cache lock, rather than starving them indefinitely.
template <typename Ready, typename Progress>
void wait_with_progress(std::condition_variable& changed, std::unique_lock<std::mutex>& lock,
                        Ready ready, Progress progress) {
  while (!changed.wait_for(lock, std::chrono::milliseconds(4), ready)) {
    lock.unlock();
    progress();
    lock.lock();
  }
}
} // namespace aurora::gfx::detail
