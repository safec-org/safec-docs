# SIMD (`std::simd`)

`std/simd/` is a portable SIMD library built entirely on
[`vec<T, N>`](/reference/simd), the compiler's native vector type — there
is no hand-written per-instruction implementation anywhere in this
library. Elementwise arithmetic and lane access already work on any
`vec<T,N>` through ordinary operator/subscript syntax; this library adds
convenient ISA-agnostic type names, pointer load/store, broadcast,
horizontal reductions, and fused multiply-add on top.

```c
#include <std/simd/simd.h>

int main() {
    float src[4] = {1.0, 5.0, 3.0, 2.0};
    float dst[4];

    f32x4 v = std::simd_load_f32x4(&src[0]);
    f32x4 doubled = v + v;
    std::simd_store_f32x4(doubled, &dst[0]);

    float total = std::simd_hsum_f32x4(v);      // horizontal sum
    f32x4 a = std::simd_splat_f32x4(2.0);       // broadcast
    f32x4 fma = std::simd_fma_f32x4(a, a, v);   // a*a + v
    return 0;
}
```

Load and store take a reference to the first element and read or write N consecutive elements from there, as one unaligned vector load or store. Passing the address of an array element (`&src[0]`) needs no `unsafe`.

## Type Aliases {#type-aliases}

Naming convention: `<elem><bits>x<lanes>`. Widths cover every target's
baseline SIMD register (128-bit: SSE, NEON, WASM SIMD128, RVV with
VLEN≥128) plus the common 256-bit case (AVX2 — on targets without a native
256-bit register, LLVM legalizes it into two 128-bit ops, still correct,
just not a single instruction).

| Alias | Underlying type |
|---|---|
| `f32x4`, `f32x8` | `vec<float, 4>`, `vec<float, 8>` |
| `f64x2`, `f64x4` | `vec<double, 2>`, `vec<double, 4>` |
| `i32x4`, `i32x8` | `vec<int, 4>`, `vec<int, 8>` |
| `i64x2`, `i64x4` | `vec<long long, 2>`, `vec<long long, 4>` |
| `i16x8`, `i16x16` | `vec<short, 8>`, `vec<short, 16>` |
| `i8x16`, `i8x32` | `vec<signed char, 16>`, `vec<signed char, 32>` |
| `u32x4`, `u32x8` | `vec<unsigned int, 4>`, `vec<unsigned int, 8>` |
| `u8x16`, `u8x32` | `vec<unsigned char, 16>`, `vec<unsigned char, 32>` |

## Functions {#functions}

| Function | Description |
|---|---|
| `simd_load_<type>(const &T p)` | Load N lanes starting at `p` — unaligned by construction |
| `simd_store_<type>(v, &T p)` | Store N lanes starting at `p` |
| `simd_splat_<type>(x)` | Broadcast a scalar to every lane |
| `simd_fma_<type>(a, b, c)` | Multiply-add `a*b + c`, as one fused instruction where the target has FMA |
| `simd_min_<type>(a, b)` / `simd_max_<type>(a, b)` | Elementwise min/max |
| `simd_hsum_<type>(v)` | Horizontal sum — reduce all lanes to one scalar |
| `simd_hmin_<type>(v)` / `simd_hmax_<type>(v)` | Horizontal min/max |

Not every function is defined for every type alias. The exact set:

| Function | Types |
|---|---|
| `simd_load_*` / `simd_store_*` | `f32x4`, `f32x8`, `f64x2`, `f64x4`, `i32x4`, `i32x8`, `u32x4`, `u32x8`, `i8x16`, `u8x16` |
| `simd_splat_*` | `f32x4`, `f32x8`, `f64x2`, `f64x4`, `i32x4`, `i32x8`, `u32x4` |
| `simd_fma_*` | `f32x4`, `f32x8`, `f64x2`, `f64x4` |
| `simd_min_*` / `simd_max_*` | `f32x4`, `f32x8`, `i32x4` |
| `simd_hsum_*` | `f32x4`, `f32x8`, `f64x2`, `f64x4`, `i32x4`, `i32x8`, `u32x4` |
| `simd_hmin_*` / `simd_hmax_*` | `f32x4` |

