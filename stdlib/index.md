# Standard Library Overview

The SafeC standard library (`std/`) covers core utilities, collections, allocators, networking, filesystems, DSP, debugging, and security. Each module is a `.h` header, plus a `.sc` implementation for the non-generic parts; generic containers are header-only. `#include` only ever names `.h` files — the `.sc` files are compiled once into `libsafec_std.a` and linked. Include `prelude.h` to pull in most modules at once; GUI, encoding, HTTP/RPC/wasm, the real-time scheduler, ML and SIMD are included by their own headers.

```c
#include <std/prelude.h>

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
| slice | `collections/slice.h` | `Slice<T>` bounds-checked view + `arr_*` helpers |
| vec | `collections/vec.h` | `Vec<T>` dynamic array; introsort, compile-time `vec_sort_by` |
| string | `collections/string.h` | Mutable, heap-allocated growable string (30+ methods) |
| stack | `collections/stack.h` | `Stack<T>` LIFO |
| queue | `collections/queue.h` | `Queue<T>` FIFO ring |
| list | `collections/list.h` | `List<T>` doubly linked list over a node pool |
| map | `collections/map.h` | `HashMap<K, V>` / `StrMap<V>` (open addressing) |
| bst | `collections/bst.h` | `BST<K, V, C>` with a compile-time ordering `C` |
| btree | `collections/btree.h` | `BTree<V>` ordered map, fixed 256-node pool |
| ringbuffer | `collections/ringbuffer.h` | SPSC lock-free power-of-two byte ring |
| node_pool | `collections/node_pool.h` | `NodePool<N>` per-container node arena |
| static_vec | `collections/static_vec.h` | `StaticVec<T>` / `StaticMap<V>` over caller storage (zero heap) |

### [Allocators](/stdlib/allocators)

| Module | Header | Description |
|--------|--------|-------------|
| bump | `alloc/bump.h` | Linear bump-pointer arena; O(1) alloc, reset-only free |
| slab | `alloc/slab.h` | Freelist slab for fixed-size objects; O(1) alloc/dealloc |
| pool | `alloc/pool.h` | Variable-size first-fit pool with coalescing |
| tlsf | `alloc/tlsf.h` | Two-Level Segregated Fit; O(1) worst-case general heap |

### [Synchronization](/stdlib/sync)

| Module | Header | Description |
|--------|--------|-------------|
| spinlock | `sync/spinlock.h` | Busy-wait mutual exclusion (`__sync_lock_test_and_set`) |
| lockfree | `sync/lockfree.h` | `LFQueue<T>` lock-free SPSC ring buffer |
| channel | `sync/channel.h` | `Chan<T>`: typed bounded blocking MPMC channel over the `chan_*` built-ins |
| mpsc | `sync/mpsc.h` | `MpscQueue<T>` spinlock-guarded MPSC ring, non-blocking |
| task | `sync/task.h` | Cooperative scheduler; typed `task_spawn` |
| thread_bare | `sync/thread_bare.h` | Priority-ordered freestanding threads (no OS) |
| bare_spawn | `sync/bare_spawn.sc` | Reference "Hook" backend for the `spawn`/`join` language keywords on freestanding targets |

### [IPC](/stdlib/ipc)

| Module | Header | Description |
|--------|--------|-------------|
| pipe | `ipc/pipe.h` | Anonymous pipes (hosted) — one-way byte stream, typically parent/child after `fork()` |
| uds | `ipc/uds.h` | Unix domain sockets (hosted) — named IPC between unrelated processes, non-blocking/`Reactor`-pairable |

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

### [HTTP & Web](/stdlib/http)

| Module | Header | Description |
|--------|--------|-------------|
| http | `http/http.h` | HTTP/1.1 client + server (`http_serve`/`http_serve_threaded`), request/response types |
| cors | `http/cors.h` | CORS preflight detection + response headers |
| jwt | `http/jwt.h` | HS256 JWT sign/verify (HMAC-SHA256) |
| oauth2 | `http/oauth2.h` | OAuth2 client: authorization-code + refresh-token exchange (RFC 6749) |
| websocket | `http/websocket.h` | WebSocket handshake + frame read/write |
| rpc | `rpc/rpc.h` | gRPC-inspired RPC-over-HTTP (length-prefixed framing, path-based dispatch) |
| server_fn | `rpc/server_fn.h` | "Write once, call from anywhere" JSON-marshaled server functions (Dioxus/Leptos-style) |
| reactive | `reactive/signal.h` | `Signal<T>` fine-grained reactive state (WASM client hydration) |
| wasm | `wasm/dom.h`, `wasm/hydrate.h` | wasm32 DOM interop + client hydration; `wasm/wasm_rt.h` is the freestanding runtime (bump `malloc`/`free`, `mem*`, trapping `abort`) for wasm32/wasm64 — only a wasm target's std archive includes it (see [WebAssembly](/reference/baremetal#wasm)) |
| scx | n/a (transpiler) | JSX/TSX-style HTML templating — `.scx` files transpile to plain SafeC before `safec` ever sees them |

### Machine Learning — see [ML](/stdlib/ml)

### [GUI](/stdlib/gui)

| Module | Header | Description |
|--------|--------|-------------|
| gui | `gui/gui.h` | Portable `GuiWindow`/`GuiEvent` API over an RGBA8888 software framebuffer; Cocoa, Win32, X11 and bare-metal framebuffer backends |
| draw | `gui/gui_draw.h` | Rectangles, borders, lines, built-in 8x8 bitmap text |
| font | `gui/gui_font.h` | `GuiFont` bitmap fonts of any glyph size; `SCXF` save/load |
| png | `gui/gui_png.h` | From-scratch PNG decoder (own DEFLATE inflater) |
| svg | `gui/gui_svg.h` | SVG subset renderer (shapes, paths with cubic curves) |
| widget | `gui/gui_widget.h` | Retained-mode widget tree: stacks, button, label, checkbox, text input, slider, custom widgets |

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
| fixed | `dsp/fixed.h` | Q8.24 fixed-point arithmetic (`newtype Fixed = int`) |
| dsp | `dsp/dsp.h` | `dsp_dot`, `dsp_scale`, `dsp_add`, `dsp_clip`, `dsp_peak`, `dsp_rms`, SIMD-FMA `dsp_dot_f64` |
| complex_dsp | `dsp/complex_dsp.h` | Value-type `Complex`/`FComplex` with operator overloading (`+`,`-`,`*`,`/`,`abs`,`arg`,`conj`) |
| dft | `dsp/dft.h` | Direct O(n²) DFT/IDFT, arbitrary length (float + Q8.24) |
| fft | `dsp/fft.h` | In-place radix-2 Cooley-Tukey FFT/IFFT, O(n log n), power-of-two length (float + Q8.24) |
| convolution | `dsp/convolution.h` | Linear convolution — direct O(len_x·len_h) (SIMD-accelerated) and FFT-based O(n log n) |
| window | `dsp/window.h` | Rectangular/Hann/Hamming/Blackman analysis windows |
| filter | `dsp/filter.h` | General streaming FIR/IIR (feedforward/feedback) difference-equation filters (float + Q8.24) |
| biquad | `dsp/biquad.h` | 2nd-order IIR sections + RBJ "Audio EQ Cookbook" designers + general bilinear transform |
| dct | `dsp/dct.h` | DCT-II/DCT-III (JPEG/MPEG-style discrete cosine transform), float + Q8.24 |
| stft | `dsp/stft.h` | Short-Time Fourier Transform + inverse (windowed overlap-add) + multi-resolution STFT loss |
| resample | `dsp/resample.h` | Up/downsampling — nearest, linear, windowed-sinc (band-limited); float + Q8.24 nearest/linear |
| ztransform | `dsp/ztransform.h` | Arbitrary-order bilinear (Laplace-to-Z) transform + Z-domain frequency response evaluator |
| comb | `dsp/comb.h` | Feedforward/feedback comb filters + Karplus-Strong string synthesis (float + Q8.24) |
| minphase | `dsp/minphase.h` | Minimum-phase reconstruction via real cepstrum (homomorphic processing) |
| cqt | `dsp/cqt.h` | Constant-Q Transform (log-spaced bins, direct-correlation form) |
| cwt | `dsp/cwt.h` | Continuous Wavelet Transform (Morlet wavelet, time-scale scalogram) |
| imaging | `dsp/imaging.h` | 2D convolution, 2D FFT (row-column decomposition), Gaussian/Sobel kernels |
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

### [Encoding](/stdlib/encoding)

| Module | Header | Description |
|--------|--------|-------------|
| ascii | `encoding/ascii.h` | ASCII validation, classification, case conversion |
| utf8 | `encoding/utf8.h` | UTF-8 encode/decode/validate, codepoint count |
| url | `encoding/url.h` | RFC 3986 percent-encoding (component style) |
| base64 | `encoding/base64.h` | RFC 4648 Base64 with padding |

### [Utilities](/stdlib/utilities)

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

### [C Compatibility Headers](/stdlib/utilities#c-type-and-limit-headers)

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

Containers and other value-holding APIs are generic structs and functions, monomorphized per element type. They hand out `?&T` references (narrow them with `match`, `.default(x)`, or `std::expect_ref`), return `?T` when there may be no value, and take typed `fn` callbacks — the standard library has no `void*` element or callback parameters. `T` is written once, on the variable, and the constructor infers it from that target type:

```c
#include <std/collections/vec.h>

