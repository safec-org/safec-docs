# 베어메탈 프로그래밍

SafeC는 프리스탠딩(freestanding) 모드, 인터럽트 처리를 위한 함수 속성, 인라인 어셈블리, 그리고 하드웨어 레지스터 접근을 위한 volatile/원자적 연산을 통해 베어메탈 및 임베디드 개발을 지원합니다.

## 프리스탠딩 모드 {#freestanding-mode}

`--freestanding` 플래그는 표준 라이브러리를 비활성화하고 OS에 의존하지 않는 코드를 생성합니다.

```bash
safec firmware.sc --freestanding --emit-llvm -o firmware.ll
```

(아래의 모든 `--target=<베어메탈 별칭>`은 `--freestanding`을 함의합니다.)

프리스탠딩 모드에서는

- libc에 대한 암묵적 의존성이 없습니다
- 시작 코드가 없습니다(`_start`/`main` 관례는 프로그래머가 정의합니다)
- 표준 C 헤더 임포트가 없습니다
- 프로그래머가 모든 런타임 지원을 제공합니다

## 베어메탈 타깃 {#targets}

`--target=<alias>`는 OS 없는 타깃을 이름으로 선택합니다(WebAssembly는 아래 [WebAssembly](#wasm) 절에서 따로 다룹니다): LLVM 트리플, CPU, ISA 기능, ABI를 고르고 `--freestanding`을 함의합니다. `safec --list-targets`가 다음 표를 출력합니다:

| 별칭 | 트리플 | CPU / ISA | ABI | `size_t` / 포인터 |
|---|---|---|---|---|
| `x86` | `i686-unknown-none-elf` | i686 | i386 SysV | 32비트 |
| `x86_64` | `x86_64-unknown-none-elf` | x86-64 | SysV | 64비트 |
| `aarch32` | `armv7a-none-eabi` | ARMv7-A (ARM 상태) | AAPCS, soft-float | 32비트 |
| `aarch64` | `aarch64-unknown-none-elf` | ARMv8-A | AAPCS64 | 64비트 |
| `thumb` | `thumbv6m-none-eabi` | Cortex-M0 (Thumb) | AAPCS, soft-float | 32비트 |
| `thumb2` | `thumbv7m-none-eabi` | Cortex-M3 (Thumb-2) | AAPCS, soft-float | 32비트 |
| `arm-6m` | `thumbv6m-none-eabi` | Cortex-M0/M0+/M1 (ARMv6-M) | AAPCS, soft-float | 32비트 |
| `arm-7m` | `thumbv7m-none-eabi` | Cortex-M3 (ARMv7-M) | AAPCS, soft-float | 32비트 |
| `arm-7em` | `thumbv7em-none-eabi` | Cortex-M4/M7 (ARMv7E-M, DSP) | AAPCS, soft-float | 32비트 |
| `arm-8m` | `thumbv8m.main-none-eabi` | Cortex-M33 (ARMv8-M Mainline) | AAPCS, soft-float | 32비트 |
| `arm-81m` | `thumbv8.1m.main-none-eabi` | Cortex-M55/M85 (ARMv8.1-M, Helium) | AAPCS, soft-float 호출 | 32비트 |
| `rv32i` | `riscv32-unknown-elf` | RV32I | `ilp32` | 32비트 |
| `rv32e` | `riscv32-unknown-elf` | RV32E (레지스터 16개) | `ilp32e` | 32비트 |
| `rv64i` | `riscv64-unknown-elf` | RV64I | `lp64` | 64비트 |
| `rv64e` | `riscv64-unknown-elf` | RV64E (레지스터 16개) | `lp64e` | 64비트 |
| `wasm32` | `wasm32-unknown-unknown` | WebAssembly | — | 32비트 |
| `wasm64` | `wasm64-unknown-unknown` | 64비트 메모리 WebAssembly (memory64) | — | 64비트 |

그 밖의 LLVM 트리플도 `--target=<triple>`로 동작하며, `--target-cpu=<name>`, `--target-features=<+a,-b>`(LLVM `-mattr` 문법), `--target-abi=<name>`으로 세부 조정할 수 있습니다. 이 값들은 모듈에 기록되므로, 이후 `clang -c`가 만드는 오브젝트도 이와 일치해야 합니다: `safec --print-target-clang-flags --target=<alias>`는 그에 맞는 clang 플래그(`--target=…`, RISC-V의 `-march`/`-mabi`, ARM의 `-mcpu`/`-mfloat-abi`)를 한 줄에 하나씩 출력합니다.

```bash
safec firmware.sc -I /path/to/SafeC --target=arm-6m --emit-llvm -o firmware.ll
clang -c firmware.ll $(safec --print-target-clang-flags --target=arm-6m) -o firmware.o
ld.lld -T board.ld --gc-sections -static -nostdlib firmware.o libsafec_std.a libclang_rt.builtins.a -o firmware.elf
```

`safeguard`에서는 `--target`이 이 모든 것을 해 줍니다 — [safeguard](/ko/advanced/safeguard#cross-targets) 참고.

타깃마다 달라지는 것:

- **아키텍처 매크로.** 컴파일러는 clang과 같은 아키텍처 매크로를 미리 정의하므로 `#if` 블록으로 ISA별 코드를 고를 수 있습니다: `__x86_64__`/`__amd64__`, `__i386__`, `__aarch64__`, `__riscv`와 `__riscv_xlen`(32 또는 64), `__wasm__`과 `__wasm32__`/`__wasm64__`, 그리고 32비트 ARM에서는 `__arm__`, `__ARM_ARCH`(6, 7, 8), `__ARM_ARCH_PROFILE`(`'A'`, `'R'`, `'M'`), `__ARM_ARCH_<name>__`(`__ARM_ARCH_6M__`, `__ARM_ARCH_7EM__`, `__ARM_ARCH_8M_MAIN__`, `__ARM_ARCH_7A__`, …), `__thumb__`, 그리고 ARMv7(및 ARMv6T2)부터 `__thumb2__`. 모든 베어메탈 타깃은 `__SAFEC_FREESTANDING__`도 정의하며, OS 매크로(`__linux__`, `__APPLE__`, `_WIN32`)는 어느 것도 정의하지 않습니다. [전처리기](/ko/reference/preprocessor#predefined-macros)를 참고하십시오.
- **포인터 너비.** `size_t`, `ptrdiff_t`, `uintptr_t`, `__SIZEOF_POINTER__`는 타깃을 따릅니다(위의 32비트 타깃에서는 4바이트). SafeC의 `long`은 항상 64비트이므로, 포인터 크기 정수가 필요한 코드는 `long`이 아니라 `size_t`/`uintptr_t`를 써야 합니다.
- **C 구조체 반환 ABI.** 값으로 반환되는 집합체는 타깃의 규약으로 lowering됩니다: i386은 항상 숨겨진 포인터로 구조체를 반환하고, AAPCS32는 4바이트 초과일 때, RISC-V는 2×XLEN 초과일 때, x86-64와 AArch64는 16바이트 초과일 때(AArch64는 동종 부동소수점 집합체 제외), Win64는 1, 2, 4, 8바이트 이외의 모든 크기에서 그렇게 합니다. 큰 구조체 복사는 `memcpy`/`memset` 호출로 생성되며, 런타임이 이를 제공해야 합니다.
- **런타임 헬퍼.** 하드웨어 곱셈/나눗셈이나 원자적 연산이 없는 타깃은 compiler-rt 내장 함수(`__muldi3`, `__udivdi3`, …)와 `__atomic_*` 라이브러리 함수를 호출합니다: 해당 타깃용 `libclang_rt.builtins.a`를 링크하고 원자적 연산을 제공합니다(단일 코어 런타임은 인터럽트를 마스킹한 채로 구현할 수 있습니다).

### libc 이름에 대한 프리스탠딩 호출 {#provides}

프리스탠딩 모드에서 호스트 전용 함수(`malloc`, `printf`, …)를 호출하면 이를 해결할 libc가 없으므로 경고가 납니다. 베어메탈 런타임은 그중 일부를 직접 정의하는 경우가 많습니다. 같은 번역 단위에서 정의된 함수는 경고가 나지 않으며, 다른 파일에서 정의된 함수는 `--provides=malloc,free,memcpy`로 나열하면 정확히 그 이름들에 대해서만 경고가 억제됩니다(`safeguard`는 프로젝트 자체의 정의를 자동으로 전달합니다).

### 베어메탈에서 테스트하기 {#testing}

표준 라이브러리 자체의 베어메탈 테스트 모음(`tests/stdlib/baremetal/`)은 타깃마다 모든 `std/*.sc`를 빌드하고(`sweep.sh`), 최소 런타임(`bm_rt.sc`: 정적 아레나 위의 `memcpy`/`memset`/`malloc`, `__atomic_*` 심)과 함께 테스트 이미지를 주소 `0x10000`에 링크한 뒤 Unicorn CPU 에뮬레이터에서 실행합니다(`run.sh`, `run.py`). wasm32와 wasm64 이미지는 `wasm-ld`로 링크해 Node에서 실행합니다(`run_wasm.mjs`). 현재 16개 타깃 모두 모든 검사를 통과합니다.

`tests/stdlib/baremetal/qemu/run.sh`는 한 단계 더 나아가 QEMU의 에뮬레이션 하드웨어에서 작은 프로그램을 부팅합니다: 주소 변환을 실제로 켠 네이티브 [`MmuContext`](/ko/stdlib/kernel#mmu-context-kernelmmuh) 형식(S 모드의 RISC-V Sv39/Sv32, MMU를 켠 AArch64와 ARMv7-A, x86 32비트 페이징과 x86-64 롱 모드), 그리고 QEMU의 주변장치 모델을 상대로 한 [HAL 칩](/ko/stdlib/hal#hal-chips) PL011, 16550, STM32F4 USART/TIM/SPI입니다. x86에서는 LLVM의 i686 코드가 SSE를 쓰므로 시작 코드가 SSE도 켭니다(`CR0`/`CR4`).

## WebAssembly {#wasm}

`--target=wasm32`와 `--target=wasm64`는 브라우저, Node 등 어떤 호스트에서든 쓸 WebAssembly 모듈을 빌드합니다. 베어메탈 타깃처럼 프리스탠딩이라 libc도 WASI도 없습니다. 모듈은 `main`이 있는 프로그램이 아니라 호스트가 인스턴스화하는 라이브러리입니다:

- `--emit-bin`을 주면 safec는 clang으로 `-nostdlib`, `--no-entry`, `--allow-undefined`를 붙여 링크합니다.
- 모듈은 `export_name`이 붙은 함수(와 선형 메모리)만 내보냅니다.
- 정의하지 않고 호출하는 extern은 모두 임포트가 되며, `import_module`로 다른 모듈을 지정하지 않으면 `env` 모듈에서 가져옵니다.

```c
// add.sc
__attribute__((import_name("log_int"))) void host_log(int v);   // env.log_int

int counter = 0;

__attribute__((export_name)) int add(int a, int b) {               // "add"로 내보냄
    return a + b;
}

__attribute__((export_name("bump"))) int bump_counter(int by) {   // "bump"로 내보냄
    counter = counter + by;
    host_log(counter);
    return counter;
}
```

```bash
safec add.sc --target=wasm32 -O2 --emit-bin -o add.wasm
```

```js
// run.mjs (Node)
import { readFileSync } from 'node:fs';
const { instance } = await WebAssembly.instantiate(readFileSync('add.wasm'), {
    env: { log_int: (v) => console.log('host saw', v) },
});
console.log(Object.keys(instance.exports));   // [ 'memory', 'add', 'bump' ]
console.log(instance.exports.add(2, 3));      // 5
instance.exports.bump(5);                     // host saw 5
```

| 속성 | 효과 |
|---|---|
| `__attribute__((export_name))` | 함수를 자기 이름으로 내보냄 |
| `__attribute__((export_name("x")))` | `x`라는 이름으로 내보냄 |
| `__attribute__((import_module("m")))` | 선언만 된 함수를 `env` 대신 모듈 `m`에서 임포트 |
| `__attribute__((import_name("n")))` | 자기 이름 대신 `n`이라는 이름으로 임포트 |

속성은 선언 앞이나 뒤, 프로토타입이나 정의 어디에든 붙일 수 있고 함께 쓸 수 있습니다: `__attribute__((import_module("console"), import_name("log"))) void log_i(int v);`는 `console.log`를 임포트합니다. 다른 타깃에서는 무시됩니다.

런타임은 `std/wasm/wasm_rt.sc`입니다. `malloc`/`free`/`calloc`/`realloc`(4MiB 정적 풀 `WASM_RT_POOL_SIZE` 위의 범프 할당자이며 `free`는 회수하지 않음)과 `memcpy`/`memmove`/`memset`/`memcmp`를 제공합니다. `abort()`는 wasm의 네이티브 트랩이라 호스트에는 `RuntimeError: unreachable`로 보이고, `printf`/`fprintf`는 출력을 버리며, `std::panic`도 같은 방식으로 트랩합니다. 그래서 모듈에는 자기 코드가 선언한 것 외의 호스트 임포트가 필요 없습니다.

`safeguard build --target wasm32`는 다른 타깃에는 없는 `std/wasm`을 포함해 wasm용 std 아카이브를 빌드하고, 같은 방식의 라이브러리 모듈로 링크합니다. `std/wasm/dom.h`와 `hydrate.h`는 [풀스택 예제](https://github.com/safec-org/SafeC/tree/main/examples/fullstack_demo)를 위한 DOM 상호 운용과 클라이언트 하이드레이션을 제공합니다.

## 함수 속성 {#function-attributes}

### Naked 함수 {#naked-functions}

naked 함수는 컴파일러가 생성하는 프롤로그나 에필로그가 없습니다. 함수 본문은 전적으로 인라인 어셈블리로만 구성되어야 합니다. 부트로더 진입점, ISR 트램폴린, 컨텍스트 스위치에 사용하십시오.

```c
naked void _start() {
    asm volatile (
        "mov $stack_top, %rsp\n"
        "call main\n"
        "hlt"
    );
}
```

### 인터럽트 함수 {#interrupt-functions}

인터럽트 함수는 ISR 호출 규약을 사용합니다. 반드시 `void(void)`여야 하며 컴파일러가 적절한 진입/종료 시퀀스를 생성합니다(예: x86에서의 `iret`).

```c
interrupt void timer_handler() {
    volatile int *timer_reg = (volatile int*)0x40000C00;
    unsafe { *timer_reg = 1; }  // 인터럽트 확인 응답
}
```

### Noreturn 함수 {#noreturn-functions}

절대 반환하지 않는 함수는 `noreturn`으로 표시할 수 있으며, 컴파일러가 호출 지점을 최적화할 수 있게 해줍니다.

```c
noreturn void panic(const &char msg) {
    // ... UART로 씀 ...
    while (1) {}
}
```

### Section 속성 {#section-attribute}

함수나 변수를 특정 링커 섹션에 배치합니다.

```c
section(".isr_vector")
fn void() vector_table[256] = {
    _start,
    nmi_handler,
    hardfault_handler,
    // ...
};

section(".text.fast")
void hot_path() {
    // placed in fast memory section
}
```

함수의 코드 주소는 프로그램이 실행되는 동안 해제되거나 이동되지 않으므로,
`unsafe {}` *블록* 자체가 있을 수 없는 파일 스코프에서조차 이를 저장하는 데
`unsafe {}`가 필요하지 않습니다. 슬롯에는 타입 없는 포인터 대신 함수 타입
(`fn void()`)을 주어, 알맞은 형태의 함수만 들어갈 수 있게 하십시오:

```c
fn void() reset_vector = _start;      // a bare function name is its address
```

## 인라인 어셈블리 {#inline-assembly}

SafeC는 GCC 스타일의 확장 인라인 어셈블리를 지원합니다.

```c
asm [volatile] ( "template" [: outputs [: inputs [: clobbers]]] );
```

제약 조건은 GCC의 문자를 씁니다: `r`, `m`, `i`, `=`/`+`, 그리고 x86에서는
레지스터 문자 `a`, `b`, `c`, `d`, `S`, `D`(EAX/RAX, EBX, ECX, EDX, ESI,
EDI). 그래서 `asm volatile ("rdtsc" : "=a"(lo), "=d"(hi));`가 C에서처럼
동작합니다.

인라인 `asm`은 다른 메모리 안전하지 않은 구문과 마찬가지로 `unsafe {}`
블록이 필요합니다 -- 단, `naked` 함수 내부에서는 예외입니다(본문 전체가
이미 어셈블리여야 하므로 추가로 옵트인할 것이 없습니다).

### 기본 어셈블리 {#basic-assembly}

```c
unsafe {
    asm volatile ("cli");          // 인터럽트 비활성화
    asm volatile ("sti");          // 인터럽트 활성화
    asm volatile ("nop");          // 아무 동작 없음
    asm volatile ("hlt");          // 프로세서 정지
}
```

### 피연산자가 있는 확장 어셈블리 {#extended-assembly-with-operands}

```c
int result = 0;
unsafe {
    asm volatile (
        "mov %1, %0\n"
        "add $1, %0"
        : "=r"(result)             // 출력: result 레지스터
        : "r"(input)               // 입력: input 레지스터
        : "cc"                     // clobber: 조건 코드
    );
}
```

쓰기 전용 출력 피연산자(`"=r"(result)`)는 확정 초기화 검사기 입장에서
대상을 초기화하는 것으로 간주되므로, (초기화식 없는) `int result;`
다음에 `asm volatile (... : "=r"(result) ...)`가 오는 것도 문제없습니다
— 위의 `int result = 0;`도 동작하지만 필수는 아닙니다. 읽기-쓰기
피연산자(`"+r"(var)`)는 쓰기뿐 아니라 진짜 읽기이기도 하므로, `asm` 문
이전에 `var`가 이미 값을 가지고 있어야 합니다.

### 특수 레지스터 읽기 {#reading-special-registers}

```c
long long read_tsc() {
    int lo = 0;
    int hi = 0;
    unsafe {
        asm volatile (
            "rdtsc"
            : "=a"(lo), "=d"(hi)
        );
    }
    return ((long long)hi << 32) | lo;
}
```

### 어셈블리를 이용한 메모리 매핑 I/O {#memory-mapped-io-with-assembly}

```c
void outb(uint16_t port, uint8_t value) {
    unsafe {
        asm volatile (
            "outb %0, %1"
            :
            : "a"(value), "Nd"(port)
        );
    }
}

uint8_t inb(uint16_t port) {
    uint8_t result = 0;
    unsafe {
        asm volatile (
            "inb %1, %0"
            : "=a"(result)
            : "Nd"(port)
        );
    }
    return result;
}
```

## Volatile 접근 {#volatile-access}

`volatile` 한정자는 읽기와 쓰기가 컴파일러에 의해 최적화되어 제거되거나 재배치되지 않도록 보장합니다. 메모리 매핑 하드웨어 레지스터에 필수적입니다.

### Volatile 변수 {#volatile-variables}

```c
void uart_registers() {
    volatile int *UART_DATA = (volatile int*)0x40001000;
    volatile int *UART_STATUS = (volatile int*)0x40001004;
}
```

컴파일 타임 상수 주소(리터럴, 또는 리터럴에 대한 산술 연산)에 대한 정수-포인터 캐스트는 지역 변수와 마찬가지로 전역 변수를 직접 초기화할 수 있습니다.

```c
volatile int *UART_DATA   = (volatile int*)0x40001000;
volatile int *UART_STATUS = (volatile int*)(0x40001000 + 4);
```

### `volatile`이 적용되는 대상 {#what-volatile-applies-to}

volatile lvalue를 거치는 모든 로드와 스토어는 LLVM `load volatile` / `store volatile`로 생성됩니다. 일반 대입과 복합 대입(`|=`, `+=`, …), `++`/`--`, 첨자, 멤버 접근, 역참조가 모두 포함됩니다. 한정자는 네 곳에 올 수 있습니다:

| 선언 | volatile인 것 |
|---|---|
| `volatile int x;` | 변수 `x` (지역 또는 전역) |
| `volatile unsigned int* reg` (또는 `unsigned int volatile* reg`) | `reg`가 가리키는 `unsigned int`. `reg` 자체는 보통 변수 |
| `int* volatile p` | 포인터 변수 `p`. 그것이 가리키는 `int`는 volatile이 아님 |
| `volatile &Dev d` | 참조 뒤의 `Dev`. 그래서 `d.ctrl = 1`은 volatile 스토어 |

구조체 필드도 `volatile`로 선언할 수 있습니다. 그 필드에 대한 접근은 volatile이고, 같은 구조체의 volatile이 아닌 필드는 여전히 평소대로 최적화됩니다:

```c
struct Dev {
    volatile unsigned int ctrl;   // 장치 레지스터: 모든 접근이 메모리에 감
    unsigned int shadow;          // 보통 필드
};

void reset(&Dev d) {
    d.ctrl = 0;      // store volatile
    d.ctrl |= 4;     // load volatile + store volatile
    d.shadow = 0;    // 다른 스토어처럼 합쳐지거나 제거될 수 있음
}
```

한정자는 그 객체를 가리키는 모든 것에 따라붙습니다:

- volatile 변수, 필드, 원소의 `&x`는 `&volatile T`이고, volatile 배열은 `volatile T*`로 감쇠합니다.
- `volatile` 매개변수는 함수 안에서 volatile이며, typedef도 한정자를 담을 수 있습니다: `typedef volatile unsigned int reg_t;` 뒤에는 `reg_t x;`와 `reg_t* p;` 모두 volatile입니다.
- 암묵적으로 한정자를 떨어뜨리는 것은 오류입니다: `volatile T*` → `T*`, volatile 배열 → `T*`, `volatile T**` → `T**`, `&volatile T` → `&T` 또는 `T*`. 정말 그럴 의도라면 명시적 캐스트(원시 포인터는 `unsafe` 안에서)는 여전히 됩니다.

```c
volatile unsigned int status;
void poll(volatile unsigned int* p);

void f() {
    poll(&status);                 // OK: &status는 &volatile unsigned int
    unsigned int* q = &status;     // 오류: cannot convert '&static volatile unsigned int' to 'unsigned int*'
}
```

`volatile`은 컴파일러가 접근을 제거하거나, 합치거나, 서로의 순서를 바꾸지 못하게 합니다. 스레드 사이의 메모리 배리어는 아닙니다. 그 용도로는 [`std::atomic`](/ko/stdlib/atomic)을 씁니다. `std::mem_zero_secure`는 이 volatile 스토어에 의존하므로, 키를 지우는 코드가 `-O2`의 dead-store 제거에서도 살아남습니다.

### Volatile Load와 Store {#volatile-load-and-store}

내장 함수는 명시적인 volatile 접근을 제공합니다.

```c
int val = volatile_load(ptr);       // 메모리에서 읽는 것을 보장
volatile_store(ptr, value);         // 메모리에 쓰는 것을 보장
```

이들은 LLVM `load volatile`과 `store volatile` 명령어로 컴파일됩니다. `volatile T*`를 통한 접근과 같은 일을 하며, 포인터 자체가 volatile로 선언되지 않았을 때 유용합니다.

### 예제: 하드웨어 레지스터 폴링 {#example-polling-a-hardware-register}

```c
void uart_send(char c) {
    volatile int *status = (volatile int*)0x40001004;
    volatile int *data = (volatile int*)0x40001000;

    // 송신 버퍼가 비워질 때까지 대기
    while ((volatile_load(status) & 0x20) == 0) {}

    volatile_store(data, (int)c);
}
```

## 원자적 연산 {#atomic-operations}

원자적 연산은 락 프리(lock-free) 동기화를 제공하며 멀티코어 베어메탈 시스템과 인터럽트 핸들러에 필수적입니다.

### 원자적 변수 {#atomic-variables}

```c
atomic int counter = 0;
```

### 원자적 내장 함수 {#atomic-built-ins}

| 연산 | 시그니처 | 설명 |
|-----------|-----------|-------------|
| `atomic_load(ptr)` | `T atomic_load(T *ptr)` | 값을 원자적으로 로드 |
| `atomic_store(ptr, val)` | `void atomic_store(T *ptr, T val)` | 값을 원자적으로 저장 |
| `atomic_fetch_add(ptr, val)` | `T atomic_fetch_add(T *ptr, T val)` | 더하고 이전 값을 반환 |
| `atomic_fetch_sub(ptr, val)` | `T atomic_fetch_sub(T *ptr, T val)` | 빼고 이전 값을 반환 |
| `atomic_fetch_and(ptr, val)` | `T atomic_fetch_and(T *ptr, T val)` | 비트 AND 후 이전 값을 반환 |
| `atomic_fetch_or(ptr, val)` | `T atomic_fetch_or(T *ptr, T val)` | 비트 OR 후 이전 값을 반환 |
| `atomic_fetch_xor(ptr, val)` | `T atomic_fetch_xor(T *ptr, T val)` | 비트 XOR 후 이전 값을 반환 |
| `atomic_exchange(ptr, val)` | `T atomic_exchange(T *ptr, T val)` | 교환 후 이전 값을 반환 |
| `atomic_cas(ptr, expected, desired)` | `bool atomic_cas(T *ptr, T exp, T des)` | 비교 후 교환 (compare-and-swap) |
| `atomic_fence()` | `void atomic_fence()` | 완전한 메모리 배리어 |

모든 원자적 연산은 기본적으로 순차적 일관성(sequentially consistent) 순서를 사용합니다.

### 예제: 락 프리 카운터 {#example-lock-free-counter}

```c
atomic int shared_counter = 0;

interrupt void timer_isr() {
    atomic_fetch_add(&shared_counter, 1);
}

int read_counter() {
    return atomic_load(&shared_counter);
}
```

### 예제: 스핀락 {#example-spinlock}

```c
atomic int lock = 0;

void spin_lock() {
    while (atomic_exchange(&lock, 1) != 0) {
        // spin
    }
    atomic_fence();
}

void spin_unlock() {
    atomic_fence();
    atomic_store(&lock, 0);
}
```

## ARM Cortex-M {#arm-cortex-m}

Cortex-M은 위의 일반적인 베어메탈 기능을 넘어서는 일급 대우를 받습니다.
`--target thumbv7em-none-eabi --freestanding`(M4/M7),
`--target thumbv6m-none-eabi --freestanding`(M0/M0+, FPU/DSP 없음 —
가장 제약이 큰 변형), 또는 `+mve`를 사용한
`--target thumbv8.1m.main-none-eabi`(M55/M85)로 깔끔하게 크로스
컴파일되며, 아래 설명된 HAL과 DSP 확장 내장 함수도 제공됩니다. 전체
크로스 컴파일 매트릭스는
[멀티 타겟 코드 생성](/ko/advanced/compiler#multi-target-codegen)을
참고하십시오.

### HAL: NVIC, SysTick, SCB {#hal-nvic-systick-scb}

`std/hal/cortex_m.h`는 가장 흔히 필요한 세 가지 Cortex-M 주변장치를
표준 메모리 매핑 주소의 전역 인스턴스에 대한 메서드로 감쌉니다 — 전체
API는 [`std::hal`](/ko/stdlib/hal)을 참고하십시오.

```c
#include <std/hal/cortex_m.h>

void setup_timer_interrupt() {
    std::nvic_init();
    std::systick_init();
    std::systick.start(1000000, 1);   // 인터럽트당 1,000,000 코어 클럭 틱
    std::nvic.enable(15);              // SysTick IRQ
    std::nvic.set_priority(15, (unsigned char)0);
}
```

### DSP 확장 (Cortex-M4/M7) {#dsp-extension-cortex-m4-m7}

DSP 확장의 패킹된-SIMD/포화(saturating) 명령어 — `SADD16`, `SMLAD`,
`USAD8`, `SSAT` 등 — 는 2×16비트 또는 4×8비트 레인을 단일 32비트
*스칼라* 레지스터에 패킹합니다. 진짜 벡터 레지스터 파일과는 다른
모델입니다. LLVM은 `vec<T,N>` IR을 이러한 명령어로 자동 벡터화하지
않으므로([네이티브 SIMD](/ko/reference/simd) 참고), 이들은
`std/simd/cortex_m.h`에서 `std::dsp_*` 함수로 직접 노출되며, 실제
`llc -mcpu=cortex-m4` 출력을 통해 각각 (라이브러리 호출이 아닌) 단일
명명된 명령어를 방출하는지 검증됩니다.

```c
#include <std/simd/cortex_m.h>

int clamp_i8(int x) {
    return __arm_dsp_ssat(x, 8);   // 부호 있는 8비트 범위로 포화
}

int checksum4(int a, int b, int acc) {
    return std::dsp_smlad(a, b, acc);   // 이중 16x16 곱셈-누산
}
```

`dsp_ssat`/`dsp_usat`/`dsp_ssat16`/`dsp_usat16`은 함수로 감싸져 있지
않습니다 — 기반 명령어의 비트 너비 피연산자는 명령어에 직접 인코딩되는
리터럴 즉시값(immediate)이며, `__arm_dsp_ssat(val, bits)` 호출 지점에서
컴파일러에 의해 강제됩니다(`bits`는 그곳에서 반드시 문자 그대로 정수
리터럴 표현식이어야 하며, 전달용 함수 매개변수로는 결코 이를 만족시킬
수 없습니다) — 위와 같이 리터럴 너비로 내장 함수를 직접 호출하십시오.

ARM 타겟이 필요합니다 — 비ARM 타겟(또는 비ARM 머신에서의 기본 호스트
타겟)을 컴파일하면서 `__arm_dsp_*`/`std::dsp_*` 함수를 사용하는 것은
잘못된 코드 생성이 조용히 발생하는 것이 아니라 호출 지점에서의
컴파일 오류입니다.

### MVE (Cortex-M55/M85) {#mve-cortex-m55-m85}

DSP 확장과 달리, MVE("Helium")는 진짜 벡터 레지스터 파일*이며*,
`vec<T,N>`은 이를 직접 활용합니다 — `--target thumbv8.1m.main-none-eabi`와
`+mve`로 컴파일된 `vec<int,4>` 산술은 진짜 MVE 명령어(`vadd.i32 q2, q0, q1`,
로드/스토어를 위한 `vldrw.u32`/`vstrw.32`)로 lowering되며, 다른 타겟에서
NEON, SSE, RVV, SIMD128에 쓰이는 것과 동일한 메커니즘입니다 —
[네이티브 SIMD](/ko/reference/simd)를 참고하십시오.

## 베어메탈 예제: 최소 커널 {#bare-metal-example-minimal-kernel}

```c
// kernel.sc -- compiled with --freestanding

section(".text.boot")
naked void _start() {
    asm volatile (
        "mov $0x80000, %rsp\n"
        "call kernel_main\n"
        "hlt"
    );
}

void vga_putchar(int x, int y, char c, uint8_t color) {
    volatile uint8_t *VGA_BUFFER = (volatile uint8_t*)0xB8000;
    int offset = (y * 80 + x) * 2;
    unsafe {
        volatile_store(VGA_BUFFER + offset, (uint8_t)c);
        volatile_store(VGA_BUFFER + offset + 1, color);
    }
}

void kernel_main() {
    const &char msg = "Hello from SafeC!";
    unsafe {
        for (int i = 0; msg[i] != 0; i = i + 1) {
            vga_putchar(i, 0, msg[i], 0x0F);
        }
    }

    while (1) {
        unsafe { asm volatile ("hlt"); }
    }
}
```

::: warning
`VGA_BUFFER`는 (위의 "Volatile 변수" 참고) 지금이라도 파일 스코프
전역으로 끌어올릴 수 있습니다 — 여기서 지역으로 둔 것은 오직
`vga_putchar`만이 이를 필요로 하기 때문입니다. `msg[i]`는 어느 쪽이든
`unsafe`가 필요합니다: `msg`는 원시 `const char*`이며, 문자열 리터럴
여부와 무관하게 원시 포인터를 첨자 접근하는 것은 항상 이를 요구합니다.
:::
