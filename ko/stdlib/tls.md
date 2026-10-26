# TLS (`std/tls/`)

직접 연결하거나 accept한 소켓 위의 암호화·인증된 연결입니다. TLS 클라이언트와 서버, 시스템의 신뢰 루트에 대한 인증서 검증, ALPN, 클라이언트 인증서를 제공합니다. `std/http`는 이것으로 `https://`, `wss://`, HTTPS 서버를 지원합니다([HTTPS](/ko/stdlib/http#https) 참고).

```c
#include <std/tls/tls.h>
```

실제 작업은 운영체제의 TLS 라이브러리가 있으면 그것이, 없으면 SafeC 자체 TLS 1.3 구현이 맡습니다. API는 하나로 모두를 다룹니다.

| 백엔드 | 플랫폼 | 내용 |
|---|---|---|
| `TLS_IMPL_OPENSSL` | Linux, BSD | libssl + libcrypto 3.x 또는 1.1.1을 **실행 시간에 `dlopen()`으로 불러옵니다**. 링크할 것도, 헤더도 필요 없으며, OpenSSL이 없는 기계에서는 SafeC 백엔드로 동작합니다 |
| `TLS_IMPL_SCHANNEL` | Windows | SSPI(`secur32`)를 통한 SChannel. 인증서는 Windows 인증서 저장소(`crypt32`)로 검증합니다 |
| `TLS_IMPL_SECURE_TRANSPORT` | macOS | Security.framework의 SecureTransport(최대 TLS 1.2). 인증서는 시스템 키체인에 대해 `SecTrust`로 검증합니다 |
| `TLS_IMPL_SAFEC` | 모든 곳 | [`std/crypto`](/ko/stdlib/crypto)의 X25519, P-256/P-384, AES-GCM, ChaCha20-Poly1305, RSA, ECDSA 위에 만든 SafeC 자체 TLS 1.3 클라이언트와 서버. 루트는 시스템의 PEM 번들(또는 Windows 저장소)에서 읽습니다 |

백엔드가 필요로 하는 것(Linux의 `-ldl`, macOS의 `-framework Security -framework CoreFoundation`, Windows의 `secur32`/`crypt32`)은 `safeguard`가 링크하므로 설정할 것이 없습니다.

## 빠른 시작 {#quick-start}

연결된 블로킹 소켓 위의 클라이언트:

```c
#include <std/tls/tls.h>
#include <std/http/http.h>       // http_resolve_host
#include <std/sched/io_nb.h>     // tcp_connect_timeout
#include <std/str.h>
#include <std/io.h>

int main() {
    unsigned int addr = 0U;
    if (!std::http_resolve_host("example.com", &addr)) return 1;
    int fd = std::tcp_connect_timeout(addr, 443, 5000);
    if (fd < 0) return 1;
    std::sock_set_io_timeout(fd, 10000);              // 핸드셰이크와 모든 읽기의 제한

    struct TlsConfig cfg = std::TlsConfig::new();     // 시스템 루트, 호스트 이름 검사
    cfg.set_alpn("http/1.1");
    struct Result<TlsStream, TlsError> r = std::TlsStream::connect(fd, "example.com", &cfg);
    cfg.free();
    match (r.get_err()) {
        case some(e): { std::println(e.message); return 1; }   // fd는 여전히 호출자가 닫습니다
        case null: { }
    }
    struct TlsStream t = r.value;
    std::print(t.backend_name()); std::print(" "); std::println(t.cipher());

    const &char req = "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n";
    unsafe { t.write(((const unsigned char*)req)[0..std::str_len(req)]); }
    unsigned char buf[4096];
    long long n = t.read(buf[0..4096UL]);            // 읽은 수, 서버가 닫으면 0, 오류면 -1
    t.close();                                        // close_notify 후 소켓을 닫습니다
    return n > 0LL ? 0 : 1;
}
```

`TlsStream`은 [`io_traits.h`의 `Stream`](/ko/stdlib/io#io-traits)(`Reader + Writer`)이므로 `read_exact`, `write_all`, `BufReader<TlsStream>`, `BufWriter<TlsStream>`을 그대로 쓸 수 있습니다. HTTP라면 이것조차 필요 없습니다. `std::http_get_url("https://example.com/", &ok)`가 모두 처리합니다.

서버는 accept한 연결마다 핸드셰이크를 한 번씩 합니다.

```c
struct TlsServerConfig sc = std::TlsServerConfig::new();
sc.load_files("cert.pem", "key.pem");     // 체인(리프 먼저)과 키: PKCS#8, RSA, EC PEM
sc.set_alpn("h2,http/1.1");               // 서버의 선호 순서
struct Result<TlsAcceptor, TlsError> ar = std::TlsAcceptor::new(&sc);
sc.free();                                // acceptor는 필요한 것을 보관합니다
struct TlsAcceptor acc = ar.value;        // (먼저 ar.ok()를 확인하세요)

int fd = std::tcp_accept_nb(listen_fd);   // 여기서부터 블로킹: fd_set_blocking(fd)
struct Result<TlsStream, TlsError> r = acc.accept(fd);   // 핸드셰이크
if (r.ok()) {
    struct TlsStream t = r.value;
    // t.read / t.write ..., t.alpn(), t.peer_certificate(&cert) ...
    t.close();
}
acc.free();                               // 시작한 스트림들의 핸드셰이크가 끝난 뒤에
```

## 백엔드 고르기 {#backends}

`TlsConfig::backend`와 `TlsServerConfig::backend`:

| 값 | 의미 |
|---|---|
| `TLS_BACKEND_AUTO`(기본값) | `$SAFEC_TLS`가 있으면 그것, 없으면 OS 백엔드가 있고 설정이 요구하는 것을 할 수 있을 때 OS 백엔드, 그 외에는 SafeC 백엔드 |
| `TLS_BACKEND_OS` | 플랫폼의 라이브러리(OpenSSL / SChannel / SecureTransport), 없으면 `TLS_ERR_UNAVAILABLE` |
| `TLS_BACKEND_SAFEC` | SafeC 자체 TLS 1.3 |
| `TLS_BACKEND_IMPL_BASE + TLS_IMPL_*` | 이름으로 지정한 백엔드 하나(예: Homebrew OpenSSL이 설치된 macOS에서 OpenSSL) |

환경 변수 `SAFEC_TLS`는 프로그램 전체에서 `TLS_BACKEND_AUTO`를 대신합니다: `os`, `safec`, `openssl`, `schannel`, `securetransport`. `tls_default_impl()`은 `AUTO`가 무엇으로 정해지는지, `tls_backend_available(impl)`은 여기서 그 백엔드를 쓸 수 있는지, `TlsStream::backend_name()`은 연결이 실제로 쓰는 백엔드를 알려 줍니다.

대체(fallback)는 *기능*에만 적용됩니다. OS 라이브러리가 없거나 요청한 기능(SecureTransport의 TLS 1.3, SChannel과 SecureTransport의 서버와 PEM 클라이언트 인증서)을 못 할 때 `AUTO`는 SafeC 백엔드를 씁니다. 백엔드가 있는데 실패한 경우(잘못된 인증서, 핸드셰이크 실패)는 오류로 보고되며 다른 백엔드로 다시 시도하지 않습니다.

## 설정 {#configuration}

```c
struct TlsConfig {
    int           verify_peer;       // 1: 체인과 날짜 검사(기본값)
    int           verify_hostname;   // 1: 이름 검사(기본값)
    int           use_system_roots;  // 1: OS의 루트를 신뢰(기본값)
    struct String ca_pem;            // 추가로 신뢰할 루트, PEM
    struct String ca_file;           // 추가로 신뢰할 루트의 PEM 번들 파일
    struct String server_name;       // SNI + 검증할 이름; 비어 있으면 connect()의 host
    struct String alpn;              // 제안할 프로토콜, 쉼표로 구분("h2,http/1.1")
    int           min_version;       // TLS_VERSION_1_2(기본값) 또는 TLS_VERSION_1_3
    int           max_version;       // TLS_VERSION_1_3(기본값)
    int           backend;           // TLS_BACKEND_AUTO(기본값)
    struct String cert_pem;          // 서버가 요청할 때 보낼 클라이언트 인증서 체인
    struct String key_pem;           // 그 개인 키

    static struct TlsConfig new();
    void set_ca_pem(const &char pem);
    void set_ca_file(const &char path);
    void set_server_name(const &char name);
    void set_alpn(const &char protocols);
    void set_client_cert(const &char cert_pem, const &char key_pem);
    void insecure_skip_verify();     // 검증 끔: 자체 서명 테스트 서버에만
    struct TlsConfig clone() const;
    void free();
};

struct TlsServerConfig {
    struct String cert_pem;          // 리프 먼저, 그다음 중간 인증서
    struct String key_pem;           // PKCS#8, RSA, EC PEM
    struct String alpn;              // 서버가 말하는 프로토콜, 선호 순
    int           min_version;       // TLS_VERSION_1_2(기본값)
    int           max_version;       // TLS_VERSION_1_3(기본값)
    int           backend;           // TLS_BACKEND_AUTO(기본값)
    int           verify_client;     // 0 없음(기본값), 1 인증서 요청, 2 인증서 필수
    struct String client_ca_pem;     // 클라이언트 인증서의 루트

    static struct TlsServerConfig new();
    int  load_files(const &char cert_path, const &char key_path);   // 하나라도 못 읽으면 0
    void set_alpn(const &char protocols);
    void free();
};
```

설정은 자신의 문자열을 소유합니다(`free()`하세요). 스트림과 acceptor는 필요한 것을 복사해 둡니다. 호스트가 IP 리터럴이면 SNI를 보내지 않으며(RFC 6066), 인증서의 IP 주소 항목과 비교합니다.

## 스트림 {#streams}

```c
struct TlsStream {
    // 클라이언트, 블로킹: 핸드셰이크 전체. 실패해도 fd는 열린 채로 남습니다.
    static struct Result<TlsStream, TlsError> connect(int fd, const &char host, const ?&TlsConfig cfg);
    // 클라이언트, 논블로킹: 준비만 하고 handshake()로 진행합니다.
    static struct Result<TlsStream, TlsError> client(int fd, const &char host, const ?&TlsConfig cfg);

    int       handshake();     // 0 완료, TLS_WANT_READ / TLS_WANT_WRITE, -1 실패
    long long read([]unsigned char buf);         // 읽은 수, 0 = 상대가 닫음, -1, TLS_WANT_*
    long long write([]const unsigned char buf);  // 쓴 수, -1, TLS_WANT_*
    int       shutdown();      // close_notify 보내기(한 번)
    int       close();         // shutdown, 해제, 소켓 닫기
    void      release();       // TLS 상태만 해제하고 fd는 열어 둠

    int  is_open() const;
    int  version() const;               // TLS_VERSION_1_2 / TLS_VERSION_1_3
    const &char cipher() const;         // IANA 이름: "TLS_AES_128_GCM_SHA256"
    const &char alpn() const;           // 합의된 프로토콜, 없으면 ""
    const &char backend_name() const;   // "openssl", "schannel", "securetransport", "safec"
    int  peer_certificate(&X509Cert out) const;   // 상대 리프 인증서 요약
    const &TlsError last_error() const;
};

struct TlsAcceptor {
    static struct Result<TlsAcceptor, TlsError> new(const &TlsServerConfig cfg);
    struct Result<TlsStream, TlsError> accept(int fd);   // 블로킹 핸드셰이크
    struct Result<TlsStream, TlsError> start(int fd);    // 논블로킹: handshake()로 진행
    void free();
};
```

`peer_certificate`는 상대의 리프 인증서로 [`X509Cert`](/ko/stdlib/crypto#x509-certificate-parser)(주체, 발급자, 유효 기간, DNS 이름)를 채웁니다. DER 바이트는 `TlsStream::peer_der_`에 있습니다.

### 논블로킹 사용 {#non-blocking}

논블로킹 소켓에서는 `TlsStream::client()`(또는 `TlsAcceptor::start()`)로 시작해 `handshake()`가 0을 돌려줄 때까지 부릅니다. 이 함수와 `read()`, `write()`는 소켓이 블록될 상황이면 `TLS_WANT_READ` 또는 `TLS_WANT_WRITE`를 돌려줍니다. fd가 읽기 또는 쓰기 가능해질 때까지 기다린 뒤([`std::Reactor`](/ko/stdlib/sched), [`std/async`의 `IoReady`](/ko/stdlib/async)) 다시 부르면 됩니다. `write()`는 암호화된 레코드 일부가 아직 대기 중이어도 데이터를 받아들일 수 있으며, 다음 호출이 그것을 먼저 보냅니다. *블로킹* 소켓에서 `TLS_WANT_*`는 `SO_RCVTIMEO`/`SO_SNDTIMEO`가 만료되었다는 뜻입니다(`connect()`는 `TLS_ERR_TIMEOUT`을 보고합니다).

## 오류 {#errors}

실패는 `TlsError`로 돌아옵니다(`Result` 안에, 또는 `last_error()`). `code`, 받거나 보낸 TLS `alert`(없으면 -1), 백엔드 고유의 `detail` 코드(OpenSSL 오류, `SECURITY_STATUS`, `OSStatus`), 무엇이 잘못됐는지 설명하는 `message`가 들어 있습니다.

| 코드 | 의미 |
|---|---|
| `TLS_ERR_CERT_UNTRUSTED` | 신뢰하는 루트까지의 체인이 없음: 자체 서명, 알 수 없는 CA, 중간 인증서 누락 |
| `TLS_ERR_CERT_HOSTNAME` | 인증서가 이 호스트의 것이 아님 |
| `TLS_ERR_CERT_EXPIRED` | 유효 기간 밖 |
| `TLS_ERR_CERT_INVALID` | 잘못된 서명, 맞지 않는 키 용도, 손상, 지원하지 않는 critical 확장 |
| `TLS_ERR_CERT_REVOKED` | 폐기됨(검사하는 OS 백엔드에서) |
| `TLS_ERR_NO_CERT` | 필요한 곳에 상대 인증서가 없음 |
| `TLS_ERR_HANDSHAKE` | 공통 버전, 암호 스위트, 그룹, 서명 방식이 없음 |
| `TLS_ERR_ALERT` | 상대가 치명적 alert를 보냄(`alert`가 무엇인지 알려 줌) |
| `TLS_ERR_PROTOCOL` | 손상되었거나 예상하지 못한 메시지 |
| `TLS_ERR_IO`, `TLS_ERR_CLOSED`, `TLS_ERR_TIMEOUT` | 소켓 실패, 핸드셰이크 중 닫힘, 시간 초과 |
| `TLS_ERR_UNAVAILABLE` | 이 시스템에 백엔드가 없거나 이것을 할 수 없음 |
| `TLS_ERR_CONFIG` | 읽을 수 없는 PEM, 맞지 않는 키, 빠진 설정 |

`tls_error_name(code)`는 코드의 고정된 영어 이름을 돌려줍니다.

## 인증서 검증 {#verification}

기본으로 켜져 있습니다. 체인은 신뢰하는 루트로 이어져야 하고, 모든 인증서가 유효 기간 안에 있어야 하며, CA 인증서는 CA여야 하고(basicConstraints, keyCertSign, pathLenConstraint), 리프의 확장 키 용도가 TLS 서버(서버 쪽에서는 클라이언트)를 허용해야 하며, 이름이 맞아야 합니다. DNS 이름은 인증서 `subjectAltName`의 DNS 항목과(대소문자 무시, `*.` 와일드카드는 맨 왼쪽 레이블 하나에만 일치), IP 리터럴은 IP 항목과 비교하고, 주체의 CN은 그런 항목이 전혀 없을 때만 봅니다.

- **OpenSSL**은 기본 CA 경로(`OPENSSLDIR`, `SSL_CERT_FILE`, `SSL_CERT_DIR`)와, 거기 아무것도 없으면 아래에 나열한 배포판 번들에 대해 검증합니다(`SSL_VERIFY_PEER`, `SSL_set1_host` / `X509_VERIFY_PARAM_set1_ip_asc`).
- **SChannel**: Windows 인증서 저장소에 대해 `CertGetCertificateChain` + `CertVerifyCertificateChainPolicy`(SSL 정책, 서버 이름). `ca_pem`/`ca_file`을 주거나 `use_system_roots = 0`이면 그 루트로 std 자체 경로 검증을 합니다(`use_system_roots`가 켜져 있으면 Windows ROOT 저장소도 함께).
- **SecureTransport**: 호스트에 대한 SSL 정책으로 `SecTrustEvaluateWithError`. `ca_pem`/`ca_file`은 추가 앵커가 됩니다(`use_system_roots = 0`이면 유일한 앵커).
- **SafeC**는 경로를 직접 만들고 검사합니다(교차 서명 체인을 위해 다른 발급자 후보로 되돌아가며). `$SSL_CERT_FILE` 또는 `/etc/ssl/certs/ca-certificates.crt`, `/etc/pki/tls/certs/ca-bundle.crt`, `/etc/ssl/ca-bundle.pem`, `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem`, `/etc/ssl/cert.pem`(macOS 포함), FreeBSD 경로 중 처음 찾은 번들, 또는 Windows ROOT 저장소를 씁니다. 폐기 검사는 하지 않으며, 이름 제약이 있는 체인은 절반만 검사하는 대신 거부합니다.

`insecure_skip_verify()`는 검증을 끕니다. 다른 방법으로는 인증서를 신뢰할 수 없는 테스트 서버와 통신할 때만 쓰세요. 그 서버의 자체 서명 인증서를 `set_ca_pem`으로 주는 편이 낫습니다.

## SafeC 백엔드 {#safec-backend}

| | |
|---|---|
| 버전 | TLS 1.3만(TLS 1.2 전용 상대는 `TLS_ERR_HANDSHAKE`) |
| 암호 스위트 | `TLS_CHACHA20_POLY1305_SHA256`(선호), `TLS_AES_128_GCM_SHA256`, `TLS_AES_256_GCM_SHA384` |
| 키 교환 | X25519(먼저 제안), secp256r1, secp384r1(HelloRetryRequest로) |
| 서명 | ECDSA P-256/SHA-256, P-384/SHA-384, RSA-PSS(rsae와 pss 키, SHA-256/384/512). 인증서에서는 RSA PKCS#1과 ECDSA |
| 키(서버, 클라이언트 인증서) | EC P-256/P-384와 RSA 1024–4096, PEM: PKCS#8, `RSA PRIVATE KEY`, `EC PRIVATE KEY` |
| 그 밖 | SNI, ALPN, 양방향 클라이언트 인증서, 쿠키를 포함한 HelloRetryRequest, KeyUpdate, 미들박스 호환 ChangeCipherSpec. 티켓은 읽고 버립니다(재개, 0-RTT 없음) |

::: warning 성숙도
SafeC 백엔드는 새로운 코드이며 독립적인 보안 감사를 받지 않았습니다. 근거로 삼는 것은 다음과 같습니다. 프리미티브는 RFC와 NIST 벡터(RFC 8439, RFC 7748, RFC 5869, RFC 6979, RFC 8448 키 스케줄) 및 OpenSSL과 pyca가 만든 서명과 키로 테스트했고, 핸드셰이크는 양방향으로 OpenSSL과, 모든 암호 스위트와 그룹, 클라이언트 인증서, 검증 실패 사례로 테스트했습니다. X25519, P-256/P-384 스칼라 곱셈, 체와 스칼라 산술, RSA 개인 키 연산, 모든 태그와 Finished 비교는 상수 시간으로 동작합니다. 여기의 AES는 테이블 기반이라(캐시를 통해 시간이 키에 따라 달라질 수 있음) ChaCha20-Poly1305를 선호합니다. Linux, BSD, Windows, macOS에서는 기본으로 OS 백엔드를 쓰며, SafeC 백엔드는 OS 백엔드가 없는 곳의 대체이자 OS 라이브러리가 PEM 키를 받지 못하는 곳의 서버입니다.
:::

## 플랫폼별 참고 {#platforms}

**Linux와 BSD.** libssl은 `libssl.so.3`, `libssl.so.1.1`(FreeBSD/OpenBSD에서는 `.so.30`/`.so.111`), `libssl.so` 순으로 찾고, 맞는 libcrypto를 함께 불러옵니다. OpenSSL은 소켓을 직접 건드리지 않습니다. 메모리 BIO를 읽고 쓰며, std가 그 내용을 소켓으로 옮기므로 쓰기가 `SIGPIPE`를 일으키지 않고 논블로킹 소켓도 위와 같이 동작합니다.

**Windows.** SChannel은 Windows 10 1809 이후에서 TLS 1.3(`SCH_CREDENTIALS`), 그 전에는 TLS 1.2(`SCHANNEL_CRED`)를 협상합니다. 메모리의 PEM으로는 TLS 서버나 클라이언트 인증서를 쓸 수 없으므로(개인 키가 Windows 키 저장소에 있어야 합니다) `TLS_BACKEND_AUTO`는 그 경우 SafeC 백엔드를 씁니다. `tests/stdlib/win32/run.sh`는 Wine에서 `openssl s_server`를 상대로 이것을 검사합니다.

**macOS.** OS 백엔드는 Network.framework가 아니라 SecureTransport입니다. `TlsStream`은 프로그램이 연결(또는 accept)한 소켓 위에서 동작하는데, `nw_connection`은 자기 소켓을 직접 열어 소유하고 dispatch 블록으로 데이터를 전달하므로 기존 fd 위에 올릴 수 없습니다. SecureTransport는 사용 중단(deprecated)되었지만 여전히 있고, TLS 1.2까지만 지원합니다. `min_version = TLS_VERSION_1_3`인 설정, 서버, PEM 클라이언트 인증서는 `TLS_BACKEND_AUTO`에서 SafeC 백엔드를 씁니다.

## 저수준 구성 요소 {#low-level}

SafeC 백엔드의 구성 요소는 [`std/crypto`](/ko/stdlib/crypto)에 공개되어 있습니다: [ChaCha20-Poly1305](/ko/stdlib/crypto#chacha20-poly1305), [X25519](/ko/stdlib/crypto#x25519), [HKDF](/ko/stdlib/crypto#hkdf), [P-256/P-384](/ko/stdlib/crypto#ec), [RSA](/ko/stdlib/crypto#rsa), [X.509 파싱과 서명 검증](/ko/stdlib/crypto#x509-verification). `std/crypto/tls.h`는 핸드셰이크 없는 별개의, 더 오래된 TLS 1.3 *레코드 레이어*입니다.
