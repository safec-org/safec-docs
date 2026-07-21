# Benchmarks

How SafeC compares to C, C++, Rust, Zig, Go, and Python on wall-clock time and peak memory, across a few classic microbenchmarks — in the spirit of [The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html) and [programming-language-benchmarks.vercel.app](https://programming-language-benchmarks.vercel.app), scaled down to what fits in one CI-sized machine. **Fastest** and **leanest** cells are bolded per benchmark.

::: warning Read this before the numbers below
This is a **single machine, single session, best-of-3** measurement — not the Benchmarks Game's much more rigorous multi-run statistical methodology. Treat every number here as "roughly this, on one Apple M1 Pro" rather than a universal truth about the language. Three benchmarks cannot characterize a language's overall performance; they characterize *these three workloads*, on *this machine*, with *these compiler versions*, on *this day*. What the numbers ARE good for: showing where SafeC's actual, current tradeoffs are — including ones its own documentation didn't know about until this page was built (see the multithreading section).
:::

## Methodology

- **Machine**: Apple M1 Pro, 10 cores, 32 GB RAM, macOS 26.5.1.
- **Toolchains**: `safec` 1.0.0 (this repo's build) · Apple Clang 21.0.0 (C/C++) · Go 1.26.5 · Zig 0.16.0 · Rust 1.97.1 · Python 3.14.6 (+ NumPy 2.5.1 for one SIMD row).
- **Build flags** — debug: `-O0` (C/C++), unoptimized IR (SafeC), `go build -gcflags="all=-N -l"`, Zig's default Debug mode, plain `rustc`. Release: `-O2` (C/C++, and SafeC's `clang -O2` backend step — matching what `safeguard build --release` actually does, not a hand-picked "best" flag), plain `go build`, `zig build-exe -O ReleaseFast`, `rustc -O`. Python has no debug/release distinction — one interpreted number, shown in both columns for reference.
- **Timing**: `/usr/bin/time -l`, best (lowest) wall-clock time of 3 runs. **Memory**: peak of the same 3 runs' "maximum resident set size."
- **Correctness**: every binary's output was checked against the known-correct value for its workload before being included — a benchmark that produced a wrong answer would be a bug in the harness, not a real result, so none are shown.
- Every source file used is linked inline next to its result, not embedded — click through for the raw file.

## Single-threaded

### fib(37) — recursive Fibonacci

Naive recursive Fibonacci — pure function-call and integer-arithmetic overhead, no allocation, no I/O.

#### Debug build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.14s | **1.3 MB (leanest)** | 0.11s |
| C | 0.13s | 1.3 MB | 0.11s |
| C++ | 0.13s | **1.3 MB (leanest)** | 0.11s |
| Rust | 0.18s | 1.5 MB | 0.15s |
| Zig | 0.16s | 1.7 MB | 1.38s |
| Go | **0.11s (fastest)** | 4.1 MB | 0.09s |
| Python | 2.70s | 14.5 MB | N/A (interpreted) |

#### Release build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.08s | **1.3 MB (leanest)** | 0.13s |
| C | **0.07s (fastest)** | **1.3 MB (leanest)** | 0.09s |
| C++ | **0.07s (fastest)** | **1.3 MB (leanest)** | 0.09s |
| Rust | 0.09s | 1.5 MB | 0.18s |
| Zig | 0.08s | 1.4 MB | 5.60s |
| Go | 0.08s | 4.0 MB | 0.08s |
| Python | 2.70s | 14.5 MB | N/A (interpreted) |

**Sources:**

**SafeC**: [fib.sc](/benchmarks/fib/safec/fib.sc)

**C**: [fib.c](/benchmarks/fib/c/fib.c)

**C++**: [fib.cpp](/benchmarks/fib/cpp/fib.cpp)

**Rust**: [fib.rs](/benchmarks/fib/rust/fib.rs)

**Zig**: [fib.zig](/benchmarks/fib/zig/fib.zig)

**Go**: [fib.go](/benchmarks/fib/go/fib.go)

**Python**: [fib.py](/benchmarks/fib/python/fib.py)

### n-body — 5-body orbital simulation

The classic Benchmarks Game n-body test (Sun/Jupiter/Saturn/Uranus/Neptune), 2,000,000 simulation steps. Floating-point heavy, no allocation, tiny working set.

#### Debug build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.63s | **1.3 MB (leanest)** | 0.11s |
| C | 0.42s | **1.3 MB (leanest)** | 0.11s |
| C++ | 0.41s | **1.3 MB (leanest)** | 0.13s |
| Rust | 0.70s | 1.5 MB | 0.13s |
| Zig | 0.56s | 1.8 MB | 1.44s |
| Go | **0.28s (fastest)** | 4.1 MB | 0.09s |
| Python | 9.89s | 15.2 MB | N/A (interpreted) |

#### Release build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.11s | **1.3 MB (leanest)** | 0.13s |
| C | **0.10s (fastest)** | 1.3 MB | 0.10s |
| C++ | 0.11s | **1.3 MB (leanest)** | 0.12s |
| Rust | 0.11s | 1.5 MB | 0.16s |
| Zig | 0.11s | 1.5 MB | 5.69s |
| Go | **0.10s (fastest)** | 4.1 MB | 0.09s |
| Python | 9.89s | 15.2 MB | N/A (interpreted) |

**Sources:**

**SafeC**: [nbody.sc](/benchmarks/nbody/safec/nbody.sc)

**C**: [nbody.c](/benchmarks/nbody/c/nbody.c)

**C++**: [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp)

**Rust**: [nbody.rs](/benchmarks/nbody/rust/nbody.rs)

**Zig**: [nbody.zig](/benchmarks/nbody/zig/nbody.zig)

**Go**: [nbody.go](/benchmarks/nbody/go/nbody.go)

**Python**: [nbody.py](/benchmarks/nbody/python/nbody.py)

### binary-trees — allocation/deallocation stress

Builds and discards millions of small binary trees (max depth 18) — this is the one that actually exercises each language's memory management strategy rather than its arithmetic, and it's where the results get interesting.

#### Debug build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | **0.29s (fastest)** | 25.4 MB | 0.12s |
| C | 1.67s | **17.4 MB (leanest)** | 0.11s |
| C++ | 1.87s | **17.4 MB (leanest)** | 0.10s |
| Rust | 3.16s | 17.6 MB | 0.14s |
| Zig | 4.85s | 17.9 MB | 1.35s |
| Go | 2.52s | 38.6 MB | 0.12s |
| Python | 20.93s | 87.0 MB | N/A (interpreted) |

#### Release build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | **0.21s (fastest)** | 25.4 MB | 0.14s |
| C | 1.56s | **17.4 MB (leanest)** | 0.10s |
| C++ | 1.86s | **17.4 MB (leanest)** | 0.10s |
| Rust | 1.74s | 17.6 MB | 0.16s |
| Zig | 1.57s | 17.6 MB | 5.28s |
| Go | 1.26s | 40.8 MB | 0.09s |
| Python | 20.93s | 87.0 MB | N/A (interpreted) |

The SafeC row above uses `region`/`arena<R>` (compile-time region-scoped lifetime safety, bump-pointer allocation, `arena_reset<R>()` discards a whole region in O(1)) instead of `std::alloc`/`heap` — this workload is all short-lived, same-scope allocations, exactly what regions are for. Measured against the plain heap version: 1.15s → 0.21s release (~5.5x), 33.6 MB → 26.0 MB peak (~23% less).

**Sources:**

**SafeC**: [binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc)

**C**: [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c)

**C++**: [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp)

**Rust**: [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs)

**Zig**: [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig)

**Go**: [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go)

**Python**: [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)

## Collections — std::collections throughput (1,000,000 elements)

| Operation | Throughput |
|---|---|
| `bst_insert` | 1,577,110/sec |
| `list_push_back` | 65,427,899/sec |
| `map_insert` | 4,365,783/sec |
| `map_get` | 8,468,404/sec |

[bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc)

## Multithreaded — binary-trees, 8 threads

Same binary-trees workload, parallelized across 8 worker threads (this machine has 10 cores) — each thread builds/checksums an independent slice of the tree count at a given depth, joined before moving to the next depth. Release builds only. SafeC: one `region`/`arena<R>` per thread (arena state isn't shared/locked, so each thread needs its own) — measured against the plain heap version: 0.68s → 0.09s (~7.5x).

| Language | 8-thread time | Peak memory | vs. single-thread |
|---|---|---|---|
| SafeC | **0.09s (fastest)** | 89.7 MB | 2.33× |
| C | 0.61s | 72.3 MB | 2.56× |
| C++ | 0.63s | 72.5 MB | 2.95× |
| Rust | 0.68s | **72.2 MB (leanest)** | 2.56× |
| Zig | 0.54s | 74.1 MB | 2.91× |
| Go | 0.44s | 144.2 MB | 2.86× |
| Python | 24.43s | 372.0 MB | 0.86× (slower than 1 thread) |

**Sources:**

**SafeC**: [binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc)

**C**: [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c)

**C++**: [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp)

**Rust**: [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs)

**Zig**: [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig)

**Go**: [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go)

**Python**: [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)

## SIMD — sum of squares over 20,000,000 f64 values

A large-array reduction (`sum(a[i]*a[i])`), comparing a plain scalar loop against each language's explicit vector type at the same optimization level (`-O2`/release) — so this isolates what writing explicit SIMD buys *on top of* whatever the backend's ordinary auto-vectorizer already does to the scalar loop, not "vectorized vs. deliberately crippled." SafeC uses its native `vec<double,4>` type; C/C++ use GCC/Clang vector extensions; Zig uses `@Vector`; Rust uses stable-channel AArch64 NEON intrinsics (`std::simd`/`portable_simd` needs Rust nightly, not used here). Go has no portable SIMD type in its standard toolchain, so only a scalar number exists for it. Python's plain loop is included for scale, alongside NumPy — the realistic way anyone actually gets vectorized numeric performance in Python.

| Language | Scalar time | Explicit-SIMD time | Speedup |
|---|---|---|---|
| SafeC | 0.04s | **0.03s (fastest)** | 1.33× |
| C | 0.04s | **0.03s (fastest)** | 1.33× |
| C++ | 0.04s | **0.03s (fastest)** | 1.33× |
| Rust | 0.04s | **0.03s (fastest)** | 1.33× |
| Zig | 0.04s | **0.03s (fastest)** | 1.33× |
| Go | 0.06s | N/A | — |
| Python | 3.50s | N/A | — |
| Python + NumPy | — | 0.14s | 25.00× |

**Sources:**

**SafeC**: [simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc)

**C**: [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c)

**C++**: [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp)

**Rust**: [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs)

**Zig**: [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig)

**Go**: [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go)

**Python**: [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)

## Web service — JSON "hello world" endpoint

`GET /` returning `{"message":"Hello, World!"}` — the same minimal shape as TechEmpower's "JSON serialization" test, run through Apache Bench (`ab -n 20000 -c 50`, no keep-alive so every server is measured the same way regardless of whether it supports connection reuse) against each language's own HTTP-serving story: SafeC's `std::http_serve_threaded` (native, part of this repo); a minimal raw-socket accept loop for C/C++/Zig, since none of the three have a dominant "web framework" the way the others do; Go's `net/http`; Python's FastAPI + uvicorn; and Rust's **axum**, not Dioxus — Dioxus's fullstack server layer *is* axum underneath (its own server-function RPC sits on top of an axum app), so benchmarking axum directly measures what Dioxus's own requests actually run through, without needing the separate `dx`/WASM-bundling toolchain Dioxus's client-rendering story requires and that a raw-throughput HTTP test has no use for.

| Language | Req/sec | p50 latency | p99 latency | Peak memory |
|---|---|---|---|---|
| SafeC | **28305 (fastest)** | 2ms | 3ms | 7.2 MB |
| C | 25303 | 2ms | 2ms | **1.4 MB (leanest)** |
| C++ | 24148 | 2ms | 4ms | 1.6 MB |
| Rust | 27270 | 2ms | 2ms | 3.4 MB |
| Zig | 25742 | 2ms | 2ms | 1.5 MB |
| Go | 25274 | 2ms | 3ms | 19.7 MB |
| Python | 4790 | 10ms | 33ms | 54.9 MB |

[io_nb_bsd.sc](/benchmarks/stdlib/io_nb_bsd.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h)

**Sources:**

**SafeC**: [server.sc](/benchmarks/webservice/safec/server.sc)

**C**: [server.c](/benchmarks/webservice/c/server.c)

**C++**: [server.cpp](/benchmarks/webservice/cpp/server.cpp)

**Rust**: [main.rs](/benchmarks/webservice/rust/src/main.rs) · [private.rs](/benchmarks/webservice/rust/target/release/build/serde-27ec540374108a89/out/private.rs) · [private.rs](/benchmarks/webservice/rust/target/release/build/serde_core-b2f4be27c80b0f48/out/private.rs)

**Zig**: [server.zig](/benchmarks/webservice/zig/server.zig)

**Go**: [server.go](/benchmarks/webservice/go/server.go)

**Python**: [server.py](/benchmarks/webservice/python/server.py)

## Machine learning — small MLP, training and inference

2-layer MLP (`relu(X @ W1) @ W2`, 128→256→64, batch 64, MSE loss, hand-rolled SGD, no bias) — 100 training steps then 1000 inference-only passes, fixed seed. Compares the same computation graph across frameworks; `std::ml` covers CPU (Accelerate BLAS) and MPS (this shape is too small for GPU to beat CPU BLAS on any framework here, SafeC included — the MPS row is included for completeness, not to compete on it).

| Framework | Device | Train (100 steps) | Throughput | Inference (1000 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC (Accelerate BLAS) | CPU | **16.8ms (fastest)** | 380499 samples/s | 26.1ms | 100.333420 |
| SafeC | MPS | 39.4ms | 162581 samples/s | 61.1ms | 100.333290 |
| PyTorch | CPU | 23.1ms | 277232 samples/s | **13.5ms (fastest)** | 100.018272 |
| PyTorch | MPS | 131.6ms | 48649 samples/s | 358.7ms | 103.423889 |
| TensorFlow | CPU | 83.0ms | 77103 samples/s | 245.0ms | 106.182991 |
| TensorFlow | GPU | 245.3ms | 26086 samples/s | 924.6ms | 106.182999 |
| MLX | GPU | 72.2ms | 88685 samples/s | 309.1ms | 96.632431 |

[tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [tensor_gpu.h](/benchmarks/stdlib/tensor_gpu.h) · [tensor_gpu.sc](/benchmarks/stdlib/tensor_gpu.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [gen_mps_metallib.sh](/benchmarks/stdlib/gen_mps_metallib.sh) · [time.sc](/benchmarks/stdlib/time.sc) · [time.h](/benchmarks/stdlib/time.h)

**Sources:**

**SafeC**: [train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc)

**PyTorch**: [train.py](/benchmarks/ml/pytorch/train.py)

**TensorFlow**: [train.py](/benchmarks/ml/tensorflow/train.py)

**MLX**: [train.py](/benchmarks/ml/mlx/train.py)

## Machine learning, bigger model — SafeC vs PyTorch, TensorFlow, MLX

Same shape, scaled up 512→1024→256, batch 128 (~50x the multiply-adds/matmul). 50 training steps, 200 inference passes. SafeC: Accelerate-BLAS CPU path plus MPS GPU, both compared against the other three frameworks at this size for the first time.

| Framework | Device | Train (50 steps) | Throughput | Inference (200 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC (Accelerate BLAS) | CPU | 89.2ms | 71713 samples/s | 61.4ms | 95.428856 |
| SafeC | MPS | **47.9ms (fastest)** | 133433 samples/s | **47.3ms (fastest)** | 95.428223 |
| PyTorch | CPU | 67.8ms | 94440 samples/s | 48.2ms | 106.548233 |
| PyTorch | MPS | 110.5ms | 57926 samples/s | 86.4ms | 108.710899 |
| TensorFlow | CPU | 148.8ms | 43003 samples/s | 197.6ms | 316.556885 |
| TensorFlow | GPU | 154.1ms | 41539 samples/s | 218.3ms | 317.200012 |
| MLX | GPU | 54.3ms | 117953 samples/s | 74.4ms | 88.762100 |

[gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [tensor_gpu.h](/benchmarks/stdlib/tensor_gpu.h) · [tensor_gpu.sc](/benchmarks/stdlib/tensor_gpu.sc)

**Sources:**

**SafeC**: [train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_cpu_blas.sc](/benchmarks/ml_big/safec/train_cpu_blas.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc)

**PyTorch**: [train.py](/benchmarks/ml_big/pytorch/train.py)

**TensorFlow**: [train.py](/benchmarks/ml_big/tensorflow/train.py)

**MLX**: [train.py](/benchmarks/ml_big/mlx/train.py)

## Machine learning, GPU backends — CUDA, ROCm, Vulkan/SPIR-V, WebGPU

[gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_spirv.h](/benchmarks/stdlib/gpu_spirv.h) · [gpu_spirv.sc](/benchmarks/stdlib/gpu_spirv.sc) · [gpu_webgpu.h](/benchmarks/stdlib/gpu_webgpu.h) · [gpu_webgpu.sc](/benchmarks/stdlib/gpu_webgpu.sc) · [tensor_cuda.h](/benchmarks/stdlib/tensor_cuda.h) · [tensor_cuda.sc](/benchmarks/stdlib/tensor_cuda.sc) · [tensor_rocm.h](/benchmarks/stdlib/tensor_rocm.h) · [tensor_rocm.sc](/benchmarks/stdlib/tensor_rocm.sc)

## Machine learning, device selection

Every backend names its op explicitly (`tensor_matmul` vs `_blas` vs `_gpu` vs `_cuda` vs ...) — precise, but awkward for a caller that wants to pick a device once rather than hard-code a function name per call site.

`tensor_matmul_on(a, b, device)` / `tensor_relu_on(a, device)` dispatch over a `Device` enum instead — verified bit-identical across every backend runnable in this sandbox (CPU, CPU+BLAS, MPS):

| Device | Y[0][0] | Y[0][1] |
|---|---|---|
| CPU | 0.900000 | 1.300000 |
| CPU + BLAS | 0.900000 | 1.300000 |
| MPS | 0.900000 | 1.300000 |

Building this exposed a real name collision: `activations.sc` (forward-only ops) and `tensor_nn.sc` (autograd ops, needed by every GPU backend) both defined `tensor_sigmoid`/`tensor_relu`/etc. — no program could link both, so no `activations.sc`-based layer could use a GPU backend. Fixed by renaming `activations.h`'s forward-only functions with a `_fwd` suffix and updating `attention.sc`/`transformer.sc`/`rnn.sc`'s call sites.

`jit_block_forward_on(block, x, device)` threads device selection through a real layer (JiTBlock's Q/K/V/output projections + FFN) — verified bit-identical to the reference across CPU/CPU+BLAS/MPS:

| Path | y[0] | y[1] | y[2] |
|---|---|---|---|
| `jit_block_forward` (reference) | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU_BLAS)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_MPS)` | -0.479727 | -0.589425 | -0.537808 |

Scope boundary: attention's internal QK^T/softmax/·V matmuls aren't device-routed yet, and neither is `DiTBlock`/`cnn.sc` — same pattern, not applied everywhere yet.

[tensor_dispatch.h](/benchmarks/stdlib/tensor_dispatch.h) · [tensor_dispatch.sc](/benchmarks/stdlib/tensor_dispatch.sc) · [transformer_dispatch.h](/benchmarks/stdlib/transformer_dispatch.h) · [transformer_dispatch.sc](/benchmarks/stdlib/transformer_dispatch.sc) · [activations.h](/benchmarks/stdlib/activations.h) · [activations.sc](/benchmarks/stdlib/activations.sc) · [attention.h](/benchmarks/stdlib/attention.h) · [attention.sc](/benchmarks/stdlib/attention.sc) · [transformer.h](/benchmarks/stdlib/transformer.h) · [transformer.sc](/benchmarks/stdlib/transformer.sc)

## Memory allocation — is `std::alloc`/`dealloc` slower than raw `malloc`/`free`?

`std::alloc` uses a size-class caching allocator (same idea PyTorch's CPU/CUDA caching allocators and MLX's Metal buffer cache use): a freed block goes into a thread-local free list bucketed by power-of-two size class, and the next same-class `alloc()` is satisfied straight from there, skipping `malloc()`/`free()` entirely. Double-free/UAF detection is unaffected (a cached block still carries its "freed" magic word until reused).

`std::alloc`/`dealloc` runs *faster* than raw `malloc`/`free` on a binarytrees-shaped workload of many small, same-size, short-lived allocations (1210ms vs 1518ms, ~20% faster) and ~3.3x faster on an interleaved alloc/free microbenchmark (11–12ns/call vs 37–38ns/call). `region`/`arena<R>` is faster still (~5.9x over heap).

[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc)
