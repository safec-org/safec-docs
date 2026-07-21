# C 상호운용 (FFI)

SafeC는 C와의 매끄러운 상호운용을 위해 설계되었다. 리전 한정 참조는
코드 생성 시점에 원시 포인터로 소거되므로, 바이너리 수준에서 SafeC와
C 사이에 ABI 차이가 없다.

## Extern 선언 {#extern-declarations}

C 함수는 원시 C 타입을 사용해 `extern`으로 선언한다. 리전 한정자는
extern 시그니처에 나타나서는 안 된다:

```c
extern int printf(const char *fmt, ...);
extern void *malloc(long size);
extern void free(void *ptr);
extern int open(const char *path, int flags);
```

## 네이티브 C 헤더 임포트 {#native-c-header-import}

SafeC는 표준 C 헤더를 직접 임포트할 수 있다. 컴파일러는 내부적으로
`clang -ast-dump=json`을 호출해 함수와 typedef 선언을 추출한다:

```c
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main() {
    char *buf = (char*)malloc(256);
    sprintf(buf, "Hello, %s!", "world");
    printf("%s\n", buf);
    free(buf);
    return 0;
}
```

`CHeaderImporter`는 다음을 추출한다:
- `FunctionDecl` 노드 (함수 프로토타입)
- `TypedefDecl` 노드 (타입 별칭)

이는 SafeC가 지원하지 않는 구성을 자동으로 건너뛴다:
- typedef 안의 함수 포인터
- Objective-C 블록 타입 (`^`)
- 배열 typedef
- `long double`, `wchar_t`, `__int128`

열거형 typedef는 `typedef int name;`으로 변환된다.

네이티브 헤더 임포트를 비활성화하려면 `--no-import-c-headers`를
전달한다.

## 안전한 강제 변환: `&static T` → `T*` {#safe-coercion-static-t-to-t}

`static` 리전을 가진 참조는 `unsafe` 블록 없이 C 함수로 전달할 수
있다. static 데이터는 프로그램 전체 수명 동안 존재하며 절대 무효가
되지 않기 때문이다:

```c
extern int puts(const char *s);

void greet() {
    static const char *greeting = "Hello from SafeC";
    puts(greeting);             // OK: &static → 원시 포인터로의 변환은 안전함
}
```

이 강제 변환은 `unsafe` 밖에서 허용되는 두 가지 암묵적인 리전-포인터
변환 중 하나다 — 나머지 하나는 `&T`/`?&T`(리전 한정자 없음)이며,
바로 다음에 다룬다.

::: warning `static const char*` 초기화자는 반드시 지역이어야 하며 전역이면 안 됨
[메모리와 리전](/ko/reference/memory)에서와 동일한 패턴대로, 파일
스코프의 `static const char *greeting = "...";`는 컴파일에 실패한다
("전역 초기화자는 컴파일 타임 상수 표현식이 아닙니다"). 위 예시처럼
함수 내부의 지역 `static`으로 선언해야 한다.
:::

## 안전한 강제 변환: `&T` / `?&T`(리전 없음) → `T*` {#safe-coercion-t-t-no-region-to-t}

**리전 한정자가 전혀 없는** 참조 — `&T`(널이 될 수 없음) 또는
`?&T`(널이 될 수 있음) — 도 양방향 모두에서 `unsafe` 없이 원시
포인터로 변환된다. 이는 애초에 `extern` 경계를 넘나들도록 의도된
포인터, 특히 C 쪽이 호출이 반환된 뒤에도 그 포인터를 *계속 보유*할
수 있는 경우(등록된 콜백의 컨텍스트, 불투명한 핸들)에 자연스러운
타입이다 — 자세한 근거는 [메모리와 리전](/ko/reference/memory)의
"참조가 스코프보다 오래 살아남는 경우" 절을 참고.

```c
struct Widget { int value; }

// 개념적으로 이 호출이 반환된 뒤에도 'w'를 계속 보유함 -- 실제 extern
// 선언이라면 그냥 'extern int c_register_callback(struct Widget*);'가 될 것
int c_register_callback(struct Widget* w) {
    unsafe { return w->value; }
}

int main() {
    struct Widget local;
    local.value = 42;

    &stack Widget sref = &local;
    ?&Widget wref = sref;         // &stack Widget -> ?&Widget: 암묵적, unsafe 불필요
    c_register_callback(wref);    // ?&Widget -> struct Widget*: 암묵적, unsafe 불필요
    return 0;
}
```

