# 보안 & 암호학

SafeC는 대칭 암호화, 해싱, 난수 생성, 인증서 파싱, TLS 레코드 레이어 프레이밍을 제공하는 보안 라이브러리를 `std/crypto/`에 제공합니다.

```c
#include <std/crypto/crypto.h>  // master header: pulls in all modules
```

::: warning
SafeC의 암호 모듈은 올바른 알고리즘 구현을 제공합니다. 하지만 현재는 부채널 대응(상수 시간 비교, 키 사용 후 캐시 플러시 등)을 구현하고 있지 않습니다. 보안이 중요한 프로덕션 환경에서는 이 점을 유의하여 사용하세요.
:::

---

## AES {#aes}

```c
#include <std/crypto/aes.h>
```

ECB와 CBC 모드를 지원하는 AES-128, AES-256 블록 암호입니다. 완전한 S-box, 키 확장, MixColumns 구현을 포함합니다.

| 상수 | 값 |
|----------|-------|
| `AES_BLOCK_SIZE` | 16바이트 |
| `AES128_KEY_SIZE` / `AES256_KEY_SIZE` | 16 / 32바이트 |
| `AES128_ROUNDS` / `AES256_ROUNDS` | 10 / 14 |
| `AES_KS_WORDS` | 60 (키 스케줄 워드 수) |

### API {#api}

```c
struct AesCtx {
    unsigned int  ks[60];             // 확장된 키 스케줄
    int           rounds;             // 10 (AES-128) 또는 14 (AES-256)
    unsigned char iv[16];             // CBC 초기화 벡터

    // ECB — 16바이트 블록 하나를 제자리에서 암호화/복호화
    void encrypt_block(&stack unsigned char block);
    void decrypt_block(&stack unsigned char block);

    // CBC — `len`은 반드시 16의 배수여야 함; self.iv를 제자리에서 갱신
    void cbc_encrypt(&stack unsigned char data, unsigned long len);
    void cbc_decrypt(&stack unsigned char data, unsigned long len);

    // CBC IV 설정
    void set_iv(const &stack unsigned char iv);
}

// 생성자 — key 매개변수는 안전한 스택 참조
struct AesCtx aes128_init(const &stack unsigned char key);  // key: 16바이트
struct AesCtx aes256_init(const &stack unsigned char key);  // key: 32바이트
```

::: info
`cbc_encrypt` / `cbc_decrypt`는 버퍼를 **제자리에서** 암호화하며, 각 블록 처리 후 `self.iv`를 갱신하여 다음 호출을 이어갈 준비를 합니다. 버퍼에 대한 포인터 연산은 `unsafe {}` 내부에서 이루어집니다.
:::

### 예제 {#example}

```c
#include <std/crypto/aes.h>

int main() {
    unsigned char key[16];
    unsigned char iv[16];
    unsigned char buf[32];
    const &char text = "Hello, SafeC! AES CBC test data.";   // 32 bytes
    unsafe {
        int i = 0;
        while (i < 16) { key[i] = (unsigned char)(i * 17); iv[i] = 0; i = i + 1; }
        i = 0;
        while (i < 32) { buf[i] = (unsigned char)text[i]; i = i + 1; }
    }

    struct AesCtx ctx = std::aes128_init(&key[0]);
    ctx.set_iv(&iv[0]);
    ctx.cbc_encrypt(&buf[0], 32UL);   // buf is now ciphertext

    ctx.set_iv(&iv[0]);               // reset the IV and decrypt back
    ctx.cbc_decrypt(&buf[0], 32UL);   // buf holds the plaintext again
    unsafe { return buf[0] == (unsigned char)'H' ? 0 : 1; }
}
```

---

## SHA-256 {#sha-256}

```c
#include <std/crypto/sha256.h>
```

스트리밍(update) 방식과 원샷 방식 인터페이스를 모두 지원하는 SHA-256, SHA-224입니다.

```c
struct Sha256Ctx {
    unsigned int  h[8];           // 현재 해시 상태
    unsigned char buf[64];        // 부분 블록 버퍼
    unsigned long total_bytes;    // 처리된 총 바이트 수
    int           is224;          // 1이면 SHA-224 변형

    // 해시에 바이트를 공급 (스트리밍)
    void update(const &stack unsigned char data, unsigned long len);

    // 마무리하고 다이제스트를 기록: SHA-256은 32바이트, SHA-224는 28바이트
    void finish(&stack unsigned char digest);
}

// 생성자
struct Sha256Ctx sha256_init();   // SHA-256
struct Sha256Ctx sha224_init();   // SHA-224

// 원샷 편의 함수
void sha256(const &stack unsigned char data, unsigned long len,
            &stack unsigned char digest);

#define SHA256_DIGEST_SIZE  32   // bytes
#define SHA224_DIGEST_SIZE  28
#define SHA256_BLOCK_SIZE   64
```

