# Standard Library Overview

The SafeC standard library (`std/`) covers core utilities, collections, allocators, networking, filesystems, DSP, debugging, and security. Each module is a `.h` (declarations) + `.sc` (implementation) pair. Include `prelude.h` to pull in every module at once.

```c
#include "prelude.h"

int main() {
    println("Hello from SafeC stdlib!");
    return 0;
}
```

## Module Categories

### Core

| Module | Header | Description |
|--------|--------|-------------|
| [mem](/stdlib/mem) | `mem.h` | Allocation, deallocation, safe memcpy/memmove/memset/memcmp; cache-line helpers, alignment utilities |
| [io](/stdlib/io) | `io.h` | Formatted output (stdout/stderr), stdin input, buffer formatting |
| [str](/stdlib/str) | `str.h` | String length, comparison, copy, search, tokenisation, duplication |
| [math](/stdlib/math) | `math.h` | Constants (PI, E, …), float/double math, classification |
| [thread](/stdlib/thread) | `thread.h` | Threads, mutexes, condition variables, read-write locks |
| [atomic](/stdlib/atomic) | `atomic.h` | Lock-free atomic operations (C11 `<stdatomic.h>` wrappers) |

### [Serialization](/stdlib/serial)

| Module | Header | Description |
|--------|--------|-------------|
| value | `serial/value.h` | Format-agnostic `Value` tree (Null/Bool/Int/Float/String/Array/Object) |
| json | `serial/json.h` | JSON writer + parser, exact round-trip |
| xml | `serial/xml.h` | XML writer + parser (own-grammar round-trip) |
| html | `serial/html.h` | HTML fragment writer + parser (`<dl>`/`<ul>` shape) |

### [Collections](/stdlib/collections)

| Module | Header | Description |
|--------|--------|-------------|
| slice | `collections/slice.h` | Bounds-checked fat pointer + generic array functions |
| vec | `collections/vec.h` | Dynamic array with push/pop/sort/filter/map |
| string | `collections/string.h` | Mutable, heap-allocated growable string (30+ methods) |
| stack | `collections/stack.h` | LIFO stack backed by growable array |
| queue | `collections/queue.h` | FIFO circular buffer queue |
| list | `collections/list.h` | Doubly linked list |
| map | `collections/map.h` | Hash map (open addressing, linear probing) |
| bst | `collections/bst.h` | Unbalanced binary search tree |
| btree | `collections/btree.h` | B-tree ordered map (order-4, 256-node pool) |
| ringbuffer | `collections/ringbuffer.h` | SPSC lock-free power-of-two ring buffer |
| static_collections | `collections/static_vec.h` | Header-only zero-heap vec + map macros |

### [Allocators](/stdlib/allocators)

| Module | Header | Description |
|--------|--------|-------------|
| bump | `alloc/bump.h` | Linear bump-pointer arena; O(1) alloc, reset-only free |
| slab | `alloc/slab.h` | Freelist slab for fixed-size objects; O(1) alloc/dealloc |
| pool | `alloc/pool.h` | Fixed-block pool for mixed content; O(1) alloc/free |
| tlsf | `alloc/tlsf.h` | Two-Level Segregated Fit; O(1) worst-case general heap |

### [Synchronization](/stdlib/sync)

| Module | Header | Description |
|--------|--------|-------------|
| spinlock | `sync/spinlock.h` | Busy-wait mutual exclusion (`__sync_lock_test_and_set`) |
| lockfree | `sync/lockfree.h` | Wait-free SPSC ring buffer with compiler barriers |
| task | `sync/task.h` | Cooperative round-robin task scheduler |
| thread_bare | `sync/thread_bare.h` | Priority-ordered freestanding threads (no OS) |
| bare_spawn | `sync/bare_spawn.sc` | Reference "Hook" backend for the `spawn`/`join` language keywords on freestanding targets |

### [Real-Time Scheduler](/stdlib/sched)

| Module | Header | Description |
|--------|--------|-------------|
| reactor | `sched/reactor.h` | `Reactor` — kqueue-backed I/O event loop driving `TaskScheduler` |
| io_nb | `sched/io_nb.h` | Non-blocking file/socket helpers meant to pair with the reactor |

### [Networking](/stdlib/net)

| Module | Header | Description |
|--------|--------|-------------|
| net-core | `net/net_core.h` | `PacketBuf`, `NetIf`, byte-order utilities, IP/MAC helpers |
| ethernet | `net/ethernet.h` | `EthernetHdr`, `eth_parse`, `eth_build` |
| arp | `net/arp.h` | `ArpTable` (16-entry FIFO), `arp_build_packet`, `arp_parse_packet` |
| ipv4 | `net/ipv4.h` | `Ipv4Hdr`, Internet checksum, `ipv4_parse`, `ipv4_build` |
| ipv6 | `net/ipv6.h` | `Ipv6Addr`/`Ipv6Hdr`, link-local/loopback predicates, `ipv6_frame` |
| udp | `net/udp.h` | `UdpHdr`, `udp_parse`, `udp_build`, `udp_frame` |
| tcp | `net/tcp.h` | `TcpConn` 10-state machine, pseudo-header checksum |
| dns | `net/dns.h` | A-record query builder + reply parser (label compression) |
| dhcp | `net/dhcp.h` | `DhcpClient` DORA handshake |

