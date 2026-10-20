# 벤치마크

몇 가지 대표적인 마이크로벤치마크([The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html) 방식)에서 SafeC를 C, C++, Rust, Zig, Go, Python과 비교하고, 표준 라이브러리의 컬렉션, HTTP 서버, ML 텐서, DSP, 할당자도 측정합니다. 머신 한 대, 3회 중 최선입니다: 모든 숫자는 언어 순위가 아니라 "이 머신에서 대략 이 정도"로 읽어 주세요.

## 한눈에 보기 {#at-a-glance}

릴리스 빌드 실행 시간(낮을수록 좋음, 가장 빠른 값 굵게). SafeC의 binary-trees 행은 `region`/`arena` 할당을 씁니다. 힙 버전은 [binary-trees](#binary-trees-allocationdeallocation-stress)에 있습니다.

| 벤치마크 | SafeC | C | C++ | Rust | Zig | Go | Python |
|---|---|---|---|---|---|---|---|
| [fib(37)](#fib37-recursive-fibonacci) | 0.042s | 0.042s | **0.041s** | **0.041s** | 0.042s | 0.089s | 1.992s |
| [n-body](#n-body-5-body-orbital-simulation) | **0.098s** | **0.098s** | **0.098s** | 0.106s | 0.110s | 0.105s | 7.716s |
| [binary-trees](#binary-trees-allocationdeallocation-stress) | **0.140s** | 0.804s | 0.937s | 0.910s | 0.850s | 0.722s | 18.169s |
| [binary-trees, 8 스레드](#multithreaded-binary-trees-8-threads) | **0.054s** | 0.225s | 0.239s | 0.234s | 0.227s | 0.255s | 23.034s |
| [SIMD](#simd-sum-of-squares-over-20000000-f64-values) (명시적 벡터) | 0.078s | 0.077s | **0.076s** | 0.077s | **0.076s** | — | — |
| [웹 서비스](#web-service-json-hello-world-endpoint) (req/s, 높을수록 좋음) | **25,216** | 24,618 | 24,870 | 24,814 | 24,828 | 23,573 | 5,047 |

- **머신러닝** (2층 MLP, [자세히](#machine-learning-small-mlp-training-and-inference)): MI100에서 SafeC는 작은 모델을 7.0 ms에 학습하며(PyTorch-ROCm 20.5 ms, fp32), 큰 모델은 7.4 ms입니다(PyTorch-ROCm 10.7 ms). fp16에서 큰 모델은 5.9 ms입니다(PyTorch 9.9 ms). CPU에서는 AOCL-BLAS를 쓴 SafeC가 작은 모델을 14.8 ms에 학습하고(PyTorch 14.1 ms), 큰 모델은 37.0 ms입니다(PyTorch 38.5 ms).
- **NVIDIA H200** ([자세히](#h200-machine-learning)): SafeC의 CUDA 백엔드는 작은 모델을 3.80 ms에 학습하며(PyTorch-CUDA fp32 51.0 ms), 큰 모델은 3.13 ms에 학습합니다(17.8 ms). fp16에서는 큰 모델이 1.90 ms입니다(16.9 ms). Xeon 8570 CPU 결과는 [별도 절](#xeon-8570-classic)에 있습니다.
- **MI100의 합성곱과 FFT** ([자세히](#mi100-conv-fft)): MIOpen으로 SafeC는 64채널 3×3 합성곱 하나를 0.027 ms에 실행하고(PyTorch-ROCm 0.063 ms), 배치 1 U-Net은 0.224 ms입니다(0.315 ms). 배치 16–32에서는 2% 이내입니다. 장치 상주 hipFFT 변환은 PyTorch를 거칠 때보다 1.3–1.7배 빠릅니다.
- **CPU 합성곱과 FFT** ([자세히](#mi100-conv-fft)): Winograd 3×3 합성곱으로 U-Net이 CPU에서 1.42 ms에 실행됩니다(PyTorch 2.97 ms). 2²⁰점 FFT는 0.92 ms입니다(16스레드 FFTW 1.63 ms). 2D, 배치, 실수 변환에서는 FFTW가 1.1–3.1배 빠릅니다.
- **DSP** ([자세히](#dsp-cpu)): 측정한 모든 작업에서 double과 float 모두 `std/dsp`가 SciPy, KFR, Q, madronalib, dasp보다 빠릅니다. 2²⁰ 샘플의 biquad는 0.47 ms(Q 1.69 ms, float), 127탭 스트리밍 FIR은 3.0 ms(SciPy 19.2 ms), 44.1→48 kHz 리샘플링은 3.8 ms(SciPy 9.1 ms)입니다.
- **H200에서의 합성곱과 FFT** ([자세히](#h200-conv-fft)): cuDNN을 쓰는 SafeC는 모든 합성곱 행에서 PyTorch-CUDA보다 빠릅니다. 이미지 한 장의 64채널 3×3 합성곱은 0.018 ms(PyTorch 0.022 ms, fp32), 32장 배치는 0.084 ms(0.097 ms, TF32), 배치 16의 U-Net은 0.45 ms(0.55 ms, TF32)입니다. 호스트 배열의 2²⁰점 FFT는 PCIe 복사 두 번을 포함해 1.25 ms입니다(2.08 ms). GPU에 상주하는 데이터의 cuFFT 변환은 PyTorch를 거친 것과 같은 시간이 걸립니다.
- **컬렉션** ([자세히](#collections-stdcollections-throughput-1000000-elements)): `vec_sort`는 `qsort`보다 1.9배, 풀을 쓰는 리스트는 노드마다 `malloc`하는 리스트보다 1.9배 빠르고, `Vec` push와 `HashMap`은 순수 C보다 1.2–1.6배 느립니다.
- **할당자** ([자세히](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree)): 블록이 재사용되면 `std::alloc`이 `malloc`보다 1.8배 빠르고, 살아 있는 블록이 100만 개면 1.4배 느립니다.

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
| SafeC | 0.042s | 1.9 MB | 0.037s |
| C | 0.042s | 1.9 MB | 0.031s |
| C++ | **0.041s (가장 빠름)** | 3.8 MB | 0.049s |
| Rust | **0.041s (가장 빠름)** | 2.0 MB | 0.050s |
| Zig | 0.042s | **0.5 MB (가장 가벼움)** | 8.19s |
| Go | 0.089s | 2.0 MB | **0.024s** |
| Python | 1.992s | 10.4 MB | N/A (인터프리터) |

LLVM을 거치는 언어는 릴리스에서 모두 4% 이내로 모입니다. 차이는 잡음 수준입니다.

::: details 소스
[fib.sc](/benchmarks/fib/safec/fib.sc) · [fib.c](/benchmarks/fib/c/fib.c) · [fib.cpp](/benchmarks/fib/cpp/fib.cpp) · [fib.rs](/benchmarks/fib/rust/fib.rs) · [fib.zig](/benchmarks/fib/zig/fib.zig) · [fib.go](/benchmarks/fib/go/fib.go) · [fib.py](/benchmarks/fib/python/fib.py)
:::

## n-body — 5체 궤도 시뮬레이션 {#n-body-5-body-orbital-simulation}

Benchmarks Game의 고전적인 n-body 테스트(태양/목성/토성/천왕성/해왕성), 2,000,000 스텝. 부동소수점 연산이 많고, 할당이 없으며, 작업 집합이 아주 작습니다.

| 언어 | 시간 | 최대 메모리 | 컴파일 시간 |
|---|---|---|---|
| SafeC | **0.098s (가장 빠름)** | 2.0 MB | 0.045s |
| C | **0.098s (가장 빠름)** | 2.0 MB | 0.044s |
| C++ | **0.098s (가장 빠름)** | 3.8 MB | 0.096s |
| Rust | 0.106s | 2.0 MB | 0.071s |
| Zig | 0.110s | **0.5 MB (가장 가벼움)** | 8.28s |
| Go | 0.105s | 2.0 MB | **0.025s** |
| Python | 7.716s | 11.0 MB | N/A (인터프리터) |

::: details 소스
[nbody.sc](/benchmarks/nbody/safec/nbody.sc) · [nbody.c](/benchmarks/nbody/c/nbody.c) · [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp) · [nbody.rs](/benchmarks/nbody/rust/nbody.rs) · [nbody.zig](/benchmarks/nbody/zig/nbody.zig) · [nbody.go](/benchmarks/nbody/go/nbody.go) · [nbody.py](/benchmarks/nbody/python/nbody.py)
:::

## binary-trees — 할당/해제 스트레스 {#binary-trees-allocationdeallocation-stress}

작은 이진 트리(최대 깊이 18) 수백만 개를 만들고 버립니다 — 산술보다 메모리 관리를 시험합니다. 주 SafeC 항목은 힙 대신 `region`/`arena<R>`(범프 포인터 할당, `arena_reset<R>()`이 영역 전체를 O(1)에 버림)를 씁니다 — 이 워크로드는 전부 수명이 짧고 같은 스코프 안의 할당이라, 바로 영역이 쓰이는 경우입니다. **SafeC (힙)** 행은 같은 프로그램을 `new` / `std::dealloc`으로 바꾼 것으로, 다른 언어의 `malloc`/`Box`/GC와 같은 조건으로 비교합니다.

| 언어 | 시간 | 최대 메모리 | 컴파일 시간 |
|---|---|---|---|
| SafeC (arena) | **0.140s (가장 빠름)** | **25.8 MB (가장 가벼움)** | 0.044s |
| SafeC (힙) | 0.713s | 50.9 MB | 0.044s |
| C | 0.804s | 34.0 MB | 0.038s |
| C++ | 0.937s | 35.8 MB | 0.055s |
| Rust | 0.910s | 33.9 MB | 0.066s |
| Zig | 0.850s | 33.8 MB | 8.17s |
| Go | 0.722s | 36.3 MB | **0.027s** |
| Python | 18.169s | 82.7 MB | N/A (인터프리터) |

힙 버전이 C보다 빠른 것은 `std::alloc`이 해제된 블록을 크기 등급별로 캐시하기 때문입니다([메모리 할당](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree) 참고). 대신 메모리는 더 씁니다: 블록마다 16바이트 안전 헤더가 붙고, 캐시가 해제된 블록을 돌려주지 않고 보관합니다.

::: details 소스
[binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc) · [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c) · [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp) · [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs) · [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig) · [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go) · [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)
:::

## 멀티스레드 — binary-trees, 8 스레드 {#multithreaded-binary-trees-8-threads}

같은 binary-trees 워크로드를 작업 스레드 8개로 병렬화했습니다 — 각 스레드가 주어진 깊이의 트리 개수 중 독립된 몫을 만들고 체크섬을 계산하며, 다음 깊이로 가기 전에 합류합니다. 릴리스만 측정했습니다. SafeC (arena)는 스레드마다 `region`/`arena<R>`을 하나씩 쓰고(arena 상태는 공유하지도 잠그지도 않음), SafeC (힙)은 `new` / `std::dealloc`을 씁니다.

| 언어 | 8 스레드 시간 | 최대 메모리 | 1 스레드 대비 속도 향상 |
|---|---|---|---|
| SafeC (arena) | **0.054s (가장 빠름)** | **98.1 MB (가장 가벼움)** | 2.58× |
| SafeC (힙) | 0.263s | 260.0 MB | 2.71× |
| C | 0.225s | 161.6 MB | 3.57× |
| C++ | 0.239s | 163.5 MB | 3.92× |
| Rust | 0.234s | 162.0 MB | 3.89× |
| Zig | 0.227s | 164.0 MB | 3.75× |
| Go | 0.255s | 130.1 MB | 2.83× |
| Python | 23.034s | 364.3 MB | 0.79× (1 스레드보다 느림) |

힙 버전의 메모리가 큰 것은 스레드마다 크기 등급 캐시가 자기가 해제한 블록을 따로 보관하기 때문입니다.

::: details 소스
[binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc) · [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c) · [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp) · [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs) · [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig) · [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go) · [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)
:::

## SIMD — f64 값 20,000,000개의 제곱합 {#simd-sum-of-squares-over-20000000-f64-values}

`-O2`/릴리스에서 단순 스칼라 루프와 각 언어의 명시적 벡터 타입을 비교합니다 — "벡터화 vs 일부러 망가뜨린 코드"가 아니라, 백엔드의 자동 벡터화 *위에* 명시적 SIMD가 더해 주는 몫만 떼어 봅니다. SafeC: 네이티브 `vec<double,4>`(LLVM의 타깃 독립 `FixedVectorType`으로 내려가므로 아키텍처별 소스가 필요 없음). C/C++: GCC/Clang 벡터 확장. Zig: `@Vector`. Rust: stable 채널 인트린식(`std::simd`는 nightly 기능 필요) — x86-64에서는 SSE2([simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs)), AArch64에서는 NEON([simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs)). Go에는 이식 가능한 SIMD 타입이 없어 스칼라만 측정합니다.

| 언어 | 스칼라 | 명시적 SIMD | 속도 향상 |
|---|---|---|---|
| SafeC | 0.086s | 0.078s | 1.10× |
| C | 0.085s | 0.077s | 1.11× |
| C++ | 0.082s | **0.076s (가장 빠름)** | 1.07× |
| Rust | 0.083s | 0.077s | 1.07× |
| Zig | **0.075s (가장 빠름)** | **0.076s (가장 빠름)** | 0.99× |
| Go | 0.086s | N/A | — |
| Python | 3.010s | 0.190s (NumPy) | 15.9× |

이 루프는 7800X3D에서 메모리 대역폭에 묶입니다(double 160MB가 96MB L3를 훨씬 넘어 흘러감). 그래서 명시적 SIMD가 자동 벡터화된 스칼라 루프보다 얻는 것이 적고, 표 전체가 프로세스 시작 잡음 정도인 ~10ms 안에 있습니다.

::: details 소스
[simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc) · [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c) · [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp) · [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs) · [simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs) · [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig) · [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go) · [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)
:::

## 컬렉션 — std::collections 처리량 (1,000,000개 요소) {#collections-stdcollections-throughput-1000000-elements}

제네릭 컨테이너를 같은 알고리즘의 순수 C 구현과 비교합니다: 늘어나는 배열 + `qsort`, 같은 해시를 쓰는 선형 탐사 해시 맵, 노드마다 `malloc`하는 이중 연결 리스트, 2의 거듭제곱 크기 링 큐, 균형을 맞추지 않는 BST. 양쪽 모두 같은 섞인 키(xorshift64)를 쓰고, 둘 다 clang 21.1.8 `-O2`(`safeguard build --release`), 3회 중 최선입니다.

| 연산 | SafeC | C | SafeC / C |
|---|---|---|---|
| `Vec<int>` push | 2.50 ms | **1.55 ms** | 1.61× |
| `vec_sort` (인트로소트, 인라인된 `Less<int>`) vs `qsort` | **43.5 ms** | 83.4 ms | 0.52× |
| `HashMap<int,int>` insert | 41.6 ms | **33.5 ms** | 1.24× |
| `HashMap<int,int>` get | 8.63 ms | **7.24 ms** | 1.19× |
| `List<int>` push_back + pop_front | **11.5 ms** | 22.2 ms | 0.52× |
| `Queue<int>` enqueue + dequeue | **3.42 ms** | 5.47 ms | 0.63× |
| `BST<int,int,Less<int>>` insert | **177.1 ms** | 179.0 ms | 0.99× |
| `BST<int,int,Less<int>>` get | **171.0 ms** | 176.0 ms | 0.97× |

::: details 결과를 가르는 요인
- **정렬**이 `qsort`보다 빠른 것은 순서가 타입 매개변수이기 때문입니다: 모든 비교가 인라인되지만, `qsort`는 비교할 때마다 간접 호출을 합니다.
- **List**와 **BST** 노드는 노드마다 할당하지 않고 컨테이너별 노드 풀(청크 단위 범프 할당 + 프리 리스트)에서 나옵니다. 그래서 `malloc`의 청크당 오버헤드와 `std::alloc`의 16바이트 안전 헤더를 모두 피합니다. BST 조회는 무작위 탐색의 캐시 미스가 지배하며, 양쪽이 같습니다.
- **HashMap**은 1/2/4/8바이트 키를 한 워드로 해시하고(murmur3 finalizer) 정수 비교 한 번으로 비교하며, 이를 컴파일 시간에 `if const`로 고릅니다. 남은 차이는 insert의 툼스톤 관리입니다.
- **Vec push**는 경계 검사 접근(`keys.at(i)`)과 `std::alloc`의 검사된 `realloc` 경로 비용을 냅니다.
:::

::: details 소스
[bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc) · [bench_collections.c](/benchmarks/collections/c/bench_collections.c)
:::

## 웹 서비스 — JSON "hello world" 엔드포인트 {#web-service-json-hello-world-endpoint}

`GET /`이 `{"message":"Hello, World!"}`를 돌려줍니다 — TechEmpower의 "JSON serialization" 테스트와 같은 형태입니다. Apache Bench(`ab -n 20000 -c 50`, keep-alive 없음, 2,000 요청 워밍업 후)로 각 언어의 HTTP 방식을 측정합니다: SafeC의 `std::http_serve_reactor`(Linux에서는 epoll 리액터), C/C++/Zig는 최소한의 raw 소켓 accept 루프, Go의 `net/http`, Python의 FastAPI+uvicorn, Rust의 **axum**(Dioxus가 아님 — Dioxus의 풀스택 서버 계층이 바로 axum이므로, 실제로 거치는 것을 측정합니다).

| 언어 | req/s | p50 / p99 지연 | 최대 메모리 |
|---|---|---|---|
| SafeC | **25,216 (가장 빠름)** | 2 / 2 ms | 5.1 MB |
| C | 24,618 | 2 / 2 ms | **3.0 MB (가장 가벼움)** |
| C++ | 24,870 | 2 / 2 ms | 5.0 MB |
| Rust | 24,814 | 2 / 2 ms | 4.3 MB |
| Zig | 24,828 | 2 / 2 ms | 9.3 MB |
| Go | 23,573 | 2 / 3 ms | 15.7 MB |
| Python | 5,047 | 10 / 14 ms | 46.1 MB |

모든 서버가 실패 없이 모든 요청을 처리했습니다. 컴파일 언어 서버는 모두 서로 8% 이내입니다: keep-alive가 없으면 요청마다 새 TCP 연결이므로, 병목은 서버가 아니라 커널의 connect/accept 경로와 `ab` 자체입니다.

::: details 소스
[server_reactor.sc](/benchmarks/webservice/safec/server_reactor.sc) · [server.sc](/benchmarks/webservice/safec/server.sc) · [server.c](/benchmarks/webservice/c/server.c) · [server.cpp](/benchmarks/webservice/cpp/server.cpp) · [main.rs](/benchmarks/webservice/rust/src/main.rs) · [Cargo.toml](/benchmarks/webservice/rust/Cargo.toml) · [server.zig](/benchmarks/webservice/zig/server.zig) · [server.go](/benchmarks/webservice/go/server.go) · [server.py](/benchmarks/webservice/python/server.py) · [http.sc](/benchmarks/stdlib/http.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h) · [task.h](/benchmarks/stdlib/task.h)
:::

## 머신러닝 — 작은 MLP, 학습과 추론 {#machine-learning-small-mlp-training-and-inference}

2층 MLP(`relu(X @ W1) @ W2`, 128→256→64, 배치 64, MSE 손실, 직접 작성한 SGD, 바이어스 없음) — 고정 시드로 학습 100 스텝 후 추론 1000회. 모든 프레임워크가 매 스텝 손실을, 매 추론마다 `Y[0][0]`을 호스트로 읽어 옵니다(PyTorch의 `.item()`). 그래서 GPU 행은 손실을 기록하는 실제 학습 루프처럼 스텝마다 호스트 왕복 비용을 냅니다. 각 프레임워크는 먼저 시간을 재지 않는 워밍업을 한 번 합니다: 이 빌드의 PyTorch는 첫 `backward()`에 CPU에서도 ~10초의 일회성 초기화가 걸리고, rocBLAS는 GEMM 형태별 커널을 처음 쓸 때 불러옵니다.

| 프레임워크 | 장치 | 학습 (100 스텝) | 처리량 | 추론 (1000회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (내장 `sgemm`, 16 스레드) | 17.6 ms | 364,100 samples/s | 35.2 ms | 100.333267 |
| SafeC | CPU, AOCL-BLAS | 14.8 ms | 431,100 samples/s | 24.9 ms | 100.333267 |
| SafeC | CPU, oneMKL | 18.6 ms | 343,800 samples/s | 30.4 ms | 100.333267 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 25.6 ms | 249,900 samples/s | 55.6 ms | 100.333267 |
| SafeC | ROCm (MI100), fp32 | 7.26 ms | 881,300 samples/s | 33.3 ms | 100.333282 |
| SafeC | ROCm, 그래프, fp32 | 7.01 ms | 912,900 samples/s | 36.4 ms | 100.333282 |
| SafeC | ROCm, 그래프, fp16 | **5.39 ms (가장 빠름)** | **1,187,000 samples/s** | 33.2 ms | 100.336113 |
| SafeC | ROCm, 그래프, bf16 | 5.78 ms | 1,106,700 samples/s | 35.0 ms | 99.905960 |
| PyTorch | CPU | 14.1 ms | 453,600 samples/s | **22.0 ms (가장 빠름)** | 100.018272 |
| PyTorch | ROCm (MI100), fp32 | 20.5 ms | 311,700 samples/s | 36.3 ms | 103.071129 |
| PyTorch | ROCm, fp16 | 19.0 ms | 336,600 samples/s | 33.5 ms | 103.062500 |
| PyTorch | ROCm, bf16 | 18.9 ms | 338,700 samples/s | 47.1 ms | 103.000000 |
| TensorFlow | CPU | 79.3 ms | 80,700 samples/s | 203.5 ms | 106.182999 |

이 크기에서는 GPU 스텝 하나가 마이크로초 단위 커널 몇 개와 손실의 블로킹 읽기로 이루어지므로, 실행 시작과 동기화 지연이 시간을 지배합니다. CPU에서는 PyTorch가 여전히 추론에서 가장 빠릅니다. AOCL-BLAS를 링크한 SafeC는 학습에서 PyTorch의 5% 이내입니다(14.8 vs 14.1 ms).

SafeC의 CPU 행은 `tensor_matmul`을 패킹된 SIMD GEMM인 [`sgemm`](/ko/stdlib/simd#gemm)으로 [스레드 풀](/ko/stdlib/sync#parallel)에서 실행합니다. 커널은 실행 시점에 CPU의 [SIMD 수준](/ko/stdlib/simd#cpu-levels)(여기서는 AVX-512)에 맞춰 고릅니다. 텐서는 단계마다의 [`TensorArena`](/ko/stdlib/ml#tensor-arena)에서 할당하고, 풀의 워커는 잠들기 전에 잠시 스핀합니다. [`[build] blas`](/ko/advanced/safeguard#linking-external-libraries-and-library-output)를 지정하면 GEMM 타일은 AOCL-BLAS(AMD)나 oneMKL(Intel)로 가며, SafeC의 풀에서 타일마다 단일 스레드로 실행됩니다. OpenBLAS 행은 예전의 `tensor_blas.h` 경로로, 곱셈마다 멀티스레드 OpenBLAS 호출 하나입니다. 이 모든 것이 대체한 단순 스칼라 루프는 학습 107.4 ms, 추론 285.8 ms였습니다. 프레임워크마다 손실이 다른 것은 각자 자기 RNG로 데이터와 초기 가중치를 뽑기 때문입니다. SafeC 행들은 모두 시드가 같은 하나의 스트림(`drand48`)을 공유하며, CPU와 GPU에서 6자리까지 일치합니다.

::: details 소스
[train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [gemm.h](/benchmarks/stdlib/gemm.h) · [gemm.sc](/benchmarks/stdlib/gemm.sc) · [parallel.h](/benchmarks/stdlib/parallel.h) · [parallel.sc](/benchmarks/stdlib/parallel.sc) · [tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py)
:::

## 머신러닝, 더 큰 모델 — 512→1024→256 {#machine-learning-bigger-model-512-1024-256}

같은 모델을 ~50배 키웠습니다(배치 128). 학습 50 스텝, 추론 200회. 워밍업과 스텝마다 읽어 오는 방식은 위와 같습니다.

| 프레임워크 | 장치 | 학습 (50 스텝) | 처리량 | 추론 (200회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (내장 `sgemm`, 16 스레드) | 48.7 ms | 131,300 samples/s | 67.3 ms | 95.426926 |
| SafeC | CPU, AOCL-BLAS | 37.0 ms | 173,100 samples/s | 43.3 ms | 95.427536 |
| SafeC | CPU, oneMKL | 44.8 ms | 142,900 samples/s | 52.0 ms | 95.428177 |
| SafeC | CPU (OpenBLAS, `tensor_blas.h`) | 148.5 ms | 43,100 samples/s | 240.4 ms | 95.424927 |
| SafeC | ROCm (MI100), fp32 | 7.64 ms | 838,100 samples/s | 10.6 ms | 95.428314 |
| SafeC | ROCm, 그래프, fp32 | 7.37 ms | 868,500 samples/s | 11.5 ms | 95.428322 |
| SafeC | ROCm, 그래프, fp16 | **5.90 ms (가장 빠름)** | **1,085,300 samples/s** | **9.26 ms (가장 빠름)** | 95.090317 |
| SafeC | ROCm, 그래프, bf16 | 6.05 ms | 1,057,500 samples/s | 9.80 ms | 63.433674 |
| PyTorch | CPU | 38.5 ms | 166,100 samples/s | 51.4 ms | 106.601181 |
| PyTorch | ROCm (MI100), fp32 | 10.7 ms | 599,000 samples/s | 13.4 ms | 1868.155273 |
| PyTorch | ROCm, fp16 | 9.91 ms | 645,600 samples/s | 17.9 ms | 1942.000000 |
| PyTorch | ROCm, bf16 | 10.8 ms | 590,700 samples/s | 15.7 ms | 107.000000 |
| TensorFlow | CPU | 105.6 ms | 60,600 samples/s | 114.6 ms | 316.611603 |

GPU에서 fp32로 SafeC는 PyTorch보다 학습이 1.45배, 추론이 1.26배 빠릅니다(둘 다 한 번에 rocBLAS GEMM 두 개, 원소별 커널 하나, 읽기 한 번). 이 차이의 일부는 새로 생겼습니다: 각 GEMM 크기의 첫 호출이 rocBLAS가 내놓는 솔루션을 모두 재서 가장 빠른 것을 쓰므로([ROCm](/ko/stdlib/ml#rocm-hip-amd) 참고) fp32 그래프 스텝이 8.29 ms에서 7.37 ms로 줄었습니다. 그래프 재생은 학습 스텝 전체를 `RocmGraph` 하나에 기록해 한 번의 호출로 실행하며, 매 스텝 손실을 블로킹으로 읽어도 3–4% 줄어듭니다. 스텝마다 읽지 않으면, 같은 루프의 C++ 프로토타입이 7.1ms 대신 3.8ms로 돌았습니다. PyTorch-ROCm의 손실은 GPU RNG로 뽑은 초기 가중치에서 lr 0.001로 발산합니다. 속도와는 상관없지만, 검증용 값이 이상해 보이는 이유입니다.

**fp16과 bf16.** 16비트 행은 데이터, 가중치, 활성화, 기울기를 모두 [`RocmHalfBuf`](/ko/stdlib/ml#rocm-hip-amd)에 담은 그래프 스텝입니다. GEMM은 MI100의 행렬 코어에서 rocBLAS `gemm_ex`로, fp32로 누적합니다. 원소별 연산은 fp32로 계산해 저장할 때 한 번 반올림하고, 손실은 fp32로 남습니다. PyTorch 행은 같은 모델과 데이터를 `.to(dtype)`로 바꿔 그 dtype으로만 학습합니다.

- 여기서 16비트가 SafeC의 가장 빠른 모드입니다. fp16은 fp32 그래프보다 작은 모델에서 23%(5.39 vs 7.01 ms), 큰 모델에서 20%(5.90 vs 7.37 ms) 빠르게 학습하고, 큰 모델 추론도 20% 빠릅니다(9.26 vs 11.5 ms). bf16은 fp16과 7% 이내입니다.
- 같은 dtype의 PyTorch-ROCm과 비교하면 SafeC는 작은 모델 학습에서 3.5배(fp16), 3.3배(bf16), 큰 모델에서 1.7배, 1.8배 빠릅니다. 큰 모델 추론은 1.9배, 1.6배 빠릅니다. PyTorch는 16비트로 학습이 많아야 8% 빨라지고, 큰 모델 추론은 16비트가 fp32보다 느립니다.
- 크기별 솔루션 선택이 여기서 가장 중요합니다. rocBLAS의 기본 솔루션으로는 큰 모델에서 fp16이 fp32보다 *느렸습니다*(9.3 vs 8.4 ms): 128×256×1024 fp16 곱의 기본 솔루션이 0.047 ms, 가장 빠른 것은 0.008 ms였습니다.
- 이 루프는 반올림 차이를 키웁니다. 50 스텝 뒤 큰 모델의 손실은 fp16에서 95.09, bf16에서 63.43이고 fp32는 95.43입니다. H200도 같은 양상입니다(93.44, 62.01).
- CPU의 [`tensor_half.h`](/ko/stdlib/ml#half-precision-tensors)는 fp16/bf16으로 저장하지만 계산은 fp32로 하므로 메모리는 줄어도 시간은 줄지 않습니다. 그래서 16비트 CPU 행은 없습니다.

CPU에서는 이 크기에서 AOCL-BLAS를 쓴 SafeC가 가장 빠릅니다: 학습은 PyTorch보다 4%(37.0 vs 38.5 ms), 추론은 16%(43.3 vs 51.4 ms) 빠릅니다. 내장 `sgemm`만으로도 PyTorch의 1.3배 이내이며, 이것이 대체한 스칼라 루프(학습 1761.2 ms)보다 36배 빠릅니다. OpenBLAS 행(Ubuntu 패키지의 범용 pthreads 빌드)이 가장 느린 CPU 경로입니다. 곱셈마다 멀티스레드 호출 하나이고, 이 작은 GEMM들에서 그 스레드들이 서로 스핀하며 부딪힙니다.

::: details 소스
[train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_cpu_blas.sc](/benchmarks/ml_big/safec/train_cpu_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc) · [PyTorch train.py](/benchmarks/ml_big/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml_big/tensorflow/train.py) · [MLX train.py](/benchmarks/ml_big/mlx/train.py)
:::

## 메모리 할당 — `std::alloc`/`dealloc`은 raw `malloc`/`free`보다 느린가? {#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree}

`std::alloc`은 블록마다 이중 해제/해제 후 사용 검사를 담는 16바이트 헤더를 붙이고, 크기 등급 캐시를 둡니다: PyTorch의 CPU/CUDA 캐싱 할당자, MLX의 Metal 버퍼 캐시와 같은 발상입니다. 해제된 블록은 2의 거듭제곱 크기 등급별 스레드 로컬 프리 리스트로 들어가고, 같은 등급의 다음 `alloc()`은 `malloc()`/`free()`를 부르지 않고 거기서 바로 받습니다. 캐시된 블록은 재사용될 때까지 "해제됨" 매직 워드를 유지하므로 안전 검사는 그대로 동작합니다. glibc의 `malloc`/`free`와 비교한 결과(3회 중 최선. 모든 블록 주소를 출력되는 체크섬에 섞어, 최적화기가 할당/해제 쌍을 지우지 못하게 함):

| 패턴 | `std::alloc` / `dealloc` | `malloc` / `free` | 비율 |
|---|---|---|---|
| 교차: 48바이트 블록 하나를 할당하고 해제, 2천만 번 | **3.66 ns / 쌍** | 6.76 ns / 쌍 | 1.8배 빠름 |
| 일괄: 24바이트 블록 100만 개를 할당한 뒤 모두 해제, 10회 | 134 ms | **94 ms** | 1.4배 느림 |

해제된 블록이 곧 다시 쓰이면 캐시가 이깁니다. 수명이 짧은 객체와 binary-trees 같은 반복 할당이 그렇습니다: 위의 힙 binary-trees는 다른 모든 언어의 할당자보다 빠릅니다. 살아 있는 블록 100만 개는 캐시가 담을 수 있는 양(모든 등급 합쳐 125,587 슬롯)을 넘으므로, 일괄 패턴의 할당 대부분은 `malloc`에 헤더 쓰기와 캐시 관리까지 거치고, 그 오버헤드가 드러납니다. 수명이 짧은 대량 할당에는 `region`/`arena<R>`이 더 빠른 도구입니다: binary-trees에서 힙 버전보다 5.1배 빠릅니다.

::: details 소스
[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc) · [alloc_bench.sc](/benchmarks/memalloc/safec/alloc_bench.sc)
:::

## MI100의 합성곱과 FFT {#mi100-conv-fft}

[H200](#h200-conv-fft)과 같은 작업을 이 페이지의 주 머신에서 잰 결과입니다: SafeC의 CPU 코드(`cnn.h`, `unet.h`, `fft.h`), ROCm 라이브러리 [`gpu_miopen.h`와 `fft_rocm.h`](/ko/stdlib/ml#rocm-miopen-hipfft)(ROCm 7.1의 MIOpen과 hipFFT), 그리고 ROCm 6.4 위의 PyTorch 2.9.1(역시 MIOpen과 rocFFT 사용). 텐서는 호출 사이에 GPU에 머뭅니다. GPU 행은 두 번의 동기화 사이에 쌓은 호출 300–2000개의 평균, 3회 중 최선이며, 모두 fp32입니다(MI100에는 TF32가 없음). SafeC는 첫 호출에서 MIOpen의 Find로 합성곱 알고리즘을 고르고, PyTorch도 같은 일을 합니다(`cudnn.benchmark = True`).

합성곱, 호출당 ms(낮을수록 좋음). `conv64`는 Conv2D 계층 하나(64→64 채널, 3×3, 스트라이드 1, 패딩 1, 56×56, 바이어스 포함). `unet128`은 128×128에서 `unet_new(3, 32, 64, 3)`의 순전파입니다.

| 프레임워크 | 장치 | conv64, 이미지 1장 | conv64, 32장 | unet128, 1장 | unet128, 16장 |
|---|---|---|---|---|---|
| SafeC | CPU (Winograd / im2col + 내장 `sgemm`, 16 스레드) | 0.520 | — | 1.86 | — |
| SafeC | CPU, AOCL-BLAS | 0.470 | — | 1.42 | — |
| SafeC | CPU, oneMKL | 0.505 | — | 1.80 | — |
| PyTorch | CPU (oneDNN, 8 스레드) | 0.310 | — | 2.97 | — |
| SafeC | ROCm | **0.0270** | **0.343** | 0.224 | 1.288 |
| SafeC | ROCm, U-Net을 그래프 하나로 | | | **0.219** | |
| PyTorch | ROCm | 0.0627 | 0.345 | 0.315 | **1.264** |

GPU의 FFT, 호출당 ms. `fft1d`와 `fft2d`는 호스트 메모리에서 시작해 끝나는 복소 `double` 배열이라, GPU 호출마다 업로드와 읽기가 포함됩니다. `batch`와 `rfft`는 이미 GPU에 있는 데이터(complex64, float32)로 실행합니다.

| 프레임워크 | 장치 | fft1d, 2²⁰점 | fft2d, 1024×1024 | batch, 1024 × 4096 | rfft, 1024 × 4096 |
|---|---|---|---|---|---|
| SafeC | ROCm | **1.54** | **1.44** | **0.0997** | **0.0945** |
| PyTorch | ROCm | 1.55 | 1.53 | 0.169 | 0.126 |

CPU의 FFT, 호출당 ms. 비교 대상은 FFTW 3.3.10(AVX/AVX2/AVX-512, FMA 코드렛으로 빌드, `FFTW_MEASURE`로 계획), KFR 7.2.0, RustFFT 6.4.1입니다. 데이터는 모두 메모리에서 시작합니다. `fft1d`는 2²⁰점 변환 하나, `fft2d`는 1024×1024 변환 하나, `batch`는 4096점 변환 1024개, `rfft`는 4096점 실수 변환 1024개입니다. `rfft`에서 FFTW, KFR, RustFFT는 n/2 + 1개 빈을 돌려주고, SafeC의 `fft_real_batch`는 n개 전부를 씁니다. RustFFT에는 2D와 실수 변환이 없습니다. oneMKL을 링크하면 SafeC는 65,536점 미만의 변환을 DFTI에 맡깁니다. AOCL은 여기에 영향이 없습니다(AOCL의 FFT는 FFTW 기반이라 GPL이며 쓰지 않음).

배정밀도(complex128):

| 라이브러리 | 스레드 | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft.h` | 16 | **0.917** | 0.654 | 1.26 | 1.75 |
| SafeC, oneMKL DFTI | 16 | 0.938 | 1.03 | 2.01 | 1.96 |
| FFTW | 16 | 1.63 | **0.597** | **0.975** | **0.561** |
| PyTorch | 8 | 8.36 | 0.749 | — | — |
| SafeC `fft.h` | 1 | 5.53 | 4.38 | 13.9 | 8.56 |
| FFTW | 1 | 6.70 | 3.51 | 7.83 | 4.10 |
| KFR | 1 | 3.45 | 4.25 | 11.9 | 4.33 |
| RustFFT | 1 | 4.76 | — | 8.44 | — |

단정밀도(complex64 / float32):

| 라이브러리 | 스레드 | fft1d | fft2d | batch | rfft |
|---|---|---|---|---|---|
| SafeC `fft_f32` | 16 | **0.692** | 0.539 | 0.822 | 0.720 |
| SafeC, oneMKL DFTI | 16 | 0.694 | 0.513 | 0.915 | 0.515 |
| FFTW (`fftwf`) | 16 | 1.69 | **0.432** | **0.500** | **0.409** |
| PyTorch | 8 | — | — | 4.65 | 0.438 |
| SafeC `fft_f32` | 1 | 4.49 | 3.39 | 5.83 | 5.41 |
| FFTW (`fftwf`) | 1 | 5.52 | 2.51 | 3.45 | 2.98 |
| KFR | 1 | 1.77 | 2.65 | 4.17 | 2.13 |
| RustFFT | 1 | 2.10 | — | 4.19 | — |

단일 스레드 SafeC 행은 표 측정 뒤에 같은 빌드로 따로 쟀습니다(`SAFEC_NUM_THREADS=1`).

- **합성곱**: GPU에서 SafeC는 이미지 1장에서 PyTorch보다 2.3배, 그래프로 실행한 배치 1 U-Net에서 1.44배 빠릅니다. 32장에서는 비기고, 16장 U-Net에서는 PyTorch가 2% 빠릅니다. SafeC의 합성곱은 MIOpen 호출 하나와 바이어스(및 ReLU)용 HIP 커널 하나이고, U-Net 디코더 단계는 연결 버퍼로 바로 업샘플하는 커널 하나입니다.
- **CPU 합성곱**: 3×3, 스트라이드 1 계층은 이제 [Winograd F(2×2, 3×3)](/ko/stdlib/ml#cnn)(Lavin & Gray)를 씁니다. 출력 2×2와 채널 쌍마다 곱셈이 36번 대신 16번입니다. 입력 변환, 채널 GEMM 16개, 출력 변환이 각각 풀에서 실행됩니다. 그 밖의 계층은 im2col + `sgemm`입니다. U-Net에서 SafeC는 AOCL-BLAS로 PyTorch의 CPU 경로보다 2.1배(1.42 vs 2.97 ms), 내장 GEMM으로 1.6배 빠릅니다. Winograd 이전에는 3.13 ms였습니다(이 페이지의 이전 측정에서는 4.86 ms). 단일 `conv64` 계층에서는 PyTorch(oneDNN)가 여전히 1.5–1.7배 빠릅니다.
- **GPU의 FFT**: 양쪽 모두 rocFFT를 부릅니다. 호스트 배열은 PCIe가 지배해 시간이 같습니다. 장치 데이터에서는 SafeC가 1.3–1.7배 빠릅니다. 호출당 0.1 ms 수준에서는 같은 변환을 둘러싼 PyTorch의 호출당 작업(출력 텐서 할당, 디스패치)이 큰 몫을 차지하지만, 따로 재지는 않았습니다.
- **CPU FFT**: 2²⁰점 변환에서는 두 정밀도 모두 SafeC가 가장 빠르며, 16스레드 FFTW보다 1.8배(double), 2.4배(float) 앞섭니다. 2D, 배치, 실수 변환에서는 FFTW가 1.1배(double `fft2d`)에서 3.1배(double `rfft`)까지 앞섭니다. 단일 스레드에서 SafeC는 2²⁰점에서는 FFTW보다 빠르고, 2D와 4096점 행에서는 뒤집니다(double 배치에서 1.8배). 이 행들에서는 FFTW의 더 큰 코드렛과 행마다 더 적은 패스가 효과를 냅니다. SafeC의 실수 변환 후처리는 아직 스칼라입니다. 이 페이지의 이전 측정 이후, 합친 radix-8 패스, 벡터 하나에 복소수 네 개(AVX-512, 그리고 AVX2의 float), 컴파일 타임 비트 역순 테이블로 16스레드 double 배치가 4.35 ms에서 1.26 ms로, 2D 변환이 2.06 ms에서 0.654 ms로 줄었습니다.
- **단정밀도**: float는 같은 알고리즘(`fft_core.h`를 두 번 빌드)을 레지스터당 두 배의 값으로 실행합니다. 배치는 double 시간의 0.65배, 실수 배치는 0.41배입니다.
- **oneMKL**: DFTI는 float `rfft`(0.515 vs 0.720)와 float `fft2d`에서는 SafeC 자체 코드보다 빠르지만, double에서는 느립니다(배치 2.01 vs 1.26).
- **워밍업**: 모든 CPU 행은 시간을 재지 않는 호출을 먼저 한 번 합니다(스레드 풀을 시작하고 회전 인자 테이블을 만듭니다). FFTW의 계획은 시간 측정 밖에서 만듭니다. PyTorch 스크립트는 워밍업 호출을 다섯 번 합니다.

::: details 소스
[conv_rocm.sc](/benchmarks/conv/safec/conv_rocm.sc) · [conv_cpu.sc](/benchmarks/conv/safec/conv_cpu.sc) · [conv.py](/benchmarks/conv/pytorch/conv.py) · [fft_rocm.sc](/benchmarks/fft/safec/fft_rocm.sc) · [fft_cpu.sc](/benchmarks/fft/safec/fft_cpu.sc) · [fft.py](/benchmarks/fft/pytorch/fft.py) · [fftw_bench.c](/benchmarks/fft/c/fftw_bench.c) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [RustFFT main.rs](/benchmarks/dsp/rust/src/main.rs) · [cnn.sc](/benchmarks/stdlib/cnn.sc) · [unet.sc](/benchmarks/stdlib/unet.sc) · [gemm.sc](/benchmarks/stdlib/gemm.sc) · [fft.h](/benchmarks/stdlib/fft.h) · [fft.sc](/benchmarks/stdlib/fft.sc) · [fft_f32.sc](/benchmarks/stdlib/fft_f32.sc) · [fft_core.h](/benchmarks/stdlib/fft_core.h) · [parallel.sc](/benchmarks/stdlib/parallel.sc) · [gpu_miopen.h](/benchmarks/stdlib/gpu_miopen.h) · [gpu_miopen.sc](/benchmarks/stdlib/gpu_miopen.sc) · [fft_rocm.h](/benchmarks/stdlib/fft_rocm.h) · [fft_rocm.sc](/benchmarks/stdlib/fft_rocm.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc)
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
| fir stream | 5.99 | 5.92 / 5.90 | 18.0 | 31.9 | |
| fir block | 0.836 | 0.805 / 0.872 | 15.3 | — | |
| fftconv | 2.66 | 2.72 / 3.01 | 32.6 | 4.88 | |
| biquad | 0.465 | 0.477 / 0.484 | 4.67 | 2.33 | |
| stft | 1.93 | 1.96 / 2.06 | 53.0 | — | |
| resample | 3.78 | 3.82 / 3.86 | 9.10 | 63.6 (draft 26.3) | dasp 520 |
| conv2d | 0.393 | 0.396 / 0.387 | 35.8 | — | |

단정밀도:

| 작업 | SafeC | SafeC, AOCL / oneMKL | SciPy | KFR | 기타 |
|---|---|---|---|---|---|
| fir stream | 3.02 | 3.04 / 3.10 | 19.2 | 27.8 | |
| fir block | 0.484 | 0.443 / 0.511 | 18.9 | — | |
| fftconv | 1.57 | 1.54 / 1.52 | 14.7 | 2.80 | |
| biquad | 0.469 | 0.474 / 0.473 | 4.58 | 2.14 | Q 1.69, madronalib 2.90 |
| stft | 1.13 | 1.12 / 1.09 | 55.5 | — | |
| resample | 3.93 | 3.90 / 3.92 | 8.70 | 34.1 (draft 27.2) | |

- 두 정밀도 모두 모든 행에서 SafeC가 가장 빠릅니다. 벤더 라이브러리는 FFT가 들어가는 곳(`fftconv`, `stft`)에서만 의미가 있고, 거기서도 내장 코드와 오차 범위 안입니다.
- **FIR**: 블록마다 `dsp_sliding_dot`을 실행합니다. 패스 하나에 출력 32개(float는 64개)를 계산하며, 탭마다 값을 네 개의 누산 벡터에 브로드캐스트하므로 수평 합이 없습니다. 스트리밍 필터는 14.2 ms에서 5.99 ms(double), 9.45 ms에서 3.02 ms(float)로 줄었습니다. KFR과 SciPy는 출력마다 내적을 하나씩 합니다. `fir block`은 스레드를 쓰고, `fir stream`은 쓰지 않습니다.
- **Biquad**: `biquad_process_block`은 분산 룩어헤드(Parhi & Messerschmitt)를 씁니다. 재귀를 y[i-2]와 y[i-4]로 다시 써서 짝수와 홀수 출력을 서로 독립인 두 사슬로 만들고, 2레인 벡터 하나로 함께 계산합니다. 그 결과 float에서 Q보다 3.6배, KFR보다 4.6배 빠르고, 이전(2.14 ms)보다 4.6배 빠릅니다. 추가되는 극점은 필터 자신의 극점을 뒤집은 것이라 안정성이 유지되고, 출력은 `process()`와 반올림 차이만 납니다.
- **리샘플링**: 탭마다의 가중치를 호출마다 한 번 만드는 테이블과 각의 차 공식으로 구하므로 탭이 벡터화됩니다. 출력마다의 각도는 회전으로 넘기므로 출력마다 삼각 함수 호출이 없습니다. SciPy의 폴리페이즈 필터보다 2.4배 빠릅니다(이전 12.1 ms). SafeC의 리샘플러는 스레드를 쓰고, SciPy는 쓰지 않습니다.
- **STFT** 프레임은 제자리 실수 FFT 배치로 변환합니다. 이전에 쓰던 복소 변환의 절반 작업입니다(이전 7.08 ms).
- **float vs double**: float는 메모리 트래픽이나 벡터 폭이 한계인 곳에서 이득입니다: FIR(2배), `fftconv`, `stft`. biquad는 지연 시간이 한계이고 리샘플러는 커널을 double로 계산하므로, 이 둘은 변하지 않습니다.

::: details 소스
[dsp_cpu.sc](/benchmarks/dsp/safec/dsp_cpu.sc) · [dsp_scipy.py](/benchmarks/dsp/python/dsp_scipy.py) · [kfr_bench.cpp](/benchmarks/dsp/cpp/kfr_bench.cpp) · [audio_libs_bench.cpp](/benchmarks/dsp/cpp/audio_libs_bench.cpp) · [dasp main.rs](/benchmarks/dsp/rust/src/main.rs) · [Cargo.toml](/benchmarks/dsp/rust/Cargo.toml) · [dsp.sc](/benchmarks/stdlib/dsp.sc) · [filter.sc](/benchmarks/stdlib/filter.sc) · [biquad.sc](/benchmarks/stdlib/biquad.sc) · [convolution.sc](/benchmarks/stdlib/convolution.sc) · [conv_core.h](/benchmarks/stdlib/conv_core.h) · [stft.sc](/benchmarks/stdlib/stft.sc) · [resample_core.h](/benchmarks/stdlib/resample_core.h) · [imaging_core.h](/benchmarks/stdlib/imaging_core.h) · [fft_core.h](/benchmarks/stdlib/fft_core.h)
:::

## Xeon Platinum 8570 + NVIDIA H200 {#xeon-8570-h200}

같은 벤치마크를 GPU 서버에서 돌린 결과이며, SafeC의 CUDA 백엔드를 검증한 장비입니다. CPU, 코어 수, 툴체인 버전이 모두 다르므로 위의 7800X3D 표와 비교하지 말고 이 절 안에서만 비교하세요.

- **장비**: 호스트 112코어 중 12코어로 제한된 Backend.AI 컨테이너의 Intel Xeon Platinum 8570, NVIDIA H200 (sm_90, 141 GB HBM3e, 드라이버 580.126.20, CUDA 13.0, cuDNN 9.15), Ubuntu 24.04.3 — `main`(449420a, v1.0.0-beta-5 이후)에서 빌드한 `safec` 1.0.0 · clang 21.1.8 · Rust 1.99.1 · Go 1.27.3 · Zig 0.17.0 · Python 3.12.3 · PyTorch 2.10.0a0 (NGC 25.11) · TensorFlow 2.21.0 (CPU) · OpenBLAS (pthreads, Ubuntu 패키지).
- **방법**: [위](#methodology)와 같고, 3회 중 최고값입니다. H200은 학습 작업과 공유되는데, 측정하는 동안 그 작업을 일시 정지(`SIGSTOP`)해서 GPU와 12코어가 비어 있었습니다. 그 작업이 도는 동안에는 같은 GPU 벤치마크가 SafeC든 PyTorch든 실행마다 5배까지 차이 났습니다. SafeC의 std는 7800X3D에서와 같이 기본 x86-64 대상(SSE2, FMA 없음)으로 빌드됩니다.
- **수치**: [`results/linux-xeon8570-h200.json`](/benchmarks/results/linux-xeon8570-h200.json). `main`의 CPU 코드로 다시 돌렸을 때, 이전(beta-5 시점) 측정의 GPU 수치는 모두 1% 안에서 재현되었습니다. 그 뒤 [머신러닝](#h200-machine-learning)과 [합성곱/FFT](#h200-conv-fft) 절은 SafeC의 `cuda-perf-gaps` 브랜치(cuDNN graph API, pinned 전송 레인, fp16/bf16 버퍼)로 다시 측정했습니다.

### H200에서의 머신러닝 {#h200-machine-learning}

모델, 루프, 스텝마다의 읽기는 [MI100 표](#machine-learning-small-mlp-training-and-inference)와 같습니다. SafeC CUDA 행은 [train_cuda.sc](/benchmarks/ml/safec/train_cuda.sc)의 여섯 가지 모드입니다:

- **plain**: ROCm 벤치마크와 같은, 커널 실행 10번짜리 스텝.
- **fused**: `relu(X W1)`을 cuBLASLt 호출 한 번으로, SGD 갱신 두 번을 커널 하나로 처리해 스텝당 7번 실행.
- **fused + graph**: fused 스텝을 CUDA 그래프 하나로 재생.
- **TF32**: 그래프 스텝에 TF32 텐서 코어를 켠 것(`cuda_set_tf32`).
- **fp16 / bf16**: 데이터, 가중치, 활성값, 기울기를 모두 16비트로 저장하는 그래프 스텝([`CudaHalfBuf`](/ko/stdlib/ml#cuda)). GEMM은 텐서 코어에서 fp32로 누적하고, 원소별 계산은 fp32로 한 뒤 저장할 때 한 번 반올림합니다. 손실은 fp32로 둡니다. PyTorch의 fp16/bf16 행은 같은 모델과 데이터를 `.to(dtype)`으로 바꿔 전부 그 형식으로 학습합니다.

NVIDIA NGC PyTorch 컨테이너는 `TORCH_ALLOW_TF32_CUBLAS_OVERRIDE=1`을 설정하므로, PyTorch의 fp32 행렬곱은 기본으로 이미 TF32로 돕니다. PyTorch fp32 행은 이 변수를 0으로 설정한 결과입니다.

작은 모델 (128→256→64, 배치 64, 학습 100 스텝, 추론 1000회):

| 프레임워크 | 장치 | 학습 (100 스텝) | 처리량 | 추론 (1000회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (`sgemm`, 12 스레드) | 58.7 ms | 108,990 samples/s | 294.2 ms | 100.333267 |
| SafeC | CPU (OpenBLAS) | 34.9 ms | 183,644 samples/s | 85.0 ms | 100.333267 |
| SafeC | CUDA (H200), plain | 6.76 ms | 946,326 samples/s | 38.8 ms | 100.333282 |
| SafeC | CUDA, fused | 6.22 ms | 1,028,278 samples/s | 34.8 ms | 100.333290 |
| SafeC | CUDA, fused + graph | 3.80 ms | 1,685,985 samples/s | 19.6 ms | 100.333282 |
| SafeC | CUDA, graph + TF32 | 3.79 ms | 1,688,209 samples/s | 19.6 ms | 100.334084 |
| SafeC | CUDA, graph, fp16 | 2.98 ms | 2,150,538 samples/s | **19.6 ms (가장 빠름)** | 100.335869 |
| SafeC | CUDA, graph, bf16 | **2.97 ms (가장 빠름)** | **2,153,432 samples/s** | 19.6 ms | 99.951088 |
| PyTorch | CPU | 23.6 ms | 270,682 samples/s | 44.9 ms | 100.018272 |
| PyTorch | CUDA, TF32 (NGC 기본값) | 53.3 ms | 120,104 samples/s | 56.7 ms | 103.076630 |
| PyTorch | CUDA, fp32 | 51.0 ms | 125,414 samples/s | 55.6 ms | 103.071129 |
| PyTorch | CUDA, fp16 | 48.1 ms | 132,987 samples/s | 52.8 ms | 103.062500 |
| PyTorch | CUDA, bf16 | 47.8 ms | 133,832 samples/s | 54.5 ms | 103.000000 |
| TensorFlow | CPU | 123.6 ms | 51,777 samples/s | 265.3 ms | 106.182999 |

큰 모델 (512→1024→256, 배치 128, 학습 50 스텝, 추론 200회):

| 프레임워크 | 장치 | 학습 (50 스텝) | 처리량 | 추론 (200회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (`sgemm`, 12 스레드) | 340.4 ms | 18,800 samples/s | 403.0 ms | 95.429657 |
| SafeC | CPU (OpenBLAS) | 176.4 ms | 36,285 samples/s | 245.8 ms | 95.424927 |
| SafeC | CUDA (H200), plain | 4.08 ms | 1,569,397 samples/s | 8.23 ms | 95.427338 |
| SafeC | CUDA, fused | 3.79 ms | 1,690,885 samples/s | 7.50 ms | 95.427330 |
| SafeC | CUDA, fused + graph | 3.13 ms | 2,046,036 samples/s | 5.57 ms | 95.427330 |
| SafeC | CUDA, graph + TF32 | 2.31 ms | 2,772,964 samples/s | 5.56 ms | 90.685493 |
| SafeC | CUDA, graph, fp16 | **1.90 ms (가장 빠름)** | **3,375,527 samples/s** | **3.90 ms (가장 빠름)** | 93.444328 |
| SafeC | CUDA, graph, bf16 | 1.90 ms | 3,371,970 samples/s | 3.92 ms | 62.006176 |
| PyTorch | CPU | 61.5 ms | 104,131 samples/s | 54.5 ms | 106.544487 |
| PyTorch | CUDA, TF32 (NGC 기본값) | 17.8 ms | 360,360 samples/s | 11.8 ms | 871.826538 |
| PyTorch | CUDA, fp32 | 17.8 ms | 359,268 samples/s | 12.6 ms | 1754.826172 |
| PyTorch | CUDA, fp16 | 16.9 ms | 378,810 samples/s | 10.5 ms | 1281.000000 |
| PyTorch | CUDA, bf16 | 16.8 ms | 380,839 samples/s | 10.5 ms | 91.500000 |
| TensorFlow | CPU | 227.7 ms | 28,111 samples/s | 238.0 ms | 316.611603 |

- **GPU에서 SafeC 대 PyTorch, fp32끼리**: 학습은 작은 모델에서 13배(3.80 ms 대 51.0 ms), 큰 모델에서 5.7배(3.13 ms 대 17.8 ms) 빠르고, 추론은 2.8배와 2.3배 빠릅니다. 이 크기에서 한 스텝은 마이크로초 단위 커널 몇 개와 손실 값을 기다리는 읽기 한 번이라, 실행 한 번에 드는 비용이 결과를 정합니다. SafeC 한 스텝은 약 38 µs(그래프 실행 한 번과 읽기)이고, PyTorch 한 스텝은 같은 cuBLAS 호출 몇 개를 둘러싼 Python, autograd, 디스패처 작업으로 약 510 µs입니다.
- **fp16과 bf16**: 16비트 저장이 SafeC에서 가장 빠른 모드입니다. TF32 그래프보다 학습이 작은 모델에서 22%, 큰 모델에서 18% 빠르고, 큰 모델은 추론도 30% 빠릅니다(3.90 ms 대 5.56 ms). 작은 모델의 추론은 실행 비용이 지배해 변하지 않습니다. 같은 형식의 PyTorch와 비교하면 학습이 16배(작은 모델), 8.9배(큰 모델) 빠르고, 추론은 둘 다 2.7배 빠릅니다. fp16과 bf16의 시간은 같습니다. PyTorch는 스텝이 호스트 오버헤드에 묶여 있어 16비트로 얻는 이득이 6–10%뿐입니다. TF32처럼 이 루프는 반올림 차이를 키워서, 큰 모델의 50 스텝 후 손실이 fp32의 95.43 대신 fp16에서 93.44, bf16에서 62.01입니다. PyTorch도 비슷하게 흩어집니다(fp32 1754.8, fp16 1281.0, bf16 91.5; PyTorch는 자체 난수 데이터를 씁니다).
- **SafeC의 시간 분해**: 융합으로 실행 10번 중 3번이 빠지면서 8% 줄어듭니다. 그래프는 실행 비용이 지배적인 작은 모델에서 39%, 큰 모델에서 17%를 더 줄입니다. TF32는 계산량이 병목이 아닌 작은 모델에서는 차이가 없고, 큰 모델의 학습을 26% 줄입니다. TF32를 쓰면 큰 모델의 50 스텝 후 손실이 95.43 대신 90.69가 됩니다. 이 루프가 반올림 차이를 키우기 때문이며, CPU, fp32 GPU, ROCm 실행은 모두 4자리까지 일치합니다.
- **이 백엔드 이전**: v1.0.0-beta-5에서는 `tensor_matmul_cuda_blas`가 호출마다 CUDA 컨텍스트와 cuBLAS 핸들을 만들고 없앴습니다. 이 GPU에서 그 경로로 작은 벤치마크를 돌리면 학습에 83초, 추론에 816초가 걸렸습니다. fused + graph 행보다 20,000배 넘게 느립니다.
- **CPU**: SafeC 자체 행은 이제 [`sgemm`](/stdlib/simd#gemm)을 [스레드 풀](/stdlib/sync#parallel)에서 돌리는 `tensor_matmul`을 씁니다. beta-5의 스칼라 루프는 여기서 학습에 144.0 ms와 2323.4 ms가 걸렸으므로, 작은 모델에서 2.5배, 큰 모델에서 6.8배 빨라졌습니다. 실행 시점에 AVX-512를 고르는 OpenBLAS는 SSE2로 빌드한 `sgemm`보다 여전히 1.7–1.9배 빠르고, PyTorch의 CPU 커널은 OpenBLAS보다 1.5배(작은 모델), 2.9배(큰 모델) 빠릅니다.

### H200에서의 합성곱과 FFT {#h200-conv-fft}

SafeC의 CPU 코드(`sgemm`과 스레드 풀 위의 `cnn.h`, `unet.h`, `fft.h`), GPU 라이브러리 [`gpu_cudnn.h`와 `fft_cuda.h`](/ko/stdlib/ml#cuda-cudnn-cufft)(cuDNN 9.15, CUDA 13.0의 cuFFT), 그리고 두 장치의 PyTorch를 비교합니다. 텐서는 호출 사이에 GPU에 머뭅니다. SafeC CUDA와 PyTorch-CUDA 행은 동기화 두 번 사이에 쌓은 300–2000번 호출의 평균입니다. CPU 행은 측정하지 않는 예열 호출 한 번 뒤 몇 번 호출한 평균입니다. 모든 수치는 학습 작업을 멈춘 상태에서 3회 중 최고값입니다.

SafeC는 합성곱 모양마다 첫 호출에서 계획을 만듭니다. 합성곱 + bias(+ ReLU)를 융합한 그래프에 대해 cuDNN graph API가 제안하는 엔진 구성들과 레거시 API의 최선 알고리즘의 시간을 재서, 가장 빠른 것을 씁니다. PyTorch 행도 후보의 시간을 재며(`cudnn.benchmark = True`), PyTorch 기본 휴리스틱으로는 시간이 1.5% 안에서 같았습니다. "fp32"는 PyTorch에서 `cudnn.allow_tf32 = False`로 두고 SafeC에서 `cuda_set_tf32`를 끈 것이고, "TF32"는 둘 다 켠 것입니다. PyTorch의 합성곱 기본값은 TF32입니다.

합성곱, 호출당 ms(낮을수록 좋음). `conv64`는 Conv2D 층 하나(64→64 채널, 3×3, stride 1, padding 1, 56×56 맵, bias 포함)이고, `unet128`은 128×128에서 `unet_new(3, 32, 64, 3)`의 순전파(합성곱 다섯 번, max pool 두 번, upsample + concat 두 번)입니다.

| 프레임워크 | 장치 | conv64, 1장 | conv64, 32장 | unet128, 1장 | unet128, 16장 |
|---|---|---|---|---|---|
| SafeC | CPU (im2col + `sgemm`, 12 스레드) | 1.53 | — | 6.50 | — |
| PyTorch | CPU (12 스레드) | 0.346 | — | 2.93 | — |
| SafeC | CUDA, fp32 | **0.0181** | **0.165** | 0.156 | **0.534** |
| SafeC | CUDA, fp32, U-Net을 그래프 하나로 | | | **0.144** | |
| PyTorch | CUDA, fp32 | 0.0224 | 0.257 | 0.206 | 0.874 |
| SafeC | CUDA, TF32 | **0.0182** | **0.0835** | 0.104 | **0.455** |
| SafeC | CUDA, TF32, U-Net을 그래프 하나로 | | | **0.0873** | |
| PyTorch | CUDA, TF32 (기본값) | 0.0381 | 0.0967 | 0.271 | 0.553 |

FFT, 호출당 ms. `fft1d`와 `fft2d`는 호스트 메모리에서 시작해 호스트 메모리로 끝나는 복소수 `double` 배열이라, GPU 호출마다 업로드와 읽기가 포함됩니다(`cuda_fft`/`cuda_fft2d`; `.to("cuda")`, `torch.fft`, `.cpu()`). `batch`와 `rfft`는 이미 GPU에 있는 데이터로 돕니다(`cuda_fft_c2c`/`cuda_fft_r2c`; complex64와 float32). 이 둘의 SafeC CPU 행은 `double` 데이터를 변환합니다.

| 프레임워크 | 장치 | fft1d, 2²⁰ 점 | fft2d, 1024×1024 | batch, 1024 × 4096 | rfft, 1024 × 4096 |
|---|---|---|---|---|---|
| SafeC | CPU (radix-2, 12 스레드) | 2.68 | 2.76 | 7.05 | 5.20 |
| PyTorch | CPU (12 스레드) | 1.19 | **0.671** | 3.31 | 0.288 |
| SafeC | CUDA | **1.25** | 1.25 | 0.0245 | **0.0108** |
| PyTorch | CUDA | 2.08 | 2.07 | **0.0240** | 0.0117 |

- **GPU에서의 합성곱**: 이제 모든 행에서 SafeC가 PyTorch보다 빠릅니다. 이미지 한 장의 합성곱은 0.0181 ms 대 0.0224 ms입니다(1.24배, PyTorch 기본값인 TF32와 비교하면 2.1배). 32장 배치는 fp32에서 1.56배, TF32에서 1.16배 빠릅니다. 배치 16의 U-Net은 fp32에서 1.64배, TF32에서 1.22배 빠르고, 배치 1을 TF32로 그래프 하나로 돌리면 3.1배 빠릅니다(0.087 ms 대 0.271 ms).
- **무엇이 바뀌었나**: [이전 측정](#h200-conv-fft-before)의 세 가지 격차를 Nsight Systems로 프로파일링해 해소했습니다.
  - *이미지 한 장의 합성곱*(이전 0.0335 ms 대 PyTorch 0.0224 ms): 두 쪽 모두 같은 cuDNN 커널을 썼고, 차이는 호스트 시간이었습니다. cuDNN 9의 레거시 `cudnnConvolutionForward`는 호출마다 엔진을 다시 정하느라 CPU를 약 38 µs 쓰는데, 이는 커널 시간보다 길고, bias는 두 번째 호출로 더했습니다. 이제 SafeC는 cuDNN graph API로 합성곱 → + bias(→ ReLU)를 융합한 그래프의 계획을 만들고 variant pack을 보관합니다. 호출 한 번에 호스트 시간 약 6 µs, 커널 하나입니다.
  - *배치 16–32의 TF32*(이전 10–20% 뒤짐): graph API는 레거시 알고리즘 목록에 없는 엔진, 특히 PyTorch가 쓰던 Hopper(`sm90`) 커널에 닿습니다. 또 U-Net은 skip 연결 두 개를 이미지마다 memcpy 한 번으로 복사해서 배치 16에서는 순전파마다 64번 복사했습니다. 이제 concat마다 strided 복사 커널(`cuda_copy_rows`)을 두 번 실행합니다.
  - *호스트 배열 FFT*(이전 2.71 ms 대 2.05 ms): 드라이버는 pageable 메모리를 자체 pinned 버퍼를 거쳐 스레드 하나로 복사하며, 이 호스트에서 16 MB에 약 1.05 ms가 걸립니다. 이제 `cuda_write`/`cuda_read`는 1 MiB 이상의 복사를 레인 네 개(호출 스레드와 보조 스레드 세 개)로 나눕니다. 레인마다 자기 몫을 자기 스트림에서 pinned 버퍼를 거쳐 옮기며, 16 MB를 0.45–0.7 ms에 옮깁니다. FFT 왕복 전체가 2.2배 빨라졌고, 변환 자체는 그중 약 41 µs입니다.
- **CPU 합성곱**: 12 스레드의 im2col + `sgemm`은 beta-5의 직접 루프보다 `conv64`에서 111배(169.8 ms), U-Net에서 71배(459.1 ms) 빠릅니다. PyTorch의 CPU 경로(oneDNN, AVX-512)는 여전히 `conv64`에서 4.4배, U-Net에서 2.2배 빠릅니다. 배치 1에서 cuDNN은 SafeC CPU 경로보다 85배, U-Net에서 42배 빠릅니다.
- **GPU에서의 FFT**: 데이터가 GPU에 있으면 SafeC와 PyTorch 모두 cuFFT를 부르고 시간이 10% 안에서 같습니다. 호스트 배열은 PCIe와 호스트 쪽 복사가 지배합니다. SafeC의 pinned 레인 덕분에 왕복이 PyTorch의 `.to("cuda")` … `.cpu()`보다 1.66배 빠릅니다.
- **CPU에서의 FFT**: `fft.h`의 twiddle 표, `f64x2` butterfly, 스레드 풀 덕분에 2²⁰점 변환이 여기서 beta-5(198.1 ms)보다 74배 빨라졌습니다. 왕복을 포함한 `cuda_fft`가 이제 CPU 변환보다 2.1배 빠릅니다. PyTorch CPU FFT는 `fft1d`에서 2.2배, `batch`에서 2.1배, `fft2d`에서 4.1배 빠릅니다. `rfft`에서 PyTorch는 float32로 계산하고 중복이 없는 n/2 + 1개 bin만 돌려주는 반면, `fft_real_batch`는 n개 bin 전부를 `double`로 씁니다.

<a id="h200-conv-fft-before"></a>`main`(449420a)으로 한 이전 측정, ms: conv64 0.0335 / 0.163 (fp32), 0.0335 / 0.106 (TF32); unet128 0.175 / 0.731 (fp32), 0.176 / 0.663 (TF32), 그래프로 0.164 / 0.123; fft1d / fft2d 2.71 / 2.71.

### Xeon 8570에서의 기본 벤치마크 {#xeon-8570-classic}

릴리스 빌드 실행 시간, 3회 중 최고값(낮을수록 좋음, 가장 빠른 값 굵게). 웹 서비스는 초당 요청 수입니다(높을수록 좋음). 멀티스레드와 웹 벤치마크의 Zig 소스는 Zig 0.16용으로 작성되어, `**` 배열 반복 문법을 바꾸고 `@cImport`를 없앤 0.17.0에서는 빌드되지 않습니다.

| 벤치마크 | SafeC | C | C++ | Rust | Zig | Go | Python |
|---|---|---|---|---|---|---|---|
| fib(37) | 0.091s | 0.088s | 0.089s | 0.092s | **0.072s** | 0.131s | 2.432s |
| n-body | **0.144s** | 0.146s | 0.148s | 0.155s | 0.156s | 0.191s | 9.314s |
| binary-trees (SafeC: arena) | **0.279s** | 1.154s | 1.372s | 1.412s | 1.262s | 3.205s | 29.257s |
| binary-trees, SafeC 힙 | 1.141s | | | | | | |
| binary-trees, 8 스레드 (SafeC: arena) | **0.099s** | 0.414s | 0.431s | 0.461s | N/A | 0.419s | 74.976s |
| binary-trees, 8 스레드, SafeC 힙 | 0.398s | | | | | | |
| SIMD, 명시적 벡터 | 0.108s | 0.116s | 0.112s | 0.114s | **0.091s** | — | 0.337s (NumPy) |
| 웹 서비스 (req/s) | **33,902** | 32,001 | 32,210 | 32,747 | N/A | 32,370 | 2,879 |

컬렉션 (1,000,000개 요소, ms): `Vec` push 3.22 대 C 1.83, `vec_sort` **75.0** 대 `qsort` 148.6, `HashMap` insert 60.0 대 53.2, get 20.1 대 18.6, `List` push+pop **17.8** 대 27.6, `Queue` **4.29** 대 6.81, `BST` insert 367.0 대 **316.6**, get 349.6 대 **336.1**. 할당자: 번갈아 할당·해제하는 쌍 하나에 `std::alloc`/`dealloc` 10.03 ns 대 `malloc`/`free` **8.49** ns, 일괄 패턴은 159 ms 대 **119** ms. 순위는 7800X3D와 같지만, 번갈아 할당하는 패턴만은 이 장비에서 glibc `malloc`이 앞섭니다. SafeC의 arena binary-trees(10% 빨라짐, 0.310 → 0.279 s)를 빼면 모든 행이 이 장비의 이전 측정과 9% 안에서 같습니다.

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
