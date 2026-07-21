# 컴파일러 아키텍처

SafeC는 `.sc` 소스 파일을 LLVM IR을 거쳐 네이티브 실행 파일로 컴파일합니다. 컴파일러는 런타임 주입이 없는 단일 패스 프론트엔드입니다 — 작성한 것이 곧 컴파일되는 것입니다.

## 파이프라인 {#pipeline}

```
Source (.sc)
    |
Preprocessor --- #include, #define, #ifdef, #pragma once
    |
Lexer --- SafeC 키워드 집합을 사용한 토큰화
    |
Parser --- 재귀 하강, C 문법 + SafeC 확장
    |
AST
    |
Semantic Analysis --- 이름 해석, 타입 검사, 리전 이스케이프 분석
    |
Const-Eval Engine --- 컴파일 타임에 const/consteval 평가
    |
Code Generation --- 리전 메타데이터(noalias, nonnull 등)를 포함한 LLVM IR
    |
LLVM Backend --- 오브젝트 코드 / 비트코드
```

런타임 주입 단계는 없습니다. 숨겨진 할당을 삽입하는 암묵적 변환도 없습니다.

## 주요 설계 결정 {#key-design-decisions}

**참조는 원시 포인터로 낮춰집니다.** `&stack T`나 `&heap T`는 `noalias`, `nonnull`, `dereferenceable` 속성이 표기된 평범한 LLVM `ptr`이 됩니다. 팻 포인터도, 참조 카운팅도, 숨겨진 간접 참조도 없습니다.

**리전은 컴파일 타임에만 존재합니다.** 리전 표기(`&arena<R> T`)는 의미 분석 중 이스케이프 분석과 대여 검사를 이끕니다. 생성된 IR에는 리전 메타데이터가 전혀 남지 않습니다.

**Const 전역 변수는 폴딩됩니다.** const-eval 엔진은 `const`와 `consteval` 초기화식을 컴파일 타임에 평가합니다. 결과는 리터럴 IR 상수로 다시 기록됩니다 — 런타임 초기화 코드는 생성되지 않습니다.

**`if const`는 죽은 코드로 제거됩니다.** `if const`로 보호된 분기는 const-eval 중에 해결됩니다. 선택된 분기만 코드 생성으로 넘어갑니다.

**`consteval`은 강제됩니다.** 런타임 컨텍스트에서 호출된 `consteval` 함수는 조용히 폴백되지 않고 컴파일 오류가 됩니다.

**구조체 레이아웃은 C ABI를 따릅니다.** SafeC 구조체는 C 구조체와 레이아웃 호환성을 가집니다. vtable도, 숨겨진 필드도, 예상치 못한 패딩도 없습니다.

## 소스 레이아웃 {#source-layout}

```
compiler/
├── include/safec/
│   ├── Token.h          키워드와 토큰 정의
│   ├── Lexer.h          토크나이저 인터페이스
│   ├── AST.h            모든 AST 노드 타입
│   ├── Type.h           타입 표현 (기본형, 참조, 리전)
│   ├── Parser.h         재귀 하강 파서
│   ├── Sema.h           의미 분석 (스코프, 타입, 대여 검사)
│   ├── ConstEval.h      컴파일 타임 평가 엔진
│   ├── CodeGen.h        LLVM IR 생성
│   ├── Preprocessor.h   매크로 확장과 조건부 컴파일
│   ├── CHeaderImporter.h  clang AST를 통한 네이티브 C 헤더 가져오기
│   ├── Clone.h          제네릭 단형화를 위한 AST 딥 클론
│   └── Diagnostic.h     오류/경고 보고
├── src/
│   ├── Preprocessor.cpp
│   ├── CHeaderImporter.cpp
│   ├── Lexer.cpp
│   ├── AST.cpp
│   ├── Type.cpp
│   ├── Parser.cpp
│   ├── Sema.cpp
│   ├── Clone.cpp
│   ├── ConstEval.cpp
│   ├── CodeGen.cpp
│   └── main.cpp
├── examples/            데모 파일
└── CMakeLists.txt
```

## 컴파일러 빌드하기 {#building-the-compiler}

SafeC는 LLVM 21+와 CMake를 필요로 합니다:

```bash
cd compiler
cmake -S . -B build \
  -DLLVM_DIR=/path/to/llvm/lib/cmake/llvm
cmake --build build
```

결과 바이너리는 `build/safec`입니다.

## 사용법 {#usage}

