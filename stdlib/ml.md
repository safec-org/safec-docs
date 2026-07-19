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
- **CUDA**/**ROCm**: written against the real Driver/Runtime API ABIs and
  type-checked, but unverified — no NVIDIA/AMD GPU in the environment
  this was built in (same status as `std/gui/gui_win32.sc`/`gui_x11.sc`).
:::

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
