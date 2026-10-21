// The mlp_big workload of train_nn.sc with its forward matmuls on the GPU
// through tensor_rocm.h (tensor_matmul_rocm_blas: upload, rocBLAS, read
// back per call; the backward pass runs on the CPU). The nn.h training
// stack has no device-resident GPU path, so this is SafeC's GPU option for
// it today. One warm-up run, then one timed run.
#include <std/ml/nn.h>
#include <std/ml/datasets.h>
#include <std/ml/trainer.h>
#include <std/ml/tensor_rocm.h>
#include <std/time.h>
#include <std/io.h>

static &Tensor fwd_(&Mlp m, const &Tensor x) {
    &Tensor h = tensor_relu(tensor_add_bias(tensor_matmul_rocm_blas(x, *m.params.at(0UL)), *m.params.at(1UL)));
    h = tensor_relu(tensor_add_bias(tensor_matmul_rocm_blas(h, *m.params.at(2UL)), *m.params.at(3UL)));
    return tensor_add_bias(tensor_matmul_rocm_blas(h, *m.params.at(4UL)), *m.params.at(5UL));
}
static &Tensor loss_(&Mlp m, const &Tensor x, const &Tensor y) { return tensor_cross_entropy_loss(fwd_(m, x), y); }

int main() {
    struct Rng rng = rng_new(2UL);
    struct Dataset d = dataset_blobs(2048UL, 10UL, 784UL, (float)1.0, &rng);
    double ms = 0.0;
    float loss = (float)0.0;
    int r = 0;
    while (r < 2) {                             // run 0 is the warm-up
        struct Rng r3 = rng_new(3UL);
        struct Mlp net = mlp_new(784UL, 256UL, 2UL, 10UL, ACT_RELU, ACT_NONE, &r3);
        struct Optimizer opt = optimizer_new(OPT_ADAM, (float)0.001);
        optimizer_add_mlp(&opt, &net);
        struct TrainConfig tc = train_config_default();
        tc.epochs = 3UL; tc.batchSize = 128UL;
        long long t0 = time_mono_ns();
        struct TrainReport rep = trainer_fit(&net, loss_, &opt, &d, &tc);
        ms = (double)(time_mono_ns() - t0) * 1e-6;
        loss = rep.lastEpochLoss;
        optimizer_free(&opt); mlp_free(&net);
        r = r + 1;
    }
    print("[mlp_big] train_ms="); print_float(ms); print(" last_loss="); println_float((double)loss);
    return 0;
}
