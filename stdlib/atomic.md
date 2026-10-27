# atomic -- Atomics

The `atomic` module provides lock-free atomic operations on shared integers, wrapping C11 `<stdatomic.h>`. All operations use sequential consistency ordering.

```c
#include <std/atomic.h>
```

## atomic_int (32-bit signed) {#atomic_int-32-bit-signed}

### Load / Store {#load-store}

```c
int  atomic_load_int(const &int addr);
void atomic_store_int(&int addr, int val);
```

Atomically read or write `*addr`.

### Fetch-and-Modify {#fetch-and-modify}

```c
int atomic_fetch_add_int(&int addr, int delta);
int atomic_fetch_sub_int(&int addr, int delta);
int atomic_fetch_and_int(&int addr, int mask);
int atomic_fetch_or_int(&int addr, int mask);
int atomic_fetch_xor_int(&int addr, int mask);
```

Each atomically applies the operation and returns the **old** value.

### Exchange {#exchange}

```c
int atomic_exchange_int(&int addr, int val);
```

Atomically replace `*addr` with `val` and return the old value.

### Compare-and-Swap (CAS) {#compare-and-swap-cas}

```c
int atomic_cas_int(&int addr, &int expected, int desired);
```

If `*addr == *expected`, atomically store `desired` into `*addr` and return 1 (success). Otherwise, update `*expected` to the current value of `*addr` and return 0 (failure).

## atomic_long (64-bit signed) {#atomic_long-64-bit-signed}

```c
long long  atomic_load_ll(const &long long addr);
void       atomic_store_ll(&long long addr, long long val);
long long  atomic_fetch_add_ll(&long long addr, long long delta);
long long  atomic_fetch_sub_ll(&long long addr, long long delta);
long long  atomic_exchange_ll(&long long addr, long long val);
int        atomic_cas_ll(&long long addr, &long long expected, long long desired);
```

Same semantics as the `_int` variants, operating on `long long` (64-bit) values.

## AtomicInt / AtomicLong {#typed-atomics}

An `int` / `long long` every access to which is atomic. Both are [Sync](/reference/threads) (`unsafe impl Send + Sync`), so threads may share one through a mutable reference — a `&static AtomicInt` passed to `spawn`, or a local lent to `spawn_scoped` threads — where a plain `int` would be rejected as a data race. A zero-initialized `static` one needs no `new`.

```c
struct AtomicInt {                  // also AtomicLong, with long long values
    static struct AtomicInt new(int v);
    int  load() const;
    void store(int v);
    int  fetch_add(int d);          // returns the old value
    int  fetch_sub(int d);
    int  exchange(int v);
    int  compare_exchange(&int expected, int desired);   // 1 if stored; else *expected = current
};
```

```c
#include <std/atomic.h>
extern int printf(const char *fmt, ...);

static struct AtomicInt g_hits;              // zero-initialized

?&AtomicInt hit(?&AtomicInt c) {
    match (c) {
        case some(a): { for (int i = 0; i < 1000; i = i + 1) a.fetch_add(1); }
        case null: { }
    }
    return null;
}

int main() {
    long long h1 = spawn(hit, &g_hits);      // OK: AtomicInt synchronizes itself
    long long h2 = spawn(hit, &g_hits);
    join(h1);
    join(h2);

    struct AtomicLong total = AtomicLong::new(5LL);
    long long expected = 5LL;
    int stored = total.compare_exchange(&expected, 7LL);   // 1: it was 5

    printf("%d %d %lld\n", g_hits.load(), stored, total.load());
    return 0;
}
```

## Memory Fence {#memory-fence}

```c
void atomic_thread_fence();
```

Issue a full sequential-consistency memory fence. This ensures that all memory operations before the fence are visible to all threads before any memory operations after the fence.

## Example: Lock-Free Counter {#example-lock-free-counter}

```c
#include <std/atomic.h>
#include <std/thread.h>
#include <std/io.h>

static int counter = 0;

static ?&int increment(?&int unused) {
    int i = 0;
    while (i < 10000) {
        std::atomic_fetch_add_int(&counter, 1);
        i = i + 1;
    }
    return null;
}

int main() {
    unsigned long long t1 = 0ULL;
    unsigned long long t2 = 0ULL;
    std::thread_create(&t1, increment, null);
    std::thread_create(&t2, increment, null);

    std::thread_join(t1);
    std::thread_join(t2);

    std::print("counter = ");
    std::println_int(std::atomic_load_int(&counter));  // 20000
    return 0;
}
```

## Example: Compare-and-Swap Loop {#example-compare-and-swap-loop}

```c
#include <std/atomic.h>
#include <std/io.h>

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

## Safety Note {#safety-note}

The atomic operations themselves are memory-safe. However, storing results into raw pointers received from C FFI requires `unsafe {}` at the call site, as per SafeC's FFI policy.
