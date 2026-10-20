// Flash and linear attention on an AMD GPU with std::ml's ROCm kernels
// (gpu_rocm.h), the fp32 shapes of attn_cuda.sc and ../pytorch/attn.py:
//
//   flash   batch 4, 16 heads, head dim 64 / 128, 1024 and 4096 tokens,
//           causal and not (rocm_flash_attention)
//   linear  batch 4, 16 heads, head dim 64 at 4096 and 16384 tokens and
//           head dim 128 at 4096, causal and not (rocm_linear_attention)
//
// The ROCm ops take [heads][rows][headDim], so the batch is folded into the
// heads (64 "heads"), which is the same memory layout as [batch][heads]. Data
// stays on the GPU. Each row is the mean of a run of queued calls between two
// synchronizations, after warm-up calls. Flash rows also report TFLOPS
// (4 B H S^2 D, halved for causal).
//
// Build: safec attn_rocm.sc -I <SafeC> -O2 -o attn_rocm.ll
//        clang -O2 attn_rocm.ll libsafec_std.a -L/opt/rocm/lib \
//              -lamdhip64 -lhiprtc -lrocblas -lm -lpthread -o attn_rocm
extern int printf(const char* fmt, ...);
#include <std/ml/gpu_rocm.h>
#include <std/collections/vec.h>
#include <std/time.h>

#define BATCH 4UL
#define HEADS 16UL

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

// Uploads q/k/v/out for 'heads' x 's' x 'd' from 'host' (three disjoint
// slices), runs 'iters' calls of flash (lin = 0) or linear (lin = 1)
// attention after 'warm' warm-up calls, and returns ms per call (-1 on
// failure).
static double time_case(&Vec<float> host, unsigned long heads, unsigned long s, unsigned long d,
                        int causal, int lin, int warm, int iters) {
    unsigned long n = heads * s * d;
    struct RocmBuf q = std::rocm_upload(host.at(0UL), n);
    struct RocmBuf k = std::rocm_upload(host.at(n), n);
    struct RocmBuf v = std::rocm_upload(host.at(2UL * n), n);
    struct RocmBuf o = std::rocm_alloc(n);
    double ms = -1.0;
    int ok = 1;
    int i = 0;
    while (i < warm + iters && ok) {
        if (i == warm) { std::rocm_sync(); ms = now_ms(); }
        ok = lin ? std::rocm_linear_attention(q, k, v, o, heads, s, s, d, causal)
                 : std::rocm_flash_attention(q, k, v, o, heads, s, s, d, (float)0.0, causal);
        i = i + 1;
    }
    std::rocm_sync();
    ms = ok ? (now_ms() - ms) / (double)iters : -1.0;
    std::rocm_free(&q); std::rocm_free(&k); std::rocm_free(&v); std::rocm_free(&o);
    return ms;
}

static void flash_row(&Vec<float> host, unsigned long s, unsigned long d, int causal) {
    int iters = s >= 4096UL ? 5 : 25;
    unsigned long heads = BATCH * HEADS;
    double ms = time_case(host, heads, s, d, causal, 0, 2, iters);
    double flops = 4.0 * (double)heads * (double)s * (double)s * (double)d;
    if (causal) flops = flops / 2.0;
    unsafe {
        printf("[flash fp32 d%lu s%lu c%d] ms_per_call=%.4f tflops=%.2f\n", d, s, causal, ms,
               ms > 0.0 ? flops / (ms * 1.0e9) : 0.0);
    }
}

static void linear_row(&Vec<float> host, unsigned long s, unsigned long d, int causal) {
    int iters = s >= 16384UL ? 10 : 40;
    double ms = time_case(host, BATCH * HEADS, s, d, causal, 1, 3, iters);
    unsafe { printf("[linear fp32 d%lu s%lu c%d] ms_per_call=%.4f\n", d, s, causal, ms); }
}

int main() {
    if (!std::rocm_available()) { unsafe { printf("no ROCm GPU -- skipping\n"); } return 0; }
    unsafe { printf("device: %s\n", std::rocm_device_name()); }
    // One host buffer large enough for q, k and v of the largest case.
    unsigned long maxn = BATCH * HEADS * 16384UL * 64UL;
    struct Vec<float> host = std::vec_with_cap(3UL * maxn);
    unsigned long x = 88172645463325252UL;
    unsigned long i = 0UL;
    while (i < 3UL * maxn) {
        x = x ^ (x << 13); x = x ^ (x >> 7); x = x ^ (x << 17);   // xorshift64
        host.push((float)((double)(x >> 11) / 9007199254740992.0 - 0.5));
        i = i + 1UL;
    }
    unsigned long d = 64UL;
    while (d <= 128UL) {
        unsigned long s = 1024UL;
        while (s <= 4096UL) {
            int c = 0;
            while (c < 2) { flash_row(&host, s, d, c); c = c + 1; }
            s = s * 4UL;
        }
        d = d * 2UL;
    }
    unsigned long s2 = 4096UL;
    while (s2 <= 16384UL) {
        int c = 0;
        while (c < 2) { linear_row(&host, s2, 64UL, c); c = c + 1; }
        s2 = s2 * 4UL;
    }
    int c2 = 0;
    while (c2 < 2) { linear_row(&host, 4096UL, 128UL, c2); c2 = c2 + 1; }
    host.free();
    return 0;
}
