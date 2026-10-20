# 인코딩 (`std/encoding/`)

ASCII 검사와 대소문자 변환, UTF-8 인코드/디코드, URL 퍼센트 인코딩, Base64를 제공합니다.

```c
#include <std/encoding/encoding.h>   // all four
// or individually:
#include <std/encoding/ascii.h>
#include <std/encoding/utf8.h>
#include <std/encoding/url.h>
#include <std/encoding/base64.h>
```

`std/prelude.h`는 이 헤더들을 포함하지 않습니다 — 직접 include하세요.

잘못된 입력에서 실패할 수 있는 함수는 `convert.h`와 같은 규칙으로 `int* ok` 출력 매개변수를 받습니다: 성공하면 1, 실패하면 0이 설정되며, 필요 없으면 null을 넘겨도 됩니다.

---

## ASCII — `ascii.h` {#ascii-ascii-h}

```c
int ascii_is_valid(const &char s);                     // 1 if every byte of the NUL-terminated string is 0..127
int ascii_is_valid_n(const &char s, unsigned long len); // same, for a raw byte range (no NUL needed)

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

ASCII만 보고 로케일은 무시합니다. C 스타일 `ctype` 함수는 [개요](/ko/stdlib/#c-compatibility-headers)의 `ctype.h`를 참고하세요.

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

`utf8_len`은 빈 문자열과 잘못된 문자열 모두에 0을 반환합니다; 둘을 구분해야 하면 먼저 `utf8_is_valid`를 호출하세요.

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

RFC 3986 퍼센트 인코딩, 컴포넌트 방식입니다(JavaScript의 `encodeURIComponent`와 같음).

```c
// Encodes every byte except A-Z a-z 0-9 - _ . ~   Space becomes "%20".
struct String url_encode(const &char s);

// "%XX" becomes byte 0xXX. "+" stays "+". On a bad escape, *ok = 0 and
// decoding stops there.
struct String url_decode(const &char s, ?&int ok);
```

`+`는 양방향 모두 공백으로 취급하지 않습니다 — 그것은 `application/x-www-form-urlencoded` 규칙이지 RFC 3986의 일부가 아닙니다. 폼 디코더는 `url_decode`를 호출하기 전에 `+`를 공백으로 바꿔야 합니다.

둘 다 힙 `String`을 반환합니다; `.free()`로 해제하세요([컬렉션](/ko/stdlib/collections) 참고).

---

## Base64 — `base64.h` {#base64-base64-h}

`=` 패딩을 쓰는 표준 알파벳입니다(RFC 4648).

```c
// No line wrapping in the output.
struct String base64_encode(const &unsigned char data, unsigned long len);

// Whitespace between groups is skipped. Any other invalid byte, or '=' that
// isn't trailing padding, sets *ok = 0 and returns an empty Vec.
struct Vec<unsigned char> base64_decode(const &char s, ?&int ok);
```

결과는 다른 `String`이나 `Vec`처럼 `.free()`로 해제하세요.

### 예제 — 왕복 변환 {#example-round-trip}

```c
#include <std/encoding/base64.h>

int main() {
    unsigned char msg[5] = { 'S', 'a', 'f', 'e', 'C' };
    struct String enc = std::base64_encode(msg, 5UL);  // "U2FmZUM="

    int ok = 0;
    struct Vec<unsigned char> dec = std::base64_decode(enc.as_ptr(), &ok);
    int good = ok == 1 && dec.len == 5UL;

    dec.free();
    enc.free();
    return good ? 0 : 1;
}
```
