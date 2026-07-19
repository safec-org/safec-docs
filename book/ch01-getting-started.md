# Chapter 1: Getting Started

## Installing SafeC

```bash
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash
```

This installs three binaries — `safec` (the compiler), `safeguard` (the
package manager and build tool, playing the same role `cargo` plays for
Rust), and `sc-lsp` (the language server, for editor integration) — and
wires up your shell so all three are on `PATH`. Restart your shell (or
source its rc file) once the script finishes, then check it worked:

```bash
safec --version
```

For install-script options (custom prefix, a specific release version,
building from source instead of the prebuilt release) see
[Getting Started](/guide/getting-started) in the Guide section — this
chapter only covers what you need to write and run your first program.

## `safeguard`: creating a project

Real SafeC projects use `safeguard`, not bare `safec` invocations — it
handles the standard library, dependencies, and the compile-then-link
sequence for you. Create one:

```bash
safeguard new hello
cd hello
```

This scaffolds:

```
hello/
├── Package.toml
└── src/
    └── main.sc
```

`Package.toml` is the project manifest (name, version, dependencies —
[Package Manager](/advanced/safeguard) covers its full shape later).
`src/main.sc` is your entry point, and it already compiles as-is:

```bash
safeguard run
```

```
Hello from SafeC!
```

## Anatomy of a SafeC program

Open `src/main.sc`:

```c
extern int printf(const char* fmt, ...);

int main() {
    printf("Hello from SafeC!\n");
    return 0;
}
```

If you've written C, every line of this is already familiar — that's
deliberate. SafeC is a strict superset of C's syntax; the one line worth
pausing on is the `extern` declaration. `printf` is a C standard library
function, not a SafeC one, so it needs an explicit signature before you
can call it — SafeC doesn't implicitly know what `printf` is the way a
`#include <stdio.h>` in plain C would let you assume. You *can* still
`#include <stdio.h>` directly:

```c
#include <stdio.h>

int main() {
    printf("Hello from SafeC!\n");
    return 0;
}
```

and SafeC will import `printf`'s declaration (and everything else in the
header) automatically — the compiler invokes `clang` behind the scenes to
read the header and extract the declarations it needs. Both forms compile
to the same thing; this book mostly writes out explicit `extern`
declarations for the handful of C functions each example needs, so every
example's full set of dependencies is visible in the snippet itself rather
than hidden behind a header include.

## The edit-compile-run loop

`safeguard run` is really three steps — `safeguard build` (compile
everything under `src/`, link it into `build/hello`), then execute the
result. While you're iterating, `safeguard check` is faster: it runs the
full front end (so every type error and region-safety violation is still
caught) but skips assembling and linking, since that's normally the
majority of the wall-clock time. Reach for `check` while you're fixing
compile errors, `run` once you want to see it actually execute:

```bash
safeguard check   # fast: did I break anything?
safeguard run     # slower: build and execute
```

## A note on `unsafe`

You'll see the `unsafe` keyword show up starting in the next couple of
chapters, well before Chapter 5 properly explains what it's guarding
against. A one-sentence preview: SafeC tracks, at compile time, where
every reference points and whether it's still valid — dereferencing a raw
pointer, or converting a reference to one, steps outside what the compiler
can verify, so it has to be marked explicitly. Chapter 5 is where this
actually gets explained; until then, treat `unsafe { ... }` as a marker
meaning "trust me" and move on — you'll have the full picture soon enough.

Next: [A First Program](/book/ch02-first-program), where we'll write
something a little more substantial than "Hello, world."
