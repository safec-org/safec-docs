// Gemma 4 E2B decode kernels for ROCm (compiled at run time with hiprtc;
// gemma4_rocm.sc embeds this file as a string -- regenerate it with
// tools/embed_hip.py after editing). One decode step = one token, M = 1.
// Weights are bf16 [N][K] row-major (y = W x); activations are fp32. The
// token id and position live in device memory (tok[0], pos[0]) so a whole
// step can be recorded as a HIP graph once and replayed.
typedef unsigned short bf16;
__device__ __forceinline__ float bfu(unsigned v) { return __uint_as_float(v << 16); }
__device__ __forceinline__ float bfs(bf16 v) { return __uint_as_float(((unsigned)v) << 16); }

__device__ __forceinline__ float wave_sum(float a) {
    a += __shfl_xor(a, 32); a += __shfl_xor(a, 16); a += __shfl_xor(a, 8);
    a += __shfl_xor(a, 4); a += __shfl_xor(a, 2); a += __shfl_xor(a, 1);
    return a;
}
// Sum over a 256-thread block; every thread gets the result.
// Sum over the block (blockDim.x a multiple of 64, at most 1024; 'red'
// holds 16 floats); every thread gets the result.
__device__ __forceinline__ float block_sum(float a, float* red) {
    a = wave_sum(a);
    const int lane = threadIdx.x & 63, wave = threadIdx.x >> 6, nw = blockDim.x >> 6;
    __syncthreads();
    if (lane == 0) red[wave] = a;
    __syncthreads();
    float t = 0.f;
    for (int w = 0; w < nw; ++w) t += red[w];
    return t;
}
__device__ __forceinline__ float gelu_tanh(float x) {
    return 0.5f * x * (1.0f + tanhf(0.7978845608028654f * (x + 0.044715f * x * x * x)));
}

// y[n] = alpha * sum_k W[n][k] x[k]: one wave per row, 16-byte weight loads.
// K must be a multiple of 8.
extern "C" __global__ __launch_bounds__(256) void g4_gemv(const bf16* __restrict__ W, const float* __restrict__ x,
                                                          float* __restrict__ y, int N, int K, float alpha) {
    const int lane = threadIdx.x & 63;
    const int row = blockIdx.x * 4 + (threadIdx.x >> 6);
    if (row >= N) return;
    const bf16* w = W + (size_t)row * K;
    float a = 0.f;
#pragma unroll 2
    for (int k = lane * 8; k < K; k += 512) {
        float4 x0 = *(const float4*)(x + k), x1 = *(const float4*)(x + k + 4);
        uint4 v = *(const uint4*)(w + k);
        a = fmaf(bfu(v.x & 0xffffu), x0.x, a); a = fmaf(bfu(v.x >> 16), x0.y, a);
        a = fmaf(bfu(v.y & 0xffffu), x0.z, a); a = fmaf(bfu(v.y >> 16), x0.w, a);
        a = fmaf(bfu(v.z & 0xffffu), x1.x, a); a = fmaf(bfu(v.z >> 16), x1.y, a);
        a = fmaf(bfu(v.w & 0xffffu), x1.z, a); a = fmaf(bfu(v.w >> 16), x1.w, a);
    }
    a = wave_sum(a);
    if (lane == 0) y[row] = a * alpha;
}

