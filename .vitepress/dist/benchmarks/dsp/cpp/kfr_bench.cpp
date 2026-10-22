// The fft_cpu.sc and dsp_cpu.sc workloads with KFR (double, then float
// precision for the rows tagged c64 / f32;
// one thread; built with KFR_ENABLE_MULTIARCH, so it dispatches to the
// CPU's best ISA at run time). Plans and filters are built once outside the
// timing; the mean of a few calls after one warm-up call is printed.
//   build: see run_bench.py (clang++ -std=c++20 -O2, KFR's static libraries)
#include <kfr/dft.hpp>
#include <kfr/dsp.hpp>
#include <chrono>
#include <cstdio>
#include <random>

using namespace kfr;

template <typename F>
static double bench(F&& f, int iters = 5) {
    f();
    auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < iters; i++) f();
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / iters;
}

template <typename T>
static univector<T> rnd(size_t n) {
    std::mt19937_64 g(42);
    std::uniform_real_distribution<double> d(-1.0, 1.0);
    univector<T> v(n);
    for (auto& x : v) x = T(d(g));
    return v;
}

// T = double: tags "c128" / ""; T = float: "c64" / " f32".
template <typename T>
static void run_all(const char* C, const char* S) {
    // ── FFT ──
    {
        size_t n = 1 << 20;
        univector<complex<T>> x(n), y(n);
        auto r = rnd<T>(2 * n);
        for (size_t i = 0; i < n; i++) x[i] = complex<T>(r[2 * i], r[2 * i + 1]);
        dft_plan<T> plan(n);
        univector<u8> temp(plan.temp_size);
        printf("[fft1d n=1048576 %s cpu] ms_per_call=%.3f\n", C, bench([&] { plan.execute(y, x, temp); }));
    }
    {
        size_t side = 1024;
        univector<complex<T>> x(side * side), y(side * side);
        auto r = rnd<T>(2 * side * side);
        for (size_t i = 0; i < side * side; i++) x[i] = complex<T>(r[2 * i], r[2 * i + 1]);
        dft_plan_md<T> plan(shape<dynamic_shape>{ side, side });
        univector<u8> temp(plan.temp_size);
        printf("[fft2d 1024x1024 %s cpu] ms_per_call=%.3f\n", C,
               bench([&] { plan.execute(y.data(), x.data(), temp.data(), false); }));
    }
    {
        size_t len = 4096, rows = 1024;
        univector<complex<T>> x(len * rows), y(len * rows);
        auto r = rnd<T>(2 * len * rows);
        for (size_t i = 0; i < len * rows; i++) x[i] = complex<T>(r[2 * i], r[2 * i + 1]);
        dft_plan<T> plan(len);
        univector<u8> temp(plan.temp_size);
        printf("[batch 1024x4096 c2c%s cpu] ms_per_call=%.3f\n", S, bench([&] {
                   for (size_t k = 0; k < rows; k++) plan.execute(&y[k * len], &x[k * len], temp.data());
               }));
        auto re = rnd<T>(len * rows);
        dft_plan_real<T> rplan(len);
        univector<u8> rtemp(rplan.temp_size);
        univector<complex<T>> out((len / 2 + 1) * rows);
        printf("[rfft 1024x4096 r2c%s cpu] ms_per_call=%.3f\n", S, bench([&] {
                   for (size_t k = 0; k < rows; k++)
                       rplan.execute(&out[k * (len / 2 + 1)], &re[k * len], rtemp.data());
               }));
    }
    // ── DSP ──
    size_t n = 1 << 20;
    auto x = rnd<T>(n);
    univector<T> y(n);
    {
        auto h = rnd<T>(127);
        fir_filter<T> f(h);
        printf("[fir stream n=1048576 taps=127%s] ms_per_call=%.3f\n", S, bench([&] { f.reset(); f.apply(y, x); }));
    }
    {
        auto k = rnd<T>(4096);
        convolve_filter<T> f(k, 4096);
        printf("[fftconv n=1048576 taps=4096%s] ms_per_call=%.3f\n", S, bench([&] { f.reset(); f.apply(y, x); }));
    }
    {
        biquad_section<T> bq = biquad_lowpass<T>(T(1000.0 / 48000.0), T(0.7071));
        iir_filter<T> f(iir_params<T>{ bq });
        printf("[biquad n=1048576%s] ms_per_call=%.3f\n", S, bench([&] { f.reset(); f.apply(y, x); }));
    }
    {
        printf("[resample n=1048576 44100->48000%s] ms_per_call=%.3f\n", S, bench([&] {
                   auto c = sample_rate_converter<T>(sample_rate_conversion_quality::normal, 160, 147);
                   univector<T> o(c.output_size_for_input(n));
                   c.process(o, x);
               }));
    }
    {
        printf("[resample draft n=1048576 44100->48000%s] ms_per_call=%.3f\n", S, bench([&] {
                   auto c = sample_rate_converter<T>(sample_rate_conversion_quality::draft, 160, 147);
                   univector<T> o(c.output_size_for_input(n));
                   c.process(o, x);
               }));
    }
}

int main() {
    run_all<double>("c128", "");
    run_all<float>("c64", " f32");
    printf("kfr %s\n", library_version());
    return 0;
}
