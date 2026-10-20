# Machine Learning (`std/ml/`)

A CPU tensor/autograd core, a wide attention/diffusion/architecture
library, GPU backends, and an LLM orchestration layer
(LangChain/LangGraph/LangSmith-inspired).

```c
#include <std/ml/tensor.h>
```

::: warning Scope
This is a from-scratch, single-session implementation, not a port of an
existing framework. `tensor.h` covers 1D/2D f64 tensors and the ops
needed for the modules below — not a general n-dimensional array
library. See `std/ml/ROADMAP.md` in the repo for exactly what's verified
vs. deliberately deferred (DPM-Solver-v3's calibration-dependent design,
EDM's own VE-flavored samplers, positional encoding, and patchify — see
that file for why each one is out of scope rather than faked).
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

CPU tensors (1D/2D, `double`) with reverse-mode autograd — PyTorch-style
ergonomics: build a computation graph implicitly by calling ops on
`requiresGrad` tensors, then call `tensor_backward()`.

```c
&Tensor x = tensor_from_1d(values, 3UL, /*requiresGrad=*/1);
&Tensor y = tensor_sum(tensor_mul(x, x));  // y = sum(x^2)
tensor_backward(y);                         // x.grad now holds 2x
```

Ops: `tensor_add`/`tensor_sub`/`tensor_mul`/`tensor_scale`/`tensor_matmul`/
`tensor_relu`/`tensor_sum`. Verified against hand-computed gradients and a
real finite-difference check on a matmul→relu→sum chain.

## Activations

```c
#include <std/ml/activations.h>
```

Elementwise `tensor_sigmoid`/`tensor_tanh`/`tensor_silu`/`tensor_gelu`;
`tensor_layernorm_rows` (row-wise mean-0/var-1 normalization, no affine —
apply your own scale/shift for AdaLN-style conditioning, see DiT below);
`tensor_residual_add` (a named `tensor_add` alias for residual/skip-
connection call sites).

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

## U-Net

```c
#include <std/ml/unet.h>
```

A fixed 2-level encoder/decoder CNN with skip connections
(Ronneberger et al. 2015) — the classic DDPM-style diffusion denoiser
backbone. Verified via output-shape checks and full self-consistency
against a manual replication of `unet_forward`'s call sequence.

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
real data — see `std/ml/ROADMAP.md`).

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

## GPU backends

```c
#include <std/ml/gpu_mps.h>   // Apple Silicon/macOS — [features] "mps"
#include <std/ml/gpu_cuda.h>  // NVIDIA — [features] "cuda"
#include <std/ml/gpu_rocm.h>  // AMD — [features] "rocm"
```

The "unified memory" piece of the ML brief: MPS's default buffer storage
mode is genuine CPU+GPU shared memory on Apple Silicon (MLX's own
selling point) — no explicit host↔device copy the way CUDA/ROCm's
discrete memory model needs.

