# TLS (`std/tls/`)

Encrypted, authenticated connections over a socket you have connected or accepted: TLS clients and servers, certificate verification against the system's trusted roots, ALPN, client certificates. `std/http` uses it for `https://`, `wss://` and HTTPS servers (see [HTTPS](/stdlib/http#https)).

```c
#include <std/tls/tls.h>
```

The work is done by the operating system's TLS library when there is one, and by SafeC's own TLS 1.3 implementation otherwise. One API covers all of them:

| Backend | Where | What it is |
|---|---|---|
| `TLS_IMPL_OPENSSL` | Linux, BSD | libssl + libcrypto 3.x or 1.1.1, **loaded with `dlopen()` at run time**: nothing to link, no headers; a program runs on machines without OpenSSL and gets the SafeC backend there |
| `TLS_IMPL_SCHANNEL` | Windows | SChannel through SSPI (`secur32`), certificates checked against the Windows certificate store (`crypt32`) |
| `TLS_IMPL_SECURE_TRANSPORT` | macOS | Security.framework's SecureTransport (TLS 1.2 at most), certificates checked by `SecTrust` against the system keychains |
| `TLS_IMPL_SAFEC` | everywhere | SafeC's own TLS 1.3 client and server on [`std/crypto`](/stdlib/crypto)'s X25519, P-256/P-384, AES-GCM, ChaCha20-Poly1305, RSA and ECDSA; roots from the system's PEM bundle (or the Windows store) |

`safeguard` links what a backend needs (`-ldl` on Linux, `-framework Security -framework CoreFoundation` on macOS, `secur32`/`crypt32` on Windows); there is nothing to configure.

## Quick start {#quick-start}

A client on a connected, blocking socket:

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
    std::sock_set_io_timeout(fd, 10000);              // bounds the handshake and every read

    struct TlsConfig cfg = std::TlsConfig::new();     // system roots, host name checked
    cfg.set_alpn("http/1.1");
    struct Result<TlsStream, TlsError> r = std::TlsStream::connect(fd, "example.com", &cfg);
    cfg.free();
    match (r.get_err()) {
        case some(e): { std::println(e.message); return 1; }   // the fd is still yours to close
        case null: { }
    }
    struct TlsStream t = r.value;
    std::print(t.backend_name()); std::print(" "); std::println(t.cipher());

    const &char req = "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n";
    unsafe { t.write(((const unsigned char*)req)[0..std::str_len(req)]); }
    unsigned char buf[4096];
    long long n = t.read(buf[0..4096UL]);            // count, 0 once the server closed, -1 on error
    t.close();                                        // close_notify, then closes the socket
    return n > 0LL ? 0 : 1;
}
```

`TlsStream` is an [`io_traits.h` `Stream`](/stdlib/io#io-traits) (`Reader + Writer`), so `read_exact`, `write_all`, `BufReader<TlsStream>` and `BufWriter<TlsStream>` work on it. For HTTP you don't need any of this: `std::http_get_url("https://example.com/", &ok)` does it all.

A server, one handshake per accepted connection:

```c
struct TlsServerConfig sc = std::TlsServerConfig::new();
sc.load_files("cert.pem", "key.pem");     // chain (leaf first) and its key: PKCS#8, RSA or EC PEM
sc.set_alpn("h2,http/1.1");               // the server's preference order
struct Result<TlsAcceptor, TlsError> ar = std::TlsAcceptor::new(&sc);
sc.free();                                // the acceptor keeps what it needs
struct TlsAcceptor acc = ar.value;        // (check ar.ok() first)

