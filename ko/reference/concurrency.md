# 동시성

SafeC는 저수준의, C와 호환되는 동시성 기본 요소를 제공합니다. 스레드는 `spawn`으로 생성되고 `join`으로 join되며, POSIX pthread에 직접 매핑됩니다. 클로저나 런타임 스케줄러는 존재하지 않습니다 -- 동시성은 명시적이고 결정론적입니다.

스레드가 무엇에 접근할 수 있는지는 컴파일 타임에 검사됩니다. 모든 타입은 (구조로부터 유도되는) Send 그리고/또는 Sync이며, `spawn`, `spawn_scoped`, 채널, 표준 라이브러리의 스레드 타입은 공유해도 안전한 것만 받습니다. 전체 규칙은 [스레드 안전성](/ko/reference/threads)을 참고하십시오.

## Spawn과 Join {#spawn-and-join}

### 스레드 생성 {#creating-a-thread}

`spawn(fn, arg)`은 주어진 함수를 주어진 인자와 함께 실행하는 새 스레드를 생성합니다. 스레드 핸들(`long long`, 내부적으로는 `pthread_t`)을 반환합니다.

```c
extern int printf(const char* fmt, ...);
static int g_value = 42;

?&int worker(?&int arg) {
    match (arg) {
        case some(v): { printf("Worker received: %d\n", *v); }
        case null:    { }
    }
    return null;
}

int main() {
    // 분리될 수 있는 스레드는 정적 데이터만 볼 수 있음; 여기서는 읽기 전용.
    long long handle = spawn(worker, (const &static int)&g_value);
    join(handle);
    return 0;
}
```

### 스레드 함수 시그니처 {#thread-function-signature}

스레드 함수는 널 가능 참조 하나를 받고 하나를 반환합니다 — `?&T name(?&T arg)` — 이는 ABI 수준에서 POSIX의 `void*(void*)` 형태와 정확히 같으면서 인자의 타입이 검사됩니다:

```c
?&int my_thread(?&int arg) {
    // ... do work ...
    return null;
}
```

인자가 필요 없는 스레드에는 `null`을 넘기십시오.

### 스레드 Join하기 {#joining-a-thread}

`join(h)`은 핸들 `h`로 식별되는 스레드가 완료될 때까지 호출한 스레드를 블로킹합니다. `pthread_join`에 직접 매핑됩니다.

```c
long long h1 = spawn(worker1, arg1);
long long h2 = spawn(worker2, arg2);
join(h1);                      // worker1 대기
join(h2);                      // worker2 대기
```

## Lowering {#lowering}

`spawn`과 `join`은 pthread 호출로 직접 lowering됩니다.

| SafeC | C / POSIX |
|-------|-----------|
| `spawn(fn, arg)` | `pthread_create(&tid, NULL, fn, arg)` |
| `join(handle)` | `pthread_join((pthread_t)handle, NULL)` |

`spawn`이 반환하는 핸들은 `long long`으로 캐스팅된 원시 `pthread_t` 값입니다.

## 동시성에서의 리전 안전성 {#region-safety-in-concurrency}

리전 시스템은 동시성 코드에 안전성 보장을 제공합니다.

### Spawn은 정적 함수 참조가 필요합니다 {#spawn-requires-static-function-reference}

`spawn`에 전달되는 함수는 `&static` 함수 참조여야 합니다 -- 스코프를 벗어날 수 있는 지역 함수 포인터가 아니라 이름이 붙은 함수여야 합니다.

```c
?&int worker(?&int arg) { return null; }

int main() {
    long long h = spawn(worker, null);  // OK: worker is static
    join(h);
    return 0;
}
```

### 스레드가 받을 수 있는 것 {#mutable-non-static-references-rejected}

`spawn`된 스레드는 어떤 스코프에서도 join되지 않으므로, 인자는 `null`이거나 정적 데이터를 가리켜야 합니다 — Sync인 `T`에 대한 `const &static T`, 또는 `T`가 자기 상태를 동기화할 때(`Mutex`, 원자 연산)에만 가변 `&static T`. 스택, 아레나, 힙 데이터에 대한 참조는 거부되며, raw 포인터는 `unsafe` 안에서만 허용됩니다.

```c
?&int bad_worker(?&int arg) {
    match (arg) {
        case some(p): { *p = 99; }   // would race with the spawning thread
        case null:    { }
    }
    return null;
}

static int g_counter = 0;

void example() {
    int local = 42;
    // spawn(bad_worker, &local);      // ERROR: '&stack int' refers to data the thread may outlive
    // spawn(bad_worker, &g_counter);  // ERROR: 'int' is plain data: concurrent writes race
}
```

