# HTTP와 웹 (`std/http/`, `std/rpc/`, `std/reactive/`, `std/wasm/`)

웹 API를 구축하기 위한 HTTP/1.1 클라이언트/서버, CORS, JWT, OAuth2,
WebSocket; 그 위의 RPC와 "서버 함수"; 그리고 클라이언트 측을 위한
리액티브 시그널과 wasm32 DOM 하이드레이션입니다.

```c
#include <std/http/http.h>
```

그 아래의 논블로킹 소켓 계층(`std/sched/io_nb.h`)은 플랫폼마다 하나의 백엔드(`io_nb_linux.sc`, `io_nb_bsd.sc`, `io_nb_win32.sc`)를 가지며, `safeguard`가 타깃에 맞는 것을 `libsafec_std.a`에 컴파일해 넣으므로 직접 포함하거나 선택할 것이 없습니다.

범위: 본문은 `Content-Length`로 구분되며(청크 전송 인코딩은 디코딩되지 않은 채 반환), TLS는 없습니다 — 평문 HTTP입니다. HTML 템플릿은 [scx](/ko/advanced/scx)를 참고하세요.

## 서버 {#server}

```c
struct HttpResponse handle(&HttpRequest req) {
    struct HttpResponse resp;
    resp.status = 200;
    resp.headers = std::string_new();
    resp.body = std::string_from("hello");
    return resp;
}

std::http_serve(8080, handle);
```

### 멀티스레딩 {#multithreading}

```c
std::http_serve_threaded(8080, handle, /*numThreads=*/4);
```

`numThreads`개의 워커 스레드가 모두 동일한 리스닝 소켓에 대해
`accept()`를 호출한다 — OS 커널이 현재 `accept()`에서 블록되어 있는
스레드들에게 들어오는 연결을 공평하게 분배하며, 이는 nginx의 워커
모델이 사용하는 것과 동일한 "스레드당 리스너" 패턴으로, 별도의 작업
큐가 필요 없다. `handler`는 최대 `numThreads`개의 OS 스레드에서
동시에 호출될 수 있으므로, 핸들러(그리고 그것이 다루는 모든 것)는 그런
방식으로 호출되어도 안전해야 한다. 검증됨: 300ms의 인위적 지연을 가진
핸들러에 대해 4개의 동시 요청을 보냈을 때 전체가 약 1.2초가 아니라 약
0.34초 만에 완료되었다 — 직렬화가 아니라 실제 병렬 처리가 이루어짐을
확인했다.

`http_serve_reactor(port, handler, numThreads)`도 같은 계약이지만, 각 스레드가 논블로킹 [`Reactor`](/ko/stdlib/sched) 이벤트 루프를 돌리므로 스레드 하나가 여러 연결을 동시에 처리합니다.

## 클라이언트 {#client}

```c
int ok;
struct HttpResponse resp = std::http_get("example.com", 80, "/", &ok);
```

### API {#api}

```c
struct HttpResponse {
    int           status;
    struct String headers;   // raw "Key: value" lines, CRLF-separated
    struct String body;
    void free();
};

struct HttpRequest {
    struct String method;
    struct String path;
    struct String headers;
    struct String body;
    void free();
};

// Blocking HTTP/1.1 request. host is a hostname or numeric IPv4 address.
// extra_headers: null, or "Key: value\r\n" lines (Host, Content-Length and
// Connection: close are added for you). body may be null with body_len 0.
struct HttpResponse http_request(const &char host, unsigned short port,
                                 const &char method, const &char path,
                                 const ?&char extra_headers,
                                 const ?&unsigned char body, unsigned long body_len,
                                 ?&int ok);
struct HttpResponse http_get(const &char host, unsigned short port, const &char path, ?&int ok);
struct HttpResponse http_post(const &char host, unsigned short port, const &char path,
                              const &char content_type,
                              const ?&unsigned char body, unsigned long body_len, ?&int ok);

// Case-insensitive header lookup in a raw header block. Empty String if absent.
struct String http_header_get(const &char raw_headers, const &char name);

// Resolve a hostname or dotted IPv4 to a network-order address. 1 on success.
int http_resolve_host(const &char host, &unsigned int addr_out);

typedef fn struct HttpResponse(&HttpRequest) HttpHandler;

int http_serve(unsigned short port, HttpHandler handler);                         // -1 on setup failure; otherwise never returns
int http_serve_threaded(unsigned short port, HttpHandler handler, int numThreads);
int http_serve_reactor(unsigned short port, HttpHandler handler, int numThreads);

// A complete "HTTP/1.1 <status> <reason>\r\n...\r\n\r\n<body>" response text.
struct String http_build_response(int status, const &char content_type,
                                  const ?&unsigned char body, unsigned long body_len);
const char* http_reason_phrase(int status);   // 404 -> "Not Found"; "Unknown" otherwise
```

