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

### C Compatibility Headers

| Header | Description |
|--------|-------------|
| `assert.h` | Runtime assertions (`runtime_assert`, `assert_true`); NDEBUG support |
| `ctype.h` | Character classification (`char_is_alpha`, `char_is_digit`, ...) and conversion |
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
