# Interrupts & MMIO (`std::interrupt`)

`std/interrupt/` covers the software-managed side of interrupt handling —
vector tables, ISR registration/dispatch, MMIO register access, bitfield
manipulation, and clock configuration — complementing the peripheral
drivers in [`std::hal`](/stdlib/hal). All freestanding-safe.

```c
#include <std/interrupt/mmio.h>
#include <std/interrupt/bitfield.h>

void configure_uart(void* uart_base) {
    struct MmioReg ctrl = std::mmio_reg(uart_base);
    unsigned int baud_field = ctrl.read_field(0, 15);
    ctrl.write_field(0, 15, baud_field | 1u);

    unsigned int combined = std::bf_insert32(0, 4, 7, 0xA);
}
```

## MMIO (`interrupt/mmio.h`)

Two equivalent APIs over the same operations: a `MmioReg` struct
(`mmio_reg(addr)` to construct, then methods) for grouping several
accesses to one register, and free functions for one-off access.

| `MmioReg` method | Equivalent free function |
|---|---|
| `read32()` / nothing (write-only free fn) | `mmio_read32(addr)` / `mmio_write32(addr, val)` |
| — | `mmio_read16`/`mmio_write16`, `mmio_read8`/`mmio_write8` |
| `set_bits(mask)` / `clear_bits(mask)` | `mmio_set_bits32(addr, mask)` / `mmio_clear_bits32(addr, mask)` |
| `read_field(lo, hi)` / `write_field(lo, hi, val)` | `mmio_read_field32(addr, lo, hi)` / `mmio_write_field32(addr, lo, hi, val)` |

## Bitfield Helpers (`interrupt/bitfield.h`)

Pure functions for register bit manipulation — `bf_extract32(val, lo, hi)`,
`bf_insert32(val, lo, hi, field)`, `bf_set32`/`bf_clear32`/`bf_toggle32`
(bits `[hi:lo]`), `bf_test32(val, n)` (single-bit test), `bf_mask32(lo, hi)`
(build a mask with no register access), `bf_popcount32(val)`.

## ISR Vector Table (`interrupt/isr.h`)

A software dispatch table distinct from the hardware vector table below —
`IsrTable` holds up to `ISR_MAX` (256) handler function pointers:

```c
struct IsrTable table = std::isr_init();
unsafe { table.register_(3, (void*)my_handler); }  // trailing underscore: 'register' is a C keyword
...
if (table.dispatch(3)) { /* handler ran */ }
```

Plus `irq_disable()`/`irq_enable()` — platform-specific inline `asm`
wrapping the global interrupt-enable flag.

## Hardware Vector Table (`interrupt/vector_table.h`)

`VectorTable` (global instance `vtable`) is the actual CPU-recognized
vector table — `install(index, handler)`/`remove(index)`/`get(index)` to
populate it, `dispatch(index)` to invoke a slot's handler (or the default
handler, set via `set_default()`), and `vtable_activate()` to point the
CPU at it:

| Target | What `vtable_activate()` does |
|---|---|
| Cortex-M | Writes `SCB->VTOR` |
| RISC-V | Writes `mtvec` (vectored mode) |
| AArch64 | Writes `VBAR_EL1` |

`vtable_init()` sets every slot to `vtable_default_handler` (a weak,
infinite-loop fault handler meant to be overridden per project) before use.

## Clock Configuration (`interrupt/clock.h`)

`ClockConfig` (via `clock_init(base)`) — `set_source()` (`CLK_SRC_HSI`/
`CLK_SRC_HSE`/`CLK_SRC_PLL`), `configure_pll(mul, div)`, `set_ahb_div`/
`set_apb1_div`/`set_apb2_div` (bus prescalers), `get_freq()`.
