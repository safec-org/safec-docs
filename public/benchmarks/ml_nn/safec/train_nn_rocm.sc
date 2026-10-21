// train_nn.sc's workloads on nn_rocm.h's device-resident GPU path:
// parameters, activations, gradients, optimizer state and the dataset stay
// in GPU memory, and rocm_trainer_fit replays recorded training steps. The
// timed run includes building the device model and uploading the data.
// LoRA has no GPU path. One untimed warm-up run, then one timed run per
// workload (run_bench.py keeps the best of its runs). Prints
// "[workload] train_ms=... last_loss=..." per workload.
// Link: -lamdhip64 -lhiprtc -lrocblas -lMIOpen
#include <std/ml/nn.h>
#include <std/ml/datasets.h>
#include <std/ml/trainer.h>
#include <std/ml/autoencoder.h>
#include <std/ml/gan.h>
#include <std/ml/rl.h>
#include <std/ml/nn_rocm.h>
#include <std/time.h>
#include <std/io.h>

static float g_loss = (float)0.0;

static double now_ms() { return (double)time_mono_ns() * 1e-6; }

static struct Dataset g_xor;
static struct Dataset g_big;
static struct Dataset g_bits;
static struct Dataset g_gauss;
static struct Dataset g_img;

// ── MLP classifiers ──────────────────────────────────────────────────────────
static void run_mlp_(&Dataset d, unsigned long in, unsigned long hidden, unsigned long out,
                     float lr, unsigned long batch, unsigned long epochs) {
    struct Rng rng = rng_new(3UL);
    struct Mlp net = mlp_new(in, hidden, 2UL, out, ACT_RELU, ACT_NONE, &rng);
    struct TrainConfig tc = train_config_default();
    tc.epochs = epochs;
    tc.batchSize = batch;
    struct RocmNet dn = rocm_net_from_mlp(&net, batch);
    struct RocmOptimizer o = rocm_optimizer_new(OPT_ADAM, lr);
    rocm_optimizer_add_net(&o, &dn);
    struct TrainReport rep = rocm_trainer_fit(&dn, rocm_net_ce_step, &o, d, &tc);
    g_loss = rep.steps > 0UL ? rep.lastEpochLoss : (float)-1.0;
    rocm_optimizer_free(&o);
    rocm_net_free(&dn);
    mlp_free(&net);
}

static void w_mlp_small() { run_mlp_(&g_xor, 2UL, 32UL, 2UL, (float)0.01, 32UL, 30UL); }
static void w_mlp_big() { run_mlp_(&g_big, 784UL, 256UL, 10UL, (float)0.001, 128UL, 3UL); }

// ── VAE ──────────────────────────────────────────────────────────────────────
static void w_vae() {
    struct VaeConfig cfg = vae_config_default(4UL);
    cfg.hidden = 128UL;
    struct Vae v = vae_new(100UL, &cfg);
    struct TrainConfig tc = train_config_default();
    tc.epochs = 10UL;
    struct RocmVae dv = rocm_vae_new(&v, 32UL);
    struct RocmOptimizer o = rocm_optimizer_new(OPT_ADAM, (float)0.001);
    rocm_optimizer_add_vae(&o, &dv);
    struct TrainReport rep = rocm_trainer_fit(&dv, rocm_vae_step, &o, &g_bits, &tc);
    g_loss = rep.steps > 0UL ? rep.lastEpochLoss : (float)-1.0;
    rocm_optimizer_free(&o);
    rocm_vae_free(&dv);
    vae_free(&v);
}

// ── GAN ──────────────────────────────────────────────────────────────────────
static void w_gan() {
    struct GanConfig cfg = gan_config_default(4UL);
    cfg.hidden = 32UL;
    cfg.lrG = (float)0.0005; cfg.lrD = (float)0.0005;
    cfg.emaDecay = (float)0.0;
    struct Gan g = gan_new(2UL, &cfg);
    struct RocmGan d = rocm_gan_new(&g, 64UL);
    struct GanLosses l = rocm_gan_train(&d, &g_gauss, 10UL, 64UL);
    g_loss = d.ok ? l.d : (float)-1.0;
    rocm_gan_free(&d);
    gan_free(&g);
}

