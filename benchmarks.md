# Benchmarks

SafeC against C, C++, Rust, Zig, Go and Python on a few classic microbenchmarks (in the spirit of [The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html)), plus the standard library's collections, HTTP server, ML tensors, DSP and allocator. One machine, best of 3 runs: read every number as "roughly this, on this machine", not as a ranking of languages.

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

- **Machine learning** (2-layer MLP, [details](#machine-learning-small-mlp-training-and-inference)): on the MI100, SafeC trains the small model in 7.0 ms vs PyTorch-ROCm's 20.5 ms (fp32), and the big one in 7.4 ms vs 10.7 ms. In fp16 the big one takes 5.9 ms vs 9.9 ms. On the CPU, SafeC with AOCL-BLAS trains the small model in 14.8 ms vs PyTorch's 14.1 ms, and the big one in 37.0 ms vs 38.5 ms.
- **NVIDIA H200** ([details](#h200-machine-learning)): SafeC's CUDA backend trains the small model in 3.80 ms vs PyTorch-CUDA's 51.0 ms (fp32), and the big one in 3.13 ms vs 17.8 ms. In fp16 the big one takes 1.90 ms vs 16.9 ms. Results for the Xeon 8570 CPU are in [their own section](#xeon-8570-classic).
- **Convolution and FFT on the MI100** ([details](#mi100-conv-fft)): with MIOpen, SafeC runs one 64-channel 3×3 convolution in 0.027 ms vs PyTorch-ROCm's 0.063 ms and the batch-1 U-Net in 0.224 ms vs 0.315 ms; at batch 16–32 the two are within 2%. Device-resident hipFFT transforms are 1.3–1.7× faster than through PyTorch.
- **CPU convolution and FFT** ([details](#mi100-conv-fft)): with Winograd 3×3 convolutions the U-Net runs in 1.42 ms on the CPU vs PyTorch's 2.97 ms. A 2²⁰-point FFT takes 0.92 ms vs 16-thread FFTW's 1.63 ms; FFTW is 1.1–3.1× faster on the 2D, batched and real transforms.
- **DSP** ([details](#dsp-cpu)): `std/dsp` is the fastest of SafeC, SciPy, KFR, Q, madronalib and dasp on every workload measured, in double and in float. A biquad over 2²⁰ samples takes 0.47 ms vs Q's 1.69 ms (float), a 127-tap streaming FIR 3.0 ms vs SciPy's 19.2 ms, and resampling 44.1→48 kHz 3.8 ms vs SciPy's 9.1 ms.
- **Convolution and FFT on the H200** ([details](#h200-conv-fft)): with cuDNN, SafeC is faster than PyTorch-CUDA in every convolution row. A one-image 64-channel 3×3 convolution takes 0.018 ms vs 0.022 ms (fp32), a batch of 32 takes 0.084 ms vs 0.097 ms (TF32), and the U-Net at batch 16 takes 0.45 ms vs 0.55 ms (TF32). A 2²⁰-point FFT of a host array, including both PCIe copies, takes 1.25 ms vs 2.08 ms. Device-resident cuFFT transforms take the same time as through PyTorch.
- **Collections** ([details](#collections-stdcollections-throughput-1000000-elements)): `vec_sort` is 1.9× faster than `qsort` and the pooled list 1.9× faster than a `malloc`-per-node one; `Vec` push and `HashMap` are 1.2–1.6× slower than plain C.
- **Allocator** ([details](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree)): `std::alloc` is 1.8× faster than `malloc` when blocks are reused, 1.4× slower for a million live blocks.

## Methodology {#methodology}

- **Machine**: AMD Ryzen 7 7800X3D (8 cores / 16 threads), AMD Instinct MI100 (gfx908, 32 GB HBM2), Ubuntu 24.04.5, native Linux — `safec` 1.0.0 · clang 21.1.8 (C/C++ and SafeC's backend) · Rust 1.100.0-nightly · Go 1.27.2 · Zig 0.16.0 · Python 3.12.3 (+ NumPy 2.5.4) · PyTorch 2.9.1+rocm6.4 · TensorFlow (CPU) · OpenBLAS (pthreads) · AOCL-BLAS 5.3.2 · oneMKL 2026.2 · ROCm 6.4 (HIP, hiprtc, rocBLAS). For the FFT/DSP comparison: FFTW 3.3.10, KFR 7.2.0, SciPy 1.18.1, RustFFT 6.4.1, dasp 0.11.0, Q and madronalib from their current sources. Each third-party library is built and linked only into its own benchmark program, never into SafeC's std.
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
| SafeC | CPU (built-in `sgemm`, 16 threads) | 17.6 ms | 364,100 samples/s | 35.2 ms | 100.333267 |
| SafeC | CPU, AOCL-BLAS | 14.8 ms | 431,100 samples/s | 24.9 ms | 100.333267 |
| SafeC | CPU, oneMKL | 18.6 ms | 343,800 samples/s | 30.4 ms | 100.333267 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 25.6 ms | 249,900 samples/s | 55.6 ms | 100.333267 |
| SafeC | ROCm (MI100), fp32 | 7.26 ms | 881,300 samples/s | 33.3 ms | 100.333282 |
| SafeC | ROCm, graph, fp32 | 7.01 ms | 912,900 samples/s | 36.4 ms | 100.333282 |
| SafeC | ROCm, graph, fp16 | **5.39 ms (fastest)** | **1,187,000 samples/s** | 33.2 ms | 100.336113 |
| SafeC | ROCm, graph, bf16 | 5.78 ms | 1,106,700 samples/s | 35.0 ms | 99.905960 |
| PyTorch | CPU | 14.1 ms | 453,600 samples/s | **22.0 ms (fastest)** | 100.018272 |
| PyTorch | ROCm (MI100), fp32 | 20.5 ms | 311,700 samples/s | 36.3 ms | 103.071129 |
| PyTorch | ROCm, fp16 | 19.0 ms | 336,600 samples/s | 33.5 ms | 103.062500 |
| PyTorch | ROCm, bf16 | 18.9 ms | 338,700 samples/s | 47.1 ms | 103.000000 |
| TensorFlow | CPU | 79.3 ms | 80,700 samples/s | 203.5 ms | 106.182999 |

At this size every GPU step is a handful of microsecond-scale kernels plus a blocking read of the loss, so launch and synchronization latency dominate. On the CPU, PyTorch is still the fastest at inference. With AOCL-BLAS linked, SafeC trains within 5% of it (14.8 vs 14.1 ms).

SafeC's CPU rows run `tensor_matmul` on [`sgemm`](/stdlib/simd#gemm), the packed SIMD GEMM, on the [thread pool](/stdlib/sync#parallel). Its kernel is picked at run time for the CPU's [SIMD level](/stdlib/simd#cpu-levels) (AVX-512 here). Tensors come from a per-step [`TensorArena`](/stdlib/ml#tensor-arena), and the pool's workers spin briefly before sleeping. With [`[build] blas`](/advanced/safeguard#linking-external-libraries-and-library-output) set, the GEMM tiles go to AOCL-BLAS (AMD) or oneMKL (Intel), single-threaded per tile on SafeC's pool. The OpenBLAS row is the older `tensor_blas.h` path, one whole multithreaded OpenBLAS call per product. The plain scalar loop all of this replaced took 107.4 ms to train and 285.8 ms for inference. Losses differ between frameworks because each draws its data and initial weights from its own RNG; the SafeC rows all share one seeded stream (`drand48`), and agree to 6 digits across CPU and GPU.

::: details Sources
[train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [gemm.h](/benchmarks/stdlib/gemm.h) · [gemm.sc](/benchmarks/stdlib/gemm.sc) · [parallel.h](/benchmarks/stdlib/parallel.h) · [parallel.sc](/benchmarks/stdlib/parallel.sc) · [tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py)
:::

## Machine learning, bigger model — 512→1024→256 {#machine-learning-bigger-model-512-1024-256}

Same model scaled ~50x (batch 128). 50 training steps, 200 inference passes; same warm-up and per-step readback as above.

| Framework | Device | Train (50 steps) | Throughput | Inference (200 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (built-in `sgemm`, 16 threads) | 48.7 ms | 131,300 samples/s | 67.3 ms | 95.426926 |
| SafeC | CPU, AOCL-BLAS | 37.0 ms | 173,100 samples/s | 43.3 ms | 95.427536 |
| SafeC | CPU, oneMKL | 44.8 ms | 142,900 samples/s | 52.0 ms | 95.428177 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 148.5 ms | 43,100 samples/s | 240.4 ms | 95.424927 |
| SafeC | ROCm (MI100), fp32 | 7.64 ms | 838,100 samples/s | 10.6 ms | 95.428314 |
| SafeC | ROCm, graph, fp32 | 7.37 ms | 868,500 samples/s | 11.5 ms | 95.428322 |
| SafeC | ROCm, graph, fp16 | **5.90 ms (fastest)** | **1,085,300 samples/s** | **9.26 ms (fastest)** | 95.090317 |
| SafeC | ROCm, graph, bf16 | 6.05 ms | 1,057,500 samples/s | 9.80 ms | 63.433674 |
| PyTorch | CPU | 38.5 ms | 166,100 samples/s | 51.4 ms | 106.601181 |
| PyTorch | ROCm (MI100), fp32 | 10.7 ms | 599,000 samples/s | 13.4 ms | 1868.155273 |
| PyTorch | ROCm, fp16 | 9.91 ms | 645,600 samples/s | 17.9 ms | 1942.000000 |
| PyTorch | ROCm, bf16 | 10.8 ms | 590,700 samples/s | 15.7 ms | 107.000000 |
| TensorFlow | CPU | 105.6 ms | 60,600 samples/s | 114.6 ms | 316.611603 |

On the GPU in fp32, SafeC trains 1.45× faster than PyTorch and runs inference 1.26× faster (both are a pair of rocBLAS GEMMs plus one elementwise kernel and one readback per pass). Part of that gain is new: the first call of each GEMM shape now times every solution rocBLAS offers and keeps the fastest (see [ROCm](/stdlib/ml#rocm-hip-amd)), which took the fp32 graph step from 8.29 ms to 7.37 ms. Graph replay records a whole training step into one `RocmGraph` and launches it with one call; with a blocking loss read every step it saves 3–4%. Without the per-step read, a C++ prototype of the same loop ran 3.8 ms vs 7.1 ms. PyTorch-ROCm's loss diverges at lr 0.001 from its GPU RNG's initial weights. That says nothing about speed, but it is why its sanity-check value looks wrong.

**fp16 and bf16.** The 16-bit rows run the graph step with data, weights, activations and gradients all in [`RocmHalfBuf`](/stdlib/ml#rocm-hip-amd) storage. GEMMs go to rocBLAS `gemm_ex` on the MI100's matrix cores with fp32 accumulation. The elementwise math runs in fp32 and is rounded once per stored value, and the loss stays fp32. The PyTorch rows cast the same model and data with `.to(dtype)` and train entirely in that dtype.

- 16-bit is SafeC's fastest mode here. fp16 trains 23% faster than the fp32 graph on the small model (5.39 vs 7.01 ms) and 20% faster on the big one (5.90 vs 7.37 ms), and big-model inference is 20% faster (9.26 vs 11.5 ms). bf16 is within 7% of fp16.
- Against PyTorch-ROCm in the same dtype, SafeC trains 3.5× (fp16) and 3.3× (bf16) faster on the small model, and 1.7× and 1.8× on the big one. Big-model inference is 1.9× and 1.6× faster. PyTorch gains at most 8% from 16-bit training, and its big-model inference is slower in 16-bit than in fp32.
- The per-shape solution choice matters most here. With rocBLAS's default solutions, fp16 was *slower* than fp32 on the big model (9.3 vs 8.4 ms): the default for the 128×256×1024 fp16 product took 0.047 ms, against 0.008 ms for the fastest one.
- This loop amplifies rounding. After 50 steps the big model's loss is 95.09 in fp16 and 63.43 in bf16, against 95.43 in fp32; the H200 shows the same spread (93.44 and 62.01).
- On the CPU, [`tensor_half.h`](/stdlib/ml#half-precision-tensors) stores fp16/bf16 values but computes in fp32, which saves memory but not time. That's why there are no 16-bit CPU rows.

On the CPU, SafeC with AOCL-BLAS is the fastest at this size: it trains 4% faster than PyTorch (37.0 vs 38.5 ms) and runs inference 16% faster (43.3 vs 51.4 ms). The built-in `sgemm` alone is within 1.3× of PyTorch, and 36× faster than the scalar loop it replaced (1761.2 ms to train). The OpenBLAS row (generic pthreads build, Ubuntu package) is the slowest CPU path. Each product is one multithreaded call there, and its threads spin against each other on these small GEMMs.

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
| SafeC | CPU (Winograd / im2col + built-in `sgemm`, 16 threads) | 0.520 | — | 1.86 | — |
| SafeC | CPU, AOCL-BLAS | 0.470 | — | 1.42 | — |
| SafeC | CPU, oneMKL | 0.505 | — | 1.80 | — |
| PyTorch | CPU (oneDNN, 8 threads) | 0.310 | — | 2.97 | — |
| SafeC | ROCm | **0.0270** | **0.343** | 0.224 | 1.288 |
| SafeC | ROCm, U-Net as one graph | | | **0.219** | |
| PyTorch | ROCm | 0.0627 | 0.345 | 0.315 | **1.264** |

FFT on the GPU, ms per call. `fft1d` and `fft2d` are complex `double` arrays that start and end in host memory, so each GPU call includes the upload and the read back. `batch` and `rfft` run on data already on the GPU (complex64 and float32).

| Framework | Device | fft1d, 2²⁰ points | fft2d, 1024×1024 | batch, 1024 × 4096 | rfft, 1024 × 4096 |
|---|---|---|---|---|---|
| SafeC | ROCm | **1.54** | **1.44** | **0.0997** | **0.0945** |
| PyTorch | ROCm | 1.55 | 1.53 | 0.169 | 0.126 |

FFT on the CPU, ms per call, against FFTW 3.3.10 (built with AVX/AVX2/AVX-512 and FMA codelets, planned with `FFTW_MEASURE`), KFR 7.2.0 and RustFFT 6.4.1. All data starts in memory. `fft1d` is one 2²⁰-point transform, `fft2d` one 1024×1024 transform, `batch` 1024 transforms of 4096 points, `rfft` 1024 real transforms of 4096 points. FFTW, KFR and RustFFT return n/2 + 1 bins for `rfft`; SafeC's `fft_real_batch` writes all n. RustFFT has no 2D or real transform. With oneMKL linked, SafeC hands transforms below 65,536 points to its DFTI. AOCL has no effect here (its FFT is FFTW-based, GPL, and not used).

Double precision (complex128):

| Library | Threads | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft.h` | 16 | **0.917** | 0.654 | 1.26 | 1.75 |
| SafeC, oneMKL DFTI | 16 | 0.938 | 1.03 | 2.01 | 1.96 |
| FFTW | 16 | 1.63 | **0.597** | **0.975** | **0.561** |
| PyTorch | 8 | 8.36 | 0.749 | — | — |
| SafeC `fft.h` | 1 | 5.53 | 4.38 | 13.9 | 8.56 |
| FFTW | 1 | 6.70 | 3.51 | 7.83 | 4.10 |
| KFR | 1 | 3.45 | 4.25 | 11.9 | 4.33 |
| RustFFT | 1 | 4.76 | — | 8.44 | — |

Single precision (complex64 / float32):

| Library | Threads | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft_f32` | 16 | **0.692** | 0.539 | 0.822 | 0.720 |
| SafeC, oneMKL DFTI | 16 | 0.694 | 0.513 | 0.915 | 0.515 |
| FFTW (`fftwf`) | 16 | 1.69 | **0.432** | **0.500** | **0.409** |
| PyTorch | 8 | — | — | 4.65 | 0.438 |
| SafeC `fft_f32` | 1 | 4.49 | 3.39 | 5.83 | 5.41 |
| FFTW (`fftwf`) | 1 | 5.52 | 2.51 | 3.45 | 2.98 |
| KFR | 1 | 1.77 | 2.65 | 4.17 | 2.13 |
| RustFFT | 1 | 2.10 | — | 4.19 | — |

The single-thread SafeC rows were run separately (`SAFEC_NUM_THREADS=1`), after the table run, with the same build.

- **Convolutions**: on the GPU, SafeC is 2.3× faster than PyTorch on one image and 1.44× faster on the batch-1 U-Net as a graph. At 32 images the two tie, and PyTorch is 2% faster on the 16-image U-Net. Each SafeC convolution is one MIOpen call plus one HIP kernel for the bias (and ReLU), and each U-Net decoder step is one kernel that upsamples straight into the concat buffer.
- **CPU convolution**: 3×3 stride-1 layers now use [Winograd F(2×2, 3×3)](/stdlib/ml#cnn) (Lavin & Gray), with 16 multiplies per 2×2 outputs and channel pair instead of 36. Its input transform, 16 channel GEMMs and output transform each run on the pool. Other layers use im2col + `sgemm`. On the U-Net SafeC is now 2.1× faster than PyTorch's CPU path with AOCL-BLAS (1.42 vs 2.97 ms) and 1.6× faster with the built-in GEMM. Before Winograd it took 3.13 ms (4.86 ms in the earlier run on this page). On the single `conv64` layer PyTorch (oneDNN) is still 1.5–1.7× faster.
- **FFT on the GPU**: both sides call rocFFT. For host arrays PCIe dominates and the times match. On device data SafeC is 1.3–1.7× faster; at 0.1 ms per call, PyTorch's per-call work around the same transform (allocating the output tensor, dispatch) is a large share, though that hasn't been measured on its own.
- **CPU FFT**: SafeC is the fastest at the 2²⁰-point transform in both precisions, 1.8× (double) and 2.4× (float) ahead of 16-thread FFTW. FFTW leads on the 2D, batched and real transforms, by 1.1× (double `fft2d`) to 3.1× (double `rfft`). Single-threaded, SafeC is ahead of FFTW at 2²⁰ points and behind it on the 2D and 4096-point rows (1.8× on the double batch), where FFTW's larger codelets and fewer passes over each row count. The real transform's post-processing in SafeC is still scalar. Since this page's previous run, fused radix-8 passes, four complex values per vector (AVX-512, and float on AVX2) and compile-time bit-reversal tables took the 16-thread double batch from 4.35 to 1.26 ms and the 2D transform from 2.06 to 0.654 ms.
- **Single precision**: float runs the same algorithm (`fft_core.h` built twice) with twice the values per register. The batch takes 0.65× the double time, and the real batch 0.41×.
- **oneMKL**: its DFTI is faster than SafeC's own code on the float `rfft` (0.515 vs 0.720) and float `fft2d`, but slower in double (batch 2.01 vs 1.26).
- **Warm-up**: every CPU row gets one untimed call first, which starts the thread pool and builds the twiddle tables. FFTW's plans are made outside the timing. PyTorch's script runs five warm-up calls.

::: details Sources
[conv_rocm.sc](/benchmarks/conv/safec/conv_rocm.sc) · [conv_cpu.sc](/benchmarks/conv/safec/conv_cpu.sc) · [conv.py](/benchmarks/conv/pytorch/conv.py) · [fft_rocm.sc](/benchmarks/fft/safec/fft_rocm.sc) · [fft_cpu.sc](/benchmarks/fft/safec/fft_cpu.sc) · [fft.py](/benchmarks/fft/pytorch/fft.py) · [fftw_bench.c](/benchmarks/fft/c/fftw_bench.c) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [RustFFT main.rs](/benchmarks/dsp/rust/src/main.rs) · [cnn.sc](/benchmarks/stdlib/cnn.sc) · [unet.sc](/benchmarks/stdlib/unet.sc) · [gemm.sc](/benchmarks/stdlib/gemm.sc) · [fft.h](/benchmarks/stdlib/fft.h) · [fft.sc](/benchmarks/stdlib/fft.sc) · [fft_f32.sc](/benchmarks/stdlib/fft_f32.sc) · [fft_core.h](/benchmarks/stdlib/fft_core.h) · [parallel.sc](/benchmarks/stdlib/parallel.sc) · [gpu_miopen.h](/benchmarks/stdlib/gpu_miopen.h) · [gpu_miopen.sc](/benchmarks/stdlib/gpu_miopen.sc) · [fft_rocm.h](/benchmarks/stdlib/fft_rocm.h) · [fft_rocm.sc](/benchmarks/stdlib/fft_rocm.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc)
:::

## DSP on the CPU {#dsp-cpu}

[`std/dsp`](/stdlib/dsp) against NumPy 2.5.4 / SciPy, KFR 7.2.0, and, for the biquad, two float32 audio libraries (Q and madronalib) and dasp 0.11 for resampling. Every workload runs on 1,048,576 random samples (a 1024×1024 image for `conv2d`), in double and then in single precision (SafeC's [`_f32` functions](/stdlib/dsp#single-precision-float)). ms per call, mean after one warm-up call, best of 3.

- `fir stream`: a 127-tap streaming FIR that keeps its state between calls (SafeC `fir_process_block`, SciPy `lfilter`, KFR `fir_filter`). SafeC's is the real-time path: no allocation, no threads.
- `fir block`: the same filter as one convolution (`conv_direct`, `np.convolve`).
- `fftconv`: a 4,096-tap kernel (`conv_fft`, `fftconvolve`, KFR `convolve_filter`).
- `biquad`: one RBJ low-pass section (fs 48 kHz, f0 1 kHz, Q 0.7071; `biquad_process_block`, `sosfilt`). It runs single-threaded, as a real-time filter would.
- `stft`: 1,024-point Hann frames, hop 256 (`stft_forward`; SciPy's `ShortTimeFFT` returns the one-sided spectrum).
- `resample`: 44.1 kHz → 48 kHz. SafeC uses a windowed sinc with 16 taps each side, SciPy `resample_poly` (polyphase, Kaiser), KFR its `normal` quality (and `draft`), dasp a 16-tap-each-side sinc.
- `conv2d`: a 5×5 Gaussian, "same" size.

Double precision:

| Workload | SafeC | SafeC, AOCL / oneMKL | SciPy | KFR | Others |
|---|---|---|---|---|---|
| fir stream | 5.99 | 5.92 / 5.90 | 18.0 | 31.9 | |
| fir block | 0.836 | 0.805 / 0.872 | 15.3 | — | |
| fftconv | 2.66 | 2.72 / 3.01 | 32.6 | 4.88 | |
| biquad | 0.465 | 0.477 / 0.484 | 4.67 | 2.33 | |
| stft | 1.93 | 1.96 / 2.06 | 53.0 | — | |
| resample | 3.78 | 3.82 / 3.86 | 9.10 | 63.6 (draft 26.3) | dasp 520 |
| conv2d | 0.393 | 0.396 / 0.387 | 35.8 | — | |

Single precision:

| Workload | SafeC | SafeC, AOCL / oneMKL | SciPy | KFR | Others |
|---|---|---|---|---|---|
| fir stream | 3.02 | 3.04 / 3.10 | 19.2 | 27.8 | |
| fir block | 0.484 | 0.443 / 0.511 | 18.9 | — | |
| fftconv | 1.57 | 1.54 / 1.52 | 14.7 | 2.80 | |
| biquad | 0.469 | 0.474 / 0.473 | 4.58 | 2.14 | Q 1.69, madronalib 2.90 |
| stft | 1.13 | 1.12 / 1.09 | 55.5 | — | |
| resample | 3.93 | 3.90 / 3.92 | 8.70 | 34.1 (draft 27.2) | |

- SafeC is the fastest on every row, in both precisions. The vendor libraries only matter where an FFT is involved (`fftconv`, `stft`), and there they are within noise of the built-in code.
- **FIR**: each block runs `dsp_sliding_dot`, which computes 32 outputs per pass (64 in float) with each tap broadcast into four accumulator vectors, so there are no horizontal sums. The streaming filter went from 14.2 ms to 5.99 (double) and from 9.45 to 3.02 (float); KFR and SciPy run one dot product per output. `fir block` is threaded; `fir stream` isn't.
- **Biquad**: `biquad_process_block` uses scattered look-ahead (Parhi & Messerschmitt). The recursion is rewritten in y[i-2] and y[i-4], so the even and odd outputs are two independent chains, computed together in one 2-lane vector. That makes it 3.6× faster than Q and 4.6× faster than KFR in float, and 4.6× faster than before (2.14 ms). The added poles mirror the filter's own, so it stays stable, and its output matches `process()` to rounding.
- **Resample**: each tap's weight comes from angle-difference formulas over tables built once per call, so the taps vectorize. The per-output angles are carried by rotation, so each output needs no trig call. It is 2.4× faster than SciPy's polyphase filter (12.1 ms before). SafeC's resampler is threaded; SciPy's isn't.
- **STFT** frames go through an in-place real FFT batch, half the work of the complex transform it used before (7.08 ms).
- **Float vs double**: float helps where memory traffic or vector width limits the code: the FIR (2×), `fftconv` and `stft`. The biquad is latency-bound and the resampler computes its kernel in double, so those two don't change.

::: details Sources
[dsp_cpu.sc](/benchmarks/dsp/safec/dsp_cpu.sc) · [dsp_scipy.py](/benchmarks/dsp/python/dsp_scipy.py) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [audio_libs_bench.cpp](/benchmarks/dsp/cpp/audio_libs_bench.cpp) · [dasp main.rs](/benchmarks/dsp/rust/src/main.rs) · [Cargo.toml](/benchmarks/dsp/rust/Cargo.toml) · [dsp.sc](/benchmarks/stdlib/dsp.sc) · [filter.sc](/benchmarks/stdlib/filter.sc) · [biquad.sc](/benchmarks/stdlib/biquad.sc) · [convolution.sc](/benchmarks/stdlib/convolution.sc) · [conv_core.h](/benchmarks/stdlib/conv_core.h) · [stft.sc](/benchmarks/stdlib/stft.sc) · [resample_core.h](/benchmarks/stdlib/resample_core.h) · [imaging_core.h](/benchmarks/stdlib/imaging_core.h) · [fft_core.h](/benchmarks/stdlib/fft_core.h)
:::

## Xeon Platinum 8570 + NVIDIA H200 {#xeon-8570-h200}

The same suite on a GPU server, the machine SafeC's CUDA backend was verified on. Compare these numbers with each other, not with the 7800X3D tables above: the CPU, the core count and the toolchain versions all differ.

- **Machine**: Intel Xeon Platinum 8570 in a Backend.AI container limited to 12 of the host's 112 cores, NVIDIA H200 (sm_90, 141 GB HBM3e, driver 580.126.20, CUDA 13.0, cuDNN 9.15), Ubuntu 24.04.3 — `safec` 1.0.0 built from `main` (449420a, after v1.0.0-beta-5) · clang 21.1.8 · Rust 1.99.1 · Go 1.27.3 · Zig 0.17.0 · Python 3.12.3 · PyTorch 2.10.0a0 (NGC 25.11) · TensorFlow 2.21.0 (CPU) · OpenBLAS (pthreads, Ubuntu package).
- **Method**: same as [above](#methodology), best of 3. The H200 is shared with a training job, which was paused (`SIGSTOP`) for the whole run, so the GPU and the 12 cores were idle. While that job ran, the same GPU benchmarks varied 5× from run to run, for SafeC and PyTorch alike. SafeC's std is built for baseline x86-64 (SSE2, no FMA), as on the 7800X3D.
- **Numbers**: [`results/linux-xeon8570-h200.json`](/benchmarks/results/linux-xeon8570-h200.json). Re-running this suite with `main`'s CPU code reproduced every GPU number from the earlier beta-5-era run within 1%. The [ML](#h200-machine-learning) and [convolution/FFT](#h200-conv-fft) sections were then re-run with the `cuda-perf-gaps` branch of SafeC (cuDNN graph API, pinned transfer lanes, fp16/bf16 buffers).

### Machine learning on the H200 {#h200-machine-learning}

The models, loops and per-step readback are the same as in the [MI100 tables](#machine-learning-small-mlp-training-and-inference). The SafeC CUDA rows come from [train_cuda.sc](/benchmarks/ml/safec/train_cuda.sc), which runs six modes:

- **plain**: the same 10-launch step as the ROCm benchmark.
- **fused**: `relu(X W1)` in one cuBLASLt call and both SGD updates in one kernel, 7 launches per step.
- **fused + graph**: the fused step replayed as one CUDA graph.
- **TF32**: the graph step with TF32 tensor cores turned on (`cuda_set_tf32`).
- **fp16 / bf16**: the graph step with data, weights, activations and gradients all in 16-bit storage ([`CudaHalfBuf`](/stdlib/ml#cuda)). GEMMs run on tensor cores with fp32 accumulation, and the elementwise math is done in fp32 and rounded once per stored value. The loss stays fp32. The PyTorch fp16/bf16 rows cast the same model and data with `.to(dtype)` and train entirely in that dtype.

NVIDIA's NGC PyTorch container sets `TORCH_ALLOW_TF32_CUBLAS_OVERRIDE=1`, so PyTorch's fp32 matmuls already run in TF32 by default. The PyTorch fp32 row sets that variable to 0.

Small model (128→256→64, batch 64, 100 training steps, 1000 inference passes):

| Framework | Device | Train (100 steps) | Throughput | Inference (1000 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (`sgemm`, 12 threads) | 58.7 ms | 108,990 samples/s | 294.2 ms | 100.333267 |
| SafeC | CPU (OpenBLAS) | 34.9 ms | 183,644 samples/s | 85.0 ms | 100.333267 |
| SafeC | CUDA (H200), plain | 6.76 ms | 946,326 samples/s | 38.8 ms | 100.333282 |
| SafeC | CUDA, fused | 6.22 ms | 1,028,278 samples/s | 34.8 ms | 100.333290 |
| SafeC | CUDA, fused + graph | 3.80 ms | 1,685,985 samples/s | 19.6 ms | 100.333282 |
| SafeC | CUDA, graph + TF32 | 3.79 ms | 1,688,209 samples/s | 19.6 ms | 100.334084 |
| SafeC | CUDA, graph, fp16 | 2.98 ms | 2,150,538 samples/s | **19.6 ms (fastest)** | 100.335869 |
| SafeC | CUDA, graph, bf16 | **2.97 ms (fastest)** | **2,153,432 samples/s** | 19.6 ms | 99.951088 |
| PyTorch | CPU | 23.6 ms | 270,682 samples/s | 44.9 ms | 100.018272 |
| PyTorch | CUDA, TF32 (NGC default) | 53.3 ms | 120,104 samples/s | 56.7 ms | 103.076630 |
| PyTorch | CUDA, fp32 | 51.0 ms | 125,414 samples/s | 55.6 ms | 103.071129 |
| PyTorch | CUDA, fp16 | 48.1 ms | 132,987 samples/s | 52.8 ms | 103.062500 |
| PyTorch | CUDA, bf16 | 47.8 ms | 133,832 samples/s | 54.5 ms | 103.000000 |
| TensorFlow | CPU | 123.6 ms | 51,777 samples/s | 265.3 ms | 106.182999 |

Bigger model (512→1024→256, batch 128, 50 training steps, 200 inference passes):

| Framework | Device | Train (50 steps) | Throughput | Inference (200 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (`sgemm`, 12 threads) | 340.4 ms | 18,800 samples/s | 403.0 ms | 95.429657 |
| SafeC | CPU (OpenBLAS) | 176.4 ms | 36,285 samples/s | 245.8 ms | 95.424927 |
| SafeC | CUDA (H200), plain | 4.08 ms | 1,569,397 samples/s | 8.23 ms | 95.427338 |
| SafeC | CUDA, fused | 3.79 ms | 1,690,885 samples/s | 7.50 ms | 95.427330 |
| SafeC | CUDA, fused + graph | 3.13 ms | 2,046,036 samples/s | 5.57 ms | 95.427330 |
| SafeC | CUDA, graph + TF32 | 2.31 ms | 2,772,964 samples/s | 5.56 ms | 90.685493 |
| SafeC | CUDA, graph, fp16 | **1.90 ms (fastest)** | **3,375,527 samples/s** | **3.90 ms (fastest)** | 93.444328 |
| SafeC | CUDA, graph, bf16 | 1.90 ms | 3,371,970 samples/s | 3.92 ms | 62.006176 |
| PyTorch | CPU | 61.5 ms | 104,131 samples/s | 54.5 ms | 106.544487 |
| PyTorch | CUDA, TF32 (NGC default) | 17.8 ms | 360,360 samples/s | 11.8 ms | 871.826538 |
| PyTorch | CUDA, fp32 | 17.8 ms | 359,268 samples/s | 12.6 ms | 1754.826172 |
| PyTorch | CUDA, fp16 | 16.9 ms | 378,810 samples/s | 10.5 ms | 1281.000000 |
| PyTorch | CUDA, bf16 | 16.8 ms | 380,839 samples/s | 10.5 ms | 91.500000 |
| TensorFlow | CPU | 227.7 ms | 28,111 samples/s | 238.0 ms | 316.611603 |

- **SafeC versus PyTorch on the GPU, fp32 against fp32**: training is 13× faster on the small model (3.80 ms vs 51.0 ms) and 5.7× on the big one (3.13 ms vs 17.8 ms); inference is 2.8× and 2.3× faster. At these sizes a step is a few microsecond-scale kernels plus a blocking loss read, so the cost per launch decides the result. One SafeC step is about 38 µs (one graph launch plus the readback). One PyTorch step is about 510 µs of Python, autograd and dispatcher work around the same handful of cuBLAS calls.
- **fp16 and bf16**: 16-bit storage is SafeC's fastest mode. It trains 22% faster than the TF32 graph on the small model and 18% faster on the big one, where inference is also 30% faster (3.90 ms vs 5.56 ms); small-model inference is launch-bound and doesn't change. Against PyTorch in the same dtype, SafeC trains 16× (small) and 8.9× (big) faster, and runs inference 2.7× faster on both. fp16 and bf16 take the same time. PyTorch gains only 6–10% from 16-bit here, since its steps are bound by host overhead. As with TF32, this loop amplifies rounding: the big model's loss after 50 steps is 93.44 in fp16 and 62.01 in bf16, against 95.43 in fp32. PyTorch shows the same spread (1754.8 fp32, 1281.0 fp16, 91.5 bf16; it uses its own random data).
- **Where SafeC's time goes**: fusing removes 3 of the 10 launches and saves 8%. The graph saves another 39% on the small model, where launch cost dominates, and 17% on the big one. TF32 changes nothing on the small model, which isn't compute-bound, and takes 26% off training on the big one. With TF32, the big model's loss after 50 steps is 90.69 instead of 95.43: this loop amplifies rounding differences, and the CPU, fp32 GPU and ROCm runs all agree to 4 digits.
- **Before this backend**: in v1.0.0-beta-5, `tensor_matmul_cuda_blas` created and destroyed a CUDA context and a cuBLAS handle on every call. On this GPU the small benchmark took 83 s to train and 816 s for inference through that path, more than 20,000× slower than the fused + graph row.
- **CPU**: SafeC's own row now runs `tensor_matmul` on [`sgemm`](/stdlib/simd#gemm) on the [thread pool](/stdlib/sync#parallel). The beta-5 scalar loop took 144.0 ms and 2323.4 ms to train here, so that is 2.5× faster on the small model and 6.8× on the big one. OpenBLAS (AVX-512 at run time) is still 1.7–1.9× faster than `sgemm` built for SSE2, and PyTorch's CPU kernels are 1.5× (small) and 2.9× (big) faster than OpenBLAS.

### Convolution and FFT on the H200 {#h200-conv-fft}

SafeC's CPU code (`cnn.h`, `unet.h`, `fft.h` on `sgemm` and the thread pool), the GPU libraries [`gpu_cudnn.h` and `fft_cuda.h`](/stdlib/ml#cuda-cudnn-cufft) (cuDNN 9.15, cuFFT from CUDA 13.0), and PyTorch on both devices. All
tensors stay on the GPU between calls. The SafeC CUDA and PyTorch-CUDA rows
are the mean of 300–2000 queued calls between two synchronizations. The CPU
rows are the mean of a few calls after one untimed warm-up. Each number is the best of 3 runs, with the
training job paused.

SafeC builds each convolution shape's plan on the first call. It times the
engine configurations cuDNN's graph API suggests for the fused conv + bias
(+ ReLU) graph, and the legacy API's best algorithm, and keeps the fastest.
The PyTorch rows also time their candidates (`cudnn.benchmark = True`); with
PyTorch's default heuristic the times were within 1.5%. "fp32" sets
`cudnn.allow_tf32 = False` in PyTorch, and SafeC leaves `cuda_set_tf32` off.
"TF32" turns both on. PyTorch's default is TF32 for convolutions.

Convolution, ms per call (lower is better). `conv64` is one Conv2D layer, 64→64
channels, 3×3, stride 1, padding 1, on 56×56 maps, plus bias. `unet128` is
`unet_new(3, 32, 64, 3)` forward at 128×128: five convolutions, two max pools,
two upsample + concat steps.

| Framework | Device | conv64, 1 image | conv64, 32 images | unet128, 1 image | unet128, 16 images |
|---|---|---|---|---|---|
| SafeC | CPU (im2col + `sgemm`, 12 threads) | 1.53 | — | 6.50 | — |
| PyTorch | CPU (12 threads) | 0.346 | — | 2.93 | — |
| SafeC | CUDA, fp32 | **0.0181** | **0.165** | 0.156 | **0.534** |
| SafeC | CUDA, fp32, U-Net as one graph | | | **0.144** | |
| PyTorch | CUDA, fp32 | 0.0224 | 0.257 | 0.206 | 0.874 |
| SafeC | CUDA, TF32 | **0.0182** | **0.0835** | 0.104 | **0.455** |
| SafeC | CUDA, TF32, U-Net as one graph | | | **0.0873** | |
| PyTorch | CUDA, TF32 (default) | 0.0381 | 0.0967 | 0.271 | 0.553 |

FFT, ms per call. `fft1d` and `fft2d` are complex `double` arrays that start
and end in host memory, so each GPU call includes the upload and the read back
(`cuda_fft`/`cuda_fft2d`; `.to("cuda")`, `torch.fft`, `.cpu()`). `batch` and
`rfft` run on data already on the GPU (`cuda_fft_c2c`/`cuda_fft_r2c`; complex64
and float32); their SafeC CPU rows transform `double` data.

| Framework | Device | fft1d, 2²⁰ points | fft2d, 1024×1024 | batch, 1024 × 4096 | rfft, 1024 × 4096 |
|---|---|---|---|---|---|
| SafeC | CPU (radix-2, 12 threads) | 2.68 | 2.76 | 7.05 | 5.20 |
| PyTorch | CPU (12 threads) | 1.19 | **0.671** | 3.31 | 0.288 |
| SafeC | CUDA | **1.25** | 1.25 | 0.0245 | **0.0108** |
| PyTorch | CUDA | 2.08 | 2.07 | **0.0240** | 0.0117 |

- **Convolutions on the GPU**: SafeC is now faster than PyTorch in every row. The one-image convolution takes 0.0181 ms against 0.0224 ms (1.24×; 2.1× against PyTorch's TF32 default). The batch of 32 is 1.56× faster in fp32 and 1.16× with TF32. The U-Net at batch 16 is 1.64× faster in fp32 and 1.22× with TF32, and at batch 1 with TF32, as one graph, it runs 3.1× faster (0.087 ms against 0.271 ms).
- **What changed**: three gaps from the [earlier run](#h200-conv-fft-before) were profiled with Nsight Systems and closed:
  - *One-image convolution* (was 0.0335 ms vs PyTorch's 0.0224 ms): both ran the same cuDNN kernel. The difference was host time. cuDNN 9's legacy `cudnnConvolutionForward` spends about 38 µs of CPU per call re-deriving its engine, more than the kernel takes, and the bias went through a second call. SafeC now builds a plan with cuDNN's graph API for the fused conv → + bias (→ ReLU) graph and keeps its variant pack. A call costs about 6 µs of host time and launches one kernel.
  - *TF32 at batch 16–32* (was 10–20% behind): the graph API reaches engines the legacy algorithm list doesn't, including the Hopper (`sm90`) kernels PyTorch was using. The U-Net also copied its two skip connections with one memcpy per image, 64 copies per forward pass at batch 16. It now uses two launches of a strided-copy kernel (`cuda_copy_rows`) per concat.
  - *Host-array FFT* (was 2.71 ms vs 2.05 ms): the driver copies pageable memory through its own pinned buffers on one thread, about 1.05 ms per 16 MB on this host. `cuda_write`/`cuda_read` now split copies of 1 MiB and up across four lanes (the caller plus three helper threads). Each lane stages its slice through pinned buffers on its own stream, and that moves 16 MB in 0.45–0.7 ms. The FFT round trip itself got 2.2× faster; the transform is about 41 µs of it.
- **CPU convolution**: im2col + `sgemm` on 12 threads is 111× faster than the beta-5 direct loop on `conv64` (169.8 ms) and 71× faster on the U-Net (459.1 ms). PyTorch's CPU path (oneDNN, AVX-512) is still 4.4× faster on `conv64` and 2.2× on the U-Net. cuDNN is 85× faster than SafeC's CPU path at batch 1, and 42× on the U-Net.
- **FFT on the GPU**: with data on the GPU, SafeC and PyTorch both call cuFFT, and their times match within 10%. For host arrays, PCIe and the host-side copies dominate. SafeC's pinned lanes make its round trip 1.66× faster than PyTorch's `.to("cuda")` … `.cpu()`.
- **FFT on the CPU**: `fft.h`'s twiddle table, `f64x2` butterflies and thread pool make the 2²⁰-point transform 74× faster than beta-5 here (198.1 ms). `cuda_fft`, round trip included, is now 2.1× faster than the CPU transform. PyTorch's CPU FFT is 2.2× faster on `fft1d`, 2.1× on `batch` and 4.1× on `fft2d`. On `rfft`, PyTorch works in float32 and returns only the n/2 + 1 non-redundant bins, while `fft_real_batch` writes all n bins in `double`.

<a id="h200-conv-fft-before"></a>The earlier run, with `main` (449420a), in ms: conv64 0.0335 / 0.163 (fp32), 0.0335 / 0.106 (TF32); unet128 0.175 / 0.731 (fp32), 0.176 / 0.663 (TF32), 0.164 / 0.123 as one graph; fft1d / fft2d 2.71 / 2.71.

### Classic benchmarks on the Xeon 8570 {#xeon-8570-classic}

Release-build wall-clock time, best of 3 (lower is better; fastest in bold). Web service is requests per second (higher is better). The Zig sources for the threaded and web benchmarks were written for Zig 0.16 and don't build with 0.17.0, which changed `**` array repetition and removed `@cImport`.

| Benchmark | SafeC | C | C++ | Rust | Zig | Go | Python |
|---|---|---|---|---|---|---|---|
| fib(37) | 0.091s | 0.088s | 0.089s | 0.092s | **0.072s** | 0.131s | 2.432s |
| n-body | **0.144s** | 0.146s | 0.148s | 0.155s | 0.156s | 0.191s | 9.314s |
| binary-trees (SafeC: arena) | **0.279s** | 1.154s | 1.372s | 1.412s | 1.262s | 3.205s | 29.257s |
| binary-trees, SafeC heap | 1.141s | | | | | | |
| binary-trees, 8 threads (SafeC: arena) | **0.099s** | 0.414s | 0.431s | 0.461s | N/A | 0.419s | 74.976s |
| binary-trees, 8 threads, SafeC heap | 0.398s | | | | | | |
| SIMD, explicit vectors | 0.108s | 0.116s | 0.112s | 0.114s | **0.091s** | — | 0.337s (NumPy) |
| Web service (req/s) | **33,902** | 32,001 | 32,210 | 32,747 | N/A | 32,370 | 2,879 |

Collections (1,000,000 elements, ms): `Vec` push 3.22 vs C 1.83, `vec_sort` **75.0** vs `qsort` 148.6, `HashMap` insert 60.0 vs 53.2, get 20.1 vs 18.6, `List` push+pop **17.8** vs 27.6, `Queue` **4.29** vs 6.81, `BST` insert 367.0 vs **316.6**, get 349.6 vs **336.1**. Allocator: `std::alloc`/`dealloc` 10.03 ns per interleaved pair vs `malloc`/`free` **8.49** ns, and the batch pattern takes 159 ms vs **119** ms. The ordering matches the 7800X3D except for the interleaved allocator pattern, where glibc's `malloc` comes out ahead on this machine. Apart from SafeC's arena binary-trees (10% faster, 0.310 → 0.279 s), every row is within 9% of the earlier run on this machine.

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
