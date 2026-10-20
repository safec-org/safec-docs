# Encoding (`std/encoding/`)

ASCII checks and case conversion, UTF-8 encode/decode, URL percent-encoding, and Base64.

```c
#include <std/encoding/encoding.h>   // all four
// or individually:
#include <std/encoding/ascii.h>
#include <std/encoding/utf8.h>
#include <std/encoding/url.h>
#include <std/encoding/base64.h>
```

`std/prelude.h` does not include these headers — include them directly.

Functions that can fail on bad input take an `int* ok` out-parameter, the same convention as `convert.h`: it is set to 1 on success and 0 on failure, and may be null if you don't need it.

---

## ASCII — `ascii.h`

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

These only look at ASCII and ignore locale. For the C-style `ctype` functions, see `ctype.h` in the [overview](/stdlib/#c-compatibility-headers).

---

## UTF-8 — `utf8.h`

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

### Example — walk the codepoints

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

## URL — `url.h`

Percent-encoding per RFC 3986, component style (like JavaScript's `encodeURIComponent`).

```c
// Encodes every byte except A-Z a-z 0-9 - _ . ~   Space becomes "%20".
struct String url_encode(const &char s);

// "%XX" becomes byte 0xXX. "+" stays "+". On a bad escape, *ok = 0 and
// decoding stops there.
struct String url_decode(const &char s, ?&int ok);
```

`+` is not treated as a space in either direction — that is an `application/x-www-form-urlencoded` rule, not part of RFC 3986. A form decoder should replace `+` with a space before calling `url_decode`.

Both return a heap `String`; free it with `.free()` (see [Collections](/stdlib/collections)).

---

## Base64 — `base64.h`

Standard alphabet with `=` padding (RFC 4648).

```c
// No line wrapping in the output.
struct String base64_encode(const &unsigned char data, unsigned long len);

// Whitespace between groups is skipped. Any other invalid byte, or '=' that
// isn't trailing padding, sets *ok = 0 and returns an empty Vec.
struct Vec<unsigned char> base64_decode(const &char s, ?&int ok);
```

Free the results with `.free()` like any other `String` or `Vec`.

### Example — round trip

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