```bash
# 실행 파일로 컴파일 (LLVM IR + clang 링크를 통해) — 2단계 방식
./build/safec input.sc --emit-llvm -o input.ll
clang input.ll -o input

# 또는 한 단계로, --emit-bin을 통해 (아래 "링킹" 참고)
./build/safec input.sc --emit-bin -o input

# AST 덤프
./build/safec input.sc --dump-ast

# (기본적으로 켜져 있는) 증분 비트코드 캐시 비활성화
./build/safec input.sc --no-incremental

# 디버그 정보
./build/safec input.sc --g lines    # 라인 수준 디버그 정보
./build/safec input.sc --g full     # 전체 변수 수준 디버그 정보

# 다른 아키텍처/OS로 크로스 컴파일
./build/safec input.sc --target aarch64-unknown-linux-gnu --emit-llvm -o input.ll

# 컴파일러 버전 출력
./build/safec --version
```

## 링킹 (`--emit-bin`) {#linking-emit-bin}

`safec` 자체의 파이프라인은 LLVM IR/비트코드에서 멈춥니다 — 스스로
링크하지 않습니다. `--emit-bin`은 진짜 "네이티브 바이너리/라이브러리로
바로 컴파일"하는 모드를 추가합니다: 모듈을 임시 `.ll` 파일로 기록한 다음,
시스템의 `clang`(어셈블 + 링크)이나 `ar`(정적 아카이브용)을 호출하여
`-o`가 지정한 파일을 생성합니다. 이는 [`safeguard`](/ko/advanced/safeguard)가
소스 파일마다 이미 사용하고 있는 것과 같은 도구 셸링 방식입니다 —
`--emit-bin`은 그저 이를 프로젝트/`Package.toml`을 거치지 않고 단일
`.sc` 파일에 대해 `safec`에서 직접 사용할 수 있게 만들 뿐입니다.

```bash
# 단순 실행 파일 링크
./build/safec main.sc --emit-bin -o main

# 외부 라이브러리에 링크: -l<name> / -L<dir>, clang/gcc와 같은 관례.
# C 라이브러리를 호출하려면 SafeC 쪽에 그에 대응하는 'extern' 선언이
# 필요합니다(C 상호운용 참고); C++/Objective-C를 호출하려면 이름 맹글링을
# 피하기 위해 *그쪽*에 'extern "C"'가 필요합니다 — SafeC 자체는 이름
# 맹글링이 없으므로 이를 옵트아웃할 필요가 없습니다. 모든 최상위 함수는
# 이미 평범한 C 호환 심볼 이름을 가지고 있기 때문입니다.
./build/safec main.sc --emit-bin -o main -lm -L/usr/local/lib

# 실행 파일 대신 공유/동적 라이브러리 생성
./build/safec mathlib.sc --emit-bin --shared -o libmath.dylib

# 정적 라이브러리 생성 (컴파일된 오브젝트의 'ar' 아카이브 — 링커 없음,
# .a는 링크되지 않고 그저 패키징될 뿐이므로 -l/-L은 여기서는 의미가 없음)
./build/safec mathlib.sc --emit-bin --static-lib -o libmath.a

# 링크 단계에 LTO 활성화 (thin이 기본값이며, full도 사용 가능)
./build/safec main.sc --emit-bin --lto -o main
./build/safec main.sc --emit-bin --lto=full -o main

# 릴리스 프로필: -O가 명시적으로 주어지지 않으면 -O2
./build/safec main.sc --emit-bin --release -o main
```

`-l`/`-L`/`--shared`/`--static-lib`/`--lto`는 `--emit-bin`과 함께일 때만
의미가 있습니다 — 이것 없이 전달하면 경고가 출력되고 아무 효과도 없습니다.
설정할 링크 단계 자체가 없기 때문입니다. `SAFEC_CLANG`/`SAFEC_AR`
환경 변수는 발견된 `clang`/`ar` 경로를 재정의하며, `safeguard`의
`SAFEC_CLANG`/`SAFEC_CLANGXX`와 대응됩니다.

## 다중 타겟 코드 생성 {#multi-target-codegen}

`--target <triple>`은 LLVM에 등록된 모든 타겟으로 크로스 컴파일합니다 —
컴파일러는 아키텍처에 특화된 것을 하드코딩하지 않습니다; `--target`은
LLVM `TargetMachine`을 선택하며, 이는 (코드 생성 중 `sizeof`/구조체
레이아웃/정렬에 실시간으로 사용되는) 데이터 레이아웃과, 생성된 IR을
실제 머신 코드로 바꾸는 이후의 `llc`/`clang -c` 단계가 사용하는 명령어
선택 정보를 모두 제공합니다. 플래그를 생략하면 이 플래그가 존재하기
전과 동일하게 호스트를 타겟으로 합니다.

실제로 생성된 머신 코드로 검증되었습니다(단순히 입력을 받아들이는 것이
아니라) 다음 대상에서:

