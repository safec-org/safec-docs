# DSP & Real-Time Utilities

`std/dsp/` covers deterministic fixed-point arithmetic, spectral transforms (DFT/FFT/DCT/STFT/CQT/CWT), filters (FIR/IIR/biquad/comb), convolution, resampling, minimum-phase reconstruction, general Laplace-to-Z (bilinear) transform tooling, 2D/imaging primitives, audio buffering, and real-time timer scheduling — twenty modules in total.

```c
#include "dsp/dsp_all.h"  // master header: pulls in every module below
```

Every transform/filter that has a meaningful fixed-point form ships **two** implementations — a `double`-precision path and a parallel Q8.24 (`Fixed`) path (function/type names prefixed with `f`, e.g. `fft`/`ffft`, `struct Biquad`/`struct FBiquad`) — following one consistent design: coefficients that only need to be computed once (FFT twiddle factors, filter coefficients) are always derived in `double` precision using the real transcendental functions in `std/math.h`, then quantized to `Fixed` a single time; the actual per-sample signal-processing loop runs entirely in Q8.24 arithmetic. Standard practice — even hardware FFT accelerators precompute twiddle tables offline in floating point. A few of the newer, more specialized transforms (STFT, CQT, CWT, minimum-phase, imaging) are `double`-only: there's no natural fixed-point form for a Gaussian-windowed wavelet kernel or a homomorphic cepstrum, the same way there's no fixed-point `sin()`.

---

## fixed — Q8.24 Fixed-Point Arithmetic

```c
#include "dsp/fixed.h"
```

`Fixed` is a **Q8.24** fixed-point type: 8 integer bits, 24 fractional bits, stored as a 32-bit signed int (matches 24-bit audio sample depth for sub-sample precision). It's a `newtype` over `int`, so the compiler treats it as a distinct type while using the same bit representation.

### Constants

| Constant | Value | Meaning |
|----------|-------|---------|
| `FIXED_ONE` | 16777216 (2^24) | 1.0 |
| `FIXED_HALF` | 8388608 | 0.5 |
| `FIXED_PI` | 52707178 | ≈ π |

### Conversion

```c
Fixed  fixed_from_int(int x);       // x << 24
Fixed  fixed_from_float(double f);  // (Fixed)(f * 16777216.0)
int    fixed_to_int(Fixed x);       // x >> 24 (arithmetic, rounds toward -inf)
double fixed_to_float(Fixed x);     // x / 16777216.0
```

### Arithmetic

```c
Fixed fixed_add(Fixed a, Fixed b);
Fixed fixed_sub(Fixed a, Fixed b);
Fixed fixed_mul(Fixed a, Fixed b);   // (a * b) >> 24, 64-bit intermediate
Fixed fixed_div(Fixed a, Fixed b);   // (a << 24) / b, 64-bit intermediate
Fixed fixed_abs(Fixed x);
Fixed fixed_neg(Fixed x);
Fixed fixed_sqrt(Fixed x);           // Newton-Raphson, 4 iterations; x must be >= 0
```

### Example

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

## dsp — Array Primitives

```c
#include "dsp/dsp.h"
```

Basic array-level building blocks shared by the rest of `std/dsp/`. All operate on `Fixed*` arrays with no heap allocation, except `dsp_dot_f64` (a `double`-array primitive, unrelated to the Fixed path — see below).

```c
Fixed dsp_dot(&stack Fixed a, &stack Fixed b, unsigned long n);           // sum of a[i]*b[i]
void  dsp_scale(&stack Fixed a, Fixed scale, unsigned long n);            // a[i] *= scale
void  dsp_add(&stack Fixed a, &stack Fixed b, &stack Fixed out, unsigned long n); // out[i] = a[i]+b[i]
void  dsp_clip(&stack Fixed a, Fixed lo, Fixed hi, unsigned long n);      // clamp to [lo, hi]
Fixed dsp_peak(&stack Fixed a, unsigned long n);                          // max |a[i]|
Fixed dsp_rms(&stack Fixed a, unsigned long n);                           // sqrt(mean(a[i]^2))

// SIMD FMA-accelerated dot product of two *double* arrays (see simd.h) —
// 4 elements/iteration via simd_fma_f64x4, scalar remainder for n % 4.
// No Fixed counterpart: Q8.24 multiply needs a 64-bit widening shift per
// element that std::simd's integer FMA doesn't provide. Used internally
// by convolution.h's conv_direct once the kernel is reversed into a
// contiguous access pattern; also usable directly.
double dsp_dot_f64(const double* a, const double* b, unsigned long n);
```

