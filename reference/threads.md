# Thread Safety

SafeC checks at compile time what a thread can reach. Every type is classified as **Send** (a value of it can move to another thread) and **Sync** (a `const &` to it can be shared between threads). The compiler derives both from a type's structure, and the thread APIs — [`spawn`](/reference/concurrency#spawn-and-join), `spawn_scoped`, channels, `std::thread_spawn`, `Mutex<T>`, the async thread pool — accept only what is safe to hand over. Together with [ownership](/reference/ownership) this rules out data races in code that stays out of `unsafe`.

Nothing here costs anything at run time: the checks only accept or reject programs.

## Send and Sync {#send-and-sync}

| Type | Send | Sync |
|------|------|------|
| scalars, enums, vectors, function references | yes | yes (reading is safe; concurrent *writes* through a mutable reference are not) |
| raw pointers `T*` | no | no |
| `&static T` / `const &static T`, region-less `&T` | yes, if `T` is Sync (mutable: if `T` synchronizes its own writes) | same |
| `&stack T`, `&arena<R> T`, `&heap T` | no: the referent may die while the other thread runs | as `T` |
| slices `[]T` | no (a view of memory that may not outlive the thread) | if the elements are |
| arrays, optionals, tuples, structs | if every member is | if every member is |

Inside a type that implements `Drop`, raw-pointer and `&heap` fields count as the type's own uniquely owned buffers, so they are as Send/Sync as what they point to: `Vec<int>` and `String` are Send.

A region-less reference (`&T` without a region keyword) has opted out of lifetime tracking everywhere in SafeC, and is treated like a `&static` one here too.

### Markers {#markers}

A struct can override the derived answer in its body:

```c
struct AtomicInt {
    int v;
    unsafe impl Send + Sync;   // every access is atomic
};

struct GuiWindow {
    void* handle;
    impl !Send;                // must stay on the thread that created it
    impl !Sync;
};
```

`unsafe impl Sync;` also promises that the type synchronizes writes to its own state, so a mutable reference to it may be shared too (that is what a lock is). `impl !Send;` / `impl !Sync;` take the property away even when the fields would allow it. A positive marker is `unsafe` because the compiler can't check it.

### Bounds {#bounds}

