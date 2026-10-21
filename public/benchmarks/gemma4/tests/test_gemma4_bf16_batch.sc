// Batched decode correctness: gemma4_bf16_generate_batch's per-sequence
// results must exactly match what gemma4_bf16_generate produces for that
// SAME prompt run alone -- sequences in a batch must never see each
// other's tokens or KV cache (see gemma4_model_bf16.h's
// gemma4_bf16_model_forward_batch header comment on why: attention loops
// per-row over each sequence's own cache, unchanged from the single-
// sequence path). eosId is set to an impossible token id so every
// sequence runs the full maxNewTokens, keeping the comparison a simple
// fixed-length array match.
extern int printf(const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
#include "../src/gemma4_generate.h"

#define MAX_NEW_TOKENS 5UL

struct SingleCtx {
    int* buf;
    unsigned long count;
};

static void single_token_cb(void* ctxRaw, int tokenId) {
    struct SingleCtx* c;
    unsafe {
        c = (struct SingleCtx*)ctxRaw;
        c->buf[c->count] = tokenId;
        c->count = c->count + 1UL;
    }
}

struct BatchCtx {
    int* buf;             // [batchSize * MAX_NEW_TOKENS]
    unsigned long* counts; // [batchSize]
};

static void batch_token_cb(void* ctxRaw, unsigned long seqIdx, int tokenId) {
    struct BatchCtx* c;
    unsafe {
        c = (struct BatchCtx*)ctxRaw;
        c->buf[seqIdx * MAX_NEW_TOKENS + c->counts[seqIdx]] = tokenId;
        c->counts[seqIdx] = c->counts[seqIdx] + 1UL;
    }
}

int main() {
    Autograd::get().set_enabled(0);

    struct Gemma4Bf16Model model = std::gemma4_bf16_model_load("/tmp/gemma4_synthetic.safetensors");

    unsigned long batchSize = 3UL;
    int prompt0[3]; unsafe { prompt0[0]=1; prompt0[1]=5; prompt0[2]=12; }
    int prompt1[2]; unsafe { prompt1[0]=3; prompt1[1]=7; }
    int prompt2[4]; unsafe { prompt2[0]=20; prompt2[1]=21; prompt2[2]=22; prompt2[3]=1; }

    struct Gemma4GenConfig cfg;
    cfg.maxNewTokens = MAX_NEW_TOKENS;
    cfg.temperature = (float)0.0; // greedy
    cfg.eosId = -1;               // impossible token id -- never stop early

    // ── Baseline: each prompt run alone through the single-sequence path ──
    int baseline[3][MAX_NEW_TOKENS];
    unsigned long baselineCount[3];

    struct SingleCtx sctx0;
    unsafe { sctx0.buf = (int*)malloc(sizeof(int) * MAX_NEW_TOKENS); }
    sctx0.count = 0UL;
    unsigned long n0;
    unsafe { n0 = std::gemma4_bf16_generate(&model, (const int*)prompt0, 3UL, cfg, (void*)&sctx0, single_token_cb); }
    baselineCount[0] = n0;
    unsafe {
        unsigned long i = 0UL;
        while (i < n0) { baseline[0][i] = sctx0.buf[i]; i = i + 1UL; }
        free((void*)sctx0.buf);
    }

    struct SingleCtx sctx1;
    unsafe { sctx1.buf = (int*)malloc(sizeof(int) * MAX_NEW_TOKENS); }
    sctx1.count = 0UL;
    unsigned long n1;
    unsafe { n1 = std::gemma4_bf16_generate(&model, (const int*)prompt1, 2UL, cfg, (void*)&sctx1, single_token_cb); }
    baselineCount[1] = n1;
    unsafe {
        unsigned long i = 0UL;
        while (i < n1) { baseline[1][i] = sctx1.buf[i]; i = i + 1UL; }
        free((void*)sctx1.buf);
    }

    struct SingleCtx sctx2;
    unsafe { sctx2.buf = (int*)malloc(sizeof(int) * MAX_NEW_TOKENS); }
    sctx2.count = 0UL;
    unsigned long n2;
    unsafe { n2 = std::gemma4_bf16_generate(&model, (const int*)prompt2, 4UL, cfg, (void*)&sctx2, single_token_cb); }
    baselineCount[2] = n2;
    unsafe {
        unsigned long i = 0UL;
        while (i < n2) { baseline[2][i] = sctx2.buf[i]; i = i + 1UL; }
        free((void*)sctx2.buf);
    }

    printf("baseline counts (expect %lu each): %lu %lu %lu\n", MAX_NEW_TOKENS, baselineCount[0], baselineCount[1], baselineCount[2]);

    // ── Same three prompts, run TOGETHER through the batched path ─────────
    struct Gemma4BatchPrompt prompts[3];
    unsafe {
        prompts[0].ids = (const int*)prompt0; prompts[0].count = 3UL;
        prompts[1].ids = (const int*)prompt1; prompts[1].count = 2UL;
        prompts[2].ids = (const int*)prompt2; prompts[2].count = 4UL;
    }

    struct BatchCtx bctx;
    unsafe { bctx.buf = (int*)malloc(sizeof(int) * batchSize * MAX_NEW_TOKENS); }
    unsafe { bctx.counts = (unsigned long*)malloc(sizeof(unsigned long) * batchSize); }
    unsafe {
        unsigned long b = 0UL;
        while (b < batchSize) { bctx.counts[b] = 0UL; b = b + 1UL; }
    }

    unsigned long* outGenerated;
    unsafe { outGenerated = (unsigned long*)malloc(sizeof(unsigned long) * batchSize); }

    unsafe { std::gemma4_bf16_generate_batch(&model, (const struct Gemma4BatchPrompt*)prompts, batchSize, cfg, (void*)&bctx, batch_token_cb, outGenerated); }

    unsafe {
        printf("batch counts (expect %lu each): %lu %lu %lu\n", MAX_NEW_TOKENS, outGenerated[0], outGenerated[1], outGenerated[2]);
    }

    int allMatch = 1;
    unsafe {
        unsigned long b = 0UL;
        while (b < batchSize) {
            if (outGenerated[b] != baselineCount[b]) { allMatch = 0; }
            unsigned long i = 0UL;
            while (i < MAX_NEW_TOKENS) {
                if (bctx.buf[b * MAX_NEW_TOKENS + i] != baseline[b][i]) { allMatch = 0; }
                i = i + 1UL;
            }
            b = b + 1UL;
        }
    }
    printf("batch matches single-sequence baseline exactly (expect 1): %d\n", allMatch);

    unsafe {
        printf("prompt0 batch tokens: [%d, %d, %d, %d, %d]  baseline: [%d, %d, %d, %d, %d]\n",
               bctx.buf[0], bctx.buf[1], bctx.buf[2], bctx.buf[3], bctx.buf[4],
               baseline[0][0], baseline[0][1], baseline[0][2], baseline[0][3], baseline[0][4]);
    }

    unsafe {
        free((void*)bctx.buf);
        free((void*)bctx.counts);
        free((void*)outGenerated);
    }

    std::gemma4_bf16_model_free(&model);
    printf("DONE\n");
    return 0;
}
