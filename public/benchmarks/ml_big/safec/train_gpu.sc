extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
#include <std/ml/tensor_gpu.h>
#include <std/ml/tensor_gpu.sc>
#include <std/time.sc>

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
            float* data = (float*)w->data;
            float* grad = (float*)w->grad;
            data[i] = data[i] - (float)LR * grad[i];
            i = i + 1UL;
        }
    }
}

double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

int main() {
    if (!std::mps_available()) { printf("no MPS device -- skipping\n"); return 0; }
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

        &Tensor H = std::tensor_relu_gpu(std::tensor_matmul_gpu(X, W1));
        &Tensor Y = std::tensor_matmul_gpu(H, W2);
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
    return 0;
}
