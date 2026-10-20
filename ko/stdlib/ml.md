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
- **ROCm**: AMD Instinct MI100(gfx908, ROCm 7.1)에서 검증되었다 —
  `tests/stdlib/rocm/run.sh`가 모든 연산, GEMM 전치 조합 네 가지,
  그래프, 호스트 배열 API를 CPU 결과와 비교하며(검사 29개),
  [ML 벤치마크](/ko/benchmarks#machine-learning-small-mlp-training-and-inference)는
  CPU 및 MPS 경로와 같은 손실까지 학습한다.
- **CUDA**: NVIDIA H200(sm_90, CUDA 13.0, 드라이버 580)에서 검증했다 —
  `tests/stdlib/cuda/run.sh`가 모든 연산, 네 가지 GEMM 전치 조합(ReLU 융합
  유무 모두), 그래프, 호스트 배열 API와 원시 포인터 API, fp16/bf16 커널을
  CPU 결과와 비교하고(59개 검사),
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
| 그래프 | `rocm_capture_begin` / `rocm_capture_end` → `RocmGraph`, `rocm_graph_launch`, `rocm_graph_free` |
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
| 원시 포인터 | `cuda_upload_persistent`, `cuda_matmul_f32_persistent`, `cuda_sgd_update_f32`, fp16/bf16용 `cuda_*_f16_*`/`cuda_*_bf16_*`(2바이트 저장, fp32 연산) |

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

### 백엔드 상태 {#backend-status}

| 백엔드 | 원소별 연산 | 행렬곱 (std 커널) | 행렬곱 (벤더 BLAS) | 상태 |
|---|---|---|---|---|
| ROCm / HIP | 실제 동작 (HIP C++, 처음 쓸 때 hiprtc로 컴파일) | 실제 동작 (64×64 LDS 타일 SGEMM) | 실제 동작 (rocBLAS) | MI100에서 **측정함**: `tests/stdlib/rocm`의 검사 29개와 [MLP 벤치마크](/ko/benchmarks#machine-learning-small-mlp-training-and-inference) |
| CUDA | 실제 동작 (CUDA C++, 처음 쓸 때 NVRTC로 컴파일) | 실제 동작 (64×64 공유 메모리 타일 SGEMM) | 실제 동작 (cuBLAS, GEMM + ReLU는 cuBLASLt) | H200에서 **측정**: `tests/stdlib/cuda`의 59개 검사와 [MLP 벤치마크](/ko/benchmarks#machine-learning-small-mlp-training-and-inference) |
| Vulkan/SPIR-V | 항상 0 반환 | 항상 0 반환 | n/a | SPIR-V는 바이너리 IR; 테스트 환경에 glslc/glslangValidator 없음 |
| WebGPU | 실제 동작 (WGSL, 텍스트 IR) | 실제 동작 (WGSL) | n/a | 측정 안 함 — 테스트 환경에 wgpu-native/Dawn 라이브러리 없음 |

SPIR-V 커널은 바이너리 IR이라 Vulkan 백엔드가 완성되려면 SPIR-V 컴파일러(glslc/glslangValidator)가 필요합니다. WebGPU는 텍스트 IR(WGSL)을 받아 전부 작성되어 있지만, 실행할 wgpu-native/Dawn 라이브러리가 없었습니다.

## fp16 / bf16 {#fp16-bf16}

SafeC 타입 시스템에는 네이티브 16비트 부동소수점이 없습니다(`Type.h`의 `TypeKind`에는 `Float32`/`Float64`뿐 — 표준 라이브러리 변경이 아니라 컴파일러 기능이 필요함). 대신 표준적인 우회 방법을 씁니다: fp16/bf16을 `unsigned short`의 원시 비트로 담고, `float`와의 변환을 명시적으로 올바르게 반올림(최근접 짝수 반올림)합니다 — 그리고 MPS 백엔드에서는 저장 공간만 절반으로 줄이는 것이 아니라 실제 네이티브 `half`/`bfloat` GPU 연산을 합니다.

| 검사 | 결과 |
|---|---|
| 알려진 비트 패턴 (fp16: 1.0, -1.0, 0.0, -0.0, 2.0, 0.5, 최대 정규수 65504, 오버플로→inf, 최소 비정규수, inf; bf16: 1.0, -1.0, 0.0, 2.0, π) | 모두 정확 |
| fp16 비정규수 멱등성 스윕 (모든 가수 1–1023, `fp16→f32→fp16`) | 1023/1023 정확 |
| fp16 정규 범위 멱등성 스윕 (모든 지수 × 표본 가수, 패턴 330개) | 330/330 정확 |
| 표현 가능한 값의 fp16 왕복 변환 | 정확 |
| bf16 왕복 변환 (구조상 손실 있음 — 가수 7비트) | 상대 오차 ~0.4% 이내 |


## 장치 선택 {#device-selection}

모든 백엔드는 연산 이름에 장치를 명시합니다(`tensor_matmul` vs `_blas` vs `_gpu` vs `_cuda` vs `_rocm`) — 정확하지만, 장치를 한 번만 고르고 싶은 호출자에게는 불편합니다. 대신 `tensor_matmul_on(a, b, device)` / `tensor_relu_on(a, device)`가 `Device` 열거형으로 분기합니다 — CPU/CPU+BLAS/MPS에서 비트 단위로 같음을 확인했습니다:

| 장치 | Y[0][0] | Y[0][1] |
|---|---|---|
| CPU | 0.900000 | 1.300000 |
| CPU + BLAS | 0.900000 | 1.300000 |
| MPS | 0.900000 | 1.300000 |

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

다섯 가지 모두 종단 간(end-to-end)으로 검증되었으며, `LlmClient`는
실제 LLM 엔드포인트를 대신하는 실제 로컬 모의(mock) HTTP 서버에 대해
검증되었다(`std/rpc/server_fn.h` 자체의 테스트가 쓰는 것과 같은 기법 —
여기서는 어느 것도 실제로 호스팅된 LLM API를 호출하지 않는다).
