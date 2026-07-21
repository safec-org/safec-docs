# 동시성

SafeC는 저수준의, C와 호환되는 동시성 기본 요소를 제공합니다. 스레드는 `spawn`으로 생성되고 `join`으로 join되며, POSIX pthread에 직접 매핑됩니다. 클로저나 런타임 스케줄러는 존재하지 않습니다 -- 동시성은 명시적이고 결정론적입니다.

## Spawn과 Join {#spawn-and-join}

### 스레드 생성 {#creating-a-thread}

`spawn(fn, arg)`은 주어진 함수를 주어진 인자와 함께 실행하는 새 스레드를 생성합니다. 스레드 핸들(`long long`, 내부적으로는 `pthread_t`)을 반환합니다.

```c
void* worker(void *arg) {
    int *data = (int*)arg;
    unsafe {
        printf("Worker received: %d\n", *data);   // 원시 포인터 역참조는 unsafe 필요
    }
    return (void*)0;
}

int main() {
    int value = 42;
    void *arg;
    unsafe { arg = (void*)&value; }   // &stack 참조를 raw pointer로 캐스팅하려면 unsafe 필요
    long long handle = spawn(worker, arg);
    join(handle);
    return 0;
}
```

### 스레드 함수 시그니처 {#thread-function-signature}

스레드 함수는 반드시 `void*(void*)` 시그니처를 가져야 합니다. 이는 표준 POSIX 스레드 함수 타입입니다.

```c
void* my_thread(void *arg) {
    // ... 작업 수행 ...
    return (void*)0;
}
```

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
void* worker(void *arg) { return (void*)0; }

int main() {
    long long h = spawn(worker, (void*)0);  // OK: worker는 static
    join(h);
    return 0;
}
```

### 가변인 비정적 참조는 거부됩니다 {#mutable-non-static-references-rejected}

컴파일러는 데이터 레이스를 방지하기 위해 비정적 데이터에 대한 가변 참조를 spawn 인자로 전달하는 것을 거부합니다.

```c
void* bad_worker(void *arg) {
    int *p = (int*)arg;
    unsafe { *p = 99; }        // 데이터 레이스!
    return (void*)0;
}

void example() {
    int local = 42;
    // spawn(bad_worker, &local);  // ERROR: spawn에 가변 비정적 참조 사용
}
```

스레드 간에 가변 데이터를 공유하려면, 원자적 연산이나 표준 라이브러리 thread 모듈을 이용한 명시적 동기화를 사용하십시오.

## 링킹 {#linking}

`spawn`이나 `join`을 사용하는 프로그램은 `-lpthread`로 링크되어야 합니다.

```bash
./build/safec program.sc --emit-llvm -o program.ll
/usr/bin/clang program.ll -lpthread -o program
```

## 원자적 내장 함수 {#atomic-built-ins}

스레드 간의 락 프리(lock-free) 동기화를 위해 SafeC는 원자적 연산을 제공합니다.

```c
atomic int counter = 0;

void* increment(void *arg) {
    for (int i = 0; i < 1000; i = i + 1) {
        atomic_fetch_add(&counter, 1);
    }
    return (void*)0;
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
void* worker(void *arg) {
    return (void*)0;
}

void example() {
    void *arg;
    unsafe { arg = (void*)0; }
    long long h = spawn_scoped(worker, arg);
    join(h);
}
```

## 채널 {#channels}

채널은 (`spawn`/`join`처럼 `#include`가 필요 없는) 또 다른 컴파일러 내장
기능으로, 공유 가변 참조 없이 스레드 간에 값을 전달하기 위한 유계
(bounded) MPMC 큐를 제공합니다.

| 내장 함수 | 시그니처 | 설명 |
|----------|-----------|-------------|
| `chan_create(capacity)` | `(int) -> void*` | 주어진 용량으로 채널을 생성하고, 불투명한 핸들을 반환 |
| `chan_send(channel, value_ptr)` | `(void*, void*) -> bool` | `value_ptr`이 가리키는 값을 전송; 성공 시 `true` 반환 |
| `chan_recv(channel, out_ptr)` | `(void*, void*) -> bool` | `*out_ptr`로 값을 수신; 채널이 닫혀 있고 비어 있으면 `false` 반환 |
| `chan_close(channel)` | `(void*) -> void` | 채널을 닫음; 대기 중이거나 이후의 `chan_recv` 호출은 남은 값을 모두 소진한 후 `false`를 반환 |

채널 핸들과 값 포인터가 모두 원시 `void*`이므로, 전송과 수신 모두 관련된
포인터 캐스팅에 `unsafe`가 필요합니다.

```c
void* producer(void *arg) {
    void* ch;
    unsafe { ch = *(void**)arg; }
    int value = 7;
    unsafe { chan_send(ch, (void*)&value); }
    chan_close(ch);
    return (void*)0;
}

void example2() {
    void* ch = chan_create(4);

    void* argp;
    unsafe { argp = (void*)&ch; }
    long long h = spawn_scoped(producer, argp);

    int received = 0;
    void* recvp;
    unsafe { recvp = (void*)&received; }
    bool ok = chan_recv(ch, recvp);

    join(h);
}
```

## 예제: 병렬 계산 {#example-parallel-computation}

```c
#include <stdio.h>
#include <stdlib.h>

struct WorkItem {
    int *data;
    int start;
    int end;
    long long result;
};

void* sum_range(void *arg) {
    struct WorkItem *w = (struct WorkItem*)arg;
    long long sum = 0;
    unsafe {
        // 'w'(원시 포인터)를 통한 모든 접근은 unsafe가 필요합니다: 멤버
        // 접근, 첨자 접근, 그리고 마지막 저장까지 모두 이를 거칩니다.
        for (int i = w->start; i < w->end; i = i + 1) {
            sum = sum + (long long)w->data[i];   // int -> long long 암묵적 확장은 없음
        }
        w->result = sum;
    }
    return (void*)0;
}

int main() {
    int N = 10000;
    int *data = (int*)malloc((unsigned long)N * sizeof(int));  // int -> unsigned long 암묵적 변환 없음
    unsafe {
        for (int i = 0; i < N; i = i + 1) {
            data[i] = i;                          // 원시 포인터 첨자 접근은 unsafe 필요
        }
    }

    struct WorkItem w1 = {data, 0, N / 2, 0};
    struct WorkItem w2 = {data, N / 2, N, 0};

    void *arg1;
    void *arg2;
    unsafe {
        arg1 = (void*)&w1;                        // &stack 참조를 raw pointer로 캐스팅하려면 unsafe 필요
        arg2 = (void*)&w2;
    }

    long long h1 = spawn(sum_range, arg1);
    long long h2 = spawn(sum_range, arg2);

    join(h1);
    join(h2);

    long long total = w1.result + w2.result;
    printf("Total: %lld\n", total);

    free(data);
    return 0;
}
```
