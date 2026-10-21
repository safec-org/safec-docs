# PyTorch counterparts of benchmarks/ml_nn/safec/train_nn.sc: the same
# models, shapes, optimizers and step counts, fp32.
#   usage: train_nn.py [cpu|cuda|mps]   (cuda is PyTorch-ROCm on an AMD GPU)
# One untimed warm-up run, then one timed run per workload. Prints
# "[workload] train_ms=... last_loss=...". On the GPU, the DQN replay
# buffer and the PPO GAE recursion stay on the host and only batches move,
# as GPU implementations usually do.
import math, sys, time, torch, torch.nn as nn, torch.nn.functional as F
torch.manual_seed(0)
DEV = torch.device(sys.argv[1] if len(sys.argv) > 1 else 'cpu')
g = {}

def loader(x, y, batch, gen):
    n = x.shape[0]
    perm = torch.randperm(n, generator=gen, device='cpu').to(DEV)
    for s in range(0, n, batch):
        idx = perm[s:s + batch]
        yield x[idx], y[idx]

def mlp(i, h, o, act=nn.ReLU):
    return nn.Sequential(nn.Linear(i, h), act(), nn.Linear(h, h), act(), nn.Linear(h, o))

def fit(model, params, lossfn, x, y, lr, batch, epochs, opt=torch.optim.Adam, **kw):
    o = opt(params, lr=lr, **kw)
    gen = torch.Generator(device='cpu').manual_seed(1)
    last = 0.0
    for e in range(epochs):
        tot = 0.0; nb = 0
        for xb, yb in loader(x, y, batch, gen):
            loss = lossfn(model, xb, yb)
            o.zero_grad(set_to_none=False)
            loss.backward()
            o.step()
            tot += loss.item(); nb += 1
        last = tot / nb
    return last

def xor_data(n):
    x = torch.rand(n, 2, device=DEV) * 2 - 1
    y = ((x[:, 0] > 0) != (x[:, 1] > 0)).long()
    return x, y

def w_mlp_small():
    m = mlp(2, 32, 2).to(DEV)
    return fit(m, m.parameters(), lambda m, x, y: F.cross_entropy(m(x), y), *g['xor'], 0.01, 32, 30)

def w_mlp_big():
    m = mlp(784, 256, 10).to(DEV)
    return fit(m, m.parameters(), lambda m, x, y: F.cross_entropy(m(x), y), *g['big'], 0.001, 128, 3)

class Vae(nn.Module):
    def __init__(s):
        super().__init__()
        s.enc = nn.Sequential(nn.Linear(100, 128), nn.ReLU())
        s.mu = nn.Linear(128, 4); s.lv = nn.Linear(128, 4)
        s.dec = nn.Sequential(nn.Linear(4, 128), nn.ReLU(), nn.Linear(128, 100))
def vae_loss(v, x, y):
    h = v.enc(x); mu = v.mu(h); lv = v.lv(h)
    z = mu + torch.exp(0.5 * lv) * torch.randn_like(mu)
    out = v.dec(z)
    rec = F.binary_cross_entropy_with_logits(out, x, reduction='sum') / x.shape[0]
    kl = -0.5 * torch.sum(1 + lv - mu * mu - lv.exp()) / x.shape[0]
    return rec + kl
def w_vae():
    v = Vae().to(DEV)
    return fit(v, v.parameters(), vae_loss, g['bits'], g['bits'], 0.001, 32, 10)

