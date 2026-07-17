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

    unsafe {
        f32x4 v = simd_load_f32x4(src);
        f32x4 doubled = v + v;
        simd_store_f32x4(doubled, dst);

        float total = simd_hsum_f32x4(v);   // horizontal sum
        f32x4 a = simd_splat_f32x4(2.0);    // broadcast
        f32x4 fma = simd_fma_f32x4(a, a, v); // a*a + v
    }
    return 0;
}
```

Load/store take a raw pointer, so they require `unsafe { }` at the call
site like any other raw-pointer access in SafeC.

## Type Aliases

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

## Functions

| Function | Description |
|---|---|
| `simd_load_<type>(const T* p)` | Load from a raw pointer — unaligned by construction, safe on any pointer |
| `simd_store_<type>(v, T* p)` | Store to a raw pointer |
| `simd_splat_<type>(x)` | Broadcast a scalar to every lane |
| `simd_fma_<type>(a, b, c)` | Fused multiply-add: `a*b + c` |
| `simd_min_<type>(a, b)` / `simd_max_<type>(a, b)` | Elementwise min/max |
| `simd_hsum_<type>(v)` | Horizontal sum — reduce all lanes to one scalar |
| `simd_hmin_<type>(v)` / `simd_hmax_<type>(v)` | Horizontal min/max |

Not every function is defined for every type alias — see `std/simd/simd.h`
for the exact set (e.g. `simd_hmin`/`simd_hmax` are float-only; integer
types have `simd_hsum` but not the horizontal min/max variants).

## Per-ISA Convenience Headers

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

### GPU targets: SIMT, not SIMD-in-one-instruction

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

### Metal Shading Language

Not supported. Apple's Metal compiler has no LLVM backend upstream —
unlike NVPTX/AMDGPU/SPIR-V, which are real LLVM targets `--target` can
select, Metal is a separate, closed toolchain. The only interop path from
this library's SPIR-V output is a third-party translator (e.g.
SPIRV-Cross), not something `safec` or `std::simd` does directly.
