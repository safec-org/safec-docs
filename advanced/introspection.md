# Compile-Time Introspection

SafeC provides several built-in operators for inspecting types and expressions at compile time. These are resolved during semantic analysis and produce constant values — no runtime reflection or RTTI is involved.

## `sizeof`

Returns the size of a type or expression in bytes. The result is a compile-time constant of type `long`.

```c
long s1 = sizeof(int);            // 4
long s2 = sizeof(double);         // 8
long s3 = sizeof(char);           // 1

struct Point { double x; double y; };
long s4 = sizeof(Point);          // 16

int arr[10];
long s5 = sizeof(arr);            // 40 (10 * sizeof(int))
```

When applied to an expression, `sizeof` returns the size of the expression's type without evaluating the expression:

```c
int x = 42;
long s = sizeof(x);               // 4 (size of int, x is not evaluated)
```

## `alignof`

Returns the alignment requirement of a type in bytes. The result is a compile-time constant.

```c
long a1 = alignof(char);          // 1
long a2 = alignof(int);           // 4
long a3 = alignof(double);        // 8
long a4 = alignof(long long);     // 8
```

Alignment values are platform-dependent and useful alongside the `align(N)` attribute to ensure correct alignment for SIMD, cache-line optimization, or hardware requirements:

```c
// Allocate a buffer aligned to the natural alignment of double (8 on
// most platforms)
align(8) char buf[1024];
```

::: warning `align(N)` requires a literal integer, not an expression
`align(alignof(double))` doesn't parse — `align(N)`'s parser only accepts a
literal integer token for `N`, not an expression, `alignof(...)` call, or
`const`-declared identifier. Look up the alignment value with `alignof`
separately (e.g. in a `static_assert`) and hardcode the literal in `align(N)`.
:::

## `typeof`

Extracts the type of an expression at compile time. The expression is not evaluated — only its type is resolved during semantic analysis.

```c
int x = 42;
typeof(x) y = 100;                // y is int

double arr[5];
typeof(arr[0]) val = 3.14;        // val is double

typeof(x + 1) z = 0;             // z is int (type of the expression x + 1)
```

`typeof` is particularly useful in generic code and macros where the type of an expression may not be known in advance:

```c
generic<T>
T double_it(T val) {
    typeof(val) result = val + val;
    return result;
}
```

::: tip
`typeof` is resolved entirely at compile time. The operand expression is never executed — only its type is inspected.
:::

## `fieldcount`

Returns the number of fields in a struct type as a compile-time constant:

```c
struct Point { double x; double y; };
struct Color { uint8_t r; uint8_t g; uint8_t b; uint8_t a; };
struct Empty {};

long n1 = fieldcount(Point);      // 2
long n2 = fieldcount(Color);      // 4
long n3 = fieldcount(Empty);      // 0
```

`fieldcount` only works on struct types. Using it on a non-struct type is a compile error.

This is useful for compile-time validation and generic serialization patterns:

```c
static_assert(fieldcount(Config) == 5, "Config struct changed — update serializer");
```

## `sizeof...(T)`

Returns the number of types in a variadic generic type pack:

```c
generic<T...>
int count_types(T... args) {
    return (int)sizeof...(T);      // sizeof...(T) is unsigned long; cast to
                                    // match the declared int return type
}

int main() {
    int n = count_types(1, 2.0, 'a'); // 3
    return n;
}
```

This is a compile-time constant. See [Generics](/reference/generics) for variadic generic details.

::: warning
`int n = count_types(...);` must be a local variable, not a global — like
other non-literal global initializers in SafeC, a call to a generic
function isn't accepted as a compile-time constant expression at file
scope, even though the value is knowable at compile time.
:::

## `static_assert`

Verifies a condition at compile time. If the condition is false, compilation fails with the provided message.

```c
static_assert(sizeof(int) == 4, "int must be 32-bit");
static_assert(sizeof(void*) == 8, "64-bit platform required");
static_assert(alignof(double) >= 8, "double must be 8-byte aligned");
```

`static_assert` can appear at file scope or inside function bodies. The condition must be a compile-time constant expression.

### Combining with `fieldcount`

```c
struct Packet {
    uint16_t header;
    uint32_t payload;
    uint16_t checksum;
};

static_assert(sizeof(Packet) <= 64, "Packet must fit in a cache line");
static_assert(fieldcount(Packet) == 3, "Packet field count changed");
```

## `if const`

Compile-time conditional branching. The condition must be a constant expression. Only the taken branch is compiled — the other branch is eliminated before codegen.

```c
#include <io.h>

const int DEBUG = 1;

void log_message(const char *msg) {
    if const (DEBUG) {
        println(msg);
    }
    // When DEBUG is 0, the println call is completely eliminated
}
```

This is more powerful than preprocessor `#ifdef` because it participates in type checking — both branches must be syntactically valid, but only the taken branch must be semantically valid.

```c
#include <io.h>

generic<T>
void print_value(T val) {
    if const (sizeof(T) == 4) {
        print_int((int)val);
    } else if const (sizeof(T) == 8) {
        print_float((double)val);
    }
}
```

## Const-Eval Engine Limits

The compile-time evaluator enforces resource limits to prevent infinite compile times:

| Limit | Default |
|-------|---------|
| Maximum recursion depth | 256 calls |
| Maximum loop iterations | 1,000,000 per loop |
| Total instruction budget | 10,000,000 operations |

Exceeding any limit is a compile error. These limits apply to `const` function evaluation, `consteval` functions, `static_assert` conditions, and `if const` branch resolution.

```c
// This hits the recursion limit:
consteval int bad(int n) {
    return bad(n + 1);
}
const int r1 = bad(0);             // ERROR: recursion depth limit (256) exceeded

// This hits the loop limit:
consteval int slow() {
    int x = 0;
    for (int i = 0; i < 2000000; i++) {
        x += i;
    }
    return x;
}
const int r2 = slow();             // ERROR: loop iteration limit exceeded
```

Both functions must actually be evaluated at compile time to trigger these
limits — assigning the call result to a `const` global (as above) forces
that; a `consteval` function that's never called in a constant context
never runs through the const-eval engine at all.

## Summary

| Operator | Returns | Evaluated At |
|----------|---------|-------------|
| `sizeof(T)` | Size in bytes | Compile time |
| `sizeof(expr)` | Size of expression type in bytes | Compile time |
| `alignof(T)` | Alignment requirement in bytes | Compile time |
| `typeof(expr)` | The type of the expression | Compile time |
| `fieldcount(T)` | Number of struct fields | Compile time |
| `sizeof...(T)` | Number of types in variadic pack | Compile time |
| `static_assert(cond, msg)` | (assertion) | Compile time |
| `if const (cond)` | (branch selection) | Compile time |
