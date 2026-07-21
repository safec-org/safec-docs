# 함수

SafeC는 일반적인 C 호환 함수부터 컴파일 타임에 평가되는 함수, 구조체 메서드, 제네릭 함수까지 여러 형태의 함수를 지원합니다.

## 일반 함수 {#regular-functions}

```c
int add(int a, int b) {
    return a + b;
}

void greet(const char *name) {
    printf("Hello, %s\n", name);
}
```

## Const 함수 {#const-functions}

`const`로 표시된 함수는 모든 인자가 알려진 상수일 경우 컴파일 타임에 실행될 수 있습니다. 런타임에 호출할 수도 있습니다.

```c
const int factorial(int n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}

const int val = factorial(5);  // 컴파일 타임에 평가됨 → 120
int dynamic = factorial(x);    // 런타임에 호출됨
```

## Consteval 함수 {#consteval-functions}

`consteval`로 표시된 함수는 **반드시** 컴파일 타임에 실행되어야 합니다. 상수가 아닌 인자로 호출하면 컴파일 오류입니다.

```c
consteval int table_size() {
    return 256;
}

int lookup[table_size()];      // OK: 컴파일 타임 상수
// int bad = table_size();     // ERROR: 결과가 const 컨텍스트에서 사용되지 않으면
```

## `constinit` 전역 변수 {#constinit-globals}

모든 전역 변수의 초기화식은 이미 컴파일 타임 상수 표현식이어야
합니다 — 이는 선택적 `constinit` 키워드가 있을 때만이 아니라 무조건적으로,
하드 컴파일 오류로 강제됩니다.

```c
int table_size() { return 256; }
int bad = table_size();   // ERROR: 컴파일 타임 상수 표현식이 아님
int ok  = 256;             // 정상
```

`constinit`은 순수하게 스스로 문서화하는 선택적 표기(annotation)입니다 —
이를 작성한다고 해서 초기화식이 검사되는 방식이 달라지지 않습니다.
모든 전역 변수가 이미 동일한 기준으로 검사되기 때문입니다. 이는 가독성을
위해(C++의 `constinit` 키워드와 대응하도록) 존재하며, 독자가 규칙을 알고
있으리라 기대하는 대신 선언부에서 의도를 명시하기 위한 것입니다.

```c
constinit int table_size = 256;
```

이는 일반 C나 C++이 허용하는 것보다 의도적으로 더 엄격한 조치입니다.
C++의 `constinit`이 의미를 갖는 이유는 오직 C++이 기본적으로 전역
변수의 동적(런타임) 초기화를 *허용*하고, `constinit`이 특정 선언을
그로부터 명시적으로 제외시키기 때문입니다. SafeC는 애초에 전역 변수의
동적 초기화를 전혀 허용하지 않으므로, 방어해야 할 암묵적인 대체 경로가
없습니다 — 상수가 아닌 초기화식은 키워드 유무와 관계없이 어디서든
컴파일 타임에 잡힙니다.

## 순수 함수 {#pure-functions}

`pure`로 표시된 함수는 부수 효과가 없음을 보장합니다. 컴파일러는 이를 LLVM `readonly`와 `nounwind` 속성으로 lowering하여 적극적인 최적화를 가능하게 합니다.

```c
pure double square(double x) {
    return x * x;
}
```

순수 함수는 다음을 할 수 없습니다.
- 전역 상태 변경
- I/O 수행
- 비순수 함수 호출