`ok`는 DNS, 연결, 파싱 실패 시 0이 됩니다. 필요 없으면 `null`을 넘기세요.

## CORS {#cors}

```c
#include <std/http/cors.h>
```

래핑하는 미들웨어가 아니다(단순한 `HttpHandler` 함수 포인터에는 클로저
슬롯이 없다) — 자신의 핸들러에서 직접 호출한다:

```c
struct HttpResponse handle(&HttpRequest req) {
    if (std::cors_is_preflight(req))
        return std::cors_preflight_response("*", "GET, POST, OPTIONS", "Content-Type, Authorization");
    struct HttpResponse resp = /* ... */;
    std::cors_apply_headers(&resp, "*");
    return resp;
}
```

```c
int  cors_is_preflight(const &HttpRequest req);   // OPTIONS with Access-Control-Request-Method
// Adds Access-Control-Allow-Origin (and -Credentials: true unless the origin is "*").
// Call on every response, not just preflights.
void cors_apply_headers(&HttpResponse resp, const &char allowedOrigin);
// A complete 204 preflight response with Allow-Origin/-Methods/-Headers/-Max-Age.
struct HttpResponse cors_preflight_response(const &char allowedOrigin,
                                            const &char allowedMethods,
                                            const &char allowedHeaders);
```

## JWT {#jwt}

```c
#include <std/http/jwt.h>
```

[`std/crypto/hmac.h`](/ko/stdlib/crypto#hmac-sha256) 위에 구축된 HS256(HMAC-SHA256) 압축 직렬화(compact
serialization) JWT.

```c
struct Value claims = std::value_object();
std::value_object_set(&claims, "sub", std::value_string("alice"));
struct String token = std::jwt_sign(&claims, "my-secret-key");

int ok;
struct Value decoded = std::jwt_verify(token.as_ptr(), "my-secret-key", &ok);
```

```c
struct String jwt_sign(const &Value claims, const &char secret);
// The decoded payload (free with value_free()), or VAL_NULL with *ok = 0.
struct Value  jwt_verify(const &char token, const &char secret, ?&int ok);
```

`jwt_verify`는 `alg: HS256`만 허용하며(`none`을 포함해 다른 알고리즘을
주장하는 토큰은 곧바로 거부된다) 서명을 검사한다 — `exp`/`nbf` 등 다른
클레임 검증은 호출자가 담당해야 한다. sign→verify 왕복, 잘못된 시크릿에
대한 거부, 변조된 토큰에 대한 거부를 통해 검증되었다.

## OAuth2 {#oauth2}

```c
#include <std/http/oauth2.h>
```

클라이언트 측 토큰 교환(RFC 6749) — 제공자에 구애받지 않으며, 실제
OAuth2 토큰 엔드포인트라면 어디든 동작한다:

```c
int ok;
struct OAuth2Token t = std::oauth2_exchange_code(
    "accounts.example.com", 443, "/oauth/token",
    clientId, clientSecret, code, redirectUri, &ok);
// t.accessToken, t.refreshToken, t.tokenType, t.expiresIn

struct OAuth2Token refreshed = std::oauth2_refresh(
    "accounts.example.com", 443, "/oauth/token",
    clientId, clientSecret, t.refreshToken.as_ptr(), &ok);

std::oauth2_token_free(&t);
std::oauth2_token_free(&refreshed);
```

