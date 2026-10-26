# Safety

SafeC enforces memory safety at compile time through several interlocking analyses. These checks catch common C bugs -- use-after-free, buffer overflows, null dereferences, aliasing violations, leaks of owned resources, data races -- before the program ever runs.

## Definite Initialization {#definite-initialization}

Every variable must be assigned before it is used. The compiler performs def-use analysis to enforce this:

```c
int x;
printf("%d\n", x);            // ERROR: use of uninitialized variable 'x'
```

```c
int x;
x = 42;
printf("%d\n", x);            // OK: x is initialized
```

Struct variables are considered initialized at declaration (fields are assigned individually):

```c
Point p;
p.x = 1.0;
p.y = 2.0;
double len = p.x * p.x + p.y * p.y;  // OK
```

## Region Escape Analysis {#region-escape-analysis}

The compiler tracks the region of every reference and ensures it cannot outlive its region:

### Stack References Cannot Escape {#stack-references-cannot-escape}

```c
&stack int bad() {
    int local = 10;
    return &local;             // ERROR: stack reference escapes function
}
```

### References Cannot Escape to Longer-Lived Storage {#references-cannot-escape-to-longer-lived-storage}

```c
static &stack int global_ref;

void bad() {
    int x = 5;
    global_ref = &x;          // ERROR: stack ref assigned to static storage
}
```

### Arena References Die on Reset {#arena-references-die-on-reset}

`arena_reset<R>()`/`arena_destroy<R>()` invalidate *every* outstanding
reference into `R`, since the memory may be handed out again by a later
`new<R>`: reading a `&arena<R> T` reference (or any copy of it) after
the call is a compile error. `arena_free_to<R>()` (paired with
`arena_mark<R>()`) is narrower — it only invalidates references allocated
*after* the checkpoint being freed to. Each mark is tracked separately, so
nested marks are precise, and a reference allocated before any
`arena_mark<R>()` call for `R` survives a `arena_free_to<R>()` call
untouched.

```c
region Pool { capacity: 1024 }

int main() {
    &arena<Pool> int p = new<Pool> int;
    arena_reset<Pool>();
    *p = 42;   // ERROR: use of 'p' (&arena<Pool> reference) after
               //        arena_reset<Pool>(), arena_destroy<Pool>(), or
               //        arena_free_to<Pool>() invalidated it
    return 0;
}
```

