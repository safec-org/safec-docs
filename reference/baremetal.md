# Bare-Metal Programming

SafeC supports bare-metal and embedded development through freestanding mode, function attributes for interrupt handling, inline assembly, and volatile/atomic operations for hardware register access.

## Freestanding Mode

The `--freestanding` flag disables the standard library and produces code that does not depend on an OS:

```bash
./build/safec firmware.sc --freestanding --emit-llvm -o firmware.ll
```

In freestanding mode:
- No implicit dependency on libc
- No startup code (`_start` / `main` convention is programmer-defined)
- No standard C header imports
- The programmer provides all runtime support

## Function Attributes

### Naked Functions

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

### Interrupt Functions

Interrupt functions use the ISR calling convention. They must be `void(void)` and the compiler generates appropriate entry/exit sequences (e.g., `iret` on x86):

```c
interrupt void timer_handler() {
    volatile int *timer_reg = (volatile int*)0x40000C00;
    *timer_reg = 1;            // acknowledge interrupt
}
```

### Noreturn Functions

Functions that never return can be annotated with `noreturn`, allowing the compiler to optimize call sites:

```c
noreturn void panic(const char *msg) {
    // ... write to UART ...
    while (1) {}
}
```

### Section Attribute

Place functions or variables in specific linker sections:

```c
section(".isr_vector")
void* vector_table[256] = {
    (void*)&_start,
    (void*)&nmi_handler,
    (void*)&hardfault_handler,
    // ...
};

section(".text.fast")
void hot_path() {
    // placed in fast memory section
}
```

## Inline Assembly

SafeC supports GCC-style extended inline assembly:

```c
asm [volatile] ( "template" [: outputs [: inputs [: clobbers]]] );
```

### Basic Assembly

```c
asm volatile ("cli");          // disable interrupts
asm volatile ("sti");          // enable interrupts
asm volatile ("nop");          // no operation
asm volatile ("hlt");          // halt processor
```

### Extended Assembly with Operands

```c
int result;
asm volatile (
    "mov %1, %0\n"
    "add $1, %0"
    : "=r"(result)             // output: result register
    : "r"(input)               // input: input register
    : "cc"                     // clobbers: condition codes
);
```

### Reading Special Registers

```c
long long read_tsc() {
    int lo;
    int hi;
    asm volatile (
        "rdtsc"
        : "=a"(lo), "=d"(hi)
    );
    return ((long long)hi << 32) | lo;
}
```

### Memory-Mapped I/O with Assembly

```c
void outb(uint16 port, uint8 value) {
    asm volatile (
        "outb %0, %1"
        :
        : "a"(value), "Nd"(port)
    );
}

uint8 inb(uint16 port) {
    uint8 result;
    asm volatile (
        "inb %1, %0"
        : "=a"(result)
        : "Nd"(port)
    );
    return result;
}
```

## Volatile Access

The `volatile` qualifier ensures that reads and writes are not optimized away or reordered by the compiler. This is essential for memory-mapped hardware registers.

### Volatile Variables

```c
volatile int *UART_DATA = (volatile int*)0x40001000;
volatile int *UART_STATUS = (volatile int*)0x40001004;
```

### Volatile Load and Store

Built-in functions provide explicit volatile access:

```c
int val = volatile_load(ptr);       // guaranteed to read from memory
volatile_store(ptr, value);         // guaranteed to write to memory
```

These compile to LLVM `load volatile` and `store volatile` instructions.

### Example: Polling a Hardware Register

```c
void uart_send(char c) {
    volatile int *status = (volatile int*)0x40001004;
    volatile int *data = (volatile int*)0x40001000;

    // Wait until transmit buffer is empty
    while ((volatile_load(status) & 0x20) == 0) {}

    volatile_store(data, (int)c);
}
```

## Atomic Operations

Atomic operations provide lock-free synchronization and are essential for multi-core bare-metal systems and interrupt handlers.

### Atomic Variables

```c
atomic int counter = 0;
```

### Atomic Built-ins

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

### Example: Lock-Free Counter

```c
atomic int shared_counter = 0;

interrupt void timer_isr() {
    atomic_fetch_add(&shared_counter, 1);
}

int read_counter() {
    return atomic_load(&shared_counter);
}
```

### Example: Spinlock

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

## Bare-Metal Example: Minimal Kernel

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

volatile uint8 *VGA_BUFFER = (volatile uint8*)0xB8000;

void vga_putchar(int x, int y, char c, uint8 color) {
    int offset = (y * 80 + x) * 2;
    volatile_store(VGA_BUFFER + offset, (uint8)c);
    volatile_store(VGA_BUFFER + offset + 1, color);
}

void kernel_main() {
    const char *msg = "Hello from SafeC!";
    for (int i = 0; msg[i] != 0; i = i + 1) {
        vga_putchar(i, 0, msg[i], 0x0F);
    }

    while (1) {
        asm volatile ("hlt");
    }
}
```
