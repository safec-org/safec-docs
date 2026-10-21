// FFT on an AMD GPU with std/dsp/fft_rocm.h (hipFFT/rocFFT on gpu_rocm's
// stream). Same transforms as fft_cpu.sc and ../pytorch/fft.py:
//   fft1d / fft2d  double-precision host arrays through rocm_fft /
//                  rocm_fft2d: each call uploads, transforms and reads back,
//                  so PCIe transfers dominate
//   batch          1024 complex64 transforms of length 4096, data resident
//                  on the GPU (rocm_fft_c2c)
//   rfft           1024 real fp32 transforms of length 4096 (rocm_fft_r2c)
// Each row is the mean over many calls, timed between two stream syncs.
//
// Build: safec fft_rocm.sc -I <SafeC> -O2 -o fft_rocm.ll
//        clang -O2 fft_rocm.ll libsafec_std.a -L/opt/rocm/lib \
//              -lhipfft -lamdhip64 -lhiprtc -lrocblas -lm -lpthread -o fft_rocm
extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
#include <std/dsp/fft_rocm.h>
#include <std/dsp/complex_dsp.h>
#include <std/ml/gpu_rocm.h>
#include <std/collections/vec.h>
#include <std/mem.h>
#include <std/panic.h>
#include <std/time.h>

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

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
        case null: { std::panic_at("random_complex: out of memory", "fft_rocm.sc", 0); }
    }
    std::panic_at("random_complex: unreachable", "fft_rocm.sc", 0);
}

static struct RocmBuf random_buf(unsigned long n) {
    struct Vec<float> v = std::Vec::with_cap(n);
    unsigned long i = 0UL;
    while (i < n) { unsafe { v.push((float)(drand48() * 2.0 - 1.0)); } i = i + 1UL; }
    struct RocmBuf b = RocmBuf::upload(v.at(0UL), n);
    v.free();
    return b;
}

int main() {
    if (!std::rocm_available()) { unsafe { printf("no ROCm GPU\n"); } return 1; }
    unsafe { srand48(42L); }
    unsigned long n = 1048576UL;
    &heap Complex x = random_complex(n);
    std::rocm_fft(x, n);   // warm-up: plan + scratch
    int iters = 50;
    double t0 = now_ms();
    int it = 0;
    while (it < iters) { std::rocm_fft(x, n); it = it + 1; }
    double ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[fft1d n=1048576 c128 rocm] ms_per_call=%.4f\n", ms); }
    unsafe { std::dealloc(x); }

    unsigned long side = 1024UL;
    &heap Complex img = random_complex(side * side);
    std::rocm_fft2d(img, side, side);
    t0 = now_ms();
    it = 0;
    while (it < iters) { std::rocm_fft2d(img, side, side); it = it + 1; }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[fft2d 1024x1024 c128 rocm] ms_per_call=%.4f\n", ms); }
    unsafe { std::dealloc(img); }

    unsigned long rows = 1024UL;
    unsigned long len = 4096UL;
    struct RocmBuf din = random_buf(2UL * rows * len);
    struct RocmBuf dout = RocmBuf::alloc(2UL * rows * len);
    iters = 1000;
    int w = 0;
    while (w < 5) { std::rocm_fft_c2c(din, dout, len, rows, 0); w = w + 1; }
    std::rocm_sync();
    t0 = now_ms();
    it = 0;
    while (it < iters) { std::rocm_fft_c2c(din, dout, len, rows, 0); it = it + 1; }
    std::rocm_sync();
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[batch 1024x4096 c2c rocm] ms_per_call=%.4f\n", ms); }

    struct RocmBuf rin = random_buf(rows * len);
    w = 0;
    while (w < 5) { std::rocm_fft_r2c(rin, dout, len, rows); w = w + 1; }
    std::rocm_sync();
    t0 = now_ms();
    it = 0;
    while (it < iters) { std::rocm_fft_r2c(rin, dout, len, rows); it = it + 1; }
    std::rocm_sync();
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[rfft 1024x4096 r2c rocm] ms_per_call=%.4f\n", ms); }
    din.free();
    dout.free();
    rin.free();
    return 0;
}
