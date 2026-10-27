# DSP & Real-Time Utilities

`std/dsp/` covers deterministic fixed-point arithmetic, spectral transforms (DFT/FFT/DCT/STFT/CQT/CWT), filters (FIR/IIR/biquad/comb), convolution, resampling, minimum-phase reconstruction, general Laplace-to-Z (bilinear) transform tooling, 2D/imaging primitives, audio buffering, and real-time timer scheduling — twenty modules in total, plus the [streaming traits](#sample-filter-traits) that every filter and generator satisfies.

```c
#include <std/dsp/dsp_all.h>  // master header: pulls in every module below
```

Types are built with [static methods](/reference/functions#static-methods) (`Biquad::lowpass(...)`, `FirFilter::new(...)`, `Complex::new(...)`; write `std::Biquad::lowpass(...)` outside `namespace std`), and the operations on them are methods (`f.process_block(in[0..n], out[0..n])`).

Every transform/filter that has a meaningful fixed-point form ships **two** implementations — a `double`-precision path and a parallel Q8.24 (`Fixed`) path (function/type names prefixed with `f`, e.g. `fft`/`ffft`, `struct Biquad`/`struct FBiquad`) — following one consistent design: coefficients that only need to be computed once (FFT twiddle factors, filter coefficients) are always derived in `double` precision using the real transcendental functions in `std/math.h`, then quantized to `Fixed` a single time; the actual per-sample signal-processing loop runs entirely in Q8.24 arithmetic. Standard practice — even hardware FFT accelerators precompute twiddle tables offline in floating point. A few of the newer, more specialized transforms (STFT, CQT, CWT, minimum-phase, imaging) are `double`-only: there's no natural fixed-point form for a Gaussian-windowed wavelet kernel or a homomorphic cepstrum, the same way there's no fixed-point `sin()`.

The signal paths also come in single precision (`float`), described in [Single precision](#single-precision-float) below.

---

## Single precision (`float`) {#single-precision-float}

The FFT, filters, convolution, windows, STFT, resampling, DCT/DFT and imaging also take `float` data. These functions have an `_f32` suffix, and the types a `32` suffix (`Complex32`, `FirFilter32`, `Biquad32`). Each one has the same contract as its `double` version. Float halves the memory traffic and fits twice as many values in each vector register. It keeps about 7 significant digits (relative error around 1e-6) instead of about 16.

- **One implementation for both precisions.** The FFT, convolution, resampling and 2D code is written once, in internal core headers (`fft_core.h`, `conv_core.h`, `resample_core.h`, `imaging_core.h`). Each core is compiled twice, once per precision, from `fft.sc` and `fft_f32.sc` and so on. The float FFT therefore has the same radix-4 passes, 16-point codelet, cache blocking, threading and per-ISA builds. With oneMKL linked it uses DFTI's single-precision transforms.
- **Precision where it matters.** Twiddle factors and window values are computed in `double` and rounded to float once. Filter coefficients are designed in `double` (`Biquad::lowpass`, ...) and then converted with `Biquad32::from`. The O(n²) `dct2_f32`/`dft_f32` and the sinc kernel in `resample_sinc_f32` sum in `double`, so only their inputs and outputs are float.
- **Real time.** `fft_serial_f32`/`ifft_serial_f32` never allocate or lock, the same as the double versions. Up to 4096 points they read a compile-time float twiddle table; call `fft_prepare_f32(maxN)` for longer ones. `fft_prepare` fills only the double table.

```c
// complex_dsp.h
struct Complex32 {
    float re; float im;
    /* + - * / neg abs arg conj, as Complex */
    static Complex32 new(float re, float im);
    static Complex32 polar(float magnitude, float theta);
    static Complex32 from(Complex c);     // round each part to float
    Complex          to_complex() const;  // back to double (exact)
};

// fft.h
int fft_f32([]Complex32 data);
int ifft_f32([]Complex32 data);
int fft_serial_f32([]Complex32 data);
int ifft_serial_f32([]Complex32 data);
int fft_prepare_f32(unsigned long n);
int fft_real_f32([]const float in, []Complex32 out);
int fft_batch_f32([]Complex32 data, unsigned long n, int inverse);   // data.len / n transforms
int fft_real_batch_f32([]const float in, []Complex32 out, unsigned long n);
int fft_real_half_batch_f32([]const float in, []Complex32 out, unsigned long n);   // and fft_real_half_f32

// dsp.h
float dsp_dot_f32([]const float a, []const float b);   // f32x8 FMA accumulators

// filter.h, biquad.h, comb.h: each filter has process(x), reset() and
// process_block(in, out) on float samples, as its double counterpart
static struct FirFilter32 FirFilter32::new([]const float coeffs, []float history);
static struct IirFilter32 IirFilter32::new([]const float b, []const float a, []float xHistory, []float yHistory);
static struct Biquad32 Biquad32::new(float b0, float b1, float b2, float a1, float a2);
static struct Biquad32 Biquad32::from(struct Biquad bq);   // a double design, rounded once
static struct CombFF32 CombFF32::new([]float history, float gain);   // delay = history.len
static struct CombFB32 CombFB32::new([]float history, float gain);
static struct KarplusStrong32 KarplusStrong32::new([]float buffer, float damping);   // next(), pluck()

// convolution.h, window.h, stft.h, resample.h
int conv_direct_f32([]const float x, []const float h, []float out);
int conv_fft_f32([]const float x, []const float h, []float out);
void window_hann_f32([]float w);       // also _rectangular_, _hamming_, _blackman_f32
void window_apply_f32([]float x, []const float w);
int stft_forward_f32([]const float signal, unsigned long frameSize, unsigned long hopSize, int windowType, []Complex32 out);
int stft_inverse_f32([]const Complex32 frames, unsigned long frameSize, unsigned long hopSize, int windowType, []float outSignal);
void resample_sinc_f32([]const float in, []float out, unsigned long kernelHalfWidth);   // also resample_nearest_f32, resample_linear_f32

// dct.h, dft.h, imaging.h
int dct2_f32([]const float in, []float out);
int idct3_f32([]const float in, []float out);
int dft_f32([]const Complex32 in, []Complex32 out);   // also dft_real_f32, idft_f32
int conv2d_f32([]const float img, unsigned long h, unsigned long w, []const float kernel, unsigned long kh, unsigned long kw, []float out);
int fft2d_f32([]Complex32 img, unsigned long h, unsigned long w);    // and ifft2d_f32
```

The analysis-only modules (CQT, CWT, minimum-phase, z-transform design) stay `double`.

::: warning High-order IIR in float
`IirFilter32` is a direct-form filter, and float rounding in its coefficients grows with the order. For anything steeper than second order, cascade `Biquad32` sections with a [`FilterChain`](#filter-chain).
:::

---

## fixed — Q8.24 Fixed-Point Arithmetic {#fixed-q824-fixed-point-arithmetic}

```c
#include <std/dsp/fixed.h>
```

`Fixed` is a **Q8.24** fixed-point type: 8 integer bits, 24 fractional bits, stored as a 32-bit signed int (matches 24-bit audio sample depth for sub-sample precision). It's a `newtype` over `int`, so the compiler treats it as a distinct type while using the same bit representation.

### Constants {#constants}

| Constant | Value | Meaning |
|----------|-------|---------|
| `FIXED_ONE` | 16777216 (2^24) | 1.0 |
| `FIXED_HALF` | 8388608 | 0.5 |
| `FIXED_PI` | 52707178 | ≈ π |

### Conversion {#conversion}

```c
Fixed  fixed_from_int(int x);       // x << 24
Fixed  fixed_from_float(double f);  // (Fixed)(f * 16777216.0)
int    fixed_to_int(Fixed x);       // x >> 24 (arithmetic, rounds toward -inf)
double fixed_to_float(Fixed x);     // x / 16777216.0
```

### Arithmetic {#arithmetic}

```c
Fixed fixed_add(Fixed a, Fixed b);
Fixed fixed_sub(Fixed a, Fixed b);
Fixed fixed_mul(Fixed a, Fixed b);   // (a * b) >> 24, 64-bit intermediate
Fixed fixed_div(Fixed a, Fixed b);   // (a << 24) / b, 64-bit intermediate
Fixed fixed_abs(Fixed x);
Fixed fixed_neg(Fixed x);
Fixed fixed_sqrt(Fixed x);           // Newton-Raphson, 4 iterations; x must be >= 0
```

### Example {#example}

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

## dsp — Array Primitives {#dsp-array-primitives}

```c
#include <std/dsp/dsp.h>
```

Basic array-level building blocks shared by the rest of `std/dsp/`. All operate on `Fixed` slices (`[]Fixed`) with no heap allocation, except `dsp_dot_f64` (a `double`-array primitive, unrelated to the Fixed path — see below).

```c
Fixed dsp_dot([]const Fixed a, []const Fixed b);           // sum of a[i]*b[i]
void dsp_scale([]Fixed a, Fixed scale);            // a[i] *= scale
unsigned long dsp_add([]const Fixed a, []const Fixed b, []Fixed out); // out[i] = a[i]+b[i]
void dsp_clip([]Fixed a, Fixed lo, Fixed hi);      // clamp to [lo, hi]
Fixed dsp_peak([]const Fixed a);                          // max |a[i]|
Fixed dsp_rms([]const Fixed a);                           // sqrt(mean(a[i]^2))

// SIMD FMA-accelerated dot product of two *double* arrays (see simd.h) —
// 4 elements/iteration via simd_fma_f64x4, scalar remainder for n % 4.
// No Fixed counterpart: Q8.24 multiply needs a 64-bit widening shift per
// element that std::simd's integer FMA doesn't provide. Used internally
// by convolution.h's conv_direct once the kernel is reversed into a
// contiguous access pattern; also usable directly.
double dsp_dot_f64([]const double a, []const double b);

// out[q] = dot(a, x[q .. q+n)) for q < nOut: an FIR / correlation over many
// outputs. 32 outputs per pass (64 in float), each tap broadcast into four
// accumulator vectors: no horizontal sums, one load per multiply-add.
int dsp_sliding_dot_f64([]const double a, []const double x, []double out);
int dsp_sliding_dot_f32([]const float a, []const float x, []float out);
```

::: tip
`dsp_moving_avg`/`dsp_iir_lp` from earlier versions of this module were removed — they were superseded by `filter.h`'s general streaming `FirFilter`/`IirFilter` (a 1-tap moving average is just `FirFilter::new` with `numTaps=1`, and a first-order IIR low-pass is `IirFilter::new` with a 1-pole `a[]`) and `biquad.h`'s cookbook designers, both of which are real, verified DSP implementations rather than the earlier toy versions.
:::

---

## complex_dsp — Complex Numbers {#complex_dsp-complex-numbers}

```c
#include <std/dsp/complex_dsp.h>
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

    static Complex new(double re, double im);
    static Complex polar(double magnitude, double theta);  // (mag*cos(theta), mag*sin(theta))
};

struct FComplex {   // Q8.24 counterpart — Fixed re/im, same shape minus operator/
    Fixed re; Fixed im;
    FComplex operator+(FComplex other) const;
    FComplex operator-(FComplex other) const;
    FComplex operator*(FComplex other) const;
    FComplex neg() const;
    Fixed     abs() const;
    FComplex  conj() const;

    static FComplex new(Fixed re, Fixed im);
    static FComplex from(Complex c);   // quantize a design-time double result to Q8.24
    Complex         to_complex() const;
};
```

`a * b + c` works with named locals, and so does chaining an operator call directly onto a temporary — `Complex::new(x, 0.0) * zInv` compiles and evaluates correctly; the temporary is materialized into a hidden stack slot for the call, no need to bind it to a named variable first.

---

## dft — Discrete Fourier Transform (direct, O(n²)) {#dft-discrete-fourier-transform-direct-on}

```c
#include <std/dsp/dft.h>
```

Direct-summation DFT: `X[k] = sum x[n] * e^(-i*2*pi*k*n/N)`. Works for **any** N, not just powers of two — use this for arbitrary-length transforms, and as the independent reference `fft.h`'s own correctness checks are verified against. The twiddles come from a table of the n exact roots of unity, indexed by k·n mod N, not a `cos`/`sin` call per term. From 256 points the output bins are split across the thread pool.

```c
int dft([]const Complex in, []Complex out);       // forward, complex -> complex
int dft_real([]const double in, []Complex out);          // forward, real -> complex
int idft([]const Complex in, []Complex out);      // inverse (includes 1/N)
int fdft([]const FComplex in, []FComplex out);    // Q8.24 forward
int fidft([]const FComplex in, []FComplex out);   // Q8.24 inverse
```

---

## fft — Fast Fourier Transform (radix-2 Cooley-Tukey) {#fft-fast-fourier-transform-radix-2-cooley-tukey}

```c
#include <std/dsp/fft.h>
```

In-place, iterative decimation-in-time FFT: O(n log n) instead of `dft.h`'s O(n²), at the cost of requiring `n` to be a power of two (functions return 0 and leave `data` unmodified otherwise — fall back to `dft.h` for arbitrary lengths).

How it runs:

- **Twiddle table.** Twiddle factors come from one table shared by all threads. It is built the first time a length needs it and then kept; it only ever grows, so readers never lock.
- **Fused passes.** A generated 16-point codelet runs the first four stages in registers. The rest are fused into radix-8 passes (three stages per sweep over the data, with the W8 constants folded in, as in SPIRAL/FFTE's radix-8/16 factorizations), then radix-4 or radix-2 for what's left. A 4096-point transform is the codelet and three passes instead of eleven radix-2 sweeps. Transforms up to 4096 points run as one block; longer ones run stages up to 2048 points block by block, so a block stays in L1/L2 through all of them.
- **SIMD width.** Butterflies work on four complex values per vector where that fits the registers: float with AVX2, and both precisions with AVX-512. Otherwise they work on two (double with AVX2, SSE2, Neon).
- **Bit reversal.** Up to 4096 points the bit-reversal swaps come from a compile-time list of index pairs, so there's no per-element index walk or unpredictable branch. Above that it's done incrementally, split across threads.
- **Threads.** Transforms of 32768 points or more are split across the [thread pool](/stdlib/sync#parallel). `fft_batch` and `fft_real_batch` spread many transforms over it.
- **Real input.** `fft_real` computes the full n-point spectrum from one n/2-point complex transform; the split is vectorized two bins at a time from both ends. `fft_real_half` / `fft_real_half_batch` return only the n/2 + 1 non-redundant bins, as FFTW and NumPy do, which halves what a batch writes.
- **SIMD level.** The butterflies are built per [CPU SIMD level](/stdlib/simd#cpu-levels): AVX2 adds FMA to the complex multiply, and AVX-512 doubles the width.
- **Vendor FFT.** With oneMKL linked (the Intel choice of [`[build] blas`](/advanced/safeguard#linking-external-libraries-and-library-output)), its DFTI transforms run `fft`/`ifft`/`fft_real` below 65,536 points, every row of `fft_batch`/`fft_real_batch`, and `fft2d`. On this page's 7800X3D that halves `fft2d` and the batched transforms. `fft_vendor()` reports it. FFTW-based libraries, AOCL-FFTW included, are GPL and are not used.
- **Apple: vDSP for FIR, built-in FFT.** On macOS a program that links Accelerate (safeguard's default there) runs `dsp_sliding_dot_f64`/`_f32` — and so `FirFilter::process_block`, `conv_direct` and the other FIR paths built on it — through `vDSP_convD`/`vDSP_conv`, which measured 1.2–1.4× faster than std's NEON kernel on an M1 Pro, with and without threads ([benchmarks](/benchmarks#dsp)). vDSP's convolution doesn't allocate, lock or start threads, so `FirFilter::process_block` keeps its real-time properties. The FFT stays on std's own transform: oneMKL doesn't exist for Apple Silicon, and std's FFT is faster than vDSP's except for vDSP's threaded double batch. Without Accelerate, std's kernels run (safeguard passes `-U` for the weak references, since Apple's linker resolves even weak references at link time).

::: tip Real-time use
In an audio callback, call `fft_prepare(maxN)` once beforehand, then use `fft_serial`/`ifft_serial`. They never allocate, lock, touch the thread pool or call a vendor library. `fft`/`ifft` are not for real-time code when oneMKL is linked: the first call for each length creates a DFTI descriptor.
:::

On NVIDIA GPUs, [`fft_cuda.h`](/stdlib/ml#cuda-cudnn-cufft) runs the same transforms, and 2D ones, through cuFFT, for any length; on AMD GPUs, [`fft_rocm.h`](/stdlib/ml#rocm-miopen-hipfft) does the same through hipFFT.

```c
int dsp_is_pow2(unsigned long n);                                    // n >= 1 and a power of two
int fft([]Complex data);                      // in-place forward, 1=ok/0=not-pow2
int ifft([]Complex data);                     // in-place inverse (includes 1/N)
int fft_serial([]Complex data);              // fft on the calling thread only
int ifft_serial([]Complex data);
int fft_prepare(unsigned long n);                             // build twiddles for lengths <= n
int fft_real([]const double in, []Complex out); // real -> full n-point spectrum
// 'batch' transforms stored back to back (row r at data[r*n]), threaded
int fft_batch([]Complex data, unsigned long n, int inverse);   // data.len / n transforms
int fft_real_batch([]const double in, []Complex out, unsigned long n);   // batch = in.len / n
// the same in place: row r holds its n reals in its first n/2 slots
int fft_real_batch_inplace([]Complex data, unsigned long n);
// only bins 0 .. n/2 (n/2 + 1 values; row r at out[r*(n/2+1)])
int fft_real_half([]const double in, []Complex out);
int fft_real_half_batch([]const double in, []Complex out, unsigned long n);
int ffft([]FComplex data);                    // Q8.24 forward
int fifft([]FComplex data);                   // Q8.24 inverse
```

### Example {#example-1}

```c
#include <std/dsp/fft.h>
#include <std/io.h>

int main() {
    struct Complex data[8];
    for (int i = 0; i < 8; i++) data[i] = Complex::new((double)i, 0.0);

    fft(data[0..8]);     // in-place forward transform
    ifft(data[0..8]);    // in-place inverse — round-trips back to the original signal

    print("data[1].re after round-trip (expect ~1.0): ");
    println_float(data[1].re);
    return 0;
}
```

---

## convolution — Linear Convolution {#convolution-linear-convolution}

```c
#include <std/dsp/convolution.h>
```

`y[n] = sum_k x[k]*h[n-k]`, output length `len_x + len_h - 1`. Two implementations, same output, different cost trade-off:

```c
// O(len_x * len_h) — best for short kernels. Each output is one SIMD dot
// product; long ones are split across the thread pool.
int conv_direct([]const double x, []const double h, []double out);
int fconv_direct([]const Fixed x, []const Fixed h, []Fixed out);

// O(n log n) via fft.h — best for long inputs/kernels where direct
// convolution's O(len_x*len_h) cost dominates. double-precision only.
// A kernel at most 1/16 of the signal uses overlap-add (blocks of about
// 8x the kernel through fft_batch); otherwise x and h share one packed
// complex FFT.
int conv_fft([]const double x, []const double h, []double out);
```

`out` must have room for `len_x + len_h - 1` samples in all three. `conv_direct` reverses the kernel once, so the outputs whose window lies wholly inside `x` become one `dsp_sliding_dot_f64`. That call computes 32 outputs at a time (64 in float), broadcasting each tap into four accumulator vectors, with no horizontal sums. The edge outputs use `dsp_dot_f64`.

---

## window — Window Functions {#window-window-functions}

```c
#include <std/dsp/window.h>
```

Symmetric N-point analysis windows, for tapering a frame before an FFT (reduces spectral leakage) or FIR design (window method). All four fill a caller-provided array; none allocate.

```c
void window_rectangular([]double w);  // w[i] = 1 (no tapering — leakage baseline)
void window_hann([]double w);         // 0.5*(1 - cos(2*pi*i/(n-1)))
void window_hamming([]double w);       // 0.54 - 0.46*cos(2*pi*i/(n-1))
void window_blackman([]double w);      // 0.42 - 0.5*cos(2*pi*i/(n-1)) + 0.08*cos(4*pi*i/(n-1))
void window_apply([]double x, []const double w);  // x[i] *= w[i], in place
```

---

## filter — General Streaming FIR / IIR {#filter-general-streaming-fir-iir}

```c
#include <std/dsp/filter.h>
```

One-sample-at-a-time difference-equation filters, keeping their own history/state internally so a caller can feed real-time samples one at a time (e.g. inside a `std::Reactor` task) rather than needing the whole signal in memory the way `convolution.h` does. They never allocate or use threads. `FirFilter::process` walks its ring buffer as two contiguous runs with four partial sums, without a per-tap modulo. `FirFilter::process_block` (up to 256 taps) copies the history and up to 512 new samples into a window on the stack and runs `dsp_sliding_dot_f64` over it: many outputs per vector instead of one dot product each.

```
FIR ("feedforward"): y[n] = sum_{k=0}^{M-1} b[k]*x[n-k]
IIR ("feedback"):     y[n] = sum b[k]*x[n-k] - sum_{k=1}^{N-1} a[k]*y[n-k]   (a[0] = 1)
```

```c
struct FirFilter {
    const &double coeffs;   // b[0..numTaps-1], caller-owned
    unsigned long numTaps;
    &double       history;  // caller-provided ring buffer, numTaps elements
    unsigned long pos;

    double process(double x);
    void   reset();          // zeros the history
    void   process_block([]const double in, []double out);   // out may alias in

    static struct FirFilter new([]const double coeffs, []double history);   // numTaps = min(coeffs.len, history.len)
};

struct IirFilter {           // general-order, direct form I
    const &double b; unsigned long numB;   // feedforward
    const &double a; unsigned long numA;   // feedback, a[0] assumed 1
    &double xHistory; &double yHistory;    // numB / numA elements, zeroed
    unsigned long xPos; unsigned long yPos;

    double process(double x);
    void   reset();
    void   process_block([]const double in, []double out);

    static struct IirFilter new([]const double b, []const double a,
                                []double xHistory, []double yHistory);
};
```

`FFirFilter` and `FIirFilter` (built with `FFirFilter::new` / `FIirFilter::new`) are the Q8.24 counterparts, same shape with `Fixed` in place of `double`. All of them are [`SampleFilter`s](#sample-filter-traits).

### Example — 3-tap moving average {#example-3-tap-moving-average}

```c
#include <std/dsp/filter.h>
#include <std/io.h>

int main() {
    double coeffs[3] = { 0.333333, 0.333333, 0.333333 };
    double history[3] = { 0.0, 0.0, 0.0 };
    struct FirFilter mavg = FirFilter::new(coeffs[0..3UL], history[0..3UL]);

    double step[6] = { 1.0, 1.0, 1.0, 1.0, 1.0, 1.0 };
    double out[6];
    mavg.process_block(step[0..6UL], out[0..6UL]);
    // out ~= [0.333, 0.667, 1, 1, 1, 1]
    println_float(out[2]);
    return 0;
}
```

---

## biquad — 2nd-Order IIR Sections + RBJ Cookbook {#biquad-2nd-order-iir-sections-rbj-cookbook}

```c
#include <std/dsp/biquad.h>
```

`struct Biquad` is a single Direct-Form-II-Transposed 2nd-order section (`a0` always normalized to 1):

```
y[n] = b0*x[n] + z1
z1'  = b1*x[n] - a1*y[n] + z2
z2'  = b2*x[n] - a2*y[n]
```

Cascade several for a higher-order filter with a [`FilterChain`](#filter-chain), which runs each section's own `process_block` over the block.

```c
struct Biquad {
    double b0, b1, b2, a1, a2, z1, z2;
    double process(double x);
    void   reset();
    // process() over a block with the state kept in registers; in == out allowed, real-time safe.
    // Runs direct form I with scattered look-ahead (Parhi & Messerschmitt):
    // y[i] = q[i] + (a1^2 - 2 a2) y[i-2] - a2^2 y[i-4], q from the inputs only,
    // so the even and odd outputs are two independent chains, computed together
    // in one 2-lane vector. The added poles mirror the filter's own (same
    // radius), so it stays stable. Matches process() to rounding.
    void   process_block([]const double in, []double out);

    // Z-domain frequency response at freqHz: magnitude (linear) and phase (radians).
    double response_mag(double fs, double freqHz) const;
    double response_phase(double fs, double freqHz) const;

    static struct Biquad new(double b0, double b1, double b2, double a1, double a2);

    // Robert Bristow-Johnson's "Audio EQ Cookbook" formulas. fs = sample rate,
    // f0 = cutoff/center frequency, q = Q factor (higher = narrower/more resonant).
    static struct Biquad lowpass(double fs, double f0, double q);
    static struct Biquad highpass(double fs, double f0, double q);
    static struct Biquad bandpass(double fs, double f0, double q);   // constant 0dB peak gain
    static struct Biquad notch(double fs, double f0, double q);
    static struct Biquad allpass(double fs, double f0, double q);
    static struct Biquad peaking(double fs, double f0, double q, double dbGain);
    static struct Biquad lowshelf(double fs, double f0, double s, double dbGain);   // s = shelf slope, 1.0 = default
    static struct Biquad highshelf(double fs, double f0, double s, double dbGain);

    // General analog-to-digital 2nd order section via the bilinear transform:
    // H(s) = (B2 s^2 + B1 s + B0) / (A2 s^2 + A1 s + A0) -> matching digital biquad.
    static struct Biquad bilinear(double B0, double B1, double B2,
                                  double A0, double A1, double A2, double fs);
};

struct FBiquad {   // Q8.24 counterpart
    Fixed b0, b1, b2, a1, a2, z1, z2;
    Fixed process(Fixed x);
    void  reset();
    void  process_block([]const Fixed in, []Fixed out);

    static struct FBiquad from(struct Biquad bq);   // quantize a designed-in-double biquad once
};
```

`Biquad32` (float) has the same methods plus `Biquad32::new` and `Biquad32::from(bq)`; see [Single precision](#single-precision-float).

### Example {#example-2}

```c
#include <std/dsp/biquad.h>
#include <std/io.h>

int main() {
    struct Biquad lp = Biquad::lowpass(48000.0, 1000.0, 0.707);

    // Unity DC gain — the standard invariant a lowpass must satisfy.
    print("|H(0)| (expect 1.0): ");
    println_float(lp.response_mag(48000.0, 0.0));

    double y = 0.0;
    for (int i = 0; i < 200; i++) y = lp.process(1.0);
    print("settled output for constant input 1.0 (expect ~1.0): ");
    println_float(y);

    struct Biquad32 lp32 = Biquad32::from(lp);   // the same design, rounded to float once
    struct FBiquad  lpq  = FBiquad::from(lp);    // ... or quantized to Q8.24 once
    return 0;
}
```

---

## dct — Discrete Cosine Transform (DCT-II / DCT-III) {#dct-discrete-cosine-transform-dct-ii-dct-iii}

```c
#include <std/dsp/dct.h>
```

`dct2` is "the DCT" in the JPEG/MPEG sense — real-valued in, real-valued out, energy-compacting. `idct3` is its exact inverse. Direct O(n²) summation, like `dft.h`: cosines come from a 4n-entry table, and from 512 points the outputs are split across the thread pool.

```c
int dct2([]const double in, []double out);    // X[k] = sum x[n]*cos(pi/N*(n+0.5)*k)
int idct3([]const double in, []double out);   // exact inverse of dct2
int fdct2([]const Fixed in, []Fixed out);      // Q8.24 forward
int fidct3([]const Fixed in, []Fixed out);     // Q8.24 inverse
```

---

## stft — Short-Time Fourier Transform + Multi-Resolution STFT Loss {#stft-short-time-fourier-transform-multi-resolution-stft-loss}

```c
#include <std/dsp/stft.h>
```

`stft_forward` slides a windowed frame across a signal and FFTs each one (`frameSize` must be a power of two), producing a spectrogram. The frames are transformed together as real transforms, in place, with `fft_real_batch_inplace` (half the work of complex ones). On a host they run on the thread pool. For streaming, window a frame at a time and use `fft_serial`. `stft_inverse` reconstructs the signal via windowed overlap-add, normalized by the accumulated squared analysis window — exact whenever the window/hop combination satisfies the constant-overlap-add condition (e.g. Hann at 50%/75% overlap).

```c
#define STFT_WIN_RECTANGULAR 0
#define STFT_WIN_HANN 1
#define STFT_WIN_HAMMING 2
#define STFT_WIN_BLACKMAN 3

unsigned long stft_num_frames(unsigned long signalLen, unsigned long frameSize, unsigned long hopSize);

// out: room for stft_num_frames(...) * frameSize Complex values.
int stft_forward([]const double signal, unsigned long frameSize, unsigned long hopSize, int windowType, []Complex out);

// outSignal: room for (numFrames-1)*hopSize + frameSize samples, caller-zeroed
// (stft_inverse accumulates into it).
int stft_inverse([]const Complex frames, unsigned long frameSize, unsigned long hopSize, int windowType, []double outSignal);
```

`mr_stft_loss` computes the "multi-resolution STFT loss" from the neural-vocoder literature (Yamamoto et al., Parallel WaveGAN): for each of `numResolutions` frame sizes (each a power of two, hop = frameSize/4, Hann window), the spectral-convergence plus mean L1 log-magnitude error between two same-length signals, averaged across resolutions:

```c
double mr_stft_loss([]const double x, []const double y, []const unsigned long frameSizes);
```

Lower is more similar; `0` for identical signals.

---

## resample — Upsampling / Downsampling {#resample-upsampling-downsampling}

```c
#include <std/dsp/resample.h>
```

Maps an `inLen`-sample buffer onto a caller-sized `outLen`-sample buffer — `outLen > inLen` upsamples, `outLen < inLen` downsamples, same function either direction.

```c
void resample_nearest([]const double in, []double out);
void resample_linear([]const double in, []double out);

// Windowed-sinc (band-limited): the highest-quality option. When
// downsampling, the kernel's cutoff auto-scales to the new (lower)
// Nyquist rate, so it also acts as the anti-aliasing filter. 8-16 is a
// good kernelHalfWidth default. Each tap's weight comes from the
// angle-difference formulas, with tables of the tap angles made once per
// call. That makes the taps independent, so they run four to a vector, and
// the four per-output angles are carried from output to output by rotation
// (recomputed exactly every 64). Outputs run on the thread pool.
void resample_sinc([]const double in, []double out, unsigned long kernelHalfWidth);

// Q8.24 nearest/linear. No sinc counterpart — a windowed-sinc kernel needs
// per-tap sin/cos evaluated against a runtime fractional position, which
// doesn't fit the usual design-time-float/runtime-fixed split.
void fresample_nearest([]const Fixed in, []Fixed out);
void fresample_linear([]const Fixed in, []Fixed out);
```

---

## ztransform — General N-th Order Bilinear Transform {#ztransform-general-n-th-order-bilinear-transform}

```c
#include <std/dsp/ztransform.h>
```

`biquad.h`'s `Biquad::bilinear` / `Biquad::response_mag` / `response_phase` generalized to arbitrary order — for designing/analyzing a single higher-order IIR section directly instead of as a cascade of biquads. No polynomial root-finder is included (that needs a separate iterative eigenvalue/Durand-Kerner algorithm); this works directly on coefficient arrays.

```c
// B, A: order+1 analog (s-domain) coefficients, ascending powers of s.
// bOut, aOut: order+1 digital coefficients in the negative-power-of-z
// convention (aOut[0] = 1) — feed straight into filter.h's IirFilter::new.
int bilinear_nth_order([]const double B, []const double A, double fs, []double bOut, []double aOut);   // order = B.len - 1

double ztransform_response_mag([]const double b, []const double a, double fs, double freqHz);
double ztransform_response_phase([]const double b, []const double a, double fs, double freqHz);
```

---

## comb — Comb Filters + Karplus-Strong String Synthesis {#comb-comb-filters-karplus-strong-string-synthesis}

```c
#include <std/dsp/comb.h>
```

`CombFF` (feedforward, `y[n] = x[n] + gain*x[n-delay]`) produces evenly-spaced notches; `CombFB` (feedback, `y[n] = x[n] + gain*y[n-delay]`) produces evenly-spaced resonant peaks (the basis of most artificial reverbs — a bank of feedback combs at different delays/gains).

```c
struct CombFF {
    &double history; unsigned long delay; unsigned long pos; double gain;
    double process(double x);
    void   reset();
    void   process_block([]const double in, []double out);   // out may alias in

    // 'history' is the caller's ring buffer (its length is the delay), zeroed.
    static struct CombFF new([]double history, double gain);   // delay = history.len
};
struct CombFB {
    &double history; unsigned long delay; unsigned long pos; double gain;
    double process(double x);
    void   reset();
    void   process_block([]const double in, []double out);

    static struct CombFB new([]double history, double gain);   // delay = history.len
};
```

`KarplusStrong` is the classic plucked-string/percussion synthesis algorithm (Karplus & Strong, 1983): a feedback comb whose feedback path is a 2-tap averaging lowpass instead of a plain scalar gain — the averaging progressively removes high-frequency energy each time around the loop, producing a string's characteristic brighten-then-decay timbre. It takes no input, so it is a [`SampleGenerator`](#sample-filter-traits), not a filter: its methods are `next()` and `pluck()`, not `process()` and `reset()`.

```c
struct KarplusStrong {
    &double buffer;         // ring buffer; also the delay line and the pitch period
    unsigned long length; unsigned long pos;
    double damping;         // 0..1, closer to 1 = slower decay (typical 0.99-0.999)

    double next();          // the next output sample: no input, self-sustaining
    // Refill the delay line with a new excitation (n samples copied in; size
    // it to 'length' for a fresh pluck, the rest keeps its old contents).
    void   pluck([]const double excitation);

    // 'buffer' is the caller's delay line (its length is the period); seed it (or
    // call pluck) before next().
    static struct KarplusStrong new([]double buffer, double damping);   // length = buffer.len
};
```

`FCombFF`, `FCombFB` and `FKarplusStrong` are the Q8.24 counterparts, and `CombFF32`, `CombFB32` and `KarplusStrong32` the float ones, each built with its own `::new`.

### Example — plucked string {#example-plucked-string}

```c
#include <std/dsp/comb.h>
#include <std/io.h>

int main() {
    double excitation[8] = { 0.5, -0.3, 0.8, -0.6, 0.2, -0.9, 0.4, -0.1 };  // noise burst
    double buf[8];
    struct KarplusStrong ks = KarplusStrong::new(buf[0..8UL], 0.995);
    ks.pluck(excitation[0..8UL]);

    for (int i = 0; i < 16; i++) {
        double sample = ks.next();
        println_float(sample);   // decaying, self-sustained "pluck"
    }

    double block[32];
    generate_block(&ks, block[0..32UL]);   // the next 32 samples: works for any SampleGenerator
    return 0;
}
```

---

## Streaming traits: one interface over every filter {#sample-filter-traits}

```c
#include <std/dsp/sample_filter.h>   // also included by filter.h, biquad.h and comb.h
```

The streaming filters above share one shape: `process()` one sample, `process_block()` a buffer (out may alias in), `reset()` the state to silence. `sample_filter.h` names each capability as its own small [trait](/reference/generics#composing-traits) and composes them into the roles generic code asks for. The helpers after the table are written once, ask only for what they use, and are resolved at compile time, so a generic wrapper around a hand-tuned `process_block` costs nothing at run time.

| Trait | Composed of | Satisfied by |
|---|---|---|
| `SampleProcessor` | `process(x)`: one sample in, one out | every filter below |
| `BlockProcessor` | `process_block(in, out)` (slices) | every filter below |
| `Resettable` | `reset()`: back to silence | every filter below |
| `SampleFilter` | `SampleProcessor + BlockProcessor + Resettable` | `double`: `FirFilter`, `IirFilter`, `Biquad`, `CombFF`, `CombFB`; `float`: `FirFilter32`, `IirFilter32`, `Biquad32`, `CombFF32`, `CombFB32`; Q8.24: `FFirFilter`, `FIirFilter`, `FBiquad`, `FCombFF`, `FCombFB`; and [`FilterChain<S, A, B>`](#filter-chain) |
| `SampleGenerator` | `next()`: one sample out, no input | `KarplusStrong`, `KarplusStrong32`, `FKarplusStrong` |

One set of traits covers all three sample types. A trait is satisfied by method name, so a `float` or `Fixed` filter conforms the same way a `double` one does; the helpers take the sample type `S` as a type parameter of their own, inferred from the buffers.

| Function | Bound | Does |
|---|---|---|
| `filter_process_each(&f, in, out)` | `SampleProcessor` | `out[i] = f.process(in[i])`: the `process_block` of a filter with no faster block form |
| `filter_impulse_response(&f, out[0..n])` | `SampleProcessor + Resettable` | the first `n` samples of the response to 1, 0, 0, ... from a reset state; `f` is left reset. A FIR's impulse response is its taps |
| `filter_step_response(&f, out[0..n])` | `SampleProcessor + Resettable` | the same for 1, 1, 1, ... |
| `generate_block(&g, out[0..n])` | `SampleGenerator` | `out[i] = g.next()` |
| `sample_one()` | | `1.0` as a sample of type `S` (`FIXED_ONE` for `Fixed`) |

A filter of your own conforms by having the three methods; it then works with the helpers above, with `FilterChain` and with any `generic<F: SampleFilter>` code:

```c
#include <std/dsp/filter.h>
#include <std/io.h>

// One-pole DC blocker: y[n] = x[n] - x[n-1] + r*y[n-1].
struct DcBlocker {
    double r; double x1; double y1;
    double process(double x);
    void   process_block([]const double in, []double out);
    void   reset();
};
double DcBlocker::process(double x) {
    double y = x - self.x1 + self.r * self.y1;
    self.x1 = x;
    self.y1 = y;
    return y;
}
void DcBlocker::process_block([]const double in, []double out) {
    filter_process_each(self, in, out);
}
void DcBlocker::reset() { self.x1 = 0.0; self.y1 = 0.0; }

// Any SampleFilter: the output after n samples of a unit step.
generic<F: SampleFilter>
double settled(&F f) {
    double s[256];
    filter_step_response(f, s[0..256UL]);
    return s[255];
}

int main() {
    struct DcBlocker dc;
    dc.r = 0.995;
    dc.reset();

    double taps[3] = { 0.25, 0.5, 0.25 };
    double hist[3] = { 0.0, 0.0, 0.0 };
    struct FirFilter fir = FirFilter::new(taps[0..3UL], hist[0..3UL]);

    println_float(settled(&dc));    // ~0.28 and falling: a DC blocker removes DC
    println_float(settled(&fir));   // 1.0: the sum of the taps
    return 0;
}
```

### FilterChain: filters in series {#filter-chain}

```c
generic<S, A: SampleFilter, B: SampleFilter>
struct FilterChain {
    A first;
    B second;

    S    process(S x);     // second.process(first.process(x))
    void reset();          // both stages
    // Each stage over the whole block with its own process_block (so each keeps
    // its fast path), with 'out' as the intermediate buffer: no scratch, out may alias in.
    void process_block([]const S in, []S out);

    static struct FilterChain<S, A, B> of(A first, B second);
};
```

`FilterChain` is a [bounded generic struct](/reference/generics#bounded-generic-structs): `S` is the sample type (`double`, `float` or `Fixed`) and the two stages may be different filter types. It has the three methods, so a chain is itself a `SampleFilter` and chains nest: any number of stages is a chain of chains. `of()` copies the stages into the chain; reach them as `.first` and `.second`. A stage that keeps its history in your buffer (FIR, IIR, comb) still uses that buffer, so don't run the original alongside the chain's copy.

```c
#include <std/dsp/biquad.h>
#include <std/dsp/filter.h>
#include <std/io.h>

int main() {
    // 80 Hz - 8 kHz band: a 4th-order response from two biquads.
    struct Biquad hp = Biquad::highpass(48000.0, 80.0, 0.7071);
    struct Biquad lp = Biquad::lowpass(48000.0, 8000.0, 0.7071);
    struct FilterChain<double, struct Biquad, struct Biquad> band = FilterChain::of(hp, lp);

    // A chain is a SampleFilter, so it can be a stage of another chain.
    double taps[3] = { 0.25, 0.5, 0.25 };
    double hist[3] = { 0.0, 0.0, 0.0 };
    struct FirFilter smooth = FirFilter::new(taps[0..3UL], hist[0..3UL]);
    struct FilterChain<double, struct FilterChain<double, struct Biquad, struct Biquad>, struct FirFilter> eq =
        FilterChain::of(band, smooth);

    double buf[256];
    for (int i = 0; i < 256; i++) buf[i] = (i % 32 < 16) ? 1.0 : -1.0;   // square wave
    eq.process_block(buf[0..256UL], buf[0..256UL]);                              // in place
    println_float(buf[255]);
    return 0;
}
```

---

## minphase — Minimum-Phase Reconstruction {#minphase-minimum-phase-reconstruction}

```c
#include <std/dsp/minphase.h>
```

Given an impulse response (e.g. a linear-phase FIR designed from a target magnitude response), produces a different impulse response with the **same magnitude spectrum** but minimum possible group delay — halves a linear-phase filter's latency for the same frequency response. Classic homomorphic (real-cepstrum) algorithm: log-magnitude spectrum -> cepstrum -> fold the anti-causal half onto the causal half (this is what forces the result minimum-phase) -> exponentiate back through the spectral domain.

```c
// x: n samples (n <= fftLen); fftLen must be a power of two, comfortably
// larger than n (4-8x is a reasonable rule of thumb) for accurate
// cepstral liftering. outMinPhase: room for fftLen samples — energy
// concentrates near the start; callers typically keep only the first
// n-ish samples.
int minimum_phase([]const double x, unsigned long fftLen, []double outMinPhase);
```

---

## cqt — Constant-Q Transform {#cqt-constant-q-transform}

```c
#include <std/dsp/cqt.h>
```

Unlike the DFT/FFT's uniform frequency spacing, the CQT uses logarithmically-spaced center frequencies `fk = fmin * 2^(k/binsPerOctave)` with a constant ratio `fk/bandwidth` ("Q") at every bin — matching how musical pitch and human hearing are roughly logarithmic in frequency, which is why the CQT (not the plain DFT) is the standard choice for chromagrams and pitch detection. Direct-correlation form (Brown 1991): each bin's own-length Hann-windowed complex-exponential kernel is correlated directly against the start of the signal — no FFT, no kernel-matrix precomputation, O(numBins * average-kernel-length) per call. The window and the exponential are advanced by complex rotation, recomputed exactly every 64 samples, and bins run on the thread pool.

```c
// signal must have at least enough samples for the lowest (longest-kernel)
// bin — roughly Q*fs/fmin, Q = 1/(2^(1/binsPerOctave)-1) — or that bin's
// kernel silently clips to signalLen (degraded resolution for that bin,
// not an error). out: room for numBins Complex values.
void cqt_forward([]const double signal, double fs, double fmin, unsigned long binsPerOctave, []Complex out);
```

---

## cwt — Continuous Wavelet Transform (Morlet) {#cwt-continuous-wavelet-transform-morlet}

```c
#include <std/dsp/cwt.h>
```

Where the CQT picks log-spaced frequency *bins*, the CWT picks a set of *scales* directly (the caller chooses them, typically log-spaced) and correlates a scaled/shifted Morlet wavelet against the signal at every sample position, producing a full time-scale "scalogram" instead of one value per frame. A scale `s` corresponds to a center frequency of approximately `f = w0*fs / (2*pi*s)` Hz. Direct correlation, kernel support truncated to +/-4 standard deviations of the Gaussian envelope. Each scale's wavelet is computed once, every output is two SIMD dot products with it, and rows of (scale, position) run on the thread pool.

```c
// scales: numScales entries (each > 0). w0: Morlet center-frequency
// parameter (5-6 is standard). out: room for numScales*signalLen Complex
// values, row-major by scale.
int cwt_morlet([]const double signal, []const double scales, double w0, []Complex out);
```

---

## imaging — 2D DSP {#imaging-2d-dsp}

```c
#include <std/dsp/imaging.h>
```

The 2D analogues of the 1D building blocks above: `conv2d` is zero-padded ("same"-size) 2D convolution, computed as row-wide SIMD multiply-adds per kernel tap with rows on the thread pool; `fft2d`/`ifft2d` apply `fft.h`'s 1D transform to every row then every column (exact, since the 2D DFT is separable). Rows go through `fft_batch`. Columns are gathered 8 at a time into a contiguous buffer, and groups of columns run on the thread pool; the kernel generators cover the two convolution kernels almost every image pipeline needs.

```c
// img: h*w row-major; kernel: kh*kw row-major (kh/kw should be odd);
// out: room for h*w doubles. Out-of-bounds kernel taps read as zero.
int conv2d([]const double img, unsigned long h, unsigned long w, []const double kernel, unsigned long kh, unsigned long kw, []double out);

int fft2d([]Complex img, unsigned long h, unsigned long w);   // in-place, h and w must be powers of two
int ifft2d([]Complex img, unsigned long h, unsigned long w);

int gaussian_kernel([]double kernel, unsigned long size, double sigma);  // normalized (sums to 1)
int sobel_x_kernel([]double kernel);   // fixed 3x3, room for 9 doubles
int sobel_y_kernel([]double kernel);
```

---

## AudioBuffer — Lock-Free Multi-Channel Ring Buffer {#audiobuffer-lock-free-multi-channel-ring-buffer}

```c
#include <std/dsp/audio_buffer.h>
```

A single-producer / single-consumer ring of interleaved multi-channel Q8.24 (`Fixed`) frames, stored inline — no heap. Capacity is `AUDIO_BUF_FRAMES` (512) frames of up to `AUDIO_MAX_CHANNELS` (8) channels, so the struct is large (16 KB); keep it in static storage on small targets.

### Struct {#struct}

```c
struct AudioBuffer {
    Fixed         data[AUDIO_BUF_FRAMES * AUDIO_MAX_CHANNELS];   // interleaved frames
    unsigned long cap_frames;
    unsigned long channels;
    volatile unsigned long head;   // write cursor (frames)
    volatile unsigned long tail;   // read cursor (frames)

    unsigned long readable_frames() const;
    unsigned long writable_frames() const;
    unsigned long write_frames([]const Fixed src);   // returns frames written
    unsigned long read_frames([]Fixed dst);    // returns frames read
    unsigned long peek_frames([]Fixed dst) const;
    unsigned long mix_frames([]const Fixed src);
    void          clear();
    int           is_empty() const;
    int           is_full() const;

    // An empty, zeroed buffer of 'channels' channels (AUDIO_BUF_FRAMES frames).
    static struct AudioBuffer new(unsigned long channels);
};
```

### Example — producer / consumer {#example-producer-consumer}

```c
#include <std/dsp/audio_buffer.h>
#include <std/io.h>

static struct AudioBuffer g_ab;

int main() {
    g_ab = std::AudioBuffer::new(2UL);        // stereo

    Fixed frame[2];
    unsafe {
        frame[0] = std::fixed_from_float(0.5f);
        frame[1] = std::fixed_from_float(-0.25f);
    }
    g_ab.write_frames(frame[0..1]);           // producer
    std::print("readable: ");
    std::println_int((long long)g_ab.readable_frames());   // 1

    Fixed out[2];
    g_ab.read_frames(out[0..1]);              // consumer
    unsafe {
        std::print("L = "); std::println_float((double)std::fixed_to_float(out[0]));   //  0.5
        std::print("R = "); std::println_float((double)std::fixed_to_float(out[1]));   // -0.25
    }
    return 0;
}
```

::: info
`mix_frames` adds `frames` into the existing buffer content at the current write position **without** advancing `head`. This is for mixing multiple sources into one output buffer before committing. Call `write_frames` afterward to advance the cursor.
:::

---

## TimerWheel — O(1) Real-Time Timer {#timerwheel-o1-real-time-timer}

```c
#include <std/dsp/timer_wheel.h>
```

A timer scheduler with up to `WHEEL_TIMERS` (64) concurrent timers. `tick()` advances the clock by one and fires every timer due at the new tick. One wheel is multi-tenant — unrelated subsystems share it with different context types — so each slot stores its callback type-erased; `add` and `add_periodic` are [methods with their own type parameter](/reference/generics#methods-with-their-own-type-parameters) that take a matching `fn void(?&T)` / `?&T` pair, so the call is always well-typed.

### API {#api}

```c
#define WHEEL_SLOTS   256
#define WHEEL_TIMERS  64

struct TimerWheel {
    struct WheelTimer timers[WHEEL_TIMERS];
    unsigned long     current_tick;

    void          init();            // in place: all slots free, tick 0. Call before anything else.

    // One-shot at absolute tick `tick`. Returns the timer id (1-based) or 0 if full.
    generic<T> int add(unsigned long tick, fn void(?&T) cb, ?&T ctx);
    // Every `period` ticks, first at now() + period. Returns the id or 0 if full.
    generic<T> int add_periodic(unsigned long period, fn void(?&T) cb, ?&T ctx);

    void          cancel(int id);    // ignores invalid ids
    int           tick();            // advance one tick; returns how many timers fired
    unsigned long now() const;
};
```

### Example {#example-3}

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
    g_tw.init();
    struct Led led;
    led.toggles = 0;
    unsafe { g_tw.add_periodic(10UL, blink, (?&Led)&led); }  // every 10 ticks
    g_tw.add(55UL, stop, null);                               // once, at tick 55
    while (!g_done) { g_tw.tick(); }
    std::print("blinks: ");
    std::println_int(led.toggles);   // 5 (ticks 10, 20, 30, 40, 50)
    return 0;
}
```

::: warning
Timer expiry is checked with `expires == current_tick`. If `tick()` is called less frequently than expected (e.g. after an interrupt latency spike), timers may be missed. For critical deadlines, poll with `expires <= current_tick` instead, or use a hardware timer ISR to drive `tick()`.
:::
