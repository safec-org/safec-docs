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
│   └── Clone.h          AST deep-clone for generics monomorphization
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
├── examples/            16 demo files
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
# Compile to executable (via LLVM IR + clang link)
./build/safec input.sc --emit-llvm -o input.ll
clang input.ll -o input

# Dump the AST
./build/safec input.sc --dump-ast

# Incremental compilation
./build/safec input.sc --incremental

# Debug info
./build/safec input.sc --g lines    # line-level debug info
./build/safec input.sc --g full     # full variable-level debug info
```

## Incremental Compilation

When `--incremental` is passed (with optional `--cache-dir <dir>`, defaulting to `.safec_cache`), the compiler hashes the preprocessed source using FNV-1a. If a cached `.bc` file with a matching hash exists, all pipeline stages are skipped.

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
