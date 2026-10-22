# 보안 & 암호학

SafeC는 대칭 암호화, 해싱, HMAC, 난수 생성, 인증서 파싱, TLS 레코드 레이어 프레이밍을 제공하는 보안 라이브러리를 `std/crypto/`에 제공합니다.

각 알고리즘의 상태는 구조체입니다. `AesCtx::new_128`이나 `Sha256Ctx::new` 같은 [정적 생성자](/ko/reference/functions#static-methods)로 만들고 메서드를 호출합니다. 해시 컨텍스트는 [`Hasher` / `BlockHash` 트레이트](#hash-traits)를 공유하므로, HMAC과 원샷 해싱은 모든 해시에 대해 한 번만 작성되어 있습니다.

```c
#include <std/crypto/crypto.h>  // master header: pulls in all modules
```

::: warning
SafeC의 암호 모듈은 올바른 알고리즘 구현을 제공합니다. 하지만 현재는 부채널 대응(상수 시간 비교, 키 사용 후 캐시 플러시 등)을 구현하고 있지 않습니다. 보안이 중요한 프로덕션 환경에서는 이 점을 유의하여 사용합니다.
:::

---

## AES {#aes}

```c
#include <std/crypto/aes.h>
```

ECB, CBC, GCM 모드를 지원하는 AES-128, AES-256 블록 암호입니다. 완전한 S-box, 키 확장, MixColumns 구현을 포함합니다.

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

    // 생성자 (IV는 0으로 초기화)
    static struct AesCtx new_128(const &unsigned char key);   // key: 16바이트
    static struct AesCtx new_256(const &unsigned char key);   // key: 32바이트

    // ECB — 16바이트 블록 하나를 제자리에서 암호화/복호화
    void encrypt_block(&unsigned char block);
    void decrypt_block(&unsigned char block);

    // CBC — `len`은 반드시 16의 배수여야 함; self.iv를 제자리에서 갱신
    void cbc_encrypt(&unsigned char data, unsigned long len);
    void cbc_decrypt(&unsigned char data, unsigned long len);

    // CBC IV 설정
    void set_iv(const &unsigned char iv);

    // GCM(NIST SP 800-38D), 12바이트 IV, 16바이트 태그. data를 제자리에서
    // 암호화하고 aad[0..aad_len)과 암호문에 대한 태그를 기록; aad_len이 0이면
    // aad는 null이어도 됨. 같은 키로 IV를 재사용하면 안 됨.
    void gcm_encrypt(const &unsigned char iv, const ?&unsigned char aad, unsigned long aad_len,
                     &unsigned char data, unsigned long len, &unsigned char tag);
    // 태그를 상수 시간으로 검사하고, 일치할 때만 제자리에서 복호화.
    // 진본이면 1, 아니면 0(data는 암호문 그대로).
    int  gcm_decrypt(const &unsigned char iv, const ?&unsigned char aad, unsigned long aad_len,
                     &unsigned char data, unsigned long len, const &unsigned char tag);

    // 사용이 끝나면 키 스케줄과 IV를 지움(volatile 저장).
    void destroy();
}
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

    struct AesCtx ctx = std::AesCtx::new_128(&key[0]);
    ctx.set_iv(&iv[0]);
    ctx.cbc_encrypt(&buf[0], 32UL);   // buf is now ciphertext

    ctx.set_iv(&iv[0]);               // reset the IV and decrypt back
    ctx.cbc_decrypt(&buf[0], 32UL);   // buf holds the plaintext again
    ctx.destroy();                    // wipe the key schedule
    unsafe { return buf[0] == (unsigned char)'H' ? 0 : 1; }
}
```

---

## SHA-256 {#sha-256}

```c
#include <std/crypto/sha256.h>
```

스트리밍(update) 방식과 원샷 방식 인터페이스를 모두 지원하는 SHA-256, SHA-224입니다. `Sha256Ctx`는 [`BlockHash`](#hash-traits)입니다.

```c
struct Sha256Ctx {
    unsigned int  h[8];           // 현재 해시 상태
    unsigned char buf[64];        // 부분 블록 버퍼
    unsigned long total_bytes;    // 처리된 총 바이트 수
    int           is224;          // 1이면 SHA-224 변형

    static struct Sha256Ctx new();       // SHA-256
    static struct Sha256Ctx new_224();   // SHA-224

    // 해시에 바이트를 공급 (스트리밍)
    void update(const &unsigned char data, unsigned long len);

    // 마무리하고 다이제스트를 기록: SHA-256은 32바이트, SHA-224는 28바이트
    void finish(&unsigned char digest);

