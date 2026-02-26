# Synchronization Primitives

SafeC provides three synchronization modules in `std/sync/` for both hosted (OS-based) and freestanding (bare-metal) environments.

```c
#include "sync/spinlock.h"
#include "sync/lockfree.h"
#include "sync/task.h"
#include "sync/thread_bare.h"
// or via:
#include "prelude.h"
```

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
    void*         buffer;
    unsigned long head;       // producer writes here (volatile)
    unsigned long tail;       // consumer reads here (volatile)
    unsigned long cap;        // power-of-two capacity
    unsigned long elem_size;

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
LFQueue lfq_new(unsigned long elem_size, unsigned long cap);
void    lfq_free(struct LFQueue* q);
```

`cap` must be a power of two (e.g. 64, 128, 256). Internally, elements are stored at `(head & (cap-1))` and `(tail & (cap-1))`.

### Methods

| Method | Description |
|--------|-------------|
| `enqueue(elem)` | Copy `elem` into queue. Returns 1 on success, 0 if full. Producer only. |
| `dequeue(out)` | Copy front element into `out`. Returns 1 on success, 0 if empty. Consumer only. |
| `is_empty() const` | Returns 1 if queue is empty. |
| `is_full() const` | Returns 1 if queue is full (`len == cap - 1`). |
| `len() const` | Current element count. |
| `destroy()` | Frees backing buffer. |

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

A lightweight cooperative (non-preemptive) task scheduler. Each task is a function pointer + argument; the scheduler runs one task per `tick()` call, cycling through all active tasks.

### Struct

```c
struct TaskScheduler {
    // internal task table (up to 16 tasks)

    int           spawn(void(*fn)(void*), void* arg);
    void          tick();
    void          run_all();
    unsigned long active_count() const;
}
```

### Constructor

```c
TaskScheduler task_sched_new();
```

### Methods

| Method | Description |
|--------|-------------|
| `spawn(fn, arg)` | Register a task function. Returns task id (0–15) or -1 if table full. |
| `tick()` | Run the next active task once, then advance the round-robin cursor. |
| `run_all()` | Run every active task once (one full round). |
| `active_count() const` | Number of registered tasks. |

### Example

```c
#include "sync/task.h"
#include "io.h"

void task_a(void* arg) { println("Task A"); }
void task_b(void* arg) { println("Task B"); }

int main() {
    TaskScheduler sched = task_sched_new();
    sched.spawn(task_a, 0);
    sched.spawn(task_b, 0);

    // Run three ticks (A, B, A)
    sched.tick();   // Task A
    sched.tick();   // Task B
    sched.tick();   // Task A

    // Or run all tasks once
    sched.run_all();  // Task A, Task B
    return 0;
}
```

### Bare-metal event loop pattern

```c
TaskScheduler sched = task_sched_new();
sched.spawn(poll_uart, &uart);
sched.spawn(poll_sensor, &sensor);
sched.spawn(process_data, &pipeline);

while (1) {
    sched.tick();
}
```

---

## ThreadSched — Priority-Based Freestanding Threads

`thread_bare.h` wraps `TaskScheduler` with a priority array, providing a minimal priority-ordered thread abstraction for bare-metal environments. No OS, no POSIX — just cooperative execution with priority hints.

```c
#include "sync/thread_bare.h"
```

### Type

```c
newtype Thread = int;  // handle returned by spawn
```

### Struct

```c
struct ThreadSched {
    // wraps TaskScheduler + int priority[16]
}
```

### API

```c
ThreadSched thread_sched_new();

Thread thread_sched_spawn(struct ThreadSched* s,
                           void(*fn)(void*), void* arg,
                           int priority);

void thread_sched_tick(struct ThreadSched* s);  // runs highest-priority ready task
void thread_sched_join(struct ThreadSched* s, Thread t);  // spin until task exits
int  thread_sched_is_active(struct ThreadSched* s, Thread t) const;
```

### Example

```c
#include "sync/thread_bare.h"

void high_task(void* arg) { /* urgent work */ }
void low_task(void* arg)  { /* background work */ }

int main() {
    ThreadSched s = thread_sched_new();
    Thread hi = thread_sched_spawn(&s, high_task, 0, 10);
    Thread lo = thread_sched_spawn(&s, low_task,  0,  1);

    while (thread_sched_is_active(&s, hi) ||
           thread_sched_is_active(&s, lo)) {
        thread_sched_tick(&s);
    }
    return 0;
}
```
