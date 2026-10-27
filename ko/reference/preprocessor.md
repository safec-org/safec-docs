# 전처리기

SafeC는 C 전처리 지시문의 안전한 부분집합을 지원하는 전처리기를
포함합니다. 기본적으로 **안전 모드**로 동작하며, 이는 디버깅하기 어려운
매크로 문제로 이어지는 기능들을 제한합니다. 완전한 C 매크로 지원을
위한 호환 모드도 제공됩니다.

## 지시문 {#directives}

### `#include` {#include}

다른 SafeC 파일이나 C 헤더를 포함합니다:

```c
#include "mymodule.h"          // 로컬 인클루드 (따옴표)
#include <stdio.h>             // 시스템 인클루드 (꺾쇠 괄호)
```

시스템 C 헤더는 `clang -ast-dump=json`으로부터 함수와 typedef 선언을
추출하는 `CHeaderImporter`를 통해 네이티브로 임포트됩니다. 자세한 내용은
[C 상호운용](/ko/reference/ffi)을 참고.

`#include`는 **헤더만** 지정합니다. `.sc` 소스 파일을 포함하는 것은 컴파일 오류입니다: `.sc` 파일은 그 자체로 하나의 번역 단위이며, 다른 파일에 붙여 넣으면 그 안의 모든 정의가 중복됩니다 — 다중 정의 링크 오류가 나거나, `static` 상태가 서로 갈라지는 두 벌의 사본이 생깁니다. 각 `.sc`는 따로 컴파일하고 그 `.h`를 포함합니다. 모든 사용자가 인스턴스화해야 하는 제네릭 코드는 헤더 자체에 둡니다(표준 라이브러리의 컨테이너가 헤더 전용인 이유가 바로 이것입니다).

포함 검색 경로를 추가하려면 `-I <dir>`를 사용합니다.

### `#define` (객체형) {#define-object-like}

객체형 매크로는 단순한 텍스트 치환을 정의합니다:

```c
#define MAX_SIZE 1024
#define VERSION "2.0"
#define DEBUG
```

안전 모드에서는 **함수형 매크로가 거부됩니다**:

```c
// 안전 모드에서 에러:
#define MAX(a, b) ((a) > (b) ? (a) : (b))
```

대신 `const` 변수와 제네릭 함수를 사용합니다:

```c
const int MAX_SIZE = 1024;

generic<T: Numeric>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}
```

### `#undef` {#undef}

이전에 정의된 매크로를 제거합니다:

```c
#define FEATURE_X
// ... FEATURE_X 사용 ...
#undef FEATURE_X
```

### `#pragma once` {#pragma-once}

헤더가 여러 번 포함되는 것을 방지합니다:

```c
#pragma once

// 헤더 내용...
```

## 조건부 컴파일 {#conditional-compilation}

### `#ifdef` / `#ifndef` {#ifdef-ifndef}

매크로가 정의되어 있는지 검사합니다:

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

조건부 블록은 중첩될 수 있습니다:

```c
#ifdef FEATURE_A
    #ifdef FEATURE_B
        // A와 B 모두 활성화됨
    #else
        // A만 활성화됨
    #endif
#endif
```

### `#error`와 `#warning` {#error-warning}

`#error message`는 그 메시지와 함께 컴파일을 멈추고, `#warning message`는 메시지를 보고한 뒤 계속합니다. `#if` 안의 `defined(NAME)`과 함께 쓰면 헤더가 지원하지 않는 구성을 막을 수 있습니다.

```c
#if !defined(__linux__) && !defined(__APPLE__) && !defined(_WIN32) && !defined(__SAFEC_FREESTANDING__)
#error "unsupported target"
#endif
#ifndef NDEBUG
#warning "debug build"
#endif
```

`#pragma once` 외의 `#pragma`는 받아들이되 무시합니다.

## 사전 정의된 매크로 {#predefined-macros}

| 매크로 | 값 | 설명 |
|-------|-------|-------------|
| `__FILE__` | 문자열 리터럴 | 현재 소스 파일 이름 |
| `__LINE__` | 정수 리터럴 | 현재 줄 번호 |
| `__SIZEOF_POINTER__` | `4` 또는 `8` | 타깃의 포인터 너비 |
| `__SAFEC_FREESTANDING__` | `1` | `--freestanding`, 또는 베어메탈/WebAssembly `--target` 별칭 |
| `__SAFEC_COMPAT_PREPROCESSOR__` | `1` | `--compat-preprocessor`가 켜져 있음 |
| `__linux__`, `__APPLE__`, `_WIN32` | `1` | 해당 OS용 호스티드 빌드(베어메탈 타깃에서는 정의되지 않음) |
| `__x86_64__`/`__amd64__`, `__i386__`, `__aarch64__` | `1` | 타깃 아키텍처 |
| `__riscv`, `__riscv_xlen` | `1`, `32`/`64` | RISC-V 타깃 |
| `__wasm__`, `__wasm32__`/`__wasm64__` | `1` | WebAssembly 타깃 |
| `__arm__`, `__ARM_ARCH`, `__ARM_ARCH_PROFILE`, `__ARM_ARCH_<name>__`, `__thumb__`, `__thumb2__` | `1`, `6`/`7`/`8`, `'A'`/`'R'`/`'M'` | 32비트 ARM, clang과 같은 철자(`__ARM_ARCH_7EM__`, `__ARM_ARCH_8M_MAIN__`, …) |

