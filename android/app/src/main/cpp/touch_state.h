#pragma once

#include <cstdint>

namespace strikers::touch {
struct State {
    int buttons, stickX, stickY, substickX, substickY, triggerLeft, triggerRight;
};

constexpr int Clamp(int value, int low, int high) {
    return value < low ? low : (value > high ? high : value);
}

// One ARM64 atomic contains the entire PAD update (16 + 4*8 + 2*8 bits).
// Separate atomics can mix axes/buttons from two different MotionEvents.
constexpr std::uint64_t Pack(State value) {
    return (static_cast<std::uint64_t>(value.buttons) & 0xffffu)
        | ((static_cast<std::uint64_t>(Clamp(value.stickX, -127, 127)) & 0xffu) << 16)
        | ((static_cast<std::uint64_t>(Clamp(value.stickY, -127, 127)) & 0xffu) << 24)
        | ((static_cast<std::uint64_t>(Clamp(value.substickX, -127, 127)) & 0xffu) << 32)
        | ((static_cast<std::uint64_t>(Clamp(value.substickY, -127, 127)) & 0xffu) << 40)
        | (static_cast<std::uint64_t>(Clamp(value.triggerLeft, 0, 255)) << 48)
        | (static_cast<std::uint64_t>(Clamp(value.triggerRight, 0, 255)) << 56);
}

constexpr int Axis(std::uint64_t value, unsigned shift) {
    const int byte = static_cast<int>((value >> shift) & 0xffu);
    return byte > 127 ? byte - 256 : byte;
}

constexpr State Unpack(std::uint64_t value) {
    return {static_cast<int>(value & 0xffffu), Axis(value, 16), Axis(value, 24),
            Axis(value, 32), Axis(value, 40), static_cast<int>((value >> 48) & 0xffu),
            static_cast<int>((value >> 56) & 0xffu)};
}
} // namespace strikers::touch
