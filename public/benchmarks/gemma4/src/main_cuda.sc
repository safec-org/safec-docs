// gemma4 on CUDA — greedy text generation with google/gemma-4-E2B on an NVIDIA GPU
// (see gemma4_cuda.h). Streams the continuation of a prompt as it is generated, then
// times a second, non-streaming run of the same length (every step queued back to
// back, tokens read once at the end) and prints the top-5 next tokens of the prompt.
//
// Usage: gemma4_cuda [checkpointPath] [vocabBinPath] [mergesBinPath] [prompt] [newTokens]
extern int printf(const char* fmt, ...);
extern int fflush(void* f);
extern unsigned long strtoul(const char* s, char** endptr, int base);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
#include "gemma4_cuda.h"
#include "gemma4_tokenizer.h"

struct StreamCtx {
    &Gemma4Tokenizer tok;
};

static void print_token(void* ctxRaw, int id) {
    unsafe {
        struct StreamCtx* ctx = (struct StreamCtx*)ctxRaw;
        int ids[1];
        ids[0] = id;
        struct String piece = std::gemma4_tok_decode(ctx->tok, &ids[0], 1UL);
        printf("%s", piece.as_ptr());
        fflush((void*)0);
        piece.free();
    }
}

int main(int argc, char** argv) {
    const char* checkpointPath = "model.safetensors";
    const char* vocabPath = "vocab.bin";
    const char* mergesPath = "merges.bin";
    const char* prompt = "The capital of France is";
    unsigned long nNew = 64UL;
    unsafe {
        if (argc > 1) { checkpointPath = argv[1]; }
        if (argc > 2) { vocabPath = argv[2]; }
        if (argc > 3) { mergesPath = argv[3]; }
        if (argc > 4) { prompt = argv[4]; }
        if (argc > 5) { nNew = strtoul(argv[5], (char**)0, 10); }
    }

    struct Gemma4Tokenizer tok = std::gemma4_tok_load(vocabPath, mergesPath);
    struct Vec<int> encoded = std::gemma4_tok_encode(&tok, prompt);
    unsigned long promptLen = encoded.length() + 1UL;
    int* ids;
    int* out;
    float* logits;
    unsafe {
        ids = (int*)malloc(sizeof(int) * promptLen);
        out = (int*)malloc(sizeof(int) * (nNew + 1UL));
        logits = (float*)malloc(sizeof(float) * 262144UL);
        ids[0] = tok.bosId;
        for (unsigned long i = 1UL; i < promptLen; i = i + 1UL) ids[i] = *encoded.at(i - 1UL);
        printf("prompt ids:");
        for (unsigned long i = 0UL; i < promptLen; i = i + 1UL) printf(" %d", ids[i]);
        printf("\n");
    }
    encoded.free();

    struct Gemma4Cuda model = std::gemma4_cuda_load(checkpointPath, 4096UL);
    if (!model.ok) { unsafe { printf("load failed\n"); } return 1; }
    unsafe { printf("loaded in %.1fs (%s)\n\n%s", model.loadSeconds, (const char*)std::cuda_device_name(), prompt); }

    struct StreamCtx ctx;
    ctx.tok = &tok;
    struct Gemma4CudaTiming t;
    unsigned long got = 0UL;
    unsafe { got = std::gemma4_cuda_generate(&model, ids, promptLen, nNew, out, 0, 1, (void*)&ctx, print_token, &t); }
    unsafe { printf("\n\nstreamed %lu tokens: prompt %.2f ms, then %.3f ms/token (one sync per token)\n", got, t.prefillMs, t.decodeSteps > 0UL ? t.decodeMs / (double)t.decodeSteps : 0.0); }

    // timed run: all steps queued, tokens read once at the end (best of 3)
    double best = 1e30;
    double bestPrefill = 0.0;
    for (int r = 0; r < 3; r = r + 1) {
        unsafe { got = std::gemma4_cuda_generate(&model, ids, promptLen, nNew, out, 0, 0, (void*)0, print_token, &t); }
        if (t.decodeSteps > 0UL && t.decodeMs / (double)t.decodeSteps < best) { best = t.decodeMs / (double)t.decodeSteps; bestPrefill = t.prefillMs; }
    }
    unsafe {
        printf("timed: %lu tokens, prompt (%lu tokens) %.2f ms, decode %.3f ms/token (%.0f tokens/s)\n", got, promptLen, bestPrefill, best, 1000.0 / best);
        printf("ids:");
        for (unsigned long i = 0UL; i < got; i = i + 1UL) printf(" %d", out[i]);
        printf("\n");
    }

    if (std::gemma4_cuda_prompt_logits(&model, ids, promptLen, logits)) {
        unsafe {
            printf("top-5 next tokens:");
            int taken[5];
            for (int k = 0; k < 5; k = k + 1) {
                int bi = -1;
                for (int i = 0; i < 262144; i = i + 1) {
                    int used = 0;
                    for (int j = 0; j < k; j = j + 1) if (taken[j] == i) used = 1;
                    if (!used && (bi < 0 || logits[i] > logits[bi])) bi = i;
                }
                taken[k] = bi;
                printf(" %d (%.4f)", bi, (double)logits[bi]);
            }
            printf("\n");
        }
    }
    std::gemma4_cuda_free(&model);
    unsafe { free((void*)ids); free((void*)out); free((void*)logits); }
    return 0;
}
