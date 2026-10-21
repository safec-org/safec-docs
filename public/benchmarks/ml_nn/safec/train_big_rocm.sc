// train_big.sc's models on nn_rocm.h's device-resident GPU path
// (rocm_trainer_fit, rocm_dcgan, rocm_conv_vae): fp32, then the MLP and the
// conv nets again with 16-bit activations (rocm_net_set_precision: fp16 with
// a dynamic loss scale, bf16), fp32 master weights and optimizer state.
// The timed run includes building the device model and uploading the data.
// One untimed warm-up run, then one timed run per workload (run_bench.py
// keeps the best of its runs). Prints "[workload_precision] train_ms=...
// last_loss=..." per workload.
// Link: -lamdhip64 -lhiprtc -lrocblas -lMIOpen
#include <std/ml/nn.h>
#include <std/ml/datasets.h>
#include <std/ml/trainer.h>
#include <std/ml/autoencoder.h>
#include <std/ml/convnet.h>
#include <std/ml/gan.h>
#include <std/ml/nn_rocm.h>
#include <std/time.h>
#include <std/io.h>

static float g_loss = (float)0.0;
static int g_prec = 0;   // the nets' activations: 0 fp32, ROCM_FP16, ROCM_BF16
static double now_ms() { return (double)time_mono_ns() * 1e-6; }

static struct Dataset g_blob784;   // 8192 x 784, 10 classes
static struct Dataset g_img28;     // 2048 x 1x28x28 shapes, 4 classes
static struct Dataset g_rgb32;     // 1024 x 3x32x32, 10 classes
static struct Dataset g_img32;     // 512 x 1x32x32 shapes

// ── MLP 784-1024-1024-10, batch 256, 2 epochs ────────────────────────────────
static void w_mlp() {
    struct Rng rng = rng_new(3UL);
    struct Mlp net = mlp_new(784UL, 1024UL, 2UL, 10UL, ACT_RELU, ACT_NONE, &rng);
    struct TrainConfig tc = train_config_default();
    tc.epochs = 2UL; tc.batchSize = 256UL;
    struct RocmNet d = rocm_net_from_mlp(&net, 256UL);
    if (g_prec != 0 && !rocm_net_set_precision(&d, g_prec)) d.ok = 0;
    struct RocmOptimizer o = rocm_optimizer_new(OPT_ADAM, (float)0.001);
    rocm_optimizer_add_net(&o, &d);
    struct TrainReport r = rocm_trainer_fit(&d, rocm_net_ce_step, &o, &g_blob784, &tc);
    g_loss = r.steps > 0UL && d.ok ? r.lastEpochLoss : (float)-1.0;
    rocm_optimizer_free(&o);
    rocm_net_free(&d);
    mlp_free(&net);
}

// ── ConvNet classifiers ──────────────────────────────────────────────────────
static struct ConvNet cnn_() {
    struct Rng rng = rng_new(4UL);
    struct ConvNet n = convnet_new(1UL, 28UL, 28UL);
    convnet_conv(&n, 32UL, 3UL, 1UL, 1UL, ACT_NONE, &rng);
    convnet_batchnorm(&n, ACT_RELU);
    convnet_maxpool(&n, 2UL, 2UL, 0UL);                       // 32 x 14 x 14
    convnet_conv(&n, 64UL, 3UL, 1UL, 1UL, ACT_NONE, &rng);
    convnet_batchnorm(&n, ACT_RELU);
    convnet_maxpool(&n, 2UL, 2UL, 0UL);                       // 64 x 7 x 7
    convnet_dense(&n, 128UL, ACT_RELU, &rng);
    convnet_dense(&n, 4UL, ACT_NONE, &rng);
    return n;
}

static struct ConvNet resnet_() {
    struct Rng rng = rng_new(5UL);
    struct ConvNet n = convnet_new(3UL, 32UL, 32UL);
    convnet_conv(&n, 32UL, 3UL, 1UL, 1UL, ACT_NONE, &rng);
    convnet_batchnorm(&n, ACT_RELU);
    convnet_residual_block(&n, 32UL, 1UL, 1, ACT_RELU, &rng);   // 32 x 32 x 32
    convnet_residual_block(&n, 64UL, 2UL, 1, ACT_RELU, &rng);   // 64 x 16 x 16
    convnet_residual_block(&n, 128UL, 2UL, 1, ACT_RELU, &rng);  // 128 x 8 x 8
    convnet_global_avgpool(&n);
    convnet_dense(&n, 10UL, ACT_NONE, &rng);
    return n;
}

