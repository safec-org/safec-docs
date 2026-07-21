---
title: 설계 철학
---

# 설계 철학

SafeC는 모든 언어 결정에 영향을 미치는 작지만 확고한 원칙들 위에 세워져 있습니다. 이 원칙들을 이해하면 SafeC가 무엇을 하는지뿐만 아니라 왜 그렇게 하는지 — 그리고 다른 언어들이 필수적이라 여기는 특정 기능들을 왜 의도적으로 생략하는지 알 수 있습니다.

## 다섯 가지 원칙 {#the-five-principles}

### 1. 결정론 {#_1-determinism}

**동일한 입력은 항상 동일한 출력을 만듭니다. 숨겨진 런타임 변동성은 없습니다.**

오디오 처리, 임베디드 펌웨어, 비행 제어, 의료 기기와 같은 영역에서 비결정론적 동작은 단순한 버그가 아니라 안전 위험 요소입니다. SafeC는 프로그램 동작이 소스 코드와 입력 데이터에 의해 완전히 결정됨을 보장합니다. 숨겨진 변동 요인은 존재하지 않습니다.

이는 다음을 의미합니다:

- 실행을 예측 불가능하게 일시 정지시킬 수 있는 가비지 컬렉터 없음
- 제어 흐름을 바꾸는 암묵적 예외 언와인딩 없음
- 런타임에 실패할 수 있는 숨겨진 힙 할당 없음
- 런타임이 생성하는 백그라운드 스레드 없음

```c
// SafeC: 할당은 항상 명시적이고 눈에 보입니다
region SensorPool { capacity: 4096 }

&arena<SensorPool> SensorData data = new<SensorPool> SensorData;
// 할당은 경계가 정해져 있고, 결정론적이며, 실패할 수 없습니다
// (arena 메모리는 알려진 용량만큼 미리 예약됩니다)
```

### 2. 숨겨진 비용 없음 {#_2-zero-hidden-cost}

**모든 연산에는 눈에 보이고 예측 가능한 비용이 있습니다.**

SafeC는 프로그래머에게 비용을 드러내는 C의 전통을 따르되, 이를 더 확장합니다. C++가 암묵적으로 복사 생성자, 이동 생성자, 소멸자 체인을 호출할 수 있는 곳에서, SafeC는 아무것도 암묵적으로 하지 않습니다. 어떤 연산에 비용이 있다면, 그것은 소스 코드에 드러나 있습니다.

```c
// C++: 숨겨진 비용
std::vector<int> v = getVector();  // 복사? 이동? 할당?
process(v);                        // 복사? 참조? 누가 알겠는가?

// SafeC: 모든 비용이 눈에 보입니다
int buf[64];
int count = fillBuffer(buf, 64);   // buf를 채우고 개수를 반환
process(buf, count);               // 포인터를 전달, 복사 없음
```

암묵적 생성자도, 암묵적 소멸자도, 관련 없는 타입 간의 암묵적 변환도, 숨겨진 런타임 라이브러리 호출도 없습니다.

### 3. 명시적 제어 {#_3-explicit-control}

**암묵적인 메모리도, 암묵적인 변환도, 암묵적인 에러 처리도 없습니다.**

프로그래머가 메모리가 언제 할당되는지, 얼마나 오래 살아있는지, 언제 해제되는지를 결정합니다. 컴파일러는 이런 결정들이 안전한지 검증하지만, 결정을 대신 내리지는 않습니다.

```c
#include <stdlib.h>

// 메모리 라이프타임은 명시적입니다
int x = 10;                          // 스택 — 스코프 종료 시 소멸
&heap int h = (int*)malloc(4);       // 힙 — 프로그래머가 해제
arena_reset<SensorPool>();           // arena — 명시적 일괄 해제

// 에러 처리는 명시적입니다
int result = parseConfig(path);
if (result < 0) {
    printf("config error: %d\n", result);
    return result;
}
```

SafeC에는 예외가 없습니다. 에러는 값으로 반환됩니다. 프로그래머는 모든 호출 지점에서 에러를 명시적으로 처리합니다. 이는 에러 전파 경로를 예외 테이블 안에 숨기는 대신 소스 코드에 드러나게 만듭니다.

### 4. C ABI 호환성 {#_4-c-abi-compatibility}

**기본적으로 C 구조체 레이아웃, C 호출 규약, C 링키지를 사용합니다.**

SafeC는 FFI 계층을 통해 C와 상호운용하는 언어가 아닙니다. SafeC는 바이너리 수준에서 C와 호환*됩니다*. SafeC 구조체는 C 구조체 레이아웃을 갖습니다. SafeC 함수는 C 호출 규약을 사용합니다. SafeC 객체는 래퍼나 바인딩, 코드 생성 없이 C 프로젝트에 직접 링크됩니다.

