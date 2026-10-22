# 머신러닝 (`std/ml/`)

CPU 텐서/자동미분 코어, 폭넓은 어텐션/디퓨전/아키텍처 라이브러리, GPU
백엔드, 그리고 (LangChain/LangGraph/LangSmith에서 영감을 받은) LLM
오케스트레이션 계층.

```c
#include <std/ml/tensor.h>
```

::: warning 범위
이것은 기존 프레임워크를 이식한 것이 아니라, 처음부터 단일 세션으로
작성된 구현입니다. `tensor.h`는 1D/2D float32 텐서와 아래 모듈들에 필요한
연산을 다루며, 범용 n차원 배열 라이브러리는 아닙니다. 의도적으로 구현하지
않은 것: DPM-Solver-v3(실제 학습된 모델에서 캘리브레이션한 통계가 필요한
설계), EDM 고유의 VE 방식 샘플러, 위치 인코딩, 패치화.
:::

아래의 모든 함수는 `FloatTensor`(및 여기 나오는 다른 구조체들)를 리전 없는
참조(`&FloatTensor`/`const &FloatTensor` — [메모리와 리전](/ko/reference/memory)의
"참조가 살아남는 경우" 절 참고)로 받고 반환합니다: 이 라이브러리 안의
어떤 것도 호출자의 텐서가 힙/스택/정적/아레나 중 무엇에 기반하는지
신경 쓰지 않으므로, 특정 리전에 고정되는 함수는 없습니다. 원시 포인터(또는
어떤 리전이든의 참조)는 `unsafe` 없이 암묵적으로 이 타입으로 변환됩니다.