이 최적화 힌트를 넘어 순수 함수가 실제로 무엇을 보장해주는지는 [함수형 프로그래밍](/ko/reference/functional#purity)을 참고하십시오.

## Must-Use 함수 {#must-use-functions}

`must_use` 키워드는 반환값이 버려질 경우 컴파일러 경고를 발생시킵니다.

```c
must_use int compute(int x) {
    return x * x + 1;
}

compute(5);                    // WARNING: 반환값이 버려짐
int result = compute(5);       // OK
```

## Noreturn 함수 {#noreturn-functions}

절대 반환하지 않는 함수(예: `abort`, `exit`, 무한 루프)는 `noreturn`으로 표시할 수 있습니다.

```c
noreturn void panic(const char *msg) {
    printf("PANIC: %s\n", msg);
    abort();
}
```

## 구조체 메서드 {#struct-methods}

메서드는 구조체 본문 안에서 선언되고, `T::method()` 형태의 한정된(qualified) 문법을 사용해 바깥에서 정의됩니다. `self` 매개변수는 암묵적입니다.

### 선언 {#declaration}

```c
struct Point {
    double x;
    double y;

    double length() const;
    void scale(double s);
};
```

### 정의 {#definition}

```c
double Point::length() const {
    return self.x * self.x + self.y * self.y;
}

void Point::scale(double s) {
    self.x = self.x * s;
    self.y = self.y * s;
}
```

### 메서드 호출 {#calling-methods}

```c
Point p = {3.0, 4.0};
double len = p.length();       // Point_length(&p)를 호출
p.scale(2.0);                  // Point_scale(&p, 2.0)을 호출
```

### Lowering {#lowering}

메서드는 명시적인 `self` 포인터를 가진 일반 함수로 lowering됩니다.

| SafeC 시그니처 | Lowering된 C 시그니처 |
|----------------|---------------------|
| `double Point::length() const` | `double Point_length(const Point* self)` |
| `void Point::scale(double s)` | `void Point_scale(Point* self, double s)` |

const 메서드는 `const T*` self 포인터를 받고, 비const 메서드는 `T*` self 포인터를 받습니다. 메서드 본문 안에서 `self`는 `&stack T` 타입으로 취급됩니다.

## 연산자 오버로딩 {#operator-overloading}

구조체 타입은 `operator+`, `operator-` 등의 이름을 가진 메서드를 정의하여 이항 연산자를 오버로드할 수 있습니다.

```c
struct Vec2 {
    double x;
    double y;

    Vec2 operator+(Vec2 other) const;
    Vec2 operator-(Vec2 other) const;
    Vec2 operator*(double s) const;
};

Vec2 Vec2::operator+(Vec2 other) const {
    Vec2 result;
    result.x = self.x + other.x;
    result.y = self.y + other.y;
    return result;
}
```

사용법:

```c
Vec2 a = {1.0, 2.0};
Vec2 b = {3.0, 4.0};
Vec2 c = a + b;               // Vec2_operator+(a, b)를 호출
```

오버로드 가능한 연산자: `+`, `-`, `*`, `/`, `%`, `==`, `!=`, `<`, `>`, `<=`, `>=`.

연산자 메서드는 생성된 코드에서 `TypeName_operator+` 등으로 이름 맹글링됩니다.

이것이 제네릭, 트레이트, `fn_eval`과 나란히 존재하는 여러 다형성 메커니즘 중 하나로서 어떻게 맞물리는지는 [다형성과 OOP](/ko/reference/polymorphism#ad-hoc-polymorphism-operator-overloading)를 참고하십시오.

## 제네릭 함수 {#generic-functions}

제네릭 함수는 `generic<T>` 문법을 사용하며 컴파일 타임에 완전히 단형화됩니다.

### 기본 제네릭 {#basic-generic}

```c
generic<T>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}

int m1 = max(3, 7);           // max<int>를 인스턴스화
double m2 = max(1.5, 2.7);    // max<double>을 인스턴스화
```

### 제약이 있는 제네릭 {#constrained-generic}

타입 매개변수는 트레이트로 제약될 수 있습니다.

```c
generic<T: Numeric>
T clamp(T val, T lo, T hi) {
    if (val < lo) return lo;
    if (val > hi) return hi;
    return val;
}
```

### 단형화 {#monomorphization}

컴파일러는 각 구체적인 타입 인스턴스화마다 함수 본문을 깊이 복제하여, 타입 매개변수를 구체적인 타입으로 치환합니다. 맹글링된 이름은 `__safec_fn_type` 패턴을 따릅니다.

```
max<int>    → __safec_max_int
max<double> → __safec_max_double
```

제네릭 본문은 첫 번째 의미 분석(semantic analysis) 패스에서는 건너뜁니다. 타입 추론은 호출 지점의 인자 타입으로부터 `T`를 결정합니다.

## Naked 함수 {#naked-functions}

naked 함수는 컴파일러가 생성하는 프롤로그나 에필로그가 없습니다. 본문은 전적으로 인라인 어셈블리로만 구성되어야 합니다.

```c
naked void isr_handler() {
    asm volatile ("iret");
}
```

## 인터럽트 함수 {#interrupt-functions}

인터럽트 함수는 ISR 호출 규약을 사용합니다. 반드시 `void(void)`여야 합니다.

```c
interrupt void timer_isr() {
    // 타이머 인터럽트 처리
}
```

## 함수 포인터 {#function-pointers}

함수는 `fn` 타입 문법을 사용해 값으로 참조될 수 있습니다.

```c
fn int(int, int) op = add;
int result = op(3, 4);        // add(3, 4)를 호출

// 고차 함수
int apply(fn int(int) f, int x) {
    return f(x);
}
```

더 큰 고차 함수 예제(제네릭 `map`/`fold` 헬퍼)는 [함수형 프로그래밍](/ko/reference/functional)을, `fn` 타입과 `void*` 필드를 가진 구조체를 이용해 명시적인 런타임 디스패치 — SafeC에서 손으로 작성한 vtable에 대응하는 것 — 를 구축하는 방법은 [다형성과 OOP](/ko/reference/polymorphism#manual-dispatch-explicit-vtables)를 참고하십시오.
