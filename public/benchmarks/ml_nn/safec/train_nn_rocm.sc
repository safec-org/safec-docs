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
    struct Rng rng = Rng::new(3UL);
    struct Mlp net = Mlp::new(in, hidden, 2UL, out, ACT_RELU, ACT_NONE, &rng);
    struct TrainConfig tc = TrainConfig::default();
    tc.epochs = epochs;
    tc.batchSize = batch;
    struct RocmNet dn = RocmNet::from_mlp(&net, batch);
    struct RocmOptimizer o = RocmOptimizer::new(OPT_ADAM, lr);
    dn.add_params(&o);
    struct TrainReport rep = rocm_trainer_fit(&dn, rocm_net_ce_step, &o, d, &tc);
    g_loss = rep.steps > 0UL ? rep.lastEpochLoss : (float)-1.0;
    o.free();
    dn.free();
    net.free();
}

static void w_mlp_small() { run_mlp_(&g_xor, 2UL, 32UL, 2UL, (float)0.01, 32UL, 30UL); }
static void w_mlp_big() { run_mlp_(&g_big, 784UL, 256UL, 10UL, (float)0.001, 128UL, 3UL); }

// ── VAE ──────────────────────────────────────────────────────────────────────
static void w_vae() {
    struct VaeConfig cfg = VaeConfig::default(4UL);
    cfg.hidden = 128UL;
    struct Vae v = Vae::new(100UL, &cfg);
    struct TrainConfig tc = TrainConfig::default();
    tc.epochs = 10UL;
    struct RocmVae dv = RocmVae::new(&v, 32UL);
    struct RocmOptimizer o = RocmOptimizer::new(OPT_ADAM, (float)0.001);
    dv.add_params(&o);
    struct TrainReport rep = rocm_trainer_fit(&dv, rocm_vae_step, &o, &g_bits, &tc);
    g_loss = rep.steps > 0UL ? rep.lastEpochLoss : (float)-1.0;
    o.free();
    dv.free();
    v.free();
}

// ── GAN ──────────────────────────────────────────────────────────────────────
static void w_gan() {
    struct GanConfig cfg = GanConfig::default(4UL);
    cfg.hidden = 32UL;
    cfg.lrG = (float)0.0005; cfg.lrD = (float)0.0005;
    cfg.emaDecay = (float)0.0;
    struct Gan g = Gan::new(2UL, &cfg);
    struct RocmGan d = RocmGan::new(&g, 64UL);
    struct GanLosses l = d.train(&g_gauss, 10UL, 64UL);
    g_loss = d.ok ? l.d : (float)-1.0;
    d.free();
    g.free();
}

// ── DQN: 1000 updates on random transitions ──────────────────────────────────
static void w_dqn() {
    struct DqnConfig cfg = DqnConfig::default();
    cfg.learningStarts = 64UL;
    cfg.bufferSize = 10000UL;
    struct Dqn d = Dqn::new(4UL, 2UL, &cfg);
    struct RocmDqn dev = RocmDqn::new(&d);
    struct Rng rng = Rng::new(5UL);
    &Tensor o = Tensor::new_1d(4UL, 0);
    &Tensor n = Tensor::new_1d(4UL, 0);
    unsigned long i = 0UL;
    while (i < 1064UL) {
        rng.fill_normal(o, (float)0.0, (float)1.0);
        rng.fill_normal(n, (float)0.0, (float)1.0);
        dev.act(o, 0);               // acting is part of every environment step
        dev.observe(o, rng.below(2UL), rng.uniform(), n, rng.below(10UL) == 0UL);
        i = i + 1UL;
    }
    g_loss = dev.loss();
    dev.free();
    d.free();
}

// ── PPO: 5 updates of a 1024-step rollout (4 epochs x 16 minibatches) ───────
static void w_ppo() {
    struct PgConfig cfg = PgConfig::default(PG_PPO);
    struct PolicyAgent p = PolicyAgent::new(4UL, 2UL, &cfg);
    struct RocmPolicy dev = RocmPolicy::new(&p, 1024UL);
    struct Rng rng = Rng::new(6UL);
    &Tensor o = Tensor::new_1d(4UL, 0);
    &Tensor n = Tensor::new_1d(4UL, 0);
    unsigned long u = 0UL;
    float last = (float)0.0;
    while (u < 5UL) {
        unsigned long i = 0UL;
        while (i < 1024UL) {
            rng.fill_normal(o, (float)0.0, (float)1.0);
            rng.fill_normal(n, (float)0.0, (float)1.0);
            dev.record(o, rng.below(2UL), (float)1.0, n, rng.below(20UL) == 0UL, 0);
            i = i + 1UL;
        }
        last = dev.update();
        u = u + 1UL;
    }
    g_loss = last;
    dev.free();
    p.free();
}

// ── Conv autoencoder on 1x16x16 images ───────────────────────────────────────
static &Tensor param_(unsigned long r, unsigned long c, float scale, &Rng rng) {
    &Tensor t = Tensor::new_2d(r, c, 1);
    rng.fill_uniform(t, -scale, scale);
    return t;
}

static void w_conv_ae() {
    struct Rng rng = Rng::new(8UL);
    &Tensor w1 = param_(16UL, 9UL, (float)0.33, &rng);   &Tensor b1 = param_(1UL, 16UL, (float)0.33, &rng);
    &Tensor w2 = param_(32UL, 144UL, (float)0.083, &rng); &Tensor b2 = param_(1UL, 32UL, (float)0.083, &rng);
    &Tensor w3 = param_(32UL, 256UL, (float)0.044, &rng); &Tensor b3 = param_(1UL, 16UL, (float)0.044, &rng);
    &Tensor w4 = param_(16UL, 16UL, (float)0.0625, &rng); &Tensor b4 = param_(1UL, 1UL, (float)0.0625, &rng);
    struct TrainConfig tc = TrainConfig::default();
    tc.epochs = 5UL;
    struct RocmNet n = RocmNet::new(32UL);
    n.add_conv2d(w1, b1, 1UL, 16UL, 16UL, 3UL, 2UL, 1UL, ACT_RELU);           // 16x16 -> 8x8
    n.add_conv2d(w2, b2, 16UL, 8UL, 8UL, 3UL, 2UL, 1UL, ACT_RELU);            // 8x8 -> 4x4
    n.add_conv_transpose2d(w3, b3, 32UL, 4UL, 4UL, 4UL, 2UL, 1UL, ACT_RELU);  // 4x4 -> 8x8
    n.add_conv_transpose2d(w4, b4, 16UL, 8UL, 8UL, 4UL, 2UL, 1UL, ACT_NONE);  // 8x8 -> 16x16
    struct RocmOptimizer o = RocmOptimizer::new(OPT_ADAM, (float)0.001);
    n.add_params(&o);
    struct TrainReport rep = rocm_trainer_fit(&n, rocm_net_recon_bce_step, &o, &g_img, &tc);
    g_loss = rep.steps > 0UL ? rep.lastEpochLoss : (float)-1.0;
    o.free();
    n.free();
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
    struct Rng r1 = Rng::new(1UL);
    g_xor = Dataset::xor(512UL, (float)0.0, &r1);
    struct Rng r2 = Rng::new(2UL);
    g_big = Dataset::blobs(2048UL, 10UL, 784UL, (float)1.0, &r2);
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
    bench_("conv_ae", w_conv_ae);
    return 0;
}
