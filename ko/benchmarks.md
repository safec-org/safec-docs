---
outline: [2, 4]
---

# 벤치마크

## 초록 {#abstract}

SafeC를 대표적인 마이크로벤치마크에서 C, C++, Rust, Zig, Go, Python과 비교합니다. 표준 라이브러리는 컬렉션, 할당, HTTP 서버, 신호 처리, 머신러닝에서 일반 C, glibc, 벤더 수학 라이브러리, NumPy/SciPy, FFTW, KFR, PyTorch, TensorFlow, MLX와 비교합니다. 같은 벤치마크 모음을 세 대의 머신에서 돌립니다: 데스크톱(AMD Ryzen 7 7800X3D + AMD Instinct MI100), GPU 서버(Intel Xeon Platinum 8570 + NVIDIA H200), 노트북(Apple M1 Pro). 각 테스트는 세 머신을 나란히 보여 줍니다.

- **계산 위주 코드**(fib, n-body, SIMD)는 세 머신 모두에서 C와 7% 이내입니다.
- **할당 위주 코드**에서는 SafeC의 `region`/`arena`가 효과를 냅니다: binary-trees가 단일 스레드에서 C보다 4.2–7.0배, 8 스레드에서 4.3–9.3배 빠릅니다.
- **`std/dsp`**는 세 머신 모두에서 모든 DSP 작업에 걸쳐 double과 float 모두 가장 빠른 라이브러리입니다.
- **머신러닝**에서 각 프레임워크의 최적 구성끼리 비교하면:
  - SafeC 자체 GPU 백엔드가 작은 MLP를 PyTorch의 최적 구성보다 2.6배(MI100), 7.8배(H200) 빠르게 학습합니다.
  - Gemma 4 E2B를 PyTorch의 최적 구성보다 3.1배(MI100), 1.5배(H200) 빠르게 디코딩합니다.
  - M1 Pro에서는 MLX가 Gemma를 SafeC보다 2.6배 빠르게 디코딩하고, fp16 flash 어텐션도 1.2배 빠릅니다.

## 목차 {#index}

