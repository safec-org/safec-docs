"""Flash and linear attention in PyTorch: the same shapes as ../safec/attn_cuda.sc.

usage: attn.py cuda

  flash   torch.nn.functional.scaled_dot_product_attention (PyTorch picks its
          FlashAttention-2 kernel for fp16/bf16 and the memory-efficient one
          for fp32), [batch, heads, seq, dim], grouped-query via enable_gqa
  linear  phi(x) = elu(x) + 1 attention, two ways:
            'torch'  vectorized PyTorch: non-causal as two einsums; causal
                     chunked (64 rows): masked intra-chunk scores plus a
                     cumulative sum of per-chunk phi(K)^T V states. Its fp32
                     rows run twice: with the container's default (NVIDIA's
                     NGC images set TORCH_ALLOW_TF32_CUBLAS_OVERRIDE=1, so the
                     einsums use TF32) and with allow_tf32 = False (IEEE fp32,
                     the precision SafeC's fp32 kernels compute in)
            'fla'    flash-linear-attention's Triton chunk_linear_attn
                     (causal only; phi applied before, scale 1, normalize),
                     when the 'fla' package is installed

Each row is the mean of a run of calls between two synchronizations after
three warm-up calls, under torch.inference_mode(). fla's output is checked
against the 'torch' path once per shape and the max abs difference printed.
"""
import sys, time
import torch
import torch.nn.functional as F

dev = sys.argv[1] if len(sys.argv) > 1 else "cuda"
if dev != "cuda":
    sys.exit(0)
torch.manual_seed(42)
B, H = 4, 16
DT = {"fp16": torch.float16, "bf16": torch.bfloat16, "fp32": torch.float32}


def bench(fn, iters):
    for _ in range(3):
        fn()
    torch.cuda.synchronize()
    t0 = time.perf_counter()
    for _ in range(iters):
        fn()
    torch.cuda.synchronize()
    return (time.perf_counter() - t0) * 1000 / iters


def phi(x):
    return F.elu(x) + 1


def linear_torch(q, k, v, causal, chunk=64):
    """Same math as std/ml's linear attention, in fp32 accumulation for 16-bit inputs."""
    q, k, v = phi(q.float()), phi(k.float()), v.float()
    if not causal:
        kv = torch.einsum("bhnd,bhne->bhde", k, v)
        z = k.sum(2)
        num = torch.einsum("bhnd,bhde->bhne", q, kv)
        den = torch.einsum("bhnd,bhd->bhn", q, z).unsqueeze(-1)
        return num / den
    b, h, n, d = q.shape
    c = n // chunk
    qc, kc, vc = (t.reshape(b, h, c, chunk, d) for t in (q, k, v))
    a = (qc @ kc.transpose(-1, -2)).tril()                      # intra-chunk, j <= i
    s = kc.transpose(-1, -2) @ vc                               # per-chunk phi(K)^T V
    s = s.cumsum(2) - s                                         # state before each chunk
    z = kc.sum(3)
    z = z.cumsum(2) - z
    num = a @ vc + qc @ s
    den = a.sum(-1, keepdim=True) + (qc * z.unsqueeze(3)).sum(-1, keepdim=True)
    return (num / den).reshape(b, h, n, d)


try:
    from fla.ops.linear_attn import chunk_linear_attn
except Exception:
    chunk_linear_attn = None


def linear_fla(q, k, v):
    # fla wants [batch, seq, heads, dim]
    o, _ = chunk_linear_attn(phi(q).transpose(1, 2), phi(k).transpose(1, 2), v.transpose(1, 2),
                             scale=1.0, normalize=True)
    return o.transpose(1, 2)


with torch.inference_mode():
    for dt in ("fp16", "bf16", "fp32"):
        for d in (64, 128):
            for s in (1024, 4096):
                for c in (0, 1):
                    q, k, v = (torch.rand(B, H, s, d, device="cuda", dtype=DT[dt]) - 0.5 for _ in range(3))
                    iters = (20 if s >= 4096 else 100) // (4 if dt == "fp32" else 1)
                    ms = bench(lambda: F.scaled_dot_product_attention(q, k, v, is_causal=bool(c)), iters)
                    fl = 4.0 * B * H * s * s * d / (2 if c else 1)
                    print(f"[flash {dt} d{d} s{s} c{c}] ms_per_call={ms:.4f} tflops={fl / (ms * 1e9):.2f}", flush=True)
    q = torch.rand(B, 32, 4096, 128, device="cuda", dtype=torch.bfloat16) - 0.5
    k, v = (torch.rand(B, 8, 4096, 128, device="cuda", dtype=torch.bfloat16) - 0.5 for _ in range(2))
    ms = bench(lambda: F.scaled_dot_product_attention(q, k, v, is_causal=True, enable_gqa=True), 20)
    fl = 4.0 * B * 32 * 4096 * 4096 * 128 / 2
    print(f"[flash bf16 gqa32/8 d128 s4096 c1] ms_per_call={ms:.4f} tflops={fl / (ms * 1e9):.2f}", flush=True)

    for dt in ("fp32", "bf16"):
        for d, s in ((64, 4096), (64, 16384), (128, 4096)):
            for c in (0, 1):
                q, k, v = ((torch.rand(B, H, s, d, device="cuda", dtype=DT[dt]) - 0.5) for _ in range(3))
                iters = 10 if s >= 16384 else 40
                ms = bench(lambda: linear_torch(q, k, v, c), iters)
                print(f"[linear {dt} d{d} s{s} c{c}] ms_per_call={ms:.4f}", flush=True)
                if dt == "fp32":
                    torch.backends.cuda.matmul.allow_tf32 = False
                    ms = bench(lambda: linear_torch(q, k, v, c), iters)
                    torch.backends.cuda.matmul.allow_tf32 = True
                    print(f"[linear fp32 ieee d{d} s{s} c{c}] ms_per_call={ms:.4f}", flush=True)
                if c and chunk_linear_attn is not None:
                    try:
                        diff = (linear_fla(q, k, v).float() - linear_torch(q, k, v, 1)).abs().max().item()
                        ms = bench(lambda: linear_fla(q, k, v), iters)
                        print(f"[linear fla {dt} d{d} s{s} c1] ms_per_call={ms:.4f} maxdiff={diff:.3g}", flush=True)
                    except Exception as e:
                        print(f"# fla {dt} d{d} s{s}: {type(e).__name__}: {e}", flush=True)
