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

No use-after-free: region annotations tie reference lifetimes to allocation scopes. A reference to stack memory cannot escape the function, and a reference into an arena is invalidated by `arena_reset<R>()`/`arena_destroy<R>()`/`arena_free_to<R>()`.

```c
&stack int get_local() {
    int x = 42;
    return &x;      // compile-time error: stack reference escapes function
}
```

::: warning Arena use-after-reset checking is flow-insensitive
The arena side of this (see [Memory & Regions](/reference/memory#4-arena-references-die-on-reset))
is a generation-counter check over AST traversal order, not a real
control-flow simulation — sound (a genuine use-after-reset is never
missed) but it can over-flag references that are actually still valid,
most notably: `arena_free_to<R>()`'s partial free currently invalidates
*every* reference into `R`, not just the ones allocated after its
checkpoint. `unsafe {}` remains the escape hatch for cases the checker
can't (yet) prove safe on its own.
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

## Safety Analysis Phases

The compiler enforces these properties through a series of analysis passes during semantic analysis:

| Phase | What it checks |
|-------|---------------|
| Definite initialization | Every variable is assigned before use |
| Region escape analysis | Stack/static/cross-region escapes are rejected; arena-reset invalidation is checked flow-insensitively (see above) |
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
- Non-static region references passed to C require `unsafe {}`
- Raw pointers received from C must be handled inside `unsafe {}`

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
