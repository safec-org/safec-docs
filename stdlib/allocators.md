# Deterministic Allocators

SafeC provides four deterministic allocators in `std/alloc/`. Each has O(1) alloc and dealloc in the worst case, zero fragmentation surprises, and no hidden malloc calls.

```c
#include "alloc/bump.h"
#include "alloc/slab.h"
#include "alloc/pool.h"
#include "alloc/tlsf.h"
// or pull all four via:
#include "prelude.h"
```

## Comparison

| Allocator | Alloc | Free | Best for |
|-----------|-------|------|----------|
| `BumpAllocator` | O(1) | Reset-only | Per-frame arenas, scratch buffers |
| `SlabAllocator` | O(1) | O(1) | Many fixed-size objects (e.g. nodes, packets) |
| `PoolAllocator` | O(1) | O(1) | Fixed-size blocks with varied content |
| `TlsfAllocator` | O(1) | O(1) | General-purpose real-time heap |

---

## BumpAllocator

A linear (bump-pointer) allocator. Allocations advance a pointer; freeing individual objects is not supported — you reset the entire arena.

### Struct

```c
struct BumpAllocator {
    void*         base;
    unsigned long used;
    unsigned long cap;

    void*         alloc(unsigned long size, unsigned long align);
    void          reset();
    unsigned long remaining() const;
    void          destroy();
}
```

### Constructors

```c
// Use a caller-supplied buffer (no heap)
BumpAllocator bump_init(void* buffer, unsigned long cap);

// Heap-allocate a backing buffer of `cap` bytes
BumpAllocator bump_new(unsigned long cap);
```

### Methods

| Method | Description |
|--------|-------------|
| `alloc(size, align)` | Returns aligned pointer, advances `used`. Returns NULL if full. |
| `reset()` | Sets `used = 0`. All previously allocated memory is invalidated. |
| `remaining() const` | Returns `cap - used`. |
| `destroy()` | Frees backing buffer if heap-allocated. |

### Example

```c
#include "alloc/bump.h"
#include "io.h"

int main() {
    unsigned char buf[4096];
    BumpAllocator a = bump_init(buf, sizeof(buf));

    int* x = a.alloc(sizeof(int), 4);
    int* y = a.alloc(sizeof(int), 4);
    *x = 10;
    *y = 20;

    print("remaining: ");
    println_int(a.remaining());  // 4088

    a.reset();  // invalidate all; ready for next frame
    return 0;
}
```

---

## SlabAllocator

A freelist-based slab allocator for fixed-size objects. Backed by a pre-allocated pool; individual alloc/dealloc is O(1) via a linked freelist embedded in the pool.

### Struct

```c
struct SlabAllocator {
    void*         pool;
    void*         freelist;
    unsigned long obj_size;
    unsigned long count;

    void*         alloc();
    void          dealloc(void* ptr);
    unsigned long available() const;
    void          destroy();
}
```

### Constructors

```c
// Use a caller-supplied buffer
SlabAllocator slab_init(void* pool, unsigned long obj_size, unsigned long count);

// Heap-allocate a pool for `count` objects of `obj_size` bytes each
SlabAllocator slab_new(unsigned long obj_size, unsigned long count);
```

### Methods

| Method | Description |
|--------|-------------|
| `alloc()` | Pops one object from freelist. Returns NULL if full. |
| `dealloc(ptr)` | Pushes object back to freelist. Pointer must be from this allocator. |
| `available() const` | Number of free slots remaining. |
| `destroy()` | Frees backing pool if heap-allocated. |

### Example

```c
#include "alloc/slab.h"
#include "io.h"

struct Packet { unsigned char data[64]; unsigned long len; };

int main() {
    SlabAllocator sa = slab_new(sizeof(struct Packet), 32);

    struct Packet* p1 = sa.alloc();
    struct Packet* p2 = sa.alloc();
    struct Packet* p3 = sa.alloc();

    p1->len = 10;

    print("available: ");
    println_int(sa.available());  // 29

    sa.dealloc(p2);

    print("available: ");
    println_int(sa.available());  // 30

    sa.destroy();
    return 0;
}
```

---

## PoolAllocator

A fixed-size block pool. Like a slab allocator but without the constraint that all blocks hold identical object types — any data fitting within `block_size` bytes can use the same pool.

### Struct

```c
struct PoolAllocator {
    void*         base;
    void*         next_free;
    unsigned long block_size;
    unsigned long capacity;

    void*         alloc();
    void          free(void* ptr);
    unsigned long available() const;
    void          destroy();
}
```

### Constructors

```c
// Use a caller-supplied buffer
PoolAllocator pool_init(void* buffer, unsigned long block_size, unsigned long count);

// Heap-allocate
PoolAllocator pool_new(unsigned long block_size, unsigned long count);
```

### Methods

| Method | Description |
|--------|-------------|
| `alloc()` | Returns next free block. NULL if exhausted. |
| `free(ptr)` | Returns block to pool. |
| `available() const` | Free block count. |
| `destroy()` | Free heap-allocated backing buffer. |

---

## TlsfAllocator

Two-Level Segregated Fit — a real-time general-purpose allocator with **O(1) worst-case** alloc and free. Suitable for embedded firmware where `malloc` timing is unacceptable.

### Struct

```c
struct TlsfAllocator {
    void*         pool;
    unsigned long pool_size;
    // internal FL/SL bitmaps (opaque)

    void* alloc(unsigned long size);
    void  free(void* ptr);
    void  destroy();
}
```

### Constructors

```c
// Use a caller-supplied memory region as the TLSF heap
TlsfAllocator tlsf_init(void* pool, unsigned long size);

// Heap-allocate a pool of `size` bytes from the system allocator
TlsfAllocator tlsf_new(unsigned long size);
```

### Methods

| Method | Description |
|--------|-------------|
| `alloc(size)` | Allocate `size` bytes. Returns NULL if no suitable block. O(1) guaranteed. |
| `free(ptr)` | Free a previously allocated block. O(1) guaranteed. |
| `destroy()` | Free heap-allocated pool. |

### Example

```c
#include "alloc/tlsf.h"
#include "io.h"

int main() {
    TlsfAllocator ta = tlsf_new(65536);  // 64 KiB pool

    void* a = ta.alloc(100);
    void* b = ta.alloc(200);
    void* c = ta.alloc(50);

    ta.free(b);              // coalesced back into free pool
    void* d = ta.alloc(180);  // reuses freed space

    ta.free(a);
    ta.free(c);
    ta.free(d);
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
