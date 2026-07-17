# C Superset Compatibility

SafeC is a superset of C: real-world C code — including patterns C itself
inherited from decades of systems programming — compiles unmodified except
where a construct is fundamentally memory-unsafe (raw pointer arithmetic,
unchecked casts, ...), which SafeC requires wrapping in `unsafe { }` rather
than rejecting outright. This page covers the C constructs SafeC accepts
verbatim, beyond the core language described in [Types](/reference/types),
[Functions](/reference/functions), and [Memory & Regions](/reference/memory).

## `__attribute__((...))`

GCC/Clang attribute syntax is accepted in every position real C code uses
it — prefix, suffix, on a struct tag, or trailing a struct body — and
recognized attributes affect codegen; unrecognized ones are tolerated
(parsed and ignored) rather than rejected, so headers written for GCC/Clang
don't need edits to import.

```c
__attribute__((always_inline)) static int add_one(int x) { return x + 1; }

int subtract_one(int x) __attribute__((pure));

struct __attribute__((packed)) PackedPoint {
    unsigned char tag;
    int value;
};

int g_aligned_val __attribute__((aligned(16))) = 7;
```

Recognized: `always_inline`, `pure`, `packed`, `aligned(N)` (bare
`aligned` defaults to the target's max useful alignment), `section("name")`
(same effect as SafeC's native `section(...)` — see
[Bare-Metal](/reference/baremetal)).

## C-Style Function Pointers

Alongside SafeC's native `fn RetType(Params)` syntax, the classic C
declarator form works everywhere a type can appear: typedefs, globals,
struct fields, and function parameters.

```c
typedef int (*BinOp)(int, int);

int (*g_op)(int, int) = mul_impl;

struct Handler {
    void (*on_event)(int);
};

static int apply(int (*callback)(int, int), int x, int y) {
    return callback(x, y);
}
```

## Bitfields

```c
struct Flags {
    unsigned int a : 4;
    unsigned int b : 12;
    unsigned int c : 16;
    int          sc : 4;   // signed bitfield
    int          tag;      // ordinary field after bitfields, its own slot
};
```

Bitfields sharing a storage unit are packed into one integer slot with
shift/mask codegen on every read and write, including compound assignment
(`f.a += 1;`, `f.a &= 0xF;`) — a plain store on one bitfield never clobbers
its siblings packed into the same unit.

## Designated Initializers

```c
struct Point { int x; int y; int z; };
struct Point p = { .y = 5, .x = 1, .z = 9 };   // any order

int arr[6]        = { 1, 2, [4] = 40, 5 };      // index 3 zero-filled
int global_arr[5] = { [1] = 10, [3] = 30 };     // 0, 2, 4 zero-filled
```

Designators may be mixed with positional initializers; array designators
(`[N] = value`) and struct designators (`.field = value`) both resolve
through the same slot-mapping Sema uses for plain positional init, so
partially-specified aggregates are zero-filled exactly like C.

## Flexible Array Members

```c
struct Msg {
    int len;
    unsigned char data[];   // must be the last field
};

unsafe {
    struct Msg* m = (struct Msg*)malloc(sizeof(struct Msg) + 4UL);
    m->data[0] = (unsigned char)10;
}
```

`sizeof(struct Msg)` excludes the flexible member, as in C — the caller is
responsible for over-allocating and indexing past the struct's fixed
portion, which is why the access above needs `unsafe`.

## Anonymous struct/union

```c
struct Variant {
    int tag;
    union {           // anonymous — members promoted to Variant's own scope
        int   as_int;
        float as_float;
    };
};

struct Nested {
    int id;
    struct {          // anonymous nested struct, same promotion
        int x;
        int y;
    };
};

struct Variant v;
v.as_int = 42;   // no intermediate member name needed
```

## Compound Literals

```c
struct Point { int x; int y; };

int total = sum_point((struct Point){3, 4});
struct Point p2 = (struct Point){.x = 10, .y = 20};   // designators work here too
```

## `_Generic`

C11 type-generic selection — dispatches on the *type* of the controlling
expression at compile time, distinct from SafeC's own
[`generic<T>`](/reference/generics) (which monomorphizes a function body
per instantiated type; `_Generic` instead picks one of several already-
written expressions based on a single argument's type):

```c
int describe(int i, double d) {
    int r1 = _Generic(i, int: 100, double: 200, default: -1);   // 100
    int r2 = _Generic(d, int: 100, double: 200, default: -1);   // 200
    return r1 + r2;
}
```

## Variable-Length Arrays

VLAs are supported, but only inside `unsafe { }` — a runtime-sized stack
allocation is exactly the kind of unchecked-size operation the safe
fragment excludes by design (see [Safety](/reference/safety)).

```c
int sum_vla(int n) {
    int total = 0;
    unsafe {
        int arr[n];             // n need not be a compile-time constant
        for (int i = 0; i < n; i++) arr[i] = i * 2;
        for (int i = 0; i < n; i++) total += arr[i];
    }
    return total;
}
```
