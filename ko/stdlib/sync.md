# 동기화 프리미티브

SafeC는 호스트(OS 기반) 환경과 프리스탠딩(베어메탈) 환경 모두를 위해 `std/sync/`에 여섯 개의 동기화 모듈을 제공합니다 — 그리고 프리스탠딩 타겟에서 언어 차원의 `spawn`/`join` 키워드를 위한 참조 "Hook" 백엔드인 `bare_spawn.sc`도 제공합니다([thread](/ko/stdlib/thread#backend-selection) 참고).

```c
#include "sync/spinlock.h"
#include "sync/lockfree.h"
#include "sync/channel.h"
#include "sync/mpsc.h"
#include "sync/task.h"
#include "sync/thread_bare.h"
// 또는:
#include "prelude.h"
```

다른 스레드가 아니라 *다른 프로세스*와 통신하려면 [IPC](/ko/stdlib/ipc)(`std/ipc/pipe.h`, `std/ipc/uds.h`)를 참고하세요.

`task.h`의 `TaskScheduler`는 실시간 I/O 리액터가 구동하는 기반이기도 합니다 — 스케줄러 전체를 멈추지 않고도 태스크가 파일/소켓 준비 상태를 기다릴(block) 수 있게 해주는 호스트(macOS/Linux) 이벤트 루프에 대해서는 [실시간 스케줄러](/ko/stdlib/sched)를 참고하세요.

---

## 스핀락 {#spinlock}

`__sync_lock_test_and_set` 위에 구축된 바쁜 대기(busy-wait) 상호 배제 락입니다. 잠들기(sleep)가 불가능하거나 바람직하지 않은 짧은 임계 구역(인터럽트 핸들러, 베어메탈 드라이버)에 적합합니다.

### 구조체 {#struct}

```c
struct Spinlock {
    volatile int locked;

    void lock();
    int  trylock();
    void unlock();
    int  is_locked() const;
}
```

### 생성자 {#constructor}

```c
Spinlock spinlock_init();  // 0으로 초기화된(잠기지 않은) 스핀락을 반환
```

### 메서드 {#methods}

| 메서드 | 설명 |
|--------|-------------|
| `lock()` | 락을 획득할 때까지 스핀한다. |
| `trylock()` | 획득을 시도한다; 성공하면 1, 이미 잠겨 있으면 0을 반환한다. |
| `unlock()` | 락을 해제한다. |
| `is_locked() const` | 현재 잠겨 있으면 1을 반환한다 (참고용일 뿐). |

### 예제 {#example}

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
블로킹 호출, 슬립, 또는 시간이 가변적으로 걸릴 수 있는 어떤 연산에 걸쳐서도 스핀락을 붙잡고 있어서는 안 됩니다. 그런 경우에는 `thread.h`의 뮤텍스를 사용하세요.
:::

---

## LFQueue — 락프리 SPSC 링 버퍼 {#lfqueue-lock-free-spsc-ring-buffer}

단일 생산자/단일 소비자용 웨이트프리 큐입니다. 메모리 순서 보장을 위해 컴파일러 배리어를 사용합니다. 용량은 2의 거듭제곱이어야 합니다.

### 구조체 {#struct-1}

```c
struct LFQueue {
    &heap void    buffer;     // 힙에 저장되는 원소 저장소
    unsigned long cap;        // 2의 거듭제곱 용량
    unsigned long elem_size;
    long long     head;       // 생산자가 여기에 쓴다 (원자적)
    long long     tail;       // 소비자가 여기서 읽는다 (원자적)

    int           enqueue(const void* elem);
    int           dequeue(void* out);
    int           is_empty() const;
    int           is_full() const;
    unsigned long len() const;
    void          destroy();
}
```

### 생성자 {#constructor-1}

```c
struct LFQueue lfq_new(unsigned long elem_size, unsigned long cap);   // 자체 버퍼를 힙에 할당
struct LFQueue lfq_init(&heap void buffer, unsigned long elem_size, unsigned long cap);  // 호출자가 제공한 저장소 위에서 동작
```

`cap`은 2의 거듭제곱이어야 합니다(예: 64, 128, 256). `lfq_new`로 만든 큐는 `.destroy()`로 해제해야 합니다; `lfq_init`으로 만든 큐는 아무것도 소유하지 않습니다(호출자가 `buffer`의 수명을 관리합니다).

### 메서드 {#methods-1}

| 메서드 | 설명 |
|--------|-------------|
| `enqueue(elem)` | `elem`을 큐로 복사한다. 성공 시 1, 가득 찼으면 0을 반환한다. 생산자 전용. |
| `dequeue(out)` | 맨 앞 원소를 `out`으로 복사한다. 성공 시 1, 비어 있으면 0을 반환한다. 소비자 전용. |
| `is_empty() const` | 큐가 비어 있으면 1을 반환한다. |
| `is_full() const` | 큐가 가득 찼으면 1을 반환한다. |
| `len() const` | 현재 원소 개수. |
| `destroy()` | 힙에 저장된 버퍼를 해제한다(`lfq_new`로 만든 큐에서만 의미가 있음). |

### 타입 지정 제네릭 래퍼 {#typed-generic-wrappers}

고정된 원소 타입에 대해서는, `lfq_enqueue_t`/`lfq_dequeue_t`를 사용하면 로컬 변수의 주소를 수동으로 얻지 않아도 됩니다:

```c
generic<T> int lfq_enqueue_t(&stack LFQueue q, T val);
generic<T> int lfq_dequeue_t(&stack LFQueue q, T* out);
```

### 예제 {#example-1}

```c
#include "sync/lockfree.h"
#include "io.h"

int main() {
    LFQueue q = lfq_new(sizeof(int), 64);

    // 생산자
    int a = 10;
    int b = 20;
    int c = 30;
    unsafe {
        q.enqueue((const void*)&a);
        q.enqueue((const void*)&b);
        q.enqueue((const void*)&c);
    }

    // 소비자
    int val = 0;
    while (!q.is_empty()) {
        unsafe { q.dequeue((void*)&val); }
        println_int((long long)val);  // 10, 20, 30
    }

    q.destroy();
    return 0;
}
```

::: tip
실제 SPSC 시나리오에서는 생산자와 소비자가 서로 다른 스레드나 ISR 컨텍스트에서 동작합니다. 오직 한 스레드만 enqueue하고 오직 한 스레드만 dequeue하는 한, 이 큐는 별도의 락 없이도 안전합니다.
:::

---

## Channel — 바운디드 블로킹 MPMC 채널 {#channel-bounded-blocking-mpmc-channel}

언어 자체에 내장된 채널 *문법*이 있습니다 — `chan_create`/`chan_send`/`chan_recv`/`chan_close`는 컴파일러 내장 함수로, `#include`가 필요 없으며 — `std/sync/channel.h`의 런타임(`thread.h`의 뮤텍스 + 조건 변수로 구현된, 바운디드·블로킹·다중 생산자/다중 소비자 채널)이 뒷받침합니다. 아래의 `LFQueue`/`MpscQueue`와 달리, `chan_send`/`chan_recv`는 채널이 가득 찼거나 비어 있을 때 즉시 가득 참/빈 상태를 알리는 대신 호출 스레드를 **블록**합니다.

```c
#include "sync/channel.h"
```

내장 함수들은 컴파일러 수준에서 의도적으로 타입이 지정되지 않습니다: `chan_create`는 항상 용량만 받을 뿐 원소 타입은 절대 받지 않으며, 따라서 런타임은 하나의 고정된 규약을 채택합니다 — 모든 채널은 8바이트("머신 워드") 페이로드 슬롯을 가지며, 원시 `chan_send`/`chan_recv`는 항상 `*value_ptr`/`*out_ptr`에서 정확히 8바이트를 복사합니다. 더 작은 무언가(예: 그냥 `int`)에 대한 포인터를 직접 전달하는 것은 단순한 스타일 문제가 아니라 실제 범위 밖 접근(out-of-bounds) 위험입니다 — 실제로 들어맞는 임의의 `T`에 대해 채널을 안전하게 사용하는 방법이 `chan_send_t<T>`/`chan_recv_t<T>`입니다(컴파일 타임에 `static_assert`되므로, 크기가 너무 큰 `T`는 원시 내장 함수에서 조용히 데이터가 손상되는 대신 여기서 컴파일 오류가 됩니다):

```c
generic<T> int chan_send_t(void* ch, T val);
generic<T> int chan_recv_t(void* ch, T* out);
```

### 예제 {#example-2}

```c
#include "sync/channel.h"
#include "io.h"

int main() {
    void* ch = chan_create(16);      // 여전히 원시 내장 함수
    std::chan_send_t(ch, 42);        // 인자로부터 T=int가 추론됨

    int v = 0;
    int ok;
    unsafe { ok = std::chan_recv_t(ch, (int*)&v); }
    if (ok) { println_int((long long)v); }   // 42

    chan_close(ch);                  // 여전히 원시 내장 함수
    return 0;
}
```

::: warning
수신 측의 명시적인 `(int*)&v` 캐스트(`unsafe` 안)는 오늘날 생략할 수 없습니다 — 제네릭 타입 추론은 평범한 `&v` 참조 인자를 `T*` 포인터 매개변수와 단일화하지 못합니다(표준 라이브러리의 다른 몇몇 `T*` 출력 매개변수 제네릭에도 영향을 주는, 기존에 있던 컴파일러 한계입니다). `&v`만 전달하면 "타입 인자를 추론할 수 없음" 오류가 발생합니다; `T`를 결정 가능하게 만드는 것은 바로 그 명시적 포인터 캐스트입니다.

8바이트보다 큰 `T`의 경우, 힙에 박스로 담아 그 포인터를 대신 보내세요 — 포인터는 이 컴파일러가 지원하는 모든 타겟에서 항상 정확히 8바이트이므로, `chan_send_t(ch, myStructPtr)`(`T`는 `MyStruct*`로 추론됨)는 여전히 고정 슬롯 크기에 들어맞습니다.
:::

---

## MpscQueue — 다중 생산자/단일 소비자 링 버퍼 {#mpscqueue-multi-producer-single-consumer-ring-buffer}

위의 `LFQueue`는 SPSC 사례입니다: 락프리이지만, 정확히 하나의 생산자와 하나의 소비자일 때만 올바르게 동작합니다 — 동시에 여러 생산자가 있으면 아무 조율 없이 같은 `head` 인덱스를 두고 경쟁하게 됩니다. `MpscQueue`는 다중 생산자용 형제 타입입니다: 여전히 바운디드 링 버퍼이고, 여전히 논블로킹 API(`enqueue`/`dequeue`는 호출자를 블록하는 대신 즉시 가득 참/빈 상태 표시자를 반환합니다 — 위의 `Channel`과 달리)이지만, 여러 동시 생산자에 걸친 정확성은 락프리 알고리즘이 아니라 모든 변경을 지키는 `Spinlock`으로부터 나옵니다 — 직접 짠 CAS 기반 MPSC 링 버퍼보다 영리함 대신 단순함을 택한 의도적인 선택입니다.

```c
#include "sync/mpsc.h"
```

"단일 소비자"는 이 타입 자체가 강제하는 계약이 아닙니다(스핀락이 동시 `dequeue()` 호출들도 여전히 올바르게 직렬화합니다) — `LFQueue`의 SPSC 계약이 기계적으로 체크되기보다 문서화되는 것과 마찬가지로, 이는 호출자를 위한 명명/의도 신호일 뿐입니다.

### 구조체 {#struct-2}

```c
struct MpscQueue {
    &heap void      buffer;
    unsigned long   cap;        // 원소 단위 용량 — LFQueue와 달리 2의 거듭제곱일 필요 없음
    unsigned long   elem_size;
    unsigned long   head;       // 소비자가 여기서 읽는다
    unsigned long   tail;       // 생산자들이 여기에 쓴다
    unsigned long   count;      // 대기 중인 원소 개수
    struct Spinlock lock;       // head/tail/count와 버퍼 내용을 지킨다

    int           enqueue(const void* elem);   // 성공 시 1, 가득 찼으면 0
    int           dequeue(void* out);          // 성공 시 1, 비어 있으면 0
    int           is_empty() const;
    int           is_full() const;
    unsigned long len() const;
    void          destroy();                    // 뒷받침하는 버퍼를 해제한다
}

struct MpscQueue mpsc_new(unsigned long elem_size, unsigned long cap);   // 자체 버퍼를 힙에 할당
```

### 타입 지정 제네릭 래퍼 {#typed-generic-wrappers-1}

```c
generic<T> int mpsc_enqueue_t(&stack MpscQueue q, T val);
generic<T> int mpsc_dequeue_t(&stack MpscQueue q, T* out);
```

### 예제 {#example-3}

```c
#include "sync/mpsc.h"
#include "io.h"

int main() {
    struct MpscQueue q = mpsc_new(sizeof(int), 64UL);

    int a = 10;
    int b = 20;
    int c = 30;
    unsafe {
        q.enqueue((const void*)&a);
        q.enqueue((const void*)&b);
        q.enqueue((const void*)&c);
    }

    int val = 0;
    while (!q.is_empty()) {
        unsafe { q.dequeue((void*)&val); }
        println_int((long long)val);  // 10, 20, 30
    }

    q.destroy();
    return 0;
}
```

---

## TaskScheduler — 협력형 태스크 스케줄러 {#taskscheduler-cooperative-task-scheduler}

스택 없는 협력형(비선점형) 태스크 스케줄러입니다. 태스크는 평범한 콜백이 아니라 **재개 가능한 함수**입니다: 각 태스크 함수는 자신의 인자와 함께 `resume_point`를 받고, 다음번에 재개할 지점을 반환합니다(끝났으면 `0`) — 따라서 태스크는 자신만의 OS 스택 없이도 작업 도중에 양보(yield)할 수 있습니다. `tick()`은 한 라운드 전체를 실행합니다 — 블록되지 않았고 끝나지 않은 모든 태스크가 한 번씩 차례를 얻습니다.

### 구조체 {#struct-3}

```c
struct Task {
    void* func;          // int(*)(void* arg, int resume_point)
    void* arg;
    int   state;         // TASK_READY / TASK_RUNNING / TASK_DONE
    int   resume_point;
    int   blocked;        // await_fd()에 의해 설정됨; std/sched/reactor.h 참고
    int   wait_fd;
    int   wait_filter;
};

struct TaskScheduler {
    struct Task tasks[TASK_MAX];   // TASK_MAX = 64
    int         count;
    int         current;

    int  spawn_task(void* func, void* arg);   // 태스크 인덱스를 반환, 가득 찼으면 -1
    int  tick();                               // 한 라운드; 아직 활성 상태인 태스크 수를 반환
    void run_all();                            // 모든 태스크가 TASK_DONE이 될 때까지 라운드 로빈
    int  active_count() const;

    // I/O 준비 상태 대기(blocking) — std/sched/reactor.h 참고 (std::Reactor / reactor_run)
    void await_fd(int fd, int filter);          // 현재 태스크 자신의 차례 *안에서* 호출됨
    int  unblock_fd(int fd, int filter);         // fd가 준비되면 리액터가 호출함
    int  has_blocked() const;
    int  has_ready() const;
}
```

### 생성자 {#constructor-2}

```c
struct TaskScheduler task_sched_init();
```

### 태스크 함수 계약 {#task-function-contract}

```c
int my_task(void* arg, int resume_point) {
    // resume_point는 어디까지 진행했는지 알려준다; 새 resume_point로 양보하려면
    // 0보다 큰 값을 반환하고, 태스크가 끝났으면 0을 반환한다.
    if (resume_point == 0) {
        // ... 첫 번째 작업 덩어리 ...
        return 1;               // 양보; 다음 tick에서 지점 1부터 재개
    }
    // ... 두 번째 작업 덩어리 ...
    return 0;                   // 완료
}
```

절대 양보하지 않는 태스크(한 번의 호출에서 모든 작업을 마친 뒤 `0`이 아닌 경로로 반환하거나, `await_fd`를 호출하지 않고 블록하는 태스크)는 협력형 라운드 전체를 막아버립니다 — 선점이 없기 때문입니다.

### 예제 {#example-4}

```c
#include "sync/task.h"
#include "io.h"

int task_a(void* arg, int resume_point) {
    println("Task A");
    return 0;   // 한 차례 만에 완료
}
int task_b(void* arg, int resume_point) {
    println("Task B");
    return 0;
}

int main() {
    struct TaskScheduler sched = task_sched_init();
    sched.spawn_task((void*)task_a, 0);
    sched.spawn_task((void*)task_b, 0);

    sched.run_all();   // Task A, Task B
    return 0;
}
```

### 스케줄러를 멈추지 않고 I/O 대기하기 {#blocking-on-io-without-stalling-the-scheduler}

원래대로라면 I/O를 기다리며 블록되었을 태스크는 실제로 블록되는 대신 자기 자신에 대해 `await_fd(fd, filter)`를 호출합니다; 그러면 `tick()`은 무언가가 일치하는 `(fd, filter)`로 `unblock_fd()`를 호출할 때까지 — 보통은 `std::Reactor`가 fd가 준비되었다고 알릴 때까지 — 그 태스크를 건너뜁니다(재호출도, 바쁜 폴링도 없이). 이를 구동하는 리액터에 대해서는 [실시간 스케줄러](/ko/stdlib/sched)를 참고하세요. 어떤 태스크가 `await_fd()`를 호출한 뒤 결코 언블록되지 않으면, 이를 구동하는 리액터가 없는 스케줄러는 `run_all()`을 영원히 계속 돌립니다.

---

## ThreadSched — 우선순위 기반 프리스탠딩 스레드 {#threadsched-priority-based-freestanding-threads}

`thread_bare.h`는 `TaskScheduler`를 슬롯별 우선순위 배열로 감싸, 같은 재개 가능 함수 프로토콜 위에 우선순위 정렬을 추가합니다 — 여전히 완전히 협력형입니다(실행 중인 스레드는 여전히 자발적으로 양보해야 하며, 우선순위가 높다는 것은 선점이 아니라 "한 tick 안에서 더 먼저 처리된다"는 의미일 뿐입니다).

```c
#include "sync/thread_bare.h"
```

### 타입 {#type}

```c
newtype Thread = int;   // ThreadSched 테이블로의 인덱스; THREAD_NONE (-1) = spawn 실패
```

### 구조체 {#struct-4}

```c
struct ThreadSched {
    struct TaskScheduler inner;
    int                  priority[THREAD_MAX];   // THREAD_MAX = TASK_MAX = 64

    // func: int(*)(void* arg, int resume_point) — TaskScheduler와 동일한 프로토콜.
    // 우선순위 값이 높을수록 각 tick 안에서 더 먼저 처리된다.
    Thread spawn_thread(void* func, void* arg, int priority);

    int    tick();                      // 우선순위 내림차순으로 한 차례 순회; 활성 개수를 반환
    void   run_all();                   // 모든 스레드가 끝날 때까지 실행
    int    is_active(Thread t) const;
    void   join_thread(Thread t);       // 협력형 join: t가 끝날 때까지 tick()을 호출
    int    active_count() const;
}
```

### 생성자 {#constructor-3}

```c
struct ThreadSched thread_sched_init();
```

### 예제 {#example-5}

```c
#include "sync/thread_bare.h"

int high_task(void* arg, int resume_point) { /* 긴급 작업 */ return 0; }
int low_task(void* arg, int resume_point)  { /* 백그라운드 작업 */ return 0; }

int main() {
    struct ThreadSched s = thread_sched_init();
    Thread hi = s.spawn_thread((void*)high_task, 0, 10);
    Thread lo = s.spawn_thread((void*)low_task,  0,  1);

    s.join_thread(hi);
    s.join_thread(lo);
    return 0;
}
```