// GEMV epilogue variants (x read from global like g4_gemv, one wave per unit):
//   g4_gemv_geglu:    rows come in (gate, up) pairs, interleaved at upload;
//                     y[p] = gelu(gate_p . x) * (up_p . x); N = pairs
//   g4_gemv_gelu_vec: y[row] = gelu(W[row] . x) * ev[row]
template <int EPI>
__device__ __forceinline__ void gemv_epi(const bf16* __restrict__ W, const float* __restrict__ x, float* __restrict__ y,
                                         int N, int K, const float* __restrict__ ev) {
    constexpr int RW = EPI == 1 ? 2 : 1;
    const int lane = threadIdx.x & 63;
    const int unit = blockIdx.x * 4 + (threadIdx.x >> 6);
    if (unit >= N) return;
    float acc[RW];
#pragma unroll
    for (int q = 0; q < RW; ++q) acc[q] = 0.f;
#pragma unroll 2
    for (int k = lane * 8; k < K; k += 512) {
        const float4 x0 = *(const float4*)(x + k), x1 = *(const float4*)(x + k + 4);
#pragma unroll
        for (int q = 0; q < RW; ++q) {
            const uint4 v = *(const uint4*)(W + ((size_t)unit * RW + q) * K + k);
            float a = acc[q];
            a = fmaf(bfu(v.x & 0xffffu), x0.x, a); a = fmaf(bfu(v.x >> 16), x0.y, a);
            a = fmaf(bfu(v.y & 0xffffu), x0.z, a); a = fmaf(bfu(v.y >> 16), x0.w, a);
            a = fmaf(bfu(v.z & 0xffffu), x1.x, a); a = fmaf(bfu(v.z >> 16), x1.y, a);
            a = fmaf(bfu(v.w & 0xffffu), x1.z, a); a = fmaf(bfu(v.w >> 16), x1.w, a);
            acc[q] = a;
        }
    }
#pragma unroll
    for (int q = 0; q < RW; ++q) acc[q] = wave_sum(acc[q]);
    if (lane == 0) {
        if (EPI == 1) y[unit] = gelu_tanh(acc[0]) * acc[RW - 1];
        else y[unit] = gelu_tanh(acc[0]) * ev[unit];
    }
}
extern "C" __global__ __launch_bounds__(256) void g4_gemv_geglu(const bf16* W, const float* x, float* y, int N, int K) {
    gemv_epi<1>(W, x, y, N, K, 0);
}
extern "C" __global__ __launch_bounds__(256) void g4_gemv_gelu_vec(const bf16* W, const float* x, float* y, int N, int K,
                                                                   const float* ev) {
    gemv_epi<2>(W, x, y, N, K, ev);
}

// Residual + norms between sublayers over the hidden state (n <= PER *
// blockDim), done by one block with the values in registers:
//   t = (x + rmsnorm(r) * wr) * scale     (r == 0: t = x * scale)
//   x = t;  h = rmsnorm(t) * wn           (wn == 0: no h)
template <int PER>
__device__ void resnorm_block(float* x, const float* r, const bf16* wr, float scale, const bf16* wn, float* h,
                              int n, float eps, float* red) {
    float t[PER], rv[PER];
    float ss = 0.f;
#pragma unroll
    for (int u = 0; u < PER; ++u) {
        const int i = threadIdx.x + u * blockDim.x;
        t[u] = i < n ? x[i] : 0.f;
        rv[u] = (r && i < n) ? r[i] : 0.f;
        ss = fmaf(rv[u], rv[u], ss);
    }
    if (r) {
        const float inv = rsqrtf(block_sum(ss, red) / (float)n + eps);
#pragma unroll
        for (int u = 0; u < PER; ++u) {
            const int i = threadIdx.x + u * blockDim.x;
            if (i < n) t[u] = (t[u] + rv[u] * inv * bfs(wr[i])) * scale;
        }
    } else {
#pragma unroll
        for (int u = 0; u < PER; ++u) t[u] *= scale;
    }
    float s2 = 0.f;
#pragma unroll
    for (int u = 0; u < PER; ++u) {
        const int i = threadIdx.x + u * blockDim.x;
        if (i < n) { x[i] = t[u]; s2 = fmaf(t[u], t[u], s2); }
    }
    if (wn) {
        const float inv = rsqrtf(block_sum(s2, red) / (float)n + eps);
#pragma unroll
        for (int u = 0; u < PER; ++u) {
            const int i = threadIdx.x + u * blockDim.x;
            if (i < n) h[i] = t[u] * inv * bfs(wn[i]);
        }
    }
}

// Residual step between sublayers as its own one-block kernel (blockDim 512):
// x = (x + rmsnorm(r) * wr) * scale; h = rmsnorm(x) * wn (wn == 0: no h).
// (Doing this in the last block of the GEMV that produces 'r' measured no
// faster: the cost is the dependent-kernel boundary, not the launch.)
extern "C" __global__ __launch_bounds__(512) void g4_resnorm(float* x, const float* __restrict__ r,
                                                             const bf16* __restrict__ wr, float scale,
                                                             const bf16* __restrict__ wn, float* __restrict__ h,
                                                             int n, float eps) {
    __shared__ float red[16];
    resnorm_block<3>(x, r, wr, scale, wn, h, n, eps, red);
}

