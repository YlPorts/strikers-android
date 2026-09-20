#include "../app/src/main/cpp/touch_state.h"
#include <atomic>
#include <cassert>
#include <climits>
#include <thread>

using namespace strikers::touch;

int main() {
    assert(Pack({0, 0, 0, 0, 0, 0, 0}) == 0);
    for (int axis = -127; axis <= 127; ++axis) {
        const State value = Unpack(Pack({0x1f7f, axis, -axis, axis, -axis, 180, 255}));
        assert(value.buttons == 0x1f7f && value.stickX == axis && value.stickY == -axis);
        assert(value.substickX == axis && value.substickY == -axis);
        assert(value.triggerLeft == 180 && value.triggerRight == 255);
    }
    const State limited = Unpack(Pack({-1, INT_MIN, INT_MAX, -128, 128, -1, INT_MAX}));
    assert(limited.buttons == 65535 && limited.stickX == -127 && limited.stickY == 127);
    assert(limited.substickX == -127 && limited.substickY == 127);
    assert(limited.triggerLeft == 0 && limited.triggerRight == 255);

    const auto first = Pack({0x100, 127, -127, 30, -30, 180, 0});
    const auto second = Pack({0x240, -80, 80, -127, 127, 0, 255});
    std::atomic<std::uint64_t> shared{0};
    std::atomic<bool> done{false};
    std::thread writer([&] {
        for (int i = 0; i < 1000000; ++i) {
            shared.store(first, std::memory_order_relaxed);
            shared.store(second, std::memory_order_relaxed);
            shared.store(0, std::memory_order_relaxed); // cancellation / focus loss
        }
        done.store(true);
    });
    do {
        const auto snapshot = shared.load(std::memory_order_relaxed);
        assert(snapshot == 0 || snapshot == first || snapshot == second);
        assert(Pack(Unpack(snapshot)) == snapshot);
    } while (!done.load());
    writer.join();
}
