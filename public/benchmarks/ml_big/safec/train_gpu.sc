extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
#include <std/ml/gpu_mps.h>
#include <std/time.sc>

#define BATCH 128UL
#define IN_DIM 512UL
#define HIDDEN 1024UL
#define OUT_DIM 256UL
#define LR 0.001
#define STEPS 50UL
#define INFERENCE_PASSES 200UL
#define INFERENCE_BATCH_1 128UL
#define INFERENCE_BATCH_2 64UL
#define INFERENCE_BATCH_3 8UL
#define GPU_BATCHES_PER_STEP 1UL
#define GPU_BATCHES_TOTAL_INFERENCE 3UL

double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

// Runs 'count' independent inference passes sharing one command-buffer
// batch. X/W1/W2 are already GPU-resident (trained in place -- see
// main()) so every pass here reads them directly, no upload at all.
// 'scratch1'/'scratch2' are throwaway CPU readback targets for matmul1's/
// relu's intermediate output (never read; only every pass's final Y2 --
// 'yOut' -- matters), shared across every pass since dispatch, not
// readback, is what's being timed. Every pass computes the bit-identical
// Y2 (X/W1/W2 never change during inference), so summing yOut[0] 'count'
// times after one shared readback is exactly equivalent, floating-point-
// wise, to what N separate per-pass buffers would have produced.
double run_inference_batch(void* bufX, void* bufW1, void* bufW2,
                            float* scratch1, float* scratch2, float* yOut,
                            unsigned long count) {
    double sum = 0.0;
    unsafe {
        std::mps_batch_begin();
        unsigned long p = 0UL;
        while (p < count) {
            std::mps_matmul_f32_persistent(bufX, bufW1, scratch1, BATCH, IN_DIM, HIDDEN);
            void* m1Buf = std::mps_batch_last_output_buffer();
            std::mps_relu_f32_chained(m1Buf, scratch2, BATCH * HIDDEN);
            void* reluBuf = std::mps_batch_last_output_buffer();
            std::mps_matmul_f32_persistent(reluBuf, bufW2, yOut, BATCH, HIDDEN, OUT_DIM);
            p = p + 1UL;
        }
        std::mps_batch_end();
        p = 0UL;
        while (p < count) {
            sum = sum + (double)yOut[0];
            p = p + 1UL;
        }
    }
    return sum;
}

