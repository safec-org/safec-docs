# 벤치마크

SafeC가 몇 가지 대표적인 마이크로벤치마크에서 실행 시간과 최대 메모리
사용량 면에서 C, C++, Rust, Zig, Go, Python과 어떻게 비교되는지
살펴봅니다 — [The Computer Language Benchmarks
Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html)과
[programming-language-benchmarks.vercel.app](https://programming-language-benchmarks.vercel.app)의
정신을 따르되, 하나의 CI 규모 머신에 맞도록 축소했습니다. 각 벤치마크에서
**가장 빠른** 셀과 **가장 가벼운** 셀은 굵게 표시했습니다.

::: warning 아래 수치를 보기 전에 읽어 주세요
이는 **단일 머신, 단일 세션, 3회 중 최선(best-of-3)** 측정이며,
Benchmarks Game의 훨씬 더 엄격한 다중 실행 통계적 방법론이 아닙니다.
여기의 모든 숫자는 언어에 대한 보편적인 진실이 아니라 "Apple M1 Pro 한
대에서 대략 이 정도"로 받아들이세요. 세 가지 벤치마크로는 언어의
전반적인 성능을 특징짓기에 부족합니다 — 이들은 *이 세 가지 워크로드*를,
*이 머신*에서, *이 컴파일러 버전들*로, *이 날짜*에 특징지을 뿐입니다.
이 숫자들이 정말로 유용한 부분은: SafeC의 실제 현재 트레이드오프가
어디에 있는지 보여준다는 점입니다 — 이 페이지가 만들어지기 전까지는
SafeC 자체 문서조차 몰랐던 것(멀티스레딩 섹션 참고)까지 포함해서요.
:::

## 방법론 {#methodology}

- **머신**: Apple M1 Pro, 코어 10개, RAM 32GB, macOS 26.5.1.
- **툴체인**: `safec` 1.0.0(이 저장소의 빌드) · Apple Clang 21.0.0
  (C/C++) · Go 1.26.5 · Zig 0.16.0 · Rust 1.97.1 · Python 3.14.6
  (SIMD 한 행에 대해서는 + NumPy 2.5.1).
- **빌드 플래그** — 디버그: `-O0`(C/C++), 최적화하지 않은 IR(SafeC),
  `go build -gcflags="all=-N -l"`, Zig의 기본 Debug 모드, 순수
  `rustc`. 릴리스: `-O2`(C/C++, 그리고 SafeC의 `clang -O2` 백엔드
  단계 — 손으로 고른 "최선의" 플래그가 아니라 `safeguard build
  --release`가 실제로 하는 것과 일치합니다), 순수 `go build`, `zig
  build-exe -O ReleaseFast`, `rustc -O`. Python은 디버그/릴리스
  구분이 없습니다 — 참고용으로 인터프리트된 숫자 하나를 두 열
  모두에 표시했습니다.
- **타이밍**: `/usr/bin/time -l`, 3회 실행 중 최선(가장 낮은)
  실행 시간. **메모리**: 같은 3회 실행의 "최대 상주 세트 크기(maximum
  resident set size)" 중 최댓값.
- **정확성**: 결과에 포함되기 전에 모든 바이너리의 출력을 해당
  워크로드의 알려진 정답과 비교해 검증했습니다 — 잘못된 답을 낸
  벤치마크는 실제 결과가 아니라 하니스의 버그일 것이므로, 그런 것은
  전혀 표시되지 않습니다.
- 사용된 모든 소스 파일은 결과 옆에 인라인으로 링크되어 있을 뿐
  파일 자체가 임베드되어 있지는 않습니다 — 원본 파일을 보려면
  클릭해서 들어가세요.

## 싱글 스레드 {#single-threaded}

### fib(37) — 재귀 피보나치 {#fib37-recursive-fibonacci}

단순 재귀 피보나치 — 순수한 함수 호출 및 정수 산술 오버헤드만
있으며, 할당도 I/O도 없습니다.

