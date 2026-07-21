# 제어 흐름

SafeC는 표준 C의 모든 제어 흐름 구문을 지원하며, 여기에 패턴 매칭, 지연 실행(deferred execution), 컴파일 타임 분기를 추가로 제공합니다.

## If / Else {#if-else}

표준적인 조건 분기입니다.

```c
if (x > 0) {
    printf("positive\n");
} else if (x == 0) {
    printf("zero\n");
} else {
    printf("negative\n");
}
```

## While 루프 {#while-loop}

```c
int i = 0;
while (i < 10) {
    printf("%d\n", i);
    i = i + 1;
}
```

## For 루프 {#for-loop}

초기화, 조건, 증가로 구성된 C 스타일 for 루프입니다.

```c
for (int i = 0; i < 10; i = i + 1) {
    printf("%d\n", i);
}
```

## Do-While 루프 {#do-while-loop}

본문을 최소 한 번 실행합니다.

```c
int attempts = 0;
do {
    attempts = attempts + 1;
} while (!try_connect() && attempts < 3);
```

## Match 문 {#match-statement}

`match` 문은 (C의 `switch`와 달리) fall-through 없이 패턴 매칭을 제공합니다. 각 case는 독립적입니다 -- `break`가 필요하지 않습니다.

```c
match (status_code) {
    case 200: printf("OK\n");
    case 404: printf("Not Found\n");
    case 400..499: printf("Client Error\n");
    case 500..599: printf("Server Error\n");
    default: printf("Unknown\n");
}
```

### 기능 {#features}

**범위 패턴**은 `..` 또는 `...`를 사용해 정수나 문자의 포함 범위를 매칭합니다.

```c
match (status_code) {
    case 400..499: printf("client error\n");
    case 500..599: printf("server error\n");
    default: printf("other\n");
}

match (c) {
    case 'a'...'z': printf("lowercase\n");
    case 'A'..'Z':  printf("uppercase\n");
    case '0'...'9': printf("digit\n");
    default:        printf("other\n");
}
```

**교대(alternation) 패턴**은 여러 값을 매칭합니다 — `|`가 아니라 **콤마**를 사용합니다.

```c
match (day) {
    case 1, 7: printf("weekend\n");
    case 2, 3, 4, 5, 6: printf("weekday\n");
}
```

::: warning 교대는 `,`를 사용하며, `|`는 사용하지 않습니다
`case 1 | 7:`은 파싱되지 않습니다 — `|`는 패턴 목록 안에서 유효하지 않습니다. 위에서 보인 것처럼 대안들을 구분할 때는 콤마를 사용하십시오.
:::

**와일드카드**는 모든 것을 매칭합니다.

```c
match (value) {
    case 0: handle_zero();
    default: handle_other();
}
```

와일드카드/`default` 없이 작성된 match 문은 완전하지 않은(non-exhaustive) 경우 경고만 냅니다(`match statement may not be exhaustive`) — 오류는 아닙니다. 값을 생산하지 않은 채로 통과되어도 문장 위치에서는 무해하기 때문입니다.

### Nullable 참조와 옵셔널 매칭 {#matching-nullable-references-and-optionals}

