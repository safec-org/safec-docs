# 3장: 공통 개념

이번 장은 SafeC의 기본 어휘 — 변수, 타입, 연산자, 제어 흐름 — 를 빠르게
훑어봅니다. C를 이미 안다면 대부분 "네, 당연하죠"로 읽힐 것입니다 — 목표는
SafeC의 규칙이 C보다 *더 엄격한* 몇몇 지점을 짚어 주는 것입니다. 바로
그런 지점들이 C의 몸에 밴 습관이 의도했던 프로그램 대신 컴파일 오류를
만들어내는 곳이기 때문입니다.

## 변수 {#variables}

```c
int age = 30;
auto name_len = 5;      // 초기값으로부터 타입이 추론됨 -- int
const double pi = 3.14159;
```

`auto`는 초기값으로부터 변수의 타입을 추론할 뿐, 그 이상 특별한 것은
없습니다 — 일반 선언문과 `for` 반복문의 초기화 절에서 동작하며, 그
단일 초기화 표현식 이상의 흐름 분석은 하지 않습니다. `const`는 C의
`const`와 정확히 마찬가지로 바인딩을 불변으로 만들며, 컴파일 타임에
검사됩니다.

이어지지 않는 C 습관 하나: **SafeC에는 C 스타일의 다중 변수 선언이
없습니다**. `int a = 1, b = 2;`는 파싱되지 않습니다 — 두 개의 문으로
나누세요. `for` 반복문의 초기화 절 안에서도 마찬가지입니다:
`for (int i = 0, j = 10; ...)`도 동작하지 않습니다 — 대신 반복문 앞에서
`j`를 선언하세요.

## 타입과 변환 {#types-and-conversions}

SafeC의 기본 타입은 C가 제공하는 것과 동일한 집합입니다 — `int`,
`long`, `long long`, `short`, `char`와 그 `unsigned` 변형들, `float`,
`double`, `bool` — SafeC가 대상으로 하는 플랫폼에서 크기도 동일합니다.
SafeC가 C와 크게 다른 지점은 **암묵적 변환**입니다: C는 혼합 표현식에서
`int`와 `unsigned long` 사이를 자유롭게 변환하며("통상적인 산술 변환"),
부호가 일치하지 않으면 값의 의미를 조용히 재해석합니다. SafeC는 그렇게
하지 않습니다.

```c
unsigned long size = 10UL * sizeof(int);   // OK -- 두 피연산자 모두 unsigned long
// unsigned long bad = 10 * sizeof(int);   // 오류: int와 unsigned long이 서로 다름
```

`sizeof`는 `unsigned long`을 반환하고, 그냥 쓴 `10`은 `int`입니다.
둘을 곱하려면 양쪽이 같은 타입이어야 하므로, 리터럴에 명시적으로 `UL`
접미사를 붙여야 합니다 — 이는 이항 연산자 규칙입니다(`+`/`*`/... 의
두 피연산자는 이미 같은 타입이어야 함). 반면 대입이나 전달은 한 번의
암묵적 변환을 허용합니다: *확장(widening)* — 더 작은 숫자 타입에서 더
큰 타입으로, 정수에서 부동소수점으로 건너가는 것까지 포함해서
(`int` → `double`, `int` → `long`/`float` → `double`뿐 아니라):

```c
int x = 42;
double dx = x;             // 암묵적: int -> double 확장은 안전함

long long big = 100LL;
int small = (int)big;      // 명시적 캐스트 필요: 이건 축소(narrowing)임
```

