// SafeC Standard Library — Gemma 4 bfloat16 tensor implementation (see
// gemma4_bf16.h).
#pragma once
#include "gemma4_bf16.h"
#include <std/ml/gpu_mps.h>
#include <std/ml/tensor_half.h>
#include <std/collections/map.h>
#include <std/math.h>

namespace std {

extern void* malloc(unsigned long size);
extern void  free(void* ptr);

struct Gemma4Bf16Tensor gemma4_bf16_new_1d(unsigned long len) {
    struct Gemma4Bf16Tensor t;
    t.shape0 = len;
    t.shape1 = 0UL;
    t.ndim = 1UL;
    t.size = len;
    unsafe { t.data = (unsigned short*)malloc(sizeof(unsigned short) * len); }
    return t;
}

struct Gemma4Bf16Tensor gemma4_bf16_new_2d(unsigned long rows, unsigned long cols) {
    struct Gemma4Bf16Tensor t;
    t.shape0 = rows;
    t.shape1 = cols;
    t.ndim = 2UL;
    t.size = rows * cols;
    unsafe { t.data = (unsigned short*)malloc(sizeof(unsigned short) * t.size); }
    return t;
}

void gemma4_bf16_free(&Gemma4Bf16Tensor t) {
    unsafe {
        if (t.data != (unsigned short*)0) { free((void*)t.data); }
    }
    t.data = (unsigned short*)0;
    t.shape0 = 0UL; t.shape1 = 0UL; t.ndim = 0UL; t.size = 0UL;
}

struct Gemma4Bf16Tensor gemma4_bf16_from_f32(const &FloatTensor t) {
    unsigned long s0; unsigned long s1; unsigned long nd;
    unsafe {
        s0 = t->shape[0];
        nd = t->ndim;
        if (nd > 1UL) { s1 = t->shape[1]; } else { s1 = 0UL; }
    }
    struct Gemma4Bf16Tensor out;
    if (nd > 1UL) { out = gemma4_bf16_new_2d(s0, s1); } else { out = gemma4_bf16_new_1d(s0); }
    unsafe { f32_to_bf16_bulk((const float*)t->data, out.data, out.size); }
    return out;
}

FloatTensor* gemma4_bf16_to_f32(const &Gemma4Bf16Tensor t) {
    FloatTensor* out;
    if (t.ndim > 1UL) { out = FloatTensor::new_2d(t.shape0, t.shape1, 0); } else { out = FloatTensor::new_1d(t.shape0, 0); }
    unsafe { bf16_to_f32_bulk(t.data, (float*)out->data, t.size); }
    return out;
}

// ── real bf16 GPU matmul ─────────────────────────────────────────────────
static int __g4bf16_mpsChecked = 0;
static int __g4bf16_mpsOk = 0;
static int __g4bf16_mps_ok() {
    if (!__g4bf16_mpsChecked) {
        __g4bf16_mpsOk = mps_available();
        __g4bf16_mpsChecked = 1;
    }
    return __g4bf16_mpsOk;
}

// Persistent bf16 weight buffer cache, keyed by the weight's own raw bit
// buffer pointer (stable for the weight's lifetime) -- same shape as
// gemma4_math.sc's f32 weight cache, just uploading the ALREADY-bf16 raw
// bits directly (mps_upload_persistent is a dtype-agnostic raw byte
// upload -- see gpu_mps.sc; no f32 round trip anywhere in this path).
static struct HashMap<unsigned short*, void*> __g4bf16_weightBufCache;
static int __g4bf16_weightBufCacheInit = 0;

static void __g4bf16_ensure_weight_cache() {
    if (!__g4bf16_weightBufCacheInit) {
        __g4bf16_weightBufCache = HashMap::new();
        __g4bf16_weightBufCacheInit = 1;
    }
}

static void* __g4bf16_get_weight_buf(const &Gemma4Bf16Tensor w) {
    __g4bf16_ensure_weight_cache();
    unsafe {
        unsigned short* wPtr = w.data;
        void** found = (void**)__g4bf16_weightBufCache.get(wPtr);
        if (found != (void**)0) { return *found; }

        unsigned long bytes = w.size * 2UL; // 2 bytes/element (bf16)
        void* buf = mps_upload_persistent((const float*)w.data, bytes);
        if (buf != (void*)0) {
            __g4bf16_weightBufCache.insert(wPtr, buf);
        }
        return buf;
    }
}

void* gemma4_bf16_get_weight_buf(const &Gemma4Bf16Tensor w) {
    if (!__g4bf16_mps_ok()) { return (void*)0; }
    return __g4bf16_get_weight_buf(w);
}

void gemma4_bf16_release_weight_buf(const &Gemma4Bf16Tensor w) {
    if (!__g4bf16_weightBufCacheInit) { return; }
    unsafe {
        unsigned short* wPtr = w.data;
        void** found = (void**)__g4bf16_weightBufCache.get(wPtr);
        if (found != (void**)0) {
            mps_release_persistent(*found, w.size * 2UL);
            __g4bf16_weightBufCache.remove(wPtr);
        }
    }
}

// Forces gemma4_bf16_linear to skip the MPS GPU path even when a Metal
// device is available -- for CPU-vs-GPU benchmarking and for machines
// that want CPU inference explicitly. Off (0) by default.
static int __g4bf16_forceCpu = 0;
void gemma4_bf16_set_force_cpu(int forceCpu) { __g4bf16_forceCpu = forceCpu; }
int gemma4_bf16_get_force_cpu() { return __g4bf16_forceCpu; }

// Standard CBLAS C ABI (Accelerate on macOS; identical signature on
// OpenBLAS/MKL elsewhere -- see std/ml/tensor_blas.sc, which declares
// the same extern for the exact same reason: no C-header-interop story
// in SafeC yet). order: 101=RowMajor, 102=ColMajor. trans: 111=NoTrans,
// 112=Trans.
extern void cblas_sgemm(int order, int transA, int transB,
                         int M, int N, int K,
                         float alpha, const float* A, int lda,
                         const float* B, int ldb,
                         float beta, float* C, int ldc);

// CPU fallback: real Accelerate-BLAS compute (cblas_sgemm), not a naive
// triple loop -- widen bf16->f32 (exact), ONE cblas_sgemm call computing
// y = x @ w^T directly (TransB=CblasTrans reads w's [outF,inF] storage
// as its transpose without ever physically materializing w^T, the same
// trick std/ml/tensor_blas.sc's own backward pass uses), narrow the f32
// result back to bf16.
// CPU path for a few rows (decode; speculative verification; a short
// prompt's prefill): one BF16Tensor::gemv_batch over the bf16 weight as stored
// -- no float32 copy of the matrix, which __g4bf16_linear_cpu_blas makes on
// every call (1.6 GB for the tied LM head) -- reading the weights once for
// all the rows.
#define G4BF16_GEMV_MAX_ROWS 8UL
static struct Gemma4Bf16Tensor __g4bf16_linear_cpu_gemv(const &Gemma4Bf16Tensor x, const &Gemma4Bf16Tensor w,
                                                          unsigned long rows, unsigned long inF, unsigned long outF) {
    struct Gemma4Bf16Tensor result = gemma4_bf16_new_2d(rows, outF);
    // A BF16Tensor view of w's storage (gemv reads only data/shape/ndim).
    BF16Tensor wv;
    unsigned long wvShape[2];
    float* xF; float* yF;
    unsafe {
        wvShape[0] = outF; wvShape[1] = inF;
        wv.data = (&heap BF16)w.data;
        wv.shape = (&heap unsigned long)&wvShape[0]; wv.ndim = 2UL; wv.size = outF * inF;
        xF = (float*)malloc(sizeof(float) * inF * rows);
        yF = (float*)malloc(sizeof(float) * outF * rows);
        bf16_to_f32_bulk(&x.data[0], xF, inF * rows);
        wv.gemv_batch((const &float)xF, rows, (&float)yF);
        f32_to_bf16_bulk((const float*)yF, &result.data[0], outF * rows);
        free((void*)xF); free((void*)yF);
    }
    return result;
}

static struct Gemma4Bf16Tensor __g4bf16_linear_cpu_blas(const &Gemma4Bf16Tensor x, const &Gemma4Bf16Tensor w,
                                                          unsigned long rows, unsigned long inF, unsigned long outF) {
    float* xF; float* wF; float* outF32;
    unsafe {
        xF = (float*)malloc(sizeof(float) * x.size);
        wF = (float*)malloc(sizeof(float) * w.size);
        outF32 = (float*)malloc(sizeof(float) * rows * outF);
        bf16_to_f32_bulk(x.data, xF, x.size);
        bf16_to_f32_bulk(w.data, wF, w.size);
        cblas_sgemm(101, 111, 112, (int)rows, (int)outF, (int)inF,
                    (float)1.0, (const float*)xF, (int)inF,
                    (const float*)wF, (int)inF,
                    (float)0.0, outF32, (int)outF);
    }
    struct Gemma4Bf16Tensor result = gemma4_bf16_new_2d(rows, outF);
    unsafe {
        f32_to_bf16_bulk((const float*)outF32, result.data, result.size);
        free((void*)xF); free((void*)wF); free((void*)outF32);
    }
    return result;
}

struct Gemma4Bf16Tensor gemma4_bf16_linear(const &Gemma4Bf16Tensor x, const &Gemma4Bf16Tensor w) {
    unsigned long rows = x.shape0;
    unsigned long inF = x.shape1;
    unsigned long outF = w.shape0;

