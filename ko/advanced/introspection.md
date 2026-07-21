# 컴파일 타임 인트로스펙션

SafeC는 컴파일 타임에 타입과 표현식을 검사하기 위한 여러 내장 연산자를 제공합니다. 이들은 의미 분석 중에 해결되며 상수 값을 만듭니다 — 런타임 리플렉션이나 RTTI는 관여하지 않습니다.

## `sizeof` {#sizeof}

타입이나 표현식의 크기를 바이트 단위로 반환합니다. 결과는 `long` 타입의 컴파일 타임 상수입니다.

```c
long s1 = sizeof(int);            // 4
long s2 = sizeof(double);         // 8
long s3 = sizeof(char);           // 1

struct Point { double x; double y; };
long s4 = sizeof(Point);          // 16

int arr[10];
long s5 = sizeof(arr);            // 40 (10 * sizeof(int))
```

표현식에 적용하면, `sizeof`는 표현식을 평가하지 않고 그 타입의 크기를 반환합니다:

```c
int x = 42;
long s = sizeof(x);               // 4 (int의 크기, x는 평가되지 않음)
```

## `alignof` {#alignof}

타입의 정렬 요구사항을 바이트 단위로 반환합니다. 결과는 컴파일 타임 상수입니다.

```c
long a1 = alignof(char);          // 1
long a2 = alignof(int);           // 4
long a3 = alignof(double);        // 8
long a4 = alignof(long long);     // 8
```

정렬 값은 플랫폼에 따라 다르며, SIMD, 캐시 라인 최적화, 하드웨어 요구사항을 위한 올바른 정렬을 보장하기 위해 `align(N)` 속성과 함께 유용합니다:

```c
// double의 자연스러운 정렬(대부분의 플랫폼에서 8)에 맞춰 정렬된 버퍼를 할당
align(8) char buf[1024];
```

::: warning `align(N)`은 표현식이 아니라 리터럴 정수가 필요합니다
`align(alignof(double))`은 파싱되지 않습니다 — `align(N)`의 파서는
`N`에 대해 오직 리터럴 정수 토큰만 받아들이며, 표현식, `alignof(...)`
호출, `const`로 선언된 식별자는 받아들이지 않습니다. 정렬 값을
(예를 들어 `static_assert`에서) 별도로 `alignof`로 조회한 다음, 그
리터럴을 `align(N)`에 하드코딩하세요.
:::

## `typeof` {#typeof}

표현식의 타입을 컴파일 타임에 추출합니다. 표현식은 평가되지 않습니다 — 오직 그 타입만 의미 분석 중에 해결됩니다.

```c
int x = 42;
typeof(x) y = 100;                // y는 int

double arr[5];
typeof(arr[0]) val = 3.14;        // val은 double

typeof(x + 1) z = 0;             // z는 int (표현식 x + 1의 타입)
```

`typeof`는 표현식의 타입을 미리 알 수 없는 제네릭 코드와 매크로에서 특히 유용합니다:

```c
generic<T>
T double_it(T val) {
    typeof(val) result = val + val;
    return result;
}
```

::: tip
`typeof`는 전적으로 컴파일 타임에 해결됩니다. 피연산자 표현식은 결코 실행되지 않으며 — 오직 그 타입만 검사됩니다.
:::

## `fieldcount` {#fieldcount}

구조체 타입의 필드 수를 컴파일 타임 상수로 반환합니다:

```c
struct Point { double x; double y; };
struct Color { uint8_t r; uint8_t g; uint8_t b; uint8_t a; };
struct Empty {};

long n1 = fieldcount(Point);      // 2
long n2 = fieldcount(Color);      // 4
long n3 = fieldcount(Empty);      // 0
```

`fieldcount`는 구조체 타입에서만 동작합니다. 구조체가 아닌 타입에 사용하면 컴파일 오류입니다.

이는 컴파일 타임 검증과 제네릭 직렬화 패턴에 유용합니다:

```c
static_assert(fieldcount(Config) == 5, "Config struct changed — update serializer");
```

## `sizeof...(T)` {#sizeof-t}

가변 인자 제네릭 타입 팩(pack) 안의 타입 개수를 반환합니다:

```c
generic<T...>
int count_types(T... args) {
    return (int)sizeof...(T);      // sizeof...(T)는 unsigned long입니다;
                                    // 선언된 int 반환 타입에 맞추기 위해 캐스트합니다
}

int main() {
    int n = count_types(1, 2.0, 'a'); // 3
    return n;
}
```

이는 컴파일 타임 상수입니다. 가변 인자 제네릭의 세부 사항은 [제네릭](/ko/reference/generics)을 참고하세요.

