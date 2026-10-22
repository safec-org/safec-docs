//! The fft_cpu.sc FFT workloads with RustFFT (complex f64, then f32; one thread;
//! RustFFT picks AVX/SSE kernels at run time) and the dsp_cpu.sc resample
//! workload with dasp's sinc interpolator (16 taps each side, f64).
//! RustFFT has no real-input or 2D transform, so there are no rfft and
//! fft2d rows.
use dasp::{interpolate::sinc::Sinc, ring_buffer, Signal};
use rustfft::{num_complex::{Complex32, Complex64}, FftPlanner};
use std::time::Instant;

fn bench<F: FnMut()>(mut f: F, iters: u32) -> f64 {
    f();
    let t0 = Instant::now();
    for _ in 0..iters {
        f();
    }
    t0.elapsed().as_secs_f64() * 1000.0 / iters as f64
}

fn rnd(n: usize) -> Vec<f64> {
    // xorshift, seed 42: deterministic without extra crates
    let mut s: u64 = 42;
    (0..n)
        .map(|_| {
            s ^= s << 13;
            s ^= s >> 7;
            s ^= s << 17;
            (s >> 11) as f64 / (1u64 << 53) as f64 * 2.0 - 1.0
        })
        .collect()
}

fn complex(n: usize) -> Vec<Complex64> {
    let r = rnd(2 * n);
    (0..n).map(|i| Complex64::new(r[2 * i], r[2 * i + 1])).collect()
}

fn main() {
    let mut planner = FftPlanner::<f64>::new();

    let n = 1 << 20;
    let fft = planner.plan_fft_forward(n);
    let mut x = complex(n);
    let mut scratch = vec![Complex64::new(0.0, 0.0); fft.get_inplace_scratch_len()];
    let ms = bench(|| fft.process_with_scratch(&mut x, &mut scratch), 10);
    println!("[fft1d n=1048576 c128 cpu] ms_per_call={:.3}", ms);

    let len = 4096;
    let rows = 1024;
    let fb = planner.plan_fft_forward(len);
    let mut b = complex(len * rows);
    let mut sb = vec![Complex64::new(0.0, 0.0); fb.get_inplace_scratch_len()];
    // one call transforms every consecutive length-4096 chunk
    let ms = bench(|| fb.process_with_scratch(&mut b, &mut sb), 10);
    println!("[batch 1024x4096 c2c cpu] ms_per_call={:.3}", ms);

    // the same two in single precision
    let mut planner32 = FftPlanner::<f32>::new();
    let fft32 = planner32.plan_fft_forward(n);
    let mut x32: Vec<Complex32> = complex(n).iter().map(|c| Complex32::new(c.re as f32, c.im as f32)).collect();
    let mut s32 = vec![Complex32::new(0.0, 0.0); fft32.get_inplace_scratch_len()];
    let ms = bench(|| fft32.process_with_scratch(&mut x32, &mut s32), 10);
    println!("[fft1d n=1048576 c64 cpu] ms_per_call={:.3}", ms);
    let fb32 = planner32.plan_fft_forward(len);
    let mut b32: Vec<Complex32> = complex(len * rows).iter().map(|c| Complex32::new(c.re as f32, c.im as f32)).collect();
    let mut sb32 = vec![Complex32::new(0.0, 0.0); fb32.get_inplace_scratch_len()];
    let ms = bench(|| fb32.process_with_scratch(&mut b32, &mut sb32), 10);
    println!("[batch 1024x4096 c2c f32 cpu] ms_per_call={:.3}", ms);

    // resample 44.1 kHz -> 48 kHz, 1,048,576 input samples
    let input = rnd(n);
    let out_len = n * 160 / 147;
    let ms = bench(
        || {
            let source = dasp::signal::from_iter(input.iter().cloned());
            let sinc = Sinc::new(ring_buffer::Fixed::from([0.0f64; 32]));
            let conv = source.from_hz_to_hz(sinc, 44100.0, 48000.0);
            let s: f64 = conv.take(out_len).sum();
            std::hint::black_box(s);
        },
        3,
    );
    println!("[resample n=1048576 44100->48000] ms_per_call={:.3}", ms);
}
