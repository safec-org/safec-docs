# 안전성

SafeC는 여러 개의 맞물린 분석을 통해 컴파일 타임에 메모리 안전성을 강제합니다. 이 검사들은 프로그램이 실행되기 전에 흔한 C 버그 -- 해제 후 사용(use-after-free), 버퍼 오버플로, 널 역참조, 별칭(aliasing) 위반 -- 를 잡아냅니다.

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
있기 때문입니다. 컴파일러는 리전마다 세대(generation) 카운터를 두어
이를 강제합니다. 각 호출은 카운터를 증가시키고, 증가 이전에 바인딩된
`&arena<R> T` 참조를 읽는 것은 컴파일 오류가 됩니다. `arena_free_to<R>()`
(`arena_mark<R>()`와 짝을 이룸)는 더 좁은 범위를 가집니다 — 해제 대상이
되는 체크포인트 *이후*에 바인딩된 참조만 무효화하며, `arena_reset`의
세대 카운터를 공유하지 않고 자체적인 마크 중첩 깊이(mark-nesting-depth)
카운터로 추적됩니다. 특정 `R`에 대해 `arena_mark<R>()`가 한 번도 호출되기
전에 바인딩된 참조는 `arena_free_to<R>()` 호출에도 손상되지 않고
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

이 추적은 if/else 분기와 루프 본문에 걸쳐 흐름 *민감*(flow-sensitive)
합니다(하나의 `if`/`else` 분기 안에서의 reset은 다른 분기에 영향을 주지
않습니다. 루프 본문 어디에서든 발생하는 reset/free_to는 *모든* 해당
루프 본문의 문장에 대해 이미 발생했을 수 있는 것으로 취급됩니다. 텍스트
상으로 그 이후에 있는 문장만이 아닙니다 — 이후 반복이 이전 반복의
reset 이후 본문의 맨 위부터 다시 실행되기 때문입니다) `arena_reset`/
`arena_destroy` *및* `arena_free_to`에 대해 그렇습니다. 이는 여전히
견고(sound)하지만 완전(exhaustive)하지는 않습니다. 루프 본문 검사는 흔한
문장/표현식 형태를 다루는 구문적 사전 스캔이며, 문자 그대로 가능한 모든
중첩을 다루지는 않습니다. 중첩된 `arena_mark`/`arena_free_to` 스코프
역시 흐름 민감성과 무관하게 보수적으로 처리됩니다(리전마다 전체 레벨별
기록이 아닌 하나의 중첩 깊이 카운터만 존재). 전체 세부 사항은
[메모리와 리전](/ko/reference/memory#_4-arena-references-die-on-reset)을
참고하십시오. 실제로 여전히 유효한 참조에 대한 `unsafe {}` 우회 방법도
포함되어 있습니다. 다른 모든 리전/별칭 검사와 마찬가지로, `unsafe {}`는
이 검사 역시 우회합니다.

동일한 흐름 민감 세대-카운터 방식은 `std::dealloc(p)`로 해제된 `&heap T`
참조에도 적용됩니다 — 힙 관련 범위(단일 프로시저 내부, 하나의 변수 자신의
선언에 키가 매여 있음)에 대해서는
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

::: warning `const`는 리전 한정자 뒤에 와야 합니다
리전 *앞*에 `const`를 쓰는 것(`const &stack int a`)은 파서가 이를
받아들이지만 조용히 불변 대여가 아닌 **가변** 대여를 만들어냅니다 —
파서 버그입니다(리전 한정자를 살펴보기 전에 `const` 토큰이 먼저
소비됩니다). 이 때문에 동일한 변수에 대한 두 개의 `const &stack int`
대여는 "이미 가변으로 대여됨" 오류로 실패하는데, 이는 보로우 체커
자체의 버그처럼 보이지만 실제로는 그 앞단의 가변 오파싱 문제입니다.
의도한 대로 불변 대여를 얻으려면 항상 리전 한정자 *뒤*에 `const`를
쓰십시오(위와 같이 `&stack const int`).
:::

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

## Unsafe 경계 {#the-unsafe-boundary}

안전성 규칙이 지나치게 제한적일 때, `unsafe {}` 블록을 사용해 이를 우회할 수 있습니다. `unsafe` 블록 내부에서는

- 경계 검사가 억제됩니다(`boundsCheckOmit` 플래그가 설정됨)
- 리전 이스케이프 분석이 완화됩니다
- 별칭 규칙이 강제되지 않습니다
- 원시 포인터 산술이 허용됩니다
- 널 역참조가 방지되지 않습니다

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
| 아레나 리셋 무효화 (if/else와 루프에 걸친 흐름 민감; 위 참고) | Yes | -- | Yes |
| 힙 해제-후-사용/이중 해제 무효화 (`std::dealloc(p)`, 흐름 민감; [메모리와 리전](/ko/reference/memory#compile-time-use-after-free-and-double-free-checking) 참고) | Yes | Yes (백스톱, 더 넓은 커버리지) | Yes |
| 별칭 / 보로우 검사 | Yes | -- | Yes |
| Nullable 여부 강제 | Yes | -- | Yes |
| 정적 경계 검사 | Yes | -- | Yes |
| 동적 경계 검사 | -- | Yes | Yes |
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
| 데이터 레이스: 스레드 간 | Yes | -- | `spawn`/scoped-spawn은 가변인 비-`&static` 참조 인자를 거부합니다; [동시성](/ko/reference/concurrency) 참고. |
| 데이터 레이스: `unsafe` 내부 / 원시 포인터 | -- | -- | 의도적으로 검사되지 않습니다 — `unsafe`는 별칭 추적에 대한 명시적 옵트아웃이며, C와 동일합니다. 검사기의 버그가 아니라, 설계상의 경계입니다. |
| 해제 후 사용: 스택 참조 | Yes | -- | 위의 리전 이스케이프 분석. |
| 해제 후 사용: 아레나 참조 | Yes | -- | 흐름 민감 세대 카운터 — 견고하지만 깊이 중첩된 마크나 루프 내부의 특이한 표현식 중첩에 대해서는 완전하지 않습니다; [아레나 참조는 리셋 시 소멸합니다](#arena-references-die-on-reset) 아래의 경고 참고. |
| 해제 후 사용: 힙 참조 (`std::dealloc(p)`) | Yes | Yes (백스톱) | 흐름 민감 변수별 세대 카운터 — 단일 프로시저 내에서, 구문적으로(`std::dealloc`에 대한 단순 식별자 인자) 처리됩니다; 정확한 범위와 커버되지 *않는* 것(원시 `free()`, 할당자 인스턴스의 `.dealloc()` 메서드, 교차 별칭/교차 함수 해제)은 [메모리와 리전](/ko/reference/memory#compile-time-use-after-free-and-double-free-checking) 참고. |
| 이중 해제 | Yes (추적되는 `&heap T`에 대한 `std::dealloc(p)`, 또는 반복된 아레나 리셋/free_to) | Yes (다른 모든 할당자, 그리고 컴파일 타임 사례에 대한 백스톱으로도) | 위의 힙 UAF 행과 동일한 메커니즘/범위, 그리고 그것이 커버하지 못하는 모든 경우를 위한 아래의 할당자 태그 검사. |
| 잘못 짝지어진 해제 (잘못된 할당자의 해제 함수로 포인터를 해제) | -- | Yes | 각 할당자(`std::alloc`/`dealloc`, 그리고 `pool.h`/`slab.h`/`tlsf.h`의 할당자들)는 자신의 블록에 태그를 붙이고 해제 시 태그를 검사합니다. 컴파일러는 주어진 포인터를 *어느* 할당자가 생성했는지 추적하지 않으므로, 잘못된 할당자의 해제 함수를 통해 해제된 포인터는 미리가 아니라 그 할당자 자신의 태그 검사가 런타임에 거부할 때 잡힙니다. |
| 메모리 누수 | -- | -- | 컴파일 타임에도 런타임에도 검사되지 않습니다. 일반적인 누수 탐지에는 전 프로그램 단위의 소유권/이스케이프 분석이 필요합니다 — Rust 자체의 보로우 체커도 누수 없음(leak-freedom)을 보장하지 못하는 것과 같은 이유입니다(거기서도 `Rc` 순환과 `mem::forget`은 "안전한" 누수입니다). 모든 할당에는 바로 옆에 `defer std::dealloc(...)`(실패 시에만 처리하는 경우엔 `errdefer`)을 짝지어, 정리(cleanup)를 컴파일러가 강제하는 대신 구조적으로 잊기 어렵게 만드십시오 — [Defer](/ko/reference/control-flow#defer)와 [오류 처리](/ko/book/ch08-error-handling) 참고. |
