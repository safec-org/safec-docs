# HTTP & Web (`std/http/`, `std/rpc/`, `std/reactive/`, `std/wasm/`)

An HTTP/1.1 client/server, CORS, JWT, OAuth2 and WebSocket for building a web API; RPC and "server functions" on top of it; and reactive signals plus wasm32 DOM hydration for the client side.

```c
#include <std/http/http.h>
```

The non-blocking socket layer underneath (`std/sched/io_nb.h`) has one backend per platform (`io_nb_linux.sc`, `io_nb_bsd.sc`, `io_nb_win32.sc`); `safeguard` compiles the one matching the target into `libsafec_std.a`, so there is nothing to include or select by hand.

Scope: bodies are `Content-Length`-delimited (chunked transfer-encoding is returned undecoded), and there is no TLS — this is plain HTTP. Headers and bodies are read through a [`BufReader`](/stdlib/io#buffered-io) over the connection's [`TcpStream`](#tcp-streams). For HTML templating see [scx](/advanced/scx).

## Server {#server}

```c
struct HttpResponse handle(&HttpRequest req) {
    struct HttpResponse resp;
    resp.status = 200;
    resp.headers = std::String::new();
    resp.body = std::String::from("hello");
    return resp;
}

std::http_serve(8080, handle);
```

### Multithreading {#multithreading}

```c
std::http_serve_threaded(8080, handle, /*numThreads=*/4);
```

`numThreads` worker threads all call `accept()` on the same listening
socket — the OS kernel fairly distributes incoming connections across
whichever threads are currently blocked in `accept()`, the same
"thread-per-listener" pattern nginx's worker model uses, needing no
manual work queue. `handler` is called concurrently from up to
`numThreads` OS threads, so it (and anything it touches) must be safe to
call that way. Verified: 4 concurrent requests against a handler with a
300ms artificial delay completed in ~0.34s total, not ~1.2s — confirming
real parallel handling, not serialization.

`http_serve_reactor(port, handler, numThreads)` has the same contract, but each thread runs a non-blocking [`Reactor`](/stdlib/sched) event loop, so one thread serves many connections at once.

## Client {#client}

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
// http_request with limits: connect_timeout_ms bounds the TCP connect, io_timeout_ms
// each send/receive (not the whole transfer). <= 0 means no limit; DNS is not covered.
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
const &char http_reason_phrase(int status);   // 404 -> "Not Found"; "Unknown" otherwise
```

`ok` is set to 0 on DNS, connect or parse failure; pass `null` if you don't need it. `http_request` gives up on the connect after `HTTP_DEFAULT_CONNECT_TIMEOUT_MS` (30 s) but has no limit on reading the response; use `http_request_timeout` to bound that.

A body is exactly `Content-Length` bytes: `http_request`, `http_serve` and `http_serve_threaded` stop reading there, and bytes the peer sent past it are not part of `body`. Without a `Content-Length`, the body is whatever arrived together with the header block.

### TCP Streams {#tcp-streams}

For your own protocol on a plain TCP connection, wrap the connected socket in a `TcpStream`. It is the stream `http_request` and `WsConn` use, and it is a [`Stream`](/stdlib/io#io-traits) (`Reader + Writer`), so `read_exact`, `write_all`, `copy_stream`, [`BufReader` and `BufWriter`](/stdlib/io#buffered-io) work on it. Writes never raise `SIGPIPE`; a peer that has gone away gives -1.

```c
struct TcpStream {
    int fd;

    static struct TcpStream from_fd(int fd);   // the stream owns fd from then on

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

Not a wrapping middleware (a bare `HttpHandler` function pointer has no
closure slot) — call these directly from your own handler:

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

RFC 6455 client and server on the same blocking sockets as `http.h`. Client frames are masked and server frames unmasked, as the RFC requires. Fragmented messages (FIN = 0) are not reassembled: `conn.recv()` returns each frame as a message. Pings are returned to you rather than answered — reply with `conn.send_pong()`.

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
    int is_server;   // 1: accepted server-side (sends unmasked, expects masked)

    // Client: TCP connect + Upgrade handshake, verifying Sec-WebSocket-Accept.
    // connect limits the connect and the handshake to WS_DEFAULT_TIMEOUT_MS
    // (30 s); connect_timeout takes the limit (<= 0: none). The limit is
    // cleared once the handshake succeeds.
    static struct WsConn connect(const &char host, unsigned short port, const &char path, ?&int ok);
    static struct WsConn connect_timeout(const &char host, unsigned short port, const &char path,
                                         int timeout_ms, ?&int ok);

    // Server: fills this connection in place; 1 once a client completes the
    // handshake, 0 if it failed (the client's socket is already closed).
    int  accept(int listenFd);

    int  send_text(const &char text);
    int  send_binary(const &unsigned char data, unsigned long len);
    int  send_ping();
    int  send_pong();
    int  send_close();                  // close frame only; the socket stays open
    int  recv(&WsMessage out);          // 1 on success, 0 on error/EOF/malformed frame
    void close();                       // close frame + close the socket
};

int ws_listen(unsigned short port);     // listening fd for accept(), or -1
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

## WASM DOM and Hydration {#wasm-dom-and-hydration}

```c
#include <std/wasm/dom.h>
#include <std/wasm/hydrate.h>
```

For `wasm32` builds (see [WebAssembly](/reference/baremetal#wasm)). A wasm module can't touch the DOM itself, so each DOM operation is an `extern` that becomes a WebAssembly import, implemented by host JavaScript. Strings cross as `(pointer, length)` into the module's linear memory; every call is synchronous, so the host finishes reading before control returns. Only text content, attributes, clicks and console logging are covered.

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

The `js_*` imports keep raw pointers because they are the JavaScript ABI. Binding does not write the initial text; call `dom_set_text` (or `set()` once) first.

```c
#include <std/wasm/hydrate.h>

static struct Signal<int> g_count;

static void on_click(?&int unused) { g_count.set(g_count.get() + 1); }

__attribute__((export_name("start")))
void start() {
    g_count = std::Signal::new(0);
    std::hydrate_bind_text_int(&g_count, "#count");
    std::dom_on_click("#inc", on_click, null);
}
```

The SafeC repository's `examples/fullstack_demo` shows the whole round trip: scx server render, a server function, and a hydrated client.
