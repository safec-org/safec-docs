# 6장: 구조체와 메서드

## 구조체 정의하기 {#defining-a-struct}

`struct`는 C에서 기대하는 방식대로 동작하며, 여기에 메서드를 붙일 수
있는 능력이 더해집니다.

```c
struct Point {
    double x;
    double y;

    double length() const;
    void scale(double s);
};
```

구조체 본문 안에서 메서드는 *선언*됩니다 — 이름, 매개변수, 반환 타입,
그리고 `const`인지 여부 — 하지만 정의되지는 않습니다. 정의는 바깥에
있으며, `Type::method`로 한정합니다.

```c
double Point::length() const {
    return self.x * self.x + self.y * self.y;
}

void Point::scale(double s) {
    self.x = self.x * s;
    self.y = self.y * s;
}
```

`self`는 암묵적입니다 — 매개변수로 선언하지 않지만, 모든 메서드 본문
안에서 사용 가능하며 메서드가 호출된 인스턴스를 가리킵니다. `const`
메서드는 `self`를 읽기 전용으로 받습니다(필드를 읽을 수는 있지만
대입할 수는 없으며, 다른 어떤 `const` 바인딩과도 같은 방식으로
강제됩니다). `scale`처럼 `const`가 아닌 메서드는 `self`를 통해
변경할 수 있습니다.

메서드 호출은 익숙한 `.` 구문을 사용합니다.

```c
struct Point p = {3.0, 4.0};
double len = p.length();     // 25.0 (이 length()는 제곱 길이를 반환합니다)
p.scale(2.0);
// p는 이제 {6.0, 8.0}
```

내부적으로 메서드는 그저 명시적인 `self` 매개변수가 앞에 붙은 평범한
함수일 뿐입니다 — `p.length()`는 `Point_length(&p)`와 같은 형태로
낮춰지고, `p.scale(2.0)`은 `Point_scale(&p, 2.0)`으로 낮춰집니다.
마법 같은 일은 전혀 일어나지 않습니다. C에서 손으로 작성했을 "구조체
+ 그것에 대한 포인터를 받는 자유 함수들" 패턴에 문법 설탕을 얹은
것뿐입니다.

## 연산자 오버로딩 {#operator-overloading}

구조체는 `operator+`(등)라는 이름의 메서드를 정의함으로써 자신의
타입에 대해 `+`, `-`, `*`, `/`, `%`, 그리고 비교 연산자가 무엇을
의미하는지 정의할 수 있습니다.

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
```

```c
struct Vec2 a = {1.0, 2.0};
struct Vec2 b = {3.0, 4.0};
struct Vec2 c = a + b;      // {4.0, 6.0} -- Vec2::operator+ 호출
```

이는 작은 수치/기하 타입(2D/3D 벡터, 복소수, 고정소수점 값)을 다루기
편하게 만들어 주는 정확히 그런 종류의 기능입니다 — 산술 연산을 메서드로
한 번만 작성한 뒤, 코드베이스 곳곳에 흩어진 `vec2_add(a, b)` 호출
대신 어디서든 평범한 연산자 구문을 사용할 수 있습니다.

## 구조체와 리전 {#structs-and-regions}

앞 장과 연결 지어 짚어 둘 만한 것 하나: 구조체의 필드는 각자 독립적인
리전을 갖지 않습니다 — 구조체 전체는 그 변수를 담고 있는 리전에
살게 됩니다. 지역 변수로 선언된 `struct Point p`는 전체가 `&stack`
리전 데이터이고, `new<Pool>`로 할당된 `&arena<Pool> struct Point`는
필드를 포함해 아레나 안에 삽니다. 스스로 명시적으로 그렇게 만들지
않는 한, 구조체의 한 필드만 몰래 힙에 할당되고 나머지는 스택에 있는
시나리오는 존재하지 않습니다(예를 들어 필드 자체가 `&heap T` 참조라면,
그 필드는 자신이 속한 구조체와는 별개로 추적할 가치가 있는 독립적인
라이프타임을 갖습니다).

다음: [열거형, 유니온, 매치](/ko/book/ch07-enums-and-match) — 구조체는
*관련된* 데이터를 함께 묶어 주고, 열거형과 유니온은 데이터의 *대안적인*
형태를 표현하며, `match`는 모든 대안을 안전하게 처리하는 방법입니다.
