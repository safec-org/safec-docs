# 네이티브 SIMD (`vec<T, N>`)

`vec<T, N>`는 일급 언어 타입입니다 — `T` 타입의 원소 `N`개로 이루어진
고정폭 벡터로, LLVM의 `<N x T>` 벡터 IR로 직접 낮춰집니다(lower). 기본적인
사용을 위해 따로 배워야 할 인트린식 기반 API는 없습니다 — 일반적인 산술
연산자와 첨자(subscript) 문법이 이미 그대로 동작합니다.

```c
vec<float, 4> a = {1.0, 2.0, 3.0, 4.0};
vec<float, 4> b = {10.0, 20.0, 30.0, 40.0};
vec<float, 4> c = a + b;      // 원소별 덧셈
vec<float, 4> d = c * b;      // 원소별 곱셈

float first = c[0];           // extractelement — 레인 하나를 읽음
c[2] = 999.0;                 // insertelement — 레인 하나에 씀
```

`c[0]`은 (벡터의 원소 타입인) `float`입니다 — 이를 `double`에 대입하면
암묵적으로 확장(widen)되는데, 이는 다른 모든 수치 타입에 적용되는 것과
동일한 안전한 작은-타입-에서-큰-타입으로의 변환입니다([타입](/ko/reference/types#type-conversions)
참고): `double first = c[0];`는 캐스트 없이 컴파일되며 정확한 값을
그대로 읽어옵니다.

`T`는 어떤 정수 또는 부동소수점 기본 타입도 될 수 있고, `N`은 컴파일
타임 상수여야 합니다. "적당한" 하드웨어 폭으로 제한되지 않습니다 —
`vec<int, 3>`도 유효합니다 — 하지만 [`std::simd`](/ko/stdlib/simd)의
ISA별 편의 계층은 실제 벡터 레지스터에 깔끔하게 대응되는 폭을 선택합니다
(128비트 SSE/NEON/SIMD128, 256비트 AVX2, ...).

## 무엇이 무엇으로 컴파일되는가 {#what-compiles-to-what}

컴파일러에는 아키텍처에 종속된 것이 아무것도 하드코딩되어 있지 않습니다 —
`vec<T,N>`는 이식 가능한 LLVM 벡터 IR로 낮춰지고, 각 타깃의 자체
백엔드가 스칼라 산술과 정확히 마찬가지로 거기서부터 명령어 선택을
수행합니다. 컴파일러가 지원하는 모든 백엔드에서 실제로 생성된 코드를
대상으로 검증되었습니다:

| 타깃 | `vec<float,4> + vec<float,4>`가 무엇이 되는가 |
|---|---|
| x86_64 (SSE2 기본) | `addps` (단일 명령어) |
| AArch64 / 32비트 ARM (NEON) | `fadd v0.4s, v0.4s, v1.4s` |
| RISC-V (`+v` 확장) | `vsetivli` + `vfadd.vv` |
| WebAssembly (SIMD128) | `f32x4.add` |
| SPIR-V | `%v4float`에 대한 `OpFAdd` |
| ARM Cortex-M55/M85 (MVE, `+mve`) | `vadd.i32 q2, q0, q1` |
| CUDA (NVPTX) / ROCm (AMDGPU) | 스칼라 레인 연산 4개 — 아래 설명 참고 |

**GPU 타깃은 스칼라화됩니다.** PTX와 GCN에는 SSE/NEON처럼 "packed float
4개를 한 명령어로 더한다"는 개념이 없습니다 — GPU의 병렬성은 넓은 레지스터가
아니라 락스텝으로 실행되는 다수의 스레드(워프/웨이브프론트)에서
나옵니다. `vec<float,4>`는 그곳에서도 정확하게 동작하지만, 하나의 넓은
연산이 아니라 네 개의 독립적인 스칼라 연산으로 컴파일될 뿐입니다 —
모든 타깃에서 단일 명령어 처리량을 보장하는 것이 아니라, 관련된 값을
묶는 편리한 수단일 뿐입니다.

**ARM Cortex-M4/M7의 DSP 확장은 별개의 이야기입니다.** 이 확장의
packed-SIMD 명령어(`SADD16`, `SMLAD`, `USAD8`, ...)는 16비트 레인
2개 또는 8비트 레인 4개를 하나의 32비트 *스칼라* 레지스터에 채워
넣는데, 이는 실제 벡터 레지스터 파일과는 근본적으로 다른 모델이며
LLVM은 `vec<T,N>` IR을 이런 명령어로 자동 벡터화하지 않습니다. 이들은
`std::dsp_*` 함수로 별도로 노출됩니다 — [베어메탈](/ko/reference/baremetal#dsp-extension-cortex-m4-m7)
참고.

## 복합 초기화자 {#compound-initializers}

벡터 초기화자는 정확히 `N`개의 위치 기반 값만 받습니다 — 구조체/배열의
복합 초기화자와 달리 지정자(designator)는 허용되지 않습니다:

```c
vec<int, 8> ints = {1, 2, 3, 4, 5, 6, 7, 8};
```

전역 벡터의 초기화자는 다른 전역 변수와 마찬가지로 여전히 컴파일
타임 상수여야 합니다:

```c
vec<float, 4> global_v = {10.0, 20.0, 30.0, 40.0};   // 괜찮음 — 리터럴이 접혀서(fold) 상수가 됨
```

## 첨자 시맨틱 {#subscript-semantics}

상수 인덱스로 `v[i]`를 읽는 것은 컴파일 타임에 경계 검사됩니다(범위를
벗어난 상수 인덱스는 컴파일 에러입니다). 런타임 인덱스는 `unsafe { }`
안이 아닌 한 다른 인덱싱 연산과 마찬가지로 경계 검사됩니다. 배열 원소와
달리 단일 레인은 메모리상에서 독립적으로 주소를 가질 수 없습니다 —
`v[i]`는 포인터 연산이 아니라, SSA로 존재하는 벡터 값 전체에 대한
`extractelement`/`insertelement`로 낮춰집니다.

## 포인터를 통한 로드와 스토어 {#loads-and-stores-through-pointers}

`vec<T, N>` 포인터를 역참조하면 `N`개 레인을 한 번에 읽거나 씁니다:

```c
unsafe {
    *(vec<float, 8>*)&y[i] = *(vec<float, 8>*)&y[i] + a * *(const vec<float, 8>*)&x[i];
}
```

이런 포인터는 보통 배열 안 원소의 주소에서 오며, 원소 크기로만 정렬되어 있습니다. 그래서 이 접근은 벡터 자체의 정렬(32)이 아니라 원소의 정렬(여기서는 4바이트)을 가정하고, 정렬되지 않은 벡터 로드/스토어로 컴파일됩니다. 컴파일러가 직접 배치하는 벡터 지역 변수와 전역 변수는 전체 정렬을 유지합니다.

## 빌트인 {#builtins}

| 빌트인 | 타입 | 낮춰지는 형태 |
|---|---|---|
| `__builtin_fmuladd(a, b, c)` | `float`, `double`, `vec<float/double, N>` (세 인자 모두 같은 타입) | 타깃에 FMA가 있으면 융합 곱셈-덧셈 하나, 없으면 곱셈과 덧셈 (`llvm.fmuladd`) |
| `__builtin_sqrt(x)` | `float`, `double`, `vec<float/double, N>` | IEEE 제곱근 (`llvm.sqrt`); 하드웨어 sqrt가 있으면 명령어 하나이며, (`sqrtf` 호출과 달리) 루프 안에서 벡터화됩니다 |

일반 `a * b + c`는 반올림이 달라지므로 절대 FMA로 축약되지 않습니다. 내적이나 행렬 커널처럼 융합해도 되는 곳에서 `__builtin_fmuladd`를 씁니다.

## ISA 매크로와 `--target-cpu` {#isa-macros-and-target-cpu}

컴파일러는 대상 CPU와 `--target-features`가 보장하는 기능에서 clang의 SIMD 기능 매크로를 정의합니다:

| 타깃 | 매크로 |
|---|---|
| x86 / x86-64 | `__SSE2__`, `__SSE3__`, `__SSSE3__`, `__SSE4_1__`, `__SSE4_2__`, `__AVX__`, `__AVX2__`, `__FMA__`, `__F16C__`, `__AVX512F__`, `__AVX512BW__`, `__AVX512DQ__`, `__AVX512VL__` |
| AArch64 | `__ARM_NEON`, `__ARM_FEATURE_SVE`, `__ARM_FEATURE_SVE2`, `__ARM_FEATURE_FP16_VECTOR_ARITHMETIC` |
| 32비트 ARM | `__ARM_NEON`, `__ARM_FEATURE_DSP`(Cortex-M4/M7/M33), `__ARM_FEATURE_MVE`(Helium: 1 = 정수, 3 = 정수와 부동소수점) |
| WebAssembly | `__wasm_simd128__` |

`--target-cpu=native`는 clang의 `-march=native`처럼 빌드 머신의 CPU와 그 모든 기능으로 빌드합니다. `--target-cpu=znver4`, `--target-cpu=skylake-avx512` 등으로 특정 CPU를 지정할 수도 있습니다. 둘 다 없으면 x86-64 코드는 베이스라인(SSE2)을 대상으로 하며, std의 핵심 커널은 대신 실행 시점에 ISA 수준을 고릅니다([CPU SIMD 수준](/ko/stdlib/simd#cpu-levels) 참고).

## `std::simd`와의 관계 {#relationship-to-stdsimd}

`vec<T,N>`는 컴파일러 수준의 빌딩 블록입니다. [`std::simd`](/ko/stdlib/simd)는
이 위에 완전히 구축된 이식 가능한 라이브러리로 — 타입 별칭(`f32x4`,
`i32x8`, ...), 벡터와 원시 포인터 간의 로드/스토어, 브로드캐스트,
수평(horizontal) 리덕션, 곱셈-덧셈 융합(FMA)을 제공하며, 여기에 더해
동일한 타입들을 아키텍처에 맞는 이름으로 다시 내보내기만 하는 얇은
ISA별 편의 헤더(`x86_64.h`, `aarch64.h`, `riscv.h`, `wasm.h`,
`spirv.h`, `cortex_m.h`, `cuda.h`, `rocm.h`)들이 있습니다. 타깃마다 별도로
손으로 작성된 SIMD 구현은 없습니다 — 전체 라이브러리는 백엔드마다 한 번씩
컴파일되는 동일한 이식 가능한 소스입니다.
