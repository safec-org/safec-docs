# WebAssembly & DOM (`std/wasm/`)

Browser access for `wasm32` modules: the DOM (elements, content, attributes, classes, styles, form controls), events of any type with their payload, timers and animation frames, `fetch`, `localStorage`/`sessionStorage`, `location`/`history` for client-side routing, and the console. Hydration bindings connect [`Signal<T>`](/stdlib/http#reactive-signals) values to the page. The JavaScript side ships with std as `std/wasm/safec_dom.js`.

```c
#include <std/wasm/dom.h>
#include <std/wasm/hydrate.h>
```

For building wasm32 modules in general, see [WebAssembly](/reference/baremetal#wasm).

## How it fits together {#how-it-fits-together}

A wasm module can't reach the page itself. Each operation in `dom.h` is an `extern` `js_*` function that the linker turns into an import (`--allow-undefined`), and `safec_dom.js` provides all of them:

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

**Strings** go to JS as (pointer, length) into the module's memory and are read synchronously. Strings come back as owned `String`s: free them. **Elements** are integer handles into a table on the JS side; `-1` means none, and `DOM_DOCUMENT` (-2) and `DOM_WINDOW` (-3) name the document and window. A handle stays valid until `dom_release()`, so release handles you no longer need, or the table grows for the page's lifetime. **Callbacks** pair a function with a typed userdata pointer: `fn void(?&T, ...)` with a `?&T`, checked at compile time.

::: warning Memory
`std/wasm/wasm_rt.sc` provides `malloc` for wasm32 as a bump allocator: `free()` does nothing. Every returned `String` uses memory for good, so keep allocation per event small in long-running pages (or reuse a buffer with `dom_get_prop_buf`).
:::

## Elements {#elements}

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

## Content, attributes, classes, styles {#content}

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

`dom_set_html` parses its argument as HTML. Escape untrusted text with [`html_escape`](/stdlib/serial) first, or use `dom_set_text`.

## Properties and form controls {#properties}

```c
struct String dom_get_value(int handle);
void          dom_set_value(int handle, const &char value);
int           dom_get_checked(int handle);
void          dom_set_checked(int handle, int checked);
void          dom_set_disabled(int handle, int disabled);

// Any property: String(el[name]), el[name] = value, and numeric forms.
struct String dom_get_prop(int handle, const &char name);              // empty for undefined/null
int dom_get_prop_buf(int handle, const &char name, []char buf);  // full length, -1 if unset
void          dom_set_prop(int handle, const &char name, const &char value);
double        dom_get_prop_num(int handle, const &char name);
void          dom_set_prop_num(int handle, const &char name, double value);         // 0/1 for booleans
void          dom_call(int handle, const &char method);                // el[method](): "focus", "click", ...
void          dom_focus(int handle);
```

These work on `DOM_DOCUMENT` and `DOM_WINDOW` too: `dom_set_prop(DOM_DOCUMENT, "title", "Docs")`, `dom_get_prop_num(DOM_WINDOW, "innerWidth")`, `dom_call(DOM_WINDOW, "print")`. `dom_get_prop_buf` copies into a caller buffer and NUL-terminates without allocating.

## Events {#events}

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

`dom_on` returns a listener id for `dom_off`, or -1 for a bad handle or a full callback table (`DOM_MAX_CALLBACKS`, 256 slots shared by listeners, timers, frames and fetches). The handler's `DomEvent` reads the event being dispatched, so its accessors are valid only inside the handler:

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

## Timers and animation frames {#timers}

```c
generic<T> int dom_set_timeout(fn void(?&T) handler, ?&T userdata, int ms);
generic<T> int dom_set_interval(fn void(?&T) handler, ?&T userdata, int ms);
void dom_clear_timer(int timerId);
generic<T> int dom_request_animation_frame(fn void(?&T, double) handler, ?&T userdata);   // once
void dom_cancel_animation_frame(int frameId);
double dom_now();   // performance.now(), ms
```

A one-shot timer's or frame's slot is freed when it fires. For an animation loop, request the next frame from inside the handler. Ids carry a generation, so clearing a timer that already fired never cancels a newer one that reused its slot.

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

The handler runs once, when the response body has arrived; the response's accessors are valid only inside it.

## Storage {#storage}

```c
int  dom_storage_get(int kind, const &char key, &String out);     // 1 found (value appended), 0 absent
int  dom_storage_set(int kind, const &char key, const &char value);   // 0 if refused (quota)
void dom_storage_remove(int kind, const &char key);
void dom_storage_clear(int kind);
int  dom_storage_length(int kind);
struct String dom_storage_key(int kind, int index);
```

`kind` is `DOM_STORAGE_LOCAL` (`localStorage`) or `DOM_STORAGE_SESSION` (`sessionStorage`).

## Location and history {#location-and-history}

```c
struct String dom_location(int field);   // DOM_LOC_HREF / PATHNAME / SEARCH / HASH / HOST / ORIGIN
struct String dom_location_path();
void dom_history_push(const &char url);      // pushState
void dom_history_replace(const &char url);   // replaceState
void dom_history_back();
void dom_history_forward();
void dom_navigate(const &char url);          // location.assign: a real navigation
```

Client-side routing combines these with `popstate` and [`http_route_match`](/stdlib/http#route-patterns), the server router's matcher (it needs no sockets):

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

## Console {#console}

```c
void dom_console_log(const &char text);
void dom_console(int level, const &char text);   // DOM_LOG_DEBUG / INFO / LOG / WARN / ERROR
```

## Hydration {#hydration}

`hydrate.h` binds signals to the DOM: the element updates on every `set()`/`notify()` of the signal, SolidJS-style, with no virtual DOM. A hydrated page is server-rendered HTML (often [scx](/advanced/scx)) whose interactive parts the module finds by selector and binds on startup.

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

## The JavaScript glue {#javascript-glue}

`std/wasm/safec_dom.js` is a dependency-free ES module:

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

It also provides fallbacks for a few runtime symbols (`strlen`, `__multi3`, `abort`, `printf`) that a freestanding module may import; a definition inside the module takes precedence.

Under Node, pass a fake `document`/`window` through `globals`. That is how the standard library's own test runs: `tests/stdlib/wasm/run.sh` builds a wasm32 test module with `safeguard` and drives it with a small fake DOM (`fake_dom.mjs`) through the real glue.

## Imports reference {#imports-reference}

The `js_*` externs keep raw pointers because they are the JavaScript ABI; call the `dom_*` wrappers instead. String-returning imports take `(char* buf, int cap)` and return the full length (-1 for absent). The module exports `std_dom_dispatch_event`, `std_dom_dispatch_frame` and `std_dom_dispatch_fetch`, which the glue calls to run callbacks. To implement the imports yourself (another host, a different framework), read `dom.h` alongside `safec_dom.js`: each import is a few lines.
