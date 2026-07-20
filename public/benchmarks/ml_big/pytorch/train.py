import sys
import time
import torch

BATCH = 128
IN_DIM = 512
HIDDEN = 1024
OUT_DIM = 256
LR = 0.001
STEPS = 50

def run(device_name):
    torch.manual_seed(42)
    device = torch.device(device_name)

    X = (torch.rand(BATCH, IN_DIM, device=device) - 0.5)
    target = (torch.rand(BATCH, OUT_DIM, device=device) - 0.5)

    W1 = ((torch.rand(IN_DIM, HIDDEN, device=device) - 0.5) * 0.1).requires_grad_(True)
    W2 = ((torch.rand(HIDDEN, OUT_DIM, device=device) - 0.5) * 0.1).requires_grad_(True)

    if device_name == "mps":
        torch.mps.synchronize()
    t0 = time.perf_counter()
    last_loss = 0.0
    for step in range(STEPS):
        if W1.grad is not None:
            W1.grad = None
        if W2.grad is not None:
            W2.grad = None
        H = torch.relu(X @ W1)
        Y = H @ W2
        diff = Y - target
        loss = (diff * diff).sum()
        loss.backward()
        with torch.no_grad():
            W1 -= LR * W1.grad
            W2 -= LR * W2.grad
        last_loss = loss.item()
    if device_name == "mps":
        torch.mps.synchronize()
    t1 = time.perf_counter()
    train_ms = (t1 - t0) * 1000.0
    print(f"[{device_name}] train_ms={train_ms:.3f} last_loss={last_loss:.6f}")
    print(f"[{device_name}] throughput_samples_per_sec={(STEPS * BATCH) / (train_ms / 1000.0):.2f}")

    # Inference-only
    with torch.no_grad():
        if device_name == "mps":
            torch.mps.synchronize()
        t2 = time.perf_counter()
        checksum = 0.0
        for _ in range(200):
            H2 = torch.relu(X @ W1)
            Y2 = H2 @ W2
            checksum += Y2[0, 0].item()
        if device_name == "mps":
            torch.mps.synchronize()
        t3 = time.perf_counter()
    inf_ms = (t3 - t2) * 1000.0
    print(f"[{device_name}] inference_ms_per_200={inf_ms:.3f} checksum={checksum:.6f}")

if __name__ == "__main__":
    device_name = sys.argv[1] if len(sys.argv) > 1 else "cpu"
    run(device_name)
