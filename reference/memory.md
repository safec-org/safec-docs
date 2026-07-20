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

### Compile-Time Use-After-Free and Double-Free Checking

A `&heap T` variable freed with `std::dealloc(p)` is tracked the same
flow-sensitive way arena references are (if/else branches checked
independently and merged conservatively; loop bodies pre-scanned so a
free on one iteration is visible to code textually before it on the
next) — reading `p` after that point is a compile error, and freeing it
again is a double-free, also caught at compile time:

```c
#include <std/mem.sc>

void f() {
    &heap int p = new int;
    *p = 42;
    std::dealloc(p);
    *p = 43;              // ERROR: use of 'p' (&heap reference) after
                           //        std::dealloc() freed it
    std::dealloc(p);       // ERROR: same check -- this is the double-free
}
```

Rebinding recovers `p`, same as arena references:

```c
&heap int p = new int;
std::dealloc(p);
p = new int;        // OK: fresh allocation, fresh binding
*p = 42;             // OK
std::dealloc(p);
```

::: warning Intra-procedural, and 'std::dealloc(p)' specifically
This check is keyed to *this variable's own declaration*, not to the
underlying allocation — freeing a heap block through a different alias
(a second variable pointing at the same allocation, or the same pointer
value threaded through a different function) isn't tracked, so it's
sound within a function but not exhaustive across aliases or call
boundaries. It also only recognizes calls that resolve syntactically to
`std::dealloc(p)` with a bare identifier argument — a raw libc `free(p)`
call (as in the first example on this page, which predates this check),
an allocator-instance method like a `PoolAllocator`'s `.dealloc()` (see
[std/alloc/pool.h](/stdlib/index)), or `std::dealloc(some_expr())` with a
non-identifier argument fall outside this analysis entirely and rely on
the runtime double-free/invalid-free protection described in
[Safety](/reference/safety) instead. `unsafe {}` bypasses the check, same
as every other region rule.
:::

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

### Partially Freeing an Arena

`arena_reset<R>()` always rewinds all the way to zero. `arena_mark<R>()` /
`arena_free_to<R>()` are a checkpoint/rewind pair for freeing only the
*tail* of an arena's allocations — a scratch-space pattern where you take a
checkpoint, allocate some short-lived working data, then rewind past just
that data:

```c
region AudioPool { capacity: 4096 }

void process() {
    for (int i = 0; i < 10; i = i + 1) {
        unsigned long mark = arena_mark<AudioPool>();          // checkpoint
        &arena<AudioPool> struct Sample scratch = new<AudioPool> struct Sample;
        // ... use scratch for this iteration only ...
        arena_free_to<AudioPool>(mark);                        // free just 'scratch'
    }
}
```

`arena_mark<R>()` returns the region's current byte offset (an
`unsigned long`) as an opaque checkpoint value. `arena_free_to<R>(mark)`
rewinds the offset back to it at runtime, clamped so a stale or otherwise-
too-large `mark` can only shrink `used`, never grow it — verified: an
allocation made *before* the checkpoint keeps its original value after a
later allocation is freed back to that checkpoint and something else
reuses the space.

`arena_free_to<R>()` only invalidates references allocated *after* the
checkpoint it's rewinding to — a reference bound before any
`arena_mark<R>()` call was ever made for `R` survives a `arena_free_to<R>()`
call untouched, since the memory it points into is outside the scratch
range being freed:

```c
&arena<AudioPool> struct Sample keep = new<AudioPool> struct Sample;
unsigned long mark = arena_mark<AudioPool>();
&arena<AudioPool> struct Sample scratch = new<AudioPool> struct Sample;
arena_free_to<AudioPool>(mark);
keep->v = 5;      // OK: 'keep' was bound before any mark() — never staled by free_to()
scratch->v = 5;   // ERROR: 'scratch' was bound after the mark — correctly caught
```

::: warning Not precise across nested marks
Like the generation counter described below, this is tracked with a
running mark-nesting *depth*, updated as the compiler walks the
function — see "Arena References Die on Reset" below for how much of
that walk is now flow-*sensitive* (if/else branches, and loop bodies,
including `arena_mark`/`arena_free_to` as well as plain
`arena_reset`/`arena_destroy` — see below). A reference bound while any
`arena_mark<R>()` scope is active (depth > 0) is invalidated by *any*
later `arena_free_to<R>()` call while still inside that nesting —
precise for the common single-level "mark, allocate scratch, free_to"
pattern shown above, but conservative for deeply nested marks where an
outer scope's own references could, in principle, survive an inner
scope's free_to: this is a design characteristic of tracking one nesting
depth per region rather than a per-mark-level record, independent of
flow-sensitivity, so it isn't something branch/loop tracking can fix
(sound either way — it can over-flag a deeply-nested-mark reference that
was actually still valid, but it never misses a genuine use-after-free_to).

The loop pre-scan described below (for catching an earlier-iteration call
invalidating a use earlier in the same loop body) now covers
`arena_mark`/`arena_free_to` the same way it already covered
`arena_reset`/`arena_destroy`: a mark/free_to pair inside a loop body is
fine (each iteration's free_to cancels that iteration's mark, so the
depth returns to baseline every time, as in the scratch-allocation
example above), and an *unpaired* `arena_free_to<R>()` inside a loop,
invalidating an outer reference used earlier in the same body on a later
iteration, is caught too:

