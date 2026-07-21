# mem -- 메모리

`mem` 모듈은 힙 할당과 메모리 연산을 감싼다. `alloc`/`alloc_zeroed`/
`dealloc`/`realloc_buf`는 특정한 런타임 얼로케이터 오용 버그들에 대해
구조적으로 안전하지만(아래 [할당 안전성](#allocation-safety) 참고),
모든 접근에 대한 일반적인 경계 검증을 수행하지는 않는다 — 반환된
포인터를 통해 실제로 읽거나 쓰려면, SafeC의 다른 원시 포인터와
마찬가지로 여전히 `unsafe {}`가 필요하다.

```c
#include "mem.h"
```

## 할당 {#allocation}

### alloc {#alloc}

```c
void* alloc(unsigned long size);
```

초기화되지 않은 `size`바이트의 힙 메모리를 할당한다. 할당된 블록에
대한 포인터를 반환하며, 실패 시 NULL을 반환한다.

### alloc_zeroed {#alloc_zeroed}

```c
void* alloc_zeroed(unsigned long size);
```

0으로 초기화된 `size`바이트의 힙 메모리를 할당한다. 할당된 블록에
대한 포인터를 반환하며, 실패 시 NULL을 반환한다.

### dealloc {#dealloc}

```c
void dealloc(void* ptr);
```

이전에 할당된 블록을 해제한다. NULL을 전달하는 것은 아무 동작도 하지
않는다. 같은 포인터에 대해 이 함수를 두 번 호출하거나, `alloc`/
`alloc_zeroed`가 반환한 적 없는 포인터에 대해 호출하면 힙을 손상시키는
대신 진단 메시지와 함께 중단(abort)된다 — [할당 안전성](#allocation-safety)
참고.

### realloc_buf {#realloc_buf}

```c
void* realloc_buf(void* ptr, unsigned long new_size);
```

이전에 할당된 블록의 크기를 `new_size`바이트로 조정한다. 내용은 기존
크기와 새 크기 중 작은 쪽까지 보존된다. (이동했을 수도 있는) 포인터를
반환하며, 실패 시 NULL을 반환한다(실패 시 기존 블록은 해제되지 *않는다*).
`ptr == NULL`이면 `alloc(new_size)`처럼 동작한다.

### checked_mul_size {#checked_mul_size}

```c
unsigned long checked_mul_size(unsigned long a, unsigned long b);
```

두 개의 할당 크기 인자(보통 원소 개수와 원소 크기)를 곱하며, 곱셈
결과가 `unsigned long`을 오버플로하면 조용히 값을 감싸는(wrap) 대신
진단 메시지와 함께 중단(abort)된다 — 전형적인 "`count * elem_size`"
정수 오버플로로 인한 과소 할당 버그를 막는다. 표준 라이브러리에서
호출자가 제어하는 개수를 원소 크기와 곱하는 모든 할당 지점(`Vec`,
`HashMap`의 버킷, `Queue`, `MpscQueue`, `LFQueue` 등)은 단순한 `*` 대신
이 함수를 거치도록 되어 있다 — `alloc`/`alloc_zeroed`/`realloc_buf`에
전달되는 크기가 작고 컴파일 타임에 증명 가능한 상수가 아니라 두 런타임
값의 곱인 경우 어디에서나 동일하게 사용한다.

## 할당 안전성 {#allocation-safety}

`alloc`/`alloc_zeroed`는 모든 할당 앞에 살아있는지 해제되었는지를
태그하는 작은 16바이트 헤더를 붙인다. `dealloc`/`realloc_buf`는
동작하기 전에 이 태그를 확인한다:

- **이중 해제(double-free)** — 같은 포인터에 대해 `dealloc()`을 두 번
  호출하면 얼로케이터의 프리 리스트를 손상시키는 대신 진단 메시지와
  함께 중단된다.
- **얼로케이터 불일치** — `alloc`/`alloc_zeroed`가 반환한 적 없는
  포인터(스택 주소, `std/alloc/pool.h` 같은 *다른* 얼로케이터에서 온
  포인터, 손상된 메모리 등)에 대해 `dealloc()`을 호출하면 쓰레기를
  해제하는 대신 중단된다.
- **NULL** — `dealloc(NULL)`은 `free(NULL)`과 마찬가지로 안전하게 아무
  동작도 하지 않는다.
- **해제 후 사용(재해제 형태)** — 해제된 포인터가 `dealloc`/
  `realloc_buf`에 두 번째로 다시 전달되면 이중 해제와 동일한 방식으로
  잡힌다.

이를 뒷받침하는 것은 64개 항목의 격리 구역(quarantine)이다: 해제된
블록은 이후 약 64번의 `dealloc()` 호출 동안 실제로 시스템 얼로케이터에
반환되지 않으므로, 이 플랫폼 자체의 얼로케이터가 해제된 블록의 첫
바이트를 거의 즉시 덮어쓰더라도 이중 해제 검사는 신뢰할 수 있게
유지된다(태그 하나만으로는 OS 얼로케이터가 그 메모리를 재사용하기
전에 발생하는, 같은 포인터에 대한 이중 해제만 잡을 수 있다). 사이에
64개 *이상*의 다른 `dealloc()` 호출이 끼어든 이중 해제는 더 약한 "살아
있는 포인터가 아님" 진단으로 대체된다 — 이는 알려진, 문서화된 한계이지
조용한 허점이 아니다.

::: warning 다루지 않는 것
이 기능은 `dealloc()`/`realloc_buf()`를 다시 호출하지 않고 오래된
포인터를 통해 그저 읽거나 쓰기만 하는 해제 후 사용은 잡아내지 못한다
— 이를 위해서는 16바이트 헤더로 할 수 있는 것을 훨씬 뛰어넘는,
섀도 메모리 계측(ASan 방식)이 필요하다. 또한 이 기능은 경계 검사를
대체하지 않는다: `alloc(n)` 다음에 `buf[n]`에 대한 범위 밖 쓰기를
하는 것은 여전히 정의되지 않은 동작이며, 이는 이 기능으로도 (원시
포인터이지 배열/슬라이스가 아니므로) 어떤 컴파일 타임 검사로도 잡히지
않는다.
:::

`std/alloc/pool.h`/`slab.h`/`tlsf.h`의 얼로케이터들은 자신들의 기존
블록별 메타데이터를 사용해 동등한 이중 해제/불일치 포인터 검사를
얻는다(격리 구역은 필요 없다 — 이들의 해제된 블록은 시스템 얼로케이터에
반환되는 대신 얼로케이터 자체의 백킹 버퍼 안에 그대로 남아 있으므로,
`alloc`/`dealloc`의 태그처럼 외부 요인에 의해 훼손될 수 없다).

## 메모리 연산 {#memory-operations}

### safe_memcpy {#safe_memcpy}

```c
void safe_memcpy(void* dst, const void* src, unsigned long n);
```

`src`에서 `dst`로 `n`바이트를 복사한다. 원본과 대상 영역은 **겹치면
안 된다**. 겹치는 영역에는 `safe_memmove`를 사용한다.

### safe_memmove {#safe_memmove}

```c
void safe_memmove(void* dst, const void* src, unsigned long n);
```

`src`에서 `dst`로 `n`바이트를 복사한다. 겹치는 영역에도 안전하다.

### safe_memset {#safe_memset}

```c
void safe_memset(void* ptr, int val, unsigned long n);
```

`ptr`부터 `n`바이트를 값 `val`(`unsigned char`로 해석됨)로 설정한다.

### safe_memcmp {#safe_memcmp}

```c
int safe_memcmp(const void* a, const void* b, unsigned long n);
```

`a`와 `b`의 `n`바이트 메모리를 비교한다. 반환값:
- `a`가 `b`보다 작으면 `< 0`
- 같으면 `0`
- `a`가 `b`보다 크면 `> 0`

## 예제 {#example}

```c
#include "mem.h"
#include "io.h"

int main() {
    // 정수 10개를 위한 버퍼 할당
    int* buf;
    unsafe { buf = (int*)alloc(10UL * sizeof(int)); }
    unsafe { safe_memset((void*)buf, 0, 10UL * sizeof(int)); }

    unsafe {
        buf[0] = 42;
        buf[1] = 99;
    }

    // 새 버퍼로 복제
    int* copy;
    unsafe { copy = (int*)alloc(10UL * sizeof(int)); }
    unsafe { safe_memcpy((void*)copy, (const void*)buf, 10UL * sizeof(int)); }

    unsafe { print_int((long long)copy[0]); }  // 42
    println("");

    unsafe {
        dealloc((void*)buf);
        dealloc((void*)copy);
    }
    return 0;
}
```

::: tip
`alloc`/`dealloc`/`safe_memcpy`/`safe_memset`은 모두 원시 포인터
(`void*`/`int*`)를 받거나 반환하며, 원시 포인터를 통해 읽거나 쓰는
것은 이 모듈에 국한되지 않고 SafeC에서 항상 `unsafe {}` 블록을
요구한다. `unsafe`가 무엇을 면제해 주고 무엇을 면제해 주지 않는지는
[안전성 모델](/ko/advanced/safety-model)을 참고한다.
:::

### 이중 해제는 잡히지, 조용히 넘어가지 않는다 {#double-free-is-caught-not-silent}

```c
#include "mem.h"
#include "io.h"

int main() {
    void* p;
    unsafe { p = alloc(64UL); }
    unsafe { dealloc(p); }
    println("first dealloc OK");
    unsafe { dealloc(p); }   // 중단: "dealloc() called twice on the same pointer"
    println("never reached");
    return 0;
}
```
