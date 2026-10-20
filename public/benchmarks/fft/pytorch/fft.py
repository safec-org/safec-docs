"""FFT in PyTorch: the same transforms as ../safec/fft_cuda.sc.

usage: fft.py cpu|cuda

  fft1d   one complex128 transform of length 2^20, from and back to a CPU
          tensor (on CUDA: .to("cuda") -> torch.fft.fft -> .cpu())
  fft2d   one 1024 x 1024 complex128 2D transform, same round trip
  batch   1024 complex64 transforms of length 4096, data on the device
  rfft    1024 real float32 transforms of length 4096, data on the device

cuFFT does not use TF32, so there is one CUDA mode.
"""
import sys, time
import torch

dev = sys.argv[1] if len(sys.argv) > 1 else "cuda"
torch.manual_seed(42)


def sync():
    if dev == "cuda":
        torch.cuda.synchronize()


def bench(fn, iters):
    for _ in range(5):
        fn()
    sync()
    t0 = time.perf_counter()
    for _ in range(iters):
        fn()
    sync()
    return (time.perf_counter() - t0) * 1000 / iters


def rand_c(*shape, dtype, device):
    return torch.complex(torch.rand(*shape, dtype=dtype) * 2 - 1, torch.rand(*shape, dtype=dtype) * 2 - 1).to(device)


host_iters = 5 if dev == "cpu" else 50
dev_iters = 5 if dev == "cpu" else 1000
with torch.inference_mode():
    x = rand_c(1 << 20, dtype=torch.float64, device="cpu")
    ms = bench(lambda: torch.fft.fft(x.to(dev)).cpu(), host_iters)
    print(f"[fft1d n=1048576 c128 {dev}] ms_per_call={ms:.4f}", flush=True)
    img = rand_c(1024, 1024, dtype=torch.float64, device="cpu")
    ms = bench(lambda: torch.fft.fft2(img.to(dev)).cpu(), host_iters)
    print(f"[fft2d 1024x1024 c128 {dev}] ms_per_call={ms:.4f}", flush=True)
    b = rand_c(1024, 4096, dtype=torch.float32, device=dev)
    ms = bench(lambda: torch.fft.fft(b, dim=-1), dev_iters)
    print(f"[batch 1024x4096 c2c {dev}] ms_per_call={ms:.4f}", flush=True)
    r = (torch.rand(1024, 4096) * 2 - 1).to(dev)
    ms = bench(lambda: torch.fft.rfft(r, dim=-1), dev_iters)
    print(f"[rfft 1024x4096 r2c {dev}] ms_per_call={ms:.4f}", flush=True)
