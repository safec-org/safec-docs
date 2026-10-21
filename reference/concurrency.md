# Concurrency

SafeC provides low-level, C-compatible concurrency primitives. Threads are created with `spawn` and joined with `join`, mapping directly to POSIX pthreads. There are no closures or runtime schedulers -- concurrency is explicit and deterministic.

## Spawn and Join {#spawn-and-join}

### Creating a Thread {#creating-a-thread}

`spawn(fn, arg)` creates a new thread that executes the given function with the given argument. It returns a thread handle (`long long`, which is the underlying `pthread_t`).

```c
extern int printf(const char* fmt, ...);
static int g_value = 42;

?&int worker(?&int arg) {
    match (arg) {
        case some(v): { printf("Worker received: %d\n", *v); }
        case null:    { }
    }
    return null;
}

int main() {
    long long handle = spawn(worker, &g_value);   // &static int: allowed
    join(handle);
    return 0;
}
```

### Thread Function Signature {#thread-function-signature}

A thread function takes one nullable reference and returns one — `?&T name(?&T arg)` — which is exactly the POSIX `void*(void*)` shape at the ABI level, with the argument's type checked:

```c
?&int my_thread(?&int arg) {
    // ... do work ...
    return null;
}
```

Pass `null` when the thread needs no argument.

### Joining a Thread {#joining-a-thread}

`join(h)` blocks the calling thread until the thread identified by handle `h` completes. It maps directly to `pthread_join`.

```c
long long h1 = spawn(worker1, arg1);
long long h2 = spawn(worker2, arg2);
join(h1);                      // wait for worker1
join(h2);                      // wait for worker2
```

## Lowering {#lowering}

`spawn` and `join` lower directly to pthread calls:

| SafeC | C / POSIX |
|-------|-----------|
| `spawn(fn, arg)` | `pthread_create(&tid, NULL, fn, arg)` |
| `join(handle)` | `pthread_join((pthread_t)handle, NULL)` |

The handle returned by `spawn` is the raw `pthread_t` value cast to `long long`.

## Region Safety in Concurrency {#region-safety-in-concurrency}

The region system provides safety guarantees for concurrent code:

### Spawn Requires Static Function Reference {#spawn-requires-static-function-reference}

The function passed to `spawn` must be a `&static` function reference -- it must be a named function, not a local function pointer that could go out of scope:

```c
?&int worker(?&int arg) { return null; }

int main() {
    long long h = spawn(worker, null);  // OK: worker is static
    join(h);
    return 0;
}
```

### Mutable Non-Static References Rejected {#mutable-non-static-references-rejected}

The compiler rejects passing mutable references to non-static data as spawn arguments, preventing data races:

```c
?&int bad_worker(?&int arg) {
    match (arg) {
        case some(p): { *p = 99; }   // would race with the spawning thread
        case null:    { }
    }
    return null;
}

void example() {
    int local = 42;
    // spawn(bad_worker, &local);  // ERROR: mutable non-static ref in spawn
}
```

