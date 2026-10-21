"""Reference next-token top-5 (ids and logits) for bench_rocm.sc: the real
google/gemma-4-E2B checkpoint through transformers, in fp32 by default.
transformers' Gemma 4 forward already applies the final logit softcap.

usage: ref_transformers.py <checkpoint-dir> [fp32|bf16]
"""
import sys
import torch
from transformers import AutoTokenizer, AutoModelForCausalLM

path = sys.argv[1]
dt = torch.bfloat16 if len(sys.argv) > 2 and sys.argv[2] == "bf16" else torch.float32
dev = "cuda" if torch.cuda.is_available() else "cpu"
tok = AutoTokenizer.from_pretrained(path)
model = AutoModelForCausalLM.from_pretrained(path, dtype=dt).to(dev).eval()
for text in ["Hello, world!", "The quick brown fox jumps over the lazy dog.", "SafeC", "The capital of France is"]:
    ids = tok(text, return_tensors="pt").input_ids.to(dev)       # adds <bos>
    with torch.inference_mode():
        logits = model(ids).logits[0, -1].float()
    v, i = torch.topk(logits, 5)
    print(f"{text!r}: ids {ids[0].tolist()} top5 {i.tolist()} logits {[round(x, 4) for x in v.tolist()]}")
