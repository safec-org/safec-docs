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

### Arena References Die on Reset

```c
region Pool { capacity: 1024 }

&arena<Pool> int p = new<Pool> int;
arena_reset<Pool>();
*p = 42;                       // ERROR: reference invalidated by arena reset
```

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
const &stack int a = &x;       // immutable borrow
const &stack int b = &x;       // OK: multiple immutable borrows allowed
```

### Mutable + Immutable Conflict

```c
int x = 42;
const &stack int r = &x;       // immutable borrow
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

Plain references are non-null by default. Nullable references must use the `?&` prefix and be checked before use:

```c
?&stack Node next = get_next(node);

// *next;                      // ERROR: cannot dereference nullable ref
                               //        without null check

if (next != null) {
    int val = (*next).value;   // OK: null check performed
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
    int *raw = (int*)malloc(10 * sizeof(int));
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

## Unsafe Escape

The `unsafe escape {}` block permanently removes region guarantees for a reference, allowing it to be used anywhere regardless of lifetime:

```c
&stack int ref;
unsafe escape {
    int local = 42;
    ref = &local;              // allowed: region tracking removed
}
// ref is now untracked -- the programmer takes full responsibility
```

::: danger
`unsafe escape` is more dangerous than regular `unsafe`. It permanently strips region information from references, meaning the compiler can no longer prevent use-after-free. Use it only when absolutely necessary (e.g., interfacing with C libraries that manage their own lifetimes).
:::

## Summary of Safety Guarantees

| Safety Check | Compile-Time | Runtime | Suppressed by `unsafe` |
|-------------|-------------|---------|----------------------|
| Definite initialization | Yes | -- | No |
| Region escape analysis | Yes | -- | Yes |
| Aliasing / borrow check | Yes | -- | Yes |
| Nullability enforcement | Yes | -- | Yes |
| Static bounds check | Yes | -- | Yes |
| Dynamic bounds check | -- | Yes | Yes |
