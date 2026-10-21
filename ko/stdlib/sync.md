# 동기화 프리미티브

SafeC는 호스트(OS 기반) 환경과 프리스탠딩(베어메탈) 환경 모두를 위해 `std/sync/`에 여섯 개의 동기화 모듈을 제공합니다 — 여기에 더해 `bare_spawn.sc`는 프리스탠딩 타깃에서 언어 수준 `spawn`/`join` 키워드를 위한 참조 "Hook" 백엔드입니다([thread](/ko/stdlib/thread#backend-selection) 참고).

```c
#include <std/sync/spinlock.h>
#include <std/sync/lockfree.h>     // LFQueue<T>
#include <std/sync/channel.h>      // Chan<T>
#include <std/sync/mpsc.h>         // MpscQueue<T>
#include <std/sync/task.h>         // TaskScheduler, task_spawn
#include <std/sync/thread_bare.h>  // ThreadSched, thread_spawn
#include <std/sync/parallel.h>     // parallel_for (스레드 풀)
```

다른 스레드가 아니라 *다른 프로세스*와 통신하려면 [IPC](/ko/stdlib/ipc)(`std/ipc/pipe.h`, `std/ipc/uds.h`)를 참고하세요.

`TaskScheduler`(`task.h`)는 실시간 I/O 리액터가 구동하는 기반이기도 합니다 — 태스크가 전체 스케줄러를 멈추지 않고 파일/소켓 준비 상태를 기다릴 수 있게 해 주는 호스트(macOS/Linux) 이벤트 루프는 [실시간 스케줄러](/ko/stdlib/sched)를 참고하세요.

---

## 스핀락 {#spinlock}

`__sync_lock_test_and_set` 위에 구축된 바쁜 대기(busy-wait) 상호 배제 락입니다. 잠들 수 없거나 잠드는 것이 바람직하지 않은 짧은 임계 구역(인터럽트 핸들러, 베어메탈 드라이버)에 적합합니다.

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
struct Spinlock spinlock_init();  // an unlocked spinlock
```

### 메서드 {#methods}

| 메서드 | 설명 |
|--------|-------------|
| `lock()` | 락을 획득할 때까지 스핀합니다. |
| `trylock()` | 획득을 시도합니다. 성공하면 1, 이미 잠겨 있으면 0을 반환합니다. |
| `unlock()` | 락을 해제합니다. |
| `is_locked() const` | 현재 잠겨 있으면 1을 반환합니다(참고용). |

### 예제 {#example}

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
블로킹 호출, sleep, 또는 소요 시간이 가변적인 어떤 연산에서도 스핀락을 쥔 채로 있지 마세요. 그런 경우에는 `thread.h`의 뮤텍스를 사용하세요.
:::

---

## LFQueue\<T\> — 락프리 SPSC 링 버퍼 {#lfqueue}

크기가 제한된 단일 생산자/단일 소비자 큐입니다. `head`/`tail`은 원자적 저장으로 갱신되는 단조 증가 원소 카운터입니다(원소는 `head`가 공개되기 전에 기록되고, `tail`이 슬롯을 놓아주기 전에 읽힙니다). 슬롯 인덱스만 `counter & (cap - 1)`이므로 `cap`개의 슬롯을 모두 쓸 수 있고, 센티널 슬롯 없이도 "가득 참"과 "비어 있음"을 구분합니다.

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

생산자와 소비자는 정확히 하나씩이어야 합니다. 생산자가 여럿이면 `MpscQueue`를 쓰세요.

---

## Chan\<T\> — 바운디드 블로킹 채널 {#channel}

언어의 `chan_create`/`chan_send`/`chan_recv`/`chan_close` 내장 함수는 타입이 없습니다: 런타임(`channel.sc`, 뮤텍스 + 조건 변수 기반 MPMC 큐)은 고정된 8바이트 슬롯을 운반합니다. `Chan<T>`는 원소 타입을 고정하고 각 값을 슬롯 전체로 패딩하므로, 더 작은 `T`의 범위를 넘어 읽거나 쓰는 일이 없습니다. `sizeof(T) <= 8`은 컴파일 타임 `static_assert`입니다. 이 페이지의 다른 큐와 달리, `send`/`recv`는 채널이 가득 차거나 비어 있는 동안 **블록**합니다.

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

8바이트보다 큰 `T`라면 그것에 대한 `?&T`를 보내고(참조는 지원되는 모든 타깃에서 슬롯에 들어맞습니다), 수신될 때까지 참조 대상을 살아 있게 유지하세요.

---

## MpscQueue\<T\> — 다중 생산자/단일 소비자 큐 {#mpscqueue}

`LFQueue`의 다중 생산자 형제입니다: 여전히 크기가 제한되고 논블로킹이지만(`enqueue`/`dequeue`는 즉시 반환), 모든 변경을 `Spinlock`이 보호하므로 동시 생산자 수에 관계없이 올바르게 동작합니다. "단일 소비자"는 강제되지 않는 문서상의 계약입니다(동시 `dequeue()` 호출도 여전히 올바르게 직렬화됩니다).

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

## TaskScheduler — 협력형 태스크 스케줄러 {#taskscheduler}

스택 없는(stackless) 협력형(비선점) 스케줄러입니다. 태스크는 **재개 가능한 함수**입니다: 각 태스크는 인자와 `resume_point`를 받아 다음에 재개할 지점을, 끝났으면 `0`을 반환하므로, 자체 OS 스택 없이도 작업 도중에 양보할 수 있습니다. `tick()`은 한 라운드를 실행합니다 — 블록되지 않았고 끝나지 않은 모든 태스크가 한 번씩 차례를 받습니다.

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

태스크의 인자 타입은 그 함수가 받는 타입입니다: `task_spawn`은 함수에서 `T`를 추론하므로, 인자가 맞지 않으면 런타임의 잘못된 캐스트가 아니라 컴파일 오류가 됩니다. `null` 인자도 괜찮습니다 — `T`는 여전히 함수에서 옵니다.

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

선점은 없습니다: 결코 반환하지 않는 태스크는 라운드 전체를 멈춥니다. I/O에서 블록될 태스크는 대신 `await_fd(fd, filter)`를 호출합니다. 그러면 `tick()`은 `unblock_fd()` — 보통 `std::Reactor` — 가 fd가 준비되었다고 알릴 때까지 그 태스크를 건너뜁니다. [실시간 스케줄러](/ko/stdlib/sched)를 참고하세요. 리액터가 없으면 `run_all()`은 기다리기만 하고 결코 블록 해제되지 않는 태스크에서 영원히 스핀합니다.

---

태스크 상태: `TASK_READY`(0), `TASK_RUNNING`(1), `TASK_DONE`(2). 스케줄러 하나는 태스크를 최대 `TASK_MAX`(1024)개 보관합니다.

## ThreadSched — 우선순위 기반 프리스탠딩 스레드 {#threadsched}

`thread_bare.h`는 같은 재개 가능 함수 프로토콜을 유지하면서 `TaskScheduler`에 슬롯별 우선순위를 추가합니다. 여전히 협력형입니다: 우선순위가 높다는 것은 "한 tick 안에서 먼저 처리된다"는 뜻일 뿐입니다.

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

`ThreadSched`는 `TASK_MAX`개의 태스크 슬롯을 인라인으로 담습니다(수십 KB). 작은 타깃에서는 정적 저장소에 두세요.

---

## parallel_for — 데이터 병렬 루프 {#parallel}

```c
#include <std/sync/parallel.h>
```

`[0, n)`을 최소 `grain`개 인덱스 단위의 청크로 나누고, 호출한 스레드와 상주하는 워커 스레드 풀에서 각 청크마다 `body(ctx, lo, hi)`를 실행합니다. 모든 청크가 끝나면 반환합니다. 청크는 원자적 카운터로 나눠 주므로 청크마다 작업량이 달라도 균형이 맞춰집니다.

워커는 첫 호출에서 시작된 뒤 조건 변수에서 대기하며 유지되므로, 이후 호출의 비용은 스레드 생성이 아니라 깨우기(수 마이크로초)입니다. std의 CPU 커널들이 이 풀을 씁니다: `sgemm`(그리고 이를 통해 `tensor_matmul`과 `Conv2D::forward`), 큰 FFT와 배치 FFT 경로, `fft2d`, `stft_forward`.

```c
generic<T>
void parallel_for(unsigned long n, unsigned long grain,
                  fn void(&T, unsigned long, unsigned long) body, &T ctx);

unsigned long parallel_threads();               // 호출 하나가 쓰는 스레드 수 (호출자 포함)
void          parallel_set_threads(unsigned long n);   // 1 .. PARALLEL_MAX_THREADS (64)
```

| 상황 | 동작 |
|---|---|
| `n <= grain`이거나 스레드가 하나로 설정됨 | `body(ctx, 0, n)`을 그 자리에서 실행 |
| 이미 `parallel_for`가 실행 중 (body 안에서의 중첩 호출, 또는 다른 스레드의 동시 호출) | 호출한 스레드에서 그 자리에서 실행 |
| 프리스탠딩 / 베어메탈, WebAssembly | 그 자리에서 실행 (스레드 없음) |

스레드 수는 `SAFEC_NUM_THREADS`가 설정되어 있으면 그 값, 아니면 온라인 CPU 수(최대 64)이며, `parallel_set_threads`로 마지막에 정한 값이 우선합니다.

청크 하나가 최소 10–50 µs 정도의 일을 하도록 `grain`을 고르세요. 각 인덱스 범위는 정확히 한 번의 `body` 호출에 속하므로, 청크들은 락 없이 출력의 서로 겹치지 않는 부분에 쓸 수 있습니다.

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

::: warning 실시간 코드용이 아님
호출은 가장 느린 청크가 끝날 때까지 블록되며, OS 스케줄러를 통해 스레드를 깨웁니다. 오디오 콜백 같은 데드라인이 엄격한 코드는 한 스레드에 머물러야 합니다: 그런 곳에서는 `sgemm_serial`, `fft_serial` 등을 쓰세요.
:::

