# DSP 및 실시간 유틸리티

`std/dsp/`는 결정론적 고정소수점 산술, 스펙트럼 변환(DFT/FFT/DCT/STFT/CQT/CWT), 필터(FIR/IIR/biquad/comb), 컨볼루션, 리샘플링, 최소 위상 재구성, 일반적인 라플라스-Z(쌍선형) 변환 도구, 2D/이미징 프리미티브, 오디오 버퍼링, 실시간 타이머 스케줄링을 아우릅니다 — 총 20개의 모듈입니다.

```c
#include "dsp/dsp_all.h"  // 마스터 헤더: 아래의 모든 모듈을 가져온다
```

의미 있는 고정소수점 형태를 갖는 모든 변환/필터는 **두 가지** 구현을 제공합니다 — `double` 정밀도 경로와, 이에 대응하는 Q8.24(`Fixed`) 경로(함수/타입 이름에 `f` 접두어가 붙습니다, 예: `fft`/`ffft`, `struct Biquad`/`struct FBiquad`) — 하나의 일관된 설계를 따릅니다: 한 번만 계산하면 되는 계수(FFT 트위들 팩터, 필터 계수)는 항상 `std/math.h`의 실제 초월함수를 사용해 `double` 정밀도로 유도된 다음 한 번에 `Fixed`로 양자화되고, 실제 샘플 단위 신호 처리 루프는 전부 Q8.24 산술로 실행됩니다. 이는 표준적인 관행입니다 — 하드웨어 FFT 가속기조차도 트위들 테이블을 오프라인에서 부동소수점으로 미리 계산합니다. 몇몇 더 새롭고 특수한 변환(STFT, CQT, CWT, 최소 위상, 이미징)은 `double` 전용입니다: 가우시안 윈도우 웨이블릿 커널이나 준동형 켑스트럼에는 자연스러운 고정소수점 형태가 없습니다 — 고정소수점 `sin()`이 없는 것과 마찬가지입니다.

---

## fixed — Q8.24 고정소수점 산술 {#fixed-q824-fixed-point-arithmetic}

```c
#include "dsp/fixed.h"
```

`Fixed`는 **Q8.24** 고정소수점 타입입니다: 정수부 8비트, 소수부 24비트로, 32비트 부호 있는 int로 저장됩니다(서브 샘플 정밀도를 위해 24비트 오디오 샘플 깊이와 일치합니다). `int` 위의 `newtype`이므로, 컴파일러는 동일한 비트 표현을 사용하면서도 이를 별개 타입으로 취급합니다.

### 상수 {#constants}

| 상수 | 값 | 의미 |
|----------|-------|---------|
| `FIXED_ONE` | 16777216 (2^24) | 1.0 |
| `FIXED_HALF` | 8388608 | 0.5 |
| `FIXED_PI` | 52707178 | ≈ π |

### 변환 {#conversion}

```c
Fixed  fixed_from_int(int x);       // x << 24
Fixed  fixed_from_float(double f);  // (Fixed)(f * 16777216.0)
int    fixed_to_int(Fixed x);       // x >> 24 (산술 시프트, -inf 방향으로 반올림)
double fixed_to_float(Fixed x);     // x / 16777216.0
```

### 산술 {#arithmetic}

```c
Fixed fixed_add(Fixed a, Fixed b);
Fixed fixed_sub(Fixed a, Fixed b);
Fixed fixed_mul(Fixed a, Fixed b);   // (a * b) >> 24, 64비트 중간값
Fixed fixed_div(Fixed a, Fixed b);   // (a << 24) / b, 64비트 중간값
Fixed fixed_abs(Fixed x);
Fixed fixed_neg(Fixed x);
Fixed fixed_sqrt(Fixed x);           // 뉴턴-랩슨, 4회 반복; x는 0 이상이어야 함
```

### 예제 {#example}

```c
#include "dsp/fixed.h"
#include "io.h"

int main() {
    Fixed a = fixed_from_float(3.5);
    Fixed b = fixed_from_float(2.0);
    Fixed product = fixed_mul(a, b);       // 7.0
    Fixed root    = fixed_sqrt(product);   // ~2.6458

    print("3.5 * 2.0 = ");
    println_float(fixed_to_float(product));
    print("sqrt of that ~= ");
    println_float(fixed_to_float(root));
    return 0;
}
```

---

## dsp — 배열 프리미티브 {#dsp-array-primitives}

```c
#include "dsp/dsp.h"
```

`std/dsp/`의 나머지 부분이 공유하는 기본적인 배열 수준 빌딩 블록입니다. `dsp_dot_f64`(`Fixed` 경로와 무관한 `double` 배열 프리미티브 — 아래 참고)를 제외하면 모두 힙 할당 없이 `Fixed*` 배열 위에서 동작합니다.

