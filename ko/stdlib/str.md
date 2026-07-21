# str -- 문자열

`str` 모듈은 길이, 비교, 복사, 검색, 토큰화, 복제를 아우르는 C 문자열
조작 함수를 제공한다. 이 함수들은 NUL로 끝나는 `const char*`/`char*`
포인터에 대해 동작한다.

가변적이고 힙에 할당되는, 커질 수 있는 문자열 타입은
[`String` 컬렉션](/ko/stdlib/collections#string-mutable-string)을
참고한다.

```c
#include "str.h"
```

## 길이 및 비교 {#length-comparison}

### str_len {#str_len}

```c
unsigned long str_len(const char* s);
```

`s`의 바이트 길이를 반환한다(NUL 종료 문자는 포함하지 않음).

### str_cmp {#str_cmp}

```c
int str_cmp(const char* a, const char* b);
```

사전식 비교. `< 0`, `0`, `> 0` 중 하나를 반환한다.

### str_ncmp {#str_ncmp}

```c
int str_ncmp(const char* a, const char* b, unsigned long n);
```

`a`와 `b`를 최대 `n`바이트까지 비교한다.

### str_eq {#str_eq}

```c
int str_eq(const char* a, const char* b);
```

두 문자열이 같으면 1, 아니면 0을 반환한다. `str_cmp`를 감싸는 편의
래퍼다.

## 복사 및 연결 {#copy-concatenation}

### str_copy {#str_copy}

```c
void str_copy(char* dst, const char* src, unsigned long n);
```

`src`를 `dst`로 복사한다 -- NUL을 포함해 최대 `n`바이트까지. `dst`는
최소 `n`바이트를 담을 수 있어야 한다.

### str_cat {#str_cat}

```c
void str_cat(char* dst, const char* src);
```

`dst`에 있는 NUL로 끝나는 문자열 뒤에 `src`를 덧붙인다. 대상 버퍼는
충분한 공간을 가지고 있어야 한다.

### str_ncat {#str_ncat}

```c
void str_ncat(char* dst, const char* src, unsigned long n);
```

`src`의 최대 `n`바이트를 `dst`에 덧붙이고 항상 NUL로 종료한다.

## 검색 {#search}

### str_find {#str_find}

```c
const char* str_find(const char* haystack, const char* needle);
```

`haystack`에서 `needle`이 처음 나타나는 위치를 찾는다. 일치하는
위치에 대한 포인터를 반환하며, 찾지 못하면 NULL을 반환한다.

### str_find_char {#str_find_char}

```c
const char* str_find_char(const char* s, int c);
```

`s`에서 문자 `c`가 처음 나타나는 위치를 찾는다. 포인터 또는 NULL을
반환한다.

### str_rfind_char {#str_rfind_char}

```c
const char* str_rfind_char(const char* s, int c);
```

`s`에서 문자 `c`가 마지막으로 나타나는 위치를 찾는다. 포인터 또는
NULL을 반환한다.

### str_find_any {#str_find_any}

```c
const char* str_find_any(const char* s, const char* accept);
```

`s`에서 `accept`에도 속하는 첫 번째 문자를 찾는다. 포인터 또는 NULL을
반환한다.

## 구간 및 분류 {#span-classification}

### str_span {#str_span}

```c
unsigned long str_span(const char* s, const char* accept);
```

`s`의 시작 부분 중 `accept`에 속한 문자로만 이루어진 구간의 길이를
반환한다.

### str_cspan {#str_cspan}

```c
unsigned long str_cspan(const char* s, const char* reject);
```

`s`의 시작 부분 중 `reject`에 속한 문자가 하나도 없는 구간의 길이를
반환한다.

## 토큰화 {#tokenisation}

### str_tok {#str_tok}

```c
char* str_tok(char* s, const char* delim, char** saveptr);
```

재진입 가능한 토크나이저(`strtok_r`/`strtok_s`를 감쌈).

- **첫 호출:** `s`에 문자열을 전달하고, `saveptr`에는 NULL이 아닌
  임의의 `char**`를 전달한다.
- **이후 호출:** `s`에 NULL을 전달한다.
- 다음 토큰에 대한 포인터를 반환하거나, 모두 소진되면 NULL을 반환한다.
- 입력 문자열은 실제로 수정된다(구분자 위치에 NUL 종료 문자가 기록됨).

## 메모리 검색 {#memory-search}

### mem_chr {#mem_chr}

```c
void* mem_chr(const void* s, int c, unsigned long n);
```

`s`가 가리키는 `n`바이트 블록 안에서 바이트 `c`가 처음 나타나는
위치에 대한 포인터를 반환하며, 없으면 NULL을 반환한다.

## 할당 및 복제 {#allocation-duplication}

### str_dup {#str_dup}

```c
char* str_dup(const char* s);
```

`s`의 힙에 할당된 NUL로 끝나는 복사본을 반환한다. 호출자는 이를
해제하기 위해 `dealloc()`을 호출해야 한다.

### str_ndup {#str_ndup}

```c
char* str_ndup(const char* s, unsigned long n);
```

`s`의 처음 `n`바이트에 대한 힙에 할당된 복사본을 NUL로 종료하여
반환한다.

## 예제 {#example}

```c
#include "str.h"
#include "io.h"
#include "mem.h"

int main() {
    const char* greeting = "Hello, SafeC!";

    // 길이
    unsigned long len = str_len(greeting);
    print("Length: ");
    println_int(len);   // 13

    // 검색
    const char* pos = str_find(greeting, "SafeC");
    if (pos != 0) {
        print("Found at offset: ");
        println_int(pos - greeting);  // 7
    }

    // 복제
    char* copy = str_dup(greeting);
    println(copy);
    dealloc(copy);

    // 토큰화
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