// x = E[tok] * scale and h = rmsnorm(x) * wn (layer 0's input norm); one block.
extern "C" __global__ __launch_bounds__(256) void g4_embed(const bf16* __restrict__ E, const int* __restrict__ tok,
                                                           float scale, float* __restrict__ x, int n,
                                                           const bf16* __restrict__ wn, float* __restrict__ h, float eps) {
    __shared__ float red[16];
    const bf16* e = E + (size_t)tok[0] * n;
    for (int i = threadIdx.x; i < n; i += 256) x[i] = bfs(e[i]) * scale;
    resnorm_block<6>(x, 0, 0, 1.0f, wn, h, n, eps, red);
}

// After the fused q|k|v projection: per head RMSNorm (q_norm / k_norm),
// RoPE on q and k, v_norm on v; k and v go into the layer's cache at row
// pos. Block b < nq: query head b (in place in qkv); b == nq: k; b == nq+1: v.
// RoPE pairs (i, i + hd/2); full-attention layers rotate only the first 64
// pairs with theta 1e6 (proportional RoPE, partial factor 0.25), sliding
// layers all pairs with theta 1e4. Without kv (shared layers) grid = nq.
extern "C" __global__ __launch_bounds__(256) void g4_qkv_post(float* __restrict__ qkv, const bf16* __restrict__ qn,
                                                              const bf16* __restrict__ kn, float* __restrict__ kc,
                                                              float* __restrict__ vc, const int* __restrict__ posp,
                                                              int hd, int nq, int full, float eps) {
    __shared__ float red[16];
    __shared__ float buf[512];
    const int b = blockIdx.x, pos = posp[0];
    float* src = qkv + (size_t)b * hd;
    float s = 0.f;
    for (int i = threadIdx.x; i < hd; i += 256) { float v = src[i]; buf[i] = v; s = fmaf(v, v, s); }
    const float inv = rsqrtf(block_sum(s, red) / (float)hd + eps);   // block_sum syncs, so buf is complete
    if (b == nq + 1) {                                                 // v: norm without weight, no RoPE
        for (int i = threadIdx.x; i < hd; i += 256) vc[(size_t)pos * hd + i] = buf[i] * inv;
        return;
    }
    const bf16* w = b < nq ? qn : kn;
    for (int i = threadIdx.x; i < hd; i += 256) buf[i] = buf[i] * inv * bfs(w[i]);
    __syncthreads();
    const int half = hd / 2;
    float* out = b < nq ? src : kc + (size_t)pos * hd;
    for (int i = threadIdx.x; i < hd; i += 256) {
        const int j = i < half ? i : i - half;
        float invf = 0.f;
        if (full) { if (j < 64) invf = 1.0f / powf(1000000.0f, (float)(2 * j) / (float)hd); }
        else invf = 1.0f / powf(10000.0f, (float)(2 * j) / (float)hd);
        const float f = (float)pos * invf;
        const float c = cosf(f), sn = sinf(f);
        const float rot = i < half ? -buf[i + half] : buf[i - half];
        out[i] = buf[i] * c + rot * sn;
    }
}

