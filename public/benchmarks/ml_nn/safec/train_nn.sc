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
static &Tensor clf_loss_(&Mlp m, const &Tensor x, const &Tensor y) {
    return tensor_cross_entropy_loss(mlp_forward(m, x), y);
}

static struct Dataset xor_data_(unsigned long n) {
    struct Rng rng = rng_new(1UL);
    return dataset_xor(n, (float)0.0, &rng);
}

static struct Dataset big_data_() {
    struct Rng rng = rng_new(2UL);
    struct Dataset d = dataset_blobs(2048UL, 10UL, 784UL, (float)1.0, &rng);
    return d;
}

static void run_mlp_(&Dataset d, unsigned long in, unsigned long hidden, unsigned long out,
                     float lr, unsigned long batch, unsigned long epochs) {
    struct Rng rng = rng_new(3UL);
    struct Mlp net = mlp_new(in, hidden, 2UL, out, ACT_RELU, ACT_NONE, &rng);
    struct Optimizer opt = optimizer_new(OPT_ADAM, lr);
    optimizer_add_mlp(&opt, &net);
    struct TrainConfig tc = train_config_default();
    tc.epochs = epochs;
    tc.batchSize = batch;
    struct TrainReport rep = trainer_fit(&net, clf_loss_, &opt, d, &tc);
    g_loss = rep.lastEpochLoss;
    optimizer_free(&opt);
    mlp_free(&net);
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
    struct VaeConfig cfg = vae_config_default(4UL);
    cfg.hidden = 128UL;
    struct Vae v = vae_new(100UL, &cfg);
    struct Optimizer opt = optimizer_new(OPT_ADAM, (float)0.001);
    vae_add_params(&opt, &v);
    struct TrainConfig tc = train_config_default();
    tc.epochs = 10UL;
    struct TrainReport rep = trainer_fit(&v, vae_loss, &opt, &g_bits, &tc);
    g_loss = rep.lastEpochLoss;
    optimizer_free(&opt);
    vae_free(&v);
}

// ── GAN ──────────────────────────────────────────────────────────────────────
static void w_gan() {
    struct GanConfig cfg = gan_config_default(4UL);
    cfg.hidden = 32UL;
    cfg.lrG = (float)0.0005; cfg.lrD = (float)0.0005;
    cfg.emaDecay = (float)0.0;
    struct Gan g = gan_new(2UL, &cfg);
    struct GanLosses l = gan_train(&g, &g_gauss, 10UL, 64UL);
    g_loss = l.d;
    gan_free(&g);
}

// ── DQN: 1000 updates on random transitions ──────────────────────────────────
static void w_dqn() {
    struct DqnConfig cfg = dqn_config_default();
    cfg.learningStarts = 64UL;
    cfg.bufferSize = 10000UL;
    struct Dqn d = dqn_new(4UL, 2UL, &cfg);
    struct Rng rng = rng_new(5UL);
    &Tensor o = tensor_new_1d(4UL, 0);
    &Tensor n = tensor_new_1d(4UL, 0);
    unsigned long i = 0UL;
    float last = (float)0.0;
    while (i < 1064UL) {
        rng_fill_normal(&rng, o, (float)0.0, (float)1.0);
        rng_fill_normal(&rng, n, (float)0.0, (float)1.0);
        dqn_act(&d, o, 0);                      // acting is part of every environment step
        float l = dqn_observe(&d, o, rng_below(&rng, 2UL), rng_uniform(&rng), n, rng_below(&rng, 10UL) == 0UL);
        if (l >= (float)0.0) last = l;
        i = i + 1UL;
    }
    g_loss = last;
    dqn_free(&d);
}

// ── PPO: 5 updates of a 1024-step rollout (4 epochs x 16 minibatches) ───────
static void w_ppo() {
    struct PgConfig cfg = pg_config_default(PG_PPO);
    struct PolicyAgent p = pg_new(4UL, 2UL, &cfg);
    struct Rng rng = rng_new(6UL);
    &Tensor o = tensor_new_1d(4UL, 0);
    &Tensor n = tensor_new_1d(4UL, 0);
    unsigned long u = 0UL;
    float last = (float)0.0;
    while (u < 5UL) {
        unsigned long i = 0UL;
        while (i < 1024UL) {
            rng_fill_normal(&rng, o, (float)0.0, (float)1.0);
            rng_fill_normal(&rng, n, (float)0.0, (float)1.0);
            pg_record(&p, o, rng_below(&rng, 2UL), (float)1.0, n, rng_below(&rng, 20UL) == 0UL, 0);
            i = i + 1UL;
        }
        last = pg_update(&p);
        u = u + 1UL;
    }
    g_loss = last;
    pg_free(&p);
}

