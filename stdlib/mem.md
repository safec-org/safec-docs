# mem -- Memory

The `mem` module provides safe wrappers around heap allocation and memory operations. All functions perform NULL checks and bounds validation internally.

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

Free a previously allocated block. Passing NULL is a no-op.

### realloc_buf

```c
void* realloc_buf(void* ptr, unsigned long new_size);
```

Resize a previously allocated block to `new_size` bytes. The contents are preserved up to the minimum of the old and new sizes. Returns the (possibly moved) pointer, or NULL on failure.

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
    int* buf = alloc(10 * sizeof(int));
    safe_memset(buf, 0, 10 * sizeof(int));

    buf[0] = 42;
    buf[1] = 99;

    // Duplicate into a new buffer
    int* copy = alloc(10 * sizeof(int));
    safe_memcpy(copy, buf, 10 * sizeof(int));

    print_int(copy[0]);  // 42
    println("");

    dealloc(buf);
    dealloc(copy);
    return 0;
}
```
