# 오버플로 연산자

정수 오버플로는 C에서 버그와 보안 취약점의 흔한 원인이다. SafeC는
전용 연산자 변형을 통해 명시적인 오버플로 제어를 제공하며, 프로그래머가
오버플로 동작을 완전히 제어할 수 있게 한다.

## 기본 동작 {#default-behavior}

기본적으로 SafeC는 호환성을 위해 C의 시맨틱을 따른다:

- **부호 있는 정수**: 오버플로는 정의되지 않은 동작이다(컴파일러는
  오버플로가 절대 일어나지 않는다고 가정하고 최적화할 수 있다)
- **부호 없는 정수**: 오버플로는 랩어라운드(순환)된다(모듈러 연산)

```c
int x = 2147483647;           // INT_MAX
x = x + 1;                    // 정의되지 않은 동작 (부호 있는 오버플로)

uint32_t y = 4294967295;        // UINT32_MAX
y = y + 1U;                   // 0으로 래핑됨 (정의된 동작)
```

## 래핑 연산자 {#wrapping-operators}

래핑 연산자는 부호 있는 타입과 부호 없는 타입 모두에 대해 모듈러
연산을 보장한다. 결과는 2의 보수를 사용해 오버플로 시 랩어라운드된다.

| 연산자 | 설명 |
|----------|-------------|
| `+\|` | 래핑 덧셈 |
| `-\|` | 래핑 뺄셈 |
| `*\|` | 래핑 곱셈 |

> **8비트 피연산자는 `int`로 승격된다.** C와 마찬가지로 `int8_t`/`uint8_t`
> (그리고 `char`)에 대한 산술 연산은 축소되기 전 항상 `int` 폭에서
> 평가된다 — 이는 래핑/새추레이팅 연산자에도 그대로 적용되므로,
> `uint8_t + uint8_t`도 여전히 `int` 타입의 표현식이다. 결과를 대입할
> 때는 표현식 전체를 다시 8비트 타입으로 캐스트해서 감싸야 한다
> (아래 `b`, `d`, `f`, `brighten()` 참고). 16비트 이상의 피연산자는
> 영향을 받지 않는다.

### 예시 {#examples}

```c
int x = 2147483647;           // INT_MAX (2^31 - 1)
int y = x +| 1;               // -2147483648 (INT_MIN으로 래핑)
int z = x +| x;               // -2 (랩어라운드)

uint8_t a = 255;
uint8_t b = (uint8_t)(a +| 1);  // 0 (랩어라운드)

int big = 1000000;
int overflow = big *| big;     // 래핑됨 (1000000^2 mod 2^32)
```

### 사용 사례 {#use-cases}

- 해시 함수와 체크섬
- 시퀀스 번호 연산
- 암호학적 연산
- 링 버퍼 인덱스 계산

```c
// 래핑 인덱스를 사용하는 링 버퍼
uint32_t write_idx = 0;
uint32_t read_idx = 0;
const uint32_t BUF_SIZE = 1024;

void push(int value) {
    buffer[write_idx % BUF_SIZE] = value;
    write_idx = write_idx +| 1U;  // UINT32_MAX에서 안전하게 래핑됨
}
```

## 새추레이팅 연산자 {#saturating-operators}

새추레이팅 연산자는 오버플로 시 래핑하는 대신 결과를 해당 타입의
최솟값 또는 최댓값으로 클램프한다.

| 연산자 | 설명 |
|----------|-------------|
| `+%` | 새추레이팅 덧셈 |
| `-%` | 새추레이팅 뺄셈 |
| `*%` | 새추레이팅 곱셈 |

### 예시 {#examples-1}

```c
int x = 2147483647;           // INT_MAX
int y = x +% 1;               // 2147483647 (INT_MAX에서 새추레이팅)
int z = x +% 100;             // 2147483647 (여전히 INT_MAX)

int a = -2147483648;          // INT_MIN
int b = a -% 1;               // -2147483648 (INT_MIN에서 새추레이팅)

uint8_t c = 250;
uint8_t d = (uint8_t)(c +% 10); // 255 (UINT8_MAX에서 새추레이팅)
uint8_t e = 5;
uint8_t f = (uint8_t)(e -% 10); // 0 (부호 없는 타입은 0에서 새추레이팅)
```

