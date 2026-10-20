// 2-layer MLP training + inference on an NVIDIA GPU with std::ml's CUDA
// backend (gpu_cuda.h): weights stay resident on the GPU, matmuls run on
// cuBLAS, ReLU / ReLU-grad / fused MSE-grad / SGD run std's own CUDA
// kernels. Same model, data and loop as ../pytorch/train.py and
// train_rocm.sc: the loss is read back every step and Y[0][0] every
// inference pass, like .item().
//
// Each shape runs in three modes:
//   plain  every op launched individually, same op list as train_rocm.sc
//   fused  GEMM + ReLU in one cuBLASLt call (cuda_gemm_relu), both SGD
//          updates in one kernel (cuda_sgd2): 7 launches per step instead of 10
//   graph  the fused step / forward pass recorded into a CudaGraph and
//          replayed with a single launch
//   tf32   graph, with cuBLAS allowed to use TF32 tensor cores
//          (cuda_set_tf32) -- what PyTorch does when TF32 is enabled, as
//          it is by default in NVIDIA's NGC PyTorch containers
//   fp16 / bf16  the fused step as a graph with every tensor (data,
//          weights, activations, gradients) in 16-bit storage
//          (CudaHalfBuf): GEMMs on tensor cores with fp32 accumulation,
//          elementwise math in fp32, one rounding per stored value; the
//          loss stays fp32. ../pytorch/train.py's fp16/bf16 rows cast the
//          same model and data with .to(dtype).
//
// Build: safec train_cuda.sc -I <SafeC> -O2 -o train_cuda.ll
//        clang -O2 train_cuda.ll libsafec_std.a -L/usr/local/cuda/lib64 \
//              -lcuda -lcublas -lcublasLt -lnvrtc -lm -lpthread -o train_cuda
extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
#include <std/ml/gpu_cuda.h>
#include <std/ml/float16.h>
#include <std/collections/vec.h>
#include <std/time.h>

#define MODE_PLAIN 0
#define MODE_FUSED 1
#define MODE_GRAPH 2
#define MODE_TF32 3
#define MODE_FP16 4
#define MODE_BF16 5

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

static struct Vec<float> random_vec(unsigned long n, double scale) {
    struct Vec<float> v = std::vec_with_cap(n);
    unsigned long i = 0UL;
    while (i < n) { unsafe { v.push((float)((drand48() - 0.5) * scale)); } i = i + 1UL; }
    return v;
}

struct Mlp {
    unsigned long B; unsigned long I; unsigned long H; unsigned long O;
    struct CudaBuf X; struct CudaBuf T; struct CudaBuf W1; struct CudaBuf W2;
    struct CudaBuf Z1; struct CudaBuf Hh; struct CudaBuf Y; struct CudaBuf dY;
    struct CudaBuf dW2; struct CudaBuf dH; struct CudaBuf dZ1; struct CudaBuf dW1;
    struct CudaBuf loss;
};

static struct Mlp mlp_new(unsigned long B, unsigned long I, unsigned long H, unsigned long O) {
    struct Mlp m;
    m.B = B; m.I = I; m.H = H; m.O = O;
    unsafe { srand48(42L); }
    struct Vec<float> x = random_vec(B * I, 1.0);
    struct Vec<float> t = random_vec(B * O, 1.0);
    struct Vec<float> w1 = random_vec(I * H, 0.1);
    struct Vec<float> w2 = random_vec(H * O, 0.1);
    m.X = std::cuda_upload(x.at(0UL), B * I);
    m.T = std::cuda_upload(t.at(0UL), B * O);
    m.W1 = std::cuda_upload(w1.at(0UL), I * H);
    m.W2 = std::cuda_upload(w2.at(0UL), H * O);
    x.free(); t.free(); w1.free(); w2.free();
    m.Z1 = std::cuda_alloc(B * H);  m.Hh = std::cuda_alloc(B * H);
    m.Y = std::cuda_alloc(B * O);   m.dY = std::cuda_alloc(B * O);
    m.dW2 = std::cuda_alloc(H * O); m.dH = std::cuda_alloc(B * H);
    m.dZ1 = std::cuda_alloc(B * H); m.dW1 = std::cuda_alloc(I * H);
    m.loss = std::cuda_alloc(1UL);
    return m;
}

