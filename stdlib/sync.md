# Synchronization Primitives

SafeC provides six synchronization modules in `std/sync/` for both hosted (OS-based) and freestanding (bare-metal) environments — plus `bare_spawn.sc`, the reference "Hook" backend for the language-level `spawn`/`join` keywords on freestanding targets (see [thread](/stdlib/thread#backend-selection)).

```c
#include <std/sync/spinlock.h>
#include <std/sync/lockfree.h>     // LFQueue<T>
#include <std/sync/channel.h>      // Chan<T>
#include <std/sync/mpsc.h>         // MpscQueue<T>
#include <std/sync/task.h>         // TaskScheduler, task_spawn
#include <std/sync/thread_bare.h>  // ThreadSched, thread_spawn
#include <std/sync/parallel.h>     // parallel_for (thread pool)
```

For communicating with a *different process* rather than another thread, see [IPC](/stdlib/ipc) (`std/ipc/pipe.h`, `std/ipc/uds.h`).

`TaskScheduler` (from `task.h`) is also the foundation the real-time I/O reactor drives — see [Real-Time Scheduler](/stdlib/sched) for the hosted (macOS/Linux) event loop that lets tasks block on file/socket readiness without stalling the whole scheduler.

---

## Spinlock {#spinlock}

A busy-wait mutual exclusion lock built on `__sync_lock_test_and_set`. Suitable for short critical sections where sleeping is impossible or undesirable (interrupt handlers, bare-metal drivers).

### Struct {#struct}

```c
struct Spinlock {
    volatile int locked;

    void lock();
    int  trylock();
    void unlock();
    int  is_locked() const;
}
```

### Constructor {#constructor}

```c
struct Spinlock spinlock_init();  // an unlocked spinlock
```

### Methods {#methods}

| Method | Description |
|--------|-------------|
| `lock()` | Spin until the lock is acquired. |
| `trylock()` | Attempt to acquire; return 1 if successful, 0 if already locked. |
| `unlock()` | Release the lock. |
| `is_locked() const` | Return 1 if currently locked (advisory only). |

### Example {#example}

```c
#include <std/sync/spinlock.h>
#include <std/io.h>

static struct Spinlock g_lock;   // zero-initialized == unlocked
static int g_counter = 0;

void increment() {
    g_lock.lock();
    g_counter = g_counter + 1;
    g_lock.unlock();
}

int main() {
    g_lock = std::spinlock_init();
    increment();
    increment();
    std::println_int(g_counter);  // 2
    return 0;
}
```

::: warning
Do not hold a spinlock across blocking calls, sleep, or any operation that may take variable time. Use a mutex from `thread.h` for those cases.
:::

---

## LFQueue\<T\> — Lock-Free SPSC Ring Buffer {#lfqueue}

A bounded single-producer / single-consumer queue. `head`/`tail` are monotonically increasing element counters updated with atomic stores (the element is written before `head` is published, and read before `tail` releases the slot); only the slot index is `counter & (cap - 1)`, so all `cap` slots are usable and "full" is told apart from "empty" without a sentinel slot.

```c
generic<T>
struct LFQueue {
    int           enqueue(T val);   // producer; 1 on success, 0 if full
    ?T            dequeue();        // consumer; null if empty
    int           is_empty() const;
    int           is_full() const;
    unsigned long len() const;
    void          destroy();        // frees the buffer if lfq_new allocated it
};

generic<T> struct LFQueue<T> lfq_new(unsigned long cap);                  // cap rounded UP to a power of two
generic<T> struct LFQueue<T> lfq_init(&heap T buffer, unsigned long cap); // caller's buffer; cap rounded DOWN
```

```c
struct LFQueue<long long> q = std::lfq_new(64UL);
q.enqueue(5LL);                               // producer thread
match (q.dequeue()) {                         // consumer thread
    case some(v): use(v);
    case none:    { }                         // empty right now
}
q.destroy();
```

Exactly one producer and one consumer; for several producers use `MpscQueue`.

---

## Chan\<T\> — Bounded Blocking Channel {#channel}

The language's `chan_create`/`chan_send`/`chan_recv`/`chan_close` built-ins are untyped: the runtime (`channel.sc`, a mutex + condvar MPMC queue) carries fixed 8-byte slots. `Chan<T>` fixes the element type and pads each value into a full slot, so nothing reads or writes past a smaller `T`; `sizeof(T) <= 8` is a compile-time `static_assert`. Unlike the queues on this page, `send`/`recv` **block** while the channel is full/empty.

```c
generic<T>
struct Chan {
    int  send(T val);   // blocks while full; 0 once closed
    ?T   recv();        // blocks while empty; null once closed *and* drained
    void close();       // wakes every blocked sender/receiver
};

generic<T> struct Chan<T> chan_new(int cap);   // cap < 1 is clamped to 1
```

```c
#include <std/sync/channel.h>
#include <std/thread.h>

static struct Chan<int> g_ch;

static ?&int producer(?&int unused) {
    int i = 1;
    while (i <= 100) { g_ch.send(i); i = i + 1; }
    g_ch.close();
    return null;
}

int main() {
    g_ch = std::chan_new(8);
    unsigned long long tid = 0ULL;
    std::thread_create(&tid, producer, null);
    long long total = 0LL;
    int more = 1;
    while (more) {
        match (g_ch.recv()) {
            case some(v): total = total + (long long)v;
            case none:    more = 0;                     // closed and drained
        }
    }
    std::thread_join(tid);
    return total == 5050LL ? 0 : 1;
}
```

For a `T` larger than 8 bytes, send a `?&T` to it (a reference fits on every supported target) and keep the referent alive until it has been received.

---

## MpscQueue\<T\> — Multi-Producer / Single-Consumer Queue {#mpscqueue}

The multi-producer sibling of `LFQueue`: still bounded and non-blocking (`enqueue`/`dequeue` return at once), but correct with any number of concurrent producers because a `Spinlock` guards every mutation. "Single consumer" is a documented contract, not enforced (concurrent `dequeue()` calls would still be serialized correctly).

```c
generic<T>
struct MpscQueue {
    int           enqueue(T val);   // any thread; 1 on success, 0 if full
    ?T            dequeue();        // null if empty
    int           is_empty() const;
    int           is_full() const;
    unsigned long len() const;
    void          destroy();
};

generic<T> struct MpscQueue<T> mpsc_new(unsigned long cap);   // any cap (not just powers of two)
```

```c
static struct MpscQueue<int> g_q;

static ?&int producer(?&int base) {
    int b = base.default(0);
    int i = 0;
    while (i < 1000) {
        while (!g_q.enqueue(b + i)) { std::thread_yield(); }   // full: back off
        i = i + 1;
    }
    return null;
}
```

---

## TaskScheduler — Cooperative Task Scheduler {#taskscheduler}

A stackless cooperative (non-preemptive) scheduler. Tasks are **resumable functions**: each takes its argument plus a `resume_point` and returns where to resume next time, or `0` when done, so a task can yield mid-work without an OS stack of its own. `tick()` runs one round — every non-blocked, non-done task gets one turn.

```c
struct TaskScheduler {
    struct Task tasks[TASK_MAX];   // TASK_MAX = 1024; finished slots are reused
    int         count;
    int         current;

    int  tick();                    // one round; returns the number of still-active tasks
    void run_all();                 // round-robin until every task is done
    int  active_count() const;

    // I/O-readiness blocking — see std/sched/reactor.h
    void await_fd(int fd, int filter);   // from *within* the running task's turn
    int  unblock_fd(int fd, int filter); // called by the reactor
    int  has_blocked() const;
    int  has_ready() const;
};

struct TaskScheduler task_sched_init();

// Spawn func(arg, resume_point). Returns the task index, or -1 if full.
generic<T> int task_spawn(&TaskScheduler s, fn int(?&T, int) func, ?&T arg);
```

A task's argument type is whatever its function takes: `task_spawn` infers `T` from the function, so a mismatched argument is a compile error rather than a bad cast at run time. A `null` argument is fine — `T` still comes from the function.

```c
#include <std/sync/task.h>
#include <std/io.h>

struct Blinker { int led; int times; };

static int blink(?&Blinker b, int step) {
    match (b) {
        case some(x): { x.times = x.times + 1; }
        case null:    return 0;
    }
    return step < 2 ? step + 1 : 0;          // yield twice, then finish
}

static int hello(?&int unused, int step) {
    std::println("hello");
    return 0;
}

int main() {
    struct TaskScheduler sched = std::task_sched_init();
    struct Blinker b;
    b.led = 13;
    b.times = 0;
    unsafe { std::task_spawn(&sched, blink, (?&Blinker)&b); }
    std::task_spawn(&sched, hello, null);
    sched.run_all();
    return b.times == 3 ? 0 : 1;
}
```

There is no preemption: a task that never returns stalls the whole round. A task that would block on I/O calls `await_fd(fd, filter)` instead; `tick()` then skips it until `unblock_fd()` — normally `std::Reactor` — reports the fd ready. See [Real-Time Scheduler](/stdlib/sched). Without a reactor, `run_all()` spins forever on a task that awaits and is never unblocked.

---

Task states: `TASK_READY` (0), `TASK_RUNNING` (1), `TASK_DONE` (2). A scheduler holds up to `TASK_MAX` (1024) tasks.

## ThreadSched — Priority-Based Freestanding Threads {#threadsched}

`thread_bare.h` adds a per-slot priority to `TaskScheduler`, with the same resumable-function protocol. It is still cooperative: a higher priority only means "served earlier within a tick".

```c
#include <std/sync/thread_bare.h>

newtype Thread = int;   // index into the table; THREAD_NONE (-1) = spawn failed

struct ThreadSched {
    struct TaskScheduler inner;
    int                  priority[THREAD_MAX];   // THREAD_MAX = TASK_MAX

    int  tick();                      // one pass in descending-priority order
    void run_all();
    int  is_active(Thread t) const;
    void join_thread(Thread t);       // cooperative join: tick() until t is done
    int  active_count() const;
};

struct ThreadSched thread_sched_init();

generic<T> Thread thread_spawn(&ThreadSched s, fn int(?&T, int) func, ?&T arg, int priority);
```

```c
static int urgent(?&int unused, int step)     { return 0; }
static int background(?&int unused, int step) { return 0; }

void run() {
    struct ThreadSched s = std::thread_sched_init();
    Thread hi = std::thread_spawn(&s, urgent, null, 10);
    std::thread_spawn(&s, background, null, 1);
    s.join_thread(hi);
    s.run_all();
}
```

A `ThreadSched` holds `TASK_MAX` task slots inline (tens of KB); on small targets keep it in static storage.

---

## parallel_for — Data-Parallel Loops {#parallel}

```c
#include <std/sync/parallel.h>
```

Splits `[0, n)` into chunks of at least `grain` indices and runs `body(ctx, lo, hi)` on each, on the calling thread plus a persistent pool of worker threads. It returns once every chunk has finished. Chunks are handed out from an atomic counter, so uneven chunks balance out.

The workers start on the first call and are then kept, parked on a condition variable, so later calls cost a wake-up (a few microseconds) instead of a thread spawn. std's own CPU kernels use the pool: `sgemm` (and through it `tensor_matmul` and `Conv2D::forward`), the large-FFT and batched-FFT paths, `fft2d` and `stft_forward`.

```c
generic<T>
void parallel_for(unsigned long n, unsigned long grain,
                  fn void(&T, unsigned long, unsigned long) body, &T ctx);

unsigned long parallel_threads();               // threads a call uses, counting the caller
void          parallel_set_threads(unsigned long n);   // 1 .. PARALLEL_MAX_THREADS (64)
```

| Situation | Behaviour |
|---|---|
| `n <= grain`, or one thread configured | `body(ctx, 0, n)` inline |
| A `parallel_for` already running (nested call from a body, or a concurrent call from another thread) | inline, on the calling thread |
| Freestanding / bare-metal, WebAssembly | inline (no threads) |

The thread count is `SAFEC_NUM_THREADS` if set, else the number of online CPUs (capped at 64), or whatever `parallel_set_threads` last set.

Choose `grain` so a chunk does at least ~10–50 µs of work. Each index range belongs to exactly one call of `body`, so chunks can write disjoint parts of the output without locking.

```c
#include <std/sync/parallel.h>

struct Scale { &float data; float k; };

static void scale_range(&Scale s, unsigned long lo, unsigned long hi) {
    unsigned long i = lo;
    while (i < hi) {
        unsafe { s.data[i] = s.data[i] * s.k; }
        i = i + 1UL;
    }
}

void scale_all(&float data, unsigned long n, float k) {
    struct Scale s;
    s.data = data;
    s.k = k;
    std::parallel_for(n, 65536UL, scale_range, &s);
}
```

::: warning Not for real-time code
A call blocks until its slowest chunk finishes, and it wakes threads through the OS scheduler. Audio callbacks and other hard-deadline code should stay on one thread: use `sgemm_serial`, `fft_serial` and friends there.
:::

