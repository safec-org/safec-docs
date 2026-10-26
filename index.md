---
layout: home
title: SafeC
summary: SafeC is a region-aware evolution of C with compile-time memory safety, no garbage collector, zero hidden costs and full C ABI compatibility.
---

:::: hero
::: split
::: col
<p class="eyebrow">The SafeC programming language</p>

# Safe, deterministic systems programming

SafeC is C, evolved: **compile-time memory safety** through regions, no garbage collector and no hidden runtime, while keeping C's ABI, its headers and its toolchain. Write kernels, audio engines, servers, GUIs and WebAssembly in one language you can read like C.

::: actions
[Install SafeC](/install)
[Try the Playground](/playground)
[Read the Book](/book/)
:::

<p class="hero-meta">Open source (MIT) · Linux, macOS, Windows · bare metal and WebAssembly · LLVM backend</p>
:::
::: col
```c
extern int printf(const char* fmt, ...);

region Frame { capacity: 4096 }

struct Particle { float x; float v; };

// The region is part of the type: p can't outlive the arena.
void step(&arena<Frame> Particle p, float dt) {
    p.x = p.x + p.v * dt;
}

int main() {
    &arena<Frame> Particle p = new<Frame> Particle;
    p.x = 0.0;
    p.v = 2.5;
    for (int i = 0; i < 3; i = i + 1) {
        step(p, 0.5);
        printf("t=%d x=%.2f\n", i, p.x);
    }
    arena_reset<Frame>();   // O(1): no per-object free, no GC
    return 0;
}
```
:::
:::
::::

:::: section
## Why SafeC {#why}

::: lead
Keep everything that makes C the language of systems — predictable performance, a stable ABI, every platform — and let the compiler reject the bugs C lets through.
:::

::::: cards 3
::: card shield
### [Memory safety without a GC](/reference/memory)

Every reference carries its region — `stack`, `heap`, `arena<R>` or `static` — and the compiler proves it can't dangle, alias mutably or escape. No collector, no reference counts, no runtime metadata.
:::
::: card plug
### [Speaks C natively](/reference/ffi)

C struct layout, C calling conventions and C symbol names by default. `#include <stdio.h>` works; link SafeC objects into any C or C++ project and call C libraries without bindings.
:::
::: card clock
### [Deterministic by design](/guide/design)

No implicit allocations, no exceptions, no hidden copies, no narrowing conversions. The same input gives the same output with costs you can see — what real-time audio and control loops need.
:::
::: card chip
### [Bare metal and WebAssembly](/reference/baremetal)

`--freestanding` builds for 14 no-OS targets (Cortex-M, RISC-V, x86, AArch64) with interrupt handlers, inline assembly and `volatile` MMIO. `--target=wasm32` builds browser modules with `std/wasm` DOM bindings.
:::
::: card flow
### [Async without a runtime tax](/stdlib/async)

`std/async` brings Rust-style futures and wakers as a library: stackless state machines, a no-heap single-threaded executor that runs on bare metal and wasm, a thread pool, timers and async TCP.
:::
::: card layers
### [A standard library for real programs](/stdlib/)

30+ generic, `void*`-free modules: collections, a web stack (HTTP server, router, WebSocket, gzip), a GUI toolkit, real-time audio and MIDI, ML tensors on CPU and GPU, crypto, serialization.
:::
:::::
::::

:::: section alt
## Bugs C compiles, SafeC rejects {#compare}

::: lead
Each pair below is the same mistake. The C version builds without complaint and fails at run time — maybe. The SafeC version doesn't build, and the compiler says why.
:::

::::: compare
::: col C — compiles, returns a dangling pointer
```c norun
int* counter() {
    int x = 42;
    return &x;   // warning at best; UB when used
}
```
:::
::: col SafeC — compile-time error
```c norun
&stack int counter() {
    int x = 42;
    return &x;
}
```
```text
error: cannot return '&stack int': stack reference escapes function scope
```
:::
:::::

::::: compare
::: col C — a null pointer is just a pointer
```c norun
struct Node { int value; };

int read(struct Node* n) {
    return n->value;   // crashes when n == NULL
}
```
:::
::: col SafeC — nullable references must be checked
```c norun
struct Node { int value; };

int read(?&Node n) {
    return n.value;
}
```
```text
error: dereference of nullable reference '?&Node' requires 'unsafe', or use match/is_null()/.default(value) instead
```
:::
:::::

::::: compare
::: col C — silent truncation
```c norun
long big = 5000000000L;
int small = big;   // implementation-defined result
```
:::
::: col SafeC — narrowing is explicit
```c norun
long big = 5000000000L;
int small = big;
```
```text
error: type mismatch in variable declaration: cannot convert 'long' to 'int'
```
:::
:::::

[More comparisons with C, C++, Rust and Zig →](/guide/comparison)
::::

:::: section
## Batteries included {#std}

::: lead
The standard library is written in SafeC, links only the modules a program uses, and ships prebuilt for every release platform.
:::

