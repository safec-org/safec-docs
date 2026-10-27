---
layout: page
title: Tools
summary: The SafeC toolchain — the safec compiler, the safeguard package manager and build tool, the sc-lsp language server with VS Code, Neovim and Emacs setups, and the playground.
---

# Tools

::: lead
Every release ships three programs: `safec`, the compiler; `safeguard`, the package manager and build tool most projects drive everything with; and `sc-lsp`, the language server for your editor. [Install](/install) puts all three on your `PATH` (with `safec-doc`, the API reference generator behind `safeguard doc`).
:::

::::: cards 3
::: card package
### [safeguard](#safeguard)

Projects, dependencies, builds, tests, formatting, linting and app bundles.
:::
::: card edit
### [sc-lsp](#sc-lsp)

Diagnostics, hover, completion and go-to-definition, from the compiler's own frontend.
:::
::: card terminal
### [safec](#safec)

The compiler itself: SafeC to LLVM IR, objects or binaries, for any LLVM target.
:::
:::::

## safeguard {#safeguard}

`safeguard` builds a project described by its `Package.toml`: SafeC, C, C++ and Objective-C sources, git, local or prebuilt dependencies (including C/C++ libraries built with CMake), and only the standard-library modules the project uses, linked with the system libraries they need.

| Command | What it does |
|---|---|
| `safeguard new <name>` / `init` | Create a project (`--lang c\|cpp\|objc\|objcpp` for a package without SafeC) |
| `safeguard build` | Compile and link the project (`--release`, `--target <triple>` for cross builds) |
| `safeguard run [args]` | Build, then run the binary |
| `safeguard check` | Fast compile-only pass: errors without linking |
| `safeguard test` | Build and run every file under `tests/` |
| `safeguard fetch` | Clone and build the `[dependencies]` |
| `safeguard format` | Reindent and normalize whitespace in `.sc` / `.h` files |
| `safeguard lint` | Static-analysis lint passes |
| `safeguard bundle` | Package a GUI app as a macOS `.app`, a Windows app folder, a Linux `.deb` and AppDir, or (`--target wasm32`) a web page |
| `safeguard doc` | Generate the [API reference](/advanced/safeguard#safeguard-doc) of a package (`--serve` to browse it, `--std` for std's) |
| `safeguard publish` | Prepare a library for [publishing](/advanced/safeguard#safeguard-publish): CI, release archives and Pages docs |
| `safeguard clean` | Remove `build/` and `deps/` |
| `safeguard verify-lock` | Check `Package.lock` against the current dependency state |
| `safeguard std-modules` | Which std sources a build compiles and which system libraries it links |
| `safeguard std-prebuilt <prefix>` | Compile std for this machine into one archive per section: the [prebuilt std](/advanced/safeguard#prebuilt-std) a release ships |

```toml
# Package.toml
[package]
name    = "hello"
version = "0.1.0"

[[dependencies]]
name    = "mylib"
version = "https://github.com/user/mylib"   # or: path = "../mylib"
```

The [Package Manager](/advanced/safeguard) page documents every command, `Package.toml` key, feature flags, cross targets, library output, Windows DLLs and bundles.

## sc-lsp — editor support {#sc-lsp}

[`sc-lsp`](https://github.com/safec-org/sc-language-server) links the SafeC compiler's actual frontend (preprocessor, lexer, parser, semantic analysis, constant evaluation), so it understands every construct the compiler does and reports exactly what a build would. It analyzes `.sc`, `.h` and `.scx` files and provides:

- diagnostics on open and change,
- hover with full signatures,
- completion of symbols and keywords,
- go-to-definition (into std headers too),
- document symbols (the outline).

It reads the nearest `Package.toml` for features and the compat-preprocessor setting, and accepts `includePaths`, `target`, `defines` and `features` through `initializationOptions`.

::::: tabs
::: tab VS Code
```bash
git clone https://github.com/safec-org/sc-language-server
cd sc-language-server/editors/vscode
npm install
npm install -g @vscode/vsce
vsce package                         # -> safec-analyzer-<version>.vsix
code --install-extension safec-analyzer-*.vsix
```
Then set `"safec.server.path": "/path/to/sc-lsp"` in your settings. The extension (SafeC Analyzer) claims `.sc`, `.scx` and `.h`; use *Change Language Mode* to hand a plain C header back to the C/C++ extension.
:::
::: tab Neovim
```lua
require("lspconfig.configs").safec = {
  default_config = {
    cmd = { "sc-lsp" },
    filetypes = { "safec" },
    root_dir = require("lspconfig.util").find_git_ancestor,
  },
}
require("lspconfig").safec.setup({})
vim.filetype.add({ extension = { sc = "safec" } })
```
To claim `.h` files only inside SafeC projects, see the [server's README](https://github.com/safec-org/sc-language-server#neovim-nvim-lspconfig).
:::
::: tab Emacs
```lisp
(add-to-list 'auto-mode-alist '("\\.sc\\'" . c-mode))
(add-to-list 'eglot-server-programs '(c-mode . ("sc-lsp")))
```
Eglot then starts `sc-lsp` for `.sc` files (and for headers already in `c-mode`).
:::
:::::

## safec — the compiler {#safec}

`safec` compiles one translation unit to LLVM IR (`--emit-llvm`), an object or, through clang, a binary (`--emit-bin`). `--target=<triple>` cross-compiles to any LLVM target; aliases like `arm-7em`, `rv32i` or `wasm32` pick the triple, CPU, features and ABI for bare metal and WebAssembly, and `--freestanding` drops the hosted runtime.

```bash
safec hello.sc --emit-bin -o hello
safec blink.sc --target=arm-7em --freestanding --emit-llvm -o blink.ll
```

[Getting Started](/guide/getting-started#compiler-flags) lists every flag and covers single-file builds and C headers; [Compiler Architecture](/advanced/compiler) describes the pipeline behind them.

## Playground {#playground}

The [Playground](/playground) edits SafeC in the browser with highlighting, loads the Book's examples and shares programs as links. *Run* compiles and runs the program in the browser with scc, SafeC's self-hosted compiler built to WebAssembly; it accepts a subset of the language and the std headers bundled with it ([details](/playground#about)).
