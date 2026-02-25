# Formal Safety Model

SafeC's safety guarantees are not aspirational — they are formalized as a type system with syntactic type safety (progress + preservation). The formal model is documented in `SAFETY.md` and draws on prior work from Oxide (Weiss et al.) and Cyclone (Grossman et al.).

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

No use-after-free. Region annotations tie reference lifetimes to allocation scopes. A reference to stack memory cannot escape the function. A reference to arena memory cannot outlive the arena.

```c
&stack int get_local() {
    int x = 42;
    return &x;      // compile-time error: stack reference escapes function
}
```

### 3. Aliasing Safety

Mutable references are exclusive. The borrow checker enforces that at any point in the program, a value has either one mutable reference or any number of immutable references, but never both simultaneously.

```c
int x = 10;
&stack int a = &x;          // mutable borrow
const &stack int b = &x;    // error: cannot take immutable reference while mutable reference exists
```

### 4. Region Escape Safety

References cannot outlive the region they point into. The compiler tracks region scope depth and rejects any assignment or return that would move a reference to a shallower scope.

```c
arena<R> {
    &arena<R> int p = new<R> int;
    outer_ptr = p;   // error: arena reference escapes region scope
}
```

### 5. Data Race Freedom

Threads spawned with `spawn()` operate on isolated data. The type system prevents sharing mutable state across thread boundaries without explicit synchronization.

### 6. Null Safety

References are non-null by default. There is no null reference in safe SafeC code. Nullable references use the `?&T` syntax and require an explicit null check before dereferencing.

### 7. Determinism

No hidden runtime costs. SafeC does not insert garbage collection, reference counting, or implicit heap allocations. Every allocation is explicit in the source code. The performance model is transparent — what you write is what runs.

## Safety Analysis Phases

The compiler enforces these properties through a series of analysis passes during semantic analysis:

| Phase | What it checks |
|-------|---------------|
| Definite initialization | Every variable is assigned before use |
| Region escape analysis | References do not outlive their target region |
| Alias/borrow checking (NLL) | Mutable exclusivity is maintained |
| Nullability enforcement | Nullable references are checked before use |
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

SafeC adapts these formalisms to a C-compatible language with regions as the primary memory safety mechanism. Machine-checkable proofs in Lean 4 are maintained in the `proofs/` directory of the repository.

### Progress and Preservation

The type system satisfies the standard syntactic safety properties:

- **Progress**: A well-typed program either is a value, can take a step, or is a sanctioned runtime error (bounds check failure with abort)
- **Preservation**: If a well-typed program takes a step, the resulting program is also well-typed

Together, these properties guarantee that well-typed SafeC programs do not exhibit undefined behavior outside of `unsafe {}` blocks.
