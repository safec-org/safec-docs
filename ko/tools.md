---
layout: page
title: 도구
summary: SafeC 툴체인 — safec 컴파일러, safeguard 패키지 매니저·빌드 도구, VS Code·Neovim·Emacs 설정을 포함한 sc-lsp 언어 서버, 그리고 플레이그라운드.
---

# 도구

::: lead
모든 릴리스에는 세 프로그램이 들어 있습니다. 컴파일러 `safec`, 대부분의 프로젝트가 모든 작업에 쓰는 패키지 매니저이자 빌드 도구 `safeguard`, 에디터용 언어 서버 `sc-lsp`. [설치](/ko/install)하면 셋 모두(그리고 `safeguard doc`이 쓰는 API 레퍼런스 생성기 `safec-doc`) `PATH`에 들어갑니다.
:::

::::: cards 3
::: card package
### [safeguard](#safeguard)

프로젝트, 의존성, 빌드, 테스트, 포맷, 린트, 앱 번들.
:::
::: card edit
### [sc-lsp](#sc-lsp)

컴파일러의 프런트엔드를 그대로 쓰는 진단, 호버, 자동 완성, 정의로 이동.
:::
::: card terminal
### [safec](#safec)

컴파일러 자체: SafeC를 LLVM IR, 오브젝트, 바이너리로 — 모든 LLVM 타깃용.
:::
:::::

## safeguard {#safeguard}

`safeguard`는 `Package.toml`로 기술된 프로젝트를 빌드합니다: SafeC, C, C++, Objective-C 소스, git·로컬·미리 빌드된 의존성(CMake로 빌드하는 C/C++ 라이브러리 포함), 그리고 프로젝트가 쓰는 표준 라이브러리 모듈만 필요한 시스템 라이브러리와 함께 링크합니다.

