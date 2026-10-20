// Sum of squares over 20,000,000 doubles with explicit vectors. The data
// setup is the same scalar loop as every other language's version; the
// summation is built once per x86-64 SIMD tier (std/simd/cpu.h: AVX-512 >
// AVX2+FMA > AVX > SSE4.1 > SSE2) and the best one the CPU supports is
// picked at run time, so a baseline (SSE2) build still uses 512-bit vectors
// on an AVX-512 machine. Four accumulators keep the adds independent. Other
// targets use the base build (Neon on AArch64).
extern int printf(const char* fmt, ...);
#include <std/simd/cpu.h>
#define N 20000000

double a[N];

typedef vec<double, 8> d8;

// Eight lanes per vector, four independent accumulators; LLVM splits d8
// into whatever the build's registers hold (one zmm, two ymm, four xmm).
static double sumsq_body(const double* p, int n) __attribute__((always_inline)) {
    d8 acc0;
    acc0 = acc0 - acc0;
    d8 acc1 = acc0; d8 acc2 = acc0; d8 acc3 = acc0;
    int i = 0;
    unsafe {
        while (i + 32 <= n) {
            d8 v0 = *(const d8*)&p[i];
            d8 v1 = *(const d8*)&p[i + 8];
            d8 v2 = *(const d8*)&p[i + 16];
            d8 v3 = *(const d8*)&p[i + 24];
            acc0 = __builtin_fmuladd(v0, v0, acc0);
            acc1 = __builtin_fmuladd(v1, v1, acc1);
            acc2 = __builtin_fmuladd(v2, v2, acc2);
            acc3 = __builtin_fmuladd(v3, v3, acc3);
            i = i + 32;
        }
    }
    d8 acc = (acc0 + acc1) + (acc2 + acc3);
    double sum = ((acc[0] + acc[1]) + (acc[2] + acc[3])) + ((acc[4] + acc[5]) + (acc[6] + acc[7]));
    unsafe {
        while (i < n) { sum = sum + p[i] * p[i]; i = i + 1; }
    }
    return sum;
}

static double sumsq_base(const double* p, int n) { return sumsq_body(p, n); }
#if defined(__x86_64__)
static double sumsq_sse41(const double* p, int n) __attribute__((target("sse4.1"))) { return sumsq_body(p, n); }
static double sumsq_avx(const double* p, int n) __attribute__((target("avx"))) { return sumsq_body(p, n); }
static double sumsq_avx2(const double* p, int n) __attribute__((target("avx2,fma"))) { return sumsq_body(p, n); }
static double sumsq_avx512(const double* p, int n) __attribute__((target("avx512f,avx512vl,avx2,fma"))) { return sumsq_body(p, n); }
#endif

static double sumsq(const double* p, int n) {
#if defined(__x86_64__)
    int l = std::simd_level();
    if (l >= SIMD_AVX512) return sumsq_avx512(p, n);
    if (l >= SIMD_AVX2) return sumsq_avx2(p, n);
    if (l >= SIMD_AVX) return sumsq_avx(p, n);
    if (l >= SIMD_SSE41) return sumsq_sse41(p, n);
#endif
    return sumsq_base(p, n);
}

int main() {
    int i = 0;
    while (i < N) {
        a[i] = (double)(i % 1000) * 0.001;
        i = i + 1;
    }
    double sum;
    unsafe { sum = sumsq((const double*)&a[0], N); }
    printf("%.6f\n", sum);
    return 0;
}
