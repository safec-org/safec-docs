# Functions

SafeC supports several function forms, from regular C-compatible functions to compile-time evaluated functions, struct methods, and generic functions.

## Regular Functions

```c
int add(int a, int b) {
    return a + b;
}

void greet(const char *name) {
    printf("Hello, %s\n", name);
}
```

## Const Functions

Functions marked `const` may be executed at compile time if all arguments are known constants. They can also be called at runtime.

```c
const int factorial(int n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}

const int val = factorial(5);  // evaluated at compile time → 120
int dynamic = factorial(x);    // called at runtime
```

## Consteval Functions

Functions marked `consteval` **must** be executed at compile time. Calling them with non-constant arguments is a compilation error.

```c
consteval int table_size() {
    return 256;
}

int lookup[table_size()];      // OK: compile-time constant
// int bad = table_size();     // ERROR if result not used in const context
```

## Pure Functions

Functions marked `pure` guarantee no side effects. The compiler lowers them with LLVM `readonly` and `nounwind` attributes, enabling aggressive optimization.

```c
pure double square(double x) {
    return x * x;
}
```

Pure functions may not:
- Modify global state
- Perform I/O
- Call non-pure functions

## Must-Use Functions

The `must_use` keyword causes a compiler warning if the return value is discarded:

```c
must_use int compute(int x) {
    return x * x + 1;
}

compute(5);                    // WARNING: return value discarded
int result = compute(5);       // OK
```

## Noreturn Functions

Functions that never return (e.g., `abort`, `exit`, infinite loops) can be annotated with `noreturn`:

```c
noreturn void panic(const char *msg) {
    printf("PANIC: %s\n", msg);
    abort();
}
```

## Struct Methods

Methods are declared inside a struct body and defined outside using `T::method()` qualified syntax. The `self` parameter is implicit.

### Declaration

```c
struct Point {
    double x;
    double y;

    double length() const;
    void scale(double s);
};
```

### Definition

```c
double Point::length() const {
    return self.x * self.x + self.y * self.y;
}

void Point::scale(double s) {
    self.x = self.x * s;
    self.y = self.y * s;
}
```

### Calling Methods

```c
Point p = {3.0, 4.0};
double len = p.length();       // calls Point_length(&p)
p.scale(2.0);                  // calls Point_scale(&p, 2.0)
```

### Lowering

Methods are lowered to plain functions with an explicit `self` pointer:

| SafeC signature | Lowered C signature |
|----------------|---------------------|
| `double Point::length() const` | `double Point_length(const Point* self)` |
| `void Point::scale(double s)` | `void Point_scale(Point* self, double s)` |

Const methods receive a `const T*` self pointer; non-const methods receive a `T*` self pointer. Inside the method body, `self` is typed as `&stack T`.

## Operator Overloading

Struct types can overload binary operators by defining methods named `operator+`, `operator-`, etc.

```c
struct Vec2 {
    double x;
    double y;

    Vec2 operator+(Vec2 other) const;
    Vec2 operator-(Vec2 other) const;
    Vec2 operator*(double s) const;
};

Vec2 Vec2::operator+(Vec2 other) const {
    Vec2 result;
    result.x = self.x + other.x;
    result.y = self.y + other.y;
    return result;
}
```

Usage:

```c
Vec2 a = {1.0, 2.0};
Vec2 b = {3.0, 4.0};
Vec2 c = a + b;               // calls Vec2_operator+(a, b)
```

Supported overloadable operators: `+`, `-`, `*`, `/`, `%`, `==`, `!=`, `<`, `>`, `<=`, `>=`.

Operator methods are mangled as `TypeName_operator+` etc. in the generated code.

## Generic Functions

Generic functions use the `generic<T>` syntax and are fully monomorphized at compile time.

### Basic Generic

```c
generic<T>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}

int m1 = max(3, 7);           // instantiates max<int>
double m2 = max(1.5, 2.7);    // instantiates max<double>
```

### Constrained Generic

Type parameters can be constrained with traits:

```c
generic<T: Numeric>
T clamp(T val, T lo, T hi) {
    if (val < lo) return lo;
    if (val > hi) return hi;
    return val;
}
```

### Monomorphization

The compiler deep-clones the function body for each concrete type instantiation, substituting type parameters with concrete types. The mangled name follows the pattern `__safec_fn_type`:

```
max<int>    → __safec_max_int
max<double> → __safec_max_double
```

Generic bodies are skipped during the first semantic analysis pass. Type inference determines `T` from argument types at call sites.

## Naked Functions

Naked functions have no compiler-generated prologue or epilogue. The body must consist entirely of inline assembly.

```c
naked void isr_handler() {
    asm volatile ("iret");
}
```

## Interrupt Functions

Interrupt functions use the ISR calling convention. They must be `void(void)`:

```c
interrupt void timer_isr() {
    // handle timer interrupt
}
```

## Function Pointers

Functions can be referenced as values using the `fn` type syntax:

```c
fn int(int, int) op = add;
int result = op(3, 4);        // calls add(3, 4)

// Higher-order functions
int apply(fn int(int) f, int x) {
    return f(x);
}
```
