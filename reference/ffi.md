# C Interop (FFI)

SafeC is designed for seamless interoperation with C. Region-qualified references erase to raw pointers at code generation, so there is no ABI difference between SafeC and C at the binary level.

## Extern Declarations

C functions are declared using `extern` with raw C types. Region qualifiers must not appear in extern signatures:

```c
extern int printf(const char *fmt, ...);
extern void *malloc(long size);
extern void free(void *ptr);
extern int open(const char *path, int flags);
```

## Native C Header Import

SafeC can directly import standard C headers. The compiler invokes `clang -ast-dump=json` behind the scenes to extract function and typedef declarations:

```c
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main() {
    char *buf = (char*)malloc(256);
    sprintf(buf, "Hello, %s!", "world");
    printf("%s\n", buf);
    free(buf);
    return 0;
}
```

The `CHeaderImporter` extracts:
- `FunctionDecl` nodes (function prototypes)
- `TypedefDecl` nodes (type aliases)

It automatically skips constructs that SafeC does not support:
- Function pointers in typedefs
- Objective-C block types (`^`)
- Array typedefs
- `long double`, `wchar_t`, `__int128`

Enum typedefs are converted to `typedef int name;`.

To disable native header import, pass `--no-import-c-headers`.

## Safe Coercion: `&static T` to `T*`

References with `static` region can be passed to C functions without an `unsafe` block, because static data lives for the entire program and will never become invalid:

```c
extern int puts(const char *s);

void greet() {
    static const char *greeting = "Hello from SafeC";
    puts(greeting);             // OK: &static → raw pointer is safe
}
```

This coercion is one of two implicit region-to-pointer conversions allowed outside `unsafe` — the other is `&T`/`?&T` (no region qualifier), covered next.

::: warning `static const char*` initializers must be local, not global
As with the same pattern in [Memory & Regions](/reference/memory), a
file-scope `static const char *greeting = "...";` fails to compile
("global initializer is not a compile-time constant expression"). Declare
it as a local `static` inside a function instead, as shown above.
:::

## Safe Coercion: `&T` / `?&T` (No Region) to `T*`

A reference with **no region qualifier at all** — `&T` (non-nullable) or
`?&T` (nullable) — also converts to a raw pointer without `unsafe`, in
both directions. This is the natural type for a pointer that's meant to
cross the `extern` boundary in the first place, especially when the C
side might *retain* it past the call returning (a registered callback's
context, an opaque handle) — see [Memory & Regions](/reference/memory)'s
"Outliving References" section for the full rationale.

```c
struct Widget { int value; }

// Conceptually retains 'w' past this call returning -- a real extern
// declaration would just be 'extern int c_register_callback(struct Widget*);'
int c_register_callback(struct Widget* w) {
    unsafe { return w->value; }
}

int main() {
    struct Widget local;
    local.value = 42;

    &stack Widget sref = &local;
    ?&Widget wref = sref;         // &stack Widget -> ?&Widget: implicit, no unsafe
    c_register_callback(wref);    // ?&Widget -> struct Widget*: implicit, no unsafe
    return 0;
}
```

Reading a `?&T` value back out goes through the same nullable-reference
grammar as `?&stack T`/`?&heap T`/etc — `match`, `is_null()`,
`.default(fallback)`, or an `unsafe`-gated `!`. See
[Safety](/reference/safety) for that grammar.

## Non-Static References Require Unsafe

Passing stack, heap, or arena references to C functions requires an `unsafe` block, because the compiler cannot verify that C will respect the reference's lifetime:

```c
extern void process(int *data);

void example() {
    int buf[100];

    // process(buf);            // ERROR: non-static ref to C

    unsafe {
        process(buf);           // OK: programmer takes responsibility
    }
}
```

## Raw Pointers from C Require Unsafe

Pointers received from C functions are raw and untracked. They must be handled inside `unsafe`:

```c
extern void *malloc(long size);
extern void free(void *ptr);

void example() {
    unsafe {
        int *data = (int*)malloc(10UL * sizeof(int));  // UL: no implicit int -> long widening
        data[0] = 42;
        free(data);
    }
}
```

## Passing Callbacks to C

C libraries that take function pointer callbacks work naturally because SafeC functions have C-compatible calling conventions:

```c
extern void qsort(void *base, long nmemb, long size,
                   fn int(const void*, const void*) compar);

int compare_ints(const void *a, const void *b) {
    int ia;
    int ib;
    unsafe {
        ia = *(int*)a;          // raw-pointer dereference needs unsafe
        ib = *(int*)b;
    }
    return ia - ib;
}

void sort_array(int *arr, int n) {
    unsafe {
        qsort(arr, n, sizeof(int), compare_ints);
    }
}
```

## Struct Layout Compatibility

SafeC structs use C-compatible layout by default. This means you can pass SafeC structs to C functions and receive C structs without any conversion:

```c
struct Point {
    double x;
    double y;
};

extern void draw_point(Point *p);

void example() {
    Point p = {1.0, 2.0};
    unsafe {
        draw_point(&p);
    }
}
```

## Zero-Cost Abstraction

Region qualifiers are a compile-time-only concept. At the LLVM IR level:
- `&stack int` is just `i32*`
- `&heap float` is just `float*`
- `&arena<R> T` is just `T*`
- `&T`/`?&T` (no region) is also just `T*` — identical codegen to a raw pointer, since there's no region metadata to carry in the first place

References carry `nonnull` and `noalias` LLVM attributes where applicable, enabling better optimization, but there is no runtime metadata or fat pointer overhead.

## FFI Rules Summary

| Scenario | Requires `unsafe`? |
|----------|-------------------|
| `&static T` passed to C | No |
| `&T` / `?&T` (no region) passed to C | No |
| `&stack T` passed to C | Yes |
| `&heap T` passed to C | Yes |
| `&arena<R> T` passed to C | Yes |
| Raw pointer from C | Yes |
| Raw pointer from C into `&T`/`?&T` | No |
| C function call (no refs) | No |
| Struct passed by value to C | No |
