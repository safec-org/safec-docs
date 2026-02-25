# Standard Library Overview

The SafeC standard library (`std/`) provides **20+ modules** covering memory, I/O, strings, math, concurrency, and collections. Each module is a `.h` (declarations) + `.sc` (implementation) pair. Include `prelude.h` to pull in every module at once.

```c
#include "prelude.h"

int main() {
    println("Hello from SafeC stdlib!");
    return 0;
}
```

## Module Categories

### Core

| Module | Header | Description |
|--------|--------|-------------|
| [mem](/stdlib/mem) | `mem.h` | Allocation, deallocation, safe memcpy/memmove/memset/memcmp |
| [io](/stdlib/io) | `io.h` | Formatted output (stdout/stderr), stdin input, buffer formatting |
| io_file | `io_file.h` | FILE\* file I/O (open/close/read/write/seek) |
| [str](/stdlib/str) | `str.h` | String length, comparison, copy, search, tokenisation, duplication |
| fmt | `fmt.h` | snprintf-based formatting into caller-supplied buffers |
| convert | `convert.h` | String-to-number parsing, number-to-string conversion |

### Math & Numeric

| Module | Header | Description |
|--------|--------|-------------|
| [math](/stdlib/math) | `math.h` | Constants (PI, E, ...), float/double math, classification |
| complex | `complex.h` | Complex numbers as `float[2]`/`double[2]`; arithmetic + transcendentals |
| bit | `bit.h` | Popcount, clz, ctz, rotate, bswap, power-of-two helpers |

### System & OS

| Module | Header | Description |
|--------|--------|-------------|
| sys | `sys.h` | Process control, environment, PRNG, sorting, clocks |
| errno | `errno.h` | Thread-local errno access and error descriptions |
| signal | `signal.h` | Signal handling, raise, kill, pause |
| time | `time.h` | Wall/monotonic/CPU clocks, calendar, formatting, sleep |
| locale | `locale.h` | Locale get/set (wraps C `setlocale`) |
| fenv | `fenv.h` | Floating-point environment: exception flags, rounding mode |

### Concurrency

| Module | Header | Description |
|--------|--------|-------------|
| [thread](/stdlib/thread) | `thread.h` | Threads, mutexes, condition variables, read-write locks |
| [atomic](/stdlib/atomic) | `atomic.h` | Lock-free atomic operations (C11 `<stdatomic.h>` wrappers) |

### [Collections](/stdlib/collections)

| Module | Header | Description |
|--------|--------|-------------|
| slice | `collections/slice.h` | Bounds-checked fat pointer + generic array functions |
| vec | `collections/vec.h` | Dynamic array with push/pop/sort/filter/map |
| string | `collections/string.h` | Mutable, heap-allocated growable string (30+ methods) |
| stack | `collections/stack.h` | LIFO stack backed by growable array |
| queue | `collections/queue.h` | FIFO circular buffer queue |
| list | `collections/list.h` | Doubly linked list |
| map | `collections/map.h` | Hash map (open addressing, linear probing) |
| bst | `collections/bst.h` | Unbalanced binary search tree |

### C Compatibility Headers

| Header | Description |
|--------|-------------|
| `assert.h` | Runtime assertions (`runtime_assert`, `assert_true`); NDEBUG support |
| `ctype.h` | Character classification (`char_is_alpha`, `char_is_digit`, ...) and conversion |
| `stdckdint.h` | Checked integer arithmetic (C23-style `ckd_add`/`ckd_sub`/`ckd_mul`) |
| `stdint.h` | Fixed-width integer types |
| `stddef.h` | `size_t`, `NULL`, `offsetof` |
| `stdbool.h` | Boolean constants |
| `limits.h` | Integer limits |
| `float.h` | Floating-point limits |
| `inttypes.h` | Format macros for fixed-width types |

## Generic Pattern

The SafeC compiler does not support generic structs. Collections use `void*` structs for the underlying data structure, with `generic<T>` wrapper functions for type-safe access. `T` is inferred from `T*` arguments at the call site via monomorphization.

```c
#include "collections/vec.h"

int main() {
    struct Vec v = vec_new(sizeof(int));

    // Type-erased API
    int x = 42;
    vec_push(&v, &x);

    // Generic typed wrapper — T inferred from int* argument
    vec_push_t(&v, 100);
    int* p = vec_at(&v, 0);  // returns int*

    vec_free(&v);
    return 0;
}
```

## Including the Standard Library

**Individual modules:**
```c
#include "mem.h"
#include "io.h"
#include "collections/vec.h"
```

**All modules at once:**
```c
#include "prelude.h"
```

When building with the `safeguard` package manager, the standard library is automatically compiled to `build/deps/libsafec_std.a` and linked.

When building manually, pass the std directory with `-I`:
```bash
./build/safec myfile.sc -I /path/to/SafeC/std --emit-llvm -o myfile.ll
clang myfile.ll -o myfile
```