static struct ConvNet unet_() {
    struct Rng rng = rng_new(6UL);
    struct ConvNet n = convnet_new(1UL, 32UL, 32UL);
    convnet_conv(&n, 32UL, 3UL, 1UL, 1UL, ACT_RELU, &rng);
    unsigned long a = convnet_save(&n);                          // 32 x 32 x 32
    convnet_maxpool(&n, 2UL, 2UL, 0UL);
    convnet_conv(&n, 64UL, 3UL, 1UL, 1UL, ACT_RELU, &rng);
    unsigned long b = convnet_save(&n);                          // 64 x 16 x 16
    convnet_maxpool(&n, 2UL, 2UL, 0UL);
    convnet_conv(&n, 128UL, 3UL, 1UL, 1UL, ACT_RELU, &rng);      // 128 x 8 x 8
    convnet_upsample_bilinear(&n, 2UL);
    convnet_concat_skip(&n, b);                                  // 192 x 16 x 16
    convnet_conv(&n, 64UL, 3UL, 1UL, 1UL, ACT_RELU, &rng);
    convnet_upsample_bilinear(&n, 2UL);
    convnet_concat_skip(&n, a);                                  // 96 x 32 x 32
    convnet_conv(&n, 32UL, 3UL, 1UL, 1UL, ACT_RELU, &rng);
    convnet_conv(&n, 1UL, 1UL, 1UL, 0UL, ACT_NONE, &rng);
    return n;
}

// which: 0 CNN (28x28 shapes), 1 ResNet (3x32x32 blobs), 2 U-Net (reconstructing 32x32 shapes)
static void run_cn_(int which) {
    struct ConvNet n = which == 0 ? cnn_() : (which == 1 ? resnet_() : unet_());
    &Dataset d = which == 0 ? &g_img28 : (which == 1 ? &g_rgb32 : &g_img32);
    struct TrainConfig tc = train_config_default();
    tc.epochs = 2UL;
    tc.batchSize = which == 2 ? 32UL : 128UL;
    struct RocmNet dn = rocm_net_from_convnet(&n, tc.batchSize);
    if (g_prec != 0 && !rocm_net_set_precision(&dn, g_prec)) dn.ok = 0;
    struct RocmOptimizer o = rocm_optimizer_new(OPT_ADAM, (float)0.001);
    rocm_optimizer_add_net(&o, &dn);
    struct TrainReport r = which == 2 ? rocm_trainer_fit(&dn, rocm_net_recon_bce_step, &o, d, &tc)
                                      : rocm_trainer_fit(&dn, rocm_net_ce_step, &o, d, &tc);
    g_loss = r.steps > 0UL && dn.ok ? r.lastEpochLoss : (float)-1.0;
    rocm_optimizer_free(&o);
    rocm_net_free(&dn);
    convnet_free(&n);
}
static void w_cnn() { run_cn_(0); }
static void w_resnet() { run_cn_(1); }
static void w_unet() { run_cn_(2); }

// ── DCGAN 28 x 28 ────────────────────────────────────────────────────────────
static struct Dcgan dcgan_() {
    struct Rng rng = rng_new(7UL);
    struct GanConfig cfg = gan_config_default(32UL);
    cfg.emaDecay = (float)0.0;
    struct ConvNet gen = convnet_new(32UL, 1UL, 1UL);
    convnet_deconv(&gen, 64UL, 7UL, 1UL, 0UL, ACT_NONE, &rng);   // 64 x 7 x 7
    convnet_batchnorm(&gen, ACT_RELU);
    convnet_deconv(&gen, 32UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);   // 32 x 14 x 14
    convnet_batchnorm(&gen, ACT_RELU);
    convnet_deconv(&gen, 1UL, 4UL, 2UL, 1UL, ACT_TANH, &rng);    // 1 x 28 x 28
    struct ConvNet disc = convnet_new(1UL, 28UL, 28UL);
    convnet_conv(&disc, 32UL, 4UL, 2UL, 1UL, ACT_LEAKY, &rng);   // 32 x 14 x 14
    convnet_conv(&disc, 64UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);    // 64 x 7 x 7
    convnet_batchnorm(&disc, ACT_LEAKY);
    convnet_conv(&disc, 1UL, 7UL, 1UL, 0UL, ACT_NONE, &rng);     // 1 x 1 x 1
    return dcgan_new(gen, disc, &cfg);
}

