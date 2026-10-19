# thread -- Threading

SafeC has two layers of threading: the **`spawn`/`join`/`spawn_scoped` language keywords** (compiler built-ins, no `#include` needed), and the **`std::thread_*` library API** below (`thread.h`) for direct control over OS threads plus mutexes/condition variables/read-write locks. Both are backed by POSIX pthreads on Linux/macOS and Win32 threads on Windows; on freestanding/bare-metal targets (or any target the compiler doesn't recognize), the language keywords instead compile down to a documented two-symbol hook that `std/sync/bare_spawn.sc` implements cooperatively — see [Synchronization](/stdlib/sync) for that backend and for `std::thread_create`/`join`'s bare-metal-friendly cousins.

## Language-Level `spawn` / `join` / `spawn_scoped`

```c
long long spawn(fn, arg);          // fn: &static function reference; returns a thread handle
void      join(handle);
long long spawn_scoped(fn, arg);   // like spawn, but the compiler guarantees a join before scope exit
```

`spawn`'s first argument must be a `&static` reference to a function (an ordinary top-level function name decays to one implicitly); its second argument is passed through as the thread's argument. Thread functions take and return a nullable reference (`?&T f(?&T arg)`). The argument must respect region isolation: the compiler rejects a mutable reference that isn't `&static`, since the spawned thread could outlive the referent or race on it. Both `spawn` and `spawn_scoped` return a `long long` handle to pass to `join`.

```c
extern int printf(const char *fmt, ...);

static ?&int worker0(?&int unused) { unsafe { printf("thread 0\n"); } return null; }
static ?&int worker1(?&int unused) { unsafe { printf("thread 1\n"); } return null; }

int main() {
    long long h0 = spawn(worker0, null);
    long long h1 = spawn(worker1, null);

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
#include <std/thread.h>
```

## Handle Representation

All handles (thread IDs, mutexes, condition variables, read-write locks) are stored as `unsigned long long`:

- **POSIX**: `pthread_t` value (8 bytes on 64-bit), or a pointer to a heap-allocated pthread object
- **Win32**: `HANDLE` (kernel object pointer, 8 bytes on x64), or a pointer to a heap-allocated Windows primitive

## Thread

```c
generic<T>
int  thread_create(&unsigned long long tid, fn ?&T(?&T) func, ?&T arg);
int  thread_join(unsigned long long tid);
int  thread_detach(unsigned long long tid);
void thread_yield();
void thread_sleep_ms(unsigned long ms);
unsigned long long thread_self();
```

### thread_create

Start `func(arg)` on a new thread and write its handle to `*tid`; returns 0 on success. The thread function takes and returns a nullable reference to its context type — `static ?&Job work(?&Job job)` — and `T` is inferred from it, so passing the wrong context type is a compile error. Pass `null` when the thread needs no argument. The return value is discarded (`thread_join` only waits).

The untyped OS entry point underneath (POSIX `void*(*)(void*)`, Win32 `LPTHREAD_START_ROUTINE`) is `thread_create_raw_`; it is ABI-identical, since `?&T` is a plain pointer at the machine level.

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
int mutex_init(&unsigned long long m);
int mutex_destroy(&unsigned long long m);
int mutex_lock(&unsigned long long m);
int mutex_trylock(&unsigned long long m);
int mutex_unlock(&unsigned long long m);
```

### mutex_init / mutex_destroy

Initialize or destroy a mutex. The mutex handle is stored in `*m`.

### mutex_lock / mutex_unlock

Acquire or release the mutex. `mutex_lock` blocks if the mutex is held by another thread.

### mutex_trylock

Attempt to acquire the mutex without blocking. Returns 0 if the lock was acquired, non-zero if busy.

## Condition Variable

```c
int cond_init(&unsigned long long cv);
int cond_destroy(&unsigned long long cv);
int cond_wait(&unsigned long long cv, &unsigned long long m);
int cond_timedwait_ms(&unsigned long long cv, &unsigned long long m, unsigned long ms);
int cond_signal(&unsigned long long cv);
int cond_broadcast(&unsigned long long cv);
```

### cond_wait

Atomically release mutex `*m` and block until the condition variable is signaled. Re-acquires the mutex before returning.

### cond_timedwait_ms

Like `cond_wait`, but with a timeout in milliseconds. Returns non-zero on timeout.

### cond_signal / cond_broadcast

Wake one (`signal`) or all (`broadcast`) threads waiting on the condition variable.

## Read-Write Lock

```c
int rwlock_init(&unsigned long long rw);
int rwlock_destroy(&unsigned long long rw);
int rwlock_rdlock(&unsigned long long rw);
int rwlock_wrlock(&unsigned long long rw);
int rwlock_tryrdlock(&unsigned long long rw);
int rwlock_trywrlock(&unsigned long long rw);
int rwlock_rdunlock(&unsigned long long rw);
int rwlock_wrunlock(&unsigned long long rw);
```

Multiple simultaneous readers are allowed; writers are exclusive. Use `rdlock`/`rdunlock` for shared read access, and `wrlock`/`wrunlock` for exclusive write access.

## Example

```c
#include <std/thread.h>
#include <std/io.h>
#include <std/atomic.h>

static int counter = 0;
static unsigned long long mtx = 0ULL;

static ?&int worker(?&int unused) {
    int i = 0;
    while (i < 1000) {
        std::mutex_lock(&mtx);
        counter = counter + 1;
        std::mutex_unlock(&mtx);
        i = i + 1;
    }
    return null;
}

int main() {
    std::mutex_init(&mtx);

    unsigned long long t1 = 0ULL;
    unsigned long long t2 = 0ULL;
    std::thread_create(&t1, worker, null);
    std::thread_create(&t2, worker, null);

    std::thread_join(t1);
    std::thread_join(t2);

    std::print("counter = ");
    std::println_int(counter);  // 2000

    std::mutex_destroy(&mtx);
    return 0;
}
```

## Producer-Consumer Example

```c
#include <std/thread.h>
#include <std/io.h>

static int buffer = 0;
static int ready  = 0;
static unsigned long long mtx = 0ULL;
static unsigned long long cv = 0ULL;

static ?&int producer(?&int unused) {
    std::mutex_lock(&mtx);
    buffer = 42;
    ready  = 1;
    std::cond_signal(&cv);
    std::mutex_unlock(&mtx);
    return null;
}

static ?&int consumer(?&int unused) {
    std::mutex_lock(&mtx);
    while (ready == 0) {
        std::cond_wait(&cv, &mtx);
    }
    std::print("received: ");
    std::println_int(buffer);  // 42
    std::mutex_unlock(&mtx);
    return null;
}

int main() {
    std::mutex_init(&mtx);
    std::cond_init(&cv);

    unsigned long long t1 = 0ULL;
    unsigned long long t2 = 0ULL;
    std::thread_create(&t1, consumer, null);
    std::thread_create(&t2, producer, null);

    std::thread_join(t1);
    std::thread_join(t2);

    std::cond_destroy(&cv);
    std::mutex_destroy(&mtx);
    return 0;
}
```