// ── DQN: 1000 updates on random transitions ──────────────────────────────────
static void w_dqn() {
    struct DqnConfig cfg = dqn_config_default();
    cfg.learningStarts = 64UL;
    cfg.bufferSize = 10000UL;
    struct Dqn d = dqn_new(4UL, 2UL, &cfg);
    struct RocmDqn dev = rocm_dqn_new(&d);
    struct Rng rng = rng_new(5UL);
    &Tensor o = tensor_new_1d(4UL, 0);
    &Tensor n = tensor_new_1d(4UL, 0);
    unsigned long i = 0UL;
    while (i < 1064UL) {
        rng_fill_normal(&rng, o, (float)0.0, (float)1.0);
        rng_fill_normal(&rng, n, (float)0.0, (float)1.0);
        rocm_dqn_act(&dev, o, 0);               // acting is part of every environment step
        rocm_dqn_observe(&dev, o, rng_below(&rng, 2UL), rng_uniform(&rng), n, rng_below(&rng, 10UL) == 0UL);
        i = i + 1UL;
    }
    g_loss = rocm_dqn_loss(&dev);
    rocm_dqn_free(&dev);
    dqn_free(&d);
}

// ── PPO: 5 updates of a 1024-step rollout (4 epochs x 16 minibatches) ───────
static void w_ppo() {
    struct PgConfig cfg = pg_config_default(PG_PPO);
    struct PolicyAgent p = pg_new(4UL, 2UL, &cfg);
    struct RocmPolicy dev = rocm_pg_new(&p, 1024UL);
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
            rocm_pg_record(&dev, o, rng_below(&rng, 2UL), (float)1.0, n, rng_below(&rng, 20UL) == 0UL, 0);
            i = i + 1UL;
        }
        last = rocm_pg_update(&dev);
        u = u + 1UL;
    }
    g_loss = last;
    rocm_pg_free(&dev);
    pg_free(&p);
}

// ── Conv autoencoder on 1x16x16 images ───────────────────────────────────────
static &Tensor param_(unsigned long r, unsigned long c, float scale, &Rng rng) {
    &Tensor t = tensor_new_2d(r, c, 1);
    rng_fill_uniform(rng, t, -scale, scale);
    return t;
}

static void w_conv_ae() {
    struct Rng rng = rng_new(8UL);
    &Tensor w1 = param_(16UL, 9UL, (float)0.33, &rng);   &Tensor b1 = param_(1UL, 16UL, (float)0.33, &rng);
    &Tensor w2 = param_(32UL, 144UL, (float)0.083, &rng); &Tensor b2 = param_(1UL, 32UL, (float)0.083, &rng);
    &Tensor w3 = param_(32UL, 256UL, (float)0.044, &rng); &Tensor b3 = param_(1UL, 16UL, (float)0.044, &rng);
    &Tensor w4 = param_(16UL, 16UL, (float)0.0625, &rng); &Tensor b4 = param_(1UL, 1UL, (float)0.0625, &rng);
    struct TrainConfig tc = train_config_default();
    tc.epochs = 5UL;
    struct RocmNet n = rocm_net_new(32UL);
    rocm_net_add_conv2d(&n, w1, b1, 1UL, 16UL, 16UL, 3UL, 2UL, 1UL, ACT_RELU);           // 16x16 -> 8x8
    rocm_net_add_conv2d(&n, w2, b2, 16UL, 8UL, 8UL, 3UL, 2UL, 1UL, ACT_RELU);            // 8x8 -> 4x4
    rocm_net_add_conv_transpose2d(&n, w3, b3, 32UL, 4UL, 4UL, 4UL, 2UL, 1UL, ACT_RELU);  // 4x4 -> 8x8
    rocm_net_add_conv_transpose2d(&n, w4, b4, 16UL, 8UL, 8UL, 4UL, 2UL, 1UL, ACT_NONE);  // 8x8 -> 16x16
    struct RocmOptimizer o = rocm_optimizer_new(OPT_ADAM, (float)0.001);
    rocm_optimizer_add_net(&o, &n);
    struct TrainReport rep = rocm_trainer_fit(&n, rocm_net_recon_bce_step, &o, &g_img, &tc);
    g_loss = rep.steps > 0UL ? rep.lastEpochLoss : (float)-1.0;
    rocm_optimizer_free(&o);
    rocm_net_free(&n);
}

static void bench_(const &char name, fn void() work) {
    work();   // warm-up
    double t0 = now_ms();
    work();
    double t = now_ms() - t0;
    print("["); print(name); print("] train_ms="); print_float(t); print(" last_loss="); println_float((double)g_loss);
}

int main() {
    if (!rocm_available()) { println("no ROCm GPU"); return 0; }
    struct Rng r1 = rng_new(1UL);
    g_xor = dataset_xor(512UL, (float)0.0, &r1);
    struct Rng r2 = rng_new(2UL);
    g_big = dataset_blobs(2048UL, 10UL, 784UL, (float)1.0, &r2);
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
    bench_("conv_ae", w_conv_ae);
    return 0;
}
