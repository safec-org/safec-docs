# Memory & Regions

SafeC uses a region-based memory model that enforces lifetime safety at compile time. Every reference carries a region qualifier that tells the compiler where the data lives and how long it will be valid. Regions are compile-time only -- they produce no runtime metadata and erase to raw pointers in the generated code.

## The Four Regions {#the-four-regions}

| Region | Lifetime | Allocation | Deallocation |
|--------|----------|------------|--------------|
| `stack` | Lexical scope | Automatic (stack frame) | Automatic (scope exit) |
| `static` | Program lifetime | Static storage | Never |
| `heap` | Dynamic | Explicit (`malloc`) | Explicit (`free`) |
| `arena<R>` | User-defined | Region allocator | Bulk reset |

## Region-Qualified References {#region-qualified-references}

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

## Stack Region {#stack-region}

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

## Static Region {#static-region}

The `static` region is for data that lives for the entire program duration:

```c
static int counter = 0;

&static int get_counter() {
    return &counter;           // OK: counter lives forever
}
```

Static references are the most permissive -- they can be stored anywhere because they never become invalid.

## Heap Region {#heap-region}

The `heap` region is for dynamically allocated data. Heap references require explicit allocation and deallocation:

```c
void example() {
    &heap int p = new int;
    *p = 42;
    std::dealloc(p);
}
```

The compiler tracks heap references but does not automatically free them. Use `defer` for deterministic cleanup:

```c
void process() {
    &heap char buf = new char[4096];
    defer std::dealloc(buf);

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

`<std/mem.h>`'s `malloc_defer(n)` sugars the alloc-then-`defer dealloc` pattern
above into a single call. `malloc_defer` isn't a real function — it's
parser-level sugar recognized only as a var-decl initializer, and it
desugars into `std::alloc_raw_(n)` (the type-erased allocator behind the
generic `std::alloc`) for the declaration plus a synthesized
`defer std::dealloc_raw_(buf);` right after it. The result is a raw
`void*`, so declare the variable as a nullable heap reference to get a
typed, null-checked view:

```c
#include <std/mem.h>

void process2() {
    ?&heap int buf = malloc_defer(4096UL);   // freed automatically at scope exit
    match (buf) {
        case some(p): { *p = 1; }   // ... use p ...
        case null:    { }           // out of memory
    }
}   // std::dealloc_raw_(buf) runs here via the synthesized defer
```

A deferred free runs when the scope exits, so it doesn't count as freeing
the reference for the statements after it — only an explicit `dealloc`
that has already run does (see below).

### Compile-Time Use-After-Free and Double-Free Checking {#compile-time-use-after-free-and-double-free-checking}

A `&heap T` reference freed with `std::dealloc(p)` (or an allocator's
`.dealloc(p)` method) is tracked by the same dataflow analysis as arena
references (see [Arena References Die on Reset](#_4-arena-references-die-on-reset)):
reading it after that point is a compile error, and freeing it again is a
double free, also caught at compile time:

```c
#include <std/mem.h>

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

The analysis tracks each pointer, not each variable name. A free
reaches every copy of the pointer (through any number of assignments),
every struct field and array element it was stored in, and, through
per-function summaries, the caller's copies when a callee frees its
parameter or a field of it. When the callee itself is fine but what the
caller passes it was already freed, the error is reported at the call:

```c
#include <std/mem.h>

struct Box { &heap int v; };

void release(&heap int p) { std::dealloc(p); }
void release_box(&Box b)  { std::dealloc(b.v); }
int  peek(&Box b)         { return *b.v; }

void g() {
    &heap int p = new int;
    &heap int q = p;           // any number of copies...
    struct Box b;
    b.v = q;                   // ...and fields or array elements
    release(b.v);              // release() frees its parameter
    *p = 1;                    // ERROR: use of 'p' (&heap reference) after
                               //        std::dealloc() freed it
    int n = peek(&b);          // ERROR: call to 'peek' reads a &heap reference
                               //        after std::dealloc() freed it
    release_box(&b);           // ERROR: call to 'release_box' frees memory
                               //        after std::dealloc() freed it
}
```

