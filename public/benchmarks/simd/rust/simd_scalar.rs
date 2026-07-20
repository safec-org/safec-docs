const N: usize = 20_000_000;
fn main() {
    let mut a = vec![0.0f64; N];
    for i in 0..N {
        a[i] = (i % 1000) as f64 * 0.001;
    }
    let mut sum = 0.0f64;
    for i in 0..N {
        sum += a[i] * a[i];
    }
    println!("{:.6}", sum);
}
