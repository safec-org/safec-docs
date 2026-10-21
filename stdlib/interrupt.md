# Interrupts & MMIO (`std::interrupt`)

`std/interrupt/` covers the software-managed side of interrupt handling —
vector tables, ISR registration/dispatch, MMIO register access, bitfield
manipulation, and clock configuration — complementing the peripheral
drivers in [`std::hal`](/stdlib/hal). All freestanding-safe.

```c
#include <std/interrupt/mmio.h>
#include <std/interrupt/bitfield.h>

void configure_uart(&volatile unsigned int uart_base) {
    struct MmioReg ctrl = std::mmio_reg(uart_base);
    unsigned int baud_field = ctrl.read_field(0, 15);
    ctrl.write_field(0, 15, baud_field | 1u);

    unsigned int combined = std::bf_insert32(0, 4, 7, 0xA);
}
```

## MMIO (`interrupt/mmio.h`) {#mmio-interruptmmioh}

Register addresses are `&volatile` references (`&volatile unsigned int` for
32-bit registers), created from a fixed address inside `unsafe` once — see
[HAL](/stdlib/hal) for the pattern.

Two equivalent APIs over the same operations: a `MmioReg` struct
(`mmio_reg(addr)` to construct, then methods) for grouping several
accesses to one register, and free functions for one-off access.

| `MmioReg` method | Equivalent free function |
|---|---|
| `read32()` / `write32(val)` | `mmio_read32(addr)` / `mmio_write32(addr, val)` |
| — | `mmio_read16`/`mmio_write16`, `mmio_read8`/`mmio_write8` |
| `set_bits(mask)` / `clear_bits(mask)` | `mmio_set_bits32(addr, mask)` / `mmio_clear_bits32(addr, mask)` |
| `read_field(lo, hi)` / `write_field(lo, hi, val)` | `mmio_read_field32(addr, lo, hi)` / `mmio_write_field32(addr, lo, hi, val)` |

The 8- and 16-bit accessors make 8- and 16-bit bus accesses, so they never touch neighbouring registers. Field helpers accept the full width, `(0, 31)`.

## Bitfield Helpers (`interrupt/bitfield.h`) {#bitfield-helpers-interruptbitfieldh}

Pure functions for register bit manipulation — `bf_extract32(val, lo, hi)`,
`bf_insert32(val, lo, hi, field)`, `bf_set32`/`bf_clear32`/`bf_toggle32`
(bits `[hi:lo]`), `bf_test32(val, n)` (single-bit test), `bf_mask32(lo, hi)`
(build a mask with no register access), `bf_popcount32(val)`.

## ISR Vector Table (`interrupt/isr.h`) {#isr-vector-table-interruptisrh}

A software dispatch table distinct from the hardware vector table below —
`IsrTable` holds up to `ISR_MAX` (256) `fn void()` handlers:

```c
static void uart_rx_isr() { /* ... */ }

struct IsrTable table = std::isr_init();
table.register_(3, uart_rx_isr);   // trailing underscore: 'register' is a C keyword
// re-registering an IRQ replaces its handler
...
if (table.dispatch(3)) { /* handler ran */ }
```

Plus `irq_disable()`/`irq_enable()`, which mask and unmask IRQs at the CPU
on bare-metal targets: `cpsid i`/`cpsie i` (ARM), `msr daifset/daifclr, #2`
(AArch64), clearing/setting `mstatus.MIE` (RISC-V) and `cli`/`sti` (x86).
They need privileged mode; in a hosted process they are only compiler
barriers.

## Hardware Vector Table (`interrupt/vector_table.h`) {#hardware-vector-table-interruptvector_tableh}

`VectorTable` (global instance `vtable`) is the actual CPU-recognized
vector table — `install(index, handler)`/`remove(index)`/`get(index)` to
populate it, `dispatch(index)` to invoke a slot's handler (or the default
handler, set via `set_default()`), and `vtable_activate()` to point the
CPU at it:

| Target | What `vtable_activate()` does |
|---|---|
| Cortex-M | Writes `SCB->VTOR` (v7-M, v7E-M, v8-M, v8.1-M; not v6-M, where VTOR is optional) |
| RISC-V | Writes `mtvec` (vectored mode) |
| AArch64 | Writes `VBAR_EL1` |

`vtable_init()` sets every slot to `vtable_default_handler` (a weak,
infinite-loop fault handler meant to be overridden per project) before use.
`set_default(handler)` also retargets every slot still holding the
previous default, and `dispatch()` of an empty or out-of-range index
calls the current default.

`VTABLE_MAX_VECTORS` is 256 entries (Cortex-M: 16 core exceptions plus up to 240 external IRQs).

## Clock Configuration (`interrupt/clock.h`) {#clock-configuration-interruptclockh}

`ClockConfig` (via `clock_init(base)`) — `set_source()` (`CLK_SRC_HSI`/
`CLK_SRC_HSE`/`CLK_SRC_PLL`), `configure_pll(mul, div)`, `set_ahb_div`/
`set_apb1_div`/`set_apb2_div` (bus prescalers), `get_freq()`.
