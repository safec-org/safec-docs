# 벤치마크

SafeC가 몇 가지 대표적인 마이크로벤치마크에서 실행 시간과 최대 메모리 사용량 면에서 C, C++, Rust, Zig, Go, Python과 어떻게 비교되는지 살펴봅니다 — [The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html)과 [programming-language-benchmarks.vercel.app](https://programming-language-benchmarks.vercel.app)의 정신을 따르되, 머신 한 대에 맞게 축소했습니다. 열마다 **가장 빠른** 값과 **가장 가벼운** 값을 굵게 표시했습니다.

::: warning 아래 수치를 보기 전에 읽어 주세요
단일 머신, 단일 세션, 3회 중 최선(best-of-3) 측정입니다 — Benchmarks Game의 더 엄격한 다중 실행 방법론이 아닙니다. 모든 숫자를 "이 언어에 대한 보편적 진실"이 아니라 "이 머신에서, 이날, 대략 이 정도"로 받아들여 주세요. 이 벤치마크들은 *이 워크로드*의 특성을 보여 줄 뿐, 언어 전체의 성능을 말해 주지 않습니다.
:::

## 방법론 {#methodology}

- **주 머신** (따로 표시하지 않은 모든 표): AMD Ryzen 7 7800X3D(8코어 / 16스레드), AMD Instinct MI100(gfx908, 32GB HBM2), Ubuntu 24.04.5, 네이티브 Linux — `safec` 1.0.0 · clang 21.1.8(C/C++ 및 SafeC 백엔드) · Rust 1.100.0-nightly · Go 1.27.2 · Zig 0.16.0 · Python 3.12.3(+ NumPy 2.5.4) · PyTorch 2.9.1+rocm6.4 · TensorFlow(CPU) · OpenBLAS(pthreads) · ROCm 6.4(HIP, hiprtc, rocBLAS).
- **이전 측정**: "다른 플랫폼" 표는 Apple M1 Pro(macOS 26.5.1, Apple Clang 21.0.0)와, 같은 7800X3D의 WSL2 Ubuntu 22.04(clang 19.1.7) 및 Windows 11(clang/LLVM ~19)에서 예전에 측정한 수치를 그대로 둔 것입니다. 이번 업데이트에서 다시 측정하지 않았고 그 사이 컴파일러와 std가 바뀌었으므로, 경향을 보는 용도로만 보세요.
- **빌드 플래그** — 디버그: `-O0`(C/C++), 최적화하지 않은 IR(SafeC), `go build -gcflags="all=-N -l"`, Zig Debug, 순수 `rustc`. 릴리스: `-O2`(C/C++, 그리고 `safeguard build --release`와 같은 SafeC의 `clang -O2` 백엔드), 순수 `go build`, `zig build-exe -O ReleaseFast`, `rustc -O`. Python에는 디버그/릴리스 구분이 없습니다. SafeC 컴파일 시간은 `--no-incremental`로 측정해 다른 언어와 똑같이 콜드 컴파일입니다.
- **타이밍**: 3회 실행 중 최선(가장 낮은) 실행 시간. **메모리**: 같은 실행의 최대 RSS를 GNU `/usr/bin/time -f %M`으로 잰 값(벤치마크 프로세스 자신의 최댓값입니다. Linux는 `exec` 후에도 `ru_maxrss`를 유지하므로, 부모의 `wait4()`는 fork한 인터프리터의 최댓값을 보고합니다). 웹 서버의 최댓값은 종료 직전에 `/proc/<pid>/status`에서 읽은 `VmHWM`입니다.
- **정확성**: 결과에 포함하기 전에 모든 바이너리의 출력을 알려진 정답과 비교했습니다.
- **재현**: [`run_bench.py`](/benchmarks/run_bench.py)가 모든 것을 빌드하고 실행해 JSON 파일로 씁니다. 이 페이지의 수치는 [`results/linux-7800x3d-mi100.json`](/benchmarks/results/linux-7800x3d-mi100.json)입니다.
- 모든 소스 파일은 결과 옆에 링크되어 있습니다.

## fib(37) — 재귀 피보나치 {#fib37-recursive-fibonacci}

단순 재귀 피보나치 — 순수한 함수 호출 및 정수 산술 오버헤드만 있으며, 할당도 I/O도 없습니다.

| 언어 | 디버그 | 릴리스 | 릴리스 최대 메모리 | 릴리스 컴파일 시간 |
|---|---|---|---|---|
| SafeC | 0.105s | **0.041s (가장 빠름)** | 2.0 MB | 0.033s |
| C | 0.097s | 0.042s | 1.9 MB | 0.029s |
| C++ | 0.098s | 0.042s | 3.8 MB | 0.051s |
| Rust | 0.123s | 0.042s | 2.0 MB | 0.052s |
| Zig | 0.196s | 0.043s | **0.5 MB (가장 가벼움)** | 8.17s |
| Go | **0.094s (가장 빠름)** | 0.089s | 1.9 MB | **0.025s** |
| Python | 2.000s | 1.997s | 10.2 MB | N/A (인터프리터) |

LLVM을 거치는 언어는 릴리스에서 모두 4% 이내로 모입니다. 차이는 잡음 수준입니다.

**다른 플랫폼** (릴리스, 이전 측정):

| 언어 | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.080s | 0.040s | 0.046s |
| C | 0.070s | 0.040s | 0.046s |
| C++ | 0.070s | 0.040s | 0.047s |
| Rust | 0.090s | 0.040s | 0.049s |
| Zig | 0.080s | 0.040s | 0.053s |
| Go | 0.080s | 0.080s | 0.092s |
| Python | 2.700s | 3.330s | 2.485s |

**소스:** [fib.sc](/benchmarks/fib/safec/fib.sc) · [fib.c](/benchmarks/fib/c/fib.c) · [fib.cpp](/benchmarks/fib/cpp/fib.cpp) · [fib.rs](/benchmarks/fib/rust/fib.rs) · [fib.zig](/benchmarks/fib/zig/fib.zig) · [fib.go](/benchmarks/fib/go/fib.go) · [fib.py](/benchmarks/fib/python/fib.py)

## n-body — 5체 궤도 시뮬레이션 {#n-body-5-body-orbital-simulation}

Benchmarks Game의 고전적인 n-body 테스트(태양/목성/토성/천왕성/해왕성), 2,000,000 스텝. 부동소수점 연산이 많고, 할당이 없으며, 작업 집합이 아주 작습니다.

| 언어 | 디버그 | 릴리스 | 릴리스 최대 메모리 | 릴리스 컴파일 시간 |
|---|---|---|---|---|
| SafeC | 0.486s | **0.098s (가장 빠름)** | 1.9 MB | 0.049s |
| C | **0.209s (가장 빠름)** | **0.098s (가장 빠름)** | 1.9 MB | 0.053s |
| C++ | 0.210s | **0.098s (가장 빠름)** | 3.9 MB | 0.097s |
| Rust | 0.447s | 0.108s | 2.1 MB | 0.071s |
| Zig | 0.237s | 0.110s | **0.5 MB (가장 가벼움)** | 8.28s |
| Go | 0.221s | 0.106s | 1.9 MB | 0.075s |
| Python | 7.765s | 7.757s | 10.9 MB | N/A (인터프리터) |

여기서 SafeC 디버그 빌드가 C보다 느린 이유는, 최적화하지 않은 SafeC IR이 모든 경계 검사를 남기고 구조체 필드 접근을 모두 메모리를 거쳐 하기 때문입니다. 릴리스에서는 차이가 완전히 사라집니다.

**다른 플랫폼** (릴리스, 이전 측정):

| 언어 | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.110s | 0.090s | 0.102s |
| C | 0.100s | 0.090s | 0.103s |
| C++ | 0.110s | 0.090s | 0.104s |
| Rust | 0.110s | 0.100s | 0.113s |
| Zig | 0.110s | 0.110s | 0.116s |
| Go | 0.100s | 0.100s | 0.113s |
| Python | 9.890s | 12.150s | 11.922s |

**소스:** [nbody.sc](/benchmarks/nbody/safec/nbody.sc) · [nbody.c](/benchmarks/nbody/c/nbody.c) · [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp) · [nbody.rs](/benchmarks/nbody/rust/nbody.rs) · [nbody.zig](/benchmarks/nbody/zig/nbody.zig) · [nbody.go](/benchmarks/nbody/go/nbody.go) · [nbody.py](/benchmarks/nbody/python/nbody.py)

## binary-trees — 할당/해제 스트레스 {#binary-trees-allocationdeallocation-stress}

작은 이진 트리(최대 깊이 18) 수백만 개를 만들고 버립니다 — 산술보다 메모리 관리를 시험합니다. 주 SafeC 항목은 힙 대신 `region`/`arena<R>`(범프 포인터 할당, `arena_reset<R>()`이 영역 전체를 O(1)에 버림)를 씁니다 — 이 워크로드는 전부 수명이 짧고 같은 스코프 안의 할당이라, 바로 영역이 쓰이는 경우입니다. **SafeC (힙)** 행은 같은 프로그램을 `new` / `std::dealloc`으로 바꾼 것으로, 다른 언어의 `malloc`/`Box`/GC와 같은 조건으로 비교합니다.

| 언어 | 디버그 | 릴리스 | 릴리스 최대 메모리 | 릴리스 컴파일 시간 |
|---|---|---|---|---|
| SafeC (arena) | **0.234s (가장 빠름)** | **0.138s (가장 빠름)** | **25.8 MB (가장 가벼움)** | 0.044s |
| SafeC (힙) | 0.927s | 0.709s | 50.8 MB | 0.044s |
| C | 0.955s | 0.800s | 33.8 MB | 0.039s |
| C++ | 1.177s | 0.916s | 35.8 MB | 0.055s |
| Rust | 1.745s | 0.912s | 33.9 MB | 0.063s |
| Zig | 5.021s | 0.851s | 33.6 MB | 9.85s |
| Go | 1.830s | 0.723s | 37.1 MB | 0.077s |
| Python | 17.965s | 17.965s | 82.6 MB | N/A (인터프리터) |

힙 버전이 C보다 빠른 것은 `std::alloc`이 해제된 블록을 크기 등급별로 캐시하기 때문입니다([메모리 할당](#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree) 참고). 대신 메모리는 더 씁니다: 블록마다 16바이트 안전 헤더가 붙고, 캐시가 해제된 블록을 돌려주지 않고 보관합니다.

**다른 플랫폼** (릴리스, 이전 측정, SafeC는 arena):

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

**소스:** [binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc) · [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c) · [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp) · [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs) · [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig) · [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go) · [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)

## 컬렉션 — std::collections 처리량 (1,000,000개 요소) {#collections-stdcollections-throughput-1000000-elements}

제네릭 컨테이너를 같은 알고리즘의 순수 C 구현과 비교합니다: 늘어나는 배열 + `qsort`, 같은 해시를 쓰는 선형 탐사 해시 맵, 노드마다 `malloc`하는 이중 연결 리스트, 2의 거듭제곱 크기 링 큐, 균형을 맞추지 않는 BST. 양쪽 모두 같은 섞인 키(xorshift64)를 쓰고, 둘 다 clang 21.1.8 `-O2`(`safeguard build --release`), 3회 중 최선입니다.

| 연산 | SafeC | C | SafeC / C |
|---|---|---|---|
| `Vec<int>` push | 2.50 ms | **1.83 ms** | 1.37× |
| `vec_sort` (인트로소트, 인라인된 `Less<int>`) vs `qsort` | **43.4 ms** | 83.6 ms | 0.52× |
| `HashMap<int,int>` insert | 39.1 ms | **35.5 ms** | 1.10× |
| `HashMap<int,int>` get | 8.39 ms | **7.45 ms** | 1.13× |
| `List<int>` push_back + pop_front | **11.5 ms** | 22.5 ms | 0.51× |
| `Queue<int>` enqueue + dequeue | **3.41 ms** | 5.46 ms | 0.62× |
| `BST<int,int,Less<int>>` insert | 177.1 ms | **176.6 ms** | 1.00× |
| `BST<int,int,Less<int>>` get | **172.1 ms** | 174.2 ms | 0.99× |

결과를 가르는 요인:

- **정렬**이 `qsort`보다 빠른 것은 순서가 타입 매개변수이기 때문입니다: 모든 비교가 인라인되지만, `qsort`는 비교할 때마다 간접 호출을 합니다.
- **List**와 **BST** 노드는 노드마다 할당하지 않고 컨테이너별 노드 풀(청크 단위 범프 할당 + 프리 리스트)에서 나옵니다. 그래서 `malloc`의 청크당 오버헤드와 `std::alloc`의 16바이트 안전 헤더를 모두 피합니다. BST 조회는 무작위 탐색의 캐시 미스가 지배하며, 양쪽이 같습니다.
- **HashMap**은 1/2/4/8바이트 키를 한 워드로 해시하고(murmur3 finalizer) 정수 비교 한 번으로 비교하며, 이를 컴파일 시간에 `if const`로 고릅니다. 남은 차이는 insert의 툼스톤 관리입니다.
- **Vec push**는 경계 검사 접근(`keys.at(i)`)과 `std::alloc`의 검사된 `realloc` 경로 비용을 냅니다.

**소스:** [bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc) · [bench_collections.c](/benchmarks/collections/c/bench_collections.c)

## 멀티스레드 — binary-trees, 8 스레드 {#multithreaded-binary-trees-8-threads}

같은 binary-trees 워크로드를 작업 스레드 8개로 병렬화했습니다 — 각 스레드가 주어진 깊이의 트리 개수 중 독립된 몫을 만들고 체크섬을 계산하며, 다음 깊이로 가기 전에 합류합니다. 릴리스만 측정했습니다. SafeC (arena)는 스레드마다 `region`/`arena<R>`을 하나씩 쓰고(arena 상태는 공유하지도 잠그지도 않음), SafeC (힙)은 `new` / `std::dealloc`을 씁니다.

| 언어 | 8 스레드 시간 | 최대 메모리 | 1 스레드 대비 속도 향상 |
|---|---|---|---|
| SafeC (arena) | **0.052s (가장 빠름)** | 98.2 MB | 2.68× |
| SafeC (힙) | 0.267s | 260.3 MB | 2.66× |
| C | 0.215s | 161.2 MB | 3.72× |
| C++ | 0.229s | 163.5 MB | 4.00× |
| Rust | 0.234s | 162.0 MB | 3.89× |
| Zig | 0.222s | 163.8 MB | 3.83× |
| Go | 0.252s | **94.7 MB (가장 가벼움)** | 2.87× |
| Python | 22.475s | 314.1 MB | 0.80× (1 스레드보다 느림) |

힙 버전의 메모리가 큰 것은 스레드마다 크기 등급 캐시가 자기가 해제한 블록을 따로 보관하기 때문입니다.

**다른 플랫폼** (8 스레드 시간, 이전 측정, SafeC는 arena):

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

**소스:** [binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc) · [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c) · [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp) · [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs) · [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig) · [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go) · [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)

## SIMD — f64 값 20,000,000개의 제곱합 {#simd-sum-of-squares-over-20000000-f64-values}

`-O2`/릴리스에서 단순 스칼라 루프와 각 언어의 명시적 벡터 타입을 비교합니다 — "벡터화 vs 일부러 망가뜨린 코드"가 아니라, 백엔드의 자동 벡터화 *위에* 명시적 SIMD가 더해 주는 몫만 떼어 봅니다. SafeC: 네이티브 `vec<double,4>`(LLVM의 타깃 독립 `FixedVectorType`으로 내려가므로 아키텍처별 소스가 필요 없음). C/C++: GCC/Clang 벡터 확장. Zig: `@Vector`. Rust: stable 채널 인트린식(`std::simd`는 nightly 기능 필요) — x86-64에서는 SSE2([simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs)), AArch64에서는 NEON([simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs)). Go에는 이식 가능한 SIMD 타입이 없어 스칼라만 측정합니다.

| 언어 | 스칼라 | 명시적 SIMD | 속도 향상 |
|---|---|---|---|
| SafeC | 0.082s | 0.076s | 1.08× |
| C | 0.078s | **0.069s (가장 빠름)** | 1.14× |
| C++ | 0.080s | 0.076s | 1.06× |
| Rust | 0.084s | 0.075s | 1.11× |
| Zig | **0.078s (가장 빠름)** | 0.071s | 1.10× |
| Go | 0.088s | N/A | — |
| Python | 3.022s | 0.189s (NumPy) | 16.0× |

이 루프는 7800X3D에서 메모리 대역폭에 묶입니다(double 160MB가 96MB L3를 훨씬 넘어 흘러감). 그래서 명시적 SIMD가 자동 벡터화된 스칼라 루프보다 얻는 것이 적고, 표 전체가 프로세스 시작 잡음 정도인 ~10ms 안에 있습니다.

**다른 플랫폼** (이전 측정):

| 언어 | 스칼라: macOS / WSL2 / Windows | 명시적 SIMD: macOS / WSL2 / Windows |
|---|---|---|
| SafeC | 0.040s / 0.099s / 0.067s | 0.030s / 0.092s / 0.060s |
| C | 0.040s / 0.099s / 0.077s | 0.030s / 0.092s / 0.060s |
| C++ | 0.040s / 0.101s / 0.073s | 0.030s / 0.092s / 0.073s |
| Rust | 0.040s / 0.099s / 0.044s | 0.030s / 0.093s / 0.039s |
| Zig | 0.040s / 0.096s / 0.065s | 0.030s / 0.089s / 0.069s |
| Go | 0.060s / 0.105s / 0.074s | N/A |
| Python | 3.500s / 3.144s / 3.406s | 0.140s / 0.213s / 0.313s (NumPy) |

**소스:** [simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc) · [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c) · [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp) · [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs) · [simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs) · [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig) · [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go) · [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)

## 웹 서비스 — JSON "hello world" 엔드포인트 {#web-service-json-hello-world-endpoint}

`GET /`이 `{"message":"Hello, World!"}`를 돌려줍니다 — TechEmpower의 "JSON serialization" 테스트와 같은 형태입니다. Apache Bench(`ab -n 20000 -c 50`, keep-alive 없음, 2,000 요청 워밍업 후)로 각 언어의 HTTP 방식을 측정합니다: SafeC의 `std::http_serve_reactor`(Linux에서는 epoll 리액터), C/C++/Zig는 최소한의 raw 소켓 accept 루프, Go의 `net/http`, Python의 FastAPI+uvicorn, Rust의 **axum**(Dioxus가 아님 — Dioxus의 풀스택 서버 계층이 바로 axum이므로, 실제로 거치는 것을 측정합니다).

| 언어 | req/s | p50 / p99 지연 | 최대 메모리 |
|---|---|---|---|
| SafeC | **25,263 (가장 빠름)** | 2 / 2 ms | 5.1 MB |
| C | 24,577 | 2 / 2 ms | **3.2 MB (가장 가벼움)** |
| C++ | 24,837 | 2 / 2 ms | 5.0 MB |
| Rust | 25,027 | 2 / 2 ms | 4.4 MB |
| Zig | 24,766 | 2 / 2 ms | 7.0 MB |
| Go | 23,354 | 2 / 3 ms | 15.8 MB |
| Python | 5,083 | 10 / 14 ms | 46.0 MB |

모든 서버가 실패 없이 모든 요청을 처리했습니다. 컴파일 언어 서버는 모두 서로 8% 이내입니다: keep-alive가 없으면 요청마다 새 TCP 연결이므로, 병목은 서버가 아니라 커널의 connect/accept 경로와 `ab` 자체입니다.

**다른 플랫폼** (이전 측정. Windows는 `ab` 대신 작은 Go 부하 생성기를 `-n 5000 -c 20`으로 썼으므로 그 열은 자기 자신과만 비교하세요):

| 언어 | macOS req/s (p50/p99) | WSL2 req/s (p50/p99) | Windows req/s (p50/p99) |
|---|---|---|---|
| SafeC | 36055 (1/5ms) | 17650 (3/4ms) | 2313 (7.5/19.6ms) |
| C | 25303 (2/2ms) | N/A | N/A |
| C++ | 24148 (2/4ms) | N/A | N/A |
| Rust | 27270 (2/2ms) | 18077 (3/3ms) | 465 (33.0/474.3ms) |
| Zig | 25742 (2/2ms) | N/A | N/A |
| Go | 25274 (2/3ms) | 18758 (3/4ms) | 2467 (7.8/16.9ms) |
| Python | 4790 (10/33ms) | 2357 (21/27ms) | 528 (37.3/52.5ms) |

**소스:** [server_reactor.sc](/benchmarks/webservice/safec/server_reactor.sc) · [server.sc](/benchmarks/webservice/safec/server.sc) · [server.c](/benchmarks/webservice/c/server.c) · [server.cpp](/benchmarks/webservice/cpp/server.cpp) · [main.rs](/benchmarks/webservice/rust/src/main.rs) · [Cargo.toml](/benchmarks/webservice/rust/Cargo.toml) · [server.zig](/benchmarks/webservice/zig/server.zig) · [server.go](/benchmarks/webservice/go/server.go) · [server.py](/benchmarks/webservice/python/server.py) · [http.sc](/benchmarks/stdlib/http.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h) · [task.h](/benchmarks/stdlib/task.h)

## 머신러닝 — 작은 MLP, 학습과 추론 {#machine-learning-small-mlp-training-and-inference}

2층 MLP(`relu(X @ W1) @ W2`, 128→256→64, 배치 64, MSE 손실, 직접 작성한 SGD, 바이어스 없음) — 고정 시드로 학습 100 스텝 후 추론 1000회. 모든 프레임워크가 매 스텝 손실을, 매 추론마다 `Y[0][0]`을 호스트로 읽어 옵니다(PyTorch의 `.item()`). 그래서 GPU 행은 손실을 기록하는 실제 학습 루프처럼 스텝마다 호스트 왕복 비용을 냅니다. 각 프레임워크는 먼저 시간을 재지 않는 워밍업을 한 번 합니다: 이 빌드의 PyTorch는 첫 `backward()`에 CPU에서도 ~10초의 일회성 초기화가 걸리고, rocBLAS는 GEMM 형태별 커널을 처음 쓸 때 불러옵니다.

| 프레임워크 | 장치 | 학습 (100 스텝) | 처리량 | 추론 (1000회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (naive) | 110.9 ms | 57,700 samples/s | 286.0 ms | 100.333427 |
| SafeC | CPU (OpenBLAS) | 31.3 ms | 204,400 samples/s | 55.3 ms | 100.333420 |
| SafeC | ROCm (MI100) | 7.25 ms | 882,500 samples/s | 35.4 ms | 100.333282 |
| SafeC | ROCm, 그래프 재생 | **7.04 ms (가장 빠름)** | **909,000 samples/s** | 39.4 ms | 100.333282 |
| PyTorch | CPU | 14.0 ms | 458,400 samples/s | **22.0 ms (가장 빠름)** | 100.018272 |
| PyTorch | ROCm (MI100) | 19.6 ms | 326,600 samples/s | 36.5 ms | 103.071129 |
| TensorFlow | CPU | 79.8 ms | 80,200 samples/s | 207.2 ms | 106.182999 |

이 크기에서는 GPU 스텝 하나가 마이크로초 단위 커널 몇 개와 손실의 블로킹 읽기로 이루어지므로, 실행 시작과 동기화 지연이 시간을 지배합니다. CPU에서는 PyTorch의 CPU 경로(번들된 튜닝 GEMM 커널)가 SafeC의 OpenBLAS 경로보다 빠르고, 추론에서 가장 빠릅니다. 프레임워크마다 손실이 다른 것은 각자 자기 RNG로 데이터와 초기 가중치를 뽑기 때문입니다. SafeC 행들은 모두 시드가 같은 하나의 스트림(`drand48`)을 공유하며, CPU와 GPU에서 6자리까지 일치합니다.

**다른 플랫폼** (이전 측정, Apple M1 Pro):

| 프레임워크 | 장치 | 학습 (100 스텝) | 추론 (1000회) |
|---|---|---|---|
| SafeC | CPU (Accelerate BLAS) | 16.8 ms | 26.1 ms |
| SafeC | MPS | 39.4 ms | 61.1 ms |
| PyTorch | CPU | 23.1 ms | 13.5 ms |
| PyTorch | MPS | 131.6 ms | 358.7 ms |
| TensorFlow | CPU | 83.0 ms | 245.0 ms |
| TensorFlow | GPU | 245.3 ms | 924.6 ms |
| MLX | GPU | 72.2 ms | 309.1 ms |

[train.sc](/benchmarks/ml/safec/train.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py)

## 머신러닝, 더 큰 모델 — 512→1024→256 {#machine-learning-bigger-model-512-1024-256}

같은 모델을 ~50배 키웠습니다(배치 128). 학습 50 스텝, 추론 200회. 워밍업과 스텝마다 읽어 오는 방식은 위와 같습니다.

| 프레임워크 | 장치 | 학습 (50 스텝) | 처리량 | 추론 (200회) | 손실 (검증용) |
|---|---|---|---|---|---|
| SafeC | CPU (naive) | 1794.1 ms | 3,600 samples/s | 1503.3 ms | 95.428474 |
| SafeC | CPU (OpenBLAS) | 170.9 ms | 37,500 samples/s | 221.4 ms | 95.424973 |
| SafeC | ROCm (MI100) | 8.31 ms | 770,400 samples/s | **13.07 ms (가장 빠름)** | 95.428131 |
| SafeC | ROCm, 그래프 재생 | **8.09 ms (가장 빠름)** | **790,900 samples/s** | 14.01 ms | 95.428116 |
| PyTorch | CPU | 38.2 ms | 167,500 samples/s | 50.4 ms | 106.601181 |
| PyTorch | ROCm (MI100) | 10.14 ms | 631,200 samples/s | 13.30 ms | 1868.155273 |
| TensorFlow | CPU | 107.3 ms | 59,700 samples/s | 114.7 ms | 316.611603 |

GPU에서 SafeC는 PyTorch보다 ~20% 빠르게 학습하고, 추론은 비깁니다(둘 다 한 번에 rocBLAS GEMM 두 개, 원소별 커널 하나, 읽기 한 번). 그래프 재생은 학습 스텝 전체를 `RocmGraph` 하나에 기록해 한 번의 호출로 실행하지만, 매 스텝 손실을 블로킹으로 읽으면 ~3%만 줄어듭니다. 스텝마다 읽지 않으면, 같은 루프의 C++ 프로토타입이 7.1ms 대신 3.8ms로 돌았습니다. PyTorch-ROCm의 손실은 GPU RNG로 뽑은 초기 가중치에서 lr 0.001로 발산합니다. 속도와는 상관없지만, 검증용 값이 이상해 보이는 이유입니다.

CPU에서는 여기서 쓴 OpenBLAS(Ubuntu 패키지의 범용 pthreads 빌드)가 약점입니다: 이 크기에서 PyTorch의 CPU GEMM이 학습에서 4.5배 빠릅니다.

**다른 플랫폼** (이전 측정, Apple M1 Pro):

| 프레임워크 | 장치 | 학습 (50 스텝) | 추론 (200회) |
|---|---|---|---|
| SafeC | CPU (Accelerate BLAS) | 89.2 ms | 61.4 ms |
| SafeC | MPS | 47.9 ms | 47.3 ms |
| PyTorch | CPU | 67.8 ms | 48.2 ms |
| PyTorch | MPS | 110.5 ms | 86.4 ms |
| TensorFlow | CPU | 148.8 ms | 197.6 ms |
| TensorFlow | GPU | 154.1 ms | 218.3 ms |
| MLX | GPU | 54.3 ms | 74.4 ms |

[train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_cpu_blas.sc](/benchmarks/ml_big/safec/train_cpu_blas.sc) · [train_rocm.sc](/benchmarks/ml/safec/train_rocm.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc) · [PyTorch train.py](/benchmarks/ml_big/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml_big/tensorflow/train.py) · [MLX train.py](/benchmarks/ml_big/mlx/train.py)

## 머신러닝, GPU 백엔드 — CUDA, ROCm, Vulkan/SPIR-V, WebGPU {#machine-learning-gpu-backends-cuda-rocm-vulkanspir-v-webgpu}

| 백엔드 | 원소별 연산 | 행렬곱 (std 커널) | 행렬곱 (벤더 BLAS) | 상태 |
|---|---|---|---|---|
| ROCm / HIP | 실제 동작 (HIP C++, 처음 쓸 때 hiprtc로 컴파일) | 실제 동작 (64×64 LDS 타일 SGEMM) | 실제 동작 (rocBLAS) | MI100에서 **측정함**: `tests/stdlib/rocm`의 검사 29개와 위의 MLP 결과 |
| CUDA | 실제 동작 (PTX, 텍스트 IR) | 실제 동작 (PTX) | 실제 동작 (cuBLAS) | 측정 안 함 — 여기에 NVIDIA GPU 없음 |
| Vulkan/SPIR-V | 항상 0 반환 | 항상 0 반환 | n/a | SPIR-V는 바이너리 IR; 여기에 glslc/glslangValidator 없음 |
| WebGPU | 실제 동작 (WGSL, 텍스트 IR) | 실제 동작 (WGSL) | n/a | 측정 안 함 — 여기에 wgpu-native/Dawn 라이브러리 없음 |

ROCm 백엔드는 커널을 HIP 소스로 내장하고, 프로세스마다 한 번 찾은 GPU에 맞게 컴파일합니다(ROCm이 지원하는 모든 gfx 타깃, 미리 빌드한 HSACO 불필요). 연산 유닛이 가장 많은 장치를 고르며, 외장 GPU를 우선합니다. 원소별 연산 외에 학습용 융합 커널도 있습니다: `rocm_relu_grad`, `rocm_mse_grad`(기울기와 손실을 한 번에), `rocm_sgd`, `rocm_sum`(웨이브프런트 셔플, LDS, 블록당 원자 연산 한 번). HIP 그래프의 기록과 재생도 지원합니다(`rocm_capture_begin` / `rocm_capture_end` / `rocm_graph_launch`).

std SGEMM 커널은 MI100에서 4096³ 기준 ~9.5 TFLOP/s, rocBLAS는 행렬 코어를 써서 33.7 TFLOP/s에 이릅니다. 그래서 `rocm_gemm`, `rocm_matmul_f32_blas`, `tensor_matmul_rocm_blas`는 rocBLAS를 호출하고, std 커널은 `rocm_matmul_f32`와 `tensor_matmul_rocm`에 쓰입니다. 사용자 커널이 실제로 도움이 되는 곳은 융합입니다: 기울기, 손실, 갱신 커널이 각각 별도 실행 두세 번과 리덕션을 하나로 대신합니다.

[gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [tensor_rocm.h](/benchmarks/stdlib/tensor_rocm.h) · [tensor_rocm.sc](/benchmarks/stdlib/tensor_rocm.sc) · [gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gpu_spirv.h](/benchmarks/stdlib/gpu_spirv.h) · [gpu_spirv.sc](/benchmarks/stdlib/gpu_spirv.sc) · [gpu_webgpu.h](/benchmarks/stdlib/gpu_webgpu.h) · [gpu_webgpu.sc](/benchmarks/stdlib/gpu_webgpu.sc) · [tensor_cuda.h](/benchmarks/stdlib/tensor_cuda.h) · [tensor_cuda.sc](/benchmarks/stdlib/tensor_cuda.sc)

## 머신러닝, fp16 / bf16 지원 {#machine-learning-fp16-bf16-support}

SafeC 타입 시스템에는 네이티브 16비트 부동소수점이 없습니다(`Type.h`의 `TypeKind`에는 `Float32`/`Float64`뿐 — 표준 라이브러리 변경이 아니라 컴파일러 기능이 필요함). 대신 표준적인 우회 방법을 씁니다: fp16/bf16을 `unsigned short`의 원시 비트로 담고, `float`와의 변환을 명시적으로 올바르게 반올림(최근접 짝수 반올림)합니다 — 그리고 MPS 백엔드에서는 저장 공간만 절반으로 줄이는 것이 아니라 실제 네이티브 `half`/`bfloat` GPU 연산을 합니다.

| 검사 | 결과 |
|---|---|
| 알려진 비트 패턴 (fp16: 1.0, -1.0, 0.0, -0.0, 2.0, 0.5, 최대 정규수 65504, 오버플로→inf, 최소 비정규수, inf; bf16: 1.0, -1.0, 0.0, 2.0, π) | 모두 정확 |
| fp16 비정규수 멱등성 스윕 (모든 가수 1–1023, `fp16→f32→fp16`) | 1023/1023 정확 |
| fp16 정규 범위 멱등성 스윕 (모든 지수 × 표본 가수, 패턴 330개) | 330/330 정확 |
| 표현 가능한 값의 fp16 왕복 변환 | 정확 |
| bf16 왕복 변환 (구조상 손실 있음 — 가수 7비트) | 상대 오차 ~0.4% 이내 |

[float16.h](/benchmarks/stdlib/float16.h) · [float16.sc](/benchmarks/stdlib/float16.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gpu_webgpu.h](/benchmarks/stdlib/gpu_webgpu.h) · [gpu_webgpu.sc](/benchmarks/stdlib/gpu_webgpu.sc) · [train_gpu_f16.sc](/benchmarks/ml/safec/train_gpu_f16.sc) · [train_gpu_bf16.sc](/benchmarks/ml/safec/train_gpu_bf16.sc)

## 머신러닝, 장치 선택 {#machine-learning-device-selection}

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

[tensor_dispatch.h](/benchmarks/stdlib/tensor_dispatch.h) · [tensor_dispatch.sc](/benchmarks/stdlib/tensor_dispatch.sc) · [transformer_dispatch.h](/benchmarks/stdlib/transformer_dispatch.h) · [transformer_dispatch.sc](/benchmarks/stdlib/transformer_dispatch.sc) · [activations.h](/benchmarks/stdlib/activations.h) · [activations.sc](/benchmarks/stdlib/activations.sc) · [attention.h](/benchmarks/stdlib/attention.h) · [attention.sc](/benchmarks/stdlib/attention.sc) · [transformer.h](/benchmarks/stdlib/transformer.h) · [transformer.sc](/benchmarks/stdlib/transformer.sc)

## 메모리 할당 — `std::alloc`/`dealloc`은 raw `malloc`/`free`보다 느린가? {#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree}

`std::alloc`은 블록마다 이중 해제/해제 후 사용 검사를 담는 16바이트 헤더를 붙이고, 크기 등급 캐시를 둡니다: PyTorch의 CPU/CUDA 캐싱 할당자, MLX의 Metal 버퍼 캐시와 같은 발상입니다. 해제된 블록은 2의 거듭제곱 크기 등급별 스레드 로컬 프리 리스트로 들어가고, 같은 등급의 다음 `alloc()`은 `malloc()`/`free()`를 부르지 않고 거기서 바로 받습니다. 캐시된 블록은 재사용될 때까지 "해제됨" 매직 워드를 유지하므로 안전 검사는 그대로 동작합니다. glibc의 `malloc`/`free`와 비교한 결과(3회 중 최선. 모든 블록 주소를 출력되는 체크섬에 섞어, 최적화기가 할당/해제 쌍을 지우지 못하게 함):

| 패턴 | `std::alloc` / `dealloc` | `malloc` / `free` | 비율 |
|---|---|---|---|
| 교차: 48바이트 블록 하나를 할당하고 해제, 2천만 번 | **3.84 ns / 쌍** | 6.8 ns / 쌍 | 1.8배 빠름 |
| 일괄: 24바이트 블록 100만 개를 할당한 뒤 모두 해제, 10회 | 131 ms | **92 ms** | 1.4배 느림 |

해제된 블록이 곧 다시 쓰이면 캐시가 이깁니다. 수명이 짧은 객체와 binary-trees 같은 반복 할당이 그렇습니다: 위의 힙 binary-trees는 다른 모든 언어의 할당자보다 빠릅니다. 살아 있는 블록 100만 개는 캐시가 담을 수 있는 양(모든 등급 합쳐 125,587 슬롯)을 넘으므로, 일괄 패턴의 할당 대부분은 `malloc`에 헤더 쓰기와 캐시 관리까지 거치고, 그 오버헤드가 드러납니다. 수명이 짧은 대량 할당에는 `region`/`arena<R>`이 더 빠른 도구입니다: binary-trees에서 힙 버전보다 5.1배 빠릅니다.

[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc) · [alloc_bench.sc](/benchmarks/memalloc/safec/alloc_bench.sc)
