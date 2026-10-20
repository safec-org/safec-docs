// Convolution on an NVIDIA GPU with std/ml/gpu_cudnn.h (cuDNN on
// gpu_cuda's stream). Same layer and network as conv_cpu.sc and
// ../pytorch/conv.py:
//   conv64   Conv2D 64 -> 64, 3x3, stride 1, pad 1, 56 x 56, + bias;
//            batch 1 and batch 32
//   unet128  unet_new(3, 32, 64, 3) forward at 128 x 128 (cuDNN conv,
//            fused conv + bias + ReLU, max pool; std's own upsample
//            kernel); batch 1, batch 1 replayed as one CUDA graph, batch 16
// Inputs and weights stay on the GPU; each row is the mean over many
// queued calls, timed between two stream syncs. Every row runs in fp32
// and again with TF32 tensor cores allowed (cuda_set_tf32).
//
// Build: safec conv_cuda.sc -I <SafeC> -O2 -o conv_cuda.ll
//        clang -O2 conv_cuda.ll libsafec_std.a -L/usr/local/cuda/lib64 \
//              -lcuda -lcublas -lcublasLt -lnvrtc -lcudnn -lm -lpthread -o conv_cuda
extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
#include <std/ml/gpu_cudnn.h>
#include <std/ml/gpu_cuda.h>
#include <std/ml/cnn.h>
#include <std/ml/unet.h>
#include <std/collections/vec.h>
#include <std/time.h>

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

static struct CudaBuf random_buf(unsigned long n) {
    struct Vec<float> v = std::vec_with_cap(n);
    unsigned long i = 0UL;
    while (i < n) { unsafe { v.push((float)(drand48() * 2.0 - 1.0)); } i = i + 1UL; }
    struct CudaBuf b = std::cuda_upload(v.at(0UL), n);
    v.free();
    return b;
}

static double time_conv(const &CudaConv2D d, struct CudaBuf x, struct CudaBuf y, unsigned long batch, int iters) {
    int i = 0;
    while (i < 5) { std::cuda_conv2d(d, x, y, batch, 56UL, 56UL); i = i + 1; }
    std::cuda_sync();
    double t0 = now_ms();
    i = 0;
    while (i < iters) { std::cuda_conv2d(d, x, y, batch, 56UL, 56UL); i = i + 1; }
    std::cuda_sync();
    return (now_ms() - t0) / (double)iters;
}

static double time_unet(const &CudaUNet g, struct CudaBuf x, struct CudaBuf y, int iters) {
    int i = 0;
    while (i < 5) { std::cuda_unet_forward(g, x, y); i = i + 1; }
    std::cuda_sync();
    double t0 = now_ms();
    i = 0;
    while (i < iters) { std::cuda_unet_forward(g, x, y); i = i + 1; }
    std::cuda_sync();
    return (now_ms() - t0) / (double)iters;
}

int main() {
    if (!std::cuda_available()) { unsafe { printf("no CUDA GPU\n"); } return 1; }
    unsafe { srand48(42L); }
    struct Conv2D layer = std::conv2d_new(64UL, 64UL, 3UL, 3UL, 1UL, 1UL);
    struct CudaConv2D d = std::cuda_conv2d_upload(&layer);
    struct UNet net = std::unet_new(3UL, 32UL, 64UL, 3UL);
    const char* modes[2];
    modes[0] = "fp32";
    modes[1] = "tf32";
    int tf32 = 0;
    while (tf32 < 2) {
        std::cuda_set_tf32(tf32);
        const char* mode;
        unsafe { mode = modes[tf32]; }
        unsigned long batches[2];
        batches[0] = 1UL;
        batches[1] = 32UL;
        int bi = 0;
        while (bi < 2) {
            unsigned long B;
            unsafe { B = batches[bi]; }
            struct CudaBuf x = random_buf(B * 64UL * 56UL * 56UL);
            struct CudaBuf y = std::cuda_alloc(B * 64UL * 56UL * 56UL);
            double ms = time_conv(&d, x, y, B, B == 1UL ? 2000 : 300);
            unsafe { printf("[conv64 b=%lu %s] ms_per_batch=%.4f ms_per_image=%.5f\n", B, mode, ms, ms / (double)B); }
            std::cuda_free(&x);
            std::cuda_free(&y);
            bi = bi + 1;
        }

        // U-Net, batch 1: queued ops, then the same forward as one graph
        struct CudaUNet g = std::cuda_unet_new(&net, 1UL, 128UL, 128UL);
        struct CudaBuf ux = random_buf(3UL * 128UL * 128UL);
        struct CudaBuf uy = std::cuda_alloc(3UL * 128UL * 128UL);
        double ms = time_unet(&g, ux, uy, 1000);
        unsafe { printf("[unet128 b=1 %s] ms_per_batch=%.4f ms_per_image=%.5f\n", mode, ms, ms); }
        std::cuda_capture_begin();
        std::cuda_unet_forward(&g, ux, uy);
        struct CudaGraph graph = std::cuda_capture_end();
        int i = 0;
        while (i < 5) { std::cuda_graph_launch(graph); i = i + 1; }
        std::cuda_sync();
        double t0 = now_ms();
        i = 0;
        while (i < 1000) { std::cuda_graph_launch(graph); i = i + 1; }
        std::cuda_sync();
        ms = (now_ms() - t0) / 1000.0;
        unsafe { printf("[unet128 b=1 graph %s] ms_per_batch=%.4f ms_per_image=%.5f\n", mode, ms, ms); }
        std::cuda_graph_free(&graph);
        std::cuda_unet_free(&g);
        std::cuda_free(&ux);
        std::cuda_free(&uy);

        // U-Net, batch 16
        g = std::cuda_unet_new(&net, 16UL, 128UL, 128UL);
        ux = random_buf(16UL * 3UL * 128UL * 128UL);
        uy = std::cuda_alloc(16UL * 3UL * 128UL * 128UL);
        ms = time_unet(&g, ux, uy, 300);
        unsafe { printf("[unet128 b=16 %s] ms_per_batch=%.4f ms_per_image=%.5f\n", mode, ms, ms / 16.0); }
        std::cuda_unet_free(&g);
        std::cuda_free(&ux);
        std::cuda_free(&uy);
        tf32 = tf32 + 1;
    }
    std::cuda_set_tf32(0);
    std::unet_free(&net);
    std::cuda_conv2d_free(&d);
    std::conv2d_free(&layer);
    return 0;
}
