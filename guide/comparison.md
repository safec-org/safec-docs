---
title: Comparison
---

# Comparison with Other Languages

SafeC occupies a specific point in the design space of systems programming languages. This page compares it with C, C++, Rust, and Zig across the dimensions that matter most for low-level, safety-critical, and real-time systems.

## Feature Comparison

| Feature | C | C++ | Rust | Zig | SafeC |
|---|---|---|---|---|---|
| Memory Safety | No | No | Yes | No | Yes |
| C ABI | Native | Native | FFI | Native | Native |
| Hidden Runtime | No | Partial | Partial | No | No |
| Compile-Time Eval | No | constexpr | const fn | comptime | Compile-time-first |
| Garbage Collector | No | No | No | No | No |
| Unsafe Escape | N/A | N/A | `unsafe` | Manual | `unsafe {}` |
| Memory Model | Manual | Manual/RAII | Ownership | Allocators | Explicit regions |
| Preprocessor | Full (unsafe) | Full (unsafe) | None | Limited | Disciplined subset |
| Exceptions | No (setjmp) | Yes | No (panic) | No | No |
| Generics | No | Templates | Generics | comptime | Monomorphized |
| Struct Methods | No | Classes | impl blocks | Methods | Struct methods |
| Operator Overloading | No | Yes | Traits | No | Yes |

## Compared to C

SafeC is a superset of C. Valid C patterns work in SafeC, but SafeC adds compile-time safety checks that C lacks.

### What SafeC adds

**Region-annotated references** prevent dangling pointers and use-after-free:

```c
// C: compiles fine, undefined behavior at runtime
int* dangling() {
    int x = 42;
    return &x;  // returns pointer to dead stack variable
}

// SafeC: compile-time error
&stack int dangling() {
    int x = 42;
    return &x;  // ERROR: stack reference escapes function scope
}
```

**Bounds checking** catches out-of-bounds access:

```c
// C: silent buffer overflow
int arr[4];
arr[10] = 99;  // undefined behavior, no diagnostic

// SafeC: compile-time error for constant index
int arr[4];
arr[10] = 99;  // ERROR: index 10 out of bounds for array of size 4
```

**Borrow checking** prevents aliasing violations:

```c
int x = 10;
&stack int a = &x;       // mutable borrow
&stack int b = &x;       // ERROR: x is already mutably borrowed
```

**Disciplined preprocessor** rejects dangerous macro patterns while keeping useful ones:

```c
// Allowed: object-like macros, #ifdef, #pragma once
#define BUFFER_SIZE 1024
#ifdef DEBUG
    // ...
#endif

// Rejected in safe mode: function-like macros
#define MAX(a, b) ((a) > (b) ? (a) : (b))  // ERROR in safe mode
// Use: generic<T> T max(T a, T b) instead
```

### What stays the same

- Struct layout is identical
- Calling convention is identical
- Pointer arithmetic works the same
- `sizeof`, `alignof` behave the same
- C headers work via `#include`
- Object files link together

### Migration path

You can adopt SafeC one file at a time. Rename `.c` to `.sc`, fix the diagnostics the compiler reports, and link the resulting object alongside your existing C objects. No wrapper layer, no binding generator.

## Compared to C++

C++ and SafeC solve similar problems — adding safety and abstraction to C — but with fundamentally different philosophies about hidden behavior.

### Hidden costs in C++

```cpp
// C++: how many function calls happen here?
std::string greet(std::string name) {
    return "Hello, " + name + "!";
}
// Answer: at least 3 allocations, 2 copies or moves,
// 3 destructor calls — none visible in the source
```

```c
// SafeC: every operation is visible
extern int snprintf(char* buf, long n, const char* fmt, ...);

int greet(const char* name, char* out, int outLen) {
    return snprintf(out, outLen, "Hello, %s!", name);
}
```

### No implicit special member functions

C++ generates constructors, destructors, copy/move operators implicitly. SafeC does not. Struct creation is always aggregate initialization or field-by-field assignment. Cleanup is always explicit.