C와 달리 SafeC는 빌드를 재현 가능하게 유지하려고 의도적으로
`__TIME__`과 `__DATE__`를 두지 않습니다: 둘 중 하나라도 쓰면 컴파일 오류입니다("__TIME__ and __DATE__ are disabled in SafeC").

## 명령줄 플래그 {#command-line-flags}

### `-D <name>[=value]` {#-d-namevalue}

명령줄에서 매크로를 정의합니다:

```bash
./build/safec program.sc -D DEBUG -D MAX_SIZE=2048 --emit-llvm -o program.ll
```

### `-I <dir>` {#-i-dir}

포함 검색 디렉터리를 추가합니다:

```bash
./build/safec program.sc -I ./include -I ../lib/include --emit-llvm -o program.ll
```

### `--no-import-c-headers` {#--no-import-c-headers}

네이티브 C 헤더 임포트 메커니즘을 비활성화합니다. 이 플래그를 사용하면
`#include <stdio.h>`가 AST 추출을 위해 clang을 호출하지 않습니다.
`extern` 선언을 직접 제공해야 합니다.

### `--compat-preprocessor` {#--compat-preprocessor}

완전한 C 전처리기 기능을 갖춘 호환 모드를 활성화합니다(아래 참고).

## 안전 모드 대 호환 모드 {#safe-mode-vs-compatibility-mode}

### 안전 모드 (기본값) {#safe-mode-default}

기본 전처리기 모드는 C에서 흔한 버그의 원인이 되는 기능을 제한합니다:

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
모드를 활성화합니다:

```bash
./build/safec legacy.sc --compat-preprocessor --emit-llvm -o legacy.ll
```

safeguard 패키지는 `[build] compat_preprocessor = true`로 자기 소스에 이 모드를 켭니다([패키지 매니저](/ko/advanced/safeguard#linking-external-libraries-and-library-output) 참고).

호환 모드에서는 함수형 매크로, 토큰 붙이기, 문자열화가 모두 허용되며, 전처리기가 `__SAFEC_COMPAT_PREPROCESSOR__`를 정의합니다. 선택적인 함수형 편의 매크로를 제공하는 헤더는 이 매크로로 그것을 감싸서, 같은 헤더가 두 모드 모두에서 동작하게 합니다 — 표준 라이브러리는 `ASSERT_*`(`std/test/test.h`), `RING_STATIC`, 로깅·패닉 헬퍼 등에서 이렇게 합니다:

```c
#ifdef __SAFEC_COMPAT_PREPROCESSOR__   // function-like macro: compat mode only
#define ASSERT_TRUE(e) test_assert_true((e) != 0, #e, __FILE__, __LINE__)
#endif
```

함수형 매크로는 자기가 선언하는 지역 변수에 대해 **위생적**입니다: 매크로 본문에서 선언한 변수는 전개될 때마다 이름이 바뀌므로, 호출한 쪽의 같은 이름 변수를 붙잡거나 가리지 않습니다.

```c
#define SWAP(a, b) { int tmp = a; a = b; b = tmp; }

int tmp = 1, x = 2;
SWAP(tmp, x);      // expands to { int __hyg1_tmp = tmp; tmp = x; x = __hyg1_tmp; }
```

매크로 본문에서 선언한 `struct`의 필드는 이름을 유지합니다.

## 컴파일 타임 대안 {#compile-time-alternatives}

SafeC는 대부분의 전처리기 사용 사례를 대체하는 일급 언어 기능을
제공합니다:

| C 전처리기 패턴 | SafeC 대안 |
|----------------------|-------------------|
| `#define PI 3.14159` | `const double PI = 3.14159;` |
| `#define MAX(a,b) ...` | `generic<T> T max(T a, T b) { ... }` |
| `#ifdef DEBUG` | `if const (DEBUG) { ... }` |
| `#define ARRAY_SIZE 100` | `const int ARRAY_SIZE = 100;` |
| 조건부 함수 본문 | `consteval` 조건과 함께 `if const` |

매크로 대신 언어 수준의 구성을 사용하면 다음이 제공됩니다:
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
