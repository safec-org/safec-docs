# Safety

SafeC enforces memory safety at compile time through several interlocking analyses. These checks catch common C bugs -- use-after-free, buffer overflows, null dereferences, aliasing violations -- before the program ever runs.

## Definite Initialization

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

## Region Escape Analysis

The compiler tracks the region of every reference and ensures it cannot outlive its region:

### Stack References Cannot Escape

```c
&stack int bad() {
    int local = 10;
    return &local;             // ERROR: stack reference escapes function
}
```

### References Cannot Escape to Longer-Lived Storage

```c
static &stack int global_ref;

void bad() {
    int x = 5;
    global_ref = &x;          // ERROR: stack ref assigned to static storage
}
```

### Arena References Die on Reset — Not Currently Enforced

Conceptually, resetting an arena invalidates every outstanding reference
into it. In practice, the compiler does not check this: the following
compiles with no error or warning, even though `p` points at memory that
`arena_reset` has logically freed and a later `new<Pool>` may reuse.

```c
region Pool { capacity: 1024 }

int main() {
    &arena<Pool> int p = new<Pool> int;
    arena_reset<Pool>();
    *p = 42;                   // compiles — NOT actually caught
    return 0;
}
```

See [Memory & Regions](/reference/memory#4-arena-references-die-on-reset-conceptually-not-yet-enforced)
for more detail. Avoid touching a reference after resetting or destroying
its arena; the compiler currently won't catch the mistake for you.

### Scope Depth Tracking

The compiler assigns a `declScopeDepth` to each region and reference. An inner reference cannot be stored in an outer scope:

```c
&stack int ref;
{
    int inner = 10;
    ref = &inner;              // ERROR: inner scope ref escaping to outer
}
```

## Aliasing Rules (Borrow Checker)

SafeC enforces the principle: **either one mutable reference, or any number of immutable references, but never both simultaneously.**

### Single Mutable Reference

```c
int x = 42;
&stack int a = &x;             // mutable borrow
&stack int b = &x;             // ERROR: x is already mutably borrowed
```

### Multiple Immutable References

```c
int x = 42;
&stack const int a = &x;       // immutable borrow
&stack const int b = &x;       // OK: multiple immutable borrows allowed
```

::: warning `const` must come after the region qualifier
Writing `const` *before* the region (`const &stack int a`) is accepted by
the parser but silently produces a **mutable** borrow instead of an
immutable one — a parser bug (the `const` token is consumed before the
region qualifier is peeked). This makes two `const &stack int` borrows of
the same variable fail with "already borrowed as mutable," which looks like
a compiler bug in the borrow checker but is actually the mutable
misparse upstream of it. Always write `const` *after* the region qualifier
(`&stack const int`, as above) to get the immutable borrow you intended.
:::

### Mutable + Immutable Conflict

```c
int x = 42;
&stack const int r = &x;       // immutable borrow
&stack int w = &x;             // ERROR: cannot mutably borrow while
                               //        immutable borrow exists
```

### Scope-Based Tracking

Borrows are tracked per scope. When a scope exits, its borrows are released:

```c
int x = 42;
{
    &stack int a = &x;         // mutable borrow in inner scope
}                              // borrow released here
&stack int b = &x;             // OK: no conflicting borrow
```

## Nullability Enforcement

Plain references are non-null by default. Nullable references must use the `?&` prefix, and — unlike languages with flow-sensitive null narrowing — a bare `!= null` comparison does **not** make a later dereference safe: the compiler performs no flow analysis linking the check to the access. Reading a nullable reference is only sanctioned through `match`, `.is_null()`, `.default(fallback)`, or an explicit `unsafe` block:

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

## Bounds Safety

The compiler inserts bounds checks for array and slice accesses.

### Static Bounds Checking

When the index is a compile-time constant, the check happens at compile time:

```c
int arr[5];
int x = arr[10];               // ERROR: index 10 out of bounds for
                               //        array of size 5
```

### Runtime Bounds Checking

When the index is dynamic, the compiler inserts a runtime check that aborts the program on out-of-bounds access:

```c
int arr[5];
int i = get_index();
int x = arr[i];                // runtime check: if (i >= 5) abort();
```

### Slice Bounds Checking

Slice accesses are always bounds-checked against the slice length:

```c
[]int s = arr[0..3];
int x = s[i];                  // runtime check: if (i >= s.len) abort();
```

## The Unsafe Boundary

When the safety rules are too restrictive, `unsafe {}` blocks allow bypassing them. Inside an `unsafe` block:

- Bounds checks are suppressed (the `boundsCheckOmit` flag is set)
- Region escape analysis is relaxed
- Aliasing rules are not enforced
- Raw pointer arithmetic is allowed
- Null dereferences are not prevented

```c
unsafe {
    int *raw = (int*)malloc(10UL * sizeof(int));
    raw[0] = 42;               // no bounds check
    int *alias = raw;          // aliasing allowed
    free(raw);
}
```

### Unsafe Rules

1. **Unsafe blocks are explicit** -- the programmer opts in to danger
2. **Unsafe does not propagate** -- calling a safe function from unsafe context does not make the callee unsafe
3. **Minimize unsafe surface area** -- keep unsafe blocks as small as possible

### Common Unsafe Uses

- FFI calls with non-static region references
- Raw pointer manipulation from C APIs
- Performance-critical inner loops where bounds checks are measurably costly
- Hardware register access in bare-metal code

## Summary of Safety Guarantees

| Safety Check | Compile-Time | Runtime | Suppressed by `unsafe` |
|-------------|-------------|---------|----------------------|
| Definite initialization | Yes | -- | No |
| Region escape analysis (stack/static/cross-region) | Yes | -- | Yes |
| Arena-reset invalidation | No (not implemented) | No | N/A |
| Aliasing / borrow check | Yes | -- | Yes |
| Nullability enforcement | Yes | -- | Yes |
| Static bounds check | Yes | -- | Yes |
| Dynamic bounds check | -- | Yes | Yes |
