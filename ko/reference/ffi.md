# C 상호운용 (FFI)

SafeC는 C와 매끄럽게 상호운용되도록 설계되었습니다. 리전 한정 참조는 코드 생성 시 원시 포인터로 소거되므로, 바이너리 수준에서 SafeC와 C 사이에 ABI 차이가 없습니다.

## Extern 선언 {#extern-declarations}

C 함수는 원시 C 타입을 사용해 `extern`으로 선언합니다. extern 시그니처에는 리전 한정자가 나타나서는 안 됩니다:

```c
extern int printf(const char *fmt, ...);
extern void *malloc(long size);
extern void free(void *ptr);
extern int open(const char *path, int flags);
```

## 네이티브 C 헤더 임포트 {#native-c-header-import}

SafeC는 표준 C 헤더를 직접 임포트할 수 있습니다. 컴파일러는 내부적으로 `clang -ast-dump=json`을 호출해 함수와 typedef 선언을 추출합니다:

```c
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main() {
    ?&char buf = malloc(256);              // raw void* -> ?&char: implicit
    match (buf) {
        case some(b): {
            sprintf(b, "Hello, %s!", "world");   // &char -> char*: implicit
            printf("%s\n", b);
        }
        case null: { }
    }
    free(buf);
    return 0;
}
```

`CHeaderImporter`가 추출하는 것:
- `FunctionDecl` 노드 (함수 프로토타입)
- `TypedefDecl` 노드 (타입 별칭)

SafeC가 지원하지 않는 구성 요소는 자동으로 건너뜁니다:
- typedef 안의 함수 포인터, 그리고 함수 포인터 매개변수가 있는 함수(그런 함수는 직접 `extern`으로 선언)
- Objective-C 블록 타입 (`^`)
- 배열 typedef
- `long double`, `wchar_t`, `__int128`

열거형 typedef는 `typedef int name;`으로 변환됩니다.

네이티브 헤더 임포트를 끄려면 `--no-import-c-headers`를 넘기십시오.

## 참조를 원시 포인터로 {#references-to-raw-pointers}

모든 참조는 리전과 관계없이 — `&static`, `&stack`, `&heap`, `&arena<R>`, 또는 리전 없는 `&T`/`?&T` — 대응하는 원시 포인터(`&T`에서 `T*`)로 암묵적으로 변환됩니다. 리전은 데이터가 어디에 사는지에 대한 컴파일 타임 사실일 뿐이며, C 경계에서는 그냥 소거됩니다. 따라서 C 호출을 감싸는 SafeC 함수는 참조를 받아 그대로 `extern`에 넘깁니다:

```c
extern void process(int *data);
region Scratch { capacity: 1024 }

void example() {
    int buf[100];
    process(&buf[0]);                       // &stack int -> int*
    process(buf);                           // array decays to its first element
    &heap int h = new int;
    process(h);                             // &heap int -> int*
    &arena<Scratch> int a = new<Scratch> int;
    process(a);                             // &arena int -> int*
}
```

타입 없는 `void*`로의 변환도 `&stack T`를 제외한 모든 참조에서 같은 방식으로 동작합니다: `void*`로 C에 넘겨진 스택 주소는 타입과 수명을 모두 잃으므로 컴파일러가 거부합니다. 매개변수를 리전 없는 `&T`(모든 `&stack T`가 변환됨)로 받거나 — 래퍼라면 보통 이 형태입니다 — 힙, 아레나, 정적 참조를 넘기십시오.

컴파일러가 검사할 수 없는 것은 C가 그 포인터를 이후에 어떻게 쓰느냐입니다. C 쪽이 호출 이후에도 포인터를 보관한다면 — 등록된 콜백 컨텍스트, 불투명 핸들 — 그 사용보다 오래 사는 데이터(`&static`, 힙, 그 사이에 리셋되지 않는 아레나)를 넘기십시오.

### `&static T` {#static-t}

정적 데이터는 프로그램 전체 동안 살아 있으므로, C가 보관하더라도 항상 안전하게 넘길 수 있습니다:

```c
extern int puts(const char *s);

void greet() {
    static const &char greeting = "Hello from SafeC";
    puts(greeting);             // OK: &static → raw pointer is safe
}
```

::: warning `static const &char` 초기화는 전역이 아니라 지역이어야 합니다
[메모리와 리전](/ko/reference/memory)의 같은 패턴과 마찬가지로, 파일 스코프의
`static const &char greeting = "...";`는 컴파일되지 않습니다
("global initializer is not a compile-time constant expression"). 위처럼
함수 안의 지역 `static`으로 선언하십시오.
:::

### `&T` / `?&T` (리전 없음) {#t-t-no-region}

리전 한정자가 **전혀 없는** 참조 — `&T`(널 불가)나 `?&T`(널 가능) — 는
`extern` 경계를 넘기 위한 포인터에 자연스러운 타입이며, 특히 C 쪽이 호출이
반환된 뒤에도 그것을 *보관할* 수 있을 때 그렇습니다 — 전체적인 근거는
[메모리와 리전](/ko/reference/memory)의 "Outliving References" 절을 참고하십시오.