1. [서론](#introduction)
   - [환경](#environments) · [방법](#method) · [표 읽는 법](#reading-the-tables)
2. [본문](#main-article)
   - [언어와 런타임](#language-runtime): [fib(37)](#fib) · [n-body](#n-body) · [binary-trees](#binary-trees) · [binary-trees, 8 스레드](#binary-trees-mt) · [SIMD](#simd) · [컴파일 시간](#compile-time)
   - [표준 라이브러리](#standard-library): [컬렉션](#collections) · [할당자](#allocator) · [웹 서비스](#web-service)
   - [신호 처리](#signal-processing): [DSP](#dsp) · [FFT](#fft)
   - [머신러닝](#machine-learning): [MLP 학습과 추론](#ml-mlp) · [합성곱과 U-Net](#ml-convolution) · [어텐션](#ml-attention) · [LLM 추론: Gemma 4 E2B](#ml-llm)
3. [요약](#summary)

## 1. 서론 {#introduction}

이 벤치마크는 테스트마다 하나의 질문에 답합니다: 같은 머신에서 SafeC, 또는 그 표준 라이브러리는 사람들이 대신 쓰는 것과 비교해 어떤가? 대표적인 마이크로벤치마크는 [The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html)을 따릅니다. 라이브러리 테스트는 각 `std` 모듈을 그 일에 널리 쓰이는 라이브러리와 비교합니다. 모든 수치는 "이 머신에서 대략 이 정도"로 읽어 주세요: 세 머신은 CPU, 코어 수, OS, 도구 버전이 모두 다르므로 언어와 라이브러리는 한 머신의 열 안에서 비교하고, 열끼리는 비교하지 마세요.

### 환경 {#environments}

| | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| 역할 | 데스크톱 | GPU 서버 | 노트북 |
| CPU | AMD Ryzen 7 7800X3D, 8코어 / 16스레드, AVX-512 | Intel Xeon Platinum 8570, 112코어 중 12코어(컨테이너), AVX-512 | Apple M1 Pro, 성능 코어 8개 + 효율 코어 2개, NEON |
| GPU | AMD Instinct MI100(gfx908, 32 GB HBM2), ROCm 6.4 / 7.1(HIP, hiprtc, rocBLAS, MIOpen, hipFFT) | NVIDIA H200(sm_90, 141 GB HBM3e), CUDA 13.0, cuDNN 9.15, 드라이버 580.126.20 | M1 Pro GPU(Metal) |
| OS | Ubuntu 24.04.5, 네이티브 | Ubuntu 24.04.3(Backend.AI 컨테이너) | macOS 26.6 |
| 컴파일러 | `safec` 1.0.0, clang 21.1.8, Rust 1.100.0-nightly, Go 1.27.2, Zig 0.16.0 | `safec` 1.0.0, clang 21.1.8, Rust 1.99.1, Go 1.27.3, Zig 0.17.0 | `safec` 1.0.0, Homebrew clang 22.1, Rust 1.98, Go 1.26, Zig 0.16 |
| Python 환경 | Python 3.12.3, NumPy 2.5.4, SciPy 1.18.1, PyTorch 2.9.1+rocm6.4, transformers 5.20.2, TensorFlow 2.21(CPU) | Python 3.12.3, SciPy 1.16.3, PyTorch 2.10(NGC 25.11), transformers 5.20.2, TensorFlow 2.21(CPU) | Python 3.14, NumPy 2.5.4, SciPy 1.18.1, PyTorch 2.13(`mps`), MLX 0.32 |
| 네이티브 라이브러리 | OpenBLAS, AOCL-BLAS 5.3.2, oneMKL 2026.2, FFTW 3.3.10, KFR 7.2.0, RustFFT 6.4.1, dasp 0.11, Q, madronalib | OpenBLAS, oneMKL 2026.2, FFTW 3.3.10, KFR 7.2.0, RustFFT 6.4.1, dasp 0.11, Q, madronalib | Accelerate, KFR 7, dasp 0.11, Q, madronalib |
| 결과 | [linux-7800x3d-mi100.json](/benchmarks/results/linux-7800x3d-mi100.json) | [linux-xeon8570-h200.json](/benchmarks/results/linux-xeon8570-h200.json) | [macos-m1pro.json](/benchmarks/results/macos-m1pro.json) |

서드파티 라이브러리는 각자의 벤치마크 프로그램에만 빌드·링크하며, SafeC의 std에는 절대 넣지 않습니다. H200 서버는 학습 작업과 GPU를 공유하는데, 모든 측정 동안 그 작업을 멈춰(`SIGSTOP`) GPU와 12코어가 유휴 상태였습니다.

### 방법 {#method}

- **빌드**: C, C++, SafeC는 `-O2`(SafeC는 LLVM IR로 컴파일한 뒤 `safeguard build --release`처럼 `clang -O2`), `zig build-exe -O ReleaseFast`, `rustc -O`, 일반 `go build`. SafeC의 CPU 커널은 실행 시점에 CPU의 [SIMD 수준](/ko/stdlib/simd#cpu-levels)에 맞는 코드를 고릅니다.
- **시간**: 3번 실행 중 가장 짧은 실제 경과 시간. GPU와 라이브러리 행은 워밍업 호출 뒤 두 동기화 사이에 큐에 넣은 여러 호출의 평균이며, 3번 실행 중 최솟값입니다.
- **메모리**: 같은 실행의 최대 RSS(GNU `/usr/bin/time -f %M`). 웹 서버는 종료 직전 `/proc/<pid>/status`의 `VmHWM`.
- **정확성**: 모든 프로그램의 출력을 정답과 대조한 뒤에만 시간을 셉니다. ML 테스트는 손실도 확인하고, LLM은 생성된 토큰과 로짓을 `transformers`와 대조합니다.
- **재현**: [`run_bench.py`](/benchmarks/run_bench.py)가 모두 빌드·실행하고 머신별 결과 JSON을 씁니다.

### 표 읽는 법 {#reading-the-tables}

- 열은 세 머신입니다. 따로 표시하지 않으면 낮을수록 좋고, 각 열(또는 각 행 묶음)의 최솟값은 **굵게** 표시합니다.
- 한 행에 여러 라이브러리가 있는 표는 셀마다 머신별 값 하나씩을 씁니다.
- "—"는 그 머신에서 측정하지 않았다는 뜻이고, "N/A"는 측정했지만 그 항목을 빌드하거나 실행하지 못했다는 뜻입니다.
- **머신러닝 표는 머신마다 각 프레임워크의 최적 구성만 보여 줍니다**(CPU 또는 GPU, fp32·TF32·16비트, 그래프 재생 여부). 셀에 그 구성을 적었고, 나머지 구성은 결과 JSON에 있습니다.

## 2. 본문 {#main-article}

### 2.1 언어와 런타임 {#language-runtime}

#### fib(37) — 재귀 피보나치 {#fib}

단순 재귀 피보나치: 함수 호출과 정수 연산 오버헤드만 있고 할당도 I/O도 없습니다. 릴리스 시간 · 최대 메모리.

| 언어 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | **0.042** s · 1.9 MB | 0.089 s · 8.8 MB | 0.083 s · 5.6 MB |
| C | **0.042** s · 1.9 MB | 0.087 s · 8.8 MB | 0.082 s · 1.3 MB |
| C++ | **0.042** s · 3.8 MB | 0.089 s · 10.5 MB | 0.088 s · 1.3 MB |
| Rust | **0.042** s · 1.9 MB | 0.092 s · 10.5 MB | **0.081** s · 1.5 MB |
| Zig | 0.043 s · 0.6 MB | **0.072** s · 2.8 MB | **0.081** s · 1.5 MB |
| Go | 0.089 s · 2.0 MB | 0.132 s · 2.8 MB | 0.089 s · 3.9 MB |
| Python | 2.016 s · 10.4 MB | 2.432 s · 19.2 MB | 2.715 s · 14.5 MB |

LLVM을 거치는 언어들은 모두 몇 % 이내로 겹치며, 차이는 잡음 수준입니다. Xeon에서는 Zig가 가장 빠릅니다(0.072 s, 다른 언어 0.087–0.092 s).

::: details 소스
[fib.sc](/benchmarks/fib/safec/fib.sc) · [fib.c](/benchmarks/fib/c/fib.c) · [fib.cpp](/benchmarks/fib/cpp/fib.cpp) · [fib.rs](/benchmarks/fib/rust/fib.rs) · [fib.zig](/benchmarks/fib/zig/fib.zig) · [fib.go](/benchmarks/fib/go/fib.go) · [fib.py](/benchmarks/fib/python/fib.py)
:::

#### n-body — 5체 궤도 시뮬레이션 {#n-body}

Benchmarks Game의 n-body 테스트(태양, 목성, 토성, 천왕성, 해왕성), 2,000,000 스텝: 부동소수점 연산 위주이며 할당이 없고 작업 집합이 아주 작습니다. SafeC의 초기 상태는 운동량 보정까지 `consteval` 표라서 프로그램이 상수에서 시작합니다. 시뮬레이션 자체는 다른 언어와 같은 코드입니다.

| 언어 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | **0.098** s · 2.0 MB | **0.145** s · 8.8 MB | 0.119 s · 5.6 MB |
| C | 0.099 s · 2.0 MB | 0.146 s · 8.8 MB | 0.112 s · 1.3 MB |
| C++ | 0.099 s · 3.8 MB | 0.147 s · 10.5 MB | 0.112 s · 1.3 MB |
| Rust | 0.107 s · 2.0 MB | 0.155 s · 10.5 MB | 0.118 s · 1.5 MB |
| Zig | 0.110 s · 0.5 MB | 0.154 s · 2.8 MB | 0.117 s · 1.5 MB |
| Go | 0.106 s · 2.0 MB | 0.190 s · 2.8 MB | **0.103** s · 3.9 MB |
| Python | 7.630 s · 10.9 MB | 9.337 s · 19.2 MB | 9.882 s · 15.2 MB |

두 x86-64 머신에서는 SafeC가 가장 빠릅니다. M1 Pro에서는 C의 0.94배, Go의 0.87배로, 계산 테스트 중 유일하게 뒤처집니다.

::: details 소스
[nbody.sc](/benchmarks/nbody/safec/nbody.sc) · [nbody.c](/benchmarks/nbody/c/nbody.c) · [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp) · [nbody.rs](/benchmarks/nbody/rust/nbody.rs) · [nbody.zig](/benchmarks/nbody/zig/nbody.zig) · [nbody.go](/benchmarks/nbody/go/nbody.go) · [nbody.py](/benchmarks/nbody/python/nbody.py)
:::

#### binary-trees — 할당 스트레스 {#binary-trees}

작은 이진 트리 수백만 개를 만들고 버립니다(최대 깊이 18): 산술보다 메모리 관리를 시험합니다. 기본 SafeC 항목은 `region`/`arena<R>`(범프 포인터 할당, `arena_reset<R>()`가 영역 전체를 O(1)에 버림)을 쓰며, 수명이 짧은 할당뿐인 이 작업이 바로 그 용도입니다. **SafeC (힙)**은 같은 프로그램을 `new` / `std::dealloc`으로 바꾼 것으로, 다른 언어의 `malloc`, `Box`, GC와 같은 조건의 비교입니다.

| 언어 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC (아레나) | **0.132** s · 25.9 MB | **0.282** s · 33.2 MB | **0.222** s · 29.6 MB |
| SafeC (힙) | 0.616 s · 38.8 MB | 0.945 s · 47.1 MB | 0.830 s · 50.3 MB |
| C | 0.810 s · 33.9 MB | 1.175 s · 42.0 MB | 1.562 s · 17.5 MB |
| C++ | 0.924 s · 35.9 MB | 1.363 s · 43.8 MB | 1.720 s · 17.5 MB |
| Rust | 0.914 s · 34.0 MB | 1.405 s · 43.8 MB | 1.744 s · 17.6 MB |
| Zig | 0.854 s · 33.6 MB | 1.253 s · 42.0 MB | 1.549 s · 17.6 MB |
| Go | 0.718 s · 36.8 MB | 3.510 s · 34.8 MB | 1.274 s · 39.1 MB |
| Python | 18.05 s · 82.6 MB | 29.17 s · 92.0 MB | 23.25 s · 87.1 MB |

- 아레나 버전은 C보다 4.2–7.0배 빠르고, 두 Linux 머신에서는 메모리도 가장 적게 씁니다.
- 힙 버전도 세 머신 모두에서 C, C++, Rust, Zig보다 빠릅니다. `std::alloc`이 16바이트 노드를 스레드별 [slab](/ko/stdlib/mem#allocation-performance)에서 꺼내 주고, 그 앞에 해제된 블록 캐시가 있습니다.
- 메모리는 C보다 많이 씁니다. 모든 블록에 16바이트 안전 헤더(이중 해제·해제 후 사용 검사)가 붙고, 캐시와 예비 slab을 재사용하려고 남겨 두기 때문입니다. macOS에서는 C의 `malloc`이 특히 작습니다(17.5 MB).

::: details 소스
[binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc) · [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c) · [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp) · [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs) · [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig) · [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go) · [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)
:::

#### binary-trees, 8 스레드 {#binary-trees-mt}

같은 작업을 작업 스레드 8개로 나눕니다: 깊이마다 각 스레드가 트리의 독립된 일부를 만들고 검사하며, 다음 깊이 전에 합칩니다. SafeC (아레나)는 스레드마다 자기 `region`/`arena<R>`를 쓰고(공유하거나 잠그는 아레나 상태 없음), SafeC (힙)은 `new` / `std::dealloc`을 씁니다.

| 언어 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC (아레나) | **0.053** s · 98.4 MB | **0.092** s · 95.9 MB | **0.064** s · 93.9 MB |
| SafeC (힙) | 0.229 s · 200.4 MB | 0.333 s · 202.9 MB | 0.160 s · 203.6 MB |
| C | 0.226 s · 161.8 MB | 0.417 s · 161.0 MB | 0.592 s · 69.7 MB |
| C++ | 0.227 s · 163.5 MB | 0.464 s · 168.0 MB | 0.613 s · 71.6 MB |
| Rust | 0.232 s · 161.9 MB | 0.457 s · 164.5 MB | 0.683 s · 78.3 MB |
| Zig | 0.226 s · 163.8 MB | N/A | 0.585 s · 68.3 MB |
| Go | 0.248 s · 96.4 MB | 0.410 s · 139.9 MB | 0.496 s · 141.8 MB |
| Python | 22.96 s · 311.1 MB | 76.85 s · 320.8 MB | 25.36 s · 355.7 MB |

아레나 버전은 C보다 4.3–9.3배 빠릅니다. 힙 버전은 7800X3D에서 C와 같고 Xeon과 M1 Pro에서는 C보다 빠르며, 스레드마다 해제된 블록 캐시와 예비 slab을 따로 두므로 메모리를 더 씁니다. Xeon의 Zig 0.17은 0.16용으로 쓴 이 소스를 더 이상 빌드하지 못합니다. Python은 스레드를 쓰면 단일 스레드보다 느려집니다.

::: details 소스
[binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc) · [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c) · [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp) · [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs) · [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig) · [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go) · [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)
:::

#### SIMD — double 20,000,000개의 제곱합 {#simd}

릴리스 최적화에서 일반 스칼라 루프와 각 언어의 명시적 벡터 타입을 비교합니다: 자동 벡터화 위에 명시적 SIMD가 무엇을 더하는지 봅니다. SafeC는 x86-64 단계별([AVX-512 > AVX2 > AVX > SSE4.1 > SSE2](/ko/stdlib/simd#cpu-levels))로 한 번씩 빌드한 `vec<double, 8>`을 실행 시점에 고르고, C와 C++는 벡터 확장, Zig는 `@Vector`, Rust는 안정 채널 인트린식(x86-64는 SSE2, AArch64는 NEON)을 씁니다. Go에는 이식 가능한 벡터 타입이 없습니다. 셀은 스칼라 → 명시적 벡터(초)입니다.

| 언어 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | 0.080 → 0.075 | 0.121 → 0.111 | 0.045 → **0.031** |
| C | 0.080 → 0.077 | 0.115 → 0.118 | 0.044 → **0.031** |
| C++ | 0.081 → 0.074 | 0.125 → 0.117 | 0.043 → 0.032 |
| Rust | 0.080 → 0.077 | 0.116 → 0.112 | 0.046 → 0.041 |
| Zig | 0.079 → **0.072** | 0.104 → **0.098** | 0.049 → 0.033 |
| Go | 0.085 | 0.118 | 0.062 |
| Python | 3.117 → 0.186 (NumPy) | 4.080 → 0.333 (NumPy) | 3.626 → 0.152 (NumPy) |

이 루프는 double 160 MB를 훑으므로 메모리 대역폭에 묶입니다: 컴파일 언어들은 7800X3D와 M1 Pro에서 약 10 ms, Xeon에서 20 ms 이내로 겹치며, 이는 프로세스 시작 잡음과 비슷한 크기입니다. 7800X3D에서 합산 자체는 모든 단계에서 약 3.7 ms(약 43 GB/s)이고, 나머지는 스칼라 초기화 루프와 배열의 페이지 폴트입니다.

::: details 소스
[simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc) · [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c) · [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp) · [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs) · [simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs) · [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig) · [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go) · [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)
:::

#### 컴파일 시간 {#compile-time}

fib(37)의 콜드 릴리스 빌드입니다(SafeC는 비트코드 캐시를 쓰지 않도록 `--no-incremental`). 디버그 빌드 시간은 결과 JSON에 있습니다.

| 언어 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | 0.036 s | 0.119 s | 0.160 s |
| C | 0.030 s | 0.099 s | 0.131 s |
| C++ | 0.049 s | 0.127 s | 0.130 s |
| Rust | 0.050 s | 0.162 s | 0.141 s |
| Zig | 8.18 s | 14.48 s | 5.40 s |
| Go | 0.025 s | 0.064 s | 2.29 s |

SafeC는 C, C++와 비슷한 속도로 컴파일합니다. Zig의 콜드 빌드는 5–14 s가 걸립니다.

### 2.2 표준 라이브러리 {#standard-library}

#### 컬렉션 — 요소 1,000,000개 {#collections}

제네릭 컨테이너를 같은 알고리즘의 일반 C 구현과 비교합니다: 늘어나는 배열과 `qsort`, 같은 해시를 쓰는 선형 탐사 해시 맵, 노드마다 `malloc`하는 이중 연결 리스트, 2의 거듭제곱 링 큐, 균형을 맞추지 않는 BST. 양쪽 모두 같은 섞인 키와 clang `-O2`를 씁니다. 셀은 SafeC / C(ms)와 그 비율입니다(1보다 작으면 SafeC가 빠름).

| 연산 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| `Vec<int>` push | 2.31 / **1.51** (1.53×) | 3.33 / **1.95** (1.71×) | 0.966 / **0.790** (1.22×) |
| `vec_sort` vs `qsort` | **43.5** / 83.4 (0.52×) | **76.3** / 149 (0.51×) | **56.4** / 88.8 (0.64×) |
| `HashMap<int,int>` insert | 37.9 / **34.3** (1.10×) | 56.3 / **53.9** (1.04×) | 31.1 / **28.8** (1.08×) |
| `HashMap<int,int>` get | 8.36 / **7.38** (1.13×) | 20.1 / **18.6** (1.08×) | 16.6 / **12.9** (1.29×) |
| `List<int>` push_back + pop_front | **10.1** / 22.2 (0.45×) | **13.3** / 27.2 (0.49×) | **4.87** / 26.1 (0.19×) |
| `Queue<int>` enqueue + dequeue | **3.02** / 5.54 (0.55×) | **3.30** / 6.64 (0.50×) | **1.66** / 1.94 (0.86×) |
| `BST<int,int>` insert | **176** / 180 (0.98×) | 349 / **312** (1.12×) | 352 / **327** (1.08×) |
| `BST<int,int>` get | 174 / **173** (1.01×) | 351 / **338** (1.04×) | **278** / 397 (0.70×) |

- **정렬**은 `qsort`보다 1.6–2.0배 빠릅니다: 순서가 타입 매개변수라서 모든 비교가 인라인되지만, `qsort`는 비교마다 간접 호출을 합니다.
- **List**, **Queue**, **BST** 노드는 노드마다 할당하지 않고 컨테이너별 노드 풀에서 꺼냅니다.
- **HashMap**은 작은 키를 한 워드로 해시하고 정수 비교 한 번으로 비교하며, 이는 컴파일 시점에 `if const`로 고릅니다. x86-64 머신에서 C의 1.04–1.13배 이내이고, 나머지 차이는 테이블 성장입니다.
- **Vec push**는 C보다 1.2–1.7배 느립니다: 벤치마크 루프의 경계 검사된 `keys.at(i)`와 처음 접근할 때의 성장 비용입니다.

::: details 소스
[bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc) · [bench_collections.c](/benchmarks/collections/c/bench_collections.c) · [vec.h](/benchmarks/stdlib/vec.h) · [map.h](/benchmarks/stdlib/map.h) · [mem.sc](/benchmarks/stdlib/mem.sc)
:::

#### 할당자 — `std::alloc` / `dealloc`과 `malloc` / `free` {#allocator}

`std::alloc`은 모든 블록에 16바이트 헤더를 붙이고, 이 헤더가 이중 해제·해제 후 사용 검사를 맡습니다. 1 KB 이하 블록은 스레드별 64 KB slab에서 내주고, 그 앞에 크기 등급별 스레드 로컬 해제 블록 캐시가 있습니다(PyTorch와 MLX의 캐싱 할당자와 같은 생각). 모든 블록 주소를 출력되는 체크섬에 넣으므로 최적화기가 할당/해제 쌍을 지울 수 없습니다. 셀은 `std::alloc` / `malloc`입니다.

| 패턴 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| 교대: 48바이트 블록 하나를 할당·해제, 2천만 번(쌍당 ns) | **3.57** / 6.38 | 10.4 / **8.83** | **12.2** / 27.6 |
| 일괄: 24바이트 블록 100만 개 할당 후 전부 해제, 10회(ms) | **87.2** / 96.2 | **120** / 126 | **115** / 233 |

해제된 블록을 곧 다시 쓰면 캐시가 이깁니다. glibc의 `malloc`이 `std::alloc`보다 빠른 것은 Xeon의 교대 패턴뿐입니다. 블록 백만 개가 동시에 살아 있어도 slab 덕분에 같은 안전 검사를 하면서 `std::alloc`이 1.05–2.0배 앞섭니다. 수명이 짧은 대량 할당에는 여전히 `region`/`arena<R>`가 더 빠른 도구입니다(7800X3D의 binary-trees에서 힙 버전의 4.7배).

::: details 소스
[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc) · [alloc_bench.sc](/benchmarks/memalloc/safec/alloc_bench.sc)
:::

#### 웹 서비스 — JSON "hello world" {#web-service}

`GET /`가 `{"message":"Hello, World!"}`를 돌려줍니다. TechEmpower의 JSON 직렬화 테스트와 같은 형태이며, Apache Bench(`ab -n 20000 -c 50`, keep-alive 없음, 2,000 요청 워밍업 뒤)로 각 언어 자체의 HTTP 방식을 잽니다: SafeC의 `std::http_serve_reactor`(epoll 리액터), C·C++·Zig의 최소한의 raw 소켓 accept 루프, Go의 `net/http`, Rust의 axum(Dioxus 서버 계층이 쓰는 것), Python의 FastAPI + uvicorn. 셀은 초당 요청 수(높을수록 좋음) · p50/p99 지연 · 최대 메모리입니다.

| 언어 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | **25,303** · 2/2 ms · 4.9 MB | **33,730** · 1/2 ms · 14.2 MB | — |
| C | 24,620 · 2/2 ms · 3.1 MB | 32,196 · 2/2 ms · 14.0 MB | — |
| C++ | 24,845 · 2/2 ms · 5.1 MB | 32,523 · 1/2 ms · 15.7 MB | — |
| Rust | 25,075 · 2/2 ms · 4.3 MB | 32,592 · 2/2 ms · 13.6 MB | — |
| Zig | 25,030 · 2/2 ms · 7.1 MB | N/A | — |
| Go | 23,199 · 2/3 ms · 16.1 MB | 32,219 · 2/2 ms · 25.3 MB | — |
| Python | 5,096 · 10/14 ms · 46.0 MB | 2,887 · 17/30 ms · 60.8 MB | — |

모든 서버가 모든 요청을 처리했습니다. 컴파일 언어 서버들은 서로 9% 이내입니다: keep-alive가 없으면 요청마다 새 TCP 연결이므로 커널의 connect/accept 경로와 `ab`가 병목입니다. M1 Pro에서는 VS Code의 포트 포워딩이 서버 포트(127.0.0.1:8082–8087)를 잡고 있어 `ab`가 에디터에 접속했으므로 측정하지 않았습니다.

::: details 소스
[server_reactor.sc](/benchmarks/webservice/safec/server_reactor.sc) · [server.c](/benchmarks/webservice/c/server.c) · [server.cpp](/benchmarks/webservice/cpp/server.cpp) · [main.rs](/benchmarks/webservice/rust/src/main.rs) · [server.zig](/benchmarks/webservice/zig/server.zig) · [server.go](/benchmarks/webservice/go/server.go) · [server.py](/benchmarks/webservice/python/server.py) · [http.sc](/benchmarks/stdlib/http.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h)
:::

### 2.3 신호 처리 {#signal-processing}

#### DSP {#dsp}

[`std/dsp`](/ko/stdlib/dsp)를 SciPy, KFR 7, float biquad에서는 오디오 라이브러리 Q와 madronalib, 리샘플링에서는 dasp와 비교합니다. 각 작업은 무작위 샘플 1,048,576개(`conv2d`는 1024×1024 이미지)에서 돌리며, 워밍업 호출 한 번 뒤 호출당 ms, 3번 실행 중 최솟값입니다. "SafeC + oneMKL"은 oneMKL의 FFT와 BLAS를 링크한 것입니다(M1 Pro에는 없으며, 그곳에서 SafeC는 FIR에 Accelerate의 vDSP를 씁니다).

- `fir stream`: 호출 사이에 상태를 유지하는 127탭 스트리밍 FIR(SafeC `fir_process_block`, SciPy `lfilter`, KFR `fir_filter`). 할당도 스레드도 없는 실시간 경로입니다.
- `fir block`: 같은 필터를 합성곱 한 번으로(`conv_direct`, `np.convolve`).
- `fftconv`: 4,096탭 커널(`conv_fft`, `fftconvolve`, KFR `convolve_filter`).
- `biquad`: RBJ 저역 통과 섹션 하나(48 kHz, f0 1 kHz, Q 0.7071). 실시간 필터처럼 단일 스레드로 돕니다.
- `stft`: 1,024점 Hann 프레임, hop 256.
- `resample`: 44.1 → 48 kHz(SafeC는 양쪽 16탭 윈도 sinc, SciPy `resample_poly`, KFR `normal` 품질, dasp는 16탭 sinc).
- `conv2d`: 5×5 가우시안, "same" 크기.

배정밀도, 호출당 ms:

| 작업 | 라이브러리 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| fir stream | SafeC | 5.87 | 7.46 | **5.82** |
|  | SafeC + oneMKL | **5.86** | **7.37** | — |
|  | SciPy | 17.4 | 27.5 | 22.4 |
|  | KFR | 32.1 | 42.8 | 44.8 |
| fir block | SafeC | 0.841 | 0.730 | **0.926** |
|  | SafeC + oneMKL | **0.796** | **0.686** | — |
|  | SciPy | 15.4 | 25.1 | 21.4 |
| fftconv | SafeC | **2.98** | 4.54 | **3.06** |
|  | SafeC + oneMKL | 3.19 | **3.82** | — |
|  | SciPy | 32.3 | 60.5 | 22.7 |
|  | KFR | 4.89 | 6.57 | 8.06 |
| biquad | SafeC | **0.484** | **0.782** | **0.666** |
|  | SafeC + oneMKL | 0.493 | **0.782** | — |
|  | SciPy | 4.71 | 6.99 | 7.97 |
|  | KFR | 2.33 | 4.45 | 5.08 |
| stft | SafeC | 1.82 | 5.69 | **3.79** |
|  | SafeC + oneMKL | **1.81** | **5.47** | — |
|  | SciPy | 53.9 | 92.4 | 57.7 |
| resample | SafeC | **3.71** | **7.38** | **4.13** |
|  | SafeC + oneMKL | 3.88 | 8.03 | — |
|  | SciPy | 9.07 | 17.5 | 14.9 |
|  | KFR | 61.7 | 88.4 | 162 |
|  | dasp | 519 | 657 | 291 |
| conv2d | SafeC | **0.366** | **0.601** | **0.589** |
|  | SafeC + oneMKL | 0.390 | 0.715 | — |
|  | SciPy | 35.3 | 53.2 | 36.1 |

단정밀도, 호출당 ms:

| 작업 | 라이브러리 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| fir stream | SafeC | **3.04** | **3.83** | **3.45** |
|  | SafeC + oneMKL | 3.07 | 3.85 | — |
|  | SciPy | 19.5 | 16.0 | 15.9 |
|  | KFR | 27.8 | 37.2 | 63.5 |
| fir block | SafeC | 0.475 | **0.649** | **0.523** |
|  | SafeC + oneMKL | **0.471** | 0.673 | — |
|  | SciPy | 18.9 | 15.7 | 15.1 |
| fftconv | SafeC | 1.29 | 4.23 | **1.97** |
|  | SafeC + oneMKL | **1.16** | **2.21** | — |
|  | SciPy | 14.8 | 32.2 | 16.8 |
|  | KFR | 2.76 | 3.28 | 4.05 |
| biquad | SafeC | **0.482** | **0.773** | **0.661** |
|  | SafeC + oneMKL | 0.525 | 0.785 | — |
|  | SciPy | 4.59 | 6.34 | 7.84 |
|  | KFR | 2.15 | 4.26 | 4.19 |
|  | Q | 1.69 | 2.81 | 2.62 |
|  | madronalib | 2.87 | 5.37 | 4.38 |
| stft | SafeC | 0.986 | 4.88 | **2.20** |
|  | SafeC + oneMKL | **0.983** | **4.14** | — |
|  | SciPy | 55.8 | 95.1 | 56.0 |
| resample | SafeC | **3.96** | 11.1 | **4.77** |
|  | SafeC + oneMKL | 3.98 | **10.8** | — |
|  | SciPy | 8.68 | 16.5 | 16.8 |
|  | KFR | 34.1 | 53.2 | 90.0 |

- 세 머신 모두에서 모든 작업에 걸쳐 두 정밀도 모두 SafeC 빌드가 가장 빠릅니다. 벤더 라이브러리는 FFT가 들어가는 곳(`fftconv`, `stft`)에서만 차이를 냅니다.
- **FIR**: 블록마다 `dsp_sliding_dot`이 패스당 출력 32개(float는 64개)를 계산하며, 각 탭을 누산 벡터 네 개에 브로드캐스트하므로 수평 합이 없습니다. KFR과 SciPy는 출력마다 내적을 한 번씩 합니다. Apple Silicon에서는 슬라이딩 내적을 Accelerate의 `vDSP_conv`에 넘깁니다(코어당 약 47 GFLOPS, SafeC 자체 NEON 커널은 34).
- **Biquad**: 흩어진 look-ahead(Parhi & Messerschmitt)로 점화식을 y[i-2]와 y[i-4]로 다시 써서, 짝수와 홀수 출력이 2레인 벡터 하나 안의 독립된 두 사슬이 됩니다: 7800X3D의 float에서 Q보다 3.5배, KFR보다 4.5배 빠릅니다. M1 Pro에서는 `vDSP_biquad`보다도 빠릅니다(0.66 vs 0.93 ms).
- **Resample**: 각 탭의 가중치를 호출당 한 번 만드는 표 위의 각도 차 공식으로 구하므로 탭이 벡터화되고, 출력마다 삼각함수 호출이 필요 없습니다.
- **float vs double**: float는 메모리 트래픽이나 벡터 폭이 한계인 곳(FIR, `fftconv`, `stft`)에서 도움이 됩니다. biquad는 지연에 묶이고 리샘플러는 커널을 double로 계산합니다. Xeon의 float 리샘플러는 double보다 느린데(11.1 vs 7.38 ms), 원인은 아직 조사하지 않았습니다.

::: details 소스
[dsp_cpu.sc](/benchmarks/dsp/safec/dsp_cpu.sc) · [dsp_scipy.py](/benchmarks/dsp/python/dsp_scipy.py) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [audio_libs_bench.cpp](/benchmarks/dsp/cpp/audio_libs_bench.cpp) · [dasp main.rs](/benchmarks/dsp/rust/src/main.rs) · [dsp.sc](/benchmarks/stdlib/dsp.sc) · [filter.sc](/benchmarks/stdlib/filter.sc) · [biquad.sc](/benchmarks/stdlib/biquad.sc) · [convolution.sc](/benchmarks/stdlib/convolution.sc) · [conv_core.h](/benchmarks/stdlib/conv_core.h) · [stft.sc](/benchmarks/stdlib/stft.sc) · [resample_core.h](/benchmarks/stdlib/resample_core.h) · [imaging_core.h](/benchmarks/stdlib/imaging_core.h) · [fft_core.h](/benchmarks/stdlib/fft_core.h)
:::

#### FFT {#fft}

CPU에서는 [`fft.h`](/ko/stdlib/dsp)를 FFTW 3.3.10(`FFTW_MEASURE`로 계획), oneMKL의 DFTI, KFR 7.2, RustFFT 6.4, PyTorch와, GPU에서는 SafeC의 [`fft_rocm.h` / `fft_cuda.h`](/ko/stdlib/ml#rocm-miopen-hipfft)(rocFFT, cuFFT)를 PyTorch와 비교합니다. `fft1d`는 2²⁰점 변환 하나, `fft2d`는 1024×1024 변환 하나, `batch`는 4096점 변환 1024개, `rfft`는 중복 없는 n/2 + 1개 빈을 돌려주는 4096점 실수 변환 1024개입니다. "모든 스레드"는 7800X3D에서 16, Xeon에서 12입니다. FFT는 M1 Pro에서 측정하지 않았습니다.

CPU, 배정밀도(complex128), 호출당 ms:

| 작업 | 라이브러리 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| fft1d, 2²⁰ | SafeC, 모든 스레드 | **0.888** | 1.21 | — |
|  | SafeC + oneMKL DFTI | 0.891 | **1.18** | — |
|  | FFTW, 모든 스레드 | 1.63 | 2.35 | — |
|  | PyTorch | 8.31 | **1.18** | — |
|  | SafeC, 1 스레드 | 5.47 | — | — |
|  | FFTW, 1 스레드 | 6.50 | 11.4 | — |
|  | KFR, 1 스레드 | 3.36 | 6.35 | — |
|  | RustFFT, 1 스레드 | 4.75 | 8.78 | — |
| fft2d, 1024² | SafeC, 모든 스레드 | 0.691 | 1.08 | — |
|  | SafeC + oneMKL DFTI | 1.00 | 0.837 | — |
|  | FFTW, 모든 스레드 | **0.618** | 2.59 | — |
|  | PyTorch | 0.710 | **0.654** | — |
|  | SafeC, 1 스레드 | 4.43 | — | — |
|  | FFTW, 1 스레드 | 3.49 | 7.27 | — |
|  | KFR, 1 스레드 | 4.22 | 5.15 | — |
| batch, 1024 × 4096 | SafeC, 모든 스레드 | 1.25 | 2.96 | — |
|  | SafeC + oneMKL DFTI | 1.89 | **1.65** | — |
|  | FFTW, 모든 스레드 | **1.05** | 2.95 | — |
|  | SafeC, 1 스레드 | 11.9 | — | — |
|  | FFTW, 1 스레드 | 7.84 | 10.7 | — |
|  | KFR, 1 스레드 | 11.8 | 12.3 | — |
|  | RustFFT, 1 스레드 | 8.25 | 14.8 | — |
| rfft, 1024 × 4096 | SafeC, 모든 스레드 | 0.861 | 2.31 | — |
|  | SafeC + oneMKL DFTI | 1.05 | **1.36** | — |
|  | FFTW, 모든 스레드 | **0.553** | 2.02 | — |
|  | SafeC, 1 스레드 | 6.17 | — | — |
|  | FFTW, 1 스레드 | 4.28 | 7.35 | — |
|  | KFR, 1 스레드 | 4.34 | 7.32 | — |

CPU, 단정밀도(complex64 / float32), 호출당 ms:

| 작업 | 라이브러리 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| fft1d, 2²⁰ | SafeC, 모든 스레드 | **0.688** | 3.06 | — |
|  | SafeC + oneMKL DFTI | 0.693 | 2.85 | — |
|  | FFTW, 모든 스레드 | 1.67 | **2.46** | — |
|  | SafeC, 1 스레드 | 4.39 | — | — |
|  | FFTW, 1 스레드 | 5.53 | 8.72 | — |
|  | KFR, 1 스레드 | 1.77 | 3.31 | — |
|  | RustFFT, 1 스레드 | 2.10 | 4.12 | — |
| fft2d, 1024² | SafeC, 모든 스레드 | 0.560 | 2.01 | — |
|  | SafeC + oneMKL DFTI | 0.543 | **1.11** | — |
|  | FFTW, 모든 스레드 | **0.446** | 1.86 | — |
|  | SafeC, 1 스레드 | 3.25 | — | — |
|  | FFTW, 1 스레드 | 2.54 | 4.29 | — |
|  | KFR, 1 스레드 | 2.63 | 3.07 | — |
| batch, 1024 × 4096 | SafeC, 모든 스레드 | 0.775 | 3.16 | — |
|  | SafeC + oneMKL DFTI | 0.964 | **1.60** | — |
|  | FFTW, 모든 스레드 | **0.504** | 1.75 | — |
|  | PyTorch | 4.41 | 3.19 | — |
|  | SafeC, 1 스레드 | 5.76 | — | — |
|  | FFTW, 1 스레드 | 3.31 | 6.19 | — |
|  | KFR, 1 스레드 | 4.23 | 6.03 | — |
|  | RustFFT, 1 스레드 | 4.11 | 7.75 | — |
| rfft, 1024 × 4096 | SafeC, 모든 스레드 | 0.558 | 2.36 | — |
|  | SafeC + oneMKL DFTI | 0.457 | 0.812 | — |
|  | FFTW, 모든 스레드 | **0.409** | 2.08 | — |
|  | PyTorch | 0.443 | **0.288** | — |
|  | SafeC, 1 스레드 | 4.28 | — | — |
|  | FFTW, 1 스레드 | 2.98 | 6.50 | — |
|  | KFR, 1 스레드 | 2.13 | 3.73 | — |

GPU, 호출당 ms. 호스트 배열 행은 호스트 메모리에서 시작하고 끝나므로 호출마다 업로드와 읽기가 들어가고(complex128), 나머지 두 행은 이미 GPU에 있는 데이터에서 돕니다(complex64 / float32):

| 작업 | 라이브러리 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| fft1d, 2²⁰ (호스트 배열) | SafeC | **1.46** | **1.25** | — |
|  | PyTorch | 1.56 | 2.05 | — |
| fft2d, 1024² (호스트 배열) | SafeC | **1.43** | **1.26** | — |
|  | PyTorch | 1.53 | 2.04 | — |
| batch, 1024 × 4096 (GPU 상주) | SafeC | **0.100** | 0.0244 | — |
|  | PyTorch | 0.171 | **0.0238** | — |
| rfft, 1024 × 4096 (GPU 상주) | SafeC | **0.0949** | **0.0109** | — |
|  | PyTorch | 0.126 | 0.0120 | — |

- **CPU**: 7800X3D에서 2²⁰점 변환은 두 정밀도 모두 SafeC가 가장 빠르며, FFTW보다 1.8배(double), 2.4배(float) 앞섭니다. Xeon에서는 oneMKL·PyTorch와 같고 FFTW보다 1.9배 빠릅니다. 2D, 일괄, 실수 변환은 7800X3D에서 FFTW가 1.1–1.6배 앞서고, Xeon에서는 oneMKL이나 PyTorch가 앞섭니다. 융합한 radix-8 패스, 벡터당 복소수 네 개, 컴파일 시점 비트 반전 표는 [`std/dsp`](/ko/stdlib/dsp)에 설명이 있습니다.
- **Xeon의 float**: 그곳에서 `fft_f32`는 double 코드보다 느립니다(2²⁰점에서 3.06 vs 1.21 ms). 7800X3D에서는 float가 double 시간의 0.6–0.8배입니다. 원인은 아직 조사하지 않았습니다.
- **GPU**: 양쪽 모두 rocFFT / cuFFT를 호출하므로 H200에서 GPU 상주 변환은 같습니다. MI100에서는 SafeC가 1.3–1.7배 빠른데, 0.1 ms 중 PyTorch의 호출당 작업이 큰 비중을 차지합니다. 호스트 배열은 PCIe가 지배하며, H200에서는 SafeC의 핀 메모리 다중 레인 복사 덕분에 왕복이 PyTorch의 `.to("cuda")` … `.cpu()`보다 1.6배 빠릅니다.

::: details 소스
[fft_cpu.sc](/benchmarks/fft/safec/fft_cpu.sc) · [fft_rocm.sc](/benchmarks/fft/safec/fft_rocm.sc) · [fft_cuda.sc](/benchmarks/fft/safec/fft_cuda.sc) · [fft.py](/benchmarks/fft/pytorch/fft.py) · [fftw_bench.c](/benchmarks/fft/c/fftw_bench.c) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [RustFFT main.rs](/benchmarks/dsp/rust/src/main.rs) · [fft.h](/benchmarks/stdlib/fft.h) · [fft.sc](/benchmarks/stdlib/fft.sc) · [fft_f32.sc](/benchmarks/stdlib/fft_f32.sc) · [fft_core.h](/benchmarks/stdlib/fft_core.h) · [fft_rocm.sc](/benchmarks/stdlib/fft_rocm.sc) · [fft_cuda.sc](/benchmarks/stdlib/fft_cuda.sc)
:::

### 2.4 머신러닝 {#machine-learning}

이 부분의 모든 표는 **머신마다 각 프레임워크의 최적 구성**을 보여 줍니다: SafeC는 CPU·GPU 백엔드, 데이터 타입, 그래프 재생 중 가장 빠른 것, PyTorch, TensorFlow, MLX도 마찬가지입니다. 셀마다 구성을 적었습니다. 구성별 전체 결과는 결과 JSON에 있습니다.

#### MLP 학습과 추론 {#ml-mlp}

2층 MLP(`relu(X @ W1) @ W2`, MSE 손실, 직접 구현한 SGD, 바이어스 없음)를 두 크기로 돌립니다:

- **작은 모델**: 128→256→64, 배치 64, 학습 100 스텝, 추론 1000회.
- **큰 모델**: 512→1024→256, 배치 128, 학습 50 스텝, 추론 200회.

모든 프레임워크가 손실을 기록하는 학습 루프처럼 스텝마다 손실을, 추론마다 `Y[0][0]`을 호스트로 읽어 옵니다. 각각 시간에 넣지 않는 워밍업을 한 번 합니다. M1 Pro 열은 이전 std로 측정한 이전 실행 결과입니다(현재 벤치마크 모음에서는 다시 측정하지 않았습니다). 셀은 ms입니다.

작은 모델:

| 프레임워크 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro (이전 실행) |
|---|---|---|---|
| SafeC | 학습 **5.39** (ROCm, fp16, graph)<br>추론 24.3 (CPU, OpenBLAS) | 학습 **2.98** (CUDA, fp16, graph)<br>추론 **19.6** (CUDA, TF32, graph) | 학습 **16.8** (CPU, Accelerate)<br>추론 26.1 (CPU, Accelerate) |
| PyTorch | 학습 14.0 (CPU)<br>추론 **21.9** (CPU) | 학습 23.2 (CPU)<br>추론 43.0 (CPU) | 학습 23.1 (CPU)<br>추론 **13.5** (CPU) |
| TensorFlow | 학습 79.5 (CPU)<br>추론 204 (CPU) | 학습 123 (CPU)<br>추론 265 (CPU) | 학습 83.0 (CPU)<br>추론 245 (CPU) |
| MLX | — | — | 학습 72.2 (GPU)<br>추론 309 (GPU) |

큰 모델:

| 프레임워크 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro (이전 실행) |
|---|---|---|---|
| SafeC | 학습 **5.90** (ROCm, fp16, graph)<br>추론 **9.26** (ROCm, fp16, graph) | 학습 **1.90** (CUDA, fp16, graph)<br>추론 **3.99** (CUDA, bf16, graph) | 학습 **47.9** (Metal)<br>추론 **47.3** (Metal) |
| PyTorch | 학습 9.91 (ROCm, fp16)<br>추론 13.4 (ROCm, fp32) | 학습 15.9 (CUDA, fp32)<br>추론 10.5 (CUDA, fp16) | 학습 67.8 (CPU)<br>추론 48.2 (CPU) |
| TensorFlow | 학습 106 (CPU)<br>추론 124 (CPU) | 학습 229 (CPU)<br>추론 236 (CPU) | 학습 149 (CPU)<br>추론 198 (CPU) |
| MLX | — | — | 학습 54.3 (GPU)<br>추론 74.4 (GPU) |

- **이 크기에서 GPU 학습은 실행(launch) 비용에 묶입니다.** 한 스텝은 마이크로초 단위 커널 몇 개와 손실을 기다려 읽는 것입니다. SafeC CUDA 한 스텝은 약 38 µs(그래프 실행 한 번과 읽기)이고, PyTorch 한 스텝은 같은 cuBLAS 호출을 둘러싼 Python·autograd·디스패처 작업으로 약 510 µs입니다. 그래서 작은 모델에서는 모든 머신에서 PyTorch의 최적 구성이 CPU입니다.
- **SafeC의 가장 빠른 모드는 16비트**입니다: 데이터, 가중치, 활성값, 기울기를 fp16/bf16으로 저장하고 fp32로 누산하며, 스텝을 그래프 하나로 재생합니다. MI100에서는 GEMM 형태마다 첫 호출에서 rocBLAS의 모든 솔루션을 재서 가장 빠른 것을 남기며, 이것만으로 fp32 그래프 스텝이 8.29 ms에서 7.37 ms가 되었습니다.
- **작은 모델의 CPU 추론**은 PyTorch가 앞서는 곳입니다(7800X3D에서 21.9 vs 24.3 ms, M1 Pro에서 13.5 vs 26.1 ms).
- 이 루프는 반올림을 키우므로 50 스텝 뒤 16비트 손실이 fp32와 다릅니다(MI100의 큰 모델: fp16 95.09, bf16 63.43, fp32 95.43). SafeC의 fp32 실행은 CPU와 GPU에 걸쳐 6자리까지 일치합니다.

::: details 소스
[train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_cuda.sc](/benchmarks/ml/safec/train_cuda.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gemm.sc](/benchmarks/stdlib/gemm.sc)
:::

#### 합성곱과 U-Net {#ml-convolution}

`conv64`는 Conv2D 레이어 하나(64→64 채널, 3×3, stride 1, padding 1, 56×56 맵, 바이어스 포함)이고, U-Net은 `unet_new(3, 32, 64, 3)`의 128×128 순전파(합성곱 다섯 개, 최대 풀링 두 번, 업샘플 + concat 두 번)입니다. SafeC는 [MIOpen과 cuDNN](/ko/stdlib/ml#rocm-miopen-hipfft)으로 돌리며, PyTorch의 `cudnn.benchmark = True`처럼 첫 호출에서 형태마다 알고리즘을 재서 고릅니다. 텐서는 호출 사이에 GPU에 머뭅니다. 모든 행에서 GPU가 이기므로 표는 GPU 구성을 보여 주며, 합성곱 테스트는 M1 Pro에서 측정하지 않았습니다. 셀은 호출당 ms입니다.

| 작업 | 프레임워크 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| conv64, 1장 | SafeC | **0.0270** (MIOpen) | **0.0181** (cuDNN, fp32) | — |
|  | PyTorch | 0.0628 | 0.0225 (fp32) | — |
| conv64, 32장 | SafeC | **0.342** | **0.0838** (TF32) | — |
|  | PyTorch | 0.346 | 0.0976 (TF32) | — |
| U-Net, 1장 | SafeC | **0.216** (그래프 하나) | **0.0874** (TF32, 그래프 하나) | — |
|  | PyTorch | 0.314 | 0.208 (fp32) | — |
| U-Net, 16장 | SafeC | 1.29 | **0.454** (TF32) | — |
|  | PyTorch | **1.26** | 0.557 (TF32) | — |

- MI100에서 SafeC는 이미지 한 장에서 2.3배, 이미지 한 장 U-Net(그래프 하나)에서 1.45배 빠르고, 배치 16–32에서는 3% 이내로 같습니다.
- H200에서는 SafeC가 모든 행에서 빠릅니다: 합성곱마다 cuDNN 그래프 API로 conv + 바이어스(+ ReLU)를 융합한 그래프 하나를 만들어, 호출당 호스트 시간이 레거시 API의 38 µs 대신 약 6 µs입니다. 그래프 하나로 돌린 이미지 한 장 U-Net은 PyTorch의 최적 구성보다 2.4배 빠릅니다.
- CPU에서는 SafeC의 Winograd F(2×2, 3×3) 경로가 7800X3D에서 AOCL-BLAS로 U-Net을 PyTorch보다 2.0배(1.35 vs 2.68 ms), Xeon에서 oneMKL로 1.24배(2.65 vs 3.29 ms) 빠르게 돌립니다. 단일 `conv64` 레이어에서는 PyTorch의 oneDNN이 여전히 1.1–2.0배 빠릅니다.

::: details 소스
[conv_rocm.sc](/benchmarks/conv/safec/conv_rocm.sc) · [conv_cuda.sc](/benchmarks/conv/safec/conv_cuda.sc) · [conv_cpu.sc](/benchmarks/conv/safec/conv_cpu.sc) · [conv.py](/benchmarks/conv/pytorch/conv.py) · [cnn.sc](/benchmarks/stdlib/cnn.sc) · [unet.sc](/benchmarks/stdlib/unet.sc) · [gpu_miopen.sc](/benchmarks/stdlib/gpu_miopen.sc) · [gpu_cudnn.sc](/benchmarks/stdlib/gpu_cudnn.sc)
:::

#### 어텐션 {#ml-attention}

SafeC GPU 백엔드([ROCm](/ko/stdlib/ml#rocm-attention), [CUDA](/ko/stdlib/ml#cuda-attention), [Metal](/ko/stdlib/ml#mps-flash-linear-attention))의 flash 어텐션(점수 행렬을 만들지 않는 `softmax(QKᵀ / √d) V`)과 선형 어텐션(φ(x) = elu(x) + 1)입니다. 비교 대상은 PyTorch의 `scaled_dot_product_attention`, MLX의 융합 `mx.fast.scaled_dot_product_attention`, 선형 어텐션에서는 벡터화한 PyTorch / MLX 구현과 flash-linear-attention의 Triton 커널입니다. 배치 4, 헤드 16, 데이터는 GPU에 둡니다. 표는 두 작업을 다룹니다: 헤드 차원 128 / 토큰 4096 / causal인 flash 어텐션과, 헤드 차원 64 / 토큰 16,384 / causal인 선형 어텐션입니다. 각 작업에서 프레임워크별로 가장 빠른 데이터 타입의 결과와 fp32 결과를 보여 줍니다. 셀은 호출당 ms이며, flash 어텐션은 TFLOPS(4·B·H·S²·D, causal이면 절반)도 적었습니다.

| 작업 | 프레임워크 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|---|
| Flash, 헤드 차원 128, 토큰 4096, causal, 가장 빠른 데이터 타입 | SafeC | **12.6** (fp32, 21.9 TFLOPS) | 0.466 (bf16, 589 TFLOPS) | 89.8 (fp16, 2.97 TFLOPS) |
|  | PyTorch | 66.4 (fp32, 4.1 TFLOPS) | **0.463** (bf16, 591 TFLOPS) | 84.4 (fp16, 3.23 TFLOPS) |
|  | MLX | — | — | **75.1** (fp16, 3.66 TFLOPS) |
| Flash, 같은 형태를 fp32로 | SafeC | **12.6** (21.9 TFLOPS) | **6.07** (45 TFLOPS) | **97.8** (2.81 TFLOPS) |
|  | PyTorch | 66.4 (4.1 TFLOPS) | 6.19 (44 TFLOPS) | 163 (1.65 TFLOPS) |
|  | MLX | — | — | 125 (1.93 TFLOPS) |
| Linear, 헤드 차원 64, 토큰 16,384, causal, 가장 빠른 데이터 타입 | SafeC | **2.76** (fp32) | **1.44** (fp32) | **26.6** (bf16) |
|  | PyTorch | 14.6 (fp32) | 3.32 (fp32를 TF32로, NGC 기본값) | 113 (fp32) |
|  | flash-linear-attention | — | 2.38 (bf16) | — |
|  | MLX | — | — | 81.0 (fp32) |

- **H200**: fp16/bf16 flash 어텐션은 PyTorch도 쓰는 cuDNN 융합 엔진을 거치므로 둘이 3% 이내입니다(최대 690 TFLOPS). SafeC의 fp32 flash 어텐션은 텐서 코어에서 3xTF32로 돌며(오차 ~2e-7), 헤드 차원 128에서 PyTorch와 같고 64에서 1.34배 빠릅니다.
- **MI100**: SafeC의 fp32 커널은 행렬 코어(MFMA)에서 18–26 TFLOPS로 돕니다. PyTorch는 gfx908에서 융합 커널이 없어 점수 행렬 전체를 만들며, 그래서 마스크를 쓰면 오히려 느려집니다.
- **M1 Pro**: 헤드 차원 128의 fp16에서는 MLX가 1.2배 빠릅니다. fp32에서는 SafeC가 셋 중 가장 빠르며(여기서 1.3–1.7배), M1 계열에는 bf16 행렬 유닛이 없어 SafeC는 bf16 타일을 올릴 때 fp32로 바꿉니다.
- **선형 어텐션**은 모든 곳에서 PyTorch보다 2.3–5.3배 빠릅니다: 청크별 상태 φ(K)ᵀV와 청크에 걸친 스캔(MI100에서는 decoupled look-back을 쓰는 단일 패스)을 정확한 fp32로 계산합니다.

::: details 소스
[attn_rocm.sc](/benchmarks/attention/safec/attn_rocm.sc) · [attn_cuda.sc](/benchmarks/attention/safec/attn_cuda.sc) · [attn_mps.sc](/benchmarks/attention/safec/attn_mps.sc) · [PyTorch attn.py](/benchmarks/attention/pytorch/attn.py) · [MLX attn.py](/benchmarks/attention/mlx/attn.py) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_cuda_attention.sc](/benchmarks/stdlib/gpu_cuda_attention.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal)
:::

#### LLM 추론: Gemma 4 E2B {#ml-llm}

실제 `google/gemma-4-E2B` 체크포인트(bf16 10.25 GB)를 처음부터 구현해 돌립니다: Per-Layer Embeddings, 레이어 간 KV 공유, 두 가지 RoPE 설정을 쓰는 sliding·full 어텐션, 로짓 소프트캡핑. SafeC 경로에는 외부 추론 엔진(llama.cpp, ONNX Runtime, vLLM)이 없습니다. 독립 프로젝트([`benchmarks/gemma4/`](/benchmarks/gemma4/DESIGN_NOTES.md))이며 머신마다 GPU 엔진이 하나씩 있습니다. 모든 엔진은 가중치를 모두 GPU에 두고, 사영마다 bf16 GEMV로 읽으며, 디코딩 스텝 하나를 GPU 그래프로 기록해 토큰마다 재생합니다. 측정 대상은 단일 요청 지연입니다: 프롬프트 `"The capital of France is"`, greedy 디코딩, 생성 토큰당 ms. 모든 경로가 같은 텍스트(`" Paris.\n\nThe capital of France is Paris. …"`)를 냅니다.

| 프레임워크 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| SafeC | **6.44** (ROCm 엔진, HIP 그래프) | **2.91** (CUDA 엔진, CUDA 그래프) | 127 (Metal, bf16, 추측 디코딩) |
| PyTorch + transformers | 20.2 (`torch.compile`) | 4.47 (정적 KV 캐시) | 85 (`mps`) |
| MLX (mlx-lm) | — | — | **48** |

- **정확성**: MI100에서 네 프롬프트의 다음 토큰 top-5 로짓이 `transformers` fp32와 1e-4 이내로 일치하고, 900토큰 greedy 생성(512토큰 sliding window를 넘어)이 토큰 하나하나 같습니다. H200에서는 greedy 토큰 64개가 모두 `transformers`와 같습니다.
- **시간이 드는 곳**: 디코딩 스텝 하나가 가중치 약 4.6 GB를 읽습니다. H200에서 엔진은 LM head를 약 4.4 TB/s로 읽고 programmatic dependent launch로 커널을 겹치며, MI100에서는 GEMV가 0.85–1.07 TB/s로 6.44 ms 중 5.2 ms를 차지합니다.
- **M1 Pro**: MLX가 더 빠릅니다: `mlx_lm`은 디코딩 스텝 전체를 지연 그래프 하나로 만들어 토큰당 커맨드 버퍼가 거의 하나인 반면, SafeC의 Metal 경로는 아직 토큰당 GPU 왕복이 약 70–84번입니다. SafeC 행은 prompt-lookup 추측 디코딩도 쓰며, 다른 둘은 스텝마다 토큰 하나를 디코딩합니다.
- **CPU만 쓸 때**: SafeC는 [`std::half_gemv`](/ko/stdlib/ml#half-precision-tensors)로 저장된 bf16 가중치를 그대로 메모리 대역폭으로 읽어 디코딩합니다: 7800X3D에서 토큰당 225 ms(PyTorch 211), M1 Pro에서 136 ms(PyTorch 285)입니다.

::: details 소스
[DESIGN_NOTES.md](/benchmarks/gemma4/DESIGN_NOTES.md) · [gemma4_rocm.sc](/benchmarks/gemma4/src/gemma4_rocm.sc) · [gemma4_rocm_kernels.hip](/benchmarks/gemma4/src/gemma4_rocm_kernels.hip) · [gemma4_cuda.sc](/benchmarks/gemma4/src/gemma4_cuda.sc) · [gemma4_cuda.cuh](/benchmarks/gemma4/src/gemma4_cuda.cuh) · [gemma4_model_bf16.sc](/benchmarks/gemma4/src/gemma4_model_bf16.sc) · [gemma4_bf16.sc](/benchmarks/gemma4/src/gemma4_bf16.sc) · [gemma4_generate.sc](/benchmarks/gemma4/src/gemma4_generate.sc) · [bench_rocm.sc](/benchmarks/gemma4/tests/bench_rocm.sc) · [bench_safec.sc](/benchmarks/gemma4/tests/bench_safec.sc) · [bench_pytorch.py](/benchmarks/gemma4/tests/bench_pytorch.py) · [bench_pytorch_compiled.py](/benchmarks/gemma4/tests/bench_pytorch_compiled.py) · [bench_pytorch_cuda.py](/benchmarks/gemma4/tests/bench_pytorch_cuda.py) · [bench_mlx.py](/benchmarks/gemma4/tests/bench_mlx.py) · [ref_transformers.py](/benchmarks/gemma4/tests/ref_transformers.py)
:::

## 3. 요약 {#summary}

| 테스트 | 7800X3D + MI100 | Xeon 8570 + H200 | M1 Pro |
|---|---|---|---|
| fib, n-body, SIMD(SafeC vs C) | 1.00–1.03배 | 0.97–1.07배 | 0.94–1.01배 |
| binary-trees, 아레나(vs C) | 6.1배 빠름 | 4.2배 빠름 | 7.0배 빠름 |
| binary-trees, 8 스레드, 아레나(vs C) | 4.3배 빠름 | 4.5배 빠름 | 9.3배 빠름 |
| 웹 서비스 | 가장 빠름, 25,303 req/s | 가장 빠름, 33,730 req/s | — |
| DSP(작업 7개, 정밀도 2가지) | 모든 행에서 가장 빠름 | 모든 행에서 가장 빠름 | 모든 행에서 가장 빠름 |
| CPU FFT, 2²⁰점 | 가장 빠름 | oneMKL / PyTorch와 같음 | — |
| MLP 학습, 최적 대 최적(작은 / 큰 모델) | PyTorch보다 2.6배 / 1.7배 빠름 | 7.8배 / 8.4배 빠름 | 1.4배 / 1.4배 빠름(이전 실행) |
| Flash 어텐션, 가장 빠른 데이터 타입 | PyTorch보다 5.3배 빠름 | PyTorch와 같음(cuDNN) | MLX가 1.2배 빠름 |
| 선형 어텐션 | PyTorch보다 5.3배 빠름 | 2.3배 빠름 | MLX보다 3.0배 빠름 |
| Gemma 4 E2B 디코딩 | PyTorch보다 3.1배 빠름 | 1.5배 빠름 | MLX가 2.6배 빠름 |

- **언어**: 계산 위주 SafeC는 C와 몇 % 이내로 돌므로, 이 테스트들에서 안전 검사의 비용은 작습니다. `consteval` 표와 단계별 SIMD 디스패치는 실행 비용을 더하지 않습니다.
- **메모리**: 영역(region)이 가장 큰 단일 이득입니다. slab을 쓰는 힙 할당자는 블록당 16바이트의 안전 헤더를 치르면서도 대부분의 패턴에서 glibc의 `malloc`보다 앞섭니다.
- **표준 라이브러리**: 컨테이너와 DSP 코드는 컴파일 시점 특수화가 간접 호출을 없애는 곳(정렬, 필터)에서 이기며, 남은 차이는 컬렉션 성장입니다.
- **머신러닝**: AMD와 NVIDIA 머신에서 SafeC 백엔드는 PyTorch와의 최적 대 최적 비교 대부분에서 이깁니다. 예외는 H200의 fp16/bf16 flash 어텐션(같음, 둘 다 cuDNN), MI100의 이미지 16장 U-Net(2% 뒤처짐), 7800X3D CPU의 작은 모델 추론(1.1배 뒤처짐)입니다. Apple Silicon에서는 MLX가 LLM 디코딩과 반정밀도 flash 어텐션에서 앞섭니다.
- **아직 측정하지 않음**: M1 Pro의 합성곱, FFT, 현재 MLP 테스트와 웹 서비스.
