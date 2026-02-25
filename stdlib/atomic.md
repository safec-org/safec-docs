# atomic -- Atomics

The `atomic` module provides lock-free atomic operations on shared integers, wrapping C11 `<stdatomic.h>`. All operations use sequential consistency ordering.

```c
#include "atomic.h"
```

## atomic_int (32-bit signed)

### Load / Store

```c
int  atomic_load_int(const int* addr);
void atomic_store_int(int* addr, int val);
```

Atomically read or write `*addr`.

### Fetch-and-Modify

```c
int atomic_fetch_add_int(int* addr, int delta);
int atomic_fetch_sub_int(int* addr, int delta);
int atomic_fetch_and_int(int* addr, int mask);
int atomic_fetch_or_int(int* addr, int mask);
int atomic_fetch_xor_int(int* addr, int mask);
```

Each atomically applies the operation and returns the **old** value.

### Exchange

```c
int atomic_exchange_int(int* addr, int val);
```

Atomically replace `*addr` with `val` and return the old value.

### Compare-and-Swap (CAS)

```c
int atomic_cas_int(int* addr, int* expected, int desired);
```

If `*addr == *expected`, atomically store `desired` into `*addr` and return 1 (success). Otherwise, update `*expected` to the current value of `*addr` and return 0 (failure).

## atomic_long (64-bit signed)

```c
long long  atomic_load_ll(const long long* addr);
void       atomic_store_ll(long long* addr, long long val);
long long  atomic_fetch_add_ll(long long* addr, long long delta);
long long  atomic_fetch_sub_ll(long long* addr, long long delta);
long long  atomic_exchange_ll(long long* addr, long long val);
int        atomic_cas_ll(long long* addr, long long* expected, long long desired);
```

Same semantics as the `_int` variants, operating on `long long` (64-bit) values.

## Memory Fence

```c
void atomic_thread_fence();
```

Issue a full sequential-consistency memory fence. This ensures that all memory operations before the fence are visible to all threads before any memory operations after the fence.

## Example: Lock-Free Counter

```c
#include "atomic.h"
#include "thread.h"
#include "io.h"

int counter = 0;

void* increment(void* arg) {
    int i = 0;
    while (i < 10000) {
        atomic_fetch_add_int(&counter, 1);
        i = i + 1;
    }
    return 0;
}

int main() {
    unsigned long long t1;
    unsigned long long t2;
    thread_create(&t1, increment, 0);
    thread_create(&t2, increment, 0);

    thread_join(t1);
    thread_join(t2);

    print("counter = ");
    println_int(atomic_load_int(&counter));  // 20000
    return 0;
}
```

## Example: Compare-and-Swap Loop

```c
#include "atomic.h"
#include "io.h"

int main() {
    int val = 10;

    // Try to change 10 -> 20
    int expected = 10;
    int ok = atomic_cas_int(&val, &expected, 20);
    print("CAS succeeded: ");
    println_int(ok);          // 1
    print("val = ");
    println_int(val);         // 20

    // Try again with stale expected value
    expected = 10;
    ok = atomic_cas_int(&val, &expected, 30);
    print("CAS succeeded: ");
    println_int(ok);          // 0
    print("expected updated to: ");
    println_int(expected);    // 20 (current value)

    return 0;
}
```

## Safety Note

The atomic operations themselves are memory-safe. However, storing results into raw pointers received from C FFI requires `unsafe {}` at the call site, as per SafeC's FFI policy.