```c
Fixed dsp_dot(&stack Fixed a, &stack Fixed b, unsigned long n);           // a[i]*b[i]의 합
void  dsp_scale(&stack Fixed a, Fixed scale, unsigned long n);            // a[i] *= scale
void  dsp_add(&stack Fixed a, &stack Fixed b, &stack Fixed out, unsigned long n); // out[i] = a[i]+b[i]
void  dsp_clip(&stack Fixed a, Fixed lo, Fixed hi, unsigned long n);      // [lo, hi]로 클램프
Fixed dsp_peak(&stack Fixed a, unsigned long n);                          // max |a[i]|
Fixed dsp_rms(&stack Fixed a, unsigned long n);                           // sqrt(mean(a[i]^2))

// SIMD FMA로 가속되는 두 *double* 배열의 내적 (simd.h 참고) --
// simd_fma_f64x4를 통해 반복당 4개 원소씩, n % 4에 대한 스칼라 나머지 처리.
// Fixed 대응 버전은 없다: Q8.24 곱셈에는 원소마다 64비트 확장 시프트가
// 필요한데, std::simd의 정수 FMA는 이를 제공하지 않는다. convolution.h의
// conv_direct가 커널을 뒤집어 연속적인 접근 패턴으로 만든 뒤 내부적으로
// 사용한다; 직접 사용하는 것도 가능하다.
double dsp_dot_f64(const double* a, const double* b, unsigned long n);
```

::: tip
이전 버전의 이 모듈에 있던 `dsp_moving_avg`/`dsp_iir_lp`는 제거되었습니다 —
`filter.h`의 범용 스트리밍 `FirFilter`/`IirFilter`(1탭 이동 평균은 그저
`numTaps=1`인 `fir_init`이고, 1차 IIR 저역통과는 1극 `a[]`를 가진 `iir_init`입니다)와
`biquad.h`의 쿡북 설계 함수들로 대체되었으며, 둘 다 이전의 장난감 수준
구현이 아니라 실제로 검증된 DSP 구현입니다.
:::

---

## complex_dsp — 복소수 {#complex_dsp-complex-numbers}

```c
#include "dsp/complex_dsp.h"
```

연산자 오버로딩을 갖춘 값 타입 복소수로, 아래의 스펙트럼 모듈들(FFT 버터플라이, 트위들 팩터, 주파수 영역 곱셈)을 위해 특별히 만들어졌습니다 — `std/complex.h`의 C99 스타일 `[re, im]` 출력 배열 API와는 다릅니다. `w * a + b`는 포인터를 써가며 이어지는 `cmul_d`/`cadd_d` 호출 체인 대신 수식이 읽히는 그대로 읽힙니다.

```c
struct Complex {
    double re; double im;
    Complex operator+(Complex other) const;
    Complex operator-(Complex other) const;
    Complex operator*(Complex other) const;
    Complex operator/(Complex other) const;
    Complex neg() const;    // 단항 부호 반전 (연산자 오버로드는 인자 개수로 구분되지 않음)
    double  abs() const;    // sqrt(re^2 + im^2)
    double  arg() const;    // atan2(im, re)
    Complex conj() const;   // (re, -im)
};
Complex complex_new(double re, double im);
Complex complex_from_polar(double magnitude, double theta);  // (mag*cos(theta), mag*sin(theta))

struct FComplex {   // Q8.24 대응 버전 -- Fixed re/im, operator/를 제외하면 형태 동일
    Fixed re; Fixed im;
    FComplex operator+(FComplex other) const;
    FComplex operator-(FComplex other) const;
    FComplex operator*(FComplex other) const;
    FComplex neg() const;
    Fixed     abs() const;
    FComplex  conj() const;
};
FComplex fcomplex_new(Fixed re, Fixed im);
FComplex complex_to_fixed(Complex c);   // 설계 시점의 double 결과를 Q8.24로 양자화
Complex  fcomplex_to_float(FComplex c);
```

`a * b + c`는 이름 붙은 지역 변수들로 잘 동작하며, 임시값에 연산자 호출을 바로 체이닝하는 것도 마찬가지입니다 — `complex_new(x,0.0) * zInv`는 컴파일되어 올바르게 평가됩니다; 그 임시값은 이름 붙은 변수에 먼저 바인딩할 필요 없이 호출을 위해 숨겨진 스택 슬롯에 만들어집니다.

---

## dft — 이산 푸리에 변환 (직접 계산, O(n²)) {#dft-discrete-fourier-transform-direct-on}

```c
#include "dsp/dft.h"
```

직접 합산 방식 DFT입니다: `X[k] = sum x[n] * e^(-i*2*pi*k*n/N)`. 2의 거듭제곱뿐 아니라 **임의의** N에 대해 동작합니다 — 임의 길이의 변환에는 이것을 사용하고, `fft.h` 자체의 정확성 검증이 대조하는 독립적인 기준으로도 사용됩니다.

```c
void dft(const struct Complex* in, struct Complex* out, unsigned long n);       // 순변환, 복소수 -> 복소수
void dft_real(const double* in, struct Complex* out, unsigned long n);          // 순변환, 실수 -> 복소수
void idft(const struct Complex* in, struct Complex* out, unsigned long n);      // 역변환 (1/N 포함)
void fdft(const struct FComplex* in, struct FComplex* out, unsigned long n);    // Q8.24 순변환
void fidft(const struct FComplex* in, struct FComplex* out, unsigned long n);   // Q8.24 역변환
```

---