```c
struct Buffer {
    char* data;
    int size;
};

// No implicit constructor — you initialize fields explicitly
Buffer b;
b.data = (char*)malloc(1024);
b.size = 1024;

// No implicit destructor — you free explicitly
defer free(b.data);
```

### No exceptions

C++ exceptions add hidden control flow paths, stack unwinding machinery, and unpredictable timing. SafeC has no exception mechanism. Errors are returned as values:

```c
// Error handling is always visible
int result = loadConfig(path, &config);
if (result != 0) {
    printf("failed to load config: error %d\n", result);
    return result;
}
```

### No RTTI or virtual dispatch

SafeC has no `virtual` functions, no `dynamic_cast`, no vtables. Polymorphism is achieved through generics (monomorphized at compile time) or function pointers (explicit).

### What SafeC keeps from C++

- Struct methods (with `self` instead of `this`)
- Operator overloading (explicit, opt-in)
- Generics (via monomorphization, not templates)

## Compared to Rust

Rust and SafeC share the goal of memory safety without garbage collection, but they achieve it through different mechanisms.

### Ownership vs Regions

Rust tracks **ownership transfer**: a value has one owner at a time, and ownership can be moved or borrowed.

SafeC tracks **region membership**: a reference knows which memory region it points into, and the compiler prevents references from escaping their region.

```rust
// Rust: ownership transfer
fn process(data: Vec<u8>) {
    // data is owned here, dropped at end of function
}

let v = vec![1, 2, 3];
process(v);
// v is no longer accessible — ownership moved
```

```c
// SafeC: region-based
region Pool { capacity: 4096 }

void process(&arena<Pool> int data) {
    // data points into Pool, cannot escape
}

&arena<Pool> int v = new<Pool> int;
*v = 42;
process(v);
// v is still accessible — no ownership transfer
```

### Explicit vs Elided lifetimes

Rust elides lifetimes in many common cases, making code cleaner but sometimes obscuring the actual lifetime relationships. SafeC requires explicit region annotations:

```rust
// Rust: lifetime elided
fn first(s: &str) -> &str {
    &s[..1]
}

// Rust: explicit lifetime when needed
fn longest<'a>(a: &'a str, b: &'a str) -> &'a str {
    if a.len() > b.len() { a } else { b }
}
```

```c
// SafeC: region always explicit
&stack char first(&stack char s) {
    // region annotation makes the relationship visible
}
```

### No borrow checker "fights"

Rust's borrow checker is powerful but can reject valid programs, particularly those involving self-referential structures, graph data structures, or arena allocation patterns. SafeC's region model is more permissive for these patterns:

```c
// Multiple references into the same arena — no ownership conflict
region Pool { capacity: 8192 }

&arena<Pool> Node a = new<Pool> Node;
&arena<Pool> Node b = new<Pool> Node;
a.next = b;  // both references coexist — arena guarantees validity
b.prev = a;  // cyclic references are fine within the same arena
```

### C ABI by default

Rust requires `extern "C"` and `#[repr(C)]` annotations for C interop. SafeC uses C ABI by default:

```rust
// Rust: explicit C interop annotations
#[repr(C)]
struct Point {
    x: f64,
    y: f64,
}

extern "C" {
    fn c_process_point(p: *const Point);
}
```

```c
// SafeC: C ABI by default, no annotations needed
struct Point {
    double x;
    double y;
};

extern void c_process_point(const Point* p);
```

### Different trade-offs

| Aspect | Rust | SafeC |
|---|---|---|
| Learning curve | Steep (ownership, lifetimes, traits) | Moderate (C + region annotations) |
| Ecosystem | Large (crates.io) | Small (growing) |
| Safety model | Ownership + borrow checker | Region types + escape analysis |
| Runtime | Minimal (panic handler, allocator) | None (`--freestanding`) |
| C interop | FFI layer required | Native, zero-cost |
| Self-referential structs | Difficult (Pin, unsafe) | Natural (arena regions) |
| Async | Built-in (async/await) | Not built-in (pthreads, channels) |

## Compared to Zig

Zig and SafeC share the philosophy of zero hidden cost and explicit control, but differ in their memory models and compile-time systems.

