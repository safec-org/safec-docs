// Flash and linear attention on an Apple GPU with std::ml's Metal kernels
// (gpu_mps.h), the same shapes as attn_cuda.sc, ../pytorch/attn.py and
// ../mlx/attn.py.
//
//   flash   batch 4, 16 heads, head dim 64 / 128, 1024 and 4096 tokens,
//           causal and not, fp16 / bf16 / fp32 (mps_flash_attention_ex),
//           plus one grouped-query shape (32 query heads over 8 kv heads,
//           head dim 128, bf16, causal)
//   linear  batch 4, 16 heads, head dim 64 / 128, 4096 and 16384 tokens,
//           causal and not, fp32 and bf16 (mps_linear_attention_ex)
//
// Data stays on the GPU. Each row is the mean of a run of calls queued in
// one batch (one command buffer, one wait), after warm-up calls. Flash rows
// also report TFLOPS (4 B H S^2 D, halved for causal).
extern int printf(const char* fmt, ...);
#include <std/ml/gpu_mps.h>
#include <std/collections/vec.h>
#include <std/time.h>

#define BATCH 4UL
#define HEADS 16UL

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

static struct MpsAttnShape shape(unsigned long heads, unsigned long kvHeads, unsigned long s, unsigned long d) {
    struct MpsAttnShape a;
    a.batch = BATCH; a.heads = heads; a.kvHeads = kvHeads; a.seqLen = s; a.kvLen = s; a.headDim = d; a.window = 0UL;
    return a;
}

static const char* dtype_name(int dtype) {
    if (dtype == MPS_FP16) return "fp16";
    if (dtype == MPS_BF16) return "bf16";
    return "fp32";
}

static void* upload(&Vec<float> host, unsigned long at, unsigned long n, int dtype) {
    unsafe {
        if (dtype == MPS_FP16) return (void*)std::mps_upload_f16_persistent(host.at(at), n);
        if (dtype == MPS_BF16) return (void*)std::mps_upload_bf16_persistent(host.at(at), n);
        return (void*)std::mps_upload_persistent(host.at(at), n * 4UL);
    }
}

// Uploads q/k/v/out for 'a' from 'host' (three disjoint slices) in 'dtype',
// runs 'warm' warm-up calls, then 'iters' calls of flash (lin = 0) or
// linear (lin = 1) attention in one batch, and returns ms per call (-1 on
// failure).
static double time_case(&Vec<float> host, struct MpsAttnShape a, int dtype, int causal, int lin,
                        int warm, int iters) {
    unsigned long qn = a.batch * a.heads * a.seqLen * a.headDim;
    unsigned long kn = a.batch * a.kvHeads * a.kvLen * a.headDim;
    unsigned long es = dtype == 0 ? 4UL : 2UL;
    void* q = upload(host, 0UL, qn, dtype);
    void* k = upload(host, qn, kn, dtype);
    void* v = upload(host, qn + kn, kn, dtype);
    void* o = (void*)0;
    unsafe { o = (void*)std::mps_alloc_persistent(qn * es); }
    int ok = q != (void*)0 && k != (void*)0 && v != (void*)0 && o != (void*)0;
    double ms = -1.0;
    int round = 0;
    while (round < 2 && ok) {
        int n = round == 0 ? warm : iters;
        double t0 = now_ms();
        std::mps_batch_begin();
        int i = 0;
        while (i < n && ok) {
            unsafe {
                ok = lin ? std::mps_linear_attention_ex(q, k, v, o, a, dtype, causal)
                         : std::mps_flash_attention_ex(q, k, v, o, a, dtype, (float)0.0, causal);
            }
            i = i + 1;
        }
        std::mps_batch_end();
        if (round == 1) ms = (now_ms() - t0) / (double)iters;
        round = round + 1;
    }
    unsafe {
        if (q != (void*)0) std::mps_release_persistent(q, qn * es);
        if (k != (void*)0) std::mps_release_persistent(k, kn * es);
        if (v != (void*)0) std::mps_release_persistent(v, kn * es);
        if (o != (void*)0) std::mps_release_persistent(o, qn * es);
    }
    return ok ? ms : -1.0;
}

static void flash_row(&Vec<float> host, struct MpsAttnShape a, int dtype, int causal, const char* tag) {
    int iters = a.seqLen >= 4096UL ? 10 : 40;
    if (dtype == 0) iters = iters / 2;
    double ms = time_case(host, a, dtype, causal, 0, 3, iters);
    double flops = 4.0 * (double)(a.batch * a.heads) * (double)a.seqLen * (double)a.kvLen * (double)a.headDim;
    if (causal) flops = flops / 2.0;
    unsafe {
        printf("[flash %s %sd%lu s%lu c%d] ms_per_call=%.4f tflops=%.2f\n", dtype_name(dtype), tag, a.headDim,
               a.seqLen, causal, ms, ms > 0.0 ? flops / (ms * 1.0e9) : 0.0);
    }
}

static void linear_row(&Vec<float> host, struct MpsAttnShape a, int dtype, int causal) {
    int iters = a.seqLen >= 16384UL ? 5 : 20;
    double ms = time_case(host, a, dtype, causal, 1, 2, iters);
    unsafe {
        printf("[linear %s d%lu s%lu c%d] ms_per_call=%.4f\n", dtype_name(dtype), a.headDim, a.seqLen, causal, ms);
    }
}

int main() {
    if (!std::mps_available()) { unsafe { printf("no Metal GPU -- skipping\n"); } return 0; }
    // One host buffer large enough for q, k and v of the largest case.
    unsigned long maxn = BATCH * HEADS * 16384UL * 64UL;   // = 4 x 32 x 4096 x 128, the GQA query
    struct Vec<float> host = std::vec_with_cap(3UL * maxn);
    unsigned long x = 88172645463325252UL;
    unsigned long i = 0UL;
    while (i < 3UL * maxn) {
        x = x ^ (x << 13); x = x ^ (x >> 7); x = x ^ (x << 17);   // xorshift64
        host.push((float)((double)(x >> 11) / 9007199254740992.0 - 0.5));
        i = i + 1UL;
    }
    int dt[3];
    dt[0] = MPS_FP16; dt[1] = MPS_BF16; dt[2] = 0;
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
    flash_row(&host, shape(32UL, 8UL, 4096UL, 128UL), MPS_BF16, 1, "gqa32/8 ");
    int lt = 0;
    while (lt < 2) {
        int ldt = lt == 0 ? 0 : MPS_BF16;
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
