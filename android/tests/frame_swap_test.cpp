// Exercise the real glx swap path with the real host VI limiter and a fake clock.
// Only GX/device calls are stubbed: a cinematic must not silently halve the cap.
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include "NL/glx/glxSwap.cpp"
#include "port/framerate.h"
#include "port/determinism.h"

static unsigned long long clockNs = 1000000000ull;
static unsigned callbacks;
static float requestedWait = 1.f;
extern "C" unsigned long long port_monotonic_ns(void) { return clockNs; }
extern "C" void port_sleep_ns(unsigned long long ns) { clockNs += ns; }
extern "C" void port_yield(void) { clockNs += 1000; }
extern "C" void PortBenchAddSleep(unsigned long long) { }
extern "C" void PortMorphWatchPoll(unsigned long) { }

nlVector4 glConstantGet(const char*) { return {requestedWait, 0, 0, 0}; }
extern "C" void GXWaitDrawDone(void) { }
extern "C" void VIFlush(void) { }
extern "C" void OSYieldThread(void) { }
static void onRetrace(u32 count) { assert(count == ++callbacks); }

int main(int argc, char** argv) {
    const int target = argc > 1 ? atoi(argv[1]) : 60;
    assert(target == 60 || target == 120);
    setenv("STRIKERS_FPS_LIMIT", target == 60 ? "60" : "120", 1);
    unsetenv("STRIKERS_FIXED_DT");
    VISetPostRetraceCallback(onRetrace);
    const unsigned long long period = 1000000000ull / target;
    // The production Hitz mode is the default in glxInitSwap.
    glx_SwapMode = GLSwap_Hitz;
    const float waits[] = {1.f, 2.f, 2.f, 2.f, 1.f, 0.5f, 0.f};
    for (float wait : waits) {
        requestedWait = wait;
        count0 = VIGetRetraceCount(); // as hitz_Post records after the previous frame
        const unsigned beforeCount = callbacks;
        const auto before = clockNs;
        hitz_Pre(true);
        assert(callbacks == beforeCount + 1);
        assert(clockNs - before >= period - 1000);
        assert(clockNs - before < period + 1000);
    }
    // Expensive effect frame: don't add a new frame's sleep after missing the budget.
    clockNs += 50000000;
    requestedWait = 2.f;
    count0 = VIGetRetraceCount();
    const auto before = clockNs;
    hitz_Pre(true);
    assert(clockNs == before);
    assert(callbacks == 8);
    assert(!PortFixedTimestep());
    printf("Real Android swap: gameplay/cinematic/effect pacing at %d FPS PASS\n", target);
}