## fft — 고속 푸리에 변환 (radix-2 Cooley-Tukey) {#fft-fast-fourier-transform-radix-2-cooley-tukey}

```c
#include "dsp/fft.h"
```

제자리(in-place), 반복적인 시간 데시메이션 FFT입니다: `dft.h`의 O(n²) 대신 O(n log n)이지만, 그 대가로 `n`이 2의 거듭제곱이어야 합니다(그렇지 않으면 함수들은 0을 반환하고 `data`를 수정하지 않은 채로 둡니다 — 임의 길이에는 `dft.h`를 사용하세요).

```c
int dsp_is_pow2(unsigned long n);                                    // n >= 1이고 2의 거듭제곱이면
int fft(struct Complex* data, unsigned long n);                      // 제자리 순변환, 1=성공/0=2의거듭제곱아님
int ifft(struct Complex* data, unsigned long n);                     // 제자리 역변환 (1/N 포함)
int fft_real(const double* in, struct Complex* out, unsigned long n); // 실수 -> 복소수 편의 함수
int ffft(struct FComplex* data, unsigned long n);                    // Q8.24 순변환
int fifft(struct FComplex* data, unsigned long n);                   // Q8.24 역변환
```

### 예제 {#example-1}

```c
#include "dsp/fft.h"
#include "io.h"

int main() {
    struct Complex data[8];
    for (int i = 0; i < 8; i++) data[i] = complex_new((double)i, 0.0);

    fft(data, 8);     // 제자리 순변환
    ifft(data, 8);    // 제자리 역변환 -- 원래 신호로 왕복 복귀

    print("data[1].re after round-trip (expect ~1.0): ");
    println_float(data[1].re);
    return 0;
}
```

---

## convolution — 선형 컨볼루션 {#convolution-linear-convolution}

```c
#include "dsp/convolution.h"
```

`y[n] = sum_k x[k]*h[n-k]`, 출력 길이는 `len_x + len_h - 1`입니다. 결과는 같지만 비용 트레이드오프가 다른 두 가지 구현이 있습니다:

```c
// O(len_x * len_h), 할당 없음 -- 짧은 커널에 가장 적합.
void conv_direct(const double* x, unsigned long len_x,
                  const double* h, unsigned long len_h, double* out);
void fconv_direct(const Fixed* x, unsigned long len_x,
                   const Fixed* h, unsigned long len_h, Fixed* out);

// fft.h를 통한 O(n log n) (제로 패딩 FFT, 원소별 곱셈, 역 FFT)
// -- 직접 컨볼루션의 O(len_x*len_h) 비용이 부담이 되는 긴 입력/커널에 가장 적합.
// double 정밀도 전용.
void conv_fft(const double* x, unsigned long len_x,
              const double* h, unsigned long len_h, double* out);
```

세 함수 모두 `out`은 `len_x + len_h - 1` 샘플을 담을 공간이 있어야 합니다. `conv_direct`의 내부 루프는 SIMD로 가속됩니다: 커널을 미리 한 번 뒤집어서 누산이 `dsp_dot_f64`에 대한 연속 구간 호출이 되도록 합니다.

---

## window — 윈도우 함수 {#window-window-functions}

```c
#include "dsp/window.h"
```

FFT 이전 프레임을 테이퍼링하거나(스펙트럼 누설 감소) FIR 설계(윈도우 방법)를 위한 대칭 N포인트 분석 윈도우입니다. 네 함수 모두 호출자가 제공한 배열을 채우며, 할당은 하지 않습니다.

```c
void window_rectangular(double* w, unsigned long n);  // w[i] = 1 (테이퍼링 없음 -- 누설 기준선)
void window_hann(double* w, unsigned long n);         // 0.5*(1 - cos(2*pi*i/(n-1)))
void window_hamming(double* w, unsigned long n);       // 0.54 - 0.46*cos(2*pi*i/(n-1))
void window_blackman(double* w, unsigned long n);      // 0.42 - 0.5*cos(2*pi*i/(n-1)) + 0.08*cos(4*pi*i/(n-1))
void window_apply(double* x, const double* w, unsigned long n);  // x[i] *= w[i], 제자리 연산
```

---

## filter — 범용 스트리밍 FIR / IIR {#filter-general-streaming-fir-iir}

```c
#include "dsp/filter.h"
```

한 번에 한 샘플씩 처리하는 차분 방정식 필터로, 자체 이력/상태를 내부에 유지하므로 호출자가 (예를 들어 `std::Reactor` 태스크 안에서) `convolution.h`처럼 전체 신호를 메모리에 담아둘 필요 없이 실시간 샘플을 하나씩 넣어줄 수 있습니다.

```
FIR ("피드포워드"): y[n] = sum_{k=0}^{M-1} b[k]*x[n-k]
IIR ("피드백"):      y[n] = sum b[k]*x[n-k] - sum_{k=1}^{N-1} a[k]*y[n-k]   (a[0] = 1)
```

