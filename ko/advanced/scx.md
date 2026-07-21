# scx 템플릿

`.scx`는 SafeC 버전의 JSX/TSX/RSX입니다: 태그 단위로 문자열을 직접 만들지
않고 서버 렌더링 페이지를 작성하기 위해 소스에 직접 내장되는 HTML과
비슷한 마크업입니다. JSX가 `React.createElement`로, `rsx!`가 DOM
연산으로 변환되는 것처럼, 이것도 트랜스파일러입니다 — `.scx` 파일은
평범한 SafeC 소스에 한 가지 추가 문법이 더해진 것으로, `safec`이 파일을
보기 *전에* 평범한 SafeC 호출로 다시 작성됩니다. `safeguard
build`/`check`/`test`는 발견하는 모든 `.scx` 파일에 대해 이를 자동으로
수행합니다; `safec` 자체는 `.scx`의 존재를 전혀 모릅니다.

이는 의도적으로 "SafeC를 위한 JSX"의 가장 작고 유용한 버전입니다:
가상 DOM이 아니라 HTML **문자열**(`struct String`)을 만듭니다 —
서버 사이드 렌더링(페이지를 만들어 `std::http_serve`에 응답 본문으로
넘기는 것)에는 적합하지만, 클라이언트 사이드 반응성이나 차등 재렌더링에는
적합하지 않습니다.

## 문법 {#syntax}

마크업은 정확히 한 곳에서만 나타날 수 있습니다: `return` 문의 전체 피연산자로서입니다.

```c
struct String render_home(const char* visitor_name) {
    return <html>
        <head><title>My Page</title></head>
        <body>
            <h1>Hello, {visitor_name}!</h1>
            <p>Welcome.</p>
        </body>
    </html>;
}
```

- 태그는 식별자입니다(`[a-zA-Z][a-zA-Z0-9-]*`, 커스텀 엘리먼트를 위해
  하이픈이 허용됩니다), HTML/XML처럼 열고 닫습니다: `<div>...</div>`
  또는 자체 종료 형태인 `<br/>`.
- 속성: `name="literal text"` 또는 `name={expr}`.
- `{expr}`은 SafeC 표현식의 값을 출력에 보간하며, **HTML 이스케이프**
  처리됩니다(`&`, `<`, `>`, `"`, `'`가 엔티티가 됩니다) — JSX가
  `{expr}` 자식을 자동 이스케이프하는 것과 같은 안전한 기본 동작입니다.
