# Benchmarks

How SafeC compares to C, C++, Rust, Zig, Go, and Python on wall-clock time and peak memory, across a few classic microbenchmarks — in the spirit of [The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html) and [programming-language-benchmarks.vercel.app](https://programming-language-benchmarks.vercel.app), scaled down to fit one CI-sized machine. **Fastest** and **leanest** cells are bolded per benchmark.

::: warning Read this before the numbers below
Single-machine, single-session, best-of-3 — not the Benchmarks Game's more rigorous multi-run methodology. Treat every number as "roughly this, on this machine, this day," not a universal truth about the language. Three benchmarks characterize *these workloads*, not overall language performance.
:::

## Methodology {#methodology}

- **Primary machine**: Apple M1 Pro, 10 cores, 32 GB RAM, macOS 26.5.1 — `safec` 1.0.0 · Apple Clang 21.0.0 (C/C++) · Go 1.26.5 · Zig 0.16.0 · Rust 1.97.1 · Python 3.14.6 (+ NumPy 2.5.1 for SIMD).
- **Secondary machines** (fib/n-body/binary-trees/multithreading/SIMD/web service, release build only): same AMD Ryzen 7 7800X3D box (no GPU), once under **WSL2 Ubuntu 22.04** (clang 19.1.7, same Go/Zig/Rust versions) and once natively on **Windows 11** (clang/LLVM ~19, MSVC-targeting). Different CPU architecture from the Mac, so treat cross-machine deltas as directional, not controlled.
- **Build flags** — debug: `-O0` (C/C++), unoptimized IR (SafeC), `go build -gcflags="all=-N -l"`, Zig Debug, plain `rustc`. Release: `-O2` (C/C++, and SafeC's `clang -O2` backend — matching `safeguard build --release`), plain `go build`, `zig build-exe -O ReleaseFast`, `rustc -O`. Python has no debug/release distinction.
- **Timing**: best (lowest) wall-clock of 3 runs — `/usr/bin/time -l` on macOS/WSL2, `System.Diagnostics.Stopwatch` on Windows (no `/usr/bin/time` equivalent there). **Memory**: peak RSS of the same runs, macOS/WSL2 only — Windows' `Process.PeakWorkingSet64` consistently read 0 for these short-lived processes even after a refresh (an unresolved API quirk), so Windows memory is omitted throughout rather than shown unreliably.
- **Correctness**: every binary's output is checked against the known-correct value before being included.
- Every source file is linked inline next to its result.

## fib(37) — recursive Fibonacci {#fib37-recursive-fibonacci}

Naive recursive Fibonacci — pure function-call and integer-arithmetic overhead, no allocation, no I/O.

| Language | Debug | Release | Release peak memory | Release compile time |
|---|---|---|---|---|
| SafeC | 0.14s | 0.08s | **1.3 MB (leanest)** | 0.13s |
| C | 0.13s | **0.07s (fastest)** | **1.3 MB (leanest)** | 0.09s |
| C++ | 0.13s | **0.07s (fastest)** | **1.3 MB (leanest)** | 0.09s |
| Rust | 0.18s | 0.09s | 1.5 MB | 0.18s |
| Zig | 0.16s | 0.08s | 1.4 MB | 5.60s |
| Go | **0.11s (fastest)** | 0.08s | 4.0 MB | 0.08s |
| Python | 2.70s | 2.70s | 14.5 MB | N/A (interpreted) |

**Release, across platforms:**

| Language | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.080s | **0.040s (fastest)** | 0.046s |
| C | 0.070s | **0.040s (fastest)** | 0.046s |
| C++ | 0.070s | **0.040s (fastest)** | 0.047s |
| Rust | 0.090s | **0.040s (fastest)** | 0.049s |
| Zig | 0.080s | **0.040s (fastest)** | 0.053s |
| Go | 0.080s | 0.080s | 0.092s |
| Python | 2.700s | 3.330s | 2.485s |

**Sources:** [fib.sc](/benchmarks/fib/safec/fib.sc) · [fib.c](/benchmarks/fib/c/fib.c) · [fib.cpp](/benchmarks/fib/cpp/fib.cpp) · [fib.rs](/benchmarks/fib/rust/fib.rs) · [fib.zig](/benchmarks/fib/zig/fib.zig) · [fib.go](/benchmarks/fib/go/fib.go) · [fib.py](/benchmarks/fib/python/fib.py)

## n-body — 5-body orbital simulation {#n-body-5-body-orbital-simulation}

The classic Benchmarks Game n-body test (Sun/Jupiter/Saturn/Uranus/Neptune), 2,000,000 steps. Floating-point heavy, no allocation, tiny working set.

| Language | Debug | Release | Release peak memory | Release compile time |
|---|---|---|---|---|
| SafeC | 0.63s | 0.11s | **1.3 MB (leanest)** | 0.13s |
| C | 0.42s | **0.10s (fastest)** | 1.3 MB | 0.10s |
| C++ | 0.41s | 0.11s | **1.3 MB (leanest)** | 0.12s |
| Rust | 0.70s | 0.11s | 1.5 MB | 0.16s |
| Zig | 0.56s | 0.11s | 1.5 MB | 5.69s |
| Go | **0.28s (fastest)** | **0.10s (fastest)** | 4.1 MB | 0.09s |
| Python | 9.89s | 9.89s | 15.2 MB | N/A (interpreted) |

**Release, across platforms:**

| Language | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.110s | **0.090s (fastest)** | 0.102s |
| C | 0.100s | **0.090s (fastest)** | 0.103s |
| C++ | 0.110s | **0.090s (fastest)** | 0.104s |
| Rust | 0.110s | 0.100s | 0.113s |
| Zig | 0.110s | 0.110s | 0.116s |
| Go | 0.100s | 0.100s | 0.113s |
| Python | 9.890s | 12.150s | 11.922s |

**Sources:** [nbody.sc](/benchmarks/nbody/safec/nbody.sc) · [nbody.c](/benchmarks/nbody/c/nbody.c) · [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp) · [nbody.rs](/benchmarks/nbody/rust/nbody.rs) · [nbody.zig](/benchmarks/nbody/zig/nbody.zig) · [nbody.go](/benchmarks/nbody/go/nbody.go) · [nbody.py](/benchmarks/nbody/python/nbody.py)

## binary-trees — allocation/deallocation stress {#binary-trees-allocationdeallocation-stress}

Builds and discards millions of small binary trees (max depth 18) — exercises memory management rather than arithmetic. SafeC uses `region`/`arena<R>` (bump-pointer allocation, `arena_reset<R>()` discards a whole region in O(1)) instead of `std::alloc`/heap — this workload is all short-lived, same-scope allocations, exactly what regions are for.

| Language | Debug | Release | Release peak memory | Release compile time |
|---|---|---|---|---|
| SafeC | **0.29s (fastest)** | **0.21s (fastest)** | 25.4 MB | 0.14s |
| C | 1.67s | 1.56s | **17.4 MB (leanest)** | 0.10s |
| C++ | 1.87s | 1.86s | **17.4 MB (leanest)** | 0.10s |
| Rust | 3.16s | 1.74s | 17.6 MB | 0.16s |
| Zig | 4.85s | 1.57s | 17.6 MB | 5.28s |
| Go | 2.52s | 1.26s | 40.8 MB | 0.09s |
| Python | 20.93s | 20.93s | 87.0 MB | N/A (interpreted) |

SafeC's plain-heap variant (`std::alloc` instead of arena) for reference: 1.15s release, 33.6 MB peak — arena is ~5.5x faster and ~23% leaner here.

**Release, across platforms** (SafeC uses arena on all three, for a fair comparison):

| Language | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC (arena) | 0.210s | **0.130s (fastest)** | 0.147s |
| C | 1.560s | 0.820s | 1.965s |
| C++ | 1.860s | 0.930s | 2.025s |
| Rust | 1.740s | 0.900s | 2.184s |
| Zig | 1.570s | 0.880s | 2.020s |
| Go | 1.260s | 1.150s | 1.125s |
| Python | 20.930s | 20.130s | 10.019s |

SafeC plain-heap variant, same three platforms: macOS 1.150s, WSL2 **0.620s (fastest)**, Windows 1.119s.

**Sources:** [binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc) · [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c) · [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp) · [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs) · [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig) · [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go) · [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)

## Collections — std::collections throughput (1,000,000 elements) {#collections-stdcollections-throughput-1000000-elements}

| Operation | Throughput |
|---|---|
| `bst_insert` | 1,577,110/sec |
| `list_push_back` | 65,427,899/sec |
| `map_insert` | 4,365,783/sec |
| `map_get` | 8,468,404/sec |

[bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc)

## Multithreaded — binary-trees, 8 threads {#multithreaded-binary-trees-8-threads}

Same binary-trees workload, parallelized across 8 worker threads — each thread builds/checksums an independent slice of the tree count at a given depth, joined before the next depth. Release only. SafeC: one `region`/`arena<R>` per thread (arena state isn't shared/locked) — measured against plain heap: 0.68s → 0.09s (~7.5x) on macOS.

| Language | macOS 8-thread time | Peak memory | vs. single-thread |
|---|---|---|---|
| SafeC | **0.09s (fastest)** | 89.7 MB | 2.33× |
| C | 0.61s | 72.3 MB | 2.56× |
| C++ | 0.63s | 72.5 MB | 2.95× |
| Rust | 0.68s | **72.2 MB (leanest)** | 2.56× |
| Zig | 0.54s | 74.1 MB | 2.91× |
| Go | 0.44s | 144.2 MB | 2.86× |
| Python | 24.43s | 372.0 MB | 0.86× (slower than 1 thread) |

**8-thread time, across platforms** (SafeC uses arena on all three):

| Language | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC (arena) | **0.090s (fastest)** | 0.154s | 0.112s |
| C | 0.610s | 0.274s | 0.549s |
| C++ | 0.630s | 0.290s | 0.566s |
| Rust | 0.680s | 0.319s | 0.591s |
| Zig | 0.540s | 0.280s | 0.563s |
| Go | 0.440s | 0.358s | 0.320s |
| Python | 24.430s | 26.626s | 10.055s |

SafeC plain-heap variant: macOS 0.680s, WSL2 **0.308s (fastest)**, Windows 0.367s.

**Sources:** [binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc) · [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c) · [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp) · [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs) · [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig) · [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go) · [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)

## SIMD — sum of squares over 20,000,000 f64 values {#simd-sum-of-squares-over-20000000-f64-values}

Plain scalar loop vs. each language's explicit vector type at `-O2`/release — isolates what explicit SIMD buys *on top of* the backend's auto-vectorizer, not "vectorized vs. deliberately crippled." SafeC: native `vec<double,4>` (lowers to LLVM's target-generic `FixedVectorType`, no per-architecture source needed). C/C++: GCC/Clang vector extensions. Zig: `@Vector`. Rust: stable-channel AArch64 NEON intrinsics on macOS (`std::simd` needs nightly) — the WSL2/Windows columns below use a separate x86_64/SSE2 source ([simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs)) written for this comparison. Go has no portable SIMD type, scalar only.

| Language | macOS scalar | macOS explicit SIMD | Speedup |
|---|---|---|---|
| SafeC | 0.04s | **0.03s (fastest)** | 1.33× |
| C | 0.04s | **0.03s (fastest)** | 1.33× |
| C++ | 0.04s | **0.03s (fastest)** | 1.33× |
| Rust | 0.04s | **0.03s (fastest)** | 1.33× |
| Zig | 0.04s | **0.03s (fastest)** | 1.33× |
| Go | 0.06s | N/A | — |
| Python | 3.50s | N/A (0.14s with NumPy, 25.00×) | — |

**Across platforms** — Apple Silicon wins outright on every language here, by a wide margin; treat that as a real result of this specific memory-bound microbenchmark on this hardware, not a general "M1 beats Ryzen" claim. Absolute times (tens of ms) are small enough that process-startup noise matters proportionally more than elsewhere.

| Language | Scalar: macOS / WSL2 / Windows | Explicit SIMD: macOS / WSL2 / Windows |
|---|---|---|
| SafeC | **0.040s (fastest)** / 0.099s / 0.067s | **0.030s (fastest)** / 0.092s / 0.060s |
| C | **0.040s (fastest)** / 0.099s / 0.077s | **0.030s (fastest)** / 0.092s / 0.060s |
| C++ | **0.040s (fastest)** / 0.101s / 0.073s | **0.030s (fastest)** / 0.092s / 0.073s |
| Rust | **0.040s (fastest)** / 0.099s / 0.044s | **0.030s (fastest)** / 0.093s / 0.039s |
| Zig | **0.040s (fastest)** / 0.096s / 0.065s | **0.030s (fastest)** / 0.089s / 0.069s |
| Go | 0.060s / 0.105s / 0.074s | N/A |
| Python | 3.500s / 3.144s / 3.406s | 0.140s / 0.213s / 0.313s (NumPy) |

**Sources:** [simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc) · [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c) · [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp) · [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs) (macOS/NEON) · [simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs) (WSL2/Windows/SSE2) · [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig) · [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go) · [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)

## Web service — JSON "hello world" endpoint {#web-service-json-hello-world-endpoint}

`GET /` returning `{"message":"Hello, World!"}` — the same shape as TechEmpower's "JSON serialization" test, via Apache Bench (`ab -n 20000 -c 50`, no keep-alive) on macOS/WSL2 against each language's own HTTP story: SafeC's `std::http_serve_reactor`; a minimal raw-socket accept loop for C/C++/Zig (macOS only — not ported to Winsock); Go's `net/http`; Python's FastAPI+uvicorn; Rust's **axum** (not Dioxus — Dioxus's fullstack server layer *is* axum underneath, so this measures what it actually runs through). Windows has no `ab`, so SafeC/Go/Rust/Python there use a small custom Go load generator at `-n 5000 -c 20` instead (smaller scale — rapid same-port restarts hit Windows' long `TIME_WAIT`); treat the Windows column as directional relative to itself, not directly comparable to `ab`'s numbers.

| Language | macOS req/s (p50/p99) | WSL2 req/s (p50/p99) | Windows req/s (p50/p99) | macOS peak memory |
|---|---|---|---|---|
| SafeC | **35832 (fastest)** (1/5ms) | 17497 (3/4ms) | 2043 (9.1/21.2ms) | 7.2 MB |
| C | 25303 (2/2ms) | N/A | N/A | **1.4 MB (leanest)** |
| C++ | 24148 (2/4ms) | N/A | N/A | 1.6 MB |
| Rust | 27270 (2/2ms) | 18077 (3/3ms) | 465 (33.0/474.3ms) | 3.4 MB |
| Zig | 25742 (2/2ms) | N/A | N/A | 1.5 MB |
| Go | 25274 (2/3ms) | **18758 (fastest)** (3/4ms) | **2467 (fastest)** (7.8/16.9ms) | 19.7 MB |
| Python | 4790 (10/33ms) | 2357 (21/27ms) | 528 (37.3/52.5ms) | 54.9 MB |

Every language completed every request with zero failures on every platform once measured in isolation.

**Sources:** [server.sc](/benchmarks/webservice/safec/server.sc) · [server_reactor.sc](/benchmarks/webservice/safec/server_reactor.sc) · [server.c](/benchmarks/webservice/c/server.c) · [server.cpp](/benchmarks/webservice/cpp/server.cpp) · [main.rs](/benchmarks/webservice/rust/src/main.rs) · [server.zig](/benchmarks/webservice/zig/server.zig) · [server.go](/benchmarks/webservice/go/server.go) · [server.py](/benchmarks/webservice/python/server.py) · [io_nb_bsd.sc](/benchmarks/stdlib/io_nb_bsd.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h)

## Machine learning — small MLP, training and inference {#machine-learning-small-mlp-training-and-inference}

2-layer MLP (`relu(X @ W1) @ W2`, 128→256→64, batch 64, MSE loss, hand-rolled SGD, no bias) — 100 training steps then 1000 inference passes, fixed seed. `std::ml` covers CPU (Accelerate BLAS) and MPS (this shape is too small for GPU to beat CPU BLAS anywhere, SafeC included — the MPS row is for completeness, not competition).

| Framework | Device | Train (100 steps) | Throughput | Inference (1000 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC (Accelerate BLAS) | CPU | **16.8ms (fastest)** | 380499 samples/s | 26.1ms | 100.333420 |
| SafeC | MPS | 39.4ms | 162581 samples/s | 61.1ms | 100.333290 |
| PyTorch | CPU | 23.1ms | 277232 samples/s | **13.5ms (fastest)** | 100.018272 |
| PyTorch | MPS | 131.6ms | 48649 samples/s | 358.7ms | 103.423889 |
| TensorFlow | CPU | 83.0ms | 77103 samples/s | 245.0ms | 106.182991 |
| TensorFlow | GPU | 245.3ms | 26086 samples/s | 924.6ms | 106.182999 |
| MLX | GPU | 72.2ms | 88685 samples/s | 309.1ms | 96.632431 |

[tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [tensor_gpu.h](/benchmarks/stdlib/tensor_gpu.h) · [tensor_gpu.sc](/benchmarks/stdlib/tensor_gpu.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [gen_mps_metallib.sh](/benchmarks/stdlib/gen_mps_metallib.sh) · [time.sc](/benchmarks/stdlib/time.sc) · [time.h](/benchmarks/stdlib/time.h) · [train.sc](/benchmarks/ml/safec/train.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py)

## Machine learning, bigger model — 512→1024→256 {#machine-learning-bigger-model-512-1024-256}

Same shape, scaled ~50x (batch 128). 50 training steps, 200 inference passes.

| Framework | Device | Train (50 steps) | Throughput | Inference (200 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC (Accelerate BLAS) | CPU | 89.2ms | 71713 samples/s | 61.4ms | 95.428856 |
| SafeC | MPS | **47.9ms (fastest)** | 133433 samples/s | **47.3ms (fastest)** | 95.428223 |
| PyTorch | CPU | 67.8ms | 94440 samples/s | 48.2ms | 106.548233 |
| PyTorch | MPS | 110.5ms | 57926 samples/s | 86.4ms | 108.710899 |
| TensorFlow | CPU | 148.8ms | 43003 samples/s | 197.6ms | 316.556885 |
| TensorFlow | GPU | 154.1ms | 41539 samples/s | 218.3ms | 317.200012 |
| MLX | GPU | 54.3ms | 117953 samples/s | 74.4ms | 88.762100 |

[gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [tensor_gpu.h](/benchmarks/stdlib/tensor_gpu.h) · [tensor_gpu.sc](/benchmarks/stdlib/tensor_gpu.sc) · [train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_cpu_blas.sc](/benchmarks/ml_big/safec/train_cpu_blas.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc) · [PyTorch train.py](/benchmarks/ml_big/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml_big/tensorflow/train.py) · [MLX train.py](/benchmarks/ml_big/mlx/train.py)

## Machine learning, GPU backends — CUDA, ROCm, Vulkan/SPIR-V, WebGPU {#machine-learning-gpu-backends-cuda-rocm-vulkanspir-v-webgpu}

This machine has no NVIDIA/AMD GPU, CUDA/ROCm toolkit, Vulkan SDK, or WebGPU library — **nothing here is measured**. Every function is hand-written against the real vendor C ABI and type-checks under `safec`, but is unlinkable/unrunnable here — "should be right," not "confirmed right." Each backend hits a different wall:

| Backend | Elementwise ops | Matmul (naive) | Matmul (vendor BLAS) | Gap |
|---|---|---|---|---|
| CUDA | real (PTX, text IR) | real (PTX) | real (cuBLAS) | no NVIDIA GPU here |
| ROCm | always returns 0 | always returns 0 | real (rocBLAS) | HSACO is binary IR; no ROCm toolchain to compile one |
| Vulkan/SPIR-V | always returns 0 | always returns 0 | n/a | SPIR-V is binary IR; no glslc/glslangValidator here |
| WebGPU | real (WGSL, text IR) | real (WGSL) | n/a | no wgpu-native/Dawn library here |

[gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_spirv.h](/benchmarks/stdlib/gpu_spirv.h) · [gpu_spirv.sc](/benchmarks/stdlib/gpu_spirv.sc) · [gpu_webgpu.h](/benchmarks/stdlib/gpu_webgpu.h) · [gpu_webgpu.sc](/benchmarks/stdlib/gpu_webgpu.sc) · [tensor_cuda.h](/benchmarks/stdlib/tensor_cuda.h) · [tensor_cuda.sc](/benchmarks/stdlib/tensor_cuda.sc) · [tensor_rocm.h](/benchmarks/stdlib/tensor_rocm.h) · [tensor_rocm.sc](/benchmarks/stdlib/tensor_rocm.sc)

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

Every backend names its op explicitly (`tensor_matmul` vs `_blas` vs `_gpu` vs `_cuda`) — precise, but awkward for a caller that wants to pick a device once. `tensor_matmul_on(a, b, device)` / `tensor_relu_on(a, device)` dispatch over a `Device` enum instead — verified bit-identical across CPU/CPU+BLAS/MPS:

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

`std::alloc` is a size-class caching allocator (same idea as PyTorch's CPU/CUDA caching allocators and MLX's Metal buffer cache): a freed block goes into a thread-local free list bucketed by power-of-two size class, and the next same-class `alloc()` is satisfied straight from there, skipping `malloc()`/`free()` entirely. Double-free/UAF detection is unaffected (a cached block still carries its "freed" magic word until reused).

`std::alloc`/`dealloc` runs *faster* than raw `malloc`/`free` on a binarytrees-shaped workload of many small, same-size, short-lived allocations (1210ms vs 1518ms, ~20% faster) and ~3.3x faster on an interleaved alloc/free microbenchmark (11–12ns/call vs 37–38ns/call). `region`/`arena<R>` is faster still (~5.9x over heap).

[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc)
