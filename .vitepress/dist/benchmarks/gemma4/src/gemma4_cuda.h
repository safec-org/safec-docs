#pragma once
// Gemma 4 E2B on NVIDIA GPUs (CUDA, sm_90): single-request greedy decoding with every
// weight and the KV cache resident on the GPU.
//
// One decode step is 251 kernels (gemma4_cuda.sc's kernel source, compiled with NVRTC
// through std/ml/gpu_cuda.h's cuda_load_module): a bandwidth-bound bf16 GEMV for every
// projection (Q/K/V and gate/up fused into one matrix each), with the residual add,
// RMSNorm and the next op's input preparation done by the last block of the GEMV
// before it ("last block done" counter) instead of separate launches; a split-key
// decode attention that also applies q/k/v norm and RoPE and writes the KV cache; and
// a tied LM head whose GEMV keeps a running argmax. The step reads its position from
// GPU memory and writes the next token back there, so it is captured once as a CUDA
// graph and replayed with no host work between tokens. Kernels are launched with
// programmatic dependent launch: each one starts while its predecessor finishes and
// prefetches its weights into L2 before waiting for its input.
//
// Numerics follow the PyTorch bf16 model: activations are rounded to bf16 where
// PyTorch stores a bf16 tensor, with fp32 inside each op (norms, softmax, dot products).
// The prompt is processed one token at a time through the same graph.
#include <std/ml/gpu_cuda.h>

namespace std {

#define GEMMA4_CUDA_LAYERS 35

struct Gemma4CudaLayer {
    unsigned long hd;            // head dim: 256 sliding, 512 full attention
    unsigned long ff;            // FFN width: 6144, 12288 for the KV-shared layers 15..34
    int full;                    // full (global) attention layer
    int shared;                  // reuses layer 13's / 14's K/V
    unsigned long wqkv;   // device pointers (bf16)
    unsigned long wo;
    unsigned long wgu;
    unsigned long wd;
    unsigned long wpg;
    unsigned long wpp;
    unsigned long inln;
    unsigned long postattn;
    unsigned long preffn;
    unsigned long postffn;
    unsigned long postple;
    unsigned long qn;
    unsigned long kn;
    unsigned long scal;
    unsigned long kc;   // KV cache [maxSeq, hd] (the source layer's for shared layers)
    unsigned long vc;
};

struct Gemma4Cuda {
    int ok;
    unsigned long maxSeq;
    double loadSeconds;
    unsigned long embW;
    unsigned long pleW;
    unsigned long pleProjW;
    unsigned long pleNormW;
    unsigned long finalNormW;
    struct Gemma4CudaLayer layers[GEMMA4_CUDA_LAYERS];
    // activations and step state
    unsigned long xa;
    unsigned long xb;
    unsigned long xs;
    unsigned long pleTok;
    unsigned long pleRaw;
    unsigned long ple;
    unsigned long qkv;
    unsigned long att;
    unsigned long tmp;
    unsigned long ffh;
    unsigned long logits;
    unsigned long part;
    unsigned long state;
    unsigned long tokens;
    unsigned long best;
    unsigned long counters;
    unsigned long nsp;           // attention key splits
    void* kfn[7];                // kernels: embed, ple_combine, finish, gemv, attn, advance (+spare)
    struct CudaGraph graph;
};

// Loads the text model from a google/gemma-4-E2B model.safetensors onto the GPU (bf16),
// with a KV cache for maxSeq positions. Check .ok.
struct Gemma4Cuda gemma4_cuda_load(const char* checkpointPath, unsigned long maxSeq);

typedef fn void(void* ctx, int tokenId) Gemma4CudaTokenFn;

struct Gemma4CudaTiming {
    double prefillMs;            // the prompt, through its last position (gives the first new token)
    double decodeMs;             // the generated tokens after the first
    unsigned long decodeSteps;
};

// Greedy decoding: runs the prompt (ids, starting with <bos>) through the model, then
// generates up to nNew tokens into out[0..nNew). With 'streaming' set, onToken(ctx, id)
// is called with each new token as soon as it is known (one GPU sync per token);
// otherwise every step is queued back to back and the tokens are read once at the end.
// Returns the number of tokens generated (stops at maxSeq, and with stopAtEos after
// <eos> = 1 -- checked per token only when streaming).
unsigned long gemma4_cuda_generate(&Gemma4Cuda m, const int* prompt, unsigned long promptLen,
                                   unsigned long nNew, int* out, int stopAtEos, int streaming,
                                   void* ctx, Gemma4CudaTokenFn onToken, &Gemma4CudaTiming timing);

// Logits of the last prompt position (vocab-size bf16 values, as floats) after running
// the prompt; for checking against a reference. Returns 1 on success.
int gemma4_cuda_prompt_logits(&Gemma4Cuda m, const int* prompt, unsigned long promptLen, float* logitsOut);

void gemma4_cuda_free(&Gemma4Cuda m);

} // namespace std
