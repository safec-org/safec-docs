# Functional Programming

SafeC borrows a handful of specific, individually-useful ideas from functional programming — purity you can verify by reading a keyword instead of the whole call graph, sum types with compiler-enforced exhaustive matching, immutable-by-default bindings, functions as ordinary values — without taking on a garbage collector, closures, or a runtime. Every mechanism on this page already exists for a reason unrelated to "being functional" (mostly determinism and compile-time verification, SafeC's actual design priorities — see [Design Philosophy](/guide/design)); this page collects them under the FP lens because that's a genuinely useful way to see what they add up to.

Read [What SafeC Doesn't Have](#what-safec-doesnt-have) before assuming this is Haskell-with-C-syntax — it isn't, and several FP staples are deliberately absent.

## Functions as Values

A function can be referenced, stored, passed, and returned as an ordinary value via the `fn` type — `fn ReturnType(ParamTypes)`:

```c
int add(int a, int b) { return a + b; }

fn int(int, int) op = add;
int result = op(3, 4);        // 7
```

### Higher-Order Functions

Functions that take or return other functions work exactly like any other parameter/return type — no special syntax beyond the `fn` type itself:

```c
generic<T>
void map_inplace(T* arr, int n, fn T(T) f) {
    int i = 0;
    while (i < n) {
        unsafe { arr[i] = f(arr[i]); }
        i = i + 1;
    }
}

generic<T>
T fold(T* arr, int n, T init, fn T(T, T) f) {
    T acc = init;
    int i = 0;
    while (i < n) {
        T v;
        unsafe { v = arr[i]; }
        acc = f(acc, v);
        i = i + 1;
    }
    return acc;
}

pure int square(int x) { return x * x; }
pure int add(int a, int b) { return a + b; }

int main() {
    int nums[5] = {1, 2, 3, 4, 5};
    int* p;
    unsafe { p = (int*)&nums[0]; }

    map_inplace(p, 5, square);              // nums -> 1 4 9 16 25
    int total = fold(p, 5, 0, add);          // 55
    printf("sum of squares = %d\n", total);
    return 0;
}
```

(Verified: compiles and runs, prints `1 4 9 16 25` and `sum of squares = 55`.) SafeC has no built-in `map`/`filter`/`fold` over arrays or slices — `map_inplace`/`fold` above are hand-rolled, the same way you'd write them in C. Writing your own generic higher-order helpers, once, is the idiomatic way to get this shape of code; there's no standard-library combinator set to reach for instead.

::: warning Generic type inference and pointers
Generic type inference works for a `T` that appears as a plain parameter (`T a, T b` above infers `T` from `max(3, 7)` directly) or as `T*`/`[]T` **only when the argument is already pointer/slice-typed** — passing a fixed-size array argument directly (`int nums[5]`) to a `T*` parameter does not infer `T`; obtain an explicit `int*` first (`unsafe { p = (int*)&nums[0]; }`, as above) and pass that. This is a real, verified inference limitation, not a style preference.
:::

See [Functions](/reference/functions#function-pointers) for the base `fn` type syntax.

## Purity

A `pure` function is a compiler-checked promise of no side effects — not just documentation, it's enforced and exploited: SafeC lowers `pure` functions with LLVM's `readonly`/`nounwind` attributes, unlocking optimizations the compiler can't safely make otherwise (reordering, common-subexpression elimination across calls, etc. — the same category of win Haskell gets from purity by default, opted into per-function here instead):

```c
pure double square(double x) {
    return x * x;
}
```

Pure functions may not modify global state, perform I/O, or call a non-`pure` function — violations are compile errors, not conventions a reviewer has to catch. See [Functions](/reference/functions#pure-functions).

## Compile-Time Evaluation

Three related mechanisms move computation from runtime to compile time — SafeC's answer to the "pure functions can be evaluated whenever" idea FP languages get from referential transparency, made explicit rather than left to an optimizer's discretion:

- **`const` functions** *may* run at compile time, if every argument is a compile-time constant; called normally otherwise.
- **`consteval` functions** *must* run at compile time — passing a non-constant argument is a compile error, not a fallback to runtime.
- **`if const`** branches on a compile-time condition; the untaken branch is dropped entirely and never even type-checked — SafeC's replacement for `#ifdef`-based conditional compilation, scoping-aware unlike the preprocessor.

```c
const int factorial(int n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}
const int val = factorial(5);   // evaluated at compile time -> 120

consteval int table_size() { return 256; }
int lookup[table_size()];        // OK: compile-time constant required here

const int PLATFORM = 1;
void init() {
    if const (PLATFORM == 1) { init_linux(); }
    else { init_generic(); }
}
```

See [Functions](/reference/functions#const-functions), [Functions](/reference/functions#consteval-functions), and [Control Flow](/reference/control-flow#if-const-compile-time-branching).

## Immutability

`const` on a local binding is FP's default-immutable stance, opted into per-variable rather than assumed globally — "declares an immutable binding; the value cannot be modified after initialization," checked at compile time:

```c
const int MAX_SIZE = 1024;
// MAX_SIZE = 2048;   // ERROR: cannot assign to const
```

See [Literals & Qualifiers](/reference/literals#const).

This pairs with SafeC's borrow-checker-style aliasing rule — **one mutable reference, or any number of immutable references, never both at once** — which gives immutable data the same "nothing else can be quietly mutating this out from under you" guarantee FP languages get from immutability being the only option, without requiring everything to actually be immutable:

```c
int x = 42;
&stack const int a = &x;   // immutable borrow
&stack const int b = &x;   // OK: multiple immutable borrows coexist
```

See [Safety](/reference/safety#aliasing-rules-borrow-checker).

## Algebraic Data Types

Sum types (tagged `union`) and product types (`tuple`), read back exhaustively via `match` — the type-plus-pattern-match style familiar from Rust's `enum`/`match` or Haskell's ADTs/case expressions:

```c
union Result {
    int ok;
    int err;
}

int unwrap_or(union Result r, int fallback) {
    return match (r) {
        case .ok(v):  v,
        case .err(_): fallback,
    };
}

tuple(int, double) pair = (42, 3.14);
int first = pair.0;
double second = pair.1;
```

A `match` **expression** (as opposed to a `match` statement) must be provably exhaustive — every variant covered, or a `default`/wildcard arm — a compile error otherwise, not a runtime fallthrough. `union` and `tuple` are both generic: `generic<T, E> union Result { T ok; E err; }` is a real, monomorphized sum type over an arbitrary pair of types. See [Types](/reference/types#union-types), [Types](/reference/types#tuple-types), and [Control Flow](/reference/control-flow#match-statement).

## Optional Values and Propagation

`?T` is SafeC's Option-shaped type — "may or may not be present," lowered to a `{T, i1}` pair, read back through the same exhaustive-`match` discipline (`none`/`some(v)` arms) or a small fixed set of explicit accessors:

```c
?int safe_div(int a, int b) {
    if (b == 0) { return null; }   // the empty case
    return a / b;                  // implicit T -> ?T wrap
}

int main() {
    int r1 = match (safe_div(10, 2)) {
        case none:    -1,
        case some(v): v,
    };
    int r2 = match (safe_div(10, 0)) {
        case none:    -1,
        case some(v): v,
    };
    printf("10/2=%d 10/0=%d\n", r1, r2);   // 10/2=5 10/0=-1
    return 0;
}
```

(Verified: compiles and runs, prints `10/2=5 10/0=-1`.) `try` unwraps a `?T` and propagates the empty case to the *caller* immediately on failure — short-circuiting error propagation in the same spirit as Rust's `?` operator or a `Maybe`/`Either` monad's bind, without requiring the containing function's own return type machinery to be spelled out at each call site:

```c
?int parse_config(const char *path) {
    ?int fd = open_file(path);
    int file = try fd;          // if fd is empty, return null immediately
    ?int value = read_int(file);
    return try value;
}
```

What's **not** here: no `.map()`/`.and_then()`/monadic-combinator methods on `?T` — only `is_none()`, `.default(fallback)`, `match`, and (inside `unsafe`) direct unwrapping. Chaining a sequence of fallible operations means writing out `try` at each step, not composing combinators. See [Types](/reference/types#optional-types) and [Control Flow](/reference/control-flow#try-operator).

## Parametric Polymorphism

Generic functions (`generic<T>`) are SafeC's parametric-polymorphism mechanism — the same core idea as Haskell's `a -> a` type variables or ML's `'a`, fully monomorphized at compile time rather than represented uniformly at runtime. Covered in full, including trait-constrained generics and variadic packs, on [Polymorphism & OOP](/reference/polymorphism#parametric-polymorphism-generics) and [Generics](/reference/generics) — not duplicated here since it's exactly the same feature from either angle, just a different lens on why it's useful.

## What SafeC Doesn't Have {#what-safec-doesnt-have}

- **No closures or lambdas.** `fn` values are plain function pointers with no captured environment — "a bare `HttpHandler` function pointer has no closure slot" ([stdlib/http](/stdlib/http)). [Concurrency](/reference/concurrency) states this directly: "there are no closures or runtime schedulers — concurrency is explicit and deterministic." Anywhere a closure would capture state, pass that state explicitly as an extra argument instead.
- **No currying or partial application.** Every function call supplies every argument; there's no built-in mechanism to fix some arguments and get back a callable for the rest.
- **No built-in `map`/`filter`/`reduce` over collections.** Write a `generic<T>` helper once (as `map_inplace`/`fold` above), the same way you would in plain C.
- **No lazy evaluation.** All evaluation is eager, matching C — no infinite lists, no lazily-computed thunks.
- **No monadic combinators beyond `match`/`try`/`.default()`.** `?T` behaves like an Option type structurally, but doesn't expose `.map()`/`.and_then()`/`.or_else()` — see [Optional Values and Propagation](#optional-values-and-propagation).
- **Pattern matching is exhaustive-checked, but only for `match` expressions, not statements.** A `match` *statement* with no `default`/wildcard arm only warns ("may not be exhaustive"), it doesn't error — see [Control Flow](/reference/control-flow#match-statement).
