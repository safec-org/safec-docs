import time
import torch
from transformers import AutoTokenizer, Gemma4ForConditionalGeneration

MODEL_DIR = "/Users/minjaekim/SafeC/models/gemma4-e2b"
PROMPT = "The capital of France is"
MAX_NEW_TOKENS = 20
BATCH_SIZES = [1, 4, 8, 16, 32]


def run_batch(model, tok, device_name, batch_size):
    ids1 = tok(PROMPT, return_tensors="pt", add_special_tokens=True)["input_ids"].to(device_name)
    ids = ids1.repeat(batch_size, 1)
    attention_mask = torch.ones_like(ids)

    t0 = time.time()
    with torch.no_grad():
        out = model.generate(
            ids,
            attention_mask=attention_mask,
            max_new_tokens=MAX_NEW_TOKENS,
            do_sample=False,
            temperature=None,
            top_p=None,
            top_k=None,
            pad_token_id=tok.eos_token_id,
        )
    gen_s = time.time() - t0

    new_tokens_per_seq = out.shape[1] - ids.shape[1]
    total_tokens = new_tokens_per_seq * batch_size
    per_token_ms = (gen_s / new_tokens_per_seq) * 1000 if new_tokens_per_seq > 0 else float("nan")
    agg_tok_s = total_tokens / gen_s if gen_s > 0 else float("nan")
    text0 = tok.decode(out[0][ids.shape[1]:], skip_special_tokens=True)

    print(f"  batch={batch_size:>2}: {gen_s:6.2f}s  {per_token_ms:7.1f}ms/token  "
          f"{agg_tok_s:7.2f} tok/s aggregate  text[0]={text0!r}")

    return {"batch": batch_size, "gen_s": gen_s, "new_tokens_per_seq": new_tokens_per_seq,
            "per_token_ms": per_token_ms, "agg_tok_s": agg_tok_s}


def run(device_name):
    print(f"\n=== PyTorch, device={device_name} ===")
    tok = AutoTokenizer.from_pretrained(MODEL_DIR)
    if tok.pad_token_id is None:
        tok.pad_token = tok.eos_token

    t0 = time.time()
    model = Gemma4ForConditionalGeneration.from_pretrained(
        MODEL_DIR, torch_dtype=torch.bfloat16
    ).to(device_name)
    model.eval()
    load_s = time.time() - t0
    print(f"load: {load_s:.1f}s")

    batch_results = []
    for b in BATCH_SIZES:
        r = run_batch(model, tok, device_name, b)
        batch_results.append(r)

    del model
    if device_name == "mps":
        torch.mps.empty_cache()

    return {"device": device_name, "load_s": load_s, "batches": batch_results}


if __name__ == "__main__":
    results = []
    results.append(run("cpu"))
    if torch.backends.mps.is_available():
        results.append(run("mps"))
    else:
        print("\nMPS not available in this torch build -- skipping MPS run")

    print("\n=== summary ===")
    for r in results:
        best = max(r["batches"], key=lambda x: x["agg_tok_s"])
        print(f"{r['device']:>4}: load={r['load_s']:.1f}s  "
              f"peak={best['agg_tok_s']:.2f} tok/s @ batch={best['batch']} "
              f"({best['per_token_ms']:.0f}ms/token, batch=1 baseline: "
              f"{r['batches'][0]['per_token_ms']:.0f}ms/token)")
