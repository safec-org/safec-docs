# 패키지 매니저

`safeguard`는 공식 SafeC 패키지 매니저이자 빌드 시스템입니다. 정상 동작하는 `safec` 컴파일러와 `clang` 링커 외에는 런타임 의존성이 없는 독립형 C++17 바이너리입니다.

## 명령어 {#commands}

| 명령어 | 설명 |
|---------|-------------|
| `safeguard new <name>` | `Package.toml`과 `src/main.sc`를 갖춘 새 프로젝트 디렉터리를 스캐폴딩 |
| `safeguard init` | 현재 디렉터리에 `Package.toml` 초기화 |
| `safeguard fetch` | `Package.toml`에 나열된 모든 의존성 다운로드 |
| `safeguard build [--release]` | 프로젝트와 모든 의존성 컴파일 |
| `safeguard check` | 빠른 컴파일 전용 패스 — 오브젝트 어셈블이나 링킹 없이 오류만 보고 |
| `safeguard test` | `tests/` 아래의 모든 파일을 독립적인 바이너리로 빌드하고 실행 |
| `safeguard run` | 프로젝트 바이너리를 빌드하고 실행 |
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
    struct TestSuite t = std::test_suite_init();
    unsafe { t.add("addition works", (void*)addition_works); }
    std::test_run_and_exit(&t);   // 실패가 하나라도 있으면 1로 종료
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

각 `[[dependencies]]` 항목은 이름과 소스 URL로 의존성을 지정합니다. 의존성은 `safeguard fetch` 중에 빌드 디렉터리로 클론되는 git 저장소입니다.

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
#include <std/http/http.sc>
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
`examples/fullstack_demo/`를 참고하세요. scx 프론트엔드도 포함되어
있습니다([scx 템플릿](/ko/advanced/scx) 참고).

## 빌드 흐름 {#build-flow}

`src/`는 `.sc`, `.c`, `.cpp`/`.cc`/`.cxx` 파일을 자유롭게 섞을 수
있으며 — 각각은 확장자별로 분류되어 독자적인 오브젝트 파일로 개별
컴파일되며, 공유 번역 단위로 합쳐지지 않으므로 파일 단위 재컴파일은
순수 SafeC 프로젝트만큼 세밀하게 유지됩니다:

```
.sc sources          .c / .cpp sources
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
2. 각 `.c`/`.cpp` 파일은 `clang`/`clang++`에 의해 바로 `.o`로 컴파일됩니다 — 이들은 이미 네이티브 컴파일러 프론트엔드이므로 LLVM-IR 중간 단계가 없습니다
3. 의존성은 `ar`을 사용하여 정적 라이브러리로 아카이브됩니다(마찬가지로 혼합 언어를 인식합니다 — 의존성 자체의 `src/`도 `.sc`/`.c`/`.cpp`를 포함할 수 있습니다)
4. 최종 링크는 `.cpp` 소스가 하나라도 컴파일에 포함되었다면 드라이버로 `clang++`를 사용하고(C++ 런타임 — libc++/libstdc++, 예외, RTTI가 올바르게 링크되도록), 그렇지 않으면 평범한 `clang`을 사용합니다; 어느 쪽이든 그저 평범한 오브젝트 파일을 링크하는 것입니다. SafeC의 C ABI 호환성 덕분에 `.sc`로 컴파일된 `.o`와 `.c`/`.cpp`로 컴파일된 `.o`는 오브젝트 파일 수준에서 상호교환 가능하기 때문입니다

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
```

`crate_type`은 `safeguard build`가 무엇을 생성할지 선택합니다:

| `crate_type` | 출력 | 생성 방식 |
|---|---|---|
| `"bin"` (기본값) | `build/<output>` | 링크된 실행 파일 |
| `"staticlib"` | `build/lib<output>.a` | 이 프로젝트 자체 오브젝트의 `ar`로 패킹된 아카이브(링커 없음, `-l`/`-L`/`lto` 없음 — `.a`는 링크되지 않고 이후 링크가 소비할 수 있도록 그저 번들링될 뿐입니다) |
| `"cdylib"` | `build/lib<output>.{dylib,so,dll}` (플랫폼에 따라 다름) | `-shared`로 링크됨 |

`safeguard run`은 `crate_type = "bin"`을 요구합니다 — 정적 또는 동적
라이브러리는 실행할 진입점이 없으며, 이를 링커에 직접 대고 실행하려
하면 혼란스러운 OS 수준 실패 대신 명확한 오류가 발생합니다.

## 표준 라이브러리 링킹 {#standard-library-linking}

SafeC 표준 라이브러리(`std/`)는 자동으로 컴파일되어 `build/deps/libsafec_std.a`로 아카이브됩니다. 이 라이브러리는 별도의 수동 설정 없이 기본적으로 모든 프로젝트에 링크됩니다.

각 의존성도 비슷하게 `build/deps/lib<name>.a`로 컴파일됩니다.

## 환경 설정 {#environment-setup}

`safeguard`는 `SAFEC_HOME` 환경 변수를 사용하여 SafeC 툴체인을 찾습니다:

```bash
export SAFEC_HOME=/path/to/SafeC
```

이는 SafeC 저장소 루트를 가리켜야 합니다. 여기서부터 `safeguard`는 다음을 자동으로 발견합니다:

- `$SAFEC_HOME/compiler/build/safec`에 있는 `safec` 컴파일러 바이너리
- `$SAFEC_HOME/std/`에 있는 표준 라이브러리

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
