# 안전성

SafeC는 여러 개의 맞물린 분석을 통해 컴파일 타임에 메모리 안전성을 강제합니다. 이 검사들은 프로그램이 실행되기 전에 흔한 C 버그 -- 해제 후 사용(use-after-free), 버퍼 오버플로, 널 역참조, 별칭(aliasing) 위반, 소유 자원의 누수, 데이터 경쟁 -- 를 잡아냅니다.

## 확정 초기화 {#definite-initialization}

모든 변수는 사용되기 전에 반드시 대입되어야 합니다. 컴파일러는 이를 강제하기 위해 정의-사용(def-use) 분석을 수행합니다.

```c
int x;
printf("%d\n", x);            // ERROR: 초기화되지 않은 변수 'x' 사용
```

```c
int x;
x = 42;
printf("%d\n", x);            // OK: x가 초기화됨
```

구조체 변수는 선언 시점에 이미 초기화된 것으로 간주됩니다(필드는 개별적으로 대입됩니다).

```c
Point p;
p.x = 1.0;
p.y = 2.0;
double len = p.x * p.x + p.y * p.y;  // OK
```

## 리전 이스케이프 분석 {#region-escape-analysis}

컴파일러는 모든 참조의 리전을 추적하여 해당 참조가 자신의 리전보다 오래 살아남지 않도록 보장합니다.

### 스택 참조는 이스케이프할 수 없습니다 {#stack-references-cannot-escape}

```c
&stack int bad() {
    int local = 10;
    return &local;             // ERROR: 스택 참조가 함수를 벗어남
}
```

### 참조는 더 오래 사는 저장소로 이스케이프할 수 없습니다 {#references-cannot-escape-to-longer-lived-storage}

```c
static &stack int global_ref;

void bad() {
    int x = 5;
    global_ref = &x;          // ERROR: 스택 참조가 정적 저장소에 대입됨
}
```

### 아레나 참조는 리셋 시 소멸합니다 {#arena-references-die-on-reset}

`arena_reset<R>()`/`arena_destroy<R>()`는 `R`에 속한 *모든* 남아있는
참조를 무효화합니다. 이후의 `new<R>`에 의해 그 메모리가 다시 배분될 수
있기 때문입니다. 호출 이후에 `&arena<R> T` 참조(또는 그 복사본)를 읽는
것은 컴파일 오류가 됩니다. `arena_free_to<R>()`(`arena_mark<R>()`와 짝을
이룸)는 더 좁은 범위를 가집니다 — 해제 대상이 되는 체크포인트 *이후*에
할당된 참조만 무효화합니다. 마크는 각각 따로 추적하므로 중첩된 마크도
정밀하게 다루며, 특정 `R`에 대해 `arena_mark<R>()`가 한 번도 호출되기
전에 할당된 참조는 `arena_free_to<R>()` 호출에도 손상되지 않고
살아남습니다.

```c
region Pool { capacity: 1024 }

int main() {
    &arena<Pool> int p = new<Pool> int;
    arena_reset<Pool>();
    *p = 42;   // ERROR: 'p'(&arena<Pool> 참조)가 arena_reset<Pool>(),
               //        arena_destroy<Pool>(), 또는 arena_free_to<Pool>()에
               //        의해 무효화된 후 사용됨
    return 0;
}
```