::: tip
`dsp_moving_avg`/`dsp_iir_lp` from earlier versions of this module were removed — they were superseded by `filter.h`'s general streaming `FirFilter`/`IirFilter` (a 1-tap moving average is just `fir_init` with `numTaps=1`, and a first-order IIR low-pass is `iir_init` with a 1-pole `a[]`) and `biquad.h`'s cookbook designers, both of which are real, verified DSP implementations rather than the earlier toy versions.
:::

---

## complex_dsp — Complex Numbers

```c
#include "dsp/complex_dsp.h"
```

Value-type complex numbers with operator overloading, purpose-built for the spectral modules below (FFT butterflies, twiddle factors, frequency-domain multiply) — distinct from `std/complex.h`'s C99-style `[re, im]` out-array API. `w * a + b` reads the way the math does, instead of a chain of pointer-writing `cmul_d`/`cadd_d` calls.

```c
struct Complex {
    double re; double im;
    Complex operator+(Complex other) const;
    Complex operator-(Complex other) const;
    Complex operator*(Complex other) const;
    Complex operator/(Complex other) const;
    Complex neg() const;    // unary negate (operator overloads aren't arity-distinguished)
    double  abs() const;    // sqrt(re^2 + im^2)
    double  arg() const;    // atan2(im, re)
    Complex conj() const;   // (re, -im)
};
Complex complex_new(double re, double im);
Complex complex_from_polar(double magnitude, double theta);  // (mag*cos(theta), mag*sin(theta))

struct FComplex {   // Q8.24 counterpart — Fixed re/im, same shape minus operator/
    Fixed re; Fixed im;
    FComplex operator+(FComplex other) const;
    FComplex operator-(FComplex other) const;
    FComplex operator*(FComplex other) const;
    FComplex neg() const;
    Fixed     abs() const;
    FComplex  conj() const;
};
FComplex fcomplex_new(Fixed re, Fixed im);
FComplex complex_to_fixed(Complex c);   // quantize a design-time double result to Q8.24
Complex  fcomplex_to_float(FComplex c);
```

::: warning Operator calls need an addressable receiver
`a * b + c` works when `a`/`b`/`c` are named locals, but a chained call like `complex_new(x,0.0) * zInv` fails to compile — a SafeC operator call needs an addressable (lvalue) receiver, and `complex_new(...)`'s return value is a temporary. Bind each intermediate to a named variable first: `struct Complex cx = complex_new(x, 0.0); struct Complex t = cx * zInv;`.
:::

---

## dft — Discrete Fourier Transform (direct, O(n²))

```c
#include "dsp/dft.h"
```

Direct-summation DFT: `X[k] = sum x[n] * e^(-i*2*pi*k*n/N)`. Works for **any** N, not just powers of two — use this for arbitrary-length transforms, and as the independent reference `fft.h`'s own correctness checks are verified against.

```c
void dft(const struct Complex* in, struct Complex* out, unsigned long n);       // forward, complex -> complex
void dft_real(const double* in, struct Complex* out, unsigned long n);          // forward, real -> complex
void idft(const struct Complex* in, struct Complex* out, unsigned long n);      // inverse (includes 1/N)
void fdft(const struct FComplex* in, struct FComplex* out, unsigned long n);    // Q8.24 forward
void fidft(const struct FComplex* in, struct FComplex* out, unsigned long n);   // Q8.24 inverse
```

---

## fft — Fast Fourier Transform (radix-2 Cooley-Tukey)

```c
#include "dsp/fft.h"
```

In-place, iterative decimation-in-time FFT: O(n log n) instead of `dft.h`'s O(n²), at the cost of requiring `n` to be a power of two (functions return 0 and leave `data` unmodified otherwise — fall back to `dft.h` for arbitrary lengths).

