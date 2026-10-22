#pragma once
// SafeC Standard Library — Gemma 4 gated feed-forward block, bf16
// throughout (reused from Gemma3MLP as Gemma4TextMLP, no architectural
// override beyond the weight shapes themselves -- see DESIGN_NOTES.md's
// "Gated FFN" section):
//
//   down_proj(gelu_tanh(gate_proj(x)) * up_proj(x))
//
// intermediate_size doubles (6144 -> 12288) for cross-layer-KV-shared
// layers ("double-wide MLP") -- this is purely a weight-shape difference
// baked into whatever gateUpProjW/downProjW the caller loads for a given
// layer, NOT something this function needs to special-case: it just
// reads intermediate_size off the caller-supplied parameter, same as
// gemma4_bf16_linear does for any other projection.
#include "gemma4_bf16.h"

namespace std {

// x: [seqLen, hiddenSize]. 'gateUpProjW' is gateProjW and upProjW
// concatenated row-wise ([2*intermediateSize, hiddenSize], gate's rows
// first) into ONE weight matrix, built once at model-load time -- one
// matmul instead of two, halving dispatch count for the single most
// frequently hit projection pair in the whole model (called once per
// layer, every layer, every forward pass). downProjW: [hiddenSize,
// intermediateSize]. All bias-free (confirmed no bias tensors in the
// real checkpoint). Returns [seqLen, hiddenSize]. Both matmuls run as
// real bf16 GPU/BLAS compute via gemma4_bf16_linear -- the dominant FLOP
// cost here; GELU stays on std::ml's existing, generic F32 tensor_gelu_
// fwd/tensor_mul (widen the gate/up split, compute, narrow back) rather
// than a bf16-native reimplementation -- GELU's tanh is precision-
// sensitive enough that even the F32 GPU kernel needed a real overflow-
// clamp fix this project found against the real checkpoint (see
// DESIGN_NOTES.md); bf16's coarser mantissa would only make that risk
// worse, for an elementwise op that isn't the dominant cost anyway.
struct Gemma4Bf16Tensor gemma4_bf16_ffn_forward_fused(const &Gemma4Bf16Tensor x, const &Gemma4Bf16Tensor gateUpProjW, const &Gemma4Bf16Tensor downProjW,
                                                         unsigned long intermediateSize);

// The FFN's chained GPU body, exposed for callers building a BIGGER chain
// around it (e.g. gemma4_model_bf16.sc's per-layer chained tail) -- MUST
// be called inside an already-open batch (mps_batch_begin() already
// called by the caller), does not call mps_batch_begin()/mps_batch_end()
// itself. 'bufX' is 'x' already uploaded/pending as a GPU buffer.
// 'gateUpScratch' ([rows, 2*intermediateSize]), 'gatedScratch' ([rows,
// intermediateSize]), and 'outScratch' ([rows, inF]) are CALLER-allocated
// and must stay alive until AFTER the caller's own mps_batch_end() call
// (freeing them earlier is a use-after-free -- mps_batch_end() is what
// actually writes through these pointers, not this function). On success,
// the result is also available via MpsBuf::batch_last_output(). See
// gemma4_ffn.sc for the full contract.
int gemma4_bf16_ffn_forward_chained_body(struct MpsBuf bufX, struct MpsBuf gateUpWBuf, struct MpsBuf downWBuf,
                                           unsigned long rows, unsigned long inF, unsigned long intermediateSize,
                                           unsigned short* gateUpScratch, unsigned short* gatedScratch,
                                           unsigned short* outScratch);

} // namespace std
