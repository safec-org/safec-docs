# 제네릭

SafeC의 제네릭은 컴파일 타임에만 존재한다. 모든 제네릭 함수는 완전히
단형화된다 — 컴파일러는 사용된 구체 타입마다 별도의 사본을 생성한다.
vtable도, 타입 소거도, 런타임 디스패치도 없다.

구조체와 유니온도 제네릭이 될 수 있다 — `generic<T> struct Pair { T first; T second; };`
— 제네릭 함수와 마찬가지로 구체 타입 인자마다 완전히 단형화된다.
구조체 메서드와 별도 파일(out-of-line) 정의에 대해서는 아래
[제네릭 메서드](#generic-structs-and-methods)를 참고. 표준 라이브러리의
컨테이너도 이 방식으로 만들어졌다(`Vec<T>`, `HashMap<K, V>`, `BST<K, V, C>`, …) —
[컬렉션](/ko/stdlib/collections) 참고.

## 제네릭 함수 {#generic-functions}

반환 타입 앞에 `generic<T>`를 붙여 제네릭 함수를 선언한다:

```c
generic<T>
T max(T a, T b) {
    if (a > b) return a;
    return b;
}
```

컴파일러는 호출 지점에서 타입 인자를 추론한다:

```c
int m1 = max(3, 7);           // max<int>를 인스턴스화
double m2 = max(1.5, 2.7);    // max<double>을 인스턴스화
```

각 인스턴스화는 생성된 코드에서 별도의 함수를 만들어낸다.

## 트레이트와 제약된 제네릭 {#traits-and-constrained-generics}

타입 매개변수는 트레이트로 제약을 걸어 받아들이는 타입을 제한할 수
있다:

```c
generic<T: Numeric>
T clamp(T val, T lo, T hi) {
    if (val < lo) return lo;
    if (val > hi) return hi;
    return val;
}
```

`Numeric` 제약은 `T`가 산술과 비교를 지원하도록 보장한다. 컴파일러는
이 제약을 만족하지 못하는 타입으로의 인스턴스화를 거부한다.

### 트레이트 선언하기 {#declaring-a-trait}

트레이트는 이름이 붙은 메서드 시그니처의 집합이다:

```c
trait Drawable {
    void draw() const;
}
```

SafeC의 트레이트는 명목적(nominal)이 아니라 **구조적**("덕 타이핑")이다
— `impl Trait for Type` 블록이 없다. 구조체는 이름과 시그니처가
일치하는 메서드를 정의하는 즉시 자동으로 트레이트를 만족시킨다:

```c
struct Circle {
    double radius;
    void draw() const;   // Drawable을 만족시킴 -- 그 외에는 아무것도 필요 없음
}
void Circle::draw() const { printf("circle r=%.1f\n", self.radius); }

generic<T: Drawable> void render(T shape) { shape.draw(); }
render(circle);   // OK: Circle이 일치하는 draw() const를 가짐
```

적합성(conformance)은 단형화 도중 호출 지점에서 검사된다: `T`에
대입된 구체 타입이 트레이트 바운드가 요구하는 모든 메서드를 갖고
있지 않으면, (어쩌면 멀리 떨어진, 라이브러리 내부의) 제네릭 함수
본문이 그 메서드를 사용하는 지점이 아니라 바로 그 인스턴스화 지점에서
컴파일 에러로 실패한다.

### 내장 트레이트 {#built-in-traits}

| 트레이트 | 요구되는 연산 |
|-------|---------------------|
| `Numeric` | 산술 (`+`, `-`, `*`, `/`) |
| `Eq` | `==`, `!=` |
| `Ord` | `<`, `>`, `<=`, `>=` |
| `Add` | `+`만 |
| `Sub` | `-`만 |
| `Mul` | `*`만 |
| `Div` | `/`만 |

두 가지 트레이트는 사용자 정의 메서드가 아니라 타입 시스템 자체에
의해 구조적으로 만족된다: `Indexed`(배열, 슬라이스, `vec<T,N>` 타입 —
`[]`로 사용 가능한 모든 것)와 `Pointer`(원시 포인터와 참조 타입).

## 가변 인자 제네릭 {#variadic-generics}

SafeC는 `generic<T...>`를 사용한 가변 인자 타입 팩을 지원한다. 가변
인자 제네릭 매개변수는 어떤 타입이든 임의 개수의 인자를 받으며,
`sizeof...(T)`는 팩의 크기를 알려준다:

```c
generic<T...>
unsigned long count_args(T... args) {
    return sizeof...(T);
}

unsigned long n = count_args(1, 2.0, 'a');   // n은 3
```

팩은 맨몸(bare) 인자로 이름을 지정함으로써 — `args...`가 아니라
`args` — 다른 호출로 전달(forward)된다. 이는 각 단형화된 호출 지점에서
팩의 실제 인자들로 확장된다:

```c
int sum3(int a, int b, int c) { return a + b + c; }

generic<T...>
int forward_sum(T... args) {
    return sum3(args);   // 3개 원소 팩의 경우 sum3(args0, args1, args2)로 확장됨
}

int n = forward_sum(1, 2, 3);   // n == 6
```

팩의 원소는 리터럴(상수) 인덱스로도 첨자화할 수 있다 — `args[0]`,
`args[1]`, ... — 단형화 시점에 대응하는 인자로 해석된다:

```c
generic<T...>
int first_int(T... args) {
    return args[0];
}
```

::: warning 리터럴 인덱스만 가능
`args[i]`는 `i`가 런타임 변수가 아니라 컴파일 타임 정수 리터럴일 것을
요구한다 — 팩은 코드 생성 시점에 실제로 첨자화 가능한 배열로 존재하는
것이 아니라, 각 `args[N]`이 단형화 도중 N번째 실제 인자로 재작성될
뿐이다. 런타임 인덱스로 팩을 순회하는 것은 지원되지 않는다 — 대신
`sizeof...(T)` 재귀를 작성하거나 필요한 각 위치에 대해 리터럴 인덱스
형태를 반복하라.
:::

## 단형화 {#monomorphization}

컴파일러가 제네릭 함수 호출을 만나면 다음을 수행한다:

1. 호출 지점의 인자 타입으로부터 타입 인자를 **추론**한다
2. 타입 매개변수를 구체 타입으로 치환하며 함수 AST를 **깊은 복제**한다
3. 이름을 **맹글링**한다: `max<int>`는 `__safec_max_int`가 된다
4. 단형화된 사본을 전체 시맨틱 분석 파이프라인을 통해 **분석**한다
5. 서로 다른 인스턴스화마다 코드를 **내보낸다**

제네릭 함수 본문은 첫 번째 시맨틱 분석 패스에서는 **건너뛰어진다** —
단형화 이후 구체 타입으로만 타입 검사된다.

### 이름 맹글링 {#name-mangling}

단형화된 함수는 `__safec_<name>_<type>` 패턴을 따른다:

| SafeC | 맹글링된 이름 |
|-------|-------------|
| `max<int>` | `__safec_max_int` |
| `max<double>` | `__safec_max_double` |
| `Pair<int>` | `__safec_Pair_int` |

인스턴스화는 `linkonce_odr` 링키지로(ELF/COFF에서는 COMDAT 안에) 생성된다: `Vec<int>`를 사용하는 모든 번역 단위가 자기 사본을 내보내고, 링커는 정확히 하나만 남긴다. 제네릭 컨테이너가 헤더 안에서만 존재할 수 있는 것은 이 덕분이다.

### 코드 크기 {#code-size}

각 고유한 인스턴스화는 별도의 코드를 생성한다. `max`를 5개의 서로
다른 타입으로 인스턴스화하면 5개의 별도 함수가 만들어진다. 이는
바이너리 크기를 런타임 성능(간접 참조 없음)과 맞바꾸는 것이다.

## 제네릭 구조체와 메서드 {#generic-structs-and-methods}

구조체(또는 유니온)는 제네릭 함수와 동일한 방식으로 선언된, 자신만의
타입 매개변수를 가질 수 있다:

```c
generic<T>
struct Container {
    T value;
    int count;

    T get() const;
    void set(T new_value);
};

struct Container<int> c;
c.set(42);
int v = c.get();   // v == 42
```

위의 `T get() const;`/`void set(T new_value);`는 본문 내(in-body)
메서드 *선언*이다 — 제네릭이 아닌 구조체의 메서드와 동일한 방식으로
본문을 별도 파일에 작성하되, 각 정의 위에 `generic<T>` 줄을 하나씩
추가하면 된다. out-of-line 한정자는 그냥 평범한 구조체 이름
(`Container::get()`)이며, `Container<T>::get()`이 **아니다** — 타입
매개변수는 이미 `generic<T>` 줄로부터 스코프에 들어와 있으므로 메서드
소유자 이름에서 반복하지 않는다:

```c
generic<T>
T Container::get() const {
    return self.value;
}

generic<T>
void Container::set(T new_value) {
    self.value = new_value;
}
```

### 타입 인자는 대상으로부터 추론된다 {#type-arguments-are-inferred-from-the-target}

`f<int>(...)` 같은 호출 문법은 없다. 타입 인자는 항상 추론된다. 먼저 인자를 매개변수와 대조하고, 그래도 바인딩되지 않은 타입 매개변수는 호출의 *대상 타입* — 초기화되거나 대입되는 변수의 선언 타입 — 에서 가져온다:

```c
struct Vec<int> v = std::vec_new();                  // T from the declaration
struct HashMap<int, long long> m = std::map_new();   // K and V likewise
?&heap Packet p = std::pool_alloc_t(&pool);          // T = Packet
```

대상이 없는 호출(단독 문장, `match` 대상, 다른 제네릭의 인자)은 반환 타입에만 나타나는 매개변수를 추론할 수 없다: 먼저 타입이 지정된 변수에 바인딩하라.

nullable 참조 매개변수(`?&T`)에 대응하는 단독 `null` 인자는 아무것도 바인딩하지 않으며, `T`는 다른 인자에서 온다: `std::thread_create(&tid, worker, null)`은 `worker`의 시그니처에서 `T`를 가져온다.

추론은 인자가 매개변수 *패턴*에 들어맞는다는 것만 증명한다. 그 다음 각 인자는 비제네릭 호출과 똑같이 인스턴스화된 매개변수 타입에 대해 검사된다 — `&T`가 기대되는 곳에 구조체 값을 넘기면 조용한 복사가 아니라 오류다.

### 중첩된 타입 인자 {#nested-type-arguments}

타입 인자는 자유롭게 중첩되며, `>>`는 두 목록을 닫는다: `struct NodePool<struct ListNode<T>>`, `struct Vec<struct Vec<int>>`. 템플릿은 자기 자신의 인스턴스를 언급할 수 있고(`ListNode` 안의 `?&heap struct ListNode<T> next;`), 제네릭 구조체 안에서 타입 매개변수의 배열도 허용된다(`V vals[8];`).

### 타입 매개변수를 통한 컴파일 타임 디스패치 {#compile-time-dispatch-through-a-type-parameter}

타입 매개변수는 제네릭 코드가 메서드를 호출하는 *정책* 타입을 나타낼 수 있다. 단형화 후 그 호출은 해당 타입 메서드에 대한 직접 호출이 되고, 옵티마이저가 이를 인라인한다 — 함수 포인터를 넘기는 것의 비용 없는 대안이다:

```c
generic<T> struct Less { int unused_; int cmp(const &T a, const &T b) const; };
generic<T> int Less::cmp(const &T a, const &T b) const { return *a < *b ? -1 : (*a > *b ? 1 : 0); }

generic<K, V, C>          // C: any type with a matching cmp method
struct BST { C ord; /* ... */ int insert(K key, V val); };

// inside BST::insert:  int c = self.ord.cmp(&key, &n.key);   // inlined for Less<int>
```

`std/cmp.h`는 `Less<T>`, `Greater<T>`, `FnOrder<T>`(런타임 비교자)를 제공하며, `BST<K, V, C>`와 `vec_sort_by`가 이를 사용한다. `if const`와 조합하면 타입 인자의 속성에 따라 컴파일 타임에 코드를 선택할 수 있다. 죽은 분기는 타입 검사조차 되지 않는다:

```c
generic<K>
unsigned int hash_key(const &K key) {
    if const (sizeof(K) == 4UL || sizeof(K) == 8UL) {
        // ... hash the key as one machine word ...
    }
    // ... byte-wise fallback for other sizes ...
}
```

## 리전과의 상호작용 {#interaction-with-regions}

제네릭 타입은 리전 한정 참조와 결합될 수 있다:

```c
generic<T>
?T find_in_slice([]T haystack, T needle) {
    for (int i = 0; i < haystack.len; i++) {
        if (haystack[i] == needle) return some(haystack[i]);
    }
    return none;
}
```

제네릭 코드 내부의 참조에 붙는 리전 한정자는 제네릭이 아닌 코드와
동일한 규칙을 따른다. 컴파일러는 단형화 이후에 리전 안전성을 검사한다.

## 한계 {#limitations}

- **런타임 디스패치 없음**: 제네릭은 항상 단형화된다. 트레이트 객체나
  동적 디스패치는 없다.
- **부분 특수화 없음**: 타입의 부분집합에 대해 특수화된 구현을 제공할
  수 없다.
- **기본 타입 인자 없음**: 모든 타입 매개변수는 추론되어야 한다.
- **추론은 지역적임**: `T`는 인자 또는 호출의 대상 타입에서 온다 —
  결과가 나중에 어떻게 쓰이는지로부터는 절대 추론하지 않는다.
- **추론은 `T*`/`[]T` 매개변수를 고정 크기 배열 그대로의 인자와
  통합(unify)하지 않는다** — 이미 포인터/슬라이스 타입이 부여된 값을
  대신 전달하라. 정확히 이 경우를 다루는 예제는
  [함수형 프로그래밍](/ko/reference/functional#higher-order-functions)을
  참고.

SafeC의 "런타임 디스패치 없음" 이야기의 나머지 부분 — 트레이트,
vtable이 필요 없는 `fn_eval` 프리미티브, 그리고 제네릭만으로는 부족할
때 진정한 의미의 이종(heterogeneous) 런타임 디스패치를 명시적으로
구축하는 방법 — 에 대해서는 [다형성과 OOP](/ko/reference/polymorphism)를
참고.