#### 디버그 빌드

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.14s | **1.3 MB (가장 가벼움)** | 0.11s |
| C | 0.13s | 1.3 MB | 0.11s |
| C++ | 0.13s | **1.3 MB (가장 가벼움)** | 0.11s |
| Rust | 0.18s | 1.5 MB | 0.15s |
| Zig | 0.16s | 1.7 MB | 1.38s |
| Go | **0.11s (가장 빠름)** | 4.1 MB | 0.09s |
| Python | 2.70s | 14.5 MB | N/A (인터프리트) |

#### 릴리스 빌드

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.08s | **1.3 MB (가장 가벼움)** | 0.13s |
| C | **0.07s (가장 빠름)** | **1.3 MB (가장 가벼움)** | 0.09s |
| C++ | **0.07s (가장 빠름)** | **1.3 MB (가장 가벼움)** | 0.09s |
| Rust | 0.09s | 1.5 MB | 0.18s |
| Zig | 0.08s | 1.4 MB | 5.60s |
| Go | 0.08s | 4.0 MB | 0.08s |
| Python | 2.70s | 14.5 MB | N/A (인터프리트) |

**출처:**

**SafeC**: [fib.sc](/benchmarks/fib/safec/fib.sc)

**C**: [fib.c](/benchmarks/fib/c/fib.c)

**C++**: [fib.cpp](/benchmarks/fib/cpp/fib.cpp)

**Rust**: [fib.rs](/benchmarks/fib/rust/fib.rs)

**Zig**: [fib.zig](/benchmarks/fib/zig/fib.zig)

**Go**: [fib.go](/benchmarks/fib/go/fib.go)

**Python**: [fib.py](/benchmarks/fib/python/fib.py)

### n-body — 5체 궤도 시뮬레이션 {#n-body-5-body-orbital-simulation}

전형적인 Benchmarks Game n-body 테스트(태양/목성/토성/천왕성/해왕성),
2,000,000회의 시뮬레이션 스텝. 부동소수점 연산 위주이며, 할당은
없고 작업 세트도 아주 작습니다.

#### 디버그 빌드

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.63s | **1.3 MB (가장 가벼움)** | 0.11s |
| C | 0.42s | **1.3 MB (가장 가벼움)** | 0.11s |
| C++ | 0.41s | **1.3 MB (가장 가벼움)** | 0.13s |
| Rust | 0.70s | 1.5 MB | 0.13s |
| Zig | 0.56s | 1.8 MB | 1.44s |
| Go | **0.28s (가장 빠름)** | 4.1 MB | 0.09s |
| Python | 9.89s | 15.2 MB | N/A (인터프리트) |

#### 릴리스 빌드

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | 0.11s | **1.3 MB (가장 가벼움)** | 0.13s |
| C | **0.10s (가장 빠름)** | 1.3 MB | 0.10s |
| C++ | 0.11s | **1.3 MB (가장 가벼움)** | 0.12s |
| Rust | 0.11s | 1.5 MB | 0.16s |
| Zig | 0.11s | 1.5 MB | 5.69s |
| Go | **0.10s (가장 빠름)** | 4.1 MB | 0.09s |
| Python | 9.89s | 15.2 MB | N/A (인터프리트) |

**출처:**

**SafeC**: [nbody.sc](/benchmarks/nbody/safec/nbody.sc)

**C**: [nbody.c](/benchmarks/nbody/c/nbody.c)

**C++**: [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp)

**Rust**: [nbody.rs](/benchmarks/nbody/rust/nbody.rs)

**Zig**: [nbody.zig](/benchmarks/nbody/zig/nbody.zig)

**Go**: [nbody.go](/benchmarks/nbody/go/nbody.go)

**Python**: [nbody.py](/benchmarks/nbody/python/nbody.py)

### binary-trees — 할당/해제 스트레스 테스트 {#binary-trees-allocationdeallocation-stress}

수백만 개의 작은 이진 트리를 만들고 버립니다(최대 깊이 18) — 산술
연산이 아니라 각 언어의 메모리 관리 전략을 실제로 시험하는 벤치마크이며,
결과가 흥미로워지는 지점이기도 합니다.

