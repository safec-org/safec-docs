// std::ml's CUDA mixed-precision / 16-bit training ops vs ../pytorch/mp.py. bf16. us per call
// (mean of a run of calls between two syncs, after warm-up).
extern int printf(const char* fmt, ...);
#include <std/ml/gpu_cuda.h>
#include <std/ml/gpu_cudnn.h>
#include <std/ml/cnn.h>
#include <std/collections/vec.h>
#include <std/time.h>

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

// GPT-2 small's parameter tensors (148, 124.4M)
static struct Vec<unsigned long> gpt2_sizes() {
    struct Vec<unsigned long> s = std::vec_with_cap(160UL);
    s.push(50257UL * 768UL); s.push(1024UL * 768UL);
    int l = 0;
    while (l < 12) {
        s.push(768UL); s.push(768UL);                 // ln1
        s.push(768UL * 2304UL); s.push(2304UL);       // qkv
        s.push(768UL * 768UL); s.push(768UL);         // proj
        s.push(768UL); s.push(768UL);                 // ln2
        s.push(768UL * 3072UL); s.push(3072UL);       // fc
        s.push(3072UL * 768UL); s.push(768UL);        // fc proj
        l = l + 1;
    }
    s.push(768UL); s.push(768UL);
    return s;
}

static int g_op = 0;
static struct Vec<struct CudaBuf> g_w;
static struct Vec<struct CudaBuf> g_m;
static struct Vec<struct CudaBuf> g_v;
static struct Vec<struct CudaBuf> g_g;
static struct Vec<struct CudaHalfBuf> g_h;
static struct CudaBuf g_flag;
static struct CudaHalfBuf g_a;
static struct CudaHalfBuf g_b;
static struct CudaHalfBuf g_o;
static struct CudaHalfBuf g_bias;
static struct CudaHalfConv2D g_conv;
static unsigned long g_p[6];
static unsigned long g_step = 0UL;

static int run_op() {
    if (g_op == 0) {   // AdamW over the model, writing the bf16 copy
        g_step = g_step + 1UL;
        unsigned long i = 0UL;
        while (i < g_w.length()) {
            if (!std::cuda_mp_adam(*g_w.at(i), *g_m.at(i), *g_v.at(i), *g_g.at(i), *g_h.at(i), (float)1e-4, (float)0.9,
                                   (float)0.999, (float)1e-8, (float)0.01, 1, g_step, g_flag)) return 0;
            i = i + 1UL;
        }
        return 1;
    }
    if (g_op == 9) {
        g_step = g_step + 1UL;
        return std::cuda_mp_adam_many(&g_w, &g_m, &g_v, &g_g, &g_h, (float)1e-4, (float)0.9, (float)0.999, (float)1e-8,
                                      (float)0.01, 1, g_step, g_flag);
    }
    if (g_op == 1) return std::cuda_unscale_many(&g_g, &g_g, (float)1.0, g_flag);
    if (g_op == 2) return std::cuda_to_half_many(&g_w, &g_h);
    if (g_op == 3) return std::cuda_half_add(g_a, g_b, g_o);
    if (g_op == 4) return std::cuda_half_axpy(g_a, (float)0.5, g_b, g_o);
    if (g_op == 5) return std::cuda_half_bias(g_a, g_bias, g_p[0], g_p[1], g_p[2], 1);
    if (g_op == 6) return std::cuda_half_maxpool2d(g_a, g_o, g_p[0], g_p[1], g_p[2], g_p[3], g_p[4], g_p[5]);
    if (g_op == 7) return std::cuda_half_avgpool2d(g_a, g_o, g_p[0], g_p[1], g_p[2], g_p[3], g_p[4], g_p[5]);
    if (g_op == 8) return std::cuda_half_conv2d_relu(&g_conv, g_a, g_o, g_p[0], g_p[2], g_p[3]);
    return 0;
}

static double timeit(int op, int iters) {
    g_op = op;
    int i = 0;
    while (i < 3) { if (!run_op()) return -1.0; i = i + 1; }
    std::cuda_sync();
    double t = now_ms();
    i = 0;
    while (i < iters) { run_op(); i = i + 1; }
    std::cuda_sync();
    return (now_ms() - t) * 1000.0 / (double)iters;
}

static void fill(struct CudaHalfBuf b) {
    struct CudaBuf z = std::cuda_alloc(b.len);
    std::cuda_zero(z);
    std::cuda_to_half(z, b);
    std::cuda_sync();
    std::cuda_free(&z);
}