static void mlp_free(&Mlp m) {
    std::cuda_free(&m.X); std::cuda_free(&m.T); std::cuda_free(&m.W1); std::cuda_free(&m.W2);
    std::cuda_free(&m.Z1); std::cuda_free(&m.Hh); std::cuda_free(&m.Y); std::cuda_free(&m.dY);
    std::cuda_free(&m.dW2); std::cuda_free(&m.dH); std::cuda_free(&m.dZ1); std::cuda_free(&m.dW1);
    std::cuda_free(&m.loss);
}

static void forward(&Mlp m, int fused) {
    if (fused) {
        std::cuda_gemm_relu(m.X, 0, m.W1, 0, m.Hh, m.B, m.H, m.I);  // H = relu(X W1)
    } else {
        std::cuda_gemm(m.X, 0, m.W1, 0, m.Z1, m.B, m.H, m.I);       // Z1 = X W1
        std::cuda_relu(m.Z1, m.Hh);                                 // H  = relu(Z1)
    }
    std::cuda_gemm(m.Hh, 0, m.W2, 0, m.Y, m.B, m.O, m.H);           // Y  = H W2
}

// One SGD step on loss = sum((Y - T)^2), hand-derived backward.
static void train_step(&Mlp m, float lr, int fused) {
    forward(m, fused);
    std::cuda_mse_grad(m.Y, m.T, m.dY, m.loss);                     // dY = 2 (Y - T), loss
    std::cuda_gemm(m.Hh, 1, m.dY, 0, m.dW2, m.H, m.O, m.B);         // dW2 = H^T dY
    std::cuda_gemm(m.dY, 0, m.W2, 1, m.dH, m.B, m.H, m.O);          // dH  = dY W2^T
    // dZ1 = relu'(Z1) * dH; H > 0 exactly where Z1 > 0, so the fused path
    // never materializes Z1.
    std::cuda_relu_grad(fused ? m.Hh : m.Z1, m.dH, m.dZ1);
    std::cuda_gemm(m.X, 1, m.dZ1, 0, m.dW1, m.I, m.H, m.B);         // dW1 = X^T dZ1
    if (fused) {
        std::cuda_sgd2(m.W2, m.dW2, m.W1, m.dW1, lr);
    } else {
        std::cuda_sgd(m.W2, m.dW2, lr);
        std::cuda_sgd(m.W1, m.dW1, lr);
    }
}

static void run(const char* label, unsigned long B, unsigned long I, unsigned long H, unsigned long O,
                unsigned long steps, unsigned long passes, int mode) {
    struct Mlp m = mlp_new(B, I, H, O);
    float lr = (float)0.001;
    float lossHost[1];
    float yHost[1];
    int fused = mode != MODE_PLAIN;
    int graph = mode >= MODE_GRAPH;
    std::cuda_set_tf32(mode == MODE_TF32);
    struct CudaGraph stepGraph;
    struct CudaGraph fwdGraph;
    stepGraph.exec = 0UL;
    fwdGraph.exec = 0UL;
    if (graph) {
        // Record one training step and one forward pass. Recording runs
        // nothing, so the weights are untouched. One eager step first so
        // cuBLASLt's plan for this shape exists before capture.
        forward(&m, 1);
        std::cuda_sync();
        std::cuda_capture_begin();
        train_step(&m, lr, 1);
        stepGraph = std::cuda_capture_end();
        std::cuda_capture_begin();
        forward(&m, 1);
        fwdGraph = std::cuda_capture_end();
    }
    std::cuda_sync();

    double t0 = now_ms();
    unsigned long s = 0UL;
    while (s < steps) {
        if (graph) std::cuda_graph_launch(stepGraph);
        else train_step(&m, lr, fused);
        unsafe { std::cuda_read(m.loss, &lossHost[0], 1UL); }
        s = s + 1UL;
    }
    double t1 = now_ms();

    double checksum = 0.0;
    unsigned long p = 0UL;
    while (p < passes) {
        if (graph) std::cuda_graph_launch(fwdGraph);
        else forward(&m, fused);
        unsafe {
            std::cuda_read(m.Y, &yHost[0], 1UL);
            checksum = checksum + (double)yHost[0];
        }
        p = p + 1UL;
    }
    double t2 = now_ms();

    const char* modeName = mode == MODE_TF32 ? "tf32" : (mode == MODE_GRAPH ? "graph" : (mode == MODE_FUSED ? "fused" : "plain"));
    unsafe {
        printf("[%s %s] train_ms=%.3f last_loss=%.6f throughput_samples_per_sec=%.2f inference_ms_per_%lu=%.3f checksum=%.6f\n",
               label, modeName, t1 - t0, (double)lossHost[0],
               (double)(steps * B) / ((t1 - t0) / 1000.0), passes, t2 - t1, checksum);
    }
    std::cuda_graph_free(&stepGraph);
    std::cuda_graph_free(&fwdGraph);
    mlp_free(&m);
}

