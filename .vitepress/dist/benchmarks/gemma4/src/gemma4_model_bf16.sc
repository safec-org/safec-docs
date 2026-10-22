// SafeC Standard Library — Gemma 4 E2B full model, bf16 (see
// gemma4_model_bf16.h). Mirrors gemma4_model.sc's implementation
// structure exactly -- see that file for anything not re-explained here.
#pragma once
#include "gemma4_model_bf16.h"
#include "gemma4_bf16.h"
#include "gemma4_rope.h"
#include "gemma4_attention.h"
#include "gemma4_ple.h"
#include "gemma4_ffn.h"
#include "safetensors.h"
#include <std/math.h>
#include <std/ml/activations.h>
#include <std/ml/gpu_mps.h>
#include <std/thread.h>

namespace std {

extern int   printf(const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);

static struct String __g4mbf16_layer_name(unsigned long layerIdx, const char* suffix) {
    struct String s = String::from("model.language_model.layers.");
    s.push_int((long long)layerIdx);
    s.push(".");
    unsafe { s.push(suffix); }
    return s;
}

static unsigned long __g4mbf16_shape0(&SafetensorsFile st, const char* name) {
    long idx = st.find(name);
    unsafe {
        if (idx < 0L) { return 0UL; }
        struct SafetensorsEntry* e = (struct SafetensorsEntry*)st.entries.at((unsigned long)idx);
        return e->shape[0];
    }
}

// ── Parallel checkpoint loading (see gemma4_model.sc's f32 counterpart
// for the full design rationale) -- same shape, just loading straight
// into Gemma4Bf16Tensor destinations via load_bf16 instead of FloatTensor*
// destinations via load. Simpler destination-pointer plumbing than the
// f32 version's: Gemma4Bf16Tensor is a value type, not itself a pointer,
// so a job's 'dest' points directly at the destination struct field
// (e.g. &model.layers[i].qProjW) and a worker just assigns the whole
// loaded struct into it, no (FloatTensor**)-style double-pointer cast
// needed anywhere.
#define NUM_LOAD_WORKERS_BF16 8UL

struct Gemma4Bf16LoadJob {
    struct String name;
    struct Gemma4Bf16Tensor* dest;
};

struct Gemma4Bf16LoadWorkerArgs {
    const char* path;
    struct Gemma4Bf16LoadJob* jobs;
    unsigned long start;
    unsigned long count;
    int ok;
};

static ?&Gemma4Bf16LoadWorkerArgs __g4mbf16_load_worker(?&Gemma4Bf16LoadWorkerArgs argRaw) {
    struct Gemma4Bf16LoadWorkerArgs* args;
    unsafe { args = (struct Gemma4Bf16LoadWorkerArgs*)argRaw; }

    const char* path;
    unsafe { path = args->path; }
    struct SafetensorsFile st = safetensors_open(path);
    unsafe {
        if (st.handle.is_null()) {
            args->ok = 0;
            return null;
        }
    }

    unsigned long i; unsigned long end;
    unsafe { i = args->start; end = args->start + args->count; }
    while (i < end) {
        struct Gemma4Bf16Tensor t;
        unsafe { t = st.load_bf16(args->jobs[i].name.as_ptr()); }
        unsafe {
            if (t.data == (unsigned short*)0) { args->ok = 0; }
            *(args->jobs[i].dest) = t;
        }
        i = i + 1UL;
    }

    st.close_();
    return null;
}

static int __g4mbf16_run_load_jobs(const char* path, struct Gemma4Bf16LoadJob* jobs, unsigned long jobCount) {
    if (jobCount == 0UL) { return 1; }
    unsigned long numWorkers = NUM_LOAD_WORKERS_BF16;
    if (numWorkers > jobCount) { numWorkers = jobCount; }

    struct Gemma4Bf16LoadWorkerArgs* workerArgs;
    unsafe {
        workerArgs = (struct Gemma4Bf16LoadWorkerArgs*)malloc(sizeof(struct Gemma4Bf16LoadWorkerArgs) * numWorkers);
    }

    unsigned long base = jobCount / numWorkers;
    unsigned long remainder = jobCount % numWorkers;
    unsigned long offset = 0UL;
    unsigned long w = 0UL;
    while (w < numWorkers) {
        unsigned long extra;
        if (w < remainder) { extra = 1UL; } else { extra = 0UL; }
        unsigned long thisCount = base + extra;
        unsafe {
            workerArgs[w].path = path;
            workerArgs[w].jobs = jobs;
            workerArgs[w].start = offset;
            workerArgs[w].count = thisCount;
            workerArgs[w].ok = 1;
        }
        offset = offset + thisCount;
        w = w + 1UL;
    }

    unsigned long long* handles;
    unsafe { handles = (unsigned long long*)malloc(sizeof(unsigned long long) * numWorkers); }
    w = 0UL;
    while (w < numWorkers) {
        unsafe { thread_create(&handles[w], __g4mbf16_load_worker, (?&Gemma4Bf16LoadWorkerArgs)&workerArgs[w]); }
        w = w + 1UL;
    }
    w = 0UL;
    while (w < numWorkers) {
        unsafe { thread_join(handles[w]); }
        w = w + 1UL;
    }

    int allOk = 1;
    w = 0UL;
    unsafe {
        while (w < numWorkers) {
            if (!workerArgs[w].ok) { allOk = 0; }
            w = w + 1UL;
        }
        free((void*)handles);
        free((void*)workerArgs);
    }
    return allOk;
}

// ── KV cache ──────────────────────────────────────────────────────────────
struct Gemma4Bf16KvCache gemma4_bf16_kvcache_new(&Gemma4Bf16Model model) {
    struct Gemma4Bf16KvCache cache;
    cache.numCachedLayers = model.firstKvSharedLayerIdx;
    cache.cachedLen = 0UL;
    unsafe {
        cache.pages = (struct Vec<struct Gemma4Bf16KvPage>*)malloc(sizeof(struct Vec<struct Gemma4Bf16KvPage>) * cache.numCachedLayers);
        unsigned long i = 0UL;
        while (i < cache.numCachedLayers) {
            cache.pages[i] = Vec::new();
            i = i + 1UL;
        }
    }
    return cache;
}

void gemma4_bf16_kvcache_free(&Gemma4Bf16KvCache cache) {
    unsafe {
        unsigned long i = 0UL;
        while (i < cache.numCachedLayers) {
            gemma4_bf16_kv_pages_free(&cache.pages[i]);
            i = i + 1UL;
        }
        free((void*)cache.pages);
    }
    unsafe { cache.pages = (struct Vec<struct Gemma4Bf16KvPage>*)0; }
    cache.numCachedLayers = 0UL;
    cache.cachedLen = 0UL;
}

void gemma4_bf16_kvcache_truncate(&Gemma4Bf16KvCache cache, unsigned long newLen) {
    unsafe {
        unsigned long i = 0UL;
        while (i < cache.numCachedLayers) {
            gemma4_bf16_kv_pages_truncate(&cache.pages[i], newLen);
            i = i + 1UL;
        }
    }
    cache.cachedLen = newLen;
}

static void __g4mbf16_push_job(&Vec<Gemma4Bf16LoadJob> jobs, struct String name, struct Gemma4Bf16Tensor* dest) {
    struct Gemma4Bf16LoadJob job;
    job.name = name;
    job.dest = dest;
    jobs.push(job);
}

static struct Gemma4Bf16Tensor __g4mbf16_empty() {
    struct Gemma4Bf16Tensor t;
    unsafe { t.data = (unsigned short*)0; }
    t.shape0 = 0UL; t.shape1 = 0UL; t.ndim = 0UL; t.size = 0UL;
    return t;
}

// ── Batch decode helpers ─────────────────────────────────────────────────
// Gemma4Bf16Tensor has no view/stride mechanism (see gemma4_attention.sc's
// header comment) -- these copy, same as that file's own per-head slice
// helpers. Rows here are always tiny (headDim*numHeads or numKvHeads*
// headDim wide), nowhere near the cost of a matmul or the attention score
// loop, so the copy is not worth avoiding.
static struct Gemma4Bf16Tensor __g4mbf16_row_copy(const &Gemma4Bf16Tensor x, unsigned long rowIdx) {
    unsigned long cols = x.shape1;
    struct Gemma4Bf16Tensor out = gemma4_bf16_new_2d(1UL, cols);
    unsafe {
        unsigned long c = 0UL;
        while (c < cols) {
            out.data[c] = x.data[rowIdx * cols + c];
            c = c + 1UL;
        }
    }
    return out;
}

static void __g4mbf16_write_row(&Gemma4Bf16Tensor dst, unsigned long rowIdx, const &Gemma4Bf16Tensor src) {
    unsigned long cols = dst.shape1;
    unsafe {
        unsigned long c = 0UL;
        while (c < cols) {
            dst.data[rowIdx * cols + c] = src.data[c];
            c = c + 1UL;
        }
    }
}

// ── Batch attention: real GPU dispatch, with a multithreaded CPU fallback ──
// The per-batch-item attention step (the score/softmax/weighted-sum loop,
// see gemma4_attention.sc) is pure per-sequence work with NO shared
// mutable state across batch items -- each sequence b only ever reads its
// own already-appended cache and writes only row b of attnConcatBatch.
// gemma4_bf16_attn_batch_gpu (gemma4_attention.h/.sc) tries a real GPU
// dispatch first, one Metal thread per (batch, head) pair, genuinely
// parallel across BOTH dimensions at once. __g4mbf16_run_attn_batch below
// is the fallback (no Metal device, forced-CPU mode, or KV length past
// the kernel's scratch bound): the same per-sequence work spread across
// worker threads instead, same shape as the existing parallel-checkpoint-
// load pattern below (NUM_LOAD_WORKERS_BF16), just applied to attention
// instead of weight loading. gemma4_bf16_attn_raw_paged's own scratch
// buffers are already call-local (see gemma4_attention.sc), so concurrent
// calls from different worker threads never share any mutable state -- no
// locking needed anywhere in either path. KV-page-append happens ONCE,
// separately, before either path runs (see __g4mbf16_append_kv_batch) --
// cheap (one kvWidth-wide row copy per sequence) relative to the
// attention compute itself, so it doesn't need to be threaded/GPU-
// dispatched to stop it from being the bottleneck.
#define NUM_ATTN_WORKERS_BF16 8UL

static void __g4mbf16_append_kv_batch(struct Gemma4Bf16Tensor* kFused, struct Gemma4Bf16Tensor* vFused,
                                        struct Gemma4Bf16KvCache** caches, unsigned long li, unsigned long kvWidth,
                                        unsigned long batchSize) {
    unsigned long bi = 0UL;
    while (bi < batchSize) {
        struct Gemma4Bf16Tensor kRow;
        struct Gemma4Bf16Tensor vRow;
        unsafe { kRow = __g4mbf16_row_copy(kFused, bi); vRow = __g4mbf16_row_copy(vFused, bi); }
        unsafe { gemma4_bf16_kv_pages_append(&caches[bi]->pages[li], &kRow, &vRow, kvWidth); }
        gemma4_bf16_free(&kRow);
        gemma4_bf16_free(&vRow);
        bi = bi + 1UL;
    }
}

struct Gemma4Bf16AttnWorkerArgs {
    struct Gemma4Bf16Tensor* q;
    struct Gemma4Bf16KvCache** caches;
    unsigned long* cacheLens;
    unsigned long srcLi;
    unsigned long headDim;
    unsigned long numHeads;
    unsigned long numKvHeads;
    int isSliding;
    unsigned long slidingWindow;
    struct Gemma4Bf16Tensor* attnConcatBatch;
    unsigned long start;
    unsigned long count;
};

static ?&Gemma4Bf16AttnWorkerArgs __g4mbf16_attn_worker(?&Gemma4Bf16AttnWorkerArgs argRaw) {
    struct Gemma4Bf16AttnWorkerArgs* args;
    unsafe { args = (struct Gemma4Bf16AttnWorkerArgs*)argRaw; }

    struct Gemma4Bf16Tensor q;
    struct Gemma4Bf16Tensor attnConcatBatch;
    struct Gemma4Bf16KvCache** caches; unsigned long* cacheLens;
    unsigned long srcLi; unsigned long headDim;
    unsigned long numHeads; unsigned long numKvHeads;
    int isSliding; unsigned long slidingWindow;
    unsigned long start; unsigned long count;
    unsafe {
        q = *(args->q);
        attnConcatBatch = *(args->attnConcatBatch);
        caches = args->caches; cacheLens = args->cacheLens;
        srcLi = args->srcLi; headDim = args->headDim;
        numHeads = args->numHeads; numKvHeads = args->numKvHeads;
        isSliding = args->isSliding; slidingWindow = args->slidingWindow;
        start = args->start; count = args->count;
    }

    unsigned long bi = start;
    unsigned long end = start + count;
    while (bi < end) {
        struct Gemma4Bf16Tensor qRow = __g4mbf16_row_copy(&q, bi);
        struct Gemma4Bf16Tensor rawRow;
        unsafe {
            rawRow = gemma4_bf16_attn_raw_paged(&qRow, &caches[bi]->pages[srcLi], headDim,
                                                  numHeads, numKvHeads, cacheLens[bi],
                                                  isSliding, slidingWindow);
        }
        __g4mbf16_write_row(&attnConcatBatch, bi, &rawRow);
        gemma4_bf16_free(&qRow);
        gemma4_bf16_free(&rawRow);
        bi = bi + 1UL;
    }
    return null;
}

// Dispatches batchSize batch-items' attention step across up to
// NUM_ATTN_WORKERS_BF16 worker threads, each handling a contiguous slice
// -- same job-splitting shape as __g4mbf16_run_load_jobs below, just
// splitting batch items instead of load jobs. Every sequence's KV must
// already be appended (see __g4mbf16_append_kv_batch above) before this
// runs.
static void __g4mbf16_run_attn_batch(struct Gemma4Bf16Tensor* q,
                                       struct Gemma4Bf16KvCache** caches, unsigned long* cacheLens,
                                       unsigned long srcLi, unsigned long headDim,
                                       unsigned long numHeads, unsigned long numKvHeads,
                                       int isSliding, unsigned long slidingWindow,
                                       struct Gemma4Bf16Tensor* attnConcatBatch, unsigned long batchSize) {
    unsigned long numWorkers = NUM_ATTN_WORKERS_BF16;
    if (numWorkers > batchSize) { numWorkers = batchSize; }