int main() {
    if (!std::cuda_available()) return 0;
    struct Vec<unsigned long> sz = gpt2_sizes();
    g_w = std::vec_with_cap(sz.length()); g_m = std::vec_with_cap(sz.length()); g_v = std::vec_with_cap(sz.length());
    g_g = std::vec_with_cap(sz.length()); g_h = std::vec_with_cap(sz.length());
    unsigned long total = 0UL;
    unsigned long i = 0UL;
    while (i < sz.length()) {
        unsigned long n = *sz.at(i);
        total = total + n;
        struct CudaBuf w = std::cuda_alloc(n); std::cuda_zero(w); g_w.push(w);
        struct CudaBuf m = std::cuda_alloc(n); std::cuda_zero(m); g_m.push(m);
        struct CudaBuf v = std::cuda_alloc(n); std::cuda_zero(v); g_v.push(v);
        struct CudaBuf g = std::cuda_alloc(n); std::cuda_zero(g); g_g.push(g);
        g_h.push(std::cuda_half_alloc(n, CUDA_BF16));
        i = i + 1UL;
    }
    g_flag = std::cuda_alloc(1UL); std::cuda_zero(g_flag);
    unsafe { printf("model: %lu tensors, %lu params\n", sz.length(), total); }
    unsafe { printf("[mp adamw+bf16 copy] us=%.1f\n", timeit(0, 20)); }
    unsafe { printf("[mp adamw many+bf16 copy] us=%.1f\n", timeit(9, 20)); }
    unsafe { printf("[mp unscale fp32 inplace] us=%.1f\n", timeit(1, 20)); }
    unsafe { printf("[mp to_bf16 many] us=%.1f\n", timeit(2, 20)); }

    unsigned long big = 64UL * 1024UL * 1024UL;
    g_a = std::cuda_half_alloc(big, CUDA_BF16); g_b = std::cuda_half_alloc(big, CUDA_BF16); g_o = std::cuda_half_alloc(big, CUDA_BF16);
    fill(g_a); fill(g_b);
    unsafe { printf("[half add 64M] us=%.1f\n", timeit(3, 50)); }
    unsafe { printf("[half axpy 64M] us=%.1f\n", timeit(4, 50)); }
    g_bias = std::cuda_half_alloc(256UL, CUDA_BF16); fill(g_bias);
    g_p[0] = 64UL; g_p[1] = 256UL; g_p[2] = 56UL * 56UL;
    unsafe { printf("[half bias+relu 64x256x56x56] us=%.1f\n", timeit(5, 50)); }
    g_p[0] = 64UL; g_p[1] = 64UL; g_p[2] = 112UL; g_p[3] = 112UL; g_p[4] = 3UL; g_p[5] = 2UL;
    unsafe { printf("[half maxpool 3x3s2 64x64x112x112] us=%.1f\n", timeit(6, 50)); }
    g_p[0] = 64UL; g_p[1] = 256UL; g_p[2] = 56UL; g_p[3] = 56UL; g_p[4] = 2UL; g_p[5] = 2UL;
    unsafe { printf("[half avgpool 2x2s2 64x256x56x56] us=%.1f\n", timeit(7, 50)); }

    // conv + bias + relu, NCHW bf16, batch 32
    unsigned long cs[20];   // inC, outC, k, pad, hw
    cs[0] = 64UL;  cs[1] = 64UL;  cs[2] = 3UL; cs[3] = 1UL; cs[4] = 56UL;
    cs[5] = 128UL; cs[6] = 128UL; cs[7] = 3UL; cs[8] = 1UL; cs[9] = 28UL;
    cs[10] = 256UL; cs[11] = 256UL; cs[12] = 3UL; cs[13] = 1UL; cs[14] = 14UL;
    cs[15] = 256UL; cs[16] = 64UL; cs[17] = 1UL; cs[18] = 0UL; cs[19] = 56UL;
    int c = 0;
    while (c < 4) {
        struct Conv2D layer = std::conv2d_new(cs[c * 5], cs[c * 5 + 1], cs[c * 5 + 2], cs[c * 5 + 2], 1UL, cs[c * 5 + 3]);
        g_conv = std::cuda_half_conv2d_upload(&layer, CUDA_BF16);
        g_p[0] = 32UL; g_p[2] = cs[c * 5 + 4]; g_p[3] = cs[c * 5 + 4];
        double us = timeit(8, 50);
        double hw = (double)cs[c * 5 + 4];
        double fl = 2.0 * 32.0 * hw * hw * (double)(cs[c * 5] * cs[c * 5 + 1] * cs[c * 5 + 2] * cs[c * 5 + 2]);
        unsafe { printf("[half conv+relu %lu->%lu k%lu %lux%lu b32] us=%.1f tflops=%.1f\n", cs[c * 5], cs[c * 5 + 1],
                        cs[c * 5 + 2], cs[c * 5 + 4], cs[c * 5 + 4], us, fl / (us * 1.0e6)); }
        std::cuda_half_conv2d_free(&g_conv);
        std::conv2d_free(&layer);
        c = c + 1;
    }
    return 0;
}
