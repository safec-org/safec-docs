---
title: Design Philosophy
---

# Design Philosophy

SafeC is built on a small set of principles that inform every language decision. Understanding these principles explains not just what SafeC does, but why it does it — and why it deliberately omits certain features that other languages consider essential.

## The Five Principles

### 1. Determinism

**The same input always produces the same output. No hidden runtime variance.**

In domains like audio processing, embedded firmware, flight control, and medical devices, non-deterministic behavior is not merely a bug — it is a safety hazard. SafeC guarantees that program behavior is fully determined by the source code and input data. There are no hidden sources of variance.

This means:

- No garbage collector that can pause execution unpredictably
- No implicit exception unwinding that changes control flow
- No hidden heap allocation that can fail at runtime
- No background threads spawned by the runtime

```c
// SafeC: allocation is always explicit and visible
region SensorPool { capacity: 4096 }

&arena<SensorPool> SensorData data = new<SensorPool> SensorData;
// Allocation is bounded, deterministic, and cannot fail
// (arena memory is pre-reserved at known capacity)
```

### 2. Zero Hidden Cost

**Every operation has a visible, predictable cost.**

SafeC follows C's tradition of exposing costs to the programmer, but extends it further. Where C++ might implicitly invoke a copy constructor, a move constructor, or a destructor chain, SafeC does nothing implicitly. If an operation has a cost, it is visible in the source code.

```c
// C++: hidden costs
std::vector<int> v = getVector();  // copy? move? allocate?
process(v);                        // copy? reference? who knows?

// SafeC: every cost is visible
int buf[64];
int count = fillBuffer(buf, 64);   // fills buf, returns count
process(buf, count);               // passes pointer, no copy
```

There are no implicit constructors, no implicit destructors, no implicit conversions between unrelated types, and no hidden runtime library calls.

### 3. Explicit Control

**No implicit memory, no implicit conversion, no implicit error handling.**

The programmer decides when memory is allocated, how long it lives, and when it is freed. The compiler verifies that these decisions are safe — but it does not make these decisions for you.

```c
// Memory lifetime is explicit
int x = 10;                          // stack — dies at scope exit
&heap int h = (int*)malloc(4);       // heap — programmer frees
arena_reset<SensorPool>();           // arena — explicit bulk free

// Error handling is explicit
int result = parseConfig(path);
if (result < 0) {
    printf("config error: %d\n", result);
    return result;
}
```

SafeC does not have exceptions. Errors are returned as values. The programmer handles them explicitly at every call site. This makes error propagation paths visible in the source code, not hidden in exception tables.

### 4. C ABI Compatibility

**C struct layout, C calling conventions, C linkage by default.**

SafeC is not a language that interoperates with C through an FFI layer. SafeC *is* C-compatible at the binary level. SafeC structs have C struct layout. SafeC functions use C calling conventions. SafeC objects link directly into C projects without wrappers, bindings, or codegen.

```c
// This SafeC struct has the exact same layout as its C equivalent
struct Point {
    double x;
    double y;
};

// C headers work natively
#include <stdio.h>
#include <math.h>

int main() {
    Point p;
    p.x = 3.0;
    p.y = 4.0;
    printf("distance = %.2f\n", sqrt(p.x * p.x + p.y * p.y));
    return 0;
}
```

This means SafeC can be adopted incrementally. You can rewrite one file at a time, linking SafeC objects alongside existing C objects, without changing your build system or rewriting your interfaces.

### 5. Compile-Time-First Design

**Anything knowable at compile time must be resolved at compile time.**

SafeC pushes as much computation as possible to compile time. Generics are monomorphized (no runtime type dispatch). `consteval` functions execute during compilation. `static_assert` catches errors before code runs. `if const` eliminates dead branches at compile time.

```c
consteval int factorial(int n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}

// Computed at compile time — zero runtime cost
const int LOOKUP_SIZE = factorial(5);  // 120

static_assert(LOOKUP_SIZE == 120, "factorial broken");

// Generic function — monomorphized per type, no runtime dispatch
generic<T>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}

int main() {
    int mi = max(3, 7);          // calls __safec_max_int
    double md = max(1.5, 2.7);   // calls __safec_max_double
    return 0;
}
```

## The Determinism Contract

These principles combine into a concrete contract. SafeC guarantees the absence of:

| Hidden Behavior | SafeC Guarantee |
|---|---|
| Hidden heap allocation | All allocation is explicit (`new<R>`, `malloc`) |
| Hidden runtime | No runtime library required (`--freestanding` supported) |
| Hidden panics | No panics unless bounds checks are enabled |
| Implicit exceptions | Errors are return values, not thrown objects |
| Background GC | No garbage collector exists |
| Implicit destructors | Resource cleanup is explicit (`defer`, manual `free`) |

