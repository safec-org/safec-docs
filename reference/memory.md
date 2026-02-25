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
    &heap int p = (heap int*)malloc(sizeof(int));
    *p = 42;
    free(p);
}
```

The compiler tracks heap references but does not automatically free them. Use `defer` for deterministic cleanup:

```c
void process() {
    &heap char buf = (heap char*)malloc(4096);
    defer free(buf);

    // ... use buf ...
}   // buf freed here via defer
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

### 4. Arena References Die on Reset

After `arena_reset<R>()`, all references into region `R` are invalid. The compiler tracks this:

```c
region Pool { capacity: 1024 }
&arena<Pool> int p = new<Pool> int;
arena_reset<Pool>();
// *p = 42;                    // ERROR: reference invalidated by reset
```

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

static const char *msg = "hello\n";
printf(msg);                   // OK: &static → raw pointer is safe
```

See [C Interop](/reference/ffi) for details.
