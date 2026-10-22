// Gemma 4 E2B decode kernels (bf16 storage, fp32 math). NVRTC-compatible: no includes.
// Activations are rounded to bf16 at the same points as the PyTorch bf16 model.
// Every kernel reads the decode position from st->pos (device memory), so a
// whole token step can be captured once as a CUDA graph and replayed.

#define FULL 0xffffffffu
typedef unsigned short bf;

struct G4State {           // device-resident step state
    int pos;               // position of the token being processed
    int promptLen;         // tokens[0..promptLen) are the prompt
    int maxSeq;
    int done;
};

// Programmatic dependent launch (sm_90): a kernel lets the next one start launching right
// away and waits for its predecessor's results only before touching them. Everything a
// kernel does before PDL_WAIT reads only immutable data (weights).
#define PDL_LAUNCH() asm volatile("griddepcontrol.launch_dependents;")
#define PDL_WAIT() asm volatile("griddepcontrol.wait;" ::: "memory")
__device__ __forceinline__ void pf_l2(const void* p) { asm volatile("prefetch.global.L2 [%0];" :: "l"(p)); }
__device__ __forceinline__ float b2f(bf b) { return __uint_as_float(((unsigned)b) << 16); }
__device__ __forceinline__ bf f2b(float f) {
    unsigned u = __float_as_uint(f);
    if ((u & 0x7fffffffu) > 0x7f800000u) return (bf)((u >> 16) | 0x40u);
    u += 0x7fffu + ((u >> 16) & 1u);
    return (bf)(u >> 16);
}
__device__ __forceinline__ float rb(float f) { return b2f(f2b(f)); }   // round through bf16
__device__ __forceinline__ float gelu_tanh(float x) {
    const float k0 = 0.7978845608028654f, k1 = 0.044715f;
    return 0.5f * x * (1.0f + tanhf(k0 * (x + k1 * x * x * x)));
}
__device__ __forceinline__ float warp_sum(float v) {
#pragma unroll
    for (int o = 16; o > 0; o >>= 1) v += __shfl_xor_sync(FULL, v, o);
    return v;
}
// Block-wide sum (blockDim.x multiple of 32, <= 1024); 'red' is >= 32 floats of shared memory.
__device__ float block_sum(float v, float* red) {
    const int lane = threadIdx.x & 31, w = threadIdx.x >> 5, nw = blockDim.x >> 5;
    v = warp_sum(v);
    __syncthreads();
    if (lane == 0) red[w] = v;
    __syncthreads();
    float t = threadIdx.x < nw ? red[threadIdx.x] : 0.0f;
    if (w == 0) t = warp_sum(t);
    if (threadIdx.x == 0) red[0] = t;
    __syncthreads();
    float r = red[0];
    __syncthreads();
    return r;
}
// Gemma4RMSNorm of n values (fp32 in 'v', strided by blockDim.x) -> 1/rms
__device__ float rms_inv(const float* v, int n, float* red) {
    float s = 0.0f;
    for (int i = threadIdx.x; i < n; i += blockDim.x) s += v[i] * v[i];
    s = block_sum(s, red);
    return rsqrtf(s / (float)n + 1e-6f);
}

// ── Embedding: x = embed[tok] * bf16(sqrt(1536)); pleTok = embedPle[tok] * 16 ──────────
extern "C" __global__ void g4_embed(const G4State* st, const int* tokens, const bf* __restrict__ embW,
                                    const bf* __restrict__ pleW, bf* x, bf* pleTok, int H, int PLE) {
    PDL_LAUNCH(); PDL_WAIT();
    const int tok = tokens[st->pos];
    const float es = rb(sqrtf((float)H));
    for (int i = blockIdx.x * blockDim.x + threadIdx.x; i < H + PLE; i += gridDim.x * blockDim.x) {
        if (i < H) x[i] = f2b(b2f(embW[(size_t)tok * H + i]) * es);
        else { int j = i - H; pleTok[j] = f2b(b2f(pleW[(size_t)tok * PLE + j]) * 16.0f); }
    }
}