#### 디버그 빌드

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | **0.29s (가장 빠름)** | 25.4 MB | 0.12s |
| C | 1.67s | **17.4 MB (가장 가벼움)** | 0.11s |
| C++ | 1.87s | **17.4 MB (가장 가벼움)** | 0.10s |
| Rust | 3.16s | 17.6 MB | 0.14s |
| Zig | 4.85s | 17.9 MB | 1.35s |
| Go | 2.52s | 38.6 MB | 0.12s |
| Python | 20.93s | 87.0 MB | N/A (인터프리트) |

#### 릴리스 빌드

| Language | Run time | Peak memory | Compile time |
|---|---|---|---|
| SafeC | **0.21s (가장 빠름)** | 25.4 MB | 0.14s |
| C | 1.56s | **17.4 MB (가장 가벼움)** | 0.10s |
| C++ | 1.86s | **17.4 MB (가장 가벼움)** | 0.10s |
| Rust | 1.74s | 17.6 MB | 0.16s |
| Zig | 1.57s | 17.6 MB | 5.28s |
| Go | 1.26s | 40.8 MB | 0.09s |
| Python | 20.93s | 87.0 MB | N/A (인터프리트) |

위의 SafeC 행은 `std::alloc`/`heap` 대신 `region`/`arena<R>`(컴파일
타임에 리전으로 범위가 지정된 라이프타임 안전성, 범프 포인터 할당,
`arena_reset<R>()`가 리전 전체를 O(1)에 버림)를 사용합니다 — 이
워크로드는 전부 짧게 살고 같은 스코프 안에서 끝나는 할당이며,
정확히 리전이 존재하는 이유에 부합합니다. 순수 힙 버전과 비교하면:
1.15s → 0.21s 릴리스(~5.5배), 33.6 MB → 26.0 MB 최대 메모리(~23%
감소).

**출처:**

**SafeC**: [binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc)

**C**: [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c)

**C++**: [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp)

**Rust**: [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs)

**Zig**: [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig)

**Go**: [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go)

**Python**: [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)

## 컬렉션 — std::collections 처리량 (1,000,000개 원소) {#collections-stdcollections-throughput-1000000-elements}

| Operation | Throughput |
|---|---|
| `bst_insert` | 1,577,110/sec |
| `list_push_back` | 65,427,899/sec |
| `map_insert` | 4,365,783/sec |
| `map_get` | 8,468,404/sec |

[bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc)

## 멀티스레드 — binary-trees, 8스레드 {#multithreaded-binary-trees-8-threads}

동일한 binary-trees 워크로드를, 8개의 워커 스레드로 병렬화했습니다
(이 머신은 코어가 10개입니다) — 각 스레드는 주어진 깊이에서 트리
개수의 독립적인 조각을 만들고 체크섬을 계산하며, 다음 깊이로
넘어가기 전에 조인됩니다. 릴리스 빌드만 대상으로 합니다. SafeC:
스레드당 하나씩의 `region`/`arena<R>`(아레나 상태는 공유되거나
잠기지 않으므로, 각 스레드마다 자기 것이 필요합니다) — 순수 힙
버전과 비교하면: 0.68s → 0.09s(~7.5배).

| Language | 8-thread time | Peak memory | vs. single-thread |
|---|---|---|---|
| SafeC | **0.09s (가장 빠름)** | 89.7 MB | 2.33× |
| C | 0.61s | 72.3 MB | 2.56× |
| C++ | 0.63s | 72.5 MB | 2.95× |
| Rust | 0.68s | **72.2 MB (가장 가벼움)** | 2.56× |
| Zig | 0.54s | 74.1 MB | 2.91× |
| Go | 0.44s | 144.2 MB | 2.86× |
| Python | 24.43s | 372.0 MB | 0.86× (1스레드보다 느림) |

**출처:**

**SafeC**: [binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc)

**C**: [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c)

