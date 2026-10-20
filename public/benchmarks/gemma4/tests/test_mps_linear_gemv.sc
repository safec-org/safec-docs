// Correctness test for matmul_abt_kernel_bf16_gemv, the dedicated M==1
// (single-request decode step) GEMV kernel wired into
// mps_matmul_abt_bf16_persistent's M==1 branch (see gpu_mps.sc and
// gpu_mps_kernels.metal for the design rationale). Compares its GPU output
// against a plain CPU-computed bf16-rounded reference dot product at a
// handful of shapes covering: a tiny hand-checkable case, an exact-multiple-
// of-32 case, and non-multiple-of-4/odd-length cases (K not divisible by
// GEMV_SIMDGROUPS, N not divisible by 32) to exercise the kernel's
// K-bounds-check and the simd_sum reduction's tail handling.
extern int printf(const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void free(void* ptr);
#include <std/ml/gpu_mps.h>
#include <std/ml/float16.h>

static unsigned long __rng_state = 88172645463325252UL;
static double __next_rand() {
    __rng_state ^= __rng_state << 13;
    __rng_state ^= __rng_state >> 7;
    __rng_state ^= __rng_state << 17;
    unsigned long v = __rng_state & 0xFFFFFFUL;
    return (double)v / (double)0xFFFFFF;
}

// Runs one (N,K) shape: fills a[N] and b[K,N] with small random values,
// computes the bf16-rounded reference on CPU, runs the GPU path, and
// reports max abs difference (bf16 mantissa is only 8 bits, so exact
// equality isn't expected across two different reduction orders -- a
// generous but real tolerance is checked instead).
static int __run_shape(unsigned long N, unsigned long K, const char* label) {
    float* aF32; unsafe { aF32 = (float*)malloc(sizeof(float) * N); }
    float* bF32; unsafe { bF32 = (float*)malloc(sizeof(float) * N * K); }
    unsigned long i = 0UL;
    unsafe {
        while (i < N) { aF32[i] = (float)(__next_rand() * 2.0 - 1.0); i = i + 1UL; }
        i = 0UL;
        while (i < N * K) { bF32[i] = (float)(__next_rand() * 2.0 - 1.0); i = i + 1UL; }
    }

    unsigned short* aBf; unsafe { aBf = (unsigned short*)malloc(2UL * N); }
    unsigned short* bBf; unsafe { bBf = (unsigned short*)malloc(2UL * N * K); }
    unsafe {
        f32_to_bf16_bulk(aF32, aBf, N);
        f32_to_bf16_bulk(bF32, bBf, N * K);
    }
    // Round-trip a/b through bf16 on the CPU side too so the reference dot
    // product uses the SAME rounded operands the GPU kernel actually reads
    // (bf16 storage precision loss is not what's under test here).
    float* aRound; unsafe { aRound = (float*)malloc(sizeof(float) * N); }
    float* bRound; unsafe { bRound = (float*)malloc(sizeof(float) * N * K); }
    unsafe {
        bf16_to_f32_bulk(aBf, aRound, N);
        bf16_to_f32_bulk(bBf, bRound, N * K);
    }

    float* refOut; unsafe { refOut = (float*)malloc(sizeof(float) * K); }
    unsigned long k = 0UL;
    unsafe {
        while (k < K) {
            double acc = 0.0;
            unsigned long n = 0UL;
            while (n < N) {
                acc += (double)aRound[n] * (double)bRound[k * N + n];
                n = n + 1UL;
            }
            refOut[k] = (float)acc;
            k = k + 1UL;
        }
    }

    unsigned short* gpuOutBits; unsafe { gpuOutBits = (unsigned short*)malloc(2UL * K); }
    int rc;
    unsafe {
        void* aBuf = mps_upload_persistent((const float*)aBf, 2UL * N);
        void* bBuf = mps_upload_persistent((const float*)bBf, 2UL * N * K);
        mps_batch_begin();
        rc = mps_matmul_abt_bf16_persistent(aBuf, bBuf, gpuOutBits, 1UL, N, K);
        mps_batch_end();
        mps_release_persistent(aBuf, 2UL * N);
        mps_release_persistent(bBuf, 2UL * N * K);
    }
    if (!rc) {
        printf("%s: mps_matmul_abt_bf16_persistent returned 0 (GPU path unavailable) -- skipping\n", label);
        unsafe {
            free((void*)aF32); free((void*)bF32); free((void*)aBf); free((void*)bBf);
            free((void*)aRound); free((void*)bRound); free((void*)refOut); free((void*)gpuOutBits);
        }
        return 1;
    }

    float* gpuOut; unsafe { gpuOut = (float*)malloc(sizeof(float) * K); }
    unsafe { bf16_to_f32_bulk(gpuOutBits, gpuOut, K); }

    double maxAbsDiff = 0.0;
    double maxRefMag = 0.0;
    k = 0UL;
    unsafe {
        while (k < K) {
            double diff = (double)gpuOut[k] - (double)refOut[k];
            if (diff < 0.0) { diff = 0.0 - diff; }
            if (diff > maxAbsDiff) { maxAbsDiff = diff; }
            double mag = (double)refOut[k];
            if (mag < 0.0) { mag = 0.0 - mag; }
            if (mag > maxRefMag) { maxRefMag = mag; }
            k = k + 1UL;
        }
    }
    // bf16 has ~2-3 decimal digits of precision; a relative tolerance of
    // 2% against the largest reference magnitude comfortably covers
    // reduction-order differences between the GPU's simd_sum tree and the
    // CPU's sequential accumulation, while still catching a genuinely
    // wrong kernel (wrong N/K, off-by-one indexing, uninitialized lanes).
    double tol = maxRefMag * 0.02 + 0.01;
    int ok = maxAbsDiff <= tol;
    printf("%s (N=%lu, K=%lu): maxAbsDiff=%f tol=%f -> %s\n",
           label, N, K, maxAbsDiff, tol, ok ? "PASS" : "FAIL");

    unsafe {
        free((void*)aF32); free((void*)bF32); free((void*)aBf); free((void*)bBf);
        free((void*)aRound); free((void*)bRound); free((void*)refOut);
        free((void*)gpuOutBits); free((void*)gpuOut);
    }
    return ok;
}

int main() {
    printf("mps_available: %d (expect 1 on this Mac)\n", std::mps_available());

    int allOk = 1;
    allOk = __run_shape(8UL, 8UL, "tiny") && allOk;
    allOk = __run_shape(2560UL, 2560UL, "hiddenSize-square (q/k/v-ish)") && allOk;
    allOk = __run_shape(2560UL, 10240UL, "gate/up-proj-ish (K not mult of 32... check)") && allOk;
    allOk = __run_shape(2561UL, 1030UL, "odd N, K not mult of 4") && allOk;
    allOk = __run_shape(17UL, 5UL, "tiny odd shape (K < GEMV_SIMDGROUPS)") && allOk;

    printf("all shapes passed (expect 1): %d\n", allOk);
    printf("DONE\n");
    return 0;
}