    if (!__g4bf16_forceCpu && __g4bf16_mps_ok()) {
        void* wBuf = __g4bf16_get_weight_buf(w);
        if (wBuf != (void*)0) {
            unsigned long xBytes = x.size * 2UL;
            void* xBuf;
            unsafe { xBuf = mps_upload_persistent((const float*)x.data, xBytes); }
            if (xBuf != (void*)0) {
                struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(rows, outF);
                int rc;
                unsafe {
                    mps_batch_begin();
                    // Same (M, inFeatures, outFeatures) argument convention
                    // as __g4bf16_linear_cpu_blas below -- matmul_abt_
                    // kernel_bf16 computes out[M,K] = a[M,N] . b^T[K,N].
                    rc = mps_matmul_abt_bf16_persistent(xBuf, wBuf, out.data, rows, inF, outF);
                    mps_batch_end();
                    mps_release_persistent(xBuf, xBytes);
                }
                if (rc) { return out; }
                gemma4_bf16_free(&out);
            }
        }
    }

    if (rows <= G4BF16_GEMV_MAX_ROWS) return __g4bf16_linear_cpu_gemv(x, w, rows, inF, outF);
    return __g4bf16_linear_cpu_blas(x, w, rows, inF, outF);
}

// ── RMSNorm, bf16-native ─────────────────────────────────────────────────
// Gemma4RMSNorm (== Gemma3nRMSNorm's formula -- see the old gemma4_math.sc
// derivation this replaces): normed = x * rsqrt(mean(x^2)+eps), scaled by
// 'weight'. The reduction (sumSq) and rsqrt necessarily happen in float
// (bf16 arithmetic directly would lose far too much precision for a
// variance computation) -- each element widens to float for the two
// passes over the row, narrows back to bf16 only for the final write,
// same "widen at the point of use, narrow at the point of storage"
// discipline as everywhere else in this file, just element-at-a-time
// instead of through a temporary whole-row FloatTensor.
struct Gemma4Bf16Tensor gemma4_bf16_rmsnorm_rows(const &Gemma4Bf16Tensor x, const &Gemma4Bf16Tensor weight, float eps) {
    unsigned long rows = x.shape0; unsigned long cols = x.shape1;
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(rows, cols);
    unsafe {
        unsigned long r = 0UL;
        while (r < rows) {
            float sumSq = (float)0.0;
            unsigned long c = 0UL;
            while (c < cols) {
                float v = bf16_to_f32(x.data[r * cols + c]);
                sumSq = sumSq + v * v;
                c = c + 1UL;
            }
            float meanSq = sumSq / (float)cols;
            float invRms = (float)1.0 / sqrt_f(meanSq + eps);
            c = 0UL;
            while (c < cols) {
                float v = bf16_to_f32(x.data[r * cols + c]);
                float wv = bf16_to_f32(weight.data[c]);
                out.data[r * cols + c] = f32_to_bf16(v * invRms * wv);
                c = c + 1UL;
            }
            r = r + 1UL;
        }
    }
    return out;
}

// Same formula, no weight multiply (attention's v_norm -- with_scale=False).
struct Gemma4Bf16Tensor gemma4_bf16_rmsnorm_rows_noscale(const &Gemma4Bf16Tensor x, float eps) {
    unsigned long rows = x.shape0; unsigned long cols = x.shape1;
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(rows, cols);
    unsafe {
        unsigned long r = 0UL;
        while (r < rows) {
            float sumSq = (float)0.0;
            unsigned long c = 0UL;
            while (c < cols) {
                float v = bf16_to_f32(x.data[r * cols + c]);
                sumSq = sumSq + v * v;
                c = c + 1UL;
            }
            float meanSq = sumSq / (float)cols;
            float invRms = (float)1.0 / sqrt_f(meanSq + eps);
            c = 0UL;
            while (c < cols) {
                float v = bf16_to_f32(x.data[r * cols + c]);
                out.data[r * cols + c] = f32_to_bf16(v * invRms);
                c = c + 1UL;
            }
            r = r + 1UL;
        }
    }
    return out;
}

struct Gemma4Bf16Tensor gemma4_bf16_add(const &Gemma4Bf16Tensor a, const &Gemma4Bf16Tensor b) {
    FloatTensor* aF = gemma4_bf16_to_f32(a);
    FloatTensor* bF = gemma4_bf16_to_f32(b);
    struct Gemma4Bf16Tensor result;
    unsafe {
        &FloatTensor outF = tensor_add(aF, bF);
        aF->free(); bF->free();
        result = gemma4_bf16_from_f32(outF);
        outF.free();
    }
    return result;
}

struct Gemma4Bf16Tensor gemma4_bf16_mul(const &Gemma4Bf16Tensor a, const &Gemma4Bf16Tensor b) {
    FloatTensor* aF = gemma4_bf16_to_f32(a);
    FloatTensor* bF = gemma4_bf16_to_f32(b);
    struct Gemma4Bf16Tensor result;
    unsafe {
        &FloatTensor outF = tensor_mul(aF, bF);
        aF->free(); bF->free();
        result = gemma4_bf16_from_f32(outF);
        outF.free();
    }
    return result;
}

struct Gemma4Bf16Tensor gemma4_bf16_scale(const &Gemma4Bf16Tensor x, float k) {
    FloatTensor* xF = gemma4_bf16_to_f32(x);
    struct Gemma4Bf16Tensor result;
    unsafe {
        &FloatTensor outF = xF->scale(k);
        xF->free();
        result = gemma4_bf16_from_f32(outF);
        outF.free();
    }
    return result;
}

// tanh(x/cap)*cap, elementwise, bf16-native -- tanh_f is CPU libm (see
// std/math.h), not the Metal GPU tanh() this project's own FFN work
// found overflowing for large arguments (see DESIGN_NOTES.md); libm's
// tanh saturates correctly for any finite input, so no clamp is needed
// here the way gpu_mps_kernels.metal's gated_gelu_kernel needed one.
struct Gemma4Bf16Tensor gemma4_bf16_softcap(const &Gemma4Bf16Tensor x, float cap) {
    struct Gemma4Bf16Tensor out;
    if (x.ndim > 1UL) { out = gemma4_bf16_new_2d(x.shape0, x.shape1); } else { out = gemma4_bf16_new_1d(x.shape0); }
    unsafe {
        unsigned long i = 0UL;
        while (i < x.size) {
            float v = bf16_to_f32(x.data[i]);
            out.data[i] = f32_to_bf16(tanh_f(v / cap) * cap);
            i = i + 1UL;
        }
    }
    return out;
}

struct Gemma4Bf16Tensor gemma4_bf16_slice_cols(const &Gemma4Bf16Tensor x, unsigned long colStart, unsigned long width) {
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(x.shape0, width);
    unsafe {
        unsigned long r = 0UL;
        while (r < x.shape0) {
            unsigned long c = 0UL;
            while (c < width) {
                out.data[r * width + c] = x.data[r * x.shape1 + colStart + c];
                c = c + 1UL;
            }
            r = r + 1UL;
        }
    }
    return out;
}

void gemma4_bf16_write_cols(&Gemma4Bf16Tensor dst, const &Gemma4Bf16Tensor src, unsigned long colStart, unsigned long width) {
    unsigned long rows = src.shape0; unsigned long dstCols = dst.shape1;
    unsafe {
        unsigned long r = 0UL;
        while (r < rows) {
            unsigned long c = 0UL;
            while (c < width) {
                dst.data[r * dstCols + colStart + c] = src.data[r * width + c];
                c = c + 1UL;
            }
            r = r + 1UL;
        }
    }
}

struct Gemma4Bf16Tensor gemma4_bf16_concat_rows(const &Gemma4Bf16Tensor a, const &Gemma4Bf16Tensor b) {
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(a.shape0 + b.shape0, a.shape1);
    unsafe {
        unsigned long i = 0UL;
        while (i < a.size) { out.data[i] = a.data[i]; i = i + 1UL; }
        unsigned long j = 0UL;
        while (j < b.size) { out.data[a.size + j] = b.data[j]; j = j + 1UL; }
    }
    return out;
}

struct Gemma4Bf16Tensor gemma4_bf16_embed_lookup_row(const &Gemma4Bf16Tensor embedTable, unsigned long tokenId, float scale) {
    unsigned long hiddenSize = embedTable.shape1;
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_1d(hiddenSize);
    unsafe {
        unsigned long base = tokenId * hiddenSize;
        unsigned long c = 0UL;
        while (c < hiddenSize) {
            float v = bf16_to_f32(embedTable.data[base + c]) * scale;
            out.data[c] = f32_to_bf16(v);
            c = c + 1UL;
        }
    }
    return out;
}

struct Gemma4Bf16Tensor gemma4_bf16_embed_lookup_rows(const &Gemma4Bf16Tensor embedTable, const int* tokenIds, unsigned long count, float scale) {
    unsigned long hiddenSize = embedTable.shape1;
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(count, hiddenSize);
    unsafe {
        unsigned long r = 0UL;
        while (r < count) {
            unsigned long tokenId = (unsigned long)tokenIds[r];
            unsigned long base = tokenId * hiddenSize;
            unsigned long c = 0UL;
            while (c < hiddenSize) {
                float v = bf16_to_f32(embedTable.data[base + c]) * scale;
                out.data[r * hiddenSize + c] = f32_to_bf16(v);
                c = c + 1UL;
            }
            r = r + 1UL;
        }
    }
    return out;
}

} // namespace std