```c
struct FirFilter {
    const double* coeffs;   // b[0..numTaps-1], 호출자가 소유
    unsigned long numTaps;
    double*       history;  // 호출자가 제공하는 링 버퍼, numTaps개 원소
    unsigned long pos;

    double process(double x);
    void   reset();          // 이력을 0으로 초기화
};
struct FirFilter fir_init(const double* coeffs, unsigned long numTaps, double* history);
void fir_process_block(struct FirFilter* f, const double* in, double* out, unsigned long n);

struct IirFilter {           // 임의 차수, direct form I
    const double* b; unsigned long numB;   // 피드포워드
    const double* a; unsigned long numA;   // 피드백, a[0]은 1로 가정
    double* xHistory; double* yHistory;
    unsigned long xPos; unsigned long yPos;

    double process(double x);
    void   reset();
};
struct IirFilter iir_init(const double* b, unsigned long numB,
                           const double* a, unsigned long numA,
                           double* xHistory, double* yHistory);
void iir_process_block(struct IirFilter* f, const double* in, double* out, unsigned long n);
```

`struct FFirFilter`/`ffir_init`/`ffir_process_block`과 `struct FIirFilter`/`fiir_init`/`fiir_process_block`은 `double*` 대신 `Fixed*`를 쓰는, 동일한 형태의 Q8.24 대응 버전입니다.

### 예제 — 3탭 이동 평균 {#example-3-tap-moving-average}

```c
#include "dsp/filter.h"
#include "io.h"

int main() {
    double coeffs[3] = { 0.333333, 0.333333, 0.333333 };
    double history[3] = { 0.0, 0.0, 0.0 };
    struct FirFilter mavg = fir_init(&coeffs[0], 3UL, &history[0]);

    double step[6] = { 1.0, 1.0, 1.0, 1.0, 1.0, 1.0 };
    double out[6];
    fir_process_block(&mavg, &step[0], &out[0], 6UL);
    // out ~= [0.333, 0.667, 1, 1, 1, 1]
    println_float(out[2]);
    return 0;
}
```

---

## biquad — 2차 IIR 섹션 + RBJ 쿡북 {#biquad-2nd-order-iir-sections-rbj-cookbook}

```c
#include "dsp/biquad.h"
```

`struct Biquad`는 단일 Direct-Form-II-Transposed 2차 섹션입니다(`a0`은 항상 1로 정규화됨):

```
y[n] = b0*x[n] + z1
z1'  = b1*x[n] - a1*y[n] + z2
z2'  = b2*x[n] - a2*y[n]
```

더 높은 차수의 필터를 만들려면 여러 개를 종속 연결(cascade)하세요 — 각각에 대해 순서대로 `process()`를 호출하면 됩니다.

```c
struct Biquad {
    double b0, b1, b2, a1, a2, z1, z2;
    double process(double x);
    void   reset();
};
struct Biquad biquad_new(double b0, double b1, double b2, double a1, double a2);

// Robert Bristow-Johnson의 "Audio EQ Cookbook" 공식. fs = 샘플레이트,
// f0 = 컷오프/중심 주파수, q = Q 계수 (높을수록 더 좁고 공진이 강함).
struct Biquad biquad_lowpass(double fs, double f0, double q);
struct Biquad biquad_highpass(double fs, double f0, double q);
struct Biquad biquad_bandpass(double fs, double f0, double q);   // 0dB 피크 게인 일정
struct Biquad biquad_notch(double fs, double f0, double q);
struct Biquad biquad_allpass(double fs, double f0, double q);
struct Biquad biquad_peaking(double fs, double f0, double q, double dbGain);
struct Biquad biquad_lowshelf(double fs, double f0, double s, double dbGain);   // s = 셸프 기울기, 기본값 1.0
struct Biquad biquad_highshelf(double fs, double f0, double s, double dbGain);

// 쌍선형 변환을 통한 범용 아날로그-디지털 2차 섹션 변환:
// H(s) = (B2 s^2 + B1 s + B0) / (A2 s^2 + A1 s + A0) -> 대응하는 디지털 biquad로.
struct Biquad bilinear_2nd_order(double B0, double B1, double B2,
                                  double A0, double A1, double A2, double fs);

// freqHz에서의 Z영역 주파수 응답: 크기(선형)와 위상(라디안).
double biquad_response_mag(const struct Biquad* bq, double fs, double freqHz);
double biquad_response_phase(const struct Biquad* bq, double fs, double freqHz);

struct FBiquad {   // Q8.24 대응 버전
    Fixed b0, b1, b2, a1, a2, z1, z2;
    Fixed process(Fixed x);
    void  reset();
};
struct FBiquad biquad_to_fixed(struct Biquad bq);   // double로 설계된 biquad를 한 번 양자화
```

### 예제 {#example-2}

```c
#include "dsp/biquad.h"
#include "io.h"

int main() {
    struct Biquad lp = biquad_lowpass(48000.0, 1000.0, 0.707);

    // 단위 DC 게인 -- 저역통과 필터가 만족해야 하는 표준 불변식.
    print("|H(0)| (expect 1.0): ");
    println_float(biquad_response_mag(&lp, 48000.0, 0.0));

    double y = 0.0;
    for (int i = 0; i < 200; i++) y = lp.process(1.0);
    print("settled output for constant input 1.0 (expect ~1.0): ");
    println_float(y);
    return 0;
}
```