// Decode attention, one kv head shared by A_NQ query heads (MQA), scaling 1.
// Keys [start, pos] where start = max(0, pos + 1 - window) (window 0 = none).
// Split over keys: each wave takes A_KW consecutive keys; its 64 lanes are
// 8 groups of 8, one group per query head, each lane holding HD/8 of that
// head's q and output, so a score needs only a 3-step shuffle within the
// group. Each wave runs an online softmax over its keys and writes one
// partial per head: part[w][h] = {m, l, acc[hd]}; g4_attn_combine merges the
// waves' partials.
#define A_KW 8
#define A_NQ 8
template <int HD>
__device__ __forceinline__ void attn_part(const float* __restrict__ q, const float* __restrict__ kc,
                                          const float* __restrict__ vc, float* __restrict__ part,
                                          const int* __restrict__ posp, int window) {
    constexpr int PD = HD / 8;                   // dims per lane
    const int lane = threadIdx.x & 63, h = lane >> 3, sub = lane & 7;
    const int wid = blockIdx.x * 4 + (threadIdx.x >> 6);
    const int pos = posp[0];
    int start = 0;
    if (window > 0 && pos + 1 > window) start = pos + 1 - window;
    const int j0 = start + wid * A_KW;
    if (j0 > pos) return;
    const int d0 = sub * 4;                      // lane's dims: d0 + 32 t + {0..3}, t < PD / 4
    float4 qr[PD / 4], acc[PD / 4];
#pragma unroll
    for (int t = 0; t < PD / 4; ++t) {
        qr[t] = *(const float4*)(q + h * HD + d0 + 32 * t);
        acc[t] = make_float4(0.f, 0.f, 0.f, 0.f);
    }
    float m = -3.0e38f, l = 0.f;
    const int jn = min(A_KW, pos + 1 - j0);
    for (int jj = 0; jj < jn; ++jj) {
        const float* kr = kc + (size_t)(j0 + jj) * HD + d0;
        float s = 0.f;
#pragma unroll
        for (int t = 0; t < PD / 4; ++t) {
            const float4 k4 = *(const float4*)(kr + 32 * t);
            s = fmaf(qr[t].x, k4.x, s); s = fmaf(qr[t].y, k4.y, s); s = fmaf(qr[t].z, k4.z, s); s = fmaf(qr[t].w, k4.w, s);
        }
        s += __shfl_xor(s, 4); s += __shfl_xor(s, 2); s += __shfl_xor(s, 1);
        const float mn = fmaxf(m, s);
        const float c = __expf(m - mn), p = __expf(s - mn);
        l = l * c + p;
        const float* vr = vc + (size_t)(j0 + jj) * HD + d0;
#pragma unroll
        for (int t = 0; t < PD / 4; ++t) {
            const float4 v4 = *(const float4*)(vr + 32 * t);
            acc[t].x = fmaf(p, v4.x, acc[t].x * c); acc[t].y = fmaf(p, v4.y, acc[t].y * c);
            acc[t].z = fmaf(p, v4.z, acc[t].z * c); acc[t].w = fmaf(p, v4.w, acc[t].w * c);
        }
        m = mn;
    }
    float* pw = part + ((size_t)wid * A_NQ + h) * (HD + 2);
    if (sub == 0) { pw[0] = m; pw[1] = l; }
#pragma unroll
    for (int t = 0; t < PD / 4; ++t) *(float4*)(pw + 2 + d0 + 32 * t) = acc[t];
}
extern "C" __global__ __launch_bounds__(256) void g4_attn256(const float* q, const float* kc, const float* vc,
                                                             float* part, const int* posp, int window) {
    attn_part<256>(q, kc, vc, part, posp, window);
}
extern "C" __global__ __launch_bounds__(256) void g4_attn512(const float* q, const float* kc, const float* vc,
                                                             float* part, const int* posp, int window) {
    attn_part<512>(q, kc, vc, part, posp, window);
}
// Merges the waves' partials, one block per query head:
// out[h][d] = sum_w acc_w e^(m_w - M) / sum_w l_w e^(m_w - M).
extern "C" __global__ __launch_bounds__(256) void g4_attn_combine(const float* __restrict__ part, float* __restrict__ out,
                                                                  const int* __restrict__ posp, int window, int hd) {
    const int h = blockIdx.x, pos = posp[0];
    int start = 0;
    if (window > 0 && pos + 1 > window) start = pos + 1 - window;
    const int nw = (pos + 1 - start + A_KW - 1) / A_KW;
    float M = -3.0e38f;
    for (int w = 0; w < nw; ++w) M = fmaxf(M, part[((size_t)w * A_NQ + h) * (hd + 2)]);
    float L = 0.f;
    for (int w = 0; w < nw; ++w) {
        const float* p = part + ((size_t)w * A_NQ + h) * (hd + 2);
        L = fmaf(p[1], __expf(p[0] - M), L);
    }
    const float invL = 1.0f / L;
    for (int d = threadIdx.x; d < hd; d += 256) {
        float A = 0.f;
        for (int w = 0; w < nw; ++w) {
            const float* p = part + ((size_t)w * A_NQ + h) * (hd + 2);
            A = fmaf(p[2 + d], __expf(p[0] - M), A);
        }
        out[h * hd + d] = A * invL;
    }
}

