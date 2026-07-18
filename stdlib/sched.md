# sched -- Real-Time I/O Scheduler (Reactor)

`std/sched/` adds a single-threaded I/O-readiness event loop — a **reactor** — that drives [`std::TaskScheduler`](/stdlib/sync#taskscheduler-cooperative-task-scheduler) so many concurrent network, file, and signal operations can make progress on one real OS thread without ever blocking the whole program on any one of them. It's "async" in the same sense as epoll/kqueue-based runtimes elsewhere, built directly on SafeC's existing cooperative scheduler rather than introducing a second, competing concurrency primitive.

This is a **hosted-only** module operating on real OS file descriptors through real syscalls — deliberately separate from [`std/net/tcp.sc`](/stdlib/net), which is a from-scratch, bare-metal-oriented packet-level TCP/IP stack with no OS socket syscalls at all. The two serve different targets (hosted userspace vs. no-OS-underneath embedded), and neither is a drop-in replacement for the other.

```c
#include "sched/reactor.h"
#include "sched/io_nb.h"   // non-blocking socket/file helpers meant to pair with it
```

## Backends

`reactor.h` declares one portable `struct Reactor`/API; pick the `.sc` file matching your target and include it directly (safeguard's stdlib build tolerates the other two failing to compile/assemble for the current target and skips them — see [Standard Library Resolution](/stdlib/#including-the-standard-library)):

| File | Target | Mechanism | `SCHED_SIGNAL` |
|---|---|---|---|
| `reactor_kqueue.sc` | macOS, iOS, FreeBSD | kqueue/kevent, persistent registration | native (`EVFILT_SIGNAL`) |
| `reactor_epoll.sc` | Linux, Android | epoll, persistent registration | via `signalfd()` |
| `reactor_win32.sc` | Windows | `WSAPoll`, re-registers every poll (no persistent OS-side set) | **not supported** — `add()` silently no-ops; see the file's own header comment for why and what to use instead (`SetConsoleCtrlHandler`, wired to signal the scheduler some other way) |

All three implement the exact same surface, so calling code never needs to know which one is compiled in — `SCHED_READ`/`SCHED_WRITE`/`SCHED_SIGNAL` and `TaskScheduler::await_fd`/`unblock_fd` are already backend-agnostic. `reactor_run` itself (see below) is shared across all three (`reactor.sc`), not reimplemented per backend.

::: tip Verification status
`reactor_kqueue.sc` is exercised end-to-end (compiled, linked, and run) on macOS. `reactor_epoll.sc` and `reactor_win32.sc` are compiled and cross-target-verified (real object-code generation via `--target`, plus `static_assert`-checked struct layouts for the platform-ABI-sensitive parts — `EpollEvent`'s packed 12-byte layout, `WSAPollFd`'s 16-byte layout) but not yet runtime-tested on an actual Linux or Windows host in this environment.
:::

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
    int kq;   // underlying OS event queue fd (kqueue fd, epoll fd; unused on the Win32 backend)

    // Backend-private bookkeeping — left empty and unused by whichever
    // backend doesn't need it (see reactor.h for why each exists):
    struct Vec sigfds;    // reactor_epoll.sc's signum -> signalfd table
    struct Vec watched;   // reactor_win32.sc's registered (fd, filter) table

    int  init();                                             // opens/initializes the backend; 0 ok, -1 fail
    void add(int fd, int filter);                             // registers persistent, level-triggered interest
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

Thin wrappers around `open`/`socket`/`accept`/`connect` that enable non-blocking mode, meant to be paired with the reactor above: a task calls one of these, gets `EAGAIN`/`EWOULDBLOCK` immediately instead of blocking the whole program, and awaits readiness via `await_fd` instead. Like the reactor, `io_nb.h` declares one portable API with a backend per platform:

| File | Target | Non-blocking mechanism |
|---|---|---|
| `io_nb_bsd.sc` | macOS, iOS, FreeBSD | `fcntl` + `O_NONBLOCK` (`0x0004`) |
| `io_nb_linux.sc` | Linux, Android | `fcntl` + `O_NONBLOCK` (`0x800`) |
| `io_nb_win32.sc` | Windows | sockets: `ioctlsocket` + `FIONBIO`. Files: **best-effort** — see below |

```c
#define SCHED_AF_INET     2   // same value on every backend (a BSD-sockets
#define SCHED_SOCK_STREAM 1   // accident every later API, including
#define SCHED_O_RDONLY 0      // Winsock, deliberately kept)
#define SCHED_O_WRONLY 1
#define SCHED_O_RDWR   2

unsigned short sched_htons(unsigned short host16);
unsigned int   sched_htonl(unsigned int host32);
unsigned int   sched_ipv4(unsigned char a, unsigned char b, unsigned char c, unsigned char d);

int fd_set_nonblocking(int fd);                         // enables non-blocking mode on an open fd
int fd_open_nb(const char* path, int flags, int mode);   // open() in non-blocking mode where supported

int tcp_listen_nb(unsigned short port);                  // socket+bind+listen, non-blocking; listening fd or -1
int tcp_accept_nb(int listenfd);
    // non-blocking accept(); -1/EAGAIN if none pending yet — the caller
    // should await_fd(listenfd, SCHED_READ) and retry.
int tcp_connect_nb(unsigned int addr_network_order, unsigned short port);
    // non-blocking connect(); returns immediately with EINPROGRESS — the
    // caller should await_fd(fd, SCHED_WRITE) and treat writability as
    // "connect finished" (check SO_ERROR to distinguish success/failure).
```

Each backend also declares its own `struct SockAddrIn` matching that platform's real `sockaddr_in` layout — BSD/macOS has a leading 1-byte `sin_len` field Linux and Windows don't (both of those use a plain 2-byte `sin_family` instead), so the three aren't interchangeable; use whichever one your included backend file declares.

::: warning
`io_nb_win32.sc`'s `fd_open_nb` is best-effort: Windows has no non-blocking-file-open equivalent to `O_NONBLOCK` (true async file I/O there means `CreateFile` with `FILE_FLAG_OVERLAPPED` plus a completion-based model entirely different from this reactor's readiness-based one — out of scope here), so a file opened through it on that backend is an ordinary blocking file descriptor. Don't `await_fd()` a Windows file handle from `fd_open_nb` expecting non-blocking semantics the way a socket from `tcp_listen_nb`/`tcp_connect_nb` genuinely has; sockets are fully non-blocking on all three backends.
:::

::: tip Verification status
Same status as the reactor backends: `io_nb_bsd.sc` is exercised end-to-end on macOS (including a real `tcp_listen_nb`/bind/listen call). `io_nb_linux.sc` and `io_nb_win32.sc` are compiled and cross-target-verified (real object-code generation via `--target`, `static_assert`-checked `SockAddrIn` layout) but not yet runtime-tested on an actual Linux or Windows host.
:::

## Scheduling Model

The reactor's own scheduling is round-robin/FIFO at the task level (`Task[]` array order), the same as plain `TaskScheduler::run_all()` — it adds zero-CPU blocking waits when idle, not priority or deadline ordering. Genuine priority scheduling exists separately in [`ThreadSched`](/stdlib/sync#threadsched-priority-based-freestanding-threads), which wraps the same underlying `TaskScheduler` but is not currently wired into the reactor — the two are not combined in one type today.
