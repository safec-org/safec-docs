# Package Manager

`safeguard` is the official SafeC package manager and build system. It is a standalone C++17 binary with no runtime dependencies beyond a working `safec` compiler and `clang` linker.

## Commands

| Command | Description |
|---------|-------------|
| `safeguard new <name>` | Scaffold a new project directory with `Package.toml` and `src/main.sc` |
| `safeguard init` | Initialize a `Package.toml` in the current directory |
| `safeguard fetch` | Download all dependencies listed in `Package.toml` |
| `safeguard build [--release]` | Compile the project and all dependencies |
| `safeguard check` | Fast compile-only pass — reports errors without assembling objects or linking |
| `safeguard test` | Build and run every file under `tests/` as an independent binary |
| `safeguard run` | Build and execute the project binary |
| `safeguard clean` | Remove the `build/` directory and all artifacts |
| `safeguard verify-lock` | Check `Package.lock` against current dependency state |
| `safeguard lint [--verbose]` | Run static-analysis lint passes on all `.sc` source files |
| `safeguard format [--check]` | Reindent and whitespace-normalize all `.sc`/`.h` source files |

## `check` — fast compile-only feedback

Like Rust's `cargo check`: runs every source file through its full front
end (for `.sc`, `safec`'s Preprocessor→Lexer→Parser→Sema→ConstEval — so
every type error, borrow-check violation, and region-escape error is
still caught; for `.c`/`.cpp`, `clang -fsyntax-only`) but skips assembling
object files and skips linking entirely, and never builds the standard
library archive — `check` only needs `std/`'s *headers* on the include
path, not the linkable implementation. This is normally the majority of
`build`'s wall time, so `check` is the fast inner-loop command for "did I
break anything," with `build`/`run` reserved for when you actually need a
binary.

## `test` — integration tests

`tests/` is to `safeguard test` what Rust's `tests/` directory is to
`cargo test`: each file is an independent, standalone program with its
own `main()` — not a set of `#[test]`-annotated functions extracted from
one binary, since SafeC has no such attribute. Every file under `tests/`
is built (linked against the same stdlib and `[[dependencies]]` as the
main project) and run; a test passes if its binary exits `0`. This
composes naturally with [`std::test`](/stdlib/testing)'s `TestSuite` —
`test_run_and_exit()` is designed to be the last line of a test file's
`main()`, translating a suite of assertions into the single pass/fail
exit code `safeguard test` checks:

```c
// tests/math_test.sc
#include <std/test/test.h>

void addition_works() { ASSERT_EQ(2 + 2, 4); }

int main(void) {
    struct TestSuite t = std::test_suite_init();
    unsafe { t.add("addition works", (void*)addition_works); }
    std::test_run_and_exit(&t);   // exits 1 on any failure
}
```

## `format` — reindenter

Deliberately scoped narrower than a full AST-based pretty-printer like
`rustfmt` or `clang-format`: it recomputes indentation from `{`/`}`
nesting depth, trims trailing whitespace, normalizes tabs, and collapses
runs of blank lines — but never touches string/comment *contents*,
horizontal spacing within a line, or line-wrapping. This scope was chosen
because `safec`'s own Lexer discards comments entirely during
tokenization (see [Compiler Architecture](/advanced/compiler)) — there is
no lossless AST for a formatter to round-trip through, so it works from
raw source text directly, where safe reindentation is tractable but
arbitrary reflow risks corrupting comment/string content. `--check`
reports which files would change without writing them (matching `cargo
fmt --check` / `gofmt -l`), useful as a CI gate.

## `lint` — static analysis

In the spirit of `cargo clippy`: catches real issues beyond what
compilation alone checks. Combines heuristic scans over raw source with
`safec --dump-ast`'s own unused-variable warnings:

| Code | Level | Description |
|------|-------|-------------|
| SA001 | warning | File contains more than 5 `unsafe {}` blocks — consider refactoring |
| SA002 | note | `alloc()`/`malloc()` result is not null-checked on the same line |
| SA003 | warning | Unused variable (forwarded from `safec --dump-ast`) |
| SA004 | warning | Empty `unsafe {}` block — has no effect |
| SA005 | note | Unresolved `TODO`/`FIXME`/`XXX` marker |
| SA006 | warning | `=` instead of `==` in an `if` condition |
| SA007 | warning | Duplicate `#include` in the same file |

## Package.toml

Every SafeC project has a `Package.toml` at its root:

```toml
[package]
name = "myproject"
version = "0.1.0"

[[dependencies]]
name = "mylib"
version = "https://github.com/user/mylib"
```

The `[package]` section defines the project name and version.

Each `[[dependencies]]` entry specifies a dependency by name and source URL. Dependencies are git repositories cloned into the build directory during `safeguard fetch`.

## Build Flow

`src/` can freely mix `.sc`, `.c`, and `.cpp`/`.cc`/`.cxx` files — each is
dispatched by extension and compiled to its own object file independently,
never merged into a shared translation unit, so per-file recompilation
stays as granular as a pure-SafeC project:

```
.sc sources          .c / .cpp sources
    |                       |
safec --emit-llvm      clang / clang++ -c
    |                       |
clang -c ------------> .o files <-----
    |
ar ---------------------> .a static library (for deps)
    |
clang(++) link ----------> executable
```

