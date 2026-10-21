# Kernel Primitives (`std::kernel`)

`std/kernel/` provides the building blocks for writing a small kernel or
hypervisor from scratch — physical memory allocation, page tables and MMU
context, process control blocks, a round-robin scheduler, IPC mailboxes,
and syscall dispatch. All freestanding-safe; this is lower-level than
[`std::hal`](/stdlib/hal)/[`std::interrupt`](/stdlib/interrupt), which
assume you're writing firmware for a single address space rather than
managing multiple ones.

```c
#include <std/kernel/frame.h>
#include <std/kernel/process.h>
#include <std/kernel/scheduler.h>

static struct FrameAllocator frames;

void kernel_setup() {
    frames.init(1024);              // 1024 4K frames = 4 MiB tracked
    long long f = frames.alloc();   // -1 on OOM

    struct PCB p = std::pcb_init(1, 0x1000, 0x8000, 0);   // pid, entry, sp, page_table
    struct Scheduler sched = std::sched_init();
    unsafe { sched.spawn_proc(&p); }
    int next = sched.next();        // highest-priority READY process index
}
```

## Physical Frame Allocator (`kernel/frame.h`) {#physical-frame-allocator-kernelframeh}

`FrameAllocator` is a bitmap allocator over 4 KiB frames (`FRAME_BITMAP_SIZE`
= 4096 words = 131072 frames = 512 MB tracked by one instance). `init(n)`,
`alloc()` (frame number or `-1` on OOM), `free(frame)`, `is_used(frame)`,
`mark_range(start, count)` (reserve a range up front — e.g. the kernel
image itself), `free_count()`. `init` clamps `n` to the 131072 frames the
bitmap tracks, and freeing a frame that is already free does nothing. `init_at(base, n)` starts frame 0 at physical
address `base` instead of 0 — on most Arm and RISC-V boards RAM starts at
1 or 2 GiB — and `frame_addr(f)` gives frame `f`'s physical address.

## Paging (`kernel/paging.h`) {#paging-kernelpagingh}

`PageEntry` (one raw 64-bit entry: physical frame number in bits [63:12],
flags in bits [11:0]) and `PageTable` (512-entry table of them).
Flags: `PAGE_PRESENT`, `PAGE_WRITABLE`, `PAGE_USER`, `PAGE_WRITE_THRU`,
`PAGE_NO_CACHE`, `PAGE_ACCESSED`, `PAGE_DIRTY`, `PAGE_HUGE`.
`PageTable` methods: `init()`, `map(idx, phys_addr, flags)`,
`unmap(idx)`, `is_present(idx)`, `get_phys(idx)`, `get_flags(idx)`,
`set_flags(idx, flags)`.

`PAGE_SIZE` is 4096 bytes and `PAGE_TABLE_SIZE` is 512 entries per table.

## MMU Context (`kernel/mmu.h`) {#mmu-context-kernelmmuh}

