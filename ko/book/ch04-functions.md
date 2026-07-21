# 4장: 함수

## 함수 선언하기 {#declaring-functions}

함수는 C와 정확히 똑같이 생겼습니다.

```c
int add(int a, int b) {
    return a + b;
}
```

매개변수와 반환 타입은 이미 예상하는 것과 같은 방식으로 동작합니다 —
기본 타입은 값으로 전달하고, 반환값이 없으면 `void`를 씁니다. 여기까지는
새로운 것이 없습니다. SafeC 함수 이야기에서 흥미로운 부분은 C에는 없는
몇 가지 속성들이며 — 다음 장부터는 매개변수와 반환값이 평범한 값이
아니라 *참조*가 될 때 무슨 일이 일어나는지입니다.

## `pure` 함수 {#pure-functions}

`pure`는 함수가 부작용이 없음을 — 전역 상태에 대한 쓰기도, I/O도,
`pure`가 아닌 함수 호출도 없음을 — 문서화하고(그리고 강제합니다).
컴파일러는 이 보장을 최적화에 활용하며(LLVM의 `readonly`/`nounwind`
속성으로 낮춰서), 단순히 주석을 신뢰하는 대신 컴파일 타임에 이를
강제합니다.

```c
pure double square(double x) {
    return x * x;
}
```

## `must_use` {#must-use}

`must_use`로 표시된 함수는 호출자가 반환값을 버리면 컴파일러 경고를
받습니다 — 결과를 무시하는 것이 거의 항상 버그인 함수(파싱 결과, 할당,
에러 코드 등)에 유용합니다.

```c
must_use int compute(int x) {
    return x * x + 1;
}

compute(5);          // 경고: 'compute'의 반환값을 무시해서는 안 됩니다
                      // (must_use로 표시됨) -- 무시되었을 뿐, 오류는 아님
int result = compute(5);   // 괜찮음, 경고 없음
```

## `noreturn` {#noreturn}

절대 반환하지 않는 함수 — 항상 `exit`/`abort`를 호출하거나 무한
루프를 도는 함수 — 는 이를 명시적으로 표시할 수 있으며, 이를 통해
컴파일러는 호출 지점을 최적화할 수 있습니다("이 호출 이후에 무슨
일이 일어나는가"에 대한 코드를 생성할 필요가 없습니다. 아무것도
일어나지 않으니까요).

```c
#include <stdlib.h>

noreturn void die(const char* msg) {
    printf("PANIC: %s\n", msg);
    abort();
}
```

## 함수 값과 고차 함수 {#function-values-and-higher-order-functions}

함수는 `fn <ReturnType>(<ParamTypes>)` 타입 구문을 사용해 값으로
전달할 수 있습니다.

```c
fn int(int, int) opRef = add;
int result = opRef(3, 4);        // 7 -- opRef를 통해 add(3, 4)를 호출

int apply(fn int(int, int) op, int a, int b) {
    return op(a, b);
}

apply(add, 10, 20);              // 30
```

이것이 표준 라이브러리의 콜백을 받는 모든 API 뒤에 있는 메커니즘입니다 —
스레드의 진입점(`std::spawn`), 테스트 프레임워크에 등록된 테스트 케이스,
HTTP 서버의 요청 핸들러 모두 이런 형태의 평범한 함수 값을 받습니다.

## 제네릭 맛보기 {#a-first-taste-of-generics}

필요해지기 전에 알아 두면 좋을 것 하나 더: 함수는 타입 매개변수에 대해
제네릭할 수 있으며, 한 번 작성되고 타입 검사된 뒤, 실제로 호출될 때마다
각 타입에 대해 별도의 완전히 구체적인 버전으로 컴파일됩니다(단형화 —
Rust와 C++ 템플릿이 사용하는 것과 같은 전략입니다).

```c
generic<T>
T my_max(T a, T b) {
    if (a > b) { return a; }
    return b;
}

my_max(3, 7);         // 7    -- my_max<int>를 인스턴스화
my_max(1.5, 2.7);      // 2.7  -- my_max<double>을 인스턴스화
```

이번 장은 이 예제 하나 이상으로 깊이 들어가지 않습니다 — 제네릭은
구조체, 트레이트/제약, 가변 인자 팩과 여러 방식으로 상호작용하며, 필요할
때 그 자체로 집중해서 읽을 가치가 있습니다. 필요해지면
[제네릭](/ko/reference/generics)을 참고하세요.

## `const`와 `consteval`: 컴파일 타임에 실행되는 함수 {#const-and-consteval-functions-that-run-at-compile-time}

함수 한정자 두 가지가 더 있는데, 둘 다 함수의 본문이 실제로 *언제*
실행되는지에 관한 것입니다. `const` 함수는 주어진 호출 지점의 모든
인자가 그 자체로 컴파일 타임 상수라면 컴파일 타임에 실행*될 수*
있으며, 그렇지 않으면 일반적인 런타임 호출로 대체됩니다.

```c
const int factorial(int n) {
    if (n <= 1) { return 1; }
    return n * factorial(n - 1);
}

const int val = factorial(5);   // 컴파일 타임에 평가됨 -> 120
int dynamic = factorial(x);     // x는 상수가 아님 -> 일반 런타임 호출
```

`consteval`은 더 엄격한 형제입니다: 반드시 컴파일 타임에 실행되어야
하며, 상수가 아닌 인자로 호출하면 조용히 런타임으로 대체되는 대신
컴파일 오류가 발생합니다 — 런타임에는 정말로 계산할 수 없는 룩업
테이블 크기 같은 것에 유용합니다(배열이 존재하기 전에 배열의 크기를
알아야 하기 때문입니다).

```c
consteval int table_size() { return 256; }
int lookup[table_size()];       // OK: 컴파일 타임 상수
```

다음: [리전 이해하기](/ko/book/ch05-understanding-regions) — 이 책 전체가
쌓아 올려 온 장입니다. 지금까지는 "몇 가지 키워드가 추가된 C"였습니다.
여기서부터 SafeC는 더 이상 그런 것이 아니게 됩니다.