축소(더 큰 것 → 더 작은 것, 또는 부동소수점 → 정수)는 항상 명시적
캐스트가 필요합니다 — 확장 규칙의 전체 내용은
[타입](/ko/reference/types#type-conversions)을 참고하세요.

이 규칙은 시그니처에 `size_t`/`unsigned long`을 사용하는 libc 함수를
호출할 때마다 계속 마주치게 됩니다 — 컴파일러가 두 타입이 "다르다"고
불평하는 순간 `UL` 접미사나 명시적 캐스트를 붙이는 습관을 들이세요.

### 리터럴 구문의 차이점 {#literal-syntax-gaps}

C에는 있지만 SafeC에는 없는 리터럴 형태 하나: 앞자리 `0`은 8진수를
의미하지 **않습니다** — `0777`은 8진수 511이 아니라 10진수 777입니다.
실제로 8진수 값이 필요하다면 16진수/2진수로부터 계산하거나 10진수 값을
직접 쓰세요. 2진수 리터럴은 지원되며, 16진수의 `0x`/`0X`와 마찬가지로
`0b`/`0B` 접두사를 사용합니다 — 전체 표는
[리터럴](/ko/reference/literals)을 참고하세요.

```c
unsigned int flags = 0b1100U;   // 12
unsigned int mask  = 0b1010U;   // 10
printf("%u %u %u\n", flags & mask, flags | mask, flags ^ mask);
// 8 14 6
```

## 연산자 {#operators}

산술, 비교, 논리 연산자는 모두 C에서 기대하는 것과 정확히 같습니다.

```c
int a = 7;
int b = 3;
printf("%d %d %d %d\n", a + b, a - b, a * b, a / b);  // 10 4 21 2
printf("%d\n", (a > b) && (b > 0));                    // 1
```

비트 연산자(`&`, `|`, `^`, `~`, `<<`, `>>`)도 C와 동일합니다. 새로운
것은 명시적인 오버플로 동작을 위한 연산자 집합인데, 부호 있는 정수에
대한 일반 `+`/`-`/`*`는 여전히 C의 "오버플로 시 정의되지 않은 동작"
규칙을 그대로 따르기 때문입니다(부호 없는 정수는 C와 마찬가지로 여전히
래핑됩니다):

```c
int max = 2147483647;         // INT_MAX
int wrapped   = max +| 1;     // 래핑 덧셈: -2147483648 (2의 보수 래핑)
int saturated = max +% 1;     // 포화 덧셈: 2147483647 (클램프, 래핑 안 됨)
```

`+|`/`-|`/`*|`는 항상 래핑됩니다(정의된 2의 보수 동작이며, 해시,
체크섬, 링 버퍼 인덱스에 유용합니다). `+%`/`-%`/`*%`는 항상 포화됩니다
(타입의 최소/최대값으로 클램프하며, 오디오/신호 처리처럼 "래핑되는 것"보다
"표현 가능한 가장 가까운 값"이 나은 곳에 유용합니다). 전체 연산자
표는 [오버플로 연산자](/ko/reference/overflow)를, 특정 방식으로 오버플로를
해소하는 대신 오버플로를 감지하는 방법은 `std::checked_mul_size`/
`<stdckdint.h>`를 참고하세요.

## 제어 흐름 {#control-flow}

`if`/`else`, `while`, C 스타일의 `for`는 모두 C와 정확히 동일하게
동작합니다.

```c
int i = 0;
while (i < 3) {
    printf("while: %d\n", i);
    i = i + 1;
}

for (int j = 0; j < 3; j = j + 1) {
    printf("for: %d\n", j);
}

int k = 5;
if (k > 10) {
    printf("big\n");
} else if (k > 0) {
    printf("small positive\n");
} else {
    printf("non-positive\n");
}
```

SafeC에는 `match`도 있습니다 — C의 `switch`와 같은 다방향 분기 영역을
다루는 패턴 매칭 문/식이지만, `switch`의 폴스루(fall-through) 함정이
없습니다(각 케이스는 독립적이며, 다음 케이스로 떨어지지 않게 하는 데
`break`가 필요 없습니다). 폴스루가 있는 진짜 C 스타일 `switch`/`case`도
실제로 그것을 원할 때를 위해 제공됩니다 — [제어
흐름](/ko/reference/control-flow#switch-statement)을 참고하세요 — 하지만
`match`는 여기서 간단히 언급하고 넘어가기보다 그 자체로 다룰 가치가
있습니다. [7장](/ko/book/ch07-enums-and-match)에서 열거형, 유니온과
함께 다룹니다. 이 둘이야말로 `match`가 가장 유용하게 쓰이는 매칭 대상입니다.

다음: [4장](/ko/book/ch04-functions)에서는 함수 — 선언, 매개변수, 그리고
C에 직접적으로 대응하는 개념이 없는 몇 가지 SafeC 고유 속성(`pure`,
`inline`, `must_use`) — 을 다룹니다.
