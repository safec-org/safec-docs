# 형식 안전 모델

SafeC의 안전 보장은 지향적인 목표가 아닙니다 — 이는 구문적 타입 안전성(progress + preservation)을 갖춘 타입 시스템으로 형식화되어 있으며, Oxide(Weiss 외)와 Cyclone(Grossman 외)의 선행 연구를 기반으로 합니다.

## 일곱 가지 안전 속성 {#seven-safety-properties}

### 1. 공간 안전성 {#_1-spatial-safety}

범위를 벗어난 메모리 접근이 없습니다. 배열 첨자는 상수 인덱스의 경우 컴파일 타임에, 동적 인덱스의 경우 런타임에 경계 검사됩니다.

```c
int arr[4];
arr[5] = 1;        // 컴파일 타임 오류: index 5 out of bounds for array of size 4

int i = get_index();
arr[i] = 1;        // 런타임 경계 검사가 삽입됨 (위반 시 abort)
```

런타임 경계 검사는 `unsafe {}` 블록 안에서 억제될 수 있습니다.

### 2. 시간 안전성 {#_2-temporal-safety}

use-after-free가 없습니다: 리전 표기는 참조의 라이프타임을 할당 스코프에 묶습니다. 스택 메모리에 대한 참조는 함수를 벗어날 수 없고, arena에 대한 참조는 `arena_reset<R>()`/`arena_destroy<R>()`/`arena_free_to<R>()`에 의해 무효화되며, `&heap T` 참조는 `std::dealloc()`에 의해 무효화됩니다 — 이 세 가지 모두 단순히 런타임뿐 아니라 컴파일 타임에도 검사됩니다.

```c
&stack int get_local() {
    int x = 42;
    return &x;      // 컴파일 타임 오류: stack reference escapes function
}
```

```c
#include <std/mem.sc>

void heap_example() {
    &heap int p = new int;
    std::dealloc(p);
    *p = 1;              // 컴파일 타임 오류: use of 'p' after std::dealloc() freed it
    std::dealloc(p);      // 컴파일 타임 오류: double-free, same check
}
```