// ── PLE combine: per layer l (block), proj = rmsnorm(bf16(raw * 1/sqrt(H))) * w;
//    ple[l] = bf16(bf16(proj + pleTok[l]) * 2^-0.5) ─────────────────────────────────────
extern "C" __global__ void g4_ple_combine(const bf* raw, const bf* pleTok, const bf* __restrict__ normW, bf* ple,
                                          int D, float projScale) {
    PDL_LAUNCH(); PDL_WAIT();
    extern __shared__ float sm[];
    float* v = sm; float* red = sm + D;
    const int l = blockIdx.x;
    for (int i = threadIdx.x; i < D; i += blockDim.x) v[i] = rb(b2f(raw[l * D + i]) * projScale);
    __syncthreads();
    float inv = rms_inv(v, D, red);
    for (int i = threadIdx.x; i < D; i += blockDim.x) {
        float p = rb(v[i] * inv * b2f(normW[i]));
        ple[l * D + i] = f2b(rb(p + b2f(pleTok[l * D + i])) * 0.70710678118654752f);
    }
}

// ── GEMV: y[n] = W[n,:] . xs, W bf16 [N, K] row-major, K % 256 == 0, xs bf16 [K] ─────────
// One row per warp, four 16-byte loads in flight per lane. Epilogue:
//   emode 0: y[n] = bf16(acc)
//   emode 1: gate/up: rows n and n + N of W; y[n] = bf16(bf16(gelu(bf16 g)) * bf16 u)
//   emode 2: y[n] = bf16(bf16(gelu(bf16 acc)) * m[n])
//   emode 3: final softcap: y[n] = bf16(bf16(tanh(bf16(bf16(acc) / c))) * c), and the
//            running argmax (first index of the maximum) in *best as one 64-bit atomicMax
// Finisher (fmode 1): the last block to finish (a grid-wide counter) folds y into the
// residual stream and prepares the next GEMV's input, 'fin' below; H = N of this GEMV.
struct Finish {
    const bf* x; const bf* wd; const bf* scal; bf* xo; const bf* w1; bf* xs; int* counter; int useDelta;
};
struct GemvArgs {
    const bf* W; int N; int K; const bf* xs; const bf* m; bf* y; float cap; unsigned long long* best;
};
__device__ __forceinline__ uint4 ldw(const bf* p) {
    uint4 w;
    asm volatile("ld.global.nc.L1::no_allocate.v4.u32 {%0,%1,%2,%3}, [%4];" : "=r"(w.x), "=r"(w.y), "=r"(w.z), "=r"(w.w) : "l"(p));
    return w;
}
__device__ __forceinline__ float dot8(uint4 wv, uint4 xv) {
    const unsigned* xp = (const unsigned*)&xv; const unsigned* wp = (const unsigned*)&wv;
    float s = 0.0f;
#pragma unroll
    for (int e = 0; e < 4; ++e) {
        s += __uint_as_float(wp[e] << 16) * __uint_as_float(xp[e] << 16);
        s += __uint_as_float(wp[e] & 0xffff0000u) * __uint_as_float(xp[e] & 0xffff0000u);
    }
    return s;
}
// Residual step on the hidden vector (HID values), by one 128-thread block:
//   xn = useDelta ? bf16(x + bf16(rmsnorm(y) * wd)) : x;  if (scal) xn = bf16(xn * scal[0])
//   xo = xn (if xo);  xs = w1 ? bf16(rmsnorm(xn) * w1) : xn
// All loads are issued up front (HID / 128 values per thread in registers): the inputs come
// from global memory, and loading them one dependent step at a time would serialize.
#define HID 1536
#define FPT (HID / 128)
__device__ float block_sum128(float v, float* red) {
    v = warp_sum(v);
    if ((threadIdx.x & 31) == 0) red[threadIdx.x >> 5] = v;
    __syncthreads();
    float r = red[0] + red[1] + red[2] + red[3];
    __syncthreads();
    return r;
}
__device__ void finish_block(const Finish& f, const bf* y, float* red) {
    const int tid = threadIdx.x;
    float yv[FPT], xv[FPT], wdv[FPT], w1v[FPT];
    const float sc = f.scal ? b2f(f.scal[0]) : 1.0f;
#pragma unroll
    for (int e = 0; e < FPT; ++e) {
        const int i = tid + 128 * e;
        xv[e] = b2f(__ldcg(f.x + i));
        yv[e] = f.useDelta ? b2f(__ldcg(y + i)) : 0.0f;
        wdv[e] = f.useDelta ? b2f(f.wd[i]) : 0.0f;
        w1v[e] = f.w1 ? b2f(f.w1[i]) : 0.0f;
    }
    if (f.useDelta) {
        float ss = 0.0f;
#pragma unroll
        for (int e = 0; e < FPT; ++e) ss += yv[e] * yv[e];
        const float inv = rsqrtf(block_sum128(ss, red) / (float)HID + 1e-6f);
#pragma unroll
        for (int e = 0; e < FPT; ++e) xv[e] = rb(xv[e] + rb(yv[e] * inv * wdv[e]));
    }
    if (f.scal) {
#pragma unroll
        for (int e = 0; e < FPT; ++e) xv[e] = rb(xv[e] * sc);
    }
    if (f.xo) {
#pragma unroll
        for (int e = 0; e < FPT; ++e) f.xo[tid + 128 * e] = f2b(xv[e]);
    }
    float inv2 = 1.0f;
    if (f.w1) {
        float ss = 0.0f;
#pragma unroll
        for (int e = 0; e < FPT; ++e) ss += xv[e] * xv[e];
        inv2 = rsqrtf(block_sum128(ss, red) / (float)HID + 1e-6f);
    }
#pragma unroll
    for (int e = 0; e < FPT; ++e) f.xs[tid + 128 * e] = f2b(f.w1 ? xv[e] * inv2 * w1v[e] : xv[e]);
}
__device__ bool last_block(int* counter) {
    __shared__ int amLast;
    __threadfence();
    __syncthreads();
    if (threadIdx.x == 0) {
        int t = atomicAdd(counter, 1);
        amLast = t == (int)(gridDim.x * gridDim.y) - 1;
        if (amLast) *counter = 0;
    }
    __syncthreads();
    if (amLast) __threadfence();
    return amLast;
}
// wpr > 1 (emode 0 only): wpr warps share each row, each summing every wpr-th 256-element
// slice of K, reduced through shared memory; the grid then covers every row exactly once.
__device__ __forceinline__ void gemv_body(const GemvArgs& a, const Finish& f, int emode, int fmode, int wpr);
// Flat parameters (no by-value structs) so hosts in other languages can launch it simply.
extern "C" __global__ void __launch_bounds__(128) g4_gemv(const bf* W, const bf* xs, const bf* m, bf* y, unsigned long long* best,
                                                          const bf* fx, const bf* fwd, const bf* fscal, bf* fxo, const bf* fw1,
                                                          bf* fxs, int* fcounter, int N, int K, float cap, int emode, int fmode,
                                                          int fuseDelta, int wpr) {
    GemvArgs a{W, N, K, xs, m, y, cap, best};
    Finish f{fx, fwd, fscal, fxo, fw1, fxs, fcounter, fuseDelta};
    gemv_body(a, f, emode, fmode, wpr);
}
__device__ __forceinline__ void gemv_body(const GemvArgs& a, const Finish& f, int emode, int fmode, int wpr) {
    extern __shared__ __align__(16) unsigned char smraw[];
    bf* xs = (bf*)smraw;                       // K bf16
    const int K = a.K, tid = threadIdx.x;
    PDL_LAUNCH();
    {   // this warp's first row into L2 (one 128-byte line per lane step) while the predecessor finishes
        const int nw0 = blockDim.x >> 5, w0 = tid >> 5, l0 = tid & 31;
        const int r0 = wpr > 1 ? blockIdx.x * (nw0 / wpr) + w0 / wpr : blockIdx.x * nw0 + w0;
        if (r0 < a.N && (wpr == 1 || w0 % wpr == 0))
            for (int k = l0 * 64; k < K; k += 32 * 64) {
                pf_l2(a.W + (size_t)r0 * K + k);
                if (emode == 1) pf_l2(a.W + (size_t)(r0 + a.N) * K + k);
            }
    }
    PDL_WAIT();
    for (int i = tid * 8; i < K; i += blockDim.x * 8) *(uint4*)(xs + i) = __ldcg((const uint4*)(a.xs + i));
    __syncthreads();
    const int warp = tid >> 5, lane = tid & 31, nw = blockDim.x >> 5;
    unsigned long long best = 0ull;
    if (wpr > 1) {
        __shared__ float part[4];
        const int n = blockIdx.x * (nw / wpr) + warp / wpr, sl = warp % wpr;
        float s0 = 0.0f;
        if (n < a.N) {
            const bf* w0 = a.W + (size_t)n * K;
            const int step = 256 * wpr;
            for (int k = sl * 256 + lane * 8; k < K; k += 4 * step) {
                uint4 wv[4];
#pragma unroll
                for (int u = 0; u < 4; ++u) if (k + u * step < K) wv[u] = ldw(w0 + k + u * step);
#pragma unroll
                for (int u = 0; u < 4; ++u) if (k + u * step < K) s0 += dot8(wv[u], *(const uint4*)(xs + k + u * step));
            }
        }
        s0 = warp_sum(s0);
        if (lane == 0) part[warp] = s0;
        __syncthreads();
        if (sl == 0 && lane == 0 && n < a.N) {
            float t = 0.0f;
            for (int i = 0; i < wpr; ++i) t += part[warp + i];
            a.y[n] = f2b(t);
        }
    } else
    for (int n = blockIdx.x * nw + warp; n < a.N; n += gridDim.x * nw) {
        const bf* w0 = a.W + (size_t)n * K;
        const bf* w1 = a.W + (size_t)(n + a.N) * K;
        float s0 = 0.0f, s1 = 0.0f;
        for (int k = lane * 8; k < K; k += 1024) {
            uint4 wv[4], wu[4];
#pragma unroll
            for (int u = 0; u < 4; ++u)
                if (k + u * 256 < K) { wv[u] = ldw(w0 + k + u * 256); if (emode == 1) wu[u] = ldw(w1 + k + u * 256); }
#pragma unroll
            for (int u = 0; u < 4; ++u)
                if (k + u * 256 < K) {
                    uint4 xv = *(const uint4*)(xs + k + u * 256);
                    s0 += dot8(wv[u], xv);
                    if (emode == 1) s1 += dot8(wu[u], xv);
                }
        }
        s0 = warp_sum(s0);
        if (emode == 1) s1 = warp_sum(s1);
        if (lane == 0) {
            float r;
            if (emode == 0) r = s0;
            else if (emode == 1) r = rb(gelu_tanh(rb(s0))) * rb(s1);
            else if (emode == 2) r = rb(gelu_tanh(rb(s0))) * b2f(a.m[n]);
            else r = rb(tanhf(rb(rb(s0) / a.cap))) * a.cap;
            bf o = f2b(r);
            a.y[n] = o;
            if (emode == 3) {
                unsigned u = ((unsigned)o) << 16;
                u = (u & 0x80000000u) ? ~u : (u | 0x80000000u);   // order-preserving key
                unsigned long long key = ((unsigned long long)u << 32) | (unsigned)(0xffffffffu - (unsigned)n);
                if (key > best) best = key;
            }
        }
    }
    if (emode == 3 && lane == 0 && best) atomicMax(a.best, best);
    if (fmode && last_block(f.counter)) {
        __shared__ float red[4];
        finish_block(f, a.y, red);
    }
}
// Standalone residual step (one block): used once per token before layer 0.
extern "C" __global__ void __launch_bounds__(128) g4_finish(const bf* fx, const bf* fw1, bf* fxs) {
    PDL_LAUNCH(); PDL_WAIT();
    __shared__ float red[4];
    Finish f{fx, 0, 0, 0, fw1, fxs, 0, 0};
    finish_block(f, 0, red);
}

