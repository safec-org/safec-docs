"""Gemma 4 E2B single-request greedy decoding on CUDA with transformers, for comparison with
build/gemma4_cuda: same prompt, 64 greedy tokens, bf16.

usage: bench_pytorch_cuda.py MODEL_DIR [eager|static|compile]
  eager    model.generate with the default dynamic cache
  static   cache_implementation="static" (transformers compiles the decode step)
  compile  static cache plus torch.compile(mode="reduce-overhead") on forward"""
import sys, time, torch
from transformers import AutoTokenizer, Gemma4ForConditionalGeneration
M = sys.argv[1]
NEW = 64
tok = AutoTokenizer.from_pretrained(M)
t0 = time.time()
model = Gemma4ForConditionalGeneration.from_pretrained(M, dtype=torch.bfloat16).to("cuda").eval()
torch.cuda.synchronize(); load = time.time() - t0
ids = tok("The capital of France is", return_tensors="pt")["input_ids"].cuda()

def run(n=NEW):
    torch.cuda.synchronize(); t = time.time()
    with torch.no_grad():
        out = model.generate(ids, max_new_tokens=n, min_new_tokens=n, do_sample=False, temperature=None, top_p=None, top_k=None)
    torch.cuda.synchronize(); return time.time() - t, out

def best(k, n=NEW):
    r = [run(n) for _ in range(k)]
    return min(x[0] for x in r), r[0][1]

mode = sys.argv[2] if len(sys.argv) > 2 else "eager"
if mode == "eager":
    run(4)
    s, out = best(3)
else:
    model.generation_config.cache_implementation = "static"
    if mode == "compile": model.forward = torch.compile(model.forward, mode="reduce-overhead", fullgraph=False)
    for _ in range(3): run()
    s, out = best(3)
t1, _ = best(3, 1)   # prefill + first token
new = out[0, ids.shape[1]:].tolist()
print(f"{mode}: load {load:.1f}s  {NEW} tokens in {s*1e3:.1f} ms -> {s*1e3/NEW:.2f} ms/token (incl. prefill); decode only {(s-t1)*1e3/(NEW-1):.2f} ms/token  first ids {new[:8]}")
