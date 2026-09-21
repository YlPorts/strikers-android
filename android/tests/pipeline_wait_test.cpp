#include "../../smstrikers-port/extern/aurora/lib/gfx/wait_with_progress.hpp"

#include <cassert>
#include <cstdio>
#include <thread>

int main() {
  std::mutex mutex;
  std::condition_variable changed;
  bool ready = false, stop = false, callbackProcessed = false;
  int progressCalls = 0;
  // Simulate a compiler completion that depends on processing a GPU callback.
  std::thread compiler([&] {
    std::unique_lock lock(mutex);
    assert(changed.wait_for(lock, std::chrono::seconds(2), [&] { return callbackProcessed; }));
    ready = true;
    changed.notify_all();
  });
  std::unique_lock lock(mutex);
  aurora::gfx::detail::wait_with_progress(changed, lock, [&] { return ready || stop; }, [&] {
    // Completion may need the very same mutex: progress must run with it released.
    std::lock_guard callbackLock(mutex);
    ++progressCalls;
    callbackProcessed = true;
    changed.notify_all();
  });
  assert(ready && progressCalls > 0 && lock.owns_lock());
  lock.unlock();
  compiler.join();

  ready = false;
  std::thread shutdown([&] {
    std::lock_guard stoppingLock(mutex);
    stop = true;
    changed.notify_all();
  });
  lock.lock();
  aurora::gfx::detail::wait_with_progress(changed, lock, [&] { return ready || stop; }, [] {});
  assert(stop && !ready);
  lock.unlock();
  shutdown.join();
  std::puts("Pipeline completion progress and shutdown passed");
}
