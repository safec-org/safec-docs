# thread -- 스레딩

SafeC는 두 개의 계층으로 이루어진 스레딩을 제공합니다: **`spawn`/`join`/`spawn_scoped` 언어 키워드**(컴파일러 내장 기능으로, `#include`가 필요 없습니다)와, 뮤텍스/조건 변수/읽기-쓰기 락과 함께 OS 스레드를 직접 제어할 수 있는 아래의 **`std::thread_*` 라이브러리 API**(`thread.h`)입니다. 두 계층 모두 Linux/macOS에서는 POSIX pthreads, Windows에서는 Win32 스레드를 기반으로 동작합니다. 프리스탠딩/베어메탈 타깃(또는 컴파일러가 인식하지 못하는 모든 타깃)에서는 언어 키워드가 대신 문서화된 두 개의 심볼 훅으로 컴파일되며, `std/sync/bare_spawn.sc`가 이를 협조적으로 구현합니다. 해당 백엔드와, 베어메탈에 친화적인 `std::thread_create`/`join`의 사촌 격 함수들에 대해서는 [동기화](/ko/stdlib/sync)를 참고하십시오.

## 언어 레벨 `spawn` / `join` / `spawn_scoped` {#language-level-spawn-join-spawn_scoped}

```c
long long spawn(fn, arg);          // fn: &static 함수 참조; 스레드 핸들을 반환
void      join(handle);
long long spawn_scoped(fn, arg);   // spawn과 같지만, 컴파일러가 스코프 종료 전 join을 보장
```

`spawn`의 첫 번째 인자는 함수에 대한 `&static` 참조여야 합니다(일반적인 최상위 함수 이름은 암묵적으로 이 형태로 붕괴합니다). 두 번째 인자는 스레드의 인자로 그대로 전달됩니다. 스레드 함수는 nullable 참조를 받고 반환합니다(`?&T f(?&T arg)`). 인자는 리전 격리를 지켜야 합니다: 생성된 스레드가 참조 대상보다 오래 살거나 그 위에서 경쟁할 수 있으므로, 컴파일러는 `&static`이 아닌 가변 참조를 거부합니다. `spawn`과 `spawn_scoped` 모두 `join`에 전달할 `long long` 핸들을 반환합니다.

```c
extern int printf(const char *fmt, ...);

static ?&int worker0(?&int unused) { unsafe { printf("thread 0\n"); } return null; }
static ?&int worker1(?&int unused) { unsafe { printf("thread 1\n"); } return null; }

int main() {
    long long h0 = spawn(worker0, null);
    long long h1 = spawn(worker1, null);

    join(h0);
    join(h1);

    printf("all threads done\n");
    return 0;
}
```

### 리전 격리 {#region-isolation}

`spawn`의 인자는 **가변이면서 `&static`이 아닌** 참조일 수 없습니다 — 그런 참조를 전달하면 컴파일 오류("region isolation violation")가 발생합니다. 스폰된 스레드가 해당 참조가 가리키는 리전보다 더 오래 살아남을 수 있기 때문입니다. 대신 `&static` 참조, 불변 참조, 또는 (인덱스나 직접 관리하는 힙 포인터 같은) 일반 값을 전달합니다.

### 백엔드 선택 {#backend-selection}

컴파일러는 빌드 타깃에 따라 `spawn`/`join`/`spawn_scoped`의 백엔드를 선택합니다.

