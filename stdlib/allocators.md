# Deterministic Allocators

`std/alloc/` provides four allocators that manage one fixed buffer — either one you supply (`*_init`, any region: static, stack, arena) or one they allocate (`*_new`). None of them calls `malloc` after construction.

```c
#include <std/alloc/bump.h>
#include <std/alloc/slab.h>
#include <std/alloc/pool.h>
#include <std/alloc/tlsf.h>
```

## Comparison {#comparison}

| Allocator | Alloc | Free | Best for |
|-----------|-------|------|----------|
| `BumpAllocator` | O(1) | reset-only | per-frame arenas, scratch buffers |
| `SlabAllocator` | O(1) | O(1) | many same-sized objects (packets, nodes) |
| `PoolAllocator` | O(blocks), first fit | O(blocks), coalescing | small heaps with varied sizes |
| `TlsfAllocator` | O(1) | O(1) | general-purpose real-time heap |

## Typed allocation {#typed}

Every allocator has a **typed layer** — use it in preference to the byte-level methods. `T` comes from the variable you assign to; the result is a nullable reference (`null` when the allocator is exhausted) that you narrow before use.

```c
generic<T> ?&heap T bump_alloc_t(&BumpAllocator a);                     // aligned for T
generic<T> ?&heap T bump_alloc_array_t(&BumpAllocator a, unsigned long n);
generic<T> ?&heap T slab_alloc_t(&SlabAllocator s);                     // null if T > obj_size
generic<T> void     slab_free_t(&SlabAllocator s, &heap T obj);
generic<T> ?&heap T pool_alloc_t(&PoolAllocator p);
generic<T> void     pool_free_t(&PoolAllocator p, &heap T obj);
generic<T> ?&heap T tlsf_alloc_t(&TlsfAllocator t);
generic<T> ?&heap T tlsf_alloc_array_t(&TlsfAllocator t, unsigned long n);
generic<T> void     tlsf_free_t(&TlsfAllocator t, &heap T obj);
```

The byte-level methods (`alloc(size)`, `dealloc(ptr)`, …) remain for code that genuinely handles untyped bytes, such as a custom container's storage.

`destroy()` frees the buffer only if the allocator allocated it (`*_new`); for `*_init` it just forgets the buffer, which stays yours.

---

## BumpAllocator {#bump}

A linear allocator: allocation advances an offset, and the whole arena is reclaimed at once with `reset()`. No per-object free.

```c
struct BumpAllocator {
    unsigned long used;
    unsigned long cap;

    &heap void    alloc(unsigned long size, unsigned long align);   // byte level; null if full
    void          reset();                    // every earlier allocation becomes invalid
    unsigned long remaining() const;
    void          destroy();
};

struct BumpAllocator bump_init(&unsigned char buffer, unsigned long cap);
struct BumpAllocator bump_new(unsigned long cap);
```

```c
#include <std/alloc/bump.h>
#include <std/io.h>

struct Particle { float x; float y; int life; };

static unsigned char g_frame[4096];          // per-frame scratch, no heap

int main() {
    struct BumpAllocator a = std::bump_init(&g_frame[0], 4096UL);
    ?&heap Particle p = std::bump_alloc_t(&a);
    ?&heap int counts = std::bump_alloc_array_t(&a, 16UL);
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

Fixed-size objects from a free list embedded in the buffer: O(1) alloc and free. A heap-backed slab (`slab_new`) tags every object and aborts on a double free or a foreign pointer.

```c
struct SlabAllocator {
    unsigned long obj_size;
    &heap void    alloc();                    // byte level; null if full
    void          dealloc(void* ptr);
    unsigned long available() const;          // free slots
    void          destroy();
};

struct SlabAllocator slab_init(&unsigned char buffer, unsigned long obj_size, unsigned long count);
struct SlabAllocator slab_new(unsigned long obj_size, unsigned long count);
```

```c
#include <std/alloc/slab.h>
#include <std/io.h>

struct Packet { unsigned char data[64]; unsigned long len; };

int main() {
    struct SlabAllocator sa = std::slab_new(sizeof(struct Packet), 32UL);
    ?&heap Packet p1 = std::slab_alloc_t(&sa);
    ?&heap Packet p2 = std::slab_alloc_t(&sa);
    std::println_int((long long)sa.available());   // 30
    match (p2) {
        case some(p): std::slab_free_t(&sa, p);
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
    &heap void    alloc(unsigned long size);  // byte level; null if no block fits
    void          dealloc(void* ptr);
    unsigned long available() const;         // total free bytes
    void          destroy();
};

struct PoolAllocator pool_init(&unsigned char buffer, unsigned long cap);
struct PoolAllocator pool_new(unsigned long cap);
```

---

## TlsfAllocator {#tlsf}

Two-Level Segregated Fit: a two-level bitmap of size classes finds a fitting free block in O(1), and freeing coalesces with physical neighbours in O(1). This is what `std/heap.h` uses on freestanding targets.

```c
struct TlsfAllocator {
    unsigned long cap;
    &heap void    alloc(unsigned long size);  // byte level; null if exhausted
    void          free(&heap void ptr);
    void          destroy();
};

struct TlsfAllocator tlsf_init(&unsigned char buffer, unsigned long cap);
struct TlsfAllocator tlsf_new(unsigned long cap);
```

```c
#include <std/alloc/tlsf.h>

int main() {
    struct TlsfAllocator ta = std::tlsf_new(65536UL);   // 64 KiB
    ?&heap double a = std::tlsf_alloc_array_t(&ta, 100UL);
    ?&heap double b = std::tlsf_alloc_array_t(&ta, 200UL);
    match (b) { case some(x): std::tlsf_free_t(&ta, x); case null: { } }   // coalesced
    ?&heap double c = std::tlsf_alloc_array_t(&ta, 180UL);                  // reuses b's space
    match (a) { case some(x): std::tlsf_free_t(&ta, x); case null: { } }
    match (c) { case some(x): std::tlsf_free_t(&ta, x); case null: { } }
    ta.destroy();
    return 0;
}
```

### When to use TLSF

- Firmware with hard real-time constraints (audio, motor control)
- Any context where `malloc` response time must be bounded
- Long-running embedded applications where heap fragmentation is a concern

---

## Choosing an Allocator

```
Per-frame scratch work?        → BumpAllocator (reset every frame)
Many identical objects?        → SlabAllocator
Fixed-block mixed content?     → PoolAllocator
Variable-size, RT guaranteed?  → TlsfAllocator
```