이 검사는 함수의 제어 흐름(하나의 `if`/`else` 분기 안에서의 reset은 다른
분기에 영향을 주지 않으며, 루프는 고정점에 도달할 때까지 반복 분석하므로
reset은 다음 반복에서 본문의 맨 위에 닿습니다)과 호출(`R`을 reset하거나
전달받은 마크로 되감는 함수는 호출자의 참조를 무효화합니다)을 따라가는
데이터플로우 분석으로 이루어집니다. 전체 세부 사항은
[메모리와 리전](/ko/reference/memory#_4-arena-references-die-on-reset)을,
이 분석이 건전성을 보장하는 신뢰 경계는
[시간 안전성](/ko/advanced/safety-model#_2-temporal-safety)을
참고하십시오. 다른 모든 리전/별칭 검사와 마찬가지로, `unsafe {}`는
이 검사 역시 우회합니다.

같은 분석이 `std::dealloc(p)`로 해제된 `&heap T` 참조에도 적용되며, 복사본,
구조체 필드, 배열 원소, 호출을 모두 따라갑니다 —
[메모리와 리전](/ko/reference/memory#compile-time-use-after-free-and-double-free-checking)을
참고하십시오.

### 스코프 깊이 추적 {#scope-depth-tracking}

컴파일러는 각 리전과 참조에 `declScopeDepth`를 할당합니다. 내부 참조는 외부 스코프에 저장될 수 없습니다.

```c
&stack int ref;
{
    int inner = 10;
    ref = &inner;              // ERROR: 내부 스코프 참조가 외부로 이스케이프함
}
```

## 별칭 규칙 (보로우 체커) {#aliasing-rules-borrow-checker}

SafeC는 다음 원칙을 강제합니다: **하나의 가변 참조, 또는 임의 개수의 불변 참조 중 하나만 허용되며, 둘이 동시에 존재할 수 없습니다.**

### 단일 가변 참조 {#single-mutable-reference}

```c
int x = 42;
&stack int a = &x;             // 가변 대여
&stack int b = &x;             // ERROR: x는 이미 가변으로 대여됨
```

### 다중 불변 참조 {#multiple-immutable-references}

```c
int x = 42;
&stack const int a = &x;       // 불변 대여
&stack const int b = &x;       // OK: 다중 불변 대여는 허용됨
```

`const &stack int a`는 `&stack const int a`와 같은 타입입니다. `const`는 리전 한정자의 앞과 뒤 어느 쪽에 써도 됩니다.

### 가변 + 불변 충돌 {#mutable-immutable-conflict}

```c
int x = 42;
&stack const int r = &x;       // 불변 대여
&stack int w = &x;             // ERROR: 불변 대여가 존재하는 동안
                               //        가변 대여를 할 수 없음
```

::: tip 이것이 WAR/RAW/WAW 위험을 배제하는 방식입니다
"하나의 가변 XOR 다수의 공유"는 보통 *위험(hazard)*의 관점에서
설명되는 것과 동일한 보장입니다: 동일 메모리에 대한 두 개의 살아있는
참조로부터 동시에 발생하는 write-after-read, read-after-write, 또는
write-after-write. 위의 "가변 + 불변 충돌" 예제는 WAR/RAW 위험입니다
(`w`가 쓰는 동안 `r`은 여전히 이전 값을 읽을 것으로 기대할 수
있습니다), 이는 두 개의 동시 가변 대여를 거부하는 동일한 보로우
검사(WAW 위험)에 의해 컴파일 타임에 거부됩니다. 이는 일반적인
단일 스레드 별칭에 대해 성립합니다. 이 보장이 `spawn`과 `unsafe`에
걸쳐 어떻게 확장되고(또 어디서 멈추는지)는 [동시성](/ko/reference/concurrency)을
참고하십시오.
:::

### 스코프 기반 추적 {#scope-based-tracking}

대여는 스코프 단위로 추적됩니다. 스코프가 종료되면 해당 대여도 해제됩니다.

```c
int x = 42;
{
    &stack int a = &x;         // 내부 스코프의 가변 대여
}                              // 여기서 대여 해제
&stack int b = &x;             // OK: 충돌하는 대여 없음
```

## Nullable 여부 강제 {#nullability-enforcement}

일반 참조는 기본적으로 non-null입니다. Nullable 참조는 `?&` 접두사를
사용해야 합니다 — 리전을 포함할 수도 있고(`?&stack Node`) 포함하지 않을
수도 있습니다(`?&Node`, 추적되는 리전이 전혀 없는 "outliving 참조"; 자세한 내용은
[메모리와 리전](/ko/reference/memory) 참고) — 그리고 플로우 민감(flow-sensitive)한
null 좁히기(narrowing)를 지원하는 언어와 달리, 단순한 `!= null` 비교는
이후의 역참조를 안전하게 만들어주지 **않습니다**: 컴파일러는 검사와
접근을 연결하는 흐름 분석을 수행하지 않습니다. Nullable 참조를 읽는
것은 `match`, `.is_null()`, `.default(fallback)`, 또는 명시적인
`unsafe` 블록을 통해서만 허용됩니다.

```c
void demo(?&stack Node next) {
    // *next;                   // ERROR: nullable 참조는 사전 null 검사
                                //         유무와 관계없이 직접 역참조할 수 없음

    match (next) {
        case null:    return;
        case some(n): {
            int val = n.value;  // OK: n은 non-null 페이로드로 바인딩됨
        }
    }
}

void demo_default(?&stack Node next) {
    if (next.is_null()) {       // OK: 허용되는 null 검사
        return;
    }
    // 위의 is_null() 검사 이후에도 여기서 'next'를 직접 역참조할 수는
    // 없습니다 -- match나 명시적 unsafe 블록을 사용하십시오
}
```

## 경계 안전성 {#bounds-safety}

컴파일러는 배열 및 슬라이스 접근에 대해 경계 검사(bounds check)를 삽입합니다.

### 정적 경계 검사 {#static-bounds-checking}

인덱스가 컴파일 타임 상수인 경우, 검사는 컴파일 타임에 이루어집니다.

```c
int arr[5];
int x = arr[10];               // ERROR: 인덱스 10이 크기 5인 배열의
                               //        범위를 벗어남
```

### 런타임 경계 검사 {#runtime-bounds-checking}

인덱스가 동적인 경우, 컴파일러는 범위를 벗어난 접근 시 프로그램을 abort시키는 런타임 검사를 삽입합니다.

```c
int arr[5];
int i = get_index();
int x = arr[i];                // 런타임 검사: if (i >= 5) abort();
```

### 슬라이스 경계 검사 {#slice-bounds-checking}

슬라이스 접근은 항상 슬라이스 길이에 대해 경계 검사됩니다.

```c
[]int s = arr[0..3];
int x = s[i];                  // 런타임 검사: if (i >= s.len) abort();
```

## 원시 포인터 {#raw-pointers}

<a id="pointer-arithmetic"></a>

`unsafe` 밖에서는 메모리에 오직 참조(`&T`, `?&T`, 영역 키워드는 있어도 없어도 됨)로만 접근하며, 널이 될 수 있는 참조는 먼저 `match`, `.is_null()`, `.default()`로 확인해야 합니다. 원시 `T*`는 안전한 코드가 들고 있을 수는 있지만 사용할 수는 없는 C 값입니다:

| 원시 포인터 `p`로 하는 일 | 안전한 코드 | `unsafe` 필요 |
|---|---|---|
| 저장, C 함수에 전달, 반환 | 가능 | |
| 비교(`p == q`, `p == null`), `void*`로 넓히기, 정수로 캐스트 | 가능 | |
| `null` / `(T*)0` | 가능 | |
| 널이 될 수 있는 참조 만들기: `(?&T)p`, 또는 `?&T` 자리에 전달 | 가능 | |
| non-null 참조 만들기: `(&T)p`, 또는 `&T` 자리에 전달 | | 필요: 널이 아님을 아무것도 확인하지 않음 |
| 그것을 통한 접근: `*p`, `p[i]`, `p->field` | | 필요 |
| 산술: `p + n`, `p - n`, `p += n`, `p++`, `p - q` | | 필요 |
| 정수에서 만들기: `(T*)0x4000` | | 필요 |
| 재해석: `(char*)p`, `(T*)voidPtr` | | 필요 |

C에서 온 포인터를 안전한 코드에서 쓰는 일반적인 방법은 널이 될 수 있는 참조로 바꾼 뒤 `match`하는 것입니다. 영역은 여전히 알 수 없으므로(`?&T`에는 영역 키워드가 없음), 그 참조는 알려진 수명이 필요한 곳에 저장할 수 없습니다:

```c
extern int* find_entry(int key);

int lookup(int key) {
    ?&int r = (?&int)find_entry(key);   // 영역 모름, 널일 수 있음
    match (r) {
        case some(v): return *v;        // v는 non-null 참조
        case null:    return -1;
    }
    return -1;
}
```

포인터 산술은 C와 똑같이(원소 크기만큼 스케일, `p - q`는 부호 있는 64비트 원소 거리) 컴파일되지만, `unsafe` 안에서만 쓸 수 있습니다:

```c
void fill(int* p, int n) {
    unsafe {
        int* end = p + n;
        while (p < end) { *p = 0; p += 1; }
    }
}
```

참조에는 산술이 전혀 없습니다: 대신 슬라이스나 컨테이너를 통해 인덱싱합니다.

## 엄격한 별칭 규칙 {#strict-aliasing}

SafeC는 C의 엄격한 별칭(strict aliasing) 규칙을 따릅니다: 한 스칼라 타입의 값은 다른 스칼라 타입의 lvalue로 읽거나 쓰지 않습니다. 단, 8비트(`char`, `unsigned char`, `int8_t`, `bool`) lvalue는 무엇이든 접근할 수 있습니다. 같은 너비의 부호 있는/없는 정수는 한 타입으로 치고, 열거형과 newtype은 기반 타입으로, 모든 포인터와 참조는 한 타입으로 칩니다.

컴파일러는 이 규칙을 타입 기반 별칭 정보로 LLVM에 넘깁니다. 그래서 루프가 다른 타입의 원소를 저장하는 동안에도 구조체의 길이와 데이터 포인터를 레지스터에 둘 수 있습니다:

```c
struct Buf { float* data; unsigned long len; };

void scale(&Buf b, float k) {
    unsafe {
        // float 저장은 b->len이나 b->data를 바꿀 수 없으므로 둘 다 한 번만
        // 읽히고 루프가 벡터화됩니다.
        for (unsigned long i = 0UL; i < b->len; i = i + 1UL) {
            b->data[i] = b->data[i] * k;
        }
    }
}
```

안전한 코드는 이 규칙을 어길 수 없습니다: 포인터를 다른 포인터 타입으로 재해석하려면 `unsafe`가 필요합니다. `unsafe` 안에서 값의 비트 패턴을 다른 타입으로 다뤄야 할 때는 캐스트 대신 바이트를 복사합니다:

```c
#include <std/stddef.h>
extern void* memcpy(void* dst, const void* src, size_t n);   // unsigned long이 아니라 size_t: wasm32에서는 32비트

unsigned int float_bits(float f) {
    unsigned int bits = 0U;
    unsafe { memcpy((void*)&bits, (const void*)&f, (size_t)4); }   // *(unsigned int*)&f 대신
    return bits;
}
```

공용체 멤버와 `volatile` 접근은 예외입니다. 포인터 캐스트로 타입 퍼닝하는 코드는 `--no-strict-aliasing`으로 빌드해야 하며, 그러면 그 컴파일에서 별칭 정보가 꺼집니다.

참조 매개변수에는 `noalias`를 붙이지 않습니다. 빌림 검사기는 두 인자가 서로 별칭이 되는 것을 막지만, 함수는 여전히 다른 경로(예: 직접 쓰는 전역 변수)로 같은 객체에 닿을 수 있으므로 그 약속을 할 수 없습니다.

## 소유권과 Drop {#ownership-and-drop}

`void drop()`을 정의하는 타입(내장 `Drop` 트레이트)은 자원을 소유하며, 그런 값을 담은 타입도 마찬가지입니다. 컴파일러는 그런 값이 모든 경로 — `return`, `break`, `goto`, `match`, `defer`를 거치는 — 에서 정확히 한 번 drop 또는 move되는지 검사하고, 누수, drop/move 후 사용, 이중 drop, 살아 있는 값 덮어쓰기를 거부합니다. 코드는 삽입하지 않습니다: `drop()`은 소스가 호출하는 곳에서만 실행됩니다.

```c
void f(int c) {
    struct Vec<int> v = Vec::new();
    if (c) return;      // ERROR: value of Drop type 'Vec<int>' leaks when the function returns
    v.drop();
}
```

[소유권과 Drop](/ko/reference/ownership)을 참고하십시오.

## 스레드 안전성 {#thread-safety}

모든 타입은 구조로부터 유도되는 Send(다른 스레드로 옮길 수 있음) 그리고/또는 Sync(`const &`로 스레드 간에 공유할 수 있음)입니다. `spawn`, `spawn_scoped`, 채널, `std::thread_spawn`, `Mutex<T>`, async 스레드 풀은 넘겨도 안전한 것만 받으므로, `unsafe` 밖에서는 데이터 경쟁이 배제됩니다. [스레드 안전성](/ko/reference/threads)을 참고하십시오.

## Unsafe 경계 {#the-unsafe-boundary}

안전성 규칙이 지나치게 제한적일 때, `unsafe {}` 블록을 사용해 이를 우회할 수 있습니다. `unsafe` 블록 내부에서는

- 경계 검사가 억제됩니다(`boundsCheckOmit` 플래그가 설정됨)
- 리전 이스케이프 분석이 완화됩니다
- 별칭 규칙이 강제되지 않습니다
- 원시 포인터를 역참조(`*p`, `p[i]`, `p->f`)하고, 산술로 옮기고, 정수에서 만들고, 다른 포인터 타입으로 재해석하고, non-null 참조(`(&T)p`)로 바꿀 수 있습니다
- 널 역참조가 방지되지 않습니다
- raw 포인터로 도달한 값의 소유권은 추적되지 않으며, `forget(x)`로 Drop 값을 drop하지 않고 포기할 수 있습니다

```c
unsafe {
    int *raw = (int*)malloc(10UL * sizeof(int));
    raw[0] = 42;               // 경계 검사 없음
    int *alias = raw;          // 별칭 허용
    free(raw);
}
```

### Unsafe 규칙 {#unsafe-rules}

1. **Unsafe 블록은 명시적입니다** -- 프로그래머가 위험을 스스로 선택합니다
2. **Unsafe는 전파되지 않습니다** -- unsafe 컨텍스트에서 안전한 함수를 호출한다고 해서 그 피호출 함수가 unsafe가 되는 것은 아닙니다
3. **Unsafe 범위를 최소화하십시오** -- unsafe 블록은 가능한 한 작게 유지하십시오

### 흔한 Unsafe 사용 사례 {#common-unsafe-uses}

- 비정적 리전 참조를 사용하는 FFI 호출
- C API로부터의 원시 포인터 조작
- 경계 검사 비용이 측정 가능할 정도로 큰, 성능이 중요한 내부 루프
- 베어메탈 코드에서의 하드웨어 레지스터 접근

## 안전성 보장 요약 {#summary-of-safety-guarantees}

메커니즘별로:

| 안전성 검사 | 컴파일 타임 | 런타임 | `unsafe`로 억제 가능 |
|-------------|-------------|---------|----------------------|
| 확정 초기화 | Yes | -- | No |
| 리전 이스케이프 분석 (스택/정적/교차 리전) | Yes | -- | Yes |
| 아레나 리셋/free_to 무효화 (데이터플로우 분석; 위 참고) | Yes | -- | Yes |
| 힙 해제-후-사용/이중 해제 무효화 (`std::dealloc(p)`, 같은 분석; [메모리와 리전](/ko/reference/memory#compile-time-use-after-free-and-double-free-checking) 참고) | Yes | Yes (백스톱, 더 넓은 커버리지) | Yes |
| 별칭 / 보로우 검사 | Yes | -- | Yes |
| Nullable 여부 강제 | Yes | -- | Yes |
| 정적 경계 검사 | Yes | -- | Yes |
| 동적 경계 검사 | -- | Yes | Yes |
| 소유권: [Drop](/ko/reference/ownership) 값의 누수, drop/move 후 사용, 이중 drop | Yes | -- | Yes |
| 스레드 안전성: 스레드가 받는 것의 Send/Sync ([스레드 안전성](/ko/reference/threads)) | Yes | -- | Yes (raw 포인터는 `unsafe`에서만) |
| 할당자 이중 해제/유효하지 않은 해제 태그 검사 (`std::alloc`/`dealloc`, `pool.h`/`slab.h`/`tlsf.h`) | -- | Yes | N/A (런타임 전용) |

버그 분류별로 — 이는 위의 메커니즘들을 전형적인 C 버그 분류 체계에
매핑한 것입니다. 하나의 메커니즘(예: 보로우 체커)이 여러 이름으로
불리는 버그 분류 하나를 통째로 커버하는 경우가 많기 때문입니다.

| 버그 분류 | 컴파일 타임 | 런타임 | 방법 |
|-----------|-------------|---------|-----|
| 초기화되지 않은 변수 읽기 | Yes | -- | 위의 확정 초기화. |
| 널 포인터 역참조 (`&T`, `?T`, `?&region T`) | Yes | -- | 위의 nullable 여부 강제. 원시 C `T*`는 커버되지 **않습니다** — 이를 역참조하는 것은 `unsafe {}` 안에서만 가능하며, C와 동일한 책임 모델입니다. |
| 버퍼 오버플로/오버리드, 상수 인덱스 | Yes | -- | 위의 정적 경계 검사. |
| 버퍼 오버플로/오버리드, 동적 인덱스 | -- | Yes | 위의 런타임 경계 검사 — 임의의 런타임 계산 인덱스가 범위 내에 있음을 미리 증명하는 것은 일반적으로 결정 불가능(undecidable)하므로, 이는 빈틈이 아니라 의도적인 런타임 백스톱입니다. |
| 데이터 레이스: 별칭된 데이터에 대한 WAR/RAW/WAW, 단일 스레드 | Yes | -- | 보로우 체커의 "하나의 가변 XOR 다수의 공유" 규칙 — "스코프 기반 추적" 절 위의 팁 참고. |
| 데이터 레이스: 스레드 간 | Yes | -- | 모든 타입은 구조로부터 유도되는 Send 그리고/또는 Sync입니다. `spawn`은 Sync인(또는 자기 쓰기를 동기화하는) 정적 데이터만 공유하고, `spawn_scoped`는 join할 때까지 지역 변수를 빌려주며, 채널과 `thread_spawn`은 Send 값을 move하고, `Mutex<T>`는 가드를 통해서만 값에 접근합니다. 무엇이 커버되고 무엇이 커버되지 않는지(교착 상태는 아님)는 [스레드 안전성](/ko/reference/threads) 참고. |
| 데이터 레이스: `unsafe` 내부 / 원시 포인터 | -- | -- | 의도적으로 검사되지 않습니다 — `unsafe`는 별칭 추적에 대한 명시적 옵트아웃이며, C와 동일합니다. 검사기의 버그가 아니라, 설계상의 경계입니다. |
| 해제 후 사용: 스택 참조 | Yes | -- | 위의 리전 이스케이프 분석. |
| 해제 후 사용: 아레나 참조 | Yes | -- | 각 함수 본문에 대한 데이터플로우 분석으로, 마크별 `arena_free_to` 추적과 호출 요약을 포함합니다; [아레나 참조는 리셋 시 소멸합니다](#arena-references-die-on-reset) 참고. |
| 해제 후 사용: 힙 참조 (`std::dealloc(p)`) | Yes | Yes (백스톱) | 같은 데이터플로우 분석 — 복사본, 구조체 필드, 배열 원소, 호출을 통해 포인터를 따라가며, 할당자 인스턴스의 `.dealloc()` 메서드도 인식합니다; 커버되지 *않는* 것(원시 `free()`와 본문이 없는 그 밖의 `extern` 함수, 함수 포인터를 통한 호출, `unsafe {}` 안에서 원시 포인터로 도달한 메모리)은 [메모리와 리전](/ko/reference/memory#compile-time-use-after-free-and-double-free-checking) 참고. |
| 이중 해제 | Yes (`&heap T`에 대한 `std::dealloc(p)` 또는 할당자의 `.dealloc(p)`, 또는 반복된 아레나 리셋/free_to) | Yes (다른 모든 할당자, 그리고 컴파일 타임 사례에 대한 백스톱으로도) | 위의 힙 UAF 행과 동일한 메커니즘/범위, 그리고 그것이 커버하지 못하는 모든 경우를 위한 아래의 할당자 태그 검사. |
| 잘못 짝지어진 해제 (잘못된 할당자의 해제 함수로 포인터를 해제) | -- | Yes | 각 할당자(`std::alloc`/`dealloc`, 그리고 `pool.h`/`slab.h`/`tlsf.h`의 할당자들)는 자신의 블록에 태그를 붙이고 해제 시 태그를 검사합니다. 컴파일러는 주어진 포인터를 *어느* 할당자가 생성했는지 추적하지 않으므로, 잘못된 할당자의 해제 함수를 통해 해제된 포인터는 미리가 아니라 그 할당자 자신의 태그 검사가 런타임에 거부할 때 잡힙니다. |
| 메모리 누수: Drop 타입의 값 | Yes | -- | `drop()` 메서드를 가진 타입(또는 그런 값을 담은 타입)은 모든 경로에서 drop 또는 move되어야 합니다. 잊거나, `drop()` 후 사용하거나, 두 번 drop하면 컴파일 오류이며 런타임 비용은 없습니다. [소유권과 Drop](/ko/reference/ownership) 참고. 표준 라이브러리의 소유 타입(`Vec`, `String`, `HashMap`, 파일, 소켓, 스레드, 가드, GUI와 오디오 핸들, future)은 모두 `Drop`을 구현합니다. |
| 메모리 누수: raw 할당, `unsafe`, 순환 | -- | -- | 어떤 Drop 타입도 소유하지 않는 `std::alloc`의 맨 `&heap T`, raw 포인터 뒤의 메모리, `forget()`(`unsafe`에서만 허용)으로 포기한 것은 추적하지 않습니다. 할당을 `drop()`을 가진 타입으로 감싸거나, 바로 옆에 `defer std::dealloc(...)`을 짝지으십시오 — [Defer](/ko/reference/control-flow#defer) 참고. |