상태를 가진 타입(모델, 레이어, 옵티마이저, 데이터 로더, 에이전트,
디바이스 신경망과 버퍼)은 [메서드](/ko/reference/functions#struct-methods)로
씁니다: `net.forward(x)`, `v.loss(x, y)`, `opt.step()`, `buf.free()`.
생성자는 [정적 메서드](/ko/reference/functions#static-methods)입니다:
`Mlp::new(...)`, `FloatTensor::new_2d(...)`, `Dataset::moons(...)`,
`SparseTensor::from_dense(&d, 0.0f, 1)`, `TrainConfig::default()`. 텐서 하나에 대한
연산은 그 텐서의 메서드입니다 — `x.relu()`, `logits.log_softmax()`,
`loss.backward()`, `t.fill(0.0f)`. 텐서를 제자리에서 바꾸지 않는 한 const
메서드이며, 텐서 두 개에 대한 연산은 함수로 남습니다(`tensor_matmul(a, b)`). 모든 모델이 할 수
있는 일 — 파라미터를 옵티마이저에 추가하고, 세고, 저장하고 불러오고,
복사하고, 내장 손실로 학습하는 일 — 은 모델들이 만족시키는
[트레이트](#modules)를 대상으로 한 제네릭 함수로 한 번만 작성됩니다:
`model_add_params(&net, &opt)`, `model_save_file(&net, path)`,
`module_fit(&net, TRAIN_LOSS_CE, &opt, &data, &cfg)`, `model_to(&net, DEVICE_ROCM)`.

---

## 텐서 {#tensor}

```c
#include <std/ml/tensor.h>
```

역방향 모드 자동미분을 갖춘 CPU 텐서(1D/2D) — PyTorch 스타일의 사용감:
`requiresGrad` 텐서에 연산을 호출해 암묵적으로 계산 그래프를 만들고, 그다음
`FloatTensor::backward()`를 호출합니다.

텐서는 `DenseTensor<E>`입니다: 값은 `E`로 저장하고, 계산은 float32로 하며,
그래디언트는 항상 float32입니다. 원소 타입은 세 가지가 제공됩니다:

| 타입 | `E` | 저장 형식 |
|---|---|---|
| `FloatTensor` | `float` | float32(PyTorch의 기본값이자 Metal이 지원하는 유일한 정밀도) |
| `HalfTensor` | `F16` | IEEE fp16 |
| `BF16Tensor` | `BF16` | bfloat16 |

아래의 핵심 연산(`tensor_add`/`sub`/`mul`/`matmul`, `scale`, `sum`)과 그
자동미분, 역전파는 세 타입 모두에서 동작합니다. `FloatTensor` 연산은 텐서
자체에 float32 커널을 실행하고, 16비트 연산은 피연산자를 float32로 읽어 같은
커널을 실행한 뒤 결과를 한 번 반올림합니다. 역전파는 어느 쪽이든 float32
역전파이며, 한 그래프에 원소 타입이 섞여도 됩니다(`to_float()` /
`from_float()`가 그래프 노드입니다). 이 라이브러리의 나머지 연산(활성화,
손실, 레이어)은 `FloatTensor`를 받습니다. 변환, 일괄 헬퍼, 혼합 정밀도 학습은
[반정밀도 텐서](#half-precision-tensors)를 참고합니다.

```c
&FloatTensor x = FloatTensor::from_1d(values, 3UL, /*requiresGrad=*/1);
&FloatTensor y = tensor_mul(x, x).sum();  // y = sum(x^2)
y.backward();                               // 이제 x.grad는 2x를 담고 있다
```

연산: `tensor_add`/`tensor_sub`/`tensor_mul`/`FloatTensor::scale`/
`tensor_matmul`/`FloatTensor::relu`/`FloatTensor::sum`. 손으로 계산한 그래디언트와,
matmul→relu→sum 체인에 대한 실제 유한 차분(finite-difference) 검사를
통해 검증되었습니다.

`tensor_matmul`과 그 역전파는 [`sgemm`](/ko/stdlib/simd#gemm) 위에서
실행됩니다: CPU의 가장 좋은 [SIMD 수준](/ko/stdlib/simd#cpu-levels)(AVX-512부터
SSE2까지, SVE 또는 Neon)의 패킹된 SIMD 커널이며, 큰 곱은
[스레드 풀](/ko/stdlib/sync#parallel)에 나뉘어 실행됩니다. CPU 벤더 BLAS가
링크되어 있으면 — AMD에서는 AOCL-BLAS, Intel에서는 oneMKL, Apple에서는
Accelerate이며, safeguard의 [`[build] blas`](/ko/advanced/safeguard#linking-external-libraries-and-library-output)가
찾으면 기본으로 링크합니다 — 벤더 커널이 곱셈을 맡습니다. 역전파는 기존
그래디언트에 바로 누적합니다. 스레드 수를 제한하려면 `SAFEC_NUM_THREADS`를
설정합니다.

원소별 연산(`tensor_add`/`sub`/`mul`/`scale`/`fill`, ReLU, 모든 역전파 누적)은
벡터화된 루프이며 65,536개 원소부터 풀에 나뉘어 실행됩니다. 초월 함수
활성화 — sigmoid, tanh, GELU, SiLU, ELU, GLU, SwiGLU — 와 softmax는
[`vmath.h`](/ko/stdlib/simd#vmath)의 벡터화된 `exp`/`tanh`/sigmoid를 쓰므로 더는
원소마다 libm을 호출하지 않습니다.

```c
generic<E>
struct DenseTensor {
    &heap E             data;          // flat row-major, length == size
    &heap unsigned long shape;
    unsigned long       ndim;          // 1 or 2
    unsigned long       size;
    int                 requiresGrad;
    &heap float         grad;          // float32; allocated only when requiresGrad
    // ... autograd bookkeeping (gradFn, parents, visited)

    float at1(unsigned long i) const;                      // as float32
    float at2(unsigned long r, unsigned long c) const;
    void  set1(unsigned long i, float v);                  // rounded to E
    void  set2(unsigned long r, unsigned long c, float v);
    void  free();
    // ... and the methods below
};

typedef struct DenseTensor<float>       FloatTensor;
typedef struct DenseTensor<struct F16>  HalfTensor;
typedef struct DenseTensor<struct BF16> BF16Tensor;

// What generic ML code asks of a tensor; all three satisfy it.
trait Tensor {
    float at1(unsigned long i) const;
    float at2(unsigned long r, unsigned long c) const;
    void  set1(unsigned long i, float v);
    void  set2(unsigned long r, unsigned long c, float v);
    void  fill(float v);
    void  backward();
    void  zero_grad();
    void  free();
}

// Shown for FloatTensor; HalfTensor:: and BF16Tensor:: are the same.
static &FloatTensor FloatTensor::new_1d(unsigned long n, int requiresGrad);
static &FloatTensor FloatTensor::new_2d(unsigned long rows, unsigned long cols, int requiresGrad);
static &FloatTensor FloatTensor::from_1d(const &float values, unsigned long n, int requiresGrad);
static &FloatTensor FloatTensor::from_2d(const &float values, unsigned long rows, unsigned long cols, int requiresGrad);
&FloatTensor FloatTensor::zeros_like() const;
void    FloatTensor::fill(float v);   // in place

// Generic over the element type: both operands are the same DenseTensor<E>.
generic<E> &DenseTensor<E> tensor_add(const &DenseTensor<E> a, const &DenseTensor<E> b);
generic<E> &DenseTensor<E> tensor_sub(const &DenseTensor<E> a, const &DenseTensor<E> b);
generic<E> &DenseTensor<E> tensor_mul(const &DenseTensor<E> a, const &DenseTensor<E> b);   // elementwise
generic<E> &DenseTensor<E> tensor_matmul(const &DenseTensor<E> a, const &DenseTensor<E> b);
&FloatTensor FloatTensor::scale(float k) const;
&FloatTensor FloatTensor::sum() const;

// Between element types, as graph nodes (gradients pass through unchanged).
&FloatTensor FloatTensor::to_float() const;                       // a FloatTensor: itself
static &HalfTensor HalfTensor::from_float(const &FloatTensor x);  // rounded

void FloatTensor::backward();    // t: a scalar (a loss); accumulates every ancestor's grad
void FloatTensor::zero_grad();

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

### 텐서 아레나 {#tensor-arena}

모든 연산은 출력을 새로 할당하므로, 학습 루프는 단계마다 연산마다 `malloc`(그리고 새 메모리라면 페이지 폴트)을 합니다. 작은 MLP에서는 그 비용이 계산보다 컸습니다. `TensorArena`는 한 단계의 임시 텐서를 위한 청크 단위 범프 할당자(64바이트 정렬)입니다. `TensorArena::begin`과 `tensor_arena_end` 사이에 그 스레드에서 만든 모든 텐서(데이터, shape, 기울기)는 아레나에서 나옵니다. `TensorArena::reset`은 그것들을 한꺼번에 버리고, 이미 페이지가 할당된 메모리는 다음 단계를 위해 남겨 둡니다.

```c
static struct TensorArena TensorArena::new(unsigned long chunkBytes);   // 0 = 16 MiB 청크
void TensorArena::begin();                                       // 이 스레드의 새 텐서는 a에서 할당
void tensor_arena_end();                                         // 다시 힙으로
void TensorArena::reset();                                       // 안의 텐서를 모두 버리고 메모리는 유지
void TensorArena::free();
unsigned long TensorArena::used() const;   // 마지막 reset 이후 할당한 바이트

struct TensorArena step = TensorArena::new(0UL);
while (s < steps) {
    step.begin();
    // ... 순전파, FloatTensor::backward, 옵티마이저 갱신 ...
    tensor_arena_end();
    step.reset();   // 이 단계의 텐서는 사라짐
    s = s + 1;
}
step.free();
```

매개변수처럼 아레나 밖에서 만든 텐서는 힙에 남고, 그 기울기도 마찬가지라서 reset 뒤에도 살아 있습니다. 아레나가 활성인 동안 만든 [`FeatureMap`](#cnn)도 `UNet::forward` 안의 모든 중간 결과까지 포함해 아레나에서 할당하며, `FeatureMap::free`는 그것들을 reset에 맡깁니다. 각 텐서는 자신의 `owner`를 기록합니다. 아레나 텐서에 대한 `FloatTensor::free()`는 아무 일도 하지 않으며, 아레나가 reset된 뒤에는 그 텐서를 쓰면 안 됩니다.

### 자동미분 연산과 옵티마이저 — `tensor_nn.h` {#autograd-ops-and-optimizer-tensor-nn-h}

```c
#include <std/ml/tensor_nn.h>
```

역방향 패스를 갖춘 활성화 함수(모든 GPU 백엔드가 쓰는 것), 1D 풀링, Adam입니다.

```c
&FloatTensor FloatTensor::relu() const;
&FloatTensor FloatTensor::sigmoid() const;
&FloatTensor FloatTensor::tanh() const;
&FloatTensor FloatTensor::softmax() const;
&FloatTensor FloatTensor::elu(float alpha) const;
&FloatTensor FloatTensor::gelu() const;
&FloatTensor FloatTensor::silu() const;
&FloatTensor FloatTensor::glu() const;      // split last axis into [a1, a2]: a1 * sigmoid(a2)
&FloatTensor FloatTensor::swiglu() const;   // silu(a1) * a2 — output has half the columns
&FloatTensor FloatTensor::maxpool1d(unsigned long kernel, unsigned long stride) const;
&FloatTensor FloatTensor::avgpool1d(unsigned long kernel, unsigned long stride) const;

struct AdamState {
    &heap float m; &heap float v;
    unsigned long size;
    float lr; float beta1; float beta2; float eps;
    unsigned long t;
};
static struct AdamState AdamState::new(unsigned long size, float lr, float beta1, float beta2, float eps);
static struct AdamState AdamState::new_default(unsigned long size, float lr);   // beta1 0.9, beta2 0.999, eps 1e-8 (paper defaults)
void AdamState::step(&FloatTensor param);                               // uses param.grad
void AdamState::free();
```

## 활성화 함수 {#activations}

```c
#include <std/ml/activations.h>
```

순방향 전용 원소 단위 활성화 함수(자동미분 없음 — 추론 레이어용이며,
자동미분 버전은 위의 `tensor_nn.h`에 있습니다); `FloatTensor::layernorm_rows`(행 단위 평균 0/분산 1 정규화,
어파인 변환 없음 — AdaLN 방식 컨디셔닝에는 직접 스케일/시프트를 적용할
것, 아래 DiT 참고); `tensor_residual_add`(residual/skip 연결 호출부를
위한, `tensor_add`에 이름을 붙인 별칭).

```c
&FloatTensor FloatTensor::relu_fwd() const;
&FloatTensor FloatTensor::sigmoid_fwd() const;
&FloatTensor FloatTensor::tanh_fwd() const;
&FloatTensor FloatTensor::silu_fwd() const;
&FloatTensor FloatTensor::gelu_fwd() const;
&FloatTensor FloatTensor::layernorm_rows(float eps) const;
&FloatTensor tensor_residual_add(const &FloatTensor x, const &FloatTensor sublayerOut);
```

## 어텐션 {#attention}

```c
#include <std/ml/attention.h>
```

`softmax_rows()`와 `attention_forward()`(단일 헤드 스케일드 닷프로덕트
어텐션)가 `mha_forward()`(표준 멀티헤드 어텐션 — Q/K/V의 열을 헤드로
나누고, 헤드별로 어텐션을 실행한 뒤 결과를 이어붙임)를 구성합니다. 이
파일에는 다음도 있습니다:

- **`gated_attention_forward`/`gated_mha_forward`** — 시그모이드 출력
  게이팅으로, 어텐션 결과에 원소 단위로 곱해집니다.
- **`linear_attention_forward`** — `phi(x) = elu(x)+1` 커널 트릭
  재구성(Katharopoulos 외), 전체 O(seqLen·kvLen·headDim) 점수 행렬
  대신 O((seqLen+kvLen)·headDim²).
- **`flash_attention_forward`** — 타일 방식의, 온라인 소프트맥스
  어텐션(Dao 외): K/V를 블록 단위로 처리하며 [seqLen,kvLen] 전체 점수
  행렬을 결코 구체화하지 않습니다. `kvLen`을 고르게 나누지 않는 블록
  크기에 대해서도 `attention_forward`의 출력과 허용 오차 내에서
  일치함이 검증되었습니다.

모두 `tensor_matmul`처럼 역방향 간선을 기록하므로 어텐션 레이어를
[학습](#training)할 수 있고, 피연산자가 있는 곳([디바이스](#devices))에서
실행됩니다. Q/K/V는 `[seqLen, dim]` 텐서입니다. 배치 차원이 없고(시퀀스마다
한 번 호출) 위치 인코딩도 들어 있지 않습니다.

```c
&FloatTensor FloatTensor::softmax_rows() const;
&FloatTensor FloatTensor::softmax_rows_mask(int causal, unsigned long window) const;
&FloatTensor FloatTensor::transpose() const;
&FloatTensor FloatTensor::slice_cols(unsigned long startCol, unsigned long numCols) const;
void    tensor_set_cols(&FloatTensor dst, unsigned long startCol, const &FloatTensor src);   // 제자리 쓰기, 기울기 없음

&FloatTensor attention_forward(const &FloatTensor Q, const &FloatTensor K, const &FloatTensor V);
&FloatTensor mha_forward(const &FloatTensor Q, const &FloatTensor K, const &FloatTensor V, unsigned long numHeads);
&FloatTensor attention_forward_mask(const &FloatTensor Q, const &FloatTensor K, const &FloatTensor V,
                                    int causal, unsigned long window);
&FloatTensor mha_forward_mask(const &FloatTensor Q, const &FloatTensor K, const &FloatTensor V,
                              unsigned long numHeads, int causal, unsigned long window);
&FloatTensor gated_attention_forward(const &FloatTensor Q, const &FloatTensor K, const &FloatTensor V, const &FloatTensor gateLogits);
&FloatTensor gated_mha_forward(const &FloatTensor Q, const &FloatTensor K, const &FloatTensor V,
                          unsigned long numHeads, const &FloatTensor gateLogits);
&FloatTensor linear_attention_forward(const &FloatTensor Q, const &FloatTensor K, const &FloatTensor V);
&FloatTensor flash_attention_forward(const &FloatTensor Q, const &FloatTensor K, const &FloatTensor V, unsigned long kvBlockSize);
int attention_set_fused(int on);   // GPU 멀티헤드 연산: 1 융합 커널(기본), 0 조합 연산; 이전 값을 반환
```

**마스크.** 쿼리 행 `i`의 위치는 `pos = i + (kvLen - seqLen)`입니다. 마지막
`seqLen`개의 키가 쿼리 자신의 토큰이고, 그 앞은 KV 캐시입니다. `causal`은
`j > pos`인 키를 가리고, 슬라이딩 `window` w > 0은 `j <= pos - w`인 키를
가려서 각 쿼리는 자신의 마지막 w개 위치를 봅니다. 보이는 키가 없는 쿼리는
0을 얻습니다. 아래 GPU 추론 커널([ROCm](#rocm-attention),
[CUDA](#cuda-attention), [Metal](#mps-flash-linear-attention))과 같은 마스크입니다.

**실행 방식.**

| | CPU에서 | GPU에서 |
|---|---|---|
| `mha_forward[_mask]`, `attention_forward[_mask]` | 헤드마다 `transpose`, `tensor_matmul`, `scale`, `softmax_rows_mask`, `tensor_matmul`; 헤드는 `tensor_concat_cols`로 합침 | 모든 헤드를 융합 실행 한 번으로(아래); `attention_set_fused(0)`이거나 헤드 폭이 128을 넘으면 디바이스에서 같은 조합 연산(전치 커널, 마스크 소프트맥스 커널) |
| `flash_attention_forward` | 타일 루프; 입력이 기울기를 요구하면 호스트 버퍼 위의 융합 연산 | 융합 연산 |
| `linear_attention_forward` | 루프; 입력이 기울기를 요구하면 조합 연산(`elu`, `transpose`, `tensor_matmul`, `sum_rows`, ...) | 조합 연산 |
| 게이트 형태 | 위의 어텐션에 `sigmoid(gateLogits)`를 곱함 | 같음 |

융합 연산은 `device_kernels.cu`에 있는 FlashAttention-2(Dao 2023)의
타일링이며 ROCm과 CUDA가 공유합니다. 256개 스레드의 블록 하나가 한 헤드의
쿼리 16행 타일을 맡아 키를 16개씩 공유 메모리로 가져오며 온라인
소프트맥스로 처리하므로, `[seqLen, kvLen]` 점수 행렬을 만들지 않습니다.
causal이나 윈도우 마스크가 전부 가리는 타일은 건너뜁니다. 각 행의
log-sum-exp를 보관하며, 역방향은 그것으로 점수를 다시 계산하는 실행 두
번(쿼리 타일별 dQ, 이어서 키 타일별 dK와 dV)입니다. Metal과, CPU의
`flash_attention_forward`에서는 같은 산술을 행 하나씩 실행합니다
(`device_ref.sc`). 16비트 활성화([정밀도](#device-precision))에서는 커널이
fp16 / bf16을 읽고 쓰며 fp32로 계산합니다.

MI100에서 causal 4헤드의 순방향 + 역방향:

| seqLen | headDim 16 | headDim 64 | headDim 128 |
|---|---|---|---|
| 32 | 0.06 ms (조합 0.52) | 0.12 ms (0.51) | 0.21 ms (0.51) |
| 512 | 0.48 ms (13.0) | 1.41 ms (13.4) | 2.86 ms (13.8) |
| 2048 | 4.0 ms (49.9) | 12.9 ms (51.5) | 23.5 ms (52.7) |

[`gpu_rocm.h`](#rocm-attention), [`gpu_cuda_attention.h`](#cuda-attention),
[`gpu_mps.h`](#mps-attention)의 추론 커널(그룹 쿼리 K/V, bf16, 행렬 코어)은
자체 `[heads][seqLen][headDim]` 버퍼에서 순방향만 할 때는 여전히 더 빠른
선택입니다. 이 커널들은 log-sum-exp를 보관하지 않으며 역방향이 없습니다.

**`MultiHeadAttention`** 은 자체 프로젝션을 가진 셀프 어텐션 레이어로,
`mha_forward_mask(X Wq, X Wk, X Wv, numHeads, causal, window) Wo`를
계산합니다. 가중치는 기울기를 요구하며, [`Module`](#modules)입니다.
디코더라면 `causal`을 설정합니다.

```c
struct MultiHeadAttention { &FloatTensor Wq; &FloatTensor Wk; &FloatTensor Wv; &FloatTensor Wo;
                            unsigned long numHeads; int causal; unsigned long window; };
static struct MultiHeadAttention MultiHeadAttention::new(unsigned long dModel, unsigned long numHeads, &Rng rng);
&FloatTensor MultiHeadAttention::forward(const &FloatTensor X) const;   // [seqLen, dModel] -> [seqLen, dModel]
void    MultiHeadAttention::free();
```

`trainer_fit`으로 GPU에서 학습하는 작은 디코더 언어 모델입니다(배치 하나가
시퀀스 하나: 데이터셋의 행은 연속된 토큰이고, `x`는 토큰, `y`는 다음
토큰이며, 순서대로 순회합니다):

```c
struct TinyLm {
    &FloatTensor E;      // [vocab, dim] 토큰 임베딩
    &FloatTensor P;      // [seq, dim] 학습되는 위치
    &FloatTensor Wout;   // [dim, vocab]
    struct LayerNorm ln1; struct LayerNorm ln2;
    struct MultiHeadAttention mha;   // mha.causal = 1
    struct Mlp ffn;
};

static &FloatTensor lm_loss(&TinyLm m, const &FloatTensor x, const &FloatTensor y) {
    &FloatTensor h = tensor_add(tensor_gather_rows(m.E, x), m.P);
    &FloatTensor h1 = tensor_add(h, m.mha.forward(m.ln1.forward(h)));
    &FloatTensor h2 = tensor_add(h1, m.ffn.forward(m.ln2.forward(h1)));
    return tensor_cross_entropy_loss(tensor_matmul(h2, m.Wout), y);
}

// ... 모든 파라미터를 opt에 추가한 뒤:
struct TrainConfig cfg = TrainConfig::default();
cfg.batchSize = seq; cfg.shuffle = 0; cfg.device = DEVICE_AUTO;
struct TrainReport rep = trainer_fit(&lm, lm_loss, &opt, &tokens, &cfg);
```

## 고급 어텐션 {#advanced-attention}

```c
#include <std/ml/attention_advanced.h>
```

- **`Mla`/`EgMla`** — Multi-head Latent Attention (DeepSeek-V2): K/V를
  공유된 저차원(low-rank) 잠재 표현으로 압축한 뒤 전체 폭으로 다시
  업프로젝션하여 KV 캐시 메모리를 절약합니다. `EgMla`는 하나의 잠재 표현을
  독립적인 그룹별 업프로젝션들이 공유합니다(그룹드 쿼리 어텐션의 KV 공유를
  MLA의 압축 위에 얹은 형태이며, 그룹은 `tensor_concat_cols`로 합칩니다).
  둘 다 기울기를 요구하는 가중치를 가진 레이어이며 [`Module`](#modules)이므로,
  [`MultiHeadAttention`](#attention)처럼 GPU에서도 학습됩니다.
- **`windowed_attention_forward`** — 1D 시퀀스에 맞게 적용한 Shifted-
  Window(Swin) 어텐션: 고정 크기의 로컬 윈도우로 분할하되, 순환
  시프트(`shift` 파라미터)를 적용해 다음 레이어의 윈도우가 이 레이어의
  경계를 가로지르게 합니다.
- **`GatedDeltaNet::forward`** — Gated DeltaNet(Yang 외 2024): 델타 규칙
  상태 업데이트(상태가 이미 예측한 값과 새 값 사이의 잔차를 씀)와
  스칼라 감쇠 게이트를 순차 스캔으로 계산하는, 선형 순환 어텐션
  대체제.

수동 그룹별/윈도우별 재구성에 대한 자기 일관성 검사와, Gated
DeltaNet에 대한 손으로 계산한 2단계 상태 추적을 통해 검증되었습니다.
`windowed_attention_forward`와 `GatedDeltaNet`은 순방향 전용 호스트
루프입니다(학습되는 로컬 윈도우에는 `mha_forward_mask`의 `window`를 씁니다).

```c
struct Mla { &FloatTensor Wq; &FloatTensor Wdkv; &FloatTensor Wuk; &FloatTensor Wuv; unsigned long numHeads; };
static struct Mla Mla::new(unsigned long dModel, unsigned long dLatent, unsigned long numHeads, &Rng rng);
&FloatTensor Mla::forward(const &FloatTensor X) const;           // [seqLen, dModel] -> [seqLen, dModel]

struct EgMla { &FloatTensor Wq; &FloatTensor Wdkv; struct Vec<&FloatTensor> Wuk; struct Vec<&FloatTensor> Wuv;  // 그룹마다 하나
               unsigned long headsPerGroup; };
static struct EgMla EgMla::new(unsigned long dModel, unsigned long dLatent, unsigned long numGroups, unsigned long headsPerGroup, &Rng rng);
&FloatTensor EgMla::forward(const &FloatTensor X) const;
&FloatTensor windowed_attention_forward(const &FloatTensor Q, const &FloatTensor K, const &FloatTensor V,
                                   unsigned long windowSize, unsigned long shift);

struct GatedDeltaNet { &FloatTensor Walpha; &FloatTensor Wbeta; unsigned long keyDim; unsigned long valueDim; };
static struct GatedDeltaNet GatedDeltaNet::new(unsigned long dModel, unsigned long keyDim, unsigned long valueDim);
&FloatTensor GatedDeltaNet::forward(const &FloatTensor X, const &FloatTensor Q, const &FloatTensor K, const &FloatTensor V) const;
void    GatedDeltaNet::free();
```

가중치는 공개 필드입니다. 사전 학습된 가중치를 쓰려면 필드에 대입합니다(`EgMla`의 그룹별 업프로젝션은 길이가 그룹 수인 `Vec`입니다). `free()`가 가중치를 해제합니다.

## RNN 계열 {#rnn-family}

```c
#include <std/ml/rnn.h>
```

`RNNCell`/`GRUCell`/`LSTMCell`/`XLSTMCell` — 일반 RNN, GRU, LSTM, 그리고
xLSTM의 sLSTM 변형(Beck 외 2024: 로그 도메인 안정화기와 정규화 상태를
갖춘 지수 입력/망각 게이팅으로, LSTM의 시그모이드 게이트를 대체). 행
벡터 관례를 따릅니다(`x_t`는 `[1, inputSize]`). xLSTM의 mLSTM(행렬 메모리)
변형은 구현되지 않았습니다 — sLSTM만 지원됩니다. 각 셀은 실제 순방향 패스에
대한 손으로 계산한 게이트 값으로 검증되었습니다.

```c
static struct RNNCell  RNNCell::new(unsigned long inputSize, unsigned long hiddenSize);
&FloatTensor         RNNCell::forward(const &FloatTensor x_t, const &FloatTensor h_prev) const;
void            RNNCell::free();

static struct GRUCell  GRUCell::new(unsigned long inputSize, unsigned long hiddenSize);
&FloatTensor         GRUCell::forward(const &FloatTensor x_t, const &FloatTensor h_prev) const;
void            GRUCell::free();

static struct LSTMCell LSTMCell::new(unsigned long inputSize, unsigned long hiddenSize);
// Returns h_t; the new cell state is written through c_out.
&FloatTensor         LSTMCell::forward(const &FloatTensor x_t, const &FloatTensor h_prev, const &FloatTensor c_prev, &&FloatTensor c_out) const;
void            LSTMCell::free();

struct XLSTMState { &FloatTensor h; &FloatTensor c; &FloatTensor n; &FloatTensor m; };   // hidden, cell, normalizer, stabilizer
static struct XLSTMCell  XLSTMCell::new(unsigned long inputSize, unsigned long hiddenSize);
struct XLSTMState XLSTMCell::forward(const &FloatTensor x_t, const &XLSTMState prev) const;
void              XLSTMCell::free();
void              XLSTMState::free();
```

각 셀의 가중치 텐서는 공개 필드(`W_ih`, `W_hh`, … — 게이트별 이름)이므로 학습된 가중치를 넣을 수 있습니다.

## 상태 공간 모델 (Mamba) {#ssm}

```c
#include <std/ml/ssm.h>
```

상태 공간 모델은 어텐션 대신 채널마다의 선형 점화식으로 시퀀스를 섞습니다.
D개 채널은 각각 N개 값으로 된 은닉 상태를 가지며, 토큰마다 이를 갱신하고
읽습니다.

`h_t = exp(Δ_t·A)·h_{t−1} + Δ_t·B_t·x_t`, `y_t = C_t·h_t + D·x_t`

시퀀스 하나의 비용은 O(T·D·N)입니다. 토큰 하나를 디코딩하는 비용은 문맥
길이와 관계없이 고정 크기 상태로 O(D·N)이므로 KV 캐시가 없습니다. Mamba(Gu &
Dao 2023, "S6")는 스텝 Δ와 B·C 프로젝션을 입력의 함수로 만듭니다. 그래서
모델이 토큰마다 무엇을 기억하고 무엇을 잊을지 고를 수 있습니다.

`ssm_selective_scan`은 스캔만 수행합니다. `Mamba`는 Mamba-1 믹서 블록
하나입니다(앞뒤의 RMSNorm과 잔차 덧셈은 포함하지 않습니다).

1. `(x, z) = X·inProj`.
2. x에 인과적 depthwise conv1d(커널 `dConv`)와 SiLU를 적용합니다.
3. `(dt, B, C) = x·xProj`.
4. `Δ = softplus(dt·dtProj + dtBias)`.
5. `A = −exp(Alog)`로 스캔합니다.
6. `silu(z)`로 게이트합니다.
7. `outProj`.

가중치는 다른 `std::ml`과 같이 `[in, out]`입니다. PyTorch `nn.Linear`를
불러오려면 가중치를 전치합니다. Conv1d 가중치 `[dInner, 1, dConv]`는
`[dInner, dConv]`로 불러옵니다. `Mamba::new`는 논문의 초기화를 따릅니다:
`Alog[d][n] = log(n+1)`, `D = 1`, 그리고 `softplus(dtBias)`가
[0.001, 0.1]에 걸치도록 고른 `dtBias`.

컨볼루션과 스캔은 [스레드 풀](/ko/stdlib/sync#parallel)에서 채널을 병렬로
처리합니다. 프로젝션은 `tensor_matmul`을 거칩니다. `Mamba::step`은 토큰 하나를
디코딩하고 `MambaState`(컨볼루션의 마지막 입력 `dConv − 1`개와 SSM
상태)를 진행시킵니다. 토큰을 하나씩 `Mamba::step`에 넣으면 시퀀스 전체에 대한
`Mamba::forward`와 같은 행이 나오며, 테스트가 이를 확인합니다. 같은 가중치를
HF transformers의 `MambaMixer`에 복사했을 때 출력이 약 0.05 크기에서 절대
오차 2e-8 이내로 일치합니다. 순전파 전용이며 autograd는 없습니다.

```c
// Selective scan alone: x, delta [T, D]; A [D, N]; B, C [T, N]; Dskip [D] -> y [T, D]
&FloatTensor ssm_selective_scan(const &FloatTensor x, const &FloatTensor delta, const &FloatTensor A,
                           const &FloatTensor B, const &FloatTensor C, const &FloatTensor Dskip);

struct Mamba {
    &FloatTensor inProj;   // [dModel, 2 * dInner]
    &FloatTensor convW;    // [dInner, dConv]
    &FloatTensor convB;    // [dInner]
    &FloatTensor xProj;    // [dInner, dtRank + 2 * dState]
    &FloatTensor dtProj;   // [dtRank, dInner]
    &FloatTensor dtBias;   // [dInner]
    &FloatTensor Alog;     // [dInner, dState]; A = -exp(Alog)
    &FloatTensor Dskip;    // [dInner]
    &FloatTensor outProj;  // [dInner, dModel]
    unsigned long dModel; unsigned long dInner; unsigned long dState; unsigned long dConv; unsigned long dtRank;
};
static struct Mamba Mamba::new(unsigned long dModel, unsigned long dState, unsigned long dConv, unsigned long expand);
&FloatTensor      Mamba::forward(const &FloatTensor X) const;   // [T, dModel] -> [T, dModel]
void         Mamba::free();

struct MambaState { &FloatTensor conv; &FloatTensor h; };   // [dInner, dConv-1], [dInner, dState]
static struct MambaState MambaState::new(const &Mamba layer);
&FloatTensor           Mamba::step(&MambaState state, const &FloatTensor x) const;   // [1, dModel]
void              MambaState::reset();
void              MambaState::free();

struct Mamba m = Mamba::new(768UL, 16UL, 4UL, 2UL);
&FloatTensor y = m.forward(prompt);   // prefill
struct MambaState st = MambaState::new(&m);
// ... 또는 고정 크기 상태로 토큰 하나씩 디코딩
&FloatTensor yt = m.step(&st, token);
```

## CNN {#cnn}

```c
#include <std/ml/cnn.h>
```

이 레이어들은 순방향 전용입니다. 합성곱 네트워크를 학습하려면 같은 연산을
자동미분 텐서로 제공하는 [`convnet.h`](#convnet)를 씁니다.

`Conv2D`(스트라이드 + 제로 패딩, 팽창/그룹 컨볼루션 없음),
`FeatureMap::maxpool2d`/`FeatureMap::avgpool2d`, `FeatureMap::upsample2x_nearest`(최근접
이웃 방식의 2배 공간 업샘플링), `concat_channels`(채널 축 연결) — 모두
(2D까지만 지원하는 `FloatTensor`가 아니라) 채널 우선 `FeatureMap`에 대해
동작합니다. 손으로 계산한 3×3 커널 검사와 풀링 윈도우 검사를 통해
검증되었습니다.

3×3, stride 1 레이어(입출력 채널이 8개 이상)는 Winograd F(2×2, 3×3)
(Lavin & Gray, arXiv:1509.09308)를 씁니다. 출력 2×2 블록 하나를 4×4 입력
타일에서 채널 쌍마다 곱셈 36번 대신 16번으로 구합니다. 변환한 타일과 필터로
작업이 채널에 대한 GEMM 16개가 됩니다. 입력 변환, GEMM, 출력 변환은 각각
스레드 풀에서 실행되며, 입력·출력 변환은 벡터 하나에 타일 여덟 개씩
처리합니다. 결과는 직접 합과 상대 오차 약 1e-6 이내로 일치합니다. 그 밖의
레이어는 im2col을 씁니다.

`Conv2D::forward`는 im2col + [`sgemm`](/ko/stdlib/simd#gemm)입니다. 출력
픽셀 블록마다 수용 영역을 스레드별 열 버퍼에 모아 가중치 행렬과 곱합니다.
블록들은 [스레드 풀](/ko/stdlib/sync#parallel)에서 실행되므로 전체 im2col
행렬은 만들어지지 않습니다. 1×1 컨볼루션은 모으는 단계를 건너뜁니다.
`Conv2D::relu_forward`는 같은 패스에서 ReLU를 적용합니다. 풀링, 업샘플링,
연결은 맵이 충분히 크면 채널 단위로 풀에 나뉘어 실행됩니다.

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
struct FeatureMap Conv2D::relu_forward(const &FeatureMap input) const;   // relu(conv), 한 패스
void              Conv2D::free();

struct FeatureMap FeatureMap::maxpool2d(unsigned long kernel, unsigned long stride) const;
struct FeatureMap FeatureMap::avgpool2d(unsigned long kernel, unsigned long stride) const;
struct FeatureMap FeatureMap::upsample2x_nearest() const;
struct FeatureMap concat_channels(const &FeatureMap a, const &FeatureMap b);
```

### 패딩·전역 풀링, 잔차 블록 {#cnn-residual}

`_ex` 풀링 함수는 모든 변에 제로 패딩을 더합니다. 출력 크기는
`(dim + 2·padding − kernel)/stride + 1`입니다. 최대 풀링은 PyTorch의
`MaxPool2d`처럼 패딩 위치를 건너뜁니다. 평균 풀링은 `countIncludePad`가
켜져 있으면(PyTorch `AvgPool2d`의 기본값) 패딩 위치를 0으로 쳐서 나눗수에
넣습니다. 꺼져 있으면 평면 안쪽 위치의 수로 나눕니다.
`FeatureMap::global_avgpool2d` / `FeatureMap::global_maxpool2d`는 채널마다 평면 전체를 값 하나로
줄여 채널 × 1 × 1을 만듭니다. ResNet 계열 분류기의 헤드가 이 연산입니다.

`feature_map_add`는 잔차 연결로, `a + b`를 원소별로 계산하며 같은 패스에서
ReLU를 선택적으로 적용합니다. `ResidualBlock`은 ResNet 기본 블록(He et al.
2016)입니다.

`relu(conv2(relu(conv1(x))) + shortcut(x))`

`conv1`은 블록의 stride를 쓰는 3×3입니다. `conv2`는 stride 1인 3×3입니다.
둘 다 padding 1을 씁니다. shortcut은 `x` 자체이거나, stride가 1이 아니거나
채널 수가 바뀌면 그 stride를 쓰는 1×1 컨볼루션입니다. 배치 정규화는 없습니다.
학습된 모델의 배치 정규화는 불러오기 전에 컨볼루션의 가중치와 편향에
접어 넣습니다. 테스트는 패딩 풀링을 직접 계산한 기준값과 비교하고, 블록을
구성 컨볼루션을 차례로 적용한 결과와 비교합니다.

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

// ResNet-18 스템과 첫 스테이지
struct FeatureMap s = stem7x7s2.relu_forward(&image);
struct FeatureMap p = s.maxpool2d_ex(3UL, 2UL, 1UL);
struct FeatureMap r = layer1a.forward(&p);
```

## U-Net {#u-net}

```c
#include <std/ml/unet.h>
```

스킵 연결을 갖춘 고정된 2단계 인코더/디코더 CNN(Ronneberger 외 2015)
— 전형적인 DDPM 스타일 디퓨전 디노이저 백본입니다. 출력 형태 검사와,
`UNet::forward`의 호출 순서를 수동으로 재현한 것과의 완전한 자기
일관성 검사를 통해 검증되었습니다.

```c
static struct UNet UNet::new(unsigned long inChannels, unsigned long c1, unsigned long c2, unsigned long outChannels);
struct FeatureMap UNet::forward(const &FeatureMap input);
void UNet::free();
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
  (`dpm_solver_2_step`은 `DenoiseModel`을 만족시키는 모델을 받아, 로그
  SNR 중점에서 두 번째로 평가합니다).
- **`dpm_solver_pp_1_step`/`dpm_solver_pp_2_step`** — DPM-Solver++
  (Lu 외 2022b), 데이터(x0) 예측 재구성. 1차는 `ddim_sampler_step`과
  증명 가능하게 동일합니다 — 유도뿐 아니라 수치적으로도 검증되었습니다.

DPM-Solver-v3는 구현되지 않았습니다(이 방식의 핵심 아이디어는 실제로
학습된 모델을 실제 데이터에 대해 캘리브레이션한 "경험적 모델 통계"를
필요로 합니다).

```c
void edm_karras_sigmas(unsigned long numSteps, float sigmaMin, float sigmaMax, float rho,
                       &float outSigmas);                         // numSteps values
void ddpm_linear_schedule(unsigned long numSteps, float betaStart, float betaEnd,
                          &float outBeta, &float outAlphaBar);    // numSteps values each

&FloatTensor ddpm_sampler_step(const &FloatTensor x_t, const &FloatTensor epsPred,
                          float beta_t, float alphaBar_t, const &FloatTensor noise);
&FloatTensor ddim_sampler_step(const &FloatTensor x_t, const &FloatTensor epsPred,
                          float alphaBar_t, float alphaBar_prev);
&FloatTensor dpm_solver_1_step(const &FloatTensor x_cur, const &FloatTensor epsPred,
                          float alpha_cur, float sigma_cur, float alpha_next, float sigma_next);
&FloatTensor dpm_solver_pp_1_step(const &FloatTensor x_cur, const &FloatTensor x0Pred,
                             float alpha_cur, float sigma_cur, float alpha_next, float sigma_next);

// 2차 솔버는 중점에서 모델을 다시 호출합니다: 예측한 노이즈(eps)나
// 깨끗한 샘플(x0)을 반환하는 predict 메서드를 가진 구조체면 됩니다.
trait DenoiseModel {
    &FloatTensor predict(const &FloatTensor x, float sigma);
}
generic<M: DenoiseModel>
&FloatTensor dpm_solver_2_step(&M model, const &FloatTensor x_cur, const &FloatTensor epsPred,
                          float alpha_cur, float sigma_cur, float alpha_next, float sigma_next);
generic<M: DenoiseModel>
&FloatTensor dpm_solver_pp_2_step(&M model, const &FloatTensor x_cur, const &FloatTensor x0Pred,
                             float alpha_cur, float sigma_cur, float alpha_next, float sigma_next);
```

모델은 가중치를 가진 직접 만든 구조체입니다. 솔버는 그 `predict` 메서드를 직접 호출하며, 콜백이나 타입이 지워진 컨텍스트는 없습니다.

## 트랜스포머 (DiT / JiT) {#transformer-dit-jit}

```c
#include <std/ml/transformer.h>
```

- **`DiTBlock`** — Diffusion Transformer(Peebles & Xie 2023), adaLN
  방식: 컨디셔닝 벡터가 블록당 6개의 변조 벡터를 생성합니다(어텐션과
  FFN 서브레이어 각각에 대한 시프트/스케일/게이트). 게이트=0일 때
  정확히 항등이 되는지 검사(게이트가 0이면 블록의 출력이 입력과
  정확히 같아야 함)와 완전한 자기 일관성 검사를 통해 검증되었습니다.
- **`JiTBlock`/`JiT`** — 컨디셔닝을 (DiT의 레이어별 적응적
  변조와 달리) 토큰 임베딩에 처음 한 번만 더하는, 평범한 pre-LN
  트랜스포머입니다 — 이 구현이 둘 사이에 두는 아키텍처적 구분입니다.

둘 다 [`mha_forward`](#attention), `layer_norm`, `tensor_mul_row` /
`tensor_add_bias`(adaLN 변조)와 ReLU 피드포워드 블록으로 만들어집니다.
가중치가 기울기를 요구하므로 `trainer_fit`으로 학습되며, `TrainConfig.device`로
GPU에서 학습하면 어텐션은 융합 커널로 실행됩니다:

```c
struct DitModel { struct DiTBlock blk; &FloatTensor c; };   // c: [1, condDim]
static &FloatTensor dit_loss(&DitModel m, const &FloatTensor x, const &FloatTensor y) {
    return tensor_mse_loss(m.blk.forward(x, m.c), y);
}
struct DitModel m;
m.blk = DiTBlock::new(64UL, 256UL, 16UL, 4UL);
// ... m.c를 정한 뒤:
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.001);
model_add_params(&m.blk, &opt);
struct TrainConfig cfg = TrainConfig::default();
cfg.batchSize = 16UL; cfg.shuffle = 0; cfg.device = DEVICE_AUTO;   // 배치 하나: 16토큰 시퀀스 하나
struct TrainReport rep = trainer_fit(&m, dit_loss, &opt, &tokens, &cfg);
```

```c
static struct DiTBlock DiTBlock::new(unsigned long dModel, unsigned long dHidden, unsigned long dModelCond, unsigned long numHeads);
&FloatTensor DiTBlock::forward(const &FloatTensor x, const &FloatTensor c) const;   // c = conditioning
void    DiTBlock::free();

static struct JiTBlock JiTBlock::new(unsigned long dModel, unsigned long dHidden, unsigned long numHeads);
&FloatTensor JiTBlock::forward(const &FloatTensor x) const;
void    JiTBlock::free();
// 모델: 패치 임베딩 + 컨디셔닝, 그다음 블록들.
struct JiT { &FloatTensor WPatchEmbed; struct Vec<struct JiTBlock> blocks; };
static struct JiT JiT::new(unsigned long patchDim, unsigned long dModel, unsigned long dHidden, unsigned long numHeads, unsigned long depth);
&FloatTensor JiT::forward(const &FloatTensor patches, const &FloatTensor cond) const;   // cond: [1, dModel]
void    JiT::free();
```

## 학습: 레이어, 손실, 옵티마이저 {#training}

```c
#include <std/ml/nn.h>        // Rng, 추가 autograd 연산, 손실, Mlp, Optimizer
#include <std/ml/datasets.h>  // Dataset, 생성기, MNIST / CSV 로더, DataLoader
#include <std/ml/trainer.h>   // trainer_fit, trainer_evaluate, trainer_accuracy
```

이 세 헤더는 `tensor.h`의 autograd 위에서 모델을 처음부터 끝까지 학습시킵니다.
배치는 `[batch, features]` 텐서입니다. 클래스 레이블은 클래스 인덱스를 float로
담은 텐서입니다.

- **`Rng`**는 시드를 받는 splitmix64 생성기로, 균등·정규·범위 정수 난수를
  뽑습니다. 이 모듈들의 모든 초기화, 셔플, 샘플링이 이를 거치므로 실행 결과가
  재현됩니다.
- **미분 가능한 연산** (`tensor.h` / `tensor_nn.h`에 더해):
  - 산술: `tensor_add_bias`(행 브로드캐스트), `FloatTensor::add_scalar`.
  - 원소별: `FloatTensor::exp`, `FloatTensor::log`, `FloatTensor::abs`, `FloatTensor::leaky_relu`,
    `FloatTensor::clamp`, `tensor_minimum`.
  - 축소와 형태 변환: `FloatTensor::mean`, `FloatTensor::sum_rows`(`[m,n] → [m,1]`),
    `tensor_concat_cols`, `tensor_gather_rows`(임베딩·코드북 조회로,
    그래디언트를 scatter-add로 되돌립니다).
  - `FloatTensor::log_softmax`.
  - 그래디언트를 끊는 `FloatTensor::detach`.
- **손실** (각각 원소 평균인 값 하나를 반환합니다):
  - `tensor_mse_loss`.
  - `tensor_huber_loss`.
  - `tensor_bce_logits_loss`: 로짓에서 바로 계산하는 이진 교차 엔트로피라
    `log(0)`을 계산하지 않습니다.
  - `tensor_cross_entropy_loss`: 클래스 인덱스에 대한 softmax 교차 엔트로피.

  `tensor_accuracy`는 배치 예측의 정확도를 구합니다.
- **`Mlp`**는 완전 연결 레이어의 스택입니다. `inDim → hidden × hiddenLayers →
  outDim`이며, 레이어 사이에 `ACT_RELU`, `ACT_LEAKY`, `ACT_TANH`,
  `ACT_SIGMOID`, `ACT_GELU`, `ACT_SILU` 중 하나를 씁니다. PyTorch `nn.Linear`처럼
  초기화하며 가중치는 `[in, out]`으로 저장합니다. `model_copy` /
  `model_soft_update`는 타깃 네트워크를 동기화합니다.
- **`Optimizer`**는 파라미터 목록을 갖고 다음 종류를 제공합니다.

  | 종류 | 갱신 |
  |---|---|
  | `OPT_SGD` | 기본 경사 하강. |
  | `OPT_MOMENTUM` / `OPT_NESTEROV` | heavy-ball 또는 Nesterov 모멘텀(`momentum` 0.9). |
  | `OPT_RMSPROP` | 제곱 그래디언트의 이동 평균(`beta2` 0.99). |
  | `OPT_ADAGRAD` | 누적 제곱 그래디언트의 제곱근으로 학습률을 나눕니다(Duchi et al. 2011). |
  | `OPT_ADAM` | L2 weight decay를 쓰는 Adam(`beta1` 0.9, `beta2` 0.999). |
  | `OPT_ADAMW` | 분리된 weight decay를 쓰는 Adam(`weightDecay` 0.01). |
  | `OPT_LION` | 보간한 모멘텀의 부호로 갱신합니다(Chen et al. 2023). 분리된 decay를 쓰며, 학습률은 Adam보다 3~10배 작게 줍니다. |

  `Optimizer::clip_grad_norm`은 전체 그래디언트 노름을 자릅니다.
  필드(`lr`, beta, `weightDecay`)가 공개되어 있어 스텝 사이에 스케줄로 바꿀 수
  있습니다.

- **저장과 불러오기.** `tensors_save` / `tensors_load`는 텐서 목록(형상과
  float32 값)을 파일에 쓰고, 같은 형상의 텐서에 다시 읽어 들입니다.
  `model_save_file` / `model_load_file`은 `Mlp`에 대해 같은 일을 합니다. 파일이
  없거나 맞지 않으면 아무것도 바꾸지 않고 0을 반환합니다.

`trainer_fit`은 모델 타입에 대해 제네릭이라 순전파와 손실이 컴파일 시간에
결정됩니다. 섞인 미니배치로 에폭을 돌립니다: 손실, 역전파, 선택적 클리핑, 스텝,
에폭마다 lr 감쇠. 각 스텝의 텐서는 스텝이 끝나면 리셋되는 `TensorArena`에서
나옵니다. 파라미터와 옵티마이저 상태는 항상 힙에 남습니다.

```c
static &FloatTensor clf_loss(&Mlp m, const &FloatTensor x, const &FloatTensor y) {
    return tensor_cross_entropy_loss(m.forward(x), y);
}
static &FloatTensor clf_logits(&Mlp m, const &FloatTensor x) { return m.forward(x); }

struct Rng rng = Rng::new(1UL);
struct Dataset data = Dataset::moons(400UL, (float)0.1, &rng);
struct DatasetSplit sp = data.split((float)0.25, &rng);
struct Mlp net = Mlp::new(2UL, 32UL, 2UL, 2UL, ACT_RELU, ACT_NONE, &rng);
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.01);
model_add_params(&net, &opt);
struct TrainConfig cfg = TrainConfig::default();     // 10 에폭, 배치 32
cfg.epochs = 60UL;
struct TrainReport rep = trainer_fit(&net, clf_loss, &opt, &sp.train, &cfg);
float acc = trainer_accuracy(&net, clf_logits, &sp.test, 64UL);   // 테스트에서 0.95 초과
```

**데이터셋.** `Dataset`은 `x [n, features]`와 `y [n, targets]`이며, 분류에서는
`classes`를 설정합니다. 합성 생성기는 다음과 같습니다.

- `Dataset::moons`, `Dataset::circles`, `Dataset::spirals`, `Dataset::blobs`,
  `Dataset::xor`: 2D 장난감 분류 문제.
- `Dataset::sine`: 회귀.
- `Dataset::gaussian_ring`: GAN의 모드 커버리지 테스트에 흔히 쓰는 데이터.
- `Dataset::shapes`: 네 가지 도형 클래스의 작은 이진 이미지.

`Dataset::load_mnist`는 MNIST / Fashion-MNIST IDX 파일을 읽어 픽셀을 [0, 1]로
맞춥니다. `Dataset::load_csv`는 숫자 CSV를 읽으며, 헤더와 레이블 열은 선택입니다.
둘 다 오류 시 `n == 0`을 반환합니다. `Dataset::split`, `Dataset::standardize`,
`Dataset::standardize_pair`(학습 세트의 통계를 양쪽에 적용)로 데이터를
준비합니다. `DataLoader`는 에폭마다 새로 섞은 순서로 배치를 내줍니다.

테스트는 모든 연산의 그래디언트를 중앙 차분과 비교합니다. 옵티마이저가
수렴하는지도 확인하며, moons로 학습한 MLP는 정확도 95%를 넘습니다.

같은 호출로 GPU에서도 학습합니다: `TrainConfig.device`를 두거나 `model_to`로
모델을 배치하면 모든 연산이 피연산자가 있는 곳에서 실행됩니다
([GPU에서 학습](#devices)).

## 트레이트로서의 모델: 모든 모델에 쓰는 제네릭 함수 {#modules}

```c
#include <std/ml/module.h>
```

모든 모델은 메서드를 가진 구조체입니다. `module.h`는 모델이 할 수 있는
일을 하나의 능력씩 작은 [트레이트](/ko/reference/generics#composing-traits)로
이름 붙이고, 제네릭 코드가 요구하는 역할로 합성합니다. 표 아래의 함수들은
한 번만 작성되며 그 능력을 가진 모든 모델에 쓰입니다. 각 호출은 컴파일
시간에 결정됩니다.

| 트레이트 | 구성 | 만족시키는 타입 |
|---|---|---|
| `Parameterized` | `collect_params(&out)`(학습되는 텐서), `collect_state(&out)`(그 텐서와 학습되지 않는 버퍼: 실행 통계, 스펙트럴 정규화의 `u`) | 아래의 모든 모델, 그리고 `Gan`, `Dcgan`, `Dqn`, `PolicyAgent`, `DiTBlock`, `GatedDeltaNet`, `LSTMCell`, `XLSTMCell` |
| `Forward` | `forward(x)` | |
| `Objective` | `loss(x, y)`: 모델 자체의 학습 손실 | |
| `Module` | `Parameterized + Forward` | `Mlp`, `ConvNet`, `LoraMlp`, `QLoraMlp`, `LayerNorm`, `MultiHeadAttention`, `Mla`, `EgMla`, `JiTBlock`, `Mamba` |
| `LossModel` | `Parameterized + Objective` | `Autoencoder`, `Vae`, `VqVae`, `ConvAutoencoder`, `ConvVae` |
| `CondModule` | `Parameterized` + `forward(x, c)` | `DiTBlock`, `JiT` |
| `Recurrent` | `Parameterized` + `forward(x_t, h)` | `RNNCell`, `GRUCell` |
| `Generative` | `sample(n, labels)` | `Gan`, `Dcgan`, `Vae`, `ConvVae` |
| `GanModel` (`gan.h`) | `Parameterized + Generative` + `train_step`, `train` | `Gan`, `Dcgan` |
| `DenoiseModel` (`diffusion.h`) | `predict(x, sigma)` | 직접 만든 디퓨전 모델([샘플러](#diffusion)) |

| 함수 | 바운드 | 하는 일 |
|---|---|---|
| `model_add_params(&m, &opt)` | `Parameterized` | 학습되는 텐서를 `Optimizer`에 추가합니다 |
| `model_param_count(&m)` | `Parameterized` | 학습되는 값의 수 |
| `model_save_file(&m, path)` / `model_load_file(&m, path)` | `Parameterized` | `tensors_save` 형식의 상태 파일. 불러오려면 형상이 같아야 합니다 |
| `model_copy(&dst, &src)` / `model_soft_update(&dst, &src, tau)` | `Parameterized` | 상태 전체에 대해 `dst = src` / `dst = tau·src + (1 − tau)·dst`: 타깃 네트워크, 평균 생성기 |
| `module_fit(&m, loss, &opt, &data, &cfg)` | `Module` | 내장 손실(`TRAIN_LOSS_CE`, `TRAIN_LOSS_MSE`, `TRAIN_LOSS_BCE`)로 하는 `trainer_fit`: 손실 함수를 작성할 필요가 없습니다 |
| `module_loss(&m, loss, x, y)`, `module_predict(&m, x)`, `module_evaluate(&m, loss, &data, batch)`, `module_accuracy(&m, &data, batch)` | `Module` | 손실 하나, 기울기 없는 순전파, 데이터셋 전체의 평균 손실과 정확도 |
| `model_fit(&m, &opt, &data, &cfg)`, `model_evaluate(&m, &data, batch)` | `LossModel` | 모델 자체의 손실로 하는 같은 일 |
| `recurrent_run(&cell, xs, h0)` | `Recurrent` | 셀을 시퀀스 `[T, in]` 위로 실행하고 마지막 은닉 상태를 반환합니다 |

```c
struct Mlp net = Mlp::new(2UL, 32UL, 2UL, 2UL, ACT_RELU, ACT_NONE, &rng);
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.01);
model_add_params(&net, &opt);
struct TrainReport rep = module_fit(&net, TRAIN_LOSS_CE, &opt, &train, &cfg);
float acc = module_accuracy(&net, &test, 64UL);
model_save_file(&net, "net.bin");
```

같은 다섯 줄로 `ConvNet`, `LoraMlp`(`collect_params`가 어댑터만 반환하므로
어댑터만 학습되고 저장됩니다), 또는 메서드를 갖춘 직접 만든 모델을 학습할
수 있습니다. 바운드가 있는 제네릭 구조체로 다른 모델들을 조합한 모델도
그 자체로 `Module`입니다.

```c
generic<M: Module>
struct Avg2 {
    M a;
    M b;
    &FloatTensor forward(const &FloatTensor x);
    void collect_params(&Vec<&FloatTensor> out) const;
    void collect_state(&Vec<&FloatTensor> out) const;
};
generic<M: Module>
&FloatTensor Avg2::forward(const &FloatTensor x) {
    return tensor_add(self.a.forward(x), self.b.forward(x)).scale((float)0.5);
}
generic<M: Module>
void Avg2::collect_params(&Vec<&FloatTensor> out) const { self.a.collect_params(out); self.b.collect_params(out); }
generic<M: Module>
void Avg2::collect_state(&Vec<&FloatTensor> out) const { self.a.collect_state(out); self.b.collect_state(out); }

struct Avg2<struct Mlp> pair;   // ... pair.a, pair.b = mlp_new(...)
module_fit(&pair, TRAIN_LOSS_CE, &opt, &train, &cfg);
```

직접 정의한 손실에는 `trainer_fit`을 그대로 씁니다. 손실을 모델과 배치의
함수로 받습니다. `tests/stdlib/tests/test_ml_modules.sc`는 하나의 제네릭
검사(순전파, 파라미터 수, 저장과 불러오기, 복사)를 열 가지 `Module`에
실행하고, `module_fit`이 `trainer_fit`과 비트 단위까지 같은지 확인하며,
위의 `Avg2` 쌍을 학습합니다.

## 정규화: LayerNorm과 AdaLN {#norm}

```c
#include <std/ml/norm.h>
```

`[batch, dim]` 행에 대한 학습 가능한 정규화 레이어로, autograd를 지원합니다.
`activations.h`의 `FloatTensor::layernorm_rows`는 순전파 전용 버전입니다.

- **`LayerNorm`**(Ba et al. 2016)은 `y = LN(x) · gamma + beta`를 계산합니다.
  `gamma`(초기값 1)와 `beta`(초기값 0)를 학습합니다.
- **`AdaLN`**은 DiT(Peebles & Xie 2023)의 적응형 LayerNorm입니다. 샘플마다의
  조건 벡터(타임스텝·클래스 임베딩 등)에 선형 변환을 적용해
  `(shift, scale[, gate])`를 얻고, `y = LN(x) · (1 + scale) + shift`를
  계산합니다. 변환은 0에서 시작하므로(adaLN-Zero) 새 AdaLN은 일반 LayerNorm과
  같습니다. `gated`를 켜면 `AdaLNOut.gate`도 0에서 시작하므로, 잔차 분기
  `x + gate · f(y)`가 항등 함수로 시작합니다.
- **구성 연산**: `FloatTensor::layer_norm`, `tensor_mul_row`(모든 행에 벡터를
  곱함), 그리고 이제 미분 가능해진 `attention.h`의 `FloatTensor::slice_cols`.

```c
struct AdaLN ad = AdaLN::new(dim, condDim, 1);   // 게이트 사용
struct AdaLNOut o = ad.forward(x, cond);        // x [m, dim], cond [m, condDim]
&FloatTensor h = tensor_add(x, tensor_mul(o.gate, ffn.forward(o.out)));
model_add_params(&ad, &opt);
```

테스트는 두 레이어의 그래디언트를 유한 차분과 비교하고, 새 AdaLN이
LayerNorm과 같은지 확인합니다. 또 일반 LayerNorm으로는 표현할 수 없는 클래스별
아핀 변환을 AdaLN이 학습해, 손실을 0.5 초과에서 0.001 미만으로 낮추는지도
확인합니다.

## 미세 조정: LoRA와 QLoRA {#lora}

```c
#include <std/ml/lora.h>
```

**LoRA**(Hu et al. 2021)는 사전 학습된 `Mlp`를 고정하고 레이어마다 저랭크
갱신을 학습합니다.

`y = x·W + b + (alpha / r) · (dropout(x)·A)·B`

`A [in, r]`는 무작위로, `B [r, out]`는 0으로 시작하므로 적응된 네트워크는
기반 모델과 똑같이 시작합니다. A와 B만 학습하며, 레이어마다 `in · out` 대신
`r · (in + out)`개 값입니다.

- `LoraMlp::attach`는 기반 모델의 `requiresGrad`를 0으로 만들고, `LoraMlp::detach`가
  되돌립니다.
- `LoraMlp::merge`는 `scale · A·B`를 `W`에 접어 넣으므로, 이후에는 기반 모델만으로
  적응된 출력을 계산하며 추론 비용이 늘지 않습니다.

**QLoRA**(Dettmers et al. 2023)는 같은 일을 **4비트 NormalFloat(NF4)**로
저장한 기반 모델 위에서 합니다. 가중치 64개 블록마다 absmax 스케일 하나와,
16개 NF4 레벨에 대한 4비트 인덱스를 둡니다. NF4 레벨은 정규 분포의
분위수이므로, 가우시안에 가까운 가중치가 레벨을 고르게 씁니다. 이중 양자화는
스케일도 8비트로 저장하고 스케일 256개마다 float 하나를 두어, 기반 모델을
가중치당 약 4.1비트로 줄입니다.

`QLoraMlp::attach`는 기반 모델을 복사하므로 원래 `Mlp`는 해제해도 됩니다. 순전파마다
가중치를 float32로 역양자화하며, 그래디언트는 고정된 4비트 기반 모델을 거쳐
어댑터로 흐릅니다. `Nf4FloatTensor::quantize` / `Nf4FloatTensor::dequantize`는 단독으로도 쓸 수 있습니다.

```c
struct LoraConfig cfg = LoraConfig::default(8UL);    // 랭크 8, alpha 8
struct QLoraMlp ft = QLoraMlp::attach(&pretrained, &cfg, NF4_BLOCK, 1);   // NF4 + 이중 양자화
pretrained.free();
struct Optimizer opt = Optimizer::new(OPT_ADAMW, (float)0.001);
model_add_params(&ft, &opt);                   // 어댑터만
trainer_fit(&ft, my_loss, &opt, &data, &tc);   // my_loss가 qlora_forward를 호출
```

테스트에서는 moons로 사전 학습한 MLP를, 회전하고 레이블을 뒤바꾼 과제로 미세
조정합니다. 고정된 기반 모델은 이 과제를 틀립니다(정확도 70% 미만).

- **LoRA**는 정확도 95%를 넘깁니다. 기반 가중치는 비트 단위로 그대로이며,
  병합한 기반 모델은 어댑터의 출력을 1e-3 이내로 재현합니다.
- **NF4**는 격자 위의 값을 정확히 복원합니다. 가우시안 가중치에서는 가중치당
  4.5비트에 상대 RMS 오차가 12% 미만이며, 이중 양자화를 쓰면 거의 같은 오차로
  4.2비트 미만이 됩니다.
- **QLoRA**는 float32 크기의 1/6 미만으로 저장한 기반 모델에서 동작합니다. 4비트
  기반 모델은 원래 과제에서 여전히 95%를 넘기고, 어댑터는 새 과제에서 95%를
  넘깁니다.

## 합성곱 네트워크 {#convnet}

```c
#include <std/ml/convnet.h>
```

`cnn.h`는 `FeatureMap`에서 합성곱을 순방향으로만 실행합니다. `convnet.h`는 같은
연산을 `FloatTensor`에서 미분 가능하게 만들어, `Mlp`처럼 `FloatTensor::backward`,
옵티마이저, `trainer_fit`으로 학습할 수 있게 합니다. 이미지는 다른 모델과
마찬가지로 행입니다. 배치는 `[batch, C*H*W]` 텐서이고, 각 행에 CHW 순서의
이미지 하나가 들어 있습니다. 2D 텐서는 이미지 형상을 담지 않으므로 모든 연산이
`C, H, W`를 인수로 받습니다. 각 연산은 입력과 학습 파라미터의 그래디언트를
기록합니다.

### 합성곱과 풀링

`tensor_conv2d_ex`와 `tensor_conv_transpose2d_ex`는 `Conv2dSpec`을 받습니다.
축마다 따로 정하는 커널 크기, 스트라이드, 패딩, 팽창(dilation)에 더해
`groups`와, 전치 합성곱이라면 `outPadH` / `outPadW`가 들어 있습니다.
`Conv2dSpec::new(kernel, stride, padding)`은 정사각 커널, 팽창 1, 그룹 1, 출력
패딩 0으로 채우며, 필요한 필드만 바꾸면 됩니다. `tensor_conv2d` /
`tensor_conv_transpose2d`는 두 축에 같은 스트라이드와 패딩을 쓰는 흔한
경우입니다.

| 연산 | 가중치 | 출력 크기 |
|---|---|---|
| `tensor_conv2d_ex` | `[outC, (inC/groups)*kH*kW]`(PyTorch `Conv2d` 가중치를 평탄화한 것), 바이어스 `[outC]` | `(dim + 2·pad − dil·(k − 1) − 1)/stride + 1`, 내림 |
| `tensor_conv_transpose2d_ex` | `[inC, (outC/groups)*kH*kW]`(PyTorch `ConvTranspose2d` 가중치를 평탄화한 것), 바이어스 `[outC]` | `(dim − 1)·stride − 2·pad + dil·(k − 1) + outPad + 1` |

`groups == inC`이면 깊이별(depthwise) 합성곱입니다. 출력 패딩은 PyTorch처럼
스트라이드나 팽창보다 작아야 합니다. 같은 스트라이드가 한 입력 크기로 되돌리는
여러 출력 크기 중 하나를 고르는 데 씁니다.

풀링은 `Pool2dSpec::new(kernel, stride, padding)`으로 만드는 `Pool2dSpec`을
받습니다. 축마다 따로 정하는 윈도우, 스트라이드, 패딩과 `ceilMode`, 그리고
평균 풀링이라면 `countIncludePad`가 들어 있습니다.

- `FloatTensor::maxpool2d_ex` / `FloatTensor::avgpool2d_ex`는 윈도우를 평면 안으로
  자릅니다. 그래서 최대 풀링은 PyTorch의 `MaxPool2d`처럼 패딩을 무시하고,
  각 윈도우의 그래디언트를 첫 번째 최댓값 입력으로 보냅니다.
- `countIncludePad`를 켜면 평균 풀링은 PyTorch `AvgPool2d`의 기본값처럼,
  패딩된 평면 안에 드는 윈도우 크기로 나눕니다. 끄면 평면 안의 위치 수로
  나눕니다.
- `ceilMode`는 출력 크기를 올림합니다. PyTorch처럼 마지막 윈도우도 입력이나
  왼쪽 패딩 안에서 시작해야 합니다.
- `FloatTensor::maxpool2d` / `FloatTensor::avgpool2d`는 정사각 윈도우를 받습니다.
  `FloatTensor::global_avgpool2d` / `FloatTensor::global_maxpool2d`는 채널마다 값
  하나로 줄입니다(`[batch, C*H*W] → [batch, C]`).

크기는 `conv_out_side_ex`, `conv_transpose_out_side_ex`, `pool_out_side`로
구합니다.

두 합성곱 모두 그룹마다 im2col + `sgemm`으로 계산합니다. 입출력 채널이 8개
이상인 3×3, 스트라이드 1 레이어는 순방향을 `cnn.h`의 Winograd F(2×2, 3×3)
커널로, 1×1 레이어는 직접 경로로 실행합니다. 배치가 충분히 크면 스레드 풀에
나눠, 스레드마다 이미지 묶음 하나를 맡깁니다. 가중치와 바이어스 그래디언트는
묶음별로 합한 뒤 묶음 순서대로 더하므로, 결과가 스케줄링에 따라 달라지지
않습니다. 풀링, 크기 조절, 정규화는 큰 입력을 평면, 행, 채널 단위로 나눕니다.
단위끼리 출력을 공유하지 않으므로, 여러 스레드의 결과가 한 스레드의 결과와
비트 단위로 같습니다.

### 크기 조절

`x.resize2d(C, H, W, outH, outW, mode, alignCorners)`는 PyTorch의
`interpolate(size=...)`처럼 어떤 크기로든 키우거나 줄입니다.

| `mode` | 읽는 위치 |
|---|---|
| `RESIZE_NEAREST` | 원본 픽셀 `floor(o · in / out)`(PyTorch `nearest`) |
| `RESIZE_BILINEAR` | 가장 가까운 픽셀 4개 |
| `RESIZE_BICUBIC` | 가장 가까운 픽셀 16개, a = −0.75 3차 합성곱 |

`alignCorners`를 켜면 모서리 픽셀의 중심끼리 맞추고, 끄면 픽셀 영역끼리
맞춥니다. 가장자리는 고정(clamp)하며, 그래디언트는 같은 가중치로 되돌아갑니다.
`FloatTensor::upsample_nearest`는 정수 배율용 빠른 경로이고,
`FloatTensor::upsample_bilinear`는 모서리를 맞추지 않고 정수 배율로 크기를 바꿉니다.

### 정규화와 드롭아웃

- **배치 정규화**(`tensor_batch_norm`, Ioffe & Szegedy 2015)는 채널마다
  배치와 평면에 걸쳐 정규화합니다. 특징 맵에는 `plane = H*W`를,
  `[batch, features]` 행에는 1을 넘깁니다. 학습 중에는 배치 통계를 쓰고 이동
  통계를 `momentum`만큼 그쪽으로 옮깁니다. 이동 분산은 PyTorch처럼 불편
  추정치를 씁니다. 추론 중에는 이동 통계를 씁니다.
- **그룹 정규화**(`tensor_group_norm`, Wu & He 2018)는 이미지마다 채널
  그룹을 그 채널과 평면에 걸쳐 정규화합니다. 배치와 무관하므로 학습과 추론에서
  똑같이 동작합니다. 그룹 1개는 (C, H, W)에 대한 레이어 정규화이고, 채널마다
  그룹 하나는 인스턴스 정규화입니다.
- **스펙트럴 정규화**(`tensor_spectral_norm`, Miyato et al. 2018)는
  가중치를 최대 특이값으로 나눕니다. 그 값은 호출 사이에 유지되는 벡터 `u`에서
  출발하는 거듭제곱 반복으로 추정합니다. 그래디언트는 PyTorch처럼 거듭제곱 반복
  벡터를 상수로 봅니다.
- **드롭아웃**: `FloatTensor::dropout`은 학습 중에 각 값을 확률 p로 0으로 만들고
  나머지를 1/(1 − p)배 하며, 추론 중에는 x를 그대로 통과시킵니다.
  `FloatTensor::dropout2d`는 채널 평면 전체를 떨굽니다.

**조건 입력.** `FloatTensor::label_planes`는 클래스 인덱스를 클래스마다 상수 평면
하나로 바꿉니다. 행의 레이블에 해당하는 평면은 1이고 나머지는 0입니다.
`tensor_append_label_channels`는 그 평면들을 이미지 채널 뒤에 붙입니다. 조건부
`ConvVae`와 `Dcgan`은 이 방식으로 이미지 쪽 네트워크에 레이블을 넘깁니다.

### `ConvNet`

`ConvNet`은 `Mlp`에 대응하는 합성곱 네트워크입니다. 입력 형상으로 시작해
레이어를 덧붙이며, 각 레이어의 입력은 앞 레이어의 출력입니다.

| 빌더 | 레이어 |
|---|---|
| `n.conv(outC, kernel, stride, padding, act, &rng)` / `n.conv_ex(outC, &spec, act, &rng)` | 합성곱 후 `act` |
| `n.deconv(...)` / `n.deconv_ex(...)` | 전치 합성곱 후 `act` |
| `n.maxpool(kernel, stride, padding)` / `n.maxpool_ex(&spec)` | 최대 풀링 |
| `n.avgpool(kernel, stride, padding, countIncludePad)` / `n.avgpool_ex(&spec)` | 평균 풀링 |
| `n.global_avgpool()` / `n.global_maxpool()` | 채널마다 값 하나로. 출력은 `C × 1 × 1` |
| `n.upsample(factor)` / `n.upsample_bilinear(factor)` / `n.resize(outH, outW, mode, alignCorners)` | 크기 조절 |
| `n.batchnorm(act)` | 배치 정규화(momentum 0.1, eps 1e-5) 후 `act` |
| `n.groupnorm(groups, act)` / `n.layernorm(act)` / `n.instancenorm(act)` | 그룹 정규화(eps 1e-5) 후 `act` |
| `n.spectral_norm(&rng)` | 마지막 합성곱이나 완전 연결 레이어의 가중치를 스펙트럴 정규화합니다. 학습 패스마다 거듭제곱 반복 1회 |
| `n.dropout(p, byChannel, &rng)` | 드롭아웃. 난수열은 `rng`에서 시드를 받습니다 |
| `n.dense(outDim, act, &rng)` | 평탄화한 맵에 대한 완전 연결. 출력은 `outDim × 1 × 1` |
| `n.reshape(C, H, W)` | 데이터를 옮기지 않고 형상만 다시 해석합니다 |
| `slot = n.save()` | 스킵 연결에 쓸 현재 활성값을 보관합니다 |
| `n.add_skip(slot, act, &rng)` | 보관한 활성값을 더한 뒤 `act`. 채널 수나 크기가 다르면 보관한 활성값을 학습되는 1×1 합성곱에 통과시킵니다(ResNet의 투영 지름길) |
| `n.concat_skip(slot)` | 보관한 활성값의 채널을 뒤에 붙입니다. 높이와 너비가 같아야 합니다(U-Net 스킵) |
| `n.residual_block(outC, stride, batchNorm, act, &rng)` | ResNet 기본 블록: 3×3 합성곱(stride) → [배치 정규화] → act → 3×3 합성곱 → [배치 정규화] → 지름길 더하기 → act |

정규화는 `ACT_NONE` 합성곱과 활성화 사이에 둡니다. 가중치와 바이어스는
PyTorch처럼 U(−1/√fanIn, 1/√fanIn)으로 초기화하고, 정규화는 gamma 1,
beta 0에서 시작합니다. `params`에는 학습되는 텐서가, `buffers`에는 옵티마이저가
학습하지 않는 것(이동 통계, 스펙트럴 정규화의 `u`)이 들어 있으며, 둘 다 힙에
둡니다.

`training` 필드는 `ConvNet::new`에서 1로 시작합니다. 학습 모드에서는 배치
정규화가 배치 통계를 쓰고 이동 통계를 갱신하며, 스펙트럴 정규화가 반복하고,
드롭아웃이 값을 떨굽니다. 추론할 때는 `n.set_training(0)`을
호출하며, 이 함수는 이전 모드를 반환합니다. `autoencoder.h`와 `gan.h`의 모델은
모드를 직접 바꿉니다. 손실 함수와 학습 스텝 함수는 학습 모드로,
encode·decode·sample·generate·score는 추론 모드로 실행합니다.

`ConvNet`에 대한 그 밖의 연산:

- `ConvNet::forward`는 `[batch, in_dim()]`을
  `[batch, out_dim()]`으로 바꿉니다. `outC`, `outH`, `outW` 필드가 현재
  출력 형상입니다.
- `model_add_params`로 파라미터를 등록합니다(버퍼는 제외).
- `ConvNet::clone`, `model_copy`, `model_soft_update`는 버퍼까지 포함해
  타깃 네트워크와 평균 네트워크를 다룹니다.
- `ConvNet::fold_batchnorm`은 합성곱이나 완전 연결 레이어 바로 뒤의 배치
  정규화를 추론용으로 그 레이어의 가중치와 바이어스에 접어 넣습니다. 출력은
  그대로지만, 그 뒤로는 네트워크를 학습하지 않습니다.
- `model_save_file` / `model_load_file`은 파라미터와 버퍼를 파일에 쓰고,
  같은 레이어로 만든 네트워크에 다시 읽어 들입니다. 맞지 않으면 아무것도 덮어쓰지
  않고 0을 반환합니다. `nn.h`의 `tensors_save` / `tensors_load`,
  `Mlp`의 파일도 같은 형식을 씁니다.

```c
static &FloatTensor cnn_loss(&ConvNet m, const &FloatTensor x, const &FloatTensor y) {
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
net.dense(10UL, ACT_NONE, &rng);   // 로짓
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.001);
model_add_params(&net, &opt);
struct TrainConfig cfg = TrainConfig::default();
trainer_fit(&net, cnn_loss, &opt, &mnist, &cfg);
net.set_training(0);   // 여기부터 추론
net.fold_batchnorm();
model_save_file(&net, "mnist_resnet.bin");
```

테스트는 모든 연산의 입력과 파라미터 그래디언트를 중앙 차분과 비교합니다.
다루는 경우는 다음과 같습니다.

- 스트라이드 1과 2의 conv2d, 팽창 그룹 합성곱, 깊이별 합성곱, 축마다
  스트라이드와 패딩이 다른 2×3 커널
- 스트라이드 1과 2의 전치 합성곱, 출력 패딩이 있는 그룹 전치 합성곱, 팽창
  전치 합성곱
- 겹치지 않는 윈도우, 겹치는 윈도우, 패딩된 윈도우, 2×3 ceil 모드 윈도우의
  최대 풀링
- 패딩을 셀 때와 세지 않을 때, 그리고 ceil 모드의 평균 풀링
- 전역 평균 풀링과 전역 최대 풀링
- 최근접 업샘플링, 2배와 3배 쌍선형 업샘플링, 5×4로의 모서리 맞춤 쌍선형,
  7×5로의 쌍삼차, 최근접 다운샘플링
- 학습 모드와 추론 모드의 배치 정규화, 레이어 정규화, 그룹 정규화
- 스펙트럴 정규화
- 드롭아웃과 채널 드롭아웃

기준값과의 비교:

- 크기: 패딩된 풀링과 ceil 모드 풀링, 팽창 합성곱의 출력 크기가 PyTorch와
  같습니다.
- `cnn.h`와의 일치: `tensor_conv2d`, 패딩된 풀링, 전역 풀링이
  `Conv2D::forward`, `*pool2d_forward_ex`, `global_*pool2d`와 일치합니다.
  Winograd 경로는 직접 합과 일치합니다.
- 크기 조절: 쌍선형 업샘플링이 2×2 입력에서 PyTorch와 같은 출력을 내고,
  쌍삼차 크기 조절이 상수 이미지를 그대로 유지합니다.

`ConvNet` 확인:

- 투영 스킵, 연결 스킵, 그룹 정규화, 완전 연결 레이어를 거치는 입력
  그래디언트를 확인합니다.
- 잔차 블록의 파라미터 수를 확인합니다.
- 배치 정규화 접기 뒤의 추론 출력이 1e-4 이내로 같습니다.
- 저장한 네트워크를 다르게 초기화한 네트워크에 읽으면 출력이 정확히 같고,
  구조가 다른 네트워크는 읽기를 거부합니다.

그 밖의 확인:

- 배치 정규화의 이동 통계가 배치 통계를 따라갑니다.
- 드롭아웃이 정해진 비율만큼 떨구고, 채널 드롭아웃이 평면 전체를 떨굽니다.
- 스펙트럴 정규화 뒤의 최대 특이값이 1입니다.
- 그룹 정규화가 배치의 다른 행과 무관합니다.
- 여러 스레드의 합성곱이 한 스레드와 1e-4 이내로 같고, 풀링·업샘플링·배치
  정규화는 비트 단위로 같습니다.
- 끝에서 끝까지, 배치 정규화와 채널 드롭아웃을 쓰는 작은 잔차 CNN이 따로 떼어
  둔 12×12 도형을 90% 넘게 맞힙니다.

## 오토인코더 {#autoencoders}

```c
#include <std/ml/autoencoder.h>
```

평탄화한 행에 대한 MLP 인코더와 디코더입니다. 각 `*_loss` 함수는 `trainer_fit`의
손실 시그니처를 따릅니다. 재구성 손실은 실수 데이터에 `RECON_MSE`, [0, 1]
데이터에 `RECON_BCE`를 씁니다. BCE에서는 디코더가 로짓을 출력하고 `*_decode` /
`*_reconstruct`가 sigmoid를 적용합니다.

| 모델 | 추가되는 것 |
|---|---|
| `Autoencoder` | 기본 undercomplete AE. 설정으로 변형을 켜며, 변형끼리 조합할 수 있습니다. **디노이징**은 `noiseStd` 가우시안 노이즈를 더하고 입력의 `dropProb` 비율을 0으로 만들며, 손실은 깨끗한 입력과 비교합니다. **희소**는 코드에 `sparsity` × L1 벌점을 더합니다. |
| `Vae` | **VAE**(Kingma & Welling 2014): 가우시안 인코더와 재매개변수화를 쓰며, 손실은 특징에 대해 합한 재구성 오차에 `beta` × KL을 더한 것입니다. `beta > 1`이면 **β-VAE**이며, KL 워밍업에는 `beta`를 0에서 올립니다. `classes > 0`이면 **조건부 VAE**로, 원-핫 레이블을 인코더 입력과 디코더 입력에 모두 붙입니다. `Vae::sample`은 사전 분포에서 뽑으며, 클래스별로도 뽑을 수 있습니다. |
| `VqVae` | **VQ-VAE**(van den Oord et al. 2017): 인코더 출력을 가장 가까운 코드북 벡터로 바꿉니다. 그래디언트는 straight-through로 통과하고, 손실에 코드북 항과 `commitment` 항이 더해집니다. 코드북 붕괴를 막기 위해 첫 배치로 코드북을 초기화하고, `resetDeadAfter` 배치 동안 안 쓰인 코드는 무작위 인코더 출력으로 옮깁니다. `VqVae::encode` / `VqVae::decode`는 이산 코드와 서로 변환합니다. |

```c
struct VaeConfig cfg = VaeConfig::default(4UL);   // 은닉 256, BCE, beta 1
cfg.classes = 4UL;                               // 조건부
struct Vae v = Vae::new(100UL, &cfg);
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.002);
model_add_params(&v, &opt);
model_fit(&v, &opt, &shapes, &tc);   // VAE 자체의 손실(Vae::loss)
&FloatTensor samples = v.sample(64UL, (?&FloatTensor)labels);   // (0, 1) 범위의 [64, 100]
```

테스트는 각 변형의 재구성 오차를 학습 전 오차와 비교합니다. 또 실제 도형으로
학습한 분류기가 조건부 VAE의 샘플을 요청한 클래스로 인식하는지(정확도 70%
초과), VQ-VAE가 코드를 4개 넘게 쓰는지도 확인합니다.

### 합성곱 오토인코더 {#conv-autoencoders}

`ConvAutoencoder`와 `ConvVae`는 직접 만든 `ConvNet` 인코더와 디코더를 쓰는
기본 AE와 VAE입니다. 인코더는 이미지를 특징으로 바꿉니다. 디코더는 코드를
`latent × 1 × 1` 입력으로 받아 이미지 크기를 출력해야 합니다.
`ConvNet::dense` + `ConvNet::reshape`로 시작하거나, 1×1에서 시작하는 전치
합성곱으로 시작하면 됩니다. `ConvVae`에서는 평탄화한 특징 위의 선형 헤드가
μ와 log σ²를 만듭니다. 손실은 `Vae`와 같고, `VaeConfig`에서 `latent`, `recon`,
`beta`, `classes`, `seed`를 읽습니다.

`classes > 0`이면 `ConvVae`는 **조건부 VAE**입니다. 인코더는 레이블을 `classes`개의
추가 입력 채널(클래스마다 상수 평면 하나)로 받고, 디코더는 코드 뒤에 붙인
원-핫 레이블을 받습니다. 따라서 C채널 이미지라면 인코더는 `C + classes`채널을
받고, 디코더는 `latent + classes`개 입력을 받아 C채널을 출력합니다. `Vae`와
마찬가지로 `y`에 클래스 인덱스가 들어가고, `ConvVae::sample`은 레이블이나
`null`을 받습니다.

두 모델 모두 네트워크의 소유권을 가져가므로, 네트워크가 아니라 모델을
해제합니다. 손실 함수는 네트워크를 학습 모드로 실행합니다. encode, decode,
reconstruct, sample은 추론 모드로 실행하므로, 거기서 배치 정규화는 이동
통계를 씁니다.

```c
unsigned long classes = 10UL;                                // 조건부, MNIST 숫자
struct ConvNet enc = ConvNet::new(1UL + classes, 28UL, 28UL);
enc.conv(32UL, 4UL, 2UL, 1UL, ACT_LEAKY, &rng);   // 32 x 14 x 14
enc.conv(64UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);    // 64 x 7 x 7
enc.batchnorm(ACT_LEAKY);
struct ConvNet dec = ConvNet::new(16UL + classes, 1UL, 1UL);  // 잠재 16 + 레이블
dec.dense(64UL * 49UL, ACT_RELU, &rng);
dec.reshape(64UL, 7UL, 7UL);
dec.deconv(32UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);   // 32 x 14 x 14
dec.batchnorm(ACT_RELU);
dec.deconv(1UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);   // 1 x 28 x 28 로짓
struct VaeConfig cfg = VaeConfig::default(16UL);
cfg.classes = classes;
struct ConvVae v = ConvVae::new(enc, dec, &cfg);
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.001);
model_add_params(&v, &opt);
model_fit(&v, &opt, &mnist, &tc);
&FloatTensor samples = v.sample(64UL, (?&FloatTensor)labels);   // (0, 1) 범위의 [64, 784]
```

테스트는 두 네트워크 모두에 배치 정규화를 넣고 8×8 도형으로 실행합니다.

- 합성곱 AE는 재구성 오차를 학습 전의 30% 미만으로 줄여야 합니다.
- 합성곱 VAE는 손실을 첫 에폭의 60% 미만으로 줄이고, KL을 0.1보다 크게
  유지하며, MSE 0.1 미만으로 재구성해야 합니다.
- 실제 이미지로 학습한 분류기가 조건부 합성곱 VAE의 샘플을 요청한 클래스로
  인식하는 비율이 70%를 넘어야 합니다.

## GAN {#gans}

```c
#include <std/ml/gan.h>
```

MLP 생성기와 판별기(leaky ReLU)를 번갈아 학습합니다. 목적 함수는 설정에서
고릅니다.

| `loss` | 목적 함수 |
|---|---|
| `GAN_STANDARD` | Goodfellow et al. 2014, 비포화(non-saturating) 생성기 손실. |
| `GAN_LSGAN` | 최소 제곱(Mao et al. 2017). |
| `GAN_WGAN` | 가중치 클리핑을 쓰는 Wasserstein 크리틱. 생성기 스텝마다 크리틱 스텝 `nCritic`번, RMSProp(Arjovsky et al. 2017). |
| `GAN_HINGE` | 힌지 손실(Miyato et al. 2018). |
| `GAN_WGAN_GP` | 그래디언트 벌점을 쓰는 Wasserstein 크리틱(Gulrajani et al. 2017): 실제 행과 생성 행을 무작위로 섞은 점에서 `gpWeight`(10) × (‖∇ₓD(x̂)‖ − 1)². 클리핑 없음, Adam (0, 0.9). `nCritic`은 5를 씁니다. |

크리틱 파라미터에 대한 벌점의 그래디언트는 혼합 2계 도함수입니다. 역전파를
다시 미분하는 대신, 행마다 정규화한 입력 그래디언트 방향으로 크리틱의 중앙
차분을 구해 계산합니다. 크리틱 패스가 두 번 더 들고 O(ε²)까지 정확하며(ε =
0.01), 테스트에서 벌점 자체의 유한 차분과 비교합니다. 벌점이 행을 독립적으로
다루므로 크리틱에 배치 정규화를 쓰면 안 됩니다(그룹 정규화나 레이어 정규화는
괜찮습니다).

`classes > 0`이면 **조건부 GAN**으로, 레이블이 두 네트워크에 모두 들어갑니다.
`emaDecay`는 생성기 가중치의 지수 이동 평균(Karras et al. 2018)을 유지하며,
`Gan::sample`는 그 평균에서 샘플링합니다. 적대적 게임은 진동하고, 평균은
어느 한 스텝의 가중치보다 중심에 가깝습니다. GAN은 두 옵티마이저(Adam, β1 =
0.5. WGAN은 RMSProp)와 스텝용 아레나를 직접 가집니다. `Gan::train`은 `Dataset`에
대해 에폭을 돌리고, `Gan::train_step`은 직접 만든 배치로 한 스텝을 돌립니다.

```c
struct GanConfig cfg = GanConfig::default(4UL);   // 잠재 차원 4
cfg.loss = GAN_HINGE;
struct Gan g = Gan::new(2UL, &cfg);
g.train(&data, 80UL, 64UL);
&FloatTensor fake = g.sample(1000UL, null);   // 힙 텐서, 호출자가 해제
```

테스트에서 모든 목적 함수가 2D 가우시안을 학습하며, 평균은 0.25 이내로
맞춥니다. WGAN-GP 크리틱의 입력 그래디언트 노름은 0.6과 1.4 사이에 듭니다. GAN은 분산을 덜 덮는 경향이 있어 퍼짐은 느슨하게 확인합니다. 조건부
GAN은 4개 모드 링에서 샘플의 75% 넘게를 각 레이블의 모드에 놓습니다.

### DCGAN {#dcgan}

`Dcgan`은 `ConvNet` 생성기와 판별기로 같은 게임을 합니다(Radford et al.
2016). G는 잠재 배치를 `latent × 1 × 1` 입력으로 받아 데이터 크기를 출력해야
하고, D는 데이터 행을 받아 점수 하나를 출력해야 합니다. `GanConfig`에서 목적
함수, 학습률, `nCritic`, `clip`, `classes`, `emaDecay`, `seed`를 읽습니다.

`classes > 0`이면 **조건부 DCGAN**입니다. G는 노이즈 뒤에 붙인 원-핫 레이블을
받으므로 `latent + classes`개 입력을 받습니다. D는 레이블을 `classes`개의 추가
입력 채널(클래스마다 상수 평면 하나)로 받습니다. `Dcgan::train_step`,
`Dcgan::sample`, `Dcgan::score`는 레이블이나 `null`을 받고, `Dcgan::train`은
데이터셋에서 레이블을 가져옵니다.

DCGAN은 네트워크, 옵티마이저, 평균 생성기, 스텝 아레나를 직접 가집니다. 학습
스텝은 네트워크를 학습 모드로 실행하므로, DCGAN 논문대로 배치 정규화가 배치
통계를 씁니다. `Dcgan::sample`와 `Dcgan::score`는 추론 모드로 실행합니다.

```c
struct ConvNet gen = ConvNet::new(16UL, 1UL, 1UL);   // 잠재 차원 16
gen.deconv(64UL, 4UL, 1UL, 0UL, ACT_NONE, &rng);    // 64 x 4 x 4
gen.batchnorm(ACT_RELU);
gen.deconv(32UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);   // 32 x 8 x 8
gen.batchnorm(ACT_RELU);
gen.deconv(1UL, 4UL, 2UL, 1UL, ACT_SIGMOID, &rng);   // 1 x 16 x 16
struct ConvNet disc = ConvNet::new(1UL, 16UL, 16UL);
disc.conv(32UL, 4UL, 2UL, 1UL, ACT_LEAKY, &rng);   // 32 x 8 x 8
disc.conv(64UL, 4UL, 2UL, 1UL, ACT_NONE, &rng);    // 64 x 4 x 4
disc.batchnorm(ACT_LEAKY);
disc.conv(1UL, 4UL, 1UL, 0UL, ACT_NONE, &rng);   // 점수 하나
struct GanConfig cfg = GanConfig::default(16UL);
struct Dcgan g = Dcgan::new(gen, disc, &cfg);
g.train(&images, 20UL, 64UL);
&FloatTensor fake = g.sample(64UL, null);   // [64, 256], 힙 텐서, 호출자가 해제
```

테스트는 8×8 도형으로 학습합니다. DCGAN은 샘플의 평균 밝기를 학습 전
sigmoid의 0.5에서 데이터 평균의 0.1 이내로 옮겨야 합니다. 실제 이미지로 학습한
분류기가 조건부 DCGAN의 샘플을 요청한 클래스로 인식하는 비율은 55%를 넘어야
합니다(우연 수준은 25%).

## 강화 학습 {#rl}

```c
#include <std/ml/rl.h>
```

**환경.** `Bandit`은 k개 팔 가우시안 밴딧입니다. `GridWorld`는 벽, 구덩이,
목표, 미끄러짐 확률, 잘림(truncation)을 지원하며, `GridWorld::cliff()`는 Sutton
& Barto의 절벽 걷기입니다. `CartPole`은 Gym CartPole-v1의 동역학, 12° / 2.4 m
한계, 500 스텝 잘림을 씁니다. 스텝은 `EnvStep`을 반환하며, `terminated`(미래
가치 없음)와 `truncated`(시간 제한이라 부트스트랩함)를 구분합니다. 관측은
텐서입니다.

| 기법 | API |
|---|---|
| 밴딧 | `BanditAgent`: `BANDIT_EPS_GREEDY`, `BANDIT_UCB1`, `BANDIT_THOMPSON`(가우시안 사후 분포), `BANDIT_GRADIENT`(기준선을 둔 softmax 선호도). |
| 테이블 TD | `TabularAgent`의 메서드 `q_learning_update`, `sarsa_update`, `expected_sarsa_update`, ε-greedy `act`. |
| 가치 기반 | `Dqn`(Mnih et al. 2015): 리플레이 버퍼, 타깃 네트워크(소프트 갱신 또는 주기적 하드 복사), Huber 손실, 선형 ε 스케줄, Double DQN(van Hasselt et al. 2016), 그래디언트 클리핑. 스텝마다 `Dqn::act` + `Dqn::observe`. |
| 정책 기반 | `PolicyAgent`: `PG_REINFORCE`(몬테카를로 리턴, 학습된 기준선), `PG_A2C`(advantage actor-critic, GAE(λ)), `PG_PPO`(클리핑된 surrogate, 롤아웃마다 섞은 미니배치로 여러 에폭). 모든 변형이 엔트로피 보너스, advantage 정규화, 그래디언트 클리핑을 씁니다. 스텝마다 `PolicyAgent::act` + `PolicyAgent::record`, 에피소드나 롤아웃마다 `PolicyAgent::update`. |

```c
struct Dqn d = Dqn::new(CARTPOLE_OBS, CARTPOLE_ACTIONS, &cfg);
struct CartPole env = CartPole::new(1UL);
env.reset(obs);
while (1) {
    unsigned long a = d.act(obs, 1);   // epsilon-greedy
    struct EnvStep s = env.step(a, next);
    d.observe(obs, a, s.reward, next, s.terminated);   // 저장 + 학습
    // obs = next ...
    if (s.terminated || s.truncated) break;
}
```

테스트는 각 기법을 해당 문제에서 실행합니다.

- 네 밴딧 에이전트는 10개 팔 중 최적의 팔을 85% 넘게 고릅니다.
- 절벽 걷기에서 Q-learning은 가장자리를 따라가는 최적 13스텝 경로를,
  SARSA는 더 안전하고 긴 경로를 배웁니다(Sutton & Barto 예제 6.6).
- DQN, REINFORCE, A2C, PPO는 각자의 스텝 예산 안에 CartPole의 "해결" 기준인
  평균 greedy 리턴 195에 도달합니다.

## GPU에서 학습 {#devices}

<a id="train-traits"></a><a id="train-backend"></a><a id="rocm-training"></a>

```c
#include <std/ml/device.h>   // 디바이스, 정밀도. tensor.h, trainer.h, module.h가 포함함
```

모든 텐서에는 디바이스가 있습니다: `DEVICE_CPU`, 또는 GPU 백엔드(AMD는
`DEVICE_ROCM`, NVIDIA는 `DEVICE_CUDA`, Apple Silicon은 `DEVICE_MPS`)입니다.
연산은 피연산자의 디바이스에서 실행됩니다. 피연산자 중 하나라도 GPU에
있으면 연산은 그곳에서 실행되며 나머지를 올리고, 결과도 그곳에 둡니다.
그래서 파라미터가 GPU에 있는 모델은 CPU에서 학습하는 것과 같은 코드로
그곳에서 학습합니다: 위의 모든 레이어, 손실, 옵티마이저, `trainer_fit`,
`module_fit` / `model_fit`, 오토인코더, GAN, 강화 학습 에이전트, LoRA
어댑터가 그렇습니다. 역전파는 각 노드를 순전파가 실행된 곳에서 실행합니다.

```c
struct Mlp net = Mlp::new(784UL, 256UL, 2UL, 10UL, ACT_RELU, ACT_NONE, &rng);
struct Optimizer opt = Optimizer::new(OPT_ADAM, (float)0.001);
model_add_params(&net, &opt);
struct TrainConfig cfg = TrainConfig::default();
cfg.device = DEVICE_AUTO;                  // 쓸 수 있는 첫 GPU, 없으면 CPU
cfg.precision = DEVICE_FP16;               // 선택: 16비트 활성값
struct TrainReport rep = trainer_fit(&net, clf_loss, &opt, &train, &cfg);
model_sync(&net);                          // 호스트 사본을 최신으로: 저장, 확인, CPU에서 평가
```

**디바이스 고르기.**

| 방법 | 호출 |
|---|---|
| 명시적으로 | `TrainConfig.device`(학습 함수가 옵티마이저의 파라미터를 그곳에 두고 데이터셋도 그곳에 둠), 모든 모델에 `model_to(&m, d)`(파라미터와 버퍼: 실행 통계, 평균 생성기), `t.place(d)`는 텐서를 제자리에서 옮김, `t.to(d)`는 `d`에 있는 사본을 돌려줌(그래프 노드: 기울기가 거꾸로 흐름) |
| 기본값으로 | `device_set_default(d)`. `DEVICE_AUTO`는 `device_default()`로 결정됨 |
| 자동으로 | `device_auto()`: 프로그램이 함께 빌드된 백엔드 중 쓸 수 있는 첫 GPU(ROCm, CUDA, Metal 순), 없으면 CPU. 환경 변수 `SAFEC_DEVICE=cpu\|rocm\|cuda\|mps`가 이를 덮어씀 |

빌드의 [feature](/ko/advanced/safeguard#feature-flags)가 백엔드를 링크합니다.
아무것도 없이 빌드한 프로그램은 모든 것을 CPU에서 실행하며 GPU
라이브러리를 링크하지 않습니다:

| Feature | 백엔드 | 링크 |
|---|---|---|
| `rocm` | `device_rocm.sc`: hiprtc가 한 번 컴파일하는 HIP 커널, rocBLAS GEMM, MIOpen 합성곱 | `-lamdhip64 -lhiprtc -lrocblas`, `-lMIOpen`(선택, weak: 없으면 합성곱은 im2col + GEMM) |
| `cuda` | `device_cuda.sc`: 같은 커널 소스를 NVRTC가 컴파일, cuBLAS, cuDNN | `-lcuda -lcublas -lnvrtc`, `-lcudnn`(선택, weak) |
| `mps` | `device_mps.sc`: 공유 메모리 버퍼, `mps_gemm`, Metal 옵티마이저 커널. 다른 연산은 공유 메모리 위에서 CPU로 실행 | `-framework Metal -framework Foundation -lobjc` |

`device_compiled(d)`, `device_available(d)`, `device_name(d)`,
`device_sync(d)`, `device_stats(d)`(`device_stats_reset` 이후의 실행 수,
GEMM, 전송과 바이트)는 프로그램이 무엇에서 실행되는지 알려 줍니다.
디바이스 메모리는 스텝이 재사용하는 크기별 캐시에서 나오며,
`device_bytes_reserved(d)`와 `device_cache_clear(d)`로 관리합니다.

**호스트 사본과 디바이스 사본.** 텐서는 호스트 값과 디바이스 사본을
필요할 때 맞춥니다: 디바이스의 연산은 호스트에서 쓴 텐서를 한 번 올리고,
호스트에서 값을 읽으면(`at1`, `at2`, CPU 전용 연산) 한 번 내려받습니다.
`t->data`를 직접 읽는 코드를 위해 `t.sync()` / `model_sync(&m)`가 호스트
사본을 명시적으로 최신으로 만듭니다. 기울기도 같습니다: 디바이스에서
`zero_grad()`는 기울기를 건드리지 않고 0으로 표시하며, 그 기울기에 처음
닿는 역전파 연산은 더하는 대신 씁니다.

**디바이스에서 실행되는 것.** 모든 원소 단위 연산과 활성화, 행렬곱,
바이어스와 행 연산, 합과 평균, concat / slice / gather, 전치, softmax(어텐션의
causal / 슬라이딩 윈도우 마스크 포함)와 log-softmax, 모든 헤드를 융합 실행 한
번으로 처리하는 [멀티헤드 어텐션](#attention)(과 그 역방향), 융합 손실(교차
엔트로피, 로짓의 BCE, Huber), 레이어 정규화,
배치 정규화와 그룹 정규화(각각 뒤따르는 ReLU나 leaky ReLU와 융합),
최대 / 평균 / 전역 풀링, 최근접 업샘플링, 쌍선형과 쌍삼차 크기 조정,
합성곱과 전치 합성곱, 희소 곱, 모든 옵티마이저(모든 파라미터에 실행 한 번),
기울기 노름 클리핑입니다. 축약이 긴 작은 GEMM은 split-K로 실행합니다.
디바이스 커널이 없는 연산은 피연산자를 먼저 가져와 CPU에서 계산하므로,
모든 연산이 모든 디바이스에서 동작하고 GPU는 속도만 바꿉니다.

**트레이트.** `DenseTensor`와 [`SparseTensor`](#sparse)는
`trait DeviceTensor : Tensor`(`device()`, `place(d)`, `sync()`)를 따르므로,
[제네릭 코드](/ko/reference/generics#composing-traits)는 둘 중 무엇이든 받을
수 있습니다. `RocmTensor`, `CudaTensor`, `MpsTensor`는 각각
`DeviceTensor`를 상속하고, 벤더 라이브러리를 직접 부르는 코드를 위해
그 백엔드에서의 값 버퍼(hipDeviceptr, CUdeviceptr, MTLBuffer)인
`device_buffer()`를 더합니다.

```c
generic<T: DeviceTensor>
int to_host(&T t) { return t.sync(); }   // DenseTensor 또는 SparseTensor
```

**백엔드.** 백엔드는 정적 함수(alloc, release, upload, download, copy,
sync, gemm, gemm_batched, conv, launch, offset, storage16)의 구조체이며
`device_register_backend_(device, &tag)`로 등록합니다. 모든 호출은 컴파일
타임에 결정되고, 런타임은 텐서를 보고 디바이스만 고릅니다. ROCm과 CUDA
백엔드는 커널 소스 `device_kernels.cu` 하나를 함께 씁니다.

### 정밀도: 디바이스의 fp16과 bf16 {#device-precision}

<a id="rocm-training-mixed-precision"></a>

`device_set_precision(d, DEVICE_FP16)`(또는 `DEVICE_BF16`), 또는 학습 한
번에는 `TrainConfig.precision`이 `d`에서 16비트로 학습합니다:

- **저장.** ROCm과 CUDA에서는 활성값과 그 기울기를 16비트로 저장합니다.
  파라미터, 그 기울기, 옵티마이저 상태는 fp32로 남습니다. 버퍼의 원소
  형식은 디바이스 핸들과 함께 다니므로 모든 커널, 복사, GEMM, 합성곱이
  그곳에서 읽습니다. 커널은 fp32로 계산하고 저장하는 값마다 한 번
  반올림합니다. 커널 소스는 두 번 컴파일됩니다: 평범한 fp32 빌드와
  16비트 피연산자용 빌드이며, 16비트 피연산자가 없는 실행은 평범한 빌드를
  고르므로 fp32 학습에는 비용이 없습니다.
- **계산.** 행렬곱은 fp32로 누적하는 16비트 GEMM(rocBLAS / cuBLAS
  `gemm_ex`), 합성곱은 MIOpen / cuDNN의 16비트 커널로 실행합니다. 합성곱
  형태와 방향마다 처음 실행할 때 16비트와 fp32를 재서 그 형태에는 더
  빠른 쪽을 씁니다(`device_set_precision_pick(DEVICE_PICK_ALWAYS)`는 모두
  16비트로 실행). 파라미터의 16비트 사본은 옵티마이저 스텝마다 한 번
  만들어 재사용합니다.
- **fp16 손실 스케일.** fp16은 PyTorch의 `GradScaler`처럼 디바이스에 동적
  손실 스케일을 둡니다: 65536에서 시작해 손실의 기울기에 곱하고,
  `backward()`가 끝날 때 모든 리프 기울기를 원래 크기로 되돌리므로 사용자
  코드는 스케일된 기울기를 보지 않습니다. 기울기가 넘친 스텝은 옵티마이저가
  디바이스에서 읽는 플래그를 세워 갱신을 건너뛰고, 스케일은 절반이 되며,
  2000번 연속 깨끗한 스텝 뒤에는 두 배가 됩니다. `device_loss_scale(d)`가
  읽습니다. bf16은 fp32와 지수 범위가 같아 스케일링이 필요 없습니다.
- **Metal.** Metal 백엔드는 fp32 저장을 유지하므로(커널이 공유 메모리
  위에서 CPU로 실행됨), 그곳에서 16비트 정밀도는 손실 스케일링만 바꿉니다.

MI100에서는 fp16이 더 빠른 16비트 형식입니다: MIOpen의 fp16 커널은 행렬
코어를 쓰지만 bf16 합성곱은 fp32와 비슷하거나 더 느립니다
([GPU 학습 벤치마크](/ko/benchmarks#ml-training-gpu)).

### 희소 텐서 {#sparse}

```c
#include <std/ml/sparse.h>
```

`SparseTensor`는 0이 아닌 값만 저장하는 CSR 형식의 `rows × cols`
행렬입니다. 값은 길이 `nnz`의 평범한 `FloatTensor`이므로 희소 행렬이
학습되는 파라미터(희소 레이어, 그래프의 학습되는 간선 가중치)가 될 수
있습니다. 기울기는 값에 닿고, 패턴은 고정입니다. 인덱스 배열도 float
텐서이므로(2^24까지 정확), `place(d)`가 행렬 전체를 디바이스로 옮기고 그
곱은 그곳에서 실행됩니다.

```c
struct SparseTensor A = SparseTensor::from_dense(adj, (float)0.0, 1);   // 학습되는 간선 가중치
&FloatTensor H = tensor_spmm(&A, X);           // [rows, n] = A [rows, cols] . X [cols, n]
// ... 손실 ... loss.backward();               // A.values->grad, X->grad
```

| 부분 | 함수 |
|---|---|
| 생성 | `SparseTensor::new_csr(rows, cols, &rowPtr, &colIdx, &values, requiresGrad)`, `from_dense(&dense, threshold, requiresGrad)`, `identity(n, requiresGrad)` |
| 원소 | `at1` / `at2`(패턴 밖은 0), `set1` / `set2`(있는 0 아닌 값), `fill` |
| 연산 | S의 값과 X로 기울기가 가는 `tensor_spmm(&S, X)`, `to_dense()`, `scale(k)`(패턴 공유), `transpose()`: 그래프 노드 |
| 디바이스 | `device()`, `place(d)`, `sync()`, `device_buffer()`(값의 버퍼) |
| 수명 | `backward()`, `zero_grad()`, `free()` |

`SparseTensor`는 `Tensor`와 `DeviceTensor`를 따르므로, 그 트레이트를
대상으로 쓴 제네릭 코드는 밀집 텐서와 나란히 희소 텐서도 받습니다.

::: tip 검증
`tests/stdlib/tests/test_ml_device.sc`는 참조 디바이스를 등록합니다. 이는
GPU와 같은 디스패치·일관성·역전파 경로를 거쳐 모든 디바이스 커널의 CPU
버전을 실행하는 호스트 메모리입니다. 그 위에서 12개 워크로드(MLP, VAE,
CVAE, GAN, hinge GAN, DQN, PPO, LoRA, 합성곱 오토인코더, 손실과 레이어
정규화, 배치 / 그룹 정규화·풀링·잔차 블록이 있는 CNN, 업샘플링·크기
조정·드롭아웃·concat 신경망)를 CPU와 대조해 학습하고, fp16 / bf16과 넘친
스텝을 건너뛰는 손실 스케일도 확인합니다.
`tests/stdlib/device/device_check.sc`는 같은 12개 워크로드를 실제 GPU에서
학습하며(`tests/stdlib/rocm/run.sh`, `cuda/run.sh`, `mps/run.sh`), fp32는
CPU와 2e-4 이내, fp16과 bf16은 5% 이내로 맞는지, 희소 곱과 그 기울기가
유한 차분과 2e-3 이내로 맞는지 봅니다. MI100에서 모든 검사가 통과합니다.
:::

## GPU 백엔드 {#gpu-backends}

```c
#include <std/ml/gpu_mps.h>   // Apple Silicon/macOS — [features] "mps"
#include <std/ml/gpu_cuda.h>  // NVIDIA — [features] "cuda"
#include <std/ml/gpu_rocm.h>  // AMD — [features] "rocm"
#include <std/ml/gpu_spirv.h> // Vulkan/SPIR-V (incomplete, see Backend status)
#include <std/ml/gpu_webgpu.h>// WebGPU
```

ML 개요서에서 말하는 "통합 메모리" 부분: MPS의 기본 버퍼 저장 모드는
Apple Silicon에서 진짜 CPU+GPU 공유 메모리입니다(MLX 자체의 셀링 포인트)
— CUDA/ROCm의 분리된(discrete) 메모리 모델이 필요로 하는 명시적인
호스트↔디바이스 복사가 없습니다.

::: warning 검증 상태
- **MPS**: 실제 Apple Silicon 하드웨어에서 종단 간 검증되었습니다 — 올바른
  출력을 내는 실제 GPU 디스패치(`mps_add_f32`, 그리고 대표 사례로
  `mps_sub_f32`/`mps_mul_f32`; 다른 원소 단위 연산은 같은 코드 경로를
  공유합니다). 예전에 `dispatchThreadgroups:threadsPerThreadgroup:`에서
  나던 세그폴트는 함수 포인터 캐스트를 통해 큰 구조체를 값으로 넘길 때의
  컴파일러 버그였으며, 코드 생성기에서 수정되었습니다. bf16 [어텐션과
  트랜스포머 연산](#mps-attention)은 M1 Pro에서 지원하는 헤드 차원 네
  가지 모두, GQA, causal·슬라이딩 윈도·non-causal 마스킹에 걸쳐 CPU 참조
  구현과 대조해 확인했습니다.
- **ROCm**: AMD Instinct MI100(gfx908, ROCm 7.1)에서 검증되었습니다 —
  `tests/stdlib/rocm/run.sh`가 모든 연산, GEMM 전치 조합 네 가지,
  그래프, 호스트 배열 API, fp16/bf16 버퍼(`float16.h`와 비트 단위로 같은
  반올림, 16비트 GEMM, SGD, 혼합 정밀도 연산)를 CPU 결과와 비교하며(검사 325개), [MIOpen과
  hipFFT 라이브러리](#rocm-miopen-hipfft)(검사 73개)와 [디바이스에서의
  학습](#devices)(fp32·fp16·bf16의 12개 워크로드를 CPU와 대조, 희소 곱)도
  검사합니다. [ML 벤치마크](/ko/benchmarks#ml-mlp)는
  CPU 및 MPS 경로와 같은 손실까지 학습합니다.
- **CUDA**: NVIDIA H200(sm_90, CUDA 13.0, 드라이버 580)에서 검증했습니다 —
  `tests/stdlib/cuda/run.sh`가 모든 연산, 네 가지 GEMM 전치 조합(ReLU 융합
  유무 모두), 그래프, 호스트 배열 API와 영구 버퍼 API, fp16/bf16 커널,
  [cuDNN·cuFFT 라이브러리](#cuda-cudnn-cufft)를 CPU 결과와 비교하고(216개 검사),
  [ML 벤치마크](/ko/benchmarks#ml-mlp)는
  CPU 및 ROCm 경로와 같은 손실까지 학습합니다.
:::

### ROCm / HIP (AMD) {#rocm-hip-amd}

```c
#include <std/ml/gpu_rocm.h>   // 링크: -lamdhip64 -lhiprtc -lrocblas
```

데이터는 `RocmBuf`에 있습니다 — 디바이스 주소와 원소 개수로 기술되는 GPU
버퍼이며 모든 연산이 개수를 검사합니다. 연산은 하나의 HIP 스트림에 순서대로
쌓이므로 학습 스텝 전체가 GPU에서 돌고, 요청한 값(`rocm_read`)만 돌아옵니다.
행렬곱은 rocBLAS로 갑니다. 나머지는 이 라이브러리 자체의 HIP 커널입니다 —
원소별 연산, ReLU와 그 기울기, 제자리 SGD, 합 리덕션, 그리고 융합된 MSE
기울기(`dy = 2(y − t)`와 손실 합을 한 번에) — 처음 쓸 때 hiprtc로 현재 GPU에
맞게 컴파일되므로 한 빌드가 어떤 ROCm GPU에서든 동작합니다. GPU가 여러 개인
머신에서는 연산 유닛이 가장 많은 것을 고릅니다.

| 그룹 | 함수 |
|---|---|
| 디바이스 | `rocm_available`, `rocm_device_name`, `rocm_sync` |
| 버퍼 | `RocmBuf::alloc`, `RocmBuf::upload`, `rocm_write`, `rocm_read`, `RocmBuf::free` |
| 행렬곱 | `rocm_gemm(a, ta, b, tb, c, m, n, k)` — `c = op(a) op(b)`, 어느 피연산자든 전치 가능 |
| 원소별 | `rocm_add`/`sub`/`mul`/`div`/`pow`, `rocm_scale`, `rocm_relu`, `rocm_gelu`(tanh 근사), `rocm_exp`/`log`/`sqrt`, `rocm_relu_grad` |
| 디코딩(한 번에 토큰 하나) | `rocm_half_gemv(w, x, y, n, k)`: fp16/bf16 가중치 `[n][k]`와 fp32 벡터로 `y = w x`. 출력 행마다 wave 하나로, MI100에서 0.8–1.07 TB/s이며 한 행짜리 `rocm_half_gemm_f32`보다 1.04–1.8배 빠릅니다. `rocm_rmsnorm(x, w, y, rows, n, eps)`는 행별 RMSNorm이며 가중치는 선택, 제자리 계산 가능. [Gemma 4 E2B ROCm 엔진](/ko/benchmarks#ml-llm)의 커널입니다 |
| 학습 | `rocm_sgd(w, g, lr)`(제자리), `rocm_mse_grad(y, t, dy, loss)`, `rocm_sum` |
| 배치 | `rocm_copy(src, srcOff, dst, dstOff, n)`, `rocm_concat(a, na, c, nc, out, batch)`, `rocm_upsample2x_nearest`, `rocm_upsample2x_concat`(U-Net 디코더 한 단계를 한 번에), `rocm_bias_channels(y, bias, batch, channels, plane, relu)`(채널별 바이어스와 선택적 ReLU, 제자리) |
| fp16 / bf16 | `RocmHalfBuf`(`ROCM_FP16` / `ROCM_BF16`): `RocmHalfBuf::alloc`, `RocmHalfBuf::upload`, `rocm_half_write` / `rocm_half_read`(원시 비트), `RocmHalfBuf::free`; `rocm_to_half` / `rocm_from_half`; `rocm_half_gemm`(16비트 결과), `rocm_half_gemm_relu`, `rocm_half_gemm_f32`(fp32 결과); 학습 연산 `rocm_half_relu`, `rocm_half_relu_grad`, `rocm_half_mse_grad`(손실은 fp32), `rocm_half_sgd(w, g, lr)`, `rocm_half_sgd2`(갱신 두 번을 실행 한 번에). CUDA의 `CudaHalfBuf`와 같은 API이며, 원소별 연산은 fp32로 계산해 저장할 때 한 번 반올림하므로 `float16.h`와 비트 단위로 같습니다 |
| 혼합 정밀도 | `rocm_half_convert`(fp16 ↔ bf16); `rocm_to_half_many` / `rocm_from_half_many`(실행 한 번에 버퍼 최대 32개); `rocm_half_unscale_many` / `rocm_unscale_many`(그래디언트 × 1/scale, 오버플로 플래그 포함)와 `rocm_zero`; `rocm_mp_sgd` / `rocm_mp_adam`(fp32 마스터 가중치, 16비트 사본도 기록, 플래그가 서 있으면 GPU에서 건너뜀); `rocm_half_add` / `sub` / `mul` / `scale` / `axpy`, `rocm_half_bias`(행 또는 NCHW 채널 바이어스, ReLU); `rocm_half_maxpool2d` / `rocm_half_avgpool2d`. [GPU 혼합 정밀도](#gpu-mixed-precision) 참고 |
| 어텐션 | `rocm_flash_attention(q, k, v, out, heads, seqLen, kvLen, headDim, scale, causal)`(정확한 softmax 어텐션, 타일 방식, 점수 행렬 없음), `rocm_linear_attention(...)`(φ(x) = elu(x) + 1 커널 어텐션, 시퀀스 길이에 선형); 호스트 배열 형태 `rocm_flash_attention_f32` / `rocm_linear_attention_f32`; `rocm_attention_use_mfma(on)`(CDNA GPU의 행렬 코어) |
| 그래프 | `rocm_capture_begin` / `rocm_capture_end` → `RocmGraph`, `rocm_graph_launch`, `RocmGraph::free` |
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
기록하고 `rocm_graph_launch()`로 재생하면 스텝 전체를 한 번에 실행합니다.
호스트가 매 스텝 GPU를 기다리지 않을 때 효과가 있습니다.

행렬곱(fp32와 16비트)은 rocBLAS `gemm_ex`를 거칩니다. rocBLAS가 어떤 크기에
기본으로 고르는 솔루션은 가장 빠른 것보다 몇 배 느릴 수 있습니다: MI100에서
128×256×1024 fp16 곱은 3.5배, 작은 bf16 곱은 2–3배. 그래서 (타입, 전치, 크기)
조합마다 첫 호출에서 rocBLAS가 내놓는 솔루션을 그 호출의 버퍼로 모두 재고
(MIOpen의 Find처럼) 가장 빠른 것을 캐시합니다. 크기 하나에 약 0.1초가 걸립니다.
캡처 안에서 처음 보는 크기는 기본 솔루션을 쓰므로, 각 크기를
`rocm_capture_begin()` 전에 한 번 실행해 두세요. 선택이 시간 측정에서 나오므로,
두 번의 실행이 다른 솔루션을 골라 마지막 비트가 다를 수 있습니다.

자동미분 텐서는 이 호스트 배열 호출이 아니라 [자신의 디바이스](#devices)로
GPU를 씁니다.

rocBLAS와 직접 작성한 커널의 비교: 이 라이브러리의 타일 SGEMM
(`rocm_matmul_f32`)은 MI100에서 4096³ 기준 약 9.5 TFLOPS, rocBLAS는 행렬
코어를 써서 33.7 TFLOPS에 이르고, 학습 루프가 쓰는 작은 크기에서도 rocBLAS가
더 빠릅니다(≈5 µs, 실행 시작 하한에 가까움). 그래서 행렬곱은 rocBLAS를 쓰고,
사용자 커널은 rocBLAS가 하지 않는 일을 맡습니다.

#### 어텐션 {#rocm-attention}

두 어텐션 연산 모두 `q`를 `[heads][seqLen][headDim]`, `k`와 `v`를
`[heads][kvLen][headDim]`(모두 fp32)으로 받아 `out`에
`[heads][seqLen][headDim]`으로 씁니다. `headDim`은 최대 128입니다. `scale`이
0 이하이면 `1/√headDim`을 씁니다. 작업을 큐에 넣으면 1, 형태가 맞지 않거나
ROCm GPU가 없으면 0을 돌려줍니다.

- **`rocm_flash_attention`**은 `softmax(scale · QKᵀ) V`를 정확히 계산합니다.
  K/V 타일을 공유 메모리로 흘려 보내며 온라인 softmax(FlashAttention)를
  쓰므로 메모리는 O(seqLen · headDim)이고 seqLen × kvLen 점수 행렬을
  저장하지 않습니다. `causal`이면 질의 `i`는 키 `j ≤ i + kvLen − seqLen`만
  봅니다(마지막 질의 행이 마지막 키와 맞춰짐 — KV 캐시와 같음). 따라서
  `kvLen ≥ seqLen`이어야 합니다. 완전히 가려진 타일은 건너뛰므로 causal
  어텐션이 약 두 배 빠릅니다.
- **`rocm_linear_attention`**은 `φ(Q)(φ(K)ᵀV) / φ(Q)(φ(K)ᵀ1)`,
  `φ(x) = elu(x) + 1`을 계산합니다(Katharopoulos 외). 시간과 메모리가 시퀀스
  길이에 선형입니다. `causal`이면 각 질의는 자기 위치까지의 키만
  봅니다(`kvLen == seqLen`). 청크별 누적 상태와 청크 내부의 마스크 항으로
  계산합니다. softmax 어텐션의 근사이므로 flash 어텐션과 결과가 다르며, CPU의
  `std::linear_attention_forward`와 일치합니다.

행렬 코어가 있는 GPU(CDNA: MI100, MI200, MI300)에서는 두 연산 모두 행렬 코어에서
fp32(MFMA)로 돕니다. 다른 AMD GPU는 반올림 오차 범위에서 같은 결과를 내는 CUDA 코어
커널을 씁니다. `rocm_attention_use_mfma(0)`은 비교를 위해 CDNA GPU에서도 CUDA 코어
커널을 고르고, `rocm_attention_use_mfma(1)`은 기본값으로 되돌립니다. 둘 다 행렬
코어 커널을 쓰고 있는지를 돌려줍니다.

MI100, 배치 4 × 헤드 16, fp32, 호출당 ms, 같은 GPU의 ROCm용 PyTorch 2.9.1과
비교([전체 결과](/ko/benchmarks#ml-attention)):

| 연산 | `headDim` | 토큰 | Causal | SafeC | PyTorch |
|---|---:|---:|:---:|---:|---:|
| flash | 64 | 4096 | 아니오 | 10.7 (25.7 TFLOPS) | 42.8 |
| flash | 64 | 4096 | 예 | 5.48 | 56.2 |
| flash | 128 | 4096 | 아니오 | 24.8 (22.2 TFLOPS) | 53.4 |
| linear | 64 | 4096 | 아니오 | 0.464 | 1.40 |
| linear | 64 | 16384 | 예 | 2.76 | 14.6 |
| linear | 128 | 4096 | 예 | 2.84 | 8.73 |

### CUDA (NVIDIA) {#cuda}

```c
#include <std/ml/gpu_cuda.h>   // 링크: -lcuda -lcublas -lcublasLt -lnvrtc
```

ROCm 백엔드와 같은 구조이고, 함수 이름도 `cuda_` 접두사로 같습니다. `CudaBuf`는
원소 개수를 함께 가진 GPU 버퍼이고, 연산은 하나의 CUDA 스트림에 순서대로
쌓이며, `cuda_read`로 요청한 값만 호스트로 돌아옵니다. 행렬곱은 cuBLAS에서
완전한 fp32로 돕니다(TF32 없음). 나머지는 이 라이브러리의 CUDA 커널이 맡는데,
처음 쓸 때 NVRTC가 현재 GPU용 cubin으로 컴파일합니다(H100/H200에서는
`-arch=sm_90`). 그래서 빌드 하나로 설치된 CUDA가 아는 모든 GPU에서 돕니다.
백엔드는 CUDA 런타임과 PyTorch가 쓰는 장치의 primary context를 쓰므로, 같은
프로세스의 다른 CUDA 코드와 버퍼를 함께 쓸 수 있습니다.

ROCm 연산에 더해, 두 가지 융합으로 학습 한 스텝의 커널 실행이 10번에서
7번으로 줍니다:

- `cuda_gemm_relu` — `relu(op(a) op(b))`를 ReLU epilogue가 붙은 cuBLASLt
  행렬곱 한 번으로 계산합니다. 이러면 ReLU 기울기를 활성화 이전 값 대신 활성화
  값으로 판정할 수 있어서(`z > 0`인 곳과 `h > 0`인 곳이 정확히 같음) `Z1`을
  저장하지 않습니다.
- `cuda_sgd2` — 텐서 두 개짜리 모델의 가중치 갱신 두 번을 커널 하나로 합니다.

그 밖의 CUDA 전용 선택: 64 KB 이하 읽기(손실 값, 출력 하나)는 pinned 스테이징
버퍼를 거쳐 DMA 한 번으로 끝납니다. 1 MiB 이상의 복사는 방향과 상관없이 병렬 레인
네 개(호출 스레드와 처음 쓸 때 띄우는 보조 스레드 세 개)로 나뉩니다. 레인마다 자기
몫을 자기 스트림에서 pinned 메모리를 거쳐 옮기므로, 한 청크의 DMA가 다음 청크의
복사와 겹칩니다. H200 호스트에서 16 MB를 0.45–0.7 ms에 옮기며, 스레드 하나로 복사하는
드라이버 자체의 pageable 메모리 경로는 1.05 ms가 걸립니다. 블록 하나짜리 리덕션은 결과를 바로 저장하므로
`cuda_mse_grad`/`cuda_sum`에 0으로 채우는 memset이 필요 없습니다. cuBLAS에는
32 MB 고정 작업 공간을 주어 그래프 캡처가 가능합니다.

| 분류 | 함수 |
|---|---|
| 장치 | `cuda_available`, `cuda_device_name`, `cuda_sync` |
| 버퍼 | `CudaBuf::alloc`, `CudaBuf::upload`, `cuda_write`, `cuda_read`, `CudaBuf::free`; 바이트 단위 복사 `cuda_copy_to_device(dev, host, bytes)`, `cuda_copy_to_host` |
| 행렬곱 | `cuda_gemm(a, ta, b, tb, c, m, n, k)`, `cuda_gemm_relu`(같은 인자, ReLU 융합) |
| 원소별 | `cuda_add`/`sub`/`mul`/`div`/`pow`, `cuda_scale`, `cuda_relu`, `cuda_gelu`(tanh 근사), `cuda_exp`/`log`/`sqrt`, `cuda_relu_grad` |
| 학습 | `cuda_sgd`, `cuda_sgd2`, `cuda_mse_grad(y, t, dy, loss)`, `cuda_sum` |
| fp16 / bf16 (`gpu_rocm.h`의 `RocmHalfBuf`와 같은 `CudaHalfBuf`) | `CudaHalfBuf::alloc`/`upload`/`write`/`read`/`free`, `cuda_to_half`/`cuda_from_half`, `cuda_half_gemm`(텐서 코어, fp32 누적), `cuda_half_gemm_relu`, `cuda_half_gemm_f32`, `cuda_half_relu`, `cuda_half_relu_grad`, `cuda_half_mse_grad`(손실은 fp32), `cuda_half_sgd`, `cuda_half_sgd2`. 16비트 저장, fp32 계산, 저장할 때 한 번 반올림(`float16.h`와 비트 단위로 같음) |
| 혼합 정밀도 | `cuda_` 이름의 [ROCm 혼합 정밀도 연산](#gpu-mixed-precision): `cuda_half_convert`, `cuda_to_half_many` / `cuda_from_half_many`, `cuda_half_unscale_many` / `cuda_unscale_many`, `cuda_zero`, `cuda_mp_sgd` / `cuda_mp_adam`, `cuda_mp_adam_many`(모델 전체의 Adam 한 스텝, 런치마다 텐서 24개), `cuda_half_gemm_strided`(strided-batched 16비트 GEMM), `cuda_half_add` / `sub` / `mul` / `scale` / `axpy`, `cuda_half_bias`, `cuda_half_maxpool2d` / `cuda_half_avgpool2d`. 큐에 들어가므로 `CudaGraph`에 기록할 수 있습니다. H200에서(bf16, PyTorch 2.10 대비): bf16 사본을 포함한 GPT-2 small AdamW 한 스텝이 `cuda_mp_adam_many`로 1.11 ms, fused `torch.optim.AdamW` + `_foreach_copy_`로 1.61 ms; 언스케일은 `_amp_foreach_non_finite_check_and_unscale_`보다 1.1배 빠르고, fp32 → bf16 변환과 16비트 덧셈은 PyTorch와 7% 이내, 풀링과 바이어스 + ReLU는 1.3–1.6배 빠름 |
| 디코딩(한 번에 토큰 하나) | `cuda_half_gemv(w, x, y, n, k)`: fp16/bf16 가중치 `[n][k]`와 fp32 벡터로 `y = w x`. 출력 행마다 워프 1–4개, `k`가 8의 배수면 16바이트 스트리밍 로드(`ld.global.nc.L1::no_allocate`, 동시에 4개). H200에서 Gemma 4 E2B의 어텐션·down 프로젝션에서 한 행짜리 `cuda_half_gemm_f32`(cuBLAS)보다 1.3–2.2배 빠르고, 262k 행 LM head에서 1.05배(4.47 TB/s), 합친 12288 × 1536 gate/up에서 0.89배입니다. `cuda_rmsnorm(x, w, y, rows, n, eps)`는 행마다, 가중치는 선택, 제자리 가능. [ROCm](#rocm-hip-amd)과 같은 API이며 [Gemma 4 CUDA 엔진](/ko/benchmarks#ml-llm)의 커널에서 가져왔습니다 |
| 그래프 | `cuda_capture_begin` / `cuda_capture_end` → `CudaGraph`, `cuda_graph_launch`, `CudaGraph::free` |
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
cuBLAS 핸들을 만들고 없앴습니다. `tensor_matmul_cuda_blas`를 거친 작은 MLP
벤치마크는 H200에서 학습에 83초, 추론에 816초가 걸렸습니다. 지금은 호스트 배열
함수도 백엔드의 컨텍스트, 스트림, 커널을 같이 씁니다.

### CUDA의 합성곱(cuDNN)과 FFT(cuFFT) {#cuda-cudnn-cufft}

```c
#include <std/ml/gpu_cudnn.h>   // 링크: -lcudnn + gpu_cuda 라이브러리
#include <std/dsp/fft_cuda.h>   // 링크: -lcufft + gpu_cuda 라이브러리
```

`gpu_cuda.h` 위에 얹은 라이브러리 두 개입니다. 같은 스트림에 작업을 넣으므로
`CudaBuf` 연산과 순서가 지켜지고 `CudaGraph`에 기록할 수도 있습니다. std
아카이브에서 각각 별도 오브젝트 파일이라, 쓸 때만 링크하면 됩니다.

**`gpu_cudnn.h`**는 [CNN](#cnn)과 [U-Net](#u-net)을 GPU에서 돌립니다. 텐서는
NCHW 순서의 `CudaBuf`로, `FeatureMap`의 CHW 레이아웃 앞에 배치 축을 붙인 것입니다.
모양마다(ReLU 유무별로) 첫 합성곱에서 cuDNN 그래프 API로 합성곱 → + 바이어스
(→ ReLU)를 융합한 그래프를 만듭니다. cuDNN 휴리스틱이 제안한 엔진 구성들과 레거시
API의 최선 알고리즘(`cudnnFindConvolutionForwardAlgorithm`)을 그 호출의 버퍼로
시간을 재고, 가장 빠른 것을 전용 워크스페이스와 함께 캐시합니다. 이 첫 호출은
그래프 캡처 밖에서 해야 합니다. 이후 호출의 호스트 비용은 몇 마이크로초입니다. 레거시
`cudnnConvolutionForward`는 cuDNN 9에서 호출마다 CPU를 약 40 µs 씁니다. 합성곱은
`cuda_set_tf32(1)`을 켜지 않는 한 순수 fp32로 돕니다. cuBLAS GEMM과 같은
스위치입니다.

| 분류 | 함수 |
|---|---|
| 레이어 | `CudaConv2D::upload(&Conv2D)` → `CudaConv2D`, `CudaConv2D::free`, `cuda_conv_out_dim`; `CudaHalfConv2D::upload(&Conv2D, dtype)` → `CudaHalfConv2D`(가중치를 fp16 / bf16으로 반올림), `CudaHalfConv2D::free` |
| 큐 연산 | `CudaConv2D::forward(x, y, batch, h, w)`, `CudaConv2D::forward_relu` (합성곱 + 바이어스 + ReLU 융합), `CudaHalfConv2D::forward` / `CudaHalfConv2D::forward_relu`(16비트 `CudaHalfBuf`, fp32 계산: fp32와 같은 융합 합성곱 + 바이어스 (+ ReLU) cuDNN 그래프 계획으로, 엔진 구성을 모두 시간 재어 고름; 1×1 stride-1 합성곱은 strided-batched cuBLAS GEMM 한 번과 융합 바이어스 커널; H200에서 NCHW bf16, 배치 32일 때 `cudnn.benchmark`를 켠 PyTorch `conv2d` + `relu_`보다 1.2–1.5배 빠름), `cuda_maxpool2d`, `cuda_avgpool2d`, `cuda_concat_channels` (strided 복사 커널 두 번); `gpu_cuda.h`에 `cuda_upsample2x_nearest`, `cuda_copy`, `cuda_copy_rows(src, srcOff, srcStride, dst, dstOff, dstStride, rowLen, rows)` (커널 하나로 하는 2D strided 복사) |
| U-Net | `CudaUNet::new(&UNet, batch, h, w)` → `CudaUNet` (가중치와 모든 활성값을 미리 할당), `CudaUNet::forward(x, y)`, `CudaUNet::free` |
| 호스트 배열 | `conv2d_forward_cuda`, `unet_forward_cuda`: `Conv2D::forward`/`UNet::forward`와 같은 시그니처. 호출마다 가중치를 올리고, 쓸 수 있는 GPU가 없으면 CPU 구현으로 돌아갑니다 |

**`fft_cuda.h`**는 [`fft.h`](/ko/stdlib/dsp#fft-fast-fourier-transform-radix-2-cooley-tukey)의
GPU판입니다. `fft.h`와 달리 2의 거듭제곱이 아닌 길이도 받습니다. `fft.h`처럼
순변환은 정규화하지 않고 역변환은 1/N을 포함합니다.

| 분류 | 함수 |
|---|---|
| 호스트 배열, `double` | `cuda_fft`, `cuda_ifft`, `cuda_fft_batch(data, n, batch, inverse)`, `cuda_fft2d`, `cuda_ifft2d` (`imaging.h`의 `fft2d`) |
| 큐 연산, fp32 `CudaBuf` | `cuda_fft_c2c(in, out, n, batch, inverse)` (re/im 교차 배치), `cuda_fft_r2c` (실수 n개 → 복소수 n/2+1개), `cuda_fft_c2r` (cuFFT처럼 입력을 덮어씀) |

`tests/stdlib/cuda`는 두 라이브러리를 CPU 코드와 비교합니다: stride 1과 2,
배치를 쓴 `Conv2D::forward`, 풀링·업샘플·연결, U-Net 전체, `fft`/`ifft`,
2의 거듭제곱이 아닌 길이에 대한 `dft`, 배치 변환과 2D 변환, fp32 장치 변환.
그래프로 합성곱을 재실행하는 경우, TF32를 켠 경우, 다른 버퍼로 실행하는 경우,
크고 길이가 어중간한 호스트 전송(아직 이전 내용을 읽는 커널 뒤에 쓰기를 큐에
넣는 경우 포함)도 검사합니다.

H200에서 큐 연산은 배치 합성곱(이미지 32장: fp32에서 0.164 ms, PyTorch-CUDA
0.257 ms)과 U-Net(배치 1: 0.175 ms, PyTorch 0.206 ms, 그래프 하나로는
0.164 ms)에서 PyTorch-CUDA보다 빠릅니다. 이미지 한 장짜리 합성곱 하나는
PyTorch가 더 빠릅니다(0.022 ms 대 0.034 ms). 장치 FFT는 PyTorch를 거친 cuFFT와
같은 속도입니다. [벤치마크](/ko/benchmarks#ml-convolution)를 보세요.

### CUDA의 어텐션 {#cuda-attention}

```c
#include <std/ml/gpu_cuda_attention.h>   // 링크: gpu_cuda 라이브러리 (cuDNN은 실행 중에 불러옴)
```

[`flash_attention_forward`와 `linear_attention_forward`](#attention)의 배치·멀티헤드 버전을 `CudaBuf` 위에서, `gpu_cuda`의 스트림에 쌓아 실행합니다(다른 연산과 순서가 맞고 `CudaGraph`에 기록할 수 있습니다). 순전파만 있습니다.

- **Flash 어텐션**, 점수 행렬 없이 `softmax(Q Kᵀ · scale) V`. fp16/bf16은 `libcudnn.so.9`를 `dlopen`으로 열 수 있으면 cuDNN fused attention 엔진으로 가고(SafeC가 cudnn-frontend의 `sdpa()`와 같은 그래프를 만들어 모양마다 계획을 캐시), 아니면 이 라이브러리의 FlashAttention-2 커널이 텐서 코어(`mma.sync`, sm_80+, head dim ≤ 128)에서 돌립니다. fp32는 그 커널을 3xTF32로 돌립니다(fp32 수준 오차, ~2e-7). head dim 256과 오래된 GPU는 같은 알고리즘의 CUDA 코어 커널을 씁니다. `cuda_attention_use_cudnn(0)`이면 자체 커널만 씁니다.
- **Linear 어텐션**, φ(x) = elu(x) + 1, O(n d²): head dim 128까지는 헤드별 패스가 상태 φ(K)ᵀV를 레지스터에 둔 채 64키 청크를 훑고(causal이면 각 청크 앞에서 저장), 그다음 모든 청크의 출력을 한 번에. 데이터 형식과 관계없이 정확한 fp32로 계산합니다.
- **모양**: `cuda_flash_attention` / `cuda_linear_attention` / `_f32` 형태는 [ROCm](#rocm-attention)의 `rocm_flash_attention` / `rocm_linear_attention`과 같은 인자를 받습니다(fp32, `[heads][seq][dim]`). `_ex`와 `cuda_half_` 형태는 `CudaAttnShape`(배치, grouped-query K/V — `kvHeads`가 `heads`를 나눔, sliding window)를 받습니다. head dim은 32, 64, 96, 128, 256.
- **마스킹**: 쿼리 i는 위치 `kvLen - seqLen + i`에 있습니다(마지막 `seqLen`개 키가 쿼리 자신의 토큰이고 그 앞은 캐시, [`mps_flash_attn_bf16`](#mps-attention)과 같음). `causal`은 뒤쪽 키를 가리고, `window`(flash만)는 `window`칸 이상 앞의 키도 가립니다.
- **디코딩**: `(heads / kvHeads) · seqLen ≤ 16`이면(KV 캐시에 대해 새 토큰 하나나 몇 개) 모든 데이터 형식의 flash 어텐션이 대신 split-KV(flash-decoding) 커널 쌍으로 돕니다. 키를 32의 배수 길이 조각으로 나눠 SM마다 블록 8개쯤을 띄우고, 각 블록은 같은 kv head를 쓰는 모든 쿼리 head를 위해 키와 값을 한 번씩만 읽으며, 두 번째 커널이 부분 softmax를 합칩니다. H200에서 이 경로가 대신하는 cuDNN 경로와 비교하면, kv head 1개에 쿼리 head 8개(head dim 256)인 bf16 32k 토큰 디코딩이 1.40 ms에서 36 µs로, head dim 128의 32/8 head가 533 µs에서 54 µs로 줄어듭니다. PyTorch `scaled_dot_product_attention`(텐서 코어 위 FlashAttention-2 split-KV 커널)이 아직 1.1–1.8배 빠릅니다. `cuda_attention_use_decode(0)`이면 이 경로를 끕니다.
- (커널, 데이터 형식, head dim) 조합마다 첫 호출에서 NVRTC가 컴파일하고, cuDNN 계획과 linear 어텐션·디코딩 작업 공간도 그때 만들어지므로, 그래프를 캡처하기 전에 모양마다 한 번 호출하세요.

| 분류 | 함수 |
|---|---|
| Flash | `cuda_flash_attention(q, k, v, out, heads, seqLen, kvLen, headDim, scale, causal)`, `cuda_flash_attention_ex(q, k, v, out, shape, scale, causal)`, `cuda_half_flash_attention`(fp16/bf16 `CudaHalfBuf`) |
| Linear | `cuda_linear_attention(q, k, v, out, heads, seqLen, kvLen, headDim, causal)`, `cuda_linear_attention_ex`, `cuda_half_linear_attention` |
| 호스트 배열 | `cuda_flash_attention_f32`, `cuda_linear_attention_f32`(업로드, 계산, 읽기) |
| 제어 | `cuda_attention_head_dim_ok`, `cuda_attention_use_cudnn`, `cuda_attention_use_decode` |

H200에서 fp16/bf16 flash 어텐션은 PyTorch `scaled_dot_product_attention`과 같고(둘 다 cuDNN), fp32는 head dim 64에서 1.34배 빠르고 128에서 같으며(0.97–1.05배), linear 어텐션은 PyTorch보다 최대 2.6배, flash-linear-attention보다 최대 2.1배 빠릅니다(head dim 128 fp32 non-causal에서는 PyTorch의 TF32 einsum과 같음). [벤치마크](/ko/benchmarks#ml-attention)를 보세요. `gpu_cuda.h`에는 이런 식으로 자체 NVRTC 커널을 가져오는 라이브러리를 위해 `cuda_compute_capability()`, `cuda_sm_count()`, `cuda_load_module(source, name)`이 추가되었습니다.

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
| 계층 | `RocmConv2D::upload(&Conv2D)` → `RocmConv2D`, `RocmConv2D::free`, `rocm_conv_out_dim`; `RocmHalfConv2D::upload(&Conv2D, dtype)` → `RocmHalfConv2D`(가중치를 fp16 / bf16으로 반올림), `RocmHalfConv2D::free` |
| 큐 연산 | `RocmConv2D::forward(x, y, batch, h, w)`, `RocmConv2D::forward_relu`, `RocmHalfConv2D::forward` / `RocmHalfConv2D::forward_relu`(16비트 `RocmHalfBuf`: fp32 누적으로 MIOpen의 half / bfloat16 합성곱, 이어서 융합 16비트 바이어스 커널), `rocm_maxpool2d`, `rocm_avgpool2d`, `rocm_concat_channels`(배치 전체를 커널 하나로); `gpu_rocm.h`의 `rocm_upsample2x_nearest`, `rocm_upsample2x_concat`, `rocm_copy` |
| U-Net | `RocmUNet::new(&UNet, batch, h, w)` → `RocmUNet`, `RocmUNet::forward(x, y)`(디코더 단계마다 연결 버퍼로 바로 업샘플), `RocmUNet::free` |
| 호스트 배열 | `conv2d_forward_rocm`, `unet_forward_rocm`. GPU를 쓸 수 없으면 CPU로 대체 |

**`fft_rocm.h`**는 hipFFT(내부적으로 rocFFT)로 임의 길이의 변환을 실행합니다.
순방향 변환은 정규화하지 않고 역방향 변환은 1/N을 포함합니다.

| 그룹 | 함수 |
|---|---|
| 호스트 배열, `double` | `rocm_fft`, `rocm_ifft`, `rocm_fft_batch(data, n, batch, inverse)`, `rocm_fft2d`, `rocm_ifft2d` |
| 큐, fp32 `RocmBuf` | `rocm_fft_c2c(in, out, n, batch, inverse)`, `rocm_fft_r2c`, `rocm_fft_c2r`(입력을 덮어쓸 수 있음) |

`tests/stdlib/rocm/rocm_dnn_fft_check.sc`는 CUDA 모음과 같은 검사를 CPU 코드와
비교해 실행합니다(MI100에서 검사 73개): 스트라이드 1과 2, 배치, ReLU, 그래프
재생 합성곱; 풀링, 업샘플, 연결; U-Net 전체; 모든 FFT 형태; 같은 반올림 입력에서
`cnn.h`와 비교한 fp16·bf16 합성곱과 풀링. MIOpen에는 모든 GPU에서 쓸 수 있는
bfloat16 풀링 solver가 없으므로(MI100에는 없음), 16비트 풀링은 `gpu_rocm.h`의 HIP
커널입니다.

MI100에서 64채널 합성곱 하나는 0.027 ms(PyTorch-ROCm 0.063 ms), 배치 1 U-Net은 0.227 ms(0.315 ms)이며, 배치 16–32에서는 2% 이내입니다. 장치 상주 FFT는 PyTorch를 거칠 때보다 1.4–1.7배 빠릅니다. [벤치마크](/ko/benchmarks#ml-convolution)를 참고하세요.

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

호스트 배열은 첫 원소에 대한 참조로 넘깁니다. 아래의 장치 버퍼는 CUDA, SPIR-V, WebGPU에서는 메모리가 GPU 드라이버에 속하므로 불투명한 `&void` 핸들이고, Metal에서는 ROCm의 `RocmBuf`처럼 `MpsBuf`(아래)입니다.

**영구 버퍼**는 가중치를 호출 사이에 장치에 남겨 두어 학습 루프가 다시 업로드하지 않게 합니다:

```c
// <p> = cuda | spirv | webgpu
?&void <p>_upload_persistent(const &float data, unsigned long bytes);   // null on failure
void   <p>_release_persistent(&void buf);
int    <p>_persistent_available();
int    <p>_matmul_f32_persistent(&void bufA, &void bufB, &float out,
                                unsigned long M, unsigned long K, unsigned long N); // cuda, webgpu
int    cuda_sgd_update_f32(&void devW, &void devGrad, float lr, unsigned long n);    // in place
int    webgpu_sgd_update_f32(&void bufW, &void bufGrad, float lr, unsigned long n);
```

Metal의 장치 버퍼는 `struct MpsBuf`입니다. `MTLBuffer` 핸들, 바이트 크기, 원소 타입(`0` = fp32, `MPS_FP16`, `MPS_BF16`)을 담습니다. 메모리를 CPU와 공유하므로 `read`와 `write`는 전송 없는 복사입니다. 0으로 초기화한 `MpsBuf`는 무효이고, 모든 연산은 무효 버퍼에 0을 반환하며, 무효 버퍼의 `free()`는 아무것도 하지 않습니다.

```c
struct MpsBuf {
    void* handle; unsigned long bytes; int dtype;
    int  valid() const;
    void free();                                          // 버퍼 풀로 반환
    int  read(&void dst, unsigned long bytes) const;      // 이 버퍼에 쓰는 배치가 모두 끝난 뒤 유효
    int  write(const &void src, unsigned long bytes);
    static struct MpsBuf alloc(unsigned long n, int dtype);                 // 원소 n개
    static struct MpsBuf upload(const &float host, unsigned long n);        // fp32 값 n개
    static struct MpsBuf upload_f16(const &float host, unsigned long n);    // CPU에서 반올림; upload_bf16도 있음
    static struct MpsBuf upload_bits(const &unsigned short bits, unsigned long n, int dtype);  // 원시 fp16 / bf16
    static struct MpsBuf linear_attn_state(unsigned long numHeads, unsigned long headDim);
    static struct MpsBuf batch_last_output();             // 열린 배치 소유: free() 금지
};
int mps_matmul_f32_persistent(struct MpsBuf bufA, struct MpsBuf bufB, &float out,
                              unsigned long M, unsigned long K, unsigned long N);
int mps_sgd_update_f32_chained(struct MpsBuf bufW, struct MpsBuf bufGrad, float lr, unsigned long n);  // in place
```

**반정밀도**(MPS, CUDA, WebGPU): fp16/bf16으로 업로드해 네이티브로 계산하고, 원시 16비트 값을 읽어 옵니다(아래 `float16.h`로 변환). ROCm은 16비트 데이터를 대신 `RocmHalfBuf`에 담습니다. 위의 ROCm 표를 참고하세요.

```c
?&void cuda_upload_f16_persistent(const &float data, unsigned long n);   // also _bf16; webgpu_ (f16); Metal: MpsBuf::upload_f16
int    mps_matmul_f16_persistent(struct MpsBuf bufA, struct MpsBuf bufB, &unsigned short out,
                                unsigned long M, unsigned long K, unsigned long N); // also _bf16; cuda_, webgpu_ (&void)
int    cuda_sgd_update_f16(&void devW, &void devGrad, float lr, unsigned long n);    // also _bf16; webgpu_ (f16)
int   mps_matmul_f16(const &float a, const &float b, &float out,
                     unsigned long M, unsigned long K, unsigned long N);   // also mps_matmul_bf16
int   mps_relu_f16(const &float a, &float out, unsigned long n);           // also mps_relu_bf16
```

**MPS 배칭.** `mps_batch_begin()`과 `mps_batch_end()` 사이에서는 MPS 연산이 각자 커밋하고 기다리는 대신 하나의 공유 커맨드 버퍼에 인코딩되므로, 순전파 + 손실 + 역전파를 묶은 스텝이 GPU 제출 한 번으로 실행됩니다. `*_chained` 변형은 이전 연산의 출력 버퍼(`MpsBuf::batch_last_output()`)를 호스트 메모리를 거치지 않고 입력으로 받습니다. `mps_batch_submit()`은 열린 배치에 지금까지 인코딩된 작업을 기다리지 않고 GPU로 보내므로, 긴 배치에서 인코딩과 실행이 겹칩니다.

```c
void  mps_batch_begin();
void  mps_batch_end();                      // commit, wait, run finalizers
void  mps_batch_submit();                   // send what's encoded so far, don't wait
int   mps_batch_is_active();
generic<T> void mps_batch_register_finalize(fn void(?&T) finalizeFn, ?&T ctx);  // runs after mps_batch_end's wait

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

`_ab` 접미사는 두 입력이 모두 이미 GPU 버퍼(값으로 넘기는 `MpsBuf`)라는 뜻입니다. 각 함수의 인자 순서는 `std/ml/gpu_mps.h`를 참고하세요.

### MPS에서의 어텐션 {#mps-flash-linear-attention}

```c
#include <std/ml/gpu_mps.h>   // Apple GPU; -framework Metal -framework Foundation 링크
```

[CUDA](#cuda-attention)와 같은 배치 멀티헤드 [`flash_attention_forward`와
`linear_attention_forward`](#attention)를 Metal 버퍼(`MpsBuf::upload`,
`MpsBuf::upload_f16` / `upload_bf16`, `MpsBuf::alloc`)에서 실행합니다.
순방향 전용이며 모든 계산은 fp32입니다.

- **Flash 어텐션**, 점수 행렬 없이 `softmax(Q Kᵀ · scale) V`: head dim 256까지는 GPU의 8×8 simdgroup 행렬 유닛에서 FlashAttention-2로(Q 블록은 레지스터, K/V 타일은 threadgroup 메모리, 소프트맥스는 프래그먼트 위에서 온라인), 512에서는 simdgroup 리덕션 커널로 계산합니다. fp16은 half 정밀도로 곱하고, bf16은 M1 계열에 bf16 행렬 유닛이 없어 타일을 올릴 때 fp32로 바꿉니다.
- **Linear 어텐션**, φ(x) = elu(x) + 1, O(n d²). head dim 64와 128은 행렬 유닛에서 청크 병렬로 돕니다 — 구간별 상태 φ(K)ᵀV, 64행 청크에 걸친 prefix(causal) 또는 전체 합(non-causal), 그다음 모든 청크의 출력을 한 번에 계산하며, 상태는 라이브러리가 유지하는 스크래치 버퍼에 둡니다. 다른 head dim, `causal`과 KV 캐시 오프셋을 함께 쓰는 경우, 아래의 순환 형태는 헤드마다 시퀀스를 차례로 훑습니다.
- **형태**: `mps_flash_attention` / `mps_linear_attention` / `_f32`는 `rocm_`, `cuda_`와 같은 인자(fp32, `[heads][seq][dim]`)를 받고, `_ex` 형태는 `MpsAttnShape`(배치, grouped-query K/V, 슬라이딩 윈도)와 dtype(`0` = fp32, `MPS_FP16`, `MPS_BF16`)을 받습니다. head dim 32, 64, 96, 128, 256, 512.
- **마스킹**: CUDA와 같습니다 — 쿼리 i는 위치 `kvLen - seqLen + i`에 있고, `causal`은 뒤쪽 키를, `window`(flash만)는 `window`칸 이상 앞의 키도 가립니다. `scale` ≤ 0이면 1/√headDim입니다.
- **배칭**: `mps_batch_begin()`/`mps_batch_end()` 안에서는 배치의 커맨드 버퍼 하나에 인코딩되고, 밖에서는 호출마다 실행하고 기다립니다.

| 묶음 | 함수 |
|---|---|
| Flash | `mps_flash_attention(q, k, v, out, heads, seqLen, kvLen, headDim, scale, causal)`, `mps_flash_attention_ex(q, k, v, out, shape, dtype, scale, causal)` |
| Linear | `mps_linear_attention(q, k, v, out, heads, seqLen, kvLen, headDim, causal)`, `mps_linear_attention_ex(q, k, v, out, shape, dtype, causal)` |
| 호스트 배열 | `mps_flash_attention_f32`, `mps_linear_attention_f32` (업로드, 계산, 읽어 오기) |
| 버퍼 | `MpsBuf::alloc(n, dtype)`, `buf.read(dst, bytes)`, `mps_attention_head_dim_ok(headDim)` |

M1 Pro에서 fp32 flash 어텐션은 MLX의 fused 어텐션보다 1.01–1.42배, PyTorch `mps` 장치보다 1.13–1.70배 빠르고, head dim 128의 bf16은 MLX와 같으며, fp16과 head dim 64의 bf16은 이들의 0.80–1.02배(2.6–3.4 TFLOPS)이고, linear 어텐션은 벡터화한 PyTorch와 MLX보다 2.0–4.4배 빠릅니다. [벤치마크](/ko/benchmarks#ml-attention)를 참고하세요.

`tests/stdlib/mps/run.sh`는 `mps_attention_check`를 빌드해 모든 경로(두 flash 커널, 청크 병렬과 순차 linear 커널, 세 데이터 타입, GQA, KV 캐시 오프셋, 슬라이딩 윈도, 호스트 배열 형태)를 배정밀도 참조와 attention.h의 CPU 함수와 비교합니다: 95개 검사.

### MPS 어텐션과 트랜스포머 연산 {#mps-attention}

Apple GPU에서 트랜스포머 레이어를 실행하기 위한 bf16 구성 요소입니다. 텐서는 원시 bf16 비트(`unsigned short`, `float16.h`로 변환)이며 행 우선이고, 각 행에 헤드가 나란히 놓입니다 — 레이어의 프로젝션이 만드는 배치 그대로입니다: Q와 출력은 `[rows, numHeads*headDim]`, K/V는 `[rows, numKvHeads*headDim]`입니다. `numHeads`는 `numKvHeads`의 배수여야 합니다(그룹 쿼리 어텐션). flash와 linear 함수는 `mps_flash_attention_ex`와 [같은 커널](#mps-flash-linear-attention)에서 돌고(head dim 32–512), 디코드 커널은 64, 128, 256, 512를 받습니다. 다른 MPS 함수와 마찬가지로 실행할 수 없으면(장치 없음, 지원하지 않는 형태) 0을 반환하므로 호출자가 CPU로 대체할 수 있습니다.

| 함수 | 계산 |
|---|---|
| `mps_attn_batch_bf16` | 배치 전체와 모든 헤드에 대해, 시퀀스마다 새 쿼리 토큰 하나를 그 시퀀스의 KV 캐시와 계산합니다 — 디코드 스텝. KV 길이 제한이 없고, 슬라이딩 윈도 밖의 키는 건너뜁니다. |
| `mps_flash_attn_bf16` | 임의 개수의 쿼리 행(프리필)에 대한 `flash_attention_forward`의 GPU 버전으로, 위의 FlashAttention-2 커널에서 돕니다. causal과 슬라이딩 윈도 마스킹을 지원합니다. |
| `mps_linear_attn_bf16` | `linear_attention_forward`(`phi(x) = elu(x)+1`)의 GPU 버전, non-causal 또는 causal. |
| `mps_linear_attn_bf16_chained` | 순환 상태를 GPU에 두고 제자리에서 갱신하는 causal 선형 어텐션 — 프리필을 한 번 하고, 이후 디코드 스텝마다 토큰 하나를 시퀀스 길이와 무관한 비용으로 처리합니다. |

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

// 순환 선형 어텐션 상태(헤드마다 S와 z), GPU에 상주.
struct MpsBuf MpsBuf::linear_attn_state(unsigned long numHeads, unsigned long headDim);  // 0으로 초기화
unsigned long mps_linear_attn_state_bytes(unsigned long numHeads, unsigned long headDim);
int mps_linear_attn_bf16_chained(struct MpsBuf bufQ, struct MpsBuf bufK, struct MpsBuf bufV, struct MpsBuf bufState,
                                 &unsigned short out,
                                 unsigned long seqLen, unsigned long numHeads,
                                 unsigned long numKvHeads, unsigned long headDim);

// 레이어 나머지를 위한 체인 bf16 원소 단위/정규화 연산:
int mps_gated_gelu_bf16_chained(struct MpsBuf bufRaw, &unsigned short out, unsigned long rows, unsigned long intermediateSize);
int mps_add_bf16_chained(struct MpsBuf bufA, struct MpsBuf bufB, &unsigned short out, unsigned long n);       // 잔차 연결
int mps_gelu_mul_bf16_chained(struct MpsBuf bufA, struct MpsBuf bufB, &unsigned short out, unsigned long n);  // gelu(a) * b
int mps_rmsnorm_bf16_chained(struct MpsBuf bufX, struct MpsBuf weightBuf, &unsigned short out,
                             unsigned long rows, unsigned long cols, float eps);
```

- **마스킹.** `causal`이면 쿼리 `i`는 위치 `kvLen - seqLen + i`에 놓입니다 — 마지막 `seqLen`개의 키가 쿼리 자신의 토큰이고 그 앞은 캐시입니다 — 그리고 자기 위치 이하의 키만 봅니다. `isSliding`은 쿼리보다 `slidingWindow` 이상 뒤에 있는 키도 가립니다. `mps_attn_batch_bf16`의 `cacheLens[b]`는 새 토큰을 포함한 각 시퀀스의 KV 길이입니다.
- **스케일.** `mps_flash_attn_bf16`은 `q·k`에 `scale`을 곱합니다: `flash_attention_forward`와 맞추려면 `1/sqrt(headDim)`을, q를 미리 스케일하는 모델이면 `1.0`을 넘기세요. 디코드 커널은 스케일을 적용하지 않습니다. 선형 어텐션은 `phi(q)·z`로 나누는 과정에서 q의 스케일이 상쇄되므로 필요 없습니다.
- **배칭.** 원샷 함수는 열린 배치 안에서도(결과는 `mps_batch_end()`에서 `out`에 채워짐) 단독으로도(반환 시 채워짐) 동작합니다. `mps_attn_batch_bf16_chained`와 `mps_flash_attn_bf16_chained`는 디바이스 버퍼를 받고 출력을 `MpsBuf::batch_last_output()`으로 남기므로, 어텐션 결과를 같은 제출 안에서 출력 프로젝션(`mps_matmul_abt_bf16_persistent`)에 바로 넘길 수 있습니다. `M == 1`인 `mps_matmul_abt_bf16_persistent`는 전용 행렬-벡터 커널을 씁니다.

선형 어텐션 디코드 루프:

```c
struct MpsBuf state = MpsBuf::linear_attn_state(numHeads, headDim);
if (state.valid()) {
    mps_batch_begin();
    mps_linear_attn_bf16_chained(promptQ, promptK, promptV, state, promptOut, promptLen,
                                 numHeads, numKvHeads, headDim);
    mps_batch_end();
    // 생성하는 토큰마다:
    mps_batch_begin();
    mps_linear_attn_bf16_chained(tokQ, tokK, tokV, state, tokOut, 1, numHeads, numKvHeads, headDim);
    mps_batch_end();
    // ...
    state.free();
} else {
    // Metal 장치 없음: CPU의 linear_attention_forward 사용
}
```

M1 Pro에서 8개 시퀀스 × 캐시 토큰 2048개(헤드 8개, 헤드 차원 256)의 디코드 스텝은 약 0.3–0.5 ms, 1024 토큰 causal flash 어텐션 프리필(헤드 8개, 헤드 차원 256)은 약 7 ms가 걸립니다.

### 텐서 래퍼 {#tensor-wrappers}

자체 [디바이스 백엔드](#devices)가 없는 백엔드에서 순전파를 실행하고 CPU 역전파를 재사용하는 자동미분 `FloatTensor` 연산입니다. 장치를 쓸 수 없으면 각각 CPU로 대체됩니다. ROCm·CUDA·Metal 텐서는 대신 모든 연산을 디바이스에서 실행합니다: 텐서를 그곳에 두십시오.

| 헤더 | 함수 | 장치 |
|--------|-----------|--------|
| `tensor_spirv.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_spirv` | Vulkan |
| `tensor_webgpu.h` | `tensor_{add,sub,mul,scale,relu,sum,matmul}_webgpu` | WebGPU |
| `tensor_vdsp.h` | `tensor_{add,sub,mul,sum}_vdsp` | Apple Accelerate vDSP (`-framework Accelerate`). 다른 플랫폼에서는 core의 SIMD 연산 |
| `tensor_blas.h` | `tensor_matmul_blas` | `sgemm`을 거쳐: 링크된 CBLAS(Accelerate / AOCL-BLAS / oneMKL / OpenBLAS), 없으면 내장 커널 |
| `tensor_auto.h` | `tensor_{add,sub,mul,sum,matmul}_auto` | 크기에 따라 core(`sgemm`) / vDSP / BLAS 선택 |

`tensor_auto.h`는 의도적으로 CPU 전용입니다: 측정한 모든 크기(2×2×2부터 128×128×128까지의 행렬곱, 그리고 128×512×1024×256 학습 스텝)에서 BLAS가 (`tensor_matmul`이 `sgemm`으로 바뀌기 전에 측정한) core 루프에 진 적이 없고 MPS가 BLAS를 이긴 적이 없으므로 "auto"는 GPU를 고르지 않습니다. GPU를 쓰려면 [텐서를 그곳에 두십시오](#devices).

### 백엔드 상태 {#backend-status}

| 백엔드 | 원소별 연산 | 행렬곱 (std 커널) | 행렬곱 (벤더 BLAS) | 상태 |
|---|---|---|---|---|
| ROCm / HIP | 실제 동작 (HIP C++, 처음 쓸 때 hiprtc로 컴파일) | 실제 동작 (64×64 LDS 타일 SGEMM) | 실제 동작 (rocBLAS) | MI100에서 **측정함**: `tests/stdlib/rocm`의 검사 325 + 73개와 [MLP 벤치마크](/ko/benchmarks#ml-mlp) |
| CUDA | 실제 동작 (CUDA C++, 처음 쓸 때 NVRTC로 컴파일) | 실제 동작 (64×64 공유 메모리 타일 SGEMM) | 실제 동작 (cuBLAS, GEMM + ReLU는 cuBLASLt) | H200에서 **측정**: `tests/stdlib/cuda`의 216개 검사(cuDNN, cuFFT 포함), [MLP 벤치마크](/ko/benchmarks#ml-mlp), [합성곱/FFT 벤치마크](/ko/benchmarks#ml-convolution) |
| Vulkan/SPIR-V | 항상 0 반환 | 항상 0 반환 | n/a | SPIR-V는 바이너리 IR; 테스트 환경에 glslc/glslangValidator 없음 |
| WebGPU | 실제 동작 (WGSL, 텍스트 IR) | 실제 동작 (WGSL) | n/a | 측정 안 함 — 테스트 환경에 wgpu-native/Dawn 라이브러리 없음 |

SPIR-V 커널은 바이너리 IR이라 Vulkan 백엔드가 완성되려면 SPIR-V 컴파일러(glslc/glslangValidator)가 필요합니다. WebGPU는 텍스트 IR(WGSL)을 받아 전부 작성되어 있지만, 실행할 wgpu-native/Dawn 라이브러리가 없었습니다.

## fp16 / bf16 {#fp16-bf16}

SafeC 타입 시스템에는 네이티브 16비트 부동소수점이 없습니다(`Type.h`의 `TypeKind`에는 `Float32`/`Float64`뿐 — 표준 라이브러리 변경이 아니라 컴파일러 기능이 필요함). 대신 표준적인 우회 방법을 씁니다: fp16/bf16을 `unsigned short`의 원시 비트로 담고, `float`와의 변환을 명시적으로 올바르게 반올림(최근접 짝수 반올림)합니다 . 그 위에 반정밀도 텐서와 혼합 정밀도 학습(`tensor_half.h`, 아래)이 있고, MPS, CUDA, WebGPU, ROCm에서는 네이티브 `half`/`bfloat` GPU 연산을 하며([호스트 배열 API](#host-array-api-mps-cuda-spir-v-webgpu) 참고), 디바이스에서 16비트로 학습합니다([정밀도](#device-precision)).

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
void fp16_to_bf16_bulk(const &unsigned short src, &unsigned short dst, unsigned long n);
void bf16_to_fp16_bulk(const &unsigned short src, &unsigned short dst, unsigned long n);

int         half_cpu_features();        // HALF_CPU_* 비트, 한 번 감지
const char* half_cpu_features_name();   // "F16C, AVX512-BF16", ..., "none"
```

**CPU가 직접 하는 것.** 2012년 이후의 모든 x86-64 CPU는 fp16을 하드웨어로
변환하고(F16C), 모든 AArch64 코어도 마찬가지입니다(FCVTL / FCVTN). bf16은 더 새롭고
선택 사항입니다. Cooper Lake, Sapphire Rapids, Zen 4 이후의 AVX512-BF16, 그리고
Armv8.6 코어(Neoverse V1 / N2, Apple M2 이후)의 FEAT_BF16에 있습니다. fp16 산술
(AVX512-FP16, FEAT_FP16)도 선택 사항입니다. `half_cpu_features()`가 이 CPU에 있는
것을 알려 줍니다.

| 비트 | 뜻 | x86-64 | AArch64 |
|---|---|---|---|
| `HALF_CPU_F16_CONVERT` | fp16 ↔ fp32 명령 | F16C | 항상 |
| `HALF_CPU_F16_ARITH` | fp16 산술 | AVX512-FP16 | FEAT_FP16 |
| `HALF_CPU_BF16_CONVERT` | fp32 → bf16 명령 | AVX512-BF16 | FEAT_BF16 |
| `HALF_CPU_BF16_DOT` | bf16 내적 | AVX512-BF16(`VDPBF16PS`) | FEAT_BF16(`BFDOT`) |

감지는 한 번만 합니다. x86-64에서는 cpuid와 xgetbv, Linux AArch64에서는
`getauxval`, Apple에서는 `sysctl`을 씁니다. `SAFEC_HALF=soft`는 아무것도 없다고
보고하고, 비교할 수 있도록 일괄 변환이 이식 가능한 코드를 쓰게 합니다.

**일괄 변환.**

- fp16: x86-64에서는 F16C, AArch64에서는 FCVTL / FCVTN을 씁니다.
- fp32 → bf16: AVX512-BF16이 있으면 명령 하나에 값 16개를 바꿉니다.
  `VCVTNEPS2BF16`은 fp32 비정규수를 0으로 만들어 버리므로, 비정규수가 든 블록은
  `VFPCLASSPS`로 찾아 소프트웨어로 대신 변환합니다.
- 그 밖에는 bf16 → fp32는 시프트이고 fp32 → bf16은 정수 반올림이며, 둘 다
  컴파일러가 벡터화하는 루프입니다.
- 값이 256K개 이상인 배열은 스레드 풀에 나눕니다.

결과는 NaN이 페이로드를 더 많이 유지할 수 있다는 점을 빼면 스칼라 함수와 비트
단위로 같습니다. 테스트는 두 모드 모두에서 fp16 패턴 65,536개 전부와, 모든 종류의
무작위 fp32 패턴을 비교합니다.

Ryzen 7 7800X3D에서(스레드 하나, 값 16M개, 읽기 + 쓰기 대역폭):

| 변환 | 하드웨어 | `SAFEC_HALF=soft` |
|---|---|---|
| fp32 → fp16 | 47 GB/s | 2.8 GB/s |
| fp16 → fp32 | 43 GB/s | 7.0 GB/s |
| fp32 → bf16 | 53 GB/s | 27 GB/s |
| bf16 → fp32 | 39 GB/s(어느 쪽이든 시프트) | 41 GB/s |

스레드 16개 전부를 쓰면 하드웨어 경로에서 모든 변환이 58–99 GB/s에 이릅니다.


### 반정밀도 텐서 — `tensor_half.h` {#half-precision-tensors}

```c
#include <std/ml/tensor_half.h>
```

두 계층이 있습니다:

- **`HalfTensor`(fp16)와 `BF16Tensor`(bf16)** — 16비트로 저장하는 [`DenseTensor`](#tensor)로, `FloatTensor` 메모리의 절반입니다. 연산과 자동미분은 핵심 텐서 연산 그대로입니다: 피연산자를 float32로 읽고, float32 커널을 실행하고, 결과를 한 번 반올림합니다(`tensor_matmul`은 텐서 코어처럼 각 내적을 float32로 누적합니다). 그래디언트는 float32입니다. 이 헤더는 원소 타입 간 변환, 혼합 정밀도 학습이 쓰는 일괄 헬퍼, 16비트 가중치로 디코딩하는 `gemv`를 더합니다.
- **float32 텐서에 대한 혼합 정밀도.** `FloatTensor::cast`는 순방향에서 값을 fp16/bf16 정밀도로 반올림하고 그래디언트는 그대로 통과시킵니다(straight-through 추정량). 그래서 가중치와 그래디언트는 float32로 유지하면서 그래프를 낮은 정밀도로 계산할 수 있습니다. `LossScaler`는 fp16 학습의 동적 손실 스케일링이며, bf16은 float32와 지수 범위가 같아 필요 없습니다.

```c
#define TENSOR_FP16 1
#define TENSOR_BF16 2

float          half_round(float v, int dtype);          // nearest dtype value, as float32
unsigned short half_encode(float v, int dtype);
float          half_decode(unsigned short bits, int dtype);

// dst = dst의 원소 타입으로 반올림한 src(fp16 <-> bf16을 포함한 모든 쌍);
// 크기가 같아야 함(아니면 0). 그래프 노드가 아님 -- to_float / from_float 참고.
generic<S, D> int tensor_convert_into(const &DenseTensor<S> src, &DenseTensor<D> dst);

// 혼합 정밀도 일괄 변환: fp32 마스터 <-> 16비트 사본, 16비트 그래디언트 되돌리기
generic<S, D> void tensors_convert(const &Vec<&DenseTensor<S>> src, &Vec<&DenseTensor<D>> out);  // 사본을 덧붙임
generic<S, D> int  tensors_convert_into(const &Vec<&DenseTensor<S>> src, &Vec<&DenseTensor<D>> dst);
generic<E>    void tensors_free(&Vec<&DenseTensor<E>> ts);
generic<E>    int  half_grads_accumulate(const &Vec<&DenseTensor<E>> grads, &Vec<&FloatTensor> params,
                                         float invScale);  // 1 = 모두 유한

int HalfTensor::gemv(const &float x, &float y) const;     // y = w x, float32 x / y
int HalfTensor::gemv_batch(const &float x, unsigned long m, &float y) const; // 벡터 m개: x [m][k], y [m][n]
int BF16Tensor::gemv(const &float x, &float y) const;
int BF16Tensor::gemv_batch(const &float x, unsigned long m, &float y) const;

&FloatTensor FloatTensor::cast(int dtype) const;                            // straight-through gradient
&FloatTensor tensor_matmul_lowp(const &FloatTensor a, const &FloatTensor b, int dtype);    // cast(cast(a) @ cast(b))

struct LossScaler {
    float scale;            // multiply the loss by this before FloatTensor::backward()
    float growthFactor;     // 2.0
    float backoffFactor;    // 0.5
    int   growthInterval;   // 2000 clean steps
    int   goodSteps;
};
static struct LossScaler LossScaler::new();                  // scale 65536 (PyTorch GradScaler's defaults)
int  LossScaler::unscale(&FloatTensor p);                  // divide p.grad by scale; 0 if any grad is inf/NaN
int  LossScaler::unscale_all(&Vec<&FloatTensor> params);   // 목록의 모든 텐서
void LossScaler::update(int allFinite);               // grow after clean steps, back off after overflow
```

`HalfTensor`나 `BF16Tensor`의 `data`는 GPU 반정밀도 함수들(`mps_upload_f16_persistent` 등)이 읽고 쓰는 것과 정확히 같은 배치입니다.

`gemv`는 토큰 하나를 디코딩할 때의 행렬-벡터 곱입니다: `w`는 선형 레이어의 2D fp16/bf16 가중치(`[n][k]`), `x`와 `y`는 float32입니다. 가중치를 레지스터에서 넓히므로 행렬의 float32 사본이 없고, CPU의 SIMD 단계에 맞게 벡터화되어 스레드 풀에서 행 단위로 나뉩니다. Ryzen 7 7800X3D(16스레드)에서 bf16 24576 × 1536 곱은 1.0 ms(가중치 76 GB/s, 메모리 대역폭에 가까움)로, float32로 넓힌 가중치에 한 행짜리 `sgemm`을 쓰는 것보다 6배 빠릅니다. fp16은 넓히기를 정수 연산으로 하므로 35 GB/s입니다. [Gemma 4 E2B](/ko/benchmarks#ml-llm)의 CPU 디코딩을 토큰당 4.43 s에서 0.225 s로 줄였습니다. AArch64에서는 NEON 커널이 가중치 8개를 32비트 레인 4개로 읽어 두 절반을 그 자리에서 넓힙니다. M1 Pro(성능 코어 8개)에서 bf16은 95–113 GB/s, fp16은 40–55 GB/s입니다. `gemv_batch`는 짧은 프롬프트의 프리필이나 추측 디코딩 검증을 위해 벡터 `m`개를 한 번에 계산하며, 가중치는 모든 벡터에 대해 메모리에서 한 번만 읽습니다. M1 Pro에서 bf16 24576 × 1536 가중치에 벡터 6개를 곱하는 데 1.65 ms가 걸리며, `gemv`를 6번 호출하면 4.35 ms입니다. 이를 사용해 M1 Pro에서 [Gemma 4 E2B](/ko/benchmarks#ml-llm) CPU 경로는 토큰당 136 ms입니다.

bf16으로 저장하고 계산하기, 자동미분 포함:

```c
#include <std/ml/tensor_half.h>
#include <std/collections/slice.h>
#include <std/io.h>

int main() {
    // 가중치를 bf16으로 저장하고(메모리 절반) bf16으로 행렬곱을 계산한다.
    &BF16Tensor w = BF16Tensor::new_2d(2UL, 2UL, 1);
    &BF16Tensor x = BF16Tensor::new_2d(2UL, 1UL, 0);
    w.set2(0UL, 0UL, 0.5f);  w.set2(0UL, 1UL, -1.0f);
    w.set2(1UL, 0UL, 2.0f);  w.set2(1UL, 1UL, 0.25f);
    x.set1(0UL, 3.0f);       x.set1(1UL, 4.0f);
    &BF16Tensor y = tensor_matmul(w, x);      // f32 누적, bf16 결과
    std::println_float((double)y.at1(0UL));   // -2.5
    std::println_float((double)y.at1(1UL));   // 7

    y.sum().backward();                       // w.grad: float32
    struct Slice<float> g = std::slice_of(w.grad, w.size);
    std::println_float((double)*g.at(1UL));   // 4 (= x[1])
    x.free(); w.free();
    return 0;
}
```

혼합 정밀도 학습 — fp16 순방향, float32 마스터 가중치, 오버플로 시 스텝 건너뛰기:

```c
#include <std/ml/tensor_half.h>
#include <std/collections/slice.h>

// One mixed-precision SGD step: fp16 forward, float32 master weights,
// dynamic loss scaling so small fp16 gradients don't flush to zero.
int train_step(&FloatTensor w, const &FloatTensor x, const &FloatTensor target, &LossScaler scaler, float lr) {
    &FloatTensor y = std::tensor_matmul_lowp(x, w, TENSOR_FP16);
    &FloatTensor diff = std::tensor_sub(y, target);
    &FloatTensor loss = std::tensor_mul(diff, diff).sum();
    loss.scale(scaler.scale).backward();

    int finite = scaler.unscale(w);
    if (finite) {
        struct Slice<float> g = std::Slice::of(w.grad, w.size);
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
    &FloatTensor w = FloatTensor::from_2d(&wv[0], 2UL, 1UL, 1);
    &FloatTensor x = FloatTensor::from_2d(&xv[0], 1UL, 2UL, 0);
    &FloatTensor t = FloatTensor::from_2d(&tv[0], 1UL, 1UL, 0);
    struct LossScaler scaler = LossScaler::new();
    int ok = 1;
    for (int i = 0; i < 20; i = i + 1) ok = ok & train_step(w, x, t, &scaler, 0.05f);
    float pred = w.at1(0UL) + w.at1(1UL) * (float)2.0;
    return (ok && pred > (float)0.95 && pred < (float)1.05) ? 0 : 1;
}
```

### GPU 혼합 정밀도 {#gpu-mixed-precision}

```c
#include <std/ml/gpu_rocm.h>     // ROCm. gpu_cuda.h에는 cuda_ 이름으로 같은 함수가 있다
#include <std/ml/gpu_miopen.h>   // 16비트 합성곱(CUDA에서는 gpu_cudnn.h)
```

혼합 정밀도 학습은 보통 다음을 둡니다.

- fp32 마스터 가중치와 옵티마이저 상태
- 순전파와 역전파에 쓰는 가중치의 16비트 사본
- 손실 스케일이 곱해진 그래디언트

ROCm과 CUDA는 스텝의 모든 부분에 같은 큐 연산을 갖습니다.

| 단계 | ROCm(CUDA에서는 `cuda_`) |
|---|---|
| 여러 텐서의 사본 | `rocm_to_half_many(&f32s, &halves)`, `rocm_from_half_many`: 실행 한 번에 버퍼 최대 32개, 호출마다 dtype 하나 |
| fp16 ↔ bf16 | `rocm_half_convert(src, dst)` |
| 16비트 계산 | `rocm_half_gemm` / `_f32` / `_relu`, `RocmHalfConv2D::forward` / `forward_relu`, `rocm_half_add` / `sub` / `mul` / `scale` / `axpy`, `rocm_half_bias(y, bias, rows, channels, plane, relu)`, `rocm_half_maxpool2d` / `avgpool2d`, `rocm_half_relu` / `_relu_grad` |
| 스케일 되돌리기 | `rocm_half_unscale_many(&g16, &g32, 1 / scale, flag)`(16비트 그래디언트 → fp32), `rocm_unscale_many(&g32, &g32, …)`(fp32, 제자리): inf / NaN이 하나라도 있으면 `flag[0] = 1`. 먼저 `rocm_zero(flag)` |
| 갱신 | `rocm_mp_adam(w32, m, v, g32, w16, lr, β1, β2, eps, weightDecay, decoupled, step, flag)`(Adam / AdamW)와 `rocm_mp_sgd(w32, g32, vel, w16, lr, momentum, weightDecay, flag)`: `nn.h`의 `Optimizer::step`과 같은 산술이며, 반올림한 가중치를 `w16`에도 쓰고, `flag[0] != 0`이면 갱신 전체를 GPU에서 건너뜁니다 |

GPU에서 건너뛰므로, 오버플로가 난 스텝도 갱신 전에 호스트를 오갈 필요가 없습니다.
`LossScaler`를 키우거나 줄이려면 스텝마다 편할 때 4바이트 플래그를 한 번 읽으면
됩니다. bf16은 fp32와 지수 범위가 같아서 보통 손실 스케일링 없이 학습합니다.
`rocm_mp_*`에 무효한 `flag`(`dev == 0`)를 넘기면 건너뛰지 않습니다.

모든 커널은 fp32로 계산하고 저장할 값마다 한 번 반올림합니다. 갱신은 FMA 축약
없이 실행하므로(HIP에서는 `fp contract(off)`, CUDA에서는 `__fmul_rn` 계열
intrinsic) CPU 산술과 결과가 같습니다. `tests/stdlib/rocm/rocm_check.sc`는 MI100에서
다음을 확인합니다.

- 모든 16비트 패턴에서의 fp16 ↔ bf16
- bf16을 거쳐 돌아오는 버퍼 40개
- 스케일 되돌리기와 오버플로 플래그
- CPU와 비교한 Adam·AdamW·모멘텀 SGD 세 스텝(1e-5 이내)과 그 bf16 사본, 그리고
  플래그가 선 스텝이 아무것도 바꾸지 않는지
- 모든 16비트 원소별 연산과 NCHW 바이어스가 비트 단위로 같은지

CUDA 버전은 `tests/stdlib/cuda/cuda_check.sc`에 같은 테스트가 있습니다. 커널은
컴파일되고(clang, sm_80) 테스트도 빌드되지만, 아직 NVIDIA GPU에서 실행하지는
않았습니다.

## 장치 선택 {#device-selection}

텐서의 디바이스가 연산이 실행될 곳을 고릅니다: `t.place(d)`, `t.to(d)`,
`model_to(&m, d)`, `TrainConfig.device`, `device_set_default(d)`,
`device_auto()`([GPU에서 학습](#devices)). 트랜스포머와 디퓨전 블록을
포함해 텐서 연산으로 만든 모든 레이어는 파라미터와 입력의 디바이스에서
실행됩니다.

## LLM 오케스트레이션 {#llm-orchestration}

```c
#include <std/ml/llm.h>
```

- **`LlmClient`** — OpenAI chat-completions 와이어 형식(vLLM 자체
  서버를 비롯한 대부분의 로컬/호스팅 LLM 서버가 구현하는 형식)을
  구사합니다. `client.chat()`은 `<host>:<port>/v1/chat/completions`로
  POST합니다.
- **`PromptTemplate`** — `prompt_template_render(tmpl, vars)`는 `Value`
  객체를 대상으로 `{name}` 치환을 수행합니다.
- **`Chain`** — 고정된 `Value -> Value` 단계 시퀀스.
- **그래프 실행기** — LangGraph 스타일: 이름 붙은 노드들이 공유된
  `Value` 상태를 변환하며, 고정 또는 조건부(라우터 함수) 에지로
  연결되어 `"__end__"`까지 실행됩니다.
- **`Tracer`** — 이름 붙은 입력/출력/타이밍 이벤트를 담는 인메모리,
  JSON으로 덤프 가능한 로그(LangSmith 스타일).

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

다섯 가지 모두 종단 간(end-to-end)으로 검증되었으며, `LlmClient`는
실제 LLM 엔드포인트를 대신하는 실제 로컬 모의(mock) HTTP 서버에 대해
검증되었습니다(`std/rpc/server_fn.h` 자체의 테스트가 쓰는 것과 같은 기법 —
여기서는 어느 것도 실제로 호스팅된 LLM API를 호출하지 않습니다).
