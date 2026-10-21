# DSP 및 실시간 유틸리티

`std/dsp/`는 결정론적 고정소수점 산술, 스펙트럼 변환(DFT/FFT/DCT/STFT/CQT/CWT), 필터(FIR/IIR/biquad/comb), 컨볼루션, 리샘플링, 최소 위상 재구성, 일반적인 라플라스-Z(쌍선형) 변환 도구, 2D/이미징 프리미티브, 오디오 버퍼링, 실시간 타이머 스케줄링을 아우릅니다 — 총 20개의 모듈입니다.

```c
#include <std/dsp/dsp_all.h>  // master header: pulls in every module below
```

의미 있는 고정소수점 형태를 갖는 모든 변환/필터는 **두 가지** 구현을 제공합니다 — `double` 정밀도 경로와, 이에 대응하는 Q8.24(`Fixed`) 경로(함수/타입 이름에 `f` 접두어가 붙습니다, 예: `fft`/`ffft`, `struct Biquad`/`struct FBiquad`) — 하나의 일관된 설계를 따릅니다: 한 번만 계산하면 되는 계수(FFT 트위들 팩터, 필터 계수)는 항상 `std/math.h`의 실제 초월함수를 사용해 `double` 정밀도로 유도된 다음 한 번에 `Fixed`로 양자화되고, 실제 샘플 단위 신호 처리 루프는 전부 Q8.24 산술로 실행됩니다. 이는 표준적인 관행입니다 — 하드웨어 FFT 가속기조차도 트위들 테이블을 오프라인에서 부동소수점으로 미리 계산합니다. 몇몇 더 새롭고 특수한 변환(STFT, CQT, CWT, 최소 위상, 이미징)은 `double` 전용입니다: 가우시안 윈도우 웨이블릿 커널이나 준동형 켑스트럼에는 자연스러운 고정소수점 형태가 없습니다 — 고정소수점 `sin()`이 없는 것과 마찬가지입니다.

