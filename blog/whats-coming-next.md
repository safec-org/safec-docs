---
title: "What's coming next: everything merged since 1.0.0-beta-6"
date: 2026-10-26
author: The SafeC Team
tags: [unreleased, std, compiler, safety, breaking, tools]
summary: "A log of everything on SafeC's main branch that the next release will ship — the web stack, async, the GUI toolkit, audio and MIDI, HTTPS, safeguard doc and publish, the self-hosted compiler behind the online playground — and the two breaking changes: a std API without void* or pointer + length pairs, and compiler-enforced Drop and Send/Sync. With a migration guide."
---

SafeC's `main` branch has moved a long way since [1.0.0-beta-6](/blog/releases/v1.0.0-beta-6). This post is the running log of what the next release will contain: the pull requests merged since then, [#56](https://github.com/safec-org/SafeC/pull/56) through [#79](https://github.com/safec-org/SafeC/pull/79), grouped by area. Two of them are **breaking changes** that the compiler reports at every affected call site; the [migration guide](#migration) at the end shows how to update code.

SafeC's [`CHANGELOG.md`](https://github.com/safec-org/SafeC/blob/main/CHANGELOG.md), which this site renders as the [changelog](/changelog), records the later changes — `safeguard doc` and `publish`, scc, the slice API and the ownership checks — under *Unreleased*, down to individual signatures. The first half of this cycle — the web stack, async, the GUI toolkit, audio and HTTPS — has a longer write-up in [This cycle in SafeC](/blog/this-release-cycle).

## At a glance {#at-a-glance}

