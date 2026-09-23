#include "../app/src/main/cpp/lan_protocol.h"

#include <cassert>
#include <cstdint>

using namespace strikers::lan;

int main() {
    Packet source{};
    source.type = MessageType::Input;
    source.sequence = 0xfedcba98u;
    source.session = 0x12345678u;
    source.input = {0xa55au, -127, 126, -45, 44, 255, 127, 128, 42};

    const auto bytes = Encode(source);
    Packet decoded{};
    assert(Decode(bytes.data(), bytes.size(), decoded));
    assert(decoded.type == source.type);
    assert(decoded.sequence == source.sequence);
    assert(decoded.session == source.session);
    assert(decoded.input.buttons == source.input.buttons);
    assert(decoded.input.stickX == source.input.stickX);
    assert(decoded.input.stickY == source.input.stickY);
    assert(decoded.input.substickX == source.input.substickX);
    assert(decoded.input.substickY == source.input.substickY);
    assert(decoded.input.triggerLeft == source.input.triggerLeft);
    assert(decoded.input.triggerRight == source.input.triggerRight);
    assert(decoded.input.analogA == source.input.analogA);
    assert(decoded.input.analogB == source.input.analogB);

    assert(!Decode(bytes.data(), bytes.size() - 1, decoded));
    auto badMagic = bytes;
    badMagic[0] = 'X';
    assert(!Decode(badMagic.data(), badMagic.size(), decoded));
    auto badVersion = bytes;
    badVersion[4] = static_cast<std::uint8_t>(kProtocolVersion + 1);
    assert(!Decode(badVersion.data(), badVersion.size(), decoded));
    auto badType = bytes;
    badType[5] = 0xff;
    assert(!Decode(badType.data(), badType.size(), decoded));

    assert(IsNewerSequence(2, 1));
    assert(!IsNewerSequence(1, 2));
    assert(IsNewerSequence(0, 0xffffffffu));
    assert(!IsNewerSequence(0x80000000u, 0));
    return 0;
}
