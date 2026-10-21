# atomic -- 아토믹

`atomic` 모듈은 C11 `<stdatomic.h>`를 감싸, 공유 정수에 대한 락 프리
아토믹 연산을 제공합니다. 모든 연산은 순차 일관성(sequential
consistency) 순서를 사용합니다.

```c
#include <std/atomic.h>
```

## atomic_int (32비트 부호 있음) {#atomic_int-32-bit-signed}

### 로드 / 스토어 {#load-store}

```c
int  atomic_load_int(const &int addr);
void atomic_store_int(&int addr, int val);
```

`*addr`를 원자적으로 읽거나 씁니다.

### 페치 후 변경 (Fetch-and-Modify) {#fetch-and-modify}

```c
int atomic_fetch_add_int(&int addr, int delta);
int atomic_fetch_sub_int(&int addr, int delta);
int atomic_fetch_and_int(&int addr, int mask);
int atomic_fetch_or_int(&int addr, int mask);
int atomic_fetch_xor_int(&int addr, int mask);
```

각 함수는 연산을 원자적으로 적용하고 **이전** 값을 반환합니다.

### 교환 (Exchange) {#exchange}

```c
int atomic_exchange_int(&int addr, int val);
```

`*addr`를 `val`로 원자적으로 교체하고 이전 값을 반환합니다.

### Compare-and-Swap (CAS) {#compare-and-swap-cas}

```c
int atomic_cas_int(&int addr, &int expected, int desired);
```

`*addr == *expected`이면 `desired`를 `*addr`에 원자적으로 저장하고 1(성공)을
반환합니다. 그렇지 않으면 `*expected`를 `*addr`의 현재 값으로 갱신하고
0(실패)을 반환합니다.

## atomic_long (64비트 부호 있음) {#atomic_long-64-bit-signed}

```c
long long  atomic_load_ll(const &long long addr);
void       atomic_store_ll(&long long addr, long long val);
long long  atomic_fetch_add_ll(&long long addr, long long delta);
long long  atomic_fetch_sub_ll(&long long addr, long long delta);
long long  atomic_exchange_ll(&long long addr, long long val);
int        atomic_cas_ll(&long long addr, &long long expected, long long desired);
```

`_int` 변형과 동일한 의미를 가지며, `long long`(64비트) 값에 대해
동작합니다.

## 메모리 펜스 {#memory-fence}

```c
void atomic_thread_fence();
```

완전한 순차 일관성 메모리 펜스를 발행합니다. 이는 펜스 이전의 모든 메모리
연산이, 펜스 이후의 어떤 메모리 연산보다 먼저 모든 스레드에 보이도록
보장합니다.

## 예제: 락 프리 카운터 {#example-lock-free-counter}

```c
#include <std/atomic.h>
#include <std/thread.h>
#include <std/io.h>

static int counter = 0;

static ?&int increment(?&int unused) {
    int i = 0;
    while (i < 10000) {
        std::atomic_fetch_add_int(&counter, 1);
        i = i + 1;
    }
    return null;
}

int main() {
    unsigned long long t1 = 0ULL;
    unsigned long long t2 = 0ULL;
    std::thread_create(&t1, increment, null);
    std::thread_create(&t2, increment, null);

    std::thread_join(t1);
    std::thread_join(t2);

    std::print("counter = ");
    std::println_int(std::atomic_load_int(&counter));  // 20000
    return 0;
}
```

## 예제: Compare-and-Swap 루프 {#example-compare-and-swap-loop}

```c
#include <std/atomic.h>
#include <std/io.h>

int main() {
    int val = 10;

    // Try to change 10 -> 20
    int expected = 10;
    int ok = atomic_cas_int(&val, &expected, 20);
    print("CAS succeeded: ");
    println_int(ok);          // 1
    print("val = ");
    println_int(val);         // 20

    // Try again with stale expected value
    expected = 10;
    ok = atomic_cas_int(&val, &expected, 30);
    print("CAS succeeded: ");
    println_int(ok);          // 0
    print("expected updated to: ");
    println_int(expected);    // 20 (current value)

    return 0;
}
```

## 안전성 참고 사항 {#safety-note}

아토믹 연산 자체는 메모리 안전합니다. 다만 C FFI로부터 받은 원시
포인터에 결과를 저장하는 것은 SafeC의 FFI 정책에 따라 호출부에서
`unsafe {}`가 필요합니다.