---

## dct — 이산 코사인 변환 (DCT-II / DCT-III) {#dct-discrete-cosine-transform-dct-ii-dct-iii}

```c
#include "dsp/dct.h"
```

`dct2`는 JPEG/MPEG에서 말하는 "그 DCT"입니다 — 실수 입력, 실수 출력, 에너지를 압축합니다. `idct3`는 그 정확한 역변환입니다. `dft.h`처럼 직접 O(n²) 합산 방식입니다.

```c
void dct2(const double* in, double* out, unsigned long n);    // X[k] = sum x[n]*cos(pi/N*(n+0.5)*k)
void idct3(const double* in, double* out, unsigned long n);   // dct2의 정확한 역변환
void fdct2(const Fixed* in, Fixed* out, unsigned long n);      // Q8.24 순변환
void fidct3(const Fixed* in, Fixed* out, unsigned long n);     // Q8.24 역변환
```

---

## stft — 단시간 푸리에 변환 + 다중 해상도 STFT 손실 {#stft-short-time-fourier-transform-multi-resolution-stft-loss}

```c
#include "dsp/stft.h"
```

`stft_forward`는 윈도우가 적용된 프레임을 신호를 따라 슬라이드시키며 각각을 FFT하여(`frameSize`는 2의 거듭제곱이어야 함) 스펙트로그램을 만듭니다. `stft_inverse`는 윈도우가 적용된 오버랩-애드 방식으로 신호를 재구성하며, 누적된 분석 윈도우 제곱값으로 정규화합니다 — 윈도우/홉 조합이 상수 오버랩-애드 조건을 만족할 때(예: 50%/75% 오버랩의 Hann) 정확합니다.

```c
#define STFT_WIN_RECTANGULAR 0
#define STFT_WIN_HANN 1
#define STFT_WIN_HAMMING 2
#define STFT_WIN_BLACKMAN 3

unsigned long stft_num_frames(unsigned long signalLen, unsigned long frameSize, unsigned long hopSize);

// out: stft_num_frames(...) * frameSize개의 Complex 값을 담을 공간.
void stft_forward(const double* signal, unsigned long signalLen,
                   unsigned long frameSize, unsigned long hopSize,
                   int windowType, struct Complex* out);

// outSignal: (numFrames-1)*hopSize + frameSize개 샘플을 담을 공간, 호출자가
// 0으로 초기화 (stft_inverse가 그 안에 누적함).
void stft_inverse(const struct Complex* frames, unsigned long numFrames,
                   unsigned long frameSize, unsigned long hopSize,
                   int windowType, double* outSignal);
```

`mr_stft_loss`는 신경 보코더 문헌(Yamamoto 등, Parallel WaveGAN)에서 말하는 "다중 해상도 STFT 손실"을 계산합니다: `numResolutions`개의 각 프레임 크기(각각 2의 거듭제곱, hop = frameSize/4, Hann 윈도우)에 대해, 같은 길이의 두 신호 사이의 스펙트럼 수렴도와 평균 L1 로그 크기 오차를 계산한 뒤 여러 해상도에 걸쳐 평균 냅니다:

```c
double mr_stft_loss(const double* x, const double* y, unsigned long len,
                     const unsigned long* frameSizes, unsigned long numResolutions);
```

값이 낮을수록 더 유사합니다; 동일한 신호라면 `0`입니다.

---

## resample — 업샘플링 / 다운샘플링 {#resample-upsampling-downsampling}

```c
#include "dsp/resample.h"
```

`inLen` 샘플 버퍼를 호출자가 지정한 크기의 `outLen` 샘플 버퍼로 매핑합니다 — `outLen > inLen`이면 업샘플링, `outLen < inLen`이면 다운샘플링이며, 어느 방향이든 같은 함수를 사용합니다.

```c
void resample_nearest(const double* in, unsigned long inLen, double* out, unsigned long outLen);
void resample_linear(const double* in, unsigned long inLen, double* out, unsigned long outLen);

// 윈도우가 적용된 sinc(대역 제한): 가장 품질이 높은 옵션. 다운샘플링할
// 때는 커널의 컷오프가 새로운(더 낮은) 나이퀴스트 레이트에 맞춰 자동으로
// 스케일되므로, 앤티에일리어싱 필터 역할도 겸합니다. kernelHalfWidth의
// 기본값으로는 8-16이 적당합니다.
void resample_sinc(const double* in, unsigned long inLen, double* out, unsigned long outLen,
                    unsigned long kernelHalfWidth);

// Q8.24 nearest/linear. sinc 대응 버전은 없다 -- 윈도우 적용된 sinc
// 커널은 런타임의 소수 위치에 대해 탭마다 sin/cos를 계산해야 하는데,
// 이는 일반적인 설계 시점-float/런타임-fixed 분리 방식에 맞지 않는다.
void fresample_nearest(const Fixed* in, unsigned long inLen, Fixed* out, unsigned long outLen);
void fresample_linear(const Fixed* in, unsigned long inLen, Fixed* out, unsigned long outLen);
```

---

## ztransform — 범용 N차 쌍선형 변환 {#ztransform-general-n-th-order-bilinear-transform}

