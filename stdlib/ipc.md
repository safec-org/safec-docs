# Inter-Process Communication

`std/ipc/` covers communicating with a *different process* rather than another thread within the same process — see [Synchronization](/stdlib/sync) for `Channel`/`MpscQueue`/`LFQueue`, which are all intra-process.

```c
#include "ipc/pipe.h"
#include "ipc/uds.h"
// or via:
#include "prelude.h"
```

---

## Pipe — Anonymous Pipes

The simplest form of hosted IPC: a one-way byte stream with a read end and a write end, most commonly used for parent/child communication — a spawned child process inherits the pipe's fds/handles, or they're wired to its stdin/stdout (that wiring itself is out of scope for this module, which only creates and operates the pipe).

Handles are `long long` uniformly across platforms: a POSIX pipe fd fits directly; a Windows pipe `HANDLE` (a pointer-sized value) is stored via the same pointer-to-integer convention `std/thread.h`'s OS-thread handles already use. Two backends, picked the same way `std/sched/reactor.h`'s are: `pipe_posix.sc` (macOS, iOS, Linux, Android, FreeBSD — `pipe()` is identical across all of these) and `pipe_win32.sc` (`CreatePipe`/`ReadFile`/`WriteFile`/`CloseHandle`).

### Struct

```c
struct Pipe {
    long long read_fd;
    long long write_fd;
};

int  pipe_create(struct Pipe* out);   // 0 on success, -1 on failure

// Returns bytes read (0 = write end closed and pipe drained: EOF), -1 on error.
long long pipe_read(struct Pipe* p, void* buf, unsigned long count);

// Returns bytes written, or -1 on error.
long long pipe_write(struct Pipe* p, const void* buf, unsigned long count);

// Non-blocking mode (pairs with std::Reactor — await_fd(fd, SCHED_READ/WRITE)).
int  pipe_set_read_nonblocking(struct Pipe* p);
int  pipe_set_write_nonblocking(struct Pipe* p);

// Closing the write end wakes a reader blocked in pipe_read (or awaiting
// SCHED_READ) with EOF once the pipe drains. Safe to call each end's
// close exactly once — closing twice is undefined, the same as any
// double-close(2).
int  pipe_close_read(struct Pipe* p);
int  pipe_close_write(struct Pipe* p);
```

### Example

```c
#include "ipc/pipe.h"
#include "io.h"

int main() {
    struct Pipe p;
    if (pipe_create(&p) != 0) { println("pipe_create failed"); return 1; }

    const char* msg = "hello";
    unsafe { pipe_write(&p, (const void*)msg, 5UL); }

    char buf[6];
    unsafe { buf[5] = (char)0; }
    long long n;
    unsafe { n = pipe_read(&p, (void*)&buf[0], 5UL); }
    print("read "); print_int(n); print(" bytes: ");
    unsafe { println(&buf[0]); }   // hello

    pipe_close_read(&p);
    pipe_close_write(&p);
    return 0;
}
```

::: tip
This example writes and reads within a single process/thread to keep it self-contained — the real use case is a pipe shared across a `fork()`, with the parent and child each closing the end they don't use and communicating through the other.
:::

---

## UDS — Unix Domain Sockets

Named, addressable IPC between *unrelated* processes (unlike `Pipe`'s anonymous pipes, which only work between a process and something that already has the fds — typically a `fork()`'d child): any process that knows the filesystem path can connect, the same way any process that knows a TCP port can connect over the network. Deliberately the same non-blocking, `std::Reactor`-pairable shape as `std/sched/io_nb.h`'s `tcp_*_nb` functions — a task calls one of these, gets `EAGAIN`/`EWOULDBLOCK` immediately instead of blocking the whole program, and awaits readiness via `TaskScheduler::await_fd(fd, SCHED_READ/SCHED_WRITE)` instead.

::: info No Windows backend
Windows has no `AF_UNIX`-domain-socket equivalent in the traditional BSD-sockets sense universally across the versions this project supports, so there's no `uds_win32.sc` — named pipes (`CreateNamedPipe`/`ConnectNamedPipe`, a differently-shaped API entirely) are Windows' native equivalent, and would need their own module rather than slotting into these function signatures.
:::

Two backends: `uds_bsd.sc` (macOS, iOS, FreeBSD — `struct sockaddr_un` has a leading 1-byte `sun_len` field) and `uds_linux.sc` (Linux, Android — no `sun_len`, plain 2-byte `sun_family`).

### API

```c
// socket()+bind()+listen() on 'path', non-blocking. Returns the listening
// fd, or -1 on failure (EADDRINUSE if 'path' already exists as a socket
// file from a previous run that didn't clean up — call uds_unlink first
// if that's a possibility).
int uds_listen_nb(const char* path);

// Non-blocking accept(): a connected client fd (itself non-blocking), or
// -1/EAGAIN if none pending yet — await_fd(listenfd, SCHED_READ) and retry.
int uds_accept_nb(int listenfd);

// socket()+connect() to 'path', non-blocking: returns immediately with
// EINPROGRESS rather than waiting for the handshake — await_fd(fd,
// SCHED_WRITE) and treat the fd becoming writable as "connect finished".
int uds_connect_nb(const char* path);

// Removes a stale socket file at 'path' — bind() fails EADDRINUSE if the
// filesystem path from a previous, uncleanly-terminated run still
// exists, unlike a TCP port which the OS simply releases. 0 on success
// (including "path didn't exist"), -1 on a real failure.
int uds_unlink(const char* path);
```

### Example

```c
#include "ipc/uds.h"
#include "io.h"

extern int write(int fd, const void* buf, unsigned long count);
extern long long read(int fd, void* buf, unsigned long count);
extern int close(int fd);

int main() {
    const char* path = "/tmp/example.sock";
    uds_unlink(path);

    int listenFd = uds_listen_nb(path);
    int clientFd = uds_connect_nb(path);

    // Non-blocking accept: retry until the pending connection shows up.
    // A real program would await_fd(listenFd, SCHED_READ) via
    // TaskScheduler/Reactor instead of busy-polling like this.
    int serverFd = -1;
    while (serverFd < 0) {
        serverFd = uds_accept_nb(listenFd);
    }

    const char* msg = "hi from client";
    unsafe { write(clientFd, (const void*)msg, 14UL); }

    char buf[15];
    unsafe { buf[14] = (char)0; }
    unsafe { read(serverFd, (void*)&buf[0], 14UL); }
    unsafe { println(&buf[0]); }   // hi from client

    unsafe { close(clientFd); close(serverFd); close(listenFd); }
    uds_unlink(path);
    return 0;
}
```
