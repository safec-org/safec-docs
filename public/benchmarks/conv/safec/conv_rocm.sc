// Convolution on an AMD GPU with std/ml/gpu_miopen.h (MIOpen on
// gpu_rocm's stream). Same layer and network as conv_cpu.sc and
// ../pytorch/conv.py:
//   conv64   Conv2D 64 -> 64, 3x3, stride 1, pad 1, 56 x 56, + bias;
//            batch 1 and batch 32
//   unet128  unet_new(3, 32, 64, 3) forward at 128 x 128 (MIOpen conv and
//            max pool; std's own bias + ReLU and upsample kernels);
//            batch 1, batch 1 replayed as one HIP graph, batch 16
// Inputs and weights stay on the GPU; each row is the mean over many
// queued calls, timed between two stream syncs. fp32 throughout.
//
// Build: safec conv_rocm.sc -I <SafeC> -O2 -o conv_rocm.ll
//        clang -O2 conv_rocm.ll libsafec_std.a -L/opt/rocm/lib \
//              -lMIOpen -lamdhip64 -lhiprtc -lrocblas -lm -lpthread -o conv_rocm
extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
#include <std/ml/gpu_miopen.h>
#include <std/ml/gpu_rocm.h>
#include <std/ml/cnn.h>
#include <std/ml/unet.h>
#include <std/collections/vec.h>
#include <std/time.h>

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

static struct RocmBuf random_buf(unsigned long n) {
    struct Vec<float> v = std::Vec::with_cap(n);
    unsigned long i = 0UL;
    while (i < n) { unsafe { v.push((float)(drand48() * 2.0 - 1.0)); } i = i + 1UL; }
    struct RocmBuf b = RocmBuf::upload(v.at(0UL), n);
    v.free();
    return b;
}

static double time_conv(const &RocmConv2D d, struct RocmBuf x, struct RocmBuf y, unsigned long batch, int iters) {
    int i = 0;
    while (i < 5) { d.forward(x, y, batch, 56UL, 56UL); i = i + 1; }
    std::rocm_sync();
    double t0 = now_ms();
    i = 0;
    while (i < iters) { d.forward(x, y, batch, 56UL, 56UL); i = i + 1; }
    std::rocm_sync();
    return (now_ms() - t0) / (double)iters;
}

static double time_unet(const &RocmUNet g, struct RocmBuf x, struct RocmBuf y, int iters) {
    int i = 0;
    while (i < 5) { g.forward(x, y); i = i + 1; }
    std::rocm_sync();
    double t0 = now_ms();
    i = 0;
    while (i < iters) { g.forward(x, y); i = i + 1; }
    std::rocm_sync();
    return (now_ms() - t0) / (double)iters;
}

int main() {
    if (!std::rocm_available()) { unsafe { printf("no ROCm GPU\n"); } return 1; }
    unsafe { srand48(42L); }
    struct Conv2D layer = Conv2D::new(64UL, 64UL, 3UL, 3UL, 1UL, 1UL);
    struct RocmConv2D d = RocmConv2D::upload(&layer);
    struct UNet net = UNet::new(3UL, 32UL, 64UL, 3UL);
    const char* mode = "fp32";
    {
        unsigned long batches[2];
        batches[0] = 1UL;
        batches[1] = 32UL;
        int bi = 0;
        while (bi < 2) {
            unsigned long B;
            unsafe { B = batches[bi]; }
            struct RocmBuf x = random_buf(B * 64UL * 56UL * 56UL);
            struct RocmBuf y = RocmBuf::alloc(B * 64UL * 56UL * 56UL);
            double ms = time_conv(&d, x, y, B, B == 1UL ? 2000 : 300);
            unsafe { printf("[conv64 b=%lu %s] ms_per_batch=%.4f ms_per_image=%.5f\n", B, mode, ms, ms / (double)B); }
            x.free();
            y.free();
            bi = bi + 1;
        }

        // U-Net, batch 1: queued ops, then the same forward as one graph
        struct RocmUNet g = RocmUNet::new(&net, 1UL, 128UL, 128UL);
        struct RocmBuf ux = random_buf(3UL * 128UL * 128UL);
        struct RocmBuf uy = RocmBuf::alloc(3UL * 128UL * 128UL);
        double ms = time_unet(&g, ux, uy, 1000);
        unsafe { printf("[unet128 b=1 %s] ms_per_batch=%.4f ms_per_image=%.5f\n", mode, ms, ms); }
        std::rocm_capture_begin();
        g.forward(ux, uy);
        struct RocmGraph graph = std::rocm_capture_end();
        int i = 0;
        while (i < 5) { std::rocm_graph_launch(graph); i = i + 1; }
        std::rocm_sync();
        double t0 = now_ms();
        i = 0;
        while (i < 1000) { std::rocm_graph_launch(graph); i = i + 1; }
        std::rocm_sync();
        ms = (now_ms() - t0) / 1000.0;
        unsafe { printf("[unet128 b=1 graph %s] ms_per_batch=%.4f ms_per_image=%.5f\n", mode, ms, ms); }
        graph.free();
        g.free();
        ux.free();
        uy.free();

        // U-Net, batch 16
        g = RocmUNet::new(&net, 16UL, 128UL, 128UL);
        ux = random_buf(16UL * 3UL * 128UL * 128UL);
        uy = RocmBuf::alloc(16UL * 3UL * 128UL * 128UL);
        ms = time_unet(&g, ux, uy, 300);
        unsafe { printf("[unet128 b=16 %s] ms_per_batch=%.4f ms_per_image=%.5f\n", mode, ms, ms / 16.0); }
        g.free();
        ux.free();
        uy.free();
    }
    net.free();
    d.free();
    layer.free();
    return 0;
}