// ── Q/K/V post-processing: per-head RMSNorm (q, k with weight; v without), RoPE, cache ──
// qkv: [8*hd q | hd k | hd v] (kv part absent when kvw == 0). One block per head (+2 for k, v).
// RoPE: inv_freq[j] = j < nrot ? theta^(-2j/hd) : 0 for j < hd/2, cos/sin rounded to bf16.
extern "C" __global__ void g4_qkv_post(const G4State* st, const bf* qkv, const bf* __restrict__ qnw,
                                       const bf* __restrict__ knw, bf* q, bf* kc, bf* vc, int nh, int hd,
                                       int hasKV, int nrot, float theta) {
    extern __shared__ float sm[];
    float* v = sm; float* red = sm + hd;
    const int b = blockIdx.x, pos = st->pos;
    if (b >= nh && !hasKV) return;
    const bf* src = qkv + (size_t)b * hd;
    for (int i = threadIdx.x; i < hd; i += blockDim.x) v[i] = b2f(src[i]);
    __syncthreads();
    float inv = rms_inv(v, hd, red);
    const bf* w = b < nh ? qnw : knw;
    for (int i = threadIdx.x; i < hd; i += blockDim.x) v[i] = rb(w && b <= nh ? v[i] * inv * b2f(w[i]) : v[i] * inv);
    __syncthreads();
    if (b == nh + 1) {   // v: no RoPE
        for (int i = threadIdx.x; i < hd; i += blockDim.x) vc[(size_t)pos * hd + i] = f2b(v[i]);
        return;
    }
    const int half = hd / 2;
    bf* dst = b < nh ? q + (size_t)b * hd : kc + (size_t)pos * hd;
    for (int i = threadIdx.x; i < hd; i += blockDim.x) {
        int j = i < half ? i : i - half;
        float invf = j < nrot ? powf(theta, -2.0f * (float)j / (float)hd) : 0.0f;
        float ang = (float)pos * invf;
        float c = rb(cosf(ang)), s = rb(sinf(ang));
        float rot = i < half ? -v[i + half] : v[i - half];
        dst[i] = f2b(rb(rb(v[i] * c) + rb(rot * s)));
    }
}

