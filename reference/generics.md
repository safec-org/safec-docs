# Generics

SafeC generics are compile-time only. Every generic function is fully monomorphized — the compiler generates a separate copy for each concrete type used. There are no vtables, no type erasure, and no runtime dispatch.

Structs and unions can be generic too — `generic<T> struct Pair { T first; T second; };` — fully monomorphized per concrete type argument, the same as generic functions; see [Generic Methods](#generic-structs-and-methods) below for struct methods and out-of-line definitions. The standard library's containers are built this way (`Vec<T>`, `HashMap<K, V>`, `BST<K, V, C>`, …) — see [Collections](/stdlib/collections).

## Generic Functions {#generic-functions}

Declare a generic function with `generic<T>` before the return type:

```c
generic<T>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}
```

The compiler infers the type argument from the call site:

```c
int m1 = max(3, 7);           // instantiates max<int>
double m2 = max(1.5, 2.7);    // instantiates max<double>
```

Each instantiation produces a separate function in the generated code.

## Traits and Constrained Generics {#traits-and-constrained-generics}

Type parameters can be constrained with traits to restrict what types are accepted:

```c
generic<T: Numeric>
T clamp(T val, T lo, T hi) {
    if (val < lo) return lo;
    if (val > hi) return hi;
    return val;
}
```

The `Numeric` constraint ensures `T` supports arithmetic and comparison. The compiler rejects instantiation with types that don't satisfy the constraint.

### Declaring a Trait {#declaring-a-trait}

A trait is a named set of method signatures:

```c
trait Drawable {
    void draw() const;
}
```

Traits in SafeC are **structural** ("duck-typed"), not nominal — there's no `impl Trait for Type` block. A struct satisfies a trait automatically as soon as it defines methods with the trait's method names. (The check is by name; a method whose signature doesn't fit fails where the generic body calls it. A `const` method satisfies a non-`const` requirement.)

```c
struct Circle {
    double radius;
    void draw() const;   // satisfies Drawable — nothing else needed
}
void Circle::draw() const { printf("circle r=%.1f\n", self.radius); }

generic<T: Drawable> void render(T shape) { shape.draw(); }
render(circle);   // OK: Circle has a matching draw() const
```

Conformance is checked at the call site during monomorphization: if the concrete type substituted for `T` doesn't have every method the trait bound requires, instantiation fails with a compile error there — not at the (possibly far-away, library-internal) point where the generic function's body uses that method.

### Built-in Traits {#built-in-traits}

| Trait | Required Operations |
|-------|---------------------|
| `Numeric` | Arithmetic (`+`, `-`, `*`, `/`) |
| `Eq` | `==`, `!=` |
| `Ord` | `<`, `>`, `<=`, `>=` |
| `Add` | `+` only |
| `Sub` | `-` only |
| `Mul` | `*` only |
| `Div` | `/` only |

Two further traits are satisfied structurally by the type system itself rather than by user-defined methods: `Indexed` (array, slice, and `vec<T,N>` types — anything usable with `[]`) and `Pointer` (raw pointer and reference types).

Primitive arithmetic types (`int`, `float`, …) satisfy the operator traits above and nothing else: they don't satisfy a user trait, since they have none of its methods.

Three more describe ownership and threads: `Drop` (a `void drop()` method: the type owns a resource and must be dropped — see [Ownership & Drop](/reference/ownership)), and `Send` / `Sync` (a value may move to, or be shared with, another thread; derived from the type's structure — see [Thread Safety](/reference/threads)).

### Combining bounds {#combining-bounds}

A type parameter can carry several traits joined with `+`. The constraint is their conjunction: the concrete type must satisfy every one, and each unmet one is reported.

```c
trait Sized { int size() const; }
trait Named { int name_len() const; }

generic<T: Sized + Named>
int label(const &T s) { return s.name_len() * s.size(); }
```

### Composing traits {#composing-traits}

A trait can be built from other traits — its **supertraits** — listed after a `:` and joined with `+`. A type satisfies the composed trait when it has the trait's own methods *and* satisfies every supertrait, transitively. A trait with an empty body is a pure combination:

```c
trait Shape : Sized + Named { int area() const; }   // size, name_len and area
trait Solid : Shape { int volume() const; }         // two levels: all four
trait Labeled : Sized + Named {}                     // just the combination

generic<T: Solid>
int describe(const &T s) { return s.size() + s.name_len() + s.area() + s.volume(); }
```

This is how to keep traits small — one capability each — and still give generic code a single name for a role. A generic function can then ask for exactly what it uses: `generic<T: Shape>` for one that needs areas, `generic<T: Sized>` for one that only needs sizes, and a `Solid` satisfies both. The standard library's ML models are organized this way: `trait Module : Parameterized + Forward {}` (see [Machine Learning](/stdlib/ml#modules)).

A supertrait can be a user trait or a built-in one (`trait Scalar : Numeric + Ord {}`). An unknown supertrait, or a trait that is its own supertrait through a cycle, is a compile error at the trait's declaration.

## Variadic Generics {#variadic-generics}

SafeC supports variadic type packs with `generic<T...>`. A variadic generic parameter accepts any number of arguments of any types, and `sizeof...(T)` gives the pack's size:

```c
generic<T...>
unsigned long count_args(T... args) {
    return sizeof...(T);
}

unsigned long n = count_args(1, 2.0, 'a');   // n is 3
```

A pack forwards into another call by naming it as a bare argument — `args`, not `args...` — which expands to the pack's actual arguments at each monomorphized call site:

```c
int sum3(int a, int b, int c) { return a + b + c; }

generic<T...>
int forward_sum(T... args) {
    return sum3(args);   // expands to sum3(args0, args1, args2) for a 3-element pack
}

int n = forward_sum(1, 2, 3);   // n == 6
```

A pack element can also be indexed with a literal (constant) index — `args[0]`, `args[1]`, ... — resolved at monomorphization time to the corresponding argument:

```c
generic<T...>
int first_int(T... args) {
    return args[0];
}
```

::: warning Literal indices only
`args[i]` requires `i` to be a compile-time integer literal, not a runtime variable — the pack doesn't exist as a real indexable array at codegen time, each `args[N]` is rewritten to the Nth actual argument during monomorphization. Looping over a pack with a runtime index isn't supported; write `sizeof...(T)` recursion or repeat the literal-index form for each position you need instead.
:::

## Monomorphization {#monomorphization}

When the compiler encounters a call to a generic function, it:

1. **Infers** the type argument(s) from the call-site argument types
2. **Deep-clones** the function AST, substituting type parameters with concrete types
3. **Mangles** the name: `max<int>` becomes `__safec_max_int`
4. **Analyzes** the monomorphized copy through the full semantic analysis pipeline
5. **Emits** code for each distinct instantiation

Generic function bodies are **skipped** during the first semantic analysis pass — they are only type-checked after monomorphization with concrete types.

### Name Mangling {#name-mangling}

Monomorphized functions follow the pattern `__safec_<name>_<type>`:

| SafeC | Mangled Name |
|-------|-------------|
| `max<int>` | `__safec_max_int` |
| `max<double>` | `__safec_max_double` |
| `Pair<int>` | `__safec_Pair_int` |

Instantiations are emitted with `linkonce_odr` linkage (in a COMDAT on ELF/COFF): every translation unit that uses `Vec<int>` emits its own copy, and the linker keeps exactly one. That is what lets a generic container live entirely in a header.

### Code Size {#code-size}

Each unique instantiation generates separate code. If you instantiate `max` with 5 different types, 5 separate functions are emitted. This trades binary size for runtime performance (no indirection).

## Generic Structs and Methods {#generic-structs-and-methods}

A struct (or union) can carry its own type parameters, declared the same way as a generic function's:

```c
generic<T>
struct Container {
    T value;
    int count;

    T get() const;
    void set(T new_value);
};

struct Container<int> c;
c.set(42);
int v = c.get();   // v == 42
```

`T get() const;`/`void set(T new_value);` above are in-body method
*declarations* — write the bodies out-of-line the same way you would for
a non-generic struct's methods, just with a `generic<T>` line above each
definition too. The out-of-line qualifier is the plain struct name
(`Container::get()`), **not** `Container<T>::get()` — the type parameter
is already in scope from the `generic<T>` line, so it isn't repeated on
the method-owner name:

```c
generic<T>
T Container::get() const {
    return self.value;
}

generic<T>
void Container::set(T new_value) {
    self.value = new_value;
}
```

### Bounded generic structs {#bounded-generic-structs}

A generic struct's type parameters take trait bounds exactly like a generic function's, including `+` and composed traits. The bound is checked where the struct is instantiated (`struct Holder<struct Box> h;`), and the struct's methods may call every bound method on fields of type `T`:

```c
generic<T: Shape + Sized>
struct Holder {
    T item;
    int total() const;
};

generic<T: Shape + Sized>
int Holder::total() const { return self.item.area() + self.item.size(); }

struct Holder<struct Box> h;       // OK when Box satisfies Shape (and so Sized)
// struct Holder<struct Flat> f;   // error: type 'Flat' does not satisfy constraint 'Shape' ...
```

Repeat the bound on each out-of-line method's `generic<...>` line. A bounded generic struct can itself satisfy a trait: a struct whose methods forward to its `T` fields is a new implementation of the same role, and generic code accepts it like any other.

### Methods with their own type parameters {#methods-with-their-own-type-parameters}

A method of a non-generic struct can have type parameters of its own: write `generic<...>` before it in the struct body and on its definition. It works for [static methods](/reference/functions#static-methods) and instance methods alike, and each call instantiates it like a generic function. For `x.m(...)` the type arguments come from the written arguments (and the target type), never from `self`:

```c
struct Node {
    void* ctx;
    int   tag;
    generic<T> static struct Node new(&T ctx, int tag);   // a generic constructor
    generic<T> int on(fn int(&T, int) cb, &T data);       // a generic callback registration
};

generic<T>
struct Node Node::new(&T ctx, int tag) { ... }
generic<T>
int Node::on(fn int(&T, int) cb, &T data) { return cb(data, self.tag); }

struct Node n = Node::new(&point, 2);   // T = Pt
n.on(handler, &point);                  // T = Pt, from cb and data
```

The struct body and the definition must agree on the number of type parameters. Keep the definitions in the header, as for any generic function. A generic struct's static methods can add parameters of their own, but its instance methods can't yet.

### Type arguments are inferred from the target {#type-arguments-are-inferred-from-the-target}

There is no `f<int>(...)` call syntax; type arguments are always inferred. Arguments are matched against the parameters first; any type parameter still unbound is then taken from the call's *target type* — the declared type of the variable being initialized or assigned, or the function's return type in a `return` statement:

```c
struct Vec<int> v = std::Vec::new();                  // T from the declaration
struct HashMap<int, long long> m = std::HashMap::new();   // K and V likewise
?&heap Packet p = std::alloc_t(&pool);               // T = Packet (A = PoolAllocator, from &pool)
```

A call with no target (a bare statement, a `match` subject, an argument to another generic) can't infer a parameter that appears only in the return type: bind it to a typed variable first.

A bare `null` argument matched against a nullable-reference parameter (`?&T`) binds nothing, and `T` comes from the other arguments: `std::thread_create(&tid, worker, null)` takes `T` from `worker`'s signature.

Inference only proves that the arguments fit the parameter *pattern*. Each argument is then checked against its instantiated parameter type exactly as for a non-generic call — passing a struct value where `&T` is expected is an error, not a silent copy.

### Nested type arguments {#nested-type-arguments}

Type arguments nest freely, and `>>` closes two lists: `struct NodePool<struct ListNode<T>>`, `struct Vec<struct Vec<int>>`. A template can mention its own instance (`?&heap struct ListNode<T> next;` inside `ListNode`), and arrays of a type parameter are allowed in a generic struct (`V vals[8];`).

### Compile-time dispatch through a type parameter {#compile-time-dispatch-through-a-type-parameter}

A type parameter can stand for a *policy* type whose methods the generic code calls. After monomorphization the call is a direct call to that type's method, which the optimizer inlines — the zero-cost alternative to passing a function pointer:

```c
generic<T> struct Less { int unused_; int cmp(const &T a, const &T b) const; };
generic<T> int Less::cmp(const &T a, const &T b) const { return *a < *b ? -1 : (*a > *b ? 1 : 0); }

generic<K, V, C>          // C: any type with a matching cmp method
struct BST { C ord; /* ... */ int insert(K key, V val); };

// inside BST::insert:  int c = self.ord.cmp(&key, &n.key);   // inlined for Less<int>
```

`std/cmp.h` provides `Less<T>`, `Greater<T>` and `FnOrder<T>` (a run-time comparator); `BST<K, V, C>` and `vec_sort_by` use them. Combine with `if const` to select code by a property of the type argument at compile time; the dead branch isn't even type-checked:

```c
generic<K>
unsigned int hash_key(const &K key) {
    if const (sizeof(K) == 4UL || sizeof(K) == 8UL) {
        // ... hash the key as one machine word ...
    }
    // ... byte-wise fallback for other sizes ...
}
```

### Static methods through a type parameter {#static-methods-through-a-type-parameter}

Inside a generic body, `E::f(...)` calls the [static method](/reference/functions#static-methods) `f` of whatever struct `E` is bound to. Each instantiation calls its own type's method directly — no value of `E` is needed, so a type parameter can stand for a set of type-level functions:

```c
struct F32 { float v;           static float load(F32 x);  static struct F32 store(float f); };
struct F16 { unsigned short bits; static float load(F16 x); static struct F16 store(float f); };

generic<E>
float sum2(E a, E b) { return E::load(a) + E::load(b); }   // F32::load or F16::load
```

### Generic functions as values {#generic-functions-as-values}

A generic function can be used as a value wherever a function type is expected — a variable or field initializer, an assignment, an argument, a return value. Its type arguments come from that function type, so the value is the matching instantiation:

```c
generic<E> struct Node { E val; fn void(&Node<E>) back; };
generic<E> void back_relu(&Node<E> n) { ... }

struct Node<struct F16> n;
n.back = back_relu;                        // back_relu for E = F16
fn void(&Node<struct F32>) f = back_relu;  // ... and for E = F32
```

Passed to another generic function (`link(&n, back_relu)`), it is skipped while that call's type arguments are inferred from the other arguments, then instantiated for the resulting parameter type. If its type arguments can't be read off the target function type, that's a compile error naming the function.

## Interaction with Regions {#interaction-with-regions}

Generic types can be combined with region-qualified references:

```c
generic<T>
?T find_in_slice([]T haystack, T needle) {
    for (int i = 0; i < haystack.len; i++) {
        if (haystack[i] == needle) return some(haystack[i]);
    }
    return none;
}
```

Region qualifiers on references inside generic code follow the same rules as non-generic code. The compiler checks region safety after monomorphization.

## Limitations {#limitations}

- **No runtime dispatch**: generics are always monomorphized. There are no trait objects or dynamic dispatch.
- **No partial specialization**: you cannot provide a specialized implementation for a subset of types.
- **No default type arguments**: every type parameter must be inferred.
- **Inference is local**: `T` comes from the arguments or the call's target type — never from later uses of the result.
- **Inference doesn't unify a `T*`/`[]T` parameter against a bare fixed-size array argument** — pass an already pointer/slice-typed value instead. See [Functional Programming](/reference/functional#higher-order-functions) for a worked example of this exact case.

For everything else in SafeC's "no runtime dispatch" story — traits, the vtable-free `fn_eval` primitive, and how to build genuine heterogeneous runtime dispatch explicitly when generics alone can't — see [Polymorphism & OOP](/reference/polymorphism).
