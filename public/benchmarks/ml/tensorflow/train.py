import sys
import time
import tensorflow as tf

BATCH = 64
IN_DIM = 128
HIDDEN = 256
OUT_DIM = 64
LR = 0.001
STEPS = 100

def run(device_name):
    tf.random.set_seed(42)
    dev = "/CPU:0" if device_name == "cpu" else "/GPU:0"
    with tf.device(dev):
        X = tf.random.uniform((BATCH, IN_DIM)) - 0.5
        target = tf.random.uniform((BATCH, OUT_DIM)) - 0.5
        W1 = tf.Variable((tf.random.uniform((IN_DIM, HIDDEN)) - 0.5) * 0.1)
        W2 = tf.Variable((tf.random.uniform((HIDDEN, OUT_DIM)) - 0.5) * 0.1)

        t0 = time.perf_counter()
        last_loss = 0.0
        for step in range(STEPS):
            with tf.GradientTape() as tape:
                H = tf.nn.relu(X @ W1)
                Y = H @ W2
                diff = Y - target
                loss = tf.reduce_sum(diff * diff)
            gW1, gW2 = tape.gradient(loss, [W1, W2])
            W1.assign_sub(LR * gW1)
            W2.assign_sub(LR * gW2)
            last_loss = float(loss.numpy())
        t1 = time.perf_counter()
        train_ms = (t1 - t0) * 1000.0
        print(f"[{device_name}] train_ms={train_ms:.3f} last_loss={last_loss:.6f}")
        print(f"[{device_name}] throughput_samples_per_sec={(STEPS * BATCH) / (train_ms / 1000.0):.2f}")

        t2 = time.perf_counter()
        checksum = 0.0
        for _ in range(1000):
            H2 = tf.nn.relu(X @ W1)
            Y2 = H2 @ W2
            checksum += float(Y2[0, 0].numpy())
        t3 = time.perf_counter()
        inf_ms = (t3 - t2) * 1000.0
        print(f"[{device_name}] inference_ms_per_1000={inf_ms:.3f} checksum={checksum:.6f}")

if __name__ == "__main__":
    device_name = sys.argv[1] if len(sys.argv) > 1 else "cpu"
    run(device_name)