// Per-layer inputs: ple[l] = (rmsnorm(proj[l] * projScale) * normW + E_ple[tok][l] * 16) / sqrt(2),
// one block per layer, d = 256 per layer.
extern "C" __global__ __launch_bounds__(256) void g4_ple_combine(const float* __restrict__ proj, const bf16* __restrict__ normW,
                                                                 const bf16* __restrict__ Eple, const int* __restrict__ tok,
                                                                 float* __restrict__ ple, int d, int layers,
                                                                 float projScale, float tokScale, float eps) {
    __shared__ float red[16];
    const int l = blockIdx.x;
    const float* p = proj + (size_t)l * d;
    float s = 0.f;
    for (int i = threadIdx.x; i < d; i += 256) { float v = p[i] * projScale; s = fmaf(v, v, s); }
    const float inv = rsqrtf(block_sum(s, red) / (float)d + eps);
    const bf16* e = Eple + (size_t)tok[0] * d * layers + (size_t)l * d;
    for (int i = threadIdx.x; i < d; i += 256)
        ple[(size_t)l * d + i] = (p[i] * projScale * inv * bfs(normW[i]) + bfs(e[i]) * tokScale) * 0.7071067811865476f;
}

// Argmax over n logits in two passes: per-block (value, index) pairs, then
// one block over those; writes tok[0] (the next step's input) and
// out[step] (the generated sequence). Ties take the lower index.
extern "C" __global__ __launch_bounds__(256) void g4_argmax_part(const float* __restrict__ x, int n,
                                                                 float* __restrict__ pv, int* __restrict__ pi) {
    __shared__ float sv[256];
    __shared__ int si[256];
    float bv = -3.0e38f; int bi = 0x7fffffff;
    for (int i = blockIdx.x * 256 + threadIdx.x; i < n; i += gridDim.x * 256) {
        float v = x[i];
        if (v > bv) { bv = v; bi = i; }
    }
    sv[threadIdx.x] = bv; si[threadIdx.x] = bi;
    __syncthreads();
    for (int s = 128; s > 0; s >>= 1) {
        if (threadIdx.x < s) {
            float v2 = sv[threadIdx.x + s]; int i2 = si[threadIdx.x + s];
            if (v2 > sv[threadIdx.x] || (v2 == sv[threadIdx.x] && i2 < si[threadIdx.x])) { sv[threadIdx.x] = v2; si[threadIdx.x] = i2; }
        }
        __syncthreads();
    }
    if (threadIdx.x == 0) { pv[blockIdx.x] = sv[0]; pi[blockIdx.x] = si[0]; }
}
extern "C" __global__ __launch_bounds__(256) void g4_argmax_final(const float* __restrict__ pv, const int* __restrict__ pi,
                                                                  int nb, int* __restrict__ tok, int* __restrict__ out,
                                                                  int* __restrict__ posp) {
    __shared__ float sv[256];
    __shared__ int si[256];
    float bv = -3.0e38f; int bi = 0x7fffffff;
    for (int i = threadIdx.x; i < nb; i += 256) {
        float v = pv[i]; int j = pi[i];
        if (v > bv || (v == bv && j < bi)) { bv = v; bi = j; }
    }
    sv[threadIdx.x] = bv; si[threadIdx.x] = bi;
    __syncthreads();
    for (int s = 128; s > 0; s >>= 1) {
        if (threadIdx.x < s) {
            float v2 = sv[threadIdx.x + s]; int i2 = si[threadIdx.x + s];
            if (v2 > sv[threadIdx.x] || (v2 == sv[threadIdx.x] && i2 < si[threadIdx.x])) { sv[threadIdx.x] = v2; si[threadIdx.x] = i2; }
        }
        __syncthreads();
    }
    if (threadIdx.x == 0) { tok[0] = si[0]; out[posp[1]] = si[0]; posp[1] = posp[1] + 1; }
}

// pos[0] += 1 at the end of a step.
extern "C" __global__ void g4_advance(int* posp) { if (threadIdx.x == 0) posp[0] = posp[0] + 1; }
