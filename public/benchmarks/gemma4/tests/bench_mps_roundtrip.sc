// Microbenchmark: how much of a single mps_matmul_abt_bf16_persistent call's
// wall time is fixed GPU-dispatch/command-buffer/wait overhead vs. actual
// memory-bound compute? Runs the SAME real gemma4 projection shape
// (N=2560, K=2560, the q/k/v-proj-ish shape used in test_mps_linear_gemv.sc)
// two ways: (a) N_ITERS separate mps_batch_begin/matmul/mps_batch_end round
// trips (today's gemma4_bf16_linear pattern -- one round trip per
// projection), and (b) N_ITERS matmuls issued inside ONE mps_batch_begin/
// mps_batch_end pair (one round trip total). The difference per-call,
// divided by N_ITERS, is the fixed per-round-trip overhead this shape's
// real compute is paying on top of, every single call -- the number that
// determines whether chaining gemma4's ~180 per-token round trips into
// fewer, larger batches would be a meaningful further win or not.
extern int printf(const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void free(void* ptr);
#include <std/ml/gpu_mps.h>
#include <std/ml/float16.h>
#include <std/time.h>

int main() {
    printf("mps_available: %d\n", std::mps_available());
    unsigned long N = 2560UL;
    unsigned long K = 2560UL;
    unsigned long ITERS = 200UL;

    float* aF32; unsafe { aF32 = (float*)malloc(sizeof(float) * N); }
    float* bF32; unsafe { bF32 = (float*)malloc(sizeof(float) * N * K); }
    unsigned long i = 0UL;
    unsafe {
        while (i < N) { aF32[i] = (float)0.01; i = i + 1UL; }
        i = 0UL;
        while (i < N * K) { bF32[i] = (float)0.001; i = i + 1UL; }
    }
    unsigned short* aBf; unsafe { aBf = (unsigned short*)malloc(2UL * N); }
    unsigned short* bBf; unsafe { bBf = (unsigned short*)malloc(2UL * N * K); }
    unsafe {
        std::f32_to_bf16_bulk(aF32, aBf, N);
        std::f32_to_bf16_bulk(bF32, bBf, N * K);
    }
    unsigned short* outBits; unsafe { outBits = (unsigned short*)malloc(2UL * K); }

    void* aBuf; unsafe { aBuf = std::mps_upload_persistent((const float*)aBf, 2UL * N); }
    void* bBuf; unsafe { bBuf = std::mps_upload_persistent((const float*)bBf, 2UL * N * K); }

    // (a) separate round trips -- today's per-op pattern.
    unsigned long long t0 = std::time_mono_ns();
    unsigned long it = 0UL;
    while (it < ITERS) {
        unsafe {
            std::mps_batch_begin();
            std::mps_matmul_abt_bf16_persistent(aBuf, bBuf, outBits, 1UL, N, K);
            std::mps_batch_end();
        }
        it = it + 1UL;
    }
    unsigned long long t1 = std::time_mono_ns();
    double separateMs = std::time_ns_to_ms(std::time_elapsed_ns(t0, t1));

    // (b) all ITERS matmuls chained into ONE round trip.
    unsigned long long t2 = std::time_mono_ns();
    unsafe { std::mps_batch_begin(); }
    it = 0UL;
    while (it < ITERS) {
        unsafe { std::mps_matmul_abt_bf16_persistent(aBuf, bBuf, outBits, 1UL, N, K); }
        it = it + 1UL;
    }
    unsafe { std::mps_batch_end(); }
    unsigned long long t3 = std::time_mono_ns();
    double chainedMs = std::time_ns_to_ms(std::time_elapsed_ns(t2, t3));

    double perCallSeparate = separateMs / (double)ITERS;
    double perCallChained = chainedMs / (double)ITERS;
    double overheadPerRoundTrip = perCallSeparate - perCallChained;

    printf("shape N=%lu K=%lu, %lu iters\n", N, K, ITERS);
    printf("separate round trips: %f ms total, %f ms/call\n", separateMs, perCallSeparate);
    printf("chained (1 round trip): %f ms total, %f ms/call\n", chainedMs, perCallChained);
    printf("estimated fixed overhead per round trip: %f ms (%.1f%% of separate per-call time)\n",
           overheadPerRoundTrip, 100.0 * overheadPerRoundTrip / perCallSeparate);

    unsafe {
        std::mps_release_persistent(aBuf, 2UL * N);
        std::mps_release_persistent(bBuf, 2UL * N * K);
        free((void*)aF32); free((void*)bF32); free((void*)aBf); free((void*)bBf); free((void*)outBits);
    }
    printf("DONE\n");
    return 0;
}