::::: tabs
::: tab Web server
```c norun
#include <std/http/router.h>

struct HttpResponse hello(&HttpRequest req, &HttpCtx ctx) {
    struct String body = std::String::from("Hello, ");
    body.push(ctx.param_or("name", "world"));
    struct HttpResponse r = std::HttpResponse::text(200, body.as_ptr());
    body.free();
    return r;
}

int main() {
    struct Router app = std::Router::new();
    app.use_logger();
    app.use_gzip(1024UL);
    app.get("/hello/:name", hello);
    app.static_dir("/assets", "./public");

    struct HttpServerConfig cfg = std::HttpServerConfig::defaults(8080);
    return app.listen(&cfg);    // keep-alive HTTP/1.1 on the I/O reactor
}
```
Router, middleware, HTTPS, static files, cookies, multipart, SSE, WebSocket and a redirect-following client with gzip — [HTTP & Web](/stdlib/http).
:::
::: tab GUI
```c norun
#include <std/gui/gui_app.h>

static void on_click(&Widget w, ?&Widget label) {
    match (label) { case some(l): { l.set_text("Clicked!"); } case null: { } }
}

int main() {
    struct GuiApp app = GuiApp::new();
    ?&GuiWindow ow = app.open_window("Hello", 360, 200);
    match (ow) {
        case null: { return 1; }                 // no display
        case some(win): {
            &Widget root = Widget::vstack().set_padding(16);
            &Widget label = Widget::label("Hello from SafeC");
            root.add_child(label);
            root.add_child(Widget::button("Click me").on_click(on_click, label));
            app.set_root(win, root);
            app.run();                           // until the window closes
        }
    }
    app.free();
    return 0;
}
```
An anti-aliased vector canvas with TrueType text and 30+ themed widgets on X11, Win32 and Cocoa — [GUI](/stdlib/gui).
:::
::: tab Async
```c norun
#include <std/async/async.h>
#include <std/async/host.h>
extern int printf(const char* fmt, ...);

struct Countdown {
    int st; int i; struct Sleep d;
    struct Poll<int> poll(&Context cx);
    void drop();
};
struct Poll<int> Countdown::poll(&Context cx) {
    ASYNC_BEGIN(self.st, 1);                 // one await point
    for (self.i = 3; self.i > 0; self.i = self.i - 1) {
        self.d = Sleep::new(100LL);
        AWAIT(1, self.d);                    // Pending until the timer fires
        printf("%d\n", self.i);
    }
    ASYNC_RETURN(42);
    ASYNC_END;
}
void Countdown::drop() { if (self.st == 1) self.d.drop(); }

int main() {
    struct Countdown c; c.st = ASYNC_INIT;
    return block_on(&c) == 42 ? 0 : 1;       // or LocalExecutor / ThreadPool / Runtime
}
```
Futures, wakers, join/select/timeout, channels and async TCP over epoll, kqueue and WSAPoll — [Async](/stdlib/async).
:::
::: tab Audio
```c norun
#include <std/audio/audio.h>
#include <std/math.h>

struct Tone { double phase; double step; };

// The real-time callback: fill the output buffer, never allocate or block.
int render(&AudioIo io, ?&Tone tp) {
    match (tp) {
        case some(t): {
            struct Slice<float> out = io.output_f32();   // frames x channels, interleaved
            for (unsigned int f = 0U; f < io.frames; f = f + 1U) {
                float v = (float)(0.2 * std::sin_d(t.phase));
                out.set((unsigned long)f * 2UL, v);
                out.set((unsigned long)f * 2UL + 1UL, v);
                t.phase = t.phase + t.step;
            }
        }
        case null: {}
    }
    return AUDIO_CB_CONTINUE;
}
```
Real-time audio and MIDI on ALSA, JACK, Core Audio, WASAPI and Web Audio — [Audio & MIDI](/stdlib/audio).
:::
::: tab Bare metal & wasm
```c norun
#define LED_PORT ((unsigned int*)0x40020014)

// safec --target=arm-7em --freestanding blink.sc
interrupt void systick_handler() {
    unsafe { volatile_store(LED_PORT, volatile_load(LED_PORT) ^ 1U); }
}

// safec --target=wasm32 fib.sc  ->  a module exporting fib()
__attribute__((export_name("fib")))
int fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
```
The whole standard library builds for every bare-metal target; `std/wasm` drives the DOM from SafeC — [Bare-Metal](/reference/baremetal), [WebAssembly & DOM](/stdlib/wasm).
:::
:::::
::::

:::: section alt
## Install {#install}

::::: tabs
::: tab macOS & Linux
```bash
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash
```
:::
::: tab Windows
```powershell
irm https://raw.githubusercontent.com/safec-org/SafeC/main/install.ps1 | iex
```
:::
:::::

Then create a project and run it:

```bash
safeguard new hello && cd hello
safeguard run
```

SafeC needs **clang 21 or newer** to compile and link (the installer finds it or tells you how to get it). See [Install](/install) for options, requirements and building from source.
::::

:::: section
## Get going {#next}

::::: cards 3
::: card book
### [Learn](/learn/)

The Book, the guide, the language reference and the standard library, in one place.
:::
::: card play
### [Playground](/playground)

Edit SafeC in your browser, load the Book's examples and share a program as a link.
:::
::: card terminal
### [Tools](/tools)

`safeguard` builds, tests, formats and lints; `sc-lsp` brings diagnostics and completion to your editor.
:::
::: card rss
### [Blog](/blog/)

Release notes and news. Follow the [Atom feed](/blog/feed.xml).
:::
::: card chat
### [Community](/community)

Report bugs, propose features and send pull requests on GitHub.
:::
::: card git
### [GitHub](https://github.com/safec-org/SafeC)

The compiler, the standard library, safeguard and the install scripts — MIT licensed.
:::
:::::
::::
