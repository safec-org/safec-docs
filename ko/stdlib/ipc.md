# 프로세스 간 통신 (IPC)

`std/ipc/`는 동일 프로세스 내의 다른 스레드가 아니라 *다른 프로세스*와
통신하는 것을 다룬다 — 모두 프로세스 내부용인 `Channel`/`MpscQueue`/
`LFQueue`는 [동기화](/ko/stdlib/sync)를 참고한다.

```c
#include "ipc/pipe.h"
#include "ipc/uds.h"
// 또는 다음을 통해:
#include "prelude.h"
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

int  pipe_create(struct Pipe* out);   // 성공 시 0, 실패 시 -1

// 읽은 바이트 수를 반환 (0 = 쓰기 끝이 닫히고 파이프가 비워짐: EOF), 오류 시 -1.
long long pipe_read(struct Pipe* p, void* buf, unsigned long count);

// 쓴 바이트 수를 반환, 오류 시 -1.
long long pipe_write(struct Pipe* p, const void* buf, unsigned long count);

// 논블로킹 모드 (std::Reactor와 짝을 이룸 — await_fd(fd, SCHED_READ/WRITE)).
int  pipe_set_read_nonblocking(struct Pipe* p);
int  pipe_set_write_nonblocking(struct Pipe* p);

// 쓰기 끝을 닫으면, pipe_read에서 블록 중인(또는 SCHED_READ를 기다리는)
// 리더가 파이프가 비워진 뒤 EOF와 함께 깨어난다. 각 끝을 정확히 한 번씩
// close하는 것은 안전하다 — 두 번 close하는 것은, 다른 모든 이중 close(2)와
// 마찬가지로 정의되지 않은 동작이다.
int  pipe_close_read(struct Pipe* p);
int  pipe_close_write(struct Pipe* p);
```

### 예제 {#example}

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
int uds_listen_nb(const char* path);

// 논블로킹 accept(): 연결된(그 자체로 논블로킹인) 클라이언트 fd, 또는
// 아직 대기 중인 것이 없으면 -1/EAGAIN — await_fd(listenfd, SCHED_READ) 후
// 재시도.
int uds_accept_nb(int listenfd);

// 'path'에 대해 socket()+connect()를 수행, 논블로킹: 핸드셰이크를
// 기다리지 않고 즉시 EINPROGRESS와 함께 반환 — await_fd(fd,
// SCHED_WRITE)를 사용하고, fd가 쓰기 가능해지는 것을 "연결 완료"로
// 취급한다.
int uds_connect_nb(const char* path);

// 'path'에 있는 오래된 소켓 파일을 제거한다 — 정리되지 않고 종료된
// 이전 실행의 파일 시스템 경로가 여전히 존재하면, TCP 포트는 OS가
// 단순히 반환하는 것과 달리 bind()가 EADDRINUSE로 실패한다. 성공 시
// 0("경로가 존재하지 않았음"도 포함), 실제 실패 시 -1.
int uds_unlink(const char* path);
```

### 예제 {#example-1}

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

    // 논블로킹 accept: 대기 중인 연결이 나타날 때까지 재시도.
    // 실제 프로그램이라면 이렇게 바쁜 폴링을 하는 대신
    // TaskScheduler/Reactor를 통해 await_fd(listenFd, SCHED_READ)를
    // 사용해야 한다.
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