`match`는 포인터(`T*`), nullable 참조(`?&region T`), 옵셔널(`?T`)도
구조 분해(destructure)합니다 — 이는 이들을 읽는 주된 허용 방법으로,
`is_null()`/`is_none()`/`.default(fallback)`과 나란히 사용됩니다
(자세한 내용은 [타입](/ko/reference/types#reading-a-nullable-value) 참고).
직접적인 역참조/멤버 접근/강제 언래핑(`!`)은 `unsafe`가 필요합니다.

```c
struct Node { int value; };

void describe(?&stack Node next) {
    match (next) {
        case null:    printf("empty\n");
        case some(n): printf("value=%d\n", n.value);  // n은 페이로드 타입 자체로 바인딩됨
    }
}

?int maybe = compute();
match (maybe) {
    case none:    printf("nothing\n");
    case some(x): printf("got %d\n", x);
}
```

패턴 이름은 포인터와 nullable 참조에 대해 `null`/`some(x)`이고, 옵셔널에 대해서는 `none`/`some(x)`입니다 — (태그된 유니온의 변형 패턴과 달리) 점(dot)이 붙지 않은 일반 식별자입니다.

### 표현식으로서의 Match {#match-as-an-expression}

`match`는 표현식으로도 사용될 수 있으며, 실행된 분기에서 값을 생산합니다.
문장 형태와 달리, match **표현식**은 반드시 완전성(exhaustiveness)이
증명 가능해야 합니다 — 모든 태그된 유니온 변형이 다루어지거나
(`default`/와일드카드 분기가 있거나), nullable/옵셔널 대상의 경우
`null`/`none`과 `some(x)` 양쪽이 모두 다루어져야 합니다 — 그렇지 않으면
모든 경로에서 값을 만들어야 하므로 컴파일 오류가 됩니다.

```c
int result = match (status_code) {
    case 200:      1,
    case 400..499: -1,
    default:       0,
};

int describe_len(?&stack Node next) {
    return match (next) {
        case null:    -1,
        case some(n): n.value,
    };
}
```

## Switch 문 {#switch-statement}

위의 `match`와는 구별되는, 진짜 C의 fall-through 디스패치입니다
(`match`는 결코 fall-through하지 않으며, `match` 안의 `break`는
match 자체가 아니라 *감싸는 루프*로 전달됩니다).

```c
switch (n) {
case 1:
case 2:
    do_low();
    break;
case 3:
    do_three();
    // fall-through — break 없음
case 4:
    do_three_or_four();
    break;
default:
    do_other();
}
```

Case 값은 (선택적으로 부호가 반전된) 리터럴 정수 또는 문자 상수여야 하며 —
일반적인 상수 표현식은 허용되지 않습니다. 이는 실제 `switch`/점프 테이블
명령어로 컴파일될 수 있도록 C의 "case 레이블은 정수 상수 표현식이어야
한다"는 규칙을 충실히 따르는 것으로, 비교 체인으로 컴파일되지 않습니다.
레이블 없는 `break`는 switch를 빠져나가고, `continue`는 switch에 의해
소비되지 않습니다 — 진짜 C와 마찬가지로 (있다면) 이미 감싸고 있는 루프를
계속 가리킵니다.

## Defer {#defer}

`defer`는 감싸는 스코프가 종료될 때 실행되도록 문장을 예약하며, LIFO(후입선출) 순서로 실행됩니다. 리소스 정리에 이상적입니다.

```c
void process_file(const char *path) {
    FILE *f = fopen(path, "r");
    defer fclose(f);

    char *buf = (char*)malloc(4096);
    defer free(buf);

    // ... f와 buf 사용 ...
}   // free(buf)가 먼저 실행되고, 그 다음 fclose(f)가 실행됨
```

지연된 문장은 스코프가 정상 흐름, `return`, 또는 조기 종료 중 어떤 방식으로 종료되든 관계없이 실행됩니다.

### 다중 Defer {#multiple-defers}

Defer는 선언의 역순으로 실행됩니다.

```c
void example() {
    defer printf("3\n");
    defer printf("2\n");
    defer printf("1\n");
}
// 출력: 1, 2, 3
```

## Try 연산자 {#try-operator}

`try` 연산자는 옵셔널 값을 언래핑하며, 값이 없을 경우(빈 케이스인
`null`) 즉시 호출자에게 전파합니다 — `if (x.is_none()) return null; T value = ...;`를
인라인으로 작성한 것과 동등합니다.

```c
?int parse_config(const char *path) {
    ?int fd = open_file(path);
    int file = try fd;         // fd가 비어 있으면 즉시 null을 반환

    ?int value = read_int(file);
    return try value;
}
```

`?T`를 반환하는 함수에서 반환된 순수한 `T`는 암묵적으로 "존재함"으로 래핑되고(위의 `return file;`은 명시적 래핑이 필요 없습니다), `return null;`은 빈 케이스를 만듭니다 — 일반 코드에서 호출할 별도의 `some(x)` 생성자는 없습니다(`some`/`none`은 위에서 본 것처럼 `match` 패턴으로만 등장합니다).

## Errdefer {#errdefer}

`errdefer`는 `defer`처럼 작성되지만, 모든 종료가 아니라 `try`로 전파되는 실패 종료 시에만 실행되도록 의도되었습니다.

```c
?int open_and_process(const char *path) {
    ?int fd = open_file(path);
    int f = try fd;             // 실패 시 null을 전파
    errdefer close_fd(f);       // 아래의 이후 'try'가 실패할 때만 실행되도록 의도됨

    ?int size = read_size(f);
    int n = try size;           // 실패 시: close_fd(f)가 실행된 후 null이 전파됨

    // ... 처리 ...
    return n;
}
```

정상적인(`try`에 의해 촉발되지 않은) 종료 — 성공적인 종료를 포함한 일반적인
명시적 `return`, 또는 함수 끝까지 도달하는 경우 — 는 등록된 모든 `defer`를
실행하지만 `errdefer`는 건너뜁니다. 이름 그대로입니다. `try`로 전파되는
실패 종료는 둘 다 실행합니다: 일반적인 정리(`defer`)는 여전히 자신의
몫을 해야 하고, 여기에 등록된 오류 전용 정리(`errdefer`)까지 더해집니다.

## 레이블이 붙은 Break와 Continue {#labeled-break-and-continue}

레이블을 사용하면 중첩된 루프를 벗어나거나 계속할 수 있습니다.

```c
outer: for (int i = 0; i < 10; i = i + 1) {
    for (int j = 0; j < 10; j = j + 1) {
        if (i + j > 15) break outer;
        if (j % 2 == 0) continue;
        printf("%d %d\n", i, j);
    }
}
```

## Goto와 레이블 {#goto-and-labels}

C 호환성과 저수준 제어 흐름을 위한 것입니다.

```c
void state_machine(int input) {
    goto start;

start:
    if (input == 0) goto done;
    input = input - 1;
    goto start;

done:
    printf("finished\n");
}
```

::: warning
`goto`보다 구조화된 제어 흐름(`match`, 루프, `break`/`continue`)을 선호하십시오. 컴파일러는 `goto`가 리전 라이프타임을 존중하는지 검증하지 않습니다.
:::

## If Const (컴파일 타임 분기) {#if-const-compile-time-branching}

`if const`는 조건을 컴파일 타임에 평가하여 분기를 선택합니다. 선택되지 않은(dead) 분기는 완전히 제거되며 타입 검사되지 않습니다.

```c
const int PLATFORM = 1;

void init() {
    if const (PLATFORM == 1) {
        init_linux();
    } else if const (PLATFORM == 2) {
        init_macos();
    } else {
        init_generic();
    }
}
```

이는 `#ifdef` 기반 조건부 컴파일에 대한 SafeC의 대체 수단입니다. 전처리기 조건문과 달리, `if const`는 스코프를 존중하며 `const`와 `consteval` 값을 참조할 수 있습니다.
