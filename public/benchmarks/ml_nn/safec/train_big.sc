// Bigger std::ml training workloads on the CPU (nn.h / convnet.h autograd,
// trainer_fit): an MLP, a CNN with batch norm, a small ResNet, a U-Net, a
// DCGAN and a convolutional VAE. train_big_rocm.sc runs the same models on
// the GPU. One untimed warm-up run, then one timed run per workload
// (run_bench.py keeps the best of its runs). Prints
// "[workload] train_ms=... last_loss=..." per workload.
#include <std/ml/nn.h>
#include <std/ml/datasets.h>
#include <std/ml/trainer.h>
#include <std/ml/autoencoder.h>
#include <std/ml/convnet.h>
#include <std/ml/gan.h>
#include <std/time.h>
#include <std/io.h>

static float g_loss = (float)0.0;
static double now_ms() { return (double)time_mono_ns() * 1e-6; }

static struct Dataset g_blob784;   // 8192 x 784, 10 classes
static struct Dataset g_img28;     // 2048 x 1x28x28 shapes, 4 classes
static struct Dataset g_rgb32;     // 1024 x 3x32x32, 10 classes
static struct Dataset g_img32;     // 512 x 1x32x32 shapes

// ── MLP 784-1024-1024-10, batch 256, 2 epochs ────────────────────────────────
static &Tensor clf_mlp_(&Mlp m, const &Tensor x, const &Tensor y) { return tensor_cross_entropy_loss(mlp_forward(m, x), y); }

static void w_mlp() {
    struct Rng rng = rng_new(3UL);
    struct Mlp net = mlp_new(784UL, 1024UL, 2UL, 10UL, ACT_RELU, ACT_NONE, &rng);
    struct TrainConfig tc = train_config_default();
    tc.epochs = 2UL; tc.batchSize = 256UL;
    struct Optimizer o = optimizer_new(OPT_ADAM, (float)0.001);
    optimizer_add_mlp(&o, &net);
    g_loss = trainer_fit(&net, clf_mlp_, &o, &g_blob784, &tc).lastEpochLoss;
    optimizer_free(&o);
    mlp_free(&net);
}

static &Tensor clf_cn_(&ConvNet n, const &Tensor x, const &Tensor y) { return tensor_cross_entropy_loss(convnet_forward(n, x), y); }
static &Tensor rec_cn_(&ConvNet n, const &Tensor x, const &Tensor y) { return tensor_bce_logits_loss(convnet_forward(n, x), x); }

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
    struct Optimizer o = optimizer_new(OPT_ADAM, (float)0.001);
    optimizer_add_convnet(&o, &n);
    g_loss = (which == 2 ? trainer_fit(&n, rec_cn_, &o, d, &tc) : trainer_fit(&n, clf_cn_, &o, d, &tc)).lastEpochLoss;
    optimizer_free(&o);
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
    g_loss = dcgan_train(&g, &g_img28, 1UL, 64UL).d;
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
    struct Optimizer o = optimizer_new(OPT_ADAM, (float)0.001);
    conv_vae_add_params(&o, &v);
    g_loss = trainer_fit(&v, conv_vae_loss, &o, &g_img28, &tc).lastEpochLoss;
    optimizer_free(&o);
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
    struct Rng r1 = rng_new(11UL);
    g_blob784 = dataset_blobs(8192UL, 10UL, 784UL, (float)1.0, &r1);
    g_img28 = dataset_shapes(2048UL, 28UL, &r1);
    g_rgb32 = dataset_blobs(1024UL, 10UL, 3072UL, (float)1.0, &r1);
    g_img32 = dataset_shapes(512UL, 32UL, &r1);
    bench_("mlp", w_mlp);
    bench_("cnn", w_cnn);
    bench_("resnet", w_resnet);
    bench_("unet", w_unet);
    bench_("dcgan", w_dcgan);
    bench_("convvae", w_convvae);
    return 0;
}
