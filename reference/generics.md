# Generics

SafeC generics are compile-time only. Every generic function is fully monomorphized — the compiler generates a separate copy for each concrete type used. There are no vtables, no type erasure, and no runtime dispatch.

::: warning No generic structs
Only **functions** can be generic (`generic<T> T max(T a, T b) { ... }`). `generic<T> struct Pair { T first; T second; };` is not supported — it fails to parse. The standard library's own generic-looking containers (`Vec`, `HashMap`, `BST`, ...) work around this with a `void*`-based struct plus `generic<T>` *wrapper functions* for type-safe access (`vec_push_t<T>`, `map_get_t<T>`, ...) — see [Standard Library Overview](/stdlib/#generic-pattern) for the pattern. If you're looking for `Pair<T>`/`Container<T>`-style generic structs, they aren't there; write the type-erased struct + typed wrapper functions instead.
:::

## Generic Functions

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

## Traits and Constrained Generics

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

### Declaring a Trait

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

### Built-in Traits

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

## Variadic Generics

SafeC supports variadic type packs with `generic<T...>`. A variadic generic parameter accepts any number of arguments of any types, and `sizeof...(T)` gives the pack's size:

```c
generic<T...>
unsigned long count_args(T... args) {
    return sizeof...(T);
}

unsigned long n = count_args(1, 2.0, 'a');   // n is 3
```

::: warning No pack expansion yet
There's currently no way to *expand* a parameter pack back out into another call's argument list (no `args...` forwarding, e.g. into a variadic C function like `printf`) — only declaring the pack and asking for its size with `sizeof...(T)` are supported. A `generic<T...>` function's only real use today is arity/type-counting logic over the pack, not forwarding it somewhere else.
:::

## Monomorphization

When the compiler encounters a call to a generic function, it:

1. **Infers** the type argument(s) from the call-site argument types
2. **Deep-clones** the function AST, substituting type parameters with concrete types
3. **Mangles** the name: `max<int>` becomes `__safec_max_int`
4. **Analyzes** the monomorphized copy through the full semantic analysis pipeline
5. **Emits** code for each distinct instantiation

Generic function bodies are **skipped** during the first semantic analysis pass — they are only type-checked after monomorphization with concrete types.

### Name Mangling

Monomorphized functions follow the pattern `__safec_<name>_<type>`:

| SafeC | Mangled Name |
|-------|-------------|
| `max<int>` | `__safec_max_int` |
| `max<double>` | `__safec_max_double` |
| `Pair<int>` | `__safec_Pair_int` |

### Code Size

Each unique instantiation generates separate code. If you instantiate `max` with 5 different types, 5 separate functions are emitted. This trades binary size for runtime performance (no indirection).

## Generic Methods

Structs with generic type parameters can define methods that use the type parameter:

```c
generic<T>
struct Container {
    T value;
    int count;

    T get() const;
    void set(T new_value);
};

generic<T>
T Container<T>::get() const {
    return self.value;
}

generic<T>
void Container<T>::set(T new_value) {
    self.value = new_value;
}
```

## Interaction with Regions

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

## Limitations

- **No runtime dispatch**: generics are always monomorphized. There are no trait objects or dynamic dispatch.
- **No partial specialization**: you cannot provide a specialized implementation for a subset of types.
- **No default type arguments**: every type parameter must be inferred or explicitly provided.
- **Inference is call-site only**: the compiler infers `T` from function arguments. It does not infer from the return type.
