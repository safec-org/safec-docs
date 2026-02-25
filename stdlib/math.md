# math -- Math, Complex & Bit

This page covers three numeric modules:

- **math** -- Mathematical constants and functions (float and double)
- **complex** -- Complex number arithmetic and transcendentals
- **bit** -- Bit manipulation and power-of-two helpers

## math

```c
#include "math.h"
```

### Naming Convention

- `_int` / `_ll` -- integer operations (const-evaluable at compile time)
- `_f` -- single-precision `float` (wraps `<math.h>` `*f` variants)
- `_d` -- double-precision `double` (wraps `<math.h>` base variants)

### Constants

```c
const double PI_D();       // 3.14159265358979323846
const double E_D();        // 2.71828182845904523536
const double LN2_D();      // 0.693147...
const double LN10_D();     // 2.302585...
const double SQRT2_D();    // 1.414213...
const double INF_D();      // positive infinity

const float  PI_F();
const float  E_F();
const float  SQRT2_F();
```

### Integer Functions

All integer functions are `const`-qualified and eligible for compile-time evaluation.

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

### Float Functions (_f)

| Category | Functions |
|----------|-----------|
| Basic | `abs_f`, `sqrt_f`, `cbrt_f` |
| Rounding | `floor_f`, `ceil_f`, `round_f`, `trunc_f` |
| Power & Exp | `pow_f`, `exp_f`, `exp2_f` |
| Logarithm | `log_f`, `log2_f`, `log10_f` |
| Trigonometry | `sin_f`, `cos_f`, `tan_f`, `asin_f`, `acos_f`, `atan_f`, `atan2_f` |
| Hyperbolic | `sinh_f`, `cosh_f`, `tanh_f` |
| Misc | `hypot_f`, `fmod_f`, `copysign_f`, `fma_f`, `min_f`, `max_f`, `clamp_f` |
| Classification | `isnan_f`, `isinf_f`, `isfinite_f` |

### Double Functions (_d)

All the same functions are available with the `_d` suffix operating on `double`:

| Category | Functions |
|----------|-----------|
| Basic | `abs_d`, `sqrt_d`, `cbrt_d` |
| Rounding | `floor_d`, `ceil_d`, `round_d`, `trunc_d` |
| Power & Exp | `pow_d`, `exp_d`, `exp2_d` |
| Logarithm | `log_d`, `log2_d`, `log10_d` |
| Trigonometry | `sin_d`, `cos_d`, `tan_d`, `asin_d`, `acos_d`, `atan_d`, `atan2_d` |
| Hyperbolic | `sinh_d`, `cosh_d`, `tanh_d` |
| Misc | `hypot_d`, `fmod_d`, `copysign_d`, `fma_d`, `min_d`, `max_d`, `clamp_d` |
| Classification | `isnan_d`, `isinf_d`, `isfinite_d` |

### Example

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

## complex

```c
#include "complex.h"
```

Complex numbers are represented as two-element arrays: `float[2]` or `double[2]` where index 0 is the real part and index 1 is the imaginary part. All functions use the naming convention `cXXX_f` (float) and `cXXX_d` (double). Results are written to an output array parameter.

### Construction & Access

```c
void   cmplx_f(float* out, float re, float im);
void   cmplx_d(double* out, double re, double im);

float  creal_f(const float* z);
double creal_d(const double* z);
float  cimag_f(const float* z);
double cimag_d(const double* z);
```

### Magnitude & Angle

```c
float  cabs_f(const float* z);    // |z| = sqrt(re^2 + im^2)
double cabs_d(const double* z);
float  carg_f(const float* z);    // phase angle = atan2(im, re)
double carg_d(const double* z);
```

### Arithmetic

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
void cconj_f(float* out, const float* z);   // complex conjugate
void cconj_d(double* out, const double* z);
```

### Transcendental

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

### Example

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

## bit

```c
#include "bit.h"
```

Bit manipulation functions based on C23 `<stdbit.h>` and compiler builtins. All functions are `const`-qualified (pure, no side effects) and eligible for compile-time evaluation.

### Population Count

```c
const int popcount32(unsigned int x);
const int popcount64(unsigned long long x);
```

Count the number of set (1) bits.

### Leading / Trailing Zeros

```c
const int clz32(unsigned int x);        // returns 32 for x == 0
const int clz64(unsigned long long x);  // returns 64 for x == 0
const int ctz32(unsigned int x);        // returns 32 for x == 0
const int ctz64(unsigned long long x);  // returns 64 for x == 0
```

### Bit Scan (Find First/Last Set)

```c
const int bsf32(unsigned int x);        // least-significant set bit (0-based); -1 if x == 0
const int bsf64(unsigned long long x);
const int bsr32(unsigned int x);        // most-significant set bit (0-based); -1 if x == 0
const int bsr64(unsigned long long x);
```

### Rotate

```c
const unsigned int       rotl32(unsigned int x, int n);
const unsigned int       rotr32(unsigned int x, int n);
const unsigned long long rotl64(unsigned long long x, int n);
const unsigned long long rotr64(unsigned long long x, int n);
```

Rotate `x` left/right by `n` bits. `n` is taken modulo 32 or 64.

### Byte Swap (Endian Reversal)

```c
const unsigned int       bswap32(unsigned int x);
const unsigned long long bswap64(unsigned long long x);
```

### Power-of-Two Helpers

```c
const int               is_pow2(unsigned long long x);   // 1 if x is a power of 2
const unsigned long long next_pow2(unsigned long long x); // round up to next power of 2
const int               ilog2(unsigned long long x);      // floor(log2(x)); -1 for x == 0
```

### Bit Field Helpers

```c
const unsigned int       bits32(unsigned int x, int lo, int hi);
const unsigned long long bits64(unsigned long long x, int lo, int hi);
const unsigned int       set_bits32(unsigned int x, int lo, int hi, unsigned int val);
```

Extract or set bits `[hi:lo]` (inclusive) in `x`.

### Example

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
