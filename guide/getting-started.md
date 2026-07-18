---
title: Getting Started
---

# Getting Started

This guide walks you through installing SafeC, compiling your first program, and understanding the basic compilation workflow.

## Installation

Run the install script. It downloads a prebuilt `safec`/`safeguard`/`sc-lsp` release from GitHub Releases and configures your shell environment — no LLVM or C++ toolchain needed on the machine you're installing to.

```bash
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash
```
```powershell
irm https://raw.githubusercontent.com/safec-org/SafeC/main/install.ps1 | iex
```

### Install Script Options

| Option | Description |
|---|---|
| `--prefix=<path>` | Install directory (default: `~/safec`) |
| `--version=<tag>` | Release tag to install, e.g. `v0.2.0` (default: latest) |
| `--skip-env` | Skip shell environment configuration |

macOS/Linux publish `arm64`/`x86_64` binaries; Windows publishes `x86_64`. See [Releases](https://github.com/safec-org/SafeC/releases) for the full asset list.

::: tip
Building from source (needed for a platform the release workflow doesn't publish, or to build from an unreleased commit) is still possible via each component's own `CMakeLists.txt` under `compiler/`, `safeguard/`, and the sibling `sc-language-server` repo — see [Compiler Architecture](/advanced/compiler).
:::

Verify the install:

```bash
safec --help
```

## Your First Program

Create a new project with `safeguard`:

```bash
safeguard new hello
cd hello
```

This generates the following structure:

```
hello/
  Package.toml
  src/
    main.sc
```

Replace `src/main.sc` with:

```c
extern int printf(const char* fmt, ...);

int main() {
    printf("Hello from SafeC!\n");
    return 0;
}
```

Build and run:

```bash
safeguard build
safeguard run
```

Output:

```
Hello from SafeC!
```

## A More Complete Example

This example demonstrates regions, references, arrays, and slices:

```c
extern int printf(const char* fmt, ...);

int main() {
    // Stack variable with explicit region reference
    int x = 42;
    &stack int ref = &x;
    printf("x = %d\n", *ref);

    // Array with bounds checking
    int arr[5];
    arr[0] = 10;
    arr[1] = 20;
    arr[2] = 30;

    // Slice — a bounds-checked view into the array
    []int s = arr[0..3];
    printf("slice length = %ld\n", s.len);

    return 0;
}
```

## Compilation Pipeline

The SafeC compiler processes source files through a multi-stage pipeline:

```
Source (.sc)
    |
    v
Preprocessor     #define, #include, #ifdef, -D/-I flags
    |
    v
Lexer            Tokenization
    |
    v
Parser           Recursive-descent parsing -> AST
    |
    v
Sema             Type checking, region analysis, borrow checking
    |
    v
ConstEval        Compile-time function evaluation, static_assert
    |
    v
CodeGen          LLVM IR generation
    |
    v
LLVM IR (.ll)    Text IR or bitcode output
    |
    v
clang/lld        Link to native binary
```

## Compiler Flags

```
safec <input.sc> [options]
```

### Output

| Flag | Description |
|---|---|
| `-o <file>` | Output file path |
| `--emit-llvm` | Emit LLVM IR as text (`.ll`) |

### Debug and Diagnostics

| Flag | Description |
|---|---|
| `--dump-ast` | Print the AST and exit |
| `--dump-pp` | Print preprocessed source and exit |
| `--g lines` | Emit line-table debug info (DWARF) |
| `--g full` | Emit full debug info with local variables |
| `-v` | Verbose output |

### Compilation Control

| Flag | Description |
|---|---|
| `--no-sema` | Skip semantic analysis |
| `--no-consteval` | Skip compile-time evaluation pass |
| `--compat-preprocessor` | Enable full C preprocessor compatibility |
| `--freestanding` | Freestanding mode (no standard library assumptions) |
| `--no-import-c-headers` | Disable automatic C header import |

### Preprocessor

| Flag | Description |
|---|---|
| `-I <dir>` | Add include search path |
| `-D NAME[=VALUE]` | Define a preprocessor macro |

### Incremental Compilation

Incremental compilation (per-file `.bc` caching) is **on by default** — no flag needed to enable it.

| Flag | Description |
|---|---|
| `--no-incremental` | Disable incremental compilation (always recompile from scratch) |
| `--cache-dir <dir>` | Set cache directory (default: `.safec_cache`) |
| `--clear-cache` | Clear all cached `.bc` files and exit |

## C Header Import

SafeC can natively include C standard headers. The compiler parses C header declarations and makes them available without manual `extern` stubs:

```c
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main() {
    char* msg = (char*)malloc(64);
    strcpy(msg, "Hello from C headers!");
    printf("%s\n", msg);
    free(msg);
    return 0;
}
```

The automatic import works by invoking `clang -ast-dump=json` on the included header and extracting function and typedef declarations. Disable it with `--no-import-c-headers` if you prefer manual `extern` declarations.

## Using the Package Manager

For projects with dependencies or multiple source files, use `safeguard`. The install script installs it alongside `safec` and `sc-lsp`, so it's already available.

```bash
# Create a new project
safeguard new myproject
cd myproject

# Build and run
safeguard build
safeguard run
```

The project structure created by `safeguard new`:

```
myproject/
  Package.toml       # Project manifest
  src/
    main.sc          # Entry point
```

The install script already sets `SAFEC_HOME` (to the install prefix, `~/safec` by default) and adds it to `PATH` so `safeguard` can locate the compiler and standard library automatically — restart your shell, or `source` your shell rc file, after installing. If you skipped that step (`--skip-env`) or moved the install elsewhere, set it manually:

```bash
export SAFEC_HOME=/path/to/safec
export PATH="$SAFEC_HOME/bin:$PATH"
```

## Linking

SafeC compiles to LLVM IR. The final linking step uses your system's C toolchain:

```bash
# Basic linking
clang hello.ll -o hello

# With pthreads (for spawn/join)
clang concurrency.ll -lpthread -o concurrency

# With math library
clang math_demo.ll -lm -o math_demo
```

## Editor Support

An LSP server is available for `.sc` files, providing:

- Diagnostics (error highlighting)
- Hover information
- Code completion
- Go-to-definition
- Document symbols

A VS Code extension is included in the `editors/vscode/` directory of the LSP server repository.

## Next Steps

- [Design Philosophy](/guide/design) — Understand why SafeC makes the choices it does
- [Types](/reference/types) — Learn about SafeC's type system
- [Memory and Regions](/reference/memory) — Understand region-based memory safety
