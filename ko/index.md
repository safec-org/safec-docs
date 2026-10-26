---
layout: home
title: SafeC
summary: SafeC는 컴파일 타임 메모리 안전성, 가비지 컬렉터 없음, 숨은 비용 없음, 완전한 C ABI 호환성을 갖춘 리전 기반의 C 진화형 언어입니다.
---

:::: hero
::: split
::: col
<p class="eyebrow">SafeC 프로그래밍 언어</p>

# 안전하고 결정론적인 시스템 프로그래밍 {#safe-deterministic-systems-programming}

SafeC는 진화한 C입니다. 리전을 통한 **컴파일 타임 메모리 안전성**을 제공하면서도 가비지 컬렉터나 숨은 런타임이 없고, C의 ABI·헤더·툴체인을 그대로 씁니다. 커널, 오디오 엔진, 서버, GUI, WebAssembly를 C처럼 읽히는 하나의 언어로 작성하세요.

::: actions
[SafeC 설치](/ko/install)
[플레이그라운드](/ko/playground)
[SafeC 북 읽기](/ko/book/)
:::

<p class="hero-meta">오픈 소스(MIT) · Linux, macOS, Windows · 베어메탈과 WebAssembly · LLVM 백엔드</p>
:::
::: col
```c
extern int printf(const char* fmt, ...);

region Frame { capacity: 4096 }

struct Particle { float x; float v; };

// 리전은 타입의 일부입니다: 이 참조는 아레나보다 오래 살 수 없습니다.
void step(&arena<Frame> Particle p, float dt) {
    p.x = p.x + p.v * dt;
}

int main() {
    &arena<Frame> Particle p = new<Frame> Particle;
    p.x = 0.0;
    p.v = 2.5;
    for (int i = 0; i < 3; i = i + 1) {
        step(p, 0.5);
        printf("t=%d x=%.2f\n", i, p.x);
    }
    arena_reset<Frame>();   // O(1): 객체별 해제도, GC도 없음
    return 0;
}
```
:::
:::
::::

:::: section
## 왜 SafeC인가 {#why}

::: lead
C를 시스템 언어로 만드는 모든 것 — 예측 가능한 성능, 안정적인 ABI, 모든 플랫폼 — 은 그대로 두고, C가 그냥 넘기는 버그는 컴파일러가 거부하게 하세요.
:::

::::: cards 3
::: card shield
### [GC 없는 메모리 안전성](/ko/reference/memory)

모든 참조는 자신의 리전(`stack`, `heap`, `arena<R>`, `static`)을 지니며, 컴파일러는 그 참조가 댕글링되거나, 가변 별칭을 만들거나, 탈출하지 않음을 증명합니다. 컬렉터도, 참조 카운트도, 런타임 메타데이터도 없습니다.
:::
::: card plug
### [C와 그대로 통합니다](/ko/reference/ffi)

기본적으로 C 구조체 레이아웃, C 호출 규약, C 심볼 이름을 씁니다. `#include <stdio.h>`가 동작하고, SafeC 오브젝트를 어떤 C/C++ 프로젝트에든 링크하며, 바인딩 없이 C 라이브러리를 호출합니다.
:::
::: card clock
### [설계부터 결정론적](/ko/guide/design)

암묵적 할당, 예외, 숨은 복사, 축소 변환이 없습니다. 같은 입력은 같은 출력을, 눈에 보이는 비용으로 냅니다 — 실시간 오디오와 제어 루프에 필요한 성질입니다.
:::
::: card chip
### [베어메탈과 WebAssembly](/ko/reference/baremetal)

`--freestanding`은 인터럽트 핸들러, 인라인 어셈블리, `volatile` MMIO와 함께 14개의 OS 없는 타깃(Cortex-M, RISC-V, x86, AArch64)으로 빌드합니다. `--target=wasm32`는 `std/wasm` DOM 바인딩을 쓰는 브라우저 모듈을 만듭니다.
:::
::: card flow
### [런타임 부담 없는 비동기](/ko/stdlib/async)

`std/async`는 Rust 스타일의 future와 waker를 라이브러리로 제공합니다: 스택 없는 상태 머신, 베어메탈과 wasm에서도 도는 힙 없는 단일 스레드 실행기, 스레드 풀, 타이머, 비동기 TCP.
:::
::: card layers
### [실제 프로그램을 위한 표준 라이브러리](/ko/stdlib/)

