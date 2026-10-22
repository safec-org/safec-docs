// Gemma 4 E2B on NVIDIA GPUs (see gemma4_cuda.h). The kernel source below is generated
// from gemma4_cuda.cuh by gen_cuda_sc.py; edit that file, not the string.
#pragma once
#include "gemma4_cuda.h"
#include "safetensors.h"

namespace std {

extern int   printf(const char* fmt, ...);
extern int   snprintf(char* buf, unsigned long n, const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
extern void* fopen(const char* path, const char* mode);
extern int   fseeko(void* f, long long off, int whence);
extern unsigned long fread(void* buf, unsigned long size, unsigned long n, void* f);
extern int   fclose(void* f);
// CUDA driver C ABI (cuda.h)
extern int cuModuleGetFunction(void** func, void* module, const char* name);
extern int cuMemAlloc_v2(unsigned long long* dptr, unsigned long bytes);
extern int cuMemFree_v2(unsigned long long dptr);
extern int cuMemsetD8_v2(unsigned long long dptr, unsigned char value, unsigned long n);
extern int cuLaunchKernelEx(const void* config, void* f, void** params, void** extra);
extern int clock_gettime(int clk, void* ts);

// Monotonic nanoseconds. CLOCK_MONOTONIC is 1 on Linux and 6 on macOS; std's
// sys_monotonic_ns passes 6, which on Linux is CLOCK_MONOTONIC_COARSE (~4 ms ticks).
#if defined(__linux__)
#define G4C_CLOCK_MONOTONIC 1
#else
#define G4C_CLOCK_MONOTONIC 6
#endif
static long long g4c_now_ns_() {
    long long ts[2];
    unsafe {
        if (clock_gettime(G4C_CLOCK_MONOTONIC, (void*)&ts[0]) != 0) return 0LL;
    }
    return ts[0] * 1000000000LL + ts[1];
}

// CUlaunchAttribute / CUlaunchConfig (cuda.h, CUDA 12+): only the programmatic stream
// serialization attribute (id 6) is used.
struct G4cLaunchAttr {
    int id;
    int pad;
    int val[16];
};
struct G4cLaunchConfig {
    unsigned int gx; unsigned int gy; unsigned int gz;
    unsigned int bx; unsigned int by; unsigned int bz;
    unsigned int smem; unsigned int pad;
    void* stream;
    void* attrs;
    unsigned int numAttrs; unsigned int pad2;
};

#define G4C_H        1536UL
#define G4C_NH       8UL
#define G4C_PLE      256UL
#define G4C_V        262144UL
#define G4C_SHARED0  15UL
#define G4C_NL       35UL     // GEMMA4_CUDA_LAYERS as unsigned long
#define G4C_ATT_CH   32UL     // ATT_CH in the kernel source
#define G4C_PDL      1        // launch with programmatic dependent launch

// kernel indices in Gemma4Cuda.fn
#define G4C_K_EMBED   0
#define G4C_K_PLE     1
#define G4C_K_FINISH  2
#define G4C_K_GEMV    3
#define G4C_K_ATTN    4
#define G4C_K_ADVANCE 5

static const char* g4c_kernel_source_() {
    return
    "// Gemma 4 E2B decode kernels (bf16 storage, fp32 math). NVRTC-compatible: no includes.\n"
    "// Activations are rounded to bf16 at the same points as the PyTorch bf16 model.\n"
    "// Every kernel reads the decode position from st->pos (device memory), so a\n"
    "// whole token step can be captured once as a CUDA graph and replayed.\n"
    "\n"
    "#define FULL 0xffffffffu\n"
    "typedef unsigned short bf;\n"
    "\n"
    "struct G4State {           // device-resident step state\n"
    "    int pos;               // position of the token being processed\n"
    "    int promptLen;         // tokens[0..promptLen) are the prompt\n"
    "    int maxSeq;\n"
    "    int done;\n"
    "};\n"
    "\n"
    "// Programmatic dependent launch (sm_90): a kernel lets the next one start launching right\n"
    "// away and waits for its predecessor's results only before touching them. Everything a\n"
    "// kernel does before PDL_WAIT reads only immutable data (weights).\n"
    "#define PDL_LAUNCH() asm volatile(\"griddepcontrol.launch_dependents;\")\n"
    "#define PDL_WAIT() asm volatile(\"griddepcontrol.wait;\" ::: \"memory\")\n"
    "__device__ __forceinline__ void pf_l2(const void* p) { asm volatile(\"prefetch.global.L2 [%0];\" :: \"l\"(p)); }\n"
    "__device__ __forceinline__ float b2f(bf b) { return __uint_as_float(((unsigned)b) << 16); }\n"
    "__device__ __forceinline__ bf f2b(float f) {\n"
    "    unsigned u = __float_as_uint(f);\n"
    "    if ((u & 0x7fffffffu) > 0x7f800000u) return (bf)((u >> 16) | 0x40u);\n"
    "    u += 0x7fffu + ((u >> 16) & 1u);\n"
    "    return (bf)(u >> 16);\n"
    "}\n"
    "__device__ __forceinline__ float rb(float f) { return b2f(f2b(f)); }   // round through bf16\n"
    "__device__ __forceinline__ float gelu_tanh(float x) {\n"
    "    const float k0 = 0.7978845608028654f, k1 = 0.044715f;\n"
    "    return 0.5f * x * (1.0f + tanhf(k0 * (x + k1 * x * x * x)));\n"
    "}\n"
    "__device__ __forceinline__ float warp_sum(float v) {\n"
    "#pragma unroll\n"
    "    for (int o = 16; o > 0; o >>= 1) v += __shfl_xor_sync(FULL, v, o);\n"
    "    return v;\n"
    "}\n"
    "// Block-wide sum (blockDim.x multiple of 32, <= 1024); 'red' is >= 32 floats of shared memory.\n"
    "__device__ float block_sum(float v, float* red) {\n"
    "    const int lane = threadIdx.x & 31, w = threadIdx.x >> 5, nw = blockDim.x >> 5;\n"
    "    v = warp_sum(v);\n"
    "    __syncthreads();\n"
    "    if (lane == 0) red[w] = v;\n"
    "    __syncthreads();\n"
    "    float t = threadIdx.x < nw ? red[threadIdx.x] : 0.0f;\n"
    "    if (w == 0) t = warp_sum(t);\n"
    "    if (threadIdx.x == 0) red[0] = t;\n"
    "    __syncthreads();\n"
    "    float r = red[0];\n"
    "    __syncthreads();\n"
    "    return r;\n"
    "}\n"
    "// Gemma4RMSNorm of n values (fp32 in 'v', strided by blockDim.x) -> 1/rms\n"
    "__device__ float rms_inv(const float* v, int n, float* red) {\n"
    "    float s = 0.0f;\n"
    "    for (int i = threadIdx.x; i < n; i += blockDim.x) s += v[i] * v[i];\n"
    "    s = block_sum(s, red);\n"
    "    return rsqrtf(s / (float)n + 1e-6f);\n"
    "}\n"
    "\n"
    "// ── Embedding: x = embed[tok] * bf16(sqrt(1536)); pleTok = embedPle[tok] * 16 ──────────\n"
    "extern \"C\" __global__ void g4_embed(const G4State* st, const int* tokens, const bf* __restrict__ embW,\n"
    "                                    const bf* __restrict__ pleW, bf* x, bf* pleTok, int H, int PLE) {\n"
    "    PDL_LAUNCH(); PDL_WAIT();\n"
    "    const int tok = tokens[st->pos];\n"
    "    const float es = rb(sqrtf((float)H));\n"
    "    for (int i = blockIdx.x * blockDim.x + threadIdx.x; i < H + PLE; i += gridDim.x * blockDim.x) {\n"
    "        if (i < H) x[i] = f2b(b2f(embW[(size_t)tok * H + i]) * es);\n"
    "        else { int j = i - H; pleTok[j] = f2b(b2f(pleW[(size_t)tok * PLE + j]) * 16.0f); }\n"
    "    }\n"
    "}\n"
    "\n"
    "// ── PLE combine: per layer l (block), proj = rmsnorm(bf16(raw * 1/sqrt(H))) * w;\n"
    "//    ple[l] = bf16(bf16(proj + pleTok[l]) * 2^-0.5) ─────────────────────────────────────\n"
    "extern \"C\" __global__ void g4_ple_combine(const bf* raw, const bf* pleTok, const bf* __restrict__ normW, bf* ple,\n"
    "                                          int D, float projScale) {\n"
    "    PDL_LAUNCH(); PDL_WAIT();\n"
    "    extern __shared__ float sm[];\n"
    "    float* v = sm; float* red = sm + D;\n"
    "    const int l = blockIdx.x;\n"
    "    for (int i = threadIdx.x; i < D; i += blockDim.x) v[i] = rb(b2f(raw[l * D + i]) * projScale);\n"
    "    __syncthreads();\n"
    "    float inv = rms_inv(v, D, red);\n"
    "    for (int i = threadIdx.x; i < D; i += blockDim.x) {\n"
    "        float p = rb(v[i] * inv * b2f(normW[i]));\n"
    "        ple[l * D + i] = f2b(rb(p + b2f(pleTok[l * D + i])) * 0.70710678118654752f);\n"
    "    }\n"
    "}\n"
    "\n"
    "// ── GEMV: y[n] = W[n,:] . xs, W bf16 [N, K] row-major, K % 256 == 0, xs bf16 [K] ─────────\n"
    "// One row per warp, four 16-byte loads in flight per lane. Epilogue:\n"
    "//   emode 0: y[n] = bf16(acc)\n"
    "//   emode 1: gate/up: rows n and n + N of W; y[n] = bf16(bf16(gelu(bf16 g)) * bf16 u)\n"
    "//   emode 2: y[n] = bf16(bf16(gelu(bf16 acc)) * m[n])\n"
    "//   emode 3: final softcap: y[n] = bf16(bf16(tanh(bf16(bf16(acc) / c))) * c), and the\n"
    "//            running argmax (first index of the maximum) in *best as one 64-bit atomicMax\n"
    "// Finisher (fmode 1): the last block to finish (a grid-wide counter) folds y into the\n"
    "// residual stream and prepares the next GEMV's input, 'fin' below; H = N of this GEMV.\n"
    "struct Finish {\n"
    "    const bf* x; const bf* wd; const bf* scal; bf* xo; const bf* w1; bf* xs; int* counter; int useDelta;\n"
    "};\n"
    "struct GemvArgs {\n"
    "    const bf* W; int N; int K; const bf* xs; const bf* m; bf* y; float cap; unsigned long long* best;\n"
    "};\n"
    "__device__ __forceinline__ uint4 ldw(const bf* p) {\n"
    "    uint4 w;\n"
    "    asm volatile(\"ld.global.nc.L1::no_allocate.v4.u32 {%0,%1,%2,%3}, [%4];\" : \"=r\"(w.x), \"=r\"(w.y), \"=r\"(w.z), \"=r\"(w.w) : \"l\"(p));\n"
    "    return w;\n"
    "}\n"
    "__device__ __forceinline__ float dot8(uint4 wv, uint4 xv) {\n"
    "    const unsigned* xp = (const unsigned*)&xv; const unsigned* wp = (const unsigned*)&wv;\n"
    "    float s = 0.0f;\n"
    "#pragma unroll\n"
    "    for (int e = 0; e < 4; ++e) {\n"
    "        s += __uint_as_float(wp[e] << 16) * __uint_as_float(xp[e] << 16);\n"
    "        s += __uint_as_float(wp[e] & 0xffff0000u) * __uint_as_float(xp[e] & 0xffff0000u);\n"
    "    }\n"
    "    return s;\n"
    "}\n"
    "// Residual step on the hidden vector (HID values), by one 128-thread block:\n"
    "//   xn = useDelta ? bf16(x + bf16(rmsnorm(y) * wd)) : x;  if (scal) xn = bf16(xn * scal[0])\n"
    "//   xo = xn (if xo);  xs = w1 ? bf16(rmsnorm(xn) * w1) : xn\n"
    "// All loads are issued up front (HID / 128 values per thread in registers): the inputs come\n"
    "// from global memory, and loading them one dependent step at a time would serialize.\n"
    "#define HID 1536\n"
    "#define FPT (HID / 128)\n"
    "__device__ float block_sum128(float v, float* red) {\n"
    "    v = warp_sum(v);\n"
    "    if ((threadIdx.x & 31) == 0) red[threadIdx.x >> 5] = v;\n"
    "    __syncthreads();\n"
    "    float r = red[0] + red[1] + red[2] + red[3];\n"
    "    __syncthreads();\n"
    "    return r;\n"
    "}\n"
    "__device__ void finish_block(const Finish& f, const bf* y, float* red) {\n"
    "    const int tid = threadIdx.x;\n"
    "    float yv[FPT], xv[FPT], wdv[FPT], w1v[FPT];\n"
    "    const float sc = f.scal ? b2f(f.scal[0]) : 1.0f;\n"
    "#pragma unroll\n"
    "    for (int e = 0; e < FPT; ++e) {\n"
    "        const int i = tid + 128 * e;\n"
    "        xv[e] = b2f(__ldcg(f.x + i));\n"
    "        yv[e] = f.useDelta ? b2f(__ldcg(y + i)) : 0.0f;\n"
    "        wdv[e] = f.useDelta ? b2f(f.wd[i]) : 0.0f;\n"
    "        w1v[e] = f.w1 ? b2f(f.w1[i]) : 0.0f;\n"
    "    }\n"
    "    if (f.useDelta) {\n"
    "        float ss = 0.0f;\n"
    "#pragma unroll\n"
    "        for (int e = 0; e < FPT; ++e) ss += yv[e] * yv[e];\n"
    "        const float inv = rsqrtf(block_sum128(ss, red) / (float)HID + 1e-6f);\n"
    "#pragma unroll\n"
    "        for (int e = 0; e < FPT; ++e) xv[e] = rb(xv[e] + rb(yv[e] * inv * wdv[e]));\n"
    "    }\n"
    "    if (f.scal) {\n"
    "#pragma unroll\n"
    "        for (int e = 0; e < FPT; ++e) xv[e] = rb(xv[e] * sc);\n"
    "    }\n"
    "    if (f.xo) {\n"
    "#pragma unroll\n"
    "        for (int e = 0; e < FPT; ++e) f.xo[tid + 128 * e] = f2b(xv[e]);\n"
    "    }\n"
    "    float inv2 = 1.0f;\n"
    "    if (f.w1) {\n"
    "        float ss = 0.0f;\n"
    "#pragma unroll\n"
    "        for (int e = 0; e < FPT; ++e) ss += xv[e] * xv[e];\n"
    "        inv2 = rsqrtf(block_sum128(ss, red) / (float)HID + 1e-6f);\n"
    "    }\n"
    "#pragma unroll\n"
    "    for (int e = 0; e < FPT; ++e) f.xs[tid + 128 * e] = f2b(f.w1 ? xv[e] * inv2 * w1v[e] : xv[e]);\n"
    "}\n"
    "__device__ bool last_block(int* counter) {\n"
    "    __shared__ int amLast;\n"
    "    __threadfence();\n"
    "    __syncthreads();\n"
    "    if (threadIdx.x == 0) {\n"
    "        int t = atomicAdd(counter, 1);\n"
    "        amLast = t == (int)(gridDim.x * gridDim.y) - 1;\n"
    "        if (amLast) *counter = 0;\n"
    "    }\n"
    "    __syncthreads();\n"
    "    if (amLast) __threadfence();\n"
    "    return amLast;\n"
    "}\n"
    "// wpr > 1 (emode 0 only): wpr warps share each row, each summing every wpr-th 256-element\n"
    "// slice of K, reduced through shared memory; the grid then covers every row exactly once.\n"
    "__device__ __forceinline__ void gemv_body(const GemvArgs& a, const Finish& f, int emode, int fmode, int wpr);\n"
    "// Flat parameters (no by-value structs) so hosts in other languages can launch it simply.\n"
    "extern \"C\" __global__ void __launch_bounds__(128) g4_gemv(const bf* W, const bf* xs, const bf* m, bf* y, unsigned long long* best,\n"
    "                                                          const bf* fx, const bf* fwd, const bf* fscal, bf* fxo, const bf* fw1,\n"
    "                                                          bf* fxs, int* fcounter, int N, int K, float cap, int emode, int fmode,\n"
    "                                                          int fuseDelta, int wpr) {\n"
    "    GemvArgs a{W, N, K, xs, m, y, cap, best};\n"
    "    Finish f{fx, fwd, fscal, fxo, fw1, fxs, fcounter, fuseDelta};\n"
    "    gemv_body(a, f, emode, fmode, wpr);\n"
    "}\n"
    "__device__ __forceinline__ void gemv_body(const GemvArgs& a, const Finish& f, int emode, int fmode, int wpr) {\n"
    "    extern __shared__ __align__(16) unsigned char smraw[];\n"
    "    bf* xs = (bf*)smraw;                       // K bf16\n"
    "    const int K = a.K, tid = threadIdx.x;\n"
    "    PDL_LAUNCH();\n"
    "    {   // this warp's first row into L2 (one 128-byte line per lane step) while the predecessor finishes\n"
    "        const int nw0 = blockDim.x >> 5, w0 = tid >> 5, l0 = tid & 31;\n"
    "        const int r0 = wpr > 1 ? blockIdx.x * (nw0 / wpr) + w0 / wpr : blockIdx.x * nw0 + w0;\n"
    "        if (r0 < a.N && (wpr == 1 || w0 % wpr == 0))\n"
    "            for (int k = l0 * 64; k < K; k += 32 * 64) {\n"
    "                pf_l2(a.W + (size_t)r0 * K + k);\n"
    "                if (emode == 1) pf_l2(a.W + (size_t)(r0 + a.N) * K + k);\n"
    "            }\n"
    "    }\n"
    "    PDL_WAIT();\n"
    "    for (int i = tid * 8; i < K; i += blockDim.x * 8) *(uint4*)(xs + i) = __ldcg((const uint4*)(a.xs + i));\n"
    "    __syncthreads();\n"
    "    const int warp = tid >> 5, lane = tid & 31, nw = blockDim.x >> 5;\n"
    "    unsigned long long best = 0ull;\n"
    "    if (wpr > 1) {\n"
    "        __shared__ float part[4];\n"
    "        const int n = blockIdx.x * (nw / wpr) + warp / wpr, sl = warp % wpr;\n"
    "        float s0 = 0.0f;\n"
    "        if (n < a.N) {\n"
    "            const bf* w0 = a.W + (size_t)n * K;\n"
    "            const int step = 256 * wpr;\n"
    "            for (int k = sl * 256 + lane * 8; k < K; k += 4 * step) {\n"
    "                uint4 wv[4];\n"
    "#pragma unroll\n"
    "                for (int u = 0; u < 4; ++u) if (k + u * step < K) wv[u] = ldw(w0 + k + u * step);\n"
    "#pragma unroll\n"
    "                for (int u = 0; u < 4; ++u) if (k + u * step < K) s0 += dot8(wv[u], *(const uint4*)(xs + k + u * step));\n"
    "            }\n"
    "        }\n"
    "        s0 = warp_sum(s0);\n"
    "        if (lane == 0) part[warp] = s0;\n"
    "        __syncthreads();\n"
    "        if (sl == 0 && lane == 0 && n < a.N) {\n"
    "            float t = 0.0f;\n"
    "            for (int i = 0; i < wpr; ++i) t += part[warp + i];\n"
    "            a.y[n] = f2b(t);\n"
    "        }\n"
    "    } else\n"
    "    for (int n = blockIdx.x * nw + warp; n < a.N; n += gridDim.x * nw) {\n"
    "        const bf* w0 = a.W + (size_t)n * K;\n"
    "        const bf* w1 = a.W + (size_t)(n + a.N) * K;\n"
    "        float s0 = 0.0f, s1 = 0.0f;\n"
    "        for (int k = lane * 8; k < K; k += 1024) {\n"
    "            uint4 wv[4], wu[4];\n"
    "#pragma unroll\n"
    "            for (int u = 0; u < 4; ++u)\n"
    "                if (k + u * 256 < K) { wv[u] = ldw(w0 + k + u * 256); if (emode == 1) wu[u] = ldw(w1 + k + u * 256); }\n"
    "#pragma unroll\n"
    "            for (int u = 0; u < 4; ++u)\n"
    "                if (k + u * 256 < K) {\n"
    "                    uint4 xv = *(const uint4*)(xs + k + u * 256);\n"
    "                    s0 += dot8(wv[u], xv);\n"
    "                    if (emode == 1) s1 += dot8(wu[u], xv);\n"
    "                }\n"
    "        }\n"
    "        s0 = warp_sum(s0);\n"
    "        if (emode == 1) s1 = warp_sum(s1);\n"
    "        if (lane == 0) {\n"
    "            float r;\n"
    "            if (emode == 0) r = s0;\n"
    "            else if (emode == 1) r = rb(gelu_tanh(rb(s0))) * rb(s1);\n"
    "            else if (emode == 2) r = rb(gelu_tanh(rb(s0))) * b2f(a.m[n]);\n"
    "            else r = rb(tanhf(rb(rb(s0) / a.cap))) * a.cap;\n"
    "            bf o = f2b(r);\n"
    "            a.y[n] = o;\n"
    "            if (emode == 3) {\n"
    "                unsigned u = ((unsigned)o) << 16;\n"
    "                u = (u & 0x80000000u) ? ~u : (u | 0x80000000u);   // order-preserving key\n"
    "                unsigned long long key = ((unsigned long long)u << 32) | (unsigned)(0xffffffffu - (unsigned)n);\n"
    "                if (key > best) best = key;\n"
    "            }\n"
    "        }\n"
    "    }\n"
    "    if (emode == 3 && lane == 0 && best) atomicMax(a.best, best);\n"
    "    if (fmode && last_block(f.counter)) {\n"
    "        __shared__ float red[4];\n"
    "        finish_block(f, a.y, red);\n"
    "    }\n"
    "}\n"
    "// Standalone residual step (one block): used once per token before layer 0.\n"
    "extern \"C\" __global__ void __launch_bounds__(128) g4_finish(const bf* fx, const bf* fw1, bf* fxs) {\n"
    "    PDL_LAUNCH(); PDL_WAIT();\n"
    "    __shared__ float red[4];\n"
    "    Finish f{fx, 0, 0, 0, fw1, fxs, 0, 0};\n"
    "    finish_block(f, 0, red);\n"
    "}\n"
    "\n"
    "// ── Q/K/V post-processing: per-head RMSNorm (q, k with weight; v without), RoPE, cache ──\n"
    "// qkv: [8*hd q | hd k | hd v] (kv part absent when kvw == 0). One block per head (+2 for k, v).\n"
    "// RoPE: inv_freq[j] = j < nrot ? theta^(-2j/hd) : 0 for j < hd/2, cos/sin rounded to bf16.\n"
    "extern \"C\" __global__ void g4_qkv_post(const G4State* st, const bf* qkv, const bf* __restrict__ qnw,\n"
    "                                       const bf* __restrict__ knw, bf* q, bf* kc, bf* vc, int nh, int hd,\n"
    "                                       int hasKV, int nrot, float theta) {\n"
    "    extern __shared__ float sm[];\n"
    "    float* v = sm; float* red = sm + hd;\n"
    "    const int b = blockIdx.x, pos = st->pos;\n"
    "    if (b >= nh && !hasKV) return;\n"
    "    const bf* src = qkv + (size_t)b * hd;\n"
    "    for (int i = threadIdx.x; i < hd; i += blockDim.x) v[i] = b2f(src[i]);\n"
    "    __syncthreads();\n"
    "    float inv = rms_inv(v, hd, red);\n"
    "    const bf* w = b < nh ? qnw : knw;\n"
    "    for (int i = threadIdx.x; i < hd; i += blockDim.x) v[i] = rb(w && b <= nh ? v[i] * inv * b2f(w[i]) : v[i] * inv);\n"
    "    __syncthreads();\n"
    "    if (b == nh + 1) {   // v: no RoPE\n"
    "        for (int i = threadIdx.x; i < hd; i += blockDim.x) vc[(size_t)pos * hd + i] = f2b(v[i]);\n"
    "        return;\n"
    "    }\n"
    "    const int half = hd / 2;\n"
    "    bf* dst = b < nh ? q + (size_t)b * hd : kc + (size_t)pos * hd;\n"
    "    for (int i = threadIdx.x; i < hd; i += blockDim.x) {\n"
    "        int j = i < half ? i : i - half;\n"
    "        float invf = j < nrot ? powf(theta, -2.0f * (float)j / (float)hd) : 0.0f;\n"
    "        float ang = (float)pos * invf;\n"
    "        float c = rb(cosf(ang)), s = rb(sinf(ang));\n"
    "        float rot = i < half ? -v[i + half] : v[i - half];\n"
    "        dst[i] = f2b(rb(rb(v[i] * c) + rb(rot * s)));\n"
    "    }\n"
    "}\n"
    "\n"
    "// ── Decode attention, split over keys: block (head, split) handles keys\n"
    "//    [lo + split*CH, ...) of the valid range [lo, pos]; writes (max, sum, acc[hd]) ─────────\n"
    "#ifndef ATT_CH\n"
    "#define ATT_CH 32\n"
    "#endif\n"
    "// Combines head h's splits (max, sum, acc) into o[h]; run by that head's last block.\n"
    "__device__ void attn_combine(const float* ph, int used, bf* oh, int hd) {\n"
    "    float mx = -1e30f;\n"
    "    for (int s = 0; s < used; ++s) mx = fmaxf(mx, __ldcg(ph + (size_t)s * (hd + 2)));\n"
    "    float l = 0.0f;\n"
    "    for (int s = 0; s < used; ++s) l += __ldcg(ph + (size_t)s * (hd + 2) + 1) * __expf(__ldcg(ph + (size_t)s * (hd + 2)) - mx);\n"
    "    const float il = 1.0f / l;\n"
    "    for (int i = threadIdx.x; i < hd; i += blockDim.x) {\n"
    "        float a = 0.0f;\n"
    "        for (int s = 0; s < used; ++s) {\n"
    "            const float* r = ph + (size_t)s * (hd + 2);\n"
    "            a += __ldcg(r + 2 + i) * __expf(__ldcg(r) - mx);\n"
    "        }\n"
    "        oh[i] = f2b(a * il);\n"
    "    }\n"
    "}\n"
    "// Per-head RMSNorm (with weight w, or none) and RoPE of hd raw values src -> dst (bf16), by\n"
    "// the calling block; 'v' is hd floats of shared memory, 'red' 4. RoPE inv_freq[j] =\n"
    "// j < nrot ? theta^(-2j/hd) : 0 for j < hd/2, cos/sin rounded to bf16 as in PyTorch.\n"
    "__device__ void norm_rope(const bf* src, const bf* w, bool rope, int pos, int hd, int nrot, float theta,\n"
    "                          bf* dst, float* v, float* red) {\n"
    "    float ss = 0.0f;\n"
    "    for (int i = threadIdx.x; i < hd; i += blockDim.x) { float x = b2f(src[i]); v[i] = x; ss += x * x; }\n"
    "    const float inv = rsqrtf(block_sum128(ss, red) / (float)hd + 1e-6f);\n"
    "    for (int i = threadIdx.x; i < hd; i += blockDim.x) v[i] = rb(w ? v[i] * inv * b2f(w[i]) : v[i] * inv);\n"
    "    __syncthreads();\n"
    "    const int half = hd / 2;\n"
    "    for (int i = threadIdx.x; i < hd; i += blockDim.x) {\n"
    "        if (!rope) { dst[i] = f2b(v[i]); continue; }\n"
    "        int j = i < half ? i : i - half;\n"
    "        float invf = j < nrot ? powf(theta, -2.0f * (float)j / (float)hd) : 0.0f;\n"
    "        float ang = (float)pos * invf;\n"
    "        float c = rb(cosf(ang)), sn = rb(sinf(ang));\n"
    "        float rot = i < half ? -v[i + half] : v[i - half];\n"
    "        dst[i] = f2b(rb(rb(v[i] * c) + rb(rot * sn)));\n"
    "    }\n"
    "    __syncthreads();\n"
    "}\n"
    "// Block (head h, split sp) scores keys [lo + sp*CH, ...) of the valid range [lo, pos] (two\n"
    "// threads per key, each summing half of the head dim), softmaxes them locally and writes\n"
    "// (max, sum, acc[hd]); the last block of each head combines its splits. Each block applies\n"
    "// q_norm and RoPE to its head's raw q itself. In a layer with its own K/V (hasKV), the block\n"
    "// holding position pos also normalizes and ropes k / normalizes v for pos from the raw\n"
    "// projection, uses them from shared memory, and (head 0) writes them to the cache.\n"
    "extern \"C\" __global__ void __launch_bounds__(128) g4_attn(const G4State* st, const bf* qkv, const bf* __restrict__ qnw,\n"
    "                                                          const bf* __restrict__ knw, bf* kc, bf* vc, float* part, bf* o,\n"
    "                                                          int* counters, int nh, int hd, int window, int hasKV, int nrot, float theta) {\n"
    "    PDL_LAUNCH(); PDL_WAIT();\n"
    "    extern __shared__ __align__(16) float sm[];\n"
    "    float* p = sm;                        // ATT_CH probabilities\n"
    "    float* v = p + ATT_CH;                // hd scratch\n"
    "    bf* qs = (bf*)(v + hd);               // hd\n"
    "    bf* kn = qs + hd;                     // hd (current key, hasKV)\n"
    "    bf* vn = kn + hd;                     // hd (current value, hasKV)\n"
    "    __shared__ float red[4];\n"
    "    const int h = blockIdx.x, sp = blockIdx.y, nsp = gridDim.y, pos = st->pos;\n"
    "    const int lo = window > 0 ? max(0, pos - window + 1) : 0;\n"
    "    const int used = min(nsp, (pos - lo) / ATT_CH + 1);\n"
    "    const int k0 = lo + sp * ATT_CH, k1 = min(pos + 1, k0 + ATT_CH), n = k1 - k0;\n"
    "    float* out = part + ((size_t)h * nsp + sp) * (hd + 2);\n"
    "    if (sp < used) {\n"
    "        norm_rope(qkv + (size_t)h * hd, qnw, true, pos, hd, nrot, theta, qs, v, red);\n"
    "        const bool cur = hasKV && sp == used - 1;      // this split ends at pos\n"
    "        if (cur) {\n"
    "            norm_rope(qkv + (size_t)nh * hd, knw, true, pos, hd, nrot, theta, kn, v, red);\n"
    "            norm_rope(qkv + (size_t)(nh + 1) * hd, 0, false, pos, hd, nrot, theta, vn, v, red);\n"
    "            if (h == 0)\n"
    "                for (int i = threadIdx.x * 8; i < hd; i += blockDim.x * 8) {\n"
    "                    *(uint4*)(kc + (size_t)pos * hd + i) = *(const uint4*)(kn + i);\n"
    "                    *(uint4*)(vc + (size_t)pos * hd + i) = *(const uint4*)(vn + i);\n"
    "                }\n"
    "        }\n"
    "        {\n"
    "            const int j = threadIdx.x >> 1, half = threadIdx.x & 1, hh = hd / 2;\n"
    "            float s = 0.0f;\n"
    "            if (j < n) {\n"
    "                const bf* kr = (cur && k0 + j == pos ? kn : kc + (size_t)(k0 + j) * hd) + half * hh;\n"
    "                const bf* qr = qs + half * hh;\n"
    "#pragma unroll 8\n"
    "                for (int i = 0; i < hh; i += 8) s += dot8(*(const uint4*)(kr + i), *(const uint4*)(qr + i));\n"
    "            }\n"
    "            s += __shfl_xor_sync(FULL, s, 1);\n"
    "            if (j < n && half == 0) p[j] = s;\n"
    "        }\n"
    "        __syncthreads();\n"
    "        float mx = -1e30f;\n"
    "        for (int j = 0; j < n; ++j) mx = fmaxf(mx, p[j]);\n"
    "        __syncthreads();\n"
    "        if (threadIdx.x < n) p[threadIdx.x] = __expf(p[threadIdx.x] - mx);\n"
    "        __syncthreads();\n"
    "        const int nc = cur ? n - 1 : n;                // the current value is added from shared memory\n"
    "        for (int i = threadIdx.x * 2; i < hd; i += blockDim.x * 2) {\n"
    "            float a0 = 0.0f, a1 = 0.0f;\n"
    "            const bf* vp = vc + (size_t)k0 * hd + i;\n"
    "#pragma unroll 8\n"
    "            for (int j = 0; j < nc; ++j) {\n"
    "                unsigned u = *(const unsigned*)(vp + (size_t)j * hd);\n"
    "                a0 += p[j] * __uint_as_float(u << 16); a1 += p[j] * __uint_as_float(u & 0xffff0000u);\n"
    "            }\n"
    "            if (cur) { unsigned u = *(const unsigned*)(vn + i); a0 += p[n - 1] * __uint_as_float(u << 16); a1 += p[n - 1] * __uint_as_float(u & 0xffff0000u); }\n"
    "            out[2 + i] = a0; out[3 + i] = a1;\n"
    "        }\n"
    "        if (threadIdx.x == 0) {\n"
    "            float l = 0.0f;\n"
    "            for (int j = 0; j < n; ++j) l += p[j];\n"
    "            out[0] = mx; out[1] = l;\n"
    "        }\n"
    "    }\n"
    "    // per-head completion count over all nsp blocks of head h\n"
    "    __shared__ int amLast;\n"
    "    __threadfence();\n"
    "    __syncthreads();\n"
    "    if (threadIdx.x == 0) {\n"
    "        int t = atomicAdd(counters + h, 1);\n"
    "        amLast = t == nsp - 1;\n"
    "        if (amLast) counters[h] = 0;\n"
    "    }\n"
    "    __syncthreads();\n"
    "    if (amLast) { __threadfence(); attn_combine(part + (size_t)h * nsp * (hd + 2), used, o + (size_t)h * hd, hd); }\n"
    "}\n"
    "\n"
    "// ── Advance: the next token is the prompt's or the argmax the LM-head GEMV left in *best ──\n"
    "extern \"C\" __global__ void g4_advance(G4State* st, unsigned long long* best, int* tokens) {\n"
    "    PDL_LAUNCH(); PDL_WAIT();\n"
    "    const int idx = (int)(0xffffffffu - (unsigned)(*best & 0xffffffffull));\n"
    "    *best = 0ull;\n"
    "    const int np = st->pos + 1;\n"
    "    if (np >= st->promptLen && np < st->maxSeq) tokens[np] = idx;\n"
    "    if (np < st->maxSeq) st->pos = np; else st->done = 1;\n"
    "}\n";
}

static unsigned long g4c_alloc_(unsigned long bytes) {
    unsigned long long d = 0ULL;
    unsafe {
        if (cuMemAlloc_v2(&d, bytes) != 0) return 0UL;
    }
    return (unsigned long)d;
}

// Copies the named tensors (bf16, back to back) into one new device buffer, reading the
// file through 'staging' (stageBytes) in chunks. 0 if a name is missing or a copy fails.
static unsigned long g4c_upload_(&SafetensorsFile st, void* f, const char** names, unsigned long n,
                                 unsigned char* staging, unsigned long stageBytes) {
    unsigned long total = 0UL;
    unsafe {
        for (unsigned long i = 0UL; i < n; i = i + 1UL) {
            long idx = st.find(names[i]);
            if (idx < 0L) { printf("gemma4_cuda: missing tensor %s\n", names[i]); return 0UL; }
            struct SafetensorsEntry* e = (struct SafetensorsEntry*)st.entries.at((unsigned long)idx);
            total = total + (e->dataOffsetEnd - e->dataOffsetStart);
        }
        unsigned long dev = g4c_alloc_(total);
        if (dev == 0UL) return 0UL;
        unsigned long o = 0UL;
        for (unsigned long i = 0UL; i < n; i = i + 1UL) {
            long idx = st.find(names[i]);
            struct SafetensorsEntry* e = (struct SafetensorsEntry*)st.entries.at((unsigned long)idx);
            unsigned long bytes = e->dataOffsetEnd - e->dataOffsetStart;
            if (fseeko(f, (long long)(st.dataBaseOffset + e->dataOffsetStart), 0) != 0) return 0UL;
            unsigned long done = 0UL;
            while (done < bytes) {
                unsigned long c = bytes - done;
                if (c > stageBytes) c = stageBytes;
                if (fread((void*)staging, 1UL, c, f) != c) return 0UL;
                if (!cuda_copy_to_device(dev + o + done, (const void*)staging, c)) return 0UL;
                done = done + c;
            }
            o = o + bytes;
        }
        return dev;
    }
}

// Uploads model.language_model.layers.<l>.<suffix>... (up to 3 tensors).
static unsigned long g4c_upload_layer_(&SafetensorsFile st, void* f, unsigned long l, const char* s0, const char* s1,
                                       const char* s2, unsigned char* staging, unsigned long stageBytes) {
    char b0[160];
    char b1[160];
    char b2[160];
    const char* names[3];
    unsigned long n = 0UL;
    unsafe {
        snprintf(&b0[0], 160UL, "model.language_model.layers.%lu.%s", l, s0);
        names[0] = (const char*)&b0[0];
        n = 1UL;
        if (s1 != (const char*)0) { snprintf(&b1[0], 160UL, "model.language_model.layers.%lu.%s", l, s1); names[1] = (const char*)&b1[0]; n = 2UL; }
        if (s2 != (const char*)0) { snprintf(&b2[0], 160UL, "model.language_model.layers.%lu.%s", l, s2); names[2] = (const char*)&b2[0]; n = 3UL; }
        return g4c_upload_(st, f, &names[0], n, staging, stageBytes);
    }
}

static unsigned long g4c_upload_one_(&SafetensorsFile st, void* f, const char* name, unsigned char* staging, unsigned long stageBytes) {
    const char* names[1];
    unsafe {
        names[0] = name;
        return g4c_upload_(st, f, &names[0], 1UL, staging, stageBytes);
    }
}

// ── Launches (on gpu_cuda's stream, recorded by graph capture) ──────────────────────────
static int g4c_launch_(void* f, unsigned int gx, unsigned int gy, unsigned int bx, unsigned int smem, void** params) {
    struct G4cLaunchAttr at;
    struct G4cLaunchConfig c;
    unsafe {
        at.id = 6;            // CU_LAUNCH_ATTRIBUTE_PROGRAMMATIC_STREAM_SERIALIZATION
        at.pad = 0;
        for (int i = 0; i < 16; i = i + 1) at.val[i] = 0;
        at.val[0] = 1;
        c.gx = gx; c.gy = gy; c.gz = 1U; c.bx = bx; c.by = 1U; c.bz = 1U;
        c.smem = smem; c.pad = 0U;
        c.stream = (void*)cuda_stream_handle();
        c.attrs = (void*)&at;
        c.numAttrs = G4C_PDL ? 1U : 0U;
        c.pad2 = 0U;
        return cuLaunchKernelEx((const void*)&c, f, params, (void**)0) == 0;
    }
}

// y = W xin (N x K bf16 GEMV) with epilogue 'emode'; when 'fin', the last block also does
// the residual step (see the kernel source): x' = (x + rmsnorm(y) * wd) [* scal], written
// to xo, and the next input xs = w1 ? rmsnorm(x') * w1 : x'.
static int g4c_gemv_(&Gemma4Cuda m, unsigned long W, unsigned long N, unsigned long K, unsigned long xin, int emode,
                     unsigned long y, unsigned long mvec, int fin, unsigned long fx, unsigned long fwd, unsigned long fscal,
                     unsigned long fxo, unsigned long fw1, unsigned long counter, float cap) {
    unsigned long blocks = (N + 3UL) / 4UL;
    if (blocks > 132UL * 16UL) blocks = 132UL * 16UL;
    unsigned long best = m.best;
    unsigned long fxs = m.xs;
    int Ni = (int)N;
    int Ki = (int)K;
    int fmode = fin;
    int fuseDelta = fin;
    int wpr = 1;
    void* p[19];
    unsafe {
        p[0] = (void*)&W; p[1] = (void*)&xin; p[2] = (void*)&mvec; p[3] = (void*)&y; p[4] = (void*)&best;
        p[5] = (void*)&fx; p[6] = (void*)&fwd; p[7] = (void*)&fscal; p[8] = (void*)&fxo; p[9] = (void*)&fw1;
        p[10] = (void*)&fxs; p[11] = (void*)&counter; p[12] = (void*)&Ni; p[13] = (void*)&Ki; p[14] = (void*)&cap;
        p[15] = (void*)&emode; p[16] = (void*)&fmode; p[17] = (void*)&fuseDelta; p[18] = (void*)&wpr;
        return g4c_launch_(m.kfn[G4C_K_GEMV], (unsigned int)blocks, 1U, 128U, (unsigned int)(K * 2UL), &p[0]);
    }
}

// One decode step for the token at state.pos, ending with the next token written to
// tokens[pos + 1] (unless that position is still in the prompt) and pos advanced.
static int g4c_step_(&Gemma4Cuda m) {
    unsigned long x = m.xa;
    unsigned long xo = m.xb;
    unsigned long t = 0UL;
    int ok = 1;
    unsafe {
        {   // embedding + PLE token rows
            int H = (int)G4C_H;
            int P = (int)(G4C_NL * G4C_PLE);
            void* p[8];
            p[0] = (void*)&m.state; p[1] = (void*)&m.tokens; p[2] = (void*)&m.embW; p[3] = (void*)&m.pleW;
            p[4] = (void*)&x; p[5] = (void*)&m.pleTok; p[6] = (void*)&H; p[7] = (void*)&P;
            ok = ok && g4c_launch_(m.kfn[G4C_K_EMBED], 264U, 1U, 256U, 0U, &p[0]);
        }
        ok = ok && g4c_gemv_(m, m.pleProjW, G4C_NL * G4C_PLE, G4C_H, x, 0, m.pleRaw, 0UL, 0, 0UL, 0UL, 0UL, 0UL, 0UL, 0UL, (float)0.0);
        {
            int D = (int)G4C_PLE;
            float scale = (float)0.025515518153991442;   // 1 / sqrt(1536)
            void* p[6];
            p[0] = (void*)&m.pleRaw; p[1] = (void*)&m.pleTok; p[2] = (void*)&m.pleNormW; p[3] = (void*)&m.ple;
            p[4] = (void*)&D; p[5] = (void*)&scale;
            ok = ok && g4c_launch_(m.kfn[G4C_K_PLE], (unsigned int)GEMMA4_CUDA_LAYERS, 1U, 256U, (unsigned int)((G4C_PLE + 32UL) * 4UL), &p[0]);
        }
        {   // layer 0's input: rmsnorm(x) * input_layernorm
            void* p[3];
            p[0] = (void*)&x; p[1] = (void*)&m.layers[0].inln; p[2] = (void*)&m.xs;
            ok = ok && g4c_launch_(m.kfn[G4C_K_FINISH], 1U, 1U, 128U, 0U, &p[0]);
        }
        for (unsigned long l = 0UL; l < (unsigned long)GEMMA4_CUDA_LAYERS; l = l + 1UL) {
            struct Gemma4CudaLayer* y = (struct Gemma4CudaLayer*)&m.layers[l];
            unsigned long nq = G4C_NH * y->hd;
            unsigned long nqkv = y->shared ? nq : nq + 2UL * y->hd;
            ok = ok && g4c_gemv_(m, y->wqkv, nqkv, G4C_H, m.xs, 0, m.qkv, 0UL, 0, 0UL, 0UL, 0UL, 0UL, 0UL, 0UL, (float)0.0);
            {
                unsigned long cnt = m.counters + 8UL * 4UL;
                int nh = (int)G4C_NH;
                int hd = (int)y->hd;
                int window = y->full ? 0 : 512;
                int hasKV = y->shared ? 0 : 1;
                int nrot = y->full ? 64 : (int)(y->hd / 2UL);
                float theta = y->full ? (float)1000000.0 : (float)10000.0;
                void* p[15];
                p[0] = (void*)&m.state; p[1] = (void*)&m.qkv; p[2] = (void*)&y->qn; p[3] = (void*)&y->kn; p[4] = (void*)&y->kc;
                p[5] = (void*)&y->vc; p[6] = (void*)&m.part; p[7] = (void*)&m.att; p[8] = (void*)&cnt; p[9] = (void*)&nh;
                p[10] = (void*)&hd; p[11] = (void*)&window; p[12] = (void*)&hasKV; p[13] = (void*)&nrot; p[14] = (void*)&theta;
                unsigned int smem = (unsigned int)(G4C_ATT_CH * 4UL + y->hd * 10UL);
                ok = ok && g4c_launch_(m.kfn[G4C_K_ATTN], (unsigned int)G4C_NH, (unsigned int)m.nsp, 128U, smem, &p[0]);
            }
            // x += rmsnorm(attn) * post_attention; FFN input = rmsnorm(x) * pre_feedforward
            ok = ok && g4c_gemv_(m, y->wo, G4C_H, nq, m.att, 0, m.tmp, 0UL, 1, x, y->postattn, 0UL, xo, y->preffn, m.counters + 4UL, (float)0.0);
            t = x; x = xo; xo = t;
            ok = ok && g4c_gemv_(m, y->wgu, y->ff, G4C_H, m.xs, 1, m.ffh, 0UL, 0, 0UL, 0UL, 0UL, 0UL, 0UL, 0UL, (float)0.0);
            // x += rmsnorm(ffn) * post_feedforward; PLE gate input = x
            ok = ok && g4c_gemv_(m, y->wd, G4C_H, y->ff, m.ffh, 0, m.tmp, 0UL, 1, x, y->postffn, 0UL, xo, 0UL, m.counters + 8UL, (float)0.0);
            t = x; x = xo; xo = t;
            ok = ok && g4c_gemv_(m, y->wpg, G4C_PLE, G4C_H, m.xs, 2, m.ffh, m.ple + l * G4C_PLE * 2UL, 0, 0UL, 0UL, 0UL, 0UL, 0UL, 0UL, (float)0.0);
            // x = (x + rmsnorm(ple) * post_per_layer_input) * layer_scalar; next input norm (or the final norm)
            unsigned long nextNorm = l + 1UL < (unsigned long)GEMMA4_CUDA_LAYERS ? m.layers[l + 1UL].inln : m.finalNormW;
            ok = ok && g4c_gemv_(m, y->wpp, G4C_H, G4C_PLE, m.ffh, 0, m.tmp, 0UL, 1, x, y->postple, y->scal, xo, nextNorm, m.counters + 12UL, (float)0.0);
            t = x; x = xo; xo = t;
        }
        // tied LM head with the final softcap and a running argmax, then advance
        ok = ok && g4c_gemv_(m, m.embW, G4C_V, G4C_H, m.xs, 3, m.logits, 0UL, 0, 0UL, 0UL, 0UL, 0UL, 0UL, 0UL, (float)30.0);
        {
            void* p[3];
            p[0] = (void*)&m.state; p[1] = (void*)&m.best; p[2] = (void*)&m.tokens;
            ok = ok && g4c_launch_(m.kfn[G4C_K_ADVANCE], 1U, 1U, 1U, 0U, &p[0]);
        }
    }
    return ok;
}

struct Gemma4Cuda gemma4_cuda_load(const char* checkpointPath, unsigned long maxSeq) {
    struct Gemma4Cuda m;
    m.ok = 0;
    m.maxSeq = maxSeq;
    m.graph.exec = 0UL;
    long long t0 = g4c_now_ns_();
    if (!cuda_available()) { unsafe { printf("gemma4_cuda: no CUDA GPU\n"); } return m; }
    unsafe {
        void* module = (void*)cuda_load_module((const &char)g4c_kernel_source_(), "gemma4_cuda.cu");
        if (module == (void*)0) return m;
        const char* names[6];
        names[0] = "g4_embed"; names[1] = "g4_ple_combine"; names[2] = "g4_finish";
        names[3] = "g4_gemv"; names[4] = "g4_attn"; names[5] = "g4_advance";
        for (int i = 0; i < 6; i = i + 1) {
            if (cuModuleGetFunction(&m.kfn[i], module, names[i]) != 0) { printf("gemma4_cuda: no kernel %s\n", names[i]); return m; }
        }
    }
    struct SafetensorsFile st = safetensors_open(checkpointPath);
    unsafe {
        if (st.handle.is_null()) { printf("gemma4_cuda: could not open '%s'\n", checkpointPath); return m; }
        void* f = fopen(checkpointPath, "rb");
        if (f == (void*)0) return m;
        unsigned long stageBytes = 64UL << 20;
        unsigned char* staging = (unsigned char*)malloc(stageBytes);
        int good = staging != (unsigned char*)0;
        if (good) {
            m.embW = g4c_upload_one_(&st, f, "model.language_model.embed_tokens.weight", staging, stageBytes);
            m.pleW = g4c_upload_one_(&st, f, "model.language_model.embed_tokens_per_layer.weight", staging, stageBytes);
            m.pleProjW = g4c_upload_one_(&st, f, "model.language_model.per_layer_model_projection.weight", staging, stageBytes);
            m.pleNormW = g4c_upload_one_(&st, f, "model.language_model.per_layer_projection_norm.weight", staging, stageBytes);
            m.finalNormW = g4c_upload_one_(&st, f, "model.language_model.norm.weight", staging, stageBytes);
            good = m.embW != 0UL && m.pleW != 0UL && m.pleProjW != 0UL && m.pleNormW != 0UL && m.finalNormW != 0UL;
        }
        for (unsigned long l = 0UL; good && l < (unsigned long)GEMMA4_CUDA_LAYERS; l = l + 1UL) {
            struct Gemma4CudaLayer* y = (struct Gemma4CudaLayer*)&m.layers[l];
            y->full = l % 5UL == 4UL;
            y->shared = l >= G4C_SHARED0;
            y->hd = y->full ? 512UL : 256UL;
            y->ff = y->shared ? 12288UL : 6144UL;
            if (y->shared) y->wqkv = g4c_upload_layer_(&st, f, l, "self_attn.q_proj.weight", (const char*)0, (const char*)0, staging, stageBytes);
            else y->wqkv = g4c_upload_layer_(&st, f, l, "self_attn.q_proj.weight", "self_attn.k_proj.weight", "self_attn.v_proj.weight", staging, stageBytes);
            y->wo = g4c_upload_layer_(&st, f, l, "self_attn.o_proj.weight", (const char*)0, (const char*)0, staging, stageBytes);
            y->wgu = g4c_upload_layer_(&st, f, l, "mlp.gate_proj.weight", "mlp.up_proj.weight", (const char*)0, staging, stageBytes);
            y->wd = g4c_upload_layer_(&st, f, l, "mlp.down_proj.weight", (const char*)0, (const char*)0, staging, stageBytes);
            y->wpg = g4c_upload_layer_(&st, f, l, "per_layer_input_gate.weight", (const char*)0, (const char*)0, staging, stageBytes);
            y->wpp = g4c_upload_layer_(&st, f, l, "per_layer_projection.weight", (const char*)0, (const char*)0, staging, stageBytes);
            y->inln = g4c_upload_layer_(&st, f, l, "input_layernorm.weight", (const char*)0, (const char*)0, staging, stageBytes);
            y->postattn = g4c_upload_layer_(&st, f, l, "post_attention_layernorm.weight", (const char*)0, (const char*)0, staging, stageBytes);
            y->preffn = g4c_upload_layer_(&st, f, l, "pre_feedforward_layernorm.weight", (const char*)0, (const char*)0, staging, stageBytes);
            y->postffn = g4c_upload_layer_(&st, f, l, "post_feedforward_layernorm.weight", (const char*)0, (const char*)0, staging, stageBytes);
            y->postple = g4c_upload_layer_(&st, f, l, "post_per_layer_input_norm.weight", (const char*)0, (const char*)0, staging, stageBytes);
            y->qn = g4c_upload_layer_(&st, f, l, "self_attn.q_norm.weight", (const char*)0, (const char*)0, staging, stageBytes);
            y->kn = y->shared ? 0UL : g4c_upload_layer_(&st, f, l, "self_attn.k_norm.weight", (const char*)0, (const char*)0, staging, stageBytes);
            y->scal = g4c_upload_layer_(&st, f, l, "layer_scalar", (const char*)0, (const char*)0, staging, stageBytes);
            good = y->wqkv != 0UL && y->wo != 0UL && y->wgu != 0UL && y->wd != 0UL && y->wpg != 0UL && y->wpp != 0UL &&
                   y->inln != 0UL && y->postattn != 0UL && y->preffn != 0UL && y->postffn != 0UL && y->postple != 0UL &&
                   y->qn != 0UL && (y->shared || y->kn != 0UL) && y->scal != 0UL;
            if (good && !y->shared) {
                y->kc = g4c_alloc_(maxSeq * y->hd * 2UL);
                y->vc = g4c_alloc_(maxSeq * y->hd * 2UL);
                good = y->kc != 0UL && y->vc != 0UL;
            }
        }
        if (staging != (unsigned char*)0) free((void*)staging);
        fclose(f);
        st.close_();
        if (!good) { printf("gemma4_cuda: loading the checkpoint failed\n"); return m; }
        for (unsigned long l = G4C_SHARED0; l < (unsigned long)GEMMA4_CUDA_LAYERS; l = l + 1UL) {
            unsigned long src = m.layers[l].full ? 14UL : 13UL;   // last full / sliding layer before the shared ones
            m.layers[l].kc = m.layers[src].kc;
            m.layers[l].vc = m.layers[src].vc;
        }
        // activations and step state
        m.nsp = (maxSeq + G4C_ATT_CH - 1UL) / G4C_ATT_CH;
        m.xa = g4c_alloc_(G4C_H * 2UL); m.xb = g4c_alloc_(G4C_H * 2UL); m.xs = g4c_alloc_(12288UL * 2UL);
        m.pleTok = g4c_alloc_(G4C_NL * G4C_PLE * 2UL); m.pleRaw = g4c_alloc_(G4C_NL * G4C_PLE * 2UL);
        m.ple = g4c_alloc_(G4C_NL * G4C_PLE * 2UL);
        m.qkv = g4c_alloc_((G4C_NH + 2UL) * 512UL * 2UL); m.att = g4c_alloc_(G4C_NH * 512UL * 2UL);
        m.tmp = g4c_alloc_(G4C_H * 2UL); m.ffh = g4c_alloc_(12288UL * 2UL); m.logits = g4c_alloc_(G4C_V * 2UL);
        m.part = g4c_alloc_(G4C_NH * m.nsp * (512UL + 2UL) * 4UL);
        m.state = g4c_alloc_(16UL); m.tokens = g4c_alloc_(maxSeq * 4UL); m.best = g4c_alloc_(8UL); m.counters = g4c_alloc_(64UL * 4UL);
        if (m.xa == 0UL || m.xb == 0UL || m.xs == 0UL || m.pleTok == 0UL || m.pleRaw == 0UL || m.ple == 0UL || m.qkv == 0UL ||
            m.att == 0UL || m.tmp == 0UL || m.ffh == 0UL || m.logits == 0UL || m.part == 0UL || m.state == 0UL ||
            m.tokens == 0UL || m.best == 0UL || m.counters == 0UL) return m;
        cuMemsetD8_v2((unsigned long long)m.best, (unsigned char)0, 8UL);
        cuMemsetD8_v2((unsigned long long)m.counters, (unsigned char)0, 256UL);
        cuda_sync();
        // capture one step as a graph
        if (!cuda_capture_begin()) return m;
        int okStep = g4c_step_(&m);
        m.graph = cuda_capture_end();
        if (!okStep || m.graph.exec == 0UL) { printf("gemma4_cuda: graph capture failed\n"); return m; }
    }
    m.loadSeconds = (double)(g4c_now_ns_() - t0) * 1e-9;
    m.ok = 1;
    return m;
}

static int g4c_reset_(&Gemma4Cuda m, const int* prompt, unsigned long promptLen) {
    int st[4];
    unsafe {
        st[0] = 0; st[1] = (int)promptLen; st[2] = (int)m.maxSeq; st[3] = 0;
        if (!cuda_copy_to_device(m.state, (const void*)&st[0], 16UL)) return 0;
        return cuda_copy_to_device(m.tokens, (const void*)prompt, promptLen * 4UL);
    }
}

unsigned long gemma4_cuda_generate(&Gemma4Cuda m, const int* prompt, unsigned long promptLen,
                                   unsigned long nNew, int* out, int stopAtEos, int streaming,
                                   void* ctx, Gemma4CudaTokenFn onToken, &Gemma4CudaTiming timing) {
    timing.prefillMs = 0.0;
    timing.decodeMs = 0.0;
    timing.decodeSteps = 0UL;
    if (!m.ok || promptLen == 0UL || nNew == 0UL) return 0UL;
    if (promptLen + nNew > m.maxSeq) nNew = m.maxSeq - promptLen;
    if (!g4c_reset_(m, prompt, promptLen)) return 0UL;
    unsigned long got = 0UL;
    unsafe {
        long long t0 = g4c_now_ns_();
        for (unsigned long i = 0UL; i < promptLen; i = i + 1UL) cuda_graph_launch(m.graph);
        if (!cuda_copy_to_host((void*)&out[0], m.tokens + promptLen * 4UL, 4UL)) return 0UL;
        long long t1 = g4c_now_ns_();
        timing.prefillMs = (double)(t1 - t0) * 1e-6;
        got = 1UL;
        if (streaming) onToken(ctx, out[0]);
        if (stopAtEos && out[0] == 1) return got;
        if (streaming) {
            while (got < nNew) {
                cuda_graph_launch(m.graph);
                if (!cuda_copy_to_host((void*)&out[got], m.tokens + (promptLen + got) * 4UL, 4UL)) break;
                onToken(ctx, out[got]);
                got = got + 1UL;
                if (stopAtEos && out[got - 1UL] == 1) break;
            }
        } else {
            for (unsigned long i = 1UL; i < nNew; i = i + 1UL) cuda_graph_launch(m.graph);
            if (nNew > 1UL && cuda_copy_to_host((void*)&out[1], m.tokens + (promptLen + 1UL) * 4UL, (nNew - 1UL) * 4UL)) got = nNew;
        }
        timing.decodeMs = (double)(g4c_now_ns_() - t1) * 1e-6;
        timing.decodeSteps = got - 1UL;
    }
    return got;
}

int gemma4_cuda_prompt_logits(&Gemma4Cuda m, const int* prompt, unsigned long promptLen, float* logitsOut) {
    if (!m.ok || promptLen == 0UL) return 0;
    if (!g4c_reset_(m, prompt, promptLen)) return 0;
    unsafe {
        for (unsigned long i = 0UL; i < promptLen; i = i + 1UL) cuda_graph_launch(m.graph);
        unsigned short* raw = (unsigned short*)malloc(G4C_V * 2UL);
        if (raw == (unsigned short*)0) return 0;
        int ok = cuda_copy_to_host((void*)raw, m.logits, G4C_V * 2UL);
        for (unsigned long i = 0UL; ok && i < G4C_V; i = i + 1UL) {
            unsigned int u = ((unsigned int)raw[i]) << 16;
            logitsOut[i] = *(float*)&u;
        }
        free((void*)raw);
        return ok;
    }
}

void gemma4_cuda_free(&Gemma4Cuda m) {
    if (m.graph.exec != 0UL) m.graph.free();
    unsafe {
        unsigned long* bufs[21];
        bufs[0] = &m.embW; bufs[1] = &m.pleW; bufs[2] = &m.pleProjW; bufs[3] = &m.pleNormW; bufs[4] = &m.finalNormW;
        bufs[5] = &m.xa; bufs[6] = &m.xb; bufs[7] = &m.xs; bufs[8] = &m.pleTok; bufs[9] = &m.pleRaw; bufs[10] = &m.ple;
        bufs[11] = &m.qkv; bufs[12] = &m.att; bufs[13] = &m.tmp; bufs[14] = &m.ffh; bufs[15] = &m.logits;
        bufs[16] = &m.part; bufs[17] = &m.state; bufs[18] = &m.tokens; bufs[19] = &m.best; bufs[20] = &m.counters;
        for (int i = 0; i < 21; i = i + 1) {
            if (*bufs[i] != 0UL) cuMemFree_v2((unsigned long long)*bufs[i]);
            *bufs[i] = 0UL;
        }
        for (unsigned long l = 0UL; l < (unsigned long)GEMMA4_CUDA_LAYERS; l = l + 1UL) {
            struct Gemma4CudaLayer* y = (struct Gemma4CudaLayer*)&m.layers[l];
            unsigned long w[16];
            w[0] = y->wqkv; w[1] = y->wo; w[2] = y->wgu; w[3] = y->wd; w[4] = y->wpg; w[5] = y->wpp; w[6] = y->inln;
            w[7] = y->postattn; w[8] = y->preffn; w[9] = y->postffn; w[10] = y->postple; w[11] = y->qn; w[12] = y->kn;
            w[13] = y->scal; w[14] = y->shared ? 0UL : y->kc; w[15] = y->shared ? 0UL : y->vc;
            for (int i = 0; i < 16; i = i + 1) if (w[i] != 0UL) cuMemFree_v2((unsigned long long)w[i]);
        }
    }
    m.ok = 0;
}

} // namespace std
