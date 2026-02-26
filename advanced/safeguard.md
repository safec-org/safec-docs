# Package Manager

`safeguard` is the official SafeC package manager and build system. It is a standalone C++17 binary with no runtime dependencies beyond a working `safec` compiler and `clang` linker.

## Commands

| Command | Description |
|---------|-------------|
| `safeguard new <name>` | Scaffold a new project directory with `Package.toml` and `src/main.sc` |
| `safeguard init` | Initialize a `Package.toml` in the current directory |
| `safeguard fetch` | Download all dependencies listed in `Package.toml` |
| `safeguard build [--release]` | Compile the project and all dependencies |
| `safeguard run` | Build and execute the project binary |
| `safeguard clean` | Remove the `build/` directory and all artifacts |
| `safeguard verify-lock` | Check `Package.lock` against current dependency state |
| `safeguard analyze [--verbose]` | Run static analysis lint passes on all `.sc` source files |

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

```
.sc sources
    |
safec --emit-llvm ---> .ll files
    |
clang -c ------------> .o files
    |
ar ---------------------> .a static library (for deps)
    |
clang link -------------> executable
```

1. Each `.sc` file is compiled to LLVM IR (`.ll`) by the `safec` compiler
2. `clang -c` assembles each `.ll` file into an object file (`.o`)
3. Dependencies are archived into static libraries using `ar`
4. The final executable is linked by `clang`, combining the project objects with dependency libraries

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
