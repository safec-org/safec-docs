# Security & Cryptography

SafeC provides a security library in `std/crypto/` with symmetric encryption, hashing, random number generation, certificate parsing, and TLS record-layer framing.

```c
#include <std/crypto/crypto.h>  // master header: pulls in all modules
```

::: warning
SafeC's crypto modules provide correct algorithm implementations. They do not currently implement side-channel countermeasures (constant-time comparison, cache-flush after key use, etc.). Use with that caveat in security-critical production contexts.
:::

---

## AES

```c
#include <std/crypto/aes.h>
```

AES-128 and AES-256 block cipher with ECB and CBC modes. Full S-box, key expansion, and MixColumns implementation.

| Constant | Value |
|----------|-------|
| `AES_BLOCK_SIZE` | 16 bytes |
| `AES128_KEY_SIZE` / `AES256_KEY_SIZE` | 16 / 32 bytes |
| `AES128_ROUNDS` / `AES256_ROUNDS` | 10 / 14 |
| `AES_KS_WORDS` | 60 (key-schedule words) |

### API

```c
struct AesCtx {
    unsigned int  ks[60];             // expanded key schedule
    int           rounds;             // 10 (AES-128) or 14 (AES-256)
    unsigned char iv[16];             // CBC initialisation vector

    // ECB — encrypt/decrypt one 16-byte block in-place
    void encrypt_block(&stack unsigned char block);
    void decrypt_block(&stack unsigned char block);

    // CBC — `len` must be a multiple of 16; updates self.iv in-place
    void cbc_encrypt(&stack unsigned char data, unsigned long len);
    void cbc_decrypt(&stack unsigned char data, unsigned long len);

    // Set the CBC IV
    void set_iv(const &stack unsigned char iv);
}

// Constructors — key parameter is a safe stack reference
struct AesCtx aes128_init(const &stack unsigned char key);  // key: 16 bytes
struct AesCtx aes256_init(const &stack unsigned char key);  // key: 32 bytes
```

::: info
`cbc_encrypt` / `cbc_decrypt` encrypt the buffer **in-place** and update `self.iv` after each block, ready for chaining the next call. The pointer arithmetic over the buffer happens inside `unsafe {}`.
:::

### Example

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

## SHA-256

```c
#include <std/crypto/sha256.h>
```

SHA-256 and SHA-224 with streaming (update) and one-shot interfaces.

```c
struct Sha256Ctx {
    unsigned int  h[8];           // current hash state
    unsigned char buf[64];        // partial block buffer
    unsigned long total_bytes;    // total bytes processed
    int           is224;          // 1 = SHA-224 variant

    // Feed bytes into the hash (streaming)
    void update(const &stack unsigned char data, unsigned long len);

    // Finalise and write digest: 32 bytes for SHA-256, 28 for SHA-224
    void finish(&stack unsigned char digest);
}

// Constructors
struct Sha256Ctx sha256_init();   // SHA-256
struct Sha256Ctx sha224_init();   // SHA-224

// One-shot convenience
void sha256(const &stack unsigned char data, unsigned long len,
            &stack unsigned char digest);

#define SHA256_DIGEST_SIZE  32   // bytes
#define SHA224_DIGEST_SIZE  28
#define SHA256_BLOCK_SIZE   64
```

### Example

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

## SHA-512 / SHA-384

```c
#include <std/crypto/sha512.h>
```

Same interface as SHA-256, with 64-bit words and a 128-byte block.

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

## SHA-1

```c
#include <std/crypto/sha1.h>
```

::: danger Broken hash
SHA-1 has practical collision attacks. It is here only because some protocols require it — the [WebSocket](/stdlib/http#websocket) handshake's `Sec-WebSocket-Accept` is defined with SHA-1. Use SHA-256 or SHA-512 for anything else.
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

## HMAC-SHA256

```c
#include <std/crypto/hmac.h>
```

HMAC (RFC 2104) over SHA-256. Used by [JWT](/stdlib/http#jwt) signing.

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

## RNG — ChaCha20 CSPRNG

```c
#include <std/crypto/rng.h>
```

A cryptographically secure pseudo-random number generator based on ChaCha20. Output is produced in `RNG_BLOCK_SIZE` (64-byte) ChaCha20 blocks.

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

### Example

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

## SecureAllocator

```c
#include <std/crypto/secure_alloc.h>
```

Wraps a slab allocator with zeroing-on-free, so sensitive data (keys, passwords) is erased when returned to the pool.

Fixed-size slots also mean no size metadata can leak.

```c
struct SecureAllocator {
    struct SlabAllocator slab;

    &heap void    alloc();               // one slot; null when full
    void          dealloc(?&heap void ptr);  // zeroes the slot, then frees it; null is a no-op
    unsigned long available() const;
    void          destroy();             // zeroes everything, frees the backing buffer
};

struct SecureAllocator secure_alloc_new(unsigned long obj_size, unsigned long count);
```

---

## X.509 Certificate Parser

```c
#include <std/crypto/x509.h>
```

Parses X.509v3 DER-encoded certificates. Extracts subject/issuer distinguished names, validity period, Subject Alternative Names (SAN), BasicConstraints, and KeyUsage extensions.

Fixed limits: `X509_MAX_NAME_LEN` (128 bytes per name), `X509_MAX_SAN` (8 SAN entries), `X509_MAX_SAN_LEN` (64 bytes each), `X509_KEY_LEN` (256-byte public key).

### Structs

```c
struct X509Validity {
    unsigned long long not_before;  // Unix timestamp
    unsigned long long not_after;
}

struct X509Name {
    char cn[128];   // Common Name
    char o[128];    // Organisation
    char c[4];      // Country
}

struct X509San {
    char          names[8][64];  // DNS SAN entries
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
    int matches_hostname(const &char host) const;  // SAN + CN fallback; wildcard *.domain
    int is_ca_cert() const;
}
```

### API

```c
// Parse a DER-encoded certificate.
// Returns 0 on success, negative on error.
int x509_parse_der(const &unsigned char der, unsigned long len,
                   &X509Cert out);

// Structural issuer/subject name match only.
// Does NOT verify cryptographic signature.
int x509_verify_chain(const &X509Cert cert,
                      const &X509Cert ca);
```

### Example

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
`matches_hostname` supports wildcard certificates: `*.example.com` matches `api.example.com` but not `a.b.example.com`. The CN field is checked as a fallback when no SAN entries are present.
:::

---

## TLS 1.3 Record Layer

```c
#include <std/crypto/tls.h>
```

Implements the TLS 1.3 **record layer** (RFC 8446 §5). Handles framing, encryption, and decryption of TLS records. Key scheduling and the handshake protocol are **not** included — supply keys after completing the handshake externally.

### Constants

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

### Structs

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

### Nonce derivation

Per RFC 8446 §5.3, the per-record nonce is:

```
nonce = write_iv XOR (zero-padded big-endian sequence number)
```

This is computed automatically inside `encode_record` / `decode_record`. The same derivation is exposed as a method if you need it directly:

```c
void TlsSession::compute_nonce(unsigned char nonce[TLS_IV_LEN], int is_write) const;
```

### Example

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
