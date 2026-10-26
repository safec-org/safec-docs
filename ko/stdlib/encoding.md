# 인코딩 (`std/encoding/`)

ASCII 검사와 대소문자 변환, UTF-8 인코드/디코드, URL(퍼센트 인코딩, 폼 인코딩, 상대 참조를 해석하는 `Url` 타입), Base64, DEFLATE/zlib/gzip 압축을 제공합니다.

```c
#include <std/encoding/encoding.h>   // all of them
// or individually:
#include <std/encoding/ascii.h>
#include <std/encoding/utf8.h>
#include <std/encoding/url.h>
#include <std/encoding/base64.h>
#include <std/encoding/deflate.h>
```

`std/prelude.h`는 이 헤더들을 포함하지 않습니다 — 직접 include합니다.

잘못된 입력에서 실패할 수 있는 함수는 `convert.h`와 같은 규칙으로 `int* ok` 출력 매개변수를 받습니다: 성공하면 1, 실패하면 0이 설정되며, 필요 없으면 null을 넘겨도 됩니다.

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

ASCII만 보고 로케일은 무시합니다. C 스타일 `ctype` 함수는 [개요](/ko/stdlib/#c-compatibility-headers)의 `ctype.h`를 참고하십시오.

---

## UTF-8 — `utf8.h` {#utf-8-utf8-h}

코드포인트는 `unsigned int`에 저장된 유니코드 스칼라 값(0..0x10FFFF, 서로게이트 0xD800..0xDFFF 제외)입니다. SafeC에는 별도의 rune 타입이 없습니다.

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

`utf8_len`은 빈 문자열과 잘못된 문자열 모두에 0을 반환합니다; 둘을 구분해야 하면 먼저 `utf8_is_valid`를 호출합니다.

### 예제 — 코드포인트 순회 {#example-walk-the-codepoints}

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

퍼센트 인코딩(RFC 3986), 쿼리 문자열과 HTML 폼의 `application/x-www-form-urlencoded` 형식, 그리고 구조화된 URL 타입입니다. 순수한 문자열 코드이므로 호스트, 베어메탈, wasm32 타깃 어디서나 동작합니다.

### 퍼센트 인코딩과 폼 인코딩 {#percent-and-form-encoding}

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

`url_decode`는 `+`를 공백으로 바꾸지 않습니다 — 그것은 폼 규칙이며 `form_decode`와 `QueryParams`가 적용합니다.

### QueryParams {#queryparams}

디코딩된 쌍의 순서 있는 멀티맵입니다 — 파싱된 쿼리 문자열이나 폼 본문이며, 브라우저의 `URLSearchParams`와 같습니다.

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
    void          free() consume;
};
```

`const ?&char` 결과는 객체 자신의 저장소를 가리키므로, 객체가 바뀌거나 해제되기 전까지만 유효합니다.

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
    void          free() consume;
};

struct String url_remove_dot_segments(const &char path);   // "/a/b/../c/./d" -> "/a/c/d"
```

`parse`는 잘못된 스킴, 65535 이하의 숫자가 아닌 포트, 닫히지 않은 `[` IPv6 리터럴, 텍스트 어디에든 있는 공백이나 제어 문자에서 실패합니다(`*ok = 0`) — 그런 문자는 먼저 퍼센트 인코딩하십시오.

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

모든 함수는 힙 `String`이나 그것을 소유하는 구조체를 반환하므로 `.free()`로 해제하십시오.

---

## Base64 — `base64.h` {#base64-base64-h}

`=` 패딩을 쓰는 표준 알파벳입니다(RFC 4648).

```c
// No line wrapping in the output.
struct String base64_encode([]const unsigned char data);

// Whitespace between groups is skipped. Any other invalid byte, or '=' that
// isn't trailing padding, sets *ok = 0 and returns an empty Vec.
struct Vec<unsigned char> base64_decode(const &char s, ?&int ok);
```

결과는 다른 `String`이나 `Vec`처럼 `.free()`로 해제합니다.

### 예제 — 왕복 변환 {#example-round-trip}

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

## DEFLATE, zlib, gzip — `deflate.h` {#deflate-deflate-h}

HTTP의 `Content-Encoding: gzip` / `deflate`([HTTP 클라이언트](/ko/stdlib/http#client)가 디코딩하고 [라우터의 gzip 미들웨어](/ko/stdlib/http#middleware)가 만듦), zip, PNG 뒤에 있는 압축입니다. 메모리 안의 바이트 범위를 한 번에 처리하는 함수들이며, 각각 새 `Vec<unsigned char>`를 반환합니다.

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

압축기는 32 KiB 윈도 위에서 LZ77(해시 체인, 레벨 4부터 지연 매칭)을 돌리고, 각 블록을 stored, 고정 허프만, 동적 허프만 중 가장 작은 형태로 씁니다. 출력은 어떤 디코더든 읽는 평범한 DEFLATE이며, 크기는 같은 레벨의 zlib과 1% 미만 차이로 비슷하지만 바이트 단위로 같지는 않습니다. 해제기는 규격에 맞는 모든 스트림을 받아들입니다.

입력을 신뢰할 수 없다면 항상 `max_out`을 지정하십시오: 조작된 몇 킬로바이트의 DEFLATE가 기가바이트로 불어날 수 있습니다.

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
