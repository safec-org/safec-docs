# HTTP & Web (`std/http/`, `std/rpc/`, `std/reactive/`)

An HTTP/1.1 server and client for building web applications: a configurable keep-alive server, a router with middleware, static files, cookies, forms and uploads, Server-Sent Events, WebSocket, CORS, JWT and OAuth2; RPC and "server functions" on top; and reactive signals for the client side. The browser half — DOM access from a wasm32 module — is [WebAssembly & DOM](/stdlib/wasm).

| Header | What it gives you |
|---|---|
| `std/http/http.h` | `HttpRequest`/`HttpResponse`, `HttpServer`, the client (`http_fetch`, `http_request`), chunked coding, `TcpStream`, `http_open_stream` |
| `std/http/headers.h` | `HttpHeaders` (structured header map), header parameters |
| `std/http/router.h` | `Router`, middleware, `HttpCtx` |
| `std/http/route.h` | path patterns (`/users/:id`, `/files/*path`), usable without sockets |
| `std/http/static_files.h` | file and directory serving, MIME types |
| `std/http/cookie.h` | `CookieList`, `SetCookie` |
| `std/http/multipart.h` | `multipart/form-data` parsing and building |
| `std/http/sse.h` | Server-Sent Events: server stream, parser, client |
| `std/http/date.h` | HTTP dates |
| `std/http/websocket.h`, `cors.h`, `jwt.h`, `oauth2.h` | WebSocket, CORS, JWT, OAuth2 |
| `std/encoding/url.h`, `deflate.h` | [URLs, query strings, gzip](/stdlib/encoding#url-url-h) |

The non-blocking socket layer underneath (`std/sched/io_nb.h`) has one backend per platform (`io_nb_linux.sc`, `io_nb_bsd.sc`, `io_nb_win32.sc`); `safeguard` compiles the one matching the target, so there is nothing to include or select by hand. For HTML templating see [scx](/advanced/scx).

Scope: HTTP/1.1 and 1.0, over plain TCP or TLS ([HTTPS](#https): `https://` and `wss://` URLs, HTTPS servers). Bodies may be `Content-Length`-delimited, chunked (decoded on both sides) or, for responses, run until the connection closes. No HTTP/2.

## Quick start {#quick-start}

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

## Server {#server}

### HttpServer {#httpserver}

A bound, configurable server: `threads` worker threads each serve one connection at a time, with persistent connections (keep-alive), request limits and a clean stop.

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

`stop()` stops accepting, wakes connections idling between requests, lets requests already being handled finish, and closes the listening socket. Called from a handler's own thread it does not wait for that thread.

What the server does for every request:

- reads the request line and headers up to `max_header_bytes`; skips blank lines before a request; accepts absolute-form targets (`GET http://host/path`) and turns them into `/path`;
- answers malformed requests itself: 400 (bad request line, missing `Host` on HTTP/1.1, conflicting `Content-Length`s, `Transfer-Encoding` together with `Content-Length`), 413, 431, 501 (a transfer coding other than chunked), 505 (not HTTP/1.x) — then closes the connection;
- sends `100 Continue` before reading a body the client announced with `Expect: 100-continue`;
- decodes chunked request bodies (extensions and trailers are dropped);
- keeps the connection open unless the client asks for `Connection: close`, it is HTTP/1.0 without `keep-alive`, the per-connection request limit is reached, the server is stopping, or the handler set `ctx.conn.keep_alive = 0`;
- writes every header the handler set except `Content-Length`, `Transfer-Encoding` and `Connection` (the server owns the framing), adds `Date` and a default `Content-Type` when missing, and leaves the body out for `HEAD` (a `HEAD` handler may state the would-be length with an explicit `Content-Length` and an empty body), 204 and 304.

A worker serves one connection at a time, so an idle keep-alive client holds a worker for up to `idle_timeout_ms`. Give the server more threads than you expect concurrent clients, or use `http_serve_reactor` (below) for many connections.

### Plain handlers and the original entry points {#plain-handlers}

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

`http_serve` and `http_serve_threaded` keep their original behavior: one request per connection (`Connection: close`). `http_serve_threaded` runs `numThreads` workers that all `accept()` on one listening socket — the kernel hands each connection to a waiting thread, the "thread-per-listener" pattern nginx's workers use — so `handler` runs on up to `numThreads` threads at once and must be safe to call that way. `http_serve_reactor` has the same contract, but each thread runs a non-blocking [`Reactor`](/stdlib/sched) event loop, so one thread serves many connections at once (one request per connection, without the router's connection detaching).

### The connection {#httpconn}

Router handlers and middleware see the connection a request arrived on:

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

Server-Sent Events, WebSocket upgrades and [`HttpChunkedWriter`](#streaming) detach for you. A detached connection's handler keeps the worker thread for as long as it runs; hand long-lived ones to a thread of their own.

## Requests and responses {#requests-and-responses}

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
    static struct HttpResponse bytes(int status, const &char content_type,
                                     const ?&unsigned char data, unsigned long len);
    static struct HttpResponse redirect(int status, const &char location);  // 301/302/303/307/308
    static struct HttpResponse error(int status);                        // "404 Not Found" as text

    void          set_header(const &char name, const &char value);       // replaces
    void          add_header(const &char name, const &char value);       // appends
    unsigned long remove_header(const &char name);
    struct String header(const &char name) const;
    int           has_header(const &char name) const;
    struct HttpHeaders header_map() const;
    void          set_body(const &char body);
    void          set_body_bytes(const &unsigned char data, unsigned long len);
    void free();
};

const &char http_reason_phrase(int status);    // 413 -> "Content Too Large"; "Unknown" otherwise
int http_status_is_informational(int status);  // 1xx
int http_status_is_success(int status);        // 2xx
int http_status_is_redirect(int status);       // 301, 302, 303, 307, 308
int http_status_is_client_error(int status);   // 4xx
int http_status_is_server_error(int status);   // 5xx

// A complete "HTTP/1.1 <status> <reason>\r\n...\r\n\r\n<body>" response text.
struct String http_build_response(int status, const &char content_type,
                                  const ?&unsigned char body, unsigned long body_len);
```

The fields are public, so a response can still be filled in by hand (`resp.status = 200; resp.headers = ...`); the constructors just save the boilerplate.

## Headers {#headers}

Requests and responses keep their headers as one raw block — what the wire carries, cheap to build. Single lookups and edits work on it directly:

```c
struct String http_header_get(const &char raw_headers, const &char name);  // case-insensitive; empty if absent
void          http_headers_set(&String raw, const &char name, const &char value);   // replaces every line
void          http_headers_add(&String raw, const &char name, const &char value);   // appends
unsigned long http_headers_remove(&String raw, const &char name);
int           http_header_has_token(const &char raw, const &char name, const &char token);  // "Connection: keep-alive, Upgrade"
```

`HttpHeaders` (`std/http/headers.h`) is the structured view, for iterating or repeated lookups. It keeps order and duplicates (several `Set-Cookie`s), compares names ignoring case, and joins obsolete folded lines.

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

## Router {#router}

```c
#include <std/http/router.h>
```

A `Router` maps a method and a path pattern to a handler. Patterns are `/`-separated segments: a literal, `:name` (one segment, captured), or a final `*` / `*name` (the rest of the path, possibly empty). Captured values are percent-decoded. The most specific route wins whatever the registration order — `/users/me` beats `/users/:id`, which beats `/users/*` — and a trailing slash is ignored.

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

A path that matches some route but not the request's method gets 405 with an `Allow` header; `HEAD` falls back to a `GET` route; `OPTIONS` without a route of its own answers 204 with `Allow`; no match at all goes to the not-found handler (a plain 404 by default).

`dispatch` makes a router easy to test without a network:

```c
struct HttpRequest req = std::HttpRequest::new("GET", "/hello/Ada");
struct HttpConn conn = std::http_conn_none();
struct HttpResponse resp = app.dispatch(&req, &conn);   // 200 "Hello, Ada"
```

Handlers are plain function pointers with no closure, so application state lives in globals or behind `router_set_state`. Handlers run on several worker threads at once: synchronize what they share.

### Middleware {#middleware}

```c
typedef fn struct HttpResponse(&HttpRequest req, &HttpCtx ctx, &HttpNext next) Middleware;

struct HttpNext {
    struct HttpResponse run(&HttpRequest req, &HttpCtx ctx);   // the rest of the chain
};
```

Middleware runs in registration order for every request, matched or not, and sees the matched route's `ctx.params`. Call `next.run(req, ctx)` to continue (and post-process the response it returns), or return a response of your own to stop there:

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

The built-ins:

| Method | Effect |
|---|---|
| `use_logger()` | one line per request through [`std/log.h`](/stdlib/utilities) (`LOG_LEVEL_INFO`, tag `http`): `GET /path -> 200 (1.2 ms, 512 B)` |
| `use_cors(origin, methods, headers)` | answers preflights and adds `Access-Control-Allow-Origin` & co. to every response (see [CORS](#cors)) |
| `use_body_limit(n)` | 413 for bodies over `n` bytes, below the server's own limit |
| `use_gzip(min)` | gzip-compresses 200 responses of a compressible type and at least `min` bytes when the client accepts gzip; adds `Vary: Accept-Encoding` |
| `use_status_guard()` | turns a response whose status was never set (0) into a 500 |

### Route patterns without a server {#route-patterns}

`std/http/route.h` is the matcher on its own (no sockets), so a wasm32 client can route `location.pathname` with the same patterns:

```c
// 0 for no match, else a specificity score; captures appended to params.
long long    http_route_match(const &char pattern, const &char path, &Vec<RouteParam> params);
const ?&char http_route_param(const &Vec<RouteParam> params, const &char name);
void         http_route_params_clear(&Vec<RouteParam> params);
```

## Static files {#static-files}

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

Both serve `GET` and `HEAD` (405 otherwise) with a `Content-Type` from the extension, an `ETag` (size + modification time) and `Last-Modified`; a matching `If-None-Match`, or a not-newer `If-Modified-Since`, gets 304. A single byte range (`bytes=a-b`, `a-`, `-n`) gets 206 with `Content-Range`, an unsatisfiable one 416, and a range whose `If-Range` validator no longer matches gets the whole file. Several ranges in one request are answered with the whole file.

`http_serve_dir` percent-decodes the URL path and rejects with 403 anything that could leave the root: a `..` segment (also as `%2e%2e`), a backslash, a NUL byte, and names starting with `.` unless `dotfiles` is set. A directory requested without a trailing slash is redirected (301) to the slash form so relative links resolve, then served by its `index.html`.

Files are read whole into the response (a range reads only its part). That suits development servers and small sites; there is no `sendfile`.

## Cookies {#cookies}

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

`SameSite=None` always adds `Secure`, as browsers require. Values are written verbatim — encode a value that contains spaces, `;`, `,` or quotes yourself (e.g. with `url_encode`). For a single value, `req.cookie("name")` is enough.

## Forms and uploads {#forms-and-uploads}

An `application/x-www-form-urlencoded` body is `req.form()` (a [`QueryParams`](/stdlib/encoding#queryparams)). `multipart/form-data` — file uploads — is `std/http/multipart.h`:

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
    void add_file(const &char name, const &char filename, const &char content_type,
                  const &unsigned char data, unsigned long len);
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

The whole body is in memory first (bounded by the server's `max_body_bytes`).

## Streaming responses {#streaming}

### Chunked {#chunked}

```c
void http_chunked_encode(&String out, const ?&unsigned char data, unsigned long len);   // one chunk
void http_chunked_end(&String out);                                                     // "0\r\n\r\n"
int  http_chunked_decode(const &unsigned char data, unsigned long len, &String out, ?&unsigned long consumed);

struct HttpChunkedWriter {             // a response of unknown length, over a detached connection
    static struct HttpChunkedWriter begin(&HttpConn conn, int status, const ?&char headers);
    int write(const &unsigned char data, unsigned long len);
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
    void feed(const &unsigned char bytes, unsigned long len);
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

`send*` returns 0 once the client has gone away. Multi-line data is split into several `data:` lines, and the parser joins them back with `\n`, as `EventSource` does. A stream holds its worker thread while it runs.

## Client {#client}

```c
int ok;
struct HttpResponse resp = std::http_get_url("http://example.com/", &ok);
```

### URL requests {#url-requests}

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

struct HttpResponse http_fetch(const &char method, const &char url,
                               const ?&char extra_headers,
                               const ?&unsigned char body, unsigned long body_len,
                               const ?&HttpClientOptions opts, ?&String final_url, ?&int ok);
struct HttpResponse http_get_url(const &char url, ?&int ok);
struct HttpResponse http_post_url(const &char url, const &char content_type,
                                  const ?&unsigned char body, unsigned long body_len, ?&int ok);
int http_decode_body(&HttpResponse resp, unsigned long max_out);   // undo Content-Encoding in place
```

`http_fetch` follows redirects, resolving `Location` against the current URL. 301, 302 and 303 turn any method except `HEAD` into a bodyless `GET`; 307 and 308 resend the request unchanged. A gzip or deflate body is decoded and its `Content-Encoding` header removed. `*ok` is 0 for a bad or non-`http(s)://` URL, a transport or TLS failure (`opts.tls_error` says which), a malformed response, more than `max_redirects` redirects, or a body over the limit. A completed exchange with a 4xx or 5xx status still sets `*ok = 1`; check `resp.status`.

The client reads chunked bodies, bodies that run until the server closes, and skips interim 1xx responses. Each request uses a new connection (`Connection: close`).

### Host/port requests {#host-port-requests}

```c
// Blocking HTTP/1.1 request. host is a hostname or numeric IPv4 address.
// extra_headers: null, or "Key: value\r\n" lines (Host, Content-Length and
// Connection: close are added for you, unless you set them).
struct HttpResponse http_request(const &char host, unsigned short port,
                                 const &char method, const &char path,
                                 const ?&char extra_headers,
                                 const ?&unsigned char body, unsigned long body_len,
                                 ?&int ok);
// connect_timeout_ms bounds the connect, io_timeout_ms each send/receive.
struct HttpResponse http_request_timeout(const &char host, unsigned short port,
                                         const &char method, const &char path,
                                         const ?&char extra_headers,
                                         const ?&unsigned char body, unsigned long body_len,
                                         int connect_timeout_ms, int io_timeout_ms,
                                         ?&int ok);
struct HttpResponse http_get(const &char host, unsigned short port, const &char path, ?&int ok);
struct HttpResponse http_post(const &char host, unsigned short port, const &char path,
                              const &char content_type,
                              const ?&unsigned char body, unsigned long body_len, ?&int ok);
int http_resolve_host(const &char host, &unsigned int addr_out);   // 1 on success
// Connect (and, with tls, a TLS client handshake; cfg null = defaults with
// ALPN "http/1.1"). fd -1 on failure, *err says why.
struct TcpStream http_open_stream(const &char host, unsigned short port, int tls,
                                  const ?&TlsConfig cfg, int connect_timeout_ms,
                                  int io_timeout_ms, ?&TlsError err);
```

These don't follow redirects or decompress. `http_request` gives up on the connect after `HTTP_DEFAULT_CONNECT_TIMEOUT_MS` (30 s) but doesn't limit reading the response; use `http_request_timeout` for that. Neither covers DNS resolution, which only the system resolver's own settings bound. To send a chunked request body, pass `Transfer-Encoding: chunked` in `extra_headers` and a body built with `http_chunked_encode`/`http_chunked_end`.

## Dates {#dates}

```c
#include <std/http/date.h>

struct String http_date_format(long long unix_seconds);        // "Sun, 06 Nov 1994 08:49:37 GMT"
long long     http_date_parse(const &char text, ?&int ok);     // IMF-fixdate, RFC 850 or asctime
struct String http_date_now();
long long     http_days_from_civil(long long year, int month, int day);
void          http_civil_from_days(long long days, &long long year, &int month, &int day);
```

## HTTPS {#https}

`https://` URLs work everywhere a URL does — `http_fetch`, `http_get_url`, `http_post_url`, redirects between http and https, `SseClient` — and `wss://` with `WsConn::connect_url`. The TLS comes from [`std/tls`](/stdlib/tls): the operating system's TLS library where there is one (OpenSSL loaded at run time on Linux/BSD, SChannel on Windows, SecureTransport on macOS), SafeC's own TLS 1.3 otherwise. The server's certificate is verified against the system's trusted roots and its name, by default:

```c
int ok = 0;
struct HttpResponse r = std::http_get_url("https://example.com/", &ok);
```

`HttpClientOptions::tls` takes a [`TlsConfig`](/stdlib/tls#configuration) for anything else — your own CA, a client certificate, a backend — and `tls_error` receives the reason when the TLS side fails:

```c
struct TlsConfig tc = std::TlsConfig::new();
tc.set_ca_file("internal-ca.pem");             // trust a private CA as well
struct TlsError te = std::TlsError::none();
struct HttpClientOptions o = std::HttpClientOptions::defaults();
o.tls = &tc;
o.tls_error = &te;
struct HttpResponse r = std::http_fetch("GET", "https://intranet.example/api", null, null, 0UL, &o, null, &ok);
if (!ok && te.code != TLS_OK) { std::println(te.message); }   // e.g. "the certificate has expired"
r.free();
tc.free();
```

The host/port functions (`http_request`, `http_get`, ...) stay plain HTTP. For TLS on your own protocol, `http_open_stream` connects (and handshakes) and returns a [`TcpStream`](#tcp-streams) that reads and writes through TLS.

An **HTTPS server** is an `HttpServer` whose config names a `TlsServerConfig`: every connection starts with a TLS handshake, and routers, static files, SSE and WebSocket upgrades (`wss://`) work over it unchanged.

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

`bind` builds a `TlsAcceptor` from the config; when it fails, `std::TlsAcceptor::new(&tc)` gives the reason. `http_serve_reactor` (the non-blocking server) has no TLS.

## TCP Streams {#tcp-streams}

For your own protocol on a TCP connection, wrap the connected socket in a `TcpStream`. It is the stream `http_request` and `WsConn` use, and it is a [`Stream`](/stdlib/io#io-traits) (`Reader + Writer`), so `read_exact`, `write_all`, `copy_stream`, [`BufReader` and `BufWriter`](/stdlib/io#buffered-io) work on it. Writes never raise `SIGPIPE`; a peer that has gone away gives -1. With `tls` set, it reads and writes through that [TLS stream](/stdlib/tls) instead.

```c
struct TcpStream {
    int fd;
    ?&heap TlsStream tls;                       // null: plain TCP

    static struct TcpStream from_fd(int fd);   // the stream owns fd from then on
    static struct TcpStream over_tls(&heap TlsStream t);   // owns the TLS stream (and its socket)
    int is_tls() const;

    long long read(&unsigned char buf, unsigned long n);         // count, 0 once the peer closed, -1 on error
    long long write(const &unsigned char buf, unsigned long n);  // count, -1 on error
    int       close();                                            // 0 on success
};
```

The socket must be blocking: `tcp_connect_timeout` returns one, and a socket from `tcp_accept_nb` is switched with `fd_set_blocking` (both in `std/sched/io_nb.h`).

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
    int ok = std::write_all(&out, &ping[0], 5UL) && out.flush() == 0;

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

Call these from your own handler — or let a [Router](#middleware) do it with `use_cors`:

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

HS256 (HMAC-SHA256) compact-serialization JWTs, built on [`std/crypto/hmac.h`](/stdlib/crypto#hmac-sha256).

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

`jwt_verify` only accepts `alg: HS256` (a token claiming any other
algorithm, including `none`, is rejected outright) and checks the
signature — `exp`/`nbf`/other claim validation is left to the caller.
Verified against sign→verify round trips, a wrong-secret rejection, and a
tampered-token rejection.

## OAuth2 {#oauth2}

```c
#include <std/http/oauth2.h>
```

Client-side token exchange (RFC 6749) — provider-agnostic, works against
any real OAuth2 token endpoint:

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

Verified end-to-end against a local mock token endpoint. Building the
browser-facing `/authorize?...` redirect URL is provider-specific query
parameters and left to the caller.

## WebSocket {#websocket}

```c
#include <std/http/websocket.h>
```

RFC 6455 client and server on the same blocking sockets as `http.h`, over TCP (`ws://`) or TLS (`wss://`). Client frames are masked and server frames unmasked, as the RFC requires. `conn.recv()` returns single frames (fragments and pings included); `conn.recv_message()` reassembles fragmented messages, answers pings with a pong itself and skips pongs. A server either accepts on a socket of its own (`ws_listen` + `accept`) or upgrades a request that reached an [`HttpServer`/`Router`](#router) (`accept_upgrade`), so HTTP and WebSocket share one port — on an HTTPS server that is `wss://`. No extensions (permessage-deflate).

The handshake is read through a [`BufReader`](/stdlib/io#buffered-io) over the connection's [`TcpStream`](#tcp-streams), and each frame is written through a `BufWriter`: the header, the mask key and a payload that fits the buffer leave in one write, and a larger payload streams through after them.

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
    int  send_binary(const &unsigned char data, unsigned long len);
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

On a router, next to ordinary routes:

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

A `WsConn` is message-based (`recv` yields whole frames), so it is not a byte-stream [`Reader`/`Writer`](/stdlib/io#io-traits); the `TcpStream` under it is.

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

gRPC-shaped RPC over HTTP/1.1: gRPC's length-prefixed envelope (1 flag byte + 4-byte big-endian length) and `/Service/Method` routing, carried in a plain POST. It is **not** wire-compatible with real gRPC, which needs HTTP/2. Payloads are opaque bytes — encode them yourself, e.g. with [protobuf](/stdlib/serial#protobuf).

```c
struct RpcResult {
    int                  ok;            // 1 = success
    ?&heap unsigned char data;          // response bytes; null on failure
    unsigned long        len;
    struct String        error_message; // when ok == 0
    void free();
};

typedef fn struct RpcResult(const &unsigned char data, unsigned long len) RpcMethodHandler;

// Client. Transport failures and handler-reported failures both come back with ok = 0.
struct RpcResult rpc_call(const &char host, unsigned short port, const &char path,
                          const &unsigned char request_data, unsigned long request_len);

// Server: register every method, then serve. Unknown paths get HTTP 404.
void rpc_register(const &char path, RpcMethodHandler handler);
int  rpc_serve(unsigned short port);
```

A handler reports failure by returning `ok = 0` with `error_message` set; `rpc_serve` turns it into an HTTP error.

## Server Functions {#server-functions}

```c
#include <std/rpc/server_fn.h>
```

"Write it once, call it from anywhere" (Dioxus/Leptos/Next.js server actions), as JSON over RPC. You define and register the server-side function; clients call it by name. Arguments and results are [`Value`](/stdlib/serial) trees. All functions share one path (`/fn/call`) and the name travels in the payload.

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

## Reactive Signals {#reactive-signals}

```c
#include <std/reactive/signal.h>
```

`Signal<T>` holds one value and pushes every change to its subscribers — SolidJS-style fine-grained reactivity with no virtual DOM. It is the client-side state primitive for hydrated scx pages.

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

Subscribers with different context types share one list, so each is stored type-erased; `signal_subscribe` only pairs a `fn void(?&U)` with a `?&U`, so every call is checked at compile time (see [Generic Pattern](/stdlib/#generic-pattern)).


## WebAssembly and the DOM {#wasm-dom-and-hydration}

DOM access, browser APIs and signal-driven hydration for wasm32 clients live in [WebAssembly & DOM](/stdlib/wasm). The SafeC repository's `examples/fullstack_demo` shows the whole round trip: scx pages served by a `Router`, a JSON API, and a hydrated wasm32 client.
