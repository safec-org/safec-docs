# Package Manager

`safeguard` is the official SafeC package manager and build system. It is a standalone C++17 binary with no runtime dependencies beyond a working `safec` compiler and `clang` linker.

## Commands {#commands}

| Command | Description |
|---------|-------------|
| `safeguard new <name> [--lang L] [--std]` | Scaffold a new project directory with `Package.toml` and `src/main.sc` (or, with `--lang c`/`cpp`/`objc`/`objcpp`, a [C, C++ or Objective-C package](#c-c-and-objective-c-packages)) |
| `safeguard init [--lang L] [--std]` | Initialize a `Package.toml` in the current directory |
| `safeguard fetch` | Clone the git dependencies listed in `Package.toml` and build the ones from source |
| `safeguard build [--release] [--crate-type T]` | Compile the project and all dependencies (`--crate-type staticlib\|cdylib\|bin` overrides `[build] crate_type`) |
| `safeguard check` | Fast compile-only pass — reports errors without assembling objects or linking |
| `safeguard std-modules [std/x.h\|file...]` | List the std sources a build compiles and the system libraries it links for them (see [Standard Library Modules](#standard-library-linking)) |
| `safeguard std-prebuilt <prefix>` | Compile std for this machine into `<prefix>/lib/<triple>/std/<section>.a`, one archive per section: the [prebuilt std](#prebuilt-std) a release ships |
| `safeguard test` | Build and run every file under `tests/` as an independent binary |
| `safeguard doc [--serve] [--std]` | Generate the [API reference](#safeguard-doc) into `build/doc/` (or std's, with `--std`) |
| `safeguard publish [--ci=…] [--write] [--tag] [--push]` | Prepare the repository for [publishing](#safeguard-publish): `.gitignore`, CI, release archives, Pages |
| `safeguard run` | Build and execute the project binary |
| `safeguard bundle [--sign <identity>]` | Build the binary and package it as an application: `.app`, app folder or `.deb` (see [App Bundles](#app-bundles)) |
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
exit code `safeguard test` checks. In a library package (`crate_type =
"staticlib"` or `"cdylib"`), the library itself is built as a static
archive and linked into every test, with `src/` on the include path, so
the tests exercise its API:

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
which compile `src/` without it unless `[build] compat_preprocessor = true`)
precisely so `<std/test/test.h>` can be included — verified against a real `safeguard test` run of the example
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

## `doc` — API reference {#safeguard-doc}

`safeguard doc` generates a library's API reference the way `rustdoc` does — from the declarations themselves and the doc comments on them — into `build/doc/`:

```bash
safeguard doc                 # build/doc/index.html
safeguard doc --serve         # and serve it at http://localhost:8000/ (--port N)
safeguard doc --std           # SafeC's own std (what the site's /api/ is)
```

What you get:

- a page per item — struct and union (fields, methods), enum (variants), trait (required methods), function, macro, constant, type alias — with its declaration highlighted, its doc comment rendered, and a link to its line in the source (and to the definition, when a method or function is defined elsewhere);
- a page per module: each header is a module, nested by directory (`include/geo/shapes.h` is `geo::shapes`), with its own doc and its items by kind; the crate page shows the `README.md` and the module tree; `all.html` lists everything;
- highlighted source pages with line anchors (`src/include/geo/shapes.h.html#L42`), and, when `Package.toml` names a `repository`, a link to the file at the release tag;
- [intra-doc links](/advanced/doc-comments#intra-doc-links) (`[Point::norm2]`), and every name in a signature linked to its item — std's types link into the [std API reference](/api/);
- search: a SafeC program compiled to WebAssembly (on `std/wasm/dom`) searches the reference's index in the browser — `/` or <kbd>Ctrl</kbd>+<kbd>K</kbd>, arrows and <kbd>Enter</kbd>;
- light and dark themes (the SafeC site's), following the OS until toggled.

Every link in the output is relative, so `build/doc/` works from any URL prefix or straight from disk, and `safeguard doc` fails if any link or `#anchor` in it is broken. The comment conventions it reads are on the [Doc Comments](/advanced/doc-comments) page.

| Option | Effect |
|--------|--------|
| `--serve`, `--port N` | Serve the result over HTTP after building it (default port 8000) |
| `--std` | Document SafeC's std (`$SAFEC_HOME/std`) instead of the project; no `Package.toml` needed |
| `--out DIR` | Write somewhere other than `build/doc/` |
| `--private` | Include hidden items: `@hidden`, names starting or ending with `_`, `static` functions |
| `--std-url URL` | Where links to std items point (default: this site's `/api/`; also `SAFEC_STD_DOCS_URL`) |
| `--home URL[=text]` | A header link back to the site hosting the reference |
| `--no-check` | Skip the link check |

**How it works.** The declarations come from the compiler's own front end, not from pattern matching: `safec --emit-doc-json` preprocesses and parses every header and source of the package (no semantic analysis or code generation) and writes each declaration — kind, name, namespace, generic parameters and bounds, parameters, fields, methods, enumerators, macros, its source text as the signature, and its location — with the doc comment read from the file around it, as JSON. The page generator, **safec-doc**, is itself a SafeC program (`safeguard/doctool` in the SafeC repository) built on std's web libraries: `std/serial/json` reads that file, `std/serial/markdown` renders the comments through a render hook (intra-doc links, highlighted code), scx templates lay out the pages, `std/dir` and `std/path` write them, `std/http` serves them, and the search client is SafeC compiled to `wasm32` on `std/wasm/dom`. A release ships it prebuilt (`bin/safec-doc`, with its assets in `share/safec/doctool/`); from a source checkout, `safeguard doc` builds it from those sources the first time and caches it (`~/.cache/safeguard/`, or `SAFEGUARD_CACHE`). Its search client needs clang's `wasm-ld`; without it the reference is built without search.

## `publish` — publishing a library {#safeguard-publish}

A SafeC library is published as a git repository: dependents name it by URL (`[[dependencies]] version = "https://…"`), each release carries prebuilt libraries, and its API reference lives on the repository's own Pages site — there is no central registry. `safeguard publish` sets that up:

```bash
safeguard publish                      # dry run: print what it would do
safeguard publish --write              # do it: .gitignore, CI, README/LICENSE
safeguard publish --tag --bump=minor   # 0.3.1 -> 0.4.0: commit, tag v0.4.0
safeguard publish --tag --push         # and push the branch and the tag
```

Step by step:

1. **git** — `git init -b main` when the project isn't in a repository.
2. **`.gitignore`** — adds what is missing of `build/`, `deps/`, `.safec_cache/`, `dist/`, `public/`, editor files (`.vscode/`, `.idea/`, `*.swp`, `*~`) and OS files (`.DS_Store`, `Thumbs.db`, `desktop.ini`).
3. **Metadata** — the version must be semver; a missing `description`, `license` or `repository` is reported (`repository` is filled in from the `origin` remote when there is one). A `README.md` is written when there is none; a `LICENSE` too when `license = "MIT"` (otherwise you are told to add the license text).
4. **Version** — `--bump=major|minor|patch` (or `--bump=1.4.0`) updates `version` in `Package.toml`; a version that isn't newer is refused.
5. **CI** — for the provider `--ci` (or `[publish] ci`) names, default `github`. It builds and tests on Linux, macOS and Windows on every push and pull request, installing LLVM 21's clang and a SafeC release the way the SafeC release workflow does (`.ci/install-safec.sh`); and on a version tag it builds prebuilt archives per OS/arch — `lib/` with the static library (`lib<name>.a`) and the shared one (`.so`, `.dylib`, or `.dll` + import `.lib`), `include/` with the exported headers — attaches them to a release, and deploys `safeguard doc` to Pages. An extracted archive is itself a [prebuilt dependency](#dependencies) directory.
6. **`--tag`** — the work tree must be clean and have a commit; runs `safeguard test` (`--no-verify` skips it), commits the files `publish` wrote, and creates the annotated tag `v<version>` (`[publish] tag_prefix`). An existing tag is refused.
7. **`--push`** — only when asked: pushes the current branch and the tag to `origin` (`--remote` for another).

Nothing is written without `--write` (`--tag` and `--push` imply it), and nothing is pushed without `--push`.

| `--ci` | Files | Build + test | Releases | Pages |
|--------|-------|--------------|----------|-------|
| `github` (default) | `.github/workflows/ci.yml`, `release.yml` | Linux x86_64, macOS arm64, Windows x86_64 (hosted runners) | GitHub Release with the archives | GitHub Pages via `actions/deploy-pages` — enable it once: *Settings → Pages → Source: GitHub Actions* |
| `gitlab` | `.gitlab-ci.yml` | Linux on `saas-linux-*`; macOS and Windows on GitLab.com's hosted `saas-macos-*` / `saas-windows-*` runners (macOS needs a Premium or Ultimate plan; on a self-managed instance, register runners with those tags or delete the jobs) | archives in the project's generic package registry, linked from a GitLab Release (`release-cli`) | GitLab Pages (the `pages` job publishes `public/`) |
| `gitea` | `.gitea/workflows/ci.yml`, `release.yml`, `.ci/upload-release.sh` | Linux on any runner labeled `ubuntu-latest`; macOS/Windows entries are commented out — Gitea's runners are self-hosted, so enable them once you register `macos` / `windows` runners | a Gitea release, through its API (`.ci/upload-release.sh`, needs a `RELEASE_TOKEN` secret with write access) | Gitea has no Pages service: the reference is pushed to a `pages` branch for a static host to serve |
| `forgejo` | `.forgejo/workflows/…` (same as Gitea) | Linux on the Forgejo runner's default `docker` label; macOS/Windows as for Gitea | a Forgejo release through the same API | the `pages` branch — on Codeberg, Codeberg Pages serves it at `https://<user>.codeberg.page/<repo>/` |

The prebuilt targets are the ones SafeC itself ships releases for (Linux x86_64, macOS arm64, Windows x86_64): CI installs a SafeC release, which needs one for its OS. The CI pins the SafeC release you have installed (`$SAFEC_HOME/.release`), so builds don't change under you; edit `SAFEC_VERSION` in the generated files to move. Generating the API reference in CI needs a SafeC release that includes `safeguard doc`.

## Cross targets {#cross-targets}

`--target <alias|triple>` (on `build`, `check`, `test`) cross-compiles the project and its standard library for another machine. A bare-metal alias (`x86`, `x86_64`, `aarch32`, `aarch64`, `thumb`, `thumb2`, `arm-6m`, `arm-7m`, `arm-7em`, `arm-8m`, `arm-81m`, `rv32i`, `rv32e`, `rv64i`, `rv64e`, `wasm32`, `wasm64` — see [Bare-Metal Targets](/reference/baremetal#targets)) implies freestanding mode; any LLVM triple is accepted too.

```bash
safeguard build --target arm-7em --release
safeguard test --target rv32e        # builds every test for RV32E, doesn't run them
```

- The std archive is built per target under `build/deps/<target>/libsafec_std.a`, from the [std modules](#standard-library-linking) the project uses, in the variant for that target (OS-specific backends are skipped on bare metal).
- Objects — SafeC, C, C++ and Objective-C alike — are compiled with the target's clang flags (`safec --print-target-clang-flags`), and bare-metal links use `ld.lld` with `-nostdlib -static`: supply the linker script, startup code and runtime (`memcpy`, `memset`, any `malloc`, compiler-rt builtins) through `[build] cflags`/`libs`.
- Functions your project defines that freestanding mode would otherwise flag as hosted-only (a runtime's own `malloc`, say) are passed to the compiler as `--provides`, so they don't warn.
- WebAssembly targets link a library module instead: no entry point, exports are the functions marked `export_name`, unresolved externs become imports, and the std archive includes `std/wasm/wasm_rt` (the wasm runtime). See [WebAssembly](/reference/baremetal#wasm).
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

The `[package]` section defines the project name and version, and optionally what `safeguard doc` and `safeguard publish` use:

```toml
[package]
name          = "geo"
version       = "0.3.1"                     # semver; 'publish --bump' updates it
author        = "Jane Doe"
description   = "2D geometry primitives"
license       = "MIT"
repository    = "https://github.com/jane/geo"   # source links in the docs, the CI's release URLs
homepage      = "https://jane.dev/geo"
documentation = "https://jane.github.io/geo/"   # where the API reference is published
readme        = "README.md"                 # shown on the reference's front page (default README.md)
keywords      = ["geometry", "math"]

[publish]
ci         = "github"     # github (default), gitlab, gitea, forgejo
branch     = "main"       # the branch CI builds
tag_prefix = "v"          # release tags: v1.2.3
pages      = true         # deploy 'safeguard doc' to Pages on a tag
release    = true         # attach prebuilt libraries to a release on a tag
```

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

On Windows a DLL is linked through its import library: `<name>.lib` (or `lib<name>.lib`) is found like the other names, and the DLL beside it — or in `../bin/`, where CMake installs DLLs — is copied next to the program after `build`, `run` and `test` (the job an rpath does elsewhere). `lib = ["vendor/mylib.dll"]` links the `mylib.lib` beside it.

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
| `"cmake"` | configures it with the same `clang`/`clang++` the build uses (`-G Ninja` when `ninja` is on `PATH`, `Release` for `--release`, `Debug` otherwise — `RelWithDebInfo` and `MultiThreadedDLL` on Windows, the shared release CRT the program uses, since the debug CRT doesn't mix with the program's), plus `cmake_args`; builds it; installs it into `build/deps/<name>/install`. The libraries are `lib` (relative to that install prefix) if given, else `lib<name>` or every library in its `lib/`, else the ones in the build tree for a project without `install()` rules |

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
then name each platform's libraries for the project's own code:

```toml
[build.macos]
frameworks = ["Security"]

[build.linux]
libs = ["sqlite3"]

[build.windows]
libs = ["winsqlite3", "crypt32"]
```

The system libraries std's own modules call into (Cocoa, X11 or
user32/gdi32 for `std::gui`, Metal for the GPU backends, `ws2_32` for
networking on Windows, ...) needn't be listed: safeguard links them for
the modules in the build (see [Standard Library
Modules](#standard-library-linking)).

Any other `[build.<name>]` table, or another key in one, is an error.

### C, C++ and Objective-C packages {#c-c-and-objective-c-packages}

A package needn't contain SafeC at all. `safeguard new <name> --lang c`
(or `cpp`, `objc`, `objcpp`) scaffolds `src/main.c` (`.cpp`, `.m`, `.mm`;
the Objective-C ones with `objc_arc = true` and `frameworks =
["Foundation"]`), and such a package builds as a `bin`, `staticlib` or
`cdylib` like any other, exporting its headers the same way. When neither
the package's sources nor a SafeC source dependency contain SafeC, the
standard library is neither built nor linked.

A C, C++ or Objective-C package that calls into std (through `extern`
declarations of its functions, which SafeC exports with C linkage as
`std_<name>`) sets `[build] std = true`: std is then built and linked, and
std's parent directory goes on the C/C++ include path so its headers are
reachable as `<std/...>` (never `std/` itself, whose `stdint.h` and
friends would shadow the system's). `safeguard new <name> --lang c --std`
writes that key when the project is created. `std = false` never links
std, even with SafeC sources; `std = "all"` compiles every std module into
the archive, not just the ones the sources include (see [Standard Library
Modules](#standard-library-linking)).

```toml
[build]
edition = "2026"
std     = true      # build and link SafeC's std in a C/C++/ObjC package
```

`compat_preprocessor = true` compiles the package's SafeC sources with
`safec --compat-preprocessor`: function-like macros, `##` and `#`, which
the default safe mode rejects (see [Preprocessor](/reference/preprocessor#safe-mode-vs-compatibility-mode)).
[`std/async`](/stdlib/async)'s coroutine macros (`ASYNC_BEGIN`, `AWAIT`, ...)
are the usual reason:

```toml
[build]
compat_preprocessor = true
```

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

`blas` picks the CPU vendor's BLAS that [`sgemm`](/stdlib/simd#gemm) — and through it `tensor_matmul` and `Conv2D::forward` — uses, chosen when the program is linked. It is linked only when the build includes `std/simd/gemm.h` (directly, or through `std::ml` or `std::dsp`):

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
| `"cdylib"` | `build/lib<output>.dylib` (macOS), `build/lib<output>.so` (Linux, with that SONAME), or `build/<output>.dll` and its import library `build/<output>.lib` (Windows); and `build/include/` | linked with `-shared` |

A library build also exports its public headers to `build/include/`: everything under the project's `include/`, and the headers (`.h`, and C++'s `.hh`/`.hpp`/`.hxx`) under `src/`, with their paths kept (`include/mylib/config.h` → `build/include/mylib/config.h`, `src/mylib.h` → `build/include/mylib.h`). These are the headers a dependent building the package from source would see. The directory is rebuilt on every build, so a deleted header leaves it too. A header at the same path in both `include/` and `src/` is an error. `build/` is then a [prebuilt dependency](#dependencies) as it stands:

```toml
[[dependencies]]
name = "mylib"
path = "../mylib/build"    # links libmylib.a, puts build/include on the include path
```

The project's `include/` is on the include path of its own SafeC, C and C++ sources, so a library's sources include the headers it exports the same way its users do.

### Windows DLLs {#windows-dlls}

A DLL exports only the symbols it's told to, and SafeC has no
`dllexport`. A Windows `cdylib` therefore exports every external function
defined in the package's own object files — the same set a `.so` or
`.dylib` exposes from them — listed in `build/<output>.def` (read from the
objects with `llvm-nm`, which ships with LLVM beside `clang`) and passed to
the linker, which also writes the import library `build/<output>.lib`.
Not exported:

- the standard library's and the dependencies' code linked into the DLL;
- `static` functions, and C++-mangled names (SafeC can't call those);
- global variables — importing data from a DLL needs `__declspec(dllimport)`
  on the consumer's side, which SafeC can't express. Export an accessor
  function instead.

A package that defines no functions gets a warning and no import library.
A dependent links `<output>.lib` and gets the DLL copied beside its
executable ([Dependencies](#dependencies)), so `path = "../mylib/build"`
works on Windows as it does elsewhere.

Every Windows (MSVC-ABI) build uses the **shared C runtime**: objects are
compiled with `-fms-runtime-lib=dll` (`/MD`), the link takes `msvcrt`
instead of the static `libcmt`, and CMake dependencies build with
`MultiThreadedDLL`. A DLL and the program that loads it therefore share
one CRT and one heap, so memory allocated on one side of the DLL boundary
may be freed on the other, and `FILE*`s and `errno` are shared too. The
program needs the Universal CRT and the Visual C++ runtime DLLs at run
time, which ship with Windows 10 and later (the VC++ redistributable for
older systems). MinGW targets keep their own runtime.

`safeguard run` requires `crate_type = "bin"` — a static or dynamic library
has no executable entry point to run, and running it against the linker
directly produces a clear error rather than a confusing OS-level failure.

## App Bundles {#app-bundles}

`safeguard bundle` builds a `bin` target and packages it the way the
platform installs applications. The `[bundle]` table describes it:

```toml
[bundle]
name               = "GuiHello"              # default: the package name
identifier         = "org.example.gui-hello" # required, reverse-DNS
version            = "1.0.0"                 # default: the package version
icon               = "assets/icon.png"       # .png anywhere; .icns (macOS), .ico (Windows)
resources          = ["assets/greeting.txt", "assets/data"]  # files or directories
category           = "Utility"               # freedesktop category, or "public.app-category.*"
copyright          = "Copyright 2026 Example"
subsystem          = "windows"               # Windows: "windows" (default) or "console"
min_system_version = "11.0"                  # macOS LSMinimumSystemVersion (default 11.0)
codesign_identity  = "-"                     # macOS: "-" ad-hoc (default), an identity, "none"
depends            = ["libsqlite3-0"]        # Linux: more of the .deb's Depends
```

```bash
safeguard bundle                              # build/GuiHello.app, build/GuiHello/, or a .deb
safeguard bundle --release --sign "Developer ID Application: Example (TEAMID)"
```

| Platform | Output |
|---|---|
| macOS | `build/<name>.app`: `Contents/MacOS/<output>`, `Contents/Info.plist` (`CFBundleIdentifier`, `CFBundleName`, `CFBundleExecutable`, `CFBundleShortVersionString`, `CFBundleVersion`, `CFBundleIconFile`, `NSHighResolutionCapable`, `LSMinimumSystemVersion`, `LSApplicationCategoryType`), `Contents/Resources/` (the icon and the resources) and `Contents/Frameworks/` (the dependencies' `.dylib`s) |
| Windows | `build/<name>/`: `<output>.exe`, the dependencies' DLLs and the resources — a portable app folder |
| Linux | `build/<pkg>_<version>_<arch>.deb` and `build/<name>.AppDir` |

**macOS.** A `.png` icon becomes an `.icns` (scaled to each size with
`sips`, packed with `iconutil`). Each dependency `.dylib` copied into
`Contents/Frameworks` gets the install name `@rpath/<file>` (in the
library and in the executable, with `install_name_tool`), and the
executable is linked with an rpath to `@executable_path/../Frameworks`,
so the bundle doesn't depend on the build tree. The nested libraries and
then the bundle are signed with `codesign`: ad-hoc by default,
`codesign_identity` or `--sign` for a real identity, `"none"` to skip.

**Windows.** The executable is linked for the GUI subsystem
(`/SUBSYSTEM:WINDOWS` with `/ENTRY:mainCRTStartup`, so a plain `main` still
works and no console window opens; `subsystem = "console"` keeps one) with
a resource compiled by `llvm-rc` (or the Windows SDK's `rc`): the icon (an
`.ico`, or a `.png` of up to 256×256 wrapped in one), version information
from the package (`FileVersion`, `ProductName`, `CompanyName` from the
author, ...) and an application manifest declaring per-monitor DPI
awareness and Common Controls v6.

**Linux.** The `.deb` (built with `dpkg-deb`) installs `/usr/bin/<output>`,
`/usr/share/applications/<identifier>.desktop`, the `.png` icon as
`/usr/share/icons/hicolor/<w>x<h>/apps/<identifier>.png`, the resources
under `/usr/share/<pkg>/`, and the dependencies' shared libraries under
`/usr/lib/<pkg>/` (on the executable's rpath). `DEBIAN/control` has
`Package` (the package name in Debian's form, `_` becoming `-`),
`Version`, `Architecture` (`dpkg --print-architecture`), `Maintainer`
(the package's `author`), `Description`, `Installed-Size` and `Depends`:
`[bundle] depends`, plus the packages std's modules name for the system
libraries they link (`libx11-6` for `std::gui` on X11).
The same tree, with `AppRun`, the desktop entry and the icon at its top,
is `build/<name>.AppDir`, ready for `appimagetool`. Without `dpkg` only the
AppDir is made, with a warning.

A GUI program uses [`std::gui`](/stdlib/gui), whose backend calls each
platform's own libraries; safeguard links them by itself (Cocoa and the
Objective-C runtime, X11, or user32 and gdi32). The SafeC repository's
`examples/gui_hello/` opens a window, draws for a second and closes it
again; its manifest is the `[bundle]` above, without `depends`, and names
no library.

## Standard Library Modules {#standard-library-linking}

A build compiles and links only the parts of the standard library (`std/`) it uses — no manual configuration needed — except a [package with no SafeC in it](#c-c-and-objective-c-packages), which doesn't use std at all.

**Which modules.** safeguard reads the `#include <std/...>` lines of the project's sources (and of the project headers they include, and of a SafeC dependency's sources; with [`std = true`](#c-c-and-objective-c-packages), of its C, C++ and Objective-C sources too). Each std header brings in the std sources implementing it, whose own includes count in turn: `std/gui/gui.h` brings `gui/gui_cocoa.sc` on macOS (`gui_x11.sc` on Linux, `gui_win32.sc` on Windows), `std/ml/tensor.h` the tensor core, `sgemm` and the threading it uses. `safeguard test` does the same for the files under `tests/`. Includes inside an `#if` count, unless the condition is a plain test of `__APPLE__`, `_WIN32` or `__linux__` (decided by the target) or, in the project's own files, of a `SAFEC_FEATURE_<NAME>` macro (decided by its [features](#feature-flags)). The GPU device backends of `std::ml` come with their features: `--features mps` builds `device_mps.sc`, `rocm` and `cuda` theirs.

**What the code calls.** Some of std is called by code no header names: safec compiles `new` to a call into `std/mem.sc`, channels (`chan_create` and the rest) into `std/sync/channel.sc`, and `spawn`/`join` on Linux and macOS into `pthread_create`/`pthread_join`. safeguard reads the external symbols of the IR it compiles — the project's sources, a SafeC dependency's, and the std sources in the build — and adds the std sources they call, along with whatever those include and call in turn. A program with no channels and no threads compiles neither `sync/channel.sc` nor `thread.sc`, and links no `-lpthread` on Linux; one using channels gets both, and `spawn` alone brings `thread.sc` for `-lpthread`.

**Caching.** Each std source compiles to its own object under `build/deps/std/` (`build/deps/<target>/std/` for a [cross target](#cross-targets)), recompiled only when it, a std header it includes, or the compiler changes; the build's objects are packed into `build/deps/libsafec_std.a`, from which the linker still takes only what the program references. std is always compiled optimized, with the flags of the [prebuilt std](#prebuilt-std) (`-O2`, and on macOS `-mmacosx-version-min=11.0` or your `MACOSX_DEPLOYMENT_TARGET`), whether the build is a debug or a `--release` one: the two share these objects, and a debug build doesn't get a slow std. Your own code still follows the build's mode. (Objects compiled before this rule, at `-O0`, are rebuilt once.) A hello world on `std/io.h` compiles 4 std sources instead of all 219 (0.4 s instead of 15 s on an M1 Pro), and a program including no std header and calling nothing above, none. A [release install](#prebuilt-std) compiles none of them at all.

**System libraries.** The libraries std's modules call into are added to the link — after the objects and archives, deduplicated, and only for the modules in the build:

| Module | macOS | Linux | Windows |
|---|---|---|---|
| `std/math.h`, `std/fenv.h` | `-lm` | `-lm` | (in the C runtime) |
| `std/thread.h`, channels, `spawn` | — | `-lpthread` | — |
| `std/gui/gui.h` | `-framework Cocoa -lobjc` | `-lX11` (.deb: `libx11-6`) | `-luser32 -lgdi32` |
| `std/audio/audio.h` | `-framework CoreAudio -framework CoreFoundation` | `-ldl -lpthread` (libasound, libjack loaded at run time) | `-lole32` |
| `std/audio/midi.h` | `-framework CoreMIDI -framework CoreFoundation` | `-ldl -lpthread` | `-lwinmm` |
| `std/http/http.h`, `std/sched/` | — | — | `-lws2_32` |
| `std/crypto/rng.h` | — | — | `-lbcrypt` |
| `std/simd/gemm.h` (and `std::ml`, `std::dsp` through it) | the [`[build] blas`](#linking-external-libraries-and-library-output) choice: Accelerate by default | the `[build] blas` choice | — |
| `std/ml/tensor_vdsp.h`, `tensor_blas.h` | `-framework Accelerate` | — | — |
| `std/ml/gpu_mps.h`, `--features mps` | `-framework Metal -framework Foundation -lobjc` | | |
| `std/ml/gpu_rocm.h`, `--features rocm` | | `-lamdhip64 -lhiprtc -lrocblas` | |
| `std/ml/gpu_miopen.h` / `std/dsp/fft_rocm.h` | | `-lMIOpen` / `-lhipfft` | |
| `std/ml/gpu_cuda.h`, `--features cuda` | | `-lcuda -lcublas -lcublasLt -lnvrtc` | the same |
| `std/ml/gpu_cudnn.h` / `std/dsp/fft_cuda.h` | | `-lcudnn` / `-lcufft` | the same |
| `std/ml/gpu_spirv.h` | `-lvulkan` | `-lvulkan` | `-lvulkan-1` |
| `std/ml/gpu_webgpu.h` | `-lwgpu_native` | `-lwgpu_native` | `-lwgpu_native` |

ROCm's libraries are looked up in `$ROCM_PATH/lib` (default `/opt/rocm/lib`), CUDA's in `$CUDA_HOME/lib64` (default `/usr/local/cuda/lib64`; `$CUDA_PATH/lib/x64` on Windows), Vulkan's in `$VULKAN_SDK`, wgpu's in `$WGPU_HOME/lib` — each added with an rpath when the directory exists. MIOpen and cuDNN stay optional for the device backends (weak references: without them convolutions use im2col + GEMM); a program including `gpu_miopen.h` or `gpu_cudnn.h` itself links them. The C library comes with the compiler driver as always. `[build] libs`/`frameworks` and `[build.<os>]` add the project's own libraries after these.

`safeguard std-modules` prints what a build takes from std — the sources (`unit` lines) and the system libraries (`link`, and `deb` for a `.deb`'s Depends) — for the project, or for std headers and files given on the command line:

```bash
$ safeguard std-modules std/gui/gui.h
unit std/atomic.sc
unit std/gui/gui_cocoa.sc
...
link -framework Cocoa
link -lobjc
$ safeguard std-modules --target x86_64-unknown-linux-gnu std/gui/gui.h
...
link -lX11
deb libx11-6
```

**All of std.** `[build] std = "all"` compiles every std module into the archive, as before modules: for an archive linked by hand (`clang prog.ll build/deps/libsafec_std.a ...`), or for code that reaches std through `extern` declarations without including its headers. A [staticlib](#linking-external-libraries-and-library-output) records the std files its sources include in `build/lib<output>.a.std`, and the symbols its code calls in `build/lib<output>.a.refs`, so a program linking it as a prebuilt dependency builds those modules too; a library built otherwise needs `std = "all"` in the program using it.

**Writing std.** A std header `dir/foo.h` is implemented by `dir/foo.sc` and by the `dir/foo_<suffix>.sc` files that have no header of their own (the platform variants `gui_cocoa.sc`, `fft_f32.sc`, ...). A platform variant's suffix picks the targets it builds for: `_linux`/`_epoll` Linux, `_bsd`/`_kqueue` macOS and the BSDs, `_cocoa`/`_macos` macOS, `_x11` Linux and the BSDs, `_win32` Windows, `_posix` every hosted target but Windows, `_wasm` WebAssembly, `_fb` bare metal. Anything else, and the system libraries, is declared in comment lines a std source carries:

```c
// safeguard: implements <std/gui/gui.h>        another header this file implements
// safeguard: sources <std/x/extra.sc>          (in a header) another file implementing it
// safeguard: feature mps                       only in builds with this feature
// safeguard: runtime-symbol __safec_chan_      built when the code calls a symbol starting so
// safeguard: runtime baremetal wasm            always built for these targets
// safeguard: link macos -framework Cocoa -lobjc
// safeguard: link linux -L${ROCM_PATH:-/opt/rocm}/lib -lamdhip64
// safeguard: link hosted blas                  the [build] blas choice
// safeguard: deb linux libx11-6
```

The OS of a `link` or `deb` line is `macos`, `linux`, `windows`, `bsd`, `unix` (macOS, Linux, BSD), `hosted`, `baremetal`, `wasm` or `any`. `tests/std_modules/check.py`, run by CI, compiles every std header alone and fails if a function it declares, or a symbol the modules it brings leave undefined, is defined by a std source outside them.

Each SafeC dependency built from source is similarly compiled to `build/deps/lib<name>.a`, a C/C++ one into `build/deps/<name>/`; prebuilt ones are linked from where they are.

### Prebuilt std {#prebuilt-std}

A release archive (and so an install from `install.sh`/`install.ps1`) ships std already compiled for its platform, in sections: one archive per top-level directory of `std/`, and `core` for the files directly in it.

```
<prefix>/lib/aarch64-apple-darwin/std/  (x86_64-unknown-linux-gnu, x86_64-pc-windows-msvc)
├── core.a            std/*.sc: io, fmt, mem, string, math, thread, ...
├── core.units        its stamp (the safec that built it, a hash of the binary, and
│                     the target), each source's input hash and the symbols it calls
├── collections.a     std/collections/
├── collections.units
├── gui.a, gui.units
├── ml.a, ml.units
└── ...               alloc, crypto, debug, dsp, encoding, fs, hal, http, interrupt,
                      ipc, kernel, net, reactive, rpc, sched, scx, serial, simd, sync, test
```

Each archive holds the section's sources that no [feature](#feature-flags) gates, compiled optimized as above (for macOS 11.0 and later on macOS). A native build looks for the sections beside the `safec` it uses (`<bin>/../lib/<triple>/std/`) and under `$SAFEC_HOME/lib/<triple>/std/`. The modules are worked out as above; then each one comes from its section when that section is installed and its stamp matches the compiler and the host, and is compiled otherwise. The build links only the sections holding its modules: a hello world on `std/io.h` and `std/collections/vec.h` links `core.a` and `collections.a`, not `gui.a` or `ml.a`, and compiles no std at all; nor does a `std::gui` window or a `std::ml` training loop.

What a section doesn't have is compiled into `build/deps/libsafec_std.a`:

- **a section that isn't installed** (`install.sh --std=...`, see below): its sources the build uses are compiled, the other sections still linked prebuilt;
- **a GPU backend whose feature is on** (`--features mps`, ...);
- **a std source changed since the release was built**: it is compiled, and the section is linked from a copy without its old object (`build/deps/std-prebuilt/<section>.a`), so no source is linked twice.

`--verbose` says which sections are linked and which sources are compiled and why:

```
safeguard: std modules (6): atomic.sc collections/vec.sc io.sc io_file.sc mem.sc panic.sc
safeguard: std section 'collections' is not installed in ~/safec/lib/aarch64-apple-darwin/std; compiling its units from source: std/collections/vec.sc
safeguard: linking the prebuilt std sections core from ~/safec/lib/aarch64-apple-darwin/std
```

Cross, bare-metal and WebAssembly builds don't use the prebuilt std; neither does a build with `[build] std_prebuilt = false`, `SAFEC_STD_FROM_SOURCE=1` or extra compiler flags, or, on macOS, with a `MACOSX_DEPLOYMENT_TARGET` older than 11.0. A section stamped for another compiler or target is passed over and compiled from source, with a note under `--verbose`. On macOS the sections are built for 11.0, so a program built for 11.0 or later links them without "built for newer macOS version" warnings; safeguard passes no minimum of its own, and clang's default (the running macOS) or `MACOSX_DEPLOYMENT_TARGET` applies to your code.

**Choosing sections.** `install.sh --std=core,collections,gui` installs only those sections (`install.ps1 -Std core,collections,gui`); the default is `all`. `--std=+ml` (`-Std +ml`) adds sections to an existing install later, from the release it was installed from (recorded in `<prefix>/.release`), changing nothing else. std's sources are always installed, so any section works either way; a missing one only costs its compile time, once per project.

`safeguard std-prebuilt <prefix>` writes the sections (the release workflow runs it through `.github/scripts/package.sh`, which lays out the whole install). `tests/std_modules/check.py --prebuilt <prefix>/lib/<triple>/std` checks them: each section holds exactly its sources, no symbol is defined in two sections, and each std header's modules are complete with only their sections linked.

`SAFEC_STD_ARCHIVE=/path/to/libsafec_std.a` links that archive instead, whatever it is: nothing is compiled, the linker takes the members the program references, and the system libraries still come from the modules the sources include when `std/` is found (`SAFEC_HOME`). Without `std/`, safeguard can't tell which modules are used and adds none: list them in `[build] libs`/`frameworks`.

## Environment Setup {#environment-setup}

`safeguard` locates the SafeC toolchain using the `SAFEC_HOME` environment variable:

```bash
export SAFEC_HOME=/path/to/SafeC
```

This should point to the SafeC repository root. From there, `safeguard` auto-discovers:

- The `safec` compiler binary at `$SAFEC_HOME/compiler/build/safec`
- The standard library at `$SAFEC_HOME/std/`
- In an install, the [prebuilt std](#prebuilt-std) sections at `$SAFEC_HOME/lib/<triple>/std/`

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
