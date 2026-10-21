# Debugging & Profiling

SafeC provides three debug modules in `std/debug/` for performance measurement, code coverage tracking, and hardware debug integration.

```c
#include <std/debug/debug.h>  // master header: perf + coverage + jtag
```

The SafeC compiler also emits DWARF debug information when invoked with `--g`:

| Flag | Output |
|------|--------|
| `--g lines` | DICompileUnit, DISubprogram per function, DILocation per statement |
| `--g full` | All of `lines` + DILocalVariable + `dbg.declare` per local variable |
| *(none)* | No debug metadata |

---

## perf — Hardware Performance Counters {#perf-hardware-performance-counters}

```c
#include <std/debug/perf.h>
```

`PerfCounter` reads architecture-specific cycle counters for sub-microsecond timing.

### Cycle sources {#cycle-sources}

| Architecture | Instruction |
|---|---|
| x86-64 | `rdtsc` (EDX:EAX) |
| AArch64 | `mrs %0, cntvct_el0` |
| RISC-V | `csrr %0, cycle` |
| Other | 0 (no hardware counter) |

### Struct {#struct}

```c
struct PerfCounter {
    unsigned long long start_val;
    unsigned long long end_val;
    unsigned long long freq_hz;  // CPU cycles per second

    static struct PerfCounter new();    // zeroed, freq_hz detected (see below)

    void               start();
    void               stop();
    unsigned long long ticks() const;   // end_val - start_val
    unsigned long long ns() const;      // ticks / freq_hz * 1e9
}
```

### Constructor {#constructor}

```c
struct PerfCounter pc = std::PerfCounter::new();   // a static method
```

**Hosted**: calibrates `freq_hz` by spinning ~10 ms on `POSIX clock()` and counting CPU cycles over that interval.

**Freestanding**: sets `freq_hz = 0`; `ns()` returns 0.

### One-shot reads {#one-shot-reads}

```c
unsigned long long perf_read_cycle();   // raw cycle counter for this arch
unsigned long long perf_read_ns();      // hosted: time_mono_ns(); 0 on freestanding
```

`PERF_BEGIN(ctr)` / `PERF_END(ctr)` wrap `ctr.start()` / `ctr.stop()` in [compat preprocessor mode](/reference/preprocessor).

### Example {#example}

```c
#include <std/debug/perf.h>
#include <std/io.h>

int main() {
    struct PerfCounter pc = std::PerfCounter::new();

    pc.start();
    // Work to measure
    for (int i = 0; i < 1000000; i++) {}
    pc.stop();

    std::print("ticks: "); std::println_int((long long)pc.ticks());
    std::print("ns:    "); std::println_int((long long)pc.ns());
    return 0;
}
```

---

## coverage — Source-Level Code Coverage {#coverage-source-level-code-coverage}

```c
#include <std/debug/coverage.h>
```

Instruments call sites with a single macro. Tracks how many times each site was reached. 1024 sites maximum.

### Global instance {#global-instance}

```c
extern struct Coverage coverage;
void coverage_init();  // zero all sites (call at program start)
```

### Struct {#struct-1}

```c
struct CovSite {
    const &char   file;
    int           line;
    unsigned long count;
}

struct Coverage {
    CovSite       sites[COV_MAX_SITES];  // 1024
    unsigned int  used;

    int           register_site(const &char file, int line);
    void          hit(int idx);
    unsigned long get(int idx) const;
    unsigned long covered_count() const;
    unsigned long coverage_pct() const;  // integer 0–100
    void          reset();
    void          report();              // print HIT/MISS per site (hosted only)
}
```

### COV_SITE() macro {#cov_site-macro}

Place `COV_SITE();` at any point you want to instrument. It registers once (via a `static int` guard) and increments the counter on every call:

```c
int clamp(int v, int lo, int hi) {
    COV_SITE();          // instruments this entry point
    if (v < lo) { COV_SITE(); return lo; }
    if (v > hi) { COV_SITE(); return hi; }
    return v;
}
```

### Example {#example-1}

```c
#include <std/debug/coverage.h>
#include <std/io.h>

int clamp(int v, int lo, int hi) {
    COV_SITE();
    if (v < lo) { COV_SITE(); return lo; }
    if (v > hi) { COV_SITE(); return hi; }
    return v;
}

int main() {
    coverage_init();

    clamp(5,  0, 10);   // middle branch
    clamp(-1, 0, 10);   // lo branch
    clamp(15, 0, 10);   // hi branch

    coverage.report();
    print("coverage: ");
    println_int(coverage.coverage_pct());
    return 0;
}
```

Sample output:
```
[cov] /path/clamp.sc:3 HIT (3)
[cov] /path/clamp.sc:4 HIT (1)
[cov] /path/clamp.sc:5 HIT (1)
coverage: 100
```

---

## jtag — Hardware Debug Helpers {#jtag-hardware-debug-helpers}

```c
#include <std/debug/jtag.h>
```

Arch-conditional breakpoints, semihosting, and Cortex-M ITM stimulus port output. Useful during early bring-up when a UART is not yet available.

### Breakpoints {#breakpoints}

```c
void debug_break();
```

Emits the native breakpoint instruction:

| Architecture | Instruction |
|---|---|
| AArch64 | `brk #0` |
| ARM / Thumb | `bkpt #0` |
| x86-64 | `int3` |
| RISC-V | `ebreak` |

### Semihosting (ARM / AArch64) {#semihosting-arm-aarch64}

Semihosting lets a Cortex-M or Cortex-A target send output to the host debugger (J-Link, OpenOCD) via the debug connection — no UART needed.

```c
void debug_semihost_puts(const &char s);  // SYS_WRITE0 — NUL-terminated
void debug_semihost_putc(char c);         // SYS_WRITEC — single character
```

These are **no-ops** on x86-64 and RISC-V.

### ITM Stimulus Ports (Cortex-M only) {#itm-stimulus-ports-cortex-m-only}

Registers at `ITM_STIMULUS_BASE` (`0xE0000000`) and `ITM_TCR_BASE` (`0xE0000E80`).

Instrumentation Trace Macrocell — sends data over the SWO pin for capture by a J-Link or similar probe.

```c
void itm_enable_port(unsigned char port);                 // set bit in ITM TER (0xE0000E00)
void itm_putc(unsigned char port, unsigned char c);       // write byte to stimulus register
void itm_put32(unsigned char port, unsigned int word);    // write 32-bit word
```

`itm_putc` and `itm_put32` poll until the stimulus register is ready (bit 0 == 1) before writing. They are no-ops if the ITM is not enabled or the port is not enabled.

### DBG_ASSERT {#dbg_assert}

```c
DBG_ASSERT(cond);
// Calls debug_break() if cond is false.
```

A function-like macro, so it exists only in [compat preprocessor mode](/reference/preprocessor). In safe mode write `if (!cond) { std::debug_break(); }`.

### Example {#example-2}

```c
#include <std/debug/jtag.h>

void board_bringup(int clocks_ok) {
    if (!clocks_ok) { std::debug_break(); }   // stop in the debugger

    // Early startup output via semihost before the UART is ready
    std::debug_semihost_puts("bring-up started\n");

    // Once the probe is attached, switch to ITM for lightweight tracing
    std::itm_enable_port(0);
    std::itm_putc(0, 'A');
}
```

::: info
ITM requires a Cortex-M3/M4/M7 or later and a debug probe that supports SWO capture (J-Link, ULINK, ST-LINK v3). Configure your probe's SWO clock to match your target CPU's speed.
:::
