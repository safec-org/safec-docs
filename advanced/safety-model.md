# Formal Safety Model

SafeC's safety guarantees are not aspirational — they are formalized as a type system with syntactic type safety (progress + preservation), drawing on prior work from Oxide (Weiss et al.) and Cyclone (Grossman et al.).

## Seven Safety Properties

### 1. Spatial Safety

No out-of-bounds memory access. Array subscripts are bounds-checked at compile time for constant indices and at runtime for dynamic indices.

```c
int arr[4];
arr[5] = 1;        // compile-time error: index 5 out of bounds for array of size 4

int i = get_index();
arr[i] = 1;        // runtime bounds check inserted (abort on violation)
```

Runtime bounds checks can be suppressed inside `unsafe {}` blocks.

### 2. Temporal Safety

No use-after-free: region annotations tie reference lifetimes to allocation scopes. A reference to stack memory cannot escape the function, a reference into an arena is invalidated by `arena_reset<R>()`/`arena_destroy<R>()`/`arena_free_to<R>()`, and a `&heap T` reference is invalidated by `std::dealloc()` — all three are checked at compile time, not just at runtime.

```c
&stack int get_local() {
    int x = 42;
    return &x;      // compile-time error: stack reference escapes function
}
```

```c
#include <std/mem.sc>

void heap_example() {
    &heap int p = new int;
    std::dealloc(p);
    *p = 1;              // compile-time error: use of 'p' after std::dealloc() freed it
    std::dealloc(p);      // compile-time error: double-free, same check
}
```

