"""PyTorch Gemma 4 E2B greedy decode in its faster setups, next to
bench_pytorch.py's plain eager generate():
  compile   torch.compile(model.forward) (default mode), dynamic KV cache
  static    static KV cache, eager
(torch.compile mode="reduce-overhead" with the static cache, PyTorch's
graph-replay setup, crashes in transformers 5.20.2's static cache update on
ROCm 6.4, so it isn't measured.)

usage: bench_pytorch_compiled.py <model-dir> [compile|static] [max-new-tokens]
"""
import sys
import time
import torch
from transformers import AutoTokenizer, Gemma4ForConditionalGeneration

path = sys.argv[1]
mode = sys.argv[2] if len(sys.argv) > 2 else "compile"
n_new = int(sys.argv[3]) if len(sys.argv) > 3 else 64
dev = "cuda"
tok = AutoTokenizer.from_pretrained(path)
model = Gemma4ForConditionalGeneration.from_pretrained(path, dtype=torch.bfloat16).to(dev).eval()
if mode == "static":
    model.generation_config.cache_implementation = "static"
else:
    model.forward = torch.compile(model.forward)
ids = tok("The capital of France is", return_tensors="pt").input_ids.to(dev)
kw = dict(max_new_tokens=n_new, do_sample=False, temperature=None, top_p=None, top_k=None,
          pad_token_id=tok.eos_token_id)
with torch.inference_mode():
    for i in range(3):                       # compile / warm up
        t0 = time.time()
        out = model.generate(ids, **kw)
        torch.cuda.synchronize()
        print(f"warm-up {i}: {time.time() - t0:.1f}s", flush=True)
    t0 = time.time()
    out = model.generate(ids, **kw)
    torch.cuda.synchronize()
    dt = time.time() - t0
n = out.shape[1] - ids.shape[1]
print(f"{mode}: {n} tokens in {dt:.3f}s = {dt / n * 1000:.2f} ms/token")
print(repr(tok.decode(out[0][ids.shape[1]:], skip_special_tokens=True)[:60]))
