// The dsp_cpu.sc biquad workload (1,048,576 samples through one low-pass
// section, fs 48 kHz, f0 1 kHz, Q 0.7071) with two float32 audio libraries,
// each used the way its documentation shows:
//   Q            q::lowpass, one sample per call
//   madronalib   ml::Lopass (state-variable form), 64-sample SignalBlocks
// Mean of a few calls after one warm-up call.
#include <q/fx/biquad.hpp>
#include <MLDSPFilters.h>
#include <chrono>
#include <cstdio>
#include <random>
#include <vector>

template <typename F>
static double bench(F&& f, int iters = 5) {
    f();
    auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < iters; i++) f();
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / iters;
}

int main() {
    const size_t n = 1 << 20;
    std::vector<float> x(n), y(n);
    std::mt19937_64 g(42);
    std::uniform_real_distribution<float> d(-1.f, 1.f);
    for (auto& v : x) v = d(g);
    using namespace cycfi::q::literals;
    {
        double ms = bench([&] {
            cycfi::q::lowpass f(1000_Hz, 48000.f, 0.7071);
            for (size_t i = 0; i < n; i++) y[i] = f(x[i]);
        });
        double s = 0; for (auto v : y) s += v;
        printf("[biquad q n=1048576 f32] ms_per_call=%.3f checksum=%.6f\n", ms, s);
    }
    {
        double ms = bench([&] {
            ml::Lopass<float> f(1000.f / 48000.f, 1.f / 0.7071f);
            std::array<float, 2> params{1000.f / 48000.f, 1.f / 0.7071f};
            for (size_t b = 0; b + ml::kFramesPerBlock <= n; b += ml::kFramesPerBlock) {
                ml::SignalBlock in;
                ml::load(in, &x[b]);
                ml::SignalBlock out = f(in, params);
                ml::store(out, &y[b]);
            }
        });
        double s = 0; for (auto v : y) s += v;
        printf("[biquad madronalib n=1048576 f32] ms_per_call=%.3f checksum=%.6f\n", ms, s);
    }
    return 0;
}
