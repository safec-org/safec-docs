# 7장: 열거형, 유니온, 매치

구조체는 관련된 데이터를 함께 묶습니다 — `Point`는 언제나 `x` *그리고*
`y`입니다. 이번 장은 그 반대되는 형태를 다룹니다: *여러 대안 중
하나*인 데이터입니다. 고정된 이름 붙은 값들의 집합을 위한 열거형,
"이 타입들 중 하나이며, 이것이 그 무엇인지"를 위한 태그된 유니온, 그리고
둘 다 안전하게 처리하는 유일한 구성 요소인 `match`를 다룹니다.

## 열거형 {#enums}

명시적인 기반 타입을 가진 열거형은 크기가 명시된 C의 열거형과 똑같이
생겼습니다.

```c
enum Status : int {
    OK = 200,
    NotFound = 404,
    ServerError = 500
}
```

```c
enum Status s = NotFound;
printf("%d\n", (int)s);   // 404
```

C나 C++의 열거형을 써 본 적이 있다면 놀랄 것이 없습니다 — 이름 붙은
정수 상수의 작은 집합이며, 기반 저장 타입을 컴파일러가 고르도록
맡기는 대신 명시적으로 밝힙니다.

## 유니온: 나란히가 아니라 대안 {#unions-alternatives-not-alongsides}

구조체는 "이 필드들 전부, 항상"입니다. `union`은 "이 필드들 중 정확히
하나이며, 언어가 그것이 무엇인지 기억한다"입니다.

```c
union Shape {
    double radius;
    double side;
}
```

이것은 C 스타일 유니온을 선언하는 것처럼 보일 수 있습니다 — 같은
필드, "같은 바이트에 대한 서로 다른 해석"이라는 `|` 모양의 동일한
사고 모델입니다. 하지만 정확히는 그렇지 않습니다. **SafeC의 모든
`union`은 태그된 유니온입니다**: 필드들과 함께, 컴파일러는 실제로
어느 것이 살아 있는지 기록하는 숨겨진 마커를 유지하며, 값을 다시
꺼내는 유일한 방법은 `match`를 통하는 것입니다 — 순수 C의 타입 펀닝
유니온이 허용하는 방식으로(그리고 실제 C 버그들이 실수로 정기적으로
악용하는 방식으로) 여러분이 설정한 것과 다른 필드를 실수로 읽을 방법이
없습니다.

유니온 값을 **생성**할 때는 타입과 채우는 필드를 이름 지어, 생성자
호출처럼 씁니다.

```c
union Shape circle = Shape.radius(2.0);
union Shape square = Shape.side(3.0);
```

**읽기**는 `match`를 사용하며, 변형(variant)마다 `.`이 앞에 붙은
패턴을 씁니다 — 이 앞의 점이 이것을 (`enum`에 대해 매칭할 때 쓰는
평범한 식별자 패턴이나, [앞 장](/ko/book/ch05-understanding-regions)의
`null`/`some(x)` 패턴이 아닌) 태그된 유니온 패턴으로 표시하는
부분입니다.

```c
double area(union Shape s) {
    return match (s) {
        case .radius(r): 3.14159 * r * r,
        case .side(sd):  sd * sd,
    };
}

area(circle);   // 12.56636
area(square);   // 9.0
```

`case .radius(r):` 안에서 `r`은 페이로드의 타입(여기서는 `double`)으로
직접 바인딩됩니다 — 수동 언래핑도 필요 없고, `r`을 실수로 `side`
변형인 것처럼 읽을 방법도 없습니다. 컴파일러는 태그로부터 이미 어느
필드가 살아 있는지 알고 있으며, `match`는 그저 그것을 물어보는
구문일 뿐입니다.

## 문으로서의 `match` vs. 식으로서의 `match` {#match-as-a-statement-vs-an-expression}

이제 `match`가 가질 수 있는 두 가지 형태를 모두 보았습니다. **문**으로
쓰일 때는 각 분기가 블록을 실행하며, 가능한 모든 케이스를 커버해야
한다는 요구 사항이 없습니다 — 커버되지 않은 케이스는 그냥 아무것도
하지 않고 통과할 뿐이며, 이는 오류가 아니라 경고에 불과할 만큼
무해합니다.

```c
match (day) {
    case Monday: printf("start of the week\n");
    case Friday: printf("almost done\n");
    // 그 외 요일: 조용히 통과 -- 컴파일러 경고일 뿐, 오류 아님
}
```

**식**으로 쓰일 때 — 위의 `area`가 매칭된 분기가 평가하는 값을
반환하듯이, 값을 만들어내는 데 쓰일 때 — 는 모든 케이스를 진짜로
커버해야 합니다. 아무것도 매칭되지 않는 경로에서 만들어낼 만한
합리적인 값이 없기 때문입니다. 그래서 `area`의 두 변형(`radius`,
`side`)만으로 충분했던 것입니다 — 필드가 두 개뿐인 유니온은 태그도
두 개뿐이므로, 둘 다 커버하는 것이 *곧* 완전성입니다. 모든 케이스가
커버되었는지 확신할 수 없거나, 전부 나열하고 싶지 않을 때는 언제든
`default:` 분기를 추가하세요.

```c
int severity_of(enum Status s) {
    return match (s) {
        case OK:      0,
        case NotFound: 1,
        default:       2,   // ServerError와 그 외 모든 것을 잡음
    };
}
```

## 범위와 다중 값 패턴 {#range-and-multi-value-patterns}

`match`는 위의 `Status` 열거형이 암시하는 상태 코드/버킷 로직과 정확히
같은 종류에 유용한 정수 범위와 분기당 여러 값도 처리합니다.

```c
match (status_code) {
    case 200:      printf("OK\n");
    case 400..499: printf("client error\n");
    case 500..599: printf("server error\n");
    default:       printf("other\n");
}

match (day) {
    case 1, 7:          printf("weekend\n");
    case 2, 3, 4, 5, 6:  printf("weekday\n");
}
```

교차(alternation)에는 파이프가 아니라 쉼표(`case 1, 7:`)를 쓴다는
점에 주의하세요. 범위는 정수뿐 아니라 문자 패턴에서도 동작합니다 —
`case 'a'..'z':` — 더 많은 범위 패턴 예제는 [제어
흐름](/ko/reference/control-flow)을 참고하세요.

다음: [에러 처리](/ko/book/ch08-error-handling) — `?T` 옵셔널은, 내부적으로는
이번 장에서 여러분이 직접 만드는 법을 배운 두 변형(variant)짜리 태그된
유니온(`none`/`some(x)`)과 정확히 같은 것입니다. 그것이 바로 5장에서
이미 그 `match` 구문이 낯익어 보였던 이유입니다.
