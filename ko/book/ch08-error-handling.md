# 8장: 에러 처리

SafeC에는 예외가 없습니다. `throw`도, `catch`도, 뒤에서 몰래 돌아가는
스택 언와인딩 장치도 없습니다 — 실패할 수 있는 함수는 반환 타입에서
그것을 밝히고, 호출자는 그 가능성을 명시적으로 처리하도록 요구받습니다.
이번 장은 이를 실제로 다룰 만하게 만들어 주는 세 가지 요소를
다룹니다: 부재를 알리기 위한 `?T` 옵셔널, `if` 검사의 피라미드 없이
실패를 전파하기 위한 `try`, 그리고 함수가 어떤 경로로 종료되든 실행되는
정리 작업을 위한 `defer`/`errdefer`입니다.

## `?T`: 값이 없을 수도 있다 {#t-might-not-have-a-value}

`?T`와 `null`은 앞선 장들에서 짧게 이미 본 적이 있습니다 — 이제
제대로 소개할 차례입니다. `?T`는 "`T`이거나, 아무것도 아니거나"를
뜻합니다.

```c
?int parse_positive(const char* s) {
    int v;
    unsafe { v = atoi(s); }
    if (v <= 0) {
        return null;
    }
    return v;
}
```

`?T`를 반환하는 함수에서 반환된 평범한 `T`는 암묵적으로 "존재함"으로
감싸집니다 — 위의 `return v;`는 "이것을 존재함으로 감싸라"는 명시적인
호출이 필요 없습니다. `null`은 `?T` 옵셔널과 [5장](/ko/book/ch05-understanding-regions)의
널 허용 참조 모두에 대해 "부재"를 나타내는 유일한 표기법입니다 —
평범한 코드에서 호출하는 별도의 `some(x)`/`none` 생성자는 없습니다.
이 두 이름은 `match` 패턴으로만 존재하며, 널 허용 참조 매칭에서
쓰는 것과 같은 점 없는 `null`/`some(x)` 형태입니다.

`?T`를 다시 꺼내 읽으려면 소수의 공인된 연산 중 하나를 거쳐야 합니다
— 직접 접근은 허용되지 않으며, [5장](/ko/book/ch05-understanding-regions)이
널 허용 참조에 부과했던 것과 같은 제약이고, 이유도 같습니다: "검사하는
것을 깜빡했다"는 버그가 여기서는 SafeC가 완전히 설계로 없애려는
바로 그 널 포인터 역참조 부류의 버그이기 때문입니다.

```c
match (parse_positive("21")) {
    case none:    printf("nothing\n");
    case some(x): printf("got %d\n", x);
}
```

## `try`: 피라미드 없이 실패 전파하기 {#try-propagate-failure-without-the-pyramid}

각각 실패할 수 있는 여러 함수를 이어 붙이면, `try`가 없는 언어에서는
"성공했으면 다음 것을 하라"는 검사가 계단식으로 중첩되는 결과를 낳기
십상입니다. `try`는 그것을 평평하게 만듭니다: `?T`를 풀어내고, 값이
`null`이었다면 *감싸고 있는* 함수에서도 즉시 `null`을 반환합니다 —
중첩된 `if` 대신 한 줄입니다.

```c
?int double_positive(const char* s) {
    int v = try parse_positive(s);   // parse_positive가 null을 반환했다면
                                       // double_positive도 여기서 null을 반환
    return v * 2;
}
```

```c
double_positive("21");   // some(42)
double_positive("-5");   // none -- parse_positive의 null이 그대로 전파됨
```

이는 Rust의 `?` 연산자, 또는 Go의 `if err != nil { return err }`
관용구와 같은 모양이지만, 둘 중 어느 쪽이든 호출 지점마다 필요한
보일러플레이트가 없습니다 — `try` *자체가* 그 보일러플레이트를 키워드
하나로 표현한 것입니다.

## `defer`: 항상 실행되는 정리 작업 {#defer-cleanup-that-always-runs}

`defer`는 감싸고 있는 스코프가 종료될 때 — 끝까지 실행되어서든, 이른
`return`을 통해서든, 어떤 경로를 거치든 — 실행될 문을 예약합니다.
후입선출(LIFO) 순서로 실행됩니다.

```c
void demo() {
    printf("start\n");
    defer printf("first deferred\n");
    defer printf("second deferred\n");
    printf("middle\n");
}
```

```
start
middle
second deferred
first deferred
```

이것이 획득과 해제를 소스 코드에서 바로 옆에 짝지어 두는 관용적인
방법입니다 — `malloc`/`free`, `fopen`/`fclose`, 뮤텍스 잠금/해제 등 —
모든 이른 반환 경로마다 중복되는 해제 문 대신입니다. 후자는 그중
하나에서 잊어버리기 쉽고, 잊어버렸다는 것을 알아차리기도 어려운
바로 그런 종류의 문제입니다.

## `errdefer`: 실패 경로에서만 실행되는 정리 작업 {#errdefer-cleanup-only-on-the-failure-path}

`errdefer`는 `defer`의 더 좁은 형제입니다 — 쓰는 방식은 같지만,
함수가 `try`가 실패를 전파하며 종료될 때만 실행되고, 평범한 `return`
(성공이든 아니든)에서는 실행되지 않습니다 — 명시적인 `return null;`도
이를 발동시키지 않으며, 오직 이 함수를 실제로 되감아 지나가는
`try`만이 발동시킵니다.

```c
?int risky_step(int fail_at) {
    if (fail_at != 0) { return null; }
    return 0;
}

?int process(int fail_at) {
    printf("processing\n");
    errdefer printf("rolling back\n");
    int r = try risky_step(fail_at);   // try를 통해 risky_step의 실패를 전파
    return 42;
}
```

`process(0)`은 `processing`만 출력합니다 — `risky_step`이 성공하고,
`try`가 정상적으로 값을 풀어내며, `errdefer`는 절대 발동하지 않습니다.
`process(1)`은 `processing`을 출력한 뒤 `rolling back`을 출력합니다
— `risky_step`이 `null`을 반환하고, `try`가 그 실패를 `process`
밖으로 전파하며(그로 인해 `process(1)` 자체가 `null`로 평가됩니다),
바로 그 되감기가 `errdefer`를 발동시킵니다.

## `?T`를 넘어서: 더 풍부한 에러 {#beyond-t-richer-errors}

`?T`는 무언가가 실패*했다*는 것은 알려주지만, *왜* 실패했는지는
알려주지 않습니다 — "양수가 아니다"가 유일하게 이름 붙일 가치가 있는
실패 모드인 `parse_positive`에는 이걸로 충분하지만, 호출자가 진짜로
구분해야 하는 여러 다른 이유로 실패할 수 있는 파일 열기 호출 같은
것에는 충분치 않습니다. 그런 경우에는 `std::Result`를 사용하세요
([7장](/ko/book/ch07-enums-and-match)에서 손으로 만드는 법을 배운
것과 같은 태그된 유니온 메커니즘 위에 만들어져 있습니다) — 필요할
때 [표준 라이브러리](/ko/stdlib/)를 참고하세요. 이번 장의 `?T`는 여전히
흔한 "성공했는가 아닌가"라는 경우에는 옳은 기본 선택입니다.

다음: [최종 프로젝트](/ko/book/ch09-final-project) — 지난 여덟 장의
모든 것을 실제로 간직할 만한 가치가 있는 프로그램 하나로 결합합니다.