int fd = std::tcp_accept_nb(listen_fd);   // blocking from here: fd_set_blocking(fd)
struct Result<TlsStream, TlsError> r = acc.accept(fd);   // the handshake
if (r.ok()) {
    struct TlsStream t = r.value;
    // t.read / t.write ..., t.alpn(), t.peer_certificate(&cert) ...
    t.close();
}
acc.free();                               // after the streams it started have finished their handshakes
```

## Choosing a backend {#backends}

`TlsConfig::backend` and `TlsServerConfig::backend`:

| Value | Meaning |
|---|---|
| `TLS_BACKEND_AUTO` (default) | `$SAFEC_TLS` if set; else the OS backend when it is available and can do what the config asks; else SafeC's |
| `TLS_BACKEND_OS` | the platform's library (OpenSSL / SChannel / SecureTransport), or `TLS_ERR_UNAVAILABLE` |
| `TLS_BACKEND_SAFEC` | SafeC's own TLS 1.3 |
| `TLS_BACKEND_IMPL_BASE + TLS_IMPL_*` | one backend by name (e.g. OpenSSL on macOS, if Homebrew's is installed) |

The `SAFEC_TLS` environment variable overrides `TLS_BACKEND_AUTO` for a whole program: `os`, `safec`, `openssl`, `schannel` or `securetransport`. `tls_default_impl()` says what `AUTO` resolves to, `tls_backend_available(impl)` whether a backend loads here, and `TlsStream::backend_name()` what a connection actually uses.

Falling back is only for *capabilities*: when the OS library isn't there, or can't do a requested feature (TLS 1.3 on SecureTransport, servers and PEM client certificates on SChannel and SecureTransport), `AUTO` uses the SafeC backend. A backend that is there and fails — a bad certificate, a handshake failure — is reported as an error, never retried elsewhere.

## Configuration {#configuration}

```c
struct TlsConfig {
    int           verify_peer;       // 1: check the chain and dates (default)
    int           verify_hostname;   // 1: check the name (default)
    int           use_system_roots;  // 1: trust the OS's roots (default)
    struct String ca_pem;            // more trusted roots, PEM
    struct String ca_file;           // a PEM bundle file of more trusted roots
    struct String server_name;       // SNI + the name to verify; empty: connect()'s host
    struct String alpn;              // protocols to offer, comma-separated ("h2,http/1.1")
    int           min_version;       // TLS_VERSION_1_2 (default) or TLS_VERSION_1_3
    int           max_version;       // TLS_VERSION_1_3 (default)
    int           backend;           // TLS_BACKEND_AUTO (default)
    struct String cert_pem;          // client certificate chain, if the server asks for one
    struct String key_pem;           // its private key

    static struct TlsConfig new();
    void set_ca_pem(const &char pem);
    void set_ca_file(const &char path);
    void set_server_name(const &char name);
    void set_alpn(const &char protocols);
    void set_client_cert(const &char cert_pem, const &char key_pem);
    void insecure_skip_verify();     // verification off: tests against self-signed servers only
    struct TlsConfig clone() const;
    void free();
};

struct TlsServerConfig {
    struct String cert_pem;          // leaf first, then intermediates
    struct String key_pem;           // PKCS#8, RSA or EC PEM
    struct String alpn;              // the protocols the server speaks, preferred first
    int           min_version;       // TLS_VERSION_1_2 (default)
    int           max_version;       // TLS_VERSION_1_3 (default)
    int           backend;           // TLS_BACKEND_AUTO (default)
    int           verify_client;     // 0 none (default), 1 request a certificate, 2 require one
    struct String client_ca_pem;     // roots for client certificates

    static struct TlsServerConfig new();
    int  load_files(const &char cert_path, const &char key_path);   // 0 if either can't be read
    void set_alpn(const &char protocols);
    void free();
};
```

Configs own their strings (`free()` them); a stream or acceptor copies what it needs. An IP literal as the host gets no SNI (as RFC 6066 requires) and is checked against the certificate's IP address entries.

## Streams {#streams}

```c
struct TlsStream {
    // Client, blocking: the whole handshake. On failure the fd stays open.
    static struct Result<TlsStream, TlsError> connect(int fd, const &char host, const ?&TlsConfig cfg);
    // Client, non-blocking: set up only; drive with handshake().
    static struct Result<TlsStream, TlsError> client(int fd, const &char host, const ?&TlsConfig cfg);

    int       handshake();     // 0 done, TLS_WANT_READ / TLS_WANT_WRITE, -1 failed
    long long read([]unsigned char buf);         // count, 0 = peer closed, -1, TLS_WANT_*
    long long write([]const unsigned char buf);  // count, -1, TLS_WANT_*
    int       shutdown();      // send close_notify (once)
    int       close();         // shutdown, free, close the socket
    void      release();       // free the TLS state, leave the fd open

    int  is_open() const;
    int  version() const;               // TLS_VERSION_1_2 / TLS_VERSION_1_3
    const &char cipher() const;         // IANA name: "TLS_AES_128_GCM_SHA256"
    const &char alpn() const;           // the protocol agreed, "" if none
    const &char backend_name() const;   // "openssl", "schannel", "securetransport", "safec"
    int  peer_certificate(&X509Cert out) const;   // the peer's leaf, summarized
    const &TlsError last_error() const;
};

