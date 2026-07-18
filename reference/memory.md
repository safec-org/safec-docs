# Memory & Regions

SafeC uses a region-based memory model that enforces lifetime safety at compile time. Every reference carries a region qualifier that tells the compiler where the data lives and how long it will be valid. Regions are compile-time only -- they produce no runtime metadata and erase to raw pointers in the generated code.

## The Four Regions

| Region | Lifetime | Allocation | Deallocation |
|--------|----------|------------|--------------|
| `stack` | Lexical scope | Automatic (stack frame) | Automatic (scope exit) |
| `static` | Program lifetime | Static storage | Never |
| `heap` | Dynamic | Explicit (`malloc`) | Explicit (`free`) |
| `arena<R>` | User-defined | Region allocator | Bulk reset |

## Region-Qualified References

Every reference in SafeC must be region-qualified. This tells the compiler exactly where the pointed-to data lives:

```c
&stack int x_ref;              // points to stack-allocated int
&heap float buf_ref;           // points to heap-allocated float
&static Config cfg_ref;        // points to static storage
&arena<AudioPool> Frame f;     // points to arena-allocated Frame
```

All references are non-null by default. Use the optional prefix for nullable references:

```c
?&stack Node next;             // may be null
?&heap Buffer cache;           // may be null
```

## Stack Region

The `stack` region is the default for local variables. Stack references cannot escape the scope in which they were created.

```c
void example() {
    int x = 42;
    &stack int ref = &x;       // OK: ref lives in same scope as x

    // return ref;             // ERROR: stack ref would escape scope
}
```

The compiler performs escape analysis to ensure stack references never outlive their referent:

```c
&stack int bad() {
    int local = 10;
    return &local;             // ERROR: returning reference to local
}
```

## Static Region

The `static` region is for data that lives for the entire program duration:

```c
static int counter = 0;

&static int get_counter() {
    return &counter;           // OK: counter lives forever
}
```

Static references are the most permissive -- they can be stored anywhere because they never become invalid.

## Heap Region

The `heap` region is for dynamically allocated data. Heap references require explicit allocation and deallocation:

```c
void example() {
    &heap int p = (int*)malloc(sizeof(int));
    *p = 42;
    free(p);
}
```

The compiler tracks heap references but does not automatically free them. Use `defer` for deterministic cleanup:

```c
void process() {
    &heap char buf = (char*)malloc(4096UL);
    defer free(buf);

    // ... use buf ...
}   // buf freed here via defer
```

::: warning Cast to the plain pointer type, not `(heap T*)`
`(heap int*)malloc(...)` doesn't parse — a region qualifier isn't valid
inside a cast's parentheses. Cast to the ordinary pointer type (`(int*)`);
the result converts implicitly to the `&heap` reference on assignment, as
shown above. Also note the `UL` suffix on `malloc(4096UL)` — `malloc` takes
an `unsigned long` (`size_t`), and there's no implicit `int` →
`unsigned long` conversion (see [Types](/reference/types)).
:::

`<mem.h>`'s `malloc_defer(n)` sugars the alloc-then-`defer free()` pattern
above into a single call. `malloc_defer` isn't a real function — it's
parser-level sugar recognized only as a var-decl initializer, and it
desugars into `std::alloc(n)` for the declaration plus a synthesized
`defer std::dealloc(buf);` right after it. Because it's backed by
`std::alloc`, the declared variable's type is `void*` (same as `alloc()`
everywhere else in `std/`), so an explicit `unsafe` cast is still needed to
use it as a typed pointer:

```c
#include <mem.h>

void process2() {
    auto buf = malloc_defer(4096UL);   // void*, freed automatically at scope exit
    int* typed;
    unsafe { typed = (int*)buf; }
    // ... use typed ...
}   // std::dealloc(buf) runs here via the synthesized defer
```

## Arena Regions

Arenas provide bulk allocation and deallocation. You declare a region, allocate into it with `new<R>`, and reset all allocations at once with `arena_reset<R>()`.

### Declaring a Region

```c
region AudioPool {
    capacity: 4096
}
```

### Allocating in an Arena

```c
&arena<AudioPool> Sample s = new<AudioPool> Sample;
```

