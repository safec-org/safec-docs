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
| SafeC | 0.13s | **1.3 MB (leanest)** | 0.12s |
| C | 0.13s | 1.3 MB | 0.09s |
| C++ | 0.13s | 1.3 MB | 0.10s |
| Rust | 0.17s | 1.5 MB | 0.12s |
| Zig | 0.15s | 1.7 MB | 1.26s |
| Go | **0.11s (fastest)** | 4.0 MB | 0.09s |
| Python | 2.66s | 14.5 MB | N/A (interpreted) |

#### Release build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | **0.07s (fastest)** | **1.3 MB (leanest)** | 0.10s |
| C | **0.07s (fastest)** | **1.3 MB (leanest)** | 0.08s |
| C++ | **0.07s (fastest)** | **1.3 MB (leanest)** | 0.08s |
| Rust | **0.07s (fastest)** | 1.5 MB | 0.12s |
| Zig | **0.07s (fastest)** | 1.5 MB | 4.91s |
| Go | 0.08s | 3.8 MB | 0.07s |
| Python | 2.66s | 14.5 MB | N/A (interpreted) |

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
| SafeC | 0.63s | 1.3 MB | 0.11s |
| C | 0.41s | **1.3 MB (leanest)** | 0.11s |
| C++ | 0.41s | 1.3 MB | 0.12s |
| Rust | 0.68s | 1.5 MB | 0.13s |
| Zig | 0.54s | 1.8 MB | 1.27s |
| Go | **0.27s (fastest)** | 4.2 MB | 0.08s |
| Python | 9.73s | 15.1 MB | N/A (interpreted) |

#### Release build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.11s | **1.3 MB (leanest)** | 0.11s |
| C | **0.10s (fastest)** | 1.3 MB | 0.09s |
| C++ | **0.10s (fastest)** | **1.3 MB (leanest)** | 0.11s |
| Rust | 0.11s | 1.5 MB | 0.16s |
| Zig | 0.11s | 1.5 MB | 5.19s |
| Go | **0.10s (fastest)** | 3.9 MB | 0.08s |
| Python | 9.73s | 15.1 MB | N/A (interpreted) |

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
| SafeC | **0.29s (fastest)** | 25.4 MB | 0.14s |
| C | 1.66s | **17.4 MB (leanest)** | 0.10s |
| C++ | 1.88s | **17.4 MB (leanest)** | 0.10s |
| Rust | 3.09s | 17.6 MB | 0.13s |
| Zig | 4.79s | 17.8 MB | 1.32s |
| Go | 2.51s | 39.4 MB | 0.08s |
| Python | 21.14s | 86.9 MB | N/A (interpreted) |

#### Release build

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | **0.21s (fastest)** | 25.4 MB | 0.15s |
| C | 1.54s | **17.4 MB (leanest)** | 0.10s |
| C++ | 1.69s | **17.4 MB (leanest)** | 0.09s |
| Rust | 1.75s | 17.6 MB | 0.14s |
| Zig | 1.54s | 17.6 MB | 5.16s |
| Go | 1.24s | 39.1 MB | 0.08s |
| Python | 21.14s | 86.9 MB | N/A (interpreted) |

SafeC uses `region`/`arena<R>` (compile-time region-scoped lifetime safety, bump-pointer allocation, `arena_reset<R>()` discards a whole region in O(1)) rather than `std::alloc`/`heap` for this benchmark.

[binarytrees_arena.sc](/benchmarks/binarytrees/safec_arena/binarytrees_arena.sc)

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
| `bst_insert` | 1,611,383/sec |
| `list_push_back` | 66,840,451/sec |
| `map_insert` | 4,359,711/sec |
| `map_get` | 8,623,886/sec |

[bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc)

## Multithreaded — binary-trees, 8 threads