### 예제 {#example-1}

```c
#include <std/crypto/sha256.h>

int main() {
    unsigned char a[32];
    unsigned char b[32];
    unsigned char msg[5];
    unsafe { msg[0] = 'h'; msg[1] = 'e'; msg[2] = 'l'; msg[3] = 'l'; msg[4] = 'o'; }

    std::sha256(&msg[0], 5UL, &a[0]);              // one-shot

    struct Sha256Ctx ctx = std::sha256_init();     // streaming, two chunks
    ctx.update(&msg[0], 3UL);
    unsafe { ctx.update(&msg[3], 2UL); }
    ctx.finish(&b[0]);                             // same digest as the one-shot

    int same = 1;
    unsafe { int i = 0; while (i < 32) { if (a[i] != b[i]) same = 0; i = i + 1; } }
    return same ? 0 : 1;
}
```

---

## SHA-512 / SHA-384 {#sha-512-sha-384}

```c
#include <std/crypto/sha512.h>
```

SHA-256과 같은 인터페이스이며, 64비트 워드와 128바이트 블록을 씁니다.

```c
#define SHA512_DIGEST_SIZE  64   // bytes
#define SHA384_DIGEST_SIZE  48
#define SHA512_BLOCK_SIZE   128

struct Sha512Ctx {
    unsigned long long h[8];
    unsigned char      buf[SHA512_BLOCK_SIZE];
    unsigned long long total_bytes;   // 64-bit count; the high half of the 128-bit length is written as 0
    int                is384;         // 1 = SHA-384

    void update(const &stack unsigned char data, unsigned long len);
    void finish(&stack unsigned char digest);  // 64 bytes (SHA-512) or 48 (SHA-384)
};

struct Sha512Ctx sha512_init();
struct Sha512Ctx sha384_init();

void sha512(const &stack unsigned char data, unsigned long len, &stack unsigned char digest);
void sha384(const &stack unsigned char data, unsigned long len, &stack unsigned char digest);
```

---

## SHA-1 {#sha-1}

```c
#include <std/crypto/sha1.h>
```