::: warning
`int n = count_types(...);`는 전역 변수가 아니라 지역 변수여야 합니다 —
SafeC의 다른 리터럴이 아닌 전역 초기화식과 마찬가지로, 제네릭 함수
호출은 그 값이 컴파일 타임에 알려져 있음에도 불구하고 파일 스코프에서
컴파일 타임 상수 표현식으로 받아들여지지 않습니다.
:::

## `static_assert` {#static-assert}

컴파일 타임에 조건을 검증합니다. 조건이 거짓이면 제공된 메시지와 함께 컴파일이 실패합니다.

```c
static_assert(sizeof(int) == 4, "int must be 32-bit");
static_assert(sizeof(void*) == 8, "64-bit platform required");
static_assert(alignof(double) >= 8, "double must be 8-byte aligned");
```

`static_assert`는 파일 스코프나 함수 본문 안 어디에나 나타날 수 있습니다. 조건은 컴파일 타임 상수 표현식이어야 합니다.

### `fieldcount`와 결합하기 {#combining-with-fieldcount}

```c
struct Packet {
    uint16_t header;
    uint32_t payload;
    uint16_t checksum;
};

static_assert(sizeof(Packet) <= 64, "Packet must fit in a cache line");
static_assert(fieldcount(Packet) == 3, "Packet field count changed");
```

## `if const` {#if-const}

컴파일 타임 조건부 분기입니다. 조건은 상수 표현식이어야 합니다. 선택된 분기만 컴파일됩니다 — 다른 분기는 코드 생성 전에 제거됩니다.

```c
#include <io.h>

const int DEBUG = 1;

void log_message(const char *msg) {
    if const (DEBUG) {
        println(msg);
    }
    // DEBUG가 0이면 println 호출은 완전히 제거됩니다
}
```

이는 전처리기 `#ifdef`보다 더 강력한데, 타입 검사에 참여하기 때문입니다 — 두 분기 모두 구문적으로 유효해야 하지만, 선택된 분기만 의미적으로 유효하면 됩니다.

```c
#include <io.h>

generic<T>
void print_value(T val) {
    if const (sizeof(T) == 4) {
        print_int((int)val);
    } else if const (sizeof(T) == 8) {
        print_float((double)val);
    }
}
```

## Const-Eval 엔진 한계 {#const-eval-engine-limits}

컴파일 타임 평가기는 무한한 컴파일 시간을 방지하기 위해 리소스 한계를 강제합니다:

| 한계 | 기본값 |
|-------|---------|
| 최대 재귀 깊이 | 256회 호출 |
| 최대 루프 반복 횟수 | 루프당 1,000,000회 |
| 총 명령어 예산 | 10,000,000회 연산 |

어떤 한계든 초과하면 컴파일 오류입니다. 이 한계는 `const` 함수 평가, `consteval` 함수, `static_assert` 조건, `if const` 분기 해결에 적용됩니다.

```c
// 재귀 한계에 걸리는 경우:
consteval int bad(int n) {
    return bad(n + 1);
}
const int r1 = bad(0);             // ERROR: recursion depth limit (256) exceeded

// 루프 한계에 걸리는 경우:
consteval int slow() {
    int x = 0;
    for (int i = 0; i < 2000000; i++) {
        x += i;
    }
    return x;
}
const int r2 = slow();             // ERROR: loop iteration limit exceeded
```

이 한계를 발동시키려면 두 함수 모두 실제로 컴파일 타임에 평가되어야
합니다 — 위처럼 호출 결과를 `const` 전역 변수에 대입하면 그렇게
강제됩니다; 상수 컨텍스트에서 결코 호출되지 않는 `consteval` 함수는
const-eval 엔진을 전혀 거치지 않습니다.

## 요약 {#summary}

| 연산자 | 반환값 | 평가 시점 |
|----------|---------|-------------|
| `sizeof(T)` | 바이트 단위 크기 | 컴파일 타임 |
| `sizeof(expr)` | 표현식 타입의 바이트 단위 크기 | 컴파일 타임 |
| `alignof(T)` | 바이트 단위 정렬 요구사항 | 컴파일 타임 |
| `typeof(expr)` | 표현식의 타입 | 컴파일 타임 |
| `fieldcount(T)` | 구조체 필드 개수 | 컴파일 타임 |
| `sizeof...(T)` | 가변 인자 팩 안의 타입 개수 | 컴파일 타임 |
| `static_assert(cond, msg)` | (단언) | 컴파일 타임 |
| `if const (cond)` | (분기 선택) | 컴파일 타임 |