::: warning Flow-sensitive, not exhaustive
Both the arena and heap sides of this (see [Memory & Regions](/reference/memory#_4-arena-references-die-on-reset)
and [Memory & Regions](/reference/memory#compile-time-use-after-free-and-double-free-checking))
are generation-counter checks, updated as the compiler walks the
function's actual control flow (if/else branches don't bleed into each
other; a reset/free_to/dealloc anywhere in a loop body is treated as
possibly having happened before every statement in that body, not just
the ones after it) rather than a naive linear pass — sound (a genuine
use-after-reset, use-after-free_to, use-after-free, or double-free is
never missed) but not a full dataflow fixpoint, so it can still
over-flag references that are actually valid in less common shapes: the
loop-body check is a syntactic pre-scan covering the common statement/
expression forms a reset/destroy/free_to/dealloc call appears in, not
literally every nesting; `arena_mark<R>()`/`arena_free_to<R>()` remain
conservative across *deeply nested* marks independent of
flow-sensitivity — a design characteristic of tracking one nesting depth
per region rather than a per-level record, not something loop/branch
tracking addresses. Heap tracking is intra-procedural, and follows a
direct-copy alias one hop deep — `&heap T q = p;` or `q = p;` shares p's
own tracking key, so `std::dealloc()` through *either* name correctly
invalidates both — but no further: an allocation reached through a struct
field, array element, or function parameter/return isn't tracked (see the
warning in [Memory & Regions](/reference/memory#compile-time-use-after-free-and-double-free-checking)
for the exact scope). `unsafe {}` remains the escape hatch for cases the
checker can't (yet) prove safe on its own.
:::

### 3. Aliasing Safety

Mutable references are exclusive. The borrow checker enforces that at any point in the program, a value has either one mutable reference or any number of immutable references, but never both simultaneously.

```c
int x = 10;
&stack int a = &x;          // mutable borrow
&stack const int b = &x;    // error: cannot borrow 'x' as immutable: already borrowed as mutable
```

::: warning Write `const` after the region qualifier
`const &stack int b` (const *before* the region) is accepted by the parser
but silently misparses as a mutable borrow — a known parser bug (see
[Safety](/reference/safety#aliasing-rules-borrow-checker)). Always write
`const` *after* the region qualifier (`&stack const int`, as above).
:::

### 4. Region Escape Safety

References cannot outlive the region they point into. The compiler tracks region scope depth and rejects any assignment or return that would move a reference to a shallower scope.

```c
region R { capacity: 1024 }
&arena<R> int outer_ptr;

void demo() {
    &arena<R> int p = new<R> int;
    outer_ptr = p;   // error: cannot assign '&arena<R> int' to variable in
                     //        outer scope: arena reference would escape
}
```

### 5. Data Race Freedom

Threads spawned with `spawn()` operate on isolated data. The type system prevents sharing mutable state across thread boundaries without explicit synchronization.

### 6. Null Safety

References are non-null by default. There is no null reference in safe SafeC code. Nullable references use the `?&T` syntax; unlike languages with flow-sensitive null narrowing, a bare `!= null` comparison does not by itself make a later dereference safe — reading a nullable reference is only sanctioned through `match`, `.is_null()`, `.default(fallback)`, or an explicit `unsafe` block (see [Safety](/reference/safety#nullability-enforcement)).

### 7. Determinism

No hidden runtime costs. SafeC does not insert garbage collection, reference counting, or implicit heap allocations. Every allocation is explicit in the source code. The performance model is transparent — what you write is what runs.

## Memory Safety Compared to C, C++, Rust, and Zig

[Comparison](/guide/comparison) has a one-line "Memory Safety: Yes/No" summary across the same five languages; this breaks that single row out into the properties above, since "yes" and "no" hide real differences in *when* each language checks (compile time vs. runtime vs. not at all) and *how completely* (every safe-mode program vs. an opt-in build mode vs. nothing).

| Property | C | C++ | Rust | Zig | SafeC |
|---|---|---|---|---|---|
| Spatial safety (buffer overflow) | None — `[]` has no check, OOB is UB | None for raw arrays/`[]`; opt-in via `.at()`/`std::span` (throws) | Compile-time for const indices, runtime panic otherwise — always on in safe code | Runtime check in Debug/ReleaseSafe; **compiled out** (UB) in ReleaseFast/ReleaseSmall | Compile-time for const indices, runtime check otherwise — always on outside `unsafe` |
| Temporal safety (use-after-free) | None — manual `free()`, no tracking | None for raw pointers/refs; RAII (`unique_ptr`/`shared_ptr`) helps only if used consistently, not enforced | Compile-time — the borrow checker rejects any use after move/drop; sound | None from the compiler; `GeneralPurposeAllocator` catches some in Debug builds only, at runtime | Compile-time, region-based (stack/arena/heap) — flow-sensitive and sound but [not exhaustive](#_2-temporal-safety); `unsafe {}` opts out |
| Double-free | None — UB | Same as C for raw `delete`; smart pointers avoid it structurally unless defeated by raw aliasing | Compile-time — only one owner can ever call drop | Runtime-only, opt-in (`GeneralPurposeAllocator` in Debug) | Compile-time (same check as above), plus a runtime tagged-header guard in `std::alloc`/`std::dealloc` as defense in depth |
| Uninitialized reads | None — UB | None — UB | Compile-time — definite-assignment analysis | `undefined` is explicit and typed; Debug builds poison it (`0xaa`) to make bugs *visible*, not prevent them | Compile-time — definite-initialization check |
| Null-pointer dereference | None — raw pointers, untracked | None for raw pointers; references can't be null by construction but can be formed from a deref'd null pointer (UB), uncaught | Compile-time — no null in safe code, `Option<T>` must be matched/unwrapped | Compile-time — optionals (`?T`) must be explicitly unwrapped | Compile-time — `?&T` requires `match`/`.is_null()`/`.default()`/`unsafe` to read (see [Null Safety](#_6-null-safety)) |
| Data races | None — no language-level concurrency safety | None — races are UB; `atomic`/mutexes are opt-in tools, not enforced | Compile-time — `Send`/`Sync` traits enforce shared-state safety in safe code | None — no type-system enforcement, manual synchronization only | Compile-time — `spawn()` isolates data by default; sharing mutable state requires explicit synchronization |
| Enforcement point | N/A | Mostly none; a few opt-in runtime checks | Compile-time first (borrow checker), runtime for the genuinely dynamic parts (bounds, panics) | Runtime, and only in safety-checked build modes — the *same binary* can be compiled with checks off | Compile-time first, runtime only where the check is inherently dynamic (index bounds) — always on outside `unsafe` |
| Escape hatch | N/A — the whole language is this way | N/A — the whole language is this way | `unsafe {}` blocks/functions | No lexical escape hatch — safety is a whole-build-mode setting, not a per-block opt-out | `unsafe {}` blocks — lexically scoped, non-propagating (see below) |

The throughline: C and C++ don't check most of these at all (undefined behavior is the "checked" state most of the time). Zig checks several of them, but as a *build-mode* choice — the same source either has the checks or doesn't, decided once for the whole binary, not per-line. Rust and SafeC both check at compile time by default and require an explicit, lexically-scoped opt-out (`unsafe {}`) to turn a check off for a specific block of code — that's the property this page's own [`unsafe` escape hatch](#the-unsafe-escape-hatch) section is describing. Where Rust and SafeC differ is *how* they prove temporal safety (ownership/borrowing vs. region membership — see [Compared to Rust](/guide/comparison#compared-to-rust) for that trade-off in detail) and how complete the proof is: Rust's borrow checker is a sound, complete static proof for the patterns it accepts (it may reject valid programs, but never accepts an unsound one); SafeC's region/generation-counter checks are sound but intentionally incomplete in a few documented shapes (nested arena marks, heap aliasing beyond one hop — see the warnings throughout this page), trading some precision for a simpler, more C-compatible mental model.

## Safety Analysis Phases

The compiler enforces these properties through a series of analysis passes during semantic analysis:

| Phase | What it checks |
|-------|---------------|
| Definite initialization | Every variable is assigned before use |
| Region escape analysis | Stack/static/cross-region escapes are rejected; arena-reset invalidation is checked flow-sensitively across if/else and loops, not exhaustively (see above) |
| Alias/borrow checking (NLL) | Mutable exclusivity is maintained |
| Nullability enforcement | Dereferencing a nullable reference outside `match`/`.is_null()`/`.default()`/`unsafe` is rejected |
| Bounds checking | Array accesses are within bounds |

All checks run at compile time. The only runtime insertion is bounds checks for dynamic array indices, and those are visible in the generated IR.

## The `unsafe` Escape Hatch

All safety checks can be locally suppressed inside `unsafe {}` blocks:

```c
unsafe {
    int* raw = (int*)some_addr;
    *raw = 42;       // no bounds check, no borrow check, no region check
}
```

`unsafe` is lexically scoped. It does not propagate to called functions. It serves as a grep-able marker for code that requires manual auditing.

## FFI Boundary Rules

Foreign function calls (C interop) follow specific safety rules:

- `extern` declarations use raw C types — no region qualifiers
- `&static T` to `T*` coercion is safe without `unsafe {}`
- `&T`/`?&T` (no region qualifier at all — an "outliving reference," see
  [Memory & Regions](/reference/memory)) also coerces to/from `T*`/`void*`
  safely without `unsafe {}`, in both directions — the type for a pointer
  that may be retained by the C side past the call returning
- Non-static, non-region-less references passed to C require `unsafe {}`
- Raw pointers received from C must be handled inside `unsafe {}`, unless
  received directly into a `&T`/`?&T`-typed variable

These rules ensure that the boundary between safe SafeC code and unsafe C code is always explicit.

## Formal Foundations

The safety model is grounded in established type theory:

- **Oxide** (Weiss et al., 2019) — formalization of Rust's ownership and borrowing as a typed calculus with places and provenance
- **Cyclone** (Grossman et al., 2002) — region-based memory management with static safety guarantees for a C-like language

SafeC adapts these formalisms to a C-compatible language with regions as the primary memory safety mechanism.

### Progress and Preservation

The type system satisfies the standard syntactic safety properties:

- **Progress**: A well-typed program either is a value, can take a step, or is a sanctioned runtime error (bounds check failure with abort)
- **Preservation**: If a well-typed program takes a step, the resulting program is also well-typed

Together, these properties guarantee that well-typed SafeC programs do not exhibit undefined behavior outside of `unsafe {}` blocks.
