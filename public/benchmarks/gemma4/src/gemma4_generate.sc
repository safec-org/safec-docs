// SafeC Standard Library — Gemma 4 generation loop implementation (see
// gemma4_generate.h).
#pragma once
#include "gemma4_generate.h"
#include <std/math.h>
#include <std/sys.h>

namespace std {

// Greedy argmax over row 'row' of 'logits' ([rows, vocabSize]).
static int __g4g_argmax(const &FloatTensor logits, unsigned long row) {
    unsigned long cols;
    unsafe { cols = logits->shape[1]; }
    int bestId = 0;
    float bestVal;
    unsafe { bestVal = logits->data[row * cols]; }
    unsigned long c = 1UL;
    unsafe {
        while (c < cols) {
            float v = logits->data[row * cols + c];
            if (v > bestVal) { bestVal = v; bestId = (int)c; }
            c = c + 1UL;
        }
    }
    return bestId;
}

// softmax(logits[row]/temperature) then sample via cumulative distribution.
// Two passes over the vocab (max-then-sum for a numerically stable
// softmax, same pattern as gemma4_attention.sc's per-head softmax) plus a
// third for the cumulative sample -- fine for a one-token-per-call
// decode step, not a hot inner loop the way attention's score loop is.
static int __g4g_sample(const &FloatTensor logits, unsigned long row, float temperature) {
    unsigned long cols;
    unsafe { cols = logits->shape[1]; }

    float maxV;
    unsafe { maxV = logits->data[row * cols] / temperature; }
    unsigned long c = 1UL;
    unsafe {
        while (c < cols) {
            float v = logits->data[row * cols + c] / temperature;
            if (v > maxV) { maxV = v; }
            c = c + 1UL;
        }
    }

    float sumExp = (float)0.0;
    c = 0UL;
    unsafe {
        while (c < cols) {
            sumExp = sumExp + exp_f(logits->data[row * cols + c] / temperature - maxV);
            c = c + 1UL;
        }
    }

    float target = ((float)sys_rand() / (float)RAND_MAX) * sumExp;
    float cum = (float)0.0;
    c = 0UL;
    unsafe {
        while (c < cols) {
            cum = cum + exp_f(logits->data[row * cols + c] / temperature - maxV);
            if (cum >= target) { return (int)c; }
            c = c + 1UL;
        }
    }
    return (int)(cols - 1UL); // fallback: float rounding could theoretically undershoot target by an epsilon
}

unsigned long gemma4_bf16_generate(&Gemma4Bf16Model model, const int* promptIds, unsigned long promptCount,
                                     struct Gemma4GenConfig cfg, void* ctx, Gemma4TokenCallback onToken) {
    struct Gemma4Bf16KvCache cache = gemma4_bf16_kvcache_new(model);

    &FloatTensor promptLogits = gemma4_bf16_model_forward(model, promptIds, promptCount, &cache);
    unsigned long promptLastRow;
    unsafe { promptLastRow = promptLogits->shape[0] - 1UL; }

    int nextId;
    if (cfg.temperature <= (float)0.0) {
        nextId = __g4g_argmax(promptLogits, promptLastRow);
    } else {
        nextId = __g4g_sample(promptLogits, promptLastRow, cfg.temperature);
    }
    promptLogits.free();

    unsigned long generated = 0UL;
    while (generated < cfg.maxNewTokens) {
        if (nextId == cfg.eosId) { break; }

        unsafe { onToken(ctx, nextId); }
        generated = generated + 1UL;
        if (generated >= cfg.maxNewTokens) { break; }

        int stepIds[1];
        unsafe { stepIds[0] = nextId; }
        &FloatTensor stepLogits = gemma4_bf16_model_forward(model, stepIds, 1UL, &cache);

        if (cfg.temperature <= (float)0.0) {
            nextId = __g4g_argmax(stepLogits, 0UL);
        } else {
            nextId = __g4g_sample(stepLogits, 0UL, cfg.temperature);
        }
        stepLogits.free();
    }