**C++**: [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp)

**Rust**: [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs)

**Zig**: [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig)

**Go**: [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go)

**Python**: [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)

## SIMD — 2천만 개 f64 값의 제곱합 {#simd-sum-of-squares-over-20000000-f64-values}

큰 배열에 대한 리덕션(`sum(a[i]*a[i])`) 연산으로, 단순 스칼라
루프와 각 언어의 명시적 벡터 타입을 같은 최적화 수준(`-O2`/릴리스)에서
비교합니다 — 그래서 이는 백엔드의 일반적인 자동 벡터화기가 스칼라
루프에 대해 이미 하고 있는 것 *위에* 명시적인 SIMD를 작성함으로써
무엇을 더 얻을 수 있는지를 분리해서 보여주는 것이지, "벡터화 대
일부러 저해된 코드"를 비교하는 것이 아닙니다. SafeC는 자체
`vec<double,4>` 타입을 사용합니다. C/C++는 GCC/Clang 벡터
확장을 사용합니다. Zig는 `@Vector`를 사용합니다. Rust는
안정 채널(stable channel)의 AArch64 NEON 인트린식을 사용합니다
(`std::simd`/`portable_simd`는 Rust nightly가 필요하므로 여기서는
사용하지 않았습니다). Go는 표준 툴체인에 이식 가능한 SIMD 타입이
없으므로, 스칼라 숫자만 존재합니다. Python의 단순 루프는 비교
기준으로 포함되었고, NumPy도 함께 포함했습니다 — 실제로 누군가
Python에서 벡터화된 수치 연산 성능을 얻는 현실적인 방법이기
때문입니다.

| Language | Scalar time | Explicit-SIMD time | Speedup |
|---|---|---|---|
| SafeC | 0.04s | **0.03s (가장 빠름)** | 1.33× |
| C | 0.04s | **0.03s (가장 빠름)** | 1.33× |
| C++ | 0.04s | **0.03s (가장 빠름)** | 1.33× |
| Rust | 0.04s | **0.03s (가장 빠름)** | 1.33× |
| Zig | 0.04s | **0.03s (가장 빠름)** | 1.33× |
| Go | 0.06s | N/A | — |
| Python | 3.50s | N/A | — |
| Python + NumPy | — | 0.14s | 25.00× |

**출처:**

**SafeC**: [simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc)

**C**: [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c)

**C++**: [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp)

**Rust**: [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs)

**Zig**: [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig)

**Go**: [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go)

**Python**: [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)

## 웹 서비스 — JSON "hello world" 엔드포인트 {#web-service-json-hello-world-endpoint}

`GET /`가 `{"message":"Hello, World!"}`를 반환합니다 — TechEmpower의
"JSON serialization" 테스트와 같은 최소 형태이며, Apache Bench
(`ab -n 20000 -c 50`, keep-alive 없이, 연결 재사용 지원 여부와
무관하게 모든 서버를 동일한 기준으로 측정)를 사용해 각 언어 고유의
HTTP 서빙 방식으로 실행했습니다: SafeC의 `std::http_serve_threaded`
(네이티브, 이 저장소의 일부), C/C++/Zig에는 최소한의 raw 소켓 accept
루프(셋 다 다른 둘처럼 지배적인 "웹 프레임워크"가 없기 때문입니다),
Go의 `net/http`, Python의 FastAPI + uvicorn, 그리고 Rust는
Dioxus가 아니라 **axum**입니다 — Dioxus의 풀스택 서버 레이어는
내부적으로 axum *자체*이며(자체 서버 함수 RPC가 axum 앱 위에
얹혀 있습니다), axum을 직접 벤치마킹하는 것이 곧 Dioxus 자체의
요청이 실제로 실행되는 경로를 측정하는 것입니다. 순수한 처리량
테스트에는 쓸모가 없는, Dioxus의 클라이언트 렌더링 방식이 요구하는
별도의 `dx`/WASM 번들링 툴체인 없이도 말입니다.

