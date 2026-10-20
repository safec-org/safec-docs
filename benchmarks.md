# Benchmarks

SafeC against C, C++, Rust, Zig, Go and Python on a few classic microbenchmarks (in the spirit of [The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html)), plus the standard library's collections, HTTP server, ML tensors, DSP and allocator. One machine, best of 3 runs: read every number as "roughly this, on this machine", not as a ranking of languages.

## At a glance {#at-a-glance}

Release-build wall-clock time (lower is better; fastest in bold). SafeC's binary-trees rows use its `region`/`arena` allocation; the heap version is in [binary-trees](#binary-trees-allocationdeallocation-stress).

| Benchmark | SafeC | C | C++ | Rust | Zig | Go | Python |
|---|---|---|---|---|---|---|---|
| [fib(37)](#fib37-recursive-fibonacci) | **0.042s** | **0.042s** | **0.042s** | **0.042s** | 0.043s | 0.089s | 2.016s |
| [n-body](#n-body-5-body-orbital-simulation) | **0.098s** | 0.099s | 0.099s | 0.107s | 0.110s | 0.106s | 7.630s |
| [binary-trees](#binary-trees-allocationdeallocation-stress) | **0.132s** | 0.810s | 0.924s | 0.914s | 0.854s | 0.718s | 18.049s |
| [binary-trees, 8 threads](#multithreaded-binary-trees-8-threads) | **0.053s** | 0.226s | 0.227s | 0.232s | 0.226s | 0.248s | 22.958s |
| [SIMD](#simd-sum-of-squares-over-20000000-f64-values) (explicit vectors) | 0.075s | 0.077s | 0.074s | 0.077s | **0.072s** | — | — |
| [Web service](#web-service-json-hello-world-endpoint) (req/s, higher is better) | **25,303** | 24,620 | 24,845 | 25,075 | 25,030 | 23,199 | 5,096 |

- **Machine learning** (2-layer MLP, [details](#machine-learning-small-mlp-training-and-inference)): on the MI100, SafeC trains the small model in 7.0 ms vs PyTorch-ROCm's 20.5 ms (fp32), and the big one in 7.4 ms vs 10.7 ms. In fp16 the big one takes 5.9 ms vs 9.9 ms. On the CPU, SafeC trains the small model in 9.6 ms (OpenBLAS) vs PyTorch's 14.0 ms, and the big one in 36.5 ms (AOCL-BLAS) vs 38.4 ms.
- **NVIDIA H200** ([details](#h200-machine-learning)): SafeC's CUDA backend trains the small model in 3.81 ms vs PyTorch-CUDA's 51.3 ms (fp32), and the big one in 3.13 ms vs 15.9 ms. In fp16 the big one takes 1.90 ms vs 17.3 ms. Results for the Xeon 8570 CPU are in [their own section](#xeon-8570-classic).
- **Convolution and FFT on the MI100** ([details](#mi100-conv-fft)): with MIOpen, SafeC runs one 64-channel 3×3 convolution in 0.027 ms vs PyTorch-ROCm's 0.063 ms and the batch-1 U-Net in 0.222 ms vs 0.314 ms; at batch 16–32 the two are within 2%. Device-resident hipFFT transforms are 1.3–1.7× faster than through PyTorch.
- **CPU convolution and FFT** ([details](#mi100-conv-fft)): with Winograd 3×3 convolutions and per-image arenas the U-Net runs in 1.35 ms on the CPU vs PyTorch's 2.68 ms. A 2²⁰-point FFT takes 0.89 ms vs 16-thread FFTW's 1.63 ms; FFTW is 1.1–1.6× faster on the 2D, batched and real transforms.
- **DSP** ([details](#dsp-cpu)): `std/dsp` is the fastest of SafeC, SciPy, KFR, Q, madronalib and dasp on every workload measured, in double and in float. A biquad over 2²⁰ samples takes 0.48 ms vs Q's 1.69 ms (float), a 127-tap streaming FIR 3.0 ms vs SciPy's 19.5 ms, and resampling 44.1→48 kHz 3.7 ms vs SciPy's 9.1 ms.
- **Attention on the H200** ([details](#h200-attention)): fp16/bf16 flash attention through cuDNN matches PyTorch (within 3%, up to 690 TFLOPS); fp32 flash attention on tensor cores (3xTF32) is 1.34× faster than PyTorch at head dim 64 and 0.97–1.05× at 128. Linear attention is up to 2.6× faster than PyTorch and up to 2.1× faster than flash-linear-attention, level with PyTorch's TF32 einsums only at head dim 128 fp32 non-causal (while computing in exact fp32).
- **Attention on the MI100** ([details](#mi100-attention)): fp32 flash attention runs on the matrix cores at 18–26 TFLOPS. That is 1.8–4× faster than PyTorch-ROCm without a mask and 4–10× faster with a causal one. Linear attention is 2–5.4× faster.
- **M1 Pro** ([details](#m1pro)): `std/dsp` is the fastest on every DSP row against SciPy, KFR, Q, madronalib and dasp, with FIR another 1.2–1.4× faster through Accelerate's vDSP. binary-trees with arenas is 7× faster than C (9× with 8 threads); fib and SIMD match C; n-body is 0.94× C and 0.87× Go.
- **Attention on the M1 Pro** ([details](#m1pro-attention)): fp32 flash attention on Metal is 1.01–1.42× faster than MLX and 1.13–1.70× faster than PyTorch's `mps` device; bf16 at head dim 128 is level with MLX and 1.06–1.10× faster than PyTorch; fp16 and bf16 at 64 reach 0.80–1.02× of them. Linear attention is 2.0–4.4× faster than vectorized PyTorch and MLX.
- **Convolution and FFT on the H200** ([details](#h200-conv-fft)): with cuDNN, SafeC is faster than PyTorch-CUDA in every convolution row. A one-image 64-channel 3×3 convolution takes 0.018 ms vs 0.023 ms (fp32), a batch of 32 takes 0.084 ms vs 0.098 ms (TF32), and the U-Net at batch 16 takes 0.45 ms vs 0.56 ms (TF32). A 2²⁰-point FFT of a host array, including both PCIe copies, takes 1.25 ms vs 2.05 ms. Device-resident cuFFT transforms take the same time as through PyTorch.
- **Collections** ([details](#collections-stdcollections-throughput-1000000-elements)): `vec_sort` is 1.9× faster than `qsort` and the pooled list 2.2× faster than a `malloc`-per-node one; `HashMap` is within 1.1× of plain C and `Vec` push 1.5× slower.
- **Allocator** ([details](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree)): `std::alloc` is 1.8× faster than `malloc` when blocks are reused, and with its slabs 1.1× faster with a million blocks live at once.

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
| SafeC | **0.042s (fastest)** | 1.9 MB | 0.036s |
| C | **0.042s (fastest)** | 1.9 MB | 0.030s |
| C++ | **0.042s (fastest)** | 3.8 MB | 0.049s |
| Rust | **0.042s (fastest)** | 1.9 MB | 0.050s |
| Zig | 0.043s | **0.6 MB (leanest)** | 8.18s |
| Go | 0.089s | 2.0 MB | **0.025s** |
| Python | 2.016s | 10.4 MB | N/A (interpreted) |

Everything that goes through LLVM lands within 4% of each other in release; the differences are noise.

::: details Sources
[fib.sc](/benchmarks/fib/safec/fib.sc) · [fib.c](/benchmarks/fib/c/fib.c) · [fib.cpp](/benchmarks/fib/cpp/fib.cpp) · [fib.rs](/benchmarks/fib/rust/fib.rs) · [fib.zig](/benchmarks/fib/zig/fib.zig) · [fib.go](/benchmarks/fib/go/fib.go) · [fib.py](/benchmarks/fib/python/fib.py)
:::

## n-body — 5-body orbital simulation {#n-body-5-body-orbital-simulation}

The classic Benchmarks Game n-body test (Sun/Jupiter/Saturn/Uranus/Neptune), 2,000,000 steps. Floating-point heavy, no allocation, tiny working set. SafeC's initial state, including the momentum offset, is a `consteval` table, so the program starts from constants; the simulation itself is the same code as the others'.

| Language | Time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | **0.098s (fastest)** | 2.0 MB | 0.044s |
| C | 0.099s | 2.0 MB | 0.043s |
| C++ | 0.099s | 3.8 MB | 0.097s |
| Rust | 0.107s | 2.0 MB | 0.072s |
| Zig | 0.110s | **0.5 MB (leanest)** | 8.25s |
| Go | 0.106s | 2.0 MB | **0.026s** |
| Python | 7.630s | 10.9 MB | N/A (interpreted) |

::: details Sources
[nbody.sc](/benchmarks/nbody/safec/nbody.sc) · [nbody.c](/benchmarks/nbody/c/nbody.c) · [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp) · [nbody.rs](/benchmarks/nbody/rust/nbody.rs) · [nbody.zig](/benchmarks/nbody/zig/nbody.zig) · [nbody.go](/benchmarks/nbody/go/nbody.go) · [nbody.py](/benchmarks/nbody/python/nbody.py)
:::

## binary-trees — allocation/deallocation stress {#binary-trees-allocationdeallocation-stress}

Builds and discards millions of small binary trees (max depth 18) — exercises memory management rather than arithmetic. The main SafeC entry uses `region`/`arena<R>` (bump-pointer allocation, `arena_reset<R>()` discards a whole region in O(1)) instead of the heap — this workload is all short-lived, same-scope allocations, exactly what regions are for. The **SafeC (heap)** row is the same program with `new` / `std::dealloc`, for a like-for-like comparison with the other languages' `malloc`/`Box`/GC.

| Language | Time | Peak memory | Compile time |
|---|---|---|---|
| SafeC (arena) | **0.132s (fastest)** | **25.9 MB (leanest)** | 0.044s |
| SafeC (heap) | 0.616s | 38.8 MB | 0.043s |
| C | 0.810s | 33.9 MB | 0.039s |
| C++ | 0.924s | 35.9 MB | 0.054s |
| Rust | 0.914s | 34.0 MB | 0.064s |
| Zig | 0.854s | 33.6 MB | 8.22s |
| Go | 0.718s | 36.8 MB | **0.035s** |
| Python | 18.049s | 82.6 MB | N/A (interpreted) |

The heap variant is 1.3× faster than C: `std::alloc` serves these 16-byte nodes from per-thread slabs, with a cache of freed blocks in front (see [Memory allocation](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree)). Before the slabs it took 0.713 s and 50.9 MB. It still uses a little more memory than C (38.8 vs 33.9 MB), because each block carries a 16-byte safety header and the cache and spare slabs are kept for reuse.

::: details Sources
[binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc) · [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c) · [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp) · [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs) · [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig) · [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go) · [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)
:::

## Multithreaded — binary-trees, 8 threads {#multithreaded-binary-trees-8-threads}

Same binary-trees workload, parallelized across 8 worker threads — each thread builds/checksums an independent slice of the tree count at a given depth, joined before the next depth. Release only. SafeC (arena) uses one `region`/`arena<R>` per thread (arena state isn't shared or locked); SafeC (heap) uses `new` / `std::dealloc`.

| Language | 8-thread time | Peak memory | Speedup vs. 1 thread |
|---|---|---|---|
| SafeC (arena) | **0.053s (fastest)** | 98.4 MB | 2.50× |
| SafeC (heap) | 0.229s | 200.4 MB | 2.69× |
| C | 0.226s | 161.8 MB | 3.58× |
| C++ | 0.227s | 163.5 MB | 4.07× |
| Rust | 0.232s | 161.9 MB | 3.93× |
| Zig | 0.226s | 163.8 MB | 3.78× |
| Go | 0.248s | **96.4 MB (leanest)** | 2.89× |
| Python | 22.958s | 311.1 MB | 0.79× (slower than 1 thread) |

The heap variant now matches C's time (0.229 vs 0.226 s; 0.263 s before the slabs). Its memory is higher because each thread keeps its own cache of freed blocks and its own spare slabs.

::: details Sources
[binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc) · [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c) · [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp) · [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs) · [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig) · [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go) · [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)
:::

## SIMD — sum of squares over 20,000,000 f64 values {#simd-sum-of-squares-over-20000000-f64-values}

Plain scalar loop vs. each language's explicit vector type at `-O2`/release — isolates what explicit SIMD buys *on top of* the backend's auto-vectorizer, not "vectorized vs. deliberately crippled." SafeC: native `vec<double,4>` (lowers to LLVM's target-generic `FixedVectorType`, no per-architecture source needed). C/C++: GCC/Clang vector extensions. Zig: `@Vector`. Rust: stable-channel intrinsics (`std::simd` needs nightly features) — SSE2 on x86-64 ([simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs)), NEON on AArch64 ([simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs)). Go has no portable SIMD type, scalar only.

| Language | Scalar | Explicit SIMD | Speedup |
|---|---|---|---|
| SafeC | 0.080s | 0.075s | 1.07× |
| C | 0.080s | 0.077s | 1.04× |
| C++ | 0.081s | 0.074s | 1.09× |
| Rust | 0.080s | 0.077s | 1.05× |
| Zig | **0.079s (fastest)** | **0.072s (fastest)** | 1.09× |
| Go | 0.085s | N/A | — |
| Python | 3.117s | 0.186s (NumPy) | 16.8× |

This loop is memory-bound on the 7800X3D (160 MB of doubles streams far past the 96 MB L3), so explicit SIMD buys little over the auto-vectorized scalar loop; the whole table is within ~10 ms, about the size of process-startup noise. SafeC's vector version builds its summation per x86-64 tier ([AVX-512 > AVX2 > AVX > SSE4.1 > SSE2](/stdlib/simd#cpu-levels)) and picks the best at run time, with four accumulators. Timed on its own, the summation takes about 3.7 ms at every tier (about 43 GB/s), so the rest of each row is the scalar setup loop and page faults on the array.

::: details Sources
[simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc) · [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c) · [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp) · [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs) · [simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs) · [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig) · [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go) · [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)
:::

## Collections — std::collections throughput (1,000,000 elements) {#collections-stdcollections-throughput-1000000-elements}

The generic containers against a plain C implementation of the same algorithms: growable array + `qsort`, a linear-probing hash map with the same hash, a `malloc`-per-node doubly linked list, a power-of-two ring queue, and an unbalanced BST. Both sides use identical shuffled keys (xorshift64), clang 21.1.8 `-O2` for both (`safeguard build --release`), best of 3.

| Operation | SafeC | C | SafeC / C |
|---|---|---|---|
| `Vec<int>` push | 2.31 ms | **1.51 ms** | 1.53× |
| `vec_sort` (introsort, inlined `Less<int>`) vs `qsort` | **43.5 ms** | 83.4 ms | 0.52× |
| `HashMap<int,int>` insert | 37.9 ms | **34.3 ms** | 1.10× |
| `HashMap<int,int>` get | 8.36 ms | **7.38 ms** | 1.13× |
| `List<int>` push_back + pop_front | **10.1 ms** | 22.2 ms | 0.45× |
| `Queue<int>` enqueue + dequeue | **3.02 ms** | 5.54 ms | 0.55× |
| `BST<int,int,Less<int>>` insert | **175.6 ms** | 179.9 ms | 0.98× |
| `BST<int,int,Less<int>>` get | 173.7 ms | **172.7 ms** | 1.01× |

::: details Why these results
- **Sorting** beats `qsort` because the ordering is a type parameter: every comparison inlines, where `qsort` makes an indirect call per comparison.
- **List** and **BST** nodes come from a per-container node pool (chunked bump allocation plus a free list), not one allocation per node; that avoids both `malloc`'s per-chunk overhead and `std::alloc`'s 16-byte safety header. BST lookups are dominated by cache misses on the random walk, the same for both.
- **HashMap** keys of 1/2/4/8 bytes are hashed as one word (murmur3 finalizer) and compared with one integer compare, chosen with `if const` at compile time. Lookup and insert are inlined, with the table's arrays read into locals, and the rehash is an out-of-line cold call (insert went from 41.6 to 37.9 ms). The rest of the gap is mostly the growth: each rehash starts its new arrays on fresh pages.
- **Vec push** keeps its growth in one out-of-line cold call, so the push itself is a compare, a store and an increment. It went from 2.50 to 2.31 ms; most of the improvement came from the allocator's quarantine no longer holding freed multi-megabyte buffers back from reuse (60,000 page faults vs C's 3,000 before). The rest of the gap is the bounds-checked `keys.at(i)` in the benchmark loop and first-touch growth.
:::

::: details Sources
[bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc) · [bench_collections.c](/benchmarks/collections/c/bench_collections.c) · [vec.h](/benchmarks/stdlib/vec.h) · [map.h](/benchmarks/stdlib/map.h) · [mem.sc](/benchmarks/stdlib/mem.sc)
:::

## Web service — JSON "hello world" endpoint {#web-service-json-hello-world-endpoint}

`GET /` returning `{"message":"Hello, World!"}` — the same shape as TechEmpower's "JSON serialization" test, via Apache Bench (`ab -n 20000 -c 50`, no keep-alive, after a 2,000-request warm-up) against each language's own HTTP story: SafeC's `std::http_serve_reactor` (epoll reactor on Linux); a minimal raw-socket accept loop for C/C++/Zig; Go's `net/http`; Python's FastAPI+uvicorn; Rust's **axum** (not Dioxus — Dioxus's fullstack server layer *is* axum underneath, so this measures what it actually runs through).

| Language | req/s | p50 / p99 latency | Peak memory |
|---|---|---|---|
| SafeC | **25,303 (fastest)** | 2 / 2 ms | 4.9 MB |
| C | 24,620 | 2 / 2 ms | **3.1 MB (leanest)** |
| C++ | 24,845 | 2 / 2 ms | 5.1 MB |
| Rust | 25,075 | 2 / 2 ms | 4.3 MB |
| Zig | 25,030 | 2 / 2 ms | 7.1 MB |
| Go | 23,199 | 2 / 3 ms | 16.1 MB |
| Python | 5,096 | 10 / 14 ms | 46.0 MB |

Every server completed every request with zero failures. The compiled servers all land within 8% of each other: without keep-alive, each request is a fresh TCP connection, and the kernel's connect/accept path and `ab` itself are the bottleneck, not the server.

::: details Sources
[server_reactor.sc](/benchmarks/webservice/safec/server_reactor.sc) · [server.sc](/benchmarks/webservice/safec/server.sc) · [server.c](/benchmarks/webservice/c/server.c) · [server.cpp](/benchmarks/webservice/cpp/server.cpp) · [main.rs](/benchmarks/webservice/rust/src/main.rs) · [Cargo.toml](/benchmarks/webservice/rust/Cargo.toml) · [server.zig](/benchmarks/webservice/zig/server.zig) · [server.go](/benchmarks/webservice/go/server.go) · [server.py](/benchmarks/webservice/python/server.py) · [http.sc](/benchmarks/stdlib/http.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h) · [task.h](/benchmarks/stdlib/task.h)
:::

## Machine learning — small MLP, training and inference {#machine-learning-small-mlp-training-and-inference}

2-layer MLP (`relu(X @ W1) @ W2`, 128→256→64, batch 64, MSE loss, hand-rolled SGD, no bias) — 100 training steps then 1000 inference passes, fixed seed. Every framework reads the loss back to the host every step and `Y[0][0]` every inference pass (`.item()` in PyTorch), so GPU rows pay a host round-trip per step, as a real training loop that logs its loss would. Each framework gets one untimed warm-up pass first: PyTorch's first `backward()` on this build takes ~10 s of one-time initialization even on CPU, and rocBLAS loads each GEMM shape's kernels on first use.

| Framework | Device | Train (100 steps) | Throughput | Inference (1000 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (built-in `sgemm`, 16 threads) | 16.8 ms | 380,800 samples/s | 36.0 ms | 100.333267 |
| SafeC | CPU, AOCL-BLAS | 14.9 ms | 430,900 samples/s | 26.3 ms | 100.333267 |
| SafeC | CPU, oneMKL | 18.1 ms | 353,800 samples/s | 31.4 ms | 100.333267 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 9.6 ms | 668,800 samples/s | 24.3 ms | 100.333267 |
| SafeC | ROCm (MI100), fp32 | 7.26 ms | 881,300 samples/s | 33.3 ms | 100.333282 |
| SafeC | ROCm, graph, fp32 | 7.01 ms | 912,900 samples/s | 36.4 ms | 100.333282 |
| SafeC | ROCm, graph, fp16 | **5.39 ms (fastest)** | **1,187,000 samples/s** | 33.2 ms | 100.336113 |
| SafeC | ROCm, graph, bf16 | 5.78 ms | 1,106,700 samples/s | 35.0 ms | 99.905960 |
| PyTorch | CPU | 14.0 ms | 455,900 samples/s | **21.9 ms (fastest)** | 100.018272 |
| PyTorch | ROCm (MI100), fp32 | 20.5 ms | 311,700 samples/s | 36.3 ms | 103.071129 |
| PyTorch | ROCm, fp16 | 19.0 ms | 336,600 samples/s | 33.5 ms | 103.062500 |
| PyTorch | ROCm, bf16 | 18.9 ms | 338,700 samples/s | 47.1 ms | 103.000000 |
| TensorFlow | CPU | 79.5 ms | 80,500 samples/s | 203.9 ms | 106.182999 |

At this size every GPU step is a handful of microsecond-scale kernels plus a blocking read of the loss, so launch and synchronization latency dominate. On the CPU, SafeC trains 1.5× faster than PyTorch with OpenBLAS (9.6 vs 14.0 ms); PyTorch is still the fastest at inference (21.9 vs SafeC's 24.3 ms).

SafeC's CPU rows run `tensor_matmul` on [`sgemm`](/stdlib/simd#gemm), the packed SIMD GEMM, on the [thread pool](/stdlib/sync#parallel). Its kernel is picked at run time for the CPU's [SIMD level](/stdlib/simd#cpu-levels) (AVX-512 here). Tensors come from a per-step [`TensorArena`](/stdlib/ml#tensor-arena), and the pool's workers spin briefly before sleeping. With [`[build] blas`](/advanced/safeguard#linking-external-libraries-and-library-output) set, the GEMM tiles go to AOCL-BLAS (AMD) or oneMKL (Intel), single-threaded per tile on SafeC's pool. The OpenBLAS row is the older `tensor_blas.h` path, one whole multithreaded OpenBLAS call per product. It now also runs each step in a `TensorArena` (it used to leave every step's tensors unfreed), which took it from 25.6 to 9.6 ms; at this size one multithreaded call per product beats tiling on SafeC's pool. The plain scalar loop all of this replaced took 107.4 ms to train and 285.8 ms for inference. Losses differ between frameworks because each draws its data and initial weights from its own RNG; the SafeC rows all share one seeded stream (`drand48`), and agree to 6 digits across CPU and GPU.

::: details Sources
[train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [gemm.h](/benchmarks/stdlib/gemm.h) · [gemm.sc](/benchmarks/stdlib/gemm.sc) · [parallel.h](/benchmarks/stdlib/parallel.h) · [parallel.sc](/benchmarks/stdlib/parallel.sc) · [tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py)
:::

## Machine learning, bigger model — 512→1024→256 {#machine-learning-bigger-model-512-1024-256}

Same model scaled ~50x (batch 128). 50 training steps, 200 inference passes; same warm-up and per-step readback as above.

| Framework | Device | Train (50 steps) | Throughput | Inference (200 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (built-in `sgemm`, 16 threads) | 48.8 ms | 131,200 samples/s | 67.0 ms | 95.426926 |
| SafeC | CPU, AOCL-BLAS | 36.5 ms | 175,300 samples/s | 43.6 ms | 95.427536 |
| SafeC | CPU, oneMKL | 45.4 ms | 141,000 samples/s | 52.6 ms | 95.428177 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 93.3 ms | 68,600 samples/s | 123.1 ms | 95.424927 |
| SafeC | ROCm (MI100), fp32 | 7.64 ms | 838,100 samples/s | 10.6 ms | 95.428314 |
| SafeC | ROCm, graph, fp32 | 7.37 ms | 868,500 samples/s | 11.5 ms | 95.428322 |
| SafeC | ROCm, graph, fp16 | **5.90 ms (fastest)** | **1,085,300 samples/s** | **9.26 ms (fastest)** | 95.090317 |
| SafeC | ROCm, graph, bf16 | 6.05 ms | 1,057,500 samples/s | 9.80 ms | 63.433674 |
| PyTorch | CPU | 38.4 ms | 166,900 samples/s | 50.8 ms | 106.601181 |
| PyTorch | ROCm (MI100), fp32 | 10.7 ms | 599,000 samples/s | 13.4 ms | 1868.155273 |
| PyTorch | ROCm, fp16 | 9.91 ms | 645,600 samples/s | 17.9 ms | 1942.000000 |
| PyTorch | ROCm, bf16 | 10.8 ms | 590,700 samples/s | 15.7 ms | 107.000000 |
| TensorFlow | CPU | 105.8 ms | 60,500 samples/s | 123.6 ms | 316.611603 |

On the GPU in fp32, SafeC trains 1.45× faster than PyTorch and runs inference 1.26× faster (both are a pair of rocBLAS GEMMs plus one elementwise kernel and one readback per pass). Part of that gain is new: the first call of each GEMM shape now times every solution rocBLAS offers and keeps the fastest (see [ROCm](/stdlib/ml#rocm-hip-amd)), which took the fp32 graph step from 8.29 ms to 7.37 ms. Graph replay records a whole training step into one `RocmGraph` and launches it with one call; with a blocking loss read every step it saves 3–4%. Without the per-step read, a C++ prototype of the same loop ran 3.8 ms vs 7.1 ms. PyTorch-ROCm's loss diverges at lr 0.001 from its GPU RNG's initial weights. That says nothing about speed, but it is why its sanity-check value looks wrong.

**fp16 and bf16.** The 16-bit rows run the graph step with data, weights, activations and gradients all in [`RocmHalfBuf`](/stdlib/ml#rocm-hip-amd) storage. GEMMs go to rocBLAS `gemm_ex` on the MI100's matrix cores with fp32 accumulation. The elementwise math runs in fp32 and is rounded once per stored value, and the loss stays fp32. The PyTorch rows cast the same model and data with `.to(dtype)` and train entirely in that dtype.

- 16-bit is SafeC's fastest mode here. fp16 trains 23% faster than the fp32 graph on the small model (5.39 vs 7.01 ms) and 20% faster on the big one (5.90 vs 7.37 ms), and big-model inference is 20% faster (9.26 vs 11.5 ms). bf16 is within 7% of fp16.
- Against PyTorch-ROCm in the same dtype, SafeC trains 3.5× (fp16) and 3.3× (bf16) faster on the small model, and 1.7× and 1.8× on the big one. Big-model inference is 1.9× and 1.6× faster. PyTorch gains at most 8% from 16-bit training, and its big-model inference is slower in 16-bit than in fp32.
- The per-shape solution choice matters most here. With rocBLAS's default solutions, fp16 was *slower* than fp32 on the big model (9.3 vs 8.4 ms): the default for the 128×256×1024 fp16 product took 0.047 ms, against 0.008 ms for the fastest one.
- This loop amplifies rounding. After 50 steps the big model's loss is 95.09 in fp16 and 63.43 in bf16, against 95.43 in fp32; the H200 shows the same spread (93.44 and 62.01).
- On the CPU, [`tensor_half.h`](/stdlib/ml#half-precision-tensors) stores fp16/bf16 values but computes in fp32, which saves memory but not time. That's why there are no 16-bit CPU rows.

On the CPU, SafeC with AOCL-BLAS is the fastest at this size: it trains 5% faster than PyTorch (36.5 vs 38.4 ms) and runs inference 14% faster (43.6 vs 50.8 ms). The built-in `sgemm` alone is within 1.3× of PyTorch, and 36× faster than the scalar loop it replaced (1761.2 ms to train). The OpenBLAS row (generic pthreads build, Ubuntu package) is the slowest CPU path here, even after its arena (93.3 ms, from 148.5). Each product is one multithreaded call there, and its threads spin against each other on these small GEMMs.

::: details Sources
[train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_cpu_blas.sc](/benchmarks/ml_big/safec/train_cpu_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc) · [PyTorch train.py](/benchmarks/ml_big/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml_big/tensorflow/train.py) · [MLX train.py](/benchmarks/ml_big/mlx/train.py)
:::

## Machine learning, real LLM inference — Gemma 4 E2B from scratch vs. PyTorch and MLX {#machine-learning-real-llm-inference-gemma-4-e2b-from-scratch}

A scale test for `std::ml`'s Tensor/attention/RoPE/RMSNorm primitives, built for small hand-rolled MLPs/transformers elsewhere on this page: do they hold up for a real, currently-shipping 35-layer LLM with genuinely unusual architecture — Per-Layer Embeddings, cross-layer KV sharing, dual RoPE configs (standard + proportional with zero-padded high frequencies), a non-standard RMSNorm variant, logit softcapping — loaded from the real 10.25 GB `google/gemma-4-E2B` checkpoint, with no external inference engine (no llama.cpp, no ONNX Runtime, no vLLM) anywhere in SafeC's own path. A standalone project, not part of `std::ml` itself: [safeguard](/advanced/safeguard)-structured at `benchmarks/gemma4/`, `Package.toml` + `src/` + `tests/`.

Every mechanism was verified in isolation against hand-computed reference values before being wired into the full model (see the project's own design notes for the derivation of each), then the whole pipeline was checked against ground truth: the actual `google/gemma-4-E2B` weights run through `transformers` in Python, same prompts, same tokenizer.

| Prompt | Reference top-5 token ids (`transformers`) | SafeC top-5 token ids | Match |
|---|---|---|---|
| "Hello, world!" | [236888, 108, 1, 107, 1717] | [236888, 108, 1, 107, 1717] | **exact, same order** |
| "The quick brown fox jumps over the lazy dog." | [107, 1, 31770, 108, 669] | [107, 1, 31770, 108, 669] | **exact, same order** |
| "SafeC" | [39316, 12977, 1869, 67, 55879] | [39316, 12977, 1869, 67, 55879] | **exact, same order** |

`main.sc`'s server runs a fully bf16 model — weights AND activations, no F32 model left in the codebase at all (see below): every op along the forward path, not just the matmuls, reads and writes bf16 bits directly (`bf16_to_f32`/`f32_to_bf16` at the point of each scalar use), with float accumulation only where numerically required (RMSNorm's sum-of-squares, softmax's max/exp/sum, RoPE's cos/sin table lookup) — never a whole intermediate F32 `Tensor`. This bf16-native rewrite's top-5 ids match the `transformers` reference **exactly** on all three prompts above — an improvement over an earlier bf16 revision (weights bf16, activations widened to F32 for most ops) which matched only 4/5 on the "Hello, world!" prompt's low-confidence tail token. Not chased further as proof of causation, but consistent with fewer intermediate F32 round-trips reducing cumulative rounding drift on borderline logit ties.

**Single-request latency** — the metric that actually matters for one chat session (one sequence, decoding one token at a time), as opposed to the batch-throughput numbers further down (those answer "how many *concurrent* requests can this serve," a different question). Same real checkpoint, same prompt (`"The capital of France is"`), same greedy decoding, same machine; every framework/path reproduces the identical, correct continuation (`" Paris.\n\nThe capital of France is Paris. ..."`, then predictably loops, an artifact of plain greedy decoding with no repetition penalty):

| Framework / path | Device | Load | ms/token |
|---|---|---|---|
| MLX | Apple Silicon GPU | 3.2s | 48ms |
| PyTorch | MPS | 7.5s | 85ms |
| PyTorch | CPU | 0.5s | 285ms |
| SafeC | MPS, BF16 | 6.5s | 127ms |
| SafeC | CPU, Accelerate BLAS, BF16 | 6.5s | 14.1s |

SafeC's 127ms is four independently-measured, independently-verified techniques stacked on the original 297ms baseline: a dedicated GEMV kernel for the `M==1` decode-step matmuls, a fully GPU-chained FFN block, whole-layer GPU chaining (RMSNorm/residual-add/GELU as GPU-resident kernels, collapsing ~150-180 GPU round trips/token toward roughly 70-84, confirmed via a controlled on/off A/B averaging ~1.32x), and speculative (prompt-lookup) decoding — see [DESIGN_NOTES.md](/benchmarks/gemma4/DESIGN_NOTES.md) for each technique's own before/after measurement. Neither MLX nor PyTorch's `mps` backend uses the Apple Neural Engine either (both confirmed GPU-only) — whole-layer chaining was pursued specifically after reading `mlx_lm`'s own decode loop, which fuses an entire decode step into one lazy graph materialized by a single `mx.eval()` call; a from-scratch CoreML/ANE proof of concept was also attempted separately and found to measure *slower* than this project's own hand-written GPU kernels even where the Neural Engine genuinely engaged (see DESIGN_NOTES.md's ANE section).

::: details Sources
[gemma4_bf16.h](/benchmarks/gemma4/src/gemma4_bf16.h) · [gemma4_bf16.sc](/benchmarks/gemma4/src/gemma4_bf16.sc) · [gemma4_rope.h](/benchmarks/gemma4/src/gemma4_rope.h) · [gemma4_rope.sc](/benchmarks/gemma4/src/gemma4_rope.sc) · [gemma4_attention.h](/benchmarks/gemma4/src/gemma4_attention.h) · [gemma4_attention.sc](/benchmarks/gemma4/src/gemma4_attention.sc) · [gemma4_ple.h](/benchmarks/gemma4/src/gemma4_ple.h) · [gemma4_ple.sc](/benchmarks/gemma4/src/gemma4_ple.sc) · [gemma4_ffn.h](/benchmarks/gemma4/src/gemma4_ffn.h) · [gemma4_ffn.sc](/benchmarks/gemma4/src/gemma4_ffn.sc) · [gemma4_model_bf16.h](/benchmarks/gemma4/src/gemma4_model_bf16.h) · [gemma4_model_bf16.sc](/benchmarks/gemma4/src/gemma4_model_bf16.sc) · [gemma4_generate.h](/benchmarks/gemma4/src/gemma4_generate.h) · [gemma4_generate.sc](/benchmarks/gemma4/src/gemma4_generate.sc) · [gemma4_tokenizer.h](/benchmarks/gemma4/src/gemma4_tokenizer.h) · [gemma4_tokenizer.sc](/benchmarks/gemma4/src/gemma4_tokenizer.sc) · [gemma4_http_server.h](/benchmarks/gemma4/src/gemma4_http_server.h) · [gemma4_http_server.sc](/benchmarks/gemma4/src/gemma4_http_server.sc) · [safetensors.h](/benchmarks/gemma4/src/safetensors.h) · [safetensors.sc](/benchmarks/gemma4/src/safetensors.sc) · [main.sc](/benchmarks/gemma4/src/main.sc) · [PyTorch bench_pytorch.py](/benchmarks/gemma4/tests/bench_pytorch.py) · [MLX bench_mlx.py](/benchmarks/gemma4/tests/bench_mlx.py) · [bench_safec.sc](/benchmarks/gemma4/tests/bench_safec.sc) · [test_gemma4_bf16_batch.sc](/benchmarks/gemma4/tests/test_gemma4_bf16_batch.sc) · [test_gemma4_bf16_batch_real.sc](/benchmarks/gemma4/tests/test_gemma4_bf16_batch_real.sc) · [test_gemma4_bf16_speculative.sc](/benchmarks/gemma4/tests/test_gemma4_bf16_speculative.sc) · [test_gemma4_bf16_speculative_real.sc](/benchmarks/gemma4/tests/test_gemma4_bf16_speculative_real.sc) · [test_mps_linear_gemv.sc](/benchmarks/gemma4/tests/test_mps_linear_gemv.sc) · [bench_mps_roundtrip.sc](/benchmarks/gemma4/tests/bench_mps_roundtrip.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal)
:::

## Memory allocation — is `std::alloc`/`dealloc` slower than raw `malloc`/`free`? {#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree}

`std::alloc` puts a 16-byte header on every block, which carries the double-free/use-after-free check, and keeps a size-class cache: the same idea as PyTorch's CPU/CUDA caching allocators and MLX's Metal buffer cache. A freed block goes into a thread-local free list bucketed by power-of-two size class, and the next same-class `alloc()` is served from there without calling `malloc()`/`free()`. Cached blocks keep their "freed" magic word until reused, so the safety checks still work. Measured against glibc's `malloc`/`free` (best of 3; every block's address goes into a printed checksum so the optimizer can't delete an alloc/free pair):

| Pattern | `std::alloc` / `dealloc` | `malloc` / `free` | Ratio |
|---|---|---|---|
| Interleaved: allocate one 48-byte block, free it, 20M times | **3.57 ns / pair** | 6.38 ns / pair | 1.8× faster |
| Batch: allocate 1M 24-byte blocks, then free them all, 10 rounds | **87 ms** | 96 ms | 1.1× faster |

The cache wins whenever freed blocks are reused soon. Short-lived objects and binary-trees-style churn work like this: the heap binary-trees above beats every other language's allocator. A million live blocks at once is more than the cache holds (125,587 slots across all classes). Those blocks used to go through `malloc` one by one with the header write on top (134 ms, 1.4× slower than `malloc`). Blocks up to 1 KB now come from per-thread 64 KB slabs instead, which makes the batch pattern 1.1× faster than `malloc` with the same double-free checks (see [`std::mem`](/stdlib/mem#allocation-performance)). For bulk short-lived allocation, `region`/`arena<R>` is still the faster tool: 4.7× faster than the heap version on binary-trees.

::: details Sources
[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc) · [alloc_bench.sc](/benchmarks/memalloc/safec/alloc_bench.sc)
:::

## Convolution and FFT on the MI100 {#mi100-conv-fft}

The same workloads as [on the H200](#h200-conv-fft), on this page's primary machine: SafeC's CPU code (`cnn.h`, `unet.h`, `fft.h`), the ROCm libraries [`gpu_miopen.h` and `fft_rocm.h`](/stdlib/ml#rocm-miopen-hipfft) (MIOpen and hipFFT from ROCm 7.1), and PyTorch 2.9.1 on ROCm 6.4 (which also uses MIOpen and rocFFT). Tensors stay on the GPU between calls. The GPU rows are the mean of 300–2000 queued calls between two synchronizations, best of 3 runs, all in fp32 (the MI100 has no TF32). SafeC picks each convolution's algorithm with MIOpen's Find on the first call, and PyTorch does the same (`cudnn.benchmark = True`).

Convolution, ms per call (lower is better). `conv64` is one Conv2D layer, 64→64 channels, 3×3, stride 1, padding 1, on 56×56 maps, plus bias. `unet128` is `unet_new(3, 32, 64, 3)` forward at 128×128.

| Framework | Device | conv64, 1 image | conv64, 32 images | unet128, 1 image | unet128, 16 images |
|---|---|---|---|---|---|
| SafeC | CPU (Winograd / im2col + built-in `sgemm`, 16 threads) | 0.412 | — | 1.68 | — |
| SafeC | CPU, AOCL-BLAS | 0.359 | — | 1.35 | — |
| SafeC | CPU, oneMKL | 0.399 | — | 1.69 | — |
| PyTorch | CPU (oneDNN, 8 threads) | 0.327 | — | 2.68 | — |
| SafeC | ROCm | **0.0270** | **0.342** | 0.222 | 1.289 |
| SafeC | ROCm, U-Net as one graph | | | **0.216** | |
| PyTorch | ROCm | 0.0628 | 0.346 | 0.314 | **1.259** |

FFT on the GPU, ms per call. `fft1d` and `fft2d` are complex `double` arrays that start and end in host memory, so each GPU call includes the upload and the read back. `batch` and `rfft` run on data already on the GPU (complex64 and float32).

| Framework | Device | fft1d, 2²⁰ points | fft2d, 1024×1024 | batch, 1024 × 4096 | rfft, 1024 × 4096 |
|---|---|---|---|---|---|
| SafeC | ROCm | **1.46** | **1.43** | **0.1002** | **0.0949** |
| PyTorch | ROCm | 1.56 | 1.53 | 0.171 | 0.126 |

FFT on the CPU, ms per call, against FFTW 3.3.10 (built with AVX/AVX2/AVX-512 and FMA codelets, planned with `FFTW_MEASURE`), KFR 7.2.0 and RustFFT 6.4.1. All data starts in memory. `fft1d` is one 2²⁰-point transform, `fft2d` one 1024×1024 transform, `batch` 1024 transforms of 4096 points, `rfft` 1024 real transforms of 4096 points. For `rfft` every library returns the n/2 + 1 non-redundant bins (SafeC through `fft_real_half_batch`). RustFFT has no 2D or real transform. With oneMKL linked, SafeC hands transforms below 65,536 points to its DFTI. AOCL has no effect here (its FFT is FFTW-based, GPL, and not used).

Double precision (complex128):

| Library | Threads | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft.h` | 16 | **0.888** | 0.691 | 1.25 | 0.861 |
| SafeC, oneMKL DFTI | 16 | 0.891 | 1.00 | 1.89 | 1.05 |
| FFTW | 16 | 1.63 | **0.618** | **1.05** | **0.553** |
| PyTorch | 8 | 8.31 | 0.710 | — | — |
| SafeC `fft.h` | 1 | 5.47 | 4.43 | 11.9 | 6.17 |
| FFTW | 1 | 6.50 | 3.49 | 7.84 | 4.28 |
| KFR | 1 | 3.36 | 4.22 | 11.8 | 4.34 |
| RustFFT | 1 | 4.75 | — | 8.25 | — |

Single precision (complex64 / float32):

| Library | Threads | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft_f32` | 16 | **0.688** | 0.560 | 0.775 | 0.558 |
| SafeC, oneMKL DFTI | 16 | 0.693 | 0.543 | 0.964 | 0.457 |
| FFTW (`fftwf`) | 16 | 1.67 | **0.446** | **0.504** | **0.409** |
| PyTorch | 8 | — | — | 4.41 | 0.443 |
| SafeC `fft_f32` | 1 | 4.39 | 3.25 | 5.76 | 4.28 |
| FFTW (`fftwf`) | 1 | 5.53 | 2.54 | 3.31 | 2.98 |
| KFR | 1 | 1.77 | 2.63 | 4.23 | 2.13 |
| RustFFT | 1 | 2.10 | — | 4.11 | — |

The single-thread SafeC rows were run separately (`SAFEC_NUM_THREADS=1`), after the table run, with the same build.

- **Convolutions**: on the GPU, SafeC is 2.3× faster than PyTorch on one image and 1.45× faster on the batch-1 U-Net as a graph. At 32 images the two tie, and PyTorch is 2% faster on the 16-image U-Net. Each SafeC convolution is one MIOpen call plus one HIP kernel for the bias (and ReLU), and each U-Net decoder step is one kernel that upsamples straight into the concat buffer.
- **CPU convolution**: 3×3 stride-1 layers use [Winograd F(2×2, 3×3)](/stdlib/ml#cnn) (Lavin & Gray), with 16 multiplies per 2×2 outputs and channel pair instead of 36. Its input transform, 16 channel GEMMs and output transform each run on the pool. Other layers use im2col + `sgemm`. Each image's maps, the U-Net's intermediates included, now come from a [`TensorArena`](/stdlib/ml#tensor-arena) reset after the image, so no layer pays for a fresh multi-megabyte allocation (that alone took `conv64` from 0.535 to 0.415 ms). On the U-Net SafeC is 2.0× faster than PyTorch's CPU path with AOCL-BLAS (1.35 vs 2.68 ms) and 1.6× faster with the built-in GEMM; before Winograd and the arena it took 3.13 ms. On the single `conv64` layer PyTorch (oneDNN) is still 1.1–1.3× faster.
- **FFT on the GPU**: both sides call rocFFT. For host arrays PCIe dominates and the times match. On device data SafeC is 1.3–1.7× faster; at 0.1 ms per call, PyTorch's per-call work around the same transform (allocating the output tensor, dispatch) is a large share, though that hasn't been measured on its own.
- **CPU FFT**: SafeC is the fastest at the 2²⁰-point transform in both precisions, 1.8× (double) and 2.4× (float) ahead of 16-thread FFTW. FFTW leads on the 2D, batched and real transforms, by 1.1× (double `fft2d`) to 1.6× (double `rfft`). Single-threaded, SafeC is ahead of FFTW at 2²⁰ points and behind it on the 2D and 4096-point rows (1.5× on the double batch), where FFTW's larger codelets and fewer passes over each row count. Since this page's previous run, fused radix-8 passes, four complex values per vector (AVX-512, and float on AVX2) and compile-time bit-reversal tables took the 16-thread double batch from 4.35 to 1.25 ms and the 2D transform from 2.06 to 0.691 ms. The real transform's split is now vectorized two bins at a time and the rfft rows return the half spectrum, as the other libraries do: the double real batch went from 1.75 ms (all n bins, scalar split) to 0.861 ms.
- **Single precision**: float runs the same algorithm (`fft_core.h` built twice) with twice the values per register. The batch takes 0.62× the double time, and the real batch 0.65×.
- **oneMKL**: its DFTI is faster than SafeC's own code on the float `rfft` (0.457 vs 0.558) and float `fft2d` (0.543 vs 0.560), but slower in double (batch 1.89 vs 1.25).
- **Warm-up**: every CPU row gets one untimed call first, which starts the thread pool and builds the twiddle tables. FFTW's plans are made outside the timing. PyTorch's script runs five warm-up calls.

::: details Sources
[conv_rocm.sc](/benchmarks/conv/safec/conv_rocm.sc) · [conv_cpu.sc](/benchmarks/conv/safec/conv_cpu.sc) · [conv.py](/benchmarks/conv/pytorch/conv.py) · [fft_rocm.sc](/benchmarks/fft/safec/fft_rocm.sc) · [fft_cpu.sc](/benchmarks/fft/safec/fft_cpu.sc) · [fft.py](/benchmarks/fft/pytorch/fft.py) · [fftw_bench.c](/benchmarks/fft/c/fftw_bench.c) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [RustFFT main.rs](/benchmarks/dsp/rust/src/main.rs) · [cnn.sc](/benchmarks/stdlib/cnn.sc) · [unet.sc](/benchmarks/stdlib/unet.sc) · [gemm.sc](/benchmarks/stdlib/gemm.sc) · [fft.h](/benchmarks/stdlib/fft.h) · [fft.sc](/benchmarks/stdlib/fft.sc) · [fft_f32.sc](/benchmarks/stdlib/fft_f32.sc) · [fft_core.h](/benchmarks/stdlib/fft_core.h) · [parallel.sc](/benchmarks/stdlib/parallel.sc) · [gpu_miopen.h](/benchmarks/stdlib/gpu_miopen.h) · [gpu_miopen.sc](/benchmarks/stdlib/gpu_miopen.sc) · [fft_rocm.h](/benchmarks/stdlib/fft_rocm.h) · [fft_rocm.sc](/benchmarks/stdlib/fft_rocm.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc)
:::

## Attention on the MI100 {#mi100-attention}

Flash and linear attention from [`gpu_rocm.h`](/stdlib/ml#rocm-attention) against PyTorch 2.9.1 on ROCm 6.4, with the fp32 shapes of the [H200 run](#h200-attention): batch 4 × 16 heads (passed to SafeC as 64 heads, the same memory layout), data on the GPU. Each number is the mean of 5–40 queued calls between two synchronizations, best of 3 runs. TFLOPS count 4·B·H·S²·D (halved when causal). On gfx908, PyTorch's `scaled_dot_product_attention` has no fused kernel: it forms the whole score matrix with batched matrix products and runs the softmax over it. Its linear-attention rows are the same vectorized PyTorch code as on the H200, in IEEE fp32 (the MI100 has no TF32).

Flash attention, ms per call (TFLOPS):

| Head dim | Tokens | Causal | SafeC ms (TFLOPS) | PyTorch ms (TFLOPS) | PyTorch / SafeC |
|---|---|---|---|---|---|
| 64 | 1024 | no | **0.858 (20.0)** | 2.52 (6.8) | 2.93 |
| 64 | 1024 | yes | **0.457 (18.7)** | 3.28 (2.6) | 7.17 |
| 64 | 4096 | no | **10.7 (25.7)** | 42.8 (6.4) | 4.00 |
| 64 | 4096 | yes | **5.48 (25.0)** | 56.2 (2.5) | 10.25 |
| 128 | 1024 | no | **1.81 (18.9)** | 3.32 (10.3) | 1.83 |
| 128 | 1024 | yes | **0.950 (18.0)** | 4.05 (4.2) | 4.26 |
| 128 | 4096 | no | **24.8 (22.2)** | 53.4 (10.3) | 2.16 |
| 128 | 4096 | yes | **12.6 (21.9)** | 66.4 (4.1) | 5.29 |

Linear attention (φ(x) = elu(x) + 1), ms per call:

| Head dim | Tokens | Causal | SafeC | PyTorch | PyTorch / SafeC |
|---|---|---|---|---|---|
| 64 | 4096 | no | **0.464** | 1.40 | 3.02 |
| 64 | 4096 | yes | **0.673** | 3.64 | 5.41 |
| 64 | 16384 | no | **1.62** | 5.63 | 3.49 |
| 64 | 16384 | yes | **2.76** | 14.6 | 5.28 |
| 128 | 4096 | no | **1.60** | 3.25 | 2.03 |
| 128 | 4096 | yes | **2.84** | 8.73 | 3.07 |

- **Flash attention** runs on the MI100's matrix cores in fp32: `v_mfma_f32_32x32x2f32` up to head dim 64, and `16x16x4` at 128, which needs half the registers per wave and so keeps two waves per SIMD. The scores are computed transposed (Sᵀ = K Qᵀ), so each lane owns one query row. Its q stays in registers, the row max and sum need one cross-half shuffle, and P feeds the P·V product straight from registers. Causal blocks start longest first, and the output is rescaled only when a row's max grows by more than 2⁸, as in FlashAttention-4. That gives 18–26 TFLOPS of the MI100's 46 TFLOPS fp32 matrix peak, 4–7× faster than the CUDA-core kernel it replaces (45.5 ms → 10.7 ms at head dim 64 and 4096 tokens).
- **Against PyTorch**, SafeC is 1.8–4× faster without a mask and 4–10× faster with a causal one. PyTorch's math path computes the whole score matrix whether or not it is masked, so masking makes it slower; SafeC skips fully masked tiles.
- **Linear attention** works on 128-row chunks. Non-causal, and causal at head dim 128, it has three passes. Per-chunk states φ(K)ᵀV come from matrix cores. A scan runs over the chunks. Then an output pass holds φ(Q) rows in registers as the MFMA operand and adds the masked term inside each chunk. Causal attention up to head dim 64 runs as one pass. Each chunk's block loads its K/V tiles once and uses them twice: for the masked term and for its own state. It gets the prefix of earlier chunks with a decoupled look-back (Merrill & Garland's single-pass scan): blocks take chunks in order from an atomic ticket, publish their state at once, and add up predecessors' states back to the first published prefix. That removes the separate state and scan passes and a second read of K and V. Causal is 13% faster at 4096 tokens than with three passes (0.775 → 0.673 ms) and 10% faster at 16k. Overall that is 7–11× faster than the previous kernels and 2–5.4× faster than PyTorch.
- GPUs without matrix cores (RDNA) use the CUDA-core kernels. `rocm_attention_use_mfma(0)` selects them on an MI100 too. `tests/stdlib/rocm/rocm_check.sc` checks both paths against a double-precision reference and attention.h's CPU functions: 251 checks.

::: details Sources
[attn_rocm.sc](/benchmarks/attention/safec/attn_rocm.sc) · [attn.py](/benchmarks/attention/pytorch/attn.py) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc)
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
| fir stream | 5.87 | 5.97 / 5.86 | 17.4 | 32.1 | |
| fir block | 0.841 | 0.824 / 0.796 | 15.4 | — | |
| fftconv | 2.98 | 3.02 / 3.19 | 32.3 | 4.89 | |
| biquad | 0.484 | 0.484 / 0.493 | 4.71 | 2.33 | |
| stft | 1.82 | 1.85 / 1.81 | 53.9 | — | |
| resample | 3.71 | 3.84 / 3.88 | 9.07 | 61.7 (draft 26.3) | dasp 519 |
| conv2d | 0.366 | 0.369 / 0.390 | 35.3 | — | |

Single precision:

| Workload | SafeC | SafeC, AOCL / oneMKL | SciPy | KFR | Others |
|---|---|---|---|---|---|
| fir stream | 3.04 | 3.03 / 3.07 | 19.5 | 27.8 | |
| fir block | 0.475 | 0.450 / 0.471 | 18.9 | — | |
| fftconv | 1.29 | 1.31 / 1.16 | 14.8 | 2.76 | |
| biquad | 0.482 | 0.483 / 0.525 | 4.59 | 2.15 | Q 1.69, madronalib 2.87 |
| stft | 0.986 | 0.968 / 0.983 | 55.8 | — | |
| resample | 3.96 | 3.82 / 3.98 | 8.68 | 34.1 (draft 27.3) | |

- SafeC is the fastest on every row, in both precisions. The vendor libraries only matter where an FFT is involved (`fftconv`, `stft`), and there they are within noise of the built-in code.
- **FIR**: each block runs `dsp_sliding_dot`, which computes 32 outputs per pass (64 in float) with each tap broadcast into four accumulator vectors, so there are no horizontal sums. The streaming filter went from 14.2 ms to 5.87 (double) and from 9.45 to 3.04 (float); KFR and SciPy run one dot product per output. `fir block` is threaded; `fir stream` isn't.
- **Biquad**: `biquad_process_block` uses scattered look-ahead (Parhi & Messerschmitt). The recursion is rewritten in y[i-2] and y[i-4], so the even and odd outputs are two independent chains, computed together in one 2-lane vector. That makes it 3.5× faster than Q and 4.5× faster than KFR in float, and 4.4× faster than before (2.14 ms). The added poles mirror the filter's own, so it stays stable, and its output matches `process()` to rounding.
- **Resample**: each tap's weight comes from angle-difference formulas over tables built once per call, so the taps vectorize. The per-output angles are carried by rotation, so each output needs no trig call. It is 2.4× faster than SciPy's polyphase filter (12.1 ms before). SafeC's resampler is threaded; SciPy's isn't.
- **STFT** frames go through an in-place real FFT batch, half the work of the complex transform it used before (7.08 ms).
- **Float vs double**: float helps where memory traffic or vector width limits the code: the FIR (2×), `fftconv` and `stft`. The biquad is latency-bound and the resampler computes its kernel in double, so those two don't change.

::: details Sources
[dsp_cpu.sc](/benchmarks/dsp/safec/dsp_cpu.sc) · [dsp_scipy.py](/benchmarks/dsp/python/dsp_scipy.py) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [audio_libs_bench.cpp](/benchmarks/dsp/cpp/audio_libs_bench.cpp) · [dasp main.rs](/benchmarks/dsp/rust/src/main.rs) · [Cargo.toml](/benchmarks/dsp/rust/Cargo.toml) · [dsp.sc](/benchmarks/stdlib/dsp.sc) · [filter.sc](/benchmarks/stdlib/filter.sc) · [biquad.sc](/benchmarks/stdlib/biquad.sc) · [convolution.sc](/benchmarks/stdlib/convolution.sc) · [conv_core.h](/benchmarks/stdlib/conv_core.h) · [stft.sc](/benchmarks/stdlib/stft.sc) · [resample_core.h](/benchmarks/stdlib/resample_core.h) · [imaging_core.h](/benchmarks/stdlib/imaging_core.h) · [fft_core.h](/benchmarks/stdlib/fft_core.h)
:::

## Xeon Platinum 8570 + NVIDIA H200 {#xeon-8570-h200}

The same suite on a GPU server, the machine SafeC's CUDA backend was verified on. Compare these numbers with each other, not with the 7800X3D tables above: the CPU, the core count and the toolchain versions all differ.

- **Machine**: Intel Xeon Platinum 8570 in a Backend.AI container limited to 12 of the host's 112 cores, NVIDIA H200 (sm_90, 141 GB HBM3e, driver 580.126.20, CUDA 13.0, cuDNN 9.15), Ubuntu 24.04.3 — `safec` 1.0.0 built from `main` (3848bf1) · clang 21.1.8 · Rust 1.99.1 · Go 1.27.3 · Zig 0.17.0 · Python 3.12.3 · PyTorch 2.10.0a0 (NGC 25.11) · TensorFlow 2.21.0 (CPU) · OpenBLAS (pthreads, Ubuntu package) · oneMKL 2026.2. For the FFT/DSP comparison: FFTW 3.3.10 (Ubuntu package), KFR 7.2.0, SciPy 1.16.3, RustFFT 6.4.1, dasp 0.11.0, Q and madronalib from their current sources. Each third-party library is linked only into its own benchmark program.
- **Method**: same as [above](#methodology), best of 3. The H200 is shared with a training job, which was paused (`SIGSTOP`) for the whole run, so the GPU and the 12 cores were idle. While that job ran, the same GPU benchmarks varied 5× from run to run, for SafeC and PyTorch alike. SafeC's CPU kernels are picked at run time for the CPU's [SIMD level](/stdlib/simd#cpu-levels), AVX-512 on this Xeon.
- **Numbers**: [`results/linux-xeon8570-h200.json`](/benchmarks/results/linux-xeon8570-h200.json). Every SafeC GPU row reproduces the previous run (SafeC `cuda-perf-gaps`) within 2.4%; PyTorch's GPU rows, which are bound by host overhead, moved by up to 11%.

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
| SafeC | CPU (built-in `sgemm`, 12 threads) | 24.3 ms | 262,985 samples/s | 54.0 ms | 100.333267 |
| SafeC | CPU, oneMKL | 25.9 ms | 247,467 samples/s | 39.3 ms | 100.333267 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 21.6 ms | 296,777 samples/s | 55.3 ms | 100.333267 |
| SafeC | CUDA (H200), fp32 | 6.77 ms | 945,347 samples/s | 38.9 ms | 100.333282 |
| SafeC | CUDA, fused, fp32 | 6.15 ms | 1,039,974 samples/s | 34.9 ms | 100.333282 |
| SafeC | CUDA, fused + graph, fp32 | 3.81 ms | 1,678,909 samples/s | 19.9 ms | 100.333282 |
| SafeC | CUDA, graph, TF32 | 3.80 ms | 1,684,654 samples/s | **19.6 ms (fastest)** | 100.334084 |
| SafeC | CUDA, graph, fp16 | **2.98 ms (fastest)** | **2,145,491 samples/s** | 19.7 ms | 100.335869 |
| SafeC | CUDA, graph, bf16 | 2.98 ms | 2,144,054 samples/s | 19.7 ms | 99.951080 |
| PyTorch | CPU | 23.2 ms | 275,672 samples/s | 43.0 ms | 100.018272 |
| PyTorch | CUDA, TF32 (NGC default) | 47.6 ms | 134,533 samples/s | 55.7 ms | 103.076630 |
| PyTorch | CUDA, fp32 | 51.3 ms | 124,839 samples/s | 56.6 ms | 103.071129 |
| PyTorch | CUDA, fp16 | 49.1 ms | 130,346 samples/s | 52.5 ms | 103.062500 |
| PyTorch | CUDA, bf16 | 48.1 ms | 132,937 samples/s | 52.4 ms | 103.000000 |
| TensorFlow | CPU | 122.9 ms | 52,064 samples/s | 264.9 ms | 106.182999 |

Bigger model (512→1024→256, batch 128, 50 training steps, 200 inference passes):

| Framework | Device | Train (50 steps) | Throughput | Inference (200 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC | CPU (built-in `sgemm`, 12 threads) | 115.6 ms | 55,359 samples/s | 117.3 ms | 95.426926 |
| SafeC | CPU, oneMKL | 101.3 ms | 63,164 samples/s | 61.3 ms | 95.427391 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 195.3 ms | 32,769 samples/s | 174.6 ms | 95.424927 |
| SafeC | CUDA (H200), fp32 | 4.10 ms | 1,561,737 samples/s | 8.23 ms | 95.427292 |
| SafeC | CUDA, fused, fp32 | 3.77 ms | 1,698,514 samples/s | 7.57 ms | 95.427292 |
| SafeC | CUDA, fused + graph, fp32 | 3.13 ms | 2,045,382 samples/s | 5.60 ms | 95.427277 |
| SafeC | CUDA, graph, TF32 | 2.31 ms | 2,770,563 samples/s | 5.58 ms | 90.685493 |
| SafeC | CUDA, graph, fp16 | **1.90 ms (fastest)** | **3,377,309 samples/s** | 4.00 ms | 93.444328 |
| SafeC | CUDA, graph, bf16 | 1.90 ms | 3,370,195 samples/s | **3.99 ms (fastest)** | 62.006180 |
| PyTorch | CPU | 63.1 ms | 101,349 samples/s | 53.6 ms | 106.544487 |
| PyTorch | CUDA, TF32 (NGC default) | 18.4 ms | 346,883 samples/s | 11.8 ms | 871.826538 |
| PyTorch | CUDA, fp32 | 15.9 ms | 402,516 samples/s | 11.8 ms | 1754.826172 |
| PyTorch | CUDA, fp16 | 17.3 ms | 370,821 samples/s | 10.5 ms | 1281.000000 |
| PyTorch | CUDA, bf16 | 16.7 ms | 382,272 samples/s | 10.5 ms | 91.500000 |
| TensorFlow | CPU | 228.5 ms | 28,014 samples/s | 236.0 ms | 316.611603 |

- **SafeC versus PyTorch on the GPU, fp32 against fp32**: training is 13× faster on the small model (3.81 ms vs 51.3 ms) and 5.1× on the big one (3.13 ms vs 15.9 ms); inference is 2.8× and 2.1× faster. At these sizes a step is a few microsecond-scale kernels plus a blocking loss read, so the cost per launch decides the result. One SafeC step is about 38 µs (one graph launch plus the readback). One PyTorch step is about 510 µs of Python, autograd and dispatcher work around the same handful of cuBLAS calls.
- **fp16 and bf16**: 16-bit storage is SafeC's fastest mode. It trains 21% faster than the TF32 graph on the small model and 18% faster on the big one, where inference is also 28% faster (3.99 ms vs 5.58 ms); small-model inference is launch-bound and doesn't change. Against PyTorch in the same dtype, SafeC trains 16× (small) and 8.8–9.1× (big) faster, and runs inference 2.6–2.7× faster. fp16 and bf16 take the same time. PyTorch's training time barely moves with 16-bit storage here (−6% to +9% against fp32), since its steps are bound by host overhead; its inference gets 7–11% faster. As with TF32, this loop amplifies rounding: the big model's loss after 50 steps is 93.44 in fp16 and 62.01 in bf16, against 95.43 in fp32. PyTorch shows the same spread (1754.8 fp32, 1281.0 fp16, 91.5 bf16; it uses its own random data).
- **Where SafeC's time goes**: fusing removes 3 of the 10 launches and saves 8–9%. The graph saves another 38% on the small model, where launch cost dominates, and 17% on the big one. TF32 changes nothing on the small model, which isn't compute-bound, and takes 26% off training on the big one. With TF32, the big model's loss after 50 steps is 90.69 instead of 95.43: this loop amplifies rounding differences, and the CPU, fp32 GPU and ROCm runs all agree to 4 digits.
- **Before this backend**: in v1.0.0-beta-5, `tensor_matmul_cuda_blas` created and destroyed a CUDA context and a cuBLAS handle on every call. On this GPU the small benchmark took 83 s to train and 816 s for inference through that path, more than 20,000× slower than the fused + graph row.
- **CPU**: SafeC's built-in [`sgemm`](/stdlib/simd#gemm) now runs its AVX-512 kernel here, on the [thread pool](/stdlib/sync#parallel). The small model trains in 24.3 ms, 2.4× faster than the previous run's SSE2 build (58.7 ms) and 5.9× faster than beta-5's scalar loop (144.0 ms); the big one in 115.6 ms (2.9× and 20×). That is within 5% of PyTorch's CPU kernels on the small model (23.2 ms) and beats OpenBLAS on the big one by 1.7× (195.3 ms). With oneMKL linked, the big model trains in 101.3 ms and runs inference in 61.3 ms, the fastest SafeC CPU rows; PyTorch is still 1.6× faster at big-model training (63.1 ms).

### Convolution and FFT on the H200 {#h200-conv-fft}

SafeC's CPU code (`cnn.h`, `unet.h`, `fft.h`), the GPU libraries [`gpu_cudnn.h` and `fft_cuda.h`](/stdlib/ml#cuda-cudnn-cufft) (cuDNN 9.15, cuFFT from CUDA 13.0), and PyTorch on both devices. All
tensors stay on the GPU between calls. The SafeC CUDA and PyTorch-CUDA rows
are the mean of 300–2000 queued calls between two synchronizations. The CPU
rows are the mean of a few calls after one untimed warm-up. Each number is the best of 3 runs, with the
training job paused.

SafeC builds each convolution shape's plan on the first call. It times the
engine configurations cuDNN's graph API suggests for the fused conv + bias
(+ ReLU) graph, and the legacy API's best algorithm, and keeps the fastest.
The PyTorch rows also time their candidates (`cudnn.benchmark = True`); with
PyTorch's default heuristic the times were within 1%. "fp32" sets
`cudnn.allow_tf32 = False` in PyTorch, and SafeC leaves `cuda_set_tf32` off.
"TF32" turns both on. PyTorch's default is TF32 for convolutions.

Convolution, ms per call (lower is better; fastest GPU row in bold within fp32 and within TF32). `conv64` is one Conv2D layer, 64→64
channels, 3×3, stride 1, padding 1, on 56×56 maps, plus bias. `unet128` is
`unet_new(3, 32, 64, 3)` forward at 128×128: five convolutions, two max pools,
two upsample + concat steps.

| Framework | Device | conv64, 1 image | conv64, 32 images | unet128, 1 image | unet128, 16 images |
|---|---|---|---|---|---|
| SafeC | CPU (Winograd / im2col + built-in `sgemm`, 12 threads) | 0.854 | — | 3.31 | — |
| SafeC | CPU, oneMKL | 0.710 | — | 2.65 | — |
| PyTorch | CPU (oneDNN, 12 threads) | 0.424 | — | 3.29 | — |
| SafeC | CUDA, fp32 | **0.0181** | **0.164** | 0.156 | **0.534** |
| SafeC | CUDA, fp32, U-Net as one graph |  |  | **0.144** |  |
| PyTorch | CUDA, fp32 | 0.0225 | 0.257 | 0.208 | 0.877 |
| SafeC | CUDA, TF32 | **0.0181** | **0.0838** | 0.104 | **0.454** |
| SafeC | CUDA, TF32, U-Net as one graph |  |  | **0.0874** |  |
| PyTorch | CUDA, TF32 (default) | 0.0380 | 0.0976 | 0.269 | 0.557 |

FFT on the GPU, ms per call. `fft1d` and `fft2d` are complex `double` arrays that start
and end in host memory, so each GPU call includes the upload and the read back
(`cuda_fft`/`cuda_fft2d`; `.to("cuda")`, `torch.fft`, `.cpu()`). `batch` and
`rfft` run on data already on the GPU (`cuda_fft_c2c`/`cuda_fft_r2c`; complex64
and float32).

| Framework | Device | fft1d, 2²⁰ points | fft2d, 1024×1024 | batch, 1024 × 4096 | rfft, 1024 × 4096 |
|---|---|---|---|---|---|
| SafeC | CUDA | **1.25** | **1.26** | 0.0244 | **0.0109** |
| PyTorch | CUDA | 2.05 | 2.04 | **0.0238** | 0.0120 |

FFT on the CPU, ms per call, against FFTW 3.3.10 (Ubuntu's package, planned with `FFTW_MEASURE`), KFR 7.2.0 and RustFFT 6.4.1; the transforms are the same as [on the 7800X3D](#mi100-conv-fft). For `rfft` every library returns the n/2 + 1 non-redundant bins (SafeC through `fft_real_half_batch`). The "12" rows use every core of the container (FFTW's `16t` key in the JSON ran with 12 threads). Unlike the 7800X3D page, there are no single-thread SafeC rows here.

Double precision (complex128):

| Library | Threads | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft.h` | 12 | 1.21 | 1.08 | 2.96 | 2.31 |
| SafeC, oneMKL DFTI | 12 | **1.18** | 0.837 | **1.65** | **1.36** |
| FFTW | 12 | 2.35 | 2.59 | 2.95 | 2.02 |
| PyTorch | 12 | 1.18 | **0.654** | — | — |
| FFTW | 1 | 11.4 | 7.27 | 10.7 | 7.35 |
| KFR | 1 | 6.35 | 5.15 | 12.3 | 7.32 |
| RustFFT | 1 | 8.78 | — | 14.8 | — |

Single precision (complex64 / float32):

| Library | Threads | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft_f32` | 12 | 3.06 | 2.01 | 3.16 | 2.36 |
| SafeC, oneMKL DFTI | 12 | 2.85 | **1.11** | **1.60** | 0.812 |
| FFTW (`fftwf`) | 12 | **2.46** | 1.86 | 1.75 | 2.08 |
| PyTorch | 12 | — | — | 3.19 | **0.288** |
| FFTW (`fftwf`) | 1 | 8.72 | 4.29 | 6.19 | 6.50 |
| KFR | 1 | 3.31 | 3.07 | 6.03 | 3.73 |
| RustFFT | 1 | 4.12 | — | 7.75 | — |

- **Convolutions on the GPU**: SafeC is faster than PyTorch in every row. The one-image convolution takes 0.0181 ms against 0.0225 ms (1.24×; 2.1× against PyTorch's TF32 default). The batch of 32 is 1.57× faster in fp32 and 1.16× with TF32. The U-Net at batch 16 is 1.64× faster in fp32 and 1.23× with TF32, and at batch 1 with TF32, as one graph, it runs 3.1× faster (0.0874 ms against 0.269 ms).
- **What changed**: three gaps from the [earlier run](#h200-conv-fft-before) were profiled with Nsight Systems and closed:
  - *One-image convolution* (was 0.0335 ms vs PyTorch's 0.0224 ms): both ran the same cuDNN kernel. The difference was host time. cuDNN 9's legacy `cudnnConvolutionForward` spends about 38 µs of CPU per call re-deriving its engine, more than the kernel takes, and the bias went through a second call. SafeC now builds a plan with cuDNN's graph API for the fused conv → + bias (→ ReLU) graph and keeps its variant pack. A call costs about 6 µs of host time and launches one kernel.
  - *TF32 at batch 16–32* (was 10–20% behind): the graph API reaches engines the legacy algorithm list doesn't, including the Hopper (`sm90`) kernels PyTorch was using. The U-Net also copied its two skip connections with one memcpy per image, 64 copies per forward pass at batch 16. It now uses two launches of a strided-copy kernel (`cuda_copy_rows`) per concat.
  - *Host-array FFT* (was 2.71 ms vs 2.05 ms): the driver copies pageable memory through its own pinned buffers on one thread, about 1.05 ms per 16 MB on this host. `cuda_write`/`cuda_read` now split copies of 1 MiB and up across four lanes (the caller plus three helper threads). Each lane stages its slice through pinned buffers on its own stream, and that moves 16 MB in 0.45–0.7 ms. The FFT round trip itself got 2.2× faster; the transform is about 41 µs of it.
- **CPU convolution**: 3×3 stride-1 layers now use Winograd F(2×2, 3×3), the rest im2col + `sgemm`, all with AVX-512 kernels. `conv64` takes 0.854 ms, 1.8× faster than the previous run (1.53 ms) and 199× faster than the beta-5 direct loop (169.8 ms); the U-Net takes 3.31 ms (2.0× and 138×). PyTorch's CPU path (oneDNN) is still 2.0× faster on `conv64`. With oneMKL linked, SafeC runs the U-Net 1.24× faster than PyTorch (2.65 ms vs 3.29 ms). cuDNN is 47× faster than SafeC's CPU path at batch 1, and 21× on the U-Net.
- **FFT on the GPU**: with data on the GPU, SafeC and PyTorch both call cuFFT, and their times match within 10%. For host arrays, PCIe and the host-side copies dominate. SafeC's pinned lanes make its round trip 1.64× faster than PyTorch's `.to("cuda")` … `.cpu()`, and it now takes about as long as `fft.h` on the CPU (1.25 ms vs 1.21 ms).
- **FFT on the CPU, double**: the 2²⁰-point transform takes 1.21 ms, 164× faster than beta-5 here (198.1 ms), level with oneMKL and PyTorch (1.18 ms) and 1.9× faster than 12-thread FFTW. On the other three the leader is oneMKL's DFTI (batch, rfft) or PyTorch (fft2d, 0.654 ms against SafeC's 1.08); SafeC ties FFTW on the batch and is 1.15× behind it on `rfft`.
- **FFT on the CPU, float**: on this machine SafeC's `fft_f32` is slower than its double code: 3.06 ms against 1.21 ms at 2²⁰ points and 2.01 ms against 1.08 ms in 2D, about the same on the batch and `rfft`. On the 7800X3D the float rows take 0.6–0.8× the double time. The cause hasn't been investigated. oneMKL leads in 2D, on the batch and (after PyTorch) on `rfft`; FFTW leads at 2²⁰ points.

### Attention on the H200 {#h200-attention}

Flash and linear attention from [`gpu_cuda_attention.h`](/stdlib/ml#cuda-attention) against PyTorch 2.10: `scaled_dot_product_attention` for flash attention (on this GPU PyTorch picks cuDNN's fused flash kernels for fp16/bf16 and CUTLASS's memory-efficient kernel for fp32), and for linear attention a vectorized PyTorch implementation (two einsums non-causal; 64-row chunks with a cumulative state sum causal) and flash-linear-attention 0.5.2's Triton `chunk_linear_attn` (causal only). Batch 4, 16 heads, [batch, heads, tokens, head dim] layout, data on the GPU; each number is the mean of 10–100 queued calls between two synchronizations, best of 3 runs, with the training job paused. TFLOPS count 4·B·H·S²·D (halved when causal).

Flash attention, ms per call (TFLOPS):

| Data | Head dim | Tokens | Causal | SafeC ms (TFLOPS) | PyTorch ms (TFLOPS) | PyTorch / SafeC |
|---|---|---|---|---|---|---|
| fp16 | 64 | 1024 | no | **0.043 (398)** | 0.044 (393) | 1.01 |
| fp16 | 64 | 1024 | yes | 0.036 (239) | **0.035 (244)** | 0.98 |
| fp16 | 64 | 4096 | no | **0.572 (481)** | 0.575 (478) | 1.01 |
| fp16 | 64 | 4096 | yes | 0.336 (407) | **0.332 (414)** | 0.99 |
| fp16 | 128 | 1024 | no | 0.060 (571) | **0.060 (574)** | 1.00 |
| fp16 | 128 | 1024 | yes | **0.049 (352)** | 0.050 (345) | 1.02 |
| fp16 | 128 | 4096 | no | 0.831 (661) | **0.822 (669)** | 0.99 |
| fp16 | 128 | 4096 | yes | 0.480 (572) | **0.478 (575)** | 1.00 |
| bf16 | 64 | 1024 | no | **0.043 (400)** | 0.043 (396) | 1.01 |
| bf16 | 64 | 1024 | yes | 0.036 (239) | **0.035 (245)** | 0.97 |
| bf16 | 64 | 4096 | no | **0.569 (483)** | 0.573 (479) | 1.01 |
| bf16 | 64 | 4096 | yes | 0.336 (409) | **0.331 (414)** | 0.98 |
| bf16 | 128 | 1024 | no | **0.059 (586)** | 0.059 (585) | 1.00 |
| bf16 | 128 | 1024 | yes | **0.048 (354)** | 0.049 (347) | 1.02 |
| bf16 | 128 | 4096 | no | 0.798 (688) | **0.786 (699)** | 0.98 |
| bf16 | 128 | 4096 | yes | 0.466 (589) | **0.463 (591)** | 0.99 |
| fp32 | 64 | 1024 | no | **0.387 (44)** | 0.519 (33) | 1.34 |
| fp32 | 64 | 1024 | yes | **0.263 (33)** | 0.353 (24) | 1.34 |
| fp32 | 64 | 4096 | no | **5.81 (47)** | 7.78 (35) | 1.34 |
| fp32 | 64 | 4096 | yes | **3.11 (44)** | 4.18 (33) | 1.34 |
| fp32 | 128 | 1024 | no | **0.748 (46)** | 0.788 (44) | 1.05 |
| fp32 | 128 | 1024 | yes | 0.502 (34) | **0.485 (35)** | 0.97 |
| fp32 | 128 | 4096 | no | **11.3 (49)** | 11.9 (46) | 1.05 |
| fp32 | 128 | 4096 | yes | **6.07 (45)** | 6.19 (44) | 1.02 |
| bf16 (GQA 32 / 8 heads) | 128 | 4096 | yes | 0.893 (615) | **0.884 (622)** | 0.99 |

Linear attention (phi(x) = elu(x) + 1), ms per call:

| Data | Head dim | Tokens | Causal | SafeC | PyTorch (default: TF32 for fp32) | PyTorch, IEEE fp32 | fla (Triton) |
|---|---|---|---|---|---|---|---|
| fp32 | 64 | 4096 | no | **0.231** | 0.350 | 0.500 | — |
| fp32 | 64 | 4096 | yes | **0.385** | 0.879 | 0.998 | 0.815 |
| fp32 | 64 | 16384 | no | **0.868** | 1.41 | 1.97 | — |
| fp32 | 64 | 16384 | yes | **1.44** | 3.32 | 3.74 | 3.08 |
| fp32 | 128 | 4096 | no | 0.633 | **0.629** | 0.934 | — |
| fp32 | 128 | 4096 | yes | **1.03** | 1.69 | 2.04 | 1.59 |
| bf16 | 64 | 4096 | no | **0.228** | 0.504 | — | — |
| bf16 | 64 | 4096 | yes | **0.392** | 1.03 | — | 0.643 |
| bf16 | 64 | 16384 | no | **0.853** | 1.98 | — | — |
| bf16 | 64 | 16384 | yes | **1.48** | 3.89 | — | 2.38 |
| bf16 | 128 | 4096 | no | **0.619** | 0.921 | — | — |
| bf16 | 128 | 4096 | yes | **1.01** | 1.98 | — | 1.15 |

- **fp16/bf16 flash attention** goes through cuDNN's fused attention engine, the same kernels PyTorch runs here, so the two are within 3% everywhere (up to 690 TFLOPS). SafeC builds the cuDNN graph itself (dlopen, no link dependency) and falls back to its own FlashAttention-2 kernel (mma.sync) without cuDNN. Grouped-query attention runs at PyTorch's speed too.
- **fp32 flash attention** runs SafeC's FlashAttention-2 kernel on tensor cores in 3xTF32 (each product as hi·hi + hi·lo + lo·hi of TF32 halves), the same trick CUTLASS uses, with errors of ~2e-7. It is 1.34× faster than PyTorch at head dim 64 and 0.97–1.05× at head dim 128. The operands are rounded to TF32 with an integer add-and-mask: `cvt.rna.tf32.f32` gives the same bits but is emulated with a long instruction sequence on sm_90, and with it the kernel was bound by integer work (0.68–0.75× at head dim 128 before). At head dim 128 the kernel is now bound by shared-memory traffic; splitting K, V or Q into TF32 halves once in shared memory, or keeping Q in registers, was slower.
- **Linear attention** cuts the keys into 64-row chunks. A per-head pass walks a share of the chunks with the D×D state phi(K)ᵀV in registers, storing the state before each chunk when causal; a small pass adds up those shares; then every output chunk runs in parallel from its state plus, when causal, the masked phi(Q)phi(K)ᵀV inside the chunk. All of it runs on CUDA cores in exact fp32 (errors ~1e-7). SafeC is 1.5–2.6× faster than vectorized PyTorch and 1.1–2.1× faster than fla in every row but one: head dim 128 fp32 non-causal, where it is level with PyTorch's TF32 einsums (0.99×) and 1.5× faster than PyTorch in IEEE fp32. PyTorch's fp32 einsums use TF32 by default in NVIDIA's NGC container (`TORCH_ALLOW_TF32_CUBLAS_OVERRIDE=1`), and fla's fp32 output differs from the exact result by ~1e-3, which suggests TF32 dot products inside. The previous version (per-chunk states, a separate prefix pass over all of them, outputs split across column slices) was 0.45–0.78× of PyTorch at head dim 128: its output kernel recomputed phi(Q)phi(K)ᵀ for each column slice at two multiply-adds per shared-memory load, and the prefix pass read and wrote every chunk's D×D state once more. Its exact-fp32 matrix products now run at roughly the rate of cuBLAS's exact-fp32 SGEMM on these shapes (~35 TFLOPS).
- `tests/stdlib/cuda/cuda_attention_check.sc` checks every path (cuDNN, both own flash kernels, CUDA-core fallbacks, chunked and sequential linear attention, both argument forms, sliding windows, GQA, graph replay) against a double-precision reference and attention.h's CPU functions: 97 checks.

::: details Sources
[attn_cuda.sc](/benchmarks/attention/safec/attn_cuda.sc) · [attn.py](/benchmarks/attention/pytorch/attn.py) · [gpu_cuda_attention.h](/benchmarks/stdlib/gpu_cuda_attention.h) · [gpu_cuda_attention.sc](/benchmarks/stdlib/gpu_cuda_attention.sc)
:::

### DSP on the CPU, Xeon 8570 {#h200-dsp}

The [DSP workloads](#dsp-cpu) on this machine, ms per call (lower is better, fastest in bold): [`std/dsp`](/stdlib/dsp) on its own and with oneMKL linked, against SciPy 1.16.3, KFR 7.2.0 and, for the biquad, Q and madronalib (float32 only) and dasp for resampling.

Double precision:

| Workload | SafeC | SafeC, oneMKL | SciPy | KFR | Others |
|---|---|---|---|---|---|
| fir stream | 7.46 | **7.37** | 27.5 | 42.8 |  |
| fir block | 0.730 | **0.686** | 25.1 | — |  |
| fftconv | 4.54 | **3.82** | 60.5 | 6.57 |  |
| biquad | **0.782** | **0.782** | 6.99 | 4.45 |  |
| stft | 5.69 | **5.47** | 92.4 | — |  |
| resample | **7.38** | 8.03 | 17.5 | 88.4 (draft 58.1) | dasp 656.9 |
| conv2d | **0.601** | 0.715 | 53.2 | — |  |

Single precision:

| Workload | SafeC | SafeC, oneMKL | SciPy | KFR | Others |
|---|---|---|---|---|---|
| fir stream | **3.83** | 3.85 | 16.0 | 37.2 |  |
| fir block | **0.649** | 0.673 | 15.7 | — |  |
| fftconv | 4.23 | **2.21** | 32.2 | 3.28 |  |
| biquad | **0.773** | 0.785 | 6.34 | 4.26 | Q 2.81, madronalib 5.37 |
| stft | 4.88 | **4.14** | 95.1 | — |  |
| resample | 11.1 | **10.8** | 16.5 | 53.2 (draft 60.6) |  |

- A SafeC build is the fastest on every row, in both precisions. The built-in code loses only the float `fftconv`, to KFR (4.23 ms vs 3.28); with oneMKL's FFT linked it takes 2.21 ms, and oneMKL also helps `fftconv` and `stft` in double.
- Against SciPy in double: the streaming FIR is 3.7× faster, the block FIR 34×, `fftconv` 13×, the biquad 8.9×, the STFT 16×, resampling 2.4× and the 5×5 image convolution 89×. The float biquad is 3.6× faster than Q, 5.5× faster than KFR and 6.9× faster than madronalib.
- **Float vs double**: float halves the streaming FIR (3.83 vs 7.46 ms) and helps the STFT a little; the biquad is latency-bound and doesn't change. The float resampler is slower than the double one (11.1 vs 7.38 ms), more than on the 7800X3D (3.96 vs 3.71).

### Classic benchmarks on the Xeon 8570 {#xeon-8570-classic}

Release-build wall-clock time, best of 3 (lower is better; fastest in bold). Web service is requests per second (higher is better). The Zig sources for the threaded and web benchmarks were written for Zig 0.16 and don't build with 0.17.0, which changed `**` array repetition and removed `@cImport`.

| Benchmark | SafeC | C | C++ | Rust | Zig | Go | Python |
|---|---|---|---|---|---|---|---|
| fib(37) | 0.089s | 0.087s | 0.089s | 0.092s | **0.072s** | 0.132s | 2.432s |
| n-body | **0.145s** | 0.146s | 0.147s | 0.155s | 0.154s | 0.190s | 9.337s |
| binary-trees (SafeC: arena) | **0.282s** | 1.175s | 1.363s | 1.405s | 1.253s | 3.510s | 29.166s |
| binary-trees, SafeC heap | 0.945s | | | | | | |
| binary-trees, 8 threads (SafeC: arena) | **0.092s** | 0.417s | 0.464s | 0.457s | N/A | 0.410s | 76.851s |
| binary-trees, 8 threads, SafeC heap | 0.333s | | | | | | |
| SIMD, explicit vectors | 0.111s | 0.118s | 0.117s | 0.112s | **0.098s** | — | 0.333s (NumPy) |
| Web service (req/s) | **33,730** | 32,196 | 32,523 | 32,592 | N/A | 32,219 | 2,887 |

Collections (1,000,000 elements, ms): `Vec` push 3.33 vs C 1.95, `vec_sort` **76.3** vs `qsort` 149.0, `HashMap` insert 56.3 vs **53.9**, get 20.1 vs **18.6**, `List` push+pop **13.3** vs 27.2, `Queue` **3.30** vs 6.64, `BST` insert 348.8 vs **312.0**, get 351.0 vs **337.8**. Allocator: `std::alloc`/`dealloc` 10.4 ns per interleaved pair vs `malloc`/`free` **8.83** ns, and the batch pattern takes **120** ms vs 126 ms.

`std::alloc`'s new [slabs](/stdlib/mem#allocation-performance) show here: SafeC's heap binary-trees is 17% faster than the previous run (1.141 → 0.945 s), 16% with 8 threads (0.398 → 0.333 s), `List` and `Queue` are about 25% faster, and the batch allocation pattern went from 159 ms to 120 ms, now ahead of glibc's `malloc`. The other rows are within 10% of the previous run.

::: details Sources
[train_cuda.sc](/benchmarks/ml/safec/train_cuda.sc) · [gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [conv_cpu.sc](/benchmarks/conv/safec/conv_cpu.sc) · [conv_cuda.sc](/benchmarks/conv/safec/conv_cuda.sc) · [PyTorch conv.py](/benchmarks/conv/pytorch/conv.py) · [fft_cpu.sc](/benchmarks/fft/safec/fft_cpu.sc) · [fft_cuda.sc](/benchmarks/fft/safec/fft_cuda.sc) · [PyTorch fft.py](/benchmarks/fft/pytorch/fft.py) · [gpu_cudnn.h](/benchmarks/stdlib/gpu_cudnn.h) · [gpu_cudnn.sc](/benchmarks/stdlib/gpu_cudnn.sc) · [fft_cuda.h](/benchmarks/stdlib/fft_cuda.h) · [fft_cuda.sc](/benchmarks/stdlib/fft_cuda.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [PyTorch big train.py](/benchmarks/ml_big/pytorch/train.py) · the classic benchmarks' sources are linked in their sections above.
:::


## Apple M1 Pro {#m1pro}

macOS 26.6, Homebrew clang 22.1, rustc 1.98, Go 1.26, Zig 0.16, Python 3.14 (NumPy 2.5.4, SciPy 1.18.1), KFR 7 (Homebrew). Best of 3 runs, through `run_bench.py`. The web service row isn't measured here: VS Code's port forwarding held 127.0.0.1:8082–8087, the ports the servers use, so `ab` reached the editor instead.

### Classic benchmarks on the M1 Pro {#m1pro-classic}

Release-build wall-clock time, best of 3 (lower is better; fastest in bold).

| Benchmark | SafeC | C | C++ | Rust | Zig | Go | Python |
|---|---|---|---|---|---|---|---|
| fib(37) | 0.083s | 0.082s | 0.088s | **0.081s** | 0.081s | 0.089s | 2.715s |
| n-body | 0.119s | 0.112s | 0.112s | 0.118s | 0.117s | **0.103s** | 9.882s |
| binary-trees (SafeC: arena) | **0.222s** | 1.562s | 1.720s | 1.744s | 1.549s | 1.274s | 23.254s |
| binary-trees, SafeC heap | 0.830s | | | | | | |
| binary-trees, 8 threads (SafeC: arena) | **0.064s** | 0.592s | 0.613s | 0.683s | 0.585s | 0.496s | 25.359s |
| binary-trees, 8 threads, SafeC heap | 0.160s | | | | | | |
| SIMD, explicit vectors | **0.031s** | 0.031s | 0.032s | 0.041s | 0.033s | — | 0.152s (NumPy) |

Collections (1,000,000 elements, ms): `Vec` push 0.966 vs C **0.79**, `vec_sort` **56.4** vs `qsort` 88.8, `HashMap` insert 31.1 vs **28.8**, get 16.6 vs **12.9**, `List` push+pop **4.87** vs 26.1, `Queue` **1.66** vs 1.94, `BST` insert 352 vs **327**, get **278** vs 397. Allocator: `std::alloc`/`dealloc` **12.2** ns per interleaved pair vs `malloc`/`free` 27.6 ns, and the batch pattern takes **115** ms vs 233 ms.

::: details Sources
[fib.sc](/benchmarks/fib/safec/fib.sc) · [nbody.sc](/benchmarks/nbody/safec/nbody.sc) · [binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [results/macos-m1pro.json](/benchmarks/results/macos-m1pro.json)
:::

### DSP on the M1 Pro {#m1pro-dsp}

The [DSP workloads](#dsp-cpu) against SciPy 1.18, KFR 7, Q and madronalib (biquad) and dasp (resampling). ms per call, best of 3. SafeC is the fastest on every row, in both precisions.

Double precision:

| Workload | SafeC | SciPy | KFR | Others |
|---|---|---|---|---|
| fir stream | **5.82** | 22.4 | 44.8 |  |
| fir block | **0.926** | 21.4 | — |  |
| fftconv | **3.06** | 22.7 | 8.06 |  |
| biquad | **0.666** | 7.97 | 5.08 |  |
| stft | **3.79** | 57.7 | — |  |
| resample | **4.13** | 14.9 | 162 (draft 32.9) | dasp 291 |
| conv2d | **0.589** | 36.1 | — |  |

Single precision:

| Workload | SafeC | SciPy | KFR | Others |
|---|---|---|---|---|
| fir stream | **3.45** | 15.9 | 63.5 |  |
| fir block | **0.523** | 15.1 | — |  |
| fftconv | **1.97** | 16.8 | 4.05 |  |
| biquad | **0.661** | 7.84 | 4.19 | Q 2.62, madronalib 4.38 |
| stft | **2.2** | 56 | — |  |
| resample | **4.77** | 16.8 | 90 (draft 34) |  |

- **FIR on Apple Silicon** hands the sliding dot product to Accelerate's `vDSP_conv`/`vDSP_convD` when the program links Accelerate (safeguard's default on macOS). SafeC's own NEON kernel tops out at about 34 GFLOPS in double on a core — every accumulator count tried, and shuffles instead of unaligned loads, landed there — while vDSP runs the same 127-tap filter at about 47; the streaming FIR went from 8.03 to 5.82 ms (double) and 4.03 to 3.45 ms (float), and the threaded block FIR from 1.38 to 0.93 ms. Without Accelerate the NEON kernel runs.
- **FFT** stays on SafeC's own transform: it is 3.7× faster than vDSP's single-threaded 2²⁰-point double FFT and ahead of vDSP's threaded float batch; only vDSP's threaded double batch (1.78 vs 2.51 ms) is faster, and converting to vDSP's split-complex layout per call would eat most of that.
- **Biquad** (0.66 ms) beats vDSP's `vDSP_biquad` (0.93–1.0 ms) as well as Q and madronalib.

::: details Sources
[dsp_cpu.sc](/benchmarks/dsp/safec/dsp_cpu.sc) · [dsp_scipy.py](/benchmarks/dsp/python/dsp_scipy.py) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [audio_libs_bench.cpp](/benchmarks/dsp/cpp/audio_libs_bench.cpp) · [dasp main.rs](/benchmarks/dsp/rust/src/main.rs) · [dsp.sc](/benchmarks/stdlib/dsp.sc)
:::

### Attention on the M1 Pro {#m1pro-attention}

Flash and linear attention from [`gpu_mps.h`](/stdlib/ml#mps-flash-linear-attention) against PyTorch 2.13's `mps` device and MLX 0.32 on an Apple M1 Pro (macOS 26.6), with the same shapes as [the H200 run](#h200-attention): batch 4, 16 heads, [batch, heads, tokens, head dim], data on the GPU. Flash attention compares with PyTorch's `scaled_dot_product_attention` and MLX's fused `mx.fast.scaled_dot_product_attention`; linear attention (φ(x) = elu(x) + 1) with the same vectorized implementation in both (two einsums non-causal; 64-row chunks with a cumulative state sum causal), as neither ships a fused kernel. Each number is the mean of 5–40 calls queued between two synchronizations, best of 3 runs. TFLOPS count 4·B·H·S²·D (halved when causal).

Flash attention, ms per call (TFLOPS):

| Data | Head dim | Tokens | Causal | SafeC ms (TFLOPS) | PyTorch ms (TFLOPS) | MLX ms (TFLOPS) | PyTorch / SafeC | MLX / SafeC |
|---|---|---|---|---|---|---|---|---|
| fp16 | 64 | 1024 | no | 4.93 (3.40) | 4.88 (3.42) | **4.59 (3.74)** | 0.99 | 0.93 |
| fp16 | 64 | 1024 | yes | 2.81 (2.99) | 2.72 (3.09) | **2.65 (3.24)** | 0.97 | 0.94 |
| fp16 | 64 | 4096 | no | 78 (3.42) | 76.3 (3.51) | **72.6 (3.79)** | 0.98 | 0.93 |
| fp16 | 64 | 4096 | yes | **40 (3.22)** | 40.1 (3.38) | 40.7 (3.31) | 1.00 | 1.02 |
| fp16 | 128 | 1024 | no | 11 (3.07) | 9.66 (3.54) | **9.4 (3.63)** | 0.88 | 0.86 |
| fp16 | 128 | 1024 | yes | 6.16 (2.72) | 5.79 (2.96) | **4.95 (3.47)** | 0.94 | 0.80 |
| fp16 | 128 | 4096 | no | 171 (3.17) | 151 (3.64) | **148 (3.72)** | 0.88 | 0.86 |
| fp16 | 128 | 4096 | yes | 89.8 (2.97) | 84.4 (3.23) | **75.1 (3.66)** | 0.94 | 0.84 |
| bf16 | 64 | 1024 | no | 6.04 (2.77) | 5.69 (2.96) | **5.25 (3.23)** | 0.94 | 0.87 |
| bf16 | 64 | 1024 | yes | 3.25 (2.55) | 3.11 (2.64) | **2.8 (3.07)** | 0.96 | 0.86 |
| bf16 | 64 | 4096 | no | 95.7 (2.80) | 89 (3.05) | **82.5 (3.32)** | 0.93 | 0.86 |
| bf16 | 64 | 4096 | yes | 49.1 (2.76) | 45.8 (2.96) | **42.5 (3.17)** | 0.93 | 0.87 |
| bf16 | 128 | 1024 | no | 12.1 (2.82) | 13.2 (2.55) | **12.1 (2.85)** | 1.09 | 1.00 |
| bf16 | 128 | 1024 | yes | 6.76 (2.52) | 7.22 (2.35) | **6.54 (2.63)** | 1.07 | 0.97 |
| bf16 | 128 | 4096 | no | **189 (2.90)** | 201 (2.73) | 190 (2.90) | 1.06 | 1.00 |
| bf16 | 128 | 4096 | yes | 98.2 (2.80) | 106 (2.53) | **96.6 (2.85)** | 1.08 | 0.98 |
| fp32 | 64 | 1024 | no | **6.05 (2.82)** | 6.87 (2.43) | 6.12 (2.81) | 1.13 | 1.01 |
| fp32 | 64 | 1024 | yes | **3.21 (2.68)** | 3.83 (2.22) | 3.48 (2.47) | 1.20 | 1.08 |
| fp32 | 64 | 4096 | no | **93.8 (2.93)** | 107 (2.52) | 97.5 (2.81) | 1.15 | 1.04 |
| fp32 | 64 | 4096 | yes | **47.8 (2.87)** | 56.1 (2.42) | 52 (2.64) | 1.17 | 1.09 |
| fp32 | 128 | 1024 | no | **12.2 (2.83)** | 20 (1.68) | 16.3 (2.10) | 1.64 | 1.34 |
| fp32 | 128 | 1024 | yes | **6.74 (2.55)** | 11 (1.53) | 9.22 (1.85) | 1.63 | 1.37 |
| fp32 | 128 | 4096 | no | **187 (2.94)** | 317 (1.71) | 265 (2.07) | 1.70 | 1.42 |
| fp32 | 128 | 4096 | yes | **97.8 (2.81)** | 163 (1.65) | 125 (1.93) | 1.67 | 1.28 |
| bf16 (GQA 32 / 8 heads) | 128 | 4096 | yes | **195 (2.82)** | 214 (2.57) | 197 (2.76) | 1.10 | 1.01 |

Linear attention (φ(x) = elu(x) + 1), ms per call:

| Data | Head dim | Tokens | Causal | SafeC | PyTorch | MLX | PyTorch / SafeC | MLX / SafeC |
|---|---|---|---|---|---|---|---|---|
| fp32 | 64 | 4096 | no | **3.27** | 10.6 | 11 | 3.2 | 3.4 |
| fp32 | 64 | 4096 | yes | **6.99** | 28.4 | 21.1 | 4.1 | 3.0 |
| fp32 | 64 | 16384 | no | **12.3** | 41.8 | 40.8 | 3.4 | 3.3 |
| fp32 | 64 | 16384 | yes | **28.1** | 113 | 81 | 4.0 | 2.9 |
| fp32 | 128 | 4096 | no | **10.3** | 21.9 | 22.9 | 2.1 | 2.2 |
| fp32 | 128 | 4096 | yes | **22** | 58 | 43.5 | 2.6 | 2.0 |
| bf16 | 64 | 4096 | no | **3.15** | 12.1 | 12.5 | 3.9 | 4.0 |
| bf16 | 64 | 4096 | yes | **6.61** | 29.3 | 22.6 | 4.4 | 3.4 |
| bf16 | 64 | 16384 | no | **11.9** | 47.5 | 49 | 4.0 | 4.1 |
| bf16 | 64 | 16384 | yes | **26.6** | 117 | 87.6 | 4.4 | 3.3 |
| bf16 | 128 | 4096 | no | **9.92** | 25.2 | 26.3 | 2.5 | 2.7 |
| bf16 | 128 | 4096 | yes | **21.1** | 60.4 | 45.7 | 2.9 | 2.2 |

- **fp32 flash attention** is the fastest of the three everywhere: 1.01–1.42× MLX and 1.13–1.70× PyTorch. SafeC's FlashAttention-2 kernel runs QKᵀ and PV on the GPU's 8×8 simdgroup matrix units with an online softmax on the fragments, in fp32 throughout.
- **bf16 flash attention** at head dim 128 (and with grouped-query K/V) is level with MLX (0.97–1.01×) and 1.06–1.10× faster than PyTorch; at 64 it reaches 0.86–0.87× MLX. The M1 family has no bf16 matrix units, so SafeC converts bf16 tiles to fp32 as it stages them.
- **fp16 flash attention** reaches 0.93–1.02× MLX at head dim 64 and 0.80–0.86× at 128 (2.7–3.4 TFLOPS). It multiplies in half precision.
- **What limits it**: a kernel that skipped re-staging K/V tiles ran ~20% faster, while prefetching the next tile into registers or making the threadgroup stores cheaper gained nothing — reading K/V once per query block is the cost. 64-row query blocks (8 simdgroups) halve it, which is what put bf16/fp32 at head dim 128 level with or ahead of MLX; for bf16/fp32 at 64 they measured no faster. MLX's own arrangement (fp32 fragments for every type, Q re-read from threadgroup memory, K and V sharing one buffer) measured slower here.
- **Linear attention** is 2.0–4.4× faster than both vectorized versions. At head dims 64 and 128 it runs chunk-parallel on the matrix units: per-segment states φ(K)ᵀV, a prefix over 64-row chunks, then every chunk's outputs at once, all in fp32.
- `tests/stdlib/mps/run.sh` checks every path against a double-precision reference and attention.h's CPU functions: 95 checks.

::: details Sources
[attn_mps.sc](/benchmarks/attention/safec/attn_mps.sc) · [PyTorch attn.py](/benchmarks/attention/pytorch/attn.py) · [MLX attn.py](/benchmarks/attention/mlx/attn.py) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal)
:::

## Earlier runs on other platforms {#earlier-runs}

These numbers are from an earlier run on an Apple M1 Pro (macOS 26.5.1, Apple Clang 21.0.0; the [current M1 Pro numbers](#m1pro) are above), and on the same 7800X3D under WSL2 Ubuntu 22.04 (clang 19.1.7) and Windows 11 (clang/LLVM ~19). They weren't re-measured since, and the compiler and std have changed, so compare them only with each other.

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
