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

## Building safeguard

The package manager itself is built with CMake:

```bash
cd safeguard
cmake -S . -B build
cmake --build build
```

The resulting binary is `build/safeguard`.
