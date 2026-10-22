# PyTorch counterparts of train_big.sc / train_big_gpu.sc: the same models,
# sizes, optimizers and step counts. fp32, then the MLP and the conv nets
# with automatic mixed precision (torch.autocast; fp16 with a GradScaler,
# bf16 without). One untimed warm-up run, then one timed run per workload.
# Prints "[workload_precision] train_ms=... last_loss=...".
#   usage: train_big.py [device]   (default cuda; cpu works but is slow)
import sys, time, torch, torch.nn as nn, torch.nn.functional as F
DEV = torch.device(sys.argv[1] if len(sys.argv) > 1 else 'cuda')
torch.manual_seed(0)

def sync():
    if DEV.type == 'cuda': torch.cuda.synchronize()

def loader(n, batch, gen):
    perm = torch.randperm(n, generator=gen).to(DEV)
    for s in range(0, n, batch):
        yield perm[s:s + batch]

AMP = ''   # '', 'fp16' (autocast + GradScaler) or 'bf16' (autocast)

def fit(model, lossfn, x, y, batch, epochs, lr=1e-3):
    o = torch.optim.Adam(model.parameters(), lr=lr)
    gen = torch.Generator().manual_seed(1)
    scaler = torch.amp.GradScaler(DEV.type) if AMP == 'fp16' else None
    dt = torch.float16 if AMP == 'fp16' else torch.bfloat16
    last = 0.0
    for e in range(epochs):
        tot = 0.0; nb = 0
        for idx in loader(x.shape[0], batch, gen):
            o.zero_grad(set_to_none=False)
            if AMP:
                with torch.autocast(DEV.type, dtype=dt):
                    loss = lossfn(model, x[idx], y[idx])
                if scaler:
                    scaler.scale(loss).backward(); scaler.step(o); scaler.update()
                else:
                    loss.backward(); o.step()
            else:
                loss = lossfn(model, x[idx], y[idx])
                loss.backward(); o.step()
            tot += loss.item(); nb += 1
        last = tot / nb
    return last

def blobs(n, classes, feat):
    c = torch.randn(classes, feat, device=DEV) * 3
    y = torch.arange(n, device=DEV) % classes
    return torch.randn(n, feat, device=DEV) + c[y], y

def shapes(n, side):
    return (torch.rand(n, 1, side, side, device=DEV) < 0.2).float(), torch.arange(n, device=DEV) % 4

blob784 = blobs(8192, 10, 784)
img28 = shapes(2048, 28)
rgb32x, rgb32y = blobs(1024, 10, 3072)
rgb32 = (rgb32x.view(-1, 3, 32, 32), rgb32y)
img32 = shapes(512, 32)

ce = lambda m, x, y: F.cross_entropy(m(x), y)

def w_mlp():
    m = nn.Sequential(nn.Linear(784, 1024), nn.ReLU(), nn.Linear(1024, 1024), nn.ReLU(), nn.Linear(1024, 10)).to(DEV)
    return fit(m, ce, *blob784, 256, 2)

def w_cnn():
    m = nn.Sequential(nn.Conv2d(1, 32, 3, 1, 1), nn.BatchNorm2d(32), nn.ReLU(), nn.MaxPool2d(2),
                      nn.Conv2d(32, 64, 3, 1, 1), nn.BatchNorm2d(64), nn.ReLU(), nn.MaxPool2d(2),
                      nn.Flatten(), nn.Linear(64 * 49, 128), nn.ReLU(), nn.Linear(128, 4)).to(DEV)
    return fit(m, ce, *img28, 128, 2)

class Block(nn.Module):
    def __init__(s, cin, cout, stride):
        super().__init__()
        s.c1 = nn.Conv2d(cin, cout, 3, stride, 1); s.b1 = nn.BatchNorm2d(cout)
        s.c2 = nn.Conv2d(cout, cout, 3, 1, 1); s.b2 = nn.BatchNorm2d(cout)
        s.proj = nn.Conv2d(cin, cout, 1, stride) if (cin != cout or stride != 1) else None
    def forward(s, x):
        h = F.relu(s.b1(s.c1(x)))
        h = s.b2(s.c2(h))
        return F.relu(h + (s.proj(x) if s.proj is not None else x))

def w_resnet():
    m = nn.Sequential(nn.Conv2d(3, 32, 3, 1, 1), nn.BatchNorm2d(32), nn.ReLU(), Block(32, 32, 1), Block(32, 64, 2),
                      Block(64, 128, 2), nn.AdaptiveAvgPool2d(1), nn.Flatten(), nn.Linear(128, 10)).to(DEV)
    return fit(m, ce, *rgb32, 128, 2)