struct TlsAcceptor {
    static struct Result<TlsAcceptor, TlsError> new(const &TlsServerConfig cfg);
    struct Result<TlsStream, TlsError> accept(int fd);   // blocking handshake
    struct Result<TlsStream, TlsError> start(int fd);    // non-blocking: drive with handshake()
    void free();
};
```

`peer_certificate` fills a [`X509Cert`](/stdlib/crypto#x509-certificate-parser) (subject, issuer, validity, DNS names) from the peer's leaf certificate; the DER bytes are in `TlsStream::peer_der_`.

### Non-blocking use {#non-blocking}

On a non-blocking socket, start with `TlsStream::client()` (or `TlsAcceptor::start()`) and call `handshake()` until it returns 0. It, `read()` and `write()` return `TLS_WANT_READ` or `TLS_WANT_WRITE` when the socket would block: wait until the fd is readable or writable — a [`std::Reactor`](/stdlib/sched), [`std/async`'s `IoReady`](/stdlib/async) — and call again. `write()` may accept data while some of the encrypted record is still queued; the next call sends that first. On a *blocking* socket a `TLS_WANT_*` result means its `SO_RCVTIMEO`/`SO_SNDTIMEO` expired (`connect()` reports `TLS_ERR_TIMEOUT`).

## Errors {#errors}

Failures come back as a `TlsError` (in the `Result`, or `last_error()`): a `code`, the TLS `alert` received or sent (-1 if none), the backend's own `detail` code (an OpenSSL error, a `SECURITY_STATUS`, an `OSStatus`) and a `message` saying what went wrong.

| Code | Meaning |
|---|---|
| `TLS_ERR_CERT_UNTRUSTED` | no chain to a trusted root: self-signed, unknown CA, missing intermediate |
| `TLS_ERR_CERT_HOSTNAME` | the certificate isn't for this host |
| `TLS_ERR_CERT_EXPIRED` | outside its validity period |
| `TLS_ERR_CERT_INVALID` | bad signature, wrong key usage, malformed, an unsupported critical extension |
| `TLS_ERR_CERT_REVOKED` | revoked (the OS backends that check) |
| `TLS_ERR_NO_CERT` | no peer certificate where one is required |
| `TLS_ERR_HANDSHAKE` | no common version, cipher suite, group or signature scheme |
| `TLS_ERR_ALERT` | the peer sent a fatal alert (`alert` says which) |
| `TLS_ERR_PROTOCOL` | malformed or unexpected message |
| `TLS_ERR_IO`, `TLS_ERR_CLOSED`, `TLS_ERR_TIMEOUT` | the socket failed, closed mid-handshake, timed out |
| `TLS_ERR_UNAVAILABLE` | the backend isn't on this system or can't do this |
| `TLS_ERR_CONFIG` | unreadable PEM, a key that doesn't match, missing settings |

`tls_error_name(code)` gives the fixed English name of a code.

## Certificate verification {#verification}

On by default: the chain must lead to a trusted root, every certificate must be within its validity dates, CA certificates must be CAs (basicConstraints, keyCertSign, pathLenConstraint), the leaf's extended key usage must allow TLS servers (or clients, on a server), and the name must match — DNS names against the certificate's `subjectAltName` DNS entries (case-insensitive, a `*.` wildcard matching exactly one leftmost label), IP literals against its IP entries, the subject's CN only when the certificate has no such entries at all.

- **OpenSSL** verifies (`SSL_VERIFY_PEER`, `SSL_set1_host` / `X509_VERIFY_PARAM_set1_ip_asc`) against its default CA paths — `OPENSSLDIR`, `SSL_CERT_FILE`, `SSL_CERT_DIR` — and, if those hold nothing, the distribution bundles listed below.
- **SChannel**: `CertGetCertificateChain` + `CertVerifyCertificateChainPolicy` (SSL policy, the server name) against the Windows certificate store; with `ca_pem`/`ca_file` or `use_system_roots = 0`, std's own path validation with those roots (plus the Windows ROOT store when `use_system_roots` is on).
- **SecureTransport**: `SecTrustEvaluateWithError` with the SSL policy for the host; `ca_pem`/`ca_file` become extra anchors (the only ones with `use_system_roots = 0`).
- **SafeC** builds and checks the path itself (backtracking over alternative issuers, for cross-signed chains) against `$SSL_CERT_FILE` or the first bundle found among `/etc/ssl/certs/ca-certificates.crt`, `/etc/pki/tls/certs/ca-bundle.crt`, `/etc/ssl/ca-bundle.pem`, `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem`, `/etc/ssl/cert.pem` (macOS too) and the FreeBSD paths — or the Windows ROOT store. It does no revocation checking, and rejects chains with name constraints rather than half-checking them.

`insecure_skip_verify()` turns verification off. Use it only to talk to a test server whose certificate you can't trust any other way — `set_ca_pem` with that server's self-signed certificate is the better option.

## The SafeC backend {#safec-backend}

| | |
|---|---|
| Versions | TLS 1.3 only (a TLS 1.2-only peer gets `TLS_ERR_HANDSHAKE`) |
| Cipher suites | `TLS_CHACHA20_POLY1305_SHA256` (preferred), `TLS_AES_128_GCM_SHA256`, `TLS_AES_256_GCM_SHA384` |
| Key exchange | X25519 (offered first), secp256r1, secp384r1 (via HelloRetryRequest) |
| Signatures | ECDSA P-256/SHA-256, P-384/SHA-384, RSA-PSS (rsae and pss keys, SHA-256/384/512); RSA PKCS#1 and ECDSA in certificates |
| Keys (servers, client certificates) | EC P-256/P-384 and RSA 1024–4096, PEM: PKCS#8, `RSA PRIVATE KEY`, `EC PRIVATE KEY` |
| Also | SNI, ALPN, client certificates both ways, HelloRetryRequest with cookies, KeyUpdate, middlebox-compatible ChangeCipherSpec; tickets are read and dropped (no resumption, no 0-RTT) |

::: warning Maturity
The SafeC backend is new code and has not had an independent security audit. What it rests on: its primitives are tested against the RFC and NIST vectors (RFC 8439, RFC 7748, RFC 5869, RFC 6979, the RFC 8448 key schedule) and against OpenSSL- and pyca-generated signatures and keys; its handshake is tested against OpenSSL in both directions, every cipher suite and group, client certificates and the verification failures. X25519, P-256/P-384 scalar multiplication, field and scalar arithmetic, RSA private-key operations and every tag and Finished comparison run in constant time. AES there is table-based (its timing can depend on the key through the cache), which is why ChaCha20-Poly1305 is preferred. On Linux, BSD, Windows and macOS the OS backend is used by default; the SafeC backend is the fallback where there is none, and the server where the OS library can't take PEM keys.
:::

## Platform notes {#platforms}

**Linux and BSD.** libssl is found as `libssl.so.3`, `libssl.so.1.1` (`.so.30`/`.so.111` on FreeBSD/OpenBSD) or `libssl.so`, with the matching libcrypto. OpenSSL never touches the socket: it reads and writes memory BIOs that std moves to and from the socket itself, so writes don't raise `SIGPIPE` and non-blocking sockets behave as above.

**Windows.** SChannel negotiates TLS 1.3 on Windows 10 1809 and later (`SCH_CREDENTIALS`), TLS 1.2 before that (`SCHANNEL_CRED`). It can't serve TLS or present a client certificate from PEM in memory — it wants the private key in a Windows key store — so `TLS_BACKEND_AUTO` uses the SafeC backend for those. `tests/stdlib/win32/run.sh` checks it under Wine against `openssl s_server`.

**macOS.** The OS backend is SecureTransport, not Network.framework: `TlsStream` runs over a socket the program connected (or accepted), and `nw_connection` opens and owns its own socket and delivers data through dispatch blocks — it can't be put on an existing fd. SecureTransport is deprecated but present, and stops at TLS 1.2: a config with `min_version = TLS_VERSION_1_3`, a server, or a PEM client certificate gets the SafeC backend under `TLS_BACKEND_AUTO`.

## Low-level pieces {#low-level}

The SafeC backend's building blocks are public in [`std/crypto`](/stdlib/crypto): [ChaCha20-Poly1305](/stdlib/crypto#chacha20-poly1305), [X25519](/stdlib/crypto#x25519), [HKDF](/stdlib/crypto#hkdf), [P-256/P-384](/stdlib/crypto#ec), [RSA](/stdlib/crypto#rsa) and [X.509 parsing and signature verification](/stdlib/crypto#x509-verification). `std/crypto/tls.h` is a separate, older TLS 1.3 *record layer* without a handshake.
