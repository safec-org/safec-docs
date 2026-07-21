# HTTP (`std/http/`)

웹 API를 구축하기 위한 HTTP/1.1 클라이언트/서버와, CORS, JWT, OAuth2
헬퍼.

```c
#include <std/sched/io_nb_bsd.sc>  // 또는 io_nb_linux.sc / io_nb_win32.sc — 플랫폼에 맞는 백엔드를 선택
#include <std/http/http.h>
#include <std/http/http.sc>
```

## 서버 {#server}

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

## 클라이언트 {#client}

```c
int ok;
struct HttpResponse resp = std::http_get("example.com", 80, "/", &ok);
```

## CORS {#cors}

```c
#include <std/http/cors.h>
#include <std/http/cors.sc>
```

래핑하는 미들웨어가 아니다(단순한 `HttpHandler` 함수 포인터에는 클로저
슬롯이 없다) — 자신의 핸들러에서 직접 호출한다:

```c
struct HttpResponse handle(struct HttpRequest* req) {
    if (std::cors_is_preflight(req))
        return std::cors_preflight_response("*", "GET, POST, OPTIONS", "Content-Type, Authorization");
    struct HttpResponse resp = /* ... */;
    std::cors_apply_headers(&resp, "*");
    return resp;
}
```

## JWT {#jwt}

```c
#include <std/http/jwt.h>
#include <std/http/jwt.sc>
```

`std/crypto/hmac.h` 위에 구축된 HS256(HMAC-SHA256) 압축 직렬화(compact
serialization) JWT.

```c
struct Value claims = std::value_object();
std::value_object_set(&claims, "sub", std::value_string("alice"));
struct String token = std::jwt_sign(&claims, "my-secret-key");

int ok;
struct Value decoded = std::jwt_verify(token.as_ptr(), "my-secret-key", &ok);
```

`jwt_verify`는 `alg: HS256`만 허용하며(`none`을 포함해 다른 알고리즘을
주장하는 토큰은 곧바로 거부된다) 서명을 검사한다 — `exp`/`nbf` 등 다른
클레임 검증은 호출자가 담당해야 한다. sign→verify 왕복, 잘못된 시크릿에
대한 거부, 변조된 토큰에 대한 거부를 통해 검증되었다.

## OAuth2 {#oauth2}

```c
#include <std/http/oauth2.h>
#include <std/http/oauth2.sc>
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
```

로컬 모의(mock) 토큰 엔드포인트에 대해 종단 간(end-to-end) 검증되었다.
브라우저용 `/authorize?...` 리다이렉트 URL을 구성하는 것은 제공자마다
다른 쿼리 파라미터가 필요하며, 호출자의 몫으로 남겨져 있다.
