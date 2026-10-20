# 프로세스 간 통신 (IPC)

`std/ipc/`는 동일 프로세스 내의 다른 스레드가 아니라 *다른 프로세스*와
통신하는 것을 다룬다 — 모두 프로세스 내부용인 `Channel`/`MpscQueue`/
`LFQueue`는 [동기화](/ko/stdlib/sync)를 참고한다.

```c
#include <std/ipc/pipe.h>
#include <std/ipc/uds.h>
// or via:
#include <std/prelude.h>
```

---

## 파이프 — 익명 파이프 {#pipe-anonymous-pipes}

가장 단순한 형태의 호스트 IPC: 읽기 끝과 쓰기 끝을 가진 단방향 바이트
스트림으로, 가장 흔히는 부모/자식 프로세스 간 통신에 쓰인다 —
spawn된 자식 프로세스가 파이프의 fd/핸들을 상속받거나, 자식의
stdin/stdout에 연결된다(그 연결 작업 자체는 이 모듈의 범위 밖이며,
이 모듈은 파이프를 생성하고 조작하는 것만 담당한다).

핸들은 플랫폼 전반에 걸쳐 균일하게 `long long`이다: POSIX 파이프 fd는
그대로 들어맞고, Windows 파이프 `HANDLE`(포인터 크기의 값)은
`std/thread.h`의 OS 스레드 핸들이 이미 사용하는 것과 동일한
포인터-정수 변환 관례를 통해 저장된다. 두 개의 백엔드가 있으며 선택
방식은 `std/sched/reactor.h`의 것과 동일하다: `pipe_posix.sc`(macOS,
iOS, Linux, Android, FreeBSD — 이들 모두에서 `pipe()`는 동일함)와
`pipe_win32.sc`(`CreatePipe`/`ReadFile`/`WriteFile`/`CloseHandle`).

### 구조체 {#struct}

```c
struct Pipe {
    long long read_fd;
    long long write_fd;
};

int  pipe_create(&Pipe out);          // 0 on success, -1 on failure

// Returns bytes read (0 = write end closed and pipe drained: EOF), -1 on error.
long long pipe_read(&Pipe p, &unsigned char buf, unsigned long count);

// Returns bytes written, or -1 on error.
long long pipe_write(&Pipe p, const &unsigned char buf, unsigned long count);

// Non-blocking mode (pairs with std::Reactor — await_fd(fd, SCHED_READ/WRITE)).
int  pipe_set_read_nonblocking(&Pipe p);
int  pipe_set_write_nonblocking(&Pipe p);

// Closing the write end wakes a reader blocked in pipe_read (or awaiting
// SCHED_READ) with EOF once the pipe drains. Safe to call each end's
// close exactly once — closing twice is undefined, the same as any
// double-close(2).
int  pipe_close_read(&Pipe p);
int  pipe_close_write(&Pipe p);
```

### 예제 {#example}

```c
#include <std/ipc/pipe.h>
#include <std/io.h>

int main() {
    struct Pipe p;
    if (std::pipe_create(&p) != 0) { std::println("pipe_create failed"); return 1; }

    unsigned char msg[5] = { 'h', 'e', 'l', 'l', 'o' };
    std::pipe_write(&p, &msg[0], 5UL);

    unsigned char buf[5];
    long long n = std::pipe_read(&p, &buf[0], 5UL);
    std::print("read "); std::print_int(n); std::println(" bytes");   // read 5 bytes

    std::pipe_close_read(&p);
    std::pipe_close_write(&p);
    return (n == 5LL && buf[4] == 'o') ? 0 : 1;
}
```

::: tip
이 예제는 자체 완결적으로 만들기 위해 단일 프로세스/스레드 내에서
쓰고 읽는다 — 실제 사용 사례는 `fork()`를 거쳐 공유되는 파이프로,
부모와 자식이 각자 사용하지 않는 끝을 닫고 나머지 한쪽을 통해
통신한다.
:::

---

## UDS — 유닉스 도메인 소켓 {#uds-unix-domain-sockets}

*관련 없는* 프로세스 간의 이름 있는, 주소 지정 가능한 IPC다(이미 fd를
가지고 있는 것 — 보통은 `fork()`된 자식 — 사이에서만 동작하는 `Pipe`의
익명 파이프와 다르다): 파일 시스템 경로를 아는 프로세스라면 어느
쪽이든 연결할 수 있으며, 이는 TCP 포트를 아는 프로세스가 네트워크를
통해 연결할 수 있는 것과 마찬가지다. `std/sched/io_nb.h`의 `tcp_*_nb`
함수와 의도적으로 동일한, 논블로킹이며 `std::Reactor`와 짝지을 수 있는
형태다 — 태스크가 이 함수들 중 하나를 호출하면 프로그램 전체를
블로킹하는 대신 곧바로 `EAGAIN`/`EWOULDBLOCK`을 받고,
`TaskScheduler::await_fd(fd, SCHED_READ/SCHED_WRITE)`를 통해 준비
상태를 기다린다.

::: info Windows 백엔드 없음
Windows에는 이 프로젝트가 지원하는 버전 전반에 걸쳐 보편적으로
적용되는, 전통적인 BSD 소켓 의미의 `AF_UNIX` 도메인 소켓에 대응하는
것이 없다. 그래서 `uds_win32.sc`는 존재하지 않는다 — 네임드 파이프
(`CreateNamedPipe`/`ConnectNamedPipe`, 완전히 다른 형태의 API)가
Windows의 네이티브 대응물이며, 이 함수 시그니처들에 끼워 맞추기보다는
별도의 모듈이 필요할 것이다.
:::

두 개의 백엔드: `uds_bsd.sc`(macOS, iOS, FreeBSD — `struct sockaddr_un`에
1바이트 `sun_len` 필드가 앞에 있음)와 `uds_linux.sc`(Linux, Android —
`sun_len` 없이 2바이트 `sun_family`만 있음).

### API {#api}

```c
// 'path'에 대해 socket()+bind()+listen()을 수행, 논블로킹. 리스닝 fd를
// 반환하거나, 실패 시 -1 (정리되지 않은 이전 실행에서 남은 소켓 파일로
// 'path'가 이미 존재하면 EADDRINUSE — 그럴 가능성이 있으면 먼저
// uds_unlink를 호출).
int uds_listen_nb(const &char path);

// 논블로킹 accept(): 연결된(그 자체로 논블로킹인) 클라이언트 fd, 또는
// 아직 대기 중인 것이 없으면 -1/EAGAIN — await_fd(listenfd, SCHED_READ) 후
// 재시도.
int uds_accept_nb(int listenfd);

// 'path'에 대해 socket()+connect()를 수행, 논블로킹: 핸드셰이크를
// 기다리지 않고 즉시 EINPROGRESS와 함께 반환 — await_fd(fd,
// SCHED_WRITE)를 사용하고, fd가 쓰기 가능해지는 것을 "연결 완료"로
// 취급한다.
int uds_connect_nb(const &char path);

// 'path'에 있는 오래된 소켓 파일을 제거한다 — 정리되지 않고 종료된
// 이전 실행의 파일 시스템 경로가 여전히 존재하면, TCP 포트는 OS가
// 단순히 반환하는 것과 달리 bind()가 EADDRINUSE로 실패한다. 성공 시
// 0("경로가 존재하지 않았음"도 포함), 실제 실패 시 -1.
int uds_unlink(const &char path);
```

### 예제 {#example-1}

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
