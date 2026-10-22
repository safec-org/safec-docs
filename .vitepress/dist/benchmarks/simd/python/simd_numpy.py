import numpy as np
N = 20_000_000
a = (np.arange(N, dtype=np.int64) % 1000).astype(np.float64) * 0.001
s = np.sum(a * a)
print(f"{s:.6f}")