`MmuContext<F>` builds the page tables the CPU itself walks. `F` is a
page-table format type chosen at compile time (the same pattern as
`BST<K, V, C>`'s ordering), one per architecture:

| Format | Tables |
|---|---|
| `MmuX86_64` | x86-64 4-level paging, 48-bit VA, 8-byte entries. Non-executable pages set NX, which needs `EFER.NXE` |
| `MmuX86_32` | x86 32-bit paging without PAE: 2 levels, 4-byte entries (no NX) |
| `MmuSv39` | RISC-V Sv39 (RV64): 3 levels, 8-byte PTEs; leaves set A and D up front |
| `MmuSv32` | RISC-V Sv32 (RV32): 2 levels, 4-byte PTEs |
| `MmuAArch64` | AArch64 stage 1, 4 KiB granule, 48-bit VA: 4 levels. Leaves use MAIR index 0 for normal memory and 1 for device memory (program `MAIR_EL1` with Attr0 = 0xFF, Attr1 = 0x04); `TCR_EL1` must select a 4 KiB granule and T0SZ = 16 |
| `MmuArmV7` | ARMv7-A short descriptors: a 16 KiB, 16 KiB-aligned L1 table and 1 KiB L2 tables; normal memory is write-back (TEX=001 C B), device memory TEX=000 B |

`MmuNative` names the format of the CPU being compiled for (there is none
on Cortex-M, which has an MPU but no MMU, or on wasm). Every format can be
built and walked on any target — on the host for a test or an image
builder, say — but `activate()` and the TLB functions only act when the
format is the running bare-metal CPU's; they return 0 otherwise.

```c
#include <std/kernel/mmu.h>

static struct FrameAllocator g_frames;

void vm_setup(unsigned long root) {           // a zeroed, 4 KiB-aligned table
    g_frames.init_at(0x80400000UL, 256UL);
    struct MmuContext<MmuNative> m = std::mmu_init(root, &g_frames, 0UL);
    m.map(0x80000000UL, 0x80000000UL, MMU_WRITE | MMU_EXEC);   // identity: kernel image
    m.map(0x10000000UL, 0x10000000UL, MMU_WRITE | MMU_DEVICE); // a UART
    m.activate();                             // satp / CR3 / TTBR0
}
```

- `mmu_init(root, frames, phys_offset)`: `root` is the physical address of
  a zeroed root table (4 KiB aligned; 16 KiB for `MmuArmV7`), `frames` a
  live `&static` `FrameAllocator` that supplies one frame per new
  intermediate table, and the tables are read and written at physical
  address + `phys_offset` (0 for an identity-mapped kernel, the direct-map
  base for a higher-half one).
- `map(virt, phys, flags)` maps one 4 KiB page; `flags` are `MMU_WRITE`,
  `MMU_EXEC`, `MMU_USER` and `MMU_DEVICE` (every mapping is readable; RISC-V
  leaves cacheability to its PMAs). It returns 0 when a table frame can't be
  allocated or a larger page already covers `virt`.
- `unmap(virt)`, `walk(virt, &phys_out)` (follows larger block/superpage
  leaves too), and `entry(virt, &entry_out)` (the raw leaf entry).
- `tlb_flush_all()`, `tlb_flush_page(virt)` and `activate()`: x86 reloads
  `CR3` / `invlpg`; RISC-V writes `satp` (which turns translation on for S
  and U mode) / `sfence.vma`; AArch64 writes `TTBR0_EL1` / `TLBI`; ARMv7
  writes `TTBR0` / `TLBIALL`. Turning paging on (`CR0.PG`, `SCTLR.M`) and
  the MAIR/TCR setup stay with the boot code.

`tests/stdlib/baremetal/qemu/run.sh` runs every format on QEMU with
translation on — Sv39 and Sv32 from S-mode, AArch64 and ARMv7 with the MMU
enabled, x86 32-bit paging and x86-64 long mode — reading and writing a
page through an alias mapping.

## Process Control Block (`kernel/process.h`) {#process-control-block-kernelprocessh}

`PCB` — `pid`, `state` (`PROC_READY`/`PROC_RUNNING`/`PROC_BLOCKED`/
`PROC_ZOMBIE`), `priority`, saved `stack_ptr`/`pc`, `page_table` (physical
root address), `parent_pid`, `exit_code`. `pcb_init(pid, entry, sp,
page_table)`; methods `set_state`, `set_priority`, `save_context(sp, pc)`,
`exit(code)` (transitions to `PROC_ZOMBIE`).

## Scheduler (`kernel/scheduler.h`) {#scheduler-kernelschedulerh}

`Scheduler` — priority round-robin over up to `SCHED_MAX_PROCS` (256)
PCBs. `sched_init()`; `spawn_proc(&PCB)` (returns index or `-1` if
full), `next()` (select highest-priority READY process), `yield()` (mark
current READY, select next), `block_current()`, `unblock(idx)`,
`remove(idx)` (reap a zombie), `ready_count()`. `next()` takes the highest
priority among READY processes and rotates through equal priorities
starting after the current one; `remove` keeps `current` on the same
process (`-1` if it was the one removed).

## IPC Mailbox (`kernel/ipc.h`) {#ipc-mailbox-kernelipch}

`Mailbox` (via `mailbox_init(owner_pid)`) — a fixed-capacity
(`MAILBOX_CAPACITY` = 64 messages, `MSG_MAX_SIZE` = 256 bytes payload)
message queue. `send(sender_pid, type, payload, size)` (`payload` is a
`const ?&unsigned char`; pass `null` with `size` 0 for a signal-only message), `recv(&Message out)`,
`peek(&Message out)` (non-destructive), `has_msg()`,
`length()`, `clear()`.

## Syscall Table (`kernel/syscall.h`) {#syscall-table-kernelsyscallh}

`SyscallTable` (via `syscall_init()`) — up to `SYSCALL_MAX` (256)
handlers typed `fn long long(long long, long long, long long)`.
`register_(num, handler)`/`unregister_(num)` (trailing underscore:
`register` is a C keyword), `dispatch(num, arg0, arg1, arg2)` (returns the
handler's result, or `-1` if unregistered), `is_registered(num)`.
