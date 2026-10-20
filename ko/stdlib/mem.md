# mem -- 메모리

`mem` 모듈은 힙 할당과 메모리 연산을 감싼다. `alloc`/`alloc_zeroed`/
`dealloc`/`realloc_buf`는 특정한 런타임 얼로케이터 오용 버그들에 대해
구조적으로 안전하다(아래 [할당 안전성](#allocation-safety) 참고). 이 함수들은
`?&heap T` 참조를 돌려주며, `T`는 대입받는 변수에서 정해진다(타입 없는 바이트에는
`void`). 참조는 인덱싱할 수 없으므로, 여러 원소로 된 블록은
[`Slice`](/ko/stdlib/collections#slice)로 보며 경계 검사와 함께 읽고 쓴다.

타입이 있는 배열에는 보통 `new T[n]`(힙)과 `new<R> T[n]`(아레나)이 더 간단하다 —
[메모리와 리전](/ko/reference/memory#arrays-by-region) 참고.

```c
#include <std/mem.h>
```

## 할당 {#allocation}

### alloc {#alloc}

```c
generic<T> ?&heap T alloc(unsigned long size);
```

초기화되지 않은 `size`바이트의 힙 메모리를 할당한다. 블록에 대한
참조를 반환하며, 실패 시 `null`을 반환한다.

### alloc_zeroed {#alloc_zeroed}

```c
generic<T> ?&heap T alloc_zeroed(unsigned long size);
```

0으로 초기화된 `size`바이트의 힙 메모리를 할당한다. 블록에 대한
참조를 반환하며, 실패 시 `null`을 반환한다.

### dealloc {#dealloc}

```c
generic<T> void dealloc(?&heap T ptr);
```

이전에 할당된 블록을 해제한다. `null`을 전달하는 것은 아무 동작도 하지
않는다. 같은 포인터에 대해 이 함수를 두 번 호출하거나, `alloc`/
`alloc_zeroed`가 반환한 적 없는 포인터에 대해 호출하면 힙을 손상시키는
대신 진단 메시지와 함께 중단(abort)된다 — [할당 안전성](#allocation-safety)
참고.

### realloc_buf {#realloc_buf}

```c
generic<T> ?&heap T realloc_buf(?&heap T ptr, unsigned long new_size);
```

이전에 할당된 블록의 크기를 `new_size`바이트로 조정한다. 내용은 기존
크기와 새 크기 중 작은 쪽까지 보존된다. (이동했을 수도 있는) 블록을
반환하며, 실패 시 `null`을 반환한다(실패 시 기존 블록은 해제되지 *않는다*).
`ptr`이 `null`이면 `alloc(new_size)`처럼 동작한다.

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

### checked_add_size {#checked_add_size}

```c
unsigned long checked_add_size(unsigned long a, unsigned long b);
```

`checked_mul_size`의 덧셈판입니다: 합이 `unsigned long`을 넘치면 감싸지 않고 중단합니다. 여러 필드 크기를 더해 하나로 할당할 때 사용하세요.

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

## 할당 성능 {#allocation-performance}

호스트 타깃에서 `alloc`은 해제된 블록을 크기 클래스별로 나눈 스레드별 캐시를 유지하므로, 해제 직후 비슷한 크기를 할당하면 `malloc`을 호출하지 않고 처리된다:

- **크기 클래스**는 1.5배씩 증가한다 — 16, 24, 32, 48, 64, 96, … 최대 1 MB까지(33개 클래스) — 따라서 요청은 최대 3분의 1까지만 패딩된다. 더 큰 요청은 곧장 `malloc`으로 간다.
- **캐시 깊이**는 슬롯 개수가 아니라 바이트 예산이다: 작은 클래스는 최대 8,192개 블록, 큰 클래스는 그보다 적다(min(8192, 8 MB ÷ 클래스 크기), 최소 8). 모든 클래스는 약 0.96 MB 크기의 스레드별 평면 테이블 하나를 공유한다.
- 캐시에 들어가지 못하는 블록은 위에서 설명한 격리(quarantine)를 거쳐 `malloc`으로 돌아간다.
- 프리스탠딩 타깃에는 캐시가 없다(스레드 로컬 저장소와 테이블 메모리가 필요하기 때문). 모든 요청은 런타임의 `malloc`으로 간다.

각 블록에는 여전히 16바이트 헤더가 붙으며, 이는 아주 작은 객체에서 가장 큰 영향을 준다: 24바이트 노드는 40바이트 요청을 차지한다. 그래서 노드 기반 컨테이너는 노드마다 할당하지 않는다 — `List`와 `BST`는 컨테이너별 [`NodePool`](/ko/stdlib/collections#node-pool)(프리 리스트를 갖춘 청크 단위 범프 할당)에서 노드를 가져오며, 이는 노드마다 C의 `malloc`/`free`를 쓰는 것보다 약 2배 빠르다([벤치마크](/ko/benchmarks#collections-stdcollections-throughput-1000000-elements) 참고). 직접 만든 고정 크기 객체에는 `std/alloc/slab.h`의 `slab_alloc_t<T>`와 아레나 리전(`new<R> T`)이 같은 효과를 준다.

## 메모리 연산 {#memory-operations}

### safe_memcpy {#safe_memcpy}

```c
generic<T> void safe_memcpy(&T dst, const &T src, unsigned long n);
```

`src`에서 `dst`로 `n`바이트를 복사한다. 원본과 대상 영역은 **겹치면
안 된다**. 겹치는 영역에는 `safe_memmove`를 사용한다.

### safe_memmove {#safe_memmove}

```c
generic<T> void safe_memmove(&T dst, const &T src, unsigned long n);
```

`src`에서 `dst`로 `n`바이트를 복사한다. 겹치는 영역에도 안전하다.

### safe_memset {#safe_memset}

```c
generic<T> void safe_memset(&T ptr, int val, unsigned long n);
```

`ptr`부터 `n`바이트를 값 `val`(`unsigned char`로 해석됨)로 설정한다.

### safe_memcmp {#safe_memcmp}

```c
generic<T> int safe_memcmp(const &T a, const &T b, unsigned long n);
```

`a`와 `b`의 `n`바이트 메모리를 비교한다. 반환값:
- `a`가 `b`보다 작으면 `< 0`
- 같으면 `0`
- `a`가 `b`보다 크면 `> 0`

## 캐시와 정렬 헬퍼 {#cache-and-alignment-helpers}

```c
#define MEM_CACHE_LINE_SIZE  64   // bytes (typical x86 / ARM / RISC-V L1)

unsigned long mem_align_up(unsigned long addr, unsigned long align);   // align is a power of two
unsigned long mem_align_down(unsigned long addr, unsigned long align);
int           mem_is_aligned(unsigned long addr, unsigned long align);
generic<T> ?&T mem_align_ptr(&T ptr, unsigned long align);             // null if it can't be aligned
int           mem_fits_page(unsigned long addr, unsigned long size);    // 1 if [addr, addr+size) stays in one 4 KiB page

// locality: 0 = no reuse, 1 = low, 2 = moderate, 3 = high. __builtin_prefetch; no-op elsewhere.
generic<T> void mem_prefetch(const &T addr, int write, int locality);
generic<T> void mem_clflush(const &T addr);              // flush the line holding addr (x86 CLFLUSH; no-op elsewhere)
generic<T> void mem_zero_secure(&T ptr, unsigned long n); // zero, then flush, so the bytes leave the cache
```

주소 산술 헬퍼는 일반 `unsigned long` 주소를 받고, 나머지는 위의 `safe_mem*` 함수들처럼 영역의 첫 바이트에 대한 참조를 받습니다.

## 예제 {#example}

```c
#include <std/mem.h>
#include <std/collections/slice.h>
#include <std/io.h>

int main() {
    // A buffer for 10 ints, zeroed
    ?&heap int maybe = std::alloc(10UL * sizeof(int));
    match (maybe) {
        case null:     { return 1; }               // out of memory
        case some(buf): {
            std::safe_memset(buf, 0, 10UL * sizeof(int));
            struct Slice<int> s = std::slice_of(buf, 10UL);   // bounds-checked view
            s.set(0UL, 42);
            s.set(1UL, 99);

            // Duplicate into a second buffer
            ?&heap int copy = std::alloc(10UL * sizeof(int));
            match (copy) {
                case some(c): {
                    std::safe_memcpy(c, buf, 10UL * sizeof(int));
                    std::println_int(*std::slice_of(c, 10UL).at(0UL));   // 42
                }
                case null: { }
            }
            std::dealloc(copy);
        }
    }
    std::dealloc(maybe);
    return 0;
}
```

::: tip
여기의 모든 함수는 참조를 받고 반환하므로 `unsafe`가 필요 없다. 참조에는
길이가 없으므로 인덱싱은 `Slice`를 거친다. `unsafe`가 무엇을 면제해 주고
무엇을 면제해 주지 않는지는 [안전성 모델](/ko/advanced/safety-model)을 참고한다.
:::

### 이중 해제는 잡히지, 조용히 넘어가지 않는다 {#double-free-is-caught-not-silent}

```c
#include <std/mem.h>
#include <std/io.h>

int main() {
    ?&heap void p = std::alloc(64UL);
    std::dealloc(p);
    std::println("first dealloc OK");
    // The compiler rejects a second dealloc of p; unsafe opts out of that
    // check, so the runtime one fires instead.
    unsafe { std::dealloc(p); }   // aborts: "dealloc() called twice on the same pointer"
    println("never reached");
    return 0;
}
```