```c
region Pool { capacity: 4096 }

void process() {
    unsigned long mark = arena_mark<Pool>();
    &arena<Pool> struct Sample keep = new<Pool> struct Sample;
    for (int i = 0; i < 3; i = i + 1) {
        keep->v = 1;                 // ERROR: could be invalidated by the
                                      // free_to below on a prior iteration
        arena_free_to<Pool>(mark);   // unpaired -- no mark() in this loop body
    }
}
```

`unsafe {}` bypasses the check entirely, same as every other region rule.
:::

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

`arena_reset<R>()` and `arena_destroy<R>()` invalidate *every* outstanding
reference into region `R`, since the memory they point into may be handed
out again by a later `new<R>`. The compiler tracks this with a generation
counter per region: every reset/destroy call bumps `R`'s generation, and
reading a `&arena<R> T` reference whose generation doesn't match is a
compile error. `arena_free_to<R>()` uses a separate, narrower check — see
"Partially Freeing an Arena" above — that only invalidates references
bound after the checkpoint being freed to.

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

The generation counter is updated as the compiler walks the function's
control flow, not just its raw text order — two cases worth knowing:

- **if/else branches don't bleed into each other.** A reset inside one
  branch only affects the generation *within that branch* and *after the
  if-statement merges* (conservatively — since either branch might have
  run) — not the *other* branch:
  ```c
  if (cond) {
      arena_reset<Pool>();
  } else {
      *p = 2;   // OK: this branch never resets Pool
  }
  *p = 3;       // ERROR: 'then' might have run, so this is possibly stale
  ```
- **A reset anywhere in a loop body invalidates the whole body, not just
  code after it.** Since a later iteration runs the top of the body
  again *after* an earlier iteration's reset, a use that's textually
  *before* the reset call is still flagged:
  ```c
  while (cond) {
      *p = 1;                // ERROR: a *previous* iteration may have reset Pool here
      arena_reset<Pool>();
  }
  ```
  This loop-body check is a syntactic pre-scan for `arena_reset`/
  `arena_destroy`/`arena_free_to` calls reachable anywhere in the body
  (not a full type-checking pass) — it covers the common statement/
  expression forms such a call appears in, not literally every possible
  expression nesting. The same pre-scan catches an unpaired
  `arena_free_to<R>()` inside a loop invalidating an outer reference used
  earlier in the same body — see the nested-marks warning above for the
  one thing it still doesn't attempt (per-nesting-level precision, a
  separate design characteristic of the mark/free_to counters themselves).

Both of these are still sound in the same direction as the rest of this
checker: a real use-after-reset (or use-after-free_to) is never missed,
and the remaining imprecision (deeply unusual expression nesting inside a
loop; conservativeness across deeply nested marks) only means some code
that's actually safe might still be conservatively flagged — never the
reverse.

Rebinding a stale reference to a fresh allocation recovers it — the check
is about reading a *specific* stale binding, not about the variable name:

```c
&arena<Pool> int p = new<Pool> int;
arena_reset<Pool>();
p = new<Pool> int;   // OK: fresh binding, judged against the current generation
*p = 42;              // OK
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

## Outliving References: `&T` / `?&T` With No Region

Leaving the region qualifier off entirely — `&T` (non-nullable) or `?&T`
(nullable) — gives a reference with **no declared or tracked region** at
all. It isn't a fifth entry in the region-escape/lifetime analysis above;
it's the escape hatch from needing one, for two overlapping cases:

1. **Crossing the `extern` boundary.** A pointer that a C function might
   *retain* past the call returning (a registered callback's context, an
   opaque handle) can't honestly be `&stack` (dies with the call) or
   `&heap`/`&static` (claims a lifetime guarantee SafeC can't verify
   across the ABI boundary).
2. **Not committing to a region for its own sake.** Most functions that
   just read or write through a reference for the call's duration don't
   care whether the caller's value is `&stack`, `&heap`, `&static`, or
   `&arena<R>` — picking one specific region as a parameter type only
   narrows who can call it, for no safety benefit. Reserve a concrete
   region for when the parameter's own semantics genuinely pin it to one
   lifetime (e.g. a callee that stores the reference past its own return
   needs `&heap`/`&static`, not `&stack`).

```c
struct Point { int x; int y; }

// No region committed -- caller may pass &stack, &heap, &static, or &arena<R>.
int sumPoint(&Point p) { return p.x + p.y; }

struct Point local;
local.x = 3; local.y = 4;
&stack Point ref = &local;
sumPoint(ref);        // &stack Point -> &Point: implicit, no unsafe, no region check
```

A reference of *any* region converts to `&T`/`?&T` implicitly (no
`unsafe`, no cast), and `&T`/`?&T` converts back to a raw pointer
implicitly too — because there's no region to check in the first place.
Nullable vs. non-nullable is the ordinary, orthogonal choice here: use
`?&T` only where the value can genuinely be absent, `&T` otherwise. See
[Safety](/reference/safety) for the nullable-reference read grammar
(`match`/`is_null()`/`.default()`/`!`), which applies identically to
`?&T`.

## FFI and Regions

When calling C functions, region information is erased:

- `&static T` converts to `T*` automatically (safe, no `unsafe` needed)
- `&T`/`?&T` (no region) converts to/from `T*`/`void*` automatically in
  both directions (safe, no `unsafe` needed) — see "Outliving References"
  above
- Other region refs (`&stack`, `&heap`, `&arena<R>`) require `unsafe {}`
  to pass to C

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
