# C 상위집합 호환성

SafeC는 C의 상위집합이다: C 자체가 수십 년간의 시스템 프로그래밍에서
물려받은 패턴을 포함해 실제 C 코드는, 근본적으로 메모리 안전하지 않은
구성(원시 포인터 연산, 검사되지 않은 캐스트 등)을 사용하는 경우를
제외하면 수정 없이 컴파일된다 — 그런 구성조차 SafeC는 완전히 거부하는
대신 `unsafe { }`로 감싸도록 요구할 뿐이다. 이 페이지는 [타입](/ko/reference/types),
[함수](/ko/reference/functions), [메모리와 리전](/ko/reference/memory)에서
설명한 핵심 언어를 넘어, SafeC가 그대로 받아들이는 C 구성들을 다룬다.

## `__attribute__((...))` {#__attribute__}

GCC/Clang의 어트리뷰트 문법은 실제 C 코드가 사용하는 모든 위치 —
접두사, 접미사, 구조체 태그, 구조체 본문 뒤 — 에서 받아들여지며,
인식되는 어트리뷰트는 코드 생성에 영향을 준다. 인식되지 않는
어트리뷰트는 거부되지 않고 그대로 용인된다(파싱만 되고 무시됨) —
그래서 GCC/Clang용으로 작성된 헤더를 임포트할 때 수정이 필요 없다.

```c
__attribute__((always_inline)) static int add_one(int x) { return x + 1; }

int subtract_one(int x) __attribute__((pure));

struct __attribute__((packed)) PackedPoint {
    unsigned char tag;
    int value;
};

int g_aligned_val __attribute__((aligned(16))) = 7;
```

인식되는 어트리뷰트: `always_inline`, `pure`, `packed`, `aligned(N)`
(단순 `aligned`는 타깃의 최대 유효 정렬값을 기본값으로 사용),
`section("name")` (SafeC 네이티브 `section(...)`과 동일한 효과 —
[베어메탈](/ko/reference/baremetal) 참고).

## C 스타일 함수 포인터 {#c-style-function-pointers}

SafeC 네이티브 문법인 `fn RetType(Params)`와 더불어, 고전적인 C
선언자(declarator) 형식도 타입이 등장할 수 있는 모든 곳 — typedef,
전역 변수, 구조체 필드, 함수 매개변수 — 에서 동작한다.

```c
typedef int (*BinOp)(int, int);

int (*g_op)(int, int) = mul_impl;

struct Handler {
    void (*on_event)(int);
};

static int apply(int (*callback)(int, int), int x, int y) {
    return callback(x, y);
}
```

## 비트필드 {#bitfields}

```c
struct Flags {
    unsigned int a : 4;
    unsigned int b : 12;
    unsigned int c : 16;
    int          sc : 4;   // 부호 있는 비트필드
    int          tag;      // 비트필드 뒤의 일반 필드, 자신만의 슬롯을 가짐
};
```

동일한 저장 단위를 공유하는 비트필드들은 하나의 정수 슬롯으로 묶여,
읽고 쓸 때마다 (복합 대입 `f.a += 1;`, `f.a &= 0xF;` 포함) 시프트/마스크
코드가 생성된다 — 한 비트필드에 대한 단순 저장이 같은 단위에 함께
묶인 형제 필드를 훼손하는 일은 절대 없다.

## 지정 초기화자 {#designated-initializers}

```c
struct Point { int x; int y; int z; };
struct Point p = { .y = 5, .x = 1, .z = 9 };   // 순서 무관

int arr[6]        = { 1, 2, [4] = 40, 5 };      // 인덱스 3은 0으로 채워짐
int global_arr[5] = { [1] = 10, [3] = 30 };     // 0, 2, 4는 0으로 채워짐
```

지정자는 위치 기반 초기화자와 섞어 쓸 수 있다. 배열 지정자
(`[N] = value`)와 구조체 지정자(`.field = value`) 모두 일반 위치 기반
초기화에 Sema가 사용하는 것과 동일한 슬롯 매핑을 통해 처리되므로,
부분적으로만 지정된 애그리게이트는 C와 정확히 동일하게 0으로 채워진다.

## 가변 길이 배열 멤버(Flexible Array Members) {#flexible-array-members}

```c
struct Msg {
    int len;
    unsigned char data[];   // 반드시 마지막 필드여야 함
};

unsafe {
    struct Msg* m = (struct Msg*)malloc(sizeof(struct Msg) + 4UL);
    m->data[0] = (unsigned char)10;
}
```

`sizeof(struct Msg)`는 C와 마찬가지로 가변 길이 멤버를 제외한다 —
초과 할당과 구조체의 고정 부분을 지난 인덱싱은 호출자의 책임이며,
그래서 위 접근에는 `unsafe`가 필요하다.

## 익명 구조체/유니온 {#anonymous-structunion}

```c
struct Variant {
    int tag;
    union {           // 익명 -- 멤버가 Variant 자신의 스코프로 승격됨
        int   as_int;
        float as_float;
    };
};

struct Nested {
    int id;
    struct {          // 익명 중첩 구조체, 동일하게 승격됨
        int x;
        int y;
    };
};

struct Variant v;
v.as_int = 42;   // 중간 멤버 이름이 필요 없음
```

## 복합 리터럴 {#compound-literals}

```c
struct Point { int x; int y; };

int total = sum_point((struct Point){3, 4});
struct Point p2 = (struct Point){.x = 10, .y = 20};   // 여기서도 지정자를 쓸 수 있음
```

## `_Generic` {#_generic}

C11의 타입 제네릭 선택(type-generic selection)은 컴파일 타임에 제어
표현식의 *타입*에 따라 디스패치한다는 점에서, SafeC 자체의
[`generic<T>`](/ko/reference/generics)(인스턴스화되는 타입마다 함수
본문을 단형화하는 방식)와는 다르다. `_Generic`은 대신 단일 인자의
타입에 따라 이미 작성되어 있는 여러 표현식 중 하나를 고른다:

```c
int describe(int i, double d) {
    int r1 = _Generic(i, int: 100, double: 200, default: -1);   // 100
    int r2 = _Generic(d, int: 100, double: 200, default: -1);   // 200
    return r1 + r2;
}
```

## 가변 길이 배열(Variable-Length Arrays) {#variable-length-arrays}

VLA는 지원되지만 `unsafe { }` 안에서만 가능하다 — 런타임에 크기가
결정되는 스택 할당은 안전한 영역이 설계상 배제하는, 검사되지 않는
크기 연산의 전형적인 예다([안전성](/ko/reference/safety) 참고).

```c
int sum_vla(int n) {
    int total = 0;
    unsafe {
        int arr[n];             // n은 컴파일 타임 상수가 아니어도 됨
        for (int i = 0; i < n; i++) arr[i] = i * 2;
        for (int i = 0; i < n; i++) total += arr[i];
    }
    return total;
}
```
