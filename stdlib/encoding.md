# Encoding (`std/encoding/`)

ASCII checks and case conversion, UTF-8 encode/decode, URLs (percent-encoding, form encoding, a parsed `Url` type with relative resolution), Base64, and DEFLATE/zlib/gzip compression.

```c
#include <std/encoding/encoding.h>   // all of them
// or individually:
#include <std/encoding/ascii.h>
#include <std/encoding/utf8.h>
#include <std/encoding/url.h>
#include <std/encoding/base64.h>
#include <std/encoding/deflate.h>
```

`std/prelude.h` does not include these headers — include them directly.

Functions that can fail on bad input take an `int* ok` out-parameter, the same convention as `convert.h`: it is set to 1 on success and 0 on failure, and may be null if you don't need it.

---

## ASCII — `ascii.h` {#ascii-ascii-h}

```c
int ascii_is_valid(const &char s);                     // 1 if every byte of the NUL-terminated string is 0..127
int ascii_is_valid_bytes([]const char s);           // same, for raw bytes (no NUL needed)

int ascii_is_alpha(char c);
int ascii_is_digit(char c);
int ascii_is_alnum(char c);
int ascii_is_space(char c);
int ascii_is_upper(char c);
int ascii_is_lower(char c);
int ascii_is_print(char c);   // 0x20..0x7E, including space

char ascii_to_upper(char c);  // unchanged unless a-z
char ascii_to_lower(char c);  // unchanged unless A-Z
```