1. Each `.sc` file is compiled to LLVM IR (`.ll`) by `safec`, then assembled to `.o` by `clang -c`
2. Each `.c`/`.cpp` file is compiled straight to `.o` by `clang`/`clang++` — no LLVM-IR intermediate step, since these are already native compiler front ends
3. Dependencies are archived into static libraries using `ar` (also mixed-language aware — a dependency's own `src/` can contain `.sc`/`.c`/`.cpp` too)
4. The final link uses `clang++` as the driver whenever any `.cpp` source was compiled in (so the C++ runtime — libc++/libstdc++, exceptions, RTTI — links in correctly), plain `clang` otherwise; either way it's linking ordinary object files, since SafeC's C ABI compatibility means a `.sc`-compiled `.o` and a `.c`/`.cpp`-compiled `.o` are interchangeable at the object-file level

All three languages share the project's own `include/` directory and each
dependency's `include/` directory for headers — but *not* SafeC's own
`std/` directory, which is deliberately kept off the `.c`/`.cpp` include
path: `std/`'s headers use `#define`-based typedefs tuned for SafeC's own
preprocessing model, and putting them on a real C/C++ compiler's search
path corrupts its own standard headers (e.g. `<cstdint>`/`<vector>`) if
they happen to shadow the same names.

### Example: calling C and C++ from SafeC

```c
// src/helper.c
int add_c(int a, int b) { return a + b; }
```

```cpp
// src/helper.cpp
#include <vector>
extern "C" int sum_cpp(int a, int b) {
    std::vector<int> v = {a, b};
    int total = 0;
    for (int x : v) total += x;
    return total;
}
```

```c
// src/main.sc
extern int add_c(int a, int b);
extern int sum_cpp(int a, int b);

int main() {
    unsafe { printf("%d %d\n", add_c(2, 3), sum_cpp(2, 3)); }
    return 0;
}
```

A C++ function called from SafeC needs `extern "C"` on the C++ side (same
requirement as calling C++ from plain C) — without it, C++ name mangling
means the linker never finds a plain `sum_cpp` symbol.

`Package.toml`'s `[build] srcs = [...]` can also list files explicitly
(mixed extensions allowed) instead of relying on auto-discovery under
`src/`; `cflags = [...]` is appended to every `clang`/`clang++` invocation
(both `.c`/`.cpp` compilation and the final link).

## Standard Library Linking

The SafeC standard library (`std/`) is automatically compiled and archived into `build/deps/libsafec_std.a`. This library is linked into every project by default — no manual configuration needed.

Each dependency is similarly compiled to `build/deps/lib<name>.a`.

## Environment Setup

`safeguard` locates the SafeC toolchain using the `SAFEC_HOME` environment variable:

```bash
export SAFEC_HOME=/path/to/SafeC
```

This should point to the SafeC repository root. From there, `safeguard` auto-discovers:

- The `safec` compiler binary at `$SAFEC_HOME/compiler/build/safec`
- The standard library at `$SAFEC_HOME/std/`

## Project Structure

A typical SafeC project created with `safeguard new`:

```
myproject/
├── Package.toml
├── src/
│   └── main.sc
└── build/           (created by safeguard build)
    ├── deps/
    │   ├── libsafec_std.a
    │   └── lib<dep>.a
    └── myproject    (final executable)
```

## Example Workflow

```bash
# Create a new project
safeguard new hello

# Enter the project directory
cd hello

# Edit src/main.sc
# ...

# Build and run
safeguard run

# Or build separately
safeguard build --release
./build/hello
```

## Reproducible Builds

After every successful build, safeguard writes a `Package.lock` file that pins:

- The `safec` compiler binary (FNV-1a 64-bit hash)
- Each dependency's git commit SHA (`git rev-parse HEAD`)
- Each source file's content hash

```toml
[safec]
hash = "a3f5c8d2e1b04976"

[dep.mylib]
url     = "https://github.com/user/mylib"
git_sha = "c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2"

[sources]
"src/main.sc"   = "9f8e7d6c5b4a3f2e"
"src/utils.sc"  = "1a2b3c4d5e6f7a8b"
```

### Verifying a lock

```bash
safeguard verify-lock
```

Checks that each dependency's current HEAD matches the locked SHA. If any dependency has drifted, the command prints a warning and exits with code 1. When the compiler binary hash changes, a warning is printed but the build is not blocked.

### Blocking builds on drift

`safeguard build` automatically calls `checkLock()` before compiling. If a dependency SHA has changed since the lock was written, the build is aborted:

```
safeguard: dep 'mylib' git SHA has changed — run 'safeguard fetch' then rebuild
```

---

## Static Analysis

```bash
safeguard analyze [--verbose]
```

Runs built-in lint passes over every `.sc` file in `src/`. Uses `safec --dump-ast` to obtain the AST for unused-variable detection.

| Code | Level | Description |
|------|-------|-------------|
| SA001 | warning | File contains more than 5 `unsafe {}` blocks — consider refactoring |
| SA002 | note | `alloc()` result is not null-checked on the same line |
| SA003 | warning | Unused variable (forwarded from `safec --dump-ast` output) |

### Example output

```
src/driver.sc: warning [SA001] file contains 7 unsafe{} blocks — consider refactoring
src/parser.sc:42: note [SA002] result of alloc() should be null-checked
src/parser.sc:58: warning [SA003] warning: unused variable 'tmp'
safeguard: analysis complete — 3 diagnostic(s), 0 error(s)
```

Returns exit code 0 if no errors (warnings are non-fatal), 1 if any error-level diagnostics are found.

---

## Building safeguard

The package manager itself is built with CMake:

```bash
cd safeguard
cmake -S . -B build
cmake --build build
```

The resulting binary is `build/safeguard`.
