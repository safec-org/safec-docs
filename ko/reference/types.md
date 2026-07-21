# 타입

SafeC는 암묵적 변환이 없는(단, 안전한 숫자 확장 — 작은 것에서 큰 것으로만, 그 반대는 절대 없음 — 은 예외) 강력한 정적 타입 시스템을 제공합니다. 모든 타입은 컴파일 타임에 해석되며, 제네릭은 런타임 오버헤드 없이 완전히 단형화됩니다.

## 기본 타입 {#primitive-types}

| 타입 | 크기 | 설명 |
|------|------|-------------|
| `int` | 32비트 | 부호 있는 정수 |
| `long` / `long long` | 64비트 | 부호 있는 long 정수 |
| `float` | 32비트 | IEEE 754 단정밀도 |
| `double` | 64비트 | IEEE 754 배정밀도 |
| `bool` | 1비트 | `true` 또는 `false` |
| `char` | 8비트 | 문자 (부호 없는 바이트) |
| `void` | 0비트 | 유닛 타입 / 값 없음 |

## 크기 지정 정수 타입 {#sized-integer-types}

너비와 부호 여부를 명시적으로 제어하기 위한 타입입니다.

| 부호 있음 | 부호 없음 | 너비 |
|--------|----------|-------|
| `int8_t` | `uint8_t` | 8비트 |
| `int16_t` | `uint16_t` | 16비트 |
| `int32_t` | `uint32_t` | 32비트 |
| `int64_t` | `uint64_t` | 64비트 |

## `auto`를 이용한 타입 추론 {#type-inference-with-auto}

`auto`는 초기화식으로부터 지역 변수의 타입을 추론합니다 — 변수는 반드시 초기화식을 가져야 합니다(초기화식이 없는 `auto x;`는 컴파일 오류입니다).

```c
auto n = 42;       // int
auto pi = 3.14;     // double
auto p = compute(); // compute()가 반환하는 무엇이든

for (auto i = 0; i < 10; i++) {
    // i : int
}
```

`auto`는 오직 초기화식 표현식 자체의 타입으로부터만 추론하며, 더 넓은 범위의 흐름 분석을 수행하지 않습니다. (다른 모든 선언된 타입과 마찬가지로) 추론된 타입은 변수의 생애 동안 고정되며, 재대입 시 재추론되지 않습니다. 위에서 보인 것처럼 `for` 루프의 초기화절을 포함해, 일반적인 선언 타입을 가진 지역 변수가 쓰일 수 있는 어디서든 사용할 수 있습니다.

## 구조체 타입 {#struct-types}

구조체는 C와 호환되는 레이아웃을 가진 값 타입입니다. 대입은 값 전체를 복사합니다.

```c
struct Point {
    double x;
    double y;

    double length() const;
    void scale(double s);
};
```

구조체는 메서드([함수](/ko/reference/functions) 참고), 연산자 오버로딩을 지원하며, 제네릭 타입 인자로 사용될 수 있습니다.

## 유니온 타입 {#union-types}

SafeC의 모든 `union`은 **태그된 유니온(tagged union)**입니다: 선언한
필드들과 함께, 컴파일러는 실제로 어느 필드가 살아있는지를 기록하는
숨겨진 판별자(discriminant)를 저장하며, 가장 최근에 쓴 필드만 읽을 수
있도록(직접적인 필드 접근이 아니라 `match`를 통해 — 아래 참고) 강제합니다.
이는 C의 `union`으로부터의 의도적인 이탈입니다. C에서는 마지막으로
어떤 필드가 쓰였는지와 무관하게 원하는 필드를 읽는 것이 (타입 펀닝에
의존적이긴 하지만) 합법입니다 — SafeC는 "안전한 코드에는 정의되지 않은
동작이 없다"는 일반 원칙에 따라 이를 허용하지 않습니다.

```c
union Result {
    int ok;
    int err;
}
```