```c
struct OAuth2Token {
    struct String accessToken;
    struct String refreshToken;  // empty if the provider didn't send one
    struct String tokenType;     // usually "Bearer"
    long long     expiresIn;     // seconds; 0 if not sent
};

struct OAuth2Token oauth2_exchange_code(const &char tokenHost, unsigned short tokenPort,
                                        const &char tokenPath, const &char clientId,
                                        const &char clientSecret, const &char code,
                                        const &char redirectUri, ?&int ok);
struct OAuth2Token oauth2_refresh(const &char tokenHost, unsigned short tokenPort,
                                  const &char tokenPath, const &char clientId,
                                  const &char clientSecret, const &char refreshToken, ?&int ok);
void oauth2_token_free(&OAuth2Token t);
```

로컬 모의(mock) 토큰 엔드포인트에 대해 종단 간(end-to-end) 검증되었다.
브라우저용 `/authorize?...` 리다이렉트 URL을 구성하는 것은 제공자마다
다른 쿼리 파라미터가 필요하며, 호출자의 몫으로 남겨져 있다.

## WebSocket {#websocket}

```c
#include <std/http/websocket.h>
```

`http.h`와 같은 블로킹 소켓 위의 RFC 6455 클라이언트와 서버입니다. RFC가 요구하는 대로 클라이언트 프레임은 마스킹하고 서버 프레임은 마스킹하지 않습니다. 조각난 메시지(FIN = 0)는 재조립하지 않습니다: `ws_recv`는 프레임 하나를 메시지 하나로 반환합니다. 핑은 자동으로 응답하지 않고 돌려주므로 `ws_send_pong`으로 직접 응답하세요.

```c
#define WS_OPCODE_CONTINUATION 0x0
#define WS_OPCODE_TEXT         0x1
#define WS_OPCODE_BINARY       0x2
#define WS_OPCODE_CLOSE        0x8
#define WS_OPCODE_PING         0x9
#define WS_OPCODE_PONG         0xA

struct WsConn    { int fd; int is_server; };
struct WsMessage {
    int                  opcode;   // WS_OPCODE_*
    ?&heap unsigned char data;     // null for an empty frame
    unsigned long        len;
    void free();
};

// Client: TCP connect + Upgrade handshake, verifying Sec-WebSocket-Accept.
struct WsConn ws_connect(const &char host, unsigned short port, const &char path, ?&int ok);

// Server
int ws_listen(unsigned short port);           // listening fd, or -1
int ws_accept(int listenFd, &WsConn out);     // 1 once a client completes the handshake

int  ws_send_text(&WsConn conn, const &char text);
int  ws_send_binary(&WsConn conn, const &unsigned char data, unsigned long len);
int  ws_send_ping(&WsConn conn);
int  ws_send_pong(&WsConn conn);
int  ws_send_close(&WsConn conn);             // close frame only; the socket stays open
int  ws_recv(&WsConn conn, &WsMessage out);   // 1 on success, 0 on error/EOF
void ws_close(&WsConn conn);                  // close frame + close the socket
```

```c
#include <std/http/websocket.h>

int main() {
    int listener = std::ws_listen(9001);
    struct WsConn conn;
    while (std::ws_accept(listener, &conn)) {
        struct WsMessage msg;
        while (std::ws_recv(&conn, &msg)) {
            if (msg.opcode == WS_OPCODE_CLOSE) { msg.free(); break; }
            if (msg.opcode == WS_OPCODE_PING)  { std::ws_send_pong(&conn); }
            if (msg.opcode == WS_OPCODE_TEXT)  { std::ws_send_text(&conn, "echo"); }
            msg.free();
        }
        std::ws_close(&conn);
    }
    return 0;
}
```

## RPC {#rpc}

```c
#include <std/rpc/rpc.h>
```