```c
int dsp_is_pow2(unsigned long n);                                    // n >= 1 and a power of two
int fft(struct Complex* data, unsigned long n);                      // in-place forward, 1=ok/0=not-pow2
int ifft(struct Complex* data, unsigned long n);                     // in-place inverse (includes 1/N)
int fft_real(const double* in, struct Complex* out, unsigned long n); // real -> complex convenience
int ffft(struct FComplex* data, unsigned long n);                    // Q8.24 forward
int fifft(struct FComplex* data, unsigned long n);                   // Q8.24 inverse
```

### Example

```c
#include "dsp/fft.h"
#include "io.h"

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

## convolution — Linear Convolution

```c
#include "dsp/convolution.h"
```

`y[n] = sum_k x[k]*h[n-k]`, output length `len_x + len_h - 1`. Two implementations, same output, different cost trade-off:

```c
// O(len_x * len_h), no allocation — best for short kernels.
void conv_direct(const double* x, unsigned long len_x,
                  const double* h, unsigned long len_h, double* out);
void fconv_direct(const Fixed* x, unsigned long len_x,
                   const Fixed* h, unsigned long len_h, Fixed* out);

// O(n log n) via fft.h (zero-padded FFT, pointwise multiply, inverse FFT)
// — best for long inputs/kernels where direct convolution's O(len_x*len_h)
// cost dominates. double-precision only.
void conv_fft(const double* x, unsigned long len_x,
              const double* h, unsigned long len_h, double* out);
```

`out` must have room for `len_x + len_h - 1` samples in all three. `conv_direct`'s inner loop is SIMD-accelerated: the kernel is reversed once up front so the accumulation becomes a contiguous-range call into `dsp_dot_f64`.

---

## window — Window Functions

```c
#include "dsp/window.h"
```

Symmetric N-point analysis windows, for tapering a frame before an FFT (reduces spectral leakage) or FIR design (window method). All four fill a caller-provided array; none allocate.

```c
void window_rectangular(double* w, unsigned long n);  // w[i] = 1 (no tapering — leakage baseline)
void window_hann(double* w, unsigned long n);         // 0.5*(1 - cos(2*pi*i/(n-1)))
void window_hamming(double* w, unsigned long n);       // 0.54 - 0.46*cos(2*pi*i/(n-1))
void window_blackman(double* w, unsigned long n);      // 0.42 - 0.5*cos(2*pi*i/(n-1)) + 0.08*cos(4*pi*i/(n-1))
void window_apply(double* x, const double* w, unsigned long n);  // x[i] *= w[i], in place
```

---

## filter — General Streaming FIR / IIR

```c
#include "dsp/filter.h"
```

One-sample-at-a-time difference-equation filters, keeping their own history/state internally so a caller can feed real-time samples one at a time (e.g. inside a `std::Reactor` task) rather than needing the whole signal in memory the way `convolution.h` does.

```
FIR ("feedforward"): y[n] = sum_{k=0}^{M-1} b[k]*x[n-k]
IIR ("feedback"):     y[n] = sum b[k]*x[n-k] - sum_{k=1}^{N-1} a[k]*y[n-k]   (a[0] = 1)
```

```c
struct FirFilter {
    const double* coeffs;   // b[0..numTaps-1], caller-owned
    unsigned long numTaps;
    double*       history;  // caller-provided ring buffer, numTaps elements
    unsigned long pos;

    double process(double x);
    void   reset();          // zeros the history
};
struct FirFilter fir_init(const double* coeffs, unsigned long numTaps, double* history);
void fir_process_block(struct FirFilter* f, const double* in, double* out, unsigned long n);

