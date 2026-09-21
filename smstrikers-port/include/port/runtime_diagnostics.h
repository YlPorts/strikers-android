#pragma once
#include <stddef.h>

enum PortGameStage {
    PORT_GAME_IDLE, PORT_GAME_EVENTS, PORT_GAME_BEGIN_FRAME, PORT_GAME_PAD,
    PORT_GAME_TASKS, PORT_GAME_PROFILE, PORT_GAME_AUDIO, PORT_GAME_OVERLAY,
    PORT_GAME_CAPTURE, PORT_GAME_END_FRAME, PORT_GAME_FINISH_FRAME
};

// Only scalar atomics are touched on the frame path. Never write files or
// acquire a diagnostic lock from a game task.
#if defined(__ANDROID__) || defined(STRIKERS_DIAGNOSTICS_TEST)
void PortDiagnosticStage(PortGameStage stage);
void PortDiagnosticTask(unsigned priority, bool transition, unsigned state, unsigned next);
void PortDiagnosticTaskDone();
void PortFormatGameDiagnostics(char* buffer, size_t capacity);
#else
inline void PortDiagnosticStage(PortGameStage) { }
inline void PortDiagnosticTask(unsigned, bool, unsigned, unsigned) { }
inline void PortDiagnosticTaskDone() { }
#endif
