# 리터럴과 한정자

이 페이지는 SafeC의 C 호환 기반의 일부인 리터럴 문법, 저장소 한정자,
그리고 변수 어트리뷰트를 다룬다.

## 정수 리터럴 {#integer-literals}

SafeC는 여러 정수 리터럴 형식을 지원한다:

| 형식 | 접두사 | 예시 | 값 |
|--------|--------|---------|-------|
| 10진수 | (없음) | `42` | 42 |
| 16진수 | `0x` / `0X` | `0xFF` | 255 |
| 2진수 | `0b` / `0B` | `0b1010` | 10 |
| 8진수 | `0o` / `0O` | `0o17` | 15 |

::: warning `0777`은 8진수가 아니라 10진수다
C와 달리, 앞에 0이 붙은 리터럴(`0777`)은 8진수가 **아니다** — 그냥
10진수 777이다. 8진수를 쓰려면 명시적인 `0o`/`0O` 접두사(`0o777` = 511)를
사용하라 — 이는 실수로 앞에 0이 붙어 숫자의 진법이 조용히 바뀌어
버리는 C의 대표적인 함정을 피하게 해준다.
:::

### 정수 접미사 {#integer-suffixes}

접미사는 정수 리터럴의 타입을 결정한다:

| 접미사 | 타입 | 예시 |
|--------|------|---------|
| (없음) | `int` | `42` |
| `U` / `u` | `unsigned int` | `42U` |
| `L` / `l` | `long` | `42L` |
| `LL` / `ll` | `long long` | `42LL` |
| `UL` / `ul` | `unsigned long` | `42UL` |
| `ULL` / `ull` | `unsigned long long` | `42ULL` |

```c
int a = 42;
unsigned int b = 42U;
long long c = 100000LL;
unsigned long long d = 0xFFFFFFFFFFFFFFFFULL;
```

## 부동소수점 리터럴 {#float-literals}

```c
double a = 3.14;
double b = 1.5e-2;        // 0.015 (지수 표기법)
float d = 3.14f;           // float를 위한 f 접미사
```

뒤에 숫자가 없는 트레일링 점(`0.`)도 부동소수점으로 파싱된다 — `0.0`과
동일하다 — 단, 바로 뒤에 또 다른 `.`이 와서 `..`/`...` 범위/스프레드
토큰을 시작하지 않는 경우에 한한다.

## 문자 리터럴 {#character-literals}

문자 리터럴은 작은따옴표로 감싼다:

```c
char a = 'A';
char newline = '\n';
char tab = '\t';
char null = '\0';          // 널 바이트
```

### 이스케이프 시퀀스 {#escape-sequences}

| 이스케이프 | 의미 |
|--------|---------|
| `\n` | 개행 |
| `\t` | 탭 |
| `\r` | 캐리지 리턴 |
| `\\` | 백슬래시 |
| `\'` | 작은따옴표 |
| `\"` | 큰따옴표 |
| `\0` | 널 바이트 |
| `\xNN` | 16진수 바이트 (1~2자리) — `'\x41'`은 `'A'` |

`\xNN`은 문자열 리터럴에서도 동일하게 동작한다: `"A\x42\x43"`은
`"ABC"`다.

## 문자열 리터럴 {#string-literals}

문자열 리터럴은 큰따옴표로 감싸며, 암묵적으로 `&static char` 참조다
(널로 종료됨):

```c
const char *msg = "hello, world";
```

문자열 리터럴은 `extern` C 함수에 직접 전달할 수 있다. `&static`은
`unsafe` 없이 `char*`로 강제 변환되기 때문이다.

## 불리언 리터럴 {#boolean-literals}

```c
bool a = true;
bool b = false;
```

## Null 리터럴 {#null-literal}

```c
?&stack Node next = null;   // null -- 널 가능 참조와 ?T 옵셔널 모두의 빈 케이스
?int val = null;             // 동일한 'null', 별도의 'none' 리터럴이 아님
```

::: warning `none`은 리터럴이 아니라 match 패턴이다
`?int val = none;`은 컴파일되지 않는다(`none`은 선언되지 않았다) —
`null`이 `?T` 옵셔널과 `?&region T` 널 가능 참조 **둘 다**를 위한
유일한 빈 값 리터럴이다. `some(x)`/`none`은 `match` 패턴으로만
존재하며(`match (val) { case none: ... case some(x): ... }`, [제어
흐름](/ko/reference/control-flow) 참고), 일반 표현식으로는 절대
존재하지 않는다.
:::

## 저장소 한정자 {#storage-qualifiers}

### `const` {#const}

불변 바인딩을 선언한다. 값은 초기화 이후 수정될 수 없다.

```c
const int MAX_SIZE = 1024;
const double PI = 3.14159265358979;
```

