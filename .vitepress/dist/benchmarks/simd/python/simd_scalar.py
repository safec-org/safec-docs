N = 20_000_000
a = [0.0] * N
for i in range(N):
    a[i] = (i % 1000) * 0.001
s = 0.0
for i in range(N):
    s += a[i] * a[i]
print(f"{s:.6f}")
