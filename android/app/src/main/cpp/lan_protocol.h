#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace strikers::lan {

constexpr std::uint16_t kDefaultPort = 43821;
constexpr std::size_t kPacketSize = 24;
constexpr std::uint8_t kProtocolVersion = 1;

enum class MessageType : std::uint8_t {
    Hello = 1,
    Accept = 2,
    Input = 3,
};

struct PadInput {
    std::uint16_t buttons = 0;
    std::int8_t stickX = 0;
    std::int8_t stickY = 0;
    std::int8_t substickX = 0;
    std::int8_t substickY = 0;
    std::uint8_t triggerLeft = 0;
    std::uint8_t triggerRight = 0;
    std::uint8_t analogA = 0;
    std::uint8_t analogB = 0;
};

struct Packet {
    MessageType type = MessageType::Hello;
    std::uint32_t sequence = 0;
    std::uint32_t session = 0;
    PadInput input{};
};

constexpr void Put16(std::array<std::uint8_t, kPacketSize>& bytes,
                     std::size_t offset, std::uint16_t value) {
    bytes[offset] = static_cast<std::uint8_t>(value & 0xffu);
    bytes[offset + 1] = static_cast<std::uint8_t>((value >> 8) & 0xffu);
}

constexpr void Put32(std::array<std::uint8_t, kPacketSize>& bytes,
                     std::size_t offset, std::uint32_t value) {
    bytes[offset] = static_cast<std::uint8_t>(value & 0xffu);
    bytes[offset + 1] = static_cast<std::uint8_t>((value >> 8) & 0xffu);
    bytes[offset + 2] = static_cast<std::uint8_t>((value >> 16) & 0xffu);
    bytes[offset + 3] = static_cast<std::uint8_t>((value >> 24) & 0xffu);
}

constexpr std::uint16_t Get16(const std::uint8_t* bytes, std::size_t offset) {
    return static_cast<std::uint16_t>(bytes[offset]) |
           static_cast<std::uint16_t>(static_cast<std::uint16_t>(bytes[offset + 1]) << 8);
}

constexpr std::uint32_t Get32(const std::uint8_t* bytes, std::size_t offset) {
    return static_cast<std::uint32_t>(bytes[offset]) |
           (static_cast<std::uint32_t>(bytes[offset + 1]) << 8) |
           (static_cast<std::uint32_t>(bytes[offset + 2]) << 16) |
           (static_cast<std::uint32_t>(bytes[offset + 3]) << 24);
}

constexpr std::array<std::uint8_t, kPacketSize> Encode(const Packet& packet) {
    std::array<std::uint8_t, kPacketSize> bytes{};
    bytes[0] = 'S';
    bytes[1] = 'T';
    bytes[2] = 'L';
    bytes[3] = 'N';
    bytes[4] = kProtocolVersion;
    bytes[5] = static_cast<std::uint8_t>(packet.type);
    Put32(bytes, 6, packet.sequence);
    Put32(bytes, 10, packet.session);
    Put16(bytes, 14, packet.input.buttons);
    bytes[16] = static_cast<std::uint8_t>(packet.input.stickX);
    bytes[17] = static_cast<std::uint8_t>(packet.input.stickY);
    bytes[18] = static_cast<std::uint8_t>(packet.input.substickX);
    bytes[19] = static_cast<std::uint8_t>(packet.input.substickY);
    bytes[20] = packet.input.triggerLeft;
    bytes[21] = packet.input.triggerRight;
    bytes[22] = packet.input.analogA;
    bytes[23] = packet.input.analogB;
    return bytes;
}

constexpr bool Decode(const std::uint8_t* bytes, std::size_t size, Packet& packet) {
    if (bytes == nullptr || size != kPacketSize || bytes[0] != 'S' || bytes[1] != 'T' ||
        bytes[2] != 'L' || bytes[3] != 'N' || bytes[4] != kProtocolVersion ||
        bytes[5] < static_cast<std::uint8_t>(MessageType::Hello) ||
        bytes[5] > static_cast<std::uint8_t>(MessageType::Input)) {
        return false;
    }

    packet.type = static_cast<MessageType>(bytes[5]);
    packet.sequence = Get32(bytes, 6);
    packet.session = Get32(bytes, 10);
    packet.input.buttons = Get16(bytes, 14);
    packet.input.stickX = static_cast<std::int8_t>(bytes[16]);
    packet.input.stickY = static_cast<std::int8_t>(bytes[17]);
    packet.input.substickX = static_cast<std::int8_t>(bytes[18]);
    packet.input.substickY = static_cast<std::int8_t>(bytes[19]);
    packet.input.triggerLeft = bytes[20];
    packet.input.triggerRight = bytes[21];
    packet.input.analogA = bytes[22];
    packet.input.analogB = bytes[23];
    return true;
}

// Sequence comparison remains valid across the uint32 wrap boundary as long as
// senders do not skip half the sequence space between successive input packets.
constexpr bool IsNewerSequence(std::uint32_t candidate, std::uint32_t previous) {
    return static_cast<std::int32_t>(candidate - previous) > 0;
}

}  // namespace strikers::lan
