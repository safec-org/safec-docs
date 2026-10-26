---
layout: page
title: 커뮤니티
summary: SafeC가 개발되는 곳 — GitHub 저장소, 이슈, 풀 리퀘스트, 그리고 코드·문서·번역에 기여하는 방법.
---

# 커뮤니티

::: lead
SafeC는 MIT 라이선스로 GitHub에서 공개적으로 개발됩니다. 질문, 버그 보고, 아이디어, 풀 리퀘스트 모두 그곳에서 환영합니다.
:::

::::: cards 3
::: card git
### [GitHub의 SafeC](https://github.com/safec-org/SafeC)

컴파일러, 표준 라이브러리, `safeguard`, 테스트, 예제, 설치 스크립트.
:::
::: card bug
### [이슈](https://github.com/safec-org/SafeC/issues)

버그, 잘못된 코드 생성, 헷갈리는 진단 메시지를 알리거나 기능·std 모듈을 제안하세요.
:::
::: card edit
### [이 문서](https://github.com/safec-org/safec-docs)

지금 읽고 있는 영어·한국어 웹사이트. SafeC로 생성됩니다.
:::
::: card terminal
### [sc-language-server](https://github.com/safec-org/sc-language-server)

LSP 서버와 VS Code 확장.
:::
::: card rss
### [블로그](/ko/blog/)

공지와 릴리스 노트 — [Atom](/ko/blog/feed.xml)과 [RSS](/ko/blog/rss.xml) 피드.
:::
::: card heart
### [릴리스](https://github.com/safec-org/SafeC/releases)

Linux, macOS, Windows용 미리 빌드된 툴체인. 저장소를 watch하면 새 릴리스 소식을 받을 수 있습니다.
:::
:::::

## 버그 보고 {#bugs}

좋은 보고는 재현할 수 있는 보고입니다: 문제를 보여 주는 가장 작은 `.sc` 파일, 실행한 명령, 기대한 결과와 실제 결과, 그리고 `safec --version`, OS, clang 버전. 잘못된 코드 생성이라면 `--emit-llvm` 출력이 도움이 됩니다. [이슈 트래커](https://github.com/safec-org/SafeC/issues/new)에 올려 주세요.

보안 문제(예: `std/crypto`나 TLS 작업)를 찾았다면 익스플로잇이 담긴 공개 이슈는 올리지 마세요. 비공개 연락처를 요청하는 최소한의 이슈를 열거나, 저장소에서 활성화되어 있다면 GitHub의 비공개 취약점 보고를 이용하세요.

## 코드 기여 {#contributing}

1. [safec-org/SafeC](https://github.com/safec-org/SafeC)를 포크하고 브랜치에서 작업하세요.
2. 소스에서 툴체인을 빌드하세요([설치 → 소스에서 빌드](/ko/install#quick-install)).
3. 변경은 작고 집중되게, 주변 코드의 스타일대로 — std 모듈은 주석이 달린 `.h`와 `.sc`를 짝지어 두고, 메서드와 `static ... new()` 생성자를 쓰며, `unsafe {}`는 필요한 곳에만 둡니다.
4. 테스트를 추가하세요: 컴파일러 변경은 `compiler/tests/`에 회귀 테스트를, std 변경은 `tests/stdlib/tests/`에 런타임 테스트를(`tests/stdlib`에서 `safeguard test`로 실행).
5. 무엇을 왜 바꿨는지 설명하는 풀 리퀘스트를 여세요. CI가 Linux, macOS, Windows에서 빌드·테스트합니다.

## 문서와 번역 기여 {#docs}

사이트는 [safec-org/safec-docs](https://github.com/safec-org/safec-docs)에 있습니다: 페이지마다 마크다운 파일 하나, 한국어 번역은 `ko/` 아래, 내비게이션은 `site/locales/<lang>.yaml`. `site/build.sh`는 사이트를 빌드하고 끊어진 링크나 앵커가 있으면 실패하며, `site/build.sh serve`는 변경 시 다시 빌드하는 미리보기를 띄웁니다. 블로그 글은 `title`, `date`, `author`, `tags`, `summary` 프런트매터를 가진 `blog/<slug>.md`입니다. 수정, 새 예제, 번역되지 않은 페이지의 번역 모두 환영합니다.

## 라이선스 {#license}

SafeC와 문서는 [MIT 라이선스](https://github.com/safec-org/SafeC/blob/main/LICENSE)로 배포됩니다.