신호 처리 경로는 단정밀도(`float`)로도 제공됩니다. 아래 [단정밀도](#single-precision-float)를 참고하세요.

---

## 단정밀도 (`float`) {#single-precision-float}

FFT, 필터, 컨볼루션, 윈도우, STFT, 리샘플링, DCT/DFT, 이미징은 `float` 데이터도 받습니다. 함수 이름에는 `_f32`, 타입 이름에는 `32`가 붙습니다(`Complex32`, `FirFilter32`, `Biquad32`). 각 함수의 동작 규약은 `double` 버전과 같습니다. float는 메모리 트래픽이 절반이고, 벡터 레지스터 하나에 두 배의 값이 들어갑니다. 유효 숫자는 약 16자리 대신 약 7자리(상대 오차 약 1e-6)입니다.

- **두 정밀도에 하나의 구현.** FFT, 컨볼루션, 리샘플링, 2D 코드는 내부 코어 헤더(`fft_core.h`, `conv_core.h`, `resample_core.h`, `imaging_core.h`)에 한 번만 작성되어 있습니다. 각 코어는 `fft.sc`와 `fft_f32.sc` 등에서 정밀도마다 한 번씩, 두 번 컴파일됩니다. 따라서 float FFT도 radix-4 패스, 16점 코드렛, 캐시 블로킹, 스레드, ISA별 빌드를 그대로 갖습니다. oneMKL이 링크되면 DFTI의 단정밀도 변환을 사용합니다.
- **정밀도가 필요한 곳은 double.** 트위들 팩터와 윈도우 값은 `double`로 계산한 뒤 float로 한 번 반올림합니다. 필터 계수는 `double`로 설계하고(`biquad_lowpass` 등) `biquad_to_f32`로 변환합니다. O(n²)인 `dct2_f32`/`dft_f32`와 `resample_sinc_f32`의 sinc 커널은 `double`로 합산하므로, 입력과 출력만 float입니다.
- **실시간.** `fft_serial_f32`/`ifft_serial_f32`는 double 버전과 마찬가지로 메모리 할당이나 잠금을 하지 않습니다. 4096점까지는 컴파일 타임 float 트위들 테이블을 읽고, 그보다 긴 길이는 `fft_prepare_f32(maxN)`을 먼저 호출하세요. `fft_prepare`는 double 테이블만 채웁니다.

```c
// complex_dsp.h
struct Complex32 { float re; float im; /* + - * / neg abs arg conj, as Complex */ };
Complex32 complex32_new(float re, float im);
Complex32 complex32_from_polar(float magnitude, float theta);
Complex32 complex_to_f32(Complex c);      // round each part to float
Complex   complex_from_f32(Complex32 c);

// fft.h
int fft_f32(&Complex32 data, unsigned long n);
int ifft_f32(&Complex32 data, unsigned long n);
int fft_serial_f32(&Complex32 data, unsigned long n);
int ifft_serial_f32(&Complex32 data, unsigned long n);
int fft_prepare_f32(unsigned long n);
int fft_real_f32(const &float in, &Complex32 out, unsigned long n);
int fft_batch_f32(&Complex32 data, unsigned long n, unsigned long batch, int inverse);
int fft_real_batch_f32(const &float in, &Complex32 out, unsigned long n, unsigned long batch);
int fft_real_half_batch_f32(const &float in, &Complex32 out, unsigned long n, unsigned long batch);   // and fft_real_half_f32

// dsp.h
float dsp_dot_f32(const &float a, const &float b, unsigned long n);   // f32x8 FMA accumulators

// filter.h, biquad.h, comb.h
struct FirFilter32 fir_init_f32(const &float coeffs, unsigned long numTaps, &float history);
void fir_process_block_f32(&FirFilter32 f, const &float in, &float out, unsigned long n);
struct IirFilter32 iir_init_f32(const &float b, unsigned long numB, const &float a, unsigned long numA,
                                &float xHistory, &float yHistory);
void iir_process_block_f32(&IirFilter32 f, const &float in, &float out, unsigned long n);
struct Biquad32 biquad_new_f32(float b0, float b1, float b2, float a1, float a2);
struct Biquad32 biquad_to_f32(struct Biquad bq);   // a double design, rounded once
void biquad_process_block_f32(&Biquad32 bq, const &float in, &float out, unsigned long n);
struct CombFF32 comb_ff_init_f32(&float history, unsigned long delay, float gain);
struct CombFB32 comb_fb_init_f32(&float history, unsigned long delay, float gain);
struct KarplusStrong32 ks_init_f32(&float buffer, unsigned long length, float damping);

// convolution.h, window.h, stft.h, resample.h
void conv_direct_f32(const &float x, unsigned long len_x, const &float h, unsigned long len_h, &float out);
void conv_fft_f32(const &float x, unsigned long len_x, const &float h, unsigned long len_h, &float out);
void window_hann_f32(&float w, unsigned long n);       // also _rectangular_, _hamming_, _blackman_f32
void window_apply_f32(&float x, const &float w, unsigned long n);
void stft_forward_f32(const &float signal, unsigned long signalLen, unsigned long frameSize,
                      unsigned long hopSize, int windowType, &Complex32 out);
void stft_inverse_f32(const &Complex32 frames, unsigned long numFrames, unsigned long frameSize,
                      unsigned long hopSize, int windowType, &float outSignal);
void resample_sinc_f32(const &float in, unsigned long inLen, &float out, unsigned long outLen,
                       unsigned long kernelHalfWidth);   // also resample_nearest_f32, resample_linear_f32

// dct.h, dft.h, imaging.h
void dct2_f32(const &float in, &float out, unsigned long n);
void idct3_f32(const &float in, &float out, unsigned long n);
void dft_f32(const &Complex32 in, &Complex32 out, unsigned long n);   // also dft_real_f32, idft_f32
void conv2d_f32(const &float img, unsigned long h, unsigned long w,
                const &float kernel, unsigned long kh, unsigned long kw, &float out);
void fft2d_f32(&Complex32 img, unsigned long h, unsigned long w);    // and ifft2d_f32
```

분석 전용 모듈(CQT, CWT, 최소 위상, z-변환 설계)은 `double`만 지원합니다.

::: warning float에서의 고차 IIR
`IirFilter32`는 직접형 필터라서, 차수가 높을수록 계수의 float 반올림 오차가 커집니다. 2차보다 가파른 필터는 `Biquad32` 섹션을 직렬로 연결하세요.
:::

---

## fixed — Q8.24 고정소수점 산술 {#fixed-q824-fixed-point-arithmetic}

```c
#include <std/dsp/fixed.h>
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
#include <std/dsp/fixed.h>
#include <std/io.h>

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
#include <std/dsp/dsp.h>
```

`std/dsp/`의 나머지 부분이 공유하는 기본적인 배열 수준 빌딩 블록입니다. `dsp_dot_f64`(`Fixed` 경로와 무관한 `double` 배열 프리미티브 — 아래 참고)를 제외하면 모두 힙 할당 없이 `Fixed` 배열(첫 원소에 대한 `&Fixed`로 전달) 위에서 동작합니다.

```c
Fixed dsp_dot(const &Fixed a, const &Fixed b, unsigned long n);           // a[i]*b[i]의 합
void  dsp_scale(&stack Fixed a, Fixed scale, unsigned long n);            // a[i] *= scale
void  dsp_add(const &Fixed a, const &Fixed b, &Fixed out, unsigned long n); // out[i] = a[i]+b[i]
void  dsp_clip(&stack Fixed a, Fixed lo, Fixed hi, unsigned long n);      // [lo, hi]로 클램프
Fixed dsp_peak(const &Fixed a, unsigned long n);                          // max |a[i]|
Fixed dsp_rms(&stack Fixed a, unsigned long n);                           // sqrt(mean(a[i]^2))

// SIMD FMA로 가속되는 두 *double* 배열의 내적 (simd.h 참고) --
// simd_fma_f64x4를 통해 반복당 4개 원소씩, n % 4에 대한 스칼라 나머지 처리.
// Fixed 대응 버전은 없다: Q8.24 곱셈에는 원소마다 64비트 확장 시프트가
// 필요한데, std::simd의 정수 FMA는 이를 제공하지 않는다. convolution.h의
// conv_direct가 커널을 뒤집어 연속적인 접근 패턴으로 만든 뒤 내부적으로
// 사용한다; 직접 사용하는 것도 가능하다.
double dsp_dot_f64(const &double a, const &double b, unsigned long n);

// q < nOut에 대해 out[q] = dot(a, x[q .. q+n)): 여러 출력에 대한 FIR/상관.
// 한 번에 출력 32개(float는 64개), 탭마다 값을 네 개의 누산 벡터에
// 브로드캐스트: 수평 합이 없고, 곱셈-덧셈마다 로드가 하나입니다.
void dsp_sliding_dot_f64(const &double a, const &double x, &double out, unsigned long nOut, unsigned long n);
void dsp_sliding_dot_f32(const &float a, const &float x, &float out, unsigned long nOut, unsigned long n);
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
#include <std/dsp/complex_dsp.h>
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
#include <std/dsp/dft.h>
```

직접 합산 방식 DFT입니다: `X[k] = sum x[n] * e^(-i*2*pi*k*n/N)`. 2의 거듭제곱뿐 아니라 **임의의** N에 대해 동작합니다 — 임의 길이의 변환에는 이것을 사용하고, `fft.h` 자체의 정확성 검증이 대조하는 독립적인 기준으로도 사용됩니다. 회전 인자는 항마다 `cos`/`sin`을 부르지 않고 n개의 정확한 단위근 테이블에서 k·n mod N 인덱스로 읽으며, 256점부터 출력 빈을 스레드 풀에 나눕니다.

```c
void dft(const &Complex in, &Complex out, unsigned long n);       // 순변환, 복소수 -> 복소수
void dft_real(const &double in, &Complex out, unsigned long n);          // 순변환, 실수 -> 복소수
void idft(const &Complex in, &Complex out, unsigned long n);      // 역변환 (1/N 포함)
void fdft(const &FComplex in, &FComplex out, unsigned long n);    // Q8.24 순변환
void fidft(const &FComplex in, &FComplex out, unsigned long n);   // Q8.24 역변환
```

---

## fft — 고속 푸리에 변환 (radix-2 Cooley-Tukey) {#fft-fast-fourier-transform-radix-2-cooley-tukey}

```c
#include <std/dsp/fft.h>
```

제자리(in-place), 반복적인 시간 데시메이션 FFT입니다: `dft.h`의 O(n²) 대신 O(n log n)이지만, 그 대가로 `n`이 2의 거듭제곱이어야 합니다(그렇지 않으면 함수들은 0을 반환하고 `data`를 수정하지 않은 채로 둡니다 — 임의 길이에는 `dft.h`를 사용하세요).

실행 방식:

- **트위들 테이블.** 트위들 계수는 모든 스레드가 공유하는 테이블 하나에서 옵니다. 어떤 길이가 처음 필요할 때 만들어져 유지되며, 커지기만 하므로 읽는 쪽은 락을 잡지 않습니다.
- **합쳐진 패스.** 생성된 16점 코드렛이 처음 네 단계를 레지스터 안에서 실행합니다. 나머지 단계는 radix-8 패스(데이터를 한 번 훑을 때마다 세 단계, W8 상수는 코드에 접어 넣음. SPIRAL/FFTE의 radix-8/16 분해와 같은 방식)로 합치고, 남는 단계는 radix-4나 radix-2로 처리합니다. 4096점 변환은 radix-2 11번 대신 코드렛과 패스 3번입니다. 4096점까지는 한 블록으로 실행하고, 더 긴 변환은 2048점까지의 단계를 블록 단위로 실행해 블록 하나가 그 단계들 내내 L1/L2에 머뭅니다.
- **SIMD 폭.** 레지스터에 들어가는 경우 버터플라이는 벡터 하나에 복소수 네 개씩 처리합니다(AVX2의 float, AVX-512의 두 정밀도). 그 밖에는 두 개씩입니다(AVX2의 double, SSE2, Neon).
- **비트 역순.** 4096점까지는 비트 역순 교환을 컴파일 타임 인덱스 쌍 목록에서 가져오므로, 원소마다 인덱스를 계산하거나 예측하기 어려운 분기가 없습니다. 그보다 크면 점진적으로 계산하며 스레드에 나눕니다.
- **스레드.** 32768점 이상의 변환은 [스레드 풀](/ko/stdlib/sync#parallel)에 나뉘어 실행됩니다. `fft_batch`와 `fft_real_batch`는 여러 변환을 풀에 분산합니다.
- **실수 입력.** `fft_real`은 n/2점 복소 변환 하나로 n점 전체 스펙트럼을 계산합니다. 분리 단계는 양 끝에서 빈 두 개씩 벡터로 처리합니다. `fft_real_half` / `fft_real_half_batch`는 FFTW나 NumPy처럼 중복되지 않는 n/2 + 1개 빈만 돌려주므로, 배치가 쓰는 양이 절반이 됩니다.
- **SIMD 수준.** 버터플라이는 [CPU SIMD 수준](/ko/stdlib/simd#cpu-levels)별로 빌드됩니다. AVX2에서는 복소 곱셈에 FMA가 더해지고, AVX-512에서는 폭이 두 배가 됩니다.
- **벤더 FFT.** oneMKL이 링크되어 있으면([`[build] blas`](/ko/advanced/safeguard#linking-external-libraries-and-library-output)의 Intel 선택), 65,536점 미만의 `fft`/`ifft`/`fft_real`, `fft_batch`/`fft_real_batch`의 모든 행, `fft2d`를 그 DFTI 변환이 실행합니다. 이 페이지의 7800X3D에서 `fft2d`와 배치 변환이 절반으로 줄어듭니다. `fft_vendor()`로 확인할 수 있습니다. FFTW 기반 라이브러리는 AOCL-FFTW를 포함해 GPL이므로 쓰지 않습니다.
- **Apple: FIR은 vDSP, FFT는 자체 구현.** macOS에서 Accelerate를 링크한 프로그램(그곳에서 safeguard의 기본값)은 `dsp_sliding_dot_f64`/`_f32`를 — 따라서 그 위에 만든 `fir_process_block`, `conv_direct` 등 FIR 경로도 — `vDSP_convD`/`vDSP_conv`로 실행합니다. M1 Pro에서 std의 NEON 커널보다 스레드 유무와 관계없이 1.2–1.4배 빨랐습니다([벤치마크](/ko/benchmarks#dsp)). vDSP의 컨볼루션은 메모리를 할당하거나 락을 잡거나 스레드를 띄우지 않으므로 `fir_process_block`의 실시간 특성은 그대로입니다. FFT는 std 자체 변환을 씁니다: oneMKL은 Apple Silicon용이 없고, std의 FFT가 vDSP보다 빠르며 vDSP의 스레드 double 배치만 예외입니다. Accelerate가 없으면 std 커널이 돕니다(Apple 링커는 약한 참조도 링크 시점에 해석하므로 safeguard가 `-U`를 넘깁니다).

::: tip 실시간 사용
오디오 콜백에서는 미리 한 번 `fft_prepare(maxN)`을 호출한 뒤 `fft_serial`/`ifft_serial`을 쓰세요. 이들은 메모리 할당, 락, 스레드 풀 사용, 벤더 라이브러리 호출을 전혀 하지 않습니다. oneMKL이 링크되어 있으면 `fft`/`ifft`는 실시간 코드용이 아닙니다: 길이마다 첫 호출이 DFTI 디스크립터를 만듭니다.
:::

NVIDIA GPU에서는 [`fft_cuda.h`](/ko/stdlib/ml#cuda-cudnn-cufft)가 같은 변환과 2D 변환을 cuFFT로 실행하며, 길이 제한이 없습니다. AMD GPU에서는 [`fft_rocm.h`](/ko/stdlib/ml#rocm-miopen-hipfft)가 같은 일을 hipFFT로 합니다.

```c
int dsp_is_pow2(unsigned long n);                                    // n >= 1이고 2의 거듭제곱이면
int fft(&Complex data, unsigned long n);                      // 제자리 순변환, 1=성공/0=2의거듭제곱아님
int ifft(&Complex data, unsigned long n);                     // 제자리 역변환 (1/N 포함)
int fft_serial(&Complex data, unsigned long n);              // 호출한 스레드에서만 fft
int ifft_serial(&Complex data, unsigned long n);
int fft_prepare(unsigned long n);                             // n 이하 길이의 트위들 테이블 생성
int fft_real(const &double in, &Complex out, unsigned long n); // 실수 -> n점 전체 스펙트럼
// 연속으로 저장된 'batch'개 변환 (행 r은 data[r*n]), 스레드 사용
int fft_batch(&Complex data, unsigned long n, unsigned long batch, int inverse);
int fft_real_batch(const &double in, &Complex out, unsigned long n, unsigned long batch);
// 같은 작업을 제자리에서: 행 r의 앞쪽 n/2칸에 실수 n개가 들어 있음
int fft_real_batch_inplace(&Complex data, unsigned long n, unsigned long batch);
// 빈 0 .. n/2만(값 n/2 + 1개; 행 r은 out[r*(n/2+1)]부터)
int fft_real_half(const &double in, &Complex out, unsigned long n);
int fft_real_half_batch(const &double in, &Complex out, unsigned long n, unsigned long batch);
int ffft(&FComplex data, unsigned long n);                    // Q8.24 순변환
int fifft(&FComplex data, unsigned long n);                   // Q8.24 역변환
```

### 예제 {#example-1}

```c
#include <std/dsp/fft.h>
#include <std/io.h>

int main() {
    struct Complex data[8];
    for (int i = 0; i < 8; i++) data[i] = complex_new((double)i, 0.0);

    fft(data, 8);     // in-place forward transform
    ifft(data, 8);    // in-place inverse — round-trips back to the original signal

    print("data[1].re after round-trip (expect ~1.0): ");
    println_float(data[1].re);
    return 0;
}
```

---

## convolution — 선형 컨볼루션 {#convolution-linear-convolution}

```c
#include <std/dsp/convolution.h>
```

`y[n] = sum_k x[k]*h[n-k]`, 출력 길이는 `len_x + len_h - 1`입니다. 결과는 같지만 비용 트레이드오프가 다른 두 가지 구현이 있습니다:

```c
// O(len_x * len_h) -- 짧은 커널에 가장 적합. 출력 하나가 SIMD 내적 하나이며,
// 긴 입력은 스레드 풀에 나뉩니다.
void conv_direct(const &double x, unsigned long len_x,
                  const &double h, unsigned long len_h, &double out);
void fconv_direct(const &Fixed x, unsigned long len_x,
                   const &Fixed h, unsigned long len_h, &Fixed out);

// fft.h를 통한 O(n log n) -- 직접 컨볼루션의 O(len_x*len_h) 비용이 부담이
// 되는 긴 입력/커널에 가장 적합. double 정밀도 전용. 커널이 신호의 1/16
// 이하면 overlap-add(커널의 약 8배 블록을 fft_batch로), 그 밖에는 x와 h를
// 복소 FFT 하나에 함께 담아 변환합니다.
void conv_fft(const &double x, unsigned long len_x,
              const &double h, unsigned long len_h, &double out);
```

세 함수 모두 `out`은 `len_x + len_h - 1` 샘플을 담을 공간이 있어야 합니다. `conv_direct`는 커널을 한 번 뒤집어, 창이 `x` 안에 완전히 들어가는 출력들을 `dsp_sliding_dot_f64` 한 번으로 계산합니다. 이 함수는 출력 32개(float는 64개)를 한꺼번에, 탭마다 값을 네 개의 누산 벡터에 브로드캐스트해 계산하므로 수평 합이 없습니다. 가장자리 출력은 `dsp_dot_f64`를 씁니다.

---

## window — 윈도우 함수 {#window-window-functions}

```c
#include <std/dsp/window.h>
```

FFT 이전 프레임을 테이퍼링하거나(스펙트럼 누설 감소) FIR 설계(윈도우 방법)를 위한 대칭 N포인트 분석 윈도우입니다. 네 함수 모두 호출자가 제공한 배열을 채우며, 할당은 하지 않습니다.

```c
void window_rectangular(&double w, unsigned long n);  // w[i] = 1 (테이퍼링 없음 -- 누설 기준선)
void window_hann(&double w, unsigned long n);         // 0.5*(1 - cos(2*pi*i/(n-1)))
void window_hamming(&double w, unsigned long n);       // 0.54 - 0.46*cos(2*pi*i/(n-1))
void window_blackman(&double w, unsigned long n);      // 0.42 - 0.5*cos(2*pi*i/(n-1)) + 0.08*cos(4*pi*i/(n-1))
void window_apply(&double x, const &double w, unsigned long n);  // x[i] *= w[i], 제자리 연산
```

---

## filter — 범용 스트리밍 FIR / IIR {#filter-general-streaming-fir-iir}

```c
#include <std/dsp/filter.h>
```

한 번에 한 샘플씩 처리하는 차분 방정식 필터로, 자체 이력/상태를 내부에 유지하므로 호출자가 (예를 들어 `std::Reactor` 태스크 안에서) `convolution.h`처럼 전체 신호를 메모리에 담아둘 필요 없이 실시간 샘플을 하나씩 넣어줄 수 있습니다. 메모리 할당이나 스레드를 전혀 쓰지 않습니다. `FirFilter::process`는 링 버퍼를 탭마다 나머지 연산을 하지 않고 두 개의 연속 구간으로, 네 개의 부분합으로 순회합니다. `fir_process_block`(256탭까지)은 이력과 새 샘플 최대 512개를 스택의 창으로 복사하고 그 위에서 `dsp_sliding_dot_f64`를 실행합니다. 출력마다 내적을 하나씩 하는 대신 벡터 하나에 여러 출력을 계산합니다.

```
FIR ("피드포워드"): y[n] = sum_{k=0}^{M-1} b[k]*x[n-k]
IIR ("피드백"):      y[n] = sum b[k]*x[n-k] - sum_{k=1}^{N-1} a[k]*y[n-k]   (a[0] = 1)
```

```c
struct FirFilter {
    const &double coeffs;   // b[0..numTaps-1], caller-owned
    unsigned long numTaps;
    &double       history;  // caller-provided ring buffer, numTaps elements
    unsigned long pos;

    double process(double x);
    void   reset();          // zeros the history
};
struct FirFilter fir_init(const &double coeffs, unsigned long numTaps, &double history);
void fir_process_block(&FirFilter f, const &double in, &double out, unsigned long n);

struct IirFilter {           // general-order, direct form I
    const &double b; unsigned long numB;   // feedforward
    const &double a; unsigned long numA;   // feedback, a[0] assumed 1
    &double xHistory; &double yHistory;
    unsigned long xPos; unsigned long yPos;

    double process(double x);
    void   reset();
};
struct IirFilter iir_init(const &double b, unsigned long numB,
                           const &double a, unsigned long numA,
                           &double xHistory, &double yHistory);
void iir_process_block(&IirFilter f, const &double in, &double out, unsigned long n);
```

`struct FFirFilter`/`ffir_init`/`ffir_process_block`과 `struct FIirFilter`/`fiir_init`/`fiir_process_block`은 `&double` 대신 `&Fixed`를 쓰는, 동일한 형태의 Q8.24 대응 버전입니다.

### 예제 — 3탭 이동 평균 {#example-3-tap-moving-average}

```c
#include <std/dsp/filter.h>
#include <std/io.h>

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
#include <std/dsp/biquad.h>
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
// 블록 단위 process(), 상태는 레지스터에 유지; in == out 허용, 실시간 안전.
// 분산 룩어헤드(scattered look-ahead, Parhi & Messerschmitt)를 쓴 직접형 I:
// y[i] = q[i] + (a1^2 - 2 a2) y[i-2] - a2^2 y[i-4] (q는 입력만으로 계산)이므로
// 짝수와 홀수 출력이 서로 독립인 두 사슬이 되고, 2레인 벡터 하나로 함께
// 계산합니다. 추가되는 극점은 필터 자신의 극점을 뒤집은 것(반지름이 같음)이라
// 안정성이 유지됩니다. process()와는 반올림 차이만 납니다.
void biquad_process_block(&Biquad bq, const &double in, &double out, unsigned long n);

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
double biquad_response_mag(const &stack Biquad bq, double fs, double freqHz);
double biquad_response_phase(const &stack Biquad bq, double fs, double freqHz);

struct FBiquad {   // Q8.24 대응 버전
    Fixed b0, b1, b2, a1, a2, z1, z2;
    Fixed process(Fixed x);
    void  reset();
};
struct FBiquad biquad_to_fixed(struct Biquad bq);   // double로 설계된 biquad를 한 번 양자화
```

### 예제 {#example-2}

```c
#include <std/dsp/biquad.h>
#include <std/io.h>

int main() {
    struct Biquad lp = biquad_lowpass(48000.0, 1000.0, 0.707);

    // Unity DC gain — the standard invariant a lowpass must satisfy.
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
#include <std/dsp/dct.h>
```

`dct2`는 JPEG/MPEG에서 말하는 "그 DCT"입니다 — 실수 입력, 실수 출력, 에너지를 압축합니다. `idct3`는 그 정확한 역변환입니다. `dft.h`처럼 직접 O(n²) 합산 방식입니다: 코사인은 4n개짜리 테이블에서 읽고, 512점부터 출력을 스레드 풀에 나눕니다.

```c
void dct2(const &double in, &double out, unsigned long n);    // X[k] = sum x[n]*cos(pi/N*(n+0.5)*k)
void idct3(const &double in, &double out, unsigned long n);   // dct2의 정확한 역변환
void fdct2(const &Fixed in, &Fixed out, unsigned long n);      // Q8.24 순변환
void fidct3(const &Fixed in, &Fixed out, unsigned long n);     // Q8.24 역변환
```

---

## stft — 단시간 푸리에 변환 + 다중 해상도 STFT 손실 {#stft-short-time-fourier-transform-multi-resolution-stft-loss}

```c
#include <std/dsp/stft.h>
```

`stft_forward`는 윈도우가 적용된 프레임을 신호를 따라 슬라이드시키며 각각을 FFT하여(`frameSize`는 2의 거듭제곱이어야 함) 스펙트로그램을 만듭니다. 프레임들은 `fft_real_batch_inplace`로 제자리에서 실수 변환으로 함께 변환되며(복소 변환의 절반 작업), 호스트에서는 스레드 풀에서 실행됩니다. 스트리밍에서는 프레임을 하나씩 윈도우 처리하고 `fft_serial`을 쓰세요. `stft_inverse`는 윈도우가 적용된 오버랩-애드 방식으로 신호를 재구성하며, 누적된 분석 윈도우 제곱값으로 정규화합니다 — 윈도우/홉 조합이 상수 오버랩-애드 조건을 만족할 때(예: 50%/75% 오버랩의 Hann) 정확합니다.

```c
#define STFT_WIN_RECTANGULAR 0
#define STFT_WIN_HANN 1
#define STFT_WIN_HAMMING 2
#define STFT_WIN_BLACKMAN 3

unsigned long stft_num_frames(unsigned long signalLen, unsigned long frameSize, unsigned long hopSize);

// out: stft_num_frames(...) * frameSize개의 Complex 값을 담을 공간.
void stft_forward(const &double signal, unsigned long signalLen,
                   unsigned long frameSize, unsigned long hopSize,
                   int windowType, &Complex out);

// outSignal: (numFrames-1)*hopSize + frameSize개 샘플을 담을 공간, 호출자가
// 0으로 초기화 (stft_inverse가 그 안에 누적함).
void stft_inverse(const &Complex frames, unsigned long numFrames,
                   unsigned long frameSize, unsigned long hopSize,
                   int windowType, &double outSignal);
```

`mr_stft_loss`는 신경 보코더 문헌(Yamamoto 등, Parallel WaveGAN)에서 말하는 "다중 해상도 STFT 손실"을 계산합니다: `numResolutions`개의 각 프레임 크기(각각 2의 거듭제곱, hop = frameSize/4, Hann 윈도우)에 대해, 같은 길이의 두 신호 사이의 스펙트럼 수렴도와 평균 L1 로그 크기 오차를 계산한 뒤 여러 해상도에 걸쳐 평균 냅니다:

```c
double mr_stft_loss(const &double x, const &double y, unsigned long len,
                     const &unsigned long frameSizes, unsigned long numResolutions);
```

값이 낮을수록 더 유사합니다; 동일한 신호라면 `0`입니다.

---

## resample — 업샘플링 / 다운샘플링 {#resample-upsampling-downsampling}

```c
#include <std/dsp/resample.h>
```

`inLen` 샘플 버퍼를 호출자가 지정한 크기의 `outLen` 샘플 버퍼로 매핑합니다 — `outLen > inLen`이면 업샘플링, `outLen < inLen`이면 다운샘플링이며, 어느 방향이든 같은 함수를 사용합니다.

```c
void resample_nearest(const &double in, unsigned long inLen, &double out, unsigned long outLen);
void resample_linear(const &double in, unsigned long inLen, &double out, unsigned long outLen);

// 윈도우가 적용된 sinc(대역 제한): 가장 품질이 높은 옵션. 다운샘플링할
// 때는 커널의 컷오프가 새로운(더 낮은) 나이퀴스트 레이트에 맞춰 자동으로
// 스케일되므로, 앤티에일리어싱 필터 역할도 겸합니다. kernelHalfWidth의
// 기본값으로는 8-16이 적당합니다. 탭마다의 가중치는 각의 차 공식으로
// 계산하고, 탭 각도 테이블은 호출마다 한 번 만듭니다. 그래서 탭끼리
// 독립이 되어 벡터 하나에 네 개씩 실행되며, 출력마다의 각도 네 개는 출력에서
// 출력으로 회전으로 넘기고 64개마다 정확히 다시 계산합니다. 출력은 스레드
// 풀에서 실행됩니다.
void resample_sinc(const &double in, unsigned long inLen, &double out, unsigned long outLen,
                    unsigned long kernelHalfWidth);

// Q8.24 nearest/linear. sinc 대응 버전은 없다 -- 윈도우 적용된 sinc
// 커널은 런타임의 소수 위치에 대해 탭마다 sin/cos를 계산해야 하는데,
// 이는 일반적인 설계 시점-float/런타임-fixed 분리 방식에 맞지 않는다.
void fresample_nearest(const &Fixed in, unsigned long inLen, &Fixed out, unsigned long outLen);
void fresample_linear(const &Fixed in, unsigned long inLen, &Fixed out, unsigned long outLen);
```

---

## ztransform — 범용 N차 쌍선형 변환 {#ztransform-general-n-th-order-bilinear-transform}

```c
#include <std/dsp/ztransform.h>
```

`biquad.h`의 `bilinear_2nd_order`/`biquad_response_*`를 임의 차수로 일반화한 것입니다 — biquad들의 종속 연결이 아니라 더 높은 차수의 IIR 섹션 하나를 직접 설계/분석할 때 사용합니다. 다항식 근 찾기 기능은 포함되어 있지 않습니다(별도의 반복적인 고유값/Durand-Kerner 알고리즘이 필요합니다); 대신 계수 배열을 직접 다룹니다.

```c
// B, A: order+1개의 아날로그(s영역) 계수, s의 오름차순 거듭제곱.
// bOut, aOut: 음의 z 거듭제곱 표기법(aOut[0] = 1)에서의 order+1개
// 디지털 계수 -- filter.h의 iir_init에 그대로 넣으면 된다.
void bilinear_nth_order(const &double B, const &double A, unsigned long order,
                         double fs, &double bOut, &double aOut);

double ztransform_response_mag(const &double b, const &double a, unsigned long order,
                                double fs, double freqHz);
double ztransform_response_phase(const &double b, const &double a, unsigned long order,
                                  double fs, double freqHz);
```

---

## comb — 콤 필터 + Karplus-Strong 현악기 합성 {#comb-comb-filters-karplus-strong-string-synthesis}

```c
#include <std/dsp/comb.h>
```

`CombFF`(피드포워드, `y[n] = x[n] + gain*x[n-delay]`)는 균등한 간격의 노치를 만들고; `CombFB`(피드백, `y[n] = x[n] + gain*y[n-delay]`)는 균등한 간격의 공진 피크를 만듭니다(대부분의 인공 리버브의 기반입니다 — 서로 다른 딜레이/게인을 가진 피드백 콤들의 뱅크).

```c
struct CombFF {
    &double history; unsigned long delay; unsigned long pos; double gain;
    double process(double x);
    void   reset();
};
struct CombFB {
    &double history; unsigned long delay; unsigned long pos; double gain;
    double process(double x);
    void   reset();
};
struct CombFF comb_ff_init(&double history, unsigned long delay, double gain);
struct CombFB comb_fb_init(&double history, unsigned long delay, double gain);
```

`KarplusStrong`은 고전적인 발현(plucked-string)/타악기 합성 알고리즘입니다(Karplus & Strong, 1983): 피드백 경로가 단순 스칼라 게인이 아니라 2탭 평균 저역통과인 피드백 콤입니다 — 이 평균화가 루프를 돌 때마다 고주파 에너지를 점진적으로 제거하여, 현악기 특유의 밝게 시작했다가 감쇠하는 음색을 만들어냅니다.

```c
struct KarplusStrong {
    &double buffer;         // ring buffer; also the delay line and the pitch period
    unsigned long length; unsigned long pos;
    double damping;         // 0..1, closer to 1 = slower decay (typical 0.99-0.999)

    double process();       // no input — self-sustaining pluck
    void   reset(const &double excitation, unsigned long n);  // seed with a new pluck
};
struct KarplusStrong ks_init(&double buffer, unsigned long length, double damping);
```

`struct FCombFF`/`FCombFB`/`FKarplusStrong`과 `comb_ff_init_fixed`/`comb_fb_init_fixed`/`ks_init_fixed`는 Q8.24 대응 버전입니다.

### 예제 — 발현 현악기 {#example-plucked-string}

```c
#include <std/dsp/comb.h>
#include <std/io.h>

int main() {
    double excitation[8] = { 0.5, -0.3, 0.8, -0.6, 0.2, -0.9, 0.4, -0.1 };  // noise burst
    double buf[8];
    struct KarplusStrong ks = ks_init(&buf[0], 8UL, 0.995);
    ks.reset(&excitation[0], 8UL);

    for (int i = 0; i < 16; i++) {
        double sample = ks.process();
        println_float(sample);   // decaying, self-sustained "pluck"
    }
    return 0;
}
```

---

## minphase — 최소 위상 재구성 {#minphase-minimum-phase-reconstruction}

```c
#include <std/dsp/minphase.h>
```

임펄스 응답(예: 목표 크기 응답으로부터 설계된 선형 위상 FIR)이 주어지면, **동일한 크기 스펙트럼**을 가지면서 가능한 최소 군지연(group delay)을 갖는 다른 임펄스 응답을 만들어냅니다 — 동일한 주파수 응답에 대해 선형 위상 필터의 지연 시간을 절반으로 줄입니다. 고전적인 준동형(실수 켑스트럼) 알고리즘입니다: 로그 크기 스펙트럼 -> 켑스트럼 -> 비인과 절반을 인과 절반 위로 접기(이것이 결과를 최소 위상으로 강제하는 부분) -> 스펙트럼 영역을 다시 거쳐 지수화.

```c
// x: n개 샘플 (n <= fftLen); fftLen은 2의 거듭제곱이어야 하며, 정확한
// 켑스트럴 리프터링을 위해 n보다 충분히 커야 함 (경험적으로 4-8배 정도가
// 적당함). outMinPhase: fftLen개 샘플을 담을 공간 -- 에너지가 시작 부분에
// 집중되며, 호출자는 보통 처음 n개 정도의 샘플만 남겨서 사용함.
void minimum_phase(const &double x, unsigned long n, unsigned long fftLen, &double outMinPhase);
```

---

## cqt — 상수-Q 변환 {#cqt-constant-q-transform}

```c
#include <std/dsp/cqt.h>
```

DFT/FFT의 균일한 주파수 간격과 달리, CQT는 모든 빈(bin)에서 일정한 비율 `fk/bandwidth`("Q")를 유지하는 로그 간격 중심 주파수 `fk = fmin * 2^(k/binsPerOctave)`를 사용합니다 — 음악적 음정과 인간의 청각이 대략 주파수에 대해 로그적이라는 사실과 맞아떨어지며, 이것이 크로마그램과 음정 검출에서 (단순 DFT가 아니라) CQT가 표준적으로 선택되는 이유입니다. 직접 상관(direct-correlation) 형태입니다(Brown 1991): 각 빈 고유 길이의 Hann 윈도우가 적용된 복소 지수 커널이 신호의 시작 부분과 직접 상관되며 — FFT도, 커널 행렬의 사전 계산도 없이 — 호출당 O(numBins * 평균 커널 길이)입니다. 윈도우와 지수는 복소 회전으로 진행하고 64샘플마다 정확히 다시 계산하며, 빈은 스레드 풀에서 실행됩니다.

```c
// signal은 최소한 가장 낮은(가장 긴 커널을 갖는) 빈에 필요한 만큼의
// 샘플을 가져야 한다 -- 대략 Q*fs/fmin, Q = 1/(2^(1/binsPerOctave)-1) --
// 그렇지 않으면 해당 빈의 커널이 signalLen에 맞춰 조용히 잘린다
// (오류가 아니라 해당 빈의 해상도 저하). out: numBins개의 Complex 값을
// 담을 공간.
void cqt_forward(const &double signal, unsigned long signalLen, double fs,
                  double fmin, unsigned long numBins, unsigned long binsPerOctave,
                  &Complex out);
```

---

## cwt — 연속 웨이블릿 변환 (Morlet) {#cwt-continuous-wavelet-transform-morlet}

```c
#include <std/dsp/cwt.h>
```

CQT가 로그 간격 주파수 *빈*을 선택하는 반면, CWT는 (호출자가 선택하는, 보통 로그 간격인) *스케일* 집합을 직접 선택하고 스케일/이동된 Morlet 웨이블릿을 모든 샘플 위치에서 신호와 상관시켜, 프레임당 값 하나가 아니라 완전한 시간-스케일 "스칼로그램"을 만들어냅니다. 스케일 `s`는 대략 `f = w0*fs / (2*pi*s)` Hz의 중심 주파수에 대응합니다. 직접 상관 방식이며, 커널 지지(support)는 가우시안 포락선의 표준편차 ±4배로 잘립니다. 스케일마다 웨이블릿을 한 번 계산하고, 각 출력은 그것과의 SIMD 내적 두 번이며, (스케일, 위치) 행은 스레드 풀에서 실행됩니다.

```c
// scales: numScales개 항목 (각각 0보다 커야 함). w0: Morlet 중심 주파수
// 파라미터 (5-6이 표준). out: numScales*signalLen개의 Complex 값을 담을
// 공간, 스케일 기준 행 우선(row-major) 순서.
void cwt_morlet(const &double signal, unsigned long signalLen,
                 const &double scales, unsigned long numScales,
                 double w0, &Complex out);
```

---

## imaging — 2D DSP {#imaging-2d-dsp}

```c
#include <std/dsp/imaging.h>
```

위의 1D 빌딩 블록들에 대응하는 2D 버전입니다: `conv2d`는 제로 패딩된("same" 크기) 2D 컨볼루션으로, 커널 탭마다 행 단위 SIMD 곱셈-덧셈으로 계산하고 행은 스레드 풀에서 실행합니다; `fft2d`/`ifft2d`는 `fft.h`의 1D 변환을 모든 행에, 그다음 모든 열에 적용합니다(2D DFT는 분리 가능(separable)하므로 정확합니다). 행은 `fft_batch`로 처리하고, 열은 8개씩 연속 버퍼로 모아 변환하며 열 묶음들은 스레드 풀에서 실행됩니다; 커널 생성 함수들은 거의 모든 이미지 파이프라인에 필요한 두 가지 컨볼루션 커널을 다룹니다.

```c
// img: h*w개, 행 우선; kernel: kh*kw개, 행 우선 (kh/kw는 홀수여야 함);
// out: h*w개의 double을 담을 공간. 범위를 벗어난 커널 탭은 0으로 취급.
void conv2d(const &double img, unsigned long h, unsigned long w,
            const &double kernel, unsigned long kh, unsigned long kw, &double out);

void fft2d(&Complex img, unsigned long h, unsigned long w);   // 제자리 연산, h와 w는 2의 거듭제곱이어야 함
void ifft2d(&Complex img, unsigned long h, unsigned long w);

void gaussian_kernel(&double kernel, unsigned long size, double sigma);  // 정규화됨 (합이 1)
void sobel_x_kernel(&double kernel);   // 고정 3x3, 9개의 double을 담을 공간
void sobel_y_kernel(&double kernel);
```

---

## AudioBuffer — 락프리 다중 채널 링 버퍼 {#audiobuffer-lock-free-multi-channel-ring-buffer}

```c
#include <std/dsp/audio_buffer.h>
```

인터리빙된 다중 채널 Q8.24(`Fixed`) 프레임을 인라인으로 저장하는 단일 생산자/단일 소비자 링입니다 — 힙을 쓰지 않습니다. 용량은 최대 `AUDIO_MAX_CHANNELS`(8)채널의 `AUDIO_BUF_FRAMES`(512)프레임이므로 구조체가 큽니다(16 KB). 작은 타깃에서는 정적 저장소에 두세요.

### 구조체 {#struct}

```c
struct AudioBuffer {
    Fixed         data[AUDIO_BUF_FRAMES * AUDIO_MAX_CHANNELS];   // interleaved frames
    unsigned long cap_frames;
    unsigned long channels;
    volatile unsigned long head;   // write cursor (frames)
    volatile unsigned long tail;   // read cursor (frames)

    unsigned long readable_frames() const;
    unsigned long writable_frames() const;
    unsigned long write_frames(&stack Fixed src, unsigned long n);   // returns frames written
    unsigned long read_frames(&stack Fixed dst, unsigned long n);    // returns frames read
    unsigned long peek_frames(&stack Fixed dst, unsigned long n) const;
    void          mix_frames(&stack Fixed src, unsigned long n);
    void          clear();
    int           is_empty() const;
    int           is_full() const;
};

struct AudioBuffer audio_buf_init(unsigned long channels);
```

### 예제 — 생산자 / 소비자 {#example-producer-consumer}

```c
#include <std/dsp/audio_buffer.h>
#include <std/io.h>

static struct AudioBuffer g_ab;

int main() {
    g_ab = std::audio_buf_init(2UL);          // stereo

    Fixed frame[2];
    unsafe {
        frame[0] = std::fixed_from_float(0.5f);
        frame[1] = std::fixed_from_float(-0.25f);
    }
    g_ab.write_frames(&frame[0], 1UL);        // producer
    std::print("readable: ");
    std::println_int((long long)g_ab.readable_frames());   // 1

    Fixed out[2];
    g_ab.read_frames(&out[0], 1UL);           // consumer
    unsafe {
        std::print("L = "); std::println_float((double)std::fixed_to_float(out[0]));   //  0.5
        std::print("R = "); std::println_float((double)std::fixed_to_float(out[1]));   // -0.25
    }
    return 0;
}
```

::: info
`mix_frames`는 현재 쓰기 위치에서 기존 버퍼 내용에 `frames`를 더할 뿐, `head`를 **전진시키지는 않습니다**. 이는 하나의 출력 버퍼로 커밋하기 전에 여러 소스를 믹싱하기 위한 것입니다. 커서를 전진시키려면 이후에 `write_frames`를 호출하세요.
:::

---

## TimerWheel — O(1) 실시간 타이머 {#timerwheel-o1-real-time-timer}

```c
#include <std/dsp/timer_wheel.h>
```

최대 `WHEEL_TIMERS`(64)개의 동시 타이머를 지원하는 타이머 스케줄러입니다. `tick()`은 시계를 1만큼 진행시키고 새 tick에 만료되는 모든 타이머를 실행합니다. 하나의 휠은 멀티테넌트이므로 — 서로 무관한 하위 시스템이 서로 다른 컨텍스트 타입으로 공유합니다 — 각 슬롯은 콜백을 타입 소거된 형태로 저장합니다. 제네릭 진입점 `timer_add`/`timer_add_periodic`은 짝이 맞는 `fn void(?&T)` / `?&T` 쌍을 받으므로 호출은 항상 타입이 올바릅니다.

### API {#api}

```c
#define WHEEL_SLOTS   256
#define WHEEL_TIMERS  64

struct TimerWheel {
    struct WheelTimer timers[WHEEL_TIMERS];
    unsigned long     current_tick;

    void          cancel(int id);    // ignores invalid ids
    int           tick();            // advance one tick; returns how many timers fired
    unsigned long now() const;
};

void timer_wheel_init(&TimerWheel tw);

// One-shot at absolute tick `tick`. Returns the timer id (1-based) or 0 if full.
generic<T> int timer_add(&TimerWheel tw, unsigned long tick, fn void(?&T) cb, ?&T ctx);

// Every `period` ticks, first at now() + period. Returns the id or 0 if full.
generic<T> int timer_add_periodic(&TimerWheel tw, unsigned long period, fn void(?&T) cb, ?&T ctx);
```

### 예제 {#example-3}

```c
#include <std/dsp/timer_wheel.h>
#include <std/io.h>

struct Led { int toggles; };
static int g_done = 0;

static void blink(?&Led led) {
    match (led) { case some(l): l.toggles = l.toggles + 1; case null: { } }
}
static void stop(?&int unused) { g_done = 1; }

static struct TimerWheel g_tw;   // ~2 KB of slots: keep it off small stacks

int main() {
    std::timer_wheel_init(&g_tw);
    struct Led led;
    led.toggles = 0;
    unsafe { std::timer_add_periodic(&g_tw, 10UL, blink, (?&Led)&led); }  // every 10 ticks
    std::timer_add(&g_tw, 55UL, stop, null);                               // once, at tick 55
    while (!g_done) { g_tw.tick(); }
    std::print("blinks: ");
    std::println_int(led.toggles);   // 5 (ticks 10, 20, 30, 40, 50)
    return 0;
}
```

::: warning
타이머 만료는 `expires == current_tick`으로 확인됩니다. `tick()`이 예상보다 드물게 호출되면(예: 인터럽트 지연 스파이크 이후) 타이머가 누락될 수 있습니다. 중요한 데드라인에는 대신 `expires <= current_tick`으로 폴링하거나, 하드웨어 타이머 ISR로 `tick()`을 구동하세요.
:::