class UNet(nn.Module):
    def __init__(s):
        super().__init__()
        s.c1 = nn.Conv2d(1, 32, 3, 1, 1); s.c2 = nn.Conv2d(32, 64, 3, 1, 1); s.c3 = nn.Conv2d(64, 128, 3, 1, 1)
        s.c4 = nn.Conv2d(192, 64, 3, 1, 1); s.c5 = nn.Conv2d(96, 32, 3, 1, 1); s.c6 = nn.Conv2d(32, 1, 1)
    def forward(s, x):
        a = F.relu(s.c1(x))
        b = F.relu(s.c2(F.max_pool2d(a, 2)))
        h = F.relu(s.c3(F.max_pool2d(b, 2)))
        h = torch.cat([F.interpolate(h, scale_factor=2, mode='bilinear', align_corners=False), b], 1)
        h = F.relu(s.c4(h))
        h = torch.cat([F.interpolate(h, scale_factor=2, mode='bilinear', align_corners=False), a], 1)
        return s.c6(F.relu(s.c5(h)))

def w_unet():
    m = UNet().to(DEV)
    return fit(m, lambda m, x, y: F.binary_cross_entropy_with_logits(m(x), x), *img32, 32, 2)

def w_dcgan():
    G = nn.Sequential(nn.ConvTranspose2d(32, 64, 7, 1, 0), nn.BatchNorm2d(64), nn.ReLU(),
                      nn.ConvTranspose2d(64, 32, 4, 2, 1), nn.BatchNorm2d(32), nn.ReLU(),
                      nn.ConvTranspose2d(32, 1, 4, 2, 1), nn.Tanh()).to(DEV)
    D = nn.Sequential(nn.Conv2d(1, 32, 4, 2, 1), nn.LeakyReLU(0.2), nn.Conv2d(32, 64, 4, 2, 1), nn.BatchNorm2d(64),
                      nn.LeakyReLU(0.2), nn.Conv2d(64, 1, 7, 1, 0)).to(DEV)
    oG = torch.optim.Adam(G.parameters(), lr=2e-4, betas=(0.5, 0.999))
    oD = torch.optim.Adam(D.parameters(), lr=2e-4, betas=(0.5, 0.999))
    x = img28[0]; gen = torch.Generator().manual_seed(1)
    last = 0.0
    for idx in loader(x.shape[0], 64, gen):
        real = x[idx]; m = real.shape[0]
        ones = torch.ones(m, 1, device=DEV); zeros = torch.zeros(m, 1, device=DEV)
        fake = G(torch.randn(m, 32, 1, 1, device=DEV)).detach()
        dl = F.binary_cross_entropy_with_logits(D(real).view(m, 1), ones) + \
             F.binary_cross_entropy_with_logits(D(fake).view(m, 1), zeros)
        oD.zero_grad(); dl.backward(); oD.step()
        gl = F.binary_cross_entropy_with_logits(D(G(torch.randn(m, 32, 1, 1, device=DEV))).view(m, 1), ones)
        oG.zero_grad(); gl.backward(); oG.step()
        last = dl.item(); gl.item()
    return last

class ConvVae(nn.Module):
    def __init__(s):
        super().__init__()
        s.enc = nn.Sequential(nn.Conv2d(1, 32, 4, 2, 1), nn.ReLU(), nn.Conv2d(32, 64, 4, 2, 1), nn.ReLU(), nn.Flatten())
        s.mu = nn.Linear(64 * 49, 16); s.lv = nn.Linear(64 * 49, 16)
        s.dec = nn.Sequential(nn.ConvTranspose2d(16, 64, 7, 1, 0), nn.ReLU(), nn.ConvTranspose2d(64, 32, 4, 2, 1),
                              nn.ReLU(), nn.ConvTranspose2d(32, 1, 4, 2, 1))
def vae_loss(v, x, y):
    h = v.enc(x); mu = v.mu(h); lv = v.lv(h)
    z = mu + torch.exp(0.5 * lv) * torch.randn_like(mu)
    out = v.dec(z.view(-1, 16, 1, 1))
    rec = F.binary_cross_entropy_with_logits(out, x, reduction='sum') / x.shape[0]
    kl = -0.5 * torch.sum(1 + lv - mu * mu - lv.exp()) / x.shape[0]
    return rec + kl

def w_convvae():
    v = ConvVae().to(DEV)
    return fit(v, vae_loss, *img28, 64, 1)

def bench(name, fn):
    fn(); sync()   # warm-up
    sync(); t0 = time.perf_counter(); loss = fn(); sync()
    print(f"[{name}] train_ms={(time.perf_counter() - t0) * 1e3:.3f} last_loss={loss:.6g}", flush=True)

for n in ['mlp', 'cnn', 'resnet', 'unet', 'dcgan', 'convvae']:
    bench(n + '_fp32', globals()['w_' + n])
for AMP in ['fp16', 'bf16']:
    for n in ['mlp', 'cnn', 'resnet', 'unet']:
        bench(n + '_' + AMP, globals()['w_' + n])