::: warning `Type.field(value)` / `match`를 통해 생성하고 읽으십시오. 일반적인 `.field` 대입은 안 됩니다
모든 유니온이 태그되어 있으므로, `union Result r; r.ok = 42;` 다음에
일반 필드 읽기를 하는 것은 C의 유니온처럼 동작하지 않습니다(그리고
판별자와 페이로드가 배치되는 방식 때문에, `int` 크기가 아닌 필드에
대해서는 완전히 잘못된 값을 만들어낼 수도 있습니다) — 이는 유니온을
사용하는 허용된 방법이 아닙니다. 유니온 값은 `TypeName.field(value)`로
생성하고, `match`의 점(dot) 접두사 변형 패턴인 `case .field(x):`로
읽으십시오.

```c
union Result {
    int ok;
    int err;
}

void handle(union Result r) {
    match (r) {
        case .ok(v):  printf("ok: %d\n", v);
        case .err(e): printf("err: %d\n", e);
        default:      printf("unreachable\n");
    }
}

int main() {
    union Result a = Result.ok(42);
    union Result b = Result.err(-1);
    handle(a);   // ok: 42
    handle(b);   // err: -1
    return 0;
}
```

이는 `?T`/`?&region T`의 `some(x)`가 사용하는 것(위의 "nullable 값 읽기" 참고)과 동일한 `.variant(x)` 패턴 형태입니다. 다만 그쪽에는 선행하는 점이 없습니다 — `some`/`none`은 태그된 유니온 자체의 변형 이름과 달리 점이 붙지 않은 일반 식별자입니다.
:::

