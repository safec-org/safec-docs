---
layout: page
title: SafeC 설치
summary: Linux, macOS, Windows에 미리 빌드된 릴리스로 SafeC 컴파일러, safeguard, sc-lsp를 설치하거나 소스에서 빌드합니다.
---

# SafeC 설치

::: lead
명령 하나로 최신 [GitHub 릴리스](https://github.com/safec-org/SafeC/releases)에서 미리 빌드된 `safec`(컴파일러), `safeguard`(패키지 매니저이자 빌드 도구), `sc-lsp`(언어 서버)와 표준 라이브러리(소스와 플랫폼용 미리 빌드된 아카이브)를 설치합니다.
:::

## 빠른 설치 {#quick-install}

::::: tabs
::: tab macOS
```bash
brew install llvm          # clang 21 이상; Apple Xcode의 clang은 너무 오래됨
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash
```
Apple 실리콘(`arm64`)용 바이너리, macOS 11 Big Sur 이상.
:::
::: tab Linux
```bash
# Debian / Ubuntu: 아직 없다면 apt.llvm.org에서 clang 21 설치
wget https://apt.llvm.org/llvm.sh && sudo bash llvm.sh 21
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash
```
glibc 2.25 이상(Debian 10, Ubuntu 18.04, RHEL 8 이후)용 `x86_64` 바이너리. musl 기반 배포판(Alpine)은 릴리스 바이너리가 지원하지 않으니 소스에서 빌드하세요.
:::
::: tab Windows
```powershell
winget install LLVM.LLVM     # clang 21 이상
irm https://raw.githubusercontent.com/safec-org/SafeC/main/install.ps1 | iex
```
PowerShell에서 실행하는 `x86_64` 바이너리.
:::
::: tab 소스에서 빌드
```bash
git clone https://github.com/safec-org/SafeC && cd SafeC
cmake -G Ninja -S compiler -B compiler/build -DCMAKE_BUILD_TYPE=Release \
      -DLLVM_DIR=/usr/lib/llvm-21/lib/cmake/llvm
cmake --build compiler/build
cmake -G Ninja -S safeguard -B safeguard/build -DCMAKE_BUILD_TYPE=Release
cmake --build safeguard/build
export SAFEC_HOME=$PWD PATH=$PWD/compiler/build:$PWD/safeguard/build:$PATH
```
CMake, Ninja, C++17 컴파일러, LLVM 21 개발 파일이 필요합니다. `sc-lsp`는 별도의 [sc-language-server](https://github.com/safec-org/sc-language-server) 저장소에 있습니다.
:::
:::::

스크립트는 기본적으로 `~/safec`에 설치하고, 셸 rc 파일(bash, zsh, fish; Windows는 사용자 환경 변수)에 `SAFEC_HOME`을 설정하고 `bin/`을 `PATH`에 추가합니다. 셸을 다시 시작하거나 rc 파일을 `source`한 뒤 확인하세요:

```bash
safec --version
safeguard --help
```

## 요구 사항 {#requirements}

**clang 21 이상.** `safec`는 LLVM IR을 내보내고, `safec --emit-bin`과 `safeguard`는 이를 clang에 넘겨 컴파일·링크합니다(C 헤더 가져오기도 clang이 합니다). 설치 스크립트는 `clang-21` 같은 버전 이름, apt.llvm.org의 `/usr/lib/llvm-21`, Homebrew의 `llvm`까지 찾아 주며, `PATH`의 기본 `clang`이 아니면 `SAFEC_CLANG` / `SAFEC_CLANGXX`를 설정합니다. 찾지 못하면 설치 방법을 안내합니다:

| 플랫폼 | clang 설치 |
|---|---|
| macOS | `brew install llvm` |
| Debian / Ubuntu | `wget https://apt.llvm.org/llvm.sh && sudo bash llvm.sh 21` |
| Windows | `winget install LLVM.LLVM` |
| 기타 | [LLVM 릴리스](https://github.com/llvm/llvm-project/releases) |

## 옵션 {#options}

| `install.sh` | `install.ps1` | |
|---|---|---|
| `--prefix=<path>` | `-Prefix <path>` | 설치 디렉터리 (기본 `~/safec`) |
| `--version=<tag>` | `-Version <tag>` | 특정 릴리스, 예: `v1.0.0-beta-6` (기본: 프리릴리스를 포함한 최신) |
| `--std=<sections>` | `-Std <sections>` | 설치할 미리 빌드된 std 섹션, 예: `core,collections,gui` (기본 `all`) |
| `--std=+<sections>` | `-Std +<sections>` | 기존 설치에 섹션 추가 |
| `--skip-env` | `-SkipEnv` | 셸 환경을 건드리지 않음 |

파이프로 실행할 때는 `bash -s --`로 옵션을 넘깁니다:

```bash
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash -s -- --version=v1.0.0-beta-6 --std=core,collections
```

표준 라이브러리의 소스는 항상 설치됩니다. 미리 빌드된 아카이브는 *섹션* — std의 최상위 디렉터리(`core`, `collections`, `gui`, `ml`, `http`, `audio`, `async`, ...) — 단위로 제공되며, `safeguard`는 프로젝트가 쓰는 섹션을 링크하고, 설치하지 않은 섹션은 필요할 때 소스에서 컴파일합니다. [미리 빌드된 std](/ko/advanced/safeguard#prebuilt-std)를 참고하세요.

## 첫 프로그램 {#first-program}

```bash
safeguard new hello && cd hello
safeguard run
```

`safeguard new`는 `Package.toml`과 `src/main.sc`를 만들고, `safeguard run`은 빌드 후 실행합니다. 다음 단계:

- [SafeC 북](/ko/book/)은 작은 프로젝트로 언어를 차근차근 안내합니다.
- [시작하기](/ko/guide/getting-started)는 `safec`로 단일 파일 컴파일, 링크, C 헤더를 다룹니다.
- [도구](/ko/tools)에서 `sc-lsp`로 에디터를 설정하세요.

## 업데이트와 제거 {#updating}

설치 명령을 다시 실행하면 최신 릴리스로 업데이트됩니다(`--version`으로 고를 수도 있습니다). 제거하려면 설치 디렉터리(기본 `~/safec`)와 설치 스크립트가 셸 rc 파일에 추가한 `# SafeC` 블록을 지우세요.
