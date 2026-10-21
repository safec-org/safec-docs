"""Convolution in PyTorch: the same layer and U-Net as ../safec/conv_cuda.sc.

usage: conv.py cpu|cuda|mps

  conv64   Conv2d 64 -> 64, 3x3, stride 1, pad 1, 56 x 56, + bias
  unet128  the std/ml/unet.h network (conv-relu-maxpool x2, bottleneck,
           nearest upsample + concat skip + conv, x2) at 128 x 128

On CUDA every row runs with cudnn.benchmark = True (cuDNN times its
algorithms per shape, as gpu_cudnn does), in fp32 and with TF32 allowed;
'fp32 heuristic' is PyTorch's default cudnn.benchmark = False. On the CPU
only batch 1 runs. Inference only, under torch.inference_mode().
"""
import sys, time
import torch
import torch.nn as nn
import torch.nn.functional as F

dev = sys.argv[1] if len(sys.argv) > 1 else "cuda"
torch.manual_seed(42)


class UNet(nn.Module):
    def __init__(self, cin, c1, c2, cout):
        super().__init__()
        self.enc1 = nn.Conv2d(cin, c1, 3, padding=1)
        self.enc2 = nn.Conv2d(c1, c2, 3, padding=1)
        self.mid = nn.Conv2d(c2, c2, 3, padding=1)
        self.dec1 = nn.Conv2d(2 * c2, c1, 3, padding=1)
        self.dec2 = nn.Conv2d(2 * c1, cout, 3, padding=1)

    def forward(self, x):
        e1 = F.relu(self.enc1(x))
        e2 = F.relu(self.enc2(F.max_pool2d(e1, 2)))
        b = F.relu(self.mid(F.max_pool2d(e2, 2)))
        d1 = F.relu(self.dec1(torch.cat([F.interpolate(b, scale_factor=2, mode="nearest"), e2], 1)))
        return self.dec2(torch.cat([F.interpolate(d1, scale_factor=2, mode="nearest"), e1], 1))


def sync():
    if dev == "cuda":
        torch.cuda.synchronize()
    elif dev == "mps":
        torch.mps.synchronize()


def bench(fn, iters):
    for _ in range(5):
        fn()
    sync()
    t0 = time.perf_counter()
    for _ in range(iters):
        fn()
    sync()
    return (time.perf_counter() - t0) * 1000 / iters


conv = nn.Conv2d(64, 64, 3, padding=1).to(dev)
unet = UNet(3, 32, 64, 3).to(dev)

with torch.inference_mode():
    if dev == "cpu":
        modes = [("cpu", False, False)]
    elif dev == "mps":
        modes = [("mps", False, False)]
    else:
        modes = [("fp32", False, True), ("tf32", True, True), ("fp32 heuristic", False, False)]
    for name, tf32, autotune in modes:
        torch.backends.cudnn.allow_tf32 = tf32
        torch.backends.cuda.matmul.allow_tf32 = tf32
        torch.backends.cudnn.benchmark = autotune
        for b in ([1] if dev == "cpu" else [1, 32]):
            x = torch.rand(b, 64, 56, 56, device=dev) * 2 - 1
            ms = bench(lambda: conv(x), 20 if dev == "cpu" else (2000 if b == 1 else 300))
            print(f"[conv64 b={b} {name}] ms_per_batch={ms:.4f} ms_per_image={ms / b:.5f}", flush=True)
        for b in ([1] if dev == "cpu" else [1, 16]):
            x = torch.rand(b, 3, 128, 128, device=dev) * 2 - 1
            ms = bench(lambda: unet(x), 10 if dev == "cpu" else (1000 if b == 1 else 300))
            print(f"[unet128 b={b} {name}] ms_per_batch={ms:.4f} ms_per_image={ms / b:.5f}", flush=True)
