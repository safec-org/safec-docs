// gemma4 — from-scratch Gemma 4 E2B inference, HTTP streaming demo server.
// `safeguard run` entry point: loads the real checkpoint + tokenizer and
// serves GET /generate?prompt=<text> as HTTP/1.1 chunked text/plain,
// streaming one token at a time as it's generated (see
// std/ml/gemma4_http_server.h's header comment for why this hand-writes
// chunked framing instead of using std::http_serve_reactor).
//
// Usage: build/gemma4 [checkpointPath] [vocabBinPath] [mergesBinPath] [port]
// All arguments optional; see the defaults below.
extern int printf(const char* fmt, ...);
extern unsigned long strtoul(const char* s, char** endptr, int base);
#include "gemma4_http_server.h"
#include <std/sys.h>

int main(int argc, char** argv) {
    Autograd::get().set_enabled(0);
    std::sys_srand(1U);

    const char* checkpointPath = "/Users/minjaekim/SafeC/models/gemma4-e2b/model.safetensors";
    const char* vocabPath = "vocab.bin";
    const char* mergesPath = "merges.bin";
    unsigned short port = 8080U;

    unsafe {
        if (argc > 1) { checkpointPath = argv[1]; }
        if (argc > 2) { vocabPath = argv[2]; }
        if (argc > 3) { mergesPath = argv[3]; }
        if (argc > 4) { port = (unsigned short)strtoul(argv[4], (char**)0, 10); }
    }

    printf("loading tokenizer (%s, %s)...\n", vocabPath, mergesPath);
    struct Gemma4Tokenizer tok = std::gemma4_tok_load(vocabPath, mergesPath);

    // Loaded and served as bf16 throughout -- weights AND activations,
    // not just storage (see src/gemma4_bf16.h's header comment): halves
    // the checkpoint's resident memory and load I/O versus an F32-
    // upcast-on-load design, and every matmul (the dominant compute
    // cost) runs as real bf16 GPU/BLAS compute. There is no separate F32
    // model in this codebase (see DESIGN_NOTES.md).
    printf("loading checkpoint as BF16 (%s)...\n", checkpointPath);
    struct Gemma4Bf16Model model = std::gemma4_bf16_model_load(checkpointPath);
    printf("loaded: vocabSize=%lu hiddenSize=%lu numLayers=%lu\n",
           model.vocabSize, model.hiddenSize, model.numLayers);

    std::gemma4_http_serve_stream_bf16(port, &model, &tok, 200UL, (float)0.0);
    return 0;
}
