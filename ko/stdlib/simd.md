# SIMD (`std::simd`)

`std/simd/`는 컴파일러의 네이티브 벡터 타입인 [`vec<T, N>`](/ko/reference/simd)
위에 전적으로 구축된 이식 가능한 SIMD 라이브러리다 — 이 라이브러리
어디에도 명령어별로 직접 손으로 작성한 구현은 없다. 원소 단위 산술
연산과 레인 접근은 이미 일반적인 연산자/첨자 문법을 통해 모든
`vec<T,N>`에서 동작한다. 이 라이브러리는 그 위에 ISA에 구애받지 않는
편리한 타입 이름, 포인터 로드/스토어, 브로드캐스트, 수평 리덕션, 융합
곱셈-덧셈(FMA)을 추가한다.

```c
#include <std/simd/simd.h>

int main() {
    float src[4] = {1.0, 5.0, 3.0, 2.0};
    float dst[4];

    unsafe {
        f32x4 v = simd_load_f32x4(src);
        f32x4 doubled = v + v;
        simd_store_f32x4(doubled, dst);

        float total = simd_hsum_f32x4(v);   // 수평 합
        f32x4 a = simd_splat_f32x4(2.0);    // 브로드캐스트
        f32x4 fma = simd_fma_f32x4(a, a, v); // a*a + v
    }
    return 0;
}
```

로드/스토어는 원시 포인터를 받으므로, SafeC의 다른 원시 포인터 접근과
마찬가지로 호출부에서 `unsafe { }`가 필요하다.

## 타입 별칭 {#type-aliases}

명명 규칙: `<원소타입><비트수>x<레인수>`. 폭은 모든 타겟의 기본 SIMD
레지스터(128비트: SSE, NEON, WASM SIMD128, VLEN≥128인 RVV)와 일반적인
256비트 경우(AVX2 — 네이티브 256비트 레지스터가 없는 타겟에서는
LLVM이 이를 두 개의 128비트 연산으로 레거라이즈하며, 여전히 정확하게
동작하지만 단일 명령어는 아니다)를 모두 다룬다.

| 별칭 | 실제 타입 |
|---|---|
| `f32x4`, `f32x8` | `vec<float, 4>`, `vec<float, 8>` |
| `f64x2`, `f64x4` | `vec<double, 2>`, `vec<double, 4>` |
| `i32x4`, `i32x8` | `vec<int, 4>`, `vec<int, 8>` |
| `i64x2`, `i64x4` | `vec<long long, 2>`, `vec<long long, 4>` |
| `i16x8`, `i16x16` | `vec<short, 8>`, `vec<short, 16>` |
| `i8x16`, `i8x32` | `vec<signed char, 16>`, `vec<signed char, 32>` |
| `u32x4`, `u32x8` | `vec<unsigned int, 4>`, `vec<unsigned int, 8>` |
| `u8x16`, `u8x32` | `vec<unsigned char, 16>`, `vec<unsigned char, 32>` |

## 함수 {#functions}

| 함수 | 설명 |
|---|---|
| `simd_load_<type>(const T* p)` | 원시 포인터에서 로드 — 구조상 정렬 불필요, 어떤 포인터에도 안전 |
| `simd_store_<type>(v, T* p)` | 원시 포인터에 스토어 |
| `simd_splat_<type>(x)` | 스칼라를 모든 레인에 브로드캐스트 |
| `simd_fma_<type>(a, b, c)` | 융합 곱셈-덧셈: `a*b + c` |
| `simd_min_<type>(a, b)` / `simd_max_<type>(a, b)` | 원소 단위 최소/최대 |
| `simd_hsum_<type>(v)` | 수평 합 — 모든 레인을 하나의 스칼라로 축약 |
| `simd_hmin_<type>(v)` / `simd_hmax_<type>(v)` | 수평 최소/최대 |

모든 함수가 모든 타입 별칭에 대해 정의되어 있지는 않다 — 정확한 목록은
`std/simd/simd.h`를 참고한다(예: `simd_hmin`/`simd_hmax`는 부동소수점
전용이며, 정수 타입은 `simd_hsum`은 있지만 수평 최소/최대 변형은 없다).

