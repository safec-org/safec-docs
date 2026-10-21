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

Traits in SafeC are **structural** ("duck-typed"), not nominal — there's no `impl Trait for Type` block. A struct satisfies a trait automatically as soon as it defines methods with matching names and signatures:

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

### Type arguments are inferred from the target {#type-arguments-are-inferred-from-the-target}

There is no `f<int>(...)` call syntax; type arguments are always inferred. Arguments are matched against the parameters first; any type parameter still unbound is then taken from the call's *target type* — the declared type of the variable being initialized or assigned:

```c
struct Vec<int> v = std::vec_new();                  // T from the declaration
struct HashMap<int, long long> m = std::map_new();   // K and V likewise
?&heap Packet p = std::pool_alloc_t(&pool);          // T = Packet
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
