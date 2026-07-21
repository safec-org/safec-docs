# math -- 수학, 복소수 & 비트

이 페이지는 세 가지 수치 모듈을 다룹니다.

- **math** -- 수학 상수와 함수 (float와 double)
- **complex** -- 복소수 연산과 초월 함수
- **bit** -- 비트 조작과 2의 거듭제곱 헬퍼

## math {#math}

```c
#include "math.h"
```

### 명명 규칙 {#naming-convention}

- `_int` / `_ll` -- 정수 연산 (컴파일 타임에 상수 평가 가능)
- `_f` -- 단정밀도 `float` (`<math.h>`의 `*f` 변형을 감쌈)
- `_d` -- 배정밀도 `double` (`<math.h>`의 기본 변형을 감쌈)

### 상수 {#constants}

```c
const double PI_D();       // 3.14159265358979323846
const double E_D();        // 2.71828182845904523536
const double LN2_D();      // 0.693147...
const double LN10_D();     // 2.302585...
const double SQRT2_D();    // 1.414213...
const double INF_D();      // 양의 무한대

const float  PI_F();
const float  E_F();
const float  SQRT2_F();
```

### 정수 함수 {#integer-functions}

모든 정수 함수는 `const`로 한정되어 있으며 컴파일 타임 평가가 가능합니다.

```c
const int       abs_int(int x);
const long long abs_ll(long long x);
const int       min_int(int a, int b);
const int       max_int(int a, int b);
const long long min_ll(long long a, long long b);
const long long max_ll(long long a, long long b);
const int       clamp_int(int v, int lo, int hi);
const long long clamp_ll(long long v, long long lo, long long hi);
```

### 부동소수점 함수 (_f) {#float-functions-_f}

| 분류 | 함수 |
|----------|-----------|
| 기본 | `abs_f`, `sqrt_f`, `cbrt_f` |
| 반올림 | `floor_f`, `ceil_f`, `round_f`, `trunc_f` |
| 거듭제곱 & 지수 | `pow_f`, `exp_f`, `exp2_f` |
| 로그 | `log_f`, `log2_f`, `log10_f` |
| 삼각함수 | `sin_f`, `cos_f`, `tan_f`, `asin_f`, `acos_f`, `atan_f`, `atan2_f` |
| 쌍곡함수 | `sinh_f`, `cosh_f`, `tanh_f` |
| 기타 | `hypot_f`, `fmod_f`, `copysign_f`, `fma_f`, `min_f`, `max_f`, `clamp_f` |
| 분류 | `isnan_f`, `isinf_f`, `isfinite_f` |

### 배정밀도 함수 (_d) {#double-functions-_d}

동일한 모든 함수가 `_d` 접미사로 `double`에 대해 제공됩니다.

| 분류 | 함수 |
|----------|-----------|
| 기본 | `abs_d`, `sqrt_d`, `cbrt_d` |
| 반올림 | `floor_d`, `ceil_d`, `round_d`, `trunc_d` |
| 거듭제곱 & 지수 | `pow_d`, `exp_d`, `exp2_d` |
| 로그 | `log_d`, `log2_d`, `log10_d` |
| 삼각함수 | `sin_d`, `cos_d`, `tan_d`, `asin_d`, `acos_d`, `atan_d`, `atan2_d` |
| 쌍곡함수 | `sinh_d`, `cosh_d`, `tanh_d` |
| 기타 | `hypot_d`, `fmod_d`, `copysign_d`, `fma_d`, `min_d`, `max_d`, `clamp_d` |
| 분류 | `isnan_d`, `isinf_d`, `isfinite_d` |

### 예제 {#example}

```c
#include "math.h"
#include "io.h"

int main() {
    double r = 5.0;
    double area = PI_D() * r * r;
    print("Area: ");
    println_float(area);

    print("sqrt(2) = ");
    println_float(sqrt_d(2.0));

    print("isnan(0.0/0.0) = ");
    println_int(isnan_d(0.0 / 0.0));

    return 0;
}
```

---

## complex {#complex}

```c
#include "complex.h"
```

복소수는 두 원소로 이루어진 배열로 표현됩니다: `float[2]` 또는 `double[2]`이며, 인덱스 0은 실수부, 인덱스 1은 허수부입니다. 모든 함수는 `cXXX_f`(float)와 `cXXX_d`(double) 명명 규칙을 따릅니다. 결과는 출력 배열 매개변수에 기록됩니다.

### 생성 & 접근 {#construction-access}

```c
void   cmplx_f(float* out, float re, float im);
void   cmplx_d(double* out, double re, double im);

float  creal_f(const float* z);
double creal_d(const double* z);
float  cimag_f(const float* z);
double cimag_d(const double* z);
```

### 크기 & 각도 {#magnitude-angle}

```c
float  cabs_f(const float* z);    // |z| = sqrt(re^2 + im^2)
double cabs_d(const double* z);
float  carg_f(const float* z);    // 위상각 = atan2(im, re)
double carg_d(const double* z);
```

### 산술 연산 {#arithmetic}

