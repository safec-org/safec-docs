# Formal Verification

SafeC's safety guarantees are backed by machine-checkable proofs written in Lean 4. The formalization lives in the `proofs/` directory of the SafeC repository and establishes that the type system satisfies syntactic type safety.

## What Is Formalized

The Lean 4 proofs cover the core SafeC type system — a formal calculus called **SafeCCore** — and establish the following:

### Progress

A well-typed program either:
- Is already a value (evaluation is complete), or
- Can take an evaluation step, or
- Is a sanctioned runtime error (bounds check failure with deterministic abort)

There are no stuck states. A well-typed SafeC program will never reach a point where it cannot proceed and is not a value.

### Preservation

If a well-typed program takes an evaluation step, the resulting program is also well-typed. Types are maintained through all evaluation steps — the type system is sound.

Together, progress and preservation guarantee that **well-typed SafeC programs do not exhibit undefined behavior** outside of `unsafe {}` blocks.

## Safety Properties Proven

The formalization covers SafeC's seven safety properties:

| Property | What It Guarantees |
|----------|--------------------|
| Spatial safety | No out-of-bounds memory access |
| Temporal safety | No use-after-free or dangling references |
| Aliasing safety | Mutable references are exclusive |
| Region escape safety | References do not outlive their region |
| Data race freedom | Threads cannot share mutable state without synchronization |
| Null safety | Non-null references are never null |
| Determinism | No hidden allocations, GC, or runtime costs |

## Formal Foundations

The SafeCCore calculus draws on two established formalisms:

### Oxide (Weiss et al., 2019)

A formal calculus that models Rust's ownership and borrowing system. SafeC adapts Oxide's treatment of places, provenance, and borrow tracking to its region-based model. Key contributions from Oxide:

- **Non-lexical lifetimes (NLL)**: borrows end at last use, not at scope exit
- **Place expressions**: formal treatment of lvalues and memory locations
- **Provenance tracking**: compile-time tracking of where references originate

### Cyclone (Grossman et al., 2002)

A region-based memory-safe dialect of C with static safety guarantees. SafeC borrows Cyclone's core insight that regions can be used as a compile-time-only mechanism for lifetime tracking. Key contributions from Cyclone:

- **Region-qualified pointers**: references carry region annotations
- **Region subtyping**: `&static T` is a subtype of any other region reference (longer lifetime subsumes shorter)
- **Existential regions**: dynamically-scoped regions with static safety

### Wright & Felleisen Framework

The overall proof strategy follows the standard syntactic type safety approach of Wright and Felleisen (1994):

1. Define a typed operational semantics
2. Prove progress: well-typed states can always step
3. Prove preservation: stepping preserves well-typedness
4. Conclude type safety as a corollary

## Proof Structure

The Lean 4 formalization is organized in `proofs/SafeCCore.lean`:

```
SafeCCore.lean
├── Type definitions       (primitive types, region qualifiers, reference types)
├── Expression syntax       (the SafeCCore expression language)
├── Typing rules           (bidirectional type checking judgments)
├── Operational semantics  (small-step reduction rules)
├── Progress theorem       (well-typed → value ∨ steps ∨ sanctioned error)
└── Preservation theorem   (well-typed + step → well-typed)
```

## What Is Not Formalized

The proofs cover the core type system, not the full compiler:

- **Preprocessor**: text-level transformations are not modeled
- **Generics monomorphization**: proofs assume all generics are already instantiated
- **LLVM codegen**: the proof is about the source language semantics, not the generated IR
- **Standard library correctness**: stdlib functions are not individually proven
- **Const-eval engine**: compile-time evaluation is trusted

The formalization establishes that the type system design is sound. The compiler implementation is a separate trust boundary — the proofs guarantee that if the compiler correctly implements the type rules, the safety properties hold.

## Checking the Proofs

To verify the proofs locally, install Lean 4 and run:

```bash
cd proofs
lake build
```

A successful build with no errors confirms that all theorems type-check.

## Relationship to `unsafe`

The formal safety guarantees apply to **safe SafeC code only**. Code inside `unsafe {}` blocks is outside the scope of the proofs. The `unsafe` keyword is a deliberate escape hatch — it marks code that the programmer takes responsibility for.

The proofs guarantee that if you write SafeC code without `unsafe`, the seven safety properties hold. This is analogous to Rust's safety story: safe code is proven sound, unsafe code requires manual auditing.

## References

- Weiss, A., Patterson, D., Matsakis, N., & Ahmed, A. (2019). *Oxide: The Essence of Rust*. arXiv:1903.00982.
- Grossman, D., Morrisett, G., Jim, T., Hicks, M., Wang, Y., & Cheney, J. (2002). *Region-Based Memory Management in Cyclone*. PLDI 2002.
- Wright, A. K., & Felleisen, M. (1994). *A Syntactic Approach to Type Soundness*. Information and Computation, 115(1).
