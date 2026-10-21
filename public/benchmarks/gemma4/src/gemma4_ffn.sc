// SafeC Standard Library — Gemma 4 gated FFN implementation, bf16
// throughout (see gemma4_ffn.h).
#pragma once
#include "gemma4_ffn.h"
#include <std/ml/activations.h>
#include <std/ml/gpu_mps.h>

namespace std {

extern void* malloc(unsigned long size);
extern void  free(void* ptr);

// CPU-GELU fallback path: both matmuls (gate/up and down -- the dominant
// FLOP cost here) run as real bf16 GPU/BLAS compute via gemma4_bf16_linear,
// but each is its OWN separate GPU round trip (or CPU+BLAS call), with
// GELU done on the CPU (std::ml's FloatTensor::gelu_fwd/tensor_mul --
// widen the gate/up split, compute, narrow back) in between. Used when the
// fully-chained GPU path below isn't available (no Metal device, forced
// CPU mode, or a weight buffer upload failed) -- see
// gemma4_bf16_ffn_forward_fused below for the normal path.
static struct Gemma4Bf16Tensor __g4ffn_forward_cpu_gelu(const &Gemma4Bf16Tensor x, const &Gemma4Bf16Tensor gateUpProjW, const &Gemma4Bf16Tensor downProjW,
                                                           unsigned long intermediateSize) {
    struct Gemma4Bf16Tensor gateUp = gemma4_bf16_linear(x, gateUpProjW); // [rows, 2*intermediateSize], real bf16 matmul
    struct Gemma4Bf16Tensor gate = gemma4_bf16_slice_cols(&gateUp, 0UL, intermediateSize);
    struct Gemma4Bf16Tensor up = gemma4_bf16_slice_cols(&gateUp, intermediateSize, intermediateSize);
    gemma4_bf16_free(&gateUp);

    FloatTensor* gateF = gemma4_bf16_to_f32(&gate);
    FloatTensor* upF = gemma4_bf16_to_f32(&up);
    gemma4_bf16_free(&gate); gemma4_bf16_free(&up);
    struct Gemma4Bf16Tensor gated;
    unsafe {
        &FloatTensor gateActF = gateF->gelu_fwd();
        gateF->free();
        &FloatTensor gatedF = tensor_mul(gateActF, upF);
        gateActF->free(); upF->free();
        gated = gemma4_bf16_from_f32(gatedF);
        gatedF.free();
    }

    struct Gemma4Bf16Tensor out = gemma4_bf16_linear(&gated, downProjW); // real bf16 matmul
    gemma4_bf16_free(&gated);
    return out;
}

// Fully GPU-chained FFN body: gate/up matmul -> gated_gelu_kernel_bf16
// (fused GELU+multiply, see gpu_mps_kernels.metal) -> down matmul, ALL on
// the SAME shared command buffer via mps_batch_last_output_buffer()
// feeding each step's still-pending GPU output straight into the next
// step's input. MUST be called inside an already-open batch
// (mps_batch_begin() already called by the caller) -- does NOT call
// mps_batch_begin()/mps_batch_end() itself, so a caller chaining a bigger
// sequence around the FFN (e.g. gemma4_model_bf16.sc's per-layer chained
// tail) can embed this as one link in a longer chain instead of paying
// its own separate round trip. 'bufX' is 'x' already uploaded/pending as
// a GPU buffer -- NOT re-uploaded here, so a caller with 'x' already
// GPU-resident (another chained op's output) pays no extra CPU<->GPU
// round trip for it either. On success, the down-proj's pending output is
// available via mps_batch_last_output_buffer() (also written to
// 'outScratch', throwaway until mps_batch_end() per the usual *_chained
// contract -- 'outScratch' must be sized for the FINAL [rows, inF] output,
// NOT the [rows, intermediateSize] intermediate; the intermediate gated-
// GELU step needs its OWN correctly-sized scratch, 'gateUpScratch'
// ([rows, 2*intermediateSize]) and 'gatedScratch' ([rows,
// intermediateSize]) -- both CALLER-allocated and passed in, NOT
// allocated/freed here: every *_chained op's 'out'/scratch pointer is
// only actually written by mps_batch_end()'s deferred copy, which the
// CALLER invokes after this function returns, so freeing scratch inside
// this function (before that copy has happened) would be a use-after-free
// -- the caller must free gateUpScratch/gatedScratch/outScratch only
// AFTER its own mps_batch_end() call. Returns 0 on any dispatch failure,
// leaving the batch's pending-op list as far as it got (caller decides
// whether to still call mps_batch_end() and fall back, same discipline as
// every other *_chained caller in this codebase).
int gemma4_bf16_ffn_forward_chained_body(void* bufX, void* gateUpWBuf, void* downWBuf,
                                           unsigned long rows, unsigned long inF, unsigned long intermediateSize,
                                           unsigned short* gateUpScratch, unsigned short* gatedScratch,
                                           unsigned short* outScratch) {
    int rc = 0;
    unsafe {
        int rc1 = mps_matmul_abt_bf16_persistent(bufX, gateUpWBuf, gateUpScratch, rows, inF, 2UL * intermediateSize);
        if (rc1) {
            void* gateUpBuf = mps_batch_last_output_buffer();
            rc = mps_gated_gelu_bf16_chained(gateUpBuf, gatedScratch, rows, intermediateSize);
            if (rc) {
                void* gatedBuf = mps_batch_last_output_buffer();
                rc = mps_matmul_abt_bf16_persistent(gatedBuf, downWBuf, outScratch, rows, intermediateSize, inF);
            }
        }
    }
    return rc;
}

// Fully GPU-chained FFN, standalone: gemma4_bf16_ffn_forward_chained_body
// above wrapped in its own mps_batch_begin()/mps_batch_end() round trip
// (ONE round trip for the whole FFN instead of two separate matmul round
// trips with a CPU widen/GELU/multiply/narrow pass in between -- real
// measured overhead: a single mps_batch_begin/end round trip costs ~0.24ms
// of fixed dispatch/wait time regardless of the op's own compute, see
// DESIGN_NOTES.md's "Chaining the FFN" section). Falls back to
// __g4ffn_forward_cpu_gelu on ANY failure (no Metal device, forced CPU
// mode, a weight buffer upload failing) -- same fallback discipline as
// gemma4_bf16_linear itself. Used when the FFN is called on its own (not
// as part of a bigger chained layer tail -- see gemma4_model_bf16.sc for
// that case, which calls gemma4_bf16_ffn_forward_chained_body directly).
struct Gemma4Bf16Tensor gemma4_bf16_ffn_forward_fused(const &Gemma4Bf16Tensor x, const &Gemma4Bf16Tensor gateUpProjW, const &Gemma4Bf16Tensor downProjW,
                                                         unsigned long intermediateSize) {
    if (gemma4_bf16_get_force_cpu()) {
        return __g4ffn_forward_cpu_gelu(x, gateUpProjW, downProjW, intermediateSize);
    }

    void* gateUpWBuf;
    void* downWBuf;
    unsafe {
        gateUpWBuf = gemma4_bf16_get_weight_buf(gateUpProjW);
        downWBuf = gemma4_bf16_get_weight_buf(downProjW);
    }
    if (gateUpWBuf == (void*)0 || downWBuf == (void*)0) {
        return __g4ffn_forward_cpu_gelu(x, gateUpProjW, downProjW, intermediateSize);
    }

    unsigned long rows = x.shape0;
    unsigned long inF = x.shape1;
    unsigned long xBytes = x.size * 2UL;
    void* xBuf;
    unsafe { xBuf = mps_upload_persistent((const float*)x.data, xBytes); }
    if (xBuf == (void*)0) {
        return __g4ffn_forward_cpu_gelu(x, gateUpProjW, downProjW, intermediateSize);
    }

    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(rows, downProjW.shape0);
    unsigned short* gateUpScratch; unsigned short* gatedScratch;
    unsafe {
        gateUpScratch = (unsigned short*)malloc(2UL * rows * 2UL * intermediateSize);
        gatedScratch = (unsigned short*)malloc(2UL * rows * intermediateSize);
    }
    int rc;
    unsafe {
        mps_batch_begin();
        rc = gemma4_bf16_ffn_forward_chained_body(xBuf, gateUpWBuf, downWBuf, rows, inF, intermediateSize,
                                                     gateUpScratch, gatedScratch, out.data);
        mps_batch_end();
        mps_release_persistent(xBuf, xBytes);
        free((void*)gateUpScratch); free((void*)gatedScratch);
    }

    if (rc) { return out; }
    gemma4_bf16_free(&out);
    return __g4ffn_forward_cpu_gelu(x, gateUpProjW, downProjW, intermediateSize);
}

} // namespace std