| Language | Req/sec | p50 latency | p99 latency | Peak memory |
|---|---|---|---|---|
| SafeC | **28305 (가장 빠름)** | 2ms | 3ms | 7.2 MB |
| C | 25303 | 2ms | 2ms | **1.4 MB (가장 가벼움)** |
| C++ | 24148 | 2ms | 4ms | 1.6 MB |
| Rust | 27270 | 2ms | 2ms | 3.4 MB |
| Zig | 25742 | 2ms | 2ms | 1.5 MB |
| Go | 25274 | 2ms | 3ms | 19.7 MB |
| Python | 4790 | 10ms | 33ms | 54.9 MB |

[io_nb_bsd.sc](/benchmarks/stdlib/io_nb_bsd.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h)

**출처:**

**SafeC**: [server.sc](/benchmarks/webservice/safec/server.sc)

**C**: [server.c](/benchmarks/webservice/c/server.c)

**C++**: [server.cpp](/benchmarks/webservice/cpp/server.cpp)

**Rust**: [main.rs](/benchmarks/webservice/rust/src/main.rs) · [private.rs](/benchmarks/webservice/rust/target/release/build/serde-27ec540374108a89/out/private.rs) · [private.rs](/benchmarks/webservice/rust/target/release/build/serde_core-b2f4be27c80b0f48/out/private.rs)

**Zig**: [server.zig](/benchmarks/webservice/zig/server.zig)

**Go**: [server.go](/benchmarks/webservice/go/server.go)

**Python**: [server.py](/benchmarks/webservice/python/server.py)

## 머신러닝 — 소형 MLP, 학습과 추론 {#machine-learning-small-mlp-training-and-inference}

2계층 MLP(`relu(X @ W1) @ W2`, 128→256→64, 배치 64, MSE 손실,
직접 구현한 SGD, 편향 없음) — 학습 스텝 100회 후 추론 전용 패스
1000회, 고정 시드. 여러 프레임워크에서 동일한 연산 그래프를
비교합니다. `std::ml`은 CPU(Accelerate BLAS)와 MPS를 다룹니다(이
크기에서는 어느 프레임워크에서도 — SafeC 포함 — GPU가 CPU BLAS를
이기기에는 너무 작으므로, MPS 행은 경쟁을 위해서가 아니라 완전성을
위해 포함했습니다).

