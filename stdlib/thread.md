# thread -- Threading

SafeC has two layers of threading: the **`spawn`/`join`/`spawn_scoped` language keywords** (compiler built-ins, no `#include` needed), and the **`std::thread_*` library API** below (`thread.h`) for direct control over OS threads plus mutexes/condition variables/read-write locks. Both are backed by POSIX pthreads on Linux/macOS and Win32 threads on Windows; on freestanding/bare-metal targets (or any target the compiler doesn't recognize), the language keywords instead compile down to a documented two-symbol hook that `std/sync/bare_spawn.sc` implements cooperatively — see [Synchronization](/stdlib/sync) for that backend and for `std::thread_create`/`join`'s bare-metal-friendly cousins.

## Language-Level `spawn` / `join` / `spawn_scoped`

```c
long long spawn(fn, arg);          // fn: &static function reference; returns a thread handle
void      join(handle);
long long spawn_scoped(fn, arg);   // like spawn, but the compiler guarantees a join before scope exit
```

`spawn`'s first argument must be a `&static` reference to a function (an ordinary top-level function name decays to one implicitly); its second argument is passed through as the thread's `void*` argument. Both `spawn` and `spawn_scoped` return a `long long` handle to pass to `join`.

```c
extern int printf(const char *fmt, ...);

void* worker0(void* arg) { printf("thread 0\n"); return (void*)0; }
void* worker1(void* arg) { printf("thread 1\n"); return (void*)0; }

int main() {
    long long h0 = spawn(worker0, 0);
    long long h1 = spawn(worker1, 0);

    join(h0);
    join(h1);

    printf("all threads done\n");
    return 0;
}
```

### Region Isolation

`spawn`'s argument may not be a **mutable, non-`&static`** reference — passing one is a compile error ("region isolation violation"), since the spawned thread could outlive the region the reference points into. Pass a `&static` reference, an immutable reference, or a plain value (e.g. an index or a heap pointer you manage explicitly) instead.

### Backend Selection

The compiler picks a backend for `spawn`/`join`/`spawn_scoped` based on the build target:

| Target | Backend |
|--------|---------|
| `--freestanding` | Hook (see below) — no OS thread API is assumed |
| Explicit `--target` naming Windows | Win32 (`CreateThread`/`WaitForSingleObject`) |
| Explicit `--target` naming Linux/macOS/*BSD/Solaris | Pthreads |
| Explicit `--target` naming anything else (e.g. `wasm32-*`, `riscv64-unknown-elf`) | Hook |
| No `--target` given | Pthreads (host default) |

The **Hook** backend compiles `spawn`/`join` to calls against two fixed extern symbols — `__safec_thread_create(func, arg) -> i64` and `__safec_thread_join(i64) -> void` — a deliberate extension point: any runtime providing exactly those two symbols can back `spawn`/`join` without compiler changes. `std/sync/bare_spawn.sc` is the reference implementation, running spawned functions cooperatively on a `std::TaskScheduler` (see [Synchronization](/stdlib/sync)); a vendor RTOS shim could define the same two symbols instead.

## Direct OS-Thread API (`thread.h`)

The rest of this page — `thread_create`/`mutex_*`/`cond_*`/`rwlock_*` — is a separate, explicitly-called API for when you want direct control over OS threads rather than the `spawn`/`join` keywords above. Both are pthread/Win32-backed on hosted targets; `thread_create`'s handle is ABI-compatible with `spawn`'s on Win32, but the two are otherwise independent (this API is not part of the Hook backend contract).

```c
#include "thread.h"
```

## Handle Representation

All handles (thread IDs, mutexes, condition variables, read-write locks) are stored as `unsigned long long`:

- **POSIX**: `pthread_t` value (8 bytes on 64-bit), or a pointer to a heap-allocated pthread object
- **Win32**: `HANDLE` (kernel object pointer, 8 bytes on x64), or a pointer to a heap-allocated Windows primitive

## Thread

```c
int  thread_create(unsigned long long* tid, void* fn, void* arg);
int  thread_join(unsigned long long tid);
int  thread_detach(unsigned long long tid);
void thread_yield();
void thread_sleep_ms(unsigned long ms);
unsigned long long thread_self();
```

### thread_create

Create a new thread. The function signature must be `void* fn(void*)` on POSIX or `DWORD WINAPI fn(LPVOID)` on Win32. The thread ID is written to `*tid`. Returns 0 on success.

### thread_join

Block until thread `tid` finishes. Returns 0 on success.

### thread_detach

Detach thread `tid` so its resources are automatically reclaimed on exit. Returns 0 on success.

### thread_yield

Yield the current thread's time slice to other runnable threads.

### thread_sleep_ms

Sleep the calling thread for `ms` milliseconds.

### thread_self

Return the calling thread's ID.

## Mutex

```c
int mutex_init(unsigned long long* m);
int mutex_destroy(unsigned long long* m);
int mutex_lock(unsigned long long* m);
int mutex_trylock(unsigned long long* m);
int mutex_unlock(unsigned long long* m);
```

### mutex_init / mutex_destroy

Initialize or destroy a mutex. The mutex handle is stored in `*m`.

### mutex_lock / mutex_unlock

Acquire or release the mutex. `mutex_lock` blocks if the mutex is held by another thread.

### mutex_trylock

Attempt to acquire the mutex without blocking. Returns 0 if the lock was acquired, non-zero if busy.

## Condition Variable

```c
int cond_init(unsigned long long* cv);
int cond_destroy(unsigned long long* cv);
int cond_wait(unsigned long long* cv, unsigned long long* m);
int cond_timedwait_ms(unsigned long long* cv, unsigned long long* m, unsigned long ms);
int cond_signal(unsigned long long* cv);
int cond_broadcast(unsigned long long* cv);
```

### cond_wait

Atomically release mutex `*m` and block until the condition variable is signaled. Re-acquires the mutex before returning.

### cond_timedwait_ms

Like `cond_wait`, but with a timeout in milliseconds. Returns non-zero on timeout.

### cond_signal / cond_broadcast

Wake one (`signal`) or all (`broadcast`) threads waiting on the condition variable.

## Read-Write Lock

```c
int rwlock_init(unsigned long long* rw);
int rwlock_destroy(unsigned long long* rw);
int rwlock_rdlock(unsigned long long* rw);
int rwlock_wrlock(unsigned long long* rw);
int rwlock_tryrdlock(unsigned long long* rw);
int rwlock_trywrlock(unsigned long long* rw);
int rwlock_rdunlock(unsigned long long* rw);
int rwlock_wrunlock(unsigned long long* rw);
```

Multiple simultaneous readers are allowed; writers are exclusive. Use `rdlock`/`rdunlock` for shared read access, and `wrlock`/`wrunlock` for exclusive write access.

## Example

```c
#include "thread.h"
#include "io.h"
#include "atomic.h"

int counter = 0;
unsigned long long mtx;

void* worker(void* arg) {
    int i = 0;
    while (i < 1000) {
        mutex_lock(&mtx);
        counter = counter + 1;
        mutex_unlock(&mtx);
        i = i + 1;
    }
    return 0;
}

int main() {
    mutex_init(&mtx);

    unsigned long long t1;
    unsigned long long t2;
    thread_create(&t1, worker, 0);
    thread_create(&t2, worker, 0);

    thread_join(t1);
    thread_join(t2);

    print("counter = ");
    println_int(counter);  // 2000

    mutex_destroy(&mtx);
    return 0;
}
```

## Producer-Consumer Example

```c
#include "thread.h"
#include "io.h"

int buffer = 0;
int ready  = 0;
unsigned long long mtx;
unsigned long long cv;

void* producer(void* arg) {
    mutex_lock(&mtx);
    buffer = 42;
    ready  = 1;
    cond_signal(&cv);
    mutex_unlock(&mtx);
    return 0;
}

void* consumer(void* arg) {
    mutex_lock(&mtx);
    while (ready == 0) {
        cond_wait(&cv, &mtx);
    }
    print("received: ");
    println_int(buffer);  // 42
    mutex_unlock(&mtx);
    return 0;
}

int main() {
    mutex_init(&mtx);
    cond_init(&cv);

    unsigned long long t1;
    unsigned long long t2;
    thread_create(&t1, consumer, 0);
    thread_create(&t2, producer, 0);

    thread_join(t1);
    thread_join(t2);

    cond_destroy(&cv);
    mutex_destroy(&mtx);
    return 0;
}
```
