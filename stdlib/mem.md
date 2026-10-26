# mem -- Memory

The `mem` module wraps heap allocation and memory operations. `alloc`/`alloc_zeroed`/`dealloc`/`realloc_buf` are safe by construction against a specific set of runtime allocator-misuse bugs (see [Allocation Safety](#allocation-safety) below). They hand out `?&heap T` references; `T` comes from the variable you assign to (use `void` for untyped bytes). A reference can't be indexed, so view a multi-element block through a [`Slice`](/stdlib/collections#slice) to read and write it with bounds checks.

For typed arrays, `new T[n]` (heap) and `new<R> T[n]` (arena) are usually simpler — see [Memory & Regions](/reference/memory#arrays-by-region).

```c
#include <std/mem.h>
```

## Allocation {#allocation}

### alloc {#alloc}

```c
generic<T> ?&heap T alloc(unsigned long size);
```

Allocate `size` bytes of uninitialized heap memory. Returns a reference to the block, or `null` on failure.

### alloc_zeroed {#alloc_zeroed}

```c
generic<T> ?&heap T alloc_zeroed(unsigned long size);
```

Allocate `size` bytes of zero-initialized heap memory. Returns a reference to the block, or `null` on failure.

### dealloc {#dealloc}

```c
generic<T> void dealloc(?&heap T ptr);
```

Free a previously allocated block. Passing `null` is a no-op. Calling this twice on the same pointer, or on a pointer `alloc`/`alloc_zeroed` never returned, aborts with a diagnostic instead of corrupting the heap — see [Allocation Safety](#allocation-safety).

### realloc_buf {#realloc_buf}

```c
generic<T> ?&heap T realloc_buf(?&heap T ptr, unsigned long new_size);
```

Resize a previously allocated block to `new_size` bytes. The contents are preserved up to the minimum of the old and new sizes. Returns the (possibly moved) block, or `null` on failure (the old block is *not* freed on failure). A `null` `ptr` behaves like `alloc(new_size)`.

### checked_mul_size {#checked_mul_size}

```c
unsigned long checked_mul_size(unsigned long a, unsigned long b);
```

Multiplies two allocation-size factors (typically an element count and an element size) and aborts with a diagnostic instead of silently wrapping if the product overflows `unsigned long` — the classic "`count * elem_size`" integer-overflow-to-undersized-allocation bug. Every allocation site in the standard library that multiplies a caller-controlled count by an element size (`Vec`, `HashMap`'s buckets, `Queue`, `MpscQueue`, `LFQueue`, ...) is routed through this instead of a bare `*`; use it the same way anywhere a size passed to `alloc`/`alloc_zeroed`/`realloc_buf` is a product of two runtime values rather than a small compile-time-provable constant.

### checked_add_size {#checked_add_size}

```c
unsigned long checked_add_size(unsigned long a, unsigned long b);
```

The addition counterpart of `checked_mul_size`: aborts instead of wrapping when the sum overflows `unsigned long`. Use it when adding several field sizes into one combined allocation.

## Allocation Safety {#allocation-safety}

`alloc`/`alloc_zeroed` prefix every allocation with a small 16-byte header tagging it live or freed. `dealloc`/`realloc_buf` check that tag before acting:

- **Double-free** — calling `dealloc()` twice on the same pointer aborts with a diagnostic instead of corrupting the allocator's free list.
- **Mismatched allocator** — calling `dealloc()` on a pointer `alloc`/`alloc_zeroed` never returned (a stack address, a pointer from a *different* allocator like `std/alloc/pool.h`, or corrupted memory) aborts instead of freeing garbage.
- **NULL** — `dealloc(NULL)` is a safe no-op, matching `free(NULL)`.
- **Use-after-free (redeallocation shape)** — a freed pointer handed back to `dealloc`/`realloc_buf` a second time is caught the same way double-free is.

A bounded 64-entry quarantine backs this: freed blocks aren't actually returned to the system allocator for a further ~64 `dealloc()` calls, so the double-free check stays reliable even though this platform's own allocator overwrites a freed block's first bytes almost immediately (a bare tag alone would only catch a same-pointer double-free that happens to occur before the OS allocator reuses that memory). A double-free separated by 64+ *other* `dealloc()` calls in between falls back to the weaker "not a live pointer" diagnosis — a known, documented limit, not a silent gap. Blocks the size-class cache doesn't hold (over 1 MB, or grown by `realloc_buf`) also count against an 8 MB byte budget per thread. Once the quarantined ones add up to more than that, the oldest are really freed. Otherwise a few dozen large frees could keep hundreds of MB from reuse, and every later large allocation would start on fresh pages.

::: warning Not covered
This does **not** catch a use-after-free that only reads or writes through a stale pointer without ever calling `dealloc()`/`realloc_buf()` on it again — that needs shadow-memory instrumentation (ASan-style), well beyond what a 16-byte header can do. It also doesn't replace bounds checking: `alloc(n)` followed by an out-of-bounds write at `buf[n]` is still undefined behavior, caught by neither this nor (since it's a raw pointer, not an array/slice) any compile-time check.
:::

`std/alloc/pool.h`/`slab.h`/`tlsf.h`'s allocators get the equivalent double-free/mismatched-pointer check using their own existing per-block metadata (no quarantine needed there — their freed blocks stay in the allocator's own backing buffer rather than being handed back to the system allocator, so their tag can't be clobbered by anything external the way `alloc`/`dealloc`'s can).

## Allocation Performance {#allocation-performance}

On hosted targets `alloc` keeps a per-thread cache of freed blocks, bucketed by size class, so a free followed by an allocation of a similar size is served without calling `malloc`:

- **Size classes** step by 1.5× — 16, 24, 32, 48, 64, 96, … up to 1 MB (33 classes) — so a request is padded by at most a third. Larger requests go straight to `malloc`.
- **Cache depth** is a byte budget rather than a slot count: up to 8,192 blocks for small classes, fewer for large ones (min(8192, 8 MB ÷ class size), at least 8). All classes share one flat per-thread table of about 0.96 MB.
- **Slabs** back the classes up to 1 KB (Linux and macOS). Instead of one `malloc` per block, blocks are carved from 64 KB slabs owned by the allocating thread. A slab is found from any of its blocks by masking the address. The owner frees onto a plain list; a free from another thread is pushed onto the slab's lock-free remote stack, which the owner takes whole when it runs short. A slab whose blocks are all free again is returned to the system, except up to 16 spare empty slabs per class. The cache above sits in front of the slabs. Every block keeps its header, and a freed block stays marked freed until it is reused, so the double-free check is unchanged. The free-list link lives in the header, not the payload, so a write through a dangling pointer can't corrupt the allocator. With a million small blocks live at once, `alloc`/`dealloc` is now faster than `malloc`/`free` (see [Benchmarks](/benchmarks#allocator)). Slabs of a thread that exits while some of its blocks are still live stay allocated until other threads free those blocks.
- Larger blocks that don't fit the cache go through the quarantine described above, then back to `malloc`.
- Freestanding targets have no cache or slabs (they need thread-local storage and the table's memory); every request goes to the runtime's `malloc`. Windows and wasm keep the cache without slabs.

Each block still carries the 16-byte header, which matters most for very small objects: a 24-byte node occupies a 40-byte request. Node-based containers therefore don't allocate per node — `List` and `BST` take nodes from a per-container [`NodePool`](/stdlib/collections#node-pool) (chunked bump allocation with a free list), which is about 2× faster than C's `malloc`/`free` per node (see [Benchmarks](/benchmarks#collections)). For your own fixed-size objects, a `std/alloc/slab.h` `SlabAllocator` with [`alloc_t`](/stdlib/allocators#typed) and the arena regions (`new<R> T`) give the same effect.

## Memory Operations {#memory-operations}

### safe_memcpy {#safe_memcpy}

```c
generic<T> unsigned long safe_memcpy([]T dst, []const T src);
```

Copy `n` bytes from `src` to `dst`. The source and destination regions **must not overlap**. Use `safe_memmove` for overlapping regions.

### safe_memmove {#safe_memmove}

```c
generic<T> unsigned long safe_memmove([]T dst, []const T src);
```

Copy `n` bytes from `src` to `dst`. Safe for overlapping regions.

### safe_memset {#safe_memset}

```c
generic<T> void safe_memset([]T s, int val);        // every byte of s's elements
generic<T> void safe_memset_obj(&T v, int val);     // one object
```

Set `n` bytes starting at `ptr` to the value `val` (interpreted as `unsigned char`).

### safe_memcmp {#safe_memcmp}

```c
generic<T> int safe_memcmp([]const T a, []const T b);
```

Compare `n` bytes of memory at `a` and `b`. Returns:
- `< 0` if `a` is less than `b`
- `0` if equal
- `> 0` if `a` is greater than `b`

## Cache and Alignment Helpers {#cache-and-alignment-helpers}

```c
#define MEM_CACHE_LINE_SIZE  64   // bytes (typical x86 / ARM / RISC-V L1)

unsigned long mem_align_up(unsigned long addr, unsigned long align);   // align is a power of two
unsigned long mem_align_down(unsigned long addr, unsigned long align);
int           mem_is_aligned(unsigned long addr, unsigned long align);
generic<T> ?&T mem_align_ptr(&T ptr, unsigned long align);             // null if it can't be aligned
int           mem_fits_page(unsigned long addr, unsigned long size);    // 1 if [addr, addr+size) stays in one 4 KiB page

// locality: 0 = no reuse, 1 = low, 2 = moderate, 3 = high. __builtin_prefetch; no-op elsewhere.
generic<T> void mem_prefetch(const &T addr, int write, int locality);
generic<T> void mem_clflush(const &T addr);              // write back + invalidate addr's line (x86 CLFLUSH, AArch64 DC CIVAC; else no-op)
generic<T> void mem_zero_secure([]T s);    // zero with volatile stores the optimizer can't remove
generic<T> void mem_zero_secure_obj(&T v); // the same for one object
```

The address arithmetic helpers take plain `unsigned long` addresses; the rest take a reference to the first byte of the region, like the `safe_mem*` functions above.

## Example {#example}

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
            struct Slice<int> s = std::Slice::of(buf, 10UL);   // bounds-checked view of the 10 ints
            std::safe_memset(s.as_slice(), 0);
            s.set(0UL, 42);
            s.set(1UL, 99);

            // Duplicate into a second buffer
            ?&heap int copy = std::alloc(10UL * sizeof(int));
            match (copy) {
                case some(c): {
                    std::safe_memcpy(std::Slice::of(c, 10UL).as_slice(), s.as_slice());   // 10 elements
                    std::println_int(*std::Slice::of(c, 10UL).at(0UL));   // 42
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

### Double-free is caught, not silent {#double-free-is-caught-not-silent}

```c
#include <std/mem.h>
#include <std/io.h>

int main() {
    ?&heap unsigned char p = std::alloc(64UL);
    std::dealloc(p);
    std::println("first dealloc OK");
    // The compiler rejects a second dealloc of p; unsafe opts out of that
    // check, so the runtime one fires instead.
    unsafe { std::dealloc(p); }   // aborts: "dealloc() called twice on the same pointer"
    println("never reached");
    return 0;
}
```
