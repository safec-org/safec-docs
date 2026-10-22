"""DSP with NumPy/SciPy: the same workloads and sizes as ../safec/dsp_cpu.sc.

  fir        lfilter(h, 1, x) (streaming IIR/FIR, one pass) and
             np.convolve(x, h) (block), 127 taps
  fftconv    scipy.signal.fftconvolve with a 4,096-tap kernel
  biquad     sosfilt with one RBJ low-pass section
  stft       scipy.signal.ShortTimeFFT, 1,024-point Hann, hop 256
             (one-sided spectrum, as SciPy returns it)
  resample   scipy.signal.resample_poly(x, 160, 147)   (polyphase, Kaiser)
  conv2d     scipy.signal.convolve2d(img, g, mode="same")

Everything runs in float64, then the 1-D workloads again in float32 (rows
tagged f32). Mean time per call after one warm-up call.
"""
import time
import numpy as np
import scipy
import scipy.signal as sg

rng = np.random.default_rng(42)


def bench(name, fn, iters=3, extra=""):
    fn()
    t0 = time.perf_counter()
    for _ in range(iters):
        r = fn()
    ms = (time.perf_counter() - t0) * 1000 / iters
    print(f"[{name}] ms_per_call={ms:.3f}{extra}", flush=True)
    return r


n = 1 << 20
x = rng.uniform(-1, 1, n)
h = rng.uniform(-1, 1, 127)
bench("fir stream n=1048576 taps=127", lambda: sg.lfilter(h, 1.0, x))
bench("fir block n=1048576 taps=127", lambda: np.convolve(x, h))
k = rng.uniform(-1, 1, 4096)
bench("fftconv n=1048576 taps=4096", lambda: sg.fftconvolve(x, k))

# RBJ low-pass, fs 48 kHz, f0 1 kHz, Q 0.7071 (same coefficients as SafeC's Biquad::lowpass)
fs, f0, q = 48000.0, 1000.0, 0.7071
w0 = 2 * np.pi * f0 / fs
alpha = np.sin(w0) / (2 * q)
cw = np.cos(w0)
a0 = 1 + alpha
b = np.array([(1 - cw) / 2, 1 - cw, (1 - cw) / 2]) / a0
a = np.array([1.0, -2 * cw / a0, (1 - alpha) / a0])
sos = np.concatenate([b, a])[None, :]
bench("biquad n=1048576", lambda: sg.sosfilt(sos, x))

stf = sg.ShortTimeFFT(sg.windows.hann(1024, sym=False), hop=256, fs=1.0, fft_mode="onesided")
bench("stft n=1048576 frame=1024 hop=256", lambda: stf.stft(x, p0=0, p1=(n - 1024) // 256 + 1))
bench("resample n=1048576 44100->48000", lambda: sg.resample_poly(x, 160, 147))
img = rng.uniform(-1, 1, (1024, 1024))
ax = np.arange(5) - 2
g1 = np.exp(-ax * ax / 2.0)
g = np.outer(g1, g1)
g /= g.sum()
bench("conv2d 1024x1024 k=5x5", lambda: sg.convolve2d(img, g, mode="same"))
# single precision
xf = x.astype(np.float32)
hf = h.astype(np.float32)
kf = k.astype(np.float32)
sosf = sos.astype(np.float32)
bench("fir stream n=1048576 taps=127 f32", lambda: sg.lfilter(hf, np.float32(1.0), xf))
bench("fir block n=1048576 taps=127 f32", lambda: np.convolve(xf, hf))
bench("fftconv n=1048576 taps=4096 f32", lambda: sg.fftconvolve(xf, kf))
bench("biquad n=1048576 f32", lambda: sg.sosfilt(sosf, xf))
bench("stft n=1048576 frame=1024 hop=256 f32", lambda: stf.stft(xf, p0=0, p1=(n - 1024) // 256 + 1))
bench("resample n=1048576 44100->48000 f32", lambda: sg.resample_poly(xf, 160, 147))
print(f"numpy {np.__version__} scipy {scipy.__version__}")