For anything else, use the native `vec<T, N>` operators directly (see [Native SIMD](/reference/simd)).

```c
#include <std/simd/simd.h>

float dot4(const &float a, const &float b) {
    f32x4 va = std::simd_load_f32x4(a);
    f32x4 vb = std::simd_load_f32x4(b);
    f32x4 acc = std::simd_fma_f32x4(va, vb, std::simd_splat_f32x4(0.0f));
    return std::simd_hsum_f32x4(acc);
}
```

## CPU SIMD levels — `cpu.h` {#cpu-levels}

```c
#include <std/simd/cpu.h>
```

std's hot kernels — [`sgemm`](#gemm), [`vmath.h`](#vmath), the FFT butterflies and `dsp_dot_f64` — are built in several versions and use the best one the CPU supports, in this order:

| Family | Order | Chosen |
|---|---|---|
| x86-64 | AVX-512 (F+BW+DQ+VL) > AVX2 + FMA > AVX > SSE4.1 > SSE2 | at run time (`cpuid` + `xgetbv`), once |
| AArch64 | SVE > Neon | at run time on Linux (`getauxval`), else Neon |
| Cortex-M | Helium (MVE) > DSP extension | at compile time, by target: `arm-81m` for Helium, `arm-7em` / `arm-8m` for DSP |
| WebAssembly | SIMD128 | at compile time |

