# Compiler Architecture

SafeC compiles `.sc` source files to native executables via LLVM IR. The compiler is a single-pass frontend with no runtime injection — what you write is what gets compiled.

## Pipeline

```
Source (.sc)
    |
Preprocessor --- #include, #define, #ifdef, #pragma once
    |
Lexer --- tokenization with SafeC keyword set
    |
Parser --- recursive-descent, C grammar + SafeC extensions
    |
AST
    |
Semantic Analysis --- name resolution, type checking, region escape analysis
    |
Const-Eval Engine --- evaluate const/consteval at compile time
    |
Code Generation --- LLVM IR with region metadata (noalias, nonnull, etc.)
    |
LLVM Backend --- object code / bitcode
```

No runtime injection phase. No implicit transformation inserting hidden allocations.

## Key Design Decisions

**References lower to raw pointers.** A `&stack T` or `&heap T` becomes a plain LLVM `ptr` annotated with `noalias`, `nonnull`, and `dereferenceable` attributes. There is no fat pointer, no reference counting, no hidden indirection.

**Regions are compile-time only.** Region annotations (`&arena<R> T`) drive escape analysis and borrow checking during semantic analysis. Zero region metadata survives into the generated IR.

**Const globals are folded.** The const-eval engine evaluates `const` and `consteval` initializers at compile time. The results are written back as literal IR constants — no runtime initialization code is emitted.

**`if const` is dead-code-eliminated.** Branches guarded by `if const` are resolved during const-eval. Only the taken branch survives into codegen.

**`consteval` is enforced.** A `consteval` function called in a runtime context is a compile error, not a silent fallback.

**Struct layout follows C ABI.** SafeC structs are layout-compatible with C structs. No vtable, no hidden fields, no padding surprises.

## Source Layout

```
compiler/
├── include/safec/
│   ├── Token.h          Keyword and token definitions
│   ├── Lexer.h          Tokenizer interface
│   ├── AST.h            All AST node types
│   ├── Type.h           Type representation (primitives, references, regions)
│   ├── Parser.h         Recursive-descent parser
│   ├── Sema.h           Semantic analysis (scope, types, borrow checking)
│   ├── ConstEval.h      Compile-time evaluation engine
│   ├── CodeGen.h        LLVM IR generation
│   ├── Preprocessor.h   Macro expansion and conditional compilation
│   ├── CHeaderImporter.h  Native C header import via clang AST
│   ├── Clone.h          AST deep-clone for generics monomorphization
│   └── Diagnostic.h     Error/warning reporting
├── src/
│   ├── Preprocessor.cpp
│   ├── CHeaderImporter.cpp
│   ├── Lexer.cpp
│   ├── AST.cpp
│   ├── Type.cpp
│   ├── Parser.cpp
│   ├── Sema.cpp
│   ├── Clone.cpp
│   ├── ConstEval.cpp
│   ├── CodeGen.cpp
│   └── main.cpp
├── examples/            demo files
└── CMakeLists.txt
```

## Building the Compiler

SafeC requires LLVM 21+ and CMake:

```bash
cd compiler
cmake -S . -B build \
  -DLLVM_DIR=/path/to/llvm/lib/cmake/llvm
cmake --build build
```

The resulting binary is `build/safec`.

## Usage

```bash
# Compile to executable (via LLVM IR + clang link) — the two-step form
./build/safec input.sc --emit-llvm -o input.ll
clang input.ll -o input

# Or in one step, via --emit-bin (see "Linking" below)
./build/safec input.sc --emit-bin -o input

# Dump the AST
./build/safec input.sc --dump-ast

# Disable the (on-by-default) incremental bitcode cache
./build/safec input.sc --no-incremental

# Debug info
./build/safec input.sc --g lines    # line-level debug info
./build/safec input.sc --g full     # full variable-level debug info

# Cross-compile to another architecture/OS
./build/safec input.sc --target aarch64-unknown-linux-gnu --emit-llvm -o input.ll

# Print the compiler version
./build/safec --version
```

## Linking (`--emit-bin`)

`safec`'s own pipeline stops at LLVM IR/bitcode — it never links on its
own. `--emit-bin` adds a genuine "compile straight to a native
binary/library" mode: it writes the module to a temporary `.ll` file, then
invokes the system `clang` (assemble + link) or `ar` (for a static
archive) to produce the file at `-o`. This is the same tool-shelling
approach [`safeguard`](/advanced/safeguard) already uses per source file —
`--emit-bin` just makes it available directly from `safec` for a single
`.sc` file, without going through a project/`Package.toml`.

