# str -- Strings

The `str` module provides C-string manipulation functions covering length, comparison, copy, search, tokenisation, and duplication. These operate on NUL-terminated `const char*` / `char*` pointers.

For a mutable, heap-allocated growable string type, see the [`String` collection](/stdlib/collections#string----mutable-string).

```c
#include "str.h"
```

## Length & Comparison

### str_len

```c
unsigned long str_len(const char* s);
```

Return the byte length of `s` (not including the NUL terminator).

### str_cmp

```c
int str_cmp(const char* a, const char* b);
```

Lexicographic comparison. Returns `< 0`, `0`, or `> 0`.

### str_ncmp

```c
int str_ncmp(const char* a, const char* b, unsigned long n);
```

Compare at most `n` bytes of `a` and `b`.

### str_eq

```c
int str_eq(const char* a, const char* b);
```

Return 1 if the strings are equal, 0 otherwise. Convenience wrapper around `str_cmp`.

## Copy & Concatenation

### str_copy

```c
void str_copy(char* dst, const char* src, unsigned long n);
```

Copy `src` into `dst` -- at most `n` bytes including NUL. `dst` must hold at least `n` bytes.

### str_cat

```c
void str_cat(char* dst, const char* src);
```

Append `src` to the NUL-terminated string at `dst`. The destination buffer must have enough room.

### str_ncat

```c
void str_ncat(char* dst, const char* src, unsigned long n);
```

Append at most `n` bytes of `src` to `dst` and always NUL-terminate.

## Search

### str_find

```c
const char* str_find(const char* haystack, const char* needle);
```

Find first occurrence of `needle` in `haystack`. Returns a pointer to the match, or NULL if not found.

### str_find_char

```c
const char* str_find_char(const char* s, int c);
```

Find first occurrence of character `c` in `s`. Returns pointer or NULL.

### str_rfind_char

```c
const char* str_rfind_char(const char* s, int c);
```

Find last occurrence of character `c` in `s`. Returns pointer or NULL.

### str_find_any

```c
const char* str_find_any(const char* s, const char* accept);
```

Find first character in `s` that is also in `accept`. Returns pointer or NULL.

## Span & Classification

### str_span

```c
unsigned long str_span(const char* s, const char* accept);
```

Return the length of the initial segment of `s` consisting only of characters in `accept`.

### str_cspan

```c
unsigned long str_cspan(const char* s, const char* reject);
```

Return the length of the initial segment of `s` with no characters from `reject`.

## Tokenisation

### str_tok

```c
char* str_tok(char* s, const char* delim, char** saveptr);
```

Reentrant tokeniser (wraps `strtok_r` / `strtok_s`).

- **First call:** pass the string as `s`, any non-NULL `char**` for `saveptr`.
- **Subsequent calls:** pass NULL as `s`.
- Returns a pointer to the next token, or NULL when exhausted.
- The input string **is** modified (NUL-terminators are written at delimiter positions).

## Memory Search

### mem_chr

```c
void* mem_chr(const void* s, int c, unsigned long n);
```

Return a pointer to the first occurrence of byte `c` in the `n`-byte block at `s`, or NULL.

## Allocation & Duplication

### str_dup

```c
char* str_dup(const char* s);
```

Return a heap-allocated NUL-terminated copy of `s`. The caller must call `dealloc()` to free it.

### str_ndup

```c
char* str_ndup(const char* s, unsigned long n);
```

Return a heap-allocated copy of the first `n` bytes of `s`, NUL-terminated.

## Example

```c
#include "str.h"
#include "io.h"
#include "mem.h"

int main() {
    const char* greeting = "Hello, SafeC!";

    // Length
    unsigned long len = str_len(greeting);
    print("Length: ");
    println_int(len);   // 13

    // Search
    const char* pos = str_find(greeting, "SafeC");
    if (pos != 0) {
        print("Found at offset: ");
        println_int(pos - greeting);  // 7
    }

    // Duplication
    char* copy = str_dup(greeting);
    println(copy);
    dealloc(copy);

    // Tokenisation
    char buf[64];
    str_copy(buf, "one,two,three", 64);
    char* save;
    char* tok = str_tok(buf, ",", &save);
    while (tok != 0) {
        println(tok);
        tok = str_tok(0, ",", &save);
    }

    return 0;
}
```