int main() {
    struct Vec<int> v = std::vec_new();
    v.push(42);
    v.push(100);
    match (v.get(0UL)) {
        case some(x): *x = *x + 1;      // 43, written in place
        case null:    { }
    }
    int last = v.pop().default(-1);     // 100
    v.free();
    return last == 100 ? 0 : 1;
}
```

Where a container must hold callbacks with *different* context types at once (a timer wheel, a widget tree, a VFS mounting several drivers), the slot stores the pair type-erased, but the only way to fill it is a generic function that takes a matching `fn(?&T)` and `?&T` — `timer_add`, `widget_on_click`, `vfs_mount`, `task_spawn` — so every call is still checked at compile time. The remaining `void*` declarations in `std/` are C ABI boundaries: libc/OS externs, `mem.h`'s raw byte allocator, MMIO base addresses, and GPU/WASM foreign interfaces.

## Including the Standard Library

**Individual modules:**
```c
#include <std/mem.h>
#include <std/io.h>
#include <std/collections/vec.h>
```

**Most modules at once:**
```c
#include <std/prelude.h>
```

When building with the `safeguard` package manager, the standard library is automatically compiled to `build/deps/libsafec_std.a` and linked.

When building manually, put the SafeC checkout on the include path (headers are named `<std/...>`) and link the std archive that `safeguard` builds (or that you build from every `std/**/*.sc`):
```bash
safec myfile.sc -I /path/to/SafeC -o myfile.ll
clang myfile.ll /path/to/libsafec_std.a -lm -lpthread -o myfile
```