struct IirFilter {           // general-order, direct form I
    const double* b; unsigned long numB;   // feedforward
    const double* a; unsigned long numA;   // feedback, a[0] assumed 1
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

`struct FFirFilter`/`ffir_init`/`ffir_process_block` and `struct FIirFilter`/`fiir_init`/`fiir_process_block` are the Q8.24 counterparts, same shape with `Fixed*` in place of `double*`.

### Example — 3-tap moving average

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

## biquad — 2nd-Order IIR Sections + RBJ Cookbook

```c
#include "dsp/biquad.h"
```

`struct Biquad` is a single Direct-Form-II-Transposed 2nd-order section (`a0` always normalized to 1):

```
y[n] = b0*x[n] + z1
z1'  = b1*x[n] - a1*y[n] + z2
z2'  = b2*x[n] - a2*y[n]
```

Cascade several for a higher-order filter — call `process()` on each in sequence.

```c
struct Biquad {
    double b0, b1, b2, a1, a2, z1, z2;
    double process(double x);
    void   reset();
};
struct Biquad biquad_new(double b0, double b1, double b2, double a1, double a2);

// Robert Bristow-Johnson's "Audio EQ Cookbook" formulas. fs = sample rate,
// f0 = cutoff/center frequency, q = Q factor (higher = narrower/more resonant).
struct Biquad biquad_lowpass(double fs, double f0, double q);
struct Biquad biquad_highpass(double fs, double f0, double q);
struct Biquad biquad_bandpass(double fs, double f0, double q);   // constant 0dB peak gain
struct Biquad biquad_notch(double fs, double f0, double q);
struct Biquad biquad_allpass(double fs, double f0, double q);
struct Biquad biquad_peaking(double fs, double f0, double q, double dbGain);
struct Biquad biquad_lowshelf(double fs, double f0, double s, double dbGain);   // s = shelf slope, 1.0 = default
struct Biquad biquad_highshelf(double fs, double f0, double s, double dbGain);

// General analog-to-digital 2nd order section via the bilinear transform:
// H(s) = (B2 s^2 + B1 s + B0) / (A2 s^2 + A1 s + A0) -> matching digital biquad.
struct Biquad bilinear_2nd_order(double B0, double B1, double B2,
                                  double A0, double A1, double A2, double fs);

// Z-domain frequency response at freqHz: magnitude (linear) and phase (radians).
double biquad_response_mag(const struct Biquad* bq, double fs, double freqHz);
double biquad_response_phase(const struct Biquad* bq, double fs, double freqHz);

struct FBiquad {   // Q8.24 counterpart
    Fixed b0, b1, b2, a1, a2, z1, z2;
    Fixed process(Fixed x);
    void  reset();
};
struct FBiquad biquad_to_fixed(struct Biquad bq);   // quantize a designed-in-double biquad once
```

### Example

```c
#include "dsp/biquad.h"
#include "io.h"

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

## dct — Discrete Cosine Transform (DCT-II / DCT-III)

```c
#include "dsp/dct.h"
```

`dct2` is "the DCT" in the JPEG/MPEG sense — real-valued in, real-valued out, energy-compacting. `idct3` is its exact inverse. Direct O(n²) summation, like `dft.h`.

```c
void dct2(const double* in, double* out, unsigned long n);    // X[k] = sum x[n]*cos(pi/N*(n+0.5)*k)
void idct3(const double* in, double* out, unsigned long n);   // exact inverse of dct2
void fdct2(const Fixed* in, Fixed* out, unsigned long n);      // Q8.24 forward
void fidct3(const Fixed* in, Fixed* out, unsigned long n);     // Q8.24 inverse
```

---

## stft — Short-Time Fourier Transform + Multi-Resolution STFT Loss

```c
#include "dsp/stft.h"
```

`stft_forward` slides a windowed frame across a signal and FFTs each one (`frameSize` must be a power of two), producing a spectrogram. `stft_inverse` reconstructs the signal via windowed overlap-add, normalized by the accumulated squared analysis window — exact whenever the window/hop combination satisfies the constant-overlap-add condition (e.g. Hann at 50%/75% overlap).

```c
#define STFT_WIN_RECTANGULAR 0
#define STFT_WIN_HANN 1
#define STFT_WIN_HAMMING 2
#define STFT_WIN_BLACKMAN 3

unsigned long stft_num_frames(unsigned long signalLen, unsigned long frameSize, unsigned long hopSize);

// out: room for stft_num_frames(...) * frameSize Complex values.
void stft_forward(const double* signal, unsigned long signalLen,
                   unsigned long frameSize, unsigned long hopSize,
                   int windowType, struct Complex* out);

// outSignal: room for (numFrames-1)*hopSize + frameSize samples, caller-zeroed
// (stft_inverse accumulates into it).
void stft_inverse(const struct Complex* frames, unsigned long numFrames,
                   unsigned long frameSize, unsigned long hopSize,
                   int windowType, double* outSignal);
```

`mr_stft_loss` computes the "multi-resolution STFT loss" from the neural-vocoder literature (Yamamoto et al., Parallel WaveGAN): for each of `numResolutions` frame sizes (each a power of two, hop = frameSize/4, Hann window), the spectral-convergence plus mean L1 log-magnitude error between two same-length signals, averaged across resolutions:

```c
double mr_stft_loss(const double* x, const double* y, unsigned long len,
                     const unsigned long* frameSizes, unsigned long numResolutions);
```

Lower is more similar; `0` for identical signals.

---

## resample — Upsampling / Downsampling

```c
#include "dsp/resample.h"
```

Maps an `inLen`-sample buffer onto a caller-sized `outLen`-sample buffer — `outLen > inLen` upsamples, `outLen < inLen` downsamples, same function either direction.

```c
void resample_nearest(const double* in, unsigned long inLen, double* out, unsigned long outLen);
void resample_linear(const double* in, unsigned long inLen, double* out, unsigned long outLen);

// Windowed-sinc (band-limited): the highest-quality option. When
// downsampling, the kernel's cutoff auto-scales to the new (lower)
// Nyquist rate, so it also acts as the anti-aliasing filter. 8-16 is a
// good kernelHalfWidth default.
void resample_sinc(const double* in, unsigned long inLen, double* out, unsigned long outLen,
                    unsigned long kernelHalfWidth);

// Q8.24 nearest/linear. No sinc counterpart — a windowed-sinc kernel needs
// per-tap sin/cos evaluated against a runtime fractional position, which
// doesn't fit the usual design-time-float/runtime-fixed split.
void fresample_nearest(const Fixed* in, unsigned long inLen, Fixed* out, unsigned long outLen);
void fresample_linear(const Fixed* in, unsigned long inLen, Fixed* out, unsigned long outLen);
```

---

## ztransform — General N-th Order Bilinear Transform

```c
#include "dsp/ztransform.h"
```

`biquad.h`'s `bilinear_2nd_order`/`biquad_response_*` generalized to arbitrary order — for designing/analyzing a single higher-order IIR section directly instead of as a cascade of biquads. No polynomial root-finder is included (that needs a separate iterative eigenvalue/Durand-Kerner algorithm); this works directly on coefficient arrays.

```c
// B, A: order+1 analog (s-domain) coefficients, ascending powers of s.
// bOut, aOut: order+1 digital coefficients in the negative-power-of-z
// convention (aOut[0] = 1) — feed straight into filter.h's iir_init.
void bilinear_nth_order(const double* B, const double* A, unsigned long order,
                         double fs, double* bOut, double* aOut);

double ztransform_response_mag(const double* b, const double* a, unsigned long order,
                                double fs, double freqHz);
double ztransform_response_phase(const double* b, const double* a, unsigned long order,
                                  double fs, double freqHz);
```

---

## comb — Comb Filters + Karplus-Strong String Synthesis

```c
#include "dsp/comb.h"
```

`CombFF` (feedforward, `y[n] = x[n] + gain*x[n-delay]`) produces evenly-spaced notches; `CombFB` (feedback, `y[n] = x[n] + gain*y[n-delay]`) produces evenly-spaced resonant peaks (the basis of most artificial reverbs — a bank of feedback combs at different delays/gains).

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

`KarplusStrong` is the classic plucked-string/percussion synthesis algorithm (Karplus & Strong, 1983): a feedback comb whose feedback path is a 2-tap averaging lowpass instead of a plain scalar gain — the averaging progressively removes high-frequency energy each time around the loop, producing a string's characteristic brighten-then-decay timbre.

```c
struct KarplusStrong {
    double* buffer;         // ring buffer; also the delay line and the pitch period
    unsigned long length; unsigned long pos;
    double damping;         // 0..1, closer to 1 = slower decay (typical 0.99-0.999)