// ── 16-bit storage ───────────────────────────────────────────────────────────

struct HalfMlp {
    unsigned long B; unsigned long I; unsigned long H; unsigned long O;
    struct CudaHalfBuf X; struct CudaHalfBuf T; struct CudaHalfBuf W1; struct CudaHalfBuf W2;
    struct CudaHalfBuf Hh; struct CudaHalfBuf Y; struct CudaHalfBuf dY;
    struct CudaHalfBuf dW2; struct CudaHalfBuf dH; struct CudaHalfBuf dZ1; struct CudaHalfBuf dW1;
    struct CudaBuf loss;
};

static struct HalfMlp half_new(unsigned long B, unsigned long I, unsigned long H, unsigned long O, int dt) {
    struct HalfMlp m;
    m.B = B; m.I = I; m.H = H; m.O = O;
    unsafe { srand48(42L); }
    struct Vec<float> x = random_vec(B * I, 1.0);
    struct Vec<float> t = random_vec(B * O, 1.0);
    struct Vec<float> w1 = random_vec(I * H, 0.1);
    struct Vec<float> w2 = random_vec(H * O, 0.1);
    m.X = std::cuda_half_upload(x.at(0UL), B * I, dt);
    m.T = std::cuda_half_upload(t.at(0UL), B * O, dt);
    m.W1 = std::cuda_half_upload(w1.at(0UL), I * H, dt);
    m.W2 = std::cuda_half_upload(w2.at(0UL), H * O, dt);
    x.free(); t.free(); w1.free(); w2.free();
    m.Hh = std::cuda_half_alloc(B * H, dt);  m.Y = std::cuda_half_alloc(B * O, dt);
    m.dY = std::cuda_half_alloc(B * O, dt);  m.dW2 = std::cuda_half_alloc(H * O, dt);
    m.dH = std::cuda_half_alloc(B * H, dt);  m.dZ1 = std::cuda_half_alloc(B * H, dt);
    m.dW1 = std::cuda_half_alloc(I * H, dt);
    m.loss = std::cuda_alloc(1UL);
    return m;
}

static void half_free(&HalfMlp m) {
    std::cuda_half_free(&m.X); std::cuda_half_free(&m.T); std::cuda_half_free(&m.W1); std::cuda_half_free(&m.W2);
    std::cuda_half_free(&m.Hh); std::cuda_half_free(&m.Y); std::cuda_half_free(&m.dY);
    std::cuda_half_free(&m.dW2); std::cuda_half_free(&m.dH); std::cuda_half_free(&m.dZ1);
    std::cuda_half_free(&m.dW1); std::cuda_free(&m.loss);
}

static void half_forward(&HalfMlp m) {
    std::cuda_half_gemm_relu(m.X, 0, m.W1, 0, m.Hh, m.B, m.H, m.I);   // H = relu(X W1)
    std::cuda_half_gemm(m.Hh, 0, m.W2, 0, m.Y, m.B, m.O, m.H);        // Y = H W2
}

