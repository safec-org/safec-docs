# 보안 & 암호학

SafeC는 대칭 암호화, 해싱, 난수 생성, 인증서 파싱, TLS 레코드 레이어 프레이밍을 제공하는 보안 라이브러리를 `std/crypto/`에 제공합니다.

```c
#include "crypto/crypto.h"  // 마스터 헤더: 모든 모듈을 가져옴
```

::: warning
SafeC의 암호 모듈은 올바른 알고리즘 구현을 제공합니다. 하지만 현재는 부채널 대응(상수 시간 비교, 키 사용 후 캐시 플러시 등)을 구현하고 있지 않습니다. 보안이 중요한 프로덕션 환경에서는 이 점을 유의하여 사용하세요.
:::

---

## AES {#aes}

```c
#include "crypto/aes.h"
```

ECB와 CBC 모드를 지원하는 AES-128, AES-256 블록 암호입니다. 완전한 S-box, 키 확장, MixColumns 구현을 포함합니다.

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
#include "crypto/aes.h"

int main() {
    unsigned char key[16] = {0x2b,0x7e,0x15,0x16, 0x28,0xae,0xd2,0xa6,
                              0xab,0xf7,0x15,0x88, 0x09,0xcf,0x4f,0x3c};
    unsigned char iv[16]  = {0};
    unsigned char buf[32] = "Hello, SafeC! AES CBC test data.";

    struct AesCtx ctx = aes128_init(key);
    ctx.set_iv(iv);

    ctx.cbc_encrypt(buf, 32);   // buf는 이제 암호문

    // IV를 재설정하고 다시 복호화
    ctx.set_iv(iv);
    ctx.cbc_decrypt(buf, 32);   // buf가 평문으로 복원됨
    return 0;
}
```

---

## SHA-256 {#sha-256}

```c
#include "crypto/sha256.h"
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
```

### 예제 {#example-1}

```c
#include "crypto/sha256.h"
#include "io.h"

int main() {
    unsigned char digest[32];

    // 원샷 해시
    unsigned char msg[] = "hello";
    sha256(msg, 5, digest);

    // 두 청크의 스트리밍 해시
    struct Sha256Ctx ctx = sha256_init();
    unsigned char part1[] = "hel";
    unsigned char part2[] = "lo";
    ctx.update(part1, 3);
    ctx.update(part2, 2);
    ctx.finish(digest);  // 위 원샷 결과와 동일

    return 0;
}
```

---

## RNG — ChaCha20 CSPRNG {#rng-chacha20-csprng}

```c
#include "crypto/rng.h"
```

ChaCha20을 기반으로 한 암호학적으로 안전한 의사 난수 생성기입니다.

```c
struct RngCtx {
    unsigned int state[16];  // ChaCha20 상태
}

// 호출자가 제공한 32바이트로 시드
void rng_init(struct RngCtx* ctx, const unsigned char seed[32]);

// 하드웨어 엔트로피로 시드:
//   x86-64: rdrand | 호스티드: /dev/urandom | 프리스탠딩: no-op
void rng_init_os(struct RngCtx* ctx);

// `len` 바이트의 난수를 생성
void rng_fill(struct RngCtx* ctx, void* out, unsigned long len);

// 난수 값 하나를 생성
unsigned int       rng_u32(struct RngCtx* ctx);
unsigned long long rng_u64(struct RngCtx* ctx);
```

### 예제 {#example-2}

```c
#include "crypto/rng.h"
#include "io.h"

int main() {
    struct RngCtx rng;
    rng_init_os(&rng);

    unsigned int n = rng_u32(&rng);
    print("random: ");
    println_int(n);

    unsigned char key[32];
    rng_fill(&rng, key, 32);  // 무작위 256비트 키 생성
    return 0;
}
```

---

## SecureAllocator {#secureallocator}

```c
#include "crypto/secure_alloc.h"
```

슬랩 얼로케이터를 감싸 해제 시 제로화(zeroing-on-free)를 수행하므로, 민감한 데이터(키, 비밀번호)가 풀로 반환될 때 지워집니다.

```c
struct SecureAllocator {
    // SlabAllocator를 감쌈
}

SecureAllocator secure_alloc_new(unsigned long obj_size, unsigned long count);

