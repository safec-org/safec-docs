#pragma once
// Gemma 4 E2B decode on an AMD GPU (ROCm/HIP), fully GPU-resident.
//
// gemma4_rocm_init uploads every weight of a loaded Gemma4Bf16Model to the
// GPU as bf16 (~10 GB, the per-layer embedding table included, so a decode
// step never touches the host) and compiles the decode kernels
// (gemma4_rocm_kernels.hip) with hiprtc. A decode step is ~420 kernels:
// a bf16 GEMV per projection (one wave per output row, ~1 TB/s on an
// MI100), fused RMSNorm/residual/scale kernels, q/k head norms + RoPE +
// the KV-cache write in one kernel, split-key attention with an online
// softmax per wave (MQA: the one kv head is read once for all 8 query
// heads), and a GPU argmax that writes the next token straight into the
// next step's input. Token and position live in device memory, so one step
// is recorded once as a HIP graph and replayed per token.
//
// Activations and the KV cache are fp32 (the weights are bf16); the
// projection math is the model's (see gemma4_model_bf16.sc), verified
// against the transformers reference by the tests.
#include "gemma4_model_bf16.h"

namespace std {

// Uploads 'model' and prepares buffers for up to 'maxSeq' positions
// (prompt + generated). 1 on success, 0 without a ROCm GPU or on failure.
int gemma4_rocm_init(&Gemma4Bf16Model model, unsigned long maxSeq);

// Starts a new sequence (position 0).
void gemma4_rocm_reset();

// Runs 'n' prompt tokens through the model, one step per token. Returns the
// greedy next token. If 'logitsOut' is given it receives the last step's
// softcapped logits (vocab size floats). 'useGraph' replays the recorded
// step instead of launching each kernel.
int gemma4_rocm_prefill(const &int prompt, unsigned long n, ?&float logitsOut, int useGraph);

// After a prefill: greedily decodes up to 'maxNew' tokens (the first is the
// prefill's own prediction) into 'outIds'. Stops early after 'eosId'
// (checked every 'checkEvery' steps; the tokens past it are dropped).
// Returns the number of tokens written.
unsigned long gemma4_rocm_generate(unsigned long maxNew, &int outIds, int eosId,
                                   unsigned long checkEvery, int useGraph);

// Current position (tokens processed so far in this sequence).
unsigned long gemma4_rocm_position();

void gemma4_rocm_free();

} // namespace std
