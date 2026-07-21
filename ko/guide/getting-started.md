---
title: 시작하기
---

# 시작하기

이 가이드는 SafeC 설치, 첫 프로그램 컴파일, 기본적인 컴파일 워크플로 이해까지 안내합니다.

## 설치 {#installation}

설치 스크립트를 실행하세요. 이 스크립트는 GitHub Releases에서 미리 빌드된 `safec`/`safeguard`/`sc-lsp` 릴리스를 다운로드하고 셸 환경을 구성합니다 — 설치 대상 머신에 LLVM이나 C++ 툴체인이 필요하지 않습니다.

```bash
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash
```
```powershell
irm https://raw.githubusercontent.com/safec-org/SafeC/main/install.ps1 | iex
```

### 설치 스크립트 옵션 {#install-script-options}

| 옵션 | 설명 |
|---|---|
| `--prefix=<path>` | 설치 디렉터리 (기본값: `~/safec`) |
| `--version=<tag>` | 설치할 릴리스 태그, 예: `v0.2.0` (기본값: 최신) |
| `--skip-env` | 셸 환경 구성 건너뛰기 |

macOS/Linux는 `arm64`/`x86_64` 바이너리를 배포하며, Windows는 `x86_64`를 배포합니다. 전체 에셋 목록은 [Releases](https://github.com/safec-org/SafeC/releases)를 참고하세요.

::: tip
소스에서 빌드하는 것(릴리스 워크플로가 배포하지 않는 플랫폼을 위해 필요하거나, 릴리스되지 않은 커밋에서 빌드하기 위해 필요)도 여전히 가능합니다. `compiler/`, `safeguard/`, 그리고 자매 저장소인 `sc-language-server`의 각 구성 요소 자체 `CMakeLists.txt`를 통해서입니다 — [컴파일러 아키텍처](/ko/advanced/compiler)를 참고하세요.
:::

설치를 확인하세요:

```bash
safec --help
```

## 첫 프로그램 {#your-first-program}

`safeguard`로 새 프로젝트를 만드세요:

```bash
safeguard new hello
cd hello
```

이렇게 하면 다음과 같은 구조가 생성됩니다:

```
hello/
  Package.toml
  src/
    main.sc
```

`src/main.sc`를 다음으로 교체하세요:

```c
extern int printf(const char* fmt, ...);

int main() {
    printf("Hello from SafeC!\n");
    return 0;
}
```

빌드하고 실행하세요:

```bash
safeguard build
safeguard run
```

출력:

```
Hello from SafeC!
```

## 더 완전한 예제 {#a-more-complete-example}

이 예제는 리전, 참조, 배열, 슬라이스를 보여줍니다:

```c
extern int printf(const char* fmt, ...);

int main() {
    // 명시적 리전 참조를 가진 스택 변수
    int x = 42;
    &stack int ref = &x;
    printf("x = %d\n", *ref);

    // 경계 검사가 이루어지는 배열
    int arr[5];
    arr[0] = 10;
    arr[1] = 20;
    arr[2] = 30;

    // 슬라이스 — 배열에 대한 경계 검사가 이루어진 뷰
    []int s = arr[0..3];
    printf("slice length = %ld\n", s.len);

    return 0;
}
```

## 컴파일 파이프라인 {#compilation-pipeline}

SafeC 컴파일러는 소스 파일을 다단계 파이프라인을 통해 처리합니다:

```
Source (.sc)
    |
    v
Preprocessor     #define, #include, #ifdef, -D/-I 플래그
    |
    v
Lexer            토큰화
    |
    v
Parser           재귀 하강 파싱 -> AST
    |
    v
Sema             타입 검사, 리전 분석, 대여 검사
    |
    v
ConstEval        컴파일 타임 함수 평가, static_assert
    |
    v
CodeGen          LLVM IR 생성
    |
    v
LLVM IR (.ll)    텍스트 IR 또는 비트코드 출력
    |
    v
clang/lld        네이티브 바이너리로 링크
```

## 컴파일러 플래그 {#compiler-flags}

```
safec <input.sc> [options]
```

### 출력 {#output}

| 플래그 | 설명 |
|---|---|
| `-o <file>` | 출력 파일 경로 |
| `--emit-llvm` | LLVM IR을 텍스트(`.ll`)로 출력 |

### 디버그 및 진단 {#debug-and-diagnostics}

| 플래그 | 설명 |
|---|---|
| `--dump-ast` | AST를 출력하고 종료 |
| `--dump-pp` | 전처리된 소스를 출력하고 종료 |
| `--g lines` | 라인 테이블 디버그 정보(DWARF) 출력 |
| `--g full` | 지역 변수를 포함한 전체 디버그 정보 출력 |
| `-v` | 상세 출력 |

### 컴파일 제어 {#compilation-control}

| 플래그 | 설명 |
|---|---|
| `--no-sema` | 의미 분석 건너뛰기 |
| `--no-consteval` | 컴파일 타임 평가 단계 건너뛰기 |
| `--compat-preprocessor` | 완전한 C 전처리기 호환성 활성화 |
| `--freestanding` | 프리스탠딩 모드 (표준 라이브러리를 가정하지 않음) |
| `--no-import-c-headers` | 자동 C 헤더 가져오기 비활성화 |

### 전처리기 {#preprocessor}

| 플래그 | 설명 |
|---|---|
| `-I <dir>` | include 검색 경로 추가 |
| `-D NAME[=VALUE]` | 전처리기 매크로 정의 |

### 증분 컴파일 {#incremental-compilation}

증분 컴파일(파일 단위 `.bc` 캐싱)은 **기본적으로 켜져 있습니다** — 활성화를 위한 별도 플래그가 필요하지 않습니다.

| 플래그 | 설명 |
|---|---|
| `--no-incremental` | 증분 컴파일 비활성화 (항상 처음부터 재컴파일) |
| `--cache-dir <dir>` | 캐시 디렉터리 설정 (기본값: `.safec_cache`) |
| `--clear-cache` | 캐시된 모든 `.bc` 파일을 지우고 종료 |

## C 헤더 임포트 {#c-header-import}

SafeC는 C 표준 헤더를 네이티브로 포함할 수 있습니다. 컴파일러는 C 헤더 선언을 파싱하여 수동 `extern` 스텁 없이 사용할 수 있게 합니다:

```c
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main() {
    char* msg = (char*)malloc(64);
    strcpy(msg, "Hello from C headers!");
    printf("%s\n", msg);
    free(msg);
    return 0;
}
```

자동 임포트는 포함된 헤더에 대해 `clang -ast-dump=json`을 호출하고 함수 및 typedef 선언을 추출하는 방식으로 동작합니다. 수동 `extern` 선언을 선호한다면 `--no-import-c-headers`로 비활성화하세요.

## 패키지 매니저 사용하기 {#using-the-package-manager}

의존성이 있거나 여러 소스 파일이 있는 프로젝트라면 `safeguard`를 사용하세요. 설치 스크립트가 `safec`, `sc-lsp`와 함께 설치하므로 이미 사용할 수 있습니다.

```bash
# 새 프로젝트 생성
safeguard new myproject
cd myproject

# 빌드 및 실행
safeguard build
safeguard run
```

`safeguard new`가 생성하는 프로젝트 구조:

```
myproject/
  Package.toml       # 프로젝트 매니페스트
  src/
    main.sc          # 진입점
```

설치 스크립트는 이미 `SAFEC_HOME`(설치 prefix, 기본값 `~/safec`)을 설정하고 이를 `PATH`에 추가하여 `safeguard`가 컴파일러와 표준 라이브러리를 자동으로 찾을 수 있도록 합니다 — 설치 후 셸을 다시 시작하거나 셸 rc 파일을 `source`하세요. 이 단계를 건너뛰었거나(`--skip-env`) 설치 위치를 옮겼다면 수동으로 설정하세요:

```bash
export SAFEC_HOME=/path/to/safec
export PATH="$SAFEC_HOME/bin:$PATH"
```

## 링킹 {#linking}

SafeC는 LLVM IR로 컴파일됩니다. 최종 링킹 단계는 시스템의 C 툴체인을 사용합니다:

```bash
# 기본 링킹
clang hello.ll -o hello

# pthreads 사용 (spawn/join용)
clang concurrency.ll -lpthread -o concurrency

# 수학 라이브러리 사용
clang math_demo.ll -lm -o math_demo
```

## 에디터 지원 {#editor-support}

`.sc` 파일을 위한 LSP 서버가 제공되며, 다음을 지원합니다:

- 진단 (오류 강조 표시)
- 호버 정보
- 코드 완성
- 정의로 이동
- 문서 심볼

LSP 서버 저장소의 `editors/vscode/` 디렉터리에 VS Code 확장이 포함되어 있습니다.

## 다음 단계 {#next-steps}

- [설계 철학](/ko/guide/design) — SafeC가 왜 그런 선택을 했는지 이해합니다
- [타입](/ko/reference/types) — SafeC의 타입 시스템을 배웁니다
- [메모리와 리전](/ko/reference/memory) — 리전 기반 메모리 안전성을 이해합니다