### Allocators vs Regions

Zig passes allocators as function parameters. The caller decides where memory comes from. SafeC uses region types that encode the memory source in the type system.

```zig
// Zig: allocator as parameter
fn createBuffer(allocator: std.mem.Allocator, size: usize) ![]u8 {
    return allocator.alloc(u8, size);
}
```

```c
// SafeC: region encoded in type
region Audio { capacity: 65536 }

&arena<Audio> float createBuffer() {
    return new<Audio> float;  // region is part of the type
}
```

Zig's approach is more flexible (any allocator can be swapped in). SafeC's approach provides compile-time safety guarantees (the compiler verifies region lifetimes).

### comptime vs consteval

Both languages emphasize compile-time computation, but with different mechanisms:

```zig
// Zig: comptime
fn fibonacci(comptime n: u32) u32 {
    if (n <= 1) return n;
    return fibonacci(n - 1) + fibonacci(n - 2);
}

const fib10 = fibonacci(10);
```

```c
// SafeC: consteval
consteval int fibonacci(int n) {
    if (n <= 1) return n;
    return fibonacci(n - 1) + fibonacci(n - 2);
}

const int fib10 = fibonacci(10);

static_assert(fib10 == 55, "fibonacci broken");
```

Zig's `comptime` is more general (it can operate on types, generate code). SafeC's `consteval` is more focused (compile-time value computation and branch elimination).

### Preprocessor

Zig has no preprocessor. SafeC has a disciplined preprocessor subset that allows safe macros while rejecting dangerous patterns:

```c
// SafeC preprocessor: safe subset
#define VERSION 3
#define BUFFER_SIZE 1024
#ifdef PLATFORM_LINUX
    // platform-specific code
#endif
#pragma once  // header guard

// Rejected in safe mode:
// #define SQUARE(x) ((x) * (x))  // function-like macros
```

### Error handling

Zig uses error unions and `try`/`catch`. SafeC uses return values:

```zig
// Zig
fn readFile(path: []const u8) ![]u8 {
    const file = try std.fs.openFile(path, .{});
    defer file.close();
    return file.readToEndAlloc(allocator, max_size);
}
```

```c
// SafeC
int readFile(const char* path, char* buf, int bufLen) {
    int fd = open(path, 0);
    if (fd < 0) return -1;
    defer close(fd);
    return read(fd, buf, bufLen);
}
```

## When to Choose SafeC

SafeC is a strong fit when:

- **You are already using C** and want to add safety without rewriting in a new language
- **C ABI compatibility is critical** — your code links into existing C projects, kernels, or firmware
- **Deterministic behavior is required** — real-time audio, embedded systems, safety-critical software
- **You want zero hidden cost** — every allocation, every operation visible in the source
- **You need bare-metal support** — no runtime, no allocator, no standard library assumed
- **Incremental adoption matters** — rewrite one file at a time, not the entire project

SafeC may not be the best fit when:

- You need a large ecosystem of libraries (Rust's crates.io is far more mature)
- You prefer automatic memory management (ownership transfer, RAII)
- You need async/await for high-concurrency network services
- Your team is already productive in Rust or Zig

## Summary

| | C | C++ | Rust | Zig | SafeC |
|---|---|---|---|---|---|
| **Philosophy** | Trust the programmer | Abstraction | Safety first | Simplicity | Safety + transparency |
| **Memory** | Manual | RAII | Ownership | Allocators | Regions |
| **Cost model** | Explicit | Hidden | Mostly explicit | Explicit | Explicit |
| **C interop** | Native | Native | FFI layer | Native | Native |
| **Learning from C** | N/A | Moderate | High | Moderate | Low |
| **Safety** | None | Partial | Strong | Partial | Strong |

SafeC's position: **the safety of Rust, the transparency of C, the interop of neither-having-to-change**.

## Next Steps

- [Types](/reference/types) — Learn the SafeC type system
- [Memory and Regions](/reference/memory) — Deep dive into region-based memory safety
- [C Interop](/reference/ffi) — FFI policy and interop patterns
