# str -- Strings

The `str` module provides C-string manipulation functions covering length, comparison, copy, search, tokenisation, and duplication. These operate on NUL-terminated C strings, taken as `const &char` (read) or `&char` (written) references to the first character. String literals and `char` arrays pass directly.

For a mutable, heap-allocated growable string type, see the [`String` collection](/stdlib/collections#string-mutable-string).

```c
#include <std/str.h>
```

## Length & Comparison {#length-comparison}

### str_len {#str_len}

```c
unsigned long str_len(const &char s);
```

Return the byte length of `s` (not including the NUL terminator).

### str_cmp {#str_cmp}

```c
int str_cmp(const &char a, const &char b);
```

Lexicographic comparison. Returns `< 0`, `0`, or `> 0`.

### str_ncmp {#str_ncmp}

```c
int str_ncmp(const &char a, const &char b, unsigned long n);
```

Compare at most `n` bytes of `a` and `b`.

### str_eq {#str_eq}

```c
int str_eq(const &char a, const &char b);
```

Return 1 if the strings are equal, 0 otherwise. Convenience wrapper around `str_cmp`.

## Copy & Concatenation {#copy-concatenation}

### str_copy {#str_copy}

```c
int str_copy([]char dst, const &char src);
```

Copy `src` into `dst` -- at most `n` bytes including NUL. `dst` must hold at least `n` bytes.

### str_cat {#str_cat}

```c
int str_cat([]char dst, const &char src);
```

Append `src` to the NUL-terminated string at `dst`. The destination buffer must have enough room.

### str_ncat {#str_ncat}

```c
int str_ncat([]char dst, const &char src, unsigned long n);
```

Append at most `n` bytes of `src` to `dst` and always NUL-terminate.

## Search {#search}

### str_find {#str_find}

```c
const ?&char str_find(const &char haystack, const &char needle);
```

Find first occurrence of `needle` in `haystack`. Returns a pointer to the match, or NULL if not found.

### str_find_char {#str_find_char}

```c
const ?&char str_find_char(const &char s, int c);
```

Find first occurrence of character `c` in `s`. Returns pointer or NULL.

### str_rfind_char {#str_rfind_char}

```c
const ?&char str_rfind_char(const &char s, int c);
```

Find last occurrence of character `c` in `s`. Returns pointer or NULL.

### str_find_any {#str_find_any}

```c
const ?&char str_find_any(const &char s, const &char accept);
```

Find first character in `s` that is also in `accept`. Returns pointer or NULL.

## Span & Classification {#span-classification}

### str_span {#str_span}

```c
unsigned long str_span(const &char s, const &char accept);
```

Return the length of the initial segment of `s` consisting only of characters in `accept`.

### str_cspan {#str_cspan}

```c
unsigned long str_cspan(const &char s, const &char reject);
```

Return the length of the initial segment of `s` with no characters from `reject`.

## Tokenisation {#tokenisation}

### str_tok {#str_tok}

```c
?&char str_tok(?&char s, const &char delim, &?&char saveptr);
```

Reentrant tokeniser (wraps `strtok_r` / `strtok_s`).

- **First call:** pass the string as `s`, and the address of a `?&char` for `saveptr` (where the tokenizer remembers its position).
- **Subsequent calls:** pass `null` as `s`.
- Returns a pointer to the next token, or NULL when exhausted.
- The input string **is** modified (NUL-terminators are written at delimiter positions).

## Memory Search {#memory-search}

### mem_chr {#mem_chr}

```c
const ?&unsigned char mem_chr([]const unsigned char s, int c);
```

Return a pointer to the first occurrence of byte `c` in the `n`-byte block at `s`, or NULL.

## Allocation & Duplication {#allocation-duplication}

### str_dup {#str_dup}

```c
?&heap char str_dup(const &char s);
```

Return a heap-allocated NUL-terminated copy of `s`. The caller must call `dealloc()` to free it.

### str_ndup {#str_ndup}

```c
?&heap char str_ndup(const &char s, unsigned long n);
```

Return a heap-allocated, NUL-terminated copy of at most the first `n` bytes of `s` — fewer if `s` ends sooner (it never reads past the terminator).

## Example {#example}

```c
#include <std/str.h>
#include <std/io.h>
#include <std/mem.h>

int main() {
    const &char greeting = "Hello, SafeC!";

    // Length
    unsigned long len = str_len(greeting);
    print("Length: ");
    println_int(len);   // 13

    // Search
    match (str_find(greeting, "SafeC")) {
        case some(pos): { println(pos); }       // SafeC!
        case null:      { println("not found"); }
    }

    // Duplication
    ?&heap char copy = str_dup(greeting);
    match (copy) {
        case some(c): { println(c); }
        case null:    { }
    }
    dealloc(copy);

    // Tokenisation
    char buf[64];
    str_copy(buf[..], "one,two,three");
    ?&char save = null;
    ?&char tok = str_tok(buf, ",", &save);
    while (!tok.is_null()) {
        match (tok) { case some(t): { println(t); } case null: { } }
        tok = str_tok(null, ",", &save);
    }

    return 0;
}
```