This contract is not aspirational — it is enforced by the compiler. Code that would violate these guarantees does not compile.

## Region-Based Safety Model

SafeC's approach to memory safety is fundamentally different from Rust's ownership model. Instead of tracking ownership transfer, SafeC tracks **where** memory lives:

```c
int x = 10;
&stack int r1 = &x;         // r1 points to stack memory

&heap int r2 = (int*)malloc(sizeof(int));
*r2 = 20;                    // r2 points to heap memory

region Pool { capacity: 1024 }
&arena<Pool> int r3 = new<Pool> int;
*r3 = 30;                    // r3 points to arena memory
```

Each reference carries its region in the type. The compiler uses this information to prevent:

- **Dangling references** — returning a `&stack` reference from a function
- **Region escape** — storing an arena reference that outlives its arena
- **Aliasing violations** — multiple mutable references to the same data

The region information exists only at compile time. At runtime, references are plain pointers with no metadata, no reference counting, and no indirection.

## Why Not Ownership?

Rust's ownership model is powerful but imposes a specific programming style. Self-referential structures, arena allocation patterns, and many low-level systems idioms fight against ownership semantics.

SafeC's region model is more permissive in some ways (multiple references into an arena are fine) and more restrictive in others (region annotations are explicit, not elided). The trade-off is intentional: SafeC prioritizes **transparency** over **convenience**. The programmer sees exactly what the compiler sees.

## Why Not Exceptions?

Exceptions hide control flow. In a real-time system, you need to know exactly which paths your code can take and how long each path takes. Exception unwinding is inherently unpredictable in timing and behavior.

SafeC uses return values for error handling. This is more verbose than exceptions, but the control flow is always visible:

```c
int readSensor(int id, float* out) {
    if (id < 0 || id >= SENSOR_COUNT) return -1;
    *out = sensorValues[id];
    return 0;
}

int main() {
    float val;
    if (readSensor(0, &val) < 0) {
        printf("sensor error\n");
        return 1;
    }
    printf("sensor = %.2f\n", val);
    return 0;
}
```

## Why Not Classes?

Classes in C++ bundle data, behavior, lifetime management, and polymorphism into a single mechanism. This creates implicit costs: constructors run when you declare a variable, destructors run when a scope exits, virtual calls add indirection, and inheritance hierarchies create coupling.

SafeC separates these concerns:

- **Data** is defined in structs (plain data, C layout)
- **Behavior** is attached via struct methods (no vtable, no inheritance)
- **Lifetime** is managed via regions (explicit, compile-time)
- **Polymorphism** is achieved via generics (monomorphized, zero cost)

```c
struct Vec2 {
    double x;
    double y;

    double length() const;
    Vec2 operator+(Vec2 other) const;
};

double Vec2::length() const {
    return sqrt(self.x * self.x + self.y * self.y);
}

Vec2 Vec2::operator+(Vec2 other) const {
    Vec2 result;
    result.x = self.x + other.x;
    result.y = self.y + other.y;
    return result;
}
```

Methods are syntactic sugar for functions with an implicit `self` parameter. At the LLVM IR level, `v.length()` compiles to `Vec2_length(&v)`. There is no vtable, no dynamic dispatch, no hidden cost.

## Unsafe Escape Hatch

Safety checks can be selectively disabled inside `unsafe {}` blocks. This is necessary for low-level operations like raw pointer manipulation, hardware register access, and FFI with C libraries that return raw pointers:

```c
unsafe {
    int* raw = (int*)0x40000000;  // memory-mapped I/O
    *raw = 0x01;                   // direct hardware write
}
```

The `unsafe` block is a clear signal — both to the compiler and to human readers — that the enclosed code bypasses safety guarantees. It is not a failure of the safety model; it is a deliberate, visible escape hatch for code that inherently cannot be statically verified.

## Summary

SafeC's design can be summarized in one sentence:

> **Everything the programmer writes is everything that executes — the compiler verifies correctness but adds nothing hidden.**

This is the opposite of the C++ and Rust philosophy, where the compiler is expected to generate substantial invisible code (destructors, drop glue, move constructors, unwinding tables). SafeC trusts the programmer to make explicit decisions and uses compile-time analysis to verify those decisions are safe.

## Next Steps

- [Comparison](/guide/comparison) — See how these principles play out compared to C, C++, Rust, and Zig
- [Memory and Regions](/reference/memory) — Deep dive into the region-based memory model
- [Safety](/reference/safety) — Bounds checking, borrow analysis, and escape analysis
