// Timed load + 20-token greedy generation over the real checkpoint, bf16
// throughout (weights AND activations -- see src/gemma4_bf16.h), mirroring
// bench_pytorch.py/bench_mlx.py's methodology exactly: load timed
// separately from generate(), same prompt, same 20 max_new_tokens, greedy
// (temperature=0). See DESIGN_NOTES.md's BF16 section for the numbers
// this produces.
//
// Usage: bench_safec [cpu|gpu] [batchSize]
//   cpu|gpu    Forces gemma4_bf16_linear's CPU+BLAS path (cblas_sgemm/
//              Accelerate) or leaves the default GPU path
//              (mps_matmul_abt_bf16_persistent) in place. Default: gpu.
//   batchSize  Number of IDENTICAL copies of the same prompt to decode
//              TOGETHER via gemma4_bf16_generate_batch (see
//              gemma4_model_bf16.h's gemma4_bf16_model_forward_batch for
//              the design: every matmul-shaped op batches into one call
//              across the whole batch on both backends; attention stays
//              a per-sequence loop, unaffected either way). Default: 1
//              (the original single-sequence gemma4_bf16_generate path,
//              unchanged). batchSize>1 reports AGGREGATE throughput
//              (batchSize*tokens / wall-clock seconds) alongside the
//              per-decode-step wall-clock cost, since the whole point of
//              batching is trading per-token latency for aggregate
//              throughput, not improving single-sequence latency itself.
extern int printf(const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
extern int strcmp(const char* a, const char* b);
#include "../src/gemma4_generate.h"
#include "../src/gemma4_tokenizer.h"
#include <std/sys.h>
#include <std/time.h>

static void stream_token_noop(void* ctxRaw, int tokenId) {
    // Intentionally no per-token decode/print work here -- this bench
    // measures the model's own compute cost, not string handling.
}

static void batch_stream_token_noop(void* ctxRaw, unsigned long seqIdx, int tokenId) {
}

int main(int argc, char** argv) {
    tensor_set_grad_enabled(0);
    std::sys_srand(7U);

    int forceCpu = 0;
    unsigned long batchSize = 1UL;
    unsafe {
        if (argc > 1 && strcmp(argv[1], "cpu") == 0) { forceCpu = 1; }
        if (argc > 2) { batchSize = (unsigned long)std::sys_atoi(argv[2]); }
        if (batchSize < 1UL) { batchSize = 1UL; }
    }
    std::gemma4_bf16_set_force_cpu(forceCpu);
    if (forceCpu) { printf("mode: CPU+BLAS (forced)\n"); } else { printf("mode: GPU (default, falls back to CPU+BLAS if no Metal device)\n"); }
    printf("batch size: %lu\n", batchSize);

    long long tLoad0 = std::time_mono_ns();
    struct Gemma4Tokenizer tok = std::gemma4_tok_load(
        "/Users/minjaekim/SafeC/safec-docs/public/benchmarks/gemma4/vocab.bin",
        "/Users/minjaekim/SafeC/safec-docs/public/benchmarks/gemma4/merges.bin"
    );
    struct Gemma4Bf16Model model = std::gemma4_bf16_model_load("/Users/minjaekim/SafeC/models/gemma4-e2b/model.safetensors");
    long long tLoad1 = std::time_mono_ns();
    double loadMs = std::time_ns_to_ms(std::time_elapsed_ns(tLoad0, tLoad1));
    printf("load: %f s\n", loadMs / 1000.0);

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
    cfg.maxNewTokens = 20UL;
    cfg.temperature = (float)0.0;
    cfg.eosId = tok.eosId;

    if (batchSize == 1UL) {
        long long tGen0 = std::time_mono_ns();
        unsigned long n;
        unsafe { n = std::gemma4_bf16_generate(&model, promptIds, promptLen + 1UL, cfg, (void*)0, stream_token_noop); }
        long long tGen1 = std::time_mono_ns();
        double genMs = std::time_ns_to_ms(std::time_elapsed_ns(tGen0, tGen1));
        printf("generate (%lu tokens): %f s (%f ms/token)\n", n, genMs / 1000.0, genMs / (double)n);
    } else {
        struct Gemma4BatchPrompt* prompts;
        unsafe { prompts = (struct Gemma4BatchPrompt*)malloc(sizeof(struct Gemma4BatchPrompt) * batchSize); }
        unsigned long b = 0UL;
        unsafe {
            while (b < batchSize) {
                prompts[b].ids = (const int*)promptIds;
                prompts[b].count = promptLen + 1UL;
                b = b + 1UL;
            }
        }
        unsigned long* outGenerated;
        unsafe { outGenerated = (unsigned long*)malloc(sizeof(unsigned long) * batchSize); }

        long long tGen0 = std::time_mono_ns();
        unsafe {
            std::gemma4_bf16_generate_batch(&model, (const struct Gemma4BatchPrompt*)prompts, batchSize, cfg,
                                             (void*)0, batch_stream_token_noop, outGenerated);
        }
        long long tGen1 = std::time_mono_ns();
        double genMs = std::time_ns_to_ms(std::time_elapsed_ns(tGen0, tGen1));

        unsigned long totalTokens = 0UL;
        unsigned long maxSteps = 0UL;
        unsafe {
            b = 0UL;
            while (b < batchSize) {
                totalTokens = totalTokens + outGenerated[b];
                if (outGenerated[b] > maxSteps) { maxSteps = outGenerated[b]; }
                b = b + 1UL;
            }
            free((void*)outGenerated);
            free((void*)prompts);
        }
        double genSec = genMs / 1000.0;
        double aggThroughput = (double)totalTokens / genSec;
        double msPerStep = genMs / (double)maxSteps;
        printf("generate (batch=%lu, %lu total tokens, %lu decode steps): %f s (%f ms/step, %f tok/s aggregate)\n",
               batchSize, totalTokens, maxSteps, genSec, msPerStep, aggThroughput);
    }

    unsafe { free((void*)promptIds); }
    std::gemma4_tok_free(&tok);
    std::gemma4_bf16_model_free(&model);
    printf("DONE\n");
    return 0;
}
