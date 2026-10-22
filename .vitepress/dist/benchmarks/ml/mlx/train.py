import time
import mlx.core as mx

BATCH = 64
IN_DIM = 128
HIDDEN = 256
OUT_DIM = 64
LR = 0.001
STEPS = 100

mx.random.seed(42)

X = mx.random.uniform(-0.5, 0.5, (BATCH, IN_DIM))
target = mx.random.uniform(-0.5, 0.5, (BATCH, OUT_DIM))
W1 = mx.random.uniform(-0.5, 0.5, (IN_DIM, HIDDEN)) * 0.1
W2 = mx.random.uniform(-0.5, 0.5, (HIDDEN, OUT_DIM)) * 0.1
mx.eval(X, target, W1, W2)

def loss_fn(W1, W2, X, target):
    H = mx.maximum(X @ W1, 0.0)
    Y = H @ W2
    diff = Y - target
    return mx.sum(diff * diff)

grad_fn = mx.grad(loss_fn, argnums=(0, 1))

t0 = time.perf_counter()
last_loss = 0.0
for step in range(STEPS):
    gW1, gW2 = grad_fn(W1, W2, X, target)
    W1 = W1 - LR * gW1
    W2 = W2 - LR * gW2
    mx.eval(W1, W2)
    last_loss = loss_fn(W1, W2, X, target).item()
t1 = time.perf_counter()
train_ms = (t1 - t0) * 1000.0
print(f"[mlx] train_ms={train_ms:.3f} last_loss={last_loss:.6f}")
print(f"[mlx] throughput_samples_per_sec={(STEPS * BATCH) / (train_ms / 1000.0):.2f}")

t2 = time.perf_counter()
checksum = 0.0
for _ in range(1000):
    H2 = mx.maximum(X @ W1, 0.0)
    Y2 = H2 @ W2
    mx.eval(Y2)
    checksum += Y2[0, 0].item()
t3 = time.perf_counter()
inf_ms = (t3 - t2) * 1000.0
print(f"[mlx] inference_ms_per_1000={inf_ms:.3f} checksum={checksum:.6f}")
