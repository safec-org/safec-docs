# Namespaces

`namespace` groups functions and global variables under a qualified name,
matching C++'s `namespace` syntax. It exists mainly so the standard library
can expose its entire surface under `std::` without every internal helper
colliding with unrelated user-code identifiers of the same name.

```c
namespace std {
    int square(int x) { return x * x; }
    int counter = 41;

    namespace inner {
        int triple(int x) { return x * 3; }
    }
}

int main(void) {
    int a = std::square(6);          // qualified
    int b = std::inner::triple(4);   // nested namespace, qualified
    std::counter = std::counter + 1;

    int c = square(6);               // unqualified fallback also resolves
    return 0;
}
```

## What gets namespaced

Only **functions and global variables** are namespaced. Struct, enum, and
typedef names are deliberately *not* — every type name lives in one flat,
global namespace regardless of which `namespace { }` block it's declared
in. This is a scope decision, not an oversight: SafeC's C ABI compatibility
means a struct's name is also its mangled symbol identity for tooling and
FFI purposes, and C has no concept of namespaced types at all — keeping
types flat avoids inventing a mangling scheme with no C-side equivalent.

```c
namespace net {
    struct Packet { int len; };   // 'struct Packet' is global, NOT net::Packet
}

struct Packet p;   // works — no 'net::' qualifier needed or accepted
```

## Qualified vs. unqualified lookup

A call inside the same namespace (or in code that hasn't opened any
namespace) can reach a namespaced function or variable either by its full
qualified path or by its bare name — unqualified lookup falls back to
searching namespaced symbols when no plain-scope match exists. This is why
standard library code can call its own helpers unqualified while user code
typically calls them as `std::something`.

## Linkage

A namespaced function or variable is compiled to a single mangled symbol
(`std::foo` → `std_foo`) — namespaces are purely a *source-level*
qualification/lookup mechanism, not a runtime construct; there's no
namespace metadata, no ABI-visible namespace tag, nothing beyond the
mangled name itself. `extern` declarations inside a `namespace { }` block
are the one exception: an `extern` exists specifically to bind to a
pre-existing external symbol by its exact, unmangled name (e.g. `extern
void* memcpy(...)` inside `namespace std { }` must stay plain `memcpy` to
link against libc), so `extern` declarations are never mangled regardless
of which namespace encloses them. Methods (`Type::method(...)`) are
similarly excluded — they're already dispatched through their own
`methodOwner`-qualified mechanism, so double-mangling would be redundant.
