# Types

SafeC provides a strong, statically-typed type system with no implicit conversions (except safe numeric widening — smaller to bigger, never the reverse). All types are resolved at compile time; generics are fully monomorphized with zero runtime overhead.

## Primitive Types {#primitive-types}

| Type | Size | Description |
|------|------|-------------|
| `int` | 32-bit | Signed integer |
| `long` / `long long` | 64-bit | Signed long integer |
| `float` | 32-bit | IEEE 754 single-precision |
| `double` | 64-bit | IEEE 754 double-precision |
| `bool` | 1-bit | `true` or `false` |
| `char` | 8-bit | Character (unsigned byte) |
| `void` | 0-bit | Unit type / no value |

## Sized Integer Types {#sized-integer-types}

For explicit control over width and signedness:

| Signed | Unsigned | Width |
|--------|----------|-------|
| `int8_t` | `uint8_t` | 8-bit |
| `int16_t` | `uint16_t` | 16-bit |
| `int32_t` | `uint32_t` | 32-bit |
| `int64_t` | `uint64_t` | 64-bit |
| `intptr_t`, `ptrdiff_t` | `uintptr_t`, `size_t` | pointer width |

`size_t` and friends follow the target's pointer width (`__SIZEOF_POINTER__`): 8 bytes on 64-bit targets, 4 on 32-bit ones such as `x86`, `aarch32`, Cortex-M and RV32. SafeC's own `long` is always 64-bit on every target, so it is *not* a pointer-sized integer — use `size_t`/`uintptr_t` for sizes and addresses. An integer argument to an `extern` C function's pointer-width integer parameter (a `size_t` on a 32-bit target) is converted at the call without a cast, so libc calls written with `unsigned long` sizes stay portable.

## Type Inference with `auto` {#type-inference-with-auto}

`auto` infers a local variable's type from its initializer — the variable must have one (`auto x;` with no initializer is a compile error):

```c
auto n = 42;       // int
auto pi = 3.14;     // double
auto p = compute(); // whatever compute() returns

for (auto i = 0; i < 10; i++) {
    // i : int
}
```

`auto` only infers from the initializer expression's own type — it doesn't do any broader flow analysis, and (like every other declared type) the inferred type is fixed for the variable's lifetime; there's no re-inference on reassignment. It works anywhere an ordinary declared-type local variable would — including a `for` loop's init clause, as shown above.

## Struct Types {#struct-types}

Structs are value types with C-compatible layout. Assignment copies the entire value.

```c
struct Point {
    double x;
    double y;

    double length() const;
    void scale(double s);
};
```

Structs support methods (see [Functions](/reference/functions)), operator overloading, and can be used as generic type arguments.

## Union Types {#union-types}

Every `union` in SafeC is a **tagged union**: alongside the fields you
declare, the compiler stores a hidden discriminant recording which field
is actually live, and enforces that you can only read the field you most
recently wrote (via `match`, not direct field access — see below). This
is a deliberate departure from C's `union`, where reading whatever field
you like regardless of which one was last written is legal (if
type-punning-dependent) — SafeC doesn't allow that, in keeping with its
general "no undefined behavior in safe code" stance.

```c
union Result {
    int ok;
    int err;
}
```

::: warning Construct and read through `Type.field(value)` / `match`, not plain `.field` assignment
Because every union is tagged, `union Result r; r.ok = 42;` followed by
plain-field reads doesn't behave like a C union (and can produce
outright wrong values for non-`int`-sized fields, due to how the
discriminant and payload are laid out) — it isn't the sanctioned way to
use one. Construct a union value with `TypeName.field(value)`, and read
it back with `match`'s dot-prefixed variant patterns, `case .field(x):`:

```c
extern int printf(const char* fmt, ...);
union Result {
    int ok;
    int err;
}

void handle(union Result r) {
    match (r) {
        case .ok(v):  printf("ok: %d\n", v);
        case .err(e): printf("err: %d\n", e);
        default:      printf("unreachable\n");
    }
}

int main() {
    union Result a = Result.ok(42);
    union Result b = Result.err(-1);
    handle(a);   // ok: 42
    handle(b);   // err: -1
    return 0;
}
```

This is the same `.variant(x)` pattern shape `?T`/`?&region T`'s
`some(x)` uses (see "Reading a nullable value" above), minus the leading
dot there — `some`/`none` are plain identifiers, not dot-prefixed, unlike
a tagged union's own variant names.
:::