### [Filesystems](/stdlib/fs)

| Module | Header | Description |
|--------|--------|-------------|
| block | `fs/block.h` | `BlockDevice` driver interface (function pointer–based) |
| partition | `fs/partition.h` | MBR partition table parser (4 primary entries) |
| vfs | `fs/vfs.h` | VFS with longest-prefix mount routing; `VfsNode` forwarding |
| fat | `fs/fat.h` | FAT32 read-only driver; 8.3 path walk, cluster chain |
| ext | `fs/ext.h` | ext2 read-only driver; inode walk, direct-block reads |
| tmpfs | `fs/tmpfs.h` | In-memory FS; 32 inodes, 64 KiB data pool; full CRUD |

### [DSP & Real-Time](/stdlib/dsp)

| Module | Header | Description |
|--------|--------|-------------|
| fixed | `dsp/fixed.h` | Q16.16 fixed-point arithmetic (`newtype Fixed = int`) |
| dsp | `dsp/dsp.h` | `dsp_dot`, `dsp_scale`, `dsp_moving_avg`, `dsp_iir_lp`, `dsp_rms` |
| audio_buffer | `dsp/audio_buffer.h` | Multi-channel SPSC audio ring buffer (interleaved `Fixed` frames) |
| timer_wheel | `dsp/timer_wheel.h` | 256-slot O(1) timer wheel; one-shot + periodic |

### [Security & Cryptography](/stdlib/crypto)

| Module | Header | Description |
|--------|--------|-------------|
| aes | `crypto/aes.h` | AES-128/256 ECB + CBC; full S-box + key expansion |
| sha256 | `crypto/sha256.h` | SHA-256/224; streaming and one-shot API |
| rng | `crypto/rng.h` | ChaCha20 CSPRNG; `rdrand`/`/dev/urandom` seeding |
| secure_alloc | `crypto/secure_alloc.h` | Slab allocator with zeroing-on-free |
| x509 | `crypto/x509.h` | X.509 DER/ASN.1 parser; SAN, wildcard hostname, validity |
| tls | `crypto/tls.h` | TLS 1.3 record layer; AES-CBC + PKCS#7 + nonce XOR seq |

### [Debugging & Profiling](/stdlib/debug)

| Module | Header | Description |
|--------|--------|-------------|
| perf | `debug/perf.h` | Arch-dispatched cycle counter (RDTSC/cntvct_el0/CSR); ns calibration |
| coverage | `debug/coverage.h` | 1024-site coverage tracker; `COV_SITE()` macro; `report()` |
| jtag | `debug/jtag.h` | `debug_break` per arch; ARM/AArch64 semihosting; ITM ports |

### [SIMD](/stdlib/simd)

| Module | Header | Description |
|--------|--------|-------------|
| simd | `simd/simd.h` | Portable core: `f32x4`/`i32x8`/... type aliases over native `vec<T,N>`; load/store, splat, fma, min/max, horizontal reductions |
| x86_64 / aarch64 / riscv / wasm / spirv / cortex_m / cuda / rocm | `simd/*.h` | Thin per-ISA convenience layers (native-preferred-width naming, real-hardware verification notes) — same portable source underneath, no separate implementation |

### [Hardware Abstraction Layer](/stdlib/hal)

| Module | Header | Description |
|--------|--------|-------------|
| gpio | `hal/gpio.h` | `GpioPin`: direction, read/write/toggle, pull-up/down |
| i2c | `hal/i2c.h` | `I2cBus`: polling master — write/read/write_read/probe |
| spi | `hal/spi.h` | `SpiDevice`: polling master — transfer/write/read, chip-select |
| uart | `hal/uart.h` | `Uart`: polling serial — byte/string I/O, ready flags |
| timer | `hal/timer.h` | `Timer`: period/start/stop/read/flag |
| watchdog | `hal/watchdog.h` | `Watchdog`: enable/feed/caused_reset |
| cortex_m | `hal/cortex_m.h` | NVIC, SysTick, SCB (ARM Cortex-M) |
| aarch64 | `hal/aarch64.h` | System registers, Generic Timer, GICv2 (ARMv8-A) |
| riscv | `hal/riscv.h` | CSR access, CLINT, PLIC |

### [Interrupts & MMIO](/stdlib/interrupt)