def w_gan():
    G = nn.Sequential(nn.Linear(4, 32), nn.ReLU(), nn.Linear(32, 32), nn.ReLU(), nn.Linear(32, 2))
    D = nn.Sequential(nn.Linear(2, 32), nn.LeakyReLU(0.2), nn.Linear(32, 32), nn.LeakyReLU(0.2), nn.Linear(32, 1))
    G.to(DEV); D.to(DEV)
    oG = torch.optim.Adam(G.parameters(), lr=5e-4, betas=(0.5, 0.999))
    oD = torch.optim.Adam(D.parameters(), lr=5e-4, betas=(0.5, 0.999))
    x = g['gauss']; gen = torch.Generator(device='cpu').manual_seed(1)
    for e in range(10):
        for xb, _ in loader(x, x, 64, gen):
            m = xb.shape[0]
            ones = torch.ones(m, 1, device=DEV); zeros = torch.zeros(m, 1, device=DEV)
            fake = G(torch.randn(m, 4, device=DEV)).detach()
            dl = F.binary_cross_entropy_with_logits(D(xb), ones) + F.binary_cross_entropy_with_logits(D(fake), zeros)
            oD.zero_grad(); dl.backward(); oD.step()
            gl = F.binary_cross_entropy_with_logits(D(G(torch.randn(m, 4, device=DEV))), ones)
            oG.zero_grad(); gl.backward(); oG.step()
            last = dl.item(); gl.item()
    return last

def w_dqn():
    q = mlp(4, 64, 2).to(DEV); t = mlp(4, 64, 2).to(DEV); t.load_state_dict(q.state_dict())
    opt = torch.optim.Adam(q.parameters(), lr=5e-4)
    cap = 10000
    C = dict(device='cpu')   # replay buffer in host memory; batches go to DEV
    O = torch.zeros(cap, 4, **C); N = torch.zeros(cap, 4, **C); A = torch.zeros(cap, dtype=torch.long, **C)
    R = torch.zeros(cap, **C); T = torch.zeros(cap, **C)
    size = 0; last = 0.0
    for i in range(1064):
        o = torch.randn(4, **C); n = torch.randn(4, **C)
        a = int(torch.randint(2, (), **C))
        # acting is part of every environment step (greedy pass, no grad)
        with torch.no_grad(): q(o[None].to(DEV)).argmax().item()
        O[i % cap] = o; N[i % cap] = n; A[i % cap] = a; R[i % cap] = float(torch.rand((), **C)); T[i % cap] = float(torch.randint(10, (), **C) == 0)
        size = min(size + 1, cap)
        if size < 64: continue
        j = torch.randint(size, (64,), **C)
        Oj, Nj, Aj, Rj, Tj = O[j].to(DEV), N[j].to(DEV), A[j].to(DEV), R[j].to(DEV), T[j].to(DEV)
        with torch.no_grad():
            best = q(Nj).argmax(1, keepdim=True)
            y = Rj + 0.99 * (1 - Tj) * t(Nj).gather(1, best).squeeze(1)
        qsa = q(Oj).gather(1, Aj[:, None]).squeeze(1)
        loss = F.huber_loss(qsa, y, delta=1.0)
        opt.zero_grad(); loss.backward()
        nn.utils.clip_grad_norm_(q.parameters(), 10.0)
        opt.step()
        with torch.no_grad():
            for pt, pq in zip(t.parameters(), q.parameters()):
                pt.mul_(0.995).add_(pq, alpha=0.005)
        last = loss.item()
    return last

def w_ppo():
    pol = mlp(4, 64, 2, nn.Tanh).to(DEV); val = mlp(4, 64, 1, nn.Tanh).to(DEV)
    params = list(pol.parameters()) + list(val.parameters())
    opt = torch.optim.Adam(params, lr=1e-3)
    last = 0.0
    for u in range(5):
        N = 1024
        O = torch.randn(N, 4, device=DEV); Nx = torch.randn(N, 4, device=DEV)
        A = torch.randint(2, (N,), device=DEV); R = torch.ones(N, device=DEV); T = (torch.randint(20, (N,), device=DEV) == 0).float()
        with torch.no_grad():
            V = val(O).squeeze(1); Vn = val(Nx).squeeze(1)
            lpold = F.log_softmax(pol(O), 1).gather(1, A[:, None]).squeeze(1)
            # the GAE recursion runs on the host (scalar loop)
            Rc, Tc, Vc, Vnc = R.cpu().tolist(), T.cpu().tolist(), V.cpu().tolist(), Vn.cpu().tolist()
            advl = [0.0] * N; gae = 0.0
            for t in reversed(range(N)):
                d = Rc[t] + 0.99 * (1 - Tc[t]) * Vnc[t] - Vc[t]
                gae = d + 0.99 * 0.95 * (1 - Tc[t]) * gae
                advl[t] = gae
            adv = torch.tensor(advl, device=DEV)
            ret = adv + V
            adv = (adv - adv.mean()) / (adv.std(unbiased=False) + 1e-8)
        for e in range(4):
            perm = torch.randperm(N, device=DEV)
            for s in range(0, N, 64):
                i = perm[s:s + 64]
                lp_all = F.log_softmax(pol(O[i]), 1)
                lp = lp_all.gather(1, A[i][:, None]).squeeze(1)
                ent = -(lp_all.exp() * lp_all).sum(1).mean()
                ratio = torch.exp(lp - lpold[i])
                pl = -torch.min(ratio * adv[i], ratio.clamp(0.8, 1.2) * adv[i]).mean()
                vl = F.mse_loss(val(O[i]).squeeze(1), ret[i])
                loss = pl + 0.5 * vl - 0.01 * ent
                opt.zero_grad(); loss.backward()
                nn.utils.clip_grad_norm_(params, 0.5)
                opt.step()
                last = loss.item()
    return last

