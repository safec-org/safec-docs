# Chapter 9: A Final Project — a Key-Value Store

Every chapter so far has introduced one idea at a time, in isolation.
This last chapter is the opposite: one program that leans on nearly
everything the book has covered — structs and methods, `?T` and `match`,
`defer`, and the standard library's `HashMap` — to build something
small but genuinely useful: an in-memory key-value store.

## Designing the type

A key-value store needs somewhere to keep its entries and a handful of
operations on them. `std::StrMap<V>` — the string-keyed hash map — does
the heavy lifting; `Store` wraps it with the operations we actually want
to expose:

```c
#include <std/collections/map.h>
#include <std/collections/string.h>

struct Store {
    struct StrMap<struct String> entries;

    void init();
    void set(const &char key, const &char value);
    ?struct String get(const &char key);
    int remove(const &char key);
    void drop();
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
    self.entries = std::StrMap::new();
}

void Store::set(const &char key, const &char value) {
    self.entries.insert(key, std::String::from(value));   // replacing drops the old value
}
```

The map is generic, so it knows it stores `struct String`s — there is no
casting anywhere. The map owns copies of the keys *and* the `String`
values: inserting under a key that is already there drops the value it
replaces, so `set` is a plain insert.

```c
?struct String Store::get(const &char key) {
    match (self.entries.get(key)) {
        case some(v): return v.clone();
        case null:    return null;
    }
    return null;
}
```

`get` on the map returns `?&String`: a reference *into* the map's own
storage, or `null`. `Store::get` returns a clone rather than that
reference, because the reference is only valid until the map next resizes
or that entry is overwritten — exactly the kind of implicit lifetime
dependency [Chapter 5](/book/ch05-understanding-regions) is about. A
cloned, independently owned `String` sidesteps the question entirely.

```c
int Store::remove(const &char key) {
    match (self.entries.remove(key)) {
        case some(old): { old.drop(); return 1; }
        case none:      return 0;
    }
    return 0;
}

void Store::drop() {
    self.entries.drop();   // drops every key and value
}
```

`remove` on the map moves the value out to us, so `Store::remove` drops
it. Everything still in the map is the map's: dropping the map drops
every key and every value with it, so `Store::drop` is one call — freeing
the values yourself first (a `foreach` over them) would free them twice.

## Putting it together

```c
extern int printf(const char* fmt, ...);

int main() {
    struct Store store;
    store.init();
    defer store.drop();

    store.set("name", "SafeC");
    store.set("kind", "language");
    store.set("name", "SafeC!");      // overwrites: the old value is dropped

    ?struct String name = store.get("name");
    match (name) {
        case none:    unsafe { printf("name: (not found)\n"); }
        case some(v): { unsafe { printf("name: %s\n", v.as_ptr()); } v.free(); }
    }

    int removed = store.remove("kind");
    unsafe { printf("removed kind: %d\n", removed); }

    ?struct String kind = store.get("kind");
    match (kind) {
        case none:    unsafe { printf("kind: (not found, as expected)\n"); }
        case some(v): { unsafe { printf("kind: %s\n", v.as_ptr()); } v.free(); }
    }

    return 0;
}
```

```
name: SafeC!
removed kind: 1
kind: (not found, as expected)
```

`defer store.drop();` right after `store.init();` is [Chapter
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
  `drop()`, load it back on `init()`.
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
