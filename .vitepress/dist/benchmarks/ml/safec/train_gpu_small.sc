extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
#include <std/ml/gpu_mps.h>
#include <std/time.h>

#define BATCH 64UL
#define IN_DIM 128UL
#define HIDDEN 256UL
#define OUT_DIM 64UL
#define LR 0.001
#define STEPS 100UL
#define INFERENCE_PASSES 1000UL
#define INFERENCE_BATCH_1 256UL
#define INFERENCE_BATCH_2 256UL
#define INFERENCE_BATCH_3 256UL
#define INFERENCE_BATCH_4 128UL
#define INFERENCE_BATCH_5 64UL
#define INFERENCE_BATCH_6 32UL
#define INFERENCE_BATCH_7 8UL
#define GPU_BATCHES_PER_STEP 1UL
#define GPU_BATCHES_TOTAL_INFERENCE 7UL

double now_ms() {
    return std::time_ns_to_ms(std::time_mono_ns());
}

// See train_gpu.sc (bigger model) for the full comment on this pattern --
// same shape here: X/W1/W2 already GPU-resident (trained in place), so
// every inference pass reads them directly, no upload at all.
double run_inference_batch(struct MpsBuf bufX, struct MpsBuf bufW1, struct MpsBuf bufW2,
                            float* scratch1, float* scratch2, float* yOut,
                            unsigned long count) {
    double sum = 0.0;
    unsafe {
        std::mps_batch_begin();
        unsigned long p = 0UL;
        while (p < count) {
            std::mps_matmul_f32_persistent(bufX, bufW1, scratch1, BATCH, IN_DIM, HIDDEN);
            struct MpsBuf m1Buf = MpsBuf::batch_last_output();
            std::mps_relu_f32_chained(m1Buf, scratch2, BATCH * HIDDEN);
            struct MpsBuf reluBuf = MpsBuf::batch_last_output();
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

        // See train_gpu.sc's comment: X, W1, W2 are uploaded exactly once
        // and stay GPU-resident for the rest of the program -- SGD updates
        // W1/W2 in place on the GPU every step, no per-step re-upload.
        struct MpsBuf bufX = MpsBuf::upload((const float*)xbuf, BATCH * IN_DIM);
        struct MpsBuf bufW1 = MpsBuf::upload((const float*)w1buf, IN_DIM * HIDDEN);
        struct MpsBuf bufW2 = MpsBuf::upload((const float*)w2buf, HIDDEN * OUT_DIM);

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
            struct MpsBuf bufZ1 = MpsBuf::batch_last_output();
            std::mps_relu_f32_chained(bufZ1, scratchH, BATCH * HIDDEN);
            struct MpsBuf bufH = MpsBuf::batch_last_output();
            std::mps_matmul_f32_persistent(bufH, bufW2, scratchY, BATCH, HIDDEN, OUT_DIM);
            struct MpsBuf bufY = MpsBuf::batch_last_output();

            // loss = sum((Y - target)^2)
            std::mps_sub_f32_chained(bufY, (const float*)tbuf, scratchDiff, BATCH * OUT_DIM);
            struct MpsBuf bufDiff = MpsBuf::batch_last_output();

            // hand-derived backward -- see train_gpu.sc's comment for the
            // closed-form MSE + 2-layer ReLU MLP derivation.
            std::mps_scale_f32_chained(bufDiff, 2.0f, scratchDY, BATCH * OUT_DIM);
            struct MpsBuf bufDY = MpsBuf::batch_last_output();

            std::mps_matmul_atb_f32_chained_ab(bufH, bufDY, scratchDW2, BATCH, HIDDEN, OUT_DIM);
            struct MpsBuf bufDW2 = MpsBuf::batch_last_output();

            std::mps_matmul_abt_f32_persistent(bufDY, bufW2, scratchDH, BATCH, OUT_DIM, HIDDEN);
            struct MpsBuf bufDH = MpsBuf::batch_last_output();

            std::mps_relu_backward_f32_chained_ab(bufZ1, bufDH, scratchDZ1, BATCH * HIDDEN);
            struct MpsBuf bufDZ1 = MpsBuf::batch_last_output();

            std::mps_matmul_atb_f32_chained_ab(bufX, bufDZ1, scratchDW1, BATCH, IN_DIM, HIDDEN);
            struct MpsBuf bufDW1 = MpsBuf::batch_last_output();

            // SGD, in place.
            std::mps_sgd_update_f32_chained(bufW2, bufDW2, (float)LR, HIDDEN * OUT_DIM);
            std::mps_sgd_update_f32_chained(bufW1, bufDW1, (float)LR, IN_DIM * HIDDEN);

            // loss value, for monitoring only.
            std::mps_square_f32_chained(bufDiff, scratchSq, BATCH * OUT_DIM);
            struct MpsBuf bufSq = MpsBuf::batch_last_output();
            std::mps_sum_f32_chained(bufSq, lossOut, BATCH * OUT_DIM);

            std::mps_batch_end();

            lastLoss = (double)lossOut[0];
            step = step + 1UL;
        }
        double t1 = now_ms();

        printf("train_ms=%.3f last_loss=%.6f gpu_batches_per_step=%lu\n", t1 - t0, lastLoss, GPU_BATCHES_PER_STEP);
        printf("throughput_samples_per_sec=%.2f\n", (double)(STEPS * BATCH) / ((t1 - t0) / 1000.0));

        // Inference reuses the SAME bufX/bufW1/bufW2 directly.
        double t2 = now_ms();
        double checksum = 0.0;
        checksum = checksum + run_inference_batch(bufX, bufW1, bufW2, scratchZ1, scratchH, scratchY, INFERENCE_BATCH_1);
        checksum = checksum + run_inference_batch(bufX, bufW1, bufW2, scratchZ1, scratchH, scratchY, INFERENCE_BATCH_2);
        checksum = checksum + run_inference_batch(bufX, bufW1, bufW2, scratchZ1, scratchH, scratchY, INFERENCE_BATCH_3);
        checksum = checksum + run_inference_batch(bufX, bufW1, bufW2, scratchZ1, scratchH, scratchY, INFERENCE_BATCH_4);
        checksum = checksum + run_inference_batch(bufX, bufW1, bufW2, scratchZ1, scratchH, scratchY, INFERENCE_BATCH_5);
        checksum = checksum + run_inference_batch(bufX, bufW1, bufW2, scratchZ1, scratchH, scratchY, INFERENCE_BATCH_6);
        checksum = checksum + run_inference_batch(bufX, bufW1, bufW2, scratchZ1, scratchH, scratchY, INFERENCE_BATCH_7);
        double t3 = now_ms();

        printf("inference_ms_per_%lu=%.3f checksum=%.6f gpu_batches_total=%lu\n",
               (unsigned long)INFERENCE_PASSES, t3 - t2, checksum, (unsigned long)GPU_BATCHES_TOTAL_INFERENCE);

        bufX.free();
        bufW1.free();
        bufW2.free();
    }

    return 0;
}
