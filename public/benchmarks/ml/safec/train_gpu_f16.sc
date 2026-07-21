extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
extern void* malloc(unsigned long size);
extern void  free(void* ptr);
#include <std/ml/gpu_mps.h>
#include <std/ml/float16.h>
#include <std/time.h>

#define BATCH 64UL
#define IN_DIM 128UL
#define HIDDEN 256UL
#define OUT_DIM 64UL
#define LR 0.001
#define STEPS 100UL

double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

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

        void* bufX = std::mps_upload_f16_persistent((const float*)xbuf, BATCH * IN_DIM);
        void* bufW1 = std::mps_upload_f16_persistent((const float*)w1buf, IN_DIM * HIDDEN);
        void* bufW2 = std::mps_upload_f16_persistent((const float*)w2buf, HIDDEN * OUT_DIM);

        unsigned short* tHalf = (unsigned short*)malloc(sizeof(unsigned short) * BATCH * OUT_DIM);
        std::f32_to_fp16_bulk((const float*)tbuf, tHalf, BATCH * OUT_DIM);

        unsigned short* scratchZ1 = (unsigned short*)malloc(sizeof(unsigned short) * BATCH * HIDDEN);
        unsigned short* scratchH = (unsigned short*)malloc(sizeof(unsigned short) * BATCH * HIDDEN);
        unsigned short* scratchY = (unsigned short*)malloc(sizeof(unsigned short) * BATCH * OUT_DIM);
        unsigned short* scratchDiff = (unsigned short*)malloc(sizeof(unsigned short) * BATCH * OUT_DIM);
        unsigned short* scratchDY = (unsigned short*)malloc(sizeof(unsigned short) * BATCH * OUT_DIM);
        unsigned short* scratchDW2 = (unsigned short*)malloc(sizeof(unsigned short) * HIDDEN * OUT_DIM);
        unsigned short* scratchDH = (unsigned short*)malloc(sizeof(unsigned short) * BATCH * HIDDEN);
        unsigned short* scratchDZ1 = (unsigned short*)malloc(sizeof(unsigned short) * BATCH * HIDDEN);
        unsigned short* scratchDW1 = (unsigned short*)malloc(sizeof(unsigned short) * IN_DIM * HIDDEN);

        double t0 = now_ms();
        double lastLoss = 0.0;
        unsigned long step = 0UL;
        while (step < STEPS) {
            std::mps_batch_begin();

            std::mps_matmul_f16_persistent(bufX, bufW1, scratchZ1, BATCH, IN_DIM, HIDDEN);
            void* bufZ1 = std::mps_batch_last_output_buffer();
            std::mps_relu_f16_chained(bufZ1, scratchH, BATCH * HIDDEN);
            void* bufH = std::mps_batch_last_output_buffer();
            std::mps_matmul_f16_persistent(bufH, bufW2, scratchY, BATCH, HIDDEN, OUT_DIM);
            void* bufY = std::mps_batch_last_output_buffer();

            std::mps_sub_f16_chained(bufY, (const unsigned short*)tHalf, scratchDiff, BATCH * OUT_DIM);
            void* bufDiff = std::mps_batch_last_output_buffer();

            std::mps_scale_f16_chained(bufDiff, 2.0f, scratchDY, BATCH * OUT_DIM);
            void* bufDY = std::mps_batch_last_output_buffer();

            std::mps_matmul_atb_f16_chained_ab(bufH, bufDY, scratchDW2, BATCH, HIDDEN, OUT_DIM);
            void* bufDW2 = std::mps_batch_last_output_buffer();

            std::mps_matmul_abt_f16_persistent(bufDY, bufW2, scratchDH, BATCH, OUT_DIM, HIDDEN);
            void* bufDH = std::mps_batch_last_output_buffer();

            std::mps_relu_backward_f16_chained_ab(bufZ1, bufDH, scratchDZ1, BATCH * HIDDEN);
            void* bufDZ1 = std::mps_batch_last_output_buffer();

            std::mps_matmul_atb_f16_chained_ab(bufX, bufDZ1, scratchDW1, BATCH, IN_DIM, HIDDEN);
            void* bufDW1 = std::mps_batch_last_output_buffer();

            std::mps_sgd_update_f16_chained(bufW2, bufDW2, (float)LR, HIDDEN * OUT_DIM);
            std::mps_sgd_update_f16_chained(bufW1, bufDW1, (float)LR, IN_DIM * HIDDEN);

            std::mps_batch_end();

            double lossAcc = 0.0;
            unsigned long j = 0UL;
            while (j < BATCH * OUT_DIM) {
                float d = std::fp16_to_f32(scratchDiff[j]);
                lossAcc = lossAcc + (double)(d * d);
                j = j + 1UL;
            }
            lastLoss = lossAcc;

            step = step + 1UL;
        }
        double t1 = now_ms();

        printf("train_ms=%.3f last_loss=%.6f\n", t1 - t0, lastLoss);
        printf("throughput_samples_per_sec=%.2f\n", (double)(STEPS * BATCH) / ((t1 - t0) / 1000.0));

        std::mps_release_persistent(bufX, BATCH * IN_DIM * 2UL);
        std::mps_release_persistent(bufW1, IN_DIM * HIDDEN * 2UL);
        std::mps_release_persistent(bufW2, HIDDEN * OUT_DIM * 2UL);
    }

    return 0;
}
