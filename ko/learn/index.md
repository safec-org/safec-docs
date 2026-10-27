---
layout: page
title: SafeC 배우기
summary: SafeC를 배우는 데 필요한 모든 것 — SafeC 북, 가이드, 언어 레퍼런스, 표준 라이브러리, 예제, 도구를 한곳에.
---

# SafeC 배우기

::: lead
SafeC가 처음이라면 SafeC 북부터 시작하세요. C, C++, Rust, Zig에서 왔다면 가이드의 비교·설계 페이지가 무엇이 왜 달라지는지 설명합니다. 무언가를 찾고 있다면 레퍼런스와 표준 라이브러리 페이지가 기준입니다.
:::

## 여기서 시작하세요 {#start}

::::: cards 3
::: card book
### [SafeC 북](/ko/book/)

아홉 장으로 된 프로젝트 중심 입문서: 설치, 첫 프로그램, 리전, 구조체와 메서드, 열거형과 `match`, 에러 처리, 최종 프로젝트.
:::
::: card compass
### [가이드](/ko/guide/introduction)

SafeC가 무엇이고 무엇이 아닌지: [소개](/ko/guide/introduction), [시작하기](/ko/guide/getting-started), [설계 철학](/ko/guide/design), C·C++·Rust·Zig와의 [비교](/ko/guide/comparison).
:::
::: card play
### [플레이그라운드](/ko/playground)

브라우저에서 SafeC를 편집·실행하고 북의 실행 가능한 예제를 모두 불러올 수 있습니다. 이 사이트의 프로그램 중 플레이그라운드가 빌드할 수 있는 것에는 *플레이그라운드에서 실행* 링크가 있습니다.
:::
:::::

## 레퍼런스 {#reference}

::::: cards 2
::: card list
### [언어 레퍼런스](/ko/reference/types)

타입, 연산자, 리터럴, [메모리와 리전](/ko/reference/memory), 함수, [제네릭](/ko/reference/generics), 다형성, [안전성](/ko/reference/safety), [C 상호 운용](/ko/reference/ffi), 동시성, [베어메탈](/ko/reference/baremetal), 전처리기.
:::
::: card layers
### [표준 라이브러리](/ko/stdlib/)

모든 std 모듈과 API: 코어, 컬렉션, 직렬화, [비동기](/ko/stdlib/async), [네트워킹](/ko/stdlib/net), [HTTP](/ko/stdlib/http), [TLS](/ko/stdlib/tls), [GUI](/ko/stdlib/gui), [오디오](/ko/stdlib/audio), [WebAssembly](/ko/stdlib/wasm), ML, 암호화, 임베디드.
:::
::: card code
### [API 레퍼런스](/api/)

`safeguard doc --std`가 소스에서 생성한 모든 std 선언(영어): 시그니처·문서 주석·소스 링크가 있는 항목별 페이지, 모듈 트리, 검색. 여러분의 라이브러리도 [`safeguard doc`](/ko/advanced/safeguard#safeguard-doc)으로 똑같이 만들 수 있습니다.
:::
::: card chip
### [고급](/ko/advanced/compiler)

[컴파일러](/ko/advanced/compiler)의 동작, [안전성 모델](/ko/advanced/safety-model), [컴파일 타임 인트로스펙션](/ko/advanced/introspection), [scx 템플릿](/ko/advanced/scx), [패키지 매니저](/ko/advanced/safeguard) 심화.
:::
:::::

## 예제 {#examples}

::::: cards 3
::: card code
### [GitHub의 예제](https://github.com/safec-org/SafeC/tree/main/examples)

SafeC 저장소의 완성된 프로젝트: `async_demo`, `audio_demo`, `fullstack_demo`(라우터 서버와 wasm 클라이언트), `gui_hello`, `gui_gallery`, `gui_web`(웹 페이지로 만든 GUI 프로그램), `doc_demo`(`safeguard doc`으로 문서화한 라이브러리), C++ / Objective-C 의존성 예제.
:::
::: card gauge
### [벤치마크](/ko/benchmarks)

C, C++, Rust, Zig, Go와 비교한 SafeC — 연산 커널, 할당, 컬렉션, 웹 서비스, ML 학습 — 모든 프로그램의 소스 포함.
:::
::: card edit
### [이 웹사이트](https://github.com/safec-org/safec-docs/tree/main/site)

실제 프로그램 예: 지금 읽고 있는 사이트는 SafeC로 생성됩니다 — std markdown, scx 템플릿, SafeC→WebAssembly 클라이언트.
:::
:::::

## 도구와 소식 {#more}

- [설치](/ko/install) — Linux, macOS, Windows용 미리 빌드된 릴리스, 또는 소스 빌드.
- [도구](/ko/tools) — `safeguard`(빌드, 테스트, 포맷, 린트, 번들)와 VS Code·Neovim·Emacs용 `sc-lsp`.
- [변경 내역](/ko/changelog)과 [릴리스 노트](/ko/blog/tags/release) — 릴리스별 변경 사항.
- [블로그](/ko/blog/) — 공지와 긴 글.