```c
// 이 SafeC 구조체는 대응하는 C 구조체와 정확히 동일한 레이아웃을 갖습니다
struct Point {
    double x;
    double y;
};

// C 헤더는 네이티브로 동작합니다
#include <stdio.h>
#include <math.h>

int main() {
    Point p;
    p.x = 3.0;
    p.y = 4.0;
    printf("distance = %.2f\n", sqrt(p.x * p.x + p.y * p.y));
    return 0;
}
```

이는 SafeC를 점진적으로 도입할 수 있음을 의미합니다. 빌드 시스템을 변경하거나 인터페이스를 다시 작성하지 않고도, 기존 C 객체와 나란히 SafeC 객체를 링크하면서 한 번에 파일 하나씩 다시 작성할 수 있습니다.

### 5. 컴파일 타임 우선 설계 {#_5-compile-time-first-design}

**컴파일 타임에 알 수 있는 것은 반드시 컴파일 타임에 해결되어야 합니다.**

SafeC는 가능한 한 많은 연산을 컴파일 타임으로 밀어냅니다. 제네릭은 단형화됩니다(런타임 타입 디스패치 없음). `consteval` 함수는 컴파일 중에 실행됩니다. `static_assert`는 코드가 실행되기 전에 오류를 잡아냅니다. `if const`는 컴파일 타임에 죽은 분기를 제거합니다.

```c
consteval int factorial(int n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}

// 컴파일 타임에 계산됨 — 런타임 비용 없음
const int LOOKUP_SIZE = factorial(5);  // 120

static_assert(LOOKUP_SIZE == 120, "factorial broken");

// 제네릭 함수 — 타입별로 단형화되며, 런타임 디스패치 없음
generic<T>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}

int main() {
    int mi = max(3, 7);          // __safec_max_int 호출
    double md = max(1.5, 2.7);   // __safec_max_double 호출
    return 0;
}
```

## 결정론 계약 {#the-determinism-contract}

이 원칙들이 결합되어 구체적인 계약을 형성합니다. SafeC는 다음이 없음을 보장합니다:

| 숨겨진 동작 | SafeC의 보장 |
|---|---|
| 숨겨진 힙 할당 | 모든 할당은 명시적입니다 (`new<R>`, `malloc`) |
| 숨겨진 런타임 | 런타임 라이브러리가 필요하지 않습니다 (`--freestanding` 지원) |
| 숨겨진 패닉 | bounds check가 활성화되지 않는 한 패닉 없음 |
| 암묵적 예외 | 에러는 던져지는 객체가 아니라 반환 값입니다 |
| 백그라운드 GC | 가비지 컬렉터가 존재하지 않습니다 |
| 암묵적 소멸자 | 리소스 정리는 명시적입니다 (`defer`, 수동 `free`) |

이 계약은 지향점이 아니라 컴파일러에 의해 강제됩니다. 이 보장을 위반하는 코드는 컴파일되지 않습니다.

## 리전 기반 안전 모델 {#region-based-safety-model}

메모리 안전성에 대한 SafeC의 접근 방식은 Rust의 소유권 모델과 근본적으로 다릅니다. 소유권 이전을 추적하는 대신, SafeC는 메모리가 **어디에** 있는지를 추적합니다:

```c
// 리전 선언은 최상위 레벨에서만 가능합니다 — 사용할 함수보다 먼저 선언해야
// 하며, 처음 필요한 곳에 인라인으로 선언할 수 없습니다.
region Pool { capacity: 1024 }

int main() {
    int x = 10;
    &stack int r1 = &x;         // r1은 스택 메모리를 가리킴

    &heap int r2 = (int*)malloc(sizeof(int));
    *r2 = 20;                    // r2는 힙 메모리를 가리킴

    &arena<Pool> int r3 = new<Pool> int;
    *r3 = 30;                    // r3는 arena 메모리를 가리킴
    return 0;
}
```

각 참조는 타입 안에 자신의 리전을 담고 있습니다. 컴파일러는 이 정보를 사용하여 다음을 방지합니다:

- **댕글링 참조** — 함수에서 `&stack` 참조를 반환하는 것
- **리전 이스케이프** — 자신의 arena보다 오래 살아남는 arena 참조를 저장하는 것
- **별칭 위반** — 동일한 데이터에 대한 여러 개의 가변 참조

리전 정보는 컴파일 타임에만 존재합니다. 런타임에는 참조가 메타데이터도, 참조 카운팅도, 간접 참조도 없는 순수한 포인터입니다.

## 왜 소유권이 아닌가? {#why-not-ownership}

Rust의 소유권 모델은 강력하지만 특정한 프로그래밍 스타일을 강요합니다. 자기 참조 구조체, arena 할당 패턴, 그리고 많은 저수준 시스템 관용구들이 소유권 시맨틱과 충돌합니다.

SafeC의 리전 모델은 어떤 면에서는 더 관대하고(arena 안으로의 여러 참조는 괜찮습니다) 다른 면에서는 더 엄격합니다(리전 표기는 명시적이며 생략될 수 없습니다). 이 트레이드오프는 의도적입니다: SafeC는 **편의성**보다 **투명성**을 우선시합니다. 프로그래머는 컴파일러가 보는 것을 정확히 볼 수 있습니다.