30개가 넘는 제네릭·`void*` 없는 모듈: 컬렉션, 웹 스택(HTTP 서버, 라우터, WebSocket, gzip), GUI 툴킷, 실시간 오디오와 MIDI, CPU·GPU용 ML 텐서, 암호화, 직렬화.
:::
:::::
::::

:::: section alt
## C는 컴파일하고, SafeC는 거부하는 버그 {#compare}

::: lead
아래 각 쌍은 같은 실수입니다. C 버전은 아무 불평 없이 빌드되고 실행 중에 — 어쩌면 — 실패합니다. SafeC 버전은 빌드되지 않고, 컴파일러가 이유를 알려 줍니다.
:::

::::: compare
::: col C — 컴파일되고, 댕글링 포인터를 반환
```c norun
int* counter() {
    int x = 42;
    return &x;   // 기껏해야 경고; 사용하면 UB
}
```
:::
::: col SafeC — 컴파일 타임 오류
```c norun
&stack int counter() {
    int x = 42;
    return &x;
}
```
```text
error: cannot return '&stack int': stack reference escapes function scope
```
:::
:::::

::::: compare
::: col C — 널 포인터도 그냥 포인터
```c norun
struct Node { int value; };

int read(struct Node* n) {
    return n->value;   // n == NULL이면 크래시
}
```
:::
::: col SafeC — 널 가능 참조는 반드시 검사
```c norun
struct Node { int value; };

int read(?&Node n) {
    return n.value;
}
```
```text
error: dereference of nullable reference '?&Node' requires 'unsafe', or use match/is_null()/.default(value) instead
```
:::
:::::

::::: compare
::: col C — 조용한 잘림
```c norun
long big = 5000000000L;
int small = big;   // 구현 정의 결과
```
:::
::: col SafeC — 축소 변환은 명시적으로
```c norun
long big = 5000000000L;
int small = big;
```
```text
error: type mismatch in variable declaration: cannot convert 'long' to 'int'
```
:::
:::::

[C, C++, Rust, Zig와 더 비교하기 →](/ko/guide/comparison)
::::

:::: section
## 배터리 포함 {#std}

::: lead
표준 라이브러리는 SafeC로 작성되었고, 프로그램이 쓰는 모듈만 링크하며, 모든 릴리스 플랫폼용으로 미리 빌드되어 배포됩니다.
:::

