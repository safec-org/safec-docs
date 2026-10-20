# 벤치마크

몇 가지 대표적인 마이크로벤치마크([The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html) 방식)에서 SafeC를 C, C++, Rust, Zig, Go, Python과 비교하고, 표준 라이브러리의 컬렉션, HTTP 서버, ML 텐서, 할당자도 측정합니다. 머신 한 대, 3회 중 최선입니다: 모든 숫자는 언어 순위가 아니라 "이 머신에서 대략 이 정도"로 읽어 주세요.

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

- **머신러닝** (2층 MLP, [자세히](#machine-learning-small-mlp-training-and-inference)): MI100에서 SafeC는 작은 모델을 7.2 ms에 학습하며(PyTorch-ROCm 20.1 ms), 큰 모델은 8.3 ms입니다(PyTorch-ROCm 10.1 ms). CPU에서는 PyTorch가 더 빠릅니다(14.1 ms, SafeC + OpenBLAS 29.6 ms).
- **NVIDIA H200** ([자세히](#h200-machine-learning)): SafeC의 CUDA 백엔드는 작은 모델을 3.80 ms에 학습하며(PyTorch-CUDA fp32 50.9 ms), 큰 모델은 3.13 ms에 학습합니다(16.9 ms). Xeon 8570 CPU 결과는 [별도 절](#xeon-8570-classic)에 있습니다.
- **컬렉션** ([자세히](#collections-stdcollections-throughput-1000000-elements)): `vec_sort`는 `qsort`보다 1.9배, 풀을 쓰는 리스트는 노드마다 `malloc`하는 리스트보다 1.9배 빠르고, `Vec` push와 `HashMap`은 순수 C보다 1.2–1.6배 느립니다.
- **할당자** ([자세히](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree)): 블록이 재사용되면 `std::alloc`이 `malloc`보다 1.8배 빠르고, 살아 있는 블록이 100만 개면 1.4배 느립니다.

## 방법론 {#methodology}

- **머신**: AMD Ryzen 7 7800X3D(8코어 / 16스레드), AMD Instinct MI100(gfx908, 32GB HBM2), Ubuntu 24.04.5, 네이티브 Linux — `safec` 1.0.0 · clang 21.1.8(C/C++ 및 SafeC 백엔드) · Rust 1.100.0-nightly · Go 1.27.2 · Zig 0.16.0 · Python 3.12.3(+ NumPy 2.5.4) · PyTorch 2.9.1+rocm6.4 · TensorFlow(CPU) · OpenBLAS(pthreads) · ROCm 6.4(HIP, hiprtc, rocBLAS).
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
| SafeC | CPU (naive) | 107.4 ms | 59,600 samples/s | 285.8 ms | 100.333427 |
| SafeC | CPU (OpenBLAS) | 29.6 ms | 216,400 samples/s | 55.7 ms | 100.333420 |
| SafeC | ROCm (MI100) | **7.17 ms (가장 빠름)** | **892,400 samples/s** | 34.9 ms | 100.333282 |
| SafeC | ROCm, 그래프 재생 | 7.18 ms | 891,100 samples/s | 39.2 ms | 100.333298 |
| PyTorch | CPU | 14.1 ms | 453,600 samples/s | **21.9 ms (가장 빠름)** | 100.018272 |
| PyTorch | ROCm (MI100) | 20.1 ms | 319,200 samples/s | 36.3 ms | 103.071129 |
| TensorFlow | CPU | 79.3 ms | 80,700 samples/s | 204.0 ms | 106.182999 |

이 크기에서는 GPU 스텝 하나가 마이크로초 단위 커널 몇 개와 손실의 블로킹 읽기로 이루어지므로, 실행 시작과 동기화 지연이 시간을 지배합니다. CPU에서는 PyTorch의 CPU 경로(번들된 튜닝 GEMM 커널)가 SafeC의 OpenBLAS 경로보다 빠르고, 추론에서 가장 빠릅니다. 프레임워크마다 손실이 다른 것은 각자 자기 RNG로 데이터와 초기 가중치를 뽑기 때문입니다. SafeC 행들은 모두 시드가 같은 하나의 스트림(`drand48`)을 공유하며, CPU와 GPU에서 6자리까지 일치합니다.

::: details 소스
[train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py)
:::

## 머신러닝, 더 큰 모델 — 512→1024→256 {#machine-learning-bigger-model-512-1024-256}

같은 모델을 ~50배 키웠습니다(배치 128). 학습 50 스텝, 추론 200회. 워밍업과 스텝마다 읽어 오는 방식은 위와 같습니다.

| 프레임워크 | 장치 | 학습 (50 스텝) | 처리량 | 추론 (200회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (naive) | 1761.2 ms | 3,600 samples/s | 1504.2 ms | 95.428474 |
| SafeC | CPU (OpenBLAS) | 160.6 ms | 39,900 samples/s | 217.2 ms | 95.424973 |
| SafeC | ROCm (MI100) | 8.40 ms | 761,800 samples/s | **13.0 ms (가장 빠름)** | 95.428131 |
| SafeC | ROCm, 그래프 재생 | **8.31 ms (가장 빠름)** | **770,400 samples/s** | 14.1 ms | 95.428093 |
| PyTorch | CPU | 38.4 ms | 166,500 samples/s | 50.2 ms | 106.601181 |
| PyTorch | ROCm (MI100) | 10.1 ms | 634,700 samples/s | 13.3 ms | 1868.155273 |
| TensorFlow | CPU | 106.7 ms | 60,000 samples/s | 118.1 ms | 316.611603 |

GPU에서 SafeC는 PyTorch보다 ~17% 빠르게 학습하고, 추론은 비깁니다(둘 다 한 번에 rocBLAS GEMM 두 개, 원소별 커널 하나, 읽기 한 번). 그래프 재생은 학습 스텝 전체를 `RocmGraph` 하나에 기록해 한 번의 호출로 실행하지만, 매 스텝 손실을 블로킹으로 읽으면 많아야 1% 정도만 줄어듭니다. 스텝마다 읽지 않으면, 같은 루프의 C++ 프로토타입이 7.1ms 대신 3.8ms로 돌았습니다. PyTorch-ROCm의 손실은 GPU RNG로 뽑은 초기 가중치에서 lr 0.001로 발산합니다. 속도와는 상관없지만, 검증용 값이 이상해 보이는 이유입니다.

CPU에서는 여기서 쓴 OpenBLAS(Ubuntu 패키지의 범용 pthreads 빌드)가 약점입니다: 이 크기에서 PyTorch의 CPU GEMM이 학습에서 4.2배 빠릅니다.

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

## Xeon Platinum 8570 + NVIDIA H200 {#xeon-8570-h200}

같은 벤치마크를 GPU 서버에서 돌린 결과이며, SafeC의 CUDA 백엔드를 NVIDIA 하드웨어에서 처음 실행한 결과입니다. CPU, 코어 수, 툴체인 버전이 모두 다르므로 위의 7800X3D 표와 비교하지 말고 이 절 안에서만 비교하세요.

- **장비**: 호스트 112코어 중 12코어로 제한된 Backend.AI 컨테이너의 Intel Xeon Platinum 8570, NVIDIA H200 (sm_90, 141 GB HBM3e, 드라이버 580.126.20, CUDA 13.0), Ubuntu 24.04.3 — `safec` 1.0.0 (v1.0.0-beta-5) · clang 21.1.8 · Rust 1.99.1 · Go 1.27.3 · Zig 0.17.0 · Python 3.12.3 · PyTorch 2.10.0a0 (NGC 25.11) · TensorFlow 2.21.0 (CPU) · OpenBLAS (pthreads, Ubuntu 패키지).
- **방법**: [위](#methodology)와 같고, 3회 중 최고값입니다. H200은 학습 작업과 공유되는데, 측정하는 동안 그 작업을 일시 정지(`SIGSTOP`)해서 GPU와 12코어가 비어 있었습니다. 그 작업이 도는 동안에는 같은 GPU 벤치마크가 SafeC든 PyTorch든 실행마다 5배까지 차이 났습니다.
- **수치**: [`results/linux-xeon8570-h200.json`](/benchmarks/results/linux-xeon8570-h200.json).

### H200에서의 머신러닝 {#h200-machine-learning}

모델, 루프, 스텝마다의 읽기는 [MI100 표](#machine-learning-small-mlp-training-and-inference)와 같습니다. SafeC CUDA 행은 [train_cuda.sc](/benchmarks/ml/safec/train_cuda.sc)의 네 가지 모드입니다:

- **plain**: ROCm 벤치마크와 같은, 커널 실행 10번짜리 스텝.
- **fused**: `relu(X W1)`을 cuBLASLt 호출 한 번으로, SGD 갱신 두 번을 커널 하나로 처리해 스텝당 7번 실행.
- **fused + graph**: fused 스텝을 CUDA 그래프 하나로 재생.
- **TF32**: 그래프 스텝에 TF32 텐서 코어를 켠 것(`cuda_set_tf32`).

NVIDIA NGC PyTorch 컨테이너는 `TORCH_ALLOW_TF32_CUBLAS_OVERRIDE=1`을 설정하므로, PyTorch의 fp32 행렬곱은 기본으로 이미 TF32로 돕니다. PyTorch fp32 행은 이 변수를 0으로 설정한 결과입니다.

작은 모델 (128→256→64, 배치 64, 학습 100 스텝, 추론 1000회):

| 프레임워크 | 장치 | 학습 (100 스텝) | 처리량 | 추론 (1000회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (naive) | 144.0 ms | 44,459 samples/s | 481.1 ms | 100.333427 |
| SafeC | CPU (OpenBLAS) | 40.8 ms | 157,036 samples/s | 93.9 ms | 100.333420 |
| SafeC | CUDA (H200), plain | 6.72 ms | 951,673 samples/s | 38.7 ms | 100.333282 |
| SafeC | CUDA, fused | 6.23 ms | 1,027,287 samples/s | 34.9 ms | 100.333282 |
| SafeC | CUDA, fused + graph | 3.80 ms | 1,683,325 samples/s | 19.6 ms | 100.333290 |
| SafeC | CUDA, graph + TF32 | **3.79 ms (가장 빠름)** | **1,687,319 samples/s** | **19.5 ms (가장 빠름)** | 100.334076 |
| PyTorch | CPU | 23.3 ms | 274,560 samples/s | 43.4 ms | 100.018272 |
| PyTorch | CUDA, TF32 (NGC 기본값) | 47.7 ms | 134,166 samples/s | 55.6 ms | 103.076630 |
| PyTorch | CUDA, fp32 | 50.9 ms | 125,811 samples/s | 56.5 ms | 103.071129 |
| TensorFlow | CPU | 122.0 ms | 52,470 samples/s | 265.5 ms | 106.182999 |

큰 모델 (512→1024→256, 배치 128, 학습 50 스텝, 추론 200회):

| 프레임워크 | 장치 | 학습 (50 스텝) | 처리량 | 추론 (200회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (naive) | 2323.4 ms | 2,755 samples/s | 2764.1 ms | 95.428474 |
| SafeC | CPU (OpenBLAS) | 205.8 ms | 31,094 samples/s | 255.3 ms | 95.424973 |
| SafeC | CUDA (H200), plain | 4.08 ms | 1,569,397 samples/s | 8.21 ms | 95.427277 |
| SafeC | CUDA, fused | 3.76 ms | 1,701,223 samples/s | 7.59 ms | 95.427292 |
| SafeC | CUDA, fused + graph | 3.13 ms | 2,046,690 samples/s | **5.56 ms (가장 빠름)** | 95.427284 |
| SafeC | CUDA, graph + TF32 | **2.31 ms (가장 빠름)** | **2,770,563 samples/s** | 5.57 ms | 90.685478 |
| PyTorch | CPU | 60.5 ms | 105,864 samples/s | 54.9 ms | 106.544487 |
| PyTorch | CUDA, TF32 (NGC 기본값) | 18.0 ms | 356,506 samples/s | 11.9 ms | 871.826538 |
| PyTorch | CUDA, fp32 | 16.9 ms | 379,192 samples/s | 12.1 ms | 1754.826172 |
| TensorFlow | CPU | 225.7 ms | 28,353 samples/s | 237.4 ms | 316.611603 |

- **GPU에서 SafeC 대 PyTorch, fp32끼리**: 학습은 작은 모델에서 13배(3.80 ms 대 50.9 ms), 큰 모델에서 5.4배(3.13 ms 대 16.9 ms) 빠르고, 추론은 2.9배와 2.2배 빠릅니다. 이 크기에서 한 스텝은 마이크로초 단위 커널 몇 개와 손실 값을 기다리는 읽기 한 번이라, 실행 한 번에 드는 비용이 결과를 정합니다. SafeC 한 스텝은 약 38 µs(그래프 실행 한 번과 읽기)이고, PyTorch 한 스텝은 같은 cuBLAS 호출 몇 개를 둘러싼 Python, autograd, 디스패처 작업으로 약 500 µs입니다.
- **SafeC의 시간 분해**: 융합으로 실행 10번 중 3번이 빠지면서 7–8% 줄어듭니다. 그래프는 실행 비용이 지배적인 작은 모델에서 39%, 큰 모델에서 17%를 더 줄입니다. TF32는 계산량이 병목이 아닌 작은 모델에서는 차이가 없고, 큰 모델의 학습을 26% 줄입니다. TF32를 쓰면 큰 모델의 50 스텝 후 손실이 95.43 대신 90.69가 됩니다. 이 루프가 반올림 차이를 키우기 때문이며, CPU, fp32 GPU, ROCm 실행은 모두 4자리까지 일치합니다.
- **이 백엔드 이전**: v1.0.0-beta-5에서는 `tensor_matmul_cuda_blas`가 호출마다 CUDA 컨텍스트와 cuBLAS 핸들을 만들고 없앴습니다. 이 GPU에서 그 경로로 작은 벤치마크를 돌리면 학습에 83초, 추론에 816초가 걸렸습니다. fused + graph 행보다 20,000배 넘게 느립니다.
- **CPU**: 이 12코어는 스레드당 성능이 7800X3D보다 낮습니다. 여기서 OpenBLAS는 작은 모델 학습에서 PyTorch 자체 CPU 커널보다 1.7배, 큰 모델에서 3.4배 느립니다.

### Xeon 8570에서의 기본 벤치마크 {#xeon-8570-classic}

릴리스 빌드 실행 시간, 3회 중 최고값(낮을수록 좋음, 가장 빠른 값 굵게). 웹 서비스는 초당 요청 수입니다(높을수록 좋음). 멀티스레드와 웹 벤치마크의 Zig 소스는 Zig 0.16용으로 작성되어, `**` 배열 반복 문법을 바꾸고 `@cImport`를 없앤 0.17.0에서는 빌드되지 않습니다.

| 벤치마크 | SafeC | C | C++ | Rust | Zig | Go | Python |
|---|---|---|---|---|---|---|---|
| fib(37) | 0.086s | 0.087s | 0.090s | 0.092s | **0.071s** | 0.134s | 2.432s |
| n-body | 0.146s | **0.144s** | 0.145s | 0.154s | 0.154s | 0.190s | 9.334s |
| binary-trees (SafeC: arena) | **0.310s** | 1.165s | 1.372s | 1.400s | 1.248s | 3.319s | 29.399s |
| binary-trees, SafeC 힙 | 1.160s | | | | | | |
| binary-trees, 8 스레드 (SafeC: arena) | **0.092s** | 0.407s | 0.450s | 0.459s | N/A | 0.412s | 74.727s |
| binary-trees, 8 스레드, SafeC 힙 | 0.395s | | | | | | |
| SIMD, 명시적 벡터 | 0.114s | 0.110s | 0.112s | 0.110s | **0.093s** | — | 0.334s (NumPy) |
| 웹 서비스 (req/s) | **33,738** | 32,177 | 32,606 | 32,652 | N/A | 32,182 | 2,886 |

컬렉션 (1,000,000개 요소, ms): `Vec` push 3.23 대 C 1.73, `vec_sort` **74.8** 대 `qsort` 148.8, `HashMap` insert 62.1 대 54.3, get 20.4 대 18.9, `List` push+pop **17.5** 대 27.5, `Queue` **4.16** 대 6.78, `BST` insert 357.7 대 **315.5**, get 348.4 대 **337.2**. 할당자: 번갈아 할당·해제하는 쌍 하나에 `std::alloc`/`dealloc` 10.06 ns 대 `malloc`/`free` **8.52** ns, 일괄 패턴은 158 ms 대 **119** ms. 순위는 7800X3D와 같지만, 번갈아 할당하는 패턴만은 이 장비에서 glibc `malloc`이 앞섭니다.

::: details 소스
[train_cuda.sc](/benchmarks/ml/safec/train_cuda.sc) · [gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [PyTorch 큰 모델 train.py](/benchmarks/ml_big/pytorch/train.py) · 기본 벤치마크 소스는 위의 각 절에 있습니다.
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
