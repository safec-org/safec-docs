# HTTP (`std/http/`)

An HTTP/1.1 client/server, plus CORS, JWT, and OAuth2 helpers for
building a web API.

```c
#include <std/sched/io_nb_bsd.sc>  // or io_nb_linux.sc / io_nb_win32.sc — pick your platform's backend
#include <std/http/http.h>
#include <std/http/http.sc>
```

## Server

```c
struct HttpResponse handle(struct HttpRequest* req) {
    struct HttpResponse resp;
    resp.status = 200;
    resp.headers = std::string_new();
    resp.body = std::string_from("hello");
    return resp;
}

std::http_serve(8080, handle);
```

### Multithreading

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

## Client

```c
int ok;
struct HttpResponse resp = std::http_get("example.com", 80, "/", &ok);
```

## CORS

```c
#include <std/http/cors.h>
#include <std/http/cors.sc>
```

Not a wrapping middleware (a bare `HttpHandler` function pointer has no
closure slot) — call these directly from your own handler:

```c
struct HttpResponse handle(struct HttpRequest* req) {
    if (std::cors_is_preflight(req))
        return std::cors_preflight_response("*", "GET, POST, OPTIONS", "Content-Type, Authorization");
    struct HttpResponse resp = /* ... */;
    std::cors_apply_headers(&resp, "*");
    return resp;
}
```

## JWT

```c
#include <std/http/jwt.h>
#include <std/http/jwt.sc>
```

HS256 (HMAC-SHA256) compact-serialization JWTs, built on `std/crypto/hmac.h`.

```c
struct Value claims = std::value_object();
std::value_object_set(&claims, "sub", std::value_string("alice"));
struct String token = std::jwt_sign(&claims, "my-secret-key");

int ok;
struct Value decoded = std::jwt_verify(token.as_ptr(), "my-secret-key", &ok);
```

`jwt_verify` only accepts `alg: HS256` (a token claiming any other
algorithm, including `none`, is rejected outright) and checks the
signature — `exp`/`nbf`/other claim validation is left to the caller.
Verified against sign→verify round trips, a wrong-secret rejection, and a
tampered-token rejection.

## OAuth2

```c
#include <std/http/oauth2.h>
#include <std/http/oauth2.sc>
```

Client-side token exchange (RFC 6749) — provider-agnostic, works against
any real OAuth2 token endpoint:

```c
int ok;
struct OAuth2Token t = std::oauth2_exchange_code(
    "accounts.example.com", 443, "/oauth/token",
    clientId, clientSecret, code, redirectUri, &ok);
// t.accessToken, t.refreshToken, t.tokenType, t.expiresIn

struct OAuth2Token refreshed = std::oauth2_refresh(
    "accounts.example.com", 443, "/oauth/token",
    clientId, clientSecret, t.refreshToken.as_ptr(), &ok);
```

Verified end-to-end against a local mock token endpoint. Building the
browser-facing `/authorize?...` redirect URL is provider-specific query
parameters and left to the caller.