    struct Gemma4Bf16AttnWorkerArgs* workerArgs;
    unsafe { workerArgs = (struct Gemma4Bf16AttnWorkerArgs*)malloc(sizeof(struct Gemma4Bf16AttnWorkerArgs) * numWorkers); }

    unsigned long base = batchSize / numWorkers;
    unsigned long remainder = batchSize % numWorkers;
    unsigned long offset = 0UL;
    unsigned long w = 0UL;
    while (w < numWorkers) {
        unsigned long extra;
        if (w < remainder) { extra = 1UL; } else { extra = 0UL; }
        unsigned long thisCount = base + extra;
        unsafe {
            workerArgs[w].q = q;
            workerArgs[w].caches = caches; workerArgs[w].cacheLens = cacheLens;
            workerArgs[w].srcLi = srcLi; workerArgs[w].headDim = headDim;
            workerArgs[w].numHeads = numHeads; workerArgs[w].numKvHeads = numKvHeads;
            workerArgs[w].isSliding = isSliding; workerArgs[w].slidingWindow = slidingWindow;
            workerArgs[w].attnConcatBatch = attnConcatBatch;
            workerArgs[w].start = offset; workerArgs[w].count = thisCount;
        }
        offset = offset + thisCount;
        w = w + 1UL;
    }

    if (numWorkers == 1UL) {
        unsafe { __g4mbf16_attn_worker((?&Gemma4Bf16AttnWorkerArgs)&workerArgs[0]); }
        unsafe { free((void*)workerArgs); }
        return;
    }