static void half_step(&HalfMlp m, float lr) {
    half_forward(m);
    std::cuda_half_mse_grad(m.Y, m.T, m.dY, m.loss);
    std::cuda_half_gemm(m.Hh, 1, m.dY, 0, m.dW2, m.H, m.O, m.B);
    std::cuda_half_gemm(m.dY, 0, m.W2, 1, m.dH, m.B, m.H, m.O);
    std::cuda_half_relu_grad(m.Hh, m.dH, m.dZ1);
    std::cuda_half_gemm(m.X, 1, m.dZ1, 0, m.dW1, m.I, m.H, m.B);
    std::cuda_half_sgd2(m.W2, m.dW2, m.W1, m.dW1, lr);
}

static void run_half(const char* label, unsigned long B, unsigned long I, unsigned long H, unsigned long O,
                     unsigned long steps, unsigned long passes, int mode) {
    int dt = mode == MODE_BF16 ? CUDA_BF16 : CUDA_FP16;
    struct HalfMlp m = half_new(B, I, H, O, dt);
    float lr = (float)0.001;
    float lossHost[1];
    unsigned short yBits[1];
    std::cuda_set_tf32(0);
    half_forward(&m);   // cuBLASLt plan before capture
    std::cuda_sync();
    std::cuda_capture_begin();
    half_step(&m, lr);
    struct CudaGraph stepGraph = std::cuda_capture_end();
    std::cuda_capture_begin();
    half_forward(&m);
    struct CudaGraph fwdGraph = std::cuda_capture_end();
    std::cuda_sync();

    double t0 = now_ms();
    unsigned long s = 0UL;
    while (s < steps) {
        std::cuda_graph_launch(stepGraph);
        unsafe { std::cuda_read(m.loss, &lossHost[0], 1UL); }
        s = s + 1UL;
    }
    double t1 = now_ms();
    double checksum = 0.0;
    unsigned long p = 0UL;
    while (p < passes) {
        std::cuda_graph_launch(fwdGraph);
        unsafe {
            std::cuda_half_read(m.Y, &yBits[0], 1UL);
            float y0 = dt == CUDA_BF16 ? std::bf16_to_f32(yBits[0]) : std::fp16_to_f32(yBits[0]);
            checksum = checksum + (double)y0;
        }
        p = p + 1UL;
    }
    double t2 = now_ms();
    unsafe {
        printf("[%s %s] train_ms=%.3f last_loss=%.6f throughput_samples_per_sec=%.2f inference_ms_per_%lu=%.3f checksum=%.6f\n",
               label, dt == CUDA_BF16 ? "bf16" : "fp16", t1 - t0, (double)lossHost[0],
               (double)(steps * B) / ((t1 - t0) / 1000.0), passes, t2 - t1, checksum);
    }
    std::cuda_graph_free(&stepGraph);
    std::cuda_graph_free(&fwdGraph);
    half_free(&m);
}

int main() {
    if (!std::cuda_available()) { unsafe { printf("no CUDA GPU -- skipping\n"); } return 0; }
    unsafe { printf("device: %s\n", std::cuda_device_name()); }
    // Warm-up (not reported): cuBLAS picks and loads each shape's kernels
    // on first use, and an idle GPU takes a moment to reach full clocks.
    int w = 0;
    while (w < 6) {
        if (w < MODE_FP16) {
            run("warmup", 64UL, 128UL, 256UL, 64UL, 200UL, 200UL, w);
            run("warmup", 128UL, 512UL, 1024UL, 256UL, 200UL, 200UL, w);
        } else {
            run_half("warmup", 64UL, 128UL, 256UL, 64UL, 200UL, 200UL, w);
            run_half("warmup", 128UL, 512UL, 1024UL, 256UL, 200UL, 200UL, w);
        }
        w = w + 1;
    }
    int mode = 0;
    while (mode < 6) {
        if (mode < MODE_FP16) {
            run("small 128-256-64", 64UL, 128UL, 256UL, 64UL, 100UL, 1000UL, mode);
            run("big 512-1024-256", 128UL, 512UL, 1024UL, 256UL, 50UL, 200UL, mode);
        } else {
            run_half("small 128-256-64", 64UL, 128UL, 256UL, 64UL, 100UL, 1000UL, mode);
            run_half("big 512-1024-256", 128UL, 512UL, 1024UL, 256UL, 50UL, 200UL, mode);
        }
        mode = mode + 1;
    }
    return 0;
}
