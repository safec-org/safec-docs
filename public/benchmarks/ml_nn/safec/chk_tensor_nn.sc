extern int printf(const char* fmt, ...);
#include <std/ml/tensor_nn.h>
#include <std/ml/tensor_nn.sc>

double xvals[8];
double wvals8[8];
double wvals4[4];

void init_vals() {
    xvals[0]=-2.3; xvals[1]=-0.7; xvals[2]=-0.1; xvals[3]=0.0;
    xvals[4]=0.05; xvals[5]=0.8; xvals[6]=1.5; xvals[7]=3.1;
    wvals8[0]=0.3; wvals8[1]=-1.2; wvals8[2]=0.9; wvals8[3]=0.5;
    wvals8[4]=-0.4; wvals8[5]=1.1; wvals8[6]=-0.6; wvals8[7]=0.2;
    wvals4[0]=0.6; wvals4[1]=-0.9; wvals4[2]=1.3; wvals4[3]=-0.2;
}

double last_loss(&Tensor y, &Tensor w) {
    &Tensor weighted = std::tensor_mul(y, w);
    &Tensor loss = std::tensor_sum(weighted);
    return loss->at1(0UL);
}

int main() {
    init_vals();
    int allOk = 1;
    double eps = 0.0001;

    // ── sigmoid ──────────────────────────────────────────────────────────
    {
        &Tensor x = std::tensor_from_1d(xvals, 8UL, 1);
        &Tensor w = std::tensor_from_1d(wvals8, 8UL, 0);
        &Tensor y = std::tensor_sigmoid(x);
        &Tensor weighted = std::tensor_mul(y, w);
        &Tensor loss = std::tensor_sum(weighted);
        std::tensor_backward(loss);
        double maxDiff = 0.0;
        unsigned long i = 0UL;
        while (i < 8UL) {
            unsafe {
                double orig = x->data[i];
                x->data[i] = orig + eps;
                double lp = last_loss(std::tensor_sigmoid(x), w);
                x->data[i] = orig - eps;
                double lm = last_loss(std::tensor_sigmoid(x), w);
                x->data[i] = orig;
                double numeric = (lp - lm) / (2.0 * eps);
                double diff = x->grad[i] - numeric;
                if (diff < 0.0) { diff = -diff; }
                if (diff > maxDiff) { maxDiff = diff; }
            }
            i = i + 1UL;
        }
        int ok = maxDiff < 0.001;
        allOk = ok && allOk;
        printf("sigmoid    max|analytic-numeric| = %.8f  %s\n", maxDiff, ok ? "OK" : "FAIL");
    }

    // ── tanh ─────────────────────────────────────────────────────────────
    {
        &Tensor x = std::tensor_from_1d(xvals, 8UL, 1);
        &Tensor w = std::tensor_from_1d(wvals8, 8UL, 0);
        &Tensor y = std::tensor_tanh_t(x);
        &Tensor weighted = std::tensor_mul(y, w);
        &Tensor loss = std::tensor_sum(weighted);
        std::tensor_backward(loss);
        double maxDiff = 0.0;
        unsigned long i = 0UL;
        while (i < 8UL) {
            unsafe {
                double orig = x->data[i];
                x->data[i] = orig + eps;
                double lp = last_loss(std::tensor_tanh_t(x), w);
                x->data[i] = orig - eps;
                double lm = last_loss(std::tensor_tanh_t(x), w);
                x->data[i] = orig;
                double numeric = (lp - lm) / (2.0 * eps);
                double diff = x->grad[i] - numeric;
                if (diff < 0.0) { diff = -diff; }
                if (diff > maxDiff) { maxDiff = diff; }
            }
            i = i + 1UL;
        }
        int ok = maxDiff < 0.001;
        allOk = ok && allOk;
        printf("tanh       max|analytic-numeric| = %.8f  %s\n", maxDiff, ok ? "OK" : "FAIL");
    }

    // ── softmax ──────────────────────────────────────────────────────────
    {
        &Tensor x = std::tensor_from_1d(xvals, 8UL, 1);
        &Tensor w = std::tensor_from_1d(wvals8, 8UL, 0);
        &Tensor y = std::tensor_softmax(x);
        &Tensor weighted = std::tensor_mul(y, w);
        &Tensor loss = std::tensor_sum(weighted);
        std::tensor_backward(loss);
        double maxDiff = 0.0;
        unsigned long i = 0UL;
        while (i < 8UL) {
            unsafe {
                double orig = x->data[i];
                x->data[i] = orig + eps;
                double lp = last_loss(std::tensor_softmax(x), w);
                x->data[i] = orig - eps;
                double lm = last_loss(std::tensor_softmax(x), w);
                x->data[i] = orig;
                double numeric = (lp - lm) / (2.0 * eps);
                double diff = x->grad[i] - numeric;
                if (diff < 0.0) { diff = -diff; }
                if (diff > maxDiff) { maxDiff = diff; }
            }
            i = i + 1UL;
        }
        int ok = maxDiff < 0.001;
        allOk = ok && allOk;
        printf("softmax    max|analytic-numeric| = %.8f  %s\n", maxDiff, ok ? "OK" : "FAIL");
    }

    // ── elu ──────────────────────────────────────────────────────────────
    {
        &Tensor x = std::tensor_from_1d(xvals, 8UL, 1);
        &Tensor w = std::tensor_from_1d(wvals8, 8UL, 0);
        &Tensor y = std::tensor_elu(x, 1.0);
        &Tensor weighted = std::tensor_mul(y, w);
        &Tensor loss = std::tensor_sum(weighted);
        std::tensor_backward(loss);
        double maxDiff = 0.0;
        unsigned long i = 0UL;
        while (i < 8UL) {
            unsafe {
                double orig = x->data[i];
                x->data[i] = orig + eps;
                double lp = last_loss(std::tensor_elu(x, 1.0), w);
                x->data[i] = orig - eps;
                double lm = last_loss(std::tensor_elu(x, 1.0), w);
                x->data[i] = orig;
                double numeric = (lp - lm) / (2.0 * eps);
                double diff = x->grad[i] - numeric;
                if (diff < 0.0) { diff = -diff; }
                if (diff > maxDiff) { maxDiff = diff; }
            }
            i = i + 1UL;
        }
        int ok = maxDiff < 0.001;
        allOk = ok && allOk;
        printf("elu        max|analytic-numeric| = %.8f  %s\n", maxDiff, ok ? "OK" : "FAIL");
    }

    // ── gelu ─────────────────────────────────────────────────────────────
    {
        &Tensor x = std::tensor_from_1d(xvals, 8UL, 1);
        &Tensor w = std::tensor_from_1d(wvals8, 8UL, 0);
        &Tensor y = std::tensor_gelu(x);
        &Tensor weighted = std::tensor_mul(y, w);
        &Tensor loss = std::tensor_sum(weighted);
        std::tensor_backward(loss);
        double maxDiff = 0.0;
        unsigned long i = 0UL;
        while (i < 8UL) {
            unsafe {
                double orig = x->data[i];
                x->data[i] = orig + eps;
                double lp = last_loss(std::tensor_gelu(x), w);
                x->data[i] = orig - eps;
                double lm = last_loss(std::tensor_gelu(x), w);
                x->data[i] = orig;
                double numeric = (lp - lm) / (2.0 * eps);
                double diff = x->grad[i] - numeric;
                if (diff < 0.0) { diff = -diff; }
                if (diff > maxDiff) { maxDiff = diff; }
            }
            i = i + 1UL;
        }
        int ok = maxDiff < 0.001;
        allOk = ok && allOk;
        printf("gelu       max|analytic-numeric| = %.8f  %s\n", maxDiff, ok ? "OK" : "FAIL");
    }

    // ── silu ─────────────────────────────────────────────────────────────
    {
        &Tensor x = std::tensor_from_1d(xvals, 8UL, 1);
        &Tensor w = std::tensor_from_1d(wvals8, 8UL, 0);
        &Tensor y = std::tensor_silu(x);
        &Tensor weighted = std::tensor_mul(y, w);
        &Tensor loss = std::tensor_sum(weighted);
        std::tensor_backward(loss);
        double maxDiff = 0.0;
        unsigned long i = 0UL;
        while (i < 8UL) {
            unsafe {
                double orig = x->data[i];
                x->data[i] = orig + eps;
                double lp = last_loss(std::tensor_silu(x), w);
                x->data[i] = orig - eps;
                double lm = last_loss(std::tensor_silu(x), w);
                x->data[i] = orig;
                double numeric = (lp - lm) / (2.0 * eps);
                double diff = x->grad[i] - numeric;
                if (diff < 0.0) { diff = -diff; }
                if (diff > maxDiff) { maxDiff = diff; }
            }
            i = i + 1UL;
        }
        int ok = maxDiff < 0.001;
        allOk = ok && allOk;
        printf("silu       max|analytic-numeric| = %.8f  %s\n", maxDiff, ok ? "OK" : "FAIL");
    }

    // ── glu (8 -> 4) ─────────────────────────────────────────────────────
    {
        &Tensor x = std::tensor_from_1d(xvals, 8UL, 1);
        &Tensor w = std::tensor_from_1d(wvals4, 4UL, 0);
        &Tensor y = std::tensor_glu(x);
        &Tensor weighted = std::tensor_mul(y, w);
        &Tensor loss = std::tensor_sum(weighted);
        std::tensor_backward(loss);
        double maxDiff = 0.0;
        unsigned long i = 0UL;
        while (i < 8UL) {
            unsafe {
                double orig = x->data[i];
                x->data[i] = orig + eps;
                double lp = last_loss(std::tensor_glu(x), w);
                x->data[i] = orig - eps;
                double lm = last_loss(std::tensor_glu(x), w);
                x->data[i] = orig;
                double numeric = (lp - lm) / (2.0 * eps);
                double diff = x->grad[i] - numeric;
                if (diff < 0.0) { diff = -diff; }
                if (diff > maxDiff) { maxDiff = diff; }
            }
            i = i + 1UL;
        }
        int ok = maxDiff < 0.001;
        allOk = ok && allOk;
        printf("glu        max|analytic-numeric| = %.8f  %s\n", maxDiff, ok ? "OK" : "FAIL");
    }

    // ── swiglu (8 -> 4) ──────────────────────────────────────────────────
    {
        &Tensor x = std::tensor_from_1d(xvals, 8UL, 1);
        &Tensor w = std::tensor_from_1d(wvals4, 4UL, 0);
        &Tensor y = std::tensor_swiglu(x);
        &Tensor weighted = std::tensor_mul(y, w);
        &Tensor loss = std::tensor_sum(weighted);
        std::tensor_backward(loss);
        double maxDiff = 0.0;
        unsigned long i = 0UL;
        while (i < 8UL) {
            unsafe {
                double orig = x->data[i];
                x->data[i] = orig + eps;
                double lp = last_loss(std::tensor_swiglu(x), w);
                x->data[i] = orig - eps;
                double lm = last_loss(std::tensor_swiglu(x), w);
                x->data[i] = orig;
                double numeric = (lp - lm) / (2.0 * eps);
                double diff = x->grad[i] - numeric;
                if (diff < 0.0) { diff = -diff; }
                if (diff > maxDiff) { maxDiff = diff; }
            }
            i = i + 1UL;
        }
        int ok = maxDiff < 0.001;
        allOk = ok && allOk;
        printf("swiglu     max|analytic-numeric| = %.8f  %s\n", maxDiff, ok ? "OK" : "FAIL");
    }

    printf("\nall activation gradient checks: %s\n\n", allOk ? "PASS" : "FAIL");

    // ── Pooling ──────────────────────────────────────────────────────────
    double poolIn[8];
    poolIn[0]=1.0; poolIn[1]=3.0; poolIn[2]=2.0; poolIn[3]=5.0;
    poolIn[4]=4.0; poolIn[5]=4.0; poolIn[6]=-1.0; poolIn[7]=0.5;
    &Tensor px = std::tensor_from_1d(poolIn, 8UL, 1);
    &Tensor pmax = std::tensor_maxpool1d(px, 2UL, 2UL);
    printf("maxpool1d(k=2,s=2): %.1f %.1f %.1f %.1f (expect 3 5 4 0.5)\n",
           pmax->at1(0UL), pmax->at1(1UL), pmax->at1(2UL), pmax->at1(3UL));
    &Tensor pmaxLoss = std::tensor_sum(pmax);
    std::tensor_backward(pmaxLoss);
    unsafe {
        printf("maxpool1d grad: %.1f %.1f %.1f %.1f %.1f %.1f %.1f %.1f (expect 0 1 0 1 1 0 0 1)\n",
               px->grad[0], px->grad[1], px->grad[2], px->grad[3],
               px->grad[4], px->grad[5], px->grad[6], px->grad[7]);
    }

    &Tensor px2 = std::tensor_from_1d(poolIn, 8UL, 1);
    &Tensor pavg = std::tensor_avgpool1d(px2, 2UL, 2UL);
    printf("avgpool1d(k=2,s=2): %.2f %.2f %.2f %.2f (expect 2 3.5 4 -0.25)\n",
           pavg->at1(0UL), pavg->at1(1UL), pavg->at1(2UL), pavg->at1(3UL));
    &Tensor pavgLoss = std::tensor_sum(pavg);
    std::tensor_backward(pavgLoss);
    unsafe {
        printf("avgpool1d grad: %.2f %.2f %.2f %.2f %.2f %.2f %.2f %.2f (expect all 0.5)\n",
               px2->grad[0], px2->grad[1], px2->grad[2], px2->grad[3],
               px2->grad[4], px2->grad[5], px2->grad[6], px2->grad[7]);
    }

    // ── Adam: one step on L = sum(0.5*w^2), grad = w ────────────────────────
    struct AdamState adamState = std::adam_new_default(4UL, 0.1);
    double wbuf[4]; wbuf[0]=1.0; wbuf[1]=-2.0; wbuf[2]=3.0; wbuf[3]=-4.0;
    &Tensor wT = std::tensor_from_1d(wbuf, 4UL, 1);
    unsafe {
        double* g = (double*)malloc(sizeof(double) * 4UL);
        g[0]=1.0; g[1]=-2.0; g[2]=3.0; g[3]=-4.0;
        wT->grad = (&heap double)g;
    }
    std::adam_step(&adamState, wT);
    printf("adam step1: %.6f %.6f %.6f %.6f (expect all moved by ~-0.1 toward 0)\n",
           wT->at1(0UL), wT->at1(1UL), wT->at1(2UL), wT->at1(3UL));
    std::adam_free(&adamState);

    return allOk ? 0 : 1;
}
