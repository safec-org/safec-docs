# Security & Cryptography

SafeC provides a security library in `std/crypto/` with symmetric encryption, hashing, random number generation, certificate parsing, and TLS record-layer framing.

```c
#include "crypto/crypto.h"  // master header: pulls in all modules
```

::: warning
SafeC's crypto modules provide correct algorithm implementations. They do not currently implement side-channel countermeasures (constant-time comparison, cache-flush after key use, etc.). Use with that caveat in security-critical production contexts.
:::

---

## AES

```c
#include "crypto/aes.h"
```

AES-128 and AES-256 block cipher with ECB and CBC modes. Full S-box, key expansion, and MixColumns implementation.

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
#include "crypto/aes.h"

int main() {
    unsigned char key[16] = {0x2b,0x7e,0x15,0x16, 0x28,0xae,0xd2,0xa6,
                              0xab,0xf7,0x15,0x88, 0x09,0xcf,0x4f,0x3c};
    unsigned char iv[16]  = {0};
    unsigned char buf[32] = "Hello, SafeC! AES CBC test data.";

    struct AesCtx ctx = aes128_init(key);
    ctx.set_iv(iv);

    ctx.cbc_encrypt(buf, 32);   // buf is now ciphertext

    // reset IV and decrypt back
    ctx.set_iv(iv);
    ctx.cbc_decrypt(buf, 32);   // buf restored to plaintext
    return 0;
}
```

---

## SHA-256

```c
#include "crypto/sha256.h"
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
```

### Example

```c
#include "crypto/sha256.h"
#include "io.h"

int main() {
    unsigned char digest[32];

    // One-shot hash
    unsigned char msg[] = "hello";
    sha256(msg, 5, digest);

    // Streaming hash of two chunks
    struct Sha256Ctx ctx = sha256_init();
    unsigned char part1[] = "hel";
    unsigned char part2[] = "lo";
    ctx.update(part1, 3);
    ctx.update(part2, 2);
    ctx.finish(digest);  // same result as one-shot above

    return 0;
}
```

---

## RNG — ChaCha20 CSPRNG

```c
#include "crypto/rng.h"
```

A cryptographically secure pseudo-random number generator based on ChaCha20.

```c
struct RngCtx {
    unsigned int state[16];  // ChaCha20 state
}

// Seed from caller-supplied 32 bytes
void rng_init(struct RngCtx* ctx, const unsigned char seed[32]);

// Seed from hardware entropy:
//   x86-64: rdrand | hosted: /dev/urandom | freestanding: no-op
void rng_init_os(struct RngCtx* ctx);

// Generate `len` random bytes
void rng_fill(struct RngCtx* ctx, void* out, unsigned long len);

// Generate one random value
unsigned int       rng_u32(struct RngCtx* ctx);
unsigned long long rng_u64(struct RngCtx* ctx);
```

### Example

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
    rng_fill(&rng, key, 32);  // generate a random 256-bit key
    return 0;
}
```

---

## SecureAllocator

```c
#include "crypto/secure_alloc.h"
```

Wraps a slab allocator with zeroing-on-free, so sensitive data (keys, passwords) is erased when returned to the pool.

```c
struct SecureAllocator {
    // wraps SlabAllocator
}

SecureAllocator secure_alloc_new(unsigned long obj_size, unsigned long count);

void* secure_alloc(struct SecureAllocator* a);
void  secure_free(struct SecureAllocator* a, void* ptr);  // zeros before freeing
void  secure_destroy(struct SecureAllocator* a);           // zeros entire pool
```

---

## X.509 Certificate Parser

```c
#include "crypto/x509.h"
```

Parses X.509v3 DER-encoded certificates. Extracts subject/issuer distinguished names, validity period, Subject Alternative Names (SAN), BasicConstraints, and KeyUsage extensions.

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
    int matches_hostname(const char* host) const;  // SAN + CN fallback; wildcard *.domain
    int is_ca_cert() const;
}
```

### API

```c
// Parse a DER-encoded certificate.
// Returns 0 on success, negative on error.
int x509_parse_der(const unsigned char* der, unsigned long len,
                   struct X509Cert* out);

// Structural issuer/subject name match only.
// Does NOT verify cryptographic signature.
int x509_verify_chain(const struct X509Cert* cert,
                      const struct X509Cert* ca);
```

### Example

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
`matches_hostname` supports wildcard certificates: `*.example.com` matches `api.example.com` but not `a.b.example.com`. The CN field is checked as a fallback when no SAN entries are present.
:::

---

## TLS 1.3 Record Layer

```c
#include "crypto/tls.h"
```

Implements the TLS 1.3 **record layer** (RFC 8446 §5). Handles framing, encryption, and decryption of TLS records. Key scheduling and the handshake protocol are **not** included — supply keys after completing the handshake externally.

### Constants

```c
#define TLS_CHANGE_CIPHER_SPEC  20
#define TLS_ALERT               21
#define TLS_HANDSHAKE           22
#define TLS_APPLICATION_DATA    23
```

### Structs

```c
struct TlsTranscript {
    Sha256Ctx hash;

    void update(const void* data, unsigned long len);
    void finish(unsigned char out[32]);  // snapshot — ctx continues usable
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

    // Set symmetric keys (call after handshake completes)
    void install_keys(const unsigned char wk[32], const unsigned char wi[12],
                      const unsigned char rk[32], const unsigned char ri[12]);

    // Encode a record into `out` (PacketBuf)
    // Pre-handshake: plaintext. Post-handshake: AES-256-CBC + PKCS#7
    void encode_record(unsigned char content_type,
                       const void* data, unsigned long len,
                       struct PacketBuf* out);

    // Decode a record from `pkt`
    int  decode_record(struct PacketBuf* pkt,
                       unsigned char* ct_out,
                       void* buf, unsigned long max);

    // Build a TLS Alert record
    void build_alert(unsigned char level, unsigned char desc,
                     struct PacketBuf* out);

    int  is_established() const;
}

TlsSession tls_session_init();
```

### Nonce derivation

Per RFC 8446 §5.3, the per-record nonce is:

```
nonce = write_iv XOR (zero-padded big-endian sequence number)
```

This is computed automatically inside `encode_record` / `decode_record`.

### Example

```c
#include "crypto/tls.h"

int main() {
    TlsSession session = tls_session_init();

    // After TLS 1.3 handshake completes — supply derived keys
    unsigned char wk[32] = { /* application_write_key */ };
    unsigned char wi[12] = { /* application_write_iv  */ };
    unsigned char rk[32] = { /* application_read_key  */ };
    unsigned char ri[12] = { /* application_read_iv   */ };
    session.install_keys(wk, wi, rk, ri);

    // Send application data
    struct PacketBuf out;
    const char* msg = "GET / HTTP/1.1\r\n";
    session.encode_record(TLS_APPLICATION_DATA, msg, 16, &out);
    // transmit out via TCP...

    return 0;
}
```
