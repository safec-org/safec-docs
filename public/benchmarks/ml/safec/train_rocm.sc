// 2-layer MLP training + inference on an AMD GPU with std::ml's ROCm
// backend (gpu_rocm.h): weights stay resident on the GPU, matmuls run on
// rocBLAS, ReLU / ReLU-grad / fused MSE-grad / SGD run std's own HIP
// kernels. Same model, data and loop as ../pytorch/train.py: the loss is
// read back every step and Y[0][0] every inference pass, like .item().
//
// Each shape runs twice: plain (every op launched individually) and graph
// (one training step / one forward pass recorded into a RocmGraph and
// replayed with a single launch).
//
// Build: safec train_rocm.sc -I <SafeC> -O2 -o train_rocm.ll
//        clang -O2 train_rocm.ll libsafec_std.a -L/opt/rocm/lib \
//              -lamdhip64 -lhiprtc -lrocblas -lm -lpthread -o train_rocm
extern int printf(const char* fmt, ...);
extern double drand48();
extern void srand48(long seed);
#include <std/ml/gpu_rocm.h>
#include <std/collections/vec.h>
#include <std/time.h>

static double now_ms() { return std::time_ns_to_ms(std::time_mono_ns()); }

static struct Vec<float> random_vec(unsigned long n, double scale) {
    struct Vec<float> v = std::vec_with_cap(n);
    unsigned long i = 0UL;
    while (i < n) { unsafe { v.push((float)((drand48() - 0.5) * scale)); } i = i + 1UL; }
    return v;
}

struct Mlp {
    unsigned long B; unsigned long I; unsigned long H; unsigned long O;
    struct RocmBuf X; struct RocmBuf T; struct RocmBuf W1; struct RocmBuf W2;
    struct RocmBuf Z1; struct RocmBuf Hh; struct RocmBuf Y; struct RocmBuf dY;
    struct RocmBuf dW2; struct RocmBuf dH; struct RocmBuf dZ1; struct RocmBuf dW1;
    struct RocmBuf loss;
};

static struct Mlp mlp_new(unsigned long B, unsigned long I, unsigned long H, unsigned long O) {
    struct Mlp m;
    m.B = B; m.I = I; m.H = H; m.O = O;
    unsafe { srand48(42L); }
    struct Vec<float> x = random_vec(B * I, 1.0);
    struct Vec<float> t = random_vec(B * O, 1.0);
    struct Vec<float> w1 = random_vec(I * H, 0.1);
    struct Vec<float> w2 = random_vec(H * O, 0.1);
    m.X = std::rocm_upload(x.at(0UL), B * I);
    m.T = std::rocm_upload(t.at(0UL), B * O);
    m.W1 = std::rocm_upload(w1.at(0UL), I * H);
    m.W2 = std::rocm_upload(w2.at(0UL), H * O);
    x.free(); t.free(); w1.free(); w2.free();
    m.Z1 = std::rocm_alloc(B * H);  m.Hh = std::rocm_alloc(B * H);
    m.Y = std::rocm_alloc(B * O);   m.dY = std::rocm_alloc(B * O);
    m.dW2 = std::rocm_alloc(H * O); m.dH = std::rocm_alloc(B * H);
    m.dZ1 = std::rocm_alloc(B * H); m.dW1 = std::rocm_alloc(I * H);
    m.loss = std::rocm_alloc(1UL);
    return m;
}

static void mlp_free(&Mlp m) {
    std::rocm_free(&m.X); std::rocm_free(&m.T); std::rocm_free(&m.W1); std::rocm_free(&m.W2);
    std::rocm_free(&m.Z1); std::rocm_free(&m.Hh); std::rocm_free(&m.Y); std::rocm_free(&m.dY);
    std::rocm_free(&m.dW2); std::rocm_free(&m.dH); std::rocm_free(&m.dZ1); std::rocm_free(&m.dW1);
    std::rocm_free(&m.loss);
}

static void forward(&Mlp m) {
    std::rocm_gemm(m.X, 0, m.W1, 0, m.Z1, m.B, m.H, m.I);    // Z1 = X W1
    std::rocm_relu(m.Z1, m.Hh);                              // H  = relu(Z1)
    std::rocm_gemm(m.Hh, 0, m.W2, 0, m.Y, m.B, m.O, m.H);    // Y  = H W2
}

