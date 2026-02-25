---
title: Introduction
---

# Introduction to SafeC

SafeC is a deterministic, region-aware, compile-time-first systems programming language. It is an evolution of C that preserves full C ABI compatibility while enforcing memory safety, type safety, and real-time determinism — all at compile time, with zero runtime overhead.

## Why SafeC?

C remains the foundation of systems programming: operating systems, embedded firmware, audio engines, network stacks. But C's lack of memory safety is a persistent source of bugs, vulnerabilities, and undefined behavior.

Existing alternatives each demand significant trade-offs:

- **C++** adds safety features but also adds hidden costs: implicit constructors, destructors, exceptions, RTTI, vtables.
- **Rust** provides strong safety guarantees but requires learning an entirely new ownership model, a new ecosystem, and non-trivial FFI to interface with C.
- **Zig** removes hidden behavior but uses a fundamentally different memory model based on allocators rather than regions.

SafeC takes a different approach: **start from C and add safety, without adding hidden cost**.

```c
extern int printf(const char* fmt, ...);

int main() {
    int x = 42;
    &stack int ref = &x;       // explicit region annotation
    printf("x = %d\n", *ref);
    return 0;
}
```

If you know C, you already know most of SafeC. The additions are explicit, visible, and predictable.

## Core Identity

SafeC is defined by five non-negotiable principles:

### 1. Determinism
The same input always produces the same output. No hidden runtime variance, no non-deterministic behavior. This makes SafeC suitable for real-time systems, audio processing, embedded firmware, and any domain where predictability is not optional.

### 2. Zero Hidden Cost
Every operation has a visible, predictable cost. There are no implicit heap allocations, no implicit copies, no background garbage collection, no implicit destructor chains. What you write is what executes.

### 3. Explicit Control
No implicit memory management, no implicit type conversions, no implicit error handling. The programmer controls allocation, lifetime, and error propagation explicitly. The compiler verifies correctness.

### 4. C ABI Compatibility
SafeC uses C struct layout, C calling conventions, and C linkage by default. SafeC objects link directly into C projects. C headers can be included natively with `#include <stdio.h>`.

### 5. Compile-Time-First Design
Anything knowable at compile time is resolved at compile time. `consteval` functions execute during compilation. `static_assert` catches errors before any code runs. `if const` selects branches at compile time. Generics are monomorphized — no runtime dispatch.

## The Determinism Contract

SafeC guarantees the absence of hidden runtime behavior:

- **No hidden heap allocation** — all allocation is explicit (`new<R>`, `malloc`)
- **No hidden runtime** — no runtime library required (freestanding mode available)
- **No hidden panics** — unless explicitly opted in with bounds checks
- **No implicit exceptions** — errors are returned, not thrown
- **No background GC** — memory is managed through regions and explicit deallocation
- **No implicit destructor calls** — resource cleanup is explicit (`defer`, manual free)

## What SafeC Adds to C

SafeC extends C with safety and modern features while preserving C's execution model:

| Feature | Description |
|---|---|
| **Regions** | `&stack T`, `&heap T`, `&arena<R> T`, `&static T` — compile-time memory safety |
| **Bounds checking** | Static and runtime array bounds verification |
| **Borrow checking** | Mutable/immutable reference exclusion enforced at compile time |
| **Generics** | `generic<T>` with monomorphization — zero runtime cost |
| **Struct methods** | `Point::length()` syntax with implicit `self` receiver |
| **Operator overloading** | `Vec2 operator+(Vec2 other) const` |
| **Pattern matching** | `match` expressions with exhaustiveness checking |
| **Optional types** | `T?` with `if let` unwrapping |
| **Slices** | `[]int s = arr[0..3]` — bounds-checked views into arrays |
| **Defer** | `defer close(fd)` — deterministic cleanup |
| **Compile-time evaluation** | `consteval`, `static_assert`, `if const` |
| **Tuples** | `tuple(int, double)` with `.0`, `.1` member access |
| **Concurrency** | `spawn`/`join`, typed channels |
| **Bare-metal support** | `naked`, `interrupt`, `asm`, `--freestanding` |

## What SafeC Does NOT Add

Equally important is what SafeC deliberately omits:

- No garbage collector
- No exceptions
- No RTTI or virtual dispatch
- No implicit constructors or destructors
- No hidden allocations
- No standard runtime requirement
- No class hierarchies or inheritance

## Project Components

The SafeC project consists of three main components:

**Compiler (`safec`)** — A full LLVM frontend that compiles `.sc` files to LLVM IR. The pipeline is: Preprocessor, Lexer, Parser, AST, Sema (semantic analysis), ConstEval (compile-time evaluation), CodeGen (LLVM IR generation).

**Standard Library (`std/`)** — 20+ modules covering memory, I/O, strings, math, collections, concurrency, and system interfaces. All implemented in SafeC with C FFI for system calls.

**Package Manager (`safeguard`)** — A build tool and dependency manager. Handles compilation, linking, dependency fetching, and standard library integration.

## File Extension

SafeC source files use the `.sc` extension:

```
hello.sc
math_utils.sc
audio_engine.sc
```

## Next Steps

- [Getting Started](/guide/getting-started) — Install the compiler and write your first program
- [Design Philosophy](/guide/design) — Understand the principles behind SafeC's decisions
- [Comparison](/guide/comparison) — See how SafeC compares to C, C++, Rust, and Zig
