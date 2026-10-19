# Benchmarks

How SafeC compares to C, C++, Rust, Zig, Go, and Python on wall-clock time and peak memory, across a few classic microbenchmarks — in the spirit of [The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html) and [programming-language-benchmarks.vercel.app](https://programming-language-benchmarks.vercel.app), scaled down to fit one machine. **Fastest** and **leanest** cells are bolded per column.

::: warning Read this before the numbers below
Single-machine, single-session, best-of-3 — not the Benchmarks Game's more rigorous multi-run methodology. Treat every number as "roughly this, on this machine, this day," not a universal truth about the language. These benchmarks characterize *these workloads*, not overall language performance.
:::

## Methodology {#methodology}

- **Primary machine** (every table below unless marked otherwise): AMD Ryzen 7 7800X3D (8 cores / 16 threads), AMD Instinct MI100 (gfx908, 32 GB HBM2), Ubuntu 24.04.5, native Linux — `safec` 1.0.0 · clang 21.1.8 (C/C++ and SafeC's backend) · Rust 1.100.0-nightly · Go 1.27.2 · Zig 0.16.0 · Python 3.12.3 (+ NumPy 2.5.4) · PyTorch 2.9.1+rocm6.4 · TensorFlow (CPU) · OpenBLAS (pthreads) · ROCm 6.4 (HIP, hiprtc, rocBLAS).
- **Earlier runs**: the "other platforms" tables keep the numbers from an earlier run on an Apple M1 Pro (macOS 26.5.1, Apple Clang 21.0.0), and on the same 7800X3D under WSL2 Ubuntu 22.04 (clang 19.1.7) and Windows 11 (clang/LLVM ~19). They were not re-measured for this update, and the compiler and std have changed since; treat them as directional.
- **Build flags** — debug: `-O0` (C/C++), unoptimized IR (SafeC), `go build -gcflags="all=-N -l"`, Zig Debug, plain `rustc`. Release: `-O2` (C/C++, and SafeC's `clang -O2` backend — matching `safeguard build --release`), plain `go build`, `zig build-exe -O ReleaseFast`, `rustc -O`. Python has no debug/release distinction. SafeC compile time is measured with `--no-incremental`, so it's a cold compile like everyone else's.
- **Timing**: best (lowest) wall-clock of 3 runs. **Memory**: peak RSS of the same runs from GNU `/usr/bin/time -f %M` (the benchmarked process's own peak; a parent's `wait4()` would report the forking interpreter's peak on Linux, which keeps `ru_maxrss` across `exec`). Web servers' peak is `VmHWM` from `/proc/<pid>/status`, read just before shutdown.
- **Correctness**: every binary's output is checked against the known-correct value before being included.
- **Reproduce**: [`run_bench.py`](/benchmarks/run_bench.py) builds and runs everything and writes a JSON file; this page's numbers are [`results/linux-7800x3d-mi100.json`](/benchmarks/results/linux-7800x3d-mi100.json).
- Every source file is linked inline next to its result.

## fib(37) — recursive Fibonacci {#fib37-recursive-fibonacci}

Naive recursive Fibonacci — pure function-call and integer-arithmetic overhead, no allocation, no I/O.

| Language | Debug | Release | Release peak memory | Release compile time |
|---|---|---|---|---|
| SafeC | 0.105s | **0.041s (fastest)** | 2.0 MB | 0.033s |
| C | 0.097s | 0.042s | 1.9 MB | 0.029s |
| C++ | 0.098s | 0.042s | 3.8 MB | 0.051s |
| Rust | 0.123s | 0.042s | 2.0 MB | 0.052s |
| Zig | 0.196s | 0.043s | **0.5 MB (leanest)** | 8.17s |
| Go | **0.094s (fastest)** | 0.089s | 1.9 MB | **0.025s** |
| Python | 2.000s | 1.997s | 10.2 MB | N/A (interpreted) |

Everything that goes through LLVM lands within 4% of each other in release; the differences are noise.

**Other platforms** (release, earlier run):

| Language | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.080s | 0.040s | 0.046s |
| C | 0.070s | 0.040s | 0.046s |
| C++ | 0.070s | 0.040s | 0.047s |
| Rust | 0.090s | 0.040s | 0.049s |
| Zig | 0.080s | 0.040s | 0.053s |
| Go | 0.080s | 0.080s | 0.092s |
| Python | 2.700s | 3.330s | 2.485s |

**Sources:** [fib.sc](/benchmarks/fib/safec/fib.sc) · [fib.c](/benchmarks/fib/c/fib.c) · [fib.cpp](/benchmarks/fib/cpp/fib.cpp) · [fib.rs](/benchmarks/fib/rust/fib.rs) · [fib.zig](/benchmarks/fib/zig/fib.zig) · [fib.go](/benchmarks/fib/go/fib.go) · [fib.py](/benchmarks/fib/python/fib.py)

## n-body — 5-body orbital simulation {#n-body-5-body-orbital-simulation}

The classic Benchmarks Game n-body test (Sun/Jupiter/Saturn/Uranus/Neptune), 2,000,000 steps. Floating-point heavy, no allocation, tiny working set.

| Language | Debug | Release | Release peak memory | Release compile time |
|---|---|---|---|---|
| SafeC | 0.486s | **0.098s (fastest)** | 1.9 MB | 0.049s |
| C | **0.209s (fastest)** | **0.098s (fastest)** | 1.9 MB | 0.053s |
| C++ | 0.210s | **0.098s (fastest)** | 3.9 MB | 0.097s |
| Rust | 0.447s | 0.108s | 2.1 MB | 0.071s |
| Zig | 0.237s | 0.110s | **0.5 MB (leanest)** | 8.28s |
| Go | 0.221s | 0.106s | 1.9 MB | 0.075s |
| Python | 7.765s | 7.757s | 10.9 MB | N/A (interpreted) |

SafeC's debug build is slower than C's here because unoptimized SafeC IR keeps every bounds check and every struct-field access through memory; release removes the difference entirely.

**Other platforms** (release, earlier run):

| Language | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.110s | 0.090s | 0.102s |
| C | 0.100s | 0.090s | 0.103s |
| C++ | 0.110s | 0.090s | 0.104s |
| Rust | 0.110s | 0.100s | 0.113s |
| Zig | 0.110s | 0.110s | 0.116s |
| Go | 0.100s | 0.100s | 0.113s |
| Python | 9.890s | 12.150s | 11.922s |

**Sources:** [nbody.sc](/benchmarks/nbody/safec/nbody.sc) · [nbody.c](/benchmarks/nbody/c/nbody.c) · [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp) · [nbody.rs](/benchmarks/nbody/rust/nbody.rs) · [nbody.zig](/benchmarks/nbody/zig/nbody.zig) · [nbody.go](/benchmarks/nbody/go/nbody.go) · [nbody.py](/benchmarks/nbody/python/nbody.py)

## binary-trees — allocation/deallocation stress {#binary-trees-allocationdeallocation-stress}

Builds and discards millions of small binary trees (max depth 18) — exercises memory management rather than arithmetic. The main SafeC entry uses `region`/`arena<R>` (bump-pointer allocation, `arena_reset<R>()` discards a whole region in O(1)) instead of the heap — this workload is all short-lived, same-scope allocations, exactly what regions are for. The **SafeC (heap)** row is the same program with `new` / `std::dealloc`, for a like-for-like comparison with the other languages' `malloc`/`Box`/GC.

| Language | Debug | Release | Release peak memory | Release compile time |
|---|---|---|---|---|
| SafeC (arena) | **0.234s (fastest)** | **0.138s (fastest)** | **25.8 MB (leanest)** | 0.044s |
| SafeC (heap) | 0.927s | 0.709s | 50.8 MB | 0.044s |
| C | 0.955s | 0.800s | 33.8 MB | 0.039s |
| C++ | 1.177s | 0.916s | 35.8 MB | 0.055s |
| Rust | 1.745s | 0.912s | 33.9 MB | 0.063s |
| Zig | 5.021s | 0.851s | 33.6 MB | 9.85s |
| Go | 1.830s | 0.723s | 37.1 MB | 0.077s |
| Python | 17.965s | 17.965s | 82.6 MB | N/A (interpreted) |

The heap variant is faster than C because `std::alloc` caches freed blocks per size class (see [Memory allocation](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree)), but uses more memory: each block carries a 16-byte safety header, and the cache keeps freed blocks instead of returning them.

**Other platforms** (release, earlier run, SafeC with arena):

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

**Sources:** [binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc) · [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c) · [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp) · [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs) · [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig) · [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go) · [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)

## Collections — std::collections throughput (1,000,000 elements) {#collections-stdcollections-throughput-1000000-elements}

The generic containers against a plain C implementation of the same algorithms: growable array + `qsort`, a linear-probing hash map with the same hash, a `malloc`-per-node doubly linked list, a power-of-two ring queue, and an unbalanced BST. Both sides use identical shuffled keys (xorshift64), clang 21.1.8 `-O2` for both (`safeguard build --release`), best of 3.

| Operation | SafeC | C | SafeC / C |
|---|---|---|---|
| `Vec<int>` push | 2.50 ms | **1.83 ms** | 1.37× |
| `vec_sort` (introsort, inlined `Less<int>`) vs `qsort` | **43.4 ms** | 83.6 ms | 0.52× |
| `HashMap<int,int>` insert | 39.1 ms | **35.5 ms** | 1.10× |
| `HashMap<int,int>` get | 8.39 ms | **7.45 ms** | 1.13× |
| `List<int>` push_back + pop_front | **11.5 ms** | 22.5 ms | 0.51× |
| `Queue<int>` enqueue + dequeue | **3.41 ms** | 5.46 ms | 0.62× |
| `BST<int,int,Less<int>>` insert | 177.1 ms | **176.6 ms** | 1.00× |
| `BST<int,int,Less<int>>` get | **172.1 ms** | 174.2 ms | 0.99× |

What decides these:

- **Sorting** beats `qsort` because the ordering is a type parameter: every comparison inlines, where `qsort` makes an indirect call per comparison.
- **List** and **BST** nodes come from a per-container node pool (chunked bump allocation plus a free list), not one allocation per node; that avoids both `malloc`'s per-chunk overhead and `std::alloc`'s 16-byte safety header. BST lookups are dominated by cache misses on the random walk, the same for both.
- **HashMap** keys of 1/2/4/8 bytes are hashed as one word (murmur3 finalizer) and compared with one integer compare, chosen with `if const` at compile time. The remaining gap is the tombstone bookkeeping on insert.
- **Vec push** pays for bounds-checked access (`keys.at(i)`) and `std::alloc`'s checked `realloc` path.

**Sources:** [bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc) · [bench_collections.c](/benchmarks/collections/c/bench_collections.c)

## Multithreaded — binary-trees, 8 threads {#multithreaded-binary-trees-8-threads}

Same binary-trees workload, parallelized across 8 worker threads — each thread builds/checksums an independent slice of the tree count at a given depth, joined before the next depth. Release only. SafeC (arena) uses one `region`/`arena<R>` per thread (arena state isn't shared or locked); SafeC (heap) uses `new` / `std::dealloc`.

| Language | 8-thread time | Peak memory | Speedup vs. 1 thread |
|---|---|---|---|
| SafeC (arena) | **0.052s (fastest)** | 98.2 MB | 2.68× |
| SafeC (heap) | 0.267s | 260.3 MB | 2.66× |
| C | 0.215s | 161.2 MB | 3.72× |
| C++ | 0.229s | 163.5 MB | 4.00× |
| Rust | 0.234s | 162.0 MB | 3.89× |
| Zig | 0.222s | 163.8 MB | 3.83× |
| Go | 0.252s | **94.7 MB (leanest)** | 2.87× |
| Python | 22.475s | 314.1 MB | 0.80× (slower than 1 thread) |

The heap variant's memory is high because each thread's size-class cache keeps its own freed blocks.

**Other platforms** (8-thread time, earlier run, SafeC with arena):

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

**Sources:** [binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc) · [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c) · [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp) · [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs) · [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig) · [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go) · [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)

## SIMD — sum of squares over 20,000,000 f64 values {#simd-sum-of-squares-over-20000000-f64-values}

Plain scalar loop vs. each language's explicit vector type at `-O2`/release — isolates what explicit SIMD buys *on top of* the backend's auto-vectorizer, not "vectorized vs. deliberately crippled." SafeC: native `vec<double,4>` (lowers to LLVM's target-generic `FixedVectorType`, no per-architecture source needed). C/C++: GCC/Clang vector extensions. Zig: `@Vector`. Rust: stable-channel intrinsics (`std::simd` needs nightly features) — SSE2 on x86-64 ([simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs)), NEON on AArch64 ([simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs)). Go has no portable SIMD type, scalar only.

| Language | Scalar | Explicit SIMD | Speedup |
|---|---|---|---|
| SafeC | 0.082s | 0.076s | 1.08× |
| C | 0.078s | **0.069s (fastest)** | 1.14× |
| C++ | 0.080s | 0.076s | 1.06× |
| Rust | 0.084s | 0.075s | 1.11× |
| Zig | **0.078s (fastest)** | 0.071s | 1.10× |
| Go | 0.088s | N/A | — |
| Python | 3.022s | 0.189s (NumPy) | 16.0× |

This loop is memory-bound on the 7800X3D (160 MB of doubles streams far past the 96 MB L3), so explicit SIMD buys little over the auto-vectorized scalar loop; the whole table is within ~10 ms, about the size of process-startup noise.

**Other platforms** (earlier run):

| Language | Scalar: macOS / WSL2 / Windows | Explicit SIMD: macOS / WSL2 / Windows |
|---|---|---|
| SafeC | 0.040s / 0.099s / 0.067s | 0.030s / 0.092s / 0.060s |
| C | 0.040s / 0.099s / 0.077s | 0.030s / 0.092s / 0.060s |
| C++ | 0.040s / 0.101s / 0.073s | 0.030s / 0.092s / 0.073s |
| Rust | 0.040s / 0.099s / 0.044s | 0.030s / 0.093s / 0.039s |
| Zig | 0.040s / 0.096s / 0.065s | 0.030s / 0.089s / 0.069s |
| Go | 0.060s / 0.105s / 0.074s | N/A |
| Python | 3.500s / 3.144s / 3.406s | 0.140s / 0.213s / 0.313s (NumPy) |

**Sources:** [simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc) · [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c) · [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp) · [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs) · [simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs) · [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig) · [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go) · [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)

## Web service — JSON "hello world" endpoint {#web-service-json-hello-world-endpoint}

`GET /` returning `{"message":"Hello, World!"}` — the same shape as TechEmpower's "JSON serialization" test, via Apache Bench (`ab -n 20000 -c 50`, no keep-alive, after a 2,000-request warm-up) against each language's own HTTP story: SafeC's `std::http_serve_reactor` (epoll reactor on Linux); a minimal raw-socket accept loop for C/C++/Zig; Go's `net/http`; Python's FastAPI+uvicorn; Rust's **axum** (not Dioxus — Dioxus's fullstack server layer *is* axum underneath, so this measures what it actually runs through).

| Language | req/s | p50 / p99 latency | Peak memory |
|---|---|---|---|
| SafeC | **25,263 (fastest)** | 2 / 2 ms | 5.1 MB |
| C | 24,577 | 2 / 2 ms | **3.2 MB (leanest)** |
| C++ | 24,837 | 2 / 2 ms | 5.0 MB |
| Rust | 25,027 | 2 / 2 ms | 4.4 MB |
| Zig | 24,766 | 2 / 2 ms | 7.0 MB |
| Go | 23,354 | 2 / 3 ms | 15.8 MB |
| Python | 5,083 | 10 / 14 ms | 46.0 MB |

Every server completed every request with zero failures. The compiled servers all land within 8% of each other: without keep-alive, each request is a fresh TCP connection, and the kernel's connect/accept path and `ab` itself are the bottleneck, not the server.

**Other platforms** (earlier run; Windows used a small Go load generator at `-n 5000 -c 20` instead of `ab`, so compare that column only with itself):

| Language | macOS req/s (p50/p99) | WSL2 req/s (p50/p99) | Windows req/s (p50/p99) |
|---|---|---|---|
| SafeC | 36055 (1/5ms) | 17650 (3/4ms) | 2313 (7.5/19.6ms) |
| C | 25303 (2/2ms) | N/A | N/A |
| C++ | 24148 (2/4ms) | N/A | N/A |
| Rust | 27270 (2/2ms) | 18077 (3/3ms) | 465 (33.0/474.3ms) |
| Zig | 25742 (2/2ms) | N/A | N/A |
| Go | 25274 (2/3ms) | 18758 (3/4ms) | 2467 (7.8/16.9ms) |
| Python | 4790 (10/33ms) | 2357 (21/27ms) | 528 (37.3/52.5ms) |

**Sources:** [server_reactor.sc](/benchmarks/webservice/safec/server_reactor.sc) · [server.sc](/benchmarks/webservice/safec/server.sc) · [server.c](/benchmarks/webservice/c/server.c) · [server.cpp](/benchmarks/webservice/cpp/server.cpp) · [main.rs](/benchmarks/webservice/rust/src/main.rs) · [Cargo.toml](/benchmarks/webservice/rust/Cargo.toml) · [server.zig](/benchmarks/webservice/zig/server.zig) · [server.go](/benchmarks/webservice/go/server.go) · [server.py](/benchmarks/webservice/python/server.py) · [http.sc](/benchmarks/stdlib/http.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h) · [task.h](/benchmarks/stdlib/task.h)

## Machine learning — small MLP, training and inference {#machine-learning-small-mlp-training-and-inference}

2-layer MLP (`relu(X @ W1) @ W2`, 128→256→64, batch 64, MSE loss, hand-rolled SGD, no bias) — 100 training steps then 1000 inference passes, fixed seed. Every framework reads the loss back to the host every step and `Y[0][0]` every inference pass (`.item()` in PyTorch), so GPU rows pay a host round-trip per step, as a real training loop that logs its loss would. Each framework gets one untimed warm-up pass first: PyTorch's first `backward()` on this build takes ~10 s of one-time initialization even on CPU, and rocBLAS loads each GEMM shape's kernels on first use.

| Framework | Device | Train (100 steps) | Throughput | Inference (1000 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (naive) | 110.9 ms | 57,700 samples/s | 286.0 ms | 100.333427 |
| SafeC | CPU (OpenBLAS) | 31.3 ms | 204,400 samples/s | 55.3 ms | 100.333420 |
| SafeC | ROCm (MI100) | 7.25 ms | 882,500 samples/s | 35.4 ms | 100.333282 |
| SafeC | ROCm, graph replay | **7.04 ms (fastest)** | **909,000 samples/s** | 39.4 ms | 100.333282 |
| PyTorch | CPU | 14.0 ms | 458,400 samples/s | **22.0 ms (fastest)** | 100.018272 |
| PyTorch | ROCm (MI100) | 19.6 ms | 326,600 samples/s | 36.5 ms | 103.071129 |
| TensorFlow | CPU | 79.8 ms | 80,200 samples/s | 207.2 ms | 106.182999 |

At this size every GPU step is a handful of microsecond-scale kernels plus a blocking read of the loss, so launch and synchronization latency dominate. PyTorch's CPU path (its bundled, tuned GEMM kernels) is faster than SafeC's OpenBLAS path on CPU, and is the fastest at inference. Losses differ between frameworks because each draws its data and initial weights from its own RNG; the SafeC rows all share one seeded stream (`drand48`), and agree to 6 digits across CPU and GPU.

**Other platforms** (earlier run, Apple M1 Pro):

| Framework | Device | Train (100 steps) | Inference (1000 passes) |
|---|---|---|---|
| SafeC | CPU (Accelerate BLAS) | 16.8 ms | 26.1 ms |
| SafeC | MPS | 39.4 ms | 61.1 ms |
| PyTorch | CPU | 23.1 ms | 13.5 ms |
| PyTorch | MPS | 131.6 ms | 358.7 ms |
| TensorFlow | CPU | 83.0 ms | 245.0 ms |
| TensorFlow | GPU | 245.3 ms | 924.6 ms |
| MLX | GPU | 72.2 ms | 309.1 ms |

[train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py)

## Machine learning, bigger model — 512→1024→256 {#machine-learning-bigger-model-512-1024-256}

Same model scaled ~50x (batch 128). 50 training steps, 200 inference passes; same warm-up and per-step readback as above.

| Framework | Device | Train (50 steps) | Throughput | Inference (200 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (naive) | 1794.1 ms | 3,600 samples/s | 1503.3 ms | 95.428474 |
| SafeC | CPU (OpenBLAS) | 170.9 ms | 37,500 samples/s | 221.4 ms | 95.424973 |
| SafeC | ROCm (MI100) | 8.31 ms | 770,400 samples/s | **13.07 ms (fastest)** | 95.428131 |
| SafeC | ROCm, graph replay | **8.09 ms (fastest)** | **790,900 samples/s** | 14.01 ms | 95.428116 |
| PyTorch | CPU | 38.2 ms | 167,500 samples/s | 50.4 ms | 106.601181 |
| PyTorch | ROCm (MI100) | 10.14 ms | 631,200 samples/s | 13.30 ms | 1868.155273 |
| TensorFlow | CPU | 107.3 ms | 59,700 samples/s | 114.7 ms | 316.611603 |

On the GPU SafeC trains ~20% faster than PyTorch; inference is a tie (both are a pair of rocBLAS GEMMs plus one elementwise kernel and one readback per pass). Graph replay records a whole training step into one `RocmGraph` and launches it with one call; with a blocking loss read every step it only saves ~3%. Without the per-step read, a C++ prototype of the same loop ran 3.8 ms vs 7.1 ms. PyTorch-ROCm's loss diverges at lr 0.001 from its GPU RNG's initial weights. That says nothing about speed, but it is why its sanity-check value looks wrong.

On CPU, OpenBLAS here (generic pthreads build, Ubuntu package) is the weak spot: PyTorch's CPU GEMM is 4.5× faster for training at this size.

**Other platforms** (earlier run, Apple M1 Pro):

| Framework | Device | Train (50 steps) | Inference (200 passes) |
|---|---|---|---|
| SafeC | CPU (Accelerate BLAS) | 89.2 ms | 61.4 ms |
| SafeC | MPS | 47.9 ms | 47.3 ms |
| PyTorch | CPU | 67.8 ms | 48.2 ms |
| PyTorch | MPS | 110.5 ms | 86.4 ms |
| TensorFlow | CPU | 148.8 ms | 197.6 ms |
| TensorFlow | GPU | 154.1 ms | 218.3 ms |
| MLX | GPU | 54.3 ms | 74.4 ms |

[train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_cpu_blas.sc](/benchmarks/ml_big/safec/train_cpu_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc) · [PyTorch train.py](/benchmarks/ml_big/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml_big/tensorflow/train.py) · [MLX train.py](/benchmarks/ml_big/mlx/train.py)

## Machine learning, GPU backends — CUDA, ROCm, Vulkan/SPIR-V, WebGPU {#machine-learning-gpu-backends-cuda-rocm-vulkanspir-v-webgpu}

| Backend | Elementwise ops | Matmul (std kernel) | Matmul (vendor BLAS) | Status |
|---|---|---|---|---|
| ROCm / HIP | real (HIP C++, compiled at first use by hiprtc) | real (64×64 LDS-tiled SGEMM) | real (rocBLAS) | **measured** on MI100: 29 checks in `tests/stdlib/rocm`, and the MLP results above |
| CUDA | real (PTX, text IR) | real (PTX) | real (cuBLAS) | unmeasured — no NVIDIA GPU here |
| Vulkan/SPIR-V | always returns 0 | always returns 0 | n/a | SPIR-V is binary IR; no glslc/glslangValidator here |
| WebGPU | real (WGSL, text IR) | real (WGSL) | n/a | unmeasured — no wgpu-native/Dawn library here |

The ROCm backend embeds its kernels as HIP source and compiles them once per process for whatever GPU it finds (any gfx target ROCm supports, no prebuilt HSACO). It picks the device with the most compute units and prefers a discrete GPU. Besides elementwise math it has fused training kernels: `rocm_relu_grad`, `rocm_mse_grad` (gradient and loss in one pass), `rocm_sgd`, and `rocm_sum` (wavefront shuffle, LDS, then one atomic per block). It also records and replays HIP graphs (`rocm_capture_begin` / `rocm_capture_end` / `rocm_graph_launch`).

The std SGEMM kernel reaches ~9.5 TFLOP/s on the MI100 at 4096³; rocBLAS reaches 33.7 TFLOP/s using the matrix cores. So `rocm_gemm`, `rocm_matmul_f32_blas` and `tensor_matmul_rocm_blas` call rocBLAS, and the std kernel serves `rocm_matmul_f32` and `tensor_matmul_rocm`. Where a custom kernel does help is fusion: the gradient, loss and update kernels each replace two to three separate launches plus a reduction.

[gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [tensor_rocm.h](/benchmarks/stdlib/tensor_rocm.h) · [tensor_rocm.sc](/benchmarks/stdlib/tensor_rocm.sc) · [gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gpu_spirv.h](/benchmarks/stdlib/gpu_spirv.h) · [gpu_spirv.sc](/benchmarks/stdlib/gpu_spirv.sc) · [gpu_webgpu.h](/benchmarks/stdlib/gpu_webgpu.h) · [gpu_webgpu.sc](/benchmarks/stdlib/gpu_webgpu.sc) · [tensor_cuda.h](/benchmarks/stdlib/tensor_cuda.h) · [tensor_cuda.sc](/benchmarks/stdlib/tensor_cuda.sc)

## Machine learning, fp16 / bf16 support {#machine-learning-fp16-bf16-support}

No native 16-bit float in SafeC's type system (`Type.h`'s `TypeKind` has only `Float32`/`Float64` — a real compiler feature, not a stdlib change). Instead, the standard workaround: fp16/bf16 carried as raw bits in `unsigned short`, with explicit correctly-rounded (round-to-nearest-even) conversion to/from `float` — and real native `half`/`bfloat` GPU compute on the MPS backend, not just halved storage.

| Check | Result |
|---|---|
| Known bit patterns (fp16: 1.0, -1.0, 0.0, -0.0, 2.0, 0.5, max normal 65504, overflow→inf, smallest subnormal, inf; bf16: 1.0, -1.0, 0.0, 2.0, π) | all exact |
| fp16 subnormal idempotence sweep (every mantissa 1–1023, `fp16→f32→fp16`) | 1023/1023 exact |
| fp16 normal-range idempotence sweep (every exponent × sampled mantissas, 330 patterns) | 330/330 exact |
| fp16 round-trip on representable values | exact |
| bf16 round-trip (lossy by construction — 7 mantissa bits) | within ~0.4% relative error |

[float16.h](/benchmarks/stdlib/float16.h) · [float16.sc](/benchmarks/stdlib/float16.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gpu_webgpu.h](/benchmarks/stdlib/gpu_webgpu.h) · [gpu_webgpu.sc](/benchmarks/stdlib/gpu_webgpu.sc) · [train_gpu_f16.sc](/benchmarks/ml/safec/train_gpu_f16.sc) · [train_gpu_bf16.sc](/benchmarks/ml/safec/train_gpu_bf16.sc)

## Machine learning, device selection {#machine-learning-device-selection}

Every backend names its op explicitly (`tensor_matmul` vs `_blas` vs `_gpu` vs `_cuda` vs `_rocm`) — precise, but awkward for a caller that wants to pick a device once. `tensor_matmul_on(a, b, device)` / `tensor_relu_on(a, device)` dispatch over a `Device` enum instead — verified bit-identical across CPU/CPU+BLAS/MPS:

| Device | Y[0][0] | Y[0][1] |
|---|---|---|
| CPU | 0.900000 | 1.300000 |
| CPU + BLAS | 0.900000 | 1.300000 |
| MPS | 0.900000 | 1.300000 |

Building this exposed a real name collision: `activations.sc` (forward-only ops) and `tensor_nn.sc` (autograd ops, needed by every GPU backend) both defined `tensor_sigmoid`/`tensor_relu`/etc. — no program could link both, so no `activations.sc`-based layer could use a GPU backend. Fixed by suffixing `activations.h`'s forward-only functions with `_fwd` and updating `attention.sc`/`transformer.sc`/`rnn.sc`'s call sites.

`jit_block_forward_on(block, x, device)` threads device selection through a real layer (JiTBlock's Q/K/V/output projections + FFN) — verified bit-identical to the reference across CPU/CPU+BLAS/MPS:

| Path | y[0] | y[1] | y[2] |
|---|---|---|---|
| `jit_block_forward` (reference) | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU_BLAS)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_MPS)` | -0.479727 | -0.589425 | -0.537808 |

Not yet device-routed: attention's internal QK^T/softmax/·V matmuls, `DiTBlock`, `cnn.sc`.

[tensor_dispatch.h](/benchmarks/stdlib/tensor_dispatch.h) · [tensor_dispatch.sc](/benchmarks/stdlib/tensor_dispatch.sc) · [transformer_dispatch.h](/benchmarks/stdlib/transformer_dispatch.h) · [transformer_dispatch.sc](/benchmarks/stdlib/transformer_dispatch.sc) · [activations.h](/benchmarks/stdlib/activations.h) · [activations.sc](/benchmarks/stdlib/activations.sc) · [attention.h](/benchmarks/stdlib/attention.h) · [attention.sc](/benchmarks/stdlib/attention.sc) · [transformer.h](/benchmarks/stdlib/transformer.h) · [transformer.sc](/benchmarks/stdlib/transformer.sc)

## Memory allocation — is `std::alloc`/`dealloc` slower than raw `malloc`/`free`? {#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree}

`std::alloc` puts a 16-byte header on every block, which carries the double-free/use-after-free check, and keeps a size-class cache: the same idea as PyTorch's CPU/CUDA caching allocators and MLX's Metal buffer cache. A freed block goes into a thread-local free list bucketed by power-of-two size class, and the next same-class `alloc()` is served from there without calling `malloc()`/`free()`. Cached blocks keep their "freed" magic word until reused, so the safety checks still work. Measured against glibc's `malloc`/`free` (best of 3; every block's address goes into a printed checksum so the optimizer can't delete an alloc/free pair):

| Pattern | `std::alloc` / `dealloc` | `malloc` / `free` | Ratio |
|---|---|---|---|
| Interleaved: allocate one 48-byte block, free it, 20M times | **3.84 ns / pair** | 6.8 ns / pair | 1.8× faster |
| Batch: allocate 1M 24-byte blocks, then free them all, 10 rounds | 131 ms | **92 ms** | 1.4× slower |

The cache wins whenever freed blocks are reused soon. Short-lived objects and binary-trees-style churn work like this: the heap binary-trees above beats every other language's allocator. A million live blocks at once is more than the cache holds (125,587 slots across all classes), so most allocations in the batch pattern go through `malloc` plus the header write and cache bookkeeping, and that overhead shows. For bulk short-lived allocation, `region`/`arena<R>` is the faster tool: 5.1× faster than the heap version on binary-trees.

[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc) · [alloc_bench.sc](/benchmarks/memalloc/safec/alloc_bench.sc)
