# Machine Learning (`std/ml/`)

A CPU tensor/autograd core, a wide attention/diffusion/architecture
library, GPU backends, and an LLM orchestration layer
(LangChain/LangGraph/LangSmith-inspired).

```c
#include <std/ml/tensor.h>
```

::: warning Scope
This is a from-scratch, single-session implementation, not a port of an
existing framework. `tensor.h` covers 1D/2D float32 tensors and the ops
needed for the modules below — not a general n-dimensional array
library. Deliberately not implemented: DPM-Solver-v3 (its design needs
statistics calibrated from a real trained model), EDM's own VE-flavored
samplers, positional encoding, and patchify.
:::

Every function below takes/returns `Tensor` (and the other structs here)
by a region-less reference (`&Tensor`/`const &Tensor` — see [Memory &
Regions](/reference/memory)'s "Outliving References" section): nothing
in this library cares whether the caller's tensor is heap/stack/static/
arena-backed, so no function pins one specific region. A raw pointer (or
a reference of any region) converts to these implicitly, with no
`unsafe`.

---

## Tensor

```c
#include <std/ml/tensor.h>
```

CPU tensors (1D/2D, `float` — float32, like PyTorch's default and the
only precision Metal supports) with reverse-mode autograd — PyTorch-style
ergonomics: build a computation graph implicitly by calling ops on
`requiresGrad` tensors, then call `tensor_backward()`. For fp16/bf16 storage
and mixed-precision training, see [Half-precision tensors](#half-precision-tensors).

```c
&Tensor x = tensor_from_1d(values, 3UL, /*requiresGrad=*/1);
&Tensor y = tensor_sum(tensor_mul(x, x));  // y = sum(x^2)
tensor_backward(y);                         // x.grad now holds 2x
```

Ops: `tensor_add`/`tensor_sub`/`tensor_mul`/`tensor_scale`/`tensor_matmul`/
`tensor_relu`/`tensor_sum`. Verified against hand-computed gradients and a
real finite-difference check on a matmul→relu→sum chain.

```c
struct Tensor {
    &heap float         data;          // flat row-major, length == size
    &heap unsigned long shape;
    unsigned long       ndim;          // 1 or 2
    unsigned long       size;
    int                 requiresGrad;
    &heap float         grad;          // allocated only when requiresGrad
    // ... autograd bookkeeping (gradFn, parents, visited)

    float at1(unsigned long i) const;
    float at2(unsigned long r, unsigned long c) const;
    void  set1(unsigned long i, float v);
    void  set2(unsigned long r, unsigned long c, float v);
    void  free();
};

&Tensor tensor_new_1d(unsigned long n, int requiresGrad);
&Tensor tensor_new_2d(unsigned long rows, unsigned long cols, int requiresGrad);
&Tensor tensor_from_1d(const &float values, unsigned long n, int requiresGrad);
&Tensor tensor_from_2d(const &float values, unsigned long rows, unsigned long cols, int requiresGrad);
&Tensor tensor_zeros_like(const &Tensor t);
&Tensor tensor_fill(&Tensor t, float v);

&Tensor tensor_add(const &Tensor a, const &Tensor b);
&Tensor tensor_sub(const &Tensor a, const &Tensor b);
&Tensor tensor_mul(const &Tensor a, const &Tensor b);   // elementwise
&Tensor tensor_scale(const &Tensor a, float k);
&Tensor tensor_matmul(const &Tensor a, const &Tensor b);
&Tensor tensor_sum(const &Tensor a);

void tensor_backward(&Tensor t);
void tensor_zero_grad(&Tensor t);
int  tensor_is_grad_enabled();
void tensor_set_grad_enabled(int enabled);   // 0 = inference: composed ops record no graph (leaf flags unchanged)
```

### Autograd ops and optimizer — `tensor_nn.h`

```c
#include <std/ml/tensor_nn.h>
```

Activations with backward passes (the ones every GPU backend uses), 1D pooling, and Adam.

```c
&Tensor tensor_relu(const &Tensor a);
&Tensor tensor_sigmoid(const &Tensor a);
&Tensor tensor_tanh_t(const &Tensor a);
&Tensor tensor_softmax(const &Tensor a);
&Tensor tensor_elu(const &Tensor a, float alpha);
&Tensor tensor_gelu(const &Tensor a);
&Tensor tensor_silu(const &Tensor a);
&Tensor tensor_glu(const &Tensor a);      // split last axis into [a1, a2]: a1 * sigmoid(a2)
&Tensor tensor_swiglu(const &Tensor a);   // silu(a1) * a2 — output has half the columns
&Tensor tensor_maxpool1d(const &Tensor a, unsigned long kernel, unsigned long stride);
&Tensor tensor_avgpool1d(const &Tensor a, unsigned long kernel, unsigned long stride);

struct AdamState {
    &heap float m; &heap float v;
    unsigned long size;
    float lr; float beta1; float beta2; float eps;
    unsigned long t;
};
struct AdamState adam_new(unsigned long size, float lr, float beta1, float beta2, float eps);
struct AdamState adam_new_default(unsigned long size, float lr);   // beta1 0.9, beta2 0.999, eps 1e-8 (paper defaults)
void adam_step(&AdamState state, &Tensor param);                   // uses param.grad
void adam_free(&AdamState state);
```

## Activations

```c
#include <std/ml/activations.h>
```

Forward-only elementwise activations (no autograd — for inference layers;
the autograd versions are in `tensor_nn.h` above);
`tensor_layernorm_rows` (row-wise mean-0/var-1 normalization, no affine —
apply your own scale/shift for AdaLN-style conditioning, see DiT below);
`tensor_residual_add` (a named `tensor_add` alias for residual/skip-
connection call sites).

```c
&Tensor tensor_relu_fwd(const &Tensor a);
&Tensor tensor_sigmoid_fwd(const &Tensor a);
&Tensor tensor_tanh_fwd(const &Tensor a);
&Tensor tensor_silu_fwd(const &Tensor a);
&Tensor tensor_gelu_fwd(const &Tensor a);
&Tensor tensor_layernorm_rows(const &Tensor x, float eps);
&Tensor tensor_residual_add(const &Tensor x, const &Tensor sublayerOut);
```

## Attention

```c
#include <std/ml/attention.h>
```

`softmax_rows()` and `attention_forward()` (single-head scaled
dot-product attention) build `mha_forward()` (standard multi-head
attention — splits Q/K/V's columns into heads, runs attention per head,
concatenates results). Also in this file:

- **`gated_attention_forward`/`gated_mha_forward`** — sigmoid output
  gating, elementwise-multiplied into the attention result.
- **`linear_attention_forward`** — the `phi(x) = elu(x)+1` kernel-trick
  reformulation (Katharopoulos et al.), O((seqLen+kvLen)·headDim²)
  instead of the full O(seqLen·kvLen·headDim) score matrix.
- **`flash_attention_forward`** — tiled, online-softmax attention (Dao et
  al.): processes K/V in blocks, never materializing the full
  [seqLen,kvLen] score matrix. Verified to match `attention_forward`'s
  output within tolerance for a block size that doesn't evenly divide
  `kvLen`.

```c
&Tensor softmax_rows(const &Tensor x);
&Tensor tensor_transpose(const &Tensor in);
&Tensor tensor_slice_cols(const &Tensor in, unsigned long startCol, unsigned long numCols);
void    tensor_set_cols(&Tensor dst, unsigned long startCol, const &Tensor src);

&Tensor attention_forward(const &Tensor Q, const &Tensor K, const &Tensor V);
&Tensor mha_forward(const &Tensor Q, const &Tensor K, const &Tensor V, unsigned long numHeads);
&Tensor gated_attention_forward(const &Tensor Q, const &Tensor K, const &Tensor V, const &Tensor gateLogits);
&Tensor gated_mha_forward(const &Tensor Q, const &Tensor K, const &Tensor V,
                          unsigned long numHeads, const &Tensor gateLogits);
&Tensor linear_attention_forward(const &Tensor Q, const &Tensor K, const &Tensor V);
&Tensor flash_attention_forward(const &Tensor Q, const &Tensor K, const &Tensor V, unsigned long kvBlockSize);
```

Forward-only (inference-focused, no backward pass) throughout. Verified
against a hand-computed 2×2 example, a self-consistency check between
`mha_forward` and manual per-head slicing, and per-variant hand-computed/
cross-checked tests for the gated/linear/flash forms.

## Advanced attention

```c
#include <std/ml/attention_advanced.h>
```

- **`mla_forward`/`eg_mla_forward`** — Multi-head Latent Attention
  (DeepSeek-V2): compresses K/V into a shared low-rank latent before
  up-projecting back to full width, saving KV-cache memory. `eg_mla_forward`
  shares one latent across independent per-group up-projections
  (grouped-query attention's KV-sharing layered onto MLA's compression).
- **`windowed_attention_forward`** — Shifted-Window (Swin) attention
  adapted to 1D sequences: partitions into fixed-size local windows,
  with a cyclic shift (`shift` param) so the next layer's windows
  straddle this layer's boundaries.
- **`gated_deltanet_forward`** — Gated DeltaNet (Yang et al. 2024): a
  linear-recurrent attention replacement using a delta-rule state update
  (write the residual between the new value and what the state already
  predicts) plus a scalar decay gate, computed via a sequential scan.

Verified via self-consistency checks against manual per-group/per-window
reconstruction, and a hand-computed 2-step state trace for Gated
DeltaNet.

```c
&Tensor mla_forward(const &Tensor X, const &Tensor Wq, const &Tensor Wdkv,
                    const &Tensor Wuk, const &Tensor Wuv, unsigned long numHeads);
&Tensor eg_mla_forward(const &Tensor X, const &Tensor Wq, const &Tensor Wdkv,
                       struct Slice<&Tensor> WukGroups, struct Slice<&Tensor> WuvGroups,  // one per group
                       unsigned long headsPerGroup);
&Tensor windowed_attention_forward(const &Tensor Q, const &Tensor K, const &Tensor V,
                                   unsigned long windowSize, unsigned long shift);

struct GatedDeltaNet { &Tensor Walpha; &Tensor Wbeta; unsigned long keyDim; unsigned long valueDim; };
struct GatedDeltaNet gated_deltanet_new(unsigned long dModel, unsigned long keyDim, unsigned long valueDim);
&Tensor gated_deltanet_forward(const &GatedDeltaNet layer, const &Tensor X,
                               const &Tensor Q, const &Tensor K, const &Tensor V);
void    gated_deltanet_free(&GatedDeltaNet layer);
```

`eg_mla_forward` takes the per-group up-projections as [`Slice`](/stdlib/collections#slice)s of tensor references — bounds-checked arrays whose length is the group count. Back them with a fixed-size stack or static array (`&Tensor groups[4]`) or, for a count known only at run time, a heap or arena allocation.

## RNN family

```c
#include <std/ml/rnn.h>
```

`RNNCell`/`GRUCell`/`LSTMCell`/`XLSTMCell` — plain RNN, GRU, LSTM, and
the xLSTM sLSTM variant (Beck et al. 2024: exponential input/forget
gating with a log-domain stabilizer and a normalizer state, replacing
LSTM's sigmoid gates). Row-vector convention (`x_t` is `[1, inputSize]`).
xLSTM's mLSTM (matrix-memory) variant is not implemented — sLSTM only.
Each cell verified against hand-computed gate values for a real forward
pass.

```c
struct RNNCell  rnn_cell_new(unsigned long inputSize, unsigned long hiddenSize);
&Tensor         rnn_cell_forward(const &RNNCell cell, const &Tensor x_t, const &Tensor h_prev);
void            rnn_cell_free(&RNNCell cell);

struct GRUCell  gru_cell_new(unsigned long inputSize, unsigned long hiddenSize);
&Tensor         gru_cell_forward(const &GRUCell cell, const &Tensor x_t, const &Tensor h_prev);
void            gru_cell_free(&GRUCell cell);

struct LSTMCell lstm_cell_new(unsigned long inputSize, unsigned long hiddenSize);
// Returns h_t; the new cell state is written through c_out.
&Tensor         lstm_cell_forward(const &LSTMCell cell, const &Tensor x_t,
                                  const &Tensor h_prev, const &Tensor c_prev,
                                  &&Tensor c_out);
void            lstm_cell_free(&LSTMCell cell);

struct XLSTMState { &Tensor h; &Tensor c; &Tensor n; &Tensor m; };   // hidden, cell, normalizer, stabilizer
struct XLSTMCell  xlstm_cell_new(unsigned long inputSize, unsigned long hiddenSize);
struct XLSTMState xlstm_cell_forward(const &XLSTMCell cell, const &Tensor x_t, const &XLSTMState prev);
void              xlstm_cell_free(&XLSTMCell cell);
void              xlstm_state_free(&XLSTMState s);
```

Each cell's weight tensors are public fields (`W_ih`, `W_hh`, … — named per gate) so you can load trained weights into them.

## CNN

```c
#include <std/ml/cnn.h>
```

`Conv2D` (stride + zero-padding, no dilation/grouped conv),
`maxpool2d_forward`/`avgpool2d_forward`, `upsample2x_nearest` (nearest-
neighbor 2x spatial upsample), `concat_channels` (channel-axis
concatenation) — all over a channels-first `FeatureMap` (not `Tensor`,
which tops out at 2D). Verified via hand-computed 3×3-kernel and
pooling-window checks.

```c
struct FeatureMap {
    &heap float   data;      // channels × height × width, channels-first
    unsigned long channels;
    unsigned long height;
    unsigned long width;
};
struct FeatureMap feature_map_new(unsigned long channels, unsigned long height, unsigned long width);
void              feature_map_free(&FeatureMap fm);

struct Conv2D conv2d_new(unsigned long inChannels, unsigned long outChannels,
                         unsigned long kH, unsigned long kW,
                         unsigned long stride, unsigned long padding);
struct FeatureMap conv2d_forward(const &Conv2D layer, const &FeatureMap input);
void              conv2d_free(&Conv2D layer);

struct FeatureMap maxpool2d_forward(const &FeatureMap input, unsigned long kernel, unsigned long stride);
struct FeatureMap avgpool2d_forward(const &FeatureMap input, unsigned long kernel, unsigned long stride);
struct FeatureMap upsample2x_nearest(const &FeatureMap input);
struct FeatureMap concat_channels(const &FeatureMap a, const &FeatureMap b);
```

## U-Net

```c
#include <std/ml/unet.h>
```

A fixed 2-level encoder/decoder CNN with skip connections
(Ronneberger et al. 2015) — the classic DDPM-style diffusion denoiser
backbone. Verified via output-shape checks and full self-consistency
against a manual replication of `unet_forward`'s call sequence.

```c
struct UNet unet_new(unsigned long inChannels, unsigned long c1, unsigned long c2,
                     unsigned long outChannels);
struct FeatureMap unet_forward(&UNet net, const &FeatureMap input);
void unet_free(&UNet net);
```

## Diffusion

```c
#include <std/ml/diffusion.h>
```

- **`edm_karras_sigmas`** — the Karras et al. ("Elucidating the Design
  Space...") noise schedule.
- **`ddpm_linear_schedule`/`ddpm_sampler_step`** — DDPM ancestral
  sampling.
- **`ddim_sampler_step`** — deterministic DDIM (eta=0).
- **`dpm_solver_1_step`/`dpm_solver_2_step`** — DPM-Solver (Lu et al.
  2022), eps-prediction exponential integrator, order 1 and a midpoint
  order-2 variant (`dpm_solver_2_step` takes a caller-supplied model
  callback, evaluated a second time at the log-SNR midpoint).
- **`dpm_solver_pp_1_step`/`dpm_solver_pp_2_step`** — DPM-Solver++ (Lu et
  al. 2022b), the data(x0)-prediction reformulation. Order 1 is provably
  identical to `ddim_sampler_step` — verified numerically, not just by
  derivation.

DPM-Solver-v3 is not implemented (its distinguishing idea needs
"empirical model statistics" calibrated from a real trained model over
real data).

```c
void edm_karras_sigmas(unsigned long numSteps, float sigmaMin, float sigmaMax, float rho,
                       &float outSigmas);                         // numSteps values
void ddpm_linear_schedule(unsigned long numSteps, float betaStart, float betaEnd,
                          &float outBeta, &float outAlphaBar);    // numSteps values each

&Tensor ddpm_sampler_step(const &Tensor x_t, const &Tensor epsPred,
                          float beta_t, float alphaBar_t, const &Tensor noise);
&Tensor ddim_sampler_step(const &Tensor x_t, const &Tensor epsPred,
                          float alphaBar_t, float alphaBar_prev);
&Tensor dpm_solver_1_step(const &Tensor x_cur, const &Tensor epsPred,
                          float alpha_cur, float sigma_cur, float alpha_next, float sigma_next);
&Tensor dpm_solver_pp_1_step(const &Tensor x_cur, const &Tensor x0Pred,
                             float alpha_cur, float sigma_cur, float alpha_next, float sigma_next);

// Order-2 solvers call your model again at the midpoint.
// model(x, sigma, userData) -> predicted noise (eps) or clean sample (x0).
generic<T>
&Tensor dpm_solver_2_step(fn &Tensor(const &Tensor, float, ?&T) model, ?&T userData,
                          const &Tensor x_cur, const &Tensor epsPred,
                          float alpha_cur, float sigma_cur, float alpha_next, float sigma_next);
generic<T>
&Tensor dpm_solver_pp_2_step(fn &Tensor(const &Tensor, float, ?&T) model, ?&T userData,
                             const &Tensor x_cur, const &Tensor x0Pred,
                             float alpha_cur, float sigma_cur, float alpha_next, float sigma_next);
```

`userData` is your model's own state (its weights, say); its type is checked against the callback at compile time.

## Transformer (DiT / JiT)

```c
#include <std/ml/transformer.h>
```

- **`DiTBlock`** — Diffusion Transformer (Peebles & Xie 2023), adaLN-
  style: a conditioning vector produces 6 modulation vectors per block
  (shift/scale/gate for each of the attention and FFN sublayers).
  Verified via a gate=0 exact-identity check (with gates zeroed, the
  block's output must equal its input exactly) and full self-consistency.
- **`JiTBlock`/`jit_forward`** — a plain pre-LN transformer with
  conditioning added once, up front, to the token embeddings (as
  opposed to DiT's per-layer adaptive modulation) — the architectural
  distinction this implementation draws between the two.

```c
struct DiTBlock dit_block_new(unsigned long dModel, unsigned long dHidden,
                              unsigned long dModelCond, unsigned long numHeads);
&Tensor dit_block_forward(const &DiTBlock block, const &Tensor x, const &Tensor c);  // c = conditioning
void    dit_block_free(&DiTBlock block);

struct JiTBlock jit_block_new(unsigned long dModel, unsigned long dHidden, unsigned long numHeads);
&Tensor jit_block_forward(const &JiTBlock block, const &Tensor x);
void    jit_block_free(&JiTBlock block);
// Patch embedding + conditioning, then numBlocks blocks.
&Tensor jit_forward(const &Tensor patches, const &Tensor WPatchEmbed, const &Tensor cond,
                    struct Slice<JiTBlock> blocks);
```

## GPU backends

```c
#include <std/ml/gpu_mps.h>   // Apple Silicon/macOS — [features] "mps"
#include <std/ml/gpu_cuda.h>  // NVIDIA — [features] "cuda"
#include <std/ml/gpu_rocm.h>  // AMD — [features] "rocm"
#include <std/ml/gpu_spirv.h> // Vulkan/SPIR-V (incomplete, see Backend status)
#include <std/ml/gpu_webgpu.h>// WebGPU
```

The "unified memory" piece of the ML brief: MPS's default buffer storage
mode is genuine CPU+GPU shared memory on Apple Silicon (MLX's own
selling point) — no explicit host↔device copy the way CUDA/ROCm's
discrete memory model needs.

::: warning Verification status
- **MPS**: verified end to end on real Apple Silicon hardware — real GPU
  dispatches with correct output (`mps_add_f32`, and `mps_sub_f32`/
  `mps_mul_f32` as representative cases; the other elementwise ops share
  the same code path). An earlier segfault at
  `dispatchThreadgroups:threadsPerThreadgroup:` was a compiler bug in
  passing large structs by value through a function-pointer cast, since
  fixed in the code generator.
- **ROCm**: verified on an AMD Instinct MI100 (gfx908, ROCm 7.1) —
  `tests/stdlib/rocm/run.sh` checks every op, all four GEMM transpose
  combinations, graphs, the host-array API and the fp16/bf16 buffers
  (rounding bit-identical to `float16.h`, 16-bit GEMMs, SGD) against CPU
  results (87 checks), plus the [MIOpen and hipFFT
  libraries](#rocm-miopen-hipfft) (43 checks), and the [ML benchmarks](/benchmarks#machine-learning-small-mlp-training-and-inference)
  train to the same loss as the CPU and MPS paths.
- **CUDA**: verified on an NVIDIA H200 (sm_90, CUDA 13.0, driver 580) —
  `tests/stdlib/cuda/run.sh` checks every op, all four GEMM transpose
  combinations with and without the fused ReLU, graphs, the host-array and
  persistent-buffer APIs, the fp16/bf16 kernels and the [cuDNN and cuFFT
  libraries](#cuda-cudnn-cufft) against CPU results (110 checks), and the [ML benchmarks](/benchmarks#machine-learning-small-mlp-training-and-inference)
  train to the same loss as the CPU and ROCm paths.
:::

### ROCm / HIP (AMD)

```c
#include <std/ml/gpu_rocm.h>   // link: -lamdhip64 -lhiprtc -lrocblas
```

Data lives in `RocmBuf`s — GPU buffers described by a device address and an
element count that every op checks — and ops are queued in order on one HIP
stream, so a whole training step runs on the GPU and only the values you ask
for (`rocm_read`) come back. Matmuls go to rocBLAS. Everything else runs this
library's own HIP kernels — elementwise math, ReLU and its gradient, in-place
SGD, sum reduction, and a fused MSE gradient (`dy = 2(y − t)` plus the summed
loss in one pass) — compiled with hiprtc at first use for the GPU that is
present, so one build runs on any ROCm GPU. On a machine with several GPUs
the backend picks the one with the most compute units.

| Group | Functions |
|---|---|
| Device | `rocm_available`, `rocm_device_name`, `rocm_sync` |
| Buffers | `rocm_alloc`, `rocm_upload`, `rocm_write`, `rocm_read`, `rocm_free` |
| Matmul | `rocm_gemm(a, ta, b, tb, c, m, n, k)` — `c = op(a) op(b)`, either operand transposed |
| Elementwise | `rocm_add`/`sub`/`mul`/`div`/`pow`, `rocm_scale`, `rocm_relu`, `rocm_exp`/`log`/`sqrt`, `rocm_relu_grad` |
| Training | `rocm_sgd(w, g, lr)` (in place), `rocm_mse_grad(y, t, dy, loss)`, `rocm_sum` |
| Layout | `rocm_copy(src, srcOff, dst, dstOff, n)`, `rocm_concat(a, na, c, nc, out, batch)`, `rocm_upsample2x_nearest`, `rocm_upsample2x_concat` (a U-Net decoder step in one pass), `rocm_bias_channels(y, bias, batch, channels, plane, relu)` (per-channel bias, optional ReLU, in place) |
| fp16 / bf16 | `RocmHalfBuf` (`ROCM_FP16` / `ROCM_BF16`): `rocm_half_alloc`, `rocm_half_upload`, `rocm_half_write` / `rocm_half_read` (raw bits), `rocm_half_free`; `rocm_to_half` / `rocm_from_half`; `rocm_half_gemm` (16-bit result) and `rocm_half_gemm_f32` (fp32 result); `rocm_half_sgd(w, g, lr)` |
| Graphs | `rocm_capture_begin` / `rocm_capture_end` → `RocmGraph`, `rocm_graph_launch`, `rocm_graph_free` |
| Interop | `rocm_stream_handle()`: the HIP stream every op is queued on, which MIOpen and hipFFT use too |
| Host arrays | `rocm_add_f32` … `rocm_matmul_f32_blas` — upload, compute, read back in one call |

One SGD step of a two-layer MLP, entirely on the GPU:

```c
// forward: Z1 = X W1, H = relu(Z1), Y = H W2
std::rocm_gemm(X, 0, W1, 0, Z1, B, H_DIM, I);
std::rocm_relu(Z1, H);
std::rocm_gemm(H, 0, W2, 0, Y, B, O, H_DIM);
// backward for loss = sum((Y - T)^2)
std::rocm_mse_grad(Y, T, dY, loss);
std::rocm_gemm(H, 1, dY, 0, dW2, H_DIM, O, B);     // dW2 = H^T dY
std::rocm_gemm(dY, 0, W2, 1, dH, B, H_DIM, O);     // dH  = dY W2^T
std::rocm_relu_grad(Z1, dH, dZ1);
std::rocm_gemm(X, 1, dZ1, 0, dW1, I, H_DIM, B);    // dW1 = X^T dZ1
std::rocm_sgd(W2, dW2, lr);
std::rocm_sgd(W1, dW1, lr);
```

Record such a sequence once between `rocm_capture_begin()` and
`rocm_capture_end()` and replay it with `rocm_graph_launch()` to launch the
whole step at once; this pays off when the host doesn't wait on the GPU
every step. `tensor_rocm.h` wraps the host-array ops as autograd `Tensor`
functions (`tensor_add_rocm`, `tensor_matmul_rocm_blas`, …) with a CPU
fallback.

On rocBLAS versus a hand-written kernel: this library's own tiled SGEMM
(`rocm_matmul_f32`) reaches about 9.5 TFLOPS at 4096³ on an MI100, while
rocBLAS reaches 33.7 TFLOPS using the matrix cores, and rocBLAS is also
faster at the small sizes a training loop uses (≈5 µs, close to the launch
floor). So matmuls use rocBLAS, and the custom kernels cover the work rocBLAS
doesn't do.

### CUDA (NVIDIA) {#cuda}

```c
#include <std/ml/gpu_cuda.h>   // link: -lcuda -lcublas -lcublasLt -lnvrtc
```

The same model as the ROCm backend, with the same function names under
`cuda_`: `CudaBuf`s are GPU buffers carrying their element count, ops queue in
order on one CUDA stream, and only what you `cuda_read` comes back. Matmuls go
to cuBLAS in full fp32 (no TF32). The rest runs this library's own CUDA
kernels, compiled by NVRTC at first use into a cubin for the GPU that is
present (`-arch=sm_90` on an H100/H200), so one build runs on any GPU the
installed CUDA knows. The backend uses the device's primary context, the one
the CUDA runtime and PyTorch use, so buffers can coexist with other CUDA code
in the process.

On top of the ROCm op set, two fusions cut the launches per training step
from 10 to 7:

- `cuda_gemm_relu` — `relu(op(a) op(b))` as one cuBLASLt matmul with the ReLU
  epilogue. The ReLU gradient can then test the activation instead of the
  pre-activation (`h > 0` exactly where `z > 0`), so `Z1` is never stored.
- `cuda_sgd2` — both weight updates of a two-tensor model in one kernel.

Other CUDA-specific choices: reads of up to 64 KB (a loss, one output) go
through a pinned staging buffer, and so take one DMA. A one-block reduction
stores its result directly, so `cuda_mse_grad`/`cuda_sum` need no zeroing
memset. cuBLAS gets a fixed 32 MB workspace, so its calls can be captured
into graphs.

| Group | Functions |
|---|---|
| Device | `cuda_available`, `cuda_device_name`, `cuda_sync` |
| Buffers | `cuda_alloc`, `cuda_upload`, `cuda_write`, `cuda_read`, `cuda_free` |
| Matmul | `cuda_gemm(a, ta, b, tb, c, m, n, k)`, `cuda_gemm_relu` (same arguments, fused ReLU) |
| Elementwise | `cuda_add`/`sub`/`mul`/`div`/`pow`, `cuda_scale`, `cuda_relu`, `cuda_exp`/`log`/`sqrt`, `cuda_relu_grad` |
| Training | `cuda_sgd`, `cuda_sgd2`, `cuda_mse_grad(y, t, dy, loss)`, `cuda_sum` |
| Graphs | `cuda_capture_begin` / `cuda_capture_end` → `CudaGraph`, `cuda_graph_launch`, `cuda_graph_free` |
| Host arrays | `cuda_add_f32` … `cuda_matmul_f32_blas` — upload, compute, read back in one call |
| Persistent buffers (`&void` handles) | `cuda_upload_persistent`, `cuda_matmul_f32_persistent`, `cuda_sgd_update_f32`, and the fp16/bf16 `cuda_*_f16_*`/`cuda_*_bf16_*` (2-byte storage, fp32 arithmetic) |

The MLP step above, fused:

```c
std::cuda_gemm_relu(X, 0, W1, 0, H, B, H_DIM, I);    // H = relu(X W1)
std::cuda_gemm(H, 0, W2, 0, Y, B, O, H_DIM);
std::cuda_mse_grad(Y, T, dY, loss);
std::cuda_gemm(H, 1, dY, 0, dW2, H_DIM, O, B);
std::cuda_gemm(dY, 0, W2, 1, dH, B, H_DIM, O);
std::cuda_relu_grad(H, dH, dZ1);                      // H > 0 where Z1 > 0
std::cuda_gemm(X, 1, dZ1, 0, dW1, I, H_DIM, B);
std::cuda_sgd2(W2, dW2, W1, dW1, lr);
```

Before this backend was verified, each host-array call created and destroyed
a CUDA context and a cuBLAS handle. Through `tensor_matmul_cuda_blas`, the
small MLP benchmark took 83 s to train and 816 s for inference on the H200.
The host-array functions now share the backend's context, stream and kernels.

### Convolution (cuDNN) and FFT (cuFFT) on CUDA {#cuda-cudnn-cufft}

```c
#include <std/ml/gpu_cudnn.h>   // link: -lcudnn + the gpu_cuda libraries
#include <std/dsp/fft_cuda.h>   // link: -lcufft + the gpu_cuda libraries
```

Two libraries on top of `gpu_cuda.h`. They queue their work on the same
stream, so they order with the `CudaBuf` ops and can be recorded into a
`CudaGraph`. Link them only when you use them: each is its own object file
in the std archive.

**`gpu_cudnn.h`** runs [CNN](#cnn) and [U-Net](#u-net) on the GPU. Tensors are
`CudaBuf`s in NCHW order, which is `FeatureMap`'s CHW layout with a batch axis
in front. The first convolution of each shape asks cuDNN to time its
algorithms (`cudnnFindConvolutionForwardAlgorithm`) and caches the fastest one
together with its own workspace. Make that first call outside graph capture.
Convolutions run in plain fp32 unless `cuda_set_tf32(1)` is on, the same
switch the cuBLAS GEMMs use.

| Group | Functions |
|---|---|
| Layers | `cuda_conv2d_upload(&Conv2D)` → `CudaConv2D`, `cuda_conv2d_free`, `cuda_conv_out_dim` |
| Queued ops | `cuda_conv2d(&layer, x, y, batch, h, w)`, `cuda_conv2d_relu` (conv + bias + ReLU in one cuDNN call), `cuda_maxpool2d`, `cuda_avgpool2d`, `cuda_concat_channels`; in `gpu_cuda.h`: `cuda_upsample2x_nearest`, `cuda_copy` |
| U-Net | `cuda_unet_new(&UNet, batch, h, w)` → `CudaUNet` (weights and every activation preallocated), `cuda_unet_forward(&g, x, y)`, `cuda_unet_free` |
| Host arrays | `conv2d_forward_cuda`, `unet_forward_cuda`: the signatures of `conv2d_forward`/`unet_forward`. They upload the weights on every call and fall back to the CPU when no GPU is usable |

**`fft_cuda.h`** is the GPU counterpart of [`fft.h`](/stdlib/dsp#fft-fast-fourier-transform-radix-2-cooley-tukey).
Unlike `fft.h`, it accepts any length, not only powers of two. Forward
transforms are unnormalized and inverse ones include 1/N, as in `fft.h`.

| Group | Functions |
|---|---|
| Host arrays, `double` | `cuda_fft`, `cuda_ifft`, `cuda_fft_batch(data, n, batch, inverse)`, `cuda_fft2d`, `cuda_ifft2d` (`imaging.h`'s `fft2d`) |
| Queued, fp32 `CudaBuf` | `cuda_fft_c2c(in, out, n, batch, inverse)` (interleaved re/im), `cuda_fft_r2c` (n reals → n/2+1 complex), `cuda_fft_c2r` (overwrites its input, as cuFFT does) |

`tests/stdlib/cuda` checks both libraries against the CPU code: `conv2d_forward`
at stride 1 and 2, batched; pooling, upsample and concat; a whole U-Net;
`fft`/`ifft`, `dft` for a non-power-of-two length, batched and 2D transforms;
and the fp32 device transforms. It also replays a convolution from a graph and
runs one with TF32 on.

On the H200, the queued ops beat PyTorch-CUDA on batched convolutions (32
images: 0.164 ms vs 0.257 ms in fp32) and on the U-Net (batch 1: 0.175 ms vs
0.206 ms, or 0.164 ms as one graph). PyTorch is faster for a single
one-image convolution (0.022 ms vs 0.034 ms). The device FFTs match cuFFT
through PyTorch. See the [benchmarks](/benchmarks#h200-conv-fft).

### Convolution (MIOpen) and FFT (hipFFT) on ROCm {#rocm-miopen-hipfft}

```c
#include <std/ml/gpu_miopen.h>   // link: -lMIOpen + the gpu_rocm libraries
#include <std/dsp/fft_rocm.h>    // link: -lhipfft + the gpu_rocm libraries
```

The AMD counterparts of `gpu_cudnn.h` and `fft_cuda.h`: the same functions
with `rocm_` in place of `cuda_`, on `RocmBuf`s, queued on `gpu_rocm`'s stream
so they order with its ops and can be recorded into a `RocmGraph`.

**`gpu_miopen.h`** runs [CNN](#cnn) and [U-Net](#u-net) through MIOpen. The
first convolution of each shape asks MIOpen to time its algorithms on that
call's buffers (`miopenFindConvolutionForwardAlgorithm`) and caches the
fastest one together with its own workspace. Make that first call outside
graph capture. MIOpen has no fused convolution + bias + ReLU for arbitrary
shapes, so the bias add and ReLU run as one HIP kernel after the convolution
(`rocm_bias_channels`).

| Group | Functions |
|---|---|
| Layers | `rocm_conv2d_upload(&Conv2D)` → `RocmConv2D`, `rocm_conv2d_free`, `rocm_conv_out_dim` |
| Queued ops | `rocm_conv2d(&layer, x, y, batch, h, w)`, `rocm_conv2d_relu`, `rocm_maxpool2d`, `rocm_avgpool2d`, `rocm_concat_channels` (one kernel for the whole batch); in `gpu_rocm.h`: `rocm_upsample2x_nearest`, `rocm_upsample2x_concat`, `rocm_copy` |
| U-Net | `rocm_unet_new(&UNet, batch, h, w)` → `RocmUNet`, `rocm_unet_forward(&g, x, y)` (each decoder step upsamples straight into its concat buffer), `rocm_unet_free` |
| Host arrays | `conv2d_forward_rocm`, `unet_forward_rocm`, falling back to the CPU when no GPU is usable |

**`fft_rocm.h`** runs the transforms through hipFFT (rocFFT underneath), for
any length. Forward transforms are unnormalized and inverse ones include 1/N.

| Group | Functions |
|---|---|
| Host arrays, `double` | `rocm_fft`, `rocm_ifft`, `rocm_fft_batch(data, n, batch, inverse)`, `rocm_fft2d`, `rocm_ifft2d` |
| Queued, fp32 `RocmBuf` | `rocm_fft_c2c(in, out, n, batch, inverse)`, `rocm_fft_r2c`, `rocm_fft_c2r` (may overwrite its input) |

`tests/stdlib/rocm/rocm_dnn_fft_check.sc` runs the same checks as the CUDA
suite against the CPU code (43 checks on an MI100): convolutions at stride 1
and 2, batched, with ReLU and replayed from a graph; pooling, upsample and
concat; a whole U-Net; and every FFT form.

On the MI100, one 64-channel convolution takes 0.027 ms against PyTorch-ROCm's 0.063 ms and the batch-1 U-Net 0.227 ms against 0.315 ms; at batch 16–32 the two are within 2%. Device-resident FFTs are 1.4–1.7× faster than through PyTorch. See the [benchmarks](/benchmarks#mi100-conv-fft).

### Host-array API (MPS, CUDA, SPIR-V, WebGPU)

Each backend exposes the same family of functions over plain `float` arrays, prefixed `mps_`, `cuda_`, `spirv_` or `webgpu_`. Each uploads, runs, and copies back in one call, and returns 1 on success or 0 on failure (no device, compile error), leaving `out` untouched. ROCm's equivalents (`rocm_*_f32`) are listed in its table above.

```c
// <p> = mps | cuda | spirv | webgpu
int <p>_available();

int <p>_add_f32(const &float a, const &float b, &float out, unsigned long n);   // also sub, mul, div, pow
int <p>_relu_f32(const &float a, &float out, unsigned long n);                  // also log, exp, sqrt
int <p>_scale_f32(const &float a, float k, &float out, unsigned long n);
int <p>_sum_f32(const &float a, &float out, unsigned long n);                   // out[0] = sum
int <p>_matmul_f32(const &float a, const &float b, &float out,
                   unsigned long M, unsigned long K, unsigned long N);          // [M,K] x [K,N]
int cuda_matmul_f32_blas(const &float a, const &float b, &float out,
                         unsigned long M, unsigned long K, unsigned long N);    // cuBLAS
```

Host arrays are passed as references to their first element; device buffers below are opaque `&void` handles, since their memory belongs to the GPU driver.

**Persistent buffers** keep weights on the device across calls, so a training loop doesn't re-upload them:

```c
?&void <p>_upload_persistent(const &float data, unsigned long bytes);   // null on failure
void   <p>_release_persistent(&void buf);                               // MPS: (&void buf, unsigned long bytes)
int   <p>_persistent_available();                                      // cuda, spirv, webgpu
int    <p>_matmul_f32_persistent(&void bufA, &void bufB, &float out,
                                unsigned long M, unsigned long K, unsigned long N); // mps, cuda, webgpu
int    cuda_sgd_update_f32(&void devW, &void devGrad, float lr, unsigned long n);    // in place
int    webgpu_sgd_update_f32(&void bufW, &void bufGrad, float lr, unsigned long n);
```

**Half precision** (MPS, CUDA, WebGPU): upload as fp16/bf16, compute natively, read back raw 16-bit values (convert with `float16.h` below). ROCm keeps 16-bit data in `RocmHalfBuf`s instead; see its table above.

```c
?&void mps_upload_f16_persistent(const &float data, unsigned long n);    // also _bf16; cuda_ and webgpu_ (f16) too
int    mps_matmul_f16_persistent(&void bufA, &void bufB, &unsigned short out,
                                unsigned long M, unsigned long K, unsigned long N); // also _bf16; cuda_, webgpu_
int    cuda_sgd_update_f16(&void devW, &void devGrad, float lr, unsigned long n);    // also _bf16; webgpu_ (f16)
int   mps_matmul_f16(const &float a, const &float b, &float out,
                     unsigned long M, unsigned long K, unsigned long N);   // also mps_matmul_bf16
int   mps_relu_f16(const &float a, &float out, unsigned long n);           // also mps_relu_bf16
```

**MPS batching.** Between `mps_batch_begin()` and `mps_batch_end()`, MPS ops encode onto one shared command buffer instead of each committing and waiting, so a fused forward + loss + backward step runs as one GPU submission. `*_chained` variants take the previous op's output buffer (`mps_batch_last_output_buffer()`) as input without a round trip through host memory.

```c
void  mps_batch_begin();
void  mps_batch_end();                      // commit, wait, run finalizers
int   mps_batch_is_active();
generic<T> void mps_batch_register_finalize(fn void(?&T) finalizeFn, ?&T ctx);  // runs after mps_batch_end's wait
?&void mps_batch_last_output_buffer();

// Transposed matmuls for backward passes: abt = A·Bᵀ, atb = Aᵀ·B.
int mps_matmul_abt_f32(const &float a, const &float b, &float out, unsigned long M, unsigned long N, unsigned long K);
int mps_matmul_atb_f32(const &float a, const &float b, &float out, unsigned long M, unsigned long K, unsigned long N);
int mps_relu_backward_f32(const &float a, const &float selfGrad, &float out, unsigned long n);

// Chained forms (first operand already on the GPU), plus fp16/bf16 versions:
//   mps_matmul_f32_chained, mps_relu_f32_chained, mps_sub_f32_chained, mps_mul_f32_chained,
//   mps_square_f32_chained, mps_sum_f32_chained, mps_scale_f32_chained, mps_gated_gelu_f32_chained,
//   mps_matmul_abt_f32_chained, mps_matmul_atb_f32_chained, mps_matmul_atb_f32_chained_ab,
//   mps_relu_backward_f32_chained, mps_relu_backward_f32_chained_ab, mps_matmul_abt_f32_persistent,
//   mps_sgd_update_f32_chained;
//   mps_relu_{f16,bf16}_chained, mps_sub_{f16,bf16}_chained, mps_scale_{f16,bf16}_chained,
//   mps_matmul_atb_{f16,bf16}_chained_ab, mps_matmul_abt_{f16,bf16}_persistent,
//   mps_relu_backward_{f16,bf16}_chained_ab, mps_sgd_update_{f16,bf16}_chained
```

The `_ab` suffix means both inputs are already GPU buffers. See `std/ml/gpu_mps.h` for each one's argument order.

### Tensor wrappers

Autograd `Tensor` ops that run their forward pass on a backend and reuse the CPU backward. Each falls back to the CPU when its device is unavailable.

| Header | Functions | Device |
|--------|-----------|--------|
| `tensor_gpu.h` | `tensor_{add,sub,mul,scale,relu,square,sum,matmul}_gpu` | MPS |
| `tensor_cuda.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_cuda`, `tensor_matmul_cuda_blas` | CUDA |
| `tensor_rocm.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_rocm`, `tensor_matmul_rocm_blas` | ROCm |
| `tensor_spirv.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_spirv` | Vulkan |
| `tensor_webgpu.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_webgpu` | WebGPU |
| `tensor_vdsp.h` | `tensor_{add,sub,mul,sum}_vdsp` | Apple Accelerate vDSP (`-framework Accelerate`) |
| `tensor_blas.h` | `tensor_matmul_blas` | CBLAS (Accelerate / OpenBLAS) |
| `tensor_auto.h` | `tensor_{add,sub,mul,sum,matmul}_auto` | picks naive / vDSP / BLAS by size |

`tensor_gpu.h` also controls MPS batching and persistence at the tensor level:

```c
void tensor_gpu_batch_begin();
void tensor_gpu_batch_end();
void tensor_gpu_mark_persistent(const &Tensor t);   // keep t's data resident on the GPU
void tensor_gpu_release_all_persistent();
```

`tensor_auto.h` is CPU-only on purpose: across every size benchmarked (matmul from 2×2×2 to 128×128×128, and a 128×512×1024×256 training step) BLAS never lost to the naive loop and MPS never beat BLAS, so "auto" doesn't pick the GPU. Ask for it explicitly with `tensor_matmul_on(..., DEVICE_MPS)`.

### Backend status {#backend-status}

| Backend | Elementwise ops | Matmul (std kernel) | Matmul (vendor BLAS) | Status |
|---|---|---|---|---|
| ROCm / HIP | real (HIP C++, compiled at first use by hiprtc) | real (64×64 LDS-tiled SGEMM) | real (rocBLAS) | **measured** on MI100: 29 checks in `tests/stdlib/rocm`, and the [MLP benchmarks](/benchmarks#machine-learning-small-mlp-training-and-inference) |
| CUDA | real (CUDA C++, compiled at first use by NVRTC) | real (64×64 shared-memory-tiled SGEMM) | real (cuBLAS; cuBLASLt for GEMM + ReLU) | **measured** on H200: 110 checks in `tests/stdlib/cuda` (including cuDNN and cuFFT), the [MLP benchmarks](/benchmarks#machine-learning-small-mlp-training-and-inference) and the [conv/FFT benchmarks](/benchmarks#h200-conv-fft) |
| Vulkan/SPIR-V | always returns 0 | always returns 0 | n/a | SPIR-V is binary IR; no glslc/glslangValidator in the test environment |
| WebGPU | real (WGSL, text IR) | real (WGSL) | n/a | unmeasured — no wgpu-native/Dawn library in the test environment |

SPIR-V kernels are binary IR, so the Vulkan backend needs a SPIR-V compiler (glslc/glslangValidator) to be complete; WebGPU takes text IR (WGSL) and is written in full, but no wgpu-native/Dawn library was available to run it.

## fp16 / bf16 {#fp16-bf16}

No native 16-bit float in SafeC's type system (`Type.h`'s `TypeKind` has only `Float32`/`Float64` — a real compiler feature, not a stdlib change). Instead, the standard workaround: fp16/bf16 carried as raw bits in `unsigned short`, with explicit correctly-rounded (round-to-nearest-even) conversion to/from `float`. On top of that sit half-precision tensors and mixed-precision training (`tensor_half.h`, below), and native `half`/`bfloat` GPU compute on MPS, CUDA, WebGPU and ROCm (see [Host-array API](#host-array-api-mps-cuda-spir-v-webgpu)).

| Check | Result |
|---|---|
| Known bit patterns (fp16: 1.0, -1.0, 0.0, -0.0, 2.0, 0.5, max normal 65504, overflow→inf, smallest subnormal, inf; bf16: 1.0, -1.0, 0.0, 2.0, π) | all exact |
| fp16 subnormal idempotence sweep (every mantissa 1–1023, `fp16→f32→fp16`) | 1023/1023 exact |
| fp16 normal-range idempotence sweep (every exponent × sampled mantissas, 330 patterns) | 330/330 exact |
| fp16 round-trip on representable values | exact |
| bf16 round-trip (lossy by construction — 7 mantissa bits) | within ~0.4% relative error |

```c
#include <std/ml/float16.h>

unsigned short f32_to_fp16(float v);          // round to nearest even
float          fp16_to_f32(unsigned short h);
unsigned short f32_to_bf16(float v);
float          bf16_to_f32(unsigned short b);

void f32_to_fp16_bulk(const &float src, &unsigned short dst, unsigned long n);
void fp16_to_f32_bulk(const &unsigned short src, &float dst, unsigned long n);
void f32_to_bf16_bulk(const &float src, &unsigned short dst, unsigned long n);
void bf16_to_f32_bulk(const &unsigned short src, &float dst, unsigned long n);
```

### Half-precision tensors — `tensor_half.h` {#half-precision-tensors}

```c
#include <std/ml/tensor_half.h>
```

Two layers:

- **`HalfTensor`** stores a 1D/2D tensor in fp16 or bf16 — half the memory of a float32 `Tensor`. Its ops widen operands to float32, compute, and round the result back to the storage format, the same arithmetic a GPU half path performs; `half_matmul` accumulates each dot product in float32 and rounds once, like tensor cores. No autograd on this layer.
- **Mixed precision on float32 `Tensor`s, with autograd.** `tensor_cast` rounds values to fp16/bf16 precision in the forward pass and passes gradients through unchanged (the straight-through estimator), so a graph can compute in reduced precision while weights and gradients stay float32. `LossScaler` is fp16 training's dynamic loss scaling; bf16 shares float32's exponent range and doesn't need it.

```c
#define TENSOR_FP16 1
#define TENSOR_BF16 2

struct HalfTensor {
    &heap unsigned short data;     // fp16 or bf16 bits, row-major
    unsigned long        shape[2];
    unsigned long        ndim;     // 1 or 2
    unsigned long        size;
    int                  dtype;    // TENSOR_FP16 or TENSOR_BF16

    float at1(unsigned long i) const;                   // widened to float32
    float at2(unsigned long r, unsigned long c) const;
    void  set1(unsigned long i, float v);               // rounded to dtype
    void  set2(unsigned long r, unsigned long c, float v);
    void  free();
};

float          half_round(float v, int dtype);          // nearest dtype value, as float32
unsigned short half_encode(float v, int dtype);
float          half_decode(unsigned short bits, int dtype);

struct HalfTensor half_tensor_new_1d(unsigned long n, int dtype);           // zero-filled
struct HalfTensor half_tensor_new_2d(unsigned long rows, unsigned long cols, int dtype);
struct HalfTensor tensor_to_half(const &Tensor t, int dtype);                // round to nearest even
&Tensor           half_to_tensor(const &HalfTensor h, int requiresGrad);     // exact

// Shapes and dtypes must match; otherwise the result is empty (size 0).
struct HalfTensor half_add(const &HalfTensor a, const &HalfTensor b);
struct HalfTensor half_sub(const &HalfTensor a, const &HalfTensor b);
struct HalfTensor half_mul(const &HalfTensor a, const &HalfTensor b);       // elementwise
struct HalfTensor half_scale(const &HalfTensor a, float k);
struct HalfTensor half_relu(const &HalfTensor a);
struct HalfTensor half_matmul(const &HalfTensor a, const &HalfTensor b);    // float32 accumulate
float             half_sum(const &HalfTensor a);                            // float32 accumulate

&Tensor tensor_cast(const &Tensor a, int dtype);                            // straight-through gradient
&Tensor tensor_matmul_lowp(const &Tensor a, const &Tensor b, int dtype);    // cast(cast(a) @ cast(b))

struct LossScaler {
    float scale;            // multiply the loss by this before tensor_backward()
    float growthFactor;     // 2.0
    float backoffFactor;    // 0.5
    int   growthInterval;   // 2000 clean steps
    int   goodSteps;
};
struct LossScaler loss_scaler_new();                       // scale 65536 (PyTorch GradScaler's defaults)
int  loss_scaler_unscale(&LossScaler s, &Tensor p);        // divide p.grad by scale; 0 if any grad is inf/NaN
void loss_scaler_update(&LossScaler s, int allFinite);     // grow after clean steps, back off after overflow
```

A `HalfTensor`'s `data` has exactly the layout the GPU half-precision functions read and write (`mps_upload_f16_persistent` and friends).

Storage and arithmetic in bf16:

```c
#include <std/ml/tensor_half.h>
#include <std/io.h>

int main() {
    // Store weights in bf16 (half the memory), compute a matmul in bf16.
    struct HalfTensor w = std::half_tensor_new_2d(2UL, 2UL, TENSOR_BF16);
    struct HalfTensor x = std::half_tensor_new_2d(2UL, 1UL, TENSOR_BF16);
    w.set2(0UL, 0UL, 0.5f);  w.set2(0UL, 1UL, -1.0f);
    w.set2(1UL, 0UL, 2.0f);  w.set2(1UL, 1UL, 0.25f);
    x.set1(0UL, 3.0f);       x.set1(1UL, 4.0f);
    struct HalfTensor y = std::half_matmul(&w, &x);      // f32 accumulate, bf16 result
    std::println_float((double)y.at1(0UL));             // -2.5
    std::println_float((double)y.at1(1UL));             // 7

    // Back to float32 for anything that needs autograd.
    &Tensor yf = std::half_to_tensor(&y, 0);
    yf.free();
    y.free(); x.free(); w.free();
    return 0;
}
```

Mixed-precision training — fp16 forward, float32 master weights, skipped steps on overflow:

```c
#include <std/ml/tensor_half.h>
#include <std/collections/slice.h>

// One mixed-precision SGD step: fp16 forward, float32 master weights,
// dynamic loss scaling so small fp16 gradients don't flush to zero.
int train_step(&Tensor w, const &Tensor x, const &Tensor target, &LossScaler scaler, float lr) {
    &Tensor y = std::tensor_matmul_lowp(x, w, TENSOR_FP16);
    &Tensor diff = std::tensor_sub(y, target);
    &Tensor loss = std::tensor_sum(std::tensor_mul(diff, diff));
    std::tensor_backward(std::tensor_scale(loss, scaler.scale));

    int finite = std::loss_scaler_unscale(scaler, w);
    if (finite) {
        struct Slice<float> g = std::slice_of(w.grad, w.size);
        for (unsigned long i = 0UL; i < w.size; i = i + 1UL) {
            w.set1(i, w.at1(i) - lr * *g.at(i));
        }
    }
    std::loss_scaler_update(scaler, finite);
    std::tensor_zero_grad(w);
    return finite;
}

int main() {
    float wv[2] = { 0.1f, 0.2f };
    float xv[2] = { 1.0f, 2.0f };
    float tv[1] = { 1.0f };
    &Tensor w = std::tensor_from_2d(&wv[0], 2UL, 1UL, 1);
    &Tensor x = std::tensor_from_2d(&xv[0], 1UL, 2UL, 0);
    &Tensor t = std::tensor_from_2d(&tv[0], 1UL, 1UL, 0);
    struct LossScaler scaler = std::loss_scaler_new();
    int ok = 1;
    for (int i = 0; i < 20; i = i + 1) ok = ok & train_step(w, x, t, &scaler, 0.05f);
    float pred = w.at1(0UL) + w.at1(1UL) * (float)2.0;
    return (ok && pred > (float)0.95 && pred < (float)1.05) ? 0 : 1;
}
```


## Device selection {#device-selection}

Every backend names its op explicitly (`tensor_matmul` vs `_blas` vs `_gpu` vs `_cuda` vs `_rocm`) — precise, but awkward for a caller that wants to pick a device once. `tensor_matmul_on(a, b, device)` / `tensor_relu_on(a, device)` dispatch over a `Device` enum instead — verified bit-identical across CPU/CPU+BLAS/MPS:

| Device | Y[0][0] | Y[0][1] |
|---|---|---|
| CPU | 0.900000 | 1.300000 |
| CPU + BLAS | 0.900000 | 1.300000 |
| MPS | 0.900000 | 1.300000 |

```c
#include <std/ml/tensor_dispatch.h>

enum Device {
    DEVICE_CPU = 0, DEVICE_CPU_BLAS = 1, DEVICE_MPS = 2,
    DEVICE_CUDA = 3, DEVICE_CUDA_BLAS = 4,
    DEVICE_ROCM = 5, DEVICE_ROCM_BLAS = 6,
    DEVICE_SPIRV = 7, DEVICE_WEBGPU = 8,
};
&Tensor tensor_matmul_on(const &Tensor a, const &Tensor b, enum Device device);
&Tensor tensor_relu_on(const &Tensor a, enum Device device);

#include <std/ml/transformer_dispatch.h>
&Tensor jit_block_forward_on(const &JiTBlock block, const &Tensor x, enum Device device);
&Tensor jit_forward_on(const &Tensor patches, const &Tensor WPatchEmbed, const &Tensor cond,
                       struct Slice<JiTBlock> blocks, enum Device device);
```

Building this exposed a real name collision: `activations.sc` (forward-only ops) and `tensor_nn.sc` (autograd ops, needed by every GPU backend) both defined `tensor_sigmoid`/`tensor_relu`/etc. — no program could link both, so no `activations.sc`-based layer could use a GPU backend. Fixed by suffixing `activations.h`'s forward-only functions with `_fwd` and updating `attention.sc`/`transformer.sc`/`rnn.sc`'s call sites.

`jit_block_forward_on(block, x, device)` threads device selection through a real layer (JiTBlock's Q/K/V/output projections + FFN) — verified bit-identical to the reference across CPU/CPU+BLAS/MPS:

| Path | y[0] | y[1] | y[2] |
|---|---|---|---|
| `jit_block_forward` (reference) | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU_BLAS)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_MPS)` | -0.479727 | -0.589425 | -0.537808 |

Not yet device-routed: attention's internal QK^T/softmax/·V matmuls, `DiTBlock`, `cnn.sc`.


## LLM orchestration

```c
#include <std/ml/llm.h>
```

- **`LlmClient`** — speaks the OpenAI-chat-completions wire shape (what
  vLLM's own server, and most local/hosted LLM servers, implement).
  `llm_chat()` POSTs to `<host>:<port>/v1/chat/completions`.
- **`PromptTemplate`** — `prompt_template_render(tmpl, vars)` does
  `{name}` substitution against a `Value` object.
- **`Chain`** — a fixed `Value -> Value` step sequence.
- **Graph executor** — LangGraph-style: named nodes transform a shared
  `Value` state, connected by fixed or conditional (router-function)
  edges, run until `"__end__"`.
- **`Tracer`** — an in-memory, JSON-dumpable log of named
  input/output/timing events (LangSmith-style).

```c
struct LlmMessage { struct String role; struct String content; };
struct LlmClient  { struct String host; unsigned short port; struct String model; struct String apiKey; };

struct LlmClient llm_client_new(const &char host, unsigned short port,
                                const &char model, const &char apiKey);
// The assistant's reply text; *ok = 0 on transport or response-shape failure.
struct String llm_chat(&LlmClient client, &Vec<struct LlmMessage> messages, ?&int ok);

struct String prompt_template_render(const &char tmpl, const &Value vars);   // {name} -> vars.name

typedef fn struct Value(const &Value input) ChainStepFn;
struct Chain chain_new();
void         chain_add_step(&Chain c, ChainStepFn step);
struct Value chain_run(&Chain c, const &Value input);
void         chain_free(&Chain c);

typedef fn struct Value(const &Value state) GraphNodeFn;
typedef fn struct String(const &Value state) GraphRouterFn;   // name of the next node, or "__end__"
struct Graph graph_new(const &char entryNode);
void         graph_add_node(&Graph g, const &char name, GraphNodeFn nodeFn, const &char nextNode);
void         graph_add_conditional_node(&Graph g, const &char name, GraphNodeFn nodeFn, GraphRouterFn router);
struct Value graph_run(&Graph g, struct Value initialState, int maxSteps);
void         graph_free(&Graph g);

struct TraceEvent { struct String name; struct String inputJson; struct String outputJson; double durationMs; };
struct Tracer tracer_new();
void          tracer_record(&Tracer t, const &char name, const &Value input,
                            const &Value output, double durationMs);
struct String tracer_to_json(const &Tracer t);
void          tracer_free(&Tracer t);
```

All five verified end-to-end, including `LlmClient` against a real local
mock HTTP server standing in for an LLM endpoint (the same technique
`std/rpc/server_fn.h`'s own tests use — nothing here calls out to a real
hosted LLM API).