    unsigned long long* handles;
    unsafe { handles = (unsigned long long*)malloc(sizeof(unsigned long long) * numWorkers); }
    w = 0UL;
    while (w < numWorkers) {
        unsafe { thread_create(&handles[w], __g4mbf16_attn_worker, (?&Gemma4Bf16AttnWorkerArgs)&workerArgs[w]); }
        w = w + 1UL;
    }
    w = 0UL;
    while (w < numWorkers) {
        unsafe { thread_join(handles[w]); }
        w = w + 1UL;
    }
    unsafe {
        free((void*)handles);
        free((void*)workerArgs);
    }
}

// ── Loading ───────────────────────────────────────────────────────────────
struct Gemma4Bf16Model gemma4_bf16_model_load(const char* path) {
    struct Gemma4Bf16Model model;
    model.numLayers = 35UL;
    model.numHeads = 8UL;
    model.numKvHeads = 1UL;
    model.slidingWindow = 512UL;
    model.firstKvSharedLayerIdx = 15UL;
    model.lastSlidingBeforeShared = 13UL;
    model.lastFullBeforeShared = 14UL;
    model.eps = (float)1e-6;
    model.finalLogitSoftcap = (float)30.0;
    unsafe { model.layers = (&heap struct Gemma4Bf16LayerWeights)0; }

    struct SafetensorsFile st = safetensors_open(path);
    unsafe {
        if (st.handle.is_null()) {
            printf("gemma4_bf16_model_load: could not open '%s'\n", path);
            return model;
        }
    }
    model.vocabSize = __g4mbf16_shape0(&st, "model.language_model.embed_tokens.weight");
    model.pleDim = __g4mbf16_shape0(&st, "model.language_model.per_layer_projection_norm.weight");
    unsafe {
        long idx = st.find("model.language_model.embed_tokens.weight");
        struct SafetensorsEntry* e = (struct SafetensorsEntry*)st.entries.at((unsigned long)idx);
        model.hiddenSize = e->shape[1];
    }
    st.close_();

    unsafe {
        model.layers = (&heap struct Gemma4Bf16LayerWeights)malloc(sizeof(struct Gemma4Bf16LayerWeights) * model.numLayers);
    }

    struct Vec<struct Gemma4Bf16LoadJob> jobs = Vec::new();

    unsafe {
        __g4mbf16_push_job(&jobs, String::from("model.language_model.embed_tokens.weight"), &model.embedTokensW);
        __g4mbf16_push_job(&jobs, String::from("model.language_model.embed_tokens_per_layer.weight"), &model.embedTokensPerLayerW);
        __g4mbf16_push_job(&jobs, String::from("model.language_model.per_layer_model_projection.weight"), &model.perLayerModelProjectionW);
        __g4mbf16_push_job(&jobs, String::from("model.language_model.per_layer_projection_norm.weight"), &model.perLayerProjectionNormW);
        __g4mbf16_push_job(&jobs, String::from("model.language_model.norm.weight"), &model.finalNormW);
    }

    unsigned long i = 0UL;
    while (i < model.numLayers) {
        unsigned long mod5 = i % 5UL;
        int isFull;
        if (mod5 == 4UL) { isFull = 1; } else { isFull = 0; }
        int isSliding;
        if (isFull) { isSliding = 0; } else { isSliding = 1; }
        int isKvShared;
        if (i >= model.firstKvSharedLayerIdx) { isKvShared = 1; } else { isKvShared = 0; }
        unsafe {
            model.layers[i].isSliding = isSliding;
            model.layers[i].isKvShared = isKvShared;
        }

        unsafe {
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "input_layernorm.weight"), &model.layers[i].inputLayernormW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "post_attention_layernorm.weight"), &model.layers[i].postAttentionLayernormW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "pre_feedforward_layernorm.weight"), &model.layers[i].preFeedforwardLayernormW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "post_feedforward_layernorm.weight"), &model.layers[i].postFeedforwardLayernormW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "self_attn.q_proj.weight"), &model.layers[i].qProjW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "self_attn.q_norm.weight"), &model.layers[i].qNormW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "self_attn.o_proj.weight"), &model.layers[i].oProjW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "mlp.gate_proj.weight"), &model.layers[i].gateProjW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "mlp.up_proj.weight"), &model.layers[i].upProjW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "mlp.down_proj.weight"), &model.layers[i].downProjW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "per_layer_input_gate.weight"), &model.layers[i].perLayerInputGateW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "per_layer_projection.weight"), &model.layers[i].perLayerProjectionW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "post_per_layer_input_norm.weight"), &model.layers[i].postPerLayerInputNormW);
            __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "layer_scalar"), &model.layers[i].layerScalarW);

            if (isKvShared) {
                model.layers[i].kProjW = __g4mbf16_empty();
                model.layers[i].kNormW = __g4mbf16_empty();
                model.layers[i].vProjW = __g4mbf16_empty();
                model.layers[i].qkvProjW = __g4mbf16_empty();
            } else {
                __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "self_attn.k_proj.weight"), &model.layers[i].kProjW);
                __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "self_attn.k_norm.weight"), &model.layers[i].kNormW);
                __g4mbf16_push_job(&jobs, __g4mbf16_layer_name(i, "self_attn.v_proj.weight"), &model.layers[i].vProjW);
            }
        }

        i = i + 1UL;
    }

    unsigned long jobCount = jobs.length();
    struct Gemma4Bf16LoadJob* jobArr;
    unsafe { jobArr = (struct Gemma4Bf16LoadJob*)jobs.data; }
    int allOk = __g4mbf16_run_load_jobs(path, jobArr, jobCount);
    if (!allOk) {
        printf("gemma4_bf16_model_load: one or more tensors failed to load from '%s'\n", path);
    }

    i = 0UL;
    while (i < model.numLayers) {
        unsafe {
            model.layers[i].headDim = model.layers[i].qProjW.shape0 / model.numHeads;
            model.layers[i].gateUpProjW = gemma4_bf16_concat_rows(&model.layers[i].gateProjW, &model.layers[i].upProjW);
            if (!model.layers[i].isKvShared) {
                struct Gemma4Bf16Tensor qk = gemma4_bf16_concat_rows(&model.layers[i].qProjW, &model.layers[i].kProjW);
                model.layers[i].qkvProjW = gemma4_bf16_concat_rows(&qk, &model.layers[i].vProjW);
                gemma4_bf16_free(&qk);
            }
        }
        i = i + 1UL;
    }

    unsigned long j = 0UL;
    while (j < jobCount) {
        jobs.at(j).name.free();
        j = j + 1UL;
    }
    jobs.free();

    return model;
}

void gemma4_bf16_model_free(&Gemma4Bf16Model model) {
    gemma4_bf16_release_weight_buf(&model.embedTokensW);
    gemma4_bf16_release_weight_buf(&model.perLayerModelProjectionW);

    gemma4_bf16_free(&model.embedTokensW);
    gemma4_bf16_free(&model.embedTokensPerLayerW);
    gemma4_bf16_free(&model.perLayerModelProjectionW);
    gemma4_bf16_free(&model.perLayerProjectionNormW);
    gemma4_bf16_free(&model.finalNormW);

    unsafe {
        if (model.layers != (&heap struct Gemma4Bf16LayerWeights)0) {
            unsigned long i = 0UL;
            while (i < model.numLayers) {
                gemma4_bf16_release_weight_buf(&model.layers[i].qProjW);
                gemma4_bf16_release_weight_buf(&model.layers[i].oProjW);
                gemma4_bf16_release_weight_buf(&model.layers[i].gateUpProjW);
                gemma4_bf16_release_weight_buf(&model.layers[i].downProjW);
                gemma4_bf16_release_weight_buf(&model.layers[i].perLayerInputGateW);
                gemma4_bf16_release_weight_buf(&model.layers[i].perLayerProjectionW);
                if (model.layers[i].qkvProjW.data != (unsigned short*)0) { gemma4_bf16_release_weight_buf(&model.layers[i].qkvProjW); }

                gemma4_bf16_free(&model.layers[i].inputLayernormW);
                gemma4_bf16_free(&model.layers[i].postAttentionLayernormW);
                gemma4_bf16_free(&model.layers[i].preFeedforwardLayernormW);
                gemma4_bf16_free(&model.layers[i].postFeedforwardLayernormW);
                gemma4_bf16_free(&model.layers[i].qProjW);
                gemma4_bf16_free(&model.layers[i].qNormW);
                gemma4_bf16_free(&model.layers[i].oProjW);
                gemma4_bf16_free(&model.layers[i].gateProjW);
                gemma4_bf16_free(&model.layers[i].upProjW);
                gemma4_bf16_free(&model.layers[i].gateUpProjW);
                gemma4_bf16_free(&model.layers[i].downProjW);
                gemma4_bf16_free(&model.layers[i].perLayerInputGateW);
                gemma4_bf16_free(&model.layers[i].perLayerProjectionW);
                gemma4_bf16_free(&model.layers[i].postPerLayerInputNormW);
                gemma4_bf16_free(&model.layers[i].layerScalarW);
                if (model.layers[i].kProjW.data != (unsigned short*)0) { gemma4_bf16_free(&model.layers[i].kProjW); }
                if (model.layers[i].kNormW.data != (unsigned short*)0) { gemma4_bf16_free(&model.layers[i].kNormW); }
                if (model.layers[i].vProjW.data != (unsigned short*)0) { gemma4_bf16_free(&model.layers[i].vProjW); }
                if (model.layers[i].qkvProjW.data != (unsigned short*)0) { gemma4_bf16_free(&model.layers[i].qkvProjW); }
                i = i + 1UL;
            }
            free((void*)model.layers);
        }
    }
    unsafe { model.layers = (&heap struct Gemma4Bf16LayerWeights)0; }
}