::: danger 깨진 해시
SHA-1에는 실용적인 충돌 공격이 존재합니다. 일부 프로토콜이 요구하기 때문에만 제공됩니다 — [WebSocket](/ko/stdlib/http#websocket) 핸드셰이크의 `Sec-WebSocket-Accept`는 SHA-1로 정의되어 있습니다. 그 외에는 SHA-256이나 SHA-512를 쓰세요.
:::

```c
#define SHA1_DIGEST_SIZE  20   // bytes
#define SHA1_BLOCK_SIZE   64

struct Sha1Ctx {
    unsigned int  h[5];
    unsigned char buf[SHA1_BLOCK_SIZE];
    unsigned long total_bytes;

    void update(const &stack unsigned char data, unsigned long len);
    void finish(&stack unsigned char digest);   // 20 bytes
};

struct Sha1Ctx sha1_init();
void sha1(const &stack unsigned char data, unsigned long len, &stack unsigned char digest);
```

---

## HMAC-SHA256 {#hmac-sha256}

```c
#include <std/crypto/hmac.h>
```

SHA-256 위의 HMAC(RFC 2104)입니다. [JWT](/ko/stdlib/http#jwt) 서명에 쓰입니다.

```c
#define HMAC_SHA256_SIZE 32   // bytes

// Writes the 32-byte MAC of data[0..dataLen) under key[0..keyLen) to out.
void hmac_sha256(const &unsigned char key, unsigned long keyLen,
                 const &unsigned char data, unsigned long dataLen,
                 &unsigned char out);
```

```c
#include <std/crypto/hmac.h>

int main() {
    unsigned char key[3]  = { 'k', 'e', 'y' };
    unsigned char msg[5]  = { 'h', 'e', 'l', 'l', 'o' };
    unsigned char mac[HMAC_SHA256_SIZE];
    std::hmac_sha256(&key[0], 3UL, &msg[0], 5UL, &mac[0]);
    return 0;
}
```

---

## RNG — ChaCha20 CSPRNG {#rng-chacha20-csprng}

```c
#include <std/crypto/rng.h>
```

ChaCha20을 기반으로 한 암호학적으로 안전한 의사 난수 생성기입니다. 출력은 `RNG_BLOCK_SIZE`(64바이트) 단위의 ChaCha20 블록으로 만들어집니다.

```c
struct RngCtx {
    void          fill(&unsigned char out, unsigned long len);   // len random bytes
    unsigned int  rand32();
    unsigned long rand64();
    unsigned long rand_range(unsigned long bound);   // uniform in [0, bound), no modulo bias
};

// Seed from hardware entropy (rdrand / /dev/urandom). 1 on success,
// 0 if no entropy source is available (e.g. bare metal without a TRNG).
int  rng_init(&RngCtx ctx);

// Deterministic seed (tests, reproducible simulations).
void rng_init_seed(&RngCtx ctx, const unsigned char seed[32]);
```

### 예제 {#example-2}

```c
#include <std/crypto/rng.h>
#include <std/io.h>

int main() {
    struct RngCtx rng;
    if (!std::rng_init(&rng)) { std::println("no entropy source"); return 1; }

    std::print("dice: ");
    std::println_int((long long)rng.rand_range(6UL) + 1LL);

    unsigned char key[32];
    rng.fill(&key[0], 32UL);   // a random 256-bit key
    return 0;
}
```

---

## SecureAllocator {#secureallocator}

```c
#include <std/crypto/secure_alloc.h>
```

슬랩 얼로케이터를 감싸 해제 시 제로화(zeroing-on-free)를 수행하므로, 민감한 데이터(키, 비밀번호)가 풀로 반환될 때 지워집니다.

고정 크기 슬롯이므로 크기 메타데이터도 새지 않습니다.

```c
struct SecureAllocator {
    struct SlabAllocator slab;

    &heap void    alloc();               // one slot; null when full
    void          dealloc(&heap void ptr);    // zeroes the slot, then frees it
    unsigned long available() const;
    void          destroy();             // zeroes everything, frees the backing buffer
};

struct SecureAllocator secure_alloc_new(unsigned long obj_size, unsigned long count);
```

---

## X.509 인증서 파서 {#x509-certificate-parser}

```c
#include <std/crypto/x509.h>
```

X.509v3 DER 인코딩 인증서를 파싱합니다. 주체/발급자 구분 이름, 유효기간, Subject Alternative Names(SAN), BasicConstraints, KeyUsage 확장을 추출합니다.

고정 한도: `X509_MAX_NAME_LEN`(이름당 128바이트), `X509_MAX_SAN`(SAN 8개), `X509_MAX_SAN_LEN`(각 64바이트), `X509_KEY_LEN`(공개 키 256바이트).

### 구조체 {#structs}

```c
struct X509Validity {
    unsigned long long not_before;  // Unix 타임스탬프
    unsigned long long not_after;
}

struct X509Name {
    char cn[128];   // Common Name
    char o[128];    // Organisation
    char c[4];      // Country
}

struct X509San {
    char          names[8][64];  // DNS SAN 항목
    unsigned long count;
}

struct X509Cert {
    X509Validity   validity;
    X509Name       subject;
    X509Name       issuer;
    X509San        san;
    unsigned char  pubkey[256];
    unsigned char  serial[20];
    int            is_ca;
    unsigned short key_usage;

    int is_valid_at(unsigned long long unix_time) const;
    int matches_hostname(const &char host) const;  // SAN + CN 폴백; 와일드카드 *.domain
    int is_ca_cert() const;
}
```

### API {#api-1}

```c
// DER 인코딩된 인증서를 파싱합니다.
// 성공 시 0, 오류 시 음수를 반환합니다.
int x509_parse_der(const &unsigned char der, unsigned long len,
                   &X509Cert out);

// 구조적인 발급자/주체 이름 일치 여부만 확인합니다.
// 암호학적 서명은 검증하지 **않습니다**.
int x509_verify_chain(const &X509Cert cert,
                      const &X509Cert ca);
```

### 예제 {#example-3}

```c
#include <std/crypto/x509.h>
#include <std/io.h>

void check_cert(const &unsigned char der, unsigned long len,
                unsigned long long now) {
    struct X509Cert cert;

    if (x509_parse_der(der, len, &cert) != 0) {
        println("parse error");
        return;
    }
    if (!cert.is_valid_at(now)) {
        println("certificate expired");
        return;
    }
    if (cert.matches_hostname("api.example.com")) {
        println("hostname: OK");
    }
    if (cert.is_ca_cert()) {
        println("is CA certificate");
    }
}
```

::: info
`matches_hostname`은 와일드카드 인증서를 지원합니다: `*.example.com`은 `api.example.com`과는 일치하지만 `a.b.example.com`과는 일치하지 않습니다. SAN 항목이 없을 경우 CN 필드가 폴백으로 확인됩니다.
:::

---

## TLS 1.3 레코드 레이어 {#tls-13-record-layer}

```c
#include <std/crypto/tls.h>
```

TLS 1.3 **레코드 레이어**(RFC 8446 §5)를 구현합니다. TLS 레코드의 프레이밍, 암호화, 복호화를 처리합니다. 키 스케줄링과 핸드셰이크 프로토콜은 **포함되어 있지 않습니다** — 외부에서 핸드셰이크를 완료한 후 키를 공급해야 합니다.

### 상수 {#constants}

```c
#define TLS_CHANGE_CIPHER_SPEC  20
#define TLS_ALERT               21
#define TLS_HANDSHAKE           22
#define TLS_APPLICATION_DATA    23

// Alert levels and descriptions (build_alert)
#define TLS_ALERT_WARNING        1
#define TLS_ALERT_FATAL          2
#define TLS_ALERT_CLOSE_NOTIFY   0
#define TLS_ALERT_HANDSHAKE_FAIL 40
#define TLS_ALERT_DECODE_ERROR   50

#define TLS_MAX_RECORD_LEN  16384   // max plaintext per record
#define TLS_KEY_LEN         32      // AES-256
#define TLS_IV_LEN          12      // per RFC 8446 §5.3
```

### 구조체 {#structs-1}

```c
struct TlsTranscript {
    struct Sha256Ctx hash;

    void update(const &unsigned char data, unsigned long len);
    void finish(unsigned char out[32]);   // snapshot — the transcript stays usable
};

struct TlsSession {
    unsigned char      write_key[TLS_KEY_LEN];
    unsigned char      write_iv[TLS_IV_LEN];
    unsigned char      read_key[TLS_KEY_LEN];
    unsigned char      read_iv[TLS_IV_LEN];
    unsigned long long write_seq;
    unsigned long long read_seq;
    int                handshake_done;
    struct TlsTranscript transcript;

    // Encode one record into out[0..out_cap). Pre-handshake: plaintext;
    // after install_keys: AES-256-CBC + PKCS#7. Returns bytes written, 0 on error.
    unsigned long encode_record(unsigned char content_type,
                                const &unsigned char payload, unsigned long payload_len,
                                &unsigned char out, unsigned long out_cap);

    // Decode one record from data[0..data_len); writes the plaintext to out.
    // Returns the payload length, 0 on error or incomplete input.
    unsigned long decode_record(const &unsigned char data, unsigned long data_len,
                                &stack unsigned char content_type_out,
                                &unsigned char out, unsigned long out_cap);

    unsigned long build_alert(unsigned char level, unsigned char desc,
                              &unsigned char out, unsigned long out_cap);

    void install_keys(const unsigned char write_key[TLS_KEY_LEN],
                      const unsigned char write_iv[TLS_IV_LEN],
                      const unsigned char read_key[TLS_KEY_LEN],
                      const unsigned char read_iv[TLS_IV_LEN]);
    int  is_established() const;
};

struct TlsSession tls_session_init();
```

### 논스 유도 {#nonce-derivation}

RFC 8446 §5.3에 따라, 레코드별 논스는 다음과 같이 계산됩니다.

```
nonce = write_iv XOR (제로 패딩된 빅엔디안 시퀀스 번호)
```

이는 `encode_record` / `decode_record` 내부에서 자동으로 계산됩니다. 직접 필요하면 같은 유도를 메서드로도 쓸 수 있습니다:

```c
void TlsSession::compute_nonce(unsigned char nonce[TLS_IV_LEN], int is_write) const;
```

### 예제 {#example-4}

```c
#include <std/crypto/tls.h>

int main() {
    struct TlsSession session = std::tls_session_init();
    // ... after the handshake: session.install_keys(wk, wi, rk, ri);

    const &char msg = "GET / HTTP/1.1\r\n";
    unsigned char record[256];
    unsigned long n = 0UL;
    unsafe { n = session.encode_record(TLS_APPLICATION_DATA, (const &unsigned char)msg, 16UL,
                                       &record[0], 256UL); }
    // transmit record[0..n) over TCP ...

    unsigned char type = 0;
    unsigned char plain[256];
    unsigned long got = session.decode_record(&record[0], n, &type, &plain[0], 256UL);
    return (got == 16UL && type == (unsigned char)TLS_APPLICATION_DATA) ? 0 : 1;
}
```
