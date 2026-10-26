# Functions

SafeC supports several function forms, from regular C-compatible functions to compile-time evaluated functions, struct methods, and generic functions.

## Regular Functions {#regular-functions}

```c
int add(int a, int b) {
    return a + b;
}

void greet(const &char name) {
    printf("Hello, %s\n", name);
}
```

## Const Functions {#const-functions}

Functions marked `const` may be executed at compile time if all arguments are known constants. They can also be called at runtime.

```c
const int factorial(int n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}

const int val = factorial(5);  // evaluated at compile time → 120
int dynamic = factorial(x);    // called at runtime
```

## Consteval Functions {#consteval-functions}

Functions marked `consteval` **must** be executed at compile time. Calling them with non-constant arguments is a compilation error.

```c
consteval int table_size() {
    return 256;
}

int lookup[table_size()];      // OK: compile-time constant
// int bad = table_size();     // ERROR if result not used in const context
```

## `constinit` Globals {#constinit-globals}

Every global variable's initializer must already be a compile-time
constant expression — this is enforced unconditionally, as a hard
compiler error, not merely when the optional `constinit` keyword is
present:

```c
int table_size() { return 256; }
int bad = table_size();   // ERROR: not a compile-time constant expression
int ok  = 256;             // fine
```

`constinit` is an optional, purely self-documenting annotation — writing
it changes nothing about how the initializer is checked, since every
global is already held to the same standard. It exists for readability
(matching C++'s `constinit` keyword) and to make the intent explicit at
the declaration site rather than relying on the reader to know the rule:

```c
constinit int table_size = 256;
```

This is a deliberate hardening over what plain C or C++ allow: C++'s
`constinit` only matters because C++ *permits* dynamic (runtime)
initialization of globals by default and `constinit` opts a specific
declaration out of that; SafeC never permits dynamic global
initialization at all, so there's no silent fallback to guard against —
a non-constant initializer is caught at compile time everywhere, with or
without the keyword.

## Pure Functions {#pure-functions}

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

See [Functional Programming](/reference/functional#purity) for what this buys you beyond the optimization hint.

## Must-Use Functions {#must-use-functions}

The `must_use` keyword causes a compiler warning if the return value is discarded:

```c
must_use int compute(int x) {
    return x * x + 1;
}

compute(5);                    // WARNING: return value discarded
int result = compute(5);       // OK
```

## Noreturn Functions {#noreturn-functions}

Functions that never return (e.g., `abort`, `exit`, infinite loops) can be annotated with `noreturn`:

```c
noreturn void panic(const &char msg) {
    printf("PANIC: %s\n", msg);
    abort();
}
```

## Struct Methods {#struct-methods}

Methods are declared inside a struct body and defined outside using `T::method()` qualified syntax. The `self` parameter is implicit.

### Declaration {#declaration}

```c
struct Point {
    double x;
    double y;

    double length() const;
    void scale(double s);
};
```

### Definition {#definition}

```c
double Point::length() const {
    return self.x * self.x + self.y * self.y;
}

void Point::scale(double s) {
    self.x = self.x * s;
    self.y = self.y * s;
}
```

### Calling Methods {#calling-methods}

```c
Point p = {3.0, 4.0};
double len = p.length();       // calls Point_length(&p)
p.scale(2.0);                  // calls Point_scale(&p, 2.0)
```

### Lowering {#lowering}

Methods are lowered to plain functions with an explicit `self` pointer:

| SafeC signature | Lowered C signature |
|----------------|---------------------|
| `double Point::length() const` | `double Point_length(const Point* self)` |
| `void Point::scale(double s)` | `void Point_scale(Point* self, double s)` |

Const methods receive a `const T*` self pointer; non-const methods receive a `T*` self pointer. Inside the method body, `self` is typed as `&stack T`.

### Methods declared outside the struct {#methods-declared-outside-the-struct}

A method can also be declared outside the struct's body, with the qualified name — typically in another header that builds on the type. Calls resolve the same way as for a method declared in the body:

```c
// tensor.h
struct Tensor { ... };

// tensor_nn.h — adds operations to Tensor without touching tensor.h
&FloatTensor FloatTensor::relu() const;
&FloatTensor FloatTensor::softmax() const;

// tensor_nn.sc
&FloatTensor FloatTensor::relu() const { ... }
```

This keeps each module's methods with that module: the standard library's `FloatTensor` gets its activations from `tensor_nn.h`, its pooling from `convnet.h` and its GPU variants from the backend headers. Static methods must be declared in the struct body (a `static` before an out-of-body declaration means C's internal linkage).

### Static Methods {#static-methods}

A method declared `static` has no `self` and is called through its type — the place for a type's constructors and other type-level functions:

```c
struct Log {
    int fd;

    static struct Log open(const &char path);   // no self
    int write([]const unsigned char bytes);
};

struct Log Log::open(const &char path) { ... }   // defined like any method, without 'static'

struct Log l = Log::open("app.log");              // called through the type
struct String s = std::String::from("data");      // a std type's, from outside 'namespace std'
```

A static method lowers to the plain function `Log_open` — the same mangling as any method, minus the self pointer — so a call costs exactly a function call. Any keyword spelled like a word can name a method where `(` follows, so `Vec::new()`, `String::join(...)` and `t.spawn()` are all valid.

On a [generic struct](/reference/generics#generic-structs-and-methods) a static method is a generic function over the struct's parameters, inferred like any generic call: from the arguments, or from the target type when the parameters only appear in the return type:

```c
generic<T>
struct Box {
    T v;
    static struct Box<T> of(T x);
    static struct Box<T> new();
};

struct Box<int>  a = Box::of(5);    // T = int from the argument
struct Box<long> b = Box::new();    // T = long from the target type
```

Calling a static method on a value (`f.open(...)`) or an instance method through the type (`File::read(...)`) is a compile error that names the right form; `self` is undeclared inside a static method, and `static` on a field is rejected.

## Operator Overloading {#operator-overloading}

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

See [Polymorphism & OOP](/reference/polymorphism#ad-hoc-polymorphism-operator-overloading) for how this fits alongside generics, traits, and `fn_eval` as one of several distinct polymorphism mechanisms.

## Generic Functions {#generic-functions}

Generic functions use the `generic<T>` syntax and are fully monomorphized at compile time.

### Basic Generic {#basic-generic}

```c
generic<T>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}

int m1 = max(3, 7);           // instantiates max<int>
double m2 = max(1.5, 2.7);    // instantiates max<double>
```

### Constrained Generic {#constrained-generic}

Type parameters can be constrained with traits:

```c
generic<T: Numeric>
T clamp(T val, T lo, T hi) {
    if (val < lo) return lo;
    if (val > hi) return hi;
    return val;
}
```

### Monomorphization {#monomorphization}

The compiler deep-clones the function body for each concrete type instantiation, substituting type parameters with concrete types. The mangled name follows the pattern `__safec_fn_type`:

```
max<int>    → __safec_max_int
max<double> → __safec_max_double
```

Generic bodies are skipped during the first semantic analysis pass. Type inference determines `T` from argument types at call sites.

## Naked Functions {#naked-functions}

Naked functions have no compiler-generated prologue or epilogue. The body must consist entirely of inline assembly.

```c
naked void isr_handler() {
    asm volatile ("iret");
}
```

## Interrupt Functions {#interrupt-functions}

Interrupt functions use the ISR calling convention. They must be `void(void)`:

```c
interrupt void timer_isr() {
    // handle timer interrupt
}
```

## Function Pointers {#function-pointers}

Functions can be referenced as values using the `fn` type syntax:

```c
fn int(int, int) op = add;
int result = op(3, 4);        // calls add(3, 4)

// Higher-order functions
int apply(fn int(int) f, int x) {
    return f(x);
}
```

See [Functional Programming](/reference/functional) for a larger higher-order-function example (generic `map`/`fold` helpers), and [Polymorphism & OOP](/reference/polymorphism#manual-dispatch-explicit-vtables) for using a struct field of `fn` type plus `void*` to build explicit runtime dispatch — SafeC's equivalent of a hand-written vtable.
