// FFT on an NVIDIA GPU with std/dsp/fft_cuda.h (cuFFT on gpu_cuda's
// stream). Same transforms as fft_cpu.sc and ../pytorch/fft.py:
//   fft1d / fft2d  double-precision host arrays through cuda_fft /
//                  cuda_fft2d: each call uploads, transforms and reads back,
//                  so PCIe transfers dominate
//   batch          1024 complex64 transforms of length 4096, data resident
//                  on the GPU (cuda_fft_c2c)
//   rfft           1024 real fp32 transforms of length 4096 (cuda_fft_r2c)
// Each row is the mean over many calls, timed between two stream syncs.
//
// Build: safec fft_cuda.sc -I <SafeC> -O2 -o fft_cuda.ll
//        clang -O2 fft_cuda.ll libsafec_std.a -L/usr/local/cuda/lib64 \
//              -lcuda -lcublas -lcublasLt -lnvrtc -lcufft -lm -lpthread -o fft_cuda
extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
#include <std/dsp/fft_cuda.h>
#include <std/dsp/complex_dsp.h>
#include <std/ml/gpu_cuda.h>
#include <std/collections/vec.h>
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

static struct CudaBuf random_buf(unsigned long n) {
    struct Vec<float> v = std::vec_with_cap(n);
    unsigned long i = 0UL;
    while (i < n) { unsafe { v.push((float)(drand48() * 2.0 - 1.0)); } i = i + 1UL; }
    struct CudaBuf b = std::cuda_upload(v.at(0UL), n);
    v.free();
    return b;
}

int main() {
    if (!std::cuda_available()) { unsafe { printf("no CUDA GPU\n"); } return 1; }
    unsafe { srand48(42L); }
    unsigned long n = 1048576UL;
    struct Complex* x = random_complex(n);
    std::cuda_fft(x, n);   // warm-up: plan + scratch
    int iters = 50;
    double t0 = now_ms();
    int it = 0;
    while (it < iters) { std::cuda_fft(x, n); it = it + 1; }
    double ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[fft1d n=1048576 c128 cuda] ms_per_call=%.4f\n", ms); }
    unsafe { std::dealloc((void*)x); }

    unsigned long side = 1024UL;
    struct Complex* img = random_complex(side * side);
    std::cuda_fft2d(img, side, side);
    t0 = now_ms();
    it = 0;
    while (it < iters) { std::cuda_fft2d(img, side, side); it = it + 1; }
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[fft2d 1024x1024 c128 cuda] ms_per_call=%.4f\n", ms); }
    unsafe { std::dealloc((void*)img); }

    unsigned long rows = 1024UL;
    unsigned long len = 4096UL;
    struct CudaBuf din = random_buf(2UL * rows * len);
    struct CudaBuf dout = std::cuda_alloc(2UL * rows * len);
    iters = 1000;
    int w = 0;
    while (w < 5) { std::cuda_fft_c2c(din, dout, len, rows, 0); w = w + 1; }
    std::cuda_sync();
    t0 = now_ms();
    it = 0;
    while (it < iters) { std::cuda_fft_c2c(din, dout, len, rows, 0); it = it + 1; }
    std::cuda_sync();
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[batch 1024x4096 c2c cuda] ms_per_call=%.4f\n", ms); }

    struct CudaBuf rin = random_buf(rows * len);
    w = 0;
    while (w < 5) { std::cuda_fft_r2c(rin, dout, len, rows); w = w + 1; }
    std::cuda_sync();
    t0 = now_ms();
    it = 0;
    while (it < iters) { std::cuda_fft_r2c(rin, dout, len, rows); it = it + 1; }
    std::cuda_sync();
    ms = (now_ms() - t0) / (double)iters;
    unsafe { printf("[rfft 1024x4096 r2c cuda] ms_per_call=%.4f\n", ms); }
    std::cuda_free(&din);
    std::cuda_free(&dout);
    std::cuda_free(&rin);
    return 0;
}