`?&T` 값을 다시 읽어내는 것은 `?&stack T`/`?&heap T` 등과 동일한
널 가능 참조 문법을 거친다 — `match`, `is_null()`, `.default(fallback)`,
또는 `unsafe`로 감싼 `!`. 이 문법에 대해서는 [안전성](/ko/reference/safety)을
참고.

## Static이 아닌 참조는 Unsafe가 필요함 {#non-static-references-require-unsafe}

스택, 힙, 아레나 참조를 C 함수에 전달하려면 `unsafe` 블록이 필요하다.
컴파일러가 C가 참조의 수명을 지킬지 검증할 수 없기 때문이다:

```c
extern void process(int *data);

void example() {
    int buf[100];

    // process(buf);            // 에러: static이 아닌 참조를 C로 전달

    unsafe {
        process(buf);           // OK: 프로그래머가 책임을 짐
    }
}
```

## C에서 온 원시 포인터는 Unsafe가 필요함 {#raw-pointers-from-c-require-unsafe}

C 함수로부터 받은 포인터는 원시이며 추적되지 않는다. 반드시 `unsafe`
안에서 다뤄야 한다:

```c
extern void *malloc(long size);
extern void free(void *ptr);

void example() {
    unsafe {
        int *data = (int*)malloc(10UL * sizeof(int));  // UL: int -> long 암묵적 확장이 없기 때문
        data[0] = 42;
        free(data);
    }
}
```

## C로 콜백 전달하기 {#passing-callbacks-to-c}

함수 포인터 콜백을 받는 C 라이브러리는 자연스럽게 동작한다. SafeC
함수가 C 호환 호출 규약을 가지기 때문이다:

```c
extern void qsort(void *base, long nmemb, long size,
                   fn int(const void*, const void*) compar);

int compare_ints(const void *a, const void *b) {
    int ia;
    int ib;
    unsafe {
        ia = *(int*)a;          // 원시 포인터 역참조에는 unsafe가 필요함
        ib = *(int*)b;
    }
    return ia - ib;
}

void sort_array(int *arr, int n) {
    unsafe {
        qsort(arr, n, sizeof(int), compare_ints);
    }
}
```

## 구조체 레이아웃 호환성 {#struct-layout-compatibility}

SafeC 구조체는 기본적으로 C 호환 레이아웃을 사용한다. 즉 별도의
변환 없이 SafeC 구조체를 C 함수로 전달하거나 C 구조체를 받을 수
있다:

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

리전 한정자는 컴파일 타임에만 존재하는 개념이다. LLVM IR 수준에서는:
- `&stack int`는 그냥 `i32*`
- `&heap float`는 그냥 `float*`
- `&arena<R> T`는 그냥 `T*`
- `&T`/`?&T`(리전 없음)도 그냥 `T*` — 애초에 담을 리전 메타데이터가
  없으므로 원시 포인터와 코드 생성이 동일함

참조는 해당되는 경우 LLVM의 `nonnull`과 `noalias` 어트리뷰트를 가지므로
더 나은 최적화가 가능하지만, 런타임 메타데이터나 팻 포인터(fat pointer)
오버헤드는 전혀 없다.

## FFI 규칙 요약 {#ffi-rules-summary}

| 시나리오 | `unsafe` 필요? |
|----------|-------------------|
| `&static T`를 C로 전달 | 아니요 |
| `&T` / `?&T`(리전 없음)를 C로 전달 | 아니요 |
| `&stack T`를 C로 전달 | 예 |
| `&heap T`를 C로 전달 | 예 |
| `&arena<R> T`를 C로 전달 | 예 |
| C에서 온 원시 포인터 | 예 |
| C에서 온 원시 포인터를 `&T`/`?&T`로 | 아니요 |
| C 함수 호출 (참조 없음) | 아니요 |
| 구조체를 C에 값으로 전달 | 아니요 |
