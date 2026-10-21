#pragma once
// SafeC Standard Library — Gemma 4 E2B full model, bf16 storage AND bf16
// compute throughout, weights and activations alike (see gemma4_bf16.h's
// header comment for the overall design): Gemma4Bf16Model/
// Gemma4Bf16LayerWeights/Gemma4Bf16KvCache/gemma4_bf16_model_load/_free/
// _forward, mirroring what an earlier, now-removed F32 implementation of
// this same model used to look like, field for field and function for
// function, just with every weight and every activation as
// Gemma4Bf16Tensor instead of a plain F32 Tensor throughout -- there is
// no separate F32 model in this codebase anymore; this bf16 model is the
// only one, real bf16 GPU/BLAS compute for every matmul (the dominant
// FLOP cost), bf16-native math (see gemma4_bf16.sc/gemma4_attention.sc/
// gemma4_rope.sc/gemma4_ple.sc) for everything else.
#include <std/ml/tensor.h>
#include <std/collections/vec.h>
#include "gemma4_bf16.h"
#include "gemma4_rope.h"
#include "gemma4_attention.h"
#include "safetensors.h"

namespace std {

struct Gemma4Bf16LayerWeights {
    struct Gemma4Bf16Tensor inputLayernormW;
    struct Gemma4Bf16Tensor postAttentionLayernormW;
    struct Gemma4Bf16Tensor preFeedforwardLayernormW;
    struct Gemma4Bf16Tensor postFeedforwardLayernormW;
    struct Gemma4Bf16Tensor qProjW;
    struct Gemma4Bf16Tensor qNormW;
    // "Empty" (data==null, see gemma4_bf16.h) instead of loaded when
    // isKvShared -- Gemma4Bf16Tensor is a value type, not itself
    // nullable, so "conditionally absent" is expressed through its own
    // empty-sentinel convention rather than a null pointer.
    struct Gemma4Bf16Tensor kProjW;
    struct Gemma4Bf16Tensor kNormW;
    struct Gemma4Bf16Tensor vProjW;
    struct Gemma4Bf16Tensor qkvProjW; // empty when isKvShared
    struct Gemma4Bf16Tensor oProjW;
    struct Gemma4Bf16Tensor gateProjW;
    struct Gemma4Bf16Tensor upProjW;
    struct Gemma4Bf16Tensor downProjW;
    struct Gemma4Bf16Tensor gateUpProjW;
    struct Gemma4Bf16Tensor perLayerInputGateW;
    struct Gemma4Bf16Tensor perLayerProjectionW;
    struct Gemma4Bf16Tensor postPerLayerInputNormW;
    struct Gemma4Bf16Tensor layerScalarW; // [1]
    int isSliding;
    int isKvShared;
    unsigned long headDim;
};

struct Gemma4Bf16Model {
    struct Gemma4Bf16Tensor embedTokensW;
    struct Gemma4Bf16Tensor embedTokensPerLayerW;
    struct Gemma4Bf16Tensor perLayerModelProjectionW;
    struct Gemma4Bf16Tensor perLayerProjectionNormW;
    struct Gemma4Bf16Tensor finalNormW;
    &heap struct Gemma4Bf16LayerWeights layers;

