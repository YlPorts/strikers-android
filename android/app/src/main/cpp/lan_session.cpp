#include "lan_protocol.h"

#include <jni.h>

#include <arpa/inet.h>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <poll.h>
#include <string>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

#include <dolphin/pad.h>

namespace {

using Clock = std::chrono::steady_clock;
using strikers::lan::MessageType;
using strikers::lan::Packet;
using strikers::lan::PadInput;

constexpr int kRoleHost = 1;
constexpr int kRoleClient = 2;
constexpr auto kConnectionTimeout = std::chrono::milliseconds(1500);
constexpr auto kHelloInterval = std::chrono::milliseconds(400);
constexpr auto kPeerReleaseDelay = std::chrono::seconds(5);

struct Session {
    std::mutex lifecycleMutex;
    std::mutex endpointMutex;
    std::mutex remoteMutex;
    std::thread receiver;
    std::atomic<bool> stopping{false};
    std::atomic<bool> active{false};
    std::atomic<bool> connected{false};
    std::atomic<bool> everConnected{false};
    std::atomic<int> role{0};
    std::atomic<int> socketFd{-1};
    std::atomic<std::uint32_t> sessionId{0};
    std::atomic<std::uint32_t> sendSequence{1};
    std::atomic<std::uint32_t> lastRemoteSequence{0};
    std::atomic<bool> haveRemoteSequence{false};
    sockaddr_in peer{};
    sockaddr_in server{};
    bool peerKnown = false;
    PadInput remoteInput{};
    Clock::time_point lastReceive{};
    Clock::time_point peerUnavailableSince{};
};

Session g_session;

bool SameEndpoint(const sockaddr_in& a, const sockaddr_in& b) {
    return a.sin_family == b.sin_family && a.sin_port == b.sin_port &&
           a.sin_addr.s_addr == b.sin_addr.s_addr;
}

std::uint32_t MakeSessionId() {
    const auto ticks = static_cast<std::uint64_t>(Clock::now().time_since_epoch().count());
    std::uint32_t value = static_cast<std::uint32_t>(ticks ^ (ticks >> 32) ^
                                                      static_cast<std::uint64_t>(getpid()));
    return value == 0 ? 1 : value;
}

bool SendPacket(int fd, const sockaddr_in& destination, const Packet& packet) {
    const auto bytes = strikers::lan::Encode(packet);
    const auto sent = sendto(fd, bytes.data(), bytes.size(), MSG_DONTWAIT,
                             reinterpret_cast<const sockaddr*>(&destination), sizeof(destination));
    return sent == static_cast<ssize_t>(bytes.size());
}

void SetPeer(const sockaddr_in& peer) {
    std::lock_guard<std::mutex> lock(g_session.endpointMutex);
    g_session.peer = peer;
    g_session.peerKnown = true;
}

bool GetPeer(sockaddr_in& peer) {
    std::lock_guard<std::mutex> lock(g_session.endpointMutex);
    if (!g_session.peerKnown) return false;
    peer = g_session.peer;
    return true;
}

void ClearPeerIfExpired(Clock::time_point now) {
    if (g_session.role.load(std::memory_order_relaxed) != kRoleHost ||
        g_session.connected.load(std::memory_order_relaxed)) {
        return;
    }

    bool expired = false;
    {
        std::lock_guard<std::mutex> lock(g_session.endpointMutex);
        expired = g_session.peerKnown && now - g_session.peerUnavailableSince > kPeerReleaseDelay;
        if (expired) g_session.peerKnown = false;
    }
    if (expired) {
        g_session.sessionId.store(MakeSessionId(), std::memory_order_relaxed);
        g_session.haveRemoteSequence.store(false, std::memory_order_relaxed);
    }
}

void SetRemoteInput(const PadInput& input, std::uint32_t sequence, Clock::time_point now) {
    const bool havePrevious = g_session.haveRemoteSequence.load(std::memory_order_relaxed);
    const std::uint32_t previous = g_session.lastRemoteSequence.load(std::memory_order_relaxed);
    if (havePrevious && !strikers::lan::IsNewerSequence(sequence, previous)) return;

    {
        std::lock_guard<std::mutex> lock(g_session.remoteMutex);
        g_session.remoteInput = input;
    }
    g_session.lastRemoteSequence.store(sequence, std::memory_order_relaxed);
    g_session.haveRemoteSequence.store(true, std::memory_order_relaxed);
    g_session.lastReceive = now;
    g_session.peerUnavailableSince = now;
    g_session.everConnected.store(true, std::memory_order_relaxed);
    g_session.connected.store(true, std::memory_order_relaxed);
}

PadInput NeutralInput() { return {}; }

void SetRemoteNeutral() {
    std::lock_guard<std::mutex> lock(g_session.remoteMutex);
    g_session.remoteInput = NeutralInput();
}

bool GetRemoteInput(PadInput& input) {
    if (!g_session.active.load(std::memory_order_relaxed) ||
        !g_session.connected.load(std::memory_order_relaxed)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(g_session.remoteMutex);
    input = g_session.remoteInput;
    return true;
}

void SendAccept(int fd, const sockaddr_in& peer) {
    Packet packet{};
    packet.type = MessageType::Accept;
    packet.sequence = g_session.sendSequence.fetch_add(1, std::memory_order_relaxed);
    packet.session = g_session.sessionId.load(std::memory_order_relaxed);
    SendPacket(fd, peer, packet);
}

void HandlePacket(const Packet& packet, const sockaddr_in& source) {
    const int role = g_session.role.load(std::memory_order_relaxed);
    const int fd = g_session.socketFd.load(std::memory_order_relaxed);
    if (fd < 0) return;
    const auto now = Clock::now();

    if (role == kRoleHost) {
        if (packet.type == MessageType::Hello) {
            sockaddr_in currentPeer{};
            const bool alreadyHasPeer = GetPeer(currentPeer);
            if (alreadyHasPeer && !SameEndpoint(currentPeer, source)) return;
            if (!alreadyHasPeer) {
                SetPeer(source);
                g_session.peerUnavailableSince = now;
            }
            SendAccept(fd, source);
            return;
        }

        sockaddr_in currentPeer{};
        if (!GetPeer(currentPeer) || !SameEndpoint(currentPeer, source) ||
            packet.type != MessageType::Input ||
            packet.session != g_session.sessionId.load(std::memory_order_relaxed)) {
            return;
        }
        SetRemoteInput(packet.input, packet.sequence, now);
        return;
    }

    sockaddr_in server{};
    {
        std::lock_guard<std::mutex> lock(g_session.endpointMutex);
        server = g_session.server;
    }
    if (!SameEndpoint(server, source)) return;

    if (packet.type == MessageType::Accept && packet.session != 0) {
        const std::uint32_t previous = g_session.sessionId.load(std::memory_order_relaxed);
        if (previous != packet.session) {
            g_session.sessionId.store(packet.session, std::memory_order_relaxed);
            g_session.haveRemoteSequence.store(false, std::memory_order_relaxed);
        }
        g_session.lastReceive = now;
        g_session.peerUnavailableSince = now;
        g_session.everConnected.store(true, std::memory_order_relaxed);
        g_session.connected.store(true, std::memory_order_relaxed);
        return;
    }

    if (packet.type == MessageType::Input &&
        packet.session == g_session.sessionId.load(std::memory_order_relaxed)) {
        SetRemoteInput(packet.input, packet.sequence, now);
    }
}

void ReceiverLoop() {
    Clock::time_point lastHello{};
    bool sentHello = false;
    std::uint8_t buffer[strikers::lan::kPacketSize + 1]{};

    while (!g_session.stopping.load(std::memory_order_relaxed)) {
        const int fd = g_session.socketFd.load(std::memory_order_relaxed);
        if (fd < 0) break;

        pollfd descriptor{};
        descriptor.fd = fd;
        descriptor.events = POLLIN;
        const int ready = poll(&descriptor, 1, 50);
        if (ready > 0 && (descriptor.revents & POLLIN) != 0) {
            for (;;) {
                sockaddr_in source{};
                socklen_t sourceLength = sizeof(source);
                const auto received = recvfrom(fd, buffer, sizeof(buffer), MSG_DONTWAIT,
                                               reinterpret_cast<sockaddr*>(&source), &sourceLength);
                if (received < 0) {
                    if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) break;
                    break;
                }

                Packet packet{};
                if (strikers::lan::Decode(buffer, static_cast<std::size_t>(received), packet)) {
                    HandlePacket(packet, source);
                }
            }
        }

        const auto now = Clock::now();
        if (g_session.role.load(std::memory_order_relaxed) == kRoleClient &&
            (!g_session.connected.load(std::memory_order_relaxed) ||
             now - g_session.lastReceive > kConnectionTimeout) &&
            (!sentHello || now - lastHello > kHelloInterval)) {
            sockaddr_in server{};
            {
                std::lock_guard<std::mutex> lock(g_session.endpointMutex);
                server = g_session.server;
            }
            Packet hello{};
            hello.type = MessageType::Hello;
            hello.sequence = g_session.sendSequence.fetch_add(1, std::memory_order_relaxed);
            SendPacket(fd, server, hello);
            lastHello = now;
            sentHello = true;
        }

        if (g_session.connected.load(std::memory_order_relaxed) &&
            now - g_session.lastReceive > kConnectionTimeout) {
            g_session.connected.store(false, std::memory_order_relaxed);
            g_session.peerUnavailableSince = now;
            SetRemoteNeutral();
        }
        ClearPeerIfExpired(now);
    }
}

bool Start(int role, const char* ip, int port) {
    if ((role != kRoleHost && role != kRoleClient) || port < 1 || port > 65535) return false;
    std::lock_guard<std::mutex> lifecycle(g_session.lifecycleMutex);
    if (g_session.active.load(std::memory_order_relaxed)) return false;

    const int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return false;

    sockaddr_in bindAddress{};
    bindAddress.sin_family = AF_INET;
    bindAddress.sin_addr.s_addr = htonl(INADDR_ANY);
    bindAddress.sin_port = htons(static_cast<std::uint16_t>(port));
    if (role == kRoleHost && bind(fd, reinterpret_cast<const sockaddr*>(&bindAddress),
                                  sizeof(bindAddress)) != 0) {
        close(fd);
        return false;
    }

    g_session.stopping.store(false, std::memory_order_relaxed);
    g_session.connected.store(false, std::memory_order_relaxed);
    g_session.everConnected.store(false, std::memory_order_relaxed);
    g_session.role.store(role, std::memory_order_relaxed);
    g_session.sessionId.store(role == kRoleHost ? MakeSessionId() : 0, std::memory_order_relaxed);
    g_session.sendSequence.store(1, std::memory_order_relaxed);
    g_session.lastRemoteSequence.store(0, std::memory_order_relaxed);
    g_session.haveRemoteSequence.store(false, std::memory_order_relaxed);
    g_session.lastReceive = Clock::time_point{};
    g_session.peerUnavailableSince = Clock::now();
    {
        std::lock_guard<std::mutex> endpointLock(g_session.endpointMutex);
        g_session.peerKnown = false;
        g_session.peer = {};
        g_session.server = {};
        if (role == kRoleClient) {
            if (ip == nullptr || inet_pton(AF_INET, ip, &g_session.server.sin_addr) != 1) {
                close(fd);
                return false;
            }
            g_session.server.sin_family = AF_INET;
            g_session.server.sin_port = htons(static_cast<std::uint16_t>(port));
        }
    }
    SetRemoteNeutral();
    g_session.socketFd.store(fd, std::memory_order_relaxed);
    g_session.active.store(true, std::memory_order_relaxed);
    try {
        g_session.receiver = std::thread(ReceiverLoop);
    } catch (...) {
        g_session.active.store(false, std::memory_order_relaxed);
        g_session.socketFd.store(-1, std::memory_order_relaxed);
        close(fd);
        return false;
    }
    return true;
}

void Stop() {
    std::lock_guard<std::mutex> lifecycle(g_session.lifecycleMutex);
    if (!g_session.active.exchange(false, std::memory_order_relaxed)) return;
    g_session.stopping.store(true, std::memory_order_relaxed);
    const int fd = g_session.socketFd.load(std::memory_order_relaxed);
    if (fd >= 0) shutdown(fd, SHUT_RDWR);
    if (g_session.receiver.joinable()) g_session.receiver.join();
    if (fd >= 0) close(fd);
    g_session.socketFd.store(-1, std::memory_order_relaxed);
    g_session.connected.store(false, std::memory_order_relaxed);
    g_session.role.store(0, std::memory_order_relaxed);
    g_session.haveRemoteSequence.store(false, std::memory_order_relaxed);
    SetRemoteNeutral();
}

PadInput ReadPadInput(const PADStatus& pad) {
    return {pad.button, pad.stickX, pad.stickY, pad.substickX, pad.substickY,
            pad.triggerLeft, pad.triggerRight, pad.analogA, pad.analogB};
}

PADStatus MakePadStatus(const PadInput& input) {
    PADStatus status{};
    status.button = input.buttons;
    status.stickX = input.stickX;
    status.stickY = input.stickY;
    status.substickX = input.substickX;
    status.substickY = input.substickY;
    status.triggerLeft = input.triggerLeft;
    status.triggerRight = input.triggerRight;
    status.analogA = input.analogA;
    status.analogB = input.analogB;
    status.err = PAD_ERR_NONE;
    return status;
}

void SendLocalInput(const PADStatus& local) {
    const int role = g_session.role.load(std::memory_order_relaxed);
    if (!g_session.active.load(std::memory_order_relaxed) ||
        !g_session.connected.load(std::memory_order_relaxed)) return;

    sockaddr_in peer{};
    if (role == kRoleHost) {
        if (!GetPeer(peer)) return;
    } else if (role == kRoleClient) {
        std::lock_guard<std::mutex> lock(g_session.endpointMutex);
        peer = g_session.server;
    } else {
        return;
    }

    Packet packet{};
    packet.type = MessageType::Input;
    packet.sequence = g_session.sendSequence.fetch_add(1, std::memory_order_relaxed);
    packet.session = g_session.sessionId.load(std::memory_order_relaxed);
    packet.input = ReadPadInput(local);
    SendPacket(g_session.socketFd.load(std::memory_order_relaxed), peer, packet);
}

std::string Status() {
    if (!g_session.active.load(std::memory_order_relaxed)) return "LAN: desactivado";
    const int role = g_session.role.load(std::memory_order_relaxed);
    if (g_session.connected.load(std::memory_order_relaxed)) {
        return role == kRoleHost ? "LAN: rival conectado (P2)" : "LAN: conectado (P1 anfitrión)";
    }
    if (g_session.everConnected.load(std::memory_order_relaxed)) return "LAN: conexión perdida; intentando volver";
    return role == kRoleHost ? "LAN: sala abierta; esperando rival" : "LAN: buscando anfitrión";
}

std::string JString(JNIEnv* env, jstring value) {
    if (env == nullptr || value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

// Called immediately after PADRead on the game thread. Only the non-blocking
// send path runs here; receive state is copied under a short mutex.
extern "C" void PortAndroidLanProcessPads(PADStatus* pads) {
    if (pads == nullptr || !g_session.active.load(std::memory_order_relaxed)) return;

    const PADStatus local = pads[0];
    SendLocalInput(local);

    PadInput remote{};
    const bool hasRemoteInput = GetRemoteInput(remote);
    const int role = g_session.role.load(std::memory_order_relaxed);
    if (role == kRoleHost) {
        pads[1] = hasRemoteInput ? MakePadStatus(remote) : PADStatus{};
        if (!hasRemoteInput) pads[1].err = PAD_ERR_NO_CONTROLLER;
    } else if (role == kRoleClient) {
        // Keep controller ownership fixed when connecting or reconnecting: the host
        // is always player 1 and the joining phone is always player 2.
        pads[0] = hasRemoteInput ? MakePadStatus(remote) : PADStatus{};
        if (!hasRemoteInput) pads[0].err = PAD_ERR_NO_CONTROLLER;
        pads[1] = local;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ylports_strikers_StrikersActivity_nativeStartLan(
        JNIEnv* env, jclass, jint role, jstring host, jint port) {
    const std::string address = JString(env, host);
    return Start(role, address.empty() ? nullptr : address.c_str(), port) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ylports_strikers_StrikersActivity_nativeStopLan(JNIEnv*, jclass) {
    Stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ylports_strikers_StrikersActivity_nativeLanStatus(JNIEnv* env, jclass) {
    const std::string status = Status();
    return env->NewStringUTF(status.c_str());
}
