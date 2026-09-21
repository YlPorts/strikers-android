#include "port/runtime_diagnostics.h"
#include <atomic>
#include <cassert>
#include <cstring>
#include <iostream>
#include <thread>

int main() {
    std::atomic<bool> entered{false}, release{false};
    std::thread game([&] {
        PortDiagnosticStage(PORT_GAME_EVENTS);
        PortDiagnosticStage(PORT_GAME_TASKS);
        PortDiagnosticTask(1, true, 2, 4);
        entered.store(true, std::memory_order_release);
        while (!release.load(std::memory_order_acquire)) std::this_thread::yield();
        PortDiagnosticTaskDone();
        PortDiagnosticStage(PORT_GAME_IDLE);
    });
    while (!entered.load(std::memory_order_acquire)) std::this_thread::yield();
    char buffer[256];
    for (int i = 0; i < 10000; ++i) {
        PortFormatGameDiagnostics(buffer, sizeof(buffer));
        assert(strstr(buffer, "task=Transition task_mode=transition state=2 next=4"));
        assert(strstr(buffer, "game_stage=tasks game_tick=1"));
    }
    release.store(true, std::memory_order_release);
    game.join();
    PortFormatGameDiagnostics(buffer, sizeof(buffer));
    assert(strstr(buffer, "task=none") && strstr(buffer, "game_stage=idle"));
    char tiny[2] = {'x', 'x'};
    PortFormatGameDiagnostics(tiny, sizeof(tiny));
    assert(tiny[1] == '\0');
    PortFormatGameDiagnostics(nullptr, 0);
    std::cout << "Game task diagnostics remain readable during a blocked transition\n";
}