| 명령 | 하는 일 |
|---|---|
| `safeguard new <name>` / `init` | 프로젝트 생성 (SafeC가 없는 패키지는 `--lang c\|cpp\|objc\|objcpp`) |
| `safeguard build` | 프로젝트 컴파일·링크 (`--release`, 크로스 빌드는 `--target <triple>`) |
| `safeguard run [args]` | 빌드 후 실행 |
| `safeguard check` | 링크 없이 빠른 컴파일 검사 |
| `safeguard test` | `tests/` 아래 모든 파일을 빌드·실행 |
| `safeguard fetch` | `[dependencies]` 클론·빌드 |
| `safeguard format` | `.sc` / `.h` 파일 들여쓰기·공백 정리 |
| `safeguard lint` | 정적 분석 린트 |
| `safeguard bundle` | GUI 앱을 macOS `.app`, Windows 앱 폴더, Linux `.deb`와 AppDir, 또는 (`--target wasm32`) 웹 페이지로 패키징 |
| `safeguard doc` | 패키지의 [API 레퍼런스](/ko/advanced/safeguard#safeguard-doc) 생성 (`--serve`로 보기, `--std`로 std의 것) |
| `safeguard publish` | 라이브러리를 [배포](/ko/advanced/safeguard#safeguard-publish)할 수 있게 준비: CI, 릴리스 아카이브, Pages 문서 |
| `safeguard clean` | `build/`와 `deps/` 제거 |
| `safeguard verify-lock` | `Package.lock`을 현재 의존성 상태와 대조 |
| `safeguard std-modules` | 빌드가 컴파일하는 std 소스와 링크하는 시스템 라이브러리 |
| `safeguard std-prebuilt <prefix>` | 이 머신용 std를 섹션마다 아카이브 하나로 컴파일: 릴리스가 함께 배포하는 [미리 빌드된 std](/ko/advanced/safeguard#prebuilt-std) |

```toml
# Package.toml
[package]
name    = "hello"
version = "0.1.0"

[[dependencies]]
name    = "mylib"
version = "https://github.com/user/mylib"   # 또는: path = "../mylib"
```

[패키지 매니저](/ko/advanced/safeguard) 페이지에 모든 명령, `Package.toml` 키, 기능 플래그, 크로스 타깃, 라이브러리 출력, Windows DLL, 번들이 정리되어 있습니다.

## sc-lsp — 에디터 지원 {#sc-lsp}

[`sc-lsp`](https://github.com/safec-org/sc-language-server)는 SafeC 컴파일러의 실제 프런트엔드(전처리기, 렉서, 파서, 의미 분석, 상수 평가)를 링크하므로 컴파일러가 이해하는 모든 구문을 이해하고, 빌드가 보고할 내용을 그대로 보고합니다. `.sc`, `.h`, `.scx` 파일을 분석하며 다음을 제공합니다:

- 열거나 고칠 때마다 진단,
- 전체 시그니처를 보여 주는 호버,
- 심볼과 키워드 자동 완성,
- 정의로 이동(std 헤더 포함),
- 문서 심볼(아웃라인).

가장 가까운 `Package.toml`에서 기능과 compat 전처리기 설정을 읽고, `initializationOptions`로 `includePaths`, `target`, `defines`, `features`, `noDefaultFeatures`, `compatPreprocessor`, `provides`를 받습니다(VS Code 확장에서는 같은 이름의 `safec.*` 설정).

::::: tabs
::: tab VS Code
```bash
git clone https://github.com/safec-org/sc-language-server
cd sc-language-server/editors/vscode
npm install
npm install -g @vscode/vsce
vsce package                         # -> safec-analyzer-<version>.vsix
code --install-extension safec-analyzer-*.vsix
```
그다음 설정에 `"safec.server.path": "/path/to/sc-lsp"`를 넣으세요. 확장(SafeC Analyzer)은 `.sc`, `.scx`, `.h`를 맡습니다. 일반 C 헤더는 *Change Language Mode*로 C/C++ 확장에 돌려줄 수 있습니다.
:::
::: tab Neovim
```lua
require("lspconfig.configs").safec = {
  default_config = {
    cmd = { "sc-lsp" },
    filetypes = { "safec" },
    root_dir = require("lspconfig.util").find_git_ancestor,
  },
}
require("lspconfig").safec.setup({})
vim.filetype.add({ extension = { sc = "safec" } })
```
SafeC 프로젝트 안의 `.h`만 맡게 하려면 [서버 README](https://github.com/safec-org/sc-language-server#neovim-nvim-lspconfig)를 보세요.
:::
::: tab Emacs
```lisp
(add-to-list 'auto-mode-alist '("\\.sc\\'" . c-mode))
(add-to-list 'eglot-server-programs '(c-mode . ("sc-lsp")))
```
이렇게 하면 Eglot이 `.sc` 파일(그리고 이미 `c-mode`인 헤더)에 `sc-lsp`를 띄웁니다.
:::
:::::

## safec — 컴파일러 {#safec}

`safec`는 번역 단위 하나를 LLVM IR(`--emit-llvm`), 오브젝트, 또는 clang을 거쳐 바이너리(`--emit-bin`)로 컴파일합니다. `--target=<triple>`은 모든 LLVM 타깃으로 크로스 컴파일하고, `arm-7em`, `rv32i`, `wasm32` 같은 별칭은 베어메탈과 WebAssembly용 트리플·CPU·기능·ABI를 고르며, `--freestanding`은 호스트 런타임을 뺍니다.

```bash
safec hello.sc --emit-bin -o hello
safec blink.sc --target=arm-7em --freestanding --emit-llvm -o blink.ll
```

모든 플래그와 단일 파일 빌드, C 헤더는 [시작하기](/ko/guide/getting-started#compiler-flags)에, 그 뒤의 파이프라인은 [컴파일러 아키텍처](/ko/advanced/compiler)에 있습니다.

## 플레이그라운드 {#playground}

[플레이그라운드](/ko/playground)는 브라우저에서 하이라이팅과 함께 SafeC를 편집하고, 북의 예제를 불러오고, 프로그램을 링크로 공유합니다. *실행*은 WebAssembly로 빌드한 SafeC의 셀프 호스팅 컴파일러 scc로 브라우저 안에서 프로그램을 컴파일하고 실행합니다. 언어의 부분집합과 함께 포함된 std 헤더를 받아들입니다([자세히](/ko/playground#about)).