static void w_dcgan() {
    struct Dcgan g = dcgan_();
    struct RocmGan d = rocm_dcgan_new(&g, 64UL);
    struct GanLosses l = rocm_gan_train(&d, &g_img28, 1UL, 64UL);
    g_loss = d.ok ? l.d : (float)-1.0;
    rocm_gan_free(&d);
    dcgan_free(&g);
}

// ── ConvVae 28 x 28 ──────────────────────────────────────────────────────────
static struct ConvVae cvae_() {
    struct Rng rng = rng_new(8UL);
    struct VaeConfig cfg = vae_config_default(16UL);
    struct ConvNet enc = convnet_new(1UL, 28UL, 28UL);
    convnet_conv(&enc, 32UL, 4UL, 2UL, 1UL, ACT_RELU, &rng);     // 32 x 14 x 14
    convnet_conv(&enc, 64UL, 4UL, 2UL, 1UL, ACT_RELU, &rng);     // 64 x 7 x 7
    struct ConvNet dec = convnet_new(16UL, 1UL, 1UL);
    convnet_deconv(&dec, 64UL, 7UL, 1UL, 0UL, ACT_RELU, &rng);   // 64 x 7 x 7
    convnet_deconv(&dec, 32UL, 4UL, 2UL, 1UL, ACT_RELU, &rng);   // 32 x 14 x 14
    convnet_deconv(&dec, 1UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);    // 1 x 28 x 28
    return conv_vae_new(enc, dec, &cfg);
}

static void w_convvae() {
    struct ConvVae v = cvae_();
    struct TrainConfig tc = train_config_default();
    tc.epochs = 1UL; tc.batchSize = 64UL;
    struct RocmVae d = rocm_conv_vae_new(&v, 64UL);
    struct RocmOptimizer o = rocm_optimizer_new(OPT_ADAM, (float)0.001);
    rocm_optimizer_add_vae(&o, &d);
    struct TrainReport r = rocm_trainer_fit(&d, rocm_vae_step, &o, &g_img28, &tc);
    g_loss = r.steps > 0UL ? r.lastEpochLoss : (float)-1.0;
    rocm_optimizer_free(&o);
    rocm_vae_free(&d);
    conv_vae_free(&v);
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
    struct Rng r1 = rng_new(11UL);
    g_blob784 = dataset_blobs(8192UL, 10UL, 784UL, (float)1.0, &r1);
    g_img28 = dataset_shapes(2048UL, 28UL, &r1);
    g_rgb32 = dataset_blobs(1024UL, 10UL, 3072UL, (float)1.0, &r1);
    g_img32 = dataset_shapes(512UL, 32UL, &r1);
    bench_("mlp_fp32", w_mlp);
    bench_("cnn_fp32", w_cnn);
    bench_("resnet_fp32", w_resnet);
    bench_("unet_fp32", w_unet);
    bench_("dcgan_fp32", w_dcgan);
    bench_("convvae_fp32", w_convvae);
    g_prec = ROCM_FP16;
    bench_("mlp_fp16", w_mlp);
    bench_("cnn_fp16", w_cnn);
    bench_("resnet_fp16", w_resnet);
    bench_("unet_fp16", w_unet);
    g_prec = ROCM_BF16;
    bench_("mlp_bf16", w_mlp);
    bench_("cnn_bf16", w_cnn);
    bench_("resnet_bf16", w_resnet);
    bench_("unet_bf16", w_unet);
    return 0;
}
