#pragma once
// SafeC Standard Library — Gemma 4 generation loop: greedy or
// temperature-sampled decoding on top of gemma4_model_bf16.h's cached
// bf16 forward pass, one token at a time, with a per-token callback for
// streaming (the HTTP server wraps this directly -- see DESIGN_NOTES.md).
//
// Owns nothing about tokenization -- caller passes already-tokenized
// prompt ids (gemma4_tokenizer.h) and receives already-generated token
// ids back through the callback; decoding ids back to text is the
// caller's job (gemma4_tok_decode), typically called once per token so
// an HTTP handler can stream text chunks as they're produced.
#include "gemma4_model_bf16.h"

namespace std {

typedef fn void(void* ctx, int tokenId) Gemma4TokenCallback;

struct Gemma4GenConfig {
    unsigned long maxNewTokens;
    float temperature;   // 0.0 = greedy (argmax); >0.0 = softmax(logits/temperature) sampling
    int eosId;            // stop as soon as this token is generated (not passed to onToken)
};

// Runs the prompt through the model (prefill, populating a fresh
// Gemma4Bf16KvCache), then generates up to cfg.maxNewTokens further
// tokens one at a time, calling 'onToken(ctx, tokenId)' for each NEWLY
// generated token (not for the prompt tokens themselves) immediately
// after it's picked -- this is what makes HTTP streaming possible, since
// the caller can flush a chunk to the response from inside the callback
// before the next token is even computed. Stops early if cfg.eosId is
// generated (NOT passed to onToken) or cfg.maxNewTokens is reached.
// Returns the number of tokens actually generated (excludes the prompt,
// excludes a terminating eos if one was hit).
//
// Allocates and frees its own Gemma4Bf16KvCache internally -- one call
// here is one complete, independent generation; callers wanting multi-
// turn conversation state across separate calls would need a lower-level
// API this module doesn't provide (not needed for this benchmark's
// single-turn HTTP endpoint). gemma4_bf16_model_forward returns F32
// logits even though the model itself is bf16 throughout (see
// gemma4_model_bf16.h), so the argmax/temperature-sampling logic here is
// ordinary F32 math, unaffected by the model's own dtype.
unsigned long gemma4_bf16_generate(&Gemma4Bf16Model model, const int* promptIds, unsigned long promptCount,
                                     struct Gemma4GenConfig cfg, void* ctx, Gemma4TokenCallback onToken);

// ── Batched generation (see gemma4_model_bf16.h's gemma4_bf16_model_
// forward_batch for the underlying design) ─────────────────────────────
struct Gemma4BatchPrompt {
    const int* ids;
    unsigned long count;
};

typedef fn void(void* ctx, unsigned long seqIdx, int tokenId) Gemma4BatchTokenCallback;

// Runs 'batchSize' independent prompts together: each is prefilled
// separately (gemma4_bf16_model_forward, unbatched -- prompts have
// different lengths in general), then every decode step afterward runs
// as ONE gemma4_bf16_model_forward_batch call across all batchSize
// sequences at once, batching every matmul-shaped op (the dominant FLOP
// cost) instead of paying batchSize separate calls per step.
//
// Simplification, clearly not a production continuous-batching
// scheduler: no dynamic shrinking when a sequence finishes early. Once a
// sequence hits cfg.eosId or its own maxNewTokens, onToken simply stops
// being called for it, but its cache keeps silently advancing alongside
// the rest of the batch (feeding its own last-produced id back in) until
// EVERY sequence is finished or cfg.maxNewTokens is reached -- wasted
// compute for that one row, harmless and irrelevant to correctness,
// since each sequence's attention only ever reads its own cache (see
// gemma4_bf16_model_forward_batch's header comment).
//
// 'outGenerated' is caller-allocated, batchSize entries: receives the
// number of tokens actually generated for sequence b (mirrors
// gemma4_bf16_generate's single return value, generalized to an array
// since there's no single scalar to return for a batch).
void gemma4_bf16_generate_batch(&Gemma4Bf16Model model, const struct Gemma4BatchPrompt* prompts, unsigned long batchSize,
                                  struct Gemma4GenConfig cfg, void* ctx, Gemma4BatchTokenCallback onToken,
                                  unsigned long* outGenerated);

// ── Single-request speculative decoding (prompt-lookup) ─────────────────
// A single-request technique in the sense the batching functions above
// aren't: gemma4_bf16_generate_batch parallelizes ACROSS independent
// sequences (needs multiple concurrent requests to have anything to
// batch); this parallelizes WITHIN one sequence's own future by drafting
// several candidate next tokens and verifying all of them in ONE forward
// call instead of one call per token -- vLLM/HuggingFace call this
// "prompt lookup decoding": no second (draft) model needed, just search
// the sequence's own already-generated history for the most recent
// earlier occurrence of the last few tokens, and speculatively continue
// with whatever followed it there. Real win specifically for repetitive
// output (the exact shape this project's own real-checkpoint benchmark
// prompt happens to produce -- see DESIGN_NOTES.md).
//
// Losslessly exact, not an approximation: every drafted token is
// verified against the model's own greedy prediction (gemma4_bf16_model_
// forward's existing multi-token/prefill path, reused unchanged -- causal
// masking already makes row i's logits depend only on rows <i plus the
// cache, exactly the math a real prefill already does) before being
// accepted; a mismatch rolls the KV cache back to just the tokens that
// verified correctly (gemma4_bf16_kvcache_truncate) and falls back to
// the model's own real prediction at that point. Output is bit-identical
// to gemma4_bf16_generate for the same inputs -- this is strictly a
// faster way to compute the exact same greedy decode, not a different
// one. Only drafts beyond the first token when cfg.temperature <= 0
// (exact-match acceptance only makes sense against a deterministic
// argmax); sampling (temperature > 0) automatically falls back to the
// same one-token-at-a-time shape gemma4_bf16_generate already uses.
unsigned long gemma4_bf16_generate_speculative(&Gemma4Bf16Model model, const int* promptIds, unsigned long promptCount,
                                                 struct Gemma4GenConfig cfg, void* ctx, Gemma4TokenCallback onToken);

} // namespace std
