# 2장: 첫 번째 프로그램

*The C Programming Language*은 "hello, world"를 지나 첫 번째 본격적인
장을 섭씨-화씨 변환 표로 엽니다. 첫 프로그램으로 좋은 선택입니다 —
한 화면에 들어갈 만큼 작으면서도, 변수, 반복문, 서식 있는 출력, 약간의
산술 연산을 한꺼번에 다룰 만큼은 큽니다. 이번 장에서는 SafeC로 같은
프로그램을 작성한 다음, 실제로 쓸 만한 작은 커맨드라인 도구로 확장해
봅니다.

## 변환 {#the-conversion}

화씨와 섭씨는 `F = C * 9/5 + 32`라는 관계로 이어져 있습니다. 단일 변환은
다음과 같습니다.

```c
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char** argv) {
    if (argc < 2) {
        unsafe { printf("usage: %s <celsius>\n", argv[0]); }
        return 1;
    }
    double c;
    unsafe { c = atof(argv[1]); }
    double f = c * 9.0 / 5.0 + 32.0;
    printf("%.1f C = %.1f F\n", c, f);
    return 0;
}
```

```bash
$ safeguard run -- 100
100.0 C = 212.0 F
```

짚고 넘어갈 만한 것이 두 가지 있는데, 둘 다 SafeC 코드에서 계속해서
보게 될 것들입니다.

**`unsafe { printf("...", argv[0]); }`.** `argv`는 원시 `char**`입니다 —
SafeC의 컴파일 타임 추적이 전혀 붙어 있지 않은 평범한 C 스타일 포인터입니다
(커맨드라인 인자는 OS에서 오는 것이라, 컴파일러가 검증할 수 있는 범위
밖에 있습니다). 원시 포인터를 *인덱싱*하는 것(`argv[0]`, `argv[1]`)은
역참조와 마찬가지로 `unsafe`가 필요합니다 — 5장에서 그 이유를 완전히
설명합니다. 지금은 "원시 포인터를 건드리려면 `unsafe`가 필요하다"는
경험 법칙만 기억해 두세요.

**`atof(argv[1])`을 `double c = atof(argv[1]);`처럼 바로 대입하지 않고
별도의 `unsafe { c = ...; }` 문으로 감싼 이유**는 순전히 unsafe한
연산(`argv`를 인덱싱하는 것)이 *어디서* 일어나는가에 관한 것입니다 —
`atof` 호출 자체는 unsafe하지 않고, `argv[1]`을 읽어서 인자를 얻는
부분만 unsafe하므로, 대입은 블록 안에 있어야 하지만 선언은 그럴
필요가 없습니다.

## 값 하나에서 표로 {#from-one-value-to-a-table}

K&R의 버전은 값 하나를 변환하는 대신 표 전체를 출력합니다. `for` 반복문으로
그렇게 만들어 봅시다.

```c
#include <stdio.h>

int main() {
    printf("Celsius  Fahrenheit\n");
    for (int c = 0; c <= 100; c = c + 10) {
        double f = (double)c * 9.0 / 5.0 + 32.0;
        printf("%7d  %10.1f\n", c, f);
    }
    return 0;
}
```

```
Celsius  Fahrenheit
      0        32.0
     10        50.0
     20        68.0
     30        86.0
     40       104.0
     50       122.0
     60       140.0
     70       158.0
     80       176.0
     90       194.0
    100       212.0
```

`c`는 `int`로 선언되어 있고(정수 단위 단계가 표를 더 깔끔하게 만듭니다),
따라서 `(double)c`는 부동소수점 연산 전에 그것을 `double`로
캐스팅합니다 — SafeC는 일반 C의 통상적인 산술 변환 규칙이 몸에 밴 습관으로
기대하게 만드는 것과 달리, `int`를 `double`로 암묵적으로 확장해 주지
않습니다. 크기나 부호가 다른 숫자 타입 사이의 모든 변환은 명시적으로
작성해야 합니다. 이 규칙은 앞으로도 계속 마주치게 될 것이며, [공통
개념](/ko/book/ch03-common-concepts)에서 어떤 변환이 암묵적이고 어떤 것이
아닌지 정확히 다룹니다.

## 작은 도구: 여러 값을 한 번에 변환하기 {#a-small-tool-converting-several-values-at-once}

실제 커맨드라인 도구는 보통 인자를 하나 이상 받습니다. `argv`를 순회하면
원하는 만큼 많은 값을 처리하는 변환기를 만들 수 있습니다.

```c
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char** argv) {
    if (argc < 2) {
        unsafe { printf("usage: %s <celsius>...\n", argv[0]); }
        return 1;
    }
    for (int i = 1; i < argc; i = i + 1) {
        double c;
        unsafe { c = atof(argv[i]); }
        double f = c * 9.0 / 5.0 + 32.0;
        printf("%.1f C = %.1f F\n", c, f);
    }
    return 0;
}
```

```bash
$ safeguard run -- 0 100 37
0.0 C = 32.0 F
100.0 C = 212.0 F
37.0 C = 98.6 F
```

`--`가 중요합니다: 이는 `safeguard run`에게 "이 뒤에 오는 모든 것은
`safeguard` 자체가 아니라 실행되는 프로그램에 대한 인자다"라고 알려줍니다
— 이것이 없으면 `safeguard`는 `0`, `100`, `37`을 자신의 플래그로
해석하려 들 것입니다.

## 아직 다루지 않은 것 {#what-we-havent-covered-yet}

이 프로그램은 동작하긴 하지만 입력을 검증하지 않으며(숫자가 아닌 인자는
조용히 `0.0`으로 변환되는데, 이는 `atof`가 파싱할 수 없는 입력에 대해
그렇게 동작하기 때문입니다 — SafeC 고유의 동작이 아니라 순수 C의
`atof`가 그렇습니다), SafeC 자체의 더 안전한 컬렉션 타입 대신 원시
C 스타일 배열(`argv`)을 사용합니다. 둘 다 의도적인 것입니다 — 이 장의
목표는 편집-컴파일-실행 루프와 `unsafe`를 살짝 맛보는 것이지, 견고한
에러 처리나 SafeC의 표준 라이브러리가 아니었습니다. [에러
처리](/ko/book/ch08-error-handling)와 [표준
라이브러리](/ko/stdlib/) 레퍼런스는 여러분이 언어를 더 많이 익힌 뒤,
나중에 다룹니다. 다음으로 [공통 개념](/ko/book/ch03-common-concepts)에서는
이 첫 두 장보다 더 체계적으로 SafeC 기본 어휘의 나머지 — 타입, 연산자,
제어 흐름 — 를 채워 나갑니다.