```c
#include "dsp/ztransform.h"
```

`biquad.h`의 `bilinear_2nd_order`/`biquad_response_*`를 임의 차수로 일반화한 것입니다 — biquad들의 종속 연결이 아니라 더 높은 차수의 IIR 섹션 하나를 직접 설계/분석할 때 사용합니다. 다항식 근 찾기 기능은 포함되어 있지 않습니다(별도의 반복적인 고유값/Durand-Kerner 알고리즘이 필요합니다); 대신 계수 배열을 직접 다룹니다.

```c
// B, A: order+1개의 아날로그(s영역) 계수, s의 오름차순 거듭제곱.
// bOut, aOut: 음의 z 거듭제곱 표기법(aOut[0] = 1)에서의 order+1개
// 디지털 계수 -- filter.h의 iir_init에 그대로 넣으면 된다.
void bilinear_nth_order(const double* B, const double* A, unsigned long order,
                         double fs, double* bOut, double* aOut);

double ztransform_response_mag(const double* b, const double* a, unsigned long order,
                                double fs, double freqHz);
double ztransform_response_phase(const double* b, const double* a, unsigned long order,
                                  double fs, double freqHz);
```

---

## comb — 콤 필터 + Karplus-Strong 현악기 합성 {#comb-comb-filters-karplus-strong-string-synthesis}

```c
#include "dsp/comb.h"
```

`CombFF`(피드포워드, `y[n] = x[n] + gain*x[n-delay]`)는 균등한 간격의 노치를 만들고; `CombFB`(피드백, `y[n] = x[n] + gain*y[n-delay]`)는 균등한 간격의 공진 피크를 만듭니다(대부분의 인공 리버브의 기반입니다 — 서로 다른 딜레이/게인을 가진 피드백 콤들의 뱅크).

```c
struct CombFF {
    double* history; unsigned long delay; unsigned long pos; double gain;
    double process(double x);
    void   reset();
};
struct CombFB {
    double* history; unsigned long delay; unsigned long pos; double gain;
    double process(double x);
    void   reset();
};
struct CombFF comb_ff_init(double* history, unsigned long delay, double gain);
struct CombFB comb_fb_init(double* history, unsigned long delay, double gain);
```

`KarplusStrong`은 고전적인 발현(plucked-string)/타악기 합성 알고리즘입니다(Karplus & Strong, 1983): 피드백 경로가 단순 스칼라 게인이 아니라 2탭 평균 저역통과인 피드백 콤입니다 — 이 평균화가 루프를 돌 때마다 고주파 에너지를 점진적으로 제거하여, 현악기 특유의 밝게 시작했다가 감쇠하는 음색을 만들어냅니다.

```c
struct KarplusStrong {
    double* buffer;         // 링 버퍼; 딜레이 라인이자 피치 주기 역할도 함
    unsigned long length; unsigned long pos;
    double damping;         // 0..1, 1에 가까울수록 감쇠가 느림 (보통 0.99-0.999)

    double process();       // 입력 없음 -- 스스로 지속되는 발현음
    void   reset(const double* excitation, unsigned long n);  // 새 발현음으로 시딩
};
struct KarplusStrong ks_init(double* buffer, unsigned long length, double damping);
```

`struct FCombFF`/`FCombFB`/`FKarplusStrong`과 `comb_ff_init_fixed`/`comb_fb_init_fixed`/`ks_init_fixed`는 Q8.24 대응 버전입니다.

### 예제 — 발현 현악기 {#example-plucked-string}

```c
#include "dsp/comb.h"
#include "io.h"

int main() {
    double excitation[8] = { 0.5, -0.3, 0.8, -0.6, 0.2, -0.9, 0.4, -0.1 };  // 노이즈 버스트
    double buf[8];
    struct KarplusStrong ks = ks_init(&buf[0], 8UL, 0.995);
    ks.reset(&excitation[0], 8UL);

    for (int i = 0; i < 16; i++) {
        double sample = ks.process();
        println_float(sample);   // 감쇠하며 스스로 지속되는 "발현음"
    }
    return 0;
}
```

---

## minphase — 최소 위상 재구성 {#minphase-minimum-phase-reconstruction}

```c
#include "dsp/minphase.h"
```

임펄스 응답(예: 목표 크기 응답으로부터 설계된 선형 위상 FIR)이 주어지면, **동일한 크기 스펙트럼**을 가지면서 가능한 최소 군지연(group delay)을 갖는 다른 임펄스 응답을 만들어냅니다 — 동일한 주파수 응답에 대해 선형 위상 필터의 지연 시간을 절반으로 줄입니다. 고전적인 준동형(실수 켑스트럼) 알고리즘입니다: 로그 크기 스펙트럼 -> 켑스트럼 -> 비인과 절반을 인과 절반 위로 접기(이것이 결과를 최소 위상으로 강제하는 부분) -> 스펙트럼 영역을 다시 거쳐 지수화.

