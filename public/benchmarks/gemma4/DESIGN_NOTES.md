# Gemma 4 E2B — from-scratch SafeC inference: design notes

::: warning Project reorganized — read this before any build command below
Everything in this doc up through "Numerical validation against the real
checkpoint" was originally written while the implementation lived under
`SafeC/std/ml/gemma4_*.{h,sc}` (part of the actual standard library) and
was mirrored into this directory only for doc-linking. **That's no
longer true.** This directory (`safec-docs/public/benchmarks/gemma4/`)
is now the CANONICAL, only copy — nothing gemma4-specific remains under
`std/ml/` — structured as a real (if partially-working, see below)
`safeguard` project: `Package.toml` + `src/` (all `gemma4_*.{h,sc}` +
`safetensors.{h,sc}` + `main.sc`, the HTTP-server demo entry point) +
`tests/`.

Two consequences for every build command quoted further down in this
file:
1. **Paths changed.** Every `SafeC/std/ml/gemma4_*.sc` reference should
   now read `src/gemma4_*.sc` (relative to this directory), and every
   `gemma4_*.h`/`.sc` file's own internal `#include`s changed from
   `<std/ml/gemma4_X.h>` to a local `"gemma4_X.h"`.
2. **Every cross-file `.sc`-implementation include was removed**,
   keeping only `.h`-declaration includes between gemma4 files (and
   between a gemma4 file and any `std/` `.sc`) — required because
   `safeguard build` auto-compiles every `.sc` file under a project as
   its own translation unit and links them all together; the old
   "one file's own includes pull in everything it needs, pass only the
   top-level file" convention this doc describes throughout would
   double-compile symbols under that model. Concretely: every
   `gemma4_*.sc` file must now be passed to the compiler explicitly (not
   just the one entry-point file) — **use `./build.sh <entry.sc> <output-path>`** (in this directory) instead of hand-assembling a file
   list; it already has the right list. This doesn't change what safec
   compiles conceptually, just which specific files a working command
   needs to name.