This is checked by a dataflow analysis that follows the function's
control flow (a reset inside one `if`/`else` branch doesn't affect the
other; loops are iterated to a fixpoint, so a reset reaches the top of
the body on the next iteration) and calls (a function that resets `R`, or
rewinds to a mark it was passed, invalidates the caller's references).
See [Memory & Regions](/reference/memory#_4-arena-references-die-on-reset)
for the full detail, and
[Temporal Safety](/advanced/safety-model#_2-temporal-safety) for the
trust boundaries it is sound within. As with every other region/aliasing
check, `unsafe {}` bypasses this one too.

The same analysis covers `&heap T` references freed with `std::dealloc(p)`
— through copies, struct fields, array elements and calls; see
[Memory & Regions](/reference/memory#compile-time-use-after-free-and-double-free-checking).

### Scope Depth Tracking {#scope-depth-tracking}

The compiler assigns a `declScopeDepth` to each region and reference. An inner reference cannot be stored in an outer scope:

```c
&stack int ref;
{
    int inner = 10;
    ref = &inner;              // ERROR: inner scope ref escaping to outer
}
```

## Aliasing Rules (Borrow Checker) {#aliasing-rules-borrow-checker}

SafeC enforces the principle: **either one mutable reference, or any number of immutable references, but never both simultaneously.**

### Single Mutable Reference {#single-mutable-reference}

```c
int x = 42;
&stack int a = &x;             // mutable borrow
&stack int b = &x;             // ERROR: x is already mutably borrowed
```

### Multiple Immutable References {#multiple-immutable-references}

```c
int x = 42;
&stack const int a = &x;       // immutable borrow
&stack const int b = &x;       // OK: multiple immutable borrows allowed
```

`const &stack int a` is the same type as `&stack const int a`: `const` may go on either side of the region qualifier.

### Mutable + Immutable Conflict {#mutable-immutable-conflict}

```c
int x = 42;
&stack const int r = &x;       // immutable borrow
&stack int w = &x;             // ERROR: cannot mutably borrow while
                               //        immutable borrow exists
```

::: tip This is what rules out WAR/RAW/WAW hazards
"One mutable XOR many shared" is the same guarantee usually described in
terms of *hazards*: a write-after-read, read-after-write, or
write-after-write on the same memory from two live references at once.
The "Mutable + Immutable Conflict" example above is a WAR/RAW hazard
(`w` could write while `r` still expects to read the old value) rejected
at compile time by the same borrow check that rejects two simultaneous
mutable borrows (a WAW hazard). This holds for ordinary single-threaded
aliasing; see [Concurrency](/reference/concurrency) for how the same
guarantee extends (and where it stops) across `spawn` and `unsafe`.
:::

### Scope-Based Tracking {#scope-based-tracking}

Borrows are tracked per scope. When a scope exits, its borrows are released:

```c
int x = 42;
{
    &stack int a = &x;         // mutable borrow in inner scope
}                              // borrow released here
&stack int b = &x;             // OK: no conflicting borrow
```

## Nullability Enforcement {#nullability-enforcement}

Plain references are non-null by default. Nullable references must use the `?&` prefix — with a region (`?&stack Node`) or without one (`?&Node`, an "outliving reference" with no tracked region at all; see [Memory & Regions](/reference/memory)) — and — unlike languages with flow-sensitive null narrowing — a bare `!= null` comparison does **not** make a later dereference safe: the compiler performs no flow analysis linking the check to the access. Reading a nullable reference is only sanctioned through `match`, `.is_null()`, `.default(fallback)`, or an explicit `unsafe` block:

```c
void demo(?&stack Node next) {
    // *next;                   // ERROR: cannot dereference nullable ref
                                //         directly, with or without a
                                //         preceding null check

    match (next) {
        case null:    return;
        case some(n): {
            int val = n.value;  // OK: n is bound as the non-null payload
        }
    }
}

void demo_default(?&stack Node next) {
    if (next.is_null()) {       // OK: sanctioned null check
        return;
    }
    // still cannot dereference 'next' directly here even after the
    // is_null() check above -- use match, or an explicit unsafe block
}
```

## Bounds Safety {#bounds-safety}

The compiler inserts bounds checks for array and slice accesses.

### Static Bounds Checking {#static-bounds-checking}

When the index is a compile-time constant, the check happens at compile time:

```c
int arr[5];
int x = arr[10];               // ERROR: index 10 out of bounds for
                               //        array of size 5
```

### Runtime Bounds Checking {#runtime-bounds-checking}

When the index is dynamic, the compiler inserts a runtime check that aborts the program on out-of-bounds access:

```c
int arr[5];
int i = get_index();
int x = arr[i];                // runtime check: if (i >= 5) abort();
```

### Slice Bounds Checking {#slice-bounds-checking}

Slice accesses are always bounds-checked against the slice length:

```c
[]int s = arr[0..3];
int x = s[i];                  // runtime check: if (i >= s.len) abort();
```

## Raw Pointers {#raw-pointers}

<a id="pointer-arithmetic"></a>

Outside `unsafe`, memory is reached only through references (`&T`, `?&T`, with or without a region), and a nullable reference has to be checked with `match`, `.is_null()` or `.default()` first. A raw `T*` is a C value that safe code can hold but not use:

| With a raw pointer `p` | Safe code | Needs `unsafe` |
|---|---|---|
| Store it, pass it to a C function, return it | Yes | |
| Compare it (`p == q`, `p == null`), widen it to `void*`, cast it to an integer | Yes | |
| `null` / `(T*)0` | Yes | |
| Make a nullable reference: `(?&T)p`, or pass it where a `?&T` is expected | Yes | |
| Make a non-null reference: `(&T)p`, or pass it where a `&T` is expected | | Yes: nothing checks it isn't null |
| Access through it: `*p`, `p[i]`, `p->field` | | Yes |
| Arithmetic: `p + n`, `p - n`, `p += n`, `p++`, `p - q` | | Yes |
| Make one from an integer: `(T*)0x4000` | | Yes |
| Reinterpret it: `(char*)p`, `(T*)voidPtr` | | Yes |

The usual way to use a pointer from C in safe code is to turn it into a nullable reference and match it. The region stays unknown (`?&T` has no region keyword), so the reference can't be stored anywhere that needs a known lifetime:

```c
extern int* find_entry(int key);

int lookup(int key) {
    ?&int r = (?&int)find_entry(key);   // region unknown, may be null
    match (r) {
        case some(v): return *v;        // v is a non-null reference
        case null:    return -1;
    }
    return -1;
}
```

Pointer arithmetic is compiled exactly as in C (scaled by the element size; `p - q` is a signed 64-bit element distance), but only inside `unsafe`:

```c
void fill(int* p, int n) {
    unsafe {
        int* end = p + n;
        while (p < end) { *p = 0; p += 1; }
    }
}
```

References have no arithmetic at all: index through a slice or a container instead.

## Strict Aliasing {#strict-aliasing}

SafeC follows C's strict aliasing rule: a value of one scalar type is never read or written through an lvalue of a different scalar type, except through 8-bit (`char`, `unsigned char`, `int8_t`, `bool`) lvalues, which may access anything. Signed and unsigned integers of the same width count as one type; an enum or newtype counts as its underlying type; every pointer and reference counts as one type.

The compiler passes this to LLVM as type-based alias information. That lets a loop keep a struct's length and data pointer in registers while it stores elements of another type:

```c
struct Buf { float* data; unsigned long len; };

void scale(&Buf b, float k) {
    unsafe {
        // The float stores can't change b->len or b->data, so both are
        // loaded once and the loop vectorizes.
        for (unsigned long i = 0UL; i < b->len; i = i + 1UL) {
            b->data[i] = b->data[i] * k;
        }
    }
}
```

Safe code can't break the rule: reinterpreting a pointer as another pointer type requires `unsafe`. Inside `unsafe`, copy bytes instead of casting when you need a value's bit pattern as another type:

```c
#include <std/stddef.h>
extern void* memcpy(void* dst, const void* src, size_t n);   // size_t, not unsigned long: it's 32-bit on wasm32

unsigned int float_bits(float f) {
    unsigned int bits = 0U;
    unsafe { memcpy((void*)&bits, (const void*)&f, (size_t)4); }   // not *(unsigned int*)&f
    return bits;
}
```

Union members and `volatile` accesses are exempt. Code that does type-pun through pointer casts must be built with `--no-strict-aliasing`, which turns the alias information off for that compilation.

Reference parameters are not marked `noalias`. The borrow checker stops two arguments from aliasing, but a function can still reach the same object another way, for example through a global it writes directly, so that promise can't be made.

## Ownership and Drop {#ownership-and-drop}

A type that defines `void drop()` (the builtin `Drop` trait) owns a resource, and so does any type holding one. The compiler checks that every such value is dropped or moved exactly once on every path — through `return`, `break`, `goto`, `match` and `defer` — and rejects leaks, use after drop or move, double drops and overwriting a live value. It inserts no code: `drop()` runs only where the source calls it.

```c
void f(int c) {
    struct Vec<int> v = Vec::new();
    if (c) return;      // ERROR: value of Drop type 'Vec<int>' leaks when the function returns
    v.drop();
}
```

See [Ownership & Drop](/reference/ownership).

## Thread Safety {#thread-safety}

Every type is Send (may move to another thread) and/or Sync (may be shared between threads by `const &`), derived from its structure. `spawn`, `spawn_scoped`, channels, `std::thread_spawn`, `Mutex<T>` and the async thread pool accept only what is safe to hand over, which rules out data races outside `unsafe`. See [Thread Safety](/reference/threads).

## The Unsafe Boundary {#the-unsafe-boundary}

When the safety rules are too restrictive, `unsafe {}` blocks allow bypassing them. Inside an `unsafe` block:

- Bounds checks are suppressed (the `boundsCheckOmit` flag is set)
- Region escape analysis is relaxed
- Aliasing rules are not enforced
- Raw pointers can be dereferenced (`*p`, `p[i]`, `p->f`), moved with arithmetic, made from integers, reinterpreted as other pointer types, and turned into non-null references (`(&T)p`)
- Null dereferences are not prevented
- Ownership of values reached through raw pointers is not tracked, and `forget(x)` may give up a Drop value without dropping it

```c
unsafe {
    int *raw = (int*)malloc(10UL * sizeof(int));
    raw[0] = 42;               // no bounds check
    int *alias = raw;          // aliasing allowed
    free(raw);
}
```

### Unsafe Rules {#unsafe-rules}

1. **Unsafe blocks are explicit** -- the programmer opts in to danger
2. **Unsafe does not propagate** -- calling a safe function from unsafe context does not make the callee unsafe
3. **Minimize unsafe surface area** -- keep unsafe blocks as small as possible

### Common Unsafe Uses {#common-unsafe-uses}

- FFI calls with non-static region references
- Raw pointer manipulation from C APIs
- Performance-critical inner loops where bounds checks are measurably costly
- Hardware register access in bare-metal code

## Summary of Safety Guarantees {#summary-of-safety-guarantees}

By mechanism:

| Safety Check | Compile-Time | Runtime | Suppressed by `unsafe` |
|-------------|-------------|---------|----------------------|
| Definite initialization | Yes | -- | No |
| Region escape analysis (stack/static/cross-region) | Yes | -- | Yes |
| Arena-reset/free_to invalidation (dataflow analysis; see above) | Yes | -- | Yes |
| Heap use-after-free/double-free invalidation (`std::dealloc(p)`, same analysis; see [Memory & Regions](/reference/memory#compile-time-use-after-free-and-double-free-checking)) | Yes | Yes (backstop, wider coverage) | Yes |
| Aliasing / borrow check | Yes | -- | Yes |
| Nullability enforcement | Yes | -- | Yes |
| Static bounds check | Yes | -- | Yes |
| Dynamic bounds check | -- | Yes | Yes |
| Ownership: leaks, use after drop/move, double drop of [Drop](/reference/ownership) values | Yes | -- | Yes |
| Thread safety: Send/Sync of what threads receive ([Thread Safety](/reference/threads)) | Yes | -- | Yes (raw pointers only in `unsafe`) |
| Allocator double-free/invalid-free tag check (`std::alloc`/`dealloc`, `pool.h`/`slab.h`/`tlsf.h`) | -- | Yes | N/A (runtime-only) |

By named bug class — this maps the mechanisms above onto the classic C
bug taxonomy, since a single mechanism (e.g. the borrow checker) often
covers a bug class that goes by several names:

| Bug Class | Compile-Time | Runtime | How |
|-----------|-------------|---------|-----|
| Uninitialized variable read | Yes | -- | Definite initialization, above. |
| Null pointer dereference (`&T`, `?T`, `?&region T`) | Yes | -- | Nullability enforcement, above. Raw C `T*` is **not** covered — dereferencing one is only reachable inside `unsafe {}`, same responsibility model as C. |
| Buffer overflow / overread, constant index | Yes | -- | Static bounds check, above. |
| Buffer overflow / overread, dynamic index | -- | Yes | Runtime bounds check, above — proving an arbitrary runtime-computed index in-bounds ahead of time is undecidable in general, so this is a deliberate runtime backstop, not a gap. |
| Data race: WAR/RAW/WAW on aliased data, single-threaded | Yes | -- | The borrow checker's "one mutable XOR many shared" rule — see the tip above the "Scope-Based Tracking" section. |
| Data race: across threads | Yes | -- | Every type is Send and/or Sync, derived from its structure; `spawn` only shares static data that is Sync (or synchronizes its own writes), `spawn_scoped` lends locals until the join, channels and `thread_spawn` move Send values, and `Mutex<T>` reaches its value only through a guard. See [Thread Safety](/reference/threads) for what is and isn't covered (deadlocks are not). |
| Data race: inside `unsafe` / raw pointers | -- | -- | Deliberately unchecked — `unsafe` is the explicit opt-out for aliasing tracking, same as C. Not a bug in the checker; it's the boundary by design. |
| Use-after-free: stack references | Yes | -- | Region escape analysis, above. |
| Use-after-free: arena references | Yes | -- | Dataflow analysis over each function body, with per-mark `arena_free_to` tracking and call summaries; see [Arena References Die on Reset](#arena-references-die-on-reset). |
| Use-after-free: heap references (`std::dealloc(p)`) | Yes | Yes (backstop) | The same dataflow analysis — follows the pointer through copies, struct fields, array elements and calls, and recognizes allocator-instance `.dealloc()` methods too; see [Memory & Regions](/reference/memory#compile-time-use-after-free-and-double-free-checking) for what it does *not* cover (raw `free()` and other bodiless `extern` functions, calls through function pointers, memory reached through raw pointers in `unsafe {}`). |
| Double free | Yes (`std::dealloc(p)` or an allocator's `.dealloc(p)` on a `&heap T`, or a repeated arena reset/free_to) | Yes (every other allocator, and as a backstop for the compile-time cases too) | Same mechanism/scope as the heap-UAF row above, plus the allocator tag check below for everything it doesn't cover. |
| Mismatched free (freeing a pointer with the wrong allocator's free function) | -- | Yes | Each allocator (`std::alloc`/`dealloc`, and `pool.h`/`slab.h`/`tlsf.h`'s allocators) tags its own blocks and checks the tag on free. The compiler doesn't track *which* allocator produced a given pointer, so a pointer freed through the wrong allocator's free function is caught when that allocator's own tag check rejects it at runtime, not ahead of time. |
| Memory leak: values of a Drop type | Yes | -- | A type with a `drop()` method (or one holding such a value) must be dropped or moved on every path: forgetting one, using it after `drop()`, or dropping it twice is a compile error, with no run-time cost. See [Ownership & Drop](/reference/ownership). The standard library's owning types (`Vec`, `String`, `HashMap`, files, sockets, threads, guards, GUI and audio handles, futures) all implement `Drop`. |
| Memory leak: raw allocations, `unsafe`, cycles | -- | -- | A bare `&heap T` from `std::alloc` that no Drop type owns, memory behind raw pointers, and anything given up with `forget()` (only allowed in `unsafe`) aren't tracked. Wrap allocations in a type with `drop()`, or pair them with `defer std::dealloc(...)` right next to them — see [Defer](/reference/control-flow#defer). |