```c
// x: n개 샘플 (n <= fftLen); fftLen은 2의 거듭제곱이어야 하며, 정확한
// 켑스트럴 리프터링을 위해 n보다 충분히 커야 함 (경험적으로 4-8배 정도가
// 적당함). outMinPhase: fftLen개 샘플을 담을 공간 -- 에너지가 시작 부분에
// 집중되며, 호출자는 보통 처음 n개 정도의 샘플만 남겨서 사용함.
void minimum_phase(const double* x, unsigned long n, unsigned long fftLen, double* outMinPhase);
```

---

## cqt — 상수-Q 변환 {#cqt-constant-q-transform}

```c
#include "dsp/cqt.h"
```

DFT/FFT의 균일한 주파수 간격과 달리, CQT는 모든 빈(bin)에서 일정한 비율 `fk/bandwidth`("Q")를 유지하는 로그 간격 중심 주파수 `fk = fmin * 2^(k/binsPerOctave)`를 사용합니다 — 음악적 음정과 인간의 청각이 대략 주파수에 대해 로그적이라는 사실과 맞아떨어지며, 이것이 크로마그램과 음정 검출에서 (단순 DFT가 아니라) CQT가 표준적으로 선택되는 이유입니다. 직접 상관(direct-correlation) 형태입니다(Brown 1991): 각 빈 고유 길이의 Hann 윈도우가 적용된 복소 지수 커널이 신호의 시작 부분과 직접 상관되며 — FFT도, 커널 행렬의 사전 계산도 없이 — 호출당 O(numBins * 평균 커널 길이)입니다.

```c
// signal은 최소한 가장 낮은(가장 긴 커널을 갖는) 빈에 필요한 만큼의
// 샘플을 가져야 한다 -- 대략 Q*fs/fmin, Q = 1/(2^(1/binsPerOctave)-1) --
// 그렇지 않으면 해당 빈의 커널이 signalLen에 맞춰 조용히 잘린다
// (오류가 아니라 해당 빈의 해상도 저하). out: numBins개의 Complex 값을
// 담을 공간.
void cqt_forward(const double* signal, unsigned long signalLen, double fs,
                  double fmin, unsigned long numBins, unsigned long binsPerOctave,
                  struct Complex* out);
```

---

## cwt — 연속 웨이블릿 변환 (Morlet) {#cwt-continuous-wavelet-transform-morlet}

```c
#include "dsp/cwt.h"
```

CQT가 로그 간격 주파수 *빈*을 선택하는 반면, CWT는 (호출자가 선택하는, 보통 로그 간격인) *스케일* 집합을 직접 선택하고 스케일/이동된 Morlet 웨이블릿을 모든 샘플 위치에서 신호와 상관시켜, 프레임당 값 하나가 아니라 완전한 시간-스케일 "스칼로그램"을 만들어냅니다. 스케일 `s`는 대략 `f = w0*fs / (2*pi*s)` Hz의 중심 주파수에 대응합니다. 직접 상관 방식이며, 커널 지지(support)는 가우시안 포락선의 표준편차 ±4배로 잘립니다.

```c
// scales: numScales개 항목 (각각 0보다 커야 함). w0: Morlet 중심 주파수
// 파라미터 (5-6이 표준). out: numScales*signalLen개의 Complex 값을 담을
// 공간, 스케일 기준 행 우선(row-major) 순서.
void cwt_morlet(const double* signal, unsigned long signalLen,
                 const double* scales, unsigned long numScales,
                 double w0, struct Complex* out);
```

---

## imaging — 2D DSP {#imaging-2d-dsp}

```c
#include "dsp/imaging.h"
```

위의 1D 빌딩 블록들에 대응하는 2D 버전입니다: `conv2d`는 제로 패딩된("same" 크기) 2D 컨볼루션이고; `fft2d`/`ifft2d`는 `fft.h`의 1D 변환을 모든 행에, 그다음 모든 열에 적용합니다(2D DFT는 분리 가능(separable)하므로 정확합니다); 커널 생성 함수들은 거의 모든 이미지 파이프라인에 필요한 두 가지 컨볼루션 커널을 다룹니다.

```c
// img: h*w개, 행 우선; kernel: kh*kw개, 행 우선 (kh/kw는 홀수여야 함);
// out: h*w개의 double을 담을 공간. 범위를 벗어난 커널 탭은 0으로 취급.
void conv2d(const double* img, unsigned long h, unsigned long w,
            const double* kernel, unsigned long kh, unsigned long kw, double* out);

void fft2d(struct Complex* img, unsigned long h, unsigned long w);   // 제자리 연산, h와 w는 2의 거듭제곱이어야 함
void ifft2d(struct Complex* img, unsigned long h, unsigned long w);

void gaussian_kernel(double* kernel, unsigned long size, double sigma);  // 정규화됨 (합이 1)
void sobel_x_kernel(double* kernel);   // 고정 3x3, 9개의 double을 담을 공간
void sobel_y_kernel(double* kernel);
```

---

## AudioBuffer — 락프리 다중 채널 링 버퍼 {#audiobuffer-lock-free-multi-channel-ring-buffer}

```c
#include "dsp/audio_buffer.h"
```

인터리빙된 다중 채널 오디오 프레임을 위한 SPSC(단일 생산자/단일 소비자) 링 버퍼입니다. OS 락 없이 올바른 순서를 보장하기 위해 2의 거듭제곱 용량과 컴파일러 배리어를 사용합니다.