유니온도 제네릭이 될 수 있습니다. [구조체](/ko/reference/generics#generic-structs-and-methods)와 마찬가지로, `generic<T, E> union Result { T ok; E err; }`는 임의의 타입 쌍에 대한 진짜 합 타입(sum type)이며, 다른 제네릭 타입과 마찬가지로 구체적인 `<T, E>` 인스턴스화마다 단형화됩니다.

태그된 유니온과 완전성 검사된 `match`는 SafeC의 대수적 데이터 타입 / 닫힌 다형성(closed-polymorphism) 메커니즘입니다 — [다형성과 OOP](/ko/reference/polymorphism#closed-set-polymorphism-tagged-unions-match)와 [함수형 프로그래밍](/ko/reference/functional#algebraic-data-types)을 참고하십시오.

## 튜플 타입 {#tuple-types}

튜플은 익명 곱 타입(product type)입니다. 멤버는 `.0`, `.1` 등으로 인덱스를 통해 접근합니다.

```c
tuple(int, double) pair = (42, 3.14);
int first = pair.0;
double second = pair.1;
```

튜플은 코드 생성 시 익명 LLVM 구조체 타입으로 lowering됩니다.

## 슬라이스 타입 {#slice-types}

슬라이스는 데이터 포인터와 길이로 구성된 팻 포인터(fat pointer)입니다. 슬라이스는 연속된 메모리에 대한 경계 검사된 접근을 제공합니다.

```c
int arr[5] = {10, 20, 30, 40, 50};
[]int s = arr[1..4];   // {int*, i64}이며 len=3

int x = s[0];          // 경계 검사된 접근
long len = s.len;      // 길이 필드
int *raw = s.ptr;      // 내부 포인터
```

## 옵셔널 타입 {#optional-types}

옵셔널 타입은 존재할 수도, 존재하지 않을 수도 있는 값을 나타냅니다. `{T, i1}` 쌍으로 lowering됩니다.

```c
?int find_first(int *arr, int n, int target) {
    int i = 0;
    while (i < n) {
        int v;
        unsafe { v = arr[i]; }
        if (v == target) return i;   // 암묵적 T -> ?T 래핑
        i = i + 1;
    }
    return null;                     // 빈 케이스
}

// try와 함께 사용 (빈 케이스를 호출자로 전파)
?int wrapper(int *arr, int n, int target) {
    int val = try find_first(arr, n, target);
    return val * 2;
}
```

순수한 `T`는 암묵적으로 `?T`로 래핑되고(위의 `return i;`처럼), `null`은 `?T` 옵셔널과 nullable 참조 **양쪽 모두**의 빈 값입니다 — 별도의 `some(x)`/`none` 생성자 문법은 없습니다. 이 두 식별자는 (일반 표현식이 아니라) [제어 흐름](/ko/reference/control-flow)에서 본 것처럼 오직 `match` 패턴으로만 등장합니다.

Nullable 참조도 동일한 `?` 문법을 사용합니다.

```c
?&stack Node next;   // nullable 스택 참조
```

### Nullable 값 읽기 {#reading-a-nullable-value}

포인터(`T*`), nullable 참조(`?&region T` — 여기서 "region"은 리전이 없는 `?&T` 형태도 포괄합니다. [메모리와 리전](/ko/reference/memory)의 "Outliving References" 절 참고), 또는 옵셔널(`?T`)은 직접 역참조, 멤버 접근, 강제 언래핑될 수 없습니다 — 컴파일러는 다음 중 하나를 요구합니다.

| 연산 | 대상 | 효과 |
|-----------|----------|------|
| `x.is_null()` | `T*`, `?&region T` | `bool`을 반환; 존재 여부 검사만 하며 `x`의 타입을 좁히지 않음 |
| `x.is_none()` | `?T` | `bool`을 반환; 옵셔널에서 `is_null()`에 대응 |
| `x.default(fallback)` | 세 가지 모두 | 값이 있으면 내부 값을, 없으면 `fallback`을 평가하여 반환(내부 타입과 일치해야 함) |
| `match (x) { case null / none: ...  case some(v): ... }` | 세 가지 모두 | `v`는 `some` 분기 안에서 내부 타입으로 직접 바인딩됨 |
| `unsafe { }` 안에서의 `x!` / `*x` / `x.field` / `x->field` | 세 가지 모두 | 위의 검사를 전부 우회함 |

```c
struct Node { int value; };

int describe(?&stack Node n) {
    // int v = n.value;      // ERROR: 'unsafe'가 필요하거나 match/is_null()/.default(value) 필요
    // Node v = n!;          // ERROR: '!' 강제 언래핑은 'unsafe'가 필요

    if (n.is_null()) { return -1; }        // OK: 존재 여부 검사
    Node fallback;
    fallback.value = -1;
    Node result = n.default(fallback);      // 먼저 바인딩 — .default(...).value로
    return result.value;                    // 바로 체이닝하는 것은 컴파일되지 않음(임시 리시버)

    // OK: unsafe는 검사를 완전히 우회함
    // unsafe { return n->value; }
}

int describe_match(?&stack Node n) {
    return match (n) {
        case null:    -1,
        case some(v): v.value,   // v : Node (페이로드의 복사본) -- 참조가 아니라 값으로 바인딩됨
    };
}
```

`?T`에 대한 `x.is_null()`(그리고 포인터/nullable 참조에 대한 `x.is_none()`)은 컴파일 오류입니다 — 리시버의 종류에 맞는 것을 사용하십시오. `null`/`some(x)` 패턴을 가진 `match`는 nullable 참조뿐 아니라 원시 포인터(`T*`)에도 동작합니다.

`try` 기반 전파와 `?T`가 의도적으로 제공하지 않는 것들(`.map()`/`.and_then()` 조합자가 없음)에 대해서는 [함수형 프로그래밍](/ko/reference/functional#optional-values-and-propagation)을 참고하십시오.

## 뉴타입 고유 타입 {#newtype-distinct-types}

뉴타입은 기반 타입으로부터 구별되는 타입을 만듭니다. 기반 타입과 서로 교환하여 사용할 수 없습니다.

```c
newtype UserId = int;
newtype Temperature = double;

UserId id = (UserId)42;   // 명시적 캐스트 — UserId(42) 생성자 호출 문법은 없음
// int x = id;            // ERROR: UserId는 int가 아님
```

## 열거형 타입 {#enum-types}

명시적인 하위 타입을 가진 열거형입니다.

```c
enum Color : uint8_t {
    Red = 0,
    Green = 1,
    Blue = 2
}

enum Status : int {
    OK = 200,
    NotFound = 404,
    ServerError = 500
}
```

## 함수 타입 {#function-types}

함수 포인터는 `fn` 키워드를 사용합니다.

```c
fn int(int, double) compute;
fn int(int) transform = add_one;
int result = transform(5);     // add_one(5)를 호출
```

## 리전 한정 참조 타입 {#region-qualified-reference-types}

참조는 컴파일러가 라이프타임 분석에 사용하는 리전 정보를 가지고 있습니다.

```c
&stack int           // non-null 스택 참조
&heap float          // non-null 힙 참조
&static Config       // non-null 정적 참조
&arena<AudioPool> Frame  // non-null 아레나 참조
&Point               // non-null 참조, 리전 없음 -- 위 어떤 것이든 받아들임
?&Point              // nullable, 리전 없음
```

리전 한정자를 아예 생략하는 것(`&T` / `?&T`)은 선언되거나 추적되는 리전이
없는 참조를 만듭니다 — 이것이 특정 리전을 고정하는 것보다 적절한 선택이
되는 경우는 [메모리와 리전](/ko/reference/memory)의 "Outliving References" 절을 참고하십시오.

자세한 내용은 [메모리와 리전](/ko/reference/memory)을 참고하십시오.

## 제네릭 타입 {#generic-types}

제네릭은 컴파일 타임 전용이며 완전히 단형화됩니다. vtable이나 런타임 디스패치가 없습니다.

```c
generic<T: Numeric>
T add(T a, T b) {
    return a + b;
}

// 컴파일러는 별도의 버전들을 생성합니다:
// int add(int a, int b)
// double add(double a, double b)
```

제네릭 타입 매개변수는 트레이트로 제약될 수 있습니다.

```c
generic<T: Numeric>
T clamp(T val, T lo, T hi) {
    if (val < lo) return lo;
    if (val > hi) return hi;
    return val;
}
```

## 타입 변환 {#type-conversions}

SafeC는 한 가지 예외를 제외하면 **암묵적 변환이 없습니다**
— 그 예외는 안전한 확장(widening)입니다: 정수, 부동소수점, 정수→부동소수점에
걸쳐 더 작은 숫자 타입에서 더 큰 타입으로의 변환입니다. 축소(narrowing,
더 큰 것에서 더 작은 것으로, 또는 부동소수점에서 정수로)는 여전히 명시적
캐스트가 필요합니다.

```c
int x = 42;
double d = x;             // 암묵적: int -> double은 안전하게 확장됨

float f = 3.14f;
double d2 = f;             // 암묵적: float -> double은 안전하게 확장됨
int i = (int)f;            // 명시적 캐스트 필요: float -> int는 축소됨

long long big = 100LL;
int small = (int)big;      // 명시적 캐스트 필요: 축소됨
```

## 값 시맨틱 vs 참조 시맨틱 {#value-vs-reference-semantics}

- **구조체는 값 타입입니다**: 대입은 구조체 전체를 복사합니다
- **참조는 명시적입니다**: 참조를 만들려면 `&`를 사용해야 하고 리전을 한정해야 합니다
- **숨겨진 이동 시맨틱이 없습니다**: 작성한 그대로 동작합니다
- **배열은 함수에 전달될 때 포인터로 decay됩니다**, C의 관례를 따릅니다

```c
struct Point { double x; double y; };

Point a = {1.0, 2.0};
Point b = a;              // 구조체를 복사함
b.x = 99.0;              // a에는 영향을 주지 않음

&stack Point ref = &a;    // 명시적 참조
```