함수 매개변수에 붙은 `const`는 인자의 수정을 방지한다:

```c
int length(const char *str) {
    // str의 내용은 수정될 수 없음
}
```

### `static` {#static}

지역 변수의 경우, `static`은 변수에 정적 저장소 지속 기간(static
storage duration)을 부여한다 — 함수 호출 사이에 값이 유지되며 단
한 번만 초기화된다:

```c
int call_count() {
    static int count = 0;
    count++;
    return count;
}
// 첫 호출은 1을 반환하고, 두 번째 호출은 2를 반환하는 식으로 계속됨
```

전역 변수의 경우, `static`은 가시성을 현재 번역 단위로 제한한다
(내부 링키지):

```c
static int module_state = 0;   // 이 파일 밖에서는 보이지 않음
```

### `extern` {#extern}

다른 번역 단위에 정의된 변수나 함수를 선언한다:

```c
extern int global_counter;     // 다른 곳에 정의됨
extern void c_function();      // C 함수 선언
```

### `volatile` {#volatile}

컴파일러가 읽기나 쓰기를 최적화로 없애버리는 것을 방지한다.
메모리 매핑된 I/O와 하드웨어 레지스터에 사용된다:

```c
volatile int *status_reg = (volatile int *)0x40001000;
int val = *status_reg;         // 메모리로부터의 읽기가 보장됨
*status_reg = 1;               // 메모리로의 쓰기가 보장됨
```

volatile 접근 패턴에 대한 더 자세한 내용은
[베어메탈](/ko/reference/baremetal)을 참고.

### `thread_local` {#thread_local}

스레드 로컬 저장소 지속 기간을 가진 변수를 선언한다. 각 스레드는
자신만의 사본을 갖는다:

```c
thread_local int error_code = 0;
```

C/GCC 호환을 위해 `_Thread_local`과 `__thread` 별칭도 허용된다:

```c
_Thread_local int tls_var = 0;     // C11 스타일
__thread int gcc_tls_var = 0;      // GCC 확장 스타일
```

스레드 로컬 변수는 각 스레드가 시작될 때 초기화되며 스레드마다
독립적이다.

### `atomic` {#atomic}

락 없는 동시 접근을 위한 원자적 변수를 선언한다:

```c
atomic int counter = 0;
```

원자적 연산에 대해서는 [동시성](/ko/reference/concurrency)을 참고.

## 변수 어트리뷰트 {#variable-attributes}

### `align(N)` {#alignn}

변수나 구조체 필드의 정렬 요구사항을 지정한다. `N`은 2의 거듭제곱이어야
한다.

```c
align(16) float vec[4];           // 16바이트 정렬 (SIMD에 적합)
align(64) char cache_line[64];    // 캐시 라인 정렬
```

이는 LLVM의 정렬 어트리뷰트로 매핑되며, 성능이 중요한 코드, SIMD
연산, 하드웨어 요구사항에 유용하다.

### `section("name")` {#sectionname}

변수나 함수를 특정 링커 섹션에 배치한다:

```c
section(".isr_vector") fn void() vectors[2] = { reset_handler, nmi_handler };
section(".rodata") const int lookup[256] = { /* ... */ };
```

링커 섹션 사용법에 대해서는 [베어메탈](/ko/reference/baremetal)을
참고.

## 호출 규약 {#calling-conventions}

SafeC는 시스템 API 및 외부 코드와의 상호운용을 위한 플랫폼별 호출
규약 어노테이션을 지원한다:

| 어노테이션 | 규약 | 플랫폼 |
|------------|-----------|-------|
| `__cdecl` | C 기본값 (호출자가 스택 정리) | x86 |
| `__stdcall` | 피호출자가 스택 정리 | x86 Windows |
| `__fastcall` | 첫 인자들을 레지스터로 전달 | x86 |

```c
__stdcall int WinApiCallback(int msg, int wparam, int lparam) {
    // stdcall 규약을 사용하는 Windows API 콜백
    return 0;
}

__cdecl void normal_func() {
    // 명시적인 C 호출 규약
}
```

호출 규약은 주로 x86 Windows 상호운용에서 관련이 있다. 다른 플랫폼과
아키텍처에서는 대개 기본 호출 규약으로 충분하다.

## 타입 한정자 요약 {#type-qualifiers-summary}

| 한정자 | 효과 |
|-----------|--------|
| `const` | 불변 바인딩 |
| `static` | 지속적인 저장소 (지역) 또는 내부 링키지 (전역) |
| `extern` | 외부 링키지 선언 |
| `volatile` | 읽기/쓰기 최적화 방지 |
| `atomic` | 락 없는 동시 접근 |
| `thread_local` | 스레드별 저장소 |
| `align(N)` | 커스텀 정렬 |
| `section("name")` | 링커 섹션 배치 |
