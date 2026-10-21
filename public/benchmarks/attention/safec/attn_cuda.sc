// Flash and linear attention on an NVIDIA GPU with std::ml's CUDA kernels
// (gpu_cuda_attention.h), the same shapes as ../pytorch/attn.py.
//
//   flash   batch 4, 16 heads, head dim 64 / 128, 1024 and 4096 tokens,
//           causal and not, fp16 / bf16 / fp32 (cuda_half_flash_attention,
//           cuda_flash_attention_ex), plus one grouped-query shape
//           (32 query heads over 8 kv heads, head dim 128, bf16, causal)
//   linear  batch 4, 16 heads, head dim 64 / 128, 4096 and 16384 tokens,
//           causal and not, fp32 and bf16 (cuda_linear_attention_ex /
//           cuda_half_linear_attention)
//
// Data stays on the GPU. Each row is the mean of a run of queued calls
// between two synchronizations, after warm-up calls (the first call of a
// variant compiles it). Flash rows also report TFLOPS (4 B H S^2 D, halved
// for causal).
//
// Build: safec attn_cuda.sc -I <SafeC> -O2 -o attn_cuda.ll
//        clang -O2 attn_cuda.ll libsafec_std.a -L/usr/local/cuda/lib64 \
//              -lcuda -lcublas -lcublasLt -lnvrtc -lm -lpthread -o attn_cuda
extern int printf(const char* fmt, ...);
#include <std/ml/gpu_cuda.h>
#include <std/ml/gpu_cuda_attention.h>
#include <std/collections/vec.h>
#include <std/time.h>

#define BATCH 4UL
#define HEADS 16UL

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

static struct CudaAttnShape shape(unsigned long heads, unsigned long kvHeads, unsigned long s, unsigned long d) {
    struct CudaAttnShape a;
    a.batch = BATCH; a.heads = heads; a.kvHeads = kvHeads; a.seqLen = s; a.kvLen = s; a.headDim = d; a.window = 0UL;
    return a;
}

static const char* dtype_name(int dtype) {
    if (dtype == CUDA_FP16) return "fp16";
    if (dtype == CUDA_BF16) return "bf16";
    return "fp32";
}

// Uploads q/k/v/out for 'a' from 'host' (three disjoint slices) in 'dtype',
// runs 'iters' calls of flash (lin = 0) or linear (lin = 1) attention after
// 'warm' warm-up calls, and returns ms per call (-1 on failure).
static double time_case(&Vec<float> host, struct CudaAttnShape a, int dtype, int causal, int lin,
                        int warm, int iters) {
    unsigned long qn = a.batch * a.heads * a.seqLen * a.headDim;
    unsigned long kn = a.batch * a.kvHeads * a.kvLen * a.headDim;
    double ms = -1.0;
    if (dtype == 0) {
        struct CudaBuf q = CudaBuf::upload(host.at(0UL), qn);
        struct CudaBuf k = CudaBuf::upload(host.at(qn), kn);
        struct CudaBuf v = CudaBuf::upload(host.at(qn + kn), kn);
        struct CudaBuf o = CudaBuf::alloc(qn);
        int ok = 1;
        int i = 0;
        while (i < warm + iters && ok) {
            if (i == warm) { std::cuda_sync(); ms = now_ms(); }
            ok = lin ? std::cuda_linear_attention_ex(q, k, v, o, a, causal)
                     : std::cuda_flash_attention_ex(q, k, v, o, a, (float)0.0, causal);
            i = i + 1;
        }
        ok = ok && std::cuda_sync();
        ms = ok ? (now_ms() - ms) / (double)iters : -1.0;
        q.free(); k.free(); v.free(); o.free();
    } else {
        struct CudaHalfBuf q = CudaHalfBuf::upload(host.at(0UL), qn, dtype);
        struct CudaHalfBuf k = CudaHalfBuf::upload(host.at(qn), kn, dtype);
        struct CudaHalfBuf v = CudaHalfBuf::upload(host.at(qn + kn), kn, dtype);
        struct CudaHalfBuf o = CudaHalfBuf::alloc(qn, dtype);
        int ok = 1;
        int i = 0;
        while (i < warm + iters && ok) {
            if (i == warm) { std::cuda_sync(); ms = now_ms(); }
            ok = lin ? std::cuda_half_linear_attention(q, k, v, o, a, causal)
                     : std::cuda_half_flash_attention(q, k, v, o, a, (float)0.0, causal);
            i = i + 1;
        }
        ok = ok && std::cuda_sync();
        ms = ok ? (now_ms() - ms) / (double)iters : -1.0;
        q.free(); k.free(); v.free(); o.free();
    }
    return ms;
}

