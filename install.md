---
layout: page
title: Install SafeC
summary: Install the SafeC compiler, safeguard and sc-lsp on Linux, macOS or Windows from a prebuilt release, or build them from source.
---

# Install SafeC

::: lead
One command installs a prebuilt `safec` (the compiler), `safeguard` (the package manager and build tool) and `sc-lsp` (the language server) from the newest [GitHub release](https://github.com/safec-org/SafeC/releases), together with the standard library — sources plus prebuilt archives for your platform.
:::

## Quick install {#quick-install}

::::: tabs
::: tab macOS
```bash
brew install llvm          # clang 21+; Apple's Xcode clang is too old
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash
```
Binaries for Apple silicon (`arm64`), macOS 11 Big Sur or newer.
:::
::: tab Linux
```bash
# Debian / Ubuntu: clang 21 from apt.llvm.org, if you don't have it yet
wget https://apt.llvm.org/llvm.sh && sudo bash llvm.sh 21
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash
```
`x86_64` binaries for glibc 2.25 or newer: Debian 10, Ubuntu 18.04, RHEL 8 and later. Musl-based distributions (Alpine) aren't supported by the release binaries — build from source there.
:::
::: tab Windows
```powershell
winget install LLVM.LLVM     # clang 21+
irm https://raw.githubusercontent.com/safec-org/SafeC/main/install.ps1 | iex
```
`x86_64` binaries, from PowerShell.
:::
::: tab From source
```bash
git clone https://github.com/safec-org/SafeC && cd SafeC
cmake -G Ninja -S compiler -B compiler/build -DCMAKE_BUILD_TYPE=Release \
      -DLLVM_DIR=/usr/lib/llvm-21/lib/cmake/llvm
cmake --build compiler/build
cmake -G Ninja -S safeguard -B safeguard/build -DCMAKE_BUILD_TYPE=Release
cmake --build safeguard/build
export SAFEC_HOME=$PWD PATH=$PWD/compiler/build:$PWD/safeguard/build:$PATH
```
Needs CMake, Ninja, a C++17 compiler and LLVM 21 development files. `sc-lsp` lives in the sibling [sc-language-server](https://github.com/safec-org/sc-language-server) repository.
:::
:::::

The script installs into `~/safec` by default, sets `SAFEC_HOME` and adds its `bin/` to your `PATH` in your shell's rc file (bash, zsh or fish; the user environment on Windows). Restart the shell, or `source` the rc file, then check:

```bash
safec --version
safeguard --help
```

## Requirements {#requirements}

**clang 21 or newer.** `safec` emits LLVM IR, and `safec --emit-bin` and `safeguard` hand it to clang to compile and link (clang also imports C headers). The install scripts find a suitable clang — including versioned names like `clang-21`, apt.llvm.org's `/usr/lib/llvm-21` and Homebrew's `llvm` — and set `SAFEC_CLANG` / `SAFEC_CLANGXX` when it isn't the plain `clang` on your `PATH`. If there is none they print how to install one:

| Platform | Install clang |
|---|---|
| macOS | `brew install llvm` |
| Debian / Ubuntu | `wget https://apt.llvm.org/llvm.sh && sudo bash llvm.sh 21` |
| Windows | `winget install LLVM.LLVM` |
| Other | [LLVM releases](https://github.com/llvm/llvm-project/releases) |

## Options {#options}

| `install.sh` | `install.ps1` | |
|---|---|---|
| `--prefix=<path>` | `-Prefix <path>` | Install directory (default `~/safec`) |
| `--version=<tag>` | `-Version <tag>` | A specific release, e.g. `v1.0.0-beta-6` (default: the newest, pre-releases included) |
| `--std=<sections>` | `-Std <sections>` | Prebuilt std sections to install, e.g. `core,collections,gui` (default `all`) |
| `--std=+<sections>` | `-Std +<sections>` | Add sections to an existing install later |
| `--skip-env` | `-SkipEnv` | Don't touch the shell environment |

Pass options to the piped script with `bash -s --`:

```bash
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash -s -- --version=v1.0.0-beta-6 --std=core,collections
```

The standard library's sources are always installed. Its prebuilt archives come in *sections* — std's top-level directories (`core`, `collections`, `gui`, `ml`, `http`, `audio`, `async`, ...); `safeguard` links the sections a project uses and compiles any section you left out from source when a build needs it. See [Prebuilt std](/advanced/safeguard#prebuilt-std).

## Your first program {#first-program}

```bash
safeguard new hello && cd hello
safeguard run
```

`safeguard new` writes a `Package.toml` and `src/main.sc`; `safeguard run` builds and runs it. From here:

- [The SafeC Book](/book/) walks through the language with small projects.
- [Getting Started](/guide/getting-started) covers compiling single files with `safec`, linking and C headers.
- [Tools](/tools) sets up your editor with `sc-lsp`.

## Updating and removing {#updating}

Run the install command again to update to the newest release (or pass `--version` to pick one). To remove SafeC, delete the install directory (`~/safec` by default) and the `# SafeC` block the installer added to your shell rc file.
