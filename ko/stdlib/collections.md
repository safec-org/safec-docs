# 컬렉션

SafeC는 `std/collections/`에 열한 개의 컬렉션 모듈을 제공합니다. 이들은 SafeC의 제네릭 구조체 지원([제네릭](/ko/reference/generics#generic-structs-and-methods) 참고)보다 앞서 만들어졌고 아직 그쪽으로 마이그레이션되지 않았습니다: 구조체 자체는 여전히 원소 저장을 위해 내부적으로 `void*`/원시 포인터 필드를 사용하며, 그 위에 타입 안전한 접근을 위한 `generic<T>` 래퍼 *함수*들을 얹어 놓았고, (그중 몇몇은) 타입 소거된 연산을 위한 실제 비제네릭 인스턴스 *메서드*(`v.length()`, `v.push(const void* elem)`, `m.get(const void* key)` 등)도 갖고 있습니다. `T`는 호출 지점에서 `T` 타입의 인자로부터 단형화(monomorphization)를 통해 추론되거나 — `T`가 오직 *반환* 타입에만 나타나는 몇몇 래퍼의 경우에는 — 호출 지점의 명시적인 목표 타입으로부터 추론됩니다(아래 박스 참고). SafeC는 아직 `foo<int>(...)` 형태의 명시적 타입 인자 문법을 지원하지 않습니다; 추론은 항상 암묵적입니다.

이 설계는 (`T`마다 코드가 불어나지 않도록) 컬렉션 타입마다 컴파일된 구조체를 하나만 유지하면서도, 모든 호출 지점에서 완전한 타입 안전성을 보존합니다 — 바이너리 크기가 중요한 임베디드 타겟에서 중요한 특성입니다.

`ringbuffer`는 예외입니다: `void*`가 아니라 `&stack`/`&static` 리전 애너테이션을 사용하며, `unsigned char` 스트림 위에서 직접 동작하는 바이트 지향 모듈입니다.

::: tip `T*`를 반환하는 제네릭 래퍼는 추론을 위해 타입이 지정된 대상이 필요합니다
`vec_at`, `map_get_t`, `btree_get`, `bst_get_t`, `stack_peek_t`, `queue_front_t`, `list_front_t`, `list_back_t`는 모두 한 가지 형태를 공유합니다: `generic<T> T* the_fn(..., T가-아닌-타입의-인자들...)` — 타입 매개변수 `T`는 오직 반환 타입에만 나타납니다. 추론은 여전히 호출 지점에서만 이루어지지만, 대비책으로 반환 타입을 호출 지점의 *기대되는* 타입과도 대조합니다 — 명시적으로 선언된 변수 타입(`int* p = vec_at(&v, 1UL);`)이나 대입 시 기존의 타입이 지정된 변수의 타입(`p = vec_at(&v, 1UL);`)이 그것입니다 — 그래서 이 두 위치 중 하나에 직접 호출을 작성하는 것이 동작하며, 이 페이지 전체에서 사용되는 관용적인 형태입니다. 대상이 전혀 없는 경우(맨 `vec_at(&v, 1UL);` 문장, 또는 호출을 다른 함수의 인자로 바로 전달하는 경우)에는 동작하지 **않습니다** — 그런 경우에는 타입 소거된 메서드/함수를 사용하고 캐스트하세요, 예: `(int*)v.get_raw(idx)`.
:::

## slice — 경계 검사가 되는 배열 접근 {#slice-bounds-checked-array-access}

```c
#include "collections/slice.h"
```

`Slice`는 타입이 지정된 포인터 + 길이를 감싸, 경계 검사가 되는 접근을 제공합니다. 구조체는 `void*`와 `elem_size`를 저장하며 실제 접근을 위한 인스턴스 메서드를 갖고, `generic<T>` 생성자와 몇몇 `generic<T>` 자유 함수가 타입 안전한 생성 및 배열 전체 연산을 제공합니다.

### 구조체와 메서드 {#struct-and-methods}

```c
struct Slice {
    void*         ptr;        // 첫 원소를 가리키는 포인터
    unsigned long len;        // 원소 개수
    unsigned long elem_size;  // 스트라이드(바이트 단위)

    int           in_bounds(unsigned long idx) const;
    void*         get_raw(unsigned long idx) const;    // OOB이면 NULL
    int           set_raw(unsigned long idx, const void* val);
    struct Slice  sub(unsigned long start, unsigned long end) const;
    unsigned long length() const;
    int           is_empty() const;
    void          free();
};
```

### 생성 {#construction}

```c
struct Slice  slice_void_from(void* ptr, unsigned long len, unsigned long elem_size);
struct Slice  slice_void_alloc(unsigned long len, unsigned long elem_size);

generic<T> struct Slice slice_of(&stack T ptr, unsigned long len);
```

`slice_of<T>`는 *첫* 원소에 대한 `&stack T` 참조를 받습니다(`&arr[0]`이지, 배열 자체가 아닙니다) — `T`는 여기서 추론됩니다.

### 제네릭 배열 함수 {#generic-array-functions}

이 함수들은 명시적인 길이 매개변수를 받는 원시 `T*` 배열 위에서 동작합니다 — 실제 `T*` 변수를 전달하세요(배열 이름 자체만으로는 추론되지 않으니, 먼저 포인터 변수에 대입하세요):

```c
generic<T> T*   arr_at(T* ptr, unsigned long len, unsigned long idx);   // OOB이면 NULL
generic<T> void arr_set(T* ptr, unsigned long len, unsigned long idx, T val);
generic<T> T    arr_get(T* ptr, unsigned long idx);
generic<T> void arr_fill(T* ptr, unsigned long len, T val);
generic<T> void arr_copy(T* dst, T* src, unsigned long len);
generic<T> T    arr_min(T* ptr, unsigned long len);
generic<T> T    arr_max(T* ptr, unsigned long len);
generic<T> void arr_reverse(T* ptr, unsigned long len);
```

### 예제 {#example}

실제 컴파일/실행으로 검증됨:

```c
#include <std/collections/slice.sc>

int main() {
    int data[5];
    data[0] = 10; data[1] = 20; data[2] = 30; data[3] = 40; data[4] = 50;
    int* dp = data;   // arr_*는 실제 T*를 원한다, 배열 자체가 아니라

    struct Slice s = std::slice_of(&data[0], 5UL);
    printf("len=%lu\n", s.length());              // 5
    printf("in_bounds(10)=%d\n", s.in_bounds(10UL)); // 0

    unsafe {
        int* p = (int*)s.get_raw(2UL);
        printf("s[2]=%d\n", *p);                   // 30
    }

    int* q = std::arr_at(dp, 5UL, 3UL);
    unsafe { printf("arr_at(3)=%d\n", *q); }        // 40

    std::arr_reverse(dp, 5UL);
    printf("data[0]=%d\n", data[0]);                // 50
    return 0;
}
```

---

## vec — 동적 배열 {#vec-dynamic-array}

```c
#include "collections/vec.h"
```

자동으로 커지는 동적 배열입니다. push/pop은 O(1) 상각입니다. `data`는 `&heap void`입니다.

### 구조체와 메서드 {#struct-and-methods-1}

```c
struct Vec {
    &heap void    data;
    unsigned long len;
    unsigned long cap;
    unsigned long elem_size;

    // 용량
    int           reserve(unsigned long new_cap);
    void          shrink();
    unsigned long length() const;
    unsigned long total_capacity() const;
    int           is_empty() const;

    // 원소 접근 (타입 소거됨)
    &heap void    get_raw(unsigned long idx);   // OOB이면 NULL
    int           set_raw(unsigned long idx, const void* elem);
    &heap void    front_raw();
    &heap void    back_raw();

    // 변경
    int           push(const void* elem);
    int           pop(void* out);
    int           insert(unsigned long idx, const void* elem);
    int           remove(unsigned long idx, void* out);
    void          clear();
    int           extend(const void* arr, unsigned long count);

    // 알고리즘
    void          reverse();
    void          sort(void* cmp);                          // cmp: int(*)(const void*, const void*)
    long long     find(const void* key, void* cmp) const;    // 못 찾으면 -1
    int           contains(const void* key, void* cmp) const;
    struct Vec    clone() const;
    void          foreach(void* func);                       // func: void(*)(void* elem, unsigned long idx)
    struct Vec    filter(void* pred) const;                  // pred: int(*)(const void*)
    struct Vec    map_raw(unsigned long out_elem_size, void* func) const;

    void          free();
};
```

### 생성자 {#constructors}

```c
struct Vec vec_new(unsigned long elem_size);
struct Vec vec_with_cap(unsigned long elem_size, unsigned long cap);
```

### 제네릭 래퍼 {#generic-wrappers}

```c
generic<T> int       vec_push_t(&stack Vec v, T val);
generic<T> T*        vec_at(&stack Vec v, unsigned long idx);      // 위의 팁 참고 -- 타입이 지정된 대상이 필요
generic<T> int       vec_pop_t(&stack Vec v, T* out);
generic<T> struct Vec vec_from_arr(T* arr, unsigned long len);
```

`vec_pop_t`의 `out` 매개변수는 실제 `T*`(`&stack T` 참조가 아니라 원시 포인터)를 원합니다 — 예제처럼 `unsafe` 캐스트로 얻으세요.

### 예제 {#example-1}

실제 컴파일/실행으로 검증됨:

```c
#include <std/mem.sc>
#include <std/collections/vec.sc>

int main() {
    struct Vec v = std::vec_new(sizeof(int));

    std::vec_push_t(&v, 10);
    std::vec_push_t(&v, 20);
    std::vec_push_t(&v, 30);

    int* p = std::vec_at(&v, 1UL);   // 'int*' 대상으로부터 T=int가 추론됨
    unsafe { printf("v[1]=%d\n", *p); }   // 20

    int last = 0;
    int* lastp;
    unsafe { lastp = (int*)&last; }
    std::vec_pop_t(&v, lastp);
    printf("popped=%d\n", last);   // 30

    printf("len=%lu\n", v.length());   // 2

    v.free();
    return 0;
}
```

---

## string — 가변 문자열 {#string-mutable-string}

```c
#include "collections/string.h"
```

성장 가능하고, 힙에 할당되며, NUL로 끝나는 바이트 문자열로 40개 이상의 메서드를 갖습니다. `data`는 `&heap char`입니다. 생성만 자유 함수이고 — 나머지는 전부 메서드입니다.

### 구조체와 메서드 {#struct-and-methods-2}

```c
struct String {
    &heap char    data;
    unsigned long len;
    unsigned long cap;

    // 접근
    unsigned long  length() const;
    int            is_empty() const;
    const char*    as_ptr() const;
    int            char_at(unsigned long idx) const;         // OOB이면 -1
    void           set_char(unsigned long idx, char c);

    // 용량
    int            reserve(unsigned long additional);
    void           shrink_to_fit();

    // 추가
    int            push_char(char c);
    int            push(const char* cstr);
    int            push_n(const char* data, unsigned long n);  // 원시 바이트 n개, NUL로 끝날 필요 없음
    int            push_str(&stack String other);
    int            push_int(long long v);
    int            push_uint(unsigned long long v);
    int            push_float(double v, int decimals);
    int            push_bool(int v);

    // 수정
    void           clear();
    void           truncate(unsigned long new_len);
    int            insert(unsigned long idx, const char* cstr);
    int            remove_range(unsigned long start, unsigned long end);
    void           replace_char(char from, char to);
    int            replace(const char* from, const char* to);       // 첫 번째 발생만
    int            replace_all(const char* from, const char* to);
    void           reverse();                                       // 제자리 바이트 역순
    int            pop_char();                                      // 비어 있으면 -1

    // 검색
    long long      index_of(const char* needle) const;
    long long      last_index_of(const char* needle) const;
    int            contains(const char* needle) const;
    int            starts_with(const char* prefix) const;
    int            ends_with(const char* suffix) const;
    int            count(const char* needle) const;
    long long      find_char(char c) const;
    long long      rfind_char(char c) const;

    // 변환 (새 String을 반환)
    struct String  substr(unsigned long start, unsigned long end) const;
    struct String  to_upper() const;
    struct String  to_lower() const;
    struct String  trim() const;
    struct String  trim_left() const;
    struct String  trim_right() const;
    struct String  pad_left(unsigned long width, char fill) const;
    struct String  pad_right(unsigned long width, char fill) const;
    struct String  strip_prefix(const char* prefix) const;
    struct String  strip_suffix(const char* suffix) const;
    struct String  repeat(unsigned long n) const;
    struct String  capitalize() const;

    // 분할 -- 호출자가 제공한 'out' 배열(최대 max개 슬롯)에 기록; 기록된
    // 항목 수를 반환; 넘치면 나머지 전부를 마지막 슬롯에 넣음
    unsigned long  split(const char* delim, &stack String out, unsigned long max) const;
    unsigned long  split_lines(&stack String out, unsigned long max) const;
    unsigned long  split_whitespace(&stack String out, unsigned long max) const;

    // 비교
    int            eq(&stack String other) const;
    int            eq_cstr(const char* other) const;
    int            cmp(&stack String other) const;         // <0, 0, >0
    int            lt(&stack String other) const;
    int            gt(&stack String other) const;
    int            eq_ignore_case(&stack String other) const;
    int            eq_cstr_ignore_case(const char* other) const;

    // 질의
    int            is_ascii() const;
    int            is_numeric() const;
    int            is_alphanumeric() const;

    // 변환
    long long      parse_int(int* ok) const;
    double         parse_float(int* ok) const;

    struct String  clone() const;
    void           free();
};
```

### 생성자 {#constructors-1}

```c
struct String string_new();
struct String string_from(const char* s);
struct String string_from_n(const char* s, unsigned long n);
struct String string_with_cap(unsigned long cap);
struct String string_repeat(const char* s, unsigned long n);
struct String string_join(const char* sep, &stack String parts, unsigned long count);
```

### 예제 {#example-2}

실제 컴파일/실행으로 검증됨:

```c
#include <std/mem.sc>
#include <std/str.sc>
#include <std/convert.sc>
#include <std/collections/string.sc>

int main() {
    struct String s = std::string_from("Hello");
    s.push(", SafeC!");
    printf("%s\n", s.as_ptr());              // Hello, SafeC!
    printf("contains=%d\n", s.contains("SafeC"));  // 1

    struct String upper = s.to_upper();
    printf("%s\n", upper.as_ptr());          // HELLO, SAFEC!

    struct String num = std::string_new();
    num.push("value = ");
    num.push_int(42LL);
    printf("%s\n", num.as_ptr());            // value = 42

    num.free();
    upper.free();
    s.free();
    return 0;
}
```

---

## stack — LIFO 스택 {#stack-lifo-stack}

```c
#include "collections/stack.h"
```

성장 가능한 배열로 뒷받침되는 후입선출(LIFO) 스택입니다. push/pop은 O(1) 상각입니다. 자유 함수 API입니다 — 이 모듈은 아직 구조체 메서드로 옮겨지지 않았습니다. `s`는 리전이 없는(region-less) `&Stack` 참조입니다(원시 포인터가 아닙니다) — 호출 지점에서는 이전과 정확히 동일하게 `&s`를 전달하며, 이제는 스택-로컬, 정적/전역, 힙-소유 `Stack` 중 호출자가 가진 어느 것이든 상관없이 받아들입니다.

### 구조체 {#struct}

```c
struct Stack {
    void*         data;
    unsigned long top;        // 원소 개수
    unsigned long cap;
    unsigned long elem_size;
};
```

### API {#api}

```c
// 라이프사이클
struct Stack stack_new(unsigned long elem_size);
struct Stack stack_with_cap(unsigned long elem_size, unsigned long cap);
void         stack_free(&Stack s);

// 핵심 연산
int           stack_push(&Stack s, const void* elem);   // 성공 시 1
int           stack_pop(&Stack s, void* out);            // 비어 있으면 0
void*         stack_peek(&Stack s);                      // 비어 있으면 NULL
unsigned long stack_len(&Stack s);
int           stack_is_empty(&Stack s);
void          stack_clear(&Stack s);
```

### 제네릭 래퍼 {#generic-wrappers-1}

```c
generic<T> int stack_push_t(&Stack s, T val);
generic<T> T*  stack_peek_t(&Stack s);   // 위의 팁 참고 -- 타입이 지정된 대상이 필요
generic<T> int stack_pop_t(&Stack s, T* out);
```

### 예제 {#example-3}

```c
#include <std/collections/stack.sc>

int main() {
    struct Stack s = std::stack_new(sizeof(int));

    std::stack_push_t(&s, 10);
    std::stack_push_t(&s, 20);
    std::stack_push_t(&s, 30);

    int* top = std::stack_peek_t(&s);   // 'int*' 대상으로부터 T=int가 추론됨
    unsafe { printf("%d\n", *top); }    // 30

    int val = 0;
    int* valp;
    unsafe { valp = (int*)&val; }
    std::stack_pop_t(&s, valp);
    printf("%d\n", val);        // 30

    std::stack_free(&s);
    return 0;
}
```

---

## queue — FIFO 큐 {#queue-fifo-queue}

```c
#include "collections/queue.h"
```

선입선출(FIFO) 순환 버퍼 큐입니다. enqueue/dequeue는 상각 O(1)입니다. 가득 차면 자동으로 커집니다. 자유 함수 API입니다. `q`는 리전이 없는 `&Queue` 참조입니다 — 호출 지점은 그대로이며(`&q`), 이제는 어느 리전에 있는 `Queue`든 받아들입니다.

### 구조체 {#struct-1}

```c
struct Queue {
    void*         data;
    unsigned long head;       // 맨 앞 원소의 인덱스
    unsigned long tail;       // 다음 원소가 기록될 인덱스
    unsigned long len;
    unsigned long cap;
    unsigned long elem_size;
};
```

### API {#api-1}

```c
// 라이프사이클
struct Queue queue_new(unsigned long elem_size);
struct Queue queue_with_cap(unsigned long elem_size, unsigned long cap);
void         queue_free(&Queue q);

// 핵심 연산
int           queue_enqueue(&Queue q, const void* elem);
int           queue_dequeue(&Queue q, void* out);
void*         queue_front(&Queue q);    // 맨 앞을 엿봄; 비어 있으면 NULL
void*         queue_back(&Queue q);     // 맨 뒤를 엿봄; 비어 있으면 NULL
unsigned long queue_len(&Queue q);
int           queue_is_empty(&Queue q);
void          queue_clear(&Queue q);
```

### 제네릭 래퍼 {#generic-wrappers-2}

```c
generic<T> int queue_enqueue_t(&Queue q, T val);
generic<T> T*  queue_front_t(&Queue q);   // 위의 팁 참고 -- 타입이 지정된 대상이 필요
generic<T> int queue_dequeue_t(&Queue q, T* out);
```

### 예제 {#example-4}

```c
#include <std/collections/queue.sc>

int main() {
    struct Queue q = std::queue_new(sizeof(int));

    std::queue_enqueue_t(&q, 1);
    std::queue_enqueue_t(&q, 2);
    std::queue_enqueue_t(&q, 3);

    int val = 0;
    int* valp;
    unsafe { valp = (int*)&val; }
    std::queue_dequeue_t(&q, valp);
    printf("%d\n", val);   // 1 (FIFO 순서)

    int* front = std::queue_front_t(&q);   // 'int*' 대상으로부터 T=int가 추론됨
    unsafe {
        printf("%d\n", *front);   // 2
    }

    std::queue_free(&q);
    return 0;
}
```

---

## list — 이중 연결 리스트 {#list-doubly-linked-list}

```c
#include "collections/list.h"
```

양쪽 끝에서의 push/pop, 검색, 제거, 순서대로의 순회를 지원하는 이중 연결 리스트입니다. 자유 함수 API입니다. `l`은 리전이 없는 `&List` 참조입니다(호출 지점은 그대로 `&l`입니다). `next`/`prev`/`head`/`tail`은 원시 포인터가 아니라 `?&heap ListNode`(널 가능, 힙 소유)입니다 — *구현* 자체는 여전히 `unsafe {}` 안에서 평범한 원시 포인터 체이싱으로 이들을 순회합니다(원시 포인터와 `?&heap T` 필드는 그 안에서 캐스트 없이 서로 암묵적으로 변환됩니다), 하지만 헤더가 호출자에게 노출하는 모든 것은 null 검사가 됩니다.

### 구조체들 {#structs}

```c
struct ListNode {
    void*           data;
    ?&heap ListNode next;
    ?&heap ListNode prev;
};

struct List {
    ?&heap ListNode head;
    ?&heap ListNode tail;
    unsigned long   len;
    unsigned long   elem_size;
};
```

### API {#api-2}

```c
// 라이프사이클
struct List list_new(unsigned long elem_size);
void        list_free(&List l);

// Push / Pop
int           list_push_front(&List l, const void* elem);
int           list_push_back(&List l, const void* elem);
int           list_pop_front(&List l, void* out);
int           list_pop_back(&List l, void* out);
void*         list_front(&List l);      // 비어 있으면 NULL
void*         list_back(&List l);       // 비어 있으면 NULL
unsigned long list_len(&List l);
int           list_is_empty(&List l);
void          list_clear(&List l);

// 검색 & 순회
?&heap ListNode list_find(&List l, const void* val, void* cmp);   // 못 찾으면 빈(empty) 값; cmp: int(*)(const void*, const void*)
int             list_contains(&List l, const void* val, void* cmp);
void            list_remove_node(&List l, &heap ListNode node);   // node는 이미 리스트에 있어야 함
int             list_remove(&List l, const void* val, void* cmp);
void            list_foreach(&List l, void* fn);  // fn: void(*)(void* data)

// 순서 변경
void list_reverse(&List l);
```

### 제네릭 래퍼 {#generic-wrappers-3}

```c
generic<T> int list_push_front_t(&List l, T val);
generic<T> int list_push_back_t(&List l, T val);
generic<T> T*  list_front_t(&List l);   // 위의 팁 참고 -- 타입이 지정된 대상이 필요
generic<T> T*  list_back_t(&List l);    // 위의 팁 참고 -- 타입이 지정된 대상이 필요
```

### 예제 {#example-5}

실제 컴파일/실행으로 검증됨:

```c
#include <std/collections/list.sc>

int main() {
    struct List l = std::list_new(sizeof(int));

    std::list_push_back_t(&l, 10);
    std::list_push_back_t(&l, 20);
    std::list_push_front_t(&l, 5);

    int* front = std::list_front_t(&l);   // 'int*' 대상으로부터 T=int가 추론됨
    int* back = std::list_back_t(&l);
    unsafe {
        printf("%d\n", *front);   // 5
        printf("%d\n", *back);    // 20
    }

    std::list_reverse(&l);

    front = std::list_front_t(&l);
    unsafe {
        printf("%d\n", *front);   // 20
    }

    std::list_free(&l);
    return 0;
}
```

---

## map — 해시맵 {#map-hash-map}

```c
#include "collections/map.h"
```

선형 탐사(linear probing)와 djb2 해시 함수를 사용하는 오픈 어드레싱 해시맵입니다. 부하율(load factor) 임계값은 0.75이며, 자동으로 리사이즈됩니다. 키는 바이트 단위로(`memcmp`) 비교됩니다. C 문자열 키에는 `str_map_*` 변형을 사용하세요.

### 구조체와 메서드 {#struct-and-methods-3}

```c
struct MapEntry {
    void*        key;
    void*        val;
    unsigned int hash;
    int          state;  // 0=비어 있음, 1=사용 중, 2=툼스톤
};

struct HashMap {
    struct MapEntry* buckets;
    unsigned long    cap;         // 2의 거듭제곱이어야 함
    unsigned long    len;         // 살아 있는 항목 수
    unsigned long    tombstones;  // 제거되었지만 아직 회수되지 않은 슬롯 -- 제거가
                                   // 많은 워크로드에서 모든 탐사가 조용히 O(cap)으로
                                   // 저하되지 않도록 'len'과 함께 리사이즈 임계값에 반영됨
    unsigned long    key_size;
    unsigned long    val_size;

    int           insert(const void* key, const void* val);
    void*         get(const void* key) const;    // 없으면 NULL
    int           contains(const void* key) const;
    int           remove(const void* key);
    unsigned long length() const;
    int           is_empty() const;
    void          clear();
    void          foreach(void* func);   // func: void(*)(const void* key, void* val)

    void          free();
};
```

### 생성자 {#constructors-2}

```c
struct HashMap map_new(unsigned long key_size, unsigned long val_size);
struct HashMap map_with_cap(unsigned long key_size, unsigned long val_size, unsigned long cap);
```

### 문자열 키 편의 함수 {#string-key-convenience}

`const char*` 문자열을 키로 쓰는 맵을 위한 것으로 — 이들은 `free`를 포함해 여전히 자유 함수입니다. `m`은 리전이 없는 `&HashMap` 참조입니다(호출 지점은 그대로 `&m`):

```c
struct HashMap str_map_new(unsigned long val_size);
int   str_map_insert(&HashMap m, const char* key, const void* val);
void* str_map_get(&HashMap m, const char* key);
int   str_map_contains(&HashMap m, const char* key);
int   str_map_remove(&HashMap m, const char* key);
// str_map_free는 없음 -- str_map_new는 struct HashMap을 반환하므로,
// 다른 HashMap과 똑같은 방식으로 해제하면 됨: sm.free()
```

### 제네릭 래퍼 {#generic-wrappers-4}

```c
generic<T> int map_insert_t(&stack HashMap m, const void* key, T val);
generic<T> T*  map_get_t(&stack HashMap m, const void* key);   // 위의 팁 참고 -- 타입이 지정된 대상이 필요
```

### 예제 {#example-6}

실제 컴파일/실행으로 검증됨:

```c
#include <std/mem.sc>
#include <std/str.sc>
#include <std/collections/map.sc>

int main() {
    // 정수 키 맵
    struct HashMap m = std::map_new(sizeof(int), sizeof(int));
    int key = 42;
    std::map_insert_t(&m, &key, 100);

    unsafe {
        int* found = std::map_get_t(&m, (const void*)&key);   // 'int*' 대상으로부터 T=int가 추론됨
        if (found != (int*)0) {
            printf("%d\n", *found);   // 100
        }
    }
    m.free();

    // 문자열 키 맵
    struct HashMap sm = std::str_map_new(sizeof(double));
    double pi = 3.14159;
    unsafe { std::str_map_insert(&sm, "pi", (const void*)&pi); }

    unsafe {
        double* p = (double*)std::str_map_get(&sm, "pi");
        if (p != (double*)0) {
            printf("%f\n", *p);   // 3.14159
        }
    }
    sm.free();

    return 0;
}
```

---

## btree — 순서가 있는 B-트리 맵 {#btree-ordered-b-tree-map}

```c
#include "collections/btree.h"
```

**256노드 정적 풀**로 뒷받침되는 풀 기반 B-트리(차수 4)입니다 — 이를 초과하는 삽입은 더 늘어나지 않고 실패합니다. 삽입/조회는 O(log n)입니다. 모든 키는 `unsigned long`이고, 값은 `void*`입니다. 정렬된 순서대로의 순회를 제공합니다.

### 구조체와 메서드 {#struct-and-methods-4}

```c
#define BTREE_ORDER      4      // 루트가 아닌 노드당 최소 키 수
#define BTREE_MAX_KEYS   7      // 2*ORDER - 1
#define BTREE_MAX_CHILD  8      // 2*ORDER
#define BTREE_POOL_SIZE  256    // 정적 풀 안의 최대 노드 수

struct BTreeNode {
    unsigned long keys[BTREE_MAX_KEYS];
    void*         vals[BTREE_MAX_KEYS];
    unsigned long children[BTREE_MAX_CHILD];  // 노드 풀로의 인덱스, 0 = null
    int           n;      // 현재 키 개수
    int           leaf;   // 리프 노드면 1
};

struct BTree {
    struct BTreeNode pool[BTREE_POOL_SIZE];
    int              pool_used;
    unsigned long    root;    // 풀로의 인덱스, 0 = 빈 트리
    unsigned long    count;   // 전체 키-값 쌍 개수

    int           insert(unsigned long key, void* val);   // 성공 시 0, 풀이 가득 차면 -1
    void*         get(unsigned long key) const;           // 없으면 NULL
    int           remove(unsigned long key);               // 찾아서 제거했으면 1
    unsigned long len() const;
    int           contains(unsigned long key) const;
    void          foreach(void* cb, void* user) const;     // cb: void(*)(key, val, user), 오름차순
    void          clear();
};
```

```c
// 빈 트리를 제자리에서 초기화(또는 리셋)한다 -- 위의 세 스칼라 필드만
// 건드린다; 'pool'은 건드리지 않는다 (노드들은 'pool_used'가 커질 때마다
// 지연 획득되므로, 미리 0으로 채우는 것은 순전히 낭비다). 값 반환형
// 'btree_new()' 생성자가 아닌 이유는, 'pool' 때문에 BTree가 (~47 KB로)
// 너무 커서 값으로 반환하면 호출할 때마다 전체를 복사하게 되기 때문이다.
void btree_init(&stack BTree t);
```

### 제네릭 래퍼 {#generic-wrappers-5}

값은 포인터로 저장되며, 호출자가 그 대상(pointee)의 수명을 관리합니다.

```c
generic<T> int  btree_insert(&stack BTree t, unsigned long key, T* val);
generic<T> T*   btree_get(const &stack BTree t, unsigned long key);   // 위의 팁 참고 -- 타입이 지정된 대상이 필요
```

`btree_insert`의 `val`은 실제 `T*`를 원합니다 — 예제처럼 `unsafe` 캐스트로 얻으세요.

### 예제 {#example-7}

실제 컴파일/실행으로 검증됨:

```c
#include <std/collections/btree.sc>

void print_entry(unsigned long key, void* val, void* user) {
    unsafe { printf("%lu -> %d\n", key, *(int*)val); }
}

int main() {
    struct BTree t;
    std::btree_init(&t);

    int v10 = 100; int v20 = 200; int v5 = 50;
    int* p10;
    int* p20;
    int* p5;
    unsafe { p10 = (int*)&v10; p20 = (int*)&v20; p5 = (int*)&v5; }

    std::btree_insert(&t, 10UL, p10);
    std::btree_insert(&t, 20UL, p20);
    std::btree_insert(&t,  5UL, p5);

    int* found = std::btree_get(&t, 10UL);   // 'int*' 대상으로부터 T=int가 추론됨
    unsafe {
        printf("%d\n", *found);   // 100
    }

    // 순서대로 순회: 5, 10, 20
    t.foreach((void*)print_entry, (void*)0);
    return 0;
}
```

---

## ringbuffer — SPSC 락프리 링 버퍼 {#ringbuffer-spsc-lock-free-ring-buffer}

```c
#include "collections/ringbuffer.h"
```

단일 생산자/단일 소비자용 **바이트 지향** 2의 거듭제곱 링 버퍼입니다. OS 락 없이 올바른 생산자/소비자 순서를 보장하기 위해 `head`/`tail`에 원자적 로드/스토어를 사용합니다. ISR-투-태스크 데이터 전달과 오디오 파이프라인에 적합합니다.

다른 컬렉션 타입들과 달리, `RingBuffer`는 원소 기반이 아닙니다 — 원시 바이트 스트림 위에서 동작합니다. `unsigned char` 버퍼와 함께 사용하세요.

### 구조체 {#struct-2}

```c
struct RingBuffer {
    &static unsigned char buf;   // 뒷받침 저장소 — static 수명이어야 함
    unsigned long         cap;   // 용량(바이트 단위, 2의 거듭제곱이어야 함)
    unsigned long         mask;  // cap - 1  (빠른 모듈로 연산용)
    volatile unsigned long head; // 쓰기 위치 (생산자)
    volatile unsigned long tail; // 읽기 위치 (소비자)

    unsigned long readable() const;          // 읽을 수 있는 바이트 수
    unsigned long writable() const;          // 쓸 수 있는 바이트 수
    int           is_empty() const;
    int           is_full() const;

    unsigned long write(const &stack unsigned char data, unsigned long len);
    unsigned long read(&stack unsigned char out, unsigned long len);
    unsigned long peek(&stack unsigned char out, unsigned long len) const;
    unsigned long discard(unsigned long len);
    void          clear();
};

// 이미 존재하는 static 수명 뒷받침 저장소로 초기화한다.
// `cap`은 2의 거듭제곱이어야 한다.
struct RingBuffer ring_init(&static unsigned char buf, unsigned long cap);
```

### static 매크로 {#static-macro}

`RING_STATIC` 매크로는 힙 할당 없이 링 버퍼를 만드는 관용적인 방법입니다:

```c
// static 배열로 뒷받침되는 256바이트 링 버퍼를 선언 + 초기화
RING_STATIC(uart_rx, 256);

// 다음으로 확장됨:
//   static unsigned char uart_rx_storage_[256];
//   static struct RingBuffer uart_rx = { uart_rx_storage_, 256, 255, 0, 0 };
```

### 예제 {#example-8}

```c
#include "collections/ringbuffer.h"

// static 128바이트 버퍼 — 힙이 필요 없음
RING_STATIC(rb, 128);

int main() {
    unsigned char tx[] = {'H', 'e', 'l', 'l', 'o'};
    rb.write(tx, 5);

    printf("readable: %lu\n", rb.readable());  // 5

    unsigned char rx[5];
    rb.read(rx, 5);

    int i = 0;
    while (i < 5) { printf("%c", rx[i]); i = i + 1; }
    printf("\n");  // Hello

    return 0;
}
```

::: info
`buf`의 리전은 `&static unsigned char`입니다 — 뒷받침 저장소는 `RingBuffer` 구조체 자체보다 오래 살아남아야 합니다. 임베디드 전역 변수에는 `RING_STATIC`을 사용하세요. 힙 기반으로 사용하려면 `unsafe {}` 블록 안에서 캐스트하고 여러분 스스로 수명 관리 규율을 갖추세요.
:::

---

## static_collections — 힙을 쓰지 않는 컴파일 타임 컬렉션 {#static_collections-zero-heap-compile-time-collections}

```c
#include "collections/static_vec.h"
```

스택이나 static 전역 변수로 고정 용량 컬렉션을 선언하는 헤더 전용 매크로입니다. 힙 할당도, 함수 호출 오버헤드도 없습니다. 모든 원소 접근이 원시 포인터 필드 접근을 통해 이루어지므로, **데이터에 닿는 모든 매크로 호출은 감싸는 `unsafe {}` 블록이 필요합니다**.

### Static Vec {#static-vec}

```c
STATIC_VEC_DECL(MyVec, int, 32);   // 선언됨: struct MyVec { int data[32]; unsigned long len; unsigned long cap; }
MyVec v;
STATIC_VEC_INIT(&v, 32);           // 참고: 타입 이름이 아니라 포인터 + 용량 숫자

unsafe {
    STATIC_VEC_PUSH(&v, 42);       // (vec)->data[(vec)->len++] = val -- 성공 시 1, 가득 차면 0
    STATIC_VEC_POP(&v, &out);      // *out = (vec)->data[--(vec)->len] -- 성공 시 1, 비어 있으면 0
    STATIC_VEC_TOP(&v);            // (vec)->data[(vec)->len - 1]
    STATIC_VEC_AT(&v, i);          // (vec)->data[i], 검사되지 않음
    STATIC_VEC_LEN(&v);            // (vec)->len
    STATIC_VEC_EMPTY(&v);          // (vec)->len == 0
}
```

::: warning `STATIC_VEC_INIT`은 타입 이름이 아니라 용량 숫자를 받습니다
`STATIC_VEC_INIT(vec, Cap)`은 `(vec)->len = 0; (vec)->cap = (Cap);`으로
확장됩니다 — 두 번째 인자는 `STATIC_VEC_DECL`에 전달한 것과 동일한 숫자
용량입니다, 예: `STATIC_VEC_INIT(&v, 32)`이지 구조체의 타입 이름이 아닙니다.
이 페이지의 이전 버전은 `STATIC_VEC_INIT(v, MyVec)`을 보여주었는데, 이는
매크로의 실제 매개변수와 맞지 않습니다.
:::

### Static Map (오픈 어드레싱 해시) {#static-map-open-addressing-hash}

```c
STATIC_MAP_DECL(MyMap, 64);   // key=unsigned long, val=void*, 64개 버킷
MyMap m;
STATIC_MAP_INIT(&m, 64);

unsafe {
    STATIC_MAP_INSERT(&m, key, &val);   // 삽입 또는 갱신; key는 unsigned long
    STATIC_MAP_GET(&m, key);            // void*를 반환, 또는 NULL
    STATIC_MAP_LEN(&m);                 // (m)->count
}
```

`STATIC_MAP_DECL`은 (`STATIC_VEC_DECL`과 달리) 원소 타입 매개변수가 없습니다 — 값은 항상 `void*`로 저장되며, 꺼낼 때 캐스트됩니다.

### 예제 {#example-9}

실제 컴파일/실행으로 검증됨:

```c
#include <std/collections/static_vec.h>

STATIC_VEC_DECL(IntVec, int, 16);
STATIC_MAP_DECL(IntMap, 8);

int main() {
    IntVec v;
    STATIC_VEC_INIT(&v, 16);
    unsafe {
        STATIC_VEC_PUSH(&v, 10);
        STATIC_VEC_PUSH(&v, 20);
        STATIC_VEC_PUSH(&v, 30);
        printf("top=%d\n", STATIC_VEC_TOP(&v));   // 30
        printf("len=%lu\n", STATIC_VEC_LEN(&v));  // 3

        int out = 0;
        STATIC_VEC_POP(&v, &out);
        printf("popped=%d len=%lu\n", out, STATIC_VEC_LEN(&v));  // popped=30 len=2
        printf("at1=%d\n", STATIC_VEC_AT(&v, 1));                 // 20
    }

    IntMap m;
    STATIC_MAP_INIT(&m, 8);
    int val1 = 100;
    unsafe {
        STATIC_MAP_INSERT(&m, 5UL, &val1);
        int* found = (int*)STATIC_MAP_GET(&m, 5UL);
        printf("map[5]=%d\n", *found);   // 100
    }
    return 0;
}
```

---

## bst — 이진 탐색 트리 {#bst-binary-search-tree}

```c
#include "collections/bst.h"
```

사용자가 제공한 비교 함수를 사용하는 불균형 이진 탐색 트리입니다. 노드는 힙에 소유됩니다. 순서대로(오름차순 정렬)의 순회를 제공합니다. 자유 함수 API입니다. `t`는 리전이 없는 `&BST` 참조입니다(호출 지점은 그대로 `&t`).

### 구조체들 {#structs-1}

```c
struct BSTNode {
    void*          key;
    void*          val;
    ?&heap BSTNode left;   // 리프의 없는 자식은 빈(null) 값
    ?&heap BSTNode right;
};

struct BST {
    ?&heap BSTNode root;   // 빈 트리면 빈(null) 값; 이 BST가 힙에 소유함
    unsigned long  key_size;
    unsigned long  val_size;
    void*          cmp_fn;   // int(*)(const void*, const void*)
    unsigned long  len;
};
```

### API {#api-3}

```c
// 라이프사이클
struct BST bst_new(unsigned long key_size, unsigned long val_size, void* cmp_fn);
void       bst_free(&BST t);

// 핵심 연산
int   bst_insert(&BST t, const void* key, const void* val);
void* bst_get(&BST t, const void* key);       // 못 찾으면 NULL
int   bst_contains(&BST t, const void* key);
int   bst_remove(&BST t, const void* key);
unsigned long bst_len(&BST t);
int   bst_is_empty(&BST t);
void  bst_clear(&BST t);

// 최소/최대
void* bst_min_key(&BST t);   // 비어 있으면 NULL
void* bst_max_key(&BST t);   // 비어 있으면 NULL

// 순회 (순서대로 = 오름차순 정렬)
void bst_foreach_inorder(&BST t, void* fn);  // fn: void(*)(const void* key, void* val)
```

### 내장 비교 함수 {#built-in-comparators}

`cmp_fn` 인자로 이것들을 전달하세요:

```c
int bst_cmp_int(const void* a, const void* b);    // int 키
int bst_cmp_ll(const void* a, const void* b);     // long long 키
int bst_cmp_str(const void* a, const void* b);    // const char* 키
int bst_cmp_uint(const void* a, const void* b);   // unsigned int 키
```

### 제네릭 래퍼 {#generic-wrappers-6}

```c
generic<T> int bst_insert_t(&BST t, const void* key, T val);
generic<T> T*  bst_get_t(&BST t, const void* key);   // 위의 팁 참고 -- 타입이 지정된 대상이 필요
```

### 예제 {#example-10}

```c
#include <std/collections/bst.sc>

int main() {
    struct BST tree = std::bst_new(sizeof(int), sizeof(int), (void*)std::bst_cmp_int);

    int k1 = 30; int v1 = 300;
    int k2 = 10; int v2 = 100;
    int k3 = 50; int v3 = 500;

    unsafe {
        std::bst_insert(&tree, (const void*)&k1, (const void*)&v1);
        std::bst_insert(&tree, (const void*)&k2, (const void*)&v2);
        std::bst_insert(&tree, (const void*)&k3, (const void*)&v3);

        int* found = std::bst_get_t(&tree, (const void*)&k2);   // 'int*' 대상으로부터 T=int가 추론됨
        if (found != (int*)0) {
            printf("%d\n", *found);   // 100
        }

        int* min_k = (int*)std::bst_min_key(&tree);
        int* max_k = (int*)std::bst_max_key(&tree);
        printf("min = %d\n", *min_k);   // 10
        printf("max = %d\n", *max_k);   // 50
    }

    std::bst_free(&tree);
    return 0;
}
```
