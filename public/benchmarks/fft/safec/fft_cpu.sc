// FFT on the CPU with std/dsp/fft.h and imaging.h (radix-2, double
// precision, one thread). Same transforms as fft_cuda.sc and
// ../pytorch/fft.py:
//   fft1d   one complex transform of length 2^20
//   fft2d   one 1024 x 1024 complex 2D transform
//   batch   1024 complex transforms of length 4096 (the GPU rows run
//           these in fp32)
//   rfft    1024 real transforms of length 4096 (fft_real)
// Prints the mean time per call.
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
#include <std/time.h>

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

static struct Complex* random_complex(unsigned long n) {
    struct Complex* p;
    unsafe {
        p = (struct Complex*)std::alloc(n * sizeof(struct Complex));
        unsigned long i = 0UL;
        while (i < n) { p[i].re = drand48() * 2.0 - 1.0; p[i].im = drand48() * 2.0 - 1.0; i = i + 1UL; }
    }
    return p;
}

int main() {
    unsafe { srand48(42L); }
    unsigned long n = 1048576UL;
    struct Complex* x = random_complex(n);
    int iters = 3;
    double t0 = now_ms();
    int it = 0;
    while (it < iters) { std::fft(x, n); it = it + 1; }
    double ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[fft1d n=1048576 c128 cpu] ms_per_call=%.3f\n", ms); }
    unsafe { std::dealloc((void*)x); }

    unsigned long side = 1024UL;
    struct Complex* img = random_complex(side * side);
    t0 = now_ms();
    it = 0;
    while (it < iters) { std::fft2d(img, side, side); it = it + 1; }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[fft2d 1024x1024 c128 cpu] ms_per_call=%.3f\n", ms); }
    unsafe { std::dealloc((void*)img); }

    unsigned long rows = 1024UL;
    unsigned long len = 4096UL;
    struct Complex* b = random_complex(rows * len);
    t0 = now_ms();
    it = 0;
    while (it < iters) {
        unsigned long r = 0UL;
        unsafe { while (r < rows) { std::fft(b + r * len, len); r = r + 1UL; } }
        it = it + 1;
    }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[batch 1024x4096 c2c cpu] ms_per_call=%.3f\n", ms); }

    double* re;
    unsafe {
        re = (double*)std::alloc(rows * len * sizeof(double));
        unsigned long i = 0UL;
        while (i < rows * len) { re[i] = drand48() * 2.0 - 1.0; i = i + 1UL; }
    }
    t0 = now_ms();
    it = 0;
    while (it < iters) {
        unsigned long r = 0UL;
        unsafe { while (r < rows) { std::fft_real((const double*)(re + r * len), b + r * len, len); r = r + 1UL; } }
        it = it + 1;
    }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[rfft 1024x4096 r2c cpu] ms_per_call=%.3f\n", ms); }
    unsafe { std::dealloc((void*)re); std::dealloc((void*)b); }
    return 0;
}
