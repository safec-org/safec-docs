# Formal Safety Model

SafeC's safety guarantees are not aspirational — they are formalized as a type system with syntactic type safety (progress + preservation), drawing on prior work from Oxide (Weiss et al.) and Cyclone (Grossman et al.).

## Seven Safety Properties {#seven-safety-properties}

### 1. Spatial Safety {#_1-spatial-safety}

No out-of-bounds memory access. Array subscripts are bounds-checked at compile time for constant indices and at runtime for dynamic indices.

```c
int arr[4];
arr[5] = 1;        // compile-time error: index 5 out of bounds for array of size 4

int i = get_index();
arr[i] = 1;        // runtime bounds check inserted (abort on violation)
```

Runtime bounds checks can be suppressed inside `unsafe {}` blocks.

### 2. Temporal Safety {#_2-temporal-safety}

No use-after-free: region annotations tie reference lifetimes to allocation scopes. A reference to stack memory cannot escape the function, a reference into an arena is invalidated by `arena_reset<R>()`/`arena_destroy<R>()`/`arena_free_to<R>()`, and a `&heap T` reference is invalidated by `std::dealloc()` — all three are checked at compile time, not just at runtime.

```c
&stack int get_local() {
    int x = 42;
    return &x;      // compile-time error: stack reference escapes function
}
```

```c
#include <std/mem.h>

void heap_example() {
    &heap int p = new int;
    std::dealloc(p);
    *p = 1;              // compile-time error: use of 'p' (&heap reference) after std::dealloc() freed it
    std::dealloc(p);      // compile-time error: double-free, same check
}
```

