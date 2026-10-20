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
    ?&char buf = malloc(256);              // raw void* -> ?&char: implicit
    match (buf) {
        case some(b): {
            sprintf(b, "Hello, %s!", "world");   // &char -> char*: implicit
            printf("%s\n", b);
        }
        case null: { }
    }
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

## References to Raw Pointers

Every reference converts to the matching raw pointer (`&T` to `T*`) implicitly, whatever its region — `&static`, `&stack`, `&heap`, `&arena<R>` or region-less `&T`/`?&T`. A region is a compile-time fact about where the data lives; at the C boundary it simply erases. So a SafeC function that wraps a C call takes references and hands them straight to the `extern`:

```c
extern void process(int *data);
region Scratch { capacity: 1024 }

void example() {
    int buf[100];
    process(&buf[0]);                       // &stack int -> int*
    process(buf);                           // array decays to its first element
    &heap int h = new int;
    process(h);                             // &heap int -> int*
    &arena<Scratch> int a = new<Scratch> int;
    process(a);                             // &arena int -> int*
}
```

Converting to an untyped `void*` works the same way for every reference except `&stack T`: a stack address handed to C as `void*` loses both its type and its lifetime, so the compiler refuses. Take the parameter as region-less `&T` (which any `&stack T` converts to) — the usual shape for a wrapper anyway — or pass a heap, arena or static reference.

What the compiler can't check is what C does with the pointer afterwards. If the C side keeps it past the call — a registered callback context, an opaque handle — pass data that outlives that use (`&static`, heap, or an arena that isn't reset meanwhile).

### `&static T`

Static data lives for the entire program, so it is always safe to hand to C, even when C keeps it:

```c
extern int puts(const char *s);

void greet() {
    static const &char greeting = "Hello from SafeC";
    puts(greeting);             // OK: &static → raw pointer is safe
}
```

::: warning `static const &char` initializers must be local, not global
As with the same pattern in [Memory & Regions](/reference/memory), a
file-scope `static const &char greeting = "...";` fails to compile
("global initializer is not a compile-time constant expression"). Declare
it as a local `static` inside a function instead, as shown above.
:::

### `&T` / `?&T` (No Region)

A reference with **no region qualifier at all** — `&T` (non-nullable) or
`?&T` (nullable) — is the natural type for a pointer meant to cross the
`extern` boundary, especially when the C side might *retain* it past the
call returning — see [Memory & Regions](/reference/memory)'s
"Outliving References" section for the full rationale.

```c
struct Widget { int value; }

// Stand-in for 'extern int c_register_callback(struct Widget*);' — a SafeC
// function with the same shape takes the reference, C would get the address.
int c_register_callback(?&Widget w) {
    match (w) {
        case some(x): return x.value;
        case null:    return 0;
    }
    return 0;
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

## Raw Pointers from C

A raw pointer coming back from C converts implicitly to a nullable reference, `?&T` — any pointee type, including from `void*` — which you then narrow with `match` like any other `?&T`. Turning it into a non-null `&T` directly needs `unsafe`, because only you know C didn't return null.

Wrap each C entry point once, so the rest of the program only sees references:

```c
extern void *malloc(long size);
extern void free(void *ptr);

// The one place this program turns C's untyped pointer into a typed array.
?&heap int alloc_ints(unsigned long n) {
    unsafe { return (?&heap int)malloc((long)(n * sizeof(int))); }
}

void example() {
    ?&heap int data = alloc_ints(10UL);
    match (data) {
        case some(d): { *d = 42; }          // first element
        case null:    { }
    }
    free(data);                             // ?&heap int -> void*: implicit
}
```

## Passing Callbacks to C

C libraries that take function pointer callbacks work naturally because SafeC functions have C-compatible calling conventions:

```c
extern void qsort(void *base, long nmemb, long size,
                   fn int(const void*, const void*) compar);

// C calls this with two element addresses; the parameters are references,
// so the body reads them without unsafe.
int compare_ints(const &int a, const &int b) {
    return *a - *b;
}

void sort_array(&int first, long n) {
    qsort(first, n, (long)sizeof(int), compare_ints);
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
| `&stack T` passed to C as `T*` | No |
| `&stack T` passed to C as `void*` | Not allowed — go through region-less `&T` |
| `&heap T` passed to C | No |
| `&arena<R> T` passed to C | No |
| Raw pointer from C into `?&T` | No |
| Raw pointer from C into non-null `&T` | Yes |
| Dereferencing, indexing or doing arithmetic on a raw pointer | Yes |
| C function call (no refs) | No |
| Struct passed by value to C | No |
