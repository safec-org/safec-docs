// DSP on the CPU with std/dsp, in double precision and then again in float
// (the _f32 functions; rows tagged f32). Same workloads and sizes as
// ../python/dsp_scipy.py:
//   fir        1,048,576 samples through a 127-tap FIR: the streaming
//              FirFilter (one sample at a time, real-time safe) and the
//              block conv_direct (SIMD dot products on the thread pool)
//   fftconv    1,048,576 samples convolved with a 4,096-tap kernel (conv_fft)
//   biquad     1,048,576 samples through one RBJ low-pass section
//              (biquad_process_block)
//   stft       1,048,576 samples, 1,024-point Hann frames, hop 256
//   resample   1,048,576 samples from 44.1 kHz to 48 kHz (windowed sinc,
//              16 taps each side)
//   conv2d     a 1024 x 1024 image with a 5 x 5 Gaussian ("same" size)
// Prints the mean time per call after one warm-up call (which starts the
// thread pool and builds FFT tables).
//
// Build: safec dsp_cpu.sc -I <SafeC> -O2 -o dsp_cpu.ll
//        clang -O2 dsp_cpu.ll libsafec_std.a -lm -lpthread -o dsp_cpu
extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
extern void* malloc(unsigned long n);
#include <std/dsp/filter.h>
#include <std/dsp/biquad.h>
#include <std/dsp/convolution.h>
#include <std/dsp/stft.h>
#include <std/dsp/resample.h>
#include <std/dsp/imaging.h>
#include <std/dsp/complex_dsp.h>
#include <std/time.h>

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

static double* rnd(unsigned long n) {
    unsafe {
        double* p = (double*)malloc(n * 8UL);
        unsigned long i = 0UL;
        while (i < n) { p[i] = drand48() * 2.0 - 1.0; i = i + 1UL; }
        return p;
    }
}

static float* to_f32(const double* p, unsigned long n) {
    unsafe {
        float* q = (float*)malloc(n * 4UL);
        unsigned long i = 0UL;
        while (i < n) { q[i] = (float)p[i]; i = i + 1UL; }
        return q;
    }
}

static double sum32(const float* p, unsigned long n) {
    double s = 0.0;
    unsigned long i = 0UL;
    unsafe { while (i < n) { s = s + (double)p[i]; i = i + 1UL; } }
    return s;
}

static double sum(const double* p, unsigned long n) {
    double s = 0.0;
    unsigned long i = 0UL;
    unsafe { while (i < n) { s = s + p[i]; i = i + 1UL; } }
    return s;
}

