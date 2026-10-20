# mem -- Memory

The `mem` module wraps heap allocation and memory operations. `alloc`/`alloc_zeroed`/`dealloc`/`realloc_buf` are safe by construction against a specific set of runtime allocator-misuse bugs (see [Allocation Safety](#allocation-safety) below). They hand out `?&heap T` references; `T` comes from the variable you assign to (use `void` for untyped bytes). A reference can't be indexed, so view a multi-element block through a [`Slice`](/stdlib/collections#slice) to read and write it with bounds checks.

For typed arrays, `new T[n]` (heap) and `new<R> T[n]` (arena) are usually simpler — see [Memory & Regions](/reference/memory#arrays-by-region).

```c
#include <std/mem.h>
```

## Allocation

### alloc

```c
generic<T> ?&heap T alloc(unsigned long size);
```

Allocate `size` bytes of uninitialized heap memory. Returns a reference to the block, or `null` on failure.

### alloc_zeroed

```c
generic<T> ?&heap T alloc_zeroed(unsigned long size);
```

Allocate `size` bytes of zero-initialized heap memory. Returns a reference to the block, or `null` on failure.

### dealloc

```c
generic<T> void dealloc(?&heap T ptr);
```

Free a previously allocated block. Passing `null` is a no-op. Calling this twice on the same pointer, or on a pointer `alloc`/`alloc_zeroed` never returned, aborts with a diagnostic instead of corrupting the heap — see [Allocation Safety](#allocation-safety).

### realloc_buf

```c
generic<T> ?&heap T realloc_buf(?&heap T ptr, unsigned long new_size);
```

Resize a previously allocated block to `new_size` bytes. The contents are preserved up to the minimum of the old and new sizes. Returns the (possibly moved) block, or `null` on failure (the old block is *not* freed on failure). A `null` `ptr` behaves like `alloc(new_size)`.

### checked_mul_size

```c
unsigned long checked_mul_size(unsigned long a, unsigned long b);
```

Multiplies two allocation-size factors (typically an element count and an element size) and aborts with a diagnostic instead of silently wrapping if the product overflows `unsigned long` — the classic "`count * elem_size`" integer-overflow-to-undersized-allocation bug. Every allocation site in the standard library that multiplies a caller-controlled count by an element size (`Vec`, `HashMap`'s buckets, `Queue`, `MpscQueue`, `LFQueue`, ...) is routed through this instead of a bare `*`; use it the same way anywhere a size passed to `alloc`/`alloc_zeroed`/`realloc_buf` is a product of two runtime values rather than a small compile-time-provable constant.

### checked_add_size

```c
unsigned long checked_add_size(unsigned long a, unsigned long b);
```

The addition counterpart of `checked_mul_size`: aborts instead of wrapping when the sum overflows `unsigned long`. Use it when adding several field sizes into one combined allocation.

## Allocation Safety

`alloc`/`alloc_zeroed` prefix every allocation with a small 16-byte header tagging it live or freed. `dealloc`/`realloc_buf` check that tag before acting:

- **Double-free** — calling `dealloc()` twice on the same pointer aborts with a diagnostic instead of corrupting the allocator's free list.
- **Mismatched allocator** — calling `dealloc()` on a pointer `alloc`/`alloc_zeroed` never returned (a stack address, a pointer from a *different* allocator like `std/alloc/pool.h`, or corrupted memory) aborts instead of freeing garbage.
- **NULL** — `dealloc(NULL)` is a safe no-op, matching `free(NULL)`.
- **Use-after-free (redeallocation shape)** — a freed pointer handed back to `dealloc`/`realloc_buf` a second time is caught the same way double-free is.

A bounded 64-entry quarantine backs this: freed blocks aren't actually returned to the system allocator for a further ~64 `dealloc()` calls, so the double-free check stays reliable even though this platform's own allocator overwrites a freed block's first bytes almost immediately (a bare tag alone would only catch a same-pointer double-free that happens to occur before the OS allocator reuses that memory). A double-free separated by 64+ *other* `dealloc()` calls in between falls back to the weaker "not a live pointer" diagnosis — a known, documented limit, not a silent gap.

::: warning Not covered
This does **not** catch a use-after-free that only reads or writes through a stale pointer without ever calling `dealloc()`/`realloc_buf()` on it again — that needs shadow-memory instrumentation (ASan-style), well beyond what a 16-byte header can do. It also doesn't replace bounds checking: `alloc(n)` followed by an out-of-bounds write at `buf[n]` is still undefined behavior, caught by neither this nor (since it's a raw pointer, not an array/slice) any compile-time check.
:::

`std/alloc/pool.h`/`slab.h`/`tlsf.h`'s allocators get the equivalent double-free/mismatched-pointer check using their own existing per-block metadata (no quarantine needed there — their freed blocks stay in the allocator's own backing buffer rather than being handed back to the system allocator, so their tag can't be clobbered by anything external the way `alloc`/`dealloc`'s can).

## Allocation Performance

On hosted targets `alloc` keeps a per-thread cache of freed blocks, bucketed by size class, so a free followed by an allocation of a similar size is served without calling `malloc`:

- **Size classes** step by 1.5× — 16, 24, 32, 48, 64, 96, … up to 1 MB (33 classes) — so a request is padded by at most a third. Larger requests go straight to `malloc`.
- **Cache depth** is a byte budget rather than a slot count: up to 8,192 blocks for small classes, fewer for large ones (min(8192, 8 MB ÷ class size), at least 8). All classes share one flat per-thread table of about 0.96 MB.
- Blocks that don't fit the cache go through the quarantine described above, then back to `malloc`.
- Freestanding targets have no cache (it needs thread-local storage and the table's memory); every request goes to the runtime's `malloc`.

Each block still carries the 16-byte header, which matters most for very small objects: a 24-byte node occupies a 40-byte request. Node-based containers therefore don't allocate per node — `List` and `BST` take nodes from a per-container [`NodePool`](/stdlib/collections#node-pool) (chunked bump allocation with a free list), which is about 2× faster than C's `malloc`/`free` per node (see [Benchmarks](/benchmarks#collections-stdcollections-throughput-1000000-elements)). For your own fixed-size objects, `std/alloc/slab.h`'s `slab_alloc_t<T>` and the arena regions (`new<R> T`) give the same effect.

## Memory Operations

### safe_memcpy

```c
generic<T> void safe_memcpy(&T dst, const &T src, unsigned long n);
```

Copy `n` bytes from `src` to `dst`. The source and destination regions **must not overlap**. Use `safe_memmove` for overlapping regions.

### safe_memmove

```c
generic<T> void safe_memmove(&T dst, const &T src, unsigned long n);
```

Copy `n` bytes from `src` to `dst`. Safe for overlapping regions.

### safe_memset

```c
generic<T> void safe_memset(&T ptr, int val, unsigned long n);
```

Set `n` bytes starting at `ptr` to the value `val` (interpreted as `unsigned char`).

### safe_memcmp

```c
generic<T> int safe_memcmp(const &T a, const &T b, unsigned long n);
```

Compare `n` bytes of memory at `a` and `b`. Returns:
- `< 0` if `a` is less than `b`
- `0` if equal
- `> 0` if `a` is greater than `b`

## Cache and Alignment Helpers

```c
#define MEM_CACHE_LINE_SIZE  64   // bytes (typical x86 / ARM / RISC-V L1)

unsigned long mem_align_up(unsigned long addr, unsigned long align);   // align is a power of two
unsigned long mem_align_down(unsigned long addr, unsigned long align);
int           mem_is_aligned(unsigned long addr, unsigned long align);
generic<T> ?&T mem_align_ptr(&T ptr, unsigned long align);             // null if it can't be aligned
int           mem_fits_page(unsigned long addr, unsigned long size);    // 1 if [addr, addr+size) stays in one 4 KiB page

// locality: 0 = no reuse, 1 = low, 2 = moderate, 3 = high. __builtin_prefetch; no-op elsewhere.
generic<T> void mem_prefetch(const &T addr, int write, int locality);
generic<T> void mem_clflush(const &T addr);              // flush the line holding addr (x86 CLFLUSH; no-op elsewhere)
generic<T> void mem_zero_secure(&T ptr, unsigned long n); // zero, then flush, so the bytes leave the cache
```

The address arithmetic helpers take plain `unsigned long` addresses; the rest take a reference to the first byte of the region, like the `safe_mem*` functions above.

## Example

```c
#include <std/mem.h>
#include <std/collections/slice.h>
#include <std/io.h>

int main() {
    // A buffer for 10 ints, zeroed
    ?&heap int maybe = std::alloc(10UL * sizeof(int));
    match (maybe) {
        case null:     { return 1; }               // out of memory
        case some(buf): {
            std::safe_memset(buf, 0, 10UL * sizeof(int));
            struct Slice<int> s = std::slice_of(buf, 10UL);   // bounds-checked view
            s.set(0UL, 42);
            s.set(1UL, 99);

            // Duplicate into a second buffer
            ?&heap int copy = std::alloc(10UL * sizeof(int));
            match (copy) {
                case some(c): {
                    std::safe_memcpy(c, buf, 10UL * sizeof(int));
                    std::println_int(*std::slice_of(c, 10UL).at(0UL));   // 42
                }
                case null: { }
            }
            std::dealloc(copy);
        }
    }
    std::dealloc(maybe);
    return 0;
}
```

::: tip
Every function here takes and returns references, so none of this needs `unsafe`. Indexing goes through a `Slice` because references carry no length; see [Safety Model](/advanced/safety-model) for what `unsafe` does and doesn't opt out of.
:::

### Double-free is caught, not silent

```c
#include <std/mem.h>
#include <std/io.h>

int main() {
    ?&heap void p = std::alloc(64UL);
    std::dealloc(p);
    std::println("first dealloc OK");
    // The compiler rejects a second dealloc of p; unsafe opts out of that
    // check, so the runtime one fires instead.
    unsafe { std::dealloc(p); }   // aborts: "dealloc() called twice on the same pointer"
    println("never reached");
    return 0;
}
```
