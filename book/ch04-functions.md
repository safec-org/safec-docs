# Chapter 4: Functions

## Declaring functions

Functions look exactly like C:

```c
int add(int a, int b) {
    return a + b;
}
```

Parameters and return types work the same way you already expect — pass
by value for primitives, `void` for no return value, and so on. Nothing
new here yet; the interesting parts of SafeC's function story are a
handful of attributes C doesn't have, and — starting in the next chapter
— what happens once parameters and return values become *references*
rather than plain values.

## `pure` functions

`pure` documents (and enforces) that a function has no side effects — no
writes to global state, no I/O, no calls to non-pure functions. The
compiler takes advantage of that guarantee for optimization (lowering it
with LLVM's `readonly`/`nounwind` attributes), and enforces it at compile
time rather than just trusting the annotation:

```c
pure double square(double x) {
    return x * x;
}
```

## `must_use`

A function marked `must_use` gets a compiler warning if its caller
discards the return value — useful for functions where ignoring the
result is almost always a bug (a parse result, an allocation, an error
code):

```c
must_use int compute(int x) {
    return x * x + 1;
}

compute(5);          // warning: return value of 'compute' should not be
                      // ignored (marked must_use) -- ignored, not an error
int result = compute(5);   // fine, no warning
```

## `noreturn`

Functions that never return — because they always call `exit`/`abort`, or
loop forever — can say so explicitly, letting the compiler optimize call
sites (no need to generate code for "what happens after this call
returns," because nothing does):

```c
#include <stdlib.h>

noreturn void die(const char* msg) {
    printf("PANIC: %s\n", msg);
    abort();
}
```

## Function values and higher-order functions

A function can be passed around as a value using the `fn <ReturnType>(<ParamTypes>)` type syntax:

```c
fn int(int, int) opRef = add;
int result = opRef(3, 4);        // 7 -- calls add(3, 4) through the reference

int apply(fn int(int, int) op, int a, int b) {
    return op(a, b);
}

apply(add, 10, 20);              // 30
```

This is the mechanism behind every callback-taking API in the standard
library — a thread's entry point (`std::spawn`), a test framework's
registered test cases, an HTTP server's request handler all take a
plain function value shaped this way.

## A first taste of generics

One more thing worth knowing before you need it: functions can be
generic over a type parameter, written and type-checked once, then
compiled to a separate, fully concrete version for each type it's
actually called with (monomorphization, the same strategy Rust and C++
templates use):

```c
generic<T>
T my_max(T a, T b) {
    if (a > b) { return a; }
    return b;
}

my_max(3, 7);         // 7    -- instantiates my_max<int>
my_max(1.5, 2.7);      // 2.7  -- instantiates my_max<double>
```

This chapter won't go deeper than that one example — generics interact
with structs, traits/constraints, and variadic packs in ways that deserve
their own focused read once you need them. See
[Generics](/reference/generics) when you get there.

## `const` and `consteval`: functions that run at compile time

Two more function qualifiers, both about *when* a function's body
actually executes. A `const` function *may* run at compile time, if every
argument at a given call site is itself a compile-time constant — and
falls back to an ordinary runtime call otherwise:

```c
const int factorial(int n) {
    if (n <= 1) { return 1; }
    return n * factorial(n - 1);
}

const int val = factorial(5);   // evaluated at compile time -> 120
int dynamic = factorial(x);     // x isn't a constant -> ordinary runtime call
```

`consteval` is the stricter sibling: it *must* run at compile time, and
calling it with a non-constant argument is a compile error rather than a
silent fallback to runtime — useful for things like a lookup table size
that genuinely can't be computed at runtime (an array's size needs to be
known before the array exists):

```c
consteval int table_size() { return 256; }
int lookup[table_size()];       // OK: compile-time constant
```

Next: [Understanding Regions](/book/ch05-understanding-regions) — the
chapter this whole book has been building toward. Everything so far has
been "C, with a few extra keywords." This is where SafeC stops being
that.
