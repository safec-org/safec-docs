# Synchronization Primitives

SafeC provides four synchronization modules in `std/sync/` for both hosted (OS-based) and freestanding (bare-metal) environments — plus `bare_spawn.sc`, the reference "Hook" backend for the language-level `spawn`/`join` keywords on freestanding targets (see [thread](/stdlib/thread#backend-selection)).

```c
#include "sync/spinlock.h"
#include "sync/lockfree.h"
#include "sync/task.h"
#include "sync/thread_bare.h"
// or via:
#include "prelude.h"
```

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
    int a = 10, b = 20, c = 30;
    q.enqueue(&a);
    q.enqueue(&b);
    q.enqueue(&c);

    // Consumer
    int val;
    while (!q.is_empty()) {
        q.dequeue(&val);
        println_int(val);  // 10, 20, 30
    }

    q.destroy();
    return 0;
}
```

::: tip
In a real SPSC scenario, the producer and consumer run in separate threads or ISR contexts. This queue is safe without any additional locking as long as only one thread enqueues and only one thread dequeues.
:::

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
