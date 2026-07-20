extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
#include <std/ml/tensor.h>
#include <std/ml/tensor_nn.h>
#include <std/ml/tensor_blas.h>
#include <std/time.h>

#define BATCH 128UL
#define IN_DIM 512UL
#define HIDDEN 1024UL
#define OUT_DIM 256UL
#define LR 0.001
#define STEPS 50

void sgd_update(&Tensor w) {
    unsafe {
        unsigned long i = 0UL;
        while (i < w->size) {
            double* data = (double*)w->data;
            double* grad = (double*)w->grad;
            data[i] = data[i] - LR * grad[i];
            i = i + 1UL;
        }
    }
}

double now_ms() {
    return std::time_ns_to_ms(std::time_mono_ns());
}

int main() {
    srand48(42L);

    double xbuf[BATCH * IN_DIM];
    double tbuf[BATCH * OUT_DIM];
    unsigned long i = 0UL;
    while (i < BATCH * IN_DIM) { xbuf[i] = drand48() - 0.5; i = i + 1UL; }
    i = 0UL;
    while (i < BATCH * OUT_DIM) { tbuf[i] = drand48() - 0.5; i = i + 1UL; }

    &Tensor X = std::tensor_from_2d(xbuf, BATCH, IN_DIM, 0);
    &Tensor target = std::tensor_from_2d(tbuf, BATCH, OUT_DIM, 0);

    &Tensor W1 = std::tensor_new_2d(IN_DIM, HIDDEN, 1);
    &Tensor W2 = std::tensor_new_2d(HIDDEN, OUT_DIM, 1);
    i = 0UL;
    while (i < IN_DIM * HIDDEN) { unsafe { double* d = (double*)W1->data; d[i] = (drand48() - 0.5) * 0.1; } i = i + 1UL; }
    i = 0UL;
    while (i < HIDDEN * OUT_DIM) { unsafe { double* d = (double*)W2->data; d[i] = (drand48() - 0.5) * 0.1; } i = i + 1UL; }

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

        unsafe { lastLoss = loss->data[0]; }
        step = step + 1;
    }
    double t1 = now_ms();

    printf("train_ms=%.3f last_loss=%.6f\n", t1 - t0, lastLoss);
    printf("throughput_samples_per_sec=%.2f\n", (double)((unsigned long)STEPS * BATCH) / ((t1 - t0) / 1000.0));

    // Inference-only benchmark. tensor_set_grad_enabled(0) mirrors
    // PyTorch's 'with torch.no_grad():' -- every op below still computes
    // real values, just skips building the autograd graph since nothing
    // here ever calls tensor_backward.
    std::tensor_set_grad_enabled(0);
    double t2 = now_ms();
    int inf = 0;
    double checksum = 0.0;
    while (inf < 200) {
        &Tensor H2 = std::tensor_relu(std::tensor_matmul_blas(X, W1));
        &Tensor Y2 = std::tensor_matmul_blas(H2, W2);
        unsafe { checksum = checksum + Y2->data[0]; }
        inf = inf + 1;
    }
    double t3 = now_ms();
    std::tensor_set_grad_enabled(1);
    printf("inference_ms_per_200=%.3f checksum=%.6f\n", t3 - t2, checksum);

    return 0;
}
