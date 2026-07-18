# mem -- Memory

The `mem` module wraps heap allocation and memory operations. `alloc`/`alloc_zeroed`/`dealloc`/`realloc_buf` are safe by construction against a specific set of runtime allocator-misuse bugs (see [Allocation Safety](#allocation-safety) below) — but they don't perform general bounds validation on every access; `unsafe {}` is still required to actually read/write through the returned pointer, same as any raw pointer in SafeC.

```c
#include "mem.h"
```

## Allocation

### alloc

```c
void* alloc(unsigned long size);
```

Allocate `size` bytes of uninitialized heap memory. Returns a pointer to the allocated block, or NULL on failure.

### alloc_zeroed

```c
void* alloc_zeroed(unsigned long size);
```

Allocate `size` bytes of zero-initialized heap memory. Returns a pointer to the allocated block, or NULL on failure.

### dealloc

```c
void dealloc(void* ptr);
```

Free a previously allocated block. Passing NULL is a no-op. Calling this twice on the same pointer, or on a pointer `alloc`/`alloc_zeroed` never returned, aborts with a diagnostic instead of corrupting the heap — see [Allocation Safety](#allocation-safety).

### realloc_buf

```c
void* realloc_buf(void* ptr, unsigned long new_size);
```

Resize a previously allocated block to `new_size` bytes. The contents are preserved up to the minimum of the old and new sizes. Returns the (possibly moved) pointer, or NULL on failure (the old block is *not* freed on failure). `ptr == NULL` behaves like `alloc(new_size)`.

### checked_mul_size

```c
unsigned long checked_mul_size(unsigned long a, unsigned long b);
```

Multiplies two allocation-size factors (typically an element count and an element size) and aborts with a diagnostic instead of silently wrapping if the product overflows `unsigned long` — the classic "`count * elem_size`" integer-overflow-to-undersized-allocation bug. Every allocation site in the standard library that multiplies a caller-controlled count by an element size (`Vec`, `HashMap`'s buckets, `Queue`, `MpscQueue`, `LFQueue`, ...) is routed through this instead of a bare `*`; use it the same way anywhere a size passed to `alloc`/`alloc_zeroed`/`realloc_buf` is a product of two runtime values rather than a small compile-time-provable constant.

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

## Memory Operations

### safe_memcpy

```c
void safe_memcpy(void* dst, const void* src, unsigned long n);
```

Copy `n` bytes from `src` to `dst`. The source and destination regions **must not overlap**. Use `safe_memmove` for overlapping regions.

### safe_memmove

```c
void safe_memmove(void* dst, const void* src, unsigned long n);
```

Copy `n` bytes from `src` to `dst`. Safe for overlapping regions.

### safe_memset

```c
void safe_memset(void* ptr, int val, unsigned long n);
```

Set `n` bytes starting at `ptr` to the value `val` (interpreted as `unsigned char`).

### safe_memcmp

```c
int safe_memcmp(const void* a, const void* b, unsigned long n);
```

Compare `n` bytes of memory at `a` and `b`. Returns:
- `< 0` if `a` is less than `b`
- `0` if equal
- `> 0` if `a` is greater than `b`

## Example

```c
#include "mem.h"
#include "io.h"

int main() {
    // Allocate a buffer for 10 ints
    int* buf;
    unsafe { buf = (int*)alloc(10UL * sizeof(int)); }
    unsafe { safe_memset((void*)buf, 0, 10UL * sizeof(int)); }

    unsafe {
        buf[0] = 42;
        buf[1] = 99;
    }

    // Duplicate into a new buffer
    int* copy;
    unsafe { copy = (int*)alloc(10UL * sizeof(int)); }
    unsafe { safe_memcpy((void*)copy, (const void*)buf, 10UL * sizeof(int)); }

    unsafe { print_int((long long)copy[0]); }  // 42
    println("");

    unsafe {
        dealloc((void*)buf);
        dealloc((void*)copy);
    }
    return 0;
}
```

::: tip
`alloc`/`dealloc`/`safe_memcpy`/`safe_memset` all take/return raw pointers (`void*`/`int*`), and reading or writing through a raw pointer always requires an `unsafe {}` block in SafeC — that's not specific to this module. See [Safety Model](/advanced/safety-model) for what `unsafe` does and doesn't opt out of.
:::

### Double-free is caught, not silent

```c
#include "mem.h"
#include "io.h"

int main() {
    void* p;
    unsafe { p = alloc(64UL); }
    unsafe { dealloc(p); }
    println("first dealloc OK");
    unsafe { dealloc(p); }   // aborts: "dealloc() called twice on the same pointer"
    println("never reached");
    return 0;
}
```
