extern int printf(const char* fmt, ...);
// drand48/srand48 are POSIX, not part of any C standard -- Windows/MSVC's
// UCRT doesn't provide them at all (link error, not just "wrong seed
// stream"). rand()/srand() are the portable C89 fallback there; rand()'s
// RAND_MAX is typically only 15 bits on Windows (vs. drand48's 48), so two
// calls are combined below for closer-to-drand48 resolution. This does
// mean Windows gets a numerically different (still deterministic, still
// seeded) init/data stream than macOS/WSL2's shared drand48 -- already
// true of PyTorch's own independent RNG in this same benchmark's table,
// so a platform-specific loss value isn't a new kind of caveat here.
#ifdef _WIN32
extern int rand();
extern void srand(unsigned int seed);
static double drand48() {
    unsafe { return ((double)rand() * 32768.0 + (double)rand()) / (32768.0 * 32768.0); }
}
static void srand48(long seed) {
    unsafe { srand((unsigned int)seed); }
}
#else
extern double drand48();
extern void srand48(long seed);
#endif
#include <std/ml/tensor.h>
#include <std/ml/tensor_nn.h>
#include <std/ml/tensor_blas.h>
#include <std/time.h>

#define BATCH 64UL
#define IN_DIM 128UL
#define HIDDEN 256UL
#define OUT_DIM 64UL
#define LR 0.001
#define STEPS 100

void sgd_update(&Tensor w) {
    unsafe {
        unsigned long i = 0UL;
        while (i < w->size) {
            float* data = (float*)w->data;
            float* grad = (float*)w->grad;
            data[i] = data[i] - (float)LR * grad[i];
            i = i + 1UL;
        }
    }
}

double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

int main() {
    srand48(42L);
    float xbuf[BATCH * IN_DIM];
    float tbuf[BATCH * OUT_DIM];
    unsigned long i = 0UL;
    while (i < BATCH * IN_DIM) { xbuf[i] = (float)(drand48() - 0.5); i = i + 1UL; }
    i = 0UL;
    while (i < BATCH * OUT_DIM) { tbuf[i] = (float)(drand48() - 0.5); i = i + 1UL; }

    &Tensor X = std::tensor_from_2d(xbuf, BATCH, IN_DIM, 0);
    &Tensor target = std::tensor_from_2d(tbuf, BATCH, OUT_DIM, 0);
    &Tensor W1 = std::tensor_new_2d(IN_DIM, HIDDEN, 1);
    &Tensor W2 = std::tensor_new_2d(HIDDEN, OUT_DIM, 1);
    i = 0UL;
    while (i < IN_DIM * HIDDEN) { unsafe { float* d = (float*)W1->data; d[i] = (float)((drand48() - 0.5) * 0.1); } i = i + 1UL; }
    i = 0UL;
    while (i < HIDDEN * OUT_DIM) { unsafe { float* d = (float*)W2->data; d[i] = (float)((drand48() - 0.5) * 0.1); } i = i + 1UL; }

    double t0 = now_ms();
    int step = 0;
    double lastLoss = 0.0;
    while (step < STEPS) {
        std::tensor_zero_grad(W1);
        std::tensor_zero_grad(W2);
        &Tensor H = std::tensor_relu(std::tensor_matmul_blas(X, W1));
        &Tensor Y = std::tensor_matmul_blas(H, W2);
        &Tensor diff = std::tensor_sub(Y, target);
        &Tensor sq = std::tensor_mul(diff, diff);
        &Tensor loss = std::tensor_sum(sq);
        std::tensor_backward(loss);
        sgd_update(W1);
        sgd_update(W2);
        unsafe { lastLoss = (double)loss->data[0]; }
        step = step + 1;
    }
    double t1 = now_ms();
    printf("train_ms=%.3f last_loss=%.6f\n", t1 - t0, lastLoss);
    printf("throughput_samples_per_sec=%.2f\n", (double)((unsigned long)STEPS * BATCH) / ((t1 - t0) / 1000.0));

    std::tensor_set_grad_enabled(0);
    double t2 = now_ms();
    int inf = 0;
    double checksum = 0.0;
    while (inf < 1000) {
        &Tensor H2 = std::tensor_relu(std::tensor_matmul_blas(X, W1));
        &Tensor Y2 = std::tensor_matmul_blas(H2, W2);
        unsafe { checksum = checksum + (double)Y2->data[0]; }
        // grad is disabled here, so neither tensor holds a grad buffer or
        // parent edges (see tensor_set_grad_enabled's doc comment) --
        // freeing is just releasing this iteration's data/shape buffers,
        // nothing shared with X/W1/W2. Without this, all 1000 passes'
        // worth of H2/Y2 buffers pile up unfreed for the whole loop
        // (SafeC has no GC — nothing frees them on its own the way
        // Python's refcounting does for PyTorch's own H2/Y2 each
        // iteration), and the resulting allocator/cache pressure was a
        // real, measured chunk of this loop's wall time, not just
        // theoretical: growing heap fragmentation as 1000 iterations'
        // worth of small buffers accumulate slows down every subsequent
        // malloc, on top of the memory itself just sitting there unused.
        H2.free();
        Y2.free();
        inf = inf + 1;
    }
    double t3 = now_ms();
    std::tensor_set_grad_enabled(1);
    printf("inference_ms_per_1000=%.3f checksum=%.6f\n", t3 - t2, checksum);
    return 0;
}
