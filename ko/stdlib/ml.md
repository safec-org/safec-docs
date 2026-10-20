# 머신러닝 (`std/ml/`)

CPU 텐서/자동미분 코어, 폭넓은 어텐션/디퓨전/아키텍처 라이브러리, GPU
백엔드, 그리고 (LangChain/LangGraph/LangSmith에서 영감을 받은) LLM
오케스트레이션 계층.

```c
#include <std/ml/tensor.h>
```

::: warning 범위
이것은 기존 프레임워크를 이식한 것이 아니라, 처음부터 단일 세션으로
작성된 구현이다. `tensor.h`는 1D/2D float32 텐서와 아래 모듈들에 필요한
연산을 다루며, 범용 n차원 배열 라이브러리는 아니다. 의도적으로 구현하지
않은 것: DPM-Solver-v3(실제 학습된 모델에서 캘리브레이션한 통계가 필요한
설계), EDM 고유의 VE 방식 샘플러, 위치 인코딩, 패치화.
:::

아래의 모든 함수는 `Tensor`(및 여기 나오는 다른 구조체들)를 리전 없는
참조(`&Tensor`/`const &Tensor` — [메모리와 리전](/ko/reference/memory)의
"참조가 살아남는 경우" 절 참고)로 받고 반환한다: 이 라이브러리 안의
어떤 것도 호출자의 텐서가 힙/스택/정적/아레나 중 무엇에 기반하는지
신경 쓰지 않으므로, 특정 리전에 고정되는 함수는 없다. 원시 포인터(또는
어떤 리전이든의 참조)는 `unsafe` 없이 암묵적으로 이 타입으로 변환된다.

---

## 텐서 {#tensor}

```c
#include <std/ml/tensor.h>
```