**`safeguard build`/`run`/`test` do NOT currently work for this
project** — not a bug in the gemma4 code, but a real, pre-existing
problem spanning 40+ unrelated `std/` files (`http/jwt.sc`,
`gui/gui_cocoa.sc`, `ml/diffusion.sc`, `serial/json.sc`, and many more)
that also have `.sc`-implementation self-includes. `safeguard build`
auto-compiles the ENTIRE standard library into `libsafec_std.a`
regardless of what a project actually uses, so any one of those 40+
files is enough to break the link with duplicate symbols — confirmed via
a real `safeguard build --release` run against this exact project. Fixing
that stdlib-wide is out of scope for this benchmark (touches dozens of
files with no relation to Gemma 4, written/owned by others, real risk of
breaking things outside this project's ability to verify). **`safeguard check`/`lint`/`format` DO work correctly** (`check` deliberately never
links the stdlib at all — confirmed clean via a real run against this
project's `src/`), and remain useful for fast type-checking and style
during development. Real building/running/testing goes through
`./build.sh` (a thin wrapper around a direct `safec ... --emit-bin`
invocation with the exact file list needed) instead, same as every build
command already documented throughout this file used, just centralized
into one script instead of hand-typed each time. `SAFEC_CLANG` must
point at `tests/clang_mps_wrapper.sh` for any build touching
`gemma4_bf16.sc` (MPS) — `build.sh` already does this. (Older text below
that references `gemma4_math.sc`/`gemma4_model.sc` describes the
project's F32 implementation, since deleted entirely — see the "full
deletion of the F32 model" section further down; `gemma4_bf16.sc`/
`gemma4_model_bf16.sc` are the only compute path now.)

Everything below this notice — the architecture reverse-engineering, the
per-module verification, the real-checkpoint numerical validation — is
unaffected by the reorganization: same code, same math, same verified
results, just moved and no longer entangled with `.sc`-implementation
self-includes. Re-run any test via `./build.sh tests/<name>.sc /tmp/<name>_bin && /tmp/<name>_bin` to reproduce.
:::

Ground truth: `google/gemma-4-E2B` on Hugging Face. Architecture reverse-engineered
from the real `config.json` plus the actual reference implementation
(`transformers/models/gemma4/modular_gemma4.py`, inherits pieces from
`models/gemma3/modeling_gemma3.py` and `modeling_rope_utils.py`). Text-only —
vision/audio encoders (`vision_config`, `audio_config`) are out of scope; this
project only implements `Gemma4TextModel` + `lm_head`.

Sources fetched into scratchpad this session (re-fetch if continuing later):
- `google/gemma-4-E2B/raw/main/config.json` (exact hyperparameters, quoted below)
- `huggingface/transformers/.../models/gemma4/modular_gemma4.py`
- `huggingface/transformers/.../models/gemma3/modeling_gemma3.py` (RMSNorm, MLP,
  RoPE apply, eager attention — Gemma4 reuses these)
- `huggingface/transformers/.../modeling_rope_utils.py` (proportional RoPE)

## Hyperparameters (text_config, verbatim from config.json)

```
hidden_size = 1536
num_hidden_layers = 35
num_attention_heads = 8
num_key_value_heads = 1        (MQA, not standard GQA)
head_dim = 256                  (sliding_attention layers)
global_head_dim = 512           (full_attention layers — DIFFERENT head_dim!)
intermediate_size = 6144        (doubled to 12288 for kv-shared layers, see below)
hidden_size_per_layer_input = 256   (PLE dim per layer)
vocab_size = 262144
vocab_size_per_layer_input = 262144
rms_norm_eps = 1e-6
sliding_window = 512
num_kv_shared_layers = 20
max_position_embeddings = 131072
final_logit_softcapping = 30.0
hidden_activation = "gelu_pytorch_tanh"
attention_bias = false
attention_k_eq_v = false        (so this session's implementation never hits
                                  the "use_alternative_attention" branch)
tie_word_embeddings = true      (lm_head.weight == embed_tokens.weight)
enable_moe_block = false        (E2B has no MoE — skip that whole code path)
bos_token_id=2, eos_token_id=1, pad_token_id=0

layer_types: 35 entries, "full_attention" at indices 4,9,14,19,24,29,34
(every 5th, 0-indexed), "sliding_attention" everywhere else (28 of 35).

rope_parameters:
  full_attention:    rope_theta=1000000.0, rope_type="proportional",
                      partial_rotary_factor=0.25
  sliding_attention:  rope_theta=10000.0,   rope_type="default" (full rotation)
```

## Weight tying / embedding scale

- `embed_tokens`: standard embedding, scaled by `sqrt(hidden_size)` = sqrt(1536)
  after lookup (`Gemma3TextScaledWordEmbedding`: `super().forward(ids) * embed_scale`).
- `lm_head` shares `embed_tokens.weight` (tied) — no separate lm_head tensor in
  the checkpoint; matmul with the SAME weight matrix, transposed.
- `embed_tokens_per_layer`: SEPARATE embedding table,
  shape `[vocab_size_per_layer_input=262144, num_hidden_layers(35) * hidden_size_per_layer_input(256) = 8960]`,
  scaled by `sqrt(hidden_size_per_layer_input)` = sqrt(256) = 16.0 after lookup,
  then reshaped to `[batch, seq, 35, 256]`.

## RMSNorm (Gemma4RMSNorm(Gemma3nRMSNorm) — NOT Gemma3RMSNorm, formula differs!)

CONFIRMED by fetching `modeling_gemma3n.py`'s `Gemma3nRMSNorm` directly — do
NOT use Gemma3's "(1+weight)" convention here, it's the wrong formula for this
model family despite the similar name:

```python
def _norm(hidden_states):
    mean_squared = hidden_states.pow(2).mean(-1, keepdim=True) + eps
    return hidden_states * torch.pow(mean_squared, -0.5)   # note: pow(-0.5), not rsqrt —
                                                              # same math, called out in the
                                                              # source as deliberate (JAX/Torch
                                                              # compiler-difference workaround);
                                                              # doesn't change the SafeC port.

def forward(hidden_states):
    normed = _norm(hidden_states.float())
    if with_scale:                        # true everywhere except v_norm
        normed = normed * weight.float()   # weight used DIRECTLY, no "+1" — weight
                                             # is nn.Parameter(torch.ones(dim)) at init,
                                             # i.e. checkpoint stores the real scale itself
    return normed.type_as(hidden_states)
```
`eps=1e-6` everywhere. `with_scale=False` specifically for `v_norm` (no weight
parameter exists at all for it in the checkpoint — forward just returns
`_norm(x)` with no multiply). Every other RMSNorm in the model (`input_layernorm`,
`post_attention_layernorm`, `pre_feedforward_layernorm`, `post_feedforward_layernorm`,
`q_norm`, `k_norm`, `post_per_layer_input_norm`, `per_layer_projection_norm`,
final model-output norm) uses `with_scale=True` (the default).

## Per-Layer Embeddings (PLE) — computed ONCE per forward pass, before the layer loop

```
# 1. token-identity component (embed_tokens_per_layer lookup)
per_layer_inputs = embed_tokens_per_layer(input_ids)          # [B,S,8960], scaled by sqrt(256)
per_layer_inputs = reshape to [B, S, 35, 256]

# 2. context-aware component (projected from the MAIN embedding)
per_layer_projection = per_layer_model_projection(inputs_embeds) * (1/sqrt(1536))  # Linear 1536->8960, no bias
per_layer_projection = reshape to [B, S, 35, 256]
per_layer_projection = RMSNorm(per_layer_projection)          # per_layer_projection_norm, eps=1e-6

# 3. combine
per_layer_inputs_final = (per_layer_projection + per_layer_inputs) * (1/sqrt(2))
```
Then for decoder layer `i`, slice `per_layer_input = per_layer_inputs_final[:, :, i, :]`
(shape `[B,S,256]`) and pass into that layer's forward.

## Decoder layer forward (sandwich-norm, per Gemma4TextDecoderLayer)

```
residual = h
h = input_layernorm(h)
h = self_attn(h, ...)
h = post_attention_layernorm(h)
h = residual + h

residual = h
h = pre_feedforward_layernorm(h)
h = mlp(h)                          # enable_moe_block=false for E2B -- MoE branch never runs
h = post_feedforward_layernorm(h)
h = residual + h

# PLE injection (hidden_size_per_layer_input=256, always true for this model)
residual = h
h = per_layer_input_gate(h)          # Linear 1536->256, no bias
h = gelu_tanh(h)
h = h * per_layer_input               # elementwise multiply by this layer's PLE slice
h = per_layer_projection(h)            # Linear 256->1536, no bias
h = post_per_layer_input_norm(h)        # RMSNorm
h = residual + h

h = h * layer_scalar                     # learned scalar per layer (loaded from checkpoint,
                                           # registered as torch.ones(1) init but real value
                                           # comes from the actual weights file)
return h
```

## Attention (Gemma4TextAttention forward)

Per-layer setup (at load time, not per forward call):
```
is_sliding = layer_type == "sliding_attention"
sliding_window = 512 if is_sliding else None
head_dim = global_head_dim(512) if (not is_sliding) else head_dim(256)
num_key_value_heads = 1  (attention_k_eq_v=false, so always config.num_key_value_heads)
num_key_value_groups = num_attention_heads(8) / num_key_value_heads(1) = 8
scaling = 1.0    # NOT head_dim**-0.5 -- Gemma4 overrides this to a flat 1.0,
                  # unlike Gemma3Attention which uses query_pre_attn_scalar**-0.5.
                  # This is load-bearing; getting it wrong silently produces
                  # garbage-but-plausible-looking output.

first_kv_shared_layer_idx = num_hidden_layers(35) - num_kv_shared_layers(20) = 15
is_kv_shared_layer = layer_idx >= 15   (true for layers 15..34, the LAST 20)
# store_full_length_kv: true only for the LAST layer of each type among layers 0..14
#   -> layer 14 (full_attention, last full among 0..14) stores shared_kv_states["full_attention"]
#   -> layer 13 (sliding_attention, last sliding among 0..14) stores shared_kv_states["sliding_attention"]
# Layers 15..34 have NO k_proj/v_proj/k_norm/v_norm weights at all in the checkpoint --
# they look up shared_kv_states[their_own_layer_type] instead of computing anything.
# This means only 2 K/V computations exist beyond layer 14 in the whole 35-layer stack.
```

Forward pass per layer:
```
q = q_proj(h) -> reshape [B,S,8,head_dim] -> q_norm (RMSNorm, WITH scale) -> RoPE(layer's own type) -> transpose to [B,8,S,head_dim]

if is_kv_shared_layer:
    k, v = shared_kv_states[self.layer_type]     # reuse, no compute
else:
    k = k_proj(h) -> reshape [B,S,1,head_dim] -> k_norm (RMSNorm, WITH scale) -> RoPE -> transpose [B,1,S,head_dim]
    v = v_proj(h) -> reshape [B,S,1,head_dim] -> v_norm (RMSNorm, NO scale, with_scale=False) -> transpose [B,1,S,head_dim]
    if this is a KV cache step: append to running cache
    if store_full_length_kv: shared_kv_states[self.layer_type] = (k, v)   # save for later shared layers

k_expanded = repeat_kv(k, 8)   # broadcast 1 KV head -> 8, no extra compute, just repeat
v_expanded = repeat_kv(v, 8)
attn_weights = (q @ k_expanded^T) * scaling(1.0)
attn_weights = attn_weights + causal_or_sliding_mask   # -inf where disallowed;
                                                          # full_attention layers use plain
                                                          # causal mask, sliding_attention
                                                          # layers use causal AND-ed with
                                                          # the 512-token sliding window
attn_weights = softmax(attn_weights, fp32) -> cast back
attn_output = attn_weights @ v_expanded
attn_output = transpose+reshape -> o_proj(attn_output)   # Linear [8*head_dim -> 1536], no bias
```
No attention-logit softcap in the TEXT attention path (only `final_logit_softcapping`
at the LM head, and separately the AUDIO encoder has its own unrelated softcap —
do not conflate the two).

## RoPE — two completely different configs per layer type

Standard `apply_rotary_pos_emb`:
```
rotate_half(x): x1,x2 = split(x, 2, last_dim); return cat(-x2, x1)
q_embed = q*cos + rotate_half(q)*sin
k_embed = k*cos + rotate_half(k)*sin
```
`cos`/`sin` computed once per layer_type per forward call (not per layer):
```
freqs = position_ids[:, None] * inv_freq[None, :]      # outer product
emb = cat(freqs, freqs, dim=-1)
cos = cos(emb) * attention_scaling   (attention_scaling = 1.0 for both our types)
sin = sin(emb) * attention_scaling
```

`sliding_attention` inv_freq (standard/default, full head_dim=256 rotation):
```
inv_freq[i] = 1 / (10000 ^ (2i / 256))    for i in 0..127   (128 values, head_dim/2)
```

`full_attention` inv_freq (proportional/p-RoPE, head_dim=global_head_dim=512,
partial_rotary_factor=0.25):
```
rope_angles = int(0.25 * 512 / 2) = 64
inv_freq_rotated[j] = 1 / (1000000 ^ (2j / 512))   for j in 0..63   (using base=1e6! NOT 10000)
nope_angles = 512/2 - 64 = 192
inv_freq = concat(inv_freq_rotated (64 values), zeros(192 values))   # 256 total = head_dim/2
```
So for full_attention layers, only the first 64 of 256 frequency pairs actually
rotate; the remaining 192 have inv_freq=0 (cos=1, sin=0 always -- i.e. those
dimensions of Q/K pass through RoPE completely unrotated, "NoPE" positions).
This is easy to get subtly wrong — implement the zero-padding explicitly, don't
assume a uniform 512-wide rotation.

## Gated FFN (Gemma3MLP, reused as Gemma4TextMLP with one override)

```
down_proj(gelu_tanh(gate_proj(x)) * up_proj(x))
```
`gate_proj`/`up_proj`: `[1536 -> intermediate_size]`, `down_proj`:
`[intermediate_size -> 1536]`, no bias on any of the three.
`intermediate_size` is DOUBLED (6144 -> 12288) specifically for layers where
`is_kv_shared_layer AND use_double_wide_mlp(config: true)` — i.e. layers 15..34
get the wider FFN, layers 0..14 keep the normal 6144 width. This widening is
presumably compensating for those layers not spending any parameters on their
own K/V projections.

`gelu_pytorch_tanh` = the tanh-approximation of GELU (NOT plain/erf-based GELU):
```
gelu_tanh(x) = 0.5 * x * (1 + tanh(sqrt(2/pi) * (x + 0.044715 * x^3)))
```

## LM head

```
logits = lm_head(hidden_states)     # tied weight, same matrix as embed_tokens (transposed)
logits = logits / 30.0
logits = tanh(logits)
logits = logits * 30.0
```

## Attention masks

Two separate masks built once per forward call:
- `full_attention`: standard causal mask (token i attends to 0..i).
- `sliding_attention`: causal AND windowed — token i attends to
  `max(0, i-511)..i` (512-token window per `sliding_window` config).

## RESOLVED (were TODO, now confirmed against the real checkpoint header)

1. `v_norm`'s `with_scale=False` — CONFIRMED via `modeling_gemma3n.py`'s
   `Gemma3nRMSNorm` directly (see the RMSNorm section above, already updated).

2. **Checkpoint tensor names/shapes — fetched via HTTP Range request** (first
   8 bytes for header length, then that many more bytes for the header JSON;
   no need to download the 10.25GB body just to read this). Full manifest
   saved at `gemma4/tensor_manifest.txt` in this directory (regenerate by
   re-running the same Range-request trick if it goes stale). Key findings:

   - Multimodal checkpoint, 2012 tensors total under 5 top-level prefixes:
     `model.{audio_tower,embed_audio,embed_vision,language_model,vision_tower}`.
     Only `model.language_model.*` (600 tensors) matters for this project.
   - Non-layer tensors:
     ```
     model.language_model.embed_tokens.weight              [262144, 1536]
     model.language_model.embed_tokens_per_layer.weight     [262144, 8960]
     model.language_model.norm.weight                       [1536]        <- FINAL norm, applied
                                                                              after all 35 layers,
                                                                              before lm_head. Missed
                                                                              this in the first pass
                                                                              over modular_gemma4.py;
                                                                              standard for any decoder
                                                                              stack, should have assumed
                                                                              it existed and confirmed
                                                                              from the tensor list.
     model.language_model.per_layer_model_projection.weight [8960, 1536]
     model.language_model.per_layer_projection_norm.weight   [256]
     ```
     No separate `lm_head.weight` — confirms `tie_word_embeddings`.
   - Per-layer, 17 tensors each, `model.language_model.layers.{i}.*`:
     `input_layernorm.weight [1536]`, `layer_scalar [1]`,
     `mlp.{gate,up}_proj.weight [intermediate,1536]`, `mlp.down_proj.weight [1536,intermediate]`,
     `per_layer_input_gate.weight [256,1536]`, `per_layer_projection.weight [1536,256]`,
     `post_attention_layernorm.weight [1536]`, `post_feedforward_layernorm.weight [1536]`,
     `post_per_layer_input_norm.weight [1536]`, `pre_feedforward_layernorm.weight [1536]`,
     `self_attn.{q,k}_norm.weight [head_dim]`, `self_attn.{q,k,v}_proj.weight [...]`,
     `self_attn.o_proj.weight [1536, num_heads*head_dim]`.
     No `v_norm` tensor anywhere (matches `with_scale=False`, universal, not
     layer-dependent).
   - **Confirmed by direct inspection, layers 0/4/13/14/15/19/34**:
     - `head_dim` really does flip per layer_type: sliding layers'
       `q_norm`/`k_norm` are `[256]`, `q_proj`/`k_proj`/`v_proj` sized for
       head_dim=256 (e.g. `q_proj [2048,1536]` = 8*256); full-attention
       layers (idx 4, 14) are sized for head_dim=512 (`q_proj [4096,1536]`
       = 8*512). Matches design notes exactly.
     - `mlp.*_proj` intermediate dim really doubles from 6144 to 12288
       starting exactly at layer 15 (13/14 still 6144; 15/19/34 all 12288).
       Matches `is_kv_shared_layer AND use_double_wide_mlp` exactly.
     - **Correction to the "no weight matrices" reading**: layers 15-34
       (the kv-shared range) STILL HAVE `k_proj`/`k_norm`/`v_proj` present
       in the checkpoint file — the modular_gemma4.py comment
       ("Layers sharing kv states don't need any weight matrices") was
       about `_keys_to_ignore_on_load_unexpected` (a load-time allow-list
       so `transformers` doesn't raise on "unexpected" keys when
       populating an `nn.Module` that has no such parameter) — it does
       NOT mean the checkpoint omits them. They're present but vestigial:
       **the SafeC loader should skip loading these specific tensors for
       layers >= 15 (save real memory), and the forward pass must never
       touch them regardless** — always route through `shared_kv_states`
       for any `is_kv_shared_layer`, never compute k/v locally there, even
       though the weights are sitting right there in the file.

## Tokenizer (fetched and inspected `google/gemma-4-E2B`'s real tokenizer.json, 32MB)

**Real surprise: this is BPE, not SentencePiece-Unigram** — despite
`tokenizer_class: "GemmaTokenizer"` in `tokenizer_config.json` and every
earlier Gemma generation (1/2/3/3n) using Unigram. Do not assume Unigram
carries forward; this was confirmed by inspecting `model.type` in the actual
file, not by name/precedent. BPE is simpler to implement (no Viterbi
segmentation), which is one piece of good news in an otherwise very custom
architecture.

```
normalizer:    Replace(" " -> "▁")            # U+2581, SentencePiece-style
                                                 # space marker, kept even
                                                 # though the algorithm below
                                                 # it is BPE not Unigram
pre_tokenizer: Split(" ", MergedWithPrevious)   # effectively a no-op given
                                                 # the normalizer already
                                                 # replaced every literal
                                                 # space with "▁" -- verify
                                                 # this empirically once
                                                 # tokenizer code exists,
                                                 # don't just trust this
                                                 # reading of the two steps'
                                                 # interaction
decoder:       Sequence[Replace("▁"->" "), ByteFallback, Fuse]

model.type:                    BPE
model.unk_token:                "<unk>"
model.continuing_subword_prefix: null      (no WordPiece-style "##" prefix)
model.end_of_word_suffix:        null
model.fuse_unk:                  true
model.byte_fallback:             true      (every possible byte sequence is
                                              encodable -- unseen bytes fall
                                              back to <0xXX>-style tokens,
                                              never a hard failure)
model.ignore_merges:             false

vocab:  262144 entries (string -> id)
merges: 514906 ordered pairs (string,string) -- EARLIER in the list =
        HIGHER merge priority, standard BPE greedy-merge convention
```

Special/added tokens (24 total) — first 5 match `config.json`'s
`pad_token_id=0, eos_token_id=1, bos_token_id=2` exactly:
```
id=0  <pad>     id=1  <eos>      id=2  <bos>       id=3  <unk>   id=4  <mask>
id=46 <|tool>   id=47 <tool|>    id=48 <|tool_call> id=49 <tool_call|>
id=50 <|tool_response>  ... (tool-calling / agentic special tokens, structured)
```

Real BPE merge lists this large (514,906 pairs) need an O(1) `(left,right) token pair -> rank` lookup during the greedy-merge encode loop, and the
262,144-entry vocab needs O(1) `string -> id` / `id -> string` lookups both
ways — a linear Vec scan (like the safetensors parser's `find()`, fine for
~600 tensor names) will NOT scale to this. Check for `std/collections/map.h`
(hash map) before implementing — if it doesn't exist or isn't a good fit,
that's its own small yak-shave before the tokenizer itself can be fast.

Raw 32MB `tokenizer.json` is a lot to re-parse with a general JSON tree
builder (`std/serial/json.sc`) at every model-load — try it first (simplest
path), but if `json_parse`'s in-memory `Value` tree construction proves too
slow/memory-heavy for 262K+515K entries, the fallback is a one-time
preprocessing pass (Python, run once) that flattens `tokenizer.json` into
plain vocab.tsv/merges.tsv files SafeC reads directly without a JSON parse.

## Tokenizer implementation status: DONE, verified

`std/ml/gemma4_tokenizer.h`/`.sc` implements the BPE encode/decode described
above, loading from `vocab.bin`/`merges.bin`. Verified round-trip AND exact
token-id match against the real `tokenizer.json` loaded via the Python
`tokenizers` library (0.23.1, installed in a throwaway venv at
`/tmp/tokvenv` for this check only) for three prompts, e.g. "Hello, world!"
-> `[9259, 236764, 1902, 236888]` matches exactly (excluding the leading
`<bos>`, which the reference auto-adds and our tokenizer intentionally
doesn't — bos/eos handling is the generation loop's job, not the
tokenizer's).

### SafeC gotcha hit while building this: str_map vs generic HashMap methods

`str_map_new(val_size)` returns a `struct HashMap` with `key_size` hardcoded
to `8UL` — but that `8` is a vestigial field, NOT a real fixed key width.
`str_map_insert`/`str_map_get` (free functions taking `&HashMap`) ignore
`key_size` entirely and do a proper variable-length `str_cmp` against a
`malloc`'d, `strlen`-sized copy of the key. The GENERIC `HashMap::insert()`/
`.get()` *methods*, by contrast, take `key_size` literally: they hash and
`memcpy`/`memcmp` exactly `self.key_size` (8) bytes starting at whatever
pointer you pass as `key` — silently treating a C-string pointer as "a
pointer to an 8-byte fixed key" and truncating to its first 8 bytes.

Calling `.insert()`/`.get()` (the generic methods) directly on a str_map
instead of `str_map_insert`/`str_map_get` (the free functions) compiles and
runs with no error, but silently collides every pair of vocab strings that
share an 8-byte prefix — e.g. `"▁world"` (exactly 8 UTF-8 bytes) and
`"▁worldRank"` (11 bytes, same first 8) hashed/compared as identical keys,
so the encoder started resolving "▁world" to whichever of the two got
inserted later ("▁worldRank"'s id). Caught by tracing the actual BPE merge
rounds and diffing against the real tokenizer's output — the merge
sequence itself was correct (real merge rule `["▁world","Rank"]` does exist
in the real merges list at rank 430387), it was the vocab *lookup* after
concatenation that returned the wrong id. Fix: always call `str_map_insert`/
`str_map_get` (never the bare `.insert()`/`.get()` methods) on anything
built via `str_map_new`. This will matter again for any future str-keyed
map in this codebase (e.g. a tensor-name-to-Tensor* lookup, if one gets
added instead of linear `.find()`).

## Math primitives implementation status: DONE, verified

`std/ml/gemma4_math.h`/`.sc` implements, on top of `std/ml/tensor.h`'s
existing `struct Tensor` (autograd-carrying, but used with
`tensor_set_grad_enabled(0)` — pure inference, no backward pass ever
runs):
- `tensor_rmsnorm_rows` / `tensor_rmsnorm_rows_noscale` (Gemma3n formula —
  see RMSNorm section above; noscale variant is for `v_norm` specifically)
- `tensor_logit_softcap` (`tanh(x/cap)*cap`, for the LM head only)
- `tensor_linear` (`y = x @ W^T` against a PyTorch-layout `[out,in]`
  weight, without ever materializing `W^T` — reads both operands
  row-major/contiguous; this is the workhorse every q/k/v/o/gate/up/down/
  per_layer_*/lm_head projection will call)
- `gemma4_embed_lookup_row` / `_rows` (gather + scale, for `embed_tokens`
  and `embed_tokens_per_layer`)

`tensor_gelu_fwd` (already existing in `std/ml/activations.h`/`.sc`, NOT
reimplemented here) is exactly `gelu_pytorch_tanh` — verified by reading
its formula (`0.5*x*(1+tanh(sqrt(2/pi)*(x+0.044715*x^3)))`), reuse
directly wherever the config's `hidden_activation` applies (gated FFN,
PLE's `act_fn`).

All five new ops verified against hand-computed expected values in
`tests/test_gemma4_math.sc` (this directory — copied out of the session
scratchpad so it survives past one session; scratchpad contents don't
persist) — exact match. Build command that worked (needed `tensor.sc` for
core Tensor ops, `vec.sc` + `mem.sc` transitively for Tensor's Vec-based
autograd bookkeeping and alloc/dealloc, `gemma4_math.sc` pulls in
`math.sc` itself via `#include <std/math.sc>` rather than needing it
passed separately — same "each `.sc` implementation file passed exactly
once, let its own includes do the rest" rule as established for the
tokenizer):
```
safec safec-docs/public/benchmarks/gemma4/tests/test_gemma4_math.sc \
  SafeC/std/ml/gemma4_math.sc SafeC/std/ml/tensor.sc \
  SafeC/std/collections/vec.sc SafeC/std/mem.sc \
  -I SafeC --emit-bin -o /tmp/test_gemma4_math_bin
```

`tests/test_tokenizer.sc` (same directory) is the tokenizer round-trip
test from the previous step, similarly copied out of scratchpad. Build
command:
```
safec safec-docs/public/benchmarks/gemma4/tests/test_tokenizer.sc \
  SafeC/std/ml/gemma4_tokenizer.sc SafeC/std/io_file.sc \
  SafeC/std/fmt.sc SafeC/std/convert.sc \
  -I SafeC --emit-bin -o /tmp/test_tokenizer_bin
```
(paths above are relative to the parent of both `SafeC/` and
`safec-docs/` — adjust to wherever those two repos actually sit.)

## Newly confirmed: per-layer `layer_scalar` (already captured correctly
above in "Decoder layer forward" before this note was added — re-confirmed
via `modular_gemma4.py` line ~1274, `hidden_states *= self.layer_scalar`,
applied once at the very end of the whole decoder layer, after the PLE
injection block; `torch.ones(1)` is only the init value, the real
checkpoint has a trained-if-different-from-1.0 value for every layer,
loaded from `model.language_model.layers.{i}.layer_scalar`, shape `[1]`,
confirmed present in the real tensor manifest for every layer).

## RoPE implementation status: DONE, verified

`std/ml/gemma4_rope.h`/`.sc` implements `gemma4_rope_build` (produces a
`struct Gemma4RopeTable` of `[count, headDim]` cos/sin tables for either
config, selected via an `isFullAttention` flag) and `gemma4_rope_apply`
(rotate_half + cos/sin combine on a single head's `[seqLen, headDim]`
slice). Verified in `tests/test_gemma4_rope.sc`:
- position=0 gives cos=1/sin=0 everywhere (both configs) and `apply()` is
  the identity at position 0.
- sliding config at position=1 matches `cos(1)`/`sin(1)` exactly, and the
  "doubled" half of the row (`cat(freqs,freqs)`) matches the first half.
- full-attention config at position=5: index 0 (inside the 64 rotating
  pairs) matches `cos(5)`/`sin(5)` (invFreq[0]=1 since `1/1e6^0=1`); index
  200 (outside, in the NoPE zone) is exactly `cos=1,sin=0` regardless of
  position, confirming the zero-padding is wired correctly, not just
  "close to 1".
- `apply()` at position=1, hand-verified against the rotate_half formula
  directly (`out[0] = x[0]*cos(1) - x[128]*sin(1)`) — matches to float32
  precision.

`Gemma4RopeTable` is a plain value struct (like `Gemma4Tokenizer`) — pass
`&localVar` at call sites where the caller's variable is a plain
(non-reference) value, same rule as everywhere else in this codebase.

## Attention implementation status: DONE, verified (core math; cross-layer KV sharing wiring still owed to the model-wiring step)

`std/ml/gemma4_attention.h`/`.sc` implements:
- `gemma4_attn_project_q`/`_k`/`_v` — projection + per-head RMSNorm (q/k
  WITH scale, v WITHOUT) + RoPE (q/k only, not v). Per-head slicing is
  done via real Tensor copies (`__gemma4_slice_head_cols`/
  `__gemma4_write_head_cols`) since `Tensor` has no view/stride mechanism
  — acceptable cost, this isn't the hot loop.
- `gemma4_attn_forward` — the actual scaled-dot-product attention:
  causal + optional sliding-window masking, numerically-stable softmax,
  MQA `repeat_kv` broadcast (implemented as index math — `kvHeadIdx = headIdx / (numHeads/numKvHeads)` — not an actual K/V tensor
  duplication), `o_proj`. This is the one place in the module that
  deliberately bypasses Tensor-copy helpers and indexes raw buffers
  directly (`__gemma4_attn_head`, malloc's one `float[kvSeqLen]` scores
  row, reused across query rows) — it's the O(seqLen·kvSeqLen·headDim)
  hot path.
- Position handling: queries are always the newest `seqLen` tokens at
  absolute positions `[cacheLen, cacheLen+seqLen)`; keys/values span
  `[0, kvSeqLen)` contiguously. True for both prefill (`cacheLen=0`) and
  single-step decode under a plain growing KV cache — see header comment
  for why this is a deliberate simplifying assumption, not an oversight.

Verified in `tests/test_gemma4_attention.sc` against hand-computed values
for a small (headDim=2, numHeads=2, numKvHeads=1) synthetic case: plain
causal masking + MQA + softmax + weighted-sum, with `oProjW` set to the
identity matrix so the pre-`o_proj` per-head output is directly
checkable — matches to float32 precision. A second case verifies the
sliding-window mask specifically excludes a key exactly
`slidingWindow` tokens back (`qPos - kPos == slidingWindow` is
EXCLUDED, matching modular_gemma4.py's `<` not `<=`).

**NOT yet wired here**: cross-layer KV sharing (layers 15..34 reusing
layers 13/14's K/V instead of calling `gemma4_attn_project_k`/`_v` at
all) is deliberately left to the full-model wiring step (task 11) — this
module only takes whatever K/V tensors it's given each call, computed
fresh or reused, and has no opinion on which. The 35-layer driver will
need to: call `project_k`/`project_v` normally for layers 0..14, capture
layer 13's output as `sharedKv["sliding_attention"]` and layer 14's as
`sharedKv["full_attention"]`, and for layers 15..34 skip the k/v
projection calls entirely and pass the appropriate cached tensors
straight into `gemma4_attn_forward`.

### SafeC build-order gotcha hit here: `math.sc` must be a leaf, included by declaration only

Both `gemma4_math.sc` and `gemma4_rope.sc` originally did
`#include <std/math.sc>` (the full implementation) for `sqrt_f`/
`tanh_f`/`cos_f`/`sin_f`/`pow_f`. That's fine as long as only ONE of them
is ever linked into a given binary — but `gemma4_attention.sc` needs
BOTH (transitively, via `gemma4_math.sc` for `tensor_linear`/
`tensor_rmsnorm_rows`, and directly via `gemma4_rope.sc` for
`gemma4_rope_apply`), and passing both `gemma4_math.sc` and
`gemma4_rope.sc` as separate top-level compiled files each pulling in
their own full copy of `math.sc` produced ~80 duplicate-symbol linker
errors (`_std_sqrt_f`, `_std_exp_f`, `_std_log2_f`, etc.). Fixed by
matching `activations.sc`'s existing convention (should have checked
this before writing either file): include `<std/math.h>` (declarations
only) from every `.sc` file that merely CALLS these functions, and
require whatever top-level build command links them together to also
pass `std/math.sc` explicitly, exactly once, as its own compiled file —
same as `vec.sc`/`mem.sc` already need to be passed explicitly for
`Tensor`'s own Vec-based bookkeeping. Every build command in this
directory's `tests/` now passes `std/math.sc` explicitly; do the same
for the eventual full-model driver.

## PLE implementation status: DONE, verified

`std/ml/gemma4_ple.h`/`.sc` implements `gemma4_ple_combine` (context
projection via `tensor_linear` + `1/sqrt(hidden_size)` scale + per-layer
RMSNorm via `per_layer_projection_norm`, combined with the token-identity
component — which turned out to need no new code at all, since it's
exactly `gemma4_embed_lookup_rows` against `embed_tokens_per_layer` with
`scale=sqrt(pleDim)` — via `(context + token_identity) * (1/sqrt(2))`)
and `gemma4_ple_slice_layer` (pulls one decoder layer's `[seqLen, pleDim]` slice out of the combined `[seqLen, numLayers*pleDim]` result).
Added two small generic ops to `gemma4_math.h`/`.sc` to support this —
`tensor_slice_cols`/`tensor_write_cols` — generic column-range extract/
write, the same operation `gemma4_attention.sc`'s per-head helpers do,
just exported for reuse rather than kept file-local (attention's own
copies were left untouched since they were already verified working).

Verified in `tests/test_gemma4_ple.sc` against fully hand-computed
values (hiddenSize=2, numLayers=2, pleDim=2, seqLen=2 toy case) — matches
to float32 precision on every element, including the per-layer slice
extraction afterward.

## Gated FFN implementation status: DONE, verified

`std/ml/gemma4_ffn.h`/`.sc` implements `gemma4_ffn_forward` =
`down_proj(gelu_tanh(gate_proj(x)) * up_proj(x))`, directly composing
already-verified primitives (`tensor_linear`, `tensor_gelu_fwd`,
`tensor_mul`) — no new numeric code, just wiring. The double-wide-MLP
mechanism for cross-layer-KV-shared layers needs zero special-casing
here: `intermediate_size` is implicit in whichever `gateProjW`/
`upProjW`/`downProjW` shapes the caller loads for a given layer (6144 or
12288), exactly like every other per-layer weight-shape difference in
this model. Verified in `tests/test_gemma4_ffn.sc` (identity weights,
hand-computed `gelu_tanh(±1)` reference values) — exact match.

## Full model wiring status: DONE, wiring-verified (real-checkpoint numerical validation still pending — that's task 12)

`std/ml/gemma4_model.h`/`.sc` wires together every module built so far
into the actual 35-layer Gemma4 E2B forward pass:

- `struct Gemma4Model` / `struct Gemma4LayerWeights` hold every loaded
  weight tensor. `kProjW`/`kNormW`/`vProjW` are deliberately plain
  (nullable) `struct Tensor*`, not `&Tensor`, since they're the only
  fields that can be genuinely absent (kv-shared layers 15..34) — every
  other field is `&Tensor` (never-null, matching this codebase's
  convention everywhere else).
- `gemma4_model_load(path)` opens a safetensors file and loads all ~540
  tensors by name (`model.language_model.*`, built via
  `__g4m_layer_name`'s `string_from + push_int + push` pattern). Loads
  everything scale-dependent (`hiddenSize`, `vocabSize`, `pleDim`, and
  crucially each layer's `headDim`) from the ACTUAL loaded tensor shapes
  rather than hardcoding the real E2B's 1536/262144/256/{256,512} —
  `headDim = qProjW.shape[0] / numHeads` per layer, `hiddenSize`/
  `vocabSize` from `embedTokensW`'s shape, `pleDim` from
  `perLayerProjectionNormW`'s shape. Only the genuinely
  checkpoint-invariant architectural constants (`numLayers=35`,
  `numHeads=8`, `numKvHeads=1`, the KV-sharing schedule, `eps`,
  `finalLogitSoftcap`) stay as literals. This is what let the wiring be
  tested against a tiny synthetic checkpoint instead of the real 10GB
  file (see below) — and would let the same code serve E4B or any other
  Gemma4-shaped checkpoint without changes, though that's untested.
  Vestigial `k_proj`/`k_norm`/`v_proj` weights for kv-shared layers are
  never loaded (memory savings — real checkpoint has them present but
  unused, see the RESOLVED section above).
- `gemma4_model_forward(model, tokenIds, count, cacheLen)` runs the full
  pipeline: scaled embedding lookup, PLE combine, per-layer-type RoPE
  table construction (built once per call, reused across every layer of
  that type — headDim read from layer 0 (guaranteed sliding) and layer 4
  (guaranteed full_attention) rather than hardcoded), the 35-layer
  sandwich-norm decoder loop (input_layernorm → attention → post_attn
  norm → residual → pre_ff norm → gated FFN → post_ff norm → residual →
  PLE injection sub-block → residual → `layer_scalar` multiply), capturing
  layer 13's K/V as the shared `sliding_attention` state and layer 14's as
  the shared `full_attention` state, reusing those (not recomputing, not
  loading their weights at all) for every layer ≥ 15 of the matching
  type. Finishes with the final `norm`, the tied `lm_head` (literally
  `tensor_linear(finalNormed, embedTokensW)` — no separate weight, no
  transpose needed since `embedTokensW`'s `[vocab, hidden]` layout is
  already the `[out,in]` shape `tensor_linear` wants), and the softcap.
- No persistent KV cache across separate `gemma4_model_forward` calls
  yet (deliberate — see header comment and the "Still TODO" list below);
  every call recomputes everything from scratch including any "prior"
  tokens the caller passes in via `cacheLen`/`tokenIds`.

**Verified (wiring only, NOT numerical fidelity against the real model —
that needs the real checkpoint, task 12)**: `tests/test_gemma4_model.sc`
+ `tests/make_synthetic_gemma4.py` (regenerate the synthetic checkpoint
with `python3 make_synthetic_gemma4.py`, writes
`/tmp/gemma4_synthetic.safetensors`) build a tiny (hiddenSize=8,
vocabSize=50, pleDim=4, numLayers=35 — kept real since the KV-sharing
schedule is architectural) all-F32 synthetic checkpoint with the real
tensor naming scheme and confirm: dimensions are correctly derived from
the checkpoint (not the old hardcoded literals), `headDim` is correctly
4 for a sliding layer / 6 for a full layer, layer 15's `kProjW` is
correctly null (vestigial skip) while layer 13's is correctly loaded
(needed to seed the shared state), the full forward pass runs to
completion without crashing, the output shape is exactly `[count, vocabSize]`, every logit is finite (no NaN), and — a real mathematical
invariant, not just "didn't crash" — every logit falls within
`[-30, 30]`, confirming `tensor_logit_softcap` is genuinely wired into
the live pipeline's output path.

Build command (needs `json.sc` for safetensors' header parsing, which
transitively pulls in `string.sc`/`mem.sc`/`str.sc`/`vec.sc`/`fmt.sc`/
`convert.sc` — do NOT also pass those explicitly, duplicate-symbol
gotcha again):
```
safec safec-docs/public/benchmarks/gemma4/tests/test_gemma4_model.sc \
  SafeC/std/ml/gemma4_model.sc SafeC/std/ml/tensor.sc SafeC/std/ml/float16.sc \
  SafeC/std/serial/json.sc SafeC/std/io_file.sc SafeC/std/math.sc \
  -I SafeC --emit-bin -o /tmp/test_gemma4_model_bin
```

## Numerical validation against the real checkpoint: DONE — PASSED

Downloaded the real `google/gemma-4-E2B/model.safetensors` (10,246,621,918
bytes, verified exact byte-for-byte size match) to
`/Users/minjaekim/SafeC/models/gemma4-e2b/model.safetensors`, along with
`config.json`/`tokenizer_config.json`/`generation_config.json` into the
same directory (`tokenizer.json` reused from the earlier fetch). Set up
a throwaway Python venv (`/tmp/tokvenv`, NOT part of this repo) with
`torch` (CPU) + `transformers` 5.14.1, which turned out to already
support Gemma4 natively (`Gemma4ForConditionalGeneration`,
`Gemma4TextModel`, etc. all importable — no need for a dev/nightly
install). Ran the REAL model locally (no further network access —
`transformers.AutoTokenizer`/`Gemma4ForConditionalGeneration.from_pretrained` pointed straight at the local directory) on the same
three prompts the tokenizer was validated against earlier, via
`tests/run_reference.py`, capturing last-position logits and top-5
predicted tokens into `tests/gemma4_reference_logits.json`.

Then ran `tests/test_gemma4_model_real.sc` — `gemma4_model_load` against
the SAME real 10.25GB file, `gemma4_model_forward` on the SAME token ids
(hardcoded directly from the already-tokenizer-verified ids, sidestepping
a `gemma4_tokenizer.sc` + `gemma4_model.sc` link-time duplicate-symbol
conflict neither module's build was designed around simultaneously — not
a correctness issue, just a build-graph one, noted below). Compared
last-position top-5 token ids/logit values directly against the Python
reference:

| prompt | reference top5 ids | SafeC top5 ids | match |
|---|---|---|---|
| "Hello, world!" | [236888, 108, 1, 107, 1717] | [236888, 108, 1, 107, 26352] | top-4 exact, 5th differs (low-confidence tail, not meaningful) |
| "The quick brown fox..." | [107, 1, 31770, 108, 669] | [107, 1, 31770, 108, 669] | **exact, same order** |
| "SafeC" | [39316, 12977, 1869, 67, 55879] | [39316, 12977, 1869, 67, 55879] | **exact, same order** |

Logit VALUES agree within ~0.02–0.2 absolute across a range of roughly
±25 for every prompt (e.g. "SafeC" prompt: reference `[-24.5, 4.53125, -5.375, -24.625, -9.0]` vs SafeC `[-24.539949, 4.521587, -5.524680, -24.666861, -9.191715]`) — exactly the magnitude of noise expected from
bf16-vs-f32 rounding accumulated across 35 layers of a 1536-dim model
computed in a different summation order than PyTorch/Accelerate uses,
NOT evidence of a bug. This is strong, convincing evidence every
mechanism in this implementation (PLE, cross-layer KV sharing, dual
RoPE, per-layer `layer_scalar`, the RMSNorm variant, softcapping, tied
embeddings) is correctly implemented end-to-end against the real model —
not just internally self-consistent, which is all the earlier synthetic-
checkpoint wiring test could prove.

**FIXED** (was noted here as a known gap, resolved same session):
`gemma4_tokenizer.sc` originally included `mem.sc`/`str.sc`/`map.sc`/
`vec.sc`/`string.sc` directly (full implementations), which collided
with `gemma4_model.sc`'s own transitive copies of the same (via
`json.sc`) whenever both were linked into one binary. Fixed exactly like
`gemma4_math.sc`/`gemma4_rope.sc` were fixed for `math.sc` earlier:
switched to declaration-only `.h` includes
(`mem.h`/`str.h`/`collections/map.h`/`vec.h`/`collections/string.h`),
and added explicit `extern` declarations for `malloc`/`free`/`strlen`
(these were relying on `mem.sc`/`str.sc`'s own internal `extern`
declarations leaking through when those `.sc` files were included
directly — switching to `.h`-only includes removed that, so they need
their own explicit externs now, same as `gemma4_math.sc`'s test harness
already does). The top-level build linking both modules together now
passes `mem.sc`/`str.sc`/`map.sc`/`vec.sc`/`string.sc` explicitly itself
(via `json.sc`, which already transitively supplies
`vec.sc`/`mem.sc`/`str.sc`/`string.sc`/`fmt.sc`/`convert.sc` — only
`map.sc` needs to be added on top for the tokenizer's `HashMap` use).
Verified in `tests/test_link_both.sc`: both `gemma4_tok_load`/
`gemma4_tok_encode` and (transitively, via the same binary linking
`gemma4_model.sc`) every model-forward symbol resolve and run
correctly in one binary — build command:
```
safec safec-docs/public/benchmarks/gemma4/tests/test_link_both.sc \
  SafeC/std/ml/gemma4_tokenizer.sc SafeC/std/ml/gemma4_model.sc \
  SafeC/std/ml/tensor.sc SafeC/std/ml/float16.sc \
  SafeC/std/serial/json.sc SafeC/std/io_file.sc SafeC/std/math.sc \
  SafeC/std/collections/map.sc \
  -I SafeC --emit-bin -o /tmp/test_link_both_bin
```
The eventual HTTP server should use this same build-file list (plus
whatever `std/net`/`http_serve_reactor` needs on top).

Build commands used (`run_reference.py` needs `/tmp/tokvenv` set up as
above; `test_gemma4_model_real.sc` needs the same build command as
`test_gemma4_model.sc` above, just pointed at the real checkpoint path
instead of the synthetic one — see the file itself for the exact
hardcoded token ids used).

## KV cache implementation status: DONE, verified exact

`struct Gemma4KvCache` (`gemma4_model.h`) holds one growing K/V pair per
layer 0..`firstKvSharedLayerIdx`-1 (the only layers that ever compute
their own K/V — see header comment). `gemma4_model_forward`'s signature
changed from a plain `unsigned long cacheLen` to `struct Gemma4KvCache* cache` (nullable — null means the original stateless one-shot behavior
every earlier test already relies on, unchanged): when non-null, each
non-shared layer's freshly-computed K/V for the new tokens is
concatenated (`tensor_concat_rows`, new generic primitive in
`gemma4_math.h`/`.sc`) onto whatever was already cached for that layer,
the cache slot is updated in place (old tensor freed, freshly-concat'd
one now owned by the cache), and `cache->cachedLen` advances by the
number of new tokens. Cross-layer-shared layers (15..34) are unaffected
by the cache directly — they still just borrow layer 13's/14's K/V
(which, under a cache, IS the freshly-concatenated full-context tensor
now sitting in the cache array) exactly as before.

Sliding-window cache slots are deliberately NOT trimmed to the window
width — correctness comes entirely from `gemma4_attn_forward`'s existing
mask, so an untrimmed cache is correct, just not memory-optimal for very
long contexts (a possible future optimization, not a correctness
requirement).

**Verified exact** (not just "close", genuinely bit-identical) in
`tests/test_gemma4_kvcache.sc` against the synthetic checkpoint: running
4 tokens through in one shot (no cache) vs. prefilling 3 tokens into a
cache then decoding the 4th as a separate call produces IDENTICAL
last-position logits across all 50 synthetic vocab entries (`diff<1e-4`
check passed at every position — the tiny threshold is just float
formatting slop, the underlying computation is the same sequence of
floating-point operations either way since `tensor_concat_rows` just
copies the same bytes in the same order attention already iterates over).
This is the correctness bar that actually matters for a KV cache: an
incremental/cached computation must produce the same result as full
recomputation, not merely "run without crashing."

**Also re-ran the full real-10.25GB-checkpoint numerical validation**
(`tests/test_gemma4_model_real.sc`, updated for the new `cache`
parameter, called with `(struct Gemma4KvCache*)0`) after this refactor
to confirm the signature/body change didn't regress the earlier
from-scratch validation against real `transformers` output. Result: see
the top5-ids table above (in the "Numerical validation against the real
checkpoint" section) — that table's numbers were regenerated against the
POST-refactor code and still hold. The refactor only ADDS a code path
taken when `cache` is non-null; the `cache==null` path's control flow is
identical to what was already validated.

## Generation loop implementation status: DONE, verified

`std/ml/gemma4_generate.h`/`.sc` implements `gemma4_generate`: prefills
the prompt into a fresh `Gemma4KvCache` (one `gemma4_model_forward`
call), then decodes one token at a time (`maxNewTokens` cap, `eosId`
early stop), calling a per-token callback (`Gemma4TokenCallback = fn void(void* ctx, int tokenId)`) immediately after each token is picked —
this is the hook the HTTP server wraps directly for streaming, since the
caller can flush a response chunk from inside the callback before the
next token is even computed. Greedy (`temperature<=0`, argmax) and
temperature-sampling (`softmax(logits/temperature)`, numerically-stable
two-pass max-then-sum, sampled via `std::sys_rand()`/`RAND_MAX` from the
existing `std/sys.h` — no new PRNG needed) both implemented; the eos
token itself is never passed to the callback. The loop avoids computing
one wasted extra decode-step forward pass after the last token that will
actually be emitted (checks `maxNewTokens` before, not just after,
computing the next step).

Verified in `tests/test_gemma4_generate.sc` against the synthetic
checkpoint: streaming callback fires exactly once per generated token in
order; greedy decoding is byte-for-byte deterministic across two
independent runs (same prompt → identical token sequence both times);
temperature=1.0 sampling produces a genuinely different, varied sequence
(confirms `sys_rand` is actually wired in, not silently falling back to
argmax); and an eos-triggered stop configured to match the very first
greedy-generated token correctly returns 0 generated tokens (the eos
check happens before the callback fires, so it's never emitted). Greedy
decoding against the RANDOM-weight synthetic checkpoint predictably
degenerates into repeating one token after a few steps — expected for
random weights with no learned structure, not a bug.

**Also ran a real end-to-end text-generation smoke test against the real
10.25GB checkpoint** (`tests/test_gemma4_generate_real.sc`: real
tokenizer + real model + `<bos>`-prefixed prompt, matching how the
checkpoint would actually be used, greedy decoding, streaming callback
decodes and prints each token immediately). Prompt `"The capital of France is"` → generated: `" Paris.\n\nThe capital of France is Paris.\n\nThe capital of France is Paris.\n\nThe"` — genuinely correct,
coherent, factually accurate continuation, then predictably loops (an
artifact of pure greedy decoding with no repetition penalty, not a bug —
consistent with the earlier "greedy degenerates into repetition" note
above, just with real, correct content each time before it loops instead
of random-weight garbage). This is the strongest possible confirmation
that the full pipeline — tokenizer → model forward → KV cache →
generation loop — is correct end-to-end against the real model, not just
numerically close on isolated logits.

## MPS (GPU) acceleration: DONE, verified — real, large speedup, zero regression

`tensor_linear` (`gemma4_math.sc` — the workhorse behind every q/k/v/o/
gate/up/down/per_layer_*/lm_head projection in the whole model) now
tries `std::mps_matmul_abt_f32` (`std/ml/gpu_mps.h`) first, falling back
to the original portable CPU triple loop if no Metal device is available
or the GPU call fails. `mps_matmul_abt_f32(a, b, out, M, N, K)` computes
exactly `out[M,K] = a[M,N] . b^T` (`b` stored `[K,N]`) — precisely the
shape `y = x @ W^T` already needs, so no transpose is required in either
direction; this was a drop-in swap, not a redesign. `mps_available()`
(which calls `MTLCreateSystemDefaultDevice()`, real per-call cost) is
checked once and cached (`__g4math_mps_ok`), not re-checked on every one
of the hundreds of `tensor_linear` calls a single forward pass makes.

**Important correction to this codebase's own docs**:
`safec-docs/stdlib/ml.md`'s GPU-backend warning box claims
`mps_add_f32`'s `dispatchThreadgroups:threadsPerThreadgroup:` call
"currently segfaults" on real Apple Silicon. That page is STALE —
`gpu_mps.sc`'s own inline comment on `__mps_run_binary_kernel` says this
was already "root-caused and fixed in CodeGen.cpp" (a compiler codegen
fix for two consecutive struct-by-value Objective-C message-send
arguments), and this was directly re-verified this session: a standalone
test (`tests/test_mps_linear.sc`) calling `mps_matmul_abt_f32` ran to
completion with the exact correct numeric result, no crash, before this
was wired into the model at all. Someone should update `ml.md`, but that
wasn't done this session (out of scope for this benchmark's own files).

**Build-system gotcha (real, non-obvious)**: `safec --emit-bin` has NO
`-framework` passthrough in its CLI at all — confirmed by reading
`compiler/src/main.cpp`'s link-step argument construction directly; only
`-L`/`-l` (`LinkLibDirs`/`LinkLibs`) get forwarded to the internal
`clang` invocation. Since `gpu_mps.sc` needs `-framework Metal -framework Foundation -lobjc`, and macOS's system frameworks aren't
plain `-l`-linkable dylibs (no on-disk Mach-O binary to point `-l`/a raw
path at directly on modern macOS — framework binaries live in the shared
dyld cache), the only clean fix is `SAFEC_CLANG` (an env var `safec`
already supports for overriding which `clang` binary it shells out to,
found in the same file). `tests/clang_mps_wrapper.sh` is a two-line
wrapper that appends the three framework flags and `exec`s the real
`clang` — point `SAFEC_CLANG` at it (absolute path) for ANY build that
links `gemma4_math.sc` (or anything else pulling in `gpu_mps.sc`) from
now on:
```
SAFEC_CLANG=/path/to/tests/clang_mps_wrapper.sh safec ... --emit-bin -o out
```
Every build command elsewhere in this file that includes
`gemma4_math.sc` (directly or transitively — i.e. essentially all of
them from the attention module onward) now implicitly needs this env var
set too; not re-listing it in every single command block above, but
assume it going forward.

**Verified, full regression sweep after wiring this in** (same
discipline as the KV-cache refactor — every test that could plausibly be
affected was re-run, not just the new code path):
- `tests/test_gemma4_math.sc`, `test_gemma4_attention.sc`,
  `test_gemma4_ffn.sc`, `test_gemma4_ple.sc`, `test_gemma4_model.sc`
  (synthetic), `test_gemma4_kvcache.sc` — every value matches the
  pre-GPU run to float32 precision (tiny last-digit differences from a
  different summation order, same as the earlier BF16-vs-F32 tolerance
  discussion, not a correctness regression).
- `tests/test_gemma4_model_real.sc` (the real-checkpoint validation
  against actual `transformers` output) — **same top-5 token ids in the
  same order for every prompt**, and logit values now match even MORE
  tightly than the original CPU run did (~0.0002 absolute vs ~0.02–0.2
  before — GPU-vs-CPU float32 differs less than CPU-vs-BF16 did, exactly
  as expected). Total wall time for load + 3 forward passes: **40.3s**
  (previously multiple minutes).
- `tests/test_gemma4_generate_real.sc` (real end-to-end text generation)
  — identical output text ("The capital of France is Paris." — same
  deterministic greedy continuation as the CPU run). Total wall time
  (load + prefill + 20 decode steps): **59.5s**, down from several
  minutes; isolating just the generation portion (subtracting the ~35–40s
  model-load time, unaffected by this change since it's pure I/O +
  BF16→F32 conversion, no matmuls involved) puts per-token decode at
  roughly **1–2 seconds**, down from the ~8s/token measured before this
  change — a real, substantial, directly-measured speedup, not just a
  theoretical one.

**Not yet done**: `tensor_gpu.h`'s `tensor_gpu_mark_persistent` exists
specifically for exactly this model's situation (weights that never
change across calls, unlike SGD-updated training weights) — marking
every loaded weight tensor persistent once at `gemma4_model_load` time
would let every `tensor_linear` call reuse an already-uploaded GPU buffer
instead of re-uploading (memcpy) the full weight matrix on every single
call. Not wired in this session; the un-persisted version above already
delivered a large, verified win, and persistent-buffer wiring touches
`gemma4_model_load`/`gemma4_model_free`'s lifecycle more invasively — a
reasonable next optimization pass, not required for correctness.

## HTTP streaming server: DONE, verified with real byte-level chunk timing

`std/ml/gemma4_http_server.h`/`.sc` implements `gemma4_http_serve_stream`.
Key design decision (made after checking, not assuming): `std/http/http.h`'s `HttpHandler` contract is strictly request-in/complete-
response-out (`fn struct HttpResponse(struct HttpRequest*)`) with no
incremental-write hook anywhere — its own header comment says "chunked
transfer-encoding is not supported." Real token-by-token push is
therefore impossible to build on `http_serve_reactor`'s handler
abstraction at all. Per explicit user direction (offered WebSocket,
buffered-HTTP, and hand-rolled-chunked-HTTP as options; hand-rolled
chunked HTTP was chosen), this module instead talks directly to the same
portable public socket primitives `std/http/http.sc` itself is built on
(`std/sched/io_nb.h`'s `tcp_listen_nb`/`tcp_accept_nb`/`fd_set_blocking`,
"flipped to blocking mode" exactly like `http_serve()` does) and
hand-writes real HTTP/1.1 chunked-transfer-encoding bytes
(`<hex-len>\r\n<data>\r\n` per chunk, `0\r\n\r\n` terminator) as each
token is generated — a real HTTP/1.1 server any browser/curl/fetch() can
talk to, just not routed through `http.h`'s value type. Deliberately
minimal HTTP: only the request line is parsed (`GET /generate?prompt=<url-encoded text> HTTP/1.1`), percent-decoded
(`+`→space, `%XX`→byte), no header parsing, no request body support —
enough for a benchmark demo, not a general request parser. One
connection at a time (matches `std::http_serve`'s own single-threaded
contract, not `http_serve_threaded`/`_reactor`'s concurrent ones — fine
for a benchmark demo).

SafeC-language note: `match` is a reserved keyword (pattern-matching
construct) — an early draft used it as a plain `int` variable name in the
query-string parser and hit a wall of parser errors; renamed to
`isMatch`.

**Verified two ways**:
1. `curl` against the synthetic (but real-vocab-sized, see below)
   checkpoint: HTTP 200, correct `Transfer-Encoding: chunked` header,
   and — checked at the raw wire-byte level via `curl --raw` piped
   through a small Python script, not just "curl auto-decoded it so it
   must be fine" — the exact chunk framing bytes
   (`b'2\r\n\xdc\xac\r\n...0\r\n\r\n'`) match the real HTTP/1.1 chunked
   spec exactly, 10 chunks for `maxNewTokens=10`.
2. **Real incremental delivery, not buffer-then-send**, confirmed against
   the REAL checkpoint with a byte-by-byte timestamped Python client
   (`tests/timestamp_chunks.py`, `http.client` reading one byte at a
   time so arrival timing is visible): each token's text arrives at a
   distinct, clearly-separated wall-clock timestamp (not all at once at
   the end), and the streamed text is identical to the standalone
   generation test's output ("Paris." / "The capital of France is
   Paris." / ...). Per-token gap directly measured at ~7-8s BEFORE the
   MPS change below, ~1.3-1.5s AFTER — this is what re-confirmed the GPU
   speedup was real in the exact end-to-end path a user actually hits,
   not just in an isolated benchmark.

**Testing-the-tester gotcha worth recording**: while getting the server
running, a background `until curl ...; do sleep; done` "wait until up"
loop spent 56 minutes apparently stuck at 0% CPU with ~1MB RSS. Turned
out NOT to be stuck — `sample <pid>` showed it blocked in `accept()`,
meaning the model had already fully loaded and the server was correctly
idle waiting for a connection; the `printf`s announcing this never
appeared in the piped/`tee`'d log because C stdio fully-buffers
non-TTY stdout and only flushes on program exit or a full buffer.
`sample`/`lsof` (checking what a "stuck" process is actually blocked on)
is the right first move before assuming a hang, not writing it off as
inherent slowness or restarting the process.

**Also needed a real-vocab-but-tiny synthetic checkpoint for fast local
testing** (`tests/make_synthetic_gemma4_realvocab.py`, `vocabSize=262144`
matching the real tokenizer but `hiddenSize=8` etc. otherwise tiny) —
the original 50-vocab synthetic checkpoint would go out-of-bounds the
instant a real tokenized prompt produced any token id above 49, since
`gemma4_embed_lookup_rows` indexes the embedding table directly by id
with no bounds check (deliberately — see that function's own contract,
trusting well-formed input).

## Project reorganization + PyTorch/MLX comparison benchmark: DONE

Restructured as its own `safeguard`-shaped project (see the warning box
at the very top of this file for the full detail — paths, build
approach, the `safeguard build` blocker and why `./build.sh` is the real
build path instead). Every existing test re-verified bit-for-bit
identical to its pre-reorg result, including the real-checkpoint
validation — zero regression from the move itself.

Added a genuine three-way speed comparison against PyTorch and MLX,
same real checkpoint, same prompt (`"The capital of France is"`), same
20 greedy tokens (`tests/bench_pytorch.py`, `tests/bench_mlx.py`):

| Framework | Device | Load | Generation (20 tokens) | Per token |
|---|---|---|---|---|
| MLX | Apple Silicon GPU | 3.4s | 1.41s | 70ms (fastest) |
| PyTorch | CPU | 0.5s (fastest) | 2.79s | 139ms |
| PyTorch | MPS | 7.7s | 7.26s | 363ms |
| SafeC | MPS | ~35-40s | ~26-30s | ~1.3-1.5s |
| SafeC | CPU | ~35-40s | ~140-160s | ~7-8s |

All three produce the exact same correct continuation ("Paris." then the
expected greedy-decoding repeat loop) — this is a speed comparison, not
a correctness gap. SafeC is slower across the board, reported honestly:
`tensor_linear` has no operator fusion, no persistent GPU weight buffers
(`tensor_gpu_mark_persistent` exists in `std::ml` but isn't wired in
here — see TODO below), and the checkpoint load path (BF16→F32
conversion) isn't optimized either, versus PyTorch/MLX's years of
kernel-level tuning. The GPU-vs-CPU win WITHIN SafeC (~5-6x) is still
real and honestly measured; it's just not yet competitive against mature
frameworks in absolute terms.

**MLX finding worth recording**: `mlx_lm.utils.load_model(..., strict=True)` (the default) rejected loading the real checkpoint outright
— 60 "extra" parameters, all `self_attn.{k_proj,k_norm,v_proj}.weight`
for layers 15-34. This is INDEPENDENT third-party confirmation of this
project's own earlier finding (see the "RESOLVED" section above): mlx-lm's
own from-scratch Gemma4 model definition also has no K/V weights at all
for the cross-layer-KV-shared layers, and the real checkpoint really does
carry vestigial tensors for them that no correct implementation should
read. Fixed by loading with `load_model(path, strict=False)` +
`load_tokenizer(path)` directly instead of the high-level `load()`
convenience wrapper (which doesn't expose `strict`).

## Performance optimizations: fusion, persistent GPU buffers, parallel load, paged KV cache — DONE, verified

Four optimizations, each verified against the synthetic checkpoint and
re-verified bit-identical (or float-rounding-close) against the real
`google/gemma-4-E2B` checkpoint before moving to the next one — same
discipline as every other piece of this project.

**1. Persistent GPU weight buffers.** Every weight matrix used to be
re-uploaded to the GPU on every `tensor_linear` call, even though weights
never change across a forward pass. `gemma4_math.sc` now keeps a
pointer-keyed `HashMap` (keyed on the weight `Tensor*`'s own address,
stable for the tensor's lifetime) from weight tensor to its uploaded
`mps_upload_persistent` device buffer, uploaded once on first use and
reused on every later call via `mps_matmul_abt_f32_persistent`.
`gemma4_release_weight_buf` evicts a weight's cache entry before its CPU
`Tensor` is freed (`gemma4_model_free`) — required, since a later
`gemma4_model_load` reusing the same freed address would otherwise pick
up a stale GPU buffer for a completely different weight (an ABA hazard).
`tensor_linear` is now a three-tier fallback: persistent-GPU (normal
path) → fresh-GPU-upload (if persistence fails) → portable CPU triple
loop (if no Metal device at all).

**2. Parallel checkpoint loading.** Loading was one thread walking all
~540 tensors sequentially. `gemma4_model_load` now builds a flat list of
`(tensor name, destination pointer)` jobs, partitions them across 8
worker threads (`std::thread_create`/`thread_join` — see the note below
on why not the language-level `spawn`/`join`), each worker opening its
own independent `SafetensorsFile` handle (cheap: ~264KB JSON header
re-parse, no shared mutable state between workers) and loading its
assigned range. A sequential post-pass afterward derives values that
depend on load having finished (`headDim` from `qProjW`'s shape, the
gate/up weight fusion below) since worker completion order isn't
guaranteed. Measured: **22–27s** real-checkpoint load time (was
~35–40s).

Hit a real compiler bug getting here: SafeC's language-level
`spawn`/`join` compiled to calls to `__safec_thread_create`/
`__safec_thread_join` that were never defined — `CodeGen::selectThreadBackend()` picked the wrong backend for this build's
macOS target-triple detection. Worked around by using `std::thread_create`/`thread_join` (`std/thread.h`, plain library wrappers
around `pthread_create`/`pthread_join`) instead, plus `-l pthread` at
link time — sidesteps the broken intrinsic path entirely. This is a
compiler quirk unrelated to gemma4 itself, not something this project
fixes.

**3. Operator fusion (gate/up projection).** The gated FFN computed
`gate = x @ gateProjW` and `up = x @ upProjW` as two separate
`tensor_linear` calls (two GPU dispatches). `gemma4_model_load` now
concatenates `gateProjW`/`upProjW` row-wise into one `gateUpProjW`
(`tensor_concat_rows`, at load time, once) and `gemma4_ffn_forward_fused`
does ONE `tensor_linear` call, then splits the result back into gate/up
halves with `tensor_slice_cols` before the gelu/mul/down_proj tail —
halves the FFN's GPU dispatch count. Verified exactly bit-identical to
the non-fused path (`tests/test_gemma4_ffn.sc`'s fused-vs-non-fused
block). `gateProjW`/`upProjW` are still loaded and kept (used to derive
`intermediateSize`, and the original unfused `gemma4_ffn_forward` is
still available). QKV fusion and a fully GPU-chained FFN (never leaving
the GPU for gelu/multiply) were both considered and deliberately NOT
attempted: QKV fusion needs `gemma4_attn_project_q/k/v` split into a
raw-projection step and a per-head-norm+RoPE step first (bigger
refactor, higher risk); a chained FFN needs new Metal gelu and
two-buffer-elementwise-multiply kernels that don't exist anywhere in
`gpu_mps.sc` today (would mean writing and shipping a new `.metallib`).
Both are reasonable future work, not done here.

**4. Paged KV cache (vLLM-style).** The old cache re-concatenated the
ENTIRE growing K/V tensor on every single append (`tensor_concat_rows`
onto an ever-larger tensor) — O(n²) total copy cost across a generation.
`Gemma4KvCache` now stores each cached layer's K/V as a
`Vec<Gemma4KvPage>` (`gemma4_attention.h`), fixed `GEMMA4_PAGE_SIZE=128`
row blocks; each `Gemma4KvPage` holds both its K and V slice together
(one page list per layer, not two — see the bug note below).
`gemma4_kv_pages_append` either fills a page's unused tail rows in place
(zero copy of existing data) or allocates a new page once full, never
touching a page that already has data. `gemma4_attn_forward_paged`
reads K/V directly out of the pages during the score/softmax/
weighted-sum computation (`pageIdx = j / GEMMA4_PAGE_SIZE`, `rowInPage = j % GEMMA4_PAGE_SIZE`) — it never materializes a flat contiguous copy at
attention time either, which is the actual point: flattening at read
time would just move the O(n) cost from "append" to "read," not remove
it. Cross-layer KV sharing (layers 15–34 borrowing layer 13/14's K/V)
works by having a shared layer just read layer 13's or 14's page list
directly out of the cache array — no separate "shared" tracking needed
in the paged path, since those slots are already populated earlier in
the same forward-pass loop iteration. The stateless one-shot path
(`cache == null`, what every numerical-validation test drives) is
untouched — still flat `Tensor` K/V through the original
`gemma4_attn_forward`, exactly as before, zero risk to already-verified
correctness.

Verified via `tests/test_gemma4_kvcache.sc` (one-shot 4-token decode vs.
3-token-prefill + 1-token-decode-through-the-cache must match
bit-for-bit — it does) and a dedicated paged-vs-flat comparison added to
`tests/test_gemma4_attention.sc` (140 rows of K/V, appended in two
chunks that straddle a `GEMMA4_PAGE_SIZE` page boundary, compared
against the flat computation over the identical content — bit-for-bit
match). Both the real-checkpoint one-shot test
(`test_gemma4_model_real.sc`) and the real-checkpoint generation test
(`test_gemma4_generate_real.sc`, which exercises prefill + 19 decode
steps through the paged cache) still produce correct, coherent output
after this change.

Honest caveat: paged attention's real benefit is avoiding the O(n²)
append cost over LONG generations / long contexts — for this
benchmark's short ~20-token demo prompts, the win over the previous
"trimmed but still $O(n)$-per-append" design is real but not dramatic,
since 20 tokens never gets far enough into quadratic territory to show
a large gap. It matters far more as context length grows, which is the
scenario paged attention (and this implementation) is built for.

**Bug caught during this work, worth recording**: the first version of
the paged cache gave `Gemma4KvCache` TWO parallel page-list arrays,
`k` and `v`, mirroring the old flat design's `k`/`v` split — but
`Gemma4KvPage` already carries both a K and a V tensor per page. Only
`cache->k[li]` was ever appended to; `cache->v[li]` stayed permanently
empty, and reading it back in `gemma4_attn_forward_paged` null-deref'd
inside the attention hot loop the first time a cached (non-one-shot)
forward pass ran. Caught immediately by `tests/test_gemma4_kvcache.sc`
segfaulting instead of printing its "bit-close" result. Fixed by
collapsing `Gemma4KvCache` to a single `pages` array (one
`Vec<Gemma4KvPage>` per cached layer, each page already holding both K
and V) — the design `Gemma4KvPage` was built for in the first place.

**Measured before/after (real checkpoint, MPS/GPU, same
`"The capital of France is"` prompt, 20 greedy tokens, `tests/bench_safec.sc`)**:

| Stage | Before this work | After all four optimizations |
|---|---|---|
| Checkpoint load | ~35–40s | ~22–27s |
| Generation (20 tokens) | ~26–30s (~1.3–1.5s/token) | ~19–20s (~0.96–1.0s/token) |

The `benchmarks.md`/`ko/benchmarks.md` three-way comparison table is
updated with these numbers. The CPU-fallback row in that table was NOT
re-measured this session (no code path here changed the CPU triple-loop
itself beyond what fusion/paged-cache already give it for free) and is
carried over from the prior measurement.

## Further optimization: QKV projection fusion, fully GPU-chained FFN — DONE, verified (with a real bug found and fixed along the way)

Two more optimizations on top of the four above, closing out the "possible future optimizations" the previous section deferred.

**QKV projection fusion.** `gemma4_attn_project_q`/`_k`/`_v` used to be three separate `tensor_linear` calls (three GPU dispatches) even though, for every non-shared layer, all three read the SAME input (`normed1`). `gemma4_model_load` now builds `qkvProjW` (`Gemma4LayerWeights`'s fourth conditionally-absent weight, alongside `kProjW`/`kNormW`/`vProjW`) by concatenating `qProjW`/`kProjW`/`vProjW` row-wise, once, at load time — only for non-shared layers, since `isKvShared` layers have no `kProjW`/`vProjW` to fuse at all (they still compute their own Q every layer, just never their own K/V). `gemma4_attn_project_qkv_fused` (`gemma4_attention.h`/`.sc`) does ONE `tensor_linear` call against `qkvProjW`, then slices the result into Q/K/V column ranges and runs the EXACT same per-head norm+RoPE post-processing `gemma4_attn_project_q`/`_k`/`_v` already did — mathematically identical to the three separate calls, same reasoning as the gate/up fusion. `gemma4_model_forward`'s per-layer body now branches on `isKvShared`: shared layers still call plain `gemma4_attn_project_q` (unfused, `qkvProjW` unused/null for them); non-shared layers call the fused function once, producing Q *and* K *and* V together, which then feed both the paged-cache and stateless attention paths identically to before. Verified bit-for-bit-equivalent to the three separate calls (`tests/test_gemma4_attention.sc`'s dedicated fused-vs-separate comparison, real RoPE table and non-trivial norm weights, not identity) and bit-identical end-to-end against both the synthetic and real checkpoints.

**Fully GPU-chained fused FFN.** `gemma4_ffn_forward_fused` (the previous round's gate/up fusion) still read the gate/up matmul's result back to the CPU before running `tensor_gelu_fwd`/`tensor_mul` there — every FFN call paid a GPU→CPU→GPU round trip in the middle. `gemma4_ffn_forward_fused_gpu` (`gemma4_ffn.h`/`.sc`) instead runs the ENTIRE gated FFN (gate/up matmul → GELU → multiply → down matmul) as one Metal command-buffer batch: a new `gated_gelu_kernel` (`std/ml/gpu_mps_kernels.metal`, dispatched via a new `mps_gated_gelu_f32_chained`, `std/ml/gpu_mps.h`/`.sc`) reads the gate/up matmul's still-GPU-resident output directly (`mps_batch_last_output_buffer()`) and computes `gelu(gate) * up` in one dispatch, whose own still-pending output chains straight into the down-projection matmul (`mps_matmul_abt_f32_persistent`) — nothing but the FINAL result ever gets read back to the CPU. Falls back to `gemma4_ffn_forward_fused` if MPS is unavailable or any step fails. This is a genuinely new addition to `std/ml`'s shared GPU backend (not gemma4-specific): `gated_gelu_kernel` and `mps_gated_gelu_f32_chained` live in the actual standard library, reusable by any future SafeC model with a gated (GeGLU/SwiGLU-style) FFN, not just this one. Verified bit-for-bit-equivalent to `gemma4_ffn_forward_fused` (`tests/test_gemma4_ffn.sc`, both a tiny identity-weight case and a larger 16×8×4 shape) and wired into `gemma4_model_forward` as the default FFN path.

**A real bug, found by this project's own real-checkpoint testing discipline and fixed in the shared stdlib.** The fully GPU-chained FFN passed every synthetic test, an isolated real-scale (rows=10, hiddenSize=1536, intermediateSize=6144) synthetic-weight test, and even a same-shape repeated-call test alternating row counts on the same cached weight buffers — then produced NaN logits on the SECOND of the three real-checkpoint validation prompts (`"The quick brown fox..."`, 10 tokens; the 4-token and 2-token prompts were unaffected). Bisection (reverting just the FFN chain back to the non-chained path while keeping QKV fusion) isolated the bug to `gated_gelu_kernel` specifically. Direct inspection of the matmul's raw output at the failing call showed completely healthy, finite values (max absolute value 27.39, zero NaN/inf) — but `gated_gelu_kernel`'s *output* already had exactly one NaN. Root cause: `c*(x + 0.044715*x^3)` for `x≈27.4` evaluates to `inner≈755`, and this GPU's Metal `tanh()` overflows to NaN for arguments in roughly that range (`(e^{2x}-1)/(e^{2x}+1)` computed naively overflows both numerator and denominator to `+inf`, giving `inf/inf = NaN`) instead of correctly saturating to `1.0` the way CPU libm's `tanh()` does — and `x=27.4` is an entirely ordinary activation magnitude for this real checkpoint, not an edge case a well-behaved model should be expected to avoid. **Fix**: clamp `inner` to `[-20, 20]` before calling `tanh()` in `gated_gelu_kernel` — `tanh(20)` already rounds to exactly `1.0f` in float32, so this changes nothing for any input the old code got right, and fixes every input it didn't. Regenerated the embedded metallib (`gen_mps_metallib.sh`) and added a dedicated regression test (`tests/test_gemma4_ffn.sc`'s large-gate-value case, `x=30` → expected output `900.0`, previously NaN) plus re-verified every synthetic and real-checkpoint test end-to-end after the fix. This is a genuine, previously-undiscovered bug in `std/ml`'s Metal backend (`gpu_mps_kernels.metal`'s `tanh()` usage), not gemma4-specific — worth being aware of anywhere else in the stdlib a GPU kernel calls `tanh()`/`exp()` on an unbounded value without a saturating fallback.

**Measured before/after (real checkpoint, MPS/GPU, same `"The capital of France is"` prompt, 20 greedy tokens, `tests/bench_safec.sc`)**:

| Stage | Previous round (persistent buffers + parallel load + gate/up fusion + paged cache) | This round (+ QKV fusion + fully GPU-chained FFN) |
|---|---|---|
| Checkpoint load | ~22–27s | ~26–27s (unchanged — this round touched no load-path code) |
| Generation (20 tokens) | ~19–20s (~0.96–1.0s/token) | ~16.6–16.7s (~0.83–0.84s/token) |

`benchmarks.md`/`ko/benchmarks.md`'s three-way comparison table is updated with these numbers.

## BF16: processing (not just storage) in bfloat16 throughout — DONE, verified

Everything up through the previous section ran in float32: the real checkpoint's weights are natively BF16 on disk, but `safetensors.sc`'s `.load()` widened every one of them to F32 immediately (`bf16_to_f32_bulk`), and every downstream op (matmuls, attention, RMSNorm, RoPE, GELU) computed in float32 from then on — genuinely wasteful, since F32 carries no information BF16 didn't already round away at the checkpoint's own training time. This section replaces that with a second, parallel model implementation that stores and computes in bf16 the whole way through, and is now what `main.sc`'s HTTP server actually serves requests with (the F32 model stayed in the codebase at this point — used internally as a widen/narrow boundary for a few precision-sensitive ops, and as the correctness reference this was verified against — but was no longer the default path; a later round below deleted it entirely and made every remaining widen/narrow op bf16-native instead).

**New type**: `Gemma4Bf16Tensor` (`gemma4_bf16.h`/`.sc`) — `unsigned short* data` (raw bf16 bits, the same storage convention `std/ml/float16.h` already established for GPU upload) plus shape/ndim/size, a standalone struct rather than a change to `std::Tensor` itself (SafeC's core F32 tensor type, used throughout the rest of `std::ml` — changing it was both unnecessary and far riskier than a parallel type scoped to this project). Every weight AND every activation crossing a function boundary is one of these now, for all 35 layers.

**Design principle, applied consistently everywhere in this section**: bf16→f32 widening is exact and lossless (bf16 shares float32's exponent width exactly, just fewer mantissa bits — see `float16.h`); f32→bf16 narrowing is a real, intentional rounding step. Every op here either (a) does **real bf16 GPU compute** for the matmul-shaped ops — the dominant FLOP cost in a transformer forward pass (every q/k/v/o/gate/up/down/per_layer_\*/tied-lm-head projection) — via `mps_matmul_abt_bf16_persistent` (bfloat multiply, **float32 accumulate**, bfloat output — standard mixed-precision-GEMM practice, not a deviation from "bf16 processing"; accumulating in bf16 itself would be numerically unusable, and no real bf16 inference framework does that either), or (b) widens to a temporary F32 Tensor, reuses the **existing, already real-checkpoint-verified F32 CPU/GPU math** for precision-sensitive, comparatively cheap ops (RMSNorm, RoPE, attention's score/softmax loop, GELU), then narrows the result back to bf16 before returning. This mirrors how production bf16 inference frameworks actually behave (LayerNorm/softmax are routinely kept in float32 internally regardless of the model's overall dtype) and reuses 100% of already-verified numerical logic for everything except the handful of GPU matmul kernels that needed genuine bf16 dispatch.

**Weight loading**: `SafetensorsFile::load_bf16` (`safetensors.h`/`.sc`) reads BF16-dtype tensors as a **straight byte copy** — zero conversion, zero precision loss beyond what the checkpoint already has, and half the memory/IO of the old widen-to-F32 `.load()`. (F32-dtype tensors, none present in the real checkpoint but handled for robustness, narrow down via `f32_to_bf16_bulk`.) Verified bit-identical to `.load()`-then-widen on a real checkpoint tensor before anything else was built on top of it.

**Every module got a bf16 counterpart**, each verified against its already-tested F32 sibling:
- `gemma4_bf16_rmsnorm_rows`/`_noscale`, `_add`, `_mul`, `_scale`, `_softcap`, `_slice_cols`, `_concat_rows`, `_embed_lookup_row`/`_rows` — widen/reuse/narrow wrappers (`gemma4_bf16.h`/`.sc`).
- `gemma4_bf16_linear` — the one function doing real bf16 GPU compute, with its own persistent bf16 weight-buffer cache (pointer-identity-keyed, same design as the F32 `tensor_linear`'s cache, just uploading already-bf16 raw bits directly via `mps_upload_persistent`'s dtype-agnostic raw byte upload — no redundant f32 round trip anywhere).
- `gemma4_bf16_rope_apply` (`gemma4_rope.sc`) — widen/existing-`gemma4_rope_apply`/narrow.
- `gemma4_bf16_attn_project_q/_k/_v/_qkv_fused`, `gemma4_bf16_attn_forward` (stateless path, widens q/k/v/oProjW and reuses `gemma4_attn_forward` directly), `gemma4_bf16_attn_forward_paged` (the path real generation actually uses — a bf16-native score/softmax hot loop that widens only the two scalars it multiplies per inner step, not a whole buffer, then a real `gemma4_bf16_linear` for o_proj), plus `Gemma4Bf16KvPage`/`gemma4_bf16_kv_pages_append`/`_total_len`/`_free` (bf16-backed paged KV cache — halves the cache's own memory footprint too, not just the weights) (`gemma4_attention.h`/`.sc`).
- `gemma4_bf16_ple_combine`/`_slice_layer` (`gemma4_ple.h`/`.sc`) — PLE's matmul runs once per forward pass (not once per layer), so the widen-and-reuse-`tensor_linear` cost here is minor.
- `gemma4_bf16_ffn_forward_fused` (`gemma4_ffn.h`/`.sc`) — **both** matmuls (gate/up and down) run as real bf16 GPU compute; GELU widens/reuses/narrows rather than getting a bf16-native kernel of its own (bf16's coarser mantissa would only make the tanh-overflow risk the F32 GPU gelu kernel already needed a real fix for — see the previous section — worse, for an elementwise op that isn't the dominant cost anyway).
- `Gemma4Bf16Model`/`Gemma4Bf16LayerWeights`/`Gemma4Bf16KvCache`, `gemma4_bf16_model_load`/`_free`/`_forward` (`gemma4_model_bf16.h`/`.sc`, a parallel file rather than modifying `gemma4_model.h`/`.sc` in place) — mirrors the F32 model's structure field-for-field; loading is parallelized across worker threads exactly like the F32 path, just through `load_bf16` into `Gemma4Bf16Tensor` destinations directly (simpler than the F32 version's `(struct Tensor**)`-cast destination-pointer plumbing, since `Gemma4Bf16Tensor` is a value type a worker can assign whole). `gemma4_bf16_model_forward` returns **F32** logits, not bf16: the tied LM head matmul itself runs as real bf16 GPU compute against the model's single largest weight, but the result widens back to F32 before softcap — the sampling/argmax code downstream (`gemma4_generate.sc`) needed zero changes as a result.
- `gemma4_bf16_generate` (`gemma4_generate.h`/`.sc`) and `gemma4_http_serve_stream_bf16` (`gemma4_http_server.h`/`.sc`) — thin mirrors of the F32 versions, swapping only the model/cache type and forward-pass call; token sampling logic (`__g4g_argmax`/`__g4g_sample`) is shared, unchanged, since both forward passes return F32 logits.
- `main.sc` now loads and serves through the bf16 path by default.

**Correctness, verified at every tier this project has used throughout**:
- Synthetic checkpoint: `tests/test_gemma4_bf16.sc` (core tensor ops), `tests/test_gemma4_bf16_load.sc` (bf16 load vs F32-load-then-widen, bit-identical), `tests/test_gemma4_bf16_model.sc` (full model, finite logits, sane magnitudes), `tests/test_gemma4_bf16_kvcache.sc`.
- **KV cache correctness** (`test_gemma4_bf16_kvcache.sc`): one-shot vs. prefill+decode-through-the-cache produces the same **greedy argmax token** — the invariant a real generation loop actually needs — with raw logit values close but not bit-identical (unlike the F32 cache test's 1e-4 tolerance, bf16's ~2-3 significant decimal digits per intermediate rounding step, compounding across 35 layers, is a real, expected, much coarser noise floor; checked via relative rather than fixed-absolute tolerance).
- **Real checkpoint** (`test_gemma4_bf16_model_real.sc`): all three of this project's standard validation prompts (`"Hello, world!"`, `"The quick brown fox..."`, `"SafeC"`) produce **exactly the same top-5 token ids, in the same order**, as the F32 reference — logit magnitudes differ by bf16-rounding-scale amounts (e.g. 11.45 vs. 10.91 for the top logit), the values themselves are NOT expected to match and don't, but which tokens rank where does, which is what actually matters for generation quality.
- **Real checkpoint generation** (`test_gemma4_bf16_generate_real.sc`) and the **live HTTP server** (`gemma4_bf16_server`, smoke-tested with a real `curl` request against a running instance): `"The capital of France is"` → `"Paris."` then the same predictable greedy-decoding repeat loop every other run in this project has produced — correct, coherent output end to end.

**Measured (real checkpoint, same machine, same `"The capital of France is"` / 20-greedy-token setup as every other benchmark number in this document)**:

| Stage | F32 (previous sections) | BF16 (this section) |
|---|---|---|
| Checkpoint load | ~26–27s | **~6.5–7.8s** |
| Generation (20 tokens) | ~16.6–16.7s (~0.83–0.84s/token) | **~7.8–9.1s (~0.39–0.46s/token)** |
| Peak resident memory (measured, `ps -o rss`, sampled through a full load+generate run) | ~15.6 GB | ~14.5 GB |

Load time and per-token generation time both improved substantially and reproducibly (roughly 4x and 2x respectively) — halved disk I/O (raw bf16 bytes, no per-tensor F32 upcast pass) plus real bf16 GPU compute (half the per-element bytes moved through GPU memory bandwidth for every matmul, which is what these decode-step matmuls are: memory-bandwidth-bound, not compute-bound, at batch size 1). Peak resident memory, honestly reported, is only **modestly** lower than F32, not the roughly-half a naive "half the bytes per weight" argument would predict — most likely because `tensor_linear`'s and `gemma4_bf16_linear`'s persistent weight-buffer caches keep BOTH a CPU-side copy and an uploaded GPU-side copy of every touched weight alive simultaneously, and on Apple Silicon's unified memory architecture a Metal buffer is backed by ordinary RAM, so that's real double-counting against `ps`'s RSS for both dtypes alike, diluting the relative bf16 win at the "total resident bytes" level even though every individual weight buffer itself is genuinely half the size. Worth a closer look as future work; not investigated further here given the load-time and generation-time wins (the numbers this session's optimization work has consistently focused on) are large, clean, and reproducible on their own.

## BF16: CPU+BLAS compute path, and full deletion of the F32 model — DONE, verified

The previous section made bf16 the *default* path but kept it partly a hybrid: `gemma4_bf16_linear`'s CPU fallback still widened its operands to a temporary F32 `Tensor`, called the F32 `tensor_linear`, and narrowed the result back; RMSNorm, RoPE, attention, and PLE all did the same widen/reuse-F32/narrow trick around their own math; and the entire original F32 model (`gemma4_model.h`/`.sc`, `gemma4_math.h`/`.sc`, F32 attention/PLE/FFN/generate/HTTP-server code) still lived in the codebase as the widen/narrow boundary and correctness reference. This round removes that hybrid entirely: every gemma4-specific F32 code path is deleted outright, and everything that used to widen-to-F32-and-call is rewritten to be bf16-native (reads/writes bf16 bits directly via `bf16_to_f32`/`f32_to_bf16` at the point of each scalar use, never materializing a whole intermediate F32 `Tensor`). Generic `std::ml` functions that aren't gemma4-specific (`tensor_add`, `tensor_mul`, `tensor_scale`, `tensor_gelu_fwd`, `cblas_sgemm`) are unaffected — they're shared stdlib, used by other benchmarks on this site too, not part of this deletion.

**Deleted outright**: `gemma4_model.h`/`.sc` (the F32 model struct + forward pass), `gemma4_math.h`/`.sc` (F32 math primitives), the F32 halves of `gemma4_rope.sc` (`gemma4_rope_apply`), `gemma4_attention.h`/`.sc` (`Gemma4KvPage`, `gemma4_kv_pages_*`, `gemma4_attn_project_q/_k/_v/_qkv_fused`, `gemma4_attn_forward`/`_forward_paged`), `gemma4_ple.h`/`.sc` (F32 PLE combine/slice), `gemma4_ffn.h`/`.sc` (`gemma4_ffn_forward`, `_fused`, `_fused_gpu`, and their GPU-chain scratch-buffer helpers), `gemma4_generate.h`/`.sc`'s F32 `gemma4_generate`, `gemma4_http_server.h`/`.sc`'s F32 `gemma4_http_serve_stream`, plus every test file that only existed to exercise that F32 code (`test_gemma4_model*.sc`, `test_gemma4_math.sc`, `test_gemma4_kvcache.sc`, `test_gemma4_generate_real.sc`, `gemma4_server_real.sc`, `gemma4_server_synthetic.sc`, `time_load_only.sc`). `build.sh`'s file list and every remaining test that referenced the deleted API were updated to match.

**CPU+BLAS path, added to `gemma4_bf16_linear`** (`gemma4_bf16.sc`): a new `gemma4_bf16_set_force_cpu(int)` toggle (module-level `static int` flag) makes the function skip the MPS check and go straight to CPU, for controlled CPU-vs-GPU benchmarking or explicit CPU-only serving. The CPU path itself (`__g4bf16_linear_cpu_blas`) widens `x`/`w` to temporary F32 buffers (`bf16_to_f32_bulk`), calls `cblas_sgemm` (Apple's Accelerate, same library and calling convention `tensor_blas.sc` already uses elsewhere in `std::ml`, declared with the same `extern` convention — no C-header interop in SafeC), and narrows the F32 result back to bf16 (`f32_to_bf16_bulk`) — the widen/narrow here is *at the BLAS call's boundary only*, not a whole-function fallback to the old F32 `gemma4_bf16_linear` CPU path (which no longer exists). `w` is `[outF, inF]`-shaped; `cblas_sgemm(..., transB=112 /* Trans */, ...)` reads its storage AS its transpose in the one call, never physically materializing `w^T` — the same trick `tensor_blas.sc`'s own `__matmul_backward_blas` already uses. Links via the `-framework Accelerate` already present in `tests/clang_mps_wrapper.sh` — no build changes needed.

**Every remaining widen/narrow op rewritten bf16-native**:
- `gemma4_bf16_rmsnorm_rows`/`_noscale` (`gemma4_bf16.sc`) — sum-of-squares and `rsqrt` still accumulate in float per row (necessary for numerical stability), but read the source tensor via `bf16_to_f32` per element and write the result via `f32_to_bf16`, no intermediate F32 `Tensor`.
- `gemma4_bf16_softcap` (`gemma4_bf16.sc`) — `tanh_f(bf16_to_f32(x)/cap)*cap` narrowed directly; CPU libm's `tanh` is robust across this argument range (unlike the GPU Metal kernel's `tanh`, which needed the `[-20,20]` clamp fix documented above — this function was never at risk since it always runs on CPU).
- `gemma4_bf16_rope_apply` (`gemma4_rope.sc`) — reads q/k's bf16 bits directly in the rotate-half loop, applies the (still-F32, small precomputed lookup table) cos/sin values, writes bf16 directly. The old F32 `gemma4_rope_apply` function body is gone; `Gemma4RopeTable`/`gemma4_rope_build` stay F32 (a small precomputed table, not activations, so no precision reason to change it).
- `__gemma4_bf16_attn_head` (new, `gemma4_attention.sc`) — a bf16-native flat/stateless attention hot loop, mirroring the paged variant that already existed: dot products and the weighted-value sum read q/k/v via `bf16_to_f32` per scalar, softmax's max/exp/sum reduction happens in a plain `float* scores` scratch buffer (required for numerical stability), final output written via `f32_to_bf16`. `gemma4_bf16_attn_forward` now calls this instead of widening to F32 and calling the (now-deleted) F32 `gemma4_attn_forward`.
- `gemma4_bf16_ple_combine` (`gemma4_ple.sc`) — rebuilt entirely out of the bf16-native primitives above (`gemma4_bf16_linear`, `_scale`, `_rmsnorm_rows`, `_add`, plus a new `gemma4_bf16_write_cols` for scattering each layer's normalized slice back into the combined tensor) rather than widening the whole thing to F32 and calling the old F32 PLE function.
- `gemma4_bf16_ffn_forward_fused` (`gemma4_ffn.h`/`.sc`) is now the *only* FFN function (the separate non-fused/fused/GPU-chained F32 variants are gone); its GELU step deliberately still goes through `std::ml`'s generic (already-fixed) `tensor_gelu_fwd`/`tensor_mul` rather than a bespoke bf16-native GELU kernel — a genuinely new bf16 tanh kernel would reintroduce exactly the overflow risk class the GPU `gated_gelu_kernel` fix (above) had to correct for, for an elementwise op that isn't the dominant FLOP cost anyway.
- `gemma4_bf16_model_forward`'s final softcap step now calls `gemma4_bf16_softcap` (bf16-native) directly on the raw bf16 logits from the tied LM head matmul, widening to F32 only once, at the very end, for the function's F32 return type — not through the deleted `tensor_logit_softcap`.

**Correctness, re-verified after the full rewrite** — every existing test adapted to the bf16-only API (11 test files, `Gemma4Model`→`Gemma4Bf16Model`, `gemma4_generate`→`gemma4_bf16_generate`, etc.) compiles and passes on the first full rebuild. The real-checkpoint model test (`test_gemma4_bf16_model_real.sc`) produced an interesting improvement over the previous (partial-widen) bf16 round: the `"Hello, world!"` prompt's top-5 token ids now match the `transformers` reference **exactly** (`[236888, 108, 1, 107, 1717]`), where the earlier widen/narrow-based bf16 implementation matched only 4/5 (it produced `26352` instead of `1717` for the low-confidence 5th token). The other two validation prompts continued to match exactly, as before. Not chased further as proof of causation, but consistent with the hypothesis that fewer intermediate F32 round-trips reduce cumulative rounding drift on borderline logit ties. Real-checkpoint generation (`test_gemma4_bf16_generate_real.sc`) still produces the correct `"Paris."` continuation and the expected greedy-repeat loop.

**Measured (real checkpoint, same machine, same `"The capital of France is"` / 20-greedy-token setup, `tests/bench_safec.sc` — now bf16-only, with a `cpu` argv flag that calls `gemma4_bf16_set_force_cpu(1)` before load+generate for a controlled CPU-vs-GPU comparison)**:

| Path | Load | Generation (20 tokens) | Per token |
|---|---|---|---|
| MPS (GPU, default) | 6.6s | 6.1s | 303ms |
| CPU, Accelerate BLAS (`gemma4_bf16_set_force_cpu(1)`) | 6.5s | 282.7s | 14.1s |

Load time is essentially unchanged between the two (same disk I/O and weight-buffer setup either way — the CPU/GPU split only affects where the matmuls themselves run, not how weights get from disk into memory). Generation is ~47x slower on CPU+BLAS than on MPS, which tracks: at batch size 1 every decode step is one core of Accelerate's vectorized throughput against the whole GPU's parallelism, for a 2B-parameter model whose matmul shapes favor a GPU far more than they favor BLAS. The load- and generation-time numbers for the (now fully bf16-native) MPS path also improved slightly over the previous round's (~6.5–7.8s load / ~7.8–9.1s generate, ~0.39–0.46s/token) — plausibly the removed widen/narrow overhead throughout the math, though this wasn't isolated as a controlled A/B and could just be run-to-run variance. `benchmarks.md`/`ko/benchmarks.md`'s three-way comparison table is updated with these numbers; the older, purely-F32 MPS and no-BLAS-CPU rows are retired along with the F32 model implementation itself.

## Batched decode: gemma4_bf16_model_forward_batch / gemma4_bf16_generate_batch — DONE, verified

Every benchmark up through the previous section ran a single sequence at a time (`batchSize`/`M` = 1 for every matmul in the decode step) — the standard way to lose throughput to memory-bandwidth-bound decode: each decode step's matmuls move every weight byte through GPU/CPU memory bandwidth to produce just one row of output, when the exact same weight bytes could produce many rows for roughly the same memory traffic. This section adds real multi-sequence batched decoding on top of the existing single-sequence path, on both backends.

**Design**: every matmul-shaped op in a layer (q/k/v/o, gate/up/down, per_layer_\*, and the tied LM-head projection — the dominant FLOP cost) already accepts an arbitrary row count in `gemma4_bf16_linear`, on both the MPS path (`mps_matmul_abt_bf16_persistent`) and the CPU+BLAS path (`cblas_sgemm`) — batching those was "free": stack one new-token row per sequence into a `[batchSize, hidden]` tensor and every matmul in the layer batches automatically, no changes needed to `gemma4_bf16_linear` itself. RoPE was already ready for this too — `gemma4_rope_build` takes an explicit per-row `positions` array (not an assumed `0..count-1` range), so each sequence's own current position (`cachedLen`) just becomes that array. RMSNorm, add, mul, scale, softcap, PLE, and the gated FFN are all row-independent by construction, so they batch with zero changes as well.

Attention was the one piece that couldn't just batch for free: it's a hand-rolled per-head score/softmax/weighted-sum loop (`gemma4_attention.sc`), not a matmul running through BLAS/MPS at all, and each sequence must only attend to its OWN KV cache. `gemma4_bf16_model_forward_batch` (`gemma4_model_bf16.h`/`.sc`) loops over batch rows for just the KV-append + attention-score step, reusing the existing, already-verified single-sequence `gemma4_bf16_kv_pages_append`/attention-head code unchanged, once per sequence in the batch (row-copy helpers `__g4mbf16_row_copy`/`__g4mbf16_write_row` move one row in/out, mirroring `gemma4_attention.sc`'s own per-head slice-copy helpers — this codebase's `Gemma4Bf16Tensor` has no view/stride mechanism by design, so a real copy, not a reshape). This costs the identical total FLOPs either way — there was no BLAS/MPS call in the attention-score step to lose out on merging — just `batchSize` small loop iterations instead of one.

**New API** (mirrors the single-sequence functions, generalized to a batch):
- `gemma4_bf16_model_forward_batch(model, tokenIds, batchSize, caches)` (`gemma4_model_bf16.h`/`.sc`) — `tokenIds[b]` is sequence `b`'s one new token, `caches[b]` its own already-prefilled `Gemma4Bf16KvCache*`. Returns `[batchSize, vocabSize]` F32 logits; increments every `caches[b]->cachedLen` by 1.
- `gemma4_bf16_generate_batch(model, prompts, batchSize, cfg, ctx, onToken, outGenerated)` (`gemma4_generate.h`/`.sc`) — `prompts[b]` is `{ids, count}` (prompts can have different lengths; each is prefilled separately via the existing unbatched `gemma4_bf16_model_forward`, since prefill's per-sequence row count already varies), then every decode step afterward runs through `gemma4_bf16_model_forward_batch` together. Simplification, clearly not a production continuous-batching scheduler: no dynamic shrinking when a sequence finishes early — a finished sequence just stops receiving `onToken` calls while its cache keeps silently advancing alongside the batch until every sequence is done or `cfg.maxNewTokens` is hit (harmless wasted compute for that row; correctness is unaffected since attention only ever reads a sequence's own cache).

**Correctness** (`tests/test_gemma4_bf16_batch.sc`, synthetic checkpoint): three different-length prompts run individually through `gemma4_bf16_generate` (the baseline) and then together through `gemma4_bf16_generate_batch` — every sequence's batched output is **bit-identical** to its own individual run, both the token ids and the generated count, confirming no cross-sequence contamination anywhere in the batched path (KV cache isolation, positions, everything). Re-verified after both optimizations below.

**Two follow-up optimizations, once the first real batch-size sweep showed sub-linear scaling**:

1. **Reusable softmax scratch buffer.** The attention hot loop used to `malloc`/`free` its `scores` buffer on every single call — for batched decode, that's `batchSize × numLayers × numHeads` malloc/free pairs per decode step (`35 × 8 × batchSize`). Replaced with a module-level grow-only buffer (`__g4attn_scoresBuf`/`__g4attn_get_scores` in `gemma4_attention.sc`) reused across every call — safe because generation is single-threaded throughout this codebase, and every call fully overwrites `scores[0..kvSeqLen)` before reading any of it back, so there's no possible stale-data leak from reuse. Verified bit-identical (`test_gemma4_bf16_batch.sc`, `test_gemma4_attention.sc`). **Effect: negligible** (~0.2–2% on batch=4/8 MPS numbers) — turned out not to be where the time was going.
2. **Batch the o_proj matmul, not just q/k/v/gate/up/down.** The real cause: the first version of `gemma4_bf16_model_forward_batch` called `gemma4_bf16_attn_forward_paged` per batch row, and that function bundles its own `o_proj` matmul internally — so o_proj, exactly as expensive as any other big projection, was silently running once per sequence with `M=1` instead of once for the whole batch, un-batching one of the four dominant-cost matmuls per layer. Fixed by splitting attention into two pieces: a new `gemma4_bf16_attn_raw_paged` (`gemma4_attention.h`/`.sc`) that does only the per-head score/softmax/weighted-sum loop and returns the raw concatenated `[seqLen, numHeads*headDim]` output with NO o_proj; `gemma4_bf16_attn_forward_paged` itself is now just that function plus one `gemma4_bf16_linear` call, unchanged behavior for the existing non-batch callers. `gemma4_bf16_model_forward_batch` calls the raw version per row (writing each row into a `[batchSize, numHeads*headDim]` tensor), then applies `o_proj` ONCE across the whole batch afterward. Verified bit-identical again. **Effect: real** — see the sweep below.

**Measured (real checkpoint, same machine, same `"The capital of France is"` prompt replicated `batchSize` times, same 20-greedy-token decode, `tests/bench_safec.sc <cpu|gpu> <batchSize>`), after both fixes, batch=1 as the same-session baseline for the ratio column**:

| Backend | Batch size | Per-decode-step wall time | Aggregate throughput | vs. batch=1 |
|---|---|---|---|---|
| MPS | 1 | 298.7ms | 3.35 tok/s | — |
| MPS | 4 | 651.9ms | 6.14 tok/s | **1.83x** for 4x sequences |
| MPS | 8 | 1160.3ms | 6.90 tok/s | **2.06x** for 8x sequences |
| MPS | 16 | 2211.8ms | 7.23 tok/s | **2.16x** for 16x sequences |
| MPS | 32 | 4110.0ms | 7.79 tok/s | **2.33x** for 32x sequences |
| MPS | 64 | 7840.8ms | 8.16 tok/s | **2.44x** for 64x sequences |
| CPU, Accelerate BLAS | 1 | 14135.2ms | 0.071 tok/s | — |
| CPU, Accelerate BLAS | 4 | 16697.2ms | 0.240 tok/s | **3.39x** for 4x sequences |

(batch=1 numbers on both backends are provably unaffected by either fix — there is nothing to batch at batch=1 — so the MPS batch=1 row here is a fresh same-session measurement for a clean ratio baseline, while the CPU+BLAS batch=1 row is reused from the earlier, pre-fix benchmark run.)

The o_proj fix helped (MPS batch=4 went from 2.07x to a cleaner apples-to-apples 1.83x-against-a-faster-baseline — the absolute aggregate throughput numbers themselves rose at every batch size: 5.80→6.14 tok/s at batch=4, 6.40→6.90 tok/s at batch=8, on the same hardware), but scaling stayed clearly sub-linear on MPS, and the sweep up through batch=64 shows why: **per-decode-step wall time roughly doubles every time batch size doubles** (652ms→1160ms→2212ms→4110ms→7841ms for 4→8→16→32→64), i.e. very close to *linear* growth in wall-clock cost, which means the aggregate-throughput *gain* shrinks with every doubling (1.83x→2.06x→2.16x→2.33x→2.44x) — a classic diminishing-returns curve, not a bug. The remaining cause is structural, not a missed batching opportunity: the per-sequence attention score/softmax/weighted-sum loop is real, unavoidable work that scales with `batchSize` regardless of backend (see the design section above — it was never a matmul to begin with), and at this model's short cache lengths during early decode (tens of tokens), that loop's total cost across `numHeads × numLayers` is comparable in magnitude to the matmuls it can't share batching with. **Practical takeaway for MPS: batch sizes around 16–32 capture most of the achievable aggregate-throughput win** (2.16x–2.33x) before additional batch size mostly just adds wall-clock latency per step for smaller and smaller marginal throughput gains (32→64 only added 0.11x more).

CPU+BLAS scales far closer to linear (3.39x for a 4x batch) than MPS does at the same batch size (1.83x) — consistent with the same structural explanation working in CPU+BLAS's favor here: a CPU decode step is already ~47x slower than an MPS step at batch=1 (see the previous section), so the SAME fixed per-sequence attention overhead is a much smaller fraction of a CPU step's total time, leaving much more of the win to come from batching the (now dominant, and now genuinely merged) BLAS matmul calls. CPU+BLAS wasn't swept further than batch=4: each additional doubling already costs on the order of 5+ minutes for one 20-step data point, and the batch=1→4 delta already demonstrates the qualitative pattern (CPU+BLAS scaling noticeably better than MPS) without needing to confirm exactly where its own curve eventually flattens. Batching is not wired into `main.sc`'s HTTP server (`gemma4_http_serve_stream_bf16` still serves one request at a time) — that's a real request-scheduling feature (queuing concurrent requests into a batch, handling requests that arrive mid-batch), out of scope for this benchmark-focused addition.

### PyTorch and MLX, batched too — how far behind is SafeC's batching, honestly

The earlier PyTorch/MLX comparison (see "Project reorganization + PyTorch/MLX comparison benchmark" above) only ever measured batch=1, since that's all this project's own generation loop supported at the time. Once SafeC had real batched decode, the natural next question was whether PyTorch and MLX would scale the same way — both frameworks have their own real batched-generation entry points (`tests/bench_pytorch.py` uses `model.generate()` with `input_ids.repeat(batchSize, 1)`; `tests/bench_mlx.py` uses `mlx_lm.generate.batch_generate`, a real continuous-batching-style `BatchGenerator`, not a hand-rolled loop), so this is a genuine, apples-to-apples same-API-family comparison, not SafeC-specific plumbing versus nothing.

**Measured (real checkpoint, same machine, same prompt/tokens as every other number in this document, batch sizes 1/4/8/16/32)**:

| Framework | Device | Load | batch=1 | batch=4 | batch=8 | batch=16 | batch=32 | batch=32 vs. batch=1 |
|---|---|---|---|---|---|---|---|---|
| PyTorch | CPU | 0.5s | 3.51 tok/s | 26.34 tok/s | 32.88 tok/s | 35.75 tok/s | 36.29 tok/s | **10.3x** |
| PyTorch | MPS | 7.5s | 11.75 tok/s | 20.87 tok/s | 35.44 tok/s | 128.43 tok/s | 210.81 tok/s | **17.9x** |
| MLX | Apple Silicon GPU | 3.2s | 20.64 tok/s | 45.48 tok/s | 98.43 tok/s | 167.79 tok/s | 245.16 tok/s | **11.9x** |
| SafeC | MPS, BF16 | 6.6s | 3.35 tok/s | 6.14 tok/s | 6.90 tok/s | 7.23 tok/s | 7.79 tok/s | **2.3x** |

(PyTorch's batch=1 numbers here — 285ms/token CPU, 85ms/token MPS — differ noticeably from the single-sequence-only numbers measured for the original comparison, 139ms/token CPU and 363ms/token MPS respectively; same model, same prompt, same machine, different benchmarking session. Plausibly Metal shader-cache warmth for the MPS figure and general machine-load variance for the CPU figure — this project has already seen its own batch=1 MPS number drift run to run for the same underlying reason, see the sections above — not chased further since it doesn't change the qualitative finding below.)

This is worth stating plainly: **SafeC's batching win is real but far smaller than PyTorch's or MLX's**, and the gap is architectural, not a missing flag. PyTorch's SDPA and MLX's own attention implementation both compute an entire batch's attention as one real, fused, batched GPU operation. SafeC's attention (`gemma4_attention.sc`) is a hand-rolled per-head score/softmax/weighted-sum loop that was never vectorized across a batch dimension — `gemma4_bf16_model_forward_batch` (see above) batches every *matmul* (q/k/v/o/gate/up/down/per_layer/lm-head) into one call across the whole batch, which is genuinely correct and genuinely faster than not batching, but the *attention* step itself still costs `batchSize` separate small loop iterations, with no shared GPU dispatch or vectorization across sequences at all. At this model's short early-decode cache lengths, that per-sequence loop's total cost is already comparable in magnitude to the matmuls it can't share batching with (see the analysis above), so as batch size grows it increasingly dominates and SafeC's aggregate throughput curve flattens far sooner than PyTorch's or MLX's. Writing a real batched attention kernel (GPU: one Metal dispatch computing every sequence's scores/softmax/weighted-sum together, likely via a 3D threadgroup over `[batch, head, kvPos]`; CPU: at minimum interleaving batch items in the innermost loop for better cache reuse) would be the natural next step for closing this gap — a genuinely new piece of `std::ml`/GPU-kernel work, not a config change, and out of scope for this benchmark-focused pass. `benchmarks.md`/`ko/benchmarks.md`'s comparison table now reports each framework's own best-measured throughput (whichever batch size produced it) side by side, with this section as the detail behind why the shapes of the three curves differ so much.

## Closing (part of) the attention gap: real GPU-parallel attention + CPU SIMD/multithreading — DONE, verified

The previous section named the exact next step: attention had no real batched kernel on either backend, just a per-sequence loop. This section does that work — a real Metal kernel for MPS, and real SIMD-friendly restructuring plus multithreading for CPU — and reports honestly on how much of the PyTorch/MLX gap it actually closes (a real, verified ~30% throughput improvement at larger batch sizes, not the order-of-magnitude PyTorch/MLX enjoy — see the diagnosis at the end of this section for why, and what the next step after this one would need to be).

**CPU side — SIMD-friendly restructuring + multithreading, both in `gemma4_attention.sc`/`gemma4_model_bf16.sc`**:
- The dot-product and weighted-value-sum inner loops used to widen one bf16 scalar at a time (`bf16_to_f32(q.data[...])`) interleaved with the multiply-accumulate — a shape that inhibits auto-vectorization. Rewritten to bulk-convert each contiguous headDim-wide row segment via `bf16_to_f32_bulk` (`float16.h`) into a small scratch buffer, then run a plain `float*`-to-`float*` loop — clang -O2 auto-vectorizes that onto NEON far more reliably. The weighted-value-sum step was also reordered from (output-dim outer, kv-position inner) to (kv-position outer, output-dim inner) with a headDim-wide accumulator array — mathematically the identical sum per output dim, same ascending accumulation order over kv positions, so **bit-identical**, not just bit-close, while reading each K/V row contiguously instead of with a stride. `scores`/scratch buffers moved from a shared static (introduced in the earlier batching-optimization round) to per-call-allocated — required for the next change below, and correctness-neutral on its own (verified bit-identical, `test_gemma4_attention.sc`/`test_gemma4_bf16_batch.sc`).
- `gemma4_bf16_model_forward_batch`'s per-batch-item attention step is pure per-sequence work with no shared mutable state (each sequence only touches its own cache, writes only its own output row) — an embarrassingly parallel loop, so it's now spread across up to 8 worker threads (`__g4mbf16_run_attn_batch`/`__g4mbf16_attn_worker`, same job-splitting shape as the existing parallel-checkpoint-load code in the same file). KV-page-append was split out into its own pass (`__g4mbf16_append_kv_batch`) that always runs first, once, regardless of which attention path (GPU or CPU) ends up handling the actual score/softmax computation.
- **Effect**: real on MPS (attention was running on the CPU there too, since there was no GPU attention path yet at this point) — batch=8 went from 1160.3ms/step to roughly 874ms/step from this alone, before any GPU kernel work. **Negligible on CPU+BLAS** (16697ms/step → 16679ms/step, batch=4): the BLAS matmuls dominate total step time there by roughly two orders of magnitude, so even a large relative speedup to the comparatively tiny attention step doesn't move the total.

**GPU side — a real batched attention Metal kernel**, `attn_batch_bf16_kernel` (`std/ml/gpu_mps_kernels.metal`) + `mps_attn_batch_bf16` (`std/ml/gpu_mps.h`/`.sc`) + `gemma4_bf16_attn_batch_gpu` (`gemma4_attention.h`/`.sc`):
- **Kernel design**: one GPU thread per `(batch, head)` pair — genuinely parallel across both dimensions at once, unlike the CPU loop's one-at-a-time visitation. Each thread does the same O(kvLen·headDim) work the CPU per-head function does (causal+sliding-window-masked scores, softmax, weighted sum over V) serially within itself — the simplest genuinely-parallel-across-batch-and-heads kernel shape, not a tiled/threadgroup-cooperative "flash attention" kernel (see this project's own established precedent of shipping the simplest correct kernel first, e.g. `gated_gelu_kernel`). `scores`/`acc` are fixed-size per-thread local arrays (`G4ATTN_MAX_KV=2048`, `headDim` bounded at 1024) since Metal has no per-thread dynamic allocation; a KV length past that bound makes the CPU-side dispatch function refuse (return 0) rather than risk out-of-bounds reads/writes, so the caller cleanly falls back to the CPU path instead.
- **The gather step**: the kernel addresses K/V as one flat `[batchSize, maxKvLen, numKvHeads·headDim]` buffer, but this codebase's KV cache is paged (scattered fixed-size pages, see the paged-cache section above) — `gemma4_bf16_attn_batch_gpu` gathers each sequence's pages into that flat buffer before dispatching (an array-of-page-pointers addressable from inside a Metal kernel would be real added complexity for a cheap, one-time-per-call copy). First written as a scalar per-bf16-element copy loop; that turned out to be slow enough to matter (see below), so it was rewritten to `memcpy` each page's own contiguous multi-row run in one call (both a page's `k.data`/`v.data` and the destination buffer are row-major contiguous for a run of rows, so this is a real, not cosmetic, optimization).
- **A real correctness bug, found and fixed via the existing test suite**: the first working version read `cacheLens[b]` — the batch-level array built once per `gemma4_bf16_model_forward_batch` call, before any layer's KV got appended — directly as the KV length to gather and dispatch. But each layer's own pages Vec has *already* had this step's new token appended by the time attention runs (`__g4mbf16_append_kv_batch` runs first), so the true current length is `cacheLens[b]+1`, not `cacheLens[b]` — the GPU path was silently dropping every sequence's own just-generated token from its own attention window. `tests/test_gemma4_bf16_batch.sc`'s exact-match-against-single-sequence-baseline check caught this immediately (batch output token ids differed from the baseline, no crash, no NaN — a purely numerical divergence that would have been easy to miss without a hard equality check). Fixed by computing the true length from the actual page state via `gemma4_bf16_kv_pages_total_len` (same function the CPU path already calls internally), not trusting the passed-in array. Re-verified bit-identical after the fix, both on the synthetic checkpoint and — since the bug was invisible at the synthetic checkpoint's tiny headDim (4/6) and might not generalize — on the **real checkpoint** too (new `tests/test_gemma4_bf16_batch_real.sc`, 3 identical prompts batched together, decoded text matches the known-correct `" Paris.\n\nThe capital of France is Paris. ..."` continuation exactly).
- `gemma4_bf16_get_force_cpu()` (new getter alongside the existing `gemma4_bf16_set_force_cpu` setter, `gemma4_bf16.h`/`.sc`) lets the attention-kernel choice respect the same CPU/GPU toggle `gemma4_bf16_linear` already does, so `bench_safec cpu N` stays a genuinely CPU-only run end to end, not just for the matmuls.

**Measured (real checkpoint, same setup as every other number in this document, after both the CPU and GPU changes above)**:

| Batch | Per-decode-step | Aggregate throughput | vs. pre-this-section |
|---|---|---|---|
| 1 | 279.1ms | 3.58 tok/s | 298.7ms → 279.1ms (~7% faster) |
| 8 | 882.7ms | 9.06 tok/s | 6.90 → 9.06 tok/s (~31% faster) |
| 32 | 3129.7ms | 10.22 tok/s | 7.79 → 10.22 tok/s (~31% faster) |
| 64 | 6089.5ms | 10.51 tok/s | 8.16 → 10.51 tok/s (~29% faster) |

**Honest diagnosis of why this is a real ~30% win and not the order-of-magnitude PyTorch/MLX get**: `gemma4_bf16_attn_batch_gpu` dispatches its own fully-synchronous, standalone Metal command buffer (own commit + `waitUntilCompleted` + readback) — the same "one GPU op, one full round trip" shape every other `mps_*` call in this codebase uses (see `gpu_mps.h`'s "Batched dispatch" section on why that round trip is the dominant cost for small workloads once shader compilation is cached). That's fine for the matmuls, whose compute cost is large enough to be worth paying for, but attention runs once **per layer** — 35 separate synchronous dispatches per decode step, each paying its own command-buffer submission + sync latency, for a kernel whose actual compute (kvLen up to ~30, batchSize up to 64, headDim up to 512) is small enough that the fixed per-dispatch overhead plausibly dominates the real GPU work at this problem size. This is consistent with what the numbers show: the GPU kernel path measures only marginally faster than the CPU-multithreaded fallback at every batch size tested, even though the kernel itself is verified numerically correct and genuinely parallel. PyTorch and MLX don't pay this cost 35 times over: their attention op is chained into the same graph/command-stream as everything else in the layer, not synced-and-read-back in isolation. The concrete next step at the time this was written: thread `gemma4_bf16_attn_batch_gpu`'s dispatch into the SAME open batch as the surrounding matmuls — attempted next, see below.

## Chaining attention into the matmul command stream, and a hardware-matmul attempt that didn't pan out

Two follow-up attempts at closing more of the PyTorch/MLX gap, one that worked as a real (if modest) win and one that was tried, measured, and reverted — both reported honestly rather than only keeping the one that looked good.

**1. Chaining attention's GPU output directly into o_proj — real, small win.** The diagnosis above named the concrete next step: stop paying a full command-buffer round trip for attention and then ANOTHER for the o_proj matmul immediately after it. `mps_attn_batch_bf16_chained` (`gpu_mps.h`/`.sc`) is a chained sibling of `mps_attn_batch_bf16` — takes already-uploaded device buffers, requires an open batch (`mps_batch_begin()`), encodes onto the shared batch encoder instead of its own command buffer, and leaves its output as a still-pending GPU buffer (`mps_batch_last_output_buffer()`) instead of reading it back immediately, the same contract `mps_matmul_f32_chained`/`_persistent` already established for matmuls. `gemma4_bf16_attn_batch_gpu_chained` (`gemma4_attention.h`/`.sc`) uses this: gathers KV (same as before), opens one batch, dispatches attention chained, then immediately dispatches o_proj (`mps_matmul_abt_bf16_persistent`) using attention's still-pending output buffer directly as its input — one `mps_batch_end()` for both ops together. Exposed `gemma4_bf16_get_weight_buf` (`gemma4_bf16.h`/`.sc`) so `gemma4_attention.sc` can get o_proj's persistent GPU weight-buffer handle without going through `gemma4_bf16_linear`'s own fresh-upload-of-x path. `gemma4_bf16_model_forward_batch` tries this chained path first, falling back to the older non-chained GPU path, then the CPU path, on any failure. Verified bit-identical (synthetic and real checkpoint, batch=8 — chosen specifically because it's 8-aligned, see below).

Measured: batch=8 882.7ms→895.6ms/step (essentially flat, within run-to-run noise), batch=32 3129.7ms→3116.5ms/step (~0.4% faster), batch=64 6089.5ms→5914.1ms/step (~3% faster). Real, not a regression, but far smaller than hoped — removing one dispatch out of roughly seven per layer (q/k/v-or-qkv-fused, attention, o_proj, gate/up, down, per-layer gate, per-layer proj) turns out to only remove a small fraction of the total per-layer cost, meaning per-dispatch command-buffer overhead was a smaller piece of the pie than the original diagnosis assumed — see the next finding for what's actually more likely to dominate.

**2. Hardware `simdgroup_matrix` bf16 matmul — tried, verified correct, measured SLOWER, reverted.** Every gemma4 matmul (all of q/k/v/o/gate/up/down/per_layer/lm-head) goes through `matmul_abt_kernel_bf16` — a naive threadgroup-tiled kernel (`TILE_SIZE=16`, ordinary scalar multiply-accumulate), *not* the `simdgroup_matrix` hardware-tensor-unit path the float32 kernels already have (`matmul_abt_kernel_smma`/`_smma_multi` — see `gpu_mps.h`'s own header comment noting bf16 never got this tier: "porting those to half/bfloat accumulation is future work, not attempted here"). This looked like a very plausible dominant bottleneck: PyTorch's MPSMatrixMultiplication and MLX's fastest paths both use this same class of hardware unit, and gemma4's bf16 path never had access to it at all.

Wrote `matmul_abt_kernel_bf16_smma`: bfloat operand tiles loaded via `simdgroup_load`, `simdgroup_multiply_accumulate` into a real `simdgroup_float8x8` accumulator (confirmed Metal supports mixed bfloat-operand/float32-accumulator `simdgroup_multiply_accumulate` directly — verified by test-compiling a minimal kernel before writing the full one), matching this project's own "bfloat multiply, float32 accumulate" mixed-precision convention throughout. One real wrinkle: `simdgroup_matrix<bfloat,8,8>` has no converting constructor from `simdgroup_matrix<float,8,8>` on this Metal SDK (confirmed by a failed compile, not assumed) — no way to narrow the accumulator to bfloat before `simdgroup_store` inside the kernel, so the kernel outputs float32 and the CPU side narrows it afterward via a `mps_batch_register_finalize` callback (`f32_to_bf16_bulk`) — exactly the "op needs its own CPU-side post-readback processing" scenario that mechanism's own doc comment already names float32→float64 conversion as an example of. Wired into `mps_matmul_abt_bf16_persistent` with the same shape-based tiering `mps_matmul_abt_f32` already does (use the hardware path when M/N/K are all 8-aligned, naive kernel otherwise). Verified **numerically correct**: `test_gemma4_ffn.sc`'s GPU-vs-CPU+BLAS bit-close check (16×8×8, 8-aligned) passed, the full synthetic + real-checkpoint batch test suite passed bit-identical, and a real-checkpoint batch=8 run (batch size deliberately chosen to be 8-aligned so the fast path actually activates, not silently falls back) produced the correct `"Paris."` continuation.

Then benchmarked: **~13% SLOWER**, not faster (batch=8: 882.7ms→994.9ms/decode step, reproduced twice). Reverted (`mps_matmul_abt_bf16_persistent` back to always using the naive kernel; the smma kernel itself removed from `gpu_mps_kernels.metal` rather than left as unused dead code, this section standing as the record of what was tried). The honest explanation: gemma4's actual matmul shapes at decode time have `M` (rows = batch size, at most 64 in anything benchmarked here) tiny relative to `N`/`K` (hidden/intermediate dimensions, in the thousands) — this regime is memory-bandwidth-bound (the dominant cost is streaming the whole weight matrix through device memory once, not the multiply-accumulate FLOPs), not compute-bound. The single-simdgroup kernel written here has **no threadgroup-memory tiling** — each of its 32-thread simdgroups reads its operand tiles straight from device memory on every reduction step, with no on-chip reuse — while the naive kernel it replaced already caches a `TILE_SIZE`×`TILE_SIZE` block in threadgroup memory once and reuses it `TILE_SIZE` times, giving it *better* effective memory-bandwidth utilization despite doing the multiply-accumulate in scalar hardware instead of the dedicated matrix unit. Accelerating compute that was never the bottleneck, at the cost of worse memory access patterns, is a textbook way to regress a bandwidth-bound kernel. The float32 path's `matmul_abt_kernel_smma_multi` tier (32×32 threadgroup tiling *combined with* `simdgroup_matrix`, not simdgroup_matrix alone) exists specifically to avoid this failure mode — porting that combination to bf16, a meaningfully larger kernel than the one tried here, would be the real next step if this is revisited, with no guarantee it clears the bar either: this workload may simply be bandwidth-bound enough that hardware matrix units don't move the needle much at these batch sizes regardless of tiling, and the honest way to find out is to build and measure it, not assume.

**Where this leaves the PyTorch/MLX gap**: the batching + attention + chaining work across this and the previous section took SafeC's peak MPS throughput from 2.80 tok/s (batch=1, this document's original un-batched baseline) to 10.82 tok/s (batch=64) — a real ~3.9x improvement, all verified bit-identical or bit-close at every step. PyTorch and MLX are still at 210.8/245.2 tok/s (batch=32) — the remaining gap is not one more targeted fix away. It's the accumulated effect of production kernel-fusion compilers (`torch.compile`, MLX's lazy evaluation graph), years of hand-tuned memory-bandwidth-optimal GEMM kernels, and whole-forward-pass graph execution instead of per-op dispatch — the kind of investment this benchmark-focused project was never going to fully close in a handful of sessions, and this section's reverted experiment is itself a data point for why: even a "should obviously help" change (real hardware matrix units, correctly implemented) can be a net loss once the actual bottleneck (memory bandwidth, not compute) is accounted for, and finding that out required actually building and measuring it, not just reasoning about it in the abstract.

## Reframing to single-request latency, and speculative (prompt-lookup) decoding — DONE, verified

Every optimization up through the previous two sections targeted *aggregate* throughput at large batch sizes — the right metric for concurrent multi-user serving, but not what a single chat session actually experiences: one request, one sequence, decoding one token at a time. This section reframes around that case (batch=1 single-request latency as the metric that matters day to day) and adds a technique that's specifically about speeding up ONE request rather than serving more of them at once — vLLM's and HuggingFace's **prompt-lookup (speculative) decoding**, applied for the first time in this project.

**Why this counts as "single request divided into multi-batch," not batched serving**: `gemma4_bf16_generate_batch` (earlier sections) parallelizes ACROSS independent sequences — it needs multiple concurrent requests to have anything to batch. Speculative decoding parallelizes WITHIN one sequence's own future: draft several candidate next tokens, verify all of them in one forward call instead of one call per token. No second (draft) model is needed here — **prompt-lookup decoding** (the variant vLLM and HuggingFace both ship) drafts by searching the sequence's own already-generated history for the most recent earlier occurrence of the last few tokens, and speculatively continuing with whatever followed it there. It's a genuinely different lever from every prior section: those all made a fixed amount of work faster; this makes some decode steps disappear entirely by predicting several of them at once and checking the prediction in bulk.

**Design, and why it's losslessly exact, not an approximation**: `gemma4_bf16_generate_speculative` (`gemma4_generate.h`/`.sc`) mirrors `gemma4_bf16_generate`'s loop exactly for the "no repeat found" case (falls back to identical one-token-at-a-time decoding). When `__g4spec_prompt_lookup` finds a repeat (searches for the most recent non-overlapping earlier occurrence of the trailing `G4SPEC_MATCH_LEN`=3 tokens, an O(history length) scan, no model call), the tokens that followed that earlier occurrence become a draft (`nextId` plus up to `G4SPEC_MAX_DRAFT`-1=7 more). The WHOLE draft is verified in one call to `gemma4_bf16_model_forward` with `count`=draftLen — this needed **no new forward-pass code at all**: a multi-token forward call is exactly what prefill already does, and causal masking already makes row i's logits depend only on rows <i plus the cache, precisely the property verification needs (row i's argmax is what the model actually would have picked after seeing draft tokens 0..i, comparable directly against draft token i+1). The longest verified-correct prefix is accepted; a mismatch (or the drafted token itself being `eosId` — see the bug below) means every token from that point on gets rolled back out of the KV cache via two new primitives (`gemma4_bf16_kv_pages_truncate`, `gemma4_attention.h`/`.sc`, and `gemma4_bf16_kvcache_truncate`, `gemma4_model_bf16.h`/`.sc` — the paged cache only ever supported appending before this; truncation shrinks the last page's `used` count, popping and freeing whole pages if the rollback crosses a page boundary), and the model's own real prediction at the divergence point becomes the next token to try. The output is therefore bit-identical to plain greedy decoding for the same inputs — this is strictly a faster way to compute the exact same thing, never a different one, which is exactly speculative decoding's whole selling point over something like reduced precision or a smaller model. Only activates for `cfg.temperature<=0`: exact-match acceptance against a single `argmax` doesn't have a meaningful equivalent for a sampling distribution, so `temperature>0` requests automatically fall back to the same one-token-at-a-time shape `gemma4_bf16_generate` already uses (checked at the top of every draft-length decision, not a separate code path to keep in sync).

**A real correctness bug, found by testing, not inspection**: the first working version only checked drafted tokens against the model's own prediction (accept if `predicted == draft[i]`) — it never checked whether an accepted draft token was itself `cfg.eosId`. `gemma4_bf16_generate`'s own contract is explicit ("stop early if `cfg.eosId` is generated, NOT passed to `onToken`"), and a drafted token that happens to match both the model's real prediction AND `eosId` would have been silently accepted and streamed to the caller, violating that contract. The first test written used an impossible `eosId=-1` specifically to isolate the accept/reject logic from eos handling — which meant it could never have caught this. Fixed properly, then caught for real by a follow-up exhaustive test that sweeps every possible token id (0..49 on the 50-token synthetic vocab) as `eosId` and asserts both that speculative and plain decoding produce identical output AND that neither ever emits the eos id itself — rather than trying to hand-construct the one specific history/draft state that triggers the bug, which turned out impractical to predict in advance for an arbitrary random checkpoint.

**Correctness, verified at every tier this project uses**:
- `tests/test_gemma4_bf16_speculative.sc` (synthetic checkpoint): a 30-token run (long enough that the arbitrary 50-token vocab incidentally repeats, giving the drafter real matches to exercise both accept and reject) — bit-identical to `gemma4_bf16_generate`, confirmed by exact token-id comparison, not just token count. A short 5-token run confirms the "always falls back, never drafts" path is unaffected. The exhaustive `eosId` sweep described above.
- `tests/test_gemma4_bf16_speculative_real.sc` (real checkpoint): the standard `"The capital of France is"` prompt, 40 tokens (longer than the usual 20 specifically to let the greedy-repeat loop establish and give the drafter room to work) — bit-identical output, decoded text confirmed to be the expected `"...Paris.\n\nThe capital of France is Paris. ..."` loop.
- Wired into `main.sc`'s actual serving path (`gemma4_http_server.sc`, replacing the `gemma4_bf16_generate` call with `gemma4_bf16_generate_speculative` — identical signature, degrades safely for `temperature>0` callers) and smoke-tested end to end through a real `curl` request against a running server instance, not just the unit tests.

**Measured (real checkpoint, same `"The capital of France is"` prompt, 40 greedy tokens, single request/batch=1 — deliberately NOT the large-batch throughput numbers the previous two sections focused on, since this technique is about single-request latency specifically)**:

| | Wall time | ms/token (effective) |
|---|---|---|
| Plain (`gemma4_bf16_generate`) | 11.86s | 296.6ms |
| Speculative (`gemma4_bf16_generate_speculative`) | 6.53s | 163.1ms |

**~1.82x speedup, bit-identical output.** This is the single largest single-request latency win of any change in this document — bigger than the batching, attention-kernel, or chaining work combined, because it's a fundamentally different lever (fewer sequential model calls, not faster individual ones) applied to a workload (this benchmark's own greedy-repeat behavior) that happens to be an unusually good match for it. Real deployments with less repetitive output (open-ended creative writing, for instance) would see a smaller win — prompt-lookup decoding's whole value proposition is specifically for repetitive/structured continuations (RAG-style completions quoting retrieved context, code with repeated patterns, this benchmark's own degenerate greedy loop), not a universal multiplier. Reporting it honestly means noting that dependency, not just the headline number.

## A dedicated GEMV kernel for the M==1 decode step — DONE, a real win (unlike the earlier hardware-matmul attempt)

The earlier hardware-`simdgroup_matrix` bf16 matmul kernel (previous section) was reverted because it addressed the wrong axis: it tried to accelerate the *multiply* on a workload that's bandwidth-bound, not compute-bound. But there was a second, genuinely wasteful thing happening at gemma4's actual decode-step shape that the reverted attempt never touched: `matmul_abt_kernel_bf16`'s TILE_SIZE(16)×TILE_SIZE(16) threadgroup dispatch assumes an `M`-row tile worth of output per threadgroup. Every projection during single-request decode (`gemma4_bf16_linear`'s `rows` argument, the exact case this whole optimization round has been centered on since being told to treat single-request latency as primary) calls in with `M==1`. That means 15 of every 16 rows in each threadgroup's Y-dimension compute a full N-length dot product against real device memory and then discard the result at the final `row < M` bounds check — a large fraction of the kernel's own arithmetic thrown away on every single call, on every layer, every token.

**What was built**: `matmul_abt_kernel_bf16_gemv` (`std/ml/gpu_mps_kernels.metal`) — a dedicated matrix-vector kernel used only when `M==1`. `GEMV_SIMDGROUPS` (4) simdgroups per threadgroup, each simdgroup computes exactly one output element: lane `i` strides over `a`/`b` at offsets `i, i+32, i+64, ...` (fully coalesced — adjacent lanes read adjacent memory), accumulates in float, and `simd_sum` reduces the 32 partial sums to the final dot product. No threadgroup memory, no `threadgroup_barrier` at all — there is nothing to reuse across an `M==1` "tile," so the tiled kernel's whole machinery (built to amortize a weight-tile load across many output rows) was pure overhead here. `mps_matmul_abt_bf16_persistent` (`gpu_mps.sc`) now tries this GEMV path first when `M==1` and falls back to the existing tiled kernel if it's unavailable (e.g. batch not active) — the `M>1` path (prefill, batched serving) is completely untouched.

**Correctness**: `tests/test_mps_linear_gemv.sc` — five shapes (a hand-tiny case, hidden-size-square, a gate/up-proj-sized case, non-multiples of 32/4 to exercise the `K`-bounds check and `simd_sum` tail handling, and a `K < GEMV_SIMDGROUPS` edge case) each compared against a CPU reference dot product computed from the *same bf16-rounded operands* the GPU kernel reads, within a generous (2% of max magnitude) tolerance that only accounts for reduction-order differences, not a real bug. All five passed. The full existing regression suite (every `test_gemma4_*` file, the real-checkpoint model/generate/batch tests, and the speculative-decode tests including the exhaustive `eosId` sweep) was re-run afterward and still passes — the `M>1` batched path is provably unaffected since the new branch only ever fires for `M==1`.

**Measured, controlled A/B** (`tests/test_gemma4_bf16_generate_real.sc`, real checkpoint, prompt "The capital of France is", 20 greedy tokens, same process/session, alternated to rule out thermal/ordering effects — not a comparison against a number from an earlier session):

| | run 1 | run 2 | run 3 | run 4 | avg |
|---|---|---|---|---|---|
| GEMV off (baseline, tiled kernel) | 301.7ms/tok | 289.5ms/tok | 301.5ms/tok | 296.2ms/tok | 297.2ms/tok |
| GEMV on | 242.2ms/tok | 227.9ms/tok | 234.1ms/tok | 227.5ms/tok | 232.9ms/tok |

**~1.28x speedup (≈22% latency reduction), real and reproducible across four alternating runs each way** — unlike the earlier hardware-matmul attempt, which regressed. Root-cause difference: that attempt tried to speed up an already-optimal memory access pattern's *compute*; this one removes genuinely wasted compute and per-threadgroup synchronization overhead that existed only because a tiled-GEMM kernel was being used for a shape (`M==1`) that isn't a GEMM at all — it's a GEMV, and now has its own kernel.

This composes with speculative decoding (previous section): re-running `tests/test_gemma4_bf16_speculative_real.sc` with the GEMV kernel active gives plain-greedy at 231.6ms/token (down from that section's original 296.6ms/token baseline — consistent with the A/B above) and speculative decoding at 174.9ms/token effective (1.32x over plain, in this same GEMV-enabled run). Combined against the pre-this-round baseline: **296.6ms/token → 174.9ms/token, a 1.70x total single-request latency improvement**, from two independent, honestly-measured techniques stacked together rather than one large unverified claim.

## Chaining the FFN: a fully GPU-resident matmul → gated-GELU → matmul pipeline — DONE, another real win

After the GEMV kernel above, a natural follow-up question: how much of a single decode step's remaining time is real compute vs. GPU dispatch/synchronization overhead the same way the GEMV work found for wasted threadgroup compute? Measured directly (`tests/bench_mps_roundtrip.sc`, N=K=2560 bf16 matmul, 200 iterations): running each matmul as its own `mps_batch_begin()`/`mps_batch_end()` round trip costs 0.306ms/call; running the same 200 matmuls chained inside ONE round trip costs 0.066ms/call — **a fixed ~0.24ms overhead per round trip, 78% of that shape's per-call wall time**, present regardless of the op's own compute. gemma4's single-token decode step makes on the order of 150-180 of these separate round trips (every `gemma4_bf16_linear` call — q/k/v-or-fused-qkv proj, o_proj, gate/up proj, down proj, PLE gate, PLE proj — opened and closed its own command buffer), so eliminating even some of them was worth pursuing directly, not assuming.

**Why most of them couldn't just be merged**: RMSNorm, elementwise add/mul/scale, and GELU all sit on the CPU between consecutive GPU matmuls in `gemma4_bf16_model_forward`'s layer loop (`gemma4_bf16_rmsnorm_rows`, `gemma4_bf16_add`, etc. in `gemma4_bf16.sc` — pure CPU, no GPU dispatch at all), and each needs the ACTUAL VALUES of the previous matmul's output before it can run — a real data dependency, not an artifact of how the code happens to be organized. Chaining across one of these requires the intervening op to ALSO become a GPU kernel operating on the still-pending GPU buffer, or it isn't a real chain, just two round trips renamed.

**What was actually chainable, and safely**: the FFN block's `gate/up matmul → GELU(gate)*up → down matmul` sequence has exactly ONE such CPU-side step (the gated-GELU-and-multiply) sitting between two matmuls, and `std/ml/gpu_mps.sc` already had a working GPU kernel for exactly that fused op — `gated_gelu_kernel`/`mps_gated_gelu_f32_chained` — built for a different `std::ml` benchmark and never wired into gemma4, F32-only. Added `gated_gelu_kernel_bf16` (`gpu_mps_kernels.metal`) and `mps_gated_gelu_bf16_chained` (`gpu_mps.sc`/`.h`): same formula (including the tanh-overflow clamp this project found necessary against the real checkpoint — see the FFN implementation-status section above), bf16 storage instead of f32, same "consume a still-pending GPU buffer via `mps_batch_last_output_buffer()`, produce another still-pending buffer" chaining contract every other `_chained` function in this file already uses.

**`gemma4_bf16_ffn_forward_fused`** (`gemma4_ffn.sc`) now runs, when a GPU is available: `mps_batch_begin()` → gate/up matmul (bf16, reading `x` uploaded once) → `mps_gated_gelu_bf16_chained` consuming that matmul's pending output directly → down matmul consuming the GELU step's pending output directly → `mps_batch_end()` — ONE round trip for the entire FFN block instead of two, with the GELU/multiply itself also on-GPU instead of a CPU widen/compute/narrow pass in between. Falls back to the original CPU-GELU two-round-trip implementation (kept, renamed `__g4ffn_forward_cpu_gelu`) on any failure — no Metal device, forced-CPU mode, or a weight buffer upload failing — same fallback discipline as `gemma4_bf16_linear` itself. Applies equally to the batched (`M>1`, prefill/multi-sequence) and single-request (`M==1`) paths; no special-casing needed since `rows` is just read off the input tensor's shape either way.

**Correctness**: `tests/test_gemma4_ffn.sc` already compared the GPU FFN path against a CPU+BLAS reference (bit-close) and a large-gate overflow regression (checks for NaN) — both still pass unchanged against the new chained implementation, including the overflow-clamp case (confirming the bf16 kernel's clamp works the same as the f32 one's). Re-ran the full existing regression suite (every `test_gemma4_*` synthetic test, the real-checkpoint model test — top-5 logit ids for the "SafeC" prompt still match `[39316, 12977, 1869, 67, 55879]`, this document's original `transformers`-verified reference, exactly — the real-checkpoint batch test with 8 concurrent sequences, and the speculative-decode exhaustive `eosId` sweep) and all pass.

**Measured, real checkpoint** (`tests/test_gemma4_bf16_generate_real.sc`, same prompt/setup as the GEMV section above, same session): 232.9ms/token (GEMV kernel alone, previous section's result) → **178.4ms/token average across 3 warm runs (225.2, 177.8, 178.9ms/token) — another ~23% reduction**, stacking with the GEMV kernel's own ~22% rather than replacing it (the two target different waste: wasted threadgroup compute vs. wasted round trips).

**Combined, all three techniques together** (`tests/test_gemma4_bf16_speculative_real.sc`, rebuilt fresh against the current source): plain greedy decode **174.7ms/token** (down from the original 296.6ms baseline before any of this round's work), speculative decoding on top of that **125.6ms/token effective (1.39x)**, output still exactly bit-identical to plain greedy decode (re-verified, including the exhaustive `eosId` sweep). **296.6ms/token → 125.6ms/token is a 2.36x total single-request latency improvement**, from three independently-measured, independently-verified techniques (GEMV kernel, chained FFN, speculative decoding) rather than one large unverified claim.

## Whole-layer GPU chaining, motivated by measuring MLX's own decode loop — DONE, a real win confirmed by controlled A/B

Asked directly why MLX (48ms/token) and PyTorch-MPS (85ms/token) were still well ahead of this project's GPU path even after the GEMV kernel and FFN chaining above, and whether the Apple Neural Engine was the explanation. It wasn't — verified directly (not assumed): `mlx.core.default_device()` reports `Device(gpu, 0)`, and PyTorch's `mps` device string is Metal-only too. Neither framework touches the ANE. The real answer was found by reading `mlx_lm`'s actual decode loop (`generate.py`): `_step()` builds an entire decode step — embeddings through every layer, RMSNorm, RoPE, attention, FFN, final norm, lm head, softmax, sampling — as ONE lazy MLX graph, materialized via a single `mx.eval()`. That's likely close to **one Metal command buffer per token**, against this project's ~150-180 separate round trips before this round, and still ~70-84 after the GEMV+FFN work (o_proj, FFN[chained], PLE gate, PLE proj = 4 round trips/layer × 35, roughly).

**Why most of gemma4's round trips resisted chaining until now**: RMSNorm, elementwise add, and the PLE's GELU*multiply step were pure CPU functions (`gemma4_bf16_rmsnorm_rows`, `gemma4_bf16_add`, etc. in `gemma4_bf16.sc`) sitting between GPU matmuls, each needing the actual values of the previous matmul's output — a real data dependency, not a code-organization artifact (see the FFN-chaining section above, which first ran into exactly this wall). Closing that gap required those CPU ops to become GPU-resident kernels too.

**What was built**: three new bf16 Metal kernels (`gpu_mps_kernels.metal`) plus chained host dispatchers (`gpu_mps.sc`/`.h`), all following the established `mps_batch_last_output_buffer()` chaining contract:
- `add_kernel_bf16` / `mps_add_bf16_chained` — elementwise add where BOTH operands can be still-pending GPU buffers (unlike the pre-existing `mps_sub_bf16_chained`, which only supports one pending buffer plus a CPU array) — needed for residual connections (`out = residual + normed`) where `residual` is itself another chained op's output.
- `rmsnorm_kernel_bf16` / `mps_rmsnorm_bf16_chained` — row-wise RMSNorm, GPU-resident: one simdgroup (32 lanes) per row, each lane strides over `cols/32` elements accumulating a partial sum of squares, `simd_sum` reduces (broadcasting the same total to every lane in the simdgroup, per Metal's documented semantics — no separate broadcast step needed, same pattern the GEMV kernel already relies on), then every lane writes its share of the normalized+scaled output. Same formula as `gemma4_bf16_rmsnorm_rows`, GPU float32 math instead of CPU.
- `gelu_mul_kernel_bf16` / `mps_gelu_mul_bf16_chained` — `out = gelu(a) * b` for two SEPARATE buffers (unlike `gated_gelu_kernel_bf16`'s single concatenated `[gate;up]` buffer) — needed for the PLE's `gelu(gateProj(x)) * pleSlice`, where `pleSlice` is an independent per-layer-embedding lookup, not part of the gate matmul's output.

`gemma4_bf16_ffn_forward_chained_body` (`gemma4_ffn.sc`) was split out from `gemma4_bf16_ffn_forward_fused` — the same three chained matmul/gelu dispatches, but without its own `mps_batch_begin()`/`mps_batch_end()`, so a bigger surrounding chain can embed it as one link instead of paying its own round trip. **Real bug caught here during development**: the first version of this split moved the FFN's scratch-buffer `free()` calls inside the extracted body, before the *caller's* `mps_batch_end()` ran — since every `*_chained` op's "output" pointer is only actually written by `mps_batch_end()`'s deferred copy, freeing it earlier is a use-after-free. It corrupted the heap silently on the first (small, synthetic-shape) call and only crashed several mallocs later inside an unrelated function, with a stack trace pointing nowhere near the actual bug — a real instance of the general rule this project has run into before: a `*_chained` function's scratch/output buffers must stay alive until after the batch that will write them completes, full stop. Fixed by moving allocation/free to the caller (`gemma4_ffn.sc`/`gemma4_model_bf16.sc`), matching every other chained caller's existing discipline.

`__g4mbf16_layer_tail_chained` (`gemma4_model_bf16.sc`) chains the entire post-attention layer tail as ONE round trip: o_proj → RMSNorm → add(residual) → RMSNorm → FFN (already 3 sub-dispatches) → RMSNorm → add(residual) → PLE gate matmul → gelu*mul → PLE proj matmul → RMSNorm → add(residual) → scale — 15 GPU dispatches, 1 commit+wait, instead of 4 round trips plus ~10 CPU-only calls. Scoped to `count==1` (single-request decode, this whole round's target) via the caller in `gemma4_bf16_model_forward`'s layer loop only invoking it there, falling back to the original per-op path (o_proj via a plain `gemma4_bf16_linear` call, then the unchanged CPU-mixed tail) on ANY failure — no Metal device, forced-CPU mode, or a weight-buffer upload failing. Attention itself (the softmax/weighted-value loop) stays CPU, unchanged — already established, twice, as not the bottleneck at this benchmark's context lengths; this round targeted round trips and CPU-GPU ping-pong, not attention's own compute. `gemma4_bf16_attn_raw_paged` (the pre-o_proj attention output, already existing for the batched path) replaces `gemma4_bf16_attn_forward_paged` (which did o_proj internally as its own separate round trip) in the decode branch, so o_proj becomes the first link in the new chain instead of a standalone call — both call the identical underlying attention math either way (`gemma4_bf16_attn_forward_paged` was always just `gemma4_bf16_attn_raw_paged` + a separate `gemma4_bf16_linear(oProjW)` call, confirmed by reading its own source).

**Correctness**: every existing `test_gemma4_*` synthetic test passes unchanged. `test_gemma4_bf16_kvcache.sc` (a real one-shot-forward-vs-cached-decode-step comparison, exercising the new chain directly since its decode step is `count==1`) still shows the same small, bf16-appropriate relative error (0.0012) and identical greedy argmax as before. The real-checkpoint model test's top-5 logit ids for "SafeC" still match `[39316, 12977, 1869, 67, 55879]` exactly (prefill path, count>1, unaffected by this change) and `test_gemma4_bf16_generate_real.sc`'s real-checkpoint decode produces the correct, expected continuation (`"...Paris."`, repeating) through the new chained path. The exhaustive speculative-decode `eosId` sweep (0-49, `test_gemma4_bf16_speculative.sc`) still passes completely (speculative bit-identical to plain for every value). One expected, benign observation: the *synthetic* checkpoint's own 30-token free-running greedy sequence diverges from its pre-this-round token sequence starting around token 22 — consistent with `simd_sum`'s tree-order reduction producing tiny bf16 rounding differences from the CPU's sequential accumulation, compounding over many layers and steps until a near-tied argmax flips on a small, effectively-random checkpoint (exactly the "bit-close, not bit-identical" standard already applied to every other GPU-vs-CPU comparison in this document) — the real checkpoint, with better-separated logit distributions, shows no such divergence.

**Measured, controlled A/B** (`tests/test_gemma4_bf16_generate_real.sc`, chain toggled on/off via a one-line guard in the same session, alternated to control for the session's noisier background load at the time — absolute numbers here run higher than the GEMV/FFN sections' own A/Bs for that reason, but the *relative* comparison is what matters and was measured, not assumed):

| pair | chain OFF | chain ON | speedup |
|---|---|---|---|
| 1 | 348.9ms/tok | 256.4ms/tok | 1.36x |
| 2 | 243.5ms/tok | 177.3ms/tok | 1.37x |
| 3 | 250.1ms/tok | 171.2ms/tok | 1.46x |
| 4 | 192.3ms/tok | 165.4ms/tok | 1.16x |
| 5 | 219.6ms/tok | 197.6ms/tok | 1.11x |
| 6 | 281.7ms/tok | 182.4ms/tok | 1.54x |

**ON was faster in all 6 of 6 alternating pairs, averaging ~1.32x (≈24% latency reduction)** — a real, reproducible win despite the absolute-number noise, exactly the discipline this project applies whenever a session's background load makes raw numbers hard to trust on their own.

**Combined, all four techniques together** (`tests/test_gemma4_bf16_speculative_real.sc`, rebuilt fresh): plain greedy decode 179.8ms/token, speculative decoding 126.8ms/token effective (1.42x), output still bit-identical to plain. Against the original 296.6ms/token pre-this-whole-effort baseline: **296.6ms/token → 126.8ms/token, a ~2.34x total single-request latency improvement**, from four independently-measured, independently-verified techniques (GEMV kernel, chained FFN, whole-layer chaining, speculative decoding).

**Not pursued this round**: async cross-step pipelining (MLX's `mx.async_eval` dispatches decode step N+1 before reading back step N's result, overlapping CPU graph-building with GPU execution across steps — a second, independent gap from whole-layer chaining, requiring `gemma4_bf16_generate`'s loop itself to restructure around a non-blocking dispatch/later-wait pattern rather than today's fully synchronous build-dispatch-wait-read-repeat). Flagged as the next concrete candidate, not attempted here given the size of this round's change already.

## Techniques investigated and deliberately not implemented, with reasons

In the spirit of this document's running practice of recording *why not* alongside *why*, four more techniques were evaluated and are not part of this codebase, for reasons specific to each rather than lack of time alone:

- **Apple Neural Engine (ANE) — investigated further, actually attempted via CoreML, and confirmed slower than the existing GPU path.** Earlier rounds documented (citing `std/ml/tensor_auto.h`'s own header comment) that there's no public low-level API to submit an arbitrary kernel to the ANE the way `gpu_mps.sc` submits one to the GPU — the only integration is CoreML, compiling a whole model graph and letting its own scheduler decide ANE vs. GPU vs. CPU. Re-verified directly against this machine's actual SDK headers (not from memory): `MLNeuralEngineComputeDevice` is a device descriptor only (reports core count), and `MLComputeUnitsCPUAndNeuralEngine` is a *hint*, not a guarantee — confirmed true still.

  Given the choice between full integration and a scoped, isolated proof-of-concept (the latter chosen — this is a genuinely different technology stack from every hand-written kernel in this document, and correctness here is only spot-checked, not held to the bit-exact bar the rest of the project uses), three things were actually tried, in a throwaway Python 3.11 venv (`coremltools`/`transformers`/`torch`, not part of the SafeC build):

  1. **Exporting the real gemma4 text model** (`model.model.language_model` + `lm_head`, a fixed-length single-shot prefill, no KV cache) via `torch.jit.trace` + `coremltools.convert`. Failed with `NotImplementedError: PyTorch convert function for op 'diff' not implemented` — `torch.diff`, used deep inside shared HuggingFace `transformers` masking/position-id utilities, not gemma4-specific code, has no CoreML MIL op mapping in coremltools 9.0. Fixing this would mean patching `transformers` internals until the trace happens to succeed, not exporting the real reference model — not pursued.
  2. **A representative proxy instead**: gemma4's actual dominant decode-step matmul shape (`nn.Linear(2560, 2560)`, matching the M==1 GEMV kernel's own shape above) converted and run alone. Definitive per-op device placement via `MLComputePlan` (Apple's own compute-plan API, not inferred from noisy timing) showed CoreML's scheduler kept this single op on **CPU** even with `.cpuAndNeuralEngine` explicitly requested — a single small op isn't worth ANE's own dispatch overhead in CoreML's own estimation.
  3. **A deeper 16-layer stack of the same shape** (`Linear→GELU` × 16, closer to gemma4's real depth) — `MLComputePlan` confirmed ANE placement for every layer this time. Measured: 0.256ms/layer with ANE engaged vs. 0.362ms/layer CPU-only within CoreML (real, ~29% ANE speedup over CoreML's own CPU path) — but **0.256ms/layer is roughly 4x slower than this project's own chained GPU GEMV kernel at 0.066ms/call for the identical shape** (see the GEMV section above). Some of that gap is plausibly CoreML/XPC dispatch overhead outside the actual ANE compute (SafeC's Metal dispatch is in-process; CoreML's prediction path is not) — but it's a real, measured number, not a projection.

  **Conclusion**: ANE genuinely engages for this workload's shape, but only at real graph depth, and even then measures markedly slower end-to-end than the hand-written GPU kernel already in production here. Not integrated into gemma4's serving path — the evidence points the other way, not because the integration was too hard to finish.
- **Linear attention.** Not implemented because it would silently change what's being computed, not just how fast: gemma4-E2B was trained with standard softmax attention, and its weights encode that specific inductive bias. Swapping in a linear-attention approximation (Performer-style kernelized attention, or similar) computes a genuinely different function from the one the checkpoint's weights were fit to — every other technique in this document (batching, the GPU attention kernel, chaining, speculative decoding above) is verified bit-identical or bit-close to the untouched reference specifically *because* preserving the checkpoint's real behavior has been the standing bar throughout. An approximation that couldn't clear that bar isn't a faster version of this benchmark; it's a different, unvalidated model being presented as if it were the same one.
- **Arena/region allocation** (`region`/`arena<R>`, a SafeC compiler-level feature — `new<Region> T`, `arena_reset<Region>()`, see `compiler/examples/arena.sc` and this site's own binarytrees benchmark for real usage). A genuine win for allocation-heavy workloads dominated by millions of small, same-lifetime allocations (binarytrees' own arena variant is ~5.5x faster than plain heap for exactly that shape). gemma4's decode step allocates on the order of 1,000–1,750 `Gemma4Bf16Tensor` buffers per step (roughly 30-50 per layer × 35 layers) — a moderate count of substantial-sized buffers, not the millions-of-tiny-allocations profile arenas help most. More to the point: allocator overhead has never once shown up as a measured bottleneck across every profiling pass in this document — the CPU+BLAS path is bound by matmul FLOPs, the GPU path by dispatch/memory bandwidth, every single time something was actually measured instead of assumed. Retrofitting dozens of allocation sites across five files to thread a region type through every function signature that currently returns a plain-malloc'd `Gemma4Bf16Tensor` is real, invasive, correctness-risky work (region capacity sizing, use-after-reset hazards if a tensor's lifetime crosses a reset boundary) for a bottleneck that isn't there. Deprioritized on that basis, not attempted speculatively.
- **A full Flash-Attention-style online-softmax kernel rewrite** (as opposed to prompt-lookup decoding, which is unrelated to Flash Attention despite both coming from the "speed up serving" literature). The current attention kernels (CPU and GPU alike, see the batching sections above) already avoid materializing a full `[seqLen, kvLen]` score matrix across ALL heads/sequences at once, but they do still allocate one `kvLen`-sized `scores` array per (head, sequence) pair rather than using a single-pass running-max/running-sum online softmax. A genuine online rewrite would remove the hard-coded `G4ATTN_MAX_KV=2048` bound in the GPU kernel (real robustness value for longer contexts than this benchmark exercises) but was not pursued for speed this round: this document's own profiling already established, twice, that attention is *not* the bottleneck at this benchmark's actual context lengths (tens of tokens) — the matmuls are, and they're memory-bandwidth-bound, not attention-score-matrix-bound (see the previous section's reverted hardware-matmul experiment for the same underlying finding from a different angle). Given that, and that this section's speculative decoding delivered a substantially larger, lower-risk win for the same class of "single-request latency" goal, a kernel rewrite whose main benefit is architectural robustness at context lengths this project doesn't currently exercise was judged lower priority this round, not shipped as a smaller/rushed version.
- **Propagating this project's techniques (batching, GPU kernel work, chaining, speculative decoding) to the OTHER, smaller `std::ml` benchmarks** on this docs site (the 2-layer MLP training benchmark, `JiTBlock`, `DiTBlock`, `transformer.sc`, etc.) is a real, separate body of work — each benchmark has its own model shapes, its own bottleneck profile (the 2-layer-MLP benchmark's own findings already note MPS never beat CPU BLAS at any shape tried, a very different regime from gemma4's large-checkpoint decode), and would need the same build-test-measure-document discipline applied throughout this file, not a global find-and-replace. Out of scope for this pass; noted here as a real candidate for a future, dedicated round rather than silently skipped.

## Still TODO before finishing the model (next steps)

Every functional piece is DONE and verified end-to-end against the real
checkpoint (see the status sections above, in build order: safetensors
parser, tokenizer, math primitives, RoPE, attention, PLE, gated FFN, full
35-layer model + tied LM head + softcap, real-checkpoint numerical
validation, KV cache, generation loop, MPS/GPU acceleration, HTTP
streaming server). What's left is purely writeup/polish, not
implementation:

1. ~~Benchmark doc entries~~ — DONE (`benchmarks.md`/`ko/benchmarks.md`,
   now with a real three-way PyTorch/MLX speed comparison, not just
   SafeC's own numbers — see the project-reorganization section above).
2. ~~Project reorganization~~ — DONE (own `safeguard`-project structure,
   moved out of `std/ml/` — see the warning box at the top of this file
   and the section above).
3. ~~Persistent GPU weight buffers, parallel checkpoint loading, gate/up
   operator fusion, paged KV cache~~ — DONE (see the "Performance
   optimizations" section above for all four, with measured before/after
   numbers).
4. ~~QKV projection fusion, fully GPU-chained gated FFN~~ — DONE (see the
   "Further optimization" section above — including a real Metal-`tanh()`-
   overflow bug this work found and fixed in `std/ml`'s shared GPU
   backend, not just gemma4's own code).
5. ~~Process in BF16, not just store weights as BF16~~ — DONE (see the
   "BF16" section above: a full parallel bf16 model, weights AND
   activations, real bf16 GPU matmuls for the dominant FLOP cost, now
   what `main.sc` actually serves by default — ~4x faster load, ~2x
   faster generation, verified top-5-token-identical to the F32
   reference on the real checkpoint).
6. **Possible future optimizations, none required for correctness**:
   trimming sliding-window cache slots to `slidingWindow` width instead
   of an ever-growing (if now paged, not O(n²)) buffer; top-p/top-k
   sampling on top of the existing temperature sampling; multi-connection
   HTTP serving (currently one connection at a time); understanding why
   BF16's peak resident memory win is smaller than the naive "half the
   bytes" expectation (see the BF16 section's honest caveat on unified-
   memory CPU/GPU weight-buffer double-counting).
7. Fix `safec-docs/stdlib/ml.md`'s stale MPS-segfault warning (see the
   MPS section above) — out of scope for this benchmark's own files,
   noted here so it doesn't get lost.
8. Fix the pre-existing `.sc`-implementation-self-include pattern spanning
   40+ unrelated `std/` files (see the warning box at the top of this
   file) so `safeguard build`/`run`/`test` genuinely work for this and
   every other real project — out of scope for this benchmark, noted here
   so it doesn't get lost either.

## Port to the reference-based / generic std API

The project was ported (API only — same algorithms, same numerics) to the
std that made `Vec<T>`/`HashMap<K,V>`/`StrMap<V>` generic and moved public
std signatures from raw pointers to `&T`/`?&T` references:

- `Vec` → `Vec<T>` everywhere: KV pages are `Vec<Gemma4Bf16KvPage>`,
  `gemma4_tok_encode` returns `Vec<int>`, `SafetensorsFile.entries` is
  `Vec<SafetensorsEntry>`; `get_raw(i)` became `at(i)` (bounds-checked).
- Tokenizer maps: `StrMap<int>` (vocab) and
  `HashMap<unsigned long long, int>` (merge ranks); the MPS weight-buffer
  cache is `HashMap<unsigned short*, void*>`.
- `SafetensorsFile.handle` is `?&File` (check with `.is_null()`), and the
  checkpoint loader / attention worker threads use the typed
  `thread_create(&tid, fn ?&T(?&T), ?&T)`.
- `mps_attn_batch_bf16` no longer has a KV-length cap, so the gather
  step's `maxKvLen > 2048` CPU fallback (see the batched-attention section
  above) is gone; the kernel now only refuses unsupported `headDim`s.
- `build.sh` takes the SafeC checkout from `SAFEC_HOME` (and the compiler
  from `SAFEC`), and lists every std implementation file the binaries
  reach (std `.sc` files no longer self-include each other's
  implementations). `tests/clang_mps_wrapper.sh` now prefers Homebrew
  LLVM's clang (the IR safec emits is too new for Apple's clang). Linking
  on macOS needs safec-org/SafeC#10, which makes std/panic.sc and
  std/log.sc print through std/stderr_compat.h instead of glibc's
  `stderr` symbol.
