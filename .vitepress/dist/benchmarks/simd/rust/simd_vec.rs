use std::arch::aarch64::*;

const N: usize = 20_000_000;

fn main() {
    let mut a = vec![0.0f64; N];
    for i in 0..N {
        a[i] = (i % 1000) as f64 * 0.001;
    }

    let limit = (N / 2) * 2;
    let mut sum: f64;
    unsafe {
        let mut acc = vdupq_n_f64(0.0);
        let mut i = 0;
        while i < limit {
            let v = vld1q_f64(a.as_ptr().add(i));
            acc = vfmaq_f64(acc, v, v);
            i += 2;
        }
        sum = vaddvq_f64(acc);
        while i < N {
            sum += a[i] * a[i];
            i += 1;
        }
    }
    println!("{:.6}", sum);
}
