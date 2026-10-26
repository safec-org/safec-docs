# 스레드 안전성

SafeC는 스레드가 무엇에 접근할 수 있는지를 컴파일 타임에 검사합니다. 모든 타입은 **Send**(그 타입의 값을 다른 스레드로 옮길 수 있음)와 **Sync**(그 타입에 대한 `const &`를 스레드 간에 공유할 수 있음)로 분류됩니다. 컴파일러는 두 속성을 타입의 구조로부터 유도하며, 스레드 API — [`spawn`](/ko/reference/concurrency#spawn-and-join), `spawn_scoped`, 채널, `std::thread_spawn`, `Mutex<T>`, async 스레드 풀 — 는 넘겨도 안전한 것만 받습니다. [소유권](/ko/reference/ownership)과 함께 이는 `unsafe` 밖의 코드에서 데이터 경쟁을 배제합니다.

여기의 어떤 것도 런타임 비용이 없습니다. 검사는 프로그램을 받아들이거나 거부할 뿐입니다.

## Send와 Sync {#send-and-sync}

| 타입 | Send | Sync |
|------|------|------|
| 스칼라, 열거형, 벡터, 함수 참조 | 예 | 예 (읽기는 안전; 가변 참조를 통한 동시 *쓰기*는 안전하지 않음) |
| raw 포인터 `T*` | 아니오 | 아니오 |
| `&static T` / `const &static T`, 리전 없는 `&T` | `T`가 Sync이면 예 (가변: `T`가 자신의 쓰기를 동기화하면) | 동일 |
| `&stack T`, `&arena<R> T`, `&heap T` | 아니오: 다른 스레드가 실행되는 동안 대상이 사라질 수 있음 | `T`와 같음 |
| 슬라이스 `[]T` | 아니오 (스레드보다 오래 살지 못할 수 있는 메모리의 뷰) | 원소가 Sync이면 |
| 배열, 옵셔널, 튜플, 구조체 | 모든 멤버가 그러하면 | 모든 멤버가 그러하면 |

`Drop`을 구현하는 타입 안의 raw 포인터 필드와 `&heap` 필드는 그 타입이 단독으로 소유하는 버퍼로 간주되므로, 가리키는 대상만큼 Send/Sync입니다. `Vec<int>`와 `String`은 Send입니다.

리전 없는 참조(리전 키워드가 없는 `&T`)는 SafeC 전반에서 수명 추적을 포기한 것이므로, 여기서도 `&static` 참조처럼 취급됩니다.

### 마커 {#markers}

구조체는 본문에서 유도된 결과를 바꿀 수 있습니다:

```c
struct AtomicInt {
    int v;
    unsafe impl Send + Sync;   // 모든 접근이 원자적
};

struct GuiWindow {
    void* handle;
    impl !Send;                // 만든 스레드에 머물러야 함
    impl !Sync;
};
```

`unsafe impl Sync;`는 그 타입이 자기 상태에 대한 쓰기를 동기화한다는 약속이기도 하므로, 그 타입에 대한 가변 참조도 공유할 수 있습니다(락이 바로 그런 것입니다). `impl !Send;` / `impl !Sync;`는 필드가 허용하더라도 속성을 없앱니다. 컴파일러가 검사할 수 없으므로 긍정 마커는 `unsafe`입니다.

### 바운드 {#bounds}

`Send`와 `Sync`는 [트레이트](/ko/reference/generics#built-in-traits)이며 제네릭 매개변수를 제한할 수 있습니다:

```c
generic<T: Send, R: Send>
struct SpawnedThread<T, R> thread_spawn(fn R(T) f, T arg);
```

```c
struct Raw { int *p; };
struct SpawnedThread<struct Raw, int> t = thread_spawn(raw_len, r);
// error: type 'Raw' does not satisfy constraint 'Send' for generic parameter 'T'
//        ('Raw' field 'p': raw pointer 'int*' can't be sent to another thread
//        (nothing guards what it points to))
```

## spawn {#spawn}

`spawn`으로 시작한 스레드는 어떤 스코프에도 묶이지 않으므로(프로그램이 끝날 때까지 실행될 수 있음), 인자는 `null`이거나 **정적 데이터**를 가리켜야 합니다:

- `const &static T`는 `T`를 읽기용으로 공유합니다: `T`는 Sync여야 합니다;
- 가변 `&static T`는 쓰기용으로 공유합니다: `T`는 자기 상태를 동기화해야 합니다(`Mutex`, 원자 연산);
- 스택, 아레나, 힙 데이터에 대한 참조는 거부됩니다;
- raw 포인터는 `unsafe { }` 안에서만 허용됩니다.

```c
static struct Counter g_c;
static struct Mutex<int> g_total;

spawn(work, &c);         // error: spawn: the argument '&stack Counter' refers to data the
                         //        thread may outlive (a spawned thread isn't joined by its
                         //        scope): pass static data, or use spawn_scoped ...
spawn(work, &g_c);       // error: spawn: the thread would share 'Counter' (static data) with
                         //        every other thread through a mutable reference, but it is
                         //        not safe to write concurrently: ...
spawn(add, &g_total);    // OK: Mutex는 자기 상태를 동기화함
spawn(read_only, (const &static int)&g_limit);   // OK: int는 Sync
```

## spawn_scoped {#spawn-scoped}

`spawn_scoped` 스레드는 시작한 함수가 반환하기 전에 join되므로(컴파일러가 `defer`처럼 모든 출구 경로에 join을 생성), 지역 변수를 빌릴 수 있습니다. 그러면 컴파일러는 그 대여가 지켜지는지 확인합니다:

- 가변 참조는 **값을 넘겨줍니다**: 인자의 타입은 Send여야 하고, 시작한 스레드는 그 스레드를 join할 때까지 값을 건드릴 수 없습니다;
- const 참조는 **읽기용으로 공유합니다**: 타입은 Sync여야 하며, 시작한 스레드는 계속 읽을 수 있지만 쓸 수는 없습니다;
- 스스로 동기화하는 Sync 타입(`Mutex`, 원자 연산)의 값은 양쪽 모두 계속 사용할 수 있습니다;
- 값은 스레드보다 오래 살아야 합니다: 스코프가 끝나기 전에 `join(h)`가 와야 합니다.

```c
void ok() {
    struct Counter c; c.n = 0;
    long long h = spawn_scoped(work, &c);
    join(h);
    c.n = c.n + 1;                        // OK: 스레드가 끝남
}

void touch() {
    struct Counter c; c.n = 0;
    long long h = spawn_scoped(work, &c);
    c.n = 5;      // error: 'c' is lent to the thread started here by spawn_scoped, which may
                  //        still be using it: join that thread (join(h)) before touching it again
    join(h);
}

void too_short() {
    long long h = 0;
    {
        struct Counter c; c.n = 0;
        h = spawn_scoped(work, &c);
    }             // error: 'c' goes away while the thread started by spawn_scoped may still
                  //        be using it: join that thread (join(h)) first
    join(h);
}
```

## 채널 {#channels}

`chan_send(ch, &v)`는 `v`의 타입이 Send일 것을 요구하며, [Drop 타입](/ko/reference/ownership)의 값은 수신자에게 move됩니다. 이후 `v`를 사용하면 move 후 사용입니다. `chan_recv(ch, &out)`는 `out`에 새 값을 저장합니다.

```c
struct Holds { int *p; };
chan_send(ch, &h);   // error: chan_send: a value of type 'Holds' can't be sent to another
                     //        thread: 'Holds' field 'p': raw pointer ...
```

타입이 있는 `std::Chan<T>`(`std/sync/channel.h`)와 async `AsyncChannel<T>` / `Oneshot<T>`는 `T: Send`를 요구합니다.

## 데이터를 소유하는 스레드 {#thread-spawn}

`std::thread_spawn(f, arg)`(`std/thread.h`)는 `arg`를 새 스레드로 move하고 `SpawnedThread<T, R>`를 반환합니다. 이는 Drop 값이며, 그 `join()`(`consume` 메서드)은 기다린 뒤 `f`의 결과를 반환합니다. 아무것도 공유하지 않으므로 경쟁이 일어날 수 없습니다:

```c
int vlen(struct Vec<int> v) { int n = (int)v.len; v.drop(); return n; }

struct Vec<int> v = make_vec();
struct SpawnedThread<struct Vec<int>, int> t = thread_spawn(vlen, v);
v.push(1);           // error: use of 'v' after it was moved
int n = t.join();
```

join을 잊으면 다른 경우와 같은 누수 오류입니다. `SpawnedThread`를 drop하면 join하고 결과를 drop합니다.

## Mutex와 가드 {#mutex}

`Mutex<T>`는 잠겨 있는 동안에만 접근할 수 있는 `T`를 소유합니다. `lock()`은 `drop()`에서 잠금을 푸는 Drop 값 `MutexGuard<T>`를 반환합니다. `guard.get()`은 보호되는 값 안을 가리키는 참조를 반환하며, 이 참조는 가드에게서 빌린 것입니다:

```c
static struct Mutex<int> g_m;

void add(int k) {
    struct MutexGuard<int> g = g_m.lock();
    *g.get() = *g.get() + k;
    g.drop();                               // 잠금 해제
}
```

소유권 규칙 덕분에 전형적인 실수들이 컴파일 오류가 됩니다:

```c
int forgot_unlock() {
    struct MutexGuard<int> g = g_m.lock();
    return *g.get();      // error: value of Drop type 'MutexGuard<int>' leaks when the
}                         //        function returns: 'g' is never dropped

int after_unlock() {
    struct MutexGuard<int> g = g_m.lock();
    &int v = g.get();
    g.drop();
    return *v;            // error: use of a reference borrowed from 'g' after 'g' was dropped
}

int mutex_gone() {
    struct Mutex<int> m = Mutex::new(1);
    struct MutexGuard<int> g = m.lock();
    m.drop();
    return *g.get();      // error: use of 'g', which borrows from 'm', after 'm' was dropped
}
```

`MutexGuard`는 `!Send`입니다: 잠근 스레드가 잠금을 풀어야 합니다. `Mutex<T>`는 `T: Send`를 요구하며 Sync입니다. `RwLock<T>`(`T: Send + Sync`)도 같은 방식으로 동작하며, `read()` 가드는 `const &T`를, `write()` 가드는 `&T`를 줍니다. `into_inner()`는 뮤텍스를 소비하고 값을 반환합니다.

## Async {#async}

- `ThreadPool::spawn`과 `ThreadPool::block_on`은 future와 그 출력이 Send일 것을 요구합니다: 태스크는 워커 스레드에서 실행됩니다. 지역 변수를 빌리는(`&stack` 참조를 가진) future는 Send가 아니므로 거부됩니다.
- `LocalExecutor`는 모든 것을 한 스레드에서 실행하므로 그런 요구가 없습니다. 그 태스크는 지역 변수를 빌릴 수 있습니다. spawn되었지만 끝나지 않은 `AsyncTask`를 drop하면, executor가 해제된 메모리를 poll하도록 두는 대신 패닉합니다 — 태스크를 끝내거나, `abort()`한 뒤 끝날 때까지 executor를 실행하십시오.
- `AsyncChannel<T>`, `Oneshot<T>`, `Chan<T>`는 `T: Send`를 요구합니다. await 가능한 타입들(`Context`, waker, 타이머, 태스크, join 핸들)은 `Drop`을 구현하므로 모든 future는 정확히 한 번 drop됩니다.

## 검사하지 않는 것 {#limitations}

- **`unsafe`와 raw 포인터.** `spawn`에 넘기는 raw 포인터 인자는 `unsafe` 안에서만 허용되며, 그 경우 프로그래머의 책임입니다. 긍정 마커(`unsafe impl Send/Sync`)가 약속하는 것도 마찬가지입니다.
- **OS 계층.** `std::thread_create`와 `std/thread.h`의 핸들 기반 `mutex_*`, `cond_*`, `rwlock_*` 함수는 검사되는 계층의 기반이 되는 타입 없는 기본 요소로, 스레드가 무엇을 공유하는지 전혀 검사하지 않습니다. 대신 `thread_spawn`, `Mutex<T>`, `RwLock<T>`를 사용하십시오.
- **리전 없는 참조**는 정적인 것으로 취급됩니다([Send와 Sync](#send-and-sync) 참조).
- **교착 상태**, 락 순서, 한 스레드에서 뮤텍스를 두 번 잠그는 것은 감지하지 않으며, 기아 상태도 마찬가지입니다.
- **원자 연산**은 Sync이지만, 그 위에 만든 lock-free 알고리즘이 올바른지는 컴파일러가 검사하지 않습니다.
- **외부 코드.** `extern` 함수를 통해 C에 넘긴 데이터는 분석 범위 밖입니다.
