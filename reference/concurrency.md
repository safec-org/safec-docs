# Concurrency

SafeC provides low-level, C-compatible concurrency primitives. Threads are created with `spawn` and joined with `join`, mapping directly to POSIX pthreads. There are no closures or runtime schedulers -- concurrency is explicit and deterministic.

## Spawn and Join

### Creating a Thread

`spawn(fn, arg)` creates a new thread that executes the given function with the given argument. It returns a thread handle (`long long`, which is the underlying `pthread_t`).

```c
void* worker(void *arg) {
    int *data = (int*)arg;
    printf("Worker received: %d\n", *data);
    return (void*)0;
}

int main() {
    int value = 42;
    long long handle = spawn(worker, (void*)&value);
    join(handle);
    return 0;
}
```

### Thread Function Signature

Thread functions must have the signature `void*(void*)`. This is the standard POSIX thread function type:

```c
void* my_thread(void *arg) {
    // ... do work ...
    return (void*)0;
}
```

### Joining a Thread

`join(h)` blocks the calling thread until the thread identified by handle `h` completes. It maps directly to `pthread_join`.

```c
long long h1 = spawn(worker1, arg1);
long long h2 = spawn(worker2, arg2);
join(h1);                      // wait for worker1
join(h2);                      // wait for worker2
```

## Lowering

`spawn` and `join` lower directly to pthread calls:

| SafeC | C / POSIX |
|-------|-----------|
| `spawn(fn, arg)` | `pthread_create(&tid, NULL, fn, arg)` |
| `join(handle)` | `pthread_join((pthread_t)handle, NULL)` |

The handle returned by `spawn` is the raw `pthread_t` value cast to `long long`.

## Region Safety in Concurrency

The region system provides safety guarantees for concurrent code:

### Spawn Requires Static Function Reference

The function passed to `spawn` must be a `&static` function reference -- it must be a named function, not a local function pointer that could go out of scope:

```c
void* worker(void *arg) { return (void*)0; }

int main() {
    long long h = spawn(worker, (void*)0);  // OK: worker is static
    join(h);
    return 0;
}
```

### Mutable Non-Static References Rejected

The compiler rejects passing mutable references to non-static data as spawn arguments, preventing data races:

```c
void* bad_worker(void *arg) {
    int *p = (int*)arg;
    *p = 99;                   // data race!
    return (void*)0;
}

void example() {
    int local = 42;
    // spawn(bad_worker, &local);  // ERROR: mutable non-static ref in spawn
}
```

To share mutable data between threads, use atomic operations or explicit synchronization with the standard library's thread module.

## Linking

Programs that use `spawn` or `join` must be linked with `-lpthread`:

```bash
./build/safec program.sc --emit-llvm -o program.ll
/usr/bin/clang program.ll -lpthread -o program
```

## Atomic Built-ins

For lock-free synchronization between threads, SafeC provides atomic operations:

```c
atomic int counter = 0;

void* increment(void *arg) {
    for (int i = 0; i < 1000; i = i + 1) {
        atomic_fetch_add(&counter, 1);
    }
    return (void*)0;
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

## Standard Library Thread Module

The SafeC standard library provides higher-level concurrency primitives in the `thread` module:

- **Mutexes**: `mutex_create`, `mutex_lock`, `mutex_unlock`, `mutex_destroy`
- **Condition variables**: `cond_create`, `cond_wait`, `cond_signal`, `cond_broadcast`, `cond_timedwait_ms`
- **Read-write locks**: `rwlock_create`, `rwlock_read_lock`, `rwlock_write_lock`, `rwlock_unlock`
- **Thread utilities**: `thread_yield`, `thread_sleep_ms`, `thread_self`

These are cross-platform wrappers over POSIX threads (or Win32 threads with `-D__WINDOWS__`).

## Example: Parallel Computation

```c
#include <stdio.h>
#include <stdlib.h>

struct WorkItem {
    int *data;
    int start;
    int end;
    long long result;
};

void* sum_range(void *arg) {
    struct WorkItem *w = (struct WorkItem*)arg;
    long long sum = 0;
    for (int i = w->start; i < w->end; i = i + 1) {
        sum = sum + w->data[i];
    }
    w->result = sum;
    return (void*)0;
}

int main() {
    int N = 10000;
    int *data = (int*)malloc(N * sizeof(int));
    for (int i = 0; i < N; i = i + 1) {
        data[i] = i;
    }

    struct WorkItem w1 = {data, 0, N / 2, 0};
    struct WorkItem w2 = {data, N / 2, N, 0};

    long long h1 = spawn(sum_range, (void*)&w1);
    long long h2 = spawn(sum_range, (void*)&w2);

    join(h1);
    join(h2);

    long long total = w1.result + w2.result;
    printf("Total: %lld\n", total);

    free(data);
    return 0;
}
```