```c
struct Widget { int value; }

// Stand-in for 'extern int c_register_callback(struct Widget*);' — a SafeC
// function with the same shape takes the reference, C would get the address.
int c_register_callback(?&Widget w) {
    match (w) {
        case some(x): return x.value;
        case null:    return 0;
    }
    return 0;
}

int main() {
    struct Widget local;
    local.value = 42;

    &stack Widget sref = &local;
    ?&Widget wref = sref;         // &stack Widget -> ?&Widget: implicit, no unsafe
    c_register_callback(wref);    // ?&Widget -> struct Widget*: implicit, no unsafe
    return 0;
}
```

`?&T` 값을 다시 읽는 것은 `?&stack T`/`?&heap T` 등과 같은 널 가능 참조
문법 — `match`, `is_null()`, `.default(fallback)`, 또는 `unsafe`로 제한된
`!` — 을 따릅니다. 그 문법은 [안전성](/ko/reference/safety)을 참고하십시오.

## C에서 온 원시 포인터 {#raw-pointers-from-c}

C에서 돌아온 원시 포인터는 널 가능 참조 `?&T`로 암묵적으로 변환되며 — `void*`를 포함해 어떤 대상 타입이든 — 다른 `?&T`처럼 `match`로 좁혀 씁니다. 널이 아닌 `&T`로 바로 바꾸려면 `unsafe`가 필요합니다. C가 널을 반환하지 않았다는 것은 프로그래머만 알기 때문입니다.

C 진입점마다 한 번씩 감싸서, 프로그램의 나머지 부분은 참조만 보게 하십시오:

```c
extern void *malloc(long size);
extern void free(void *ptr);

// The one place this program turns C's untyped pointer into a typed array.
?&heap int alloc_ints(unsigned long n) {
    unsafe { return (?&heap int)malloc((long)(n * sizeof(int))); }
}

void example() {
    ?&heap int data = alloc_ints(10UL);
    match (data) {
        case some(d): { *d = 42; }          // first element
        case null:    { }
    }
    free(data);                             // ?&heap int -> void*: implicit
}
```

## C로 콜백 전달하기 {#passing-callbacks-to-c}

SafeC 함수는 C 호환 호출 규약을 가지므로, 함수 포인터 콜백을 받는 C 라이브러리와 자연스럽게 동작합니다:

```c
extern void qsort(void *base, long nmemb, long size,
                   fn int(const void*, const void*) compar);

// C calls this with two element addresses; the parameters are references,
// so the body reads them without unsafe.
int compare_ints(const &int a, const &int b) {
    return *a - *b;
}

void sort_array(&int first, long n) {
    qsort(first, n, (long)sizeof(int), compare_ints);
}
```

## 구조체 레이아웃 호환성 {#struct-layout-compatibility}

SafeC 구조체는 기본적으로 C 호환 레이아웃을 사용합니다. 따라서 SafeC 구조체를 C 함수에 넘기고 C 구조체를 받을 때 아무런 변환이 필요 없습니다:

```c
struct Point {
    double x;
    double y;
};

extern void draw_point(Point *p);

void example() {
    Point p = {1.0, 2.0};
    unsafe {
        draw_point(&p);
    }
}
```

## 제로 코스트 추상화 {#zero-cost-abstraction}

리전 한정자는 컴파일 타임 전용 개념입니다. LLVM IR 수준에서는:
- `&stack int`는 그냥 `i32*`
- `&heap float`는 그냥 `float*`
- `&arena<R> T`는 그냥 `T*`
- `&T`/`?&T`(리전 없음)도 그냥 `T*` — 운반할 리전 메타데이터가 애초에 없으므로 원시 포인터와 동일한 코드가 생성됩니다

참조에는 해당하는 경우 `nonnull`과 `noalias` LLVM 속성이 붙어 더 나은 최적화를 가능하게 하지만, 런타임 메타데이터나 팻 포인터 오버헤드는 없습니다.

## FFI 규칙 요약 {#ffi-rules-summary}

| 상황 | `unsafe` 필요? |
|----------|-------------------|
| `&static T`를 C로 전달 | 아니오 |
| `&T` / `?&T`(리전 없음)를 C로 전달 | 아니오 |
| `&stack T`를 `T*`로 C에 전달 | 아니오 |
| `&stack T`를 `void*`로 C에 전달 | 허용 안 됨 — 리전 없는 `&T`를 거칠 것 |
| `&heap T`를 C로 전달 | 아니오 |
| `&arena<R> T`를 C로 전달 | 아니오 |
| C에서 온 원시 포인터를 `?&T`로 | 아니오 |
| C에서 온 원시 포인터를 널 불가 `&T`로 | 예 |
| 원시 포인터 역참조, 인덱싱, 산술 | 예 |
| C 함수 호출 (참조 없음) | 아니오 |
| 구조체를 값으로 C에 전달 | 아니오 |
