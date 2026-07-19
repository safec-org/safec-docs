# Machine Learning (`std/ml/`)

A CPU tensor/autograd core, attention, GPU backends, and an LLM
orchestration layer (LangChain/LangGraph/LangSmith-inspired).

```c
#include <std/ml/tensor.h>
```

::: warning Scope
This is a from-scratch, single-session implementation, not a port of an
existing framework. `tensor.h` covers 1D/2D f64 tensors and the handful
of ops needed for a small MLP or attention block — not a general
n-dimensional array library. See `std/ml/ROADMAP.md` in the repo for the
attention variants (Flash/Linear/Gated/MLA/Swin/DeltaNet), diffusion
samplers, and architecture families (RNN/GRU/LSTM/xLSTM/U-Net/CNN) that
were scoped out rather than implemented unverified.
:::

---

## Tensor

```c
#include <std/ml/tensor.h>
```

CPU tensors (1D/2D, `double`) with reverse-mode autograd — PyTorch-style
ergonomics: build a computation graph implicitly by calling ops on
`requiresGrad` tensors, then call `tensor_backward()`.

```c
struct Tensor* x = tensor_from_1d(values, 3UL, /*requiresGrad=*/1);
struct Tensor* y = tensor_sum(tensor_mul(x, x));  // y = sum(x^2)
tensor_backward(y);                                // x->grad now holds 2x
```

Ops: `tensor_add`/`tensor_sub`/`tensor_mul`/`tensor_scale`/`tensor_matmul`/
`tensor_relu`/`tensor_sum`. Verified against hand-computed gradients and a
real finite-difference check on a matmul→relu→sum chain.

## Attention

```c
#include <std/ml/attention.h>
```

`softmax_rows()` and `attention_forward()` (single-head scaled
dot-product attention) build `mha_forward()` (standard multi-head
attention — splits Q/K/V's columns into heads, runs attention per head,
concatenates results). Forward-only (inference-focused, no backward
pass). Verified against a hand-computed 2×2 example and a self-
consistency check between `mha_forward` and manual per-head slicing.

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
