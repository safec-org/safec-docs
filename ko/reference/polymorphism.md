# 다형성과 OOP

SafeC에는 클래스도, 상속도, vtable도 없습니다. 이는 빠진 기능이 아니라 의도적인 설계 방향입니다 — [설계 철학](/ko/guide/design#why-not-classes)의 표현을 빌리면: "다형성은 제네릭을 통해 구현됩니다 (단형화되어, 비용이 전혀 들지 않음)." 이 페이지에 나오는 모든 메커니즘은 선택적(opt-in)이며, 그 런타임 비용(대부분의 경우 0)은 당신이 고른 메커니즘 자체에 그대로 드러납니다 — 모든 객체가 필요하든 필요 없든 암묵적으로 들고 다니는 vtable 포인터 뒤에 숨어있지 않습니다.

이 페이지는 SafeC가 실제로 가지고 있는 다형성 관련 메커니즘을 한데 모은 것입니다 — 대부분은 이미 다른 곳에 개별적으로 문서화되어 있고, 여기서 링크로 연결됩니다 — "C++/Java/Python OOP에서 넘어왔을 때 무엇을 쓰면 되는가"에 대한 하나의 지도인 셈입니다. 또한 이 페이지는 `fn_eval`을 문서화합니다 — SafeC의 vtable 없는 메서드 디스패치 프리미티브로, 자체 키워드·파서 규칙·의미 분석(semantic-analysis) 패스를 갖춘 완전히 구현된 컴파일러 기능임에도 불구하고 이 페이지가 작성되기 전까지는 레퍼런스 문서가 전혀 없었습니다.

## 캡슐화: 구조체와 메서드 {#encapsulation-structs-and-methods}

데이터와 동작은 클래스와 마찬가지로 구조체에 함께 묶이지만, C++ 클래스가 함께 딸려오는 나머지 것들은 빠져 있습니다 — 구체적인 목록(암묵적 생성자/소멸자 없음, 숨겨진 `this` 없음, vtable 없음)은 [설계 철학](/ko/guide/design#why-not-classes)을 참고하세요. 메서드는 명시적인 `self` 포인터를 받는 평범한 함수의 문법 설탕(syntactic sugar)입니다:

```c
struct Point {
    double x;
    double y;
    double length() const;
};
double Point::length() const {
    return sqrt_d(self.x * self.x + self.y * self.y);
}
```

`p.length()`는 간접 호출 없이 `Point_length(&p)`라는 직접 호출로 컴파일됩니다. 전체 로우어링(lowering) 표와 호출 규약 세부사항은 [함수](/ko/reference/functions#struct-methods)를 참고하세요.

## 애드혹 다형성: 연산자 오버로딩 {#ad-hoc-polymorphism-operator-overloading}

같은 연산자 이름이 타입마다 다른 동작으로 해석되는, 애드혹 다형성의 전형적인 정의입니다 — 고정된 연산자 집합(`+ - * / % == != < > <= >=`)으로 범위가 한정되며, 일반적인 함수 오버로딩은 아닙니다 (SafeC는 이 목록 밖의 매개변수 타입 기반 오버로딩을 지원하지 않습니다):

```c
struct Vec2 {
    double x;
    double y;
    Vec2 operator+(Vec2 other) const;
};
Vec2 Vec2::operator+(Vec2 other) const {
    Vec2 result;
    result.x = self.x + other.x;
    result.y = self.y + other.y;
    return result;
}

Vec2 c = a + b;   // Vec2_operator+(a, b)를 호출
```

[함수](/ko/reference/functions#operator-overloading)와 [연산자](/ko/reference/operators#operator-overloading)를 참고하세요.

## 매개변수 다형성: 제네릭 {#parametric-polymorphism-generics}

가장 핵심적인 다형성 메커니즘이며, 설계 철학이 클래스의 대체재로 꼽는 바로 그것입니다: `generic<T>` 함수나 구조체는 완전히 단형화(monomorphize)됩니다 — 컴파일러는 인스턴스화되는 타입마다 별도의 구체 코드 사본을 컴파일 타임에 만들어내며, 런타임 타입 태그도 간접 호출도 없습니다:

```c
generic<T>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}

int m1 = max(3, 7);          // max<int>를 인스턴스화
double m2 = max(1.5, 2.7);   // max<double>을 인스턴스화
```

구조체도 같은 방식으로 제네릭이 될 수 있으며(`generic<T> struct Pair { T first; T second; };`), 제네릭 메서드도 마찬가지입니다. 단형화, 이름 맹글링(name mangling), 가변 인자 타입 팩(variadic pack), 리전 안전성과의 상호작용은 [제네릭](/ko/reference/generics)을 참고하세요.

**제네릭을 다형성의 해법으로 쓰기 전에 반드시 알아야 할, 핵심적인 제약사항입니다:** 단형화된다는 것은 서로 다른 `T`를 가진 `generic<T>` 값들의 이질적인(heterogeneous) 런타임 컬렉션이라는 것이 애초에 존재하지 않는다는 뜻입니다 — `Vec<Circle>`과 `Vec<Square>`는 서로 무관한, 완전히 별개로 생성된 타입입니다. `std::vector<Circle>`과 `std::vector<Square>`가 서로 무관한 C++ 템플릿 인스턴스인 것과 정확히 같은 이치입니다. "어떤 도형이든" 담을 수 있는 배열 하나가 필요하다면, 아래의 [수동 디스패치](#manual-dispatch-explicit-vtables)를 참고하세요 — 그것이 이를 얻는 정직하고 명시적인 방법입니다.

## 구조적 다형성: 트레이트 {#structural-polymorphism-traits}

`trait`는 이름이 붙은 메서드 시그니처 집합으로, **구조적으로**("덕 타이핑" 방식으로) 만족됩니다 — `impl Trait for Type` 같은 블록도, 명시적인 옵트인도 필요 없습니다. 구조체는 일치하는 메서드를 정의하는 순간 그 트레이트를 만족하게 됩니다:

```c
trait Drawable {
    void draw() const;
}

struct Circle {
    double radius;
    void draw() const;   // Drawable을 만족 — 그 외엔 아무것도 필요 없음
}
void Circle::draw() const { printf("circle r=%.1f\n", self.radius); }

generic<T: Drawable> void render(T shape) { shape.draw(); }
render(circle);   // OK: Circle에 일치하는 draw() const가 있음
```

만족 여부는 단형화가 일어나는 호출 지점에서 검사됩니다 — 제네릭 함수 본문이 정의된 위치가 아닙니다. 그래서 메서드가 빠져 있으면 호출 지점에서 바로 에러가 나며, 라이브러리 내부 깊숙한 곳에서 혼란스러운 실패가 나는 일이 없습니다. 내장 구조적 트레이트: `Numeric`, `Eq`, `Ord`, `Add`, `Sub`, `Mul`, `Div`(연산자 기반)와, 사용자 정의 메서드가 아니라 타입 시스템 자체가 충족시키는 `Indexed`(`[]`로 접근 가능한 모든 것)와 `Pointer`(raw 포인터/참조 타입)가 있습니다. [제네릭](/ko/reference/generics#traits-and-constrained-generics)을 참고하세요.

이것이 SafeC에서 인터페이스/추상 기반 클래스에 가장 가까운 대응물입니다 — 트레이트 바운드는 "이 메서드들을 가진 임의의 `T`"라고 말할 뿐이며, 컴파일 타임에 검사되고 여전히 단형화됩니다(여전히 런타임 디스패치는 없습니다 — `generic<T: Drawable>` 본문의 모든 호출은 해당 인스턴스의 구체적인 `draw`에 대한 직접 호출입니다).

## vtable 없는 동적 디스패치: `fn_eval` {#vtable-free-dynamic-dispatch-fn-eval}

트레이트 바운드는 "이 메서드를 가지고 있다고 알려진 `T`에 대해 이 메서드를 호출"하게 해줍니다. `fn_eval`은 트레이트 바운드로는 얻을 수 없는 것을 줍니다: **메서드 자체를, 구체 타입별로 결정(resolve)된 일급(first-class) 함수 포인터 값으로** — 트레이트를 선언할 필요도, 어떤 런타임 디스패치 장치도 필요 없이 말입니다. 컴파일러 자체의 설명(`FnEvalExpr`의 설계 주석에서)은 이를 "vtable 없는 다형성(vtable-free polymorphism)"이라고 부릅니다: 소스 코드는 메서드를 동적으로 골라내는 것처럼 보이지만, 코드 생성(codegen) 단계에 다다르면 모든 호출 지점은 이미 직접적인 함수 참조입니다.

### 문법 {#syntax}

```c
fn_eval(object, func)
```

- `object`는 오직 그 **타입**만 검사됩니다 — 절대 평가(evaluate)되거나 코드 생성되지 않으며, `sizeof`의 피연산자와 같은 취급을 받습니다.
- `func`는 이미 선언된 **평범한 함수**(메서드가 아닌)를 직접 가리켜야 합니다 — 이는 순전히 이름+시그니처 키로만 쓰이며, 그 자체가 호출되는 일은 없습니다. 그 이름이 곧 찾고자 하는 메서드 이름이 되며, (self를 제외한) 매개변수/반환 타입이 찾아낸 메서드와 정확히 일치해야 합니다.
- 전체 표현식은 `object`의 구조체 타입에서 그 이름을 가진 메서드에 대한 함수 포인터 값으로 평가됩니다 — 즉시 호출하거나 나중을 위해 저장할 수 있으며, 수신자(receiver)는 첫 번째 인자로 명시적으로 전달해야 합니다(SafeC의 메서드에는 바인딩된 수신자가 없습니다 — [함수](/ko/reference/functions#lowering)의 로우어링 표를 참고하세요).

### 결정(resolution) 규칙 {#resolution-rules}

`fn_eval(object, func)`는 다음 조건을 모두 만족하지 않으면 컴파일에 실패합니다(무언가로 조용히 대체되는 일은 없습니다):

| 요구사항 | 위반 시 에러 |
|---|---|
| `func`가 선언된 함수를 가리켜야 함 | `'X' does not name a function` |
| `func`는 메서드가 아닌 평범한 함수여야 함 | `'X' must be a plain function, not a method` |
| `object`의 타입은 구조체(혹은 그에 대한 포인터/참조)여야 함 | `first argument must be a struct...` |
| 그 구조체 타입에 `func`와 같은 이름의 메서드가 있어야 함 | `type 'X' has no method named 'Y'` |
| 메서드의 매개변수 개수(`self` 제외)가 `func`와 일치해야 함 | `'X::Y' takes N parameter(s), but 'Y' declares M` |
| 모든 매개변수 타입이 위치별로 일치해야 함 | `parameter N of 'X::Y' is '...', but 'Y' declares '...'` |
| 반환 타입이 일치해야 함 | `'X::Y' returns '...', but 'Y' declares '...'` |

### 인스턴스화마다 결정되는 제네릭 디스패치 {#generic-dispatch-resolved-per-instantiation}

원래 설계 의도가 이것입니다: `generic<T>` 함수 안에서 `fn_eval`을 호출하는 것. `T`가 단형화되는 각 인스턴스는 자신의 구체 타입에 대해 `fn_eval`을 다시 결정(re-resolve)합니다 — 트레이트 선언은 전혀 필요 없습니다. 검사 자체가 인스턴스화 시점에 (제네릭 타입 검사의 다른 모든 것과 똑같이) 이름+시그니처를 구조적으로 비교하기 때문입니다:

```c
struct Circle {
    double radius;
    double area() const;
};
double Circle::area() const { return 3.14159265 * self.radius * self.radius; }

struct Square {
    double side;
    double area() const;
};
double Square::area() const { return self.side * self.side; }

// "형태(shape)" 선언 -- 이름(area)과 시그니처(아무것도 받지 않고
// double을 반환, self 제외)만이 중요합니다. 실제로 호출되는 일은
// 없습니다.
double area() { return 0.0; }

generic<T>
double describe_area(T obj) {
    return fn_eval(obj, area)(&obj);
}

int main() {
    struct Circle c; c.radius = 2.0;
    struct Square s; s.side = 3.0;

    double ca = describe_area(c);   // describe_area<Circle>은 fn_eval을 Circle::area로 결정
    double sa = describe_area(s);   // describe_area<Square>는 fn_eval을 Square::area로 결정
    printf("circle=%.4f square=%.4f\n", ca, sa);   // circle=12.5664 square=9.0000
    return 0;
}
```

`describe_area`는 `Circle`이나 `Square`라는 이름을 한 번도 언급하지 않으며, 두 타입 모두 어떤 것에도 만족(conformance)한다고 선언하지 않습니다 — `fn_eval`은 그저 매 인스턴스마다 "`T`에 `area`처럼 생긴 메서드가 있는가?"를 물을 뿐입니다. (검증됨: 컴파일 및 실행 성공, 출력 `circle=12.5664 square=9.0000`으로 각각 `π·2²`, `3²`과 일치.)

### 메서드를 값으로 저장해 추출하기 {#extracting-a-method-as-a-stored-value}

`fn_eval`은 (즉시 호출이 아니라) 평범한 함수 포인터 값을 만들어내므로, `fn` 변수에 대입해 놓았다가 나중에 호출할 수 있습니다 — 진정한 "바인딩되지 않은 메서드 참조"이며, 정적으로 결정되고, 클로저가 아닙니다:

```c
fn double(&stack const Circle) getArea = fn_eval(c, area);
double ca = getArea(&c);   // 12.5664
```

(검증됨: 컴파일 및 실행 성공. `fn` 선언의 매개변수 타입이 raw `const Circle*`가 아니라 참조 형태 `&stack const Circle`임에 주목하세요 — `fn_eval`의 결과 타입은 메서드의 `self`가 *생성된(emitted)* 시그니처를 위해 [함수](/ko/reference/functions#lowering) 표가 보여주는, C 포인터로 로우어링된 형태가 아니라 SafeC 자체 타입 시스템에서 실제로 표현되는 방식을 그대로 반영합니다.)

### `fn_eval`과 대안들의 비교 {#fn-eval-vs-the-alternatives}

| 메커니즘 | 디스패치 결정 시점 | 트레이트 필요? | 값을 만들어내는가? | 런타임에 이질적인 타입들에 걸쳐 동작하는가? |
|---|---|---|---|---|
| 직접 호출 (`obj.method()`) | 컴파일 타임 | 아니오 | 아니오 — 즉시 호출 | 아니오 |
| 트레이트 바운드 제네릭 (`generic<T: X>`) | 컴파일 타임, 인스턴스마다 | 예 | 아니오 — 즉시 호출 | 아니오 (단형화됨) |
| `fn_eval(obj, shape)` | 컴파일 타임, 인스턴스마다 | 아니오 | **예** — `fn` 값 | 아니오 (단형화됨) |
| 수동 함수 포인터 테이블 (다음 절) | **런타임** | 아니오 | 예 | **예** |

트레이트를 따로 정식화하지 않고도 제네릭 함수가 "이 특정 `T`가 우연히 가지고 있는, 이름이 X인 메서드가 무엇이든" 그대로 집어내길 원할 때, 혹은 메서드를 그 자리에서 호출하는 대신 값으로 들고 다녀야 할 때 `fn_eval`을 쓰세요. 단순히 메서드를 호출하기만 하면 된다면 트레이트 바운드가 더 관용적이고 그 자체로 의도가 잘 드러납니다. 한 컬렉션 안에서 여러 타입이 섞인 진짜 런타임 디스패치가 필요하다면, 제네릭도 `fn_eval`도 그것을 해낼 수 없습니다 — 둘 다 컴파일 타임/단형화 메커니즘입니다 — 아래를 참고하세요.

## 닫힌 집합 다형성: 태그된 유니온 + `match` {#closed-set-polymorphism-tagged-unions-match}

SafeC의 모든 `union`은 태그가 붙어 있습니다(숨겨진 판별자(discriminant)가 있고, 필드 접근이 강제됩니다) — 그래서 진짜 합 타입(sum type)이 됩니다. Rust의 enum이나 sealed class에서 익숙한 "알려진, 닫힌 변형(variant) 집합에 대한 다형성" 스타일이며, 디스패치가 아니라 완전성이 검사된 패턴 매칭으로 처리됩니다:

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

union Result a = Result.ok(42);
handle(a);   // ok: 42
```

*표현식*으로 쓰인 `match`는 반드시 완전성이 증명 가능해야 합니다(모든 변형이 다뤄지거나, `default`/와일드카드 갈래가 있어야 함) — 런타임 검사가 아니라 컴파일러가 모든 경우가 처리됨을 보장합니다. 유니온도 제네릭이 될 수 있습니다: `generic<T, E> union Result { T ok; E err; }`. [타입](/ko/reference/types#union-types)과 [제어 흐름](/ko/reference/control-flow#match-statement)을 참고하세요.

값이 취할 수 있는 "형태(shape)"의 집합이 정의 지점에서 고정되어 있고 알려져 있을 때(항상 정확히 원, 사각형, 삼각형 중 하나인 `Shape`처럼) 이것이 올바른 도구입니다 — 이는 열린(open-ended) 다형성(다운스트림 사용자가 자신만의 새로운 변형으로 확장할 수 있는 `Shape`)과 대비되며, 후자는 제네릭/트레이트/`fn_eval`이 담당하는 영역입니다.

## 명목적 구분: Newtype {#nominal-distinctness-newtype}

디스패치와는 무관하지만, "공유된 기반 클래스 없이 잘못된 타입 통합을 방지하는 OOP"와 관련이 있습니다: `newtype`은 기반 타입으로부터 구분되는 별개의 타입을 만들며, 명시적 캐스트 없이는 호환되지 않습니다 — 구조적으로는 동일하지만 의미상으로는 다른 두 `int`(예를 들어 `UserId`와 `PostId`)가 평범한 `typedef`가 허용하는 것처럼 실수로 서로 바뀌어 쓰이는 것을 막는 데 유용합니다:

```c
newtype UserId = int;
UserId id = (UserId)42;
// int x = id;   // 오류: UserId는 int가 아닙니다
```

[타입](/ko/reference/types#newtype-distinct-types)을 참고하세요.

## 수동 디스패치: 명시적인 "vtable" {#manual-dispatch-explicit-vtables}

위의 모든 메커니즘은 컴파일 타임에 결정됩니다. 그중 어느 것도 `Circle`과 `Square`를 하나의 배열에 담아 같은 호출로 런타임에 디스패치하게 해주지는 않습니다 — 이질적인 컬렉션을 동적으로 디스패치하는 바로 그것이야말로 vtable 기반 언어가 자동으로 만들어주는 것이고, SafeC의 전체적인 입장은 이 비용이 자동으로 숨겨지는 게 아니라 눈에 보여야 한다는 것이기 때문입니다. 실제로 이것이 필요할 때는, 평범한 함수 포인터와 `void*`로 직접 만들면 됩니다 — 컴파일러가 숨겨주지 않을 뿐, 내부적으로 vtable이 하는 일과 정확히 같은 것입니다:

```c
double circle_area(void* obj) {
    struct Circle* c;
    unsafe { c = (struct Circle*)obj; }
    double r;
    unsafe { r = c->radius; }
    return 3.14159265 * r * r;
}
double square_area(void* obj) {
    struct Square* s;
    unsafe { s = (struct Square*)obj; }
    double side;
    unsafe { side = s->side; }
    return side * side;
}

struct Shape {
    void* data;
    fn double(void*) area;
};

int main() {
    struct Circle c; c.radius = 2.0;
    struct Square sq; sq.side = 3.0;

    struct Shape shapes[2];
    unsafe { shapes[0].data = (void*)&c; }
    shapes[0].area = circle_area;
    unsafe { shapes[1].data = (void*)&sq; }
    shapes[1].area = square_area;

    int i = 0;
    while (i < 2) {
        double a = shapes[i].area(shapes[i].data);
        printf("shape[%d] area=%.4f\n", i, a);   // 12.5664, 이어서 9.0000
        i = i + 1;
    }
    return 0;
}
```

(검증됨: 컴파일 및 실행 성공, 두 도형 모두 올바른 출력.) 이것은 진짜 런타임 다형성입니다 — 루프는 `shapes[i]`가 `Circle`인지 `Square`인지 알지도 신경 쓰지도 않습니다 — 그 대가로 치른 것은 접근자마다 명시적인 `unsafe` 캐스트 하나뿐이며, 그 코드는 당신이 직접 작성했고 눈으로 볼 수 있습니다. 이는 또한 SafeC 자체 표준 라이브러리 컨테이너가 내부적으로 쓰는 패턴과 정확히 같습니다([표준 라이브러리 개요](/ko/stdlib/#generic-pattern) 참고): `void*` 기반 구조체에 타입이 있는 래퍼 함수를 얹은 것으로, `generic<T>` 구조체보다 먼저 있었고 지금도 함께 쓰이고 있습니다.

## 빠른 참고표 {#quick-reference}

| 이런 것에서 왔다면... | 이것을 쓰세요 |
|---|---|
| private 필드 + public 메서드를 가진 클래스 | 구조체 + 메서드 |
| 연산자 오버로딩 (`+`, `==`, ...) | 연산자 오버로드 메서드 (고정된 연산자 집합) |
| 제네릭 클래스 / 템플릿 (`List<T>`) | `generic<T>` 구조체 |
| 인터페이스 / 추상 기반 클래스 | 구조적으로 만족되는 `trait` |
| 오버라이드된 가상 메서드를 호출하는 `Base*` 포인터 | 수동 함수 포인터 테이블 (`void*` + `fn` 필드) |
| "이 객체의 메서드를 값으로 달라"는 가벼운 리플렉션 | `fn_eval` |
| 데이터를 가진 sealed 클래스 계층 / 닫힌 enum | 태그된 `union` + 완전성 검사된 `match` |
| 강타입 ID 래퍼 (`UserId` vs raw `int`) | `newtype` |

## SafeC에 없는 것 {#what-safec-doesnt-have}

- **기본적으로 클래스도, 상속도, `virtual`도, vtable도 없습니다** — [설계 철학](/ko/guide/design#why-not-classes)과 [비교](/ko/guide/comparison)를 참고하세요.
- **일반적인 함수/메서드 오버로딩이 없습니다** — 고정된 연산자 집합(`+ - * / % == != < > <= >=`)만 타입별로 여러 정의를 가질 수 있으며, 일반 함수에 대한 C++ 스타일의 "같은 이름, 다른 매개변수 타입" 해석은 없습니다.
- **이질적인 런타임 제네릭 컬렉션이 없습니다** — `generic<T>`는 항상 단형화됩니다. `Vec<Circle>`과 `Vec<Square>`는 서로 무관한 타입입니다. 런타임 타입이 실제로 달라져야 한다면 [수동 디스패치](#manual-dispatch-explicit-vtables)나 태그된 `union`을 쓰세요.
- **`fn_eval`은 "이 형태에 맞는 아무 메서드"가 아니라 이름으로 매칭합니다** — 한 타입에 있는 서로 다른 두 메서드가 우연히 형태 함수의 시그니처와 일치하더라도, `fn_eval`은 시그니처만으로 구분하지 않습니다. 형태 함수의 이름이 대상 메서드의 이름과 반드시 같아야 합니다.
- **런타임 타입 식별(RTTI)이 없습니다** — [컴파일 타임 인트로스펙션](/ko/advanced/introspection)(`typeof`, `fieldcount`)이 컴파일 타임에 주는 것 이상은 없으며, 불투명한 `void*`에 대해 "이게 실제로 무슨 타입인가"를 런타임에 묻는 `dynamic_cast` 스타일의 쿼리는 없습니다.
