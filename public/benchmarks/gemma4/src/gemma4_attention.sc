// SafeC Standard Library — Gemma 4 attention implementation, bf16
// throughout (see gemma4_attention.h).
#pragma once
#include "gemma4_attention.h"
#include <std/math.h>
#include <std/ml/gpu_mps.h>

namespace std {

extern void* malloc(unsigned long size);
extern void  free(void* ptr);
extern void* memcpy(void* dst, const void* src, unsigned long n);

// ── Per-head column slice helpers ────────────────────────────────────────
// Gemma4Bf16Tensor is 2D-only with no view/stride mechanism, so
// extracting "head h's headDim columns out of a [rows, numHeads*headDim]
// tensor" means an actual copy, not a zero-cost reshape -- acceptable
// here since q_norm/k_norm/RoPE only run once per token per layer, nowhere
// near the O(seqLen*kvSeqLen*headDim) cost of the attention score loop
// below, which deliberately does NOT go through per-head copies (see
// __gemma4_bf16_attn_head/_paged).
static struct Gemma4Bf16Tensor __gemma4_bf16_slice_head_cols(const &Gemma4Bf16Tensor x, unsigned long headIdx, unsigned long headDim) {
    unsigned long rows = x.shape0; unsigned long cols = x.shape1;
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(rows, headDim);
    unsigned long colOff = headIdx * headDim;
    unsafe {
        unsigned long r = 0UL;
        while (r < rows) {
            unsigned long d = 0UL;
            while (d < headDim) {
                out.data[r * headDim + d] = x.data[r * cols + colOff + d];
                d = d + 1UL;
            }
            r = r + 1UL;
        }
    }
    return out;
}

static void __gemma4_bf16_write_head_cols(&Gemma4Bf16Tensor dst, const &Gemma4Bf16Tensor src, unsigned long headIdx, unsigned long headDim) {
    unsigned long rows = src.shape0; unsigned long dstCols = dst.shape1;
    unsigned long colOff = headIdx * headDim;
    unsafe {
        unsigned long r = 0UL;
        while (r < rows) {
            unsigned long d = 0UL;
            while (d < headDim) {
                dst.data[r * dstCols + colOff + d] = src.data[r * headDim + d];
                d = d + 1UL;
            }
            r = r + 1UL;
        }
    }
}

// ── Q/K/V projections ────────────────────────────────────────────────────
struct Gemma4Bf16Tensor gemma4_bf16_attn_project_q(const &Gemma4Bf16Tensor h, const &Gemma4Bf16Tensor qProjW, const &Gemma4Bf16Tensor qNormW,
                                                     float eps, &Gemma4RopeTable ropeTable,
                                                     unsigned long headDim, unsigned long numHeads) {
    struct Gemma4Bf16Tensor qLin = gemma4_bf16_linear(h, qProjW);
    unsigned long seqLen = h.shape0;
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(seqLen, numHeads * headDim);

    unsigned long hd = 0UL;
    while (hd < numHeads) {
        struct Gemma4Bf16Tensor slice = __gemma4_bf16_slice_head_cols(&qLin, hd, headDim);
        struct Gemma4Bf16Tensor normed = gemma4_bf16_rmsnorm_rows(&slice, qNormW, eps);
        struct Gemma4Bf16Tensor roped = gemma4_bf16_rope_apply(&normed, ropeTable);
        __gemma4_bf16_write_head_cols(&out, &roped, hd, headDim);
        gemma4_bf16_free(&slice); gemma4_bf16_free(&normed); gemma4_bf16_free(&roped);
        hd = hd + 1UL;
    }
    gemma4_bf16_free(&qLin);
    return out;
}

struct Gemma4Bf16Tensor gemma4_bf16_attn_project_k(const &Gemma4Bf16Tensor h, const &Gemma4Bf16Tensor kProjW, const &Gemma4Bf16Tensor kNormW,
                                                     float eps, &Gemma4RopeTable ropeTable,
                                                     unsigned long headDim, unsigned long numKvHeads) {
    struct Gemma4Bf16Tensor kLin = gemma4_bf16_linear(h, kProjW);
    unsigned long seqLen = h.shape0;
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(seqLen, numKvHeads * headDim);

    unsigned long hd = 0UL;
    while (hd < numKvHeads) {
        struct Gemma4Bf16Tensor slice = __gemma4_bf16_slice_head_cols(&kLin, hd, headDim);
        struct Gemma4Bf16Tensor normed = gemma4_bf16_rmsnorm_rows(&slice, kNormW, eps);
        struct Gemma4Bf16Tensor roped = gemma4_bf16_rope_apply(&normed, ropeTable);
        __gemma4_bf16_write_head_cols(&out, &roped, hd, headDim);
        gemma4_bf16_free(&slice); gemma4_bf16_free(&normed); gemma4_bf16_free(&roped);
        hd = hd + 1UL;
    }
    gemma4_bf16_free(&kLin);
    return out;
}

struct Gemma4Bf16Tensor gemma4_bf16_attn_project_v(const &Gemma4Bf16Tensor h, const &Gemma4Bf16Tensor vProjW, float eps,
                                                     unsigned long headDim, unsigned long numKvHeads) {
    struct Gemma4Bf16Tensor vLin = gemma4_bf16_linear(h, vProjW);
    unsigned long seqLen = h.shape0;
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(seqLen, numKvHeads * headDim);

    unsigned long hd = 0UL;
    while (hd < numKvHeads) {
        struct Gemma4Bf16Tensor slice = __gemma4_bf16_slice_head_cols(&vLin, hd, headDim);
        struct Gemma4Bf16Tensor normed = gemma4_bf16_rmsnorm_rows_noscale(&slice, eps);
        __gemma4_bf16_write_head_cols(&out, &normed, hd, headDim);
        gemma4_bf16_free(&slice); gemma4_bf16_free(&normed);
        hd = hd + 1UL;
    }
    gemma4_bf16_free(&vLin);
    return out;
}

void gemma4_bf16_attn_project_qkv_fused(const &Gemma4Bf16Tensor h, const &Gemma4Bf16Tensor qkvProjW,
                                          const &Gemma4Bf16Tensor qNormW, const &Gemma4Bf16Tensor kNormW, float eps,
                                          &Gemma4RopeTable ropeTable,
                                          unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                          struct Gemma4Bf16Tensor* qOut, struct Gemma4Bf16Tensor* kOut, struct Gemma4Bf16Tensor* vOut) {
    struct Gemma4Bf16Tensor raw = gemma4_bf16_linear(h, qkvProjW);
    unsigned long seqLen = h.shape0;

    unsigned long qCols = numHeads * headDim;
    unsigned long kvCols = numKvHeads * headDim;

    struct Gemma4Bf16Tensor qLin = gemma4_bf16_slice_cols(&raw, 0UL, qCols);
    struct Gemma4Bf16Tensor kLin = gemma4_bf16_slice_cols(&raw, qCols, kvCols);
    struct Gemma4Bf16Tensor vLin = gemma4_bf16_slice_cols(&raw, qCols + kvCols, kvCols);
    gemma4_bf16_free(&raw);

    struct Gemma4Bf16Tensor qOutT = gemma4_bf16_new_2d(seqLen, qCols);
    unsigned long hd = 0UL;
    while (hd < numHeads) {
        struct Gemma4Bf16Tensor slice = __gemma4_bf16_slice_head_cols(&qLin, hd, headDim);
        struct Gemma4Bf16Tensor normed = gemma4_bf16_rmsnorm_rows(&slice, qNormW, eps);
        struct Gemma4Bf16Tensor roped = gemma4_bf16_rope_apply(&normed, ropeTable);
        __gemma4_bf16_write_head_cols(&qOutT, &roped, hd, headDim);
        gemma4_bf16_free(&slice); gemma4_bf16_free(&normed); gemma4_bf16_free(&roped);
        hd = hd + 1UL;
    }
    gemma4_bf16_free(&qLin);

    struct Gemma4Bf16Tensor kOutT = gemma4_bf16_new_2d(seqLen, kvCols);
    hd = 0UL;
    while (hd < numKvHeads) {
        struct Gemma4Bf16Tensor slice = __gemma4_bf16_slice_head_cols(&kLin, hd, headDim);
        struct Gemma4Bf16Tensor normed = gemma4_bf16_rmsnorm_rows(&slice, kNormW, eps);
        struct Gemma4Bf16Tensor roped = gemma4_bf16_rope_apply(&normed, ropeTable);
        __gemma4_bf16_write_head_cols(&kOutT, &roped, hd, headDim);
        gemma4_bf16_free(&slice); gemma4_bf16_free(&normed); gemma4_bf16_free(&roped);
        hd = hd + 1UL;
    }
    gemma4_bf16_free(&kLin);

    struct Gemma4Bf16Tensor vOutT = gemma4_bf16_new_2d(seqLen, kvCols);
    hd = 0UL;
    while (hd < numKvHeads) {
        struct Gemma4Bf16Tensor slice = __gemma4_bf16_slice_head_cols(&vLin, hd, headDim);
        struct Gemma4Bf16Tensor normed = gemma4_bf16_rmsnorm_rows_noscale(&slice, eps);
        __gemma4_bf16_write_head_cols(&vOutT, &normed, hd, headDim);
        gemma4_bf16_free(&slice); gemma4_bf16_free(&normed);
        hd = hd + 1UL;
    }
    gemma4_bf16_free(&vLin);

    unsafe { *qOut = qOutT; *kOut = kOutT; *vOut = vOutT; }
}

// ── Scaled-dot-product attention for one head, flat K/V (stateless path) ──
// Writes directly into outConcat's columns [hIdx*headDim,(hIdx+1)*headDim)
// -- deliberately skips the per-head-copy helpers above; this is the
// O(seqLen*kvSeqLen*headDim) hot loop.
//
// SIMD-friendly by construction, not just "bf16-native": every bf16->f32
// widen happens as one bulk conversion of a CONTIGUOUS headDim-wide row
// segment (bf16_to_f32_bulk, std/ml/float16.h) into a plain float scratch
// buffer, immediately followed by an ordinary float*-to-float* loop (the
// dot product, and the weighted-value accumulate below) -- clang -O2
// auto-vectorizes a tight contiguous float loop like that onto NEON on
// Apple Silicon far more reliably than the old per-scalar-interleaved-
// with-a-conversion-call shape did. The weighted-value step is ALSO
// reordered from the original's (output-dim outer, kv-position inner) to
// (kv-position outer, output-dim inner) with a headDim-wide accumulator
// array -- mathematically the identical sum for each output dim (still
// accumulated over kv positions 0..kvSeqLen-1 in ascending order, just
// with the loop nest flipped), so this is bit-identical to the previous
// version, not merely bit-close, but reads each K/V row contiguously
// (cache-friendly) and turns the innermost loop into a vectorizable
// accumulate instead of a strided per-output-scalar reduction.
//
// 'scores'/'qF'/'kvF'/'acc' are caller-allocated (headDim- or kvSeqLen-
// sized float scratch, see gemma4_bf16_attn_forward/_raw_paged below) --
// deliberately NOT a shared/static buffer: gemma4_bf16_model_forward_
// batch calls into this concurrently from multiple worker threads (see
// its own header comment), and each thread's scratch buffers must be its
// own, never touched by another thread.
static void __gemma4_bf16_attn_head(const &Gemma4Bf16Tensor q, const &Gemma4Bf16Tensor k, const &Gemma4Bf16Tensor v,
                                      unsigned long hIdx, unsigned long kvHeadIdx, unsigned long headDim,
                                      unsigned long seqLen, unsigned long kvSeqLen, unsigned long cacheLen,
                                      int isSliding, unsigned long slidingWindow,
                                      &Gemma4Bf16Tensor outConcat,
                                      float* scores, float* qF, float* kvF, float* acc) {
    unsigned long qCols = q.shape1; unsigned long kvCols = k.shape1; unsigned long outCols = outConcat.shape1;
    unsigned long qColOff = hIdx * headDim;
    unsigned long kvColOff = kvHeadIdx * headDim;
    unsigned long outColOff = hIdx * headDim;

    unsigned long i = 0UL;
    while (i < seqLen) {
        unsigned long qPos = cacheLen + i;
        unsafe { bf16_to_f32_bulk(&q.data[i * qCols + qColOff], qF, headDim); }

        unsafe {
            unsigned long j = 0UL;
            while (j < kvSeqLen) {
                int allowed = (j <= qPos) ? 1 : 0;
                if (allowed && isSliding) {
                    allowed = ((qPos - j) < slidingWindow) ? 1 : 0;
                }
                if (allowed) {
                    bf16_to_f32_bulk(&k.data[j * kvCols + kvColOff], kvF, headDim);
                    float dot = (float)0.0;
                    unsigned long d = 0UL;
                    while (d < headDim) { dot = dot + qF[d] * kvF[d]; d = d + 1UL; }
                    scores[j] = dot; // scaling=1.0 -- no multiply (see header comment)
                } else {
                    scores[j] = (float)-1e30;
                }
                j = j + 1UL;
            }

            float maxV = scores[0];
            j = 1UL;
            while (j < kvSeqLen) { if (scores[j] > maxV) { maxV = scores[j]; } j = j + 1UL; }

            float sumExp = (float)0.0;
            j = 0UL;
            while (j < kvSeqLen) {
                scores[j] = exp_f(scores[j] - maxV);
                sumExp = sumExp + scores[j];
                j = j + 1UL;
            }
            j = 0UL;
            while (j < kvSeqLen) { scores[j] = scores[j] / sumExp; j = j + 1UL; }

            unsigned long d = 0UL;
            while (d < headDim) { acc[d] = (float)0.0; d = d + 1UL; }

            j = 0UL;
            while (j < kvSeqLen) {
                bf16_to_f32_bulk(&v.data[j * kvCols + kvColOff], kvF, headDim);
                float sj = scores[j];
                d = 0UL;
                while (d < headDim) { acc[d] = acc[d] + sj * kvF[d]; d = d + 1UL; }
                j = j + 1UL;
            }

            d = 0UL;
            while (d < headDim) {
                outConcat.data[i * outCols + outColOff + d] = f32_to_bf16(acc[d]);
                d = d + 1UL;
            }
        }
        i = i + 1UL;
    }
}

struct Gemma4Bf16Tensor gemma4_bf16_attn_forward(const &Gemma4Bf16Tensor q, const &Gemma4Bf16Tensor k, const &Gemma4Bf16Tensor v, const &Gemma4Bf16Tensor oProjW,
                                                    unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                                    unsigned long cacheLen, int isSliding, unsigned long slidingWindow) {
    unsigned long seqLen = q.shape0;
    unsigned long kvSeqLen = k.shape0;
    unsigned long groupSize = numHeads / numKvHeads;

    struct Gemma4Bf16Tensor concatOut = gemma4_bf16_new_2d(seqLen, numHeads * headDim);

    float* scores; float* qF; float* kvF; float* acc;
    unsafe {
        scores = (float*)malloc(sizeof(float) * kvSeqLen);
        qF = (float*)malloc(sizeof(float) * headDim);
        kvF = (float*)malloc(sizeof(float) * headDim);
        acc = (float*)malloc(sizeof(float) * headDim);
    }

    unsigned long hIdx = 0UL;
    while (hIdx < numHeads) {
        unsigned long kvHeadIdx = hIdx / groupSize;
        __gemma4_bf16_attn_head(q, k, v, hIdx, kvHeadIdx, headDim, seqLen, kvSeqLen, cacheLen,
                                 isSliding, slidingWindow, &concatOut, scores, qF, kvF, acc);
        hIdx = hIdx + 1UL;
    }
    unsafe { free((void*)scores); free((void*)qF); free((void*)kvF); free((void*)acc); }

    struct Gemma4Bf16Tensor attnOut = gemma4_bf16_linear(&concatOut, oProjW);
    gemma4_bf16_free(&concatOut);
    return attnOut;
}

// ── Paged KV cache storage ────────────────────────────────────────────────
void gemma4_bf16_kv_pages_append(&Vec<Gemma4Bf16KvPage> pages, const &Gemma4Bf16Tensor newK, const &Gemma4Bf16Tensor newV, unsigned long kvWidth) {
    unsigned long newRowCount = newK.shape0;
    unsigned long srcRow = 0UL;

    while (srcRow < newRowCount) {
        unsigned long numPages = pages.length();
        struct Gemma4Bf16KvPage* lastPage;
        unsafe {
            if (numPages > 0UL) { lastPage = (struct Gemma4Bf16KvPage*)pages.at(numPages - 1UL); }
            else { lastPage = (struct Gemma4Bf16KvPage*)0; }
        }
        unsafe {
            if (lastPage == (struct Gemma4Bf16KvPage*)0 || lastPage->used >= GEMMA4_PAGE_SIZE) {
                struct Gemma4Bf16KvPage newPage;
                newPage.k = gemma4_bf16_new_2d(GEMMA4_PAGE_SIZE, kvWidth);
                newPage.v = gemma4_bf16_new_2d(GEMMA4_PAGE_SIZE, kvWidth);
                newPage.used = 0UL;
                pages.push(newPage);
                numPages = pages.length();
                lastPage = (struct Gemma4Bf16KvPage*)pages.at(numPages - 1UL);
            }
        }

        unsigned long roomInPage;
        unsafe { roomInPage = GEMMA4_PAGE_SIZE - lastPage->used; }
        unsigned long remaining = newRowCount - srcRow;
        unsigned long toCopy;
        if (roomInPage < remaining) { toCopy = roomInPage; } else { toCopy = remaining; }

        unsafe {
            unsigned long destRow = lastPage->used;
            unsigned long r = 0UL;
            while (r < toCopy) {
                unsigned long c = 0UL;
                while (c < kvWidth) {
                    lastPage->k.data[(destRow + r) * kvWidth + c] = newK.data[(srcRow + r) * kvWidth + c];
                    lastPage->v.data[(destRow + r) * kvWidth + c] = newV.data[(srcRow + r) * kvWidth + c];
                    c = c + 1UL;
                }
                r = r + 1UL;
            }
            lastPage->used = lastPage->used + toCopy;
        }

        srcRow = srcRow + toCopy;
    }
}

unsigned long gemma4_bf16_kv_pages_total_len(&Vec<Gemma4Bf16KvPage> pages) {
    unsigned long total = 0UL;
    unsigned long numPages = pages.length();
    unsigned long i = 0UL;
    unsafe {
        while (i < numPages) {
            struct Gemma4Bf16KvPage* p = (struct Gemma4Bf16KvPage*)pages.at(i);
            total = total + p->used;
            i = i + 1UL;
        }
    }
    return total;
}

void gemma4_bf16_kv_pages_free(&Vec<Gemma4Bf16KvPage> pages) {
    unsigned long numPages = pages.length();
    unsigned long i = 0UL;
    unsafe {
        while (i < numPages) {
            struct Gemma4Bf16KvPage* p = (struct Gemma4Bf16KvPage*)pages.at(i);
            gemma4_bf16_free(&p->k);
            gemma4_bf16_free(&p->v);
            i = i + 1UL;
        }
    }
    pages.free();
}

void gemma4_bf16_kv_pages_truncate(&Vec<Gemma4Bf16KvPage> pages, unsigned long newTotalLen) {
    unsigned long total = gemma4_bf16_kv_pages_total_len(pages);
    if (newTotalLen >= total) { return; }
    unsigned long toRemove = total - newTotalLen;

    while (toRemove > 0UL) {
        unsigned long numPages = pages.length();
        if (numPages == 0UL) { return; }
        unsafe {
            struct Gemma4Bf16KvPage* lastPage = (struct Gemma4Bf16KvPage*)pages.at(numPages - 1UL);
            if (lastPage->used <= toRemove) {
                toRemove = toRemove - lastPage->used;
                match (pages.pop()) {
                    case some(popped): {
                        gemma4_bf16_free(&popped.k);
                        gemma4_bf16_free(&popped.v);
                    }
                    case none: { }
                }
            } else {
                lastPage->used = lastPage->used - toRemove;
                toRemove = 0UL;
            }
        }
    }
}

// Same SIMD-friendly restructuring as __gemma4_bf16_attn_head above (bulk
// bf16->f32 conversion of contiguous row segments + a headDim-wide
// accumulator, bit-identical summation order to the previous version),
// applied to the paged-cache path -- 'scores'/'qF'/'kvF'/'acc' are
// caller-allocated scratch, never shared across threads (see
// gemma4_bf16_attn_raw_paged below and gemma4_bf16_model_forward_batch's
// worker-thread design).
static void __gemma4_bf16_attn_head_paged(const &Gemma4Bf16Tensor q, &Vec<Gemma4Bf16KvPage> pages,
                                            unsigned long hIdx, unsigned long kvHeadIdx, unsigned long headDim,
                                            unsigned long seqLen, unsigned long kvSeqLen, unsigned long cacheLen,
                                            int isSliding, unsigned long slidingWindow,
                                            &Gemma4Bf16Tensor outConcat,
                                            float* scores, float* qF, float* kvF, float* acc) {
    unsigned long qCols = q.shape1; unsigned long outCols = outConcat.shape1;
    unsigned long qColOff = hIdx * headDim;
    unsigned long kvColOff = kvHeadIdx * headDim;
    unsigned long outColOff = hIdx * headDim;

    unsigned long i = 0UL;
    while (i < seqLen) {
        unsigned long qPos = cacheLen + i;
        unsafe { bf16_to_f32_bulk(&q.data[i * qCols + qColOff], qF, headDim); }

        unsafe {
            unsigned long j = 0UL;
            while (j < kvSeqLen) {
                int allowed = (j <= qPos) ? 1 : 0;
                if (allowed && isSliding) {
                    allowed = ((qPos - j) < slidingWindow) ? 1 : 0;
                }
                if (allowed) {
                    struct Gemma4Bf16KvPage* kp = (struct Gemma4Bf16KvPage*)pages.at(j / GEMMA4_PAGE_SIZE);
                    unsigned long kRow = j % GEMMA4_PAGE_SIZE;
                    unsigned long kCols = kp->k.shape1;
                    bf16_to_f32_bulk(&kp->k.data[kRow * kCols + kvColOff], kvF, headDim);
                    float dot = (float)0.0;
                    unsigned long d = 0UL;
                    while (d < headDim) { dot = dot + qF[d] * kvF[d]; d = d + 1UL; }
                    scores[j] = dot; // scaling=1.0 -- no multiply (see header comment)
                } else {
                    scores[j] = (float)-1e30;
                }
                j = j + 1UL;
            }

            float maxV = scores[0];
            j = 1UL;
            while (j < kvSeqLen) { if (scores[j] > maxV) { maxV = scores[j]; } j = j + 1UL; }

            float sumExp = (float)0.0;
            j = 0UL;
            while (j < kvSeqLen) {
                scores[j] = exp_f(scores[j] - maxV);
                sumExp = sumExp + scores[j];
                j = j + 1UL;
            }
            j = 0UL;
            while (j < kvSeqLen) { scores[j] = scores[j] / sumExp; j = j + 1UL; }

            unsigned long d = 0UL;
            while (d < headDim) { acc[d] = (float)0.0; d = d + 1UL; }

            j = 0UL;
            while (j < kvSeqLen) {
                struct Gemma4Bf16KvPage* vp = (struct Gemma4Bf16KvPage*)pages.at(j / GEMMA4_PAGE_SIZE);
                unsigned long vRow = j % GEMMA4_PAGE_SIZE;
                unsigned long vCols = vp->v.shape1;
                bf16_to_f32_bulk(&vp->v.data[vRow * vCols + kvColOff], kvF, headDim);
                float sj = scores[j];
                d = 0UL;
                while (d < headDim) { acc[d] = acc[d] + sj * kvF[d]; d = d + 1UL; }
                j = j + 1UL;
            }

            d = 0UL;
            while (d < headDim) {
                outConcat.data[i * outCols + outColOff + d] = f32_to_bf16(acc[d]);
                d = d + 1UL;
            }
        }
        i = i + 1UL;
    }
}

// Just the per-head score/softmax/weighted-value loop, NO o_proj matmul --
// split out from gemma4_bf16_attn_forward_paged specifically so batched
// decode (gemma4_bf16_model_forward_batch) can call this per row (the
// part that must stay per-sequence, since each row's attention reads a
// different sequence's cache) while still batching o_proj into ONE
// gemma4_bf16_linear call across the whole batch afterward -- o_proj is
// exactly as expensive as any other projection matmul, so calling it
// once per batch row here (the way an earlier version of this function
// effectively did, before the split) would silently un-batch one of the
// four big per-layer matmuls and was the actual cause of batched
// decode's sub-linear throughput scaling (see DESIGN_NOTES.md).
struct Gemma4Bf16Tensor gemma4_bf16_attn_raw_paged(const &Gemma4Bf16Tensor q, &Vec<Gemma4Bf16KvPage> pages,
                                                      unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                                      unsigned long cacheLen, int isSliding, unsigned long slidingWindow) {
    unsigned long seqLen = q.shape0;
    unsigned long kvSeqLen = gemma4_bf16_kv_pages_total_len(pages);
    unsigned long groupSize = numHeads / numKvHeads;

    struct Gemma4Bf16Tensor concatOut = gemma4_bf16_new_2d(seqLen, numHeads * headDim);

    // Scratch allocated once per call (not once per head, not a shared
    // static) -- cheap (a handful of headDim/kvSeqLen-sized float
    // buffers) relative to the O(numHeads*kvSeqLen*headDim) work below,
    // and critically, call-local: gemma4_bf16_model_forward_batch invokes
    // this function concurrently from multiple worker threads (one per
    // batch-item slice), and each thread's call gets its own scratch,
    // never touched by another thread.
    float* scores; float* qF; float* kvF; float* acc;
    unsafe {
        scores = (float*)malloc(sizeof(float) * kvSeqLen);
        qF = (float*)malloc(sizeof(float) * headDim);
        kvF = (float*)malloc(sizeof(float) * headDim);
        acc = (float*)malloc(sizeof(float) * headDim);
    }

    unsigned long hIdx = 0UL;
    while (hIdx < numHeads) {
        unsigned long kvHeadIdx = hIdx / groupSize;
        __gemma4_bf16_attn_head_paged(q, pages, hIdx, kvHeadIdx, headDim, seqLen, kvSeqLen, cacheLen,
                                       isSliding, slidingWindow, &concatOut, scores, qF, kvF, acc);
        hIdx = hIdx + 1UL;
    }
    unsafe { free((void*)scores); free((void*)qF); free((void*)kvF); free((void*)acc); }

    return concatOut;
}

struct Gemma4Bf16Tensor gemma4_bf16_attn_forward_paged(const &Gemma4Bf16Tensor q, &Vec<Gemma4Bf16KvPage> pages, const &Gemma4Bf16Tensor oProjW,
                                                          unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                                          unsigned long cacheLen, int isSliding, unsigned long slidingWindow) {
    struct Gemma4Bf16Tensor concatOut = gemma4_bf16_attn_raw_paged(q, pages, headDim, numHeads, numKvHeads,
                                                                     cacheLen, isSliding, slidingWindow);
    struct Gemma4Bf16Tensor attnOut = gemma4_bf16_linear(&concatOut, oProjW);
    gemma4_bf16_free(&concatOut);
    return attnOut;
}

// ── Real GPU-parallel batched attention (Metal) ─────────────────────────
// Gathers every sequence's paged KV cache into one contiguous
// [batchSize, maxKvLen, numKvHeads*headDim] buffer (mps_attn_batch_bf16's
// kernel addresses K/V as one flat buffer, not scattered pages -- an
// array of page pointers indexable from inside a Metal kernel is real
// added complexity for a cheap, one-time-per-call gather copy that's
// nowhere near the O(batchSize*numHeads*kvLen*headDim) attention compute
// itself), dispatches the real batched-attention Metal kernel (see
// gpu_mps_kernels.metal's attn_batch_bf16_kernel -- one GPU thread per
// (batch, head) pair, genuinely parallel across BOTH dimensions at once,
// not a CPU loop visiting them one at a time), and writes the raw
// [batchSize, numHeads*headDim] result directly into 'outConcat'.
//
// 'pagesPtrArr[b]' is sequence b's own page list for this layer (already
// includes this step's just-appended new token -- the caller must append
// before calling this). Returns 1 on success (outConcat populated), 0 if
// the GPU dispatch failed for any reason (no Metal device, a headDim the
// kernel doesn't support, forced-CPU mode) -- the caller falls back to the per-sequence CPU path
// (gemma4_bf16_attn_raw_paged, looped) in that case, same total FLOPs
// either way, just visited one sequence at a time on CPU cores instead
// of all at once on the GPU.
// Shared by both gemma4_bf16_attn_batch_gpu and _chained below: gathers
// every sequence's paged KV into one flat [batchSize, maxKvLen,
// numKvHeads*headDim] pair of buffers, and the true per-sequence KV
// length (see the correctness-bug comment on the caller for why this is
// NOT simply the caller's 'cacheLens' array). Returns 0 (and leaves the
// out-params untouched) if every sequence's KV is empty, same
// refuse-and-fall-back-to-CPU contract as the rest of this path (the
// kernel itself has no KV-length limit).
static int __g4attn_gather_kv_batch(struct Vec<struct Gemma4Bf16KvPage>** pagesPtrArr, unsigned long batchSize,
                                      unsigned long kvWidth,
                                      unsigned short** outKAll, unsigned short** outVAll,
                                      unsigned int** outCacheLensU, unsigned long* outMaxKvLen) {
    unsigned long maxKvLen = 0UL;
    unsigned long b = 0UL;
    while (b < batchSize) {
        struct Vec<struct Gemma4Bf16KvPage>* pagesPtr;
        unsafe { pagesPtr = pagesPtrArr[b]; }
        struct Vec<struct Gemma4Bf16KvPage> pagesForLen;
        unsafe { pagesForLen = *pagesPtr; }
        unsigned long len = gemma4_bf16_kv_pages_total_len(&pagesForLen);
        if (len > maxKvLen) { maxKvLen = len; }
        b = b + 1UL;
    }
    if (maxKvLen == 0UL) { return 0; }

    unsigned short* kAll; unsigned short* vAll; unsigned int* cacheLensU;
    unsafe {
        kAll = (unsigned short*)malloc(sizeof(unsigned short) * batchSize * maxKvLen * kvWidth);
        vAll = (unsigned short*)malloc(sizeof(unsigned short) * batchSize * maxKvLen * kvWidth);
        cacheLensU = (unsigned int*)malloc(sizeof(unsigned int) * batchSize);
    }

    b = 0UL;
    while (b < batchSize) {
        struct Vec<struct Gemma4Bf16KvPage>* pagesPtr;
        unsafe { pagesPtr = pagesPtrArr[b]; }
        struct Vec<struct Gemma4Bf16KvPage> pages;
        unsafe { pages = *pagesPtr; }
        unsigned long kvLen = gemma4_bf16_kv_pages_total_len(&pages);
        unsafe { cacheLensU[b] = (unsigned int)kvLen; }

        // Copies a whole run of CONTIGUOUS rows (bounded by the current
        // page's own remaining rows, and by kvLen) in one memcpy each for
        // K and V, instead of one scalar bf16 assignment at a time -- a
        // page's own k.data/v.data are already row-major contiguous
        // (kvWidth-wide rows), and kAll/vAll's destination rows for a
        // fixed 'b' are contiguous too, so a multi-row run is one
        // contiguous memcpy on both ends. This gather step turned out to
        // be the actual bottleneck in an earlier version of this
        // function (a scalar per-element copy loop, no faster than the
        // CPU attention path it was meant to replace) -- see
        // DESIGN_NOTES.md.
        unsigned long j = 0UL;
        while (j < kvLen) {
            unsafe {
                unsigned long pageIdx = j / GEMMA4_PAGE_SIZE;
                unsigned long row = j % GEMMA4_PAGE_SIZE;
                struct Gemma4Bf16KvPage* kp = (struct Gemma4Bf16KvPage*)pages.at(pageIdx);
                unsigned long kCols = kp->k.shape1;
                unsigned long vCols = kp->v.shape1;

                unsigned long rowsLeftInPage = GEMMA4_PAGE_SIZE - row;
                unsigned long rowsLeftInKv = kvLen - j;
                unsigned long runLen = rowsLeftInPage;
                if (rowsLeftInKv < runLen) { runLen = rowsLeftInKv; }

                unsigned long destOff = (b * maxKvLen + j) * kvWidth;
                memcpy((void*)&kAll[destOff], (const void*)&kp->k.data[row * kCols],
                       sizeof(unsigned short) * runLen * kvWidth);
                memcpy((void*)&vAll[destOff], (const void*)&kp->v.data[row * vCols],
                       sizeof(unsigned short) * runLen * kvWidth);
                j = j + runLen;
            }
        }
        b = b + 1UL;
    }

    unsafe {
        *outKAll = kAll; *outVAll = vAll; *outCacheLensU = cacheLensU; *outMaxKvLen = maxKvLen;
    }
    return 1;
}

int gemma4_bf16_attn_batch_gpu(const &Gemma4Bf16Tensor q, struct Vec<struct Gemma4Bf16KvPage>** pagesPtrArr, const unsigned long* cacheLens,
                                 unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                 int isSliding, unsigned long slidingWindow,
                                 unsigned long batchSize, &Gemma4Bf16Tensor outConcat) {
    if (gemma4_bf16_get_force_cpu()) { return 0; }

    unsigned long kvWidth = numKvHeads * headDim;
    unsigned short* kAll; unsigned short* vAll; unsigned int* cacheLensU; unsigned long maxKvLen;
    if (!__g4attn_gather_kv_batch(pagesPtrArr, batchSize, kvWidth, &kAll, &vAll, &cacheLensU, &maxKvLen)) {
        return 0;
    }

    unsigned short* outBits;
    unsafe { outBits = (unsigned short*)malloc(sizeof(unsigned short) * batchSize * numHeads * headDim); }

    int rc;
    unsafe {
        rc = mps_attn_batch_bf16(q.data, kAll, vAll, cacheLensU, outBits,
                                   batchSize, numHeads, numKvHeads, headDim, maxKvLen,
                                   isSliding, slidingWindow);
    }

    if (rc) {
        unsigned long total = batchSize * numHeads * headDim;
        unsafe {
            unsigned long i = 0UL;
            while (i < total) { outConcat.data[i] = outBits[i]; i = i + 1UL; }
        }
    }

    unsafe {
        free((void*)kAll);
        free((void*)vAll);
        free((void*)cacheLensU);
        free((void*)outBits);
    }
    return rc;
}

// Chained variant: dispatches attention AND the o_proj matmul that
// follows it inside ONE open batch, so they share a single commit+wait
// instead of attention doing its own full command-buffer round trip and
// o_proj separately re-uploading attention's CPU-read-back output as a
// fresh 'x' -- see DESIGN_NOTES.md's diagnosis of why the non-chained
// GPU path's win over the CPU fallback was smaller than expected (every
// op, not just attention, was already paying its own full sync round
// trip; this removes exactly the one between attention and o_proj, the
// two ops immediately adjacent to each other in the batched decode
// step). Returns the FINAL (post-o_proj) attention output directly into
// 'attnOut' ([batchSize, hiddenSize], not the raw pre-o_proj concat) --
// callers must NOT apply o_proj again on success. Returns 0 (leaving
// 'attnOut' untouched) on any failure -- no Metal device, unsupported
// headDim, forced-CPU mode, or oProjW has no GPU buffer yet
// (shouldn't happen once gemma4_bf16_linear or this function has run at
// least once for that weight, but checked defensively) -- caller falls
// back to gemma4_bf16_attn_batch_gpu/the CPU path plus its OWN separate
// gemma4_bf16_linear(..., oProjW) call in that case.
int gemma4_bf16_attn_batch_gpu_chained(const &Gemma4Bf16Tensor q, struct Vec<struct Gemma4Bf16KvPage>** pagesPtrArr,
                                         const &Gemma4Bf16Tensor oProjW,
                                         unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                         int isSliding, unsigned long slidingWindow,
                                         unsigned long batchSize, unsigned long hiddenSize,
                                         &Gemma4Bf16Tensor attnOut) {
    if (gemma4_bf16_get_force_cpu()) { return 0; }

    unsigned long kvWidth = numKvHeads * headDim;
    unsigned short* kAll; unsigned short* vAll; unsigned int* cacheLensU; unsigned long maxKvLen;
    if (!__g4attn_gather_kv_batch(pagesPtrArr, batchSize, kvWidth, &kAll, &vAll, &cacheLensU, &maxKvLen)) {
        return 0;
    }

    struct MpsBuf wBuf;
    unsafe { wBuf = gemma4_bf16_get_weight_buf(oProjW); }
    if (!wBuf.valid()) {
        unsafe { free((void*)kAll); free((void*)vAll); free((void*)cacheLensU); }
        return 0;
    }

    unsigned long kvN = batchSize * maxKvLen * kvWidth;
    unsigned long attnScratchBytes = batchSize * numHeads * headDim * 2UL;

    unsigned short* attnScratch; // throwaway -- see mps_attn_batch_bf16_chained's header comment
    unsafe { attnScratch = (unsigned short*)malloc(attnScratchBytes); }

    struct MpsBuf bufQ; struct MpsBuf bufK; struct MpsBuf bufV; struct MpsBuf bufCacheLens;
    unsafe {
        bufQ = MpsBuf::upload_bits(q.data, q.size, MPS_BF16);
        bufK = MpsBuf::upload_bits(kAll, kvN, MPS_BF16);
        bufV = MpsBuf::upload_bits(vAll, kvN, MPS_BF16);
        bufCacheLens = MpsBuf::upload((const float*)cacheLensU, batchSize);   // uint32 lengths, 4 bytes each
    }

    int rc = 0;
    if (bufQ.valid() && bufK.valid() && bufV.valid() && bufCacheLens.valid()) {
        unsafe {
            mps_batch_begin();
            int attnRc = mps_attn_batch_bf16_chained(bufQ, bufK, bufV, bufCacheLens, attnScratch,
                                                       batchSize, numHeads, numKvHeads, headDim, maxKvLen,
                                                       isSliding, slidingWindow);
            if (attnRc) {
                struct MpsBuf attnOutBuf = MpsBuf::batch_last_output();
                rc = mps_matmul_abt_bf16_persistent(attnOutBuf, wBuf, attnOut.data,
                                                      batchSize, numHeads * headDim, hiddenSize);
            }
            mps_batch_end();
        }
    }

    bufQ.free(); bufK.free(); bufV.free(); bufCacheLens.free();   // no-ops on invalid buffers
    unsafe {
        free((void*)kAll);
        free((void*)vAll);
        free((void*)cacheLensU);
        free((void*)attnScratch);
    }
    return rc;
}

} // namespace std
