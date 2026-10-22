---
outline: [2, 4]
---

# Benchmarks

## Abstract {#abstract}

SafeC is measured against C, C++, Rust, Zig, Go and Python on classic microbenchmarks. Its standard library is measured against plain C, glibc, the vendor math libraries, NumPy/SciPy, FFTW, KFR, PyTorch, TensorFlow and MLX on collections, allocation, an HTTP server, signal processing and machine learning. The same suite runs on three machines: a desktop (AMD Ryzen 7 7800X3D + AMD Instinct MI100), a GPU server (Intel Xeon Platinum 8570 + NVIDIA H200) and a laptop (Apple M1 Pro). Each test shows the three machines side by side.

- **Compute-bound code** (fib, n-body, SIMD) runs within 7% of C on all three machines.
- **Allocation-heavy code** is where SafeC's `region`/`arena` pays off: binary-trees is 4.2–7.0× faster than C on one thread and 4.3–9.3× on eight.
- **`std/dsp`** is the fastest library on every DSP workload on all three machines, in double and in float.
- **Machine learning**, comparing each framework's best configuration:
  - SafeC's own GPU backends train the small MLP 2.6× (MI100) and 7.8× (H200) faster than PyTorch's best.
  - Gemma 4 E2B decodes 3.1× (MI100) and 1.5× (H200) faster than PyTorch's best.
  - The training stack (classifiers, VAE, GAN, DQN, PPO, LoRA, conv autoencoder) is 5.2–44× faster than PyTorch's best on the 7800X3D + MI100, on its CPU or ROCm path.
  - Trained on the MI100, a ResNet, U-Net, CNN, MLP, DCGAN and conv VAE are 1.3–3.3× faster than PyTorch's best, with fp16 mixed precision where it helps.
  - On the M1 Pro, MLX decodes Gemma 2.6× faster than SafeC, its fp16 flash attention is 1.2× faster, and PyTorch trains both MLPs 1.2× faster.

## Index {#index}

