// Batched decode against the REAL checkpoint, real headDim (256/512) and
// real cache lengths -- exercises gemma4_bf16_attn_batch_gpu's real Metal
// kernel path at production scale (the synthetic-checkpoint batch test
// only ever sees a tiny headDim=4/6), decoding text so a human/CI can
// eyeball it against the known-correct single-sequence output ("Paris."
// + the expected greedy-repeat loop, same as every other real-checkpoint
// test in this project).
extern int printf(const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
#include "../src/gemma4_generate.h"
#include "../src/gemma4_tokenizer.h"
#include <std/sys.h>
#include <std/time.h>

struct BatchStreamCtx {
    &Gemma4Tokenizer tok;
};

static void batch_stream_token(void* ctxRaw, unsigned long seqIdx, int tokenId) {
    struct BatchStreamCtx* ctx;
    unsafe { ctx = (struct BatchStreamCtx*)ctxRaw; }
    int ids[1];
    struct String piece;
    unsafe {
        ids[0] = tokenId;
        piece = std::gemma4_tok_decode(ctx->tok, ids, 1UL);
    }
    unsafe { printf("[seq %lu] %s", seqIdx, piece.as_ptr()); }
    piece.free();
}

int main() {
    Autograd::get().set_enabled(0);
    std::sys_srand(7U);

    struct Gemma4Tokenizer tok = std::gemma4_tok_load(
        "/Users/minjaekim/SafeC/safec-docs/public/benchmarks/gemma4/vocab.bin",
        "/Users/minjaekim/SafeC/safec-docs/public/benchmarks/gemma4/merges.bin"
    );
    struct Gemma4Bf16Model model = std::gemma4_bf16_model_load("/Users/minjaekim/SafeC/models/gemma4-e2b/model.safetensors");
    printf("loaded.\n\n");

    // 8, not e.g. 3: a batch size that's an exact multiple of 8 exercises
    // gpu_mps.sc's hardware simdgroup_matrix bf16 matmul path
    // (matmul_abt_kernel_bf16_smma), which only activates when every
    // matmul dimension -- including this batch size, since it's the
    // M dimension of every per-layer projection matmul -- is 8-aligned;
    // a non-aligned batch size would only ever exercise the naive
    // fallback kernel and silently miss bugs specific to the fast path.
    unsigned long batchSize = 8UL;
    const char* prompt = "The capital of France is";
    struct Vec<int> encoded = std::gemma4_tok_encode(&tok, prompt);
    unsigned long promptLen = encoded.length();
    int* promptIds;
    unsafe { promptIds = (int*)malloc(sizeof(int) * (promptLen + 1UL)); }
    unsafe {
        promptIds[0] = tok.bosId;
        unsigned long i = 0UL;
        while (i < promptLen) {
            promptIds[i + 1UL] = *encoded.at(i);
            i = i + 1UL;
        }
    }
    encoded.free();

    struct Gemma4BatchPrompt prompts[8];
    unsafe {
        unsigned long pi = 0UL;
        while (pi < batchSize) {
            prompts[pi].ids = (const int*)promptIds; prompts[pi].count = promptLen + 1UL;
            pi = pi + 1UL;
        }
    }

    struct BatchStreamCtx ctx;
    ctx.tok = &tok;
    struct Gemma4GenConfig cfg;
    cfg.maxNewTokens = 20UL;
    cfg.temperature = (float)0.0;
    cfg.eosId = tok.eosId;

    unsigned long* outGenerated;
    unsafe { outGenerated = (unsigned long*)malloc(sizeof(unsigned long) * batchSize); }

    printf("expect all 8 identical: \" Paris.\\n\\nThe capital of France is Paris. ...\"\n\n");
    unsafe {
        std::gemma4_bf16_generate_batch(&model, (const struct Gemma4BatchPrompt*)prompts, batchSize, cfg,
                                         (void*)&ctx, batch_stream_token, outGenerated);
    }
    printf("\n\n");
    unsafe {
        unsigned long b = 0UL;
        while (b < batchSize) { printf("seq %lu generated %lu tokens\n", b, outGenerated[b]); b = b + 1UL; }
        free((void*)outGenerated);
        free((void*)promptIds);
    }

    std::gemma4_tok_free(&tok);
    std::gemma4_bf16_model_free(&model);
    printf("DONE\n");
    return 0;
}