```c
void cadd_f(float* out, const float* a, const float* b);
void cadd_d(double* out, const double* a, const double* b);
void csub_f(float* out, const float* a, const float* b);
void csub_d(double* out, const double* a, const double* b);
void cmul_f(float* out, const float* a, const float* b);
void cmul_d(double* out, const double* a, const double* b);
void cdiv_f(float* out, const float* a, const float* b);
void cdiv_d(double* out, const double* a, const double* b);
void cneg_f(float* out, const float* z);
void cneg_d(double* out, const double* z);
void cconj_f(float* out, const float* z);   // 복소 켤레
void cconj_d(double* out, const double* z);
```

### 초월 함수 {#transcendental}

```c
void csqrt_f(float* out, const float* z);
void csqrt_d(double* out, const double* z);
void cexp_f(float* out, const float* z);
void cexp_d(double* out, const double* z);
void clog_f(float* out, const float* z);
void clog_d(double* out, const double* z);
void cpow_f(float* out, const float* base, const float* exp);
void cpow_d(double* out, const double* base, const double* exp);
void csin_f(float* out, const float* z);
void csin_d(double* out, const double* z);
void ccos_f(float* out, const float* z);
void ccos_d(double* out, const double* z);
```

### 예제 {#example-1}

```c
#include "complex.h"
#include "io.h"

int main() {
    double a[2];
    double b[2];
    double c[2];

    cmplx_d(a, 3.0, 4.0);   // 3 + 4i
    cmplx_d(b, 1.0, -2.0);  // 1 - 2i

    cadd_d(c, a, b);         // 4 + 2i

    print("real = ");
    println_float(creal_d(c));
    print("imag = ");
    println_float(cimag_d(c));
    print("|a|  = ");
    println_float(cabs_d(a));   // 5.0

    return 0;
}
```

---

## bit {#bit}

```c
#include "bit.h"
```

C23 `<stdbit.h>`와 컴파일러 내장 함수를 기반으로 한 비트 조작 함수들입니다. 모든 함수는 `const`로 한정되며(순수 함수, 부작용 없음), 컴파일 타임 평가가 가능합니다.

### 비트 수 세기 {#population-count}

```c
const int popcount32(unsigned int x);
const int popcount64(unsigned long long x);
```

설정된(1인) 비트의 개수를 셉니다.

### 앞/뒤 0 개수 {#leading-trailing-zeros}

```c
const int clz32(unsigned int x);        // x == 0이면 32 반환
const int clz64(unsigned long long x);  // x == 0이면 64 반환
const int ctz32(unsigned int x);        // x == 0이면 32 반환
const int ctz64(unsigned long long x);  // x == 0이면 64 반환
```

### 비트 스캔 (첫/마지막 설정 비트 찾기) {#bit-scan-find-first-last-set}

```c
const int bsf32(unsigned int x);        // 최하위 설정 비트 (0부터 시작); x == 0이면 -1
const int bsf64(unsigned long long x);
const int bsr32(unsigned int x);        // 최상위 설정 비트 (0부터 시작); x == 0이면 -1
const int bsr64(unsigned long long x);
```

### 회전 {#rotate}

```c
const unsigned int       rotl32(unsigned int x, int n);
const unsigned int       rotr32(unsigned int x, int n);
const unsigned long long rotl64(unsigned long long x, int n);
const unsigned long long rotr64(unsigned long long x, int n);
```

`x`를 `n`비트만큼 왼쪽/오른쪽으로 회전시킵니다. `n`은 32 또는 64로 나눈 나머지가 사용됩니다.

### 바이트 스왑 (엔디언 반전) {#byte-swap-endian-reversal}

```c
const unsigned int       bswap32(unsigned int x);
const unsigned long long bswap64(unsigned long long x);
```

### 2의 거듭제곱 헬퍼 {#power-of-two-helpers}

```c
const int               is_pow2(unsigned long long x);   // x가 2의 거듭제곱이면 1
const unsigned long long next_pow2(unsigned long long x); // 다음 2의 거듭제곱으로 올림
const int               ilog2(unsigned long long x);      // floor(log2(x)); x == 0이면 -1
```

### 비트 필드 헬퍼 {#bit-field-helpers}

```c
const unsigned int       bits32(unsigned int x, int lo, int hi);
const unsigned long long bits64(unsigned long long x, int lo, int hi);
const unsigned int       set_bits32(unsigned int x, int lo, int hi, unsigned int val);
```

`x`에서 비트 `[hi:lo]`(포함)를 추출하거나 설정합니다.

### 예제 {#example-2}

```c
#include "bit.h"
#include "io.h"

int main() {
    unsigned int x = 0xFF00;

    print("popcount: ");
    println_int(popcount32(x));      // 8

    print("clz: ");
    println_int(clz32(x));           // 16

    print("bswap: 0x");
    print_hex(bswap32(0x12345678));  // 0x78563412
    println("");

    print("next_pow2(100) = ");
    println_int(next_pow2(100));     // 128

    return 0;
}
```