| Framework | Device | Train (100 steps) | Throughput | Inference (1000 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC (Accelerate BLAS) | CPU | **16.8ms (가장 빠름)** | 380499 samples/s | 26.1ms | 100.333420 |
| SafeC | MPS | 39.4ms | 162581 samples/s | 61.1ms | 100.333290 |
| PyTorch | CPU | 23.1ms | 277232 samples/s | **13.5ms (가장 빠름)** | 100.018272 |
| PyTorch | MPS | 131.6ms | 48649 samples/s | 358.7ms | 103.423889 |
| TensorFlow | CPU | 83.0ms | 77103 samples/s | 245.0ms | 106.182991 |
| TensorFlow | GPU | 245.3ms | 26086 samples/s | 924.6ms | 106.182999 |
| MLX | GPU | 72.2ms | 88685 samples/s | 309.1ms | 96.632431 |

[tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [tensor_gpu.h](/benchmarks/stdlib/tensor_gpu.h) · [tensor_gpu.sc](/benchmarks/stdlib/tensor_gpu.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [gen_mps_metallib.sh](/benchmarks/stdlib/gen_mps_metallib.sh) · [time.sc](/benchmarks/stdlib/time.sc) · [time.h](/benchmarks/stdlib/time.h)

**출처:**

**SafeC**: [train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc)

**PyTorch**: [train.py](/benchmarks/ml/pytorch/train.py)

**TensorFlow**: [train.py](/benchmarks/ml/tensorflow/train.py)

**MLX**: [train.py](/benchmarks/ml/mlx/train.py)

## 머신러닝, 더 큰 모델 — SafeC vs PyTorch, TensorFlow, MLX {#machine-learning-bigger-model-safec-vs-pytorch-tensorflow-mlx}

같은 형태를 512→1024→256, 배치 128로 확장했습니다(곱셈-덧셈/행렬곱
연산량이 약 50배). 학습 스텝 50회, 추론 패스 200회. SafeC:
Accelerate-BLAS CPU 경로와 MPS GPU 둘 다, 이 크기에서 처음으로
나머지 세 프레임워크와 비교했습니다.

| Framework | Device | Train (50 steps) | Throughput | Inference (200 passes) | Loss (sanity check) |
|---|---|---|---|---|---|
| SafeC (Accelerate BLAS) | CPU | 89.2ms | 71713 samples/s | 61.4ms | 95.428856 |
| SafeC | MPS | **47.9ms (가장 빠름)** | 133433 samples/s | **47.3ms (가장 빠름)** | 95.428223 |
| PyTorch | CPU | 67.8ms | 94440 samples/s | 48.2ms | 106.548233 |
| PyTorch | MPS | 110.5ms | 57926 samples/s | 86.4ms | 108.710899 |
| TensorFlow | CPU | 148.8ms | 43003 samples/s | 197.6ms | 316.556885 |
| TensorFlow | GPU | 154.1ms | 41539 samples/s | 218.3ms | 317.200012 |
| MLX | GPU | 54.3ms | 117953 samples/s | 74.4ms | 88.762100 |

[gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [tensor_gpu.h](/benchmarks/stdlib/tensor_gpu.h) · [tensor_gpu.sc](/benchmarks/stdlib/tensor_gpu.sc)

**출처:**

**SafeC**: [train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_cpu_blas.sc](/benchmarks/ml_big/safec/train_cpu_blas.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc)

**PyTorch**: [train.py](/benchmarks/ml_big/pytorch/train.py)

**TensorFlow**: [train.py](/benchmarks/ml_big/tensorflow/train.py)

**MLX**: [train.py](/benchmarks/ml_big/mlx/train.py)

## 머신러닝, GPU 백엔드 — CUDA, ROCm, Vulkan/SPIR-V, WebGPU {#machine-learning-gpu-backends-cuda-rocm-vulkanspir-v-webgpu}

이 머신(Apple M1 Pro, 아래 방법론 참고)에는 NVIDIA/AMD GPU도,
CUDA/ROCm 툴킷도, Vulkan SDK/드라이버도, WebGPU 네이티브 라이브러리도
없습니다 — 그래서 위의 MPS 수치와 달리, **이 섹션에서는 아무것도
측정되지 않았습니다**. 모든 함수는 실제 벤더 C ABI에 맞춰 손으로
작성되었고 `safec`에서 타입 검사를 통과하지만, 이 환경에서는
링크/실행이 불가능합니다 — 실제 하드웨어에서 검증하기 전까지는
"확인됨"이 아니라 "맞을 것으로 예상됨" 정도로 여겨 주세요. 각
백엔드는 서로 다른 벽에 부딪힙니다.

| Backend | Elementwise ops | Matmul (naive kernel) | Matmul (vendor BLAS) | Gap |
|---|---|---|---|---|
| CUDA | real (PTX, text IR) | real (PTX) | real (cuBLAS) | 없음 — 그저 여기에 NVIDIA GPU가 없을 뿐 |
| ROCm | 항상 0 반환 | 항상 0 반환 | real (rocBLAS) | HSACO는 *바이너리* IR입니다. 여기에는 커널 이미지를 컴파일할 ROCm 툴체인이 없습니다 |
| Vulkan/SPIR-V | 항상 0 반환 | 항상 0 반환 | n/a | SPIR-V는 *바이너리* IR입니다. 여기에는 그것을 컴파일할 glslc/glslangValidator가 없습니다 |
| WebGPU | real (WGSL, text IR) | real (WGSL) | n/a | 없음 — 그저 여기에 wgpu-native/Dawn 라이브러리가 없을 뿐 |

[gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_spirv.h](/benchmarks/stdlib/gpu_spirv.h) · [gpu_spirv.sc](/benchmarks/stdlib/gpu_spirv.sc) · [gpu_webgpu.h](/benchmarks/stdlib/gpu_webgpu.h) · [gpu_webgpu.sc](/benchmarks/stdlib/gpu_webgpu.sc) · [tensor_cuda.h](/benchmarks/stdlib/tensor_cuda.h) · [tensor_cuda.sc](/benchmarks/stdlib/tensor_cuda.sc) · [tensor_rocm.h](/benchmarks/stdlib/tensor_rocm.h) · [tensor_rocm.sc](/benchmarks/stdlib/tensor_rocm.sc)

## 머신러닝, fp16 / bf16 지원 {#machine-learning-fp16-bf16-support}

SafeC의 타입 시스템에는 네이티브 16비트 부동소수점이 없습니다
(`Type.h`의 `TypeKind`에는 `Float32`/`Float64`만 있습니다 — 하나
추가하는 것은 표준 라이브러리 변경이 아니라 진짜 컴파일러 기능이
필요한 일입니다: 렉서, 파서, sema의 산술 승격 규칙, LLVM 코드
생성까지). 여기 대신 있는 것은 컴파일러 네이티브 half 타입이 없을
때/없이 시스템 프로그래밍에서 쓰는 표준적인 접근법입니다: fp16과
bf16 값은 저장을 위해 `unsigned short`에 원시 비트로 담기고,
`float`로/부터의 명시적이고 정확하게 반올림되는(round-to-nearest-even)
변환이 함께 제공됩니다 — 그리고 MPS 백엔드에서는 단순히 저장 공간을
반으로 줄인 것이 아니라 실제 네이티브 `half`/`bfloat` GPU 연산이
제공됩니다.

**변환 정확성(CPU, 완전히 검증됨 — 타입 검사만이 아니라):**

| Check | Result |
|---|---|
| 알려진 비트 패턴(fp16: 1.0, -1.0, 0.0, -0.0, 2.0, 0.5, 최대 정규값 65504, 오버플로→inf, 최소 서브노멀, inf; bf16: 1.0, -1.0, 0.0, 2.0, π) | 모두 정확함 |
| fp16 서브노멀 멱등성 스윕(모든 가수 1–1023, `fp16→f32→fp16`) | 1023/1023 정확함 |
| fp16 정규 범위 멱등성 스윕(모든 지수 × 샘플링된 가수, 패턴 330개) | 330/330 정확함 |
| fp16 표현 가능한 값에 대한 왕복 변환(1.0, -3.5, 0.125, 100.0, 4096.0, ...) | 정확함 |
| bf16 왕복 변환(구조상 손실이 있음 — 가수 비트 7개) | 예상되는 약 0.4% 상대 오차 이내 |

[float16.h](/benchmarks/stdlib/float16.h) · [float16.sc](/benchmarks/stdlib/float16.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gpu_webgpu.h](/benchmarks/stdlib/gpu_webgpu.h) · [gpu_webgpu.sc](/benchmarks/stdlib/gpu_webgpu.sc)

**출처:** [train_gpu_f16.sc](/benchmarks/ml/safec/train_gpu_f16.sc) · [train_gpu_bf16.sc](/benchmarks/ml/safec/train_gpu_bf16.sc)

## 머신러닝, 디바이스 선택 {#machine-learning-device-selection}

모든 백엔드는 각자의 연산을 명시적으로 이름 짓습니다(`tensor_matmul`
대 `_blas` 대 `_gpu` 대 `_cuda` 대 ...) — 정밀하지만, 호출 지점마다
함수 이름을 하드코딩하는 대신 디바이스를 한 번만 고르고 싶은
호출자에게는 번거롭습니다.

`tensor_matmul_on(a, b, device)` / `tensor_relu_on(a, device)`는
대신 `Device` 열거형으로 디스패치합니다 — 이 샌드박스에서 실행 가능한
모든 백엔드(CPU, CPU+BLAS, MPS)에서 비트 단위로 동일함이 검증되었습니다.

| Device | Y[0][0] | Y[0][1] |
|---|---|---|
| CPU | 0.900000 | 1.300000 |
| CPU + BLAS | 0.900000 | 1.300000 |
| MPS | 0.900000 | 1.300000 |

이것을 만들면서 실제 이름 충돌이 드러났습니다: `activations.sc`
(순전파 전용 연산)와 `tensor_nn.sc`(모든 GPU 백엔드에 필요한
autograd 연산)가 둘 다 `tensor_sigmoid`/`tensor_relu` 등을
정의하고 있었습니다 — 어떤 프로그램도 둘 다 링크할 수 없었으므로,
`activations.sc` 기반 레이어는 GPU 백엔드를 쓸 수 없었습니다.
`activations.h`의 순전파 전용 함수 이름에 `_fwd` 접미사를 붙이고
`attention.sc`/`transformer.sc`/`rnn.sc`의 호출 지점을 갱신해서
해결했습니다.

`jit_block_forward_on(block, x, device)`는 실제 레이어(JiTBlock의
Q/K/V/출력 프로젝션과 FFN)를 통해 디바이스 선택을 관통시킵니다 —
CPU/CPU+BLAS/MPS 전반에서 레퍼런스와 비트 단위로 동일함이
검증되었습니다.

| Path | y[0] | y[1] | y[2] |
|---|---|---|---|
| `jit_block_forward` (reference) | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU_BLAS)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_MPS)` | -0.479727 | -0.589425 | -0.537808 |

범위 제한: attention 내부의 QK^T/softmax/·V 행렬곱은 아직
디바이스 라우팅되지 않으며, `DiTBlock`/`cnn.sc`도 마찬가지입니다 —
같은 패턴이지만 아직 모든 곳에 적용되지는 않았습니다.

[tensor_dispatch.h](/benchmarks/stdlib/tensor_dispatch.h) · [tensor_dispatch.sc](/benchmarks/stdlib/tensor_dispatch.sc) · [transformer_dispatch.h](/benchmarks/stdlib/transformer_dispatch.h) · [transformer_dispatch.sc](/benchmarks/stdlib/transformer_dispatch.sc) · [activations.h](/benchmarks/stdlib/activations.h) · [activations.sc](/benchmarks/stdlib/activations.sc) · [attention.h](/benchmarks/stdlib/attention.h) · [attention.sc](/benchmarks/stdlib/attention.sc) · [transformer.h](/benchmarks/stdlib/transformer.h) · [transformer.sc](/benchmarks/stdlib/transformer.sc)

## 메모리 할당 — `std::alloc`/`dealloc`이 순수 `malloc`/`free`보다 느릴까요? {#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree}

`std::alloc`은 크기 클래스별로 캐싱하는 할당자를 사용합니다
(PyTorch의 CPU/CUDA 캐싱 할당자와 MLX의 Metal 버퍼 캐시가 쓰는
것과 같은 아이디어입니다): 해제된 블록은 2의 거듭제곱 크기
클래스로 버킷화된 스레드 로컬 프리 리스트로 들어가고, 다음번 같은
클래스의 `alloc()`은 `malloc()`/`free()`를 완전히 건너뛰고 바로
그곳에서 충족됩니다. 이중 해제/UAF 탐지는 영향받지 않습니다(캐시된
블록은 재사용될 때까지 여전히 "해제됨" 매직 워드를 유지합니다).

`std::alloc`/`dealloc`은 작고, 크기가 같고, 짧게 사는 할당이 많은
binarytrees 형태의 워크로드에서 순수 `malloc`/`free`보다 *더
빠르게* 동작하며(1210ms 대 1518ms, 약 20% 더 빠름), 인터리브된
alloc/free 마이크로벤치마크에서는 약 3.3배 더 빠릅니다(호출당
11–12ns 대 37–38ns). `region`/`arena<R>`는 그보다도 더 빠릅니다
(힙 대비 약 5.9배).

[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc)