::: warning Trusted, not checked
Within its model the check is sound; the full statement is under
[Temporal Safety](/advanced/safety-model#_2-temporal-safety). Outside it:

- A raw libc `free(p)`, any other function with no body (`extern`
  declarations, C imports), calls through function pointers or `fn`
  values, and the function a `spawn` starts are assumed to free nothing
  and keep no pointer to their arguments. A pointer freed that way isn't
  caught at compile time; it relies on the runtime double-free/
  invalid-free protection described in [Safety](/reference/safety).
- Memory reached through a raw pointer, or pointer indexing in
  `unsafe {}` (the buffers of `std::Vec` and the other std containers),
  isn't tracked; a pointer read back from there counts as live.
- Distinct parameters (and globals) of one function are assumed not to
  point to the same allocation.

Two shapes that are actually fine are still reported: a free and a use
guarded by complementary conditions (`if (owned)` / `if (!owned)`), and a
loop that frees a linked structure node by node through safe references
(write it recursively, or walk it with raw pointers in `unsafe {}`).
`unsafe {}` suppresses the reports inside it, same as every other region
rule; its frees still count for the code after it.
:::

### Leak Checking: Owning Types {#leak-checking}

The checks above cover *references*: they catch a use or a free after a free. They don't require the memory to be freed at all — a bare `&heap T` that is never deallocated is not reported. Leaks are caught for **owning types** instead: a struct with a `drop()` method (like `std::Vec`, `std::String` or a `Buf` that frees its buffer in `drop()`) must be dropped or moved on every path, so the allocation it owns can't be forgotten:

```c
struct Buf { &heap char data; void drop(); };
void Buf::drop() { std::dealloc(self.data); }

void f(int c) {
    struct Buf b = Buf::new(4096UL);
    if (c) return;     // ERROR: value of Drop type 'Buf' leaks when the function returns
    b.drop();
}
```

Wrap allocations that outlive a scope in such a type. See [Ownership & Drop](/reference/ownership).

## Arena Regions {#arena-regions}

Arenas provide bulk allocation and deallocation. You declare a region, allocate into it with `new<R>`, and reset all allocations at once with `arena_reset<R>()`.

### Declaring a Region {#declaring-a-region}

```c
region AudioPool {
    capacity: 4096
}
```

### Allocating in an Arena {#allocating-in-an-arena}

```c
&arena<AudioPool> Sample s = new<AudioPool> Sample;
```

The `new<R> T` expression performs a bump-pointer allocation from the arena. It is fast (just an offset increment plus a capacity check). An allocation that doesn't fit in the region's remaining capacity aborts the program, the same way an out-of-bounds index does — it never hands out memory past the end of the arena.

### Resetting an Arena {#resetting-an-arena}

```c
arena_reset<AudioPool>();
```

This resets the arena's offset to zero, effectively freeing all allocations at once. No destructors are called -- arena-allocated objects must not hold external resources.

### Destroying an Arena {#destroying-an-arena}

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

### Partially Freeing an Arena {#partially-freeing-an-arena}

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

Every `arena_mark<R>()` call is tracked as its own checkpoint, so nested
marks are precise: rewinding to an inner mark leaves references
allocated between the outer and inner marks valid.

```c
region Pool { capacity: 4096 }

void nested() {
    unsigned long outer = arena_mark<Pool>();
    &arena<Pool> struct Sample a = new<Pool> struct Sample;
    unsigned long inner = arena_mark<Pool>();
    &arena<Pool> struct Sample b = new<Pool> struct Sample;
    arena_free_to<Pool>(inner);
    a->v = 1;      // OK: 'a' was allocated before 'inner'
    b->v = 1;      // ERROR: use of 'b' (&arena<Pool> reference) after arena_reset<Pool>(),
                   //        arena_destroy<Pool>(), or arena_free_to<Pool>() invalidated it
    arena_free_to<Pool>(outer);
    a->v = 2;      // ERROR: same message, for 'a'
}
```

Only a mark the checker can't identify (one passed in by the caller that
it can't resolve, or a value that may be one of several marks) makes
`arena_free_to<R>()` invalidate more than that, conservatively. A callee
that rewinds to a mark it was passed is applied at the call site.

Inside a loop, a mark/free_to pair is fine (each iteration's free_to
rewinds only that iteration's allocations, as in the scratch example
above), and an `arena_free_to<R>()` that invalidates a reference used
earlier in the same body is caught on the next iteration:

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

`unsafe {}` bypasses the check, same as every other region rule.

### Arena Runtime Representation {#arena-runtime-representation}

At the LLVM level, each arena is a global struct `{ptr, i64 used, i64 cap}`:

- `ptr` -- base pointer to the arena's backing memory
- `used` -- current bump offset
- `cap` -- total capacity in bytes

`new<R> T` (or `new<R> T[n]`) checks that `sizeof(T)` (times `n`) fits in `cap - used`, aborting if it doesn't, then increments `used` and returns `ptr + old_used`. `arena_reset<R>()` sets `used` back to 0.

## Arrays by Region {#arrays-by-region}

How big an array can be decided depends on where it lives:

| Region | Declaration | Size |
|--------|-------------|------|
| stack | `int buf[8];` | compile-time constant (a runtime size is only allowed inside `unsafe`) |
| static | `static int table[16];` | compile-time constant |
| heap | `&heap int h = new int[n];` | any runtime `n`; free with `std::dealloc(h)` |
| arena | `&arena<R> int a = new<R> int[n];` | any runtime `n`, but it must fit in `R`'s remaining capacity — otherwise the program aborts |

`new` returns a reference to the first element. A reference carries no length and can't be indexed, so pass and walk arrays as a bounds-checked [`Slice<T>`](/stdlib/collections#slice) (`std::Slice::of(first, n)`) or a `[]T` slice. Arrays of references work the same way — `&T items[N]` on the stack, `Slice<&T>` across function boundaries — which is what replaces C's `T**` "array of pointers":

```c
#include <std/collections/slice.h>
struct Sample { int v; };
region Frame { capacity: 4096 }

static int g_table[16];                      // static: size fixed at compile time

void fill(struct Slice<int> s, int v) {
    for (unsigned long i = 0UL; i < s.length(); i = i + 1UL) s.set(i, v);
}

int arrays(unsigned long n) {
    int local[8];                            // stack: size fixed at compile time
    // int bad[n];                           // error: 'n' is not a compile-time constant
    &heap int h = new int[n];                // heap: runtime size
    &arena<Frame> int a = new<Frame> int[n]; // arena: runtime size, within Frame's 4096 bytes

    fill(std::Slice::of(&local[0], 8UL), 1);  // view each one through a bounds-checked Slice
    fill(std::Slice::of(h, n), 2);
    fill(std::Slice::of(a, n), 3);
    int t = local[7] + *std::Slice::of(h, n).at(n - 1UL) + *std::Slice::of(a, n).at(0UL) + g_table[0];

    std::dealloc(h);
    arena_reset<Frame>();
    return t;                                // 1 + 2 + 3 + 0
}

// Arrays of references follow the same rules.
int count_live(struct Slice<&Sample> items) { return (int)items.length(); }

int main() {
    struct Sample a; a.v = 1;
    struct Sample b; b.v = 2;
    &Sample fixed[2] = { &a, &b };           // stack array of references
    int c = count_live(std::Slice::of(&fixed[0], 2UL));
    return arrays(4UL) == 6 && c == 2 ? 0 : 1;
}
```

## References to References {#references-to-references}

A reference can refer to another reference — the replacement for C's `T**`. Pick the form by what each level may hold:

| Type | Outer | Inner | Typical use |
|------|-------|-------|-------------|
| `&&T` | always present | always present | out-parameter that always receives a reference |
| `&?&T` | always present | may be null | out-parameter whose result may be "none" (a tokenizer's position, a lookup) |
| `?&&T` | optional | always present | optional out-parameter (`strtol`'s `end`) |
| `?&?&T` | optional | may be null | optional out-parameter whose result may be "none" |

```c
static int g_value = 7;

void always(&&int out)      { *out = &g_value; }   // writes a non-null reference
void maybe(&?&int out)      { *out = null; }       // may write null
void optional(?&&int out) {
    match (out) {
        case some(o): { *o = &g_value; }
        case null:    { }                          // caller didn't ask
    }
}
```

`&&T` and `& &T` mean the same thing. An array of references is not a reference to a reference — wrap it in an array or `Slice<&T>` as above.

## Region Rules {#region-rules}

The compiler enforces several rules to guarantee memory safety:

### 1. References Cannot Outlive Their Region {#_1-references-cannot-outlive-their-region}

```c
&stack int ref;
{
    int x = 10;
    ref = &x;          // ERROR: x's scope is shorter than ref's
}
// ref would be dangling here
```

### 2. References Cannot Escape to a Longer-Lived Region {#_2-references-cannot-escape-to-a-longer-lived-region}

A reference to a short-lived region cannot be stored in a longer-lived location:

```c
static &stack int global_ref;  // ERROR: stack ref stored in static

void bad() {
    int local = 5;
    global_ref = &local;       // ERROR: stack ref escaping to static
}
```

### 3. No Implicit Cross-Region Conversion {#_3-no-implicit-cross-region-conversion}

References from different regions are not interchangeable:

```c
void takes_heap(&heap int p);

void example() {
    int x = 42;
    takes_heap(&x);            // ERROR: &stack int is not &heap int
}
```

### 4. Arena References Die on Reset {#_4-arena-references-die-on-reset}

`arena_reset<R>()` and `arena_destroy<R>()` invalidate *every* outstanding
reference into region `R`, since the memory they point into may be handed
out again by a later `new<R>`. After a reset or destroy, every reference
into `R` (and every copy of one, in a variable, field or array element)
may be dangling, and reading it is a compile error.
`arena_free_to<R>()` is narrower — see
[Partially Freeing an Arena](#partially-freeing-an-arena) — and only
invalidates references allocated after the checkpoint being freed to.

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

This is a dataflow analysis that follows the function's control flow,
not its text order — two cases worth knowing:

- **if/else branches don't bleed into each other.** A reset inside one
  branch affects the rest of *that branch* and the code *after the
  if-statement* (where that branch may have run) — not the *other*
  branch:
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
  Each loop is analyzed until its state stops changing, so a reset
  reaches the code before it in the body on the next iteration wherever
  in the body (or inside whatever expression) it appears, and reaches
  the code after the loop only through the exits that can follow it.

The same holds for `switch` fallthrough, `match`, `?:`, `&&`/`||`,
`break`/`continue`/`goto`/`return`/`try`, and `defer`/`errdefer` (which
run at each return), and for heap frees as well as arena resets. A
function that resets the region, or rewinds to a mark it was passed,
invalidates the caller's references at the call. The check is sound —
a real use-after-reset or use-after-free_to is never missed — up to the
trust boundaries listed under
[Temporal Safety](/advanced/safety-model#_2-temporal-safety).

Rebinding a stale reference to a fresh allocation recovers it — the check
is about reading a *specific* stale binding, not about the variable name:

```c
&arena<Pool> int p = new<Pool> int;
arena_reset<Pool>();
p = new<Pool> int;   // OK: fresh allocation, made after the reset
*p = 42;              // OK
```

## Unsafe Escape Hatch {#unsafe-escape-hatch}

When the region rules are too restrictive, `unsafe {}` blocks allow bypassing them:

```c
unsafe {
    int *raw = (int*)malloc(sizeof(int));
    *raw = 42;
    free(raw);
}
```

See [Safety](/reference/safety) for the full unsafe model.

## Outliving References: `&T` / `?&T` With No Region {#outliving-references-t-t-with-no-region}

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

## FFI and Regions {#ffi-and-regions}

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
