#pragma once
// SafeC Standard Library — Gemma 4 rotary position embeddings (RoPE).
//
// Two entirely different configs depending on layer type (see
// DESIGN_NOTES.md's "RoPE — two completely different configs per layer
// type" section for the derivation):
//   sliding_attention: standard/default RoPE, theta=10000, head_dim=256,
//                       all 128 frequency pairs rotate.
//   full_attention:    "proportional"/p-RoPE, theta=1000000, head_dim=512
//                       (this model's global_head_dim), but
//                       partial_rotary_factor=0.25 means only the first 64
//                       of 256 frequency pairs actually rotate -- the
//                       remaining 192 are zero-padded (cos=1, sin=0,
//                       "NoPE": those dimensions pass through unrotated).
//
// This module only builds cos/sin tables and applies rotate_half to a
// SINGLE head's [seqLen, headDim] slice at a time -- attention.sc (once
// written) is responsible for slicing Q/K per head and calling
// gemma4_rope_apply once per head, reusing the same table across every
// head of a given layer (cos/sin only depend on position and layer type,
// not on which head).
#include <std/ml/tensor.h>
#include "gemma4_bf16.h"

namespace std {

struct Gemma4RopeTable {
    &heap float cosTable;  // [count, headDim], row-major
    &heap float sinTable;  // [count, headDim], row-major
    unsigned long count;
    unsigned long headDim;
};

// Builds cos/sin tables for 'count' absolute token positions
// (positions[i] is the position for row i -- not necessarily 0..count-1;
// during single-step decode this is a single absolute position, not 0).
// 'headDim' is a caller-supplied parameter (not hardcoded here) so this
// module stays agnostic to which checkpoint it's serving -- callers
// derive it from the actual loaded q_proj weight's shape (see
// gemma4_model.sc) rather than assuming the real E2B's 256/512 always
// applies. Gemma4 E2B's real values are still isFullAttention=0 ->
// headDim=256, isFullAttention=1 -> headDim=512 -- pass those explicitly
// for that checkpoint.
struct Gemma4RopeTable gemma4_rope_build(int isFullAttention, unsigned long headDim, const unsigned long* positions, unsigned long count);

void gemma4_rope_table_free(&Gemma4RopeTable t);

// x is [seqLen, headDim] (one attention head's Q or K slice), bf16 in and
// out (see gemma4_bf16.h's header comment on the overall design).
// table.count must equal x's row count, table.headDim must equal x's
// column count. Returns a fresh Gemma4Bf16Tensor; does not modify x.
// cos/sin tables themselves stay F32 -- small precomputed trig lookups,
// not activations, no reason to shrink them.
struct Gemma4Bf16Tensor gemma4_bf16_rope_apply(const &Gemma4Bf16Tensor x, &Gemma4RopeTable table);

} // namespace std
