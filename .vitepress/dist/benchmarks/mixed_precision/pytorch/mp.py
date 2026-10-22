"""std::ml CUDA mixed-precision / 16-bit ops benchmark, PyTorch side (../safec/mp_cuda.sc). bf16, H200."""
import time, torch, torch.nn.functional as F
torch.backends.cudnn.benchmark = True
dev = "cuda"; bf = torch.bfloat16
def t(fn, iters):
    for _ in range(3): fn()
    torch.cuda.synchronize(); t0 = time.perf_counter()
    for _ in range(iters): fn()
    torch.cuda.synchronize(); return (time.perf_counter() - t0) * 1e6 / iters
sizes = [50257*768, 1024*768]
for _ in range(12):
    sizes += [768, 768, 768*2304, 2304, 768*768, 768, 768, 768, 768*3072, 3072, 3072*768, 768]
sizes += [768, 768]
with torch.no_grad():
    ps = [torch.zeros(n, device=dev, requires_grad=True) for n in sizes]
    for p in ps: p.grad = torch.zeros_like(p)
    hs = [torch.empty(n, device=dev, dtype=bf) for n in sizes]
    print(f"model: {len(sizes)} tensors, {sum(sizes)} params")
    for name, kw in (("fused", dict(fused=True)), ("foreach", dict(foreach=True))):
        opt = torch.optim.AdamW(ps, lr=1e-4, weight_decay=0.01, **kw)
        print(f"[torch adamw {name}+bf16 copy] us={t(lambda: (opt.step(), torch._foreach_copy_(hs, ps)), 20):.1f}")
        print(f"[torch adamw {name} only] us={t(lambda: opt.step(), 20):.1f}")
    gs = [p.grad for p in ps]; inv = torch.ones((), device=dev); found = torch.zeros((), device=dev)
    print(f"[torch unscale fp32 inplace] us={t(lambda: torch._amp_foreach_non_finite_check_and_unscale_(gs, found, inv), 20):.1f}")
    print(f"[torch to_bf16 many] us={t(lambda: torch._foreach_copy_(hs, ps), 20):.1f}")
    n = 64 * 1024 * 1024
    a = torch.zeros(n, device=dev, dtype=bf); b = torch.zeros_like(a); o = torch.empty_like(a)
    print(f"[torch half add 64M] us={t(lambda: torch.add(a, b, out=o), 50):.1f}")
    print(f"[torch half axpy 64M] us={t(lambda: torch.add(a, b, alpha=0.5, out=o), 50):.1f}")
    y = torch.zeros(64, 256, 56, 56, device=dev, dtype=bf); bias = torch.zeros(256, device=dev, dtype=bf)
    print(f"[torch half bias+relu 64x256x56x56] us={t(lambda: y.add_(bias.view(1, -1, 1, 1)).relu_(), 50):.1f}")
    x = torch.zeros(64, 64, 112, 112, device=dev, dtype=bf)
    print(f"[torch half maxpool 3x3s2 64x64x112x112] us={t(lambda: F.max_pool2d(x, 3, 2), 50):.1f}")
    print(f"[torch half avgpool 2x2s2 64x256x56x56] us={t(lambda: F.avg_pool2d(y, 2, 2), 50):.1f}")
    for ci, co, k, p, hw in ((64, 64, 3, 1, 56), (128, 128, 3, 1, 28), (256, 256, 3, 1, 14), (256, 64, 1, 0, 56)):
        x = torch.randn(32, ci, hw, hw, device=dev, dtype=bf); w = torch.randn(co, ci, k, k, device=dev, dtype=bf) * 0.05
        bb = torch.zeros(co, device=dev, dtype=bf)
        us = t(lambda: F.relu_(F.conv2d(x, w, bb, padding=p)), 50)
        fl = 2 * 32 * hw * hw * ci * co * k * k
        xl, wl = x.to(memory_format=torch.channels_last), w.to(memory_format=torch.channels_last)
        us2 = t(lambda: F.relu_(F.conv2d(xl, wl, bb, padding=p)), 50)
        print(f"[torch half conv+relu {ci}->{co} k{k} {hw}x{hw} b32] us={us:.1f} tflops={fl/(us*1e6):.1f}  channels_last us={us2:.1f}")