| OS | 아키텍처 |
|---|---|
| macOS | x86_64, AArch64 |
| Linux | x86_64, x86, AArch64, Aarch32 (ARMv7), RV64, RV32 |
| Windows (MSVC) | x86_64, x86, AArch64 |
| iOS | AArch64 (디바이스 + 시뮬레이터) |
| Android | AArch64, Aarch32, x86_64, x86 |
| FreeBSD | x86_64, AArch64 |
| 베어메탈 (`--freestanding`) | ARM Cortex-M (Thumb/Thumb2), RV32, RV64, AArch64 |
| 포터블 / GPU | WebAssembly, SPIR-V, CUDA (NVPTX), ROCm (AMDGPU) |

Metal Shading Language는 `--target`으로 도달할 수 없습니다 — NVPTX/AMDGPU/SPIR-V와
달리 Apple의 Metal 컴파일러는 업스트림 LLVM 백엔드가 없는, 실제
LLVM 타겟이 아닙니다. SPIR-V 출력에서의 유일한 상호운용 경로는
서드파티 변환기(예: SPIRV-Cross)이며, `safec`이 직접 수행하는 것이
아닙니다.

이 위에 구축된 SIMD 라이브러리는 [`std::simd`](/ko/stdlib/simd)를,
ARM Cortex-M의 세부 사항(HAL, DSP 확장 내장 함수, MVE)은
[베어메탈](/ko/reference/baremetal)을 참고하세요.

## 증분 컴파일 {#incremental-compilation}

파일 수준 비트코드 캐시는 **기본적으로 켜져 있습니다**(선택적으로
`--cache-dir <dir>`을 지정할 수 있으며 기본값은 `.safec_cache`입니다;
`--no-incremental`로 비활성화). 컴파일러는 전처리된 소스를 컴파일러
바이너리 자체의 식별 정보(재빌드된 컴파일러는 항상 오래된 항목을
캐시 미스로 처리하도록)와 코드 생성에 영향을 주는 모든 플래그
(`--target`, `-g`, `--freestanding`)와 함께 FNV-1a로 해싱합니다.
일치하는 해시를 가진 캐시된 `.bc` 파일이 존재하면 모든 파이프라인
단계가 건너뛰어집니다.

캐시 미스가 발생하면 전체 파이프라인이 실행되고 결과 비트코드가
캐시 디렉터리에 기록됩니다.

캐시된 모든 `.bc` 파일을 삭제하려면 `--clear-cache`를 사용하세요.

## 디버그 정보 {#debug-info}

SafeC는 두 단계의 DWARF 디버그 정보를 지원합니다:

| 플래그 | 출력되는 것 |
|------|--------------|
| `--g lines` | 함수당 `DICompileUnit` + `DIFile` + `DISubprogram`, 문장당 `DILocation` |
| `--g full` | `lines`의 모든 것 + 지역 변수당 `DILocalVariable` + `dbg.declare` |

`--g` 플래그가 없으면 디버그 메타데이터가 출력되지 않습니다.

## 네이티브 C 헤더 임포트 {#native-c-header-import}

`#include <stdio.h>`는 SafeC에서 네이티브로 동작합니다. `CHeaderImporter`
모듈은 포함된 헤더에 대해 `clang -ast-dump=json`을 호출한 다음, `FunctionDecl`과
`TypedefDecl` 노드를 SafeC의 AST로 추출합니다.

지원되지 않는 구성 요소(함수 포인터, ObjC 블록, `long double`, `__int128`)는
조용히 건너뛰어집니다. Enum typedef는 `typedef int name`으로 낮춰집니다.

이는 `--no-import-c-headers`로 비활성화할 수 있습니다.

## 제네릭 단형화 {#generics-monomorphization}

제네릭 함수(`generic<T>`)는 컴파일 타임에 단형화됩니다. 컴파일러가
제네릭 함수 호출을 만나면 다음을 수행합니다:

1. 호출 지점의 인자 타입으로부터 타입 인자 `T`를 추론합니다
2. `Clone.cpp`를 통해 함수 AST를 딥 클론하며, `T`를 구체적인 타입으로 치환합니다
3. 단형화된 함수(`__safec_fn_type`으로 맹글링됨)를 번역 단위에 추가합니다
4. 단형화된 복사본에 대해 의미 분석과 코드 생성을 실행합니다

제네릭 함수 본문은 첫 번째 의미 분석 패스에서 건너뛰어집니다 — 단형화 이후에만 분석됩니다.

## Const-Eval 엔진 {#const-eval-engine}

const-eval 엔진은 컴파일 타임에 실행되는 트리 워킹 인터프리터입니다. 다음을 처리합니다:

- `const` 전역 초기화식
- `consteval` 함수 본문
- `static_assert` 검증
- `if const` 분기 선택

평가된 결과는 `IntLit`이나 `FloatLit` 노드로 AST에 다시 폴딩되므로, 코드 생성은 리터럴 상수만 보게 됩니다.