A `&static` reference is accepted: static data outlives every thread, and coordinating access to it is up to you (atomics or the `thread` module's locks).

To share mutable data between threads, use atomic operations or explicit synchronization with the standard library's thread module.

## Linking {#linking}

Programs that use `spawn` or `join` must be linked with `-lpthread`:

```bash
./build/safec program.sc --emit-llvm -o program.ll
/usr/bin/clang program.ll -lpthread -o program
```

## Atomic Built-ins {#atomic-built-ins}

For lock-free synchronization between threads, SafeC provides atomic operations:

```c
atomic int counter = 0;

?&int increment(?&int unused) {
    for (int i = 0; i < 1000; i = i + 1) {
        atomic_fetch_add(&counter, 1);
    }
    return null;
}
```

Available atomic operations:

| Operation | Description |
|-----------|-------------|
| `atomic_load(ptr)` | Load value atomically |
| `atomic_store(ptr, val)` | Store value atomically |
| `atomic_fetch_add(ptr, val)` | Add and return old value |
| `atomic_fetch_sub(ptr, val)` | Subtract and return old value |
| `atomic_exchange(ptr, val)` | Swap and return old value |
| `atomic_cas(ptr, expected, desired)` | Compare-and-swap |
| `atomic_fence()` | Memory fence (sequentially consistent) |

See [Bare-Metal Programming](/reference/baremetal) for the full list of atomic operations.

## Standard Library Thread Module {#standard-library-thread-module}

The SafeC standard library provides higher-level concurrency primitives in the `thread` module:

- **Mutexes**: `mutex_create`, `mutex_lock`, `mutex_unlock`, `mutex_destroy`
- **Condition variables**: `cond_create`, `cond_wait`, `cond_signal`, `cond_broadcast`, `cond_timedwait_ms`
- **Read-write locks**: `rwlock_create`, `rwlock_read_lock`, `rwlock_write_lock`, `rwlock_unlock`
- **Thread utilities**: `thread_yield`, `thread_sleep_ms`, `thread_self`

These are cross-platform wrappers over POSIX threads (or Win32 threads with `-D__WINDOWS__`).

## Scoped Spawn {#scoped-spawn}

`spawn_scoped(fn, arg)` is a compiler built-in, distinct from `spawn` — it
has the same signature and the same mutable-non-static-reference rejection,
but is intended for spawns whose join is guaranteed before the enclosing
scope exits. It returns a thread handle exactly like `spawn`, still joined
explicitly with `join`:

```c
?&int worker(?&int arg) {
    return null;
}

void example() {
    long long h = spawn_scoped(worker, null);
    join(h);
}
```

## Channels {#channels}

Channels are another compiler built-in (no `#include` required, like
`spawn`/`join`), providing a bounded MPMC queue for passing values between
threads without shared mutable references:

| Built-in | Signature | Description |
|----------|-----------|-------------|
| `chan_create(capacity)` | `(int) -> ?&void` | Creates a channel with the given capacity, returns an opaque handle |
| `chan_send(channel, value)` | `(?&void, &T) -> bool` | Sends a copy of `*value`; returns `true` on success |
| `chan_recv(channel, out)` | `(?&void, &T) -> bool` | Receives into `*out`; returns `false` if the channel is closed and empty |
| `chan_close(channel)` | `(?&void) -> void` | Closes the channel; pending/future `chan_recv` calls drain remaining values then return `false` |

The handle is opaque and values go in and out by reference, so no `unsafe`
is involved. The channel itself is shared through static storage here, the
same way any value a spawned thread uses must outlive it:

```c
extern int printf(const char* fmt, ...);
static ?&void g_ch = null;

?&int producer(?&int unused) {
    int value = 7;
    chan_send(g_ch, &value);
    chan_close(g_ch);
    return null;
}

int main() {
    g_ch = chan_create(4);
    long long h = spawn_scoped(producer, null);
    int received = 0;
    bool ok = chan_recv(g_ch, &received);
    join(h);
    printf("%d %d\n", (int)ok, received);
    return 0;
}
```

## Example: Parallel Computation {#example-parallel-computation}

```c
#include <std/collections/slice.h>
extern int printf(const char* fmt, ...);

struct WorkItem {
    struct Slice<int> data;
    unsigned long start;
    unsigned long end;
    long long result;
};

// Shared with the workers, so it lives in static storage: spawn only
// accepts references that can't dangle while the thread runs.
static struct WorkItem w1;
static struct WorkItem w2;

?&WorkItem sum_range(?&WorkItem arg) {
    match (arg) {
        case some(w): {
            long long sum = 0LL;
            for (unsigned long i = w.start; i < w.end; i = i + 1UL) {
                sum = sum + (long long)*w.data.at(i);   // bounds-checked
            }
            w.result = sum;
        }
        case null: { }
    }
    return null;
}

int main() {
    unsigned long n = 10000UL;
    struct Slice<int> data = std::Slice::alloc(n);      // heap, runtime-sized
    for (unsigned long i = 0UL; i < n; i = i + 1UL) data.set(i, (int)i);

    w1.data = data; w1.start = 0UL;    w1.end = n / 2UL; w1.result = 0LL;
    w2.data = data; w2.start = n / 2UL; w2.end = n;      w2.result = 0LL;

    long long h1 = spawn(sum_range, &w1);
    long long h2 = spawn(sum_range, &w2);
    join(h1);
    join(h2);

    printf("Total: %lld\n", w1.result + w2.result);
    data.free();
    return 0;
}
```
