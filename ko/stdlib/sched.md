# sched -- 실시간 I/O 스케줄러 (리액터)

`std/sched/`는 [`std::TaskScheduler`](/ko/stdlib/sync#taskscheduler-cooperative-task-scheduler)를
구동하는 단일 스레드 I/O 준비 상태 이벤트 루프 — 즉 **리액터**(reactor) —
를 추가하여, 여러 개의 동시적인 네트워크·파일·시그널 작업이 단 하나의
실제 OS 스레드 위에서, 그중 어느 하나 때문에 프로그램 전체가 블록되는
일 없이 진행될 수 있게 한다. 이는 epoll/kqueue 기반의 다른 런타임들과
같은 의미에서의 "비동기"이며, 경쟁하는 별도의 동시성 기본 요소를
새로 도입하는 대신 SafeC에 이미 있는 협력형 스케줄러 위에 직접
구축되었다.

이 모듈은 실제 syscall을 통해 실제 OS 파일 디스크립터에서 동작하는
**호스트 전용** 모듈이다 — OS 소켓 syscall을 전혀 사용하지 않는,
처음부터 작성된 베어메탈 지향 패킷 수준 TCP/IP 스택인
[`std/net/tcp.sc`](/ko/stdlib/net)와는 의도적으로 분리되어 있다. 이
둘은 서로 다른 대상(호스트 유저스페이스 대 OS가 없는 임베디드)을
다루며, 어느 쪽도 다른 쪽의 대체물이 아니다.

```c
#include "sched/reactor.h"
#include "sched/io_nb.h"   // 리액터와 짝을 이루도록 만들어진 논블로킹 소켓/파일 헬퍼
```

## 백엔드 {#backends}

`reactor.h`는 하나의 이식 가능한 `struct Reactor`/API를 선언한다.
대상에 맞는 `.sc` 파일을 골라 직접 include한다(safeguard의 표준
라이브러리 빌드는 현재 대상에서 컴파일/어셈블이 되지 않는 나머지 두
파일을 허용하고 건너뛴다 — [표준 라이브러리 결정 방식](/ko/stdlib/#including-the-standard-library)
참고):

| 파일 | 대상 | 메커니즘 | `SCHED_SIGNAL` |
|---|---|---|---|
| `reactor_kqueue.sc` | macOS, iOS, FreeBSD | kqueue/kevent, 영구 등록 | 네이티브 (`EVFILT_SIGNAL`) |
| `reactor_epoll.sc` | Linux, Android | epoll, 영구 등록 | `signalfd()`를 통해 |
| `reactor_win32.sc` | Windows | `WSAPoll`, 폴링마다 재등록(영구적인 OS 측 집합 없음) | **지원되지 않음** — `add()`가 조용히 아무 동작도 하지 않음; 이유와 대안(`SetConsoleCtrlHandler`를 다른 방식으로 스케줄러에 신호를 보내도록 연결)은 해당 파일 자체의 헤더 주석 참고 |

세 백엔드 모두 정확히 동일한 표면을 구현하므로, 호출하는 코드는 어느
것이 컴파일되어 들어갔는지 알 필요가 없다 — `SCHED_READ`/
`SCHED_WRITE`/`SCHED_SIGNAL`과 `TaskScheduler::await_fd`/`unblock_fd`는
이미 백엔드에 구애받지 않는다. `reactor_run` 자체(아래 참고)는 백엔드마다
다시 구현되는 것이 아니라 세 백엔드 모두에서 공유된다(`reactor.sc`).

::: tip 검증 상태
`reactor_kqueue.sc`는 macOS에서 종단 간(컴파일, 링크, 실행)으로
검증되었다. `reactor_epoll.sc`와 `reactor_win32.sc`는 컴파일되고
크로스 타겟 검증(`--target`을 통한 실제 오브젝트 코드 생성, 그리고
플랫폼 ABI에 민감한 부분에 대한 `static_assert` 검사 — `EpollEvent`의
패킹된 12바이트 레이아웃, `WSAPollFd`의 16바이트 레이아웃)까지는
되었지만, 이 환경에서 실제 Linux나 Windows 호스트 위에서 런타임
테스트된 적은 아직 없다.
:::

## 사용 형태 {#usage-shape}

1. 평소처럼 `std::TaskScheduler`에 태스크를 spawn한다([동기화](/ko/stdlib/sync)
   참고) — 각 태스크는 동일한 `int(void* arg, int resume_point)` yield
   프로토콜을 따른다.
2. 원래라면 I/O에서 블록되었을 태스크는, 자신의 차례 안에서 실제로
   블록되는 대신 자기 자신에 대해 `sched->await_fd(fd, SCHED_READ)`
   (또는 `SCHED_WRITE`/`SCHED_SIGNAL`)를 호출한다.
3. `TaskScheduler::run_all()` 대신 `std::reactor_run(sched, reactor)`로
   전체를 구동한다.

## Reactor {#reactor}

```c
#define SCHED_READ    1
#define SCHED_WRITE   2
#define SCHED_SIGNAL  3   // 이 필터에서는 'fd'가 실제로는 시그널 번호다

struct Reactor {
    int kq;   // 내부 OS 이벤트 큐 fd (kqueue fd, epoll fd; Win32 백엔드에서는 미사용)

    // 백엔드별 내부 관리용 — 필요 없는 백엔드에서는 비워진 채 사용되지
    // 않는다(각각이 왜 존재하는지는 reactor.h 참고):
    struct Vec sigfds;    // reactor_epoll.sc의 signum -> signalfd 테이블
    struct Vec watched;   // reactor_win32.sc의 등록된 (fd, filter) 테이블

    int  init();                                             // 백엔드를 열고/초기화; 성공 0, 실패 -1
    void add(int fd, int filter);                             // 영구적인, 레벨 트리거 관심 등록
    void remove(int fd, int filter);
    int  poll(struct TaskScheduler* sched, long long timeout_ms);
        // timeout_ms: 0 = 대기 중인 것이 없으면 즉시 반환, 음수 = 무기한 블록.
        // 준비된 (fd, filter)마다 sched->unblock_fd()를 호출한다. 처리된
        // OS 이벤트 수를 반환한다(여러 태스크가 같은 fd를 기다린다면
        // 언블록된 태스크 수와 다를 수 있다).
    void close_();
}

struct Reactor reactor_init();
```

## `reactor_run` — 스케줄러 그 자체 {#reactor_run-the-scheduler-itself}

```c
void reactor_run(struct TaskScheduler* sched, struct Reactor* r);
```

`sched`를 완료까지 구동한다: 실행 가능한 모든 태스크를 틱하고, 즉시
실행 가능한 태스크가 없을 때마다 리액터를 폴링한다 — 일부 태스크가
단지 라운드 중간이면(새로 준비된 I/O를 지연 없이 집어내도록) 타임아웃을
0으로, 남은 것이 전부 I/O에서 블록되어 있다면 무기한 대기로 폴링하여,
OS가 실제로 보고할 것이 생기기 전까지 프로세스가 CPU를 전혀 소비하지
않도록 한다. 태스크가 `await_fd()`를 호출하는 모든 워크로드에서
`TaskScheduler::run_all()` 대신 이것을 사용한다.

## 예제 {#example}

```c
#include "sched/reactor.h"
#include "sched/io_nb.h"
#include "io.h"

int echo_conn(void* arg, int resume_point) {
    int fd = (int)(long long)arg;
    if (resume_point == 0) {
        // 소켓에 읽을 데이터가 생길 때까지 대기
        // (실제 프로그램에서는 전역/정적 변수를 통해 스케줄러에 접근한다)
        g_sched.await_fd(fd, SCHED_READ);
        return 1;
    }
    // ... 여기서 fd로 읽기/쓰기 ...
    return 0;   // 완료
}

int main() {
    struct TaskScheduler sched = task_sched_init();
    struct Reactor reactor = reactor_init();
    reactor.init();

    int listenfd = tcp_listen_nb(8080);
    reactor.add(listenfd, SCHED_READ);

    // ... accept 루프가 연결마다 echo_conn을 spawn하고, 각 태스크는 자신의
    //     연결 fd에 대해 await_fd를 호출한다 ...

    reactor_run(&sched, &reactor);
    reactor.close_();
    return 0;
}
```

## 논블로킹 I/O 헬퍼 (`io_nb.h`) {#non-blocking-io-helpers-ionbh}

`open`/`socket`/`accept`/`connect`를 감싸 논블로킹 모드를 활성화하는
얇은 래퍼로, 위의 리액터와 짝을 이루도록 만들어졌다: 태스크가 이
함수들 중 하나를 호출하면 프로그램 전체를 블로킹하는 대신 곧바로
`EAGAIN`/`EWOULDBLOCK`을 받고, 대신 `await_fd`를 통해 준비 상태를
기다린다. 리액터와 마찬가지로 `io_nb.h`는 하나의 이식 가능한 API를
플랫폼별 백엔드와 함께 선언한다:

| 파일 | 대상 | 논블로킹 메커니즘 |
|---|---|---|
| `io_nb_bsd.sc` | macOS, iOS, FreeBSD | `fcntl` + `O_NONBLOCK` (`0x0004`) |
| `io_nb_linux.sc` | Linux, Android | `fcntl` + `O_NONBLOCK` (`0x800`) |
| `io_nb_win32.sc` | Windows | 소켓: `ioctlsocket` + `FIONBIO`. 파일: **최선 노력(best-effort)** — 아래 참고 |

```c
#define SCHED_AF_INET     2   // 모든 백엔드에서 동일한 값 (BSD 소켓에서
#define SCHED_SOCK_STREAM 1   // 비롯된 우연으로, 이후의 모든 API가 — Winsock을
#define SCHED_O_RDONLY 0      // 포함해 — 의도적으로 유지)
#define SCHED_O_WRONLY 1
#define SCHED_O_RDWR   2

unsigned short sched_htons(unsigned short host16);
unsigned int   sched_htonl(unsigned int host32);
unsigned int   sched_ipv4(unsigned char a, unsigned char b, unsigned char c, unsigned char d);

int fd_set_nonblocking(int fd);                         // 열린 fd에 대해 논블로킹 모드 활성화
int fd_open_nb(const char* path, int flags, int mode);   // 지원되는 곳에서 논블로킹 모드로 open()

int tcp_listen_nb(unsigned short port);                  // socket+bind+listen, 논블로킹; 리스닝 fd 또는 -1
int tcp_accept_nb(int listenfd);
    // 논블로킹 accept(); 아직 대기 중인 것이 없으면 -1/EAGAIN — 호출자는
    // await_fd(listenfd, SCHED_READ) 후 재시도해야 한다.
int tcp_connect_nb(unsigned int addr_network_order, unsigned short port);
    // 논블로킹 connect(); 즉시 EINPROGRESS와 함께 반환 — 호출자는
    // await_fd(fd, SCHED_WRITE)를 사용하고 쓰기 가능 상태를 "연결 완료"로
    // 취급해야 한다(성공/실패를 구분하려면 SO_ERROR를 확인).
```

각 백엔드는 해당 플랫폼의 실제 `sockaddr_in` 레이아웃에 맞는 자신만의
`struct SockAddrIn`도 선언한다 — BSD/macOS는 Linux와 Windows에는 없는
1바이트 `sin_len` 필드가 앞에 있고(둘 다 대신 평범한 2바이트
`sin_family`를 사용), 따라서 세 가지는 서로 호환되지 않는다 — 포함한
백엔드 파일이 선언하는 것을 그대로 사용한다.

::: warning
`io_nb_win32.sc`의 `fd_open_nb`는 최선 노력(best-effort)이다: Windows에는
`O_NONBLOCK`에 대응하는 논블로킹 파일 열기가 없다(그곳에서 진짜 비동기
파일 I/O는 `FILE_FLAG_OVERLAPPED`를 붙인 `CreateFile`과, 이 리액터의
준비 상태 기반 모델과는 완전히 다른 완료 기반 모델을 의미하며 — 이는
여기서 다루는 범위 밖이다). 따라서 이 백엔드에서 이 함수를 통해 연 파일은
평범한 블로킹 파일 디스크립터다. `tcp_listen_nb`/`tcp_connect_nb`에서
얻은 소켓이 실제로 갖는 논블로킹 의미를 기대하며 `fd_open_nb`로 얻은
Windows 파일 핸들에 `await_fd()`를 호출하지 말 것 — 소켓은 세 백엔드
모두에서 완전히 논블로킹이다.
:::

::: tip 검증 상태
리액터 백엔드와 동일한 상태다: `io_nb_bsd.sc`는 macOS에서(실제
`tcp_listen_nb`/bind/listen 호출 포함) 종단 간으로 검증되었다.
`io_nb_linux.sc`와 `io_nb_win32.sc`는 컴파일되고 크로스 타겟
검증(`--target`을 통한 실제 오브젝트 코드 생성, `static_assert`로 검사한
`SockAddrIn` 레이아웃)까지는 되었지만, 실제 Linux나 Windows 호스트 위에서
런타임 테스트된 적은 아직 없다.
:::

## 스케줄링 모델 {#scheduling-model}

리액터 자체의 스케줄링은 태스크 수준에서 라운드 로빈/FIFO 방식이다
(`Task[]` 배열 순서), 이는 평범한 `TaskScheduler::run_all()`과
동일하다 — 유휴 상태일 때 CPU를 전혀 쓰지 않는 대기를 추가할 뿐,
우선순위나 데드라인 순서를 매기지는 않는다. 진정한 우선순위 스케줄링은
[`ThreadSched`](/ko/stdlib/sync#threadsched-priority-based-freestanding-threads)에
별도로 존재하며, 이는 동일한 내부 `TaskScheduler`를 감싸지만 현재는
리액터에 연결되어 있지 않다 — 오늘날 이 둘은 하나의 타입으로 결합되어
있지 않다.
