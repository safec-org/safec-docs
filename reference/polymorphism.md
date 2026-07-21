# Polymorphism & OOP

SafeC has no classes, no inheritance, and no vtables. That's a deliberate design stance, not a missing feature — see [Design Philosophy](/guide/design#why-not-classes): "Polymorphism is achieved via generics (monomorphized, zero cost)." Every mechanism on this page is opt-in, and its runtime cost (zero, for most of them) is visible directly in the mechanism you chose, not hidden behind an implicit vtable pointer every object carries whether it needs one or not.

This page pulls together every polymorphism-adjacent mechanism SafeC actually has — most already documented individually elsewhere, linked from here — into one map of "coming from C++/Java/Python OOP, what do I reach for." It also documents `fn_eval`, SafeC's vtable-free method-dispatch primitive, which — despite being a fully implemented compiler feature with its own keyword, parser rule, and semantic-analysis pass — had no reference documentation before this page.

## Encapsulation: Structs and Methods

Data and behavior bundle into a struct the same way a class would, minus everything C++ classes bundle in alongside them — see [Design Philosophy](/guide/design#why-not-classes) for the specific list (no implicit constructors/destructors, no hidden `this`, no vtable). A method is sugar for a plain function taking an explicit `self` pointer:

```c
struct Point {
    double x;
    double y;
    double length() const;
};
double Point::length() const {
    return sqrt_d(self.x * self.x + self.y * self.y);
}
```

`p.length()` compiles to a direct call `Point_length(&p)` — no indirection. See [Functions](/reference/functions#struct-methods) for the full lowering table and calling-convention details.

## Ad-Hoc Polymorphism: Operator Overloading

The same operator name resolves to different behavior per type, the classic definition of ad-hoc polymorphism — scoped to a fixed operator set (`+ - * / % == != < > <= >=`), not general function overloading (SafeC has no overloading by parameter type outside this list):

```c
struct Vec2 {
    double x;
    double y;
    Vec2 operator+(Vec2 other) const;
};
Vec2 Vec2::operator+(Vec2 other) const {
    Vec2 result;
    result.x = self.x + other.x;
    result.y = self.y + other.y;
    return result;
}

Vec2 c = a + b;   // calls Vec2_operator+(a, b)
```

See [Functions](/reference/functions#operator-overloading) and [Operators](/reference/operators#operator-overloading).

## Parametric Polymorphism: Generics

The main polymorphism mechanism, and the one the design philosophy singles out as the classes replacement: a `generic<T>` function or struct is fully monomorphized — the compiler emits a separate concrete copy per instantiating type, at compile time, with no runtime type tag and no indirection:

```c
generic<T>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}

int m1 = max(3, 7);          // instantiates max<int>
double m2 = max(1.5, 2.7);   // instantiates max<double>
```

Structs are generic the same way (`generic<T> struct Pair { T first; T second; };`), including generic methods. See [Generics](/reference/generics) for monomorphization, name mangling, variadic packs, and the region-safety interaction.

**The load-bearing limitation to know before reaching for generics as your polymorphism story:** monomorphization means there is no such thing as a heterogeneous runtime collection of `generic<T>` values with different `T`s — `Vec<Circle>` and `Vec<Square>` are two unrelated, fully separate emitted types, the same way `std::vector<Circle>` and `std::vector<Square>` are two unrelated C++ template instantiations. If you need one array holding "any shape," see [Manual Dispatch](#manual-dispatch-explicit-vtables) below — that's the honest, explicit way to get it.

## Structural Polymorphism: Traits

A `trait` is a named set of method signatures, satisfied **structurally** ("duck-typed") — no `impl Trait for Type` block, no explicit opt-in. A struct satisfies a trait the moment it defines matching methods:

```c
trait Drawable {
    void draw() const;
}

struct Circle {
    double radius;
    void draw() const;   // satisfies Drawable — nothing else needed
}
void Circle::draw() const { printf("circle r=%.1f\n", self.radius); }

generic<T: Drawable> void render(T shape) { shape.draw(); }
render(circle);   // OK: Circle has a matching draw() const
```

Conformance is checked at the monomorphization call site, not where the generic function's body is defined — a missing method produces an error at the call, not a confusing failure deep inside library internals. Built-in structural traits: `Numeric`, `Eq`, `Ord`, `Add`, `Sub`, `Mul`, `Div` (operator-backed), plus `Indexed` (anything usable with `[]`) and `Pointer` (raw pointer/reference types), satisfied by the type system itself rather than user-defined methods. See [Generics](/reference/generics#traits-and-constrained-generics).

This is SafeC's closest equivalent to an interface/abstract base class — a trait bound says "any `T` with these methods," checked at compile time, still monomorphized (still no runtime dispatch — every call in a `generic<T: Drawable>` body is a direct call to that instantiation's concrete `draw`).

## Vtable-Free Dynamic Dispatch: `fn_eval`

Trait bounds get you "call this method on a `T` known to have it." `fn_eval` gets you something a trait bound can't: **the method itself, as a first-class function-pointer value**, resolved per concrete type — without declaring a trait, and without any runtime dispatch machinery. The compiler's own description (from `FnEvalExpr`'s design comment) calls this "vtable-free polymorphism": the source reads like it's picking a method dynamically, but by the time codegen sees it, every call site is a direct function reference.

### Syntax

```c
fn_eval(object, func)
```

- `object` is only ever inspected for its **type** — never evaluated, never codegen'd, the same treatment `sizeof`'s operand gets.
- `func` must directly name an already-declared **plain function** (not a method) — it's used purely as a name+signature key, and is itself never called. Its name is the method name being looked up; its parameter/return types (self excluded) must match the found method's exactly.
- The whole expression evaluates to a function-pointer value for `object`'s struct type's method of that name — callable immediately, or stored for later, with the receiver passed explicitly as the first argument (methods have no bound receiver in SafeC — see the lowering table in [Functions](/reference/functions#lowering)).

### Resolution rules

`fn_eval(object, func)` fails to compile (not silently falls back to anything) unless all of the following hold:

| Requirement | Error if violated |
|---|---|
| `func` names a declared function | `'X' does not name a function` |
| `func` is a plain function, not a method | `'X' must be a plain function, not a method` |
| `object`'s type is a struct (or pointer/reference to one) | `first argument must be a struct...` |
| That struct type has a method named `func`'s name | `type 'X' has no method named 'Y'` |
| The method's parameter count (excluding `self`) matches `func`'s | `'X::Y' takes N parameter(s), but 'Y' declares M` |
| Every parameter type matches, position by position | `parameter N of 'X::Y' is '...', but 'Y' declares '...'` |
| The return type matches | `'X::Y' returns '...', but 'Y' declares '...'` |

### Generic dispatch, resolved per instantiation

The design case: call `fn_eval` inside a `generic<T>` function. Each monomorphized instantiation of `T` re-resolves `fn_eval` against its own concrete type — no trait declaration needed, since the check is structural (name + signature) at the point of instantiation, exactly like everything else in generic type-checking:

```c
struct Circle {
    double radius;
    double area() const;
};
double Circle::area() const { return 3.14159265 * self.radius * self.radius; }

struct Square {
    double side;
    double area() const;
};
double Square::area() const { return self.side * self.side; }

// A "shape" declaration -- name (area) and signature (takes nothing,
// returns double, self excluded) are the only things that matter. It is
// never called.
double area() { return 0.0; }

generic<T>
double describe_area(T obj) {
    return fn_eval(obj, area)(&obj);
}

int main() {
    struct Circle c; c.radius = 2.0;
    struct Square s; s.side = 3.0;

    double ca = describe_area(c);   // describe_area<Circle> resolves fn_eval to Circle::area
    double sa = describe_area(s);   // describe_area<Square> resolves fn_eval to Square::area
    printf("circle=%.4f square=%.4f\n", ca, sa);   // circle=12.5664 square=9.0000
    return 0;
}
```

`describe_area` never mentions `Circle` or `Square` by name, and neither type declares conformance to anything — `fn_eval` just asks "does `T` have a method that looks like `area`?" at each instantiation. (Verified: compiles and runs, output `circle=12.5664 square=9.0000`, matching `π·2²` and `3²`.)

### Extracting a method as a stored value

Because `fn_eval` produces an ordinary function-pointer value (not an inline call), it can be assigned to a `fn` variable and invoked later — a genuine "unbound method reference," resolved statically, no closure:

```c
fn double(&stack const Circle) getArea = fn_eval(c, area);
double ca = getArea(&c);   // 12.5664
```

(Verified: compiles and runs. Note the parameter type in the `fn` declaration is the reference form `&stack const Circle`, not a raw `const Circle*` — `fn_eval`'s result type mirrors how the method's `self` is actually represented in SafeC's own type system, not the lowered-to-C pointer form the [Functions](/reference/functions#lowering) table shows for the *emitted* signature.)

### `fn_eval` vs. the alternatives

| Mechanism | Dispatch resolved | Needs a trait? | Produces a value? | Works across heterogeneous types at runtime? |
|---|---|---|---|---|
| Direct call (`obj.method()`) | Compile time | No | No — calls immediately | No |
| Trait-bound generic (`generic<T: X>`) | Compile time, per instantiation | Yes | No — calls immediately | No (monomorphized) |
| `fn_eval(obj, shape)` | Compile time, per instantiation | No | **Yes** — a `fn` value | No (monomorphized) |
| Manual function-pointer table (next section) | **Runtime** | No | Yes | **Yes** |

Reach for `fn_eval` specifically when you want a generic function to pick up "whatever method named X this particular `T` happens to have" without formalizing a trait for it, or when you need the method as a value to pass around rather than call in place. If you just need to call the method, a trait bound is more conventional and self-documenting. If you need actual runtime dispatch over a mix of types in one collection, neither generics nor `fn_eval` can do that — both are compile-time/monomorphized — see below.

## Closed-Set Polymorphism: Tagged Unions + `match`

Every `union` in SafeC is tagged (a hidden discriminant, enforced field access), making it a real sum type — the "polymorphism over a known, closed set of variants" style familiar from Rust enums or sealed classes, resolved by exhaustive pattern matching rather than dispatch:

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

union Result a = Result.ok(42);
handle(a);   // ok: 42
```

A `match` used as an *expression* must be provably exhaustive (every variant covered, or a `default`/wildcard arm) — the compiler, not a runtime check, guarantees every case is handled. Unions are generic too: `generic<T, E> union Result { T ok; E err; }`. See [Types](/reference/types#union-types) and [Control Flow](/reference/control-flow#match-statement).

This is the right tool when the set of "shapes" a value can take is fixed and known at the definition site (a `Shape` that's always exactly a circle, square, or triangle) — as opposed to open-ended polymorphism (a `Shape` a downstream user can extend with their own new variant), which is what generics/traits/`fn_eval` are for.

## Nominal Distinctness: Newtype

Not dispatch-related, but relevant to "OOP without a shared base class doing the wrong kind of type unification": `newtype` creates a distinct type from a base type, incompatible without an explicit cast — useful for preventing two structurally-identical-but-semantically-different `int`s (a `UserId` and a `PostId`, say) from being accidentally interchangeable the way a plain `typedef` would allow:

```c
newtype UserId = int;
UserId id = (UserId)42;
// int x = id;   // ERROR: UserId is not int
```

See [Types](/reference/types#newtype-distinct-types).

## Manual Dispatch: Explicit "Vtables" {#manual-dispatch-explicit-vtables}

Every mechanism above is resolved at compile time. None of them gives you a single array holding a `Circle` and a `Square` dispatched through the same call at runtime — because that specific thing (a heterogeneous collection, dispatched dynamically) is exactly what a vtable-based language builds in automatically, and SafeC's whole stance is that this cost should be visible, not automatic. When you actually need it, you build it yourself out of plain function pointers and `void*` — precisely what a vtable *is*, under the hood, minus the compiler hiding it from you:

```c
double circle_area(void* obj) {
    struct Circle* c;
    unsafe { c = (struct Circle*)obj; }
    double r;
    unsafe { r = c->radius; }
    return 3.14159265 * r * r;
}
double square_area(void* obj) {
    struct Square* s;
    unsafe { s = (struct Square*)obj; }
    double side;
    unsafe { side = s->side; }
    return side * side;
}

struct Shape {
    void* data;
    fn double(void*) area;
};

int main() {
    struct Circle c; c.radius = 2.0;
    struct Square sq; sq.side = 3.0;

    struct Shape shapes[2];
    unsafe { shapes[0].data = (void*)&c; }
    shapes[0].area = circle_area;
    unsafe { shapes[1].data = (void*)&sq; }
    shapes[1].area = square_area;

    int i = 0;
    while (i < 2) {
        double a = shapes[i].area(shapes[i].data);
        printf("shape[%d] area=%.4f\n", i, a);   // 12.5664, then 9.0000
        i = i + 1;
    }
    return 0;
}
```

(Verified: compiles and runs, correct output for both shapes.) This is real runtime polymorphism — the loop doesn't know or care whether `shapes[i]` is backed by a `Circle` or a `Square` — bought with exactly one explicit `unsafe` cast per accessor, in code you wrote and can see. It's also exactly the pattern SafeC's own standard-library containers use internally (see [Standard Library Overview](/stdlib/#generic-pattern)): a `void*`-based struct plus typed wrapper functions, predating (and still coexisting with) `generic<T>` structs.

## Quick Reference

| Coming from... | Reach for |
|---|---|
| A class with private fields + public methods | Struct + methods |
| Operator overloading (`+`, `==`, ...) | Operator-overload methods (fixed operator set) |
| A generic class / template (`List<T>`) | `generic<T>` struct |
| An interface / abstract base class | `trait`, structurally satisfied |
| A `Base*` pointer calling an overridden virtual method | Manual function-pointer table (`void*` + `fn` field) |
| Reflection-lite "get me this object's method as a value" | `fn_eval` |
| A sealed class hierarchy / closed enum with data | Tagged `union` + exhaustive `match` |
| A strongly-typed ID wrapper (`UserId` vs raw `int`) | `newtype` |

## What SafeC Doesn't Have

- **No classes, no inheritance, no `virtual`, no vtables** by default — see [Design Philosophy](/guide/design#why-not-classes) and [Comparison](/guide/comparison).
- **No general function/method overloading** — only the fixed operator set (`+ - * / % == != < > <= >=`) can have multiple type-specific definitions; there's no C++-style "same name, different parameter types" resolution for ordinary functions.
- **No heterogeneous runtime generic collections** — `generic<T>` is always monomorphized; a `Vec<Circle>` and a `Vec<Square>` are unrelated types. Reach for [manual dispatch](#manual-dispatch-explicit-vtables) or a tagged `union` when the runtime type genuinely needs to vary.
- **`fn_eval` matches by name, not "any method with this shape"** — if two different methods on a type both happen to match a shape function's signature, `fn_eval` doesn't disambiguate by signature alone; the shape function's name must equal the target method's name.
- **No runtime type identification (RTTI)** beyond what [Compile-Time Introspection](/advanced/introspection) (`typeof`, `fieldcount`) gives you at compile time — there's no `dynamic_cast`-style runtime "what type is this really" query on an opaque `void*`.
