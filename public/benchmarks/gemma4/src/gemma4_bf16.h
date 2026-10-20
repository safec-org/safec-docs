#pragma once
// SafeC Standard Library — Gemma 4 bfloat16 tensor type and core ops.
//
// Every weight in the real google/gemma-4-E2B checkpoint is stored as
// BF16 on disk (see safetensors.h). The rest of this project historically
// widened every weight to F32 on load and ran the whole pipeline in
// float32 (SafeC's Tensor, std/ml/tensor.h, is F32-only by design -- see
// float16.h's own header comment on why: no compiler-native 16-bit float
// scalar type). This module is the "actually process in bf16" path: a
// standalone bf16-backed tensor (raw bfloat16 bits in 'unsigned short',
// the same storage convention float16.h already established for GPU
// upload) used for BOTH weight storage (loaded directly from the
// checkpoint's native bf16 bytes, no f32 upcast at all -- see
// safetensors.h's load_bf16) and activations flowing through the 35-layer
// forward pass, instead of F32 Tensor.
//
// Design: bf16 -> f32 widening is exact and lossless (bf16 shares
// float32's exponent width exactly -- see float16.h); f32 -> bf16
// narrowing is a real, intentional precision-reducing round (round-to-
// nearest-even). Every op here either:
//   (a) does real bf16 GPU compute (gemma4_bf16_linear, the dominant FLOP
//       cost in a transformer forward pass -- every q/k/v/o/gate/up/down/
//       per_layer_*/lm_head projection): both operands and the output
//       stay bf16 the entire time, GPU-side multiply-accumulate happens
//       in bfloat with float32 accumulation (mps_matmul_abt_bf16_
//       persistent -- standard mixed-precision-GEMM practice, not a
//       deviation from "bf16 processing"; accumulating in bf16 itself
//       would be numerically unusable), or
//   (b) widens its bf16 input(s) to a temporary F32 Tensor, reuses the
//       EXISTING, already extensively real-checkpoint-verified F32 CPU
//       math (RMSNorm, RoPE, attention score/softmax, GELU, softcap --
//       none of which are the dominant FLOP cost, and all of which are
//       precision-sensitive enough that real bf16-inference frameworks
//       typically keep them in float32 internally regardless of the
//       model's overall dtype too), then narrows the result back to bf16
//       before returning.
// Either way, every value that's actually STORED between ops -- every
// weight, every activation crossing a function boundary -- is bf16, not
// float32: this is what "processing in bf16" means for a model whose
// language has no native half-precision arithmetic type.
#include <std/ml/tensor.h>
#include <std/ml/float16.h>

