import time
from pathlib import Path
from mlx_lm import generate
from mlx_lm.generate import batch_generate
from mlx_lm.utils import load_model, load_tokenizer
from mlx_lm.sample_utils import make_sampler

MODEL_DIR = "/Users/minjaekim/SafeC/models/gemma4-e2b"
PROMPT = "The capital of France is"
MAX_NEW_TOKENS = 20
BATCH_SIZES = [1, 4, 8, 16, 32]


def main():
    print("=== MLX (Apple Silicon GPU, unified memory) ===")
    t0 = time.time()
    # strict=False: mlx-lm's own Gemma4 model definition (like this
    # project's own gemma4_model.sc -- see DESIGN_NOTES.md's RESOLVED
    # section) correctly has NO k_proj/k_norm/v_proj for the cross-layer-
    # KV-shared layers (15..34), but the real checkpoint still contains
    # those vestigial tensors (present in the file, never read at
    # inference -- confirmed independently by mlx-lm's own strict loader
    # rejecting exactly those 60 "extra" parameters when strict=True).
    model, _ = load_model(Path(MODEL_DIR), strict=False)
    tokenizer = load_tokenizer(Path(MODEL_DIR))
    load_s = time.time() - t0
    print(f"load: {load_s:.1f}s")

    # Single-sequence baseline (batch=1), same call this file always used:
    # the tokenizer directly (not chat template) to match the SafeC/
    # PyTorch runs, which both feed the raw prompt text, not a chat-
    # formatted one.
    t0 = time.time()
    text = generate(
        model, tokenizer, prompt=PROMPT, max_tokens=MAX_NEW_TOKENS,
        sampler=make_sampler(temp=0.0), verbose=False,
    )
    gen_s = time.time() - t0
    per_token_ms = (gen_s / MAX_NEW_TOKENS) * 1000
    print(f"batch= 1: {gen_s:6.2f}s  {per_token_ms:7.1f}ms/token  "
          f"{1000.0 / per_token_ms:7.2f} tok/s aggregate  text={text!r}")

    results = [{"batch": 1, "gen_s": gen_s, "per_token_ms": per_token_ms,
                "agg_tok_s": 1000.0 / per_token_ms}]

    # mlx_lm.generate.batch_generate: real multi-sequence batched decoding
    # (continuous-batching-style BatchGenerator under the hood), same
    # prompt replicated batch_size times, greedy (temp=0 via a sampler is
    # not exposed here -- batch_generate defaults to greedy when no
    # sampler kwarg is given, matching do_sample=False/temp=0.0 above).
    prompt_ids = tokenizer.encode(PROMPT)
    for b in BATCH_SIZES[1:]:
        prompts = [prompt_ids] * b
        resp = batch_generate(model, tokenizer, prompts, max_tokens=MAX_NEW_TOKENS, verbose=False)
        gen_s = resp.stats.generation_time
        gen_tokens = resp.stats.generation_tokens
        agg_tok_s = resp.stats.generation_tps
        per_token_ms = (gen_s / (gen_tokens / b)) * 1000 if gen_tokens > 0 else float("nan")
        print(f"batch={b:>2}: {gen_s:6.2f}s  {per_token_ms:7.1f}ms/token  "
              f"{agg_tok_s:7.2f} tok/s aggregate  text[0]={resp.texts[0]!r}")
        results.append({"batch": b, "gen_s": gen_s, "per_token_ms": per_token_ms, "agg_tok_s": agg_tok_s})

    print("\n=== summary ===")
    best = max(results, key=lambda x: x["agg_tok_s"])
    print(f"load={load_s:.1f}s  peak={best['agg_tok_s']:.2f} tok/s @ batch={best['batch']} "
          f"({best['per_token_ms']:.0f}ms/token, batch=1 baseline: {results[0]['per_token_ms']:.0f}ms/token)")


if __name__ == "__main__":
    main()
