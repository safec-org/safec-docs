# Synchronization Primitives

SafeC provides six synchronization modules in `std/sync/` for both hosted (OS-based) and freestanding (bare-metal) environments — plus `bare_spawn.sc`, the reference "Hook" backend for the language-level `spawn`/`join` keywords on freestanding targets (see [thread](/stdlib/thread#backend-selection)).

```c
#include "sync/spinlock.h"
#include "sync/lockfree.h"
#include "sync/channel.h"
#include "sync/mpsc.h"
#include "sync/task.h"
#include "sync/thread_bare.h"
// or via:
#include "prelude.h"
```

For communicating with a *different process* rather than another thread, see [IPC](/stdlib/ipc) (`std/ipc/pipe.h`, `std/ipc/uds.h`).

`TaskScheduler` (from `task.h`) is also the foundation the real-time I/O reactor drives — see [Real-Time Scheduler](/stdlib/sched) for the hosted (macOS/Linux) event loop that lets tasks block on file/socket readiness without stalling the whole scheduler.

---

## Spinlock

A busy-wait mutual exclusion lock built on `__sync_lock_test_and_set`. Suitable for short critical sections where sleeping is impossible or undesirable (interrupt handlers, bare-metal drivers).

### Struct

```c
struct Spinlock {
    volatile int locked;

    void lock();
    int  trylock();
    void unlock();
    int  is_locked() const;
}
```

### Constructor

```c
Spinlock spinlock_init();  // returns zeroed spinlock (unlocked)
```

### Methods

| Method | Description |
|--------|-------------|
| `lock()` | Spin until the lock is acquired. |
| `trylock()` | Attempt to acquire; return 1 if successful, 0 if already locked. |
| `unlock()` | Release the lock. |
| `is_locked() const` | Return 1 if currently locked (advisory only). |

### Example

```c
#include "sync/spinlock.h"
#include "io.h"

Spinlock g_lock = spinlock_init();
int g_counter = 0;

void increment() {
    g_lock.lock();
    g_counter++;
    g_lock.unlock();
}

int main() {
    increment();
    increment();
    println_int(g_counter);  // 2
    return 0;
}
```

::: warning
Do not hold a spinlock across blocking calls, sleep, or any operation that may take variable time. Use a mutex from `thread.h` for those cases.
:::

---

## LFQueue — Lock-Free SPSC Ring Buffer

A single-producer / single-consumer wait-free queue. Uses compiler barriers for memory ordering. Capacity must be a power of two.

### Struct

```c
struct LFQueue {
    &heap void    buffer;     // heap-backed element storage
    unsigned long cap;        // power-of-two capacity
    unsigned long elem_size;
    long long     head;       // producer writes here (atomic)
    long long     tail;       // consumer reads here (atomic)

    int           enqueue(const void* elem);
    int           dequeue(void* out);
    int           is_empty() const;
    int           is_full() const;
    unsigned long len() const;
    void          destroy();
}
```

### Constructor

```c
struct LFQueue lfq_new(unsigned long elem_size, unsigned long cap);   // heap-allocates its own buffer
struct LFQueue lfq_init(&heap void buffer, unsigned long elem_size, unsigned long cap);  // over caller-provided storage
```

`cap` must be a power of two (e.g. 64, 128, 256). A queue from `lfq_new` should be released with `.destroy()`; one from `lfq_init` owns nothing (the caller manages `buffer`'s lifetime).

### Methods

| Method | Description |
|--------|-------------|
| `enqueue(elem)` | Copy `elem` into queue. Returns 1 on success, 0 if full. Producer only. |
| `dequeue(out)` | Copy front element into `out`. Returns 1 on success, 0 if empty. Consumer only. |
| `is_empty() const` | Returns 1 if queue is empty. |
| `is_full() const` | Returns 1 if queue is full. |
| `len() const` | Current element count. |
| `destroy()` | Frees the heap-backed buffer (only meaningful for a queue from `lfq_new`). |

### Typed Generic Wrappers

For a fixed element type, `lfq_enqueue_t`/`lfq_dequeue_t` avoid manually taking addresses of locals:

```c
generic<T> int lfq_enqueue_t(&stack LFQueue q, T val);
generic<T> int lfq_dequeue_t(&stack LFQueue q, T* out);
```

### Example

```c
#include "sync/lockfree.h"
#include "io.h"

int main() {
    LFQueue q = lfq_new(sizeof(int), 64);

    // Producer
    int a = 10;
    int b = 20;
    int c = 30;
    unsafe {
        q.enqueue((const void*)&a);
        q.enqueue((const void*)&b);
        q.enqueue((const void*)&c);
    }

    // Consumer
    int val = 0;
    while (!q.is_empty()) {
        unsafe { q.dequeue((void*)&val); }
        println_int((long long)val);  // 10, 20, 30
    }

    q.destroy();
    return 0;
}
```

::: tip
In a real SPSC scenario, the producer and consumer run in separate threads or ISR contexts. This queue is safe without any additional locking as long as only one thread enqueues and only one thread dequeues.
:::

---

## Channel — Bounded Blocking MPMC Channel

The language has its own channel *syntax* built in — `chan_create`/`chan_send`/`chan_recv`/`chan_close` are compiler built-ins, no `#include` needed — backed by `std/sync/channel.h`'s runtime (a bounded, blocking, multi-producer/multi-consumer channel implemented with a mutex + condvar from `thread.h`). Unlike `LFQueue`/`MpscQueue` below, `chan_send`/`chan_recv` **block** the calling thread when the channel is full/empty rather than returning a full/empty indicator immediately.

```c
#include "sync/channel.h"
```

The built-ins are deliberately untyped at the compiler level: `chan_create` only ever takes a capacity, never an element type, so the runtime picks one fixed convention — every channel carries 8-byte ("machine word") payload slots, and the raw `chan_send`/`chan_recv` always copy exactly 8 bytes at `*value_ptr`/`*out_ptr`. Passing a pointer to something smaller directly (e.g. a bare `int`) is a real out-of-bounds hazard, not just a style concern — `chan_send_t<T>`/`chan_recv_t<T>` are the safe way to use a channel for any `T` that actually fits (`static_assert`'d at compile time, so an oversized `T` is a compile error here instead of silent corruption at the raw built-ins):

```c
generic<T> int chan_send_t(void* ch, T val);
generic<T> int chan_recv_t(void* ch, T* out);
```

### Example

```c
#include "sync/channel.h"
#include "io.h"

int main() {
    void* ch = chan_create(16);      // still the raw built-in
    std::chan_send_t(ch, 42);        // T=int inferred from the argument

    int v = 0;
    int ok;
    unsafe { ok = std::chan_recv_t(ch, (int*)&v); }
    if (ok) { println_int((long long)v); }   // 42

    chan_close(ch);                  // still the raw built-in
    return 0;
}
```

::: warning
The explicit `(int*)&v` cast (inside `unsafe`) on the receive side is not optional today — generic type inference doesn't unify a plain `&v` reference argument against a `T*` pointer parameter (a pre-existing compiler limitation, also affecting a few other `T*` out-parameter generics in the standard library). Passing `&v` alone fails with "cannot infer type arguments"; the explicit pointer cast is what makes `T` resolvable.

For a `T` larger than 8 bytes, box it on the heap and send the pointer instead — a pointer is always exactly 8 bytes on every target this compiler supports, so `chan_send_t(ch, myStructPtr)` (`T` inferred as `MyStruct*`) still fits the fixed slot size.
:::

---

## MpscQueue — Multi-Producer / Single-Consumer Ring Buffer

`LFQueue` above is the SPSC case: lock-free, but only correct with exactly one producer and one consumer — concurrent producers would race on the same `head` index with no coordination. `MpscQueue` is the multi-producer sibling: still a bounded ring buffer, still a non-blocking API (`enqueue`/`dequeue` return immediately with a full/empty indicator rather than blocking the caller — unlike `Channel` above), but correctness across multiple concurrent producers comes from a `Spinlock` guarding every mutation rather than a lock-free algorithm — a deliberate simplicity-over-cleverness choice over a hand-rolled CAS-based MPSC ring buffer.

```c
#include "sync/mpsc.h"
```

"Single-consumer" is a contract this type does not itself enforce (the spinlock would still serialize concurrent `dequeue()` calls correctly) — it's a naming/intent signal for callers, the same way `LFQueue`'s SPSC contract is documented rather than mechanically checked.

### Struct

```c
struct MpscQueue {
    &heap void      buffer;
    unsigned long   cap;        // capacity in elements — need not be a power of two (unlike LFQueue)
    unsigned long   elem_size;
    unsigned long   head;       // consumer reads here
    unsigned long   tail;       // producers write here
    unsigned long   count;      // pending element count
    struct Spinlock lock;       // guards head/tail/count and the buffer contents

    int           enqueue(const void* elem);   // 1 on success, 0 if full
    int           dequeue(void* out);          // 1 on success, 0 if empty
    int           is_empty() const;
    int           is_full() const;
    unsigned long len() const;
    void          destroy();                    // frees the backing buffer
}

struct MpscQueue mpsc_new(unsigned long elem_size, unsigned long cap);   // heap-allocates its own buffer
```

### Typed Generic Wrappers

```c
generic<T> int mpsc_enqueue_t(&stack MpscQueue q, T val);
generic<T> int mpsc_dequeue_t(&stack MpscQueue q, T* out);
```

### Example

```c
#include "sync/mpsc.h"
#include "io.h"

int main() {
    struct MpscQueue q = mpsc_new(sizeof(int), 64UL);

    int a = 10;
    int b = 20;
    int c = 30;
    unsafe {
        q.enqueue((const void*)&a);
        q.enqueue((const void*)&b);
        q.enqueue((const void*)&c);
    }

    int val = 0;
    while (!q.is_empty()) {
        unsafe { q.dequeue((void*)&val); }
        println_int((long long)val);  // 10, 20, 30
    }

    q.destroy();
    return 0;
}
```

---

## TaskScheduler — Cooperative Task Scheduler

A stackless cooperative (non-preemptive) task scheduler. Tasks are **resumable functions**, not plain callbacks: each task function takes its own argument plus a `resume_point` and returns where to resume next time (or `0` when done), so a task can yield mid-work without needing its own OS stack. `tick()` runs one full round — every non-blocked, non-done task gets one turn.

### Struct

```c
struct Task {
    void* func;          // int(*)(void* arg, int resume_point)
    void* arg;
    int   state;         // TASK_READY / TASK_RUNNING / TASK_DONE
    int   resume_point;
    int   blocked;        // set by await_fd(); see std/sched/reactor.h
    int   wait_fd;
    int   wait_filter;
};

struct TaskScheduler {
    struct Task tasks[TASK_MAX];   // TASK_MAX = 64
    int         count;
    int         current;

    int  spawn_task(void* func, void* arg);   // returns task index, or -1 if full
    int  tick();                               // one round; returns count of still-active tasks
    void run_all();                            // round-robin until every task is TASK_DONE
    int  active_count() const;

    // I/O-readiness blocking — see std/sched/reactor.h (std::Reactor / reactor_run)
    void await_fd(int fd, int filter);          // called from *within* the current task's own turn
    int  unblock_fd(int fd, int filter);         // called by the reactor when fd becomes ready
    int  has_blocked() const;
    int  has_ready() const;
}
```

### Constructor

```c
struct TaskScheduler task_sched_init();
```

### Task function contract

```c
int my_task(void* arg, int resume_point) {
    // resume_point tells you where you left off; return >0 to yield with
    // a new resume_point, or 0 when the task has finished.
    if (resume_point == 0) {
        // ... first chunk of work ...
        return 1;               // yield, resume here at point 1 next tick
    }
    // ... second chunk of work ...
    return 0;                   // done
}
```

A task that never yields (always returns via a path that isn't `0` after doing all its work in one call, or that blocks without calling `await_fd`) blocks the whole cooperative round — there's no preemption.

### Example

```c
#include "sync/task.h"
#include "io.h"

int task_a(void* arg, int resume_point) {
    println("Task A");
    return 0;   // done after one turn
}
int task_b(void* arg, int resume_point) {
    println("Task B");
    return 0;
}

int main() {
    struct TaskScheduler sched = task_sched_init();
    sched.spawn_task((void*)task_a, 0);
    sched.spawn_task((void*)task_b, 0);

    sched.run_all();   // Task A, Task B
    return 0;
}
```

### Blocking on I/O without stalling the scheduler

A task that would otherwise block on I/O calls `await_fd(fd, filter)` on itself instead of actually blocking; `tick()` then skips it (no re-invocation, no busy-polling) until something calls `unblock_fd()` with a matching `(fd, filter)` — normally `std::Reactor` reporting the fd became ready. See [Real-Time Scheduler](/stdlib/sched) for the reactor that drives this. A scheduler with no reactor driving it will spin `run_all()` forever if any task calls `await_fd()` and is never unblocked.

---

## ThreadSched — Priority-Based Freestanding Threads

`thread_bare.h` wraps `TaskScheduler` with a per-slot priority array, adding priority ordering on top of the same resumable-function protocol — still fully cooperative (a running thread must still voluntarily yield; higher priority only means "served earlier within a tick", not preemption).

```c
#include "sync/thread_bare.h"
```

### Type

```c
newtype Thread = int;   // index into the ThreadSched table; THREAD_NONE (-1) = spawn failed
```

### Struct

```c
struct ThreadSched {
    struct TaskScheduler inner;
    int                  priority[THREAD_MAX];   // THREAD_MAX = TASK_MAX = 64

    // func: int(*)(void* arg, int resume_point) — same protocol as TaskScheduler.
    // Higher priority values are served earlier within each tick.
    Thread spawn_thread(void* func, void* arg, int priority);

    int    tick();                      // one pass, descending-priority order; returns active count
    void   run_all();                   // run until every thread is done
    int    is_active(Thread t) const;
    void   join_thread(Thread t);       // cooperative join: calls tick() until t is done
    int    active_count() const;
}
```

### Constructor

```c
struct ThreadSched thread_sched_init();
```

### Example

```c
#include "sync/thread_bare.h"

int high_task(void* arg, int resume_point) { /* urgent work */ return 0; }
int low_task(void* arg, int resume_point)  { /* background work */ return 0; }

int main() {
    struct ThreadSched s = thread_sched_init();
    Thread hi = s.spawn_thread((void*)high_task, 0, 10);
    Thread lo = s.spawn_thread((void*)low_task,  0,  1);

    s.join_thread(hi);
    s.join_thread(lo);
    return 0;
}
```
