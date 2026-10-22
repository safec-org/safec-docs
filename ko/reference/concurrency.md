# 동시성

SafeC는 저수준의, C와 호환되는 동시성 기본 요소를 제공합니다. 스레드는 `spawn`으로 생성되고 `join`으로 join되며, POSIX pthread에 직접 매핑됩니다. 클로저나 런타임 스케줄러는 존재하지 않습니다 -- 동시성은 명시적이고 결정론적입니다.

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
    long long handle = spawn(worker, &g_value);   // &static int: allowed
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

### 가변인 비정적 참조는 거부됩니다 {#mutable-non-static-references-rejected}

컴파일러는 데이터 레이스를 방지하기 위해 비정적 데이터에 대한 가변 참조를 spawn 인자로 전달하는 것을 거부합니다.

```c
?&int bad_worker(?&int arg) {
    match (arg) {
        case some(p): { *p = 99; }   // would race with the spawning thread
        case null:    { }
    }
    return null;
}

void example() {
    int local = 42;
    // spawn(bad_worker, &local);  // ERROR: mutable non-static ref in spawn
}
```

`&static` 참조는 허용됩니다: 정적 데이터는 모든 스레드보다 오래 살며, 접근 조율은 프로그래머의 몫입니다(원자적 연산이나 `thread` 모듈의 락).

스레드 간에 가변 데이터를 공유하려면, 원자적 연산이나 표준 라이브러리 thread 모듈을 이용한 명시적 동기화를 사용하십시오.

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

이들은 POSIX 스레드(또는 `-D__WINDOWS__`를 사용하는 Win32 스레드) 위에 얹힌 크로스 플랫폼 래퍼입니다.

## 스코프 기반 Spawn {#scoped-spawn}

`spawn_scoped(fn, arg)`는 `spawn`과 구별되는 컴파일러 내장 함수로서 -- 동일한
시그니처와 가변 비정적 참조 거부 규칙을 가지지만, join이 감싸는 스코프가
종료되기 전에 반드시 이루어지는 spawn을 위한 것입니다. `spawn`과 정확히
똑같이 스레드 핸들을 반환하며, 여전히 `join`으로 명시적으로 join됩니다.

```c
?&int worker(?&int arg) {
    return null;
}

void example() {
    long long h = spawn_scoped(worker, null);
    join(h);
}
```

## 채널 {#channels}

채널은 (`spawn`/`join`처럼 `#include`가 필요 없는) 또 다른 컴파일러 내장
기능으로, 공유 가변 참조 없이 스레드 간에 값을 전달하기 위한 유계
(bounded) MPMC 큐를 제공합니다.

| 내장 함수 | 시그니처 | 설명 |
|----------|-----------|-------------|
| `chan_create(capacity)` | `(int) -> ?&void` | 주어진 용량으로 채널을 생성하고, 불투명한 핸들을 반환 |
| `chan_send(channel, value)` | `(?&void, &T) -> bool` | `*value`의 복사본을 전송; 성공 시 `true` 반환 |
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
#include <std/collections/slice.h>
extern int printf(const char* fmt, ...);

struct WorkItem {
    struct Slice<int> data;
    unsigned long start;
    unsigned long end;
    long long result;
};

// Shared with the workers, so it lives in static storage: spawn only
// accepts references that can't dangle while the thread runs.
static struct WorkItem w1;
static struct WorkItem w2;

?&WorkItem sum_range(?&WorkItem arg) {
    match (arg) {
        case some(w): {
            long long sum = 0LL;
            for (unsigned long i = w.start; i < w.end; i = i + 1UL) {
                sum = sum + (long long)*w.data.at(i);   // bounds-checked
            }
            w.result = sum;
        }
        case null: { }
    }
    return null;
}

int main() {
    unsigned long n = 10000UL;
    struct Slice<int> data = std::Slice::alloc(n);      // heap, runtime-sized
    for (unsigned long i = 0UL; i < n; i = i + 1UL) data.set(i, (int)i);

    w1.data = data; w1.start = 0UL;    w1.end = n / 2UL; w1.result = 0LL;
    w2.data = data; w2.start = n / 2UL; w2.end = n;      w2.result = 0LL;

    long long h1 = spawn(sum_range, &w1);
    long long h2 = spawn(sum_range, &w2);
    join(h1);
    join(h2);

    printf("Total: %lld\n", w1.result + w2.result);
    data.free();
    return 0;
}
```