### 구조체 {#struct}

```c
struct AudioBuffer {
    Fixed*        buf;          // 인터리빙: frame0_ch0, frame0_ch1, ...
    unsigned long cap_frames;   // 2의 거듭제곱이어야 함
    unsigned long channels;
    unsigned long head;         // 쓰기 커서 (volatile)
    unsigned long tail;         // 읽기 커서 (volatile)

    unsigned long write_frames(const Fixed* frames, unsigned long count);
    unsigned long read_frames(Fixed* out, unsigned long count);
    unsigned long peek_frames(Fixed* out, unsigned long count) const;
    void          mix_frames(const Fixed* frames, unsigned long count);
    unsigned long readable() const;
    unsigned long writable() const;
}

AudioBuffer audio_buffer_new(unsigned long cap_frames, unsigned long channels);
void        audio_buffer_free(struct AudioBuffer* ab);
```

### 예제 — 생산자 / 소비자 {#example-producer-consumer}

```c
#include "dsp/audio_buffer.h"
#include "io.h"

int main() {
    // 256프레임 버퍼, 2채널 (스테레오)
    AudioBuffer ab = audio_buffer_new(256, 2);

    // 생산자: 스테레오 프레임 하나를 쓴다
    Fixed frame[2] = { fixed_from_float(0.5), fixed_from_float(-0.3) };
    ab.write_frames(frame, 1);

    print("readable: ");
    println_int(ab.readable());  // 1

    // 소비자: 다시 읽는다
    Fixed out[2];
    ab.read_frames(out, 1);
    print("L = "); println_float(fixed_to_float(out[0]));   //  0.5
    print("R = "); println_float(fixed_to_float(out[1]));   // -0.3

    audio_buffer_free(&ab);
    return 0;
}
```

::: info
`mix_frames`는 현재 쓰기 위치에서 기존 버퍼 내용에 `frames`를 더할 뿐, `head`를 **전진시키지는 않습니다**. 이는 하나의 출력 버퍼로 커밋하기 전에 여러 소스를 믹싱하기 위한 것입니다. 커서를 전진시키려면 이후에 `write_frames`를 호출하세요.
:::

---

## TimerWheel — O(1) 실시간 타이머 {#timerwheel-o1-real-time-timer}

```c
#include "dsp/timer_wheel.h"
```

최대 64개의 동시 타이머를 지원하는 256슬롯 타이머 휠입니다. `tick()`은 만료된 모든 타이머를 O(timers)로 발동시킵니다 — 우선순위 큐가 필요 없습니다.

### 상수 {#constants-1}

```c
#define WHEEL_SLOTS      256
#define WHEEL_MAX_TIMERS  64
```

### 구조체 {#struct-1}

```c
struct TimerEntry {
    void(*callback)(void* ctx);
    void*         ctx;
    unsigned long expires;  // 발동할 절대 tick
    unsigned long period;   // 0 = 일회성; >0 = `period` tick마다 반복
    int           active;
}

struct TimerWheel {
    TimerEntry    entries[WHEEL_MAX_TIMERS];
    unsigned long current_tick;
    unsigned int  used;

    int  add(void(*cb)(void*), void* ctx, unsigned long delay_ticks);
    int  add_periodic(void(*cb)(void*), void* ctx, unsigned long period_ticks);
    int  cancel(int id);
    void tick();
}

TimerWheel timer_wheel_new();
```

### 메서드 {#methods}

| 메서드 | 설명 |
|--------|-------------|
| `add(cb, ctx, delay)` | 일회성 타이머; `delay` tick 후 발동. 타이머 id 또는 가득 찼으면 -1을 반환. |
| `add_periodic(cb, ctx, period)` | 반복 타이머; `period` tick마다 발동하며 자동으로 재스케줄됨. |
| `cancel(id)` | 타이머를 비활성화. 찾았으면 1, 아니면 0을 반환. |
| `tick()` | `current_tick`을 전진시키고, `expires == current_tick`인 모든 타이머를 발동. |

### 예제 {#example-3}

```c
#include "dsp/timer_wheel.h"
#include "io.h"

int blink_count = 0;
int done = 0;

void blink(void* ctx)     { blink_count++; }
void shutdown(void* ctx)  { done = 1; }

int main() {
    TimerWheel tw = timer_wheel_new();

    tw.add_periodic(blink, 0, 10);   // 10 tick마다 깜빡임
    tw.add(shutdown, 0, 55);          // 55 tick 후 정지

    while (!done) {
        tw.tick();
    }

    print("blink count: ");
    println_int(blink_count);  // 5 (10,20,30,40,50 tick)
    return 0;
}
```

::: warning
타이머 만료는 `expires == current_tick`으로 확인됩니다. `tick()`이 예상보다 드물게 호출되면(예: 인터럽트 지연 스파이크 이후) 타이머가 누락될 수 있습니다. 중요한 데드라인에는 대신 `expires <= current_tick`으로 폴링하거나, 하드웨어 타이머 ISR로 `tick()`을 구동하세요.
:::