## ISA별 편의 헤더 {#per-isa-convenience-headers}

여덟 개의 얇은 헤더가 동일한 이식 가능 타입을 아키텍처에 맞는 이름으로
다시 내보낸다 — 각각은 순수하게 typedef와 문서로만 이루어져 있고 별도의
로직은 없으며, 각각 대상 아키텍처에 대해 실제 생성된 코드로 검증되었다
(단순히 "컴파일된다"가 아니라 `llc` 출력을 역어셈블한 결과로 확인).

| 헤더 | 대상 | 네이티브 매핑 |
|---|---|---|
| `std/simd/x86_64.h` | x86_64 | `m128`/`m128i`/`m128d`(SSE/SSE2), `m256`/`m256i`/`m256d`(AVX/AVX2) |
| `std/simd/aarch64.h` | AArch64 | 128비트 NEON 레지스터 |
| `std/simd/riscv.h` | RISC-V (`+v`) | 128비트 RVV 레지스터 그룹(기본 `zve*` 폭이며, 진정한 *스케일러블* 벡터 길이는 모델링되지 않음 — 이유는 헤더 참고) |
| `std/simd/wasm.h` | WebAssembly | 128비트 `v128`(SIMD128 제안) |
| `std/simd/spirv.h` | SPIR-V | 컴퓨트 커널 본문 내의 실제 `OpTypeVector`/`OpFAdd` — SPIR-V의 호스트 libc 없는 실행 모델에 대한 유의 사항은 해당 헤더 참고 |
| `std/simd/cortex_m.h` | ARM Cortex-M | MVE(M55/M85) 타입 별칭 + DSP 확장 `dsp_*` 함수(M4/M7) — [베어메탈](/ko/reference/baremetal#dsp-extension-cortex-m4-m7) 참고 |
| `std/simd/cuda.h` | CUDA (NVPTX) | 실제 PTX 벡터 레인 코드 생성 — GPU는 N개의 스칼라 연산으로 스칼라화됨, 아래 유의 사항 참고 |
| `std/simd/rocm.h` | ROCm (AMDGPU) | 실제 GCN 벡터 ALU 코드 생성 — 동일한 스칼라화 유의 사항 |

### GPU 타겟: 단일 명령어 SIMD가 아닌 SIMT {#gpu-targets-simt-not-simd-in-one-instruction}

PTX와 GCN에는 SSE/NEON 같은 팩(pack) 산술 명령어가 없다 — GPU의
병렬성은 한 스레드 안의 넓은 레지스터가 아니라, 여러 스레드를 락스텝으로
실행하는 데서 나온다. `vec<float,4> + vec<float,4>`는 CUDA/ROCm 타겟에서도
여전히 정확하게 컴파일되지만, 하나의 넓은 명령어가 아니라 네 개의
독립적인 스칼라 연산이 된다(확인됨: 실제로 생성된 PTX는 네 개의
`add.rn.f32` 명령어를, 실제 GCN은 네 개의 `v_add_f32_e32` 명령어를
보여준다). `std::simd`의 CUDA/ROCm 헤더는 커널 본문 *내부*에서 사용할
수 있는(디바이스 메모리, 호스트 libc 없음) 이식 가능한 산술 타입만
제공한다 — 커널 진입점(`__global__`/`ptx_kernel`/`amdgpu_kernel` 호출
규약과 실행 구성 지원)은 정의하지 않으며, 이는 이 라이브러리가 다루지
않는 별개의 영역이다.

### Metal Shading Language {#metal-shading-language}

지원되지 않는다. Apple의 Metal 컴파일러에는 업스트림 LLVM 백엔드가 없다
— `--target`으로 선택할 수 있는 실제 LLVM 타겟인 NVPTX/AMDGPU/SPIR-V와
달리, Metal은 별개의 폐쇄형 툴체인이다. 이 라이브러리의 SPIR-V 출력에서
연동할 수 있는 유일한 경로는 서드파티 변환기(예: SPIRV-Cross)뿐이며,
`safec`이나 `std::simd`가 직접 제공하는 기능이 아니다.