    unsigned long numLayers;
    unsigned long hiddenSize;
    unsigned long pleDim;
    unsigned long numHeads;
    unsigned long numKvHeads;
    unsigned long vocabSize;
    unsigned long slidingWindow;
    unsigned long firstKvSharedLayerIdx;
    unsigned long lastSlidingBeforeShared;
    unsigned long lastFullBeforeShared;
    float eps;
    float finalLogitSoftcap;
};

// Same paged design as Gemma4KvCache, bf16-backed pages (Gemma4Bf16KvPage
// -- see gemma4_attention.h) -- halves the KV cache's own memory
// footprint on top of the weights.
struct Gemma4Bf16KvCache {
    struct Vec<struct Gemma4Bf16KvPage>* pages; // heap array[numCachedLayers]
    unsigned long numCachedLayers;
    unsigned long cachedLen;
};

struct Gemma4Bf16KvCache gemma4_bf16_kvcache_new(&Gemma4Bf16Model model);
void gemma4_bf16_kvcache_free(&Gemma4Bf16KvCache cache);

// Rolls the WHOLE cache (every cached layer's pages, plus cache.cachedLen
// itself) back to 'newLen' -- for speculative decoding's reject path
// (gemma4_generate.h's gemma4_bf16_generate_speculative): verifying K
// drafted tokens appends all K to every layer unconditionally, so a
// rejected suffix needs removing from every layer at once, consistently,
// after the fact. See gemma4_bf16_kv_pages_truncate (gemma4_attention.h)
// for the per-layer mechanics this calls into.
void gemma4_bf16_kvcache_truncate(&Gemma4Bf16KvCache cache, unsigned long newLen);

// Loads every tensor as raw bf16 bits (SafetensorsFile::load_bf16 -- no
// F32 upcast anywhere in the load path), parallelized across worker
// threads exactly like gemma4_model_load. Same "model.language_model.*"
// checkpoint-name assumption.
struct Gemma4Bf16Model gemma4_bf16_model_load(const char* path);

void gemma4_bf16_model_free(&Gemma4Bf16Model model);

// Runs a forward pass over 'count' NEW token ids. bf16 weights/
// activations throughout every one of the 35 layers. Returns F32 logits,
// not bf16: the tied LM head matmul itself runs as real bf16 GPU/BLAS
// compute (gemma4_bf16_linear against the model's largest single
// weight), softcapped in bf16 too (gemma4_bf16_softcap), then widened to
// F32 only for the return -- the caller (gemma4_generate.h's sampling
// loop) works in ordinary F32 logit space, argmax/temperature-sampling
// being cheap, once-per-call operations with no real
// throughput benefit (see gemma4_bf16.h's header comment on this same
// tradeoff elsewhere).
&FloatTensor gemma4_bf16_model_forward(&Gemma4Bf16Model model, const int* tokenIds, unsigned long count, struct Gemma4Bf16KvCache* cache);

// Batched single-token decode step: 'tokenIds' holds exactly one NEW
// token id per sequence (tokenIds[b] is the token for caches[b]), and
// every sequence must already be prefilled (caches[b]->cachedLen > 0 in
// the normal case, via a prior gemma4_bf16_model_forward call on that
// sequence's own cache -- prefill itself stays per-sequence, since
// prompts have different lengths; only the decode step batches).
//
// Design: every matmul-shaped op (q/k/v/o/gate/up/down/per_layer_*/
// tied-lm-head projections -- the dominant FLOP cost) runs as ONE
// gemma4_bf16_linear call across all 'batchSize' rows at once, on both
// the MPS and CPU+BLAS backends (gemma4_bf16_linear already accepts an
// arbitrary row count; batching is "free" there). Attention itself is
// NOT a matmul in this codebase (a hand-rolled per-head score/softmax
// loop, see gemma4_attention.sc) and each sequence must only attend to
// its OWN KV cache, so it stays a per-sequence loop reusing the existing
// single-sequence gemma4_bf16_kv_pages_append/gemma4_bf16_attn_forward_
// paged UNCHANGED, once per batch row -- identical total FLOPs to the
// non-batched path either way (no BLAS/MPS call was ever involved there
// to lose out on by not merging it), just one Vec-indexed loop instead
// of a single call. Returns [batchSize, vocabSize] F32 logits (row b for
// sequence b); increments every caches[b]->cachedLen by 1 on return.
&FloatTensor gemma4_bf16_model_forward_batch(&Gemma4Bf16Model model, const int* tokenIds, unsigned long batchSize, struct Gemma4Bf16KvCache** caches);

} // namespace std
