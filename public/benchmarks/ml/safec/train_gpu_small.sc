extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
#include <std/ml/tensor.h>
#include <std/ml/tensor_nn.h>
#include <std/ml/tensor_gpu.h>
#include <std/ml/gpu_mps.h>
#include <std/time.h>

#define BATCH 64UL
#define IN_DIM 128UL
#define HIDDEN 256UL
#define OUT_DIM 64UL
#define LR 0.001
#define STEPS 100
#define INFERENCE_PASSES 1000UL
#define INFERENCE_BATCH_1 256UL
#define INFERENCE_BATCH_2 256UL
#define INFERENCE_BATCH_3 256UL
#define INFERENCE_BATCH_4 128UL
#define INFERENCE_BATCH_5 64UL
#define INFERENCE_BATCH_6 32UL
#define INFERENCE_BATCH_7 8UL
#define GPU_BATCHES_TOTAL_INFERENCE 7UL

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

double now_ms() {
    return std::time_ns_to_ms(std::time_mono_ns());
}

// Runs 'count' independent forward passes sharing one command-buffer
// batch, X/W1/W2 uploaded to the GPU once (outside this function, via
// mps_upload_persistent) and reused directly by every pass instead of
// being re-uploaded fresh each time -- see gpu_mps.h's mps_matmul_f32_
// persistent comment. 'scratch1'/'scratch2' are throwaway CPU readback
// targets for matmul1's/relu's intermediate output (never read; only
// every pass's final Y2, 'yOut', matters), shared across every pass in
// this batch since dispatch, not readback, is what's being timed.
double run_inference_batch_persistent(void* bufX, void* bufW1, void* bufW2,
                                       float* scratch1, float* scratch2, float* yOut,
                                       unsigned long count) {
    double sum = 0.0;
    unsafe {
        std::tensor_gpu_batch_begin();
        unsigned long p = 0UL;
        while (p < count) {
            std::mps_matmul_f32_persistent(bufX, bufW1, scratch1, BATCH, IN_DIM, HIDDEN);
            void* m1Buf = std::mps_batch_last_output_buffer();
            std::mps_relu_f32_chained(m1Buf, scratch2, BATCH * HIDDEN);
            void* reluBuf = std::mps_batch_last_output_buffer();
            std::mps_matmul_f32_persistent(reluBuf, bufW2, yOut, BATCH, HIDDEN, OUT_DIM);
            p = p + 1UL;
        }
        std::tensor_gpu_batch_end();
        p = 0UL;
        while (p < count) {
            sum = sum + (double)yOut[0];
            p = p + 1UL;
        }
    }
    return sum;
}

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

        // One batch for the whole step: forward (matmul -> relu -> matmul),
        // the loss computation (diff/square/sum, via their chain-aware
        // _gpu twins instead of the plain CPU tensor_sub/tensor_mul/
        // tensor_sum), and backward (matmul2-backward -> relu-backward ->
        // matmul1-backward) all share the SAME open command buffer -- one
        // GPU sync for the entire training step instead of two.
        std::tensor_gpu_batch_begin();
        &Tensor H = std::tensor_relu_gpu(std::tensor_matmul_gpu(X, W1));
        &Tensor Y = std::tensor_matmul_gpu(H, W2);
        &Tensor diff = std::tensor_sub_gpu(Y, target);
        &Tensor sq = std::tensor_square_gpu(diff);
        &Tensor loss = std::tensor_sum_gpu(sq);
        std::tensor_backward(loss);
        std::tensor_gpu_batch_end();
        sgd_update(W1);
        sgd_update(W2);

        unsafe { lastLoss = (double)loss->data[0]; }
        step = step + 1;
    }
    double t1 = now_ms();

    printf("train_ms=%.3f last_loss=%.6f\n", t1 - t0, lastLoss);
    printf("throughput_samples_per_sec=%.2f\n", (double)((unsigned long)STEPS * BATCH) / ((t1 - t0) / 1000.0));

    // Inference-only benchmark. tensor_set_grad_enabled(0) mirrors
    // PyTorch's 'with torch.no_grad():'.
    std::tensor_set_grad_enabled(0);
    double t2 = now_ms();
    double checksum = 0.0;
    unsafe {
        void* bufX = std::mps_upload_persistent((const float*)X->data, BATCH * IN_DIM * sizeof(float));
        void* bufW1 = std::mps_upload_persistent((const float*)W1->data, IN_DIM * HIDDEN * sizeof(float));
        void* bufW2 = std::mps_upload_persistent((const float*)W2->data, HIDDEN * OUT_DIM * sizeof(float));
        float* scratch1 = (float*)malloc(sizeof(float) * BATCH * HIDDEN);
        float* scratch2 = (float*)malloc(sizeof(float) * BATCH * HIDDEN);
        float* yOut = (float*)malloc(sizeof(float) * BATCH * OUT_DIM);

        checksum = checksum + run_inference_batch_persistent(bufX, bufW1, bufW2, scratch1, scratch2, yOut, INFERENCE_BATCH_1);
        checksum = checksum + run_inference_batch_persistent(bufX, bufW1, bufW2, scratch1, scratch2, yOut, INFERENCE_BATCH_2);
        checksum = checksum + run_inference_batch_persistent(bufX, bufW1, bufW2, scratch1, scratch2, yOut, INFERENCE_BATCH_3);
        checksum = checksum + run_inference_batch_persistent(bufX, bufW1, bufW2, scratch1, scratch2, yOut, INFERENCE_BATCH_4);
        checksum = checksum + run_inference_batch_persistent(bufX, bufW1, bufW2, scratch1, scratch2, yOut, INFERENCE_BATCH_5);
        checksum = checksum + run_inference_batch_persistent(bufX, bufW1, bufW2, scratch1, scratch2, yOut, INFERENCE_BATCH_6);
        checksum = checksum + run_inference_batch_persistent(bufX, bufW1, bufW2, scratch1, scratch2, yOut, INFERENCE_BATCH_7);

        std::mps_release_persistent(bufX, BATCH * IN_DIM * sizeof(float));
        std::mps_release_persistent(bufW1, IN_DIM * HIDDEN * sizeof(float));
        std::mps_release_persistent(bufW2, HIDDEN * OUT_DIM * sizeof(float));
        free((void*)scratch1);
        free((void*)scratch2);
        free((void*)yOut);
    }
    double t3 = now_ms();
    std::tensor_set_grad_enabled(1);
    printf("inference_ms_per_%lu=%.3f checksum=%.6f gpu_batches_total=%lu\n",
           (unsigned long)INFERENCE_PASSES, t3 - t2, checksum, (unsigned long)GPU_BATCHES_TOTAL_INFERENCE);

    return 0;
}
