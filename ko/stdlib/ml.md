# 머신러닝 (`std/ml/`)

CPU 텐서/자동미분 코어, 폭넓은 어텐션/디퓨전/아키텍처 라이브러리, GPU
백엔드, 그리고 (LangChain/LangGraph/LangSmith에서 영감을 받은) LLM
오케스트레이션 계층.

```c
#include <std/ml/tensor.h>
```

::: warning 범위
이것은 기존 프레임워크를 이식한 것이 아니라, 처음부터 단일 세션으로
작성된 구현이다. `tensor.h`는 1D/2D `f64` 텐서와 아래 모듈들에 필요한
연산을 다루며, 범용 n차원 배열 라이브러리는 아니다. 정확히 무엇이
검증되었고 무엇이 의도적으로 보류되었는지(DPM-Solver-v3의 캘리브레이션에
의존하는 설계, EDM 고유의 VE 방식 샘플러, 위치 인코딩, 패치화)는
저장소의 `std/ml/ROADMAP.md`를 참고한다 — 각각이 왜 조작된 것이 아니라
범위 밖인지 그 이유도 그 파일에 나와 있다.
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

역방향 모드 자동미분을 갖춘 CPU 텐서(1D/2D, `double`) — PyTorch 스타일의
사용감: `requiresGrad` 텐서에 연산을 호출해 암묵적으로 계산 그래프를
만들고, 그다음 `tensor_backward()`를 호출한다.

```c
&Tensor x = tensor_from_1d(values, 3UL, /*requiresGrad=*/1);
&Tensor y = tensor_sum(tensor_mul(x, x));  // y = sum(x^2)
tensor_backward(y);                         // 이제 x.grad는 2x를 담고 있다
```

연산: `tensor_add`/`tensor_sub`/`tensor_mul`/`tensor_scale`/
`tensor_matmul`/`tensor_relu`/`tensor_sum`. 손으로 계산한 그래디언트와,
matmul→relu→sum 체인에 대한 실제 유한 차분(finite-difference) 검사를
통해 검증되었다.

## 활성화 함수 {#activations}

```c
#include <std/ml/activations.h>
```

원소 단위의 `tensor_sigmoid`/`tensor_tanh`/`tensor_silu`/
`tensor_gelu`; `tensor_layernorm_rows`(행 단위 평균 0/분산 1 정규화,
어파인 변환 없음 — AdaLN 방식 컨디셔닝에는 직접 스케일/시프트를 적용할
것, 아래 DiT 참고); `tensor_residual_add`(residual/skip 연결 호출부를
위한, `tensor_add`에 이름을 붙인 별칭).

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

## U-Net {#u-net}

```c
#include <std/ml/unet.h>
```

스킵 연결을 갖춘 고정된 2단계 인코더/디코더 CNN(Ronneberger 외 2015)
— 전형적인 DDPM 스타일 디퓨전 디노이저 백본이다. 출력 형태 검사와,
`unet_forward`의 호출 순서를 수동으로 재현한 것과의 완전한 자기
일관성 검사를 통해 검증되었다.

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
필요로 한다 — `std/ml/ROADMAP.md` 참고).

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

## GPU 백엔드 {#gpu-backends}

```c
#include <std/ml/gpu_mps.h>   // Apple Silicon/macOS — [features] "mps"
#include <std/ml/gpu_cuda.h>  // NVIDIA — [features] "cuda"
#include <std/ml/gpu_rocm.h>  // AMD — [features] "rocm"
```

ML 개요서에서 말하는 "통합 메모리" 부분: MPS의 기본 버퍼 저장 모드는
Apple Silicon에서 진짜 CPU+GPU 공유 메모리다(MLX 자체의 셀링 포인트)
— CUDA/ROCm의 분리된(discrete) 메모리 모델이 필요로 하는 명시적인
호스트↔디바이스 복사가 없다.

::: warning 검증 상태
- **MPS**: `mps_available()`은 실제 Apple Silicon 하드웨어에서 완전히
  검증되었다. `mps_add_f32()`의 파이프라인 — 디바이스/큐 생성, 실제로
  런타임에 컴파일된 Metal Shading Language 커널, 파이프라인 상태,
  버퍼 할당, 인코더 설정 — 은 마지막 단계인
  `dispatchThreadgroups:threadsPerThreadgroup:` 호출을 제외한 모든
  단계에서 검증되었으며, 이 마지막 호출은 현재 세그폴트를 일으킨다
  (연속된 두 개의 구조체 값 전달 Objective-C 메시지 전송 인자와
  관련된 것으로 보이는 코드 생성상의 결함 — 정확하고 이분 탐색으로
  좁혀진 실패 지점은 `gpu_mps.sc`의 헤더 주석 참고).
- **CUDA**/**ROCm**: 실제 드라이버/런타임 API ABI에 맞춰 작성되고
  타입 검사는 되었지만 검증되지는 않았다 — 이것이 만들어진 환경에는
  NVIDIA/AMD GPU가 없었다(`std/gui/gui_win32.sc`/`gui_x11.sc`와 동일한
  상태).
:::

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

다섯 가지 모두 종단 간(end-to-end)으로 검증되었으며, `LlmClient`는
실제 LLM 엔드포인트를 대신하는 실제 로컬 모의(mock) HTTP 서버에 대해
검증되었다(`std/rpc/server_fn.h` 자체의 테스트가 쓰는 것과 같은 기법 —
여기서는 어느 것도 실제로 호스팅된 LLM API를 호출하지 않는다).
