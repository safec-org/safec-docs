# thread -- Threading

SafeC has two layers of threading: the **`spawn`/`join`/`spawn_scoped` language keywords** (compiler built-ins, no `#include` needed), and the **`std::thread_*` library API** below (`thread.h`) for direct control over OS threads plus mutexes/condition variables/read-write locks. Both are backed by POSIX pthreads on Linux/macOS and Win32 threads on Windows; on freestanding/bare-metal targets (or any target the compiler doesn't recognize), the language keywords instead compile down to a documented two-symbol hook that `std/sync/bare_spawn.sc` implements cooperatively — see [Synchronization](/stdlib/sync) for that backend and for `std::thread_create`/`join`'s bare-metal-friendly cousins.

## Language-Level `spawn` / `join` / `spawn_scoped` {#language-level-spawn-join-spawn_scoped}

```c
long long spawn(fn, arg);          // fn: &static function reference; returns a thread handle
void      join(handle);
long long spawn_scoped(fn, arg);   // like spawn, but joined before the starting function returns
```

`spawn`'s first argument must be a `&static` reference to a function (an ordinary top-level function name decays to one implicitly); its second argument is passed through as the thread's argument. Thread functions take and return a nullable reference (`?&T f(?&T arg)`). The argument is checked (see [Thread Safety](/reference/threads#spawn)): for `spawn` it must be `null` or refer to static data, since the spawned thread could outlive the referent or race on it; `spawn_scoped` may lend a local. Both `spawn` and `spawn_scoped` return a `long long` handle to pass to `join`.

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

### Region Isolation {#region-isolation}

A `spawn`ed thread isn't joined by any scope, so its argument must be `null` or refer to **static data**: a `const &static T` whose `T` is Sync, or a mutable `&static T` only when `T` synchronizes its own state (a `Mutex`, atomics). A reference to stack, arena or heap data is a compile error ("the argument '&stack int' refers to data the thread may outlive"), and a raw pointer is accepted only inside `unsafe`. To give a thread a local, use `spawn_scoped`; the full rules are in [Thread Safety](/reference/threads#spawn).

### Backend Selection {#backend-selection}

The compiler picks a backend for `spawn`/`join`/`spawn_scoped` based on the build target:

| Target | Backend |
|--------|---------|
| `--freestanding` | Hook (see below) — no OS thread API is assumed |
| Explicit `--target` naming Windows | Win32 (`CreateThread`/`WaitForSingleObject`) |
| Explicit `--target` naming Linux/macOS/*BSD/Solaris | Pthreads |
| Explicit `--target` naming anything else (e.g. `wasm32-*`, `riscv64-unknown-elf`) | Hook |
| No `--target` given | Pthreads (host default) |

The **Hook** backend compiles `spawn`/`join` to calls against two fixed extern symbols — `__safec_thread_create(func, arg) -> i64` and `__safec_thread_join(i64) -> void` — a deliberate extension point: any runtime providing exactly those two symbols can back `spawn`/`join` without compiler changes. `std/sync/bare_spawn.sc` is the reference implementation, running spawned functions cooperatively on a `std::TaskScheduler` (see [Synchronization](/stdlib/sync)); a vendor RTOS shim could define the same two symbols instead.

## Typed Threads and Locks {#typed-threads}

The checked layer of `std/thread.h`, built on the handle-based functions below. The compiler's [Send/Sync and ownership checks](/reference/threads) apply to it: a value moved into `thread_spawn` can't be used afterwards, a guard that isn't dropped is a leak error, and a reference from `get()` can't outlive its guard.

```c
#include <std/thread.h>

// A thread that owns its argument (T: Send, R: Send). A Drop value.
generic<T, R> struct SpawnedThread {
    int  started() const;           // 0 if the thread couldn't be created
    R    join() consume;            // waits; f's result
    void drop();                    // join() and drop the result
};
generic<T: Send, R: Send> struct SpawnedThread<T, R> thread_spawn(fn R(T) f, T arg);   // arg moves in

generic<T: Send> struct Mutex {     // Sync: share it between threads
    static struct Mutex<T> new(T value);
    struct MutexGuard<T> lock() const;   // blocks; the guard is the only way to the value
    T    into_inner() consume;      // the value back
    void drop();
};
generic<T> struct MutexGuard {      // !Send: unlock on the locking thread
    &T   get();                     // valid until drop()
    void set(T value);              // replace (dropping the old value)
    void drop();                    // unlocks
};

generic<T: Send + Sync> struct RwLock {
    static struct RwLock<T> new(T value);
    struct RwReadGuard<T>  read() const;    // many at once: get() is const &T
    struct RwWriteGuard<T> write() const;   // exclusive: get() is &T
    void drop();
};
```

`Mutex` and `RwLock` keep the value in a heap box and must not move once locked (guards point at them); put a shared one in a `static` and initialize it before starting threads. Locking twice on one thread deadlocks (POSIX) — see the [OS-level mutex](#mutex).

```c
#include <std/thread.h>
#include <std/collections/vec.h>
extern int printf(const char *fmt, ...);

static struct Mutex<int> g_total;

long long sum(struct Vec<int> v) {           // owns v: nothing is shared
    long long s = 0LL;
    for (unsigned long i = 0UL; i < v.len; i = i + 1UL) s = s + (long long)*v.at(i);
    v.drop();
    return s;
}

?&int add_ten(?&int unused) {
    for (int i = 0; i < 10; i = i + 1) {
        struct MutexGuard<int> g = g_total.lock();
        *g.get() = *g.get() + 1;
        g.drop();                            // unlocks
    }
    return null;
}

int main() {
    struct Vec<int> v = Vec::new();
    for (int i = 1; i <= 100; i = i + 1) v.push(i);
    struct SpawnedThread<struct Vec<int>, long long> t = thread_spawn(sum, v);   // v moves in
    long long s = t.join();

    g_total = Mutex::new(0);
    long long h1 = spawn(add_ten, null);
    long long h2 = spawn(add_ten, null);
    join(h1);
    join(h2);
    int total = g_total.into_inner();

    struct RwLock<int> limit = RwLock::new(5);
    struct RwReadGuard<int> r = limit.read();
    int seen = *r.get();
    r.drop();
    struct RwWriteGuard<int> w = limit.write();
    *w.get() = 6;
    w.drop();
    limit.drop();

    printf("%lld %d %d\n", s, total, seen);
    return 0;
}
```

## Direct OS-Thread API (`thread.h`) {#direct-os-thread-api-threadh}

The rest of this page — `thread_create`/`mutex_*`/`cond_*`/`rwlock_*` — is a separate, explicitly-called API for when you want direct control over OS threads rather than the `spawn`/`join` keywords above. Both are pthread/Win32-backed on hosted targets; `thread_create`'s handle is ABI-compatible with `spawn`'s on Win32, but the two are otherwise independent (this API is not part of the Hook backend contract).

::: tip Checked threads
`std/thread.h` also has the typed layer the compiler's [Send/Sync checks](/reference/threads) apply to: `thread_spawn(f, arg)` moves an argument (`T: Send`) into a new thread and returns a `SpawnedThread<T, R>` whose `join()` gives back `f`'s result, and `Mutex<T>` / `RwLock<T>` own the value they protect. The handle-based functions below check nothing about what threads share; prefer the [typed layer](#typed-threads).
:::

```c
#include <std/thread.h>
```

## Handle Representation {#handle-representation}

All handles (thread IDs, mutexes, condition variables, read-write locks) are stored as `unsigned long long`:

- **POSIX**: `pthread_t` value (8 bytes on 64-bit), or a pointer to a heap-allocated pthread object
- **Win32**: `HANDLE` (kernel object pointer, 8 bytes on x64), or a pointer to a heap-allocated Windows primitive

## Thread {#thread}

```c
generic<T>
int  thread_create(&unsigned long long tid, fn ?&T(?&T) func, ?&T arg);
int  thread_join(unsigned long long tid);
int  thread_detach(unsigned long long tid);
void thread_yield();
void thread_sleep_ms(unsigned long ms);
unsigned long long thread_self();
```

### thread_create {#thread_create}

Start `func(arg)` on a new thread and write its handle to `*tid`; returns 0 on success. The thread function takes and returns a nullable reference to its context type — `static ?&Job work(?&Job job)` — and `T` is inferred from it, so passing the wrong context type is a compile error. Pass `null` when the thread needs no argument. The return value is discarded (`thread_join` only waits).

The untyped OS entry point underneath (POSIX `void*(*)(void*)`, Win32 `LPTHREAD_START_ROUTINE`) is `thread_create_raw_`; it is ABI-identical, since `?&T` is a plain pointer at the machine level.

### thread_join {#thread_join}

Block until thread `tid` finishes. Returns 0 on success.

### thread_detach {#thread_detach}

Detach thread `tid` so its resources are automatically reclaimed on exit. Returns 0 on success.

### thread_yield {#thread_yield}

Yield the current thread's time slice to other runnable threads.

### thread_sleep_ms {#thread_sleep_ms}

Sleep the calling thread for `ms` milliseconds.

### thread_self {#thread_self}

Return the calling thread's ID.

## Mutex {#mutex}

```c
int mutex_init(&unsigned long long m);
int mutex_destroy(&unsigned long long m);
int mutex_lock(&unsigned long long m);
int mutex_trylock(&unsigned long long m);
int mutex_unlock(&unsigned long long m);
```

### mutex_init / mutex_destroy {#mutex_init-mutex_destroy}

Initialize or destroy a mutex. The mutex handle is stored in `*m`.

### mutex_lock / mutex_unlock {#mutex_lock-mutex_unlock}

Acquire or release the mutex. `mutex_lock` blocks if the mutex is held by another thread.

### mutex_trylock {#mutex_trylock}

Attempt to acquire the mutex without blocking. Returns 0 if the lock was acquired, non-zero if busy.

Don't re-lock a mutex the calling thread already holds: on Windows (a `CRITICAL_SECTION`) that succeeds, while a default POSIX mutex deadlocks in `mutex_lock` and reports busy from `mutex_trylock`.

## Condition Variable {#condition-variable}

```c
int cond_init(&unsigned long long cv);
int cond_destroy(&unsigned long long cv);
int cond_wait(&unsigned long long cv, &unsigned long long m);
int cond_timedwait_ms(&unsigned long long cv, &unsigned long long m, unsigned long ms);
int cond_signal(&unsigned long long cv);
int cond_broadcast(&unsigned long long cv);
```

### cond_wait {#cond_wait}

Atomically release mutex `*m` and block until the condition variable is signaled. Re-acquires the mutex before returning.

### cond_timedwait_ms {#cond_timedwait_ms}

Like `cond_wait`, but waits at most `ms` milliseconds. Returns 0 if woken, `THREAD_TIMEDOUT` (1) if the time ran out, and -1 on another error — the same codes on every platform.

### cond_signal / cond_broadcast {#cond_signal-cond_broadcast}

Wake one (`signal`) or all (`broadcast`) threads waiting on the condition variable.

## Read-Write Lock {#read-write-lock}

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

## Example {#example}

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

## Producer-Consumer Example {#producer-consumer-example}

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