    unsigned long digest_size() const;   // 32 또는 28
    unsigned long block_size() const;    // SHA256_BLOCK_SIZE
}

// 원샷 편의 함수
void sha256(const &unsigned char data, unsigned long len,
            &unsigned char digest);

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

    struct Sha256Ctx ctx = std::Sha256Ctx::new();  // streaming, two chunks
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

SHA-256과 같은 인터페이스이며, 64비트 워드와 128바이트 블록을 씁니다. `Sha512Ctx`는 [`BlockHash`](#hash-traits)입니다.

```c
#define SHA512_DIGEST_SIZE  64   // bytes
#define SHA384_DIGEST_SIZE  48
#define SHA512_BLOCK_SIZE   128

struct Sha512Ctx {
    unsigned long long h[8];
    unsigned char      buf[SHA512_BLOCK_SIZE];
    unsigned long long total_bytes;   // 64-bit count; the high half of the 128-bit length is written as 0
    int                is384;         // 1 = SHA-384

    static struct Sha512Ctx new();       // SHA-512
    static struct Sha512Ctx new_384();   // SHA-384

    void update(const &unsigned char data, unsigned long len);
    void finish(&unsigned char digest);  // 64 bytes (SHA-512) or 48 (SHA-384)
    unsigned long digest_size() const;   // 64 or 48
    unsigned long block_size() const;    // SHA512_BLOCK_SIZE
};

void sha512(const &unsigned char data, unsigned long len, &unsigned char digest);
void sha384(const &unsigned char data, unsigned long len, &unsigned char digest);
```

---

## SHA-1 {#sha-1}

```c
#include <std/crypto/sha1.h>
```

::: danger 깨진 해시
SHA-1에는 실용적인 충돌 공격이 존재합니다. 일부 프로토콜이 요구하기 때문에만 제공됩니다 — [WebSocket](/ko/stdlib/http#websocket) 핸드셰이크의 `Sec-WebSocket-Accept`는 SHA-1로 정의되어 있습니다. 그 외에는 SHA-256이나 SHA-512를 씁니다.
:::

```c
#define SHA1_DIGEST_SIZE  20   // bytes
#define SHA1_BLOCK_SIZE   64

struct Sha1Ctx {
    unsigned int  h[5];
    unsigned char buf[SHA1_BLOCK_SIZE];
    unsigned long total_bytes;

    static struct Sha1Ctx new();

    void update(const &unsigned char data, unsigned long len);
    void finish(&unsigned char digest);   // 20 bytes
    unsigned long digest_size() const;    // SHA1_DIGEST_SIZE
    unsigned long block_size() const;     // SHA1_BLOCK_SIZE
};

void sha1(const &unsigned char data, unsigned long len, &unsigned char digest);
```

---

## 해시 트레이트 {#hash-traits}

```c
#include <std/crypto/hash.h>   // crypto.h가 포함합니다
```

모든 해시 컨텍스트는 같은 방식으로 스트리밍합니다. `update`로 바이트를 공급하고 `finish`로 다이제스트를 기록합니다. `hash.h`는 이 형태를 두 개의 [트레이트](/ko/reference/generics#composing-traits)로 이름 붙이므로, 어떤 해시에도 동작하는 코드는 한 번만 작성하면 됩니다. 각 호출은 컴파일 타임에 결정되며 런타임 디스패치는 없습니다.

| 트레이트 | 요구 사항 | 만족하는 타입 |
|---|---|---|
| `Hasher` | `update(data, len)`, `finish(digest)`(호출 후 컨텍스트는 소진됨), `digest_size() const` | `Sha1Ctx`, `Sha256Ctx`(SHA-256 또는 SHA-224), `Sha512Ctx`(SHA-512 또는 SHA-384), `Hmac<H>` |
| `BlockHash` | `Hasher` + `block_size() const`: 압축 함수의 블록 크기로, HMAC이 키를 이 크기로 패딩합니다 | `Sha1Ctx`, `Sha256Ctx`, `Sha512Ctx` |

| 제네릭 코드 | 바운드 | 하는 일 |
|---|---|---|
| `hash_bytes(h, data, len, &digest)` | `Hasher` | 새 컨텍스트 `h`(값으로 받음)로 `len`바이트의 다이제스트를 계산: `hash_bytes(Sha512Ctx::new_384(), msg, n, &d[0])` |
| [`Hmac<H>`](#hmac-sha256) | `BlockHash` | 그 해시 위의 HMAC |

사용자 타입은 세 메서드를 갖추면 `Hasher`가 됩니다. 따로 선언할 것은 없습니다. 아래에서 제네릭 함수 하나가 std 해시 두 개와 비암호학적 FNV-1a 체크섬의 다이제스트를 출력합니다. FNV-1a에는 `block_size`가 없으므로 `Hasher`이지만 `BlockHash`는 아니며, `Hmac<Fnv1a>`는 이름을 쓰는 지점에서 오류가 됩니다.

```c
#include <std/crypto/crypto.h>
#include <std/str.h>
#include <std/io.h>

// The digest of a string, in hex, with any Hasher.
generic<H: Hasher>
void print_digest(H h, const &char msg) {
    unsigned char d[64];
    unsafe { std::hash_bytes(h, (const &unsigned char)msg, std::str_len(msg), &d[0]); }
    const &char hex = "0123456789abcdef";
    unsigned long i = 0UL;
    while (i < h.digest_size()) {
        unsafe { std::print_char((int)hex[d[i] >> 4]); std::print_char((int)hex[d[i] & 15]); }
        i = i + 1UL;
    }
    std::println("");
}

// FNV-1a: not a cryptographic hash, but it has the three methods,
// so it is a Hasher (not a BlockHash: it has no block_size).
struct Fnv1a {
    unsigned int h;
    static struct Fnv1a new();
    void update(const &unsigned char data, unsigned long len);
    void finish(&unsigned char digest);
    unsigned long digest_size() const;
};
struct Fnv1a Fnv1a::new() { struct Fnv1a f; f.h = 2166136261U; return f; }
void Fnv1a::update(const &unsigned char data, unsigned long len) {
    unsigned long i = 0UL;
    while (i < len) {
        unsafe { self.h = (self.h ^ (unsigned int)data[i]) * 16777619U; }
        i = i + 1UL;
    }
}
void Fnv1a::finish(&unsigned char digest) {
    unsafe {
        digest[0] = (unsigned char)(self.h >> 24); digest[1] = (unsigned char)(self.h >> 16);
        digest[2] = (unsigned char)(self.h >> 8);  digest[3] = (unsigned char)self.h;
    }
}
unsigned long Fnv1a::digest_size() const { return 4UL; }

int main() {
    print_digest(std::Sha256Ctx::new(), "abc");       // 32 bytes
    print_digest(std::Sha512Ctx::new_384(), "abc");   // 48 bytes
    print_digest(Fnv1a::new(), "abc");                 // 4 bytes
    return 0;
}
```

```
ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7
1a47e90b
```

---

## HMAC {#hmac-sha256}

```c
#include <std/crypto/hmac.h>
```

임의의 [`BlockHash`](#hash-traits) 위에 한 번 작성된 HMAC(RFC 2104)입니다. HMAC-SHA1, -SHA224, -SHA256, -SHA384, -SHA512는 해시 컨텍스트만 다른 같은 코드입니다. [JWT](/ko/stdlib/http#jwt) 서명은 HMAC-SHA256을 씁니다.

```c
#define HMAC_SHA256_SIZE 32   // bytes

generic<H: BlockHash>
struct Hmac {
    H inner;   // H((key ^ ipad) || message)
    H outer;   // H((key ^ opad) || 내부 다이제스트), finish()가 공급

    // 사용할 해시의 새 컨텍스트 `fresh`(Sha256Ctx::new(), Sha512Ctx::new_384(), ...)
    // 위에서 key[0..keyLen)으로 키를 설정. 해시 블록보다 긴 키는 먼저
    // 해시함 (RFC 2104).
    static struct Hmac<H> new(H fresh, const &unsigned char key, unsigned long keyLen);
    void          update(const &unsigned char data, unsigned long len);
    void          finish(&unsigned char mac);   // digest_size()바이트; 호출 후 소진됨
    unsigned long digest_size() const;          // 해시의 다이제스트 크기
};

// 원샷 HMAC-SHA256: key[0..keyLen)으로 data[0..dataLen)의 32바이트 MAC을 out에 기록.
void hmac_sha256(const &unsigned char key, unsigned long keyLen,
                 const &unsigned char data, unsigned long dataLen,
                 &unsigned char out);
```

`Hmac<H>`는 해시처럼 스트리밍하며 그 자체로 `Hasher`이므로, `hash_bytes`와 모든 `generic<H: Hasher>` 코드가 받을 수 있습니다. 타입 인자는 `struct Hmac<Sha256Ctx> mac = Hmac::new(Sha256Ctx::new(), ...)`처럼 대상에서 추론됩니다. 구현은 모든 std 해시에 대해 RFC 2202와 RFC 4231 테스트 벡터로 검사됩니다.

```c
#include <std/crypto/hmac.h>
#include <std/crypto/sha256.h>
#include <std/crypto/sha512.h>

int main() {
    unsigned char key[3] = { 'k', 'e', 'y' };
    unsigned char msg[5] = { 'h', 'e', 'l', 'l', 'o' };

    unsigned char a[HMAC_SHA256_SIZE];
    std::hmac_sha256(&key[0], 3UL, &msg[0], 5UL, &a[0]);          // one-shot

    struct Hmac<Sha256Ctx> mac = std::Hmac::new(std::Sha256Ctx::new(), &key[0], 3UL);
    mac.update(&msg[0], 2UL);                                       // streaming
    unsafe { mac.update(&msg[2], 3UL); }
    unsigned char b[HMAC_SHA256_SIZE];
    mac.finish(&b[0]);                                              // same as a

    unsigned char tag[SHA512_DIGEST_SIZE];                          // HMAC-SHA512
    struct Hmac<Sha512Ctx> mac512 = std::Hmac::new(std::Sha512Ctx::new(), &key[0], 3UL);
    mac512.update(&msg[0], 5UL);
    mac512.finish(&tag[0]);

    int same = 1;
    unsafe { int i = 0; while (i < 32) { if (a[i] != b[i]) same = 0; i = i + 1; } }
    return same ? 0 : 1;
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
    // OS의 난수 소스(Windows는 BCryptGenRandom, macOS는 getentropy, Linux는
    // getrandom), 없으면 rdrand(x86-64), 그다음 /dev/urandom으로 제자리 시드.
    // 1을 반환; 모두 실패하면(예: 베어메탈) 진단 메시지와 함께 중단 — 그런
    // 곳에서는 자체 엔트로피 소스와 init_seed를 사용.
    int           init();

    // 결정적 시드로 제자리 초기화 (테스트, 재현 가능한 시뮬레이션).
    void          init_seed(const unsigned char seed[32]);

    void          fill(&unsigned char out, unsigned long len);   // len random bytes
    unsigned int  rand32();
    unsigned long long rand64();
    unsigned long rand_range(unsigned long bound);   // uniform in [0, bound), no modulo bias
};
```

### 예제 {#example-2}

```c
#include <std/crypto/rng.h>
#include <std/io.h>

int main() {
    struct RngCtx rng;
    rng.init();   // aborts if there is no entropy source

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

    // obj_size바이트 슬롯 count개 (힙 기반, SlabAllocator::new)
    static struct SecureAllocator new(unsigned long obj_size, unsigned long count);

    // align으로 정렬된 size바이트용 슬롯 하나; 모든 슬롯이 사용 중이거나,
    // size > obj_size이거나, align이 slab.max_align()(최대 8)보다 크면 null.
    ?&heap void   alloc(unsigned long size, unsigned long align);
    void          dealloc(?&heap void ptr);  // 슬롯을 0으로 지운 뒤 해제; null이면 아무것도 안 함
    unsigned long available() const;
    void          destroy();             // 모든 슬롯을 0으로 지우고 백킹 버퍼를 해제
};
```

`SecureAllocator`는 [`Allocator`](/ko/stdlib/allocators#allocator-traits)이므로 타입 지정 `alloc_t` / `dealloc_t`(그리고 `dealloc_t`도 슬롯을 0으로 지움)와 모든 `generic<A: Allocator>` 코드가 이를 받습니다.

```c
#include <std/crypto/secure_alloc.h>

struct Key { unsigned char bytes[32]; };

int main() {
    struct SecureAllocator sa = std::SecureAllocator::new(sizeof(struct Key), 4UL);
    ?&heap Key k = std::alloc_t(&sa);   // null when full
    match (k) {
        case some(x): {
            x.bytes[0] = (unsigned char)7;
            std::dealloc_t(&sa, x);      // zeroed, then back on the free list
        }
        case null: { }
    }
    sa.destroy();                        // zeroes every slot, frees the buffer
    return 0;
}
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
    unsigned long long not_before;  // Unix 타임스탬프 (1970년 이전 날짜는 0)
    unsigned long long not_after;
}

struct X509Name {
    char cn[128];   // Common Name
    char o[128];    // Organisation
    char c[4];      // Country
}

struct X509San {
    char names[8][64];  // DNS SAN 항목
    int  count;
}

struct X509Cert {
    X509Name       subject;
    X509Name       issuer;
    X509Validity   validity;
    X509San        san;
    unsigned char  pubkey[256];     // SubjectPublicKeyInfo 본문 (최대 256바이트)
    unsigned long  pubkey_len;
    unsigned char  serial[20];
    unsigned long  serial_len;
    int            is_ca;           // BasicConstraints CA=TRUE
    int            key_usage;       // KeyUsage 원시 비트

    int is_valid_at(unsigned long long unix_time) const;
    int matches_hostname(const &char host) const;  // DNS SAN (SAN이 없을 때만 CN); 와일드카드 *.domain
    int is_ca_cert() const;

    // DER 인코딩된 인증서를 이 구조체에 제자리 파싱 (먼저 0으로 초기화).
    // 성공 시 0, 오류 시 음수.
    int init(const &unsigned char der, unsigned long len);

    // issuer_cert의 주체가 이 인증서의 발급자 이름과 일치하면 1.
    // 구조적 검사일 뿐, 서명은 검증하지 않음.
    int verify_chain(const &X509Cert issuer_cert) const;
}
```

### 파싱과 체인 검사 {#api-1}

인증서는 제자리에서 파싱합니다. `struct X509Cert`를 선언하고 `cert.init(der, len)`을 호출합니다. `cert.verify_chain(&ca)`는 이름만 비교합니다. 암호학적 서명 검증에는 RSA/ECDSA가 필요한데, std에는 아직 없습니다.

### 예제 {#example-3}

```c
#include <std/crypto/x509.h>
#include <std/io.h>

void check_cert(const &unsigned char der, unsigned long len,
                unsigned long long now) {
    struct X509Cert cert;

    if (cert.init(der, len) != 0) {
        std::println("parse error");
        return;
    }
    if (!cert.is_valid_at(now)) {
        std::println("certificate expired");
        return;
    }
    if (cert.matches_hostname("api.example.com")) {
        std::println("hostname: OK");
    }
    if (cert.is_ca_cert()) {
        std::println("is CA certificate");
    }
}
```

::: info
`matches_hostname`은 대소문자를 구분하지 않고 비교하며 와일드카드 인증서를 지원합니다: `*.example.com`은 `api.example.com`과는 일치하지만 `a.b.example.com`과는 일치하지 않습니다. 주체 CN은 인증서에 DNS SAN 항목이 없을 때만 확인합니다(RFC 6125).
:::

---

## TLS 1.3 레코드 레이어 {#tls-13-record-layer}

```c
#include <std/crypto/tls.h>
```

TLS 1.3 **레코드 레이어**(RFC 8446 §5)를 구현합니다. TLS 레코드의 프레이밍, 암호화, 복호화를 처리합니다. 키 스케줄링과 핸드셰이크 프로토콜은 **포함되어 있지 않습니다** — 외부에서 핸드셰이크를 완료한 후 키를 공급해야 합니다.

레코드 보호는 RFC 8446 §5.2의 AES-256-GCM AEAD(`TLS_AES_256_GCM_SHA384` 암호 스위트의 레코드 레이어)입니다. 같은 트래픽 키가 주어지면 보호된 레코드는 다른 TLS 1.3 구현이 만드는 것과 바이트 단위로 같습니다. 테스트 스위트는 독립적인 AES-GCM 구현으로 만든 레코드와 비교합니다.

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
#define TLS_TAG_LEN         16      // GCM tag
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

    // 새 세션: 키 0, seq = 0, handshake_done = 0.
    static struct TlsSession new();

    // Encode one record into out[0..out_cap). Pre-handshake: plaintext;
    // after install_keys: TLSInnerPlaintext (payload || content type) sealed
    // with AES-256-GCM, outer type application_data, 16-byte tag. Returns
    // bytes written, 0 on error; a record that doesn't fit out_cap doesn't
    // consume a sequence number.
    unsigned long encode_record(unsigned char content_type,
                                const &unsigned char payload, unsigned long payload_len,
                                &unsigned char out, unsigned long out_cap);

    // Decode one record from data[0..data_len); writes the plaintext to out
    // and the inner content type to *content_type_out. Protected records are
    // authenticated first: a forged or corrupted record returns 0 (as does
    // incomplete input) and doesn't advance read_seq.
    unsigned long decode_record(const &unsigned char data, unsigned long data_len,
                                &unsigned char content_type_out,
                                &unsigned char out, unsigned long out_cap);

    unsigned long build_alert(unsigned char level, unsigned char desc,
                              &unsigned char out, unsigned long out_cap);

    // Installs traffic keys, resets both sequence numbers and switches to
    // protected records (is_established() becomes 1).
    void install_keys(const unsigned char write_key[TLS_KEY_LEN],
                      const unsigned char write_iv[TLS_IV_LEN],
                      const unsigned char read_key[TLS_KEY_LEN],
                      const unsigned char read_iv[TLS_IV_LEN]);
    int  is_established() const;
};
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
    struct TlsSession session = std::TlsSession::new();
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
