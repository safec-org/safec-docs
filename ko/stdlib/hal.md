# 하드웨어 추상화 계층 (`std::hal`)

`std/hal/`은 흔히 쓰이는 임베디드 주변장치를, 해당 주변장치의 MMIO 베이스
주소를 담고 있는 구조체 인스턴스의 메서드로 감쌉니다 —
[정적 메서드](/ko/reference/functions#static-methods) `new()`(`GpioPin::new(...)`,
`Uart::new(...)`)로 인스턴스를 만든 다음 그 위에서 메서드를 호출하면 됩니다.
각 드라이버는 그 주변장치의 레지스터를 아는 *칩 타입*에 대해 제네릭이며,
칩 타입은 변수의 타입에서 컴파일 타임에 정해지고(`BST<K, V, C>`의 순서
관계와 같은 방식) [칩 트레이트](#chip-traits)로 검사됩니다. 그래서 호출은
레지스터 직접 접근으로 컴파일됩니다.
모든 모듈이 프리스탠딩 환경에서 안전하며(libc 의존성 없음), 일반적인
`--target` 크로스 컴파일은 물론 `--freestanding`에서도 동작합니다.

```c
#include <std/hal/gpio.h>
#include <std/hal/uart.h>

void blink(&volatile unsigned int gpioa, &volatile unsigned int usart2) {
    struct GpioPin<Stm32F4Gpio> led = std::GpioPin::new(gpioa, 5, GPIO_OUTPUT);
    led.write(1);
    led.toggle();
    struct Uart<Stm32F4Usart> con = std::Uart::new(usart2, 115200u, 42000000u);
    con.write_str("blink\n");
}
```

주변장치 기준 주소는 `&volatile unsigned int` 참조이므로 모든 레지스터 접근이
volatile 로드나 스토어가 됩니다. 데이터시트의 주소로부터 `unsafe` 안에서 한 번
만드십시오 — 컴파일러는 그 주소가 유효한지 알 수 없습니다:

```c
&volatile unsigned int GPIOA_BASE() {
    unsafe { return (&volatile unsigned int)0x40020000UL; }
}
```

정적 메서드는 네임스페이스를 거쳐 호출하지만 구조체 *타입* 자체에는
네임스페이스가 적용되지 않는다는 점에 유의합니다 — 타입은 `struct GpioPin`이고,
`namespace std` 밖에서의 호출은 `std::GpioPin::new(...)`입니다
([네임스페이스](/ko/reference/namespaces#what-gets-namespaced) 참고). 칩 타입은
선언된 변수에서 정해지므로 `new`에는 타입 인자를 주지 않습니다.

## 드라이버와 칩 {#generic-peripherals}

| 모듈 | 헤더 | 타입 | 설명 |
|---|---|---|---|
| GPIO | `hal/gpio.h` | `GpioPin<C>` | `GpioPin::new(base, pin, direction)`; `set_direction`, `write`, `read`, `toggle`, `set_pull` |
| I2C | `hal/i2c.h` | `I2cBus<C>` | `I2cBus::new(base, speed, clock_hz)`; `write`, `read`, `write_read`(쓰기 후 읽기), `probe`; 전송이 실패해도 STOP을 보내 버스를 해제합니다 |
| SPI | `hal/spi.h` | `SpiDevice<C>` | `SpiDevice::new(base, mode)`; `transfer`(1바이트, 전이중), `cs_assert`/`cs_deassert`, `write(tx, ?&rx, len)`(`rx`는 `null`일 수 있음), `read` |
| UART | `hal/uart.h` | `Uart<C>` | `Uart::new(base, baud, clock_hz)`; `write_byte`, `read_byte`, `write_str`, `rx_ready`, `tx_ready` |
| Timer | `hal/timer.h` | `Timer<C>` | `Timer::new(base, prescaler)`; `set_period`, `start`, `stop`, `read`, `clear_flag`, `flag_set` |
| Watchdog | `hal/watchdog.h` | `Watchdog<C>` | `Watchdog::new(base, timeout)`; `enable`(일반적으로 한 번 활성화되면 비활성화할 수 없음), `feed`, `caused_reset` |

상수: `GPIO_INPUT`/`GPIO_OUTPUT`, `GPIO_PULL_NONE`/`GPIO_PULL_UP`/`GPIO_PULL_DOWN`; I2C 결과 `I2C_OK`/`I2C_NACK`/`I2C_ERROR`; `SPI_MODE_0`–`SPI_MODE_3`(CPOL/CPHA = 00, 01, 10, 11).

`clock_hz`는 주변장치의 입력 클록으로, 보 레이트나 버스 속도 분주값을
계산하는 데 씁니다. `Uart::new`에 `clock_hz = 0`을 주면 부트 펌웨어가 설정한
속도를 그대로 둡니다.

### 칩 타입 {#hal-chips}

| 칩 타입 | 주변장치 |
|---|---|
| `GpioGeneric`, `UartGeneric`, `SpiGeneric`, `I2cGeneric`, `TimerGeneric`, `WatchdogGeneric` | 아래의 기준 레지스터 레이아웃 — 이를 구현하는 FPGA 소프트 코어나 시뮬레이터용 |
| `Pl011` | ARM PrimeCell PL011 UART(QEMU `virt`, Raspberry Pi, 여러 Arm SoC) |
| `Ns16550` | 바이트 폭 레지스터가 연속 주소에 있는 16550 호환 UART(QEMU RISC-V `virt`, 여러 SoC) |
| `Stm32F4Gpio` | STM32 GPIO 포트(`MODER`/`PUPDR`/`IDR`/`ODR`/`BSRR`; F2/F4/F7/L4/G0/H7 레이아웃). `write`는 `BSRR`를 쓰므로 읽기-수정-쓰기가 필요 없습니다 |
| `Stm32F4Usart` | STM32F1/F2/F4 USART(`SR`/`DR`/`BRR`/`CR1`) |
| `Stm32F4Spi` | 마스터 모드 STM32F1/F2/F4 SPI, 소프트웨어 NSS, fPCLK/16. 칩 셀렉트는 일반 GPIO이므로 `cs_assert`/`cs_deassert`는 아무 일도 하지 않습니다 — `GpioPin`으로 구동합니다 |
| `Stm32F4I2c` | STM32F1/F2/F4 I2C("v1": `CR1`/`CR2`/`DR`/`SR1`/`SR2`/`CCR`/`TRISE`), 표준 모드와 고속 모드 |
| `Stm32F4Tim` | STM32 범용 타이머(TIM2–TIM5): `PSC`, `ARR`, `CNT`, 업데이트 플래그 `UIF`. 카운터는 clock / (prescaler + 1)로 증가합니다 |
| `Stm32F4Iwdg` | STM32 독립 워치독: `timeout`은 LSI / 256으로 세는 12비트 리로드 값(32 kHz에서 단위당 약 8 ms)이며, `caused_reset`은 STM32F4 RCC 주소의 `RCC_CSR.IWDGRSTF`를 읽습니다 |

칩 타입은 주변장치의 기준 주소를 받아 레지스터 작업을 하는 메서드를,
대응하는 [칩 트레이트](#chip-traits)가 나열한 대로 가진 구조체입니다. 그래서
다른 부품은 그런 구조체를 하나 더 작성하면 지원됩니다.

`Pl011`, `Ns16550`, `Stm32F4Usart`, `Stm32F4Tim`, `Stm32F4Spi` 칩은
`tests/stdlib/baremetal/qemu/run.sh`에서 QEMU의 해당 주변장치 모델을 상대로
실행되며, 모든 칩의 레지스터 시퀀스는 호스트 테스트에서 RAM 기반 레지스터로
검사합니다.

### 칩 트레이트 {#chip-traits}

각 드라이버의 칩 파라미터는 드라이버가 호출하는 레지스터 연산을 나열한
[트레이트](/ko/reference/generics#bounded-generic-structs)로 제한됩니다. 모든
메서드는 주변장치의 기준 주소를 `unsigned long`으로 받으며 `const`입니다 —
칩 타입은 자기 상태를 갖지 않습니다(`std`의 칩들은 쓰이지 않는 `int` 필드
하나만 가집니다).

| 트레이트 | 요구하는 메서드 | 제한하는 타입 | 만족하는 타입 |
|---|---|---|---|
| `GpioChip` | `set_direction(base, pin, direction)`, `write(base, pin, value)`, `read(base, pin)`, `toggle(base, pin)`, `set_pull(base, pin, pull)` | `GpioPin<C: GpioChip>` | `GpioGeneric`, `Stm32F4Gpio` |
| `UartChip` | `init(base, baud, clock_hz)`, `tx_ready(base)`, `rx_ready(base)`, `put(base, c)`, `get(base)` | `Uart<C: UartChip>` | `UartGeneric`, `Pl011`, `Ns16550`, `Stm32F4Usart` |
| `I2cChip` | `init(base, speed, clock_hz)`, `write(base, addr, data, len)`, `read(base, addr, data, len)`, `probe(base, addr)`(`I2C_*` 결과) | `I2cBus<C: I2cChip>` | `I2cGeneric`, `Stm32F4I2c` |
| `SpiChip` | `init(base, mode)`, `transfer(base, tx)`, `cs(base, asserted)` | `SpiDevice<C: SpiChip>` | `SpiGeneric`, `Stm32F4Spi` |
| `TimerChip` | `init(base, prescaler)`, `set_period(base, period)`, `run(base, on)`, `count(base)`, `flag(base)`, `clear_flag(base)` | `Timer<C: TimerChip>` | `TimerGeneric`, `Stm32F4Tim` |
| `WatchdogChip` | `init(base, timeout)`, `enable(base)`, `feed(base)`, `caused_reset(base)` | `Watchdog<C: WatchdogChip>` | `WatchdogGeneric`, `Stm32F4Iwdg` |

메서드가 하나라도 빠진 칩으로 드라이버를 지정하면 드라이버 내부 깊은 곳이
아니라 선언한 자리에서 오류가 납니다:

```text
struct Uart<Stm32F4Gpio> con = Uart::new(base, 115200u, 0u);
error: type 'Stm32F4Gpio' does not satisfy constraint 'UartChip' for generic
       parameter 'C' of struct 'Uart' (no method 'init')
```

직접 만든 부품은 메서드를 갖추는 것만으로 트레이트를 만족합니다 — 트레이트에
대해 따로 선언할 것은 없습니다. 트레이트에 대해 작성한 코드는 모든 칩에서
동작합니다:

```c
#include <std/hal/uart.h>

// base + 0에 쓰기 전용 데이터 레지스터 하나가 있는 디버그 포트.
struct DebugPort {
    int           unused_;
    void          init(unsigned long base, unsigned int baud, unsigned int clock_hz) const;
    int           tx_ready(unsigned long base) const;
    int           rx_ready(unsigned long base) const;
    void          put(unsigned long base, unsigned char c) const;
    unsigned char get(unsigned long base) const;
};
void DebugPort::init(unsigned long base, unsigned int baud, unsigned int clock_hz) const { }
int  DebugPort::tx_ready(unsigned long base) const { return 1; }
int  DebugPort::rx_ready(unsigned long base) const { return 0; }
void DebugPort::put(unsigned long base, unsigned char c) const {
    unsafe { *(&volatile unsigned int)base = (unsigned int)c; }
}
unsigned char DebugPort::get(unsigned long base) const { return (unsigned char)0; }

// 모든 UART 칩에 대해 한 번만 작성합니다.
generic<C: UartChip>
void banner(&Uart<C> con) {
    con.write_str("boot\n");
}

int main() {
    unsigned int reg = 0u;
    struct Uart<DebugPort> con = Uart::new(&reg, 0u, 0u);
    banner(&con);
    return reg == 10u ? 0 : 1;   // 마지막으로 쓴 바이트는 '\n'
}
```

### 기준 레이아웃 {#hal-generic-layouts}

`*Generic` 칩은 다음 레지스터를 씁니다(`base`로부터의 바이트 오프셋, 32비트
레지스터, GPIO는 핀마다 1비트):

| 칩 | 레지스터 |
|---|---|
| `GpioGeneric` | `DIR` +0x00(1 = 출력), `OUT` +0x04, `IN` +0x08, `PULL_EN` +0x0C, `PULL_SEL` +0x10(1 = 풀업, 0 = 풀다운) |
| `UartGeneric` | `DATA` +0x00, `STATUS` +0x04(비트 0 RX 준비, 비트 1 TX 준비), `BAUD` +0x08(`baud`를 기록) |
| `SpiGeneric` | `DATA` +0x00, `STATUS` +0x04(비트 0 사용 중, 비트 1 TX 준비), `CTRL` +0x08(`mode` \| 0x80 활성화), `CS` +0x0C(0 = 선택) |
| `I2cGeneric` | `DATA` +0x00, `STATUS` +0x04(비트 0 사용 중, 비트 1 ACK, 비트 2 오류), `CTRL` +0x08(1 START, 2 STOP, 4 SEND, 8 READ+ACK, 16 READ+NACK), `ADDR` +0x0C, `SPEED` +0x10 |
| `TimerGeneric` | `CTRL` +0x00(비트 0 활성화), `PRESCALER` +0x04, `PERIOD` +0x08, `COUNT` +0x0C, `STATUS` +0x10(비트 0 플래그) |
| `WatchdogGeneric` | `CTRL` +0x00(비트 0 활성화), `TIMEOUT` +0x04, `FEED` +0x08(0x6969 기록), `STATUS` +0x0C(비트 0: 마지막 리셋이 워치독 리셋) |

모두 원시 MMIO 주소에 직접 작동하는 폴링 기반 드라이버입니다(인터럽트 기반
변형은 없음) — 주변장치가 완료를 비동기적으로 알려야 할 경우 인터럽트를
다루는 쪽은 [`std::interrupt`](/ko/stdlib/interrupt)(ISR 등록, 벡터
테이블, MMIO 비트필드 헬퍼)를 참고합니다.

## ARM Cortex-M {#arm-cortex-m}

`hal/cortex_m.h` — NVIC, SysTick, SCB를 Cortex-M 표준 메모리 맵 주소에
위치한 전역 인스턴스로 제공합니다. 전체 설명(HAL 사용법, DSP 확장
내장 함수, MVE)은 [베어메탈 → ARM Cortex-M](/ko/reference/baremetal#arm-cortex-m)을
참고합니다.

```c
#define NVIC_BASE     0xE000E100UL
#define SYSTICK_BASE  0xE000E010UL
#define SCB_BASE      0xE000ED00UL

struct Nvic {                      // global instance: nvic (nvic_init())
    &volatile unsigned int base;
    void  enable(unsigned int irq);
    void  disable(unsigned int irq);
    int   is_pending(unsigned int irq) const;
    void  clear_pending(unsigned int irq);
    void  set_priority(unsigned int irq, unsigned char priority);
    unsigned char get_priority(unsigned int irq) const;
};

struct SysTick {                   // global instance: systick (systick_init())
    &volatile unsigned int base;
    void  start(unsigned int reload, int use_core_clock);
    void  stop();
    unsigned int read() const;
    int   flag_set() const;        // COUNTFLAG: wrapped since last read
};

struct Scb {                       // global instance: scb (scb_init())
    &volatile unsigned int base;
    void  system_reset();
    void  enable_fault(unsigned int fault_mask);   // SCB_FAULT_* bits
    void  disable_fault(unsigned int fault_mask);
    void  set_vtor(unsigned int addr);             // relocate the vector table
    int   in_handler_mode() const;                 // 1 inside an exception handler
    void  set_psp(unsigned int addr);              // process stack pointer
    void  set_msp(unsigned int addr);              // main stack pointer
};

#define SCB_FAULT_USAGE  (1u << 18)
#define SCB_FAULT_BUS    (1u << 17)
#define SCB_FAULT_MEM    (1u << 16)
```

## AArch64 {#aarch64}

`hal/aarch64.h` — 위의 Cortex-M *마이크로컨트롤러* HAL과는 별개로,
ARMv8-A 애플리케이션 코어(Cortex-A53/A57/A72/A55/A76)를 대상으로 합니다.

**시스템 레지스터**(인스턴스 없이 사용하는 자유 함수):
`aa64_read_mpidr()`(프로세서 어피니티/CPU ID), `aa64_read_currentel()`
(예외 레벨 EL0–EL3), `aa64_read_daif()`/`aa64_write_daif()`(인터럽트
마스크 비트), `aa64_irq_enable()`/`aa64_irq_disable()`,
`aa64_fiq_enable()`/`aa64_fiq_disable()`, `aa64_isb()`/`aa64_dsb_sy()`/
`aa64_dmb_sy()`(배리어).

**제네릭 타이머**(`Aa64Timer`, 전역 인스턴스 `aa64_timer`):
`read_cntpct()`(시스템 카운터), `read_cntfrq()`(카운터 주파수),
`set_tval()`, `enable()`/`disable()`, `fire_pending()`.

**GIC**(GICv2 호환; `GicDist`/`GicCpu`, 전역 인스턴스 `gic_dist`/
`gic_cpu`, `gic_init(dist_base, cpu_base)`로 초기화; 기본 주소는
`GIC_DIST_BASE` = `0x08000000`, `GIC_CPU_BASE` = `0x08010000`으로 QEMU
`virt` / Juno 배치): 분배기(distributor) 쪽의 `enable_group0`,
`enable_irq`/`disable_irq`, `set_priority`, `set_target`(CPU 어피니티
마스크), `set_config`(에지 트리거 대 레벨 트리거),
`is_pending`/`clear_pending`; CPU 인터페이스 쪽의 `ack()`(대기 중인 IRQ
번호를 반환), `eoi()`(인터럽트 종료), `running_priority()`.

## RISC-V {#risc-v}

`hal/riscv.h` — 주소는 SiFive FE310 / 표준 RISC-V MMIO 맵을 따릅니다.

**CSR 접근**(인라인 `asm`을 사용하는 자유 함수):
`rv_csr_read_mstatus`/`rv_csr_write_mstatus`, `rv_csr_read_mie`/
`rv_csr_write_mie`, `rv_csr_read_mip`, `rv_csr_read_mcause`,
`rv_csr_read_mepc`/`rv_csr_write_mepc`, `rv_csr_read_mtvec`/
`rv_csr_write_mtvec`, `rv_csr_read_time`/`rv_csr_read_cycle`/
`rv_csr_read_instret`, 그리고 `rv_global_irq_enable`/
`rv_global_irq_disable`.

**CLINT**(`Clint`, 전역 인스턴스 `clint`, `clint_init(base)`로 생성; 기본 주소 `CLINT_BASE` = `0x02000000`):
`set_msip`/`clear_msip`(하트별 소프트웨어 인터럽트), `set_mtimecmp`/
`read_mtime`, `schedule(delta)`(`delta` 틱 뒤에 타이머 인터럽트 발생).

**PLIC**(`Plic`, 전역 인스턴스 `plic`, `plic_init(base)`로 생성; 기본 주소 `PLIC_BASE` = `0x0C000000`, IRQ는 `PLIC_MAX_IRQ` = 53까지):
`set_priority`, `enable`/`disable`, `set_threshold`, `claim()`(우선순위가
가장 높은 대기 중 IRQ, 0이면 없음), `complete(irq)`.