| Area | What changed | Pull requests |
|---|---|---|
| [Web stack](#web) | `std/http` server, router, WebSocket, SSE; URL and DEFLATE codecs; DOM interop for wasm | [#57](https://github.com/safec-org/SafeC/pull/57) |
| [Async](#async) | Rust-style futures as `goto` state machines, executors, async TCP | [#61](https://github.com/safec-org/SafeC/pull/61) |
| [GUI](#gui) | Vector canvas, widgets, multiple windows; X11, Wayland, Win32, Cocoa and the web; GPU presenters | [#63](https://github.com/safec-org/SafeC/pull/63), [#65](https://github.com/safec-org/SafeC/pull/65) |
| [Audio & MIDI](#audio) | One callback API over ALSA, JACK, Core Audio, WASAPI, Web Audio | [#59](https://github.com/safec-org/SafeC/pull/59) |
| [HTTPS](#https) | `std/tls`: the OS's TLS, with a SafeC TLS 1.3 fallback | [#66](https://github.com/safec-org/SafeC/pull/66) |
| [safeguard doc & publish](#safeguard-doc) | rustdoc-style API reference; library publishing with CI | [#69](https://github.com/safec-org/SafeC/pull/69) |
| [scc & the playground](#scc) | A SafeC compiler written in SafeC, compiling to WebAssembly | [#70](https://github.com/safec-org/SafeC/pull/70) |
| [Slices, not `void*`](#slices) — **breaking** | std's public API takes slices and typed erasure | [#76](https://github.com/safec-org/SafeC/pull/76) |
| [Drop, Send and Sync](#ownership) — **breaking** | Compiler-checked ownership and thread safety | [#77](https://github.com/safec-org/SafeC/pull/77) |
| [Fixes](#fixes) | Codegen, safety-check gaps, Windows and macOS CI | [#64](https://github.com/safec-org/SafeC/pull/64), [#68](https://github.com/safec-org/SafeC/pull/68), [#71](https://github.com/safec-org/SafeC/pull/71)–[#75](https://github.com/safec-org/SafeC/pull/75), [#78](https://github.com/safec-org/SafeC/pull/78), [#79](https://github.com/safec-org/SafeC/pull/79) |

Releases also now ship std prebuilt per platform, one archive per section (`core`, `collections`, `gui`, …), selected with `install.sh --std=...` / `install.ps1 -Std ...` ([#56](https://github.com/safec-org/SafeC/pull/56)), and this website is now [built by a SafeC program](/blog/new-website) ([#58](https://github.com/safec-org/SafeC/pull/58)).

## A web stack {#web}

[`std/http`](/stdlib/http) ([#57](https://github.com/safec-org/SafeC/pull/57)) is now a stack for real services: structured headers, a keep-alive `HttpServer` with chunked transfer coding, a `Router` with middleware and route patterns, static files, cookies, multipart forms, server-sent events and WebSocket upgrades, and a URL client that follows redirects and decodes gzip. It brought [`std/encoding`](/stdlib/encoding)'s URL (RFC 3986) and DEFLATE / zlib / gzip codecs. On the browser side, [`std/wasm`](/stdlib/wasm) gained DOM and browser-API interop — events, timers, fetch, storage, history — with its JavaScript glue shipped in std. `examples/fullstack_demo` puts a Router server and a SafeC-to-wasm client together.

## Async: futures as `goto` state machines {#async}

[`std/async`](/stdlib/async) ([#61](https://github.com/safec-org/SafeC/pull/61)) brings Rust's `Future` / `Poll` / `Waker` / `Context` model to SafeC as a library, with no language coroutines: the `ASYNC_BEGIN` / `AWAIT` / `ASYNC_RETURN` macros turn a struct with a `poll` method into a resumable state machine over `goto`, keeping every local that lives across an await in the struct. On top come timers, `join` / `select` / timeout combinators, channels, `Mutex`, `Semaphore`, `Notify`, cancellation, a no-heap `LocalExecutor` that also runs on bare metal and wasm32, a multi-threaded `ThreadPool`, and an `IoDriver` + `Runtime` with async TCP on the epoll, kqueue and WSAPoll reactor.

## A GUI toolkit {#gui}

[`std/gui`](/stdlib/gui) became a toolkit in [#63](https://github.com/safec-org/SafeC/pull/63): an anti-aliased [vector canvas](/stdlib/gui-graphics) with TrueType text, a retained [widget toolkit](/stdlib/gui-widgets) with layout and theming, and `GuiApp` for multi-window applications, all on a `GuiBackend` interface over the X11, Win32 and Cocoa window layers. [#65](https://github.com/safec-org/SafeC/pull/65) added [more platforms](/stdlib/gui-platforms): Wayland (chosen at run time on Linux), a WebAssembly backend that draws into a `<canvas>` or through WebGL with DOM interop and IME text input, and GPU presenters over OpenGL ES (EGL), Metal and Direct3D 11. `safeguard bundle --target wasm32` packages a GUI app as a web page.

## Audio and MIDI {#audio}

[`std/audio`](/stdlib/audio) ([#59](https://github.com/safec-org/SafeC/pull/59)) is one callback-based API for audio streams and MIDI ports, with format conversion. Audio runs on ALSA, JACK, Core Audio, WASAPI, Web Audio and a dummy device for tests; MIDI on the ALSA sequencer, JACK, Core MIDI, WinMM and Web MIDI.

## HTTPS {#https}

[`std/tls`](/stdlib/tls) ([#66](https://github.com/safec-org/SafeC/pull/66)) uses the operating system's TLS where there is one — OpenSSL (loaded at run time), SChannel, SecureTransport — and falls back to a TLS 1.3 client and server written in SafeC, with X.509 path validation against the system's trust roots. [`std/crypto`](/stdlib/crypto) grew X25519, P-256 / P-384 ECDH and ECDSA, RSA (PKCS#1 v1.5 and PSS), HKDF and ChaCha20 for it. `http_fetch` and friends speak `https://`, `WsConn` speaks `wss://`, and `HttpServer` serves HTTPS with a `TlsServerConfig`.

## `safeguard doc` and `safeguard publish` {#safeguard-doc}

[`safeguard doc`](/advanced/safeguard#safeguard-doc) ([#69](https://github.com/safec-org/SafeC/pull/69)) generates a package's API reference, like rustdoc: a page per item with highlighted declarations, Markdown [doc comments](/advanced/doc-comments) with intra-doc links (`[Vec::push]`) and examples, a module per header, source pages, client-side search (SafeC compiled to wasm32), light and dark themes, and a link check of the result. `--serve` serves it, `--std` documents std itself — that is how this site's [std API reference](/api/) is built. Underneath is the new `safec --emit-doc-json`, which dumps the declarations and their doc comments as JSON from the real front end.

[`safeguard publish`](/advanced/safeguard#safeguard-publish) prepares a library's git repository for publishing: README / LICENSE / metadata checks, `--bump` for semver, and generated CI (GitHub Actions by default; GitLab CI, Gitea or Forgejo with `--ci=`) that tests on Linux, macOS and Windows and, on a tag, attaches prebuilt static and shared libraries to a release and deploys the docs to Pages. It is a dry run unless you pass `--write`. `Package.toml` gained `repository`, `homepage`, `documentation`, `readme`, `keywords` and a `[publish]` table, and `safeguard build --crate-type staticlib|cdylib|bin` overrides `[build] crate_type`.

## scc and the online playground {#scc}

**scc** ([#70](https://github.com/safec-org/SafeC/pull/70)) is a SafeC compiler written in SafeC, in SafeC's new [`selfhost/`](https://github.com/safec-org/SafeC/tree/main/selfhost) directory. It compiles SafeC straight to a WebAssembly binary with no LLVM and no linker, through its own lexer, preprocessor, parser, semantic analysis (with safec's safety checks and diagnostics) and wasm backend. It builds with the C++ `safec`, natively and as WebAssembly, and it compiles itself: `selfhost/bootstrap.sh` checks that the self-compiled compiler reproduces itself byte for byte. A differential test harness compiles the Book, the reference programs and a corpus with both compilers and compares the results, in a new `selfhost` CI job — it is what found the variadic promotion bug [below](#fixes).

scc is what powers this site's [Playground](/playground): its *Run* button now compiles and runs your program entirely in the browser, in a Web Worker with time and memory limits. scc accepts the subset of the language listed in `selfhost/SUBSET.md` and does not run the ownership and thread-safety checks; for those and the full std, build locally.

## Breaking: std's API without `void*` or pointer + length pairs {#slices}

[#76](https://github.com/safec-org/SafeC/pull/76) removed every `void*`, raw `T*` buffer and `(pointer, length)` pair from std's public declarations:

- **Buffers are [slices](/reference/types#slice-types).** `[]T` is a buffer the function writes, `[]const T` one it only reads. A slice carries its length and indexing it is bounds-checked; a fixed-size argument (a key, a nonce, a digest) is checked on entry and panics if it is too short, instead of being read or written past. `std::str_bytes("text")` gives a string's bytes as a `[]const char`, accepted wherever `[]const unsigned char` is expected.
- **Type erasure is typed.** Where std stores callbacks with different context types side by side — executors, wakers, timers, widgets, the VFS, audio and MIDI callbacks, HTTP handlers, DOM listeners, thread entry points — the slot is a `?&Erased` (`std/erased.h`) filled only by generic methods that take a matching `fn(?&T)` / `?&T`. Opaque handles are typed structs too.
- **The language grew to match:** [read-only slices](/reference/types#read-only-slices) `[]const T`, [optional slices](/reference/types#optional-slices) `?[]T` and nullable function references `?fn R(Args)`, slice-returning functions and slice fields, range-checked re-slicing (`a[i..j]` traps when `i > j` or `j > len` outside `unsafe`), and byte slices that interconvert across `char`, `signed char` and `unsigned char`. Casting a slice to a pointer is now an error: use `s.ptr`.
- New in std: `Erased`, `S24` (a packed 24-bit audio sample, so 24-bit buffers are `[]S24`), `struct TimeFields`, `Vec::from_slice` / `as_slice`, `String::from_bytes` / `push_bytes` / `as_bytes`, and `over([]T)` constructors for `StaticVec`, `StaticMap`, `RingBuffer`, `LFQueue` and the allocators.

A lint in SafeC's test suite now fails the build if a raw pointer reappears in a public std declaration.

## Breaking: compiler-enforced Drop, Send and Sync {#ownership}

[#77](https://github.com/safec-org/SafeC/pull/77) makes resource ownership and thread safety compile-time checks. See [Ownership & Drop](/reference/ownership) and [Thread Safety](/reference/threads) for the full rules.

- **`Drop`.** A type with a `void drop()` method, or one holding such a value, must be dropped or moved exactly once on every path — `return`, `break`, `continue`, `goto`, `match`, `defer`, loops. Leaks, use after drop or move, double drops, overwriting a live value, and moving out of borrowed memory are compile errors. The check is per generic instantiation and inserts nothing into the generated code.
- `consume` methods (`int close() consume;`) destroy their receiver like `drop()`; `drop(x)` is drop glue for any type; `std::replace` and `std::swap` live in `std/ownership.h`; `forget(x)` is allowed only in `unsafe`. A reference a method returns into its receiver (`Vec::at()`, `MutexGuard::get()`) borrows from it.
- **`Send` / `Sync`** are derived from each type's structure, overridable with `unsafe impl Send;` / `impl !Send;`, and usable as generic bounds. `spawn` takes only Sync static data, `spawn_scoped` lends a local until it is joined, and `chan_send` moves a Send value.
- New in `std/thread.h`: `thread_spawn` / `SpawnedThread<T, R>` (moves its argument into the thread; `join()` returns the result), `Mutex<T>` / `MutexGuard<T>` (the value is reachable only through the guard, which unlocks in `drop()`) and `RwLock<T>`; `std/atomic.h` has `AtomicInt` and `AtomicLong`.
- std's owning types — collections, `File`, sockets, TLS, HTTP, threads, sync primitives, GUI, audio, async, ML containers — implement `Drop`; `free()` / `close()` / `destroy()` stay as `consume` aliases. `ThreadPool::spawn`, `Chan<T>`, `AsyncChannel<T>` and `Oneshot<T>` require Send values, GUI windows are `!Send`, and dropping an unfinished `AsyncTask` panics instead of leaving its executor polling freed memory.

Turning the checks on across std found real bugs, now fixed: leaks, double frees and use-after-frees in TLS error paths, the GUI SVG renderer, widget and PNG loaders, static-file and router helpers, `Audio::open`, LLM and LoRA paths in `std/ml`, and safec-doc's link checker.

## Notable fixes {#fixes}

- **Variadic calls** promote `bool`, `char` and `short` to `int`, as C requires: `printf("%d", !x)` used to print 254 ([#71](https://github.com/safec-org/SafeC/pull/71)).
- **Safety-check gaps closed** ([#72](https://github.com/safec-org/SafeC/pull/72)): writing a field through a `const &T` is rejected; the `&stack` / `&arena` escape check follows `->`; an element reference from `Vec::at`, `HashMap` or `StrMap` held across a `push` or a rehashing insert is reported. Mutation through a reference-returning call chain (`v.at(i).field.push(x)`) now reaches the real field instead of a temporary copy.
- **Method calls** on a field of a by-reference call result act on the referent, and a false positive with `StrMap` in recursive functions is gone ([#73](https://github.com/safec-org/SafeC/pull/73)); `atomic_cas` arguments are checked against the pointee type ([#74](https://github.com/safec-org/SafeC/pull/74)).
- **C header import** skips 128-bit float and `_Complex` declarations, so newer glibc headers import; `safeguard test` runs the test's `.exe` on Windows ([#75](https://github.com/safec-org/SafeC/pull/75)). In a library package, `safeguard test` links the library into each test.
- **Windows and macOS:** a generic function keeps its COMDAT through ABI lowering, `std/sched` passes `WSAStartup` a correctly sized `WSADATA`, the WSAPoll reactor no longer reads its watch list after dispatch ([#64](https://github.com/safec-org/SafeC/pull/64)); ABI-lowered functions keep their place in the module ([#79](https://github.com/safec-org/SafeC/pull/79)); the std module check accepts generic instances repeated across sections on Mach-O and COFF ([#68](https://github.com/safec-org/SafeC/pull/68)).

## Migrating {#migration}

Both breaking changes fail loudly: every affected call is a compile error (a wrong argument count or type, or an ownership error with a note at the declaration), and nothing changes behaviour silently except where the [changelog](/changelog) says so. The common edits:

| Before | After |
|---|---|
| `f.read(buf, n)`, `w.write(buf, n)` | `f.read(buf[..])`, `w.write(buf[0..n])` |
| `sha256(data, len, digest)` | `sha256(bytes, digest[..])` with `bytes` a slice, e.g. `buf[0..len]` or `std::str_bytes(s)` |
| `str_copy(dst, src, size)` | `str_copy(dst[..], src)` — returns 1 if it fit, 0 if truncated, always NUL-terminates |
| `safe_memcpy(dst, src, bytes)` | `safe_memcpy(dst[..], src[..])` — counts **elements**, copies `min` of the two lengths |
| `time_gmtime(t, fields)` (9-int array) | `struct TimeFields tm = time_gmtime(t);` |
| `Vec::from_arr(p, n)` | `Vec::from_slice(a[..])` |
| `String::from_n(p, n)` / `push_n` | `String::from_bytes(s)` / `push_bytes(s)` |
| `sys_bsearch(...)` returning `?&T` | returns the index as `?unsigned long` |
| `void*` callback context | a typed `?&T`, stored as `?&Erased` by std's generic methods |
| a custom allocator's `?&heap void alloc(size, align)` / `dealloc(?&heap void)` | `?&heap unsigned char` in both (std's allocators and `heap_alloc` hand out bytes; `alloc_t` / `dealloc_t` are unchanged) |
| `(T*)slice` | `slice.ptr` |
| a value with `free()` / `close()` that is never released on some path | `defer v.drop();` right after creating it, or release it on every path |
| `thread_create(&tid, work, ctx)` sharing a pointer | `thread_spawn(fn, value)` moving the data in, or `Mutex<T>` / `spawn_scoped` for shared data |

A typical ownership error, and its fix:

```c norun
int sum_positive([]const int xs) {
    struct Vec<int> v = std::Vec::new();
    for (unsigned long i = 0UL; i < xs.len; i = i + 1UL) {
        if (xs[i] < 0) { return -1; }   // error: value of Drop type 'Vec<int>' leaks when the
        v.push(xs[i]);                  //   function returns: 'v' is never dropped;
    }                                   //   call v.drop() or add 'defer v.drop()'
    // ...
    v.drop();
    return 0;
}
```

Adding `defer v.drop();` after the declaration (and removing the final `v.drop()`) releases `v` on every path.

The slice forms, in a program the [Playground](/playground) can run — a read-only slice parameter, a bounded `str_copy` and an element-wise `safe_memcpy`:

```c
#include <std/str.h>
#include <std/mem.h>
extern int printf(const char* fmt, ...);

long sum([]const int xs) {           // a read-only slice: the length comes with it
    long s = 0L;
    for (unsigned long i = 0UL; i < xs.len; i = i + 1UL) { s = s + (long)xs[i]; }
    return s;
}

int main() {
    char name[8];
    int fit = std::str_copy(name[..], "truncated!");   // bounded by the slice
    printf("fit=%d name=%s\n", fit, name);

    int src[4] = {1, 2, 3, 4};
    int dst[3];
    unsigned long n = std::safe_memcpy(dst[..], src[..]);   // elements, not bytes
    printf("copied=%lu sum=%ld\n", n, sum(dst[..]));
    return 0;
}
```

It prints `fit=0 name=truncat` (the copy was truncated to the 8-byte buffer, NUL included) and `copied=3 sum=6`. The same with crypto and a thread that owns its data — this one needs the full std, so build it locally with `safeguard run`:

```c norun
#include <std/crypto/sha256.h>
#include <std/str.h>
#include <std/thread.h>
#include <std/collections/vec.h>
extern int printf(const char* fmt, ...);

int count(struct Vec<int> v) {
    int n = (int)v.len;
    v.drop();                       // the thread owns v and must drop it
    return n;
}

int main() {
    unsigned char digest[32];
    std::sha256(std::str_bytes("abc"), digest[..]);   // was sha256(data, len, digest)

    struct Vec<int> v = std::Vec::new();
    v.push(1); v.push(2);
    struct SpawnedThread<struct Vec<int>, int> t = std::thread_spawn(count, v);
    // v.push(3);                   // error: use of 'v' after it was moved
    int len = t.join();             // join() consumes t and returns count's result

    printf("digest[0]=0x%02x len=%d\n", (unsigned)digest[0], len);
    return 0;
}
```

It prints `digest[0]=0xba len=2`; uncommenting `v.push(3)` is the compile error `use of 'v' after it was moved`, with a note pointing at the `thread_spawn` call.

Where to look while migrating:

- [Types: slices](/reference/types#slice-types), [read-only slices](/reference/types#read-only-slices) and [optional slices](/reference/types#optional-slices)
- [Ownership & Drop](/reference/ownership) — the rule, every error, [consume methods](/reference/ownership#consume-methods), [`forget`](/reference/ownership#forget)
- [Thread Safety](/reference/threads) — [Send and Sync](/reference/threads#send-and-sync), [`thread_spawn`](/reference/threads#thread-spawn), [`Mutex<T>`](/reference/threads#mutex)
- The std pages for the modules you use: [mem](/stdlib/mem), [str](/stdlib/str), [io](/stdlib/io), [collections](/stdlib/collections), [crypto](/stdlib/crypto), [thread](/stdlib/thread), [async](/stdlib/async), [audio](/stdlib/audio), [dsp](/stdlib/dsp), [http](/stdlib/http), and the [std API reference](/api/)
- The [changelog](/changelog)'s *Breaking changes* list, module by module

## Try it {#try}

All of this is on `main` today. Until the next release, [build SafeC from source](/install#quick-install), or try the language in the [Playground](/playground). If something you depend on broke in a way the compiler didn't explain, please [open an issue](https://github.com/safec-org/SafeC/issues).
