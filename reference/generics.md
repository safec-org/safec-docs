# Generics

SafeC generics are compile-time only. Every generic function or struct is fully monomorphized — the compiler generates a separate copy for each concrete type used. There are no vtables, no type erasure, and no runtime dispatch.

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

## Generic Structs

Structs can also be parameterized by type:

```c
generic<T>
struct Pair {
    T first;
    T second;
};
```

```c
Pair<int> p = {1, 2};
Pair<double> q = {3.14, 2.71};
```

Generic structs follow the same monomorphization rules as generic functions.

## Constrained Generics

Type parameters can be constrained with traits to restrict what types are accepted:

```c
generic<T: Numeric>
T clamp(T val, T lo, T hi) {
    if (val < lo) return lo;
    if (val > hi) return hi;
    return val;
}
```

The `Numeric` constraint ensures `T` supports comparison operators. The compiler rejects instantiation with types that do not satisfy the constraint.

### Built-in Constraints

| Constraint | Required Operations |
|------------|-------------------|
| `Numeric` | Arithmetic (`+`, `-`, `*`, `/`) and comparison (`<`, `>`, `==`) |

Constraint satisfaction is checked after monomorphization — the compiler verifies that the concrete type supports all operations used in the generic body.

## Variadic Generics

SafeC supports variadic type packs with `generic<T...>`. A variadic generic accepts any number of type arguments.

```c
generic<T...>
int safe_printf(const char *fmt, T... args) {
    // type-safe printf: each arg's type is known at compile time
    return __builtin_printf(fmt, args...);
}
```

### Pack Size

Use `sizeof...(T)` to get the number of types in a variadic pack:

```c
generic<T...>
int count_args(T... args) {
    return sizeof...(T);
}

int n = count_args(1, 2.0, 'a');   // n is 3
```

### Pack Expansion

The `...` suffix expands a parameter pack at the call site:

```c
generic<T...>
void forward_all(T... args) {
    target(args...);    // expands to target(arg0, arg1, arg2, ...)
}
```

Variadic generics are monomorphized for each unique combination of argument types.

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