So one x86-64 binary built for the portable baseline uses AVX-512 where the CPU has it and still runs on an SSE2-only machine. Whatever the build already guarantees (for instance `--target-cpu=native`, which defines `__AVX2__` etc.; see [ISA macros](/reference/simd#isa-macros-and-target-cpu)) is the floor. Each version is the same source compiled with [`__attribute__((target(...)))`](/reference/c-superset#__attribute__).

```c
#define SIMD_SSE2 1   // ... SIMD_SSE41 2, SIMD_AVX 3, SIMD_AVX2 4, SIMD_AVX512 5,
                      // SIMD_NEON 10, SIMD_SVE 11, SIMD_DSP 20, SIMD_HELIUM 21, SIMD_WASM128 30
int simd_level();                     // detected once, then cached
const &char simd_level_name(int level);   // "AVX-512", "Neon", "Helium", ...
```

`SAFEC_SIMD=sse2|sse4.1|avx|avx2|avx512|neon|sve` caps the level, to test or compare the slower paths.

## Matrix multiply — `gemm.h` {#gemm}

```c
#include <std/simd/gemm.h>
```

Single-precision GEMM for the CPU. A and B are packed into contiguous strips and multiplied by a register-tile kernel: 4 × 16 on `vec<float, 8>` (SSE2, AVX, AVX2 + FMA builds of one body) or 8 × 16 on `vec<float, 16>` for AVX-512. C is cut into tiles that run on the [thread pool](/stdlib/sync#parallel) once a product has about a million multiply-adds.

When the program links a CPU vendor BLAS, its kernels do the work: **AOCL-BLAS** on AMD, **oneMKL** on Intel, **Accelerate** on Apple. safeguard's [`[build] blas`](/advanced/safeguard#linking-external-libraries-and-library-output) links the right one; by hand, link `-lblis-mt -lgomp`, oneMKL's `mkl_intel_lp64 mkl_gnu_thread mkl_core -lgomp`, or `-framework Accelerate`. `sgemm` reaches `cblas_sgemm` through a weak reference, so the choice is made at link time. Except with Accelerate, std runs the library on one thread and splits C across its own pool, one library call per tile. The libraries' OpenMP workers busy-wait between calls, and on the same cores as the pool that made a U-Net forward pass six times slower. This setting also makes the library single-threaded for the program's own direct calls into it.

```c
#define SGEMM_NO_TRANS 0
#define SGEMM_TRANS    1

// C = alpha * op(A) * op(B) + beta * C, row-major; op(A) is m x k, op(B) is k x n.
// 'ld' = elements between the starts of two rows. beta == 0 never reads C.
void sgemm(int transA, int transB, unsigned long m, unsigned long n, unsigned long k,
           float alpha, const &float a, unsigned long lda,
           const &float b, unsigned long ldb,
           float beta, &float c, unsigned long ldc);

// Same, always on the calling thread (no pool; a vendor library, if linked,
// runs single-threaded): for code that is already parallel.
void sgemm_serial(/* same parameters */);

int sgemm_vendor();            // 1 if a vendor BLAS is in use
const &char sgemm_backend();   // "AOCL-BLAS (BLIS)", "oneMKL", "Accelerate", "OpenBLAS", "CBLAS", "built-in"
```

`tensor_matmul` (forward and backward), `Conv2D::forward` and `half_matmul` are built on it.

Throughput on a Ryzen 7 7800X3D (Zen 4, 8 cores / 16 threads), N × N × N, from one baseline x86-64 build:

| Kernel | 1 thread, N = 1024 | 16 threads, N = 512 | 16 threads, N = 1024 |
|---|---|---|---|
| built-in, SSE2 (`SAFEC_SIMD=sse2`) | 35 GFLOP/s | 252 GFLOP/s | 239 GFLOP/s |
| built-in, AVX | 86 GFLOP/s | 575 GFLOP/s | 614 GFLOP/s |
| built-in, AVX2 + FMA | 100 GFLOP/s | 575 GFLOP/s | 614 GFLOP/s |
| built-in, AVX-512 (picked on this CPU) | 105 GFLOP/s | 671 GFLOP/s | 716 GFLOP/s |
| AOCL-BLAS 5.3, tiles on std's pool | 134 GFLOP/s | 1007 GFLOP/s | 1074 GFLOP/s |
| oneMKL 2026.2, tiles on std's pool | 130 GFLOP/s | 671 GFLOP/s | 716 GFLOP/s |

## Vector math — `vmath.h` {#vmath}

```c
#include <std/simd/vmath.h>
void vm_exp_f32(const &float x, &float y, unsigned long n);
void vm_tanh_f32(const &float x, &float y, unsigned long n);
void vm_sigmoid_f32(const &float x, &float y, unsigned long n);
```

`y[i] = f(x[i])` as branch-free polynomial code (Cephes-style range reduction), vectorized at the CPU's [SIMD level](#cpu-levels) and split across the thread pool from 32,768 elements. Measured error is under 1 ulp for exp, 1.3 ulp for tanh and 2 ulp for sigmoid, subnormal results included. NaN propagates and overflow gives infinity. About 0.2 ns per element on the 7800X3D. `x` and `y` may be the same array. std's activations (sigmoid, tanh, GELU, SiLU, ELU, GLU, SwiGLU) and softmax use it.

## Per-ISA Convenience Headers {#per-isa-convenience-headers}

Eight thin headers re-export the same portable types under
architecture-idiomatic names — each is pure typedefs and documentation,
containing no separate logic, and each has been verified against real
generated code for its target (disassembled `llc` output, not just
"compiles"):

| Header | Target | Native mapping |
|---|---|---|
| `std/simd/x86_64.h` | x86_64 | `m128`/`m128i`/`m128d` (SSE/SSE2), `m256`/`m256i`/`m256d` (AVX/AVX2) |
| `std/simd/aarch64.h` | AArch64 | 128-bit NEON registers |
| `std/simd/riscv.h` | RISC-V (`+v`) | 128-bit RVV register groups (baseline `zve*` width; a true *scalable* vector length isn't modeled — see the header for why) |
| `std/simd/wasm.h` | WebAssembly | 128-bit `v128` (SIMD128 proposal) |
| `std/simd/spirv.h` | SPIR-V | Real `OpTypeVector`/`OpFAdd` in a compute kernel body — see the header's caveat about SPIR-V's no-host-libc execution model |
| `std/simd/cortex_m.h` | ARM Cortex-M | MVE (M55/M85) type aliases + the DSP-extension `dsp_*` functions (M4/M7) — see [Bare-Metal](/reference/baremetal#dsp-extension-cortex-m4-m7) |
| `std/simd/cuda.h` | CUDA (NVPTX) | Real PTX vector-lane codegen — GPU scalarizes to N scalar ops, see caveat below |
| `std/simd/rocm.h` | ROCm (AMDGPU) | Real GCN vector-ALU codegen — same scalarization caveat |

### Cortex-M DSP intrinsics {#cortex-m-dsp-intrinsics}

On Cortex-M4/M7 (`+dsp`) the packed-SIMD instructions work on 2×16-bit or 4×8-bit lanes inside one 32-bit register, and LLVM does not vectorize `vec<T, N>` into them. `std/simd/cortex_m.h` therefore exposes them as functions, each compiling to the single named instruction:

| Function | Instruction |
|---|---|
| `dsp_qadd(a, b)` / `dsp_qsub(a, b)` | saturating 32-bit add / subtract |
| `dsp_qadd16`, `dsp_qsub16`, `dsp_qadd8`, `dsp_qsub8` | saturating signed add / subtract per 16-bit or 8-bit lane |
| `dsp_uqadd16`, `dsp_uqsub16`, `dsp_uqadd8`, `dsp_uqsub8` | saturating unsigned, per lane |
| `dsp_sadd16`, `dsp_ssub16`, `dsp_sadd8`, `dsp_ssub8` | wrapping signed, per lane |
| `dsp_smlad(a, b, acc)` / `dsp_smladx` | dual 16×16 multiply, add both products to `acc` (`x`: halves of `b` swapped) |
| `dsp_smlsd(a, b, acc)` / `dsp_smlsdx` | dual multiply, add the difference of the products to `acc` |
| `dsp_smuad(a, b)` / `dsp_smuadx` | dual multiply, sum of products |
| `dsp_smusd(a, b)` / `dsp_smusdx` | dual multiply, difference of products |
| `dsp_usad8(a, b)` / `dsp_usada8(a, b, acc)` | sum of absolute differences of four bytes (plus `acc`) |
| `dsp_sxtab16(a, b)` / `dsp_uxtab16(a, b)` | sign / zero-extend two bytes of `b` to 16 bits and add to `a`'s halves |

Signed variants take and return `int`; `uq*`, `usad8`, `usada8` and `uxtab16` use `unsigned int`. On Cortex-M55/M85 (`+mve`), `mve_f32x4`, `mve_i32x4`, `mve_i16x8` and `mve_i8x16` are native 128-bit vectors. Cortex-M0/M0+/M3/M23 have neither; `vec<T, N>` still works there but is scalarized.

### GPU targets: SIMT, not SIMD-in-one-instruction {#gpu-targets-simt-not-simd-in-one-instruction}

PTX and GCN have no packed-arithmetic instruction the way SSE/NEON do — a
GPU's parallelism comes from running many threads in lockstep, not wide
registers within one thread. `vec<float,4> + vec<float,4>` still compiles
correctly on CUDA/ROCm targets, it just becomes four independent scalar
ops rather than one wide instruction (confirmed: real generated PTX shows
four `add.rn.f32` instructions, real GCN shows four `v_add_f32_e32`
instructions). `std::simd`'s CUDA/ROCm headers only provide the portable
arithmetic types usable *within* a kernel body (device memory, no host
libc) — they don't define kernel entry points (`__global__`/
`ptx_kernel`/`amdgpu_kernel` calling convention plus launch-configuration
support is a separate concern this library doesn't address).

### Metal Shading Language {#metal-shading-language}

Not supported. Apple's Metal compiler has no LLVM backend upstream —
unlike NVPTX/AMDGPU/SPIR-V, which are real LLVM targets `--target` can
select, Metal is a separate, closed toolchain. The only interop path from
this library's SPIR-V output is a third-party translator (e.g.
SPIRV-Cross), not something `safec` or `std::simd` does directly.
