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

## Physical Frame Allocator (`kernel/frame.h`)

`FrameAllocator` is a bitmap allocator over 4 KiB frames (`FRAME_BITMAP_SIZE`
= 4096 words = 131072 frames = 512 MB tracked by one instance). `init(n)`,
`alloc()` (frame number or `-1` on OOM), `free(frame)`, `is_used(frame)`,
`mark_range(start, count)` (reserve a range up front — e.g. the kernel
image itself), `free_count()`.

## Paging (`kernel/paging.h`)

`PageEntry` (one raw 64-bit entry: physical frame number in bits [63:12],
flags in bits [11:0]) and `PageTable` (512-entry table of them).
Flags: `PAGE_PRESENT`, `PAGE_WRITABLE`, `PAGE_USER`, `PAGE_WRITE_THRU`,
`PAGE_NO_CACHE`, `PAGE_ACCESSED`, `PAGE_DIRTY`, `PAGE_HUGE`.
`PageTable` methods: `init()`, `map(idx, phys_addr, flags)`,
`unmap(idx)`, `is_present(idx)`, `get_phys(idx)`, `get_flags(idx)`,
`set_flags(idx, flags)`.

## MMU Context (`kernel/mmu.h`)

`MmuContext` is a full 2-level (Sv30-style: 9+9+12 bits, 1 GiB address
space per context) virtual-memory context built on `paging.h` +
`frame.h` — `mmu_init(root, frames)` where `root` is a zeroed, 4 KiB-
aligned physical page table address and `frames` is a live
`FrameAllocator`. `map(virt, phys, flags)` (allocates an L2 frame from
`frames` if needed), `unmap(virt)`, `walk(virt, &stack phys_out)` (resolve
without modifying), `tlb_flush_all()`/`tlb_flush_page(virt)`, and
`activate()` — each of the last three is architecture-dispatched
internally (x86-64: `CR3`/`invlpg`; RISC-V: `sfence.vma`/`satp`; AArch64:
`TTBR0_EL1`/`TLBI`).

## Process Control Block (`kernel/process.h`)

`PCB` — `pid`, `state` (`PROC_READY`/`PROC_RUNNING`/`PROC_BLOCKED`/
`PROC_ZOMBIE`), `priority`, saved `stack_ptr`/`pc`, `page_table` (physical
root address), `parent_pid`, `exit_code`. `pcb_init(pid, entry, sp,
page_table)`; methods `set_state`, `set_priority`, `save_context(sp, pc)`,
`exit(code)` (transitions to `PROC_ZOMBIE`).

## Scheduler (`kernel/scheduler.h`)

`Scheduler` — priority round-robin over up to `SCHED_MAX_PROCS` (256)
PCBs. `sched_init()`; `spawn_proc(&stack PCB)` (returns index or `-1` if
full), `next()` (select highest-priority READY process), `yield()` (mark
current READY, select next), `block_current()`, `unblock(idx)`,
`remove(idx)` (reap a zombie), `ready_count()`.

## IPC Mailbox (`kernel/ipc.h`)

`Mailbox` (via `mailbox_init(owner_pid)`) — a fixed-capacity
(`MAILBOX_CAPACITY` = 64 messages, `MSG_MAX_SIZE` = 256 bytes payload)
message queue. `send(sender_pid, type, payload, size)`, `recv(&stack
Message out)`, `peek(&stack Message out)` (non-destructive), `has_msg()`,
`length()`, `clear()`.

## Syscall Table (`kernel/syscall.h`)

`SyscallTable` (via `syscall_init()`) — up to `SYSCALL_MAX` (256)
handlers of signature `long long(long long, long long, long long)`.
`register_(num, handler)`/`unregister_(num)` (trailing underscore:
`register` is a C keyword), `dispatch(num, arg0, arg1, arg2)` (returns the
handler's result, or `-1` if unregistered), `is_registered(num)`.
