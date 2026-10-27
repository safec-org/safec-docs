# Standard Library Overview

The SafeC standard library (`std/`) covers core utilities, collections, allocators, networking, filesystems, DSP, debugging, and security. Each module is a `.h` header, plus a `.sc` implementation for the non-generic parts; generic containers are header-only. `#include` only ever names `.h` files — the `.sc` files are compiled once into `libsafec_std.a` and linked. Include `prelude.h` to pull in most modules at once; GUI, audio/MIDI, encoding, HTTP/RPC/wasm, async, the real-time scheduler, ML and SIMD are included by their own headers.

```c
#include <std/prelude.h>

int main() {
    println("Hello from SafeC stdlib!");
    return 0;
}
```

## Module Categories {#module-categories}

### Core {#core}

| Module | Header | Description |
|--------|--------|-------------|
| [mem](/stdlib/mem) | `mem.h` | Allocation, deallocation, safe memcpy/memmove/memset/memcmp; cache-line helpers, alignment utilities |
| [io](/stdlib/io) | `io.h` | Formatted output (stdout/stderr), stdin input, buffer formatting |
| [io_traits](/stdlib/io) | `io_traits.h` | `Reader` / `Writer` / `Seekable` / `Flusher` traits composed into `Stream` / `SeekableStream`; `read_exact`, `write_all`, `copy_stream` over any stream |
| [io_buf](/stdlib/io) | `io_buf.h` | `BufReader<R>` / `BufWriter<W>` buffering over any `Reader` / `Writer` (themselves a `Reader` / `Writer`) |
| [io_file](/stdlib/io#io_file-file-io) | `io_file.h` | `File`: open/read/write/seek/close on a `?&File` handle, `File::read_to_string` / `write_bytes`; a `Reader` / `Writer` |
| [dir](/stdlib/dir) | `dir.h` | Directory listing, `file_stat` (kind, size, mtime), `dir_create_all`, `dir_walk`, `dir_copy_tree`, `dir_remove_all`, `file_copy` (POSIX + Win32) |
| [path](/stdlib/dir#path) | `path.h` | Path strings: join, dirname, basename, ext, stem, normalize, relative |
| [str](/stdlib/str) | `str.h` | String length, comparison, copy, search, tokenisation, duplication |
| [math](/stdlib/math) | `math.h` | Constants (PI, E, …), float/double math, classification |
| [thread](/stdlib/thread) | `thread.h` | Threads, mutexes, condition variables, read-write locks |
| [atomic](/stdlib/atomic) | `atomic.h` | Lock-free atomic operations (C11 `<stdatomic.h>` wrappers) |

### [Serialization](/stdlib/serial) {#serialization}

| Module | Header | Description |
|--------|--------|-------------|
| value | `serial/value.h` | Format-agnostic `Value` tree (Null/Bool/Int/Float/String/Array/Object); the `Format` trait behind `v.encode(f)` / `Value::decode(f, ...)` |
| json | `serial/json.h` | JSON writer + parser, exact round-trip |
| xml | `serial/xml.h` | XML writer + parser (own-grammar round-trip) |
| html | `serial/html.h` | HTML fragment writer + parser (`<dl>`/`<ul>` shape) |

### [Collections](/stdlib/collections) {#collections}

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
| traits | `collections/traits.h` | Container capability traits (`Len`, `Push`, `Enqueue`, …) composed into `Sequence` / `Lifo` / `Fifo` / `CountedFifo` / `Map`; `push_all`, `enqueue_all`, `drain_into` |

### [Allocators](/stdlib/allocators) {#allocators}

| Module | Header | Description |
|--------|--------|-------------|
| allocator | `alloc/allocator.h` | `Allocate` / `Deallocate` traits composed into `Allocator` / `ArenaAllocator`; `alloc_t` / `alloc_array_t` / `dealloc_t` and `CountingAllocator<A>` over any allocator |
| bump | `alloc/bump.h` | Linear bump-pointer arena; O(1) alloc, reset-only free |
| slab | `alloc/slab.h` | Freelist slab for fixed-size objects; O(1) alloc/dealloc |
| pool | `alloc/pool.h` | Variable-size first-fit pool with coalescing |
| tlsf | `alloc/tlsf.h` | Two-Level Segregated Fit; O(1) worst-case general heap |

### [Synchronization](/stdlib/sync) {#synchronization}

| Module | Header | Description |
|--------|--------|-------------|
| spinlock | `sync/spinlock.h` | Busy-wait mutual exclusion (`__sync_lock_test_and_set`) |
| lockfree | `sync/lockfree.h` | `LFQueue<T>` lock-free SPSC ring buffer |
| channel | `sync/channel.h` | `Chan<T>`: typed bounded blocking MPMC channel over the `chan_*` built-ins |
| mpsc | `sync/mpsc.h` | `MpscQueue<T>` spinlock-guarded MPSC ring, non-blocking |
| task | `sync/task.h` | Cooperative scheduler; typed `TaskScheduler::spawn`; `TaskRunner` trait + `run_rounds` |
| thread_bare | `sync/thread_bare.h` | Priority-ordered freestanding threads (no OS) |
| bare_spawn | `sync/bare_spawn.sc` | Reference "Hook" backend for the `spawn`/`join` language keywords on freestanding targets |

### [IPC](/stdlib/ipc) {#ipc}

| Module | Header | Description |
|--------|--------|-------------|
| pipe | `ipc/pipe.h` | Anonymous pipes (hosted) — one-way byte stream, typically parent/child after `fork()` |
| uds | `ipc/uds.h` | Unix domain sockets (hosted) — named IPC between unrelated processes, non-blocking/`Reactor`-pairable |

### [Async](/stdlib/async) {#async}

| Module | Header | Description |
|--------|--------|-------------|
| future | `async/future.h` | `Poll<T>`, the `Future` trait, `Waker`, `Context`, `WakerSlot`/`WaitList`, `Ready`/`Pending`/`YieldNow` |
| coro | `async/coro.h` | `ASYNC_BEGIN` / `AWAIT` / `ASYNC_RETURN`: stackless coroutines as `switch` + `goto` state machines |
| executor | `async/executor.h` | `AsyncTask<F, T>`, `JoinHandle<T>`, the no-heap deterministic `LocalExecutor`, `Park` (`VirtualClock`, `FnPark`) |
| timer | `async/timer.h` | `TimerQueue` (over `dsp/timer_wheel`), `Sleep`, `Interval` |
| combinator | `async/combinator.h` | `join2`/`join3`/`join_all`, `select2`/`select_any`, `timeout`, `map_fut`, `and_then` |
| channel | `async/channel.h` | `Oneshot<T>`, bounded MPMC `AsyncChannel<T>` |
| sync | `async/sync.h` | `AsyncMutex`, `AsyncSemaphore`, `AsyncNotify`, `CancelToken`, `with_cancel` |
| host | `async/host.h` | `block_on(&fut)`, `HostPark` (hosted) |
| pool | `async/pool.h` | `ThreadPool`: the multi-threaded executor (hosted) |
| net | `async/net.h` | `IoDriver` on the reactor, `Runtime`, `AsyncTcpListener` / `AsyncTcpStream` (hosted) |

### [Real-Time Scheduler](/stdlib/sched) {#real-time-scheduler}

| Module | Header | Description |
|--------|--------|-------------|
| reactor | `sched/reactor.h` | `Reactor` — kqueue-backed I/O event loop driving `TaskScheduler` |
| io_nb | `sched/io_nb.h` | Non-blocking file/socket helpers meant to pair with the reactor |

### [Networking](/stdlib/net) {#networking}

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

### [TLS](/stdlib/tls) {#tls}

| Module | Header | Description |
|--------|--------|-------------|
| tls | `tls/tls.h` | `TlsStream` clients and `TlsAcceptor` servers over a socket: the OS's TLS (OpenSSL via `dlopen`, SChannel, SecureTransport) or SafeC's own TLS 1.3; system-root verification, ALPN, client certificates, non-blocking `TLS_WANT_*` |

### [HTTP & Web](/stdlib/http) {#http-web}

| Module | Header | Description |
|--------|--------|-------------|
| http | `http/http.h` | HTTP/1.1 client + server: keep-alive `HttpServer` (HTTPS with a `TlsServerConfig`), URL client with redirects + gzip and `https://` (`http_fetch`), request/response helpers, chunked coding |
| headers | `http/headers.h` | `HttpHeaders` case-insensitive header map, header parameters |
| router | `http/router.h`, `http/route.h` | `Router` with `:param`/`*` patterns, 404/405, middleware (logger, CORS, gzip, body limit) |
| static_files | `http/static_files.h` | Static file serving: MIME types, ETag/Last-Modified/304, byte ranges |
| cookie | `http/cookie.h` | Cookie header parsing, `Set-Cookie` building |
| multipart | `http/multipart.h` | `multipart/form-data` parsing + building |
| sse | `http/sse.h` | Server-Sent Events: server stream, parser, client |
| date | `http/date.h` | HTTP date formatting/parsing |
| cors | `http/cors.h` | CORS preflight detection + response headers |
| jwt | `http/jwt.h` | HS256 JWT sign/verify (HMAC-SHA256) |
| oauth2 | `http/oauth2.h` | OAuth2 client: authorization-code + refresh-token exchange (RFC 6749) |
| websocket | `http/websocket.h` | WebSocket client + server, upgrades on the router, message reassembly |
| rpc | `rpc/rpc.h` | gRPC-inspired RPC-over-HTTP (length-prefixed framing, path-based dispatch) |
| server_fn | `rpc/server_fn.h` | "Write once, call from anywhere" JSON-marshaled server functions (Dioxus/Leptos-style) |
| reactive | `reactive/signal.h` | `Signal<T>` fine-grained reactive state (WASM client hydration) |
| wasm | `wasm/dom.h`, `wasm/hydrate.h` | [wasm32 DOM and browser APIs](/stdlib/wasm) (events, timers, fetch, storage, history) + signal hydration, with the `wasm/safec_dom.js` glue; `wasm/wasm_rt.h` is the freestanding runtime (size-class `malloc`/`free` over `memory.grow`, `mem*`/`str*`/`strto*`/`qsort`, 128-bit compiler-rt builtins, trapping `abort`) for wasm32/wasm64 — only a wasm target's std archive includes it (see [WebAssembly](/reference/baremetal#wasm)) |
| scx | n/a (transpiler) | JSX/TSX-style HTML templating — `.scx` files transpile to plain SafeC before `safec` ever sees them |

### Machine Learning — see [ML](/stdlib/ml) {#machine-learning-see-ml}

### [GUI](/stdlib/gui) {#gui}

| Module | Header | Description |
|--------|--------|-------------|
| gui | `gui/gui.h` | Windows (options, HiDPI, cursors, focus), events, colors, the clipboard and clock over an RGBA8888 buffer per window; X11, Wayland, Win32, Cocoa, WebAssembly, headless and bare-metal framebuffer backends |
| app | `gui/gui_app.h` | `GuiApp`: several windows from one event loop, timers, per-frame animation, redraw-on-change, a pumpable loop |
| backend | `gui/gui_backend.h` | The `GuiBackend` interface every backend implements, and `GuiPresenter` for GPU presentation |
| headless | `gui/gui_headless.h` | An in-memory backend with a virtual clock and input injection, for tests |
| canvas | `gui/gui_canvas.h` | Anti-aliased vector canvas: paths, strokes, dashes, gradients, patterns, transforms, clipping, blend modes, images, text — see [GUI Graphics](/stdlib/gui-graphics) |
| path | `gui/gui_path.h` | `GuiPath` (with SVG path data), `GuiMatrix`, `GuiRect` |
| text | `gui/gui_text.h` | TrueType/OpenType reader (`GuiTypeface`, kerning, system fonts, a built-in fallback face) and `GuiTextLayout` |
| widget | `gui/gui_widget.h` | Retained widget toolkit: layouts, themes, focus, menus, dialogs, tooltips and 30+ widgets — see [GUI Widgets](/stdlib/gui-widgets) |
| png | `gui/gui_png.h` | `GuiImage`: from-scratch PNG decoder and encoder, scaling |
| draw | `gui/gui_draw.h` | Integer rectangles, borders, lines, built-in 8x8 bitmap text |
| font | `gui/gui_font.h` | `GuiFont` bitmap fonts of any glyph size; `SCXF` save/load |
| svg | `gui/gui_svg.h` | SVG subset renderer (shapes, paths with cubic curves) |
| fb | `gui/gui_fb.h` | Bare-metal framebuffer backend setup: `gui_fb_configure`, `gui_fb_inject_event`, `gui_fb_set_clock` |

### [Audio & MIDI](/stdlib/audio) {#audio-midi}

| Module | Header | Description |
|--------|--------|-------------|
| audio | `audio/audio.h` | Real-time audio I/O (RtAudio-style): APIs, device enumeration, output/input/duplex streams with a real-time callback; ALSA, JACK, Core Audio, WASAPI, Web Audio and dummy backends |
| midi | `audio/midi.h` | Real-time MIDI I/O (RtMidi-style): ports, virtual ports, callback or lock-free queue input; message builders, running-status parser/encoder; ALSA sequencer, JACK, Core MIDI, WinMM, Web MIDI and dummy backends |
| convert | `audio/audio_convert.h` | Sample-format (i8/i16/i24/i32/f32/f64, TPDF dither) and layout conversion; std/dsp `Fixed`/`AudioBuffer` interop |

### [Filesystems](/stdlib/fs) {#filesystems}

| Module | Header | Description |
|--------|--------|-------------|
| block | `fs/block.h` | `BlockDevice` driver interface (function pointer–based) |
| partition | `fs/partition.h` | MBR partition table parser (4 primary entries) |
| vfs | `fs/vfs.h` | VFS with longest-prefix mount routing; `VfsNode` forwarding |
| fat | `fs/fat.h` | FAT32 read-only driver; 8.3 path walk, cluster chain |
| ext | `fs/ext.h` | ext2 read-only driver; inode walk, direct-block reads |
| tmpfs | `fs/tmpfs.h` | In-memory FS; 32 inodes, 64 KiB data pool; full CRUD |

### [DSP & Real-Time](/stdlib/dsp) {#dsp-real-time}

| Module | Header | Description |
|--------|--------|-------------|
| fixed | `dsp/fixed.h` | Q8.24 fixed-point arithmetic (`newtype Fixed = int`) |
| dsp | `dsp/dsp.h` | `dsp_dot`, `dsp_scale`, `dsp_add`, `dsp_clip`, `dsp_peak`, `dsp_rms`, SIMD-FMA `dsp_dot_f64` |
| complex_dsp | `dsp/complex_dsp.h` | Value-type `Complex`/`FComplex` with operator overloading (`+`,`-`,`*`,`/`,`abs`,`arg`,`conj`) |
| dft | `dsp/dft.h` | Direct O(n²) DFT/IDFT, arbitrary length (float + Q8.24) |
| fft | `dsp/fft.h` | In-place radix-2 Cooley-Tukey FFT/IFFT, O(n log n), power-of-two length (float + Q8.24) |
| convolution | `dsp/convolution.h` | Linear convolution — direct O(len_x·len_h) (SIMD-accelerated) and FFT-based O(n log n) |
| window | `dsp/window.h` | Rectangular/Hann/Hamming/Blackman analysis windows |
| sample_filter | `dsp/sample_filter.h` | `SampleProcessor` / `BlockProcessor` / `Resettable` traits composed into `SampleFilter` (all 15 filters); `FilterChain`, generic impulse/step response |
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

### [Security & Cryptography](/stdlib/crypto) {#security-cryptography}

| Module | Header | Description |
|--------|--------|-------------|
| aes | `crypto/aes.h` | AES-128/256 ECB + CBC; full S-box + key expansion |
| sha256 | `crypto/sha256.h` | SHA-256/224; streaming and one-shot API |
| hash | `crypto/hash.h` | `Hasher` / `BlockHash` traits; `hash_bytes` over any hash |
| hmac | `crypto/hmac.h` | `Hmac<H>` over any `BlockHash`; one-shot `hmac_sha256` |
| rng | `crypto/rng.h` | ChaCha20 CSPRNG, seeded from the OS (`BCryptGenRandom`, `getentropy`, `getrandom`), `rdrand` or `/dev/urandom` |
| chacha20 | `crypto/chacha20.h` | ChaCha20, Poly1305, ChaCha20-Poly1305 AEAD (RFC 8439), constant time |
| x25519 | `crypto/x25519.h` | X25519 key exchange (RFC 7748), constant-time ladder |
| ec | `crypto/ec.h` | P-256/P-384 ECDH + ECDSA (complete formulas, RFC 6979 signing) |
| rsa | `crypto/rsa.h` | RSA PKCS#1 v1.5 + PSS verify and sign (1024–4096 bits) |
| hkdf | `crypto/hkdf.h` | HKDF extract/expand over any `BlockHash` |
| digest | `crypto/digest.h` | Hashes chosen at run time (`DIGEST_*`), MGF1 |
| ct | `crypto/ct.h` | Constant-time compare/select helpers |
| bignum | `crypto/bignum.h` | Fixed-width Montgomery arithmetic, modular exponentiation |
| secure_alloc | `crypto/secure_alloc.h` | Slab allocator with zeroing-on-free |
| x509 | `crypto/x509.h` | X.509 DER/ASN.1 parser; SAN, IP and wildcard host matching, validity; RSA/ECDSA signature verification (`X509Parsed`) |
| tls | `crypto/tls.h` | TLS 1.3 record layer; AES-256-GCM record protection (RFC 8446 §5.2), nonce XOR seq |

### [Debugging & Profiling](/stdlib/debug) {#debugging-profiling}

| Module | Header | Description |
|--------|--------|-------------|
| perf | `debug/perf.h` | Arch-dispatched cycle counter (RDTSC/cntvct_el0/CSR); ns calibration |
| coverage | `debug/coverage.h` | 1024-site coverage tracker; `COV_SITE()` macro; `report()` |
| jtag | `debug/jtag.h` | `debug_break` per arch; ARM/AArch64 semihosting; ITM ports |

### [SIMD](/stdlib/simd) {#simd}

| Module | Header | Description |
|--------|--------|-------------|
| simd | `simd/simd.h` | Portable core: `f32x4`/`i32x8`/... type aliases over native `vec<T,N>`; load/store, splat, fma, min/max, horizontal reductions |
| x86_64 / aarch64 / riscv / wasm / spirv / cortex_m / cuda / rocm | `simd/*.h` | Thin per-ISA convenience layers (native-preferred-width naming, real-hardware verification notes) — same portable source underneath, no separate implementation |

### [Hardware Abstraction Layer](/stdlib/hal) {#hardware-abstraction-layer}

| Module | Header | Description |
|--------|--------|-------------|
| gpio | `hal/gpio.h` | `GpioPin<C>`: direction, read/write/toggle, pull-up/down (`GpioGeneric`, `Stm32F4Gpio`) |
| i2c | `hal/i2c.h` | `I2cBus<C>`: polling master — write/read/write_read/probe (`I2cGeneric`, `Stm32F4I2c`) |
| spi | `hal/spi.h` | `SpiDevice<C>`: polling master — transfer/write/read, chip-select (`SpiGeneric`, `Stm32F4Spi`) |
| uart | `hal/uart.h` | `Uart<C>`: polling serial — byte/string I/O, ready flags (`UartGeneric`, `Pl011`, `Ns16550`, `Stm32F4Usart`) |
| timer | `hal/timer.h` | `Timer<C>`: period/start/stop/read/flag (`TimerGeneric`, `Stm32F4Tim`) |
| watchdog | `hal/watchdog.h` | `Watchdog<C>`: enable/feed/caused_reset (`WatchdogGeneric`, `Stm32F4Iwdg`) |
| cortex_m | `hal/cortex_m.h` | NVIC, SysTick, SCB (ARM Cortex-M) |
| aarch64 | `hal/aarch64.h` | System registers, Generic Timer, GICv2 (ARMv8-A) |
| riscv | `hal/riscv.h` | CSR access, CLINT, PLIC |

### [Interrupts & MMIO](/stdlib/interrupt) {#interrupts-mmio}

| Module | Header | Description |
|--------|--------|-------------|
| mmio | `interrupt/mmio.h` | `MmioReg` + free-function register read/write/field access |
| bitfield | `interrupt/bitfield.h` | Pure bit-manipulation functions (`bf_extract32`, `bf_insert32`, ...) |
| isr | `interrupt/isr.h` | Software ISR dispatch table (256 slots) |
| vector_table | `interrupt/vector_table.h` | Hardware vector table — Cortex-M `VTOR`/RISC-V `mtvec`/AArch64 `VBAR_EL1` |
| clock | `interrupt/clock.h` | PLL/clock-source configuration |

### [Kernel Primitives](/stdlib/kernel) {#kernel-primitives}

| Module | Header | Description |
|--------|--------|-------------|
| frame | `kernel/frame.h` | Bitmap physical frame allocator (4 KiB frames) |
| paging | `kernel/paging.h` | `PageEntry`/`PageTable` — raw page table manipulation |
| mmu | `kernel/mmu.h` | `MmuContext<F>` — real page tables (x86-64, x86, Sv39, Sv32, AArch64, ARMv7-A): map/unmap/walk/TLB/activate |
| process | `kernel/process.h` | `PCB` — process control block |
| scheduler | `kernel/scheduler.h` | Priority round-robin scheduler over PCBs |
| ipc | `kernel/ipc.h` | `Mailbox` — fixed-capacity message queue |
| syscall | `kernel/syscall.h` | Syscall registration/dispatch table |

### [Testing & Benchmarking](/stdlib/testing) {#testing-benchmarking}

| Module | Header | Description |
|--------|--------|-------------|
| test | `test/test.h` | `TestSuite` + `ASSERT_*` macros |
| bench | `test/bench.h` | `BenchSuite` — wall-clock timed iteration benchmarks |
| fuzz | `test/fuzz.h` | `FuzzTarget` — lightweight in-process mutation fuzzer |

### [Encoding](/stdlib/encoding) {#encoding}

| Module | Header | Description |
|--------|--------|-------------|
| ascii | `encoding/ascii.h` | ASCII validation, classification, case conversion |
| utf8 | `encoding/utf8.h` | UTF-8 encode/decode/validate, codepoint count |
| url | `encoding/url.h` | `Url` parse/serialize/resolve, `QueryParams`, percent and form encoding |
| base64 | `encoding/base64.h` | RFC 4648 Base64 with padding |
| deflate | `encoding/deflate.h` | DEFLATE compress/decompress, zlib + gzip containers, CRC-32, Adler-32 |

### [Utilities](/stdlib/utilities) {#utilities}

| Header | Description |
|--------|-------------|
| `bit.h` | Bit manipulation (C23 `<stdbit.h>` + popcount/clz/ctz/bswap builtins) |
| `cmp.h` | Compile-time orderings: the `Order` trait, `Less` / `Greater` / `FnOrder` (see [Orderings](/stdlib/collections#orderings)) |
| `ownership.h` | `std::replace` / `std::swap`: take a value out of borrowed memory by putting another in its place (see [Ownership](/reference/ownership#borrowed-values)) |
| `traits.h` | Capability traits shared across modules: `Resettable` (arena allocators, DSP filters) |
| `convert.h` | String ↔ number parsing (C11/C17), `*ok` success flag on failure |
| `dma.h` | Cache-coherent DMA buffer descriptors (64-byte aligned) |
| `fmt.h` | Safe `snprintf`-based formatting into caller-supplied buffers |
| `heap.h` | Unified heap: TLSF-backed static buffer (freestanding) or malloc/free/realloc (hosted) |
| `log.h` | Configurable logging, zero overhead when `LOG_LEVEL` is 0 |
| `panic.h` | Opt-in panic handler — infinite loop (freestanding) or `abort()` (hosted) by default |
| `result.h` | `Result<T, E>`: a value or an error, stored inline (`Result::new_ok` / `Result::new_err`) |
| `sys.h` | Process-control constants (`EXIT_SUCCESS`/`EXIT_FAILURE`), PRNG constants |
| `complex.h` | Complex numbers (C99 `<complex.h>`) as `[real, imag]` float/double pairs |

### [C Compatibility Headers](/stdlib/utilities#c-type-and-limit-headers) {#c-compatibility-headers}

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

## Generic Pattern {#generic-pattern}

Containers and other value-holding APIs are generic structs and functions, monomorphized per element type. They hand out `?&T` references (narrow them with `match`, `.default(x)`, or `std::expect_ref`), return `?T` when there may be no value, and take typed `fn` callbacks — the standard library has no `void*` element or callback parameters. `T` is written once, on the variable, and the constructor infers it from that target type:

```c
#include <std/collections/vec.h>

int main() {
    struct Vec<int> v = std::Vec::new();
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

Where a container must hold callbacks with *different* context types at once (a timer wheel, a widget tree, a VFS mounting several drivers), the slot stores the pair type-erased, but the only way to fill it is a generic method that takes a matching `fn(?&T)` and `?&T` — `TimerWheel::add`, `Widget::on_click`, `Vfs::mount`, `TaskScheduler::spawn` — so every call is still checked at compile time. The erased slot is a `?&Erased` (`std/erased.h`), never a `void*`. std has no `void*` even at the C ABI boundary: untyped memory is bytes (`unsigned char*`, what `mem.h`'s raw allocator returns), libc/OS/C-library objects (`FILE`, pthread objects, `SSL`, CoreFoundation references, Win32 handles, ...) are opaque structs, and callbacks are `fn` types. `tests/std_modules/api_lint.py` (run by the smoke test) fails the build if a raw pointer appears in a public declaration, or a `void*` anywhere in std.

Buffers are **slices**. Wherever a C API would take a pointer and a length (or return an array through a pointer), std takes a built-in slice: `[]T` when it writes the elements, `[]const T` when it only reads them — `f.read(buf[..])`, `sha256(data, digest[..])`, `fft(xs[0..n])`, `String::from_bytes(str_bytes("text"))`. A slice carries its length, indexing it is bounds-checked, and an output that must have a fixed size (a key, a digest) is checked on entry. See [Slices](/reference/types#slice-types).

## Including the Standard Library {#including-the-standard-library}

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

When building with the `safeguard` package manager, the std modules the program includes (and the ones its code calls without an include: `new`, channels) are compiled into `build/deps/libsafec_std.a` and linked, with the system libraries they need — or, in a release install, taken from its prebuilt std archive (see [Standard Library Modules](/advanced/safeguard#standard-library-linking)).

When building manually, put the SafeC checkout on the include path (headers are named `<std/...>`) and link an archive of all of std — `safeguard build` of a project with `[build] std = "all"` makes one (or build it from every `std/**/*.sc`) — with the system libraries of the modules you use (`safeguard std-modules std/x.h` lists them):
```bash
safec myfile.sc -I /path/to/SafeC -o myfile.ll
clang myfile.ll /path/to/libsafec_std.a -lm -lpthread -o myfile
```
