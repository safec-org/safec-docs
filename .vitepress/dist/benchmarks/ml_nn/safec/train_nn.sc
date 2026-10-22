// std::ml training workloads from SafeC's test_ml_train / test_ml_finetune
// suites, timed on the CPU: one untimed warm-up run, then one timed run per
// workload (run_bench.py keeps the best of its runs). Prints
// "[workload] train_ms=... last_loss=..." per workload.
#include <std/ml/nn.h>
#include <std/ml/datasets.h>
#include <std/ml/trainer.h>
#include <std/ml/autoencoder.h>
#include <std/ml/gan.h>
#include <std/ml/rl.h>
#include <std/ml/lora.h>
#include <std/ml/convnet.h>
#include <std/time.h>
#include <std/io.h>

static float g_loss = (float)0.0;

static double now_ms() { return (double)time_mono_ns() * 1e-6; }

// ── MLP classifiers ──────────────────────────────────────────────────────────
static &FloatTensor clf_loss_(&Mlp m, const &FloatTensor x, const &FloatTensor y) {
    return tensor_cross_entropy_loss(m.forward(x), y);
}

static struct Dataset xor_data_(unsigned long n) {
    struct Rng rng = Rng::new(1UL);
    return Dataset::xor(n, (float)0.0, &rng);
}

static struct Dataset big_data_() {
    struct Rng rng = Rng::new(2UL);
    struct Dataset d = Dataset::blobs(2048UL, 10UL, 784UL, (float)1.0, &rng);
    return d;
}

static void run_mlp_(&Dataset d, unsigned long in, unsigned long hidden, unsigned long out,
                     float lr, unsigned long batch, unsigned long epochs) {
    struct Rng rng = Rng::new(3UL);
    struct Mlp net = Mlp::new(in, hidden, 2UL, out, ACT_RELU, ACT_NONE, &rng);
    struct Optimizer opt = Optimizer::new(OPT_ADAM, lr);
    model_add_params(&net, &opt);
    struct TrainConfig tc = TrainConfig::default();
    tc.epochs = epochs;
    tc.batchSize = batch;
    struct TrainReport rep = trainer_fit(&net, clf_loss_, &opt, d, &tc);
    g_loss = rep.lastEpochLoss;
    opt.free();
    net.free();
}

static struct Dataset g_xor;
static struct Dataset g_big;
static struct Dataset g_bits;
static struct Dataset g_gauss;
static struct Dataset g_img;

static void w_mlp_small() { run_mlp_(&g_xor, 2UL, 32UL, 2UL, (float)0.01, 32UL, 30UL); }
static void w_mlp_big() { run_mlp_(&g_big, 784UL, 256UL, 10UL, (float)0.001, 128UL, 3UL); }

// ── VAE ──────────────────────────────────────────────────────────────────────
static void w_vae() {
    struct VaeConfig cfg = VaeConfig::default(4UL);
    cfg.hidden = 128UL;
    struct Vae v = Vae::new(100UL, &cfg);
    struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.001);
    model_add_params(&v, &opt);
    struct TrainConfig tc = TrainConfig::default();
    tc.epochs = 10UL;
    struct TrainReport rep = model_fit(&v, &opt, &g_bits, &tc);
    g_loss = rep.lastEpochLoss;
    opt.free();
    v.free();
}

// ── GAN ──────────────────────────────────────────────────────────────────────
static void w_gan() {
    struct GanConfig cfg = GanConfig::default(4UL);
    cfg.hidden = 32UL;
    cfg.lrG = (float)0.0005; cfg.lrD = (float)0.0005;
    cfg.emaDecay = (float)0.0;
    struct Gan g = Gan::new(2UL, &cfg);
    struct GanLosses l = g.train(&g_gauss, 10UL, 64UL);
    g_loss = l.d;
    g.free();
}

// ── DQN: 1000 updates on random transitions ──────────────────────────────────
static void w_dqn() {
    struct DqnConfig cfg = DqnConfig::default();
    cfg.learningStarts = 64UL;
    cfg.bufferSize = 10000UL;
    struct Dqn d = Dqn::new(4UL, 2UL, &cfg);
    struct Rng rng = Rng::new(5UL);
    &FloatTensor o = FloatTensor::new_1d(4UL, 0);
    &FloatTensor n = FloatTensor::new_1d(4UL, 0);
    unsigned long i = 0UL;
    float last = (float)0.0;
    while (i < 1064UL) {
        rng.fill_normal(o, (float)0.0, (float)1.0);
        rng.fill_normal(n, (float)0.0, (float)1.0);
        d.act(o, 0);                      // acting is part of every environment step
        float l = d.observe(o, rng.below(2UL), rng.uniform(), n, rng.below(10UL) == 0UL);
        if (l >= (float)0.0) last = l;
        i = i + 1UL;
    }
    g_loss = last;
    d.free();
}

// ── PPO: 5 updates of a 1024-step rollout (4 epochs x 16 minibatches) ───────
static void w_ppo() {
    struct PgConfig cfg = PgConfig::default(PG_PPO);
    struct PolicyAgent p = PolicyAgent::new(4UL, 2UL, &cfg);
    struct Rng rng = Rng::new(6UL);
    &FloatTensor o = FloatTensor::new_1d(4UL, 0);
    &FloatTensor n = FloatTensor::new_1d(4UL, 0);
    unsigned long u = 0UL;
    float last = (float)0.0;
    while (u < 5UL) {
        unsigned long i = 0UL;
        while (i < 1024UL) {
            rng.fill_normal(o, (float)0.0, (float)1.0);
            rng.fill_normal(n, (float)0.0, (float)1.0);
            p.record(o, rng.below(2UL), (float)1.0, n, rng.below(20UL) == 0UL, 0);
            i = i + 1UL;
        }
        last = p.update();
        u = u + 1UL;
    }
    g_loss = last;
    p.free();
}

