# async -- Futures, Executors and Async I/O

`std/async/` is Rust's async model as a library. SafeC has no `async fn` and no coroutines in the language; a **future** here is an ordinary struct with a `poll` method that returns `Poll<T>`, and the coroutine macros turn that `poll` into a resumable state machine written with `goto`. On top of that sit the pieces you know from Rust's `core::task` and tokio: wakers, tasks and `JoinHandle`, a deterministic single-threaded executor that needs no heap (and runs on bare metal and wasm32), a multi-threaded executor, timers, `join`/`select`/`timeout`, channels, `Mutex`/`Semaphore`/`Notify`, cancellation tokens, and async TCP on the [`std/sched`](/stdlib/sched) reactor.

```c
#include <std/async/async.h>   // everything that runs on any target
#include <std/async/host.h>    // block_on, HostPark            (hosted)
#include <std/async/pool.h>    // ThreadPool                    (hosted)
#include <std/async/net.h>     // Runtime, async TCP            (hosted)
```

Nothing in `std/async` allocates. Futures, tasks, channel buffers and executors live in memory you provide: a `static`, a local of `main`, a field of a longer-lived struct.

## Rust, side by side {#rust-side-by-side}

| Rust | SafeC `std/async` |
|---|---|
| `Poll::Ready(v)` / `Poll::Pending` | `Poll::ready(v)` / `Poll::pending()` — `struct Poll<T> { int ready_; T value; }` |
| `trait Future { type Output; fn poll(self: Pin<&mut Self>, cx: &mut Context) -> Poll<Output>; }` | `trait Future { poll(&Context cx); drop(); }` — `struct Poll<T> F::poll(&Context cx)`; the output is the `T` |
| `impl Drop` for a future | `F::drop()` — called explicitly (SafeC has no destructors) |
| `Pin` | a rule: a future doesn't move once it has been polled |
| `async fn` / `.await` | `ASYNC_BEGIN` / `AWAIT` / `ASYNC_RETURN` / `ASYNC_END` ([coroutines](#coroutines)) |
| `Context`, `cx.waker()` | `struct Context`, `cx.waker`, `cx.wake_by_ref()` |
| `Waker` (`RawWaker` + vtable) | `struct Waker`, built only by the generic `Waker::new` / `Waker::counted` |
| `AtomicWaker` | `WakerSlot` (one waker), `WaitList` (many) |
| `tokio::spawn`, `JoinHandle`, `abort()` | `ex.spawn(&task)` / `async_spawn(cx, &task)`, `JoinHandle<T>`, `abort()` |
| `futures::executor::block_on` | `block_on(&fut)` |
| current-thread runtime / `LocalSet` | `LocalExecutor`, and `Runtime` with I/O |
| multi-thread runtime | `ThreadPool` |
| `tokio::time::{sleep, interval, timeout}` | `Sleep`, `Interval`, `timeout()` |
| `join!`, `select!`, `join_all`, `select_all` | `join2`/`join3`, `select2`, `join_all`, `select_any` |
| `FutureExt::map`, `.then` | `map_fut`, `and_then` |
| `tokio::sync::{oneshot, mpsc, Mutex, Semaphore, Notify}` | `Oneshot`, `AsyncChannel`, `AsyncMutex`, `AsyncSemaphore`, `AsyncNotify` |
| `CancellationToken` | `CancelToken`, `with_cancel()` |
| `TcpListener`, `TcpStream` | `AsyncTcpListener`, `AsyncTcpStream` |
| `tokio::time::pause()` | `VirtualClock` |

Two differences run through everything:

- **Output types are written out.** SafeC generics can't name "the `T` of `F`'s `Poll<T>`" (there are no associated types), so a combinator carries its children's output types as type parameters, written in the declaration and inferred from it: `struct Join2<struct Sleep, struct Fetch, Unit, int> j = join2(...)`.
- **Dropping is explicit.** A future abandoned before it completes must be `drop()`ped, which is what unregisters its timer, channel slot or socket interest. Combinators drop the children they abandon, executors drop aborted tasks; your own coroutines drop the child they are awaiting (see [drop](#drop)).

## Quick start {#quick-start}

```c
#include <std/async/async.h>
#include <std/async/host.h>
extern int printf(const char* fmt, ...);

struct Countdown {
    int          st;      // resume point
    int          i;       // lives across awaits: a field, not a local
    struct Sleep d;       // the child future being awaited
    struct Poll<int> poll(&Context cx);
    void drop();
};

struct Poll<int> Countdown::poll(&Context cx) {
    ASYNC_BEGIN(self.st, 1);                       // one await point, numbered 1
    for (self.i = 3; self.i > 0; self.i = self.i - 1) {
        self.d = Sleep::new(100LL);                // ms
        AWAIT(1, self.d);                          // Pending until the timer fires
        printf("%d\n", self.i);
    }
    ASYNC_RETURN(42);
    ASYNC_END;
}

void Countdown::drop() {
    if (self.st == 1) self.d.drop();               // abandoned while sleeping
    self.st = ASYNC_DONE;
}

int main() {
    struct Countdown c;
    c.st = ASYNC_INIT;
    int v = block_on(&c);                          // prints 3 2 1, returns 42
    return v == 42 ? 0 : 1;
}
```

The coroutine macros are function-like macros, which need the compiler's `--compat-preprocessor`. In a safeguard package, turn it on in `Package.toml`:

```toml
[build]
compat_preprocessor = true
```

(`safeguard test` always compiles `tests/` that way.) Without it you can still write futures — the macros expand to plain `switch`/`goto`, shown [below](#writing-it-out-by-hand).

## The model {#the-model}

### Poll and Future {#poll-and-future}

```c
generic<T> struct Poll {
    int ready_;                     // 1 = Ready, 0 = Pending
    T   value;                      // set when Ready
    static struct Poll<T> ready(T v);
    static struct Poll<T> pending();
    int is_ready() const;
    int is_pending() const;
    ?T  take() const;               // the value, or null if Pending
};

trait Future {
    void poll(&Context cx);         // checked by name: returns struct Poll<T> for some T
    void drop();
}

typedef int Unit;                   // the output of a future that produces nothing (Rust's ())
#define UNIT 0
```

`poll` is called by an executor (or by a parent future) whenever the future might be able to make progress. It must never block. It either finishes — `return Poll::ready(v);` — or returns `Poll::pending()` **after** arranging for `cx.waker` to be woken once progress is possible: it registered the waker with a timer, a channel, the I/O driver, or (to be polled again right away) called `cx.wake_by_ref()`. A future that returns Pending without doing either is never polled again. Once Ready, a future is finished: don't poll it again.

`Future` is a [structural trait](/reference/generics#traits-and-constrained-generics) checked by method name. Executors and combinators bound their parameters with it (`generic<F: Future, T>`), so a struct that lacks `drop()` is rejected where you spawn or combine it.

### Waker {#waker}

A `Waker` tells an executor "poll the task this belongs to again". It is a type-erased pair of data and functions — Rust's `RawWaker` and its vtable — that only the generic constructors fill in, so every erased call is well-typed:

```c
struct Waker {
    generic<T> static struct Waker new(fn void(?&T, unsigned long) wake, ?&T data, unsigned long tag);
    generic<T> static struct Waker counted(fn void(?&T, unsigned long) wake,
                                           fn void(?&T, unsigned long) clone,
                                           fn void(?&T, unsigned long) drop,
                                           ?&T data, unsigned long tag);
    static struct Waker noop();

    void wake();                         // wake_by_ref(), then drop()
    void wake_by_ref() const;
    struct Waker clone() const;          // runs the clone hook
    void drop();                         // runs the drop hook; a dropped waker wakes nothing
    int  will_wake(const &Waker other) const;   // same task? (skip re-registering)
    int  is_noop() const;
};
```

`wake(data, tag)` schedules the task. `Waker::counted` adds clone/drop hooks for an executor whose wakers own a reference count; every executor in `std/async` uses plain `Waker::new` wakers, whose copies are free, because tasks live in caller storage that outlives every registration (a future unregisters in `drop()`). Wakers are small values: keep one with `clone()`, let it go with `drop()`.

For the common "remember who is waiting" jobs:

```c
struct WakerSlot {                     // one waiting future (Rust's AtomicWaker)
    static struct WakerSlot new();
    void register_(const &Waker w);    // keep a clone (replacing a different one)
    int  wake();                       // wake and release; 1 if there was one
    void clear();
};

#define ASYNC_WAITERS 32
struct WaitList {                      // several waiting futures, no heap
    static struct WaitList new();
    int  add(int id, const &Waker w);  // register or refresh; returns the id, 0 if full
    void remove(int id);
    int  contains(int id) const;
    int  wake_one();
    int  wake_all();
    int  len() const;
};
```

A `WaitList` id belongs to one registration: once it is woken or removed, a later registration in the same slot gets a new id, so a future holding a stale id can't disturb another one. A future that finds the list full gets id 0 and wakes itself instead (correct, just busier). Neither type locks; the structure holding it does (every channel and primitive below has a spinlock).

### Context {#context}

```c
struct Context {
    struct Waker  waker;     // the task being polled
    ?&TimerQueue  timers;    // the executor's timers: what Sleep/Interval/Timeout use
    ?&struct IoDriver io_;   // the executor's IoDriver (std/async/net.h), via io_driver(cx)
    ?&Erased      exec_;     // the executor, and its type-erased spawn (async_spawn; see std/erased.h)
    ?fn int(?&Erased, ?&struct TaskHeader) spawn_;

    static struct Context from_waker(struct Waker w);   // no timers, no I/O, no spawning
    static struct Context noop();                        // Waker::noop(): poll by hand
    void wake_by_ref() const;
};
```

Executors fill a `Context` in for every poll. Futures read it: they clone the waker if they need to keep it, and find the timer queue and the I/O driver there instead of holding handles of their own — so `Sleep::new(250LL)` needs nothing but a duration. `Context::from_waker` and `Context::noop` are for polling futures by hand (tests, or a combinator you're writing).

### Pinning {#pinning}

A future must not move once it has been polled until it completes or is dropped: timers, channels and the I/O driver remember its address (a `Sleep` registers itself with the timer wheel). Moving a future *before* its first poll is fine, and that is how futures are composed — `join2(a, b)` copies `a` and `b` into the `Join2`, `AsyncTask::new(fut)` copies `fut` into the task. Then put the composite where it will stay (a field, a static, the task) before polling it.

### drop {#drop}

SafeC has no destructors, so abandoning a future is a call: `fut.drop()`. It releases whatever the future registered (a timer, a waiter slot, socket interest, a half-open connection). `drop()` is idempotent and harmless after completion. Who calls it:

- **select / timeout / with_cancel** drop the loser, or the inner future when time runs out or the token is cancelled;
- **`JoinHandle::abort()`** makes the executor drop the task's future instead of polling it again;
- **your coroutine's own `drop()`** drops the one child it is awaiting at its current resume point (see [rule 6](#rules-and-pitfalls)).

## Coroutines with goto {#coroutines}

A coroutine is a future whose `poll` is written as straight-line code with await points. Its state — the resume point, and every variable that lives across an await — sits in the future's struct. Each `poll` jumps back to where the previous one stopped: a `switch` on the saved resume point dispatches to a `goto` label in the function body. That is the state machine `rustc` generates for an `async fn`, written out with the preprocessor.

```c
#include <std/async/coro.h>      // (async.h includes it)
```

| Macro | |
|---|---|
| `ASYNC_BEGIN(state, n)` | First statement of `poll`. `state` is the `int` field holding the resume point (`ASYNC_INIT` = 0 before the first poll); `n` is the number of await points, numbered `1..n` (at most `ASYNC_MAX_POINTS` = 32). |
| `AWAIT(k, fut)` | Await point `k`: poll `fut` (a future lvalue, normally a field of `self`) until it is Ready; its value is discarded. |
| `AWAIT_INTO(k, dst, fut)` | The same, storing the value in `dst` (a field). |
| `ASYNC_YIELD(k)` | Await point `k`: be Pending once (after waking itself), so the executor runs other tasks first. |
| `ASYNC_RETURN(v)` | Finish with `Poll::ready(v)` and mark the coroutine done. One statement: it can be a `match` arm or an unbraced `if` body. |
| `ASYNC_END` | Last statement of `poll`: reaching it (a path without `ASYNC_RETURN`) panics. |

`poll`'s `Context` parameter must be named `cx`.

### What the macros expand to {#what-the-macros-expand-to}

```c
struct Poll<int> Fetch::poll(&Context cx) {
    ASYNC_BEGIN(self.st, 2);
    self.total = 0;
    for (self.i = 0; self.i < 3; self.i = self.i + 1) {
        self.delay = Sleep::new(10LL);
        AWAIT(1, self.delay);
        self.total = self.total + self.i;
    }
    ASYNC_YIELD(2);
    ASYNC_RETURN(self.total);
    ASYNC_END;
}
```

becomes (modulo whitespace):

```c
struct Poll<int> Fetch::poll(&Context cx) {
    &AsyncResume __async_st = &(self.st);            // alias of the state field
    AsyncResume __async_point_1 = 1;                  // one marker per declared point
    AsyncResume __async_point_2 = 2;
    switch (*__async_st) {                            // resume where we stopped
    case 0: break;                                    // first poll: start at the top
    case 1: goto __async_resume_1;
    case 2: goto __async_resume_2;
    default:
        std::panic_at("std/async: coroutine polled after it completed", ...);
        return Poll::pending();
    }
    self.total = 0;
    for (self.i = 0; self.i < 3; self.i = self.i + 1) {
        self.delay = Sleep::new(10LL);
        *__async_st = __async_point_1;                // AWAIT(1, self.delay)
    __async_resume_1:                                 //   <- a goto target inside the loop
        if ((self.delay).poll(cx).is_pending() != 0) return Poll::pending();
        self.total = self.total + self.i;
    }
    *__async_st = __async_point_2;                    // ASYNC_YIELD(2)
    cx.wake_by_ref();
    return Poll::pending();
__async_resume_2:
    { }
    return std::async_finish_(__async_st, Poll::ready(self.total));   // ASYNC_RETURN
    std::panic_at("... reached ASYNC_END without ASYNC_RETURN", ...);  // ASYNC_END
    return Poll::pending();
}
```

The resume labels sit at the top level of the function's scope structure — inside the `for` loop here — and the dispatching `goto`s jump into them. SafeC has no computed `goto` (no `&&label`), so the dispatch is a `switch` over the numbered points, which is why `ASYNC_BEGIN` needs `n`.

### What the compiler checks {#what-the-compiler-checks}

SafeC's semantic analysis applies three `goto` rules (see [Control Flow](/reference/control-flow#goto-and-labels)) that turn coroutine misuse into compile errors:

- **No jump past a declaration.** A `goto` may not jump into the scope of a local variable past its declaration — at the label it would be in scope but uninitialized. Jumping into a nested block (a loop body) is fine when no declaration in scope there is skipped.
- **One label per name.** A duplicate label is an error.
- **Labels in `match` arms are not goto targets.**

| Mistake | Compile error |
|---|---|
| `int n = f();` after `ASYNC_BEGIN`, still in scope at a later `AWAIT` | `goto '__async_resume_1' jumps over the declaration of 'n' (line 12), which is still in scope at the label ...` |
| `for (int i = 0; ...) { AWAIT(1, ...); }` | the same, for `i` |
| the same point awaited twice: `AWAIT(1, ...); ... AWAIT(1, ...);` | `duplicate label '__async_resume_1'` |
| `AWAIT(3, ...)` with `ASYNC_BEGIN(st, 2)` | `use of undeclared identifier '__async_point_3'` |
| `ASYNC_BEGIN(st, 2)` but point 2 never awaited | `use of undeclared label '__async_resume_2'` |
| `AWAIT` inside a `match` arm | `use of undeclared label '__async_resume_1'` |
| `poll`'s parameter not named `cx` | `use of undeclared identifier 'cx'` |
| a struct passed to `spawn`/`join2`/... without `drop()` | a trait-bound error naming `Future` |

The macros declare `__async_st` and the markers before the dispatching `goto`, which is what makes them visible at every label. (They are declared with the `AsyncResume` typedef rather than `int` because the preprocessor's [hygiene pass](/reference/preprocessor) renames names a macro declares after a type keyword.)

### Rules and pitfalls {#rules-and-pitfalls}

**1. Locals that live across an await are fields.** Every `poll` starts afresh from the top of the function and jumps to the resume label; a local's value doesn't survive the `return Poll::pending()` in between. The compiler rejects a local that would be in scope across a resume label:

```c
// wrong: 'n' would be garbage after resuming (compile error)
struct Poll<int> Bad::poll(&Context cx) {
    ASYNC_BEGIN(self.st, 1);
    int n = compute();
    AWAIT(1, self.delay);
    ASYNC_RETURN(n);
    ASYNC_END;
}

// right: the state struct holds it
struct Poll<int> Good::poll(&Context cx) {
    ASYNC_BEGIN(self.st, 1);
    self.n = compute();
    AWAIT(1, self.delay);
    ASYNC_RETURN(self.n);
    ASYNC_END;
}
```

Loop counters too: `for (int i = 0; ...)` around an await is an error, `for (self.i = 0; ...)` isn't. Two kinds of locals are allowed: one in a block that ends before the next await (`{ int t = f(); self.n = t * 2; }`), and one declared *before* `ASYNC_BEGIN` — but that one is re-initialized on every poll, so it is scratch space, not state.

**2. Number the await points once each, `1..n`.** The compiler catches duplicates, numbers above `n` and declared-but-unused points (table above).

**3. `defer` runs at every suspension.** Each Pending is a `return`, and `return` runs the function's `defer`s. A `defer close(fd)` in a coroutine closes the fd the first time it waits. Clean up explicitly before `ASYNC_RETURN`, and in `drop()`.

**4. Borrows don't survive an await.** Don't keep a reference to a local, a stack value, or an arena allocation across an await: when the coroutine resumes, the stack frame it pointed into is gone, and an arena may have been reset by then (a [region](/reference/memory) only promises its lifetime to the code running inside it). Keep owned values or indices in the state struct. Pointers *into the state struct itself* — a slice of its own buffer handed to a read future, as in `self.c.read(self.buf[0..512])` — are fine, because the struct is pinned.

**5. A fresh child future for each await.** A future is polled to completion once. Re-create the field before awaiting it again (`self.delay = Sleep::new(...)` inside the loop above); awaiting a finished future again is a bug (a finished coroutine panics, others may return Pending forever).

**6. `drop()` drops the child of the current await point.** At resume point `k`, exactly one child is live: the one `AWAIT(k, ...)` is polling. Drop that one, then mark the coroutine done:

```c
void Fetch::drop() {
    if (self.st == 1) self.delay.drop();
    if (self.st == 2) self.rx.drop();
    self.st = ASYNC_DONE;
}
```

**7. A finished coroutine panics when polled** ("coroutine polled after it completed"), like Rust's "`async fn` resumed after completion".

**8. Await only inside `poll`'s own body.** Await points can't be in helper functions — a label is local to its function. A helper that needs to wait is itself a future (a struct with a `poll`), awaited from the parent.

### Writing it out by hand {#writing-it-out-by-hand}

Without `--compat-preprocessor` (or for a leaf future that waits on something itself) write the state machine directly. The same rules apply, and the compiler's goto checks still protect you:

```c
struct Twice {
    int          st;
    struct Sleep d;
    int          round;
    struct Poll<int> poll(&Context cx);
    void drop();
};

struct Poll<int> Twice::poll(&Context cx) {
    switch (self.st) {
    case 0: break;
    case 1: goto resume1;
    default: return Poll::pending();
    }
    for (self.round = 0; self.round < 2; self.round = self.round + 1) {
        self.d = Sleep::new(50LL);
        self.st = 1;
    resume1:
        if (self.d.poll(cx).is_pending() != 0) return Poll::pending();
    }
    self.st = -1;
    return Poll::ready(self.round);
}

void Twice::drop() { if (self.st == 1) self.d.drop(); self.st = -1; }
```

And a leaf future — one that waits on an event rather than on other futures — keeps the waker of whoever polls it:

```c
struct Flag {                      // Ready once someone calls set()
    int              is_set;
    struct WakerSlot waiter;
    struct Poll<Unit> poll(&Context cx);
    void drop();
    void set();
};

struct Poll<Unit> Flag::poll(&Context cx) {
    if (self.is_set != 0) return Poll::ready(UNIT);
    self.waiter.register_(&cx.waker);         // first register ...
    return Poll::pending();                    // ... then report Pending
}
void Flag::set()  { self.is_set = 1; self.waiter.wake(); }
void Flag::drop() { self.waiter.clear(); }
```

(Shared between threads it would need a lock around the check and the registration, as `std/async`'s own primitives have.)

## Tasks and JoinHandle {#tasks}

An executor runs **tasks**: top-level futures together with room for their output.

```c
generic<F: Future, T> struct AsyncTask {
    struct TaskHeader hdr;             // what executors and wakers see
    F                 fut;
    T                 out[1];          // the output once finished
    static struct AsyncTask<F, T> new(F fut);
    int is_finished() const;           // done, either way
    int is_aborted() const;
    ?T  take() const;                  // the output if it finished (not aborted)
};

generic<T> struct JoinHandle {         // returned by spawn
    struct Poll<?T> poll(&Context cx); // await it: Ready(some(v)), or Ready(null) if aborted
    void abort();                      // cancel: the executor drops the future
    void drop();                       // stop waiting (the task keeps running: detach)
    int  is_finished() const;
};
```

The task's storage is yours and must stay put from `spawn` until the task has finished (or been aborted and its executor has seen that). Typical homes: a `static`, a local of `main` that outlives the executor's run, or a field of the parent future that spawns it (a server's per-connection handlers, say):

```c
static struct AsyncTask<struct Fetch, int> t1;      // static storage
t1 = AsyncTask::new(Fetch::new());
struct JoinHandle<int> h = ex.spawn(&t1);
```

Inside a task, `async_spawn(cx, &task)` spawns on whichever executor is polling it (tokio's `tokio::spawn`). A `JoinHandle` is a future: await it from another task with `AWAIT_INTO(k, self.result, self.handle)` (`self.result` is a `?T`).

## LocalExecutor {#local-executor}

```c
struct LocalExecutor {
    struct TimerQueue timers;           // handed to every poll as cx.timers
    static struct LocalExecutor new();
    generic<F, T> struct JoinHandle<T> spawn(&AsyncTask<F, T> task);
    int  run_ready();                   // one round over the woken tasks
    int  turn(long long now_ms);        // advance the timers to now, then one round
    generic<P: Park> void run(&P park); // until every task has finished
    generic<P: Park, F: Future, T> T block_on(&P park, &F fut);
    int  live_count() const;
    int  has_ready() const;
};
```

The single-threaded executor. It is **deterministic**: tasks are polled in spawn order, each woken task once per round, and the only time it knows is what its `Park` source reports. It uses **no heap, no locks and no OS**, so it builds for bare metal and wasm32 as well as hosted targets. Its wakers are safe to call from another thread or an interrupt handler: a wake is two atomic stores (the task's NOTIFIED bit, the executor's flag) plus `Park::unpark()`.

`run(&park)` loops: advance the timers to `park.now_ms()`, run a round, and — when nothing is runnable — `park.park(timeout)` until the next timer deadline (or `-1`: until something wakes a task). A task that keeps waking itself doesn't stop the timers from firing, since they advance before every round. `block_on(&park, &fut)` does the same until `fut` (polled on the calling thread, not as a task) is Ready, running spawned tasks meanwhile.

### Park sources {#park}

```c
trait Park {
    long long now_ms();               // monotonic milliseconds
    void park(long long timeout_ms);  // wait up to timeout (-1: until unpark), or less
    void unpark();                    // end the current (or the next) park early
}
```

| Park | Clock and waiting | Targets |
|---|---|---|
| `VirtualClock::new(start_ms)` | simulated time: `park(t)` jumps the clock forward by `t` instead of waiting (tokio's paused time) — runs take no real time and are exactly reproducible; `park(-1)` (every task waits, no timer pending) is a deadlock and panics | any |
| `FnPark::new(now_fn, idle_fn)` | your functions: a SysTick counter and `wfi` on a microcontroller, say | any |
| `HostPark::new()` | the OS monotonic clock, a condition variable to sleep on; `unpark` signals it | hosted (`host.h`) |
| `IoDriver` | the OS clock; waits in epoll/kqueue/WSAPoll | hosted (`net.h`) |

Deterministic test of timing logic:

```c
struct LocalExecutor ex = LocalExecutor::new();
struct VirtualClock clk = VirtualClock::new(0LL);
ex.spawn(&t1);
ex.spawn(&t2);
ex.run(&clk);
// clk.now is now exactly the virtual time the tasks needed
```

On a microcontroller, with a millisecond tick from an interrupt:

```c
static volatile long long g_ms = 0;               // incremented by the SysTick handler
static long long board_now() { return g_ms; }
static void board_idle(long long timeout_ms) { asm volatile("wfi"); }   // any interrupt wakes it

static struct LocalExecutor ex;
int main() {
    ex = LocalExecutor::new();
    struct FnPark park = FnPark::new(board_now, board_idle);
    ex.spawn(&blink_task);
    ex.spawn(&uart_task);
    ex.run(&park);
}
```

An interrupt handler that wakes a task (a UART receive completing a future) calls the task's waker, which only does atomic stores — safe from the handler.

### block_on {#block-on}

```c
#include <std/async/host.h>
generic<F: Future, T> T block_on(&F fut);
```

Runs `fut` to completion on the calling thread with a fresh `LocalExecutor` and a `HostPark` (Rust's `futures::executor::block_on`). Tasks it spawns run meanwhile on the same thread; any still running when `fut` finishes are abandoned with that executor. `T` is inferred from the target, so bind the result to a variable: `int n = block_on(&f);`.

## ThreadPool {#thread-pool}

```c
#include <std/async/pool.h>
struct ThreadPool {
    struct TimerQueue timers;            // shared by the workers
    static struct ThreadPool new();
    int  start(int n);                   // n worker threads (up to ASYNC_POOL_MAX_THREADS = 64)
    generic<F, T> struct JoinHandle<T> spawn(&AsyncTask<F, T> task);
    generic<F: Future, T> T block_on(F fut);   // run as a task, block this thread for the value
    void wait_idle();                    // every spawned task finished
    void shutdown();                     // stop and join the workers
};
```

The multi-threaded executor: `n` workers share one FIFO run queue (a shared-queue design, like tokio's multi-thread runtime without work stealing). The task types, timers, channels and primitives are the same as for `LocalExecutor`. A task never runs on two threads at once — a wake during a poll re-queues it after the poll — but successive polls may run on different workers, so whatever tasks share must be thread-safe (`std/async`'s primitives are; plain globals need atomics). Idle workers sleep on a condition variable until work arrives or the next timer is due; busy ones advance the timers between polls. Put the pool where it will stay before `start()` (the workers keep its address). I/O futures need a `Runtime` and don't run on the pool; a pool task can hand results to one through a channel or a `Oneshot`, whose wake reaches the `Runtime` across threads.

```c
static struct ThreadPool pool;
pool = ThreadPool::new();
pool.start(4);
for (int i = 0; i < 32; i = i + 1) { jobs[i] = AsyncTask::new(Job::new(i)); pool.spawn(&jobs[i]); }
pool.wait_idle();
int seven = pool.block_on(Job::new(7));
pool.shutdown();
```

## Timers {#timers}

```c
struct TimerQueue {                     // every executor owns one: cx.timers
    static struct TimerQueue new();
    int       advance(long long now_ms);   // move the clock, wake what is due
    long long now_ms() const;
    long long next_deadline() const;       // -1 if none
    long long park_timeout() const;        // ms until the next deadline (0 = due, -1 = none)
    int       pending() const;
};

struct Sleep {                          // Ready (UNIT) 'ms' after its first poll
    static struct Sleep new(long long ms);
    static struct Sleep until(long long deadline_ms);
    void reset(long long ms);           // re-arm (drops a pending registration)
    int  is_elapsed() const;
};

struct Interval {                       // a stream of ticks: Ready(1), Ready(2), ... one per period
    static struct Interval new(long long period_ms);
};
```

Times are milliseconds on the executor's clock: the OS monotonic clock with `HostPark`/`IoDriver`, whatever your `Park` counts on bare metal, simulated time with `VirtualClock`. A `Sleep`'s deadline is fixed when it is first polled, relative to the queue's current time; the queue only knows the time it was last advanced to, which keeps it deterministic.

The queue is [`std/dsp/timer_wheel.h`](/stdlib/dsp)'s `TimerWheel` with a 1 ms tick (64 concurrent timers), advanced in jumps from one due timer to the next rather than tick by tick. A `Sleep` that finds the wheel full waits in the queue's overflow list (`ASYNC_WAITERS` more) and retries when a slot frees up or its deadline comes. Every operation takes the queue's spinlock, so a `ThreadPool`'s workers share one.

`Interval` is polled again after each Ready — it is a stream. Ticks are due every period from the first poll; ticks missed while the task was busy are delivered back to back, then the schedule continues in phase (tokio's `MissedTickBehavior::Burst`).

## Combinators {#combinators}

All take their children by value (before the first poll) and own them:

| Constructor | Type | Output |
|---|---|---|
| `join2(a, b)` | `Join2<A, B, TA, TB>` | `Both<TA, TB>` (`.first`, `.second`) when both are Ready |
| `join3(a, b, c)` | `Join3<A, B, C, TA, TB, TC>` | `Both<TA, Both<TB, TC>>` |
| `join_all(futs, outs)` | `JoinAll<F, T>` | `int` (count); future `i`'s output in `outs[i]`. Slices, up to 64 |
| `select2(a, b)` | `Select2<A, B, TA, TB>` | `Either<TA, TB>`: `.which` 1 (`.left`) or 2 (`.right`); the loser is dropped |
| `select_any(futs)` | `SelectAny<F, T>` | `Picked<T>` (`.index`, `.value`); the rest are dropped |
| `timeout(f, ms)` | `Timeout<F, T>` | `?T`: null if `ms` passed first (then `f` is dropped) |
| `map_fut(f, fn)` | `MapFut<F, T, U>` | `U` = `fn(output of f)` |
| `and_then(f, fn)` | `AndThen<F, T, G, U>` | runs `f`, then the future `fn(output)` returns; its `U` |
| `with_cancel(f, &token)` | `Cancellable<F, T>` | `?T`: null if the token was cancelled first ([sync](#sync)) |
| `Ready::new(v)`, `Pending::new()`, `YieldNow::new()` | leaf futures | `v` at once; never; Pending once, then `UNIT` |

Polling is in argument order (a select of two ready futures picks the first). The output type parameters are written in the declaration:

```c
struct Join2<struct Sleep, struct Fetch, Unit, int> j = join2(Sleep::new(50LL), Fetch::new());
struct Select2<struct AcceptFut, struct Cancelled, struct AsyncTcpStream, Unit> s =
    select2(listener.accept(), token.cancelled_fut());
struct Timeout<struct ReadFut, long long> t = timeout(conn.read(self.buf[0..512]), 1000LL);
```

Inside a coroutine, keep the combinator in a field and await it like any other future: `AWAIT_INTO(2, self.res, self.t);` with `?long long res`.

## Channels {#channels}

```c
generic<T> struct Oneshot {             // one value, sent once
    static struct Oneshot<T> new();
    int  send(T v);                     // 1, or 0 if already sent / closed
    void close();                       // the receiver gets null
    struct OneshotRecv<T> recv();       // future: Ready(some(v)) or Ready(null)
    ?T   try_recv();
};

generic<T> struct AsyncChannel {        // bounded MPMC FIFO over your buffer
    static struct AsyncChannel<T> new([]T storage);
    struct ChanSend<T> send(T v);       // future: Ready(1) sent, Ready(0) closed
    struct ChanRecv<T> recv();          // future: Ready(some(v)), Ready(null) once closed and drained
    int  try_send(T v);                 // 1 sent, 0 full, -1 closed
    ?T   try_recv();
    void close();
    unsigned long len() const;
    unsigned long capacity() const;
};
```

A full channel makes senders wait (backpressure); an empty one makes receivers wait. `close()` completes pending sends with 0 and lets receivers drain what is buffered before they get null. Every operation takes the channel's spinlock, so producers and consumers can be tasks on different executors or threads. A channel must not move while send/receive futures from it exist; make a new future for each value.

```c
static int slots[8];
static struct AsyncChannel<int> ch;
ch = AsyncChannel::new(slots[0..8]);
// producer coroutine:  self.tx = ch.send(v);  AWAIT_INTO(1, self.ok, self.tx);
// consumer coroutine:  self.rx = ch.recv();   AWAIT_INTO(1, self.got, self.rx);   // ?int
```

## Mutex, Semaphore, Notify, cancellation {#sync}

```c
struct AsyncMutex {
    static struct AsyncMutex new();
    struct MutexLock lock();            // future: Ready once this task holds the lock
    int  try_lock();
    void unlock();
};

struct AsyncSemaphore {
    static struct AsyncSemaphore new(long long permits);
    struct SemAcquire acquire(long long n);
    int  try_acquire(long long n);
    void release(long long n);
    long long available() const;
};

struct AsyncNotify {
    static struct AsyncNotify new();
    struct Notified notified();         // future
    void notify_one();                  // wake one waiter, or store one permit
    void notify_waiters();              // wake everyone waiting now, store nothing
};

struct CancelToken {
    static struct CancelToken new();
    static struct CancelToken child_of(&CancelToken parent);   // cancelled with any ancestor
    void cancel();
    int  is_cancelled() const;
    struct Cancelled cancelled_fut();   // future: Ready once cancelled
};
generic<F, T> struct Cancellable<F, T> with_cancel(F inner, &CancelToken token);
```

There are no lock guards (no destructors): whoever completes `lock()` / `acquire(n)` owns the lock or the permits and calls `unlock()` / `release(n)` itself. Holding them across awaits is what they are for. In `drop()`, a coroutine that holds the lock at its current await point releases it.

```c
struct Poll<int> Writer::poll(&Context cx) {
    ASYNC_BEGIN(self.st, 2);
    self.l = g_mutex.lock();
    AWAIT(1, self.l);                   // exclusive from here ...
    self.wr = g_conn.write_all(self.msg[0..self.len]);
    AWAIT_INTO(2, self.ok, self.wr);
    g_mutex.unlock();                   // ... to here, across an await
    ASYNC_RETURN(self.ok);
    ASYNC_END;
}
```

A typical shutdown: one `CancelToken`, every long-running loop selects between its next event and `token.cancelled_fut()`, and `main` cancels it when it is done (see the [example](#example)).

## Async I/O {#async-io}

```c
#include <std/async/net.h>

struct Runtime {                        // tokio's current-thread runtime
    struct LocalExecutor ex;
    struct IoDriver      io;
    static struct Runtime new();
    int  init();                        // open the OS event queue; 0 on success
    void close_();
    generic<F, T> struct JoinHandle<T> spawn(&AsyncTask<F, T> task);
    void run();
    generic<F: Future, T> T block_on(&F fut);
};

struct AsyncTcpListener {
    int fd;                                              // < 0: bind/listen failed
    static struct AsyncTcpListener bind(unsigned short port);   // 0: any free port
    struct AcceptFut accept();                           // -> struct AsyncTcpStream (.fd < 0 on error)
    unsigned short local_port() const;
    int close();
};

struct AsyncTcpStream {
    int fd;
    static struct AsyncTcpStream from_fd(int fd);
    static struct ConnectFut connect(unsigned int addr_network_order, unsigned short port);
    struct ReadFut     read([]unsigned char buf);        // -> long long: bytes, 0 at EOF, -1 on error
    struct WriteFut    write([]const unsigned char buf);       // -> long long: bytes written, -1 on error
    struct WriteAllFut write_all([]unsigned char buf);   // -> int: 1 all written, 0 on error
    int close();
};

struct IoReady { static struct IoReady new(int fd, int filter); };   // -> UNIT once ready
```

The I/O futures make the non-blocking call at once (sockets from [`std/sched/io_nb.h`](/stdlib/sched)); when it would block they register the task's waker with the `IoDriver` for that fd's readiness and are Pending. The `IoDriver` is a [`std::Reactor`](/stdlib/sched#reactor) — epoll on Linux, kqueue on macOS/BSD, WSAPoll on Windows — driven through `Reactor::poll_events`, and it is also the executor's `Park`: when no task is runnable, the `Runtime` waits in the OS readiness queue until a socket is ready, the next timer is due, or another thread wakes a task (a wake pipe interrupts the wait on POSIX).

An echo server, one handler task per connection:

```c
struct EchoConn {
    int st; struct AsyncTcpStream c; struct ReadFut rd; struct WriteAllFut wr;
    long long n; int ok; unsigned char buf[512];
    struct Poll<int> poll(&Context cx);
    void drop();
};

struct Poll<int> EchoConn::poll(&Context cx) {
    ASYNC_BEGIN(self.st, 2);
    while (1) {
        self.rd = self.c.read(self.buf[0..512]);
        AWAIT_INTO(1, self.n, self.rd);
        if (self.n <= 0LL) break;                       // EOF or error
        self.wr = self.c.write_all(self.buf[0..(unsigned long)self.n]);
        AWAIT_INTO(2, self.ok, self.wr);
    }
    self.c.close();
    ASYNC_RETURN(0);
    ASYNC_END;
}

void EchoConn::drop() {
    if (self.st == 1) self.rd.drop();
    if (self.st == 2) self.wr.drop();
    self.c.close();
    self.st = ASYNC_DONE;
}

struct Server {
    int st; int n; struct AsyncTcpListener l; struct AcceptFut acc; struct AsyncTcpStream c;
    struct AsyncTask<struct EchoConn, int> conns[16];
    struct Poll<int> poll(&Context cx);
    void drop();
};

struct Poll<int> Server::poll(&Context cx) {
    ASYNC_BEGIN(self.st, 1);
    for (self.n = 0; self.n < 16; self.n = self.n + 1) {
        self.acc = self.l.accept();
        AWAIT_INTO(1, self.c, self.acc);
        {
            struct EchoConn e;
            e.st = ASYNC_INIT;
            e.c = self.c;
            self.conns[self.n] = AsyncTask::new(e);
            async_spawn(cx, &self.conns[self.n]);       // the handler lives in the server's struct
        }
    }
    ASYNC_RETURN(self.n);
    ASYNC_END;
}

void Server::drop() { if (self.st == 1) self.acc.drop(); self.l.close(); self.st = ASYNC_DONE; }

static struct Runtime rt;
static struct AsyncTask<struct Server, int> server;

int main() {
    rt = Runtime::new();
    if (rt.init() != 0) return 1;
    struct Server s;
    s.st = ASYNC_INIT;
    s.l = AsyncTcpListener::bind((unsigned short)7000);
    server = AsyncTask::new(s);
    rt.spawn(&server);
    rt.run();
    rt.close_();
    return 0;
}
```

Limits: `ASYNC_IO_WAITERS` (256) concurrent waits per driver (a wait that finds the table full re-polls instead); on Linux an fd is awaited for one direction at a time (epoll registers an fd once — a read and a write waiting on the same socket at the same time make the second re-poll); on Windows the driver parks at most `ASYNC_IO_REMOTE_MS` (20 ms) at a time, so wakes from other threads are seen within that; and WSAPoll on Windows before 10 version 2004 never reports a refused connect, so give connects a `timeout()` there.

## Example project {#example}

[`examples/async_demo`](https://github.com/safec-org/SafeC/tree/main/examples/async_demo) puts it together in one `Runtime`: an HTTP/1.0 server whose accept loop selects between the next connection and a `CancelToken` and spawns a handler task per connection into a fixed set of slots; eight clients that connect, send a request and read the reply under timeouts; a stats task on a timer; and a prime-counting job on a two-thread `ThreadPool` whose answer arrives through a `Oneshot` — a wake from another thread interrupting the `Runtime`'s wait in the reactor. `main` awaits all eight client `JoinHandle`s with `join_all`, then cancels the token, and the server and stats tasks wind down. CI builds and runs it on Linux, macOS and Windows.

```bash
cd examples/async_demo && safeguard run
```

## Targets {#targets}

`std/async/` is its own [release section](/stdlib/#including-the-standard-library) (`lib/<triple>/std/async.a`).

| Header | Targets | Needs |
|---|---|---|
| `future.h`, `coro.h`, `executor.h`, `timer.h`, `combinator.h`, `channel.h`, `sync.h` (`async.h`) | every target: hosted, bare metal, wasm32 | atomics (the compiler's built-ins), `std/dsp/timer_wheel` |
| `host.h` | hosted | `std/thread`, `std/time` |
| `pool.h` | hosted | `std/thread` |
| `net.h` | hosted: Linux, macOS/BSD, Windows | `std/sched` (reactor, io_nb); `ws2_32` on Windows (linked automatically) |

The bare-metal and wasm32 suite ([`tests/stdlib/baremetal`](/reference/baremetal)) runs producer/consumer coroutines over an `AsyncChannel` with an `AsyncMutex` and a `select2` of two `Sleep`s on a `VirtualClock`, on every bare-metal alias.

## Compared with TaskScheduler {#compared-with-taskscheduler}

[`std/sync/task.h`](/stdlib/sync#taskscheduler)'s `TaskScheduler` (and the [`std/sched`](/stdlib/sched) reactor driving it) is the older, lower-level model: a task is a function `int f(?&T arg, int resume_point)` that returns the point to resume at, and blocks on I/O by marking itself with `await_fd`. It has no values flowing between tasks, no wakers (a blocked task is found by scanning), and composes only by hand. `std/async` keeps the same "resume point in your own state" idea and the same OS backends, and adds futures that return values, wakers, combinators and executors that can be swapped (virtual time, bare metal, threads, I/O). Both remain; new code that waits on several things at once is usually simpler with futures.
