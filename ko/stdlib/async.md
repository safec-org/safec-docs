# async -- 퓨처, 실행기, 비동기 I/O

`std/async/`는 Rust의 비동기 모델을 라이브러리로 옮긴 것입니다. SafeC에는 `async fn`도, 언어 차원의 코루틴도 없습니다. 여기서 **퓨처**(future)는 `Poll<T>`를 반환하는 `poll` 메서드를 가진 평범한 구조체이고, 코루틴 매크로는 그 `poll`을 `goto`로 작성된 재개 가능한 상태 기계로 바꿉니다. 그 위에 Rust의 `core::task`와 tokio에서 익숙한 구성 요소들이 놓입니다: 웨이커(waker), 태스크와 `JoinHandle`, 힙이 필요 없는 결정적 단일 스레드 실행기(베어메탈과 wasm32에서도 동작), 멀티스레드 실행기, 타이머, `join`/`select`/`timeout`, 채널, `Mutex`/`Semaphore`/`Notify`, 취소 토큰, 그리고 [`std/sched`](/ko/stdlib/sched) 리액터 위의 비동기 TCP.

```c
#include <std/async/async.h>   // everything that runs on any target
#include <std/async/host.h>    // block_on, HostPark            (hosted)
#include <std/async/pool.h>    // ThreadPool                    (hosted)
#include <std/async/net.h>     // Runtime, async TCP            (hosted)
```

`std/async`의 어떤 것도 메모리를 할당하지 않습니다. 퓨처, 태스크, 채널 버퍼, 실행기는 모두 여러분이 제공하는 메모리에 놓입니다: `static`, `main`의 지역 변수, 더 오래 사는 구조체의 필드.

## Rust와 나란히 보기 {#rust-side-by-side}

