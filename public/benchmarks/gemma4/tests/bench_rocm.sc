// Real Gemma 4 E2B inference on an AMD GPU (src/gemma4_rocm.h), checked and
// timed:
//   1. top-5 next-token ids and their softcapped logits for four prompts
//      against google/gemma-4-E2B run through transformers 5.20.2 in fp32 on
//      the same GPU (tests/ref_transformers.py): ids in the same order, and
//      logits within 0.05;
//   2. greedy generation for "The capital of France is" (the prompt of the
//      PyTorch/MLX/SafeC-MPS rows), printed as text, timed per token both
//      with per-kernel launches and with the step replayed as a HIP graph.
//
// usage: bench_rocm <model-dir> [newTokens] [nograph] [ids]
//   nograph    skip the graph-replay run (e.g. under a profiler)
//   ids        also print the generated token ids (for comparing long
//              generations with tests/ref_transformers_generate.py)
//   <model-dir> holds model.safetensors, vocab.bin and merges.bin
//   (tools/make_tokenizer_bins.py makes the last two from tokenizer.json).
extern int printf(const char* fmt, ...);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
extern int snprintf(char* buf, unsigned long n, const char* fmt, ...);
extern int strcmp(const char* a, const char* b);
#include "../src/gemma4_rocm.h"
#include "../src/gemma4_tokenizer.h"
#include <std/ml/gpu_rocm.h>
#include <std/sys.h>
#include <std/time.h>

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

// Prompt -> [bos] + ids, in a malloc'd array; *n gets the count.
static int* encode_(&Gemma4Tokenizer tok, const char* text, unsigned long* n) {
    struct Vec<int> e = std::gemma4_tok_encode(tok, text);
    unsigned long len = e.length();
    int* ids;
    unsafe {
        ids = (int*)malloc(sizeof(int) * (len + 1UL));
        ids[0] = tok.bosId;
        unsigned long i = 0UL;
        while (i < len) { ids[i + 1UL] = *e.at(i); i = i + 1UL; }
        *n = len + 1UL;
    }
    e.free();
    return ids;
}

// Indices of the 5 largest logits, best first (ties: lower id first).
static void top5_(const float* lg, unsigned long n, int* best) {
    unsafe {
        int k = 0;
        while (k < 5) {
            int bi = -1;
            float bv = (float)-3.0e38;
            unsigned long i = 0UL;
            while (i < n) {
                int taken = 0;
                int t = 0;
                while (t < k) { if (best[t] == (int)i) taken = 1; t = t + 1; }
                if (!taken && lg[i] > bv) { bv = lg[i]; bi = (int)i; }
                i = i + 1UL;
            }
            best[k] = bi;
            k = k + 1;
        }
    }
}

