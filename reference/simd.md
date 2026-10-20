# Native SIMD (`vec<T, N>`)

`vec<T, N>` is a first-class language type — a fixed-width vector of `N`
elements of type `T` — that lowers directly to LLVM's `<N x T>` vector IR.
There is no intrinsic-based API to learn for basic use: ordinary
arithmetic operators and subscript syntax already work.

```c
vec<float, 4> a = {1.0, 2.0, 3.0, 4.0};
vec<float, 4> b = {10.0, 20.0, 30.0, 40.0};
vec<float, 4> c = a + b;      // element-wise add
vec<float, 4> d = c * b;      // element-wise multiply

float first = c[0];           // extractelement — reads one lane
c[2] = 999.0;                 // insertelement — writes one lane
```

`c[0]` is `float` (the vector's element type) — assigning it to a `double`
widens implicitly, the same safe smaller-to-bigger conversion that applies
to every other numeric type (see [Types](/reference/types#type-conversions)):
`double first = c[0];` compiles and reads back the exact value, no cast
needed.

`T` can be any integer or floating-point primitive; `N` must be a
compile-time constant. There's no restriction to "nice" hardware widths —
`vec<int, 3>` is legal — but the per-ISA convenience layers in
[`std::simd`](/stdlib/simd) pick widths that map cleanly onto real vector
registers (128-bit SSE/NEON/SIMD128, 256-bit AVX2, ...).

## What compiles to what

Nothing architecture-specific is hardcoded in the compiler — `vec<T,N>`
lowers to portable LLVM vector IR, and each target's own backend does
instruction selection from there, exactly like scalar arithmetic does.
Verified against real generated code across every backend the compiler
supports:

| Target | What `vec<float,4> + vec<float,4>` becomes |
|---|---|
| x86_64 (SSE2 baseline) | `addps` (single instruction) |
| AArch64 / 32-bit ARM (NEON) | `fadd v0.4s, v0.4s, v1.4s` |
| RISC-V (`+v` extension) | `vsetivli` + `vfadd.vv` |
| WebAssembly (SIMD128) | `f32x4.add` |
| SPIR-V | `OpFAdd` on `%v4float` |
| ARM Cortex-M55/M85 (MVE, `+mve`) | `vadd.i32 q2, q0, q1` |
| CUDA (NVPTX) / ROCm (AMDGPU) | 4 scalar lane ops — see note below |

**GPU targets scalarize.** PTX and GCN have no "add 4 packed floats in one
instruction" the way SSE/NEON do — a GPU's parallelism comes from running
many threads in lockstep (a warp/wavefront), not from wide registers
within one thread. `vec<float,4>` still works correctly there, it just
compiles to four independent scalar ops rather than one wide one; it's a
convenient way to group related values, not a promise of single-
instruction throughput on every target.

**ARM Cortex-M4/M7's DSP extension is a separate story.** Its packed-SIMD
instructions (`SADD16`, `SMLAD`, `USAD8`, ...) pack 2×16-bit or 4×8-bit
lanes into a single 32-bit *scalar* register — a fundamentally different
model from a real vector register file, and LLVM does not auto-vectorize
`vec<T,N>` IR into them. They're exposed separately as `std::dsp_*`
functions — see [Bare-Metal](/reference/baremetal#dsp-extension-cortex-m4-m7).

## Compound initializers

Vector initializers accept exactly `N` positional values — no designators
(unlike struct/array compound initializers):

```c
vec<int, 8> ints = {1, 2, 3, 4, 5, 6, 7, 8};
```

A global vector's initializer must still be a compile-time constant, same
as any other global:

```c
vec<float, 4> global_v = {10.0, 20.0, 30.0, 40.0};   // fine — literals fold
```

## Subscript semantics

Reading `v[i]` on a constant index is bounds-checked at compile time (an
out-of-range constant index is a compile error); a runtime index is
bounds-checked like any other indexing operation, unless inside
`unsafe { }`. A single lane isn't independently addressable in memory the
way an array element is — `v[i]` lowers to `extractelement`/
`insertelement` against the whole SSA-resident vector value, not a pointer
computation.

## Loads and stores through pointers

Dereferencing a `vec<T, N>` pointer reads or writes all `N` lanes at once:

```c
unsafe {
    *(vec<float, 8>*)&y[i] = *(vec<float, 8>*)&y[i] + a * *(const vec<float, 8>*)&x[i];
}
```

Such a pointer usually comes from an element address inside an array, which is only aligned to the element. So the access assumes the element's alignment (4 bytes here), not the vector's own (32), and compiles to an unaligned vector load or store. Vector locals and globals, which the compiler lays out itself, keep the full alignment.

## Built-ins {#builtins}

| Built-in | Types | Lowers to |
|---|---|---|
| `__builtin_fmuladd(a, b, c)` | `float`, `double`, `vec<float/double, N>` (all three the same type) | `a*b + c` as one fused multiply-add where the target has FMA, else a multiply and an add (`llvm.fmuladd`) |
| `__builtin_sqrt(x)` | `float`, `double`, `vec<float/double, N>` | IEEE square root (`llvm.sqrt`); one instruction with hardware sqrt, and vectorizable inside loops (unlike a call to `sqrtf`) |

Plain `a * b + c` is never contracted into an FMA, because that changes the rounding. Use `__builtin_fmuladd` where fusing is acceptable, as in dot products and matrix kernels.

## ISA macros and `--target-cpu`

The compiler defines clang's SIMD feature macros from what the target CPU and `--target-features` guarantee:

| Target | Macros |
|---|---|
| x86 / x86-64 | `__SSE2__`, `__SSE3__`, `__SSSE3__`, `__SSE4_1__`, `__SSE4_2__`, `__AVX__`, `__AVX2__`, `__FMA__`, `__F16C__`, `__AVX512F__`, `__AVX512BW__`, `__AVX512DQ__`, `__AVX512VL__` |
| AArch64 | `__ARM_NEON`, `__ARM_FEATURE_SVE`, `__ARM_FEATURE_SVE2`, `__ARM_FEATURE_FP16_VECTOR_ARITHMETIC` |
| 32-bit ARM | `__ARM_NEON`, `__ARM_FEATURE_DSP` (Cortex-M4/M7/M33), `__ARM_FEATURE_MVE` (Helium: 1 = integer, 3 = integer and float) |
| WebAssembly | `__wasm_simd128__` |

`--target-cpu=native` builds for the build machine's CPU with every feature it has, like clang's `-march=native`; `--target-cpu=znver4`, `--target-cpu=skylake-avx512` and so on name one. Without either, x86-64 code targets the baseline (SSE2), and std's hot kernels pick their ISA level at run time instead (see [CPU SIMD levels](/stdlib/simd#cpu-levels)).

## Relationship to `std::simd`

`vec<T,N>` is the compiler-level building block. [`std::simd`](/stdlib/simd)
is a portable library built entirely on top of it — type aliases
(`f32x4`, `i32x8`, ...), load/store between a vector and a raw pointer,
broadcast, horizontal reductions, and fused multiply-add — plus thin,
per-ISA convenience headers (`x86_64.h`, `aarch64.h`, `riscv.h`, `wasm.h`,
`spirv.h`, `cortex_m.h`, `cuda.h`, `rocm.h`) that just re-export those same
types under architecture-idiomatic names. There is no separate,
hand-written SIMD implementation per target — the whole library is the
same portable source compiled once per backend.
