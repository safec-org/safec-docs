# 전처리기

SafeC는 C 전처리 지시문의 안전한 부분집합을 지원하는 전처리기를
포함한다. 기본적으로 **안전 모드**로 동작하며, 이는 디버깅하기 어려운
매크로 문제로 이어지는 기능들을 제한한다. 완전한 C 매크로 지원을
위한 호환 모드도 제공된다.

## 지시문 {#directives}

### `#include` {#include}

다른 SafeC 파일이나 C 헤더를 포함한다:

```c
#include "mymodule.h"          // 로컬 인클루드 (따옴표)
#include <stdio.h>             // 시스템 인클루드 (꺾쇠 괄호)
```

시스템 C 헤더는 `clang -ast-dump=json`으로부터 함수와 typedef 선언을
추출하는 `CHeaderImporter`를 통해 네이티브로 임포트된다. 자세한 내용은
[C 상호운용](/ko/reference/ffi)을 참고.

포함 검색 경로를 추가하려면 `-I <dir>`를 사용한다.

### `#define` (객체형) {#define-object-like}

객체형 매크로는 단순한 텍스트 치환을 정의한다:

```c
#define MAX_SIZE 1024
#define VERSION "2.0"
#define DEBUG
```

안전 모드에서는 **함수형 매크로가 거부된다**:

```c
// 안전 모드에서 에러:
#define MAX(a, b) ((a) > (b) ? (a) : (b))
```

대신 `const` 변수와 제네릭 함수를 사용하라:

```c
const int MAX_SIZE = 1024;

generic<T: Numeric>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}
```

### `#undef` {#undef}

이전에 정의된 매크로를 제거한다:

```c
#define FEATURE_X
// ... FEATURE_X 사용 ...
#undef FEATURE_X
```

### `#pragma once` {#pragma-once}

헤더가 여러 번 포함되는 것을 방지한다:

```c
#pragma once

// 헤더 내용...
```

## 조건부 컴파일 {#conditional-compilation}

### `#ifdef` / `#ifndef` {#ifdef-ifndef}

매크로가 정의되어 있는지 검사한다:

```c
#ifdef DEBUG
    printf("debug: x = %d\n", x);
#endif

#ifndef NDEBUG
    assert(x > 0);
#endif
```

### `#if` / `#elif` / `#else` / `#endif` {#if-elif-else-endif}

상수 표현식을 사용한 일반적인 조건부 컴파일:

```c
#if PLATFORM == 1
    // 리눅스 전용 코드
#elif PLATFORM == 2
    // macOS 전용 코드
#else
    // 일반적인 폴백
#endif
```

### 중첩 {#nesting}

조건부 블록은 중첩될 수 있다:

```c
#ifdef FEATURE_A
    #ifdef FEATURE_B
        // A와 B 모두 활성화됨
    #else
        // A만 활성화됨
    #endif
#endif
```

## 사전 정의된 매크로 {#predefined-macros}

| 매크로 | 값 | 설명 |
|-------|-------|-------------|
| `__FILE__` | 문자열 리터럴 | 현재 소스 파일 이름 |
| `__LINE__` | 정수 리터럴 | 현재 줄 번호 |

C와 달리 SafeC는 재현 가능한 빌드를 지원하기 위해 의도적으로
`__TIME__`과 `__DATE__`를 생략한다.

## 명령줄 플래그 {#command-line-flags}

### `-D <name>[=value]` {#-d-namevalue}

명령줄에서 매크로를 정의한다:

```bash
./build/safec program.sc -D DEBUG -D MAX_SIZE=2048 --emit-llvm -o program.ll
```

### `-I <dir>` {#-i-dir}

포함 검색 디렉터리를 추가한다:

```bash
./build/safec program.sc -I ./include -I ../lib/include --emit-llvm -o program.ll
```

### `--no-import-c-headers` {#--no-import-c-headers}

네이티브 C 헤더 임포트 메커니즘을 비활성화한다. 이 플래그를 사용하면
`#include <stdio.h>`가 AST 추출을 위해 clang을 호출하지 않는다.
`extern` 선언을 직접 제공해야 한다.

### `--compat-preprocessor` {#--compat-preprocessor}

완전한 C 전처리기 기능을 갖춘 호환 모드를 활성화한다(아래 참고).

## 안전 모드 대 호환 모드 {#safe-mode-vs-compatibility-mode}

### 안전 모드 (기본값) {#safe-mode-default}

기본 전처리기 모드는 C에서 흔한 버그의 원인이 되는 기능을 제한한다:

| 기능 | 안전 모드 | 근거 |
|---------|-----------|-----------|
| 객체형 `#define` | 허용됨 | 단순한 상수 치환 |
| 함수형 매크로 | **거부됨** | 대신 `const` + 제네릭 사용 |
| `##` (토큰 붙이기) | **거부됨** | 코드를 난독화함 |
| `#` (문자열화) | **거부됨** | 코드를 난독화함 |
| `#include` | 허용됨 | 파일 포함은 필수적 |
| `#ifdef` / `#if` | 허용됨 | 조건부 컴파일은 필수적 |
| `#pragma once` | 허용됨 | 헤더 가드 |

### 호환 모드 (`--compat-preprocessor`) {#compatibility-mode---compat-preprocessor}

레거시 C 코드나 완전한 매크로 지원이 필요한 헤더를 다룰 때는 호환
모드를 활성화한다:

```bash
./build/safec legacy.sc --compat-preprocessor --emit-llvm -o legacy.ll
```

호환 모드에서는 함수형 매크로, 토큰 붙이기, 문자열화가 모두 허용된다.

## 컴파일 타임 대안 {#compile-time-alternatives}

SafeC는 대부분의 전처리기 사용 사례를 대체하는 일급 언어 기능을
제공한다:

| C 전처리기 패턴 | SafeC 대안 |
|----------------------|-------------------|
| `#define PI 3.14159` | `const double PI = 3.14159;` |
| `#define MAX(a,b) ...` | `generic<T> T max(T a, T b) { ... }` |
| `#ifdef DEBUG` | `if const (DEBUG) { ... }` |
| `#define ARRAY_SIZE 100` | `const int ARRAY_SIZE = 100;` |
| 조건부 함수 본문 | `consteval` 조건과 함께 `if const` |

매크로 대신 언어 수준의 구성을 사용하면 다음이 제공된다:
- **타입 안전성**: `const`와 제네릭은 타입 검사됨
- **스코핑**: `const` 변수는 어휘적 스코프를 따름
- **디버깅 용이성**: 눈에 보이지 않는 텍스트 치환이 없음
- **IDE 지원**: 정의로 이동, 이름 바꾸기, 호버가 모두 올바르게 동작함

## 예시 {#example}

```c
#pragma once
#define VERSION 1

#ifdef DEBUG
    #define LOG_LEVEL 3
#else
    #define LOG_LEVEL 0
#endif

const int MAX_CONNECTIONS = 128;

// 타입이 있는 상수에는 #define보다 const를 선호
const double TIMEOUT_SEC = 30.0;

// 함수형 매크로보다 제네릭 함수를 선호
generic<T: Numeric>
T clamp(T val, T lo, T hi) {
    if (val < lo) return lo;
    if (val > hi) return hi;
    return val;
}
```
