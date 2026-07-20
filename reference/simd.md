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