HTTP/1.1 위의 gRPC 형태 RPC입니다: gRPC의 길이 접두 엔벨로프(플래그 1바이트 + 4바이트 빅 엔디언 길이)와 `/Service/Method` 라우팅을 일반 POST에 실어 보냅니다. HTTP/2가 필요한 실제 gRPC와는 와이어 호환되지 **않습니다**. 페이로드는 불투명한 바이트이므로 직접 인코딩하세요 — 예를 들어 [protobuf](/ko/stdlib/serial#protobuf)로.

```c
struct RpcResult {
    int                  ok;            // 1 = success
    ?&heap unsigned char data;          // response bytes; null on failure
    unsigned long        len;
    struct String        error_message; // when ok == 0
    void free();
};

typedef fn struct RpcResult(const unsigned char* data, unsigned long len) RpcMethodHandler;

// Client. Transport failures and handler-reported failures both come back with ok = 0.
struct RpcResult rpc_call(const &char host, unsigned short port, const &char path,
                          const &unsigned char request_data, unsigned long request_len);

// Server: register every method, then serve. Unknown paths get HTTP 404.
void rpc_register(const &char path, RpcMethodHandler handler);
int  rpc_serve(unsigned short port);
```

핸들러는 `ok = 0`과 `error_message`를 반환해 실패를 알리며, `rpc_serve`는 이를 HTTP 오류로 바꿉니다. 핸들러는 `const unsigned char*` 매개변수까지 `RpcMethodHandler`와 정확히 일치해야 합니다.

## 서버 함수 {#server-functions}

```c
#include <std/rpc/server_fn.h>
```

"한 번 작성하고 어디서든 호출" 패턴(Dioxus/Leptos/Next.js 서버 액션)을 RPC 위의 JSON으로 제공합니다. 서버 측 함수를 정의해 등록하면 클라이언트가 이름으로 호출합니다. 인자와 결과는 [`Value`](/ko/stdlib/serial) 트리입니다. 모든 함수는 하나의 경로(`/fn/call`)를 공유하고 이름은 페이로드에 담깁니다.

```c
typedef fn struct Value(const struct Value* args) ServerFn;

void server_fn_register(const &char name, ServerFn handler);   // before serving; up to 64
int  server_fn_serve(unsigned short port);
// args may be null (sent as JSON null). VAL_NULL with *ok = 0 on failure.
struct Value server_fn_call(const &char host, unsigned short port, const &char name,
                            const ?&Value args, ?&int ok);
```

```c
// Server
struct Value add_fn(const struct Value* args) {
    long long a = args->object_get("a")->as_int();
    long long b = args->object_get("b")->as_int();
    return std::value_int(a + b);
}
std::server_fn_register("add", add_fn);
std::server_fn_serve(8080);

// Client
int ok;
struct Value args = std::value_object();
std::value_object_set(&args, "a", std::value_int(2));
std::value_object_set(&args, "b", std::value_int(3));
struct Value result = std::server_fn_call("localhost", 8080, "add", &args, &ok);   // 5
```

## 리액티브 시그널 {#reactive-signals}

```c
#include <std/reactive/signal.h>
```

`Signal<T>`는 값 하나를 보유하고 모든 변경을 구독자에게 밀어 보냅니다 — 가상 DOM 없는 SolidJS 스타일의 세밀한 반응성입니다. 하이드레이션된 scx 페이지의 클라이언트 측 상태 프리미티브입니다.

```c
generic<T>
struct Signal {
    T             value;
    unsigned long version;          // bumped on every set()

    T             get() const;
    &T            get_ref();        // mutate in place, then call notify()
    void          set(T val);       // store, bump version, notify
    unsigned long get_version() const;
    int           changed(&unsigned long last_seen) const; // 1 (and updates last_seen) if version moved
    void          notify();         // run subscribers in registration order
    void          unsubscribe_all();
    void          free();
};

generic<T> struct Signal<T> signal_new(T val);

// listener(userdata) runs on every later set()/notify() — not for the current value.
generic<T, U> void signal_subscribe(&Signal<T> s, fn void(?&U) listener, ?&U userdata);
```

```c
#include <std/reactive/signal.h>

struct Log { int calls; };

static void on_change(?&Log log) {
    match (log) {
        case some(l): { l.calls = l.calls + 1; }
        case null:    { }
    }
}

int main() {
    struct Signal<int> count = std::signal_new(0);
    struct Log log;
    log.calls = 0;
    unsafe { std::signal_subscribe(&count, on_change, (?&Log)&log); }
    count.set(count.get() + 1);      // on_change runs once
    count.set(count.get() + 1);      // and again
    count.free();
    return log.calls == 2 ? 0 : 1;
}
```

컨텍스트 타입이 서로 다른 구독자들이 하나의 목록을 공유하므로 각각은 타입 소거되어 저장되지만, `signal_subscribe`는 `fn void(?&U)`를 `?&U`와만 짝지으므로 모든 호출은 컴파일 타임에 검사됩니다([제네릭 패턴](/ko/stdlib/#generic-pattern) 참고).

## WASM DOM과 하이드레이션 {#wasm-dom-and-hydration}

```c
#include <std/wasm/dom.h>
#include <std/wasm/hydrate.h>
```

`wasm32` 빌드용입니다([WebAssembly](/ko/reference/baremetal#wasm) 참고). wasm 모듈은 DOM에 직접 접근할 수 없으므로 각 DOM 연산은 호스트 JavaScript가 구현하는 WebAssembly 임포트가 되는 `extern`입니다. 문자열은 모듈의 선형 메모리를 가리키는 `(포인터, 길이)`로 전달되며, 모든 호출이 동기식이라 제어가 돌아오기 전에 호스트가 읽기를 마칩니다. 텍스트 내용, 속성, 클릭, 콘솔 로그만 다룹니다.

```c
// Raw imports, implemented by the host page's importObject.env.
extern int  js_get_element(const char* selPtr, unsigned long selLen);
extern void js_set_text(int handle, const char* textPtr, unsigned long textLen);
extern void js_set_attr(int handle, const char* namePtr, unsigned long nameLen,
                        const char* valPtr, unsigned long valLen);
extern int  js_add_click_listener(int handle, int callbackId);
extern void js_console_log(const char* ptr, unsigned long len);

// Wrappers taking NUL-terminated strings.
int  dom_get_element(const &char selector);          // handle, or -1
void dom_set_text(int handle, const &char text);
void dom_set_attr(int handle, const &char name, const &char value);
void dom_console_log(const &char text);

#define DOM_MAX_CALLBACKS 32
generic<T> int dom_register_callback(fn void(?&T) handler, ?&T userdata);   // callback id
generic<T> int dom_on_click(const &char selector, fn void(?&T) handler, ?&T userdata); // element handle, or -1

// Exported as "std_dom_dispatch_event"; the host calls it when an event fires.
void dom_dispatch_event(int callbackId);

unsigned long wasm_strlen(const &char s);                    // no libc on wasm32
void          wasm_itoa(long long v, &char buf, unsigned long cap); // cap >= 24

// hydrate.h — bind a Signal<int> to an element's text.
void hydrate_bind_text_int_handle(&Signal<int> signal, int elementHandle);
int  hydrate_bind_text_int(&Signal<int> signal, const &char selector); // handle, or -1
```

`js_*` 임포트는 JavaScript ABI이므로 원시 포인터를 유지합니다. 바인딩은 초기 텍스트를 쓰지 않으므로 먼저 `dom_set_text`를 호출하거나 `set()`을 한 번 하세요.

```c
#include <std/wasm/hydrate.h>

static struct Signal<int> g_count;

static void on_click(?&int unused) { g_count.set(g_count.get() + 1); }

__attribute__((export_name("start")))
void start() {
    g_count = std::signal_new(0);
    std::hydrate_bind_text_int(&g_count, "#count");
    std::dom_on_click("#inc", on_click, null);
}
```

SafeC 저장소의 `examples/fullstack_demo`가 scx 서버 렌더링, 서버 함수, 하이드레이션된 클라이언트까지 전체 흐름을 보여 줍니다.