int main() {
    if (!std::mps_available()) { printf("no MPS device -- skipping\n"); return 0; }
    srand48(42L);

    unsafe {
        float* xbuf = (float*)malloc(sizeof(float) * BATCH * IN_DIM);
        float* tbuf = (float*)malloc(sizeof(float) * BATCH * OUT_DIM);
        float* w1buf = (float*)malloc(sizeof(float) * IN_DIM * HIDDEN);
        float* w2buf = (float*)malloc(sizeof(float) * HIDDEN * OUT_DIM);

        unsigned long i = 0UL;
        while (i < BATCH * IN_DIM) { xbuf[i] = (float)(drand48() - 0.5); i = i + 1UL; }
        i = 0UL;
        while (i < BATCH * OUT_DIM) { tbuf[i] = (float)(drand48() - 0.5); i = i + 1UL; }
        i = 0UL;
        while (i < IN_DIM * HIDDEN) { w1buf[i] = (float)((drand48() - 0.5) * 0.1); i = i + 1UL; }
        i = 0UL;
        while (i < HIDDEN * OUT_DIM) { w2buf[i] = (float)((drand48() - 0.5) * 0.1); i = i + 1UL; }

        // X, W1, W2 are uploaded exactly ONCE and never touch CPU memory
        // again until the very end (W1/W2) or ever again at all (X). Every
        // step's forward pass reads them straight from these buffers; SGD
        // (mps_sgd_update_f32_chained) writes the updated weights back
        // in place on the GPU -- no per-step re-upload the way a CPU-
        // resident ->data array (and a CPU-side SGD loop reading/writing
        // it) would need.
        void* bufX = std::mps_upload_persistent((const float*)xbuf, BATCH * IN_DIM * sizeof(float));
        void* bufW1 = std::mps_upload_persistent((const float*)w1buf, IN_DIM * HIDDEN * sizeof(float));
        void* bufW2 = std::mps_upload_persistent((const float*)w2buf, HIDDEN * OUT_DIM * sizeof(float));

        float* scratchZ1 = (float*)malloc(sizeof(float) * BATCH * HIDDEN);
        float* scratchH = (float*)malloc(sizeof(float) * BATCH * HIDDEN);
        float* scratchY = (float*)malloc(sizeof(float) * BATCH * OUT_DIM);
        float* scratchDiff = (float*)malloc(sizeof(float) * BATCH * OUT_DIM);
        float* scratchSq = (float*)malloc(sizeof(float) * BATCH * OUT_DIM);
        float* scratchDY = (float*)malloc(sizeof(float) * BATCH * OUT_DIM);
        float* scratchDW2 = (float*)malloc(sizeof(float) * HIDDEN * OUT_DIM);
        float* scratchDH = (float*)malloc(sizeof(float) * BATCH * HIDDEN);
        float* scratchDZ1 = (float*)malloc(sizeof(float) * BATCH * HIDDEN);
        float* scratchDW1 = (float*)malloc(sizeof(float) * IN_DIM * HIDDEN);
        float lossOut[1];

        double t0 = now_ms();
        double lastLoss = 0.0;
        unsigned long step = 0UL;
        while (step < STEPS) {
            std::mps_batch_begin();

            // forward
            std::mps_matmul_f32_persistent(bufX, bufW1, scratchZ1, BATCH, IN_DIM, HIDDEN);
            void* bufZ1 = std::mps_batch_last_output_buffer();
            std::mps_relu_f32_chained(bufZ1, scratchH, BATCH * HIDDEN);
            void* bufH = std::mps_batch_last_output_buffer();
            std::mps_matmul_f32_persistent(bufH, bufW2, scratchY, BATCH, HIDDEN, OUT_DIM);
            void* bufY = std::mps_batch_last_output_buffer();

            // loss = sum((Y - target)^2)
            std::mps_sub_f32_chained(bufY, (const float*)tbuf, scratchDiff, BATCH * OUT_DIM);
            void* bufDiff = std::mps_batch_last_output_buffer();

            // hand-derived backward (MSE + 2-layer ReLU MLP has a known
            // closed form -- no generic autograd graph walk needed):
            //   d(loss)/dY = 2*diff
            //   dW2 = H^T . dY            dH  = dY . W2^T
            //   dZ1 = relu'(Z1) * dH      dW1 = X^T . dZ1
            std::mps_scale_f32_chained(bufDiff, 2.0f, scratchDY, BATCH * OUT_DIM);
            void* bufDY = std::mps_batch_last_output_buffer();

            std::mps_matmul_atb_f32_chained_ab(bufH, bufDY, scratchDW2, BATCH, HIDDEN, OUT_DIM);
            void* bufDW2 = std::mps_batch_last_output_buffer();

            std::mps_matmul_abt_f32_persistent(bufDY, bufW2, scratchDH, BATCH, OUT_DIM, HIDDEN);
            void* bufDH = std::mps_batch_last_output_buffer();

            std::mps_relu_backward_f32_chained_ab(bufZ1, bufDH, scratchDZ1, BATCH * HIDDEN);
            void* bufDZ1 = std::mps_batch_last_output_buffer();

            std::mps_matmul_atb_f32_chained_ab(bufX, bufDZ1, scratchDW1, BATCH, IN_DIM, HIDDEN);
            void* bufDW1 = std::mps_batch_last_output_buffer();

            // SGD, in place. dH above reads bufW2 BEFORE this encodes a
            // write to it, so it correctly sees this step's pre-update W2
            // -- Metal executes one encoder's dispatches in encoding order.
            std::mps_sgd_update_f32_chained(bufW2, bufDW2, (float)LR, HIDDEN * OUT_DIM);
            std::mps_sgd_update_f32_chained(bufW1, bufDW1, (float)LR, IN_DIM * HIDDEN);

            // loss value, for monitoring only -- not consumed by anything above.
            std::mps_square_f32_chained(bufDiff, scratchSq, BATCH * OUT_DIM);
            void* bufSq = std::mps_batch_last_output_buffer();
            std::mps_sum_f32_chained(bufSq, lossOut, BATCH * OUT_DIM);

            std::mps_batch_end();

            lastLoss = (double)lossOut[0];
            step = step + 1UL;
        }
        double t1 = now_ms();

        printf("train_ms=%.3f last_loss=%.6f gpu_batches_per_step=%lu\n", t1 - t0, lastLoss, GPU_BATCHES_PER_STEP);
        printf("throughput_samples_per_sec=%.2f\n", (double)(STEPS * BATCH) / ((t1 - t0) / 1000.0));

        // Inference reuses the SAME bufX/bufW1/bufW2 directly -- W1/W2 are
        // already GPU-resident with their final trained values, so there's
        // no separate upload step here at all, not even a persistent one.
        double t2 = now_ms();
        double checksum = 0.0;
        checksum = checksum + run_inference_batch(bufX, bufW1, bufW2, scratchZ1, scratchH, scratchY, INFERENCE_BATCH_1);
        checksum = checksum + run_inference_batch(bufX, bufW1, bufW2, scratchZ1, scratchH, scratchY, INFERENCE_BATCH_2);
        checksum = checksum + run_inference_batch(bufX, bufW1, bufW2, scratchZ1, scratchH, scratchY, INFERENCE_BATCH_3);
        double t3 = now_ms();

        printf("inference_ms_per_%lu=%.3f checksum=%.6f gpu_batches_total=%lu\n",
               (unsigned long)INFERENCE_PASSES, t3 - t2, checksum, (unsigned long)GPU_BATCHES_TOTAL_INFERENCE);

        std::mps_release_persistent(bufX, BATCH * IN_DIM * sizeof(float));
        std::mps_release_persistent(bufW1, IN_DIM * HIDDEN * sizeof(float));
        std::mps_release_persistent(bufW2, HIDDEN * OUT_DIM * sizeof(float));
    }

    return 0;
}
