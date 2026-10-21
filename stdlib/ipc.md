# Inter-Process Communication

`std/ipc/` covers communicating with a *different process* rather than another thread within the same process — see [Synchronization](/stdlib/sync) for `Channel`/`MpscQueue`/`LFQueue`, which are all intra-process.

```c
#include <std/ipc/pipe.h>
#include <std/ipc/uds.h>
// or via:
#include <std/prelude.h>
```

---

## Pipe — Anonymous Pipes {#pipe-anonymous-pipes}

The simplest form of hosted IPC: a one-way byte stream with a read end and a write end, most commonly used for parent/child communication — a spawned child process inherits the pipe's fds/handles, or they're wired to its stdin/stdout (that wiring itself is out of scope for this module, which only creates and operates the pipe).

Handles are `long long` uniformly across platforms: a POSIX pipe fd fits directly; a Windows pipe `HANDLE` (a pointer-sized value) is stored via the same pointer-to-integer convention `std/thread.h`'s OS-thread handles already use. Two backends, picked the same way `std/sched/reactor.h`'s are: `pipe_posix.sc` (macOS, iOS, Linux, Android, FreeBSD — `pipe()` is identical across all of these) and `pipe_win32.sc` (`CreatePipe`/`ReadFile`/`WriteFile`/`CloseHandle`).

### Struct {#struct}

```c
struct Pipe {
    long long read_fd;
    long long write_fd;

    int  init();                     // creates the pipe in place; 0 on success, -1 on failure

    // Returns bytes read (0 = write end closed and pipe drained: EOF), -1 on error.
    long long read(&unsigned char buf, unsigned long count);

    // Returns bytes written, or -1 on error.
    long long write(const &unsigned char buf, unsigned long count);

    // Non-blocking mode (pairs with std::Reactor — await_fd(fd, SCHED_READ/WRITE)).
    int  set_read_nonblocking();
    int  set_write_nonblocking();

    // Closing the write end wakes a reader blocked in read() (or awaiting
    // SCHED_READ) with EOF once the pipe drains. Safe to call each end's
    // close exactly once — closing twice is undefined, the same as any
    // double-close(2).
    int  close_read();
    int  close_write();
};
```

`Pipe` is a [`Stream`](/stdlib/io#io-traits) (`Reader + Writer`: `read` takes from the read end, `write` puts into the write end), so `read_exact`, `write_all`, `copy_stream` and [`BufReader` / `BufWriter`](/stdlib/io#buffered-io) work on it.

### Example {#example}

```c
#include <std/ipc/pipe.h>
#include <std/io.h>

int main() {
    struct Pipe p;
    if (p.init() != 0) { std::println("pipe init failed"); return 1; }

    unsigned char msg[5] = { 'h', 'e', 'l', 'l', 'o' };
    p.write(&msg[0], 5UL);

    unsigned char buf[5];
    long long n = p.read(&buf[0], 5UL);
    std::print("read "); std::print_int(n); std::println(" bytes");   // read 5 bytes

    p.close_read();
    p.close_write();
    return (n == 5LL && buf[4] == 'o') ? 0 : 1;
}
```

::: tip
This example writes and reads within a single process/thread to keep it self-contained — the real use case is a pipe shared across a `fork()`, with the parent and child each closing the end they don't use and communicating through the other.
:::

---

## UDS — Unix Domain Sockets {#uds-unix-domain-sockets}

Named, addressable IPC between *unrelated* processes (unlike `Pipe`'s anonymous pipes, which only work between a process and something that already has the fds — typically a `fork()`'d child): any process that knows the filesystem path can connect, the same way any process that knows a TCP port can connect over the network. Deliberately the same non-blocking, `std::Reactor`-pairable shape as `std/sched/io_nb.h`'s `tcp_*_nb` functions — a task calls one of these, gets `EAGAIN`/`EWOULDBLOCK` immediately instead of blocking the whole program, and awaits readiness via `TaskScheduler::await_fd(fd, SCHED_READ/SCHED_WRITE)` instead.

::: info No Windows backend
Windows has no `AF_UNIX`-domain-socket equivalent in the traditional BSD-sockets sense universally across the versions this project supports, so there's no `uds_win32.sc` — named pipes (`CreateNamedPipe`/`ConnectNamedPipe`, a differently-shaped API entirely) are Windows' native equivalent, and would need their own module rather than slotting into these function signatures.
:::

Two backends: `uds_bsd.sc` (macOS, iOS, FreeBSD — `struct sockaddr_un` has a leading 1-byte `sun_len` field) and `uds_linux.sc` (Linux, Android — no `sun_len`, plain 2-byte `sun_family`).

### API {#api}

```c
// socket()+bind()+listen() on 'path', non-blocking. Returns the listening
// fd, or -1 on failure (EADDRINUSE if 'path' already exists as a socket
// file from a previous run that didn't clean up — call uds_unlink first
// if that's a possibility).
int uds_listen_nb(const &char path);

// Non-blocking accept(): a connected client fd (itself non-blocking), or
// -1/EAGAIN if none pending yet — await_fd(listenfd, SCHED_READ) and retry.
int uds_accept_nb(int listenfd);

// socket()+connect() to 'path', non-blocking: returns immediately with
// EINPROGRESS rather than waiting for the handshake — await_fd(fd,
// SCHED_WRITE) and treat the fd becoming writable as "connect finished".
int uds_connect_nb(const &char path);

// Removes a stale socket file at 'path' — bind() fails EADDRINUSE if the
// filesystem path from a previous, uncleanly-terminated run still
// exists, unlike a TCP port which the OS simply releases. 0 on success
// (including "path didn't exist"), -1 on a real failure.
int uds_unlink(const &char path);
```

### Example {#example-1}

```c
#include <std/ipc/uds.h>
#include <std/io.h>

extern int write(int fd, const void* buf, unsigned long count);
extern long long read(int fd, void* buf, unsigned long count);
extern int close(int fd);

// Typed wrappers over C's untyped buffers: references in, raw pointers out.
long long send_text(int fd, const &char text, unsigned long n) { return (long long)write(fd, text, n); }
long long recv_text(int fd, &char out, unsigned long n)         { return read(fd, out, n); }

int main() {
    const &char path = "/tmp/example.sock";
    std::uds_unlink(path);

    int listenFd = std::uds_listen_nb(path);
    int clientFd = std::uds_connect_nb(path);

    // Non-blocking accept: retry until the pending connection shows up.
    // A real program would await_fd(listenFd, SCHED_READ) via
    // TaskScheduler/Reactor instead of busy-polling like this.
    int serverFd = -1;
    while (serverFd < 0) {
        serverFd = std::uds_accept_nb(listenFd);
    }

    send_text(clientFd, "hi from client", 14UL);

    char buf[15];
    buf[14] = (char)0;
    recv_text(serverFd, &buf[0], 14UL);
    std::println(&buf[0]);   // hi from client

    close(clientFd); close(serverFd); close(listenFd);
    std::uds_unlink(path);
    return 0;
}
```
