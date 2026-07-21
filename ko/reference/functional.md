# 함수형 프로그래밍

SafeC는 함수형 프로그래밍에서 구체적이면서도 개별적으로 유용한 아이디어 몇 가지를 빌려왔습니다 — 전체 호출 그래프가 아니라 키워드 하나만 읽어도 확인할 수 있는 순수성(purity), 컴파일러가 완전성을 강제하는 합 타입(sum type), 기본적으로 불변인 바인딩, 평범한 값으로 취급되는 함수까지 — 가비지 컬렉터도, 클로저도, 런타임도 없이 말입니다. 이 페이지에 나오는 각 메커니즘은 애초에 "함수형이라서"가 아니라 다른 이유로 존재합니다(대부분 결정론(determinism)과 컴파일 타임 검증이라는, SafeC의 실제 설계 우선순위 때문입니다 — [설계 철학](/ko/guide/design) 참고). 이 페이지는 그것들을 FP라는 렌즈로 한데 모았을 뿐입니다. 그렇게 보는 것이 이 기능들이 결국 무엇을 이루는지 파악하는 데 실제로 유용하기 때문입니다.

이것이 "C 문법을 쓰는 Haskell"이라고 생각하기 전에 [SafeC에 없는 것](#what-safec-doesnt-have)을 먼저 읽어보세요 — 그렇지 않으며, FP의 대표적인 요소 여럿이 의도적으로 빠져 있습니다.

## 값으로서의 함수 {#functions-as-values}

함수는 `fn` 타입 — `fn 반환타입(매개변수타입들)` — 을 통해 평범한 값처럼 참조되고, 저장되고, 전달되고, 반환될 수 있습니다:

```c
int add(int a, int b) { return a + b; }

fn int(int, int) op = add;
int result = op(3, 4);        // 7
```

### 고차 함수 {#higher-order-functions}

다른 함수를 받거나 반환하는 함수는 다른 매개변수/반환 타입과 완전히 똑같이 동작합니다 — `fn` 타입 자체 말고는 특별한 문법이 필요 없습니다:

```c
generic<T>
void map_inplace(T* arr, int n, fn T(T) f) {
    int i = 0;
    while (i < n) {
        unsafe { arr[i] = f(arr[i]); }
        i = i + 1;
    }
}

generic<T>
T fold(T* arr, int n, T init, fn T(T, T) f) {
    T acc = init;
    int i = 0;
    while (i < n) {
        T v;
        unsafe { v = arr[i]; }
        acc = f(acc, v);
        i = i + 1;
    }
    return acc;
}

pure int square(int x) { return x * x; }
pure int add(int a, int b) { return a + b; }

int main() {
    int nums[5] = {1, 2, 3, 4, 5};
    int* p;
    unsafe { p = (int*)&nums[0]; }

    map_inplace(p, 5, square);              // nums -> 1 4 9 16 25
    int total = fold(p, 5, 0, add);          // 55
    printf("sum of squares = %d\n", total);
    return 0;
}
```

(검증됨: 컴파일 및 실행 성공, `1 4 9 16 25`와 `sum of squares = 55`를 출력.) SafeC에는 배열이나 슬라이스에 대한 내장 `map`/`filter`/`fold`가 없습니다 — 위의 `map_inplace`/`fold`는 순수 C에서 직접 작성하듯 손수 만든 것입니다. 이런 형태의 코드를 얻는 관용적인 방법은 제네릭 고차 함수 헬퍼를 한 번 직접 작성해 두는 것입니다. 대신 쓸 수 있는 표준 라이브러리 콤비네이터 집합 같은 것은 없습니다.

::: warning 제네릭 타입 추론과 포인터
제네릭 타입 추론은 `T`가 평범한 매개변수로 나타날 때(위의 `T a, T b`는 `max(3, 7)`에서 `T`를 바로 추론합니다) 동작하며, `T*`/`[]T` 형태로 나타날 때는 **인자가 이미 포인터/슬라이스 타입일 때만** 동작합니다 — 크기가 고정된 배열 인자(`int nums[5]`)를 `T*` 매개변수에 직접 넘기면 `T`가 추론되지 않습니다. 먼저 명시적인 `int*`를 얻어서(`unsafe { p = (int*)&nums[0]; }`, 위와 같이) 그것을 넘기세요. 이는 스타일 취향이 아니라 실제로 검증된 추론 한계입니다.
:::

기본 `fn` 타입 문법은 [함수](/ko/reference/functions#function-pointers)를 참고하세요.

## 순수성 (Purity) {#purity}

`pure` 함수는 부작용(side effect)이 없다는 것을 컴파일러가 검사하는 약속입니다 — 단순한 문서화가 아니라 강제되고 실제로 활용됩니다: SafeC는 `pure` 함수를 LLVM의 `readonly`/`nounwind` 속성으로 로우어링하며, 그 덕분에 컴파일러가 다른 방식으로는 안전하게 할 수 없는 최적화(재정렬, 호출 사이의 공통 부분식 제거 등)를 할 수 있게 됩니다 — Haskell이 기본적으로 순수성에서 얻는 것과 같은 종류의 이득을, 여기서는 함수 단위로 선택적으로 얻는 것입니다:

```c
pure double square(double x) {
    return x * x;
}
```

순수 함수는 전역 상태를 변경할 수도, I/O를 수행할 수도, `pure`가 아닌 함수를 호출할 수도 없습니다 — 위반 시 리뷰어가 잡아내야 하는 관례가 아니라 컴파일 에러입니다. [함수](/ko/reference/functions#pure-functions)를 참고하세요.

## 컴파일 타임 평가 {#compile-time-evaluation}

서로 연관된 세 가지 메커니즘이 계산을 런타임에서 컴파일 타임으로 옮깁니다 — FP 언어가 참조 투명성(referential transparency)에서 얻는 "순수 함수는 언제든 평가될 수 있다"는 아이디어에 대해, 최적화기의 재량에 맡기는 대신 명시적으로 만든 SafeC의 답입니다:

- **`const` 함수**는 모든 인자가 컴파일 타임 상수라면 컴파일 타임에 실행*될 수 있습니다*. 그렇지 않으면 평범하게 런타임에 호출됩니다.
- **`consteval` 함수**는 반드시 컴파일 타임에 실행*되어야 합니다* — 상수가 아닌 인자를 넘기면 런타임 호출로 대체되는 게 아니라 컴파일 에러가 됩니다.
- **`if const`**는 컴파일 타임 조건으로 분기하며, 선택되지 않은 분기는 완전히 제거되어 타입 검사조차 되지 않습니다 — `#ifdef` 기반 조건부 컴파일에 대한 SafeC의 대체재로, 전처리기와 달리 스코프를 인식합니다.

```c
const int factorial(int n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}
const int val = factorial(5);   // 컴파일 타임에 평가되어 -> 120

consteval int table_size() { return 256; }
int lookup[table_size()];        // OK: 여기서는 컴파일 타임 상수가 요구됨

const int PLATFORM = 1;
void init() {
    if const (PLATFORM == 1) { init_linux(); }
    else { init_generic(); }
}
```

[함수](/ko/reference/functions#const-functions), [함수](/ko/reference/functions#consteval-functions), [제어 흐름](/ko/reference/control-flow#if-const-compile-time-branching)을 참고하세요.

## 불변성 {#immutability}

지역 바인딩에 붙는 `const`는 FP의 "기본적으로 불변" 입장에 해당하며, 전역적으로 가정되는 대신 변수 단위로 선택됩니다 — "불변 바인딩을 선언합니다. 초기화 이후에는 값을 변경할 수 없습니다"라고 컴파일 타임에 검사됩니다:

```c
const int MAX_SIZE = 1024;
// MAX_SIZE = 2048;   // 오류: const에는 대입할 수 없음
```

[리터럴과 한정자](/ko/reference/literals#const)를 참고하세요.

이는 SafeC의 보로우 체커(borrow checker) 스타일 별칭(aliasing) 규칙과 짝을 이룹니다 — **가변 참조는 하나만 존재하거나, 아니면 불변 참조는 몇 개든 존재할 수 있으며, 둘이 동시에 존재할 수는 없습니다.** 이는 불변성만이 유일한 선택지이기 때문에 FP 언어가 얻는 "다른 무언가가 몰래 이 값을 바꾸고 있을 리 없다"는 보장을, 모든 것을 실제로 불변으로 만들지 않고도 불변 데이터에 대해 제공합니다:

```c
int x = 42;
&stack const int a = &x;   // 불변 참조(대여)
&stack const int b = &x;   // OK: 여러 개의 불변 참조가 동시에 존재할 수 있음
```

[안전성](/ko/reference/safety#aliasing-rules-borrow-checker)을 참고하세요.

## 대수적 데이터 타입 (ADT) {#algebraic-data-types}

합 타입(태그된 `union`)과 곱 타입(`tuple`)을, `match`를 통해 완전성이 검사된 형태로 읽어냅니다 — Rust의 `enum`/`match`나 Haskell의 ADT/case 표현식에서 익숙한, "타입 + 패턴 매칭" 스타일입니다:

```c
union Result {
    int ok;
    int err;
}

int unwrap_or(union Result r, int fallback) {
    return match (r) {
        case .ok(v):  v,
        case .err(_): fallback,
    };
}

tuple(int, double) pair = (42, 3.14);
int first = pair.0;
double second = pair.1;
```

(`match` 문(statement)과 달리) `match` **표현식(expression)**은 반드시 완전성이 증명 가능해야 합니다 — 모든 변형이 다뤄지거나 `default`/와일드카드 갈래가 있어야 하며, 그렇지 않으면 런타임에 그냥 통과되는 게 아니라 컴파일 에러가 됩니다. `union`과 `tuple`은 둘 다 제네릭이 될 수 있습니다: `generic<T, E> union Result { T ok; E err; }`는 임의의 타입 쌍에 대한 진짜 단형화된 합 타입입니다. [타입](/ko/reference/types#union-types), [타입](/ko/reference/types#tuple-types), [제어 흐름](/ko/reference/control-flow#match-statement)을 참고하세요.

## 옵셔널 값과 전파 {#optional-values-and-propagation}

`?T`는 SafeC의 Option 형태 타입입니다 — "존재할 수도, 존재하지 않을 수도 있음"을 나타내며, `{T, i1}` 쌍으로 로우어링되고, 똑같이 완전성이 검사되는 `match`(`none`/`some(v)` 갈래) 또는 작고 고정된 명시적 접근자 집합을 통해 읽어냅니다:

```c
?int safe_div(int a, int b) {
    if (b == 0) { return null; }   // 비어 있는 경우
    return a / b;                  // 암묵적으로 T -> ?T로 감싸짐
}

int main() {
    int r1 = match (safe_div(10, 2)) {
        case none:    -1,
        case some(v): v,
    };
    int r2 = match (safe_div(10, 0)) {
        case none:    -1,
        case some(v): v,
    };
    printf("10/2=%d 10/0=%d\n", r1, r2);   // 10/2=5 10/0=-1
    return 0;
}
```

(검증됨: 컴파일 및 실행 성공, `10/2=5 10/0=-1` 출력.) `try`는 `?T`를 언래핑(unwrap)하고, 실패 시 비어 있는 경우를 즉시 *호출자(caller)*에게 전파합니다 — Rust의 `?` 연산자나 `Maybe`/`Either` 모나드의 bind와 같은 정신을 가진, 단축(short-circuit) 방식의 에러 전파이며, 이를 감싸는 함수의 반환 타입 처리를 호출 지점마다 일일이 적어줄 필요가 없습니다:

```c
?int parse_config(const char *path) {
    ?int fd = open_file(path);
    int file = try fd;          // fd가 비어 있으면 즉시 null을 반환
    ?int value = read_int(file);
    return try value;
}
```

**없는** 것: `?T`에는 `.map()`/`.and_then()` 같은 모나드 콤비네이터 메서드가 없습니다 — 있는 것은 `is_none()`, `.default(fallback)`, `match`, 그리고 (`unsafe` 안에서의) 직접 언래핑뿐입니다. 실패 가능한 연산을 연쇄적으로 처리하려면 콤비네이터를 조합하는 게 아니라 각 단계마다 `try`를 써 주어야 합니다. [타입](/ko/reference/types#optional-types)과 [제어 흐름](/ko/reference/control-flow#try-operator)을 참고하세요.

## 매개변수 다형성 {#parametric-polymorphism}

제네릭 함수(`generic<T>`)는 SafeC의 매개변수 다형성 메커니즘입니다 — Haskell의 `a -> a` 타입 변수나 ML의 `'a`와 핵심 아이디어는 같지만, 런타임에 균일하게 표현되는 대신 컴파일 타임에 완전히 단형화됩니다. 트레이트로 제약된 제네릭과 가변 인자 타입 팩을 포함한 전체 내용은 [다형성과 OOP](/ko/reference/polymorphism#parametric-polymorphism-generics)와 [제네릭](/ko/reference/generics)에서 다룹니다 — 어느 쪽에서 봐도 정확히 같은 기능이라 여기서 다시 반복하지는 않습니다. 그저 왜 유용한지를 보는 관점이 다를 뿐입니다.

## SafeC에 없는 것 {#what-safec-doesnt-have}

- **클로저나 람다가 없습니다.** `fn` 값은 캡처된 환경이 없는 평범한 함수 포인터입니다 — "평범한 `HttpHandler` 함수 포인터에는 클로저 슬롯이 없다"([stdlib/http](/ko/stdlib/http)). [동시성](/ko/reference/concurrency) 문서는 이를 직접적으로 명시합니다: "클로저도 런타임 스케줄러도 없습니다 — 동시성은 명시적이고 결정론적입니다." 클로저가 상태를 캡처했을 법한 자리에는, 그 상태를 추가 인자로 명시적으로 전달하세요.
- **커링이나 부분 적용이 없습니다.** 모든 함수 호출은 모든 인자를 다 넘겨줘야 합니다. 일부 인자만 고정해 나머지를 위한 호출 가능한 값을 돌려받는 내장 메커니즘은 없습니다.
- **컬렉션에 대한 내장 `map`/`filter`/`reduce`가 없습니다.** 순수 C에서 하듯, `generic<T>` 헬퍼를(위의 `map_inplace`/`fold`처럼) 한 번 직접 작성하세요.
- **지연 평가(lazy evaluation)가 없습니다.** C와 마찬가지로 모든 평가는 즉시(eager) 이뤄집니다 — 무한 리스트도, 지연 계산되는 thunk도 없습니다.
- **`match`/`try`/`.default()` 외의 모나드 콤비네이터가 없습니다.** `?T`는 구조적으로는 Option 타입처럼 동작하지만 `.map()`/`.and_then()`/`.or_else()`는 제공하지 않습니다 — [옵셔널 값과 전파](#optional-values-and-propagation)를 참고하세요.
- **패턴 매칭의 완전성 검사는 `match` 표현식에만 적용되고, 문(statement)에는 적용되지 않습니다.** `default`/와일드카드 갈래가 없는 `match` *문*은 에러가 아니라 경고만 냅니다("완전하지 않을 수 있음") — [제어 흐름](/ko/reference/control-flow#match-statement)을 참고하세요.
