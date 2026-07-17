# sched -- Real-Time I/O Scheduler (Reactor)

`std/sched/` adds a single-threaded I/O-readiness event loop — a **reactor** — that drives [`std::TaskScheduler`](/stdlib/sync#taskscheduler-cooperative-task-scheduler) so many concurrent network, file, and signal operations can make progress on one real OS thread without ever blocking the whole program on any one of them. It's "async" in the same sense as epoll/kqueue-based runtimes elsewhere, built directly on SafeC's existing cooperative scheduler rather than introducing a second, competing concurrency primitive.

This is a **hosted-only** module (currently macOS/BSD, via kqueue) operating on real OS file descriptors through real syscalls. It's deliberately separate from [`std/net/tcp.sc`](/stdlib/net), which is a from-scratch, bare-metal-oriented packet-level TCP/IP stack with no OS socket syscalls at all — the two serve different targets (hosted userspace vs. no-OS-underneath embedded), and neither is a drop-in replacement for the other.

```c
#include "sched/reactor.h"
#include "sched/io_nb.h"   // non-blocking socket/file helpers meant to pair with it
```

## Usage Shape

1. Spawn tasks on a `std::TaskScheduler` as usual (see [Synchronization](/stdlib/sync)) — each task follows the same `int(void* arg, int resume_point)` yield protocol.
2. A task that would otherwise block on I/O calls `sched->await_fd(fd, SCHED_READ)` (or `SCHED_WRITE`/`SCHED_SIGNAL`) on itself, from within its own turn, instead of actually blocking.
3. Drive the whole thing with `std::reactor_run(sched, reactor)` instead of `TaskScheduler::run_all()`.

## Reactor

```c
#define SCHED_READ    1
#define SCHED_WRITE   2
#define SCHED_SIGNAL  3   // for this filter, 'fd' is actually a signal number

struct Reactor {
    int kq;   // underlying OS event queue fd

    int  init();                                             // opens the OS event queue; 0 ok, -1 fail
    void add(int fd, int filter);                             // registers interest (re-registers automatically each poll)
    void remove(int fd, int filter);
    int  poll(struct TaskScheduler* sched, long long timeout_ms);
        // timeout_ms: 0 = return immediately if nothing pending, negative = block indefinitely.
        // Calls sched->unblock_fd() for every ready (fd, filter). Returns the
        // number of OS events processed (may differ from tasks unblocked, if
        // several tasks await the same fd).
    void close_();
}

struct Reactor reactor_init();
```

## `reactor_run` — the scheduler itself

```c
void reactor_run(struct TaskScheduler* sched, struct Reactor* r);
```

Drives `sched` to completion: ticks every runnable task, and whenever none are immediately runnable, polls the reactor — with a zero timeout if some tasks are merely mid-round (so newly-ready I/O is picked up without stalling), or an indefinite wait if literally everything remaining is blocked on I/O, so the process consumes zero CPU until the OS actually has something to report. Use this in place of `TaskScheduler::run_all()` for any workload where tasks call `await_fd()`.

## Example

```c
#include "sched/reactor.h"
#include "sched/io_nb.h"
#include "io.h"

int echo_conn(void* arg, int resume_point) {
    int fd = (int)(long long)arg;
    if (resume_point == 0) {
        // wait until the socket has data to read
        // (the scheduler is reached via a global/static in a real program)
        g_sched.await_fd(fd, SCHED_READ);
        return 1;
    }
    // ... read/write with fd here ...
    return 0;   // done
}

int main() {
    struct TaskScheduler sched = task_sched_init();
    struct Reactor reactor = reactor_init();
    reactor.init();

    int listenfd = tcp_listen_nb(8080);
    reactor.add(listenfd, SCHED_READ);

    // ... accept loop spawns echo_conn per connection, each calling
    //     await_fd on its own connection fd ...

    reactor_run(&sched, &reactor);
    reactor.close_();
    return 0;
}
```

## Non-Blocking I/O Helpers (`io_nb.h`)

Thin wrappers around `open`/`socket`/`accept`/`connect` that set `O_NONBLOCK`, meant to be paired with the reactor above: a task calls one of these, gets `EAGAIN`/`EWOULDBLOCK` immediately instead of blocking the whole program, and awaits readiness via `await_fd` instead.

```c
#define SCHED_AF_INET     2
#define SCHED_SOCK_STREAM 1
#define SCHED_O_RDONLY 0
#define SCHED_O_WRONLY 1
#define SCHED_O_RDWR   2

struct SockAddrIn {
    unsigned char  sin_len;     // BSD/macOS layout — see the caveat below
    unsigned char  sin_family;
    unsigned short sin_port;    // network byte order
    unsigned int   sin_addr;    // network byte order
    unsigned long  sin_zero;
};

unsigned short sched_htons(unsigned short host16);
unsigned int   sched_htonl(unsigned int host32);
unsigned int   sched_ipv4(unsigned char a, unsigned char b, unsigned char c, unsigned char d);

int fd_set_nonblocking(int fd);                         // sets O_NONBLOCK on an open fd
int fd_open_nb(const char* path, int flags, int mode);   // open() + O_NONBLOCK

int tcp_listen_nb(unsigned short port);                  // socket+bind+listen, non-blocking; listening fd or -1
int tcp_accept_nb(int listenfd);
    // non-blocking accept(); -1/EAGAIN if none pending yet — the caller
    // should await_fd(listenfd, SCHED_READ) and retry.
int tcp_connect_nb(unsigned int addr_network_order, unsigned short port);
    // non-blocking connect(); returns immediately with EINPROGRESS — the
    // caller should await_fd(fd, SCHED_WRITE) and treat writability as
    // "connect finished" (check SO_ERROR to distinguish success/failure).
```

::: warning
The constant values in `io_nb.h` (`O_NONBLOCK`, `sockaddr_in`'s layout including the extra `sin_len` byte) are macOS/BSD-specific, matching the kqueue reactor backend. A Linux build needs the Linux-equivalent values (`O_NONBLOCK` is `0x800` there, not `0x0004`; Linux's `sockaddr_in` has no `sin_len` field) alongside a future epoll reactor backend — only the kqueue backend (`reactor_kqueue.sc`) exists today.
:::

## Scheduling Model

The reactor's own scheduling is round-robin/FIFO at the task level (`Task[]` array order), the same as plain `TaskScheduler::run_all()` — it adds zero-CPU blocking waits when idle, not priority or deadline ordering. Genuine priority scheduling exists separately in [`ThreadSched`](/stdlib/sync#threadsched-priority-based-freestanding-threads), which wraps the same underlying `TaskScheduler` but is not currently wired into the reactor — the two are not combined in one type today.
