# 커널 기본 요소 (`std::kernel`)

`std/kernel/`은 작은 커널이나 하이퍼바이저를 처음부터 작성하기 위한
구성 요소 — 물리 메모리 할당, 페이지 테이블과 MMU 컨텍스트, 프로세스
제어 블록, 라운드 로빈 스케줄러, IPC 메일박스, 시스템 콜 디스패치 —
를 제공한다. 모두 프리스탠딩 환경에서 안전하며, 단일 주소 공간을 위한
펌웨어를 작성한다고 가정하는 [`std::hal`](/ko/stdlib/hal)/
[`std::interrupt`](/ko/stdlib/interrupt)보다 더 저수준으로, 여러 개의
주소 공간을 관리하는 데 쓰인다.

```c
#include <std/kernel/frame.h>
#include <std/kernel/process.h>
#include <std/kernel/scheduler.h>

static struct FrameAllocator frames;

void kernel_setup() {
    frames.init(1024);              // 1024개의 4K 프레임 = 4 MiB 추적
    long long f = frames.alloc();   // OOM 시 -1

    struct PCB p = std::pcb_init(1, 0x1000, 0x8000, 0);   // pid, entry, sp, page_table
    struct Scheduler sched = std::sched_init();
    unsafe { sched.spawn_proc(&p); }
    int next = sched.next();        // 우선순위가 가장 높은 READY 프로세스 인덱스
}
```

## 물리 프레임 얼로케이터 (`kernel/frame.h`) {#physical-frame-allocator-kernelframeh}

`FrameAllocator`는 4 KiB 프레임 단위의 비트맵 얼로케이터다
(`FRAME_BITMAP_SIZE` = 4096 워드 = 131072개 프레임 = 인스턴스 하나당
512 MB 추적). `init(n)`, `alloc()`(프레임 번호, OOM 시 `-1`),
`free(frame)`, `is_used(frame)`, `mark_range(start, count)`(범위를 미리
예약 — 예를 들어 커널 이미지 자체), `free_count()`.

## 페이징 (`kernel/paging.h`) {#paging-kernelpagingh}

`PageEntry`(64비트 원시 엔트리 하나: 비트 [63:12]에 물리 프레임 번호,
비트 [11:0]에 플래그)와 `PageTable`(이를 512개 담는 테이블). 플래그:
`PAGE_PRESENT`, `PAGE_WRITABLE`, `PAGE_USER`, `PAGE_WRITE_THRU`,
`PAGE_NO_CACHE`, `PAGE_ACCESSED`, `PAGE_DIRTY`, `PAGE_HUGE`.
`PageTable` 메서드: `init()`, `map(idx, phys_addr, flags)`,
`unmap(idx)`, `is_present(idx)`, `get_phys(idx)`, `get_flags(idx)`,
`set_flags(idx, flags)`.

## MMU 컨텍스트 (`kernel/mmu.h`) {#mmu-context-kernelmmuh}

`MmuContext`는 `paging.h` + `frame.h` 위에 구축된 완전한 2단계
(Sv30 스타일: 9+9+12비트, 컨텍스트당 1 GiB 주소 공간) 가상 메모리
컨텍스트다 — `mmu_init(root, frames)`에서 `root`는 0으로 초기화된, 4
KiB 정렬된 물리 페이지 테이블 주소이고 `frames`는 살아 있는
`FrameAllocator`다. `map(virt, phys, flags)`(필요하면 `frames`에서 L2
프레임을 할당), `unmap(virt)`, `walk(virt, &stack phys_out)`(수정 없이
해석), `tlb_flush_all()`/`tlb_flush_page(virt)`, 그리고 `activate()` —
마지막 세 함수는 각각 내부적으로 아키텍처별로 디스패치된다(x86-64:
`CR3`/`invlpg`; RISC-V: `sfence.vma`/`satp`; AArch64: `TTBR0_EL1`/
`TLBI`).

## 프로세스 제어 블록 (`kernel/process.h`) {#process-control-block-kernelprocessh}

`PCB` — `pid`, `state`(`PROC_READY`/`PROC_RUNNING`/`PROC_BLOCKED`/
`PROC_ZOMBIE`), `priority`, 저장된 `stack_ptr`/`pc`, `page_table`(물리
루트 주소), `parent_pid`, `exit_code`. `pcb_init(pid, entry, sp,
page_table)`; 메서드 `set_state`, `set_priority`, `save_context(sp,
pc)`, `exit(code)`(`PROC_ZOMBIE`로 전이).

## 스케줄러 (`kernel/scheduler.h`) {#scheduler-kernelschedulerh}

`Scheduler` — 최대 `SCHED_MAX_PROCS`(256)개의 PCB에 대한 우선순위
기반 라운드 로빈. `sched_init()`; `spawn_proc(&stack PCB)`(인덱스 반환,
가득 찼으면 `-1`), `next()`(우선순위가 가장 높은 READY 프로세스 선택),
`yield()`(현재 프로세스를 READY로 표시하고 다음 프로세스 선택),
`block_current()`, `unblock(idx)`, `remove(idx)`(좀비 회수),
`ready_count()`.

## IPC 메일박스 (`kernel/ipc.h`) {#ipc-mailbox-kernelipch}

`Mailbox`(`mailbox_init(owner_pid)`로 생성) — 고정 용량
(`MAILBOX_CAPACITY` = 64개 메시지, `MSG_MAX_SIZE` = 256바이트 페이로드)
메시지 큐. `send(sender_pid, type, payload, size)`, `recv(&stack
Message out)`, `peek(&stack Message out)`(비파괴적), `has_msg()`,
`length()`, `clear()`.

## 시스템 콜 테이블 (`kernel/syscall.h`) {#syscall-table-kernelsyscallh}

`SyscallTable`(`syscall_init()`로 생성) — 시그니처가
`long long(long long, long long, long long)`인 핸들러를 최대
`SYSCALL_MAX`(256)개까지 등록. `register_(num, handler)`/
`unregister_(num)`(끝의 밑줄: `register`는 C 키워드이므로),
`dispatch(num, arg0, arg1, arg2)`(핸들러의 결과를 반환하며, 등록되지
않았으면 `-1`), `is_registered(num)`.