## 왜 예외가 아닌가? {#why-not-exceptions}

예외는 제어 흐름을 숨깁니다. 실시간 시스템에서는 코드가 정확히 어떤 경로를 취할 수 있고 각 경로가 얼마나 걸리는지 알아야 합니다. 예외 언와인딩은 타이밍과 동작 모두에서 본질적으로 예측 불가능합니다.

SafeC는 에러 처리를 위해 반환 값을 사용합니다. 이는 예외보다 장황하지만, 제어 흐름은 항상 눈에 보입니다:

```c
int readSensor(int id, float* out) {
    if (id < 0 || id >= SENSOR_COUNT) return -1;
    unsafe { *out = sensorValues[id]; }   // 원시 포인터 역참조는 항상 unsafe가 필요합니다
    return 0;
}

int main() {
    float val;
    if (readSensor(0, &val) < 0) {
        printf("sensor error\n");
        return 1;
    }
    printf("sensor = %.2f\n", val);
    return 0;
}
```

## 왜 클래스가 없는가? {#why-not-classes}

C++의 클래스는 데이터, 동작, 라이프타임 관리, 다형성을 하나의 메커니즘에 묶습니다. 이는 암묵적 비용을 만듭니다: 변수를 선언하면 생성자가 실행되고, 스코프가 종료되면 소멸자가 실행되며, 가상 호출은 간접 참조를 추가하고, 상속 계층은 결합도를 만듭니다.

SafeC는 이러한 관심사를 분리합니다:

- **데이터**는 구조체(순수 데이터, C 레이아웃)로 정의됩니다
- **동작**은 구조체 메서드를 통해 부착됩니다(vtable 없음, 상속 없음)
- **라이프타임**은 리전을 통해 관리됩니다(명시적, 컴파일 타임)
- **다형성**은 제네릭을 통해 달성됩니다(단형화됨, 제로 코스트)

```c
struct Vec2 {
    double x;
    double y;

    double length() const;
    Vec2 operator+(Vec2 other) const;
};

double Vec2::length() const {
    return sqrt(self.x * self.x + self.y * self.y);
}

Vec2 Vec2::operator+(Vec2 other) const {
    Vec2 result;
    result.x = self.x + other.x;
    result.y = self.y + other.y;
    return result;
}
```

메서드는 암묵적 `self` 매개변수를 가진 함수를 위한 문법적 설탕(syntactic sugar)입니다. LLVM IR 수준에서 `v.length()`는 `Vec2_length(&v)`로 컴파일됩니다. vtable도, 동적 디스패치도, 숨겨진 비용도 없습니다.

이 입장이 남겨두는 모든 메커니즘 — 제네릭, 구조적 트레이트, vtable 없는 `fn_eval` 디스패치 프리미티브, 그리고 실제로 필요할 때 명시적으로 진짜 런타임 다형성을 구축하는 방법 — 은 [다형성과 OOP](/ko/reference/polymorphism)를 참고하세요.

## Unsafe 탈출구 {#unsafe-escape-hatch}

안전 검사는 `unsafe {}` 블록 안에서 선택적으로 비활성화할 수 있습니다. 이는 원시 포인터 조작, 하드웨어 레지스터 접근, 원시 포인터를 반환하는 C 라이브러리와의 FFI 같은 저수준 연산에 필요합니다:

```c
unsafe {
    int* raw = (int*)0x40000000;  // 메모리 매핑 I/O
    *raw = 0x01;                   // 직접 하드웨어 쓰기
}
```

`unsafe` 블록은 컴파일러와 사람 독자 모두에게 분명한 신호입니다 — 그 안의 코드가 안전 보장을 우회한다는 것입니다. 이는 안전 모델의 실패가 아니라, 정적으로 검증될 수 없는 본질적인 코드를 위한 의도적이고 눈에 보이는 탈출구입니다.

## 요약 {#summary}

SafeC의 설계는 한 문장으로 요약할 수 있습니다:

> **프로그래머가 작성한 모든 것이 실행되는 모든 것이다 — 컴파일러는 정확성을 검증하지만 아무것도 숨겨서 추가하지 않는다.**

이는 C++와 Rust의 철학과 정반대입니다. 그 언어들에서는 컴파일러가 상당한 양의 보이지 않는 코드(소멸자, drop glue, 이동 생성자, 언와인딩 테이블)를 생성할 것이라 기대됩니다. SafeC는 프로그래머가 명시적인 결정을 내릴 것이라 신뢰하며, 그 결정이 안전한지 검증하기 위해 컴파일 타임 분석을 사용합니다.

## 다음 단계 {#next-steps}

- [비교](/ko/guide/comparison) — 이 원칙들이 C, C++, Rust, Zig와 비교했을 때 어떻게 나타나는지 확인합니다
- [메모리와 리전](/ko/reference/memory) — 리전 기반 메모리 모델을 깊이 있게 살펴봅니다
- [안전성](/ko/reference/safety) — 경계 검사, 대여 분석, 이스케이프 분석