void* secure_alloc(struct SecureAllocator* a);
void  secure_free(struct SecureAllocator* a, void* ptr);  // 해제 전 제로화
void  secure_destroy(struct SecureAllocator* a);           // 풀 전체를 제로화
```

---

## X.509 인증서 파서 {#x509-certificate-parser}

```c
#include "crypto/x509.h"
```

X.509v3 DER 인코딩 인증서를 파싱합니다. 주체/발급자 구분 이름, 유효기간, Subject Alternative Names(SAN), BasicConstraints, KeyUsage 확장을 추출합니다.

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
    int matches_hostname(const char* host) const;  // SAN + CN 폴백; 와일드카드 *.domain
    int is_ca_cert() const;
}
```

### API {#api-1}

```c
// DER 인코딩된 인증서를 파싱합니다.
// 성공 시 0, 오류 시 음수를 반환합니다.
int x509_parse_der(const unsigned char* der, unsigned long len,
                   struct X509Cert* out);

// 구조적인 발급자/주체 이름 일치 여부만 확인합니다.
// 암호학적 서명은 검증하지 **않습니다**.
int x509_verify_chain(const struct X509Cert* cert,
                      const struct X509Cert* ca);
```

### 예제 {#example-3}

```c
#include "crypto/x509.h"
#include "io.h"

void check_cert(const unsigned char* der, unsigned long len,
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
#include "crypto/tls.h"
```

TLS 1.3 **레코드 레이어**(RFC 8446 §5)를 구현합니다. TLS 레코드의 프레이밍, 암호화, 복호화를 처리합니다. 키 스케줄링과 핸드셰이크 프로토콜은 **포함되어 있지 않습니다** — 외부에서 핸드셰이크를 완료한 후 키를 공급해야 합니다.

### 상수 {#constants}

```c
#define TLS_CHANGE_CIPHER_SPEC  20
#define TLS_ALERT               21
#define TLS_HANDSHAKE           22
#define TLS_APPLICATION_DATA    23
```

### 구조체 {#structs-1}

```c
struct TlsTranscript {
    Sha256Ctx hash;

    void update(const void* data, unsigned long len);
    void finish(unsigned char out[32]);  // 스냅샷 — ctx는 계속 사용 가능
}

struct TlsSession {
    unsigned char       write_key[32];
    unsigned char       write_iv[12];
    unsigned char       read_key[32];
    unsigned char       read_iv[12];
    unsigned long long  write_seq;
    unsigned long long  read_seq;
    int                 handshake_done;
    TlsTranscript       transcript;

    // 대칭 키 설정 (핸드셰이크 완료 후 호출)
    void install_keys(const unsigned char wk[32], const unsigned char wi[12],
                      const unsigned char rk[32], const unsigned char ri[12]);

    // 레코드를 `out`(PacketBuf)으로 인코딩
    // 핸드셰이크 이전: 평문. 핸드셰이크 이후: AES-256-CBC + PKCS#7
    void encode_record(unsigned char content_type,
                       const void* data, unsigned long len,
                       struct PacketBuf* out);

    // `pkt`로부터 레코드를 디코딩
    int  decode_record(struct PacketBuf* pkt,
                       unsigned char* ct_out,
                       void* buf, unsigned long max);

    // TLS Alert 레코드 생성
    void build_alert(unsigned char level, unsigned char desc,
                     struct PacketBuf* out);

    int  is_established() const;
}

TlsSession tls_session_init();
```

### 논스 유도 {#nonce-derivation}

RFC 8446 §5.3에 따라, 레코드별 논스는 다음과 같이 계산됩니다.

```
nonce = write_iv XOR (제로 패딩된 빅엔디안 시퀀스 번호)
```

이는 `encode_record` / `decode_record` 내부에서 자동으로 계산됩니다.

### 예제 {#example-4}

```c
#include "crypto/tls.h"

int main() {
    TlsSession session = tls_session_init();

    // TLS 1.3 핸드셰이크가 완료된 후 — 유도된 키를 공급
    unsigned char wk[32] = { /* application_write_key */ };
    unsigned char wi[12] = { /* application_write_iv  */ };
    unsigned char rk[32] = { /* application_read_key  */ };
    unsigned char ri[12] = { /* application_read_iv   */ };
    session.install_keys(wk, wi, rk, ri);

    // 애플리케이션 데이터 전송
    struct PacketBuf out;
    const char* msg = "GET / HTTP/1.1\r\n";
    session.encode_record(TLS_APPLICATION_DATA, msg, 16, &out);
    // out을 TCP로 전송...

    return 0;
}
```
