# Hardware Abstraction Layer (`std::hal`)

`std/hal/` wraps common embedded peripherals as methods on a struct
instance holding the peripheral's MMIO base address — construct one with
the matching `*_init()` function, then call methods on it. Every module is
freestanding-safe (no libc dependency) and works with plain `--target`
cross-compilation as well as `--freestanding`.

```c
#include <std/hal/gpio.h>

void blink(&volatile unsigned int gpio_base, int pin) {
    struct GpioPin led = std::gpio_init(gpio_base, pin, GPIO_OUTPUT);
    led.write(1);
    led.toggle();
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

Remember that struct *types* aren't namespaced even though the
constructor functions are — `struct GpioPin`, not `struct std::GpioPin`
(see [Namespaces](/reference/namespaces#what-gets-namespaced)).

## Generic Peripherals

| Module | Header | Type | Description |
|---|---|---|---|
| GPIO | `hal/gpio.h` | `GpioPin` | `gpio_init(base, pin, direction)`; `set_direction`, `write`, `read`, `toggle`, `set_pull` |
| I2C | `hal/i2c.h` | `I2cBus` | `i2c_init(base, speed)`; `write`, `read`, `write_read` (combined transaction), `probe` |
| SPI | `hal/spi.h` | `SpiDevice` | `spi_init(base, mode)`; `transfer` (one byte, full-duplex), `cs_assert`/`cs_deassert`, `write`, `read` |
| UART | `hal/uart.h` | `Uart` | `uart_init(base, baud)`; `write_byte`, `read_byte`, `write_str`, `rx_ready`, `tx_ready` (all polling-based) |
| Timer | `hal/timer.h` | `Timer` | `timer_init(base, prescaler)`; `set_period`, `start`, `stop`, `read`, `clear_flag`, `flag_set` |
| Watchdog | `hal/watchdog.h` | `Watchdog` | `watchdog_init(base, timeout)`; `enable` (typically cannot be disabled once armed), `feed`, `caused_reset` |

Constants: `GPIO_INPUT`/`GPIO_OUTPUT`, `GPIO_PULL_NONE`/`GPIO_PULL_UP`/`GPIO_PULL_DOWN`; I2C results `I2C_OK`/`I2C_NACK`/`I2C_ERROR`; `SPI_MODE_0`–`SPI_MODE_3` (CPOL/CPHA = 00, 01, 10, 11).

All are polling-based drivers (no interrupt-driven variants) operating
directly on raw MMIO addresses — see [`std::interrupt`](/stdlib/interrupt)
for the interrupt-handling side (ISR registration, vector tables, MMIO
bitfield helpers) if a peripheral needs to signal completion
asynchronously instead.

## ARM Cortex-M

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

## AArch64

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

## RISC-V

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