class LoraMlp(nn.Module):
    def __init__(s, base, r=4, alpha=4.0):
        super().__init__()
        s.base = base
        for p in base.parameters(): p.requires_grad_(False)
        s.lins = [m for m in base if isinstance(m, nn.Linear)]
        s.A = nn.ParameterList([nn.Parameter(torch.empty(l.in_features, r).uniform_(-1 / math.sqrt(l.in_features), 1 / math.sqrt(l.in_features))) for l in s.lins])
        s.B = nn.ParameterList([nn.Parameter(torch.zeros(r, l.out_features, device=DEV)) for l in s.lins])
        s.scale = alpha / r
    def forward(s, x):
        for k, l in enumerate(s.lins):
            x = l(x) + s.scale * (x @ s.A[k]) @ s.B[k]
            if k + 1 < len(s.lins): x = F.relu(x)
        return x
def w_lora():
    ft = LoraMlp(mlp(2, 64, 2)).to(DEV)
    return fit(ft, list(ft.A) + list(ft.B), lambda m, x, y: F.cross_entropy(m(x), y), *g['xor'], 0.01, 32, 30, opt=torch.optim.AdamW)

class ConvAE(nn.Module):
    def __init__(s):
        super().__init__()
        s.c1 = nn.Conv2d(1, 16, 3, 2, 1); s.c2 = nn.Conv2d(16, 32, 3, 2, 1)
        s.t3 = nn.ConvTranspose2d(32, 16, 4, 2, 1); s.t4 = nn.ConvTranspose2d(16, 1, 4, 2, 1)
    def forward(s, x):
        h = F.relu(s.c1(x.view(-1, 1, 16, 16))); h = F.relu(s.c2(h)); h = F.relu(s.t3(h))
        return s.t4(h).view(x.shape[0], -1)
def w_conv_ae():
    m = ConvAE().to(DEV)
    return fit(m, m.parameters(), lambda m, x, y: F.binary_cross_entropy_with_logits(m(x), x), g['img'], g['img'], 0.001, 32, 5)

def sync():
    if DEV.type == 'cuda': torch.cuda.synchronize()
    elif DEV.type == 'mps': torch.mps.synchronize()

def bench(name, fn):
    fn(); sync()                                 # warm-up
    sync(); t0 = time.perf_counter(); loss = fn(); sync()
    print(f"[{name}] train_ms={(time.perf_counter() - t0) * 1e3:.3f} last_loss={loss:.6g}", flush=True)

g['xor'] = xor_data(512)
g['big'] = (torch.randn(2048, 784, device=DEV) + torch.randn(10, 784, device=DEV).repeat(205, 1)[:2048] * 3, torch.arange(2048, device=DEV) % 10)
g['bits'] = (torch.rand(512, 100, device=DEV) < 0.2).float()
g['gauss'] = torch.randn(1024, 2, device=DEV) * 0.5
g['img'] = (torch.rand(512, 256, device=DEV) < 0.2).float()
for n in ['mlp_small', 'mlp_big', 'vae', 'gan', 'dqn', 'ppo', 'lora', 'conv_ae']:
    bench(n, globals()['w_' + n])
