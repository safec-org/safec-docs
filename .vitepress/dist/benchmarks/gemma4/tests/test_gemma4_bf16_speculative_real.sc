// Speculative (prompt-lookup) decoding against the REAL checkpoint --
// this project's own standard benchmark prompt ("The capital of France
// is") produces a HIGHLY repetitive continuation once greedy decoding
// starts looping ("Paris.\n\nThe capital of France is Paris. ..."),
// exactly the shape prompt-lookup decoding is designed to exploit: once
// the loop establishes, every subsequent repeat of the cycle should be
// draftable and verifiable several tokens at a time instead of one
// decode step per token. Confirms exact output match against plain
// gemma4_bf16_generate AND reports the wall-clock difference.
extern int printf(const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
#include "../src/gemma4_generate.h"
#include "../src/gemma4_tokenizer.h"
#include <std/sys.h>
#include <std/time.h>

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
    Autograd::get().set_enabled(0);
    std::sys_srand(7U);

    struct Gemma4Tokenizer tok = std::gemma4_tok_load(
        "/Users/minjaekim/SafeC/safec-docs/public/benchmarks/gemma4/vocab.bin",
        "/Users/minjaekim/SafeC/safec-docs/public/benchmarks/gemma4/merges.bin"
    );
    struct Gemma4Bf16Model model = std::gemma4_bf16_model_load("/Users/minjaekim/SafeC/models/gemma4-e2b/model.safetensors");
    printf("loaded.\n\n");

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

    struct Gemma4GenConfig cfg;
    cfg.maxNewTokens = 40UL; // longer than the earlier 20-token benchmark specifically to let the repeat loop establish and give the drafter real room to work
    cfg.temperature = (float)0.0;
    cfg.eosId = tok.eosId;

    struct CollectCtx ctxPlain;
    ctxPlain.count = 0UL;
    long long t0 = std::time_mono_ns();
    unsigned long nPlain;
    unsafe { nPlain = std::gemma4_bf16_generate(&model, promptIds, promptLen + 1UL, cfg, (void*)&ctxPlain, collect_token); }
    long long t1 = std::time_mono_ns();
    double plainMs = std::time_ns_to_ms(std::time_elapsed_ns(t0, t1));

    struct CollectCtx ctxSpec;
    ctxSpec.count = 0UL;
    long long t2 = std::time_mono_ns();
    unsigned long nSpec;
    unsafe { nSpec = std::gemma4_bf16_generate_speculative(&model, promptIds, promptLen + 1UL, cfg, (void*)&ctxSpec, collect_token); }
    long long t3 = std::time_mono_ns();
    double specMs = std::time_ns_to_ms(std::time_elapsed_ns(t2, t3));

    int identical = 1;
    if (nPlain != nSpec) { identical = 0; }
    unsigned long i = 0UL;
    unsafe {
        while (i < nPlain && i < nSpec) {
            if (ctxPlain.ids[i] != ctxSpec.ids[i]) { identical = 0; }
            i = i + 1UL;
        }
    }

    printf("plain:       %lu tokens, %f s (%f ms/token)\n", nPlain, plainMs / 1000.0, plainMs / (double)nPlain);
    printf("speculative: %lu tokens, %f s (%f ms/token effective)\n", nSpec, specMs / 1000.0, specMs / (double)nSpec);
    printf("speedup: %fx\n", plainMs / specMs);
    printf("output bit-identical (expect 1): %d\n", identical);

    struct String textPlain = std::gemma4_tok_decode(&tok, (const int*)ctxPlain.ids, nPlain);
    unsafe { printf("\ndecoded: \"%s%s\"\n", prompt, textPlain.as_ptr()); }
    textPlain.free();

    unsafe { free((void*)promptIds); }
    std::gemma4_tok_free(&tok);
    std::gemma4_bf16_model_free(&model);
    printf("DONE\n");
    return 0;
}