// One SGD step on loss = sum((Y - T)^2), hand-derived backward.
static void train_step(&Mlp m, float lr) {
    forward(m);
    std::rocm_mse_grad(m.Y, m.T, m.dY, m.loss);              // dY = 2 (Y - T), loss
    std::rocm_gemm(m.Hh, 1, m.dY, 0, m.dW2, m.H, m.O, m.B);  // dW2 = H^T dY
    std::rocm_gemm(m.dY, 0, m.W2, 1, m.dH, m.B, m.H, m.O);   // dH  = dY W2^T
    std::rocm_relu_grad(m.Z1, m.dH, m.dZ1);                  // dZ1 = relu'(Z1) * dH
    std::rocm_gemm(m.X, 1, m.dZ1, 0, m.dW1, m.I, m.H, m.B);  // dW1 = X^T dZ1
    std::rocm_sgd(m.W2, m.dW2, lr);
    std::rocm_sgd(m.W1, m.dW1, lr);
}

static void run(const char* label, unsigned long B, unsigned long I, unsigned long H, unsigned long O,
                unsigned long steps, unsigned long passes, int useGraph) {
    struct Mlp m = mlp_new(B, I, H, O);
    float lr = (float)0.001;
    float lossHost[1];
    float yHost[1];
    struct RocmGraph stepGraph;
    struct RocmGraph fwdGraph;
    stepGraph.exec = 0UL;
    fwdGraph.exec = 0UL;
    if (useGraph) {
        // Record one training step and one forward pass. Recording runs
        // nothing, so the weights are untouched.
        std::rocm_capture_begin();
        train_step(&m, lr);
        stepGraph = std::rocm_capture_end();
        std::rocm_capture_begin();
        forward(&m);
        fwdGraph = std::rocm_capture_end();
    }
    std::rocm_sync();

    double t0 = now_ms();
    unsigned long s = 0UL;
    while (s < steps) {
        if (useGraph) std::rocm_graph_launch(stepGraph);
        else train_step(&m, lr);
        unsafe { std::rocm_read(m.loss, &lossHost[0], 1UL); }
        s = s + 1UL;
    }
    double t1 = now_ms();

    double checksum = 0.0;
    unsigned long p = 0UL;
    while (p < passes) {
        if (useGraph) std::rocm_graph_launch(fwdGraph);
        else forward(&m);
        unsafe {
            std::rocm_read(m.Y, &yHost[0], 1UL);
            checksum = checksum + (double)yHost[0];
        }
        p = p + 1UL;
    }
    double t2 = now_ms();

    unsafe {
        printf("[%s %s] train_ms=%.3f last_loss=%.6f throughput_samples_per_sec=%.2f inference_ms_per_%lu=%.3f checksum=%.6f\n",
               label, useGraph ? "graph" : "plain", t1 - t0, (double)lossHost[0],
               (double)(steps * B) / ((t1 - t0) / 1000.0), passes, t2 - t1, checksum);
    }
    std::rocm_graph_free(&stepGraph);
    std::rocm_graph_free(&fwdGraph);
    mlp_free(&m);
}

int main() {
    if (!std::rocm_available()) { unsafe { printf("no ROCm GPU -- skipping\n"); } return 0; }
    unsafe { printf("device: %s\n", std::rocm_device_name()); }
    // Warm-up (not reported): rocBLAS loads each shape's kernels lazily on
    // first use, and an idle GPU takes a moment to reach full clocks.
    run("warmup", 64UL, 128UL, 256UL, 64UL, 200UL, 200UL, 0);
    run("warmup", 128UL, 512UL, 1024UL, 256UL, 200UL, 200UL, 0);
    run("warmup", 64UL, 128UL, 256UL, 64UL, 200UL, 200UL, 1);
    int g = 0;
    while (g < 2) {
        run("small 128-256-64", 64UL, 128UL, 256UL, 64UL, 100UL, 1000UL, g);
        run("big 512-1024-256", 128UL, 512UL, 1024UL, 256UL, 50UL, 200UL, g);
        g = g + 1;
    }
    return 0;
}
