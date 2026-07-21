# 베어메탈 프로그래밍

SafeC는 프리스탠딩(freestanding) 모드, 인터럽트 처리를 위한 함수 속성, 인라인 어셈블리, 그리고 하드웨어 레지스터 접근을 위한 volatile/원자적 연산을 통해 베어메탈 및 임베디드 개발을 지원합니다.

## 프리스탠딩 모드 {#freestanding-mode}

`--freestanding` 플래그는 표준 라이브러리를 비활성화하고 OS에 의존하지 않는 코드를 생성합니다.

```bash
./build/safec firmware.sc --freestanding --emit-llvm -o firmware.ll
```

프리스탠딩 모드에서는

- libc에 대한 암묵적 의존성이 없습니다
- 시작 코드가 없습니다(`_start`/`main` 관례는 프로그래머가 정의합니다)
- 표준 C 헤더 임포트가 없습니다
- 프로그래머가 모든 런타임 지원을 제공합니다

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
noreturn void panic(const char *msg) {
    // ... UART로 씀 ...
    while (1) {}
}
```

### Section 속성 {#section-attribute}

함수나 변수를 특정 링커 섹션에 배치합니다.

```c
section(".isr_vector")
void* vector_table[256] = {
    _start,
    nmi_handler,
    hardfault_handler,
    // ...
};

section(".text.fast")
void hot_path() {
    // 빠른 메모리 섹션에 배치됨
}
```

단순 함수 이름, `&functionName`, `(void*)functionName`은 모두
동등합니다 — 함수의 코드 주소는 프로그램이 실행되는 동안 해제되거나
이동되지 않으므로, `unsafe {}` *블록* 자체가 있을 수 없는 파일 스코프에서조차
셋 중 어느 것도 `unsafe {}`가 필요하지 않습니다.

```c
void* p1 = _start;          // 단순 이름
void* p2 = &_start;         // 함수 지정자에서 '&'는 아무 동작도 하지 않음
void* p3 = (void*)_start;   // 명시적 캐스트도 unsafe{}가 필요 없음
```

## 인라인 어셈블리 {#inline-assembly}

SafeC는 GCC 스타일의 확장 인라인 어셈블리를 지원합니다.

```c
asm [volatile] ( "template" [: outputs [: inputs [: clobbers]]] );
```

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

### Volatile Load와 Store {#volatile-load-and-store}

내장 함수는 명시적인 volatile 접근을 제공합니다.

```c
int val = volatile_load(ptr);       // 메모리에서 읽는 것을 보장
volatile_store(ptr, value);         // 메모리에 쓰는 것을 보장
```

이들은 LLVM `load volatile`과 `store volatile` 명령어로 컴파일됩니다.

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
// kernel.sc -- --freestanding으로 컴파일됨

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
    const char *msg = "Hello from SafeC!";
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
