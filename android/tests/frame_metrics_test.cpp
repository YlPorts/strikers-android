#include "gfx/runtime_metrics.hpp"
#include <cassert>
#include <iostream>
#include <thread>
#include <vector>

int main() {
    using aurora::gfx::runtime_metrics::IntervalPeak;
    IntervalPeak peak;
    assert(peak.take() == 0);
    // A 33 ms effect survives a return to normal frames before the 10 s sample.
    for (unsigned sample : {16667, 33000, 16667, 15000}) peak.record(sample);
    assert(peak.take() == 33000);
    assert(peak.take() == 0);
    peak.record(1ull << 40);
    assert(peak.take() == UINT32_MAX);
    std::vector<std::thread> writers;
    for (unsigned i = 0; i < 4; ++i) writers.emplace_back([&, i] {
        for (unsigned n = 0; n < 10000; ++n) peak.record(n + 10000 * i);
    });
    for (auto& writer : writers) writer.join();
    assert(peak.take() == 39999);
    std::cout << "Transient frame peak retention, reset, saturation and concurrency PASS\n";
}