::: warning 흐름 민감적이지만 완전하지는 않음
이 내용의 arena 측면과 heap 측면 모두([메모리와 리전](/ko/reference/memory#_4-arena-references-die-on-reset)와
[메모리와 리전](/ko/reference/memory#compile-time-use-after-free-and-double-free-checking) 참고)
는 컴파일러가 함수의 실제 제어 흐름을 순회하며 갱신되는 세대 카운터
검사입니다(if/else 분기는 서로에게 영향을 주지 않으며, 루프 본문
안 어디에서든 발생하는 reset/free_to/dealloc은 그 이후의 문장뿐
아니라 본문 안의 모든 문장 이전에 발생했을 수도 있는 것으로 취급됩니다)
— 이는 단순 선형 패스가 아니라 건전(sound)하지만(진짜 use-after-reset,
use-after-free_to, use-after-free, double-free는 결코 놓치지 않음)
완전한 데이터플로우 고정점은 아니므로, 흔치 않은 형태에서는 실제로
유효한 참조를 여전히 과다 플래그할 수 있습니다: 루프 본문 검사는
reset/destroy/free_to/dealloc 호출이 나타나는 일반적인 문장/표현식
형태를 다루는 구문적 사전 스캔이지, 문자 그대로 모든 중첩을
다루지는 않습니다; `arena_mark<R>()`/`arena_free_to<R>()`는 흐름
민감성과 무관하게 *깊이 중첩된* 마크에 대해 보수적으로 남아있습니다
— 이는 레벨별 기록이 아니라 리전당 하나의 중첩 깊이를 추적하는
설계 특성이지, 루프/분기 추적이 다루는 문제가 아닙니다. 힙 추적은
프로시저 내부에 국한되며, 한 단계 깊이의 직접 복사 별칭을 따릅니다
— `&heap T q = p;`나 `q = p;`는 p 자신의 추적 키를 공유하므로,
*둘 중 어느* 이름을 통한 `std::dealloc()`이든 올바르게 둘 다 무효화합니다
— 하지만 그 이상은 아닙니다: 구조체 필드, 배열 원소, 함수 매개변수/반환값을
통해 도달한 할당은 추적되지 않습니다(정확한 범위는
[메모리와 리전](/ko/reference/memory#compile-time-use-after-free-and-double-free-checking)의
경고 참고). `unsafe {}`는 검사기가 (아직) 스스로 안전하다고 증명할
수 없는 경우를 위한 탈출구로 남아 있습니다.
:::

### 3. 별칭 안전성 {#_3-aliasing-safety}

가변 참조는 배타적입니다. 대여 검사기는 프로그램의 어느 시점에서든 값이 하나의 가변 참조를 갖거나 임의 개수의 불변 참조를 가질 수 있지만, 결코 둘 다는 가질 수 없도록 강제합니다.

```c
int x = 10;
&stack int a = &x;          // 가변 대여
&stack const int b = &x;    // error: cannot borrow 'x' as immutable: already borrowed as mutable
```

::: warning `const`는 리전 한정자 뒤에 작성하세요
`const &stack int b`(const가 리전 *앞*에 있는 형태)는 파서가 받아들이지만
조용히 가변 대여로 잘못 파싱됩니다 — 알려진 파서 버그입니다
([안전성](/ko/reference/safety#aliasing-rules-borrow-checker) 참고).
항상 `const`를 리전 한정자 *뒤*에 작성하세요(위와 같이 `&stack const int`).
:::

### 4. 리전 이스케이프 안전성 {#_4-region-escape-safety}

참조는 자신이 가리키는 리전보다 오래 살아남을 수 없습니다. 컴파일러는 리전 스코프 깊이를 추적하며, 참조를 더 얕은 스코프로 옮기는 할당이나 반환을 거부합니다.

```c
region R { capacity: 1024 }
&arena<R> int outer_ptr;

void demo() {
    &arena<R> int p = new<R> int;
    outer_ptr = p;   // error: cannot assign '&arena<R> int' to variable in
                     //        outer scope: arena reference would escape
}
```

### 5. 데이터 레이스 자유 {#_5-data-race-freedom}

`spawn()`으로 생성된 스레드는 격리된 데이터에서 동작합니다. 타입 시스템은 명시적인 동기화 없이 스레드 경계를 넘어 가변 상태를 공유하는 것을 방지합니다.

### 6. Null 안전성 {#_6-null-safety}

참조는 기본적으로 non-null입니다. 안전한 SafeC 코드에는 null 참조가 없습니다. Nullable 참조는 `?&T` 구문을 사용합니다; 흐름 민감적인 null 좁히기(narrowing)를 가진 언어들과 달리, 단순한 `!= null` 비교만으로는 이후의 역참조가 안전해지지 않습니다 — nullable 참조를 읽는 것은 오직 `match`, `.is_null()`, `.default(fallback)`, 또는 명시적인 `unsafe` 블록을 통해서만 허용됩니다([안전성](/ko/reference/safety#nullability-enforcement) 참고).

### 7. 결정론 {#_7-determinism}

숨겨진 런타임 비용이 없습니다. SafeC는 가비지 컬렉션도, 참조 카운팅도, 암묵적 힙 할당도 삽입하지 않습니다. 모든 할당은 소스 코드에 명시적입니다. 성능 모델은 투명합니다 — 작성한 것이 곧 실행되는 것입니다.

## C, C++, Rust, Zig와의 메모리 안전성 비교 {#memory-safety-compared-to-c-c-rust-and-zig}

[비교](/ko/guide/comparison)에는 동일한 다섯 언어에 걸친 한 줄짜리 "메모리 안전성: Yes/No" 요약이 있습니다; 이 섹션은 그 단일 행을 위 속성들로 세분화합니다. "yes"와 "no"는 각 언어가 *언제*(컴파일 타임 vs. 런타임 vs. 전혀 하지 않음) 그리고 *얼마나 완전하게*(모든 안전 모드 프로그램 vs. 옵트인 빌드 모드 vs. 아무것도 없음) 검사하는지의 실제 차이를 가리기 때문입니다.

| 속성 | C | C++ | Rust | Zig | SafeC |
|---|---|---|---|---|---|
| 공간 안전성(버퍼 오버플로) | 없음 — `[]`에 검사 없음, OOB는 UB | 원시 배열/`[]`에는 없음; `.at()`/`std::span`을 통한 옵트인(예외 발생) | 상수 인덱스는 컴파일 타임, 그 외에는 런타임 패닉 — 안전 코드에서 항상 켜짐 | Debug/ReleaseSafe에서 런타임 검사; ReleaseFast/ReleaseSmall에서는 **컴파일 시 제거**(UB) | 상수 인덱스는 컴파일 타임, 그 외에는 런타임 검사 — `unsafe` 밖에서는 항상 켜짐 |
| 시간 안전성(use-after-free) | 없음 — 수동 `free()`, 추적 없음 | 원시 포인터/참조에는 없음; RAII(`unique_ptr`/`shared_ptr`)는 일관되게 사용될 때만 도움이 되며 강제되지 않음 | 컴파일 타임 — 대여 검사기가 move/drop 이후의 모든 사용을 거부; 건전함 | 컴파일러 측 보장 없음; `GeneralPurposeAllocator`가 Debug 빌드에서만 일부를 런타임에 포착 | 컴파일 타임, 리전 기반(stack/arena/heap) — 흐름 민감적이고 건전하지만 [완전하지는 않음](#_2-temporal-safety); `unsafe {}`로 옵트아웃 |
| 이중 해제 | 없음 — UB | 원시 `delete`는 C와 동일; 스마트 포인터는 원시 별칭에 의해 우회되지 않는 한 구조적으로 이를 방지 | 컴파일 타임 — 오직 하나의 소유자만 drop을 호출할 수 있음 | 런타임 전용, 옵트인(Debug에서 `GeneralPurposeAllocator`) | 컴파일 타임(위와 동일한 검사) 그리고 심층 방어로서 `std::alloc`/`std::dealloc`의 런타임 태그 헤더 가드 |
| 초기화되지 않은 읽기 | 없음 — UB | 없음 — UB | 컴파일 타임 — 확정 할당 분석 | `undefined`는 명시적이고 타입이 있음; Debug 빌드는 버그를 *보이게* 만들기 위해 이를 오염시킴(`0xaa`), 방지하지는 않음 | 컴파일 타임 — 확정 초기화 검사 |
| Null 포인터 역참조 | 없음 — 원시 포인터, 추적되지 않음 | 원시 포인터에는 없음; 참조는 구조상 null이 될 수 없지만 역참조된 null 포인터로부터 형성될 수 있음(UB), 포착되지 않음 | 컴파일 타임 — 안전 코드에 null이 없음, `Option<T>`는 매치/언래핑되어야 함 | 컴파일 타임 — 옵셔널(`?T`)은 명시적으로 언래핑되어야 함 | 컴파일 타임 — `?&T`는 읽기 위해 `match`/`.is_null()`/`.default()`/`unsafe`가 필요함([Null 안전성](#_6-null-safety) 참고) |
| 데이터 레이스 | 없음 — 언어 수준의 동시성 안전성 없음 | 없음 — 레이스는 UB; `atomic`/뮤텍스는 옵트인 도구일 뿐 강제되지 않음 | 컴파일 타임 — `Send`/`Sync` 트레이트가 안전 코드에서 공유 상태 안전성을 강제 | 없음 — 타입 시스템 강제 없음, 수동 동기화만 | 컴파일 타임 — `spawn()`이 기본적으로 데이터를 격리; 가변 상태 공유는 명시적 동기화가 필요함 |
| 강제 지점 | N/A | 대체로 없음; 일부 옵트인 런타임 검사 | 컴파일 타임 우선(대여 검사기), 진짜 동적인 부분(경계, 패닉)은 런타임 | 런타임, 그리고 안전 검사가 이루어지는 빌드 모드에서만 — *동일한 바이너리*를 검사 없이 컴파일할 수 있음 | 컴파일 타임 우선, 검사가 본질적으로 동적인 경우(인덱스 경계)에만 런타임 — `unsafe` 밖에서는 항상 켜짐 |
| 탈출구 | N/A — 언어 전체가 이런 방식임 | N/A — 언어 전체가 이런 방식임 | `unsafe {}` 블록/함수 | 어휘적 탈출구 없음 — 안전성은 블록 단위 옵트아웃이 아니라 빌드 전체 모드 설정 | `unsafe {}` 블록 — 어휘적으로 스코프가 지정되며, 전파되지 않음(아래 참고) |

핵심 요지: C와 C++는 이들 대부분을 전혀 검사하지 않습니다(정의되지 않은 동작이 대부분의 시간 동안 "검사된" 상태입니다). Zig는 이들 중 여럿을 검사하지만, *빌드 모드* 선택으로서 그렇게 합니다 — 같은 소스가 검사를 갖거나 갖지 않으며, 줄 단위가 아니라 바이너리 전체에 대해 한 번 결정됩니다. Rust와 SafeC는 둘 다 기본적으로 컴파일 타임에 검사하며, 특정 코드 블록에 대해 검사를 끄려면 명시적이고 어휘적으로 스코프가 지정된 옵트아웃(`unsafe {}`)이 필요합니다 — 이것이 바로 이 페이지 자신의 [`unsafe` 탈출구](#the-unsafe-escape-hatch) 섹션이 설명하는 속성입니다. Rust와 SafeC가 다른 지점은 시간 안전성을 *어떻게* 증명하는가(소유권/대여 vs. 리전 소속 — 그 트레이드오프에 대한 자세한 내용은 [Rust와 비교](/ko/guide/comparison#compared-to-rust) 참고)와 그 증명이 얼마나 완전한가입니다: Rust의 대여 검사기는 자신이 받아들이는 패턴에 대해 건전하고 완전한 정적 증명입니다(유효한 프로그램을 거부할 수는 있지만, 결코 안전하지 않은 프로그램을 받아들이지 않습니다); SafeC의 리전/세대-카운터 검사는 건전하지만 몇 가지 문서화된 형태에서는 의도적으로 불완전합니다(중첩된 arena 마크, 한 단계를 넘어서는 힙 별칭 — 이 페이지 전반의 경고 참고), 더 단순하고 더 C 호환적인 사고 모델을 위해 일부 정밀함을 희생합니다.

## 안전 분석 단계 {#safety-analysis-phases}

컴파일러는 의미 분석 중 일련의 분석 패스를 통해 이러한 속성들을 강제합니다:

| 단계 | 검사 내용 |
|-------|---------------|
| 확정 초기화 | 모든 변수가 사용 전에 할당됨 |
| 리전 이스케이프 분석 | 스택/정적/리전 간 이스케이프가 거부됨; arena 리셋 무효화는 위에서 설명한 것처럼 if/else와 루프에 걸쳐 흐름 민감적으로 검사되며, 완전하지는 않음 |
| 별칭/대여 검사(NLL) | 가변 배타성이 유지됨 |
| Null 가능성 강제 | `match`/`.is_null()`/`.default()`/`unsafe` 밖에서 nullable 참조를 역참조하는 것은 거부됨 |
| 경계 검사 | 배열 접근이 범위 안에 있음 |

모든 검사는 컴파일 타임에 실행됩니다. 유일한 런타임 삽입은 동적 배열 인덱스에 대한 경계 검사이며, 이는 생성된 IR에서 눈에 보입니다.

## `unsafe` 탈출구 {#the-unsafe-escape-hatch}

모든 안전 검사는 `unsafe {}` 블록 안에서 지역적으로 억제될 수 있습니다:

```c
unsafe {
    int* raw = (int*)some_addr;
    *raw = 42;       // no bounds check, no borrow check, no region check
}
```

`unsafe`는 어휘적으로 스코프가 지정됩니다. 호출된 함수로 전파되지 않습니다. 수동 감사가 필요한 코드를 위한 검색 가능한(grep-able) 마커 역할을 합니다.

## FFI 경계 규칙 {#ffi-boundary-rules}

외부 함수 호출(C 상호운용)은 특정 안전 규칙을 따릅니다:

- `extern` 선언은 원시 C 타입을 사용합니다 — 리전 한정자 없음
- `&static T`에서 `T*`로의 강제 변환은 `unsafe {}` 없이 안전합니다
- `&T`/`?&T`(리전 한정자가 전혀 없는, "outliving reference" — [메모리와 리전](/ko/reference/memory) 참고)도
  양방향으로 `T*`/`void*`와의 강제 변환이 `unsafe {}` 없이 안전합니다 —
  호출이 반환된 이후에도 C 쪽에서 보유할 수 있는 포인터를 위한 타입입니다
- 정적이지 않고 리전이 없지도 않은 참조를 C에 전달하려면 `unsafe {}`가 필요합니다
- C로부터 받은 원시 포인터는 `&T`/`?&T` 타입 변수로 직접 받는 경우가 아니라면 `unsafe {}` 안에서 처리해야 합니다

이러한 규칙들은 안전한 SafeC 코드와 안전하지 않은 C 코드 사이의 경계가 항상 명시적임을 보장합니다.

## 형식적 토대 {#formal-foundations}

안전 모델은 확립된 타입 이론에 기반합니다:

- **Oxide**(Weiss 외, 2019) — place와 provenance를 가진 타입 있는 계산법으로서 Rust의 소유권과 대여를 형식화
- **Cyclone**(Grossman 외, 2002) — C와 비슷한 언어를 위한 정적 안전 보장을 갖춘 리전 기반 메모리 관리

SafeC는 이러한 형식주의들을 리전을 주요 메모리 안전 메커니즘으로 삼는 C 호환 언어에 맞게 조정합니다.

### Progress와 Preservation {#progress-and-preservation}

타입 시스템은 표준적인 구문적 안전성 속성을 만족합니다:

- **Progress**: 타입이 올바른 프로그램은 값이거나, 한 단계를 진행할 수 있거나, 승인된 런타임 오류(abort를 동반한 경계 검사 실패)입니다
- **Preservation**: 타입이 올바른 프로그램이 한 단계를 진행하면, 결과 프로그램도 타입이 올바릅니다

이 두 속성이 함께, 타입이 올바른 SafeC 프로그램이 `unsafe {}` 블록 밖에서는 정의되지 않은 동작을 보이지 않음을 보장합니다.
