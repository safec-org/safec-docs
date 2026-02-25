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
| `int8` | `uint8` | 8-bit |
| `int16` | `uint16` | 16-bit |
| `int32` | `uint32` | 32-bit |
| `int64` | `uint64` | 64-bit |

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

Tagged unions provide type-safe sum types:

```c
generic<T, E>
union Result {
    T ok;
    E err;
}
```

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
    for (int i = 0; i < n; i = i + 1) {
        if (arr[i] == target) return some(i);
    }
    return none;
}

// Usage with try operator (propagates none)
int val = try find_first(arr, n, 42);
```

Nullable references use the same syntax:

```c
?&stack Node next;   // nullable stack reference
```

## Newtype Distinct Types

Newtypes create distinct types from a base type. They are not interchangeable with their base.

```c
newtype UserId = int;
newtype Temperature = double;

UserId id = UserId(42);
// int x = id;          // ERROR: UserId is not int
```

## Enum Types

Enums with explicit underlying type:

```c
enum Color : uint8 {
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

SafeC has **no implicit conversions** with one exception: safe integer widening (e.g., `uint8` to `int`, `char` to `int`). All other conversions require explicit casts:

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
