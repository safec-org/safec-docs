/* The fft_cpu.sc workloads with FFTW 3, in double and then single precision
 * (fftwf, rows tagged c64 / f32), planned with
 * FFTW_MEASURE once per shape outside the timing:
 *   fft1d  one complex transform of length 2^20 (in place)
 *   fft2d  one 1024 x 1024 complex 2D transform
 *   batch  1024 complex transforms of length 4096 (fftw_plan_many_dft)
 *   rfft   1024 real transforms of length 4096 (n/2 + 1 bins each)
 * Run with an argument N to use N threads (fftw_plan_with_nthreads).
 * Build: cc -O2 fftw_bench.c -lfftw3f_threads -lfftw3f -lfftw3_threads -lfftw3 -lm -lpthread
 */
#include <fftw3.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

static double now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static void fill(double *p, size_t n) {
    srand48(42);
    for (size_t i = 0; i < n; i++) p[i] = drand48() * 2.0 - 1.0;
}

static void fillf(float *p, size_t n) {
    srand48(42);
    for (size_t i = 0; i < n; i++) p[i] = (float)(drand48() * 2.0 - 1.0);
}

static double runf(fftwf_plan p, int iters) {
    fftwf_execute(p);
    double t0 = now_ms();
    for (int i = 0; i < iters; i++) fftwf_execute(p);
    return (now_ms() - t0) / iters;
}

/* The same four shapes in single precision. */
static void bench_f32(int threads, const char *tag) {
    fftwf_init_threads();
    fftwf_plan_with_nthreads(threads);
    int n = 1 << 20;
    fftwf_complex *x = fftwf_alloc_complex(n);
    fftwf_plan p = fftwf_plan_dft_1d(n, x, x, FFTW_FORWARD, FFTW_MEASURE);
    fillf((float *)x, 2 * (size_t)n);
    printf("[fft1d n=1048576 c64 %s] ms_per_call=%.3f\n", tag, runf(p, 20));
    fftwf_destroy_plan(p);

    p = fftwf_plan_dft_2d(1024, 1024, x, x, FFTW_FORWARD, FFTW_MEASURE);
    fillf((float *)x, 2 * (size_t)n);
    printf("[fft2d 1024x1024 c64 %s] ms_per_call=%.3f\n", tag, runf(p, 20));
    fftwf_destroy_plan(p);

    int len = 4096, rows = 1024;
    fftwf_complex *b = fftwf_alloc_complex((size_t)len * rows);
    p = fftwf_plan_many_dft(1, &len, rows, b, NULL, 1, len, b, NULL, 1, len, FFTW_FORWARD, FFTW_MEASURE);
    fillf((float *)b, 2 * (size_t)len * rows);
    printf("[batch 1024x4096 c2c f32 %s] ms_per_call=%.3f\n", tag, runf(p, 20));
    fftwf_destroy_plan(p);

    float *r = fftwf_alloc_real((size_t)len * rows);
    int half = len / 2 + 1;
    p = fftwf_plan_many_dft_r2c(1, &len, rows, r, NULL, 1, len, b, NULL, 1, half, FFTW_MEASURE);
    fillf(r, (size_t)len * rows);
    printf("[rfft 1024x4096 r2c f32 %s] ms_per_call=%.3f\n", tag, runf(p, 20));
    fftwf_destroy_plan(p);
}

static double run(fftw_plan p, int iters) {
    fftw_execute(p);   /* warm-up */
    double t0 = now_ms();
    for (int i = 0; i < iters; i++) fftw_execute(p);
    return (now_ms() - t0) / iters;
}

int main(int argc, char **argv) {
    int threads = argc > 1 ? atoi(argv[1]) : 1;
    fftw_init_threads();
    fftw_plan_with_nthreads(threads);
    const char *tag = threads > 1 ? "mt" : "st";

    int n = 1 << 20;
    fftw_complex *x = fftw_alloc_complex(n);
    fftw_plan p = fftw_plan_dft_1d(n, x, x, FFTW_FORWARD, FFTW_MEASURE);
    fill((double *)x, 2 * (size_t)n);
    printf("[fft1d n=1048576 c128 %s] ms_per_call=%.3f\n", tag, run(p, 20));
    fftw_destroy_plan(p);

    p = fftw_plan_dft_2d(1024, 1024, x, x, FFTW_FORWARD, FFTW_MEASURE);
    fill((double *)x, 2 * (size_t)n);
    printf("[fft2d 1024x1024 c128 %s] ms_per_call=%.3f\n", tag, run(p, 20));
    fftw_destroy_plan(p);

    int len = 4096, rows = 1024;
    fftw_complex *b = fftw_alloc_complex((size_t)len * rows);
    p = fftw_plan_many_dft(1, &len, rows, b, NULL, 1, len, b, NULL, 1, len, FFTW_FORWARD, FFTW_MEASURE);
    fill((double *)b, 2 * (size_t)len * rows);
    printf("[batch 1024x4096 c2c %s] ms_per_call=%.3f\n", tag, run(p, 20));
    fftw_destroy_plan(p);

    double *r = fftw_alloc_real((size_t)len * rows);
    int half = len / 2 + 1;
    p = fftw_plan_many_dft_r2c(1, &len, rows, r, NULL, 1, len, b, NULL, 1, half, FFTW_MEASURE);
    fill(r, (size_t)len * rows);
    printf("[rfft 1024x4096 r2c %s] ms_per_call=%.3f\n", tag, run(p, 20));
    fftw_destroy_plan(p);
    bench_f32(threads, tag);
    printf("fftw %s\n", fftw_version);
    return 0;
}
