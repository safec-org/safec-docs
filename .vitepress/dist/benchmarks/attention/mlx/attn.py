"""Flash and linear attention in MLX: the same shapes as ../safec/attn_mps.sc.

  flash   mx.fast.scaled_dot_product_attention (MLX's fused Metal attention
          kernels), [batch, heads, seq, dim], causal through mask="causal",
          grouped-query K/V passed with fewer heads
  linear  phi(x) = elu(x) + 1 attention vectorized in MLX ops, the same
          formulation as ../pytorch/attn.py's 'torch' path: non-causal as two
          einsums; causal chunked (64 rows) with masked intra-chunk scores and
          a cumulative sum of per-chunk phi(K)^T V states. fp32 accumulation.

Each row is the mean of a run of calls evaluated together after three
warm-up calls (MLX is lazy: mx.eval runs the queued graph). Linear rows
evaluate each call on its own, since a run of them queued together would
keep every call's intermediates alive at once.
"""
import time
import mlx.core as mx

mx.random.seed(42)
B, H = 4, 16
DT = {"fp16": mx.float16, "bf16": mx.bfloat16, "fp32": mx.float32}


def bench(fn, iters, each=False):
    for _ in range(3):
        mx.eval(fn())
    t0 = time.perf_counter()
    if each:
        for _ in range(iters):
            mx.eval(fn())
    else:
        mx.eval(*[fn() for _ in range(iters)])
    return (time.perf_counter() - t0) * 1000 / iters


def rand(shape, dt):
    return (mx.random.uniform(shape=shape) - 0.5).astype(dt)


def phi(x):
    return mx.where(x > 0, x + 1, mx.exp(x))


def linear_mlx(q, k, v, causal, chunk=64):
    q, k, v = phi(q.astype(mx.float32)), phi(k.astype(mx.float32)), v.astype(mx.float32)
    if not causal:
        kv = mx.einsum("bhnd,bhne->bhde", k, v)
        z = k.sum(2)
        num = mx.einsum("bhnd,bhde->bhne", q, kv)
        den = mx.einsum("bhnd,bhd->bhn", q, z)[..., None]
        return num / den
    b, h, n, d = q.shape
    c = n // chunk
    qc, kc, vc = (t.reshape(b, h, c, chunk, d) for t in (q, k, v))
    a = mx.tril(qc @ kc.swapaxes(-1, -2))                     # intra-chunk, j <= i
    s = kc.swapaxes(-1, -2) @ vc                               # per-chunk phi(K)^T V
    s = mx.cumsum(s, axis=2) - s                               # state before each chunk
    z = kc.sum(3)
    z = mx.cumsum(z, axis=2) - z
    num = a @ vc + qc @ s
    den = a.sum(-1, keepdims=True) + (qc * z[:, :, :, None, :]).sum(-1, keepdims=True)
    return (num / den).reshape(b, h, n, d)


for dt in ("fp16", "bf16", "fp32"):
    for d in (64, 128):
        for s in (1024, 4096):
            for c in (0, 1):
                q, k, v = (rand((B, H, s, d), DT[dt]) for _ in range(3))
                iters = (10 if s >= 4096 else 40) // (2 if dt == "fp32" else 1)
                mask = "causal" if c else None
                ms = bench(lambda: mx.fast.scaled_dot_product_attention(q, k, v, scale=d ** -0.5, mask=mask), iters)
                fl = 4.0 * B * H * s * s * d / (2 if c else 1)
                print(f"[flash {dt} d{d} s{s} c{c}] ms_per_call={ms:.4f} tflops={fl / (ms * 1e9):.2f}", flush=True)
q = rand((B, 32, 4096, 128), mx.bfloat16)
k, v = (rand((B, 8, 4096, 128), mx.bfloat16) for _ in range(2))
ms = bench(lambda: mx.fast.scaled_dot_product_attention(q, k, v, scale=128 ** -0.5, mask="causal"), 5)
fl = 4.0 * B * 32 * 4096 * 4096 * 128 / 2
print(f"[flash bf16 gqa32/8 d128 s4096 c1] ms_per_call={ms:.4f} tflops={fl / (ms * 1e9):.2f}", flush=True)

for dt in ("fp32", "bf16"):
    for d, s in ((64, 4096), (64, 16384), (128, 4096)):
        for c in (0, 1):
            q, k, v = (rand((B, H, s, d), DT[dt]) for _ in range(3))
            iters = 3 if s >= 16384 else 10
            ms = bench(lambda: linear_mlx(q, k, v, c), iters, each=True)
            print(f"[linear {dt} d{d} s{s} c{c}] ms_per_call={ms:.4f}", flush=True)
