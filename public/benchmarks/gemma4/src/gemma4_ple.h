#pragma once
// SafeC Standard Library — Gemma 4 Per-Layer Embeddings (PLE), bf16
// throughout (see gemma4_bf16.h's header comment for the overall design).
//
// Computed ONCE per forward pass (not per decoder layer) — see
// DESIGN_NOTES.md's "Per-Layer Embeddings (PLE)" section. Two components
// combined:
//   1. token-identity: embed_tokens_per_layer(tokenIds), scaled by
//      sqrt(hidden_size_per_layer_input=256) -- this is EXACTLY
//      gemma4_bf16_embed_lookup_rows from gemma4_bf16.h (same op, same
//      table shape [vocabSize, numLayers*pleDim]), no separate function
//      needed for this half.
//   2. context-aware: per_layer_model_projection(inputs_embeds) (the
//      MAIN scaled embedding, not the per-layer table), scaled by
//      1/sqrt(hidden_size=1536), reshaped per-layer, RMSNorm'd
//      (per_layer_projection_norm, WITH scale).
// Combined as (context + token_identity) * (1/sqrt(2)), still packed as
// one [seqLen, numLayers*pleDim] tensor (columns [i*pleDim,(i+1)*pleDim)
// belong to decoder layer i) -- slice per layer with
// gemma4_bf16_ple_slice_layer before passing into that layer's forward.
#include "gemma4_bf16.h"

namespace std {

// inputsEmbeds: [seqLen, hiddenSize] (the MAIN embed_tokens output,
// already scaled by sqrt(hiddenSize) -- see gemma4_bf16_embed_lookup_
// rows). perLayerModelProjectionW: [numLayers*pleDim, hiddenSize]
// (PyTorch layout). perLayerProjectionNormW: [pleDim]. tokenIdentity:
// [seqLen, numLayers*pleDim] (from gemma4_bf16_embed_lookup_rows against
// embed_tokens_per_layer, scale=sqrt(pleDim)). Returns [seqLen,
// numLayers*pleDim].
struct Gemma4Bf16Tensor gemma4_bf16_ple_combine(const &Gemma4Bf16Tensor inputsEmbeds, const &Gemma4Bf16Tensor perLayerModelProjectionW,
                            const &Gemma4Bf16Tensor perLayerProjectionNormW, const &Gemma4Bf16Tensor tokenIdentity,
                            unsigned long numLayers, unsigned long pleDim, float eps);

// Slices decoder layer 'layerIdx's [seqLen, pleDim] input out of the
// combined [seqLen, numLayers*pleDim] tensor gemma4_bf16_ple_combine
// returned.
struct Gemma4Bf16Tensor gemma4_bf16_ple_slice_layer(const &Gemma4Bf16Tensor pleAll, unsigned long layerIdx, unsigned long pleDim);

} // namespace std
