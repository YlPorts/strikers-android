#include "port/runtime_diagnostics.h"
#if defined(__ANDROID__) || defined(STRIKERS_DIAGNOSTICS_TEST)
#include <atomic>
#include <stdio.h>

namespace {
static_assert(ATOMIC_INT_LOCK_FREE == 2, "Game diagnostics must not take locks");
std::atomic<unsigned> s_stage{PORT_GAME_IDLE}, s_tick{0}, s_task{0};
std::atomic<unsigned> s_transition{0}, s_state{0}, s_next{0};
const char* const stages[] = {"idle", "events", "begin_frame", "pad", "tasks", "profile",
    "audio", "overlay", "capture", "end_frame", "finish_frame"};
const char* task_name(unsigned task) {
    switch (task) { // nlTaskManager stores priority + 1; zero means no task.
    case 0: return "none";
    case 1: return "Reset";
    case 2: return "Transition";
    case 3: return "BeginFrame";
    case 4: return "Pad";
    case 6: return "Clock";
    case 7: return "Loading";
    case 8: return "FixedUpdate";
    case 9: return "WorldUpdate";
    case 11: return "GameRender";
    case 12: return "Particles";
    case 13: return "FrontEnd";
    case 14: return "Tweaker";
    case 15: return "AudioUpdate";
    case 16: return "EndFrame";
    case 17: return "ComUpdate";
    case 19: return "Test";
    case 21: return "DispatchEvents";
    default: return "unknown";
    }
}
}

void PortDiagnosticStage(PortGameStage stage) {
    if (stage == PORT_GAME_EVENTS) s_tick.fetch_add(1, std::memory_order_relaxed);
    s_stage.store(stage, std::memory_order_relaxed);
}
void PortDiagnosticTask(unsigned priority, bool transition, unsigned state, unsigned next) {
    s_transition.store(transition, std::memory_order_relaxed);
    s_state.store(state, std::memory_order_relaxed);
    s_next.store(next, std::memory_order_relaxed);
    s_task.store(priority + 1, std::memory_order_relaxed);
}
void PortDiagnosticTaskDone() { s_task.store(0, std::memory_order_relaxed); }
void PortFormatGameDiagnostics(char* buffer, size_t capacity) {
    if (!buffer || !capacity) return;
    const unsigned stage = s_stage.load(std::memory_order_relaxed);
    snprintf(buffer, capacity, "game_stage=%s game_tick=%u task=%s task_mode=%s state=%x next=%x",
             stage < sizeof(stages) / sizeof(*stages) ? stages[stage] : "unknown",
             s_tick.load(std::memory_order_relaxed), task_name(s_task.load(std::memory_order_relaxed)),
             s_transition.load(std::memory_order_relaxed) ? "transition" : "run",
             s_state.load(std::memory_order_relaxed), s_next.load(std::memory_order_relaxed));
}
#endif