- `{!expr}`은 **원시, 이스케이프되지 않은** 형태로 보간합니다 — 미리
  렌더링된 신뢰할 수 있는 HTML을 내장할 때 사용합니다(아래
  [페이지 조합하기](#composing-pages) 참고).
- `return <markup>;` 문 바깥의 모든 것은 수정되지 않은, 평범한
  SafeC입니다 — import, 구조체, 일반 함수. `.scx` 파일의 마크업이
  아닌 대다수 부분은 정확히 `.sc` 파일과 같습니다; 문자열/문자
  리터럴과 `//`/`/* */` 주석은 손대지 않고 그대로 훑고 지나가므로,
  그 안에 있는 우연한 `<`나 `return`이 마크업으로 오인되는 일은
  없습니다.

모든 `{expr}`/`{!expr}` 표현식은 `const char*`로 평가되어야 합니다 —
scx는 자체적으로 타입 검사를 하지 않으므로(Sema가 실행되기 전에
텍스트 수준 패스로 실행됩니다), 타입 불일치는 생성된 코드에 대한
평범한 `safec` 컴파일 오류로 나타나며, 합성된 호출 지점을 가리킵니다.

## 왜 `return <markup>;`만 허용하는가 {#why-only-return-markup}

진짜 JSX/TSX는 마크업을 호스트 언어의 전체 표현식 문법에 통합합니다
(변수에 할당 가능하고, 인자로 전달 가능하고, 삼항 연산자에 사용 가능하고,
루프 안에 내장 가능합니다). 이는 그들의 트랜스파일러가 표현식
컨텍스트를 이미 이해하는 진짜 JavaScript/TypeScript 파서 위에
구축되어 있기 때문입니다. scx의 트랜스파일러는 가볍고 독립적인
텍스트 수준 패스입니다 — `safec`의 파서나 표현식 컨텍스트 개념을
공유하지 않으며, SafeC에는 표현식 중간에 여러 문장으로 값을
구성하기 위해 기댈 만한 문장-표현식 구문(`({ ... })`)도 없습니다.

마크업을 `return <markup>;`으로 제한하면 이 모든 문제를 우회할 수
있습니다: 생성된 빌더 문들은 원래 `return` 문이 있던 바로 그 자리에,
같은 함수 안에, 이미 스코프에 있는 같은 매개변수와 지역 변수와 함께
들어갑니다 — 호이스팅도, 캡처 분석도, `safec` 자체에 새로운 문법도
필요 없습니다. 그 대가로 scx에서 "컴포넌트"는 단순히 *마크업을
반환하는 함수*일 뿐입니다 — `struct String`을 반환하는
`render_x(...)`를 작성하고 다른 함수처럼 호출하면 됩니다.

## 페이지 조합하기 {#composing-pages}

컴포넌트가 그저 함수일 뿐이므로, 조합은 한 함수를 다른 함수에서
호출하는 것을 의미합니다 — 먼저 자식을 렌더링한 다음, 이미 만들어진
HTML을 `{!...}`로 붙여넣습니다:

```c
struct String render_item(const char* name, int qty) {
    return <li>{name} &times; {qty}</li>;
}

struct String render_cart(struct String items_html) {
    return <ul>{!items_html.as_ptr()}</ul>;
}
```

호출자는 평범한 SafeC 코드(`render_item(...)` 호출을 순회하며
`String::push_str`로 이어 붙이는 루프)로 `items_html`을 만든 다음,
완성되어 필요한 곳은 이미 이스케이프된 결과를 `{!...}`로 원시 그대로
보간합니다 — `{...}`를 사용하면 이중으로 이스케이프됩니다. 이는
가상 DOM 없는 템플릿 엔진이 조합을 처리해야 하는 방식을 그대로
반영합니다: 프레임워크가 중첩된 엘리먼트 트리를 알아서 비교해주는
대신, 먼저 내부 콘텐츠를 만들고 그다음 이어 붙이는 것입니다.

## 런타임 {#runtime}

생성된 코드는 `std/scx/scx.h` 아래의 작은 런타임을 호출합니다:

```c
namespace std {
void scx_append_esc(&String buf, const char* s); // HTML 이스케이프 + 추가
}
```

`safeguard`는 마크업을 사용한 모든 파일에 `#include <std/scx/scx.h>`와
`#include <std/collections/string.h>`를 자동으로 주입합니다 — 손으로
작성한 `.scx` 소스는 이를 직접 포함하지 않습니다. 정적 텍스트와
속성(트랜스파일 시점에 알려진)은 한 번, 앞부분에서 HTML 이스케이프
처리되어 정적 콘텐츠의 연속 구간마다 단일 `String::push("...")` 호출로
합쳐집니다; 오직 `{expr}` 보간 지점만 런타임 호출 비용이 듭니다 —
생성된 코드는 다음과 같이 보입니다:

```c
struct String __scx0 = std::string_new();
unsafe {
    __scx0.push("<h1>Hello, ");
    std::scx_append_esc(&__scx0, (visitor_name));
    __scx0.push("!</h1>");
}
return __scx0;
```

## 에디터 지원 {#editor-support}

`sc-lsp`([컴파일러 아키텍처](/ko/advanced/compiler)의 도구 섹션과
`sc-language-server`의 README 참고)는 `.scx`를 직접 이해합니다 —
SafeC 확장이 설치된 에디터에서 파일을 열면 `.sc` 파일과 동일한 진단,
호버, 정의로 이동, 완성, 문서 개요를 얻을 수 있습니다. 내부적으로
서버는 `safeguard build`가 사용하는 것과 같은 트랜스파일러로 버퍼를
트랜스파일하고(하나의 공유된 사본이므로 둘이 서로 어긋날 수 없습니다),
생성된 SafeC를 분석한 다음, 보고된 모든 위치를 `.scx` 버퍼 자신의
줄로 다시 매핑합니다 — 생성된 코드는 결코 보이지 않습니다. 그 매핑은
줄 단위입니다: 마크업 확장(대개의 일반적인 파일에서 대부분을 차지하는
부분) 바깥에서는 정확하며, 마크업 *내부*의 진단(예: `{expr}` 보간의
타입 오류)에 대해서는 정확한 하위 줄이 아니라 마크업 블록이 자신이
확장되는 코드와 1:1로 대응하지 않으므로 이를 감싸는 `return <markup>;`이 시작되는 줄을 가리킵니다. 잘못된 형식의 마크업 자체(닫는
태그 불일치, 종료되지 않은 `{`)도 트랜스파일러가 이를 발견한 정확한
줄에서 동일한 방식으로 보고됩니다.

## 프론트엔드 기능 게이팅하기 {#feature-gating-a-frontend}

`.scx` 파일은 평범한 프로젝트 소스입니다 — `safeguard`는
[기능 플래그](/ko/advanced/safeguard#feature-flags)와 무관하게 `src/`
아래에서 발견하는 모든 파일을 컴파일하며, 이는 어떤 `.sc` 파일이든
마찬가지입니다. 프론트엔드/백엔드 분리는 파일을 제외하는 것이 아니라
scx로 렌더링된 페이지를 조건부로 *호출*하는 것에서 나옵니다:

```c
// src/main.sc
#ifdef SAFEC_FEATURE_FRONTEND
struct String render_home(const char* visitor_name); // src/pages.scx
#endif

int main() {
#ifdef SAFEC_FEATURE_BACKEND
    std::http_serve((unsigned short)8123, handle); // handle()은 render_home()을 호출함
#else
    struct String page = render_home("static build");
    unsafe { printf("%s\n", page.as_ptr()); }
    page.free();
#endif
    return 0;
}
```

이 예제가 바탕으로 하는 완전하고 실제로 동작하는 프로젝트는 SafeC
저장소의 `examples/fullstack_demo/`를 참고하세요 — `frontend`/`backend`/`fullstack`
기능을 갖춘 `Package.toml`, 세 개의 렌더링된 페이지를 가진
`src/pages.scx`, 그리고 `fullstack`/`backend` 모드에서는 HTTP로
페이지를 제공하고 `frontend` 전용 모드에서는 표준 출력으로 하나를
렌더링하는 `src/main.sc`가 있습니다:

```bash
cd examples/fullstack_demo
safeguard run                                             # fullstack: HTTP 서버, HTML 페이지
safeguard run --features backend  --no-default-features   # HTTP 서버, JSON만
safeguard run --features frontend --no-default-features   # 서버 없음 — 페이지 하나를 렌더링하고 출력
```
