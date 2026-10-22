# Package Manager

`safeguard` is the official SafeC package manager and build system. It is a standalone C++17 binary with no runtime dependencies beyond a working `safec` compiler and `clang` linker.

## Commands {#commands}

| Command | Description |
|---------|-------------|
| `safeguard new <name> [--lang L]` | Scaffold a new project directory with `Package.toml` and `src/main.sc` (or, with `--lang c`/`cpp`/`objc`/`objcpp`, a [C, C++ or Objective-C package](#c-c-and-objective-c-packages)) |
| `safeguard init [--lang L]` | Initialize a `Package.toml` in the current directory |
| `safeguard fetch` | Clone the git dependencies listed in `Package.toml` and build the ones from source |
| `safeguard build [--release]` | Compile the project and all dependencies |
| `safeguard check` | Fast compile-only pass — reports errors without assembling objects or linking |
| `safeguard test` | Build and run every file under `tests/` as an independent binary |
| `safeguard run` | Build and execute the project binary |
| `safeguard clean` | Remove the `build/` directory and all artifacts |
| `safeguard verify-lock` | Check `Package.lock` against current dependency state |
| `safeguard lint [--verbose]` | Run static-analysis lint passes on all `.sc` source files |
| `safeguard format [--check]` | Reindent and whitespace-normalize all `.sc`/`.h` source files |

## `check` — fast compile-only feedback {#check-fast-compile-only-feedback}

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

## `test` — integration tests {#test-integration-tests}

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

void addition_works() {
    std::test_assert_eq_i(2 + 2, 4, "2+2 == 4", "math_test.sc", 4);
}

int main(void) {
    struct TestSuite t = std::TestSuite::new();
    t.add("addition works", addition_works);
    std::test_run_and_exit(&t);   // exits 1 on any failure
}
```

::: tip Why this compiles despite `test.h`'s function-like macros
`<std/test/test.h>` unconditionally `#define`s several function-like
macros (`ASSERT_EQ`, `ASSERT_TRUE`, ...) at the top of the header, and
merely *including* a header containing function-like macro definitions
normally fails outside `--compat-preprocessor` mode ("function-like
macros are not allowed in safe mode..."), even in the macro-free calling
style shown above. `safeguard test` specifically compiles every file
under `tests/` with `--compat-preprocessor` (unlike `build`/`check`,
which compile `src/` without it) precisely so `<std/test/test.h>` can be
included — verified against a real `safeguard test` run of the example
above.
:::

## `format` — reindenter {#format-reindenter}

(`safeguard fmt` is accepted as a shorter alias.)

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

## `lint` — static analysis {#lint-static-analysis}

In the spirit of `cargo clippy`: catches real issues beyond what
compilation alone checks. Combines heuristic scans over raw source with
`safec --dump-ast`'s own unused-variable warnings. `safeguard analyze` is
accepted as an alias for `safeguard lint` — both run the identical pass.

| Code | Level | Description |
|------|-------|-------------|
| SA000 | error | Source file could not be opened |
| SA001 | warning | File contains more than 5 `unsafe {}` blocks — consider refactoring |
| SA002 | note | `alloc()`/`malloc()` result is not null-checked on the same line |
| SA003 | warning | Unused variable (forwarded from `safec --dump-ast`) |
| SA004 | warning | Empty `unsafe {}` block — has no effect |
| SA005 | note | Unresolved `TODO`/`FIXME`/`XXX` marker |
| SA006 | warning | `=` instead of `==` in an `if` condition |
| SA007 | warning | Duplicate `#include` in the same file |

### Example output {#example-output}

```
src/driver.sc: warning [SA001] file contains 7 unsafe{} blocks — consider refactoring
src/parser.sc:42: note [SA002] result of alloc() should be null-checked
src/parser.sc:58: warning [SA003] warning: unused variable 'tmp'
safeguard: analysis complete — 3 diagnostic(s), 0 error(s)
```

Returns exit code 0 if no errors (warnings are non-fatal), 1 if any error-level diagnostics are found.

## Cross targets {#cross-targets}

`--target <alias|triple>` (on `build`, `check`, `test`) cross-compiles the project and its standard library for another machine. A bare-metal alias (`x86`, `x86_64`, `aarch32`, `aarch64`, `thumb`, `thumb2`, `arm-6m`, `arm-7m`, `arm-7em`, `arm-8m`, `arm-81m`, `rv32i`, `rv32e`, `rv64i`, `rv64e`, `wasm32`, `wasm64` — see [Bare-Metal Targets](/reference/baremetal#targets)) implies freestanding mode; any LLVM triple is accepted too.

```bash
safeguard build --target arm-7em --release
safeguard test --target rv32e        # builds every test for RV32E, doesn't run them
```

- The std archive is built per target under `build/deps/<target>/libsafec_std.a`, from just the sources that apply to it (OS-specific backends are skipped on bare metal).
- Objects — SafeC, C, C++ and Objective-C alike — are compiled with the target's clang flags (`safec --print-target-clang-flags`), and bare-metal links use `ld.lld` with `-nostdlib -static`: supply the linker script, startup code and runtime (`memcpy`, `memset`, any `malloc`, compiler-rt builtins) through `[build] cflags`/`libs`.
- Functions your project defines that freestanding mode would otherwise flag as hosted-only (a runtime's own `malloc`, say) are passed to the compiler as `--provides`, so they don't warn.
- WebAssembly targets link a library module instead: no entry point, exports are the functions marked `export_name`, unresolved externs become imports, and the std archive includes `std/wasm` (the wasm runtime). See [WebAssembly](/reference/baremetal#wasm).
- `test` builds the test binaries without running them (they're for another machine); `run` refuses a cross target.

## Package.toml {#package-toml}

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

Each `[[dependencies]]` entry names a dependency and where it comes from (see [Dependencies](#dependencies)).

## Dependencies {#dependencies}

A dependency comes from one of three places:

```toml
[[dependencies]]                              # a git repository, cloned to deps/<name>
name    = "mylib"
version = "https://github.com/user/mylib"

[[dependencies]]                              # a local directory
name    = "mylib"
path    = "../mylib"

[[dependencies]]                              # prebuilt library files
name    = "mylib"
lib     = ["vendor/libmylib.a"]               # .a, .so, .dylib or .lib
include = ["vendor/include"]                  # optional header directories

[[dependencies]]                              # a C/C++/ObjC project safeguard builds
name    = "mylib"
path    = "../mylib"                          # or version = "https://..."
build   = "cmake"                             # or "sources"
```

What a directory provides, whether it's fetched or local, depends on what it contains:

| The directory has | It is | safeguard |
|---|---|---|
| `src/` | a SafeC package | builds `src/` (`.sc`, `.c`, `.cpp`) into `build/deps/lib<name>.a`; `include/` and `src/` go on the include path |
| no `src/` | a prebuilt library directory | links `lib<name>.a`, `lib<name>.so`, `lib<name>.dylib` or `<name>.lib` from `lib/`, `lib64/` or the directory itself, static first. Without one by that name, it links every library in the first of those that has any (an install with `libssl` and `libcrypto` for a dependency named `openssl`). `include/` goes on the include path |

A directory whose entry has `build` is neither: it's an existing C, C++ or Objective-C project that safeguard builds itself (see [C/C++ dependencies built from source](#c-c-dependencies-built-from-source)).

`lib` names the library files outright and takes precedence over what's in the directory. `lib` and `include` paths are relative to the dependency's directory when it has `version` or `path`, and to the project root otherwise. A fetched repository can ship prebuilt libraries this way too: `version = "https://..."` with `lib = ["lib/macos-arm64/libmylib.a"]`. Shared libraries get an rpath to their directory, so the program finds them at run time. A local `path` is resolved against the project root. `safeguard fetch` and `build` fail with the dependency's name when a library, a directory or an `include` directory doesn't exist, or when a directory has neither `src/` nor a library. Dependency libraries are linked before the standard library, so a SafeC package can use `std`.

### C/C++ dependencies built from source {#c-c-dependencies-built-from-source}

A C, C++ or Objective-C library that isn't a safeguard package — a CMake project, or just a directory of sources — can be a dependency built by safeguard itself. `build` says how; the result goes to `build/deps/<name>/`:

```toml
[[dependencies]]                  # a CMake project
name       = "cppstats"
path       = "../cpplib"          # or version = "https://..." (cloned first)
build      = "cmake"
cmake_args = ["-DCPPSTATS_TESTS=OFF"]

[[dependencies]]                  # a directory of sources
name       = "nslen"
path       = "../nslen"
build      = "sources"
sources    = ["src", "extra/*.c"] # files, directories, '*' patterns
defines    = ["NDEBUG", "LEVEL=2"]
cflags     = ["-fobjc-arc"]
include    = ["include"]          # its public headers
frameworks = ["Foundation"]       # macOS frameworks it needs
```

| `build` | safeguard |
|---|---|
| `"sources"` | compiles the `.c`/`.cpp`/`.cc`/`.cxx`/`.m`/`.mm` files that `sources` names (default: its `src/`, recursively, else the files directly in the directory) with `defines` (`-D`), `cflags` and its `include` directories, into `build/deps/<name>/lib<name>.a` |
| `"cmake"` | configures it with the same `clang`/`clang++` the build uses (`-G Ninja` when `ninja` is on `PATH`, `Release` for `--release`, `Debug` otherwise — `RelWithDebInfo` on Windows, whose debug CRT doesn't mix with the program's), plus `cmake_args`; builds it; installs it into `build/deps/<name>/install`. The libraries are `lib` (relative to that install prefix) if given, else `lib<name>` or every library in its `lib/`, else the ones in the build tree for a project without `install()` rules |

Headers come from `include` (relative to the dependency's directory) when it's given; otherwise from `include/` — the install's for CMake, the dependency's own for `"sources"`, or the directory itself if it has none. They go on the include path of both SafeC and C/C++ sources.

The build is cached: it reruns only when a file under the dependency's directory or its `Package.toml` settings changed since the last one. A C++ dependency makes `clang++` the link driver (for a CMake one, always — what it compiles can't be told from outside, and `clang++` links C just as well); Objective-C adds `-lobjc`; `frameworks` are linked on macOS. `sources`, `defines` and `cflags` need `build = "sources"`, `cmake_args` needs `build = "cmake"`; anything else is an error naming the dependency. CMake dependencies can't be cross-compiled with `--target` yet.

SafeC calls these libraries through C declarations, so a C++ library must expose `extern "C"` functions: C++ classes, templates, overloads and other mangled symbols aren't callable from SafeC. Wrap them in a small C API:

```cpp
// cpplib/include/cppstats.h — included by SafeC and C++ alike
#ifdef __cplusplus
extern "C" {
#endif
double cppstats_median(const double* values, int n);
#ifdef __cplusplus
}
#endif
```

```cpp
// cpplib/src/cppstats.cpp
#include "cppstats.h"
#include <algorithm>
#include <vector>

extern "C" double cppstats_median(const double* values, int n) {
    std::vector<double> v(values, values + n);
    std::sort(v.begin(), v.end());
    return n % 2 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2.0;
}
```

```c
// app/src/main.sc
#include <cppstats.h>
extern int printf(const char* fmt, ...);

int main() {
    double samples[5] = { 7.0, 1.0, 4.0, 9.0, 3.0 };
    unsafe { printf("median %.1f\n", cppstats_median(&samples[0], 5)); }
    return 0;
}
```

The SafeC repository's `examples/cpp_dep/` is this project with its `CMakeLists.txt`, and `examples/objc_dep/` an Objective-C one (`NSString` behind a C API, macOS).

## Feature Flags {#feature-flags}

`Package.toml` can declare a Cargo-style `[features]` table — named, optional
build configurations that gate source code without maintaining separate
branches or build scripts:

```toml
[features]
default   = ["backend"]              # enabled unless --no-default-features
frontend  = []
backend   = []
fullstack = ["frontend", "backend"]  # enabling one feature can enable others
```

Each entry's value is a list of *other* features it also turns on — the same
"a feature can pull in other features" model Cargo uses (SafeC has no
optional-dependency concept yet, so a feature can only chain other features,
not toggle a `[[dependencies]]` entry on or off).

Select features at build time:

```bash
safeguard build                                              # "default" only
safeguard build --features frontend                          # default + frontend
safeguard build --features frontend,backend                  # comma-separated, repeatable
safeguard build --no-default-features --features backend     # backend only
```

`build` / `check` / `test` / `run` all accept `--features`/`--no-default-features`.
safeguard resolves the enabled set (starting from `default` unless suppressed,
following each enabled feature's own sub-feature list to a fixed point) and
passes every enabled feature to `safec` as a preprocessor define:

```
feature "backend"  ->  -DSAFEC_FEATURE_BACKEND=1
feature "frontend" ->  -DSAFEC_FEATURE_FRONTEND=1
```

(hyphens in a feature name become underscores). Project source gates on that
define with `safec`'s existing preprocessor — no new language syntax:

```c
#ifdef SAFEC_FEATURE_BACKEND
#include <std/http/http.h>
#include <std/http/http.h>
// ... server-only code ...
#endif
```

Feature `-D` defines apply only to the project's own `src/`/`tests/` sources
— the standard library and fetched dependencies build the same way
regardless of which features the top-level project enabled. All files under
`src/` are still compiled either way (feature flags decide what code
*runs*, not what gets *compiled* — an `#ifdef`'d-out function still exists
in the binary as dead code, same as plain C conditional compilation); this
keeps the model simple and matches how the rest of `safec`'s preprocessor
already works, at the cost of not shrinking binary size or skipping
unrelated build errors the way Cargo's per-feature compilation does. See
`examples/fullstack_demo/` in the SafeC repository for a complete
`frontend`/`backend`/`fullstack` project built this way, including a scx
frontend (see [scx Templating](/advanced/scx)).

## Build Flow {#build-flow}

`src/` can freely mix `.sc`, `.c`, `.cpp`/`.cc`/`.cxx`, and Objective-C `.m`/`.mm` files — each is
dispatched by extension and compiled to its own object file independently,
never merged into a shared translation unit, so per-file recompilation
stays as granular as a pure-SafeC project:

```
.sc sources          .c / .cpp / .m / .mm sources
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
2. Each `.c`/`.cpp`/`.m`/`.mm` file is compiled straight to `.o` by `clang`/`clang++` — no LLVM-IR intermediate step, since these are already native compiler front ends
3. Dependencies are archived into static libraries using `ar` (also mixed-language aware — a dependency's own `src/` can contain `.sc`/`.c`/`.cpp` too)
4. The final link uses `clang++` as the driver whenever any C++ (`.cpp` or `.mm`, the project's own or a dependency's) was compiled in (so the C++ runtime — libc++/libstdc++, exceptions, RTTI — links in correctly), plain `clang` otherwise, and adds `-lobjc` when there's Objective-C; either way it's linking ordinary object files, since SafeC's C ABI compatibility means a `.sc`-compiled `.o` and a `.c`/`.cpp`-compiled `.o` are interchangeable at the object-file level

All three languages share the project's own `include/` directory and each
dependency's `include/` directory for headers — but *not* SafeC's own
`std/` directory, which is deliberately kept off the `.c`/`.cpp` include
path: `std/`'s headers use `#define`-based typedefs tuned for SafeC's own
preprocessing model, and putting them on a real C/C++ compiler's search
path corrupts its own standard headers (e.g. `<cstdint>`/`<vector>`) if
they happen to shadow the same names.

### Example: calling C and C++ from SafeC {#example-calling-c-and-c-from-safec}

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
extern int printf(const char* fmt, ...);
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

### Objective-C {#objective-c}

`.m` (Objective-C) and `.mm` (Objective-C++) files build on macOS; for any
other target safeguard stops with an error naming the file. Two `[build]`
keys go with them:

```toml
[build]
objc_arc   = true                     # compile .m/.mm with -fobjc-arc
frameworks = ["Cocoa", "Metal"]       # -framework <name> at the final link
```

`frameworks` applies to macOS builds only (elsewhere it's dropped with a
note). Objective-C classes, like C++ ones, aren't visible to SafeC: give
the code a C function to call.

### Per-platform settings {#per-platform-settings}

`[build.macos]`, `[build.linux]` and `[build.windows]` take `cflags`,
`libs`, `lib_dirs` and `frameworks`, added to `[build]`'s own only when
building for that OS (the `--target`'s, or the host's) — one manifest can
then name each platform's system libraries:

```toml
[build.macos]
frameworks = ["Cocoa"]
libs       = ["objc"]

[build.linux]
libs = ["X11"]

[build.windows]
libs = ["user32", "gdi32"]
```

Any other `[build.<name>]` table, or another key in one, is an error.

### C, C++ and Objective-C packages {#c-c-and-objective-c-packages}

A package needn't contain SafeC at all. `safeguard new <name> --lang c`
(or `cpp`, `objc`, `objcpp`) scaffolds `src/main.c` (`.cpp`, `.m`, `.mm`;
the Objective-C ones with `objc_arc = true` and `frameworks =
["Foundation"]`), and such a package builds as a `bin`, `staticlib` or
`cdylib` like any other, exporting its headers the same way. When neither
the package's sources nor a SafeC source dependency contain SafeC, the
standard library is neither built nor linked.

## Linking External Libraries and Library Output {#linking-external-libraries-and-library-output}

Four more `[build]` keys configure the final link step and what kind of
artifact it produces — the same underlying flags `safec --emit-bin` exposes
directly (see [Compiler Architecture](/advanced/compiler#linking-emit-bin)),
here driven by `Package.toml` instead of the command line:

```toml
[build]
libs       = ["m", "pthread"]   # -l<name> at the final link
lib_dirs   = ["/usr/local/lib"] # -L<dir> at the final link
lto        = "thin"             # "" (off, default), "thin", or "full"
crate_type = "bin"              # "bin" (default), "staticlib", or "cdylib"
blas       = "auto"             # CPU BLAS for std's sgemm (see below)
```

`blas` picks the CPU vendor's BLAS that [`sgemm`](/stdlib/simd#gemm) — and through it `tensor_matmul` and `Conv2D::forward` — uses, chosen when the program is linked:

| `blas` | Links |
|---|---|
| `"auto"` (default) | AOCL-BLAS on AMD CPUs, oneMKL on Intel CPUs, Accelerate on macOS — if the library is found; otherwise none |
| `"aocl"` / `"mkl"` / `"openblas"` / `"accelerate"` | that library (a warning and std's built-in kernel if it isn't found) |
| `"none"` | nothing: std's built-in SIMD kernel |

Libraries are looked up in `AOCL_ROOT`, `MKLROOT` and `OPENBLAS_ROOT` (or their `lib/`, `lib_LP64/` and `lib/intel64/` subdirectories), then in the default install locations (`/opt/AMD/aocl/...`, `/opt/intel/oneapi/mkl/latest/lib`). oneMKL is linked with its GNU OpenMP threading layer. `SAFEC_BLAS` overrides the manifest's choice and `SAFEC_BLAS_LIBS` replaces the link flags outright. Bare-metal, WebAssembly and Windows builds don't link a BLAS. `--verbose` prints which one was picked.

`crate_type` selects what `safeguard build` produces:

| `crate_type` | Output | How it's produced |
|---|---|---|
| `"bin"` (default) | `build/<output>` | linked executable |
| `"staticlib"` | `build/lib<output>.a` and `build/include/` | `ar`-packed archive of this project's own objects (no linker, no `-l`/`-L`/`lto` — a `.a` isn't linked, just bundled for a later link to consume) |
| `"cdylib"` | `build/lib<output>.{dylib,so,dll}` (platform-dependent) and `build/include/` | linked with `-shared` |

A library build also exports its public headers to `build/include/`: everything under the project's `include/`, and the headers (`.h`, and C++'s `.hh`/`.hpp`/`.hxx`) under `src/`, with their paths kept (`include/mylib/config.h` → `build/include/mylib/config.h`, `src/mylib.h` → `build/include/mylib.h`). These are the headers a dependent building the package from source would see. The directory is rebuilt on every build, so a deleted header leaves it too. A header at the same path in both `include/` and `src/` is an error. `build/` is then a [prebuilt dependency](#dependencies) as it stands:

```toml
[[dependencies]]
name = "mylib"
path = "../mylib/build"    # links libmylib.a, puts build/include on the include path
```

The project's `include/` is on the include path of its own SafeC, C and C++ sources, so a library's sources include the headers it exports the same way its users do.

`safeguard run` requires `crate_type = "bin"` — a static or dynamic library
has no executable entry point to run, and running it against the linker
directly produces a clear error rather than a confusing OS-level failure.

## Standard Library Linking {#standard-library-linking}

The SafeC standard library (`std/`) is automatically compiled and archived into `build/deps/libsafec_std.a`. This library is linked into every project by default — no manual configuration needed — except a [package with no SafeC in it](#c-c-and-objective-c-packages).

Each SafeC dependency built from source is similarly compiled to `build/deps/lib<name>.a`, a C/C++ one into `build/deps/<name>/`; prebuilt ones are linked from where they are.

## Environment Setup {#environment-setup}

`safeguard` locates the SafeC toolchain using the `SAFEC_HOME` environment variable:

```bash
export SAFEC_HOME=/path/to/SafeC
```

This should point to the SafeC repository root. From there, `safeguard` auto-discovers:

- The `safec` compiler binary at `$SAFEC_HOME/compiler/build/safec`
- The standard library at `$SAFEC_HOME/std/`

## Project Structure {#project-structure}

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

## Example Workflow {#example-workflow}

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

## Reproducible Builds {#reproducible-builds}

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

### Verifying a lock {#verifying-a-lock}

```bash
safeguard verify-lock
```

Checks that each dependency's current HEAD matches the locked SHA. If any dependency has drifted, the command prints a warning and exits with code 1. When the compiler binary hash changes, a warning is printed but the build is not blocked.

### Blocking builds on drift {#blocking-builds-on-drift}

`safeguard build` automatically calls `checkLock()` before compiling. A dependency SHA mismatch is a hard error that aborts the build (a `safec` binary hash mismatch, by contrast, only warns — see above):

```
safeguard: error: dependency 'mylib' git SHA mismatch (locked=c3d4e5f... current=a1b2c3d...)
  Run 'safeguard fetch' to update, then rebuild.
```

---

## Building safeguard {#building-safeguard}

The package manager itself is built with CMake:

```bash
cd safeguard
cmake -S . -B build
cmake --build build
```

The resulting binary is `build/safeguard`.
