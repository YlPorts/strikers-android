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
} // namespace aurora::gfx::runtime_metrics
