// Speculative (prompt-lookup) decoding must be losslessly exact: for the
// SAME prompt/config, gemma4_bf16_generate_speculative's output must be
// bit-identical (same token ids, same count) to plain gemma4_bf16_generate
// -- it's a faster way to compute the same greedy decode, not a different
// one. Uses a longer maxNewTokens than this project's other synthetic
// tests specifically to raise the odds of the synthetic (arbitrary,
// non-repetitive by construction) 50-token vocab producing at least one
// incidental repeat for the prompt-lookup drafter to find and exercise
// both its accept and reject paths -- any garbage draft it proposes on a
// tiny vocab still costs nothing but a wasted verify call, since every
// drafted token is checked against the real model before being kept.
extern int printf(const char* fmt, ...);
#include "../src/gemma4_generate.h"
#include <std/sys.h>

struct CollectCtx {
    int ids[64];
    unsigned long count;
};

static void collect_token(void* ctxRaw, int tokenId) {
    struct CollectCtx* ctx;
    unsafe { ctx = (struct CollectCtx*)ctxRaw; }
    unsafe {
        ctx->ids[ctx->count] = tokenId;
        ctx->count = ctx->count + 1UL;
    }
}

int main() {
    tensor_set_grad_enabled(0);
    std::sys_srand(42U);

    struct Gemma4Bf16Model model = std::gemma4_bf16_model_load("/tmp/gemma4_synthetic.safetensors");
    int promptIds[3]; unsafe { promptIds[0]=1; promptIds[1]=5; promptIds[2]=12; }

    struct Gemma4GenConfig cfg;
    cfg.maxNewTokens = 30UL;
    cfg.temperature = (float)0.0; // greedy -- speculative acceptance requires this
    cfg.eosId = -1;               // never stop early, keep the comparison a simple fixed-length match

    printf("=== plain gemma4_bf16_generate ===\n");
    struct CollectCtx ctxPlain;
    ctxPlain.count = 0UL;
    unsigned long nPlain;
    unsafe { nPlain = std::gemma4_bf16_generate(&model, promptIds, 3UL, cfg, (void*)&ctxPlain, collect_token); }
    printf("generated %lu tokens (expect 30)\n", nPlain);

    printf("=== gemma4_bf16_generate_speculative (prompt-lookup) ===\n");
    struct CollectCtx ctxSpec;
    ctxSpec.count = 0UL;
    unsigned long nSpec;
    unsafe { nSpec = std::gemma4_bf16_generate_speculative(&model, promptIds, 3UL, cfg, (void*)&ctxSpec, collect_token); }
    printf("generated %lu tokens (expect 30)\n", nSpec);

    int identical = 1;
    if (nPlain != nSpec) { identical = 0; }
    unsigned long i = 0UL;
    unsafe {
        while (i < nPlain && i < nSpec) {
            if (ctxPlain.ids[i] != ctxSpec.ids[i]) { identical = 0; }
            i = i + 1UL;
        }
    }
    printf("speculative output bit-identical to plain greedy decode (expect 1): %d\n", identical);

    unsafe {
        printf("plain:       [");
        i = 0UL;
        while (i < nPlain) { printf("%d ", ctxPlain.ids[i]); i = i + 1UL; }
        printf("]\n");
        printf("speculative: [");
        i = 0UL;
        while (i < nSpec) { printf("%d ", ctxSpec.ids[i]); i = i + 1UL; }
        printf("]\n");
    }

    // Also check a short run (maxNewTokens smaller than the match window)
    // exercises the "never drafts, always falls back to single-token
    // decode" path cleanly -- this is what test_gemma4_generate.sc's own
    // maxNewTokens=5 case already looks like, so a mismatch here would
    // mean the fallback path itself regressed, not just the speculative
    // acceptance logic.
    struct Gemma4GenConfig cfgShort;
    cfgShort.maxNewTokens = 5UL;
    cfgShort.temperature = (float)0.0;
    cfgShort.eosId = -1;
    struct CollectCtx ctxShortPlain; ctxShortPlain.count = 0UL;
    struct CollectCtx ctxShortSpec; ctxShortSpec.count = 0UL;
    unsigned long nShortPlain;
    unsigned long nShortSpec;
    unsafe {
        nShortPlain = std::gemma4_bf16_generate(&model, promptIds, 3UL, cfgShort, (void*)&ctxShortPlain, collect_token);
        nShortSpec = std::gemma4_bf16_generate_speculative(&model, promptIds, 3UL, cfgShort, (void*)&ctxShortSpec, collect_token);
    }
    int shortIdentical = 1;
    if (nShortPlain != nShortSpec) { shortIdentical = 0; }
    i = 0UL;
    unsafe {
        while (i < nShortPlain && i < nShortSpec) {
            if (ctxShortPlain.ids[i] != ctxShortSpec.ids[i]) { shortIdentical = 0; }
            i = i + 1UL;
        }
    }
    printf("short-run (maxNewTokens=5, expect 1): %d\n", shortIdentical);

    // Exhaustive eosId sweep: a drafted (not-yet-accepted) token that
    // happens to equal cfg.eosId must NOT be passed to onToken and must
    // NOT be left in the KV cache (see gemma4_generate.h's contract:
    // "stop early if cfg.eosId is generated, NOT passed to onToken") --
    // easy to get wrong specifically for the multi-token verify/accept
    // loop (a real bug caught this way during development: the loop
    // happily accepted a drafted eos token before this fix). Rather than
    // hand-construct the exact history/draft state where this triggers,
    // sweep every possible token id as eosId against the SAME prompt/
    // config used above -- the synthetic vocab is only 50 tokens and the
    // plain run's own output (printed above) repeats several of them at
    // different positions, so this sweep is very likely to hit the
    // mid-draft-eos case for at least one value without needing to know
    // in advance which one.
    int allEosOk = 1;
    int eosVal = 0;
    while (eosVal < 50) {
        struct Gemma4GenConfig cfgEos;
        cfgEos.maxNewTokens = 30UL;
        cfgEos.temperature = (float)0.0;
        cfgEos.eosId = eosVal;
        struct CollectCtx ctxEosPlain; ctxEosPlain.count = 0UL;
        struct CollectCtx ctxEosSpec; ctxEosSpec.count = 0UL;
        unsigned long nEosPlain;
        unsigned long nEosSpec;
        unsafe {
            nEosPlain = std::gemma4_bf16_generate(&model, promptIds, 3UL, cfgEos, (void*)&ctxEosPlain, collect_token);
            nEosSpec = std::gemma4_bf16_generate_speculative(&model, promptIds, 3UL, cfgEos, (void*)&ctxEosSpec, collect_token);
        }
        if (nEosPlain != nEosSpec) { allEosOk = 0; }
        unsigned long j = 0UL;
        unsafe {
            while (j < nEosPlain && j < nEosSpec) {
                if (ctxEosPlain.ids[j] != ctxEosSpec.ids[j]) { allEosOk = 0; }
                j = j + 1UL;
            }
            // Neither output may ever contain eosVal itself.
            j = 0UL;
            while (j < nEosPlain) { if (ctxEosPlain.ids[j] == eosVal) { allEosOk = 0; } j = j + 1UL; }
            j = 0UL;
            while (j < nEosSpec) { if (ctxEosSpec.ids[j] == eosVal) { allEosOk = 0; } j = j + 1UL; }
        }
        eosVal = eosVal + 1;
    }
    printf("eosId sweep (0..49), all match + never emit eos (expect 1): %d\n", allEosOk);

    std::gemma4_bf16_model_free(&model);
    printf("DONE\n");
    return 0;
}
