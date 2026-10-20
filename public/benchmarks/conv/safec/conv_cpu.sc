// Convolution on the CPU with std/ml/cnn.h and unet.h, one image at a time
// (im2col + std/simd/gemm.h's SIMD GEMM on std/sync/parallel.h's thread
// pool). Same layer and network as conv_cuda.sc and
// ../pytorch/conv.py:
//   conv64  Conv2D 64 -> 64, 3x3, stride 1, pad 1, on a 64 x 56 x 56 map
//           (a ResNet stage-1 layer), + bias
//   unet    unet_new(3, 32, 64, 3) forward on a 3 x 128 x 128 image
// Prints the mean time per image over a few images, after one warm-up call
// (which starts the thread pool; ../pytorch/conv.py warms up too). Each
// image's maps -- the output and, in the U-Net, every intermediate -- come
// from a TensorArena that is reset after the image, so no layer pays for a
// fresh multi-megabyte malloc (PyTorch's caching allocator does the same).
//
// Build: safec conv_cpu.sc -I <SafeC> -O2 -o conv_cpu.ll
//        clang -O2 conv_cpu.ll libsafec_std.a -lm -lpthread -o conv_cpu
extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
#include <std/ml/cnn.h>
#include <std/ml/unet.h>
#include <std/ml/tensor.h>
#include <std/time.h>

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

static struct FeatureMap random_map(unsigned long c, unsigned long h, unsigned long w) {
    struct FeatureMap fm = std::feature_map_new(c, h, w);
    unsigned long n = c * h * w;
    unsigned long i = 0UL;
    unsafe { while (i < n) { fm.data[i] = (float)(drand48() * 2.0 - 1.0); i = i + 1UL; } }
    return fm;
}

static double checksum(const &FeatureMap fm) {
    double s = 0.0;
    unsigned long n = fm.channels * fm.height * fm.width;
    unsigned long i = 0UL;
    unsafe { while (i < n) { s = s + (double)fm.data[i]; i = i + 1UL; } }
    return s;
}

int main() {
    unsafe { srand48(42L); }
    struct Conv2D layer = std::conv2d_new(64UL, 64UL, 3UL, 3UL, 1UL, 1UL);
    struct FeatureMap x = random_map(64UL, 56UL, 56UL);
    struct TensorArena imageArena = std::tensor_arena_new(0UL);
    std::tensor_arena_begin(&imageArena);
    struct FeatureMap warm = std::conv2d_forward(&layer, &x);
    std::tensor_arena_end();
    std::tensor_arena_reset(&imageArena);
    unsigned long images = 3UL;
    double sum = 0.0;
    double t0 = now_ms();
    unsigned long i = 0UL;
    while (i < images) {
        std::tensor_arena_begin(&imageArena);
        struct FeatureMap y = std::conv2d_forward(&layer, &x);
        sum = sum + checksum(&y);
        std::tensor_arena_end();
        std::tensor_arena_reset(&imageArena);
        i = i + 1UL;
    }
    double ms = (now_ms() - t0) / (double)images;
    unsafe { printf("[conv64 b=1 cpu] ms_per_image=%.3f checksum=%.4f\n", ms, sum / (double)images); }

    struct UNet net = std::unet_new(3UL, 32UL, 64UL, 3UL);
    struct FeatureMap img = random_map(3UL, 128UL, 128UL);
    std::tensor_arena_begin(&imageArena);
    struct FeatureMap uwarm = std::unet_forward(&net, &img);
    std::tensor_arena_end();
    std::tensor_arena_reset(&imageArena);
    images = 2UL;
    sum = 0.0;
    t0 = now_ms();
    i = 0UL;
    while (i < images) {
        std::tensor_arena_begin(&imageArena);
        struct FeatureMap y = std::unet_forward(&net, &img);
        sum = sum + checksum(&y);
        std::tensor_arena_end();
        std::tensor_arena_reset(&imageArena);
        i = i + 1UL;
    }
    ms = (now_ms() - t0) / (double)images;
    unsafe { printf("[unet128 b=1 cpu] ms_per_image=%.3f checksum=%.4f\n", ms, sum / (double)images); }

    std::tensor_arena_free(&imageArena);
    std::feature_map_free(&img);
    std::unet_free(&net);
    std::feature_map_free(&x);
    std::conv2d_free(&layer);
    return 0;
}