```bash
# Link a plain executable
./build/safec main.sc --emit-bin -o main

# Link against an external library: -l<name> / -L<dir>, same convention as
# clang/gcc. Calling into a C library needs a matching 'extern' declaration
# on the SafeC side (see C Interop); calling into C++/Objective-C needs
# 'extern "C"' on *their* side to avoid name mangling — SafeC itself has no
# name mangling to opt out of, since every top-level function already has
# a plain C-compatible symbol name.
./build/safec main.sc --emit-bin -o main -lm -L/usr/local/lib

# Produce a shared/dynamic library instead of an executable
./build/safec mathlib.sc --emit-bin --shared -o libmath.dylib

# Produce a static library (an 'ar' archive of the compiled object — no
# linker, -l/-L are meaningless here since a .a isn't linked, just packaged)
./build/safec mathlib.sc --emit-bin --static-lib -o libmath.a

# Enable LTO for the link step (thin is the default; full is also available)
./build/safec main.sc --emit-bin --lto -o main
./build/safec main.sc --emit-bin --lto=full -o main

# Release profile: -O2 unless -O was given explicitly
./build/safec main.sc --emit-bin --release -o main
```

`-l`/`-L`/`--shared`/`--static-lib`/`--lto` are only meaningful with
`--emit-bin` — passing them without it prints a warning and has no effect,
since there's no link step for them to configure. `SAFEC_CLANG`/`SAFEC_AR`
environment variables override the discovered `clang`/`ar` path, mirroring
`safeguard`'s `SAFEC_CLANG`/`SAFEC_CLANGXX`.

## Multi-Target Codegen

`--target <triple>` cross-compiles to any LLVM-registered target — the
compiler hardcodes nothing architecture-specific; `--target` selects an
LLVM `TargetMachine`, which supplies both the data layout (used live
during codegen for `sizeof`/struct layout/alignment) and the instruction
selection a later `llc`/`clang -c` step uses to turn the emitted IR into
real machine code. Omit the flag to target the host, unchanged from
before this flag existed.

Verified with real generated machine code (not merely accepted input)
across:

| OS | Architectures |
|---|---|
| macOS | x86_64, AArch64 |
| Linux | x86_64, x86, AArch64, Aarch32 (ARMv7), RV64, RV32 |
| Windows (MSVC) | x86_64, x86, AArch64 |
| iOS | AArch64 (device + simulator) |
| Android | AArch64, Aarch32, x86_64, x86 |
| FreeBSD | x86_64, AArch64 |
| Bare metal (`--freestanding`) | ARM Cortex-M (Thumb/Thumb2), RV32, RV64, AArch64 |
| Portable / GPU | WebAssembly, SPIR-V, CUDA (NVPTX), ROCm (AMDGPU) |

Metal Shading Language is not reachable via `--target` — Apple's Metal
compiler has no LLVM backend upstream, unlike NVPTX/AMDGPU/SPIR-V, which
are real LLVM targets. The only interop path from SPIR-V output is a
third-party translator (e.g. SPIRV-Cross), not something `safec` does.

See [`std::simd`](/stdlib/simd) for the SIMD library built on top of this,
and [Bare-Metal](/reference/baremetal) for ARM Cortex-M specifics (HAL,
DSP-extension intrinsics, MVE).

## Incremental Compilation

The file-level bitcode cache is **on by default** (optional `--cache-dir <dir>`, defaulting to `.safec_cache`; disable with `--no-incremental`). The compiler hashes the preprocessed source together with the compiler binary's own identity (so a rebuilt compiler always misses stale entries) and every codegen-affecting flag — `--target`, `-g`, `--freestanding` — using FNV-1a. If a cached `.bc` file with a matching hash exists, all pipeline stages are skipped.

On a cache miss, the full pipeline runs and the resulting bitcode is written to the cache directory.

Use `--clear-cache` to delete all cached `.bc` files.

## Debug Info

SafeC supports two levels of DWARF debug information:

| Flag | What it emits |
|------|--------------|
| `--g lines` | `DICompileUnit` + `DIFile` + `DISubprogram` per function + `DILocation` per statement |
| `--g full` | Everything in `lines` + `DILocalVariable` + `dbg.declare` per local variable |

No `--g` flag means no debug metadata is emitted.

## Native C Header Import

`#include <stdio.h>` works natively in SafeC. The `CHeaderImporter` module invokes `clang -ast-dump=json` on the included header, then extracts `FunctionDecl` and `TypedefDecl` nodes into SafeC's AST.

Unsupported constructs (function pointers, ObjC blocks, `long double`, `__int128`) are silently skipped. Enum typedefs are lowered to `typedef int name`.

This can be disabled with `--no-import-c-headers`.

## Generics Monomorphization

Generic functions (`generic<T>`) are monomorphized at compile time. When the compiler encounters a call to a generic function, it:

1. Infers the type argument `T` from the call-site argument types
2. Deep-clones the function AST via `Clone.cpp`, substituting `T` with the concrete type
3. Appends the monomorphized function (mangled as `__safec_fn_type`) to the translation unit
4. Runs semantic analysis and codegen on the monomorphized copy

Generic function bodies are skipped during the first semantic analysis pass — they are only analyzed after monomorphization.

## Const-Eval Engine

The const-eval engine is a tree-walking interpreter that runs at compile time. It handles:

- `const` global initializers
- `consteval` function bodies
- `static_assert` verification
- `if const` branch selection

Evaluated results are folded back into the AST as `IntLit` or `FloatLit` nodes, so codegen sees only literal constants.