int main(int argc, char** argv) {
    Autograd::get().set_enabled(0);
    if (argc < 2) { printf("usage: bench_rocm <model-dir> [newTokens] [nograph] [ids]\n"); return 1; }
    unsigned long newTokens = 64UL;
    int runs = 2;
    int printIds = 0;
    unsafe {
        int ai = 3;
        while (ai < argc) {
            if (strcmp(argv[ai], "nograph") == 0) runs = 1;
            if (strcmp(argv[ai], "ids") == 0) printIds = 1;
            ai = ai + 1;
        }
    }
    char pModel[1024]; char pVocab[1024]; char pMerges[1024];
    unsafe {
        if (argc > 2) newTokens = (unsigned long)std::sys_atoi(argv[2]);
        snprintf(&pModel[0], 1024UL, "%s/model.safetensors", argv[1]);
        snprintf(&pVocab[0], 1024UL, "%s/vocab.bin", argv[1]);
        snprintf(&pMerges[0], 1024UL, "%s/merges.bin", argv[1]);
    }
    double t0 = now_ms();
    struct Gemma4Tokenizer tok = std::gemma4_tok_load(&pVocab[0], &pMerges[0]);
    struct Gemma4Bf16Model model = std::gemma4_bf16_model_load(&pModel[0]);
    double t1 = now_ms();
    if (!std::gemma4_rocm_init(&model, 4096UL)) { printf("no ROCm GPU or init failed\n"); return 1; }
    double t2 = now_ms();
    unsafe { printf("device: %s\nload: %.2f s from disk + %.2f s upload/compile\n", std::rocm_device_name(), (t1 - t0) / 1000.0, (t2 - t1) / 1000.0); }

    // 1. top-5 against the transformers reference
    const char* prompts[4];
    prompts[0] = "Hello, world!";
    prompts[1] = "The quick brown fox jumps over the lazy dog.";
    prompts[2] = "SafeC";
    prompts[3] = "The capital of France is";
    int ref[4][5];
    float refv[4][5];
    ref[0][0] = 108; ref[0][1] = 564; ref[0][2] = 3551; ref[0][3] = 107; ref[0][4] = 19694;
    refv[0][0] = (float)17.4623; refv[0][1] = (float)16.615; refv[0][2] = (float)15.7023; refv[0][3] = (float)15.4637; refv[0][4] = (float)15.4251;
    ref[1][0] = 108; ref[1][1] = 669; ref[1][2] = 107; ref[1][3] = 236743; ref[1][4] = 1174;
    refv[1][0] = (float)18.6245; refv[1][1] = (float)17.2376; refv[1][2] = (float)17.0428; refv[1][3] = (float)16.8159; refv[1][4] = (float)16.7197;
    ref[2][0] = 694; ref[2][1] = 4720; ref[2][2] = 8830; ref[2][3] = 1853; ref[2][4] = 4506;
    refv[2][0] = (float)4.5813; refv[2][1] = (float)3.3327; refv[2][2] = (float)3.1209; refv[2][3] = (float)2.75; refv[2][4] = (float)2.4673;
    ref[3][0] = 9079; ref[3][1] = 496; ref[3][2] = 506; ref[3][3] = 3224; ref[3][4] = 886;
    refv[3][0] = (float)22.5101; refv[3][1] = (float)21.7976; refv[3][2] = (float)20.9275; refv[3][3] = (float)20.6587; refv[3][4] = (float)20.492;
    float* logits;
    unsafe { logits = (float*)malloc(sizeof(float) * model.vocabSize); }
    int fails = 0;
    int pi = 0;
    while (pi < 4) {
        unsigned long n = 0UL;
        int* ids = encode_(&tok, prompts[pi], &n);
        std::gemma4_rocm_reset();
        unsafe { std::gemma4_rocm_prefill((const &int)ids, n, (?&float)logits, runs - 1); }
        int best[5];
        top5_(logits, model.vocabSize, &best[0]);
        int same = 1;
        float worst = (float)0.0;
        int k = 0;
        while (k < 5) {
            if (best[k] != ref[pi][k]) same = 0;
            float dv;
            unsafe { dv = logits[best[k]] - refv[pi][k]; }
            if (dv < (float)0.0) dv = -dv;
            if (dv > worst) worst = dv;
            k = k + 1;
        }
        if (!same || worst > (float)0.05) fails = fails + 1;
        unsafe {
            printf("top-5 \"%s\": [%d, %d, %d, %d, %d] logits %.4f %.4f %.4f %.4f %.4f  %s, max |logit - ref| %.4f\n",
                   prompts[pi], best[0], best[1], best[2], best[3], best[4], (double)logits[best[0]], (double)logits[best[1]],
                   (double)logits[best[2]], (double)logits[best[3]], (double)logits[best[4]],
                   same ? "ids == reference" : "ids != reference", (double)worst);
            free((void*)ids);
        }
        pi = pi + 1;
    }

    // 2. generation
    unsigned long n = 0UL;
    int* ids = encode_(&tok, "The capital of France is", &n);
    int* outIds;
    unsafe { outIds = (int*)malloc(sizeof(int) * (newTokens + 1UL)); }
    int g = 0;
    while (g < runs) {
        std::gemma4_rocm_reset();
        double a = now_ms();
        unsafe { std::gemma4_rocm_prefill((const &int)ids, n, (?&float)0, g); }
        std::rocm_sync();
        double b = now_ms();
        unsigned long got;
        unsafe { got = std::gemma4_rocm_generate(newTokens, (&int)outIds, -1, 0UL, g); }
        double c = now_ms();
        unsafe {
            printf("%s: prefill %lu tokens %.1f ms; %lu new tokens in %.1f ms = %.2f ms/token (%.1f tokens/s)\n",
                   g ? "graph replay" : "kernel launches", n, b - a, got, c - b, (c - b) / (double)(got - 1UL),
                   1000.0 * (double)(got - 1UL) / (c - b));
        }
        if (g == runs - 1) {
            struct String text = std::gemma4_tok_decode(&tok, (const int*)outIds, got);
            unsafe { printf("text: The capital of France is%s\n", text.as_ptr()); }
            text.free();
            if (printIds) {
                printf("ids:");
                unsigned long q = 0UL;
                while (q < got) { unsafe { printf(" %d", outIds[q]); } q = q + 1UL; }
                printf("\n");
            }
        }
        g = g + 1;
    }
    unsafe { free((void*)ids); free((void*)outIds); free((void*)logits); }
    std::gemma4_rocm_free();
    std::gemma4_tok_free(&tok);
    std::gemma4_bf16_model_free(&model);
    printf("%s\n", fails == 0 ? "top-5: all match" : "top-5: MISMATCH");
    return fails == 0 ? 0 : 1;
}
