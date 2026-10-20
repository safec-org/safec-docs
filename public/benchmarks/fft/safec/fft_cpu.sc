// FFT on the CPU with std/dsp/fft.h and imaging.h (radix-2/4, on
// std/sync/parallel.h's thread pool), in double precision and then in
// float (the _f32 functions, rows tagged c64 / f32). Same transforms as
// fft_cuda.sc and
// ../pytorch/fft.py:
//   fft1d   one complex transform of length 2^20
//   fft2d   one 1024 x 1024 complex 2D transform
//   batch   1024 complex transforms of length 4096 (fft_batch; the GPU
//           rows run these in fp32)
//   rfft    1024 real transforms of length 4096 (fft_real_half_batch: the
//           n/2 + 1 bins per row, as FFTW, KFR and PyTorch return them)
// Prints the mean time per call, after one warm-up call of each (which
// starts the thread pool and builds the twiddle table; ../pytorch/fft.py
// warms up too).
//
// Build: safec fft_cpu.sc -I <SafeC> -O2 -o fft_cpu.ll
//        clang -O2 fft_cpu.ll libsafec_std.a -lm -lpthread -o fft_cpu
extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
#include <std/dsp/fft.h>
#include <std/dsp/imaging.h>
#include <std/dsp/complex_dsp.h>
#include <std/mem.h>
#include <std/panic.h>
#include <std/time.h>

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

static &heap Complex32 random_complex32(unsigned long n) {
    ?&heap Complex32 q = std::alloc(n * sizeof(struct Complex32));
    match (q) {
        case some(p): {
            unsafe {
                unsigned long i = 0UL;
                while (i < n) { p[i].re = (float)(drand48() * 2.0 - 1.0); p[i].im = (float)(drand48() * 2.0 - 1.0); i = i + 1UL; }
            }
            return p;
        }
        case null: { std::panic_at("random_complex32: out of memory", "fft_cpu.sc", 0); }
    }
    std::panic_at("random_complex32: unreachable", "fft_cpu.sc", 0);
}

static &heap Complex random_complex(unsigned long n) {
    ?&heap Complex q = std::alloc(n * sizeof(struct Complex));
    match (q) {
        case some(p): {
            unsafe {
                unsigned long i = 0UL;
                while (i < n) { p[i].re = drand48() * 2.0 - 1.0; p[i].im = drand48() * 2.0 - 1.0; i = i + 1UL; }
            }
            return p;
        }
        case null: { std::panic_at("random_complex: out of memory", "fft_cpu.sc", 0); }
    }
    std::panic_at("random_complex: unreachable", "fft_cpu.sc", 0);
}

int main() {
    unsafe { srand48(42L); }
    unsigned long n = 1048576UL;
    &heap Complex x = random_complex(n);
    int iters = 3;
    std::fft(x, n);
    double t0 = now_ms();
    int it = 0;
    while (it < iters) { std::fft(x, n); it = it + 1; }
    double ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[fft1d n=1048576 c128 cpu] ms_per_call=%.3f\n", ms); }
    unsafe { std::dealloc(x); }

    unsigned long side = 1024UL;
    &heap Complex img = random_complex(side * side);
    std::fft2d(img, side, side);
    t0 = now_ms();
    it = 0;
    while (it < iters) { std::fft2d(img, side, side); it = it + 1; }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[fft2d 1024x1024 c128 cpu] ms_per_call=%.3f\n", ms); }
    unsafe { std::dealloc(img); }

    unsigned long rows = 1024UL;
    unsigned long len = 4096UL;
    &heap Complex b = random_complex(rows * len);
    std::fft_batch(b, len, rows, 0);
    t0 = now_ms();
    it = 0;
    while (it < iters) {
        std::fft_batch(b, len, rows, 0);
        it = it + 1;
    }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[batch 1024x4096 c2c cpu] ms_per_call=%.3f\n", ms); }

    ?&heap double reh = std::alloc(rows * len * sizeof(double));
    double* re;
    unsafe {
        re = (double*)reh;
        unsigned long i = 0UL;
        while (i < rows * len) { re[i] = drand48() * 2.0 - 1.0; i = i + 1UL; }
    }
    unsafe { std::fft_real_half_batch((const double*)re, b, len, rows); }
    t0 = now_ms();
    it = 0;
    while (it < iters) {
        unsafe { std::fft_real_half_batch((const double*)re, b, len, rows); }
        it = it + 1;
    }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[rfft 1024x4096 r2c cpu] ms_per_call=%.3f\n", ms); }
    std::dealloc(reh);
    std::dealloc(b);
    // ── single precision ──
    &heap Complex32 xf = random_complex32(n);
    std::fft_f32(xf, n);
    t0 = now_ms();
    it = 0;
    while (it < iters) { std::fft_f32(xf, n); it = it + 1; }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[fft1d n=1048576 c64 cpu] ms_per_call=%.3f\n", ms); }
    unsafe { std::dealloc(xf); }

    &heap Complex32 imgf = random_complex32(side * side);
    std::fft2d_f32(imgf, side, side);
    t0 = now_ms();
    it = 0;
    while (it < iters) { std::fft2d_f32(imgf, side, side); it = it + 1; }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[fft2d 1024x1024 c64 cpu] ms_per_call=%.3f\n", ms); }
    unsafe { std::dealloc(imgf); }

    &heap Complex32 bf = random_complex32(rows * len);
    std::fft_batch_f32(bf, len, rows, 0);
    t0 = now_ms();
    it = 0;
    while (it < iters) { std::fft_batch_f32(bf, len, rows, 0); it = it + 1; }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[batch 1024x4096 c2c f32 cpu] ms_per_call=%.3f\n", ms); }

    ?&heap float refh = std::alloc(rows * len * sizeof(float));
    float* ref;
    unsafe {
        ref = (float*)refh;
        unsigned long i = 0UL;
        while (i < rows * len) { ref[i] = (float)(drand48() * 2.0 - 1.0); i = i + 1UL; }
    }
    unsafe { std::fft_real_half_batch_f32((const float*)ref, bf, len, rows); }
    t0 = now_ms();
    it = 0;
    while (it < iters) {
        unsafe { std::fft_real_half_batch_f32((const float*)ref, bf, len, rows); }
        it = it + 1;
    }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[rfft 1024x4096 r2c f32 cpu] ms_per_call=%.3f\n", ms); }
    std::dealloc(refh);
    std::dealloc(bf);
    return 0;
}