1. [Introduction](#introduction)
   - [Environments](#environments) · [Method](#method) · [Reading the tables](#reading-the-tables)
2. [Main article](#main-article)
   - [Language and runtime](#language-runtime): [fib(37)](#fib) · [n-body](#n-body) · [binary-trees](#binary-trees) · [binary-trees, 8 threads](#binary-trees-mt) · [SIMD](#simd) · [Compile time](#compile-time)
   - [Standard library](#standard-library): [Collections](#collections) · [Allocator](#allocator) · [Web service](#web-service)
   - [Signal processing](#signal-processing): [DSP](#dsp) · [FFT](#fft)
   - [Machine learning](#machine-learning): [MLP training and inference](#ml-mlp) · [Training: networks, generative models, RL](#ml-training) · [Training on the GPU](#ml-training-gpu) · [Convolution and U-Net](#ml-convolution) · [Attention](#ml-attention) · [LLM inference: Gemma 4 E2B](#ml-llm)
3. [Summary](#summary)

## 1. Introduction {#introduction}

The benchmarks answer one question per test: how does SafeC, or its standard library, compare with what people use instead, on the same machine? The classic microbenchmarks follow [The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html). The library tests compare each `std` module with the established library for the job. Read every number as "roughly this, on this machine": the three machines differ in CPU, core count, OS and toolchain versions, so compare languages and libraries within a machine's column, not the columns with each other.

### Environments {#environments}

| | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| Role | Desktop | GPU server | Laptop |
| CPU | AMD Ryzen 7 7800X3D, 8 cores / 16 threads, AVX-512 | Intel Xeon Platinum 8570, 12 of 112 cores (container), AVX-512 | Apple M1 Pro, 8 performance + 2 efficiency cores, NEON |
| GPU | AMD Instinct MI100 (gfx908, 32 GB HBM2), ROCm 6.4 / 7.1 (HIP, hiprtc, rocBLAS, MIOpen, hipFFT) | NVIDIA H200 (sm_90, 141 GB HBM3e), CUDA 13.0, cuDNN 9.15, driver 580.126.20 | M1 Pro GPU (Metal) |
| OS | Ubuntu 24.04.5, native | Ubuntu 24.04.3 (Backend.AI container) | macOS 26.6 |
| Compilers | `safec` 1.0.0, clang 21.1.8, Rust 1.100.0-nightly, Go 1.27.2, Zig 0.16.0 | `safec` 1.0.0, clang 21.1.8, Rust 1.99.1, Go 1.27.3, Zig 0.17.0 | `safec` 1.0.0, Homebrew clang 22.1, Rust 1.98, Go 1.26, Zig 0.16 |
| Python stack | Python 3.12.3, NumPy 2.5.4, SciPy 1.18.1, PyTorch 2.9.1+rocm6.4, transformers 5.20.2, TensorFlow 2.21 (CPU) | Python 3.12.3, SciPy 1.16.3, PyTorch 2.10 (NGC 25.11), transformers 5.20.2, TensorFlow 2.21 (CPU) | Python 3.14, NumPy 2.5.4, SciPy 1.18.1, PyTorch 2.13 (`mps`), MLX 0.32; for MLP, convolution, FFT and web service: Python 3.12, PyTorch 2.14.1 (`mps`), TensorFlow 2.21 (CPU), MLX 0.32.4, FastAPI + uvicorn |
| Native libraries | OpenBLAS, AOCL-BLAS 5.3.2, oneMKL 2026.2, FFTW 3.3.10, KFR 7.2.0, RustFFT 6.4.1, dasp 0.11, Q, madronalib | OpenBLAS, oneMKL 2026.2, FFTW 3.3.10, KFR 7.2.0, RustFFT 6.4.1, dasp 0.11, Q, madronalib | Accelerate, FFTW 3.3.10, KFR 7.2.0, RustFFT 6.4.1, dasp 0.11, Q, madronalib |
| Results | [linux-7800x3d-mi100.json](/benchmarks/results/linux-7800x3d-mi100.json) | [linux-xeon8570-h200.json](/benchmarks/results/linux-xeon8570-h200.json) | [macos-m1pro.json](/benchmarks/results/macos-m1pro.json) |

Each third-party library is built and linked only into its own benchmark program, never into SafeC's std. On the H200 server a training job shares the GPU; it was paused (`SIGSTOP`) for every run, so the GPU and the 12 cores were idle.

### Method {#method}

- **Builds**: `-O2` for C, C++ and SafeC (SafeC compiles to LLVM IR, then `clang -O2`, as `safeguard build --release` does), `zig build-exe -O ReleaseFast`, `rustc -O`, plain `go build`. SafeC's CPU kernels pick their code at run time for the CPU's [SIMD level](/stdlib/simd#cpu-levels).
- **Timing**: the best (lowest) wall-clock time of 3 runs. GPU and library rows are the mean of many calls queued between two synchronizations, after warm-up calls, best of 3 runs.
- **Memory**: peak RSS of the same runs from GNU `/usr/bin/time -f %M`; for web servers, `VmHWM` from `/proc/<pid>/status` just before shutdown. On macOS, `/usr/bin/time -l`, and the server's own `wait4` maximum RSS.
- **Correctness**: every program's output is checked against the known-correct value before its time counts. The ML tests also check losses and, for the LLM, the generated tokens and logits against `transformers`.
- **Reproduce**: [`run_bench.py`](/benchmarks/run_bench.py) builds and runs everything and writes the results JSON for a machine.

### Reading the tables {#reading-the-tables}

- Columns are the three machines. Lower is better unless a table says otherwise, and the best value in each column (or each row group) is in **bold**.
- Tables with several libraries per row write each cell as one value per machine.
- "—" means the test wasn't run on that machine; "N/A" means it was run and that entry failed to build or run.
- **Machine learning tables show only the best configuration of each framework on each machine** (CPU or GPU, fp32, TF32 or 16-bit, graph replay or not), with that configuration named in the cell. The other configurations are in the results JSON.

## 2. Main article {#main-article}

### 2.1 Language and runtime {#language-runtime}

#### fib(37) — recursive Fibonacci {#fib}

Naive recursive Fibonacci: function-call and integer-arithmetic overhead, no allocation, no I/O. Release time · peak memory.

| Language | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | **0.042** s · 1.9 MB | 0.089 s · 8.8 MB | 0.083 s · 5.6 MB |
| C | **0.042** s · 1.9 MB | 0.087 s · 8.8 MB | 0.082 s · 1.3 MB |
| C++ | **0.042** s · 3.8 MB | 0.089 s · 10.5 MB | 0.088 s · 1.3 MB |
| Rust | **0.042** s · 1.9 MB | 0.092 s · 10.5 MB | **0.081** s · 1.5 MB |
| Zig | 0.043 s · 0.6 MB | **0.072** s · 2.8 MB | **0.081** s · 1.5 MB |
| Go | 0.089 s · 2.0 MB | 0.132 s · 2.8 MB | 0.089 s · 3.9 MB |
| Python | 2.016 s · 10.4 MB | 2.432 s · 19.2 MB | 2.715 s · 14.5 MB |

Everything that goes through LLVM lands within a few percent of each other; the differences are noise. Zig is fastest on the Xeon (0.072 s vs 0.087–0.092 s).

::: details Sources
[fib.sc](/benchmarks/fib/safec/fib.sc) · [fib.c](/benchmarks/fib/c/fib.c) · [fib.cpp](/benchmarks/fib/cpp/fib.cpp) · [fib.rs](/benchmarks/fib/rust/fib.rs) · [fib.zig](/benchmarks/fib/zig/fib.zig) · [fib.go](/benchmarks/fib/go/fib.go) · [fib.py](/benchmarks/fib/python/fib.py)
:::

#### n-body — 5-body orbital simulation {#n-body}

The Benchmarks Game n-body test (Sun, Jupiter, Saturn, Uranus, Neptune), 2,000,000 steps: floating-point heavy, no allocation, a tiny working set. SafeC's initial state, including the momentum offset, is a `consteval` table, so the program starts from constants; the simulation is the same code as the others'.

| Language | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | **0.098** s · 2.0 MB | **0.145** s · 8.8 MB | 0.119 s · 5.6 MB |
| C | 0.099 s · 2.0 MB | 0.146 s · 8.8 MB | 0.112 s · 1.3 MB |
| C++ | 0.099 s · 3.8 MB | 0.147 s · 10.5 MB | 0.112 s · 1.3 MB |
| Rust | 0.107 s · 2.0 MB | 0.155 s · 10.5 MB | 0.118 s · 1.5 MB |
| Zig | 0.110 s · 0.5 MB | 0.154 s · 2.8 MB | 0.117 s · 1.5 MB |
| Go | 0.106 s · 2.0 MB | 0.190 s · 2.8 MB | **0.103** s · 3.9 MB |
| Python | 7.630 s · 10.9 MB | 9.337 s · 19.2 MB | 9.882 s · 15.2 MB |

SafeC is the fastest on the two x86-64 machines. On the M1 Pro it is 0.94× C and 0.87× Go, the one compute test where it trails.

::: details Sources
[nbody.sc](/benchmarks/nbody/safec/nbody.sc) · [nbody.c](/benchmarks/nbody/c/nbody.c) · [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp) · [nbody.rs](/benchmarks/nbody/rust/nbody.rs) · [nbody.zig](/benchmarks/nbody/zig/nbody.zig) · [nbody.go](/benchmarks/nbody/go/nbody.go) · [nbody.py](/benchmarks/nbody/python/nbody.py)
:::

#### binary-trees — allocation stress {#binary-trees}

Builds and discards millions of small binary trees (max depth 18): memory management rather than arithmetic. The main SafeC entry uses `region`/`arena<R>` (bump-pointer allocation; `arena_reset<R>()` discards a whole region in O(1)), which is what this all-short-lived workload is for. **SafeC (heap)** is the same program with `new` / `std::dealloc`, the like-for-like comparison with the others' `malloc`, `Box` or GC.

| Language | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC (arena) | **0.132** s · 25.9 MB | **0.282** s · 33.2 MB | **0.222** s · 29.6 MB |
| SafeC (heap) | 0.616 s · 38.8 MB | 0.945 s · 47.1 MB | 0.830 s · 50.3 MB |
| C | 0.810 s · 33.9 MB | 1.175 s · 42.0 MB | 1.562 s · 17.5 MB |
| C++ | 0.924 s · 35.9 MB | 1.363 s · 43.8 MB | 1.720 s · 17.5 MB |
| Rust | 0.914 s · 34.0 MB | 1.405 s · 43.8 MB | 1.744 s · 17.6 MB |
| Zig | 0.854 s · 33.6 MB | 1.253 s · 42.0 MB | 1.549 s · 17.6 MB |
| Go | 0.718 s · 36.8 MB | 3.510 s · 34.8 MB | 1.274 s · 39.1 MB |
| Python | 18.05 s · 82.6 MB | 29.17 s · 92.0 MB | 23.25 s · 87.1 MB |

- The arena version is 4.2–7.0× faster than C and also the leanest on the two Linux machines.
- The heap version beats C, C++, Rust and Zig on all three. `std::alloc` serves these 16-byte nodes from per-thread [slabs](/stdlib/mem#allocation-performance), with a cache of freed blocks in front.
- It uses more memory than C, because every block carries a 16-byte safety header (the double-free and use-after-free check) and the cache and spare slabs are kept for reuse. On macOS, C's `malloc` is particularly compact (17.5 MB).

::: details Sources
[binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc) · [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c) · [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp) · [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs) · [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig) · [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go) · [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)
:::

#### binary-trees, 8 threads {#binary-trees-mt}

The same workload split across 8 worker threads: each builds and checks an independent slice of the trees at a depth, joined before the next depth. SafeC (arena) gives each thread its own `region`/`arena<R>` (no shared or locked arena state); SafeC (heap) uses `new` / `std::dealloc`.

| Language | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC (arena) | **0.053** s · 98.4 MB | **0.092** s · 95.9 MB | **0.064** s · 93.9 MB |
| SafeC (heap) | 0.229 s · 200.4 MB | 0.333 s · 202.9 MB | 0.160 s · 203.6 MB |
| C | 0.226 s · 161.8 MB | 0.417 s · 161.0 MB | 0.592 s · 69.7 MB |
| C++ | 0.227 s · 163.5 MB | 0.464 s · 168.0 MB | 0.613 s · 71.6 MB |
| Rust | 0.232 s · 161.9 MB | 0.457 s · 164.5 MB | 0.683 s · 78.3 MB |
| Zig | 0.226 s · 163.8 MB | N/A | 0.585 s · 68.3 MB |
| Go | 0.248 s · 96.4 MB | 0.410 s · 139.9 MB | 0.496 s · 141.8 MB |
| Python | 22.96 s · 311.1 MB | 76.85 s · 320.8 MB | 25.36 s · 355.7 MB |

The arena version is 4.3–9.3× faster than C. The heap version matches C on the 7800X3D and beats it on the Xeon and the M1 Pro; its memory is higher because each thread keeps its own cache of freed blocks and spare slabs. Zig 0.17 on the Xeon no longer builds these sources (written for 0.16). Python's threads make the work slower than one thread.

::: details Sources
[binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc) · [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c) · [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp) · [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs) · [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig) · [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go) · [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)
:::

#### SIMD — sum of squares over 20,000,000 doubles {#simd}

A plain scalar loop against each language's explicit vector type, at release optimization: what explicit SIMD buys on top of the auto-vectorizer. SafeC uses `vec<double, 8>` built once per x86-64 tier ([AVX-512 > AVX2 > AVX > SSE4.1 > SSE2](/stdlib/simd#cpu-levels)) and picked at run time; C and C++ use vector extensions; Zig `@Vector`; Rust stable intrinsics (SSE2 on x86-64, NEON on AArch64); Go has no portable vector type. Cells are scalar → explicit vectors, in seconds.

| Language | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | 0.080 → 0.075 | 0.121 → 0.111 | 0.045 → **0.031** |
| C | 0.080 → 0.077 | 0.115 → 0.118 | 0.044 → **0.031** |
| C++ | 0.081 → 0.074 | 0.125 → 0.117 | 0.043 → 0.032 |
| Rust | 0.080 → 0.077 | 0.116 → 0.112 | 0.046 → 0.041 |
| Zig | 0.079 → **0.072** | 0.104 → **0.098** | 0.049 → 0.033 |
| Go | 0.085 | 0.118 | 0.062 |
| Python | 3.117 → 0.186 (NumPy) | 4.080 → 0.333 (NumPy) | 3.626 → 0.152 (NumPy) |

The loop streams 160 MB of doubles, so it is memory-bound: the compiled languages land within about 10 ms of each other on the 7800X3D and the M1 Pro and 20 ms on the Xeon, about the size of process-startup noise. On the 7800X3D the summation alone takes about 3.7 ms at every tier (about 43 GB/s); the rest of each row is the scalar setup loop and page faults.

::: details Sources
[simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc) · [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c) · [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp) · [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs) · [simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs) · [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig) · [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go) · [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)
:::

#### Compile time {#compile-time}

A cold release build of fib(37) (SafeC with `--no-incremental`, so it doesn't hit its bitcode cache). Debug-build times are in the results JSON.

| Language | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | 0.036 s | 0.119 s | 0.160 s |
| C | 0.030 s | 0.099 s | 0.131 s |
| C++ | 0.049 s | 0.127 s | 0.130 s |
| Rust | 0.050 s | 0.162 s | 0.141 s |
| Zig | 8.18 s | 14.48 s | 5.40 s |
| Go | 0.025 s | 0.064 s | 2.29 s |

SafeC compiles about as fast as C and C++. Zig's cold builds take 5–14 s.

### 2.2 Standard library {#standard-library}

#### Collections — 1,000,000 elements {#collections}

The generic containers against a plain C implementation of the same algorithms: a growable array with `qsort`, a linear-probing hash map with the same hash, a `malloc`-per-node doubly linked list, a power-of-two ring queue and an unbalanced BST. Both sides use the same shuffled keys and clang `-O2`. Cells are SafeC / C in ms, and the ratio (below 1 means SafeC is faster).

| Operation | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| `Vec<int>` push | 2.31 / **1.51** (1.53×) | 3.33 / **1.95** (1.71×) | 0.966 / **0.790** (1.22×) |
| `vec_sort` vs `qsort` | **43.5** / 83.4 (0.52×) | **76.3** / 149 (0.51×) | **56.4** / 88.8 (0.64×) |
| `HashMap<int,int>` insert | 37.9 / **34.3** (1.10×) | 56.3 / **53.9** (1.04×) | 31.1 / **28.8** (1.08×) |
| `HashMap<int,int>` get | 8.36 / **7.38** (1.13×) | 20.1 / **18.6** (1.08×) | 16.6 / **12.9** (1.29×) |
| `List<int>` push_back + pop_front | **10.1** / 22.2 (0.45×) | **13.3** / 27.2 (0.49×) | **4.87** / 26.1 (0.19×) |
| `Queue<int>` enqueue + dequeue | **3.02** / 5.54 (0.55×) | **3.30** / 6.64 (0.50×) | **1.66** / 1.94 (0.86×) |
| `BST<int,int>` insert | **176** / 180 (0.98×) | 349 / **312** (1.12×) | 352 / **327** (1.08×) |
| `BST<int,int>` get | 174 / **173** (1.01×) | 351 / **338** (1.04×) | **278** / 397 (0.70×) |

- **Sorting** is 1.6–2.0× faster than `qsort`: the ordering is a type parameter, so every comparison inlines, where `qsort` makes an indirect call per comparison.
- **List**, **Queue** and **BST** nodes come from a per-container node pool, not one allocation per node.
- **HashMap** hashes small keys as one word and compares them with one integer compare, chosen at compile time with `if const`. It is within 1.04–1.13× of C on the x86-64 machines; the rest of the gap is the growth.
- **Vec push** is 1.2–1.7× slower than C: the benchmark loop's bounds-checked `keys.at(i)` and first-touch growth.

::: details Sources
[bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc) · [bench_collections.c](/benchmarks/collections/c/bench_collections.c) · [vec.h](/benchmarks/stdlib/vec.h) · [map.h](/benchmarks/stdlib/map.h) · [mem.sc](/benchmarks/stdlib/mem.sc)
:::

#### Allocator — `std::alloc` / `dealloc` against `malloc` / `free` {#allocator}

`std::alloc` puts a 16-byte header on every block, which carries the double-free and use-after-free check. It serves blocks up to 1 KB from per-thread 64 KB slabs, with a thread-local cache of freed blocks per size class in front, the same idea as PyTorch's and MLX's caching allocators. Every block's address goes into a printed checksum, so the optimizer can't delete an alloc/free pair. Cells are `std::alloc` / `malloc`.

| Pattern | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| Interleaved: one 48-byte block allocated and freed, 20M times (ns per pair) | **3.57** / 6.38 | 10.4 / **8.83** | **12.2** / 27.6 |
| Batch: 1M 24-byte blocks allocated, then freed, 10 rounds (ms) | **87.2** / 96.2 | **120** / 126 | **115** / 233 |

The cache wins whenever freed blocks are reused soon; glibc's `malloc` is faster than `std::alloc` only on the Xeon's interleaved pattern. With a million blocks live at once the slabs keep `std::alloc` 1.05–2.0× ahead with the same safety checks. For bulk short-lived allocation, `region`/`arena<R>` is still the faster tool (4.7× the heap version on binary-trees on the 7800X3D).

::: details Sources
[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc) · [alloc_bench.sc](/benchmarks/memalloc/safec/alloc_bench.sc)
:::

#### Web service — JSON "hello world" {#web-service}

`GET /` returning `{"message":"Hello, World!"}`, TechEmpower's JSON-serialization shape, through Apache Bench (`ab -n 20000 -c 50`, no keep-alive, after a 2,000-request warm-up), against each language's own HTTP story: SafeC's `std::http_serve_reactor` (epoll reactor; kqueue on macOS), a minimal raw-socket accept loop for C, C++ and Zig, Go's `net/http`, Rust's axum (what Dioxus's server layer runs on) and Python's FastAPI + uvicorn. Cells are requests per second (higher is better) · p50/p99 latency · peak memory.

| Language | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | **25,303** · 2/2 ms · 4.9 MB | **33,730** · 1/2 ms · 14.2 MB | **32,925** · 1/3 ms · 6.6 MB |
| C | 24,620 · 2/2 ms · 3.1 MB | 32,196 · 2/2 ms · 14.0 MB | 26,111 · 2/3 ms · 2.1 MB |
| C++ | 24,845 · 2/2 ms · 5.1 MB | 32,523 · 1/2 ms · 15.7 MB | 26,181 · 2/3 ms · 2.2 MB |
| Rust | 25,075 · 2/2 ms · 4.3 MB | 32,592 · 2/2 ms · 13.6 MB | 29,106 · 2/2 ms · 3.5 MB |
| Zig | 25,030 · 2/2 ms · 7.1 MB | N/A | 24,751 · 2/3 ms · 2.3 MB |
| Go | 23,199 · 2/3 ms · 16.1 MB | 32,219 · 2/2 ms · 25.3 MB | 26,424 · 2/3 ms · 19.8 MB |
| Python | 5,096 · 10/14 ms · 46.0 MB | 2,887 · 17/30 ms · 60.8 MB | 4,813 · 10/18 ms · 56.8 MB |

Every server completed every request. On the two Linux machines the compiled servers land within 9% of each other: without keep-alive each request is a new TCP connection, and the kernel's connect/accept path and `ab` are the bottleneck. On the M1 Pro the spread is wider. SafeC's single-threaded kqueue reactor leads at 32,925 req/s, 1.13× Rust's axum and 1.25–1.33× the thread-per-connection C, C++, Zig and Go servers. VS Code's port forwarding holds 127.0.0.1:8082–8087 there, so the servers ran on ports 18082–18088. `run_bench.py` sets this with `WEB_PORT_BASE`, which it passes to each server as `PORT`.

::: details Sources
[server_reactor.sc](/benchmarks/webservice/safec/server_reactor.sc) · [server.c](/benchmarks/webservice/c/server.c) · [server.cpp](/benchmarks/webservice/cpp/server.cpp) · [main.rs](/benchmarks/webservice/rust/src/main.rs) · [server.zig](/benchmarks/webservice/zig/server.zig) · [server.go](/benchmarks/webservice/go/server.go) · [server.py](/benchmarks/webservice/python/server.py) · [http.sc](/benchmarks/stdlib/http.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h)
:::

### 2.3 Signal processing {#signal-processing}

#### DSP {#dsp}

[`std/dsp`](/stdlib/dsp) against SciPy, KFR 7, and for the float biquad the Q and madronalib audio libraries, and dasp for resampling. Each workload runs on 1,048,576 random samples (a 1024×1024 image for `conv2d`), ms per call after one warm-up call, best of 3. "SafeC + oneMKL" links oneMKL's FFT and BLAS (not available on the M1 Pro, where SafeC uses Accelerate's vDSP for the FIR instead).

- `fir stream`: a 127-tap streaming FIR that keeps its state between calls (SafeC `FirFilter::process_block`, SciPy `lfilter`, KFR `fir_filter`); the real-time path, no allocation, no threads.
- `fir block`: the same filter as one convolution (`conv_direct`, `np.convolve`).
- `fftconv`: a 4,096-tap kernel (`conv_fft`, `fftconvolve`, KFR `convolve_filter`).
- `biquad`: one RBJ low-pass section (48 kHz, f0 1 kHz, Q 0.7071), single-threaded as a real-time filter would be.
- `stft`: 1,024-point Hann frames, hop 256.
- `resample`: 44.1 → 48 kHz (SafeC windowed sinc, 16 taps each side; SciPy `resample_poly`; KFR `normal` quality; dasp a 16-tap sinc).
- `conv2d`: a 5×5 Gaussian, "same" size.

Double precision, ms per call:

| Workload | Library | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| fir stream | SafeC | 5.87 | 7.46 | **5.82** |
|  | SafeC + oneMKL | **5.86** | **7.37** | — |
|  | SciPy | 17.4 | 27.5 | 22.4 |
|  | KFR | 32.1 | 42.8 | 44.8 |
| fir block | SafeC | 0.841 | 0.730 | **0.926** |
|  | SafeC + oneMKL | **0.796** | **0.686** | — |
|  | SciPy | 15.4 | 25.1 | 21.4 |
| fftconv | SafeC | **2.98** | 4.54 | **3.06** |
|  | SafeC + oneMKL | 3.19 | **3.82** | — |
|  | SciPy | 32.3 | 60.5 | 22.7 |
|  | KFR | 4.89 | 6.57 | 8.06 |
| biquad | SafeC | **0.484** | **0.782** | **0.666** |
|  | SafeC + oneMKL | 0.493 | **0.782** | — |
|  | SciPy | 4.71 | 6.99 | 7.97 |
|  | KFR | 2.33 | 4.45 | 5.08 |
| stft | SafeC | 1.82 | 5.69 | **3.79** |
|  | SafeC + oneMKL | **1.81** | **5.47** | — |
|  | SciPy | 53.9 | 92.4 | 57.7 |
| resample | SafeC | **3.71** | **7.38** | **4.13** |
|  | SafeC + oneMKL | 3.88 | 8.03 | — |
|  | SciPy | 9.07 | 17.5 | 14.9 |
|  | KFR | 61.7 | 88.4 | 162 |
|  | dasp | 519 | 657 | 291 |
| conv2d | SafeC | **0.366** | **0.601** | **0.589** |
|  | SafeC + oneMKL | 0.390 | 0.715 | — |
|  | SciPy | 35.3 | 53.2 | 36.1 |

Single precision, ms per call:

| Workload | Library | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| fir stream | SafeC | **3.04** | **3.83** | **3.45** |
|  | SafeC + oneMKL | 3.07 | 3.85 | — |
|  | SciPy | 19.5 | 16.0 | 15.9 |
|  | KFR | 27.8 | 37.2 | 63.5 |
| fir block | SafeC | 0.475 | **0.649** | **0.523** |
|  | SafeC + oneMKL | **0.471** | 0.673 | — |
|  | SciPy | 18.9 | 15.7 | 15.1 |
| fftconv | SafeC | 1.29 | 4.23 | **1.97** |
|  | SafeC + oneMKL | **1.16** | **2.21** | — |
|  | SciPy | 14.8 | 32.2 | 16.8 |
|  | KFR | 2.76 | 3.28 | 4.05 |
| biquad | SafeC | **0.482** | **0.773** | **0.661** |
|  | SafeC + oneMKL | 0.525 | 0.785 | — |
|  | SciPy | 4.59 | 6.34 | 7.84 |
|  | KFR | 2.15 | 4.26 | 4.19 |
|  | Q | 1.69 | 2.81 | 2.62 |
|  | madronalib | 2.87 | 5.37 | 4.38 |
| stft | SafeC | 0.986 | 4.88 | **2.20** |
|  | SafeC + oneMKL | **0.983** | **4.14** | — |
|  | SciPy | 55.8 | 95.1 | 56.0 |
| resample | SafeC | **3.96** | 11.1 | **4.77** |
|  | SafeC + oneMKL | 3.98 | **10.8** | — |
|  | SciPy | 8.68 | 16.5 | 16.8 |
|  | KFR | 34.1 | 53.2 | 90.0 |

- A SafeC build is the fastest on every workload on all three machines, in both precisions. The vendor library matters only where an FFT is involved (`fftconv`, `stft`).
- **FIR**: each block runs `dsp_sliding_dot`, 32 outputs per pass (64 in float) with each tap broadcast into four accumulator vectors, so there are no horizontal sums; KFR and SciPy run one dot product per output. On Apple Silicon the sliding dot product goes to Accelerate's `vDSP_conv` (about 47 GFLOPS per core against 34 for SafeC's own NEON kernel).
- **Biquad**: scattered look-ahead (Parhi & Messerschmitt) rewrites the recursion in y[i-2] and y[i-4], so the even and odd outputs are two independent chains in one 2-lane vector: 3.5× faster than Q and 4.5× faster than KFR in float on the 7800X3D. It also beats `vDSP_biquad` on the M1 Pro (0.66 vs 0.93 ms).
- **Resample**: each tap's weight comes from angle-difference formulas over tables built once per call, so the taps vectorize and no output needs a trig call.
- **Float vs double**: float helps where memory traffic or vector width limits the code (FIR, `fftconv`, `stft`); the biquad is latency-bound and the resampler computes its kernel in double. The float resampler on the Xeon is slower than the double one (11.1 vs 7.38 ms); that hasn't been investigated.

::: details Sources
[dsp_cpu.sc](/benchmarks/dsp/safec/dsp_cpu.sc) · [dsp_scipy.py](/benchmarks/dsp/python/dsp_scipy.py) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [audio_libs_bench.cpp](/benchmarks/dsp/cpp/audio_libs_bench.cpp) · [dasp main.rs](/benchmarks/dsp/rust/src/main.rs) · [dsp.sc](/benchmarks/stdlib/dsp.sc) · [filter.sc](/benchmarks/stdlib/filter.sc) · [biquad.sc](/benchmarks/stdlib/biquad.sc) · [convolution.sc](/benchmarks/stdlib/convolution.sc) · [conv_core.h](/benchmarks/stdlib/conv_core.h) · [stft.sc](/benchmarks/stdlib/stft.sc) · [resample_core.h](/benchmarks/stdlib/resample_core.h) · [imaging_core.h](/benchmarks/stdlib/imaging_core.h) · [fft_core.h](/benchmarks/stdlib/fft_core.h)
:::

#### FFT {#fft}

[`fft.h`](/stdlib/dsp) against FFTW 3.3.10 (planned with `FFTW_MEASURE`), oneMKL's DFTI, KFR 7.2, RustFFT 6.4 and PyTorch, on the CPU; and SafeC's [`fft_rocm.h` / `fft_cuda.h`](/stdlib/ml#rocm-miopen-hipfft) (rocFFT, cuFFT) against PyTorch on the GPU. `fft1d` is one 2²⁰-point transform, `fft2d` one 1024×1024 transform, `batch` 1024 transforms of 4096 points, `rfft` 1024 real transforms of 4096 points returning the n/2 + 1 non-redundant bins. "All threads" is 16 on the 7800X3D and 12 on the Xeon. On the M1 Pro it is 8 for SafeC (its pool uses the performance cores) and 10 for FFTW. oneMKL isn't available there, and SafeC has no Metal FFT, so the M1 Pro's GPU column has only PyTorch's `mps` rows. MPS has no complex128, so PyTorch's host-array rows don't run there either.

CPU, double precision (complex128), ms per call:

| Workload | Library | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| fft1d, 2²⁰ | SafeC, all threads | **0.888** | 1.21 | **2.00** |
|  | SafeC + oneMKL DFTI | 0.891 | **1.18** | N/A |
|  | FFTW, all threads | 1.63 | 2.35 | 3.65 |
|  | PyTorch | 8.31 | **1.18** | 16.5 |
|  | SafeC, 1 thread | 5.47 | — | 10.4 |
|  | FFTW, 1 thread | 6.50 | 11.4 | 16.3 |
|  | KFR, 1 thread | 3.36 | 6.35 | 6.85 |
|  | RustFFT, 1 thread | 4.75 | 8.78 | 9.44 |
| fft2d, 1024² | SafeC, all threads | 0.691 | 1.08 | **1.59** |
|  | SafeC + oneMKL DFTI | 1.00 | 0.837 | N/A |
|  | FFTW, all threads | **0.618** | 2.59 | 7.07 |
|  | PyTorch | 0.710 | **0.654** | 12.4 |
|  | SafeC, 1 thread | 4.43 | — | 8.54 |
|  | FFTW, 1 thread | 3.49 | 7.27 | 37.2 |
|  | KFR, 1 thread | 4.22 | 5.15 | 9.23 |
| batch, 1024 × 4096 | SafeC, all threads | 1.25 | 2.96 | **2.66** |
|  | SafeC + oneMKL DFTI | 1.89 | **1.65** | N/A |
|  | FFTW, all threads | **1.05** | 2.95 | 3.20 |
|  | SafeC, 1 thread | 11.9 | — | 15.4 |
|  | FFTW, 1 thread | 7.84 | 10.7 | 16.6 |
|  | KFR, 1 thread | 11.8 | 12.3 | 12.7 |
|  | RustFFT, 1 thread | 8.25 | 14.8 | 17.6 |
| rfft, 1024 × 4096 | SafeC, all threads | 0.861 | 2.31 | **1.45** |
|  | SafeC + oneMKL DFTI | 1.05 | **1.36** | N/A |
|  | FFTW, all threads | **0.553** | 2.02 | 1.81 |
|  | SafeC, 1 thread | 6.17 | — | 8.93 |
|  | FFTW, 1 thread | 4.28 | 7.35 | 8.46 |
|  | KFR, 1 thread | 4.34 | 7.32 | 7.38 |

CPU, single precision (complex64 / float32), ms per call:

| Workload | Library | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| fft1d, 2²⁰ | SafeC, all threads | **0.688** | 3.06 | **1.58** |
|  | SafeC + oneMKL DFTI | 0.693 | 2.85 | N/A |
|  | FFTW, all threads | 1.67 | **2.46** | 2.93 |
|  | SafeC, 1 thread | 4.39 | — | 7.55 |
|  | FFTW, 1 thread | 5.53 | 8.72 | 7.91 |
|  | KFR, 1 thread | 1.77 | 3.31 | 3.29 |
|  | RustFFT, 1 thread | 2.10 | 4.12 | 4.63 |
| fft2d, 1024² | SafeC, all threads | 0.560 | 2.01 | **0.865** |
|  | SafeC + oneMKL DFTI | 0.543 | **1.11** | N/A |
|  | FFTW, all threads | **0.446** | 1.86 | 4.84 |
|  | SafeC, 1 thread | 3.25 | — | 4.43 |
|  | FFTW, 1 thread | 2.54 | 4.29 | 24.7 |
|  | KFR, 1 thread | 2.63 | 3.07 | 3.31 |
| batch, 1024 × 4096 | SafeC, all threads | 0.775 | 3.16 | **1.17** |
|  | SafeC + oneMKL DFTI | 0.964 | **1.60** | N/A |
|  | FFTW, all threads | **0.504** | 1.75 | 2.02 |
|  | PyTorch | 4.41 | 3.19 | 9.02 |
|  | SafeC, 1 thread | 5.76 | — | 8.70 |
|  | FFTW, 1 thread | 3.31 | 6.19 | 10.5 |
|  | KFR, 1 thread | 4.23 | 6.03 | 6.21 |
|  | RustFFT, 1 thread | 4.11 | 7.75 | 10.3 |
| rfft, 1024 × 4096 | SafeC, all threads | 0.558 | 2.36 | **0.805** |
|  | SafeC + oneMKL DFTI | 0.457 | 0.812 | N/A |
|  | FFTW, all threads | **0.409** | 2.08 | 1.06 |
|  | PyTorch | 0.443 | **0.288** | 4.12 |
|  | SafeC, 1 thread | 4.28 | — | 5.24 |
|  | FFTW, 1 thread | 2.98 | 6.50 | 5.31 |
|  | KFR, 1 thread | 2.13 | 3.73 | 3.92 |

GPU, ms per call. The host-array rows start and end in host memory, so each call includes the upload and the read back (complex128); the other two run on data already on the GPU (complex64 / float32):

| Workload | Library | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| fft1d, 2²⁰ (host array) | SafeC | **1.46** | **1.25** | N/A |
|  | PyTorch | 1.56 | 2.05 | N/A |
| fft2d, 1024² (host array) | SafeC | **1.43** | **1.26** | N/A |
|  | PyTorch | 1.53 | 2.04 | N/A |
| batch, 1024 × 4096 (on GPU) | SafeC | **0.100** | 0.0244 | N/A |
|  | PyTorch | 0.171 | **0.0238** | **0.397** (MPS) |
| rfft, 1024 × 4096 (on GPU) | SafeC | **0.0949** | **0.0109** | N/A |
|  | PyTorch | 0.126 | 0.0120 | **0.219** (MPS) |

- **CPU**: SafeC is the fastest at the 2²⁰-point transform on the 7800X3D in both precisions, 1.8× (double) and 2.4× (float) ahead of FFTW; on the Xeon it is level with oneMKL and PyTorch and 1.9× ahead of FFTW. FFTW leads the 2D, batched and real transforms on the 7800X3D (by 1.1–1.6×); on the Xeon oneMKL or PyTorch leads them. The fused radix-8 passes, four complex values per vector and compile-time bit-reversal tables are described in [`std/dsp`](/stdlib/dsp).
- **M1 Pro**: on all threads SafeC is the fastest CPU library on all eight workloads. It is 1.2–4.4× ahead of 10-thread FFTW in double and 1.3–5.6× in float, with the widest gaps on the 1024² 2D transform. At 2²⁰ points it is 8.2× ahead of PyTorch's CPU path. On one thread KFR is faster on seven of the eight (by 1.2–2.3×); SafeC's one-thread win is the double 2D transform (8.54 vs 9.23 ms).
- **Float on the Xeon**: `fft_f32` is slower than the double code there (3.06 vs 1.21 ms at 2²⁰ points), while on the 7800X3D float takes 0.6–0.8× the double time. The cause hasn't been investigated.
- **GPU**: both sides call rocFFT / cuFFT, so device-resident transforms match on the H200; on the MI100 SafeC is 1.3–1.7× faster, where PyTorch's per-call work is a large share of 0.1 ms. For host arrays PCIe dominates; on the H200 SafeC's pinned multi-lane copies make the round trip 1.6× faster than PyTorch's `.to("cuda")` … `.cpu()`.

::: details Sources
[fft_cpu.sc](/benchmarks/fft/safec/fft_cpu.sc) · [fft_rocm.sc](/benchmarks/fft/safec/fft_rocm.sc) · [fft_cuda.sc](/benchmarks/fft/safec/fft_cuda.sc) · [fft.py](/benchmarks/fft/pytorch/fft.py) · [fftw_bench.c](/benchmarks/fft/c/fftw_bench.c) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [RustFFT main.rs](/benchmarks/dsp/rust/src/main.rs) · [fft.h](/benchmarks/stdlib/fft.h) · [fft.sc](/benchmarks/stdlib/fft.sc) · [fft_f32.sc](/benchmarks/stdlib/fft_f32.sc) · [fft_core.h](/benchmarks/stdlib/fft_core.h) · [fft_rocm.sc](/benchmarks/stdlib/fft_rocm.sc) · [fft_cuda.sc](/benchmarks/stdlib/fft_cuda.sc)
:::

### 2.4 Machine learning {#machine-learning}

Every table in this part shows the **best configuration of each framework on each machine**: for SafeC the fastest of its CPU and GPU backends, data types and graph replay, and the same for PyTorch, TensorFlow and MLX. Each cell names the configuration. The full per-configuration results are in the results JSON.

#### MLP training and inference {#ml-mlp}

A 2-layer MLP (`relu(X @ W1) @ W2`, MSE loss, hand-rolled SGD, no bias), in two sizes:

- **Small**: 128→256→64, batch 64, 100 training steps and 1000 inference passes.
- **Big**: 512→1024→256, batch 128, 50 training steps and 200 inference passes.

Every framework reads the loss back to the host every step and `Y[0][0]` every inference pass, as a training loop that logs its loss would. Each gets one untimed warm-up pass. Cells are ms.

Small model:

| Framework | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | train **5.39** (ROCm, fp16, graph)<br>inference 24.3 (CPU, OpenBLAS) | train **2.98** (CUDA, fp16, graph)<br>inference **19.6** (CUDA, TF32, graph) | train 10.5 (CPU, Accelerate)<br>inference **9.11** (CPU, Accelerate) |
| PyTorch | train 14.0 (CPU)<br>inference **21.9** (CPU) | train 23.2 (CPU)<br>inference 43.0 (CPU) | train **8.67** (CPU)<br>inference 13.1 (CPU) |
| TensorFlow | train 79.5 (CPU)<br>inference 204 (CPU) | train 123 (CPU)<br>inference 265 (CPU) | train 75.8 (CPU)<br>inference 230 (CPU) |
| MLX | — | — | train 70.6 (GPU)<br>inference 299 (GPU) |

Big model:

| Framework | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | train **5.90** (ROCm, fp16, graph)<br>inference **9.26** (ROCm, fp16, graph) | train **1.90** (CUDA, fp16, graph)<br>inference **3.99** (CUDA, bf16, graph) | train 47.7 (Metal)<br>inference 48.8 (Metal) |
| PyTorch | train 9.91 (ROCm, fp16)<br>inference 13.4 (ROCm, fp32) | train 15.9 (CUDA, fp32)<br>inference 10.5 (CUDA, fp16) | train **41.2** (MPS)<br>inference **48.6** (CPU) |
| TensorFlow | train 106 (CPU)<br>inference 124 (CPU) | train 229 (CPU)<br>inference 236 (CPU) | train 141 (CPU)<br>inference 185 (CPU) |
| MLX | — | — | train 58.8 (GPU)<br>inference 78.6 (GPU) |

- **GPU training is launch-bound at these sizes.** A step is a few microsecond-scale kernels plus a blocking loss read. One SafeC CUDA step is about 38 µs (one graph launch plus the readback); a PyTorch step is about 510 µs of Python, autograd and dispatcher work around the same cuBLAS calls. That is why PyTorch's best small-model configuration is its CPU path on every machine.
- **SafeC's fastest mode is 16-bit**: data, weights, activations and gradients in fp16/bf16 storage with fp32 accumulation, replayed as one graph per step. On the MI100 the first call of each GEMM shape times every rocBLAS solution and keeps the fastest, which took the fp32 graph step from 8.29 to 7.37 ms.
- **CPU inference** of the small model is where PyTorch stays ahead on the 7800X3D (21.9 vs 24.3 ms). On the M1 Pro, SafeC on Accelerate leads it (9.11 vs 13.1 ms).
- **M1 Pro training**: PyTorch is ahead in both sizes. Its CPU path trains the small model 1.21× faster than SafeC's CPU path (8.67 vs 10.5 ms). Its `mps` path trains the big model 1.16× faster than SafeC's Metal path (41.2 vs 47.7 ms). Big-model inference is level (48.6 ms PyTorch CPU vs 48.8 ms SafeC Metal). MLX and TensorFlow trail both.
- This loop amplifies rounding, so the 16-bit losses differ from fp32 after 50 steps (big model: 95.09 fp16 and 63.43 bf16 against 95.43 fp32 on the MI100). Every SafeC fp32 run agrees to 6 digits across CPU and GPU. The one exception is the M1 Pro's big model, which agrees to 5 (95.42873 on the CPU, 95.42822 on Metal).

::: details Sources
[train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_cuda.sc](/benchmarks/ml/safec/train_cuda.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gemm.sc](/benchmarks/stdlib/gemm.sc)
:::

#### Training: networks, generative models, RL {#ml-training}

The training workloads of SafeC's `std::ml` test suites, run end to end through the library's own training stack: [`nn.h`](/stdlib/ml#training) layers, losses and optimizers, `trainer_fit` with per-step tensor arenas, and the [autoencoder](/stdlib/ml#autoencoders), [GAN](/stdlib/ml#gans), [RL](/stdlib/ml#rl), [LoRA](/stdlib/ml#lora) and [convolution](/stdlib/ml#convnet) modules. SafeC runs each workload on the CPU and, through the same calls with `TrainConfig.device` or `model_to`, on the GPU ([training on a GPU](/stdlib/ml#devices)): parameters, activations, gradients, optimizer state and the dataset stay in GPU memory, and every op is its own kernel launch. PyTorch runs the same models, shapes, optimizers and step counts in fp32. On the GPU it keeps the DQN replay buffer and the PPO advantage recursion on the host, as GPU implementations usually do. Each program gets one untimed warm-up run, and the table shows the best of 7 runs. SafeC's GPU timings include placing the model and uploading the data. Each side draws its own random data of the same shapes, so the final losses are a sanity check rather than a comparison. Cells are ms per workload.

| Workload | Steps | Framework | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|---|
| MLP classifier, 2→32→32→2, batch 32, Adam | 480 | SafeC | **3.64** (CPU, AOCL)<br>76.8 (ROCm) | — | — |
|  |  | PyTorch | 159 (CPU) | — | — |
| MLP classifier, 784→256→256→10, batch 128, Adam | 48 | SafeC | 19.9 (CPU, AOCL)<br>**14.2** (ROCm) | — | — |
|  |  | PyTorch | 31.8 (ROCm) | — | — |
| VAE, 100→128→4, batch 32 | 160 | SafeC | **14.7** (CPU, AOCL)<br>66.8 (ROCm) | — | — |
|  |  | PyTorch | 97 (CPU) | — | — |
| GAN, 2D Gaussian, 32×2 hidden, batch 64 | 160 D + 160 G | SafeC | **8.62** (CPU, AOCL)<br>130 (ROCm) | — | — |
|  |  | PyTorch | 144 (CPU) | — | — |
| DQN, 4→64→64→2, Double DQN, batch 64 | 1000 updates + 1064 actions | SafeC | **51.2** (CPU, AOCL)<br>485 (ROCm) | — | — |
|  |  | PyTorch | 624 (CPU) | — | — |
| PPO, 1024-step rollouts, 4 epochs × 16 minibatches | 5 updates | SafeC | **25.5** (CPU, AOCL)<br>193 (ROCm) | — | — |
|  |  | PyTorch | 268 (CPU) | — | — |
| LoRA, rank 4 on a frozen 2→64→64→2 MLP | 480 | SafeC | **11.2** (CPU, AOCL)<br>143 (ROCm) | — | — |
|  |  | PyTorch | 210 (CPU) | — | — |
| Conv autoencoder, 1×16×16, 2 conv + 2 transposed conv | 80 | SafeC | **30.4** (CPU, oneMKL)<br>32.6 (ROCm) | — | — |
|  |  | PyTorch | 80.3 (ROCm) | — | — |

- **SafeC's best is 2.2–44× faster than PyTorch's best on every workload.** The gap is widest on the small models, where a PyTorch step is mostly per-op dispatch and autograd bookkeeping in Python. It is narrowest on the 784-wide MLP (14.2 vs 31.8 ms) and the conv autoencoder (30.4 vs 80.3 ms), where the arithmetic dominates.
- **On these sizes the CPU is SafeC's faster path, except for the 784-wide MLP** (14.2 ms on the GPU against 19.9 on the CPU); the conv autoencoder is even (32.6 against 30.4). A step of a tiny net is dozens of small kernels, and each launch costs a few microseconds of fixed work that outweighs its arithmetic. Even so, the GPU path beats PyTorch's best on every row, by 1.1–2.5×. [Bigger models](#ml-training-gpu) are where the GPU pays off.
- **PyTorch's best is its CPU path on six of the eight workloads.** Its ROCm path is faster only for the 784-wide MLP and the conv autoencoder.
- **AOCL-BLAS is within 6% of SafeC's built-in GEMM** on the CPU (19.9 vs 21.1 ms on the 784-wide MLP); it or oneMKL is the fastest CPU configuration on every row.
- **Two CPU library changes produced the CPU numbers.**
  - `sgemm` now packs both operands once per call instead of once per tile, which doubled throughput on training-sized products.
  - The optimizer update is one vectorized loop per ISA level, split across the thread pool; Adam on 270K weights went from 952 to 33 µs per step.

  Together they took the 784-wide MLP from 74.3 to 20.7 ms and made the other workloads 1.4× (conv autoencoder) to 2.3× (VAE) faster.

Bigger conv nets and mixed precision are in [Training on the GPU](#ml-training-gpu).

::: details Sources
[train_nn.sc](/benchmarks/ml_nn/safec/train_nn.sc) · [train_nn_gpu.sc](/benchmarks/ml_nn/safec/train_nn_gpu.sc) · [PyTorch train_nn.py](/benchmarks/ml_nn/pytorch/train_nn.py)
:::

#### Training on the GPU: conv nets and mixed precision {#ml-training-gpu}

Bigger models, trained end to end through the same `trainer_fit`, `model_fit` and `Dcgan::train` calls as on the CPU, with `TrainConfig.device` (or `model_to`) set to the GPU ([training on a GPU](/stdlib/ml#devices)). Parameters, activations, gradients, optimizer state and the dataset stay in GPU memory. Convolutions run on MIOpen. SafeC's CPU path (`nn.h` / `convnet.h` autograd, `trainer_fit`) runs the same models for reference. PyTorch runs the same architectures, sizes, optimizers (Adam) and step counts on the same GPU, in fp32 and with automatic mixed precision: `torch.autocast` in fp16 with a `GradScaler`, and in bf16. SafeC's 16-bit columns set the [precision](/stdlib/ml#device-precision) to fp16 (with a dynamic loss scale on the GPU) or bf16: activations and their gradients stored in 16 bits, fp32 parameters, gradients of parameters and optimizer state. Each program gets one untimed warm-up run, and the table shows the best of 7 runs. SafeC's timings include placing the model and uploading the data. Cells are ms per workload, measured on the 7800X3D + MI100 only.

| Workload | Steps | SafeC CPU | SafeC ROCm fp32 | fp16 | bf16 | PyTorch ROCm fp32 | AMP fp16 | AMP bf16 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| MLP 784→1024→1024→10, batch 256 | 64 | 205 | 36.6 | **32.1** | 34.4 | 56.6 | 70.2 | 63.3 |
| CNN + batch norm, 1×28×28, batch 128 | 32 | 864 | 36.3 | **31.1** | 36.0 | 60.7 | 58.3 | 94.0 |
| ResNet (3 residual blocks), 3×32×32, batch 128 | 16 | 2850 | 83.4 | **58.9** | 75.1 | 98.9 | 74.3 | 271 |
| U-Net, 1×32×32, batch 32 | 32 | 2344 | 54.8 | **49.2** | 59.7 | 146 | 154 | 277 |
| DCGAN, 28×28, batch 64 | 32 D + 32 G | 764 | 83.3 | **83.0** | 95.1 | 106 | — | — |
| Conv VAE, 28×28, batch 64 | 32 | 149 | 40.5 | **38.2** | 47.1 | 53.3 | — | — |

- **SafeC's best is 1.3–3.0× faster than PyTorch's best on every workload.** The largest gap is the U-Net (49.2 vs 146 ms); the smallest are the ResNet (58.9 vs 74.3 ms, both fp16) and the DCGAN (83.0 vs 106 ms).
- **fp16 is the faster 16-bit type on the MI100.** MIOpen's fp16 convolution kernels use the matrix cores, which cuts the ResNet from 83.4 to 58.9 ms. Its bf16 convolutions run at about fp32 speed in SafeC (75.1 ms) and much slower in PyTorch (271 ms); on the DCGAN and the VAE, whose transposed convolutions MIOpen has no fast bf16 kernels for, bf16 is slower than fp32. The first run of each convolution shape times it in 16 bits and in fp32 and keeps the faster, which keeps those layers from falling further behind.
- **The GPU path is 3.9–48× faster than SafeC's own CPU path** on these sizes (48× on the ResNet), so for conv nets it is the one to use.
- **What makes 16 bits pay off.** Converting fp32 activations to 16 bits around every convolution cost about as much as the 16-bit kernels saved (the ResNet took 78 ms that way). Storing the activations and their gradients in 16 bits removes those conversions, and 16-bit kernels that move two values per 32-bit load and store reach the bandwidth the halved bytes allow.
- **Losses.** SafeC's fp32, fp16 and bf16 runs reach the same final loss (ResNet 0.131 in all three, CNN 0.081). PyTorch's fp16 ResNet run ended at 2.32 against 0.138 in fp32, so its AMP number there is for a run that did not train. Each side draws its own random data, so losses between frameworks are only a sanity check.
- **First runs.** MIOpen fills a per-user find database the first time it sees a shape and data type. Until then, a new precision can run several times slower, for SafeC and PyTorch alike. The warm-up run absorbs this, and the per-shape timing above, within a program, and these numbers come from a machine where the database was already filled.

**On the H200 (CUDA).** The same programs built with the cuda feature (cuDNN for the convolutions) on the Xeon 8570 + H200, against PyTorch 2.10 (NGC 25.11) on the same GPU, with a shared training job paused for the runs. One run each after the warm-up, ms per workload:

| Workload | SafeC CUDA fp32 | fp16 | bf16 | PyTorch CUDA fp32 | AMP fp16 | AMP bf16 |
|---|---:|---:|---:|---:|---:|---:|
| MLP 784→1024→1024→10, batch 256 | 33.8 | 31.4 | **30.6** | 123 | 202 | 191 |
| CNN + batch norm, 1×28×28, batch 128 | 21.9 | 19.3 | **18.1** | 141 | 196 | 184 |
| ResNet (3 residual blocks), 3×32×32, batch 128 | 50.7 | **32.7** | 32.9 | 136 | 80.2 | 179 |
| U-Net, 1×32×32, batch 32 | 37.0 | 26.0 | **25.3** | 188 | 139 | 261 |
| DCGAN, 28×28, batch 64 | **43.0** | 50.0 | 52.5 | 374 | — | — |
| Conv VAE, 28×28, batch 64 | **22.9** | 24.2 | 24.5 | 213 | — | — |

- **SafeC's best is 2.5–9.3× faster than PyTorch's best** (ResNet 32.7 vs 80.2 ms, Conv VAE 22.9 vs 213 ms). These models take a few milliseconds per step, so PyTorch's per-op Python and dispatch cost weighs more on the H200 than on the MI100. The smaller workloads of [the previous table](#ml-training) run 6.0–15.6× faster than PyTorch through the same device path.
- **Convolutions run on cached cuDNN graph plans**, one per shape and direction (forward, data gradient, filter gradient), each the fastest of the heuristic engine configs timed on first use. cuDNN 9's legacy calls cost about 85 µs of host time per 16-bit convolution on this machine, as much as the kernel, which made 16-bit no faster than fp32 (ResNet 52.8 ms fp16 vs 52.8 fp32); with the graph plans fp16 takes 32.7. fp32 keeps IEEE arithmetic (no TF32) unless `cuda_set_tf32(1)`.
- **16 bits pay off on the conv nets, not on the DCGAN and VAE**, whose transposed convolutions are faster in fp32 here.

::: details Sources
[train_big.sc](/benchmarks/ml_nn/safec/train_big.sc) · [train_big_gpu.sc](/benchmarks/ml_nn/safec/train_big_gpu.sc) · [PyTorch train_big.py](/benchmarks/ml_nn/pytorch/train_big.py)
:::

#### Convolution and U-Net {#ml-convolution}

`conv64` is one Conv2D layer (64→64 channels, 3×3, stride 1, padding 1, 56×56 maps, plus bias); the U-Net is `UNet::new(3, 32, 64, 3)` forward at 128×128 (five convolutions, two max pools, two upsample + concat steps). SafeC runs them through [MIOpen and cuDNN](/stdlib/ml#rocm-miopen-hipfft), choosing each shape's algorithm by timing on the first call, as PyTorch does with `cudnn.benchmark = True`. Tensors stay on the GPU between calls. The GPU wins every row, so the tables show the GPU configurations. SafeC has no Metal convolution, so its M1 Pro cells are its CPU path, which runs one image (N/A for the batched rows). PyTorch's are `mps`. Cells are ms per call.

| Workload | Framework | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| conv64, 1 image | SafeC | **0.0270** (MIOpen) | **0.0181** (cuDNN, fp32) | 0.600 (CPU) |
|  | PyTorch | 0.0628 | 0.0225 (fp32) | **0.0728** (MPS) |
| conv64, 32 images | SafeC | **0.342** | **0.0838** (TF32) | N/A |
|  | PyTorch | 0.346 | 0.0976 (TF32) | **1.43** (MPS) |
| U-Net, 1 image | SafeC | **0.216** (one graph) | **0.0874** (TF32, one graph) | 2.43 (CPU) |
|  | PyTorch | 0.314 | 0.208 (fp32) | **0.664** (MPS) |
| U-Net, 16 images | SafeC | 1.29 | **0.454** (TF32) | N/A |
|  | PyTorch | **1.26** | 0.557 (TF32) | **9.31** (MPS) |

- On the MI100 SafeC is 2.3× faster on one image and 1.45× on the one-image U-Net (as one graph); at batch 16–32 the two are within 3%.
- On the H200 SafeC is faster in every row: it builds each convolution as one fused conv + bias (+ ReLU) graph with cuDNN's graph API, about 6 µs of host time per call against the legacy API's 38 µs. The one-image U-Net as one graph is 2.4× faster than PyTorch's best.
- On the CPU, SafeC's Winograd F(2×2, 3×3) path with AOCL-BLAS runs the U-Net 2.0× faster than PyTorch on the 7800X3D (1.35 vs 2.68 ms) and 1.24× faster with oneMKL on the Xeon (2.65 vs 3.29 ms), though PyTorch's oneDNN is still 1.1–2.0× faster on the single `conv64` layer. On the M1 Pro, SafeC's CPU U-Net is 3.8× faster than PyTorch's CPU path (2.43 vs 9.29 ms), and PyTorch's CPU path is 1.75× faster on `conv64` (0.343 vs 0.600 ms). PyTorch's `mps` path is 4.7× (`conv64`) and 3.7× (U-Net) faster than the best CPU result.

::: details Sources
[conv_rocm.sc](/benchmarks/conv/safec/conv_rocm.sc) · [conv_cuda.sc](/benchmarks/conv/safec/conv_cuda.sc) · [conv_cpu.sc](/benchmarks/conv/safec/conv_cpu.sc) · [conv.py](/benchmarks/conv/pytorch/conv.py) · [cnn.sc](/benchmarks/stdlib/cnn.sc) · [unet.sc](/benchmarks/stdlib/unet.sc) · [gpu_miopen.sc](/benchmarks/stdlib/gpu_miopen.sc) · [gpu_cudnn.sc](/benchmarks/stdlib/gpu_cudnn.sc)
:::

#### Attention {#ml-attention}

Flash attention (`softmax(QKᵀ / √d) V` without forming the score matrix) and linear attention (φ(x) = elu(x) + 1) from SafeC's GPU backends ([ROCm](/stdlib/ml#rocm-attention), [CUDA](/stdlib/ml#cuda-attention), [Metal](/stdlib/ml#mps-flash-linear-attention)). The competitors are PyTorch's `scaled_dot_product_attention`, MLX's fused `mx.fast.scaled_dot_product_attention`, and, for linear attention, a vectorized PyTorch / MLX implementation and flash-linear-attention's Triton kernel. Batch 4, 16 heads, data on the GPU. The table takes two workloads, flash attention at head dim 128 / 4096 tokens / causal and linear attention at head dim 64 / 16,384 tokens / causal. For each, it shows each framework's fastest data type and the fp32 result. Cells are ms per call, with TFLOPS for flash attention (4·B·H·S²·D, halved for causal).

| Workload | Framework | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| Flash, head dim 128, 4096 tokens, causal, fastest data type | SafeC | **12.6** (fp32, 21.9 TFLOPS) | 0.466 (bf16, 589 TFLOPS) | 89.8 (fp16, 2.97 TFLOPS) |
|  | PyTorch | 66.4 (fp32, 4.1 TFLOPS) | **0.463** (bf16, 591 TFLOPS) | 84.4 (fp16, 3.23 TFLOPS) |
|  | MLX | — | — | **75.1** (fp16, 3.66 TFLOPS) |
| Flash, the same shape in fp32 | SafeC | **12.6** (21.9 TFLOPS) | **6.07** (45 TFLOPS) | **97.8** (2.81 TFLOPS) |
|  | PyTorch | 66.4 (4.1 TFLOPS) | 6.19 (44 TFLOPS) | 163 (1.65 TFLOPS) |
|  | MLX | — | — | 125 (1.93 TFLOPS) |
| Linear, head dim 64, 16,384 tokens, causal, fastest data type | SafeC | **2.76** (fp32) | **1.44** (fp32) | **26.6** (bf16) |
|  | PyTorch | 14.6 (fp32) | 3.32 (fp32 as TF32, NGC default) | 113 (fp32) |
|  | flash-linear-attention | — | 2.38 (bf16) | — |
|  | MLX | — | — | 81.0 (fp32) |

- **H200**: fp16/bf16 flash attention goes through cuDNN's fused engine, the kernels PyTorch runs too, so the two are within 3% (up to 690 TFLOPS). SafeC's fp32 flash attention runs on tensor cores in 3xTF32 (errors ~2e-7), level with PyTorch at head dim 128 and 1.34× faster at 64.
- **MI100**: SafeC's fp32 kernels run on the matrix cores (MFMA) at 18–26 TFLOPS, where PyTorch has no fused kernel on gfx908 and forms the whole score matrix, which also makes masking slower rather than faster.
- **M1 Pro**: MLX is 1.2× faster in fp16 at head dim 128. In fp32 SafeC is the fastest of the three (1.3–1.7× here); the M1 family has no bf16 matrix units, so SafeC converts bf16 tiles to fp32 as it stages them.
- **Linear attention** is 2.3–5.3× faster than PyTorch everywhere: chunked states φ(K)ᵀV with a scan over chunks (a single pass with a decoupled look-back on the MI100), in exact fp32.

::: details Sources
[attn_rocm.sc](/benchmarks/attention/safec/attn_rocm.sc) · [attn_cuda.sc](/benchmarks/attention/safec/attn_cuda.sc) · [attn_mps.sc](/benchmarks/attention/safec/attn_mps.sc) · [PyTorch attn.py](/benchmarks/attention/pytorch/attn.py) · [MLX attn.py](/benchmarks/attention/mlx/attn.py) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_cuda_attention.sc](/benchmarks/stdlib/gpu_cuda_attention.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal)
:::

#### LLM inference: Gemma 4 E2B {#ml-llm}

The real `google/gemma-4-E2B` checkpoint (10.25 GB of bf16) run from scratch: Per-Layer Embeddings, cross-layer KV sharing, sliding and full attention with two RoPE configurations, logit softcapping. There is no external inference engine in SafeC's path (no llama.cpp, ONNX Runtime or vLLM). It is a standalone project ([`benchmarks/gemma4/`](/benchmarks/gemma4/DESIGN_NOTES.md)) with one GPU engine per machine. Every engine keeps all weights on the GPU, reads them with a bf16 GEMV per projection, and records one decode step as a GPU graph that is replayed per token. The measurement is single-request latency: prompt `"The capital of France is"`, greedy decoding, ms per generated token. Every path produces the same text (`" Paris.\n\nThe capital of France is Paris. …"`).

| Framework | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | **6.44** (ROCm engine, HIP graph) | **2.91** (CUDA engine, CUDA graph) | 127 (Metal, bf16, speculative decoding) |
| PyTorch + transformers | 20.2 (`torch.compile`) | 4.47 (static KV cache) | 85 (`mps`) |
| MLX (mlx-lm) | — | — | **48** |

- **Correctness**: on the MI100 the top-5 next-token logits match `transformers` fp32 to 1e-4 on four prompts, and 900 greedy tokens (past the 512-token sliding window) match token for token. On the H200 all 64 greedy tokens match `transformers` bf16.
- **Where the time goes**: a decode step reads about 4.6 GB of weights. On the H200 the engine streams the LM head at ~4.4 TB/s and overlaps kernels with programmatic dependent launch; on the MI100 the GEMVs reach 0.85–1.07 TB/s and take 5.2 of the 6.44 ms.
- **M1 Pro**: MLX is faster: `mlx_lm` builds a whole decode step as one lazy graph, close to one command buffer per token, and SafeC's Metal path still makes about 70–84 GPU round trips per token. SafeC's row also uses prompt-lookup speculative decoding, where the other two decode one token per step.
- **CPU only**: SafeC decodes through [`std::half_gemv`](/stdlib/ml#half-precision-tensors), reading the bf16 weights as stored at memory bandwidth: 225 ms/token on the 7800X3D (PyTorch 211) and 136 ms/token on the M1 Pro (PyTorch 285).

::: details Sources
[DESIGN_NOTES.md](/benchmarks/gemma4/DESIGN_NOTES.md) · [gemma4_rocm.sc](/benchmarks/gemma4/src/gemma4_rocm.sc) · [gemma4_rocm_kernels.hip](/benchmarks/gemma4/src/gemma4_rocm_kernels.hip) · [gemma4_cuda.sc](/benchmarks/gemma4/src/gemma4_cuda.sc) · [gemma4_cuda.cuh](/benchmarks/gemma4/src/gemma4_cuda.cuh) · [gemma4_model_bf16.sc](/benchmarks/gemma4/src/gemma4_model_bf16.sc) · [gemma4_bf16.sc](/benchmarks/gemma4/src/gemma4_bf16.sc) · [gemma4_generate.sc](/benchmarks/gemma4/src/gemma4_generate.sc) · [bench_rocm.sc](/benchmarks/gemma4/tests/bench_rocm.sc) · [bench_safec.sc](/benchmarks/gemma4/tests/bench_safec.sc) · [bench_pytorch.py](/benchmarks/gemma4/tests/bench_pytorch.py) · [bench_pytorch_compiled.py](/benchmarks/gemma4/tests/bench_pytorch_compiled.py) · [bench_pytorch_cuda.py](/benchmarks/gemma4/tests/bench_pytorch_cuda.py) · [bench_mlx.py](/benchmarks/gemma4/tests/bench_mlx.py) · [ref_transformers.py](/benchmarks/gemma4/tests/ref_transformers.py)
:::

## 3. Summary {#summary}

| Test | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| fib, n-body, SIMD (SafeC vs C) | 1.00–1.03× | 0.97–1.07× | 0.94–1.01× |
| binary-trees, arena (vs C) | 6.1× faster | 4.2× faster | 7.0× faster |
| binary-trees, 8 threads, arena (vs C) | 4.3× faster | 4.5× faster | 9.3× faster |
| Web service | fastest, 25,303 req/s | fastest, 33,730 req/s | fastest, 32,925 req/s |
| DSP (7 workloads, 2 precisions) | fastest on every row | fastest on every row | fastest on every row |
| CPU FFT, 2²⁰ points | fastest | level with oneMKL / PyTorch | fastest (1.8× FFTW) |
| MLP training, best vs best (small / big) | 2.6× / 1.7× faster than PyTorch | 7.8× / 8.4× faster | PyTorch 1.2× / 1.2× faster |
| Training stack (8 workloads), best vs best | 5.2–44× faster than PyTorch | — | — |
| GPU training: ResNet, U-Net, CNN, MLP, DCGAN, conv VAE | 1.3–3.3× faster than PyTorch (fp32 / AMP) | — | — |
| Flash attention, fastest data type | 5.3× faster than PyTorch | level with PyTorch (cuDNN) | MLX 1.2× faster |
| Linear attention | 5.3× faster than PyTorch | 2.3× faster | 3.0× faster than MLX |
| Gemma 4 E2B decode | 3.1× faster than PyTorch | 1.5× faster | MLX 2.6× faster |

- **The language**: compute-bound SafeC runs within a few percent of C, so its safety checks cost little in these tests; `consteval` tables and per-tier SIMD dispatch add no runtime cost.
- **Memory**: regions are the biggest single win; the heap allocator with its slabs is ahead of glibc's `malloc` in most patterns, while paying 16 bytes per block for its safety header.
- **The standard library**: the containers and DSP code win where compile-time specialization removes indirection (sorting, filters); collection growth is the remaining gap.
- **Machine learning**: on the AMD and NVIDIA machines, SafeC's backends win most best-versus-best comparisons with PyTorch. The exceptions are fp16/bf16 flash attention on the H200 (level, both run cuDNN), the 16-image U-Net on the MI100 (2% behind) and small-model CPU inference on the 7800X3D (1.1× behind). On Apple Silicon, MLX leads in LLM decoding and half-precision flash attention, PyTorch trains both MLPs 1.2× faster, and PyTorch's `mps` path leads the convolutions, where SafeC has only its CPU path.
