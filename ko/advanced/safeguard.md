# 패키지 매니저

`safeguard`는 공식 SafeC 패키지 매니저이자 빌드 시스템입니다. 정상 동작하는 `safec` 컴파일러와 `clang` 링커 외에는 런타임 의존성이 없는 독립형 C++17 바이너리입니다.

## 명령어 {#commands}

| 명령어 | 설명 |
|---------|-------------|
| `safeguard new <name> [--lang L] [--std]` | `Package.toml`과 `src/main.sc`를 갖춘 새 프로젝트 디렉터리를 스캐폴딩(`--lang c`/`cpp`/`objc`/`objcpp`를 주면 [C, C++ 또는 Objective-C 패키지](#c-c-and-objective-c-packages)) |
| `safeguard init [--lang L] [--std]` | 현재 디렉터리에 `Package.toml` 초기화 |
| `safeguard fetch` | `Package.toml`에 나열된 git 의존성을 클론하고 소스 의존성을 빌드 |
| `safeguard build [--release]` | 프로젝트와 모든 의존성 컴파일 |
| `safeguard check` | 빠른 컴파일 전용 패스 — 오브젝트 어셈블이나 링킹 없이 오류만 보고 |
| `safeguard std-modules [std/x.h\|file...]` | 빌드가 컴파일하는 std 소스와 그 소스를 위해 링크하는 시스템 라이브러리를 나열([표준 라이브러리 모듈](#standard-library-linking) 참고) |
| `safeguard std-prebuilt <prefix>` | 이 머신용 std를 섹션마다 아카이브 하나씩 `<prefix>/lib/<triple>/std/<section>.a`로 컴파일. 릴리스가 함께 배포하는 [미리 빌드된 std](#prebuilt-std) |
| `safeguard test` | `tests/` 아래의 모든 파일을 독립적인 바이너리로 빌드하고 실행 |
| `safeguard run` | 프로젝트 바이너리를 빌드하고 실행 |
| `safeguard bundle [--sign <identity>]` | 바이너리를 빌드하여 애플리케이션으로 패키징: `.app`, 앱 폴더 또는 `.deb`([앱 번들](#app-bundles) 참고) |
| `safeguard clean` | `build/` 디렉터리와 모든 산출물 제거 |
| `safeguard verify-lock` | `Package.lock`을 현재 의존성 상태와 비교하여 검사 |
| `safeguard lint [--verbose]` | 모든 `.sc` 소스 파일에 대해 정적 분석 린트 패스 실행 |
| `safeguard format [--check]` | 모든 `.sc`/`.h` 소스 파일의 들여쓰기와 공백을 재정렬 |

## `check` — 빠른 컴파일 전용 피드백 {#check-fast-compile-only-feedback}

Rust의 `cargo check`와 비슷합니다: 모든 소스 파일을 전체 프론트엔드
(`.sc`의 경우 `safec`의 Preprocessor→Lexer→Parser→Sema→ConstEval — 따라서
모든 타입 오류, 대여 검사 위반, 리전 이스케이프 오류가 여전히 잡힙니다;
`.c`/`.cpp`의 경우 `clang -fsyntax-only`)로 통과시키지만, 오브젝트
파일 어셈블과 링킹을 완전히 건너뛰며, 표준 라이브러리 아카이브도
빌드하지 않습니다 — `check`는 `std/`의 *헤더*만 include 경로에
필요할 뿐, 링크 가능한 구현체는 필요하지 않습니다. 이것이 보통
`build`의 실행 시간 대부분을 차지하므로, "뭔가 깨뜨렸는지" 확인하는
빠른 내부 루프 명령어는 `check`이고, `build`/`run`은 실제로 바이너리가
필요할 때를 위해 남겨둡니다.

## `test` — 통합 테스트 {#test-integration-tests}

`tests/`는 `safeguard test`에게 있어 Rust의 `tests/` 디렉터리가
`cargo test`에게 있는 것과 같은 존재입니다: 각 파일은 자체 `main()`을
가진 독립적이고 단독으로 실행 가능한 프로그램입니다 — SafeC에는 그런
속성(attribute)이 없으므로, 하나의 바이너리에서 추출된 `#[test]`가
표기된 함수들의 집합이 아닙니다. `tests/` 아래의 모든 파일은 (메인
프로젝트와 동일한 stdlib 및 `[[dependencies]]`에 링크되어) 빌드되고
실행되며, 바이너리가 `0`으로 종료하면 테스트를 통과한 것입니다. 이는
[`std::test`](/ko/stdlib/testing)의 `TestSuite`와 자연스럽게 조합됩니다 —
`test_run_and_exit()`은 테스트 파일 `main()`의 마지막 줄이 되도록
설계되었으며, 일련의 어서션을 `safeguard test`가 확인하는 단일
성공/실패 종료 코드로 변환합니다:

```c
// tests/math_test.sc
#include <std/test/test.h>

void addition_works() {
    std::test_assert_eq_i(2 + 2, 4, "2+2 == 4", "math_test.sc", 4);
}

int main(void) {
    struct TestSuite t = std::TestSuite::new();
    t.add("addition works", addition_works);
    std::test_run_and_exit(&t);   // exits 1 on any failure
}
```

::: tip 함수형 매크로를 가진 `test.h`가 어떻게 컴파일되는가
`<std/test/test.h>`는 헤더 맨 위에서 여러 함수형 매크로(`ASSERT_EQ`,
`ASSERT_TRUE` 등)를 무조건 `#define`합니다. 함수형 매크로 정의를 담은
헤더를 단순히 *포함*하는 것만으로도 위에서 보여준 매크로 없는 호출
스타일에서조차 `--compat-preprocessor` 모드 밖에서는 보통 실패합니다
("function-like macros are not allowed in safe mode..."). `safeguard
test`는 정확히 이런 이유로 `tests/` 아래의 모든 파일을 `--compat-preprocessor`로
컴파일합니다(이는 이 옵션 없이 `src/`를 컴파일하는 `build`/`check`와
다릅니다). 그래야 `<std/test/test.h>`를 포함할 수 있기 때문입니다 —
위 예제의 실제 `safeguard test` 실행으로 검증되었습니다.
:::

## `format` — 재들여쓰기 도구 {#format-reindenter}

(`safeguard fmt`가 더 짧은 별칭으로 허용됩니다.)

`rustfmt`나 `clang-format` 같은 완전한 AST 기반 프리티 프린터보다
의도적으로 범위가 좁습니다: `{`/`}` 중첩 깊이로부터 들여쓰기를
재계산하고, 후행 공백을 제거하고, 탭을 정규화하고, 연속된 빈 줄을
축소하지만 — 문자열/주석 *내용*, 한 줄 내의 수평 간격, 줄 바꿈에는
전혀 손대지 않습니다. 이런 범위가 선택된 이유는 `safec` 자체의 렉서가
토큰화 중에 주석을 완전히 폐기하기 때문입니다([컴파일러 아키텍처](/ko/advanced/compiler)
참고) — 포매터가 왕복할 수 있는 무손실 AST가 없으므로, 원시 소스
텍스트로부터 직접 작업하며, 그 안에서는 안전한 재들여쓰기는 다루기
쉽지만 임의의 리플로우는 주석/문자열 내용을 손상시킬 위험이 있습니다.
`--check`는 어떤 파일이 변경될지 기록하지 않고 보고합니다(`cargo fmt
--check` / `gofmt -l`과 일치), CI 게이트로 유용합니다.

## `lint` — 정적 분석 {#lint-static-analysis}

`cargo clippy`의 정신을 따라: 컴파일만으로는 확인할 수 없는 실제 문제를
잡아냅니다. 원시 소스에 대한 휴리스틱 스캔과 `safec --dump-ast`
자체의 미사용 변수 경고를 결합합니다. `safeguard analyze`는 `safeguard
lint`의 별칭으로 허용되며 — 둘 다 동일한 패스를 실행합니다.

| 코드 | 수준 | 설명 |
|------|-------|-------------|
| SA000 | error | 소스 파일을 열 수 없음 |
| SA001 | warning | 파일에 `unsafe {}` 블록이 5개를 초과하여 포함됨 — 리팩터링 고려 |
| SA002 | note | `alloc()`/`malloc()` 결과가 같은 줄에서 null 검사되지 않음 |
| SA003 | warning | 미사용 변수 (`safec --dump-ast`에서 전달됨) |
| SA004 | warning | 빈 `unsafe {}` 블록 — 아무 효과 없음 |
| SA005 | note | 해결되지 않은 `TODO`/`FIXME`/`XXX` 마커 |
| SA006 | warning | `if` 조건에서 `==` 대신 `=` 사용 |
| SA007 | warning | 같은 파일에서 중복된 `#include` |

### 출력 예시 {#example-output}

```
src/driver.sc: warning [SA001] file contains 7 unsafe{} blocks — consider refactoring
src/parser.sc:42: note [SA002] result of alloc() should be null-checked
src/parser.sc:58: warning [SA003] warning: unused variable 'tmp'
safeguard: analysis complete — 3 diagnostic(s), 0 error(s)
```

오류가 없으면 종료 코드 0을 반환하고(경고는 치명적이지 않음), 오류 수준 진단이 하나라도 발견되면 1을 반환합니다.

## 크로스 타깃 {#cross-targets}

`--target <alias|triple>`(`build`, `check`, `test`에서 사용)은 프로젝트와 그 표준 라이브러리를 다른 머신용으로 크로스 컴파일합니다. 베어메탈 별칭(`x86`, `x86_64`, `aarch32`, `aarch64`, `thumb`, `thumb2`, `arm-6m`, `arm-7m`, `arm-7em`, `arm-8m`, `arm-81m`, `rv32i`, `rv32e`, `rv64i`, `rv64e`, `wasm32`, `wasm64` — [베어메탈 타깃](/ko/reference/baremetal#targets) 참고)은 freestanding 모드를 함의하며, 임의의 LLVM 트리플도 받아들입니다.

```bash
safeguard build --target arm-7em --release
safeguard test --target rv32e        # builds every test for RV32E, doesn't run them
```

- std 아카이브는 타깃별로 `build/deps/<target>/libsafec_std.a`에, 프로젝트가 사용하는 [std 모듈](#standard-library-linking)의 해당 타깃용 변형으로 빌드됩니다(OS 전용 백엔드는 베어메탈에서 건너뜁니다).
- 오브젝트는 SafeC, C, C++, Objective-C 모두 타깃의 clang 플래그(`safec --print-target-clang-flags`)로 컴파일되고, 베어메탈 링크는 `-nostdlib -static`과 함께 `ld.lld`를 사용합니다: 링커 스크립트, 스타트업 코드, 런타임(`memcpy`, `memset`, 필요하다면 `malloc`, compiler-rt 내장 함수)은 `[build] cflags`/`libs`로 공급합니다.
- freestanding 모드가 호스트 전용으로 경고할 함수를 프로젝트가 직접 정의한다면(예: 런타임 자체의 `malloc`) 그 함수들은 `--provides`로 컴파일러에 전달되어 경고가 나지 않습니다.
- WebAssembly 타깃은 대신 라이브러리 모듈을 링크합니다: 진입점이 없고, `export_name`이 붙은 함수를 내보내며, 해결되지 않은 extern은 임포트가 되고, std 아카이브에는 `std/wasm/wasm_rt`(wasm 런타임)가 포함됩니다. [WebAssembly](/ko/reference/baremetal#wasm)를 참고하십시오.
- `test`는 테스트 바이너리를 빌드만 하고 실행하지 않으며(다른 머신용이므로), `run`은 크로스 타깃을 거부합니다.

## Package.toml {#package-toml}

모든 SafeC 프로젝트는 루트에 `Package.toml`을 가집니다:

```toml
[package]
name = "myproject"
version = "0.1.0"

[[dependencies]]
name = "mylib"
version = "https://github.com/user/mylib"
```

`[package]` 섹션은 프로젝트 이름과 버전을 정의합니다.

각 `[[dependencies]]` 항목은 의존성의 이름과 출처를 지정합니다([의존성](#dependencies) 참고).

## 의존성 {#dependencies}

의존성은 세 곳 중 하나에서 옵니다:

```toml
[[dependencies]]                              # git 저장소, deps/<name>으로 클론
name    = "mylib"
version = "https://github.com/user/mylib"

[[dependencies]]                              # 로컬 디렉터리
name    = "mylib"
path    = "../mylib"

[[dependencies]]                              # 미리 빌드된 라이브러리 파일
name    = "mylib"
lib     = ["vendor/libmylib.a"]               # .a, .so, .dylib 또는 .lib
include = ["vendor/include"]                  # 헤더 디렉터리(선택)

[[dependencies]]                              # safeguard가 빌드하는 C/C++/ObjC 프로젝트
name    = "mylib"
path    = "../mylib"                          # 또는 version = "https://..."
build   = "cmake"                             # 또는 "sources"
```

디렉터리가 제공하는 것은 가져온 것이든 로컬이든 그 내용에 따라 정해집니다:

| 디렉터리에 있는 것 | 종류 | safeguard의 동작 |
|---|---|---|
| `src/` | SafeC 패키지 | `src/`(`.sc`, `.c`, `.cpp`)를 `build/deps/lib<name>.a`로 빌드합니다. `include/`와 `src/`를 include 경로에 넣습니다 |
| `src/` 없음 | 미리 빌드된 라이브러리 디렉터리 | `lib/`, `lib64/` 또는 디렉터리 자체에서 `lib<name>.a`, `lib<name>.so`, `lib<name>.dylib` 또는 `<name>.lib`를 정적 라이브러리 우선으로 링크합니다. 그 이름의 라이브러리가 없으면, 라이브러리가 있는 첫 디렉터리의 라이브러리를 모두 링크합니다(`openssl`이라는 의존성에 `libssl`과 `libcrypto`가 있는 설치 등). `include/`를 include 경로에 넣습니다 |

항목에 `build`가 있는 디렉터리는 둘 다 아닙니다: safeguard가 직접 빌드하는 기존 C, C++ 또는 Objective-C 프로젝트입니다([소스에서 빌드하는 C/C++ 의존성](#c-c-dependencies-built-from-source) 참고).

`lib`는 라이브러리 파일을 직접 지정하며, 디렉터리 내용보다 우선합니다. `lib`와 `include` 경로는 의존성에 `version`이나 `path`가 있으면 그 디렉터리를, 없으면 프로젝트 루트를 기준으로 합니다. 가져온 저장소도 이렇게 미리 빌드된 라이브러리를 제공할 수 있습니다: `version = "https://..."`와 `lib = ["lib/macos-arm64/libmylib.a"]`. 공유 라이브러리에는 그 디렉터리로 rpath를 걸어 프로그램이 실행 시점에 찾을 수 있게 합니다. 로컬 `path`는 프로젝트 루트를 기준으로 합니다. 라이브러리, 디렉터리 또는 `include` 디렉터리가 없거나, 디렉터리에 `src/`도 라이브러리도 없으면 `safeguard fetch`와 `build`는 의존성 이름과 함께 실패합니다. 의존성 라이브러리는 표준 라이브러리보다 앞에 링크되므로 SafeC 패키지가 `std`를 쓸 수 있습니다.

Windows에서 DLL은 임포트 라이브러리를 통해 링크합니다: `<name>.lib`(또는 `lib<name>.lib`)를 다른 이름과 같은 방식으로 찾고, 그 옆의 DLL — 또는 CMake가 DLL을 설치하는 `../bin/`의 DLL — 을 `build`, `run`, `test` 후 프로그램 옆으로 복사합니다(다른 플랫폼에서 rpath가 하는 역할입니다). `lib = ["vendor/mylib.dll"]`은 그 옆의 `mylib.lib`를 링크합니다.

### 소스에서 빌드하는 C/C++ 의존성 {#c-c-dependencies-built-from-source}

safeguard 패키지가 아닌 C, C++ 또는 Objective-C 라이브러리 — CMake 프로젝트, 또는 소스 파일만 있는 디렉터리 — 도 safeguard가 직접 빌드하는 의존성이 될 수 있습니다. `build`가 빌드 방식을 지정하며, 결과는 `build/deps/<name>/`에 놓입니다:

```toml
[[dependencies]]                  # CMake 프로젝트
name       = "cppstats"
path       = "../cpplib"          # 또는 version = "https://..." (먼저 클론)
build      = "cmake"
cmake_args = ["-DCPPSTATS_TESTS=OFF"]

[[dependencies]]                  # 소스 디렉터리
name       = "nslen"
path       = "../nslen"
build      = "sources"
sources    = ["src", "extra/*.c"] # 파일, 디렉터리, '*' 패턴
defines    = ["NDEBUG", "LEVEL=2"]
cflags     = ["-fobjc-arc"]
include    = ["include"]          # 공개 헤더
frameworks = ["Foundation"]       # 필요한 macOS 프레임워크
```

| `build` | safeguard의 동작 |
|---|---|
| `"sources"` | `sources`가 지정하는 `.c`/`.cpp`/`.cc`/`.cxx`/`.m`/`.mm` 파일(기본값: `src/`를 재귀적으로, 없으면 디렉터리 바로 아래의 파일)을 `defines`(`-D`), `cflags`, `include` 디렉터리와 함께 컴파일하여 `build/deps/<name>/lib<name>.a`로 만듭니다 |
| `"cmake"` | 빌드에 쓰는 것과 같은 `clang`/`clang++`로 구성하고(`PATH`에 `ninja`가 있으면 `-G Ninja`, `--release`면 `Release`, 아니면 `Debug` — Windows에서는 디버그 CRT가 프로그램의 CRT와 섞이지 않으므로 `RelWithDebInfo`, 그리고 프로그램과 같은 공유 CRT인 `MultiThreadedDLL`), `cmake_args`를 덧붙인 뒤, 빌드하고 `build/deps/<name>/install`에 설치합니다. 라이브러리는 `lib`가 있으면 그것(설치 prefix 기준)이고, 없으면 그 `lib/`의 `lib<name>` 또는 모든 라이브러리이며, `install()` 규칙이 없는 프로젝트라면 빌드 트리의 라이브러리입니다 |

헤더는 `include`가 있으면 그 디렉터리(의존성 디렉터리 기준)에서, 없으면 `include/` — CMake는 설치된 것, `"sources"`는 의존성 자체의 것, 그것도 없으면 디렉터리 자체 — 에서 가져옵니다. 이 헤더는 SafeC와 C/C++ 소스 양쪽의 include 경로에 들어갑니다.

빌드 결과는 캐시됩니다: 의존성 디렉터리 아래의 파일이나 `Package.toml`의 설정이 지난 빌드 이후 바뀌었을 때만 다시 빌드합니다. C++ 의존성이 있으면 `clang++`가 링크 드라이버가 되고(CMake 의존성은 항상 — 무엇을 컴파일하는지 바깥에서 알 수 없고, `clang++`는 C도 똑같이 링크합니다), Objective-C가 있으면 `-lobjc`가 추가되며, `frameworks`는 macOS에서 링크됩니다. `sources`, `defines`, `cflags`는 `build = "sources"`를, `cmake_args`는 `build = "cmake"`를 필요로 하며, 그 밖의 조합은 의존성 이름을 밝힌 오류입니다. CMake 의존성은 아직 `--target`으로 크로스 컴파일할 수 없습니다.

SafeC는 이러한 라이브러리를 C 선언을 통해 호출하므로, C++ 라이브러리는 `extern "C"` 함수를 노출해야 합니다: C++ 클래스, 템플릿, 오버로드 등 맹글링된 심볼은 SafeC에서 호출할 수 없습니다. 작은 C API로 감싸십시오:

```cpp
// cpplib/include/cppstats.h — SafeC와 C++ 모두가 포함
#ifdef __cplusplus
extern "C" {
#endif
double cppstats_median(const double* values, int n);
#ifdef __cplusplus
}
#endif
```

```cpp
// cpplib/src/cppstats.cpp
#include "cppstats.h"
#include <algorithm>
#include <vector>

extern "C" double cppstats_median(const double* values, int n) {
    std::vector<double> v(values, values + n);
    std::sort(v.begin(), v.end());
    return n % 2 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2.0;
}
```

```c
// app/src/main.sc
#include <cppstats.h>
extern int printf(const char* fmt, ...);

int main() {
    double samples[5] = { 7.0, 1.0, 4.0, 9.0, 3.0 };
    unsafe { printf("median %.1f\n", cppstats_median(&samples[0], 5)); }
    return 0;
}
```

SafeC 저장소의 `examples/cpp_dep/`가 `CMakeLists.txt`를 포함한 이 프로젝트이며, `examples/objc_dep/`는 Objective-C 예제(C API 뒤의 `NSString`, macOS)입니다.

## 기능 플래그 {#feature-flags}

`Package.toml`은 Cargo 스타일의 `[features]` 테이블 — 별도의 브랜치나
빌드 스크립트를 유지하지 않고도 소스 코드를 게이팅하는, 이름이 붙은
선택적 빌드 구성 — 을 선언할 수 있습니다:

```toml
[features]
default   = ["backend"]              # --no-default-features가 아니면 활성화됨
frontend  = []
backend   = []
fullstack = ["frontend", "backend"]  # 하나의 기능을 활성화하면 다른 기능도 활성화될 수 있음
```

각 항목의 값은 함께 켜지는 *다른* 기능들의 목록입니다 — Cargo가 사용하는
"하나의 기능이 다른 기능을 끌어들일 수 있다"는 모델과 동일합니다(SafeC에는
아직 선택적 의존성 개념이 없으므로, 기능은 다른 기능을 연쇄시킬 수만
있을 뿐, `[[dependencies]]` 항목을 켜고 끌 수는 없습니다).

빌드 시점에 기능을 선택합니다:

```bash
safeguard build                                              # "default"만
safeguard build --features frontend                          # default + frontend
safeguard build --features frontend,backend                  # 쉼표로 구분, 반복 가능
safeguard build --no-default-features --features backend     # backend만
```

`build` / `check` / `test` / `run` 모두 `--features`/`--no-default-features`를
받아들입니다. safeguard는 활성화된 집합을 해석하고(억제되지 않는 한
`default`에서 시작하여, 활성화된 각 기능 자체의 하위 기능 목록을
고정점까지 따라가며) 활성화된 모든 기능을 전처리기 정의로서 `safec`에
전달합니다:

```
feature "backend"  ->  -DSAFEC_FEATURE_BACKEND=1
feature "frontend" ->  -DSAFEC_FEATURE_FRONTEND=1
```

(기능 이름의 하이픈은 밑줄이 됩니다). 프로젝트 소스는 새로운 언어
문법 없이 `safec`의 기존 전처리기로 이 정의를 게이팅합니다:

```c
#ifdef SAFEC_FEATURE_BACKEND
#include <std/http/http.h>
#include <std/http/http.h>
// ... server-only code ...
#endif
```

기능 `-D` 정의는 프로젝트 자체의 `src/`/`tests/` 소스에만 적용됩니다
— 표준 라이브러리와 가져온 의존성은 최상위 프로젝트가 어떤 기능을
활성화했는지와 상관없이 동일하게 빌드됩니다. `src/` 아래의 모든 파일은
어느 쪽이든 여전히 컴파일됩니다(기능 플래그는 무엇이 *실행*되는지를
결정하지, 무엇이 *컴파일*되는지를 결정하지 않습니다 — `#ifdef`로
제외된 함수도 평범한 C 조건부 컴파일과 마찬가지로 데드 코드로서
바이너리에 여전히 존재합니다); 이는 모델을 단순하게 유지하고 `safec`
전처리기의 나머지 부분이 이미 동작하는 방식과 일치시켜 주지만, 대신
바이너리 크기를 줄이거나 Cargo의 기능별 컴파일 방식처럼 관련 없는
빌드 오류를 건너뛰지는 못합니다. 이런 방식으로 구축된 완전한
`frontend`/`backend`/`fullstack` 프로젝트 예시는 SafeC 저장소의
`examples/fullstack_demo/`를 참고하십시오. scx 프론트엔드도 포함되어
있습니다([scx 템플릿](/ko/advanced/scx) 참고).

## 빌드 흐름 {#build-flow}

`src/`는 `.sc`, `.c`, `.cpp`/`.cc`/`.cxx`, Objective-C `.m`/`.mm` 파일을
자유롭게 섞을 수 있으며 — 각각은 확장자별로 분류되어 독자적인 오브젝트 파일로 개별
컴파일되며, 공유 번역 단위로 합쳐지지 않으므로 파일 단위 재컴파일은
순수 SafeC 프로젝트만큼 세밀하게 유지됩니다:

```
.sc sources          .c / .cpp / .m / .mm sources
    |                       |
safec --emit-llvm      clang / clang++ -c
    |                       |
clang -c ------------> .o files <-----
    |
ar ---------------------> .a static library (for deps)
    |
clang(++) link ----------> executable
```

1. 각 `.sc` 파일은 `safec`에 의해 LLVM IR(`.ll`)로 컴파일된 다음, `clang -c`에 의해 `.o`로 어셈블됩니다
2. 각 `.c`/`.cpp`/`.m`/`.mm` 파일은 `clang`/`clang++`에 의해 바로 `.o`로 컴파일됩니다 — 이들은 이미 네이티브 컴파일러 프론트엔드이므로 LLVM-IR 중간 단계가 없습니다
3. 의존성은 `ar`을 사용하여 정적 라이브러리로 아카이브됩니다(마찬가지로 혼합 언어를 인식합니다 — 의존성 자체의 `src/`도 `.sc`/`.c`/`.cpp`를 포함할 수 있습니다)
4. 최종 링크는 C++(`.cpp` 또는 `.mm`, 프로젝트 자체의 것이든 의존성의 것이든)가 하나라도 컴파일에 포함되었다면 드라이버로 `clang++`를 사용하고(C++ 런타임 — libc++/libstdc++, 예외, RTTI가 올바르게 링크되도록), 그렇지 않으면 평범한 `clang`을 사용하며, Objective-C가 있으면 `-lobjc`를 추가합니다; 어느 쪽이든 그저 평범한 오브젝트 파일을 링크하는 것입니다. SafeC의 C ABI 호환성 덕분에 `.sc`로 컴파일된 `.o`와 `.c`/`.cpp`로 컴파일된 `.o`는 오브젝트 파일 수준에서 상호교환 가능하기 때문입니다

세 언어 모두 헤더를 위해 프로젝트 자체의 `include/` 디렉터리와 각
의존성의 `include/` 디렉터리를 공유합니다 — 하지만 SafeC 자체의
`std/` 디렉터리는 아닙니다. 이는 의도적으로 `.c`/`.cpp` include
경로에서 제외되어 있습니다: `std/`의 헤더는 SafeC 자체의 전처리
모델에 맞춰진 `#define` 기반 typedef를 사용하며, 이를 실제 C/C++
컴파일러의 검색 경로에 두면 같은 이름을 가려서(예: `<cstdint>`/`<vector>`)
자체 표준 헤더를 손상시킬 수 있습니다.

### 예제: SafeC에서 C와 C++ 호출하기 {#example-calling-c-and-c-from-safec}

```c
// src/helper.c
int add_c(int a, int b) { return a + b; }
```

```cpp
// src/helper.cpp
#include <vector>
extern "C" int sum_cpp(int a, int b) {
    std::vector<int> v = {a, b};
    int total = 0;
    for (int x : v) total += x;
    return total;
}
```

```c
extern int printf(const char* fmt, ...);
// src/main.sc
extern int add_c(int a, int b);
extern int sum_cpp(int a, int b);

int main() {
    unsafe { printf("%d %d\n", add_c(2, 3), sum_cpp(2, 3)); }
    return 0;
}
```

SafeC에서 호출되는 C++ 함수는 C++ 쪽에 `extern "C"`가 필요합니다(평범한
C에서 C++를 호출할 때와 같은 요구사항입니다) — 이것이 없으면 C++ 이름
맹글링 때문에 링커가 평범한 `sum_cpp` 심볼을 결코 찾지 못합니다.

`Package.toml`의 `[build] srcs = [...]`도 (확장자를 섞어서) 파일을
명시적으로 나열할 수 있습니다, `src/` 아래 자동 발견에 의존하는 대신;
`cflags = [...]`는 모든 `clang`/`clang++` 호출(`.c`/`.cpp` 컴파일과
최종 링크 모두)에 추가됩니다.

### Objective-C {#objective-c}

`.m`(Objective-C)과 `.mm`(Objective-C++) 파일은 macOS에서 빌드되며, 다른
타깃에서는 safeguard가 파일 이름을 밝힌 오류와 함께 중단합니다. 함께
쓰는 `[build]` 키가 두 개 있습니다:

```toml
[build]
objc_arc   = true                     # .m/.mm을 -fobjc-arc로 컴파일
frameworks = ["Cocoa", "Metal"]       # 최종 링크에서 -framework <name>
```

`frameworks`는 macOS 빌드에만 적용됩니다(다른 곳에서는 안내 메시지와
함께 무시됩니다). Objective-C 클래스는 C++ 클래스와 마찬가지로 SafeC에서
보이지 않습니다: 코드에 SafeC가 호출할 C 함수를 마련하십시오.

### 플랫폼별 설정 {#per-platform-settings}

`[build.macos]`, `[build.linux]`, `[build.windows]`는 `cflags`, `libs`,
`lib_dirs`, `frameworks`를 받으며, 해당 OS(`--target`의 OS, 없으면
호스트)용으로 빌드할 때만 `[build]`의 값 뒤에 추가됩니다 — 하나의
매니페스트로 프로젝트 자체 코드가 쓰는 플랫폼마다의 라이브러리를 지정할 수
있습니다:

```toml
[build.macos]
frameworks = ["Security"]

[build.linux]
libs = ["sqlite3"]

[build.windows]
libs = ["winsqlite3", "crypt32"]
```

std 모듈 자체가 호출하는 시스템 라이브러리(`std::gui`의 Cocoa, X11 또는
user32/gdi32, GPU 백엔드의 Metal, Windows 네트워킹의 `ws2_32` 등)는 나열할
필요가 없습니다. safeguard가 빌드에 포함된 모듈에 대해 이를 링크합니다([표준
라이브러리 모듈](#standard-library-linking) 참고).

그 밖의 `[build.<name>]` 테이블이나, 이 테이블 안의 다른 키는 오류입니다.

### C, C++, Objective-C 패키지 {#c-c-and-objective-c-packages}

패키지에 SafeC가 전혀 없어도 됩니다. `safeguard new <name> --lang c`(또는
`cpp`, `objc`, `objcpp`)는 `src/main.c`(`.cpp`, `.m`, `.mm`. Objective-C
쪽은 `objc_arc = true`와 `frameworks = ["Foundation"]`도 함께)를
스캐폴딩하며, 이러한 패키지도 다른 패키지와 같이 `bin`, `staticlib`,
`cdylib`로 빌드되고 헤더도 같은 방식으로 내보냅니다. 패키지 자체의
소스에도, SafeC 소스 의존성에도 SafeC가 없으면 표준 라이브러리는
빌드되지도 링크되지도 않습니다.

std를 호출하는 C, C++, Objective-C 패키지(SafeC가 C 링키지로
`std_<name>`이라는 이름으로 내보내는 함수를 `extern`으로 선언해 호출)는
`[build] std = true`를 설정합니다. 그러면 std가 빌드되고 링크되며, std의
상위 디렉터리가 C/C++ include 경로에 들어가 헤더를 `<std/...>`로 가져올
수 있습니다(`std/` 자체는 넣지 않습니다. 그 안의 `stdint.h` 등이 시스템
헤더를 가리기 때문입니다). `safeguard new <name> --lang c --std`는 프로젝트를
만들 때 이 키를 기록합니다. `std = false`는 SafeC 소스가 있어도 std를
링크하지 않습니다. `std = "all"`은 소스가 포함하는 모듈만이 아니라 모든 std
모듈을 아카이브에 컴파일합니다([표준 라이브러리
모듈](#standard-library-linking) 참고).

```toml
[build]
edition = "2026"
std     = true      # C/C++/ObjC 패키지에서 SafeC의 std를 빌드하고 링크
```

## 외부 라이브러리 링킹과 라이브러리 출력 {#linking-external-libraries-and-library-output}

`[build]`의 네 가지 추가 키가 최종 링크 단계와 어떤 종류의 산출물이
생성되는지를 구성합니다 — `safec --emit-bin`이 직접 노출하는 것과
동일한 내부 플래그이며([컴파일러 아키텍처](/ko/advanced/compiler#linking-emit-bin)
참고), 여기서는 커맨드 라인 대신 `Package.toml`에 의해 구동됩니다:

```toml
[build]
libs       = ["m", "pthread"]   # 최종 링크에서 -l<name>
lib_dirs   = ["/usr/local/lib"] # 최종 링크에서 -L<dir>
lto        = "thin"             # "" (기본값, 꺼짐), "thin", 또는 "full"
crate_type = "bin"              # "bin" (기본값), "staticlib", 또는 "cdylib"
blas       = "auto"             # std의 sgemm이 쓸 CPU BLAS (아래 참고)
```

`blas`는 [`sgemm`](/ko/stdlib/simd#gemm)(그리고 이를 통해 `tensor_matmul`과 `Conv2D::forward`)이 쓸 CPU 벤더 BLAS를 고르며, 프로그램을 링크할 때 정해집니다. 빌드가 `std/simd/gemm.h`를 (직접, 또는 `std::ml`이나 `std::dsp`를 통해) 포함할 때만 링크됩니다:

| `blas` | 링크 대상 |
|---|---|
| `"auto"` (기본값) | AMD CPU에서는 AOCL-BLAS, Intel CPU에서는 oneMKL, macOS에서는 Accelerate — 라이브러리를 찾은 경우. 못 찾으면 없음 |
| `"aocl"` / `"mkl"` / `"openblas"` / `"accelerate"` | 그 라이브러리(못 찾으면 경고 후 std 내장 커널) |
| `"none"` | 없음: std 내장 SIMD 커널 |

라이브러리는 `AOCL_ROOT`, `MKLROOT`, `OPENBLAS_ROOT`(또는 그 아래 `lib/`, `lib_LP64/`, `lib/intel64/`)에서, 그다음 기본 설치 위치(`/opt/AMD/aocl/...`, `/opt/intel/oneapi/mkl/latest/lib`)에서 찾습니다. oneMKL은 GNU OpenMP 스레딩 계층으로 링크합니다. `SAFEC_BLAS`는 매니페스트의 선택을 덮어쓰고, `SAFEC_BLAS_LIBS`는 링크 플래그 자체를 대체합니다. 베어메탈, WebAssembly, Windows 빌드는 BLAS를 링크하지 않습니다. `--verbose`를 주면 어떤 것을 골랐는지 출력합니다.

`crate_type`은 `safeguard build`가 무엇을 생성할지 선택합니다:

| `crate_type` | 출력 | 생성 방식 |
|---|---|---|
| `"bin"` (기본값) | `build/<output>` | 링크된 실행 파일 |
| `"staticlib"` | `build/lib<output>.a`와 `build/include/` | 이 프로젝트 자체 오브젝트의 `ar`로 패킹된 아카이브(링커 없음, `-l`/`-L`/`lto` 없음 — `.a`는 링크되지 않고 이후 링크가 소비할 수 있도록 그저 번들링될 뿐입니다) |
| `"cdylib"` | `build/lib<output>.dylib`(macOS), `build/lib<output>.so`(Linux, 같은 이름의 SONAME), 또는 `build/<output>.dll`과 그 임포트 라이브러리 `build/<output>.lib`(Windows), 그리고 `build/include/` | `-shared`로 링크됨 |

라이브러리 빌드는 공개 헤더도 `build/include/`로 내보냅니다. 프로젝트의 `include/` 아래 모든 파일과 `src/` 아래의 헤더(`.h`, 그리고 C++의 `.hh`/`.hpp`/`.hxx`)를 경로를 유지한 채 복사합니다(`include/mylib/config.h` → `build/include/mylib/config.h`, `src/mylib.h` → `build/include/mylib.h`). 이는 패키지를 소스에서 빌드하는 의존자가 보게 되는 헤더와 같습니다. 이 디렉터리는 빌드할 때마다 새로 만들어지므로, 삭제한 헤더는 여기서도 사라집니다. 같은 경로의 헤더가 `include/`와 `src/` 양쪽에 있으면 오류입니다. 따라서 `build/`는 그대로 [미리 빌드된 의존성](#dependencies)으로 쓸 수 있습니다:

```toml
[[dependencies]]
name = "mylib"
path = "../mylib/build"    # libmylib.a를 링크하고 build/include를 include 경로에 추가
```

프로젝트의 `include/`는 자체 SafeC, C, C++ 소스의 include 경로에도 들어가므로, 라이브러리 소스는 사용자와 같은 방식으로 자신이 내보내는 헤더를 포함합니다.

### Windows DLL {#windows-dlls}

DLL은 지시받은 심볼만 내보내며, SafeC에는 `dllexport`가 없습니다. 그래서
Windows `cdylib`는 패키지 자체의 오브젝트 파일이 정의하는 모든 외부
함수 — `.so`나 `.dylib`가 그 오브젝트에서 노출하는 것과 같은 집합 — 를
내보냅니다. 이 목록은 `build/<output>.def`에 기록되어(`clang` 옆에 LLVM과
함께 설치되는 `llvm-nm`으로 오브젝트에서 읽습니다) 링커에 전달되며,
링커는 임포트 라이브러리 `build/<output>.lib`도 함께 생성합니다.
내보내지 않는 것은 다음과 같습니다:

- DLL에 링크된 표준 라이브러리와 의존성의 코드
- `static` 함수와 C++ 맹글링된 이름(SafeC에서 호출할 수 없습니다)
- 전역 변수 — DLL에서 데이터를 가져오려면 사용하는 쪽에
  `__declspec(dllimport)`가 필요한데, SafeC는 이를 표현할 수 없습니다.
  대신 접근자 함수를 내보내십시오.

함수를 하나도 정의하지 않는 패키지는 경고를 받고 임포트 라이브러리가
생성되지 않습니다. 의존자는 `<output>.lib`를 링크하고 DLL을 실행 파일
옆으로 복사받으므로([의존성](#dependencies)), `path = "../mylib/build"`는
Windows에서도 다른 플랫폼과 똑같이 동작합니다.

Windows(MSVC ABI) 빌드는 모두 **공유 C 런타임**을 씁니다. 오브젝트는
`-fms-runtime-lib=dll`(`/MD`)로 컴파일하고, 링크는 정적 `libcmt` 대신
`msvcrt`를 쓰며, CMake 의존성은 `MultiThreadedDLL`로 빌드합니다. 따라서
DLL과 그것을 불러오는 프로그램이 하나의 CRT와 하나의 힙을 공유하므로,
DLL 경계 한쪽에서 할당한 메모리를 다른 쪽에서 해제해도 되고 `FILE*`와
`errno`도 공유됩니다. 실행 시에는 Universal CRT와 Visual C++ 런타임 DLL이
필요하며, Windows 10 이상에는 기본으로 들어 있습니다(그 이전 시스템에는
VC++ 재배포 패키지). MinGW 타깃은 자체 런타임을 그대로 씁니다.

`safeguard run`은 `crate_type = "bin"`을 요구합니다 — 정적 또는 동적
라이브러리는 실행할 진입점이 없으며, 이를 링커에 직접 대고 실행하려
하면 혼란스러운 OS 수준 실패 대신 명확한 오류가 발생합니다.

## 앱 번들 {#app-bundles}

`safeguard bundle`은 `bin` 타깃을 빌드하여 각 플랫폼이 애플리케이션을
설치하는 형태로 패키징합니다. `[bundle]` 테이블이 그 내용을 기술합니다:

```toml
[bundle]
name               = "GuiHello"              # 기본값: 패키지 이름
identifier         = "org.example.gui-hello" # 필수, 역DNS 형식
version            = "1.0.0"                 # 기본값: 패키지 버전
icon               = "assets/icon.png"       # .png는 모든 플랫폼, .icns(macOS), .ico(Windows)
resources          = ["assets/greeting.txt", "assets/data"]  # 파일 또는 디렉터리
category           = "Utility"               # freedesktop 카테고리 또는 "public.app-category.*"
copyright          = "Copyright 2026 Example"
subsystem          = "windows"               # Windows: "windows"(기본값) 또는 "console"
min_system_version = "11.0"                  # macOS LSMinimumSystemVersion(기본값 11.0)
codesign_identity  = "-"                     # macOS: "-" 애드혹(기본값), 서명 ID, "none"
depends            = ["libsqlite3-0"]        # Linux: .deb의 추가 Depends
```

```bash
safeguard bundle                              # build/GuiHello.app, build/GuiHello/ 또는 .deb
safeguard bundle --release --sign "Developer ID Application: Example (TEAMID)"
```

| 플랫폼 | 출력 |
|---|---|
| macOS | `build/<name>.app`: `Contents/MacOS/<output>`, `Contents/Info.plist`(`CFBundleIdentifier`, `CFBundleName`, `CFBundleExecutable`, `CFBundleShortVersionString`, `CFBundleVersion`, `CFBundleIconFile`, `NSHighResolutionCapable`, `LSMinimumSystemVersion`, `LSApplicationCategoryType`), `Contents/Resources/`(아이콘과 리소스), `Contents/Frameworks/`(의존성의 `.dylib`) |
| Windows | `build/<name>/`: `<output>.exe`, 의존성의 DLL, 리소스 — 포터블 앱 폴더 |
| Linux | `build/<pkg>_<version>_<arch>.deb`와 `build/<name>.AppDir` |

**macOS.** `.png` 아이콘은 `.icns`로 변환됩니다(`sips`로 각 크기로
조정하고 `iconutil`로 묶습니다). `Contents/Frameworks`로 복사된 각 의존성
`.dylib`은 설치 이름이 `@rpath/<file>`로 바뀌고(라이브러리와 실행 파일
양쪽에서, `install_name_tool`로), 실행 파일은
`@executable_path/../Frameworks`로의 rpath와 함께 링크되므로 번들이 빌드
트리에 의존하지 않습니다. 내부 라이브러리와 번들은 차례로 `codesign`으로
서명됩니다: 기본은 애드혹이며, 실제 서명 ID는 `codesign_identity` 또는
`--sign`으로 지정하고, `"none"`이면 서명을 건너뜁니다.

**Windows.** 실행 파일은 GUI 서브시스템용으로 링크되며
(`/SUBSYSTEM:WINDOWS`와 `/ENTRY:mainCRTStartup`. 따라서 평범한 `main`이
그대로 동작하고 콘솔 창이 열리지 않습니다. `subsystem = "console"`이면
콘솔을 유지합니다), `llvm-rc`(또는 Windows SDK의 `rc`)로 컴파일한
리소스가 함께 링크됩니다: 아이콘(`.ico`, 또는 256×256 이하의 `.png`를
감싼 `.ico`), 패키지에서 가져온 버전 정보(`FileVersion`, `ProductName`,
author에서 가져온 `CompanyName` 등), 모니터별 DPI 인식과 Common Controls
v6를 선언하는 애플리케이션 매니페스트입니다.

**Linux.** `dpkg-deb`로 만든 `.deb`는 `/usr/bin/<output>`,
`/usr/share/applications/<identifier>.desktop`,
`/usr/share/icons/hicolor/<w>x<h>/apps/<identifier>.png`(`.png`
아이콘), `/usr/share/<pkg>/` 아래의 리소스, `/usr/lib/<pkg>/` 아래의
의존성 공유 라이브러리(실행 파일의 rpath에 있습니다)를 설치합니다.
`DEBIAN/control`에는 `Package`(데비안 형식의 패키지 이름. `_`는 `-`가
됩니다), `Version`, `Architecture`(`dpkg --print-architecture`),
`Maintainer`(패키지의 `author`), `Description`, `Installed-Size`,
`Depends`가 들어갑니다. `Depends`는 `[bundle] depends`에, std 모듈이 링크하는
시스템 라이브러리의 패키지(X11의 `std::gui`라면 `libx11-6`)를 더한 것입니다. 같은 트리에 `AppRun`, 데스크톱 항목, 아이콘을
최상위에 더한 것이 `build/<name>.AppDir`이며, `appimagetool`에 바로 넘길
수 있습니다. `dpkg`가 없으면 경고와 함께 AppDir만 만듭니다.

GUI 프로그램은 [`std::gui`](/ko/stdlib/gui)를 사용하며, 그 백엔드는
플랫폼마다의 라이브러리를 호출합니다. safeguard는 이를 스스로 링크합니다(Cocoa와
Objective-C 런타임, X11, 또는 user32와 gdi32). SafeC 저장소의
`examples/gui_hello/`는 창을 열어 1초 동안 그린 뒤 다시 닫으며, 그
매니페스트는 위의 `[bundle]`에서 `depends`를 뺀 것이고 라이브러리를 하나도
지정하지 않습니다.

## 표준 라이브러리 모듈 {#standard-library-linking}

빌드는 표준 라이브러리(`std/`) 중 실제로 사용하는 부분만 컴파일하고 링크합니다. 별도의 설정은 필요하지 않습니다. 단, [SafeC가 없는 패키지](#c-c-and-objective-c-packages)는 std를 전혀 사용하지 않습니다.

**어떤 모듈인지.** safeguard는 프로젝트 소스(그 소스가 포함하는 프로젝트 헤더, SafeC 의존성의 소스, [`std = true`](#c-c-and-objective-c-packages)이면 C, C++, Objective-C 소스까지)의 `#include <std/...>` 줄을 읽습니다. 각 std 헤더는 그것을 구현하는 std 소스를 가져오고, 그 소스의 include도 차례로 반영됩니다. 예를 들어 `std/gui/gui.h`는 macOS에서 `gui/gui_cocoa.sc`(Linux에서는 `gui_x11.sc`, Windows에서는 `gui_win32.sc`)를, `std/ml/tensor.h`는 텐서 코어와 그것이 쓰는 `sgemm`, 스레딩을 가져옵니다. `safeguard test`는 `tests/` 아래의 파일에 대해 같은 일을 합니다. `#if` 안의 include도 반영하지만, 조건이 `__APPLE__`, `_WIN32`, `__linux__`를 단순히 검사하는 경우(타깃으로 판정합니다)와, 프로젝트 자체 파일에서 `SAFEC_FEATURE_<NAME>` 매크로를 검사하는 경우([기능](#feature-flags)으로 판정합니다)는 예외입니다. `std::ml`의 GPU 디바이스 백엔드는 해당 기능과 함께 들어옵니다. `--features mps`는 `device_mps.sc`를, `rocm`과 `cuda`는 각자의 백엔드를 빌드합니다.

**코드가 호출하는 것.** std의 일부는 어떤 헤더도 지정하지 않는 코드에서 호출됩니다. safec는 `new`를 `std/mem.sc` 호출로, 채널(`chan_create` 등)을 `std/sync/channel.sc` 호출로, Linux와 macOS의 `spawn`/`join`을 `pthread_create`/`pthread_join` 호출로 컴파일합니다. safeguard는 자신이 컴파일하는 IR — 프로젝트 소스, SafeC 의존성의 소스, 빌드에 포함된 std 소스 — 의 외부 심볼을 읽어, 그 코드가 호출하는 std 소스와 그 소스가 다시 포함하고 호출하는 것을 추가합니다. 채널도 스레드도 쓰지 않는 프로그램은 `sync/channel.sc`도 `thread.sc`도 컴파일하지 않으며 Linux에서 `-lpthread`도 링크하지 않습니다. 채널을 쓰는 프로그램은 둘 다 가져오고, `spawn`만 써도 `-lpthread`를 위해 `thread.sc`를 가져옵니다.

**캐시.** 각 std 소스는 `build/deps/std/`([크로스 타깃](#cross-targets)이면 `build/deps/<target>/std/`) 아래에 자체 오브젝트로 컴파일되며, 그 소스나 그것이 포함하는 std 헤더, 또는 컴파일러가 바뀔 때만 다시 컴파일됩니다. 빌드의 오브젝트는 `build/deps/libsafec_std.a`로 묶이고, 링커는 여전히 프로그램이 참조하는 것만 가져옵니다. std는 빌드가 디버그 빌드이든 `--release` 빌드이든 항상 [미리 빌드된 std](#prebuilt-std)와 같은 플래그(`-O2`, macOS에서는 `-mmacosx-version-min=11.0` 또는 설정된 `MACOSX_DEPLOYMENT_TARGET`)로 최적화하여 컴파일합니다. 두 빌드가 이 오브젝트를 공유하므로 디버그 빌드에서도 std가 느려지지 않습니다. 프로젝트 자체의 코드는 여전히 빌드 모드를 따릅니다. (이 규칙 이전에 `-O0`으로 컴파일된 오브젝트는 한 번 다시 컴파일됩니다.) `std/io.h`를 쓰는 hello world는 219개 전체 대신 4개의 std 소스만 컴파일하며(M1 Pro에서 15초 대신 0.4초), std 헤더를 포함하지 않고 위의 것도 호출하지 않는 프로그램은 하나도 컴파일하지 않습니다. [릴리스 설치본](#prebuilt-std)에서는 이마저도 전혀 컴파일하지 않습니다.

**시스템 라이브러리.** std 모듈이 호출하는 라이브러리는 빌드에 포함된 모듈에 대해서만, 오브젝트와 아카이브 뒤에 중복 없이 링크에 추가됩니다:

| 모듈 | macOS | Linux | Windows |
|---|---|---|---|
| `std/math.h`, `std/fenv.h` | `-lm` | `-lm` | (C 런타임에 포함) |
| `std/thread.h`, 채널, `spawn` | — | `-lpthread` | — |
| `std/gui/gui.h` | `-framework Cocoa -lobjc` | `-lX11` (.deb: `libx11-6`) | `-luser32 -lgdi32` |
| `std/http/http.h`, `std/sched/` | — | — | `-lws2_32` |
| `std/crypto/rng.h` | — | — | `-lbcrypt` |
| `std/simd/gemm.h`(이를 통한 `std::ml`, `std::dsp` 포함) | [`[build] blas`](#linking-external-libraries-and-library-output)의 선택: 기본값은 Accelerate | `[build] blas`의 선택 | — |
| `std/ml/tensor_vdsp.h`, `tensor_blas.h` | `-framework Accelerate` | — | — |
| `std/ml/gpu_mps.h`, `--features mps` | `-framework Metal -framework Foundation -lobjc` | | |
| `std/ml/gpu_rocm.h`, `--features rocm` | | `-lamdhip64 -lhiprtc -lrocblas` | |
| `std/ml/gpu_miopen.h` / `std/dsp/fft_rocm.h` | | `-lMIOpen` / `-lhipfft` | |
| `std/ml/gpu_cuda.h`, `--features cuda` | | `-lcuda -lcublas -lcublasLt -lnvrtc` | 동일 |
| `std/ml/gpu_cudnn.h` / `std/dsp/fft_cuda.h` | | `-lcudnn` / `-lcufft` | 동일 |
| `std/ml/gpu_spirv.h` | `-lvulkan` | `-lvulkan` | `-lvulkan-1` |
| `std/ml/gpu_webgpu.h` | `-lwgpu_native` | `-lwgpu_native` | `-lwgpu_native` |

ROCm 라이브러리는 `$ROCM_PATH/lib`(기본값 `/opt/rocm/lib`)에서, CUDA 라이브러리는 `$CUDA_HOME/lib64`(기본값 `/usr/local/cuda/lib64`, Windows에서는 `$CUDA_PATH/lib/x64`)에서, Vulkan은 `$VULKAN_SDK`에서, wgpu는 `$WGPU_HOME/lib`에서 찾으며, 디렉터리가 있으면 rpath와 함께 추가합니다. 디바이스 백엔드에서 MIOpen과 cuDNN은 계속 선택 사항입니다(약한 참조이며, 없으면 합성곱은 im2col + GEMM을 사용합니다). `gpu_miopen.h`나 `gpu_cudnn.h`를 직접 포함하는 프로그램은 이를 링크합니다. C 라이브러리는 이전과 같이 컴파일러 드라이버가 제공합니다. `[build] libs`/`frameworks`와 `[build.<os>]`는 프로젝트 자체의 라이브러리를 이 뒤에 추가합니다.

`safeguard std-modules`는 빌드가 std에서 가져오는 것, 즉 소스(`unit` 줄)와 시스템 라이브러리(`link`, `.deb`의 Depends에 대해서는 `deb`)를 프로젝트에 대해, 또는 명령줄에 준 std 헤더와 파일에 대해 출력합니다:

```bash
$ safeguard std-modules std/gui/gui.h
unit std/atomic.sc
unit std/gui/gui_cocoa.sc
...
link -framework Cocoa
link -lobjc
$ safeguard std-modules --target x86_64-unknown-linux-gnu std/gui/gui.h
...
link -lX11
deb libx11-6
```

**std 전체.** `[build] std = "all"`은 모듈 도입 이전과 같이 모든 std 모듈을 아카이브에 컴파일합니다. 손으로 링크하는 아카이브(`clang prog.ll build/deps/libsafec_std.a ...`)나, 헤더를 포함하지 않고 `extern` 선언으로 std에 접근하는 코드에 사용합니다. [staticlib](#linking-external-libraries-and-library-output)은 소스가 포함하는 std 파일을 `build/lib<output>.a.std`에, 코드가 호출하는 심볼을 `build/lib<output>.a.refs`에 기록하므로, 이를 미리 빌드된 의존성으로 링크하는 프로그램도 그 모듈을 빌드합니다. 다른 방식으로 빌드된 라이브러리를 사용하는 프로그램에는 `std = "all"`이 필요합니다.

**std 작성.** std 헤더 `dir/foo.h`는 `dir/foo.sc`와, 자체 헤더가 없는 `dir/foo_<suffix>.sc` 파일(플랫폼 변형인 `gui_cocoa.sc`, `fft_f32.sc` 등)이 구현합니다. 그 밖의 관계와 시스템 라이브러리는 std 소스의 주석 줄로 선언합니다:

```c
// safeguard: implements <std/gui/gui.h>        이 파일이 구현하는 다른 헤더
// safeguard: sources <std/x/extra.sc>          (헤더에서) 이를 구현하는 다른 파일
// safeguard: feature mps                       이 기능이 켜진 빌드에서만
// safeguard: runtime-symbol __safec_chan_      코드가 이 접두사로 시작하는 심볼을 호출하면 빌드
// safeguard: runtime baremetal wasm            이 타깃에서는 항상 빌드
// safeguard: link macos -framework Cocoa -lobjc
// safeguard: link linux -L${ROCM_PATH:-/opt/rocm}/lib -lamdhip64
// safeguard: link hosted blas                  [build] blas의 선택
// safeguard: deb linux libx11-6
```

`link`나 `deb` 줄의 OS는 `macos`, `linux`, `windows`, `bsd`, `unix`(macOS, Linux, BSD), `hosted`, `baremetal`, `wasm`, `any` 중 하나입니다. CI가 실행하는 `tests/std_modules/check.py`는 각 std 헤더를 단독으로 컴파일하여, 그 헤더가 선언하는 함수나 그 헤더가 가져오는 모듈이 정의하지 않고 남긴 심볼이 그 모듈 밖의 std 소스에서 정의되면 실패합니다.

소스에서 빌드하는 각 SafeC 의존성도 비슷하게 `build/deps/lib<name>.a`로, C/C++ 의존성은 `build/deps/<name>/`으로 컴파일되며, 미리 빌드된 의존성은 있는 위치에서 링크됩니다.

### 미리 빌드된 std {#prebuilt-std}

릴리스 아카이브(따라서 `install.sh`/`install.ps1`로 설치한 것)는 해당 플랫폼용으로 이미 컴파일된 std를 섹션 단위로 함께 배포합니다. `std/`의 최상위 디렉터리마다 아카이브가 하나씩 있고, `std/` 바로 아래의 파일은 `core` 섹션입니다.

```
<prefix>/lib/aarch64-apple-darwin/std/  (x86_64-unknown-linux-gnu, x86_64-pc-windows-msvc)
├── core.a            std/*.sc: io, fmt, mem, string, math, thread, ...
├── core.units        스탬프(이를 빌드한 safec, 즉 바이너리의 해시와 타깃),
│                     각 소스의 입력 해시와 그 코드가 호출하는 심볼
├── collections.a     std/collections/
├── collections.units
├── gui.a, gui.units
├── ml.a, ml.units
└── ...               alloc, crypto, debug, dsp, encoding, fs, hal, http, interrupt,
                      ipc, kernel, net, reactive, rpc, sched, scx, serial, simd, sync, test
```

각 아카이브에는 그 섹션에서 [기능](#feature-flags)으로 제한되지 않는 소스가 위와 같이 최적화되어(macOS에서는 macOS 11.0 이상용으로) 컴파일되어 있습니다. 네이티브 빌드는 사용하는 `safec` 옆(`<bin>/../lib/<triple>/std/`)과 `$SAFEC_HOME/lib/<triple>/std/`에서 섹션을 찾습니다. 모듈은 위와 같이 정해지며, 각 모듈은 그 섹션이 설치되어 있고 스탬프가 컴파일러와 호스트에 맞으면 섹션에서 가져오고, 그렇지 않으면 컴파일합니다. 빌드는 자신의 모듈이 들어 있는 섹션만 링크합니다. `std/io.h`와 `std/collections/vec.h`를 쓰는 hello world는 `gui.a`나 `ml.a`가 아니라 `core.a`와 `collections.a`만 링크하며, std를 전혀 컴파일하지 않습니다. `std::gui` 창이나 `std::ml` 학습 루프도 마찬가지입니다.

섹션에 없는 것은 컴파일되어 `build/deps/libsafec_std.a`로 묶입니다.

- **설치되지 않은 섹션**(`install.sh --std=...`, 아래 참고): 빌드가 쓰는 그 섹션의 소스를 컴파일하며, 다른 섹션은 여전히 미리 빌드된 것을 링크합니다.
- **기능이 켜진 GPU 백엔드**(`--features mps` 등).
- **릴리스를 빌드한 뒤 바뀐 std 소스**: 이를 컴파일하고, 그 섹션은 이전 오브젝트를 뺀 복사본(`build/deps/std-prebuilt/<section>.a`)으로 링크하므로 같은 소스가 두 번 링크되지 않습니다.

`--verbose`는 어떤 섹션을 링크하는지, 어떤 소스를 왜 컴파일하는지 알려 줍니다.

```
safeguard: std modules (6): atomic.sc collections/vec.sc io.sc io_file.sc mem.sc panic.sc
safeguard: std section 'collections' is not installed in ~/safec/lib/aarch64-apple-darwin/std; compiling its units from source: std/collections/vec.sc
safeguard: linking the prebuilt std sections core from ~/safec/lib/aarch64-apple-darwin/std
```

크로스, 베어메탈, WebAssembly 빌드는 미리 빌드된 std를 사용하지 않으며, `[build] std_prebuilt = false`나 `SAFEC_STD_FROM_SOURCE=1`, 추가 컴파일러 플래그가 있는 빌드, macOS에서 `MACOSX_DEPLOYMENT_TARGET`이 11.0보다 낮은 빌드도 마찬가지입니다. 다른 컴파일러나 타깃의 스탬프가 있는 섹션은 건너뛰고 소스에서 컴파일하며, `--verbose`에서 그 이유를 알려 줍니다. macOS에서 섹션은 11.0용으로 빌드되어 있으므로, 11.0 이상을 대상으로 하는 프로그램은 "built for newer macOS version" 경고 없이 이를 링크합니다. safeguard는 자체적으로 최소 버전을 지정하지 않으며, 프로젝트 코드에는 clang의 기본값(실행 중인 macOS)이나 `MACOSX_DEPLOYMENT_TARGET`이 적용됩니다.

**섹션 선택.** `install.sh --std=core,collections,gui`는 그 섹션만 설치합니다(`install.ps1 -Std core,collections,gui`). 기본값은 `all`입니다. `--std=+ml`(`-Std +ml`)은 나중에 기존 설치본에 섹션을 추가하며, 설치한 릴리스(`<prefix>/.release`에 기록됨)에서 가져오고 그 밖의 것은 바꾸지 않습니다. std의 소스는 항상 설치되므로 어느 섹션이든 사용할 수 있으며, 설치되지 않은 섹션은 프로젝트마다 한 번 컴파일하는 시간만 듭니다.

`safeguard std-prebuilt <prefix>`가 이 섹션들을 만듭니다(릴리스 워크플로는 설치본 전체를 구성하는 `.github/scripts/package.sh`를 통해 이를 실행합니다). `tests/std_modules/check.py --prebuilt <prefix>/lib/<triple>/std`는 각 섹션에 정확히 그 섹션의 소스만 들어 있는지, 두 섹션에서 정의되는 심볼이 없는지, 각 std 헤더의 모듈이 그 섹션들만 링크해도 완전한지 검사합니다.

`SAFEC_STD_ARCHIVE=/path/to/libsafec_std.a`는 그 대신 어떤 아카이브든 지정한 것을 링크합니다. 아무것도 컴파일하지 않으며, 링커는 프로그램이 참조하는 멤버만 가져옵니다. `std/`를 찾을 수 있으면(`SAFEC_HOME`) 시스템 라이브러리는 여전히 소스가 포함하는 모듈로부터 정해집니다. `std/`가 없으면 safeguard는 어떤 모듈이 쓰이는지 알 수 없으므로 아무것도 추가하지 않습니다. 이 경우 `[build] libs`/`frameworks`에 지정하십시오.

## 환경 설정 {#environment-setup}

`safeguard`는 `SAFEC_HOME` 환경 변수를 사용하여 SafeC 툴체인을 찾습니다:

```bash
export SAFEC_HOME=/path/to/SafeC
```

이는 SafeC 저장소 루트를 가리켜야 합니다. 여기서부터 `safeguard`는 다음을 자동으로 발견합니다:

- `$SAFEC_HOME/compiler/build/safec`에 있는 `safec` 컴파일러 바이너리
- `$SAFEC_HOME/std/`에 있는 표준 라이브러리
- 설치본에서는 `$SAFEC_HOME/lib/<triple>/std/`에 있는 [미리 빌드된 std](#prebuilt-std) 섹션

## 프로젝트 구조 {#project-structure}

`safeguard new`로 생성된 일반적인 SafeC 프로젝트:

```
myproject/
├── Package.toml
├── src/
│   └── main.sc
└── build/           (safeguard build로 생성됨)
    ├── deps/
    │   ├── libsafec_std.a
    │   └── lib<dep>.a
    └── myproject    (최종 실행 파일)
```

## 예제 워크플로 {#example-workflow}

```bash
# 새 프로젝트 생성
safeguard new hello

# 프로젝트 디렉터리로 진입
cd hello

# src/main.sc 편집
# ...

# 빌드 및 실행
safeguard run

# 또는 따로 빌드
safeguard build --release
./build/hello
```

## 재현 가능한 빌드 {#reproducible-builds}

빌드가 성공할 때마다 safeguard는 다음을 고정하는 `Package.lock` 파일을 작성합니다:

- `safec` 컴파일러 바이너리(FNV-1a 64비트 해시)
- 각 의존성의 git 커밋 SHA(`git rev-parse HEAD`)
- 각 소스 파일의 콘텐츠 해시

```toml
[safec]
hash = "a3f5c8d2e1b04976"

[dep.mylib]
url     = "https://github.com/user/mylib"
git_sha = "c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2"

[sources]
"src/main.sc"   = "9f8e7d6c5b4a3f2e"
"src/utils.sc"  = "1a2b3c4d5e6f7a8b"
```

### 락 검증하기 {#verifying-a-lock}

```bash
safeguard verify-lock
```

각 의존성의 현재 HEAD가 잠긴 SHA와 일치하는지 확인합니다. 어떤 의존성이라도 벗어났다면 명령어는 경고를 출력하고 종료 코드 1로 종료합니다. 컴파일러 바이너리 해시가 변경되면 경고가 출력되지만 빌드는 차단되지 않습니다.

### 드리프트 발생 시 빌드 차단하기 {#blocking-builds-on-drift}

`safeguard build`는 컴파일 전에 자동으로 `checkLock()`을 호출합니다. 의존성 SHA 불일치는 빌드를 중단시키는 치명적 오류입니다(반면 `safec` 바이너리 해시 불일치는 위에서 본 것처럼 경고만 합니다):

```
safeguard: error: dependency 'mylib' git SHA mismatch (locked=c3d4e5f... current=a1b2c3d...)
  Run 'safeguard fetch' to update, then rebuild.
```

---

## safeguard 빌드하기 {#building-safeguard}

패키지 매니저 자체는 CMake로 빌드됩니다:

```bash
cd safeguard
cmake -S . -B build
cmake --build build
```

결과 바이너리는 `build/safeguard`입니다.