Same binary-trees workload, parallelized across 8 worker threads (this machine has 10 cores) — each thread builds/checksums an independent slice of the tree count at a given depth, joined before moving to the next depth. Release builds only. SafeC: one `region`/`arena<R>` per thread (arena state isn't shared/locked, so each thread needs its own) — [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec_arena/binarytrees_mt_arena.sc).

| Language | 8-thread time | Peak memory | vs. single-thread |
|---|---|---|---|
| SafeC | **0.08s (fastest)** | 89.6 MB | 2.62× |
| C | 0.50s | **73.9 MB (leanest)** | 3.08× |
| C++ | 0.57s | 74.0 MB | 2.96× |
| Rust | 0.66s | 74.4 MB | 2.65× |
| Zig | 0.57s | 74.1 MB | 2.70× |
| Go | 0.43s | 152.0 MB | 2.88× |
| Python | 24.18s | 350.5 MB | 0.87× (slower than 1 thread) |

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
| Python + NumPy | — | 0.15s | 23.33× |

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
| SafeC | **26131 (fastest)** | 2ms | 3ms | 6.9 MB |
| C | 23049 | 2ms | 9ms | **1.5 MB (leanest)** |
| C++ | 22612 | 2ms | 3ms | 1.8 MB |
| Rust | 24387 | 2ms | 3ms | 3.4 MB |
| Zig | 23749 | 2ms | 3ms | 1.9 MB |
| Go | 22843 | 2ms | 7ms | 20.1 MB |
| Python | 4436 | 11ms | 29ms | 55.0 MB |

::: tip Two real, verified bugs found and fixed by this benchmark
The first SafeC run of this benchmark surfaced two genuine bugs in
`std/sched/io_nb_bsd.sc` (shared by every module built on it — HTTP, RPC,
WebSocket, raw TCP), both now fixed and reflected in the numbers above:

1. **`listen(fd, 16)`** — the accept backlog was hardcoded to 16 connections. A
   completely ordinary load-test concurrency of 50 overflowed it, and the
   connections that didn't fit hit real TCP SYN-retransmit delays: p99
   latency was 31ms and the worst request took 577ms, entirely in the
   *connect* phase, not request processing — while every other
   language's server (backlog 1024, or a framework default) stayed at a
   flat ~2-3ms. Fixed by raising the backlog to 512 (also fixed in the
   Linux and Unix-domain-socket backends, same bug, same fix).
2. **No `SO_REUSEADDR`** — restarting a server on the same port right
   after it had handled real traffic silently failed to bind (every other
   language's server sets this; SafeC's didn't). The process printed its
   own "listening" banner regardless, since that print happened *before*
   the bind attempt and nothing checked `http_serve_threaded`'s return
   value — so it looked like it started, then just exited. Fixed by
   setting `SO_REUSEADDR` before `bind()` in both the BSD and Linux
   backends (left Windows alone deliberately — `SO_REUSEADDR` has
   different, riskier semantics there that I can't verify without a
   Windows environment to test against).

Both are re-verified against the same bar as every other fix on this
page: full stdlib regression (176/177) and a direct reproduction (kill a
server that just handled real traffic, immediately try to rebind the same
port) before and after.
:::

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

2-layer MLP (`relu(X @ W1) @ W2`, 128→256→64, batch 64, MSE loss, hand-rolled SGD, no bias) — 100 training steps then 1000 inference-only passes, fixed seed. Compares the same computation graph across frameworks; `std::ml` covers CPU (naive + Accelerate BLAS), MPS, and CUDA/ROCm/SPIR-V/WebGPU backends (see the bigger-model table below for GPU numbers — this shape is too small for any GPU backend to win, on any framework).

| Framework | Device | Train (100 steps) | Throughput | Inference (1000 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC (Accelerate BLAS) | CPU | **26.7ms (fastest)** | 239342 samples/s | 53.1ms | 100.333294 |
| PyTorch | CPU | 27.0ms | 236967 samples/s | **14.3ms (fastest)** | 100.018272 |
| PyTorch | MPS | 154.6ms | 41405 samples/s | 360.1ms | 103.423889 |
| TensorFlow | CPU | 96.2ms | 66501 samples/s | 276.6ms | 106.182991 |
| TensorFlow | GPU | 296.6ms | 21575 samples/s | 1051.5ms | 106.182999 |
| MLX | GPU | 75.4ms | 84876 samples/s | 305.6ms | 96.632431 |

::: tip Naive matmul vs. Accelerate BLAS
`tensor_matmul`'s naive triple loop was ~6x slower training / ~36x slower inference than PyTorch CPU (Accelerate BLAS underneath) — a GEMM-quality gap, not a language gap. `tensor_matmul_blas` (same autograd graph, `cblas_dgemm` for forward+backward) closes training to noise-level parity with PyTorch. Inference stays behind: PyTorch skips BLAS's per-call setup below a size threshold via a small-matrix fast path; matching that needs a size-specialized micro-kernel, not a BLAS call — not pursued.

[tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc)
:::

::: tip GPU dispatch: five runtime costs, all fixed — the last one was the real bottleneck
`mps_*_f32` used to recompile its Metal kernel from source on every call, allocate a fresh never-reused Metal buffer on every dispatch, and only ever run a tensor's *forward* pass on the GPU — backward always fell back to the CPU, even for a GPU-computed tensor.

- **Pipeline caching** (matmul/relu, then the rest of the op set): compile once, reuse forever. Cut GPU inference on the bigger-model shape 40% (2433ms → 1449ms/200 passes).
- **Batched dispatch** (`tensor_gpu_batch_begin`/`end`): a `matmul → relu → matmul` forward pass shares one command-buffer sync instead of three. Caught and fixed a real bug here — naive buffer chaining silently computed 0 because it re-read stale CPU data instead of the previous op's GPU output; fixed by chaining GPU buffers directly (`mps_*_f32_chained`). Combined with pipeline caching: inference 2433ms → 1238ms, training 2728ms → 2408ms.
- **Compile-time kernel generation**: kernels compile ahead of time (`gen_mps_metallib.sh` → `.metallib`, embedded as a byte array) instead of from an in-process MSL source string, and every Objective-C selector is resolved once into a static. Isolated measurement (device creation excluded): library load 0.05ms vs. the old per-op source compile's 0.85–2.4ms. Barely moved training time — see why below.
- **Buffer pooling**: every op called `newBufferWithBytes:`/`newBufferWithLength:` fresh on every dispatch and never released the result — a real Metal buffer allocation, not a cheap malloc, every single call. Fixed the same way PyTorch's MPSAllocator and MLX's buffer cache do it: freed buffers go into a thread-local size-class free list instead of being abandoned (same design as `std::alloc`'s CPU-side cache — see the memory-allocation section below). Measured on an isolated 1M-element op: 2.4–2.7x faster (`mps_add_f32` 1.51ms/call → 0.60ms/call).
- **GPU-backed backward** — the actual bottleneck, found by profiling the real training loop instead of assuming a fix helped: `tensor_matmul_gpu`/`tensor_relu_gpu` ran forward on the GPU but stayed linked to the plain CPU `__matmul_backward`/`__relu_backward` for backward, same as the naive CPU-only path. Direct measurement: backward was ~2400ms of a ~2650ms training step (90%), GPU forward only ~215ms — every fix above could only ever move that 215ms. This is exactly the gap PyTorch and MLX don't have: moving a tensor to a GPU device puts the whole graph, forward *and* backward, on that device. Added dedicated backward kernels (`mps_matmul_abt_f32`/`mps_matmul_atb_f32` for matmul's dA/dB, `mps_relu_backward_f32` for relu), verified bit-identical gradients against the CPU reference on a hand-checkable toy network, then linked `tensor_matmul_gpu`/`tensor_relu_gpu` to them: **train_ms 2352.8 → 509.4 (4.6x)**. Inference improved less (1075.6ms → 798.6ms) since it has no backward pass to fix — it only got the buffer-pooling/pipeline-caching wins above.
- **Tiled matmul kernels**: `matmul_kernel` and the two new backward kernels were still one-thread-per-output-element with no data reuse — every output element re-read its whole row/column of A/B from device memory independently, the same class of gap PyTorch's `MPSMatrixMultiplication` and MLX's own kernels close with threadgroup-memory tiling. Rewrote all three as tiled kernels (16x16 tiles, cooperative load into `threadgroup` memory, boundary-checked for matrices smaller than one tile) — correctness verified against a CPU reference on a deliberately non-tile-aligned shape (37x53 · 53x29, every dimension straddling a partial tile) before measuring, not after. Effect on training time: noisy on this machine (a single run ranged 343–778ms across repeated back-to-back naive-vs-tiled comparisons, averaging out to roughly 1.5x, up to ~2x in the best case) — real, but smaller and less consistent than the backward-pass fix, and Apple Silicon's `simdgroup_matrix` hardware matrix units (the tier above plain tiling, and the tier PyTorch/MLX's fastest paths actually use) aren't attempted here.

[tensor_gpu.h](/benchmarks/stdlib/tensor_gpu.h) · [tensor_gpu.sc](/benchmarks/stdlib/tensor_gpu.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [gen_mps_metallib.sh](/benchmarks/stdlib/gen_mps_metallib.sh)
:::

::: tip GPU is slower than CPU here — correctly
Tiny workload (a few hundred thousand multiply-adds/step): every GPU path (PyTorch MPS, TensorFlow-Metal, MLX) is slower than its own CPU path, dispatch overhead dominates below this problem size. Expected, not a bug.

Also found and fixed here: `std::time_mono_ns()` used Linux's `CLOCK_MONOTONIC` id (1) instead of Darwin's (6), so it always returned -1 on macOS.

[time.sc](/benchmarks/stdlib/time.sc) · [time.h](/benchmarks/stdlib/time.h)
:::

::: tip Compiler bug: struct-by-value args over 16 bytes used the wrong ABI
`mps_add_f32`'s `dispatchThreadgroups:threadsPerThreadgroup:` call (two 24-byte `MTLSize` structs by value) segfaulted. SafeC's `objc_msgSend`-cast indirect calls emitted such structs as raw LLVM aggregates instead of the AAPCS64-required indirect form (caller `alloca`s a copy, passes a pointer). Bisected against a minimal Objective-C-free repro, confirmed against `clang -S -emit-llvm`'s own lowering, fixed in `CodeGen.cpp`'s indirect-call codegen — scoped to non-HFA struct-by-value args over 16 bytes through a function-pointer-cast call, so direct SafeC-to-SafeC calls and existing HFA cases (`gui_cocoa.sc`'s `NSRect`) are untouched. Full stdlib and every benchmark on this page rebuilt and re-verified against the fix.
:::

::: tip SafeC CPU path: 2.7x faster after profiling
Went from train_ms=453.6 (worse than TensorFlow-GPU) to 165.0 via three fixes in `tensor.sc`, each checked against a hand-computed gradient and the full loss trajectory before keeping:

1. `tensor_matmul`'s loop order (`i,j,p` → `i,p,j`) let LLVM auto-vectorize the inner loop — ~90% of runtime, alone worth 2.7x train / 4.3x inference.
2. Elementwise ops (`add`/`sub`/`mul`/`scale`/`relu`) stopped zero-filling buffers about to be fully overwritten.
3. Elementwise backward passes accumulate straight into `x->grad` instead of a temp buffer + copy — except `__matmul_backward`, where the same change was tried, measured, and reverted: pointer-aliasing ambiguity between struct fields blocked auto-vectorization there and nearly doubled its profiled cost.

Net: train_ms 453.6 → 165.0 (2.7x), inference_ms/1000 2253.0 → 517.5 (4.4x) — now ahead of TensorFlow-GPU and competitive with PyTorch-MPS, still behind PyTorch/TensorFlow/MLX's own mature CPU BLAS backends. Also checked: disassembly confirms real NEON SIMD already in use; multithreading `tensor_matmul` was tried and reverted (212ms, worse) — per-call OS thread creation cost more than the compute it parallelized at this call frequency. A persistent thread pool might still help but doesn't exist in `std/` yet.

[tensor.sc](/benchmarks/stdlib/tensor.sc) · [tensor.h](/benchmarks/stdlib/tensor.h)
:::

**Sources:**

**SafeC**: [train.sc](/benchmarks/ml/safec/train.sc)

**PyTorch**: [train.py](/benchmarks/ml/pytorch/train.py)

**TensorFlow**: [train.py](/benchmarks/ml/tensorflow/train.py)

**MLX**: [train.py](/benchmarks/ml/mlx/train.py)

## Machine learning, bigger model — SafeC vs PyTorch, TensorFlow, MLX

Same shape, scaled up 512→1024→256, batch 128 (~50x the multiply-adds/matmul). 50 training steps, 200 inference passes. SafeC: Accelerate-BLAS CPU path plus MPS GPU, both compared against the other three frameworks at this size for the first time.

| Framework | Device | Train (50 steps) | Throughput | Inference (200 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC (Accelerate BLAS) | CPU | 194.4ms | 32915 samples/s | 141.1ms | 95.429579 |
| SafeC | MPS | 401.8ms | 15932 samples/s | 717.6ms | 95.429121 |
| PyTorch | CPU | 70.7ms | 90545 samples/s | **49.0ms (fastest)** | 106.548233 |
| PyTorch | MPS | 121.1ms | 52854 samples/s | 89.7ms | 108.710899 |
| TensorFlow | CPU | 156.1ms | 41003 samples/s | 197.9ms | 316.556885 |
| TensorFlow | GPU | 190.0ms | 33691 samples/s | 244.6ms | 317.200012 |
| MLX | GPU | **54.4ms (fastest)** | 117589 samples/s | 74.9ms | 88.762100 |

::: tip BLAS closes most of the CPU gap; GPU backward + tiling closed most of the GPU gap
Naive SafeC matmul trained in ~3035ms at this size (off the chart); `tensor_matmul_blas` cuts that to ~194ms (~15.6x), but SafeC CPU-BLAS is still ~2.8x behind PyTorch CPU and ~3.6x behind MLX — `cblas_dgemm` is identical regardless of caller, so the remaining gap is the non-BLAS elementwise ops and per-op Tensor/autograd bookkeeping, both scaling with element count the same way matmul's FLOPs do. TensorFlow is the slowest CPU entry here across every framework, a known eager-mode cost.

SafeC's MPS path used to fall further behind at this size, not closer — the cause (see the GPU dispatch tip above) was that `tensor_matmul_gpu`/`tensor_relu_gpu`'s backward pass never ran on the GPU at all, so scaling up the model just scaled up the CPU backward cost. Fixed in two steps: GPU-backed backward kernels (2352.8ms → 509.4ms, 4.6x) and then tiling those kernels' memory access pattern (509.4ms → 401.8ms here, though this second step measured noisy — see the GPU dispatch tip). Combined: still ~2.1x behind SafeC's own CPU-BLAS path and ~3.3x behind PyTorch's MPS backend, but no longer the outlier it was. Two real costs remain: **precision** (Metal has no `double`; `95.429579` CPU vs `95.429121` GPU is float32 rounding, not noise) and **kernel quality** — the tiled kernels are real, verified GEMM tiling, but still well short of `simdgroup_matrix` hardware matrix units, the tier PyTorch's `MPSMatrixMultiplication`/MLX's fastest paths actually use. Backward dispatch also still isn't batched the way forward is (4 separate GPU round-trips per step instead of 1) — a real, scoped, not-yet-done follow-up.

[gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [tensor_gpu.h](/benchmarks/stdlib/tensor_gpu.h) · [tensor_gpu.sc](/benchmarks/stdlib/tensor_gpu.sc)
:::

**Sources:**

**SafeC**: [train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_cpu_blas.sc](/benchmarks/ml_big/safec/train_cpu_blas.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc)

**PyTorch**: [train.py](/benchmarks/ml_big/pytorch/train.py)

**TensorFlow**: [train.py](/benchmarks/ml_big/tensorflow/train.py)

**MLX**: [train.py](/benchmarks/ml_big/mlx/train.py)

## Machine learning, GPU backends — CUDA, ROCm, Vulkan/SPIR-V, WebGPU

[gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_spirv.h](/benchmarks/stdlib/gpu_spirv.h) · [gpu_spirv.sc](/benchmarks/stdlib/gpu_spirv.sc) · [gpu_webgpu.h](/benchmarks/stdlib/gpu_webgpu.h) · [gpu_webgpu.sc](/benchmarks/stdlib/gpu_webgpu.sc)

::: tip Vendor BLAS for CUDA/ROCm
`cuda_matmul_f32`/`rocm_matmul_f32` are hand-written, untuned PTX/HIP kernels. `cuda_matmul_f32_blas`/`rocm_matmul_f32_blas` dispatch to cuBLAS/rocBLAS instead — the same vendor-GEMM approach `tensor_matmul_blas` uses for Accelerate, plus the row-major↔column-major operand swap cuBLAS/rocBLAS need that Accelerate's CBLAS doesn't. For ROCm the BLAS path is the more usable one: the hand-written kernel needs an offline-compiled HSACO binary this sandbox can't produce; `rocblas_sgemm` is an ordinary linkable symbol.

Type-checked and link-verified against stub vendor symbols; UNVERIFIED against real NVIDIA/AMD hardware — none in this sandbox.

[tensor_cuda.h](/benchmarks/stdlib/tensor_cuda.h) · [tensor_cuda.sc](/benchmarks/stdlib/tensor_cuda.sc) · [tensor_rocm.h](/benchmarks/stdlib/tensor_rocm.h) · [tensor_rocm.sc](/benchmarks/stdlib/tensor_rocm.sc)
:::

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

## Memory allocation — is `std::alloc`/`dealloc` actually slower than raw `malloc`/`free`?

Used to be, measurably: ~30% slower on a binarytrees-shaped allocation-heavy workload (many small, same-size, short-lived objects). Root cause, broken down directly: `new T`/`dealloc()` (SafeC's built-in heap syntax) always goes through `std::alloc`, which prefixes every allocation with a 16-byte header for use-after-free/double-free detection — for small objects (binarytrees' 16-byte node), that header alone roughly *doubles* the real allocation size — and used to defer every real `free()` into a small 64-slot quarantine ring so a double-free stays reliably catchable instead of racing the platform allocator's own free-list bookkeeping (which overwrites a freed block's first bytes immediately). Both costs were real, but neither was the actual bottleneck: profiling pointed at `malloc()`/`free()` themselves — every allocation and every deallocation was a full round trip through the platform allocator, even for a workload that repeatedly asks for and frees the exact same handful of struct sizes.

Fixed with a size-class caching allocator, learning directly from PyTorch's CPU/CUDA caching allocators and MLX's Metal buffer cache: `dealloc()` no longer routes a freed block straight to the quarantine ring — it goes into a thread-local free list bucketed by power-of-two size class (16 bytes up to 1MB, 17 classes) instead, and the next `alloc()` for that class is satisfied straight from there, skipping `malloc()`/`free()` entirely. Each class's cache depth scales inversely with its block size (up to 8192 slots for 16-byte blocks, floored at 8 for the largest cached class) so no single class can retain more than a few MB, while small, frequently-reused sizes — the common case for tree/list/graph-shaped data — get a deep enough cache to matter. The double-free/UAF header check is untouched (a cached block still has its magic word flipped to "freed" until reused, so double-free detection is if anything *stronger* now: a small size class can hold a freed block open far longer than the old shared 64-slot ring could).

Re-measured after the rewrite: the binarytrees benchmark (build-and-discard trees across depths 4–18, `std::alloc`/`dealloc` throughout) now runs *faster* than raw `malloc`/`free` on the identical workload — 1210ms vs 1518ms, roughly 20% faster, not 30% slower. An interleaved alloc-then-immediately-free microbenchmark (2,000,000 calls, fixed 32-byte payload, the steady-state shape the cache is built for) lands `std::alloc`/`dealloc` around 11-12ns/call against raw `malloc`/`free`'s 37-38ns/call on the same machine — roughly 3.3x faster. The one caveat that hasn't changed: this only helps allocation *patterns* the cache can actually reuse (same size class, freed and re-requested by the same thread) — a single enormous one-off allocation still just falls through to `malloc()` directly, uncached, exactly as before.

`region`/`arena<R>` allocation (see the binary-trees section above) is still faster still — arena allocation bypasses `std::alloc`'s header and free-list bookkeeping entirely, down to a raw pointer bump — but the gap narrowed with this fix: arena now measures roughly 5.9x faster than heap on the same binarytrees workload (0.20s vs 1.18s, release build), down from roughly 9.6x before the caching allocator existed. `std::ml`'s own `Tensor` allocation (`__tensor_alloc`) already uses raw `malloc`/`free` directly, bypassing `std::alloc` entirely — this finding doesn't affect the ML benchmarks above at all.

[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc)

## Arena safety — a real gap found and closed

Region/arena reference staleness (`&arena<R> T` read after `arena_reset<R>()`/`arena_destroy<R>()`/`arena_free_to<R>()` invalidated it) was already caught at compile time for the common case: a direct variable read, an alias (`q = p;`), a function argument, or a function return value all correctly produce a compile error. Verified directly, not assumed.

The actual gap: storing an arena reference into a **struct field or array element** escaped tracking entirely — `h.n = ref; arena_reset<R>(); h.n->x` compiled with zero warnings and read freed memory. Reproduced directly, then closed: the compiler now tracks staleness through `.`-chain field access (precise per-instance, keyed by the exact variable+field path), `->`-chain field access and array elements (coarser, keyed by the field/array declaration itself, since indirection through a reference makes per-instance tracking impossible without full alias analysis — sound, never misses a real bug, but can over-flag a still-valid instance after a *different* instance's field was restamped). Verified against a full stdlib sweep (zero new failures) and both existing arena-heavy benchmark programs above (`binarytrees_arena.sc`, `binarytrees_mt_arena.sc` — both use exactly this arrow-based struct-field pattern for tree nodes) — bit-identical checksums, confirming the extended tracking doesn't false-positive on working code.

## Machine learning, neural-net building blocks

Activations (sigmoid, tanh, softmax, ELU, GELU, SiLU, GLU, SwiGLU), Adam optimizer, 1D max/avg pooling — each checked against a finite-difference numerical gradient:

| Op | max\|analytic − numeric\| |
|---|---|
| sigmoid | 0.00000000 |
| tanh | 0.00000000 |
| softmax | 0.00000000 |
| elu | 0.00001250 |
| gelu | 0.00000000 |
| silu | 0.00000000 |
| glu | 0.00000000 |
| swiglu | 0.00000000 |

[tensor_nn.h](/benchmarks/stdlib/tensor_nn.h) · [tensor_nn.sc](/benchmarks/stdlib/tensor_nn.sc) · [chk_tensor_nn.sc](/benchmarks/ml_nn/safec/chk_tensor_nn.sc)