| Rust | SafeC `std/async` |
|---|---|
| `Poll::Ready(v)` / `Poll::Pending` | `Poll::ready(v)` / `Poll::pending()` — `struct Poll<T> { int ready_; T value; }` |
| `trait Future { type Output; fn poll(self: Pin<&mut Self>, cx: &mut Context) -> Poll<Output>; }` | `trait Future { poll(&Context cx); drop(); }` — `struct Poll<T> F::poll(&Context cx)`; 출력은 그 `T` |
| 퓨처의 `impl Drop` | `F::drop()` — 명시적으로 호출(SafeC에는 소멸자가 없음) |
| `Pin` | 규칙 하나: 한 번 폴링된 퓨처는 움직이지 않는다 |
| `async fn` / `.await` | `ASYNC_BEGIN` / `AWAIT` / `ASYNC_RETURN` / `ASYNC_END` ([코루틴](#coroutines)) |
| `Context`, `cx.waker()` | `struct Context`, `cx.waker`, `cx.wake_by_ref()` |
| `Waker` (`RawWaker` + vtable) | `struct Waker` — 제네릭 `Waker::new` / `Waker::counted`로만 만듦 |
| `AtomicWaker` | `WakerSlot`(웨이커 하나), `WaitList`(여러 개) |
| `tokio::spawn`, `JoinHandle`, `abort()` | `ex.spawn(&task)` / `async_spawn(cx, &task)`, `JoinHandle<T>`, `abort()` |
| `futures::executor::block_on` | `block_on(&fut)` |
| current-thread 런타임 / `LocalSet` | `LocalExecutor`, I/O까지 포함하면 `Runtime` |
| multi-thread 런타임 | `ThreadPool` |
| `tokio::time::{sleep, interval, timeout}` | `Sleep`, `Interval`, `timeout()` |
| `join!`, `select!`, `join_all`, `select_all` | `join2`/`join3`, `select2`, `join_all`, `select_any` |
| `FutureExt::map`, `.then` | `map_fut`, `and_then` |
| `tokio::sync::{oneshot, mpsc, Mutex, Semaphore, Notify}` | `Oneshot`, `AsyncChannel`, `AsyncMutex`, `AsyncSemaphore`, `AsyncNotify` |
| `CancellationToken` | `CancelToken`, `with_cancel()` |
| `TcpListener`, `TcpStream` | `AsyncTcpListener`, `AsyncTcpStream` |
| `tokio::time::pause()` | `VirtualClock` |

두 가지 차이가 전반에 걸쳐 나타납니다:

- **출력 타입을 직접 씁니다.** SafeC 제네릭은 "`F`의 `Poll<T>`에 있는 `T`"를 이름으로 가리킬 수 없으므로(연관 타입이 없음), 컴비네이터는 자식들의 출력 타입을 타입 매개변수로 가지며, 그것은 선언에 적고 선언에서 추론됩니다: `struct Join2<struct Sleep, struct Fetch, Unit, int> j = join2(...)`.
- **드롭은 명시적입니다.** 완료 전에 포기하는 퓨처는 `drop()`해야 하며, 그래야 타이머, 채널 슬롯, 소켓 관심 등록이 해제됩니다. 컴비네이터는 포기하는 자식을, 실행기는 중단(abort)된 태스크를 드롭합니다. 여러분의 코루틴은 자신이 기다리고 있는 자식을 드롭합니다([drop](#drop) 참고).

## 빠른 시작 {#quick-start}

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
    c.drop();                                      // a Drop type: finished, so this does nothing
    return v == 42 ? 0 : 1;
}
```

코루틴 매크로는 함수형 매크로이므로 컴파일러의 `--compat-preprocessor`가 필요합니다. safeguard 패키지에서는 `Package.toml`에서 켭니다:

```toml
[build]
compat_preprocessor = true
```

(`safeguard test`는 `tests/`를 항상 이 방식으로 컴파일합니다.) 이 옵션 없이도 퓨처는 작성할 수 있습니다 — 매크로는 평범한 `switch`/`goto`로 펼쳐지며, 그 모습은 [아래](#writing-it-out-by-hand)에 있습니다.

## 모델 {#the-model}

### Poll과 Future {#poll-and-future}

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

`poll`은 퓨처가 진행할 수 있을지도 모를 때마다 실행기(또는 부모 퓨처)가 호출합니다. 절대 블록해서는 안 됩니다. 끝나거나 — `return Poll::ready(v);` — 아니면 진행이 가능해졌을 때 `cx.waker`가 깨워지도록 **준비해 둔 뒤에** `Poll::pending()`을 반환합니다: 웨이커를 타이머, 채널, I/O 드라이버에 등록했거나, (곧바로 다시 폴링되도록) `cx.wake_by_ref()`를 호출했어야 합니다. 둘 다 하지 않고 Pending을 반환한 퓨처는 다시는 폴링되지 않습니다. Ready가 된 퓨처는 끝난 것이므로 다시 폴링하지 마십시오.

`Future`는 메서드 이름으로 검사하는 [구조적 트레이트](/ko/reference/generics#traits-and-constrained-generics)입니다. 실행기와 컴비네이터는 매개변수를 이것으로 제한하므로(`generic<F: Future, T>`), `drop()`이 없는 구조체는 spawn하거나 조합하는 곳에서 거부됩니다.

### Waker {#waker}

`Waker`는 실행기에게 "이것이 속한 태스크를 다시 폴링하라"고 알려 줍니다. 데이터와 함수들의 타입 소거된 쌍으로 — Rust의 `RawWaker`와 그 vtable — 제네릭 생성자만 채우므로 소거된 호출은 모두 타입이 맞습니다:

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

`wake(data, tag)`가 태스크를 스케줄합니다. `Waker::counted`는 웨이커가 참조 카운트를 소유하는 실행기를 위해 clone/drop 훅을 추가합니다. `std/async`의 실행기들은 모두 복사 비용이 없는 평범한 `Waker::new` 웨이커를 씁니다. 태스크가 모든 등록보다 오래 사는 호출자 저장소에 있기 때문입니다(퓨처는 `drop()`에서 등록을 해제합니다). 웨이커는 작은 값입니다: 보관하려면 `clone()`, 놓으려면 `drop()`.

"누가 기다리는지 기억하기"라는 흔한 작업에는:

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

`WaitList`의 id는 하나의 등록에 속합니다: 깨워지거나 제거되고 나면 같은 슬롯의 이후 등록은 새 id를 받으므로, 오래된 id를 쥔 퓨처가 다른 퓨처를 방해할 수 없습니다. 목록이 가득 찬 것을 발견한 퓨처는 id 0을 받고 대신 스스로를 깨웁니다(올바르지만 더 바쁩니다). 두 타입 모두 스스로 잠그지 않으며, 그것을 가진 구조체가 잠급니다(아래의 모든 채널과 기본 요소에는 스핀락이 있습니다).

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

실행기는 폴링할 때마다 `Context`를 채웁니다. 퓨처는 그것을 읽습니다: 보관해야 할 때 웨이커를 복제하고, 자기 핸들을 들고 다니는 대신 거기서 타이머 큐와 I/O 드라이버를 찾습니다 — 그래서 `Sleep::new(250LL)`에는 시간 외에 아무것도 필요 없습니다. `Context::from_waker`와 `Context::noop`은 퓨처를 손으로 폴링할 때(테스트, 또는 작성 중인 컴비네이터) 씁니다.

### 고정(Pinning) {#pinning}

한 번 폴링된 퓨처는 완료되거나 드롭될 때까지 움직이면 안 됩니다: 타이머, 채널, I/O 드라이버가 그 주소를 기억합니다(`Sleep`은 자신을 타이머 휠에 등록합니다). 첫 폴링 *전에* 퓨처를 옮기는 것은 괜찮으며, 퓨처는 그렇게 조합됩니다 — `join2(a, b)`는 `a`와 `b`를 `Join2` 안으로 복사하고, `AsyncTask::new(fut)`는 `fut`를 태스크 안으로 복사합니다. 그런 다음 합성된 것을 폴링하기 전에 머무를 곳(필드, static, 태스크)에 둡니다.

### drop {#drop}

SafeC에는 소멸자가 없으므로 퓨처를 포기하는 것은 호출입니다: `fut.drop()`. 퓨처가 등록한 것(타이머, 대기 슬롯, 소켓 관심, 반쯤 열린 연결)을 해제합니다. `drop()`은 멱등이며 완료 후에 불러도 무해합니다. 누가 부르는가:

- **select / timeout / with_cancel**은 진 쪽을, 또는 시간이 다 되거나 토큰이 취소되었을 때 안쪽 퓨처를 드롭합니다.
- **`JoinHandle::abort()`**는 실행기가 태스크의 퓨처를 다시 폴링하는 대신 드롭하게 합니다.
- **코루틴 자신의 `drop()`**은 현재 재개 지점에서 기다리고 있는 자식 하나를 드롭합니다([규칙 6](#rules-and-pitfalls) 참고).

## goto로 만드는 코루틴 {#coroutines}

코루틴은 `poll`을 대기 지점이 있는 직선 코드로 작성한 퓨처입니다. 그 상태 — 재개 지점과, 대기를 넘어 사는 모든 변수 — 는 퓨처의 구조체에 있습니다. 각 `poll`은 지난번에 멈춘 곳으로 되돌아갑니다: 저장된 재개 지점에 대한 `switch`가 함수 본문 안의 `goto` 레이블로 분기합니다. 이것은 `rustc`가 `async fn`에 대해 생성하는 상태 기계를 전처리기로 써 낸 것입니다.

```c
#include <std/async/coro.h>      // (async.h includes it)
```

| 매크로 | |
|---|---|
| `ASYNC_BEGIN(state, n)` | `poll`의 첫 문장. `state`는 재개 지점을 담는 `int` 필드(첫 폴링 전에는 `ASYNC_INIT` = 0), `n`은 대기 지점의 개수로 `1..n`으로 번호를 붙입니다(최대 `ASYNC_MAX_POINTS` = 32). |
| `AWAIT(k, fut)` | 대기 지점 `k`: `fut`(퓨처 lvalue, 보통 `self`의 필드)를 Ready가 될 때까지 폴링하고 값은 버립니다. |
| `AWAIT_INTO(k, dst, fut)` | 같지만 값을 `dst`(필드)에 저장합니다. |
| `ASYNC_YIELD(k)` | 대기 지점 `k`: (스스로를 깨운 뒤) 한 번 Pending이 되어 실행기가 다른 태스크를 먼저 돌리게 합니다. |
| `ASYNC_RETURN(v)` | `Poll::ready(v)`로 끝내고 코루틴을 완료로 표시합니다. 하나의 문장이므로 `match` 갈래나 중괄호 없는 `if` 본문이 될 수 있습니다. |
| `ASYNC_END` | `poll`의 마지막 문장: 여기에 도달하면(`ASYNC_RETURN`이 없는 경로) 패닉합니다. |

`poll`의 `Context` 매개변수 이름은 반드시 `cx`여야 합니다.

### 매크로가 펼쳐지는 모습 {#what-the-macros-expand-to}

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

은 (공백을 제외하면) 다음이 됩니다:

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

재개 레이블은 함수의 스코프 구조 안에 — 여기서는 `for` 루프 안에 — 놓이고, 분기하는 `goto`가 그 안으로 점프합니다. SafeC에는 계산된 `goto`(`&&label`)가 없으므로 분기는 번호 붙은 지점들에 대한 `switch`이며, 그래서 `ASYNC_BEGIN`에 `n`이 필요합니다.

### 컴파일러가 검사하는 것 {#what-the-compiler-checks}

SafeC의 의미 분석은 코루틴 오용을 컴파일 오류로 바꾸는 세 가지 `goto` 규칙을 적용합니다([제어 흐름](/ko/reference/control-flow#goto-and-labels) 참고):

- **선언을 건너뛰는 점프 금지.** `goto`는 지역 변수의 선언을 건너뛰어 그 스코프 안으로 점프할 수 없습니다 — 레이블 지점에서 그 변수는 스코프 안에 있지만 초기화되지 않았을 것입니다. 중첩 블록(루프 본문) 안으로의 점프는, 그곳에서 스코프 안에 있는 선언 중 건너뛰는 것이 없다면 괜찮습니다.
- **이름마다 레이블 하나.** 중복 레이블은 오류입니다.
- **`match` 갈래 안의 레이블은 goto 대상이 아닙니다.**

| 실수 | 컴파일 오류 |
|---|---|
| `ASYNC_BEGIN` 뒤의 `int n = f();`가 이후 `AWAIT`에서도 스코프 안에 있음 | `goto '__async_resume_1' jumps over the declaration of 'n' (line 12), which is still in scope at the label ...` |
| `for (int i = 0; ...) { AWAIT(1, ...); }` | `i`에 대해 같은 오류 |
| 같은 지점을 두 번 대기: `AWAIT(1, ...); ... AWAIT(1, ...);` | `duplicate label '__async_resume_1'` |
| `ASYNC_BEGIN(st, 2)`인데 `AWAIT(3, ...)` | `use of undeclared identifier '__async_point_3'` |
| `ASYNC_BEGIN(st, 2)`인데 지점 2를 대기하지 않음 | `use of undeclared label '__async_resume_2'` |
| `match` 갈래 안의 `AWAIT` | `use of undeclared label '__async_resume_1'` |
| `poll`의 매개변수 이름이 `cx`가 아님 | `use of undeclared identifier 'cx'` |
| `drop()`이 없는 구조체를 `spawn`/`join2`/... 에 넘김 | `Future`를 언급하는 트레이트 제약 오류 |

매크로는 `__async_st`와 표지 변수들을 분기하는 `goto`보다 앞에 선언하므로 모든 레이블에서 보입니다. (`int`가 아니라 `AsyncResume` typedef로 선언하는 이유는, 전처리기의 [위생(hygiene) 처리](/ko/reference/preprocessor)가 매크로가 타입 키워드 뒤에 선언하는 이름을 바꾸기 때문입니다.)

### 규칙과 함정 {#rules-and-pitfalls}

**1. 대기를 넘어 사는 지역 변수는 필드입니다.** 매번의 `poll`은 함수 맨 위에서 새로 시작해 재개 레이블로 점프합니다. 그 사이의 `return Poll::pending()`을 지나면 지역 변수의 값은 남지 않습니다. 컴파일러는 재개 레이블을 가로질러 스코프 안에 있게 될 지역 변수를 거부합니다:

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

루프 카운터도 마찬가지입니다: 대기를 감싼 `for (int i = 0; ...)`은 오류이고, `for (self.i = 0; ...)`은 아닙니다. 허용되는 지역 변수는 두 종류입니다: 다음 대기 전에 끝나는 블록 안의 것(`{ int t = f(); self.n = t * 2; }`), 그리고 `ASYNC_BEGIN` *앞에* 선언한 것 — 다만 그것은 폴링할 때마다 다시 초기화되므로 상태가 아니라 임시 공간입니다.

**2. 대기 지점에는 `1..n`으로 한 번씩 번호를 붙입니다.** 컴파일러는 중복, `n`보다 큰 번호, 선언했지만 쓰지 않은 지점을 잡아냅니다(위 표).

**3. `defer`는 중단될 때마다 실행됩니다.** 매번의 Pending은 `return`이고, `return`은 함수의 `defer`를 실행합니다. 코루틴 안의 `defer close(fd)`는 처음 기다리는 순간 fd를 닫아 버립니다. `ASYNC_RETURN` 전과 `drop()` 안에서 명시적으로 정리하십시오.

**4. 빌림(borrow)은 대기를 넘어 살아남지 못합니다.** 지역 변수, 스택 값, 아레나 할당에 대한 참조를 대기를 넘어 들고 있지 마십시오: 코루틴이 재개될 때 그것이 가리키던 스택 프레임은 사라졌고, 아레나는 그 사이에 리셋되었을 수 있습니다([리전](/ko/reference/memory)은 그 안에서 실행되는 코드에게만 수명을 약속합니다). 소유한 값이나 인덱스를 상태 구조체에 두십시오. *상태 구조체 자신을 가리키는* 포인터 — `self.c.read(self.buf[0..512])`처럼 읽기 퓨처에 넘기는 자기 버퍼의 슬라이스 — 는 괜찮습니다. 구조체가 고정되어 있기 때문입니다.

**5. 대기할 때마다 새 자식 퓨처를.** 퓨처는 한 번 완료될 때까지 폴링됩니다. 같은 필드를 다시 기다리기 전에 새로 만드십시오(위의 루프 안 `self.delay = Sleep::new(...)`). 끝난 퓨처를 다시 기다리는 것은 버그입니다(끝난 코루틴은 패닉하고, 다른 퓨처는 영원히 Pending을 반환할 수 있습니다).

**6. `drop()`은 현재 대기 지점의 자식을 드롭합니다.** 재개 지점 `k`에서는 정확히 하나의 자식이 살아 있습니다: `AWAIT(k, ...)`가 폴링하던 것. 그것을 드롭하고 코루틴을 완료로 표시합니다:

```c
void Fetch::drop() {
    if (self.st == 1) self.delay.drop();
    if (self.st == 2) self.rx.drop();
    self.st = ASYNC_DONE;
}
```

**7. 끝난 코루틴을 폴링하면 패닉합니다**("coroutine polled after it completed"). Rust의 "`async fn` resumed after completion"과 같습니다.

**8. 대기는 `poll` 자신의 본문 안에서만.** 대기 지점은 헬퍼 함수 안에 있을 수 없습니다 — 레이블은 그 함수에 국한됩니다. 기다려야 하는 헬퍼는 그 자체로 퓨처(`poll`을 가진 구조체)이며, 부모에서 그것을 기다립니다.

### 손으로 써 내기 {#writing-it-out-by-hand}

`--compat-preprocessor` 없이(또는 스스로 무언가를 기다리는 말단 퓨처의 경우) 상태 기계를 직접 작성합니다. 같은 규칙이 적용되며, 컴파일러의 goto 검사가 여전히 보호해 줍니다:

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

그리고 말단 퓨처 — 다른 퓨처가 아니라 사건을 기다리는 퓨처 — 는 자신을 폴링하는 쪽의 웨이커를 보관합니다:

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

(스레드 사이에서 공유된다면 `std/async`의 기본 요소들처럼 검사와 등록을 잠금으로 감싸야 합니다.)

## 태스크와 JoinHandle {#tasks}

실행기는 **태스크**를 실행합니다: 최상위 퓨처와 그 출력을 담을 공간.

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

태스크의 저장소는 여러분의 것이며, `spawn`부터 태스크가 끝날 때까지(또는 중단되고 실행기가 그것을 확인할 때까지) 제자리에 있어야 합니다. 흔한 자리: `static`, 실행기의 실행보다 오래 사는 `main`의 지역 변수, 또는 그것을 spawn하는 부모 퓨처의 필드(예: 서버의 연결별 핸들러):

```c
static struct AsyncTask<struct Fetch, int> t1;      // static storage
t1 = AsyncTask::new(Fetch::new());
struct JoinHandle<int> h = ex.spawn(&t1);
```

태스크 안에서는 `async_spawn(cx, &task)`가 자신을 폴링하는 실행기에 spawn합니다(tokio의 `tokio::spawn`). `JoinHandle`은 퓨처입니다: 다른 태스크에서 `AWAIT_INTO(k, self.result, self.handle)`로 기다립니다(`self.result`는 `?T`).

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

단일 스레드 실행기입니다. **결정적**입니다: 태스크는 spawn 순서대로, 깨워진 태스크는 라운드마다 한 번씩 폴링되며, 아는 시간은 `Park` 소스가 알려 주는 것뿐입니다. **힙, 잠금, OS를 쓰지 않으므로** 호스트 대상뿐 아니라 베어메탈과 wasm32용으로도 빌드됩니다. 웨이커는 다른 스레드나 인터럽트 핸들러에서 불러도 안전합니다: 깨우기는 두 번의 원자적 저장(태스크의 NOTIFIED 비트, 실행기의 플래그)과 `Park::unpark()`입니다.

`run(&park)`은 반복합니다: 타이머를 `park.now_ms()`로 전진시키고, 한 라운드를 돌고, 실행할 것이 없으면 다음 타이머 마감까지(또는 `-1`: 무언가가 태스크를 깨울 때까지) `park.park(timeout)`. 타이머가 라운드마다 전진하므로, 스스로를 계속 깨우는 태스크가 있어도 타이머가 멈추지 않습니다. `block_on(&park, &fut)`은 `fut`(태스크가 아니라 호출 스레드에서 폴링됨)가 Ready가 될 때까지 같은 일을 하며, 그동안 spawn된 태스크들도 실행합니다.

### Park 소스 {#park}

```c
trait Park {
    long long now_ms();               // monotonic milliseconds
    void park(long long timeout_ms);  // wait up to timeout (-1: until unpark), or less
    void unpark();                    // end the current (or the next) park early
}
```

| Park | 시계와 대기 | 대상 |
|---|---|---|
| `VirtualClock::new(start_ms)` | 모의 시간: `park(t)`는 기다리는 대신 시계를 `t`만큼 앞으로 건너뜁니다(tokio의 paused time) — 실행에 실제 시간이 들지 않고 정확히 재현됩니다. `park(-1)`(모든 태스크가 기다리고 대기 중인 타이머가 없음)은 교착 상태이므로 패닉합니다 | 모두 |
| `FnPark::new(now_fn, idle_fn)` | 여러분의 함수: 예컨대 마이크로컨트롤러의 SysTick 카운터와 `wfi` | 모두 |
| `HostPark::new()` | OS 단조 시계, 잠들 조건 변수. `unpark`가 신호를 보냄 | 호스트(`host.h`) |
| `IoDriver` | OS 시계, epoll/kqueue/WSAPoll에서 대기 | 호스트(`net.h`) |

타이밍 로직의 결정적 테스트:

```c
struct LocalExecutor ex = LocalExecutor::new();
struct VirtualClock clk = VirtualClock::new(0LL);
ex.spawn(&t1);
ex.spawn(&t2);
ex.run(&clk);
// clk.now is now exactly the virtual time the tasks needed
```

인터럽트가 밀리초 틱을 세는 마이크로컨트롤러에서:

```c norun
static volatile long long g_ms = 0;               // incremented by the SysTick handler
static long long board_now() { return g_ms; }
static void board_idle(long long timeout_ms) { unsafe { asm volatile("wfi"); } }   // any interrupt wakes it

static struct LocalExecutor ex;
int main() {
    ex = LocalExecutor::new();
    struct FnPark park = FnPark::new(board_now, board_idle);
    ex.spawn(&blink_task);
    ex.spawn(&uart_task);
    ex.run(&park);
}
```

태스크를 깨우는 인터럽트 핸들러(UART 수신이 퓨처를 완료시키는 경우)는 태스크의 웨이커를 호출하며, 웨이커는 원자적 저장만 하므로 핸들러에서 안전합니다.

### block_on {#block-on}

```c
#include <std/async/host.h>
generic<F: Future, T> T block_on(&F fut);
```

새 `LocalExecutor`와 `HostPark`로 호출 스레드에서 `fut`를 완료까지 실행합니다(Rust의 `futures::executor::block_on`). 그것이 spawn한 태스크들은 그동안 같은 스레드에서 실행되며, `fut`가 끝났을 때 아직 실행 중인 것은 그 실행기와 함께 버려집니다. `T`는 대상에서 추론되므로 결과를 변수에 받으십시오: `int n = block_on(&f);`.

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

멀티스레드 실행기: `n`개의 워커가 하나의 FIFO 실행 큐를 공유합니다(공유 큐 설계로, 작업 훔치기가 없는 tokio의 multi-thread 런타임과 비슷합니다). 태스크 타입, 타이머, 채널, 기본 요소는 `LocalExecutor`와 같습니다. 태스크는 결코 두 스레드에서 동시에 실행되지 않지만 — 폴링 중의 깨우기는 폴링 후에 다시 큐에 넣습니다 — 연속된 폴링이 다른 워커에서 실행될 수 있으므로, 태스크가 공유하는 것은 스레드 안전해야 합니다(`std/async`의 기본 요소는 그렇습니다. 평범한 전역 변수에는 원자적 연산이 필요합니다). 쉬는 워커는 일이 오거나 다음 타이머가 될 때까지 조건 변수에서 잠들고, 바쁜 워커는 폴링 사이에 타이머를 전진시킵니다. `start()` 전에 풀을 머무를 곳에 두십시오(워커가 그 주소를 기억합니다). I/O 퓨처는 `Runtime`이 필요하며 풀에서는 실행되지 않습니다. 풀의 태스크는 채널이나 `Oneshot`으로 `Runtime`에 결과를 넘길 수 있으며, 그 깨우기는 스레드를 넘어 `Runtime`에 닿습니다.

```c
static struct ThreadPool pool;
pool = ThreadPool::new();
pool.start(4);
for (int i = 0; i < 32; i = i + 1) { jobs[i] = AsyncTask::new(Job::new(i)); pool.spawn(&jobs[i]); }
pool.wait_idle();
int seven = pool.block_on(Job::new(7));
pool.shutdown();
```

## 타이머 {#timers}

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

시간은 실행기 시계의 밀리초입니다: `HostPark`/`IoDriver`에서는 OS 단조 시계, 베어메탈에서는 여러분의 `Park`가 세는 것, `VirtualClock`에서는 모의 시간. `Sleep`의 마감은 처음 폴링될 때 큐의 현재 시간을 기준으로 정해집니다. 큐는 마지막으로 전진한 시간만 알며, 그래서 결정적입니다.

큐는 1 ms 틱의 [`std/dsp/timer_wheel.h`](/ko/stdlib/dsp) `TimerWheel`(동시 타이머 64개)이며, 틱 단위가 아니라 만기가 되는 타이머에서 다음 타이머로 건너뛰며 전진합니다. 휠이 가득 찬 것을 발견한 `Sleep`은 큐의 오버플로 목록(`ASYNC_WAITERS`개 더)에서 기다리다가, 슬롯이 비거나 마감이 되면 다시 시도합니다. 모든 연산이 큐의 스핀락을 잡으므로 `ThreadPool`의 워커들이 하나를 공유합니다.

`Interval`은 Ready가 될 때마다 다시 폴링됩니다 — 스트림입니다. 틱은 첫 폴링부터 주기마다 만기가 되고, 태스크가 바빠서 놓친 틱은 연달아 전달된 뒤 원래 위상대로 일정이 이어집니다(tokio의 `MissedTickBehavior::Burst`).

## 컴비네이터 {#combinators}

모두 자식을 값으로(첫 폴링 전에) 받아 소유합니다:

| 생성자 | 타입 | 출력 |
|---|---|---|
| `join2(a, b)` | `Join2<A, B, TA, TB>` | 둘 다 Ready일 때 `Both<TA, TB>`(`.first`, `.second`) |
| `join3(a, b, c)` | `Join3<A, B, C, TA, TB, TC>` | `Both<TA, Both<TB, TC>>` |
| `join_all(futs, outs)` | `JoinAll<F, T>` | `int`(개수); 퓨처 `i`의 출력은 `outs[i]`에. 슬라이스, 최대 64개 |
| `select2(a, b)` | `Select2<A, B, TA, TB>` | `Either<TA, TB>`: `.which`가 1(`.left`) 또는 2(`.right`); 진 쪽은 드롭 |
| `select_any(futs)` | `SelectAny<F, T>` | `Picked<T>`(`.index`, `.value`); 나머지는 드롭 |
| `timeout(f, ms)` | `Timeout<F, T>` | `?T`: `ms`가 먼저 지나면 null(그때 `f`는 드롭) |
| `map_fut(f, fn)` | `MapFut<F, T, U>` | `U` = `fn(f의 출력)` |
| `and_then(f, fn)` | `AndThen<F, T, G, U>` | `f`를 실행한 뒤 `fn(출력)`이 돌려준 퓨처를 실행; 그 `U` |
| `with_cancel(f, &token)` | `Cancellable<F, T>` | `?T`: 토큰이 먼저 취소되면 null([동기화](#sync)) |
| `Ready::new(v)`, `Pending::new()`, `YieldNow::new()` | 말단 퓨처 | 즉시 `v`; 영원히 안 됨; 한 번 Pending 후 `UNIT` |

폴링은 인자 순서대로입니다(둘 다 준비된 select는 첫 번째를 고릅니다). 출력 타입 매개변수는 선언에 적습니다:

```c
struct Join2<struct Sleep, struct Fetch, Unit, int> j = join2(Sleep::new(50LL), Fetch::new());
struct Select2<struct AcceptFut, struct Cancelled, struct AsyncTcpStream, Unit> s =
    select2(listener.accept(), token.cancelled_fut());
struct Timeout<struct ReadFut, long long> t = timeout(conn.read(self.buf[0..512]), 1000LL);
```

코루틴 안에서는 컴비네이터를 필드에 두고 다른 퓨처처럼 기다립니다: `?long long res` 필드로 `AWAIT_INTO(2, self.res, self.t);`.

## 채널 {#channels}

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

가득 찬 채널은 송신자를 기다리게 하고(배압), 빈 채널은 수신자를 기다리게 합니다. `close()`는 대기 중인 송신을 0으로 완료시키고, 수신자가 버퍼에 남은 것을 다 꺼낸 뒤 null을 받게 합니다. 모든 연산이 채널의 스핀락을 잡으므로 생산자와 소비자는 서로 다른 실행기나 스레드의 태스크일 수 있습니다. 채널에서 나온 송신/수신 퓨처가 있는 동안에는 채널을 옮기면 안 되며, 값마다 새 퓨처를 만드십시오.

```c
static int slots[8];
static struct AsyncChannel<int> ch;
ch = AsyncChannel::new(slots[0..8]);
// producer coroutine:  self.tx = ch.send(v);  AWAIT_INTO(1, self.ok, self.tx);
// consumer coroutine:  self.rx = ch.recv();   AWAIT_INTO(1, self.got, self.rx);   // ?int
```

## Mutex, Semaphore, Notify, 취소 {#sync}

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

잠금 가드는 없습니다(소멸자가 없으므로): `lock()` / `acquire(n)`을 완료한 쪽이 잠금이나 허가를 소유하며 `unlock()` / `release(n)`을 직접 호출합니다. 대기를 넘어 그것을 쥐고 있는 것이 바로 이것들의 용도입니다. `drop()`에서, 현재 대기 지점에서 잠금을 쥐고 있는 코루틴은 그것을 해제합니다.

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

흔한 종료 방식: `CancelToken` 하나를 두고, 오래 도는 루프는 모두 다음 사건과 `token.cancelled_fut()` 사이에서 select하며, `main`은 할 일을 마치면 토큰을 취소합니다([예제](#example) 참고).

## 비동기 I/O {#async-io}

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
    struct WriteAllFut write_all([]const unsigned char buf);   // -> int: 1 all written, 0 on error
    int close();
};

struct IoReady { static struct IoReady new(int fd, int filter); };   // -> UNIT once ready
```

I/O 퓨처는 논블로킹 호출을 바로 시도합니다([`std/sched/io_nb.h`](/ko/stdlib/sched)의 소켓). 블록될 상황이면 그 fd의 준비 상태에 대해 태스크의 웨이커를 `IoDriver`에 등록하고 Pending이 됩니다. `IoDriver`는 `Reactor::poll_events`로 구동되는 [`std::Reactor`](/ko/stdlib/sched#reactor) — Linux에서는 epoll, macOS/BSD에서는 kqueue, Windows에서는 WSAPoll — 이며, 동시에 실행기의 `Park`이기도 합니다: 실행할 태스크가 없으면 `Runtime`은 소켓이 준비되거나, 다음 타이머가 되거나, 다른 스레드가 태스크를 깨울 때까지 OS 준비 큐에서 기다립니다(POSIX에서는 깨우기 파이프가 대기를 끊습니다).

연결마다 핸들러 태스크를 하나씩 두는 에코 서버:

```c
#include <std/async/async.h>
#include <std/async/net.h>

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
        if (self.ok == 0) break;
    }
    unsafe { self.c.close(); }    // early, in place: drop() then closes nothing again
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
        if (self.c.fd < 0) break;
        {
            struct EchoConn e;
            e.st = ASYNC_INIT;
            e.c = std::replace(&self.c, AsyncTcpStream::from_fd(-1));   // move the stream out
            self.conns[self.n] = AsyncTask::new(e);
            drop(async_spawn(cx, &self.conns[self.n]));  // the handler lives in the server's struct
        }
    }
    ASYNC_RETURN(self.n);
    ASYNC_END;
}

void Server::drop() {
    if (self.st == 1) self.acc.drop();
    self.c.drop();
    for (int i = 0; i < self.n; i = i + 1) self.conns[i].drop();
    self.l.close();
    self.st = ASYNC_DONE;
}

static struct Runtime rt;
static struct AsyncTask<struct Server, int> server;

int main() {
    rt = Runtime::new();
    if (rt.init() != 0) return 1;
    struct Server s;
    s.st = ASYNC_INIT;
    s.n = 0;
    s.l = AsyncTcpListener::bind((unsigned short)7000);
    server = AsyncTask::new(s);
    drop(rt.spawn(&server));      // the JoinHandle: not awaited here
    rt.run();
    rt.close_();
    return 0;
}
```

한계: 드라이버당 동시 대기 `ASYNC_IO_WAITERS`(256)개(테이블이 가득 찬 것을 본 대기는 대신 다시 폴링합니다). Linux에서는 fd 하나를 한 번에 한 방향으로만 기다립니다(epoll은 fd를 한 번만 등록합니다 — 같은 소켓에서 읽기와 쓰기가 동시에 기다리면 두 번째는 다시 폴링합니다). Windows에서는 드라이버가 한 번에 최대 `ASYNC_IO_REMOTE_MS`(20 ms)만 대기하므로 다른 스레드의 깨우기는 그 안에 반영됩니다. 또한 Windows 10 2004 이전의 WSAPoll은 거부된 연결을 보고하지 않으므로, 그곳에서는 연결에 `timeout()`을 거십시오.

## 예제 프로젝트 {#example}

[`examples/async_demo`](https://github.com/safec-org/SafeC/tree/main/examples/async_demo)는 이것들을 하나의 `Runtime`에 모읍니다: 다음 연결과 `CancelToken` 사이에서 select하고 연결마다 핸들러 태스크를 고정된 슬롯에 spawn하는 HTTP/1.0 서버, 연결해서 요청을 보내고 타임아웃 아래에서 응답을 읽는 클라이언트 여덟 개, 타이머로 도는 통계 태스크, 그리고 두 스레드짜리 `ThreadPool`에서 소수를 세는 작업 — 그 답은 `Oneshot`으로 도착하며, 다른 스레드의 깨우기가 리액터에서 기다리던 `Runtime`을 깨웁니다. `main`은 여덟 클라이언트의 `JoinHandle`을 `join_all`로 기다린 뒤 토큰을 취소하고, 서버와 통계 태스크가 정리됩니다. CI가 Linux, macOS, Windows에서 빌드하고 실행합니다.

```bash
cd examples/async_demo && safeguard run
```

## 대상 {#targets}

`std/async/`는 독립된 [릴리스 섹션](/ko/stdlib/#including-the-standard-library)(`lib/<triple>/std/async.a`)입니다.

| 헤더 | 대상 | 필요한 것 |
|---|---|---|
| `future.h`, `coro.h`, `executor.h`, `timer.h`, `combinator.h`, `channel.h`, `sync.h` (`async.h`) | 모든 대상: 호스트, 베어메탈, wasm32 | 원자적 연산(컴파일러 내장), `std/dsp/timer_wheel` |
| `host.h` | 호스트 | `std/thread`, `std/time` |
| `pool.h` | 호스트 | `std/thread` |
| `net.h` | 호스트: Linux, macOS/BSD, Windows | `std/sched`(reactor, io_nb); Windows에서는 `ws2_32`(자동 링크) |

베어메탈·wasm32 테스트 모음([`tests/stdlib/baremetal`](/ko/reference/baremetal))은 모든 베어메탈 별칭에서 `AsyncMutex`를 쓰는 `AsyncChannel` 위의 생산자/소비자 코루틴과, `VirtualClock` 위에서 두 `Sleep`의 `select2`를 실행합니다.

## TaskScheduler와의 비교 {#compared-with-taskscheduler}

[`std/sync/task.h`](/ko/stdlib/sync#taskscheduler)의 `TaskScheduler`(와 그것을 구동하는 [`std/sched`](/ko/stdlib/sched) 리액터)는 더 오래되고 더 저수준인 모델입니다: 태스크는 재개할 지점을 반환하는 함수 `int f(?&T arg, int resume_point)`이고, `await_fd`로 스스로를 표시해 I/O에서 블록합니다. 태스크 사이에 흐르는 값이 없고, 웨이커가 없으며(블록된 태스크는 훑어서 찾음), 조합은 손으로만 합니다. `std/async`는 "재개 지점을 자기 상태에 둔다"는 같은 생각과 같은 OS 백엔드를 유지하면서, 값을 반환하는 퓨처, 웨이커, 컴비네이터, 교체 가능한 실행기(가상 시간, 베어메탈, 스레드, I/O)를 더합니다. 둘 다 남아 있으며, 여러 가지를 동시에 기다리는 새 코드는 보통 퓨처로 쓰는 편이 더 간단합니다.