### 사용 사례 {#use-cases-1}

- 오디오와 신호 처리 (클리핑)
- 색상 값 계산 (0-255로 클램프)
- 물리적 한계가 있는 센서 값
- "표현 가능한 가장 가까운 값"이 랩어라운드보다 유용한 모든 영역

```c
// 새추레이팅을 사용한 오디오 샘플 믹싱
int16_t mix_samples(int16_t a, int16_t b) {
    return a +% b;             // [-32768, 32767]로 클램프
}

// 색상 밝기 조정
uint8_t brighten(uint8_t color, uint8_t amount) {
    return (uint8_t)(color +% amount); // 255로 클램프, 어두운 쪽으로 래핑되지 않음
}

// 음수가 될 수 없는 거리 계산
uint32_t safe_distance(uint32_t a, uint32_t b) {
    if (a > b) return a -% b;
    return b -% a;
}
```

## 검사된 산술 연산 {#checked-arithmetic}

오버플로를 래핑이나 클램핑 대신 감지해야 하는 경우를 위해,
`<stdckdint.h>`(C23의 `<stdckdint.h>`에 대응하는 SafeC 버전)는 연산을
수행하고, 래핑된 결과를 저장하며, 오버플로 여부를 보고하는 함수를
제공한다. C23의 타입 제네릭 매크로 `ckd_add`/`ckd_sub`/`ckd_mul`과
달리, SafeC는 폭/부호별로 명시적으로 타입이 지정된 함수를 노출한다 —
`int`에 대한 `ckd_add_i32`/`ckd_sub_i32`/`ckd_mul_i32`, `long long`에
대한 `..._i64`, `unsigned int`에 대한 `..._u32`, `unsigned long long`에
대한 `..._u64`:

```c
#include <stdckdint.h>

int result;
int overflowed = std::ckd_add_i32(&result, 2147483647, 1);
if (overflowed) {
    // result는 래핑된 값(INT_MIN)을 담고 있고, overflowed는 1 (CKD_OVERFLOW)이다
}
```

`std::CKD_OK`(0)와 `std::CKD_OVERFLOW`(1)는 반환값을 위한 읽기 쉬운
별칭으로 제공된다.

### 검사된 할당 크기 계산 {#checked-allocation-sizing}

`std::checked_mul_size`(`<mem.h>`)는 흔히 쓰이는 `count * element_size`
형태의 할당 크기 곱셈을 위한 더 좁은 목적의 헬퍼다 — 오버플로 플래그를
반환하는 대신 중단(abort)한다. 조용히 래핑된 할당 크기는 복구 가능한
값이 아니라 메모리 안전성 버그이기 때문이다:

```c
#include <mem.h>

unsigned long n = std::checked_mul_size(count, sizeof(int));
int* buf = (int*)std::alloc(n);
```

## 다른 언어와의 비교 {#comparison-with-other-languages}

| 언어 | 기본값 | 래핑 | 새추레이팅 |
|----------|---------|----------|------------|
| C | UB (부호 있음) / wrap (부호 없음) | N/A | N/A |
| SafeC | UB (부호 있음) / wrap (부호 없음) | `+\|` `-\|` `*\|` | `+%` `-%` `*%` |
| Rust | Panic (디버그) / wrap (릴리스) | `.wrapping_add()` | `.saturating_add()` |
| Zig | Undefined (최적화됨) | `+%` | `@addWithOverflow` |
| Swift | Trap | `&+` | `clamping:` |

SafeC는 메서드 호출이 아닌 연산자 문법을 사용해 표현식을 읽기 쉽게
유지한다:

```c
// SafeC: 자연스러운 연산자 문법
int result = a +| b *| c;

// vs. 메서드 기반 (다른 언어들)
// int result = a.wrapping_add(b.wrapping_mul(c));
```

## 요약 {#summary}

| 범주 | 연산자 | 오버플로 동작 |
|----------|-----------|-------------------|
| 기본값 | `+` `-` `*` | 부호 있는 타입은 UB, 부호 없는 타입은 wrap |
| 래핑 | `+\|` `-\|` `*\|` | 모든 타입에 대해 2의 보수로 래핑 |
| 새추레이팅 | `+%` `-%` `*%` | 타입의 최솟값/최댓값으로 클램프 |