static void flash_row(&Vec<float> host, struct CudaAttnShape a, int dtype, int causal, const char* tag) {
    int iters = a.seqLen >= 4096UL ? 20 : 100;
    if (dtype == 0) iters = iters / 4;
    double ms = time_case(host, a, dtype, causal, 0, 3, iters);
    double flops = 4.0 * (double)(a.batch * a.heads) * (double)a.seqLen * (double)a.kvLen * (double)a.headDim;
    if (causal) flops = flops / 2.0;
    unsafe {
        printf("[flash %s %sd%lu s%lu c%d] ms_per_call=%.4f tflops=%.2f\n", dtype_name(dtype), tag, a.headDim,
               a.seqLen, causal, ms, ms > 0.0 ? flops / (ms * 1.0e9) : 0.0);
    }
}

static void linear_row(&Vec<float> host, struct CudaAttnShape a, int dtype, int causal) {
    int iters = a.seqLen >= 16384UL ? 10 : 40;
    double ms = time_case(host, a, dtype, causal, 1, 3, iters);
    unsafe {
        printf("[linear %s d%lu s%lu c%d] ms_per_call=%.4f\n", dtype_name(dtype), a.headDim, a.seqLen, causal, ms);
    }
}

int main() {
    if (!std::cuda_available()) { unsafe { printf("no CUDA GPU -- skipping\n"); } return 0; }
    unsafe { printf("device: %s\n", std::cuda_device_name()); }
    // One host buffer large enough for q, k and v of the largest case.
    unsigned long maxn = BATCH * HEADS * 16384UL * 64UL;   // = 4 x 32 x 4096 x 128, the GQA query
    struct Vec<float> host = std::Vec::with_cap(3UL * maxn);
    unsigned long x = 88172645463325252UL;
    unsigned long i = 0UL;
    while (i < 3UL * maxn) {
        x = x ^ (x << 13); x = x ^ (x >> 7); x = x ^ (x << 17);   // xorshift64
        host.push((float)((double)(x >> 11) / 9007199254740992.0 - 0.5));
        i = i + 1UL;
    }
    int dt[3];
    dt[0] = CUDA_FP16; dt[1] = CUDA_BF16; dt[2] = 0;
    int t = 0;
    while (t < 3) {
        unsigned long d = 64UL;
        while (d <= 128UL) {
            unsigned long s = 1024UL;
            while (s <= 4096UL) {
                int c = 0;
                while (c < 2) { flash_row(&host, shape(HEADS, HEADS, s, d), dt[t], c, ""); c = c + 1; }
                s = s * 4UL;
            }
            d = d * 2UL;
        }
        t = t + 1;
    }
    flash_row(&host, shape(32UL, 8UL, 4096UL, 128UL), CUDA_BF16, 1, "gqa32/8 ");
    int lt = 0;
    while (lt < 2) {
        int ldt = lt == 0 ? 0 : CUDA_BF16;
        unsigned long s = 4096UL;
        while (s <= 16384UL) {
            int c = 0;
            while (c < 2) { linear_row(&host, shape(HEADS, HEADS, s, 64UL), ldt, c); c = c + 1; }
            s = s * 4UL;
        }
        int c2 = 0;
        while (c2 < 2) { linear_row(&host, shape(HEADS, HEADS, 4096UL, 128UL), ldt, c2); c2 = c2 + 1; }
        lt = lt + 1;
    }
    host.free();
    return 0;
}