// ── Fully GPU-chained per-layer tail (post-attention) ───────────────────────
// Chains o_proj -> attnNorm -> add(residual) -> normed2 -> FFN
// (gate/up matmul -> gated-GELU -> down matmul, itself already chained --
// see gemma4_bf16_ffn_forward_chained_body) -> ffnNorm -> add(residual) ->
// PLE gate matmul -> gelu*pleSlice -> PLE proj matmul -> pleNorm ->
// add(residual) -> scale, ALL as ONE mps_batch_begin/end round trip
// instead of ~4 separate matmul round trips (o_proj, FFN[already 1],
// PLE gate, PLE proj) plus ~10 CPU-only RMSNorm/add/mul/GELU-widen-narrow
// calls in between. Motivated directly by measuring mlx_lm's own decode
// loop (see DESIGN_NOTES.md's "Whole-layer GPU chaining" section): MLX
// builds an entire decode step's forward pass as ONE lazy graph,
// materialized via a single mx.eval() -- this is the same idea applied to
// gemma4's per-layer tail (attention itself stays CPU, unchanged, already
// established as not the bottleneck at this benchmark's context lengths).
//
// Scoped to M==1 (single-request decode, this whole optimization round's
// target) via the caller only invoking this when count==1 -- 'concatOut'
// is the pre-o_proj attention output ([1, numHeads*headDim]), 'residual1'
// is the layer's input (h before this layer). Returns the new 'h'
// (post-layer-scalar-scale) on success, and sets '*outOk' to 1; on ANY
// failure (no Metal device, forced-CPU mode, a weight buffer upload
// failing), sets '*outOk' to 0 and returns an unspecified/unusable tensor
// -- the caller MUST check '*outOk' before using the return value and
// fall back to the existing per-op tail otherwise, same discipline as
// every other GPU path in this codebase.
static struct Gemma4Bf16Tensor __g4mbf16_layer_tail_chained(
        const &Gemma4Bf16Tensor concatOut, unsigned long attnConcatDim, const &Gemma4Bf16Tensor residual1,
        const &Gemma4Bf16Tensor oProjW, const &Gemma4Bf16Tensor postAttnLnW,
        const &Gemma4Bf16Tensor preFfLnW,
        const &Gemma4Bf16Tensor gateUpProjW, const &Gemma4Bf16Tensor downProjW, unsigned long intermediateSize,
        const &Gemma4Bf16Tensor postFfLnW,
        const &Gemma4Bf16Tensor pleSlice,
        const &Gemma4Bf16Tensor perLayerGateW, const &Gemma4Bf16Tensor perLayerProjW, const &Gemma4Bf16Tensor postPerLayerNormW,
        float layerScalarVal, float eps, unsigned long hiddenSize, unsigned long pleDim,
        int* outOk) {
    unsigned long rows = concatOut.shape0;

    struct MpsBuf oProjWBuf; struct MpsBuf postAttnLnWBuf; struct MpsBuf preFfLnWBuf;
    struct MpsBuf gateUpWBuf; struct MpsBuf downWBuf; struct MpsBuf postFfLnWBuf;
    struct MpsBuf perLayerGateWBuf; struct MpsBuf perLayerProjWBuf; struct MpsBuf postPerLayerNormWBuf;
    unsafe {
        oProjWBuf = gemma4_bf16_get_weight_buf(oProjW);
        postAttnLnWBuf = gemma4_bf16_get_weight_buf(postAttnLnW);
        preFfLnWBuf = gemma4_bf16_get_weight_buf(preFfLnW);
        gateUpWBuf = gemma4_bf16_get_weight_buf(gateUpProjW);
        downWBuf = gemma4_bf16_get_weight_buf(downProjW);
        postFfLnWBuf = gemma4_bf16_get_weight_buf(postFfLnW);
        perLayerGateWBuf = gemma4_bf16_get_weight_buf(perLayerGateW);
        perLayerProjWBuf = gemma4_bf16_get_weight_buf(perLayerProjW);
        postPerLayerNormWBuf = gemma4_bf16_get_weight_buf(postPerLayerNormW);
    }
    if (!oProjWBuf.valid() || !postAttnLnWBuf.valid() || !preFfLnWBuf.valid() ||
        !gateUpWBuf.valid() || !downWBuf.valid() || !postFfLnWBuf.valid() ||
        !perLayerGateWBuf.valid() || !perLayerProjWBuf.valid() || !postPerLayerNormWBuf.valid()) {
        unsafe { *outOk = 0; }
        return __g4mbf16_empty();
    }

    struct MpsBuf concatBuf; struct MpsBuf residualBuf; struct MpsBuf pleSliceBuf;
    unsafe {
        concatBuf = MpsBuf::upload_bits(concatOut.data, concatOut.size, MPS_BF16);
        residualBuf = MpsBuf::upload_bits(residual1.data, residual1.size, MPS_BF16);
        pleSliceBuf = MpsBuf::upload_bits(pleSlice.data, pleSlice.size, MPS_BF16);
    }
    if (!concatBuf.valid() || !residualBuf.valid() || !pleSliceBuf.valid()) {
        concatBuf.free(); residualBuf.free(); pleSliceBuf.free();   // free() of an invalid buffer is a no-op
        unsafe { *outOk = 0; }
        return __g4mbf16_empty();
    }

    struct Gemma4Bf16Tensor result = gemma4_bf16_new_2d(rows, hiddenSize);

    // Every throwaway CPU-out pointer for a chained op must stay alive
    // until AFTER mps_batch_end() (see gemma4_bf16_ffn_forward_chained_
    // body's comment for why) -- allocate everything up front, free
    // everything after.
    unsigned short* s_attnRaw; unsigned short* s_attnNormed; unsigned short* s_afterAttn;
    unsigned short* s_normed2; unsigned short* s_gateUp; unsigned short* s_gated; unsigned short* s_ffnOut;
    unsigned short* s_ffnNormed; unsigned short* s_afterFfn; unsigned short* s_gateH; unsigned short* s_gated2;
    unsigned short* s_pleProj; unsigned short* s_pleNormed; unsigned short* s_afterPle;
    unsigned long hb = rows * hiddenSize * 2UL;
    unsigned long ib2 = rows * 2UL * intermediateSize * 2UL;
    unsigned long ib1 = rows * intermediateSize * 2UL;
    unsigned long pb = rows * pleDim * 2UL;
    unsafe {
        s_attnRaw = (unsigned short*)malloc(hb);
        s_attnNormed = (unsigned short*)malloc(hb);
        s_afterAttn = (unsigned short*)malloc(hb);
        s_normed2 = (unsigned short*)malloc(hb);
        s_gateUp = (unsigned short*)malloc(ib2);
        s_gated = (unsigned short*)malloc(ib1);
        s_ffnOut = (unsigned short*)malloc(hb);
        s_ffnNormed = (unsigned short*)malloc(hb);
        s_afterFfn = (unsigned short*)malloc(hb);
        s_gateH = (unsigned short*)malloc(pb);
        s_gated2 = (unsigned short*)malloc(pb);
        s_pleProj = (unsigned short*)malloc(hb);
        s_pleNormed = (unsigned short*)malloc(hb);
        s_afterPle = (unsigned short*)malloc(hb);
    }

    int ok = 1;
    struct MpsBuf attnRawBuf; struct MpsBuf attnNormedBuf; struct MpsBuf afterAttnBuf;   // zero-initialized: invalid
    struct MpsBuf normed2Buf; struct MpsBuf ffnOutBuf; struct MpsBuf ffnNormedBuf;
    struct MpsBuf afterFfnBuf; struct MpsBuf gateHBuf; struct MpsBuf gated2Buf;
    struct MpsBuf pleProjBuf; struct MpsBuf pleNormedBuf; struct MpsBuf afterPleBuf;
    unsafe {
        mps_batch_begin();
        if (ok) { ok = mps_matmul_abt_bf16_persistent(concatBuf, oProjWBuf, s_attnRaw, rows, attnConcatDim, hiddenSize); if (ok) { attnRawBuf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = mps_rmsnorm_bf16_chained(attnRawBuf, postAttnLnWBuf, s_attnNormed, rows, hiddenSize, eps); if (ok) { attnNormedBuf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = mps_add_bf16_chained(residualBuf, attnNormedBuf, s_afterAttn, rows * hiddenSize); if (ok) { afterAttnBuf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = mps_rmsnorm_bf16_chained(afterAttnBuf, preFfLnWBuf, s_normed2, rows, hiddenSize, eps); if (ok) { normed2Buf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = gemma4_bf16_ffn_forward_chained_body(normed2Buf, gateUpWBuf, downWBuf, rows, hiddenSize, intermediateSize, s_gateUp, s_gated, s_ffnOut); if (ok) { ffnOutBuf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = mps_rmsnorm_bf16_chained(ffnOutBuf, postFfLnWBuf, s_ffnNormed, rows, hiddenSize, eps); if (ok) { ffnNormedBuf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = mps_add_bf16_chained(afterAttnBuf, ffnNormedBuf, s_afterFfn, rows * hiddenSize); if (ok) { afterFfnBuf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = mps_matmul_abt_bf16_persistent(afterFfnBuf, perLayerGateWBuf, s_gateH, rows, hiddenSize, pleDim); if (ok) { gateHBuf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = mps_gelu_mul_bf16_chained(gateHBuf, pleSliceBuf, s_gated2, rows * pleDim); if (ok) { gated2Buf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = mps_matmul_abt_bf16_persistent(gated2Buf, perLayerProjWBuf, s_pleProj, rows, pleDim, hiddenSize); if (ok) { pleProjBuf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = mps_rmsnorm_bf16_chained(pleProjBuf, postPerLayerNormWBuf, s_pleNormed, rows, hiddenSize, eps); if (ok) { pleNormedBuf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = mps_add_bf16_chained(afterFfnBuf, pleNormedBuf, s_afterPle, rows * hiddenSize); if (ok) { afterPleBuf = MpsBuf::batch_last_output(); } }
        if (ok) { ok = mps_scale_bf16_chained(afterPleBuf, layerScalarVal, result.data, rows * hiddenSize); }
        mps_batch_end();

        concatBuf.free(); residualBuf.free(); pleSliceBuf.free();
        free((void*)s_attnRaw); free((void*)s_attnNormed); free((void*)s_afterAttn);
        free((void*)s_normed2); free((void*)s_gateUp); free((void*)s_gated); free((void*)s_ffnOut);
        free((void*)s_ffnNormed); free((void*)s_afterFfn); free((void*)s_gateH); free((void*)s_gated2);
        free((void*)s_pleProj); free((void*)s_pleNormed); free((void*)s_afterPle);
    }

    unsafe { *outOk = ok; }
    if (!ok) { gemma4_bf16_free(&result); return __g4mbf16_empty(); }
    return result;
}

// ── Forward pass ──────────────────────────────────────────────────────────
&FloatTensor gemma4_bf16_model_forward(&Gemma4Bf16Model model, const int* tokenIds, unsigned long count, struct Gemma4Bf16KvCache* cache) {
    unsigned long cacheLen = 0UL;
    unsafe { if (cache != (struct Gemma4Bf16KvCache*)0) { cacheLen = cache->cachedLen; } }

    float embedScale = sqrt_f((float)model.hiddenSize);
    struct Gemma4Bf16Tensor inputsEmbeds = gemma4_bf16_embed_lookup_rows(&model.embedTokensW, tokenIds, count, embedScale);

    float pleScale = sqrt_f((float)model.pleDim);
    struct Gemma4Bf16Tensor tokenIdentity = gemma4_bf16_embed_lookup_rows(&model.embedTokensPerLayerW, tokenIds, count, pleScale);
    struct Gemma4Bf16Tensor pleAll = gemma4_bf16_ple_combine(&inputsEmbeds, &model.perLayerModelProjectionW,
                                         &model.perLayerProjectionNormW, &tokenIdentity,
                                         model.numLayers, model.pleDim, model.eps);
    gemma4_bf16_free(&tokenIdentity);

    unsigned long* positions;
    unsafe { positions = (unsigned long*)malloc(sizeof(unsigned long) * count); }
    unsigned long pi = 0UL;
    while (pi < count) { unsafe { positions[pi] = cacheLen + pi; } pi = pi + 1UL; }
    unsigned long slidingHeadDim; unsigned long fullHeadDim;
    unsafe { slidingHeadDim = model.layers[0].headDim; fullHeadDim = model.layers[4].headDim; }
    struct Gemma4RopeTable slidingRope = gemma4_rope_build(0, slidingHeadDim, positions, count);
    struct Gemma4RopeTable fullRope = gemma4_rope_build(1, fullHeadDim, positions, count);
    unsafe { free((void*)positions); }

    struct Gemma4Bf16Tensor h = inputsEmbeds;
    struct Gemma4Bf16Tensor sharedSlidingK = __g4mbf16_empty();
    struct Gemma4Bf16Tensor sharedSlidingV = __g4mbf16_empty();
    struct Gemma4Bf16Tensor sharedFullK = __g4mbf16_empty();
    struct Gemma4Bf16Tensor sharedFullV = __g4mbf16_empty();

    unsigned long li = 0UL;
    while (li < model.numLayers) {
        int isSliding; int isKvShared; unsigned long headDim;
        struct Gemma4Bf16Tensor inputLnW; struct Gemma4Bf16Tensor postAttnLnW; struct Gemma4Bf16Tensor preFfLnW; struct Gemma4Bf16Tensor postFfLnW;
        struct Gemma4Bf16Tensor qProjW; struct Gemma4Bf16Tensor qNormW; struct Gemma4Bf16Tensor oProjW;
        struct Gemma4Bf16Tensor kProjW; struct Gemma4Bf16Tensor kNormW; struct Gemma4Bf16Tensor vProjW;
        struct Gemma4Bf16Tensor qkvProjW;
        struct Gemma4Bf16Tensor gateUpProjW; struct Gemma4Bf16Tensor downProjW;
        struct Gemma4Bf16Tensor perLayerGateW; struct Gemma4Bf16Tensor perLayerProjW; struct Gemma4Bf16Tensor postPerLayerNormW;
        float layerScalarVal;
        unsigned long intermediateSize;
        unsafe {
            isSliding = model.layers[li].isSliding;
            isKvShared = model.layers[li].isKvShared;
            headDim = model.layers[li].headDim;
            inputLnW = model.layers[li].inputLayernormW;
            postAttnLnW = model.layers[li].postAttentionLayernormW;
            preFfLnW = model.layers[li].preFeedforwardLayernormW;
            postFfLnW = model.layers[li].postFeedforwardLayernormW;
            qProjW = model.layers[li].qProjW;
            qNormW = model.layers[li].qNormW;
            oProjW = model.layers[li].oProjW;
            kProjW = model.layers[li].kProjW;
            kNormW = model.layers[li].kNormW;
            vProjW = model.layers[li].vProjW;
            qkvProjW = model.layers[li].qkvProjW;
            gateUpProjW = model.layers[li].gateUpProjW;
            downProjW = model.layers[li].downProjW;
            intermediateSize = model.layers[li].gateProjW.shape0;
            perLayerGateW = model.layers[li].perLayerInputGateW;
            perLayerProjW = model.layers[li].perLayerProjectionW;
            postPerLayerNormW = model.layers[li].postPerLayerInputNormW;
            layerScalarVal = bf16_to_f32(model.layers[li].layerScalarW.data[0]);
        }

        struct Gemma4Bf16Tensor residual1 = h;
        struct Gemma4Bf16Tensor normed1 = gemma4_bf16_rmsnorm_rows(&h, &inputLnW, model.eps);

        struct Gemma4Bf16Tensor q;
        struct Gemma4Bf16Tensor kFused = __g4mbf16_empty();
        struct Gemma4Bf16Tensor vFused = __g4mbf16_empty();
        if (isKvShared) {
            if (isSliding) {
                q = gemma4_bf16_attn_project_q(&normed1, &qProjW, &qNormW, model.eps, &slidingRope, headDim, model.numHeads);
            } else {
                q = gemma4_bf16_attn_project_q(&normed1, &qProjW, &qNormW, model.eps, &fullRope, headDim, model.numHeads);
            }
        } else {
            if (isSliding) {
                gemma4_bf16_attn_project_qkv_fused(&normed1, &qkvProjW, &qNormW, &kNormW, model.eps,
                                               &slidingRope, headDim, model.numHeads, model.numKvHeads,
                                               &q, &kFused, &vFused);
            } else {
                gemma4_bf16_attn_project_qkv_fused(&normed1, &qkvProjW, &qNormW, &kNormW, model.eps,
                                               &fullRope, headDim, model.numHeads, model.numKvHeads,
                                               &q, &kFused, &vFused);
            }
        }

        struct Gemma4Bf16Tensor attnRaw;
        int chainedTailDone = 0;
        struct Gemma4Bf16Tensor chainedResult;
        if (cache != (struct Gemma4Bf16KvCache*)0) {
            unsigned long srcLi;
            if (isKvShared) {
                if (isSliding) { srcLi = model.lastSlidingBeforeShared; }
                else { srcLi = model.lastFullBeforeShared; }
            } else {
                unsigned long kvWidth = model.numKvHeads * headDim;
                unsafe {
                    gemma4_bf16_kv_pages_append(&cache->pages[li], &kFused, &vFused, kvWidth);
                }
                gemma4_bf16_free(&kFused);
                gemma4_bf16_free(&vFused);
                srcLi = li;
            }
            // Pre-o_proj attention output (gemma4_bf16_attn_raw_paged, not
            // gemma4_bf16_attn_forward_paged -- the latter also does
            // o_proj internally as its OWN separate GPU round trip, which
            // is exactly the round trip the chained tail below folds into
            // its single combined dispatch instead). count==1 (single-
            // request decode, this whole optimization round's scope) and
            // GPU available: try the fully-chained tail (o_proj through
            // this layer's final scale, one round trip -- see
            // __g4mbf16_layer_tail_chained's comment above). Any failure
            // (or count!=1) falls back to the per-op path below, doing
            // o_proj itself via gemma4_bf16_linear the way it always did.
            struct Gemma4Bf16Tensor concatOut;
            unsafe {
                concatOut = gemma4_bf16_attn_raw_paged(&q, &cache->pages[srcLi], headDim,
                                                         model.numHeads, model.numKvHeads, cacheLen,
                                                         isSliding, model.slidingWindow);
            }
            if (count == 1UL && !gemma4_bf16_get_force_cpu()) {
                unsigned long attnConcatDim = model.numHeads * headDim;
                struct Gemma4Bf16Tensor pleSliceForChain = gemma4_bf16_ple_slice_layer(&pleAll, li, model.pleDim);
                int ok = 0;
                chainedResult = __g4mbf16_layer_tail_chained(
                    &concatOut, attnConcatDim, &residual1,
                    &oProjW, &postAttnLnW, &preFfLnW,
                    &gateUpProjW, &downProjW, intermediateSize,
                    &postFfLnW, &pleSliceForChain,
                    &perLayerGateW, &perLayerProjW, &postPerLayerNormW,
                    layerScalarVal, model.eps, model.hiddenSize, model.pleDim, &ok);
                gemma4_bf16_free(&pleSliceForChain);
                if (ok) {
                    chainedTailDone = 1;
                    gemma4_bf16_free(&concatOut);
                    gemma4_bf16_free(&normed1);
                    gemma4_bf16_free(&q);
                    gemma4_bf16_free(&residual1);
                }
            }
            if (!chainedTailDone) {
                attnRaw = gemma4_bf16_linear(&concatOut, &oProjW);
                gemma4_bf16_free(&concatOut);
            }
        } else {
            struct Gemma4Bf16Tensor k;
            struct Gemma4Bf16Tensor v;
            if (isKvShared) {
                if (isSliding) { k = sharedSlidingK; v = sharedSlidingV; }
                else { k = sharedFullK; v = sharedFullV; }
            } else {
                k = kFused;
                v = vFused;
                if (li == model.lastSlidingBeforeShared) { sharedSlidingK = k; sharedSlidingV = v; }
                if (li == model.lastFullBeforeShared) { sharedFullK = k; sharedFullV = v; }
            }
            attnRaw = gemma4_bf16_attn_forward(&q, &k, &v, &oProjW, headDim,
                                           model.numHeads, model.numKvHeads, cacheLen,
                                           isSliding, model.slidingWindow);
            if (!isKvShared && li != model.lastSlidingBeforeShared && li != model.lastFullBeforeShared) {
                gemma4_bf16_free(&k); gemma4_bf16_free(&v);
            }
        }

        if (chainedTailDone) {
            h = chainedResult;
            li = li + 1UL;
            continue;
        }

        gemma4_bf16_free(&normed1);
        gemma4_bf16_free(&q);

        struct Gemma4Bf16Tensor attnNormed = gemma4_bf16_rmsnorm_rows(&attnRaw, &postAttnLnW, model.eps);
        gemma4_bf16_free(&attnRaw);
        struct Gemma4Bf16Tensor afterAttn = gemma4_bf16_add(&residual1, &attnNormed);
        gemma4_bf16_free(&attnNormed);
        gemma4_bf16_free(&residual1);

        struct Gemma4Bf16Tensor residual2 = afterAttn;
        struct Gemma4Bf16Tensor normed2 = gemma4_bf16_rmsnorm_rows(&afterAttn, &preFfLnW, model.eps);
        struct Gemma4Bf16Tensor ffnOut = gemma4_bf16_ffn_forward_fused(&normed2, &gateUpProjW, &downProjW, intermediateSize);
        gemma4_bf16_free(&normed2);
        struct Gemma4Bf16Tensor ffnNormed = gemma4_bf16_rmsnorm_rows(&ffnOut, &postFfLnW, model.eps);
        gemma4_bf16_free(&ffnOut);
        struct Gemma4Bf16Tensor afterFfn = gemma4_bf16_add(&residual2, &ffnNormed);
        gemma4_bf16_free(&ffnNormed);
        gemma4_bf16_free(&residual2);

        struct Gemma4Bf16Tensor residual3 = afterFfn;
        struct Gemma4Bf16Tensor pleSlice = gemma4_bf16_ple_slice_layer(&pleAll, li, model.pleDim);
        struct Gemma4Bf16Tensor gateH = gemma4_bf16_linear(&afterFfn, &perLayerGateW);
        // GELU: widen -> existing verified FloatTensor::gelu_fwd -> narrow
        // (see gemma4_bf16_ffn_forward_fused's identical reasoning).
        FloatTensor* gateHF = gemma4_bf16_to_f32(&gateH);
        gemma4_bf16_free(&gateH);
        struct Gemma4Bf16Tensor gateAct;
        unsafe {
            &FloatTensor gateActF = gateHF->gelu_fwd();
            gateHF->free();
            gateAct = gemma4_bf16_from_f32(gateActF);
            gateActF.free();
        }
        struct Gemma4Bf16Tensor gated = gemma4_bf16_mul(&gateAct, &pleSlice);
        gemma4_bf16_free(&gateAct);
        gemma4_bf16_free(&pleSlice);
        struct Gemma4Bf16Tensor pleProj = gemma4_bf16_linear(&gated, &perLayerProjW);
        gemma4_bf16_free(&gated);
        struct Gemma4Bf16Tensor pleNormed = gemma4_bf16_rmsnorm_rows(&pleProj, &postPerLayerNormW, model.eps);
        gemma4_bf16_free(&pleProj);
        struct Gemma4Bf16Tensor afterPle = gemma4_bf16_add(&residual3, &pleNormed);
        gemma4_bf16_free(&pleNormed);
        gemma4_bf16_free(&residual3);

        struct Gemma4Bf16Tensor scaled = gemma4_bf16_scale(&afterPle, layerScalarVal);
        gemma4_bf16_free(&afterPle);

        h = scaled;
        li = li + 1UL;
    }

    gemma4_bf16_free(&pleAll);
    gemma4_rope_table_free(&slidingRope);
    gemma4_rope_table_free(&fullRope);
    unsafe {
        if (cache == (struct Gemma4Bf16KvCache*)0) {
            if (sharedSlidingK.data != (unsigned short*)0) { gemma4_bf16_free(&sharedSlidingK); gemma4_bf16_free(&sharedSlidingV); }
            if (sharedFullK.data != (unsigned short*)0) { gemma4_bf16_free(&sharedFullK); gemma4_bf16_free(&sharedFullV); }
        } else {
            cache->cachedLen = cache->cachedLen + count;
        }
    }

    struct Gemma4Bf16Tensor finalNormed = gemma4_bf16_rmsnorm_rows(&h, &model.finalNormW, model.eps);
    gemma4_bf16_free(&h);

    struct Gemma4Bf16Tensor rawLogitsBf16 = gemma4_bf16_linear(&finalNormed, &model.embedTokensW);
    gemma4_bf16_free(&finalNormed);
    struct Gemma4Bf16Tensor softcappedBf16 = gemma4_bf16_softcap(&rawLogitsBf16, model.finalLogitSoftcap);
    gemma4_bf16_free(&rawLogitsBf16);
    FloatTensor* logits = gemma4_bf16_to_f32(&softcappedBf16);
    gemma4_bf16_free(&softcappedBf16);

    unsafe { return logits; }
}

// ── Batched decode-step forward pass (see gemma4_model_bf16.h) ────────────
&FloatTensor gemma4_bf16_model_forward_batch(&Gemma4Bf16Model model, const int* tokenIds, unsigned long batchSize, struct Gemma4Bf16KvCache** caches) {
    unsigned long* cacheLens;
    unsafe { cacheLens = (unsigned long*)malloc(sizeof(unsigned long) * batchSize); }
    unsigned long bi = 0UL;
    while (bi < batchSize) {
        unsafe { cacheLens[bi] = caches[bi]->cachedLen; }
        bi = bi + 1UL;
    }

    float embedScale = sqrt_f((float)model.hiddenSize);
    struct Gemma4Bf16Tensor inputsEmbeds = gemma4_bf16_embed_lookup_rows(&model.embedTokensW, tokenIds, batchSize, embedScale);

    float pleScale = sqrt_f((float)model.pleDim);
    struct Gemma4Bf16Tensor tokenIdentity = gemma4_bf16_embed_lookup_rows(&model.embedTokensPerLayerW, tokenIds, batchSize, pleScale);
    struct Gemma4Bf16Tensor pleAll = gemma4_bf16_ple_combine(&inputsEmbeds, &model.perLayerModelProjectionW,
                                         &model.perLayerProjectionNormW, &tokenIdentity,
                                         model.numLayers, model.pleDim, model.eps);
    gemma4_bf16_free(&tokenIdentity);

    // Each sequence's new token sits at its OWN current position
    // (cacheLens[b]) -- gemma4_rope_build already takes an arbitrary
    // per-row positions array (built for exactly this: single-step decode
    // isn't necessarily position 0..count-1), so no RoPE changes needed.
    unsigned long slidingHeadDim; unsigned long fullHeadDim;
    unsafe { slidingHeadDim = model.layers[0].headDim; fullHeadDim = model.layers[4].headDim; }
    struct Gemma4RopeTable slidingRope = gemma4_rope_build(0, slidingHeadDim, cacheLens, batchSize);
    struct Gemma4RopeTable fullRope = gemma4_rope_build(1, fullHeadDim, cacheLens, batchSize);

    struct Gemma4Bf16Tensor h = inputsEmbeds;

    unsigned long li = 0UL;
    while (li < model.numLayers) {
        int isSliding; int isKvShared; unsigned long headDim;
        struct Gemma4Bf16Tensor inputLnW; struct Gemma4Bf16Tensor postAttnLnW; struct Gemma4Bf16Tensor preFfLnW; struct Gemma4Bf16Tensor postFfLnW;
        struct Gemma4Bf16Tensor qProjW; struct Gemma4Bf16Tensor qNormW; struct Gemma4Bf16Tensor oProjW;
        struct Gemma4Bf16Tensor kNormW;
        struct Gemma4Bf16Tensor qkvProjW;
        struct Gemma4Bf16Tensor gateUpProjW; struct Gemma4Bf16Tensor downProjW;
        struct Gemma4Bf16Tensor perLayerGateW; struct Gemma4Bf16Tensor perLayerProjW; struct Gemma4Bf16Tensor postPerLayerNormW;
        float layerScalarVal;
        unsigned long intermediateSize;
        unsafe {
            isSliding = model.layers[li].isSliding;
            isKvShared = model.layers[li].isKvShared;
            headDim = model.layers[li].headDim;
            inputLnW = model.layers[li].inputLayernormW;
            postAttnLnW = model.layers[li].postAttentionLayernormW;
            preFfLnW = model.layers[li].preFeedforwardLayernormW;
            postFfLnW = model.layers[li].postFeedforwardLayernormW;
            qProjW = model.layers[li].qProjW;
            qNormW = model.layers[li].qNormW;
            oProjW = model.layers[li].oProjW;
            kNormW = model.layers[li].kNormW;
            qkvProjW = model.layers[li].qkvProjW;
            gateUpProjW = model.layers[li].gateUpProjW;
            downProjW = model.layers[li].downProjW;
            intermediateSize = model.layers[li].gateProjW.shape0;
            perLayerGateW = model.layers[li].perLayerInputGateW;
            perLayerProjW = model.layers[li].perLayerProjectionW;
            postPerLayerNormW = model.layers[li].postPerLayerInputNormW;
            layerScalarVal = bf16_to_f32(model.layers[li].layerScalarW.data[0]);
        }

        struct Gemma4Bf16Tensor residual1 = h;
        struct Gemma4Bf16Tensor normed1 = gemma4_bf16_rmsnorm_rows(&h, &inputLnW, model.eps);

        // Every matmul here (qkv/q projection) runs ONCE across the whole
        // batch -- gemma4_bf16_linear (inside gemma4_bf16_attn_project_q/
        // _qkv_fused) already takes an arbitrary row count on both the MPS
        // and CPU+BLAS backends, so this is the actual "batching win".
        struct Gemma4Bf16Tensor q;
        struct Gemma4Bf16Tensor kFused = __g4mbf16_empty();
        struct Gemma4Bf16Tensor vFused = __g4mbf16_empty();
        if (isKvShared) {
            if (isSliding) {
                q = gemma4_bf16_attn_project_q(&normed1, &qProjW, &qNormW, model.eps, &slidingRope, headDim, model.numHeads);
            } else {
                q = gemma4_bf16_attn_project_q(&normed1, &qProjW, &qNormW, model.eps, &fullRope, headDim, model.numHeads);
            }
        } else {
            if (isSliding) {
                gemma4_bf16_attn_project_qkv_fused(&normed1, &qkvProjW, &qNormW, &kNormW, model.eps,
                                               &slidingRope, headDim, model.numHeads, model.numKvHeads,
                                               &q, &kFused, &vFused);
            } else {
                gemma4_bf16_attn_project_qkv_fused(&normed1, &qkvProjW, &qNormW, &kNormW, model.eps,
                                               &fullRope, headDim, model.numHeads, model.numKvHeads,
                                               &q, &kFused, &vFused);
            }
        }
        gemma4_bf16_free(&normed1);

        // Attention's score/softmax/weighted-sum loop is a hand-rolled
        // per-head loop, not a matmul (see gemma4_attention.sc), and each
        // sequence must only attend to its OWN cache. o_proj IS a real
        // matmul (as expensive as q/k/v/gate/up/down), so it must NOT be
        // called per row the way an earlier version of this function did
        // (via gemma4_bf16_attn_forward_paged, which bundles o_proj into
        // the per-sequence call) -- that silently un-batched one of the
        // four big per-layer matmuls and was the actual cause of batched
        // decode's sub-linear throughput scaling. Collect every row's RAW
        // (pre-o_proj) attention output into one [batchSize,
        // numHeads*headDim] tensor, then apply o_proj ONCE across the
        // whole batch afterward.
        unsigned long srcLi;
        if (isKvShared) {
            if (isSliding) { srcLi = model.lastSlidingBeforeShared; } else { srcLi = model.lastFullBeforeShared; }
        } else {
            srcLi = li;
        }
        unsigned long kvWidth = model.numKvHeads * headDim;

        // Append this step's new K/V into each sequence's own cache FIRST
        // (cheap -- one kvWidth-wide row copy per sequence, not the
        // O(kvLen*headDim) attention compute itself), regardless of which
        // attention path runs below.
        if (!isKvShared) {
            __g4mbf16_append_kv_batch(&kFused, &vFused, caches, li, kvWidth, batchSize);
        }

        struct Vec<struct Gemma4Bf16KvPage>** pagesPtrArr;
        unsafe { pagesPtrArr = (struct Vec<struct Gemma4Bf16KvPage>**)malloc(sizeof(struct Vec<struct Gemma4Bf16KvPage>*) * batchSize); }
        unsigned long ppi = 0UL;
        while (ppi < batchSize) {
            unsafe { pagesPtrArr[ppi] = caches[ppi]->pages + srcLi; }
            ppi = ppi + 1UL;
        }

        // Chained GPU path first: attention AND o_proj dispatched inside
        // ONE open Metal batch, one commit+wait for both instead of
        // attention's own full round trip followed by o_proj re-uploading
        // attention's CPU-read-back output as a fresh matmul input (see
        // gemma4_bf16_attn_batch_gpu_chained's header comment and
        // DESIGN_NOTES.md). Falls back to the older non-chained GPU path
        // (still real GPU-parallel attention, just its own separate
        // dispatch, then a normal gemma4_bf16_linear call for o_proj) or,
        // failing that, the multithreaded CPU path -- same total FLOPs
        // either way, no Metal device / forced-CPU / KV-length-past-
        // bound are the only reasons any of these steps decline.
        struct Gemma4Bf16Tensor attnRaw = gemma4_bf16_new_2d(batchSize, model.hiddenSize);
        int chainedOk;
        unsafe {
            chainedOk = gemma4_bf16_attn_batch_gpu_chained(&q, pagesPtrArr, &oProjW, headDim,
                                                              model.numHeads, model.numKvHeads,
                                                              isSliding, model.slidingWindow,
                                                              batchSize, model.hiddenSize, &attnRaw);
        }

        if (!chainedOk) {
            gemma4_bf16_free(&attnRaw);
            struct Gemma4Bf16Tensor attnConcatBatch = gemma4_bf16_new_2d(batchSize, model.numHeads * headDim);
            int gpuOk;
            unsafe {
                gpuOk = gemma4_bf16_attn_batch_gpu(&q, pagesPtrArr, cacheLens, headDim,
                                                     model.numHeads, model.numKvHeads,
                                                     isSliding, model.slidingWindow,
                                                     batchSize, &attnConcatBatch);
            }
            if (!gpuOk) {
                unsafe {
                    __g4mbf16_run_attn_batch(&q, caches, cacheLens, srcLi, headDim,
                                               model.numHeads, model.numKvHeads,
                                               isSliding, model.slidingWindow,
                                               &attnConcatBatch, batchSize);
                }
            }
            attnRaw = gemma4_bf16_linear(&attnConcatBatch, &oProjW);
            gemma4_bf16_free(&attnConcatBatch);
        }
        unsafe { free((void*)pagesPtrArr); }

        gemma4_bf16_free(&q);
        if (!isKvShared) { gemma4_bf16_free(&kFused); gemma4_bf16_free(&vFused); }

        struct Gemma4Bf16Tensor attnNormed = gemma4_bf16_rmsnorm_rows(&attnRaw, &postAttnLnW, model.eps);
        gemma4_bf16_free(&attnRaw);
        struct Gemma4Bf16Tensor afterAttn = gemma4_bf16_add(&residual1, &attnNormed);
        gemma4_bf16_free(&attnNormed);
        gemma4_bf16_free(&residual1);

        struct Gemma4Bf16Tensor residual2 = afterAttn;
        struct Gemma4Bf16Tensor normed2 = gemma4_bf16_rmsnorm_rows(&afterAttn, &preFfLnW, model.eps);
        struct Gemma4Bf16Tensor ffnOut = gemma4_bf16_ffn_forward_fused(&normed2, &gateUpProjW, &downProjW, intermediateSize);
        gemma4_bf16_free(&normed2);
        struct Gemma4Bf16Tensor ffnNormed = gemma4_bf16_rmsnorm_rows(&ffnOut, &postFfLnW, model.eps);
        gemma4_bf16_free(&ffnOut);
        struct Gemma4Bf16Tensor afterFfn = gemma4_bf16_add(&residual2, &ffnNormed);
        gemma4_bf16_free(&ffnNormed);
        gemma4_bf16_free(&residual2);

        struct Gemma4Bf16Tensor residual3 = afterFfn;
        struct Gemma4Bf16Tensor pleSlice = gemma4_bf16_ple_slice_layer(&pleAll, li, model.pleDim);
        struct Gemma4Bf16Tensor gateH = gemma4_bf16_linear(&afterFfn, &perLayerGateW);
        FloatTensor* gateHF = gemma4_bf16_to_f32(&gateH);
        gemma4_bf16_free(&gateH);
        struct Gemma4Bf16Tensor gateAct;
        unsafe {
            &FloatTensor gateActF = gateHF->gelu_fwd();
            gateHF->free();
            gateAct = gemma4_bf16_from_f32(gateActF);
            gateActF.free();
        }
        struct Gemma4Bf16Tensor gated = gemma4_bf16_mul(&gateAct, &pleSlice);
        gemma4_bf16_free(&gateAct);
        gemma4_bf16_free(&pleSlice);
        struct Gemma4Bf16Tensor pleProj = gemma4_bf16_linear(&gated, &perLayerProjW);
        gemma4_bf16_free(&gated);
        struct Gemma4Bf16Tensor pleNormed = gemma4_bf16_rmsnorm_rows(&pleProj, &postPerLayerNormW, model.eps);
        gemma4_bf16_free(&pleProj);
        struct Gemma4Bf16Tensor afterPle = gemma4_bf16_add(&residual3, &pleNormed);
        gemma4_bf16_free(&pleNormed);
        gemma4_bf16_free(&residual3);

        struct Gemma4Bf16Tensor scaled = gemma4_bf16_scale(&afterPle, layerScalarVal);
        gemma4_bf16_free(&afterPle);

        h = scaled;
        li = li + 1UL;
    }

    gemma4_bf16_free(&pleAll);
    gemma4_rope_table_free(&slidingRope);
    gemma4_rope_table_free(&fullRope);
    unsafe { free((void*)cacheLens); }

    bi = 0UL;
    unsafe {
        while (bi < batchSize) {
            caches[bi]->cachedLen = caches[bi]->cachedLen + 1UL;
            bi = bi + 1UL;
        }
    }

    struct Gemma4Bf16Tensor finalNormed = gemma4_bf16_rmsnorm_rows(&h, &model.finalNormW, model.eps);
    gemma4_bf16_free(&h);

    struct Gemma4Bf16Tensor rawLogitsBf16 = gemma4_bf16_linear(&finalNormed, &model.embedTokensW);
    gemma4_bf16_free(&finalNormed);
    struct Gemma4Bf16Tensor softcappedBf16 = gemma4_bf16_softcap(&rawLogitsBf16, model.finalLogitSoftcap);
    gemma4_bf16_free(&rawLogitsBf16);
    FloatTensor* logits2 = gemma4_bf16_to_f32(&softcappedBf16);
    gemma4_bf16_free(&softcappedBf16);

    unsafe { return logits2; }
}

} // namespace std