// ── LoRA: rank 4 on a frozen 2-64-64-2 MLP ───────────────────────────────────
static &FloatTensor lora_loss_(&LoraMlp l, const &FloatTensor x, const &FloatTensor y) {
    return tensor_cross_entropy_loss(l.forward(x), y);
}

static void w_lora() {
    struct Rng rng = Rng::new(7UL);
    struct Mlp base = Mlp::new(2UL, 64UL, 2UL, 2UL, ACT_RELU, ACT_NONE, &rng);
    struct LoraConfig cfg = LoraConfig::default(4UL);
    struct LoraMlp ft = LoraMlp::attach(&base, &cfg);
    struct Optimizer opt = Optimizer::new(OPT_ADAMW, (float)0.01);
    model_add_params(&ft, &opt);
    struct TrainConfig tc = TrainConfig::default();
    tc.epochs = 30UL;
    struct TrainReport rep = trainer_fit(&ft, lora_loss_, &opt, &g_xor, &tc);
    g_loss = rep.lastEpochLoss;
    ft.detach();
    opt.free();
    base.free();
}

// ── Conv autoencoder on 1x16x16 images ───────────────────────────────────────
struct ConvAE {
    &FloatTensor w1; &FloatTensor b1;   // conv 1->16, 3x3 s2 p1     16x16 -> 8x8
    &FloatTensor w2; &FloatTensor b2;   // conv 16->32, 3x3 s2 p1    8x8 -> 4x4
    &FloatTensor w3; &FloatTensor b3;   // convT 32->16, 4x4 s2 p1   4x4 -> 8x8
    &FloatTensor w4; &FloatTensor b4;   // convT 16->1, 4x4 s2 p1    8x8 -> 16x16
};

static &FloatTensor param_(unsigned long r, unsigned long c, float scale, &Rng rng) {
    &FloatTensor t = FloatTensor::new_2d(r, c, 1);
    rng.fill_uniform(t, -scale, scale);
    return t;
}

static &FloatTensor cae_loss_(&ConvAE m, const &FloatTensor x, const &FloatTensor y) {
    &FloatTensor h = tensor_conv2d(x, m.w1, m.b1, 1UL, 16UL, 16UL, 3UL, 3UL, 2UL, 1UL).relu();
    h = tensor_conv2d(h, m.w2, m.b2, 16UL, 8UL, 8UL, 3UL, 3UL, 2UL, 1UL).relu();
    h = tensor_conv_transpose2d(h, m.w3, m.b3, 32UL, 4UL, 4UL, 4UL, 4UL, 2UL, 1UL).relu();
    h = tensor_conv_transpose2d(h, m.w4, m.b4, 16UL, 8UL, 8UL, 4UL, 4UL, 2UL, 1UL);
    return tensor_bce_logits_loss(h, x);
}

static void w_conv_ae() {
    struct Rng rng = Rng::new(8UL);
    struct ConvAE m;
    m.w1 = param_(16UL, 9UL, (float)0.33, &rng);   m.b1 = param_(1UL, 16UL, (float)0.33, &rng);
    m.w2 = param_(32UL, 144UL, (float)0.083, &rng); m.b2 = param_(1UL, 32UL, (float)0.083, &rng);
    m.w3 = param_(32UL, 256UL, (float)0.044, &rng); m.b3 = param_(1UL, 16UL, (float)0.044, &rng);
    m.w4 = param_(16UL, 16UL, (float)0.0625, &rng); m.b4 = param_(1UL, 1UL, (float)0.0625, &rng);
    struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.001);
    opt.add(m.w1); opt.add(m.b1); opt.add(m.w2); opt.add(m.b2);
    opt.add(m.w3); opt.add(m.b3); opt.add(m.w4); opt.add(m.b4);
    struct TrainConfig tc = TrainConfig::default();
    tc.epochs = 5UL;
    struct TrainReport rep = trainer_fit(&m, cae_loss_, &opt, &g_img, &tc);
    g_loss = rep.lastEpochLoss;
    opt.free();
}

static void bench_(const &char name, fn void() work) {
    work();   // warm-up
    double t0 = now_ms();
    work();
    double t = now_ms() - t0;
    print("["); print(name); print("] train_ms="); print_float(t); print(" last_loss="); println_float((double)g_loss);
}

int main() {
    g_xor = xor_data_(512UL);
    g_big = big_data_();
    struct Rng rng = Rng::new(9UL);
    g_bits = Dataset::shapes(512UL, 10UL, &rng);
    g_gauss = Dataset::blobs(1024UL, 1UL, 2UL, (float)0.5, &rng);
    g_img = Dataset::shapes(512UL, 16UL, &rng);
    bench_("mlp_small", w_mlp_small);
    bench_("mlp_big", w_mlp_big);
    bench_("vae", w_vae);
    bench_("gan", w_gan);
    bench_("dqn", w_dqn);
    bench_("ppo", w_ppo);
    bench_("lora", w_lora);
    bench_("conv_ae", w_conv_ae);
    return 0;
}