역방향 모드 자동미분을 갖춘 CPU 텐서(1D/2D, `float` — PyTorch의 기본값이자 Metal이 지원하는 유일한 정밀도인 float32) — PyTorch 스타일의
사용감: `requiresGrad` 텐서에 연산을 호출해 암묵적으로 계산 그래프를
만들고, 그다음 `tensor_backward()`를 호출한다. fp16/bf16 저장과 혼합 정밀도
학습은 [반정밀도 텐서](#half-precision-tensors)를 참고한다.

```c
&Tensor x = tensor_from_1d(values, 3UL, /*requiresGrad=*/1);
&Tensor y = tensor_sum(tensor_mul(x, x));  // y = sum(x^2)
tensor_backward(y);                         // 이제 x.grad는 2x를 담고 있다
```

연산: `tensor_add`/`tensor_sub`/`tensor_mul`/`tensor_scale`/
`tensor_matmul`/`tensor_relu`/`tensor_sum`. 손으로 계산한 그래디언트와,
matmul→relu→sum 체인에 대한 실제 유한 차분(finite-difference) 검사를
통해 검증되었다.

`tensor_matmul`과 그 역전파는 [`sgemm`](/ko/stdlib/simd#gemm) 위에서
실행된다: 패킹된 SIMD 커널이며, 큰 곱은 [스레드 풀](/ko/stdlib/sync#parallel)에
나뉘어 실행된다. 역전파는 기존 그래디언트에 바로 누적한다. 스레드 수를
제한하려면 `SAFEC_NUM_THREADS`를 설정한다.

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

### 자동미분 연산과 옵티마이저 — `tensor_nn.h` {#autograd-ops-and-optimizer-tensor-nn-h}

```c
#include <std/ml/tensor_nn.h>
```

역방향 패스를 갖춘 활성화 함수(모든 GPU 백엔드가 쓰는 것), 1D 풀링, Adam입니다.

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

## 활성화 함수 {#activations}

```c
#include <std/ml/activations.h>
```

순방향 전용 원소 단위 활성화 함수(자동미분 없음 — 추론 레이어용이며,
자동미분 버전은 위의 `tensor_nn.h`에 있다); `tensor_layernorm_rows`(행 단위 평균 0/분산 1 정규화,
어파인 변환 없음 — AdaLN 방식 컨디셔닝에는 직접 스케일/시프트를 적용할
것, 아래 DiT 참고); `tensor_residual_add`(residual/skip 연결 호출부를
위한, `tensor_add`에 이름을 붙인 별칭).

```c
&Tensor tensor_relu_fwd(const &Tensor a);
&Tensor tensor_sigmoid_fwd(const &Tensor a);
&Tensor tensor_tanh_fwd(const &Tensor a);
&Tensor tensor_silu_fwd(const &Tensor a);
&Tensor tensor_gelu_fwd(const &Tensor a);
&Tensor tensor_layernorm_rows(const &Tensor x, float eps);
&Tensor tensor_residual_add(const &Tensor x, const &Tensor sublayerOut);
```

## 어텐션 {#attention}

```c
#include <std/ml/attention.h>
```

`softmax_rows()`와 `attention_forward()`(단일 헤드 스케일드 닷프로덕트
어텐션)가 `mha_forward()`(표준 멀티헤드 어텐션 — Q/K/V의 열을 헤드로
나누고, 헤드별로 어텐션을 실행한 뒤 결과를 이어붙임)를 구성한다. 이
파일에는 다음도 있다:

- **`gated_attention_forward`/`gated_mha_forward`** — 시그모이드 출력
  게이팅으로, 어텐션 결과에 원소 단위로 곱해진다.
- **`linear_attention_forward`** — `phi(x) = elu(x)+1` 커널 트릭
  재구성(Katharopoulos 외), 전체 O(seqLen·kvLen·headDim²) 점수 행렬
  대신 O((seqLen+kvLen)·headDim²).
- **`flash_attention_forward`** — 타일 방식의, 온라인 소프트맥스
  어텐션(Dao 외): K/V를 블록 단위로 처리하며 [seqLen,kvLen] 전체 점수
  행렬을 결코 구체화하지 않는다. `kvLen`을 고르게 나누지 않는 블록
  크기에 대해서도 `attention_forward`의 출력과 허용 오차 내에서
  일치함이 검증되었다.

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

전체적으로 순방향 전용이다(추론 중심, 역방향 패스 없음). 손으로 계산한
2×2 예제, `mha_forward`와 수동 헤드별 슬라이싱 간의 자기 일관성 검사,
그리고 gated/linear/flash 각 변형에 대한 손으로 계산/교차 검증된
테스트를 통해 검증되었다.

## 고급 어텐션 {#advanced-attention}

```c
#include <std/ml/attention_advanced.h>
```

- **`mla_forward`/`eg_mla_forward`** — Multi-head Latent Attention
  (DeepSeek-V2): K/V를 공유된 저차원(low-rank) 잠재 표현으로 압축한 뒤
  전체 폭으로 다시 업프로젝션하여 KV 캐시 메모리를 절약한다.
  `eg_mla_forward`는 하나의 잠재 표현을 독립적인 그룹별 업프로젝션들이
  공유한다(그룹드 쿼리 어텐션의 KV 공유를 MLA의 압축 위에 얹은 형태).
- **`windowed_attention_forward`** — 1D 시퀀스에 맞게 적용한 Shifted-
  Window(Swin) 어텐션: 고정 크기의 로컬 윈도우로 분할하되, 순환
  시프트(`shift` 파라미터)를 적용해 다음 레이어의 윈도우가 이 레이어의
  경계를 가로지르게 한다.
- **`gated_deltanet_forward`** — Gated DeltaNet(Yang 외 2024): 델타 규칙
  상태 업데이트(상태가 이미 예측한 값과 새 값 사이의 잔차를 씀)와
  스칼라 감쇠 게이트를 순차 스캔으로 계산하는, 선형 순환 어텐션
  대체제.

수동 그룹별/윈도우별 재구성에 대한 자기 일관성 검사와, Gated
DeltaNet에 대한 손으로 계산한 2단계 상태 추적을 통해 검증되었다.

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

`eg_mla_forward`는 그룹별 업프로젝션을 텐서 참조의 [`Slice`](/ko/stdlib/collections#slice)로 받는다 — 길이가 그룹 수인, 경계 검사가 있는 배열이다. 고정 크기 스택이나 정적 배열(`&Tensor groups[4]`)로, 개수가 런타임에만 정해지면 힙이나 아레나 할당으로 뒷받침한다.

## RNN 계열 {#rnn-family}

```c
#include <std/ml/rnn.h>
```

`RNNCell`/`GRUCell`/`LSTMCell`/`XLSTMCell` — 일반 RNN, GRU, LSTM, 그리고
xLSTM의 sLSTM 변형(Beck 외 2024: 로그 도메인 안정화기와 정규화 상태를
갖춘 지수 입력/망각 게이팅으로, LSTM의 시그모이드 게이트를 대체). 행
벡터 관례를 따른다(`x_t`는 `[1, inputSize]`). xLSTM의 mLSTM(행렬 메모리)
변형은 구현되지 않았다 — sLSTM만 지원된다. 각 셀은 실제 순방향 패스에
대한 손으로 계산한 게이트 값으로 검증되었다.

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

각 셀의 가중치 텐서는 공개 필드(`W_ih`, `W_hh`, … — 게이트별 이름)이므로 학습된 가중치를 넣을 수 있다.

## CNN {#cnn}

```c
#include <std/ml/cnn.h>
```

`Conv2D`(스트라이드 + 제로 패딩, 팽창/그룹 컨볼루션 없음),
`maxpool2d_forward`/`avgpool2d_forward`, `upsample2x_nearest`(최근접
이웃 방식의 2배 공간 업샘플링), `concat_channels`(채널 축 연결) — 모두
(2D까지만 지원하는 `Tensor`가 아니라) 채널 우선 `FeatureMap`에 대해
동작한다. 손으로 계산한 3×3 커널 검사와 풀링 윈도우 검사를 통해
검증되었다.

`conv2d_forward`는 im2col + [`sgemm`](/ko/stdlib/simd#gemm)이다. 출력
픽셀 블록마다 수용 영역을 스레드별 열 버퍼에 모아 가중치 행렬과 곱한다.
블록들은 [스레드 풀](/ko/stdlib/sync#parallel)에서 실행되므로 전체 im2col
행렬은 만들어지지 않는다. 1×1 컨볼루션은 모으는 단계를 건너뛴다.
`conv2d_relu_forward`는 같은 패스에서 ReLU를 적용한다. 풀링, 업샘플링,
연결은 맵이 충분히 크면 채널 단위로 풀에 나뉘어 실행된다.

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
struct FeatureMap conv2d_relu_forward(const &Conv2D layer, const &FeatureMap input);  // relu(conv), 한 패스
void              conv2d_free(&Conv2D layer);

struct FeatureMap maxpool2d_forward(const &FeatureMap input, unsigned long kernel, unsigned long stride);
struct FeatureMap avgpool2d_forward(const &FeatureMap input, unsigned long kernel, unsigned long stride);
struct FeatureMap upsample2x_nearest(const &FeatureMap input);
struct FeatureMap concat_channels(const &FeatureMap a, const &FeatureMap b);
```

## U-Net {#u-net}

```c
#include <std/ml/unet.h>
```

스킵 연결을 갖춘 고정된 2단계 인코더/디코더 CNN(Ronneberger 외 2015)
— 전형적인 DDPM 스타일 디퓨전 디노이저 백본이다. 출력 형태 검사와,
`unet_forward`의 호출 순서를 수동으로 재현한 것과의 완전한 자기
일관성 검사를 통해 검증되었다.

```c
struct UNet unet_new(unsigned long inChannels, unsigned long c1, unsigned long c2,
                     unsigned long outChannels);
struct FeatureMap unet_forward(&UNet net, const &FeatureMap input);
void unet_free(&UNet net);
```

## 디퓨전 {#diffusion}

```c
#include <std/ml/diffusion.h>
```

- **`edm_karras_sigmas`** — Karras 외("Elucidating the Design
  Space...")의 노이즈 스케줄.
- **`ddpm_linear_schedule`/`ddpm_sampler_step`** — DDPM 조상(ancestral)
  샘플링.
- **`ddim_sampler_step`** — 결정론적 DDIM(eta=0).
- **`dpm_solver_1_step`/`dpm_solver_2_step`** — DPM-Solver(Lu 외 2022),
  eps 예측 지수 적분기, 1차 및 중점(midpoint) 2차 변형
  (`dpm_solver_2_step`은 호출자가 제공한 모델 콜백을 받아, 로그
  SNR 중점에서 두 번째로 평가한다).
- **`dpm_solver_pp_1_step`/`dpm_solver_pp_2_step`** — DPM-Solver++
  (Lu 외 2022b), 데이터(x0) 예측 재구성. 1차는 `ddim_sampler_step`과
  증명 가능하게 동일하다 — 유도뿐 아니라 수치적으로도 검증되었다.

DPM-Solver-v3는 구현되지 않았다(이 방식의 핵심 아이디어는 실제로
학습된 모델을 실제 데이터에 대해 캘리브레이션한 "경험적 모델 통계"를
필요로 한다).

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

`userData`는 모델 자신의 상태(예: 가중치)이며, 그 타입은 컴파일 타임에 콜백과 대조해 검사된다.

## 트랜스포머 (DiT / JiT) {#transformer-dit-jit}

```c
#include <std/ml/transformer.h>
```

- **`DiTBlock`** — Diffusion Transformer(Peebles & Xie 2023), adaLN
  방식: 컨디셔닝 벡터가 블록당 6개의 변조 벡터를 생성한다(어텐션과
  FFN 서브레이어 각각에 대한 시프트/스케일/게이트). 게이트=0일 때
  정확히 항등이 되는지 검사(게이트가 0이면 블록의 출력이 입력과
  정확히 같아야 함)와 완전한 자기 일관성 검사를 통해 검증되었다.
- **`JiTBlock`/`jit_forward`** — 컨디셔닝을 (DiT의 레이어별 적응적
  변조와 달리) 토큰 임베딩에 처음 한 번만 더하는, 평범한 pre-LN
  트랜스포머다 — 이 구현이 둘 사이에 두는 아키텍처적 구분이다.

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

## GPU 백엔드 {#gpu-backends}

```c
#include <std/ml/gpu_mps.h>   // Apple Silicon/macOS — [features] "mps"
#include <std/ml/gpu_cuda.h>  // NVIDIA — [features] "cuda"
#include <std/ml/gpu_rocm.h>  // AMD — [features] "rocm"
#include <std/ml/gpu_spirv.h> // Vulkan/SPIR-V (incomplete, see Backend status)
#include <std/ml/gpu_webgpu.h>// WebGPU
```

ML 개요서에서 말하는 "통합 메모리" 부분: MPS의 기본 버퍼 저장 모드는
Apple Silicon에서 진짜 CPU+GPU 공유 메모리다(MLX 자체의 셀링 포인트)
— CUDA/ROCm의 분리된(discrete) 메모리 모델이 필요로 하는 명시적인
호스트↔디바이스 복사가 없다.

::: warning 검증 상태
- **MPS**: 실제 Apple Silicon 하드웨어에서 종단 간 검증되었다 — 올바른
  출력을 내는 실제 GPU 디스패치(`mps_add_f32`, 그리고 대표 사례로
  `mps_sub_f32`/`mps_mul_f32`; 다른 원소 단위 연산은 같은 코드 경로를
  공유한다). 예전에 `dispatchThreadgroups:threadsPerThreadgroup:`에서
  나던 세그폴트는 함수 포인터 캐스트를 통해 큰 구조체를 값으로 넘길 때의
  컴파일러 버그였으며, 코드 생성기에서 수정되었다.
- **ROCm**: AMD Instinct MI100(gfx908, ROCm 7.1)에서 검증되었다 —
  `tests/stdlib/rocm/run.sh`가 모든 연산, GEMM 전치 조합 네 가지,
  그래프, 호스트 배열 API, fp16/bf16 버퍼(`float16.h`와 비트 단위로 같은
  반올림, 16비트 GEMM, SGD)를 CPU 결과와 비교하며(검사 87개), [MIOpen과
  hipFFT 라이브러리](#rocm-miopen-hipfft)도 검사한다(검사 43개).
  [ML 벤치마크](/ko/benchmarks#machine-learning-small-mlp-training-and-inference)는
  CPU 및 MPS 경로와 같은 손실까지 학습한다.
- **CUDA**: NVIDIA H200(sm_90, CUDA 13.0, 드라이버 580)에서 검증했다 —
  `tests/stdlib/cuda/run.sh`가 모든 연산, 네 가지 GEMM 전치 조합(ReLU 융합
  유무 모두), 그래프, 호스트 배열 API와 영구 버퍼 API, fp16/bf16 커널,
  [cuDNN·cuFFT 라이브러리](#cuda-cudnn-cufft)를 CPU 결과와 비교하고(110개 검사),
  [ML 벤치마크](/ko/benchmarks#machine-learning-small-mlp-training-and-inference)는
  CPU 및 ROCm 경로와 같은 손실까지 학습한다.
:::

### ROCm / HIP (AMD) {#rocm-hip-amd}

```c
#include <std/ml/gpu_rocm.h>   // 링크: -lamdhip64 -lhiprtc -lrocblas
```

데이터는 `RocmBuf`에 있다 — 디바이스 주소와 원소 개수로 기술되는 GPU
버퍼이며 모든 연산이 개수를 검사한다. 연산은 하나의 HIP 스트림에 순서대로
쌓이므로 학습 스텝 전체가 GPU에서 돌고, 요청한 값(`rocm_read`)만 돌아온다.
행렬곱은 rocBLAS로 간다. 나머지는 이 라이브러리 자체의 HIP 커널이다 —
원소별 연산, ReLU와 그 기울기, 제자리 SGD, 합 리덕션, 그리고 융합된 MSE
기울기(`dy = 2(y − t)`와 손실 합을 한 번에) — 처음 쓸 때 hiprtc로 현재 GPU에
맞게 컴파일되므로 한 빌드가 어떤 ROCm GPU에서든 동작한다. GPU가 여러 개인
머신에서는 연산 유닛이 가장 많은 것을 고른다.

| 그룹 | 함수 |
|---|---|
| 디바이스 | `rocm_available`, `rocm_device_name`, `rocm_sync` |
| 버퍼 | `rocm_alloc`, `rocm_upload`, `rocm_write`, `rocm_read`, `rocm_free` |
| 행렬곱 | `rocm_gemm(a, ta, b, tb, c, m, n, k)` — `c = op(a) op(b)`, 어느 피연산자든 전치 가능 |
| 원소별 | `rocm_add`/`sub`/`mul`/`div`/`pow`, `rocm_scale`, `rocm_relu`, `rocm_exp`/`log`/`sqrt`, `rocm_relu_grad` |
| 학습 | `rocm_sgd(w, g, lr)`(제자리), `rocm_mse_grad(y, t, dy, loss)`, `rocm_sum` |
| 배치 | `rocm_copy(src, srcOff, dst, dstOff, n)`, `rocm_concat(a, na, c, nc, out, batch)`, `rocm_upsample2x_nearest`, `rocm_upsample2x_concat`(U-Net 디코더 한 단계를 한 번에), `rocm_bias_channels(y, bias, batch, channels, plane, relu)`(채널별 바이어스와 선택적 ReLU, 제자리) |
| fp16 / bf16 | `RocmHalfBuf`(`ROCM_FP16` / `ROCM_BF16`): `rocm_half_alloc`, `rocm_half_upload`, `rocm_half_write` / `rocm_half_read`(원시 비트), `rocm_half_free`; `rocm_to_half` / `rocm_from_half`; `rocm_half_gemm`(16비트 결과)과 `rocm_half_gemm_f32`(fp32 결과); `rocm_half_sgd(w, g, lr)` |
| 그래프 | `rocm_capture_begin` / `rocm_capture_end` → `RocmGraph`, `rocm_graph_launch`, `rocm_graph_free` |
| 상호 운용 | `rocm_stream_handle()`: 모든 연산이 쌓이는 HIP 스트림. MIOpen과 hipFFT도 이 스트림을 씀 |
| 호스트 배열 | `rocm_add_f32` … `rocm_matmul_f32_blas` — 업로드, 계산, 읽기를 한 번에 |

2층 MLP의 SGD 한 스텝, 전부 GPU에서:

```c
// 순전파: Z1 = X W1, H = relu(Z1), Y = H W2
std::rocm_gemm(X, 0, W1, 0, Z1, B, H_DIM, I);
std::rocm_relu(Z1, H);
std::rocm_gemm(H, 0, W2, 0, Y, B, O, H_DIM);
// loss = sum((Y - T)^2)에 대한 역전파
std::rocm_mse_grad(Y, T, dY, loss);
std::rocm_gemm(H, 1, dY, 0, dW2, H_DIM, O, B);     // dW2 = H^T dY
std::rocm_gemm(dY, 0, W2, 1, dH, B, H_DIM, O);     // dH  = dY W2^T
std::rocm_relu_grad(Z1, dH, dZ1);
std::rocm_gemm(X, 1, dZ1, 0, dW1, I, H_DIM, B);    // dW1 = X^T dZ1
std::rocm_sgd(W2, dW2, lr);
std::rocm_sgd(W1, dW1, lr);
```

이런 연산열을 `rocm_capture_begin()`과 `rocm_capture_end()` 사이에서 한 번
기록하고 `rocm_graph_launch()`로 재생하면 스텝 전체를 한 번에 실행한다.
호스트가 매 스텝 GPU를 기다리지 않을 때 효과가 있다. `tensor_rocm.h`는
호스트 배열 연산을 자동 미분 `Tensor` 함수(`tensor_add_rocm`,
`tensor_matmul_rocm_blas`, …)로 감싸며 CPU 대체 경로가 있다.

rocBLAS와 직접 작성한 커널의 비교: 이 라이브러리의 타일 SGEMM
(`rocm_matmul_f32`)은 MI100에서 4096³ 기준 약 9.5 TFLOPS, rocBLAS는 행렬
코어를 써서 33.7 TFLOPS에 이르고, 학습 루프가 쓰는 작은 크기에서도 rocBLAS가
더 빠르다(≈5 µs, 실행 시작 하한에 가까움). 그래서 행렬곱은 rocBLAS를 쓰고,
사용자 커널은 rocBLAS가 하지 않는 일을 맡는다.

### CUDA (NVIDIA) {#cuda}

```c
#include <std/ml/gpu_cuda.h>   // 링크: -lcuda -lcublas -lcublasLt -lnvrtc
```

ROCm 백엔드와 같은 구조이고, 함수 이름도 `cuda_` 접두사로 같다. `CudaBuf`는
원소 개수를 함께 가진 GPU 버퍼이고, 연산은 하나의 CUDA 스트림에 순서대로
쌓이며, `cuda_read`로 요청한 값만 호스트로 돌아온다. 행렬곱은 cuBLAS에서
완전한 fp32로 돈다(TF32 없음). 나머지는 이 라이브러리의 CUDA 커널이 맡는데,
처음 쓸 때 NVRTC가 현재 GPU용 cubin으로 컴파일한다(H100/H200에서는
`-arch=sm_90`). 그래서 빌드 하나로 설치된 CUDA가 아는 모든 GPU에서 돈다.
백엔드는 CUDA 런타임과 PyTorch가 쓰는 장치의 primary context를 쓰므로, 같은
프로세스의 다른 CUDA 코드와 버퍼를 함께 쓸 수 있다.

ROCm 연산에 더해, 두 가지 융합으로 학습 한 스텝의 커널 실행이 10번에서
7번으로 준다:

- `cuda_gemm_relu` — `relu(op(a) op(b))`를 ReLU epilogue가 붙은 cuBLASLt
  행렬곱 한 번으로 계산한다. 이러면 ReLU 기울기를 활성화 이전 값 대신 활성화
  값으로 판정할 수 있어서(`z > 0`인 곳과 `h > 0`인 곳이 정확히 같다) `Z1`을
  저장하지 않는다.
- `cuda_sgd2` — 텐서 두 개짜리 모델의 가중치 갱신 두 번을 커널 하나로 한다.

그 밖의 CUDA 전용 선택: 64 KB 이하 읽기(손실 값, 출력 하나)는 pinned 스테이징
버퍼를 거쳐 DMA 한 번으로 끝난다. 블록 하나짜리 리덕션은 결과를 바로 저장하므로
`cuda_mse_grad`/`cuda_sum`에 0으로 채우는 memset이 필요 없다. cuBLAS에는
32 MB 고정 작업 공간을 주어 그래프 캡처가 가능하다.

| 분류 | 함수 |
|---|---|
| 장치 | `cuda_available`, `cuda_device_name`, `cuda_sync` |
| 버퍼 | `cuda_alloc`, `cuda_upload`, `cuda_write`, `cuda_read`, `cuda_free` |
| 행렬곱 | `cuda_gemm(a, ta, b, tb, c, m, n, k)`, `cuda_gemm_relu`(같은 인자, ReLU 융합) |
| 원소별 | `cuda_add`/`sub`/`mul`/`div`/`pow`, `cuda_scale`, `cuda_relu`, `cuda_exp`/`log`/`sqrt`, `cuda_relu_grad` |
| 학습 | `cuda_sgd`, `cuda_sgd2`, `cuda_mse_grad(y, t, dy, loss)`, `cuda_sum` |
| 그래프 | `cuda_capture_begin` / `cuda_capture_end` → `CudaGraph`, `cuda_graph_launch`, `cuda_graph_free` |
| 호스트 배열 | `cuda_add_f32` … `cuda_matmul_f32_blas` — 업로드, 계산, 읽기를 한 번에 |
| 영구 버퍼(`&void` 핸들) | `cuda_upload_persistent`, `cuda_matmul_f32_persistent`, `cuda_sgd_update_f32`, fp16/bf16용 `cuda_*_f16_*`/`cuda_*_bf16_*`(2바이트 저장, fp32 연산) |

위의 MLP 한 스텝을 융합하면:

```c
std::cuda_gemm_relu(X, 0, W1, 0, H, B, H_DIM, I);    // H = relu(X W1)
std::cuda_gemm(H, 0, W2, 0, Y, B, O, H_DIM);
std::cuda_mse_grad(Y, T, dY, loss);
std::cuda_gemm(H, 1, dY, 0, dW2, H_DIM, O, B);
std::cuda_gemm(dY, 0, W2, 1, dH, B, H_DIM, O);
std::cuda_relu_grad(H, dH, dZ1);                      // Z1 > 0인 곳에서 H > 0
std::cuda_gemm(X, 1, dZ1, 0, dW1, I, H_DIM, B);
std::cuda_sgd2(W2, dW2, W1, dW1, lr);
```

이 백엔드가 검증되기 전에는 호스트 배열 함수를 부를 때마다 CUDA 컨텍스트와
cuBLAS 핸들을 만들고 없앴다. `tensor_matmul_cuda_blas`를 거친 작은 MLP
벤치마크는 H200에서 학습에 83초, 추론에 816초가 걸렸다. 지금은 호스트 배열
함수도 백엔드의 컨텍스트, 스트림, 커널을 같이 쓴다.

### CUDA의 합성곱(cuDNN)과 FFT(cuFFT) {#cuda-cudnn-cufft}

```c
#include <std/ml/gpu_cudnn.h>   // 링크: -lcudnn + gpu_cuda 라이브러리
#include <std/dsp/fft_cuda.h>   // 링크: -lcufft + gpu_cuda 라이브러리
```

`gpu_cuda.h` 위에 얹은 라이브러리 두 개다. 같은 스트림에 작업을 넣으므로
`CudaBuf` 연산과 순서가 지켜지고 `CudaGraph`에 기록할 수도 있다. std
아카이브에서 각각 별도 오브젝트 파일이라, 쓸 때만 링크하면 된다.

**`gpu_cudnn.h`**는 [CNN](#cnn)과 [U-Net](#u-net)을 GPU에서 돌린다. 텐서는
NCHW 순서의 `CudaBuf`로, `FeatureMap`의 CHW 레이아웃 앞에 배치 축을 붙인 것이다.
모양마다 첫 합성곱에서 cuDNN이 알고리즘들의 시간을 재게 하고
(`cudnnFindConvolutionForwardAlgorithm`), 가장 빠른 것을 전용 워크스페이스와
함께 캐시한다. 이 첫 호출은 그래프 캡처 밖에서 해야 한다. 합성곱은
`cuda_set_tf32(1)`을 켜지 않는 한 순수 fp32로 돈다. cuBLAS GEMM과 같은
스위치다.

| 분류 | 함수 |
|---|---|
| 레이어 | `cuda_conv2d_upload(&Conv2D)` → `CudaConv2D`, `cuda_conv2d_free`, `cuda_conv_out_dim` |
| 큐 연산 | `cuda_conv2d(&layer, x, y, batch, h, w)`, `cuda_conv2d_relu` (합성곱 + 바이어스 + ReLU를 cuDNN 호출 한 번으로), `cuda_maxpool2d`, `cuda_avgpool2d`, `cuda_concat_channels`; `gpu_cuda.h`에 `cuda_upsample2x_nearest`, `cuda_copy` |
| U-Net | `cuda_unet_new(&UNet, batch, h, w)` → `CudaUNet` (가중치와 모든 활성값을 미리 할당), `cuda_unet_forward(&g, x, y)`, `cuda_unet_free` |
| 호스트 배열 | `conv2d_forward_cuda`, `unet_forward_cuda`: `conv2d_forward`/`unet_forward`와 같은 시그니처. 호출마다 가중치를 올리고, 쓸 수 있는 GPU가 없으면 CPU 구현으로 돌아간다 |

**`fft_cuda.h`**는 [`fft.h`](/ko/stdlib/dsp#fft-fast-fourier-transform-radix-2-cooley-tukey)의
GPU판이다. `fft.h`와 달리 2의 거듭제곱이 아닌 길이도 받는다. `fft.h`처럼
순변환은 정규화하지 않고 역변환은 1/N을 포함한다.

| 분류 | 함수 |
|---|---|
| 호스트 배열, `double` | `cuda_fft`, `cuda_ifft`, `cuda_fft_batch(data, n, batch, inverse)`, `cuda_fft2d`, `cuda_ifft2d` (`imaging.h`의 `fft2d`) |
| 큐 연산, fp32 `CudaBuf` | `cuda_fft_c2c(in, out, n, batch, inverse)` (re/im 교차 배치), `cuda_fft_r2c` (실수 n개 → 복소수 n/2+1개), `cuda_fft_c2r` (cuFFT처럼 입력을 덮어씀) |

`tests/stdlib/cuda`는 두 라이브러리를 CPU 코드와 비교한다: stride 1과 2,
배치를 쓴 `conv2d_forward`, 풀링·업샘플·연결, U-Net 전체, `fft`/`ifft`,
2의 거듭제곱이 아닌 길이에 대한 `dft`, 배치 변환과 2D 변환, fp32 장치 변환.
그래프로 합성곱을 재실행하는 경우와 TF32를 켠 경우도 검사한다.

H200에서 큐 연산은 배치 합성곱(이미지 32장: fp32에서 0.164 ms, PyTorch-CUDA
0.257 ms)과 U-Net(배치 1: 0.175 ms, PyTorch 0.206 ms, 그래프 하나로는
0.164 ms)에서 PyTorch-CUDA보다 빠르다. 이미지 한 장짜리 합성곱 하나는
PyTorch가 더 빠르다(0.022 ms 대 0.034 ms). 장치 FFT는 PyTorch를 거친 cuFFT와
같은 속도다. [벤치마크](/ko/benchmarks#h200-conv-fft)를 보라.

### ROCm의 합성곱(MIOpen)과 FFT(hipFFT) {#rocm-miopen-hipfft}

```c
#include <std/ml/gpu_miopen.h>   // 링크: -lMIOpen + gpu_rocm 라이브러리
#include <std/dsp/fft_rocm.h>    // 링크: -lhipfft + gpu_rocm 라이브러리
```

`gpu_cudnn.h`와 `fft_cuda.h`의 AMD 대응입니다: `cuda_` 대신 `rocm_`이 붙은 같은
함수들이 `RocmBuf` 위에서 동작하며, `gpu_rocm`의 스트림에 쌓이므로 그 연산들과
순서가 맞고 `RocmGraph`로 기록할 수 있습니다.

**`gpu_miopen.h`**는 [CNN](#cnn)과 [U-Net](#u-net)을 MIOpen으로 실행합니다.
형태마다 첫 합성곱은 그 호출의 버퍼로 MIOpen의 알고리즘들을 시간 측정하게 하고
(`miopenFindConvolutionForwardAlgorithm`), 가장 빠른 것을 자체 작업 공간과 함께
캐시합니다. 그 첫 호출은 그래프 캡처 밖에서 하세요. MIOpen에는 임의 형태의
합성곱 + 바이어스 + ReLU 융합이 없으므로, 바이어스 덧셈과 ReLU는 합성곱 뒤에
HIP 커널 하나로 실행됩니다(`rocm_bias_channels`).

| 그룹 | 함수 |
|---|---|
| 계층 | `rocm_conv2d_upload(&Conv2D)` → `RocmConv2D`, `rocm_conv2d_free`, `rocm_conv_out_dim` |
| 큐 연산 | `rocm_conv2d(&layer, x, y, batch, h, w)`, `rocm_conv2d_relu`, `rocm_maxpool2d`, `rocm_avgpool2d`, `rocm_concat_channels`(배치 전체를 커널 하나로); `gpu_rocm.h`의 `rocm_upsample2x_nearest`, `rocm_upsample2x_concat`, `rocm_copy` |
| U-Net | `rocm_unet_new(&UNet, batch, h, w)` → `RocmUNet`, `rocm_unet_forward(&g, x, y)`(디코더 단계마다 연결 버퍼로 바로 업샘플), `rocm_unet_free` |
| 호스트 배열 | `conv2d_forward_rocm`, `unet_forward_rocm`. GPU를 쓸 수 없으면 CPU로 대체 |

**`fft_rocm.h`**는 hipFFT(내부적으로 rocFFT)로 임의 길이의 변환을 실행합니다.
순방향 변환은 정규화하지 않고 역방향 변환은 1/N을 포함합니다.

| 그룹 | 함수 |
|---|---|
| 호스트 배열, `double` | `rocm_fft`, `rocm_ifft`, `rocm_fft_batch(data, n, batch, inverse)`, `rocm_fft2d`, `rocm_ifft2d` |
| 큐, fp32 `RocmBuf` | `rocm_fft_c2c(in, out, n, batch, inverse)`, `rocm_fft_r2c`, `rocm_fft_c2r`(입력을 덮어쓸 수 있음) |

`tests/stdlib/rocm/rocm_dnn_fft_check.sc`는 CUDA 모음과 같은 검사를 CPU 코드와
비교해 실행합니다(MI100에서 검사 43개): 스트라이드 1과 2, 배치, ReLU, 그래프
재생 합성곱; 풀링, 업샘플, 연결; U-Net 전체; 모든 FFT 형태.

MI100에서 64채널 합성곱 하나는 0.027 ms(PyTorch-ROCm 0.063 ms), 배치 1 U-Net은 0.227 ms(0.315 ms)이며, 배치 16–32에서는 2% 이내입니다. 장치 상주 FFT는 PyTorch를 거칠 때보다 1.4–1.7배 빠릅니다. [벤치마크](/ko/benchmarks#mi100-conv-fft)를 참고하세요.

### 호스트 배열 API (MPS, CUDA, SPIR-V, WebGPU) {#host-array-api-mps-cuda-spir-v-webgpu}

각 백엔드는 일반 `float` 배열에 대해 같은 계열의 함수를 `mps_`, `cuda_`, `spirv_`, `webgpu_` 접두어로 제공합니다. 각각 업로드, 실행, 복사를 한 번에 하며, 성공하면 1, 실패하면(장치 없음, 컴파일 오류) 0을 반환하고 `out`은 건드리지 않습니다. ROCm의 대응 함수(`rocm_*_f32`)는 위의 표에 있습니다.

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

호스트 배열은 첫 원소에 대한 참조로 넘기며, 아래의 장치 버퍼는 메모리가 GPU 드라이버에 속하므로 불투명한 `&void` 핸들입니다.

**영구 버퍼**는 가중치를 호출 사이에 장치에 남겨 두어 학습 루프가 다시 업로드하지 않게 합니다:

```c
?&void <p>_upload_persistent(const &float data, unsigned long bytes);   // null on failure
void   <p>_release_persistent(&void buf);                               // MPS: (&void buf, unsigned long bytes)
int   <p>_persistent_available();                                      // cuda, spirv, webgpu
int    <p>_matmul_f32_persistent(&void bufA, &void bufB, &float out,
                                unsigned long M, unsigned long K, unsigned long N); // mps, cuda, webgpu
int    cuda_sgd_update_f32(&void devW, &void devGrad, float lr, unsigned long n);    // in place
int    webgpu_sgd_update_f32(&void bufW, &void bufGrad, float lr, unsigned long n);
```

**반정밀도**(MPS, CUDA, WebGPU): fp16/bf16으로 업로드해 네이티브로 계산하고, 원시 16비트 값을 읽어 옵니다(아래 `float16.h`로 변환). ROCm은 16비트 데이터를 대신 `RocmHalfBuf`에 담습니다. 위의 ROCm 표를 참고하세요.

```c
?&void mps_upload_f16_persistent(const &float data, unsigned long n);    // also _bf16; cuda_ and webgpu_ (f16) too
int    mps_matmul_f16_persistent(&void bufA, &void bufB, &unsigned short out,
                                unsigned long M, unsigned long K, unsigned long N); // also _bf16; cuda_, webgpu_
int    cuda_sgd_update_f16(&void devW, &void devGrad, float lr, unsigned long n);    // also _bf16; webgpu_ (f16)
int   mps_matmul_f16(const &float a, const &float b, &float out,
                     unsigned long M, unsigned long K, unsigned long N);   // also mps_matmul_bf16
int   mps_relu_f16(const &float a, &float out, unsigned long n);           // also mps_relu_bf16
```

**MPS 배칭.** `mps_batch_begin()`과 `mps_batch_end()` 사이에서는 MPS 연산이 각자 커밋하고 기다리는 대신 하나의 공유 커맨드 버퍼에 인코딩되므로, 순전파 + 손실 + 역전파를 묶은 스텝이 GPU 제출 한 번으로 실행됩니다. `*_chained` 변형은 이전 연산의 출력 버퍼(`mps_batch_last_output_buffer()`)를 호스트 메모리를 거치지 않고 입력으로 받습니다.

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

`_ab` 접미사는 두 입력이 모두 이미 GPU 버퍼라는 뜻입니다. 각 함수의 인자 순서는 `std/ml/gpu_mps.h`를 참고하세요.

### 텐서 래퍼 {#tensor-wrappers}

순전파를 백엔드에서 실행하고 CPU 역전파를 재사용하는 자동미분 `Tensor` 연산입니다. 장치를 쓸 수 없으면 각각 CPU로 대체됩니다.

| 헤더 | 함수 | 장치 |
|--------|-----------|--------|
| `tensor_gpu.h` | `tensor_{add,sub,mul,scale,relu,square,sum,matmul}_gpu` | MPS |
| `tensor_cuda.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_cuda`, `tensor_matmul_cuda_blas` | CUDA |
| `tensor_rocm.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_rocm`, `tensor_matmul_rocm_blas` | ROCm |
| `tensor_spirv.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_spirv` | Vulkan |
| `tensor_webgpu.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_webgpu` | WebGPU |
| `tensor_vdsp.h` | `tensor_{add,sub,mul,sum}_vdsp` | Apple Accelerate vDSP (`-framework Accelerate`) |
| `tensor_blas.h` | `tensor_matmul_blas` | CBLAS (Accelerate / OpenBLAS) |
| `tensor_auto.h` | `tensor_{add,sub,mul,sum,matmul}_auto` | 크기에 따라 core(`sgemm`) / vDSP / BLAS 선택 |

`tensor_gpu.h`는 텐서 수준에서 MPS 배칭과 영구 버퍼도 제어합니다:

```c
void tensor_gpu_batch_begin();
void tensor_gpu_batch_end();
void tensor_gpu_mark_persistent(const &Tensor t);   // keep t's data resident on the GPU
void tensor_gpu_release_all_persistent();
```

`tensor_auto.h`는 의도적으로 CPU 전용입니다: 측정한 모든 크기(2×2×2부터 128×128×128까지의 행렬곱, 그리고 128×512×1024×256 학습 스텝)에서 BLAS가 (`tensor_matmul`이 `sgemm`으로 바뀌기 전에 측정한) core 루프에 진 적이 없고 MPS가 BLAS를 이긴 적이 없으므로 "auto"는 GPU를 고르지 않습니다. 필요하면 `tensor_matmul_on(..., DEVICE_MPS)`로 명시하세요.

### 백엔드 상태 {#backend-status}

| 백엔드 | 원소별 연산 | 행렬곱 (std 커널) | 행렬곱 (벤더 BLAS) | 상태 |
|---|---|---|---|---|
| ROCm / HIP | 실제 동작 (HIP C++, 처음 쓸 때 hiprtc로 컴파일) | 실제 동작 (64×64 LDS 타일 SGEMM) | 실제 동작 (rocBLAS) | MI100에서 **측정함**: `tests/stdlib/rocm`의 검사 29개와 [MLP 벤치마크](/ko/benchmarks#machine-learning-small-mlp-training-and-inference) |
| CUDA | 실제 동작 (CUDA C++, 처음 쓸 때 NVRTC로 컴파일) | 실제 동작 (64×64 공유 메모리 타일 SGEMM) | 실제 동작 (cuBLAS, GEMM + ReLU는 cuBLASLt) | H200에서 **측정**: `tests/stdlib/cuda`의 110개 검사(cuDNN, cuFFT 포함), [MLP 벤치마크](/ko/benchmarks#machine-learning-small-mlp-training-and-inference), [합성곱/FFT 벤치마크](/ko/benchmarks#h200-conv-fft) |
| Vulkan/SPIR-V | 항상 0 반환 | 항상 0 반환 | n/a | SPIR-V는 바이너리 IR; 테스트 환경에 glslc/glslangValidator 없음 |
| WebGPU | 실제 동작 (WGSL, 텍스트 IR) | 실제 동작 (WGSL) | n/a | 측정 안 함 — 테스트 환경에 wgpu-native/Dawn 라이브러리 없음 |

SPIR-V 커널은 바이너리 IR이라 Vulkan 백엔드가 완성되려면 SPIR-V 컴파일러(glslc/glslangValidator)가 필요합니다. WebGPU는 텍스트 IR(WGSL)을 받아 전부 작성되어 있지만, 실행할 wgpu-native/Dawn 라이브러리가 없었습니다.

## fp16 / bf16 {#fp16-bf16}

SafeC 타입 시스템에는 네이티브 16비트 부동소수점이 없습니다(`Type.h`의 `TypeKind`에는 `Float32`/`Float64`뿐 — 표준 라이브러리 변경이 아니라 컴파일러 기능이 필요함). 대신 표준적인 우회 방법을 씁니다: fp16/bf16을 `unsigned short`의 원시 비트로 담고, `float`와의 변환을 명시적으로 올바르게 반올림(최근접 짝수 반올림)합니다 . 그 위에 반정밀도 텐서와 혼합 정밀도 학습(`tensor_half.h`, 아래)이 있고, MPS, CUDA, WebGPU, ROCm에서는 네이티브 `half`/`bfloat` GPU 연산을 합니다([호스트 배열 API](#host-array-api-mps-cuda-spir-v-webgpu) 참고).

| 검사 | 결과 |
|---|---|
| 알려진 비트 패턴 (fp16: 1.0, -1.0, 0.0, -0.0, 2.0, 0.5, 최대 정규수 65504, 오버플로→inf, 최소 비정규수, inf; bf16: 1.0, -1.0, 0.0, 2.0, π) | 모두 정확 |
| fp16 비정규수 멱등성 스윕 (모든 가수 1–1023, `fp16→f32→fp16`) | 1023/1023 정확 |
| fp16 정규 범위 멱등성 스윕 (모든 지수 × 표본 가수, 패턴 330개) | 330/330 정확 |
| 표현 가능한 값의 fp16 왕복 변환 | 정확 |
| bf16 왕복 변환 (구조상 손실 있음 — 가수 7비트) | 상대 오차 ~0.4% 이내 |

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


### 반정밀도 텐서 — `tensor_half.h` {#half-precision-tensors}

```c
#include <std/ml/tensor_half.h>
```

두 계층이 있습니다:

- **`HalfTensor`**는 1D/2D 텐서를 fp16이나 bf16으로 저장합니다 — float32 `Tensor` 메모리의 절반입니다. 연산은 피연산자를 float32로 넓혀 계산한 뒤 결과를 저장 형식으로 반올림하며, 이는 GPU 반정밀도 경로와 같은 산술입니다. `half_matmul`은 텐서 코어처럼 각 내적을 float32로 누적하고 한 번만 반올림합니다. 이 계층에는 자동미분이 없습니다.
- **float32 `Tensor`에 대한 혼합 정밀도, 자동미분 포함.** `tensor_cast`는 순방향에서 값을 fp16/bf16 정밀도로 반올림하고 그래디언트는 그대로 통과시킵니다(straight-through 추정량). 그래서 가중치와 그래디언트는 float32로 유지하면서 그래프를 낮은 정밀도로 계산할 수 있습니다. `LossScaler`는 fp16 학습의 동적 손실 스케일링이며, bf16은 float32와 지수 범위가 같아 필요 없습니다.

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

`HalfTensor`의 `data`는 GPU 반정밀도 함수들(`mps_upload_f16_persistent` 등)이 읽고 쓰는 것과 정확히 같은 배치입니다.

bf16으로 저장하고 계산하기:

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

혼합 정밀도 학습 — fp16 순방향, float32 마스터 가중치, 오버플로 시 스텝 건너뛰기:

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

## 장치 선택 {#device-selection}

모든 백엔드는 연산 이름에 장치를 명시합니다(`tensor_matmul` vs `_blas` vs `_gpu` vs `_cuda` vs `_rocm`) — 정확하지만, 장치를 한 번만 고르고 싶은 호출자에게는 불편합니다. 대신 `tensor_matmul_on(a, b, device)` / `tensor_relu_on(a, device)`가 `Device` 열거형으로 분기합니다 — CPU/CPU+BLAS/MPS에서 비트 단위로 같음을 확인했습니다:

| 장치 | Y[0][0] | Y[0][1] |
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

이를 만들면서 실제 이름 충돌이 드러났습니다: `activations.sc`(순전파 전용 연산)와 `tensor_nn.sc`(모든 GPU 백엔드에 필요한 자동 미분 연산)가 둘 다 `tensor_sigmoid`/`tensor_relu` 등을 정의해서, 어떤 프로그램도 둘을 함께 링크할 수 없었고, 따라서 `activations.sc` 기반 계층은 GPU 백엔드를 쓸 수 없었습니다. `activations.h`의 순전파 전용 함수에 `_fwd` 접미사를 붙이고 `attention.sc`/`transformer.sc`/`rnn.sc`의 호출부를 고쳐 해결했습니다.

`jit_block_forward_on(block, x, device)`은 실제 계층(JiTBlock의 Q/K/V/출력 투영 + FFN)에 장치 선택을 전달합니다 — CPU/CPU+BLAS/MPS에서 기준값과 비트 단위로 같음을 확인했습니다:

| 경로 | y[0] | y[1] | y[2] |
|---|---|---|---|
| `jit_block_forward` (기준) | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU_BLAS)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_MPS)` | -0.479727 | -0.589425 | -0.537808 |

아직 장치 분기가 안 된 것: attention 내부의 QK^T/softmax/·V 행렬곱, `DiTBlock`, `cnn.sc`.


## LLM 오케스트레이션 {#llm-orchestration}

```c
#include <std/ml/llm.h>
```

- **`LlmClient`** — OpenAI chat-completions 와이어 형식(vLLM 자체
  서버를 비롯한 대부분의 로컬/호스팅 LLM 서버가 구현하는 형식)을
  구사한다. `llm_chat()`은 `<host>:<port>/v1/chat/completions`로
  POST한다.
- **`PromptTemplate`** — `prompt_template_render(tmpl, vars)`는 `Value`
  객체를 대상으로 `{name}` 치환을 수행한다.
- **`Chain`** — 고정된 `Value -> Value` 단계 시퀀스.
- **그래프 실행기** — LangGraph 스타일: 이름 붙은 노드들이 공유된
  `Value` 상태를 변환하며, 고정 또는 조건부(라우터 함수) 에지로
  연결되어 `"__end__"`까지 실행된다.
- **`Tracer`** — 이름 붙은 입력/출력/타이밍 이벤트를 담는 인메모리,
  JSON으로 덤프 가능한 로그(LangSmith 스타일).

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

다섯 가지 모두 종단 간(end-to-end)으로 검증되었으며, `LlmClient`는
실제 LLM 엔드포인트를 대신하는 실제 로컬 모의(mock) HTTP 서버에 대해
검증되었다(`std/rpc/server_fn.h` 자체의 테스트가 쓰는 것과 같은 기법 —
여기서는 어느 것도 실제로 호스팅된 LLM API를 호출하지 않는다).
