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

Stateful types (models, layers, optimizers, data loaders, agents, device
nets and buffers) are used through [methods](/reference/functions#struct-methods):
`net.forward(x)`, `v.loss(x, y)`, `opt.step()`, `buf.free()`.
Constructors are [static methods](/reference/functions#static-methods):
`Mlp::new(...)`, `Tensor::new_2d(...)`, `Dataset::moons(...)`,
`RocmNet::from_mlp(&net, 128UL)`, `TrainConfig::default()`. An operation on
one tensor is a method of that tensor — `x.relu()`, `logits.log_softmax()`,
`loss.backward()`, `t.fill(0.0f)` — const unless it changes the tensor in
place; operations on two tensors stay functions (`tensor_matmul(a, b)`). What every
model can do — add its parameters to an optimizer, count them, save and
load them, copy them, train on a built-in loss — is written once as
generic functions over [traits](#modules) the models satisfy:
`model_add_params(&net, &opt)`, `model_save_file(&net, path)`,
`module_fit(&net, TRAIN_LOSS_CE, &opt, &data, &cfg)`. The step functions
passed to `rocm_trainer_fit` as values (`rocm_net_ce_step`) stay functions.

---

## Tensor

```c
#include <std/ml/tensor.h>
```

CPU tensors (1D/2D, `float` — float32, like PyTorch's default and the
only precision Metal supports) with reverse-mode autograd — PyTorch-style
ergonomics: build a computation graph implicitly by calling ops on
`requiresGrad` tensors, then call `Tensor::backward()`. For fp16/bf16 storage
and mixed-precision training, see [Half-precision tensors](#half-precision-tensors).

```c
&Tensor x = Tensor::from_1d(values, 3UL, /*requiresGrad=*/1);
&Tensor y = tensor_mul(x, x).sum();  // y = sum(x^2)
y.backward();                               // x.grad now holds 2x
```

Ops: `tensor_add`/`tensor_sub`/`tensor_mul`/`Tensor::scale`/`tensor_matmul`/
`Tensor::relu`/`Tensor::sum`. Verified against hand-computed gradients and a
real finite-difference check on a matmul→relu→sum chain.

`tensor_matmul` and its backward pass run on [`sgemm`](/stdlib/simd#gemm):
packed SIMD kernels at the CPU's best [SIMD level](/stdlib/simd#cpu-levels)
(AVX-512 down to SSE2, SVE or Neon), split across the
[thread pool](/stdlib/sync#parallel) for larger products. With a CPU
vendor BLAS linked — AOCL-BLAS on AMD, oneMKL on Intel, Accelerate on
Apple, which safeguard's [`[build] blas`](/advanced/safeguard#linking-external-libraries-and-library-output)
does by default when it finds one — the vendor's kernels do the
multiplying. The backward pass accumulates straight into the existing
gradients. Set `SAFEC_NUM_THREADS` to limit the thread count.

Elementwise ops (`tensor_add`/`sub`/`mul`/`scale`/`fill`, ReLU and every
backward accumulation) are vectorized loops that split across the pool from
65,536 elements. The transcendental activations — sigmoid, tanh, GELU,
SiLU, ELU, GLU, SwiGLU — and softmax use [`vmath.h`](/stdlib/simd#vmath)'s
vectorized `exp`/`tanh`/sigmoid, so they no longer call libm per element.

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

static &Tensor Tensor::new_1d(unsigned long n, int requiresGrad);
static &Tensor Tensor::new_2d(unsigned long rows, unsigned long cols, int requiresGrad);
static &Tensor Tensor::from_1d(const &float values, unsigned long n, int requiresGrad);
static &Tensor Tensor::from_2d(const &float values, unsigned long rows, unsigned long cols, int requiresGrad);
&Tensor Tensor::zeros_like() const;
void    Tensor::fill(float v);   // in place

&Tensor tensor_add(const &Tensor a, const &Tensor b);
&Tensor tensor_sub(const &Tensor a, const &Tensor b);
&Tensor tensor_mul(const &Tensor a, const &Tensor b);   // elementwise
&Tensor Tensor::scale(float k) const;
&Tensor tensor_matmul(const &Tensor a, const &Tensor b);
&Tensor Tensor::sum() const;

void Tensor::backward();    // t: a scalar (a loss); accumulates every ancestor's grad
void Tensor::zero_grad();

// Gradient recording: the program's autograd state. Disabled (inference),
// composed ops record no graph (leaf flags unchanged).
struct GradGuard { int was; void restore(); };
struct Autograd {
    int  enabled() const;
    void set_enabled(int enabled);
    struct GradGuard no_grad();   // disables recording; restore() puts the mode back
};
static &Autograd Autograd::get();

struct GradGuard g = Autograd::get().no_grad();
// ... forward passes record nothing ...
g.restore();
```

### Tensor arenas {#tensor-arena}

Every op allocates its output, so a training loop does a `malloc` (and, for fresh memory, page faults) per op per step. On a small MLP that cost more than the arithmetic. A `TensorArena` is a chunked bump allocator, 64-byte aligned, for one step's temporaries. Between `TensorArena::begin` and `tensor_arena_end`, every tensor created on that thread comes from the arena: its data, shape and gradient. `TensorArena::reset` drops them all at once and keeps the memory, already faulted in, for the next step.

```c
static struct TensorArena TensorArena::new(unsigned long chunkBytes);   // 0 = 16 MiB chunks
void TensorArena::begin();                                       // this thread's new tensors come from this arena
void tensor_arena_end();                                         // back to the heap
void TensorArena::reset();                                       // drop every tensor in it, keep the memory
void TensorArena::free();
unsigned long TensorArena::used() const;   // bytes since the last reset

struct TensorArena step = TensorArena::new(0UL);
while (s < steps) {
    step.begin();
    // ... forward, Tensor::backward, optimizer update ...
    tensor_arena_end();
    step.reset();   // this step's tensors are gone
    s = s + 1;
}
step.free();
```

Tensors created outside the arena, such as parameters, stay on the heap, and so do their gradients, so they survive resets. [`FeatureMap`](#cnn)s created while an arena is active come from it too, every intermediate inside `UNet::forward` included; `FeatureMap::free` leaves those to the reset. Each tensor records its `owner`. `Tensor::free()` on an arena tensor does nothing, and a tensor must not be used after its arena is reset.

### Autograd ops and optimizer — `tensor_nn.h`

```c
#include <std/ml/tensor_nn.h>
```

Activations with backward passes (the ones every GPU backend uses), 1D pooling, and Adam.

```c
&Tensor Tensor::relu() const;
&Tensor Tensor::sigmoid() const;
&Tensor Tensor::tanh() const;
&Tensor Tensor::softmax() const;
&Tensor Tensor::elu(float alpha) const;
&Tensor Tensor::gelu() const;
&Tensor Tensor::silu() const;
&Tensor Tensor::glu() const;      // split last axis into [a1, a2]: a1 * sigmoid(a2)
&Tensor Tensor::swiglu() const;   // silu(a1) * a2 — output has half the columns
&Tensor Tensor::maxpool1d(unsigned long kernel, unsigned long stride) const;
&Tensor Tensor::avgpool1d(unsigned long kernel, unsigned long stride) const;

struct AdamState {
    &heap float m; &heap float v;
    unsigned long size;
    float lr; float beta1; float beta2; float eps;
    unsigned long t;
};
static struct AdamState AdamState::new(unsigned long size, float lr, float beta1, float beta2, float eps);
static struct AdamState AdamState::new_default(unsigned long size, float lr);   // beta1 0.9, beta2 0.999, eps 1e-8 (paper defaults)
void AdamState::step(&Tensor param);                               // uses param.grad
void AdamState::free();
```

## Activations

```c
#include <std/ml/activations.h>
```

Forward-only elementwise activations (no autograd — for inference layers;
the autograd versions are in `tensor_nn.h` above);
`Tensor::layernorm_rows` (row-wise mean-0/var-1 normalization, no affine —
apply your own scale/shift for AdaLN-style conditioning, see DiT below);
`tensor_residual_add` (a named `tensor_add` alias for residual/skip-
connection call sites).

```c
&Tensor Tensor::relu_fwd() const;
&Tensor Tensor::sigmoid_fwd() const;
&Tensor Tensor::tanh_fwd() const;
&Tensor Tensor::silu_fwd() const;
&Tensor Tensor::gelu_fwd() const;
&Tensor Tensor::layernorm_rows(float eps) const;
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
&Tensor Tensor::softmax_rows() const;
&Tensor Tensor::transpose() const;
&Tensor Tensor::slice_cols(unsigned long startCol, unsigned long numCols) const;
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

Flash and linear attention also run on Apple GPUs, in bf16 and multi-head
with grouped-query K/V — see [MPS attention and transformer ops](#mps-attention).

**`MultiHeadAttention`** is a self-attention layer with its own
projections, `mha_forward(X Wq, X Wk, X Wv, numHeads) Wo`, forward only
like the ops above. It is a [`Module`](#modules).

```c
static struct MultiHeadAttention MultiHeadAttention::new(unsigned long dModel, unsigned long numHeads, &Rng rng);
&Tensor MultiHeadAttention::forward(const &Tensor X) const;   // [seqLen, dModel] -> [seqLen, dModel]
void    MultiHeadAttention::free();
```

## Advanced attention

```c
#include <std/ml/attention_advanced.h>
```

- **`Mla`/`EgMla`** — Multi-head Latent Attention (DeepSeek-V2):
  compresses K/V into a shared low-rank latent before up-projecting back
  to full width, saving KV-cache memory. `EgMla` shares one latent across
  independent per-group up-projections (grouped-query attention's
  KV-sharing layered onto MLA's compression). Both are layers holding
  their weights, and [`Module`](#modules)s.
- **`windowed_attention_forward`** — Shifted-Window (Swin) attention
  adapted to 1D sequences: partitions into fixed-size local windows,
  with a cyclic shift (`shift` param) so the next layer's windows
  straddle this layer's boundaries.
- **`GatedDeltaNet::forward`** — Gated DeltaNet (Yang et al. 2024): a
  linear-recurrent attention replacement using a delta-rule state update
  (write the residual between the new value and what the state already
  predicts) plus a scalar decay gate, computed via a sequential scan.

Verified via self-consistency checks against manual per-group/per-window
reconstruction, and a hand-computed 2-step state trace for Gated
DeltaNet.

```c
struct Mla { &Tensor Wq; &Tensor Wdkv; &Tensor Wuk; &Tensor Wuv; unsigned long numHeads; };
static struct Mla Mla::new(unsigned long dModel, unsigned long dLatent, unsigned long numHeads, &Rng rng);
&Tensor Mla::forward(const &Tensor X) const;           // [seqLen, dModel] -> [seqLen, dModel]

struct EgMla { &Tensor Wq; &Tensor Wdkv; struct Vec<&Tensor> Wuk; struct Vec<&Tensor> Wuv;  // one per group
               unsigned long headsPerGroup; };
static struct EgMla EgMla::new(unsigned long dModel, unsigned long dLatent, unsigned long numGroups, unsigned long headsPerGroup, &Rng rng);
&Tensor EgMla::forward(const &Tensor X) const;
&Tensor windowed_attention_forward(const &Tensor Q, const &Tensor K, const &Tensor V,
                                   unsigned long windowSize, unsigned long shift);

struct GatedDeltaNet { &Tensor Walpha; &Tensor Wbeta; unsigned long keyDim; unsigned long valueDim; };
static struct GatedDeltaNet GatedDeltaNet::new(unsigned long dModel, unsigned long keyDim, unsigned long valueDim);
&Tensor GatedDeltaNet::forward(const &Tensor X, const &Tensor Q, const &Tensor K, const &Tensor V) const;
void    GatedDeltaNet::free();
```

The weights are public fields: to load pretrained ones, assign them (an `EgMla`'s per-group up-projections are `Vec`s whose length is the group count). `free()` releases them.

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
static struct RNNCell  RNNCell::new(unsigned long inputSize, unsigned long hiddenSize);
&Tensor         RNNCell::forward(const &Tensor x_t, const &Tensor h_prev) const;
void            RNNCell::free();

static struct GRUCell  GRUCell::new(unsigned long inputSize, unsigned long hiddenSize);
&Tensor         GRUCell::forward(const &Tensor x_t, const &Tensor h_prev) const;
void            GRUCell::free();

static struct LSTMCell LSTMCell::new(unsigned long inputSize, unsigned long hiddenSize);
// Returns h_t; the new cell state is written through c_out.
&Tensor         LSTMCell::forward(const &Tensor x_t, const &Tensor h_prev, const &Tensor c_prev, &&Tensor c_out) const;
void            LSTMCell::free();

struct XLSTMState { &Tensor h; &Tensor c; &Tensor n; &Tensor m; };   // hidden, cell, normalizer, stabilizer
static struct XLSTMCell  XLSTMCell::new(unsigned long inputSize, unsigned long hiddenSize);
struct XLSTMState XLSTMCell::forward(const &Tensor x_t, const &XLSTMState prev) const;
void              XLSTMCell::free();
void              XLSTMState::free();
```

Each cell's weight tensors are public fields (`W_ih`, `W_hh`, … — named per gate) so you can load trained weights into them.

## State space models (Mamba) {#ssm}

```c
#include <std/ml/ssm.h>
```

A state space model mixes a sequence through a linear recurrence per
channel instead of attention. Each of D channels carries an N-value
hidden state that every token updates and reads:

`h_t = exp(Δ_t·A)·h_{t−1} + Δ_t·B_t·x_t`, `y_t = C_t·h_t + D·x_t`

A sequence costs O(T·D·N). Decoding one token costs O(D·N) with a state
of fixed size, however long the context, so there is no KV cache. Mamba
(Gu & Dao 2023, "S6") makes the step Δ and the B and C projections
functions of the input. This lets the model choose, token by token, what
to keep and what to forget.

`ssm_selective_scan` is the scan alone. `Mamba` is one Mamba-1 mixer
block, without the surrounding RMSNorm and residual add:

1. `(x, z) = X·inProj`.
2. A causal depthwise conv1d (kernel `dConv`) and SiLU on x.
3. `(dt, B, C) = x·xProj`.
4. `Δ = softplus(dt·dtProj + dtBias)`.
5. The scan with `A = −exp(Alog)`.
6. Gate with `silu(z)`.
7. `outProj`.

Weights are `[in, out]` like the rest of `std::ml`. To load a PyTorch
`nn.Linear`, transpose its weight. The Conv1d weight `[dInner, 1, dConv]`
loads as `[dInner, dConv]`. `Mamba::new` follows the paper's
initialization: `Alog[d][n] = log(n+1)`, `D = 1`, and `dtBias` chosen so
that `softplus(dtBias)` spans [0.001, 0.1].

The convolution and the scan run the channels in parallel on the
[thread pool](/stdlib/sync#parallel). The projections go through
`tensor_matmul`. `Mamba::step` decodes one token and advances a
`MambaState` (the last `dConv − 1` convolution inputs plus the SSM state).
Feeding tokens one at a time through `Mamba::step` gives the same rows as
`Mamba::forward` on the whole sequence, and the tests check this. With
weights copied into HF transformers' `MambaMixer`, the outputs match to
2e-8 absolute, on outputs of about 0.05. Forward only, no autograd.

```c
// Selective scan alone: x, delta [T, D]; A [D, N]; B, C [T, N]; Dskip [D] -> y [T, D]
&Tensor ssm_selective_scan(const &Tensor x, const &Tensor delta, const &Tensor A,
                           const &Tensor B, const &Tensor C, const &Tensor Dskip);

struct Mamba {
    &Tensor inProj;   // [dModel, 2 * dInner]
    &Tensor convW;    // [dInner, dConv]
    &Tensor convB;    // [dInner]
    &Tensor xProj;    // [dInner, dtRank + 2 * dState]
    &Tensor dtProj;   // [dtRank, dInner]
    &Tensor dtBias;   // [dInner]
    &Tensor Alog;     // [dInner, dState]; A = -exp(Alog)
    &Tensor Dskip;    // [dInner]
    &Tensor outProj;  // [dInner, dModel]
    unsigned long dModel; unsigned long dInner; unsigned long dState; unsigned long dConv; unsigned long dtRank;
};
static struct Mamba Mamba::new(unsigned long dModel, unsigned long dState, unsigned long dConv, unsigned long expand);
&Tensor      Mamba::forward(const &Tensor X) const;   // [T, dModel] -> [T, dModel]
void         Mamba::free();

struct MambaState { &Tensor conv; &Tensor h; };   // [dInner, dConv-1], [dInner, dState]
static struct MambaState MambaState::new(const &Mamba layer);
&Tensor           Mamba::step(&MambaState state, const &Tensor x) const;   // [1, dModel]
void              MambaState::reset();
void              MambaState::free();

struct Mamba m = Mamba::new(768UL, 16UL, 4UL, 2UL);
&Tensor y = m.forward(prompt);   // prefill
struct MambaState st = MambaState::new(&m);
// ... or decode token by token with a fixed-size state
&Tensor yt = m.step(&st, token);
```

## CNN

```c
#include <std/ml/cnn.h>
```

These layers run forward only. To train convolutional networks, use
[`convnet.h`](#convnet), which has the same operations on autograd tensors.

`Conv2D` (stride + zero-padding, no dilation/grouped conv),
`FeatureMap::maxpool2d`/`FeatureMap::avgpool2d`, `FeatureMap::upsample2x_nearest` (nearest-
neighbor 2x spatial upsample), `concat_channels` (channel-axis
concatenation) — all over a channels-first `FeatureMap` (not `Tensor`,
which tops out at 2D). Verified via hand-computed 3×3-kernel and
pooling-window checks.

3×3, stride-1 layers (with at least 8 input and output channels) use
Winograd F(2×2, 3×3) (Lavin & Gray, arXiv:1509.09308). Each 2×2 block of
outputs comes from a 4×4 input tile with 16 multiplies per channel pair
instead of 36. The transformed tiles and filters turn the work into 16
GEMMs over channels. The input transform, the GEMMs and the output transform
each run on the thread pool, with the input and output transforms working
on eight tiles per vector. Results match the direct sum to about 1e-6
relative. Other layers use im2col:

`Conv2D::forward` is im2col + [`sgemm`](/stdlib/simd#gemm). Each block of
output pixels gathers its receptive fields into a per-thread column buffer
and multiplies it by the weight matrix. Blocks run on the
[thread pool](/stdlib/sync#parallel), so the full im2col matrix is never
built. 1×1 convolutions skip the gather. `Conv2D::relu_forward` applies
the ReLU in the same pass. Pooling, upsampling and concatenation are split
across the pool by channel once a map is large enough.

```c
struct FeatureMap {
    &heap float   data;      // channels × height × width, channels-first
    unsigned long channels;
    unsigned long height;
    unsigned long width;
};
static struct FeatureMap FeatureMap::new(unsigned long channels, unsigned long height, unsigned long width);
void              FeatureMap::free();

static struct Conv2D Conv2D::new(unsigned long inChannels, unsigned long outChannels, unsigned long kH, unsigned long kW, unsigned long stride, unsigned long padding);
struct FeatureMap Conv2D::forward(const &FeatureMap input) const;
struct FeatureMap Conv2D::relu_forward(const &FeatureMap input) const;   // relu(conv), one pass
void              Conv2D::free();

struct FeatureMap FeatureMap::maxpool2d(unsigned long kernel, unsigned long stride) const;
struct FeatureMap FeatureMap::avgpool2d(unsigned long kernel, unsigned long stride) const;
struct FeatureMap FeatureMap::upsample2x_nearest() const;
struct FeatureMap concat_channels(const &FeatureMap a, const &FeatureMap b);
```

### Padded and global pooling, residual blocks {#cnn-residual}

The `_ex` pooling functions add zero padding on every side. The output
side is `(dim + 2·padding − kernel)/stride + 1`. Max pooling skips the
padded positions, as PyTorch's `MaxPool2d` does. Average pooling counts
them as zeros in the divisor when `countIncludePad` is set (PyTorch's
`AvgPool2d` default). Otherwise it divides by the number of positions
inside the plane. `FeatureMap::global_avgpool2d` / `FeatureMap::global_maxpool2d` reduce each
channel's whole plane to one value, giving channels × 1 × 1: the head of
a ResNet-style classifier.

`feature_map_add` is the residual connection, `a + b` element by element,
with an optional ReLU in the same pass. `ResidualBlock` is the ResNet basic
block (He et al. 2016):

`relu(conv2(relu(conv1(x))) + shortcut(x))`

`conv1` is 3×3 with the block's stride. `conv2` is 3×3 with stride 1. Both
use padding 1. The shortcut is `x` itself, or a 1×1 convolution with the
stride when the stride isn't 1 or the channel count changes. There is no
batch norm: fold a trained model's batch norms into the convolutions'
weights and biases before loading them. The tests check padded pooling
against a direct reference, and the block against the composition of its
convolutions.

```c
struct FeatureMap FeatureMap::maxpool2d_ex(unsigned long kernel, unsigned long stride, unsigned long padding) const;
struct FeatureMap FeatureMap::avgpool2d_ex(unsigned long kernel, unsigned long stride, unsigned long padding, int countIncludePad) const;
struct FeatureMap FeatureMap::global_avgpool2d() const;   // channels x 1 x 1
struct FeatureMap FeatureMap::global_maxpool2d() const;
struct FeatureMap feature_map_add(const &FeatureMap a, const &FeatureMap b, int relu);

struct ResidualBlock { struct Conv2D conv1; struct Conv2D conv2; struct Conv2D projection; int hasProjection; };
static struct ResidualBlock ResidualBlock::new(unsigned long inChannels, unsigned long outChannels, unsigned long stride);
struct FeatureMap    ResidualBlock::forward(const &FeatureMap input) const;
void                 ResidualBlock::free();

// ResNet-18 stem and first stage
struct FeatureMap s = stem7x7s2.relu_forward(&image);
struct FeatureMap p = s.maxpool2d_ex(3UL, 2UL, 1UL);
struct FeatureMap r = layer1a.forward(&p);
```

## U-Net

```c
#include <std/ml/unet.h>
```

A fixed 2-level encoder/decoder CNN with skip connections
(Ronneberger et al. 2015) — the classic DDPM-style diffusion denoiser
backbone. Verified via output-shape checks and full self-consistency
against a manual replication of `UNet::forward`'s call sequence.

```c
static struct UNet UNet::new(unsigned long inChannels, unsigned long c1, unsigned long c2, unsigned long outChannels);
struct FeatureMap UNet::forward(const &FeatureMap input);
void UNet::free();
```

## Diffusion {#diffusion}

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
  order-2 variant (`dpm_solver_2_step` takes your model, any
  `DenoiseModel`, and evaluates it a second time at the log-SNR midpoint).
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

// Order-2 solvers call your model again at the midpoint: any struct with
// a predict method returning the predicted noise (eps) or clean sample (x0).
trait DenoiseModel {
    &Tensor predict(const &Tensor x, float sigma);
}
generic<M: DenoiseModel>
&Tensor dpm_solver_2_step(&M model, const &Tensor x_cur, const &Tensor epsPred,
                          float alpha_cur, float sigma_cur, float alpha_next, float sigma_next);
generic<M: DenoiseModel>
&Tensor dpm_solver_pp_2_step(&M model, const &Tensor x_cur, const &Tensor x0Pred,
                             float alpha_cur, float sigma_cur, float alpha_next, float sigma_next);
```

The model is your own struct, holding its weights; the solver calls its `predict` method directly, with no callback or type-erased context.

## Transformer (DiT / JiT)

```c
#include <std/ml/transformer.h>
```

- **`DiTBlock`** — Diffusion Transformer (Peebles & Xie 2023), adaLN-
  style: a conditioning vector produces 6 modulation vectors per block
  (shift/scale/gate for each of the attention and FFN sublayers).
  Verified via a gate=0 exact-identity check (with gates zeroed, the
  block's output must equal its input exactly) and full self-consistency.
- **`JiTBlock`/`JiT`** — a plain pre-LN transformer with
  conditioning added once, up front, to the token embeddings (as
  opposed to DiT's per-layer adaptive modulation) — the architectural
  distinction this implementation draws between the two.

```c
static struct DiTBlock DiTBlock::new(unsigned long dModel, unsigned long dHidden, unsigned long dModelCond, unsigned long numHeads);
&Tensor DiTBlock::forward(const &Tensor x, const &Tensor c) const;   // c = conditioning
void    DiTBlock::free();

static struct JiTBlock JiTBlock::new(unsigned long dModel, unsigned long dHidden, unsigned long numHeads);
&Tensor JiTBlock::forward(const &Tensor x) const;
void    JiTBlock::free();
// The model: patch embedding + conditioning, then the blocks.
struct JiT { &Tensor WPatchEmbed; struct Vec<struct JiTBlock> blocks; };
static struct JiT JiT::new(unsigned long patchDim, unsigned long dModel, unsigned long dHidden, unsigned long numHeads, unsigned long depth);
&Tensor JiT::forward(const &Tensor patches, const &Tensor cond) const;   // cond: [1, dModel]
void    JiT::free();
```

## Training: layers, losses, optimizers {#training}

```c
#include <std/ml/nn.h>        // Rng, more autograd ops, losses, Mlp, Optimizer
#include <std/ml/datasets.h>  // Dataset, generators, MNIST / CSV loaders, DataLoader
#include <std/ml/trainer.h>   // trainer_fit, trainer_evaluate, trainer_accuracy
```

These three headers train a model end to end on `tensor.h`'s autograd. A
batch is a `[batch, features]` tensor. Class labels are tensors of class
indices stored as floats.

- **`Rng`** is a seeded splitmix64 generator with uniform, normal and
  bounded-integer draws. Every initialization, shuffle and sample in these
  modules goes through one, so runs are reproducible.
- **Differentiable ops**, in addition to `tensor.h` / `tensor_nn.h`:
  - Arithmetic: `tensor_add_bias` (row broadcast) and `Tensor::add_scalar`.
  - Elementwise: `Tensor::exp`, `Tensor::log`, `Tensor::abs`,
    `Tensor::leaky_relu`, `Tensor::clamp` and `tensor_minimum`.
  - Reductions and reshaping: `Tensor::mean`, `Tensor::sum_rows`
    (`[m,n] → [m,1]`), `tensor_concat_cols` and `tensor_gather_rows`
    (embedding or codebook lookup, with gradients scatter-added back).
  - `Tensor::log_softmax`.
  - `Tensor::detach`, which stops gradients.
- **Losses**, each returning one value averaged over elements:
  - `tensor_mse_loss`.
  - `tensor_huber_loss`.
  - `tensor_bce_logits_loss`: binary cross-entropy taken from logits, so
    it never computes `log(0)`.
  - `tensor_cross_entropy_loss`: softmax cross-entropy against class
    indices.

  `tensor_accuracy` scores a batch's predictions.
- **`Mlp`** is a stack of fully connected layers: `inDim → hidden ×
  hiddenLayers → outDim`, with `ACT_RELU`, `ACT_LEAKY`, `ACT_TANH`,
  `ACT_SIGMOID`, `ACT_GELU` or `ACT_SILU` between layers. It is
  initialized like PyTorch's `nn.Linear`, with weights stored `[in, out]`.
  `model_copy` / `model_soft_update` keep target networks in sync.
- **`Optimizer`** holds a list of parameters and offers these kinds:

  | Kind | Update |
  |---|---|
  | `OPT_SGD` | Plain gradient descent. |
  | `OPT_MOMENTUM` / `OPT_NESTEROV` | Heavy-ball or Nesterov momentum (`momentum` 0.9). |
  | `OPT_RMSPROP` | Running average of squared gradients (`beta2` 0.99). |
  | `OPT_ADAGRAD` | Learning rate divided by the root of the summed squared gradients (Duchi et al. 2011). |
  | `OPT_ADAM` | Adam with L2 weight decay (`beta1` 0.9, `beta2` 0.999). |
  | `OPT_ADAMW` | Adam with decoupled weight decay (`weightDecay` 0.01). |
  | `OPT_LION` | Sign of the interpolated momentum (Chen et al. 2023), with decoupled decay. Use a learning rate 3–10× smaller than Adam's. |

  `Optimizer::clip_grad_norm` clips the global gradient norm. The fields (`lr`, betas, `weightDecay`) are public, so a
  schedule can change them between steps.

- **Saving and loading.** `tensors_save` / `tensors_load` write a list of
  tensors (shapes and float32 values) to a file and read it back into
  tensors of the same shapes. `model_save_file` / `model_load_file` do this
  for an `Mlp`. Loading returns 0, and changes nothing, when the file is
  missing or doesn't match.

`trainer_fit` is generic over the model type, so the forward pass and loss
are resolved at compile time. It runs epochs of shuffled mini-batches:
loss, backward, optional clipping, step, and a per-epoch lr decay. Each
step's tensors come from a `TensorArena` that is reset after the step.
Parameters and optimizer state always stay on the heap.

```c
static &Tensor clf_loss(&Mlp m, const &Tensor x, const &Tensor y) {
    return tensor_cross_entropy_loss(m.forward(x), y);
}
static &Tensor clf_logits(&Mlp m, const &Tensor x) { return m.forward(x); }

struct Rng rng = Rng::new(1UL);
struct Dataset data = Dataset::moons(400UL, (float)0.1, &rng);
struct DatasetSplit sp = data.split((float)0.25, &rng);
struct Mlp net = Mlp::new(2UL, 32UL, 2UL, 2UL, ACT_RELU, ACT_NONE, &rng);
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.01);
model_add_params(&net, &opt);
struct TrainConfig cfg = TrainConfig::default();     // 10 epochs, batch 32
cfg.epochs = 60UL;
struct TrainReport rep = trainer_fit(&net, clf_loss, &opt, &sp.train, &cfg);
float acc = trainer_accuracy(&net, clf_logits, &sp.test, 64UL);   // > 0.95 in the tests
```

**Datasets.** A `Dataset` is `x [n, features]` plus `y [n, targets]`, with
`classes` set for classification. The synthetic generators are:

- `Dataset::moons`, `Dataset::circles`, `Dataset::spirals`, `Dataset::blobs`
  and `Dataset::xor`: 2D toy classification.
- `Dataset::sine`: regression.
- `Dataset::gaussian_ring`: the usual GAN mode-coverage test.
- `Dataset::shapes`: small binary images of four shape classes.

`Dataset::load_mnist` reads MNIST / Fashion-MNIST IDX files, with pixels
scaled to [0, 1]. `Dataset::load_csv` reads numeric CSV, with an optional
header and label column. Both return `n == 0` on error. `Dataset::split`,
`Dataset::standardize` and `Dataset::standardize_pair` (which applies the
train set's statistics to both sets) prepare the data. A `DataLoader`
hands out batches in a fresh shuffled order every epoch.

Every op's gradient is checked against central differences in the tests.
The optimizers are checked to converge, and an MLP trained on moons
reaches more than 95% accuracy.

On an AMD GPU, [`nn_rocm.h`](#rocm-training) trains these models (and the
conv nets, autoencoders, GANs and RL agents below) with everything kept in
GPU memory: `rocm_trainer_fit` is `trainer_fit` on the device. To write
the training once and pick the backend at build time, use
[`train.h`](#train-traits).

## Models as traits: generic functions for every model {#modules}

```c
#include <std/ml/module.h>
```

Every model is a struct with methods. `module.h` names what a model can
do as small [traits](/reference/generics#composing-traits), one capability
each, and composes them into the roles generic code asks for. The
functions after the table are written once and apply to every model that
has the capability. Each call is resolved at compile time.

| Trait | Composed of | Satisfied by |
|---|---|---|
| `Parameterized` | `collect_params(&out)` (the trained tensors), `collect_state(&out)` (those plus non-trained buffers: running statistics, spectral norms' `u`) | every model below, plus `Gan`, `Dcgan`, `Dqn`, `PolicyAgent`, `DiTBlock`, `GatedDeltaNet`, `LSTMCell`, `XLSTMCell` |
| `Forward` | `forward(x)` | |
| `Objective` | `loss(x, y)`: the model's own training loss | |
| `Module` | `Parameterized + Forward` | `Mlp`, `ConvNet`, `LoraMlp`, `QLoraMlp`, `LayerNorm`, `MultiHeadAttention`, `Mla`, `EgMla`, `JiTBlock`, `Mamba` |
| `LossModel` | `Parameterized + Objective` | `Autoencoder`, `Vae`, `VqVae`, `ConvAutoencoder`, `ConvVae` |
| `CondModule` | `Parameterized` + `forward(x, c)` | `DiTBlock`, `JiT` |
| `Recurrent` | `Parameterized` + `forward(x_t, h)` | `RNNCell`, `GRUCell` |
| `Generative` | `sample(n, labels)` | `Gan`, `Dcgan`, `Vae`, `ConvVae` |
| `GanModel` (`gan.h`) | `Parameterized + Generative` + `train_step`, `train` | `Gan`, `Dcgan` |
| `DenoiseModel` (`diffusion.h`) | `predict(x, sigma)` | your diffusion model ([samplers](#diffusion)) |

| Function | Bound | Does |
|---|---|---|
| `model_add_params(&m, &opt)` | `Parameterized` | adds the trained tensors to an `Optimizer` |
| `model_param_count(&m)` | `Parameterized` | trained values |
| `model_save_file(&m, path)` / `model_load_file(&m, path)` | `Parameterized` | the state in `tensors_save`'s format; loading needs the same shapes |
| `model_copy(&dst, &src)` / `model_soft_update(&dst, &src, tau)` | `Parameterized` | `dst = src` / `dst = tau·src + (1 − tau)·dst` over the state: target networks, averaged generators |
| `module_fit(&m, loss, &opt, &data, &cfg)` | `Module` | `trainer_fit` on a built-in loss (`TRAIN_LOSS_CE`, `TRAIN_LOSS_MSE`, `TRAIN_LOSS_BCE`): no loss function to write |
| `module_loss(&m, loss, x, y)`, `module_predict(&m, x)`, `module_evaluate(&m, loss, &data, batch)`, `module_accuracy(&m, &data, batch)` | `Module` | one loss, a gradient-free forward, the mean loss and the accuracy over a dataset |
| `model_fit(&m, &opt, &data, &cfg)`, `model_evaluate(&m, &data, batch)` | `LossModel` | the same on the model's own loss |
| `recurrent_run(&cell, xs, h0)` | `Recurrent` | the cell over a sequence `[T, in]`, returning the last hidden state |

```c
struct Mlp net = Mlp::new(2UL, 32UL, 2UL, 2UL, ACT_RELU, ACT_NONE, &rng);
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.01);
model_add_params(&net, &opt);
struct TrainReport rep = module_fit(&net, TRAIN_LOSS_CE, &opt, &train, &cfg);
float acc = module_accuracy(&net, &test, 64UL);
model_save_file(&net, "net.bin");
```

The same five lines train a `ConvNet`, a `LoraMlp` (its `collect_params`
returns only the adapters, so only they train and only they are saved)
or a model of your own once it has the methods. A model can also be
assembled from others with a bounded generic struct that is itself a
`Module`:

```c
generic<M: Module>
struct Avg2 {
    M a;
    M b;
    &Tensor forward(const &Tensor x);
    void collect_params(&Vec<&Tensor> out) const;
    void collect_state(&Vec<&Tensor> out) const;
};
generic<M: Module>
&Tensor Avg2::forward(const &Tensor x) {
    return tensor_add(self.a.forward(x), self.b.forward(x)).scale((float)0.5);
}
generic<M: Module>
void Avg2::collect_params(&Vec<&Tensor> out) const { self.a.collect_params(out); self.b.collect_params(out); }
generic<M: Module>
void Avg2::collect_state(&Vec<&Tensor> out) const { self.a.collect_state(out); self.b.collect_state(out); }

struct Avg2<struct Mlp> pair;   // ... pair.a, pair.b = mlp_new(...)
module_fit(&pair, TRAIN_LOSS_CE, &opt, &train, &cfg);
```

`trainer_fit` remains for losses of your own: it takes the loss as a
function of the model and a batch. `tests/stdlib/tests/test_ml_modules.sc`
runs one generic check (forward, parameter count, save and load, copy)
over ten `Module`s, checks `module_fit` against `trainer_fit` to the bit,
and trains the `Avg2` pair above.

## Normalization: LayerNorm and AdaLN {#norm}

```c
#include <std/ml/norm.h>
```

Trainable normalization layers, with autograd, over `[batch, dim]` rows.
`activations.h`'s `Tensor::layernorm_rows` is the forward-only version.

- **`LayerNorm`** (Ba et al. 2016) computes `y = LN(x) · gamma + beta`, with
  learned `gamma` (initialized to 1) and `beta` (initialized to 0).
- **`AdaLN`** is the adaptive LayerNorm of DiT (Peebles & Xie 2023). A
  linear map of a per-sample conditioning vector, such as a timestep or
  class embedding, gives `(shift, scale[, gate])`, and
  `y = LN(x) · (1 + scale) + shift`. The map starts at zero (adaLN-Zero),
  so a fresh AdaLN is a plain LayerNorm. With `gated`, `AdaLNOut.gate`
  also starts at 0, so a residual branch `x + gate · f(y)` starts as the
  identity.
- **Building blocks**: `Tensor::layer_norm`, `tensor_mul_row` (multiply by
  a vector on every row), and `attention.h`'s `Tensor::slice_cols`, which is
  now differentiable.

```c
struct AdaLN ad = AdaLN::new(dim, condDim, 1);   // gated
struct AdaLNOut o = ad.forward(x, cond);        // x [m, dim], cond [m, condDim]
&Tensor h = tensor_add(x, tensor_mul(o.gate, ffn.forward(o.out)));
model_add_params(&ad, &opt);
```

The tests check both layers' gradients against finite differences, and
check that a fresh AdaLN equals LayerNorm. They also check that AdaLN
learns a class-dependent affine map that a plain LayerNorm cannot
represent, bringing its loss from above 0.5 to below 0.001.

## Fine-tuning: LoRA and QLoRA {#lora}

```c
#include <std/ml/lora.h>
```

**LoRA** (Hu et al. 2021) freezes a pretrained `Mlp` and learns a low-rank
update for each layer:

`y = x·W + b + (alpha / r) · (dropout(x)·A)·B`

`A [in, r]` starts random and `B [r, out]` starts at zero, so the adapted
network begins identical to the base. Only A and B train, which is
`r · (in + out)` values per layer instead of `in · out`.

- `LoraMlp::attach` sets the base's `requiresGrad` to 0, and `LoraMlp::detach`
  restores it.
- `LoraMlp::merge` folds `scale · A·B` into `W`, so the base alone then
  computes the adapted output at no extra inference cost.

**QLoRA** (Dettmers et al. 2023) does the same on a base stored as
**4-bit NormalFloat (NF4)**. Each block of 64 weights keeps one absmax
scale and a 4-bit index into the 16 NF4 levels, which are quantiles of a
normal distribution, so Gaussian-like weights use the levels evenly.
Double quantization also stores the scales in 8 bits, with one float per
256 scales, bringing the base to about 4.1 bits per weight.

`QLoraMlp::attach` copies the base, so the original `Mlp` can be freed. Each
forward pass dequantizes the weights to float32, and gradients flow
through the frozen 4-bit base to the adapters. `Nf4Tensor::quantize` /
`Nf4Tensor::dequantize` are also usable on their own.

```c
struct LoraConfig cfg = LoraConfig::default(8UL);    // rank 8, alpha 8
struct QLoraMlp ft = QLoraMlp::attach(&pretrained, &cfg, NF4_BLOCK, 1);   // NF4 + double quant
pretrained.free();
struct Optimizer opt = Optimizer::new(OPT_ADAMW, (float)0.001);
model_add_params(&ft, &opt);                   // adapters only
trainer_fit(&ft, my_loss, &opt, &data, &tc);   // my_loss calls QLoraMlp::forward
```

In the tests, an MLP pretrained on moons is fine-tuned to a rotated,
label-swapped task that the frozen base gets wrong (accuracy below 70%):

- **LoRA** reaches more than 95% accuracy. The base weights stay
  bit-identical, and the merged base reproduces the adapter's output to
  1e-3.
- **NF4** reproduces values on its grid exactly. On Gaussian weights the
  relative RMS error is below 12%, at 4.5 bits per weight, or under 4.2
  bits with double quantization at almost the same error.
- **QLoRA** works on a base stored in under 1/6 of its float32 size. The
  4-bit base still scores above 95% on the original task, and the adapters
  reach more than 95% on the new one.

## Convolutional networks {#convnet}

```c
#include <std/ml/convnet.h>
```

`cnn.h` runs convolutions forward on `FeatureMap`s. `convnet.h` makes them
differentiable on `Tensor`s, so they train with `Tensor::backward`, the
optimizers and `trainer_fit` like `Mlp` does. Images are rows, as in every
other model here: a batch is a `[batch, C*H*W]` tensor, and each row holds
one image in CHW order. A 2D tensor doesn't carry the image geometry, so
every op takes `C, H, W` as arguments. Each op records gradients for its
input and its trained parameters.

### Convolution and pooling

`tensor_conv2d_ex` and `tensor_conv_transpose2d_ex` take a `Conv2dSpec`
with a separate kernel size, stride, padding and dilation for each axis,
plus `groups` and, for the transposed convolution, `outPadH` / `outPadW`.
`Conv2dSpec::new(kernel, stride, padding)` fills in a square kernel with
dilation 1, one group and no output padding; change the fields you need.
`tensor_conv2d` / `tensor_conv_transpose2d` are the common case with one
stride and padding for both axes.

| Op | Weight | Output side |
|---|---|---|
| `tensor_conv2d_ex` | `[outC, (inC/groups)*kH*kW]` (a PyTorch `Conv2d` weight, flattened), bias `[outC]` | `(dim + 2·pad − dil·(k − 1) − 1)/stride + 1`, rounded down |
| `tensor_conv_transpose2d_ex` | `[inC, (outC/groups)*kH*kW]` (a PyTorch `ConvTranspose2d` weight, flattened), bias `[outC]` | `(dim − 1)·stride − 2·pad + dil·(k − 1) + outPad + 1` |

`groups == inC` gives a depthwise convolution. Output padding must be
below the stride or the dilation, as in PyTorch; it picks among the
output sizes that the same stride maps back to one input size.

Pooling takes a `Pool2dSpec`, built with `pool2d_spec(kernel, stride,
padding)`: a separate window, stride and padding per axis, `ceilMode`,
and, for avg pooling, `countIncludePad`.

- `Tensor::maxpool2d_ex` / `Tensor::avgpool2d_ex` clip their windows to the
  plane. Max pooling therefore ignores the padding, as PyTorch's
  `MaxPool2d` does, and sends each window's gradient to its first largest
  input.
- With `countIncludePad`, avg pooling divides by the window's extent
  inside the padded plane, as PyTorch's `AvgPool2d` does by default.
  Without it, avg pooling divides by the positions inside the plane.
- `ceilMode` rounds the output side up. As in PyTorch, the last window
  must still start inside the input or its left padding.
- `Tensor::maxpool2d` / `Tensor::avgpool2d` take a square window.
  `Tensor::global_avgpool2d` / `Tensor::global_maxpool2d` reduce each
  channel to one value, `[batch, C*H*W] → [batch, C]`.

`conv_out_side_ex`, `conv_transpose_out_side_ex` and `pool_out_side`
compute the sizes.

Both convolutions use im2col + `sgemm`, per group. A 3×3, stride-1 layer
with at least 8 input and output channels runs its forward pass through
`cnn.h`'s Winograd F(2×2, 3×3) kernel, and a 1×1 layer through its direct
path. A large enough batch is split across the thread pool, one chunk of
images per thread. Weight and bias gradients are summed per chunk and
then added up in chunk order, so the result doesn't depend on scheduling.
Pooling, resizing and the norms split large inputs by plane, row or
channel. Those units never share an output, so the threaded result is
bit-for-bit the single-threaded one.

### Resizing

`x.resize2d(C, H, W, outH, outW, mode, alignCorners)` resizes to
any size, up or down, as PyTorch's `interpolate(size=...)`:

| `mode` | Reads |
|---|---|
| `RESIZE_NEAREST` | source pixel `floor(o · in / out)` (PyTorch's `nearest`) |
| `RESIZE_BILINEAR` | the four nearest pixels |
| `RESIZE_BICUBIC` | the sixteen nearest pixels, cubic convolution with a = −0.75 |

With `alignCorners`, the corner pixels' centres map onto each other.
Without it, pixel areas line up. Edges are clamped, and gradients scatter
back with the same weights. `Tensor::upsample_nearest` is a fast path for
an integer factor. `Tensor::upsample_bilinear` resizes by an integer
factor without aligning corners.

### Normalization and dropout

- **Batch norm** (`tensor_batch_norm`; Ioffe & Szegedy 2015) normalizes
  each channel over the batch and the plane. Pass `plane = H*W` for
  feature maps, or 1 for `[batch, features]` rows. In training it uses the
  batch's statistics and moves the running ones toward them by `momentum`.
  The running variance uses the unbiased estimate, as in PyTorch. In
  inference it uses the running statistics.
- **Group norm** (`tensor_group_norm`; Wu & He 2018) normalizes each
  image's channel groups over their channels and planes. It doesn't depend
  on the batch, so it behaves the same in training and inference. One
  group is layer norm over (C, H, W); one group per channel is instance
  norm.
- **Spectral norm** (`tensor_spectral_norm`; Miyato et al. 2018) divides
  a weight by its largest singular value. It estimates that value by power
  iteration from a vector `u` kept between calls. The gradient treats the
  power-iteration vectors as constants, as PyTorch does.
- **Dropout**: `Tensor::dropout` zeroes each value with probability p and
  scales the rest by 1/(1 − p) in training, and passes x unchanged in
  inference. `Tensor::dropout2d` drops whole channel planes.

**Conditioning.** `Tensor::label_planes` turns class indices into one
constant plane per class: 1 in the plane of the row's label and 0
elsewhere. `tensor_append_label_channels` adds those planes after an
image's channels. This is how the conditional `ConvVae` and `Dcgan` pass
the label to their image-side network.

### `ConvNet`

`ConvNet` is the convolutional counterpart of `Mlp`. Start it with the
input geometry and append layers; each layer's input is the previous
layer's output.

| Builder | Layer |
|---|---|
| `n.conv(outC, kernel, stride, padding, act, &rng)` / `n.conv_ex(outC, &spec, act, &rng)` | convolution, then `act` |
| `n.deconv(...)` / `n.deconv_ex(...)` | transposed convolution, then `act` |
| `n.maxpool(kernel, stride, padding)` / `n.maxpool_ex(&spec)` | max pooling |
| `n.avgpool(kernel, stride, padding, countIncludePad)` / `n.avgpool_ex(&spec)` | avg pooling |
| `n.global_avgpool()` / `n.global_maxpool()` | each channel to one value; output `C × 1 × 1` |
| `n.upsample(factor)` / `n.upsample_bilinear(factor)` / `n.resize(outH, outW, mode, alignCorners)` | resizing |
| `n.batchnorm(act)` | batch norm (momentum 0.1, eps 1e-5), then `act` |
| `n.groupnorm(groups, act)` / `n.layernorm(act)` / `n.instancenorm(act)` | group norm (eps 1e-5), then `act` |
| `n.spectral_norm(&rng)` | spectrally normalizes the last convolution's or dense layer's weight, with one power iteration per training pass |
| `n.dropout(p, byChannel, &rng)` | dropout, with its own random stream seeded from `rng` |
| `n.dense(outDim, act, &rng)` | fully connected over the flattened map; output `outDim × 1 × 1` |
| `n.reshape(C, H, W)` | reinterprets the geometry without moving data |
| `slot = n.save()` | keeps the current activation for a skip |
| `n.add_skip(slot, act, &rng)` | adds a saved activation, then `act`; when the channels or size differ, the saved activation goes through a learned 1×1 convolution (ResNet's projection shortcut) |
| `n.concat_skip(slot)` | appends a saved activation's channels, which must match the current height and width (a U-Net skip) |
| `n.residual_block(outC, stride, batchNorm, act, &rng)` | ResNet's basic block: conv 3×3 (stride) → [batch norm] → act → conv 3×3 → [batch norm] → add the shortcut → act |

Place a norm between a convolution with `ACT_NONE` and the activation.
Weights and biases are initialized like PyTorch's, from
U(−1/√fanIn, 1/√fanIn), and the norms start at gamma 1 and beta 0.
`params` holds the trained tensors. `buffers` holds what the optimizer
doesn't train: running statistics and spectral norms' `u`. Both stay on
the heap.

The `training` field is 1 from `ConvNet::new`. In training mode, batch norm
uses batch statistics and updates the running ones, spectral norms
iterate, and dropout drops. Call `n.set_training(0)` for
inference; it returns the previous mode. The models in `autoencoder.h`
and `gan.h` switch modes themselves: their loss and training-step
functions run in training mode, and encode, decode, sample and
score run in inference mode.

Other operations on a `ConvNet`:

- `ConvNet::forward` maps `[batch, in_dim()]` to
  `[batch, out_dim()]`. The `outC`, `outH` and `outW` fields give
  the current output geometry.
- `model_add_params` registers the parameters (not the buffers).
- `ConvNet::clone`, `model_copy` and `model_soft_update` handle target
  and averaged networks, buffers included.
- `ConvNet::fold_batchnorm` folds each batch norm that directly follows a
  convolution or dense layer into that layer's weight and bias, for
  inference. The output is unchanged, but don't train the net afterwards.
- `model_save_file` / `model_load_file` write the parameters and
  buffers to a file and read them back into a net built with the same
  layers. Loading returns 0 on a mismatch, and nothing is overwritten.
  `tensors_save` / `tensors_load` and an `Mlp`'s files
  in `nn.h` use the same format.

```c
static &Tensor cnn_loss(&ConvNet m, const &Tensor x, const &Tensor y) {
    return tensor_cross_entropy_loss(m.forward(x), y);
}

struct Rng rng = Rng::new(1UL);
struct ConvNet net = ConvNet::new(1UL, 28UL, 28UL);   // MNIST
net.conv(16UL, 3UL, 1UL, 1UL, ACT_NONE, &rng);       // 16 x 28 x 28
net.batchnorm(ACT_RELU);
net.residual_block(16UL, 1UL, 1, ACT_RELU, &rng);   // 16 x 28 x 28
net.residual_block(32UL, 2UL, 1, ACT_RELU, &rng);   // 32 x 14 x 14
net.residual_block(64UL, 2UL, 1, ACT_RELU, &rng);   // 64 x 7 x 7
net.dropout((float)0.1, 1, &rng);
net.global_avgpool();              // 64 x 1 x 1
net.dense(10UL, ACT_NONE, &rng);   // logits
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.001);
model_add_params(&net, &opt);
struct TrainConfig cfg = TrainConfig::default();
trainer_fit(&net, cnn_loss, &opt, &mnist, &cfg);
net.set_training(0);   // inference from here on
net.fold_batchnorm();
model_save_file(&net, "mnist_resnet.bin");
```

The tests check every op's input and parameter gradients against central
differences. The cases cover:

- conv2d with stride 1 and 2, a dilated grouped convolution, a depthwise
  convolution, and a 2×3 kernel with different strides and padding per
  axis;
- transposed convolution with stride 1 and 2, grouped with output padding,
  and dilated;
- max pooling with disjoint, overlapping, padded, and 2×3 ceil-mode
  windows;
- avg pooling, with and without padding counted, and in ceil mode;
- global average and max pooling;
- nearest upsampling, bilinear upsampling by 2 and 3, aligned-corner
  bilinear to 5×4, bicubic to 7×5, and nearest downsampling;
- batch norm in training and inference mode, layer norm, and group norm;
- spectral norm;
- dropout and channel dropout.

Further checks against reference values:

- Sizes: the output sides of padded and ceil-mode pooling and of dilated
  convolutions match PyTorch's.
- `cnn.h` parity: `tensor_conv2d`, the padded pooling and global pooling
  match `Conv2D::forward`, `*pool2d_forward_ex` and `global_*pool2d`. The
  Winograd path matches a direct sum.
- Resizing: bilinear upsampling reproduces PyTorch's output for a 2×2
  input, and bicubic resizing keeps a constant image constant.

ConvNet checks:

- An input-gradient check runs through a projection skip, a concat skip,
  group norm and dense layers.
- The residual blocks' parameter counts are checked.
- Batch-norm folding leaves inference output unchanged within 1e-4.
- A saved net loads into a differently initialized one and gives the same
  output exactly; a mismatched net refuses to load.

Other checks:

- Batch norm's running statistics follow the batch's.
- Dropout drops the right fraction, and channel dropout drops whole
  planes.
- Spectral norm leaves a largest singular value of 1.
- Group norm doesn't depend on the rest of the batch.
- Threaded convolutions match one thread within 1e-4, and pooling,
  upsampling and batch norm match bit for bit.
- End to end, a small residual CNN with batch norm and channel dropout
  classifies held-out 12×12 shapes with more than 90% accuracy.

## Autoencoders {#autoencoders}

```c
#include <std/ml/autoencoder.h>
```

MLP encoders and decoders over flattened rows. Each `*_loss` function has
`trainer_fit`'s loss signature. Reconstruction is `RECON_MSE` for real
values, or `RECON_BCE` for data in [0, 1]. With BCE the decoder outputs
logits, and `*_decode` / `*_reconstruct` apply the sigmoid.

| Model | What it adds |
|---|---|
| `Autoencoder` | Plain undercomplete AE. Its config switches on the variants, which combine: **denoising** (`noiseStd` Gaussian noise and a `dropProb` fraction of zeroed inputs; the loss compares against the clean input) and **sparse** (`sparsity` × L1 penalty on the code). |
| `Vae` | **VAE** (Kingma & Welling 2014): Gaussian encoder, reparameterization, reconstruction summed over features plus `beta` × KL. `beta > 1` is a **β-VAE**; raise `beta` from 0 for KL warm-up. `classes > 0` makes it a **conditional VAE**: the one-hot label is appended to both the encoder input and the decoder input. `Vae::sample` draws from the prior, optionally per class. |
| `VqVae` | **VQ-VAE** (van den Oord et al. 2017): the encoder output snaps to its nearest codebook vector. Gradients pass straight through, and the loss adds codebook and `commitment` terms. Against codebook collapse, the first batch initializes the codebook, and a code unused for `resetDeadAfter` batches is moved onto a random encoder output. `VqVae::encode` / `VqVae::decode` map to and from the discrete codes. |

```c
struct VaeConfig cfg = VaeConfig::default(4UL);   // 256 hidden, BCE, beta 1
cfg.classes = 4UL;                               // conditional
struct Vae v = Vae::new(100UL, &cfg);
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.002);
model_add_params(&v, &opt);
model_fit(&v, &opt, &shapes, &tc);   // the VAE's own loss (Vae::loss)
&Tensor samples = v.sample(64UL, (?&Tensor)labels);   // [64, 100] in (0, 1)
```

The tests check each variant's reconstruction against its untrained error.
They also check that a classifier trained on the real shapes recognizes the
conditional VAE's samples as their requested class (accuracy above 70%),
and that the VQ-VAE keeps more than 4 codes in use.

### Convolutional autoencoders {#conv-autoencoders}

`ConvAutoencoder` and `ConvVae` are the plain AE and the VAE with
`ConvNet` encoders and decoders that you build. The encoder maps the image
to features. The decoder takes the code as a `latent × 1 × 1` input and
must output the image's size; start it with `ConvNet::dense` +
`ConvNet::reshape`, or with a transposed convolution from 1×1. In
`ConvVae`, linear heads on the flattened features produce μ and log σ².
Its loss is `Vae`'s, and it reads `latent`, `recon`, `beta`, `classes` and
`seed` from a `VaeConfig`.

With `classes > 0`, `ConvVae` is a **conditional VAE**. The encoder gets
the label as `classes` extra input channels (one constant plane per
class), and the decoder gets the one-hot label appended to the code. So
for C-channel images the encoder takes `C + classes` channels, and the
decoder takes `latent + classes` inputs and outputs C channels. As with
`Vae`, `y` carries the class indices, and `ConvVae::sample` takes labels
or `null`.

Both models take ownership of the nets, so free the model and not the
nets. Loss functions run the nets in training mode. Encode, decode,
reconstruct and sample run them in inference mode, so batch norm uses its
running statistics there.

```c
unsigned long classes = 10UL;                                // conditional, MNIST digits
struct ConvNet enc = ConvNet::new(1UL + classes, 28UL, 28UL);
enc.conv(32UL, 4UL, 2UL, 1UL, ACT_LEAKY, &rng);   // 32 x 14 x 14
enc.conv(64UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);    // 64 x 7 x 7
enc.batchnorm(ACT_LEAKY);
struct ConvNet dec = ConvNet::new(16UL + classes, 1UL, 1UL);  // latent 16 + label
dec.dense(64UL * 49UL, ACT_RELU, &rng);
dec.reshape(64UL, 7UL, 7UL);
dec.deconv(32UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);   // 32 x 14 x 14
dec.batchnorm(ACT_RELU);
dec.deconv(1UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);   // 1 x 28 x 28 logits
struct VaeConfig cfg = VaeConfig::default(16UL);
cfg.classes = classes;
struct ConvVae v = ConvVae::new(enc, dec, &cfg);
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.001);
model_add_params(&v, &opt);
model_fit(&v, &opt, &mnist, &tc);
&Tensor samples = v.sample(64UL, (?&Tensor)labels);   // [64, 784] in (0, 1)
```

The tests run on 8×8 shapes, with batch norm in both nets:

- A conv AE must cut its reconstruction error to below 30% of the
  untrained error.
- A conv VAE must cut its loss to below 60% of the first epoch's, keep KL
  above 0.1, and reconstruct with MSE below 0.1.
- The conditional conv VAE's samples must be recognized as their
  requested class more than 70% of the time, by a classifier trained on
  the real images.

## GANs {#gans}

```c
#include <std/ml/gan.h>
```

An MLP generator and discriminator (leaky ReLU), trained in alternation.
The objective is set in the config:

| `loss` | Objective |
|---|---|
| `GAN_STANDARD` | Goodfellow et al. 2014, with the non-saturating generator loss. |
| `GAN_LSGAN` | Least squares (Mao et al. 2017). |
| `GAN_WGAN` | Wasserstein critic with weight clipping, `nCritic` critic steps per generator step, RMSProp (Arjovsky et al. 2017). |
| `GAN_HINGE` | Hinge loss (Miyato et al. 2018). |
| `GAN_WGAN_GP` | Wasserstein critic with gradient penalty (Gulrajani et al. 2017): `gpWeight` (10) × (‖∇ₓD(x̂)‖ − 1)² on random interpolates of real and generated rows, no clipping, Adam with betas (0, 0.9); use `nCritic` 5. |

The penalty's gradient with respect to the critic's parameters is a mixed
second derivative. Rather than differentiating the backward pass, it is
computed from a central difference of the critic along each row's
normalized input gradient. That costs two extra critic passes and is
exact to O(ε²), with ε = 0.01; the tests check it against finite
differences of the penalty itself. Because the penalty treats rows
independently, the critic must not use batch norm (group norm or layer
norm are fine).

`classes > 0` makes it a **conditional GAN**: the label goes to both
networks. `emaDecay` keeps an exponential moving average of the
generator's weights (Karras et al. 2018), and `Gan::sample` samples from
it. The game oscillates, and the average sits nearer its centre than any
single step does. The GAN owns its two optimizers (Adam with β1 = 0.5, or
RMSProp for WGAN) and the arena for its steps. `Gan::train` runs epochs
over a `Dataset`, and `Gan::train_step` runs one step on your own batch.

```c
struct GanConfig cfg = GanConfig::default(4UL);   // latent 4
cfg.loss = GAN_HINGE;
struct Gan g = Gan::new(2UL, &cfg);
g.train(&data, 80UL, 64UL);
&Tensor fake = g.sample(1000UL, null);   // heap tensor, caller frees
```

In the tests, every objective learns a 2D Gaussian, with the mean within
0.25. The WGAN-GP critic's input gradient ends up with a norm between 0.6
and 1.4. The spread is bounded loosely, because GANs tend to under-cover it.
On a 4-mode ring, a conditional GAN puts more than 75% of its samples
on the mode of their label.

### DCGAN {#dcgan}

`Dcgan` plays the same game with a `ConvNet` generator and discriminator
(Radford et al. 2016). G takes the latent batch as a `latent × 1 × 1`
input and must output the data's size. D takes a data row and must output
one score. It reads the objective, learning rates, `nCritic`, `clip`,
`classes`, `emaDecay` and `seed` from a `GanConfig`.

With `classes > 0`, it is a **conditional DCGAN**. G gets the one-hot label
appended to its noise, so it takes `latent + classes` inputs. D gets the
label as `classes` extra input channels, one constant plane per class.
`Dcgan::train_step`, `Dcgan::sample` and `Dcgan::score` take labels or
`null`, and `Dcgan::train` takes them from the dataset.

The DCGAN owns the nets, the optimizers, the averaged generator and the
step arena. Training steps run the nets in training mode, so batch norm
uses batch statistics, as DCGAN prescribes. `Dcgan::sample` and
`Dcgan::score` run in inference mode.

```c
struct ConvNet gen = ConvNet::new(16UL, 1UL, 1UL);   // latent 16
gen.deconv(64UL, 4UL, 1UL, 0UL, ACT_NONE, &rng);    // 64 x 4 x 4
gen.batchnorm(ACT_RELU);
gen.deconv(32UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);   // 32 x 8 x 8
gen.batchnorm(ACT_RELU);
gen.deconv(1UL, 4UL, 2UL, 1UL, ACT_SIGMOID, &rng);   // 1 x 16 x 16
struct ConvNet disc = ConvNet::new(1UL, 16UL, 16UL);
disc.conv(32UL, 4UL, 2UL, 1UL, ACT_LEAKY, &rng);   // 32 x 8 x 8
disc.conv(64UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);    // 64 x 4 x 4
disc.batchnorm(ACT_LEAKY);
disc.conv(1UL, 4UL, 1UL, 0UL, ACT_NONE, &rng);   // one score
struct GanConfig cfg = GanConfig::default(16UL);
struct Dcgan g = Dcgan::new(gen, disc, &cfg);
g.train(&images, 20UL, 64UL);
&Tensor fake = g.sample(64UL, null);   // [64, 256], heap tensor, caller frees
```

The tests train on 8×8 shapes. A DCGAN must move its samples' mean
intensity from the untrained sigmoid's 0.5 to within 0.1 of the data's.
A conditional DCGAN's samples must be recognized as their requested class
more than 55% of the time, by a classifier trained on the real images
(chance is 25%).

## Reinforcement learning {#rl}

```c
#include <std/ml/rl.h>
```

**Environments.** `Bandit` is a k-armed Gaussian bandit. `GridWorld` has
walls, pits, goals, slip probability and truncation; `GridWorld::cliff()`
is Sutton & Barto's cliff walk. `CartPole` uses Gym's CartPole-v1
dynamics, its 12° / 2.4 m limits and 500-step truncation. Steps return an
`EnvStep` that separates `terminated` (no future value) from `truncated`
(time limit, so learners bootstrap). Observations are tensors.

| Technique | API |
|---|---|
| Bandits | `BanditAgent` with `BANDIT_EPS_GREEDY`, `BANDIT_UCB1`, `BANDIT_THOMPSON` (Gaussian posterior) or `BANDIT_GRADIENT` (softmax preferences with a baseline). |
| Tabular TD | `TabularAgent` methods `q_learning_update`, `sarsa_update`, `expected_sarsa_update`, and ε-greedy `act`. |
| Value-based | `Dqn` (Mnih et al. 2015): replay buffer, target network (soft or periodic hard copies), Huber loss, linear ε schedule, Double DQN (van Hasselt et al. 2016), gradient clipping. `Dqn::act` + `Dqn::observe` per step. |
| Policy-based | `PolicyAgent` with `PG_REINFORCE` (Monte Carlo returns, learned baseline), `PG_A2C` (advantage actor-critic, GAE(λ)) or `PG_PPO` (clipped surrogate, several epochs of shuffled minibatches per rollout). Every variant uses an entropy bonus, advantage normalization and gradient clipping. `PolicyAgent::act` + `PolicyAgent::record` per step, then `PolicyAgent::update` per episode or rollout. |

```c
struct Dqn d = Dqn::new(CARTPOLE_OBS, CARTPOLE_ACTIONS, &cfg);
struct CartPole env = CartPole::new(1UL);
env.reset(obs);
while (1) {
    unsigned long a = d.act(obs, 1);   // epsilon-greedy
    struct EnvStep s = env.step(a, next);
    d.observe(obs, a, s.reward, next, s.terminated);   // store + train
    // obs = next ...
    if (s.terminated || s.truncated) break;
}
```

The tests run each technique on its problem:

- The four bandit agents pick the best of 10 arms more than 85% of the
  time.
- On the cliff walk, Q-learning learns the optimal 13-step path along the
  edge, and SARSA a safer, longer one (Sutton & Barto's Example 6.6).
- DQN, REINFORCE, A2C and PPO each reach CartPole's "solved" mean greedy
  return of 195 within their step budget.

## One training interface: CPU or GPU {#train-traits}

```c
#include <std/ml/train.h>          // backend-neutral names; the build's features pick the backend
#include <std/ml/train_traits.h>   // the traits and the generic loops (train.h includes it)
```

The CPU stack above and [`nn_rocm.h`](#rocm-training)'s device-resident
stack train the same models. `train_traits.h` puts both behind one
interface: [traits](/reference/generics#composing-traits) composed from
four small capabilities, and generic loops written once against them.
Traits are structural. A trainer conforms by having the methods,
conformance is checked where a generic is instantiated, and every call is
a direct call to that type's method, with no dispatch at run time.

| Capability | Methods |
|---|---|
| `HostSynced` | `sync()` (make the trainer's `host` model current), `free()` |
| `EpochFit` | `fit_begin(&data, &cfg)`, `fit_epoch()` → `EpochLoss`, `fit_end()`, `learning_rate()` / `set_learning_rate(lr)` |
| `BatchStep` | `train_step(x, y)`: one step on a host batch |
| `Actor` | `act(obs, explore)` |

| Role | Composed of | For |
|---|---|---|
| `Trainable` | `EpochFit + BatchStep + HostSynced` | a model and its optimizer, trained on a `Dataset`'s batches: nets (`Mlp`, `ConvNet`) on a loss, autoencoders, VAEs |
| `GanTrainable` | `HostSynced` + `train_step(real, labels)`, `train(&data, epochs, batchSize)` | a `Gan` or `Dcgan` with its two optimizers |
| `ValueAgent` | `Actor + HostSynced` + `epsilon()`, `observe(obs, action, reward, next, terminal)` (1 when a gradient step ran), `last_loss()`, `updates()` | a value-based RL agent (`Dqn`) |
| `PolicyLearner` | `Actor + HostSynced` + `record(obs, action, reward, next, terminated, truncated)`, `update()` | a policy-gradient agent (`PolicyAgent`: REINFORCE, A2C, PPO) |

- **`train_fit(&m, &data, &cfg)`** is `trainer_fit` for any trainer that
  is `EpochFit + HostSynced` (every `Trainable` is; the bound asks only for
  what the loop uses): the same batches, epochs, lr decay and `TrainReport`. It leaves the host
  model synced, and reports `steps == 0` when a run fails on a device.
- **`train_gan(&g, &data, epochs, batchSize)`** is `Gan::train` for any
  `GanTrainable`, also leaving the host model synced.
- Code of your own can take `generic<A: ValueAgent>` (or any other trait)
  in the same way, and then runs unchanged on either backend.

**The trainers.** `train.h` names each trainer without a backend prefix
and picks the concrete type at compile time:

| `train.h` | Constructor | CPU (`train_cpu.h`) | ROCm (`nn_rocm.h`) |
|---|---|---|---|
| `MlpTrainer` | `mlp_trainer_new(mlp, loss, &opt, &to)` | `CpuTrainer<Mlp>` | `RocmTrainer<Mlp, RocmNet>` |
| `ConvNetTrainer` | `convnet_trainer_new(net, loss, &opt, &to)` | `CpuTrainer<ConvNet>` | `RocmTrainer<ConvNet, RocmNet>` |
| `VaeTrainer` / `ConvVaeTrainer` | `vae_trainer_new(v, &opt, &to)` / `conv_vae_trainer_new` | `CpuModelTrainer<Vae>` / `<ConvVae>` | `RocmTrainer<Vae, RocmVae>` / `<ConvVae, RocmVae>` |
| `ConvAeTrainer` | `conv_ae_trainer_new(ae, &opt, &to)` | `CpuModelTrainer<ConvAutoencoder>` | `RocmTrainer<ConvAutoencoder, RocmConvAe>` |
| `GanTrainer` / `DcganTrainer` | `gan_trainer_new(g, &to)` / `dcgan_trainer_new` | `CpuGanTrainer<Gan>` / `<Dcgan>` | `RocmGanTrainer<Gan>` / `<Dcgan>` |
| `DqnLearner` | `dqn_learner_new(d)` | `CpuDqnAgent` | `RocmDqnAgent` |
| `PgLearner` | `pg_learner_new(a, &to)` | `CpuPgAgent` | `RocmPgAgent` |

- **Ownership.** A constructor takes the host model by value into the
  trainer's `host` field. The model's tensors are shared with the struct
  you passed, but its other fields are not, so use `t.host` from then on.
  `sync()` makes `host` current: a no-op on the CPU, a download from the
  GPU. `free()` releases what the trainer allocated (optimizer state,
  device buffers), not the host model, which you free as usual
  (`t.host.free()`).
- **Optimizer.** The `Optimizer` passed to a constructor only supplies the
  kind and hyperparameters (`Optimizer::from` / `RocmOptimizer::from`). The
  trainer adds the model's parameters to its own optimizer.
- **Losses.** A net trainer takes `TRAIN_LOSS_CE` (softmax cross-entropy on
  class indices), `TRAIN_LOSS_MSE` or `TRAIN_LOSS_BCE` (on logits).
  Autoencoders and VAEs train on their own losses.
- **`TrainerOptions`** (`TrainerOptions::default()`: `maxBatch` 256, fp32).
  The CPU ignores both fields. `maxBatch` sizes a device's buffers;
  `cfg.batchSize` must not exceed it, and for `pg_learner_new` it is the
  longest rollout. `precision` (`TENSOR_FP16` / `TENSOR_BF16`) selects
  16-bit activations on a device. On ROCm that is
  [`RocmNet::set_precision`](#rocm-training-mixed-precision), for net
  trainers only.
- **Nothing is lost on the GPU.** `RocmTrainer::fit_epoch` is
  `rocm_trainer_fit`'s epoch: the dataset stays on the GPU and steps are
  replayed as graphs. The GAN and RL trainers wrap `RocmGan`, `RocmDqn` and
  `RocmPolicy`. `train_step` on a device uploads the batch and waits for
  its loss, so it suits hand-written loops, not speed.

```c
#include <std/ml/train.h>

static &Tensor logits(&Mlp m, const &Tensor x) { return m.forward(x); }

struct Rng rng = Rng::new(1UL);
struct Dataset data = Dataset::moons(400UL, (float)0.1, &rng);
struct Optimizer adam = Optimizer::new(OPT_ADAM, (float)0.01);   // kind and hyperparameters
struct TrainerOptions to = TrainerOptions::default();
MlpTrainer t = mlp_trainer_new(Mlp::new(2UL, 32UL, 2UL, 2UL, ACT_RELU, ACT_NONE, &rng),
                               TRAIN_LOSS_CE, &adam, &to);
struct TrainConfig cfg = TrainConfig::default();
cfg.epochs = 60UL;
struct TrainReport rep = train_fit(&t, &data, &cfg);         // t.host is the trained Mlp
float acc = trainer_accuracy(&t.host, logits, &data, 64UL);
t.free();
t.host.free();
```

### Choosing the backend {#train-backend}

The build's [features](/advanced/safeguard#feature-flags) select the backend, so the
program above trains on the CPU or on an AMD GPU without a source change:

```toml
[features]
rocm = []       # safeguard build --features rocm
```

| Features | Backend | Notes |
|---|---|---|
| none | CPU (`train_cpu.h`) | |
| `rocm` (`SAFEC_FEATURE_ROCM`) | ROCm (`nn_rocm.h`) | Link the `gpu_rocm` libraries, plus `-lMIOpen` for MIOpen convolutions. |
| `cuda` (`SAFEC_FEATURE_CUDA`) | none yet | A compile error. CUDA has per-op kernels (`gpu_cuda.h`) but no device-resident training stack yet. When `rocm` is enabled as well, ROCm is used. |

`ML_TRAIN_BACKEND` is the chosen backend's name as a string (`"cpu"` or
`"rocm"`). Each name in `train.h` is a typedef, and each constructor is a
static function that forwards to the backend's own (`cpu_module_trainer_new`,
`rocm_mlp_trainer_new`, …). The CPU trainers are bounded generic structs —
`CpuTrainer<M: Module>`, `CpuModelTrainer<M: LossModel>`,
`CpuGanTrainer<H: GanModel>` — built by generic constructors, so on the CPU
any [`Module`](#modules) trains through the interface with
`cpu_module_trainer_new(model, loss, &opt)` and any `LossModel` with
`cpu_model_trainer_new(model, &opt)`. Those can also be called directly to use
both backends in one program. A future CUDA backend adds types that
satisfy the same traits, plus one branch in `train.h`.

`tests/stdlib/tests/test_ml_traits.sc` checks that every CPU trainer, used
through the traits, trains to the bit like the API it wraps (`trainer_fit`,
`Gan::train`, `Dqn`, `PolicyAgent`). `tests/stdlib/rocm/run.sh` builds
`train_backend_check.sc`, a program written only against `train.h`, once
for the CPU and once with the `rocm` feature. On an MI100 the two builds
agree on every compared value: the losses of an MLP, a `ConvNet` and a
convolutional autoencoder, `train_step`, the VAE's loss level, and the DQN
and PPO agents step for step.

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
  fixed in the code generator. The bf16 [attention and transformer
  ops](#mps-attention) were checked against CPU reference implementations
  on an M1 Pro across all four supported head dims, GQA, causal,
  sliding-window and non-causal masking.
- **ROCm**: verified on an AMD Instinct MI100 (gfx908, ROCm 7.1) —
  `tests/stdlib/rocm/run.sh` checks every op, all four GEMM transpose
  combinations, graphs, the host-array API and the fp16/bf16 buffers
  (rounding bit-identical to `float16.h`, 16-bit GEMMs, SGD) against CPU
  results (325 checks, including the mixed-precision ops), plus the [MIOpen and hipFFT
  libraries](#rocm-miopen-hipfft) (73 checks), and [device-resident
  training](#rocm-training) (`nn_rocm.h`, 387 checks against the CPU training
  stack); the [ML benchmarks](/benchmarks#ml-mlp) train to the same loss as
  the CPU and MPS paths.
- **CUDA**: verified on an NVIDIA H200 (sm_90, CUDA 13.0, driver 580) —
  `tests/stdlib/cuda/run.sh` checks every op, all four GEMM transpose
  combinations with and without the fused ReLU, graphs, the host-array and
  persistent-buffer APIs, the fp16/bf16 kernels and the [cuDNN and cuFFT
  libraries](#cuda-cudnn-cufft) against CPU results (216 checks), and the [ML benchmarks](/benchmarks#ml-mlp)
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
| Buffers | `RocmBuf::alloc`, `RocmBuf::upload`, `rocm_write`, `rocm_read`, `RocmBuf::free` |
| Matmul | `rocm_gemm(a, ta, b, tb, c, m, n, k)` — `c = op(a) op(b)`, either operand transposed |
| Elementwise | `rocm_add`/`sub`/`mul`/`div`/`pow`, `rocm_scale`, `rocm_relu`, `rocm_gelu` (tanh approximation), `rocm_exp`/`log`/`sqrt`, `rocm_relu_grad` |
| Decoding (one token at a time) | `rocm_half_gemv(w, x, y, n, k)`: `y = w x` with fp16/bf16 weights `[n][k]` and fp32 vectors; one wave per output row, 0.8–1.07 TB/s on an MI100, 1.04–1.8× faster than a one-row `rocm_half_gemm_f32`. `rocm_rmsnorm(x, w, y, rows, n, eps)` per row, optional weight, in place allowed. These are the kernels of the [Gemma 4 E2B ROCm engine](/benchmarks#ml-llm) |
| Training | `rocm_sgd(w, g, lr)` (in place), `rocm_mse_grad(y, t, dy, loss)`, `rocm_sum` |
| Layout | `rocm_copy(src, srcOff, dst, dstOff, n)`, `rocm_concat(a, na, c, nc, out, batch)`, `rocm_upsample2x_nearest`, `rocm_upsample2x_concat` (a U-Net decoder step in one pass), `rocm_bias_channels(y, bias, batch, channels, plane, relu)` (per-channel bias, optional ReLU, in place) |
| fp16 / bf16 | `RocmHalfBuf` (`ROCM_FP16` / `ROCM_BF16`): `RocmHalfBuf::alloc`, `RocmHalfBuf::upload`, `rocm_half_write` / `rocm_half_read` (raw bits), `RocmHalfBuf::free`; `rocm_to_half` / `rocm_from_half`; `rocm_half_gemm` (16-bit result), `rocm_half_gemm_relu` and `rocm_half_gemm_f32` (fp32 result); training ops `rocm_half_relu`, `rocm_half_relu_grad`, `rocm_half_mse_grad` (fp32 loss), `rocm_half_sgd(w, g, lr)` and `rocm_half_sgd2` (two updates in one launch). Same API as CUDA's `CudaHalfBuf`; elementwise math is fp32, rounded once per stored value, bit-exact with `float16.h` |
| Mixed precision | `rocm_half_convert` (fp16 ↔ bf16); `rocm_to_half_many` / `rocm_from_half_many` (up to 32 buffers per launch); `rocm_half_unscale_many` / `rocm_unscale_many` (gradients × 1/scale, with an overflow flag) and `rocm_zero`; `rocm_mp_sgd` / `rocm_mp_adam` (fp32 master weights, also writing the 16-bit copy, skipped on the GPU when the flag is set); `rocm_half_add` / `sub` / `mul` / `scale` / `axpy`, `rocm_half_bias` (row or NCHW channel bias, ReLU); `rocm_half_maxpool2d` / `rocm_half_avgpool2d`. See [mixed precision on the GPU](#gpu-mixed-precision) |
| Attention | `rocm_flash_attention(q, k, v, out, heads, seqLen, kvLen, headDim, scale, causal)` (exact softmax attention, tiled, no score matrix), `rocm_linear_attention(...)` (φ(x) = elu(x) + 1 kernel attention, linear in sequence length); host-array forms `rocm_flash_attention_f32` / `rocm_linear_attention_f32`; `rocm_attention_use_mfma(on)` (matrix cores on CDNA GPUs) |
| Graphs | `rocm_capture_begin` / `rocm_capture_end` → `RocmGraph`, `rocm_graph_launch`, `RocmGraph::free` |
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
every step.

GEMMs (fp32 and 16-bit) go through rocBLAS `gemm_ex`. The default solution
rocBLAS picks for a shape can be several times slower than its best: on an
MI100, 3.5× for a 128×256×1024 fp16 product and 2–3× for small bf16 ones.
So the first call for each (types, transposes, size) times every solution
rocBLAS offers on that call's buffers, like MIOpen's Find, and caches the
fastest. That takes about 0.1 s per shape. Run each shape once before
`rocm_capture_begin()`, since an untuned shape inside a capture uses the
default solution. Because the choice comes from timing, two runs can pick
different solutions and differ in the last bits. `tensor_rocm.h` wraps the host-array ops as autograd `Tensor`
functions (`tensor_add_rocm`, `tensor_matmul_rocm_blas`, …) with a CPU
fallback.

On rocBLAS versus a hand-written kernel: this library's own tiled SGEMM
(`rocm_matmul_f32`) reaches about 9.5 TFLOPS at 4096³ on an MI100, while
rocBLAS reaches 33.7 TFLOPS using the matrix cores, and rocBLAS is also
faster at the small sizes a training loop uses (≈5 µs, close to the launch
floor). So matmuls use rocBLAS, and the custom kernels cover the work rocBLAS
doesn't do.

#### Attention {#rocm-attention}

Both attention ops take `q` as `[heads][seqLen][headDim]` and `k`, `v` as
`[heads][kvLen][headDim]`, all fp32, and write `out` as
`[heads][seqLen][headDim]`. `headDim` can be at most 128. A `scale` of 0 or
less means `1/√headDim`. Each op returns 1 once the work is queued, or 0 when the shape is
rejected or no ROCm GPU is present.

- **`rocm_flash_attention`** computes `softmax(scale · QKᵀ) V` exactly. It
  streams K/V tiles through shared memory with an online softmax
  (FlashAttention), so memory stays O(seqLen · headDim) and no
  seqLen × kvLen score matrix is ever stored. With `causal`, query `i` sees
  keys `j ≤ i + kvLen − seqLen` (the last query rows line up with the last
  keys, as in a KV cache), so `kvLen ≥ seqLen` is required. Fully masked
  tiles are skipped, which makes causal attention about twice as fast.
- **`rocm_linear_attention`** computes
  `φ(Q)(φ(K)ᵀV) / φ(Q)(φ(K)ᵀ1)` with `φ(x) = elu(x) + 1` (Katharopoulos et
  al.). Time and memory are linear in sequence length. With `causal`, each
  query sees only the keys up to its own position (`kvLen == seqLen`); this
  uses per-chunk running states and a masked term inside each chunk. It
  approximates softmax attention, so its results differ from flash attention.
  It matches `std::linear_attention_forward` on the CPU.

On GPUs with matrix cores (CDNA: MI100, MI200, MI300) both ops run on them in
fp32 (MFMA). Other AMD GPUs use CUDA-core kernels that give the same results up to
rounding. `rocm_attention_use_mfma(0)` selects the CUDA-core kernels on a
CDNA GPU too, for comparison; `rocm_attention_use_mfma(1)` restores the
default, and both return whether the matrix-core kernels are in use.

MI100, batch 4 × 16 heads, fp32, ms per call, against PyTorch 2.9.1 on ROCm on
the same GPU ([full results](/benchmarks#ml-attention)):

| Op | `headDim` | Tokens | Causal | SafeC | PyTorch |
|---|---:|---:|:---:|---:|---:|
| flash | 64 | 4096 | no | 10.7 (25.7 TFLOPS) | 42.8 |
| flash | 64 | 4096 | yes | 5.48 | 56.2 |
| flash | 128 | 4096 | no | 24.8 (22.2 TFLOPS) | 53.4 |
| linear | 64 | 4096 | no | 0.464 | 1.40 |
| linear | 64 | 16384 | yes | 2.76 | 14.6 |
| linear | 128 | 4096 | yes | 2.84 | 8.73 |

### Device-resident training on ROCm — `nn_rocm.h` {#rocm-training}

```c
#include <std/ml/nn_rocm.h>   // link: the gpu_rocm libraries, plus -lMIOpen for MIOpen convolutions
```

[`nn.h`](#training), [`trainer.h`](#training), [`convnet.h`](#convnet),
[`autoencoder.h`](#autoencoders), [`gan.h`](#gans) and [`rl.h`](#rl) train
on the CPU with autograd. `nn_rocm.h` trains the same models on an AMD GPU
and keeps everything in GPU memory: parameters, activations, gradients,
optimizer state, the dataset and the running loss. A training step moves
nothing between host and device. The models are built from the host ones
and copied back with their `download` methods, so the rest of `std::ml`
(evaluation, sampling, saving) works on the result. The same models are
also wrapped as `RocmTrainer`, `RocmGanTrainer`, `RocmDqnAgent` and
`RocmPgAgent`, which satisfy the [training traits](#train-traits), so code
written against `train.h` runs here when built with the `rocm` feature.

```c
struct Mlp net = Mlp::new(784UL, 256UL, 2UL, 10UL, ACT_RELU, ACT_NONE, &rng);
struct RocmNet dnet = RocmNet::from_mlp(&net, 128UL);           // batches of up to 128 rows
struct RocmOptimizer opt = RocmOptimizer::new(OPT_ADAM, (float)0.001);
dnet.add_params(&opt);
struct TrainReport rep = rocm_trainer_fit(&dnet, rocm_net_ce_step, &opt, &train, &cfg);
dnet.download(&net.params);   // back into the host Mlp
```

| Part | Functions |
|---|---|
| Nets | `RocmNet`: `RocmNet::from_mlp`, `RocmNet::from_convnet` (every `ConvNet` layer kind, its running statistics and training mode), or `RocmNet::new` and the `RocmNet` methods `add_linear` / `add_conv2d(_ex)` / `add_conv_transpose2d(_ex)` / `add_maxpool` / `add_avgpool` / `add_global_pool` / `add_upsample` / `add_resize` / `add_reshape` / `add_batchnorm` / `add_groupnorm` / `add_dropout`, `spectral_norm`, and skips (`add_save`, then `add_skip` (add, optional 1×1 projection) or `add_concat`) |
| Passes | `RocmNet` methods `forward`, `grad_output`, `backward`; `set_training` (batch norm statistics, dropout) |
| Losses | `rocm_cross_entropy` (class indices), `rocm_bce_logits`, `rocm_mse`: each writes its gradient and adds to a loss sum on the GPU |
| Steps | `rocm_net_ce_step`, `rocm_net_mse_step`, `rocm_net_bce_step`, `rocm_net_recon_bce_step`, `rocm_net_recon_mse_step`, `rocm_vae_step`, `rocm_conv_autoencoder_step`: forward, loss and backward (functions, so they can be passed to `rocm_trainer_fit`) |
| Optimizer | `RocmOptimizer` (`RocmOptimizer::new`, `RocmOptimizer::from` an `Optimizer`, `net.add_params(&opt)`, `opt.step()`): every `OPT_*` kind with `Optimizer::step`'s arithmetic, gradient-norm clipping (`clipNorm`) and weight clamping (`clampWeights`) |
| Training loop | `rocm_trainer_fit(&model, step, &opt, &data, &cfg)`: `trainer_fit` with the same batch order |
| Models | `RocmVae::new` / `RocmVae::new_conv` (+ conditional), `RocmConvAe::new`, `RocmGan::new` / `RocmGan::new_dcgan` with `RocmGan::train` (every GAN loss, gradient penalty, conditional models, the averaged generator), `RocmDqn::new`, `RocmPolicy::new` (REINFORCE, A2C, PPO) |
| Transfer | `RocmNet` methods `download` / `upload` (parameters), `download_stats` / `upload_stats` (running statistics, spectral norms' `u`), `download_convnet`, `download_mlp`, and `download` on every device model |
| Engine, precision | `rocm_conv_engine(ROCM_CONV_AUTO \| ROCM_CONV_IM2COL)`, `net.set_precision(ROCM_FP16 \| ROCM_BF16)`, `net.set_loss_scale(s)`, `net.loss_scale()` |

**How a step runs.** Every layer has a hand-written backward pass. Matmuls
go to rocBLAS. Bias, activations, norms, pooling, resizing and their
gradients are HIP kernels compiled with hiprtc on first use. Batches are
NCHW rows, `convnet.h`'s layout.

- **Training loop.** `rocm_trainer_fit` uploads the dataset once. Each
  epoch it uploads only the shuffled row order, the same order
  `trainer_fit` uses for that seed, and gathers the batches on the GPU. The
  first step for each batch size runs eagerly, which also lets rocBLAS and
  MIOpen pick their kernels. After that, `ROCM_TRAIN_CHUNK` (4) steps at a
  time are recorded into a `RocmGraph` and replayed. The loss is read back
  once per epoch.
- **Optimizer state.** Hyperparameters and the step count live on the GPU,
  so a recorded step stays correct when it is replayed.
- **Convolutions.** They run on MIOpen when the program links `-lMIOpen`
  (the symbols are weak), and otherwise on an im2col / col2im + rocBLAS
  engine. The first pass of each fp32 shape times both engines and keeps
  im2col when it is clearly faster. That happens for small convolutions,
  where MIOpen's fixed cost per call dominates.
- **Fusions.** Batch norm takes one launch for its statistics. It folds in
  the bias of the convolution before it and applies its activation's
  gradient in the same pass. Skip layers share their neighbours' buffers
  instead of copying them. Long weight gradients use a split-K GEMM when
  that is faster than rocBLAS for the shape.
- **Small nets.** A small fully connected net (2-32-32-2 at batch 32, for
  example) runs a whole step as one kernel, since the fixed cost of a
  kernel, about 1.3 µs on an MI100 even inside a graph, outweighs its work.

**Random numbers.** The GAN, DQN and policy-gradient agents draw every
random choice from the host model's own `Rng`, so they act step for step
like the CPU versions. The VAEs' reparameterization noise and the dropout
masks are drawn on the GPU, so those differ from the CPU's draws.

#### Mixed precision {#rocm-training-mixed-precision}

`net.set_precision(ROCM_FP16)` (or `ROCM_BF16`) switches a
`RocmNet` to 16-bit activations. Call it after the last layer and before
`RocmNet::add_params`.

- **Storage.** Activations and their gradients are stored in 16 bits, and
  all arithmetic is fp32. Weights, gradients and optimizer state stay fp32;
  the weights are rounded to 16 bits for each forward pass.
- **Unchanged interface.** The input, the output and their gradients stay
  fp32, so the losses, the step functions and `rocm_trainer_fit` work
  without changes.
- **Engines.** Linear layers use 16-bit rocBLAS GEMMs. 16-bit convolutions
  need MIOpen; `RocmNet::set_precision` returns 0 without it.
- **fp16 loss scale.** fp16 adds a dynamic loss scale that lives on the
  GPU, like PyTorch's `GradScaler`. It starts at 65536 and multiplies the
  output gradient, and the optimizer divides it back out. A step whose
  gradients overflowed is skipped and halves the scale; 2000 good steps in
  a row double it. All of this happens on the GPU, so it works inside
  replayed graphs. `RocmNet::loss_scale` reads the scale.
- **Scope.** GAN, VAE and RL models stay fp32.

On an MI100, fp16 is the faster 16-bit type: MIOpen's fp16 kernels use the
matrix cores, while its bf16 convolutions run at about fp32 speed.

MI100, ms per training run, against PyTorch 2.9.1 on ROCm on the same
GPU ([all workloads](/benchmarks#ml-training-gpu)):

| Workload | SafeC fp32 | SafeC fp16 | SafeC bf16 | PyTorch fp32 | PyTorch AMP fp16 | PyTorch AMP bf16 |
|---|---:|---:|---:|---:|---:|---:|
| ResNet (3 residual blocks), 3×32×32, batch 128, 16 steps | 75.3 | **57.9** | 79.5 | 98.9 | 74.3 | 271 |
| U-Net, 1×32×32, batch 32, 32 steps | 50.5 | **44.7** | 69.8 | 146 | 154 | 277 |
| CNN + batch norm, 1×28×28, batch 128, 32 steps | 31.2 | **29.6** | 32.5 | 60.7 | 58.3 | 94.0 |
| MLP 784→1024→1024→10, batch 256, 64 steps | 27.6 | 26.6 | **25.7** | 56.6 | 70.2 | 63.3 |
| DCGAN, 28×28, batch 64, 32 D + 32 G steps | **56.1** | — | — | 106 | — | — |
| Conv VAE, 28×28, batch 64, 32 steps | **26.8** | — | — | 53.3 | — | — |

::: tip Verification
`tests/stdlib/rocm/nn_rocm_check.sc` (in `tests/stdlib/rocm/run.sh`)
compares every layer kind's gradients, every optimizer, `rocm_trainer_fit`,
the conv autoencoder and the GAN, DQN and policy-gradient steps against the
CPU code. They agree to about 1e-7 on both convolution engines. The VAEs
are compared on loss levels and dropout on its statistics, since their
random draws differ. The gradient cases run again in fp16 (2% tolerance)
and bf16 (10%). Short ResNet trainings check that 16-bit losses track fp32,
and an fp16 run that starts with an overflowing loss scale recovers. 387
checks in total.
:::

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
through a pinned staging buffer, and so take one DMA. Copies of 1 MiB and up
in either direction run as four parallel lanes (the caller plus three helper
threads started on first use). Each lane stages its slice through pinned
memory on its own stream, so the DMA of one chunk overlaps the copy of the
next. On the H200 host that moves 16 MB in 0.45–0.7 ms, against 1.05 ms for
the driver's own pageable-memory path, which copies on one thread. A one-block reduction
stores its result directly, so `cuda_mse_grad`/`cuda_sum` need no zeroing
memset. cuBLAS gets a fixed 32 MB workspace, so its calls can be captured
into graphs.

| Group | Functions |
|---|---|
| Device | `cuda_available`, `cuda_device_name`, `cuda_sync` |
| Buffers | `CudaBuf::alloc`, `CudaBuf::upload`, `cuda_write`, `cuda_read`, `CudaBuf::free`; raw byte copies `cuda_copy_to_device(dev, host, bytes)`, `cuda_copy_to_host` |
| Matmul | `cuda_gemm(a, ta, b, tb, c, m, n, k)`, `cuda_gemm_relu` (same arguments, fused ReLU) |
| Elementwise | `cuda_add`/`sub`/`mul`/`div`/`pow`, `cuda_scale`, `cuda_relu`, `cuda_gelu` (tanh approximation), `cuda_exp`/`log`/`sqrt`, `cuda_relu_grad` |
| Training | `cuda_sgd`, `cuda_sgd2`, `cuda_mse_grad(y, t, dy, loss)`, `cuda_sum` |
| fp16 / bf16 (`CudaHalfBuf`, as `gpu_rocm.h`'s `RocmHalfBuf`) | `CudaHalfBuf::alloc`/`upload`/`write`/`read`/`free`, `cuda_to_half`/`cuda_from_half`, `cuda_half_gemm` (tensor cores, fp32 accumulation), `cuda_half_gemm_relu`, `cuda_half_gemm_f32`, `cuda_half_relu`, `cuda_half_relu_grad`, `cuda_half_mse_grad` (fp32 loss), `cuda_half_sgd`, `cuda_half_sgd2`. 16-bit storage, fp32 arithmetic, one rounding per stored value (bit-identical to `float16.h`) |
| Mixed precision | The [ROCm mixed-precision ops](#gpu-mixed-precision) with `cuda_` names: `cuda_half_convert`, `cuda_to_half_many` / `cuda_from_half_many`, `cuda_half_unscale_many` / `cuda_unscale_many`, `cuda_zero`, `cuda_mp_sgd` / `cuda_mp_adam`, `cuda_mp_adam_many` (a whole model's Adam step, 24 tensors per launch), `cuda_half_gemm_strided` (strided-batched 16-bit GEMM), `cuda_half_add` / `sub` / `mul` / `scale` / `axpy`, `cuda_half_bias`, `cuda_half_maxpool2d` / `cuda_half_avgpool2d`; queued, so they can be recorded into a `CudaGraph`. On the H200 (bf16, vs PyTorch 2.10): a GPT-2-small AdamW step with the bf16 copy takes 1.11 ms with `cuda_mp_adam_many` vs 1.61 ms for fused `torch.optim.AdamW` + `_foreach_copy_`; unscaling is 1.1× faster than `_amp_foreach_non_finite_check_and_unscale_`; fp32 → bf16 casts and 16-bit add are within 7% of PyTorch; pooling and bias + ReLU are 1.3–1.6× faster |
| Decoding (one token at a time) | `cuda_half_gemv(w, x, y, n, k)`: `y = w x` with fp16/bf16 weights `[n][k]` and fp32 vectors, one to four warps per output row, 16-byte streaming loads (`ld.global.nc.L1::no_allocate`, four in flight) when `k` is a multiple of 8. On the H200 it is 1.3–2.2× faster than a one-row `cuda_half_gemm_f32` (cuBLAS) on Gemma 4 E2B's attention and down projections, 1.05× on the 262k-row LM head (4.47 TB/s), and 0.89× on the fused 12288 × 1536 gate/up. `cuda_rmsnorm(x, w, y, rows, n, eps)` per row, optional weight, in place allowed. Same API as [ROCm's](#rocm-hip-amd), taken from the [Gemma 4 CUDA engine](/benchmarks#ml-llm)'s kernels |
| Graphs | `cuda_capture_begin` / `cuda_capture_end` → `CudaGraph`, `cuda_graph_launch`, `CudaGraph::free` |
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
in front. The first convolution of each shape (plain or with ReLU) builds the
fused graph conv → + bias (→ ReLU) with cuDNN's graph API. It times the
engine configs cuDNN's heuristics suggest, and the legacy API's best
algorithm (`cudnnFindConvolutionForwardAlgorithm`), on that call's buffers,
then caches the fastest one with its own workspace. Make that first call
outside graph capture. Later calls cost a few microseconds of host time. The
legacy `cudnnConvolutionForward` spends about 40 µs of CPU per call on cuDNN 9.
Convolutions run in plain fp32 unless `cuda_set_tf32(1)` is on, the same
switch the cuBLAS GEMMs use.

| Group | Functions |
|---|---|
| Layers | `CudaConv2D::upload(&Conv2D)` → `CudaConv2D`, `CudaConv2D::free`, `cuda_conv_out_dim`; `CudaHalfConv2D::upload(&Conv2D, dtype)` → `CudaHalfConv2D` (weights rounded to fp16 / bf16), `CudaHalfConv2D::free` |
| Queued ops | `CudaConv2D::forward(x, y, batch, h, w)`, `CudaConv2D::forward_relu` (conv + bias + ReLU, fused), `CudaHalfConv2D::forward` / `CudaHalfConv2D::forward_relu` (16-bit `CudaHalfBuf`s, fp32 compute: the same fused conv + bias (+ ReLU) cuDNN graph plan as fp32, every engine config timed; 1×1 stride-1 convolutions as one strided-batched cuBLAS GEMM plus the fused bias kernel; on the H200, NCHW bf16 at batch 32, 1.2–1.5× faster than PyTorch's `conv2d` + `relu_` with `cudnn.benchmark`), `cuda_maxpool2d`, `cuda_avgpool2d`, `cuda_concat_channels` (two strided-copy kernels); in `gpu_cuda.h`: `cuda_upsample2x_nearest`, `cuda_copy`, `cuda_copy_rows(src, srcOff, srcStride, dst, dstOff, dstStride, rowLen, rows)` (2D strided copy, one kernel) |
| U-Net | `CudaUNet::new(&UNet, batch, h, w)` → `CudaUNet` (weights and every activation preallocated), `CudaUNet::forward(x, y)`, `CudaUNet::free` |
| Host arrays | `conv2d_forward_cuda`, `unet_forward_cuda`: the signatures of `Conv2D::forward`/`UNet::forward`. They upload the weights on every call and fall back to the CPU when no GPU is usable |

**`fft_cuda.h`** is the GPU counterpart of [`fft.h`](/stdlib/dsp#fft-fast-fourier-transform-radix-2-cooley-tukey).
Unlike `fft.h`, it accepts any length, not only powers of two. Forward
transforms are unnormalized and inverse ones include 1/N, as in `fft.h`.

| Group | Functions |
|---|---|
| Host arrays, `double` | `cuda_fft`, `cuda_ifft`, `cuda_fft_batch(data, n, batch, inverse)`, `cuda_fft2d`, `cuda_ifft2d` (`imaging.h`'s `fft2d`) |
| Queued, fp32 `CudaBuf` | `cuda_fft_c2c(in, out, n, batch, inverse)` (interleaved re/im), `cuda_fft_r2c` (n reals → n/2+1 complex), `cuda_fft_c2r` (overwrites its input, as cuFFT does) |

`tests/stdlib/cuda` checks both libraries against the CPU code: `Conv2D::forward`
at stride 1 and 2, batched; pooling, upsample and concat; a whole U-Net;
`fft`/`ifft`, `dft` for a non-power-of-two length, batched and 2D transforms;
and the fp32 device transforms. It also replays a convolution from a graph,
runs one with TF32 on and one on other buffers, and checks large and odd-sized
host transfers, including a write queued after kernels that still read the
old contents.

On the H200, the queued ops beat PyTorch-CUDA on batched convolutions (32
images: 0.164 ms vs 0.257 ms in fp32) and on the U-Net (batch 1: 0.175 ms vs
0.206 ms, or 0.164 ms as one graph). PyTorch is faster for a single
one-image convolution (0.022 ms vs 0.034 ms). The device FFTs match cuFFT
through PyTorch. See the [benchmarks](/benchmarks#ml-convolution).

### Attention on CUDA {#cuda-attention}

```c
#include <std/ml/gpu_cuda_attention.h>   // link: the gpu_cuda libraries (cuDNN is loaded at run time)
```

Batched multi-head versions of [`flash_attention_forward` and
`linear_attention_forward`](#attention) on `CudaBuf`s, queued on `gpu_cuda`'s
stream (so they order with the other ops and can be recorded into a
`CudaGraph`). Forward only.

- **Flash attention**, `softmax(Q Kᵀ · scale) V` without the score matrix. fp16/bf16 go to cuDNN's fused attention engine when `libcudnn.so.9` can be loaded with `dlopen` — SafeC builds the same graph cudnn-frontend's `sdpa()` does, one cached plan per shape — and otherwise to this library's FlashAttention-2 kernel on tensor cores (`mma.sync`, sm_80+, head dim ≤ 128). fp32 runs that kernel in 3xTF32 (fp32-level error, ~2e-7). Head dim 256 and older GPUs use a CUDA-core kernel with the same algorithm. `cuda_attention_use_cudnn(0)` forces the own kernels.
- **Linear attention**, φ(x) = elu(x) + 1, in O(n d²): up to head dim 128, a per-head pass walks the 64-key chunks with the state φ(K)ᵀV in registers (storing it before each chunk when causal), then every chunk's outputs run at once. Exact fp32 arithmetic for every data type.
- **Shapes**: the `cuda_flash_attention` / `cuda_linear_attention` / `_f32` forms take the same arguments as [ROCm's](#rocm-attention) `rocm_flash_attention` / `rocm_linear_attention` (fp32, `[heads][seq][dim]`). The `_ex` and `cuda_half_` forms take a `CudaAttnShape`: batch, grouped-query K/V (`kvHeads` dividing `heads`), and a sliding window. Head dims 32, 64, 96, 128, 256.
- **Masking**: query i sits at position `kvLen - seqLen + i` (the last `seqLen` keys are the queries' own tokens, any before them a cache, as in [`mps_flash_attn_bf16`](#mps-attention)). `causal` hides later keys; `window` (flash only) also hides keys `window` or more positions back.
- **Decoding**: when `(heads / kvHeads) · seqLen ≤ 16` (one or a few new tokens against a KV cache), flash attention in any dtype runs a split-KV (flash-decoding) kernel pair instead: the keys are cut into 32-key-multiple splits, about eight blocks per SM, each block reading every key and value once for all the query heads that share it, and a second kernel merges the partial softmaxes. On the H200, against the cuDNN path it replaces, a bf16 32k-token decode with 8 query heads over 1 kv head (head dim 256) drops from 1.40 ms to 36 µs and 32/8 heads at head dim 128 from 533 to 54 µs. PyTorch's `scaled_dot_product_attention` (FlashAttention-2's split-KV kernel on tensor cores) is still 1.1–1.8× faster. `cuda_attention_use_decode(0)` turns the path off.
- Each (kernel, dtype, head dim) variant is compiled by NVRTC on its first call, and cuDNN plans and the linear-attention and decode workspaces are made then too, so make one call per shape before capturing a graph.

| Group | Functions |
|---|---|
| Flash | `cuda_flash_attention(q, k, v, out, heads, seqLen, kvLen, headDim, scale, causal)`, `cuda_flash_attention_ex(q, k, v, out, shape, scale, causal)`, `cuda_half_flash_attention` (fp16/bf16 `CudaHalfBuf`s) |
| Linear | `cuda_linear_attention(q, k, v, out, heads, seqLen, kvLen, headDim, causal)`, `cuda_linear_attention_ex`, `cuda_half_linear_attention` |
| Host arrays | `cuda_flash_attention_f32`, `cuda_linear_attention_f32` (upload, compute, read back) |
| Control | `cuda_attention_head_dim_ok`, `cuda_attention_use_cudnn`, `cuda_attention_use_decode` |

On the H200, fp16/bf16 flash attention matches PyTorch's `scaled_dot_product_attention` (both use cuDNN), fp32 is 1.34× faster at head dim 64 and level at 128 (0.97–1.05×), and linear attention is up to 2.6× faster than PyTorch and up to 2.1× faster than flash-linear-attention (level with PyTorch's TF32 einsums at head dim 128 fp32 non-causal). See the [benchmarks](/benchmarks#ml-attention). `gpu_cuda.h` gained `cuda_compute_capability()`, `cuda_sm_count()` and `cuda_load_module(source, name)` for libraries like this one that bring their own NVRTC kernels.

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
| Layers | `RocmConv2D::upload(&Conv2D)` → `RocmConv2D`, `RocmConv2D::free`, `rocm_conv_out_dim`; `RocmHalfConv2D::upload(&Conv2D, dtype)` → `RocmHalfConv2D` (weights rounded to fp16 / bf16), `RocmHalfConv2D::free` |
| Queued ops | `RocmConv2D::forward(x, y, batch, h, w)`, `RocmConv2D::forward_relu`, `RocmHalfConv2D::forward` / `RocmHalfConv2D::forward_relu` (16-bit `RocmHalfBuf`s: MIOpen's half / bfloat16 convolution with fp32 accumulation, then the fused 16-bit bias kernel), `rocm_maxpool2d`, `rocm_avgpool2d`, `rocm_concat_channels` (one kernel for the whole batch); in `gpu_rocm.h`: `rocm_upsample2x_nearest`, `rocm_upsample2x_concat`, `rocm_copy` |
| U-Net | `RocmUNet::new(&UNet, batch, h, w)` → `RocmUNet`, `RocmUNet::forward(x, y)` (each decoder step upsamples straight into its concat buffer), `RocmUNet::free` |
| Host arrays | `conv2d_forward_rocm`, `unet_forward_rocm`, falling back to the CPU when no GPU is usable |

**`fft_rocm.h`** runs the transforms through hipFFT (rocFFT underneath), for
any length. Forward transforms are unnormalized and inverse ones include 1/N.

| Group | Functions |
|---|---|
| Host arrays, `double` | `rocm_fft`, `rocm_ifft`, `rocm_fft_batch(data, n, batch, inverse)`, `rocm_fft2d`, `rocm_ifft2d` |
| Queued, fp32 `RocmBuf` | `rocm_fft_c2c(in, out, n, batch, inverse)`, `rocm_fft_r2c`, `rocm_fft_c2r` (may overwrite its input) |

`tests/stdlib/rocm/rocm_dnn_fft_check.sc` runs the same checks as the CUDA
suite against the CPU code (73 checks on an MI100): convolutions at stride 1
and 2, batched, with ReLU and replayed from a graph; pooling, upsample and
concat; a whole U-Net; every FFT form; and fp16 and bf16 convolutions and
pooling against `cnn.h` on the same rounded inputs. 16-bit pooling is a HIP
kernel in `gpu_rocm.h`, since MIOpen has no bfloat16 pooling solver on every
GPU (none on the MI100).

On the MI100, one 64-channel convolution takes 0.027 ms against PyTorch-ROCm's 0.063 ms and the batch-1 U-Net 0.227 ms against 0.315 ms; at batch 16–32 the two are within 2%. Device-resident FFTs are 1.4–1.7× faster than through PyTorch. See the [benchmarks](/benchmarks#ml-convolution).

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

### Attention on MPS {#mps-flash-linear-attention}

```c
#include <std/ml/gpu_mps.h>   // Apple GPUs; link -framework Metal -framework Foundation
```

The same batched multi-head [`flash_attention_forward` and
`linear_attention_forward`](#attention) as [CUDA's](#cuda-attention), on
Metal buffers (`mps_upload_persistent`, `mps_upload_f16/bf16_persistent`,
`mps_alloc_persistent`). Forward only; all math in fp32.

- **Flash attention**, `softmax(Q Kᵀ · scale) V` without the score matrix: FlashAttention-2 on the GPU's 8×8 simdgroup matrix units for head dims up to 256 (Q block in registers, K/V tiles in threadgroup memory, online softmax on the fragments), a simdgroup-reduction kernel at 512. fp16 multiplies in half precision; bf16 is converted to fp32 as tiles are staged, since the M1 family has no bf16 matrix units.
- **Linear attention**, φ(x) = elu(x) + 1, in O(n d²). Head dims 64 and 128 run chunk-parallel on the matrix units — per-segment states φ(K)ᵀV, a prefix over 64-row chunks (causal) or their total (non-causal), then every chunk's outputs at once, with the states in a scratch buffer the library keeps. Other head dims, a KV-cache offset with `causal`, and the recurrent form below walk the sequence per head.
- **Shapes**: `mps_flash_attention` / `mps_linear_attention` / `_f32` take the same arguments as `rocm_` and `cuda_`'s (fp32, `[heads][seq][dim]`); the `_ex` forms take an `MpsAttnShape` (batch, grouped-query K/V, sliding window) and a dtype (`0` = fp32, `MPS_FP16`, `MPS_BF16`). Head dims 32, 64, 96, 128, 256, 512.
- **Masking**: as on CUDA — query i sits at position `kvLen - seqLen + i`; `causal` hides later keys; `window` (flash only) also hides keys `window` or more positions back. `scale` ≤ 0 means 1/√headDim.
- **Batching**: inside `mps_batch_begin()`/`mps_batch_end()` the calls are encoded into the batch's one command buffer; outside one, each call runs and waits.

| Group | Functions |
|---|---|
| Flash | `mps_flash_attention(q, k, v, out, heads, seqLen, kvLen, headDim, scale, causal)`, `mps_flash_attention_ex(q, k, v, out, shape, dtype, scale, causal)` |
| Linear | `mps_linear_attention(q, k, v, out, heads, seqLen, kvLen, headDim, causal)`, `mps_linear_attention_ex(q, k, v, out, shape, dtype, causal)` |
| Host arrays | `mps_flash_attention_f32`, `mps_linear_attention_f32` (upload, compute, read back) |
| Buffers | `mps_alloc_persistent(bytes)`, `mps_read_persistent(buf, dst, bytes)`, `mps_attention_head_dim_ok(headDim)` |

On an M1 Pro, fp32 flash attention is 1.01–1.42× faster than MLX's fused attention and 1.13–1.70× faster than PyTorch's `mps` device, bf16 at head dim 128 is level with MLX, fp16 and bf16 at 64 reach 0.80–1.02× of them (2.6–3.4 TFLOPS), and linear attention is 2.0–4.4× faster than vectorized PyTorch and MLX. See the [benchmarks](/benchmarks#ml-attention).

`tests/stdlib/mps/run.sh` builds `mps_attention_check`, which compares every path (both flash kernels, the chunked and sequential linear kernels, all three data types, GQA, KV-cache offsets, sliding windows, the host-array forms) with a double-precision reference and attention.h's CPU functions: 95 checks.

### MPS attention and transformer ops {#mps-attention}

bf16 building blocks for running a transformer layer on an Apple GPU. Tensors are raw bf16 bits (`unsigned short`, convert with `float16.h`), row-major, with heads side by side in each row — the layout a layer's projections produce: Q and outputs are `[rows, numHeads*headDim]`, K/V `[rows, numKvHeads*headDim]`. `numHeads` must be a multiple of `numKvHeads` (grouped-query attention). The flash and linear functions run on the [same kernels](#mps-flash-linear-attention) as `mps_flash_attention_ex` (head dims 32–512); the decode kernel takes 64, 128, 256 or 512. Like every MPS entry point, each returns 0 when it can't run (no device, unsupported shape) so the caller can fall back to the CPU.

| Function | Computes |
|---|---|
| `mps_attn_batch_bf16` | One new query token per sequence against that sequence's KV cache, for a whole batch and every head in one dispatch — the decode step. No KV-length limit; keys outside a sliding window are skipped. |
| `mps_flash_attn_bf16` | GPU counterpart of `flash_attention_forward` for any number of query rows (prefill), on the FlashAttention-2 kernel above. Causal and sliding-window masking. |
| `mps_linear_attn_bf16` | GPU counterpart of `linear_attention_forward` (`phi(x) = elu(x)+1`), non-causal or causal. |
| `mps_linear_attn_bf16_chained` | Causal linear attention with the recurrent state kept on the GPU and advanced in place — prefill once, then one token per decode step at a cost independent of sequence length. |

```c
int mps_attn_batch_bf16(const &unsigned short q, const &unsigned short kAll, const &unsigned short vAll,
                        const &unsigned int cacheLens, &unsigned short out,
                        unsigned long batchSize, unsigned long numHeads, unsigned long numKvHeads,
                        unsigned long headDim, unsigned long maxKvLen,
                        int isSliding, unsigned long slidingWindow);

int mps_flash_attn_bf16(const &unsigned short q, const &unsigned short k, const &unsigned short v,
                        &unsigned short out, unsigned long seqLen, unsigned long kvLen,
                        unsigned long numHeads, unsigned long numKvHeads, unsigned long headDim,
                        float scale, int causal, int isSliding, unsigned long slidingWindow);

int mps_linear_attn_bf16(const &unsigned short q, const &unsigned short k, const &unsigned short v,
                         &unsigned short out, unsigned long seqLen, unsigned long kvLen,
                         unsigned long numHeads, unsigned long numKvHeads, unsigned long headDim,
                         int causal);

// Recurrent linear-attention state (S and z for every head), GPU-resident.
?&void        mps_linear_attn_state_new(unsigned long numHeads, unsigned long headDim);   // zeroed
unsigned long mps_linear_attn_state_bytes(unsigned long numHeads, unsigned long headDim); // for mps_release_persistent
int mps_linear_attn_bf16_chained(&void bufQ, &void bufK, &void bufV, &void bufState, &unsigned short out,
                                 unsigned long seqLen, unsigned long numHeads,
                                 unsigned long numKvHeads, unsigned long headDim);

// Chained bf16 elementwise/normalization ops for the rest of the layer:
int mps_gated_gelu_bf16_chained(&void bufRaw, &unsigned short out, unsigned long rows, unsigned long intermediateSize);
int mps_add_bf16_chained(&void bufA, &void bufB, &unsigned short out, unsigned long n);       // residuals
int mps_gelu_mul_bf16_chained(&void bufA, &void bufB, &unsigned short out, unsigned long n);  // gelu(a) * b
int mps_rmsnorm_bf16_chained(&void bufX, &void weightBuf, &unsigned short out,
                             unsigned long rows, unsigned long cols, float eps);
```

- **Masking.** With `causal`, query `i` sits at position `kvLen - seqLen + i` — the last `seqLen` keys are the queries' own tokens and any before them are a cache — and sees keys at or before it. `isSliding` also hides keys `slidingWindow` or more positions behind the query. `mps_attn_batch_bf16`'s `cacheLens[b]` is each sequence's KV length including the new token.
- **Scale.** `mps_flash_attn_bf16` multiplies `q·k` by `scale`: pass `1/sqrt(headDim)` to match `flash_attention_forward`, or `1.0` for models that pre-scale q. The decode kernel applies no scale. Linear attention needs none, since dividing by `phi(q)·z` cancels any scaling of q.
- **Batching.** The one-shot functions work inside an open batch (results land in `out` at `mps_batch_end()`) or on their own (filled on return). `mps_attn_batch_bf16_chained` and `mps_flash_attn_bf16_chained` take device buffers and leave their output as `mps_batch_last_output_buffer()` — so attention can feed the output projection (`mps_matmul_abt_bf16_persistent`) in the same submission. `mps_matmul_abt_bf16_persistent` with `M == 1` uses a dedicated matrix-vector kernel.

Linear attention decode loop:

```c
match (mps_linear_attn_state_new(numHeads, headDim)) {
    case some(state): {
        mps_batch_begin();
        mps_linear_attn_bf16_chained(promptQ, promptK, promptV, state, promptOut, promptLen,
                                     numHeads, numKvHeads, headDim);
        mps_batch_end();
        // each generated token:
        mps_batch_begin();
        mps_linear_attn_bf16_chained(tokQ, tokK, tokV, state, tokOut, 1, numHeads, numKvHeads, headDim);
        mps_batch_end();
        // ...
        mps_release_persistent(state, mps_linear_attn_state_bytes(numHeads, headDim));
    }
    case null: { /* no Metal device: use linear_attention_forward on the CPU */ }
}
```

On an M1 Pro, a decode step over 8 sequences × 2048 cached tokens (8 heads, head dim 256) takes about 0.3–0.5 ms and a 1024-token causal flash-attention prefill (8 heads, head dim 256) about 7 ms.

### Tensor wrappers

Autograd `Tensor` ops that run their forward pass on a backend and reuse the CPU backward. Each falls back to the CPU when its device is unavailable.

| Header | Functions | Device |
|--------|-----------|--------|
| `tensor_gpu.h` | `tensor_{add,sub,mul,scale,relu,square,sum,matmul}_gpu` | MPS |
| `tensor_cuda.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_cuda`, `tensor_matmul_cuda_blas` | CUDA |
| `tensor_rocm.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_rocm`, `tensor_matmul_rocm_blas` | ROCm |
| `tensor_spirv.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_spirv` | Vulkan |
| `tensor_webgpu.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_webgpu` | WebGPU |
| `tensor_vdsp.h` | `tensor_{add,sub,mul,sum}_vdsp` | Apple Accelerate vDSP (`-framework Accelerate`); elsewhere the core's own SIMD ops |
| `tensor_blas.h` | `tensor_matmul_blas` | through `sgemm`: a linked CBLAS (Accelerate / AOCL-BLAS / oneMKL / OpenBLAS), else the built-in kernel |
| `tensor_auto.h` | `tensor_{add,sub,mul,sum,matmul}_auto` | picks core (`sgemm`) / vDSP / BLAS by size |

`tensor_gpu.h` also controls MPS batching and persistence at the tensor level:

```c
void tensor_gpu_batch_begin();
void tensor_gpu_batch_end();
void Tensor::gpu_mark_persistent() const;   // keep t's data resident on the GPU
void tensor_gpu_release_all_persistent();
```

`tensor_auto.h` is CPU-only on purpose: across every size benchmarked (matmul from 2×2×2 to 128×128×128, and a 128×512×1024×256 training step) BLAS never lost to the core loop (measured before `tensor_matmul` moved to `sgemm`) and MPS never beat BLAS, so "auto" doesn't pick the GPU. Ask for it explicitly with `tensor_matmul_on(..., DEVICE_MPS)`.

### Backend status {#backend-status}

| Backend | Elementwise ops | Matmul (std kernel) | Matmul (vendor BLAS) | Status |
|---|---|---|---|---|
| ROCm / HIP | real (HIP C++, compiled at first use by hiprtc) | real (64×64 LDS-tiled SGEMM) | real (rocBLAS) | **measured** on MI100: 325 + 73 checks in `tests/stdlib/rocm`, and the [MLP benchmarks](/benchmarks#ml-mlp) |
| CUDA | real (CUDA C++, compiled at first use by NVRTC) | real (64×64 shared-memory-tiled SGEMM) | real (cuBLAS; cuBLASLt for GEMM + ReLU) | **measured** on H200: 216 checks in `tests/stdlib/cuda` (including cuDNN and cuFFT), the [MLP benchmarks](/benchmarks#ml-mlp) and the [conv/FFT benchmarks](/benchmarks#ml-convolution) |
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
void fp16_to_bf16_bulk(const &unsigned short src, &unsigned short dst, unsigned long n);
void bf16_to_fp16_bulk(const &unsigned short src, &unsigned short dst, unsigned long n);

int         half_cpu_features();        // HALF_CPU_* bits, detected once
const char* half_cpu_features_name();   // "F16C, AVX512-BF16", ..., "none"
```

**What the CPU does natively.** Every x86-64 CPU since 2012 converts fp16
in hardware (F16C), and so does every AArch64 core (FCVTL / FCVTN). bf16 is
newer and optional: AVX512-BF16 on Cooper Lake, Sapphire Rapids and Zen 4
or later, and FEAT_BF16 on Armv8.6 cores (Neoverse V1 / N2, Apple M2 and
later). fp16 arithmetic (AVX512-FP16, FEAT_FP16) is optional too.
`half_cpu_features()` reports what this CPU has:

| Bit | Meaning | x86-64 | AArch64 |
|---|---|---|---|
| `HALF_CPU_F16_CONVERT` | fp16 ↔ fp32 instructions | F16C | always |
| `HALF_CPU_F16_ARITH` | fp16 arithmetic | AVX512-FP16 | FEAT_FP16 |
| `HALF_CPU_BF16_CONVERT` | fp32 → bf16 instructions | AVX512-BF16 | FEAT_BF16 |
| `HALF_CPU_BF16_DOT` | bf16 dot products | AVX512-BF16 (`VDPBF16PS`) | FEAT_BF16 (`BFDOT`) |

Detection runs once: cpuid and xgetbv on x86-64, `getauxval` on Linux
AArch64, `sysctl` on Apple. `SAFEC_HALF=soft` reports none and makes the
bulk conversions use the portable code, for comparison.

**Bulk conversions.**

- fp16: F16C on x86-64 and FCVTL / FCVTN on AArch64.
- fp32 → bf16: AVX512-BF16 where present, 16 values per instruction.
  `VCVTNEPS2BF16` flushes fp32 subnormals to zero, so a block containing
  one is spotted with `VFPCLASSPS` and converted in software instead.
- Everywhere else: bf16 → fp32 is a shift, and fp32 → bf16 an integer
  rounding, both in loops the compiler vectorizes.
- Arrays of 256K values or more are split across the thread pool.

The results are bit-identical to the scalar functions, except that NaNs
may keep more of their payload. The tests compare every one of the 65,536
fp16 patterns, and random fp32 patterns of every class, in both modes.

On a Ryzen 7 7800X3D (one thread, 16M values, read + write bandwidth):

| Conversion | Hardware | `SAFEC_HALF=soft` |
|---|---|---|
| fp32 → fp16 | 47 GB/s | 2.8 GB/s |
| fp16 → fp32 | 43 GB/s | 7.0 GB/s |
| fp32 → bf16 | 53 GB/s | 27 GB/s |
| bf16 → fp32 | 39 GB/s (a shift either way) | 41 GB/s |

Across all 16 threads, every conversion reaches 58–99 GB/s with the
hardware paths.

### Half-precision tensors — `tensor_half.h` {#half-precision-tensors}

```c
#include <std/ml/tensor_half.h>
```

Two layers:

- **`HalfTensor`** stores a 1D/2D tensor in fp16 or bf16 — half the memory of a float32 `Tensor`. Its ops widen operands to float32, compute, and round the result back to the storage format, the same arithmetic a GPU half path performs; `half_matmul` accumulates each dot product in float32 and rounds once, like tensor cores. No autograd on this layer.
- **Mixed precision on float32 `Tensor`s, with autograd.** `Tensor::cast` rounds values to fp16/bf16 precision in the forward pass and passes gradients through unchanged (the straight-through estimator), so a graph can compute in reduced precision while weights and gradients stay float32. `LossScaler` is fp16 training's dynamic loss scaling; bf16 shares float32's exponent range and doesn't need it.

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

static struct HalfTensor HalfTensor::new_1d(unsigned long n, int dtype);           // zero-filled
static struct HalfTensor HalfTensor::new_2d(unsigned long rows, unsigned long cols, int dtype);
struct HalfTensor Tensor::to_half(int dtype) const;                // round to nearest even
&Tensor           HalfTensor::to_tensor(int requiresGrad) const;     // exact

// Shapes and dtypes must match; otherwise the result is empty (size 0).
struct HalfTensor half_add(const &HalfTensor a, const &HalfTensor b);
struct HalfTensor half_sub(const &HalfTensor a, const &HalfTensor b);
struct HalfTensor half_mul(const &HalfTensor a, const &HalfTensor b);       // elementwise
struct HalfTensor HalfTensor::scale(float k) const;
struct HalfTensor HalfTensor::relu() const;
struct HalfTensor half_matmul(const &HalfTensor a, const &HalfTensor b);    // float32 accumulate
float             HalfTensor::sum() const;                            // float32 accumulate
int               HalfTensor::gemv(const &float x, &float y) const; // y = w x, float32 x / y
int               HalfTensor::gemv_batch(const &float x, unsigned long m, &float y) const; // m vectors: x [m][k], y [m][n]

// Mixed-precision batches: fp32 masters <-> 16-bit copies, 16-bit gradients back
int  tensor_to_half_into(const &Tensor t, &HalfTensor dst);                 // into an existing copy
int  half_to_tensor_into(const &HalfTensor h, &Tensor dst);
int  half_convert(const &HalfTensor src, &HalfTensor dst);                  // fp16 <-> bf16
struct Vec<struct HalfTensor> tensors_to_half(const &Vec<&Tensor> ts, int dtype);
int  tensors_to_half_into(const &Vec<&Tensor> ts, &Vec<struct HalfTensor> dst);
int  halves_to_tensors(const &Vec<struct HalfTensor> hs, &Vec<&Tensor> dst);
void half_tensors_free(&Vec<struct HalfTensor> hs);
int  half_grads_accumulate(const &Vec<struct HalfTensor> grads, &Vec<&Tensor> params, float invScale);  // 1 = all finite

&Tensor Tensor::cast(int dtype) const;                            // straight-through gradient
&Tensor tensor_matmul_lowp(const &Tensor a, const &Tensor b, int dtype);    // cast(cast(a) @ cast(b))

struct LossScaler {
    float scale;            // multiply the loss by this before Tensor::backward()
    float growthFactor;     // 2.0
    float backoffFactor;    // 0.5
    int   growthInterval;   // 2000 clean steps
    int   goodSteps;
};
static struct LossScaler LossScaler::new();                  // scale 65536 (PyTorch GradScaler's defaults)
int  LossScaler::unscale(&Tensor p);                  // divide p.grad by scale; 0 if any grad is inf/NaN
int  LossScaler::unscale_all(&Vec<&Tensor> params);   // every tensor in a list
void LossScaler::update(int allFinite);               // grow after clean steps, back off after overflow
```

A `HalfTensor`'s `data` has exactly the layout the GPU half-precision functions read and write (`mps_upload_f16_persistent` and friends).

`HalfTensor::gemv` is the matrix-vector product of decoding one token: `w` is a linear layer's 2D fp16/bf16 weight (`[n][k]`), `x` and `y` are float32. Each weight is widened in registers, so there is no float32 copy of the matrix; it is vectorized for the CPU's SIMD tier and split over rows on the thread pool. On a Ryzen 7 7800X3D (16 threads) a bf16 24576 × 1536 product takes 1.0 ms (76 GB/s of weights, near the memory's bandwidth), 6× faster than `sgemm` with one row on widened float32 weights; fp16 reaches 35 GB/s (its widening is done in integer arithmetic). It took the [Gemma 4 E2B](/benchmarks#ml-llm) CPU decode from 4.43 s to 0.225 s per token. On AArch64 a NEON kernel loads eight weights as four 32-bit lanes and widens both halves in place. On an M1 Pro (8 performance cores) it reaches 95–113 GB/s for bf16 and 40–55 GB/s for fp16. `HalfTensor::gemv_batch` does the same for `m` vectors at once, for a short prompt's prefill or speculative verification, and reads the weights from memory once for all of them. Six vectors over a bf16 24576 × 1536 weight take 1.65 ms on the M1 Pro, against 4.35 ms for six `HalfTensor::gemv` calls. With it, the [Gemma 4 E2B](/benchmarks#ml-llm) CPU path on the M1 Pro takes 136 ms per token.

Storage and arithmetic in bf16:

```c
#include <std/ml/tensor_half.h>
#include <std/io.h>

int main() {
    // Store weights in bf16 (half the memory), compute a matmul in bf16.
    struct HalfTensor w = HalfTensor::new_2d(2UL, 2UL, TENSOR_BF16);
    struct HalfTensor x = HalfTensor::new_2d(2UL, 1UL, TENSOR_BF16);
    w.set2(0UL, 0UL, 0.5f);  w.set2(0UL, 1UL, -1.0f);
    w.set2(1UL, 0UL, 2.0f);  w.set2(1UL, 1UL, 0.25f);
    x.set1(0UL, 3.0f);       x.set1(1UL, 4.0f);
    struct HalfTensor y = std::half_matmul(&w, &x);      // f32 accumulate, bf16 result
    std::println_float((double)y.at1(0UL));             // -2.5
    std::println_float((double)y.at1(1UL));             // 7

    // Back to float32 for anything that needs autograd.
    &Tensor yf = y.to_tensor(0);
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
    &Tensor loss = std::tensor_mul(diff, diff).sum();
    loss.scale(scaler.scale).backward();

    int finite = scaler.unscale(w);
    if (finite) {
        struct Slice<float> g = std::slice_of(w.grad, w.size);
        for (unsigned long i = 0UL; i < w.size; i = i + 1UL) {
            w.set1(i, w.at1(i) - lr * *g.at(i));
        }
    }
    scaler.update(finite);
    w.zero_grad();
    return finite;
}

int main() {
    float wv[2] = { 0.1f, 0.2f };
    float xv[2] = { 1.0f, 2.0f };
    float tv[1] = { 1.0f };
    &Tensor w = Tensor::from_2d(&wv[0], 2UL, 1UL, 1);
    &Tensor x = Tensor::from_2d(&xv[0], 1UL, 2UL, 0);
    &Tensor t = Tensor::from_2d(&tv[0], 1UL, 1UL, 0);
    struct LossScaler scaler = LossScaler::new();
    int ok = 1;
    for (int i = 0; i < 20; i = i + 1) ok = ok & train_step(w, x, t, &scaler, 0.05f);
    float pred = w.at1(0UL) + w.at1(1UL) * (float)2.0;
    return (ok && pred > (float)0.95 && pred < (float)1.05) ? 0 : 1;
}
```


### Mixed precision on the GPU {#gpu-mixed-precision}

```c
#include <std/ml/gpu_rocm.h>     // ROCm; gpu_cuda.h has the same functions with cuda_ names
#include <std/ml/gpu_miopen.h>   // 16-bit convolution (gpu_cudnn.h on CUDA)
```

Mixed-precision training normally keeps:

- fp32 master weights and optimizer state;
- a 16-bit copy of the weights for the forward and backward passes;
- gradients that carry the loss scale.

ROCm and CUDA have the same queued ops for every part of the step:

| Step | ROCm (`cuda_` on CUDA) |
|---|---|
| Copies of many tensors | `rocm_to_half_many(&f32s, &halves)`, `rocm_from_half_many`: up to 32 buffers per launch, one dtype per call |
| fp16 ↔ bf16 | `rocm_half_convert(src, dst)` |
| 16-bit compute | `rocm_half_gemm` / `_f32` / `_relu`, `RocmHalfConv2D::forward` / `forward_relu`, `rocm_half_add` / `sub` / `mul` / `scale` / `axpy`, `rocm_half_bias(y, bias, rows, channels, plane, relu)`, `rocm_half_maxpool2d` / `avgpool2d`, `rocm_half_relu` / `_relu_grad` |
| Unscaling | `rocm_half_unscale_many(&g16, &g32, 1 / scale, flag)` (16-bit gradients → fp32), `rocm_unscale_many(&g32, &g32, …)` (fp32, in place): set `flag[0] = 1` on any inf / NaN; `rocm_zero(flag)` first |
| Update | `rocm_mp_adam(w32, m, v, g32, w16, lr, β1, β2, eps, weightDecay, decoupled, step, flag)` (Adam / AdamW) and `rocm_mp_sgd(w32, g32, vel, w16, lr, momentum, weightDecay, flag)`: `nn.h`'s `Optimizer::step` arithmetic, the rounded weights also written to `w16`, and the whole update skipped on the GPU when `flag[0] != 0` |

Because the skip happens on the GPU, an overflowed step needs no host round
trip before the update. Read the 4-byte flag once per step, whenever it's
convenient, to grow or back off the `LossScaler`. bf16 shares fp32's exponent
range and usually trains without loss scaling; pass an invalid `flag`
(`dev == 0`) to `rocm_mp_*` to never skip.

Every kernel computes in fp32 and rounds once per stored value. Updates run
without FMA contraction (`fp contract(off)` in HIP, `__fmul_rn`-style
intrinsics in CUDA), so the results match the CPU arithmetic.
`tests/stdlib/rocm/rocm_check.sc` checks this on an MI100:

- fp16 ↔ bf16 over every 16-bit pattern;
- 40 buffers through bf16 and back;
- unscaling, and the overflow flag;
- three Adam, AdamW and momentum-SGD steps against the CPU (within 1e-5)
  with their bf16 copies, and a flagged step changing nothing;
- every 16-bit elementwise op and the NCHW bias bit-for-bit.

The CUDA versions have the same tests in `tests/stdlib/cuda/cuda_check.sc`.
Their kernels compile (clang, sm_80) and the tests build, but they haven't
yet run on an NVIDIA GPU.

## Device selection {#device-selection}

Every backend names its op explicitly (`tensor_matmul` vs `_blas` vs `_gpu` vs `_cuda` vs `_rocm`) — precise, but awkward for a caller that wants to pick a device once. `tensor_matmul_on(a, b, device)` / `a.relu_on(device)` dispatch over a `Device` enum instead — verified bit-identical across CPU/CPU+BLAS/MPS:

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
&Tensor Tensor::relu_on(enum Device device) const;

#include <std/ml/transformer_dispatch.h>
&Tensor jit_block_forward_on(const &JiTBlock block, const &Tensor x, enum Device device);
&Tensor jit_forward_on(const &JiT model, const &Tensor patches, const &Tensor cond, enum Device device);
```

Building this exposed a real name collision: `activations.sc` (forward-only ops) and `tensor_nn.sc` (autograd ops, needed by every GPU backend) both defined `Tensor::sigmoid`/`Tensor::relu`/etc. — no program could link both, so no `activations.sc`-based layer could use a GPU backend. Fixed by suffixing `activations.h`'s forward-only functions with `_fwd` and updating `attention.sc`/`transformer.sc`/`rnn.sc`'s call sites.

`jit_block_forward_on(block, x, device)` threads device selection through a real layer (JiTBlock's Q/K/V/output projections + FFN) — verified bit-identical to the reference across CPU/CPU+BLAS/MPS:

| Path | y[0] | y[1] | y[2] |
|---|---|---|---|
| `JiTBlock::forward` (reference) | -0.479727 | -0.589425 | -0.537808 |
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
  `client.chat()` POSTs to `<host>:<port>/v1/chat/completions`.
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

static struct LlmClient LlmClient::new(const &char host, unsigned short port, const &char model, const &char apiKey);
// The assistant's reply text; *ok = 0 on transport or response-shape failure.
struct String LlmClient::chat(&Vec<struct LlmMessage> messages, ?&int ok);

struct String prompt_template_render(const &char tmpl, const &Value vars);   // {name} -> vars.name

typedef fn struct Value(const &Value input) ChainStepFn;
static struct Chain Chain::new();
void         Chain::add_step(ChainStepFn step);
struct Value Chain::run(const &Value input);
void         Chain::free();

typedef fn struct Value(const &Value state) GraphNodeFn;
typedef fn struct String(const &Value state) GraphRouterFn;   // name of the next node, or "__end__"
static struct Graph Graph::new(const &char entryNode);
void         Graph::add_node(const &char name, GraphNodeFn nodeFn, const &char nextNode);
void         Graph::add_conditional_node(const &char name, GraphNodeFn nodeFn, GraphRouterFn router);
struct Value Graph::run(struct Value initialState, int maxSteps);
void         Graph::free();

struct TraceEvent { struct String name; struct String inputJson; struct String outputJson; double durationMs; };
static struct Tracer Tracer::new();
void          Tracer::record(const &char name, const &Value input, const &Value output, double durationMs);
struct String Tracer::to_json() const;
void          Tracer::free();
```

All five verified end-to-end, including `LlmClient` against a real local
mock HTTP server standing in for an LLM endpoint (the same technique
`std/rpc/server_fn.h`'s own tests use — nothing here calls out to a real
hosted LLM API).