Unions can be generic too, the same as [structs](/reference/generics#generic-structs-and-methods): `generic<T, E> union Result { T ok; E err; }` is a real sum type over an arbitrary pair of types, monomorphized per concrete `<T, E>` instantiation like any other generic type.

Tagged unions plus exhaustive `match` are SafeC's algebraic-data-type / closed-polymorphism mechanism — see [Polymorphism & OOP](/reference/polymorphism#closed-set-polymorphism-tagged-unions-match) and [Functional Programming](/reference/functional#algebraic-data-types).

## Tuple Types {#tuple-types}

Tuples are anonymous product types. Members are accessed by index using `.0`, `.1`, etc.

```c
tuple(int, double) pair = (42, 3.14);
int first = pair.0;
double second = pair.1;
```

A parenthesized, comma-separated list is a **tuple literal** wherever a tuple type is expected: an initializer, an assignment, a `return`, or an argument. Each element converts to the corresponding member type, so literals widen as they would in a plain assignment:

```c
tuple(int, double) divmod(int a, int b) { return (a / b, (double)(a % b)); }
int sum(tuple(int, int) t) { return t.0 + t.1; }

tuple(long long, int) q = (1, 2);   // 1 widens to long long
int s = sum((3, 4));
```

Tuples lower to anonymous LLVM struct types at codegen.

## Slice Types {#slice-types}

A slice is a fat pointer consisting of a data pointer and a length. Slices provide bounds-checked access to contiguous memory.

```c
int arr[5] = {10, 20, 30, 40, 50};
[]int s = arr[1..4];   // {int*, i64} with len=3

int x = s[0];          // bounds-checked access
long len = s.len;      // length field
int *raw = s.ptr;      // underlying pointer
```

Slicing takes a half-open range: `a[i..j]`, `a[i..]` (to the end of an array or slice), `a[..j]`, `a[..]` (all of it). A range outside the base, or with `i > j`, traps at run time like an out-of-bounds index; inside `unsafe { }` the check is left out. A raw pointer can be sliced only inside `unsafe { }` (`p[0..n]` asserts that `n` elements are there), and a slice can't be *cast* to a pointer — use its `.ptr` field.

### Read-only slices {#read-only-slices}

`[]const T` views elements it may not write. `[]T` converts to `[]const T` implicitly (never the other way), so a function that only reads takes `[]const T` and accepts both:

```c
long sum([]const int xs) {
    long s = 0L;
    for (unsigned long i = 0UL; i < xs.len; i = i + 1UL) { s = s + (long)xs[i]; }
    // xs[0] = 1;   // ERROR: cannot assign through a read-only slice '[]const int'
    return s;
}

int a[4] = {1, 2, 3, 4};
long total = sum(a[..]);
```

Byte slices interconvert across `char`, `signed char` and `unsigned char` (keeping `const`), so text is a byte buffer: `std::str_bytes("text")` (`std/str.h`) is a string's bytes as a `[]const char`, accepted wherever `[]const unsigned char` is expected.

### Optional slices and nullable functions {#optional-slices}

`?[]T` is an optional slice (narrow it with `match (s) { case some(v): ... case none: ... }`). `?fn R(Args)` is a nullable function reference: it holds `null` or a function, `match` or `!` (in `unsafe`) gets the function out, and calling it directly is a compile error.

The standard library takes slices wherever C would take a pointer and a length — `f.read(buf[..])`, `sha256(data, digest[..])`, `fft(xs[0..n])` — see [the standard library overview](/stdlib/#generic-pattern).

## Optional Types {#optional-types}

Optional types represent values that may or may not be present. They lower to a `{T, i1}` pair.

```c
?int find_first([]int arr, int target) {
    long i = 0L;
    while (i < arr.len) {
        if (arr[i] == target) return (int)i;   // implicit T -> ?T wrap
        i = i + 1L;
    }
    return null;                               // the empty case
}

// Usage with try (propagates the empty case to the caller)
?int wrapper([]int arr, int target) {
    int val = try find_first(arr, target);
    return val * 2;
}
```

A plain `T` implicitly wraps to `?T` (as in `return i;` above), and `null` is the empty value for **both** `?T` optionals and nullable references — there is no separate `some(x)`/`none` constructor syntax; those two identifiers only appear as `match` patterns (see [Control Flow](/reference/control-flow)), not as general expressions.

Nullable references use the same `?` syntax:

```c
?&stack Node next;   // nullable stack reference
```

### Reading a nullable value {#reading-a-nullable-value}

A pointer (`T*`), nullable reference (`?&region T` — "region" here also covers the region-less `?&T` form, see [Memory & Regions](/reference/memory)'s "Outliving References" section), or optional (`?T`) cannot be dereferenced, member-accessed, or force-unwrapped directly — the compiler requires one of the following:

| Operation | Works on | Effect |
|-----------|----------|--------|
| `x.is_null()` | `T*`, `?&region T` | Returns `bool`; presence check only, does not narrow `x`'s type |
| `x.is_none()` | `?T` | Returns `bool`; the optional's equivalent of `is_null()` |
| `x.default(fallback)` | all three | Returns the inner value if present, else evaluates and returns `fallback` (must match the inner type) |
| `match (x) { case null / none: ...  case some(v): ... }` | all three | Inside the `some` arm, `v` is the narrowed value: for `?&T`, the non-null reference `&T` (writes through `v` reach the referent); for `?T`, the contained `T` |
| `std::expect_ref(x, "msg")` (`std/panic.h`) | `?&T` | Returns `&T`, or panics with `msg` when `x` is null — for a null that would be a bug, not an expected case |
| `x!` / `*x` / `x.field` / `x->field` inside `unsafe { }` | all three | Bypasses the checks above entirely |

```c
struct Node { int value; };

int describe(?&stack Node n) {
    // int v = n.value;      // ERROR: requires 'unsafe', or match/is_null()/.default(value)
    // Node v = n!;          // ERROR: '!' force-unwrap requires 'unsafe'

    if (n.is_null()) { return -1; }        // OK: presence check
    Node fallback;
    fallback.value = -1;
    Node result = n.default(fallback);      // bind first — chaining .default(...).value
    return result.value;                    // directly doesn't compile (temporary receiver)

    // OK: unsafe bypasses the checks entirely
    // unsafe { return n->value; }
}

int describe_match(?&stack Node n) {
    return match (n) {
        case null:    -1,
        case some(v): v.value,   // v : &Node -- the narrowed, non-null reference
    };
}
```

A nullable reference can't be *cast* to a non-null one: `(&T)q` on a `?&T q` is a compile error — the cast would skip exactly the check `match` forces. Casting the other way, or to another nullable type (`(?&T)p`), is fine.

`x.is_null()` on a `?T` (and `x.is_none()` on a pointer/nullable reference) is a compile error — use the one matching the receiver's kind. `match` with `null`/`some(x)` patterns works on raw pointers (`T*`) too, not just nullable references.

See [Functional Programming](/reference/functional#optional-values-and-propagation) for `try`-based propagation and what `?T` deliberately doesn't offer (no `.map()`/`.and_then()` combinators).

## Newtype Distinct Types {#newtype-distinct-types}

Newtypes create distinct types from a base type. They are not interchangeable with their base.

```c
newtype UserId = int;
newtype Temperature = double;

UserId id = (UserId)42;   // explicit cast — no UserId(42) constructor-call syntax
// int x = id;            // ERROR: UserId is not int
```

## Enum Types {#enum-types}

Enums with explicit underlying type:

```c
enum Color : uint8_t {
    Red = 0,
    Green = 1,
    Blue = 2
}

enum Status : int {
    OK = 200,
    NotFound = 404,
    ServerError = 500
}
```

## Function Types {#function-types}

Function pointers use the `fn` keyword:

```c
fn int(int, double) compute;
fn int(int) transform = add_one;
int result = transform(5);     // calls add_one(5)
```

## Region-Qualified Reference Types {#region-qualified-reference-types}

References carry region information that the compiler uses for lifetime analysis:

```c
&stack int           // non-null stack reference
&heap float          // non-null heap reference
&static Config       // non-null static reference
&arena<AudioPool> Frame  // non-null arena reference
&Point               // non-null reference, no region -- accepts any of the above
?&Point              // nullable, no region
&volatile unsigned int   // reference to a volatile object (MMIO registers)
&&int                // reference to a reference (also &?&, ?&&, ?&?&)
```

Leaving off the region qualifier entirely (`&T` / `?&T`) gives a
reference with no declared or tracked region — see [Memory &
Regions](/reference/memory)'s "Outliving References" section for when
that's the right choice over pinning a specific region.

References replace raw pointers in SafeC signatures: `void*` becomes a
typed `&T` (generic where any type is accepted), `T**` becomes a
[reference to a reference](/reference/memory#references-to-references),
and an array of pointers becomes an array or `Slice<&T>` (see
[Arrays by Region](/reference/memory#arrays-by-region)). Raw pointers stay
for `extern` C declarations and `unsafe` code.

See [Memory & Regions](/reference/memory) for details.

## Generic Types {#generic-types}

Generics are compile-time only and fully monomorphized. No vtables or runtime dispatch.

```c
generic<T: Numeric>
T add(T a, T b) {
    return a + b;
}

// The compiler generates separate versions:
// int add(int a, int b)
// double add(double a, double b)
```

Generic type parameters can be constrained with traits:

```c
generic<T: Numeric>
T clamp(T val, T lo, T hi) {
    if (val < lo) return lo;
    if (val > hi) return hi;
    return val;
}
```

## Type Conversions {#type-conversions}

SafeC has **no implicit conversions** with one exception: safe widening
— any smaller numeric type to a bigger one, across integer, float, and
integer→float. Narrowing (bigger → smaller, or float → integer) still
requires an explicit cast:

```c
int x = 42;
double d = x;             // implicit: int -> double widens safely

float f = 3.14f;
double d2 = f;             // implicit: float -> double widens safely
int i = (int)f;            // explicit cast required: float -> int narrows

long long big = 100LL;
int small = (int)big;      // explicit cast required: narrowing
```

## Value vs Reference Semantics {#value-vs-reference-semantics}

- **Structs are value types**: assignment copies the entire struct
- **References are explicit**: you must use `&` to create a reference and region-qualify it
- **No hidden move semantics**: what you write is what happens
- **Arrays decay to a reference to their first element** when passed to functions (and to a pointer when passed to C)

```c
struct Point { double x; double y; };

Point a = {1.0, 2.0};
Point b = a;              // copies the struct
b.x = 99.0;              // does not affect a

&stack Point ref = &a;    // explicit reference
```
