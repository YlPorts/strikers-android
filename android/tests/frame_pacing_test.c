// Link the actual VI implementation to a deterministic host clock. No GPU needed.
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

#include "port/framerate.h"

static unsigned long long clock_ns = 1000000000ull;
static unsigned long long slept_ns;
static unsigned int callbacks;

unsigned long long port_monotonic_ns(void) { return clock_ns; }
void port_sleep_ns(unsigned long long ns) { clock_ns += ns; }
void port_yield(void) { clock_ns += 1000; }
void PortBenchAddSleep(unsigned long long ns) { slept_ns += ns; }
void PortMorphWatchPoll(unsigned long frame) { (void)frame; }

extern void VIWaitForRetrace(void);
extern int PortFixedTimestep(void);
typedef void (*RetraceCallback)(unsigned int);
extern RetraceCallback VISetPostRetraceCallback(RetraceCallback callback);

static void on_retrace(unsigned int count)
{
    callbacks++;
    assert(count == callbacks);
}

int main(int argc, char** argv)
{
    const int target = argc > 1 && atoi(argv[1]) == 120 ? 120 : 60;
    const unsigned long long period = 1000000000ull / target;
    setenv("STRIKERS_FPS_LIMIT", target == 120 ? "120" : "60", 1);
    unsetenv("STRIKERS_FIXED_DT");
    VISetPostRetraceCallback(on_retrace);
    assert(!PortFixedTimestep());

    const double displays[] = {0.0, 59.94, 60.0, 90.0, 120.0, 144.0};
    for (unsigned int i = 0; i < sizeof(displays) / sizeof(displays[0]); ++i) {
        double limit;
        PortSetDisplayRefresh(displays[i], 1);
        PortFrameLimitInfo(&limit, NULL, NULL, NULL);
        assert(limit > target - 0.01 && limit < target + 0.01);
    }

    unsigned long long before = clock_ns;
    VIWaitForRetrace();
    assert(clock_ns - before >= period);
    assert(clock_ns - before < period + 1000);

    // A normal frame with 5 ms of work sleeps only the remaining budget.
    clock_ns += 5000000;
    before = clock_ns;
    VIWaitForRetrace();
    assert(clock_ns - before >= period - 5000000 - 1000);
    assert(clock_ns - before < period - 5000000 + 1000);

    // A 50 ms hitch is not followed by several unpaced catch-up frames.
    clock_ns += 50000000;
    before = clock_ns;
    VIWaitForRetrace();
    assert(clock_ns == before); // no extra full wait on the already slow frame
    for (int i = 0; i < 8; ++i) {
        before = clock_ns;
        VIWaitForRetrace();
        assert(clock_ns - before >= period - 1000);
        assert(clock_ns - before < period + 1000);
    }

    // Returning after a long suspension rebases, with no burst and no lost input.
    clock_ns += 30000000000ull;
    before = clock_ns;
    VIWaitForRetrace();
    assert(clock_ns == before);
    VIWaitForRetrace();
    assert(clock_ns - before >= period);
    assert(slept_ns > 0);
    assert(callbacks == 13);
    assert(!PortFixedTimestep());
    printf("Android frame pacing at %d FPS: PASS (six display rates, hitch, resume, callbacks)\n", target);
    return 0;
}
