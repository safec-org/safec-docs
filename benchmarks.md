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
- **NVIDIA H200** ([details](#h200-machine-learning)): SafeC's CUDA backend trains the small model in 3.80 ms vs PyTorch-CUDA's 50.9 ms (fp32), and the big one in 3.13 ms vs 16.9 ms. Results for the Xeon 8570 CPU are in [their own section](#xeon-8570-classic).
- **Convolution and FFT on the MI100** ([details](#mi100-conv-fft)): with MIOpen, SafeC runs one 64-channel 3×3 convolution in 0.027 ms vs PyTorch-ROCm's 0.063 ms and the batch-1 U-Net in 0.227 ms vs 0.315 ms; at batch 16–32 the two are within 2%. Device-resident hipFFT transforms are 1.4–1.7× faster than through PyTorch.
- **Convolution and FFT on the H200** ([details](#h200-conv-fft)): with cuDNN, SafeC runs a batch of 32 64-channel 3×3 convolutions in 0.164 ms vs PyTorch-CUDA's 0.257 ms (fp32), and the U-Net in 0.175 ms vs 0.206 ms. PyTorch is faster for a single one-image convolution (0.022 ms vs 0.034 ms) and with TF32 at batch 16–32. Device-resident cuFFT transforms take the same time as through PyTorch.
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

## Convolution and FFT on the MI100 {#mi100-conv-fft}

The same workloads as [on the H200](#h200-conv-fft), on this page's primary machine: SafeC's CPU code (`cnn.h`, `unet.h`, `fft.h`), the ROCm libraries [`gpu_miopen.h` and `fft_rocm.h`](/stdlib/ml#rocm-miopen-hipfft) (MIOpen and hipFFT from ROCm 7.1), and PyTorch 2.9.1 on ROCm 6.4 (which also uses MIOpen and rocFFT). Tensors stay on the GPU between calls. The GPU rows are the mean of 300–2000 queued calls between two synchronizations, best of 3 runs, all in fp32 (the MI100 has no TF32). SafeC picks each convolution's algorithm with MIOpen's Find on the first call, and PyTorch does the same (`cudnn.benchmark = True`).

Convolution, ms per call (lower is better). `conv64` is one Conv2D layer, 64→64 channels, 3×3, stride 1, padding 1, on 56×56 maps, plus bias. `unet128` is `unet_new(3, 32, 64, 3)` forward at 128×128.

| Framework | Device | conv64, 1 image | conv64, 32 images | unet128, 1 image | unet128, 16 images |
|---|---|---|---|---|---|
| SafeC | CPU (naive, 1 thread) | 101.4 | — | 286.1 | — |
| PyTorch | CPU (8 threads) | 0.314 | — | 2.80 | — |
| SafeC | ROCm | **0.0270** | **0.342** | 0.227 | 1.293 |
| SafeC | ROCm, U-Net as one graph | | | **0.222** | |
| PyTorch | ROCm | 0.0628 | 0.346 | 0.315 | **1.268** |

FFT, ms per call. `fft1d` and `fft2d` are complex `double` arrays that start and end in host memory, so each GPU call includes the upload and the read back. `batch` and `rfft` run on data already on the GPU (complex64 and float32).

| Framework | Device | fft1d, 2²⁰ points | fft2d, 1024×1024 | batch, 1024 × 4096 | rfft, 1024 × 4096 |
|---|---|---|---|---|---|
| SafeC | CPU (radix-2, 1 thread) | 156.7 | 163.6 | 377.4 | 371.6 |
| PyTorch | CPU (8 threads) | 8.32 | **0.719** | 4.41 | 0.442 |
| SafeC | ROCm | **1.52** | 1.49 | **0.100** | **0.095** |
| PyTorch | ROCm | 1.56 | 1.52 | 0.171 | 0.131 |

- **Convolutions**: SafeC is 2.3× faster than PyTorch on one image and 1.39× faster on the batch-1 U-Net (1.42× as a graph). At 32 images the two tie, and PyTorch is 2% faster on the 16-image U-Net. Each SafeC convolution is one MIOpen call plus one HIP kernel for the bias (and ReLU), and each U-Net decoder step is one kernel that upsamples straight into the concat buffer. Before that fusion the 16-image U-Net took 1.51 ms.
- **FFT**: both sides call rocFFT. For host arrays PCIe dominates and the times match. On device data SafeC is 1.4–1.7× faster; at 0.1 ms per call, PyTorch's per-call work around the same transform (allocating the output tensor, dispatch) is a large share, though that hasn't been measured on its own.
- **CPU**: as on the H200, SafeC's CPU convolution is a direct single-threaded loop and `fft.h` computes twiddles inside the butterfly; PyTorch's CPU paths are 320× faster on `conv64` and 19× on the 2²⁰-point FFT.

::: details Sources
[conv_rocm.sc](/benchmarks/conv/safec/conv_rocm.sc) · [conv_cpu.sc](/benchmarks/conv/safec/conv_cpu.sc) · [conv.py](/benchmarks/conv/pytorch/conv.py) · [fft_rocm.sc](/benchmarks/fft/safec/fft_rocm.sc) · [fft_cpu.sc](/benchmarks/fft/safec/fft_cpu.sc) · [fft.py](/benchmarks/fft/pytorch/fft.py) · [gpu_miopen.h](/benchmarks/stdlib/gpu_miopen.h) · [gpu_miopen.sc](/benchmarks/stdlib/gpu_miopen.sc) · [fft_rocm.h](/benchmarks/stdlib/fft_rocm.h) · [fft_rocm.sc](/benchmarks/stdlib/fft_rocm.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc)
:::

## Xeon Platinum 8570 + NVIDIA H200 {#xeon-8570-h200}

This is the same suite on a GPU server, and the first run of SafeC's CUDA backend on NVIDIA hardware. Compare these numbers with each other, not with the 7800X3D tables above: the CPU, the core count and the toolchain versions all differ.

- **Machine**: Intel Xeon Platinum 8570 in a Backend.AI container limited to 12 of the host's 112 cores, NVIDIA H200 (sm_90, 141 GB HBM3e, driver 580.126.20, CUDA 13.0), Ubuntu 24.04.3 — `safec` 1.0.0 (v1.0.0-beta-5) · clang 21.1.8 · Rust 1.99.1 · Go 1.27.3 · Zig 0.17.0 · Python 3.12.3 · PyTorch 2.10.0a0 (NGC 25.11) · TensorFlow 2.21.0 (CPU) · OpenBLAS (pthreads, Ubuntu package).
- **Method**: same as [above](#methodology), best of 3. The H200 is shared with a training job, which was paused (`SIGSTOP`) for the whole run, so the GPU and the 12 cores were idle. While that job ran, the same GPU benchmarks varied 5× from run to run, for SafeC and PyTorch alike.
- **Numbers**: [`results/linux-xeon8570-h200.json`](/benchmarks/results/linux-xeon8570-h200.json).

### Machine learning on the H200 {#h200-machine-learning}

The models, loops and per-step readback are the same as in the [MI100 tables](#machine-learning-small-mlp-training-and-inference). The SafeC CUDA rows come from [train_cuda.sc](/benchmarks/ml/safec/train_cuda.sc), which runs four modes:

- **plain**: the same 10-launch step as the ROCm benchmark.
- **fused**: `relu(X W1)` in one cuBLASLt call and both SGD updates in one kernel, 7 launches per step.
- **fused + graph**: the fused step replayed as one CUDA graph.
- **TF32**: the graph step with TF32 tensor cores turned on (`cuda_set_tf32`).

NVIDIA's NGC PyTorch container sets `TORCH_ALLOW_TF32_CUBLAS_OVERRIDE=1`, so PyTorch's fp32 matmuls already run in TF32 by default. The PyTorch fp32 row sets that variable to 0.

Small model (128→256→64, batch 64, 100 training steps, 1000 inference passes):

| Framework | Device | Train (100 steps) | Throughput | Inference (1000 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (naive) | 144.0 ms | 44,459 samples/s | 481.1 ms | 100.333427 |
| SafeC | CPU (OpenBLAS) | 40.8 ms | 157,036 samples/s | 93.9 ms | 100.333420 |
| SafeC | CUDA (H200), plain | 6.72 ms | 951,673 samples/s | 38.7 ms | 100.333282 |
| SafeC | CUDA, fused | 6.23 ms | 1,027,287 samples/s | 34.9 ms | 100.333282 |
| SafeC | CUDA, fused + graph | 3.80 ms | 1,683,325 samples/s | 19.6 ms | 100.333290 |
| SafeC | CUDA, graph + TF32 | **3.79 ms (fastest)** | **1,687,319 samples/s** | **19.5 ms (fastest)** | 100.334076 |
| PyTorch | CPU | 23.3 ms | 274,560 samples/s | 43.4 ms | 100.018272 |
| PyTorch | CUDA, TF32 (NGC default) | 47.7 ms | 134,166 samples/s | 55.6 ms | 103.076630 |
| PyTorch | CUDA, fp32 | 50.9 ms | 125,811 samples/s | 56.5 ms | 103.071129 |
| TensorFlow | CPU | 122.0 ms | 52,470 samples/s | 265.5 ms | 106.182999 |

Bigger model (512→1024→256, batch 128, 50 training steps, 200 inference passes):

| Framework | Device | Train (50 steps) | Throughput | Inference (200 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (naive) | 2323.4 ms | 2,755 samples/s | 2764.1 ms | 95.428474 |
| SafeC | CPU (OpenBLAS) | 205.8 ms | 31,094 samples/s | 255.3 ms | 95.424973 |
| SafeC | CUDA (H200), plain | 4.08 ms | 1,569,397 samples/s | 8.21 ms | 95.427277 |
| SafeC | CUDA, fused | 3.76 ms | 1,701,223 samples/s | 7.59 ms | 95.427292 |
| SafeC | CUDA, fused + graph | 3.13 ms | 2,046,690 samples/s | **5.56 ms (fastest)** | 95.427284 |
| SafeC | CUDA, graph + TF32 | **2.31 ms (fastest)** | **2,770,563 samples/s** | 5.57 ms | 90.685478 |
| PyTorch | CPU | 60.5 ms | 105,864 samples/s | 54.9 ms | 106.544487 |
| PyTorch | CUDA, TF32 (NGC default) | 18.0 ms | 356,506 samples/s | 11.9 ms | 871.826538 |
| PyTorch | CUDA, fp32 | 16.9 ms | 379,192 samples/s | 12.1 ms | 1754.826172 |
| TensorFlow | CPU | 225.7 ms | 28,353 samples/s | 237.4 ms | 316.611603 |

- **SafeC versus PyTorch on the GPU, fp32 against fp32**: training is 13× faster on the small model (3.80 ms vs 50.9 ms) and 5.4× on the big one (3.13 ms vs 16.9 ms); inference is 2.9× and 2.2× faster. At these sizes a step is a few microsecond-scale kernels plus a blocking loss read, so the cost per launch decides the result. One SafeC step is about 38 µs (one graph launch plus the readback). One PyTorch step is about 500 µs of Python, autograd and dispatcher work around the same handful of cuBLAS calls.
- **Where SafeC's time goes**: fusing removes 3 of the 10 launches and saves 7–8%. The graph saves another 39% on the small model, where launch cost dominates, and 17% on the big one. TF32 changes nothing on the small model, which isn't compute-bound, and takes 26% off training on the big one. With TF32, the big model's loss after 50 steps is 90.69 instead of 95.43: this loop amplifies rounding differences, and the CPU, fp32 GPU and ROCm runs all agree to 4 digits.
- **Before this backend**: in v1.0.0-beta-5, `tensor_matmul_cuda_blas` created and destroyed a CUDA context and a cuBLAS handle on every call. On this GPU the small benchmark took 83 s to train and 816 s for inference through that path, more than 20,000× slower than the fused + graph row.
- **CPU**: these 12 cores are slower per thread than the 7800X3D. OpenBLAS trains the small model 1.7× slower than PyTorch's own CPU kernels here, and the big one 3.4× slower.

### Convolution and FFT on the H200 {#h200-conv-fft}

These rows compare SafeC's plain CPU code (`cnn.h`, `unet.h`, `fft.h`) with
the GPU libraries [`gpu_cudnn.h` and `fft_cuda.h`](/stdlib/ml#cuda-cudnn-cufft)
(cuDNN 9.15, cuFFT from CUDA 13.0) and with PyTorch on both devices. All
tensors stay on the GPU between calls. The SafeC CUDA and PyTorch-CUDA rows
are the mean of 300–2000 queued calls between two synchronizations. The CPU
rows are the mean of a few calls. Each number is the best of 3 runs, with the
training job paused.

SafeC picks each convolution's algorithm by timing cuDNN's candidates on the
first call. The PyTorch rows do the same (`cudnn.benchmark = True`); with
PyTorch's default heuristic the times were within 1%. "fp32" sets
`cudnn.allow_tf32 = False` in PyTorch, and SafeC leaves `cuda_set_tf32` off.
"TF32" turns both on. PyTorch's default is TF32 for convolutions.

Convolution, ms per call (lower is better). `conv64` is one Conv2D layer, 64→64
channels, 3×3, stride 1, padding 1, on 56×56 maps, plus bias. `unet128` is
`unet_new(3, 32, 64, 3)` forward at 128×128: five convolutions, two max pools,
two upsample + concat steps.

| Framework | Device | conv64, 1 image | conv64, 32 images | unet128, 1 image | unet128, 16 images |
|---|---|---|---|---|---|
| SafeC | CPU (naive, 1 thread) | 169.8 | — | 459.1 | — |
| PyTorch | CPU (12 threads) | 0.307 | — | 3.21 | — |
| SafeC | CUDA, fp32 | 0.0336 | **0.164** | 0.175 | **0.731** |
| SafeC | CUDA, fp32, U-Net as one graph | | | **0.164** | |
| PyTorch | CUDA, fp32 | **0.0224** | 0.257 | 0.206 | 0.873 |
| SafeC | CUDA, TF32 | **0.0334** | 0.106 | 0.175 | 0.659 |
| SafeC | CUDA, TF32, U-Net as one graph | | | **0.123** | |
| PyTorch | CUDA, TF32 (default) | 0.0381 | **0.097** | 0.274 | **0.556** |

FFT, ms per call. `fft1d` and `fft2d` are complex `double` arrays that start
and end in host memory, so each GPU call includes the upload and the read back
(`cuda_fft`/`cuda_fft2d`; `.to("cuda")`, `torch.fft`, `.cpu()`). `batch` and
`rfft` run on data already on the GPU (`cuda_fft_c2c`/`cuda_fft_r2c`; complex64
and float32). Their SafeC CPU rows use `double` and loop `fft`/`fft_real` over
the rows.

| Framework | Device | fft1d, 2²⁰ points | fft2d, 1024×1024 | batch, 1024 × 4096 | rfft, 1024 × 4096 |
|---|---|---|---|---|---|
| SafeC | CPU (radix-2, 1 thread) | 198.1 | 193.7 | 448.0 | 445.7 |
| PyTorch | CPU (12 threads) | **1.20** | **0.660** | 3.08 | 0.291 |
| SafeC | CUDA | 2.68 | 2.68 | 0.0245 | **0.0108** |
| PyTorch | CUDA | 2.07 | 2.06 | **0.0240** | 0.0119 |

- **Convolutions on the GPU**: SafeC is 1.6× faster than PyTorch on the batch of 32 in fp32, and 1.18–1.25× faster on the U-Net in fp32 at either batch size. At batch 1 the U-Net is launch-bound (11 ops), so recording it as one graph helps most with TF32: 0.123 ms against PyTorch's 0.274 ms. PyTorch wins the single one-image convolution, 0.022 ms against 0.034 ms. Two differences could explain this, but neither has been measured on its own: SafeC adds the bias in a second cuDNN call, and PyTorch reaches cuDNN through its graph API, which offers other kernels. With TF32 at batch 16–32, PyTorch is 9–19% faster. TF32 cuts SafeC's batched convolution by 35% and changes nothing at batch 1.
- **Against the CPU**: SafeC's `conv2d_forward` is a direct 7-deep loop on one thread. The same layer through cuDNN takes 1/5,000 of the time at batch 1, and the U-Net 1/2,600. PyTorch's CPU path (oneDNN, 12 threads) is 550× faster than SafeC's CPU loop on `conv64`.
- **FFT on the GPU**: with data on the GPU, SafeC and PyTorch both call cuFFT, and their times match within 10%. For host arrays, PCIe dominates. Copying 16 MB takes 1.05 ms in each direction, the same for SafeC and PyTorch. SafeC's round trip still takes 0.6 ms longer than PyTorch's, and the cause is not yet known.
- **FFT on the CPU**: `fft.h` computes every twiddle factor with `cos`/`sin` inside the butterfly loop and runs on one thread. PyTorch's CPU FFT is 165× faster on the 2²⁰-point transform. `cuda_fft` is 74× faster than `fft` for the same call, including the PCIe transfers.

### Classic benchmarks on the Xeon 8570 {#xeon-8570-classic}

Release-build wall-clock time, best of 3 (lower is better; fastest in bold). Web service is requests per second (higher is better). The Zig sources for the threaded and web benchmarks were written for Zig 0.16 and don't build with 0.17.0, which changed `**` array repetition and removed `@cImport`.

| Benchmark | SafeC | C | C++ | Rust | Zig | Go | Python |
|---|---|---|---|---|---|---|---|
| fib(37) | 0.086s | 0.087s | 0.090s | 0.092s | **0.071s** | 0.134s | 2.432s |
| n-body | 0.146s | **0.144s** | 0.145s | 0.154s | 0.154s | 0.190s | 9.334s |
| binary-trees (SafeC: arena) | **0.310s** | 1.165s | 1.372s | 1.400s | 1.248s | 3.319s | 29.399s |
| binary-trees, SafeC heap | 1.160s | | | | | | |
| binary-trees, 8 threads (SafeC: arena) | **0.092s** | 0.407s | 0.450s | 0.459s | N/A | 0.412s | 74.727s |
| binary-trees, 8 threads, SafeC heap | 0.395s | | | | | | |
| SIMD, explicit vectors | 0.114s | 0.110s | 0.112s | 0.110s | **0.093s** | — | 0.334s (NumPy) |
| Web service (req/s) | **33,738** | 32,177 | 32,606 | 32,652 | N/A | 32,182 | 2,886 |

Collections (1,000,000 elements, ms): `Vec` push 3.23 vs C 1.73, `vec_sort` **74.8** vs `qsort` 148.8, `HashMap` insert 62.1 vs 54.3, get 20.4 vs 18.9, `List` push+pop **17.5** vs 27.5, `Queue` **4.16** vs 6.78, `BST` insert 357.7 vs **315.5**, get 348.4 vs **337.2**. Allocator: `std::alloc`/`dealloc` 10.06 ns per interleaved pair vs `malloc`/`free` **8.52** ns, and the batch pattern takes 158 ms vs **119** ms. The ordering matches the 7800X3D except for the interleaved allocator pattern, where glibc's `malloc` comes out ahead on this machine.

::: details Sources
[train_cuda.sc](/benchmarks/ml/safec/train_cuda.sc) · [gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [conv_cpu.sc](/benchmarks/conv/safec/conv_cpu.sc) · [conv_cuda.sc](/benchmarks/conv/safec/conv_cuda.sc) · [PyTorch conv.py](/benchmarks/conv/pytorch/conv.py) · [fft_cpu.sc](/benchmarks/fft/safec/fft_cpu.sc) · [fft_cuda.sc](/benchmarks/fft/safec/fft_cuda.sc) · [PyTorch fft.py](/benchmarks/fft/pytorch/fft.py) · [gpu_cudnn.h](/benchmarks/stdlib/gpu_cudnn.h) · [gpu_cudnn.sc](/benchmarks/stdlib/gpu_cudnn.sc) · [fft_cuda.h](/benchmarks/stdlib/fft_cuda.h) · [fft_cuda.sc](/benchmarks/stdlib/fft_cuda.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [PyTorch big train.py](/benchmarks/ml_big/pytorch/train.py) · the classic benchmarks' sources are linked in their sections above.
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