// ── Decode attention, split over keys: block (head, split) handles keys
//    [lo + split*CH, ...) of the valid range [lo, pos]; writes (max, sum, acc[hd]) ─────────
#ifndef ATT_CH
#define ATT_CH 32
#endif
// Combines head h's splits (max, sum, acc) into o[h]; run by that head's last block.
__device__ void attn_combine(const float* ph, int used, bf* oh, int hd) {
    float mx = -1e30f;
    for (int s = 0; s < used; ++s) mx = fmaxf(mx, __ldcg(ph + (size_t)s * (hd + 2)));
    float l = 0.0f;
    for (int s = 0; s < used; ++s) l += __ldcg(ph + (size_t)s * (hd + 2) + 1) * __expf(__ldcg(ph + (size_t)s * (hd + 2)) - mx);
    const float il = 1.0f / l;
    for (int i = threadIdx.x; i < hd; i += blockDim.x) {
        float a = 0.0f;
        for (int s = 0; s < used; ++s) {
            const float* r = ph + (size_t)s * (hd + 2);
            a += __ldcg(r + 2 + i) * __expf(__ldcg(r) - mx);
        }
        oh[i] = f2b(a * il);
    }
}
// Per-head RMSNorm (with weight w, or none) and RoPE of hd raw values src -> dst (bf16), by
// the calling block; 'v' is hd floats of shared memory, 'red' 4. RoPE inv_freq[j] =
// j < nrot ? theta^(-2j/hd) : 0 for j < hd/2, cos/sin rounded to bf16 as in PyTorch.
__device__ void norm_rope(const bf* src, const bf* w, bool rope, int pos, int hd, int nrot, float theta,
                          bf* dst, float* v, float* red) {
    float ss = 0.0f;
    for (int i = threadIdx.x; i < hd; i += blockDim.x) { float x = b2f(src[i]); v[i] = x; ss += x * x; }
    const float inv = rsqrtf(block_sum128(ss, red) / (float)hd + 1e-6f);
    for (int i = threadIdx.x; i < hd; i += blockDim.x) v[i] = rb(w ? v[i] * inv * b2f(w[i]) : v[i] * inv);
    __syncthreads();
    const int half = hd / 2;
    for (int i = threadIdx.x; i < hd; i += blockDim.x) {
        if (!rope) { dst[i] = f2b(v[i]); continue; }
        int j = i < half ? i : i - half;
        float invf = j < nrot ? powf(theta, -2.0f * (float)j / (float)hd) : 0.0f;
        float ang = (float)pos * invf;
        float c = rb(cosf(ang)), sn = rb(sinf(ang));
        float rot = i < half ? -v[i + half] : v[i - half];
        dst[i] = f2b(rb(rb(v[i] * c) + rb(rot * sn)));
    }
    __syncthreads();
}
// Block (head h, split sp) scores keys [lo + sp*CH, ...) of the valid range [lo, pos] (two
// threads per key, each summing half of the head dim), softmaxes them locally and writes
// (max, sum, acc[hd]); the last block of each head combines its splits. Each block applies
// q_norm and RoPE to its head's raw q itself. In a layer with its own K/V (hasKV), the block
// holding position pos also normalizes and ropes k / normalizes v for pos from the raw
// projection, uses them from shared memory, and (head 0) writes them to the cache.
extern "C" __global__ void __launch_bounds__(128) g4_attn(const G4State* st, const bf* qkv, const bf* __restrict__ qnw,
                                                          const bf* __restrict__ knw, bf* kc, bf* vc, float* part, bf* o,
                                                          int* counters, int nh, int hd, int window, int hasKV, int nrot, float theta) {
    PDL_LAUNCH(); PDL_WAIT();
    extern __shared__ __align__(16) float sm[];
    float* p = sm;                        // ATT_CH probabilities
    float* v = p + ATT_CH;                // hd scratch
    bf* qs = (bf*)(v + hd);               // hd
    bf* kn = qs + hd;                     // hd (current key, hasKV)
    bf* vn = kn + hd;                     // hd (current value, hasKV)
    __shared__ float red[4];
    const int h = blockIdx.x, sp = blockIdx.y, nsp = gridDim.y, pos = st->pos;
    const int lo = window > 0 ? max(0, pos - window + 1) : 0;
    const int used = min(nsp, (pos - lo) / ATT_CH + 1);
    const int k0 = lo + sp * ATT_CH, k1 = min(pos + 1, k0 + ATT_CH), n = k1 - k0;
    float* out = part + ((size_t)h * nsp + sp) * (hd + 2);
    if (sp < used) {
        norm_rope(qkv + (size_t)h * hd, qnw, true, pos, hd, nrot, theta, qs, v, red);
        const bool cur = hasKV && sp == used - 1;      // this split ends at pos
        if (cur) {
            norm_rope(qkv + (size_t)nh * hd, knw, true, pos, hd, nrot, theta, kn, v, red);
            norm_rope(qkv + (size_t)(nh + 1) * hd, 0, false, pos, hd, nrot, theta, vn, v, red);
            if (h == 0)
                for (int i = threadIdx.x * 8; i < hd; i += blockDim.x * 8) {
                    *(uint4*)(kc + (size_t)pos * hd + i) = *(const uint4*)(kn + i);
                    *(uint4*)(vc + (size_t)pos * hd + i) = *(const uint4*)(vn + i);
                }
        }
        {
            const int j = threadIdx.x >> 1, half = threadIdx.x & 1, hh = hd / 2;
            float s = 0.0f;
            if (j < n) {
                const bf* kr = (cur && k0 + j == pos ? kn : kc + (size_t)(k0 + j) * hd) + half * hh;
                const bf* qr = qs + half * hh;
#pragma unroll 8
                for (int i = 0; i < hh; i += 8) s += dot8(*(const uint4*)(kr + i), *(const uint4*)(qr + i));
            }
            s += __shfl_xor_sync(FULL, s, 1);
            if (j < n && half == 0) p[j] = s;
        }
        __syncthreads();
        float mx = -1e30f;
        for (int j = 0; j < n; ++j) mx = fmaxf(mx, p[j]);
        __syncthreads();
        if (threadIdx.x < n) p[threadIdx.x] = __expf(p[threadIdx.x] - mx);
        __syncthreads();
        const int nc = cur ? n - 1 : n;                // the current value is added from shared memory
        for (int i = threadIdx.x * 2; i < hd; i += blockDim.x * 2) {
            float a0 = 0.0f, a1 = 0.0f;
            const bf* vp = vc + (size_t)k0 * hd + i;
#pragma unroll 8
            for (int j = 0; j < nc; ++j) {
                unsigned u = *(const unsigned*)(vp + (size_t)j * hd);
                a0 += p[j] * __uint_as_float(u << 16); a1 += p[j] * __uint_as_float(u & 0xffff0000u);
            }
            if (cur) { unsigned u = *(const unsigned*)(vn + i); a0 += p[n - 1] * __uint_as_float(u << 16); a1 += p[n - 1] * __uint_as_float(u & 0xffff0000u); }
            out[2 + i] = a0; out[3 + i] = a1;
        }
        if (threadIdx.x == 0) {
            float l = 0.0f;
            for (int j = 0; j < n; ++j) l += p[j];
            out[0] = mx; out[1] = l;
        }
    }
    // per-head completion count over all nsp blocks of head h
    __shared__ int amLast;
    __threadfence();
    __syncthreads();
    if (threadIdx.x == 0) {
        int t = atomicAdd(counters + h, 1);
        amLast = t == nsp - 1;
        if (amLast) counters[h] = 0;
    }
    __syncthreads();
    if (amLast) { __threadfence(); attn_combine(part + (size_t)h * nsp * (hd + 2), used, o + (size_t)h * hd, hd); }
}

// ── Advance: the next token is the prompt's or the argmax the LM-head GEMV left in *best ──
extern "C" __global__ void g4_advance(G4State* st, unsigned long long* best, int* tokens) {
    PDL_LAUNCH(); PDL_WAIT();
    const int idx = (int)(0xffffffffu - (unsigned)(*best & 0xffffffffull));
    *best = 0ull;
    const int np = st->pos + 1;
    if (np >= st->promptLen && np < st->maxSeq) tokens[np] = idx;
    if (np < st->maxSeq) st->pos = np; else st->done = 1;
}
