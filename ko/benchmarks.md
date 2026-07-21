# 벤치마크

SafeC가 몇 가지 대표적인 마이크로벤치마크에서 실행 시간과 최대 메모리 사용량 면에서 C, C++, Rust, Zig, Go, Python과 어떻게 비교되는지 살펴봅니다 — [The Computer Language Benchmarks Game](https://benchmarksgame-team.pages.debian.net/benchmarksgame/index.html)과 [programming-language-benchmarks.vercel.app](https://programming-language-benchmarks.vercel.app)의 정신을 따르되, CI 규모 머신 한 대에 맞도록 축소했습니다. **가장 빠른** 셀과 **가장 가벼운** 셀은 벤치마크별로 굵게 표시했습니다.

::: warning 아래 수치를 보기 전에 읽어 주세요
단일 머신, 단일 세션, 3회 중 최선(best-of-3) 측정입니다 — Benchmarks Game의 더 엄격한 다중 실행 방법론이 아닙니다. 모든 숫자를 "이 언어에 대한 보편적 진실"이 아니라 "이 머신, 이 날짜에 대략 이 정도"로 받아들이세요. 세 가지 벤치마크는 *이 워크로드들*을 특징지을 뿐, 언어 전반의 성능을 특징짓지 않습니다.
:::

## 방법론 {#methodology}

- **주 머신**: Apple M1 Pro, 코어 10개, RAM 32GB, macOS 26.5.1 — `safec` 1.0.0 · Apple Clang 21.0.0(C/C++) · Go 1.26.5 · Zig 0.16.0 · Rust 1.97.1 · Python 3.14.6(SIMD 항목은 + NumPy 2.5.1).
- **보조 머신** (fib/n-body/binary-trees/멀티스레딩/SIMD/웹 서비스, 릴리스 빌드만): 동일한 AMD Ryzen 7 7800X3D 머신(GPU 없음)을, 한 번은 **WSL2 Ubuntu 22.04**(clang 19.1.7, Go/Zig/Rust는 동일 버전)에서, 한 번은 **Windows 11**(clang/LLVM ~19, MSVC 타깃)에서 네이티브로. Mac과는 CPU 아키텍처 자체가 다르므로, 머신 간 차이는 통제된 실험이 아니라 참고 방향으로만 받아들이세요.
- **빌드 플래그** — 디버그: `-O0`(C/C++), 최적화하지 않은 IR(SafeC), `go build -gcflags="all=-N -l"`, Zig의 Debug 모드, 순수 `rustc`. 릴리스: `-O2`(C/C++, 그리고 SafeC의 `clang -O2` 백엔드 — `safeguard build --release`가 실제로 하는 것과 일치) · 순수 `go build` · `zig build-exe -O ReleaseFast` · `rustc -O`. Python은 디버그/릴리스 구분이 없습니다.
- **타이밍**: 3회 실행 중 최선(가장 낮은) 실행 시간 — macOS/WSL2는 `/usr/bin/time -l`, Windows는 `System.Diagnostics.Stopwatch`(동등한 `/usr/bin/time`이 없음). **메모리**: 같은 실행들의 최대 RSS, macOS/WSL2만 — Windows의 `Process.PeakWorkingSet64`는 짧게 실행되는 이 프로세스들에서 새로고침 후에도 계속 0을 반환했습니다(원인이 해결되지 않은 API상의 특이 동작). 그래서 신뢰할 수 없는 값을 보여주는 대신 Windows 메모리는 전체적으로 생략했습니다.
- **정확성**: 결과에 포함되기 전에 모든 바이너리의 출력을 해당 워크로드의 알려진 정답과 비교해 검증했습니다.
- 사용된 모든 소스 파일은 결과 옆에 인라인으로 링크되어 있습니다.

## fib(37) — 재귀 피보나치 {#fib37-recursive-fibonacci}

단순 재귀 피보나치 — 순수한 함수 호출 및 정수 산술 오버헤드만 있으며, 할당도 I/O도 없습니다.

| 언어 | 디버그 | 릴리스 | 릴리스 최대 메모리 | 릴리스 컴파일 시간 |
|---|---|---|---|---|
| SafeC | 0.14s | 0.08s | **1.3 MB (가장 가벼움)** | 0.13s |
| C | 0.13s | **0.07s (가장 빠름)** | **1.3 MB (가장 가벼움)** | 0.09s |
| C++ | 0.13s | **0.07s (가장 빠름)** | **1.3 MB (가장 가벼움)** | 0.09s |
| Rust | 0.18s | 0.09s | 1.5 MB | 0.18s |
| Zig | 0.16s | 0.08s | 1.4 MB | 5.60s |
| Go | **0.11s (가장 빠름)** | 0.08s | 4.0 MB | 0.08s |
| Python | 2.70s | 2.70s | 14.5 MB | N/A (인터프리트) |

**릴리스, 플랫폼 간 비교:**

| 언어 | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.080s | **0.040s (가장 빠름)** | 0.046s |
| C | 0.070s | **0.040s (가장 빠름)** | 0.046s |
| C++ | 0.070s | **0.040s (가장 빠름)** | 0.047s |
| Rust | 0.090s | **0.040s (가장 빠름)** | 0.049s |
| Zig | 0.080s | **0.040s (가장 빠름)** | 0.053s |
| Go | 0.080s | 0.080s | 0.092s |
| Python | 2.700s | 3.330s | 2.485s |

**출처:** [fib.sc](/benchmarks/fib/safec/fib.sc) · [fib.c](/benchmarks/fib/c/fib.c) · [fib.cpp](/benchmarks/fib/cpp/fib.cpp) · [fib.rs](/benchmarks/fib/rust/fib.rs) · [fib.zig](/benchmarks/fib/zig/fib.zig) · [fib.go](/benchmarks/fib/go/fib.go) · [fib.py](/benchmarks/fib/python/fib.py)

## n-body — 5체 궤도 시뮬레이션 {#n-body-5-body-orbital-simulation}

Benchmarks Game의 고전적인 n-body 테스트(태양/목성/토성/천왕성/해왕성), 2,000,000 스텝. 부동소수점 연산이 많고, 할당은 없으며, 작업 집합이 작습니다.

| 언어 | 디버그 | 릴리스 | 릴리스 최대 메모리 | 릴리스 컴파일 시간 |
|---|---|---|---|---|
| SafeC | 0.63s | 0.11s | **1.3 MB (가장 가벼움)** | 0.13s |
| C | 0.42s | **0.10s (가장 빠름)** | 1.3 MB | 0.10s |
| C++ | 0.41s | 0.11s | **1.3 MB (가장 가벼움)** | 0.12s |
| Rust | 0.70s | 0.11s | 1.5 MB | 0.16s |
| Zig | 0.56s | 0.11s | 1.5 MB | 5.69s |
| Go | **0.28s (가장 빠름)** | **0.10s (가장 빠름)** | 4.1 MB | 0.09s |
| Python | 9.89s | 9.89s | 15.2 MB | N/A (인터프리트) |

**릴리스, 플랫폼 간 비교:**

| 언어 | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC | 0.110s | **0.090s (가장 빠름)** | 0.102s |
| C | 0.100s | **0.090s (가장 빠름)** | 0.103s |
| C++ | 0.110s | **0.090s (가장 빠름)** | 0.104s |
| Rust | 0.110s | 0.100s | 0.113s |
| Zig | 0.110s | 0.110s | 0.116s |
| Go | 0.100s | 0.100s | 0.113s |
| Python | 9.890s | 12.150s | 11.922s |

**출처:** [nbody.sc](/benchmarks/nbody/safec/nbody.sc) · [nbody.c](/benchmarks/nbody/c/nbody.c) · [nbody.cpp](/benchmarks/nbody/cpp/nbody.cpp) · [nbody.rs](/benchmarks/nbody/rust/nbody.rs) · [nbody.zig](/benchmarks/nbody/zig/nbody.zig) · [nbody.go](/benchmarks/nbody/go/nbody.go) · [nbody.py](/benchmarks/nbody/python/nbody.py)

## binary-trees — 할당/해제 스트레스 테스트 {#binary-trees-allocationdeallocation-stress}

작은 이진 트리 수백만 개를 만들고 버립니다(최대 깊이 18) — 산술이 아니라 메모리 관리 방식을 시험하는 벤치마크입니다. SafeC는 `std::alloc`/힙 대신 `region`/`arena<R>`(포인터 증가 방식 할당, `arena_reset<R>()`이 리전 전체를 O(1)에 폐기)를 사용합니다 — 이 워크로드는 전부 수명이 짧고 같은 스코프 안에서 끝나는 할당이라, 리전이 정확히 위해 만들어진 상황입니다.

| 언어 | 디버그 | 릴리스 | 릴리스 최대 메모리 | 릴리스 컴파일 시간 |
|---|---|---|---|---|
| SafeC | **0.29s (가장 빠름)** | **0.21s (가장 빠름)** | 25.4 MB | 0.14s |
| C | 1.67s | 1.56s | **17.4 MB (가장 가벼움)** | 0.10s |
| C++ | 1.87s | 1.86s | **17.4 MB (가장 가벼움)** | 0.10s |
| Rust | 3.16s | 1.74s | 17.6 MB | 0.16s |
| Zig | 4.85s | 1.57s | 17.6 MB | 5.28s |
| Go | 2.52s | 1.26s | 40.8 MB | 0.09s |
| Python | 20.93s | 20.93s | 87.0 MB | N/A (인터프리트) |

참고로 SafeC의 순수 힙 버전(아레나 대신 `std::alloc`): 릴리스 1.15초, 최대 25.4MB — 아레나가 여기서 약 5.5배 빠르고 메모리도 약 23% 적게 씁니다.

**릴리스, 플랫폼 간 비교** (공정한 비교를 위해 SafeC는 세 플랫폼 모두 아레나 사용):

| 언어 | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC (아레나) | 0.210s | **0.130s (가장 빠름)** | 0.147s |
| C | 1.560s | 0.820s | 1.965s |
| C++ | 1.860s | 0.930s | 2.025s |
| Rust | 1.740s | 0.900s | 2.184s |
| Zig | 1.570s | 0.880s | 2.020s |
| Go | 1.260s | 1.150s | 1.125s |
| Python | 20.930s | 20.130s | 10.019s |

SafeC 순수 힙 버전, 동일 세 플랫폼: macOS 1.150s, WSL2 **0.620s (가장 빠름)**, Windows 1.119s.

**출처:** [binarytrees.sc](/benchmarks/binarytrees/safec/binarytrees.sc) · [binarytrees_arena.sc](/benchmarks/binarytrees/safec/binarytrees_arena.sc) · [binarytrees.c](/benchmarks/binarytrees/c/binarytrees.c) · [binarytrees.cpp](/benchmarks/binarytrees/cpp/binarytrees.cpp) · [binarytrees.rs](/benchmarks/binarytrees/rust/binarytrees.rs) · [binarytrees.zig](/benchmarks/binarytrees/zig/binarytrees.zig) · [binarytrees.go](/benchmarks/binarytrees/go/binarytrees.go) · [binarytrees.py](/benchmarks/binarytrees/python/binarytrees.py)

## 컬렉션 — std::collections 처리량 (1,000,000개 원소) {#collections-stdcollections-throughput-1000000-elements}

| 연산 | 처리량 |
|---|---|
| `bst_insert` | 초당 1,577,110회 |
| `list_push_back` | 초당 65,427,899회 |
| `map_insert` | 초당 4,365,783회 |
| `map_get` | 초당 8,468,404회 |

[bench_collections.sc](/benchmarks/collections/safec/bench_collections.sc)

## 멀티스레드 — binary-trees, 8스레드 {#multithreaded-binary-trees-8-threads}

동일한 binary-trees 워크로드를 8개 워커 스레드로 병렬화 — 각 스레드가 특정 깊이의 트리 개수 중 독립된 구간을 만들고 체크섬을 계산한 뒤, 다음 깊이로 넘어가기 전에 조인합니다. 릴리스만. SafeC: 스레드마다 `region`/`arena<R>` 하나씩(아레나 상태는 공유/잠금이 없으므로 스레드마다 자기 것이 필요) — macOS에서 순수 힙 버전과 비교하면 0.68s → 0.09s(약 7.5배).

| 언어 | macOS 8스레드 시간 | 최대 메모리 | 싱글 스레드 대비 |
|---|---|---|---|
| SafeC | **0.09s (가장 빠름)** | 89.7 MB | 2.33배 |
| C | 0.61s | 72.3 MB | 2.56배 |
| C++ | 0.63s | 72.5 MB | 2.95배 |
| Rust | 0.68s | **72.2 MB (가장 가벼움)** | 2.56배 |
| Zig | 0.54s | 74.1 MB | 2.91배 |
| Go | 0.44s | 144.2 MB | 2.86배 |
| Python | 24.43s | 372.0 MB | 0.86배 (싱글 스레드보다 느림) |

**8스레드 시간, 플랫폼 간 비교** (SafeC는 세 플랫폼 모두 아레나 사용):

| 언어 | macOS (M1 Pro) | WSL2 (7800X3D) | Windows (7800X3D) |
|---|---|---|---|
| SafeC (아레나) | 0.090s | 0.075s | **0.045s (가장 빠름)** |
| C | 0.610s | 0.274s | 0.549s |
| C++ | 0.630s | 0.290s | 0.566s |
| Rust | 0.680s | 0.319s | 0.591s |
| Zig | 0.540s | 0.280s | 0.563s |
| Go | 0.440s | 0.358s | 0.320s |
| Python | 24.430s | 26.626s | 10.055s |

SafeC 순수 힙 버전: macOS 0.680s, WSL2 **0.308s (가장 빠름)**, Windows 0.367s.

**출처:** [binarytrees_mt.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt.sc) · [binarytrees_mt_arena.sc](/benchmarks/binarytrees_mt/safec/binarytrees_mt_arena.sc) · [binarytrees_mt.c](/benchmarks/binarytrees_mt/c/binarytrees_mt.c) · [binarytrees_mt.cpp](/benchmarks/binarytrees_mt/cpp/binarytrees_mt.cpp) · [binarytrees_mt.rs](/benchmarks/binarytrees_mt/rust/binarytrees_mt.rs) · [binarytrees_mt.zig](/benchmarks/binarytrees_mt/zig/binarytrees_mt.zig) · [binarytrees_mt.go](/benchmarks/binarytrees_mt/go/binarytrees_mt.go) · [binarytrees_mt.py](/benchmarks/binarytrees_mt/python/binarytrees_mt.py)

## SIMD — 2천만 개 f64 값의 제곱합 {#simd-sum-of-squares-over-20000000-f64-values}

순수 스칼라 루프와 각 언어의 명시적 벡터 타입을 `-O2`/릴리스에서 비교 — 백엔드의 자동 벡터화가 스칼라 루프에 이미 해주는 것 "위에" 명시적 SIMD가 추가로 벌어주는 것이 무엇인지를 가려냅니다. "벡터화 대 일부러 망가뜨린 코드"의 비교가 아닙니다. SafeC: 네이티브 `vec<double,4>` 타입(LLVM의 타깃에 무관한 `FixedVectorType`으로 낮춰지므로 아키텍처별 소스가 따로 필요 없음). C/C++: GCC/Clang 벡터 확장. Zig: `@Vector`. Rust: macOS에서는 stable 채널의 AArch64 NEON 인트린식(`std::simd`는 nightly 필요) — 아래 WSL2/Windows 열은 이 비교를 위해 새로 작성한 별도의 x86_64/SSE2 소스([simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs))를 씁니다. Go는 이식 가능한 SIMD 타입이 없어 스칼라만 있습니다.

| 언어 | macOS 스칼라 | macOS 명시적 SIMD | 배속 |
|---|---|---|---|
| SafeC | 0.04s | **0.03s (가장 빠름)** | 1.33배 |
| C | 0.04s | **0.03s (가장 빠름)** | 1.33배 |
| C++ | 0.04s | **0.03s (가장 빠름)** | 1.33배 |
| Rust | 0.04s | **0.03s (가장 빠름)** | 1.33배 |
| Zig | 0.04s | **0.03s (가장 빠름)** | 1.33배 |
| Go | 0.06s | N/A | — |
| Python | 3.50s | N/A (NumPy 사용 시 0.14s, 25.00배) | — |

**플랫폼 간 비교** — 이 벤치마크는 모든 언어에서 Apple Silicon이 큰 차이로 압승합니다. 이 특정 메모리 바운드 마이크로벤치마크가 이 하드웨어에서 낸 실제 결과로 받아들이되, "M1이 Ryzen을 이긴다"는 일반적 주장으로 확대 해석하지는 마세요. 절대 시간(수십 밀리초)이 작다 보니 다른 벤치마크보다 프로세스 시작 노이즈가 상대적으로 더 크게 반영됩니다.

| 언어 | 스칼라: macOS / WSL2 / Windows | 명시적 SIMD: macOS / WSL2 / Windows |
|---|---|---|
| SafeC | **0.040s (가장 빠름)** / 0.099s / 0.067s | **0.030s (가장 빠름)** / 0.092s / 0.060s |
| C | **0.040s (가장 빠름)** / 0.099s / 0.077s | **0.030s (가장 빠름)** / 0.092s / 0.060s |
| C++ | **0.040s (가장 빠름)** / 0.101s / 0.073s | **0.030s (가장 빠름)** / 0.092s / 0.073s |
| Rust | **0.040s (가장 빠름)** / 0.099s / 0.044s | **0.030s (가장 빠름)** / 0.093s / 0.039s |
| Zig | **0.040s (가장 빠름)** / 0.096s / 0.065s | **0.030s (가장 빠름)** / 0.089s / 0.069s |
| Go | 0.060s / 0.105s / 0.074s | N/A |
| Python | 3.500s / 3.144s / 3.406s | 0.140s / 0.213s / 0.313s (NumPy) |

**출처:** [simd_scalar.sc](/benchmarks/simd/safec/simd_scalar.sc) · [simd_vec.sc](/benchmarks/simd/safec/simd_vec.sc) · [simd_scalar.c](/benchmarks/simd/c/simd_scalar.c) · [simd_vec.c](/benchmarks/simd/c/simd_vec.c) · [simd_scalar.cpp](/benchmarks/simd/cpp/simd_scalar.cpp) · [simd_vec.cpp](/benchmarks/simd/cpp/simd_vec.cpp) · [simd_scalar.rs](/benchmarks/simd/rust/simd_scalar.rs) · [simd_vec.rs](/benchmarks/simd/rust/simd_vec.rs)(macOS/NEON) · [simd_vec_x86_64.rs](/benchmarks/simd/rust/simd_vec_x86_64.rs)(WSL2/Windows/SSE2) · [simd_scalar.zig](/benchmarks/simd/zig/simd_scalar.zig) · [simd_vec.zig](/benchmarks/simd/zig/simd_vec.zig) · [simd_scalar.go](/benchmarks/simd/go/simd_scalar.go) · [simd_numpy.py](/benchmarks/simd/python/simd_numpy.py) · [simd_scalar.py](/benchmarks/simd/python/simd_scalar.py)

## 웹 서비스 — JSON "hello world" 엔드포인트 {#web-service-json-hello-world-endpoint}

`{"message":"Hello, World!"}`를 반환하는 `GET /` — TechEmpower의 "JSON serialization" 테스트와 같은 형태입니다. macOS/WSL2에서는 Apache Bench(`ab -n 20000 -c 50`, keep-alive 없음)로 각 언어 고유의 HTTP 서빙 방식을 측정합니다: SafeC의 `std::http_serve_reactor`; C/C++/Zig의 최소한의 raw-socket accept 루프(macOS 전용 — Winsock으로는 아직 이식되지 않음); Go의 `net/http`; Python의 FastAPI+uvicorn; Rust의 **axum**(Dioxus가 아님 — Dioxus의 풀스택 서버 레이어는 내부적으로 axum이므로, axum을 직접 측정하는 것이 Dioxus의 요청이 실제로 거쳐 가는 경로를 재는 것과 같습니다). Windows에는 `ab`가 없어서, SafeC/Go/Rust/Python은 그 대신 작은 커스텀 Go 로드 제너레이터를 `-n 5000 -c 20` 규모로 사용합니다(더 작은 규모인 이유: 같은 포트로 빠르게 재시작하면 Windows의 긴 `TIME_WAIT`에 걸립니다). Windows 열은 `ab` 수치와 직접 비교하지 말고, 그 열 안에서의 상대적 크기로만 보세요.

| 언어 | macOS req/s (p50/p99) | WSL2 req/s (p50/p99) | Windows req/s (p50/p99) | macOS 최대 메모리 |
|---|---|---|---|---|
| SafeC | **36055 (가장 빠름)** (1/5ms) | 17650 (3/4ms) | 2313 (7.5/19.6ms) | 7.2 MB |
| C | 25303 (2/2ms) | N/A | N/A | **1.4 MB (가장 가벼움)** |
| C++ | 24148 (2/4ms) | N/A | N/A | 1.6 MB |
| Rust | 27270 (2/2ms) | 18077 (3/3ms) | 465 (33.0/474.3ms) | 3.4 MB |
| Zig | 25742 (2/2ms) | N/A | N/A | 1.5 MB |
| Go | 25274 (2/3ms) | **18758 (가장 빠름)** (3/4ms) | **2467 (가장 빠름)** (7.8/16.9ms) | 19.7 MB |
| Python | 4790 (10/33ms) | 2357 (21/27ms) | 528 (37.3/52.5ms) | 54.9 MB |

모든 언어가 모든 플랫폼에서 개별 측정 시 요청 실패 없이 전부 완료했습니다.

**출처:** [server.sc](/benchmarks/webservice/safec/server.sc) · [server_reactor.sc](/benchmarks/webservice/safec/server_reactor.sc) · [server.c](/benchmarks/webservice/c/server.c) · [server.cpp](/benchmarks/webservice/cpp/server.cpp) · [main.rs](/benchmarks/webservice/rust/src/main.rs) · [server.zig](/benchmarks/webservice/zig/server.zig) · [server.go](/benchmarks/webservice/go/server.go) · [server.py](/benchmarks/webservice/python/server.py) · [io_nb_bsd.sc](/benchmarks/stdlib/io_nb_bsd.sc) · [io_nb.h](/benchmarks/stdlib/io_nb.h) · [task.h](/benchmarks/stdlib/task.h)

## 머신러닝 — 소형 MLP, 학습과 추론 {#machine-learning-small-mlp-training-and-inference}

2계층 MLP(`relu(X @ W1) @ W2`, 128→256→64, 배치 64, MSE 손실, 직접 작성한 SGD, 바이어스 없음) — 학습 100스텝 후 추론 전용 1000회 패스, 고정 시드. `std::ml`은 CPU(Accelerate BLAS)와 MPS를 다룹니다(이 크기는 GPU가 CPU BLAS를 이기기엔 너무 작습니다, SafeC도 마찬가지 — MPS 행은 경쟁을 위해서가 아니라 완전성을 위해 포함했습니다).

| 프레임워크 | 디바이스 | 학습 (100스텝) | 처리량 | 추론 (1000회) | 손실 (정합성 확인) |
|---|---|---|---|---|---|
| SafeC (Accelerate BLAS) | CPU | **16.8ms (가장 빠름)** | 초당 380499개 | 26.1ms | 100.333420 |
| SafeC | MPS | 39.4ms | 초당 162581개 | 61.1ms | 100.333290 |
| PyTorch | CPU | 23.1ms | 초당 277232개 | **13.5ms (가장 빠름)** | 100.018272 |
| PyTorch | MPS | 131.6ms | 초당 48649개 | 358.7ms | 103.423889 |
| TensorFlow | CPU | 83.0ms | 초당 77103개 | 245.0ms | 106.182991 |
| TensorFlow | GPU | 245.3ms | 초당 26086개 | 924.6ms | 106.182999 |
| MLX | GPU | 72.2ms | 초당 88685개 | 309.1ms | 96.632431 |

[tensor_blas.h](/benchmarks/stdlib/tensor_blas.h) · [tensor_blas.sc](/benchmarks/stdlib/tensor_blas.sc) · [train_blas.sc](/benchmarks/ml/safec/train_blas.sc) · [tensor.h](/benchmarks/stdlib/tensor.h) · [tensor.sc](/benchmarks/stdlib/tensor.sc) · [tensor_gpu.h](/benchmarks/stdlib/tensor_gpu.h) · [tensor_gpu.sc](/benchmarks/stdlib/tensor_gpu.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [gen_mps_metallib.sh](/benchmarks/stdlib/gen_mps_metallib.sh) · [time.sc](/benchmarks/stdlib/time.sc) · [time.h](/benchmarks/stdlib/time.h) · [train.sc](/benchmarks/ml/safec/train.sc) · [train_gpu_small.sc](/benchmarks/ml/safec/train_gpu_small.sc) · [PyTorch train.py](/benchmarks/ml/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml/tensorflow/train.py) · [MLX train.py](/benchmarks/ml/mlx/train.py)

## 머신러닝, 더 큰 모델 — 512→1024→256 {#machine-learning-bigger-model-512-1024-256}

같은 형태를 약 50배로 키운 것(배치 128). 학습 50스텝, 추론 200회 패스.

| 프레임워크 | 디바이스 | 학습 (50스텝) | 처리량 | 추론 (200회) | 손실 (정합성 확인) |
|---|---|---|---|---|---|
| SafeC (Accelerate BLAS) | CPU | 89.2ms | 초당 71713개 | 61.4ms | 95.428856 |
| SafeC | MPS | **47.9ms (가장 빠름)** | 초당 133433개 | **47.3ms (가장 빠름)** | 95.428223 |
| PyTorch | CPU | 67.8ms | 초당 94440개 | 48.2ms | 106.548233 |
| PyTorch | MPS | 110.5ms | 초당 57926개 | 86.4ms | 108.710899 |
| TensorFlow | CPU | 148.8ms | 초당 43003개 | 197.6ms | 316.556885 |
| TensorFlow | GPU | 154.1ms | 초당 41539개 | 218.3ms | 317.200012 |
| MLX | GPU | 54.3ms | 초당 117953개 | 74.4ms | 88.762100 |

[gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [tensor_gpu.h](/benchmarks/stdlib/tensor_gpu.h) · [tensor_gpu.sc](/benchmarks/stdlib/tensor_gpu.sc) · [train_cpu.sc](/benchmarks/ml_big/safec/train_cpu.sc) · [train_cpu_blas.sc](/benchmarks/ml_big/safec/train_cpu_blas.sc) · [train_gpu.sc](/benchmarks/ml_big/safec/train_gpu.sc) · [PyTorch train.py](/benchmarks/ml_big/pytorch/train.py) · [TensorFlow train.py](/benchmarks/ml_big/tensorflow/train.py) · [MLX train.py](/benchmarks/ml_big/mlx/train.py)

## 머신러닝, GPU 백엔드 — CUDA, ROCm, Vulkan/SPIR-V, WebGPU {#machine-learning-gpu-backends-cuda-rocm-vulkanspir-v-webgpu}

이 머신에는 NVIDIA/AMD GPU도, CUDA/ROCm 툴체인도, Vulkan SDK도, WebGPU 라이브러리도 없습니다 — **이 절의 수치는 하나도 실측되지 않았습니다.** 모든 함수는 실제 벤더 C ABI에 맞춰 직접 작성되었고 `safec` 아래에서 타입 검사는 통과하지만, 이 환경에서는 링크도 실행도 할 수 없습니다 — "맞을 것"이지 "맞다고 확인됨"은 아닙니다. 백엔드마다 막히는 지점이 다릅니다:

| 백엔드 | 원소별 연산 | Matmul (단순 커널) | Matmul (벤더 BLAS) | 막히는 이유 |
|---|---|---|---|---|
| CUDA | 실제 동작(PTX, 텍스트 IR) | 실제 동작(PTX) | 실제 동작(cuBLAS) | NVIDIA GPU가 없을 뿐 |
| ROCm | 항상 0 반환 | 항상 0 반환 | 실제 동작(rocBLAS) | HSACO는 바이너리 IR이라 커널 이미지를 컴파일할 ROCm 툴체인이 없음 |
| Vulkan/SPIR-V | 항상 0 반환 | 항상 0 반환 | 해당 없음 | SPIR-V는 바이너리 IR이라 컴파일할 glslc/glslangValidator가 없음 |
| WebGPU | 실제 동작(WGSL, 텍스트 IR) | 실제 동작(WGSL) | 해당 없음 | wgpu-native/Dawn 라이브러리가 없을 뿐 |

[gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gpu_rocm.h](/benchmarks/stdlib/gpu_rocm.h) · [gpu_rocm.sc](/benchmarks/stdlib/gpu_rocm.sc) · [gpu_spirv.h](/benchmarks/stdlib/gpu_spirv.h) · [gpu_spirv.sc](/benchmarks/stdlib/gpu_spirv.sc) · [gpu_webgpu.h](/benchmarks/stdlib/gpu_webgpu.h) · [gpu_webgpu.sc](/benchmarks/stdlib/gpu_webgpu.sc) · [tensor_cuda.h](/benchmarks/stdlib/tensor_cuda.h) · [tensor_cuda.sc](/benchmarks/stdlib/tensor_cuda.sc) · [tensor_rocm.h](/benchmarks/stdlib/tensor_rocm.h) · [tensor_rocm.sc](/benchmarks/stdlib/tensor_rocm.sc)

## 머신러닝, fp16 / bf16 지원 {#machine-learning-fp16-bf16-support}

SafeC의 타입 시스템에는 네이티브 16비트 부동소수점이 없습니다(`Type.h`의 `TypeKind`는 `Float32`/`Float64`만 있음 — 새 타입을 추가하려면 렉서, 파서, sema의 산술 승격 규칙, LLVM 코드 생성까지 건드려야 하는 진짜 컴파일러 기능이지, 표준 라이브러리만의 변경이 아닙니다). 대신 컴파일러 네이티브 half 타입이 없을 때 쓰는 표준적인 방법을 사용합니다: fp16/bf16 값을 `unsigned short`에 원시 비트로 저장하고, `float`과의 명시적이고 정확히 반올림되는(round-to-nearest-even) 변환을 제공합니다 — 그리고 MPS 백엔드에서는 저장 공간만 절반으로 줄인 게 아니라 진짜 네이티브 `half`/`bfloat` GPU 연산을 씁니다.

| 검사 | 결과 |
|---|---|
| 알려진 비트 패턴(fp16: 1.0, -1.0, 0.0, -0.0, 2.0, 0.5, 최대 정규값 65504, 오버플로→inf, 최소 비정규값, inf; bf16: 1.0, -1.0, 0.0, 2.0, π) | 모두 정확히 일치 |
| fp16 비정규값 항등성 스윕(가수부 1–1023 전체, `fp16→f32→fp16`) | 1023/1023 정확히 일치 |
| fp16 정규 범위 항등성 스윕(모든 지수부 × 샘플링한 가수부, 330개 패턴) | 330/330 정확히 일치 |
| fp16 표현 가능한 값의 라운드트립 | 정확히 일치 |
| bf16 라운드트립(가수부 7비트라 구조적으로 손실 있음) | 예상대로 상대 오차 약 0.4% 이내 |

[float16.h](/benchmarks/stdlib/float16.h) · [float16.sc](/benchmarks/stdlib/float16.sc) · [gpu_mps.h](/benchmarks/stdlib/gpu_mps.h) · [gpu_mps.sc](/benchmarks/stdlib/gpu_mps.sc) · [gpu_mps_kernels.metal](/benchmarks/stdlib/gpu_mps_kernels.metal) · [gpu_cuda.h](/benchmarks/stdlib/gpu_cuda.h) · [gpu_cuda.sc](/benchmarks/stdlib/gpu_cuda.sc) · [gpu_webgpu.h](/benchmarks/stdlib/gpu_webgpu.h) · [gpu_webgpu.sc](/benchmarks/stdlib/gpu_webgpu.sc) · [train_gpu_f16.sc](/benchmarks/ml/safec/train_gpu_f16.sc) · [train_gpu_bf16.sc](/benchmarks/ml/safec/train_gpu_bf16.sc)

## 머신러닝, 디바이스 선택 {#machine-learning-device-selection}

모든 백엔드가 자신의 연산 이름을 명시적으로 씁니다(`tensor_matmul` 대 `_blas` 대 `_gpu` 대 `_cuda`) — 정확하지만, 디바이스를 한 번만 고르고 싶은 호출자 입장에서는 번거롭습니다. 대신 `tensor_matmul_on(a, b, device)` / `tensor_relu_on(a, device)`가 `Device` 열거형으로 분기합니다 — 이 샌드박스에서 실행 가능한 모든 백엔드(CPU, CPU+BLAS, MPS)에서 비트 단위로 동일함을 검증했습니다:

| 디바이스 | Y[0][0] | Y[0][1] |
|---|---|---|
| CPU | 0.900000 | 1.300000 |
| CPU + BLAS | 0.900000 | 1.300000 |
| MPS | 0.900000 | 1.300000 |

이 작업 중 실제 이름 충돌을 하나 발견했습니다: `activations.sc`(순전파 전용 연산)와 `tensor_nn.sc`(모든 GPU 백엔드에 필요한 오토그라드 연산) 둘 다 `tensor_sigmoid`/`tensor_relu` 등을 정의하고 있어서, 어떤 프로그램도 둘 다 링크할 수 없었고, `activations.sc` 기반 레이어는 GPU 백엔드를 쓸 수 없었습니다. `activations.h`의 순전파 전용 함수 이름에 `_fwd` 접미사를 붙이고 `attention.sc`/`transformer.sc`/`rnn.sc`의 호출부를 갱신해서 고쳤습니다.

`jit_block_forward_on(block, x, device)`는 실제 레이어(JiTBlock의 Q/K/V/출력 프로젝션 + FFN)를 통해 디바이스 선택을 전달합니다 — CPU/CPU+BLAS/MPS 전체에서 기준값과 비트 단위로 동일함을 검증했습니다:

| 경로 | y[0] | y[1] | y[2] |
|---|---|---|---|
| `jit_block_forward` (기준) | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_CPU_BLAS)` | -0.479727 | -0.589425 | -0.537808 |
| `..._on(DEVICE_MPS)` | -0.479727 | -0.589425 | -0.537808 |

아직 디바이스 라우팅이 안 된 것: attention 내부의 QK^T/softmax/·V 행렬곱, `DiTBlock`, `cnn.sc`.

[tensor_dispatch.h](/benchmarks/stdlib/tensor_dispatch.h) · [tensor_dispatch.sc](/benchmarks/stdlib/tensor_dispatch.sc) · [transformer_dispatch.h](/benchmarks/stdlib/transformer_dispatch.h) · [transformer_dispatch.sc](/benchmarks/stdlib/transformer_dispatch.sc) · [activations.h](/benchmarks/stdlib/activations.h) · [activations.sc](/benchmarks/stdlib/activations.sc) · [attention.h](/benchmarks/stdlib/attention.h) · [attention.sc](/benchmarks/stdlib/attention.sc) · [transformer.h](/benchmarks/stdlib/transformer.h) · [transformer.sc](/benchmarks/stdlib/transformer.sc)

## 메모리 할당 — `std::alloc`/`dealloc`이 순수 `malloc`/`free`보다 느릴까요? {#memory-allocation-is-stdallocdealloc-slower-than-raw-mallocfree}

`std::alloc`은 크기 클래스별로 캐시하는 할당자입니다(PyTorch의 CPU/CUDA 캐싱 할당자, MLX의 Metal 버퍼 캐시와 같은 발상): 해제된 블록은 2의 거듭제곱 크기 클래스별로 나뉜 스레드-로컬 프리 리스트로 들어가고, 같은 클래스의 다음 `alloc()` 요청은 `malloc()`/`free()`를 전혀 거치지 않고 바로 거기서 처리됩니다. 이중 해제/UAF(use-after-free) 탐지에는 영향이 없습니다(캐시된 블록도 재사용되기 전까지는 "해제됨" 매직 워드를 그대로 갖고 있습니다).

`std::alloc`/`dealloc`은 작고 크기가 같은 수명이 짧은 할당이 많은 binarytrees 형태의 워크로드에서 순수 `malloc`/`free`보다 실제로 *더 빠릅니다*(1210ms 대 1518ms, 약 20% 빠름). alloc/free를 번갈아 호출하는 마이크로벤치마크에서는 약 3.3배 빠릅니다(호출당 11–12ns 대 37–38ns). `region`/`arena<R>`는 힙보다 더 빠릅니다(약 5.9배).

[mem.h](/benchmarks/stdlib/mem.h) · [mem.sc](/benchmarks/stdlib/mem.sc)
