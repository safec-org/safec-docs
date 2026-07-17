# Hardware Abstraction Layer (`std::hal`)

`std/hal/` wraps common embedded peripherals as methods on a struct
instance holding the peripheral's MMIO base address — construct one with
the matching `*_init()` function, then call methods on it. Every module is
freestanding-safe (no libc dependency) and works with plain `--target`
cross-compilation as well as `--freestanding`.

```c
#include <std/hal/gpio.h>

void blink(void* gpio_base, int pin) {
    struct GpioPin led = std::gpio_init(gpio_base, pin, GPIO_OUTPUT);
    led.write(1);
    led.toggle();
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
`gic_dist`/`gic_cpu`, initialized via `gic_init(dist_base, cpu_base)`):
`enable_irq`/`disable_irq`, `set_priority`, `set_target` (CPU affinity
mask), `set_config` (edge- vs level-triggered), `is_pending`/
`clear_pending` on the distributor; `ack()` (returns the pending IRQ
number)/`eoi()` (end-of-interrupt) on the CPU interface.

## RISC-V

`hal/riscv.h` — addresses follow the SiFive FE310 / standard RISC-V MMIO
map.

**CSR access** (free functions, backed by inline `asm`):
`rv_csr_read_mstatus`/`rv_csr_write_mstatus`, `rv_csr_read_mie`/
`rv_csr_write_mie`, `rv_csr_read_mip`, `rv_csr_read_mcause`,
`rv_csr_read_mepc`/`rv_csr_write_mepc`, `rv_csr_read_mtvec`/
`rv_csr_write_mtvec`, `rv_csr_read_time`/`rv_csr_read_cycle`/
`rv_csr_read_instret`, plus `rv_global_irq_enable`/`rv_global_irq_disable`.

**CLINT** (`Clint`, global instance `clint`, via `clint_init(base)`):
`set_msip`/`clear_msip` (per-hart software interrupt), `set_mtimecmp`/
`read_mtime`, `schedule(delta)` (timer interrupt `delta` ticks out).

**PLIC** (`Plic`, global instance `plic`, via `plic_init(base)`):
`set_priority`, `enable`/`disable`, `set_threshold`, `claim()` (highest-
priority pending IRQ, 0 = none), `complete(irq)`.
