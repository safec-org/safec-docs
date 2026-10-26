# Deterministic Allocators

`std/alloc/` provides four allocators that manage one fixed buffer — either one you supply (`X::over`, any region: static, stack, arena) or one they allocate (`X::new`). None of them calls `malloc` after construction. Construction is a [static method](/reference/functions#static-methods) on the type; from outside `namespace std`, call it as `std::BumpAllocator::new(...)`.

```c
#include <std/alloc/bump.h>
#include <std/alloc/slab.h>
#include <std/alloc/pool.h>
#include <std/alloc/tlsf.h>
#include <std/alloc/allocator.h>   // the traits, alloc_t and CountingAllocator (each header above includes it)
```

## Comparison {#comparison}

| Allocator | Alloc | Free | Best for |
|-----------|-------|------|----------|
| `BumpAllocator` | O(1) | reset-only | per-frame arenas, scratch buffers |
| `SlabAllocator` | O(1) | O(1) | many same-sized objects (packets, nodes) |
| `PoolAllocator` | O(blocks), first fit | O(blocks), coalescing | small heaps with varied sizes |
| `TlsfAllocator` | O(1) | O(1) | general-purpose real-time heap |

## Allocators as traits {#allocator-traits}

`std/alloc/allocator.h` names what an allocator can do as small [traits](/reference/generics#composing-traits), one capability each, and composes them into the two roles generic code asks for. Generic code is bounded by the smallest trait it uses, so it works with every allocator that has that capability. Each call is resolved at compile time — there is no dispatch at run time.

```c
trait Allocate {
    ?&heap unsigned char alloc(unsigned long size, unsigned long align);   // null when it can't
}
trait Deallocate {
    void dealloc(?&heap unsigned char ptr);                                // null is a no-op
}
trait Resettable {
    void reset();                                                 // frees every block at once
}
trait Allocator      : Allocate + Deallocate {}
trait ArenaAllocator : Allocate + Resettable {}
```

`alloc` returns `size` uninitialised bytes aligned to `align` (a power of two; `0` counts as `1`), or `null` when the allocator is out of space or doesn't provide that size or alignment. `dealloc` gives back a block `alloc` returned; on an allocator that can't free one block (bump) it is a no-op. `reset` frees every block at once, and blocks handed out before become invalid.

| Trait | Composed of | Satisfied by |
|---|---|---|
| `Allocate` | `alloc(size, align)` | every allocator below |
| `Deallocate` | `dealloc(ptr)` | every allocator below |
| `Resettable` | `reset()` | `BumpAllocator`, `PoolAllocator`, `SlabAllocator`, `TlsfAllocator` |
| `Allocator` | `Allocate + Deallocate` | `BumpAllocator` (no-op `dealloc`), `PoolAllocator`, `SlabAllocator`, `TlsfAllocator`, [`SecureAllocator`](/stdlib/crypto#secureallocator), `CountingAllocator<A>` |
| `ArenaAllocator` | `Allocate + Resettable` | `BumpAllocator`, `PoolAllocator`, `SlabAllocator`, `TlsfAllocator` |

| Function / struct | Bound | Does |
|---|---|---|
| [`alloc_t(&a)`, `alloc_array_t(&a, n)`](#typed) | `Allocate` | one `T` / `n` `T`s, aligned for `T` |
| [`dealloc_t(&a, obj)`](#typed) | `Deallocate` | gives back a block from `alloc_t` / `alloc_array_t` |
| [`CountingAllocator<A>`](#counting-allocator) | `Allocator` | counts the calls to the allocator it wraps; an `Allocator` itself |

What each allocator does with `align` and `dealloc`:

| Allocator | `align` honoured | `dealloc` |
|-----------|------------------|-----------|
| `BumpAllocator` | any power of two (the address is aligned, padding the arena) | no-op — `reset()` frees everything |
| `SlabAllocator` | up to the largest power of two dividing `obj_size`, at most 8 (`max_align()`); also `null` when `size > obj_size` | returns the slot |
| `PoolAllocator` | up to 8 | coalesces with free neighbours |
| `TlsfAllocator` | up to 8 | coalesces with physical neighbours |

Beyond those, `alloc` returns `null` rather than a misaligned block. Pool, slab and TLSF bounds assume an 8-aligned buffer: always true for `X::new`; for `X::over` it is the buffer you pass.

The example writes a list builder once against `Allocate` and a per-frame pass once against `ArenaAllocator`, runs both over a bump arena and a pool, and counts a workload on the pool:

```c
#include <std/alloc/pool.h>
#include <std/alloc/bump.h>
#include <std/io.h>

struct Node { int value; ?&heap Node next; };

// Builds a list of n nodes from any allocator that can allocate.
generic<A: Allocate>
?&heap Node build_list(&A a, int n) {
    ?&heap Node head = null;
    for (int i = 0; i < n; i = i + 1) {
        ?&heap Node nd = std::alloc_t(a);
        match (nd) {
            case some(x): { x.value = i; x.next = head; head = x; }
            case null:    { return head; }
        }
    }
    return head;
}

// One pass of work per frame: allocate freely, then free it all at once.
generic<A: ArenaAllocator>
int frame(&A a, int n) {
    ?&heap Node l = build_list(a, n);
    int sum = 0;
    while (1) {
        match (l) {
            case some(x): { sum = sum + x.value; l = x.next; }
            case null:    { a.reset(); return sum; }
        }
    }
    return sum;
}

int main() {
    struct BumpAllocator bump = std::BumpAllocator::new(4096UL);
    struct PoolAllocator pool = std::PoolAllocator::new(4096UL);
    int a = frame(&bump, 10);            // A = BumpAllocator
    int b = frame(&pool, 10);            // A = PoolAllocator

    // Count what a workload does on the pool.
    struct CountingAllocator<PoolAllocator> c = std::CountingAllocator::over(&pool);
    ?&heap Node one = std::alloc_t(&c);
    match (one) { case some(x): std::dealloc_t(&c, x); case null: { } }
    std::println_int((long long)c.allocs);   // 1
    std::println_int((long long)c.live());   // 0

    bump.destroy();
    pool.destroy();
    return (a == 45 && b == 45 && c.live() == 0UL) ? 0 : 1;
}
```

### Typed allocation {#typed}

Use the **typed helpers** in preference to the byte-level methods. `alloc_t` and `alloc_array_t` need only `Allocate`, `dealloc_t` only `Deallocate`. `T` comes from the variable you assign to, and the result is a nullable reference (`null` when the allocator can't) that you narrow before use.

```c
generic<T, A: Allocate>   ?&heap T alloc_t(&A a);                         // one T, aligned for T
generic<T, A: Allocate>   ?&heap T alloc_array_t(&A a, unsigned long n);  // n Ts
generic<T, A: Deallocate> void     dealloc_t(&A a, &heap T obj);
```

```c
struct PoolAllocator pool = std::PoolAllocator::new(4096UL);
?&heap Packet p = std::alloc_t(&pool);                 // T = Packet
?&heap float xs = std::alloc_array_t(&pool, 64UL);     // 64 floats
match (p) { case some(x): std::dealloc_t(&pool, x); case null: { } }
```

`alloc_array_t` aborts with a diagnostic if `n * sizeof(T)` overflows (`checked_mul_size`). The memory is uninitialised. These are distinct from [`std::alloc` / `std::dealloc`](/stdlib/mem), which allocate from the general-purpose heap and take no allocator.

The byte-level methods remain for code that genuinely handles untyped bytes, such as a custom container's storage.

`destroy()` frees the buffer only if the allocator allocated it (`X::new`); for `X::over` it just forgets the buffer, which stays yours.

### CountingAllocator {#counting-allocator}

A [bounded generic struct](/reference/generics#bounded-generic-structs) that borrows any `Allocator`, forwards every call to it and counts them. It is an `Allocator` itself, so it slots in wherever the allocator it wraps would — including around another `CountingAllocator`. Use it for leak checks in tests, or to size an arena or pool from a real workload.

```c
generic<A: Allocator>
struct CountingAllocator {
    &A            inner;      // the allocator doing the work (not owned)
    unsigned long allocs;     // alloc calls that returned a block
    unsigned long frees;      // dealloc calls with a non-null block
    unsigned long failures;   // alloc calls that returned null
    unsigned long bytes;      // bytes requested by the successful allocs

    static struct CountingAllocator<A> over(&A inner);
    ?&heap unsigned char alloc(unsigned long size, unsigned long align);
    void          dealloc(?&heap unsigned char ptr);
    unsigned long live() const;   // blocks handed out and not given back yet
};
```

```c
struct PoolAllocator pool = std::PoolAllocator::new(65536UL);
struct CountingAllocator<PoolAllocator> c = std::CountingAllocator::over(&pool);
run_workload(&c);                       // any generic<A: Allocator> code
if (c.live() != 0UL) { /* blocks never given back */ }
```

`CountingAllocator` has no `reset`, so it is not an `ArenaAllocator`: reset the allocator it wraps directly.

### Your own allocator {#custom}

Traits are structural: a type conforms by having the methods, and there is nothing to declare. A type with `alloc` and `dealloc` of the trait signatures is an `Allocator`, so `alloc_t`, `alloc_array_t`, `dealloc_t`, `CountingAllocator` and any `generic<A: Allocator>` code accept it; adding `reset()` makes it an `ArenaAllocator` too. For example, the general-purpose heap with a count of live blocks:

```c
#include <std/alloc/allocator.h>

struct CountingHeap {
    unsigned long live;
    ?&heap unsigned char alloc(unsigned long size, unsigned long align);
    void        dealloc(?&heap unsigned char ptr);
};

?&heap unsigned char CountingHeap::alloc(unsigned long size, unsigned long align) {
    if ((align & (align - 1UL)) != 0UL || align > 16UL) return null;   // std::alloc gives 16
    ?&heap unsigned char p = std::alloc(size);
    if (!p.is_null()) self.live = self.live + 1UL;
    return p;
}

void CountingHeap::dealloc(?&heap unsigned char ptr) {
    if (ptr.is_null()) return;
    std::dealloc(ptr);
    self.live = self.live - 1UL;
}

struct Point { int x; int y; };

int main() {
    struct CountingHeap h;
    h.live = 0UL;
    ?&heap Point p = std::alloc_t(&h);                 // through the trait
    match (p) { case some(x): std::dealloc_t(&h, x); case null: { } }
    return (int)h.live;                                // 0
}
```

---

## BumpAllocator {#bump}

A linear allocator: allocation advances an offset, and the whole arena is reclaimed at once with `reset()`. No per-object free.

```c
struct BumpAllocator {
    unsigned long used;
    unsigned long cap;

    static struct BumpAllocator over([]unsigned char buffer);  // over your buffer
    static struct BumpAllocator new(unsigned long cap);                          // allocates cap bytes

    ?&heap unsigned char alloc(unsigned long size, unsigned long align);   // byte level; null if full
    void          dealloc(?&heap unsigned char ptr);   // no-op (one block can't be freed)
    void          reset();                    // every earlier allocation becomes invalid
    unsigned long remaining() const;
    void          destroy();
};
```

```c
#include <std/alloc/bump.h>
#include <std/io.h>

struct Particle { float x; float y; int life; };

static unsigned char g_frame[4096];          // per-frame scratch, no heap

int main() {
    struct BumpAllocator a = std::BumpAllocator::over(g_frame[..]);
    ?&heap Particle p = std::alloc_t(&a);
    ?&heap int counts = std::alloc_array_t(&a, 16UL);
    match (p) {
        case some(x): { x.x = 1.0f; x.y = 2.0f; x.life = 30; }
        case null:    std::println("arena full");
    }
    std::print("remaining: ");
    std::println_int((long long)a.remaining());
    a.reset();                               // next frame starts empty
    return counts.is_null() ? 1 : 0;
}
```

---

## SlabAllocator {#slab}

Fixed-size objects from a free list embedded in the buffer: O(1) alloc and free. A heap-backed slab (`SlabAllocator::new`) tags every object and aborts on a double free or a foreign pointer.

```c
struct SlabAllocator {
    unsigned long obj_size;

    // over your buffer of obj_size * count bytes (no double-free tags)
    static struct SlabAllocator over([]unsigned char buffer, unsigned long obj_size);
    static struct SlabAllocator new(unsigned long obj_size, unsigned long count);

    // byte level: one object for size <= obj_size bytes; null if full, size > obj_size
    // or align > max_align()
    ?&heap unsigned char alloc(unsigned long size, unsigned long align);
    void          dealloc(?&heap unsigned char ptr);  // a block alloc() returned; null is a no-op
    void          reset();                   // frees every slot at once
    unsigned long available() const;         // free slots
    unsigned long max_align() const;         // largest power of two dividing obj_size, <= 8
    void          destroy();
};
```

`SlabAllocator::over` takes a buffer of `obj_size * count` bytes. Slots are `obj_size` rounded up to a multiple of 8 (at least 8, since a free slot holds the free-list link) and start 8-byte aligned, so `cap` can come out below `count` when that rounding doesn't fit; `SlabAllocator::new` allocates room for every slot.

```c
#include <std/alloc/slab.h>
#include <std/io.h>

struct Packet { unsigned char data[64]; unsigned long len; };

int main() {
    struct SlabAllocator sa = std::SlabAllocator::new(sizeof(struct Packet), 32UL);
    ?&heap Packet p1 = std::alloc_t(&sa);
    ?&heap Packet p2 = std::alloc_t(&sa);
    std::println_int((long long)sa.available());   // 30
    match (p2) {
        case some(p): std::dealloc_t(&sa, p);
        case null: { }
    }
    std::println_int((long long)sa.available());   // 31
    sa.destroy();
    return p1.is_null() ? 1 : 0;
}
```

---

## PoolAllocator {#pool}

A variable-size, first-fit allocator: each block carries a small header, and freeing coalesces adjacent free blocks. Simple and compact, but allocation and free walk the block list — use TLSF when you need O(1).

```c
struct PoolAllocator {
    unsigned long cap;

    static struct PoolAllocator over([]unsigned char buffer);  // over your buffer (8-aligned)
    static struct PoolAllocator new(unsigned long cap);

    ?&heap unsigned char alloc(unsigned long size, unsigned long align);   // byte level; null if no block fits or align > 8
    void          dealloc(?&heap unsigned char ptr);  // a block alloc() returned; null is a no-op
    void          reset();                   // the whole buffer is one free block again
    unsigned long available() const;         // total free bytes
    void          destroy();
};
```

---

## TlsfAllocator {#tlsf}

Two-Level Segregated Fit: a two-level bitmap of size classes finds a fitting free block in O(1), and freeing coalesces with physical neighbours in O(1). This is what `std/heap.h` uses on freestanding targets.

```c
struct TlsfAllocator {
    unsigned long cap;

    static struct TlsfAllocator over([]unsigned char buffer);  // over your buffer
    static struct TlsfAllocator new(unsigned long cap);

    ?&heap unsigned char alloc(unsigned long size, unsigned long align);   // byte level; null if exhausted or align > 8
    void          dealloc(?&heap unsigned char ptr);  // a block alloc() returned; null is a no-op
    void          reset();                   // the whole buffer is one free block again
    void          destroy();
};
```

```c
#include <std/alloc/tlsf.h>

int main() {
    struct TlsfAllocator ta = std::TlsfAllocator::new(65536UL);   // 64 KiB
    ?&heap double a = std::alloc_array_t(&ta, 100UL);
    ?&heap double b = std::alloc_array_t(&ta, 200UL);
    match (b) { case some(x): std::dealloc_t(&ta, x); case null: { } }   // coalesced
    ?&heap double c = std::alloc_array_t(&ta, 180UL);                    // reuses b's space
    match (a) { case some(x): std::dealloc_t(&ta, x); case null: { } }
    match (c) { case some(x): std::dealloc_t(&ta, x); case null: { } }
    ta.destroy();
    return 0;
}
```

### When to use TLSF {#when-to-use-tlsf}

- Firmware with hard real-time constraints (audio, motor control)
- Any context where `malloc` response time must be bounded
- Long-running embedded applications where heap fragmentation is a concern

---

Index sizes: `TLSF_FL_MAX` (32 first-level classes) × `TLSF_SL_COUNT` (16 second-level subdivisions).

## Choosing an Allocator {#choosing-an-allocator}

```
Per-frame scratch work?        → BumpAllocator (reset every frame)
Many identical objects?        → SlabAllocator
Fixed-block mixed content?     → PoolAllocator
Variable-size, RT guaranteed?  → TlsfAllocator
```

Code that should work with any of them takes `generic<A: Allocator>` (or `ArenaAllocator` when it frees with `reset()`), so the choice can change without touching it.