The `new<R> T` expression performs a bump-pointer allocation from the arena. It is fast (just an offset increment) and never fails as long as capacity is available.

### Resetting an Arena

```c
arena_reset<AudioPool>();
```

This resets the arena's offset to zero, effectively freeing all allocations at once. No destructors are called -- arena-allocated objects must not hold external resources.

### Destroying an Arena

`arena_destroy<R>()` goes a step further than `arena_reset<R>()`: it frees
the region's malloc'd backing buffer entirely rather than just rewinding the
bump offset.

```c
arena_destroy<AudioPool>();
```

A second call is a safe no-op (the buffer pointer is nulled after the first
call), and a subsequent `new<AudioPool> T` transparently re-`malloc`s a
fresh backing buffer. Use `arena_destroy<R>()` when the arena won't be
reused for a while and you want to release its memory back to the system;
use `arena_reset<R>()` when you'll keep allocating from it again soon and
want to avoid the re-`malloc` cost.

### Arena Runtime Representation

At the LLVM level, each arena is a global struct `{ptr, i64 used, i64 cap}`:

- `ptr` -- base pointer to the arena's backing memory
- `used` -- current bump offset
- `cap` -- total capacity in bytes

`new<R> T` increments `used` by `sizeof(T)` and returns `ptr + old_used`. `arena_reset<R>()` sets `used` back to 0.

## Region Rules

The compiler enforces several rules to guarantee memory safety:

### 1. References Cannot Outlive Their Region

```c
&stack int ref;
{
    int x = 10;
    ref = &x;          // ERROR: x's scope is shorter than ref's
}
// ref would be dangling here
```

### 2. References Cannot Escape to a Longer-Lived Region

A reference to a short-lived region cannot be stored in a longer-lived location:

```c
static &stack int global_ref;  // ERROR: stack ref stored in static

void bad() {
    int local = 5;
    global_ref = &local;       // ERROR: stack ref escaping to static
}
```

### 3. No Implicit Cross-Region Conversion

References from different regions are not interchangeable:

```c
void takes_heap(&heap int p);

void example() {
    int x = 42;
    takes_heap(&x);            // ERROR: &stack int is not &heap int
}
```

### 4. Arena References Die on Reset — Conceptually, Not Yet Enforced

*Conceptually*, `arena_reset<R>()` invalidates every outstanding reference
into region `R`, since the next `new<R>` can hand out that same memory
again.

::: danger Not currently checked by the compiler
Unlike rules 1-3 above, this one is **not enforced**. The compiler compiles
the following without any error or warning, generating code that reads/writes
through a reference into memory that may already have been reused by a
later `new<Pool>`:

```c
region Pool { capacity: 1024 }
int main() {
    &arena<Pool> int p = new<Pool> int;
    arena_reset<Pool>();
    *p = 42;                    // compiles with no diagnostic — use-after-reset
    return 0;
}
```

Treat "don't touch a reference after resetting its arena" as a rule you must
enforce yourself in code review/discipline, the same way you would in
hand-written C — the compiler currently gives no help here despite the
region model's design intent. This applies to `arena_destroy<R>()` as well.
:::

## Unsafe Escape Hatch

When the region rules are too restrictive, `unsafe {}` blocks allow bypassing them:

```c
unsafe {
    int *raw = (int*)malloc(sizeof(int));
    *raw = 42;
    free(raw);
}
```

See [Safety](/reference/safety) for the full unsafe model.

## FFI and Regions

When calling C functions, region information is erased:

- `&static T` converts to `T*` automatically (safe, no `unsafe` needed)
- Other region refs require `unsafe {}` to pass to C

```c
extern int printf(const char *fmt, ...);

void example() {
    static const char *msg = "hello\n";
    printf(msg);                // OK: &static → raw pointer is safe
}
```

::: warning `static const char*` initializers must be local, not global
A file-scope `static const char *msg = "hello\n";` fails with "global
initializer for 'msg' is not a compile-time constant expression" — even
though the value genuinely is a string-literal constant. The same
declaration works fine as a local (`static`-storage) variable inside a
function, as shown above.
:::

See [C Interop](/reference/ffi) for details.