These only look at ASCII and ignore locale. For the C-style `ctype` functions, see `ctype.h` in the [overview](/stdlib/#c-compatibility-headers).

---

## UTF-8 — `utf8.h` {#utf-8-utf8-h}

A codepoint is a Unicode scalar value (0..0x10FFFF, excluding surrogates 0xD800..0xDFFF) stored in an `unsigned int`. SafeC has no separate rune type.

```c
// Writes 1-4 bytes to out (needs room for 4). Returns the byte count,
// or 0 if cp is not a valid scalar value.
int utf8_encode(unsigned int cp, &char out);

// Decodes one codepoint at s[pos]. Returns bytes consumed (1-4), or 0 on a
// malformed sequence: bad continuation byte, overlong form, encoded surrogate,
// or a sequence cut off by NUL.
int utf8_decode(const &char s, unsigned long pos, &unsigned int cp_out);

int           utf8_is_valid(const &char s);       // 1 if the whole string is well-formed
unsigned long utf8_len(const &char s);            // codepoints, not bytes; 0 if invalid
int           utf8_encoded_len(unsigned int cp);  // 1-4, or 0 if invalid
```

`utf8_len` returns 0 for both an empty string and an invalid one; call `utf8_is_valid` first if you need to tell them apart.

### Example — walk the codepoints {#example-walk-the-codepoints}

```c
#include <std/encoding/utf8.h>
#include <std/io.h>

int main() {
    const &char s = "héllo";
    unsigned long pos = 0UL;
    unsigned int cp = 0U;
    int count = 0;
    while (1) {
        int n = std::utf8_decode(s, pos, &cp);
        if (n == 0 || cp == 0U) { break; }
        pos = pos + (unsigned long)n;
        count = count + 1;
    }
    std::println_int(count);   // 5
    return 0;
}
```

---

## URL — `url.h` {#url-url-h}

Percent-encoding (RFC 3986), the `application/x-www-form-urlencoded` format of query strings and HTML forms, and a structured URL type. Pure string code: it works on hosted, bare-metal and wasm32 targets alike.

### Percent and form encoding {#percent-and-form-encoding}

```c
// Encodes every byte except A-Z a-z 0-9 - _ . ~   Space becomes "%20".
// (JavaScript's encodeURIComponent.)
struct String url_encode(const &char s);
// "%XX" becomes byte 0xXX. "+" stays "+". On a bad escape, *ok = 0 and
// decoding stops there.
struct String url_decode(const &char s, ?&int ok);
// Like url_encode but keeps '/' and the characters a path may hold
// (sub-delims, ':', '@'): "/a b/c:d" -> "/a%20b/c:d".
struct String url_encode_path(const &char s);
// Form encoding: space <-> '+'. form_decode keeps a malformed '%' as-is.
struct String form_encode(const &char s);
struct String form_decode(const &char s);
```

`url_decode` does not turn `+` into a space — that is the form rule, which `form_decode` and `QueryParams` apply.

### QueryParams {#queryparams}

An ordered multi-map of decoded pairs — a parsed query string or form body, like the browser's `URLSearchParams`.

```c
struct QueryParams {
    static struct QueryParams new();
    static struct QueryParams parse(const &char qs);                    // a leading '?' is skipped
    static struct QueryParams parse_bytes([]const char qs);  // raw bytes (a request body)

    unsigned long length() const;
    const ?&char  get(const &char key) const;            // first value, or null
    const &char   get_or(const &char key, const &char fallback) const;
    unsigned long count(const &char key) const;
    const ?&char  get_nth(const &char key, unsigned long n) const;
    int           has(const &char key) const;
    const ?&char  key_at(unsigned long i) const;
    const ?&char  value_at(unsigned long i) const;

    void          append(const &char key, const &char value);
    void          set(const &char key, const &char value);   // replaces every pair with this key
    unsigned long remove(const &char key);                    // how many were removed
    void          sort();                                     // stable, by key

    struct String to_string() const;                          // "k=v&k2=v2", form-encoded
    struct QueryParams clone() const;
    void          free();
};
```

The `const ?&char` results point into the object's own storage: they stay valid until it is changed or freed.

### Url {#url}

```c
struct Url {
    struct String scheme;     // lowercased, no ':'; empty for a relative reference
    struct String username;
    struct String password;
    struct String host;       // lowercased; an IPv6 literal without its brackets
    int           port;       // -1 if the URL has none
    struct String path;       // as written (still percent-encoded)
    struct String query;      // without '?'
    struct String fragment;   // without '#'
    int           has_authority, has_query, has_fragment;

    static struct Url new();
    static struct Url parse(const &char s, ?&int ok);
    struct String to_string() const;
    // RFC 3986 §5.2 resolution, dot segments removed:
    // "http://a/b/c/d;p?q" + "../g" = "http://a/b/g"
    struct Url    resolve(const &char ref, ?&int ok) const;

    int           port_or_default() const;    // port, or 80/443/21 by scheme; -1 if unknown
    struct String host_port() const;          // "host[:port]" (the Host header)
    struct String origin() const;             // "scheme://host[:port]"
    struct String request_target() const;     // "/path?query" ("/" for an empty path)
    struct QueryParams query_params() const;
    void          set_query_params(const &QueryParams params);
    struct String path_decoded() const;
    int           is_absolute() const;
    struct Url    clone() const;
    void          free();
};

struct String url_remove_dot_segments(const &char path);   // "/a/b/../c/./d" -> "/a/c/d"
```

`parse` fails (`*ok = 0`) on an invalid scheme, a port that isn't a number up to 65535, an unterminated `[` IPv6 literal, or a space or control character anywhere in the text — percent-encode those first.

```c
#include <std/encoding/url.h>

int main() {
    int ok = 0;
    struct Url base = std::Url::parse("https://example.com/docs/guide/intro?lang=en", &ok);
    struct Url next = base.resolve("../reference/types#generics", &ok);
    struct String s = next.to_string();   // "https://example.com/docs/reference/types#generics"

    struct QueryParams q = base.query_params();
    const &char lang = q.get_or("lang", "ko");   // "en"

    q.free();
    s.free();
    next.free();
    base.free();
    return 0;
}
```

Every function returns heap `String`s or structures that own them; free them with `.free()`.

---

## Base64 — `base64.h` {#base64-base64-h}

Standard alphabet with `=` padding (RFC 4648).

```c
// No line wrapping in the output.
struct String base64_encode([]const unsigned char data);

// Whitespace between groups is skipped. Any other invalid byte, or '=' that
// isn't trailing padding, sets *ok = 0 and returns an empty Vec.
struct Vec<unsigned char> base64_decode(const &char s, ?&int ok);
```

Free the results with `.free()` like any other `String` or `Vec`.

### Example — round trip {#example-round-trip}

```c
#include <std/encoding/base64.h>

int main() {
    unsigned char msg[5] = { 'S', 'a', 'f', 'e', 'C' };
    struct String enc = std::base64_encode(msg[..]);  // "U2FmZUM="

    int ok = 0;
    struct Vec<unsigned char> dec = std::base64_decode(enc.as_ptr(), &ok);
    int good = ok == 1 && dec.len == 5UL;

    dec.free();
    enc.free();
    return good ? 0 : 1;
}
```

---

## DEFLATE, zlib and gzip — `deflate.h` {#deflate-deflate-h}

The compression behind HTTP's `Content-Encoding: gzip` / `deflate` (the [HTTP client](/stdlib/http#client) decodes it and the [router's gzip middleware](/stdlib/http#middleware) produces it), zip and PNG. One-shot functions over a byte range in memory; each returns a fresh `Vec<unsigned char>`.

```c
#define DEFLATE_STORE   0
#define DEFLATE_FAST    1
#define DEFLATE_DEFAULT 6
#define DEFLATE_BEST    9

// Raw DEFLATE (RFC 1951). Levels 0..9; out-of-range values are clamped.
struct Vec<unsigned char> deflate_raw([]const unsigned char data, int level);
// max_out: fail instead of growing past it (0 = no limit). consumed may be null.
struct Vec<unsigned char> inflate_raw([]const unsigned char data, unsigned long max_out, ?&unsigned long consumed, ?&int ok);

// zlib (RFC 1950): header + DEFLATE + Adler-32.
struct Vec<unsigned char> zlib_compress([]const unsigned char data, int level);
struct Vec<unsigned char> zlib_decompress([]const unsigned char data, unsigned long max_out, ?&int ok);

// gzip (RFC 1952): one member on output; every concatenated member on input,
// each CRC-32 and length checked.
struct Vec<unsigned char> gzip_compress([]const unsigned char data, int level);
struct Vec<unsigned char> gzip_decompress([]const unsigned char data, unsigned long max_out, ?&int ok);
int gzip_is_gzip([]const unsigned char data);   // starts with 1f 8b

unsigned int crc32([]const unsigned char data);   // "123456789" -> 0xCBF43926
unsigned int crc32_update(unsigned int crc, []const unsigned char data);
unsigned int adler32([]const unsigned char data);
unsigned int adler32_update(unsigned int adler, []const unsigned char data);
```

The compressor runs LZ77 over a 32 KiB window (hash chains, lazy matching from level 4) and writes each block in whichever form is smallest: stored, fixed Huffman or dynamic Huffman. The output is ordinary DEFLATE that any decoder reads, and its size is within a fraction of a percent of zlib's at the same level, though not byte-identical. The decompressor accepts every conforming stream.

Set `max_out` whenever the input is untrusted: a few kilobytes of crafted DEFLATE can expand to gigabytes.

```c
#include <std/encoding/deflate.h>
#include <std/collections/string.h>

int main() {
    struct String text = std::String::repeat_str("compress me ", 1000UL);
    struct Vec<unsigned char> gz = std::gzip_compress(text.as_bytes(), DEFLATE_DEFAULT);

    int ok = 0;
    struct Vec<unsigned char> back = std::gzip_decompress(gz.as_slice(), 1000000UL, &ok);
    int same = ok && back.len == text.length();

    back.free();
    gz.free();
    text.free();
    return same ? 0 : 1;
}
```
