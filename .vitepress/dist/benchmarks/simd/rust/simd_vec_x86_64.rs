// x86_64 counterpart to simd_vec.rs (which uses AArch64 NEON intrinsics and
// so only builds on Apple Silicon). Same computation, same "explicit SIMD
// type, stable channel, no std::simd/portable_simd nightly feature" spirit,
// using SSE2 (baseline on every x86_64 CPU, no target-feature flags needed)
// instead of NEON. SSE2's __m128d is 2 x f64, vs. NEON's float64x2_t here
// being loaded 2-at-a-time already — same vector width, so the loop shape
// is unchanged from simd_vec.rs.
use std::arch::x86_64::*;

const N: usize = 20_000_000;

fn main() {
    let mut a = vec![0.0f64; N];
    for i in 0..N {
        a[i] = (i % 1000) as f64 * 0.001;
    }

    let limit = (N / 2) * 2;
    let mut sum: f64;
    unsafe {
        let mut acc = _mm_setzero_pd();
        let mut i = 0;
        while i < limit {
            let v = _mm_loadu_pd(a.as_ptr().add(i));
            acc = _mm_add_pd(acc, _mm_mul_pd(v, v));
            i += 2;
        }
        let mut tmp = [0.0f64; 2];
        _mm_storeu_pd(tmp.as_mut_ptr(), acc);
        sum = tmp[0] + tmp[1];
        while i < N {
            sum += a[i] * a[i];
            i += 1;
        }
    }
    println!("{:.6}", sum);
}
