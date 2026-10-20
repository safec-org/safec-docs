# 벤치마크

몇 가지 대표적인 마이크로벤치마크([The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html) 방식)에서 SafeC를 C, C++, Rust, Zig, Go, Python과 비교하고, 표준 라이브러리의 컬렉션, HTTP 서버, ML 텐서, DSP, 할당자도 측정합니다. 머신 한 대, 3회 중 최선입니다: 모든 숫자는 언어 순위가 아니라 "이 머신에서 대략 이 정도"로 읽어 주세요.

## 한눈에 보기 {#at-a-glance}

릴리스 빌드 실행 시간(낮을수록 좋음, 가장 빠른 값 굵게). SafeC의 binary-trees 행은 `region`/`arena` 할당을 씁니다. 힙 버전은 [binary-trees](#binary-trees-allocationdeallocation-stress)에 있습니다.

| 벤치마크 | SafeC | C | C++ | Rust | Zig | Go | Python |
|---|---|---|---|---|---|---|---|
| [fib(37)](#fib37-recursive-fibonacci) | **0.042s** | **0.042s** | **0.042s** | **0.042s** | 0.043s | 0.089s | 2.016s |
| [n-body](#n-body-5-body-orbital-simulation) | **0.098s** | 0.099s | 0.099s | 0.107s | 0.110s | 0.106s | 7.630s |
| [binary-trees](#binary-trees-allocationdeallocation-stress) | **0.132s** | 0.810s | 0.924s | 0.914s | 0.854s | 0.718s | 18.049s |
| [binary-trees, 8 스레드](#multithreaded-binary-trees-8-threads) | **0.053s** | 0.226s | 0.227s | 0.232s | 0.226s | 0.248s | 22.958s |
| [SIMD](#simd-sum-of-squares-over-20000000-f64-values) (명시적 벡터) | 0.075s | 0.077s | 0.074s | 0.077s | **0.072s** | — | — |
| [웹 서비스](#web-service-json-hello-world-endpoint) (req/s, 높을수록 좋음) | **25,303** | 24,620 | 24,845 | 25,075 | 25,030 | 23,199 | 5,096 |

- **머신러닝** (2층 MLP, [자세히](#machine-learning-small-mlp-training-and-inference)): MI100에서 SafeC는 작은 모델을 7.0 ms에 학습하며(PyTorch-ROCm 20.5 ms, fp32), 큰 모델은 7.4 ms입니다(PyTorch-ROCm 10.7 ms). fp16에서 큰 모델은 5.9 ms입니다(PyTorch 9.9 ms). CPU에서는 SafeC가 작은 모델을 9.6 ms에 학습하고(OpenBLAS, PyTorch 14.0 ms), 큰 모델은 36.5 ms입니다(AOCL-BLAS, PyTorch 38.4 ms).
- **NVIDIA H200** ([자세히](#h200-machine-learning)): SafeC의 CUDA 백엔드는 작은 모델을 3.81 ms에 학습하며(PyTorch-CUDA fp32 51.3 ms), 큰 모델은 3.13 ms에 학습합니다(15.9 ms). fp16에서는 큰 모델이 1.90 ms입니다(17.3 ms). Xeon 8570 CPU 결과는 [별도 절](#xeon-8570-classic)에 있습니다.
- **MI100의 합성곱과 FFT** ([자세히](#mi100-conv-fft)): MIOpen으로 SafeC는 64채널 3×3 합성곱 하나를 0.027 ms에 실행하고(PyTorch-ROCm 0.063 ms), 배치 1 U-Net은 0.222 ms입니다(0.314 ms). 배치 16–32에서는 2% 이내입니다. 장치 상주 hipFFT 변환은 PyTorch를 거칠 때보다 1.3–1.7배 빠릅니다.
- **CPU 합성곱과 FFT** ([자세히](#mi100-conv-fft)): Winograd 3×3 합성곱과 이미지별 아레나로 U-Net이 CPU에서 1.35 ms에 실행됩니다(PyTorch 2.68 ms). 2²⁰점 FFT는 0.89 ms입니다(16스레드 FFTW 1.63 ms). 2D, 배치, 실수 변환에서는 FFTW가 1.1–1.6배 빠릅니다.
- **DSP** ([자세히](#dsp-cpu)): 측정한 모든 작업에서 double과 float 모두 `std/dsp`가 SciPy, KFR, Q, madronalib, dasp보다 빠릅니다. 2²⁰ 샘플의 biquad는 0.48 ms(Q 1.69 ms, float), 127탭 스트리밍 FIR은 3.0 ms(SciPy 19.5 ms), 44.1→48 kHz 리샘플링은 3.7 ms(SciPy 9.1 ms)입니다.
- **H200에서의 어텐션** ([자세히](#h200-attention)): cuDNN을 거치는 fp16/bf16 flash 어텐션은 PyTorch와 같고(3% 이내, 최대 690 TFLOPS), 텐서 코어(3xTF32)의 fp32 flash 어텐션은 head dim 64에서 같고 128에서 0.7배입니다. head dim 64의 linear 어텐션은 PyTorch보다 최대 1.6배 빠르고 flash-linear-attention과 같으며, head dim 128에서는 0.45–0.78배입니다.
- **MI100에서의 어텐션** ([자세히](#mi100-attention)): 행렬 코어의 fp32 flash 어텐션이 18–26 TFLOPS로 돕니다. 마스크가 없으면 PyTorch-ROCm보다 1.8–4배, causal 마스크가 있으면 4–10배 빠릅니다. 선형 어텐션은 2–5.4배 빠릅니다.
- **H200에서의 합성곱과 FFT** ([자세히](#h200-conv-fft)): cuDNN을 쓰는 SafeC는 모든 합성곱 행에서 PyTorch-CUDA보다 빠릅니다. 이미지 한 장의 64채널 3×3 합성곱은 0.018 ms(PyTorch 0.023 ms, fp32), 32장 배치는 0.084 ms(0.098 ms, TF32), 배치 16의 U-Net은 0.45 ms(0.56 ms, TF32)입니다. 호스트 배열의 2²⁰점 FFT는 PCIe 복사 두 번을 포함해 1.25 ms입니다(2.05 ms). GPU에 상주하는 데이터의 cuFFT 변환은 PyTorch를 거친 것과 같은 시간이 걸립니다.
- **컬렉션** ([자세히](#collections-stdcollections-throughput-1000000-elements)): `vec_sort`는 `qsort`보다 1.9배, 풀을 쓰는 리스트는 노드마다 `malloc`하는 리스트보다 2.2배 빠르고, `HashMap`은 순수 C의 1.1배 이내, `Vec` push는 1.5배 느립니다.
- **할당자** ([자세히](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree)): 블록이 재사용되면 `std::alloc`이 `malloc`보다 1.8배 빠르고, 슬랩 덕분에 블록 100만 개가 동시에 살아 있을 때도 1.1배 빠릅니다.

## 방법론 {#methodology}

- **머신**: AMD Ryzen 7 7800X3D(8코어 / 16스레드), AMD Instinct MI100(gfx908, 32GB HBM2), Ubuntu 24.04.5, 네이티브 Linux — `safec` 1.0.0 · clang 21.1.8(C/C++ 및 SafeC 백엔드) · Rust 1.100.0-nightly · Go 1.27.2 · Zig 0.16.0 · Python 3.12.3(+ NumPy 2.5.4) · PyTorch 2.9.1+rocm6.4 · TensorFlow(CPU) · OpenBLAS(pthreads) · AOCL-BLAS 5.3.2 · oneMKL 2026.2 · ROCm 6.4(HIP, hiprtc, rocBLAS). FFT/DSP 비교에는 FFTW 3.3.10, KFR 7.2.0, SciPy 1.18.1, RustFFT 6.4.1, dasp 0.11.0, Q와 madronalib(현재 소스)를 썼습니다. 각 서드파티 라이브러리는 자기 벤치마크 프로그램에만 빌드·링크되며, SafeC의 std에는 절대 링크되지 않습니다.
- **빌드 플래그**: C/C++와 SafeC는 `-O2`(SafeC는 LLVM IR로 컴파일한 뒤 `safeguard build --release`처럼 `clang -O2`), `zig build-exe -O ReleaseFast`, `rustc -O`, 순수 `go build`. SafeC 컴파일 시간은 `--no-incremental`로 재서 다른 언어처럼 콜드 컴파일입니다. 디버그 빌드(`-O0`) 시간은 결과 JSON에 있습니다.
- **타이밍**: 3회 실행 중 최선(가장 낮은) 실행 시간. **메모리**: 같은 실행의 최대 RSS를 GNU `/usr/bin/time -f %M`으로 잰 값(벤치마크 프로세스 자신의 최댓값입니다. Linux는 `exec` 후에도 `ru_maxrss`를 유지하므로, 부모의 `wait4()`는 fork한 인터프리터의 최댓값을 보고합니다). 웹 서버의 최댓값은 종료 직전에 `/proc/<pid>/status`에서 읽은 `VmHWM`입니다.
- **정확성**: 결과에 포함하기 전에 모든 바이너리의 출력을 알려진 정답과 비교했습니다.
- **재현**: [`run_bench.py`](/benchmarks/run_bench.py)가 모든 것을 빌드하고 실행해 JSON 파일로 씁니다. 이 페이지의 수치는 [`results/linux-7800x3d-mi100.json`](/benchmarks/results/linux-7800x3d-mi100.json)입니다.
- 소스 파일은 각 결과 아래에 링크되어 있습니다.

## fib(37) — 재귀 피보나치 {#fib37-recursive-fibonacci}

단순 재귀 피보나치 — 순수한 함수 호출 및 정수 산술 오버헤드만 있으며, 할당도 I/O도 없습니다.

| 언어 | 시간 | 최대 메모리 | 컴파일 시간 |
|---|---|---|---|
| SafeC | **0.042s (가장 빠름)** | 1.9 MB | 0.036s |
| C | **0.042s (가장 빠름)** | 1.9 MB | 0.030s |
| C++ | **0.042s (가장 빠름)** | 3.8 MB | 0.049s |
| Rust | **0.042s (가장 빠름)** | 1.9 MB | 0.050s |
| Zig | 0.043s | **0.6 MB (가장 가벼움)** | 8.18s |
| Go | 0.089s | 2.0 MB | **0.025s** |
| Python | 2.016s | 10.4 MB | N/A (인터프리터) |

LLVM을 거치는 언어는 릴리스에서 모두 4% 이내로 모입니다. 차이는 잡음 수준입니다.

::: details 소스
[fib.sc](/benchmarks/fib/safec/fib.sc) · [fib.c](/benchmarks/fib/c/fib.c) · [fib.cpp](/benchmarks/fib/cpp/fib.cpp) · [fib.rs](/benchmarks/fib/rust/fib.rs) · [fib.zig](/benchmarks/fib/zig/fib.zig) · [fib.go](/benchmarks/fib/go/fib.go) · [fib.py](/benchmarks/fib/python/fib.py)
:::

## n-body — 5체 궤도 시뮬레이션 {#n-body-5-body-orbital-simulation}

Benchmarks Game의 고전적인 n-body 테스트(태양/목성/토성/천왕성/해왕성), 2,000,000 스텝. 부동소수점 연산이 많고, 할당이 없으며, 작업 집합이 아주 작습니다. SafeC의 초기 상태(운동량 보정 포함)는 `consteval` 테이블이라 프로그램은 상수에서 시작하며, 시뮬레이션 자체는 다른 언어와 같은 코드입니다.

| 언어 | 시간 | 최대 메모리 | 컴파일 시간 |
|---|---|---|---|
| SafeC | **0.098s (가장 빠름)** | 2.0 MB | 0.044s |
| C | 0.099s | 2.0 MB | 0.043s |
| C++ | 0.099s | 3.8 MB | 0.097s |
| Rust | 0.107s | 2.0 MB | 0.072s |
| Zig | 0.110s | **0.5 MB (가장 가벼움)** | 8.25s |
| Go | 0.106s | 2.0 MB | **0.026s** |
| Python | 7.630s | 10.9 MB | N/A (인터프리터) |

::: details 소스
[nbody.sc](/benchmarks/nbody/safec/nbody.sc) · [nbody.c](/benchmarks/nbody/c/nbody.c) · [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp) · [nbody.rs](/benchmarks/nbody/rust/nbody.rs) · [nbody.zig](/benchmarks/nbody/zig/nbody.zig) · [nbody.go](/benchmarks/nbody/go/nbody.go) · [nbody.py](/benchmarks/nbody/python/nbody.py)
:::

## binary-trees — 할당/해제 스트레스 {#binary-trees-allocationdeallocation-stress}

작은 이진 트리(최대 깊이 18) 수백만 개를 만들고 버립니다 — 산술보다 메모리 관리를 시험합니다. 주 SafeC 항목은 힙 대신 `region`/`arena<R>`(범프 포인터 할당, `arena_reset<R>()`이 영역 전체를 O(1)에 버림)를 씁니다 — 이 워크로드는 전부 수명이 짧고 같은 스코프 안의 할당이라, 바로 영역이 쓰이는 경우입니다. **SafeC (힙)** 행은 같은 프로그램을 `new` / `std::dealloc`으로 바꾼 것으로, 다른 언어의 `malloc`/`Box`/GC와 같은 조건으로 비교합니다.

| 언어 | 시간 | 최대 메모리 | 컴파일 시간 |
|---|---|---|---|
| SafeC (arena) | **0.132s (가장 빠름)** | **25.9 MB (가장 가벼움)** | 0.044s |
| SafeC (힙) | 0.616s | 38.8 MB | 0.043s |
| C | 0.810s | 33.9 MB | 0.039s |
| C++ | 0.924s | 35.9 MB | 0.054s |
| Rust | 0.914s | 34.0 MB | 0.064s |
| Zig | 0.854s | 33.6 MB | 8.22s |
| Go | 0.718s | 36.8 MB | **0.035s** |
| Python | 18.049s | 82.6 MB | N/A (인터프리터) |

힙 버전은 C보다 1.3배 빠릅니다: `std::alloc`은 이 16바이트 노드를 스레드별 슬랩에서 내주고, 그 앞에 해제된 블록의 캐시를 둡니다([메모리 할당](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree) 참고). 슬랩 이전에는 0.713초, 50.9 MB였습니다. 메모리는 여전히 C보다 조금 더 씁니다(38.8 vs 33.9 MB). 블록마다 16바이트 안전 헤더가 붙고, 재사용을 위해 캐시와 여분 슬랩을 남겨 두기 때문입니다.

::: details 소스
[binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc) · [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c) · [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp) · [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs) · [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig) · [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go) · [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)
:::

## 멀티스레드 — binary-trees, 8 스레드 {#multithreaded-binary-trees-8-threads}

같은 binary-trees 워크로드를 작업 스레드 8개로 병렬화했습니다 — 각 스레드가 주어진 깊이의 트리 개수 중 독립된 몫을 만들고 체크섬을 계산하며, 다음 깊이로 가기 전에 합류합니다. 릴리스만 측정했습니다. SafeC (arena)는 스레드마다 `region`/`arena<R>`을 하나씩 쓰고(arena 상태는 공유하지도 잠그지도 않음), SafeC (힙)은 `new` / `std::dealloc`을 씁니다.

| 언어 | 8 스레드 시간 | 최대 메모리 | 1 스레드 대비 속도 향상 |
|---|---|---|---|
| SafeC (arena) | **0.053s (가장 빠름)** | 98.4 MB | 2.50× |
| SafeC (힙) | 0.229s | 200.4 MB | 2.69× |
| C | 0.226s | 161.8 MB | 3.58× |
| C++ | 0.227s | 163.5 MB | 4.07× |
| Rust | 0.232s | 161.9 MB | 3.93× |
| Zig | 0.226s | 163.8 MB | 3.78× |
| Go | 0.248s | **96.4 MB (가장 가벼움)** | 2.89× |
| Python | 22.958s | 311.1 MB | 0.79× (1 스레드보다 느림) |

힙 버전은 이제 C와 같은 시간입니다(0.229 vs 0.226초, 슬랩 이전 0.263초). 메모리가 큰 것은 스레드마다 해제된 블록의 캐시와 여분 슬랩을 따로 두기 때문입니다.

::: details 소스
[binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc) · [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c) · [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp) · [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs) · [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig) · [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go) · [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)
:::

## SIMD — f64 값 20,000,000개의 제곱합 {#simd-sum-of-squares-over-20000000-f64-values}

`-O2`/릴리스에서 단순 스칼라 루프와 각 언어의 명시적 벡터 타입을 비교합니다 — "벡터화 vs 일부러 망가뜨린 코드"가 아니라, 백엔드의 자동 벡터화 *위에* 명시적 SIMD가 더해 주는 몫만 떼어 봅니다. SafeC: 네이티브 `vec<double,4>`(LLVM의 타깃 독립 `FixedVectorType`으로 내려가므로 아키텍처별 소스가 필요 없음). C/C++: GCC/Clang 벡터 확장. Zig: `@Vector`. Rust: stable 채널 인트린식(`std::simd`는 nightly 기능 필요) — x86-64에서는 SSE2([simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs)), AArch64에서는 NEON([simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs)). Go에는 이식 가능한 SIMD 타입이 없어 스칼라만 측정합니다.

| 언어 | 스칼라 | 명시적 SIMD | 속도 향상 |
|---|---|---|---|
| SafeC | 0.080s | 0.075s | 1.07× |
| C | 0.080s | 0.077s | 1.04× |
| C++ | 0.081s | 0.074s | 1.09× |
| Rust | 0.080s | 0.077s | 1.05× |
| Zig | **0.079s (가장 빠름)** | **0.072s (가장 빠름)** | 1.09× |
| Go | 0.085s | N/A | — |
| Python | 3.117s | 0.186s (NumPy) | 16.8× |

이 루프는 7800X3D에서 메모리 대역폭에 묶입니다(double 160MB가 96MB L3를 훨씬 넘어 흘러감). 그래서 명시적 SIMD가 자동 벡터화된 스칼라 루프보다 얻는 것이 적고, 표 전체가 프로세스 시작 잡음 정도인 ~10ms 안에 있습니다. SafeC의 벡터 버전은 합산을 x86-64 단계별로([AVX-512 > AVX2 > AVX > SSE4.1 > SSE2](/ko/stdlib/simd#cpu-levels)) 빌드해 실행 시점에 가장 좋은 것을 고르고, 누산기 네 개를 씁니다. 합산만 따로 재면 어느 단계에서든 약 3.7 ms(약 43 GB/s)이므로, 각 행의 나머지는 스칼라 준비 루프와 배열의 페이지 폴트입니다.

::: details 소스
[simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc) · [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c) · [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp) · [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs) · [simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs) · [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig) · [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go) · [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)
:::

## 컬렉션 — std::collections 처리량 (1,000,000개 요소) {#collections-stdcollections-throughput-1000000-elements}

제네릭 컨테이너를 같은 알고리즘의 순수 C 구현과 비교합니다: 늘어나는 배열 + `qsort`, 같은 해시를 쓰는 선형 탐사 해시 맵, 노드마다 `malloc`하는 이중 연결 리스트, 2의 거듭제곱 크기 링 큐, 균형을 맞추지 않는 BST. 양쪽 모두 같은 섞인 키(xorshift64)를 쓰고, 둘 다 clang 21.1.8 `-O2`(`safeguard build --release`), 3회 중 최선입니다.

| 연산 | SafeC | C | SafeC / C |
|---|---|---|---|
| `Vec<int>` push | 2.31 ms | **1.51 ms** | 1.53× |
| `vec_sort` (인트로소트, 인라인된 `Less<int>`) vs `qsort` | **43.5 ms** | 83.4 ms | 0.52× |
| `HashMap<int,int>` insert | 37.9 ms | **34.3 ms** | 1.10× |
| `HashMap<int,int>` get | 8.36 ms | **7.38 ms** | 1.13× |
| `List<int>` push_back + pop_front | **10.1 ms** | 22.2 ms | 0.45× |
| `Queue<int>` enqueue + dequeue | **3.02 ms** | 5.54 ms | 0.55× |
| `BST<int,int,Less<int>>` insert | **175.6 ms** | 179.9 ms | 0.98× |
| `BST<int,int,Less<int>>` get | 173.7 ms | **172.7 ms** | 1.01× |

::: details 결과를 가르는 요인
- **정렬**이 `qsort`보다 빠른 것은 순서가 타입 매개변수이기 때문입니다: 모든 비교가 인라인되지만, `qsort`는 비교할 때마다 간접 호출을 합니다.
- **List**와 **BST** 노드는 노드마다 할당하지 않고 컨테이너별 노드 풀(청크 단위 범프 할당 + 프리 리스트)에서 나옵니다. 그래서 `malloc`의 청크당 오버헤드와 `std::alloc`의 16바이트 안전 헤더를 모두 피합니다. BST 조회는 무작위 탐색의 캐시 미스가 지배하며, 양쪽이 같습니다.
- **HashMap**은 1/2/4/8바이트 키를 한 워드로 해시하고(murmur3 finalizer) 정수 비교 한 번으로 비교하며, 이를 컴파일 시간에 `if const`로 고릅니다. 조회와 삽입은 인라인되고 테이블 배열을 지역 변수로 읽으며, 재해시는 인라인되지 않는 cold 호출입니다(insert 41.6 → 37.9 ms). 남은 차이는 대부분 성장 비용입니다: 재해시마다 새 배열이 새 페이지에서 시작합니다.
- **Vec push**는 성장을 인라인되지 않는 cold 호출 하나에 두므로, push 자체는 비교, 저장, 증가뿐입니다. 2.50에서 2.31 ms가 되었고, 개선의 대부분은 할당자의 격리 구역이 더 이상 해제된 수 MB 버퍼의 재사용을 막지 않게 된 데서 왔습니다(이전에는 C의 3,000번에 비해 페이지 폴트 60,000번). 남은 차이는 벤치마크 루프의 경계 검사 `keys.at(i)`와 처음 쓰는 성장 비용입니다.
:::

::: details 소스
[bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc) · [bench_collections.c](/benchmarks/collections/c/bench_collections.c) · [vec.h](/benchmarks/stdlib/vec.h) · [map.h](/benchmarks/stdlib/map.h) · [mem.sc](/benchmarks/stdlib/mem.sc)
:::

## 웹 서비스 — JSON "hello world" 엔드포인트 {#web-service-json-hello-world-endpoint}

`GET /`이 `{"message":"Hello, World!"}`를 돌려줍니다 — TechEmpower의 "JSON serialization" 테스트와 같은 형태입니다. Apache Bench(`ab -n 20000 -c 50`, keep-alive 없음, 2,000 요청 워밍업 후)로 각 언어의 HTTP 방식을 측정합니다: SafeC의 `std::http_serve_reactor`(Linux에서는 epoll 리액터), C/C++/Zig는 최소한의 raw 소켓 accept 루프, Go의 `net/http`, Python의 FastAPI+uvicorn, Rust의 **axum**(Dioxus가 아님 — Dioxus의 풀스택 서버 계층이 바로 axum이므로, 실제로 거치는 것을 측정합니다).

| 언어 | req/s | p50 / p99 지연 | 최대 메모리 |
|---|---|---|---|
| SafeC | **25,303 (가장 빠름)** | 2 / 2 ms | 4.9 MB |
| C | 24,620 | 2 / 2 ms | **3.1 MB (가장 가벼움)** |
| C++ | 24,845 | 2 / 2 ms | 5.1 MB |
| Rust | 25,075 | 2 / 2 ms | 4.3 MB |
| Zig | 25,030 | 2 / 2 ms | 7.1 MB |
| Go | 23,199 | 2 / 3 ms | 16.1 MB |
| Python | 5,096 | 10 / 14 ms | 46.0 MB |

모든 서버가 실패 없이 모든 요청을 처리했습니다. 컴파일 언어 서버는 모두 서로 8% 이내입니다: keep-alive가 없으면 요청마다 새 TCP 연결이므로, 병목은 서버가 아니라 커널의 connect/accept 경로와 `ab` 자체입니다.

::: details 소스
[server_reactor.sc](/benchmarks/webservice/safec/server_reactor.sc) · [server.sc](/benchmarks/webservice/safec/server.sc) · [server.c](/benchmarks/webservice/c/server.c) · [server.cpp](/benchmarks/webservice/cpp/server.cpp) · [main.rs](/benchmarks/webservice/rust/src/main.rs) · [Cargo.toml](/benchmarks/webservice/rust/Cargo.toml) · [server.zig](/benchmarks/webservice/zig/server.zig) · [server.go](/benchmarks/webservice/go/server.go) · [server.py](/benchmarks/webservice/python/server.py) · [http.sc](/benchmarks/stdlib/http.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h) · [task.h](/benchmarks/stdlib/task.h)
:::

## 머신러닝 — 작은 MLP, 학습과 추론 {#machine-learning-small-mlp-training-and-inference}

2층 MLP(`relu(X @ W1) @ W2`, 128→256→64, 배치 64, MSE 손실, 직접 작성한 SGD, 바이어스 없음) — 고정 시드로 학습 100 스텝 후 추론 1000회. 모든 프레임워크가 매 스텝 손실을, 매 추론마다 `Y[0][0]`을 호스트로 읽어 옵니다(PyTorch의 `.item()`). 그래서 GPU 행은 손실을 기록하는 실제 학습 루프처럼 스텝마다 호스트 왕복 비용을 냅니다. 각 프레임워크는 먼저 시간을 재지 않는 워밍업을 한 번 합니다: 이 빌드의 PyTorch는 첫 `backward()`에 CPU에서도 ~10초의 일회성 초기화가 걸리고, rocBLAS는 GEMM 형태별 커널을 처음 쓸 때 불러옵니다.

| 프레임워크 | 장치 | 학습 (100 스텝) | 처리량 | 추론 (1000회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (내장 `sgemm`, 16 스레드) | 16.8 ms | 380,800 samples/s | 36.0 ms | 100.333267 |
| SafeC | CPU, AOCL-BLAS | 14.9 ms | 430,900 samples/s | 26.3 ms | 100.333267 |
| SafeC | CPU, oneMKL | 18.1 ms | 353,800 samples/s | 31.4 ms | 100.333267 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 9.6 ms | 668,800 samples/s | 24.3 ms | 100.333267 |
| SafeC | ROCm (MI100), fp32 | 7.26 ms | 881,300 samples/s | 33.3 ms | 100.333282 |
| SafeC | ROCm, 그래프, fp32 | 7.01 ms | 912,900 samples/s | 36.4 ms | 100.333282 |
| SafeC | ROCm, 그래프, fp16 | **5.39 ms (가장 빠름)** | **1,187,000 samples/s** | 33.2 ms | 100.336113 |
| SafeC | ROCm, 그래프, bf16 | 5.78 ms | 1,106,700 samples/s | 35.0 ms | 99.905960 |
| PyTorch | CPU | 14.0 ms | 455,900 samples/s | **21.9 ms (가장 빠름)** | 100.018272 |
| PyTorch | ROCm (MI100), fp32 | 20.5 ms | 311,700 samples/s | 36.3 ms | 103.071129 |
| PyTorch | ROCm, fp16 | 19.0 ms | 336,600 samples/s | 33.5 ms | 103.062500 |
| PyTorch | ROCm, bf16 | 18.9 ms | 338,700 samples/s | 47.1 ms | 103.000000 |
| TensorFlow | CPU | 79.5 ms | 80,500 samples/s | 203.9 ms | 106.182999 |

이 크기에서는 GPU 스텝 하나가 마이크로초 단위 커널 몇 개와 손실의 블로킹 읽기로 이루어지므로, 실행 시작과 동기화 지연이 시간을 지배합니다. CPU에서는 OpenBLAS를 쓴 SafeC가 PyTorch보다 1.5배 빠르게 학습합니다(9.6 vs 14.0 ms). 추론은 여전히 PyTorch가 가장 빠릅니다(21.9 vs SafeC 24.3 ms).

SafeC의 CPU 행은 `tensor_matmul`을 패킹된 SIMD GEMM인 [`sgemm`](/ko/stdlib/simd#gemm)으로 [스레드 풀](/ko/stdlib/sync#parallel)에서 실행합니다. 커널은 실행 시점에 CPU의 [SIMD 수준](/ko/stdlib/simd#cpu-levels)(여기서는 AVX-512)에 맞춰 고릅니다. 텐서는 단계마다의 [`TensorArena`](/ko/stdlib/ml#tensor-arena)에서 할당하고, 풀의 워커는 잠들기 전에 잠시 스핀합니다. [`[build] blas`](/ko/advanced/safeguard#linking-external-libraries-and-library-output)를 지정하면 GEMM 타일은 AOCL-BLAS(AMD)나 oneMKL(Intel)로 가며, SafeC의 풀에서 타일마다 단일 스레드로 실행됩니다. OpenBLAS 행은 예전의 `tensor_blas.h` 경로로, 곱셈마다 멀티스레드 OpenBLAS 호출 하나입니다. 이제 이 경로도 단계마다 `TensorArena`를 써서(예전에는 단계마다 텐서를 해제하지 않았음) 25.6 ms에서 9.6 ms가 되었습니다. 이 크기에서는 곱셈마다 멀티스레드 호출 하나가 SafeC 풀 위의 타일 분할보다 빠릅니다. 이 모든 것이 대체한 단순 스칼라 루프는 학습 107.4 ms, 추론 285.8 ms였습니다. 프레임워크마다 손실이 다른 것은 각자 자기 RNG로 데이터와 초기 가중치를 뽑기 때문입니다. SafeC 행들은 모두 시드가 같은 하나의 스트림(`drand48`)을 공유하며, CPU와 GPU에서 6자리까지 일치합니다.

::: details 소스
[train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [gemm.h](/benchmarks/stdlib/gemm.h) · [gemm.sc](/benchmarks/stdlib/gemm.sc) · [parallel.h](/benchmarks/stdlib/parallel.h) · [parallel.sc](/benchmarks/stdlib/parallel.sc) · [tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py)
:::

## 머신러닝, 더 큰 모델 — 512→1024→256 {#machine-learning-bigger-model-512-1024-256}

같은 모델을 ~50배 키웠습니다(배치 128). 학습 50 스텝, 추론 200회. 워밍업과 스텝마다 읽어 오는 방식은 위와 같습니다.

| 프레임워크 | 장치 | 학습 (50 스텝) | 처리량 | 추론 (200회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (내장 `sgemm`, 16 스레드) | 48.8 ms | 131,200 samples/s | 67.0 ms | 95.426926 |
| SafeC | CPU, AOCL-BLAS | 36.5 ms | 175,300 samples/s | 43.6 ms | 95.427536 |
| SafeC | CPU, oneMKL | 45.4 ms | 141,000 samples/s | 52.6 ms | 95.428177 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 93.3 ms | 68,600 samples/s | 123.1 ms | 95.424927 |
| SafeC | ROCm (MI100), fp32 | 7.64 ms | 838,100 samples/s | 10.6 ms | 95.428314 |
| SafeC | ROCm, 그래프, fp32 | 7.37 ms | 868,500 samples/s | 11.5 ms | 95.428322 |
| SafeC | ROCm, 그래프, fp16 | **5.90 ms (가장 빠름)** | **1,085,300 samples/s** | **9.26 ms (가장 빠름)** | 95.090317 |
| SafeC | ROCm, 그래프, bf16 | 6.05 ms | 1,057,500 samples/s | 9.80 ms | 63.433674 |
| PyTorch | CPU | 38.4 ms | 166,900 samples/s | 50.8 ms | 106.601181 |
| PyTorch | ROCm (MI100), fp32 | 10.7 ms | 599,000 samples/s | 13.4 ms | 1868.155273 |
| PyTorch | ROCm, fp16 | 9.91 ms | 645,600 samples/s | 17.9 ms | 1942.000000 |
| PyTorch | ROCm, bf16 | 10.8 ms | 590,700 samples/s | 15.7 ms | 107.000000 |
| TensorFlow | CPU | 105.8 ms | 60,500 samples/s | 123.6 ms | 316.611603 |

GPU에서 fp32로 SafeC는 PyTorch보다 학습이 1.45배, 추론이 1.26배 빠릅니다(둘 다 한 번에 rocBLAS GEMM 두 개, 원소별 커널 하나, 읽기 한 번). 이 차이의 일부는 새로 생겼습니다: 각 GEMM 크기의 첫 호출이 rocBLAS가 내놓는 솔루션을 모두 재서 가장 빠른 것을 쓰므로([ROCm](/ko/stdlib/ml#rocm-hip-amd) 참고) fp32 그래프 스텝이 8.29 ms에서 7.37 ms로 줄었습니다. 그래프 재생은 학습 스텝 전체를 `RocmGraph` 하나에 기록해 한 번의 호출로 실행하며, 매 스텝 손실을 블로킹으로 읽어도 3–4% 줄어듭니다. 스텝마다 읽지 않으면, 같은 루프의 C++ 프로토타입이 7.1ms 대신 3.8ms로 돌았습니다. PyTorch-ROCm의 손실은 GPU RNG로 뽑은 초기 가중치에서 lr 0.001로 발산합니다. 속도와는 상관없지만, 검증용 값이 이상해 보이는 이유입니다.

**fp16과 bf16.** 16비트 행은 데이터, 가중치, 활성화, 기울기를 모두 [`RocmHalfBuf`](/ko/stdlib/ml#rocm-hip-amd)에 담은 그래프 스텝입니다. GEMM은 MI100의 행렬 코어에서 rocBLAS `gemm_ex`로, fp32로 누적합니다. 원소별 연산은 fp32로 계산해 저장할 때 한 번 반올림하고, 손실은 fp32로 남습니다. PyTorch 행은 같은 모델과 데이터를 `.to(dtype)`로 바꿔 그 dtype으로만 학습합니다.

- 여기서 16비트가 SafeC의 가장 빠른 모드입니다. fp16은 fp32 그래프보다 작은 모델에서 23%(5.39 vs 7.01 ms), 큰 모델에서 20%(5.90 vs 7.37 ms) 빠르게 학습하고, 큰 모델 추론도 20% 빠릅니다(9.26 vs 11.5 ms). bf16은 fp16과 7% 이내입니다.
- 같은 dtype의 PyTorch-ROCm과 비교하면 SafeC는 작은 모델 학습에서 3.5배(fp16), 3.3배(bf16), 큰 모델에서 1.7배, 1.8배 빠릅니다. 큰 모델 추론은 1.9배, 1.6배 빠릅니다. PyTorch는 16비트로 학습이 많아야 8% 빨라지고, 큰 모델 추론은 16비트가 fp32보다 느립니다.
- 크기별 솔루션 선택이 여기서 가장 중요합니다. rocBLAS의 기본 솔루션으로는 큰 모델에서 fp16이 fp32보다 *느렸습니다*(9.3 vs 8.4 ms): 128×256×1024 fp16 곱의 기본 솔루션이 0.047 ms, 가장 빠른 것은 0.008 ms였습니다.
- 이 루프는 반올림 차이를 키웁니다. 50 스텝 뒤 큰 모델의 손실은 fp16에서 95.09, bf16에서 63.43이고 fp32는 95.43입니다. H200도 같은 양상입니다(93.44, 62.01).
- CPU의 [`tensor_half.h`](/ko/stdlib/ml#half-precision-tensors)는 fp16/bf16으로 저장하지만 계산은 fp32로 하므로 메모리는 줄어도 시간은 줄지 않습니다. 그래서 16비트 CPU 행은 없습니다.

CPU에서는 이 크기에서 AOCL-BLAS를 쓴 SafeC가 가장 빠릅니다: 학습은 PyTorch보다 5%(36.5 vs 38.4 ms), 추론은 14%(43.6 vs 50.8 ms) 빠릅니다. 내장 `sgemm`만으로도 PyTorch의 1.3배 이내이며, 이것이 대체한 스칼라 루프(학습 1761.2 ms)보다 36배 빠릅니다. OpenBLAS 행(Ubuntu 패키지의 범용 pthreads 빌드)은 아레나를 쓴 뒤에도(148.5 → 93.3 ms) 여기서 가장 느린 CPU 경로입니다. 곱셈마다 멀티스레드 호출 하나이고, 이 작은 GEMM들에서 그 스레드들이 서로 스핀하며 부딪힙니다.

::: details 소스
[train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_cpu_blas.sc](/benchmarks/ml_big/safec/train_cpu_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc) · [PyTorch train.py](/benchmarks/ml_big/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml_big/tensorflow/train.py) · [MLX train.py](/benchmarks/ml_big/mlx/train.py)
:::

## 메모리 할당 — `std::alloc`/`dealloc`은 raw `malloc`/`free`보다 느린가? {#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree}

`std::alloc`은 블록마다 이중 해제/해제 후 사용 검사를 담는 16바이트 헤더를 붙이고, 크기 등급 캐시를 둡니다: PyTorch의 CPU/CUDA 캐싱 할당자, MLX의 Metal 버퍼 캐시와 같은 발상입니다. 해제된 블록은 2의 거듭제곱 크기 등급별 스레드 로컬 프리 리스트로 들어가고, 같은 등급의 다음 `alloc()`은 `malloc()`/`free()`를 부르지 않고 거기서 바로 받습니다. 캐시된 블록은 재사용될 때까지 "해제됨" 매직 워드를 유지하므로 안전 검사는 그대로 동작합니다. glibc의 `malloc`/`free`와 비교한 결과(3회 중 최선. 모든 블록 주소를 출력되는 체크섬에 섞어, 최적화기가 할당/해제 쌍을 지우지 못하게 함):

| 패턴 | `std::alloc` / `dealloc` | `malloc` / `free` | 비율 |
|---|---|---|---|
| 교차: 48바이트 블록 하나를 할당하고 해제, 2천만 번 | **3.57 ns / 쌍** | 6.38 ns / 쌍 | 1.8배 빠름 |
| 일괄: 24바이트 블록 100만 개를 할당한 뒤 모두 해제, 10회 | **87 ms** | 96 ms | 1.1배 빠름 |

해제된 블록이 곧 다시 쓰이면 캐시가 이깁니다. 수명이 짧은 객체와 binary-trees 같은 반복 할당이 그렇습니다: 위의 힙 binary-trees는 다른 모든 언어의 할당자보다 빠릅니다. 살아 있는 블록 100만 개는 캐시가 담을 수 있는 양(모든 등급 합쳐 125,587 슬롯)을 넘습니다. 예전에는 그런 블록이 하나씩 `malloc`을 거치고 헤더 쓰기가 더해졌습니다(134 ms, `malloc`보다 1.4배 느림). 이제 1 KB 이하 블록은 스레드별 64 KB 슬랩에서 나오므로, 같은 이중 해제 검사를 유지하면서 일괄 패턴이 `malloc`보다 1.1배 빠릅니다([`std::mem`](/ko/stdlib/mem#allocation-performance) 참고). 수명이 짧은 대량 할당에는 여전히 `region`/`arena<R>`이 더 빠른 도구입니다: binary-trees에서 힙 버전보다 4.7배 빠릅니다.

::: details 소스
[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc) · [alloc_bench.sc](/benchmarks/memalloc/safec/alloc_bench.sc)
:::

## MI100의 합성곱과 FFT {#mi100-conv-fft}

[H200](#h200-conv-fft)과 같은 작업을 이 페이지의 주 머신에서 잰 결과입니다: SafeC의 CPU 코드(`cnn.h`, `unet.h`, `fft.h`), ROCm 라이브러리 [`gpu_miopen.h`와 `fft_rocm.h`](/ko/stdlib/ml#rocm-miopen-hipfft)(ROCm 7.1의 MIOpen과 hipFFT), 그리고 ROCm 6.4 위의 PyTorch 2.9.1(역시 MIOpen과 rocFFT 사용). 텐서는 호출 사이에 GPU에 머뭅니다. GPU 행은 두 번의 동기화 사이에 쌓은 호출 300–2000개의 평균, 3회 중 최선이며, 모두 fp32입니다(MI100에는 TF32가 없음). SafeC는 첫 호출에서 MIOpen의 Find로 합성곱 알고리즘을 고르고, PyTorch도 같은 일을 합니다(`cudnn.benchmark = True`).

합성곱, 호출당 ms(낮을수록 좋음). `conv64`는 Conv2D 계층 하나(64→64 채널, 3×3, 스트라이드 1, 패딩 1, 56×56, 바이어스 포함). `unet128`은 128×128에서 `unet_new(3, 32, 64, 3)`의 순전파입니다.

| 프레임워크 | 장치 | conv64, 이미지 1장 | conv64, 32장 | unet128, 1장 | unet128, 16장 |
|---|---|---|---|---|---|
| SafeC | CPU (Winograd / im2col + 내장 `sgemm`, 16 스레드) | 0.412 | — | 1.68 | — |
| SafeC | CPU, AOCL-BLAS | 0.359 | — | 1.35 | — |
| SafeC | CPU, oneMKL | 0.399 | — | 1.69 | — |
| PyTorch | CPU (oneDNN, 8 스레드) | 0.327 | — | 2.68 | — |
| SafeC | ROCm | **0.0270** | **0.342** | 0.222 | 1.289 |
| SafeC | ROCm, U-Net을 그래프 하나로 | | | **0.216** | |
| PyTorch | ROCm | 0.0628 | 0.346 | 0.314 | **1.259** |

GPU의 FFT, 호출당 ms. `fft1d`와 `fft2d`는 호스트 메모리에서 시작해 끝나는 복소 `double` 배열이라, GPU 호출마다 업로드와 읽기가 포함됩니다. `batch`와 `rfft`는 이미 GPU에 있는 데이터(complex64, float32)로 실행합니다.

| 프레임워크 | 장치 | fft1d, 2²⁰점 | fft2d, 1024×1024 | batch, 1024 × 4096 | rfft, 1024 × 4096 |
|---|---|---|---|---|---|
| SafeC | ROCm | **1.46** | **1.43** | **0.1002** | **0.0949** |
| PyTorch | ROCm | 1.56 | 1.53 | 0.171 | 0.126 |

CPU의 FFT, 호출당 ms. 비교 대상은 FFTW 3.3.10(AVX/AVX2/AVX-512, FMA 코드렛으로 빌드, `FFTW_MEASURE`로 계획), KFR 7.2.0, RustFFT 6.4.1입니다. 데이터는 모두 메모리에서 시작합니다. `fft1d`는 2²⁰점 변환 하나, `fft2d`는 1024×1024 변환 하나, `batch`는 4096점 변환 1024개, `rfft`는 4096점 실수 변환 1024개입니다. `rfft`에서는 모든 라이브러리가 중복되지 않는 n/2 + 1개 빈을 돌려줍니다(SafeC는 `fft_real_half_batch`로). RustFFT에는 2D와 실수 변환이 없습니다. oneMKL을 링크하면 SafeC는 65,536점 미만의 변환을 DFTI에 맡깁니다. AOCL은 여기에 영향이 없습니다(AOCL의 FFT는 FFTW 기반이라 GPL이며 쓰지 않음).

배정밀도(complex128):

| 라이브러리 | 스레드 | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft.h` | 16 | **0.888** | 0.691 | 1.25 | 0.861 |
| SafeC, oneMKL DFTI | 16 | 0.891 | 1.00 | 1.89 | 1.05 |
| FFTW | 16 | 1.63 | **0.618** | **1.05** | **0.553** |
| PyTorch | 8 | 8.31 | 0.710 | — | — |
| SafeC `fft.h` | 1 | 5.47 | 4.43 | 11.9 | 6.17 |
| FFTW | 1 | 6.50 | 3.49 | 7.84 | 4.28 |
| KFR | 1 | 3.36 | 4.22 | 11.8 | 4.34 |
| RustFFT | 1 | 4.75 | — | 8.25 | — |

단정밀도(complex64 / float32):

| 라이브러리 | 스레드 | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft_f32` | 16 | **0.688** | 0.560 | 0.775 | 0.558 |
| SafeC, oneMKL DFTI | 16 | 0.693 | 0.543 | 0.964 | 0.457 |
| FFTW (`fftwf`) | 16 | 1.67 | **0.446** | **0.504** | **0.409** |
| PyTorch | 8 | — | — | 4.41 | 0.443 |
| SafeC `fft_f32` | 1 | 4.39 | 3.25 | 5.76 | 4.28 |
| FFTW (`fftwf`) | 1 | 5.53 | 2.54 | 3.31 | 2.98 |
| KFR | 1 | 1.77 | 2.63 | 4.23 | 2.13 |
| RustFFT | 1 | 2.10 | — | 4.11 | — |

단일 스레드 SafeC 행은 표 측정 뒤에 같은 빌드로 따로 쟀습니다(`SAFEC_NUM_THREADS=1`).

- **합성곱**: GPU에서 SafeC는 이미지 1장에서 PyTorch보다 2.3배, 그래프로 실행한 배치 1 U-Net에서 1.45배 빠릅니다. 32장에서는 비기고, 16장 U-Net에서는 PyTorch가 2% 빠릅니다. SafeC의 합성곱은 MIOpen 호출 하나와 바이어스(및 ReLU)용 HIP 커널 하나이고, U-Net 디코더 단계는 연결 버퍼로 바로 업샘플하는 커널 하나입니다.
- **CPU 합성곱**: 3×3, 스트라이드 1 계층은 [Winograd F(2×2, 3×3)](/ko/stdlib/ml#cnn)(Lavin & Gray)를 씁니다. 출력 2×2와 채널 쌍마다 곱셈이 36번 대신 16번입니다. 입력 변환, 채널 GEMM 16개, 출력 변환이 각각 풀에서 실행됩니다. 그 밖의 계층은 im2col + `sgemm`입니다. 이제 이미지마다의 맵(U-Net의 중간 결과 포함)은 이미지가 끝나면 reset하는 [`TensorArena`](/ko/stdlib/ml#tensor-arena)에서 할당하므로, 어느 계층도 수 MB짜리 새 할당 비용을 치르지 않습니다(이것만으로 `conv64`가 0.535 ms에서 0.415 ms가 됨). U-Net에서 SafeC는 AOCL-BLAS로 PyTorch의 CPU 경로보다 2.0배(1.35 vs 2.68 ms), 내장 GEMM으로 1.6배 빠릅니다. Winograd와 아레나 이전에는 3.13 ms였습니다. 단일 `conv64` 계층에서는 PyTorch(oneDNN)가 여전히 1.1–1.3배 빠릅니다.
- **GPU의 FFT**: 양쪽 모두 rocFFT를 부릅니다. 호스트 배열은 PCIe가 지배해 시간이 같습니다. 장치 데이터에서는 SafeC가 1.3–1.7배 빠릅니다. 호출당 0.1 ms 수준에서는 같은 변환을 둘러싼 PyTorch의 호출당 작업(출력 텐서 할당, 디스패치)이 큰 몫을 차지하지만, 따로 재지는 않았습니다.
- **CPU FFT**: 2²⁰점 변환에서는 두 정밀도 모두 SafeC가 가장 빠르며, 16스레드 FFTW보다 1.8배(double), 2.4배(float) 앞섭니다. 2D, 배치, 실수 변환에서는 FFTW가 1.1배(double `fft2d`)에서 1.6배(double `rfft`)까지 앞섭니다. 단일 스레드에서 SafeC는 2²⁰점에서는 FFTW보다 빠르고, 2D와 4096점 행에서는 뒤집니다(double 배치에서 1.5배). 이 행들에서는 FFTW의 더 큰 코드렛과 행마다 더 적은 패스가 효과를 냅니다. 이 페이지의 이전 측정 이후, 합친 radix-8 패스, 벡터 하나에 복소수 네 개(AVX-512, 그리고 AVX2의 float), 컴파일 타임 비트 역순 테이블로 16스레드 double 배치가 4.35 ms에서 1.25 ms로, 2D 변환이 2.06 ms에서 0.691 ms로 줄었습니다. 실수 변환의 분리 단계는 이제 빈 두 개씩 벡터로 처리하고, rfft 행은 다른 라이브러리처럼 절반 스펙트럼을 돌려줍니다: double 실수 배치는 1.75 ms(n개 빈 전부, 스칼라 분리)에서 0.861 ms가 되었습니다.
- **단정밀도**: float는 같은 알고리즘(`fft_core.h`를 두 번 빌드)을 레지스터당 두 배의 값으로 실행합니다. 배치는 double 시간의 0.62배, 실수 배치는 0.65배입니다.
- **oneMKL**: DFTI는 float `rfft`(0.457 vs 0.558)와 float `fft2d`(0.543 vs 0.560)에서는 SafeC 자체 코드보다 빠르지만, double에서는 느립니다(배치 1.89 vs 1.25).
- **워밍업**: 모든 CPU 행은 시간을 재지 않는 호출을 먼저 한 번 합니다(스레드 풀을 시작하고 회전 인자 테이블을 만듭니다). FFTW의 계획은 시간 측정 밖에서 만듭니다. PyTorch 스크립트는 워밍업 호출을 다섯 번 합니다.

::: details 소스
[conv_rocm.sc](/benchmarks/conv/safec/conv_rocm.sc) · [conv_cpu.sc](/benchmarks/conv/safec/conv_cpu.sc) · [conv.py](/benchmarks/conv/pytorch/conv.py) · [fft_rocm.sc](/benchmarks/fft/safec/fft_rocm.sc) · [fft_cpu.sc](/benchmarks/fft/safec/fft_cpu.sc) · [fft.py](/benchmarks/fft/pytorch/fft.py) · [fftw_bench.c](/benchmarks/fft/c/fftw_bench.c) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [RustFFT main.rs](/benchmarks/dsp/rust/src/main.rs) · [cnn.sc](/benchmarks/stdlib/cnn.sc) · [unet.sc](/benchmarks/stdlib/unet.sc) · [gemm.sc](/benchmarks/stdlib/gemm.sc) · [fft.h](/benchmarks/stdlib/fft.h) · [fft.sc](/benchmarks/stdlib/fft.sc) · [fft_f32.sc](/benchmarks/stdlib/fft_f32.sc) · [fft_core.h](/benchmarks/stdlib/fft_core.h) · [parallel.sc](/benchmarks/stdlib/parallel.sc) · [gpu_miopen.h](/benchmarks/stdlib/gpu_miopen.h) · [gpu_miopen.sc](/benchmarks/stdlib/gpu_miopen.sc) · [fft_rocm.h](/benchmarks/stdlib/fft_rocm.h) · [fft_rocm.sc](/benchmarks/stdlib/fft_rocm.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc)
:::

## MI100에서의 어텐션 {#mi100-attention}

[`gpu_rocm.h`](/ko/stdlib/ml#rocm-attention)의 flash 어텐션과 선형 어텐션을 ROCm 6.4의 PyTorch 2.9.1과 비교합니다. 형태는 [H200 측정](#h200-attention)의 fp32 형태와 같습니다: 배치 4 × 헤드 16(SafeC에는 같은 메모리 배치의 64개 헤드로 전달), 데이터는 GPU에 둡니다. 각 수치는 두 동기화 사이에 큐에 넣은 5–40번 호출의 평균이며, 3번 실행 중 최솟값입니다. TFLOPS는 4·B·H·S²·D로 셉니다(causal이면 절반). gfx908에서 PyTorch의 `scaled_dot_product_attention`에는 융합 커널이 없어서, 배치 행렬곱으로 점수 행렬 전체를 만든 뒤 그 위에서 softmax를 돌립니다. 선형 어텐션 행은 H200과 같은 벡터화된 PyTorch 코드이며 IEEE fp32로 계산합니다(MI100에는 TF32가 없습니다).

Flash 어텐션, 호출당 ms(TFLOPS):

| 헤드 차원 | 토큰 | Causal | SafeC ms (TFLOPS) | PyTorch ms (TFLOPS) | PyTorch / SafeC |
|---|---|---|---|---|---|
| 64 | 1024 | 아니오 | **0.858 (20.0)** | 2.52 (6.8) | 2.93 |
| 64 | 1024 | 예 | **0.457 (18.7)** | 3.28 (2.6) | 7.17 |
| 64 | 4096 | 아니오 | **10.7 (25.7)** | 42.8 (6.4) | 4.00 |
| 64 | 4096 | 예 | **5.48 (25.0)** | 56.2 (2.5) | 10.25 |
| 128 | 1024 | 아니오 | **1.81 (18.9)** | 3.32 (10.3) | 1.83 |
| 128 | 1024 | 예 | **0.950 (18.0)** | 4.05 (4.2) | 4.26 |
| 128 | 4096 | 아니오 | **24.8 (22.2)** | 53.4 (10.3) | 2.16 |
| 128 | 4096 | 예 | **12.6 (21.9)** | 66.4 (4.1) | 5.29 |

선형 어텐션(φ(x) = elu(x) + 1), 호출당 ms:

| 헤드 차원 | 토큰 | Causal | SafeC | PyTorch | PyTorch / SafeC |
|---|---|---|---|---|---|
| 64 | 4096 | 아니오 | **0.464** | 1.40 | 3.02 |
| 64 | 4096 | 예 | **0.673** | 3.64 | 5.41 |
| 64 | 16384 | 아니오 | **1.62** | 5.63 | 3.49 |
| 64 | 16384 | 예 | **2.76** | 14.6 | 5.28 |
| 128 | 4096 | 아니오 | **1.60** | 3.25 | 2.03 |
| 128 | 4096 | 예 | **2.84** | 8.73 | 3.07 |

- **Flash 어텐션**은 MI100의 행렬 코어에서 fp32로 돕니다. 헤드 차원 64까지는 `v_mfma_f32_32x32x2f32`, 128에서는 `16x16x4`를 씁니다. 후자는 wave당 레지스터가 절반이라 SIMD당 두 wave를 유지합니다. 점수를 전치해서(Sᵀ = K Qᵀ) 계산하므로 각 레인이 질의 한 행을 맡습니다. 그 q는 레지스터에 머물고, 행 최댓값과 합은 반쪽 사이 셔플 한 번이면 되며, P는 레지스터에서 바로 P·V 곱으로 들어갑니다. causal 블록은 가장 긴 것부터 시작하고, 출력은 어떤 행의 최댓값이 2⁸ 넘게 커질 때만 다시 스케일합니다(FlashAttention-4와 같음). 그 결과 MI100의 fp32 행렬 최대치 46 TFLOPS 중 18–26 TFLOPS가 나오며, 이전 CUDA 코어 커널보다 4–7배 빠릅니다(헤드 차원 64, 4096 토큰에서 45.5 ms → 10.7 ms).
- **PyTorch 대비** 마스크가 없으면 1.8–4배, causal 마스크가 있으면 4–10배 빠릅니다. PyTorch의 수학 경로는 마스크와 상관없이 점수 행렬 전체를 계산하므로 마스크가 있으면 오히려 느려지고, SafeC는 완전히 가려진 타일을 건너뜁니다.
- **선형 어텐션**은 128행 청크 단위로 돕니다. non-causal과 헤드 차원 128의 causal은 세 패스입니다. 청크별 상태 φ(K)ᵀV를 행렬 코어에서 구합니다. 청크들에 대해 스캔을 돕니다. 출력 패스는 φ(Q) 행을 MFMA 피연산자로 레지스터에 두고, 청크 내부의 마스크 항을 더합니다. 헤드 차원 64까지의 causal은 한 패스로 돕니다. 각 청크의 블록이 K/V 타일을 한 번 읽어 마스크 항과 자기 상태에 함께 씁니다. 앞 청크들의 접두합은 decoupled look-back(Merrill & Garland의 단일 패스 스캔)으로 구합니다. 블록은 원자적 티켓으로 청크를 순서대로 받고, 자기 상태를 바로 공개한 뒤, 처음으로 공개된 접두합이 나올 때까지 앞 청크들의 상태를 더합니다. 그래서 별도의 상태·스캔 패스와 K, V의 두 번째 읽기가 없어집니다. 세 패스일 때보다 4096 토큰에서 13%(0.775 → 0.673 ms), 16k에서 10% 빠릅니다. 전체적으로 이전 커널보다 7–11배, PyTorch보다 2–5.4배 빠릅니다.
- 행렬 코어가 없는 GPU(RDNA)는 CUDA 코어 커널을 씁니다. MI100에서도 `rocm_attention_use_mfma(0)`으로 그 커널을 고를 수 있습니다. `tests/stdlib/rocm/rocm_check.sc`가 두 경로를 배정밀도 기준값과 attention.h의 CPU 함수에 대해 검사합니다: 251개 검사.

::: details 소스
[attn_rocm.sc](/benchmarks/attention/safec/attn_rocm.sc) · [attn.py](/benchmarks/attention/pytorch/attn.py) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc)
:::

## CPU의 DSP {#dsp-cpu}

[`std/dsp`](/ko/stdlib/dsp)를 NumPy 2.5.4 / SciPy, KFR 7.2.0, 그리고 biquad에서는 float32 오디오 라이브러리 두 개(Q, madronalib), 리샘플링에서는 dasp 0.11과 비교합니다. 모든 작업은 무작위 샘플 1,048,576개(`conv2d`는 1024×1024 이미지)로 double, 그다음 단정밀도(SafeC의 [`_f32` 함수](/ko/stdlib/dsp#single-precision-float))로 실행합니다. 워밍업 호출 한 번 뒤의 호출당 평균 ms, 3회 중 최선입니다.

- `fir stream`: 호출 사이에 상태를 유지하는 127탭 스트리밍 FIR(SafeC `fir_process_block`, SciPy `lfilter`, KFR `fir_filter`). SafeC 쪽은 실시간 경로입니다: 할당도 스레드도 없습니다.
- `fir block`: 같은 필터를 컨볼루션 하나로(`conv_direct`, `np.convolve`).
- `fftconv`: 4,096탭 커널(`conv_fft`, `fftconvolve`, KFR `convolve_filter`).
- `biquad`: RBJ 저역 통과 섹션 하나(fs 48 kHz, f0 1 kHz, Q 0.7071; `biquad_process_block`, `sosfilt`). 실시간 필터처럼 단일 스레드로 실행합니다.
- `stft`: 1,024점 Hann 프레임, 홉 256(`stft_forward`; SciPy의 `ShortTimeFFT`는 단측 스펙트럼을 돌려줌).
- `resample`: 44.1 kHz → 48 kHz. SafeC는 양쪽 16탭의 윈도우 sinc, SciPy는 `resample_poly`(폴리페이즈, Kaiser), KFR은 `normal` 품질(및 `draft`), dasp는 양쪽 16탭 sinc를 씁니다.
- `conv2d`: 5×5 가우시안, "same" 크기.

배정밀도:

| 작업 | SafeC | SafeC, AOCL / oneMKL | SciPy | KFR | 기타 |
|---|---|---|---|---|---|
| fir stream | 5.87 | 5.97 / 5.86 | 17.4 | 32.1 | |
| fir block | 0.841 | 0.824 / 0.796 | 15.4 | — | |
| fftconv | 2.98 | 3.02 / 3.19 | 32.3 | 4.89 | |
| biquad | 0.484 | 0.484 / 0.493 | 4.71 | 2.33 | |
| stft | 1.82 | 1.85 / 1.81 | 53.9 | — | |
| resample | 3.71 | 3.84 / 3.88 | 9.07 | 61.7 (draft 26.3) | dasp 519 |
| conv2d | 0.366 | 0.369 / 0.390 | 35.3 | — | |

단정밀도:

| 작업 | SafeC | SafeC, AOCL / oneMKL | SciPy | KFR | 기타 |
|---|---|---|---|---|---|
| fir stream | 3.04 | 3.03 / 3.07 | 19.5 | 27.8 | |
| fir block | 0.475 | 0.450 / 0.471 | 18.9 | — | |
| fftconv | 1.29 | 1.31 / 1.16 | 14.8 | 2.76 | |
| biquad | 0.482 | 0.483 / 0.525 | 4.59 | 2.15 | Q 1.69, madronalib 2.87 |
| stft | 0.986 | 0.968 / 0.983 | 55.8 | — | |
| resample | 3.96 | 3.82 / 3.98 | 8.68 | 34.1 (draft 27.3) | |

- 두 정밀도 모두 모든 행에서 SafeC가 가장 빠릅니다. 벤더 라이브러리는 FFT가 들어가는 곳(`fftconv`, `stft`)에서만 의미가 있고, 거기서도 내장 코드와 오차 범위 안입니다.
- **FIR**: 블록마다 `dsp_sliding_dot`을 실행합니다. 패스 하나에 출력 32개(float는 64개)를 계산하며, 탭마다 값을 네 개의 누산 벡터에 브로드캐스트하므로 수평 합이 없습니다. 스트리밍 필터는 14.2 ms에서 5.87 ms(double), 9.45 ms에서 3.04 ms(float)로 줄었습니다. KFR과 SciPy는 출력마다 내적을 하나씩 합니다. `fir block`은 스레드를 쓰고, `fir stream`은 쓰지 않습니다.
- **Biquad**: `biquad_process_block`은 분산 룩어헤드(Parhi & Messerschmitt)를 씁니다. 재귀를 y[i-2]와 y[i-4]로 다시 써서 짝수와 홀수 출력을 서로 독립인 두 사슬로 만들고, 2레인 벡터 하나로 함께 계산합니다. 그 결과 float에서 Q보다 3.5배, KFR보다 4.5배 빠르고, 이전(2.14 ms)보다 4.4배 빠릅니다. 추가되는 극점은 필터 자신의 극점을 뒤집은 것이라 안정성이 유지되고, 출력은 `process()`와 반올림 차이만 납니다.
- **리샘플링**: 탭마다의 가중치를 호출마다 한 번 만드는 테이블과 각의 차 공식으로 구하므로 탭이 벡터화됩니다. 출력마다의 각도는 회전으로 넘기므로 출력마다 삼각 함수 호출이 없습니다. SciPy의 폴리페이즈 필터보다 2.4배 빠릅니다(이전 12.1 ms). SafeC의 리샘플러는 스레드를 쓰고, SciPy는 쓰지 않습니다.
- **STFT** 프레임은 제자리 실수 FFT 배치로 변환합니다. 이전에 쓰던 복소 변환의 절반 작업입니다(이전 7.08 ms).
- **float vs double**: float는 메모리 트래픽이나 벡터 폭이 한계인 곳에서 이득입니다: FIR(2배), `fftconv`, `stft`. biquad는 지연 시간이 한계이고 리샘플러는 커널을 double로 계산하므로, 이 둘은 변하지 않습니다.

::: details 소스
[dsp_cpu.sc](/benchmarks/dsp/safec/dsp_cpu.sc) · [dsp_scipy.py](/benchmarks/dsp/python/dsp_scipy.py) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [audio_libs_bench.cpp](/benchmarks/dsp/cpp/audio_libs_bench.cpp) · [dasp main.rs](/benchmarks/dsp/rust/src/main.rs) · [Cargo.toml](/benchmarks/dsp/rust/Cargo.toml) · [dsp.sc](/benchmarks/stdlib/dsp.sc) · [filter.sc](/benchmarks/stdlib/filter.sc) · [biquad.sc](/benchmarks/stdlib/biquad.sc) · [convolution.sc](/benchmarks/stdlib/convolution.sc) · [conv_core.h](/benchmarks/stdlib/conv_core.h) · [stft.sc](/benchmarks/stdlib/stft.sc) · [resample_core.h](/benchmarks/stdlib/resample_core.h) · [imaging_core.h](/benchmarks/stdlib/imaging_core.h) · [fft_core.h](/benchmarks/stdlib/fft_core.h)
:::

## Xeon Platinum 8570 + NVIDIA H200 {#xeon-8570-h200}

같은 벤치마크를 GPU 서버에서 돌린 결과이며, SafeC의 CUDA 백엔드를 검증한 장비입니다. CPU, 코어 수, 툴체인 버전이 모두 다르므로 위의 7800X3D 표와 비교하지 말고 이 절 안에서만 비교하세요.

- **장비**: 호스트 112코어 중 12코어로 제한된 Backend.AI 컨테이너의 Intel Xeon Platinum 8570, NVIDIA H200 (sm_90, 141 GB HBM3e, 드라이버 580.126.20, CUDA 13.0, cuDNN 9.15), Ubuntu 24.04.3 — `main`(3848bf1)에서 빌드한 `safec` 1.0.0 · clang 21.1.8 · Rust 1.99.1 · Go 1.27.3 · Zig 0.17.0 · Python 3.12.3 · PyTorch 2.10.0a0 (NGC 25.11) · TensorFlow 2.21.0 (CPU) · OpenBLAS (pthreads, Ubuntu 패키지) · oneMKL 2026.2. FFT/DSP 비교용: FFTW 3.3.10 (Ubuntu 패키지), KFR 7.2.0, SciPy 1.16.3, RustFFT 6.4.1, dasp 0.11.0, 현재 소스의 Q와 madronalib. 서드파티 라이브러리는 각자의 벤치마크 프로그램에만 링크됩니다.
- **방법**: [위](#methodology)와 같고, 3회 중 최고값입니다. H200은 학습 작업과 공유되는데, 측정하는 동안 그 작업을 일시 정지(`SIGSTOP`)해서 GPU와 12코어가 비어 있었습니다. 그 작업이 도는 동안에는 같은 GPU 벤치마크가 SafeC든 PyTorch든 실행마다 5배까지 차이 났습니다. SafeC의 CPU 커널은 실행 시점에 CPU의 [SIMD 수준](/ko/stdlib/simd#cpu-levels)에 맞춰 고르며, 이 Xeon에서는 AVX-512입니다.
- **수치**: [`results/linux-xeon8570-h200.json`](/benchmarks/results/linux-xeon8570-h200.json). SafeC의 GPU 행은 모두 이전 측정(SafeC `cuda-perf-gaps`)과 2.4% 안에서 같고, 호스트 오버헤드에 묶인 PyTorch GPU 행은 최대 11% 달라졌습니다.

### H200에서의 머신러닝 {#h200-machine-learning}

모델, 루프, 스텝마다의 읽기는 [MI100 표](#machine-learning-small-mlp-training-and-inference)와 같습니다. SafeC CUDA 행은 [train_cuda.sc](/benchmarks/ml/safec/train_cuda.sc)의 여섯 가지 모드입니다:

- **plain**: ROCm 벤치마크와 같은, 커널 실행 10번짜리 스텝.
- **fused**: `relu(X W1)`을 cuBLASLt 호출 한 번으로, SGD 갱신 두 번을 커널 하나로 처리해 스텝당 7번 실행.
- **fused + graph**: fused 스텝을 CUDA 그래프 하나로 재생.
- **TF32**: 그래프 스텝에 TF32 텐서 코어를 켠 것(`cuda_set_tf32`).
- **fp16 / bf16**: 데이터, 가중치, 활성값, 기울기를 모두 16비트로 저장하는 그래프 스텝([`CudaHalfBuf`](/ko/stdlib/ml#cuda)). GEMM은 텐서 코어에서 fp32로 누산하고, 원소별 연산은 fp32로 계산해 저장할 때 한 번 반올림합니다. 손실 값은 fp32로 유지합니다. PyTorch fp16/bf16 행은 같은 모델과 데이터를 `.to(dtype)`으로 바꿔 그 형식으로만 학습합니다.

NVIDIA NGC PyTorch 컨테이너는 `TORCH_ALLOW_TF32_CUBLAS_OVERRIDE=1`을 설정하므로, PyTorch의 fp32 행렬곱은 기본으로 이미 TF32로 돕니다. PyTorch fp32 행은 이 변수를 0으로 설정한 결과입니다.

작은 모델 (128→256→64, 배치 64, 학습 100 스텝, 추론 1000회):

| 프레임워크 | 장치 | 학습 (100 스텝) | 처리량 | 추론 (1000회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (내장 `sgemm`, 12 스레드) | 24.3 ms | 262,985 samples/s | 54.0 ms | 100.333267 |
| SafeC | CPU, oneMKL | 25.9 ms | 247,467 samples/s | 39.3 ms | 100.333267 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 21.6 ms | 296,777 samples/s | 55.3 ms | 100.333267 |
| SafeC | CUDA (H200), fp32 | 6.77 ms | 945,347 samples/s | 38.9 ms | 100.333282 |
| SafeC | CUDA, fused, fp32 | 6.15 ms | 1,039,974 samples/s | 34.9 ms | 100.333282 |
| SafeC | CUDA, fused + graph, fp32 | 3.81 ms | 1,678,909 samples/s | 19.9 ms | 100.333282 |
| SafeC | CUDA, graph, TF32 | 3.80 ms | 1,684,654 samples/s | **19.6 ms (가장 빠름)** | 100.334084 |
| SafeC | CUDA, graph, fp16 | **2.98 ms (가장 빠름)** | **2,145,491 samples/s** | 19.7 ms | 100.335869 |
| SafeC | CUDA, graph, bf16 | 2.98 ms | 2,144,054 samples/s | 19.7 ms | 99.951080 |
| PyTorch | CPU | 23.2 ms | 275,672 samples/s | 43.0 ms | 100.018272 |
| PyTorch | CUDA, TF32 (NGC 기본값) | 47.6 ms | 134,533 samples/s | 55.7 ms | 103.076630 |
| PyTorch | CUDA, fp32 | 51.3 ms | 124,839 samples/s | 56.6 ms | 103.071129 |
| PyTorch | CUDA, fp16 | 49.1 ms | 130,346 samples/s | 52.5 ms | 103.062500 |
| PyTorch | CUDA, bf16 | 48.1 ms | 132,937 samples/s | 52.4 ms | 103.000000 |
| TensorFlow | CPU | 122.9 ms | 52,064 samples/s | 264.9 ms | 106.182999 |

큰 모델 (512→1024→256, 배치 128, 학습 50 스텝, 추론 200회):

| 프레임워크 | 장치 | 학습 (50 스텝) | 처리량 | 추론 (200회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (내장 `sgemm`, 12 스레드) | 115.6 ms | 55,359 samples/s | 117.3 ms | 95.426926 |
| SafeC | CPU, oneMKL | 101.3 ms | 63,164 samples/s | 61.3 ms | 95.427391 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 195.3 ms | 32,769 samples/s | 174.6 ms | 95.424927 |
| SafeC | CUDA (H200), fp32 | 4.10 ms | 1,561,737 samples/s | 8.23 ms | 95.427292 |
| SafeC | CUDA, fused, fp32 | 3.77 ms | 1,698,514 samples/s | 7.57 ms | 95.427292 |
| SafeC | CUDA, fused + graph, fp32 | 3.13 ms | 2,045,382 samples/s | 5.60 ms | 95.427277 |
| SafeC | CUDA, graph, TF32 | 2.31 ms | 2,770,563 samples/s | 5.58 ms | 90.685493 |
| SafeC | CUDA, graph, fp16 | **1.90 ms (가장 빠름)** | **3,377,309 samples/s** | 4.00 ms | 93.444328 |
| SafeC | CUDA, graph, bf16 | 1.90 ms | 3,370,195 samples/s | **3.99 ms (가장 빠름)** | 62.006180 |
| PyTorch | CPU | 63.1 ms | 101,349 samples/s | 53.6 ms | 106.544487 |
| PyTorch | CUDA, TF32 (NGC 기본값) | 18.4 ms | 346,883 samples/s | 11.8 ms | 871.826538 |
| PyTorch | CUDA, fp32 | 15.9 ms | 402,516 samples/s | 11.8 ms | 1754.826172 |
| PyTorch | CUDA, fp16 | 17.3 ms | 370,821 samples/s | 10.5 ms | 1281.000000 |
| PyTorch | CUDA, bf16 | 16.7 ms | 382,272 samples/s | 10.5 ms | 91.500000 |
| TensorFlow | CPU | 228.5 ms | 28,014 samples/s | 236.0 ms | 316.611603 |

- **GPU에서 SafeC 대 PyTorch, fp32끼리**: 학습은 작은 모델에서 13배(3.81 ms 대 51.3 ms), 큰 모델에서 5.1배(3.13 ms 대 15.9 ms) 빠르고, 추론은 2.8배와 2.1배 빠릅니다. 이 크기에서 한 스텝은 마이크로초 단위 커널 몇 개와 손실 값을 기다리는 읽기 한 번이라, 실행 한 번에 드는 비용이 결과를 정합니다. SafeC 한 스텝은 약 38 µs(그래프 실행 한 번과 읽기)이고, PyTorch 한 스텝은 같은 cuBLAS 호출 몇 개를 둘러싼 Python, autograd, 디스패처 작업으로 약 510 µs입니다.
- **fp16과 bf16**: 16비트 저장이 SafeC에서 가장 빠른 모드입니다. TF32 그래프보다 학습이 작은 모델에서 21%, 큰 모델에서 18% 빠르고, 큰 모델은 추론도 28% 빠릅니다(3.99 ms 대 5.58 ms). 작은 모델의 추론은 실행 비용에 묶여 있어 변하지 않습니다. 같은 형식의 PyTorch보다 학습은 16배(작은 모델), 8.8–9.1배(큰 모델) 빠르고, 추론은 2.6–2.7배 빠릅니다. fp16과 bf16은 시간이 같습니다. 여기서 PyTorch는 스텝이 호스트 오버헤드에 묶여 있어 16비트로 바꿔도 학습 시간이 거의 변하지 않고(fp32 대비 −6%에서 +9%), 추론만 7–11% 빨라집니다. TF32와 마찬가지로 이 루프는 반올림을 키웁니다: 큰 모델의 50 스텝 후 손실은 fp32의 95.43 대신 fp16에서 93.44, bf16에서 62.01입니다. PyTorch도 비슷하게 퍼집니다(fp32 1754.8, fp16 1281.0, bf16 91.5; 자체 난수 데이터 사용).
- **SafeC의 시간 분해**: 융합으로 실행 10번 중 3번이 빠지면서 8–9% 줄어듭니다. 그래프는 실행 비용이 지배적인 작은 모델에서 38%, 큰 모델에서 17%를 더 줄입니다. TF32는 계산량이 병목이 아닌 작은 모델에서는 차이가 없고, 큰 모델의 학습을 26% 줄입니다. TF32를 쓰면 큰 모델의 50 스텝 후 손실이 95.43 대신 90.69가 됩니다. 이 루프가 반올림 차이를 키우기 때문이며, CPU, fp32 GPU, ROCm 실행은 모두 4자리까지 일치합니다.
- **이 백엔드 이전**: v1.0.0-beta-5에서는 `tensor_matmul_cuda_blas`가 호출마다 CUDA 컨텍스트와 cuBLAS 핸들을 만들고 없앴습니다. 이 GPU에서 그 경로로 작은 벤치마크를 돌리면 학습에 83초, 추론에 816초가 걸렸습니다. fused + graph 행보다 20,000배 넘게 느립니다.
- **CPU**: SafeC 내장 [`sgemm`](/ko/stdlib/simd#gemm)은 이제 이 장비에서 AVX-512 커널을 [스레드 풀](/ko/stdlib/sync#parallel) 위에서 돌립니다. 작은 모델 학습은 24.3 ms로 이전 측정의 SSE2 빌드(58.7 ms)보다 2.4배, beta-5 스칼라 루프(144.0 ms)보다 5.9배 빠르고, 큰 모델은 115.6 ms입니다(2.9배, 20배). 작은 모델에서는 PyTorch CPU 커널(23.2 ms)과 5% 이내이고, 큰 모델에서는 OpenBLAS(195.3 ms)보다 1.7배 빠릅니다. oneMKL을 링크하면 큰 모델 학습이 101.3 ms, 추론이 61.3 ms로 SafeC CPU 행 중 가장 빠릅니다. 큰 모델 학습은 여전히 PyTorch(63.1 ms)가 1.6배 빠릅니다.

### H200에서의 합성곱과 FFT {#h200-conv-fft}

SafeC의 CPU 코드(`cnn.h`, `unet.h`, `fft.h`), GPU 라이브러리 [`gpu_cudnn.h`와 `fft_cuda.h`](/ko/stdlib/ml#cuda-cudnn-cufft)(cuDNN 9.15, CUDA 13.0의 cuFFT), 그리고 두 장치의 PyTorch를 비교합니다. 텐서는 호출 사이에 GPU에 머뭅니다. SafeC CUDA와 PyTorch-CUDA 행은 동기화 두 번 사이에 쌓은 300–2000번 호출의 평균입니다. CPU 행은 측정하지 않는 예열 호출 한 번 뒤 몇 번 호출한 평균입니다. 모든 수치는 학습 작업을 멈춘 상태에서 3회 중 최고값입니다.

SafeC는 합성곱 형태마다 첫 호출에서 계획을 만듭니다. cuDNN graph API가 융합된 conv + bias(+ ReLU) 그래프에 제안하는 엔진 구성과 레거시 API의 최선 알고리즘의 시간을 재서 가장 빠른 것을 씁니다. PyTorch 행도 후보의 시간을 재며(`cudnn.benchmark = True`), PyTorch 기본 휴리스틱으로는 시간이 1% 안에서 같았습니다. "fp32"는 PyTorch에서 `cudnn.allow_tf32 = False`로 두고 SafeC에서 `cuda_set_tf32`를 끈 것이고, "TF32"는 둘 다 켠 것입니다. PyTorch의 합성곱 기본값은 TF32입니다.

합성곱, 호출당 ms(낮을수록 좋음, fp32와 TF32 각각에서 가장 빠른 GPU 행 굵게). `conv64`는 Conv2D 층 하나(64→64 채널, 3×3, stride 1, padding 1, 56×56 맵, bias 포함)이고, `unet128`은 128×128에서 `unet_new(3, 32, 64, 3)`의 순전파(합성곱 다섯 번, max pool 두 번, upsample + concat 두 번)입니다.

| 프레임워크 | 장치 | conv64, 1장 | conv64, 32장 | unet128, 1장 | unet128, 16장 |
|---|---|---|---|---|---|
| SafeC | CPU (Winograd / im2col + 내장 `sgemm`, 12 스레드) | 0.854 | — | 3.31 | — |
| SafeC | CPU, oneMKL | 0.710 | — | 2.65 | — |
| PyTorch | CPU (oneDNN, 12 스레드) | 0.424 | — | 3.29 | — |
| SafeC | CUDA, fp32 | **0.0181** | **0.164** | 0.156 | **0.534** |
| SafeC | CUDA, fp32, U-Net을 그래프 하나로 |  |  | **0.144** |  |
| PyTorch | CUDA, fp32 | 0.0225 | 0.257 | 0.208 | 0.877 |
| SafeC | CUDA, TF32 | **0.0181** | **0.0838** | 0.104 | **0.454** |
| SafeC | CUDA, TF32, U-Net을 그래프 하나로 |  |  | **0.0874** |  |
| PyTorch | CUDA, TF32 (기본값) | 0.0380 | 0.0976 | 0.269 | 0.557 |

GPU의 FFT, 호출당 ms. `fft1d`와 `fft2d`는 호스트 메모리에서 시작해 호스트 메모리로 끝나는 복소수 `double` 배열이라, GPU 호출마다 업로드와 읽기가 포함됩니다(`cuda_fft`/`cuda_fft2d`; `.to("cuda")`, `torch.fft`, `.cpu()`). `batch`와 `rfft`는 이미 GPU에 있는 데이터로 돕니다(`cuda_fft_c2c`/`cuda_fft_r2c`; complex64와 float32).

| 프레임워크 | 장치 | fft1d, 2²⁰ 점 | fft2d, 1024×1024 | batch, 1024 × 4096 | rfft, 1024 × 4096 |
|---|---|---|---|---|---|
| SafeC | CUDA | **1.25** | **1.26** | 0.0244 | **0.0109** |
| PyTorch | CUDA | 2.05 | 2.04 | **0.0238** | 0.0120 |

CPU의 FFT, 호출당 ms. FFTW 3.3.10(Ubuntu 패키지, `FFTW_MEASURE`로 계획), KFR 7.2.0, RustFFT 6.4.1과 비교하며, 변환은 [7800X3D](#mi100-conv-fft)와 같습니다. `rfft`에서는 모든 라이브러리가 중복 없는 n/2 + 1개 bin을 돌려줍니다(SafeC는 `fft_real_half_batch`). "12" 행은 컨테이너의 코어를 모두 씁니다(JSON의 FFTW `16t` 키는 12 스레드로 돌았습니다). 7800X3D 쪽과 달리 단일 스레드 SafeC 행은 없습니다.

배정밀도 (complex128):

| 라이브러리 | 스레드 | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft.h` | 12 | 1.21 | 1.08 | 2.96 | 2.31 |
| SafeC, oneMKL DFTI | 12 | **1.18** | 0.837 | **1.65** | **1.36** |
| FFTW | 12 | 2.35 | 2.59 | 2.95 | 2.02 |
| PyTorch | 12 | 1.18 | **0.654** | — | — |
| FFTW | 1 | 11.4 | 7.27 | 10.7 | 7.35 |
| KFR | 1 | 6.35 | 5.15 | 12.3 | 7.32 |
| RustFFT | 1 | 8.78 | — | 14.8 | — |

단정밀도 (complex64 / float32):

| 라이브러리 | 스레드 | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft_f32` | 12 | 3.06 | 2.01 | 3.16 | 2.36 |
| SafeC, oneMKL DFTI | 12 | 2.85 | **1.11** | **1.60** | 0.812 |
| FFTW (`fftwf`) | 12 | **2.46** | 1.86 | 1.75 | 2.08 |
| PyTorch | 12 | — | — | 3.19 | **0.288** |
| FFTW (`fftwf`) | 1 | 8.72 | 4.29 | 6.19 | 6.50 |
| KFR | 1 | 3.31 | 3.07 | 6.03 | 3.73 |
| RustFFT | 1 | 4.12 | — | 7.75 | — |

- **GPU에서의 합성곱**: 모든 행에서 SafeC가 PyTorch보다 빠릅니다. 이미지 한 장의 합성곱은 0.0181 ms 대 0.0225 ms입니다(1.24배, PyTorch 기본값인 TF32 대비 2.1배). 32장 배치는 fp32에서 1.57배, TF32에서 1.16배 빠릅니다. 배치 16의 U-Net은 fp32에서 1.64배, TF32에서 1.23배 빠르고, 배치 1을 TF32 그래프 하나로 돌리면 3.1배 빠릅니다(0.0874 ms 대 0.269 ms).
- **무엇이 바뀌었나**: [이전 측정](#h200-conv-fft-before)의 세 가지 격차를 Nsight Systems로 프로파일링해 해소했습니다.
  - *이미지 한 장의 합성곱*(이전 0.0335 ms 대 PyTorch 0.0224 ms): 두 쪽 모두 같은 cuDNN 커널을 썼고, 차이는 호스트 시간이었습니다. cuDNN 9의 레거시 `cudnnConvolutionForward`는 호출마다 엔진을 다시 정하느라 CPU를 약 38 µs 쓰는데, 이는 커널 시간보다 길고, bias는 두 번째 호출로 더했습니다. 이제 SafeC는 cuDNN graph API로 합성곱 → + bias(→ ReLU)를 융합한 그래프의 계획을 만들고 variant pack을 보관합니다. 호출 한 번에 호스트 시간 약 6 µs, 커널 하나입니다.
  - *배치 16–32의 TF32*(이전 10–20% 뒤짐): graph API는 레거시 알고리즘 목록에 없는 엔진, 특히 PyTorch가 쓰던 Hopper(`sm90`) 커널에 닿습니다. 또 U-Net은 skip 연결 두 개를 이미지마다 memcpy 한 번으로 복사해서 배치 16에서는 순전파마다 64번 복사했습니다. 이제 concat마다 strided 복사 커널(`cuda_copy_rows`)을 두 번 실행합니다.
  - *호스트 배열 FFT*(이전 2.71 ms 대 2.05 ms): 드라이버는 pageable 메모리를 자체 pinned 버퍼를 거쳐 스레드 하나로 복사하며, 이 호스트에서 16 MB에 약 1.05 ms가 걸립니다. 이제 `cuda_write`/`cuda_read`는 1 MiB 이상의 복사를 레인 네 개(호출 스레드와 보조 스레드 세 개)로 나눕니다. 레인마다 자기 몫을 자기 스트림에서 pinned 버퍼를 거쳐 옮기며, 16 MB를 0.45–0.7 ms에 옮깁니다. FFT 왕복 전체가 2.2배 빨라졌고, 변환 자체는 그중 약 41 µs입니다.
- **CPU 합성곱**: 3×3 stride 1 층은 이제 Winograd F(2×2, 3×3)를, 나머지는 im2col + `sgemm`을 쓰며 모두 AVX-512 커널입니다. `conv64`는 0.854 ms로 이전 측정(1.53 ms)보다 1.8배, beta-5 직접 루프(169.8 ms)보다 199배 빠르고, U-Net은 3.31 ms입니다(2.0배, 138배). PyTorch의 CPU 경로(oneDNN)는 `conv64`에서 여전히 2.0배 빠릅니다. oneMKL을 링크하면 SafeC가 U-Net을 PyTorch보다 1.24배 빠르게 돌립니다(2.65 ms 대 3.29 ms). cuDNN은 배치 1에서 SafeC CPU 경로보다 47배, U-Net에서 21배 빠릅니다.
- **GPU에서의 FFT**: 데이터가 GPU에 있으면 SafeC와 PyTorch 모두 cuFFT를 부르고 시간이 10% 안에서 같습니다. 호스트 배열은 PCIe와 호스트 쪽 복사가 지배합니다. SafeC의 pinned 레인 덕분에 왕복이 PyTorch의 `.to("cuda")` … `.cpu()`보다 1.64배 빠르고, 이제 CPU의 `fft.h`와 거의 같은 시간이 걸립니다(1.25 ms 대 1.21 ms).
- **CPU에서의 FFT, double**: 2²⁰점 변환은 1.21 ms로, 이 장비에서 beta-5(198.1 ms)보다 164배 빠르고, oneMKL·PyTorch(1.18 ms)와 비슷하며, 12 스레드 FFTW보다 1.9배 빠릅니다. 나머지 셋에서는 oneMKL DFTI(batch, rfft)나 PyTorch(fft2d, 0.654 ms 대 SafeC 1.08 ms)가 앞섭니다. batch에서는 SafeC와 FFTW가 비슷하고, `rfft`에서는 SafeC가 FFTW보다 1.15배 느립니다.
- **CPU에서의 FFT, float**: 이 장비에서는 SafeC의 `fft_f32`가 double 코드보다 느립니다. 2²⁰점에서 3.06 ms 대 1.21 ms, 2D에서 2.01 ms 대 1.08 ms이고, batch와 `rfft`는 비슷합니다. 7800X3D에서는 float 행이 double의 0.6–0.8배 시간이 걸립니다. 원인은 아직 조사하지 않았습니다. 2D, batch, (PyTorch 다음으로) `rfft`에서는 oneMKL이, 2²⁰점에서는 FFTW가 앞섭니다.

### H200에서의 어텐션 {#h200-attention}

[`gpu_cuda_attention.h`](/ko/stdlib/ml#cuda-attention)의 flash 어텐션과 linear 어텐션을 PyTorch 2.10과 비교합니다. flash 어텐션은 `scaled_dot_product_attention`(이 GPU에서 PyTorch는 fp16/bf16에 cuDNN fused flash 커널을, fp32에 CUTLASS memory-efficient 커널을 고릅니다)과, linear 어텐션은 벡터화한 PyTorch 구현(non-causal은 einsum 두 번, causal은 64행 청크와 누적 상태 합)과 flash-linear-attention 0.5.2의 Triton `chunk_linear_attn`(causal만)과 비교합니다. 배치 4, 헤드 16, [batch, heads, tokens, head dim] 배치, 데이터는 GPU에 있습니다. 각 수치는 동기화 두 번 사이에 쌓은 10–100번 호출의 평균이며, 학습 작업을 멈춘 상태에서 3회 중 최고값입니다. TFLOPS는 4·B·H·S²·D(causal이면 절반)로 셉니다.

Flash 어텐션, 호출당 ms (TFLOPS):

| 데이터 | head dim | 토큰 | causal | SafeC ms (TFLOPS) | PyTorch ms (TFLOPS) | PyTorch / SafeC |
|---|---|---|---|---|---|---|
| fp16 | 64 | 1024 | 아니오 | **0.043 (396)** | 0.044 (394) | 1.00 |
| fp16 | 64 | 1024 | 예 | 0.036 (238) | **0.035 (243)** | 0.98 |
| fp16 | 64 | 4096 | 아니오 | 0.574 (479) | 0.574 (479) | 1.00 |
| fp16 | 64 | 4096 | 예 | 0.338 (407) | **0.332 (414)** | 0.98 |
| fp16 | 128 | 1024 | 아니오 | 0.060 (572) | **0.060 (574)** | 1.00 |
| fp16 | 128 | 1024 | 예 | **0.049 (352)** | 0.049 (348) | 1.01 |
| fp16 | 128 | 4096 | 아니오 | 0.831 (662) | **0.820 (670)** | 0.99 |
| fp16 | 128 | 4096 | 예 | **0.480 (573)** | 0.481 (571) | 1.00 |
| bf16 | 64 | 1024 | 아니오 | **0.043 (398)** | 0.043 (397) | 1.00 |
| bf16 | 64 | 1024 | 예 | 0.036 (239) | **0.035 (246)** | 0.97 |
| bf16 | 64 | 4096 | 아니오 | **0.572 (480)** | 0.573 (480) | 1.00 |
| bf16 | 64 | 4096 | 예 | 0.337 (407) | **0.331 (416)** | 0.98 |
| bf16 | 128 | 1024 | 아니오 | **0.059 (585)** | 0.059 (583) | 1.00 |
| bf16 | 128 | 1024 | 예 | **0.049 (353)** | 0.050 (346) | 1.02 |
| bf16 | 128 | 4096 | 아니오 | 0.797 (690) | **0.786 (699)** | 0.99 |
| bf16 | 128 | 4096 | 예 | 0.466 (589) | **0.463 (593)** | 0.99 |
| fp32 | 64 | 1024 | 아니오 | **0.503 (34)** | 0.520 (33) | 1.03 |
| fp32 | 64 | 1024 | 예 | 0.358 (24) | **0.351 (24)** | 0.98 |
| fp32 | 64 | 4096 | 아니오 | **7.29 (38)** | 7.78 (35) | 1.07 |
| fp32 | 64 | 4096 | 예 | 4.48 (31) | **4.18 (33)** | 0.93 |
| fp32 | 128 | 1024 | 아니오 | 1.05 (33) | **0.788 (44)** | 0.75 |
| fp32 | 128 | 1024 | 예 | 0.712 (24) | **0.485 (35)** | 0.68 |
| fp32 | 128 | 4096 | 아니오 | 15.8 (35) | **11.9 (46)** | 0.75 |
| fp32 | 128 | 4096 | 예 | 9.02 (30) | **6.18 (44)** | 0.69 |
| bf16 (GQA 32 / 8 헤드) | 128 | 4096 | 예 | 0.892 (616) | **0.884 (622)** | 0.99 |

Linear 어텐션 (phi(x) = elu(x) + 1), 호출당 ms:

| 데이터 | head dim | 토큰 | causal | SafeC | PyTorch (기본값: fp32는 TF32) | PyTorch, IEEE fp32 | fla (Triton) |
|---|---|---|---|---|---|---|---|
| fp32 | 64 | 4096 | 아니오 | 0.359 | **0.351** | 0.501 | — |
| fp32 | 64 | 4096 | 예 | **0.669** | 0.880 | 1.00 | 0.817 |
| fp32 | 64 | 16384 | 아니오 | **1.29** | 1.41 | 1.97 | — |
| fp32 | 64 | 16384 | 예 | **2.50** | 3.32 | 3.74 | 3.08 |
| fp32 | 128 | 4096 | 아니오 | 1.40 | **0.629** | 0.935 | — |
| fp32 | 128 | 4096 | 예 | 2.60 | 1.69 | 2.04 | **1.59** |
| bf16 | 64 | 4096 | 아니오 | **0.340** | 0.503 | — | — |
| bf16 | 64 | 4096 | 예 | **0.644** | 1.03 | — | 0.644 |
| bf16 | 64 | 16384 | 아니오 | **1.23** | 1.97 | — | — |
| bf16 | 64 | 16384 | 예 | 2.43 | 3.89 | — | **2.38** |
| bf16 | 128 | 4096 | 아니오 | 1.39 | **0.921** | — | — |
| bf16 | 128 | 4096 | 예 | 2.57 | 1.98 | — | **1.15** |

- **fp16/bf16 flash 어텐션**은 여기서 PyTorch가 쓰는 것과 같은 cuDNN fused attention 엔진으로 가므로, 모든 행에서 3% 안에서 같습니다(최대 690 TFLOPS). SafeC는 cuDNN 그래프를 직접 만들고(dlopen, 링크 의존성 없음), cuDNN이 없으면 자체 FlashAttention-2 커널(mma.sync)로 돌립니다. grouped-query 어텐션도 PyTorch와 같은 속도입니다.
- **fp32 flash 어텐션**은 SafeC의 FlashAttention-2 커널을 텐서 코어에서 3xTF32(곱마다 TF32 두 조각의 hi·hi + hi·lo + lo·hi)로 돌립니다. CUTLASS와 같은 방법이며 오차는 ~2e-7입니다. head dim 64에서는 PyTorch와 7% 안이고 causal 마스크가 없으면 더 빠릅니다. head dim 128에서는 PyTorch의 0.68–0.75입니다: SafeC는 SM당 블록 두 개가 들어가도록 32키 타일을 쓰고, CUTLASS는 64×128 타일을 레지스터에 둡니다.
- **Linear 어텐션, head dim 64**: SafeC의 청크 병렬 커널(청크별 상태, 청크에 걸친 prefix, 모든 청크의 출력을 한 번에)은 벡터화한 PyTorch보다 bf16에서 1.5–1.6배, fp32 causal에서 1.3배 빠르고, fp32 non-causal은 PyTorch의 TF32 einsum과 비슷합니다(0.98–1.09). fla와는 bf16에서 같고 fp32에서 1.2배 빠릅니다. SafeC는 정확한 fp32로 계산합니다: NVIDIA NGC 컨테이너의 PyTorch fp32 einsum은 기본으로 TF32를 쓰고(`TORCH_ALLOW_TF32_CUBLAS_OVERRIDE=1`), fla의 fp32 출력은 정확한 결과와 ~1e-3 차이가 나서 내부에서 TF32 내적을 쓰는 것으로 보입니다. IEEE fp32 PyTorch보다는 1.4–1.5배 빠릅니다.
- **Linear 어텐션, head dim 128**은 SafeC의 약점입니다: PyTorch와 fla의 0.45–0.78. 청크 커널이 CUDA 코어에서 돌며, 텐서 코어 버전(m16n8k16 / 3xTF32)도 시도했지만 아직 빨라지지 않았으므로 시간은 다른 곳(패스 사이의 상태 트래픽)에 쓰입니다.
- `tests/stdlib/cuda/cuda_attention_check.sc`가 모든 경로(cuDNN, 자체 flash 커널 두 가지, CUDA 코어 대체 경로, 청크/순차 linear 어텐션, 두 인자 형태, sliding window, GQA, 그래프 재생)를 배정밀도 기준값 및 attention.h의 CPU 함수와 비교합니다: 97개 검사.

::: details 소스
[attn_cuda.sc](/benchmarks/attention/safec/attn_cuda.sc) · [attn.py](/benchmarks/attention/pytorch/attn.py) · [gpu_cuda_attention.h](/benchmarks/stdlib/gpu_cuda_attention.h) · [gpu_cuda_attention.sc](/benchmarks/stdlib/gpu_cuda_attention.sc)
:::

### Xeon 8570의 CPU DSP {#h200-dsp}

이 장비의 [DSP 작업](#dsp-cpu), 호출당 ms(낮을수록 좋음, 가장 빠른 값 굵게): [`std/dsp`](/ko/stdlib/dsp) 단독과 oneMKL 링크를 SciPy 1.16.3, KFR 7.2.0, 그리고 biquad는 Q와 madronalib(float32만), 리샘플링은 dasp와 비교합니다.

배정밀도:

| 작업 | SafeC | SafeC, oneMKL | SciPy | KFR | 기타 |
|---|---|---|---|---|---|
| fir stream | 7.46 | **7.37** | 27.5 | 42.8 |  |
| fir block | 0.730 | **0.686** | 25.1 | — |  |
| fftconv | 4.54 | **3.82** | 60.5 | 6.57 |  |
| biquad | **0.782** | **0.782** | 6.99 | 4.45 |  |
| stft | 5.69 | **5.47** | 92.4 | — |  |
| resample | **7.38** | 8.03 | 17.5 | 88.4 (draft 58.1) | dasp 656.9 |
| conv2d | **0.601** | 0.715 | 53.2 | — |  |

단정밀도:

| 작업 | SafeC | SafeC, oneMKL | SciPy | KFR | 기타 |
|---|---|---|---|---|---|
| fir stream | **3.83** | 3.85 | 16.0 | 37.2 |  |
| fir block | **0.649** | 0.673 | 15.7 | — |  |
| fftconv | 4.23 | **2.21** | 32.2 | 3.28 |  |
| biquad | **0.773** | 0.785 | 6.34 | 4.26 | Q 2.81, madronalib 5.37 |
| stft | 4.88 | **4.14** | 95.1 | — |  |
| resample | 11.1 | **10.8** | 16.5 | 53.2 (draft 60.6) |  |

- 두 정밀도 모두, 모든 행에서 SafeC 빌드 중 하나가 가장 빠릅니다. 내장 코드가 지는 것은 float `fftconv`뿐이며 KFR에 집니다(4.23 ms 대 3.28 ms). oneMKL의 FFT를 링크하면 2.21 ms이고, double에서도 oneMKL이 `fftconv`와 `stft`를 조금 줄입니다.
- double에서 SciPy 대비: 스트리밍 FIR 3.7배, 블록 FIR 34배, `fftconv` 13배, biquad 8.9배, STFT 16배, 리샘플링 2.4배, 5×5 이미지 합성곱 89배 빠릅니다. float biquad는 Q보다 3.6배, KFR보다 5.5배, madronalib보다 6.9배 빠릅니다.
- **float 대 double**: float는 스트리밍 FIR을 절반으로 줄이고(3.83 대 7.46 ms) STFT를 조금 줄입니다. biquad는 지연에 묶여 있어 변하지 않습니다. float 리샘플러는 double보다 느리며(11.1 대 7.38 ms), 그 차이가 7800X3D(3.96 대 3.71)보다 큽니다.

### Xeon 8570에서의 기본 벤치마크 {#xeon-8570-classic}

릴리스 빌드 실행 시간, 3회 중 최고값(낮을수록 좋음, 가장 빠른 값 굵게). 웹 서비스는 초당 요청 수입니다(높을수록 좋음). 멀티스레드와 웹 벤치마크의 Zig 소스는 Zig 0.16용으로 작성되어, `**` 배열 반복 문법을 바꾸고 `@cImport`를 없앤 0.17.0에서는 빌드되지 않습니다.

| 벤치마크 | SafeC | C | C++ | Rust | Zig | Go | Python |
|---|---|---|---|---|---|---|---|
| fib(37) | 0.089s | 0.087s | 0.089s | 0.092s | **0.072s** | 0.132s | 2.432s |
| n-body | **0.145s** | 0.146s | 0.147s | 0.155s | 0.154s | 0.190s | 9.337s |
| binary-trees (SafeC: arena) | **0.282s** | 1.175s | 1.363s | 1.405s | 1.253s | 3.510s | 29.166s |
| binary-trees, SafeC 힙 | 0.945s | | | | | | |
| binary-trees, 8 스레드 (SafeC: arena) | **0.092s** | 0.417s | 0.464s | 0.457s | N/A | 0.410s | 76.851s |
| binary-trees, 8 스레드, SafeC 힙 | 0.333s | | | | | | |
| SIMD, 명시적 벡터 | 0.111s | 0.118s | 0.117s | 0.112s | **0.098s** | — | 0.333s (NumPy) |
| 웹 서비스 (req/s) | **33,730** | 32,196 | 32,523 | 32,592 | N/A | 32,219 | 2,887 |

컬렉션 (1,000,000개 요소, ms): `Vec` push 3.33 대 C 1.95, `vec_sort` **76.3** 대 `qsort` 149.0, `HashMap` insert 56.3 대 **53.9**, get 20.1 대 **18.6**, `List` push+pop **13.3** 대 27.2, `Queue` **3.30** 대 6.64, `BST` insert 348.8 대 **312.0**, get 351.0 대 **337.8**. 할당자: 번갈아 할당·해제하는 쌍 하나에 `std::alloc`/`dealloc` 10.4 ns 대 `malloc`/`free` **8.83** ns, 일괄 패턴은 **120** ms 대 126 ms.

`std::alloc`의 새 [slab](/ko/stdlib/mem#allocation-performance)이 여기서 드러납니다: SafeC 힙 binary-trees가 이전 측정보다 17%(1.141 → 0.945 s), 8 스레드에서 16%(0.398 → 0.333 s) 빨라졌고, `List`와 `Queue`는 약 25% 빨라졌으며, 일괄 할당 패턴은 159 ms에서 120 ms로 줄어 이제 glibc `malloc`보다 빠릅니다. 나머지 행은 이전 측정과 10% 안에서 같습니다.

::: details 소스
[train_cuda.sc](/benchmarks/ml/safec/train_cuda.sc) · [gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [conv_cpu.sc](/benchmarks/conv/safec/conv_cpu.sc) · [conv_cuda.sc](/benchmarks/conv/safec/conv_cuda.sc) · [PyTorch conv.py](/benchmarks/conv/pytorch/conv.py) · [fft_cpu.sc](/benchmarks/fft/safec/fft_cpu.sc) · [fft_cuda.sc](/benchmarks/fft/safec/fft_cuda.sc) · [PyTorch fft.py](/benchmarks/fft/pytorch/fft.py) · [gpu_cudnn.h](/benchmarks/stdlib/gpu_cudnn.h) · [gpu_cudnn.sc](/benchmarks/stdlib/gpu_cudnn.sc) · [fft_cuda.h](/benchmarks/stdlib/fft_cuda.h) · [fft_cuda.sc](/benchmarks/stdlib/fft_cuda.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [PyTorch 큰 모델 train.py](/benchmarks/ml_big/pytorch/train.py) · 기본 벤치마크 소스는 위의 각 절에 있습니다.
:::


## 다른 플랫폼의 이전 측정 {#earlier-runs}

아래 숫자는 Apple M1 Pro(macOS 26.5.1, Apple Clang 21.0.0)와, 같은 7800X3D의 WSL2 Ubuntu 22.04(clang 19.1.7) 및 Windows 11(clang/LLVM ~19)에서 예전에 측정한 것입니다. 그 뒤로 다시 측정하지 않았고 컴파일러와 std가 바뀌었으므로, 서로끼리만 비교하세요.

::: details fib(37)
| 언어 | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.080s | 0.040s | 0.046s |
| C | 0.070s | 0.040s | 0.046s |
| C++ | 0.070s | 0.040s | 0.047s |
| Rust | 0.090s | 0.040s | 0.049s |
| Zig | 0.080s | 0.040s | 0.053s |
| Go | 0.080s | 0.080s | 0.092s |
| Python | 2.700s | 3.330s | 2.485s |
:::

::: details n-body
| 언어 | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.110s | 0.090s | 0.102s |
| C | 0.100s | 0.090s | 0.103s |
| C++ | 0.110s | 0.090s | 0.104s |
| Rust | 0.110s | 0.100s | 0.113s |
| Zig | 0.110s | 0.110s | 0.116s |
| Go | 0.100s | 0.100s | 0.113s |
| Python | 9.890s | 12.150s | 11.922s |
:::

::: details binary-trees
| 언어 | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC (arena) | 0.210s | 0.130s | 0.147s |
| SafeC (힙) | 1.150s | 0.620s | 1.119s |
| C | 1.560s | 0.820s | 1.965s |
| C++ | 1.860s | 0.930s | 2.025s |
| Rust | 1.740s | 0.900s | 2.184s |
| Zig | 1.570s | 0.880s | 2.020s |
| Go | 1.260s | 1.150s | 1.125s |
| Python | 20.930s | 20.130s | 10.019s |
:::

::: details binary-trees, 8 스레드
| 언어 | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC (arena) | 0.090s | 0.075s | 0.045s |
| SafeC (힙) | 0.680s | 0.308s | 0.367s |
| C | 0.610s | 0.274s | 0.549s |
| C++ | 0.630s | 0.290s | 0.566s |
| Rust | 0.680s | 0.319s | 0.591s |
| Zig | 0.540s | 0.280s | 0.563s |
| Go | 0.440s | 0.358s | 0.320s |
| Python | 24.430s | 26.626s | 10.055s |
:::

::: details SIMD
| 언어 | 스칼라: macOS / WSL2 / Windows | 명시적 SIMD: macOS / WSL2 / Windows |
|---|---|---|
| SafeC | 0.040s / 0.099s / 0.067s | 0.030s / 0.092s / 0.060s |
| C | 0.040s / 0.099s / 0.077s | 0.030s / 0.092s / 0.060s |
| C++ | 0.040s / 0.101s / 0.073s | 0.030s / 0.092s / 0.073s |
| Rust | 0.040s / 0.099s / 0.044s | 0.030s / 0.093s / 0.039s |
| Zig | 0.040s / 0.096s / 0.065s | 0.030s / 0.089s / 0.069s |
| Go | 0.060s / 0.105s / 0.074s | N/A |
| Python | 3.500s / 3.144s / 3.406s | 0.140s / 0.213s / 0.313s (NumPy) |
:::

::: details 웹 서비스
Windows는 `ab` 대신 작은 Go 부하 생성기를 `-n 5000 -c 20`으로 썼으므로 그 열은 자기 자신과만 비교하세요.

| 언어 | macOS req/s (p50/p99) | WSL2 req/s (p50/p99) | Windows req/s (p50/p99) |
|---|---|---|---|
| SafeC | 36055 (1/5ms) | 17650 (3/4ms) | 2313 (7.5/19.6ms) |
| C | 25303 (2/2ms) | N/A | N/A |
| C++ | 24148 (2/4ms) | N/A | N/A |
| Rust | 27270 (2/2ms) | 18077 (3/3ms) | 465 (33.0/474.3ms) |
| Zig | 25742 (2/2ms) | N/A | N/A |
| Go | 25274 (2/3ms) | 18758 (3/4ms) | 2467 (7.8/16.9ms) |
| Python | 4790 (10/33ms) | 2357 (21/27ms) | 528 (37.3/52.5ms) |
:::

::: details ML, 작은 MLP (Apple M1 Pro)
| 프레임워크 | 장치 | 학습 (100 스텝) | 추론 (1000회) |
|---|---|---|---|
| SafeC | CPU (Accelerate BLAS) | 16.8 ms | 26.1 ms |
| SafeC | MPS | 39.4 ms | 61.1 ms |
| PyTorch | CPU | 23.1 ms | 13.5 ms |
| PyTorch | MPS | 131.6 ms | 358.7 ms |
| TensorFlow | CPU | 83.0 ms | 245.0 ms |
| TensorFlow | GPU | 245.3 ms | 924.6 ms |
| MLX | GPU | 72.2 ms | 309.1 ms |
:::

::: details ML, 더 큰 모델 (Apple M1 Pro)
| 프레임워크 | 장치 | 학습 (50 스텝) | 추론 (200회) |
|---|---|---|---|
| SafeC | CPU (Accelerate BLAS) | 89.2 ms | 61.4 ms |
| SafeC | MPS | 47.9 ms | 47.3 ms |
| PyTorch | CPU | 67.8 ms | 48.2 ms |
| PyTorch | MPS | 110.5 ms | 86.4 ms |
| TensorFlow | CPU | 148.8 ms | 197.6 ms |
| TensorFlow | GPU | 154.1 ms | 218.3 ms |
| MLX | GPU | 54.3 ms | 74.4 ms |
:::
