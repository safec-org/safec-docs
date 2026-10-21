"""Greedy token ids from transformers for "The capital of France is", to
compare with `bench_rocm <dir> <n> ids` over long generations (past the
512-token sliding window).

usage: ref_transformers_generate.py <checkpoint-dir> <new-tokens> [fp32|bf16]
"""
import sys
import torch
from transformers import AutoTokenizer, AutoModelForCausalLM

path, n = sys.argv[1], int(sys.argv[2])
dt = torch.bfloat16 if len(sys.argv) > 3 and sys.argv[3] == "bf16" else torch.float32
tok = AutoTokenizer.from_pretrained(path)
model = AutoModelForCausalLM.from_pretrained(path, dtype=dt).to("cuda").eval()
ids = tok("The capital of France is", return_tensors="pt").input_ids.to("cuda")
with torch.inference_mode():
    out = model.generate(ids, max_new_tokens=n, do_sample=False, temperature=None, top_p=None, top_k=None,
                         pad_token_id=tok.eos_token_id)
print("ids:", " ".join(str(t) for t in out[0][ids.shape[1]:].tolist()))
