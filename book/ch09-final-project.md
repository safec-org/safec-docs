# Chapter 9: A Final Project — a Key-Value Store

Every chapter so far has introduced one idea at a time, in isolation.
This last chapter is the opposite: one program that leans on nearly
everything the book has covered — structs and methods, `?T` and `match`,
`defer`, and the standard library's `HashMap` — to build something
small but genuinely useful: an in-memory key-value store.

## Designing the type

A key-value store needs somewhere to keep its entries and a handful of
operations on them. `std::HashMap` (specifically its string-keyed
convenience functions, `str_map_*`) does the heavy lifting; `Store`
wraps it with the operations we actually want to expose:

```c
#include <std/collections/map.h>
#include <std/collections/string.h>

struct Store {
    struct HashMap entries;

    void init();
    void set(const char* key, const char* value);
    ?struct String get(const char* key);
    int remove(const char* key);
    void free();
};
```

`get` returns `?struct String` — a copy of the stored value if the key
exists, `null` if it doesn't. That's the [Chapter 8](/book/ch08-error-handling)
pattern doing exactly its job: "this might not find anything" is right
there in the return type, and every caller has to handle both cases
before getting at the value.

## Implementing it

```c
void Store::init() {
    self.entries = std::str_map_new(sizeof(struct String));
}

void Store::set(const char* key, const char* value) {
    struct String v = std::string_from(value);
    unsafe { std::str_map_insert(&self.entries, key, (const void*)&v); }
}
```

`str_map_insert` takes its value argument as a type-erased `const void*`
(the map itself doesn't know or care that it's storing `struct String`s —
that's `Store`'s job to remember), which is exactly the kind of raw
pointer cast [Chapter 5](/book/ch05-understanding-regions) told you needs
`unsafe`. `set` itself stays a small, honest wrapper — it's the one place
that both knows the real element type and is willing to assert it.

```c
?struct String Store::get(const char* key) {
    void* slot;
    unsafe { slot = std::str_map_get(&self.entries, key); }
    if (slot == (void*)0) {
        return null;
    }
    struct String* found;
    unsafe { found = (struct String*)slot; }
    return found->clone();
}
```

`str_map_get` returns `NULL` for a missing key, plain C-style — `get`
translates that into the `?struct String` shape the rest of the program
expects, at the one boundary where the type-erased map API meets the
typed `Store` API. `.clone()` matters here: `slot` points *into* the
map's own storage, and returning that pointer directly (rather than a
copy) would hand the caller a reference whose lifetime depends on the map
never resizing or the entry never being overwritten — exactly the kind of
implicit lifetime dependency [Chapter 5](/book/ch05-understanding-regions)
is designed to make explicit instead of implicit. A cloned, independently
owned `String` sidesteps the question entirely.

```c
int Store::remove(const char* key) {
    int r;
    unsafe { r = std::str_map_remove(&self.entries, key); }
    return r;
}

void Store::free() {
    self.entries.free();
}
```

## Putting it together

```c
int main() {
    struct Store store;
    store.init();
    defer store.free();

    store.set("name", "SafeC");
    store.set("kind", "language");

    ?struct String name = store.get("name");
    match (name) {
        case none:    printf("name: (not found)\n");
        case some(v): printf("name: %s\n", v.as_ptr());
    }

    int removed = store.remove("kind");
    printf("removed kind: %d\n", removed);

    ?struct String kind = store.get("kind");
    match (kind) {
        case none:    printf("kind: (not found, as expected)\n");
        case some(v): printf("kind: %s\n", v.as_ptr());
    }

    return 0;
}
```

```
name: SafeC
removed kind: 1
kind: (not found, as expected)
```

`defer store.free();` right after `store.init();` is [Chapter
8](/book/ch08-error-handling)'s acquisition-next-to-release idiom, one
more time — by the time you're writing your own SafeC programs, this
pairing should feel automatic rather than like something you have to
remember to add.

## Where to go from here

This `Store` is deliberately small — in-memory only, no persistence, no
concurrent access. Extending it is a reasonable way to exercise
everything else this book didn't have room to cover in depth:

- **Persistence**: serialize `entries` to disk with
  [`std::csv`](/reference/generics) or [`std::json`](/stdlib/serial) on
  `free()`, load it back on `init()`.
- **Concurrency**: wrap access to `entries` with a mutex from
  [`std::sync`](/stdlib/sync) and share one `Store` across threads spawned
  with `std::spawn` (see [Concurrency](/reference/concurrency)).
- **A real arena**: if entries are short-lived and bulk-cleared together
  (a request-scoped cache, say), swap the heap-backed `String` values for
  `&arena<R>`-allocated ones and reset the arena between batches instead
  of freeing entries one at a time — [Chapter 5](/book/ch05-understanding-regions)
  covered exactly this trade-off.

From here, the [Reference](/reference/types) section is where to go for
exhaustive detail on any single feature this book introduced, and the
[Standard Library](/stdlib/) section catalogs everything `std::` has to
offer beyond the `HashMap`/`String` pair this chapter leaned on —
collections, networking, cryptography, serialization, and more, all
built on exactly the same region and safety rules you now know from the
inside.

Thanks for reading — happy building.