`Send` and `Sync` are [traits](/reference/generics#built-in-traits) and can bound generic parameters:

```c
generic<T: Send, R: Send>
struct SpawnedThread<T, R> thread_spawn(fn R(T) f, T arg);
```

```c
struct Raw { int *p; };
struct SpawnedThread<struct Raw, int> t = thread_spawn(raw_len, r);
// error: type 'Raw' does not satisfy constraint 'Send' for generic parameter 'T'
//        ('Raw' field 'p': raw pointer 'int*' can't be sent to another thread
//        (nothing guards what it points to))
```

## spawn {#spawn}

A thread started by `spawn` is not tied to any scope (it may run until the program ends), so its argument must be `null` or point to **static data**:

- a `const &static T` shares `T` for reading: `T` must be Sync;
- a mutable `&static T` shares it for writing: `T` must synchronize its own state (a `Mutex`, atomics);
- a reference to stack, arena or heap data is rejected;
- a raw pointer is accepted only inside `unsafe { }`.

```c
static struct Counter g_c;
static struct Mutex<int> g_total;   // = Mutex::new(0) before any thread starts

spawn(work, &c);         // error: spawn: the argument '&stack Counter' refers to data the
                         //        thread may outlive (a spawned thread isn't joined by its
                         //        scope): pass static data, or use spawn_scoped ...
spawn(work, &g_c);       // error: spawn: the thread would share 'Counter' (static data) with
                         //        every other thread through a mutable reference, but it is
                         //        not safe to write concurrently: ...
spawn(add, &g_total);    // OK: a Mutex synchronizes its own state
spawn(read_only, (const &static int)&g_limit);   // OK: int is Sync
```

## spawn_scoped {#spawn-scoped}

`spawn_scoped` threads are joined before the function that started them returns (the compiler emits the join on every exit path, like a `defer`), so they may borrow locals. The compiler then makes sure the borrow is respected:

- a mutable reference **hands the value over**: the argument's type must be Send, and the starting thread may not touch the value until it joins the thread;
- a const reference **shares it for reading**: the type must be Sync; the starting thread may keep reading but not write;
- a value of a Sync type that synchronizes itself (a `Mutex`, atomics) stays usable by both;
- the value must outlive the thread: a `join(h)` has to come before the end of its scope.

```c
void ok() {
    struct Counter c; c.n = 0;
    long long h = spawn_scoped(work, &c);
    join(h);
    c.n = c.n + 1;                        // OK: the thread is done
}

void touch() {
    struct Counter c; c.n = 0;
    long long h = spawn_scoped(work, &c);
    c.n = 5;      // error: 'c' is lent to the thread started here by spawn_scoped, which may
                  //        still be using it: join that thread (join(h)) before touching it again
    join(h);
}

void too_short() {
    long long h = 0;
    {
        struct Counter c; c.n = 0;
        h = spawn_scoped(work, &c);
    }             // error: 'c' goes away while the thread started by spawn_scoped may still
                  //        be using it: join that thread (join(h)) first
    join(h);
}
```

## Channels {#channels}

`chan_send(ch, &v)` requires `v`'s type to be Send, and a value of a [Drop type](/reference/ownership) moves to the receiver: using `v` afterwards is a use-after-move. `chan_recv(ch, &out)` stores a new value in `out`.

```c
struct Holds { int *p; };
chan_send(ch, &h);   // error: chan_send: a value of type 'Holds' can't be sent to another
                     //        thread: 'Holds' field 'p': raw pointer ...
```

The typed `std::Chan<T>` (`std/sync/channel.h`) and the async `AsyncChannel<T>` / `Oneshot<T>` require `T: Send`.

## Threads That Own Their Data {#thread-spawn}

`std::thread_spawn(f, arg)` (`std/thread.h`) moves `arg` into a new thread and returns a `SpawnedThread<T, R>`, a Drop value whose `join()` (a `consume` method) waits and returns `f`'s result. Nothing is shared, so nothing can race:

```c
int vlen(struct Vec<int> v) { int n = (int)v.len; v.drop(); return n; }

struct Vec<int> v = make_vec();
struct SpawnedThread<struct Vec<int>, int> t = thread_spawn(vlen, v);
v.push(1);           // error: use of 'v' after it was moved
int n = t.join();
```

Forgetting to join is a leak error like any other; dropping a `SpawnedThread` joins it and drops the result.

## Mutex and Guards {#mutex}

`Mutex<T>` owns a `T` that is reachable only while locked. `lock()` returns a `MutexGuard<T>`, a Drop value that unlocks in `drop()`; `guard.get()` returns a reference into the protected value that borrows from the guard:

```c
static struct Mutex<int> g_m;       // = Mutex::new(0) before any thread starts

void add(int k) {
    struct MutexGuard<int> g = g_m.lock();
    *g.get() = *g.get() + k;
    g.drop();                               // unlocks
}
```

The ownership rules make the classic mistakes compile errors:

```c
int forgot_unlock() {
    struct MutexGuard<int> g = g_m.lock();
    return *g.get();      // error: value of Drop type 'MutexGuard<int>' leaks when the
}                         //        function returns: 'g' is never dropped

int after_unlock() {
    struct MutexGuard<int> g = g_m.lock();
    &int v = g.get();
    g.drop();
    return *v;            // error: use of a reference borrowed from 'g' after 'g' was dropped
}

int mutex_gone() {
    struct Mutex<int> m = Mutex::new(1);
    struct MutexGuard<int> g = m.lock();
    m.drop();
    return *g.get();      // error: use of 'g', which borrows from 'm', after 'm' was dropped
}
```

A `MutexGuard` is `!Send`: it must be unlocked by the thread that locked it. `Mutex<T>` needs `T: Send` and is Sync. `RwLock<T>` (`T: Send + Sync`) works the same way with `read()` guards giving `const &T` and a `write()` guard giving `&T`. `into_inner()` consumes a mutex and returns the value.

## Async {#async}

- `ThreadPool::spawn` and `ThreadPool::block_on` require the future and its output to be Send: a task runs on a worker thread. A future that borrows a local (it holds a `&stack` reference) is not Send and is rejected.
- `LocalExecutor` runs everything on one thread and has no such requirement. Its tasks may borrow locals; dropping an `AsyncTask` that was spawned and has not finished panics, rather than leaving the executor to poll freed memory — finish the task, or `abort()` it and run the executor until it is done.
- `AsyncChannel<T>`, `Oneshot<T>` and `Chan<T>` require `T: Send`. The awaitable types (`Context`, wakers, timers, tasks, join handles) implement `Drop`, so every future is dropped exactly once.

## What Is Not Checked {#limitations}

- **`unsafe` and raw pointers.** A raw-pointer argument to `spawn` is accepted only in `unsafe`, and is then the programmer's responsibility; so is anything a positive marker (`unsafe impl Send/Sync`) promises.
- **The OS layer.** `std::thread_create` and the handle-based `mutex_*`, `cond_*`, `rwlock_*` functions of `std/thread.h` are the untyped primitives the checked layer is built on: they check nothing about what threads share. Use `thread_spawn`, `Mutex<T>` and `RwLock<T>` instead.
- **Region-less references** are treated as static (see [Send and Sync](#send-and-sync)).
- **Deadlocks**, lock ordering and locking a mutex twice on one thread are not detected; neither is starvation.
- **Atomics** are Sync, but the compiler doesn't check that a lock-free algorithm built on them is correct.
- **Foreign code.** Data handed to C through `extern` functions is outside the analysis.