    gemma4_bf16_kvcache_free(&cache);
    return generated;
}

void gemma4_bf16_generate_batch(&Gemma4Bf16Model model, const struct Gemma4BatchPrompt* prompts, unsigned long batchSize,
                                  struct Gemma4GenConfig cfg, void* ctx, Gemma4BatchTokenCallback onToken,
                                  unsigned long* outGenerated) {
    struct Gemma4Bf16KvCache* caches;
    unsafe { caches = (struct Gemma4Bf16KvCache*)malloc(sizeof(struct Gemma4Bf16KvCache) * batchSize); }
    struct Gemma4Bf16KvCache** cachePtrs;
    unsafe { cachePtrs = (struct Gemma4Bf16KvCache**)malloc(sizeof(struct Gemma4Bf16KvCache*) * batchSize); }
    int* nextIds;
    unsafe { nextIds = (int*)malloc(sizeof(int) * batchSize); }
    int* finished;
    unsafe { finished = (int*)malloc(sizeof(int) * batchSize); }

    unsigned long b = 0UL;
    while (b < batchSize) {
        unsafe {
            caches[b] = gemma4_bf16_kvcache_new(model);
            cachePtrs[b] = caches + b;
            outGenerated[b] = 0UL;
            finished[b] = 0;
        }

        &FloatTensor promptLogits;
        unsafe { promptLogits = gemma4_bf16_model_forward(model, prompts[b].ids, prompts[b].count, &caches[b]); }
        unsigned long lastRow;
        unsafe { lastRow = promptLogits->shape[0] - 1UL; }
        int nid;
        if (cfg.temperature <= (float)0.0) { nid = __g4g_argmax(promptLogits, lastRow); }
        else { nid = __g4g_sample(promptLogits, lastRow, cfg.temperature); }
        promptLogits.free();
        unsafe {
            nextIds[b] = nid;
            if (nid == cfg.eosId) { finished[b] = 1; }
        }
        b = b + 1UL;
    }

    unsigned long step = 0UL;
    while (step < cfg.maxNewTokens) {
        int allDone = 1;
        b = 0UL;
        unsafe {
            while (b < batchSize) {
                if (!finished[b]) { allDone = 0; }
                b = b + 1UL;
            }
        }
        if (allDone) { break; }

        b = 0UL;
        unsafe {
            while (b < batchSize) {
                if (!finished[b]) {
                    onToken(ctx, b, nextIds[b]);
                    outGenerated[b] = outGenerated[b] + 1UL;
                    if (outGenerated[b] >= cfg.maxNewTokens) { finished[b] = 1; }
                }
                b = b + 1UL;
            }
        }

        allDone = 1;
        b = 0UL;
        unsafe {
            while (b < batchSize) {
                if (!finished[b]) { allDone = 0; }
                b = b + 1UL;
            }
        }
        if (allDone) { break; }

        int* stepIds;
        unsafe { stepIds = (int*)malloc(sizeof(int) * batchSize); }
        b = 0UL;
        unsafe {
            while (b < batchSize) {
                stepIds[b] = nextIds[b];
                b = b + 1UL;
            }
        }

        &FloatTensor stepLogits = gemma4_bf16_model_forward_batch(model, stepIds, batchSize, cachePtrs);
        unsafe { free((void*)stepIds); }

        b = 0UL;
        unsafe {
            while (b < batchSize) {
                int nid;
                if (cfg.temperature <= (float)0.0) { nid = __g4g_argmax(stepLogits, b); }
                else { nid = __g4g_sample(stepLogits, b, cfg.temperature); }
                nextIds[b] = nid;
                if (nid == cfg.eosId) { finished[b] = 1; }
                b = b + 1UL;
            }
        }
        stepLogits.free();

        step = step + 1UL;
    }

    b = 0UL;
    unsafe {
        while (b < batchSize) {
            gemma4_bf16_kvcache_free(&caches[b]);
            b = b + 1UL;
        }
        free((void*)caches);
        free((void*)cachePtrs);
        free((void*)nextIds);
        free((void*)finished);
    }
}

// ── Speculative decoding (prompt-lookup drafting) ────────────────────────
#define G4SPEC_MAX_DRAFT 8UL
#define G4SPEC_MATCH_LEN 3UL

// Searches 'history[0..histLen)' for the most recent EARLIER, NON-
// OVERLAPPING occurrence of its own trailing G4SPEC_MATCH_LEN tokens, and
// if found, copies up to 'maxDraft' tokens that followed that earlier
// occurrence into 'outDraft'. Returns the number of tokens copied (0 = no
// repeat found -- caller falls back to ordinary single-token decoding for
// this step). This is the entire "drafting" step of prompt-lookup
// decoding: no model call, no second model, just an O(histLen) scan --
// any garbage match it might propose costs nothing beyond one wasted
// verify call, since gemma4_bf16_generate_speculative always checks every
// drafted token against the real model before accepting it.
static unsigned long __g4spec_prompt_lookup(const int* history, unsigned long histLen,
                                              int* outDraft, unsigned long maxDraft) {
    unsigned long matchLen = G4SPEC_MATCH_LEN;
    if (histLen < matchLen) { return 0UL; }
    unsigned long trailStart = histLen - matchLen;
    if (trailStart < matchLen) { return 0UL; } // not enough earlier history for a non-overlapping match

    unsigned long i = trailStart - matchLen;
    int searching = 1;
    while (searching) {
        int matches = 1;
        unsafe {
            unsigned long k = 0UL;
            while (k < matchLen) {
                if (history[i + k] != history[trailStart + k]) { matches = 0; break; }
                k = k + 1UL;
            }
        }
        if (matches) {
            unsigned long avail = histLen - (i + matchLen);
            unsigned long n = avail;
            if (n > maxDraft) { n = maxDraft; }
            unsafe {
                unsigned long d = 0UL;
                while (d < n) { outDraft[d] = history[i + matchLen + d]; d = d + 1UL; }
            }
            return n;
        }
        if (i == 0UL) { searching = 0; } else { i = i - 1UL; }
    }
    return 0UL;
}

unsigned long gemma4_bf16_generate_speculative(&Gemma4Bf16Model model, const int* promptIds, unsigned long promptCount,
                                                 struct Gemma4GenConfig cfg, void* ctx, Gemma4TokenCallback onToken) {
    struct Gemma4Bf16KvCache cache = gemma4_bf16_kvcache_new(model);

