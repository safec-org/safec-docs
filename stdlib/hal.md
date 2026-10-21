# Hardware Abstraction Layer (`std::hal`)

`std/hal/` wraps common embedded peripherals as methods on a struct
instance holding the peripheral's MMIO base address — construct one with
its [static](/reference/functions#static-methods) `new()` (`GpioPin::new(...)`,
`Uart::new(...)`), then call methods on it. Each driver is generic over a
*chip type* that knows that peripheral's registers, picked at compile time
from the variable's type (the same pattern as `BST<K, V, C>`'s ordering)
and checked against a [chip trait](#chip-traits), so the calls compile
down to direct register accesses. Every module is freestanding-safe (no libc dependency) and works
with plain `--target` cross-compilation as well as `--freestanding`.

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

Peripheral base addresses are `&volatile unsigned int` references, so every
register access is a volatile load or store. Make one from a datasheet
address once, inside `unsafe` — the compiler can't know the address is valid:

```c
&volatile unsigned int GPIOA_BASE() {
    unsafe { return (&volatile unsigned int)0x40020000UL; }
}
```

Remember that struct *types* aren't namespaced even though their static
methods are called through the namespace — `struct GpioPin`, but
`std::GpioPin::new(...)` from outside `namespace std` (see
[Namespaces](/reference/namespaces#what-gets-namespaced)). The chip type
comes from the declared variable, so `new` takes no type argument.

## Drivers and Chips {#generic-peripherals}

| Module | Header | Type | Description |
|---|---|---|---|
| GPIO | `hal/gpio.h` | `GpioPin<C>` | `GpioPin::new(base, pin, direction)`; `set_direction`, `write`, `read`, `toggle`, `set_pull` |
| I2C | `hal/i2c.h` | `I2cBus<C>` | `I2cBus::new(base, speed, clock_hz)`; `write`, `read`, `write_read` (write, then read), `probe`; a failed transfer still sends STOP, releasing the bus |
| SPI | `hal/spi.h` | `SpiDevice<C>` | `SpiDevice::new(base, mode)`; `transfer` (one byte, full-duplex), `cs_assert`/`cs_deassert`, `write(tx, ?&rx, len)` (`rx` may be `null`), `read` |
| UART | `hal/uart.h` | `Uart<C>` | `Uart::new(base, baud, clock_hz)`; `write_byte`, `read_byte`, `write_str`, `rx_ready`, `tx_ready` |
| Timer | `hal/timer.h` | `Timer<C>` | `Timer::new(base, prescaler)`; `set_period`, `start`, `stop`, `read`, `clear_flag`, `flag_set` |
| Watchdog | `hal/watchdog.h` | `Watchdog<C>` | `Watchdog::new(base, timeout)`; `enable` (typically cannot be disabled once armed), `feed`, `caused_reset` |

Constants: `GPIO_INPUT`/`GPIO_OUTPUT`, `GPIO_PULL_NONE`/`GPIO_PULL_UP`/`GPIO_PULL_DOWN`; I2C results `I2C_OK`/`I2C_NACK`/`I2C_ERROR`; `SPI_MODE_0`–`SPI_MODE_3` (CPOL/CPHA = 00, 01, 10, 11).

`clock_hz` is the peripheral's input clock, used to compute the baud-rate
or bus-speed divider; `Uart::new` with `clock_hz = 0` keeps the rate the
boot firmware set.

### Chip Types {#hal-chips}

| Chip type | Peripheral |
|---|---|
| `GpioGeneric`, `UartGeneric`, `SpiGeneric`, `I2cGeneric`, `TimerGeneric`, `WatchdogGeneric` | The reference register layouts below — for an FPGA soft core or a simulator that implements them |
| `Pl011` | ARM PrimeCell PL011 UART (QEMU `virt`, Raspberry Pi, many Arm SoCs) |
| `Ns16550` | 16550-compatible UART with byte-wide registers at consecutive addresses (QEMU RISC-V `virt`, many SoCs) |
| `Stm32F4Gpio` | STM32 GPIO port (`MODER`/`PUPDR`/`IDR`/`ODR`/`BSRR`; the F2/F4/F7/L4/G0/H7 layout). `write` uses `BSRR`, so it needs no read-modify-write |
| `Stm32F4Usart` | STM32F1/F2/F4 USART (`SR`/`DR`/`BRR`/`CR1`) |
| `Stm32F4Spi` | STM32F1/F2/F4 SPI as master, software NSS, fPCLK/16. Chip select is an ordinary GPIO there: `cs_assert`/`cs_deassert` do nothing — drive a `GpioPin` |
| `Stm32F4I2c` | STM32F1/F2/F4 I2C ("v1": `CR1`/`CR2`/`DR`/`SR1`/`SR2`/`CCR`/`TRISE`), standard and fast mode |
| `Stm32F4Tim` | STM32 general-purpose timer (TIM2–TIM5): `PSC`, `ARR`, `CNT`, update flag `UIF`. The counter runs at clock / (prescaler + 1) |
| `Stm32F4Iwdg` | STM32 independent watchdog: `timeout` is the 12-bit reload value counted at LSI / 256 (about 8 ms per unit at 32 kHz); `caused_reset` reads `RCC_CSR.IWDGRSTF` at the STM32F4 RCC address |

A chip type is a struct whose methods take the peripheral's base address
and do the register work, as the matching [chip trait](#chip-traits)
lists them, so another part is supported by writing one more such struct.

The `Pl011`, `Ns16550`, `Stm32F4Usart`, `Stm32F4Tim` and `Stm32F4Spi` chips
run against QEMU's models of those peripherals in
`tests/stdlib/baremetal/qemu/run.sh`, and every chip's register sequence is
checked against RAM-backed registers in the hosted tests.

### Chip Traits {#chip-traits}

Each driver's chip parameter is bounded by a
[trait](/reference/generics#bounded-generic-structs) that lists the
register operations the driver calls. Every method takes the
peripheral's base address as an `unsigned long` and is `const`: a chip
type holds no state of its own (the `std` chips have a single unused
`int` field).

| Trait | Requires | Bounds | Satisfied by |
|---|---|---|---|
| `GpioChip` | `set_direction(base, pin, direction)`, `write(base, pin, value)`, `read(base, pin)`, `toggle(base, pin)`, `set_pull(base, pin, pull)` | `GpioPin<C: GpioChip>` | `GpioGeneric`, `Stm32F4Gpio` |
| `UartChip` | `init(base, baud, clock_hz)`, `tx_ready(base)`, `rx_ready(base)`, `put(base, c)`, `get(base)` | `Uart<C: UartChip>` | `UartGeneric`, `Pl011`, `Ns16550`, `Stm32F4Usart` |
| `I2cChip` | `init(base, speed, clock_hz)`, `write(base, addr, data, len)`, `read(base, addr, data, len)`, `probe(base, addr)` (the `I2C_*` results) | `I2cBus<C: I2cChip>` | `I2cGeneric`, `Stm32F4I2c` |
| `SpiChip` | `init(base, mode)`, `transfer(base, tx)`, `cs(base, asserted)` | `SpiDevice<C: SpiChip>` | `SpiGeneric`, `Stm32F4Spi` |
| `TimerChip` | `init(base, prescaler)`, `set_period(base, period)`, `run(base, on)`, `count(base)`, `flag(base)`, `clear_flag(base)` | `Timer<C: TimerChip>` | `TimerGeneric`, `Stm32F4Tim` |
| `WatchdogChip` | `init(base, timeout)`, `enable(base)`, `feed(base)`, `caused_reset(base)` | `Watchdog<C: WatchdogChip>` | `WatchdogGeneric`, `Stm32F4Iwdg` |

Naming a driver with a chip that lacks one of the methods is an error at
the declaration, not deep inside the driver:

```text
struct Uart<Stm32F4Gpio> con = Uart::new(base, 115200u, 0u);
error: type 'Stm32F4Gpio' does not satisfy constraint 'UartChip' for generic
       parameter 'C' of struct 'Uart' (no method 'init')
```

A part of your own conforms by having the methods; nothing is declared
against the trait. Code written over the trait works for every chip:

```c
#include <std/hal/uart.h>

// A debug port with one write-only data register at base + 0.
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

// Written once, for every UART chip.
generic<C: UartChip>
void banner(&Uart<C> con) {
    con.write_str("boot\n");
}

int main() {
    unsigned int reg = 0u;
    struct Uart<DebugPort> con = Uart::new(&reg, 0u, 0u);
    banner(&con);
    return reg == 10u ? 0 : 1;   // the last byte written was '\n'
}
```

### Reference Layouts {#hal-generic-layouts}

The `*Generic` chips use these registers (byte offsets from `base`, 32-bit
registers, one bit per pin for GPIO):

| Chip | Registers |
|---|---|
| `GpioGeneric` | `DIR` +0x00 (1 = output), `OUT` +0x04, `IN` +0x08, `PULL_EN` +0x0C, `PULL_SEL` +0x10 (1 = up, 0 = down) |
| `UartGeneric` | `DATA` +0x00, `STATUS` +0x04 (bit 0 RX ready, bit 1 TX ready), `BAUD` +0x08 (written with `baud`) |
| `SpiGeneric` | `DATA` +0x00, `STATUS` +0x04 (bit 0 busy, bit 1 TX ready), `CTRL` +0x08 (`mode` \| 0x80 enable), `CS` +0x0C (0 = asserted) |
| `I2cGeneric` | `DATA` +0x00, `STATUS` +0x04 (bit 0 busy, bit 1 ACK, bit 2 error), `CTRL` +0x08 (1 START, 2 STOP, 4 SEND, 8 READ+ACK, 16 READ+NACK), `ADDR` +0x0C, `SPEED` +0x10 |
| `TimerGeneric` | `CTRL` +0x00 (bit 0 enable), `PRESCALER` +0x04, `PERIOD` +0x08, `COUNT` +0x0C, `STATUS` +0x10 (bit 0 flag) |
| `WatchdogGeneric` | `CTRL` +0x00 (bit 0 enable), `TIMEOUT` +0x04, `FEED` +0x08 (write 0x6969), `STATUS` +0x0C (bit 0: last reset was a watchdog reset) |

All are polling-based drivers (no interrupt-driven variants) operating
directly on raw MMIO addresses — see [`std::interrupt`](/stdlib/interrupt)
for the interrupt-handling side (ISR registration, vector tables, MMIO
bitfield helpers) if a peripheral needs to signal completion
asynchronously instead.

## ARM Cortex-M {#arm-cortex-m}

`hal/cortex_m.h` — NVIC, SysTick, SCB as global instances at their
standard Cortex-M memory-mapped addresses; see
[Bare-Metal → ARM Cortex-M](/reference/baremetal#arm-cortex-m) for the
full walkthrough (HAL usage, DSP-extension intrinsics, MVE).

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

`hal/aarch64.h` — targets ARMv8-A application cores (Cortex-A53/A57/A72/
A55/A76), distinct from the Cortex-M *microcontroller* HAL above.

**System registers** (free functions, no instance needed):
`aa64_read_mpidr()` (processor affinity/CPU ID), `aa64_read_currentel()`
(exception level EL0–EL3), `aa64_read_daif()`/`aa64_write_daif()`
(interrupt mask bits), `aa64_irq_enable()`/`aa64_irq_disable()`,
`aa64_fiq_enable()`/`aa64_fiq_disable()`, `aa64_isb()`/`aa64_dsb_sy()`/
`aa64_dmb_sy()` (barriers).

**Generic Timer** (`Aa64Timer`, global instance `aa64_timer`):
`read_cntpct()` (system counter), `read_cntfrq()` (counter frequency),
`set_tval()`, `enable()`/`disable()`, `fire_pending()`.

**GIC** (GICv2-compatible; `GicDist`/`GicCpu`, global instances
`gic_dist`/`gic_cpu`, initialized via `gic_init(dist_base, cpu_base)`;
default bases `GIC_DIST_BASE` = `0x08000000` and `GIC_CPU_BASE` =
`0x08010000`, the QEMU `virt` / Juno layout):
`enable_group0`, `enable_irq`/`disable_irq`, `set_priority`, `set_target`
(CPU affinity mask), `set_config` (edge- vs level-triggered),
`is_pending`/`clear_pending` on the distributor; `ack()` (returns the
pending IRQ number), `eoi()` (end-of-interrupt) and `running_priority()`
on the CPU interface.

## RISC-V {#risc-v}

`hal/riscv.h` — addresses follow the SiFive FE310 / standard RISC-V MMIO
map.

**CSR access** (free functions, backed by inline `asm`):
`rv_csr_read_mstatus`/`rv_csr_write_mstatus`, `rv_csr_read_mie`/
`rv_csr_write_mie`, `rv_csr_read_mip`, `rv_csr_read_mcause`,
`rv_csr_read_mepc`/`rv_csr_write_mepc`, `rv_csr_read_mtvec`/
`rv_csr_write_mtvec`, `rv_csr_read_time`/`rv_csr_read_cycle`/
`rv_csr_read_instret`, plus `rv_global_irq_enable`/`rv_global_irq_disable`.

**CLINT** (`Clint`, global instance `clint`, via `clint_init(base)`; default base `CLINT_BASE` = `0x02000000`):
`set_msip`/`clear_msip` (per-hart software interrupt), `set_mtimecmp`/
`read_mtime`, `schedule(delta)` (timer interrupt `delta` ticks out).

**PLIC** (`Plic`, global instance `plic`, via `plic_init(base)`; default base `PLIC_BASE` = `0x0C000000`, IRQs up to `PLIC_MAX_IRQ` = 53):
`set_priority`, `enable`/`disable`, `set_threshold`, `claim()` (highest-
priority pending IRQ, 0 = none), `complete(irq)`.
