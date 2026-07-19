# Types

SafeC provides a strong, statically-typed type system with no implicit conversions (except safe integer widening). All types are resolved at compile time; generics are fully monomorphized with zero runtime overhead.

## Primitive Types

| Type | Size | Description |
|------|------|-------------|
| `int` | 32-bit | Signed integer |
| `long` / `long long` | 64-bit | Signed long integer |
| `float` | 32-bit | IEEE 754 single-precision |
| `double` | 64-bit | IEEE 754 double-precision |
| `bool` | 1-bit | `true` or `false` |
| `char` | 8-bit | Character (unsigned byte) |
| `void` | 0-bit | Unit type / no value |

## Sized Integer Types

For explicit control over width and signedness:

| Signed | Unsigned | Width |
|--------|----------|-------|
| `int8_t` | `uint8_t` | 8-bit |
| `int16_t` | `uint16_t` | 16-bit |
| `int32_t` | `uint32_t` | 32-bit |
| `int64_t` | `uint64_t` | 64-bit |

## Type Inference with `auto`

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

## Struct Types

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

## Union Types

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

Unions can't be generic — the same limitation as [generic structs](/reference/generics): `generic<T, E> union Result { T ok; E err; }` doesn't parse (a `generic<...>` declaration only ever accepts a following function or variable, never a struct/union). For a sum type over an arbitrary pair of types, use `void*` fields plus a discriminant, the same type-erasure-plus-generic-wrapper-functions pattern the standard library's collections use.

## Tuple Types

Tuples are anonymous product types. Members are accessed by index using `.0`, `.1`, etc.

```c
tuple(int, double) pair = (42, 3.14);
int first = pair.0;
double second = pair.1;
```

Tuples lower to anonymous LLVM struct types at codegen.

## Slice Types

A slice is a fat pointer consisting of a data pointer and a length. Slices provide bounds-checked access to contiguous memory.

```c
int arr[5] = {10, 20, 30, 40, 50};
[]int s = arr[1..4];   // {int*, i64} with len=3

int x = s[0];          // bounds-checked access
long len = s.len;      // length field
int *raw = s.ptr;      // underlying pointer
```

## Optional Types

Optional types represent values that may or may not be present. They lower to a `{T, i1}` pair.

```c
?int find_first(int *arr, int n, int target) {
    int i = 0;
    while (i < n) {
        int v;
        unsafe { v = arr[i]; }
        if (v == target) return i;   // implicit T -> ?T wrap
        i = i + 1;
    }
    return null;                     // the empty case
}

// Usage with try (propagates the empty case to the caller)
?int wrapper(int *arr, int n, int target) {
    int val = try find_first(arr, n, target);
    return val * 2;
}
```

A plain `T` implicitly wraps to `?T` (as in `return i;` above), and `null` is the empty value for **both** `?T` optionals and nullable references — there is no separate `some(x)`/`none` constructor syntax; those two identifiers only appear as `match` patterns (see [Control Flow](/reference/control-flow)), not as general expressions.

Nullable references use the same `?` syntax:

```c
?&stack Node next;   // nullable stack reference
```

### Reading a nullable value

A pointer (`T*`), nullable reference (`?&region T`), or optional (`?T`) cannot be dereferenced, member-accessed, or force-unwrapped directly — the compiler requires one of the following:

| Operation | Works on | Effect |
|-----------|----------|--------|
| `x.is_null()` | `T*`, `?&region T` | Returns `bool`; presence check only, does not narrow `x`'s type |
| `x.is_none()` | `?T` | Returns `bool`; the optional's equivalent of `is_null()` |
| `x.default(fallback)` | all three | Returns the inner value if present, else evaluates and returns `fallback` (must match the inner type) |
| `match (x) { case null / none: ...  case some(v): ... }` | all three | `v` is bound directly as the inner type inside the `some` arm |
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
        case some(v): v.value,   // v : &stack Node — bound to the payload type directly
    };
}
```

`x.is_null()` on a `?T` (and `x.is_none()` on a pointer/nullable reference) is a compile error — use the one matching the receiver's kind. `match` with `null`/`some(x)` patterns works on raw pointers (`T*`) too, not just nullable references.

## Newtype Distinct Types

Newtypes create distinct types from a base type. They are not interchangeable with their base.

```c
newtype UserId = int;
newtype Temperature = double;

UserId id = (UserId)42;   // explicit cast — no UserId(42) constructor-call syntax
// int x = id;            // ERROR: UserId is not int
```

## Enum Types

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

## Function Types

Function pointers use the `fn` keyword:

```c
fn int(int, double) compute;
fn int(int) transform = add_one;
int result = transform(5);     // calls add_one(5)
```

## Region-Qualified Reference Types

References carry region information that the compiler uses for lifetime analysis:

```c
&stack int           // non-null stack reference
&heap float          // non-null heap reference
&static Config       // non-null static reference
&arena<AudioPool> Frame  // non-null arena reference
```

See [Memory & Regions](/reference/memory) for details.

## Generic Types

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

## Type Conversions

SafeC has **no implicit conversions** with one exception: safe integer widening (e.g., `uint8_t` to `int`, `char` to `int`). All other conversions require explicit casts:

```c
int x = 42;
double d = (double)x;    // explicit cast required

float f = 3.14f;
int i = (int)f;           // explicit truncation

long long big = 100LL;
int small = (int)big;     // explicit narrowing
```

## Value vs Reference Semantics

- **Structs are value types**: assignment copies the entire struct
- **References are explicit**: you must use `&` to create a reference and region-qualify it
- **No hidden move semantics**: what you write is what happens
- **Arrays decay to pointers** when passed to functions, following C convention

```c
struct Point { double x; double y; };

Point a = {1.0, 2.0};
Point b = a;              // copies the struct
b.x = 99.0;              // does not affect a

&stack Point ref = &a;    // explicit reference
```