| Module | Header | Description |
|--------|--------|-------------|
| mmio | `interrupt/mmio.h` | `MmioReg` + free-function register read/write/field access |
| bitfield | `interrupt/bitfield.h` | Pure bit-manipulation functions (`bf_extract32`, `bf_insert32`, ...) |
| isr | `interrupt/isr.h` | Software ISR dispatch table (256 slots) |
| vector_table | `interrupt/vector_table.h` | Hardware vector table — Cortex-M `VTOR`/RISC-V `mtvec`/AArch64 `VBAR_EL1` |
| clock | `interrupt/clock.h` | PLL/clock-source configuration |

### [Kernel Primitives](/stdlib/kernel)

| Module | Header | Description |
|--------|--------|-------------|
| frame | `kernel/frame.h` | Bitmap physical frame allocator (4 KiB frames) |
| paging | `kernel/paging.h` | `PageEntry`/`PageTable` — raw page table manipulation |
| mmu | `kernel/mmu.h` | `MmuContext` — 2-level virtual memory, map/unmap/walk/TLB/activate |
| process | `kernel/process.h` | `PCB` — process control block |
| scheduler | `kernel/scheduler.h` | Priority round-robin scheduler over PCBs |
| ipc | `kernel/ipc.h` | `Mailbox` — fixed-capacity message queue |
| syscall | `kernel/syscall.h` | Syscall registration/dispatch table |

### [Testing & Benchmarking](/stdlib/testing)

| Module | Header | Description |
|--------|--------|-------------|
| test | `test/test.h` | `TestSuite` + `ASSERT_*` macros |
| bench | `test/bench.h` | `BenchSuite` — wall-clock timed iteration benchmarks |
| fuzz | `test/fuzz.h` | `FuzzTarget` — lightweight in-process mutation fuzzer |

### Utilities

| Header | Description |
|--------|-------------|
| `bit.h` | Bit manipulation (C23 `<stdbit.h>` + popcount/clz/ctz/bswap builtins) |
| `convert.h` | String ↔ number parsing (C11/C17), `*ok` success flag on failure |
| `dma.h` | Cache-coherent DMA buffer descriptors (64-byte aligned) |
| `fmt.h` | Safe `snprintf`-based formatting into caller-supplied buffers |
| `heap.h` | Unified heap: TLSF-backed static buffer (freestanding) or malloc/free/realloc (hosted) |
| `log.h` | Configurable logging, zero overhead when `LOG_LEVEL` is 0 |
| `panic.h` | Opt-in panic handler — infinite loop (freestanding) or `abort()` (hosted) by default |
| `result.h` | Explicit `Result` error-propagation type (heap-allocated, mirrors `?T` optional) |
| `sys.h` | Process-control constants (`EXIT_SUCCESS`/`EXIT_FAILURE`), PRNG constants |
| `complex.h` | Complex numbers (C99 `<complex.h>`) as `[real, imag]` float/double pairs |

### C Compatibility Headers

| Header | Description |
|--------|-------------|
| `assert.h` | Runtime assertions (`runtime_assert`, `assert_true`); NDEBUG support |
| `ctype.h` | Character classification (`char_is_alpha`, `char_is_digit`, ...) and conversion |
| `errno.h` | Thread-local `errno` value and error descriptions (C11) |
| `fenv.h` | Floating-point exception flags and rounding mode (C99) |
| `locale.h` | Locale category constants (C11) |
| `signal.h` | Signal handler installation and dispatch (C11) |
| `time.h` | Calendar/wall-clock time (C11), complements `sys.h`'s high-resolution clocks |
| `stdckdint.h` | Checked integer arithmetic (C23-style `ckd_add`/`ckd_sub`/`ckd_mul`) |
| `stdint.h` | Fixed-width integer types |
| `stddef.h` | `size_t`, `NULL`, `offsetof` |
| `stdbool.h` | Boolean constants |
| `limits.h` | Integer limits |
| `float.h` | Floating-point limits |
| `inttypes.h` | Format macros for fixed-width types |

## Generic Pattern

The SafeC compiler does not support generic structs. Collections use `void*` structs for the underlying data structure, with `generic<T>` wrapper functions for type-safe access. `T` is inferred from `T*` arguments at the call site via monomorphization.

```c
#include "collections/vec.h"

int main() {
    struct Vec v = vec_new(sizeof(int));

    // Type-erased API
    int x = 42;
    vec_push(&v, &x);

    // Generic typed wrapper — T inferred from int* argument
    vec_push_t(&v, 100);
    int* p = vec_at(&v, 0);  // returns int*

    vec_free(&v);
    return 0;
}
```

## Including the Standard Library

**Individual modules:**
```c
#include "mem.h"
#include "io.h"
#include "collections/vec.h"
```

**All modules at once:**
```c
#include "prelude.h"
```

When building with the `safeguard` package manager, the standard library is automatically compiled to `build/deps/libsafec_std.a` and linked.

When building manually, pass the std directory with `-I`:
```bash
./build/safec myfile.sc -I /path/to/SafeC/std --emit-llvm -o myfile.ll
clang myfile.ll -o myfile
```