    double process();       // no input — self-sustaining pluck
    void   reset(const double* excitation, unsigned long n);  // seed with a new pluck
};
struct KarplusStrong ks_init(double* buffer, unsigned long length, double damping);
```

`struct FCombFF`/`FCombFB`/`FKarplusStrong` plus `comb_ff_init_fixed`/`comb_fb_init_fixed`/`ks_init_fixed` are the Q8.24 counterparts.

### Example — plucked string

```c
#include "dsp/comb.h"
#include "io.h"

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

## minphase — Minimum-Phase Reconstruction

```c
#include "dsp/minphase.h"
```

Given an impulse response (e.g. a linear-phase FIR designed from a target magnitude response), produces a different impulse response with the **same magnitude spectrum** but minimum possible group delay — halves a linear-phase filter's latency for the same frequency response. Classic homomorphic (real-cepstrum) algorithm: log-magnitude spectrum -> cepstrum -> fold the anti-causal half onto the causal half (this is what forces the result minimum-phase) -> exponentiate back through the spectral domain.

```c
// x: n samples (n <= fftLen); fftLen must be a power of two, comfortably
// larger than n (4-8x is a reasonable rule of thumb) for accurate
// cepstral liftering. outMinPhase: room for fftLen samples — energy
// concentrates near the start; callers typically keep only the first
// n-ish samples.
void minimum_phase(const double* x, unsigned long n, unsigned long fftLen, double* outMinPhase);
```

---

## cqt — Constant-Q Transform

```c
#include "dsp/cqt.h"
```

Unlike the DFT/FFT's uniform frequency spacing, the CQT uses logarithmically-spaced center frequencies `fk = fmin * 2^(k/binsPerOctave)` with a constant ratio `fk/bandwidth` ("Q") at every bin — matching how musical pitch and human hearing are roughly logarithmic in frequency, which is why the CQT (not the plain DFT) is the standard choice for chromagrams and pitch detection. Direct-correlation form (Brown 1991): each bin's own-length Hann-windowed complex-exponential kernel is correlated directly against the start of the signal — no FFT, no kernel-matrix precomputation, O(numBins * average-kernel-length) per call.

```c
// signal must have at least enough samples for the lowest (longest-kernel)
// bin — roughly Q*fs/fmin, Q = 1/(2^(1/binsPerOctave)-1) — or that bin's
// kernel silently clips to signalLen (degraded resolution for that bin,
// not an error). out: room for numBins Complex values.
void cqt_forward(const double* signal, unsigned long signalLen, double fs,
                  double fmin, unsigned long numBins, unsigned long binsPerOctave,
                  struct Complex* out);
```

---

## cwt — Continuous Wavelet Transform (Morlet)

```c
#include "dsp/cwt.h"
```

Where the CQT picks log-spaced frequency *bins*, the CWT picks a set of *scales* directly (the caller chooses them, typically log-spaced) and correlates a scaled/shifted Morlet wavelet against the signal at every sample position, producing a full time-scale "scalogram" instead of one value per frame. A scale `s` corresponds to a center frequency of approximately `f = w0*fs / (2*pi*s)` Hz. Direct correlation, kernel support truncated to +/-4 standard deviations of the Gaussian envelope.

```c
// scales: numScales entries (each > 0). w0: Morlet center-frequency
// parameter (5-6 is standard). out: room for numScales*signalLen Complex
// values, row-major by scale.
void cwt_morlet(const double* signal, unsigned long signalLen,
                 const double* scales, unsigned long numScales,
                 double w0, struct Complex* out);
```

---

## imaging — 2D DSP

```c
#include "dsp/imaging.h"
```

The 2D analogues of the 1D building blocks above: `conv2d` is zero-padded ("same"-size) 2D convolution; `fft2d`/`ifft2d` apply `fft.h`'s 1D transform to every row then every column (exact, since the 2D DFT is separable); the kernel generators cover the two convolution kernels almost every image pipeline needs.

```c
// img: h*w row-major; kernel: kh*kw row-major (kh/kw should be odd);
// out: room for h*w doubles. Out-of-bounds kernel taps read as zero.
void conv2d(const double* img, unsigned long h, unsigned long w,
            const double* kernel, unsigned long kh, unsigned long kw, double* out);

void fft2d(struct Complex* img, unsigned long h, unsigned long w);   // in-place, h and w must be powers of two
void ifft2d(struct Complex* img, unsigned long h, unsigned long w);

void gaussian_kernel(double* kernel, unsigned long size, double sigma);  // normalized (sums to 1)
void sobel_x_kernel(double* kernel);   // fixed 3x3, room for 9 doubles
void sobel_y_kernel(double* kernel);
```

---

## AudioBuffer — Lock-Free Multi-Channel Ring Buffer

```c
#include "dsp/audio_buffer.h"
```

A SPSC (single-producer / single-consumer) ring buffer for interleaved multi-channel audio frames. Uses power-of-two capacity and compiler barriers for correct ordering without OS locks.

### Struct

```c
struct AudioBuffer {
    Fixed*        buf;          // interleaved: frame0_ch0, frame0_ch1, ...
    unsigned long cap_frames;   // must be power of two
    unsigned long channels;
    unsigned long head;         // write cursor (volatile)
    unsigned long tail;         // read cursor (volatile)

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

### Example — producer / consumer

```c
#include "dsp/audio_buffer.h"
#include "io.h"

int main() {
    // 256-frame buffer, 2 channels (stereo)
    AudioBuffer ab = audio_buffer_new(256, 2);

    // Producer: write one stereo frame
    Fixed frame[2] = { fixed_from_float(0.5), fixed_from_float(-0.3) };
    ab.write_frames(frame, 1);

    print("readable: ");
    println_int(ab.readable());  // 1

    // Consumer: read it back
    Fixed out[2];
    ab.read_frames(out, 1);
    print("L = "); println_float(fixed_to_float(out[0]));   //  0.5
    print("R = "); println_float(fixed_to_float(out[1]));   // -0.3

    audio_buffer_free(&ab);
    return 0;
}
```

::: info
`mix_frames` adds `frames` into the existing buffer content at the current write position **without** advancing `head`. This is for mixing multiple sources into one output buffer before committing. Call `write_frames` afterward to advance the cursor.
:::

---

## TimerWheel — O(1) Real-Time Timer

```c
#include "dsp/timer_wheel.h"
```

A 256-slot timer wheel supporting up to 64 concurrent timers. `tick()` fires all expired timers in O(timers) — no priority queue needed.

### Constants

```c
#define WHEEL_SLOTS      256
#define WHEEL_MAX_TIMERS  64
```

### Struct

```c
struct TimerEntry {
    void(*callback)(void* ctx);
    void*         ctx;
    unsigned long expires;  // absolute tick when to fire
    unsigned long period;   // 0 = one-shot; >0 = repeat every `period` ticks
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

### Methods

| Method | Description |
|--------|-------------|
| `add(cb, ctx, delay)` | One-shot timer; fires after `delay` ticks. Returns timer id or -1 if full. |
| `add_periodic(cb, ctx, period)` | Repeating timer; fires every `period` ticks, rescheduled automatically. |
| `cancel(id)` | Deactivate timer. Returns 1 if found, 0 otherwise. |
| `tick()` | Advance `current_tick`, fire all timers with `expires == current_tick`. |

### Example

```c
#include "dsp/timer_wheel.h"
#include "io.h"

int blink_count = 0;
int done = 0;

void blink(void* ctx)     { blink_count++; }
void shutdown(void* ctx)  { done = 1; }

int main() {
    TimerWheel tw = timer_wheel_new();

    tw.add_periodic(blink, 0, 10);   // blink every 10 ticks
    tw.add(shutdown, 0, 55);          // stop after 55 ticks

    while (!done) {
        tw.tick();
    }

    print("blink count: ");
    println_int(blink_count);  // 5 (ticks 10,20,30,40,50)
    return 0;
}
```

::: warning
Timer expiry is checked with `expires == current_tick`. If `tick()` is called less frequently than expected (e.g. after an interrupt latency spike), timers may be missed. For critical deadlines, poll with `expires <= current_tick` instead, or use a hardware timer ISR to drive `tick()`.
:::