int main() {
    unsafe {
        srand48(42L);
        unsigned long n = 1048576UL;
        double* x = rnd(n);

        // fir: streaming FirFilter
        unsigned long taps = 127UL;
        double* h = rnd(taps);
        double* hist = (double*)malloc(taps * 8UL);
        double* y = (double*)malloc((n + 8192UL) * 8UL);
        int it = 0;
        int iters = 3;
        struct FirFilter f = std::fir_init((const double*)h, taps, hist);
        f.reset();
        std::fir_process_block(&f, (const double*)x, y, n);
        double t0 = now_ms();
        while (it < iters) { f.reset(); std::fir_process_block(&f, (const double*)x, y, n); it = it + 1; }
        printf("[fir stream n=1048576 taps=127] ms_per_call=%.3f checksum=%.6f\n", (now_ms() - t0) / (double)iters, sum(y, n));

        // fir: block conv_direct
        double* yc = (double*)malloc((n + taps) * 8UL);
        std::conv_direct((const double*)x, n, (const double*)h, taps, yc);
        t0 = now_ms();
        it = 0;
        while (it < iters) { std::conv_direct((const double*)x, n, (const double*)h, taps, yc); it = it + 1; }
        printf("[fir block n=1048576 taps=127] ms_per_call=%.3f checksum=%.6f\n", (now_ms() - t0) / (double)iters, sum(yc, n));

        // fftconv
        unsigned long ktaps = 4096UL;
        double* k = rnd(ktaps);
        double* yf = (double*)malloc((n + ktaps) * 8UL);
        std::conv_fft((const double*)x, n, (const double*)k, ktaps, yf);
        t0 = now_ms();
        it = 0;
        while (it < iters) { std::conv_fft((const double*)x, n, (const double*)k, ktaps, yf); it = it + 1; }
        printf("[fftconv n=1048576 taps=4096] ms_per_call=%.3f checksum=%.6f\n", (now_ms() - t0) / (double)iters, sum(yf, n + ktaps - 1UL));

        // biquad
        struct Biquad bq = std::biquad_lowpass(48000.0, 1000.0, 0.7071);
        std::biquad_process_block(&bq, (const double*)x, y, n);
        t0 = now_ms();
        it = 0;
        while (it < iters) { bq.reset(); std::biquad_process_block(&bq, (const double*)x, y, n); it = it + 1; }
        printf("[biquad n=1048576] ms_per_call=%.3f checksum=%.6f\n", (now_ms() - t0) / (double)iters, sum(y, n));

        // stft
        unsigned long frame = 1024UL;
        unsigned long hop = 256UL;
        unsigned long frames = std::stft_num_frames(n, frame, hop);
        struct Complex* spec = (struct Complex*)malloc(frames * frame * 16UL);
        std::stft_forward((const double*)x, n, frame, hop, STFT_WIN_HANN, spec);
        t0 = now_ms();
        it = 0;
        while (it < iters) { std::stft_forward((const double*)x, n, frame, hop, STFT_WIN_HANN, spec); it = it + 1; }
        printf("[stft n=1048576 frame=1024 hop=256] ms_per_call=%.3f frames=%lu\n", (now_ms() - t0) / (double)iters, frames);

        // resample 44.1k -> 48k
        unsigned long outLen = n * 160UL / 147UL;
        double* yr = (double*)malloc(outLen * 8UL);
        std::resample_sinc((const double*)x, n, yr, outLen, 16UL);
        t0 = now_ms();
        it = 0;
        while (it < iters) { std::resample_sinc((const double*)x, n, yr, outLen, 16UL); it = it + 1; }
        printf("[resample n=1048576 44100->48000] ms_per_call=%.3f checksum=%.6f\n", (now_ms() - t0) / (double)iters, sum(yr, outLen));

        // conv2d
        unsigned long side = 1024UL;
        double* img = rnd(side * side);
        double* out = (double*)malloc(side * side * 8UL);
        double g[25];
        std::gaussian_kernel(&g[0], 5UL, 1.0);
        std::conv2d((const double*)img, side, side, (const double*)&g[0], 5UL, 5UL, out);
        t0 = now_ms();
        it = 0;
        while (it < iters) { std::conv2d((const double*)img, side, side, (const double*)&g[0], 5UL, 5UL, out); it = it + 1; }
        printf("[conv2d 1024x1024 k=5x5] ms_per_call=%.3f checksum=%.6f\n", (now_ms() - t0) / (double)iters, sum(out, side * side));

        // ── the same in single precision ──
        float* xf = to_f32(x, n);
        float* hf = to_f32(h, taps);
        float* histf = (float*)malloc(taps * 4UL);
        float* yff = (float*)malloc((n + 8192UL) * 4UL);
        struct FirFilter32 ff = std::fir_init_f32((const float*)hf, taps, histf);
        ff.reset();
        std::fir_process_block_f32(&ff, (const float*)xf, yff, n);
        t0 = now_ms();
        it = 0;
        while (it < iters) { ff.reset(); std::fir_process_block_f32(&ff, (const float*)xf, yff, n); it = it + 1; }
        printf("[fir stream n=1048576 taps=127 f32] ms_per_call=%.3f checksum=%.6f\n", (now_ms() - t0) / (double)iters, sum32(yff, n));

        float* ycf = (float*)malloc((n + taps) * 4UL);
        std::conv_direct_f32((const float*)xf, n, (const float*)hf, taps, ycf);
        t0 = now_ms();
        it = 0;
        while (it < iters) { std::conv_direct_f32((const float*)xf, n, (const float*)hf, taps, ycf); it = it + 1; }
        printf("[fir block n=1048576 taps=127 f32] ms_per_call=%.3f checksum=%.6f\n", (now_ms() - t0) / (double)iters, sum32(ycf, n));

        float* kf = to_f32(k, ktaps);
        float* yfc = (float*)malloc((n + ktaps) * 4UL);
        std::conv_fft_f32((const float*)xf, n, (const float*)kf, ktaps, yfc);
        t0 = now_ms();
        it = 0;
        while (it < iters) { std::conv_fft_f32((const float*)xf, n, (const float*)kf, ktaps, yfc); it = it + 1; }
        printf("[fftconv n=1048576 taps=4096 f32] ms_per_call=%.3f checksum=%.6f\n", (now_ms() - t0) / (double)iters, sum32(yfc, n + ktaps - 1UL));

        struct Biquad32 bqf = std::biquad_to_f32(std::biquad_lowpass(48000.0, 1000.0, 0.7071));
        std::biquad_process_block_f32(&bqf, (const float*)xf, yff, n);
        t0 = now_ms();
        it = 0;
        while (it < iters) { bqf.reset(); std::biquad_process_block_f32(&bqf, (const float*)xf, yff, n); it = it + 1; }
        printf("[biquad n=1048576 f32] ms_per_call=%.3f checksum=%.6f\n", (now_ms() - t0) / (double)iters, sum32(yff, n));

        struct Complex32* specf = (struct Complex32*)malloc(frames * frame * 8UL);
        std::stft_forward_f32((const float*)xf, n, frame, hop, STFT_WIN_HANN, specf);
        t0 = now_ms();
        it = 0;
        while (it < iters) { std::stft_forward_f32((const float*)xf, n, frame, hop, STFT_WIN_HANN, specf); it = it + 1; }
        printf("[stft n=1048576 frame=1024 hop=256 f32] ms_per_call=%.3f frames=%lu\n", (now_ms() - t0) / (double)iters, frames);

        float* yrf = (float*)malloc(outLen * 4UL);
        std::resample_sinc_f32((const float*)xf, n, yrf, outLen, 16UL);
        t0 = now_ms();
        it = 0;
        while (it < iters) { std::resample_sinc_f32((const float*)xf, n, yrf, outLen, 16UL); it = it + 1; }
        printf("[resample n=1048576 44100->48000 f32] ms_per_call=%.3f checksum=%.6f\n", (now_ms() - t0) / (double)iters, sum32(yrf, outLen));
    }
    return 0;
}