    // Full accepted-token history (prompt + every token actually emitted
    // so far -- rejected/rolled-back draft tokens never get appended
    // here), searched by __g4spec_prompt_lookup above. Sized generously:
    // at most cfg.maxNewTokens real tokens are ever appended regardless
    // of how many draft attempts happen.
    unsigned long histCap = promptCount + cfg.maxNewTokens + 1UL;
    int* history;
    unsafe { history = (int*)malloc(sizeof(int) * histCap); }
    unsigned long histLen = 0UL;
    unsafe {
        unsigned long i = 0UL;
        while (i < promptCount) { history[histLen] = promptIds[i]; histLen = histLen + 1UL; i = i + 1UL; }
    }

    &FloatTensor promptLogits = gemma4_bf16_model_forward(model, promptIds, promptCount, &cache);
    unsigned long promptLastRow;
    unsafe { promptLastRow = promptLogits->shape[0] - 1UL; }
    int nextId;
    if (cfg.temperature <= (float)0.0) { nextId = __g4g_argmax(promptLogits, promptLastRow); }
    else { nextId = __g4g_sample(promptLogits, promptLastRow, cfg.temperature); }
    promptLogits.free();

    unsigned long generated = 0UL;
    int draft[G4SPEC_MAX_DRAFT];
    while (generated < cfg.maxNewTokens) {
        if (nextId == cfg.eosId) { break; }

        unsafe { onToken(ctx, nextId); }
        generated = generated + 1UL;
        unsafe { history[histLen] = nextId; }
        histLen = histLen + 1UL;
        if (generated >= cfg.maxNewTokens) { break; }

        unsafe { draft[0] = nextId; }
        unsigned long draftLen = 1UL;

        // Only draft beyond the already-known-correct draft[0] for
        // greedy decoding -- see this function's own header comment
        // (gemma4_generate.h) on why sampling can't use exact-match
        // acceptance the same way.
        if (cfg.temperature <= (float)0.0) {
            unsigned long maxExtra = cfg.maxNewTokens - generated;
            if (maxExtra > G4SPEC_MAX_DRAFT - 1UL) { maxExtra = G4SPEC_MAX_DRAFT - 1UL; }
            if (maxExtra > 0UL) {
                int extra[G4SPEC_MAX_DRAFT];
                unsigned long n = __g4spec_prompt_lookup(history, histLen, extra, maxExtra);
                unsafe {
                    unsigned long d = 0UL;
                    while (d < n) { draft[1UL + d] = extra[d]; d = d + 1UL; }
                }
                draftLen = 1UL + n;
            }
        }

        if (draftLen == 1UL) {
            int stepIds[1];
            unsafe { stepIds[0] = nextId; }
            &FloatTensor stepLogits = gemma4_bf16_model_forward(model, stepIds, 1UL, &cache);
            if (cfg.temperature <= (float)0.0) { nextId = __g4g_argmax(stepLogits, 0UL); }
            else { nextId = __g4g_sample(stepLogits, 0UL, cfg.temperature); }
            stepLogits.free();
            continue;
        }

        // Verify the WHOLE draft (including draft[0], already known
        // correct -- keeping it in keeps row indexing simple: row i's
        // logits are the model's real prediction for what should follow
        // draft[0..i]) in one multi-token forward call -- exactly
        // gemma4_bf16_model_forward's existing prefill path, unchanged.
        unsigned long cacheLenBeforeVerify = cache.cachedLen;
        &FloatTensor verifyLogits = gemma4_bf16_model_forward(model, draft, draftLen, &cache);

        unsigned long accepted = 1UL; // draft[0] already verified-by-construction, already emitted above
        int newNextId = nextId;
        int hitEos = 0;
        while (accepted < draftLen) {
            int predicted = __g4g_argmax(verifyLogits, accepted - 1UL);
            int wantNext;
            unsafe { wantNext = draft[accepted]; }
            if (predicted != wantNext) {
                newNextId = predicted;
                break;
            }
            if (wantNext == cfg.eosId) {
                // This drafted token itself IS eos -- must NOT be
                // accepted/passed to onToken (same contract
                // gemma4_bf16_generate's own top-of-loop eos check
                // enforces). Stop drafting right here; the rollback
                // below removes it (and anything drafted after it) from
                // the cache, and 'hitEos' stops the outer loop too.
                hitEos = 1;
                break;
            }
            unsafe { onToken(ctx, wantNext); }
            unsafe { history[histLen] = wantNext; }
            histLen = histLen + 1UL;
            generated = generated + 1UL;
            accepted = accepted + 1UL;
            if (generated >= cfg.maxNewTokens) { break; }
        }
        if (!hitEos && accepted == draftLen) {
            // Every drafted token verified correct -- the model's own
            // prediction after the last accepted token becomes the next
            // one to try (NOT re-derived from a fresh forward call; it's
            // already sitting in this same verify pass's last row).
            newNextId = __g4g_argmax(verifyLogits, draftLen - 1UL);
        }
        verifyLogits.free();

        if (accepted < draftLen) {
            // Roll back whatever the verify call appended past the
            // accepted prefix -- it appended ALL draftLen tokens to
            // every layer unconditionally (gemma4_bf16_model_forward has
            // no way to know in advance how many will verify correctly).
            gemma4_bf16_kvcache_truncate(&cache, cacheLenBeforeVerify + accepted);
        }
        if (hitEos) { break; }
        nextId = newNextId;
    }

    unsafe { free((void*)history); }
    gemma4_bf16_kvcache_free(&cache);
    return generated;
}

} // namespace std
