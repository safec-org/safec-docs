#pragma once
// SafeC Standard Library — Gemma 4 attention (Gemma4TextAttention forward),
// bf16 throughout (see gemma4_bf16.h's header comment for the overall
// design: real bf16 GPU/BLAS compute for the projections and o_proj
// matmuls, bf16-native math for the score/softmax loop and RMSNorm/RoPE
// -- everything here, weights and activations alike, is bf16, never F32).
//
// No batch dimension anywhere in this module -- this benchmark serves one
// request at a time, so every tensor here is [seqLen, features] (2D),
// never [batch, seqLen, features]. See DESIGN_NOTES.md's "Attention"
// section for the full derivation this implements: MQA (num_kv_heads=1,
// num_kv_groups=8), scaling=1.0 (NOT head_dim**-0.5 -- a deliberate
// Gemma4 override, load-bearing), per-head q_norm/k_norm (RMSNorm WITH
// scale) and v_norm (RMSNorm WITHOUT scale, with_scale=False), RoPE
// applied to Q and K (not V) before the score computation, and a causal
// mask optionally AND-ed with a sliding-window mask depending on layer
// type. Cross-layer KV sharing (layers 15..34 reusing layers 13/14's K/V)
// is the CALLER's responsibility, not this module's -- gemma4_bf16_attn_
// forward/_paged just take whatever K/V they're given, freshly computed
// or reused.
//
// Query/key absolute positions are assumed contiguous from 0: keys span
// [0, kvSeqLen), queries are the LAST 'seqLen' of those (i.e. query row i
// is at absolute position 'cacheLen + i', and kvSeqLen == cacheLen +
// seqLen always) -- true for both prefill (cacheLen=0) and single-step
// decode (cacheLen=however many tokens are already cached) under a plain
// growing KV cache.
#include <std/collections/vec.h>
#include "gemma4_rope.h"
#include "gemma4_bf16.h"

