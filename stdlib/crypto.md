# Security & Cryptography

SafeC provides a security library in `std/crypto/` with symmetric encryption, hashing, HMAC, random number generation, certificate parsing, and TLS record-layer framing.

Each algorithm's state is a struct: you get one from a [static constructor](/reference/functions#static-methods) such as `AesCtx::new_128` or `Sha256Ctx::new` and call its methods. The hash contexts share the [`Hasher` / `BlockHash` traits](#hash-traits), so HMAC and one-shot hashing are written once over every hash.

```c
#include <std/crypto/crypto.h>  // master header: pulls in all modules
```

::: warning
SafeC's crypto modules provide correct algorithm implementations. They do not currently implement side-channel countermeasures (constant-time comparison, cache-flush after key use, etc.). Use with that caveat in security-critical production contexts.
:::

---

## AES {#aes}

```c
#include <std/crypto/aes.h>
```

AES-128 and AES-256 block cipher with ECB, CBC and GCM modes. Full S-box, key expansion, and MixColumns implementation.

| Constant | Value |
|----------|-------|
| `AES_BLOCK_SIZE` | 16 bytes |
| `AES128_KEY_SIZE` / `AES256_KEY_SIZE` | 16 / 32 bytes |
| `AES128_ROUNDS` / `AES256_ROUNDS` | 10 / 14 |
| `AES_KS_WORDS` | 60 (key-schedule words) |

### API {#api}

```c
struct AesCtx {
    unsigned int  ks[60];             // expanded key schedule
    int           rounds;             // 10 (AES-128) or 14 (AES-256)
    unsigned char iv[16];             // CBC initialisation vector

    // Constructors (IV zeroed)
    static struct AesCtx new_128(const &unsigned char key);   // key: 16 bytes
    static struct AesCtx new_256(const &unsigned char key);   // key: 32 bytes

    // ECB — encrypt/decrypt one 16-byte block in-place
    void encrypt_block(&unsigned char block);
    void decrypt_block(&unsigned char block);

    // CBC — `len` must be a multiple of 16; updates self.iv in-place
    void cbc_encrypt(&unsigned char data, unsigned long len);
    void cbc_decrypt(&unsigned char data, unsigned long len);

    // Set the CBC IV
    void set_iv(const &unsigned char iv);

    // GCM (NIST SP 800-38D), 12-byte IV, 16-byte tag. Encrypts data in place
    // and writes the tag over aad[0..aad_len) and the ciphertext; aad may be
    // null when aad_len is 0. Never reuse an IV with the same key.
    void gcm_encrypt(const &unsigned char iv, const ?&unsigned char aad, unsigned long aad_len,
                     &unsigned char data, unsigned long len, &unsigned char tag);
    // Checks the tag in constant time; only if it matches, decrypts in place.
    // 1 if authentic, 0 if not (data is left as the ciphertext).
    int  gcm_decrypt(const &unsigned char iv, const ?&unsigned char aad, unsigned long aad_len,
                     &unsigned char data, unsigned long len, const &unsigned char tag);

    // Wipe the key schedule and IV (volatile stores) when done.
    void destroy();
}
```

::: info
`cbc_encrypt` / `cbc_decrypt` encrypt the buffer **in-place** and update `self.iv` after each block, ready for chaining the next call. The pointer arithmetic over the buffer happens inside `unsafe {}`.
:::

### Example {#example}

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