| 타깃 | 백엔드 |
|--------|---------|
| `--freestanding` | 훅(아래 참고) — OS 스레드 API를 전혀 가정하지 않음 |
| Windows를 지정하는 명시적 `--target` | Win32 (`CreateThread`/`WaitForSingleObject`) |
| Linux/macOS/*BSD/Solaris를 지정하는 명시적 `--target` | Pthreads |
| 그 외를 지정하는 명시적 `--target` (예: `wasm32-*`, `riscv64-unknown-elf`) | 훅 |
| `--target` 미지정 | Pthreads (호스트 기본값) |

**훅** 백엔드는 `spawn`/`join`을 두 개의 고정된 extern 심볼 — `__safec_thread_create(func, arg) -> i64`와 `__safec_thread_join(i64) -> void` — 에 대한 호출로 컴파일합니다. 이는 의도적인 확장 지점으로, 정확히 이 두 심볼을 제공하는 런타임이라면 컴파일러 변경 없이 `spawn`/`join`을 지원할 수 있습니다. `std/sync/bare_spawn.sc`는 스폰된 함수를 `std::TaskScheduler` 위에서 협조적으로 실행하는 참조 구현입니다([동기화](/ko/stdlib/sync) 참고). 벤더 RTOS 셰임(shim)이 동일한 두 심볼을 대신 정의할 수도 있습니다.

## 직접 OS 스레드 API (`thread.h`) {#direct-os-thread-api-threadh}

이 페이지의 나머지 부분 — `thread_create`/`mutex_*`/`cond_*`/`rwlock_*` — 는 위의 `spawn`/`join` 키워드 대신 OS 스레드를 직접 제어하고 싶을 때 사용하는, 별도로 명시적으로 호출하는 API입니다. 두 계층 모두 호스티드 타깃에서는 pthread/Win32 기반으로 동작합니다. `thread_create`의 핸들은 Win32에서 `spawn`의 핸들과 ABI 호환이지만, 그 외에는 두 API가 서로 독립적입니다(이 API는 훅 백엔드 계약의 일부가 아닙니다).

::: tip 검사되는 스레드
`std/thread.h`에는 컴파일러의 [Send/Sync 검사](/ko/reference/threads)가 적용되는 타입 있는 계층도 있습니다: `thread_spawn(f, arg)`는 인자(`T: Send`)를 새 스레드로 옮기고 `SpawnedThread<T, R>`를 반환하며, 그 `join()`이 `f`의 결과를 돌려줍니다. `Mutex<T>` / `RwLock<T>`는 보호하는 값을 소유합니다. 아래의 핸들 기반 함수는 스레드가 무엇을 공유하는지 전혀 검사하지 않으므로 타입 있는 계층을 쓰세요.
:::

```c
#include <std/thread.h>
```

## 핸들 표현 {#handle-representation}

모든 핸들(스레드 ID, 뮤텍스, 조건 변수, 읽기-쓰기 락)은 `unsigned long long`으로 저장됩니다.

- **POSIX**: `pthread_t` 값(64비트 환경에서 8바이트), 또는 힙에 할당된 pthread 객체를 가리키는 포인터
- **Win32**: `HANDLE`(커널 객체 포인터, x64에서 8바이트), 또는 힙에 할당된 Windows 프리미티브를 가리키는 포인터

## 스레드 {#thread}

```c
generic<T>
int  thread_create(&unsigned long long tid, fn ?&T(?&T) func, ?&T arg);
int  thread_join(unsigned long long tid);
int  thread_detach(unsigned long long tid);
void thread_yield();
void thread_sleep_ms(unsigned long ms);
unsigned long long thread_self();
```

### thread_create {#thread_create}

새 스레드에서 `func(arg)`를 시작하고 그 핸들을 `*tid`에 기록합니다. 성공 시 0을 반환합니다. 스레드 함수는 자신의 컨텍스트 타입에 대한 nullable 참조를 받고 반환하며 — `static ?&Job work(?&Job job)` — `T`는 이로부터 추론되므로, 잘못된 컨텍스트 타입을 넘기면 컴파일 오류입니다. 스레드에 인자가 필요 없으면 `null`을 넘깁니다. 반환값은 버려집니다(`thread_join`은 기다리기만 합니다).

그 아래의 타입 없는 OS 진입점(POSIX `void*(*)(void*)`, Win32 `LPTHREAD_START_ROUTINE`)은 `thread_create_raw_`입니다. 기계 수준에서 `?&T`는 평범한 포인터이므로 ABI가 동일합니다.

### thread_join {#thread_join}

스레드 `tid`가 종료될 때까지 블록합니다. 성공 시 0을 반환합니다.

### thread_detach {#thread_detach}

스레드 `tid`를 분리(detach)하여 종료 시 리소스가 자동으로 회수되도록 합니다. 성공 시 0을 반환합니다.

### thread_yield {#thread_yield}

현재 스레드의 타임 슬라이스를 다른 실행 가능한 스레드에게 양보합니다.

### thread_sleep_ms {#thread_sleep_ms}

호출한 스레드를 `ms` 밀리초 동안 재웁니다.

### thread_self {#thread_self}

호출한 스레드의 ID를 반환합니다.

## 뮤텍스 {#mutex}

```c
int mutex_init(&unsigned long long m);
int mutex_destroy(&unsigned long long m);
int mutex_lock(&unsigned long long m);
int mutex_trylock(&unsigned long long m);
int mutex_unlock(&unsigned long long m);
```

### mutex_init / mutex_destroy {#mutex_init-mutex_destroy}

뮤텍스를 초기화하거나 파괴합니다. 뮤텍스 핸들은 `*m`에 저장됩니다.

### mutex_lock / mutex_unlock {#mutex_lock-mutex_unlock}

뮤텍스를 획득하거나 해제합니다. `mutex_lock`은 다른 스레드가 뮤텍스를 보유하고 있으면 블록합니다.

### mutex_trylock {#mutex_trylock}

블록하지 않고 뮤텍스 획득을 시도합니다. 락을 획득했으면 0을, 사용 중이면 0이 아닌 값을 반환합니다.

호출 스레드가 이미 보유한 뮤텍스를 다시 잠그면 안 됩니다: Windows(`CRITICAL_SECTION`)에서는 성공하지만, 기본 POSIX 뮤텍스는 `mutex_lock`에서 교착 상태가 되고 `mutex_trylock`에서 사용 중을 보고합니다.

## 조건 변수 {#condition-variable}

```c
int cond_init(&unsigned long long cv);
int cond_destroy(&unsigned long long cv);
int cond_wait(&unsigned long long cv, &unsigned long long m);
int cond_timedwait_ms(&unsigned long long cv, &unsigned long long m, unsigned long ms);
int cond_signal(&unsigned long long cv);
int cond_broadcast(&unsigned long long cv);
```

### cond_wait {#cond_wait}

뮤텍스 `*m`을 원자적으로 해제하고 조건 변수가 시그널될 때까지 블록합니다. 반환하기 전에 뮤텍스를 다시 획득합니다.

### cond_timedwait_ms {#cond_timedwait_ms}

`cond_wait`와 같지만 최대 `ms` 밀리초만 기다립니다. 깨어나면 0, 시간이 다 되면 `THREAD_TIMEDOUT`(1), 그 밖의 오류는 -1을 반환하며, 모든 플랫폼에서 같은 값입니다.

### cond_signal / cond_broadcast {#cond_signal-cond_broadcast}

조건 변수를 기다리는 스레드 중 하나(`signal`) 또는 전체(`broadcast`)를 깨웁니다.

## 읽기-쓰기 락 {#read-write-lock}

```c
int rwlock_init(&unsigned long long rw);
int rwlock_destroy(&unsigned long long rw);
int rwlock_rdlock(&unsigned long long rw);
int rwlock_wrlock(&unsigned long long rw);
int rwlock_tryrdlock(&unsigned long long rw);
int rwlock_trywrlock(&unsigned long long rw);
int rwlock_rdunlock(&unsigned long long rw);
int rwlock_wrunlock(&unsigned long long rw);
```

여러 개의 리더가 동시에 접근하는 것은 허용되지만, 라이터는 배타적으로 접근합니다. 공유 읽기 접근에는 `rdlock`/`rdunlock`을, 배타적 쓰기 접근에는 `wrlock`/`wrunlock`을 사용합니다.

## 예제 {#example}

```c
#include <std/thread.h>
#include <std/io.h>
#include <std/atomic.h>

static int counter = 0;
static unsigned long long mtx = 0ULL;

static ?&int worker(?&int unused) {
    int i = 0;
    while (i < 1000) {
        std::mutex_lock(&mtx);
        counter = counter + 1;
        std::mutex_unlock(&mtx);
        i = i + 1;
    }
    return null;
}

int main() {
    std::mutex_init(&mtx);

    unsigned long long t1 = 0ULL;
    unsigned long long t2 = 0ULL;
    std::thread_create(&t1, worker, null);
    std::thread_create(&t2, worker, null);

    std::thread_join(t1);
    std::thread_join(t2);

    std::print("counter = ");
    std::println_int(counter);  // 2000

    std::mutex_destroy(&mtx);
    return 0;
}
```

## 생산자-소비자 예제 {#producer-consumer-example}

```c
#include <std/thread.h>
#include <std/io.h>

static int buffer = 0;
static int ready  = 0;
static unsigned long long mtx = 0ULL;
static unsigned long long cv = 0ULL;

static ?&int producer(?&int unused) {
    std::mutex_lock(&mtx);
    buffer = 42;
    ready  = 1;
    std::cond_signal(&cv);
    std::mutex_unlock(&mtx);
    return null;
}

static ?&int consumer(?&int unused) {
    std::mutex_lock(&mtx);
    while (ready == 0) {
        std::cond_wait(&cv, &mtx);
    }
    std::print("received: ");
    std::println_int(buffer);  // 42
    std::mutex_unlock(&mtx);
    return null;
}

int main() {
    std::mutex_init(&mtx);
    std::cond_init(&cv);

    unsigned long long t1 = 0ULL;
    unsigned long long t2 = 0ULL;
    std::thread_create(&t1, consumer, null);
    std::thread_create(&t2, producer, null);

    std::thread_join(t1);
    std::thread_join(t2);

    std::cond_destroy(&cv);
    std::mutex_destroy(&mtx);
    return 0;
}
```
