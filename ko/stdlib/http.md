# HTTP와 웹 (`std/http/`, `std/rpc/`, `std/reactive/`)

웹 애플리케이션을 만들기 위한 HTTP/1.1 서버와 클라이언트입니다: 설정 가능한 keep-alive 서버, 미들웨어를 갖춘 라우터, 정적 파일, 쿠키, 폼과 업로드, Server-Sent Events, WebSocket, CORS, JWT, OAuth2; 그 위의 RPC와 "서버 함수"; 그리고 클라이언트 측을 위한 리액티브 시그널. 브라우저 쪽 — wasm32 모듈에서의 DOM 접근 — 은 [WebAssembly와 DOM](/ko/stdlib/wasm)에서 다룹니다.

| 헤더 | 제공하는 것 |
|---|---|
| `std/http/http.h` | `HttpRequest`/`HttpResponse`, `HttpServer`, 클라이언트(`http_fetch`, `http_request`), 청크 인코딩, `TcpStream`, `http_open_stream` |
| `std/http/headers.h` | `HttpHeaders`(구조화된 헤더 맵), 헤더 파라미터 |
| `std/http/router.h` | `Router`, 미들웨어, `HttpCtx` |
| `std/http/route.h` | 경로 패턴(`/users/:id`, `/files/*path`), 소켓 없이 사용 가능 |
| `std/http/static_files.h` | 파일과 디렉터리 제공, MIME 타입 |
| `std/http/cookie.h` | `CookieList`, `SetCookie` |
| `std/http/multipart.h` | `multipart/form-data` 파싱과 생성 |
| `std/http/sse.h` | Server-Sent Events: 서버 스트림, 파서, 클라이언트 |
| `std/http/date.h` | HTTP 날짜 |
| `std/http/websocket.h`, `cors.h`, `jwt.h`, `oauth2.h` | WebSocket, CORS, JWT, OAuth2 |
| `std/encoding/url.h`, `deflate.h` | [URL, 쿼리 문자열, gzip](/ko/stdlib/encoding#url-url-h) |

그 아래의 논블로킹 소켓 계층(`std/sched/io_nb.h`)은 플랫폼마다 하나의 백엔드(`io_nb_linux.sc`, `io_nb_bsd.sc`, `io_nb_win32.sc`)를 가지며, `safeguard`가 타깃에 맞는 것을 컴파일하므로 직접 포함하거나 선택할 것이 없습니다. HTML 템플릿은 [scx](/ko/advanced/scx)를 참고하십시오.

범위: 평문 TCP 또는 TLS([HTTPS](#https): `https://`와 `wss://` URL, HTTPS 서버) 위의 HTTP/1.1과 1.0입니다. 본문은 `Content-Length`로 구분되거나, 청크 인코딩이거나(양쪽 모두 디코딩), 응답이라면 연결이 닫힐 때까지 이어질 수 있습니다. HTTP/2는 없습니다.

## 빠른 시작 {#quick-start}

```c
#include <std/http/router.h>

struct HttpResponse hello(&HttpRequest req, &HttpCtx ctx) {
    struct String body = std::String::from("Hello, ");
    body.push(ctx.param_or("name", "world"));
    struct HttpResponse r = std::HttpResponse::text(200, body.as_ptr());
    body.free();
    return r;
}

struct HttpResponse create(&HttpRequest req, &HttpCtx ctx) {
    if (!req.content_type_is("application/json")) return std::HttpResponse::error(415);
    return std::HttpResponse::json(201, req.body.as_ptr());
}

int main() {
    struct Router app = std::Router::new();
    app.use_logger();
    app.use_gzip(1024UL);
    app.get("/hello/:name", hello);
    app.post("/items", create);
    app.static_dir("/assets", "./public");

    struct HttpServerConfig cfg = std::HttpServerConfig::defaults(8080);
    return app.listen(&cfg);    // blocks; -1 if the port can't be bound
}
```

## 서버 {#server}

### HttpServer {#httpserver}

바인드된, 설정 가능한 서버입니다: `threads`개의 워커 스레드가 각각 한 번에 연결 하나씩 처리하며, 지속 연결(keep-alive), 요청 크기 제한, 깔끔한 정지를 지원합니다.

```c
struct HttpServerConfig {
    unsigned short port;            // 0 = any free port (read HttpServer.port)
    int           threads;          // worker threads
    int           keep_alive;       // persistent connections
    int           idle_timeout_ms;  // how long a kept-alive connection may sit idle
    int           io_timeout_ms;    // per send/receive while a request is in flight; 0 = none
    int           max_requests_per_conn;   // 0 = unlimited
    unsigned long max_header_bytes; // request line + headers; larger gets 431
    unsigned long max_body_bytes;   // Content-Length or chunked total; larger gets 413
    ?&TlsServerConfig tls;          // HTTPS when set (see HTTPS below); null = plain HTTP
    // 8 threads, keep-alive (5 s idle), 30 s I/O timeout, 1000 requests per
    // connection, 64 KiB of headers, 16 MiB of body, no TLS.
    static struct HttpServerConfig defaults(unsigned short port);
};

struct HttpServer {
    unsigned short port;            // the bound port
    static struct HttpServer bind(const &HttpServerConfig cfg, ?&int ok);
    int  serve(HttpHandler handler);    // blocks until stop(); 0 after a stop
    int  start(HttpHandler handler);    // the same on a background thread
    void stop();                        // from any thread; idempotent
    int  is_running() const;
};

int http_serve_config(const &HttpServerConfig cfg, HttpHandler handler);   // bind + serve
```

`stop()`은 새 연결 수락을 멈추고, 요청 사이에서 대기 중인 연결을 깨우며, 이미 처리 중인 요청은 끝까지 마치게 한 뒤 리스닝 소켓을 닫습니다. 핸들러 자신의 스레드에서 호출하면 그 스레드는 기다리지 않습니다.

서버가 모든 요청에 대해 하는 일:

- 요청 줄과 헤더를 `max_header_bytes`까지 읽습니다. 요청 앞의 빈 줄은 건너뛰고, 절대 형식 대상(`GET http://host/path`)은 `/path`로 바꿉니다;
- 잘못된 요청에는 직접 응답합니다: 400(잘못된 요청 줄, HTTP/1.1에서 `Host` 누락, 서로 다른 `Content-Length`, `Content-Length`와 함께 온 `Transfer-Encoding`), 413, 431, 501(chunked가 아닌 전송 코딩), 505(HTTP/1.x가 아님) — 그런 다음 연결을 닫습니다;
- 클라이언트가 `Expect: 100-continue`로 알린 본문을 읽기 전에 `100 Continue`를 보냅니다;
- 청크 인코딩된 요청 본문을 디코딩합니다(확장과 트레일러는 버림);
- 클라이언트가 `Connection: close`를 요청하거나, `keep-alive` 없는 HTTP/1.0이거나, 연결당 요청 한도에 이르렀거나, 서버가 정지 중이거나, 핸들러가 `ctx.conn.keep_alive = 0`으로 설정하지 않는 한 연결을 유지합니다;
- 핸들러가 설정한 헤더는 `Content-Length`, `Transfer-Encoding`, `Connection`(프레이밍은 서버 몫)을 제외하고 모두 쓰며, 없으면 `Date`와 기본 `Content-Type`을 더하고, `HEAD`(본문을 만들지 않은 `HEAD` 핸들러는 명시적인 `Content-Length`와 빈 본문으로 GET이었을 때의 길이를 알릴 수 있음), 204, 304에서는 본문을 생략합니다.

워커는 한 번에 연결 하나만 처리하므로, 대기 중인 keep-alive 클라이언트는 최대 `idle_timeout_ms` 동안 워커 하나를 붙잡습니다. 예상 동시 클라이언트 수보다 스레드를 넉넉히 주거나, 연결이 많다면 아래의 `http_serve_reactor`를 쓰십시오.

### 단순 핸들러와 기존 진입점 {#plain-handlers}

```c
typedef fn struct HttpResponse(&HttpRequest) HttpHandler;

int http_serve(unsigned short port, HttpHandler handler);                         // -1 on setup failure; otherwise never returns
int http_serve_threaded(unsigned short port, HttpHandler handler, int numThreads);
int http_serve_reactor(unsigned short port, HttpHandler handler, int numThreads);
```

```c
struct HttpResponse handle(&HttpRequest req) {
    return std::HttpResponse::text(200, "hello");
}

std::http_serve(8080, handle);
```

`http_serve`와 `http_serve_threaded`는 원래 동작을 그대로 유지합니다: 연결당 요청 하나(`Connection: close`). `http_serve_threaded`는 `numThreads`개의 워커가 모두 하나의 리스닝 소켓에서 `accept()`를 호출하며 — 커널이 기다리고 있는 스레드에 연결을 넘겨주는, nginx 워커가 쓰는 "스레드당 리스너" 패턴입니다 — 따라서 `handler`는 최대 `numThreads`개의 스레드에서 동시에 실행되므로 그렇게 호출되어도 안전해야 합니다. `http_serve_reactor`도 같은 계약이지만 각 스레드가 논블로킹 [`Reactor`](/ko/stdlib/sched) 이벤트 루프를 돌리므로 스레드 하나가 여러 연결을 동시에 처리합니다(연결당 요청 하나, 라우터의 연결 분리 없음).

### 연결 {#httpconn}

라우터 핸들러와 미들웨어는 요청이 도착한 연결을 봅니다:

```c
struct HttpConn {
    int            fd;
    unsigned int   peer_addr;      // IPv4, network byte order
    unsigned short peer_port;
    unsigned long  request_index;  // 0 for the connection's first request
    int            keep_alive;     // set to 0 to close after this response
    int            detached;

    // Takes the socket over: the server neither writes the returned response
    // nor closes the socket. Bytes the client already sent past this request
    // are appended to 'early' (may be null).
    struct TcpStream detach(?&String early);
    struct String    peer_string() const;   // "a.b.c.d:port"
};
```

Server-Sent Events, WebSocket 업그레이드, [`HttpChunkedWriter`](#streaming)는 알아서 분리(detach)합니다. 분리된 연결의 핸들러는 실행되는 동안 워커 스레드를 차지하므로, 오래 사는 연결은 별도 스레드에 넘기십시오.

## 요청과 응답 {#requests-and-responses}

```c
struct HttpRequest {
    struct String method;
    struct String path;      // the request-target as sent: "/a/b?x=1"
    struct String headers;   // raw "Key: value" lines, CRLF-separated
    struct String body;

    static struct HttpRequest new(const &char method, const &char path);   // no headers or body
    struct String      header(const &char name) const;   // first value, any case; empty if absent
    int                has_header(const &char name) const;
    struct HttpHeaders header_map() const;
    struct String      path_only() const;                 // before '?'
    struct String      query_string() const;              // after '?'
    struct QueryParams query() const;                     // decoded query parameters
    struct QueryParams form() const;                      // the body as x-www-form-urlencoded
    int                content_type_is(const &char mime) const;
    struct String      cookie(const &char name) const;    // empty if unset
    struct HttpRequest clone() const;
    void free();
};

struct HttpResponse {
    int           status;
    struct String headers;
    struct String body;

    static struct HttpResponse new(int status);                          // empty
    static struct HttpResponse text(int status, const &char body);       // text/plain; charset=utf-8
    static struct HttpResponse html(int status, const &char body);       // text/html; charset=utf-8
    static struct HttpResponse json(int status, const &char body);       // application/json
    static struct HttpResponse bytes(int status, const &char content_type, []const unsigned char data);
    static struct HttpResponse redirect(int status, const &char location);  // 301/302/303/307/308
    static struct HttpResponse error(int status);                        // "404 Not Found" as text

    void          set_header(const &char name, const &char value);       // replaces
    void          add_header(const &char name, const &char value);       // appends
    unsigned long remove_header(const &char name);
    struct String header(const &char name) const;
    int           has_header(const &char name) const;
    struct HttpHeaders header_map() const;
    void          set_body(const &char body);
    void set_body_bytes([]const unsigned char data);
    void free();
};

const &char http_reason_phrase(int status);    // 413 -> "Content Too Large"; "Unknown" otherwise
int http_status_is_informational(int status);  // 1xx
int http_status_is_success(int status);        // 2xx
int http_status_is_redirect(int status);       // 301, 302, 303, 307, 308
int http_status_is_client_error(int status);   // 4xx
int http_status_is_server_error(int status);   // 5xx

// A complete "HTTP/1.1 <status> <reason>\r\n...\r\n\r\n<body>" response text.
struct String http_build_response(int status, const &char content_type, []const unsigned char body);
```

필드는 공개되어 있으므로 응답을 직접 채워도 됩니다(`resp.status = 200; resp.headers = ...`). 생성자는 그 반복을 줄여 줄 뿐입니다.

## 헤더 {#headers}

요청과 응답은 헤더를 하나의 원시 블록으로 보관합니다 — 전송되는 모습 그대로이고 만들기 쉽습니다. 단일 조회와 수정은 블록에 직접 합니다:

```c
struct String http_header_get(const &char raw_headers, const &char name);  // case-insensitive; empty if absent
void          http_headers_set(&String raw, const &char name, const &char value);   // replaces every line
void          http_headers_add(&String raw, const &char name, const &char value);   // appends
unsigned long http_headers_remove(&String raw, const &char name);
int           http_header_has_token(const &char raw, const &char name, const &char token);  // "Connection: keep-alive, Upgrade"
```

`HttpHeaders`(`std/http/headers.h`)는 순회하거나 여러 번 조회할 때 쓰는 구조화된 형태입니다. 순서와 중복(여러 개의 `Set-Cookie`)을 보존하고, 이름은 대소문자를 무시하고 비교하며, 폐기된 접힌 줄(folding)을 이어 붙입니다.

```c
struct HttpHeaders {
    static struct HttpHeaders new();
    static struct HttpHeaders parse(const &char raw);
    unsigned long length() const;
    const ?&char  get(const &char name) const;
    const &char   get_or(const &char name, const &char fallback) const;
    unsigned long count(const &char name) const;
    const ?&char  get_nth(const &char name, unsigned long n) const;
    struct String get_joined(const &char name) const;            // values joined with ", "
    int           has(const &char name) const;
    int           contains_token(const &char name, const &char token) const;
    const ?&char  name_at(unsigned long i) const;
    const ?&char  value_at(unsigned long i) const;
    void          append(const &char name, const &char value);
    void          set(const &char name, const &char value);
    unsigned long remove(const &char name);
    void          clear();
    void          write_to(&String out) const;                    // "Name: value\r\n" per field
    struct String to_raw() const;                                 // the raw block form
    struct HttpHeaders clone() const;
    void          free();
};

// Parameters of structured values: boundary of a Content-Type, filename of a
// Content-Disposition. Quoted strings are unquoted.
struct String http_header_param(const &char value, const &char name);
struct String http_header_main_value(const &char value);   // "multipart/form-data"
int           http_name_eq(const &char a, const &char b);  // case-insensitive
```

## 라우터 {#router}

```c
#include <std/http/router.h>
```

`Router`는 메서드와 경로 패턴을 핸들러에 연결합니다. 패턴은 `/`로 나뉜 세그먼트로, 리터럴, `:name`(세그먼트 하나를 캡처), 또는 마지막의 `*` / `*name`(경로의 나머지, 비어 있을 수 있음)입니다. 캡처한 값은 퍼센트 디코딩됩니다. 등록 순서와 무관하게 가장 구체적인 경로가 이기며 — `/users/me`가 `/users/:id`를, 그것이 `/users/*`를 이깁니다 — 끝의 슬래시는 무시합니다.

```c
typedef fn struct HttpResponse(&HttpRequest req, &HttpCtx ctx) RouteHandler;

struct HttpCtx {
    &HttpConn conn;
    struct Vec<struct RouteParam> params;
    int       route_index;              // -1 when nothing matched
    long long start_ns;
    const ?&char param(const &char name) const;
    const &char  param_or(const &char name, const &char fallback) const;
    const &char  pattern() const;       // the matched route's pattern
};

struct Router {
    static struct Router new();
    void route(const &char method, const &char pattern, RouteHandler handler);   // method "*" = any
    void get(const &char pattern, RouteHandler handler);
    void post(const &char pattern, RouteHandler handler);
    void put(const &char pattern, RouteHandler handler);
    void patch(const &char pattern, RouteHandler handler);
    void del(const &char pattern, RouteHandler handler);      // DELETE
    void options(const &char pattern, RouteHandler handler);
    void any(const &char pattern, RouteHandler handler);
    void static_dir(const &char prefix, const &char dir);     // files under dir at prefix/*
    struct StaticOptions static_opts;                         // used by static_dir
    void set_not_found(RouteHandler handler);

    void use(Middleware mw);
    void use_logger();
    void use_cors(const &char origin, const &char methods, const &char headers);
    void use_body_limit(unsigned long max_bytes);
    void use_gzip(unsigned long min_bytes);
    void use_status_guard();

    struct HttpResponse dispatch(&HttpRequest req, &HttpConn conn);
    int  serve(&HttpServer srv);                 // blocking
    int  start(&HttpServer srv);                 // background
    int  listen(const &HttpServerConfig cfg);    // bind + serve
    void free();
};

struct HttpConn http_conn_none();   // for calling dispatch() outside a server

generic<T> void router_set_state(&Router r, ?&T state);   // one app-wide pointer
generic<T> ?&T  router_state(&HttpCtx ctx);
```

경로는 어떤 라우트와 맞지만 메서드가 맞지 않으면 `Allow` 헤더와 함께 405를 받습니다. `HEAD`는 `GET` 라우트로 대체되고, 자기 라우트가 없는 `OPTIONS`는 `Allow`와 함께 204로 응답하며, 아무것도 맞지 않으면 not-found 핸들러(기본은 단순 404)로 갑니다.

`dispatch`로 네트워크 없이 라우터를 쉽게 테스트할 수 있습니다:

```c
struct HttpRequest req = std::HttpRequest::new("GET", "/hello/Ada");
struct HttpConn conn = std::http_conn_none();
struct HttpResponse resp = app.dispatch(&req, &conn);   // 200 "Hello, Ada"
```

핸들러는 클로저가 없는 단순 함수 포인터이므로 애플리케이션 상태는 전역 변수나 `router_set_state` 뒤에 둡니다. 핸들러는 여러 워커 스레드에서 동시에 실행되므로 공유하는 것은 동기화하십시오.

### 미들웨어 {#middleware}

```c
typedef fn struct HttpResponse(&HttpRequest req, &HttpCtx ctx, &HttpNext next) Middleware;

struct HttpNext {
    struct HttpResponse run(&HttpRequest req, &HttpCtx ctx);   // the rest of the chain
};
```

미들웨어는 매칭 여부와 상관없이 모든 요청에 대해 등록 순서대로 실행되며, 매칭된 라우트의 `ctx.params`를 봅니다. 계속하려면 `next.run(req, ctx)`를 호출하고(돌아온 응답을 후처리할 수 있음), 거기서 멈추려면 자신의 응답을 반환합니다:

```c
struct HttpResponse require_token(&HttpRequest req, &HttpCtx ctx, &HttpNext next) {
    struct String auth = req.header("Authorization");
    int ok = auth.eq_cstr("Bearer secret");
    auth.free();
    if (!ok) return std::HttpResponse::error(401);
    return next.run(req, ctx);
}

app.use(require_token);
```

내장 미들웨어:

| 메서드 | 효과 |
|---|---|
| `use_logger()` | 요청마다 [`std/log.h`](/ko/stdlib/utilities)로 한 줄(`LOG_LEVEL_INFO`, 태그 `http`): `GET /path -> 200 (1.2 ms, 512 B)` |
| `use_cors(origin, methods, headers)` | 프리플라이트에 응답하고 모든 응답에 `Access-Control-Allow-Origin` 등을 더함([CORS](#cors) 참고) |
| `use_body_limit(n)` | `n`바이트를 넘는 본문에 413, 서버 자체 한도보다 낮게 |
| `use_gzip(min)` | 클라이언트가 gzip을 받으면 압축 가능한 타입이고 `min`바이트 이상인 200 응답을 gzip으로 압축하고 `Vary: Accept-Encoding`을 더함 |
| `use_status_guard()` | 상태를 설정하지 않은(0) 응답을 500으로 바꿈 |

### 서버 없이 쓰는 경로 패턴 {#route-patterns}

`std/http/route.h`는 매처만 따로 떼어 낸 것으로(소켓 없음), wasm32 클라이언트도 같은 패턴으로 `location.pathname`을 라우팅할 수 있습니다:

```c
// 0 for no match, else a specificity score; captures appended to params.
long long    http_route_match(const &char pattern, const &char path, &Vec<RouteParam> params);
const ?&char http_route_param(const &Vec<RouteParam> params, const &char name);
void         http_route_params_clear(&Vec<RouteParam> params);
```

## 정적 파일 {#static-files}

```c
#include <std/http/static_files.h>

struct StaticOptions {
    int       etag;            // ETag + If-None-Match (default 1)
    int       last_modified;   // Last-Modified + If-Modified-Since (default 1)
    int       ranges;          // Range requests (default 1)
    long long max_age;         // Cache-Control: max-age; -1 = none (default)
    int       index_html;      // a directory serves index.html (default 1)
    int       spa_fallback;    // a missing path serves the root index.html (default 0)
    int       dotfiles;        // serve names starting with '.' (default 0)
    static struct StaticOptions defaults();
};

struct HttpResponse http_serve_file(const &HttpRequest req, const &char file_path, const ?&StaticOptions opts);
struct HttpResponse http_serve_dir(const &HttpRequest req, const &char root, const &char url_path,
                                   const ?&StaticOptions opts);
const &char http_mime_type(const &char path);          // by extension; application/octet-stream if unknown
int         http_mime_is_compressible(const &char mime);
int         http_safe_join(const &char root, const &char url_path, int allow_dotfiles, &String out);
int         http_file_info(const &char path, &long long size, &long long mtime);   // 1 file, 2 dir, 0 none
```

두 함수 모두 `GET`과 `HEAD`를 제공하며(그 외는 405), 확장자로 정한 `Content-Type`, `ETag`(크기 + 수정 시각), `Last-Modified`를 붙입니다. 일치하는 `If-None-Match`나 더 새롭지 않은 `If-Modified-Since`에는 304로 응답합니다. 단일 바이트 범위(`bytes=a-b`, `a-`, `-n`)에는 `Content-Range`와 함께 206, 만족할 수 없는 범위에는 416, `If-Range` 검증자가 더 이상 맞지 않는 범위에는 파일 전체를 보냅니다. 한 요청의 여러 범위에는 파일 전체로 응답합니다.

`http_serve_dir`는 URL 경로를 퍼센트 디코딩한 뒤 루트를 벗어날 수 있는 것은 모두 403으로 거부합니다: `..` 세그먼트(`%2e%2e`로 써도), 역슬래시, NUL 바이트, 그리고 `dotfiles`를 켜지 않았다면 `.`로 시작하는 이름. 끝 슬래시 없이 요청된 디렉터리는 상대 링크가 맞게 풀리도록 슬래시 형태로 리다이렉트(301)한 뒤 그 `index.html`로 제공합니다.

파일은 응답에 통째로 읽어 들입니다(범위 요청은 그 부분만). 개발 서버와 작은 사이트에 맞으며, `sendfile`은 없습니다.

## 쿠키 {#cookies}

```c
#include <std/http/cookie.h>

struct CookieList {                         // a request's Cookie header(s)
    static struct CookieList parse(const &char cookie_header);
    static struct CookieList from_request(const &HttpRequest req);
    unsigned long length() const;
    const ?&char  get(const &char name) const;
    const ?&char  name_at(unsigned long i) const;
    const ?&char  value_at(unsigned long i) const;
    void          free();
};

struct SetCookie {
    struct String name, value, path, domain;
    long long     max_age;     // -1 = not sent; 0 deletes
    long long     expires;     // Unix seconds; 0 = session cookie
    int           secure, http_only, same_site, partitioned;   // same_site: COOKIE_SAMESITE_*
    static struct SetCookie new(const &char name, const &char value);
    static struct SetCookie removal(const &char name, const &char path);
    static struct SetCookie parse(const &char header_value, ?&int ok);
    struct String to_string() const;
    void          free();
};

void http_set_cookie(&HttpResponse resp, const &SetCookie c);   // adds one Set-Cookie line
```

```c
struct SetCookie c = std::SetCookie::new("session", token);
c.path.push("/");
c.http_only = 1;
c.same_site = COOKIE_SAMESITE_LAX;
c.max_age = 86400LL;
std::http_set_cookie(&resp, &c);   // session=...; Path=/; Max-Age=86400; HttpOnly; SameSite=Lax
c.free();
```

`SameSite=None`에는 브라우저 요구대로 항상 `Secure`를 더합니다. 값은 그대로 쓰므로 공백, `;`, `,`, 따옴표가 든 값은 직접 인코딩하십시오(예: `url_encode`). 값 하나만 필요하면 `req.cookie("name")`으로 충분합니다.

## 폼과 업로드 {#forms-and-uploads}

`application/x-www-form-urlencoded` 본문은 `req.form()`([`QueryParams`](/ko/stdlib/encoding#queryparams))입니다. 파일 업로드인 `multipart/form-data`는 `std/http/multipart.h`가 다룹니다:

```c
struct MultipartPart {
    struct String      name, filename, content_type;
    int                has_filename;
    struct HttpHeaders headers;
    struct String      data;          // binary-safe: use data.length()
};

struct MultipartForm {
    static struct MultipartForm parse(const &char content_type, const &unsigned char body,
                                      unsigned long len, ?&int ok);
    static struct MultipartForm from_request(const &HttpRequest req, ?&int ok);
    unsigned long   length() const;
    ?&MultipartPart get(const &char name);
    ?&MultipartPart part_at(unsigned long i);
    const ?&char    value(const &char name) const;   // a plain field's text
    void            free();
};

struct MultipartBuilder {              // for client requests
    static struct MultipartBuilder new();
    void add_field(const &char name, const &char value);
    void add_file(const &char name, const &char filename, const &char content_type, []const unsigned char data);
    struct String content_type() const;   // "multipart/form-data; boundary=..."
    void finish();                        // then send .body
    void free();
};
```

```c
struct HttpResponse upload(&HttpRequest req, &HttpCtx ctx) {
    int ok = 0;
    struct MultipartForm form = std::MultipartForm::from_request(req, &ok);
    struct HttpResponse r = std::HttpResponse::error(400);
    match (form.get("avatar")) {
        case some(p): {
            r.free();
            r = std::HttpResponse::text(200, p.filename.as_ptr());
        }
        case null: { }
    }
    form.free();
    return r;
}
```

본문 전체가 먼저 메모리에 올라옵니다(서버의 `max_body_bytes`로 제한됨).

## 스트리밍 응답 {#streaming}

### 청크 {#chunked}

```c
void http_chunked_encode(&String out, []const unsigned char data);   // one chunk
void http_chunked_end(&String out);                                                     // "0\r\n\r\n"
int http_chunked_decode([]const unsigned char data, &String out, ?&unsigned long consumed);

struct HttpChunkedWriter {             // a response of unknown length, over a detached connection
    static struct HttpChunkedWriter begin(&HttpConn conn, int status, const ?&char headers);
    int write([]const unsigned char data);   // one chunk; 0 once the connection failed
    int write_str(const &char text);
    int finish();                      // last chunk, then closes the socket
};
```

### Server-Sent Events {#server-sent-events}

```c
#include <std/http/sse.h>

struct SseStream {                     // server side
    static struct SseStream start(&HttpConn conn, const ?&char extra_headers);
    int  send(const &char data);
    int  send_event(const ?&char event, const &char data, const ?&char id);
    int  comment(const &char text);    // keep-alive ping
    int  retry(int ms);
    void close();
};

struct SseEvent { struct String event, data, id; int retry; void free(); };

struct SseParser {                     // incremental text/event-stream parser
    static struct SseParser new();
    void feed([]const unsigned char bytes);
    int  next(&SseEvent out);          // 1 with an event, 0 when more input is needed
    void free();
};

struct SseClient {                     // client side (http:// and https:// URLs)
    static struct SseClient connect(const &char url, const ?&char last_event_id, int timeout_ms, ?&int ok);
    // with https:// settings (null: TlsConfig::new()'s defaults)
    static struct SseClient connect_tls(const &char url, const ?&char last_event_id, const ?&TlsConfig cfg,
                                        int timeout_ms, ?&int ok);
    int  next(&SseEvent out);          // blocks; 0 once the stream ended
    void close();
};
```

```c
struct HttpResponse events(&HttpRequest req, &HttpCtx ctx) {
    struct SseStream s = std::SseStream::start(ctx.conn, null);
    int n = 0;
    while (n < 10 && s.send_event("tick", "{\"ok\":true}", null)) {
        std::thread_sleep_ms(1000UL);
        n = n + 1;
    }
    s.close();
    return std::HttpResponse::new(200);   // ignored: the connection was detached
}
```

클라이언트가 떠나면 `send*`는 0을 반환합니다. 여러 줄 데이터는 여러 `data:` 줄로 나뉘고, 파서는 `EventSource`처럼 `\n`으로 다시 합칩니다. 스트림은 실행되는 동안 워커 스레드를 차지합니다.

## 클라이언트 {#client}

```c
int ok;
struct HttpResponse resp = std::http_get_url("http://example.com/", &ok);
```

### URL 요청 {#url-requests}

```c
struct HttpClientOptions {
    int           max_redirects;       // default 5 (0: don't follow)
    int           connect_timeout_ms;  // default 30 s
    int           io_timeout_ms;       // per send/receive; default 30 s; 0 = none
    int           decompress;          // Accept-Encoding: gzip, deflate + decode (default 1)
    unsigned long max_body_bytes;      // default 64 MiB; 0 = no limit
    ?&TlsConfig   tls;                 // https:// settings; null = TlsConfig::new()'s defaults
    ?&TlsError    tls_error;           // if set, receives why a TLS connection failed
    static struct HttpClientOptions defaults();
};

struct HttpResponse http_fetch(const &char method, const &char url, const ?&char extra_headers, []const unsigned char body, const ?&HttpClientOptions opts, ?&String final_url, ?&int ok);
struct HttpResponse http_get_url(const &char url, ?&int ok);
struct HttpResponse http_post_url(const &char url, const &char content_type, []const unsigned char body, ?&int ok);
int http_decode_body(&HttpResponse resp, unsigned long max_out);   // undo Content-Encoding in place
```

`http_fetch`는 리다이렉트를 따라가며 `Location`을 현재 URL 기준으로 해석합니다. 301, 302, 303은 `HEAD`를 제외한 모든 메서드를 본문 없는 `GET`으로 바꾸고, 307과 308은 요청을 그대로 다시 보냅니다. gzip 또는 deflate 본문은 디코딩하고 `Content-Encoding` 헤더를 제거합니다. `*ok`는 잘못되었거나 `http(s)://`가 아닌 URL, 전송 또는 TLS 실패(`opts.tls_error`가 무엇인지 알려 줌), 잘못된 응답, `max_redirects`를 넘는 리다이렉트, 한도를 넘는 본문에서 0이 됩니다. 4xx나 5xx 상태로 끝난 교환도 `*ok = 1`이므로 `resp.status`를 확인하십시오.

클라이언트는 청크 본문과 서버가 닫을 때까지 이어지는 본문을 읽고, 중간 1xx 응답은 건너뜁니다. 요청마다 새 연결을 씁니다(`Connection: close`).

### 호스트/포트 요청 {#host-port-requests}

```c
// Blocking HTTP/1.1 request. host is a hostname or numeric IPv4 address.
// extra_headers: null, or "Key: value\r\n" lines (Host, Content-Length and
// Connection: close are added for you, unless you set them).
struct HttpResponse http_request(const &char host, unsigned short port, const &char method, const &char path, const ?&char extra_headers, []const unsigned char body, ?&int ok);
// connect_timeout_ms bounds the connect, io_timeout_ms each send/receive.
struct HttpResponse http_request_timeout(const &char host, unsigned short port, const &char method, const &char path, const ?&char extra_headers, []const unsigned char body, int connect_timeout_ms, int io_timeout_ms, ?&int ok);
struct HttpResponse http_get(const &char host, unsigned short port, const &char path, ?&int ok);
struct HttpResponse http_post(const &char host, unsigned short port, const &char path, const &char content_type, []const unsigned char body, ?&int ok);
int http_resolve_host(const &char host, &unsigned int addr_out);   // 1 on success
// Connect (and, with tls, a TLS client handshake; cfg null = defaults with
// ALPN "http/1.1"). fd -1 on failure, *err says why.
struct TcpStream http_open_stream(const &char host, unsigned short port, int tls,
                                  const ?&TlsConfig cfg, int connect_timeout_ms,
                                  int io_timeout_ms, ?&TlsError err);
```

이 함수들은 리다이렉트를 따라가거나 압축을 풀지 않습니다. `http_request`는 `HTTP_DEFAULT_CONNECT_TIMEOUT_MS`(30초) 후 연결을 포기하지만 응답 읽기에는 제한이 없으므로, 제한하려면 `http_request_timeout`을 쓰십시오. 둘 다 DNS 해석은 다루지 않으며, 그것은 시스템 리졸버 자체 설정만이 제한합니다. 청크 요청 본문을 보내려면 `extra_headers`에 `Transfer-Encoding: chunked`를 넣고 `http_chunked_encode`/`http_chunked_end`로 만든 본문을 넘기십시오.

## 날짜 {#dates}

```c
#include <std/http/date.h>

struct String http_date_format(long long unix_seconds);        // "Sun, 06 Nov 1994 08:49:37 GMT"
long long     http_date_parse(const &char text, ?&int ok);     // IMF-fixdate, RFC 850 or asctime
struct String http_date_now();
long long     http_days_from_civil(long long year, int month, int day);
void          http_civil_from_days(long long days, &long long year, &int month, &int day);
```

## HTTPS {#https}

`https://` URL은 URL을 받는 모든 곳에서 동작합니다. `http_fetch`, `http_get_url`, `http_post_url`, http와 https 사이의 리다이렉트, `SseClient`가 그렇고, `wss://`는 `WsConn::connect_url`로 씁니다. TLS는 [`std/tls`](/ko/stdlib/tls)가 제공합니다. 운영체제의 TLS 라이브러리(Linux/BSD에서는 실행 시간에 불러오는 OpenSSL, Windows는 SChannel, macOS는 SecureTransport)가 있으면 그것을, 없으면 SafeC 자체 TLS 1.3을 씁니다. 서버 인증서는 기본으로 시스템의 신뢰 루트와 이름에 대해 검증합니다.

```c
int ok = 0;
struct HttpResponse r = std::http_get_url("https://example.com/", &ok);
```

그 밖의 설정(자체 CA, 클라이언트 인증서, 백엔드)은 `HttpClientOptions::tls`에 [`TlsConfig`](/ko/stdlib/tls#configuration)로 주고, TLS 쪽이 실패하면 `tls_error`가 이유를 받습니다.

```c
struct TlsConfig tc = std::TlsConfig::new();
tc.set_ca_file("internal-ca.pem");             // trust a private CA as well
struct TlsError te = std::TlsError::none();
struct HttpClientOptions o = std::HttpClientOptions::defaults();
o.tls = &tc;
o.tls_error = &te;
struct HttpResponse r = std::http_fetch("GET", "https://intranet.example/api", null, str_bytes(""), &o, null, &ok);
if (!ok && te.code != TLS_OK) { std::println(te.message); }   // e.g. "the certificate has expired"
r.free();
tc.free();
```

호스트/포트 함수(`http_request`, `http_get`, ...)는 평문 HTTP 그대로입니다. 자체 프로토콜에 TLS가 필요하면 `http_open_stream`이 연결(과 핸드셰이크)을 하고, TLS를 거쳐 읽고 쓰는 [`TcpStream`](#tcp-streams)을 돌려줍니다.

**HTTPS 서버**는 설정에 `TlsServerConfig`를 지정한 `HttpServer`입니다. 모든 연결이 TLS 핸드셰이크로 시작하며, 라우터, 정적 파일, SSE, WebSocket 업그레이드(`wss://`)가 그 위에서 그대로 동작합니다.

```c
struct TlsServerConfig tc = std::TlsServerConfig::new();
tc.load_files("cert.pem", "key.pem");          // chain + key, PEM
struct HttpServerConfig cfg = std::HttpServerConfig::defaults(8443);
cfg.tls = &tc;                                  // only needed during bind()
int ok = 0;
struct HttpServer srv = std::HttpServer::bind(&cfg, &ok);   // ok = 0 if the certificate or key won't load
tc.free();
srv.serve(handler);
```

`bind`는 설정으로 `TlsAcceptor`를 만듭니다. 실패하면 `std::TlsAcceptor::new(&tc)`가 이유를 알려 줍니다. `http_serve_reactor`(논블로킹 서버)에는 TLS가 없습니다.

## TCP 스트림 {#tcp-streams}

TCP 연결 위에서 자체 프로토콜을 쓰려면 연결된 소켓을 `TcpStream`으로 감쌉니다. `http_request`와 `WsConn`이 사용하는 스트림이며 [`Stream`](/ko/stdlib/io#io-traits)(`Reader + Writer`)이므로 `read_exact`, `write_all`, `copy_stream`, [`BufReader`와 `BufWriter`](/ko/stdlib/io#buffered-io)를 그대로 쓸 수 있습니다. 쓰기는 `SIGPIPE`를 일으키지 않으며, 상대가 사라졌으면 -1을 반환합니다. `tls`가 설정되어 있으면 그 [TLS 스트림](/ko/stdlib/tls)을 거쳐 읽고 씁니다.

```c
struct TcpStream {
    int fd;
    ?&heap TlsStream tls;                       // null: plain TCP

    static struct TcpStream from_fd(int fd);   // the stream owns fd from then on
    static struct TcpStream over_tls(&heap TlsStream t);   // owns the TLS stream (and its socket)
    int is_tls() const;

    long long read([]unsigned char buf);         // count, 0 once the peer closed, -1 on error
    long long write([]const unsigned char buf);  // count, -1 on error
    int       close();                                            // 0 on success
};
```

소켓은 블로킹이어야 합니다: `tcp_connect_timeout`은 블로킹 소켓을 반환하며, `tcp_accept_nb`로 받은 소켓은 `fd_set_blocking`으로 전환합니다(둘 다 `std/sched/io_nb.h`).

```c
#include <std/http/http.h>
#include <std/io_buf.h>
#include <std/sched/io_nb.h>

int main() {
    unsigned int addr = 0U;
    if (!std::http_resolve_host("127.0.0.1", &addr)) return 1;
    int fd = std::tcp_connect_timeout(addr, 7000, 2000);   // a blocking socket, or -1
    if (fd < 0) return 1;
    struct TcpStream s = std::TcpStream::from_fd(fd);

    struct BufWriter<TcpStream> out = std::BufWriter::over(&s);
    unsigned char ping[5] = { 'P', 'I', 'N', 'G', '\n' };
    int ok = std::write_all(&out, ping[0..5]) && out.flush() == 0;

    struct BufReader<TcpStream> in = std::BufReader::over(&s);
    struct String line = std::String::new();
    ok = ok && in.read_until(&line, "\n");      // one reply line
    line.free();
    s.close();
    return ok ? 0 : 1;
}
```

## CORS {#cors}

```c
#include <std/http/cors.h>
```

자신의 핸들러에서 직접 호출하거나, [Router](#middleware)의 `use_cors`에 맡깁니다:

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
struct Value claims = std::Value::object();
claims.object_set("sub", std::Value::from_str("alice"));
struct String token = std::jwt_sign(&claims, "my-secret-key");

int ok;
struct Value decoded = std::jwt_verify(token.as_ptr(), "my-secret-key", &ok);
```

```c
struct String jwt_sign(const &Value claims, const &char secret);
// The decoded payload (release with .free()), or VAL_NULL with *ok = 0.
struct Value  jwt_verify(const &char token, const &char secret, ?&int ok);
```

`jwt_verify`는 `alg: HS256`만 허용하며(`none`을 포함해 다른 알고리즘을
주장하는 토큰은 곧바로 거부됩니다) 서명을 검사합니다 — `exp`/`nbf` 등 다른
클레임 검증은 호출자가 담당해야 합니다. sign→verify 왕복, 잘못된 시크릿에
대한 거부, 변조된 토큰에 대한 거부를 통해 검증되었습니다.

## OAuth2 {#oauth2}

```c
#include <std/http/oauth2.h>
```

클라이언트 측 토큰 교환(RFC 6749) — 제공자에 구애받지 않으며, 실제
OAuth2 토큰 엔드포인트라면 어디든 동작합니다:

```c
int ok;
struct OAuth2Token t = std::OAuth2Token::exchange_code(
    "accounts.example.com", 443, "/oauth/token",
    clientId, clientSecret, code, redirectUri, &ok);
// t.accessToken, t.refreshToken, t.tokenType, t.expiresIn

struct OAuth2Token refreshed = std::OAuth2Token::refresh(
    "accounts.example.com", 443, "/oauth/token",
    clientId, clientSecret, t.refreshToken.as_ptr(), &ok);

t.free();
refreshed.free();
```

```c
struct OAuth2Token {
    struct String accessToken;
    struct String refreshToken;  // empty if the provider didn't send one
    struct String tokenType;     // usually "Bearer"
    long long     expiresIn;     // seconds; 0 if not sent

    // Each POSTs the grant to <tokenHost>:<tokenPort><tokenPath>; *ok = 0 on
    // any transport, HTTP-status or JSON-shape failure.
    static struct OAuth2Token exchange_code(const &char tokenHost, unsigned short tokenPort,
                                            const &char tokenPath, const &char clientId,
                                            const &char clientSecret, const &char code,
                                            const &char redirectUri, ?&int ok);
    static struct OAuth2Token refresh(const &char tokenHost, unsigned short tokenPort,
                                      const &char tokenPath, const &char clientId,
                                      const &char clientSecret, const &char refreshToken, ?&int ok);

    void free();                 // releases the three strings
};
```

로컬 모의(mock) 토큰 엔드포인트에 대해 종단 간(end-to-end) 검증되었습니다.
브라우저용 `/authorize?...` 리다이렉트 URL을 구성하는 것은 제공자마다
다른 쿼리 파라미터가 필요하며, 호출자의 몫으로 남겨져 있습니다.

## WebSocket {#websocket}

```c
#include <std/http/websocket.h>
```

`http.h`와 같은 블로킹 소켓 위, TCP(`ws://`) 또는 TLS(`wss://`) 위의 RFC 6455 클라이언트와 서버입니다. RFC가 요구하는 대로 클라이언트 프레임은 마스킹하고 서버 프레임은 마스킹하지 않습니다. `conn.recv()`는 프레임 하나씩(조각과 핑 포함) 돌려주고, `conn.recv_message()`는 조각난 메시지를 재조립하며 핑에는 스스로 퐁으로 답하고 퐁은 건너뜁니다. 서버는 자신의 소켓에서 받거나(`ws_listen` + `accept`), [`HttpServer`/`Router`](#router)에 도착한 요청을 업그레이드하므로(`accept_upgrade`) HTTP와 WebSocket이 한 포트를 함께 씁니다. HTTPS 서버라면 그것이 `wss://`입니다. 확장(permessage-deflate)은 지원하지 않습니다.

핸드셰이크는 연결의 [`TcpStream`](#tcp-streams) 위에 놓인 [`BufReader`](/ko/stdlib/io#buffered-io)로 읽고, 각 프레임은 `BufWriter`를 거쳐 씁니다: 헤더, 마스크 키, 버퍼에 들어가는 페이로드가 한 번의 쓰기로 나가며, 더 큰 페이로드는 그 뒤에 이어서 흘려 보냅니다.

```c
#define WS_OPCODE_CONTINUATION 0x0
#define WS_OPCODE_TEXT         0x1
#define WS_OPCODE_BINARY       0x2
#define WS_OPCODE_CLOSE        0x8
#define WS_OPCODE_PING         0x9
#define WS_OPCODE_PONG         0xA

struct WsMessage {
    int                  opcode;   // WS_OPCODE_*
    ?&heap unsigned char data;     // null for an empty frame
    unsigned long        len;
    void free();
};

struct WsConn {
    int fd;
    ?&heap TlsStream tls;   // a wss:// connection's TLS session, else null
    int is_server;   // 1: accepted server-side (sends unmasked, expects masked)

    // Client: TCP connect + Upgrade handshake, verifying Sec-WebSocket-Accept.
    // connect limits the connect and the handshake to WS_DEFAULT_TIMEOUT_MS
    // (30 s); connect_timeout takes the limit (<= 0: none). The limit is
    // cleared once the handshake succeeds.
    static struct WsConn connect(const &char host, unsigned short port, const &char path, ?&int ok);
    static struct WsConn connect_timeout(const &char host, unsigned short port, const &char path,
                                         int timeout_ms, ?&int ok);
    // ... over TLS when tls is set (cfg null: TlsConfig::new()'s defaults)
    static struct WsConn connect_tls(const &char host, unsigned short port, const &char path,
                                     int tls, const ?&TlsConfig cfg, int timeout_ms, ?&int ok);
    // ws://host:port/path?query or wss://... (port 443 by default)
    static struct WsConn connect_url(const &char url, int timeout_ms, ?&int ok);
    static struct WsConn connect_url_tls(const &char url, const ?&TlsConfig cfg, int timeout_ms, ?&int ok);
    // Server side of a request an HttpServer/Router handler received: writes
    // the 101 response and detaches the connection. *ok = 0 if req isn't a
    // version-13 upgrade (answer it normally then, e.g. 426).
    static struct WsConn accept_upgrade(const &HttpRequest req, &HttpConn conn, ?&int ok);

    // Server: fills this connection in place; 1 once a client completes the
    // handshake, 0 if it failed (the client's socket is already closed).
    int  accept(int listenFd);

    int  send_text(const &char text);
    int send_binary([]const unsigned char data);
    int  send_ping();
    int  send_pong();
    int  send_close();                  // close frame only; the socket stays open
    int  recv(&WsMessage out);          // one frame: 1 on success, 0 on error/EOF/malformed frame
    // One whole message (TEXT/BINARY) or a CLOSE frame; pings answered.
    // 0 on error or a message over max_len bytes (0 = no limit).
    int  recv_message(&WsMessage out, unsigned long max_len);
    void close();                       // close frame + close the socket
};

int ws_listen(unsigned short port);     // listening fd for accept(), or -1
int ws_is_upgrade(const &HttpRequest req);   // GET + Upgrade: websocket + a key
```

라우터에서는 일반 경로와 나란히 둡니다:

```c
struct HttpResponse chat(&HttpRequest req, &HttpCtx ctx) {
    if (!std::ws_is_upgrade(req)) return std::HttpResponse::text(426, "WebSocket only");
    int ok = 0;
    struct WsConn ws = std::WsConn::accept_upgrade(req, ctx.conn, &ok);
    if (!ok) return std::HttpResponse::error(400);
    struct WsMessage m;
    while (ws.recv_message(&m, 1048576UL)) {
        if (m.opcode == WS_OPCODE_CLOSE) { m.free(); break; }
        ws.send_text("got it");
        m.free();
    }
    ws.close();
    return std::HttpResponse::new(200);   // ignored: the connection was detached
}
```

`WsConn`은 메시지 단위(`recv`가 프레임 전체를 돌려줌)이므로 바이트 스트림 [`Reader`/`Writer`](/ko/stdlib/io#io-traits)가 아닙니다. 그 아래의 `TcpStream`이 바이트 스트림입니다.

```c
#include <std/http/websocket.h>

int main() {
    int listener = std::ws_listen(9001);
    struct WsConn conn;
    while (conn.accept(listener)) {
        struct WsMessage msg;
        while (conn.recv(&msg)) {
            if (msg.opcode == WS_OPCODE_CLOSE) { msg.free(); break; }
            if (msg.opcode == WS_OPCODE_PING)  { conn.send_pong(); }
            if (msg.opcode == WS_OPCODE_TEXT)  { conn.send_text("echo"); }
            msg.free();
        }
        conn.close();
    }
    return 0;
}
```

## RPC {#rpc}

```c
#include <std/rpc/rpc.h>
```

HTTP/1.1 위의 gRPC 형태 RPC입니다: gRPC의 길이 접두 엔벨로프(플래그 1바이트 + 4바이트 빅 엔디언 길이)와 `/Service/Method` 라우팅을 일반 POST에 실어 보냅니다. HTTP/2가 필요한 실제 gRPC와는 와이어 호환되지 **않습니다**. 페이로드는 불투명한 바이트이므로 직접 인코딩합니다 — 예를 들어 [protobuf](/ko/stdlib/serial#protobuf)로.

```c
struct RpcResult {
    int                  ok;            // 1 = success
    ?&heap unsigned char data;          // response bytes; null on failure
    unsigned long        len;
    struct String        error_message; // when ok == 0
    void free();
};

typedef fn struct RpcResult([]const unsigned char data) RpcMethodHandler;

// Client. Transport failures and handler-reported failures both come back with ok = 0.
struct RpcResult rpc_call(const &char host, unsigned short port, const &char path, []const unsigned char request_data);

// Server: register every method, then serve. Unknown paths get HTTP 404.
void rpc_register(const &char path, RpcMethodHandler handler);
int  rpc_serve(unsigned short port);
```

핸들러는 `ok = 0`과 `error_message`를 반환해 실패를 알리며, `rpc_serve`는 이를 HTTP 오류로 바꿉니다.

## 서버 함수 {#server-functions}

```c
#include <std/rpc/server_fn.h>
```

"한 번 작성하고 어디서든 호출" 패턴(Dioxus/Leptos/Next.js 서버 액션)을 RPC 위의 JSON으로 제공합니다. 서버 측 함수를 정의해 등록하면 클라이언트가 이름으로 호출합니다. 인자와 결과는 [`Value`](/ko/stdlib/serial) 트리입니다. 모든 함수는 하나의 경로(`/fn/call`)를 공유하고 이름은 페이로드에 담깁니다.

```c
typedef fn struct Value(const &Value args) ServerFn;

void server_fn_register(const &char name, ServerFn handler);   // before serving; up to 64
int  server_fn_serve(unsigned short port);
// args may be null (sent as JSON null). VAL_NULL with *ok = 0 on failure.
struct Value server_fn_call(const &char host, unsigned short port, const &char name,
                            const ?&Value args, ?&int ok);
```

```c
// Server
struct Value add_fn(const &Value args) {
    long long a = std::expect_ref(args.object_get("a"), "missing a").as_int();
    long long b = std::expect_ref(args.object_get("b"), "missing b").as_int();
    return std::Value::from_int(a + b);
}
std::server_fn_register("add", add_fn);
std::server_fn_serve(8080);

// Client
int ok;
struct Value args = std::Value::object();
args.object_set("a", std::Value::from_int(2));
args.object_set("b", std::Value::from_int(3));
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

    static struct Signal<T> new(T val);   // version 0, no subscribers

    T             get() const;
    &T            get_ref();        // mutate in place, then call notify()
    void          set(T val);       // store, bump version, notify
    unsigned long get_version() const;
    int           changed(&unsigned long last_seen) const; // 1 (and updates last_seen) if version moved
    void          notify();         // run subscribers in registration order
    void          unsubscribe_all();
    void          free();
};

// listener(userdata) runs on every later set()/notify() — not for the current value.
// A free function: a generic struct's methods can't add type parameters (U) of their own yet.
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
    struct Signal<int> count = std::Signal::new(0);
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


## WebAssembly와 DOM {#wasm-dom-and-hydration}

wasm32 클라이언트를 위한 DOM 접근, 브라우저 API, 시그널 기반 하이드레이션은 [WebAssembly와 DOM](/ko/stdlib/wasm)에 있습니다. SafeC 저장소의 `examples/fullstack_demo`가 전체 흐름을 보여 줍니다: `Router`가 제공하는 scx 페이지, JSON API, 하이드레이션되는 wasm32 클라이언트.