::::: tabs
::: tab 웹 서버
```c norun
#include <std/http/router.h>

struct HttpResponse hello(&HttpRequest req, &HttpCtx ctx) {
    struct String body = std::String::from("Hello, ");
    body.push(ctx.param_or("name", "world"));
    struct HttpResponse r = std::HttpResponse::text(200, body.as_ptr());
    body.free();
    return r;
}

int main() {
    struct Router app = std::Router::new();
    app.use_logger();
    app.use_gzip(1024UL);
    app.get("/hello/:name", hello);
    app.static_dir("/assets", "./public");

    struct HttpServerConfig cfg = std::HttpServerConfig::defaults(8080);
    return app.listen(&cfg);    // I/O 리액터 위의 keep-alive HTTP/1.1
}
```
라우터, 미들웨어, 정적 파일, 쿠키, multipart, SSE, WebSocket, 그리고 리다이렉트를 따라가는 gzip 클라이언트 — [HTTP와 웹](/ko/stdlib/http).
:::
::: tab GUI
```c norun
#include <std/gui/gui_app.h>

static void on_click(&Widget w, ?&Widget label) {
    match (label) { case some(l): { l.set_text("Clicked!"); } case null: { } }
}

int main() {
    struct GuiApp app = GuiApp::new();
    ?&GuiWindow ow = app.open_window("Hello", 360, 200);
    match (ow) {
        case null: { return 1; }                 // 디스플레이 없음
        case some(win): {
            &Widget root = Widget::vstack().set_padding(16);
            &Widget label = Widget::label("Hello from SafeC");
            root.add_child(label);
            root.add_child(Widget::button("Click me").on_click(on_click, label));
            app.set_root(win, root);
            app.run();                           // 창이 닫힐 때까지
        }
    }
    app.free();
    return 0;
}
```
X11, Win32, Cocoa에서 TrueType 텍스트를 지원하는 안티에일리어싱 벡터 캔버스와 30개 이상의 테마 위젯 — [GUI](/ko/stdlib/gui).
:::
::: tab 비동기
```c norun
#include <std/async/async.h>
#include <std/async/host.h>
extern int printf(const char* fmt, ...);

struct Countdown {
    int st; int i; struct Sleep d;
    struct Poll<int> poll(&Context cx);
    void drop();
};
struct Poll<int> Countdown::poll(&Context cx) {
    ASYNC_BEGIN(self.st, 1);                 // await 지점 하나
    for (self.i = 3; self.i > 0; self.i = self.i - 1) {
        self.d = Sleep::new(100LL);
        AWAIT(1, self.d);                    // 타이머가 울릴 때까지 Pending
        printf("%d\n", self.i);
    }
    ASYNC_RETURN(42);
    ASYNC_END;
}
void Countdown::drop() { if (self.st == 1) self.d.drop(); }

int main() {
    struct Countdown c; c.st = ASYNC_INIT;
    return block_on(&c) == 42 ? 0 : 1;       // or LocalExecutor / ThreadPool / Runtime
}
```
future, waker, join/select/timeout, 채널, epoll·kqueue·WSAPoll 기반 비동기 TCP — [비동기](/ko/stdlib/async).
:::
::: tab 오디오
```c norun
#include <std/audio/audio.h>
#include <std/math.h>

struct Tone { double phase; double step; };

// 실시간 콜백: 출력 버퍼를 채우고, 할당하거나 블록하지 않습니다.
int render(&AudioIo io, ?&Tone tp) {
    match (tp) {
        case some(t): {
            struct Slice<float> out = io.output_f32();   // frames x channels, interleaved
            for (unsigned int f = 0U; f < io.frames; f = f + 1U) {
                float v = (float)(0.2 * std::sin_d(t.phase));
                out.set((unsigned long)f * 2UL, v);
                out.set((unsigned long)f * 2UL + 1UL, v);
                t.phase = t.phase + t.step;
            }
        }
        case null: {}
    }
    return AUDIO_CB_CONTINUE;
}
```
ALSA, JACK, Core Audio, WASAPI, Web Audio에서의 실시간 오디오와 MIDI — [오디오와 MIDI](/ko/stdlib/audio).
:::
::: tab 베어메탈과 wasm
```c norun
#define LED_PORT ((unsigned int*)0x40020014)

// safec --target=arm-7em --freestanding blink.sc
interrupt void systick_handler() {
    unsafe { volatile_store(LED_PORT, volatile_load(LED_PORT) ^ 1U); }
}

// safec --target=wasm32 fib.sc  ->  a module exporting fib()
__attribute__((export_name("fib")))
int fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
```
표준 라이브러리 전체가 모든 베어메탈 타깃으로 빌드되고, `std/wasm`은 SafeC에서 DOM을 다룹니다 — [베어메탈](/ko/reference/baremetal), [WebAssembly와 DOM](/ko/stdlib/wasm).
:::
:::::
::::

:::: section alt
## 설치 {#install}

::::: tabs
::: tab macOS & Linux
```bash
curl -fsSL https://raw.githubusercontent.com/safec-org/SafeC/main/install.sh | bash
```
:::
::: tab Windows
```powershell
irm https://raw.githubusercontent.com/safec-org/SafeC/main/install.ps1 | iex
```
:::
:::::

그다음 프로젝트를 만들고 실행합니다:

```bash
safeguard new hello && cd hello
safeguard run
```

SafeC는 컴파일과 링크에 **clang 21 이상**이 필요합니다(설치 스크립트가 찾아 주거나 설치 방법을 알려 줍니다). 옵션, 요구 사항, 소스 빌드는 [설치](/ko/install)를 보세요.
::::

:::: section
## 시작하기 {#next}

::::: cards 3
::: card book
### [배우기](/ko/learn/)

SafeC 북, 가이드, 언어 레퍼런스, 표준 라이브러리를 한곳에서.
:::
::: card play
### [플레이그라운드](/ko/playground)

브라우저에서 SafeC를 편집하고, 북의 예제를 불러오고, 프로그램을 링크로 공유하세요.
:::
::: card terminal
### [도구](/ko/tools)

`safeguard`는 빌드·테스트·포맷·린트를, `sc-lsp`는 에디터에 진단과 자동 완성을 제공합니다.
:::
::: card rss
### [블로그](/ko/blog/)

릴리스 노트와 소식. [Atom 피드](/ko/blog/feed.xml)를 구독하세요.
:::
::: card chat
### [커뮤니티](/ko/community)

GitHub에서 버그를 알리고, 기능을 제안하고, 풀 리퀘스트를 보내세요.
:::
::: card git
### [GitHub](https://github.com/safec-org/SafeC)

컴파일러, 표준 라이브러리, safeguard, 설치 스크립트 — MIT 라이선스.
:::
:::::
::::