::: warning Verification status
- **MPS**: `mps_available()` is fully verified on real Apple Silicon
  hardware. `mps_add_f32()`'s pipeline — device/queue creation, a real
  runtime-compiled Metal Shading Language kernel, pipeline state, buffer
  allocation, encoder setup — is verified through every step except the
  final `dispatchThreadgroups:threadsPerThreadgroup:` call, which
  currently segfaults (an apparent codegen gap with two consecutive
  struct-by-value Objective-C message-send arguments — see
  `gpu_mps.sc`'s header comment for the precise, bisected failure point).
- **ROCm**: verified on an AMD Instinct MI100 (gfx908, ROCm 7.1) —
  `tests/stdlib/rocm/run.sh` checks every op, all four GEMM transpose
  combinations, graphs and the host-array API against CPU results (29
  checks), and the [ML benchmarks](/benchmarks#machine-learning-small-mlp-training-and-inference)
  train to the same loss as the CPU and MPS paths.
- **CUDA**: verified on an NVIDIA H200 (sm_90, CUDA 13.0, driver 580) —
  `tests/stdlib/cuda/run.sh` checks every op, all four GEMM transpose
  combinations with and without the fused ReLU, graphs, the host-array and
  raw-pointer APIs and the fp16/bf16 kernels against CPU results (59
  checks), and the [ML benchmarks](/benchmarks#machine-learning-small-mlp-training-and-inference)
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
| Graphs | `rocm_capture_begin` / `rocm_capture_end` → `RocmGraph`, `rocm_graph_launch`, `rocm_graph_free` |
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
| Raw pointers | `cuda_upload_persistent`, `cuda_matmul_f32_persistent`, `cuda_sgd_update_f32`, and the fp16/bf16 `cuda_*_f16_*`/`cuda_*_bf16_*` (2-byte storage, fp32 arithmetic) |

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

### Backend status {#backend-status}

| Backend | Elementwise ops | Matmul (std kernel) | Matmul (vendor BLAS) | Status |
|---|---|---|---|---|
| ROCm / HIP | real (HIP C++, compiled at first use by hiprtc) | real (64×64 LDS-tiled SGEMM) | real (rocBLAS) | **measured** on MI100: 29 checks in `tests/stdlib/rocm`, and the [MLP benchmarks](/benchmarks#machine-learning-small-mlp-training-and-inference) |
| CUDA | real (CUDA C++, compiled at first use by NVRTC) | real (64×64 shared-memory-tiled SGEMM) | real (cuBLAS; cuBLASLt for GEMM + ReLU) | **measured** on H200: 59 checks in `tests/stdlib/cuda`, and the [MLP benchmarks](/benchmarks#machine-learning-small-mlp-training-and-inference) |
| Vulkan/SPIR-V | always returns 0 | always returns 0 | n/a | SPIR-V is binary IR; no glslc/glslangValidator in the test environment |
| WebGPU | real (WGSL, text IR) | real (WGSL) | n/a | unmeasured — no wgpu-native/Dawn library in the test environment |

SPIR-V kernels are binary IR, so the Vulkan backend needs a SPIR-V compiler (glslc/glslangValidator) to be complete; WebGPU takes text IR (WGSL) and is written in full, but no wgpu-native/Dawn library was available to run it.

## fp16 / bf16 {#fp16-bf16}

No native 16-bit float in SafeC's type system (`Type.h`'s `TypeKind` has only `Float32`/`Float64` — a real compiler feature, not a stdlib change). Instead, the standard workaround: fp16/bf16 carried as raw bits in `unsigned short`, with explicit correctly-rounded (round-to-nearest-even) conversion to/from `float` — and real native `half`/`bfloat` GPU compute on the MPS backend, not just halved storage.

| Check | Result |
|---|---|
| Known bit patterns (fp16: 1.0, -1.0, 0.0, -0.0, 2.0, 0.5, max normal 65504, overflow→inf, smallest subnormal, inf; bf16: 1.0, -1.0, 0.0, 2.0, π) | all exact |
| fp16 subnormal idempotence sweep (every mantissa 1–1023, `fp16→f32→fp16`) | 1023/1023 exact |
| fp16 normal-range idempotence sweep (every exponent × sampled mantissas, 330 patterns) | 330/330 exact |
| fp16 round-trip on representable values | exact |
| bf16 round-trip (lossy by construction — 7 mantissa bits) | within ~0.4% relative error |


## Device selection {#device-selection}

Every backend names its op explicitly (`tensor_matmul` vs `_blas` vs `_gpu` vs `_cuda` vs `_rocm`) — precise, but awkward for a caller that wants to pick a device once. `tensor_matmul_on(a, b, device)` / `tensor_relu_on(a, device)` dispatch over a `Device` enum instead — verified bit-identical across CPU/CPU+BLAS/MPS:

| Device | Y[0][0] | Y[0][1] |
|---|---|---|
| CPU | 0.900000 | 1.300000 |
| CPU + BLAS | 0.900000 | 1.300000 |
| MPS | 0.900000 | 1.300000 |

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

All five verified end-to-end, including `LlmClient` against a real local
mock HTTP server standing in for an LLM endpoint (the same technique
`std/rpc/server_fn.h`'s own tests use — nothing here calls out to a real
hosted LLM API).
