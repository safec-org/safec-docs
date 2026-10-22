# Bare-Metal Programming

SafeC supports bare-metal and embedded development through freestanding mode, function attributes for interrupt handling, inline assembly, and volatile/atomic operations for hardware register access.

## Freestanding Mode {#freestanding-mode}

The `--freestanding` flag disables the standard library and produces code that does not depend on an OS:

```bash
safec firmware.sc --freestanding --emit-llvm -o firmware.ll
```

(Every `--target=<bare-metal alias>` below implies `--freestanding`.)

In freestanding mode:
- No implicit dependency on libc
- No startup code (`_start` / `main` convention is programmer-defined)
- No standard C header imports
- The programmer provides all runtime support

## Bare-Metal Targets {#targets}

`--target=<alias>` selects a no-OS target by name (WebAssembly has its own section below, [WebAssembly](#wasm)): it picks the LLVM triple, the CPU, the ISA features and the ABI, and implies `--freestanding`. `safec --list-targets` prints the table:

| Alias | Triple | CPU / ISA | ABI | `size_t` / pointer |
|---|---|---|---|---|
| `x86` | `i686-unknown-none-elf` | i686 | i386 SysV | 32-bit |
| `x86_64` | `x86_64-unknown-none-elf` | x86-64 | SysV | 64-bit |
| `aarch32` | `armv7a-none-eabi` | ARMv7-A (ARM state) | AAPCS, soft-float | 32-bit |
| `aarch64` | `aarch64-unknown-none-elf` | ARMv8-A | AAPCS64 | 64-bit |
| `thumb` | `thumbv6m-none-eabi` | Cortex-M0 (Thumb) | AAPCS, soft-float | 32-bit |
| `thumb2` | `thumbv7m-none-eabi` | Cortex-M3 (Thumb-2) | AAPCS, soft-float | 32-bit |
| `arm-6m` | `thumbv6m-none-eabi` | Cortex-M0/M0+/M1 (ARMv6-M) | AAPCS, soft-float | 32-bit |
| `arm-7m` | `thumbv7m-none-eabi` | Cortex-M3 (ARMv7-M) | AAPCS, soft-float | 32-bit |
| `arm-7em` | `thumbv7em-none-eabi` | Cortex-M4/M7 (ARMv7E-M, DSP) | AAPCS, soft-float | 32-bit |
| `arm-8m` | `thumbv8m.main-none-eabi` | Cortex-M33 (ARMv8-M Mainline) | AAPCS, soft-float | 32-bit |
| `arm-81m` | `thumbv8.1m.main-none-eabi` | Cortex-M55/M85 (ARMv8.1-M, Helium) | AAPCS, soft-float calls | 32-bit |
| `rv32i` | `riscv32-unknown-elf` | RV32I | `ilp32` | 32-bit |
| `rv32e` | `riscv32-unknown-elf` | RV32E (16 registers) | `ilp32e` | 32-bit |
| `rv64i` | `riscv64-unknown-elf` | RV64I | `lp64` | 64-bit |
| `rv64e` | `riscv64-unknown-elf` | RV64E (16 registers) | `lp64e` | 64-bit |
| `wasm32` | `wasm32-unknown-unknown` | WebAssembly | — | 32-bit |
| `wasm64` | `wasm64-unknown-unknown` | WebAssembly with 64-bit memory (memory64) | — | 64-bit |

Any other LLVM triple also works with `--target=<triple>`; refine it with `--target-cpu=<name>`, `--target-features=<+a,-b>` (LLVM `-mattr` syntax) and `--target-abi=<name>`. These are recorded in the module, so the object a later `clang -c` produces has to agree with them: `safec --print-target-clang-flags --target=<alias>` prints the matching clang flags (`--target=…`, `-march`/`-mabi` for RISC-V, `-mcpu`/`-mfloat-abi` for ARM), one per line.

```bash
safec firmware.sc -I /path/to/SafeC --target=arm-6m --emit-llvm -o firmware.ll
clang -c firmware.ll $(safec --print-target-clang-flags --target=arm-6m) -o firmware.o
ld.lld -T board.ld --gc-sections -static -nostdlib firmware.o libsafec_std.a libclang_rt.builtins.a -o firmware.elf
```

With `safeguard`, `--target` does all of this — see [safeguard](/advanced/safeguard#cross-targets).

What changes per target:

- **Architecture macros.** The compiler predefines the same architecture macros as clang, so `#if` blocks can pick per-ISA code: `__x86_64__`/`__amd64__`, `__i386__`, `__aarch64__`, `__riscv` with `__riscv_xlen` (32 or 64), `__wasm__` with `__wasm32__`/`__wasm64__`, and for 32-bit ARM `__arm__`, `__ARM_ARCH` (6, 7, 8), `__ARM_ARCH_PROFILE` (`'A'`, `'R'`, `'M'`), `__ARM_ARCH_<name>__` (`__ARM_ARCH_6M__`, `__ARM_ARCH_7EM__`, `__ARM_ARCH_8M_MAIN__`, `__ARM_ARCH_7A__`, …), `__thumb__` and, from ARMv7 (and ARMv6T2), `__thumb2__`. Every bare-metal target also defines `__SAFEC_FREESTANDING__`, and none of them defines an OS macro (`__linux__`, `__APPLE__`, `_WIN32`). See [Preprocessor](/reference/preprocessor#predefined-macros).
- **Pointer width.** `size_t`, `ptrdiff_t`, `uintptr_t` and `__SIZEOF_POINTER__` follow the target (4 bytes on the 32-bit targets above). SafeC's `long` is always 64-bit, so code that needs a pointer-sized integer should say `size_t`/`uintptr_t`, never `long`.
- **C struct-return ABI.** Aggregates returned by value are lowered to the target's convention: i386 always returns structs through a hidden pointer; AAPCS32 does so above 4 bytes; RISC-V above 2×XLEN; x86-64 and AArch64 above 16 bytes (AArch64 excepting homogeneous float aggregates); Win64 for any size other than 1, 2, 4 or 8 bytes. Large struct copies are emitted as `memcpy`/`memset` calls, which the runtime must provide.
- **Runtime helpers.** Targets without hardware multiply/divide or atomics call compiler-rt builtins (`__muldi3`, `__udivdi3`, …) and `__atomic_*` library functions: link `libclang_rt.builtins.a` for the target and provide the atomics (a single-core runtime can implement them with interrupts masked).

### Freestanding calls to libc names {#provides}

In freestanding mode a call to a hosted-only function (`malloc`, `printf`, …) gets a warning, since there is no libc to resolve it. A bare-metal runtime often defines some of those itself. Functions defined in the same translation unit never warn; for ones defined in another file, list them with `--provides=malloc,free,memcpy` and the warning is suppressed for exactly those names (`safeguard` passes the project's own definitions automatically).

### Testing on bare metal {#testing}

The standard library's own bare-metal suite (`tests/stdlib/baremetal/`) builds every `std/*.sc` for each target (`sweep.sh`), then links a test image with a minimal runtime (`bm_rt.sc`: `memcpy`/`memset`/`malloc` over a static arena, `__atomic_*` shims) at address `0x10000` and runs it in the Unicorn CPU emulator (`run.sh`, `run.py`); wasm32 and wasm64 images are linked with `wasm-ld` and run under Node (`run_wasm.mjs`). All 16 targets currently pass every check.

`tests/stdlib/baremetal/qemu/run.sh` goes one step further and boots small programs on QEMU's emulated hardware: the native [`MmuContext`](/stdlib/kernel#mmu-context-kernelmmuh) format with translation really on (RISC-V Sv39/Sv32 in S-mode, AArch64 and ARMv7-A with the MMU enabled, x86 32-bit paging and x86-64 long mode), and the [HAL chips](/stdlib/hal#hal-chips) PL011, 16550 and STM32F4 USART/TIM/SPI against QEMU's models of them. On x86 the start-up code also turns SSE on (`CR0`/`CR4`), since LLVM's i686 code uses it.

## WebAssembly {#wasm}

`--target=wasm32` and `--target=wasm64` build WebAssembly modules for a browser, Node or any other host. Like the bare-metal targets they're freestanding: there is no libc and no WASI. A module is a library the host instantiates, not a program with `main`:

- With `--emit-bin`, safec links through clang with `-nostdlib`, `--no-entry` and `--allow-undefined`.
- A module exports only the functions marked `export_name` (plus its linear memory).
- Every extern it calls but doesn't define becomes an import, from module `env` unless `import_module` names another.

```c
// add.sc
__attribute__((import_name("log_int"))) void host_log(int v);   // env.log_int

int counter = 0;

__attribute__((export_name)) int add(int a, int b) {               // exported as "add"
    return a + b;
}

__attribute__((export_name("bump"))) int bump_counter(int by) {   // exported as "bump"
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

| Attribute | Effect |
|---|---|
| `__attribute__((export_name))` | Export the function under its own name |
| `__attribute__((export_name("x")))` | Export it as `x` |
| `__attribute__((import_module("m")))` | Import a declared-only function from module `m` instead of `env` |
| `__attribute__((import_name("n")))` | Import it under the name `n` instead of its own |

Attributes can go before or after the declaration, on a prototype or a definition, and combine: `__attribute__((import_module("console"), import_name("log"))) void log_i(int v);` imports `console.log`. They are ignored on other targets.

The runtime is `std/wasm/wasm_rt.sc`. It has `malloc`/`free`/`calloc`/`realloc` — a size-class allocator over the module's linear memory: blocks are powers of two, a freed block is reused by the next allocation of its size class, and the memory grows (`memory.grow`) from the linker's `__heap_base` on demand, so a long-running page can allocate and free indefinitely — plus the libc functions std's own sources call (`memcpy`/`memmove`/`memset`/`memcmp`/`memchr`, `strlen`/`strcmp`/`strchr`/`strstr` and the rest of the `str*` family std uses, `strtoll`/`strtoull`/`strtod`/`atoi`, `qsort`) and the compiler-rt builtins LLVM emits for 128-bit arithmetic (`__multi3`, `__ashlti3`, `__lshrti3`, `__ashrti3`). `snprintf` is not provided, so float formatting (`fmt_float`, `String::push_float`) needs a host-side import on wasm. Its `abort()` is wasm's native trap, so the host sees a `RuntimeError: unreachable`; `printf`/`fprintf` discard their output, and `std::panic` traps the same way. With it, a module needs no host imports beyond the ones its own code declares.

`safeguard build --target wasm32` builds the std archive for wasm with `std/wasm` included, which no other target gets. It links the same kind of library module. `std/wasm/dom.h` and `hydrate.h` add DOM interop and client hydration for the [full-stack example](https://github.com/safec-org/SafeC/tree/main/examples/fullstack_demo).

## Function Attributes {#function-attributes}

### Naked Functions {#naked-functions}

Naked functions have no compiler-generated prologue or epilogue. The function body must consist entirely of inline assembly. Use these for bootloader entry points, ISR trampolines, and context switches.

```c
naked void _start() {
    asm volatile (
        "mov $stack_top, %rsp\n"
        "call main\n"
        "hlt"
    );
}
```

### Interrupt Functions {#interrupt-functions}

Interrupt functions use the ISR calling convention. They must be `void(void)` and the compiler generates appropriate entry/exit sequences (e.g., `iret` on x86):

```c
interrupt void timer_handler() {
    volatile int *timer_reg = (volatile int*)0x40000C00;
    unsafe { *timer_reg = 1; }  // acknowledge interrupt
}
```

### Noreturn Functions {#noreturn-functions}

Functions that never return can be annotated with `noreturn`, allowing the compiler to optimize call sites:

```c
noreturn void panic(const &char msg) {
    // ... write to UART ...
    while (1) {}
}
```

### Section Attribute {#section-attribute}

Place functions or variables in specific linker sections:

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

A function's code address is neither freed nor moved for the life of the
program, so storing one needs no `unsafe {}`, even at file scope where an
`unsafe {}` *block* couldn't appear anyway. Give the slot a function type
(`fn void()`) rather than an untyped pointer, so only functions of the right
shape can go in it:

```c
fn void() reset_vector = _start;      // a bare function name is its address
```

## Inline Assembly {#inline-assembly}

SafeC supports GCC-style extended inline assembly:

```c
asm [volatile] ( "template" [: outputs [: inputs [: clobbers]]] );
```

Constraints use GCC's letters: `r`, `m`, `i`, `=`/`+`, and on x86 the
register letters `a`, `b`, `c`, `d`, `S`, `D` (EAX/RAX, EBX, ECX, EDX,
ESI, EDI), so `asm volatile ("rdtsc" : "=a"(lo), "=d"(hi));` works as in C.

Inline `asm` needs an `unsafe {}` block, same as any other memory-unsafe
construct — except inside a `naked` function, where it's exempt (the whole
body is already required to be assembly, so there's nothing extra to opt
into).

### Basic Assembly {#basic-assembly}

```c
unsafe {
    asm volatile ("cli");          // disable interrupts
    asm volatile ("sti");          // enable interrupts
    asm volatile ("nop");          // no operation
    asm volatile ("hlt");          // halt processor
}
```

### Extended Assembly with Operands {#extended-assembly-with-operands}

```c
int result = 0;
unsafe {
    asm volatile (
        "mov %1, %0\n"
        "add $1, %0"
        : "=r"(result)             // output: result register
        : "r"(input)               // input: input register
        : "cc"                     // clobbers: condition codes
    );
}
```

A write-only output operand (`"=r"(result)`) counts as initializing its
target for the definite-initialization checker, so `int result;` (no
initializer) followed by `asm volatile (... : "=r"(result) ...)` is fine —
the `int result = 0;` above works too, just isn't required. A read-write
operand (`"+r"(var)`) is a genuine read as well as a write, so it still
requires `var` to already have a value before the `asm` statement.

### Reading Special Registers {#reading-special-registers}

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

### Memory-Mapped I/O with Assembly {#memory-mapped-io-with-assembly}

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

## Volatile Access {#volatile-access}

The `volatile` qualifier ensures that reads and writes are not optimized away or reordered by the compiler. This is essential for memory-mapped hardware registers.

### Volatile Variables {#volatile-variables}

```c
void uart_registers() {
    volatile int *UART_DATA = (volatile int*)0x40001000;
    volatile int *UART_STATUS = (volatile int*)0x40001004;
}
```

An integer-to-pointer cast of a compile-time-constant address (a literal,
or arithmetic on literals) can initialize a global directly, same as a
local:

```c
volatile int *UART_DATA   = (volatile int*)0x40001000;
volatile int *UART_STATUS = (volatile int*)(0x40001000 + 4);
```

### What `volatile` applies to {#what-volatile-applies-to}

Every load and store through a volatile lvalue is emitted as an LLVM `load volatile` / `store volatile`. That includes plain and compound assignment (`|=`, `+=`, …), `++`/`--`, subscripts, member access and dereference. The qualifier can sit in four places:

| Declaration | What is volatile |
|---|---|
| `volatile int x;` | the variable `x` (local or global) |
| `volatile unsigned int* reg` (or `unsigned int volatile* reg`) | the `unsigned int` that `reg` points at; `reg` itself is an ordinary variable |
| `int* volatile p` | the pointer variable `p`; the `int` it points at is not volatile |
| `volatile &Dev d` | the `Dev` behind the reference, so `d.ctrl = 1` is a volatile store |

A struct field can also be declared `volatile`. Accesses to that field are volatile, and the non-volatile fields of the same struct are still optimized normally:

```c
struct Dev {
    volatile unsigned int ctrl;   // device register: every access hits memory
    unsigned int shadow;          // ordinary field
};

void reset(&Dev d) {
    d.ctrl = 0;      // store volatile
    d.ctrl |= 4;     // load volatile + store volatile
    d.shadow = 0;    // may be merged or removed like any other store
}
```

The qualifier follows the object through everything that refers to it:

- `&x` of a volatile variable, field or element is `&volatile T`, and a volatile array decays to `volatile T*`.
- A `volatile` parameter is volatile inside the function, and a typedef can carry it: after `typedef volatile unsigned int reg_t;`, both `reg_t x;` and `reg_t* p;` are volatile.
- Dropping it implicitly is an error: `volatile T*` → `T*`, a volatile array → `T*`, `volatile T**` → `T**`, and `&volatile T` → `&T` or `T*`. An explicit cast (inside `unsafe` for raw pointers) still works when that's really meant.

```c
volatile unsigned int status;
void poll(volatile unsigned int* p);

void f() {
    poll(&status);                 // OK: &status is &volatile unsigned int
    unsigned int* q = &status;     // error: cannot convert '&static volatile unsigned int' to 'unsigned int*'
}
```

`volatile` keeps the compiler from removing, merging or reordering the accesses relative to each other. It is not a memory barrier between threads; use [`std::atomic`](/stdlib/atomic) for that. `std::mem_zero_secure` relies on these volatile stores, so wiping a key survives dead-store elimination at `-O2`.

### Volatile Load and Store {#volatile-load-and-store}

Built-in functions provide explicit volatile access:

```c
int val = volatile_load(ptr);       // guaranteed to read from memory
volatile_store(ptr, value);         // guaranteed to write to memory
```

These compile to LLVM `load volatile` and `store volatile` instructions. They do the same thing as an access through a `volatile T*`, and are useful when the pointer itself is not declared volatile.

### Example: Polling a Hardware Register {#example-polling-a-hardware-register}

```c
void uart_send(char c) {
    volatile int *status = (volatile int*)0x40001004;
    volatile int *data = (volatile int*)0x40001000;

    // Wait until transmit buffer is empty
    while ((volatile_load(status) & 0x20) == 0) {}

    volatile_store(data, (int)c);
}
```

## Atomic Operations {#atomic-operations}

Atomic operations provide lock-free synchronization and are essential for multi-core bare-metal systems and interrupt handlers.

### Atomic Variables {#atomic-variables}

```c
atomic int counter = 0;
```

### Atomic Built-ins {#atomic-built-ins}

| Operation | Signature | Description |
|-----------|-----------|-------------|
| `atomic_load(ptr)` | `T atomic_load(T *ptr)` | Atomically load value |
| `atomic_store(ptr, val)` | `void atomic_store(T *ptr, T val)` | Atomically store value |
| `atomic_fetch_add(ptr, val)` | `T atomic_fetch_add(T *ptr, T val)` | Add and return previous value |
| `atomic_fetch_sub(ptr, val)` | `T atomic_fetch_sub(T *ptr, T val)` | Subtract and return previous value |
| `atomic_fetch_and(ptr, val)` | `T atomic_fetch_and(T *ptr, T val)` | Bitwise AND and return previous |
| `atomic_fetch_or(ptr, val)` | `T atomic_fetch_or(T *ptr, T val)` | Bitwise OR and return previous |
| `atomic_fetch_xor(ptr, val)` | `T atomic_fetch_xor(T *ptr, T val)` | Bitwise XOR and return previous |
| `atomic_exchange(ptr, val)` | `T atomic_exchange(T *ptr, T val)` | Swap and return previous value |
| `atomic_cas(ptr, expected, desired)` | `bool atomic_cas(T *ptr, T exp, T des)` | Compare-and-swap |
| `atomic_fence()` | `void atomic_fence()` | Full memory barrier |

All atomic operations use sequentially consistent ordering by default.

### Example: Lock-Free Counter {#example-lock-free-counter}

```c
atomic int shared_counter = 0;

interrupt void timer_isr() {
    atomic_fetch_add(&shared_counter, 1);
}

int read_counter() {
    return atomic_load(&shared_counter);
}
```

### Example: Spinlock {#example-spinlock}

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

Cortex-M gets first-class treatment beyond the generic bare-metal features
above: cross-compiles cleanly with `--target thumbv7em-none-eabi
--freestanding` (M4/M7), `--target thumbv6m-none-eabi --freestanding`
(M0/M0+, no FPU/DSP — the most constrained variant), or
`--target thumbv8.1m.main-none-eabi` with `+mve` (M55/M85), plus a HAL and
DSP-extension intrinsics described below. See
[Multi-Target Codegen](/advanced/compiler#multi-target-codegen) for the
full cross-compilation matrix.

### HAL: NVIC, SysTick, SCB {#hal-nvic-systick-scb}

`std/hal/cortex_m.h` wraps the three most commonly needed Cortex-M
peripherals as methods on global instances at their standard memory-mapped
addresses — see [`std::hal`](/stdlib/hal) for the full API:

```c
#include <std/hal/cortex_m.h>

void setup_timer_interrupt() {
    std::nvic_init();
    std::systick_init();
    std::systick.start(1000000, 1);   // 1,000,000 core-clock ticks per interrupt
    std::nvic.enable(15);              // SysTick IRQ
    std::nvic.set_priority(15, (unsigned char)0);
}
```

### DSP Extension (Cortex-M4/M7) {#dsp-extension-cortex-m4-m7}

The DSP extension's packed-SIMD/saturating instructions — `SADD16`,
`SMLAD`, `USAD8`, `SSAT`, and friends — pack 2×16-bit or 4×8-bit lanes into
a single 32-bit *scalar* register, a different model from a real vector
register file. LLVM does not auto-vectorize `vec<T,N>` IR into these (see
[Native SIMD](/reference/simd)), so they're exposed directly as `std::dsp_*`
functions in `std/simd/cortex_m.h`, each verified to emit the single named
instruction (not a libcall) via real `llc -mcpu=cortex-m4` output:

```c
#include <std/simd/cortex_m.h>

int clamp_i8(int x) {
    return __arm_dsp_ssat(x, 8);   // saturate to signed 8-bit range
}

int checksum4(int a, int b, int acc) {
    return std::dsp_smlad(a, b, acc);   // dual 16x16 multiply-accumulate
}
```

`dsp_ssat`/`dsp_usat`/`dsp_ssat16`/`dsp_usat16` aren't wrapped as
functions — the underlying instruction's bit-width operand is a literal
immediate encoded directly into the instruction, enforced by the compiler
at the `__arm_dsp_ssat(val, bits)` call site (`bits` must literally be an
integer-literal expression there, which a forwarding function parameter
can never satisfy) — call the builtin directly with a literal width, as
above.

Requires an ARM target — using any `__arm_dsp_*`/`std::dsp_*` function
while compiling for a non-ARM target (or the default host target on a
non-ARM machine) is a compile error at the call site, not silently wrong
codegen.

### MVE (Cortex-M55/M85) {#mve-cortex-m55-m85}

Unlike the DSP extension, MVE ("Helium") *is* a real vector register file,
and `vec<T,N>` reaches it directly — `vec<int,4>` arithmetic compiled with
`--target thumbv8.1m.main-none-eabi` and `+mve` lowers to real MVE
instructions (`vadd.i32 q2, q0, q1`, `vldrw.u32`/`vstrw.32` for loads/
stores), the same mechanism used for NEON, SSE, RVV, and SIMD128 on the
other targets — see [Native SIMD](/reference/simd).

## Bare-Metal Example: Minimal Kernel {#bare-metal-example-minimal-kernel}

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
`VGA_BUFFER` could just as well be hoisted to a file-scope global now (see
"Volatile Variables" above) — kept local here only because
`vga_putchar`'s the only function that needs it. `msg[i]` needs `unsafe`
either way: `msg` is a raw `const char*`, and subscripting a raw pointer
always requires it, string literal or not.
:::
