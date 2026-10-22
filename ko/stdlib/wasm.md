# WebAssembly와 DOM (`std/wasm/`)

`wasm32` 모듈을 위한 브라우저 접근입니다: DOM(요소, 내용, 속성, 클래스, 스타일, 폼 컨트롤), 페이로드가 담긴 모든 종류의 이벤트, 타이머와 애니메이션 프레임, `fetch`, `localStorage`/`sessionStorage`, 클라이언트 측 라우팅을 위한 `location`/`history`, 콘솔. 하이드레이션 바인딩은 [`Signal<T>`](/ko/stdlib/http#reactive-signals) 값을 페이지에 연결합니다. 자바스크립트 쪽은 std와 함께 `std/wasm/safec_dom.js`로 제공됩니다.

```c
#include <std/wasm/dom.h>
#include <std/wasm/hydrate.h>
```

wasm32 모듈 빌드 전반은 [WebAssembly](/ko/reference/baremetal#wasm)를 참고하십시오.

## 동작 방식 {#how-it-fits-together}

wasm 모듈은 스스로 페이지에 닿을 수 없습니다. `dom.h`의 각 연산은 링커가 임포트로 바꾸는(`--allow-undefined`) `extern` `js_*` 함수이며, `safec_dom.js`가 그 전부를 제공합니다:

```sh
safeguard build --target wasm32          # build/<package> is the .wasm module
cp <SafeC>/std/wasm/safec_dom.js public/
```

```js
// public/app.js
import { loadSafeC } from "./safec_dom.js";

const app = await loadSafeC(fetch("/app.wasm"));
app.exports.start();          // your __attribute__((export_name)) functions
```

```c
// src/main.sc
#include <std/wasm/dom.h>

static void on_click(?&int unused, &DomEvent ev) {
    std::dom_console(DOM_LOG_INFO, "clicked");
}

__attribute__((export_name("start")))
void start() {
    int button = std::dom_create_element("button");
    std::dom_set_text(button, "Click me");
    std::dom_append(std::dom_body(), button);
    std::dom_on(button, "click", on_click, null);
    std::dom_release(button);
}
```

**문자열**은 모듈 메모리 안의 (포인터, 길이)로 JS에 전달되어 동기적으로 읽힙니다. 돌아오는 문자열은 소유권이 있는 `String`이므로 해제하십시오. **요소**는 JS 쪽 테이블의 정수 핸들입니다. `-1`은 없음이고, `DOM_DOCUMENT`(-2)와 `DOM_WINDOW`(-3)는 document와 window를 가리킵니다. 핸들은 `dom_release()` 전까지 유효하므로 더 필요 없는 핸들은 해제하십시오. 그러지 않으면 테이블이 페이지 수명 내내 커집니다. **콜백**은 함수와 타입이 있는 userdata 포인터를 짝지으며(`fn void(?&T, ...)`와 `?&T`), 컴파일 시점에 검사됩니다.

::: warning 메모리
`std/wasm/wasm_rt.sc`는 wasm32용 `malloc`을 범프 할당기로 제공하며 `free()`는 아무 일도 하지 않습니다. 반환되는 모든 `String`은 메모리를 영구히 쓰므로, 오래 실행되는 페이지에서는 이벤트당 할당을 작게 유지하십시오(또는 `dom_get_prop_buf`로 버퍼를 재사용).
:::

## 요소 {#elements}

```c
int  dom_query(const &char selector);                  // querySelector; -1 if none
int  dom_get_element(const &char selector);            // the same (original name)
int  dom_query_in(int parent, const &char selector);
int  dom_get_by_id(const &char id);
unsigned long dom_query_all(const &char selector, &Vec<int> out);          // appends handles
unsigned long dom_query_all_in(int parent, const &char selector, &Vec<int> out);
int  dom_body();
int  dom_head();

int  dom_create_element(const &char tag);
int  dom_create_text(const &char text);
void dom_append(int parent, int child);
void dom_insert_before(int parent, int child, int ref);   // ref -1: append
void dom_remove(int handle);
void dom_clear(int handle);                                // remove every child
void dom_release(int handle);                              // drop the handle only

int  dom_parent(int handle);
int  dom_first_child(int handle);
int  dom_last_child(int handle);
int  dom_next_sibling(int handle);
int  dom_prev_sibling(int handle);
unsigned long dom_children(int handle, &Vec<int> out);
```

## 내용, 속성, 클래스, 스타일 {#content}

```c
void          dom_set_text(int handle, const &char text);     // textContent
struct String dom_get_text(int handle);
void          dom_set_html(int handle, const &char html);     // innerHTML: trusted markup only
struct String dom_get_html(int handle);

void          dom_set_attr(int handle, const &char name, const &char value);
struct String dom_get_attr(int handle, const &char name);     // empty if absent
int           dom_has_attr(int handle, const &char name);
void          dom_remove_attr(int handle, const &char name);

void dom_class_add(int handle, const &char cls);
void dom_class_remove(int handle, const &char cls);
int  dom_class_toggle(int handle, const &char cls);   // the new state
int  dom_class_contains(int handle, const &char cls);

void dom_set_style(int handle, const &char prop, const &char value);   // CSS name; "" removes
void dom_set_visible(int handle, int visible);                         // style.display
```

`dom_set_html`은 인자를 HTML로 해석합니다. 신뢰할 수 없는 텍스트는 먼저 [`html_escape`](/ko/stdlib/serial)로 이스케이프하거나 `dom_set_text`를 쓰십시오.

## 프로퍼티와 폼 컨트롤 {#properties}

```c
struct String dom_get_value(int handle);
void          dom_set_value(int handle, const &char value);
int           dom_get_checked(int handle);
void          dom_set_checked(int handle, int checked);
void          dom_set_disabled(int handle, int disabled);

// Any property: String(el[name]), el[name] = value, and numeric forms.
struct String dom_get_prop(int handle, const &char name);              // empty for undefined/null
int           dom_get_prop_buf(int handle, const &char name, &char buf, int cap);  // full length, -1 if unset
void          dom_set_prop(int handle, const &char name, const &char value);
double        dom_get_prop_num(int handle, const &char name);
void          dom_set_prop_num(int handle, const &char name, double value);         // 0/1 for booleans
void          dom_call(int handle, const &char method);                // el[method](): "focus", "click", ...
void          dom_focus(int handle);
```

이 함수들은 `DOM_DOCUMENT`와 `DOM_WINDOW`에도 쓸 수 있습니다: `dom_set_prop(DOM_DOCUMENT, "title", "Docs")`, `dom_get_prop_num(DOM_WINDOW, "innerWidth")`, `dom_call(DOM_WINDOW, "print")`. `dom_get_prop_buf`는 할당 없이 호출자 버퍼에 복사하고 NUL로 끝냅니다.

## 이벤트 {#events}

```c
// Any event type; flags: DOM_LISTEN_CAPTURE | DOM_LISTEN_ONCE | DOM_LISTEN_PASSIVE.
generic<T> int dom_add_listener(int handle, const &char type,
                                fn void(?&T, &DomEvent) handler, ?&T userdata, int flags);
generic<T> int dom_on(int handle, const &char type, fn void(?&T, &DomEvent) handler, ?&T userdata);
generic<T> int dom_on_selector(const &char selector, const &char type,
                               fn void(?&T, &DomEvent) handler, ?&T userdata);
void dom_off(int listenerId);

// The original click-only form: handler(userdata), returns the element handle.
generic<T> int dom_on_click(const &char selector, fn void(?&T) handler, ?&T userdata);
```

`dom_on`은 `dom_off`에 넘길 리스너 id를, 잘못된 핸들이거나 콜백 테이블이 가득 찼으면(`DOM_MAX_CALLBACKS`, 리스너·타이머·프레임·fetch가 함께 쓰는 256칸) -1을 반환합니다. 핸들러의 `DomEvent`는 지금 디스패치 중인 이벤트를 읽으므로, 그 접근자는 핸들러 안에서만 유효합니다:

```c
struct DomEvent {
    struct String type() const;
    struct String key() const;            // "a", "Enter", "ArrowUp"
    struct String code() const;           // "KeyA"
    struct String target_value() const;   // event.target.value (input, change)
    int           target_checked() const;
    struct String data() const;           // InputEvent / MessageEvent data
    struct String state_json() const;     // popstate: JSON of history.state
    int           target() const;         // a handle: release it when done
    int           current_target() const;
    double        client_x() const;  double client_y() const;
    double        offset_x() const;  double offset_y() const;
    int           button() const;    int buttons() const;
    double        delta_x() const;   double delta_y() const;   // wheel
    int           modifiers() const;      // DOM_MOD_ALT | CTRL | SHIFT | META
    int           alt_key() const;  int ctrl_key() const;  int shift_key() const;  int meta_key() const;
    int           repeat() const;
    double        timestamp() const;
    void          prevent_default() const;
    void          stop_propagation() const;
};
```

```c
struct Editor { int dirty; };
static struct Editor g_editor;

static void on_key(?&Editor ed, &DomEvent ev) {
    struct String k = ev.key();
    if (ev.ctrl_key() && k.eq_cstr("s")) {
        ev.prevent_default();               // keep the browser's save dialog away
        match (ed) { case some(e): { e.dirty = 0; } case null: { } }
    }
    k.free();
}

unsafe { std::dom_on(DOM_WINDOW, "keydown", on_key, (?&Editor)&g_editor); }
```

## 타이머와 애니메이션 프레임 {#timers}

```c
generic<T> int dom_set_timeout(fn void(?&T) handler, ?&T userdata, int ms);
generic<T> int dom_set_interval(fn void(?&T) handler, ?&T userdata, int ms);
void dom_clear_timer(int timerId);
generic<T> int dom_request_animation_frame(fn void(?&T, double) handler, ?&T userdata);   // once
void dom_cancel_animation_frame(int frameId);
double dom_now();   // performance.now(), ms
```

일회성 타이머나 프레임의 칸은 실행될 때 해제됩니다. 애니메이션 루프라면 핸들러 안에서 다음 프레임을 요청하십시오. id에는 세대가 들어 있어서, 이미 실행된 타이머를 지워도 그 칸을 다시 쓴 새 타이머가 취소되지 않습니다.

## fetch {#fetch}

```c
struct DomFetchResponse {
    int request;
    int status;          // 0 on a network error
    int ok;              // 200-299
    struct String text() const;
    struct String status_text() const;
    struct String url() const;                      // after redirects
    struct String header(const &char name) const;
};

// headers: null or "Name: value" lines; body: null for none.
generic<T> int dom_fetch(const &char method, const &char url, const ?&char headers, const ?&char body,
                         fn void(?&T, &DomFetchResponse) handler, ?&T userdata);
generic<T> int dom_fetch_get(const &char url, fn void(?&T, &DomFetchResponse) handler, ?&T userdata);
```

```c
static void on_results(?&int unused, &DomFetchResponse r) {
    int list = std::dom_query("#results");
    if (!r.ok) { std::dom_set_text(list, "search failed"); std::dom_release(list); return; }
    struct String json = r.text();
    // ... std::json_parse(json.as_ptr(), &ok) and render ...
    json.free();
    std::dom_release(list);
}

std::dom_fetch("POST", "/api/search", "Content-Type: application/json", "{\"q\":\"signal\"}",
               on_results, null);
```

핸들러는 응답 본문이 도착했을 때 한 번 실행되며, 응답의 접근자는 그 안에서만 유효합니다.

## 스토리지 {#storage}

```c
int  dom_storage_get(int kind, const &char key, &String out);     // 1 found (value appended), 0 absent
int  dom_storage_set(int kind, const &char key, const &char value);   // 0 if refused (quota)
void dom_storage_remove(int kind, const &char key);
void dom_storage_clear(int kind);
int  dom_storage_length(int kind);
struct String dom_storage_key(int kind, int index);
```

`kind`는 `DOM_STORAGE_LOCAL`(`localStorage`) 또는 `DOM_STORAGE_SESSION`(`sessionStorage`)입니다.

## location과 history {#location-and-history}

```c
struct String dom_location(int field);   // DOM_LOC_HREF / PATHNAME / SEARCH / HASH / HOST / ORIGIN
struct String dom_location_path();
void dom_history_push(const &char url);      // pushState
void dom_history_replace(const &char url);   // replaceState
void dom_history_back();
void dom_history_forward();
void dom_navigate(const &char url);          // location.assign: a real navigation
```

클라이언트 측 라우팅은 이것들을 `popstate`, 그리고 서버 라우터의 매처인 [`http_route_match`](/ko/stdlib/http#route-patterns)(소켓이 필요 없음)와 결합합니다:

```c
#include <std/wasm/dom.h>
#include <std/http/route.h>

static void render(const &char path) {
    struct Vec<RouteParam> params = std::Vec::new();
    int view = std::dom_query("#view");
    if (std::http_route_match("/docs/:page", path, &params) > 0LL) {
        match (std::http_route_param(&params, "page")) {
            case some(page): { std::dom_set_text(view, page); }
            case null: { }
        }
    } else {
        std::dom_set_text(view, "not found");
    }
    std::http_route_params_clear(&params);
    params.free();
    std::dom_release(view);
}

static void on_pop(?&int unused, &DomEvent ev) {
    struct String p = std::dom_location_path();
    render(p.as_ptr());
    p.free();
}

static void go(const &char url) {
    std::dom_history_push(url);
    render(url);
}

__attribute__((export_name("start")))
void start() {
    std::dom_on(DOM_WINDOW, "popstate", on_pop, null);
    struct String p = std::dom_location_path();
    render(p.as_ptr());
    p.free();
}
```

## 콘솔 {#console}

```c
void dom_console_log(const &char text);
void dom_console(int level, const &char text);   // DOM_LOG_DEBUG / INFO / LOG / WARN / ERROR
```

## 하이드레이션 {#hydration}

`hydrate.h`는 시그널을 DOM에 바인딩합니다: 시그널을 `set()`/`notify()`할 때마다 요소가 갱신되며, SolidJS처럼 가상 DOM이 없습니다. 하이드레이션되는 페이지는 서버에서 렌더링한 HTML(흔히 [scx](/ko/advanced/scx))이고, 모듈은 시작할 때 셀렉터로 상호작용 부분을 찾아 바인딩합니다.

```c
// Write the value now and on every change:
void hydrate_bind_text(&Signal<String> signal, int elementHandle);
void hydrate_bind_attr(&Signal<String> signal, int elementHandle, const &char attr);
void hydrate_bind_class(&Signal<int> signal, int elementHandle, const &char cls);   // on while non-zero
void hydrate_bind_visible(&Signal<int> signal, int elementHandle);
generic<T> void hydrate_bind_text_with(&Signal<T> signal, int elementHandle,
                                       fn void(&String, const &T) format);

// Two-way: the control follows the signal, and user input updates the signal
// ("input" / "change"). Return the listener id.
int hydrate_bind_value(&Signal<String> signal, int elementHandle);
int hydrate_bind_checked(&Signal<int> signal, int elementHandle);

// The original bindings: Signal<int> as decimal text, on change only (they
// don't write the initial value).
void hydrate_bind_text_int_handle(&Signal<int> signal, int elementHandle);
int  hydrate_bind_text_int(&Signal<int> signal, const &char selector);   // handle, or -1
```

```c
#include <std/wasm/hydrate.h>

static struct Signal<int> g_count;
static struct Signal<String> g_name;

static void fmt_count(&String out, const &int v) {
    out.push("Clicked ");
    out.push_int((long long)*v);
    out.push(" times");
}

static void on_click(?&Signal<int> s, &DomEvent ev) {
    match (s) { case some(c): { c.set(c.get() + 1); } case null: { } }
}

__attribute__((export_name("start")))
void start() {
    g_count = std::Signal::new(0);
    g_name = std::Signal::new(std::String::new());
    std::hydrate_bind_text_with(&g_count, std::dom_query("#label"), fmt_count);
    std::hydrate_bind_value(&g_name, std::dom_query("#name"));
    unsafe { std::dom_on_selector("#inc", "click", on_click, (?&Signal<int>)&g_count); }
}
```

## 자바스크립트 글루 {#javascript-glue}

`std/wasm/safec_dom.js`는 의존성 없는 ES 모듈입니다:

```js
import { loadSafeC, createSafeCGlue } from "./safec_dom.js";

// source: a Response, a Promise of one, bytes, or a WebAssembly.Module
const { instance, exports, glue } = await loadSafeC(source, {
    globals: { document, window },   // where to find the browser objects (default: globalThis)
    imports: { my_extra_import() {} },  // more env imports; yours win
    prefix: "[app] ",                // prepended to console output
});

// Or wire it up yourself:
const g = createSafeCGlue(options);
const { instance } = await WebAssembly.instantiate(bytes, g.imports);
g.attach(instance);
g.handle(someElement);   // element -> handle, and g.element(h) back
```

프리스탠딩 모듈이 임포트할 수 있는 몇몇 런타임 심볼(`strlen`, `__multi3`, `abort`, `printf`)의 대체 구현도 제공하며, 모듈 안에 정의가 있으면 그것이 우선합니다.

Node에서는 `globals`로 가짜 `document`/`window`를 넘기면 됩니다. 표준 라이브러리 자체 테스트도 그렇게 실행됩니다: `tests/stdlib/wasm/run.sh`가 `safeguard`로 wasm32 테스트 모듈을 빌드하고, 작은 가짜 DOM(`fake_dom.mjs`)과 실제 글루로 구동합니다.

## 임포트 참조 {#imports-reference}

`js_*` extern은 자바스크립트 ABI이므로 원시 포인터를 그대로 받습니다. 대신 `dom_*` 래퍼를 호출하십시오. 문자열을 돌려주는 임포트는 `(char* buf, int cap)`을 받아 전체 길이(없으면 -1)를 반환합니다. 모듈은 글루가 콜백을 실행할 때 호출하는 `std_dom_dispatch_event`, `std_dom_dispatch_frame`, `std_dom_dispatch_fetch`를 내보냅니다. 다른 호스트나 프레임워크를 위해 임포트를 직접 구현하려면 `dom.h`와 `safec_dom.js`를 나란히 읽으십시오. 임포트 하나는 몇 줄입니다.
