# Benchmarks

SafeC against C, C++, Rust, Zig, Go and Python on a few classic microbenchmarks (in the spirit of [The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html)), plus the standard library's collections, HTTP server, ML tensors and allocator. One machine, best of 3 runs: read every number as "roughly this, on this machine", not as a ranking of languages.

## At a glance {#at-a-glance}

Release-build wall-clock time (lower is better; fastest in bold). SafeC's binary-trees rows use its `region`/`arena` allocation; the heap version is in [binary-trees](#binary-trees-allocationdeallocation-stress).

| Benchmark | SafeC | C | C++ | Rust | Zig | Go | Python |
|---|---|---|---|---|---|---|---|
| [fib(37)](#fib37-recursive-fibonacci) | 0.042s | 0.042s | **0.041s** | **0.041s** | 0.042s | 0.089s | 1.992s |
| [n-body](#n-body-5-body-orbital-simulation) | **0.098s** | **0.098s** | **0.098s** | 0.106s | 0.110s | 0.105s | 7.716s |
| [binary-trees](#binary-trees-allocationdeallocation-stress) | **0.140s** | 0.804s | 0.937s | 0.910s | 0.850s | 0.722s | 18.169s |
| [binary-trees, 8 threads](#multithreaded-binary-trees-8-threads) | **0.054s** | 0.225s | 0.239s | 0.234s | 0.227s | 0.255s | 23.034s |
| [SIMD](#simd-sum-of-squares-over-20000000-f64-values) (explicit vectors) | 0.078s | 0.077s | **0.076s** | 0.077s | **0.076s** | — | — |
| [Web service](#web-service-json-hello-world-endpoint) (req/s, higher is better) | **25,216** | 24,618 | 24,870 | 24,814 | 24,828 | 23,573 | 5,047 |

- **Machine learning** (2-layer MLP, [details](#machine-learning-small-mlp-training-and-inference)): on the MI100, SafeC trains the small model in 7.2 ms vs PyTorch-ROCm's 20.1 ms, and the big one in 8.3 ms vs 10.1 ms. On CPU, PyTorch is faster (14.1 ms vs SafeC + OpenBLAS 29.6 ms).
- **Collections** ([details](#collections-stdcollections-throughput-1000000-elements)): `vec_sort` is 1.9× faster than `qsort` and the pooled list 1.9× faster than a `malloc`-per-node one; `Vec` push and `HashMap` are 1.2–1.6× slower than plain C.
- **Allocator** ([details](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree)): `std::alloc` is 1.8× faster than `malloc` when blocks are reused, 1.4× slower for a million live blocks.

## Methodology {#methodology}

- **Machine**: AMD Ryzen 7 7800X3D (8 cores / 16 threads), AMD Instinct MI100 (gfx908, 32 GB HBM2), Ubuntu 24.04.5, native Linux — `safec` 1.0.0 · clang 21.1.8 (C/C++ and SafeC's backend) · Rust 1.100.0-nightly · Go 1.27.2 · Zig 0.16.0 · Python 3.12.3 (+ NumPy 2.5.4) · PyTorch 2.9.1+rocm6.4 · TensorFlow (CPU) · OpenBLAS (pthreads) · ROCm 6.4 (HIP, hiprtc, rocBLAS).
- **Build flags**: `-O2` for C/C++ and SafeC (SafeC compiles to LLVM IR, then `clang -O2`, as `safeguard build --release` does), `zig build-exe -O ReleaseFast`, `rustc -O`, plain `go build`. SafeC compile time is measured with `--no-incremental`, so it's a cold compile like the others. Debug-build (`-O0`) times are in the results JSON.
- **Timing**: best (lowest) wall-clock of 3 runs. **Memory**: peak RSS of the same runs from GNU `/usr/bin/time -f %M` (the benchmarked process's own peak; a parent's `wait4()` would report the forking interpreter's peak on Linux, which keeps `ru_maxrss` across `exec`). Web servers' peak is `VmHWM` from `/proc/<pid>/status`, read just before shutdown.
- **Correctness**: every binary's output is checked against the known-correct value before being included.
- **Reproduce**: [`run_bench.py`](/benchmarks/run_bench.py) builds and runs everything and writes a JSON file; this page's numbers are [`results/linux-7800x3d-mi100.json`](/benchmarks/results/linux-7800x3d-mi100.json).
- Source files are linked under each result.

## fib(37) — recursive Fibonacci {#fib37-recursive-fibonacci}

Naive recursive Fibonacci — pure function-call and integer-arithmetic overhead, no allocation, no I/O.

| Language | Time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.042s | 1.9 MB | 0.037s |
| C | 0.042s | 1.9 MB | 0.031s |
| C++ | **0.041s (fastest)** | 3.8 MB | 0.049s |
| Rust | **0.041s (fastest)** | 2.0 MB | 0.050s |
| Zig | 0.042s | **0.5 MB (leanest)** | 8.19s |
| Go | 0.089s | 2.0 MB | **0.024s** |
| Python | 1.992s | 10.4 MB | N/A (interpreted) |

Everything that goes through LLVM lands within 4% of each other in release; the differences are noise.

::: details Sources
[fib.sc](/benchmarks/fib/safec/fib.sc) · [fib.c](/benchmarks/fib/c/fib.c) · [fib.cpp](/benchmarks/fib/cpp/fib.cpp) · [fib.rs](/benchmarks/fib/rust/fib.rs) · [fib.zig](/benchmarks/fib/zig/fib.zig) · [fib.go](/benchmarks/fib/go/fib.go) · [fib.py](/benchmarks/fib/python/fib.py)
:::

## n-body — 5-body orbital simulation {#n-body-5-body-orbital-simulation}

The classic Benchmarks Game n-body test (Sun/Jupiter/Saturn/Uranus/Neptune), 2,000,000 steps. Floating-point heavy, no allocation, tiny working set.

| Language | Time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | **0.098s (fastest)** | 2.0 MB | 0.045s |
| C | **0.098s (fastest)** | 2.0 MB | 0.044s |
| C++ | **0.098s (fastest)** | 3.8 MB | 0.096s |
| Rust | 0.106s | 2.0 MB | 0.071s |
| Zig | 0.110s | **0.5 MB (leanest)** | 8.28s |
| Go | 0.105s | 2.0 MB | **0.025s** |
| Python | 7.716s | 11.0 MB | N/A (interpreted) |

::: details Sources
[nbody.sc](/benchmarks/nbody/safec/nbody.sc) · [nbody.c](/benchmarks/nbody/c/nbody.c) · [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp) · [nbody.rs](/benchmarks/nbody/rust/nbody.rs) · [nbody.zig](/benchmarks/nbody/zig/nbody.zig) · [nbody.go](/benchmarks/nbody/go/nbody.go) · [nbody.py](/benchmarks/nbody/python/nbody.py)
:::

## binary-trees — allocation/deallocation stress {#binary-trees-allocationdeallocation-stress}

Builds and discards millions of small binary trees (max depth 18) — exercises memory management rather than arithmetic. The main SafeC entry uses `region`/`arena<R>` (bump-pointer allocation, `arena_reset<R>()` discards a whole region in O(1)) instead of the heap — this workload is all short-lived, same-scope allocations, exactly what regions are for. The **SafeC (heap)** row is the same program with `new` / `std::dealloc`, for a like-for-like comparison with the other languages' `malloc`/`Box`/GC.

| Language | Time | Peak memory | Compile time |
|---|---|---|---|
| SafeC (arena) | **0.140s (fastest)** | **25.8 MB (leanest)** | 0.044s |
| SafeC (heap) | 0.713s | 50.9 MB | 0.044s |
| C | 0.804s | 34.0 MB | 0.038s |
| C++ | 0.937s | 35.8 MB | 0.055s |
| Rust | 0.910s | 33.9 MB | 0.066s |
| Zig | 0.850s | 33.8 MB | 8.17s |
| Go | 0.722s | 36.3 MB | **0.027s** |
| Python | 18.169s | 82.7 MB | N/A (interpreted) |

The heap variant is faster than C because `std::alloc` caches freed blocks per size class (see [Memory allocation](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree)), but uses more memory: each block carries a 16-byte safety header, and the cache keeps freed blocks instead of returning them.

::: details Sources
[binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc) · [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c) · [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp) · [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs) · [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig) · [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go) · [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)
:::

## Multithreaded — binary-trees, 8 threads {#multithreaded-binary-trees-8-threads}

Same binary-trees workload, parallelized across 8 worker threads — each thread builds/checksums an independent slice of the tree count at a given depth, joined before the next depth. Release only. SafeC (arena) uses one `region`/`arena<R>` per thread (arena state isn't shared or locked); SafeC (heap) uses `new` / `std::dealloc`.

| Language | 8-thread time | Peak memory | Speedup vs. 1 thread |
|---|---|---|---|
| SafeC (arena) | **0.054s (fastest)** | **98.1 MB (leanest)** | 2.58× |
| SafeC (heap) | 0.263s | 260.0 MB | 2.71× |
| C | 0.225s | 161.6 MB | 3.57× |
| C++ | 0.239s | 163.5 MB | 3.92× |
| Rust | 0.234s | 162.0 MB | 3.89× |
| Zig | 0.227s | 164.0 MB | 3.75× |
| Go | 0.255s | 130.1 MB | 2.83× |
| Python | 23.034s | 364.3 MB | 0.79× (slower than 1 thread) |

The heap variant's memory is high because each thread's size-class cache keeps its own freed blocks.

::: details Sources
[binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc) · [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c) · [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp) · [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs) · [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig) · [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go) · [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)
:::

## SIMD — sum of squares over 20,000,000 f64 values {#simd-sum-of-squares-over-20000000-f64-values}

Plain scalar loop vs. each language's explicit vector type at `-O2`/release — isolates what explicit SIMD buys *on top of* the backend's auto-vectorizer, not "vectorized vs. deliberately crippled." SafeC: native `vec<double,4>` (lowers to LLVM's target-generic `FixedVectorType`, no per-architecture source needed). C/C++: GCC/Clang vector extensions. Zig: `@Vector`. Rust: stable-channel intrinsics (`std::simd` needs nightly features) — SSE2 on x86-64 ([simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs)), NEON on AArch64 ([simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs)). Go has no portable SIMD type, scalar only.

| Language | Scalar | Explicit SIMD | Speedup |
|---|---|---|---|
| SafeC | 0.086s | 0.078s | 1.10× |
| C | 0.085s | 0.077s | 1.11× |
| C++ | 0.082s | **0.076s (fastest)** | 1.07× |
| Rust | 0.083s | 0.077s | 1.07× |
| Zig | **0.075s (fastest)** | **0.076s (fastest)** | 0.99× |
| Go | 0.086s | N/A | — |
| Python | 3.010s | 0.190s (NumPy) | 15.9× |

This loop is memory-bound on the 7800X3D (160 MB of doubles streams far past the 96 MB L3), so explicit SIMD buys little over the auto-vectorized scalar loop; the whole table is within ~10 ms, about the size of process-startup noise.

::: details Sources
[simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc) · [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c) · [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp) · [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs) · [simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs) · [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig) · [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go) · [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)
:::

## Collections — std::collections throughput (1,000,000 elements) {#collections-stdcollections-throughput-1000000-elements}

The generic containers against a plain C implementation of the same algorithms: growable array + `qsort`, a linear-probing hash map with the same hash, a `malloc`-per-node doubly linked list, a power-of-two ring queue, and an unbalanced BST. Both sides use identical shuffled keys (xorshift64), clang 21.1.8 `-O2` for both (`safeguard build --release`), best of 3.

| Operation | SafeC | C | SafeC / C |
|---|---|---|---|
| `Vec<int>` push | 2.50 ms | **1.55 ms** | 1.61× |
| `vec_sort` (introsort, inlined `Less<int>`) vs `qsort` | **43.5 ms** | 83.4 ms | 0.52× |
| `HashMap<int,int>` insert | 41.6 ms | **33.5 ms** | 1.24× |
| `HashMap<int,int>` get | 8.63 ms | **7.24 ms** | 1.19× |
| `List<int>` push_back + pop_front | **11.5 ms** | 22.2 ms | 0.52× |
| `Queue<int>` enqueue + dequeue | **3.42 ms** | 5.47 ms | 0.63× |
| `BST<int,int,Less<int>>` insert | **177.1 ms** | 179.0 ms | 0.99× |
| `BST<int,int,Less<int>>` get | **171.0 ms** | 176.0 ms | 0.97× |

::: details Why these results
- **Sorting** beats `qsort` because the ordering is a type parameter: every comparison inlines, where `qsort` makes an indirect call per comparison.
- **List** and **BST** nodes come from a per-container node pool (chunked bump allocation plus a free list), not one allocation per node; that avoids both `malloc`'s per-chunk overhead and `std::alloc`'s 16-byte safety header. BST lookups are dominated by cache misses on the random walk, the same for both.
- **HashMap** keys of 1/2/4/8 bytes are hashed as one word (murmur3 finalizer) and compared with one integer compare, chosen with `if const` at compile time. The remaining gap is the tombstone bookkeeping on insert.
- **Vec push** pays for bounds-checked access (`keys.at(i)`) and `std::alloc`'s checked `realloc` path.
:::

::: details Sources
[bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc) · [bench_collections.c](/benchmarks/collections/c/bench_collections.c)
:::

## Web service — JSON "hello world" endpoint {#web-service-json-hello-world-endpoint}

`GET /` returning `{"message":"Hello, World!"}` — the same shape as TechEmpower's "JSON serialization" test, via Apache Bench (`ab -n 20000 -c 50`, no keep-alive, after a 2,000-request warm-up) against each language's own HTTP story: SafeC's `std::http_serve_reactor` (epoll reactor on Linux); a minimal raw-socket accept loop for C/C++/Zig; Go's `net/http`; Python's FastAPI+uvicorn; Rust's **axum** (not Dioxus — Dioxus's fullstack server layer *is* axum underneath, so this measures what it actually runs through).

| Language | req/s | p50 / p99 latency | Peak memory |
|---|---|---|---|
| SafeC | **25,216 (fastest)** | 2 / 2 ms | 5.1 MB |
| C | 24,618 | 2 / 2 ms | **3.0 MB (leanest)** |
| C++ | 24,870 | 2 / 2 ms | 5.0 MB |
| Rust | 24,814 | 2 / 2 ms | 4.3 MB |
| Zig | 24,828 | 2 / 2 ms | 9.3 MB |
| Go | 23,573 | 2 / 3 ms | 15.7 MB |
| Python | 5,047 | 10 / 14 ms | 46.1 MB |

Every server completed every request with zero failures. The compiled servers all land within 8% of each other: without keep-alive, each request is a fresh TCP connection, and the kernel's connect/accept path and `ab` itself are the bottleneck, not the server.

::: details Sources
[server_reactor.sc](/benchmarks/webservice/safec/server_reactor.sc) · [server.sc](/benchmarks/webservice/safec/server.sc) · [server.c](/benchmarks/webservice/c/server.c) · [server.cpp](/benchmarks/webservice/cpp/server.cpp) · [main.rs](/benchmarks/webservice/rust/src/main.rs) · [Cargo.toml](/benchmarks/webservice/rust/Cargo.toml) · [server.zig](/benchmarks/webservice/zig/server.zig) · [server.go](/benchmarks/webservice/go/server.go) · [server.py](/benchmarks/webservice/python/server.py) · [http.sc](/benchmarks/stdlib/http.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h) · [task.h](/benchmarks/stdlib/task.h)
:::

## Machine learning — small MLP, training and inference {#machine-learning-small-mlp-training-and-inference}

2-layer MLP (`relu(X @ W1) @ W2`, 128→256→64, batch 64, MSE loss, hand-rolled SGD, no bias) — 100 training steps then 1000 inference passes, fixed seed. Every framework reads the loss back to the host every step and `Y[0][0]` every inference pass (`.item()` in PyTorch), so GPU rows pay a host round-trip per step, as a real training loop that logs its loss would. Each framework gets one untimed warm-up pass first: PyTorch's first `backward()` on this build takes ~10 s of one-time initialization even on CPU, and rocBLAS loads each GEMM shape's kernels on first use.

| Framework | Device | Train (100 steps) | Throughput | Inference (1000 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (naive) | 107.4 ms | 59,600 samples/s | 285.8 ms | 100.333427 |
| SafeC | CPU (OpenBLAS) | 29.6 ms | 216,400 samples/s | 55.7 ms | 100.333420 |
| SafeC | ROCm (MI100) | **7.17 ms (fastest)** | **892,400 samples/s** | 34.9 ms | 100.333282 |
| SafeC | ROCm, graph replay | 7.18 ms | 891,100 samples/s | 39.2 ms | 100.333298 |
| PyTorch | CPU | 14.1 ms | 453,600 samples/s | **21.9 ms (fastest)** | 100.018272 |
| PyTorch | ROCm (MI100) | 20.1 ms | 319,200 samples/s | 36.3 ms | 103.071129 |
| TensorFlow | CPU | 79.3 ms | 80,700 samples/s | 204.0 ms | 106.182999 |

At this size every GPU step is a handful of microsecond-scale kernels plus a blocking read of the loss, so launch and synchronization latency dominate. PyTorch's CPU path (its bundled, tuned GEMM kernels) is faster than SafeC's OpenBLAS path on CPU, and is the fastest at inference. Losses differ between frameworks because each draws its data and initial weights from its own RNG; the SafeC rows all share one seeded stream (`drand48`), and agree to 6 digits across CPU and GPU.

::: details Sources
[train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py)
:::

## Machine learning, bigger model — 512→1024→256 {#machine-learning-bigger-model-512-1024-256}

Same model scaled ~50x (batch 128). 50 training steps, 200 inference passes; same warm-up and per-step readback as above.

| Framework | Device | Train (50 steps) | Throughput | Inference (200 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (naive) | 1761.2 ms | 3,600 samples/s | 1504.2 ms | 95.428474 |
| SafeC | CPU (OpenBLAS) | 160.6 ms | 39,900 samples/s | 217.2 ms | 95.424973 |
| SafeC | ROCm (MI100) | 8.40 ms | 761,800 samples/s | **13.0 ms (fastest)** | 95.428131 |
| SafeC | ROCm, graph replay | **8.31 ms (fastest)** | **770,400 samples/s** | 14.1 ms | 95.428093 |
| PyTorch | CPU | 38.4 ms | 166,500 samples/s | 50.2 ms | 106.601181 |
| PyTorch | ROCm (MI100) | 10.1 ms | 634,700 samples/s | 13.3 ms | 1868.155273 |
| TensorFlow | CPU | 106.7 ms | 60,000 samples/s | 118.1 ms | 316.611603 |

On the GPU SafeC trains ~17% faster than PyTorch; inference is a tie (both are a pair of rocBLAS GEMMs plus one elementwise kernel and one readback per pass). Graph replay records a whole training step into one `RocmGraph` and launches it with one call; with a blocking loss read every step it saves about 1% at most. Without the per-step read, a C++ prototype of the same loop ran 3.8 ms vs 7.1 ms. PyTorch-ROCm's loss diverges at lr 0.001 from its GPU RNG's initial weights. That says nothing about speed, but it is why its sanity-check value looks wrong.

On CPU, OpenBLAS here (generic pthreads build, Ubuntu package) is the weak spot: PyTorch's CPU GEMM is 4.2× faster for training at this size.

::: details Sources
[train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_cpu_blas.sc](/benchmarks/ml_big/safec/train_cpu_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc) · [PyTorch train.py](/benchmarks/ml_big/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml_big/tensorflow/train.py) · [MLX train.py](/benchmarks/ml_big/mlx/train.py)
:::

## Memory allocation — is `std::alloc`/`dealloc` slower than raw `malloc`/`free`? {#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree}

`std::alloc` puts a 16-byte header on every block, which carries the double-free/use-after-free check, and keeps a size-class cache: the same idea as PyTorch's CPU/CUDA caching allocators and MLX's Metal buffer cache. A freed block goes into a thread-local free list bucketed by power-of-two size class, and the next same-class `alloc()` is served from there without calling `malloc()`/`free()`. Cached blocks keep their "freed" magic word until reused, so the safety checks still work. Measured against glibc's `malloc`/`free` (best of 3; every block's address goes into a printed checksum so the optimizer can't delete an alloc/free pair):

| Pattern | `std::alloc` / `dealloc` | `malloc` / `free` | Ratio |
|---|---|---|---|
| Interleaved: allocate one 48-byte block, free it, 20M times | **3.66 ns / pair** | 6.76 ns / pair | 1.8× faster |
| Batch: allocate 1M 24-byte blocks, then free them all, 10 rounds | 134 ms | **94 ms** | 1.4× slower |

The cache wins whenever freed blocks are reused soon. Short-lived objects and binary-trees-style churn work like this: the heap binary-trees above beats every other language's allocator. A million live blocks at once is more than the cache holds (125,587 slots across all classes), so most allocations in the batch pattern go through `malloc` plus the header write and cache bookkeeping, and that overhead shows. For bulk short-lived allocation, `region`/`arena<R>` is the faster tool: 5.1× faster than the heap version on binary-trees.

::: details Sources
[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc) · [alloc_bench.sc](/benchmarks/memalloc/safec/alloc_bench.sc)
:::

## Earlier runs on other platforms {#earlier-runs}

These numbers are from an earlier run on an Apple M1 Pro (macOS 26.5.1, Apple Clang 21.0.0), and on the same 7800X3D under WSL2 Ubuntu 22.04 (clang 19.1.7) and Windows 11 (clang/LLVM ~19). They weren't re-measured since, and the compiler and std have changed, so compare them only with each other.

::: details fib(37)
| Language | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.080s | 0.040s | 0.046s |
| C | 0.070s | 0.040s | 0.046s |
| C++ | 0.070s | 0.040s | 0.047s |
| Rust | 0.090s | 0.040s | 0.049s |
| Zig | 0.080s | 0.040s | 0.053s |
| Go | 0.080s | 0.080s | 0.092s |
| Python | 2.700s | 3.330s | 2.485s |
:::

::: details n-body
| Language | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.110s | 0.090s | 0.102s |
| C | 0.100s | 0.090s | 0.103s |
| C++ | 0.110s | 0.090s | 0.104s |
| Rust | 0.110s | 0.100s | 0.113s |
| Zig | 0.110s | 0.110s | 0.116s |
| Go | 0.100s | 0.100s | 0.113s |
| Python | 9.890s | 12.150s | 11.922s |
:::

::: details binary-trees
| Language | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC (arena) | 0.210s | 0.130s | 0.147s |
| SafeC (heap) | 1.150s | 0.620s | 1.119s |
| C | 1.560s | 0.820s | 1.965s |
| C++ | 1.860s | 0.930s | 2.025s |
| Rust | 1.740s | 0.900s | 2.184s |
| Zig | 1.570s | 0.880s | 2.020s |
| Go | 1.260s | 1.150s | 1.125s |
| Python | 20.930s | 20.130s | 10.019s |
:::

::: details binary-trees, 8 threads
| Language | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC (arena) | 0.090s | 0.075s | 0.045s |
| SafeC (heap) | 0.680s | 0.308s | 0.367s |
| C | 0.610s | 0.274s | 0.549s |
| C++ | 0.630s | 0.290s | 0.566s |
| Rust | 0.680s | 0.319s | 0.591s |
| Zig | 0.540s | 0.280s | 0.563s |
| Go | 0.440s | 0.358s | 0.320s |
| Python | 24.430s | 26.626s | 10.055s |
:::

::: details SIMD
| Language | Scalar: macOS / WSL2 / Windows | Explicit SIMD: macOS / WSL2 / Windows |
|---|---|---|
| SafeC | 0.040s / 0.099s / 0.067s | 0.030s / 0.092s / 0.060s |
| C | 0.040s / 0.099s / 0.077s | 0.030s / 0.092s / 0.060s |
| C++ | 0.040s / 0.101s / 0.073s | 0.030s / 0.092s / 0.073s |
| Rust | 0.040s / 0.099s / 0.044s | 0.030s / 0.093s / 0.039s |
| Zig | 0.040s / 0.096s / 0.065s | 0.030s / 0.089s / 0.069s |
| Go | 0.060s / 0.105s / 0.074s | N/A |
| Python | 3.500s / 3.144s / 3.406s | 0.140s / 0.213s / 0.313s (NumPy) |
:::

::: details Web service
Windows used a small Go load generator at `-n 5000 -c 20` instead of `ab`, so compare that column only with itself.

| Language | macOS req/s (p50/p99) | WSL2 req/s (p50/p99) | Windows req/s (p50/p99) |
|---|---|---|---|
| SafeC | 36055 (1/5ms) | 17650 (3/4ms) | 2313 (7.5/19.6ms) |
| C | 25303 (2/2ms) | N/A | N/A |
| C++ | 24148 (2/4ms) | N/A | N/A |
| Rust | 27270 (2/2ms) | 18077 (3/3ms) | 465 (33.0/474.3ms) |
| Zig | 25742 (2/2ms) | N/A | N/A |
| Go | 25274 (2/3ms) | 18758 (3/4ms) | 2467 (7.8/16.9ms) |
| Python | 4790 (10/33ms) | 2357 (21/27ms) | 528 (37.3/52.5ms) |
:::

::: details ML, small MLP (Apple M1 Pro)
| Framework | Device | Train (100 steps) | Inference (1000 passes) |
|---|---|---|---|
| SafeC | CPU (Accelerate BLAS) | 16.8 ms | 26.1 ms |
| SafeC | MPS | 39.4 ms | 61.1 ms |
| PyTorch | CPU | 23.1 ms | 13.5 ms |
| PyTorch | MPS | 131.6 ms | 358.7 ms |
| TensorFlow | CPU | 83.0 ms | 245.0 ms |
| TensorFlow | GPU | 245.3 ms | 924.6 ms |
| MLX | GPU | 72.2 ms | 309.1 ms |
:::

::: details ML, bigger model (Apple M1 Pro)
| Framework | Device | Train (50 steps) | Inference (200 passes) |
|---|---|---|---|
| SafeC | CPU (Accelerate BLAS) | 89.2 ms | 61.4 ms |
| SafeC | MPS | 47.9 ms | 47.3 ms |
| PyTorch | CPU | 67.8 ms | 48.2 ms |
| PyTorch | MPS | 110.5 ms | 86.4 ms |
| TensorFlow | CPU | 148.8 ms | 197.6 ms |
| TensorFlow | GPU | 154.1 ms | 218.3 ms |
| MLX | GPU | 54.3 ms | 74.4 ms |
:::