스레드에 지역 변수를 주려면 [`spawn_scoped`](#scoped-spawn)를, 가변 데이터를 공유하려면 `Mutex<T>`나 원자 연산을 사용하십시오. [스레드 안전성](/ko/reference/threads)을 참고하십시오.

## 링킹 {#linking}

`spawn`이나 `join`을 사용하는 프로그램은 Linux에서 `-lpthread`로 링크되어야 합니다. `safeguard`는 이를 자동으로 추가합니다(코드의 `pthread_create` 호출이 그 라이브러리를 지정하는 `std/thread.sc`를 가져옵니다. [표준 라이브러리 모듈](/ko/advanced/safeguard#standard-library-linking) 참고). 직접 링크할 때는 다음과 같습니다.

```bash
./build/safec program.sc --emit-llvm -o program.ll
/usr/bin/clang program.ll -lpthread -o program
```

## 원자적 내장 함수 {#atomic-built-ins}

스레드 간의 락 프리(lock-free) 동기화를 위해 SafeC는 원자적 연산을 제공합니다.

```c
atomic int counter = 0;

?&int increment(?&int unused) {
    for (int i = 0; i < 1000; i = i + 1) {
        atomic_fetch_add(&counter, 1);
    }
    return null;
}
```

사용 가능한 원자적 연산:

| 연산 | 설명 |
|-----------|-------------|
| `atomic_load(ptr)` | 값을 원자적으로 로드 |
| `atomic_store(ptr, val)` | 값을 원자적으로 저장 |
| `atomic_fetch_add(ptr, val)` | 더하고 이전 값을 반환 |
| `atomic_fetch_sub(ptr, val)` | 빼고 이전 값을 반환 |
| `atomic_exchange(ptr, val)` | 교환하고 이전 값을 반환 |
| `atomic_cas(ptr, expected, desired)` | 비교 후 교환 (compare-and-swap) |
| `atomic_fence()` | 메모리 펜스 (순차적 일관성) |

전체 원자적 연산 목록은 [베어메탈 프로그래밍](/ko/reference/baremetal)을 참고하십시오.

## 표준 라이브러리 Thread 모듈 {#standard-library-thread-module}

SafeC 표준 라이브러리는 `thread` 모듈에서 더 고수준의 동시성 기본 요소를 제공합니다.

- **뮤텍스**: `mutex_create`, `mutex_lock`, `mutex_unlock`, `mutex_destroy`
- **조건 변수**: `cond_create`, `cond_wait`, `cond_signal`, `cond_broadcast`, `cond_timedwait_ms`
- **읽기-쓰기 락**: `rwlock_create`, `rwlock_read_lock`, `rwlock_write_lock`, `rwlock_unlock`
- **스레드 유틸리티**: `thread_yield`, `thread_sleep_ms`, `thread_self`

이들은 POSIX 스레드(또는 `-D__WINDOWS__`를 사용하는 Win32 스레드) 위에 얹힌 크로스 플랫폼 래퍼입니다. 타입 없는 핸들을 주고받으므로, 이들을 통해 스레드가 무엇을 공유하는지는 아무것도 검사하지 않습니다. 모듈의 타입 있는 계층은 이들 위에 만들어졌으며 검사됩니다.

- **`thread_spawn(f, arg)`**는 `arg`(Send 값)를 새 스레드로 move하고 `SpawnedThread<T, R>`를 반환합니다. `f`의 결과는 `join()`으로 얻습니다.
- **`Mutex<T>`**는 `lock()`이 반환하는 `MutexGuard<T>`를 통해서만 접근할 수 있는 `T`를 소유합니다. 가드는 drop될 때 잠금을 풀며, `guard.get()`이 준 참조는 가드보다 오래 살 수 없습니다.
- **`RwLock<T>`**: 여러 `read()` 가드 또는 하나의 `write()` 가드.

```c
#include <std/thread.h>
static struct Mutex<int> g_total;

void add(int k) {
    struct MutexGuard<int> g = g_total.lock();
    *g.get() = *g.get() + k;
    g.drop();                        // 잠금 해제; 잊으면 컴파일 오류
}
```

[스레드 안전성](/ko/reference/threads#mutex)을 참고하십시오.

## 스코프 기반 Spawn {#scoped-spawn}

`spawn_scoped(fn, arg)`는 `spawn`과 같은 시그니처를 가진 컴파일러 내장 함수로,
시작한 함수가 반환하기 전에 join되는 스레드를 위한 것입니다. 컴파일러는 그
함수의 모든 출구 경로에 join을 생성하며(호출 위치에 작성한 `defer`와 같은 순서),
명시적인 `join(h)`가 더 일찍 올 수도 있습니다. 그래서 인자가 **지역 변수**를
가리킬 수 있습니다.

- 가변 참조는 값을 스레드에 넘겨줍니다(타입은 Send여야 함). 시작한 스레드는
  `join(h)`까지 그 값을 건드릴 수 없습니다;
- const 참조는 읽기용으로 공유합니다(타입은 Sync여야 함);
- 스레드가 join될 때 지역 변수가 아직 존재해야 합니다.

```c
struct Counter { int n; };
?&Counter work(?&Counter c) {
    match (c) { case some(p): { p.n = p.n + 1; } case null: { } }
    return null;
}

void example() {
    struct Counter c; c.n = 0;
    long long h = spawn_scoped(work, &c);
    // c.n = 5;        // ERROR: 'c' is lent to the thread started here by spawn_scoped
    join(h);
    c.n = c.n + 1;     // OK: 스레드가 끝남
}
```

## 채널 {#channels}

채널은 (`spawn`/`join`처럼 `#include`가 필요 없는) 또 다른 컴파일러 내장
기능으로, 공유 가변 참조 없이 스레드 간에 값을 전달하기 위한 유계
(bounded) MPMC 큐를 제공합니다.

| 내장 함수 | 시그니처 | 설명 |
|----------|-----------|-------------|
| `chan_create(capacity)` | `(int) -> ?&void` | 주어진 용량으로 채널을 생성하고, 불투명한 핸들을 반환 |
| `chan_send(channel, value)` | `(?&void, &T) -> bool` | `*value`를 전송(타입은 Send여야 하며, [Drop](/ko/reference/ownership) 값은 수신자에게 move됨); 성공 시 `true` 반환 |
| `chan_recv(channel, out)` | `(?&void, &T) -> bool` | `*out`으로 값을 수신; 채널이 닫혀 있고 비어 있으면 `false` 반환 |
| `chan_close(channel)` | `(?&void) -> void` | 채널을 닫음; 대기 중이거나 이후의 `chan_recv` 호출은 남은 값을 모두 소진한 후 `false`를 반환 |

핸들은 불투명하고 값은 참조로 주고받으므로 `unsafe`가 필요 없습니다. 여기서
채널 자체는 정적 저장소를 통해 공유되는데, 생성된 스레드가 쓰는 모든 값이
스레드보다 오래 살아야 하는 것과 같은 이유입니다.

```c
extern int printf(const char* fmt, ...);
static ?&void g_ch = null;

?&int producer(?&int unused) {
    int value = 7;
    chan_send(g_ch, &value);
    chan_close(g_ch);
    return null;
}

int main() {
    g_ch = chan_create(4);
    long long h = spawn_scoped(producer, null);
    int received = 0;
    bool ok = chan_recv(g_ch, &received);
    join(h);
    printf("%d %d\n", (int)ok, received);
    return 0;
}
```

## 예제: 병렬 계산 {#example-parallel-computation}

```c
#include <std/collections/vec.h>
extern int printf(const char* fmt, ...);

struct WorkItem {
    const &Vec<int> data;       // 두 워커가 읽기용으로 공유
    unsigned long start;
    unsigned long end;
    long long result;
};

?&WorkItem sum_range(?&WorkItem arg) {
    match (arg) {
        case some(w): {
            long long sum = 0LL;
            for (unsigned long i = w.start; i < w.end; i = i + 1UL) {
                sum = sum + (long long)*w.data.at(i);   // 경계 검사됨
            }
            w.result = sum;
        }
        case null: { }
    }
    return null;
}

int main() {
    unsigned long n = 10000UL;
    struct Vec<int> data = Vec::new();
    for (unsigned long i = 0UL; i < n; i = i + 1UL) data.push((int)i);

    struct WorkItem w1; w1.data = &data; w1.start = 0UL;     w1.end = n / 2UL; w1.result = 0LL;
    struct WorkItem w2; w2.data = &data; w2.start = n / 2UL; w2.end = n;       w2.result = 0LL;

    // 각 워커는 join될 때까지 자기 WorkItem을 받고, 둘 다 'data'를 읽음.
    long long h1 = spawn_scoped(sum_range, &w1);
    long long h2 = spawn_scoped(sum_range, &w2);
    join(h1);
    join(h2);

    printf("Total: %lld\n", w1.result + w2.result);
    data.drop();
    return 0;
}
```
