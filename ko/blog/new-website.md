---
title: SafeC의 새 보금자리
date: 2026-10-26
author: SafeC 팀
tags: [announcement, website]
summary: safec-org.github.io/safec-docs가 제대로 된 언어 웹사이트가 되었습니다 — 랜딩 페이지, 배우기 허브, 설치 안내, 플레이그라운드, 이 블로그 — 그리고 전부 SafeC 프로그램으로 빌드됩니다.
---

SafeC 문서는 VitePress 사이트로 시작했습니다: 레퍼런스 페이지가 늘어선 사이드바, 그게 거의 전부였습니다. 이제 언어에 필요한 웹사이트로 자랐습니다. 새로 생긴 것과, 그대로 남은 것을 소개합니다.

## 새로운 것 {#whats-new}

- **랜딩 페이지**: SafeC가 무엇인지 한 화면에 보여 줍니다. 실행 가능한 예제, SafeC가 컴파일 타임에 거부하는 버그와 그것을 그냥 컴파일하는 C 코드의 나란한 비교, 표준 라이브러리 둘러보기.
- **[배우기](/ko/learn/)**: SafeC 북, 가이드, 언어 레퍼런스, 표준 라이브러리, 예제, 벤치마크를 한 허브에.
- **[설치](/ko/install)**: 복사 버튼이 달린 플랫폼별 안내, 요구 사항(clang 21 이상), 모든 설치 옵션.
- **[도구](/ko/tools)**: `safeguard`, VS Code·Neovim·Emacs 설정을 포함한 `sc-lsp`, 그리고 `safec`.
- **[커뮤니티](/ko/community)**: 개발이 이뤄지는 곳, 버그 보고와 기여 방법.
- **[플레이그라운드](/ko/playground)**: 하이라이팅과 줄 번호가 있는 브라우저 편집기, 북의 실행 가능한 예제 불러오기, 링크로 프로그램 공유. 이제 사이트의 SafeC 프로그램 중 플레이그라운드가 빌드할 수 있는 모든 프로그램에 *플레이그라운드에서 실행* 링크가 있습니다.
- **이 블로그**: 태그, [Atom 피드](/ko/blog/feed.xml), [RSS 피드](/ko/blog/rss.xml). 릴리스 노트는 빌드할 때 SafeC의 릴리스 기록과 `CHANGELOG.md`에서 생성되고, [변경 내역](/ko/changelog)은 병합되었지만 아직 릴리스되지 않은 것까지 보여 줍니다.
- **새 디자인**: 라이트·다크 테마, 휴대폰에서도 동작하는 레이아웃, 키보드 탐색, 문서와 블로그를 함께 찾는 검색.

## 바뀌지 않은 것 {#unchanged}

기존 URL은 모두 그대로 동작합니다 — `/guide/introduction`, `/reference/types`, `/stdlib/http`를 비롯한 모든 페이지가 영어와 한국어 모두 원래 자리에 있습니다. `/docs/`, `/download` 같은 새 짧은 URL은 정적 리다이렉트 페이지입니다. GitHub Pages에는 서버 리다이렉트가 없기 때문입니다.

## SafeC로 빌드 {#built-with-safec}

사이트는 문서 저장소의 [`site/`](https://github.com/safec-org/safec-docs/tree/main/site)에 있는 SafeC 프로그램이 표준 라이브러리만으로 생성합니다: `std/serial/markdown`이 GitHub 확장을 포함한 CommonMark를 파싱하고, `std/serial/yaml`이 설정과 프런트매터를 읽고, scx 템플릿(SafeC의 JSX 스타일 마크업)이 페이지를 배치하며, 개발 서버는 `std/http`입니다. 테마 전환, 검색, 탭, 복사 버튼, 플레이그라운드 편집기 같은 동적인 부분은 `std/wasm`의 DOM 바인딩 위에서 WebAssembly로 컴파일한 SafeC 모듈이고, 이를 불러오는 데 JavaScript 몇 줄이 쓰입니다. JavaScript 프레임워크는 없고, 모든 페이지는 스크립트 없이도 동작합니다.

## 다음 계획 {#next}

플레이그라운드의 *실행* 버튼은 WebAssembly로 빌드한 셀프 호스팅 SafeC 컴파일러를 기다리고 있었습니다. *업데이트:* 이제 들어왔습니다 — *실행*이 scc로 브라우저 안에서 프로그램을 컴파일하고 실행합니다([다음에 나올 것](/ko/blog/whats-coming-next#scc)). 버전별 문서도 계획 중입니다: 문서 사이드바의 버전 선택기에는 이미 릴리스 목록이 있습니다.

끊어진 링크나 더 명확해질 수 있는 페이지를 찾았다면 [이슈를 열거나](https://github.com/safec-org/safec-docs/issues) 풀 리퀘스트를 보내 주세요 — 방법은 [커뮤니티](/ko/community#docs) 페이지에 있습니다.