namespace std {

// 'shape1' is unused (left 0) for 1D tensors (norm weights, RoPE-headDim
// vectors after per-head slicing, etc.) -- 'ndim' says which shape this
// is, same convention std::Tensor itself uses.
struct Gemma4Bf16Tensor {
    unsigned short* data;   // raw bf16 bits, row-major [shape0, shape1] (2D) or [shape0] (1D)
    unsigned long shape0;
    unsigned long shape1;
    unsigned long ndim;     // 1 or 2
    unsigned long size;     // shape0 (ndim==1) or shape0*shape1 (ndim==2)
};

struct Gemma4Bf16Tensor gemma4_bf16_new_1d(unsigned long len);
struct Gemma4Bf16Tensor gemma4_bf16_new_2d(unsigned long rows, unsigned long cols);
void gemma4_bf16_free(&Gemma4Bf16Tensor t);

// Widens/narrows between bf16 and F32 Tensor -- the only place a full
// float32 Tensor of activation size gets materialized on the CPU side
// (transiently, for ops that reuse existing F32 math -- see header
// comment). bf16->f32 is exact; f32->bf16 is a real rounding step.
struct Gemma4Bf16Tensor gemma4_bf16_from_f32(const &Tensor t);
struct Tensor* gemma4_bf16_to_f32(const &Gemma4Bf16Tensor t);

// y = x @ w^T, real bf16 GPU compute (mps_matmul_abt_bf16_persistent --
// bfloat multiply, float32 accumulate, bfloat output, weight uploaded
// once as raw bf16 bits and cached by pointer identity) when a Metal
// device is available and gemma4_bf16_set_force_cpu(1) hasn't been
// called; otherwise falls back to a real Accelerate-BLAS CPU path
// (cblas_sgemm, widen bf16->f32, narrow back -- not a naive triple
// loop). This is the dominant FLOP cost in the model -- every other op
// below is comparatively cheap.
struct Gemma4Bf16Tensor gemma4_bf16_linear(const &Gemma4Bf16Tensor x, const &Gemma4Bf16Tensor w);

// Forces gemma4_bf16_linear's CPU-BLAS path even when a Metal device is
// available -- for CPU-vs-GPU comparison and explicit CPU-only serving.
// Off (0, i.e. prefer GPU when present) by default.
void gemma4_bf16_set_force_cpu(int forceCpu);
// Current value set by gemma4_bf16_set_force_cpu -- gemma4_bf16_model_
// forward_batch's own GPU-vs-CPU attention-kernel choice respects the
// same flag (see gemma4_attention.h's gemma4_bf16_attn_batch_gpu), so a
// benchmark forcing CPU-only serving/comparison via the setter above
// gets a genuinely CPU-only run end to end, not just for the matmuls.
int gemma4_bf16_get_force_cpu();

// Returns 'w's persistent GPU buffer handle (uploading it once and
// caching by pointer identity on first call, same as gemma4_bf16_linear's
// own internal weight-buffer cache -- this exposes that same cache, not
// a separate one, so a weight already used through gemma4_bf16_linear is
// already resident and this just returns the existing handle). NULL if
// no Metal device is available. For callers that need a weight's GPU
// buffer directly to chain a matmul against a still-pending GPU input
// (see gemma4_attention.h's gemma4_bf16_attn_batch_gpu_chained) instead
// of going through gemma4_bf16_linear's own fresh-upload-of-x path.
void* gemma4_bf16_get_weight_buf(const &Gemma4Bf16Tensor w);

// Releases 'w's cached persistent GPU buffer -- MUST be called (via
// gemma4_bf16_model_free) before a weight's own CPU memory is freed, an
// ABA-hazard precaution: a later gemma4_bf16_model_load reusing the same
// freed address must never hand back a stale GPU buffer for it.
void gemma4_bf16_release_weight_buf(const &Gemma4Bf16Tensor w);

// RMSNorm (Gemma4RMSNorm == Gemma3nRMSNorm's formula: normed =
// x*rsqrt(mean(x^2)+eps), scaled by 'weight'), bf16-native throughout
// (see gemma4_bf16.sc).
struct Gemma4Bf16Tensor gemma4_bf16_rmsnorm_rows(const &Gemma4Bf16Tensor x, const &Gemma4Bf16Tensor weight, float eps);
// Same formula, no weight multiply (attention's v_norm -- with_scale=False).
struct Gemma4Bf16Tensor gemma4_bf16_rmsnorm_rows_noscale(const &Gemma4Bf16Tensor x, float eps);
struct Gemma4Bf16Tensor gemma4_bf16_add(const &Gemma4Bf16Tensor a, const &Gemma4Bf16Tensor b);
struct Gemma4Bf16Tensor gemma4_bf16_mul(const &Gemma4Bf16Tensor a, const &Gemma4Bf16Tensor b);
struct Gemma4Bf16Tensor gemma4_bf16_scale(const &Gemma4Bf16Tensor x, float k);
struct Gemma4Bf16Tensor gemma4_bf16_softcap(const &Gemma4Bf16Tensor x, float cap);
struct Gemma4Bf16Tensor gemma4_bf16_slice_cols(const &Gemma4Bf16Tensor x, unsigned long colStart, unsigned long width);
// Writes 'src' ([rows, width]) into dst's columns [colStart, colStart+width).
// dst must already have at least colStart+width columns and the same row count as src.
void gemma4_bf16_write_cols(&Gemma4Bf16Tensor dst, const &Gemma4Bf16Tensor src, unsigned long colStart, unsigned long width);
struct Gemma4Bf16Tensor gemma4_bf16_concat_rows(const &Gemma4Bf16Tensor a, const &Gemma4Bf16Tensor b);

// Row-wise embedding gather + scale, straight on bf16 bits (widen just
// the 'scale' multiply per element to float, narrow immediately back --
// gathering doesn't touch the whole table, so there's no wasted whole-
// tensor widen here the way a naive widen-whole-table-then-narrow
// approach would cost, which matters since embed_tokens/embed_tokens_
// per_layer are this model's two largest tensors).
struct Gemma4Bf16Tensor gemma4_bf16_embed_lookup_row(const &Gemma4Bf16Tensor embedTable, unsigned long tokenId, float scale);
struct Gemma4Bf16Tensor gemma4_bf16_embed_lookup_rows(const &Gemma4Bf16Tensor embedTable, const int* tokenIds, unsigned long count, float scale);

} // namespace std
