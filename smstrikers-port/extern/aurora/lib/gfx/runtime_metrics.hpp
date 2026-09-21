#pragma once

#include <atomic>
#include <cstdint>

namespace aurora::gfx::runtime_metrics {
// Diagnostic readers must never acquire a renderer/cache/driver lock: those
// may be precisely what a stalled thread is holding. Samples are approximate.
// 0 between frames, 1 frame slot, 2 staging, 3 recording, 4 FIFO drain,
// 5 cache cleanup, 6 finish recording, 7 overlay, 8 enqueue submission.
inline std::atomic_uint32_t framePhase{0};
inline std::atomic_uint32_t pipelineCount{0};
inline std::atomic_uint32_t samplerCount{0};
inline std::atomic_uint64_t compilingPipeline{0};
inline std::atomic_uint64_t waitingPipeline{0};

// Retain transient stalls between the sparse Android log samples. Each peak
// covers the interval since its last read, not just the frame at sample time.
struct IntervalPeak {
  std::atomic_uint32_t value{0};
  void record(uint64_t sample) noexcept {
    const auto bounded = static_cast<uint32_t>(sample > UINT32_MAX ? UINT32_MAX : sample);
    auto old = value.load(std::memory_order_relaxed);
    while (old < bounded && !value.compare_exchange_weak(old, bounded, std::memory_order_relaxed)) { }
  }
  uint32_t take() noexcept { return value.exchange(0, std::memory_order_relaxed); }
};
inline IntervalPeak presentGapUs, frameSlotUs, stagingUs, cpuFrameUs, drawCalls, uploadKiB;
inline std::atomic_uint32_t gapsOver25ms{0};
inline std::atomic_uint32_t culledDraws{0}, emptyAttachmentPasses{0};
inline std::atomic_uint32_t bindingsSaved{0};
// 0 starting, 1 Vulkan, 2 OpenGL ES, 3 another backend.
inline std::atomic_uint32_t activeBackend{0};
} // namespace aurora::gfx::runtime_metrics