namespace std {

// ── Paged KV cache storage ────────────────────────────────────────────────
// A fixed-size block of GEMMA4_PAGE_SIZE K/V rows (vLLM-style paged
// attention, scoped down to this benchmark's single-request case): a
// cached layer's K/V lives as a Vec<Gemma4Bf16KvPage> instead of one
// ever-growing contiguous tensor. Appending new tokens either writes into
// the current page's unused tail rows (no allocation, no copy of
// EXISTING data at all) or allocates one new GEMMA4_PAGE_SIZE-row page
// once the current one fills up -- never reallocates/copies a page that
// already has data in it. gemma4_bf16_attn_forward_paged reads K/V
// directly from this page list during the score/softmax/weighted-sum
// computation -- it never materializes a flat contiguous copy either,
// which is the actual point: a copy-to-flatten step at attention time
// would just move the O(n) cost from "append" to "read," not remove it.
#define GEMMA4_PAGE_SIZE 128UL

struct Gemma4Bf16KvPage {
    struct Gemma4Bf16Tensor k; // [GEMMA4_PAGE_SIZE, kvWidth] bf16 -- rows [0,used) valid
    struct Gemma4Bf16Tensor v;
    unsigned long used;
};

// Appends 'newK'/'newV' ([newRowCount, kvWidth] each) onto the page list
// 'pages' (a Vec<Gemma4Bf16KvPage>, pass the SAME Vec across every call
// for one cache slot -- grows it in place), splitting across as many new
// pages as needed if newRowCount doesn't fit in the current last page's
// remaining room. 'pages' may be empty (first call for this slot).
void gemma4_bf16_kv_pages_append(&Vec<Gemma4Bf16KvPage> pages, const &Gemma4Bf16Tensor newK, const &Gemma4Bf16Tensor newV, unsigned long kvWidth);

// Total valid rows across every page in 'pages' (sum of each page's
// 'used', not pages.length()*GEMMA4_PAGE_SIZE -- the last page is
// usually partially filled).
unsigned long gemma4_bf16_kv_pages_total_len(&Vec<Gemma4Bf16KvPage> pages);

// Frees every page's K/V tensors and the Vec itself. Does NOT free
// 'pages' the pointer (caller owns that, same convention as
// gemma4_bf16_free).
void gemma4_bf16_kv_pages_free(&Vec<Gemma4Bf16KvPage> pages);

// Rolls 'pages' back to exactly 'newTotalLen' valid rows (< current
// gemma4_bf16_kv_pages_total_len(pages)) -- for speculative decoding's
// reject path (gemma4_generate.h's gemma4_bf16_generate_speculative):
// verifying K drafted tokens in one forward call appends all K to every
// layer's cache unconditionally (gemma4_bf16_model_forward has no way to
// know in advance how many will be accepted), so a rejected suffix has
// to be removed after the fact rather than never appended. Shrinks the
// last page's 'used' count, popping and freeing whole pages if the
// rollback crosses a page boundary. No-op if newTotalLen is already the
// current length; caller must ensure newTotalLen does not exceed it.
void gemma4_bf16_kv_pages_truncate(&Vec<Gemma4Bf16KvPage> pages, unsigned long newTotalLen);

// q_proj(h) -> per-head split -> q_norm (RMSNorm, WITH scale) -> RoPE.
// h is [seqLen, hiddenSize]; qProjW is [numHeads*headDim, hiddenSize]
// (PyTorch layout); qNormW is [headDim]. Returns [seqLen,
// numHeads*headDim], columns laid out head-major (column h*headDim+d is
// head h's dimension d) -- matches q_proj's own output column order
// under a (numHeads, headDim) reshape, so no separate transpose step is
// needed anywhere in this module.
struct Gemma4Bf16Tensor gemma4_bf16_attn_project_q(const &Gemma4Bf16Tensor h, const &Gemma4Bf16Tensor qProjW, const &Gemma4Bf16Tensor qNormW,
                                                     float eps, &Gemma4RopeTable ropeTable,
                                                     unsigned long headDim, unsigned long numHeads);

// k_proj(h) -> per-head split -> k_norm (RMSNorm, WITH scale) -> RoPE.
// kProjW is [numKvHeads*headDim, hiddenSize]; kNormW is [headDim].
// Returns [seqLen, numKvHeads*headDim].
struct Gemma4Bf16Tensor gemma4_bf16_attn_project_k(const &Gemma4Bf16Tensor h, const &Gemma4Bf16Tensor kProjW, const &Gemma4Bf16Tensor kNormW,
                                                     float eps, &Gemma4RopeTable ropeTable,
                                                     unsigned long headDim, unsigned long numKvHeads);

// v_proj(h) -> per-head split -> v_norm (RMSNorm, NO scale -- with_scale=
// False, no weight tensor exists for this at all). No RoPE (V is never
// rotated). vProjW is [numKvHeads*headDim, hiddenSize]. Returns [seqLen,
// numKvHeads*headDim].
struct Gemma4Bf16Tensor gemma4_bf16_attn_project_v(const &Gemma4Bf16Tensor h, const &Gemma4Bf16Tensor vProjW, float eps,
                                                     unsigned long headDim, unsigned long numKvHeads);

// Operator-fused variant of project_q + project_k + project_v together:
// 'qkvProjW' is qProjW/kProjW/vProjW concatenated row-wise (Q's rows
// first, then K, then V) into ONE [(numHeads+2*numKvHeads)*headDim,
// hiddenSize] weight matrix, built once at model-load time. Does ONE
// gemma4_bf16_linear call instead of three, then slices the result apart
// before running the EXACT same per-head norm/RoPE post-processing
// gemma4_bf16_attn_project_q/k/v each already do -- mathematically
// identical to calling all three separately. Only valid for non-shared
// layers (isKvShared layers have no kProjW/vProjW to fuse at all). Writes
// results through the three out-parameters rather than returning a
// struct (SafeC has no multi-return).
void gemma4_bf16_attn_project_qkv_fused(const &Gemma4Bf16Tensor h, const &Gemma4Bf16Tensor qkvProjW,
                                          const &Gemma4Bf16Tensor qNormW, const &Gemma4Bf16Tensor kNormW, float eps,
                                          &Gemma4RopeTable ropeTable,
                                          unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                          struct Gemma4Bf16Tensor* qOut, struct Gemma4Bf16Tensor* kOut, struct Gemma4Bf16Tensor* vOut);

// Full scaled-dot-product attention (scaling=1.0, i.e. no scaling at all
// -- see header comment) + causal/sliding mask + softmax + repeat_kv
// broadcast (numKvHeads=1 -> numHeads=8) + o_proj, all in one call. q is
// [seqLen, numHeads*headDim] (already RoPE'd); k/v are [kvSeqLen,
// numKvHeads*headDim] (already RoPE'd for k; v never rotated). oProjW is
// [hiddenSize, numHeads*headDim]. 'cacheLen' is the absolute position of
// query row 0 (0 during prefill, however many tokens are already cached
// otherwise). 'isSliding'/'slidingWindow' select whether the mask is
// plain causal (full_attention layers) or causal AND-ed with a
// slidingWindow-token window (sliding_attention layers, window=512).
// Returns [seqLen, hiddenSize]. Stateless one-shot path (no cache) --
// the score/softmax loop is bf16-native (widens only the scalars it
// actually multiplies, per element, not a whole buffer); o_proj runs
// through gemma4_bf16_linear for real bf16 GPU/BLAS compute.
struct Gemma4Bf16Tensor gemma4_bf16_attn_forward(const &Gemma4Bf16Tensor q, const &Gemma4Bf16Tensor k, const &Gemma4Bf16Tensor v, const &Gemma4Bf16Tensor oProjW,
                                                    unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                                    unsigned long cacheLen, int isSliding, unsigned long slidingWindow);

// Same contract, but K/V come from a paged cache (pages, a
// Vec<Gemma4Bf16KvPage> -- see the paged-cache section above, where each
// page already holds BOTH its K and V slice together) instead of one
// contiguous tensor -- what real generation actually uses. kvSeqLen is
// derived from gemma4_bf16_kv_pages_total_len internally. Verified
// bit-for-bit-equivalent to gemma4_bf16_attn_forward given the same
// logical K/V content, just stored differently -- see
// tests/test_gemma4_attention.sc's paged-vs-flat comparison.
struct Gemma4Bf16Tensor gemma4_bf16_attn_forward_paged(const &Gemma4Bf16Tensor q, &Vec<Gemma4Bf16KvPage> pages, const &Gemma4Bf16Tensor oProjW,
                                                          unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                                          unsigned long cacheLen, int isSliding, unsigned long slidingWindow);

// Same as gemma4_bf16_attn_forward_paged but WITHOUT the final o_proj
// matmul -- returns the raw per-head concatenated attention output
// ([seqLen, numHeads*headDim]) instead. Exists so batched decode
// (gemma4_bf16_model_forward_batch) can call this per sequence (the part
// that must stay per-row, since each row attends to a different
// sequence's own cache) while still batching o_proj into one
// gemma4_bf16_linear call across the whole batch afterward -- see
// gemma4_bf16_attn_forward_paged's own header comment above this one in
// gemma4_attention.sc for why that split matters.
struct Gemma4Bf16Tensor gemma4_bf16_attn_raw_paged(const &Gemma4Bf16Tensor q, &Vec<Gemma4Bf16KvPage> pages,
                                                      unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                                      unsigned long cacheLen, int isSliding, unsigned long slidingWindow);

// Real GPU-parallel batched attention: one Metal dispatch computes every
// sequence's every head's attention output at once (std/ml/gpu_mps.h's
// mps_attn_batch_bf16 -- one GPU thread per (batch, head) pair), instead
// of gemma4_bf16_attn_raw_paged called once per sequence on the CPU.
// 'pagesPtrArr[b]' is sequence b's own page list for this layer (already
// including this step's newly appended token -- append before calling).
// Writes the raw [batchSize, numHeads*headDim] result into 'outConcat'
// (caller-allocated, same shape gemma4_bf16_attn_raw_paged's own return
// value would have per row). Returns 1 on success, 0 on any failure (no
// Metal device, a headDim the kernel doesn't support, forced-CPU
// mode via gemma4_bf16_set_force_cpu) -- caller falls back to looping
// gemma4_bf16_attn_raw_paged per sequence in that case.
int gemma4_bf16_attn_batch_gpu(const &Gemma4Bf16Tensor q, struct Vec<struct Gemma4Bf16KvPage>** pagesPtrArr, const unsigned long* cacheLens,
                                 unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                 int isSliding, unsigned long slidingWindow,
                                 unsigned long batchSize, &Gemma4Bf16Tensor outConcat);

// Chained variant: dispatches attention AND the o_proj matmul that
// follows it inside ONE open Metal batch (one commit+wait for both,
// instead of attention's own full round trip followed by o_proj
// re-uploading attention's CPU-read-back output as a fresh matmul
// input) -- see gemma4_attention.sc's own header comment on this
// function and DESIGN_NOTES.md for why this closes a real gap the
// non-chained gemma4_bf16_attn_batch_gpu left open. Returns the FINAL
// (post-o_proj) result directly into 'attnOut' ([batchSize,
// hiddenSize]) -- callers must NOT apply o_proj again on success (1).
// Returns 0 (attnOut untouched) on any failure -- caller falls back to
// gemma4_bf16_attn_batch_gpu/the CPU path plus its own separate
// gemma4_bf16_linear(..., oProjW) call.
int gemma4_bf16_attn_batch_gpu_chained(const &Gemma4Bf16Tensor q, struct Vec<struct Gemma4Bf16KvPage>** pagesPtrArr,
                                         const &Gemma4Bf16Tensor oProjW,
                                         unsigned long headDim, unsigned long numHeads, unsigned long numKvHeads,
                                         int isSliding, unsigned long slidingWindow,
                                         unsigned long batchSize, unsigned long hiddenSize,
                                         &Gemma4Bf16Tensor attnOut);

} // namespace std