::: warning Sound up to stated trust boundaries
Both the arena and heap sides of this (see [Arena References Die on Reset](/reference/memory#_4-arena-references-die-on-reset)
and [Compile-Time Use-After-Free and Double-Free Checking](/reference/memory#compile-time-use-after-free-and-double-free-checking))
are one dataflow analysis, run over every function body once type checking is
done. It tracks each allocation as an abstract object and each pointer as the
set of objects it may point to, with its own "may be dangling" mark, through
every copy (any number of hops, and through branches that assign different
values), struct field, and array element: a constant index is its own
element, and a store through an unknown index may reach any element. Control
flow is followed exactly: if/else, `switch` fallthrough, `match`, `?:` and
`&&`/`||` (a free inside one operand only affects the outcomes where that
operand ran), `break`/`continue`/`goto`/`return`/`try`, and `defer`/`errdefer`
running at each return. Loops, and functions with labels, are iterated to a
fixpoint, so a reset or free reaches code before it in the loop body on the
next iteration, and code after the loop only through the exits it can take.
`arena_free_to<R>(m)` invalidates exactly the references allocated after mark
`m`: every mark is tracked separately, so nested marks are precise. Only a
mark the checker cannot identify (one passed in from the caller and not
resolvable, or a value that may be one of several marks) invalidates more
than that, and then conservatively.

Calls are checked through summaries. For every function with a body (generic
instances and methods included), the checker computes what it may free,
which regions it may reset or `free_to` (including through a mark it was
passed), which pointers it reads from its arguments' memory, and what it
leaves in its parameters' and globals' memory and returns, all in terms of
its parameters. Summaries are computed callees first, iterating to a
fixpoint for recursive functions. A callee that frees its parameter, frees
or replaces a field, returns its parameter, or reads memory the caller has
already freed is checked at the call site, which reports
`call to 'f' reads a &heap reference after std::dealloc() freed it` (or
its `&arena<R>` counterpart) or `call to 'f' frees memory after std::dealloc() freed it` when the callee
itself is fine but the arguments are not.

Within this model the check is sound: a genuine use-after-reset,
use-after-free_to, use-after-free or double-free is never missed. The
following are trusted, not checked:

- Calls through function pointers or `fn` values, the thread function a
  `spawn` starts, and functions with no body (`extern` declarations, C
  imports). They are assumed to free and reset nothing, to keep no pointer to
  their arguments, and to return fresh memory. That includes
  `std::realloc_buf()`, which frees its argument but is implemented by an
  extern.
- Memory reached through a raw pointer, or through pointer indexing on a
  reference, inside `unsafe {}`. That covers the buffers of `std::Vec` and the
  other std containers. Pointers stored there are not tracked, and every
  pointer read back from there counts as live.
- Inside a function, distinct parameters (and globals) are assumed not to
  point to the same allocation unless the function itself makes them do so.

Two precision limits remain, and both report code that is actually fine.
First, the analysis follows control flow but not the values that decide it,
apart from the `&&`/`||`/`!` structure of a condition: a free guarded by
`if (owned)` followed by a use guarded by `if (!owned)` is still reported.
Second, entry memory more than three pointer hops from a parameter, and the
elements behind an unknown index, are each summarized as one object. So a
loop that walks a linked structure through safe references and frees each
node as it goes cannot be shown not to revisit a freed node, and is reported.
Write it recursively, or walk it through raw pointers in `unsafe {}` as the
std containers do. `unsafe {}` remains the escape hatch: no reports are made
inside it, though its frees and resets still count for the code that
follows.
:::

### 3. Aliasing Safety {#_3-aliasing-safety}

Mutable references are exclusive. The borrow checker enforces that at any point in the program, a value has either one mutable reference or any number of immutable references, but never both simultaneously.

```c
int x = 10;
&stack int a = &x;          // mutable borrow
&stack const int b = &x;    // error: cannot borrow 'x' as immutable: already borrowed as mutable
```

`const &stack int b` is the same type as `&stack const int b`: `const` may go on either side of the region qualifier.

### 4. Region Escape Safety {#_4-region-escape-safety}

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

### 5. Data Race Freedom {#_5-data-race-freedom}

Every type is Send and/or Sync, derived from its structure (overridable with `unsafe impl Send;` / `impl !Send;`). `spawn` accepts only static data a thread may share, `spawn_scoped` lends a local until it is joined, `thread_spawn` and channels move Send values, and `Mutex<T>` reaches its value only through a guard — so safe code can't share mutable state across threads without synchronization. See [Thread Safety](/reference/threads).

### 6. Null Safety {#_6-null-safety}

References are non-null by default. There is no null reference in safe SafeC code. Nullable references use the `?&T` syntax; unlike languages with flow-sensitive null narrowing, a bare `!= null` comparison does not by itself make a later dereference safe — reading a nullable reference is only sanctioned through `match`, `.is_null()`, `.default(fallback)`, or an explicit `unsafe` block (see [Safety](/reference/safety#nullability-enforcement)).

### 7. Determinism {#_7-determinism}

No hidden runtime costs. SafeC does not insert garbage collection, reference counting, or implicit heap allocations. Every allocation is explicit in the source code. The performance model is transparent — what you write is what runs.

## Memory Safety Compared to C, C++, Rust, and Zig {#memory-safety-compared-to-c-c-rust-and-zig}

[Comparison](/guide/comparison) has a one-line "Memory Safety: Yes/No" summary across the same five languages; this breaks that single row out into the properties above, since "yes" and "no" hide real differences in *when* each language checks (compile time vs. runtime vs. not at all) and *how completely* (every safe-mode program vs. an opt-in build mode vs. nothing).

| Property | C | C++ | Rust | Zig | SafeC |
|---|---|---|---|---|---|
| Spatial safety (buffer overflow) | None — `[]` has no check, OOB is UB | None for raw arrays/`[]`; opt-in via `.at()`/`std::span` (throws) | Compile-time for const indices, runtime panic otherwise — always on in safe code | Runtime check in Debug/ReleaseSafe; **compiled out** (UB) in ReleaseFast/ReleaseSmall | Compile-time for const indices, runtime check otherwise — always on outside `unsafe` |
| Temporal safety (use-after-free) | None — manual `free()`, no tracking | None for raw pointers/refs; RAII (`unique_ptr`/`shared_ptr`) helps only if used consistently, not enforced | Compile-time — the borrow checker rejects any use after move/drop; sound | None from the compiler; `GeneralPurposeAllocator` catches some in Debug builds only, at runtime | Compile-time, region-based (stack/arena/heap) — a dataflow analysis, sound up to [stated trust boundaries](#_2-temporal-safety); `unsafe {}` opts out |
| Double-free | None — UB | Same as C for raw `delete`; smart pointers avoid it structurally unless defeated by raw aliasing | Compile-time — only one owner can ever call drop | Runtime-only, opt-in (`GeneralPurposeAllocator` in Debug) | Compile-time (same check as above), plus a runtime tagged-header guard in `std::alloc`/`std::dealloc` as defense in depth |
| Uninitialized reads | None — UB | None — UB | Compile-time — definite-assignment analysis | `undefined` is explicit and typed; Debug builds poison it (`0xaa`) to make bugs *visible*, not prevent them | Compile-time — definite-initialization check |
| Null-pointer dereference | None — raw pointers, untracked | None for raw pointers; references can't be null by construction but can be formed from a deref'd null pointer (UB), uncaught | Compile-time — no null in safe code, `Option<T>` must be matched/unwrapped | Compile-time — optionals (`?T`) must be explicitly unwrapped | Compile-time — `?&T` requires `match`/`.is_null()`/`.default()`/`unsafe` to read (see [Null Safety](#_6-null-safety)) |
| Data races | None — no language-level concurrency safety | None — races are UB; `atomic`/mutexes are opt-in tools, not enforced | Compile-time — `Send`/`Sync` traits enforce shared-state safety in safe code | None — no type-system enforcement, manual synchronization only | Compile-time — derived `Send`/`Sync`, checked by `spawn`, `spawn_scoped`, channels and std's thread types; sharing mutable state requires synchronization |
| Enforcement point | N/A | Mostly none; a few opt-in runtime checks | Compile-time first (borrow checker), runtime for the genuinely dynamic parts (bounds, panics) | Runtime, and only in safety-checked build modes — the *same binary* can be compiled with checks off | Compile-time first, runtime only where the check is inherently dynamic (index bounds) — always on outside `unsafe` |
| Escape hatch | N/A — the whole language is this way | N/A — the whole language is this way | `unsafe {}` blocks/functions | No lexical escape hatch — safety is a whole-build-mode setting, not a per-block opt-out | `unsafe {}` blocks — lexically scoped, non-propagating (see below) |

The throughline: C and C++ don't check most of these at all (undefined behavior is the "checked" state most of the time). Zig checks several of them, but as a *build-mode* choice — the same source either has the checks or doesn't, decided once for the whole binary, not per-line. Rust and SafeC both check at compile time by default and require an explicit, lexically-scoped opt-out (`unsafe {}`) to turn a check off for a specific block of code — that's the property this page's own [`unsafe` escape hatch](#the-unsafe-escape-hatch) section is describing. Where Rust and SafeC differ is *how* they prove temporal safety (ownership/borrowing vs. region membership — see [Compared to Rust](/guide/comparison#compared-to-rust) for that trade-off in detail) and how complete the proof is: Rust's borrow checker is a sound, complete static proof for the patterns it accepts (it may reject valid programs, but never accepts an unsound one); SafeC's region and lifetime checks are sound within stated trust boundaries (calls through function pointers and to bodiless `extern` functions, memory reached through raw pointers in `unsafe {}`) and may still reject a few valid shapes (a free and a use guarded by complementary conditions, a loop that frees a linked structure node by node — see [Temporal Safety](#_2-temporal-safety)), trading some precision for a simpler, more C-compatible mental model.

## Safety Analysis Phases {#safety-analysis-phases}

The compiler enforces these properties through a series of analysis passes during semantic analysis:

| Phase | What it checks |
|-------|---------------|
| Definite initialization | Every variable is assigned before use |
| Region escape analysis | Stack/static/cross-region escapes are rejected |
| Heap/arena lifetime analysis | Use after `std::dealloc()`, double free, and use after `arena_reset`/`arena_destroy`/`arena_free_to`, checked by a dataflow analysis over every function body once type checking is done, with per-function call summaries (see above) |
| Alias/borrow checking (NLL) | Mutable exclusivity is maintained |
| Nullability enforcement | Dereferencing a nullable reference outside `match`/`.is_null()`/`.default()`/`unsafe` is rejected |
| Bounds checking | Array accesses are within bounds |

All checks run at compile time. The only runtime insertion is bounds checks for dynamic array indices, and those are visible in the generated IR.

## The `unsafe` Escape Hatch {#the-unsafe-escape-hatch}

All safety checks can be locally suppressed inside `unsafe {}` blocks:

```c
unsafe {
    int* raw = (int*)some_addr;
    *raw = 42;       // no bounds check, no borrow check, no region check
}
```

`unsafe` is lexically scoped. It does not propagate to called functions. It serves as a grep-able marker for code that requires manual auditing.

## FFI Boundary Rules {#ffi-boundary-rules}

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

## Formal Foundations {#formal-foundations}

The safety model is grounded in established type theory:

- **Oxide** (Weiss et al., 2019) — formalization of Rust's ownership and borrowing as a typed calculus with places and provenance
- **Cyclone** (Grossman et al., 2002) — region-based memory management with static safety guarantees for a C-like language

SafeC adapts these formalisms to a C-compatible language with regions as the primary memory safety mechanism.

### Progress and Preservation {#progress-and-preservation}

The type system satisfies the standard syntactic safety properties:

- **Progress**: A well-typed program either is a value, can take a step, or is a sanctioned runtime error (bounds check failure with abort)
- **Preservation**: If a well-typed program takes a step, the resulting program is also well-typed

Together, these properties guarantee that well-typed SafeC programs do not exhibit undefined behavior outside of `unsafe {}` blocks.