// ── LoRA: rank 4 on a frozen 2-64-64-2 MLP ───────────────────────────────────
static &Tensor lora_loss_(&LoraMlp l, const &Tensor x, const &Tensor y) {
    return tensor_cross_entropy_loss(lora_forward(l, x), y);
}

static void w_lora() {
    struct Rng rng = rng_new(7UL);
    struct Mlp base = mlp_new(2UL, 64UL, 2UL, 2UL, ACT_RELU, ACT_NONE, &rng);
    struct LoraConfig cfg = lora_config_default(4UL);
    struct LoraMlp ft = lora_attach(&base, &cfg);
    struct Optimizer opt = optimizer_new(OPT_ADAMW, (float)0.01);
    lora_add_params(&opt, &ft);
    struct TrainConfig tc = train_config_default();
    tc.epochs = 30UL;
    struct TrainReport rep = trainer_fit(&ft, lora_loss_, &opt, &g_xor, &tc);
    g_loss = rep.lastEpochLoss;
    lora_detach(&ft);
    optimizer_free(&opt);
    mlp_free(&base);
}

// ── Conv autoencoder on 1x16x16 images ───────────────────────────────────────
struct ConvAE {
    &Tensor w1; &Tensor b1;   // conv 1->16, 3x3 s2 p1     16x16 -> 8x8
    &Tensor w2; &Tensor b2;   // conv 16->32, 3x3 s2 p1    8x8 -> 4x4
    &Tensor w3; &Tensor b3;   // convT 32->16, 4x4 s2 p1   4x4 -> 8x8
    &Tensor w4; &Tensor b4;   // convT 16->1, 4x4 s2 p1    8x8 -> 16x16
};

static &Tensor param_(unsigned long r, unsigned long c, float scale, &Rng rng) {
    &Tensor t = tensor_new_2d(r, c, 1);
    rng_fill_uniform(rng, t, -scale, scale);
    return t;
}

static &Tensor cae_loss_(&ConvAE m, const &Tensor x, const &Tensor y) {
    &Tensor h = tensor_relu(tensor_conv2d(x, m.w1, m.b1, 1UL, 16UL, 16UL, 3UL, 3UL, 2UL, 1UL));
    h = tensor_relu(tensor_conv2d(h, m.w2, m.b2, 16UL, 8UL, 8UL, 3UL, 3UL, 2UL, 1UL));
    h = tensor_relu(tensor_conv_transpose2d(h, m.w3, m.b3, 32UL, 4UL, 4UL, 4UL, 4UL, 2UL, 1UL));
    h = tensor_conv_transpose2d(h, m.w4, m.b4, 16UL, 8UL, 8UL, 4UL, 4UL, 2UL, 1UL);
    return tensor_bce_logits_loss(h, x);
}

static void w_conv_ae() {
    struct Rng rng = rng_new(8UL);
    struct ConvAE m;
    m.w1 = param_(16UL, 9UL, (float)0.33, &rng);   m.b1 = param_(1UL, 16UL, (float)0.33, &rng);
    m.w2 = param_(32UL, 144UL, (float)0.083, &rng); m.b2 = param_(1UL, 32UL, (float)0.083, &rng);
    m.w3 = param_(32UL, 256UL, (float)0.044, &rng); m.b3 = param_(1UL, 16UL, (float)0.044, &rng);
    m.w4 = param_(16UL, 16UL, (float)0.0625, &rng); m.b4 = param_(1UL, 1UL, (float)0.0625, &rng);
    struct Optimizer opt = optimizer_new(OPT_ADAM, (float)0.001);
    optimizer_add(&opt, m.w1); optimizer_add(&opt, m.b1); optimizer_add(&opt, m.w2); optimizer_add(&opt, m.b2);
    optimizer_add(&opt, m.w3); optimizer_add(&opt, m.b3); optimizer_add(&opt, m.w4); optimizer_add(&opt, m.b4);
    struct TrainConfig tc = train_config_default();
    tc.epochs = 5UL;
    struct TrainReport rep = trainer_fit(&m, cae_loss_, &opt, &g_img, &tc);
    g_loss = rep.lastEpochLoss;
    optimizer_free(&opt);
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
    struct Rng rng = rng_new(9UL);
    g_bits = dataset_shapes(512UL, 10UL, &rng);
    g_gauss = dataset_blobs(1024UL, 1UL, 2UL, (float)0.5, &rng);
    g_img = dataset_shapes(512UL, 16UL, &rng);
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