SHA-256 and SHA-224 with streaming (update) and one-shot interfaces. `Sha256Ctx` is a [`BlockHash`](#hash-traits).

```c
struct Sha256Ctx {
    unsigned int  h[8];           // current hash state
    unsigned char buf[64];        // partial block buffer
    unsigned long total_bytes;    // total bytes processed
    int           is224;          // 1 = SHA-224 variant

    static struct Sha256Ctx new();       // SHA-256
    static struct Sha256Ctx new_224();   // SHA-224

    // Feed bytes into the hash (streaming)
    void update(const &unsigned char data, unsigned long len);

    // Finalise and write digest: 32 bytes for SHA-256, 28 for SHA-224
    void finish(&unsigned char digest);

    unsigned long digest_size() const;   // 32 or 28
    unsigned long block_size() const;    // SHA256_BLOCK_SIZE
}

// One-shot convenience
void sha256(const &unsigned char data, unsigned long len,
            &unsigned char digest);

#define SHA256_DIGEST_SIZE  32   // bytes
#define SHA224_DIGEST_SIZE  28
#define SHA256_BLOCK_SIZE   64
```

### Example {#example-1}

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

Same interface as SHA-256, with 64-bit words and a 128-byte block. `Sha512Ctx` is a [`BlockHash`](#hash-traits).

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

    static struct Sha1Ctx new();

    void update(const &unsigned char data, unsigned long len);
    void finish(&unsigned char digest);   // 20 bytes
    unsigned long digest_size() const;    // SHA1_DIGEST_SIZE
    unsigned long block_size() const;     // SHA1_BLOCK_SIZE
};

void sha1(const &unsigned char data, unsigned long len, &unsigned char digest);
```

---

## Hash traits {#hash-traits}

```c
#include <std/crypto/hash.h>   // crypto.h includes it
```

Every hash context streams the same way: `update` feeds bytes, `finish` writes the digest. `hash.h` names that shape as two [traits](/reference/generics#composing-traits), so code that works over any hash is written once. Each call is resolved at compile time; there is no dispatch at run time.

| Trait | Requires | Satisfied by |
|---|---|---|
| `Hasher` | `update(data, len)`, `finish(digest)` (the context is spent after), `digest_size() const` | `Sha1Ctx`, `Sha256Ctx` (SHA-256 or SHA-224), `Sha512Ctx` (SHA-512 or SHA-384), `Hmac<H>` |
| `BlockHash` | `Hasher` + `block_size() const`: the compression-function block, which HMAC pads its key to | `Sha1Ctx`, `Sha256Ctx`, `Sha512Ctx` |

| Generic code | Bound | Does |
|---|---|---|
| `hash_bytes(h, data, len, &digest)` | `Hasher` | the digest of `len` bytes with a fresh context `h`, taken by value: `hash_bytes(Sha512Ctx::new_384(), msg, n, &d[0])` |
| [`Hmac<H>`](#hmac-sha256) | `BlockHash` | HMAC over that hash |

A type of your own is a `Hasher` once it has the three methods; nothing else declares it. Below, one generic function prints the digest for two std hashes and for a non-cryptographic FNV-1a checksum. FNV-1a has no `block_size`, so it is a `Hasher` but not a `BlockHash`, and `Hmac<Fnv1a>` is rejected where it is named.

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

HMAC (RFC 2104) written once over any [`BlockHash`](#hash-traits): HMAC-SHA1, -SHA224, -SHA256, -SHA384 and -SHA512 are the same code with a different hash context. [JWT](/stdlib/http#jwt) signing uses HMAC-SHA256.

```c
#define HMAC_SHA256_SIZE 32   // bytes

generic<H: BlockHash>
struct Hmac {
    H inner;   // H((key ^ ipad) || message)
    H outer;   // H((key ^ opad) || inner digest), fed by finish()

    // Keyed by key[0..keyLen) over `fresh`, a new context of the hash to use
    // (Sha256Ctx::new(), Sha512Ctx::new_384(), ...). A key longer than the
    // hash's block is hashed first (RFC 2104).
    static struct Hmac<H> new(H fresh, const &unsigned char key, unsigned long keyLen);
    void          update(const &unsigned char data, unsigned long len);
    void          finish(&unsigned char mac);   // digest_size() bytes; spent after
    unsigned long digest_size() const;          // the hash's digest size
};

// One-shot HMAC-SHA256: the 32-byte MAC of data[0..dataLen) under key[0..keyLen).
void hmac_sha256(const &unsigned char key, unsigned long keyLen,
                 const &unsigned char data, unsigned long dataLen,
                 &unsigned char out);
```

`Hmac<H>` streams like a hash and is a `Hasher` itself, so `hash_bytes` and any `generic<H: Hasher>` code take it. The type argument is inferred from the target, as in `struct Hmac<Sha256Ctx> mac = Hmac::new(Sha256Ctx::new(), ...)`. The implementation is checked against the RFC 2202 and RFC 4231 test vectors for every std hash.

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

A cryptographically secure pseudo-random number generator based on ChaCha20. Output is produced in `RNG_BLOCK_SIZE` (64-byte) ChaCha20 blocks.

```c
struct RngCtx {
    // Seed in place from rdrand (x86-64) or /dev/urandom. Returns 1; aborts
    // with a diagnostic if neither exists (e.g. bare metal): use init_seed
    // with your own entropy source there.
    int           init();

    // Deterministic seed in place (tests, reproducible simulations).
    void          init_seed(const unsigned char seed[32]);

    void          fill(&unsigned char out, unsigned long len);   // len random bytes
    unsigned int  rand32();
    unsigned long long rand64();
    unsigned long rand_range(unsigned long bound);   // uniform in [0, bound), no modulo bias
};
```

### Example {#example-2}

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

Wraps a slab allocator with zeroing-on-free, so sensitive data (keys, passwords) is erased when returned to the pool.

Fixed-size slots also mean no size metadata can leak.

```c
struct SecureAllocator {
    struct SlabAllocator slab;

    // count slots of obj_size bytes each (heap-backed, SlabAllocator::new)
    static struct SecureAllocator new(unsigned long obj_size, unsigned long count);

    // One slot for size bytes aligned to align; null when every slot is in
    // use, size > obj_size, or align is above slab.max_align() (at most 8).
    ?&heap void   alloc(unsigned long size, unsigned long align);
    void          dealloc(?&heap void ptr);  // zeroes the slot, then frees it; null is a no-op
    unsigned long available() const;
    void          destroy();             // zeroes every slot, frees the backing buffer
};
```

`SecureAllocator` is an [`Allocator`](/stdlib/allocators#allocator-traits), so the typed `alloc_t` / `dealloc_t` work over it (and `dealloc_t` zeroes the slot too), as does any `generic<A: Allocator>` code:

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

## X.509 Certificate Parser {#x509-certificate-parser}

```c
#include <std/crypto/x509.h>
```

Parses X.509v3 DER-encoded certificates. Extracts subject/issuer distinguished names, validity period, Subject Alternative Names (SAN), BasicConstraints, and KeyUsage extensions.

Fixed limits: `X509_MAX_NAME_LEN` (128 bytes per name), `X509_MAX_SAN` (8 SAN entries), `X509_MAX_SAN_LEN` (64 bytes each), `X509_KEY_LEN` (256-byte public key).

### Structs {#structs}

```c
struct X509Validity {
    unsigned long long not_before;  // Unix timestamp (dates before 1970 clamp to 0)
    unsigned long long not_after;
}

struct X509Name {
    char cn[128];   // Common Name
    char o[128];    // Organisation
    char c[4];      // Country
}

struct X509San {
    char names[8][64];  // DNS SAN entries
    int  count;
}

struct X509Cert {
    X509Name       subject;
    X509Name       issuer;
    X509Validity   validity;
    X509San        san;
    unsigned char  pubkey[256];     // the SubjectPublicKeyInfo body (truncated to 256 bytes)
    unsigned long  pubkey_len;
    unsigned char  serial[20];
    unsigned long  serial_len;
    int            is_ca;           // BasicConstraints CA=TRUE
    int            key_usage;       // raw KeyUsage bits

    int is_valid_at(unsigned long long unix_time) const;
    int matches_hostname(const &char host) const;  // DNS SANs (CN only without SANs); wildcard *.domain
    int is_ca_cert() const;

    // Parse a DER-encoded certificate into this struct (zeroed first).
    // Returns 0 on success, negative on error.
    int init(const &unsigned char der, unsigned long len);

    // 1 if issuer_cert's subject matches this certificate's issuer name.
    // Structural check only: does NOT verify the signature.
    int verify_chain(const &X509Cert issuer_cert) const;
}
```

### Parsing and chain checks {#api-1}

A certificate is parsed in place: declare a `struct X509Cert` and call `cert.init(der, len)`. `cert.verify_chain(&ca)` only compares names; cryptographic signature verification needs RSA/ECDSA, which std doesn't have yet.

### Example {#example-3}

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
`matches_hostname` compares case-insensitively and supports wildcard certificates: `*.example.com` matches `api.example.com` but not `a.b.example.com`. The subject CN is checked only when the certificate has no DNS SAN entries (RFC 6125).
:::

---

## TLS 1.3 Record Layer {#tls-13-record-layer}

```c
#include <std/crypto/tls.h>
```

Implements the TLS 1.3 **record layer** (RFC 8446 §5). Handles framing, encryption, and decryption of TLS records. Key scheduling and the handshake protocol are **not** included — supply keys after completing the handshake externally.

Record protection is the AES-256-GCM AEAD of RFC 8446 §5.2 (the `TLS_AES_256_GCM_SHA384` cipher suite's record layer): given the same traffic keys, protected records are byte-for-byte what other TLS 1.3 stacks produce. The test suite checks a record against an independent AES-GCM implementation.

### Constants {#constants}

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

### Structs {#structs-1}

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

    // A fresh session: zero keys, seq = 0, handshake_done = 0.
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

### Nonce derivation {#nonce-derivation}

Per RFC 8446 §5.3, the per-record nonce is:

```
nonce = write_iv XOR (zero-padded big-endian sequence number)
```

This is computed automatically inside `encode_record` / `decode_record`. The same derivation is exposed as a method if you need it directly:

```c
void TlsSession::compute_nonce(unsigned char nonce[TLS_IV_LEN], int is_write) const;
```

### Example {#example-4}

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
